(ns beat-link-trigger.show-cues
  "Implements the cue editing window for Show files."
  (:require [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.show-util :as su :refer [latest-show latest-track latest-show-and-track
                                                        swap-show! swap-track! find-cue swap-cue!]]
            [beat-link-trigger.util :as util]
            [clojure.set]
            [clojure.string :as str]
            [overtone.midi :as midi]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [thi.ng.color.core :as color]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink.data BeatGrid CueList WaveformDetail WaveformDetailComponent
            WaveformPreviewComponent]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$SongStructureTag]
           [io.kaitai.struct ByteBufferKaitaiStream]
           [java.awt Color Cursor Graphics2D Rectangle RenderingHints]
           [java.awt.event InputEvent MouseEvent]
           [java.awt.geom Rectangle2D$Double]
           [javax.swing JComponent JFrame JOptionPane JPanel JScrollPane]
           [javax.swing.text JTextComponent]
           [jiconfont.icons.font_awesome FontAwesome]
           [jiconfont.swing IconFontSwing]))

(defn run-cue-function
  "Checks whether the cue has a custom function of the specified kind
  installed and if so runs it with the supplied status or beat
  argument, the cue, and the track local and global atoms. Returns a
  tuple of the function return value and any thrown exception. If
  `alert?` is `true` the user will be alerted when there is a problem
  running the function."
  [track cue kind status-or-beat alert?]
  (let [[show track] (latest-show-and-track track)
        cue          (find-cue track cue)]
    (when-let [expression-fn (get-in track [:cues :expression-fns (:uuid cue) kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(expression-fn status-or-beat {:locals (:expression-locals track)
                                          :show   show
                                          :track  track
                                          :cue    cue}
                          (:expression-globals show)) nil])
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/cue-editor-title kind track cue) ":\n"
                               (get-in track [:contents :expressions kind])))
          (when alert? (seesaw/alert (str "<html>Problem running cue " (name kind) " expression.<br><br>" t)
                                     :title "Exception in Show Cue Expression" :type :error))
          [nil t])))))

(defn update-cue-gear-icon
  "Determines whether the gear button for a cue should be hollow or
  filled in, depending on whether any expressions have been assigned
  to it."
  [track cue gear]
  (let [cue (find-cue track cue)]
    (seesaw/config! gear :icon (if (every? clojure.string/blank? (vals (:expressions cue)))
                                 (seesaw/icon "images/Gear-outline.png")
                                 (seesaw/icon "images/Gear-icon.png")))))

(defn link-button-icon
  "Returns the proper icon to use for a cue's link button, depending on
  its current link state."
  [cue]
  (if (:linked cue)
    (IconFontSwing/buildIcon FontAwesome/LINK 16.0 Color/white)
    (IconFontSwing/buildIcon FontAwesome/CHAIN_BROKEN 16.0 Color/white)))

(defn update-cue-link-icon
  "Determines whether the link button for a cue should be connected or
  broken, depending on whether it is linked to a library cue."
  [track cue link]
  (let [cue (find-cue track cue)]
    (seesaw/config! link :icon (link-button-icon cue))))

