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
             :description "Determines whether the trigger is enabled. Slurp some HTML from a resource?"
             :bindings (merge (expressions/bindings-for-update-class CdjStatus)
                              (expressions/bindings-for-update-class MixerStatus))}})

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
  to update the expression with the value they have edited."
  [kind trigger text]
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
                               :title "Exception during Clojure evaluation" :type :error))))

(defn- create-editor-window
  "Create and show a window for editing the Clojure code of a
  particular kind of expression."
  [kind trigger]
  (let [text (get-in @(seesaw/user-data trigger) [:expressions kind])
        save-fn (fn [text] (update-expression kind trigger text))
        root (seesaw/frame :title (editor-title kind trigger) :on-close :dispose
                           ;; TODO: Add save/load capabilities
                           ;; TODO: Add documentation and binding help
                           #_:menubar #_(seesaw/menubar
                                     :items [(seesaw/menu :text "File" :items (concat [load-action save-action]
                                                                                      non-mac-actions))
                                             (seesaw/menu :text "Triggers"
                                                          :items [new-trigger-action clear-triggers-action])]))
        editor (org.fife.ui.rsyntaxtextarea.RSyntaxTextArea. 16 80)
        scroll-pane (org.fife.ui.rtextarea.RTextScrollPane. editor)
        save-button (seesaw/button :text "Update" :listen [:action (fn [e] (save-fn (.getText editor)))])]
    (.setSyntaxEditingStyle editor org.fife.ui.rsyntaxtextarea.SyntaxConstants/SYNTAX_STYLE_CLOJURE)
    (.apply editor-theme editor)
    (seesaw/config! root :content (mig/mig-panel :items [[scroll-pane "grow 100 100, wrap"]
                                                         [save-button "push, align center"]]))
    (seesaw/config! editor :id :source)
    (seesaw/value! root {:source text})
    (seesaw/listen root :window-closed
                   (fn [_] (swap! (seesaw/user-data trigger) update-in [:expression-editors] dissoc kind)))
    (seesaw/pack! root)
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
  to the trigger's list of active editors."
  [kind trigger]
  (try
    (let [editor (or (get-in @(seesaw/user-data trigger) [:expression-editors kind])
                     (create-editor-window kind trigger))]
      (show editor))
    (catch Exception e
      (timbre/error e "Problem showing trigger" (get-in trigger-editors [kind :title]) "editor"))))
