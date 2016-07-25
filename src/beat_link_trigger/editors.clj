(ns beat-link-trigger.editors
  "Provides the user interface for editing expressions that customize
  application behavior."
  (:require [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.logs :as logs]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.prefs :as prefs]
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
   <code>\"CC\"</code>, or <code>\"Custom\"</code>."}

   'trigger-note {:code
                  '(get-in trigger-data [:value :note])
                  :doc "The MIDI note or CC number the trigger is
  configured to send."}

   'trigger-channel {:code '(get-in trigger-data [:value :channel])
                     :doc "The MIDI channel on which the trigger is
  configured to send."}

   'trigger-enabled {:code '(get-in trigger-data [:value :enabled])
                     :doc "The conditions under which the trigger is
  enabled to send MIDI; one of , <code>\"Never\"</code>,
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

(def global-editors
  "Specifies the kinds of editor which can be opened for the Trigger
  window overall, along with the details needed to describe and
  compile the expressions they edit. Created as an explicit array map
  to keep the keys in the order they are found here."
  (array-map
   :setup {:title "Global Setup Expression"
           :tip "Called once to set up any state your triggers&rsquo;
           expressions may need."
           :description
           "Called once when the triggers are loaded, or when you edit
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
           "Called once when the triggers are loaded, or when you edit the
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
  allows."
                :bindings (trigger-bindings-for-class CdjStatus)}

   :beat {:title "Beat Expression"
          :tip "Called on each beat from the watched devices."
          :description
          "Called whenever a beat packet is received from the watched
  player(s). You can use this for beat-driven integrations with other
  systems."
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
  detailed information than MIDI allows. Note that sometimes
  <code>status</code> will be <code>nil</code>, such as when a device
  has disappeared or the trigger settings have been changed, so your
  expression must be able to cope with <code>nil</code> values for all
  the convenience values that it uses."
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

(defn- editor-title
  "Determines the title for an editor window. If it is from an
  individual trigger, identifies it as such."
  [kind trigger global?]
  (let [title (get-in (if global? global-editors trigger-editors) [kind :title])]
    (if global?
      title
      (str "Trigger " (trigger-index trigger) " " title))))

(defn update-expression
  "Called when an expression's editor is ending and the user has asked
  to update the expression with the value they have edited. If
  `update-fn` is not nil, it will be called with no arguments."
  [kind trigger global? text update-fn]
  (swap! (seesaw/user-data trigger) update-in [:expression-fns] dissoc kind) ; In case parse fails, leave nothing there
  (let [text (clojure.string/trim text)  ; Remove whitespace on either end
        editor-info (get (if global? global-editors trigger-editors) kind)]
    (try
      (when (seq text)  ; If we got a new expression, try to compile it
        (swap! (seesaw/user-data trigger) assoc-in [:expression-fns kind]
               (expressions/build-user-expression text (:bindings editor-info) (:nil-status? editor-info))))
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

(defn- build-help
  "Create the help information for an editor with the specified kind."
  [kind global?]
  (let [editor-info (get (if global? global-editors trigger-editors) kind)]
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

(defn- create-editor-window
  "Create and show a window for editing the Clojure code of a
  particular kind of expression, with an update function to be called
  when the editor successfully updates the expression.."
  [kind trigger update-fn]
  (let [global? (:global @(seesaw/user-data trigger))
        text (get-in @(seesaw/user-data trigger) [:expressions kind])
        save-fn (fn [text] (update-expression kind trigger global? text update-fn))
        root (seesaw/frame :title (editor-title kind trigger global?) :on-close :dispose :size [800 :by 600]
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
    (.setText help (build-help kind global?))
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
              (seesaw/config! root :title (editor-title kind trigger global?)))
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
                     (create-editor-window kind trigger update-fn))]
      (show editor))
    (catch Exception e
      (timbre/error e "Problem showing trigger" kind "editor"))))
