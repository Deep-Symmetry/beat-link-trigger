(ns beat-link-trigger.show-cues
  "Implements the cue editing window for Show files."
  (:require [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.show-util :as su :refer [latest-show latest-track latest-show-and-track
                                                        swap-show! swap-track! find-cue swap-cue!
                                                        track? phrase? latest-show-and-context
                                                        phrase-runtime-info latest-phrase
                                                        latest-show-and-phrase swap-phrase! swap-phrase-runtime!]]
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
           [java.awt BasicStroke Color Cursor Graphics2D Rectangle RenderingHints]
           [java.awt.event InputEvent MouseEvent]
           [java.awt.geom Rectangle2D$Double]
           [javax.swing JComponent JFrame JOptionPane JPanel JScrollPane]
           [javax.swing.text JTextComponent]
           [jiconfont.icons.font_awesome FontAwesome]
           [jiconfont.swing IconFontSwing]))

(defn run-cue-function
  "Checks whether the cue has a custom function of the specified kind
  installed and if so runs it with the supplied status or beat
  argument, the cue, show, track or phrase trigger, and the local and
  global atoms. Returns a tuple of the function return value and any
  thrown exception. If `alert?` is `true` the user will be alerted
  when there is a problem running the function."
  [context cue kind status-or-beat alert?]
  (if (track? context)
    (let [[show track] (latest-show-and-context context)
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
                                 (get-in cue [:expressions kind])))
            (when alert? (seesaw/alert (str "<html>Problem running cue " (name kind) " expression.<br><br>" t)
                                       :title "Exception in Show Track Cue Expression" :type :error))
            [nil t]))))
    ;; The phrase trigger version.
    (let [[show phrase runtime-info] (latest-show-and-context context)
          cue (find-cue phrase cue)]
      (when-let [expression-fn (get-in runtime-info [:expression-fns (:uuid cue) kind])]
       (try
         (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
           [(expression-fn status-or-beat {:locals  (:expression-locals runtime-info)
                                           :show    show
                                           :phrase  phrase
                                           :cue     cue}
                           (:expression-globals show)) nil])
         (catch Throwable t
           (timbre/error t (str "Problem running " (editors/cue-editor-title kind phrase cue) ":\n"
                                (get-in cue [:expressions kind])))
           (when alert? (seesaw/alert (str "<html>Problem running phrase " (name (:section cue)) " cue " (name kind)
                                          " expression.<br><br>" t)
                                      :title "Exception in Show Phrase Cue Expression" :type :error))
           [nil t]))))))

