(ns beat-link-trigger.editors
  "Provides the user interface for editing expressions that customize
  application behavior."
  (:require [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.logs :as logs]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.prefs :as prefs]
            [me.raynes.fs :as fs]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceUpdate Beat CdjStatus MixerStatus]))

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
  (dispose [this]
  "Permanently close the window and release its resources."))))

(defn sort-setup-to-front
  "Given a sequence of expression keys and value tuples, makes sure
  that if a `:setup` key is present, it and its expression are first
  in the sequence, so they get evaluated first, in case they define
  any functions needed to evaluate the other expressions."
  [exprs]
  (concat (filter #(= :setup (first %)) exprs) (filter #(not= :setup (first %)) exprs)))

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
   :setup {:title "Global Setup Expression"
           :tip "Called once to set up any state your triggers&rsquo;
           expressions may need."
           :description
           "Called once when the triggers are loaded, or when you update
  the expression. Set up any global state (such as counters, flags, or
  network connections) that your expressions within any trigger need.
  Use the Global Shutdown expression to clean up resources when the
  trigger window is shutting down."
           :bindings nil}

   :shutdown {:title "Global Shutdown Expression"
              :tip "Called once to release global resources."
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
             "Called whenever a status update packet is received from the watched
  player(s). Return a <code>true</code> value as the last expression
  to enable the trigger. The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described
  below."
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

(def show-bindings
  "Identifies symbols which can be used inside any show expression,
  along with the expression that will be used to automatically bind
  that symbol if it is used in the expression, and the documentation
  to show the user what the binding is for."
  {'show {:code '(:show trigger-data)
          :doc "All the details known about the show. Copy to an
  Expression Global if you want to use the Inspector to
  explore them."}})

(defn- show-bindings-for-class
  "Collects the set of bindings for a show editor which is called with a
  particular class of status object. Merges the standard show
  convenience bindings with those associated with the specified class,
  which may be `nil`."
  [update-class]
  (merge show-bindings (when update-class (expressions/bindings-for-update-class update-class))))

(def show-bindings-for-track
  "Identifies symbols which can be used inside any show track
  expression, along with the expression that will be used to
  automatically bind that symbol if it is used in the expression, and
  the documentation to show the user what the binding is for."
  {'track {:code '(:track trigger-data)
           :doc "All the details known about the track. Copy to an
  Expression Global if you want to use the Inspector to
  explore them."}

   'midi-output {:code '((resolve 'beat-link-trigger.show/get-chosen-output)
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

   'loaded-players {:code '(:loaded (:track trigger-data))
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

   'playing-players {:code '(:playing (:track trigger-data))
                     :doc "The set of player numbers that are currently
  playing this track, if any."}})

(defn- show-bindings-for-track-and-class
  "Collects the set of bindings for a show track editor which is called
  with a particular class of status object. Merges the standard show
  track convenience bindings with those associated with the specified
  class, which may be `nil`."
  [update-class]
  (merge show-bindings
         show-bindings-for-track
         (when update-class (expressions/bindings-for-update-class update-class))))

(def global-show-editors
  "Specifies the kinds of editor which can be opened for a Show
  window overall, along with the details needed to describe and
  compile the expressions they edit. Created as an explicit array map
  to keep the keys in the order they are found here."
  (array-map
   :setup {:title "Global Setup Expression"
           :tip "Called once to set up any state your show&rsquo;s
           expressions may need."
           :description
           "Called once when the show is loaded, or when you update the
  expression. Set up any global state (such as counters, flags, or
  network connections) that your expressions within any track or cue
  need. Use the Global Shutdown expression to clean up resources when
  the show window is shutting down."
           :bindings (show-bindings-for-class nil)}

   :shutdown {:title "Global Shutdown Expression"
              :tip "Called once to release global resources."
              :description
              "Called when when the show window is closing. Close and
  release any shared system resources (such as network connections)
  that you opened in the Global Setup expression."
              :bindings (show-bindings-for-class nil)}))

(def show-track-editors
  "Specifies the kinds of editor which can be opened for a show track,
  along with the details needed to describe and compile the
  expressions they edit. Created as an explicit array map to keep the
  keys in the order they are found here."
  (array-map
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
             "Called whenever a status update packet is received from any
  player. Return a <code>true</code> value as the last expression
  to enable the track. The status update object, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html\"><code>CdjStatus</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described
  below."
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
          :description "Called whenever a beat packet is received from a
  player. You can use this for beat-driven integrations with other
  systems.<p>

  The beat object that was received, a beat-link <a
  href=\"http://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html\"><code>Beat</code></a>
  object, is available as <code>status</code>, and you can use normal
  Clojure <a href=\"http://clojure.org/reference/java_interop\">Java
  interop syntax</a> to access its fields and methods, but it is
  generally easier to use the convenience variables described below."
          :bindings (show-bindings-for-track-and-class Beat)}  ; TODO: Upgrade to TimeFinder-enhanced beats!

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
              :bindings (show-bindings-for-track-and-class nil)}))

(def ^:private editor-theme
  "The color theme to use in the code editor, so it can match the
  overall application look."
  (seesaw/invoke-now
   (with-open [s (clojure.java.io/input-stream
                  (clojure.java.io/resource "org/fife/ui/rsyntaxtextarea/themes/dark.xml"))]
     (org.fife.ui.rsyntaxtextarea.Theme/load s))))

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
        (swap! (seesaw/user-data trigger) assoc-in [:expression-fns kind]
               (expressions/build-user-expression text (:bindings editor-info) (:nil-status? editor-info)
                                                  (triggers-editor-title kind trigger global?))))
      (when-let [editor (get-in @(seesaw/user-data trigger) [:expression-editors kind])]
        (dispose editor)  ; Close the editor
        (swap! (seesaw/user-data trigger) update-in [:expression-editors] dissoc kind))
      (swap! (seesaw/user-data trigger) assoc-in [:expressions kind] text)  ; Save the new text
      (catch Throwable e
        (timbre/error e "Problem parsing" (:title editor-info))
        (seesaw/alert (str "<html>Unable to use " (:title editor-info)
                           ".<br><br>" e "<br><br>You may wish to check the log file for the detailed stack trace.")
                      :title "Exception during Clojure evaluation" :type :error))))
  (when update-fn
    (try
      (update-fn)
      (catch Throwable t
        (timbre/error t "Problem running expression editor update function.")))))

(defn- find-show-expression-text
  "Returns the source code, if any, of the specified show expression
  type for the specified show and track. If `track` is `nil`, `kind`
  refers to a global expression."
  [kind show track]
  (get-in show (if track
                 [:tracks (:signature track) :contents :expressions kind]
                 [:contents :expressions kind])))

(defn- find-show-expression-fn
  "Returns the compiled function, if any, for the specified show
  expression type for the specified show and track. If `track` is
  `nil`, `kind` refers to a global expression."
  [kind show track]
  (get-in show (if track
                 [:tracks (:signature track) :expression-fns kind]
                 [:expression-fns kind])))

(defn- find-show-expression-editor
  "Returns the open editor window, if any, for the specified show
  expression type for the specified show and track. If `track` is
  `nil`, `kind` refers to a global expression."
  [kind show track]
  (get-in show (if track
                 [:tracks (:signature track) :expression-editors kind]
                 [:expression-editors kind])))

(defn show-editor-title
  "Determines the title for a show expression editor window. If it is
  from an individual track, identifies it as such."
  [kind show track]
  (let [title (get-in (if track show-track-editors global-show-editors) [kind :title])]
    (if track
      (str title " for Track \"" (get-in show [:tracks (:signature track) :metadata :title]) "\"")
      (str "Show \"" (fs/base-name (:file show) true) "\" " title))))

(defn update-show-expression
  "Called when an show window expression's editor is ending and the user
  has asked to update the expression with the value they have edited.
  If `update-fn` is not nil, it will be called with no arguments."
  [open-shows kind show track text update-fn]
  (swap! open-shows update-in (if track
                                [(:file show) :tracks (:signature track) :expression-fns]
                                [(:file show) :expression-fns])
         dissoc kind) ; In case parse fails, leave nothing there
  (let [text        (clojure.string/trim text) ; Remove whitespace on either end
        editor-info (get (if track show-track-editors global-show-editors) kind)]
    (try
      (when (seq text)  ; If we got a new expression, try to compile it
        (swap! open-shows assoc-in (if track
                                     [(:file show) :tracks (:signature track) :expression-fns kind]
                                     [(:file show) :expression-fns kind])
               (expressions/build-user-expression text (:bindings editor-info) (:nil-status? editor-info)
                                                  (show-editor-title kind show track))))
      (when-let [editor (find-show-expression-editor kind show track)]
        (dispose editor)  ; Close the editor
        (swap! open-shows update-in (if track
                                      [(:file show) :tracks (:signature track) :expression-editors]
                                      [(:file show) :expression-editors])
               dissoc kind))

      (swap! open-shows assoc-in (if track
                                   [(:file show) :tracks (:signature track) :contents :expressions kind]
                                   [(:file show) :contents :expressions kind])
             text)  ; Save the new text
      (catch Throwable e
        (timbre/error e "Problem parsing" (:title editor-info))
        (seesaw/alert (str "<html>Unable to use " (:title editor-info)
                           ".<br><br>" e "<br><br>You may wish to check the log file for the detailed stack trace.")
                      :title "Exception during Clojure evaluation" :type :error))))
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

(defn- build-triggers-help
  "Create the help information for a triggers window editor with the
  specified kind."
  [kind global? editors]
  (let [editor-info (get editors kind)]
    (clojure.string/join (concat [help-header "<h1>Description</h1>"
                                  (:description editor-info)
                                  "<p>The "
                                  (when-not global? "atom
  <code>locals</code> is available for use by all expressions on this
  trigger, and the ")
                                  "atom <code>globals</code> is shared across all expressions everywhere."]
                                 (when (seq (:bindings editor-info))
                                      (concat ["

  <h1>Values Available</h1>

  The following values are available for you to use in writing your expression:<dl>"]
                                              (for [[sym spec] (into (sorted-map) (:bindings editor-info))]
                                                (str "<dt><code>" (name sym) "</code></dt><dd>" (:doc spec) "</dd>"))))
                                 ["</dl>"]))))

(defn- create-triggers-editor-window
  "Create and show a window for editing the Clojure code of a particular
  kind of Triggers window expression, with an update function to be
  called when the editor successfully updates the expression."
  [kind trigger update-fn]
  (let [global? (:global @(seesaw/user-data trigger))
        text (get-in @(seesaw/user-data trigger) [:expressions kind])
        save-fn (fn [text] (update-triggers-expression kind trigger global? text update-fn))
        root (seesaw/frame :title (triggers-editor-title kind trigger global?) :on-close :dispose :size [800 :by 600]
                           ;; TODO: Add save/load capabilities?
                           #_:menubar #_(seesaw/menubar
                                     :items [(seesaw/menu :text "File" :items (concat [load-action save-action]
                                                                                      non-mac-actions))
                                             (seesaw/menu :text "Triggers"
                                                          :items [new-trigger-action clear-triggers-action])]))
        editor (org.fife.ui.rsyntaxtextarea.RSyntaxTextArea. 16 80)
        scroll-pane (org.fife.ui.rtextarea.RTextScrollPane. editor)
        save-button (seesaw/button :text "Update" :listen [:action (fn [e] (save-fn (.getText editor)))])
        help (seesaw/styled-text :id :help :wrap-lines? true)]
    (.setSyntaxEditingStyle editor org.fife.ui.rsyntaxtextarea.SyntaxConstants/SYNTAX_STYLE_CLOJURE)
    (.apply editor-theme editor)
    (seesaw/config! help :editable? false)
    (seesaw/config! root :content (mig/mig-panel :items [[scroll-pane "grow 100 100, wrap, sizegroup a"]
                                                         [save-button "push, align center, wrap"]
                                                         [(seesaw/scrollable help :hscroll :never)
                                                          "sizegroup a, gapy unrelated, width 100%"]]))
    (seesaw/config! editor :id :source)
    (seesaw/value! root {:source text})
    (.setContentType help "text/html")
    (.setText help (build-triggers-help kind global? (if global? global-trigger-editors trigger-editors)))
    (seesaw/scroll! help :to :top)
    (seesaw/config! help :background :black)
    (seesaw/listen help :hyperlink-update
                   (fn [e]
                     (let [type (.getEventType e)
                           url (.getURL e)]
                       (when (= type (javax.swing.event.HyperlinkEvent$EventType/ACTIVATED))
                         (clojure.java.browse/browse-url url)))))
    (seesaw/listen root :window-closed
                   (fn [_] (swap! (seesaw/user-data trigger) update-in [:expression-editors] dissoc kind)))
    (let [result
          (reify IExpressionEditor
            (retitle [_]
              (seesaw/config! root :title (triggers-editor-title kind trigger global?)))
            (show [_]
              (.setLocationRelativeTo root trigger)
              (seesaw/show! root)
              (.toFront root))
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

;; TODO: Provide access to trigger-globals from show expressions and document that here, or
;;       reword build-trigger-help.
(defn- build-show-help
  "Create the help information for a show window editor with the
  specified kind."
  [kind global? editors]
  (let [editor-info (get editors kind)]
    (clojure.string/join (concat [help-header "<h1>Description</h1>"
                                  (:description editor-info)
                                  "<p>The "
                                  (when-not global? "atom
  <code>locals</code> is available for use by all expressions on this
  track, and the ")
                                  "atom <code>globals</code> is shared across all expressions in this show."]
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
  [open-shows kind show track parent-frame update-fn]
  (let [text        (find-show-expression-text kind show track)
        save-fn     (fn [text] (update-show-expression open-shows kind show track text update-fn))
        root        (seesaw/frame :title (show-editor-title kind show track) :on-close :dispose :size [800 :by 600])
        editor      (org.fife.ui.rsyntaxtextarea.RSyntaxTextArea. 16 80)
        scroll-pane (org.fife.ui.rtextarea.RTextScrollPane. editor)
        save-button (seesaw/button :text "Update" :listen [:action (fn [e] (save-fn (.getText editor)))])
        help        (seesaw/styled-text :id :help :wrap-lines? true)
        close-fn    (fn [] (swap! open-shows update-in (if track
                                                         [(:file show) :tracks (:signature track) :expression-editors]
                                                         [(:file show) :expression-editors])
                                  dissoc kind))]
    (.setSyntaxEditingStyle editor org.fife.ui.rsyntaxtextarea.SyntaxConstants/SYNTAX_STYLE_CLOJURE)
    (.apply editor-theme editor)
    (seesaw/config! help :editable? false)
    (seesaw/config! root :content (mig/mig-panel :items [[scroll-pane "grow 100 100, wrap, sizegroup a"]
                                                         [save-button "push, align center, wrap"]
                                                         [(seesaw/scrollable help :hscroll :never)
                                                          "sizegroup a, gapy unrelated, width 100%"]]))
    (seesaw/config! editor :id :source)
    (seesaw/value! root {:source text})
    (.setContentType help "text/html")
    (.setText help (build-show-help kind (not track) (if track show-track-editors global-show-editors)))
    (seesaw/scroll! help :to :top)
    (seesaw/config! help :background :black)
    (seesaw/listen help :hyperlink-update
                   (fn [e]
                     (let [type (.getEventType e)
                           url  (.getURL e)]
                       (when (= type (javax.swing.event.HyperlinkEvent$EventType/ACTIVATED))
                         (clojure.java.browse/browse-url url)))))
    (seesaw/listen root :window-closed
                   (fn [_] (close-fn)))
    (let [result
          (reify IExpressionEditor
            (retitle [_]
              (seesaw/config! root :title (show-editor-title kind show track)))
            (show [_]
              (.setLocationRelativeTo root parent-frame)
              (seesaw/show! root)
              (.toFront root))
            (dispose [_]
              (close-fn)
              (seesaw/dispose! root)))]
      (swap! open-shows assoc-in  (if track
                                    [(:file show) :tracks (:signature track) :expression-editors kind]
                                    [(:file show) :expression-editors kind])
             result)
      result)))

(defn show-show-editor
  "Find or create the editor for the specified kind of expression
  associated with the specified show (and optionally track), make it
  visible, and add it to the show's list of active editors. Register
  an update function to be invoked with no arguments when the user has
  successfully updated the expression. If `track` is nil we are
  editing global expressions."
  [open-shows kind show-map track parent-frame update-fn]
  ;; We need to use `show-map` instead of `show` as the argument name so we can call the show function
  ;; defined in the editor's `IExpressionEditor` implemntation. D'ohh!
  (try
    (let [editor (or (get-in show-map (if track
                                        [:tracks (:signature track) :expression-editors kind]
                                        [:expression-editors kind]))
                     (create-show-editor-window open-shows kind show-map track parent-frame update-fn))]
      (show editor))
    (catch Throwable t
      (timbre/error t "Problem showing show" kind "editor"))))
