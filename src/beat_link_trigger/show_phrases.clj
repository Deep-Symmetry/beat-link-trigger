(ns beat-link-trigger.show-phrases
  "Implements phrase trigger features for Show files, including their
  cue editing windows."
  (:require [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.show-util :as su :refer [latest-show latest-phrase latest-show-and-phrase
                                                        swap-show! swap-phrase! find-phrase-cue swap-phrase-cue!]]
            [beat-link-trigger.util :as util]
            [clojure.set]
            [clojure.string :as str]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [thi.ng.color.core :as color]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$SongStructureTag]
           [java.awt Color Cursor Graphics2D Rectangle RenderingHints]
           [java.awt.event InputEvent MouseEvent]
           [java.awt.geom Rectangle2D$Double]
           [java.util UUID]
           [javax.swing JComponent JFrame JOptionPane JPanel JScrollPane]
           [javax.swing.text JTextComponent]
           [jiconfont.icons.font_awesome FontAwesome]
           [jiconfont.swing IconFontSwing]))

(defn- run-phrase-function
  "Checks whether the phrase trigger has a custom function of the
  specified kind installed and if so runs it with the supplied status
  argument and the track local and global atoms. Returns a tuple of
  the function return value and any thrown exception. If `alert?` is
  `true` the user will be alerted when there is a problem running the
  function."
  [show phrase kind status alert?]
  (let [[show phrase] (latest-show-and-phrase show phrase)]
    (when-let [expression-fn (get-in phrase [:expression-fns kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(expression-fn status {:locals (:expression-locals phrase)
                                  :show   show
                                  :phrase phrase} (:expression-globals show)) nil])
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/show-editor-title kind show phrase) ":\n"
                               (get-in phrase [:contents :expressions kind])))
          (when alert? (seesaw/alert (str "<html>Problem running phrase trigger " (name kind) " expression.<br><br>" t)
                                     :title "Exception in Show Phrase Trigger Expression" :type :error))
          [nil t])))))

(defn run-cue-function
  "Checks whether the cue has a custom function of the specified kind
  installed and if so runs it with the supplied status or beat
  argument, the cue, and the track local and global atoms. Returns a
  tuple of the function return value and any thrown exception. If
  `alert?` is `true` the user will be alerted when there is a problem
  running the function."
  [show phrase section cue kind status-or-beat alert?]
  (let [[show phrase] (latest-show-and-phrase show phrase)
        cue           (find-phrase-cue show phrase section cue)]
    (when-let [expression-fn (get-in phrase [:cues :expression-fns (:uuid cue) kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(expression-fn status-or-beat {:locals  (:expression-locals phrase)
                                          :show    show
                                          :track   phrase
                                          :section section
                                          :cue     cue}
                          (:expression-globals show)) nil])
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/phrase-cue-editor-title kind phrase section cue) ":\n"
                               (get-in phrase [:contents :expressions section kind])))
          (when alert? (seesaw/alert (str "<html>Problem running phrase " (name section) " cue " (name kind)
                                          " expression.<br><br>" t)
                                     :title "Exception in Show Cue Expression" :type :error))
          [nil t])))))

(defn update-cue-gear-icon
  "Determines whether the gear button for a cue should be hollow or
  filled in, depending on whether any expressions have been assigned
  to it."
  [show phrase section cue gear]
  (let [cue (find-phrase-cue show phrase section cue)]
    (seesaw/config! gear :icon (if (every? clojure.string/blank? (vals (:expressions cue)))
                                 (seesaw/icon "images/Gear-outline.png")
                                 (seesaw/icon "images/Gear-icon.png")))))

;; TODO: Continue porting over functions from start of show-cues

