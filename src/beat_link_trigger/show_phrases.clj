(ns beat-link-trigger.show-phrases
  "Implements phrase trigger features for Show files, including their
  cue editing windows."
  (:require [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.show-cues :as cues]
            [beat-link-trigger.show-util :as su :refer [latest-show latest-phrase latest-show-and-phrase
                                                        swap-show! swap-phrase! swap-phrase-runtime!
                                                        phrase-runtime-info get-chosen-output no-output-chosen]]
            [beat-link-trigger.util :as util]
            [clojure.math.numeric-tower :as math]
            [clojure.set :as set]
            [clojure.string :as str]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink Beat CdjStatus DeviceAnnouncementListener DeviceUpdateListener]
           [org.deepsymmetry.beatlink.data BeatGrid TrackPositionUpdate]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$SongStructureTag RekordboxAnlz$SongStructureEntry]
           [java.awt BasicStroke Color Graphics2D RenderingHints]
           [java.awt.event MouseEvent]
           [java.util UUID]
           [javax.swing JFrame JPanel JScrollPane]
           [javax.swing.text JTextComponent]))

(defonce ^{:private true
           :doc "Holds a map of player numbers to an index of beat
  numbers to phrase details. If there is phrase analysis information
  available for the track playing on a player, the value under that
  player number's key holds an interval map allowing a beat number to be
  efficiently mapped to the `SongStructureEntry` (if any) in which that
  beat falls."}
  phrase-intervals (atom {}))

(defonce ^{:private true
           :doc "`:current-phrase` holds a map from player number to the `SongStructureEntry`
  being played on that player. This will only have non-`nil` values
  when there is phrase analysis information available for the tracks.
  `:playing` is a map to booleans that represent our latest assessment
  of whether that player is currently playing a track. `:last` holds a
  snapshot of the above values when updating to allow change detection.
  `:cueing` is a map from player number to a boolean indicating that the
  player seems to be previewing a track rather than actually playing it,
  which is used in calculating `:playing`, and `:last-beat` is a map from
  player number to a tuple of the timestamp when we last received a beat
  and the number of the beat that it started, so we can
  avoid being tricked by status messages arriving within a few
  milliseconds after which still report the old beat number."}
  phrase-state (atom {}))

(defn current-phrase
  "Returns the `SongStructureEntry`, if any, from the track analysis for
  the phrase that is currently playing on the specified player"
  [player]
  (get-in @phrase-state [:current-phrase player]))

(defn current-phrase-type
  "Returns the phrase type keyword identifying the phrase, if any,
  currently playing on the specified player."
  [player]
  (when-let [phrase (current-phrase player)]
    (util/phrase-type-keyword phrase)))

(defn current-track-bank
  "Returns the track bank keyword associated with the track phrase
  analysis, if any, available for the track playing on the specified
  player."
  [player]
  (when-let [phrase (current-phrase player)]
    (util/track-bank-keyword (.. phrase _parent _parent))))

(defn beat-range
  "Finds the range of beats that a phrase occupies, handling the fact
  that the first phrase may start with a partial bar by offseting to
  where its down beat would be."
  [player ^RekordboxAnlz$SongStructureEntry phrase ^BeatGrid grid]
  (let [start       (.beat phrase)
        [start end] (first (first (util/matching-subsequence (get @phrase-intervals player) start nil)))
        offset      (dec (.getBeatWithinBar grid start))]
    [(- start offset) end]))

(defn current-phrase-beat-range
  "Returns a tuple of the starting and ending beats for the phrase, if
  any, which is currently playing on the specified player."
  [player]
  (when-let [phrase (current-phrase player)]
    (let [grid (.getLatestBeatGridFor (org.deepsymmetry.beatlink.data.BeatGridFinder/getInstance) player)]
      (beat-range player phrase grid))))

(defn- capture-current-state
  "Sets up the value of the `:last` key in `phrase-state` as described
  above, suitable for use as part of a `swap!` operation."
  [state]
  (assoc state :last (select-keys state [:current-phrase :playing])))

(defn build-phrase-intervals
  "Given a song structure tag for a track, build an index that allows
  efficient mapping from a beat number to the phrase (song structure
  entry) to which that beat belongs, if any."
  [^RekordboxAnlz$SongStructureTag song-structure]
  (let [body    (.body song-structure)
        entries (sort-by (fn [^RekordboxAnlz$SongStructureTag entry] (.beat entry)) (.entries body))]
    (reduce (fn [intervals [^RekordboxAnlz$SongStructureEntry entry ^RekordboxAnlz$SongStructureEntry next-entry]]
              (if (some? next-entry)
                (util/iassoc intervals (.beat entry) (.beat next-entry) entry)
                (util/iassoc intervals (.beat entry) (.endBeat body) entry)))
            util/empty-interval-map (partition 2 1 (concat entries [nil])))))

(defn upgrade-song-structure
  "When we have learned about newly available phrase analysis
  information, see if it is something we don't yet know about. This
  must only be called when both `tag` and `signature` are not `null`."
  [player ^RekordboxAnlz$SongStructureTag song-structure]
  (swap! phrase-intervals update player
         (fn [oldval]
           (or oldval (build-phrase-intervals song-structure)))))

(defn clear-song-structure
  "When we have learned of the loss of phrase structure information for
  a player, clear our index that depends on it."
  [player]
  (swap! phrase-intervals dissoc player))

(defn player-phrase-intervals
  "Returns the phrase structure information known for the specified player.
  Will be `nil` if the player has no phrase analysis, or an interval
  tree allowing efficient lookup of the `SongStructureEntry` that
  covers a beat number."
  [player]
  (get @phrase-intervals player))

(defn run-phrase-function
  "Checks whether the phrase trigger has a custom function of the
  specified kind installed and if so runs it with the supplied status
  argument and the track local and global atoms. Returns a tuple of
  the function return value and any thrown exception. If `alert?` is
  `true` the user will be alerted when there is a problem running the
  function."
  [show phrase kind status alert?]
  (let [[show phrase] (latest-show-and-phrase show phrase)
        runtime-info  (su/phrase-runtime-info show phrase)]
    (when-let [expression-fn (get-in runtime-info [:expression-fns kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(expression-fn status {:locals (:expression-locals runtime-info)
                                  :show   show
                                  :phrase phrase} (:expression-globals show)) nil])
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/show-editor-title kind show phrase) ":\n"
                               (get-in phrase [:contents :expressions kind])))
          (when alert? (seesaw/alert (str "<html>Problem running phrase trigger " (name kind) " expression.<br><br>" t)
                                     :title "Exception in Show Phrase Trigger Expression" :type :error))
          [nil t])))))

(defn- paint-phrase-state
    "Draws a representation of the state of the phrase trigger, including whether
  it is enabled and whether it has been selected and is playing."
  [show uuid c ^Graphics2D g]
  (let [w        (double (seesaw/width c))
        h        (double (seesaw/height c))
        outline  (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        show     (latest-show show)
        enabled? (boolean (seq (get-in show [:phrases uuid :enabled])))
        active?  (get-in show [:phrases uuid :tripped])]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (when active? ; Draw the inner filled circle showing the trigger is playing.
      (.setPaint g (if enabled? Color/green Color/lightGray))
      (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))

    ;; Draw the outer circle that reflects the enabled state.
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if enabled? Color/green Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(defn total-bars
  "Returns the sum of the bar sizes of all four sections of a phrase,
  which must be current."
  [{:keys [start-bars loop-bars end-bars fill-bars]
    :or   {start-bars 0
           loop-bars  1
           end-bars   0
           fill-bars  1}}]
  (+ start-bars loop-bars end-bars fill-bars))

(defn repaint-cue-canvases
  "Repaints both the preview canvas for a phrase trigger, and its editor
  canvas if one is open. `show` mist be current."
  [show phrase-or-uuid ^JPanel preview]
  (.repaint preview)
  (when-let [^JPanel canvas (get-in (phrase-runtime-info show phrase-or-uuid) [:cues-editor :wave])]
    (.repaint canvas)))

(defn update-section-boundaries
  "Recalculates the cached information that makes it easier to know
  where each section starts and ends, for the purposes of painting and
  interpreting mouse clicks. Sets the key `:sections` in the runtime
  (unsaved) phrase information to a map with the following content:

  `:start`, `:loop`, `:end`, and `:fill` are tuples of the starting
  bar (inclusive) and ending bar (exclusive) of each section. `:start`
  and `:end` will be missing if those sections have zero length.

  `:total-bars` is the sum of the sizes of all sections in the phrase.

  `:intervals` is an interval map which can be queried using `su/iget`
  to determine which section, if any, falls at the specified bar.

  Also tells any cue canvases open on the phrase to repaint themselves
  to reflect the new boundaries, and updates the spinner constraints
  for any cues in an open cues editor window."
  [show phrase-or-uuid preview]
  (let [uuid                         (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))
        show                         (latest-show show)
        phrase                       (get-in show [:contents :phrases uuid])
        {:keys [start-bars loop-bars
                end-bars fill-bars]} phrase
        start                        (when (pos? start-bars) [0 start-bars])
        loop                         (if-let [[_ start-end] start]
                                       [start-end (+ start-end loop-bars)]
                                       [0 loop-bars])
        end                          (when (pos? end-bars) (let [[_ loop-end] loop] [loop-end (+ loop-end end-bars)]))
        fill                         (if-let [[_ end-end] end]
                                       [end-end (+ end-end fill-bars)]
                                       (let [[_ loop-end] loop] [loop-end (+ loop-end fill-bars)]))]
    (swap-show! show assoc-in [:phrases uuid :sections]
                (merge {:total-bars (+ start-bars loop-bars end-bars fill-bars)
                        :loop       loop
                        :fill       fill
                        :intervals  (as-> util/empty-interval-map $
                                      (if start (apply util/iassoc $ (concat start [:start])) $)
                                      (apply util/iassoc $ (concat loop [:loop]))
                                      (if end (apply util/iassoc $ (concat end [:end])) $)
                                      (apply util/iassoc $ (concat fill [:fill])))}
                       (when start {:start start})
                       (when end {:end end})))
    (repaint-cue-canvases show phrase preview)
    (cues/update-all-cue-spinner-end-models phrase)))

(defn- paint-phrase-preview
  "Draws the compact view of the phrase shown within the Show window
  row, identifying the relative sizes of the sections, and positions
  of cues within them."
  [show uuid c ^Graphics2D g]
  (let [w            (double (seesaw/width c))
        h            (double (seesaw/height c))
        show         (latest-show show)
        phrase       (get-in show [:contents :phrases uuid])
        runtime-info (get-in show [:phrases uuid])
        sections     (:sections runtime-info)
        bars         (:total-bars sections)
        spacing      (su/cue-canvas-preview-bar-spacing bars w)
        stroke       (.getStroke g)
        stripe       (fn [color y [from-bar to-bar]]  ; Paint one of the section stripes.
                       (.setPaint g color)
                       (.fillRect g (+ su/cue-canvas-margin (* from-bar spacing))
                                  y (dec (* (- to-bar from-bar) spacing)) 3))
        fence        (fn [[_from-bar to-bar]]  ; Paint one of the section boundary fences.
                       (let [x (su/cue-canvas-preview-bar-x to-bar spacing)]
                         (.drawLine g x su/cue-canvas-margin x (- h su/cue-canvas-margin))))]

    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (.setPaint g Color/black)
    (.fill g (java.awt.geom.Rectangle2D$Double. 0.0 0.0 w h))

    ;; Paint the section stripes.
    (let [y (- h su/cue-canvas-margin 2)]
      (when-let [start (:start sections)]
        (stripe (su/phrase-section-colors :start) y start))
      (stripe (su/phrase-section-colors :loop) y (:loop sections))
      (when-let [end (:end sections)]
        (stripe (su/phrase-section-colors :end) y end))
      (stripe (su/phrase-section-colors :fill) y (:fill sections)))

    ;; Paint the section boundaries.
    (.setPaint g Color/white)
    (.setStroke g (BasicStroke. 1 BasicStroke/CAP_BUTT BasicStroke/JOIN_ROUND 1.0
                                (float-array [3.0 3.0]) 1.0))
    (when-let [start (:start sections)]
      (fence start))
    (fence (:loop sections))
    (when-let [end (:end sections)]
      (fence end))
    (.setStroke g stroke)

    (when (>= spacing 4)  ; There's enough room to draw bar lines.
      (.setPaint g Color/red)
      (.setStroke g (BasicStroke. 2.0))
      (doseq [bar (range (inc bars))]
        (let [x (su/cue-canvas-preview-bar-x bar spacing)]
          (.drawLine g x su/cue-canvas-margin x (+ su/cue-canvas-margin 4))
          (.drawLine g x (- h su/cue-canvas-margin 8) x (- h su/cue-canvas-margin 4))))
      (.setStroke g stroke))

    (let [beat-spacing (quot spacing 4)]
      (when (>= beat-spacing 4)  ; There is enough room to draw beat lines.
        (.setPaint g Color/white)
        (doseq [bar (range bars)]
          (doseq [beat (range 1 4)]
            (let [x (+ (su/cue-canvas-preview-bar-x bar spacing) (* beat beat-spacing))]
              (.drawLine g x su/cue-canvas-margin x (+ su/cue-canvas-margin 4))
              (.drawLine g x (- h su/cue-canvas-margin 9) x (- h su/cue-canvas-margin 5)))))))

    ;; Paint the cues. Someday we could be fancy like show-cues/paint-preview-cues and figure
    ;; out which ones are actually visible in the clip rect, but I am punting on that for now to get
    ;; things basically working, and then we can see if performance seems to merit it.
    (let [^Graphics2D g2 (.create g)]
      (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER cues/cue-opacity))
      (doseq [cue (vals (get-in phrase [:cues :cues]))]
        (.setPaint g2 (su/hue-to-color (:hue cue) (cues/cue-lightness phrase cue)))
        (.fill g2 (cues/cue-preview-rectangle phrase cue c)))

      (when-let [editor (:cues-editor runtime-info)]
        (let [{:keys [^JScrollPane scroll]} editor]
          (when-not (get-in phrase [:cues :auto-scroll])
            (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER
                                                                   cues/selection-opacity))
            (.setPaint g2 Color/white)
            (.setStroke g2 (java.awt.BasicStroke. 3))
            (let [view-rect  (.getViewRect (.getViewport scroll))
                  start-time (cues/cue-canvas-time-for-x phrase (.-x view-rect))
                  end-time   (cues/cue-canvas-time-for-x phrase (+ (.-x view-rect) (.-width view-rect)))
                  x          (su/cue-canvas-preview-x-for-time c runtime-info start-time)
                  width      (- (su/cue-canvas-preview-x-for-time c runtime-info end-time) x)]
              (.draw g2 (java.awt.geom.Rectangle2D$Double. (double x) 0.0
                                                           (double width) (double (dec (.getHeight c)))))))))
      (.dispose g2))

    ;; Paint the positions of the players that are playing within this phrase trigger.
    (doseq [player (util/players-phrase-uuid-set (:playing-phrases show) uuid)]
      (when-let [time (.getTimeFor util/time-finder player)]
        (let [position   (.getLatestPositionFor util/time-finder player)
              track-beat (.findBeatAtTime (.beatGrid position) time)]
          (when-let [[section first-beat] (first (util/iget (get-in show [:playing-phrases player uuid]) track-beat))]
            (let [beat         (- track-beat first-beat -1)
                  tempo        (.getEffectiveTempo (.getLatestStatusFor util/virtual-cdj player))
                  ms-per-beat  (/ 60000.0 tempo)
                  fraction     (/ (- time (.getTimeWithinTrack (.beatGrid position) track-beat)) ms-per-beat)
                  [looped-beat
                   will-loop]  (su/loop-phrase-trigger-beat runtime-info (+ beat fraction) section)
                  next-section (when will-loop (or (first (first (util/iget (get-in show [:playing-phrases player uuid])
                                                                            (inc track-beat))))
                                                   :start))
                  x            (su/cue-canvas-preview-x-for-beat c runtime-info (long looped-beat) section fraction)]
              (.setPaint g (su/phrase-playback-marker-color section next-section fraction))
              (.fillRect g (dec x) 0 2 (.getHeight c)))))))))

