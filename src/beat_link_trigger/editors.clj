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

(def trigger-editors
  "Specifies the kinds of editor which can be opened for a trigger,
  along with the details needed to describe and compile the
  expressions they edit."
  {:enabled {:title "Enabled Expression"
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
             :bindings (merge (expressions/bindings-for-update-class CdjStatus)
                              (expressions/bindings-for-update-class MixerStatus))}
   :setup {:title "Setup Expression"
             :tip "Called once to set up any state your other expressions may need."
             :description
  "Called once when the triggers are loaded, or when you edit the
  expression. Set up any state (such as counters, flags, or network
  connections) that your other expressions for this trigger need. Use
  the Shutdown expression to clean up resources when the trigger is
  shutting down."
           :bindings nil
           :run-when-saved true}
   :shutdown {:title "Shutdown Expression"
              :tip "Called once to release resources your trigger had been using."
              :description
  "Called when when the trigger is shutting down, either because it
  was deleted, the window was closed, or a new trigger file is being
  loaded. Close and release any system resources (such as network
  connections) that you opened in the Setup expression."
             :bindings nil}})

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
  "Determines the title for an editor window."
  [kind trigger]
  (str "Trigger " (trigger-index trigger) " " (get-in trigger-editors [kind :title])))

(defn update-expression
  "Called when an expression's editor is ending and the user has asked
  to update the expression with the value they have edited. If
  `update-fn` is not nil, it will be called with no arguments."
  [kind trigger text update-fn]
  (swap! (seesaw/user-data trigger) update-in [:expression-fns] dissoc kind) ; In case parse fails, leave nothing there
  (try
    (swap! (seesaw/user-data trigger) assoc-in [:expression-fns kind]
           (expressions/build-user-expression text (get-in trigger-editors [kind :bindings])))
    (when-let [editor (get-in @(seesaw/user-data trigger) [:expression-editors kind])]
      (dispose editor)  ; Close the editor
      (swap! (seesaw/user-data trigger) update-in [:expression-editors] dissoc kind))
    (swap! (seesaw/user-data trigger) assoc-in [:expressions kind] text)
    (catch Throwable e
      (timbre/error e "Problem parsing" (get-in trigger-editors [kind :title]))
      (seesaw/alert (str "<html>Unable to use " (get-in trigger-editors [kind :title]) ".<br><br>" e
                         "<br><br>You may wish to check the log file for the detailed stack trace.")
                    :title "Exception during Clojure evaluation" :type :error)))
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
  [kind]
  (clojure.string/join (concat [help-header "<h1>Description</h1>" (get-in trigger-editors [kind :description])
  "<p>

  The atom <code>locals</code> is available for use by all expressions
  on this trigger, and the atom <code>globals</code> is shared across
  all expressions everywhere."
                                ] (when (seq (get-in trigger-editors [kind :bindings]))
                                    (concat ["

  <h1>Values Available</h1>

  The following values are available for you to use in writing your expression:<dl>"]
                                            (for [[sym spec] (into (sorted-map)
                                                                   (get-in trigger-editors [kind :bindings]))]
                                              (str "<dt><code>" (name sym) "</code></dt><dd>" (:doc spec) "</dd>"))))
                               ["</dl>"])))

(defn- create-editor-window
  "Create and show a window for editing the Clojure code of a
  particular kind of expression, with an update function to be called
  when the editor successfully updates the expression.."
  [kind trigger update-fn]
  (let [text (get-in @(seesaw/user-data trigger) [:expressions kind])
        save-fn (fn [text] (update-expression kind trigger text update-fn))
        root (seesaw/frame :title (editor-title kind trigger) :on-close :dispose :size [800 :by 600]
                           ;; TODO: Add save/load capabilities
                           ;; TODO: Add documentation and binding help
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
    (.setText help (build-help kind))
    (seesaw/scroll! help :to :top)
    (seesaw/config! help :background :black)
    (seesaw/listen help :hyperlink-update
                   (fn [e]
                     (let [type (.getEventType e)
                           url (.getURL e)]
                       (when (= type (. javax.swing.event.HyperlinkEvent$EventType ACTIVATED))
                         (clojure.java.browse/browse-url url)))))
    (seesaw/listen root :window-closed
                   (fn [_] (swap! (seesaw/user-data trigger) update-in [:expression-editors] dissoc kind)))
    (let [result
          (reify IExpressionEditor
            (retitle [_]
              (seesaw/config! root :title (editor-title kind trigger)))
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
  updated the expression."
  [kind trigger update-fn]
  (try
    (let [editor (or (get-in @(seesaw/user-data trigger) [:expression-editors kind])
                     (create-editor-window kind trigger update-fn))]
      (show editor))
    (catch Exception e
      (timbre/error e "Problem showing trigger" (get-in trigger-editors [kind :title]) "editor"))))