(defn repaint-cue-states
  "Causes the two cue state indicators to redraw themselves to reflect a
  change in state. `cue` can either be the cue object or a cue UUID."
  [track cue]
  (let [uuid (if (instance? java.util.UUID cue) cue (:uuid cue))]
    (when-let [panel (get-in (latest-track track) [:cues-editor :panels uuid])]
      (seesaw/repaint! (seesaw/select panel [:#entered-state]))
      (seesaw/repaint! (seesaw/select panel [:#started-state])))))

(defn repaint-all-cue-states
  "Causes the cue state indicators for all cues in a track to redraw
  themselves to reflect a change in state."
  [track]
  (doseq [cue (keys (get-in (latest-track track) [:contents :cues :cues]))]
    (repaint-cue-states track cue)))

(declare build-cues)

(defn- scroll-to-cue
  "Makes sure the specified cue editor is visible (it has just been
  created or edited), or give the user a warning that the current cue
  filters have hidden it. If `select-comment` is true, this is a
  newly-created cue, so focus on the comment field and select its
  entire content, for easy editing."
  ([track cue]
   (scroll-to-cue track cue false false))
  ([track cue select-comment]
   (scroll-to-cue track cue select-comment false))
  ([track cue select-comment silent]
   (let [track (latest-track track)
         cues  (seesaw/select (get-in track [:cues-editor :frame]) [:#cues])
         cue   (find-cue track cue)
         uuid  (:uuid cue)]
     (if (some #(= uuid %) (get-in track [:cues-editor :visible]))
       (let [^JPanel panel           (get-in track [:cues-editor :panels (:uuid cue)])
             ^JTextComponent comment (seesaw/select panel [:#comment])]
         (seesaw/invoke-later
          (seesaw/scroll! cues :to (.getBounds panel))
          (when select-comment
            (.requestFocusInWindow comment)
            (.selectAll comment))))
       (when-not silent
         (seesaw/alert (get-in track [:cues-editor :frame])
                       (str "The cue \"" (:comment cue) "\" is currently hidden by your filters.\r\n"
                            "To continue working with it, you will need to adjust the filters.")
                       :title "Can't Scroll to Hidden Cue" :type :info))))))

(def min-lane-height
  "The minmum height, in pixels, we will allow a lane to shrink to
  before we start growing the cues editor waveform to accommodate all
  the cue lanes."
  20)

(defn- cue-rectangle
  "Calculates the outline of a cue within the coordinate system of the
  waveform detail component at the top of the cues editor window,
  taking into account its lane assignment and cluster of neighbors.
  `track` and `cue` must be current."
  ^Rectangle2D$Double [track cue ^WaveformDetailComponent wave]
  (let [[lane num-lanes] (get-in track [:cues :position (:uuid cue)])
        lane-height      (double (max min-lane-height (/ (.getHeight wave) num-lanes)))
        x                (.getXForBeat wave (:start cue))
        w                (- (.getXForBeat wave (:end cue)) x)]
    (java.awt.geom.Rectangle2D$Double. (double x) (* lane lane-height) (double w) lane-height)))

(defn scroll-wave-to-cue
  "Makes sure the specified cue is visible in the waveform detail pane
  of the cues editor window."
  [track cue]
  (let [track (latest-track track)
        cue   (find-cue track cue)]
    (when-let [editor (:cues-editor track)]
      (let [auto-scroll                   (seesaw/select (:panel editor) [:#auto-scroll])
            ^WaveformDetailComponent wave (:wave editor)]
        (seesaw/config! auto-scroll :selected? false)  ; Make sure auto-scroll is turned off.
        (seesaw/invoke-later  ; Wait for re-layout if necessary.
         (seesaw/scroll! wave :to (.getBounds (cue-rectangle track cue wave))))))))

(defn update-cue-spinner-models
  "When the start or end position of a cue has changed, that affects the
  legal values the other can take. Update the spinner models to
  reflect the new limits. Then we rebuild the cue list in case they
  need to change order. Also scroll so the cue is still visible, or if
  it has been filtered out warn the user that has happened."
  [track cue ^javax.swing.SpinnerNumberModel start-model ^javax.swing.SpinnerNumberModel end-model]
  (let [cue (find-cue track cue)]
    (.setMaximum start-model (dec (:end cue)))
    (.setMinimum end-model (inc (:start cue)))
    (seesaw/invoke-later
     (build-cues track)
     (seesaw/invoke-later scroll-to-cue track cue))))

(defn- cue-missing-expression?
  "Checks whether the expression body of the specified kind is empty for
  the specified cue."
  [track cue kind]
  (clojure.string/blank? (get-in (find-cue track cue) [:expressions kind])))

(defn cue-editor-actions
  "Creates the popup menu actions corresponding to the available
  expression editors for a given cue."
  [track cue panel gear]
  (for [[kind spec] @editors/show-track-cue-editors]
    (let [update-fn (fn [] (update-cue-gear-icon track cue gear))]
      (seesaw/action :handler (fn [_] (editors/show-cue-editor kind (latest-track track) cue panel update-fn))
                     :name (str "Edit " (:title spec))
                     :tip (:tip spec)
                     :icon (if (cue-missing-expression? track cue kind)
                             "images/Gear-outline.png"
                             "images/Gear-icon.png")))))
(declare send-cue-messages)

(defn- cue-event-enabled?
  "Checks whether the specified event type is enabled for the given
  cue (its message is something other than None, and if Custom, there
  is a non-empty expression body)."
  [track cue event]
  (let [cue     (find-cue track cue)
        message (get-in cue [:events event :message])]
    (cond
      (= "None" message)
      false

      (= "Custom" message)
      (not (cue-missing-expression? track cue event))

      (= "Same" message) ; Must be a :started-late event
      (cue-event-enabled? track cue :started-on-beat)

      :else ; Is a MIDI note or CC
      true)))

(defn- cue-simulate-actions
  "Creates the actions that simulate events happening to the cue, for
  testing expressions or creating and testing MIDI mappings in other
  software."
  [track cue]
  [(seesaw/action :name "Entered"
                  :enabled? (cue-event-enabled? track cue :entered)
                  :handler (fn [_] (send-cue-messages (latest-track track) cue :entered (su/random-beat-or-status))))
   (seesaw/action :name "Started On-Beat"
                  :enabled? (cue-event-enabled? track cue :started-on-beat)
                  :handler (fn [_] (send-cue-messages (latest-track track) cue :started-on-beat
                                                      (su/random-beat-and-position track))))
   (seesaw/action :name "Started Late"
                  :enabled? (cue-event-enabled? track cue :started-late)
                  :handler (fn [_] (send-cue-messages (latest-track track) cue :started-late (su/random-cdj-status))))
   (seesaw/action :name "Beat"
                  :enabled? (not (cue-missing-expression? track cue :beat))
                  :handler (fn [_] (run-cue-function track cue :beat (su/random-beat-and-position track) true)))
   (seesaw/action :name "Tracked Update"
                  :enabled? (not (cue-missing-expression? track cue :tracked))
                  :handler (fn [_] (run-cue-function track cue :tracked (su/random-cdj-status) true)))
   (let [enabled-events (filterv (partial cue-event-enabled? track cue) [:started-on-beat :started-late])]
     (seesaw/action :name "Ended"
                    :enabled? (seq enabled-events)
                    :handler (fn [_]
                               (swap-track! track assoc-in [:cues (:uuid cue) :last-entry-event]
                                            (rand-nth enabled-events))
                               (send-cue-messages (latest-track track) cue :ended (su/random-beat-or-status)))))
   (seesaw/action :name "Exited"
                  :enabled? (cue-event-enabled? track cue :entered)
                  :handler (fn [_] (send-cue-messages (latest-track track) cue :exited (su/random-beat-or-status))))])

(defn- cue-simulate-menu
  "Creates the submenu containing actions that simulate events happening
  to the cue, for testing expressions or creating and testing MIDI
  mappings in other software."
  [track cue]
  (seesaw/menu :text "Simulate" :items (cue-simulate-actions track cue)))

(defn- assign-cue-hue
  "Picks a color for a new cue by cycling around the color wheel, and
  recording the last one used."
  [track]
  (let [shows (swap-track! track update-in [:contents :cues :hue]
                           (fn [old-hue] (mod (+ (or old-hue 0.0) 62.5) 360.0)))]
    (get-in shows [(:file track) :tracks (:signature track) :contents :cues :hue])))

(defn- scroll-wave-to-cue-action
  "Creates the menu action which scrolls the waveform detail to ensure
  the specified cue is visible."
  [track cue]
  (seesaw/action :handler (fn [_] (scroll-wave-to-cue track cue))
                 :name "Scroll Waveform to This Cue"))

(defn- duplicate-cue-action
  "Creates the menu action which duplicates an existing cue."
  [track cue]
  (seesaw/action :handler (fn [_]
                            (try
                              (let [uuid    (java.util.UUID/randomUUID)
                                    track   (latest-track track)
                                    cue     (find-cue track cue)
                                    comment (util/assign-unique-name
                                             (map :comment (vals (get-in track [:contents :cues :cues])))
                                             (:comment cue))
                                    new-cue (merge cue {:uuid    uuid
                                                        :hue     (assign-cue-hue track)
                                                        :comment comment})]
                                (swap-track! track assoc-in [:contents :cues :cues uuid] new-cue)
                                (build-cues track)
                                (scroll-to-cue track new-cue true))
                              (catch Exception e
                                (timbre/error e "Problem duplicating cue")
                                (seesaw/alert (str e) :title "Problem Duplicating Cue" :type :error))))
                 :name "Duplicate Cue"))

(defn- expunge-deleted-cue
  "Removes all the items from a track that need to be cleaned up when
  the cue has been deleted. This function is designed to be used in a
  single swap! call for simplicity and efficiency."
  [track cue]
  (let [uuid (:uuid cue)]
    (-> track
        (update-in [:contents :cues :cues] dissoc uuid)
        (update-in [:cues-editor :panels] dissoc uuid))))

(defn- close-cue-editors?
  "Tries closing all open expression editors for the cue. If `force?` is
  true, simply closes them even if they have unsaved changes.
  Otherwise checks whether the user wants to save any unsaved changes.
  Returns truthy if there are none left open the user wants to deal
  with."
  [force? track cue]
  (let [track (latest-track track)]
    (every? (partial editors/close-editor? force?)
            (vals (get-in track [:cues-editor :expression-editors (:uuid cue)])))))

(defn players-playing-cue
  "Returns the set of players that are currently playing the specified
  cue. `track` must be current."
  [track cue]
  (let [show (latest-show (:file track))]
    (reduce (fn [result player]
              (if ((get-in track [:entered player]) (:uuid cue))
                (conj result player)
                result))
            #{}
            (util/players-signature-set (:playing show) (:signature track)))))

(defn entered?
  "Checks whether any player has entered the cue. `track` must be
  current."
  [track cue]
  ((reduce clojure.set/union (vals (:entered track))) (:uuid cue)))

#_(defn- players-inside-cue
  "Returns the set of players that are currently positioned inside the
  specified cue. `track` must be current."
  [track cue]
  (let [show (latest-show (:file track))]
    (reduce (fn [result player]
              (if ((get-in track [:entered player]) (:uuid cue))
                (conj result player)
                result))
            #{}
            (util/players-signature-set (:loaded show) (:signature track)))))

(defn- started?
  "Checks whether any players which have entered a cue is actually
  playing. `track` must be current."
  [track cue]
  (seq (players-playing-cue track cue)))

(defn hue-to-color
  "Returns a `Color` object of the given `hue` (in degrees, ranging from
  0.0 to 360.0). If `lightness` is not specified, 0.5 is used, giving
  the purest, most intense version of the hue. The color is fully
  opaque."
  ([hue]
   (hue-to-color hue 0.5))
  ([hue lightness]
   (let [color (color/hsla (/ hue 360.0) 1.0 lightness)]
     (Color. @(color/as-int24 color)))))

(defn color-to-hue
  "Extracts the hue number (in degrees) from a Color object. If
  colorless, red is the default."
  [^Color color]
  (* 360.0 (color/hue (color/int32 (.getRGB color)))))

(def cue-opacity
  "The degree to which cues replace the underlying waveform colors when
  overlaid on top of them."
  (float 0.65))

(defn cue-lightness
  "Calculates the lightness with which a cue should be painted, based on
  the track's tripped state and whether the cue is entered and
  playing. `track` must be current."
  [track cue]
  (if (and (:tripped track) (entered? track cue))
    (if (started? track cue) 0.8 0.65)
    0.5))

(defn send-cue-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a cue has changed state. `track` must be current, and
  `cue` can either be a cue map, or a uuid by which such a cue can be
  looked up. If it has been deleted, nothing is sent. `event` is the
  key identifying how look up the appropriate MIDI message or custom
  expression in the cue, and `status-or-beat` is the protocol message,
  if any, which caused the state change, if any."
  [track cue event status-or-beat]
  #_(timbre/info "sending cue messages" event (.getTimestamp status-or-beat)
               (if (instance? Beat status-or-beat)
                 (str "Beat " (.getBeatWithinBar status-or-beat) "/4")
                 (str "Status " (.getBeatNumber status-or-beat))))
  (when-let [cue (find-cue track cue)]
    (try
      (let [base-event                     ({:entered         :entered
                                             :exited          :entered
                                             :started-on-beat :started-on-beat
                                             :ended           (get-in track [:cues (:uuid cue) :last-entry-event])
                                             :started-late    :started-late} event)
            base-message                   (get-in cue [:events base-event :message])
            effective-base-event           (if (= "Same" base-message) :started-on-beat base-event)
            {:keys [message note channel]} (get-in cue [:events effective-base-event])]
        #_(timbre/info "send-cue-messages" event base-event effective-base-event message note channel)
        (when (#{"Note" "CC"} message)
          (when-let [output (su/get-chosen-output track)]
            (if (#{:exited :ended} event)
              (case message
                "Note" (midi/midi-note-off output note (dec channel))
                "CC"   (midi/midi-control output note 0 (dec channel)))
              (case message
                "Note" (midi/midi-note-on output note 127 (dec channel))
                "CC"   (midi/midi-control output note 127 (dec channel))))))
        (when (= "Custom" message)
          (let [effective-event (if (and (= "Same" base-message) (= :started-late event)) :started-on-beat event)]
            (run-cue-function track cue effective-event status-or-beat false))))
      (when (#{:started-on-beat :started-late} event)
        ;; Record how we started this cue so we know which event to send upon ending it.
        (swap-track! track assoc-in [:cues (:uuid cue) :last-entry-event] event))
      (catch Exception e
        (timbre/error e "Problem reporting cue event" event)))))

(defn cleanup-cue
  "Process the removal of a cue, either via deletion, or because the
  show is closing. If `force?` is true, any unsaved expression editors
  will simply be closed. Otherwise, they will block the cue removal,
  which will be indicated by this function returning falsey. Run any
  appropriate custom expressions and send configured MIDI messages to
  reflect the departure of the cue."
  [force? track cue]
  (when (close-cue-editors? force? track cue)
    (let [[_ track] (latest-show-and-track track)]
      (when (:tripped track)
        (when (seq (players-playing-cue track cue))
          (send-cue-messages track cue :ended nil))
        (when (entered? track cue)
          (send-cue-messages track cue :exited nil))))
    true))

(defn- delete-cue-action
  "Creates the menu action which deletes a cue after confirmation."
  [track cue panel]
  (seesaw/action :handler (fn [_]
                            (when (seesaw/confirm panel (str "This will irreversibly remove the cue, losing any\r\n"
                                                             "configuration and expressions created for it.")
                                                  :type :question
                                                  :title (str "Delete Cue “" (:comment (find-cue track cue)) "”?"))
                              (try
                                (cleanup-cue true track cue)
                                (swap-track! track expunge-deleted-cue cue)
                                (su/update-track-gear-icon track)
                                (build-cues track)
                                (catch Exception e
                                  (timbre/error e "Problem deleting cue")
                                  (seesaw/alert (str e) :title "Problem Deleting Cue" :type :error)))))
                 :name "Delete Cue"))

(defn- sanitize-cue-for-library
  "Removes the elements of a cue that will not be stored in the library.
  Returns a tuple of the name by which it will be stored, and the
  content to be stored (or compared to see if it matches another cue)."
  [cue]
   [(:comment cue) (dissoc cue :uuid :start :end :hue :linked)])

(defn linked-cues-equal?
  "Checks whether all the supplied cues have the same values for any
  elements that would be tied together if they were linked cues."
  [& cues]
  (apply = (map #(select-keys % [:events :expressions]) cues)))

(defn- cue-in-library?
  "Checks whether there is a cue matching the specified name is already
  present in the library. Returns falsy if there is none, `:matches`
  if there is an exact match, or `:conflict` if there is a different
  cue with that name present. When comparing cues, the location of the
  cue does not matter."
  [show comment content]
  (when-let [existing (get-in show [:contents :cue-library comment])]
    (if (= existing content) :matches :conflict)))

(defn- update-library-button-visibility
  "Makes sure that the Library button is visible in any open Cue Editor
  windows if the library has any cues in it, and is hidden otherwise."
  [show]
  (let [show           (latest-show show)
        library-empty? (empty? (get-in show [:contents :cue-library]))]
    (doseq [[_ track] (:tracks show)]
      (when-let [editor (:cues-editor track)]
        (let [button (seesaw/select (:frame editor) [:#library])]
          (if library-empty?
            (seesaw/hide! button)
            (seesaw/show! button)))))))

(defn- add-cue-to-library
  "Adds a cue to a show's cue library."
  [show cue-name cue]
  (swap-show! show assoc-in [:contents :cue-library cue-name] cue))

(defn- add-cue-to-folder
  "Adds a cue to a folder in the cue library."
  [show folder cue-name]
  (swap-show! show update-in [:contents :cue-library-folders folder] (fnil conj #{}) cue-name))

(defn- library-cue-action
  "Creates the menu action which either adds a cue to the library, or
  removes or updates it after confirmation, if there is already a cue
  of the same name in the library."
  [track cue panel]
  (let [[show track]      (latest-show-and-track track)
        cue               (find-cue track cue)
        [comment content] (sanitize-cue-for-library cue)]
    (if (clojure.string/blank? comment)
      (seesaw/action :name "Type a Comment to add Cue to Library" :enabled? false)
      (if-let [existing (cue-in-library? show comment content)]
        ;; The cue is already in the library, either update or remove it.
        (case existing
          :matches  ; The cue exactly matches what's in the library, offer to remove it.
          (seesaw/action :name "Remove Cue from Library"
                         :handler (fn [_]
                                    (swap-show! show update-in [:contents :cue-library] dissoc comment)
                                    (update-library-button-visibility show)))

          :conflict ; The cue is different from what is in the library, offer to update it.
          (seesaw/action :name "Update Cue in Library"
                         :handler (fn [_]
                                    (when (seesaw/confirm panel (str "This will replace the existing library cue with "
                                                                     "the same name.\r\n"
                                                                     "If you want to keep both, rename this cue first "
                                                                     "and try again.")
                                                          :type :question :title "Replace Library Cue?")
                                      (swap-show! show assoc-in [:contents :cue-library comment] content)))))
        ;; The cue is not in the library, so offer to add it.
        (let [folders (get-in show [:contents :cue-library-folders])]
          (if (empty? folders)
            ;; No folders, simply provide an action to add to top level.
            (seesaw/action :name "Add Cue to Library"
                           :handler (fn [_]
                                      (add-cue-to-library show comment content)
                                      (update-library-button-visibility show)))
            ;; Provide a menu to add to each library folder or the top level.
            (seesaw/menu :text "Add Cue to Library"
                         :items (concat
                                 (for [folder (sort (keys folders))]
                                   (seesaw/action :name (str "In Folder “" folder "”")
                                                  :handler (fn [_]
                                                             (add-cue-to-library show comment content)
                                                             (add-cue-to-folder show folder comment)
                                                             (update-library-button-visibility show))))
                                 [(seesaw/action :name "At Top Level"
                                                 :handler (fn [_]
                                                            (add-cue-to-library show comment content)
                                                            (update-library-button-visibility show)))]))))))))
(defn cue-preview-rectangle
  "Calculates the outline of a cue within the coordinate system of the
  waveform preview component in a track row of a show window, taking
  into account its lane assignment and cluster of neighbors. `track`
  and `cue` must be current."
  ^Rectangle2D$Double [track cue ^WaveformPreviewComponent preview]
  (let [[lane num-lanes] (get-in track [:cues :position (:uuid cue)])
        lane-height      (double (max 1.0 (/ (.getHeight preview) num-lanes)))
        x-for-beat       (fn [beat] (.millisecondsToX preview (.getTimeWithinTrack ^BeatGrid (:grid track) beat)))
        x                (x-for-beat (:start cue))
        w                (- (x-for-beat (:end cue)) x)
        y                (double (* lane (/ (.getHeight preview) num-lanes)))]
    (java.awt.geom.Rectangle2D$Double. (double x) y (double w) lane-height)))

(def selection-opacity
  "The degree to which the active selection replaces the underlying
  waveform colors."
  (float 0.5))

(defn paint-preview-cues
  "Draws the cues, if any, on top of the preview waveform. If there is
  an open cues editor window, also shows its current view of the wave,
  unless it is in auto-scroll mode."
  [show signature ^WaveformPreviewComponent preview ^Graphics2D graphics]
  (let [show           (latest-show show)
        ^Graphics2D g2 (.create graphics)
        cliprect       (.getClipBounds g2)
        track          (get-in show [:tracks signature])
        beat-for-x     (fn [x] (.findBeatAtTime ^BeatGrid (:grid track) (.getTimeForX preview x)))
        from           (beat-for-x (.x cliprect))
        to             (inc (beat-for-x (+ (.x cliprect) (.width cliprect))))
        cue-intervals  (get-in track [:cues :intervals])]
    (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER cue-opacity))
    (doseq [cue (map (partial find-cue track) (util/iget cue-intervals from to))]
      (.setPaint g2 (hue-to-color (:hue cue) (cue-lightness track cue)))
      (.fill g2 (cue-preview-rectangle track cue preview)))
    (when-let [editor (:cues-editor track)]
      (let [{:keys [^WaveformDetailComponent wave ^JScrollPane scroll]} editor]
        (when-not (.getAutoScroll wave)
          (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER
                                                                 selection-opacity))
          (.setPaint g2 Color/white)
          (.setStroke g2 (java.awt.BasicStroke. 3))
          (let [view-rect  (.getViewRect (.getViewport scroll))
                start-time (.getTimeForX wave (.-x view-rect))
                end-time   (.getTimeForX wave (+ (.-x view-rect) (.-width view-rect)))
                x          (.millisecondsToX preview start-time)
                width      (- (.millisecondsToX preview end-time) x)]
            (.draw g2 (java.awt.geom.Rectangle2D$Double. (double x) 0.0
                                                         (double width) (double (dec (.getHeight preview)))))))))))

(defn- get-current-selection
  "Returns the starting and ending beat of the current selection in the
  track, ignoring selections that have been dragged to zero size."
  [track]
  (when-let [selection (get-in (latest-track track) [:cues-editor :selection])]
    (when (> (second selection) (first selection))
      selection)))

(defn- paint-cues-and-beat-selection
  "Draws the cues and the selected beat range, if any, on top of the
  waveform."
  [track ^WaveformDetailComponent wave ^Graphics2D graphics]
  (let [^Graphics2D g2      (.create graphics)
        ^Rectangle cliprect (.getClipBounds g2)
        track               (latest-track track)
        from                (.getBeatForX wave (.x cliprect))
        to                  (inc (.getBeatForX wave (+ (.x cliprect) (.width cliprect))))
        cue-intervals       (get-in track [:cues :intervals])]
    (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER cue-opacity))
    (doseq [cue (map (partial find-cue track) (util/iget cue-intervals from to))]
      (.setPaint g2 (hue-to-color (:hue cue) (cue-lightness track cue)))
      (.fill g2 (cue-rectangle track cue wave)))
    (when-let [[start end] (get-current-selection track)]
      (let [x (.getXForBeat wave start)
            w (- (.getXForBeat wave end) x)]
        (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER selection-opacity))
        (.setPaint g2 Color/white)
        (.fill g2 (java.awt.geom.Rectangle2D$Double. (double x) 0.0 (double w) (double (.getHeight wave))))))
    (.dispose g2)))

(defn repaint-cue
  "Causes a single cue to be repainted in the track preview and (if one
  is open) the cues editor, because it has changed entered or active
  state. `cue` can either be the cue object or its uuid."
  [track cue]
  (let [track (latest-track track)
        cue   (find-cue track cue)]
    (when-let [preview-loader (:preview track)]
      (when-let [^WaveformPreviewComponent preview (preview-loader)]
        (let [preview-rect (cue-preview-rectangle track cue preview)]
          (.repaint ^JComponent (:preview-canvas track)
                    (.x preview-rect) (.y preview-rect) (.width preview-rect) (.height preview-rect)))))
    (when-let [^WaveformDetailComponent wave (get-in track [:cues-editor :wave])]
      (let [cue-rect (cue-rectangle track cue wave)]
        (.repaint wave (.x cue-rect) (.y cue-rect) (.width cue-rect) (.height cue-rect))))))

(defn- paint-cue-state
  "Draws a representation of the state of the cue, including whether its
  track is enabled and whether any players are positioned or playing
  inside it (as deterimined by the function passed in `f`)."
  [track cue f c ^Graphics2D g]
  (let [w            (double (seesaw/width c))
        h            (double (seesaw/height c))
        outline      (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        [show track] (latest-show-and-track track)
        enabled?     (su/enabled? show track)
        active?      (f track cue)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (when active? ; Draw the inner filled circle showing the cue is entered or playing.
      (.setPaint g (if enabled? Color/green Color/lightGray))
      (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))

    ;; Draw the outer circle that reflects the enabled state of the track itself.
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if enabled? Color/green Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(def cue-events
  "The three kind of events that get clusters of UI components in a
  cue row for configuring MIDI messages."
  [:entered :started-on-beat :started-late])

(defn- cue-event-component-id
  "Builds the keyword used to uniquely identify an component for
  configuring one of the cue event MIDI parameters. `event` will be
  one of `cue-events` above, and `suffix` will be \"message\",
  \"note\", or \"channel\". If `hash` is truthy, the keyword will
  start with the ugly, unidiomatic `#` that seesaw uses to look up a
  widget by unique ID keyword."
  ([event suffix]
   (cue-event-component-id event suffix false))
  ([event suffix hash]
   (keyword (str (when hash "#") (name event) "-" suffix))))

(defn- attach-cue-custom-editor-opener
  "Sets up an action handler so that when one of the popup menus is set
  to Custom, if there is not already an expession of the appropriate
  kind present, an editor for that expression is automatically
  opened."
  [track cue menu event panel gear]
  (seesaw/listen menu
                 :action-performed (fn [_]
                                     (let [choice (seesaw/selection menu)
                                           cue    (find-cue track cue)]
                                       (when (and (= "Custom" choice)
                                                  (not (:creating cue))
                                                  (clojure.string/blank? (get-in cue [:expressions event])))
                                         (editors/show-cue-editor event track cue panel
                                                                  #(update-cue-gear-icon track cue gear)))))))

(defn- attach-cue-message-visibility-handler
  "Sets up an action handler so that when one of the message menus is
  changed, the appropriate UI elements are shown or hidden. Also
  arranges for the proper expression editor to be opened if Custom is
  chosen for the message type and that expression is currently empty."
  [track cue event gear]
  (let [panel           (get-in (latest-track track) [:cues-editor :panels (:uuid cue)])
        message-menu    (seesaw/select panel [(cue-event-component-id event "message" true)])
        note-spinner    (seesaw/select panel [(cue-event-component-id event "note" true)])
        label           (seesaw/select panel [(cue-event-component-id event "channel-label" true)])
        channel-spinner (seesaw/select panel [(cue-event-component-id event "channel" true)])]
    (seesaw/listen message-menu
                   :action-performed (fn [_]
                                       (let [choice (seesaw/selection message-menu)]
                                         (if (#{"Same" "None"} choice)
                                           (seesaw/hide! [note-spinner label channel-spinner])
                                           (seesaw/show! [note-spinner label channel-spinner])))))
    (attach-cue-custom-editor-opener track cue message-menu event panel gear)))

(defn- create-cue-event-components
  "Builds and returns the combo box and spinners needed to configure one
  of the three events that can be reported about a cue. `event` will
  be one of `cue-events`, above."
  [track cue event default-note]
  (let [message       (seesaw/combobox :id (cue-event-component-id event "message")
                                       :model (case event
                                                :started-late ["Same" "None" "Note" "CC" "Custom"]
                                                ["None" "Note" "CC" "Custom"])
                                       :selected-item nil  ; So update in create-cue-panel saves default.
                                       :listen [:item-state-changed
                                                #(swap-cue! track cue
                                                            assoc-in [:events event :message]
                                                            (seesaw/selection %))])
        note          (seesaw/spinner :id (cue-event-component-id event "note")
                                      :model (seesaw/spinner-model (or (get-in cue [:events event :note]) default-note)
                                                                   :from 1 :to 127)
                                      :listen [:state-changed
                                               #(swap-cue! track cue
                                                           assoc-in [:events event :note]
                                                           (seesaw/value %))])
        channel       (seesaw/spinner :id (cue-event-component-id event "channel")
                                      :model (seesaw/spinner-model (or (get-in cue [:events event :channel]) 1)
                                                                   :from 1 :to 16)
                                      :listen [:state-changed
                                               #(swap-cue! track cue
                                                           assoc-in [:events event :channel]
                                                           (seesaw/value %))])
        channel-label (seesaw/label :id (cue-event-component-id event "channel-label") :text "Channel:")]
    {:message       message
     :note          note
     :channel       channel
     :channel-label channel-label}))

(defn build-cue-folder-menu
  "Creates a menu for a folder in the cue library, containing actions
  that add all the cues present in that folder to the track.
  `cue-action-builder-fn` is the function that will be called to
  create the action associated with a cue in the menu. It will be
  called with the cue name, cue contents, and track."
  [show track folder-name cues-in-folder cue-action-builder-fn]
  (let [cue-actions (filter identity
                            (for [cue-name (sort cues-in-folder)]
                              (when-let [cue (get-in (latest-show show) [:contents :cue-library cue-name])]
                                (cue-action-builder-fn cue-name cue track))))]
    (seesaw/menu :text folder-name
                 :items (if (seq cue-actions)
                          cue-actions
                          [(seesaw/action :name "No Cues in Folder" :enabled? false)]))))

(defn build-cue-folder-menus
  "Creates a menu for each folder in the cue library, containing actions
  that do something appropriate when a cue is chosen. Returns a tuple
  of that menu along with a set of the names of all the cues which
  were found in any folder, so they can be omitted from the top-level
  menu. `cue-action-builder-fn` is the function that will be called to
  create the action associated with a cue in the menu. It will be
  called with the cue name, cue contents, and track."
  [show track cue-action-builder-fn]
  (reduce (fn [[menus cues-in-folders] [folder-name cues-in-folder]]
            [(conj menus (build-cue-folder-menu show track folder-name cues-in-folder cue-action-builder-fn))
             (clojure.set/union cues-in-folders cues-in-folder)])
          [[] #{}]
          (get-in (latest-show show) [:contents :cue-library-folders])))

(defn- build-cue-library-popup-items
  "Creates the popup menu items allowing you to do something with cues
  in the library. `cue-action-builder-fn` is the function that will be
  called to create the action associated with a cue in the menu. It
  will be called with the cue name, cue contents, and track."
  [track cue-action-builder-fn]
  (let [[show track] (latest-show-and-track track)
        library      (sort-by first (vec (get-in show [:contents :cue-library])))]
    (if (empty? library)
      [(seesaw/action :name "No Cues in Show Library" :enabled? false)]
      (let [[folder-menus cues-in-folders] (build-cue-folder-menus show track cue-action-builder-fn)]
        (concat folder-menus
                (filter identity
                        (for [[cue-name cue] library]
                          (when-not (cues-in-folders cue-name)
                            (cue-action-builder-fn cue-name cue track)))))))))

(defn- library-cue-folder
  "Returns the name of the folder, if any, that a library cue was filed
  in. Returns `nil` for top-level cues. `show` must be current."
  [show library-cue-name]
  (some (fn [[folder cues]] (when (cues library-cue-name) folder)) (get-in show [:contents :cue-library-folders])))

(defn- full-library-cue-name
  "Returns the name of the cue surrounded by curly quotation marks. If
  the cue is in a folder, it is preceded by the folder name (also in
  quotes) and an arrow. Arguments must be current."
  [show library-cue-name]
  (str (when-let [folder (library-cue-folder show library-cue-name)] (str "“" folder "” → "))
       "“" library-cue-name "”"))

(defn- update-cue-panel-from-linked
  "Updates all the user elements of a cue to reflect the values that
  have changed due to a linked library cue."
  [track cue]
  (let [cue   (find-cue track cue)
        panel (get-in track [:cues-editor :panels (:uuid cue)])]
    (update-cue-gear-icon track cue (seesaw/select panel [:#gear]))
    (doseq [[event elems] (:events cue)]
      (doseq [[elem value] elems]
        (let [id     (cue-event-component-id event (name elem) true)
              widget (seesaw/select panel [id])]
          (seesaw/value! widget value))))
    (update-cue-gear-icon track cue (seesaw/select panel [:#gear]))))

(defn- build-link-cue-action
  "Creates an action that links an existing cue to a library cue. All
  arguments must be current."
  [show existing-cue button library-cue-name library-cue track]
  (seesaw/action :name library-cue-name
                 :handler (fn [_]
                            (when (or (linked-cues-equal? library-cue existing-cue)
                                      (seesaw/confirm button (str "Linking will replace the contents of this cue with"
                                                                  "\r\nthe contents of library cue "
                                                                  (full-library-cue-name show library-cue-name) ".")
                                                      :type :question
                                                      :title (str "Link Cue “" (:comment existing-cue) "”?")))
                              (swap-cue! track existing-cue
                                         (fn [cue]
                                           (-> cue
                                               (dissoc :expressions)
                                               (merge (dissoc library-cue :comment)
                                                      {:linked library-cue-name}))))
                              (update-cue-panel-from-linked track existing-cue)
                              (update-cue-link-icon track existing-cue button)))))

(defn- build-cue-link-button-menu
  "Builds the menu that appears when you click in a cue's Link button,
  either offering to link or unlink the cue as appropriate, or telling
  you the library is empty."
  [track cue button]
  (let [[show track] (latest-show-and-track track)
        cue          (find-cue track cue)
        library      (sort-by first (vec (get-in show [:contents :cue-library])))]
    (if (empty? library)
      [(seesaw/action :name "No Cues in Show Library" :enabled? false)]
      (if-let [link (:linked cue)]
        [(seesaw/action :name (str "Unlink from Library Cue " (full-library-cue-name show link))
                         :handler (fn [_]
                                    (swap-cue! track cue dissoc :linked)
                                    (update-cue-link-icon track cue button)))]
        [(seesaw/menu :text "Link to Library Cue"
                      :items (build-cue-library-popup-items track (partial build-link-cue-action show cue button)))]))))

(defn- create-cue-panel
  "Called the first time a cue is being worked with in the context of
  a cues editor window. Creates the UI panel that is used to configure
  the cue. Returns the panel after updating the cue to know about it.
  `track` and `cue` must be current."
  [track cue]
  (let [update-comment (fn [c]
                         (let [comment (seesaw/text c)]
                           (swap-cue! track cue assoc :comment comment)))
        comment-field  (seesaw/text :id :comment :paint (partial util/paint-placeholder "Comment")
                                    :text (:comment cue) :listen [:document update-comment])
        gear           (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
        link           (seesaw/button :id :link :icon (link-button-icon cue))
        start-model    (seesaw/spinner-model (:start cue) :from 1 :to (dec (:end cue)))
        end-model      (seesaw/spinner-model (:end cue) :from (inc (:start cue))
                                             :to (long (.beatCount ^BeatGrid (:grid track))))

        start  (seesaw/spinner :id :start
                               :model start-model
                               :listen [:state-changed
                                        (fn [e]
                                          (let [new-start (seesaw/selection e)]
                                            (swap-cue! track cue assoc :start new-start)
                                            (update-cue-spinner-models track cue start-model end-model)))])
        end    (seesaw/spinner :id :end
                               :model end-model
                               :listen [:state-changed
                                        (fn [e]
                                          (let [new-end (seesaw/selection e)]
                                            (swap-cue! track cue assoc :end new-end)
                                            (update-cue-spinner-models track cue start-model end-model)))])
        swatch (seesaw/canvas :size [18 :by 18]
                              :paint (fn [^JComponent component ^Graphics2D graphics]
                                       (let [cue (find-cue track cue)]
                                         (.setPaint graphics (hue-to-color (:hue cue)))
                                         (.fill graphics (java.awt.geom.Rectangle2D$Double.
                                                          0.0 0.0 (double (.getWidth component))
                                                          (double (.getHeight component)))))))

        event-components (apply merge (map-indexed (fn [index event]
                                                     {event (create-cue-event-components track cue event (inc index))})
                                                   cue-events))

        panel (mig/mig-panel
               :items [[(seesaw/label :text "Start:")]
                       [start]
                       [(seesaw/label :text "End:") "gap unrelated"]
                       [end]
                       [comment-field "gap unrelated, pushx, growx"]
                       [(seesaw/label :text "Hue:") "gap unrelated"]
                       [swatch "wrap"]

                       [gear]
                       ["Entered:" "gap unrelated, align right"]
                       [(seesaw/canvas :id :entered-state :size [18 :by 18] :opaque? false
                                       :tip "Outer ring shows track enabled, inner light when player(s) positioned inside cue."
                                       :paint (partial paint-cue-state track cue entered?))
                        "spanx, split"]
                       [(seesaw/label :text "Message:" :halign :right) "gap unrelated, sizegroup first-message"]
                       [(get-in event-components [:entered :message])]
                       [(get-in event-components [:entered :note]) "hidemode 3"]
                       [(get-in event-components [:entered :channel-label]) "gap unrelated, hidemode 3"]
                       [(get-in event-components [:entered :channel]) "hidemode 2, wrap"]

                       [link]
                       ["Started:" "gap unrelated, align right"]
                       [(seesaw/canvas :id :started-state :size [18 :by 18] :opaque? false
                                       :tip "Outer ring shows track enabled, inner light when player(s) playing inside cue."
                                       :paint (partial paint-cue-state track cue started?))
                        "spanx, split"]
                       ["On-Beat Message:" "gap unrelated, sizegroup first-message"]
                       [(get-in event-components [:started-on-beat :message])]
                       [(get-in event-components [:started-on-beat :note]) "hidemode 3"]
                       [(get-in event-components [:started-on-beat :channel-label]) "gap unrelated, hidemode 3"]
                       [(get-in event-components [:started-on-beat :channel]) "hidemode 3"]

                       ["Late Message:" "gap 30"]
                       [(get-in event-components [:started-late :message])]
                       [(get-in event-components [:started-late :note]) "hidemode 3"]
                       [(get-in event-components [:started-late :channel-label]) "gap unrelated, hidemode 3"]
                       [(get-in event-components [:started-late :channel]) "hidemode 3"]])
        popup-fn (fn [_] (concat (cue-editor-actions track cue panel gear)
                                 [(seesaw/separator) (cue-simulate-menu track cue) (su/track-inspect-action track)
                                  (seesaw/separator) (scroll-wave-to-cue-action track cue) (seesaw/separator)
                                  (duplicate-cue-action track cue) (library-cue-action track cue panel)
                                  (delete-cue-action track cue panel)]))]

    ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
    ;; or right click on the gear button. Also set the proper initial gear appearance. Add the popup builder to
    ;; the panel user data so that it can be used when control-clicking on a cue in the waveform as well.
    (seesaw/config! [panel gear] :popup popup-fn)
    (seesaw/config! panel :user-data {:popup popup-fn})
    (seesaw/listen gear
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (popup-fn e))]
                                      (util/show-popup-from-button gear popup e))))
    (update-cue-gear-icon track cue gear)

    ;; Attach the link menu to the link button, both as a normal and right click.
    (seesaw/config! [link] :popup (build-cue-link-button-menu track cue link))
    (seesaw/listen link
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (build-cue-link-button-menu track cue link))]
                                      (util/show-popup-from-button link popup e))))

    (seesaw/listen swatch
                   :mouse-pressed (fn [_]
                                    (let [cue (find-cue track cue)]
                                      (when-let [color (chooser/choose-color panel :color (hue-to-color (:hue cue))
                                                                             :title "Choose Cue Hue")]
                                        (swap-cue! track cue assoc :hue (color-to-hue color))
                                        (seesaw/repaint! [swatch])
                                        (repaint-cue track cue)))))


    ;; Record the new panel in the show, in preparation for final configuration.
    (swap-track! track assoc-in [:cues-editor :panels (:uuid cue)] panel)

    ;; Establish the saved or initial settings of the UI elements, which will also record them for the
    ;; future, and adjust the interface, thanks to the already-configured item changed listeners.
    (swap-cue! track cue assoc :creating true)  ; Don't pop up expression editors while recreating the cue row.
    (doseq [event cue-events]
      ;; Update visibility when a Message selection changes. Also sets them up to automagically open the
      ;; expression editor for the Custom Enabled Filter if "Custom" is chosen as the Message.
      (attach-cue-message-visibility-handler track cue event gear)

      ;; Set the initial state of the Message menu which will, thanks to the above, set the initial visibilty.
      (seesaw/selection! (seesaw/select panel [(cue-event-component-id event "message" true)])
                         (or (get-in cue [:events event :message]) (if (= event :started-late) "Same" "None")))

      ;; In case this is the initial creation of the cue, record the defaulted values of the numeric inputs too.
      ;; This will have no effect if they were loaded.
      (swap-cue! track cue assoc-in [:events event :note]
                 (seesaw/value (seesaw/select panel [(cue-event-component-id event "note" true)])))
      (swap-cue! track cue assoc-in [:events event :channel]
                 (seesaw/value (seesaw/select panel [(cue-event-component-id event "channel" true)]))))
    (swap-cue! track cue dissoc :creating)  ; Re-arm Message menu to pop up the expression editor when Custom chosen.

    panel))  ; Return the newly-created and configured panel.

(defn update-cue-visibility
  "Determines the cues that should be visible given the filter text (if
  any) and state of the Only Entered checkbox if we are online.
  Updates the track's cues editor's `:visible` key to hold a vector of
  the visible cue UUIDs, sorted by their start and end beats followed
  by their comment and UUID. Then uses that to update the contents of
  the `cues` panel appropriately. Safely does nothing if the track has
  no cues editor window."
  [track]
  (let [track (latest-track track)]
    (when-let [editor (:cues-editor track)]
      (let [cues          (seesaw/select (:frame editor) [:#cues])
            panels        (get-in track [:cues-editor :panels])
            text          (get-in track [:contents :cues :filter])
            entered-only? (and (util/online?) (get-in track [:contents :cues :entered-only]))
            entered       (when entered-only? (reduce clojure.set/union (vals (:entered track))))
            old-visible   (get-in track [:cues-editor :visible])
            visible-cues  (filter identity
                                  (map (fn [uuid]
                                         (let [cue (get-in track [:contents :cues :cues uuid])]
                                           (when (and
                                                  (or (clojure.string/blank? text)
                                                      (clojure.string/includes?
                                                       (clojure.string/lower-case (:comment cue ""))
                                                       (clojure.string/lower-case text)))
                                                  (or (not entered-only?) (entered (:uuid cue))))
                                             cue)))
                                       (get-in track [:cues :sorted])))
            visible-uuids (mapv :uuid visible-cues)]
        (when (not= visible-uuids old-visible)
          (swap-track! track assoc-in [:cues-editor :visible] visible-uuids)
          (let [visible-panels (mapv (fn [cue color]
                                       (let [panel (or (get panels (:uuid cue)) (create-cue-panel track cue))]
                                         (seesaw/config! panel :background color)
                                         panel))
                                     visible-cues (cycle ["#eee" "#ddd"]))]
            (seesaw/config! cues :items (concat visible-panels [:fill-v]))))))))

(defn- set-entered-only
  "Update the cues UI so that all cues or only entered cues are
  visible."
  [track entered-only?]
  (swap-track! track assoc-in [:contents :cues :entered-only] entered-only?)
  (update-cue-visibility track))

(defn- set-auto-scroll
  "Update the cues UI so that the waveform automatically tracks the
  furthest position played if `auto?` is `true` and we are connected
  to a DJ Link network."
  [track ^WaveformDetailComponent wave auto?]
  (swap-track! track assoc-in [:contents :cues :auto-scroll] auto?)
  (.setAutoScroll wave (and auto? (util/online?)))
  (su/repaint-preview track)  ; Show or hide the editor viewport overlay if needed.
  (seesaw/scroll! wave :to [:point 0 0]))

(defn- set-zoom
  "Updates the cues UI so that the waveform is zoomed out by the
  specified factor, while trying to preserve the section of the wave
  at the specified x coordinate within the scroll pane if the scroll
  positon is not being controlled by the DJ Link network."
  [track ^WaveformDetailComponent wave zoom ^JScrollPane pane anchor-x]
  (swap-track! track assoc-in [:contents :cues :zoom] zoom)
  (let [bar   (.getHorizontalScrollBar pane)
        bar-x (.getValue bar)
        time  (.getTimeForX wave (+ anchor-x bar-x))]
    (.setScale wave zoom)
    (when-not (.getAutoScroll wave)
      (seesaw/invoke-later
       (let [time-x (.millisecondsToX wave time)]
         (.setValue bar (- time-x anchor-x)))))))

(defn- cue-filter-text-changed
  "Update the cues UI so that only cues matching the specified filter
  text, if any, are visible."
  [track text]
  (swap-track! track assoc-in [:contents :cues :filter] (clojure.string/lower-case text))
  (update-cue-visibility track))

(defn- save-cue-window-position
  "Update the saved dimensions of the cue editor window, so it can be
  reopened in the same state."
  [track ^JFrame window]
  (swap-track! track assoc-in [:contents :cues :window]
               [(.getX window) (.getY window) (.getWidth window) (.getHeight window)]))

(defn update-cue-window-online-status
  "Called whenever we change online status, so that any open cue windows
  can update their user interface appropriately. Invoked on the Swing
  event update thread, so it is safe to manipulate UI elements."
  [show online?]
  (let [show (latest-show show)]
    (doseq [track (vals (:tracks show))]
      (when-let [editor (:cues-editor track)]
        (let [checkboxes [(seesaw/select (:frame editor) [:#entered-only])
                          (seesaw/select (:frame editor) [:#auto-scroll])]
              auto?      (get-in track [:contents :cues :auto-scroll])]
          (if online?
            (seesaw/show! checkboxes)
            (seesaw/hide! checkboxes))
          (when auto?
            (.setAutoScroll ^WaveformDetailComponent (:wave editor) (and auto? online?))
            (seesaw/scroll! (:wave editor) :to [:point 0 0])))
        (update-cue-visibility track)))))

(def max-zoom
  "The largest extent to which we can zoom out on the waveform in the
  cues editor window."
  64)

(defn- find-cue-under-mouse
  "Checks whether the mouse is currently over any cue, and if so returns
  it as the first element of a tuple. Always returns the latest
  version of the supplied track as the second element of the tuple."
  [track ^WaveformDetailComponent wave ^MouseEvent e]
  (let [point (.getPoint e)
        track (latest-track track)
        cue (first (filter (fn [cue] (.contains (cue-rectangle track cue wave) point))
                           (vals (get-in track [:contents :cues :cues]))))]
    [cue track]))

(def delete-cursor
  "A custom cursor that indicates a selection will be canceled."
  (delay (.createCustomCursor (java.awt.Toolkit/getDefaultToolkit)
                              (.getImage ^javax.swing.ImageIcon (seesaw/icon "images/Delete-cursor.png"))
                              (java.awt.Point. 7 7)
                              "Deselect")))

(def move-w-cursor
  "A custom cursor that indicates the left edge of something will be moved."
  (delay (.createCustomCursor (java.awt.Toolkit/getDefaultToolkit)
                              (.getImage ^javax.swing.ImageIcon (seesaw/icon "images/Move-W-cursor.png"))
                              (java.awt.Point. 7 7)
                              "Move Left Edge")))

(def move-e-cursor
  "A custom cursor that indicates the right edge of something will be moved."
  (delay (.createCustomCursor (java.awt.Toolkit/getDefaultToolkit)
                              (.getImage ^javax.swing.ImageIcon (seesaw/icon "images/Move-E-cursor.png"))
                              (java.awt.Point. 7 7)
                              "Move Right Edge")))

(defn- shift-down?
  "Checks whether the shift key was pressed when an event occured."
  [^InputEvent e]
  (pos? (bit-and (.getModifiersEx e) MouseEvent/SHIFT_DOWN_MASK)))

(defn- context-click?
  "Checks whether the control key was pressed when a mouse event
  occured, or if it was the right button."
  [^MouseEvent e]
  (or (javax.swing.SwingUtilities/isRightMouseButton e)
      (pos? (bit-and (.getModifiersEx e) MouseEvent/CTRL_DOWN_MASK))))

(defn- handle-wave-key
  "Processes a key event while a cue waveform is being displayed, in
  case it requires a cursor change."
  [track ^WaveformDetailComponent wave ^InputEvent e]
  (let [track (latest-track track)
        [unshifted shifted] (get-in track [:cues-editor :cursors])]
    (when unshifted  ; We have cursors defined, so apply the appropriate one
      (.setCursor wave (if (shift-down? e) shifted unshifted)))))

(defn- drag-cursor
  "Determines the proper cursor that will reflect the nearest edge of
  the selection that will be dragged, given the beat under the mouse."
  [track beat]
  (let [[start end]    (get-in (latest-track track) [:cues-editor :selection])
        start-distance (Math/abs (long (- beat start)))
        end-distance   (Math/abs (long (- beat end)))]
    (if (< start-distance end-distance) @move-w-cursor @move-e-cursor)))

(def click-edge-tolerance
  "The number of pixels we can click away from an edge but still count
  as dragging it."
  3)

(defn find-click-edge-target
  "Sees if the cursor is within a few pixels of an edge of the selection
  or a cue, and if so returns that as the drag darget should a click
  occur. If there is an active selection, its `start` and `end` will
  be supplied; similarly, if the mouse is over a `cue` that will be
  supplied."
  [track ^WaveformDetailComponent wave ^MouseEvent e [start end] cue]
  (cond
    (and start (<= (Math/abs (- (.getX e) (.getXForBeat wave start))) click-edge-tolerance))
    [nil :start]

    (and end (<= (Math/abs (- (.getX e) (.getXForBeat wave end))) click-edge-tolerance))
    [nil :end]

    cue
    (let [r (cue-rectangle track cue wave)]
      (if (<= (Math/abs (- (.getX e) (.getX r))) click-edge-tolerance)
        [cue :start]
        (when (<= (Math/abs (- (.getX e) (+ (.getX r) (.getWidth r)))) click-edge-tolerance)
          [cue :end])))))

(defn compile-cue-expressions
  "Compiles and installs all the expressions associated with a track's
  cue. Used both when opening a show, and when adding a cue from the
  library."
  [track cue]
  (doseq [[kind expr] (:expressions cue)]
    (let [editor-info (get @editors/show-track-cue-editors kind)]
      (try
        (swap-track! track assoc-in [:cues :expression-fns (:uuid cue) kind]
                     (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                        (editors/cue-editor-title kind track cue)))
        (catch Exception e
          (timbre/error e (str "Problem parsing " (:title editor-info)
                               " when loading Show. Expression:\n" expr "\n"))
          (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                             "Check the log file for details.")
                        :title "Exception during Clojure evaluation" :type :error))))))

(defn- build-library-cue-action
  "Creates an action that adds a cue from the library to the track."
  [cue-name cue track]
  (seesaw/action :name (str "New “" cue-name "” Cue")
                         :handler (fn [_]
                                    (try
                                      (let [uuid        (java.util.UUID/randomUUID)
                                            track       (latest-track track)
                                            [start end] (get-in track [:cues-editor :selection] [1 2])
                                            all-names   (map :comment (vals (get-in track [:contents :cues :cues])))
                                            new-name    (if (some #(= cue-name %) all-names)
                                                          (util/assign-unique-name all-names cue-name)
                                                          cue-name)
                                            new-cue     (merge cue {:uuid    uuid
                                                                    :start   start
                                                                    :end     end
                                                                    :hue     (assign-cue-hue track)
                                                                    :comment new-name})]
                                        (swap-track! track assoc-in [:contents :cues :cues uuid] new-cue)
                                        (swap-track! track update :cues-editor dissoc :selection)
                                        (su/update-track-gear-icon track)
                                        (build-cues track)
                                        (compile-cue-expressions track new-cue)
                                        (scroll-wave-to-cue track new-cue)
                                        (scroll-to-cue track new-cue true))
                                      (catch Exception e
                                        (timbre/error e "Problem adding Library Cue")
                                        (seesaw/alert (str e) :title "Problem adding Library Cue" :type :error))))))

(defn- show-cue-library-popup
  "Displays the popup menu allowing you to add a cue from the library to
  a track."
  [track ^WaveformDetailComponent wave ^MouseEvent e]
  (let [[cue track] (find-cue-under-mouse track wave e)
        popup-items (if cue
                      (let [panel    (get-in track [:cues-editor :panels (:uuid cue)])
                            popup-fn (:popup (seesaw/user-data panel))]
                        (popup-fn e))
                      (build-cue-library-popup-items track build-library-cue-action))]
    (util/show-popup-from-button wave (seesaw/popup :items popup-items) e)))

(defn- handle-wave-move
  "Processes a mouse move over the wave detail component, setting the
  tooltip and mouse pointer appropriately depending on the location of
  cues and selection."
  [track ^WaveformDetailComponent wave ^MouseEvent e]
  (let [[cue track]    (find-cue-under-mouse track wave e)
        x              (.getX e)
        beat           (long (.getBeatForX wave x))
        selection      (get-in track [:cues-editor :selection])
        [_ edge]       (find-click-edge-target track wave e selection cue)
        default-cursor (case edge
                         :start @move-w-cursor
                         :end   @move-e-cursor
                         (Cursor/getPredefinedCursor Cursor/CROSSHAIR_CURSOR))]
    (.setToolTipText wave (if cue
                            (or (:comment cue) "Unnamed Cue")
                            "Click and drag to select a beat range for the New Cue button."))
    (if selection
      (if (= selection [beat (inc beat)])
        (let [shifted   @delete-cursor ; We are hovering over a single-beat selection, and can delete it.
              unshifted default-cursor]
          (.setCursor wave (if (shift-down? e) shifted unshifted))
          (swap-track! track assoc-in [:cues-editor :cursors] [unshifted shifted]))
        (let [shifted   (drag-cursor track beat)
              unshifted default-cursor]
          (.setCursor wave (if (shift-down? e) shifted unshifted))
          (swap-track! track assoc-in [:cues-editor :cursors] [unshifted shifted])))
      (do
        (.setCursor wave default-cursor)
        (swap-track! track update :cues-editor dissoc :cursors)))))

(defn- find-selection-drag-target
  "Checks if a drag target for a general selection has already been
  established; if so, returns it, otherwise sets one up, unless we are
  still sitting on the initial beat of a just-created selection."
  [track start end beat]
  (or (get-in track [:cues-editor :drag-target])
      (when (not= start (dec end) beat)
        (let [start-distance (Math/abs (long (- beat start)))
              end-distance   (Math/abs (long (- beat (dec end))))
              target         [nil (if (< beat start) :start (if (< start-distance end-distance) :start :end))]]
          (swap-track! track assoc-in [:cues-editor :drag-target] target)
          target))))

(defn- handle-wave-drag
  "Processes a mouse drag in the wave detail component, used to adjust
  beat ranges for creating cues."
  [track ^WaveformDetailComponent wave ^MouseEvent e]
  (let [track          (latest-track track)
        ^BeatGrid grid (:grid track)
        x              (.getX e)
        beat           (long (.getBeatForX wave x))
        [start end]    (get-in track [:cues-editor :selection])
        [cue edge]     (find-selection-drag-target track start end beat)]
    ;; We are trying to adjust an existing cue or selection. Move the end that was nearest to the mouse.
    (when edge
      (if cue
        (do  ; We are dragging the edge of a cue.
          (if (= :start edge)
            (swap-cue! track cue assoc :start (min (dec (:end cue)) (max 1 beat)))
            (swap-cue! track cue assoc :end (max (inc (:start cue)) (min (.beatCount grid) (inc beat)))))
          (build-cues track))
        (swap-track! track assoc-in [:cues-editor :selection]  ; We are dragging the beat selection.
                     (if (= :start edge)
                       [(min end (max 1 beat)) end]
                       [start (max start (min (.beatCount grid) (inc beat)))])))

      (.setCursor wave (if (= :start edge) @move-w-cursor @move-e-cursor))
      (.repaint wave))
    (swap-track! track update :cues-editor dissoc :cursors)))  ; Cursor no longer depends on Shift key state.

(defn- handle-wave-click
  "Processes a mouse click in the wave detail component, used for
  setting up beat ranges for creating cues, and scrolling the lower
  pane to cues. Ignores right-clicks and control-clicks so those can
  pull up the context menu."
  [track ^WaveformDetailComponent wave ^MouseEvent e]
  (if (context-click? e)
    (show-cue-library-popup track wave e)
    (let [[cue track]     (find-cue-under-mouse track wave e)
          ^BeatGrid grid (:grid track)
          x               (.getX e)
          beat            (long (.getBeatForX wave x))
          selection       (get-in track [:cues-editor :selection])]
      (if (and (shift-down? e) selection)
        (if (= selection [beat (inc beat)])
          (do  ; Shift-click on single-beat selection clears it.
            (swap-track! track update :cues-editor dissoc :selection :cursors)
            (.setCursor wave (Cursor/getPredefinedCursor Cursor/CROSSHAIR_CURSOR)))
          (handle-wave-drag track wave e))  ; Adjusting an existing selection; we can handle it as a drag.
        (if-let [target (find-click-edge-target track wave e selection cue)]
          (do ; We are dragging the edge of the selection or a cue.
            (swap-track! track assoc-in [:cues-editor :drag-target] target)
            (handle-wave-drag track wave e))
          ;; We are starting a new selection.
          (if (< 0 beat (.beatCount grid))  ; Was the click in a valid place to make a selection?
            (do  ; Yes, set new selection.
              (swap-track! track assoc-in [:cues-editor :selection] [beat (inc beat)])
              (handle-wave-move track wave e))  ; Update the cursors.
            (swap-track! track update :cues-editor dissoc :selection))))  ; No, clear selection.
      (.repaint wave)
      (when cue (scroll-to-cue track cue false true)))))

(defn- handle-wave-release
  "Processes a mouse-released event in the wave detail component,
  cleaning up any drag-tracking structures and cursors that were in
  effect."
  [track ^WaveformDetailComponent wave ^MouseEvent e]
  (let [track (latest-track track)
        [cue-dragged] (get-in track [:cues-editor :drag-target])]
    (when cue-dragged
      (let [cue (find-cue track cue-dragged)
            panel (get-in track [:cues-editor :panels (:uuid cue)])]
        (seesaw/value! (seesaw/select panel [:#start]) (:start cue))
        (seesaw/value! (seesaw/select panel [:#end]) (:end cue))))
    (when-let [[start end] (get-in track [:cues-editor :selection])]
      (when (>= start end)  ; If the selection has shrunk to zero size, remove it.
        (swap-track! track update :cues-editor dissoc :selection))))
  (swap-track! track update :cues-editor dissoc :drag-target)
  (handle-wave-move track wave e))  ; This will restore the normal cursor.

(defn- assign-cue-lanes
  "Given a sorted list of the cues for a track, assigns each a
  non-overlapping lane number, choosing the smallest value that no
  overlapping neighbor has already been assigned. Returns a map from
  cue UUID to its assigned lane."
  [track cues cue-intervals]
  (reduce (fn [result cue]
            (let [neighbors (map (partial find-cue track) (util/iget cue-intervals (:start cue) (:end cue)))
                  used      (set (filter identity (map #(result (:uuid %)) neighbors)))]
              (assoc result (:uuid cue) (first (remove used (range))))))
          {}
          cues))

(defn- gather-cluster
  "Given a cue, returns the set of cues that overlap with it (including
  itself), and transitively any cues which overlap with them."
  [track cue cue-intervals]
  (let [neighbors (set (map (partial find-cue track) (util/iget cue-intervals (:start cue) (:end cue))))]
    (loop [result    #{cue}
           remaining (clojure.set/difference neighbors result)]
      (if (empty? remaining)
        result
        (let [current   (first remaining)
              result    (conj result current)
              neighbors (set (map (partial find-cue track) (util/iget cue-intervals (:start current) (:end current))))]
          (recur result (clojure.set/difference (clojure.set/union neighbors remaining) result)))))))

(defn- position-cues
  "Given a sorted list of the cues for a track, assigns each a
  non-overlapping lane, and determines how many lanes are needed to
  draw each overlapping cluster of cues. Returns a map from cue uuid
  to a tuple of the cue's lane assignment and cluster lane count."
  [track cues cue-intervals]
  (let [lanes (assign-cue-lanes track cues cue-intervals)]
    (reduce (fn [result cue]
              (if (result (:uuid cue))
                result
                (let [cluster   (set (map :uuid (gather-cluster track cue cue-intervals)))
                      max-lanes (inc (apply max (map lanes cluster)))]
                  (apply merge result (map (fn [uuid] {uuid [(lanes uuid) max-lanes]}) cluster)))))
            {}
            cues)))

(defn- cue-panel-constraints
  "Calculates the proper layout constraints for the cue waveform panel
  to properly fit the largest number of cue lanes required. We make
  sure there is always room to draw the waveform even if there are few
  lanes and a horizontal scrollbar ends up being needed."
  [track]
  (let [track       (latest-track track)
        max-lanes   (get-in track [:cues :max-lanes] 1)
        wave-height (max 92 (* max-lanes min-lane-height))]
    ["" "" (str "[][fill, " (+ wave-height 18) "]")]))

(defn build-cues
  "Updates the track structures to reflect the cues that are present. If
  there is an open cues editor window, also updates it. This will be
  called when the show is initially loaded, and whenever the cues are
  changed."
  [track]
  (let [track         (latest-track track)
        sorted-cues   (sort-by (juxt :start :end :comment :uuid)
                               (vals (get-in track [:contents :cues :cues])))
        cue-intervals (reduce (fn [result cue]
                                (util/iassoc result (:start cue) (:end cue) (:uuid cue)))
                              util/empty-interval-map
                              sorted-cues)
        cue-positions (position-cues track sorted-cues cue-intervals)]
    (swap-track! track #(-> %
                            (assoc-in [:cues :sorted] (mapv :uuid sorted-cues))
                            (assoc-in [:cues :intervals] cue-intervals)
                            (assoc-in [:cues :position] cue-positions)
                            (assoc-in [:cues :max-lanes] (apply max 1 (map second (vals cue-positions))))))
    (su/repaint-preview track)
    (when (:cues-editor track)
      (update-cue-visibility track)
      (repaint-all-cue-states track)
      (.repaint ^WaveformDetailComponent (get-in track [:cues-editor :wave]))
      (let [^JPanel panel (get-in track [:cues-editor :panel])]
        (seesaw/config! panel :constraints (cue-panel-constraints track))
        (.revalidate panel)))))

(defn- new-cue
  "Handles a click on the New Cue button, which creates a cue with the
  selected beat range, or a default range if there is no selection."
  [track]
  (let [track       (latest-track track)
        [start end] (get-in track [:cues-editor :selection] [1 2])
        uuid        (java.util.UUID/randomUUID)
        cue         {:uuid  uuid
                     :start start
                     :end   end
                     :hue   (assign-cue-hue track)
                     :comment (util/assign-unique-name (map :comment (vals (get-in track [:contents :cues :cues]))))}]
    (swap-track! track assoc-in [:contents :cues :cues uuid] cue)
    (swap-track! track update :cues-editor dissoc :selection)
    (su/update-track-gear-icon track)
    (build-cues track)
    (scroll-wave-to-cue track cue)
    (scroll-to-cue track cue true)))

(defn- start-animation-thread
  "Creates a background thread that updates the positions of any playing
  players 30 times a second so that the wave moves smoothly. The
  thread will exit whenever the cues window closes."
  [show track]
  (future
    (loop [editor (:cues-editor (latest-track track))]
      (when editor
        (try
          (Thread/sleep 33)
          (let [show (latest-show show)]
            (doseq [^Long player (util/players-signature-set (:playing show) (:signature track))]
              (when-let [position (.getLatestPositionFor util/time-finder player)]
                (.setPlaybackState ^WaveformDetailComponent (:wave editor)
                                   player (.getTimeFor util/time-finder player) (.playing position)))))
          (catch Throwable t
            (timbre/warn "Problem animating cues editor waveform" t)))
        (recur (:cues-editor (latest-track track)))))
    #_(timbre/info "Cues editor animation thread ending.")))

(defn- new-cue-folder
  "Opens a dialog in which a new cue folder can be created."
  [show track]
  (let [parent (get-in track [:cues-editor :frame])]
    (when-let [new-name (seesaw/invoke-now
                         (JOptionPane/showInputDialog parent "Choose the folder name:" "New Cue Library Folder"
                                                      javax.swing.JOptionPane/QUESTION_MESSAGE))]
      (when-not (clojure.string/blank? new-name)
        (if (contains? (get-in show [:contents :cue-library-folders]) new-name)
          (seesaw/alert parent (str "Folder “" new-name "” already exists.") :name "Duplicate Folder" :type :error)
          (swap-show! show assoc-in [:contents :cue-library-folders new-name] #{}))))))

(defn- rename-cue-folder
  "Opens a dialog in which a cue folder can be renamed."
  [show track folder]
  (let [parent (get-in track [:cues-editor :frame])]
    (when-let [new-name (seesaw/invoke-now
                         (JOptionPane/showInputDialog parent "Choose new name:" (str "Rename Folder “" folder "”")
                                                      javax.swing.JOptionPane/QUESTION_MESSAGE))]
      (when-not (or (clojure.string/blank? new-name) (= new-name folder))
        (if (contains? (get-in show [:contents :cue-library-folders]) new-name)
          (seesaw/alert parent (str "Folder “" new-name "” already exists.") :name "Duplicate Folder" :type :error)
          (swap-show! show (fn [current]
                             (let [folder-contents (get-in current [:contents :cue-library-folders folder])]
                               (-> current
                                   (assoc-in [:contents :cue-library-folders new-name] folder-contents)
                                   (update-in [:contents :cue-library-folders] dissoc folder))))))))))

(defn- remove-cue-folder
  "Opens a confirmation dialog for deleting a cue folder."
  [show track folder]
  (when (seesaw/confirm (get-in track [:cues-editor :frame])
                        (str "Removing a cue library folder will move all of its cues\r\n"
                             "back to the top level of the cue library.")
                        :type :question :title (str "Remove Folder “" folder "”?"))
    (swap-show! show update-in [:contents :cue-library-folders] dissoc folder)))

(defn- build-cue-library-button-menu
  "Builds the menu that appears when you click in the cue library
  button, which includes the same cue popup menu that is available
  when right-clicking in the track waveform, but adds options at the
  end for managing cue folders in case you have a lot of cues."
  [track]
  (let [[show track]  (latest-show-and-track track)
        folders (sort (keys (get-in show [:contents :cue-library-folders])))]
    (concat
     (build-cue-library-popup-items track build-library-cue-action)
     [(seesaw/menu :text "Manage Folders"
                   :items (concat
                           [(seesaw/action :name "New Folder"
                                           :handler (fn [_] (new-cue-folder show track)))]
                           (when (seq folders)
                             [(seesaw/menu :text "Rename"
                                           :items (for [folder folders]
                                                    (seesaw/action :name folder
                                                                   :handler (fn [_]
                                                                              (rename-cue-folder show track folder)))))
                              (seesaw/menu :text "Remove"
                                           :items (for [folder folders]
                                                    (seesaw/action :name folder
                                                                   :handler (fn [_]
                                                                              (remove-cue-folder
                                                                               show track folder)))))])))])))
(defn- create-cues-window
  "Create and show a new cues window for the specified show and track.
  Must be supplied current versions of `show` and `track.`"
  [show track parent]
  (let [track-root   (su/build-track-path show (:signature track))
        ^JFrame root (seesaw/frame :title (str "Cues for Track: " (su/display-title track))
                                   :on-close :nothing)
        wave         (WaveformDetailComponent. ^WaveformDetail (su/read-detail track-root)
                                               ^CueList (su/read-cue-list track-root)
                                               ^BeatGrid (:grid track))
        song-structure (when-let [bytes (su/read-song-structure track-root)]
                         (RekordboxAnlz$SongStructureTag. (ByteBufferKaitaiStream. bytes)))
        zoom-slider  (seesaw/slider :id :zoom :min 1 :max max-zoom :value (get-in track [:contents :cues :zoom] 4))
        filter-field (seesaw/text (get-in track [:contents :cues :filter] ""))
        entered-only (seesaw/checkbox :id :entered-only :text "Entered Only" :visible? (util/online?)
                                      :selected? (boolean (get-in track [:contents :cues :entered-only]))
                                      :listen [:item-state-changed #(set-entered-only track (seesaw/value %))])
        auto-scroll  (seesaw/checkbox :id :auto-scroll :text "Auto-Scroll" :visible? (util/online?)
                                      :selected? (boolean (get-in track [:contents :cues :auto-scroll]))
                                      :listen [:item-state-changed #(set-auto-scroll track wave (seesaw/value %))])
        lib-popup-fn (fn [] (seesaw/popup :items (build-cue-library-button-menu track)))
        zoom-anchor  (atom nil)  ; The x coordinate we want to keep the wave anchored at when zooming.
        wave-scroll  (proxy [javax.swing.JScrollPane] [wave]
                       (processMouseWheelEvent [^java.awt.event.MouseWheelEvent e]
                         (if (.isShiftDown e)
                           (proxy-super processMouseWheelEvent e)
                           (let [zoom (min max-zoom (max 1 (+ (.getScale wave) (.getWheelRotation e))))]
                             (reset! zoom-anchor (.getX e))
                             (seesaw/value! zoom-slider zoom)))))
        top-panel    (mig/mig-panel :background "#aaa" :constraints (cue-panel-constraints track)
                                    :items [[(seesaw/button :text "New Cue"
                                                            :listen [:action-performed
                                                                     (fn ([_] (new-cue track)))])]
                                            [(seesaw/button :id :library
                                                            :text (str "Library "
                                                                       (if (menus/on-windows?) "▼" "▾"))
                                                            :visible? (seq (get-in show [:contents :cue-library]))
                                                            :listen [:mouse-pressed
                                                                     (fn ([e] (util/show-popup-from-button
                                                                               (seesaw/to-widget e)
                                                                               (lib-popup-fn) e)))]
                                                            :popup (lib-popup-fn))
                                             "hidemode 3"]
                                            [(seesaw/label :text "Filter:") "gap unrelated"]
                                            [filter-field "pushx 4, growx 4"]
                                            [entered-only "hidemode 3"]
                                            [(seesaw/label :text "") "pushx1, growx1"]
                                            [auto-scroll "hidemode 3"]
                                            [zoom-slider]
                                            [(seesaw/label :text "Zoom") "wrap"]
                                            [wave-scroll "span, width 100%"]])
        cues         (seesaw/vertical-panel :id :cues)
        cues-scroll  (seesaw/scrollable cues)
        layout       (seesaw/border-panel :north top-panel :center cues-scroll)
        key-spy      (proxy [java.awt.KeyEventDispatcher] []
                       (dispatchKeyEvent [^java.awt.event.KeyEvent e]
                         (handle-wave-key track wave e)
                         false))
        close-fn     (fn [force?]
                       ;; Closes the cues window and performs all necessary cleanup. If `force?` is true,
                       ;; will do so even in the presence of windows with unsaved user changes. Otherwise
                       ;; prompts the user about all unsaved changes, giving them a chance to veto the
                       ;; closure. Returns truthy if the window was closed.
                       (let [track (latest-track track)
                             cues  (vals (get-in track [:contents :cues :cues]))]
                         (when (every? (partial close-cue-editors? force? track) cues)
                           (doseq [cue cues]
                             (cleanup-cue true track cue))
                           (seesaw/invoke-later
                            ;; Gives windows time to close first, so they don't recreate a broken editor.
                            (swap-track! track dissoc :cues-editor)
                            (su/repaint-preview track))  ; Removes the editor viewport overlay.
                           (.removeKeyEventDispatcher (java.awt.KeyboardFocusManager/getCurrentKeyboardFocusManager)
                                                      key-spy)
                           (.dispose root)
                           true)))]
    (swap-track! track assoc :cues-editor {:frame    root
                                           :panel    top-panel
                                           :wave     wave
                                           :scroll   wave-scroll
                                           :close-fn close-fn})
    (.addKeyEventDispatcher (java.awt.KeyboardFocusManager/getCurrentKeyboardFocusManager) key-spy)
    (.addChangeListener (.getViewport wave-scroll)
                        (proxy [javax.swing.event.ChangeListener] []
                          (stateChanged [_]
                            (su/repaint-preview track))))
    (.setScale wave (seesaw/value zoom-slider))
    (.setCursor wave (Cursor/getPredefinedCursor Cursor/CROSSHAIR_CURSOR))
    (.setAutoScroll wave (and (seesaw/value auto-scroll) (util/online?)))
    (.setOverlayPainter wave (proxy [org.deepsymmetry.beatlink.data.OverlayPainter] []
                               (paintOverlay [component graphics]
                                 (paint-cues-and-beat-selection track component graphics))))
    (.setSongStructure wave song-structure)
    (seesaw/listen wave
                   :mouse-moved (fn [e] (handle-wave-move track wave e))
                   :mouse-pressed (fn [e] (handle-wave-click track wave e))
                   :mouse-dragged (fn [e] (handle-wave-drag track wave e))
                   :mouse-released (fn [e] (handle-wave-release track wave e)))
    (seesaw/listen zoom-slider
                   :state-changed (fn [e]
                                    (set-zoom track wave (seesaw/value e) wave-scroll (or @zoom-anchor 0))
                                    (reset! zoom-anchor nil)))

    (seesaw/config! root :content layout)
    (build-cues track)
    (seesaw/listen filter-field #{:remove-update :insert-update :changed-update}
                   (fn [e] (cue-filter-text-changed track (seesaw/text e))))
    (.setSize root 800 600)
    (su/restore-window-position root (get-in track [:contents :cues]) parent)
    (seesaw/listen root
                   :window-closing (fn [_] (close-fn false))
                   #{:component-moved :component-resized} (fn [_] (save-cue-window-position track root)))
    (start-animation-thread show track)
    (su/repaint-preview track)  ; Show the editor viewport overlay.
    (seesaw/show! root)))

(defn open-cues
  "Creates, or brings to the front, a window for editing cues attached
  to the specified track in the specified show. Returns truthy if the
  window was newly opened."
  [track parent]
  (try
    (let [[show track] (latest-show-and-track track)]
      (if-let [existing (:cues-editor track)]
        (.toFront ^JFrame (:frame existing))
        (do (create-cues-window show track parent)
            true)))
    (catch Throwable t
      (swap-track! track dissoc :cues-editor)
      (timbre/error t "Problem creating cues editor.")
      (throw t))))