(defn- start-animation-thread
  "Creates a background thread that repaints the preview canvas 30 times
   a second so the player positions will move smoothly while it is
   actively playing. The thread will exit when the phrase trigger
   becomes inactive. If one is already running, this does nothing."
   [show uuid]
   (swap-show! show (fn [current]
                      (if (get-in current [:phrases uuid :animating?])
                        show  ; There is already an animation thread running for this phrase trigger.
                        (let [preview (seesaw/select (get-in show [:phrases uuid :panel]) [:#preview])]
                          (future
                            #_(timbre/info "Animation thread started for phrase trigger" uuid)
                            (try
                              (loop [show (latest-show show)]
                                (when (get-in show [:phrases uuid :tripped])  ; Still active
                                  (seesaw/repaint! preview)
                                  (Thread/sleep 33)
                                  (recur (latest-show show))))
                              (catch Exception e
                                (timbre/error e "Problem running phrase trigger preview animation thread.")))
                            (swap-show! show update-in [:phrases uuid] dissoc :animating?)
                            #_(timbre/info "Animation thread ended for phrase trigger" uuid))
                          (assoc-in show [:phrases uuid :animating?] true))))))

(defn- edit-cues-action
  "Creates the menu action which opens the phrase trigger's cue editor
  window."
  [show phrase panel]
  (seesaw/action :handler (fn [_] (cues/open-cues phrase panel))
                 :name "Edit Phrase Cues"
                 :tip "Set up cues that react to particular sections of the phrase being played."
                 :icon (if (every? empty? (vals (get-in (latest-phrase show phrase) [:cues :cues])))
                         "images/Gear-outline.png"
                         "images/Gear-icon.png")))

(defn- send-playing-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a phrase trigger has been chosen and is now playing.
  `show` and `phrase` must be current."
  [show phrase status]
  (try
    (let [{:keys [message note channel]} phrase]
      (when (#{"Note" "CC"} message)
        (when-let [output (get-chosen-output phrase)]
          (case message
            "Note" (midi/midi-note-on output note 127 (dec channel))
            "CC"   (midi/midi-control output note 127 (dec channel)))))
      (when (= "Custom" message) (run-phrase-function show phrase :playing status false)))
    (catch Exception e
      (timbre/error e "Problem reporting playing phrase trigger."))))

(defn- send-stopped-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a phrase trigger is no longer playing. `show` and
  `phrase` must be current."
  [show phrase status]
  (try
    (let [{:keys [message note channel]} phrase]
      (when (#{"Note" "CC"} message)
        (when-let [output (get-chosen-output phrase)]
          (case message
            "Note" (midi/midi-note-off output note (dec channel))
            "CC"   (midi/midi-control output note 0 (dec channel)))))
      (when (= "Custom" message) (run-phrase-function show phrase :stopped status false)))
    (catch Exception e
      (timbre/error e "Problem reporting stopped phrase trigger."))))

(defn- phrase-missing-expression?
  "Checks whether the expression body of the specified kind is empty for
  the specified phrase trigger."
  [show phrase kind]
  (str/blank? (get-in (latest-phrase show phrase) [:expressions kind])))

(defn phrase-editor-update-fn
  "The function run when an expression has been edited, to update the
  show state and user interface appropriately."
  [kind show phrase gear]
  (when (= kind :setup)  ; Clean up then run the new setup function
    (run-phrase-function show phrase :shutdown nil true)
    (let [runtime-info (su/phrase-runtime-info (latest-show show) phrase)]
      (reset! (:expression-locals runtime-info) {}))
    (run-phrase-function show phrase :setup nil true))
  (su/update-gear-icon phrase gear))


(defn- phrase-editor-actions
  "Creates the popup menu actions corresponding to the available
  expression editors for a given phrase trigger."
  [show phrase panel gear]
  (for [[kind spec] @editors/show-phrase-editors]
    (seesaw/action :handler (fn [_] (editors/show-show-editor
                                     kind (latest-show show) (latest-phrase show phrase) panel
                                     (partial phrase-editor-update-fn kind show phrase gear)))
                   :name (str "Edit " (:title spec))
                   :tip (:tip spec)
                   :icon (if (phrase-missing-expression? show phrase kind)
                           "images/Gear-outline.png"
                           "images/Gear-icon.png"))))

(defn phrase-event-enabled?
  "Checks whether the Message menu is set to something other than None,
  and if Custom, there is a non-empty expression body of the specified
  kind."
  [show phrase kind]
  (let [phrase (latest-phrase show phrase)
        message (:message phrase)]
    (cond
      (= "None" message)
      false

      (= "Custom" message)
      (not (phrase-missing-expression? show phrase kind))

      :else ; Is a MIDI note or CC
      true)))

(defn phrase-random-status-for-simulation
  "Generates an appropriate status object for simulating a phrase
  expression of the specified kind."
  [kind]
  (case kind
    (:playing :tracked :stopped) (su/random-cdj-status)
    :beat               (su/random-beat-and-position nil)
    nil))

(defn- phrase-simulate-actions
  "Creates the actions that simulate events happening to the phrase, for
  testing expressions or creating and testing MIDI mappings in other
  software."
  [show phrase]
  [(seesaw/action :name "Playing"
                  :enabled? (phrase-event-enabled? show phrase :playing)
                  :handler (fn [_] (apply send-playing-messages
                                          (concat (latest-show-and-phrase show phrase)
                                                  [(phrase-random-status-for-simulation :playing)]))))
   (seesaw/action :name "Beat"
                  :enabled? (not (phrase-missing-expression? show phrase :beat))
                  :handler (fn [_] (run-phrase-function
                                    show phrase :beat (phrase-random-status-for-simulation :beat) true)))
   (seesaw/action :name "Tracked Update"
                  :enabled? (not (phrase-missing-expression? show phrase :tracked))
                  :handler (fn [_] (run-phrase-function show phrase :tracked
                                                        (phrase-random-status-for-simulation :tracked) true)))
   (seesaw/action :name "Stopped"
                  :enabled? (phrase-event-enabled? show phrase :stopped)
                  :handler (fn [_] (apply send-stopped-messages
                                          (concat (latest-show-and-phrase show phrase)
                                                  [(phrase-random-status-for-simulation :stopped)]))))])

(defn- phrase-simulate-menu
  "Creates the submenu containing actions that simulate events happening
  to the phrase trigger, for testing expressions or creating and
  testing MIDI mappings in other software."
  [show phrase]
  (seesaw/menu :text "Simulate" :items (phrase-simulate-actions show phrase)))

(defn- remove-uuid
  "Filters a map from players to a map of active phrase trigger UUIDs to
  their beat fit information (such as the :playing-phrases entry in a
  show) to remove the UUID from all the maps. This is used as part of
  cleaning up a show when a phrase trigger has been deleted."
  [player-map uuid]
  (reduce (fn [result [k uuid-map]]
            (assoc result k (dissoc uuid-map uuid)))
          {}
          player-map))

(defn- expunge-deleted-phrase
  "Removes all the items from a show that need to be cleaned up when the
  phrase trigger has been deleted. This function is designed to be
  used in a single swap! call for simplicity and efficiency."
  [show phrase panel]
  (-> show
      (update :phrases dissoc (:uuid phrase))
      (update :panels dissoc panel)
      (update-in [:contents :phrases] dissoc (:uuid phrase))
      (update-in [:contents :phrase-order] (fn [old-order] (filterv (complement #{(:uuid phrase)}) old-order)))
      (update :playing-phrases remove-uuid (:uuid phrase))))

(defn- close-phrase-editors?
  "Tries closing all open expression and cue editors for the phrase
  trigger. If `force?` is true, simply closes them even if they have
  unsaved changes. Otherwise checks whether the user wants to save any
  unsaved changes. Returns truthy if there are none left open the user
  wants to deal with (and in that case also closes any phrase type or
  track mood pickers the user may have opened on the phrase trigger."
  [force? show phrase]
  (let [[show phrase] (latest-show-and-phrase show phrase)
        runtime-info  (phrase-runtime-info show phrase)
        closed        (and
                       (every? (partial editors/close-editor? force?) (vals (:expression-editors runtime-info)))
                       (or (not (:cues-editor runtime-info)) ((get-in runtime-info [:cues-editor :close-fn]) force?)))]
    (when closed
      (when-let [picker (:phrase-type-picker runtime-info)] (.dispose picker))
      (when-let [picker (:track-bank-picker runtime-info)] (.dispose picker)))
    closed))

(defn cleanup-phrase
  "Process the removal of a phrase trigger, either via deletion, or
  because the show is closing. If `force?` is true, any unsaved
  expression editors will simply be closed. Otherwise, they will block
  the phrase trigger removal, which will be indicated by this function
  returning falsey. Run any appropriate custom expressions and send
  configured MIDI messages to reflect the departure of the phrase
  trigger."
  [force? show phrase]
  (when (close-phrase-editors? force? show phrase)
    (let [[show phrase] (latest-show-and-phrase show phrase)
          runtime-info (phrase-runtime-info show phrase)]
      (when (:tripped runtime-info)
        (doseq [[_section cues] (get-in phrase [:cues :cues])
                cue             cues]
          (cues/cleanup-cue true phrase cue))
        (send-stopped-messages show phrase nil))
      (run-phrase-function show phrase :shutdown nil (not force?))
      (su/phrase-removed phrase))
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
                                (swap-show! show expunge-deleted-phrase phrase panel)
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
  (seesaw/selection! (seesaw/select panel [:#enabled]) (or (:enabled phrase) "See Below"))
  (seesaw/selection! (seesaw/select panel [:#players]) (or (:players phrase) "Master")))

(defn parse-phrase-expressions
  "Parses all of the expressions associated with a phrase trigger and
  its cues. `phrase` must be current."
  [show phrase]
  (doseq [[kind expr] (editors/sort-setup-to-front (get-in phrase [:contents :expressions]))]
    (let [editor-info (get @editors/show-phrase-editors kind)]
        (try
          (swap-phrase! show phrase assoc-in [:expression-fns kind]
                        (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                           (editors/show-editor-title kind show phrase)))
              (catch Exception e
                (timbre/error e (str "Problem parsing " (:title editor-info)
                                     " when loading Show. Expression:\n" expr "\n"))
                (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                   "Check the log file for details.")
                              :title "Exception during Clojure evaluation" :type :error)))))
  ;; Parse any custom expressions defined for cues in the phrase.
  (doseq [cue (vals (get-in phrase [:cues :cues]))]
    (cues/compile-cue-expressions phrase cue)))

(defn- build-filter-target
  "Creates a string that can be matched against to filter a phrase
  trigger by text substring, taking into account the custom comment
  assigned to the phrase trigger in the show, if any."
  [comment]
  (str/lower-case (or comment "")))

(defn show-midi-status
  "Set the visibility of the Enabled menu and the text and color
  of its label based on whether the currently-selected MIDI output can
  be found. This function must be called on the Swing Event Update
  thread since it interacts with UI objects."
  [show phrase]
  (try
    (let [[show phrase] (latest-show-and-phrase show phrase)
          panel         (get-in show [:phrases (:uuid phrase) :panel])
          enabled-label (seesaw/select panel [:#enabled-label])
          enabled       (seesaw/select panel [:#enabled])
          players-label (seesaw/select panel [:#players-label])
          players       (seesaw/select panel [:#players])
          output        (get-chosen-output phrase)]
      (if (or output (no-output-chosen phrase))
        (do (seesaw/config! enabled-label :foreground "white")
            (seesaw/value! enabled-label "Enabled:")
            (seesaw/config! enabled  :visible? true)
            (seesaw/config! [players-label players] :visible? (not= "Custom" (:enabled phrase))))
        (do (seesaw/config! enabled-label :foreground "red")
            (seesaw/value! enabled-label "MIDI Output not found.")
            (seesaw/config! [enabled players-label players] :visible? false))))
    (catch Exception e
      (timbre/error e "Problem showing Phrase Trigger MIDI status."))))

(defn- attach-phrase-custom-editor-opener
  "Sets up an action handler so that when a menu is set to Custom,
  if there is not already a custom expression of the appropriate kind
  present, an editor for that expression is automatically opened."
  [show phrase panel menu kind gear]
  (seesaw/listen menu :action-performed
                 (fn [_]
                   (let [choice        (seesaw/selection menu)
                         [show phrase] (latest-show-and-phrase show phrase)]
                     (when (and (= "Custom" choice)
                                (not (:creating phrase))
                                (str/blank?
                                 (get-in phrase [:expressions kind])))
                       (editors/show-show-editor kind show phrase panel
                                                 #(su/update-gear-icon phrase gear)))))))

(defn- attach-phrase-message-visibility-handler
  "Sets up an action handler so that when the message menu is changed,
  the appropriate UI elements are shown or hidden. Also arranges for
  the proper expression editor to be opened if Custom is chosen for
  the message type and that expression is currently empty."
  [show phrase panel gear]
  (let [message-menu    (seesaw/select panel [:#message])
        note-spinner    (seesaw/select panel [:#note])
        label           (seesaw/select panel [:#channel-label])
        channel-spinner (seesaw/select panel [:#channel])]
    (seesaw/listen message-menu
                   :action-performed (fn [_]
                                       (let [choice (seesaw/selection message-menu)]
                                         (if (= "None" choice)
                                           (seesaw/hide! [note-spinner label channel-spinner])
                                           (seesaw/show! [note-spinner label channel-spinner])))))
    (attach-phrase-custom-editor-opener show phrase panel message-menu :playing gear)))

(def phrase-types
  "Defines the types of track phrase which can be enabled for a phrase
  trigger, along with the labels that should be used in checkboxes to
  enable or disable them."
  {:low-intro   "Intro"
   :low-verse-1 "Verse 1"
   :low-verse-2 "Verse 2"
   :low-bridge  "Bridge"
   :low-chorus  "Chorus"
   :low-outro   "Outro"

   :mid-intro   "Intro"
   :mid-verse-1 "Verse 1"
   :mid-verse-2 "Verse 2"
   :mid-verse-3 "Verse 3"
   :mid-verse-4 "Verse 4"
   :mid-verse-5 "Verse 5"
   :mid-verse-6 "Verse 6"
   :mid-bridge  "Bridge"
   :mid-chorus  "Chorus"
   :mid-outro   "Outro"

   :high-intro-1  "Intro 1"
   :high-intro-2  "Intro 2"
   :high-up-1     "Up 1"
   :high-up-2     "Up 2"
   :high-up-3     "Up 3"
   :high-down     "Down"
   :high-chorus-1 "Chorus 1"
   :high-chorus-2 "Chorus 2"
   :high-outro-1  "Outro 1"
   :high-outro-2  "Outro 2"})

(defn- update-phrase-type-label
  "Changes the text of the phrase types label in a phrase trigger row to
  reflect how many are enabled."
  [show phrase types-label]
  (when-not (:enabled-phrase-types (latest-phrase show phrase))
      ;; This must be a newly created phrase, and the set does not yet
      ;; exist. Initialize it to contain all possible phrase types.
      (swap-phrase! show phrase assoc :enabled-phrase-types (set (keys phrase-types))))
  (let [phrase        (latest-phrase show phrase)
        enabled-count (count (:enabled-phrase-types phrase))]
    (seesaw/text! types-label
                  (case enabled-count
                    0  "[None]"
                    26 "[All]"
                    (str "[" enabled-count "]")))))

(defn- build-phrase-type-checkbox
  "Creates a checkbox that reflects and manages the enabled state of a
  phrase type for a phrase trigger."
  [show uuid types-label phrase-type]
  (let [phrase (latest-phrase show {:uuid uuid})]
    (seesaw/checkbox :text (phrase-types phrase-type)
                     :selected? ((:enabled-phrase-types phrase) phrase-type)
                     :listen [:item-state-changed
                              (fn [e]
                                (swap-phrase! show uuid update :enabled-phrase-types
                                              (if (seesaw/value e) conj disj) phrase-type)
                                (update-phrase-type-label show phrase types-label))])))

(defn- build-checkbox-group-button
  "Creates a button that checks or unchecks an entire category of
  checkboxes."
  [selected? checkboxes]
  (seesaw/button :text (if selected? "All" "None")
                 :listen [:action (fn [_]
                                    (doseq [checkbox checkboxes]
                                      (seesaw/value! checkbox selected?)))]))

(defn- show-phrase-type-picker
  "Opens (or brings to the front, if it is already open) a window for
  selecting which phrase types a phrase trigger will activate for."
  [show uuid types-label]
  (if-let [^JFrame frame (:phrase-type-picker (phrase-runtime-info (latest-show show) uuid))]
    (.toFront frame)
    (let [low-intro   (build-phrase-type-checkbox show uuid types-label :low-intro)
          low-verse-1 (build-phrase-type-checkbox show uuid types-label :low-verse-1)
          low-verse-2 (build-phrase-type-checkbox show uuid types-label :low-verse-2)
          low-bridge  (build-phrase-type-checkbox show uuid types-label :low-bridge)
          low-chorus  (build-phrase-type-checkbox show uuid types-label :low-chorus)
          low-outro   (build-phrase-type-checkbox show uuid types-label :low-outro)
          low-types   [low-intro low-verse-1 low-verse-2 low-bridge low-chorus low-outro]

          mid-intro   (build-phrase-type-checkbox show uuid types-label :mid-intro)
          mid-verse-1 (build-phrase-type-checkbox show uuid types-label :mid-verse-1)
          mid-verse-2 (build-phrase-type-checkbox show uuid types-label :mid-verse-2)
          mid-verse-3 (build-phrase-type-checkbox show uuid types-label :mid-verse-3)
          mid-verse-4 (build-phrase-type-checkbox show uuid types-label :mid-verse-4)
          mid-verse-5 (build-phrase-type-checkbox show uuid types-label :mid-verse-5)
          mid-verse-6 (build-phrase-type-checkbox show uuid types-label :mid-verse-6)
          mid-bridge  (build-phrase-type-checkbox show uuid types-label :mid-bridge)
          mid-chorus  (build-phrase-type-checkbox show uuid types-label :mid-chorus)
          mid-outro   (build-phrase-type-checkbox show uuid types-label :mid-outro)
          mid-types   [mid-intro mid-verse-1 mid-verse-2 mid-verse-3 mid-verse-4 mid-verse-5 mid-verse-6
                       mid-bridge mid-chorus mid-outro]

          high-intro-1  (build-phrase-type-checkbox show uuid types-label :high-intro-1)
          high-intro-2  (build-phrase-type-checkbox show uuid types-label :high-intro-2)
          high-up-1     (build-phrase-type-checkbox show uuid types-label :high-up-1)
          high-up-2     (build-phrase-type-checkbox show uuid types-label :high-up-2)
          high-up-3     (build-phrase-type-checkbox show uuid types-label :high-up-3)
          high-down     (build-phrase-type-checkbox show uuid types-label :high-down)
          high-chorus-1 (build-phrase-type-checkbox show uuid types-label :high-chorus-1)
          high-chorus-2 (build-phrase-type-checkbox show uuid types-label :high-chorus-2)
          high-outro-1  (build-phrase-type-checkbox show uuid types-label :high-outro-1)
          high-outro-2  (build-phrase-type-checkbox show uuid types-label :high-outro-2)
          high-types    [high-intro-1 high-intro-2 high-up-1 high-up-2 high-up-3 high-down
                         high-chorus-1 high-chorus-2 high-outro-1 high-outro-2]

          ^JPanel panel (mig/mig-panel
                         :items [["Low Mood Phrases:     "]
                                 ["Mid Mood Phrases:     "]
                                 ["High Mood Phrases:" "wrap"]

                                 [low-intro]
                                 [mid-intro]
                                 [high-intro-1 "wrap"]

                                 [low-verse-1]
                                 [mid-verse-1]
                                 [high-intro-2 "wrap"]

                                 [low-verse-2]
                                 [mid-verse-2]
                                 [high-up-1 "wrap"]

                                 [low-bridge]
                                 [mid-verse-3]
                                 [high-up-2 "wrap"]

                                 [low-chorus]
                                 [mid-verse-4]
                                 [high-up-3 "wrap"]

                                 [low-outro]
                                 [mid-verse-5]
                                 [high-down "wrap"]

                                 [""]
                                 [mid-verse-6]
                                 [high-chorus-1 "wrap"]

                                 [""]
                                 [mid-bridge]
                                 [high-chorus-2 "wrap"]

                                 [""]
                                 [mid-chorus]
                                 [high-outro-1 "wrap"]

                                 [""]
                                 [mid-outro]
                                 [high-outro-2 "wrap unrelated"]

                                 [(build-checkbox-group-button false low-types)]
                                 [(build-checkbox-group-button false mid-types)]
                                 [(build-checkbox-group-button false high-types) "wrap"]

                                 [(build-checkbox-group-button true low-types)]
                                 [(build-checkbox-group-button true mid-types)]
                                 [(build-checkbox-group-button true high-types) "wrap"]])
          phrase       (latest-phrase show {:uuid uuid})
          ^JFrame root (seesaw/frame :title (str "Enabled Phrase Types for " (su/display-title phrase))
                                     :on-close :dispose :content panel
                                     :listen [:window-closing
                                              (fn [_] (swap-phrase-runtime! show uuid dissoc :phrase-type-picker))])]
      (.pack root)
      (.setLocationRelativeTo root (.getParent types-label))
      (swap-phrase-runtime! show uuid assoc :phrase-type-picker root)
      (seesaw/show! root))))

(def track-banks
  "Defines the types of track bank which can be enabled for a phrase
  trigger, along with the labels that should be used in checkboxes to
  enable or disable them."
  {:cool    "Cool"
   :natural "Natural"
   :hot     "Hot"
   :subtle  "Subtle"
   :warm    "Warm"
   :vivid   "Vivid"
   :club-1  "Club 1"
   :club-2  "Club 2"})

(defn- update-track-bank-label
  "Changes the text of the track banks label in a phrase trigger row to
  reflect how many are enabled."
  [show phrase banks-label]
  (when-not (:enabled-track-banks (latest-phrase show phrase))
      ;; This must be a newly created phrase, and the set does not yet
      ;; exist. Initialize it to contain all possible track banks.
      (swap-phrase! show phrase assoc :enabled-track-banks (set (keys track-banks))))
  (let [phrase        (latest-phrase show phrase)
        enabled-count (count (:enabled-track-banks phrase))]
    (seesaw/text! banks-label
                  (case enabled-count
                    0  "[None]"
                    8 "[All]"
                    (str "[" enabled-count "]")))))

(defn- build-track-bank-checkbox
  "Creates a checkbox that reflects and manages the enabled state of a
  track bank for a phrase trigger."
  [show uuid banks-label track-bank]
  (let [phrase (latest-phrase show {:uuid uuid})]
    (seesaw/checkbox :text (track-banks track-bank)
                     :selected? ((:enabled-track-banks phrase) track-bank)
                     :listen [:item-state-changed
                              (fn [e]
                                (swap-phrase! show uuid update :enabled-track-banks
                                              (if (seesaw/value e) conj disj) track-bank)
                                (update-track-bank-label show phrase banks-label))])))

(defn- show-track-bank-picker
  "Opens (or brings to the front, if it is already open) a window for
  selecting which track banks a trigger will activate for."
  [show uuid banks-label]
  (if-let [^JFrame frame (:track-bank-picker (phrase-runtime-info (latest-show show) uuid))]
    (.toFront frame)
    (let [cool    (build-track-bank-checkbox show uuid banks-label :cool)
          natural (build-track-bank-checkbox show uuid banks-label :natural)
          hot     (build-track-bank-checkbox show uuid banks-label :hot)
          subtle  (build-track-bank-checkbox show uuid banks-label :subtle)
          warm    (build-track-bank-checkbox show uuid banks-label :warm)
          vivid   (build-track-bank-checkbox show uuid banks-label :vivid)
          club-1  (build-track-bank-checkbox show uuid banks-label :club-1)
          club-2  (build-track-bank-checkbox show uuid banks-label :club-2)
          banks   [cool natural hot subtle warm vivid club-1 club-2]

          ^JPanel panel (mig/mig-panel
                         :items [[cool]
                                 [natural "wrap"]
                                 [hot]
                                 [subtle "wrap"]
                                 [warm]
                                 [vivid "wrap"]
                                 [club-1]
                                 [club-2 "wrap unrelated"]


                                 [(build-checkbox-group-button false banks) "spanx 2, align center, wrap"]
                                 [(build-checkbox-group-button true banks) "spanx 2, align center, wrap"]])

          ^JFrame root (seesaw/frame :title "Track Banks"
                                     :on-close :dispose :content panel
                                     :listen [:window-closing
                                              (fn [_] (swap-phrase-runtime! show uuid dissoc :track-bank-picker))])]
      (.pack root)
      (.setLocationRelativeTo root (.getParent banks-label))
      (swap-phrase-runtime! show uuid assoc :track-bank-picker root)
      (seesaw/show! root))))

(defn- phrase-panel-constraints
  "Calculates the proper layout constraints for a prhase trigger panel
  to look right at a given window width."
  [width]
  (let [text-width (max 100 (int (/ (- width 142) 4)))
        preview-width (max 890 (- width text-width 100))]
    ["" (str "[]unrelated[][]unrelated[][][fill, " preview-width "]")]))

(defn- handle-preview-move
  "Processes a mouse move over the cue canvas preview component, setting
  the tooltip appropriately depending on the location of cues."
  [phrase ^JPanel preview ^MouseEvent e]
  (let [point  (.getPoint e)
        phrase (latest-phrase phrase)
        cue    (first (filter (fn [cue] (.contains (cues/cue-preview-rectangle phrase cue preview) point))
                              (vals (get-in phrase [:cues :cues]))))]
    (.setToolTipText preview (when cue (or (:comment cue) "Unnamed Cue")))))

(defn- handle-preview-press
  "Processes a mouse press over the cue canvas preview component. If
  there is an editor window open on the track, and it is not in
  auto-scroll mode, centers the editor on the region of the track that
  was clicked."
  [phrase ^JPanel preview ^MouseEvent e]
  (let [point                   (.getPoint e)
        [_ phrase runtime-info] (su/latest-show-and-context phrase)]
    (when-let [editor (:cues-editor runtime-info)]
      (let [{:keys [^JScrollPane scroll]} editor]
        (when-not (get-in runtime-info [:cues :auto-scroll])
          (let [target-time (su/cue-canvas-preview-time-for-x preview runtime-info (.-x point))
                center-x    (cues/cue-canvas-x-for-time phrase target-time)
                scroll-bar  (.getHorizontalScrollBar scroll)]
            (.setValue scroll-bar (- center-x (/ (.getVisibleAmount scroll-bar) 2)))))))))


(defn- handle-preview-drag
  "Processes a mouse drag over the cue canvas preview component. If
  there is an editor window open on the track, and it is not in
  auto-scroll mode, centers the editor on the region of the track that
  was dragged to, and then if the user has dragged up or down, zooms
  out or in by a correspinding amount."
  [phrase ^JPanel preview ^MouseEvent e drag-origin]
  (let [[_ phrase runtime-info] (su/latest-show-and-context phrase)]
    (when-let [editor (:cues-editor runtime-info)]
      (let [{:keys [frame]} editor]
        (when-not (get-in runtime-info [:cues :auto-scroll])
          (when-not (:zoom @drag-origin)
            (swap! drag-origin assoc :zoom (get-in phrase [:cues :zoom])))
          (let [zoom-slider (seesaw/select frame [:#zoom])
                {:keys [^java.awt.Point point zoom]} @drag-origin
                new-zoom (min cues/max-zoom (max 1 (+ zoom (/ (- (.y point) (.y (.getPoint e))) 2))))]
            (seesaw/value! zoom-slider new-zoom))
          (handle-preview-press phrase preview e))))))

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
        preview        (seesaw/canvas :id :preview :paint (partial paint-phrase-preview show uuid))
        outputs        (util/get-midi-outputs)
        gear           (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
        players-label  (seesaw/label :id :players-label :text "Players:")
        players        (seesaw/combobox :id :players
                                        :model ["Master" "On-Air" "Any"]
                                        :selected-item nil  ; So update below saves default
                                        :listen [:item-state-changed
                                                 #(swap-phrase! show uuid assoc :players (seesaw/value %))])
        types-label    (seesaw/label :id :types-label :text "[?]")
        types          (seesaw/button :id :phrase-types :text "Phrase Types"
                                      :listen [:action (fn [_] (show-phrase-type-picker show uuid types-label))])
        banks-label    (seesaw/label :id :banks-label :text "[All]")
        banks          (seesaw/button :id :banks :text "Track Banks"
                                      :listen [:action (fn [_] (show-track-bank-picker show uuid banks-label))])
        min-bars       (seesaw/spinner :id :min-bars :model (seesaw/spinner-model (:min-bars phrase 2) :from 2 :to 64)
                                       :enabled? (:min-bars? phrase)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :min-bars
                                                                              (seesaw/value %))])
        min-bars-cb    (seesaw/checkbox :id :min-bars-cb :text "Min bars:" :selected? (:min-bars? phrase)
                                        :listen [:item-state-changed
                                                 (fn [e]
                                                   (swap-phrase! show uuid assoc :min-bars? (seesaw/value e))
                                                   (seesaw/config! min-bars :enabled? (seesaw/value e)))])
        max-bars       (seesaw/spinner :id :max-bars :model (seesaw/spinner-model (:max-bars phrase 24) :from 1 :to 64)
                                       :enabled? (:max-bars? phrase)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :max-bars
                                                                              (seesaw/value %))])
        max-bars-cb    (seesaw/checkbox :id :max-bars-cb :text "Max bars:" :selected? (:max-bars? phrase)
                                        :listen [:item-state-changed
                                                 (fn [e]
                                                   (swap-phrase! show uuid assoc :max-bars? (seesaw/value e))
                                                   (seesaw/config! max-bars :enabled? (seesaw/value e)))])
        min-bpm        (seesaw/spinner :id :min-bpm :model (seesaw/spinner-model (:min-bpm phrase 60) :from 20 :to 200)
                                       :enabled? (:min-bpm? phrase)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :min-bpm
                                                                              (seesaw/value %))])
        min-bpm-cb     (seesaw/checkbox :id :min-bpm-cb :text "Min BPM:" :selected? (:min-bpm? phrase)
                                        :listen [:item-state-changed
                                                 (fn [e]
                                                   (swap-phrase! show uuid assoc :min-bpm? (seesaw/value e))
                                                   (seesaw/config! min-bpm :enabled? (seesaw/value e)))])
        max-bpm        (seesaw/spinner :id :max-bpm :model (seesaw/spinner-model (:max-bpm phrase 160) :from 20 :to 200)
                                       :enabled? (:max-bpm? phrase)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :max-bpm
                                                                              (seesaw/value %))])
        max-bpm-cb     (seesaw/checkbox :id :max-bpm-cb :text "Max BPM:" :selected? (:max-bpm? phrase)
                                        :listen [:item-state-changed
                                                 (fn [e]
                                                   (swap-phrase! show uuid assoc :max-bpm? (seesaw/value e))
                                                   (seesaw/config! max-bpm :enabled? (seesaw/value e)))])
        weight-label   (seesaw/label :id :weight-label :text "Weight:")
        weight         (seesaw/spinner :id :weight :model (seesaw/spinner-model (:weight phrase 1) :from 1 :to 1000)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :weight
                                                                              (seesaw/value %))])
        gap-label      (seesaw/label :text "")
        panel          (mig/mig-panel
                        :constraints (phrase-panel-constraints (.getWidth ^JFrame (:frame show)))
                        :items [[comment-field "spanx 5, growx, pushx"]
                                [preview "gap unrelated, spany 3, grow, push, wrap"]

                                ["Section sizes (bars):" "spany 2"]
                                ["Start:" "gap unrelated"]
                                [(seesaw/spinner :id :start
                                                 :model (seesaw/spinner-model (:start-bars phrase 1)
                                                                              :from 0 :to 64)
                                                 :listen [:state-changed
                                                          #(do (swap-phrase! show uuid assoc :start-bars
                                                                             (seesaw/value %))
                                                               (update-section-boundaries show uuid preview))])]
                                ["Loop:" "gap unrelated"]
                                [(seesaw/spinner :id :loop
                                                 :model (seesaw/spinner-model (or (:loop-bars phrase) 2)
                                                                              :from 1 :to 64)
                                                 :listen [:state-changed
                                                          #(do (swap-phrase! show uuid assoc :loop-bars
                                                                             (seesaw/value %))
                                                               (update-section-boundaries show uuid preview))])
                                 "wrap"]

                                ["End:" "gap unrelated"]
                                [(seesaw/spinner :id :end
                                                 :model (seesaw/spinner-model 1 :from 0 :to 64)
                                                 :listen [:state-changed
                                                          #(do (swap-phrase! show uuid assoc :end-bars
                                                                             (seesaw/value %))
                                                               (update-section-boundaries show uuid preview))])]
                                ["Fill:" "gap unrelated"]
                                [(seesaw/spinner :id :fill
                                                 :model (seesaw/spinner-model 2 :from 1 :to 64)
                                                 :listen [:state-changed
                                                          #(do (swap-phrase! show uuid assoc :fill-bars
                                                                             (seesaw/value %))
                                                               (update-section-boundaries show uuid preview))])
                                 "wrap unrelated"]

                                [gear "spanx, split"]

                                ["MIDI Output:" "gap unrelated"]
                                [(seesaw/combobox :id :outputs
                                                  :model (let [chosen (:midi-device phrase)]
                                                           (concat outputs
                                                                   ;; Preserve existing selection even if now missing.
                                                                   (when (and chosen (not ((set outputs) chosen)))
                                                                     [chosen])
                                                                   ;; Offer escape hatch if no MIDI devices available.
                                                                   (when (and chosen (empty? outputs))
                                                                     [nil])))
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

                                [(seesaw/label :id :channel-label :text "Channel:") "gap unrelated, hidemode 3"]
                                [(seesaw/spinner :id :channel
                                                 :model (seesaw/spinner-model (or (:channel phrase) 1)
                                                                              :from 1 :to 16)
                                                 :listen [:state-changed
                                                          #(swap-phrase! show uuid assoc :channel (seesaw/value %))])
                                 "hidemode 3"]

                                ["Solo:" "gap 30"]
                                [(seesaw/combobox :id :solo :model ["Global" "Show" "Blend"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-phrase! show uuid assoc :solo (seesaw/selection %))])]

                                [(seesaw/label :id :enabled-label :text "Enabled:") "gap 15"]
                                [(seesaw/combobox :id :enabled
                                                  :model ["See Below" "Custom"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           (fn [e]
                                                             (swap-phrase! show uuid assoc :enabled (seesaw/value e))
                                                             (let [visible? (not= "Custom" (seesaw/value e))]
                                                               (seesaw/config! [players players-label
                                                                                types types-label banks banks-label
                                                                                min-bars-cb min-bars
                                                                                max-bars-cb max-bars
                                                                                min-bpm-cb min-bpm max-bpm-cb max-bpm
                                                                                weight-label weight gap-label]
                                                                               :visible? visible?)
                                                               (when-not visible?
                                                                 (let [show         (latest-show show)
                                                                       runtime-info (phrase-runtime-info show uuid)]
                                                                   (when-let [picker (:phrase-type-picker runtime-info)]
                                                                     (.dispose picker))
                                                                   (when-let [picker (:track-bank-picker runtime-info)]
                                                                     (.dispose picker))))))])
                                 "hidemode 3"]
                                [players-label "gap 15"]
                                [players "hidemode 2, wrap unrelated"]

                                [types "spanx 5, split 4, hidemode 3"]
                                [types-label "gap unrelated, hidemode 3"]
                                [banks "gap unrelated, hidemode 3"]
                                [banks-label "gap unrelated, hidemode 3"]
                                [min-bars-cb "spanx, split, hidemode 3"]
                                [min-bars "hidemode 3"]
                                [max-bars-cb "gap 15, hidemode 3"]
                                [max-bars "hidemode 3"]
                                [min-bpm-cb "gap 30, hidemode 3"]
                                [min-bpm "hidemode 3"]
                                [max-bpm-cb "gap 15, hidemode 3"]
                                [max-bpm "hidemode 3"]
                                [weight-label "gap 30, hidemode 3"]
                                [weight "hidemode 3"]
                                [gap-label "growx, pushx, hidemode 3"]])

        phrase (merge phrase {:uuid     uuid
                              :creating true}) ; Suppress popup expression editors when reopening a show.

        popup-fn (fn [^MouseEvent _e]  ; Creates the popup menu for the gear button or right-clicking in the phrase.
                   (concat [(edit-cues-action show phrase panel) (seesaw/separator)]
                           (when (seq (su/gear-content phrase))
                             [(su/view-expressions-in-report-action show phrase)])
                           (phrase-editor-actions show phrase panel gear)
                           [(seesaw/separator) (phrase-simulate-menu show phrase) (su/inspect-action phrase)
                            (seesaw/separator)]
                           [(seesaw/separator) (delete-phrase-action show phrase panel)]))

        drag-origin (atom nil)]

    (swap-show! show assoc-in [:contents :phrases uuid] phrase)  ; information about the phrase trigger that gets saved.
    (swap-show! show assoc-in [:phrases uuid]  ; Runtime (unsaved) information about the phrase trigger.
                {:entered           {} ; Map from player # to sets of UUIDs of cues that have been entered.
                 :expression-locals (atom {})
                 :filter            (build-filter-target (:comment phrase))
                 :panel             panel
                 :preview-canvas    preview})
        (swap-show! show assoc-in [:panels panel] uuid)  ; Tracks all the rows in the show window.
        (su/phrase-added show uuid)

    ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
    ;; or right click on the gear button. Also set the proper initial gear appearance.
    (seesaw/config! [panel gear] :popup popup-fn)
    (seesaw/listen gear :mouse-pressed (fn [e]
                                         (let [popup (seesaw/popup :items (popup-fn e))]
                                           (util/show-popup-from-button gear popup e))))
    (su/update-gear-icon phrase gear)

    (seesaw/listen preview
                   :mouse-moved (fn [e] (handle-preview-move phrase preview e))
                   :mouse-pressed (fn [^MouseEvent e]
                                    (reset! drag-origin {:point (.getPoint e)})
                                    (handle-preview-press phrase preview e))
                   :mouse-dragged (fn [e] (handle-preview-drag phrase preview e drag-origin)))

    (seesaw/listen (seesaw/select panel [:#outputs])
                   :item-state-changed (fn [_] (seesaw/invoke-later (show-midi-status show phrase))))
    (attach-phrase-message-visibility-handler show phrase panel gear)
    (attach-phrase-custom-editor-opener show phrase panel (seesaw/select panel [:#enabled]) :enabled gear)

    ;; Establish the saved or initial settings of the UI elements, which will also record them for the
    ;; future, and adjust the interface, thanks to the already-configured item changed listeners.
    (update-phrase-comboboxes phrase panel)

    ;; In case this is the inital creation of the phrase trigger, record the defaulted values of the numeric inputs.
    ;; This will have no effect if they were loaded.
    (swap-phrase! show phrase assoc :start-bars (seesaw/value (seesaw/select panel [:#start])))
    (swap-phrase! show phrase assoc :loop-bars (seesaw/value (seesaw/select panel [:#loop])))
    (swap-phrase! show phrase assoc :end-bars (seesaw/value (seesaw/select panel [:#end])))
    (swap-phrase! show phrase assoc :fill-bars (seesaw/value (seesaw/select panel [:#fill])))
    (update-section-boundaries show uuid preview)  ; We now have the information needed to do this.
    (swap-phrase! show phrase assoc :note (seesaw/value (seesaw/select panel [:#note])))
    (swap-phrase! show phrase assoc :channel (seesaw/value (seesaw/select panel [:#channel])))
    (swap-phrase! show phrase assoc :min-bars (seesaw/value (seesaw/select panel [:#min-bars])))
    (swap-phrase! show phrase assoc :max-bars (seesaw/value (seesaw/select panel [:#max-bars])))
    (swap-phrase! show phrase assoc :min-bpm (seesaw/value (seesaw/select panel [:#min-bpm])))
    (swap-phrase! show phrase assoc :max-bpm (seesaw/value (seesaw/select panel [:#max-bpm])))
    (swap-phrase! show phrase assoc :weight (seesaw/value (seesaw/select panel [:#weight])))

    ;; Similarly, initialize the phrase type and track bank tracking information and labels.
    (update-phrase-type-label show phrase types-label)
    (update-track-bank-label show phrase banks-label)

    (cues/build-cues phrase)
    (parse-phrase-expressions show phrase)

    ;; We are done creating the phrase trigger, so arm the menu listeners to automatically pop up expression editors
    ;; when the user requests a custom message.
    (swap-phrase! show phrase dissoc :creating)))

(defn create-phrase-panels
  "Creates all the panels that represent phrase triggers in the show."
  [show]
  ;; First resolve any phrase UUID conflicts which result from opening a show based on a copy of another open show.
  (util/doseq-indexed idx [uuid (get-in (latest-show show) [:contents :phrase-order])]
    (when (su/show-from-phrase uuid) ; This is a UUID conflict.
      (swap-show! show (fn [current]
                         (let [new-uuid (UUID/randomUUID)
                               phrase   (get-in current [:contents :phrases uuid])]
                           (-> current
                               (assoc-in [:contents :phrase-order idx] new-uuid)
                               (assoc-in [:contents :phrases new-uuid] (assoc phrase :uuid new-uuid))
                               (update-in [:contents :phrases] dissoc uuid)))))))
  ;; Then build the GUI elements and runtime information about the resolved phrases in the newly-opened show.
  (doseq [uuid (get-in (latest-show show) [:contents :phrase-order])]
    (create-phrase-panel show uuid)))

(defn scroll-to-phrase
  "Makes sure the specified phrase trigger is visible (it has just been
  created), or give the user a warning that the current filters have
  hidden it. If the comment field is empty, focuses on it to encourage
  the user to add one."
  ([show phrase-or-uuid]
   (scroll-to-phrase show phrase-or-uuid false))
  ([show phrase-or-uuid select-comment]
   (let [show   (latest-show show)
         uuid   (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))
         phrase (get-in show [:contents :phrases uuid])
         tracks (seesaw/select (:frame show) [:#tracks])]
     (if (some #(= uuid %) (:visible-phrases show))
       (seesaw/invoke-later
        (let [^JPanel panel (get-in show [:phrases uuid :panel])]
          (seesaw/scroll! tracks :to (.getBounds panel))
          (when select-comment
            (let [^JTextComponent comment-field (seesaw/select panel [:#comment])]
              (seesaw/request-focus! comment-field)
              (.selectAll comment-field)))))
       (seesaw/alert (:frame show)
                     (str "The phrase trigger “" (su/display-title phrase) "” is currently hidden by your filters.\r\n"
                          "To continue working with it, you will need to adjust the filters.")
                     :title "Can't Scroll to Hidden Phrase Trigger" :type :info)))))

(defn next-phrase-number
  "Calculates the phrase number to assign a newly-created phrase as a
  draft comment."
  [show]
  (let [show    (latest-show show)
        phrases (vals (get-in show [:contents :phrases]))]
    (->> phrases
         (map :comment)
         (map (fn [s]
                (if-let [[_ n] (re-matches #"(?i)\s*phrase\s*(\d+)\s*" (or s ""))]
                  (inc (Long/parseLong n))
                  0)))
         (apply max (count phrases)))))

(defn new-phrase
  "Adds a new phrase trigger to the show."
  [show]
  (let [show (latest-show show)
        uuid (UUID/randomUUID)]
    (create-phrase-panel show uuid)
    (swap-show! show update-in [:contents :phrase-order] (fnil conj []) uuid)
    (let [comment-field (seesaw/select (get-in (latest-show show) [:phrases uuid :panel]) [:#comment])]
      (seesaw/value! comment-field (str "Phrase " (next-phrase-number show)))
      (su/update-row-visibility show))
    (scroll-to-phrase show uuid true)))

(defn sort-phrases
  "Sorts the phrase triggers by their comments. `show` must be current."
  [show]
  (let [show (latest-show show)
        sorted (sort-by (juxt :comment :uuid) (vals (get-in show [:contents :phrases])))]
    (swap-show! show assoc-in[:contents :phrase-order] (mapv :uuid sorted))
    (su/update-row-visibility show)))

(defn resize-phrase-panels
  "Called when the show window has resized, to put appropriate
  constraints on the columns of the phrase panels."
  [panels width]
  (let [constraints (phrase-panel-constraints width)]
    (doseq [[^JPanel panel signature-or-uuid] panels]
      (when (instance? UUID signature-or-uuid)  ; It's a phrase trigger panel.
        (seesaw/config! panel :constraints constraints)
        (.revalidate panel)))))

(defn update-playback-position
  "Updates the position and color of the playback position bar for the
specified player in the cue preview canvas for any phrase triggers
that are active for the specified player, and if they have open Cues
editor windows, in their cue canvases as well."
  [show ^Long player]
  (when-let [^TrackPositionUpdate _position (when (util/online?) (.getLatestPositionFor util/time-finder player))]
    (doseq [uuid (keys (get-in show [:playing-phrases player]))]
      (let [runtime-info (get-in show [:phrases uuid])]
        (seesaw/repaint! (seesaw/select (:panel runtime-info) [:#preview]))))))

(defn- repaint-phrase-state
  "Causes the phrase trigger state indicator tor redraw itself to reflect
  a change. Also update any cue state indicators if there is a cues
  editor open for the track."
  [show phrase]
  (let [runtime-info (su/phrase-runtime-info show phrase)
        panel  (:panel runtime-info)]
    (seesaw/repaint! (seesaw/select panel [:#state]))
    (cues/repaint-all-cue-states phrase)))

(defn all-phrase-triggers-playing
  "Given the a snapshot of the map of open shows, returns tuples
  of [player UUID] for all phrase triggers currently playing in any
  show."
  [shows]
  (mapcat (fn [show]
            (mapcat (fn [[player triggers-map]]
                      (for [uuid (keys triggers-map)] [player uuid]))
                    (:playing-phrases show)))
          (vals shows)))

(defn- no-longer-playing
  "Reacts to the fact that the specified player is no longer playing the
  track it had been. Must be passed a current view of the show and the
  snapshot of the formerly playing phrase trigger uuids. If we learned
  about the stoppage from a status update, it will be in `status`. If
  `phrase-changed` is true, this is a new instance of the trigger even
  if the set of players playing it would not suggest that."
  [show player old-playing status phrase-changed]
  (doseq [uuid old-playing]
    (when (or phrase-changed (empty? (util/players-phrase-uuid-set (:playing-phrases show) uuid)))
      ;; No other player is still playing the phrase trigger.
      (let [phrase           (get-in show [:contents :phrases uuid])
            old-runtime-info (get-in show [:last :phrases uuid])]
        (doseq [cue-uuid (reduce set/union (vals (:entered old-runtime-info)))]
          ;; All cues we had been playing are now ended.
          (cues/send-cue-messages phrase old-runtime-info cue-uuid :ended status)
          (cues/send-cue-messages phrase old-runtime-info cue-uuid :exited status)
          (cues/repaint-cue phrase cue-uuid)
          (cues/repaint-cue-states phrase cue-uuid))
        (send-stopped-messages show phrase status)
        (repaint-phrase-state show phrase)
        (seesaw/invoke-later (su/update-row-visibility show)))))
  (update-playback-position show player))

(defn run-beat-functions
  "Invoked by the our track position listener when a new beat packet
  has been received. Runs all the beat expressions for phrase triggers
  that are active for the appropriate player in all shows."
  [^Beat beat ^TrackPositionUpdate position]
  (doseq [show (vals (su/get-open-shows))]
    (doseq [uuid (keys (get-in show [:playing-phrases (.getDeviceNumber beat)]))]
      (run-phrase-function show (get-in show [:contents :phrases uuid]) :beat [beat position] false))))

(defn now-playing
  "Reacts to the fact that the specified player is now playing a phrase.
  Must be passed a current view of the show and the now-playing phrase
  trigger uuids. If we learned about the playback from a status
  update, it will be in `status`. If `phrase-changed` is true, this is
  a new instance of the trigger even if the set of players playing it
  would not suggest that. `on-beat` indicates whether this is in
  response to a beat."
  [show player playing status phrase-changed on-beat?]
  (doseq [uuid playing]
    (when (or phrase-changed (= #{player} (util/players-phrase-uuid-set (:playing-phrases show) uuid)))
      ;; This is the first player playing the phrase trigger.
      (let [phrase (get-in (latest-show show) [:contents :phrases uuid])
            runtime-info (su/phrase-runtime-info show phrase)]
        (start-animation-thread show uuid)
        (send-playing-messages show phrase status)
        (doseq [uuid (reduce set/union (vals (:entered runtime-info)))]  ; Report start for any cues we are on.
          (cues/send-cue-messages phrase runtime-info uuid (if on-beat? :started-on-beat :started-late) status)
          (cues/repaint-cue phrase uuid)
          (cues/repaint-cue-states phrase uuid))
        (repaint-phrase-state show phrase)
        (seesaw/invoke-later (su/update-row-visibility show)))))
  (update-playback-position show player))

(defn- weight-if-eligible
  "Checks whether the supplied phrase trigger map is eligible to run for
  the phrase that is playing, and if so returns the weight it should
  be assigned when randomly choosing between solo triggers. This is
  called in the context of a `swap!` operation with the most current
  values of `show` and `phrase-trigger`."
  [show status context phrase-trigger]
  (case (:enabled phrase-trigger)

    "Custom"
    (let [result (run-phrase-function show phrase-trigger :custom-enabled status false)]
      (if (number? result)
        (let [weight (long (min (math/round result) 1000))]
          (when (pos? weight) weight))
        (when result 1)))

    "See Below"
    (when (and
           ((:enabled-track-banks phrase-trigger) (:bank context))
           ((:enabled-phrase-types phrase-trigger) (:phrase-type context))
           (or (not (:max-bars? phrase-trigger)) (<= (:bars context) (:max-bars phrase-trigger)))
           (or (not (:min-bars? phrase-trigger)) (>= (:bars context) (:min-bars phrase-trigger)))
           (or (not (:max-bpm? phrase-trigger)) (<= (.getEffectiveTempo status) (:max-bpm phrase-trigger)))
           (or (not (:min-bpm? phrase-trigger)) (>= (.getEffectiveTempo status) (:min-bpm phrase-trigger)))
           (case (:players phrase-trigger)
             "Any"    true
             "Master" (.isTempoMaster status)
             "On-Air" (.isOnAir status)))
      (:weight phrase-trigger))))

(defn current-phrase-trigger-weight
  "Given the current state of the shows (with updated phrase trigger
  enabled/weight information) and a phrase trigger's UUID, checks the
  weight with which it is enabled for a player, which will be `nil` if
  not enabled."
  [shows uuid player]
  (let [file         (su/show-file-from-phrase uuid)
        runtime-info (get-in shows [file :phrases uuid])]
    (get-in runtime-info [:enabled player])))

(defn run-lottery
  "Given the current state of the shows (with updated phraes trigger
  enabled/weight information) and a list of phrase trigger maps,
  determines which are eligible to run for the phrase that is
  starting, and their weights, and randomly chooses one guided by the
  weights. Returns the UUID of the winner, if any."
  [shows candidates player]
  (let [contenders (filter identity (map (fn [candidate]
                                           (when-let [weight (current-phrase-trigger-weight
                                                              shows (:uuid candidate) player)]
                                             [(:uuid candidate) weight]))
                                         candidates))
        lottery    (reduce (fn [{:keys [total intervals]} [uuid weight]]
                             {:total     (+ total weight)
                              :intervals (util/iassoc intervals total (+ total weight) uuid)})
                           {:total     0
                            :intervals util/empty-interval-map}
                           contenders)]
    (timbre/debug "lottery:" lottery "candidates:" (count candidates))
    (first (util/iget (:intervals lottery) (rand-int (:total lottery))))))

(defn- run-global-lottery
  "Finds all global solo phrase triggers in all open shows, checks
  whether they are eligible to run for the phrase that just started,
  and picks one by weight, returning its UUID."
  [shows player]
  (let [candidates (filter (fn [phrase-trigger] (= "Global" (:solo phrase-trigger)))
                           (apply concat (map (fn [show] (vals (get-in show [:contents :phrases])))
                                              (vals shows))))]
    (run-lottery shows candidates player)))

(defn- global-survivor
  "Sees if there is a currently-running global solo phrase trigger which
  remains enabled in the current state. Returns truthy if so."
  [shows player old-phrase new-phrase]
  (let [[running-player uuid] (when (= old-phrase new-phrase)
                                (first (filter (fn [[_ uuid]]
                                                 (let [file           (su/show-file-from-phrase uuid)
                                                       phrase-trigger (get-in shows [file :contents :phrases uuid])]
                                                   (= "Global" (:solo phrase-trigger))))
                                               (all-phrase-triggers-playing shows))))]
    (and uuid (or (not= player running-player) (current-phrase-trigger-weight shows uuid player)))))

(defn- show-survivor
  "Sees if there is a currently-running per-show solo phrase trigger
  which remains enabled in the current state. Returns truthy if so."
  [shows show player old-phrase new-phrase]
  (let [[running-player uuid] (when (= old-phrase new-phrase)
                                (first (filter (fn [[_ uuid]]
                                                 (let [phrase-trigger (get-in show [:contents :phrases uuid])]
                                                   (= "Show" (:solo phrase-trigger))))
                                               (mapcat (fn [[player triggers-map]]
                                                         (for [uuid (keys triggers-map)] [player uuid]))
                                                       (:playing-phrases show)))))]
    (and uuid (or (not= player running-player) (current-phrase-trigger-weight shows uuid player)))))

(defn align-sections
  "Given the starting and ending beats of the non-fill section of an
  actual phrase, figures out which sections of our phrase trigger fit,
  and where their boundaries belong."
  [start end phrase-trigger]
  (let [phrase-size (- end start)
        start-size  (* (:start-bars phrase-trigger) 4)]
    (if (<= phrase-size start-size) ; There's only room for the start section.
      (util/iassoc util/empty-interval-map start end [:start start])
      (let [end-size (* (:end-bars phrase-trigger) 4)]
        (if (<= phrase-size (+ start-size end-size)) ; Only room for start and end
          (-> util/empty-interval-map
              (util/iassoc start (+ start start-size) [:start start])
              (util/iassoc (+ start start-size) end [:end (+ start start-size)]))
          (let [loop-size (- phrase-size start-size end-size) ; All non-fill sections fit.
                loop-beat (+ start start-size)
                end-beat  (+ loop-beat loop-size)]
            (-> util/empty-interval-map
                (util/iassoc start loop-beat [:start start])
                (util/iassoc loop-beat end-beat [:loop loop-beat])
                (util/iassoc end-beat end [:end end-beat]))))))))

(defn- align-trigger-to-phrase
  "Figures out how to shrink or stretch a phrase trigger to line up with
  the sections of the actual phrase that it has matched. Returns an
  interval map that translates track beat numbers to tuples of
  [section starting-beat]"
  [player phrase-trigger ^RekordboxAnlz$SongStructureEntry phrase]
  (let [grid        (.getLatestBeatGridFor (org.deepsymmetry.beatlink.data.BeatGridFinder/getInstance) player)
        [start end] (beat-range player phrase grid)]
    (if (zero? (.fill phrase))
      (align-sections start end phrase-trigger)
      (let [fill (.beatFill phrase)]
        (-> (align-sections start fill phrase-trigger)
            (util/iassoc fill end [:fill fill]))))))

(defn trigger-context
  "Returns a map holding the information that can be used to check
  whether a phrase trigger is eligible to run for a phrase that is
  starting."
  [player ^RekordboxAnlz$SongStructureEntry new-phrase ^CdjStatus status]
  (let [grid        (.getLatestBeatGridFor (org.deepsymmetry.beatlink.data.BeatGridFinder/getInstance) player)
        [start end] (beat-range player new-phrase grid)]
    {:bars        (quot (- end start) 4)
     :tempo       (.getEffectiveTempo status)
     :master      (.isTempoMaster status)
     :bank        (util/track-bank-keyword (.. new-phrase _parent _parent))
     :phrase-type (util/phrase-type-keyword new-phrase)}))

(defn- remove-no-longer-eligible
  "Given the updated show state, examines each running phrase trigger,
  re-evaluating its Enabled rules. Returns the UUIDs of all phrase
  triggers which should continue to run."
  [shows running player old-phrase new-phrase]
  (let [survivors (filter (fn [uuid] (current-phrase-trigger-weight shows uuid player))
                          (when (= old-phrase new-phrase) (keys running)))]
    (select-keys running survivors)))

(defn- newly-eligible-blend-triggers
  "Give the updated show state, and the set of UUIDs of phrase triggers
  from the show which are already running, returns a map of UUID to
  aligned phrase trigger for each non-solo trigger in the show which
  should be started."
  [running-uuids shows show player new-phrase]
  (reduce-kv (fn [result uuid phrase-trigger]
               (if (and (= "Blend" (:solo phrase-trigger))
                        (not (running-uuids uuid))
                        (current-phrase-trigger-weight shows uuid player))
                 (assoc result uuid (align-trigger-to-phrase player phrase-trigger new-phrase))
                 result))
             {}
             (get-in show [:contents :phrases])))

(defn- phrases-now-running-for-show
  "Updates the map of UUIDs to aligned phrase triggers for a show based
  on the current state. Filters out any phrase triggers which have
  become ineligible given the current track state, and if this means a
  previous solo winner has ended, conducts a new lottery to see
  which (if any) solo phrase trigger can take its place, both at the
  global and per-show levels."
  [running shows show player old-phrase new-phrase unblocked new-global]
  (when (and unblocked new-phrase)  ; Phrase triggers can run at all.
    (let [survivors (remove-no-longer-eligible shows running player old-phrase new-phrase)
          uuid-set  (set (keys survivors))]
      (merge survivors
             (when-let [our-global-solo (get-in show [:contents :phrases new-global])]
               ;; The new winning global solo trigger is in this show.
               {new-global (align-trigger-to-phrase player our-global-solo new-phrase)})

             (when-not (show-survivor shows show player old-phrase new-phrase)
               (when-let [new-solo-uuid (run-lottery shows
                                                     (filter (fn [phrase-trigger] (= "Show" (:solo phrase-trigger)))
                                                             (vals (get-in show [:contents :phrases])))
                                                     player)]
                 {new-solo-uuid (align-trigger-to-phrase player (get-in show [:contents :phrases new-solo-uuid])
                                                         new-phrase)}))

             (newly-eligible-blend-triggers uuid-set shows show player new-phrase)))))

(defn- update-enabled-runtime-info-for-show
  "Given the current runtime info map for a show (`phrases`), the full
  show map, the state of a player, the phrase being played on it, the
  current player status, the phrase trigger context information map
  built by `trigger-context`, and an indication of whether there is no
  show track blockng phrase triggers from running, updates the
  `:enabled` entry for each phrase trigger to reflect whether that
  phrase trigger is currently enabled for the player, and if so, what
  its weight should be in a lottery."
  [phrases show player status context unblocked]
  (reduce-kv (fn [info-map uuid runtime-info]
               (assoc info-map uuid
                      (let [weight (when unblocked (weight-if-eligible show status context
                                                                       (get-in show [:contents :phrases uuid])))]
                        (if weight
                          (assoc-in runtime-info [:enabled player] weight)
                          (update runtime-info :enabled dissoc player)))))
             {}
             phrases))

(defn- update-phrase-enabled-states
  "Given the current state of a player and the phrase being played on
  it, determines whether each phrase trigger is enabled for that
  player, and if so, what its weight should be in a lottery. Updates
  the `:enabled` map in each phrase trigger's runtime info
  accordingly. Performed in the context of a `swap!` operation on the
  open shows map, and starts out by capturing the prior state of each
  show, so we can detect when things have changed."
  [shows player status context unblocked]
  (reduce-kv (fn [shows k show]
               (assoc shows k
                      (update (su/capture-current-state show) :phrases
                              update-enabled-runtime-info-for-show show player status context unblocked)))
             {}
             shows))

(defn- entered-cues-for-player
  "Given an updated show state, a phrase trigger UUID and its
  runtime-info map, a player number, and the current playback position
  of that player, return the set of that phrase trigger's cues which
  the player is positioned within."
  [show uuid runtime-info player position]
  (or
   (when position
     (when-let [[section first-beat] (first (util/iget (get-in show [:playing-phrases player uuid])
                                                       (.beatNumber position)))]
       (let [beat-in-section (- (.beatNumber position) first-beat -1)]
         (util/iget (get-in runtime-info [:cues :intervals section])
                    (first (su/loop-phrase-trigger-beat runtime-info beat-in-section section))))))
   #{}))

(defn- update-phrase-tripped-states
  "Given the updated state of a show (including the phrase triggers
  which are now running in it), update each phrase trigger's
  `:tripped` flag for easy access to that information at rendering
  time, and update all their cues' `:entered` states."
  [show player ^TrackPositionUpdate position]
  (let [playing-phrases (vals (:playing-phrases show))]
    (update show :phrases
            (fn [phrases]
              (reduce-kv (fn [phrases uuid runtime-info]
                           (let [tripped (boolean (some (fn [player-map]
                                                          (contains? player-map uuid)) playing-phrases))]
                             (-> phrases
                                 (assoc-in [uuid :tripped] tripped)
                                 (update-in [uuid :entered]
                                            (fn [entered]
                                              (when tripped
                                                (assoc entered player (entered-cues-for-player
                                                                       show uuid runtime-info player position))))))))
                         phrases
                         phrases)))))

(defn- track-unblocked?
  "Makes sure that any show which contains the track playing on the
  player has marked that track as allowing phrase triggers to run."
  [player]
  (let [signature (.getLatestSignatureFor util/signature-finder player)]
    (not-any? (fn [show]
                (when-let [track (get-in show [:tracks signature])]
                  (not (get-in track [:contents :phrase-unlocked]))))
              (vals (su/get-open-shows)))))

(defn- update-running-phrase-triggers
  "Figure out which phrase triggers should now be running across all
  shows given a state update caused by a player. If the playing phrase
  has not changed, grandfather in any still-enabled playing phrase
  triggers, but if that doesn't leave a solo trigger running, perform
  a new lottery using their weights to add one, both at the global
  level and in each show."
  [state player position]
  (let [old-phrase (get-in state [:last :current-phrase player])
        new-phrase (get-in state [:current-phrase player])
        status     (.getLatestStatusFor util/virtual-cdj player)
        context    (when new-phrase (trigger-context player new-phrase status))
        unblocked  (track-unblocked? player)
        updated    (swap! @#'su/open-shows
                          (fn [current]
                            (let [current (update-phrase-enabled-states current player status context unblocked)
                                  global (when (and unblocked new-phrase
                                                    (not (global-survivor current player old-phrase new-phrase)))
                                           (run-global-lottery current player))]
                              (reduce-kv (fn [shows k show]
                                           (let [updated-show (update-in show [:playing-phrases player]
                                                                         phrases-now-running-for-show current show
                                                                         player old-phrase new-phrase unblocked global)]
                                             (assoc shows k
                                                    (update-phrase-tripped-states updated-show player position))))
                                         {}
                                         current))))]
    updated))

(defn- show-open-and-player-playing
  "Checks that there is at least one show file open, and that the player we are
  responding to is considered to be playing, before we do work to analyze phrase
  information."
  [player]
  (when (seq (vals (su/get-open-shows)))
    (get-in @phrase-state [:playing player])))

(defn- update-player-phrase
  "When we know a player has reached a new beat, update the
  state map appropriately based on the player number and
  beat number. Called within `swap!` so simply returns the new value."
  [state player beat]
  (let [new-phrase (when (show-open-and-player-playing player)
                     (when-let [intervals (get @phrase-intervals player)]
                       (first (util/iget intervals beat))))]
    (assoc-in state [:current-phrase player] new-phrase)))

(defn- past-beat?
  "Checks if it has been long enough after a beat packet was received to
  trust a status-packet's beat number. `state` is a current snapshot
  of our phrase state global, which includes information about when we
  received the most recent beat from the specified `player`, and
  `status` is a status update from the player that we are considering
  whether we should trust. If the beat happened long enough ago, or
  did not represent a beat one higher than the one found in the status
  packet we just received, then we will process the status packet."
  [state player ^CdjStatus status]
  (let [[timestamp last-beat] (get-in state [:last-beat player])]
    (or (not timestamp)
        (> (- (.getTimestamp status) timestamp) su/min-beat-distance)
        (not= (.getBeatNumber status) (dec last-beat)))))

(defn- update-phrase-if-past-beat
  "Checks if it has been long enough after a beat packet was received to
  update the player's current playing phrase based on a status-packet's
  beat number. This check needs to be made because we have seen status
  packets that players send within a few milliseconds after a beat
  sometimes still contain the old beat number, even though they have
  updated their beat-within-bar number. So this function leaves the
  player's phrase state unchanged if a beat happened too recently.
  However, if the status update indicates the player is not playing,
  we always remove any formerly playing phrase."
  [state player ^CdjStatus status]
  (if (get-in state [:playing player])
    (if (past-beat? state player status)
      (update-player-phrase state player (.getBeatNumber status))
      state)
    (update state :current-phrase dissoc player)))

(defn- send-phrase-changes
  "Compares the old and new sets of entered cues for all phrase
  triggers, and sends the appropriate messages and updates the UI as
  needed. Must be called with a show containing a last-state snapshot.
  Either `status` or `beat` and `position` will have non-nil values,
  and if it is `beat` and `position`, this means any cue that was
  entered was entered right on the beat."
  [state show player ^CdjStatus status ^Beat beat ^TrackPositionUpdate position]
  (let [old-phrase (get-in state [:last :current-phrase player])
        phrase     (get-in state [:current-phrase player])]
    (if (not= old-phrase phrase)
      (do
        #_(timbre/info "Player" player "phrase changed" (if beat "on-beat" "off-beat") "from" old-phrase "to" phrase)
        (no-longer-playing show player (keys (get-in show [:last :playing-phrases player])) status true)
        (now-playing show player (keys (get-in show [:playing-phrases player])) status true (some? beat)))
      (let [was-playing (set (keys (get-in show [:last :playing-phrases player])))
            playing     (set (keys (get-in show [:playing-phrases player])))]
        (no-longer-playing show player (set/difference was-playing playing) status false)
        (now-playing show player (set/difference playing was-playing) status false (some? beat)))))

  (doseq [[uuid _sections] (get-in show [:playing-phrases player])]
    (let [runtime-info     (get-in show [:phrases uuid])
          old-runtime-info (get-in show [:last :phrases uuid])
          phrase           (get-in show [:contents :phrases uuid])
          entered          (reduce set/union (vals (:entered runtime-info)))
          old-entered      (reduce set/union (vals (:entered old-runtime-info)))]

      ;; Report cues we have newly entered.
      (when (:tripped old-runtime-info)  ; Otherwise we already reported them above, because the phrase just activated.
        (doseq [cue-uuid (set/difference entered old-entered)]
          (when-let [cue (su/find-cue phrase cue-uuid)]  ; Make sure it wasn't deleted.
            (let [event (if (and beat (= :start cue) (.beatNumber position)) :started-on-beat :started-late)
                  status-or-beat (if (= event :started-on-beat) [beat position] (or status beat))]
              (cues/send-cue-messages phrase runtime-info cue :entered status-or-beat)
              (cues/send-cue-messages phrase runtime-info cue event status-or-beat))
            (cues/repaint-cue phrase cue)
            (cues/repaint-cue-states phrase cue))))

      ;; Report cues we have newly exited.
      (doseq [cue-uuid (set/difference old-entered entered)]
        (when-let [cue (su/find-cue phrase cue-uuid)]
          (cues/send-cue-messages phrase old-runtime-info cue :ended (or status beat))
          (cues/send-cue-messages phrase old-runtime-info cue :exited (or status beat))
          (cues/repaint-cue phrase cue)
          (cues/repaint-cue-states phrase cue)))

      ;; If we received a beat, run the basic beat expression for cues that we were already inside.
      (when beat
        (doseq [cue-uuid (set/intersection old-entered entered)]
          (when-let [cue (su/find-cue phrase cue-uuid)]
            (cues/run-cue-function phrase cue :beat [beat position] false))))

      ;; If the set of entered cues has changed, update the UI appropriately.
      (when (not= entered old-entered)
        (cues/repaint-all-cue-states phrase)
        ;; If we are showing only entered cues, update cue row visibility.
        (when (get-in phrase [:cues :entered-only])
        (seesaw/invoke-later (cues/update-cue-visibility phrase))))))

  ;; Repaint the status indicators of any phrases whose enabled state has changed
  (doseq [[uuid phrase] (get-in show [:contents :phrases])]
    (let [now-disabled (empty? (get-in show [:phrases uuid :enabled]))
          was-disabled (empty? (get-in show [:last :phrases uuid :enabled]))]
      (when (not= now-disabled was-disabled)
        (seesaw/invoke-later (repaint-phrase-state show phrase)))))

  ;; Run the tracked update expression for the running phrases, if appropriate.
  (when status
    (doseq [uuid (keys (get-in show [:playing-phrases player]))]
      (run-phrase-function show (get-in show [:contents :phrases uuid]) :tracked status false)))

  (update-playback-position show player))

(defn- deliver-beat-events
  "Called when a beat has been received and updated the show status.
  Compares the new status with the snapshot of the last status, runs
  any relevant expressions, and updates any needed UI elements. `show`
  and must be the just-updated values, with a valid snapshot the
  show's `:last` key."
  [state player ^Beat beat ^TrackPositionUpdate position]
  (let [updated (update-running-phrase-triggers state player position)]
    (future
      (try
        (doseq [show (vals updated)]
          (send-phrase-changes state show player nil beat position))
        (catch Throwable t
          (timbre/info t "Problem delivering phrase beat events."))))))

(defn- deliver-change-events
  "Called when a status packet has updated the phrase status.
  Compares the new status with the snapshot of the last status, runs
  any relevant expressions, and updates any needed UI elements. `show`
  must be the just-updated value, with a valid snapshot in the `:last`
  key. `player` is the player number, in case `status` is `nil`
  because we are reacting to a signature change rather than a status
  packet. Finally, even if nothing has changed, if there is a status
  packet the Tracked Update Expressions for any active phrase triggers
  for the player will be called with it."
  [state player ^CdjStatus status]
  (let [updated (update-running-phrase-triggers state player (.getLatestPositionFor util/time-finder player))]
    (future
      (try
        (doseq [show (vals updated)]
          (send-phrase-changes state show player status nil nil))
        (catch Throwable t
          (timbre/info t "Problem delivering phrase status events."))))))

(defn- update-player-beat
  "Adjusts our phrase state to reflect a new beat packet received from a
  player."
  [^Beat beat ^TrackPositionUpdate position]
  (let [player  (.getDeviceNumber beat)
        updated (swap! phrase-state
                       (fn [state]
                         (-> state
                             capture-current-state
                             (assoc-in [:playing player] ; In case beat arrives before playing status.
                                       ;; But ignore the beat as a playing indicator if DJ is actually cueing.
                                       (not (get-in state [:cueing player])))
                             (assoc-in [:last-beat player] [(.getTimestamp beat) (.beatNumber position)])
                             (update-player-phrase player (.beatNumber position)))))]
    (deliver-beat-events updated player beat position)))

(defonce ^{:private true
          :doc "Keeps track of the position listeners we have created for
  device numbers that have been seen on the network, so we don't duplicate
  them if devices come and go."}
  listeners
  (atom {}))

(defn- add-position-listener
  "Adds a track position listener for the specified player to the time
  finder, making very sure this happens only once. This is used to
  provide us with augmented information whenever this player reports a
  beat, so we can use it to determine which phrase is current, and
  which cues to activate and deactivate, and make it available to the
  phrase triggers' Beat expression."
  [player]
  (let [updated (swap! listeners update player
                           (fn [listener]
                             (or listener
                                 (proxy [org.deepsymmetry.beatlink.data.TrackPositionBeatListener] []
                                   (movementChanged [position])  ; Nothing to do.
                                   (newBeat [^Beat beat ^TrackPositionUpdate position]
                                     (try
                                       (update-player-beat beat position)
                                       (catch Exception e
                                         (timbre/error e "Problem updating status for phrase beat.")))
                                     (future
                                       (try
                                         (run-beat-functions beat position)
                                         (catch Exception e
                                           (timbre/error e "Problem reporting phrase beat.")))))))))
        listener (get updated player)]
    (.addTrackPositionListener util/time-finder player listener)))

(defonce ^{:private true
           :doc "Watches for players to come and go so we can register the
  track position listeners we need."}
  device-listener (reify DeviceAnnouncementListener
                    (deviceFound [_this announcement]
                      (let [player (.getDeviceNumber announcement)]
                        (when (< player 16)  ; Only care about players, not mixers, rekordbox, etc.
                          (add-position-listener player))))
                    (deviceLost [_this _announcement])))  ; Nothing to do.

(.addDeviceAnnouncementListener util/device-finder device-listener)

(when (.isRunning util/device-finder)
  (doseq [player (util/visible-player-numbers)]
    (add-position-listener player)))

(defn- update-phrase-status
  "Updates our phrase state information based on the receipt of a new status packet."
  [^CdjStatus status]
  (let [player (.getDeviceNumber status)
        updated    (swap! phrase-state
                          (fn [state]
                            (-> state
                                capture-current-state
                                (assoc-in [:playing player] (.isPlaying status))
                                (assoc-in [:on-air player] (.isOnAir status))
                                (assoc-in [:master player] (.isTempoMaster status))
                                (assoc-in [:cueing player] (boolean (su/cueing-states (.getPlayState1 status))))
                                (update-phrase-if-past-beat player status))))]
    (when (past-beat? updated player status)
      (deliver-change-events updated player status))))

(defonce ^{:private true
           :doc "Responds appropriately to device status updates."}
  update-listener (reify DeviceUpdateListener
                    (received [_this status]
                      (try
                        (when (and (.isRunning util/signature-finder)  ; Ignore packets when not yet fully online.
                                   (instance? CdjStatus status))  ; We only want CDJ information.
                          (update-phrase-status status))
                        (catch Exception e
                                    (timbre/error e "Problem responding to Player status packet."))))))

(.addUpdateListener util/virtual-cdj update-listener)