(defn update-cue-gear-icon
  "Determines whether the gear button for a cue should be hollow or
  filled in, depending on whether any expressions have been assigned
  to it."
  [context cue gear]
  (let [cue (find-cue context cue)]
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
  [context cue link]
  (let [cue (find-cue context cue)]
    (seesaw/config! link :icon (link-button-icon cue))))

(defn repaint-cue-states
  "Causes the two cue state indicators to redraw themselves to reflect a
  change in state. `cue` can either be the cue object or a cue UUID."
  [context cue]
  (let [uuid (if (instance? java.util.UUID cue) cue (:uuid cue))
        [_show _context runtime-info] (latest-show-and-context context)]
    (when-let [panel (get-in runtime-info [:cues-editor :panels uuid])]
      (seesaw/repaint! (seesaw/select panel [:#entered-state]))
      (seesaw/repaint! (seesaw/select panel [:#started-state])))))

(defn repaint-all-cue-states
  "Causes the cue state indicators for all cues in a track or phrase
  trigger to redraw themselves to reflect a change in state."
  [context]
  (let [[_ context] (latest-show-and-context context)]
    (if (track? context)
      (doseq [cue (keys (get-in context [:contents :cues :cues]))]
        (repaint-cue-states context cue))
      (doseq [cue (keys (get-in context [:cues :cues]))]
        (repaint-cue-states context cue)))))

(declare build-cues)

(defn- scroll-to-cue
  "Makes sure the specified cue editor is visible (it has just been
  created or edited), or give the user a warning that the current cue
  filters have hidden it. If `select-comment` is true, this is a
  newly-created cue, so focus on the comment field and select its
  entire content, for easy editing."
  ([context cue]
   (scroll-to-cue context cue false false))
  ([context cue select-comment]
   (scroll-to-cue context cue select-comment false))
  ([context cue select-comment silent]
   (let [[_ context runtime-info] (latest-show-and-context context)
         cues                     (seesaw/select (get-in runtime-info [:cues-editor :frame]) [:#cues])
         cue                      (find-cue context cue)
         uuid                     (:uuid cue)]
     (if (some #(= uuid %) (get-in runtime-info [:cues-editor :visible]))
       (let [^JPanel panel           (get-in runtime-info [:cues-editor :panels (:uuid cue)])
             ^JTextComponent comment (seesaw/select panel [:#comment])]
         (seesaw/invoke-later
          (seesaw/scroll! cues :to (.getBounds panel))
          (when select-comment
            (.requestFocusInWindow comment)
            (.selectAll comment))))
       (when-not silent
         (seesaw/alert (get-in runtime-info [:cues-editor :frame])
                       (str "The cue \"" (:comment cue) "\" is currently hidden by your filters.\r\n"
                            "To continue working with it, you will need to adjust the filters.")
                       :title "Can't Scroll to Hidden Cue" :type :info))))))

(def min-lane-height
  "The minmum height, in pixels, we will allow a lane to shrink to
  before we start growing the cues editor waveform to accommodate all
  the cue lanes."
  20)

(defn cue-canvas-time-for-x
  "Calculates a notional number if milliseconds into a phrase trigger
  corresponding to a point along its cue canvas, as if it was being
  played at 120 BPM, and the beats were all linearly related. This is
  used for stability while zooming in and out. `phrase` must be
  current."
  [phrase x]
  (let [beat (/ x (/ su/cue-canvas-pixels-per-beat (get-in phrase [:cues :zoom] 4)))]
    (* beat 500)))

(defn cue-canvas-x-for-time
  "Calculates the x position at which notional number if milliseconds
  would fall along a phrase trigger's cue canvas, as if it was being
  played at 120 BPM, and the beats were all linearly related. This is
  used for stability while zooming in and out. `phrase` must be
  current."
  [phrase time]
  (let [beat (/ time 500)
        zoom (get-in phrase [:cues :zoom] 4)]
    (* beat (/ su/cue-canvas-pixels-per-beat zoom))))

(defn cue-canvas-width
  "Calculates the current pixel width of the cue canvas for a phrase
  trigger, given its bar count and zoom level."
  [phrase]
  (let [[_ phrase runtime-info] (su/latest-show-and-context phrase)
        total-bars              (get-in runtime-info [:sections :total-bars])]
    (quot (* su/cue-canvas-pixels-per-beat 4 total-bars) (get-in phrase [:cues :zoom] 4))))

(defn beat-for-x
  "Translates an x coordinate into a beat number and section keyword
  over either a waveform detail component (for tracks), or cue
  canvas (for phrase triggers)."
  [context ^JPanel wave-or-canvas x]
  (if (track? context)
    (let [^WaveformDetailComponent wave wave-or-canvas]
      [(long (.getBeatForX wave x)) nil])
    (let [[_ phrase runtime-info] (su/latest-show-and-context context)
          sections                (:sections runtime-info)
          beat                    (quot (cue-canvas-time-for-x phrase x) 500)
          section                 (or (first (util/iget (:intervals sections) (quot beat 4))) :fill)
          [start-bar]             (section sections)]
      [(long (inc (- beat (* start-bar 4)))) section])))

(defn x-for-beat
  "Translates a beat number into an x coordinate over either a waveform
  detail component (for tracks), or cue canvas (for phrase triggers)."
  [context ^JPanel wave-or-canvas beat section]
  (if (track? context)
    (let [^WaveformDetailComponent wave wave-or-canvas]
      (long (.getXForBeat wave beat)))
    (let [[_ phrase runtime-info] (su/latest-show-and-context context)
          [start-bar] (get-in runtime-info [:sections section])]
      (long (cue-canvas-x-for-time phrase (* 500 (+ (dec beat) (* 4 start-bar))))))))

(defn- cue-rectangle
  "Calculates the outline of a cue within the coordinate system of the
  waveform detail component at the top of the cues editor window,
  taking into account its lane assignment and cluster of neighbors.
  `cue` must be current."
  ^Rectangle2D$Double [context cue ^JPanel wave-or-canvas]
  (let [[_ context runtime-info] (latest-show-and-context context)
        [lane num-lanes] (get-in runtime-info [:cues :position (:uuid cue)])
        lane-height      (double (max min-lane-height (/ (.getHeight wave-or-canvas) num-lanes)))
        x                (x-for-beat context wave-or-canvas (:start cue) (:section cue))
        w                (- (x-for-beat context wave-or-canvas (:end cue) (:section cue)) x)]
    (java.awt.geom.Rectangle2D$Double. (double x) (* lane lane-height) (double w) lane-height)))

(defn scroll-wave-to-cue
  "Makes sure the specified cue is visible in the waveform detail or cue
  canvas of the cues editor window."
  [context cue]
  (let [[_ context runtime-info] (latest-show-and-context context)
        cue                         (find-cue context cue)]
    (when-let [editor (:cues-editor runtime-info)]
      (let [auto-scroll (seesaw/select (:panel editor) [:#auto-scroll])
            wave        (:wave editor)]
        (seesaw/config! auto-scroll :selected? false)  ; Make sure auto-scroll is turned off.
        (seesaw/invoke-later  ; Wait for re-layout if necessary.
         (seesaw/scroll! wave :to (.getBounds (cue-rectangle context cue wave))))))))

(defn beat-count
  "Returns the total number of beats in a track or phrase trigger section."
  [context section]
  (if (track? context)
    (let [^BeatGrid grid (:grid context)]
      (long (.beatCount grid)))
    (let [[_ _ runtime-info] (su/latest-show-and-context context)
          [start end]        (get-in runtime-info [:sections section] [0 0])]
      (inc (* (- end start) 4)))))

(defn- update-cue-spinner-models
  "When the start or end position of a cue has changed, that affects the
  legal values the other can take. Update the spinner models to
  reflect the new limits. Then we rebuild the cue list in case they
  need to change order. Also scroll so the cue is still visible, or if
  it has been filtered out warn the user that has happened. This is
  also called when a phrase trigger section size has changed, because
  that affects the upper bound of the end of the cues in that section."
  [context cue ^javax.swing.SpinnerNumberModel start-model ^javax.swing.SpinnerNumberModel end-model]
  (let [cue (find-cue context cue)]
    (.setMaximum start-model (dec (:end cue)))
    (.setMinimum end-model (inc (:start cue)))
    (when (phrase? context)
      (.setMaximum end-model (beat-count context (:section cue))))
    (seesaw/invoke-later
     (build-cues context)
     (seesaw/invoke-later (scroll-to-cue context cue)))))

(defn- cue-missing-expression?
  "Checks whether the expression body of the specified kind is empty for
  the specified cue."
  [context cue kind]
  (clojure.string/blank? (get-in (find-cue context cue) [:expressions kind])))

(declare send-cue-messages)

(defn- cue-event-enabled?
  "Checks whether the specified event type is enabled for the given
  cue (its message is something other than None, and if Custom, there
  is a non-empty expression body)."
  [context cue event]
  (let [cue     (find-cue context cue)
        message (get-in cue [:events event :message])]
    (cond
      (= "None" message)
      false

      (= "Custom" message)
      (not (cue-missing-expression? context cue event))

      (= "Same" message) ; Must be a :started-late event
      (cue-event-enabled? context cue :started-on-beat)

      :else ; Is a MIDI note or CC
      true)))

(defn- cue-simulate-actions
  "Creates the actions that simulate events happening to the cue, for
  testing expressions or creating and testing MIDI mappings in other
  software."
  [context cue]
  [(seesaw/action :name "Entered"
                  :enabled? (cue-event-enabled? context cue :entered)
                  :handler (fn [_] (send-cue-messages (second (latest-show-and-context context))
                                                      cue :entered (su/random-beat-or-status))))
   (seesaw/action :name "Started On-Beat"
                  :enabled? (cue-event-enabled? context cue :started-on-beat)
                  :handler (fn [_] (send-cue-messages (second (latest-show-and-context context))
                                                      cue :started-on-beat
                                                      (su/random-beat-and-position (when (track? context) context)))))
   (seesaw/action :name "Started Late"
                  :enabled? (cue-event-enabled? context cue :started-late)
                  :handler (fn [_] (send-cue-messages (second (latest-show-and-context context))
                                                      cue :started-late (su/random-cdj-status))))
   (seesaw/action :name "Beat"
                  :enabled? (not (cue-missing-expression? context cue :beat))
                  :handler (fn [_] (run-cue-function context cue :beat
                                                     (su/random-beat-and-position (when (track? context) context))
                                                     true)))
   (seesaw/action :name "Tracked Update"
                  :enabled? (not (cue-missing-expression? context cue :tracked))
                  :handler (fn [_] (run-cue-function context cue :tracked (su/random-cdj-status) true)))
   (let [enabled-events (filterv (partial cue-event-enabled? context cue) [:started-on-beat :started-late])]
     (seesaw/action :name "Ended"
                    :enabled? (seq enabled-events)
                    :handler (fn [_]
                               (su/swap-context-runtime! nil context assoc-in [:cues (:uuid cue) :last-entry-event]
                                                         (rand-nth enabled-events))
                               (send-cue-messages (second (latest-show-and-context context))
                                                  cue :ended (su/random-beat-or-status)))))
   (seesaw/action :name "Exited"
                  :enabled? (cue-event-enabled? context cue :entered)
                  :handler (fn [_] (send-cue-messages (second (latest-show-and-context context))
                                                      cue :exited (su/random-beat-or-status))))])

(defn- cue-simulate-menu
  "Creates the submenu containing actions that simulate events happening
  to the cue, for testing expressions or creating and testing MIDI
  mappings in other software."
  [context cue]
  (seesaw/menu :text "Simulate" :items (cue-simulate-actions context cue)))

(defn- assign-cue-hue
  "Picks a color for a new cue by cycling around the color wheel, and
  recording the last one used."
  [context]
  (if (track? context)
    (let [shows (swap-track! context update-in [:contents :cues :hue]
                             (fn [old-hue] (mod (+ (or old-hue 0.0) 62.5) 360.0)))]
      (get-in shows [(:file context) :tracks (:signature context) :contents :cues :hue]))
    (let [show  (su/show-from-phrase context)
          shows (swap-phrase! show context update-in [:cues :hue]
                              (fn [old-hue] (mod (+ (or old-hue 0.0) 62.5) 360.0)))]
      (get-in shows [(:file show) :contents :phrases (:uuid context) :cues :hue]))))

(defn- scroll-wave-to-cue-action
  "Creates the menu action which scrolls the waveform detail or phrase
  cue canvas to ensure the specified cue is visible."
  [context cue]
  (seesaw/action :handler (fn [_] (scroll-wave-to-cue context cue))
                 :name (if (phrase? context) "Scroll Canvas to This Cue" "Scroll Waveform to This Cue")))

(defn- duplicate-cue-action
  "Creates the menu action which duplicates an existing cue."
  [context cue]
  (seesaw/action :handler (fn [_]
                            (try
                              (let [uuid           (java.util.UUID/randomUUID)
                                    [show context] (latest-show-and-context context)
                                    cue            (find-cue context cue)
                                    contents       (if (phrase? context) context (:contents context))
                                    comment        (util/assign-unique-name
                                                    (map :comment (vals (get-in contents [:cues :cues])))
                                                    (:comment cue))
                                    new-cue        (merge cue {:uuid    uuid
                                                               :hue     (assign-cue-hue contents)
                                                               :comment comment})]
                                (if (track? context)
                                  (swap-track! context assoc-in [:contents :cues :cues uuid] new-cue)
                                  (swap-phrase! show context (fn [phrase]
                                                               (-> phrase
                                                                   (assoc-in [:cues :cues uuid] new-cue)
                                                                   (update-in [:cues :sections (:section cue)]
                                                                              (fnil conj #{}) uuid)))))
                                (build-cues context)
                                (scroll-to-cue context new-cue true))
                              (catch Exception e
                                (timbre/error e "Problem duplicating cue")
                                (seesaw/alert (str e) :title "Problem Duplicating Cue" :type :error))))
                 :name "Duplicate Cue"))

(defn- expunge-deleted-cue
  "Removes all the saved items from a track or phrase trigger that need
  to be cleaned up when the cue has been deleted. This function is
  designed to be used in a single swap! call for simplicity and
  efficiency."
  [context cue]
  (let [uuid (:uuid cue)]
    (if (track? context)
      (update-in context [:contents :cues :cues] dissoc uuid)
      (-> context
          (update-in [:cues :cues] dissoc uuid)
          (update-in [:cues :sections (:section cue)] disj uuid)))))

(defn- expunge-deleted-cue-runtime
  "Removes all the runtime items from a track or phrase trigger that
  need to be cleaned up when the cue has been deleted. This function
  is designed to be used in a single swap! call for simplicity and
  efficiency."
  [runtime-info cue]
  (let [uuid (:uuid cue)]
    (update-in runtime-info [:cues-editor :panels] dissoc uuid)))  ; We know there must be a cues editor open.

(defn- close-cue-editors?
  "Tries closing all open expression editors for the cue. If `force?` is
  true, simply closes them even if they have unsaved changes.
  Otherwise checks whether the user wants to save any unsaved changes.
  Returns truthy if there are none left open the user wants to deal
  with."
  [force? context cue]
  (let [[_show _context runtime-info] (latest-show-and-context context)]
    (every? (partial editors/close-editor? force?)
            (vals (get-in runtime-info [:cues-editor :expression-editors (:uuid cue)])))))

(defn players-playing-cue
  "Returns the set of players that are currently playing the specified
  cue."
  ([context cue]
   (let [[show context runtime-info] (latest-show-and-context context)]
     (reduce (fn [result player]
               (if ((get-in runtime-info [:entered player]) (:uuid cue))
                 (conj result player)
                 result))
             #{}
             (if (track? context)
               (util/players-signature-set (:playing show) (:signature context))
               (util/players-phrase-uuid-set (:playing-phrases show) (:uuid context)))))))

(defn entered?
  "Checks whether any player has entered the cue.
  `track-or-phrase-runtime-info` must be current."
  [track-or-phrase-runtime-info cue]
  ((reduce clojure.set/union (vals (:entered track-or-phrase-runtime-info))) (:uuid cue)))

(defn- started?
  "Checks whether any players which have entered a cue is actually
  playing."
  [context cue]
  (seq (players-playing-cue context cue)))

(def cue-opacity
  "The degree to which cues replace the underlying waveform colors when
  overlaid on top of them."
  (float 0.65))

(defn cue-lightness
  "Calculates the lightness with which a cue should be painted,
  based on the track's or phrase trigger's tripped state and whether
  the cue is entered and playing."
  [context cue]
  (let [[_show context runtime-info] (latest-show-and-context context)]
    (if (and (:tripped runtime-info) (entered? context cue))
      (if (started? context cue) 0.8 0.65)
      0.5)))

(defn send-cue-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a cue has changed state. `context` must be a current
  track or phrase trigger map, and `cue` can either be a cue map, or a
  uuid by which such a cue can be looked up. If it has been deleted,
  nothing is sent. `event` is the key identifying how look up the
  appropriate MIDI message or custom expression in the cue, and
  `status-or-beat` is the protocol message, if any, which caused the
  state change, if any."
  [context cue event status-or-beat]
  #_(timbre/info "sending cue messages" event (.getTimestamp status-or-beat)
                 (if (instance? Beat status-or-beat)
                   (str "Beat " (.getBeatWithinBar status-or-beat) "/4")
                   (str "Status " (.getBeatNumber status-or-beat))))
  (when-let [cue (find-cue context cue)]
    (try
      (let [runtime-info                   (if (track? context) context
                                               (phrase-runtime-info (su/show-from-phrase context) context))
            base-event                     ({:entered         :entered
                                             :exited          :entered
                                             :started-on-beat :started-on-beat
                                             :ended           (get-in runtime-info [:cues (:uuid cue)
                                                                                    :last-entry-event])
                                             :started-late    :started-late} event)
            base-message                   (get-in cue [:events base-event :message])
            effective-base-event           (if (= "Same" base-message) :started-on-beat base-event)
            {:keys [message note channel]} (get-in cue [:events effective-base-event])]
        #_(timbre/info "send-cue-messages" event base-event effective-base-event message note channel)
        (when (#{"Note" "CC"} message)
          (when-let [output (su/get-chosen-output context)]
            (if (#{:exited :ended} event)
              (case message
                "Note" (midi/midi-note-off output note (dec channel))
                "CC"   (midi/midi-control output note 0 (dec channel)))
              (case message
                "Note" (midi/midi-note-on output note 127 (dec channel))
                "CC"   (midi/midi-control output note 127 (dec channel))))))
        (when (= "Custom" message)
          (let [effective-event (if (and (= "Same" base-message) (= :started-late event)) :started-on-beat event)]
            (run-cue-function context  cue effective-event status-or-beat false))))
      (when (#{:started-on-beat :started-late} event)
        ;; Record how we started this cue so we know which event to send upon ending it.
        (su/swap-context-runtime! nil context assoc-in [:cues (:uuid cue) :last-entry-event] event))
      (catch Exception e
        (timbre/error e "Problem reporting" (if (track? context) "track" "phrase trigger") "cue event" event)))))

(defn cleanup-cue
  "Process the removal of a cue, either via deletion, or because the
  show is closing. If `force?` is true, any unsaved expression editors
  will simply be closed. Otherwise, they will block the cue removal,
  which will be indicated by this function returning falsey. Run any
  appropriate custom expressions and send configured MIDI messages to
  reflect the departure of the cue."
  ([force? context cue]
   (when (close-cue-editors? force? context cue)
     (let [[_show context runtime-info] (latest-show-and-context context)]
       (when (:tripped runtime-info)
         (when (seq (players-playing-cue context cue))
           (send-cue-messages context cue :ended nil))
         (when (entered? context cue)
           (send-cue-messages context cue :exited nil))))
     true)))

(defn- delete-cue-action
  "Creates the menu action which deletes a cue, after confirmation if
  it's not linked to a library cue."
  [context cue panel]
  (seesaw/action :handler (fn [_]
                            (let [cue (find-cue context cue)]
                              (when (or (:linked cue)
                                        (util/confirm panel
                                                      (str "This will irreversibly remove the cue, losing any\r\n"
                                                           "configuration and expressions created for it.")
                                                      :title (str "Delete Cue “" (:comment cue) "”?")))
                                (try
                                  (cleanup-cue true context cue)
                                  (su/swap-context! nil context expunge-deleted-cue cue)
                                  (su/swap-context-runtime! nil context expunge-deleted-cue-runtime cue)
                                  (su/update-gear-icon context)
                                  (build-cues context)
                                  (catch Exception e
                                    (timbre/error e "Problem deleting cue")
                                    (seesaw/alert (str e) :title "Problem Deleting Cue" :type :error))))))
                 :name "Delete Cue"))

(defn- sanitize-cue-for-library
  "Removes the elements of a cue that will not be stored in the library.
  Returns a tuple of the name by which it will be stored, and the
  content to be stored (or compared to see if it matches another cue)."
  [cue]
   [(:comment cue) (dissoc cue :uuid :start :end :hue :linked :section)])

(defn linked-cues-equal?
  "Checks whether all the supplied cues have the same values for any
  elements that would be tied together if they were linked cues."
  [& cues]
  (let [trimmed (map #(select-keys % [:events :expressions]) cues)]
    (apply = trimmed)))

(defn add-missing-library-cues
  "When importing or pasting a track into a show, creates the library
  cues corresponding to any linked cues which do not exist in the show
  already. `show` must be current."
  [show cues]
  (doseq [cue cues]
    (when-let [linked (:linked cue)]
      (when-not (get-in show [:contents :cue-library linked])
        (let [[_ library-cue] (sanitize-cue-for-library cue)]
          (swap-show! show update-in [:contents :cue-library] assoc linked library-cue))))))

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
  windows if the library has any cues in it, and is hidden otherwise.
  At the same time all open cue editor panels have their link buttons
  shown or hidden as well."
  [show]
  (let [show            (latest-show show)
        library-in-use? (boolean (seq (get-in show [:contents :cue-library])))]
    (doseq [context (concat (vals (:tracks show)) (vals (:phrases show)))]
      (when-let [editor (:cues-editor context)]
        (let [button (seesaw/select (:frame editor) [:#library])]
          (seesaw/config! button :visible? library-in-use?)
          (doseq [panel (vals (get-in context [:cues-editor :panels]))]
            (let [link (seesaw/select panel [:#link])]
              (seesaw/config! link :visible? library-in-use?))))))))

(defn- add-cue-to-library
  "Adds a cue to a show's cue library."
  [show cue-name cue]
  (swap-show! show assoc-in [:contents :cue-library cue-name] cue))

(defn- add-cue-to-folder
  "Adds a cue to a folder in the cue library."
  [show folder cue-name]
  (swap-show! show update-in [:contents :cue-library-folders folder] (fnil conj #{}) cue-name))

(defn- cue-library-add-handler
  "Helper function to perform the common actions needed when a user has
  chosen to add a cue to a library folder. All arguments must be
  current, and `folder` can be `nil` to add the cue to the top level,
  or if folders are not in use."
  [show context original-cue cue-name content folder]
  (let [[_show context runtime-info] (su/latest-show-and-context context)
        panel                        (get-in runtime-info [:cues-editor :panels (:uuid original-cue)])]
    (add-cue-to-library show cue-name content)
    (when folder (add-cue-to-folder show folder cue-name))
    (swap-cue! context original-cue assoc :linked cue-name)
    (update-cue-link-icon context original-cue (seesaw/select panel [:#link]))
    (update-library-button-visibility show)))

(defn- cue-library-action
  "Creates the menu action which adds a cue to the library"
  [context cue]
  (let [[show context]    (latest-show-and-context context)
        cue               (find-cue context cue)
        [comment content] (sanitize-cue-for-library cue)]
    (if (clojure.string/blank? comment)
      (seesaw/action :name "Type a Comment to add Cue to Library" :enabled? false)
      (if (cue-in-library? show comment content)
        ;; The cue is already in the library, we now just report that. (There are new menu options
        ;; for managing library cues, and the old mechanisms made no sense now that cues can be
        ;; linked.
        (seesaw/action :name "Library Contains a Cue with this name" :enabled? false)

        ;; The cue is not in the library, so offer to add it.
        (let [folders (get-in show [:contents :cue-library-folders])]
          (if (empty? folders)
            ;; No folders, simply provide an action to add to top level.
            (seesaw/action :name "Add Cue to Library"
                           :handler (fn [_]
                                      (cue-library-add-handler show context cue comment content nil)))
            ;; Provide a menu to add to each library folder or the top level.
            (seesaw/menu :text "Add Cue to Library"
                         :items (concat
                                 (for [folder (sort (keys folders))]
                                   (seesaw/action :name (str "In Folder “" folder "”")
                                                  :handler (fn [_] (cue-library-add-handler
                                                                    show context cue comment content folder))))
                                 [(seesaw/action :name "At Top Level"
                                                 :handler (fn [_] (cue-library-add-handler
                                                                    show context cue comment content nil)))]))))))))

(defn cue-preview-rectangle
  "Calculates the outline of a cue within the coordinate system of the
  waveform preview component in a track or phrase trigger row of a
  show window, taking into account its lane assignment and cluster of
  neighbors. `context` and `cue` must be current."
  ^Rectangle2D$Double [context cue ^JPanel preview]
  (let [[_ _ runtime-info] (latest-show-and-context context)
        [lane num-lanes]   (get-in runtime-info [:cues :position (:uuid cue)])
        lane-height        (double (max 1.0 (/ (.getHeight preview) num-lanes)))
        x-for-beat         (fn [beat section]
                             (if (track? context)
                               (let [preview ^WaveformPreviewComponent preview]
                                 (.millisecondsToX preview (.getTimeWithinTrack ^BeatGrid (:grid context) beat)))
                               (su/cue-canvas-preview-x-for-beat preview runtime-info beat section)))
        x                  (x-for-beat (:start cue) (:section cue))
        w                  (- (x-for-beat (:end cue) (:section cue)) x)
        y                  (double (* lane (/ (.getHeight preview) num-lanes)))]
    (java.awt.geom.Rectangle2D$Double. (double x) y (double w) lane-height)))

(def selection-opacity
  "The degree to which the active selection replaces the underlying
  waveform colors."
  (float 0.5))

(defn paint-preview-cues
  "Draws the cues, if any, on top of the preview waveform. If there is
  an open cues editor window, also shows its current view of the wave,
  unless it is in auto-scroll mode. (This only needs to support track
  rows, because phrase trigger rows do all this in their standard
  paint operation.)"
  [show signature ^WaveformPreviewComponent preview ^Graphics2D graphics]
  (let [show           (latest-show show)
        ^Graphics2D g2 (.create graphics)
        cliprect       (.getClipBounds g2)
        track          (get-in show [:tracks signature])
        cues-to-paint  (let [beat-for-x    (fn [x]
                                             (.findBeatAtTime ^BeatGrid (:grid track) (.getTimeForX preview x)))
                             from          (beat-for-x (.x cliprect))
                             to            (inc (beat-for-x (+ (.x cliprect) (.width cliprect))))
                             cue-intervals (get-in track [:cues :intervals])]
                         (map (partial find-cue track) (util/iget cue-intervals from to)))]
    (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER cue-opacity))
    (doseq [cue cues-to-paint]
      (.setPaint g2 (su/hue-to-color (:hue cue) (cue-lightness track cue)))
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
  track or phrase trigger, ignoring selections that have been dragged
  to zero size. Phrase trigger selections will have a third element,
  the keyword identifying the section of the phrase in which the
  selection exists."
  [context]
  (let [[_show _context runtime-info] (latest-show-and-context context)]
    (when-let [selection (get-in runtime-info [:cues-editor :selection])]
      (when (> (second selection) (first selection))
        selection))))

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
      (.setPaint g2 (su/hue-to-color (:hue cue) (cue-lightness track cue)))
      (.fill g2 (cue-rectangle track cue wave)))
    (when-let [[start end] (get-current-selection track)]
      (let [x (.getXForBeat wave start)
            w (- (.getXForBeat wave end) x)]
        (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER selection-opacity))
        (.setPaint g2 Color/white)
        (.fill g2 (java.awt.geom.Rectangle2D$Double. (double x) 0.0 (double w) (double (.getHeight wave))))))
    (.dispose g2)))

(defn repaint-cue
  "Causes a single cue to be repainted in the track or phrase trigger
  preview and (if one is open) the cues editor, because it has changed
  entered or active state. `cue` can either be the cue object or its
  uuid."
  [context cue]
  (let [[_ context runtime-info] (latest-show-and-context context)
        cue                         (find-cue context cue)]
    (if (track? context)
      (when-let [preview-loader (:preview runtime-info)]
        (when-let [^WaveformPreviewComponent preview (preview-loader)]
          (let [preview-rect (cue-preview-rectangle context cue preview)]
            (.repaint ^JComponent (:preview-canvas runtime-info)
                      (.x preview-rect) (.y preview-rect) (.width preview-rect) (.height preview-rect)))))
      (let [^JPanel preview (:preview runtime-info)  ; Phrase triggers are a little simpler.
            preview-rect    (cue-preview-rectangle context cue preview)]
        (.repaint preview (.x preview-rect) (.y preview-rect) (.width preview-rect) (.height preview-rect))))
    (when-let [^JPanel wave (get-in runtime-info [:cues-editor :wave])]
      (let [cue-rect (cue-rectangle context cue wave)]
        (.repaint wave (.x cue-rect) (.y cue-rect) (.width cue-rect) (.height cue-rect))))))

;; TODO: Will need a versioon for phrase cues, either rename this one or add both branches to it.
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

(defn- update-cue-panel-from-linked
  "Updates all the user elements of a cue to reflect the values that
  have changed due to a linked library cue. Does nothing if the cue
  has no editor panel open."
  [context cue]
  (let [cue                (find-cue context cue)
        [_ _ runtime-info] (latest-show-and-context context)
        panel              (get-in runtime-info [:cues-editor :panels (:uuid cue)])]
    (when panel
      (swap-cue! context cue assoc :creating true)  ; Suppress repropagation and opening of editor windows.
      (update-cue-gear-icon context cue (seesaw/select panel [:#gear]))
      (doseq [[event elems] (:events cue)]
        (doseq [[elem value] elems]
          (let [id     (cue-event-component-id event (name elem) true)
                widget (seesaw/select panel [id])]
            (seesaw/value! widget value))))
      (update-cue-gear-icon context cue (seesaw/select panel [:#gear]))
      (swap-cue! context cue dissoc :creating))))

(defn update-all-linked-cues
  "Called when a cue has changed. If it is linked to a library cue,
  updates that, then also updates any cues in the show which are
  linked to the same library cue, and if they have an editor panel
  open, updates that as well."
  [context cue]
  (let [[show context] (latest-show-and-context context)
        cue          (find-cue context cue)
        uuid         (:uuid cue)
        content      (select-keys cue [:expressions :events]) ; The parts to update.
        linked       (:linked cue)]
    (when-let [library-cue (when (and linked (not (:creating cue))) (get-in show [:contents :cue-library linked]))]
      (when (not= content (select-keys library-cue [:expressions :events]))
        (swap-show! show update-in [:contents :cue-library linked]
                    (fn [library-cue] (merge (dissoc library-cue :expressions :events) content)))
        (doseq [track (vals (:tracks show))]
          (doseq [[linked-uuid linked-cue] (get-in track [:contents :cues :cues])]
            (when (and (= linked (:linked linked-cue)) (not= uuid linked-uuid))
              (swap-cue! track linked-cue
                         (fn [linked-cue] (merge (dissoc linked-cue :expressions :events) content)))
              (update-cue-panel-from-linked track linked-cue))))
        (doseq [phrase (vals (:phrases show))]
          (doseq [[linked-uuid linked-cue] (get-in phrase [:cues :cues])]
            (when (and (= linked (:linked linked-cue)) (not= uuid linked-uuid))
              (swap-cue! phrase linked-cue
                         (fn [linked-cue] (merge (dissoc linked-cue :expressions :events) content)))
              (update-cue-panel-from-linked phrase linked-cue))))))))

(defn cue-editor-actions
  "Creates the popup menu actions corresponding to the available
  expression editors for a given cue."
  [context cue panel gear]
  (for [[kind spec] @editors/show-cue-editors]
    (let [update-fn (fn []
                      (update-all-linked-cues context cue)
                      (update-cue-gear-icon context cue gear))]
      (seesaw/action :handler (fn [_] (editors/show-cue-editor kind context cue panel update-fn))
                     :name (str "Edit " (:title spec))
                     :tip (:tip spec)
                     :icon (if (cue-missing-expression? context cue kind)
                             "images/Gear-outline.png"
                             "images/Gear-icon.png")))))

(defn- attach-cue-custom-editor-opener
  "Sets up an action handler so that when one of the popup menus is set
  to Custom, if there is not already an expession of the appropriate
  kind present, an editor for that expression is automatically
  opened."
  [context cue menu event panel gear]
  (seesaw/listen menu
                 :action-performed (fn [_]
                                     (let [choice (seesaw/selection menu)
                                           cue    (find-cue context cue)]
                                       (when (and (= "Custom" choice)
                                                  (not (:creating cue))
                                                  (clojure.string/blank? (get-in cue [:expressions event])))
                                         (editors/show-cue-editor event context cue panel
                                                                  #(update-cue-gear-icon context cue gear)))))))

(defn- attach-cue-message-visibility-handler
  "Sets up an action handler so that when one of the message menus is
  changed, the appropriate UI elements are shown or hidden. Also
  arranges for the proper expression editor to be opened if Custom is
  chosen for the message type and that expression is currently empty."
  [context cue event gear]
  (let [[_show context runtime-info] (latest-show-and-context context)
        panel                        (get-in runtime-info [:cues-editor :panels (:uuid cue)])
        message-menu                 (seesaw/select panel [(cue-event-component-id event "message" true)])
        note-spinner                 (seesaw/select panel [(cue-event-component-id event "note" true)])
        label                        (seesaw/select panel [(cue-event-component-id event "channel-label" true)])
        channel-spinner              (seesaw/select panel [(cue-event-component-id event "channel" true)])]
    (seesaw/listen message-menu
                   :action-performed (fn [_]
                                       (let [choice (seesaw/selection message-menu)]
                                         (if (#{"Same" "None"} choice)
                                           (seesaw/hide! [note-spinner label channel-spinner])
                                           (seesaw/show! [note-spinner label channel-spinner])))))
    (attach-cue-custom-editor-opener context cue message-menu event panel gear)))

(defn- create-cue-event-components
  "Builds and returns the combo box and spinners needed to configure one
  of the three events that can be reported about a cue. `event` will
  be one of `cue-events`, above."
  [context cue event default-note]
  (let [message       (seesaw/combobox :id (cue-event-component-id event "message")
                                       :model (case event
                                                :started-late ["Same" "None" "Note" "CC" "Custom"]
                                                ["None" "Note" "CC" "Custom"])
                                       :selected-item nil  ; So update in create-cue-panel saves default.
                                       :listen [:item-state-changed
                                                (fn [e]
                                                  (swap-cue! context cue assoc-in [:events event :message]
                                                             (seesaw/selection e))
                                                  (update-all-linked-cues context cue))])
        note          (seesaw/spinner :id (cue-event-component-id event "note")
                                      :model (seesaw/spinner-model (or (get-in cue [:events event :note]) default-note)
                                                                   :from 1 :to 127)
                                      :listen [:state-changed
                                               (fn [e]
                                                 (swap-cue! context cue assoc-in [:events event :note]
                                                            (seesaw/value e))
                                                 (update-all-linked-cues context cue))])
        channel       (seesaw/spinner :id (cue-event-component-id event "channel")
                                      :model (seesaw/spinner-model (or (get-in cue [:events event :channel]) 1)
                                                                   :from 1 :to 16)
                                      :listen [:state-changed
                                               (fn [e]
                                                 (swap-cue! context cue assoc-in [:events event :channel]
                                                            (seesaw/value e))
                                                 (update-all-linked-cues context cue))])
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
  [show context folder-name cues-in-folder cue-action-builder-fn]
  (let [cue-actions (filter identity
                            (for [cue-name (sort cues-in-folder)]
                              (when-let [cue (get-in (latest-show show) [:contents :cue-library cue-name])]
                                (cue-action-builder-fn cue-name cue context))))]
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
  [show context cue-action-builder-fn]
  (reduce (fn [[menus cues-in-folders] [folder-name cues-in-folder]]
            [(conj menus (build-cue-folder-menu show context folder-name cues-in-folder cue-action-builder-fn))
             (clojure.set/union cues-in-folders cues-in-folder)])
          [[] #{}]
          (get-in (latest-show show) [:contents :cue-library-folders])))

(defn- build-cue-library-popup-items
  "Creates the popup menu items allowing you to do something with cues
  in the library. `cue-action-builder-fn` is the function that will be
  called to create the action associated with a cue in the menu. It
  will be called with the cue name, cue contents, and track."
  [context cue-action-builder-fn]
  (let [[show context] (latest-show-and-context context)
        library      (sort-by first (vec (get-in show [:contents :cue-library])))]
    (if (empty? library)
      [(seesaw/action :name "No Cues in Show Library" :enabled? false)]
      (let [[folder-menus cues-in-folders] (build-cue-folder-menus show context cue-action-builder-fn)]
        (concat folder-menus
                (filter identity
                        (for [[cue-name cue] library]
                          (when-not (cues-in-folders cue-name)
                            (cue-action-builder-fn cue-name cue context)))))))))

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

(defn- build-link-cue-action
  "Creates an action that links an existing cue to a library cue. All
  arguments must be current."
  [show existing-cue button library-cue-name library-cue context]
  (seesaw/action :name library-cue-name
                 :handler (fn [_]
                            (when (or (linked-cues-equal? library-cue existing-cue)
                                      (util/confirm (seesaw/to-frame button)
                                                    (str "Linking will replace the contents of this cue with\r\n"
                                                         "the contents of library cue "
                                                         (full-library-cue-name show library-cue-name) ".")
                                                    :title (str "Link Cue “" (:comment existing-cue) "”?")))
                              (swap-cue! context existing-cue
                                         (fn [cue]
                                           (-> cue
                                               (dissoc :expressions)
                                               (merge (dissoc library-cue :comment)
                                                      {:linked library-cue-name}))))
                              (update-cue-panel-from-linked context  existing-cue)
                              (update-cue-link-icon context existing-cue button)))))

(defn- build-cue-link-button-menu
  "Builds the menu that appears when you click in a cue's Link button,
  either offering to link or unlink the cue as appropriate, or telling
  you the library is empty."
  [context cue button]
  (let [[show context] (latest-show-and-context context)
        cue          (find-cue context cue)
        library      (sort-by first (vec (get-in show [:contents :cue-library])))]
    (if (empty? library)
      [(seesaw/action :name "No Cues in Show Library" :enabled? false)]
      (if-let [link (:linked cue)]
        [(seesaw/action :name (str "Unlink from Library Cue " (full-library-cue-name show link))
                         :handler (fn [_]
                                    (swap-cue! context cue dissoc :linked)
                                    (update-cue-link-icon context cue button)))]
        [(seesaw/menu :text "Link to Library Cue"
                      :items (build-cue-library-popup-items
                              context (partial build-link-cue-action show cue button)))]))))

(defn update-all-cue-spinner-end-models
  "Called when a phrase section structure has changed to update any
  affected cues in an open cues editor window so they know their new
  maximum sizes."
  [phrase]
  (let [[_ phrase runtime-info] (latest-show-and-context phrase)]
    (when-let [editor (:cues-editor runtime-info)]
      (doseq [cue (vals (get-in phrase [:cues :cues]))]
        (when-let [panel (get-in editor [:panels (:uuid cue)])]
          (let [end-model (seesaw/config (seesaw/select panel [:#end]) :model)]
            (.setMaximum end-model (beat-count phrase (:section cue)))))))))

(defn- create-cue-panel
  "Called the first time a cue is being worked with in the context of
  a cues editor window. Creates the UI panel that is used to configure
  the cue. Returns the panel after updating the cue to know about it.
  `track` and `cue` must be current."
  [context cue]
  (let [update-comment (fn [c]
                         (let [comment (seesaw/text c)]
                           (swap-cue! context cue assoc :comment comment)))
        [show]         (su/latest-show-and-context context)
        track?         (su/track? context)
        comment-field  (seesaw/text :id :comment :paint (partial util/paint-placeholder "Comment")
                                    :text (:comment cue) :listen [:document update-comment])
        gear           (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
        link           (seesaw/button :id :link :icon (link-button-icon cue)
                                      :visible? (seq (get-in show [:contents :cue-library])))
        start-model    (seesaw/spinner-model (:start cue) :from 1 :to (dec (:end cue)))
        end-model      (seesaw/spinner-model (:end cue) :from (inc (:start cue))
                                             :to (if track?
                                                   (long (.beatCount ^BeatGrid (:grid context)))
                                                   (beat-count context (:section cue))))

        start  (seesaw/spinner :id :start
                               :model start-model
                               :listen [:state-changed
                                        (fn [e]
                                          (let [new-start (seesaw/selection e)]
                                            (swap-cue! context cue assoc :start new-start)
                                            (update-cue-spinner-models context cue start-model end-model)))])
        end    (seesaw/spinner :id :end
                               :model end-model
                               :listen [:state-changed
                                        (fn [e]
                                          (let [new-end (seesaw/selection e)]
                                            (swap-cue! context cue assoc :end new-end)
                                            (update-cue-spinner-models context cue start-model end-model)))])
        swatch (seesaw/canvas :size [18 :by 18]
                              :paint (fn [^JComponent component ^Graphics2D graphics]
                                       (let [cue (find-cue context cue)]
                                         (.setPaint graphics (su/hue-to-color (:hue cue)))
                                         (.fill graphics (java.awt.geom.Rectangle2D$Double.
                                                          0.0 0.0 (double (.getWidth component))
                                                          (double (.getHeight component)))))))

        event-components (apply merge (map-indexed (fn [index event]
                                                     {event (create-cue-event-components
                                                             context cue event (inc index))})
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
                                       :paint (partial paint-cue-state context cue entered?))  ; TODO: phrase version?
                        "spanx, split"]
                       [(seesaw/label :text "Message:" :halign :right) "gap unrelated, sizegroup first-message"]
                       [(get-in event-components [:entered :message])]
                       [(get-in event-components [:entered :note]) "hidemode 3"]
                       [(get-in event-components [:entered :channel-label]) "gap unrelated, hidemode 3"]
                       [(get-in event-components [:entered :channel]) "hidemode 2, wrap"]

                       [link]
                       ["Started:" "gap unrelated, align right"]
                       [(seesaw/canvas :id :started-state :size [18 :by 18] :opaque? false
                                       :tip (str "Outer ring shows "
                                                 (if track? "track enabled" "phrase trigger chosen")
                                                 ", inner light when player(s) playing inside cue.")
                                       :paint (partial paint-cue-state context cue started?))  ; TODO: phrase version?
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
        popup-fn (fn [_] (concat (cue-editor-actions context cue panel gear)
                                 [(seesaw/separator) (cue-simulate-menu context cue) (su/inspect-action context)
                                  (seesaw/separator) (scroll-wave-to-cue-action context cue) (seesaw/separator)
                                  (duplicate-cue-action context cue) (cue-library-action context cue)
                                  (delete-cue-action context cue panel)]))]

    ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
    ;; or right click on the gear button. Also set the proper initial gear appearance. Add the popup builder to
    ;; the panel user data so that it can be used when control-clicking on a cue in the waveform as well.
    (seesaw/config! [panel gear] :popup popup-fn)
    (seesaw/config! panel :user-data {:popup popup-fn})
    (seesaw/listen gear
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (popup-fn e))]
                                      (util/show-popup-from-button gear popup e))))
    (update-cue-gear-icon context cue gear)

    ;; Attach the link menu to the link button, both as a normal and right click.
    (seesaw/config! [link] :popup (build-cue-link-button-menu context cue link))
    (seesaw/listen link
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (build-cue-link-button-menu context cue link))]
                                      (util/show-popup-from-button link popup e))))

    (seesaw/listen swatch
                   :mouse-pressed (fn [_]
                                    (let [cue (find-cue context cue)]
                                      (when-let [color (chooser/choose-color panel :color (su/hue-to-color (:hue cue))
                                                                             :title "Choose Cue Hue")]
                                        (swap-cue! context cue assoc :hue (su/color-to-hue color))
                                        (seesaw/repaint! [swatch])
                                        (repaint-cue context cue)))))


    ;; Record the new panel in the show, in preparation for final configuration.
    (su/swap-context-runtime! show context assoc-in [:cues-editor :panels (:uuid cue)] panel)

    ;; Establish the saved or initial settings of the UI elements, which will also record them for the
    ;; future, and adjust the interface, thanks to the already-configured item changed listeners.
    ;; Start by suppresing the automatic opening of expression editors while recreating the cue row.
    ;; This flag is also used to suppress propagation of changes to linked cues during row creation
    ;; and when the row is itself being updated because of a change to a linked cue.
    (swap-cue! context cue assoc :creating true)
    (doseq [event cue-events]
      ;; Update visibility when a Message selection changes. Also sets them up to automagically open the
      ;; expression editor for the Custom Enabled Filter if "Custom" is chosen as the Message.
      (attach-cue-message-visibility-handler context cue event gear)

      ;; Set the initial state of the Message menu which will, thanks to the above, set the initial visibilty.
      (seesaw/selection! (seesaw/select panel [(cue-event-component-id event "message" true)])
                         (or (get-in cue [:events event :message]) (if (= event :started-late) "Same" "None")))

      ;; In case this is the initial creation of the cue, record the defaulted values of the numeric inputs too.
      ;; This will have no effect if they were loaded.
      (swap-cue! context cue assoc-in [:events event :note]
                 (seesaw/value (seesaw/select panel [(cue-event-component-id event "note" true)])))
      (swap-cue! context cue assoc-in [:events event :channel]
                 (seesaw/value (seesaw/select panel [(cue-event-component-id event "channel" true)]))))
    (swap-cue! context cue dissoc :creating)  ; Re-arm Message menu to pop up the expression editor when Custom chosen.

    panel))  ; Return the newly-created and configured panel.

(defn update-cue-visibility
  "Determines the cues that should be visible given the filter text (if
  any) and state of the Only Entered checkbox if we are online.
  Updates the track's cues editor's `:visible` key to hold a vector of
  the visible cue UUIDs, sorted by their start and end beats followed
  by their comment and UUID. Then uses that to update the contents of
  the `cues` panel appropriately. Safely does nothing if the track or
  phrase trigger has no cues editor window."
  [context]
  (let [[show context runtime-info] (latest-show-and-context context)]
    (when-let [editor (:cues-editor runtime-info)]
      (let [cues          (seesaw/select (:frame editor) [:#cues])
            panels        (get-in runtime-info [:cues-editor :panels])
            contents      (if (phrase? context) context (:contents context))
            text          (get-in contents [:cues :filter])
            entered-only? (and (util/online?) (get-in contents [:cues :entered-only]))
            entered       (when entered-only? (reduce clojure.set/union (vals (:entered runtime-info))))
            old-visible   (get-in runtime-info [:cues-editor :visible])
            visible-cues  (filter identity
                                  (map (fn [uuid]
                                         (let [cue (get-in contents [:cues :cues uuid])]
                                           (when (and
                                                  (or (clojure.string/blank? text)
                                                      (clojure.string/includes?
                                                       (clojure.string/lower-case (:comment cue ""))
                                                       (clojure.string/lower-case text)))
                                                  (or (not entered-only?) (entered (:uuid cue))))
                                             cue)))
                                       (get-in runtime-info [:cues :sorted])))
            visible-uuids (mapv :uuid visible-cues)]
        (when (not= visible-uuids old-visible)
          (su/swap-context-runtime! show context assoc-in [:cues-editor :visible] visible-uuids)
          (let [current-section (atom nil)
                visible-panels  (mapcat (fn [cue color]
                                          (let [panel (or (get panels (:uuid cue)) (create-cue-panel context cue))]
                                            (seesaw/config! panel :background color)
                                            (if (= (:section cue) @current-section)
                                              [panel]
                                              (do
                                                (reset! current-section (:section cue))
                                                [(seesaw/border-panel
                                                  :maximum-size [Integer/MAX_VALUE :by 40]
                                                  :border 4
                                                  :background (su/phrase-section-colors (:section cue))
                                                  :west (seesaw/label
                                                         :text (str " " (str/capitalize (name (:section cue))))))
                                                 panel]))))
                                        visible-cues (cycle ["#eee" "#ddd"]))]
            (seesaw/config! cues :items (concat visible-panels [:fill-v :fill-v :fill-v :fill-v]))))))))

(defn- set-entered-only
  "Update the cues UI so that all cues or only entered cues are
  visible."
  [context entered-only?]
  (su/swap-context-runtime! nil context assoc-in [:contents :cues :entered-only] entered-only?)
  (update-cue-visibility context))

(defn- set-auto-scroll
  "Update the cues UI so that the waveform or canvas automatically
  tracks the furthest position played if `auto?` is `true` and we are
  connected to a DJ Link network."
  [context wave-or-canvas auto?]
  (if (track? context)
    (let [^WaveformDetailComponent wave wave-or-canvas]
      (swap-track! context assoc-in [:contents :cues :auto-scroll] auto?)
      (.setAutoScroll wave (and auto? (util/online?))))
    (do  ; Someday actualy implement for phrase trigger cue canvas?
      (swap-phrase! (su/show-from-phrase context) context assoc-in [:cues :auto-scroll] auto?)))
  (su/repaint-preview context)
  (when-not auto? (seesaw/scroll! wave-or-canvas :to [:point 0 0])))

(defn- set-zoom
  "Updates the cues UI so that the waveform or cue canvas is zoomed out
  by the specified factor, while trying to preserve the section of the
  wave or canvas at the specified x coordinate within the scroll pane if the
  scroll positon is not being controlled by the DJ Link network."
  [context ^JPanel panel zoom ^JScrollPane pane anchor-x]
  (if (track? context)
    (let [wave  ^WaveformDetailComponent panel
          bar   (.getHorizontalScrollBar pane)
          bar-x (.getValue bar)
          time  (.getTimeForX wave (+ anchor-x bar-x))]
      (swap-track! context assoc-in [:contents :cues :zoom] zoom)
      (.setScale wave zoom)
      (when-not (.getAutoScroll wave)
        (seesaw/invoke-later
         (let [time-x (.millisecondsToX wave time)]
           (.setValue bar (- time-x anchor-x))))))
    (let [bar            (.getHorizontalScrollBar pane)
          bar-x          (.getValue bar)
          [show context] (latest-show-and-context context)
          time           (cue-canvas-time-for-x context (+ anchor-x bar-x))]
      (swap-phrase! show context assoc-in [:cues :zoom] zoom)
      (seesaw/config! panel :size [(cue-canvas-width context) :by 92])
      (seesaw/repaint! panel)
      (.revalidate pane)
      (when-not (get-in context [:cues :auto-scroll])
        (seesaw/invoke-later
         (let [time-x (cue-canvas-x-for-time (latest-phrase show context) time)]
           (.setValue bar (- time-x anchor-x))))))))

(defn- cue-filter-text-changed
  "Update the cues UI so that only cues matching the specified filter
  text, if any, are visible."
  [context text]
  (if (track? context)
    (swap-track! context assoc-in [:contents :cues :filter] (clojure.string/lower-case text))
    (swap-phrase! (su/show-from-phrase context) context assoc-in [:cues :filter] (clojure.string/lower-case text)))
  (update-cue-visibility context))

(defn- save-cue-window-position
  "Update the saved dimensions of the cue editor window, so it can be
  reopened in the same state."
  [context ^JFrame window]
  (if (track? context)
    (swap-track! context assoc-in [:contents :cues :window]
                 [(.getX window) (.getY window) (.getWidth window) (.getHeight window)])
    (swap-phrase! (su/show-from-phrase context) context assoc-in [:cues :window]
                  [(.getX window) (.getY window) (.getWidth window) (.getHeight window)])))

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
        (update-cue-visibility track)))
    (doseq [phrase       (vals (get-in show [:contents :phrases]))
            runtime-info (su/phrase-runtime-info show phrase)]
      (when-let [editor (:cues-editor runtime-info)]
        (let [checkboxes [(seesaw/select (:frame editor) [:#entered-only])
                          #_(seesaw/select (:frame editor) [:#auto-scroll])]  ; Not yet supported for phrases.
              auto?      (get-in phrase [:cues :auto-scroll])]
          (if online?
            (seesaw/show! checkboxes)
            (seesaw/hide! checkboxes))
          (when auto?
            ;; Whatever the equivalent is for the cue canvas, if this is ever implemented.
            #_(.setAutoScroll ^WaveformDetailComponent (:wave editor) (and auto? online?))
            (seesaw/scroll! (:wave editor) :to [:point 0 0])))
        (update-cue-visibility phrase)))))

(def max-zoom
  "The largest extent to which we can zoom out on the waveform in the
  cues editor window."
  64)

(defn- find-cue-under-mouse
  "Checks whether the mouse is currently over any cue, and if so returns
  it as the first element of a tuple. Always returns the latest
  version of the supplied track or phrase trigger as the second
  element of the tuple."
  [context wave-or-canvas ^MouseEvent e]
  (let [point           (.getPoint e)
        [_show context] (latest-show-and-context context)
        contents        (if (phrase? context) context (:contents context))
        cue             (first (filter (fn [cue] (.contains (cue-rectangle context cue wave-or-canvas) point))
                                       (vals (get-in contents [:cues :cues]))))]
    [cue context]))

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
  "Processes a key event while a cue waveform (or cue canvas) is being
  displayed, in case it requires a cursor change."
  [context ^JComponent wave-or-canvas ^InputEvent e]
  (let [[_show _context runtime-info] (latest-show-and-context context)
        [unshifted shifted]          (get-in runtime-info [:cues-editor :cursors])]
    (when unshifted  ; We have cursors defined, so apply the appropriate one
      (.setCursor wave-or-canvas (if (shift-down? e) shifted unshifted)))))

;; TODO: Assuming beat will always be relative to the start of the selection's section.
(defn- drag-cursor
  "Determines the proper cursor that will reflect the nearest edge of
  the selection that will be dragged, given the beat under the mouse."
  [context beat]
  (let [[_show _context runtime-info] (latest-show-and-context context)
        [start end]                   (get-in runtime-info [:cues-editor :selection])
        start-distance                (Math/abs (long (- beat start)))
        end-distance                  (Math/abs (long (- beat end)))]
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
  [context ^JPanel wave-or-canvas ^MouseEvent e [start end section] cue]
  (cond
    (and start (<= (Math/abs (- (.getX e) (x-for-beat context wave-or-canvas start section))) click-edge-tolerance))
    [nil :start section]

    (and end (<= (Math/abs (- (.getX e) (x-for-beat context wave-or-canvas end section))) click-edge-tolerance))
    [nil :end section]

    cue
    (let [r (cue-rectangle context cue wave-or-canvas)]
      (if (<= (Math/abs (- (.getX e) (.getX r))) click-edge-tolerance)
        [cue :start (:section cue)]
        (when (<= (Math/abs (- (.getX e) (+ (.getX r) (.getWidth r)))) click-edge-tolerance)
          [cue :end (:section cue)])))))

(defn compile-cue-expressions
  "Compiles and installs all the expressions associated with a track's
  or phrase trigger's cue. Used both when opening a show, and when
  adding a cue from the library."
  [context cue]
  (doseq [[kind expr] (:expressions cue)]
    (let [editor-info (get @editors/show-cue-editors kind)]
      (try
        (su/swap-context-runtime! nil context assoc-in [:cues :expression-fns (:uuid cue) kind]
                                  (expressions/build-user-expression
                                   expr (:bindings editor-info) (:nil-status? editor-info)
                                   (editors/cue-editor-title kind context cue)))
        (catch Exception e
          (timbre/error e (str "Problem parsing " (:title editor-info)
                               " when loading Show. Expression:\n" expr "\n"))
          (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                             "Check the log file for details.")
                        :title "Exception during Clojure evaluation" :type :error))))))

(defn- move-cue-to-folder
  "Helper method that moves a cue to the specified folder, or the top
  level if `folder` is `nil`. Arguments must be current."
  [show cue-name new-folder-name]
  (doseq [[folder-name folder] (get-in show [:contents :cue-library-folders])]
    (when (folder cue-name)  ; Remove from any folder it used to be in.
      (swap-show! show update-in [:contents :cue-library-folders folder-name] disj cue-name))
    (when (= folder-name new-folder-name)  ; Add to the new folder it belongs in.
      (swap-show! show update-in [:contents :cue-library-folders folder-name] conj cue-name))))

(defn- build-library-cue-move-submenu
  "Creates a submenu for moving a library cue to a different folder."
  [cue-name _cue context]
  (let [[show]  (latest-show-and-context context)
        folders (get-in show [:contents :cue-library-folders])
        current (library-cue-folder show cue-name)]
    (seesaw/menu :text (str "“" cue-name "” to")
                 :items (concat (filter identity
                                        (for [folder (sort (keys folders))]
                                          (when (not= folder current)
                                            (seesaw/action :name (str "Folder “" folder "”")
                                                           :handler (fn [_]
                                                                      (move-cue-to-folder show cue-name folder))))))
                                (when current
                                  [(seesaw/action :name "Top Level"
                                                  :handler (fn [_] (move-cue-to-folder show cue-name nil)))])))))

(defn- build-library-cue-action
  "Creates an action that adds a cue from the library to the track
  or phrase trigger."
  [cue-name cue context]
  (seesaw/action :name (str "New “" cue-name "” Cue")
                 :enabled? (or (track? context) (some? (get-current-selection context)))
                 :handler (fn [_]
                            (try
                              (let [uuid                (java.util.UUID/randomUUID)
                                    [show context
                                     runtime-info]      (latest-show-and-context context)
                                    [start end section] (get-in runtime-info [:cues-editor :selection] [1 2 nil])
                                    all-names           (map :comment (vals (get-in runtime-info [:cues :cues])))
                                    new-name            (if (some #(= cue-name %) all-names)
                                                          (util/assign-unique-name all-names cue-name)
                                                          cue-name)
                                    new-cue             (merge cue {:uuid    uuid
                                                                    :start   start
                                                                    :end     end
                                                                    :hue     (assign-cue-hue context)
                                                                    :comment new-name
                                                                    :linked  cue-name}
                                                               (when section {:section section}))]
                                (if (track? context)
                                  (do
                                    (swap-track! context assoc-in [:contents :cues :cues uuid] new-cue)
                                    (swap-track! context update :cues-editor dissoc :selection))
                                  (do
                                    (swap-phrase! show context assoc-in [:cues :cues uuid] new-cue)
                                    (swap-phrase! show context update-in [:cues :sections section]
                                                  (fnil conj #{}) uuid)
                                    (swap-phrase-runtime! show context update :cues-editor dissoc :selection)))
                                (su/update-gear-icon context)
                                (build-cues context)
                                (compile-cue-expressions context new-cue)
                                (scroll-wave-to-cue context new-cue)
                                (scroll-to-cue context new-cue true))
                              (catch Exception e
                                (timbre/error e "Problem adding Library Cue")
                                (seesaw/alert (str e) :title "Problem adding Library Cue" :type :error))))))

(defn- rename-library-cue-action
  "Creates an action that allows a cue in the library to be renamed,
  preserving any links to it."
  [cue-name _cue context]
  (seesaw/action :name (str "Rename “" cue-name "”")
                 :handler (fn [_]
                            (let [[show _ runtime-info] (latest-show-and-context context)
                                  parent                (get-in runtime-info [:cues-editor :frame])]
                              (when-let [new-name
                                         (seesaw/invoke-now
                                          (JOptionPane/showInputDialog parent "Choose new name:"
                                                                       (str "Rename Cue "
                                                                            (full-library-cue-name show cue-name))
                                                                       javax.swing.JOptionPane/QUESTION_MESSAGE))]
                                (when-not (or (clojure.string/blank? new-name) (= new-name cue-name))
                                  (if (contains? (get-in show [:contents :cue-library]) new-name)
                                    (seesaw/alert parent (str "Cue " (full-library-cue-name show new-name)
                                                              " already exists.")
                                                  :name "Duplicate Cue" :type :error)
                                    (do
                                      ;; Update the name in the library itself.
                                      (swap-show! show
                                                  (fn [current]
                                                    (let [updated-cue (assoc (get-in current [:contents :cue-library
                                                                                              cue-name])
                                                                             :comment new-name)]
                                                      (update-in current [:contents :cue-library]
                                                                 (fn [library]
                                                                   (-> library
                                                                       (dissoc cue-name)
                                                                       (assoc new-name updated-cue)))))))
                                      ;; Update the name in any folder to which the cue belongs.
                                      (doseq [[folder-name folder] (get-in show [:contents :cue-library-folders])]
                                        (when (folder cue-name)
                                          (swap-show! show update-in [:contents :cue-library-folders folder-name]
                                                      (fn [contents] (-> contents
                                                                         (disj cue-name)
                                                                         (conj new-name))))))
                                      ;; Update any linked cues to link to the new name.
                                      (doseq [track (vals (:tracks show))
                                              other-cue   (vals (get-in track [:contents :cues :cues]))]
                                        (when (= cue-name (:linked other-cue))
                                          (swap-track! track update-in [:contents :cues :cues (:uuid other-cue)]
                                                       assoc :linked new-name)))
                                      (doseq [phrase (vals (get-in show [:contents :phrases]))
                                              other-cue   (vals (get-in phrase [:cues :cues]))]
                                        (when (= cue-name (:linked other-cue))
                                          (swap-phrase! show phrase update-in [:cues :cues (:uuid other-cue)]
                                                        assoc :linked new-name)))))))))))

(defn describe-unlinking-cues
  "Called when we are reporting that a track contains cues that are
  about to be unlinked if the user proceeds with the deletion of a
  library cue. Lists their names in quotation marks up to a limit."
  [names max-cues]
  (str (str/join "," (map #(str "“" % "”") (take max-cues names)))
       (when (seq (drop max-cues names))
         ", and others.")))

(defn- describe-unlinking
  "Called when the user is deleting a library cue. Warns about any
  linked cues which will be unlinked if they proceed. `show` must be
  current."
  [cue-name show]
  (let [tracks     (filter identity
                           (for [track (vals (:tracks show))]
                             (let [cues (filter #(= (:linked %) cue-name)
                                                (vals (get-in track [:contents :cues :cues])))]
                               (when (seq cues)
                                 [(su/display-title track) (map :comment cues)]))))
        phrases     (filter identity
                            (for [phrase (vals (get-in show [:contents :prases]))]
                             (let [cues (filter #(= (:linked %) cue-name)
                                                (vals (get-in phrase [:cues :cues])))]
                               (when (seq cues)
                                 [(su/display-title phrase) (map :comment cues)]))))
        max-tracks 4
        max-cues   4]
    (when (seq tracks)
      (str
       (apply str "\r\nThere are cues linked to this, and all will be unlinked if you proceed:\r\n"
              (map (fn [[track cues]]
                     (str "  In Track: “" track "”\r\n"
                          "      Cues: " (describe-unlinking-cues cues max-cues) "\r\n"))
                   (take max-tracks tracks)))
       (when (seq (drop max-tracks tracks))
         "  …and other tracks.")
       (when (seq phrases)
         (apply str "\r\n"
                (map (fn [[phrase cues]]
                     (str "  In Phrase Trigger: “" phrase "”\r\n"
                          "               Cues: " (describe-unlinking-cues cues max-cues) "\r\n"))
                     (take max-tracks phrases))))
       (when (seq (drop max-tracks phrases))
         "  …and other phrase triggers.")))))

(defn- delete-library-cue-action
  "Creates an action that allows a cue in the library to be deleted,
  breaking any links to it, after confirming the user really wants
  this to happen."
  [cue-name _cue context]
  (let [[show context runtime-info] (latest-show-and-context context)
        parent                      (get-in runtime-info [:cues-editor :frame])
        unlinking                   (describe-unlinking cue-name show)]
    (seesaw/action :name (str "Delete “" cue-name "”")
                   :handler (fn [_]
                              (when (util/confirm parent
                                                  (str "Deleting this library cue will discard all its "
                                                       "configuration and expressions\r\n"
                                                       "and cannot be undone.\r\n"
                                                       unlinking)
                                                  :type (if (str/blank? unlinking) :question :warning)
                                                  :title (str "Delete Library Cue "
                                                              (full-library-cue-name show cue-name) "?"))
                                ;; Remove the cue from the library itself.
                                (swap-show! show update-in [:contents :cue-library] dissoc cue-name)
                                ;; Remove it from any folder to which it belonged.
                                (doseq [[folder-name folder] (get-in show [:contents :cue-library-folders])]
                                  (when (folder cue-name)
                                    (swap-show! show update-in [:contents :cue-library-folders folder-name]
                                                disj cue-name)))
                                ;; Unlink any cues that had been linked to it.
                                (doseq [track (vals (:tracks show))
                                        other-cue   (vals (get-in track [:contents :cues :cues]))]
                                  (when (= cue-name (:linked other-cue))
                                    (swap-track! track
                                                 update-in [:contents :cues :cues (:uuid other-cue)]
                                                 dissoc :linked)
                                    ;; If there is an editor open for that cue, update its link button icon.
                                    (when-let [panel (get-in track [:cues-editor :panels (:uuid other-cue)])]
                                      (update-cue-link-icon track other-cue (seesaw/select panel [:#link])))))
                                (doseq [phrase (vals (get-in show [:contents :phrases]))
                                        other-cue   (vals (get-in phrase [:cues :cues]))]
                                  (when (= cue-name (:linked other-cue))
                                    (swap-phrase! show phrase
                                                  update-in [:cues :cues (:uuid other-cue)]
                                                  dissoc :linked)
                                    ;; If there is an editor open for that cue, update its link button icon.
                                    (when-let [panel (get-in (su/phrase-runtime-info show phrase)
                                                             [:cues-editor :panels (:uuid other-cue)])]
                                      (update-cue-link-icon phrase other-cue (seesaw/select panel [:#link])))))
                                ;; Finally, if we deleted the last library cue, make all the library buttons vanish.
                                (update-library-button-visibility show))))))

(defn- show-cue-library-popup
  "Displays the popup menu allowing you to add a cue from the library to
  a track or phrase trigger."
  [context wave-or-canvas ^MouseEvent e]
  (let [[cue context]      (find-cue-under-mouse context wave-or-canvas e)
        [_ _ runtime-info] (latest-show-and-context context)
        popup-items        (if cue
                             (let [panel    (get-in runtime-info [:cues-editor :panels (:uuid cue)])
                                   popup-fn (:popup (seesaw/user-data panel))]
                               (popup-fn e))
                             (build-cue-library-popup-items context build-library-cue-action))]
    (util/show-popup-from-button wave-or-canvas (seesaw/popup :items popup-items) e)))

(defn- handle-wave-move
  "Processes a mouse move over the wave detail component (or cue canvas
  if this is a phrase trigger), setting the tooltip and mouse pointer
  appropriately depending on the location of cues and selection."
  [context ^JPanel wave-or-canvas ^MouseEvent e]
  (let [[cue context]         (find-cue-under-mouse context wave-or-canvas e)
        [show _ runtime-info] (latest-show-and-context context)
        x                     (.getX e)
        [beat section]        (beat-for-x context wave-or-canvas x)
        selection             (get-in runtime-info [:cues-editor :selection])
        [_ edge]              (find-click-edge-target context wave-or-canvas e selection cue)
        default-cursor        (case edge
                                :start @move-w-cursor
                                :end   @move-e-cursor
                                (Cursor/getPredefinedCursor Cursor/CROSSHAIR_CURSOR))]
    (.setToolTipText wave-or-canvas (if cue
                                      (or (:comment cue) "Unnamed Cue")
                                      "Click and drag to select a beat range for the New Cue button."))
    (if selection
      (if (= selection [beat (inc beat)])
        (let [shifted   @delete-cursor ; We are hovering over a single-beat selection, and can delete it.
              unshifted default-cursor]
          (.setCursor wave-or-canvas (if (shift-down? e) shifted unshifted))
          (su/swap-context-runtime! show context assoc-in [:cues-editor :cursors] [unshifted shifted]))
        (let [shifted   (drag-cursor context beat)
              unshifted default-cursor]
          (.setCursor wave-or-canvas (if (shift-down? e) shifted unshifted))
          (su/swap-context-runtime! show context assoc-in [:cues-editor :cursors] [unshifted shifted])))
      (do
        (.setCursor wave-or-canvas default-cursor)
        (su/swap-context-runtime! show context update :cues-editor dissoc :cursors)))))

(defn- find-selection-drag-target
  "Checks if a drag target for a general selection has already been
  established; if so, returns it, otherwise sets one up, unless we are
  still sitting on the initial beat of a just-created selection."
  [context start end section beat]
  (let [[show context runtime-info] (latest-show-and-context context)]
    (or (get-in runtime-info [:cues-editor :drag-target])
        (when (and start end) (not= start (dec end) beat)
              (let [start-distance (Math/abs (long (- beat start)))
                    end-distance   (Math/abs (long (- beat (dec end))))
                    target         [nil (if (< beat start) :start (if (< start-distance end-distance) :start :end))
                                    section]]
                (su/swap-context-runtime! show context assoc-in [:cues-editor :drag-target] target)
                target)))))

(defn- update-new-cue-state
  "When the selection has changed in a phrase trigger cue canvas, update
  the enabled state of the New Cue button appropriately."
  [context]
  (when (phrase? context)
    (let [[_ context runtime-info] (su/latest-show-and-context context)
          enabled?                 (some? (get-current-selection context))]
         (seesaw/config! (seesaw/select (get-in runtime-info [:cues-editor :panel]) [:#new-cue])
                         :enabled? enabled?
                         :tip (if enabled?
                                "Create a new cue on the selected beats."
                                "Disabled because no beat range is selected.")))))

(defn- handle-wave-drag
  "Processes a mouse drag in the wave detail component (or cue canvas if
  this is a phrase trigger), used to adjust beat ranges for creating
  cues."
  [context ^JPanel wave-or-canvas ^MouseEvent e]
  (let [[show context runtime-info] (latest-show-and-context context)
        x                           (.getX e)
        [beat section]              (beat-for-x context wave-or-canvas x)
        [start end sel-section]     (get-in runtime-info [:cues-editor :selection])
        [cue edge drag-section]     (find-selection-drag-target context start end (or sel-section section) beat)
        beat                        (if (= section drag-section)
                                      beat
                                      (if (> (su/phrase-section-positions section)
                                             (su/phrase-section-positions drag-section))
                                        (beat-count context drag-section)  ; Pin correct edge of current section.
                                        1))]
    ;; We are trying to adjust an existing cue or selection. Move the end that was nearest to the mouse.
    (when edge
      (if cue
        (do  ; We are dragging the edge of a cue.
          (if (= :start edge)
            (swap-cue! context cue assoc :start (min (dec (:end cue)) (max 1 beat)))
            (swap-cue! context cue assoc :end (max (inc (:start cue))
                                                   (min (beat-count context (:section cue)) (inc beat)))))
          (build-cues context))
        ;; We are dragging the beat selection.
        (let [new-selection (if (= :start edge)
                              [(min end (max 1 beat)) end drag-section]
                              [start (max start (min (beat-count context drag-section) (inc beat)))
                               drag-section])]
          (su/swap-context-runtime! show context assoc-in [:cues-editor :selection] new-selection)
          (update-new-cue-state context)))

      (.setCursor wave-or-canvas (if (= :start edge) @move-w-cursor @move-e-cursor))
      (.repaint wave-or-canvas))
    ;; Cursor no longer depends on Shift key state.
    (su/swap-context-runtime! show context update :cues-editor dissoc :cursors)))

(defn- handle-wave-click
  "Processes a mouse click in the wave detail component, used for
  setting up beat ranges for creating cues, and scrolling the lower
  pane to cues. Ignores right-clicks and control-clicks so those can
  pull up the context menu."
  [context ^JPanel wave-or-canvas ^MouseEvent e]
  (if (context-click? e)
    (show-cue-library-popup context wave-or-canvas e)
    (let [[cue context]         (find-cue-under-mouse context wave-or-canvas e)
          [show _ runtime-info] (latest-show-and-context context)
          x                     (.getX e)
          [beat section]        (beat-for-x context wave-or-canvas x)
          selection             (get-in runtime-info [:cues-editor :selection])]
      (if (and (shift-down? e) selection)
        (if (= (take 2 selection) [beat (inc beat)])
          (do  ; Shift-click on single-beat selection clears it.
            (su/swap-context-runtime! show context update :cues-editor dissoc :selection :cursors)
            (.setCursor wave-or-canvas (Cursor/getPredefinedCursor Cursor/CROSSHAIR_CURSOR)))
          (handle-wave-drag context wave-or-canvas e))  ; Adjusting an existing selection; we can handle it as a drag.
        (if-let [target (find-click-edge-target context wave-or-canvas e selection cue)]
          (do ; We are dragging the edge of the selection or a cue.
            (su/swap-context-runtime! show context assoc-in [:cues-editor :drag-target] target)
            (handle-wave-drag context wave-or-canvas e))
          ;; We are starting a new selection.
          (if (< 0 beat (beat-count context section))  ; Was the click in a valid place to make a selection?
            (do  ; Yes, set new selection.
              (su/swap-context-runtime! show context assoc-in [:cues-editor :selection] [beat (inc beat) section])
              (handle-wave-move context wave-or-canvas e))  ; Update the cursors.

            (su/swap-context-runtime! show context update :cues-editor dissoc :selection))))  ; No, clear selection.
      (.repaint wave-or-canvas)
      (update-new-cue-state context)
      (when cue (scroll-to-cue context cue false true)))))

(defn- handle-wave-release
  "Processes a mouse-released event in the wave detail component
  (or cue canvas if this is a phrase trigger), cleaning up any
  drag-tracking structures and cursors that were in effect."
  [context ^JPanel wave-or-canvas ^MouseEvent e]
  (let [[show context runtime-info] (latest-show-and-context context)
        [cue-dragged] (get-in runtime-info [:cues-editor :drag-target])]
    (when cue-dragged
      (let [cue (find-cue context cue-dragged)
            panel (get-in runtime-info [:cues-editor :panels (:uuid cue)])]
        (seesaw/value! (seesaw/select panel [:#start]) (:start cue))
        (seesaw/value! (seesaw/select panel [:#end]) (:end cue))))
    (when-let [[start end] (seq (take 2 (get-in runtime-info [:cues-editor :selection])))]
      (when (>= start end)  ; If the selection has shrunk to zero size, remove it.
        (su/swap-context-runtime! show context update :cues-editor dissoc :selection)
        (update-new-cue-state context)))
    (su/swap-context-runtime! show context update :cues-editor dissoc :drag-target))
  (handle-wave-move context wave-or-canvas e))  ; This will restore the normal cursor.

(defn- assign-cue-lanes
  "Given a sorted list of the cues for a track or phrase trigger
  section, assigns each a non-overlapping lane number, choosing the
  smallest value that no overlapping neighbor has already been
  assigned. Returns a map from cue UUID to its assigned lane.
  `cue-intervals` is either a simple interval map for an entire track,
  or a map whose keys are phrase trigger section keywords, and whose
  values are the corresponding interval map for the cues in that
  section of the phrase trigger."
  [context cues cue-intervals]
  (reduce (fn [result cue]
            (let [neighbors (map (partial find-cue context)
                                 (util/iget (if (track? context) cue-intervals ((:section cue) cue-intervals))
                                            (:start cue) (:end cue)))
                  used      (set (filter identity (map #(result (:uuid %)) neighbors)))]
              (assoc result (:uuid cue) (first (remove used (range))))))
          {}
          cues))

(defn- gather-cluster
  "Given a cue, returns the set of cues that overlap with it (including
  itself), and transitively any cues which overlap with them.
  `cue-intervals` is either a simple interval map for an entire track,
  or a map whose keys are phrase trigger section keywords, and whose
  values are the corresponding interval map for the cues in that
  section of the phrase trigger."
  [context cue cue-intervals]
  (let [neighbors (set (map (partial find-cue context)
                            (util/iget (if (track? context) cue-intervals ((:section cue) cue-intervals))
                                       (:start cue) (:end cue))))]
    (loop [result    #{cue}
           remaining (clojure.set/difference neighbors result)]
      (if (empty? remaining)
        result
        (let [current   (first remaining)
              result    (conj result current)
              neighbors (set (map (partial find-cue context)
                                  (util/iget (if (track? context) cue-intervals ((:section current) cue-intervals))
                                             (:start current) (:end current))))]
          (recur result (clojure.set/difference (clojure.set/union neighbors remaining) result)))))))

(defn- position-cues
  "Given a sorted list of the cues for a track or phrase trigger
  section, assigns each a non-overlapping lane, and determines how
  many lanes are needed to draw each overlapping cluster of cues.
  Returns a map from cue uuid to a tuple of the cue's lane assignment
  and cluster lane count. `cue-intervals` is either a simple interval
  map for an entire track, or a map whose keys are phrase trigger
  section keywords, and whose values are the corresponding interval
  map for the cues in that section of the phrase trigger."
  [context cues cue-intervals]
  (let [lanes (assign-cue-lanes context cues cue-intervals)]
    (reduce (fn [result cue]
              (if (result (:uuid cue))
                result
                (let [cluster   (set (map :uuid (gather-cluster context cue cue-intervals)))
                      max-lanes (inc (apply max (map lanes cluster)))]
                  (apply merge result (map (fn [uuid] {uuid [(lanes uuid) max-lanes]}) cluster)))))
            {}
            cues)))

(defn- cue-panel-constraints
  "Calculates the proper layout constraints for the cue waveform panel
  to properly fit the largest number of cue lanes required. We make
  sure there is always room to draw a trqck waveform even if there are
  few lanes and a horizontal scrollbar ends up being needed."
  [context]
  (let [[_ _ runtime-info] (latest-show-and-context context)
        max-lanes          (get-in runtime-info [:cues :max-lanes] 1)
        wave-height        (max 92 (* max-lanes min-lane-height))]
    ["" "" (str "[][fill, " (+ wave-height 18) "]")]))

(defn- calculate-minimum-section-sizes
  "Called when cues have been rebuilt in a phrase trigger to update the
  section spinner models so that no section can be shrunk too small to
  fit its latest cue."
  [phrase]
  (let [[_ phrase runtime-info] (latest-show-and-context phrase)
        panel                      (:panel runtime-info)
        min-sizes                  (reduce (fn [result {:keys [section end]}]
                                             (update result section max (quot (+ end 2) 4)))
                                           {:start 0 :loop 1 :end 0 :fill 1}
                                           (vals (get-in phrase [:cues :cues])))]
    (doseq [[section min-bars] min-sizes]
      (let [model (seesaw/config (seesaw/select panel [(keyword (str "#" (name section)))]) :model)]
        (.setMinimum model min-bars)))))

(defn build-cues
  "Updates the track or phrase trigger structures to reflect the cues
  that are present. If there is an open cues editor window, also
  updates it. This will be called when the show is initially loaded,
  and whenever the cues are changed.

  If this is for a phrase trigger, also updates the minimum bounds of
  the section spinner models so the sections can't be shrunk past
  where cues exist."
  [context]
  (let [[show context runtime-info] (latest-show-and-context context)
        contents                    (if (phrase? context) context (:contents context))
        section-position            (fn [cue] (su/phrase-section-positions (:section cue)))
        sorted-cues                 (sort-by (juxt section-position :start :end :comment :uuid)
                                             (vals (get-in contents [:cues :cues])))
        cue-intervals               (if (track? context)
                                      (reduce (fn [result cue]
                                                (util/iassoc result (:start cue) (:end cue) (:uuid cue)))
                                              util/empty-interval-map
                                              sorted-cues)
                                      (reduce (fn [result cue]
                                                (update result (:section cue) (fnil util/iassoc util/empty-interval-map)
                                                        (:start cue) (:end cue) (:uuid cue)))
                                              {}
                                              sorted-cues))
        cue-positions               (position-cues context sorted-cues cue-intervals)]
    (su/swap-context-runtime! show context (fn [runtime-info]
                                             (-> runtime-info
                                                 (assoc-in [:cues :sorted] (mapv :uuid sorted-cues))
                                                 (assoc-in [:cues :intervals] cue-intervals)
                                                 (assoc-in [:cues :position] cue-positions)
                                                 (assoc-in [:cues :max-lanes]
                                                           (apply max 1 (map second (vals cue-positions)))))))
    (su/repaint-preview context)
    (when (:cues-editor runtime-info)
      (update-cue-visibility context)
      (repaint-all-cue-states context)
      (.repaint ^JComponent (get-in runtime-info [:cues-editor :wave]))
      (let [^JPanel panel (get-in runtime-info [:cues-editor :panel])]
        (seesaw/config! panel :constraints (cue-panel-constraints context))
        (.revalidate panel)))
    (when (phrase? context) (calculate-minimum-section-sizes context))))

(defn- new-cue
  "Handles a click on the New Cue button, which creates a cue with the
  selected beat range, or a default range if there is no selection."
  [context]
  (let [[show context runtime-info] (latest-show-and-context context)
        contents                    (if (phrase? context) context (:contents context))
        [start end section]         (get-in runtime-info [:cues-editor :selection] [1 2])
        uuid                        (java.util.UUID/randomUUID)
        cue                         (merge {:uuid    uuid
                                            :start   start
                                            :end     end
                                            :hue     (assign-cue-hue context)
                                            :comment (util/assign-unique-name
                                                      (map :comment (vals (get-in contents [:cues :cues]))))}
                                           (when section
                                             {:section section}))]
    (if (track? context)
      (do
        (swap-track! context assoc-in [:contents :cues :cues uuid] cue)
        (swap-track! context update :cues-editor dissoc :selection))
      (do
        (when-not section
          (throw (IllegalArgumentException.
                  "Can't create a cue in a phrase trigger without a selection to identify the section.")))
        (swap-phrase! show context (fn [phrase]
                                     (-> phrase
                                         (assoc-in [:cues :cues uuid] cue)
                                         (update-in [:cues :sections section]
                                                    (fnil conj #{}) uuid))))
        (swap-phrase-runtime! show context update :cues-editor dissoc :selection)))
    (su/update-gear-icon context)
    (build-cues context)
    (scroll-wave-to-cue context cue)
    (scroll-to-cue context cue true)))

(defn- start-animation-thread
  "Creates a background thread that updates the positions of any playing
  players 30 times a second so that the wave or phrase canvas moves
  smoothly. The thread will exit whenever the cues window closes."
  [show context]
  (future
    (loop [editor (:cues-editor (if (track? context)
                                  (latest-track context)
                                  (phrase-runtime-info (latest-show show) context)))]
      (when editor
        (try
          (Thread/sleep 33)
          (let [show (latest-show show)]
            (if (track? context)
              (doseq [^Long player (util/players-signature-set (:playing show) (:signature context))]
                (when-let [position (.getLatestPositionFor util/time-finder player)]
                  (.setPlaybackState ^WaveformDetailComponent (:wave editor)
                                     player (.getTimeFor util/time-finder player) (.playing position))))
              (doseq [^Long player (util/players-phrase-uuid-set (:playing-phrases show) (:uuid context))]
                (when-let [position (.getLatestPositionFor util/time-finder player)]
                  ;; TODO: Something equivalent for phrase triggers.
                  #_(.setPlaybackState ^WaveformDetailComponent (:wave editor)
                                       player (.getTimeFor util/time-finder player) (.playing position))))))
          (catch Throwable t
            (timbre/warn "Problem animating cues editor waveform" t)))
        (recur (:cues-editor (if (track? context)
                               (latest-track context)
                               (phrase-runtime-info (latest-show show) context))))))
    #_(timbre/info "Cues editor animation thread ending.")))

(defn- new-cue-folder
  "Opens a dialog in which a new cue folder can be created."
  [context]
  (let [[show _ runtime-info] (latest-show-and-context context)
        parent (get-in runtime-info [:cues-editor :frame])]
    (when-let [new-name (seesaw/invoke-now
                         (JOptionPane/showInputDialog parent "Choose the folder name:" "New Cue Library Folder"
                                                      javax.swing.JOptionPane/QUESTION_MESSAGE))]
      (when-not (clojure.string/blank? new-name)
        (if (contains? (get-in show [:contents :cue-library-folders]) new-name)
          (seesaw/alert parent (str "Folder “" new-name "” already exists.") :name "Duplicate Folder" :type :error)
          (swap-show! show assoc-in [:contents :cue-library-folders new-name] #{}))))))

(defn- rename-cue-folder
  "Opens a dialog in which a cue folder can be renamed."
  [context folder]
  (let [[show _ runtime-info] (latest-show-and-context context)
        parent (get-in runtime-info [:cues-editor :frame])]
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
  [context folder]
  (let [[show _ runtime-info] (latest-show-and-context context)]
    (when (util/confirm (get-in runtime-info [:cues-editor :frame])
                        (str "Removing a cue library folder will move all of its cues\r\n"
                             "back to the top level of the cue library.")
                        :type :question :title (str "Remove Folder “" folder "”?"))
      (swap-show! show update-in [:contents :cue-library-folders] dissoc folder))))

(defn- build-cue-library-button-menu
  "Builds the menu that appears when you click in the cue library
  button, which includes the same cue popup menu that is available
  when right-clicking in the track waveform or phrase trigger cue
  canvas, but adds options at the end for managing cue folders in case
  you have a lot of cues."
  [context]
  (let [[show context] (latest-show-and-context context)
        folders        (sort (keys (get-in show [:contents :cue-library-folders])))]
    (concat
     (build-cue-library-popup-items context build-library-cue-action)
     [(seesaw/menu :text "Manage Cues"
                   :items (concat
                           (when (seq (get-in show [:contents :cue-library-folders]))
                             [(seesaw/menu :text "Move"
                                           :items (build-cue-library-popup-items context
                                                                                 build-library-cue-move-submenu))])
                           [(seesaw/menu :text "Rename"
                                         :items (build-cue-library-popup-items context rename-library-cue-action))
                            (seesaw/menu :text "Delete"
                                         :items (build-cue-library-popup-items context delete-library-cue-action))]))
      (seesaw/menu :text "Manage Folders"
                   :items (concat
                           [(seesaw/action :name "New Folder"
                                           :handler (fn [_] (new-cue-folder context)))]
                           (when (seq folders)
                             [(seesaw/menu :text "Rename"
                                           :items (for [folder folders]
                                                    (seesaw/action :name folder
                                                                   :handler (fn [_]
                                                                              (rename-cue-folder context folder)))))
                              (seesaw/menu :text "Remove"
                                           :items (for [folder folders]
                                                    (seesaw/action :name folder
                                                                   :handler (fn [_]
                                                                              (remove-cue-folder context
                                                                                                 folder)))))])))])))

(defn- paint-cue-canvas
  "Draws the zommable scrolling view of the phrase trigger on which cues
  can be placed."
  [context c ^Graphics2D g]
  (let [h              (double (seesaw/height c))
        [show phrase
         runtime-info] (latest-show-and-context context)
        sections       (:sections runtime-info)
        active?        false ; TODO: TBD!
        bars           (:total-bars sections)
        stroke         (.getStroke g)
        label-font     (.getFont (javax.swing.UIManager/getDefaults) "Label.font")
        stripe         (fn [section]  ; Paint one of the section stripes.
                         (let [x              (x-for-beat phrase c 1 section)
                               [from-bar
                                to-bar]       (section sections)
                               w              (- (dec (x-for-beat phrase c (inc (* 4 (- to-bar from-bar))) section)) x)
                               label          (str/capitalize (name section))
                               render-context (.getFontRenderContext g)
                               metrics        (.getLineMetrics label-font label render-context)
                               text-height    (long (Math/ceil (+ (.getAscent metrics) (.getDescent metrics))))
                               y              (- h su/cue-canvas-margin text-height 1)
                               phrase-rect    (Rectangle2D$Double. x y w (+ text-height 2))
                               old-clip       (.getClip g)]
                           (.setPaint g (su/phrase-section-colors section))
                           (.fill g phrase-rect)
                           (.setClip g phrase-rect)
                           (.setPaint g Color/black)
                           (.drawString g label (int (+ x 2)) (int (- h su/cue-canvas-margin 4)))
                           (.setClip g old-clip)))
        fence (fn [section]  ; Paint one of the section boundary fences.
                (let [[from-bar to-bar] (section sections)
                      x                 (x-for-beat phrase c (inc (* 4 (- to-bar from-bar))) section)]
                  (.drawLine g x su/cue-canvas-margin x (- h su/cue-canvas-margin))))]

    (doseq [i (range 0 (inc (* 4 bars)) (if (< (get-in phrase [:cues :zoom] 4) 10) 1 4))]
      (.setPaint g (if (zero? (mod i 4)) Color/red Color/white))
      (let [x (cue-canvas-x-for-time phrase (* 500 i))]
        (.drawLine g x su/cue-canvas-margin x (+ su/cue-canvas-margin 4))
        (.drawLine g x (- h su/cue-canvas-margin 8) x (- h su/cue-canvas-margin 4))))

    ;; Paint the section stripes.
    (.setFont g label-font)
    (when (:start sections)
      (stripe :start))
    (stripe :loop)
    (when (:end sections)
      (stripe :end))
    (stripe :fill)

    ;; Paint the section boundaries.
    (.setPaint g Color/white)
    (.setStroke g (BasicStroke. 1 BasicStroke/CAP_BUTT BasicStroke/JOIN_ROUND 1.0
                                (float-array [3.0 3.0]) 1.0))
    (when (:start sections)
      (fence :start))
    (fence :loop)
    (when (:end sections)
      (fence :end))
    (.setStroke g stroke)

    (let [g2 (.create g)]
      ;; Paint the cues.
      (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER cue-opacity))
      (doseq [cue (vals (get-in phrase [:cues :cues]))]
        (.setPaint g2 (su/hue-to-color (:hue cue) (cue-lightness phrase cue)))
        (.fill g2 (cue-rectangle phrase cue c)))
      ;; Paint the beat selection, if any.
      (when-let [[start end section] (get-current-selection phrase)]
        (let [x (x-for-beat phrase c start section)
              w (- (x-for-beat phrase c end section) x)]
        (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER selection-opacity))
        (.setPaint g2 Color/white)
        (.fill g2 (java.awt.geom.Rectangle2D$Double. (double x) 0.0 (double w) h))))
      (.dispose g2))))

(defn- create-cues-window
  "Create and show a new cues window for the specified track or phrase
  trigger."
  [context parent]
  (let [[show context] (latest-show-and-context context)
        contents       (if (phrase? context) context (:contents context))
        track-root     (when (track? context) (su/build-track-path show (:signature context)))
        ^JFrame root   (seesaw/frame :title (str "Cues for " (if track-root "Track: " "Phrase Trigger: ")
                                                 (su/display-title context))
                                     :on-close :nothing)
        wave           (if track-root
                         (WaveformDetailComponent. ^WaveformDetail (su/read-detail track-root)
                                                   ^CueList (su/read-cue-list track-root)
                                                   ^BeatGrid (:grid context))
                         (seesaw/canvas :id :wave :paint (partial paint-cue-canvas context)
                                        :opaque? true :size [(cue-canvas-width context) :by 92]))
        song-structure (when-let [bytes (when track-root (su/read-song-structure track-root))]
                         (RekordboxAnlz$SongStructureTag. (ByteBufferKaitaiStream. bytes)))
        zoom-slider    (seesaw/slider :id :zoom :min 1 :max max-zoom :value (get-in contents [:cues :zoom] 4))
        filter-field   (seesaw/text (get-in contents [:cues :filter] ""))
        entered-only   (seesaw/checkbox :id :entered-only :text "Entered Only" :visible? (util/online?)
                                        :selected? (boolean (get-in contents [:cues :entered-only]))
                                        :listen [:item-state-changed #(set-entered-only context (seesaw/value %))])
        auto-scroll    (seesaw/checkbox :id :auto-scroll :text "Auto-Scroll" :visible? (and (util/online?) track-root)
                                        :selected? (boolean (get-in contents [:cues :auto-scroll]))
                                        :listen [:item-state-changed #(set-auto-scroll context wave (seesaw/value %))])
        lib-popup-fn   (fn [] (seesaw/popup :items (build-cue-library-button-menu context)))
        zoom-anchor    (atom nil) ; The x coordinate we want to keep the wave anchored at when zooming.
        wave-scale     (fn []  ; Determine the current scale of the waveform or cue canvas.
                         (if track-root
                           (.getScale wave)
                           (get-in (latest-phrase show context) [:cues :zoom] 4)))
        wave-scroll    (proxy [javax.swing.JScrollPane] [wave]
                         (processMouseWheelEvent [^java.awt.event.MouseWheelEvent e]
                           (if (.isShiftDown e)
                             (proxy-super processMouseWheelEvent e)
                             (let [zoom (min max-zoom (max 1 (+ (wave-scale) (.getWheelRotation e))))]
                               (reset! zoom-anchor (.getX e))
                               (seesaw/value! zoom-slider zoom)))))
        new-cue        (seesaw/button :id :new-cue :text "New Cue"
                                      :listen [:action-performed (fn ([_] (new-cue context)))]
                                      :enabled? (some? track-root))
        top-panel      (mig/mig-panel :background "#aaa" :constraints (cue-panel-constraints context)
                                      :items [[new-cue]
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
        cues           (seesaw/vertical-panel :id :cues)
        cues-scroll    (seesaw/scrollable cues)
        layout         (seesaw/border-panel :north top-panel :center cues-scroll)
        key-spy        (proxy [java.awt.KeyEventDispatcher] []
                         (dispatchKeyEvent [^java.awt.event.KeyEvent e]
                           (handle-wave-key context wave e)
                           false))
        close-fn       (fn [force?]
                         ;; Closes the cues window and performs all necessary cleanup. If `force?` is true,
                         ;; will do so even in the presence of windows with unsaved user changes. Otherwise
                         ;; prompts the user about all unsaved changes, giving them a chance to veto the
                         ;; closure. Returns truthy if the window was closed.
                         (let [[_ context] (latest-show-and-context context)
                               contents    (if (phrase? context) context (:contents context))
                               cues        (vals (get-in contents [:cues :cues]))]
                           (when (every? (partial close-cue-editors? force? context) cues)
                             (doseq [cue cues]
                               (cleanup-cue true context cue))
                             (seesaw/invoke-later
                              ;; Gives windows time to close first, so they don't recreate a broken editor.
                              (su/swap-context-runtime! show context dissoc :cues-editor)
                              (su/repaint-preview context))  ; Removes the editor viewport overlay.
                             (.removeKeyEventDispatcher (java.awt.KeyboardFocusManager/getCurrentKeyboardFocusManager)
                                                        key-spy)
                             (.dispose root)
                             true)))
        editor-info    {:frame    root
                        :panel    top-panel
                        :wave     wave
                        :scroll   wave-scroll
                        :close-fn close-fn}]
    (su/swap-context-runtime! show context assoc :cues-editor editor-info)
    (.addKeyEventDispatcher (java.awt.KeyboardFocusManager/getCurrentKeyboardFocusManager) key-spy)
    (.addChangeListener (.getViewport wave-scroll)
                        (proxy [javax.swing.event.ChangeListener] []
                          (stateChanged [_] (su/repaint-preview context))))
    (when track-root
      (.setScale wave (seesaw/value zoom-slider))
      (.setAutoScroll wave (and (seesaw/value auto-scroll) (util/online?)))
      (.setOverlayPainter wave (proxy [org.deepsymmetry.beatlink.data.OverlayPainter] []
                                 (paintOverlay [component graphics]
                                   (paint-cues-and-beat-selection context component graphics))))
      (.setSongStructure wave song-structure))
    (.setCursor wave (Cursor/getPredefinedCursor Cursor/CROSSHAIR_CURSOR))
    (seesaw/listen wave
                   :mouse-moved (fn [e] (handle-wave-move context wave e))
                   :mouse-pressed (fn [e] (handle-wave-click context wave e))
                   :mouse-dragged (fn [e] (handle-wave-drag context wave e))
                   :mouse-released (fn [e] (handle-wave-release context wave e)))
    (seesaw/listen zoom-slider
                   :state-changed (fn [e]
                                    (set-zoom context wave (seesaw/value e) wave-scroll (or @zoom-anchor 0))
                                    (reset! zoom-anchor nil)))

    (seesaw/config! root :content layout)
    (build-cues context)
    (seesaw/listen filter-field #{:remove-update :insert-update :changed-update}
                   (fn [e] (cue-filter-text-changed context (seesaw/text e))))
    (.setSize root 800 600)
    (su/restore-window-position root (:cues contents) parent)
    (seesaw/listen root
                   :window-closing (fn [_] (close-fn false))
                   #{:component-moved :component-resized} (fn [_] (save-cue-window-position context root)))
    (start-animation-thread show context)
    (update-new-cue-state context)
    (su/repaint-preview context)  ; Show the editor viewport overlay.
    (seesaw/show! root)))

(defn open-cues
  "Creates, or brings to the front, a window for editing cues attached
  to the specified track or phrase trigger. Returns truthy if the
  window was newly opened."
  [track-or-phrase parent]
  (try
    (let [[_ context runtime-info] (latest-show-and-context track-or-phrase)]
      (if-let [existing (:cues-editor runtime-info)]
        (.toFront ^JFrame (:frame existing))
        (do (create-cues-window context parent)
            true)))
    (catch Throwable t
      (su/swap-context-runtime! nil track-or-phrase dissoc :cues-editor)
      (timbre/error t "Problem creating cues editor.")
      (throw t))))