(defn- paint-phrase-state
    "Draws a representation of the state of the phrase trigger, including whether
  it is enabled and whether it has been selected and is playing."
  [show uuid c ^Graphics2D g]
  (let [w        (double (seesaw/width c))
        h        (double (seesaw/height c))
        outline  (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        show     (latest-show show)
        phrase    (get-in show [:contents :phrases uuid])
        enabled? false  ; TODO: (enabled? show phrase)
        active?  false] ; TODO: TBD!
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (when active? ; Draw the inner filled circle showing the track is loaded or playing.
      (.setPaint g (if enabled? Color/green Color/lightGray))
      (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))

    ;; Draw the outer circle that reflects the enabled state.
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if enabled? Color/green Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(defn- expunge-deleted-phrase
  "Removes all the items from a show that need to be cleaned up when the
  phrase trigger has been deleted. This function is designed to be
  used in a single swap! call for simplicity and efficiency."
  [show phrase]
  (-> show
      (update :phrases dissoc (:uuid phrase))
      (update-in [:contents :phrases] dissoc (:uuid phrase))
      (update-in [:contents :phrase-order] (fn [old-order] (filterv (complement #{(:uuid phrase)}) old-order)))
      #_(update :playing remove-signature (:signature track))))  ; TODO: What is the equivalent for phrases?

(defn- close-phrase-editors?
  "Tries closing all open expression and cue editors for the phrase
  trigger. If `force?` is true, simply closes them even if they have
  unsaved changes. Otherwise checks whether the user wants to save any
  unsaved changes. Returns truthy if there are none left open the user
  wants to deal with."
  [force? show phrase]
  (let [phrase (latest-phrase show phrase)]
    (and
     (every? (partial editors/close-editor? force?) (vals (:expression-editors phrase)))
     (or (not (:cues-editor phrase)) ((get-in phrase [:cues-editor :close-fn]) force?)))))

(defn- cleanup-phrase
  "Process the removal of a phrase trigger, either via deletion, or
  because the show is closing. If `force?` is true, any unsaved
  expression editors will simply be closed. Otherwise, they will block
  the phrase trigger removal, which will be indicated by this function
  returning falsey. Run any appropriate custom expressions and send
  configured MIDI messages to reflect the departure of the phrase
  trigger."
  [force? show phrase]
  (when (close-phrase-editors? show force? phrase)
    (let [[show phrase] (latest-show-and-phrase show phrase)]
      (when (:tripped phrase)
        (doseq [[section cues] (get-in phrase [:contents :cues :cues])
                cue             cues]
          #_(cues/cleanup-phrase-cue true phrase section cue))  ; TODO: Something like this?
        #_(when ((set (vals (:playing show))) (:signature track))
          (send-stopped-messages track nil))  ; TODO: And something like this?
        )
      (run-phrase-function show `<phrase :shutdown nil (not force?)))
    true))

(defn- delete-phrase-action
  "Creates the menu action which deletes a phrase trigger after confirmation."
  [show phrase panel]
  (seesaw/action :handler (fn [_]
                            (when (util/confirm panel
                                                (str "This will irreversibly remove the phrase trigger, losing\r\n"
                                                     "any configuration, expressions, and cues created for it.")
                                                :type :warning :title "Delete Phrase Trigger?")
                              (try
                                (cleanup-phrase true show phrase)
                                (swap-show! show expunge-deleted-phrase phrase)
                                #_(refresh-signatures show)  ; TODO: Is there a phrase equivalent?
                                (su/update-row-visibility show)
                                (catch Exception e
                                  (timbre/error e "Problem deleting phrase trigger")
                                  (seesaw/alert (str e) :title "Problem Deleting Phrase Trigger" :type :error)))))
                 :name "Delete Phrase Trigger"))

(defn- update-phrase-comboboxes
  "Called when a phrase triger row has been created in a show, or the
  phrase trigger contents have been replaced, to update the combo-box
  elements to reflect the phrase trigger's state."
  [phrase panel]
  (seesaw/selection! (seesaw/select panel [:#outputs]) (or (:midi-device phrase) (first (util/get-midi-outputs))))
  (seesaw/selection! (seesaw/select panel [:#message]) (or (:message phrase) "None"))
  (seesaw/selection! (seesaw/select panel [:#solo]) (or (:solo phrase) "Show"))
  (seesaw/selection! (seesaw/select panel [:#enabled]) (or (:enabled phrase) "See Below")))

(defn parse-phrase-expressions
  "Parses all of the expressions associated with a phrase trigger and
  its cues. `phrase` must be current."
  [show phrase]
  (doseq [[kind expr] (editors/sort-setup-to-front (get-in phrase [:contents :expressions]))]
    (let [editor-info (get @editors/show-track-editors kind)]  ; TODO: This should be show-phrase-editors?
        (try
          (swap-phrase! show phrase assoc-in [:expression-fns kind]
                        ;; TODO: this needs to use enw show-phrase-editor-title!
                        (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                           (editors/show-editor-title kind show phrase)))
              (catch Exception e
                (timbre/error e (str "Problem parsing " (:title editor-info)
                                     " when loading Show. Expression:\n" expr "\n"))
                (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                   "Check the log file for details.")
                              :title "Exception during Clojure evaluation" :type :error)))))
  ;; Parse any custom expressions defined for cues in the track.
  (doseq [[section cues] (vals (get-in phrase [:contents :cues :cues]))
          cue             cues]
    #_(cues/compile-cue-phrae-expressions phrase section cue)))  ; TODO: Implement!

(defn- build-filter-target
  "Creates a string that can be matched against to filter a phrase
  trigger by text substring, taking into account the custom comment
  assigned to the phrase trigger in the show, if any."
  [comment]
  (str/lower-case (or comment "")))

(defn- create-phrase-panel
  "Creates a panel that represents a phrase trigger in the show. Updates
  tracking indices appropriately."
  [show uuid]
  (let [show           (latest-show show)
        phrase         (get-in show [:contents :phrases uuid])
        update-comment (fn [c]
                         (let [comment (seesaw/text c)]
                           (swap-phrase! show uuid assoc :comment comment)
                           (swap-show! show assoc-in [:phrases uuid :filter] (str/lower-case (or comment "")))))
        comment-field  (seesaw/text :id :comment :paint (partial util/paint-placeholder "Comment")
                                    :text (:comment phrase "") :listen [:document update-comment])
        outputs        (util/get-midi-outputs)
        gear           (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
        panel          (mig/mig-panel
                        ;; TODO: Add view of all cues at top of panel, like waveform preview.
                        :items [[comment-field "spanx, growx, pushx, wrap"]
                                [gear "spanx, split"]

                                ["MIDI Output:" "gap unrelated"]
                                [(seesaw/combobox :id :outputs
                                                  :model (concat outputs
                                                                 (when-let [chosen (:midi-device phrase)]
                                                                   (when-not ((set outputs) chosen)
                                                                     [chosen])))
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-phrase! show uuid assoc :midi-device
                                                                          (seesaw/selection %))])]

                                ["Playing:" "gap unrelated"]
                                [(seesaw/canvas :id :state :size [18 :by 18] :opaque? false
                                                :tip "Outer ring shows track enabled, inner light when loaded."
                                                :paint (partial paint-phrase-state show uuid))]
                                ["Message:"]
                                [(seesaw/combobox :id :message :model ["None" "Note" "CC" "Custom"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-phrase! show uuid assoc :message
                                                                          (seesaw/selection %))])]
                                [(seesaw/spinner :id :note
                                                 :model (seesaw/spinner-model (or (:note phrase) 120)
                                                                              :from 1 :to 127)
                                                 :listen [:state-changed
                                                          #(swap-phrase! show uuid assoc :note (seesaw/value %))])
                                 "hidemode 3"]

                                [(seesaw/label :id :channel-label :text "Channel:")
                                 "gap unrelated, hidemode 3"]
                                [(seesaw/spinner :id :channel
                                                 :model (seesaw/spinner-model (or (:channel phrase) 1)
                                                                              :from 1 :to 16)
                                                 :listen [:state-changed
                                                          #(swap-phrase! show uuid assoc :channel (seesaw/value %))])
                                 "hidemode 3"]

                                ["Solo:" "gap unrelated"]
                                [(seesaw/combobox :id :solo :model ["Global" "Show" "Blend"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-phrase! show uuid assoc :solo (seesaw/selection %))])]

                                [(seesaw/label :id :enabled-label :text "Enabled:") "gap unrelated"]
                                [(seesaw/combobox :id :enabled
                                                  :model ["See Below" "Custom"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(do (swap-phrase! show uuid assoc :enabled (seesaw/value %))
                                                                ;; TODO: (repaint-phrase-states show uuid)
                                                                )])
                                 "hidemode 3"]

                                ;; TODO: Add rows of enabled/weight UI.
                                ])

        phrase (merge phrase
                      {:uuid     uuid
                       :filter (build-filter-target (:comment phrase))
                       :creating true}) ; Suppress popup expression editors when reopening a show.

        popup-fn (fn [^MouseEvent e]  ; Creates the popup menu for the gear button or right-clicking in the phrase.
                   ;; TODO: Implement the rest of these!
                   (concat [#_(edit-cues-action phrase panel) #_(seesaw/separator)]
                           #_(phrase-editor-actions show phrase panel gear)
                           [#_(seesaw/separator) #_(phrase-simulate-menu phrase) (su/phrase-inspect-action show phrase)
                            (seesaw/separator)]
                           [(seesaw/separator) (delete-phrase-action show phrase panel)]))

        drag-origin (atom nil)]

    (swap-show! show assoc-in [:contents :phrases uuid] phrase)  ; information about the phrase trigger that gets saved.
    (swap-show! show assoc-in [:phrases uuid]  ; Runtime (unsaved) information about the phrase trigger.
                {:panel             panel
                 :expression-locals (atom {})
                 :entered           {}}) ; Map from player # to sets of UUIDs of cues that have been entered.

    ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
    ;; or right click on the gear button. Also set the proper initial gear appearance.
    (seesaw/config! [panel gear] :popup popup-fn)
    (seesaw/listen gear :mouse-pressed (fn [e]
                                         (let [popup (seesaw/popup :items (popup-fn e))]
                                           (util/show-popup-from-button gear popup e))))
    (su/update-phrase-gear-icon show phrase gear)

    ;; TODO: The equivalent for the phrase preview once implemented.
    #_(seesaw/listen soft-preview
                   :mouse-moved (fn [e] (handle-preview-move track soft-preview preview-loader e))
                   :mouse-pressed (fn [^MouseEvent e]
                                    (reset! drag-origin {:point (.getPoint e)})
                                    (handle-preview-press track preview-loader e))
                   :mouse-dragged (fn [e] (handle-preview-drag track preview-loader e drag-origin)))

    ;; TODO: Implement these, although I think there is only one visiblity handler needed.
    ;; Update output status when selection changes, giving a chance for the other handlers to run first
    ;; so the data is ready. Also sets them up to automatically open the expression editor for the Custom
    ;; Enabled Filter if "Custom" is chosen.
    #_(seesaw/listen (seesaw/select panel [:#outputs])
                   :item-state-changed (fn [_] (seesaw/invoke-later (show-midi-status track))))
    #_(attach-track-message-visibility-handler show track "loaded" gear)
    #_(attach-track-message-visibility-handler show track "playing" gear)
    #_(attach-track-custom-editor-opener show track (seesaw/select panel [:#enabled]) :enabled gear)

    ;; Establish the saved or initial settings of the UI elements, which will also record them for the
    ;; future, and adjust the interface, thanks to the already-configured item changed listeners.
    (update-phrase-comboboxes phrase panel)

    ;; In case this is the inital creation of the phrase trigger, record the defaulted values of the numeric inputs.
    ;; This will have no effect if they were loaded.
    (swap-phrase! show phrase assoc :note (seesaw/value (seesaw/select panel [:#note])))
    (swap-phrase! show phrase assoc :channel (seesaw/value (seesaw/select panel [:#channel])))

    #_(cues/build-cues track)  ; TODO: Implement the phrase cues equivalent.
    (parse-phrase-expressions show phrase)

    ;; We are done creating the phrase trigger, so arm the menu listeners to automatically pop up expression editors
    ;; when the user requests a custom message.
    (swap-phrase! show phrase dissoc :creating)))

(defn create-phrase-panels
  "Creates all the panels that represent phrase triggers in the show."
  [show]
  (doseq [uuid (get-in (latest-show show) [:contents :phrase-order])]
    (create-phrase-panel show uuid)))

(defn- scroll-to-phrase
  "Makes sure the specified phrase trigger is visible (it has just been
  created), or give the user a warning that the current filters have
  hidden it. If the comment field is empty, focuses on it to encourage
  the user to add one."
  [show phrase-or-uuid]
  (let [show   (latest-show show)
        uuid   (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))
        phrase (get-in show [:contents :phrases uuid])
        tracks (seesaw/select (:frame show) [:#tracks])]
    (if (some #(= uuid %) (:vis-phrases show))
      (seesaw/invoke-later
       (let [^JPanel panel (get-in show [:phrases uuid :panel])]
         (seesaw/scroll! tracks :to (.getBounds panel))
         (when (str/blank? (:comment phrase))
           (seesaw/request-focus! (seesaw/select panel [:#comment])))))
      (seesaw/alert (:frame show)
                    (str "The phrase trigger “" (su/display-title phrase) "” is currently hidden by your filters.\r\n"
                          "To continue working with it, you will need to adjust the filters.")
                     :title "Can't Scroll to Hidden Phrase Trigger" :type :info))))

(defn new-phrase
  "Adds a new phrase trigger to the show."
  [show]
  (let [show (latest-show show)
        uuid (UUID/randomUUID)]
    (create-phrase-panel show uuid)
    (swap-show! show update-in [:contents :phrase-order] (fnil conj []) uuid)
    (su/update-row-visibility show)
    (scroll-to-phrase show uuid)))

(defn sort-phrases
  "Sorts the phrase triggers by their comments. `show` must be current."
  [show]
  (let [show (latest-show show)]
    ;; TODO: Perform the sort, update `:contents` `:phrase-order`, then
    ;; do a visibility refresh to update the show rows accordingly.
    ))
