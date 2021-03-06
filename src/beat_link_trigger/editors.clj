(ns beat-link-trigger.editors
  "Provides the user interface for editing expressions that customize
  application behavior."
  (:require [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.help :as help]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.show-util :as show-util]
            [beat-link-trigger.util :as util]
            [clojure.java.browse]
            [clojure.java.io]
            [clojure.string]
            [me.raynes.fs :as fs]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame JTextPane]
           [org.deepsymmetry.beatlink DeviceUpdate Beat CdjStatus]
           [org.fife.ui.rtextarea RTextArea SearchResult]
           [org.fife.ui.rsyntaxtextarea RSyntaxTextArea]))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
(defprotocol IExpressionEditor
  "A window which allows the user to edit Clojure expressions which
  make up the body of a function that customizes application
  behavior."
  (show [this]
    "Make the window visible again if it had been closed or dropped
  behind others.")
  (retitle [this]
    "Update the window title to reflect a new index for its associated
  trigger.")
  (can-close? [this]
    "Check if the editor has no unsaved changes, or if the user is
  willing to abandon them. If so, return truthy. Otherwise return falsey,
  which should abort whatever operation was in process.")
  (dispose [this]
    "Permanently close the window and release its resources, without
  regard to whether there are unsaved changes."))))

(defn sort-setup-to-front
  "Given a sequence of expression keys and value tuples, makes sure that
  if `:shared` or `:setup` keys present, they and their expressions
  are first and second in the sequence, so they get evaluated first,
  in case they define any functions needed to evaluate the other
  expressions. The setup expression should no longer do that, now
  that shared functions are better supported, but this behavior is
  maintained for backwards compatibility with older files."
  [exprs]
  (concat (filter #(= :shared (first %)) exprs) (filter #(= :setup (first %)) exprs)
          (filter #(not (#{:shared :setup} (first %))) exprs)))

(def trigger-bindings
  "Identifies symbols which can be used inside any trigger expression,
  along with the expression that will be used to automatically bind
  that symbol if it is used in the expression, and the documentation
  to show the user what the binding is for."
  {'trigger-comment {:code '(get-in trigger-data [:value :comment])
                     :doc "The descriptive comment about the trigger."}

   'trigger-output {:code '((resolve 'beat-link-trigger.triggers/get-chosen-output) nil trigger-data)
                    :doc "The MIDI output object chosen for this
  trigger. May be <code>nil</code> if the output device cannot be
  found in the current MIDI environment."}

   'trigger-message {:code '(get-in trigger-data [:value :message])
                     :doc "The type of MIDI message the trigger is
  configured to send; one of <code>\"Note\"</code>,
  <code>\"CC\"</code>, <code>\"Clock\"</code>,
  <code>\"Link\"</code>, or <code>\"Custom\"</code>."}

   'trigger-note {:code
                  '(get-in trigger-data [:value :note])
                  :doc "The MIDI note or CC number the trigger is
  configured to send."}

   'trigger-channel {:code '(get-in trigger-data [:value :channel])
                     :doc "The MIDI channel on which the trigger is
  configured to send."}

   'trigger-enabled {:code '(get-in trigger-data [:value :enabled])
                     :doc "The conditions under which the trigger is
  enabled to send MIDI; one of <code>\"Never\"</code>,
  <code>\"On-Air\"</code>, <code>\"Custom\"</code>, or
  <code>\"Always\"</code>."}

   'trigger-active? {:code '(:tripped trigger-data)
                     :doc "Will be <code>true</code> when the trigger
  is enabled and any of the players it is watching are playing."}})

(defn- trigger-bindings-for-class
  "Collects the set of bindings for a trigger editor which is called
  with a particular class of status object. Merges the standard
  trigger convenience bindings with those associated with the
  specified class, which may be `nil`."
  [update-class]
  (merge trigger-bindings (when update-class (expressions/bindings-for-update-class update-class))))

(def global-trigger-editors
  "Specifies the kinds of editor which can be opened for the Trigger
  window overall, along with the details needed to describe and
  compile the expressions they edit. Created as an explicit array map
  to keep the keys in the order they are found here."
  (array-map
   :shared {:title "Shared Functions"
            :tip "The place to define functions used by expressions."
            :description "Compiled before any expressions are, so you
            can define any functions those expressions might find
            useful. This is just ordinary Clojure code that can be
            conveniently edited using an IDE if you turn on the
            embedded nREPL server."}
   :setup {:title    "Global Setup Expression"
           :tip      "Called once to set up any state your trigger expressions may need."
           :description
           "Called once when the triggers are loaded, or when you update
  the expression. Set up any global state (such as counters, flags, or
  network connections) that your expressions within any trigger need.
  Use the Global Shutdown expression to clean up resources when the
  trigger window is shutting down."
           :bindings nil}

   :online {:title    "Came Online Expression"
            :tip      "Called when BLT has succesfully joined a Pro DJ Link network."
            :description
            "Called after the Global Setup Expression when loading a
           Triggers file if Online, or by itself if you have taken BLT
           Online manually. Set up any global state (such as sync
           modes or showing the Player Status window) that can only be
           performed when online. Use the Going Offline expression to
           gracefully disconnect from anything you need to when going
           Offline or when the trigger window is shutting down."
            :bindings {'device-number {:code '(.getDeviceNumber (VirtualCdj/getInstance))
                                       :doc  "The player number we are using when talking to DJ Link devices."}
                       'address       {:code '(.getLocalAddress (VirtualCdj/getInstance))
                                       :doc  "The IP address we are using to talk to DJ Link devices."}}}

   :offline {:title    "Going Offline Expression"
             :tip      "Called when BLT is disconnecting from a Pro DJ Link network."
             :description
             "Called before the Global Shutdown Expression when the
  trigger window is closing or when a new trigger file is being
  loaded, or by itself when you are taking BLT Offline manually.
  Gracefully close and release any shared system resources (such as
  network connections) that you opened in the Came Online
  expression."
             :bindings nil}

   :shutdown {:title    "Global Shutdown Expression"
              :tip      "Called once to release global resources."
              :description
              "Called when when the trigger window is closing, or a
  new trigger file is being loaded. Close and release any shared
  system resources (such as network connections) that you opened in
  the Global Setup expression."
              :bindings nil}))

(def trigger-editors
  "Specifies the kinds of editor which can be opened for a trigger,
  along with the details needed to describe and compile the
  expressions they edit. Created as an explicit array map to keep the
  keys in the order they are found here."
  (array-map
   :setup {:title "Setup Expression"
           :tip "Called once to set up any state your other expressions may need."
           :description
           "Called once when the triggers are loaded, or when you update the
  expression. Set up any state (such as counters, flags, or network
  connections) that your other expressions for this trigger need. Use
  the Shutdown expression to clean up resources when the trigger is
  shutting down."
           :bindings (trigger-bindings-for-class nil)}

   :enabled {:title "Enabled Filter Expression"
             :tip "Called to see if the trigger should be enabled."
             :description
             "Called whenever a status update packet is received from
  the watched player(s) and the trigger's Enabled mode is set to
  Custom. Return a <code>true</code> value as the last expression to
  enable the trigger. The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
             :bindings (trigger-bindings-for-class CdjStatus)}

   :activation {:title "Activation Expression"
                :tip "Called when the trigger becomes enabled and tripped."
                :description
                "Called when the trigger is enabled and the first device that it is
  watching starts playing. You can use this to trigger systems that do
  not respond to MIDI, or to send more detailed information than MIDI
  allows.<p>

  The status update object which caused the trigger to activate, a
  beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
                :bindings (trigger-bindings-for-class CdjStatus)}

   :beat {:title "Beat Expression"
          :tip "Called on each beat from the watched devices."
          :description
          "Called whenever a beat packet is received from the watched
  player(s). You can use this for beat-driven integrations with other
  systems.<p>

  The beat object that was received, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html\"><code>Beat</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
          :bindings (trigger-bindings-for-class Beat)}

   :tracked {:title "Tracked Update Expression"
             :tip "Called for each update from the player a trigger is tracking."
             :description
             "Called whenever a status update packet is received from
  the player a trigger is tracking, after the Enabled Filter
  Expression, if any, has had a chance to decide if the trigger is
  enabled, and after the Activaction or Deactivation expression, if
  appropriate. The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below.
  If you want to only relay updates when the trigger is active (is
  enabled, and the watched player is playing), wrap your code inside a
  <code>when</code> expression conditioned on the
  <code>trigger-active?</code> convenience variable."
             :bindings (trigger-bindings-for-class CdjStatus)}

   :deactivation {:title "Deactivation Expression"
                  :tip "Called when the trigger becomes disabled or idle."
                  :description
                  "Called when the trigger becomes disabled or when the last device it
  is watching stops playing, if it had been active. You can use this
  to trigger systems that do not respond to MIDI, or to send more
  detailed information than MIDI allows.<p>

  The status update object (if any) that caused the trigger to
  deactivate, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described
  below.<p>

  Note that sometimes
  <code>status</code> will be <code>nil</code>, such as when a device
  has disappeared or the trigger settings have been changed, so your
  expression must be able to cope with <code>nil</code> values for all
  the convenience variables that it uses."
                  :bindings (trigger-bindings-for-class CdjStatus)
                  :nil-status? true}

   :shutdown {:title "Shutdown Expression"
              :tip "Called once to release resources your trigger had been using."
              :description
              "Called when when the trigger is shutting down, either because it
  was deleted, the window was closed, or a new trigger file is being
  loaded. Close and release any system resources (such as network
  connections) that you opened in the Setup expression."
              :bindings (trigger-bindings-for-class nil)}))

(defn- show-bindings
  "Identifies symbols which can be used inside any show expression,
  along with the expression that will be used to automatically bind
  that symbol if it is used in the expression, and the documentation
  to show the user what the binding is for."
  []
  {'show {:code '(:show trigger-data)
          :doc (str "All the details known about the show. See the <a href=\""
                    (help/user-guide-link "ShowInternals.html#show")
                    "\">User Guide</a> for details.")}

   'trigger-globals {:code '@(resolve 'beat-link-trigger.triggers/expression-globals)
                     :doc  "The expression globals in the Triggers
  window, in case you want to share values with
  them."}})

(defn- show-bindings-for-class
  "Collects the set of bindings for a show editor which is called with a
  particular class of status object. Merges the standard show
  convenience bindings with those associated with the specified class,
  which may be `nil`."
  [update-class]
  (merge (show-bindings) (when update-class (expressions/bindings-for-update-class update-class))))

(defn- show-bindings-for-track
  "Identifies symbols which can be used inside any show track
  expression, along with the expression that will be used to
  automatically bind that symbol if it is used in the expression, and
  the documentation to show the user what the binding is for."
  []
  {'track {:code '(:track trigger-data)
           :doc (str "All the details known about the track. See the <a href=\""
                    (help/user-guide-link "ShowInternals.html#track")
                    "\">User Guide</a> for details.")}

   'midi-output {:code '((resolve 'beat-link-trigger.show-util/get-chosen-output)
                         (:track trigger-data))
                 :doc "The MIDI output object chosen for this
  track. May be <code>nil</code> if the output device cannot be
  found in the current MIDI environment."}

   'loaded-message {:code '(get-in (:track trigger-data)
                                   [:contents :loaded-message])
                    :doc "The type of MIDI message to be sent when
  the track is loaded; one of <code>\"None\"</code>,
  <code>\"Note\"</code>, <code>\"CC\"</code>, or
  <code>\"Custom\"</code>."}

   'loaded-note {:code '(get-in (:track trigger-data)
                                [:contents :loaded-note])
                 :doc "The MIDI note or CC number sent when the track
  is loaded or unloaded."}

   'loaded-channel {:code '(get-in (:track trigger-data)
                                   [:contents :loaded-channel])
                    :doc "The MIDI channel on which track load and
  unload messages are sent."}

   'loaded-players {:code '(util/players-signature-set (:loaded (:show trigger-data))
                                                       (:signature (:track trigger-data)))
                    :doc "The set of player numbers that currently
  have this track loaded, if any."}

   'playing-message {:code '(get-in (:track trigger-data)
                                    [:contents :playing-message])
                     :doc "The type of MIDI message to be sent when
  the track starts playing; one of <code>\"None\"</code>,
  <code>\"Note\"</code>, <code>\"CC\"</code>, or
  <code>\"Custom\"</code>."}

   'playing-note {:code '(get-in (:track trigger-data)
                                 [:contents :playing-note])
                  :doc "The MIDI note or CC number sent when the track
  starts or stops playing."}

   'playing-channel {:code '(get-in (:track trigger-data)
                                    [:contents :playing-channel])
                     :doc "The MIDI channel on which track playing
  messages are sent."}

   'track-enabled {:code '(let [local (get-in (:track trigger-data)
                                              [:contents :enabled])]
                            (if (= "Default" local)
                              (get-in (:show trigger-data)
                                      [:contents :enabled])
                              local))
                   :doc "The conditions under which the track is
  enabled to send MIDI; one of <code>\"Never\"</code>,
  <code>\"On-Air\"</code>, <code>\"Master\"</code>,
  <code>\"Custom\"</code>, or <code>\"Always\"</code>. (If this track
  is configured as \"Default\", the show's Enabled Default value is
  returned.)"}

   'playing-players {:code '(util/players-signature-set (:playing (:show trigger-data))
                                                        (:signature (:track trigger-data)))
                     :doc "The set of player numbers that are currently
  playing this track, if any."}})

(defn- show-bindings-for-track-and-class
  "Collects the set of bindings for a show track editor which is called
  with a particular class of status object. Merges the standard show
  track convenience bindings with those associated with the specified
  class, which may be `nil`."
  [update-class]
  (merge (show-bindings)
         (show-bindings-for-track)
         (when update-class (expressions/bindings-for-update-class update-class))))

(defn- show-bindings-for-phrase
  "Identifies symbols which can be used inside any show phrase trigger
  expression, along with the expression that will be used to
  automatically bind that symbol if it is used in the expression, and
  the documentation to show the user what the binding is for."
  []
  {'phrase {:code '(:phrase trigger-data)
            :doc  (str "All the details saved about the phrase trigger. See the <a href=\""
                       (help/user-guide-link "ShowInternals.html#phrase-contents")
                       "\">User Guide</a> for details.")}

   'midi-output {:code '((resolve 'beat-link-trigger.show-util/get-chosen-output)
                         show (:phrase trigger-data))
                 :doc  "The MIDI output object chosen for this phrase trigger. May be
  <code>nil</code> if the output device cannot be found in the current
  MIDI environment."}

   'message {:code '(get-in trigger-data [:phrase :message])
             :doc  "The type of MIDI message to be sent when
  a matched phrase starts or stops playing; one of <code>\"None\"</code>,
  <code>\"Note\"</code>, <code>\"CC\"</code>, or
  <code>\"Custom\"</code>."}

   'note {:code '(get-in trigger-data [:phrase :note])
          :doc  "The MIDI note or CC number sent when a matched
  phrase starts or stops playing."}

   'channel {:code '(get-in trigger-data [:phrase :channel])
             :doc  "The MIDI channel on which phrase trigger
  playing messages are sent."}

   ;; TODO: And, current section? Anything else?
   'playing-players {:code '(util/players-phrase-uuid-set (:playing-phrases (:show trigger-data))
                                                          (:uuid (:phrase trigger-data)))
                     :doc  "The set of player numbers that are currently
  playing a phrase that acivated this phrase trigger, if any."}})

(defn- show-bindings-for-phrase-and-class
  "Collects the set of bindings for a show phrase trigger editor which
  is called with a particular class of status object. Merges the
  standard phrase trigger convenience bindings with those associated
  with the specified class, which may be `nil`."
  [update-class]
  (merge (show-bindings)
         (show-bindings-for-phrase)
         (when update-class (expressions/bindings-for-update-class update-class))))

(def global-show-editors
  "Specifies the kinds of editor which can be opened for a Show
  window overall, along with the details needed to describe and
  compile the expressions they edit. Created as an explicit array map
  to keep the keys in the order they are found here."
  (delay
   (array-map
    :shared {:title "Shared Functions"
             :tip "The place to define functions used by expressions."
             :description "Compiled before any expressions are, so you
            can define any functions those expressions might find
            useful. This is just ordinary Clojure code that can be
            conveniently edited using an IDE if you turn on the
            embedded nREPL server."}

    :setup {:title "Global Setup Expression"
            :tip "Called once to set up any state your show expressions may need."
            :description
            "Called once when the show is loaded, or when you update the
  expression. Set up any global state (such as counters, flags, or
  network connections) that your expressions within any track or cue
  need. Use the Global Shutdown expression to clean up resources when
  the show window is shutting down."
            :bindings (show-bindings-for-class nil)}

    :online {:title    "Came Online Expression"
             :tip      "Called when BLT has succesfully joined a Pro DJ Link network."
             :description
             "Called after the Global Setup Expression when loading a
  Show if Online, or by itself if you have taken BLT Online manually.
  Set up any global state (such as sync modes or showing the Player
  Status window) that can only be performed when online. Use the Going
  Offline expression to gracefully disconnect from anything you need
  to when going Offline or when the Show is shutting down."
             :bindings (merge
                        (show-bindings-for-class nil)
                        {'device-number {:code '(.getDeviceNumber (VirtualCdj/getInstance))
                                         :doc  "The player number we are using when talking to DJ Link devices."}
                         'address       {:code '(.getLocalAddress (VirtualCdj/getInstance))
                                         :doc  "The IP address we are using to talk to DJ Link devices."}})}

    :enabled {:title "Default Enabled Filter Expression"
              :tip "Called to see if a track set to Default should be enabled."
              :description
              "Called whenever a status update packet is received from
  a player that has loaded a track whose Enabled mode is set to
  Default, when the show itself has chosen Custom as its Enabled
  Default. Return a <code>true</code> value as the last expression to
  enable the track. The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
              :bindings (show-bindings-for-class CdjStatus)}

    :offline {:title    "Going Offline Expression"
              :tip      "Called when BLT is disconnecting from a Pro DJ Link network."
              :description
              "Called before the Global Shutdown Expression when the
  Show window is closing, or by itself when you are taking BLT Offline
  manually. Gracefully close and release any shared system
  resources (such as network connections) that you opened in the Came
  Online expression."
              :bindings (show-bindings-for-class nil)}

    :shutdown {:title "Global Shutdown Expression"
               :tip "Called once to release global resources."
               :description
               "Called when when the show window is closing. Close and
  release any shared system resources (such as network connections)
  that you opened in the Global Setup expression."
               :bindings (show-bindings-for-class nil)})))

(def show-track-editors
  "Specifies the kinds of editor which can be opened for a show track,
  along with the details needed to describe and compile the
  expressions they edit. Created as an explicit array map to keep the
  keys in the order they are found here."
  (delay (array-map
          :setup {:title "Setup Expression"
                  :tip "Called once to set up any state your other expressions may need."
                  :description
                  "Called once when the show is loaded, or when you update the
  expression. Set up any state (such as counters, flags, or network
  connections) that your other expressions for this track need. Use
  the Shutdown expression to clean up resources when the show is
  shutting down."
                  :bindings (show-bindings-for-track-and-class nil)}

          :enabled {:title "Enabled Filter Expression"
                    :tip "Called to see if the track should be enabled."
                    :description
                    "Called whenever a status update packet is received from
  a player that has loaded a track whose Enabled mode is set to
  Custom. Return a <code>true</code> value as the last expression to
  enable the track. The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
                    :bindings (show-bindings-for-track-and-class CdjStatus)}

          :loaded {:title "Loaded Expression"
                   :tip "Called when a player loads this track, if enabled."
                   :description
                   "Called when the track is enabled and the first player loads
  this track. You can use this to trigger systems that do
  not respond to MIDI, or to send more detailed information than MIDI
  allows."
                   :bindings (show-bindings-for-track-and-class nil)}

          :playing {:title "Playing Expression"
                    :tip "Called when a player plays this track, if enabled."
                    :description
                    "Called when the track is enabled and the first player starts
  playing this track. You can use this to trigger systems that do
  not respond to MIDI, or to send more detailed information than MIDI
  allows.<p>

  The status update object which reported the track starting to play, a
  beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
                    :bindings (show-bindings-for-track-and-class CdjStatus)}

          :beat {:title "Beat Expression"
                 :tip "Called on each beat from devices with the track loaded."
                 :description "Called whenever a beat packet is received from
  a player that is playing this track. You can use this for
  beat-driven integrations with other systems.<p>

  A tuple containing the raw beat object that was received and the
  player track position inferred from it (a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TrackPositionUpdate.html\"><code>TrackPositionUpdate</code></a>
  object), is available as <code>status</code>, and you can use normal
  Clojure destructuring and <a
  href=\"http://clojure.org/reference/java_interop\">Java interop
  syntax</a> to access its fields and methods, but it is generally
  easier to use the convenience variables described below."
                 :bindings (show-bindings-for-track-and-class :beat-tpu)}

          :tracked {:title "Tracked Update Expression"
                    :tip "Called for each update from a player with this track loaded, when enabled."
                    :description
                    "Called whenever a status update packet is received from
  a player that has this track loaded, after the Enabled Filter
  Expression, if any, has had a chance to decide if the track is
  enabled, and after the Loaded, Playing, Stopped, or Unloaded
  expression, if appropriate. The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below.
  If you want to only relay updates when the track is active (is
  enabled, and at least one player is playing), wrap your code inside a
  <code>when</code> expression conditioned on the
  <code>playing-players</code> convenience variable."
                    :bindings (show-bindings-for-track-and-class CdjStatus)}

          :stopped {:title "Stopped Expression"
                    :tip "Called when all players stop playing the track, or the track is disabled."
                    :description "Called when the track becomes disabled or when the last
  player stops playing the track, if any had been. You can use this
  to trigger systems that do not respond to MIDI, or to send more
  detailed information than MIDI allows.<p>

  The status update object (if any) that reported playback stopping, a
  beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described
  below.<p>

  Note that sometimes
  <code>status</code> will be <code>nil</code>, such as when a device
  has disappeared or the track settings have been changed, so your
  expression must be able to cope with <code>nil</code> values for all
  the convenience variables that it uses."
                    :bindings (show-bindings-for-track-and-class CdjStatus)
                    :nil-status? true}

          :unloaded {:title "Unloaded Expression"
                     :tip "Called when all players unload the track, or the track is disabled."
                     :description "Called when the track becomes disabled or when the last
  player unloads the track, if any had it loaded. You can use this
  to trigger systems that do not respond to MIDI, or to send more
  detailed information than MIDI allows."
                     :bindings (show-bindings-for-track-and-class nil)}

          :shutdown {:title "Shutdown Expression"
                     :tip "Called once to release resources your track had been using."
                     :description
                     "Called when when the track is shutting down, either
  because it was deleted or the show was closed. Close and release any
  system resources (such as network connections) that you opened in
  the Setup expression."
                     :bindings (show-bindings-for-track-and-class nil)})))

(defn- show-bindings-for-track-or-phrase-and-cue
  "Identifies symbols which can be used inside any show track or phrase
  trigger cue expression, along with the expression that will be used
  to automatically bind that symbol if it is used in the expression,
  and the documentation to show the user what the binding is for."
  []
  {'track {:code '(:track trigger-data)
           :doc (str "All the details known about the track, if this cue is
  in a track. See the <a href=\""
                    (help/user-guide-link "ShowInternals.html#track")
                    "\">User Guide</a> for details.")}
   'phrase {:code '(:phrase trigger-data)
           :doc (str "All the details saved about the phrase trigger, if this
  cue is in a phrase trigger. See the <a href=\""
                    (help/user-guide-link "ShowInternals.html#phrase-contents")
                    "\">User Guide</a> for details.")}
   'midi-output {:code '((resolve 'beat-link-trigger.show-util/get-chosen-output)
                         (or (:track trigger-data) (:phrase trigger-data)))
                 :doc "The MIDI output object chosen for this
  track or phrase trigger. May be <code>nil</code> if the output device cannot be
  found in the current MIDI environment."}
   'cue {:code '(:cue trigger-data)
         :doc (str "All the details known about the cue. See the <a href=\""
                    (help/user-guide-link "ShowInternals.html#cue")
                    "\">User Guide</a> for details.")}

   'entered-message {:code '(get-in trigger-data [:cue :events :entered :message])
                     :doc  "The type of MIDI message to be sent when
  at least one player moves inside the cue; one of <code>\"None\"</code>,
  <code>\"Note\"</code>, <code>\"CC\"</code>, or
  <code>\"Custom\"</code>."}

   'entered-note {:code '(get-in trigger-data [:cue :events :entered :note])
                  :doc  "The MIDI note or CC number sent when the cue is entered or exited."}

   'entered-channel {:code '(get-in trigger-data [:cue :events :entered :channel])
                     :doc  "The MIDI channel on which cue enter and exit messages are sent."}

   'players-inside {:code '((resolve 'beat-link-trigger.show/players-inside-cue)
                            (or (:track trigger-data) (:phrase trigger-data)) (:cue trigger-data))
                    :doc  "The set of player numbers that are currently
  positioned inside this cue, if any."}

   'started-on-beat-message {:code '(get-in trigger-data [:cue :events :started-on-beat :message])
                             :doc  "The type of MIDI message to be sent when
  a player starts playing the cue on its first beat; one of <code>\"None\"</code>,
  <code>\"Note\"</code>, <code>\"CC\"</code>, or <code>\"Custom\"</code>."}

   'started-on-beat-note {:code '(get-in trigger-data [:cue :events :started-on-beat :note])
                          :doc  "The MIDI note or CC number sent when the cue
  is started on its first beat, or ended after that has occurred."}

   'started-on-beat-channel {:code '(get-in trigger-data [:cue :events :started-on-beat :channel])
                             :doc  "The MIDI channel on which on-beat cue start and end messages are sent."}

   'started-late-message {:code '(get-in trigger-data [:cue :events :started-late :message])
                          :doc  "The type of MIDI message to be sent when
  a player starts playing the cue past its first beat; one of <code>\"None\"</code>,
  <code>\"Note\"</code>, <code>\"CC\"</code>, or <code>\"Custom\"</code>."}

   'started-late-note {:code '(get-in trigger-data [:cue :events :started-late :note])
                       :doc  "The MIDI note or CC number sent when the cue
  is started past its first beat, or ended after that has occurred."}

   'started-late-channel {:code '(get-in trigger-data [:cue :events :started-late :channel])
                          :doc  "The MIDI channel on which late cue start and end messages are sent."}

   'players-playing {:code '((resolve 'beat-link-trigger.show/players-playing-cue)
                             (or (:track trigger-data) (:phrase trigger-data)) (:cue trigger-data))
                     :doc  "The set of player numbers currently playing this cue, if any."}

   ;; TODO: Copy in lots of other status and/or beat information with safe finders?
   })

(def show-phrase-editors
  "Specifies the kinds of editor which can be opened for a show phrase
  trigger, along with the details needed to describe and compile the
  expressions they edit. Created as an explicit array map to keep the
  keys in the order they are found here."
  (delay (array-map
          :setup {:title "Setup Expression"
                  :tip "Called once to set up any state your other expressions may need."
                  :description
                  "Called once when the show is loaded, or when you update the
  expression. Set up any state (such as counters, flags, or network
  connections) that your other expressions for this phrase trigger
  need. Use the Shutdown expression to clean up resources when the
  show is shutting down."
                  :bindings (show-bindings-for-phrase-and-class nil)}

          :enabled {:title "Enabled Filter Expression"
                    :tip "Called to see if phrase trigger should be enabled, and what to use when picking it."
                    :description "Called whenever a new phrase starts playing on a
  player if the phrase trigger's Enabled mode has been set to
  Custom. Return a <code>true</code> value as the last expression to
  enable the phrase trigger with weight 1, or a postive number to
  enable it with that weight (it will be rounded to an integer, and
  clipped to a maximum value of 1,000). The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
                    ;; TODO: Set up special bindings that include track mood, phrase type keywords, see :beat-tpu.
                    :bindings (show-bindings-for-phrase-and-class CdjStatus)}

          :playing {:title "Playing Expression"
                    :tip "Called when a player starts playing a phrase matching this trigger, if it was chosen."
                    :description
                    "Called when the phrase trigger is enabled by a phrase
  that has just started to play, and this trigger was selected
  as the one to run. You can use this to activate systems that do
  not respond to MIDI, or to send more detailed information than MIDI
  allows.<p>

  The status update object which reported the phrase starting to play, a
  beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
                    ;; TODO: Same special bindings as enabled filter?
                    :bindings (show-bindings-for-phrase-and-class CdjStatus)}

          :beat {:title "Beat Expression"
                 :tip "Called on each beat from devices playing the matched phrase."
                 :description "Called whenever a beat packet is received from
  a player that is playing the phrase that activated this trigger. You can use this for
  beat-driven integrations with other systems.<p>

  A tuple containing the raw beat object that was received and the
  player track position inferred from it (a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TrackPositionUpdate.html\"><code>TrackPositionUpdate</code></a>
  object), is available as <code>status</code>, and you can use normal
  Clojure destructuring and <a
  href=\"http://clojure.org/reference/java_interop\">Java interop
  syntax</a> to access its fields and methods, but it is generally
  easier to use the convenience variables described below."
                 :bindings (show-bindings-for-phrase-and-class :beat-tpu)}

          :tracked {:title "Tracked Update Expression"
                    :tip "Called for each update from a player playing the matched phrase."
                    :description
                    "Called whenever a status update packet is received from
  a player that is playing the phrase that activated this phrase trigger, after the Enabled Filter
  Expression, if any, has had a chance to decide the phrase trigger is
  enabled, and after the Playing or Stopped expression, if appropriate.
  The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
                    :bindings (show-bindings-for-phrase-and-class CdjStatus)}

          :stopped {:title "Stopped Expression"
                    :tip "Called when all players stop playing the matched phrase."
                    :description "Called when the last player stops
  playing the matched phrase, if any had been. You can use this
  to deactivate systems that do not respond to MIDI, or to send more
  detailed information than MIDI allows.<p>

  The status update object (if any) that reported the end of the phrase, a
  beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described
  below.<p>

  Note that sometimes
  <code>status</code> will be <code>nil</code>, such as when a device
  has disappeared or the phrase settings have been changed, so your
  expression must be able to cope with <code>nil</code> values for all
  the convenience variables that it uses."
                    :bindings (show-bindings-for-phrase-and-class CdjStatus)
                    :nil-status? true}

          :shutdown {:title "Shutdown Expression"
                     :tip "Called once to release resources your phrase trigger had been using."
                     :description
                     "Called when when the phrase trigger is shutting down, either
  because it was deleted or the show was closed. Close and release any
  system resources (such as network connections) that you opened in
  the Setup expression."
                     :bindings (show-bindings-for-phrase-and-class nil)})))

(defn- show-bindings-for-track-or-phrase-cue-and-class
  "Collects the set of bindings for a show track cue editor which is
  called with a particular class of status object. Merges the standard
  show, track, and cue convenience bindings with those associated with
  the specified class, which may be `nil`."
  [update-class]
  (merge (show-bindings)
         (show-bindings-for-track-or-phrase-and-cue)
         (when update-class (expressions/bindings-for-update-class update-class))))

(def show-cue-editors
  "Specifies the kinds of editor which can be opened for a show track cue,
  along with the kinds of details needed to compile the expressions
  they edit. Created as an explicit array map to keep the keys in the
  order they are found here."
  (delay (array-map
          :entered {:title    "Entered Expression"
                    :tip      "Called when a player moves inside this cue, if the track is enabled."
                    :description
                    "Called when the track is enabled and the first player
  moves inside this cue. You can use this to trigger systems that do
  not respond to MIDI, or to send more detailed information than MIDI
  allows."
                    :bindings (show-bindings-for-track-or-phrase-cue-and-class DeviceUpdate)}

          :started-on-beat {:title    "Started On-Beat Expression"
                            :tip
                            "Called when a player starts playing this cue from its first beat, if the track is enabled."
                            :description
                            "Called when the track is enabled and the first
  player starts playing the cue from the beginning of its first beat.
  You can use this to trigger systems that do not respond to MIDI, or
  to send more detailed information than MIDI allows.<p>

  A tuple containing the raw beat object that was received and the
  player track position inferred from it (a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TrackPositionUpdate.html\"><code>TrackPositionUpdate</code></a>
  object), is available as <code>status</code>, and you can use normal
  Clojure destructuring and <a
  href=\"http://clojure.org/reference/java_interop\">Java interop
  syntax</a> to access its fields and methods, but it is generally
  easier to use the convenience variables described below.<p>

  Also note that if the Cue's Started Late message is set to Same,
  this same expression will be called when the cue starts late, in
  which case <code>status</code> will contain a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, as described in the help for the Started Late expression,
  instead of the above-described tuple, so you will need to write your
  code to handle both possibilities."
                            :bindings (show-bindings-for-track-or-phrase-cue-and-class :beat-tpu)}
          :started-late {:title    "Started Late Expression"
                         :tip "Called when a player starts playing this cue later than its first beat, if the track is enabled."
                         :description
                         "Called when the track is enabled and the first player
  starts playing the cue from somewhere other than the beginning of
  its first beat. You can use this to trigger systems that do not
  respond to MIDI, or to send more detailed information than MIDI
  allows.<p>

  The status update object that caused us to notice that the cue had
  started late (a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object) is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described
  below."
                         :bindings (show-bindings-for-track-or-phrase-cue-and-class DeviceUpdate)}

          :beat   {:title "Beat Expression"
                   :tip   "Called on each beat from devices playing inside the cue."
                   :description
                   "Called whenever a beat packet is received from a player
  that is playing this cue (other than for the beat that started the
  cue, if any, which will have called the started-on-beat or
  started-late expression). You can use this for beat-driven
  integrations with other systems.<p>

  A tuple containing the raw beat object that was received and the
  player track position inferred from it (a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TrackPositionUpdate.html\"><code>TrackPositionUpdate</code></a>
  object), is available as <code>status</code>, and you can use normal
  Clojure destructuring and <a
  href=\"http://clojure.org/reference/java_interop\">Java interop
  syntax</a> to access its fields and methods, but it is generally
  easier to use the convenience variables described below."
                   :bindings (show-bindings-for-track-or-phrase-cue-and-class :beat-tpu)}

          :tracked {:title "Tracked Update Expression"
                    :tip "Called for each update from a player that is positioned inside the cue, when the track is enabled."
                    :description
                    "Called whenever a status update packet is received from
  a player whose playback position is inside the cue (as long as the
  track is enabled), and after calling the entered or started
  expression, if appropriate. The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below.
  If you want to only relay updates when the cue is active (at least
  one player is playing it), wrap your code inside a <code>when</code>
  expression conditioned on the <code>players-playing</code>
  convenience variable."
                    :bindings (show-bindings-for-track-or-phrase-cue-and-class CdjStatus)}

          :ended {:title "Ended Expression"
                  :tip   "Called when all players stop playing this cue, if the track is enabled."
                  :description
                  "Called when the track is enabled and the last player that
  had been playing this cue leaves it or stops playing. You can use
  this to trigger systems that do not respond to MIDI, or to send more
  detailed information than MIDI allows.<p>

  Note that sometimes <code>status</code> will be <code>nil</code>,
  such as when the track becomes disabled or the cue settings have
  been changed, so your expression must be able to cope with
  <code>nil</code> values for all the convenience variables that it
  uses."
                  :bindings    (show-bindings-for-track-or-phrase-cue-and-class DeviceUpdate)
                  :nil-status? true}

          :exited {:title "Exited Expression"
                   :tip   "Called when all players move outside this cue, if the track is enabled."
                   :description
                   "Called when the track is enabled and the last player that
  had been inside this cue moves back out of it. You can use this to
  trigger systems that do not respond to MIDI, or to send more
  detailed information than MIDI allows.<p>

  Note that sometimes <code>status</code> will be <code>nil</code>,
  such as when the track becomes disabled or the cue settings have
  been changed, so your expression must be able to cope with
  <code>nil</code> values for all the convenience variables that it
  uses."
                   :bindings    (show-bindings-for-track-or-phrase-cue-and-class DeviceUpdate)
                   :nil-status? true})))

(def ^:private editor-theme
  "The color theme to use in the code editor, so it can match the
  overall application look."
  (delay (seesaw/invoke-now
          (with-open [s (clojure.java.io/input-stream
                         (clojure.java.io/resource "org/fife/ui/rsyntaxtextarea/themes/dark.xml"))]
            (org.fife.ui.rsyntaxtextarea.Theme/load s)))))

(defn trigger-index
  "Returns the index number associated with the trigger, for use in
  numbering editor windows."
  [trigger]
  (let [index (:index (seesaw/value trigger))]
    (subs index 0 (dec (count index)))))

(defn triggers-editor-title
  "Determines the title for a triggers editor window. If it is from an
  individual trigger, identifies it as such."
  [kind trigger global?]
  (let [title (get-in (if global? global-trigger-editors trigger-editors) [kind :title])]
    (if global?
      title
      (str "Trigger " (trigger-index trigger) " " title))))

(defn update-triggers-expression
  "Called when a triggers window expression's editor is ending and the
  user has asked to update the expression with the value they have
  edited. If `update-fn` is not nil, it will be called with no
  arguments."
  [kind trigger global? text update-fn]
  (swap! (seesaw/user-data trigger) update-in [:expression-fns] dissoc kind) ; In case parse fails, leave nothing there
  (let [text (clojure.string/trim text)  ; Remove whitespace on either end
        editor-info (get (if global? global-trigger-editors trigger-editors) kind)]
    (try
      (when (seq text)  ; If we got a new expression, try to compile it
        (if (= kind :shared)
          (expressions/define-shared-functions text (triggers-editor-title kind trigger global?))
          (swap! (seesaw/user-data trigger) assoc-in [:expression-fns kind]
                 (expressions/build-user-expression text (:bindings editor-info) (:nil-status? editor-info)
                                                    (triggers-editor-title kind trigger global?)))))
      (when-let [editor (get-in @(seesaw/user-data trigger) [:expression-editors kind])]
        (dispose editor)  ; Close the editor
        (swap! (seesaw/user-data trigger) update-in [:expression-editors] dissoc kind))
      (swap! (seesaw/user-data trigger) assoc-in [:expressions kind] text)  ; Save the new text
      (catch Throwable e
        (timbre/error e "Problem parsing" (:title editor-info))
        (seesaw/alert (str "<html>Unable to use " (:title editor-info)
                           ".<br><br>" e
                           (when-let [cause (.getCause e)] (str "<br>Cause: " (.getMessage cause)))
                           "<br><br>You may wish to check the log file for the detailed stack trace.")
                      :title "Exception during Clojure evaluation" :type :error))))
  (when update-fn
    (try
      (update-fn)
      (catch Throwable t
        (timbre/error t "Problem running expression editor update function.")))))

(defn- find-show-expression-text
  "Returns the source code, if any, of the specified show expression
  type for the specified show and track or phrase. If
  `track-or-phrase` is `nil`, `kind` refers to a global expression."
  [kind show track-or-phrase]
  (get-in (show-util/latest-show show) (cond (:signature track-or-phrase)
                                             [:tracks (:signature track-or-phrase) :contents :expressions kind]

                                             (:uuid track-or-phrase)
                                             [:contents :phrases (:uuid track-or-phrase) :expressions kind]

                                             :else
                                             [:contents :expressions kind])))

(defn- find-show-expression-editor
  "Returns the open editor window, if any, for the specified show
  expression type for the specified show and track or phrase. If
  `track-or-phrase` is `nil`, `kind` refers to a global expression."
  [kind show track-or-phrase]
  (get-in (show-util/latest-show show) (cond
                                         (:signature track-or-phrase)
                                         [:tracks (:signature track-or-phrase) :expression-editors kind]

                                         (:uuid track-or-phrase)
                                         [:phrases (:uuid track-or-phrase) :expression-editors kind]

                                         :else
                                         [:expression-editors kind])))

(defn show-editor-title
  "Determines the title for a show expression editor window. If it is
  from an individual track or phrase trigger, identifies it as such."
  [kind show track-or-phrase]
  (let [title (get-in (cond (show-util/track? track-or-phrase)  @show-track-editors
                            (show-util/phrase? track-or-phrase) @show-phrase-editors
                            :else                               @global-show-editors) [kind :title])]
    (cond
      (show-util/track? track-or-phrase)
      (str (or title kind) " for Track “" (get-in track-or-phrase [:metadata :title]) "”")

      (show-util/phrase? track-or-phrase)
      (str (or title kind) " for Phrase Trigger “" (show-util/display-title track-or-phrase) "”")

      :else
      (str "Show “" (fs/base-name (:file show) true) "” " title))))

(defn- update-show-expression
  "Called when an show window expression's editor is ending and the user
  has asked to update the expression with the value they have edited.
  If `update-fn` is not nil, it will be called with no arguments."
  [kind show track-or-phrase text update-fn]

  ;; In case the parse fails, leave nothing as the compiled function.
  (cond (:signature track-or-phrase)
        (show-util/swap-track! track-or-phrase update :expression-fns dissoc kind)

        (:uuid track-or-phrase)
        (show-util/swap-phrase-runtime! show track-or-phrase update :expression-fns dissoc kind)

        :else
        (show-util/swap-show! show update :expression-fns dissoc kind))

  (let [text        (clojure.string/trim text) ; Remove whitespace on either end.
        editor-info (get (cond (:signature track-or-phrase) @show-track-editors
                               (:uuid track-or-phrase)      @show-phrase-editors
                               :else                        @global-show-editors) kind)]
    (try
      (when (seq text)  ; If we got a new expression, try to compile it.
        (if (= kind :shared)
          (expressions/define-shared-functions text (show-editor-title kind show track-or-phrase))
          (let [compiled (expressions/build-user-expression text (:bindings editor-info) (:nil-status? editor-info)
                                                            (show-editor-title kind show track-or-phrase))]
            (cond
              (:signature track-or-phrase)
              (show-util/swap-track! track-or-phrase assoc-in [:expression-fns kind] compiled)

              (:uuid track-or-phrase)
              (show-util/swap-phrase-runtime! show track-or-phrase assoc-in [:expression-fns kind] compiled)

              :else
              (show-util/swap-show! show assoc-in [:expression-fns kind] compiled)))))
      (when-let [editor (find-show-expression-editor kind show track-or-phrase)]
        (dispose editor)  ; Close the editor
        (cond
          (:signature track-or-phrase)
          (show-util/swap-track! track-or-phrase update :expression-editors dissoc kind)

          (:uuid track-or-phrase)
          (show-util/swap-phrase-runtime! show track-or-phrase update :expression-editors dissoc kind)

          :else
          (show-util/swap-show! show update :expression-editors dissoc kind)))
      (cond    ; Save the new text.
        (:signature track-or-phrase)
        (show-util/swap-track! track-or-phrase assoc-in [:contents :expressions kind] text)

        (:uuid track-or-phrase)
        (show-util/swap-phrase! show track-or-phrase assoc-in [:expressions kind] text)

        :else
        (show-util/swap-show! show assoc-in [:contents :expressions kind] text))
      (catch Throwable e
        (timbre/error e "Problem parsing" (:title editor-info))
        (seesaw/alert (str "<html>Unable to use " (:title editor-info)
                           ".<br><br>" e
                           (when-let [cause (.getCause e)] (str "<br>Cause: " (.getMessage cause)))
                           "<br><br>You may wish to check the log file for the detailed stack trace.")
                      :title "Exception during Clojure evaluation" :type :error))))
  (when update-fn
    (try
      (update-fn)
      (catch Throwable t
        (timbre/error t "Problem running expression editor update function.")))))

(defn- find-cue-expression-text
  "Returns the source code, if any, of the specified cue expression
  type for the specified track or phrase trigger and cue."
  [kind context cue]
  (get-in (show-util/find-cue context cue) [:expressions kind]))

(defn- find-cue-expression-editor
  "Returns the open editor window, if any, for the specified show
  expression type for the specified track and cue."
  [kind context cue]
  (let [[_show _context runtime-info] (show-util/latest-show-and-context context)]
       (get-in runtime-info [:cues-editor :expression-editors (:uuid cue) kind])))

(defn track-cue-editor-title
  "Determines the title for a track cue expression editor window."
  [kind track _cue]
  (let [title (get-in @show-cue-editors [kind :title])]
    (str title " for Cue in Track \"" (get-in track [:metadata :title]) "\"")))

(defn phrase-cue-editor-title
  "Determines the title for a cue expression editor window."
  [kind phrase {:keys [section]}]
  (let [title (get-in @show-cue-editors [kind :title])]
    (str title " for Cue in phrase \"" (get-in phrase [:contents :comment] "<uncommented>") "\" " (name section))))

(defn- update-cue-expression
  "Called when an cues editor window expression's editor is ending and
  the user has asked to update the expression with the value they have
  edited. If `update-fn` is not nil, it will be called with no
  arguments."
  [kind context cue text update-fn]
  (let [[show context] (show-util/latest-show-and-context context)
        track?         (show-util/track? context)]
    ;; Clean up any old value first in case the parse fails.
    (if track?
      (show-util/swap-track! context update-in [:cues :expression-fns (:uuid cue)]
                             dissoc kind)
      (show-util/swap-phrase-runtime! show context update-in [:cues :expression-fns (:uuid cue)]
                                      dissoc kind))
    (let [text        (clojure.string/trim text) ; Remove whitespace on either end.
          editor-info (get @show-cue-editors kind)]
      (try
        (when (seq text)  ; If we got a new expression, try to compile it.
          (if track?
            (show-util/swap-track! context assoc-in [:cues :expression-fns (:uuid cue) kind]
                                   (expressions/build-user-expression text (:bindings editor-info)
                                                                      (:nil-status? editor-info)
                                                                      (track-cue-editor-title kind context cue)))
            (show-util/swap-phrase-runtime! show context  assoc-in [:cues :expression-fns (:uuid cue) kind]
                                            (expressions/build-user-expression text (:bindings editor-info)
                                                                               (:nil-status? editor-info)
                                                                               (phrase-cue-editor-title
                                                                                kind context cue)))))
        (when-let [editor (find-cue-expression-editor kind context cue)]
          (dispose editor)  ; Close the editor.
          (if track?
            (show-util/swap-track! context update-in [:cues-editor :expression-editors (:uuid cue)] dissoc kind)
            (show-util/swap-phrase-runtime! show context update-in [:cues-editor :expression-editors (:uuid cue)]
                                            dissoc kind)))
        (show-util/swap-cue! context cue assoc-in [:expressions kind] text)  ; Save the new text.

        (catch Throwable e
          (timbre/error e "Problem parsing" (:title editor-info))
          (seesaw/alert (str "<html>Unable to use " (:title editor-info)
                             ".<br><br>" e
                             (when-let [cause (.getCause e)] (str "<br>Cause: " (.getMessage cause)))
                             "<br><br>You may wish to check the log file for the detailed stack trace.")
                        :title "Exception during Clojure evaluation" :type :error)))))
  (when update-fn
    (try
      (update-fn)
      (catch Throwable t
        (timbre/error t "Problem running expression editor update function.")))))

(def ^:private help-header
  "The HTML header added to style the help text."
  "<html><head><style type=\"text/css\">
body {
  color: white;
  font-family: \"Roboto\", \"Helvetica Neue\", Helvetica, Arial, sans-serif;
  line-height: 1.42857143;
  font-size 16pt;
}
code {
  color: #bbccff;
  font-size: 14pt;
  font-weight: bold;
}
a {
  color: #9999ff;
}
</style></head>")

(defn- triggers-locals-globals
  "Describes trigger locals and globals bindings when available
  available."
  [kind global?]
  (when-not (= kind :shared)
    (clojure.string/join (concat ["<p>The "
                                  (when-not global? "atom
  <code>locals</code> is available for use by all expressions on this
  trigger, and the ")
                                  "atom <code>globals</code> is shared across all expressions in any trigger."]))))

(defn- build-triggers-help
  "Create the help information for a triggers window editor with the
  specified kind."
  [kind global? editors]
  (let [editor-info (get editors kind)]
    (clojure.string/join (concat [help-header "<h1>Description</h1>"
                                  (:description editor-info)
                                  (triggers-locals-globals kind global?)]
                                 (when (seq (:bindings editor-info))
                                      (concat ["

  <h1>Values Available</h1>

  The following values are available for you to use in writing your expression:<dl>"]
                                              (for [[sym spec] (into (sorted-map) (:bindings editor-info))]
                                                (str "<dt><code>" (name sym) "</code></dt><dd>" (:doc spec) "</dd>"))))
                                 ["</dl>"]))))

(defn- confirm-close-if-dirty
  "Checks if an editor window has unsaved changes (which will be
  reflected by the enabled state of the supplied save action). If it
  does, bring the frame to the front and show a modal confirmation
  dialog. Return truthy if the editor should be closed."
  [^JFrame frame save-action]
  (or (not (seesaw/config save-action :enabled?))
      (do
        (.toFront frame)
        (util/confirm frame "Closing will discard the changes you made. Proceed?" :title "Discard Changes?"))))

(defn- build-search-listener
  "Creates the search coordination object that manages find and replace
  capabilities for an editor window."
  ^org.fife.rsta.ui.search.SearchListener [^RSyntaxTextArea editor status-label]
  (proxy [org.fife.rsta.ui.search.SearchListener] []
    (getSelectedText []
      (.getSelectedText editor))
    (searchEvent [^org.fife.rsta.ui.search.SearchEvent e]
      (let [type                 (.getType e)
            context              (.getSearchContext e)
            ^SearchResult result (cond
                                   (= type org.fife.rsta.ui.search.SearchEvent$Type/MARK_ALL)
                                   (org.fife.ui.rtextarea.SearchEngine/markAll editor context)

                                   (= type org.fife.rsta.ui.search.SearchEvent$Type/FIND)
                                   (let [res (org.fife.ui.rtextarea.SearchEngine/find editor context)]
                                     (when-not (.wasFound res)
                                       (.provideErrorFeedback (javax.swing.UIManager/getLookAndFeel) editor))
                                     res)

                                   (= type org.fife.rsta.ui.search.SearchEvent$Type/REPLACE)
                                   (let [res (org.fife.ui.rtextarea.SearchEngine/replace editor context)]
                                     (when-not (.wasFound res)
                                       (.provideErrorFeedback (javax.swing.UIManager/getLookAndFeel) editor))
                                     res)

                                   (= type org.fife.rsta.ui.search.SearchEvent$Type/REPLACE_ALL)
                                   (let [res (org.fife.ui.rtextarea.SearchEngine/replaceAll editor context)]
                                     (seesaw/alert (str (.getCount res) " occurrences replaced.")
                                                   :title "Replace Results")
                                     res)

                                   :else
                                   (timbre/warn "Unrecognized search event type:" type))
            text (if (and result (.wasFound result))
                   (str "Text found; occurences marked: " (.getMarkedCount result) ".")
                   (if (= type org.fife.rsta.ui.search.SearchEvent$Type/MARK_ALL)
                     (if (pos? (.getMarkedCount result))
                       (str "Occurrences marked: " (.getMarkedCount result) ".")
                       "")
                     "Text not found."))]
        #_(timbre/info type context)
        (seesaw/value! status-label text)))))

(defn- build-search-tools
  "Creates the find and replace dialogs and toolbars to work with an
  editor window."
  [^JFrame frame ^RSyntaxTextArea editor status-label]
  (let [listener        (build-search-listener editor status-label)
        find-dialog     (org.fife.rsta.ui.search.FindDialog. frame listener)
        replace-dialog  (org.fife.rsta.ui.search.ReplaceDialog. frame listener)
        find-toolbar    (org.fife.rsta.ui.search.FindToolBar. listener)
        replace-toolbar (org.fife.rsta.ui.search.ReplaceToolBar. listener)
        context         (.getSearchContext find-dialog)]
    (.setSearchContext replace-dialog context)
    (.setSearchContext find-toolbar context)
    (.setSearchContext replace-toolbar context)
    {:find-dialog     find-dialog
     :replace-dialog  replace-dialog
     :find-toolbar    find-toolbar
     :replace-toolbar replace-toolbar}))

(defn- build-find-dialog-action
  "Creates a menu action to show the Find dialog for an editor window."
  [^java.awt.Component find-dialog ^java.awt.Component replace-dialog]
  (seesaw/action :handler (fn [_]
                            (when (.isVisible replace-dialog) (.setVisible replace-dialog false))
                            (.setVisible find-dialog true))
                 :name "Show Find Dialog…"
                 :key "shift menu F"))

(defn- build-replace-dialog-action
  "Creates a menu action to show the Replace dialog for an editor
  window."
  [^java.awt.Component find-dialog ^java.awt.Component replace-dialog]
  (seesaw/action :handler (fn [_]
                            (when (.isVisible find-dialog) (.setVisible find-dialog false))
                            (.setVisible replace-dialog true))
                 :name "Show Replace Dialog…"
                 :key "shift menu R"))

(defn- build-go-to-line-action
  "Creates a menu action to jump to a particular source line for an
  editor window."
  [^JFrame frame ^RSyntaxTextArea editor ^java.awt.Component find-dialog ^java.awt.Component replace-dialog]
  (seesaw/action :handler (fn [_]
                            (when (.isVisible find-dialog) (seesaw/hide! find-dialog))
                            (when (.isVisible replace-dialog) (seesaw/hide! replace-dialog))
                            (let [dialog (org.fife.rsta.ui.GoToDialog. frame)]
                              (.setMaxLineNumberAllowed dialog (.getLineCount editor))
                              (seesaw/show! dialog)
                              (let [line (.getLineNumber dialog)]
                                (when (pos? line)
                                  (try
                                    (.setCaretPosition editor (.getLineStartOffset editor (dec line)))
                                    (catch Exception e
                                      (.provideErrorFeedback (javax.swing.UIManager/getLookAndFeel) editor)
                                      (timbre/error e "Problem going to editor line number")))))))
                 :name "Go To Line…"
                 :key "menu G"))

(defn- build-find-toolbar-action
  "Creates a menu action to show the Find toolbar for an editor window."
  [^org.fife.rsta.ui.CollapsibleSectionPanel editor-panel find-toolbar]
  (let [menu      (.getMenuShortcutKeyMask (.getToolkit editor-panel))
        keystroke (javax.swing.KeyStroke/getKeyStroke java.awt.event.KeyEvent/VK_F menu)
        action (.addBottomComponent editor-panel keystroke find-toolbar)]
    (.putValue action javax.swing.Action/NAME "Find…")
    action))

(defn- build-replace-toolbar-action
  "Creates a menu action to show the Replace toolbar for an editor
  window."
  [^org.fife.rsta.ui.CollapsibleSectionPanel editor-panel replace-toolbar]
  (let [menu      (.getMenuShortcutKeyMask (.getToolkit editor-panel))
        keystroke (javax.swing.KeyStroke/getKeyStroke java.awt.event.KeyEvent/VK_R menu)
        action (.addBottomComponent editor-panel keystroke replace-toolbar)]
    (.putValue action javax.swing.Action/NAME "Replace…")
    action))

(defn- build-load-action
  "Creates a menu action to load an editor window from the contents of a
  file."
  [^JFrame frame ^RSyntaxTextArea editor]
  (seesaw/action :handler (fn [_]
                            (when-let [file (chooser/choose-file frame :all-files? true
                                                                 :filters [["Clojure files" ["clj"]]])]
                              (try
                                (.setText editor (slurp file))
                                (.setCaretPosition editor 0)
                                (catch Exception e
                                  (timbre/error e "Problem loading" file)
                                  (seesaw/alert (str "<html>Unable to Load.<br><br>" e)
                                                :title "Problem Reading File" :type :error)))))
                 :name "Load from File"
                 :key "menu L"))

(defn- build-insert-action
  "Creates a menu action to insert the contents of a file into an editor
  window."
  [^JFrame frame ^RSyntaxTextArea editor]
  (seesaw/action :handler (fn [_]
                            (when-let [file (chooser/choose-file frame :all-files? true
                                                                 :filters [["Clojure files" ["clj"]]])]
                              (try
                                (.insert editor (slurp file) (.getCaretPosition editor))
                                (catch Exception e
                                  (timbre/error e "Problem inserting" file)
                                  (seesaw/alert (str "<html>Unable to Insert File Contents.<br><br>" e)
                                                :title "Problem Reading File" :type :error)))))
                 :name "Insert File Contents"
                 :key "menu I"))

(defn- build-save-action
  "Creates a menu action to save the contents of an editor window to a
  file."
  [^JFrame frame ^RSyntaxTextArea editor]
  (seesaw/action :handler (fn [_]
                            (when-let [file (chooser/choose-file frame :type :save
                                                                 :all-files? false
                                                                 :filters [["Clojure files" ["clj"]]])]
                              (when-let [file (util/confirm-overwrite-file file "clj" frame)]
                                (try
                                  (spit file (.getText editor))
                                  (catch Exception e
                                    (timbre/error e "Problem saving" file)
                                    (seesaw/alert (str "<html>Unable to Save.<br><br>" e)
                                                  :title "Problem Saving File" :type :error))))))
                 :name "Save to File"
                 :key "menu S"))

(defn- build-update-action
  "Creates a menu action to update the expression associated with an
  editor window."
  [^RSyntaxTextArea editor save-fn]
  (seesaw/action :handler (fn [_] (save-fn (.getText editor)))
                 :name "Update"
                 :key "menu U"
                 :enabled? false))

(defn- build-close-action
  "Creates a menu action to close an editor window."
  [^JFrame frame]
  (seesaw/action :handler (fn [_]
                            (.dispatchEvent frame (java.awt.event.WindowEvent.
                                                   frame java.awt.event.WindowEvent/WINDOW_CLOSING)))
                 :name "Close"
                 :key "menu W"))

(defn- build-menubar
  "Creates the menu bar for an editor window."
  [frame editor editor-panel update-action {:keys [find-dialog replace-dialog find-toolbar replace-toolbar]}]
  (seesaw/menubar
   :items [#_(seesaw/menu :text "File" :items (concat [load-action save-action]))  ; TODO: Add save/load capabilities?
           (seesaw/menu :text "File"
                        :items [(build-load-action frame editor)
                                (build-insert-action frame editor)
                                (build-save-action frame editor)
                                (seesaw/separator)
                                update-action
                                (seesaw/separator)
                                (build-close-action frame)])
           (seesaw/menu :text "Edit"
                        :items [(RTextArea/getAction RTextArea/UNDO_ACTION)
                                (RTextArea/getAction RTextArea/REDO_ACTION)
                                (RTextArea/getAction RTextArea/CUT_ACTION)
                                (RTextArea/getAction RTextArea/COPY_ACTION)
                                (RTextArea/getAction RTextArea/PASTE_ACTION)
                                (RTextArea/getAction RTextArea/DELETE_ACTION)
                                (RTextArea/getAction RTextArea/SELECT_ALL_ACTION)])
           (seesaw/menu :text "Search"
                        :items [(build-find-toolbar-action editor-panel find-toolbar)
                                (build-replace-toolbar-action editor-panel replace-toolbar)
                                (seesaw/separator)
                                (build-go-to-line-action frame editor find-dialog replace-dialog)
                                (seesaw/separator)
                                (build-find-dialog-action find-dialog replace-dialog)
                                (build-replace-dialog-action find-dialog replace-dialog)])
           (menus/build-help-menu)]))

(defn- create-triggers-editor-window
  "Create and show a window for editing the Clojure code of a particular
  kind of Triggers window expression, with an update function to be
  called when the editor successfully updates the expression."
  [kind trigger update-fn]
  (let [global?         (:global @(seesaw/user-data trigger))
        text            (get-in @(seesaw/user-data trigger) [:expressions kind])
        save-fn         (fn [text] (update-triggers-expression kind trigger global? text update-fn))
        ^JFrame root    (seesaw/frame :title (triggers-editor-title kind trigger global?) :on-close :nothing
                                      :size [800 :by 600])
        editor          (RSyntaxTextArea. 16 80)
        scroll-pane     (org.fife.ui.rtextarea.RTextScrollPane. editor)
        editor-panel    (org.fife.rsta.ui.CollapsibleSectionPanel.)
        status-label    (seesaw/label)
        tools           (build-search-tools root editor status-label)
        update-action   (build-update-action editor save-fn)
        ^JTextPane help (seesaw/styled-text :id :help :wrap-lines? true)]
    (.add editor-panel scroll-pane)
    (.setSyntaxEditingStyle editor org.fife.ui.rsyntaxtextarea.SyntaxConstants/SYNTAX_STYLE_CLOJURE)
    (.setCodeFoldingEnabled editor true)
    (.setMarkOccurrences editor true)
    (.apply ^org.fife.ui.rsyntaxtextarea.Theme @editor-theme editor)
    (seesaw/config! help :editable? false)
    (seesaw/config! root :content (mig/mig-panel :items [[editor-panel
                                                          "push 2, span 3, grow 100 100, wrap, sizegroup a"]

                                                         [status-label "align left, sizegroup b"]
                                                         [update-action "align center, push"]
                                                         [(seesaw/label "") "align right, sizegroup b, wrap"]

                                                         [(seesaw/scrollable help :hscroll :never)
                                                          "span 3, sizegroup a, gapy unrelated, width 100%"]]))
    (seesaw/config! root :menubar (build-menubar root editor editor-panel update-action tools))
    (seesaw/config! editor :id :source)
    (seesaw/listen editor #{:remove-update :insert-update :changed-update}
                   (fn [e]
                     (seesaw/config! update-action :enabled?
                                     (not= (util/remove-blanks (get-in @(seesaw/user-data trigger) [:expressions kind]))
                                           (util/remove-blanks (seesaw/text e))))))
    (seesaw/value! root {:source text})
    (.setCaretPosition editor 0)
    (.discardAllEdits editor)
    (.setContentType help "text/html")
    (.setText help (build-triggers-help kind global? (if global? global-trigger-editors trigger-editors)))
    (seesaw/scroll! help :to :top)
    (seesaw/config! help :background :black)
    (seesaw/listen help :hyperlink-update
                   (fn [^javax.swing.event.HyperlinkEvent e]
                     (let [type (.getEventType e)
                           url  (.getURL e)]
                       (when (= type (javax.swing.event.HyperlinkEvent$EventType/ACTIVATED))
                         (clojure.java.browse/browse-url url)))))
    (seesaw/listen root :window-closing (fn [_] (when (confirm-close-if-dirty root update-action)
                                                  (swap! (seesaw/user-data trigger) update-in [:expression-editors]
                                                         dissoc kind)
                                                  (.dispose root))))
    (let [result
          (reify IExpressionEditor
            (retitle [_]
              (seesaw/config! root :title (triggers-editor-title kind trigger global?)))
            (show [_]
              (.setLocationRelativeTo root trigger)
              (seesaw/show! root)
              (.toFront root))
            (can-close? [_]  (confirm-close-if-dirty root update-action))
            (dispose [_]
              (swap! (seesaw/user-data trigger) update-in [:expression-editors] dissoc kind)
              (seesaw/dispose! root)))]
      (swap! (seesaw/user-data trigger) assoc-in [:expression-editors kind] result)
      result)))

(defn show-trigger-editor
  "Find or create the editor for the specified kind of expression
  associated with the specified trigger, make it visible, and add it
  to the trigger's list of active editors. Register an update function
  to be invoked with no arguments when the user has successfully
  updated the expression. Also supports being passed the top-level
  trigger frame, rather than an individual trigger, for editing global
  expressions."
  [kind trigger update-fn]
  (try
    (let [editor (or (get-in @(seesaw/user-data trigger) [:expression-editors kind])
                     (create-triggers-editor-window kind trigger update-fn))]
      (show editor))
    (catch Exception e
      (timbre/error e "Problem showing trigger" kind "editor"))))

(defn- show-locals-globals
  "Describes show locals and globals bindings when available
  available."
  [kind global?]
  (when-not (= kind :shared)
    (clojure.string/join (concat ["<p>The "
                                  (when-not global? "atom
  <code>locals</code> is available for use by all expressions on this
  track, and the ")
                                  "atom <code>globals</code> is shared
  across all expressions in this show. You can also use the atom
  <code>trigger-globals</code> to share the expression globals of the
  Triggers window."]))))

(defn- build-show-help
  "Create the help information for a show window editor with the
  specified kind."
  [kind global? editors]
  (let [editor-info (get editors kind)]
    (clojure.string/join (concat [help-header "<h1>Description</h1>"
                                  (:description editor-info)
                                  (show-locals-globals kind global?)]
                                 (when (seq (:bindings editor-info))
                                   (concat ["

  <h1>Values Available</h1>

  The following values are available for you to use in writing your expression:<dl>"]
                                           (for [[sym spec] (into (sorted-map) (:bindings editor-info))]
                                             (str "<dt><code>" (name sym) "</code></dt><dd>" (:doc spec) "</dd>"))))
                                 ["</dl>"]))))

(defn- create-show-editor-window
  "Create and show a window for editing the Clojure code of a particular
  kind of Show window expression, with an update function to be
  called when the editor successfully updates the expression."
  [kind show track-or-phrase parent-frame update-fn]
  (let [text            (find-show-expression-text kind show track-or-phrase)
        save-fn         (fn [text] (update-show-expression kind show track-or-phrase text update-fn))
        ^JFrame root    (seesaw/frame :title (show-editor-title kind show track-or-phrase)
                                      :on-close :nothing :size [800 :by 600])
        editor          (org.fife.ui.rsyntaxtextarea.RSyntaxTextArea. 16 80)
        scroll-pane     (org.fife.ui.rtextarea.RTextScrollPane. editor)
        editor-panel    (org.fife.rsta.ui.CollapsibleSectionPanel.)
        status-label    (seesaw/label)
        tools           (build-search-tools root editor status-label)
        update-action   (build-update-action editor save-fn)
        ^JTextPane help (seesaw/styled-text :id :help :wrap-lines? true)
        close-fn        (fn [] (cond (:signature track-or-phrase)
                                     (show-util/swap-track! track-or-phrase update :expression-editors dissoc kind)

                                     (:uuid track-or-phrase)
                                     (show-util/swap-phrase-runtime! show track-or-phrase
                                                                     update :expression-editors dissoc kind)
                                     :else
                                     (show-util/swap-show! show update :expression-editors dissoc kind)))]
    (.add editor-panel scroll-pane)
    (.setSyntaxEditingStyle editor org.fife.ui.rsyntaxtextarea.SyntaxConstants/SYNTAX_STYLE_CLOJURE)
    (.setCodeFoldingEnabled editor true)
    (.setMarkOccurrences editor true)
    (.apply ^org.fife.ui.rsyntaxtextarea.Theme @editor-theme editor)
    (seesaw/listen editor #{:remove-update :insert-update :changed-update}
                   (fn [e]
                     (seesaw/config! update-action :enabled?
                                     (not= (util/remove-blanks (find-show-expression-text kind show track-or-phrase))
                                           (util/remove-blanks (seesaw/text e))))))
    (seesaw/config! help :editable? false)
    (seesaw/config! root :content (mig/mig-panel :items [[editor-panel
                                                          "push 2, span 3, grow 100 100, wrap, sizegroup a"]

                                                         [status-label "align left, sizegroup b"]
                                                         [update-action "align center, push"]
                                                         [(seesaw/label "") "align right, sizegroup b, wrap"]

                                                         [(seesaw/scrollable help :hscroll :never)
                                                          "span 3, sizegroup a, gapy unrelated, width 100%"]]))
    (seesaw/config! root :menubar (build-menubar root editor editor-panel update-action tools))
    (seesaw/config! editor :id :source)
    (seesaw/value! root {:source text})
    (.setCaretPosition editor 0)
    (.discardAllEdits editor)
    (.setContentType help "text/html")
    (.setText help (build-show-help kind (not track-or-phrase)
                                    (cond (:signature track-or-phrase) @show-track-editors
                                          (:uuid track-or-phrase)      @show-phrase-editors
                                          :else                        @global-show-editors)))
    (seesaw/scroll! help :to :top)
    (seesaw/config! help :background :black)
    (seesaw/listen help :hyperlink-update
                   (fn [^javax.swing.event.HyperlinkEvent e]
                     (let [type (.getEventType e)
                           url  (.getURL e)]
                       (when (= type (javax.swing.event.HyperlinkEvent$EventType/ACTIVATED))
                         (clojure.java.browse/browse-url url)))))
    (seesaw/listen root :window-closing (fn [_] (when (confirm-close-if-dirty root update-action)
                                                  (close-fn)
                                                  (.dispose root))))
    (let [result
          (reify IExpressionEditor
            (retitle [_]
              (seesaw/config! root :title (show-editor-title kind show track-or-phrase)))
            (show [_]
              (.setLocationRelativeTo root parent-frame)
              (seesaw/show! root)
              (.toFront root))
            (can-close? [_] (confirm-close-if-dirty root update-action))
            (dispose [_]
              (close-fn)
              (seesaw/dispose! root)))]
      (cond
        (:signature track-or-phrase)
        (show-util/swap-track! track-or-phrase assoc-in [:expression-editors kind] result)

        (:uuid track-or-phrase)
        (show-util/swap-phrase-runtime! show track-or-phrase assoc-in [:expression-editors kind] result)

        :else
        (show-util/swap-show! show assoc-in [:expression-editors kind] result))
      result)))

(defn show-show-editor
  "Find or create the editor for the specified kind of expression
  associated with the specified show (and optionally track or phrase),
  make it visible, and add it to the show's list of active editors.
  Register an update function to be invoked with no arguments when the
  user has successfully updated the expression. If `track-or-phrase`
  is `nil` we are editing global expressions."
  [kind show-map track-or-phrase parent-frame update-fn]
  ;; We need to use `show-map` instead of `show` as the argument name so we can call the show function
  ;; defined in the editor's `IExpressionEditor` implemntation. D'ohh!
  (try
    (let [editor (or (get-in show-map (cond
                                        (:signature track-or-phrase)
                                        [:tracks (:signature track-or-phrase) :expression-editors kind]

                                        (:uuid track-or-phrase)
                                        [:phrases (:uuid track-or-phrase) :expression-editors kind]

                                        :else
                                        [:expression-editors kind]))
                     (create-show-editor-window kind show-map track-or-phrase parent-frame update-fn))]
      (show editor))
    (catch Throwable t
      (timbre/error t "Problem showing show" kind "editor"))))

(defn- create-cue-editor-window
  "Create and show a window for editing the Clojure code of a particular
  kind of Cues Editor window expression, with an update function to be
  called when the editor successfully updates the expression."
  [kind context cue parent-frame update-fn]
  (let [text            (find-cue-expression-text kind context cue)
        track?          (show-util/track? context)
        save-fn         (fn [text] (update-cue-expression kind context cue text update-fn))
        ^JFrame root    (seesaw/frame :title (track-cue-editor-title kind context cue)
                                      :on-close :nothing
                                      :size [800 :by 600])
        editor          (org.fife.ui.rsyntaxtextarea.RSyntaxTextArea. 16 80)
        scroll-pane     (org.fife.ui.rtextarea.RTextScrollPane. editor)
        editor-panel    (org.fife.rsta.ui.CollapsibleSectionPanel.)
        status-label    (seesaw/label)
        tools           (build-search-tools root editor status-label)
        update-action   (build-update-action editor save-fn)
        ^JTextPane help (seesaw/styled-text :id :help :wrap-lines? true)
        close-fn        (fn []
                          (if track?
                            (show-util/swap-track! context update-in [:cues-editor :expression-editors (:uuid cue)]
                                                   dissoc kind)
                            (show-util/swap-phrase-runtime! (show-util/show-from-phrase context) context
                                                            update-in [:cues-editor :expression-editors (:uuid cue)]
                                                            dissoc kind)))]
    (.add editor-panel scroll-pane)
    (.setSyntaxEditingStyle editor org.fife.ui.rsyntaxtextarea.SyntaxConstants/SYNTAX_STYLE_CLOJURE)
    (.setCodeFoldingEnabled editor true)
    (.setMarkOccurrences editor true)
    (.apply ^org.fife.ui.rsyntaxtextarea.Theme @editor-theme editor)
    (seesaw/listen editor #{:remove-update :insert-update :changed-update}
                   (fn [e]
                     (seesaw/config! update-action :enabled?
                                     (not= (util/remove-blanks (find-cue-expression-text kind context cue))
                                           (util/remove-blanks (seesaw/text e))))))
    (seesaw/config! help :editable? false)
    (seesaw/config! root :content (mig/mig-panel :items [[editor-panel
                                                          "push 2, span 3, grow 100 100, wrap, sizegroup a"]

                                                         [status-label "align left, sizegroup b"]
                                                         [update-action "align center, push"]
                                                         [(seesaw/label "") "align right, sizegroup b, wrap"]

                                                         [(seesaw/scrollable help :hscroll :never)
                                                          "span 3, sizegroup a, gapy unrelated, width 100%"]]))
    (seesaw/config! root :menubar (build-menubar root editor editor-panel update-action tools))
    (seesaw/config! editor :id :source)
    (seesaw/value! root {:source text})
    (.setCaretPosition editor 0)
    (.discardAllEdits editor)
    (.setContentType help "text/html")
    (.setText help (build-show-help kind false @show-cue-editors))
    (seesaw/scroll! help :to :top)
    (seesaw/config! help :background :black)
    (seesaw/listen help :hyperlink-update
                   (fn [^javax.swing.event.HyperlinkEvent e]
                     (let [type (.getEventType e)
                           url  (.getURL e)]
                       (when (= type (javax.swing.event.HyperlinkEvent$EventType/ACTIVATED))
                         (clojure.java.browse/browse-url url)))))
    (seesaw/listen root :window-closing (fn [_] (when (confirm-close-if-dirty root update-action)
                                                  (close-fn)
                                                  (.dispose root))))
    (let [result
          (reify IExpressionEditor
            (retitle [_]
              (seesaw/config! root :title (if track?
                                            (track-cue-editor-title kind context cue)
                                            (phrase-cue-editor-title kind context cue))))
            (show [_]
              (.setLocationRelativeTo root parent-frame)
              (seesaw/show! root)
              (.toFront root))
            (can-close? [_] (confirm-close-if-dirty root update-action))
            (dispose [_]
              (close-fn)
              (seesaw/dispose! root)))]
      (if track?
        (show-util/swap-track! context assoc-in [:cues-editor :expression-editors (:uuid cue) kind] result)
        (show-util/swap-phrase-runtime! (show-util/show-from-phrase context) context
                                        assoc-in [:cues-editor :expression-editors (:uuid cue) kind] result))
      result)))

(defn- find-linked-cue-editor
  "If the supplied cue is linked, scans the show for any linked cue that
  already has an editor window open of the specified kind, and returns
  the matching window if one is found."
  [kind context cue]
  (let [[show context] (show-util/latest-show-and-context context)
        cue            (show-util/find-cue context cue)
        linked         (:linked cue)]
    (when linked
      (or (some (fn [track]
                  (some (fn [other-cue]
                          (when (= linked (:linked other-cue))
                            (get-in track [:cues-editor :expression-editors (:uuid other-cue) kind])))
                        (vals (get-in track [:contents :cues :cues]))))
                (vals (:tracks show)))
          (some (fn [phrase]
                  (some (fn [other-cue]
                          (when (= linked (:linked other-cue))
                            (get-in (show-util/phrase-runtime-info phrase)
                                    [:cues-editor :expression-editors (:uuid other-cue) kind])))
                        (vals (get-in phrase [:cues :cues]))))
                (vals (:tracks show)))))))

(defn show-cue-editor
  "Find or create the editor for the specified kind of expression
  associated with the specified cue (belonging to the specified show
  track or phrase trigger), make it visible, and add it to the track's
  list of active editors. Register an update function to be invoked with
  no arguments when the user has successfully updated the expression.

  If the cue is a linked cue, scan the entire show for any cues linked
  to it which already have an editor window open on this expression,
  and if one is found, bring that to the front rather than opening a
  new one."
  [kind context cue parent-frame update-fn]
  (try
    (let [[_ context runtime-info] (show-util/latest-show-and-context context)
          editor (or (get-in runtime-info [:cues-editor :expression-editors (:uuid cue) kind])
                     (find-linked-cue-editor kind context cue)
                     (create-cue-editor-window kind context cue parent-frame update-fn))]
      (show editor))
    (catch Throwable t
      (timbre/error t "Problem showing show" kind "editor"))))

(defn close-editor?
  "Tries to close an editor window. If `force?` is true, simply closes
  it and returns truthy. Otherwise, checks if it has any unsaved
  changes and if the user is willing to discard them. Returns falsey
  if the user wants to keep it open."
  [force? editor]
  (when (or force? (can-close? editor))
    (dispose editor)
    true))
