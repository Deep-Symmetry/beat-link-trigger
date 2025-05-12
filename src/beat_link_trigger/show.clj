(ns beat-link-trigger.show
  "A higher-level interface for creating cues within the beat grids of
  efficiently recognized tracks, with support for offline loading of
  tracks and editing of cues."
  (:require [beat-link-trigger.util :as util]
            [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.help :as help]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.track-loader :as loader]
            [beat-link-trigger.show-cues :as cues]
            [beat-link-trigger.show-phrases :as phrases]
            [beat-link-trigger.show-util :as su :refer [latest-show latest-track latest-show-and-track find-cue
                                                        swap-show! swap-track! swap-signature!]]
            [beat-link-trigger.simulator :as sim]
            [clojure.java.browse]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [inspector-jay.core :as inspector]
            [me.raynes.fs :as fs]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.icon]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.awt Color Font Graphics2D Rectangle RenderingHints]
           [java.awt.event ItemEvent MouseEvent WindowEvent]
           [java.io File]
           [java.lang.ref SoftReference]
           [java.nio.file Files FileSystem FileSystems Path StandardOpenOption]
           [javax.swing JComponent JFrame JMenu JMenuBar JPanel JScrollPane]
           [org.apache.maven.artifact.versioning DefaultArtifactVersion]
           [org.deepsymmetry.beatlink Beat CdjStatus CdjStatus$TrackSourceSlot
            DeviceAnnouncement DeviceAnnouncementListener DeviceUpdateListener DeviceFinder
            LifecycleListener VirtualCdj]
           [org.deepsymmetry.beatlink.data AlbumArt AnalysisTagFinder AnalysisTagListener
            BeatGrid CueList DataReference MetadataFinder SearchableItem
            SignatureFinder SignatureListener SignatureUpdate TrackMetadata TrackPositionUpdate
            WaveformDetail WaveformDetailComponent WaveformFinder WaveformFinder$WaveformStyle
            WaveformPreview WaveformPreviewComponent]
           [org.deepsymmetry.beatlink.dbserver Message]
           [org.deepsymmetry.cratedigger Database]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz RekordboxPdb$ArtworkRow RekordboxPdb$TrackRow
            RekordboxAnlz$SongStructureTag RekordboxAnlz$TaggedSection]
           [io.kaitai.struct RandomAccessFileKaitaiStream ByteBufferKaitaiStream]
           [jiconfont.icons.font_awesome FontAwesome]
           [jiconfont.swing IconFontSwing]))

(def ^DeviceFinder device-finder
  "A convenient reference to the [Beat Link
  `DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
  singleton."
  (DeviceFinder/getInstance))

(def ^VirtualCdj virtual-cdj
  "A convenient reference to the [Beat Link
  `VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
  singleton."
  (VirtualCdj/getInstance))

(def ^MetadataFinder metadata-finder
  "A convenient reference to the [Beat Link
  `MetadataFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html)
  singleton."
  (MetadataFinder/getInstance))

(def ^org.deepsymmetry.beatlink.data.ArtFinder art-finder
  "A convenient reference to the [Beat Link
  `ArtFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/ArtFinder.html)
  singleton."
  (org.deepsymmetry.beatlink.data.ArtFinder/getInstance))

(def ^org.deepsymmetry.beatlink.data.BeatGridFinder beatgrid-finder
  "A convenient reference to the [Beat Link
  `BeatGridFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/BeatGridFinder.html)
  singleton."
  (org.deepsymmetry.beatlink.data.BeatGridFinder/getInstance))

(def ^SignatureFinder signature-finder
  "A convenient reference to the [Beat Link
  `SingatureFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/SignatureFinder.html)
  singleton."
  (SignatureFinder/getInstance))

(def ^AnalysisTagFinder analysis-finder
  "A convenient reference to the [Beat Link
  `AnalysisTagFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/AnalysisTagFinder.html)
  singleton."
  (AnalysisTagFinder/getInstance))

(def ^WaveformFinder waveform-finder
  "A convenient reference to the [Beat Link
  `WaveformFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/WaveformFinder.html)
  singleton."
  (WaveformFinder/getInstance))

(defonce ^{:private true
           :doc "Holds copied track content for pasting into other tracks."}
  copied-track-content (atom nil))

;;; This section defines a bunch of utility functions.

(defn- write-message-path
  "Writes the supplied Message to the specified path, truncating any previously existing file."
  [^Message message path]
  (with-open [channel (java.nio.channels.FileChannel/open path (into-array [StandardOpenOption/WRITE
                                                                            StandardOpenOption/CREATE_NEW]))]
    (.write message channel)))

(defn- track-present?
  "Checks whether there is already a track with the specified signature
  in the Show."
  [show signature]
  (Files/exists (su/build-filesystem-path (:filesystem (latest-show show)) "tracks" signature)
                (make-array java.nio.file.LinkOption 0)))

(defn- run-global-function
  "Checks whether the show has a custom function of the specified kind
  installed, and if so runs it with the supplied status argument, and
  the show and its globals. Returns a tuple of the function return
  value and any thrown exception. If `alert?` is `true` the user will
  be alerted when there is a problem running the function."
  [show kind status alert?]
  (let [show (latest-show show)]
    (when-let [expression-fn (get-in show [:expression-fns kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(expression-fn status {:show show} (:expression-globals show)) nil])
        (catch Throwable t
          (timbre/error t "Problem running show global " kind " expression,"
                        (get-in show [:contents :expressions kind]))
          (when alert? (seesaw/alert (str "<html>Problem running show global " (name kind) " expression.<br><br>" t)
                                     :title "Exception in Show Expression" :type :error))
          [nil t])))))

(defn run-track-function
  "Checks whether the track has a custom function of the specified kind
  installed and if so runs it with the supplied status argument and
  the track local and global atoms. Returns a tuple of the function
  return value and any thrown exception. If `alert?` is `true` the
  user will be alerted when there is a problem running the function."
  [track kind status alert?]
  (let [[show track] (latest-show-and-track track)]
    (when-let [expression-fn (get-in track [:expression-fns kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(expression-fn status {:locals (:expression-locals track)
                                  :show   show
                                  :track  track} (:expression-globals show)) nil])
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/show-editor-title kind show track) ":\n"
                               (get-in track [:contents :expressions kind])))
          (when alert? (seesaw/alert (str "<html>Problem running track " (name kind) " expression.<br><br>" t)
                                     :title "Exception in Show Track Expression" :type :error))
          [nil t])))))

(defn- repaint-track-states
  "Causes the two track state indicators to redraw themselves to reflect
  a change in state. Also update any cue state indicators if there is
  a cues editor open for the track."
  [show signature]
  (let [track (get-in (latest-show show) [:tracks signature])
        panel (:panel track)]
    (seesaw/repaint! (seesaw/select panel [:#loaded-state]))
    (seesaw/repaint! (seesaw/select panel [:#playing-state]))
    (cues/repaint-all-cue-states track)))

(defn repaint-all-track-states
  "Causes the track state indicators for all tracks in a show to redraw
  themselves to reflect a change in state."
  [show]
  (doseq [[signature _] (:tracks (latest-show show))]
    (repaint-track-states show signature)))

(defn- update-track-enabled
  "Updates either the track or default enabled filter stored result to
  the value passed in. Currently we only store results at the track
  level, but maybe someday shows themselves will be enabled/disabled
  too."
  [show track enabled?]
  (let [ks    [:tracks (:signature track) :expression-results :enabled]
        shows (swap-show! show (fn [show]
                                 (-> show
                                     (assoc-in [:last :enabled] (get-in show ks))
                                     (assoc-in ks enabled?))))]
    (when (not= enabled? (get-in shows [(:file show) :last :enabled]))
      (repaint-track-states show (:signature track)))))

(defn- run-custom-enabled
  "Invokes the custom enabled filter assigned to a track (or to the
  show, if the track is set to Default and the show is set to Custom),
  if any, recording the result in the track data. `show` and `track`
  must be up-to-date."
  [show track status]
  (if (= "Custom" (get-in track [:contents :enabled]))
    (update-track-enabled show track (boolean (first (run-track-function track :enabled status false))))
    (when (and (= "Default" (get-in track [:contents :enabled])) (= "Custom" (get-in show [:contents :enabled])))
      (update-track-enabled show track (boolean (first (run-global-function show :enabled status false)))))))

(defn- describe-disabled-reason
  "Returns a text description of why import from a player is disabled
  based on an associated track signature, or `nil` if it is not
  disabled, given the show map and a possibly-`nil` track signature."
  [show signature]
  (cond
    (nil? signature)                " (no track signature)"
    (track-present? show signature) " (already imported)"
    :else                           nil))

(defn- handle-preview-move
  "Processes a mouse move over the softly-held waveform preview
  component, setting the tooltip appropriately depending on the
  location of cues."
  [track soft-preview preview-loader ^MouseEvent e]
  (let [point                             (.getPoint e)
        track                             (latest-track track)
        ^WaveformPreviewComponent preview (preview-loader)
        cue                               (first (filter (fn [cue]
                                                           (.contains (cues/cue-preview-rectangle track cue preview)
                                                                      point))
                                                         (vals (get-in track [:contents :cues :cues]))))]
    (.setToolTipText ^JComponent soft-preview
                     (or (when cue (cues/build-tooltip cue track nil nil nil))
                         (.toolTipText preview point)))))

(defn- handle-preview-press
  "Processes a mouse press over the softly-held waveform preview
  component. If there is an editor window open on the track, and it is
  not in auto-scroll mode, centers the editor on the region of the
  track that was clicked."
  [track preview-loader ^MouseEvent e]
  (let [point (.getPoint e)
        track (latest-track track)]
    (when-let [editor (:cues-editor track)]
      (let [{:keys [^WaveformDetailComponent wave ^JScrollPane scroll]} editor]
        (when-not (.getAutoScroll wave)
          (let [^WaveformPreviewComponent preview (preview-loader)
                target-time                       (.getTimeForX preview (.-x point))
                center-x                          (.millisecondsToX wave target-time)
                scroll-bar                        (.getHorizontalScrollBar scroll)]
            (.setValue scroll-bar (- center-x (/ (.getVisibleAmount scroll-bar) 2)))))))))


(defn- handle-preview-drag
  "Processes a mouse drag over the softly-held waveform preview
  component. If there is an editor window open on the track, and it
  is not in auto-scroll mode, centers the editor on the region of the
  track that was dragged to, and then if the user has dragged up or
  down, zooms out or in by a correspinding amount."
  [track preview-loader ^MouseEvent e drag-origin]
  (let [track (latest-track track)]
    (when-let [editor (:cues-editor track)]
      (let [{:keys [^WaveformDetailComponent wave frame]} editor]
        (when-not (.getAutoScroll wave)
          (when-not (:zoom @drag-origin)
            (swap! drag-origin assoc :zoom (.getScale wave)))
          (let [zoom-slider (seesaw/select frame [:#zoom])
                {:keys [^java.awt.Point point zoom]} @drag-origin
                new-zoom (min cues/max-zoom (max 1 (+ zoom (/ (- (.y point) (.y (.getPoint e))) 2))))]
            (seesaw/value! zoom-slider new-zoom))
          (handle-preview-press track preview-loader e))))))

;;; This next section implements the Show window and Track rows.

(defn- update-playing-text
  "Formats the text describing the players that are playing a track, and
  sets it into the proper UI label."
  [show signature playing]
  (let [text         (if (empty? playing)
                       "--"
                       (str/join ", " (sort playing)))
        playing-label (seesaw/select (get-in (latest-show show) [:tracks signature :panel]) [:#playing])]
    (seesaw/invoke-later
     (seesaw/config! playing-label :text text))))

(defn- update-playback-position
  "Updates the position and color of the playback position bar for the
  specified player in the track preview and, if there is an open Cues
  editor window, in its waveform detail."
  [show signature ^Long player]
  (let [[interpolated-time playing?] (if-let [simulator (sim/for-player player)]
                                       [(:time simulator) (:playing simulator)]
                                       (when-let [^TrackPositionUpdate position
                                                  (when (.isRunning util/time-finder)
                                                    (.getLatestPositionFor util/time-finder player))]
                                         [(.getTimeFor util/time-finder player) (.playing position)]))]
    (when-let [preview-loader (get-in show [:tracks signature :preview])]
        (when-let [^WaveformPreviewComponent preview (preview-loader)]
          (.setPlaybackState preview player interpolated-time playing?)))
      (when-let [cues-editor (get-in (latest-show show) [:tracks signature :cues-editor])]
        (.setPlaybackState ^WaveformDetailComponent (:wave cues-editor) player interpolated-time playing?))))

(defn- send-loaded-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a track is now loaded. `track` must be current."
  [track]
  (try
    (let [{:keys [loaded-message loaded-note loaded-channel]} (:contents track)]
      (when (#{"Note" "CC"} loaded-message)
        (when-let [output (su/get-chosen-output track)]
          (case loaded-message
            "Note" (midi/midi-note-on output loaded-note 127 (dec loaded-channel))
            "CC"   (midi/midi-control output loaded-note 127 (dec loaded-channel)))))
      (when (= "Custom" loaded-message) (run-track-function track :loaded nil false)))
    (catch Exception e
      (timbre/error e "Problem reporting loaded track."))))

(defn- send-playing-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a track is now playing. `track` must be current."
  [track status]
  (try
    (let [{:keys [playing-message playing-note playing-channel]} (:contents track)]
      (when (#{"Note" "CC"} playing-message)
        (when-let [output (su/get-chosen-output track)]
          (case playing-message
            "Note" (midi/midi-note-on output playing-note 127 (dec playing-channel))
            "CC"   (midi/midi-control output playing-note 127 (dec playing-channel)))))
      (when (= "Custom" playing-message) (run-track-function track :playing status false)))
    (catch Exception e
      (timbre/error e "Problem reporting playing track."))))

(defn- send-stopped-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a track is no longer playing. `track` must be current."
  [track status]
  (try
    (let [{:keys [playing-message playing-note playing-channel]} (:contents track)]
      (when (#{"Note" "CC"} playing-message)
        (when-let [output (su/get-chosen-output track)]
          (case playing-message
            "Note" (midi/midi-note-off output playing-note (dec playing-channel))
            "CC"   (midi/midi-control output playing-note 0 (dec playing-channel)))))
      (when (= "Custom" playing-message) (run-track-function track :stopped status false)))
    (catch Exception e
      (timbre/error e "Problem reporting stopped track."))))

(defn- no-longer-playing
  "Reacts to the fact that the specified player is no longer playing the
  specified track. If this left the track with no player playing it,
  run the track's Stopped expression, if there is one. Must be passed
  a current view of the show, the last snapshot version of the track,
  and an indication of whether the track's tripped state has changed.
  If we learned about the stoppage from a status update, it will be in
  `status`."
  [show player track status tripped-changed]
  (let [signature   (:signature track)
        now-playing (util/players-signature-set (:playing show) signature)]
    (when (or tripped-changed (empty? now-playing))
      (when (:tripped track)  ; This tells us it was formerly tripped, because we are run on the last state.
        (doseq [uuid (reduce set/union (vals (:entered track)))]  ; All cues we had been playing are now ended.
          (cues/send-cue-messages track track uuid :ended status)
          (cues/repaint-cue track uuid)
          (cues/repaint-cue-states track uuid))
        (send-stopped-messages track status))
      (repaint-track-states show signature))
    (update-playing-text show signature now-playing)
    (update-playback-position show signature player)))

(defn- update-loaded-text
  "Formats the text describing the players that have a track loaded, and
  sets it into the proper UI label."
  [show signature loaded]
  (let [text         (if (empty? loaded)
                       "--"
                       (str/join ", " (sort loaded)))
        loaded-label (seesaw/select (get-in (latest-show show) [:tracks signature :panel]) [:#players])]
    (seesaw/invoke-later
     (seesaw/config! loaded-label :text text))))

(defn- send-unloaded-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a track is no longer loaded. `track` must be current."
  [track]
  (try
    (let [{:keys [loaded-message loaded-note loaded-channel]} (:contents track)]
      (when (#{"Note" "CC"} loaded-message)
        (when-let [output (su/get-chosen-output track)]
          (case loaded-message
            "Note" (midi/midi-note-off output loaded-note (dec loaded-channel))
            "CC"   (midi/midi-control output loaded-note 0 (dec loaded-channel)))))
      (when (= "Custom" loaded-message) (run-track-function track :unloaded nil false)))
    (catch Exception e
      (timbre/error e "Problem reporting unloaded track."))))

(defn- no-longer-loaded
  "Reacts to the fact that the specified player no longer has the
  specified track loaded. If this leaves track not loaded in any
  player, run the track's Unloaded expression, if there is one. Must
  be passed a current view of the show and the previous track state."
  [show player track tripped-changed]
  (when-let [listener (get-in track [:listeners player])]
    (.removeTrackPositionListener util/time-finder listener)
    (swap-track! track update :listeners dissoc player))
  (let [signature  (:signature track)
        now-loaded (util/players-signature-set (:loaded show) signature)]
    (when (or tripped-changed (empty? now-loaded))
      (when (:tripped track)  ; This tells us it was formerly tripped, because we are run on the last state.
        (doseq [uuid (reduce set/union (vals (:entered track)))]  ; All cues we had been playing are now exited.
          (cues/send-cue-messages track track uuid :exited nil)
          (cues/repaint-cue track uuid)
          (cues/repaint-cue-states track uuid))
        (seesaw/invoke-later (cues/update-cue-visibility track))
        (send-unloaded-messages track))
      (repaint-track-states show signature))
    (update-loaded-text show signature now-loaded)
    (update-playing-text show signature (util/players-signature-set (:playing show) signature))

    (when-let [preview-loader (get-in show [:tracks signature :preview])]
      (when-let [preview (preview-loader)]
        #_(timbre/info "clearing for player" player)
        (.clearPlaybackState ^WaveformPreviewComponent preview player)))
    (when-let [cues-editor (get-in show [:tracks signature :cues-editor])]
      (.clearPlaybackState ^WaveformDetailComponent (:wave cues-editor) player))))

(declare update-track-beat)

(defn- add-position-listener
  "Adds a track position listener for the specified player to the time
  finder, making very sure this happens only once. This is used to
  provide us with augmented information whenever this player reports a
  beat, so we can use it to determine which cues to activate and
  deactivate, and make it available to the track's Beat expression."
  [show player track]
  (let [shows (swap-track! track update-in [:listeners player]
                           (fn [listener]
                             (or listener
                                 (proxy [org.deepsymmetry.beatlink.data.TrackPositionBeatListener] []
                                   (movementChanged [position])
                                   (newBeat [^Beat beat ^TrackPositionUpdate position]
                                     (let [[show track] (latest-show-and-track track)]
                                       (update-track-beat show track beat position)
                                       (future
                                         (try
                                           (when (su/enabled? show track)
                                             (run-track-function track :beat [beat position] false))
                                           (catch Exception e
                                             (timbre/error e "Problem reporting track beat."))))))))))
        listener (get-in shows [(:file show) :tracks (:signature track) :listeners player])]
    (.addTrackPositionListener util/time-finder player listener)))

(defn- now-loaded
  "Reacts to the fact that the specified player now has the specified
  track loaded. If this is the first player to load the track, run the
  track's Loaded expression, if there is one. Must be passed a current
  view of the show and track."
  [show player track tripped-changed]
  (add-position-listener show player track)
  (let [signature  (:signature track)
        now-loaded (util/players-signature-set (:loaded show) signature)]
    (when (or tripped-changed (= #{player} now-loaded))  ; This is the first player to load the track.
      (when (:tripped track)
        (send-loaded-messages track)
        ;; Report entry to all cues we've been sitting on.
        (doseq [uuid (reduce set/union (vals (:entered track)))]
          (cues/send-cue-messages track track uuid :entered nil)
          (cues/repaint-cue track uuid)
          (cues/repaint-cue-states track uuid))
        (seesaw/invoke-later (cues/update-cue-visibility track)))
      (repaint-track-states show signature))
    (update-loaded-text show signature now-loaded)
    (update-playback-position show signature player)))

(defn- now-playing
  "Reacts to the fact that the specified player is now playing the
  specified track. If this is the first player playing the track, run
  the track's Started expression, if there is one. Must be passed a
  current view of the show and track. If we learned about the playback
  from a status update, it will be in `status`."
  [show player track status tripped-changed]
  (let [signature   (:signature track)
        now-playing (util/players-signature-set (:playing show) signature)]
    (when (or tripped-changed (= #{player} now-playing))  ; This is the first player to play the track.
      (when (:tripped track)
        (send-playing-messages track status)
        ;; Report late start for any cues we were sitting on.
        (doseq [uuid (reduce set/union (vals (:entered track)))]
          (cues/send-cue-messages track track uuid :started-late status)
          (cues/repaint-cue track uuid)
          (cues/repaint-cue-states track uuid)))
      (repaint-track-states show signature))
    (update-playing-text show signature now-playing)
    (update-playback-position show signature player)))

(defn trip?
  "Checks whether the track should fire its deferred loaded and playing
  expressions, given that it has just become enabled. Called within
  `swap!` so `show` and `track` can be trusted to have current
  values."
  [show track]
  (or ((set (vals (:loaded show))) (:signature track))
      ((set (vals (:playing show))) (:signature track))))

(defn- update-track-trip-state
  "As part of `update-show-status` below, set the `:tripped` state of
  the track appropriately based on the current show configuration.
  Called within `swap!` so simply returns the new value. If `track` is
  `nil`, returns `show` unmodified."
  [show track]
  (if track
    (assoc-in show [:tracks (:signature track) :tripped]
              (boolean (and (su/enabled? show track) (trip? show track))))
    show))

(defn- update-cue-entered-state
  "As part of `update-show-status` below, update the `:entered` set of
  the track's cues appropriately based on the current track
  configuration, player number, and beat number. Called within `swap!`
  so simply returns the new value. If `track` is `nil`, returns `show`
  unmodified."
  [show track player beat]
  (if track
    (assoc-in show [:tracks (:signature track) :entered player]
              (util/iget (get-in track [:cues :intervals]) beat))
    show))

(defn- send-beat-changes
  "Compares the old and new sets of entered cues for the track, and
  sends the appropriate messages and updates the UI as needed. Must be
  called with a show containing a last-state snapshot, and the current
  version of the track. Either `status` or `beat` and `position` will
  have non-nil values, and if it is `beat` and `position`, this means
  any cue that was entered was entered right on the beat."
  [show track ^CdjStatus status ^Beat beat ^TrackPositionUpdate position]
  (let [old-track   (get-in show [:last :tracks (:signature track)])
        entered     (reduce set/union (vals (:entered track)))
        old-entered (reduce set/union (vals (:entered old-track)))]

    ;; Even cues we have not entered/exited may have changed playing state.
    (doseq [uuid (set/intersection entered old-entered)]
      (when-let [cue (find-cue track uuid)]  ; Make sure it wasn't deleted.
        (let [is-playing  (seq (cues/players-playing-cue track cue))
              was-playing (seq (cues/players-playing-cue (:last show) old-track old-track cue))
              event       (if is-playing
                            (if (and beat (= (:start cue) (.beatNumber position)))
                              :started-on-beat
                              :started-late)
                            :ended)]
          (when (not= is-playing was-playing)
            (cues/send-cue-messages track track cue event (if (= event :started-late) (or status beat) [beat position]))
            (cues/repaint-cue track cue)
            (cues/repaint-cue-states track cue)))))

    ;; Report cues we have newly entered, which we might also be newly playing.
    (doseq [uuid (set/difference entered old-entered)]
      (when-let [cue (find-cue track uuid)]
        (cues/send-cue-messages track track cue :entered (or status beat))
        (when (seq (cues/players-playing-cue track cue))
          (let [event          (if (and beat (= (:start cue) (.beatNumber position)))
                                 :started-on-beat
                                 :started-late)
                status-or-beat (if (= event :started-on-beat)
                                 [beat position]
                                 (or status beat))]
            (cues/send-cue-messages track track cue event status-or-beat)))
        (cues/repaint-cue track cue)
        (cues/repaint-cue-states track cue)))

    ;; Report cues we have newly exited, which we might also have previously been playing.
    (doseq [uuid (set/difference old-entered entered)]
      (when-let [cue (find-cue track uuid)]
        (when (seq (cues/players-playing-cue (:last show) old-track old-track cue))
          #_(timbre/info "detected end..." (:uuid cue))
          (cues/send-cue-messages old-track old-track cue :ended (or status beat)))
        (cues/send-cue-messages old-track old-track cue :exited (or status beat))
        (cues/repaint-cue track cue)
        (cues/repaint-cue-states track cue)))

    ;; If we received a beat, run the basic beat expression for cues that we were already inside.
    (when beat
      (doseq [uuid (set/intersection old-entered entered)]
        (when-let [cue (find-cue track uuid)]
          (cues/run-cue-function track cue :beat [beat position] false))))

    ;; If the set of entered cues has changed, update the UI appropriately.
    (when (not= entered old-entered)
      (cues/repaint-all-cue-states track)
      ;; If we are showing only entered cues, update cue row visibility.
      (when (get-in track [:contents :cues :entered-only])
        (seesaw/invoke-later (cues/update-cue-visibility track))))))

(defn- deliver-change-events
  "Called when a status packet or signature change has updated the show
  status. Compares the new status with the snapshot of the last
  status, runs any relevant expressions, and updates any needed UI
  elements. `show` and `track` must be the just-updated values, with a
  valid snapshot in the show's `:last` key (although `track` can be
  `nil` if the track is not recognized or not part of the show).
  `player` is the player number, in case `status` is `nil` because we
  are reacting to a signature change rather than a status packet. For
  similar reasons, we also pass the raw signature in case it does not
  correspond to a recognized track. Finally, even if nothing has
  changed, if there is a status packet and the track is tripped and
  has a Tracked Update Expression, it is run with the status update."
  [show signature track player ^CdjStatus status]
  (let [old-loaded  (get-in show [:last :loaded player])
        old-playing (get-in show [:last :playing player])
        old-track   (when old-loaded (get-in show [:last :tracks old-loaded]))
        is-playing  (when status (.isPlaying status))]
    (cond
      (not= old-loaded signature)
      (do  ; This is a switch between two different tracks.
        #_(timbre/info "Switching between two tracks." old-loaded signature)
        #_(timbre/info "enabled?" (su/enabled? show track))
        #_(timbre/info "on-air" (:on-air show))
        #_(timbre/info "tripped?" (:tripped track))
        (when old-track
          (when old-playing (no-longer-playing show player old-track status false))
          (no-longer-loaded show player old-track false))
        (when (and track (su/enabled? show track))
          (now-loaded show player track false)
          (when is-playing (now-playing show player track status false))))

      (not= (:tripped old-track) (:tripped track))
      (do  ; This is an overall activation/deactivation.
        (timbre/info "Track changing tripped to" (:tripped track))
        (if (:tripped track)
          (do  ; Track is now active.
            (when (seq (util/players-signature-set (:loaded show) signature))
              (now-loaded show player track true))
            (when is-playing (now-playing show player track status true)))
          (do  ; Track is no longer active.
            (when old-playing (no-longer-playing show player old-track status true))
            (when old-track (no-longer-loaded show player old-track true)))))

      :else
      (when track  ; Track is not changing tripped state, but we may be reporting a new playing state.
        (when (and old-playing (not is-playing))
          #_(timbre/info "Track stopped playing naturally.")
          (no-longer-playing show player old-track status false))
        (when (and is-playing (not old-playing))
          #_(timbre/info "Track started playing naturally.")
          (now-playing show player track status false))))

    (when track
      (let [entered     (reduce set/union (vals (:entered track)))
            old-entered (reduce set/union (vals (:entered old-track)))]
        (when (:tripped track)

          ;; Report cues we have newly entered, which we might also be newly playing.
          (when (:tripped old-track)  ; Otherwise we already reported them above because the track just activated.
            (doseq [uuid (set/difference entered old-entered)]
              (when-let [cue (find-cue track uuid)]
                (cues/send-cue-messages track track cue :entered status)
                (when (seq (cues/players-playing-cue track cue))
                  (cues/send-cue-messages track track cue :started-late status))
                (cues/repaint-cue track cue)
                (cues/repaint-cue-states track cue))))

          ;; Report cues we have newly exited, which we might also have previously been playing.
          (when (:tripped old-track)  ; Otherwise we never reported entering/playing them, so nothing to do now.
            (doseq [uuid (set/difference old-entered entered)]
              (when-let [cue (find-cue track uuid)]
                (when (seq (cues/players-playing-cue (:last show) old-track old-track cue))
                  (cues/send-cue-messages track track cue :ended status))
                (cues/send-cue-messages track track cue :exited status)
                (cues/repaint-cue track cue)
                (cues/repaint-cue-states track cue))))

          ;; Finaly, run the tracked update expression for the track, if it has one.
          (run-track-function track :tracked status false)
          (doseq [uuid entered]  ; And do the same for any cues we are inside of.
            (when-let [cue (find-cue track uuid)]
              (cues/run-cue-function track cue :tracked status false))))

        (update-playback-position show signature player)

        ;; If the set of entered cues has changed, update the UI appropriately.
        (when (not= entered old-entered)
          (cues/repaint-all-cue-states track)
          ;; If we are showing only entered cues, update cue row visibility.
          (when (get-in track [:contents :cues :entered-only])
            (seesaw/invoke-later (cues/update-cue-visibility track))))))))

(defn- update-cue-state-if-past-beat
  "Checks if it has been long enough after a beat packet was received to
  update the cues' entered state based on a status-packet's beat
  number. This check needs to be made because we have seen status
  packets that players send within a few milliseconds after a beat
  sometimes still contain the old beat number, even though they have
  updated their beat-within-bar number. So this function leaves the
  show's cue state unchanged if a beat happened too recently for the
  previous beat number in a status packet to be considered a jump
  back."
  [show track player ^CdjStatus status]
  (let [[timestamp last-beat] (get-in show [:last-beat player])]
    (if (or (not timestamp)
            (> (- (.getTimestamp status) timestamp) su/min-beat-distance)
            (not= (.getBeatNumber status) (dec last-beat)))
      (update-cue-entered-state show track player (.getBeatNumber status))
      show)))

(defn- update-show-status
  "Adjusts the track state to reflect a new status packet received from
  a player that has it loaded. `track` may be `nil` if the track is
  unrecognized or not part of the show."
  [show signature track ^CdjStatus status]
  (let [player    (.getDeviceNumber status)
        track     (when track (latest-track track))
        shows     (swap-show! show
                              (fn [show]
                                (-> show
                                    su/capture-current-state
                                    (assoc-in [:playing player] (when (and track (.isPlaying status)) signature))
                                    (assoc-in [:on-air player] (when (and track (.isOnAir status)) signature))
                                    (assoc-in [:master player] (when (and track (.isTempoMaster status)) signature))
                                    (assoc-in [:cueing player] (when (and track
                                                                          (su/cueing-states (.getPlayState1 status)))
                                                                 signature))
                                    (update-track-trip-state track)
                                    (update-cue-state-if-past-beat track player status))))
        show      (get shows (:file show))
        track     (when track (get-in show [:tracks signature]))]
    (deliver-change-events show signature track player status)))

(defn- deliver-beat-events
  "Called when a beat has been received for a loaded track and updated
  the show status. Compares the new status with the snapshot of the
  last status, runs any relevant expressions, and updates any needed
  UI elements. `show` and `track` must be the just-updated values, with a
  valid snapshot in the show's `:last` key."
  [show track player ^Beat beat ^TrackPositionUpdate position]
  (let [old-playing (get-in show [:last :playing player])
        is-playing  (get-in show [:playing player])]
    (when (and is-playing (not old-playing))
      (timbre/info "Track started playing with a beat.")
      (now-playing show player track nil false))
    (when (:tripped track)
      (send-beat-changes show track nil beat position)
      (update-playback-position show (:signature track) player))))

(defn- update-track-beat
  "Adjusts the track state to reflect a new beat packet received from a
  player that has it loaded."
  [show track ^Beat beat ^TrackPositionUpdate position]
  (let [player    (.getDeviceNumber beat)
        signature (:signature track)
        track     (latest-track track)
        shows     (swap-show! show
                              (fn [show]
                                (-> show
                                    su/capture-current-state
                                    (assoc-in [:playing player] ; In case beat arrives before playing status.
                                              ;; But ignore the beat as a playing indicator if DJ is actually cueing.
                                              (when-not (get-in show [:cueing player]) signature))
                                    (assoc-in [:last-beat player] [(.getTimestamp beat) (.beatNumber position)])
                                    (update-track-trip-state track)
                                    (update-cue-entered-state track player (.beatNumber position)))))
        show      (get shows (:file show))
        track     (when track (get-in show [:tracks signature]))]
    (when track (deliver-beat-events show track player beat position))))

(defn- clear-player-track-state
  "When a player has changed track signatures, clear the tripped flag
  that may have been set in a previously-loaded track, and any cues
  which had been marked as entered in that track. Designed to be used
  within a swap! operation, so simply returns the value of `show`,
  updated if necessary."
  [show signature player]
  (let [old-loaded  (get-in show [:last :loaded player])
        track (when old-loaded (get-in show [:tracks old-loaded]))]
    (if (and track (not= signature old-loaded))
      (-> show
          (assoc-in [:tracks old-loaded :tripped] false)
          (update-in [:tracks old-loaded :entered] dissoc player))
      show)))

(defn- clear-player-signature-state
  "When a player has changed track signatures, clear out any state
  markers associated with the previous signature. Designed to be used
  within a swap! operation, so simply returns the value of `show`,
  updated if necessary."
  [show signature player]
  (if (not= signature (get-in show [:last :loaded player]))
    (-> show
        (update :playing dissoc player)
        (update :on-air dissoc player)
        (update :master dissoc player))
    show))

(defn- set-incoming-trip-state
  "When a player has changed track signatures, set the tripped flag of
  the incoming track if it is part of the show and we can already tell
  that it is supposed to be enabled. Designed to be used within a
  swap! operation, so simply returns the value of `show`, updated if
  necessary."
  [show signature player]
  (let [old-loaded (get-in show [:last :loaded player])
        track (when signature (get-in show [:tracks signature]))]
    (if (and track (not= signature old-loaded))
      (-> show
          (assoc-in [:tracks signature :tripped] (boolean (su/enabled? show track))))
      show)))

(declare write-song-structure)

(defn- upgrade-song-structure
  "When we have learned about newly available phrase analysis
  information, see if it is for a track in the show which currently
  lacks any, and if so, add it to that track. This must only be called
  when both `tag` and `signature` are not `null`."
  [show ^RekordboxAnlz$TaggedSection tag signature]
  (let [show (latest-show show)]
    (when-let [track (get (:tracks show) signature)]
      (let [track-path (su/build-track-path show signature)]
        (when-not (su/read-song-structure track-path)
          (let [ss-bytes (._raw_body tag)]
            (write-song-structure track-path ss-bytes)
            (su/flush-show! show)
            (let [song-structure (RekordboxAnlz$SongStructureTag. (ByteBufferKaitaiStream. ss-bytes))]
              (swap-track! track assoc :song-structure song-structure)
              (when-let [preview-loader (:preview track)]
                (when-let [^WaveformPreviewComponent preview (preview-loader)]
                  (.setSongStructure preview song-structure)))
              (when-let [lock (seesaw/select (:panel track) [:#lock])]
                (seesaw/config! lock :visible? true))
              (when-let [^WaveformDetailComponent wave (get-in track [:cues-editor :wave])]
                (.setSongStructure wave song-structure)))))))))

(defn update-player-item-signature
  "Makes a player's entry in the import menu enabled or disabled (with
  an explanation), given the track signature that has just been
  associated with the player, updates the affected track(s) sets of
  loaded players, and runs any expressions that need to be informed
  about the loss of the former signature or gain of a new signature.
  Also removes any position being tracked for a player that has lost
  its signature. It is important that this function be idempotent
  because it needs to be called redundantly when importing new
  tracks."
  [^SignatureUpdate sig-update show]
  (let [player                              (.player sig-update)
        signature                           (.signature sig-update)
        ^javax.swing.JMenu import-menu      (:import-menu show)
        disabled-reason                     (describe-disabled-reason show signature)
        ^javax.swing.JMenuItem item         (.getItem import-menu (dec player))
        ^RekordboxAnlz$TaggedSection ss-tag (when (util/online?)
                                              (.getLatestTrackAnalysisFor analysis-finder player ".EXT" "PSSI"))]
    (.setEnabled item (nil? disabled-reason))
    (.setText item (str "from Player " player disabled-reason))
    (let [shows (swap-show! show
                            (fn [show]
                              (-> show
                                  su/capture-current-state
                                  (assoc-in [:loaded player] signature)
                                  (clear-player-track-state signature player)
                                  (clear-player-signature-state signature player)
                                  (set-incoming-trip-state signature player))))
          show  (get shows (:file show))
          track (when signature (get-in show [:tracks signature]))]
      (deliver-change-events show signature track player nil))
    (if (util/online?)
      ;; This version updates phrase tracking for actual online players.
      (if ss-tag
        (phrases/upgrade-song-structure player (.body ss-tag))
        (phrases/clear-song-structure player))
      ;; This version updates phrase tracking for simulation
      (if-let [ss-body (get-in (sim/for-player player) [:track :song-structure])]
        (phrases/upgrade-song-structure player ss-body)
        (phrases/clear-song-structure player)))
    ;; This updates the import menus (and, perhaps redundantly, phrase tracking) for actual online players.
    (when signature
      (when ss-tag (upgrade-song-structure show ss-tag signature))
      (when-let [track (get-in (latest-show show) [:tracks signature])]
        (when-let [song-structure (:song-structure track)]
          (phrases/upgrade-song-structure player song-structure))))))

(defn- refresh-signatures
  "Reports the current track signatures on each player; this is done
  after each new track import, and when first creating a show window,
  to get all the tracks aware of the pre-existing state."
  [show]
  (if (.isRunning signature-finder)
    (doseq [[player signature] (.getSignatures signature-finder)]
      (update-player-item-signature (SignatureUpdate. player signature) show))
    (when (sim/simulating?)
      (doseq [simulator (vals (sim/simulating?))]
        (update-player-item-signature (SignatureUpdate. (:player simulator) (get-in simulator [:track :signature]))
                                      show)))))

(defn- item-label
  "Resolves a SearchableItem label safely, returning `nil` if the item
  is itself `nil`."
  [^SearchableItem item]
  (when item (.label item)))

(defn- extract-metadata
  "Converts the metadata for a track being imported to the show
  filesystem into an ordinary Clojure map so it can be saved and
  reloaded."
  [^org.deepsymmetry.beatlink.data.TrackMetadata metadata]
  {:artist          (item-label (.getArtist metadata))
   :album           (item-label (.getAlbum metadata))
   :comment         (.getComment metadata)
   :date-added      (.getDateAdded metadata)
   :duration        (.getDuration metadata)
   :genre           (item-label (.getGenre metadata))
   :key             (item-label (.getKey metadata))
   :label           (item-label (.getLabel metadata))
   :original-artist (item-label (.getOriginalArtist metadata))
   :rating          (.getRating metadata)
   :remixer         (item-label (.getRemixer metadata))
   :tempo           (.getTempo metadata)
   :title           (.getTitle metadata)})

(defn- write-byte-buffers
  "Creates a sequentially numbered series of files with the specified
  prefix and suffix containing the contents of the supplied byte
  buffers into the show filesystem."
  [^Path track-root prefix suffix byte-buffers]
  (util/doseq-indexed idx [^java.nio.ByteBuffer buffer byte-buffers]
                          (.rewind buffer)
                          (let [bytes     (byte-array (.remaining buffer))
                                file-name (str prefix idx suffix)]
                            (.get buffer bytes)
                            (Files/write (.resolve track-root file-name) bytes su/empty-open-options))))

(defn- write-cue-list
  "Writes the cue list for a track being imported to the show
  filesystem."
  [^Path track-root ^CueList cue-list]
  (if (nil? (.rawMessage cue-list))
    (do
      (write-byte-buffers track-root "cue-list-" ".kaitai" (.rawTags cue-list))  ; Write original nexus style cue info.
      (write-byte-buffers track-root "cue-extended-" ".kaitai" (.rawExtendedTags cue-list)))  ; And nxs2 extended cues.
    (write-message-path (.rawMessage cue-list) (.resolve track-root "cue-list.dbserver"))))

(defn write-beat-grid
  "Writes the beat grid for a track being imported to the show
  filesystem."
  [^Path track-root ^BeatGrid beat-grid]
  (let [grid-vec [(mapv #(.getBeatWithinBar beat-grid (inc %)) (range (.beatCount beat-grid)))
                  (mapv #(.getTimeWithinTrack beat-grid (inc %)) (range (.beatCount beat-grid)))
                  (mapv #(.getBpm beat-grid (inc %)) (range (.beatCount beat-grid)))]]
    (su/write-edn-path grid-vec (.resolve track-root "beat-grid.edn"))))

(defn write-preview
  "Writes the waveform preview for a track being imported to the show
  filesystem."
  [^Path track-root ^WaveformPreview preview]
  (let [bytes     (byte-array (.. preview getData remaining))
        file-name (su/waveform-filename "preview" (.style preview))]
    (.. preview getData (get bytes))
    (Files/write (.resolve track-root file-name) bytes su/empty-open-options)))

(defn write-detail
  "Writes the waveform detail for a track being imported to the show
  filesystem."
  [^Path track-root ^WaveformDetail detail]
  (let [bytes     (byte-array (.. detail getData remaining))
        file-name (su/waveform-filename "detail" (.style detail))]
    (.. detail getData (get bytes))
    (Files/write (.resolve track-root file-name) bytes su/empty-open-options)))

(defn write-art
  "Writes album art for a track imported to the show filesystem."
  [^Path track-root ^AlbumArt art]
  (let [bytes (byte-array (.. art getRawBytes remaining))]
    (.. art getRawBytes (get bytes))
    (Files/write (.resolve track-root "art.jpg") bytes su/empty-open-options)))

(defn write-song-structure
  "Writes the song structure (phrase analysis information) for a track
  being imported to the show filesystem, given the bytes from which
  the song structure was parsed."
  [^Path track-root ^byte/1 ss-bytes]
  (Files/write (.resolve track-root "song-structure.kaitai") ss-bytes su/empty-open-options))

(defn- show-midi-status
  "Set the visibility of the Enabled checkbox and the text and color
  of its label based on whether the currently-selected MIDI output can
  be found. This function must be called on the Swing Event Update
  thread since it interacts with UI objects."
  [track]
  (try
    (let [panel         (:panel track)
          enabled-label (seesaw/select panel [:#enabled-label])
          enabled       (seesaw/select panel [:#enabled])
          output        (su/get-chosen-output track)]
      (if (or output (su/no-output-chosen track))
        (do (seesaw/config! enabled-label :foreground "white")
            (seesaw/value! enabled-label "Enabled:")
            (seesaw/config! enabled :visible? true))
        (do (seesaw/config! enabled-label :foreground "red")
            (seesaw/value! enabled-label "MIDI Output not found.")
            (seesaw/config! enabled :visible? false))))
    (catch Exception e
      (timbre/error e "Problem showing Track MIDI status."))))

(defn update-tracks-global-expression-icons
  "Updates the icons next to expressions in the Tracks menu to
  reflect whether they have been assigned a non-empty value."
  [show]
  (let [show        (latest-show show)
        ^JMenu menu (seesaw/select (:frame show) [:#tracks-menu])]
    (doseq [i (range (.getItemCount menu))]
      (let [item  (.getItem menu i)
            exprs {"Edit Shared Functions"                  :shared
                   "Edit Global Setup Expression"           :setup
                   "Edit Came Online Expression"            :online
                   "Edit Default Enabled Filter Expression" :enabled
                   "Edit Going Offline Expression"          :offline
                   "Edit Global Shutdown Expression"        :shutdown}]
        (when item
          (when-let [expr (get exprs (.getText item))]
            (.setIcon item (seesaw/icon (if (empty? (get-in show [:contents :expressions expr]))
                                          "images/Gear-outline.png"
                                          "images/Gear-icon.png")))))))))

(defn- attach-track-custom-editor-opener
  "Sets up an action handler so that when one of the popup menus is set
  to Custom, if there is not already an expession of the appropriate
  kind present, an editor for that expression is automatically
  opened."
  [show track menu kind gear]
  (let [panel (or (:panel track) (:frame show))]
    (seesaw/listen menu
                   :action-performed (fn [_]
                                       (let [choice (seesaw/selection menu)
                                             show   (latest-show show)
                                             track  (when track (get-in show [:tracks (:signature track)]))]
                                         (when (and (= "Custom" choice)
                                                    (not (if track (:creating track) (:creating show)))
                                                    (str/blank?
                                                     (get-in (or track show) [:contents :expressions (keyword kind)])))
                                           (editors/show-show-editor (keyword kind) show track panel
                                                                     (if gear
                                                                       #(su/update-gear-icon track gear)
                                                                       #(update-tracks-global-expression-icons show)))))))))

(defn- attach-track-message-visibility-handler
  "Sets up an action handler so that when one of the message menus is
  changed, the appropriate UI elements are shown or hidden. Also
  arranges for the proper expression editor to be opened if Custom is
  chosen for the message type and that expression is currently empty."
  [show track kind gear]
  (let [panel           (:panel track)
        message-menu    (seesaw/select panel [(keyword (str "#" kind "-message"))])
        note-spinner    (seesaw/select panel [(keyword (str "#" kind "-note"))])
        label           (seesaw/select panel [(keyword (str "#" kind "-channel-label"))])
        channel-spinner (seesaw/select panel [(keyword (str "#" kind "-channel"))])]
    (seesaw/listen message-menu
                   :action-performed (fn [_]
                                       (let [choice (seesaw/selection message-menu)]
                                         (if (= "None" choice)
                                           (seesaw/hide! [note-spinner label channel-spinner])
                                           (seesaw/show! [note-spinner label channel-spinner])))))
    (attach-track-custom-editor-opener show track message-menu kind gear)))

(defn- build-filter-target
  "Creates a string that can be matched against to filter a track by
  text substring, taking into account the custom comment assigned to
  the track in the show, if any."
  [metadata comment]
  (let [comment          (or comment (:comment metadata))
        metadata-strings (vals (select-keys metadata [:album :artist :genre :label :original-artist :remixer :title]))]
    (str/lower-case (str/join "\0" (filter identity (concat metadata-strings [comment]))))))

(defn- soft-object-loader
  "Returns a function that can be called to obtain an object. If the
  garbage collector does not need the space, the same object will be
  returned on subsequent calls. If there has been a need for memory,
  it can be garbage collected, and the next call will return a fresh
  copy. `loader` is the function called to create the object
  initially, and whenever it needs to be reloaded."
  [loader]
  (let [reference (atom (SoftReference. nil))]
    (fn []
      (let [result (.get ^SoftReference @reference)]  ; See if our soft reference holds the object we need.
        (if (some? result)
          result  ; Yes, we can return same instance we last created.
          (let [next-object (loader)]
            #_(when next-object (timbre/info "soft loaded" next-object))
            (reset! reference (SoftReference. next-object))
            next-object))))))

(defn- create-reloadable-component
  "Creates a canvas that hosts another component using a soft reference
  and loader so the underlying component can be garbage collected when
  it is not needed (e.g. scrolled far out of view) but brought back
  when it needs to be displayed. If any of the keyword arguments
  `:maximum-size`, `:minimum-size`, and `:preferred-size` are
  supplied, the associate value is used for the created component,
  otherwise the wrapped component is asked, which will require loading
  it immediately during creation."
  [loader {:keys [maximum-size minimum-size preferred-size]}]
  (let [bounds             (Rectangle.)
        size-opts          (concat (when-let [size (or minimum-size
                                                       (when-let [^JComponent wrapped (loader)]
                                                         (.getMinimumSize wrapped)))]
                                     [:minimum-size size])
                                   (when-let [size (or maximum-size
                                                       (when-let [^JComponent wrapped (loader)]
                                                         (.getMaximumSize wrapped)))]
                                     [:maximum-size size])
                                   (when-let [size (or preferred-size
                                                       (when-let [^JComponent wrapped (loader)]
                                                         (.getPreferredSize wrapped)))]
                                     [:preferred-size size]))
        ^JComponent canvas (apply seesaw/canvas (concat [:opaque? false] size-opts))
        delegate           (proxy [org.deepsymmetry.beatlink.data.RepaintDelegate] []
                             (repaint [x y w h]
                               #_(timbre/info "delegating repaint" x y w h)
                               (.repaint canvas x y w h)))]
    (seesaw/config! canvas :paint (fn [^JComponent canvas ^Graphics2D graphics]
                                    (when-let [^JComponent component (loader)]
                                      (when (instance? WaveformPreviewComponent component)
                                        (.setRepaintDelegate ^WaveformPreviewComponent component delegate))
                                      (.getBounds canvas bounds)
                                      (.setBounds component bounds)
                                      (.paint component graphics))))
    canvas))

(defn- create-track-art
  "Creates the softly-held widget that represents a track's artwork, if
  it has any, or just a blank space if it has none."
  [show signature]
  (let [art-loader (soft-object-loader #(su/read-art (su/build-track-path show signature)))]
    (seesaw/canvas :size [80 :by 80] :opaque? false
                   :paint (fn [_ ^Graphics2D graphics]
                            (when-let [^AlbumArt art (art-loader)]
                              (when-let [image (.getImage art)]
                                (.drawImage graphics image 0 0 nil)))))))

(defn- create-preview-loader
  "Creates the loader function that can (re)create a track preview
  component as needed."
  [show signature metadata]
  (soft-object-loader
   (fn []
     (let [track-root     (su/build-track-path show signature)
           preview        (su/read-preview track-root)
           cue-list       (su/read-cue-list track-root)
           beat-grid      (su/read-beat-grid track-root)
           song-structure (get-in (latest-show show) [:tracks signature :song-structure])
           component      (WaveformPreviewComponent. preview (:duration metadata) cue-list)]
       (.setBeatGrid component beat-grid)
       (.setOverlayPainter component (proxy [org.deepsymmetry.beatlink.data.OverlayPainter] []
                                       (paintOverlay [component graphics]
                                         (cues/paint-preview-cues show signature component graphics))))
       (when song-structure (.setSongStructure component song-structure))
       component))))

(defn- create-track-preview
  "Creates the softly-held widget that draws the track's waveform
  preview."
  [loader]
  (create-reloadable-component loader {:maximum-size   (java.awt.Dimension. 1208 152)
                                       :minimum-size   (java.awt.Dimension. 408 56)
                                       :preferred-size (java.awt.Dimension. 608 88)}))

(defn save-track-contents
  "Saves the contents maps for any tracks that have changed them."
  [show]
  (doseq [track (vals (:tracks (latest-show show)))]
    (let [contents (:contents track)]
      (when (not= contents (:original-contents track))
        (su/write-edn-path contents (.resolve (su/build-track-path show (:signature track)) "contents.edn"))
        (swap-track! track assoc :original-contents contents)))))

(defn- format-artist-album
  [metadata]
  (str/join ": " (filter identity (map util/remove-blanks [(:artist metadata) (:album metadata)]))))

(defn- track-panel-constraints
  "Calculates the proper layout constraints for a track panel to look
  right at a given window width."
  [width]
  (let [text-width (max 180 (int (/ (- width 142) 4)))
        preview-width (max 408 (* text-width 3))]
    ["" (str "[]unrelated[fill, " text-width "]unrelated[fill, " preview-width "]")]))

(defn- copy-track-content-action
  "Creates the menu action which copies the content of a track."
  [track]
  (let [track (latest-track track)]
    (seesaw/action :handler (fn [_] (reset! copied-track-content (select-keys track [:contents :metadata])))
                   :name "Copy Track Content"
                   :tip "Copies the current track configuration, expressions, and cues.")))

(defn- crop-cues
  "Given the cue map for a track, and the maximum beat number that
  exists in the track, removes any cues that begin at or past the
  final beat, and adjusts the ending beat of each cue to be no greater
  than the final beat."
  [cues max-beat]
  (reduce (fn [result [uuid cue]]
            (if (< (:start cue) max-beat)
              (assoc result uuid (update cue :end min max-beat))
              result))
          {}
          cues))

(defn- update-track-comboboxes
  "Called when a track row has been created in a show, or the track
  contents have been replaced, to update the combo-box elements
  to reflect the track's state."
  [contents panel]
  (seesaw/selection! (seesaw/select panel [:#outputs]) (or (:midi-device contents) (first (util/get-midi-outputs))))
  (seesaw/selection! (seesaw/select panel [:#loaded-message]) (or (:loaded-message contents) "None"))
  (seesaw/selection! (seesaw/select panel [:#playing-message]) (or (:playing-message contents) "None"))
  (seesaw/selection! (seesaw/select panel [:#enabled]) (or (:enabled contents) "Default")))

(defn parse-track-expressions
  "Parses all of the expressions associated with a track and its cues.
  `track` must be current."
  [show track]
  (doseq [[kind expr] (editors/sort-setup-to-front (get-in track [:contents :expressions]))]
      (let [editor-info (get @editors/show-track-editors kind)]
        (try
          (swap-track! track assoc-in [:expression-fns kind]
                       (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                          (editors/show-editor-title kind show track)))
              (catch Exception e
                (timbre/error e (str "Problem parsing " (:title editor-info)
                                     " when loading Show. Expression:\n" expr "\n"))
                (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                   "Check the log file for details.")
                              :title "Exception during Clojure evaluation" :type :error)))))
  ;; Parse any custom expressions defined for cues in the track.
  (doseq [cue (vals (get-in track [:contents :cues :cues]))]
    (cues/compile-cue-expressions track cue)))


(defn- replace-track-contents
  "Replaces the contents of the track with the supplied values, crops or
  removes any cues which no longer fit, then updates the user
  interface. `show` and `track` must be current."
  [show track panel contents]
  (when-let [editor (:cues-editor track)]
    ((:close-fn editor) true)) ; Forcibly close any open cue editor window; we are replacing cues.
  (let [max-beat (long (.beatCount ^BeatGrid (:grid track)))
        cues     (crop-cues (get-in contents [:cues :cues]) max-beat)
        contents (assoc-in contents [:cues :cues] cues)]
    (swap-track! track assoc :contents contents  ; Install the copied and cropped contents.
                 :creating true)  ; Disarm automatic editor popup while we are messing with the UI.
    (cues/add-missing-library-cues show (vals cues))
    (cues/build-cues track)
    (su/update-gear-icon track)
    (update-track-comboboxes contents panel)
    (seesaw/value! (seesaw/select panel [:#loaded-note]) (:loaded-note contents))
    (seesaw/value! (seesaw/select panel [:#loaded-channel]) (:loaded-channel contents))
    (seesaw/value! (seesaw/select panel [:#playing-note]) (:playing-note contents))
    (seesaw/value! (seesaw/select panel [:#playing-channel]) (:playing-channel contents))
    (parse-track-expressions show (latest-track track))
    (swap-track! track dissoc :creating)))  ; No longer suppress popup of expression editors.

(defn- find-cue-conflicts
  "Called when the user is importing or pasting a track. Finds any
  linked cues in the incoming track which refer to library tracks that
  already exist but have different content. Arguments must be
  current. Returns the names of the problematic linked cues."
  [show source-track]
  (filter identity
          (for [cue (vals (get-in source-track [:contents :cues :cues]))]
            (when-let [linked (:linked cue)]
              (when-let [library-cue (get-in show [:contents :cue-library linked])]
                (when-not (cues/linked-cues-equal? cue library-cue) (:comment cue)))))))

(defn- validate-copying-linked-cues
  "Checks to make sure the incoming track does not have any linked cues
  which conflict with existing library cues in the destination track.
  If it does, displays an error dialog explaining the problem and ways
  to address it and returns falsy to prevent the operation. Arguments
  must be current."
  [show operation panel source-track]
  (let [conflicts (find-cue-conflicts show source-track)
        max-cues  4]
    (if (empty? conflicts)
      true  ; Validation succeeded, the operation can proceed.
      (let [message (str
                     (apply str "The track you are trying to " operation " contains Cues linked to Library Cues\r\n"
                            "that exist in this show, but have different content. You won't be able to\r\n"
                            operation " unless you either unlink those cues, or remove the conflict by\r\n"
                            "renaming or updating the Library Cues they link to.\r\n"
                            "Cues: " (cues/describe-unlinking-cues conflicts max-cues)))]
        (seesaw/alert panel message :name "Library Cue Conflict" :type :error)
        false))))

(defn- paste-track-content-action
  "Creates the menu action which pastes copied content over a track."
  [track panel]
  (let [[show track] (latest-show-and-track track)
        title        (get-in @copied-track-content [:metadata :title])]
    (seesaw/action :handler (fn [_]
                              (when (and (validate-copying-linked-cues show "paste" panel @copied-track-content)
                                         (util/confirm panel (str "This will irreversibly replace the configuration, "
                                                                  "expressions, and cues of this track with\r\n"
                                                                  "the ones you copied from “" title "”.\r\n\r\n"
                                                                  "Are you sure?")
                                                       :title (str "Replace Content of “"
                                                                   (get-in track [:metadata :title]) "”?")))
                                (replace-track-contents show track panel (:contents @copied-track-content))))
                   :name "Paste Track Content"
                   :tip "Replace the contents of this track with previously copied values."
                   :enabled? (some? @copied-track-content))))

(defn- edit-cues-action
  "Creates the menu action which opens the track's cue editor window."
  [track panel]
  (seesaw/action :handler (fn [_] (cues/open-cues track panel))
                 :name "Edit Track Cues"
                 :tip "Set up cues that react to particular sections of the track being played."
                 :icon (if (empty? (get-in (latest-track track) [:contents :cues :cues]))
                         "images/Gear-outline.png"
                         "images/Gear-icon.png")))

(defn- track-missing-expression?
  "Checks whether the expression body of the specified kind is empty for
  the specified track."
  [track kind]
  (str/blank? (get-in (latest-track track) [:contents :expressions kind])))

(defn track-editor-update-fn
  "The function called when a track-level expression has been updated, to
  properly update the application state and user interface."
  [kind track gear]
  (when (= kind :setup)  ; Clean up then run the new setup function
    (run-track-function track :shutdown nil true)
    (reset! (:expression-locals track) {})
    (run-track-function track :setup nil true))
  (su/update-gear-icon track gear))

(defn- track-editor-actions
  "Creates the popup menu actions corresponding to the available
  expression editors for a given track."
  [show track panel gear]
  (for [[kind spec] @editors/show-track-editors]
    (seesaw/action :handler (fn [_] (editors/show-show-editor kind (latest-show show)
                                                              (latest-track track) panel
                                                              (partial track-editor-update-fn kind track gear)))
                   :name (str "Edit " (:title spec))
                   :tip (:tip spec)
                   :icon (if (track-missing-expression? track kind)
                           "images/Gear-outline.png"
                           "images/Gear-icon.png"))))

(defn- track-event-enabled?
  "Checks whether the specified event type is enabled for the given
  track (its message is something other than None, and if Custom,
  there is a non-empty expression body)."
  [track event]
  (let [track   (latest-track track)
        message (get-in track [:contents (keyword (str (name event) "-message"))])]
    (cond
      (= "None" message)
      false

      (= "Custom" message)
      (not (track-missing-expression? track event))

      :else ; Is a MIDI note or CC
      true)))

(defn track-random-status-for-simulation
  "Returns functions that update simulation bindings and generate
  appropriate status objects for simulating a track expression of the
  specified kind."
  [kind]
  (case kind
    (:playing :tracked :stopped) [util/time-for-simulation su/random-cdj-status]
    :beat                        [util/beat-for-simulation
                                  (fn []
                                    (let [beat-object (su/random-beat)]
                                      [beat-object (su/position-from-random-beat beat-object)]))]
    nil))

(defn- track-simulate-actions
  "Creates the actions that simulate events happening to the track, for
  testing expressions or creating and testing MIDI mappings in other
  software."
  [show track]
  [(seesaw/action :name "Loaded"
                  :enabled? (track-event-enabled? track :loaded)
                  :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation
                                                                :entry [(:file show) (:signature track)])]
                                     (send-loaded-messages (latest-track track)))))
   (seesaw/action :name "Playing"
                  :enabled? (track-event-enabled? track :playing)
                  :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation
                                                                :entry [(:file show) (:signature track)])]
                                     (let [[update-binding create-status] (track-random-status-for-simulation :playing)]
                                       (binding [util/*simulating* (update-binding)]
                                         (send-playing-messages (latest-track track) (create-status)))))))
   (seesaw/action :name "Beat"
                  :enabled? (not (track-missing-expression? track :beat))
                  :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation
                                                                :entry [(:file show) (:signature track)])]
                                     (let [[update-binding create-status] (track-random-status-for-simulation :beat)]
                                       (binding [util/*simulating* (update-binding)]
                                         (run-track-function track :beat (create-status) true))))))
   (seesaw/action :name "Tracked Update"
                  :enabled? (not (track-missing-expression? track :tracked))
                  :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation
                                                                :entry [(:file show) (:signature track)])]
                                     (let [[update-binding create-status] (track-random-status-for-simulation :tracked)]
                                       (binding [util/*simulating* (update-binding)]
                                         (run-track-function track :tracked (create-status) true))))))
   (seesaw/action :name "Stopped"
                  :enabled? (track-event-enabled? track :playing)
                  :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation
                                                                :entry [(:file show) (:signature track)])]
                                     (let [[update-binding create-status] (track-random-status-for-simulation :stopped)]
                                       (binding [util/*simulating* (update-binding)]
                                         (send-stopped-messages (latest-track track) (create-status)))))))
   (seesaw/action :name "Unloaded"
                  :enabled? (track-event-enabled? track :loaded)
                  :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation
                                                                :entry [(:file show) (:signature track)])]
                                     (send-unloaded-messages (latest-track track)))))])

(defn- track-simulate-menu
  "Creates the submenu containing actions that simulate events happening
  to the track, for testing expressions or creating and testing MIDI
  mappings in other software."
  [show track]
  (seesaw/menu :text "Simulate" :items (track-simulate-actions show track)))

(declare import-track)

(defn- track-copy-actions
  "Returns a set of menu actions which offer to copy the track to any
  other open shows which do not already contain it."
  [track]
  (let [[show track] (latest-show-and-track track)
        track-root   (su/build-track-path show (:signature track))]
    (filter identity
            (map (fn [other-show]
                   (when (and (not= (:file show) (:file other-show))
                              (not (:block-tracks? other-show))
                              (not (get-in other-show [:tracks (:signature track)])))
                     (seesaw/action :handler
                                    (fn [_]
                                      (let [new-track (merge (select-keys track [:signature :metadata
                                                                                 :contents])
                                                             {:cue-list       (su/read-cue-list track-root)
                                                              :beat-grid      (su/read-beat-grid track-root)
                                                              :preview        (su/read-preview track-root)
                                                              :detail         (su/read-detail track-root)
                                                              :art            (su/read-art track-root)
                                                              :song-structure (su/read-song-structure track-root)})]
                                        (import-track other-show new-track)
                                        (refresh-signatures other-show)))
                                    :name (str "Copy to Show \"" (fs/base-name (:file other-show) true) "\""))))
                 (vals (su/get-open-shows))))))

(defn- remove-signature
  "Filters a map from players to signatures (such as the :loaded
  and :playing entries in a show) to remove any keys whose value match
  the supplied signature. This is used as part of cleaning up a show
  when a track has been deleted."
  [player-map signature]
  (reduce (fn [result [k v]]
            (if (= v signature)
              result
              (assoc result k v)))
          {}
          player-map))

(defn- expunge-deleted-track
  "Removes all the items from a show that need to be cleaned up when the
  track has been deleted. This function is designed to be used in a
  single swap! call for simplicity and efficiency."
  [show track panel]
  (-> show
      (update :tracks dissoc (:signature track))
      (update :panels dissoc panel)
      (update :loaded remove-signature (:signature track))
      (update :playing remove-signature (:signature track))))

(defn- close-track-editors?
  "Tries closing all open expression and cue editors for the track. If
  `force?` is true, simply closes them even if they have unsaved
  changes. Otherwise checks whether the user wants to save any unsaved
  changes. Returns truthy if there are none left open the user wants
  to deal with."
  [force? track]
  (let [track (latest-track track)]
    (and
     (every? (partial editors/close-editor? force?) (vals (:expression-editors track)))
     (or (not (:cues-editor track)) ((get-in track [:cues-editor :close-fn]) force?)))))

(defn- cleanup-track
  "Process the removal of a track, either via deletion, or because the
  show is closing. If `force?` is true, any unsaved expression editors
  will simply be closed. Otherwise, they will block the track removal,
  which will be indicated by this function returning falsey. Run any
  appropriate custom expressions and send configured MIDI messages to
  reflect the departure of the track."
  [force? track]
  (when (close-track-editors? force? track)
    (let [[show track] (latest-show-and-track track)]
      (when (:tripped track)
        (doseq [cue (get-in track [:contents :cues :cues])]
          (cues/cleanup-cue true track cue))
        (when ((set (vals (:playing show))) (:signature track))
          (send-stopped-messages track nil))
        (when ((set (vals (:loaded show))) (:signature track))
          (send-unloaded-messages track)))
      (doseq [listener (vals (:listeners track))]
        (.removeTrackPositionListener util/time-finder listener))
      (swap-track! track dissoc :listeners)
      (run-track-function track :shutdown nil (not force?)))
    true))

(defn- delete-track-action
  "Creates the menu action which deletes a track after confirmation."
  [show track panel]
  (seesaw/action :handler (fn [_]
                            (when (util/confirm panel (str "This will irreversibly remove the track, losing any\r\n"
                                                           "configuration, expressions, and cues created for it.")
                                                :type :warning :title "Delete Track?")
                              (try
                                (let [show       (latest-show show)
                                      track-root (su/build-track-path show (:signature track))]
                                  (doseq [path (-> (Files/walk (.toAbsolutePath track-root)
                                                               (make-array java.nio.file.FileVisitOption 0))
                                                   (.sorted #(compare (str %2) (str %1)))
                                                   .iterator
                                                   iterator-seq)]
                                    #_(timbre/info "Trying to delete:" (str path))
                                    #_(timbre/info "Exists?" (Files/isReadable path))
                                    (Files/delete path)
                                    #_(timbre/info "Still there?" (Files/isReadable path))))
                                (cleanup-track true track)
                                (swap-show! show expunge-deleted-track track panel)
                                (refresh-signatures show)
                                (su/update-row-visibility show)
                                (sim/recompute-track-models)
                                (catch Exception e
                                  (timbre/error e "Problem deleting track")
                                  (seesaw/alert (str e) :title "Problem Deleting Track" :type :error)))))
                 :name "Delete Track"))

(defn- paint-track-state
  "Draws a representation of the state of the track, including whether
  it is enabled and whether any players have it loaded or playing (as
  deterimined by the keyword passed in `k`)."
  [show signature k c ^Graphics2D g]
  (let [w        (double (seesaw/width c))
        h        (double (seesaw/height c))
        outline  (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        show     (latest-show show)
        track    (get-in show [:tracks signature])
        enabled? (su/enabled? show track)
        active?  (seq (util/players-signature-set (k show) signature))]
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

(defn- lock-button-icon-internal
  "Determines whether a track allows phrase triggers to run while it
  plays, given the current track contents map."
  [show signature]
  (if (get-in (latest-show show) [:tracks signature :contents :phrase-unlocked])
    (IconFontSwing/buildIcon FontAwesome/UNLOCK_ALT 16.0 Color/green)
    (IconFontSwing/buildIcon FontAwesome/LOCK 16.0 Color/red)))

(defn- create-track-panel
  "Creates a panel that represents a track in the show. Updates tracking
  indices appropriately."
  [show ^Path track-root]
  (let [signature      (first (str/split (str (.getFileName track-root)), #"/")) ; ZipFS gives trailing '/'!
        metadata       (su/read-edn-path (.resolve track-root "metadata.edn"))
        contents-path  (.resolve track-root "contents.edn")
        contents       (when (Files/isReadable contents-path) (su/read-edn-path contents-path))
        comment        (or (:comment contents) (:comment metadata))
        update-comment (fn [c]
                         (let [comment (seesaw/text c)]
                           (swap-signature! show signature assoc-in [:contents :comment] comment)
                           (swap-signature! show signature assoc :filter
                                            (build-filter-target metadata comment))))
        comment-field  (seesaw/text :id :comment :paint (partial util/paint-placeholder "Comment")
                                    :text comment :listen [:document update-comment])
        preview-loader (create-preview-loader show signature metadata)
        soft-preview   (create-track-preview preview-loader)
        song-structure (when-let [raw-ss (su/read-song-structure track-root)]
                         (RekordboxAnlz$SongStructureTag. (ByteBufferKaitaiStream. raw-ss)))
        outputs        (util/get-midi-outputs)
        gear           (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
        lock           (seesaw/button :id :lock :visible? (some? (su/read-song-structure track-root))
                                      :tip "Toggle Phrase Trigger lockout"
                                      :listen [:action-performed
                                               (fn [e]
                                                 (swap-signature! show signature
                                                                  update-in [:contents :phrase-unlocked] not)
                                                 (seesaw/config! e :icon (lock-button-icon-internal show signature)))])
        panel          (mig/mig-panel
                        :constraints (track-panel-constraints (.getWidth ^JFrame (:frame show)))
                        :items [[(create-track-art show signature) "spany 4"]
                                [(seesaw/label :text (or (:title metadata) "[no title]")
                                               :font (Font. "serif" Font/ITALIC 14)
                                               :foreground :yellow)
                                 "width 60:120"]
                                [soft-preview "spany 4, wrap"]

                                [(seesaw/label :text (format-artist-album metadata)
                                               :font (Font. "serif" Font/BOLD 13)
                                               :foreground :green)
                                 "width 60:120, wrap"]

                                [comment-field "wrap"]

                                [(seesaw/label :text "Players:") "split 5, gap unrelated"]
                                [(seesaw/label :id :players :text "--")]
                                [(seesaw/label :text "Playing:") "gap unrelated"]
                                [(seesaw/label :id :playing :text "--") "gapafter push"]
                                [lock "hidemode 1, wrap unrelated"]

                                [gear "spanx, split"]

                                ["MIDI Output:" "gap unrelated"]
                                [(seesaw/combobox :id :outputs
                                                  :model (let [chosen (:midi-device contents)]
                                                           (concat outputs
                                                                   ;; Preserve existing selection even if now missing.
                                                                   (when (and chosen (not ((set outputs) chosen)))
                                                                     [chosen])
                                                                   ;; Offer escape hatch if no MIDI devices available.
                                                                   (when (and chosen (empty? outputs))
                                                                     [nil])))
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-signature! show signature
                                                                             assoc-in [:contents :midi-device]
                                                                             (seesaw/selection %))])]

                                ["Loaded:" "gap unrelated"]
                                [(seesaw/canvas :id :loaded-state :size [18 :by 18] :opaque? false
                                                :tip "Outer ring shows track enabled, inner light when loaded."
                                                :paint (partial paint-track-state show signature :loaded))]
                                ["Message:"]
                                [(seesaw/combobox :id :loaded-message :model ["None" "Note" "CC" "Custom"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-signature! show signature
                                                                             assoc-in [:contents :loaded-message]
                                                                             (seesaw/selection %))])]
                                [(seesaw/spinner :id :loaded-note
                                                 :model (seesaw/spinner-model (or (:loaded-note contents) 126)
                                                                              :from 1 :to 127)
                                                 :listen [:state-changed
                                                          #(swap-signature! show signature
                                                                            assoc-in [:contents :loaded-note]
                                                                            (seesaw/value %))])
                                 "hidemode 3"]

                                [(seesaw/label :id :loaded-channel-label :text "Channel:")
                                 "gap unrelated, hidemode 3"]
                                [(seesaw/spinner :id :loaded-channel
                                                 :model (seesaw/spinner-model (or (:loaded-channel contents) 1)
                                                                              :from 1 :to 16)
                                                 :listen [:state-changed
                                                          #(swap-signature! show signature
                                                                            assoc-in [:contents :loaded-channel]
                                                                            (seesaw/value %))])
                                 "hidemode 3"]

                                ["Playing:" "gap unrelated"]
                                [(seesaw/canvas :id :playing-state :size [18 :by 18] :opaque? false
                                                :tip "Outer ring shows track enabled, inner light when playing."
                                                :paint (partial paint-track-state show signature :playing))]
                                ["Message:"]
                                [(seesaw/combobox :id :playing-message :model ["None" "Note" "CC" "Custom"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-signature! show signature
                                                                             assoc-in [:contents :playing-message]
                                                                             (seesaw/selection %))])]
                                [(seesaw/spinner :id :playing-note
                                                 :model (seesaw/spinner-model (or (:playing-note contents) 127)
                                                                              :from 1 :to 127)
                                                 :listen [:state-changed
                                                          #(swap-signature! show signature
                                                                            assoc-in [:contents :playing-note]
                                                                            (seesaw/value %))])
                                 "hidemode 3"]

                                [(seesaw/label :id :playing-channel-label :text "Channel:")
                                 "gap unrelated, hidemode 3"]
                                [(seesaw/spinner :id :playing-channel
                                                 :model (seesaw/spinner-model (or (:playing-channel contents) 1)
                                                                              :from 1 :to 16)
                                                 :listen [:state-changed
                                                          #(swap-signature! show signature
                                                                            assoc-in [:contents :playing-channel]
                                                                            (seesaw/value %))])
                                 "hidemode 3"]

                                [(seesaw/label :id :enabled-label :text "Enabled:") "gap unrelated"]
                                [(seesaw/combobox :id :enabled
                                                  :model ["Default" "Never" "On-Air" "Master" "Custom" "Always"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(do (swap-signature! show signature
                                                                                 assoc-in [:contents :enabled]
                                                                                 (seesaw/value %))
                                                                (repaint-track-states show signature))])
                                 "hidemode 3"]])

        track (merge {:file              (:file show)
                      :signature         signature
                      :metadata          metadata
                      :contents          contents
                      :original-contents contents
                      :grid              (su/read-beat-grid track-root)
                      :panel             panel
                      :filter            (build-filter-target metadata comment)
                      :preview           preview-loader
                      :preview-canvas    soft-preview
                      :expression-locals (atom {})
                      :creating          true ; Suppress popup expression editors when reopening a show.
                      :entered           {}}  ; Map from player number to set of UUIDs of cues that have been entered.
                     (when song-structure
                       {:song-structure   song-structure}))

        popup-fn (fn [^MouseEvent e]  ; Creates the popup menu for the gear button or right-clicking in the track.
                   (if (.isShiftDown e)
                     [(copy-track-content-action track) ; The special track content copy/paste menu.
                      (paste-track-content-action track panel)]
                     (concat [(edit-cues-action track panel) (seesaw/separator)] ; The normal context menu.
                             (when (seq (su/gear-content track))
                               [(su/view-expressions-in-report-action show track)])
                             (track-editor-actions show track panel gear)
                             [(seesaw/separator) (track-simulate-menu show track) (su/inspect-action track)
                              (seesaw/separator)]
                             (track-copy-actions track)
                             [(seesaw/separator) (delete-track-action show track panel)])))

        drag-origin (atom nil)]

    (swap-show! show assoc-in [:tracks signature] track)
    (swap-show! show assoc-in [:panels panel] signature)

    ;; Now that the track is in the show, set the initial state of the phrase lock button icon.
    (seesaw/config! lock :icon (lock-button-icon-internal show signature))

    ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
    ;; or right click on the gear button. Also set the proper initial gear appearance.
    (seesaw/config! [panel gear] :popup popup-fn)
    (seesaw/listen gear
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (popup-fn e))]
                                      (util/show-popup-from-button gear popup e))))
    (su/update-gear-icon track gear)

    (seesaw/listen soft-preview
                   :mouse-moved (fn [e] (handle-preview-move track soft-preview preview-loader e))
                   :mouse-pressed (fn [^MouseEvent e]
                                    (reset! drag-origin {:point (.getPoint e)})
                                    (handle-preview-press track preview-loader e))
                   :mouse-dragged (fn [e] (handle-preview-drag track preview-loader e drag-origin)))

    ;; Update output status when selection changes, giving a chance for the other handlers to run first
    ;; so the data is ready. Also sets them up to automatically open the expression editor for the Custom
    ;; Enabled Filter if "Custom" is chosen.
    (seesaw/listen (seesaw/select panel [:#outputs])
                   :item-state-changed (fn [_] (seesaw/invoke-later (show-midi-status track))))
    (attach-track-message-visibility-handler show track "loaded" gear)
    (attach-track-message-visibility-handler show track "playing" gear)
    (attach-track-custom-editor-opener show track (seesaw/select panel [:#enabled]) :enabled gear)

    ;; Establish the saved or initial settings of the UI elements, which will also record them for the
    ;; future, and adjust the interface, thanks to the already-configured item changed listeners.
    (update-track-comboboxes contents panel)

    ;; In case this is the inital creation of the track, record the defaulted values of the numeric inputs too.
    ;; This will have no effect if they were loaded.
    (swap-signature! show signature
                     assoc-in [:contents :loaded-note] (seesaw/value (seesaw/select panel [:#loaded-note])))
    (swap-signature! show signature
                     assoc-in [:contents :loaded-channel] (seesaw/value (seesaw/select panel [:#loaded-channel])))
    (swap-signature! show signature
                     assoc-in [:contents :playing-note] (seesaw/value (seesaw/select panel [:#playing-note])))
    (swap-signature! show signature
                     assoc-in [:contents :playing-channel] (seesaw/value (seesaw/select panel [:#playing-channel])))

    (cues/build-cues track)
    (parse-track-expressions show track)

    ;; We are done creating the track, so arm the menu listeners to automatically pop up expression editors when
    ;; the user requests a custom message.
    (swap-signature! show signature dissoc :creating)))

(defn- create-track-panels
  "Creates all the panels that represent tracks in the show."
  [show]
  (let [tracks-path (su/build-filesystem-path (:filesystem show) "tracks")]
    (when (Files/isReadable tracks-path)  ; We have imported at least one track.
      (doseq [track-path (Files/newDirectoryStream tracks-path)]
        (create-track-panel show track-path)))))

(defn scroll-to-track
  "Makes sure the specified track is visible (it has just been imported
  or copied), or give the user a warning that the current track
  filters have hidden it. `track` is likely to be shockingly
  incomplete since it was just imported, so it must be refreshed from
  `show` rather than via `latest-track`."
  [show track]
  (let [signature (:signature track)
        show      (latest-show show)
        track     (get-in show [:tracks signature])
        tracks    (seesaw/select (:frame show) [:#tracks])]
    (if (some #(= signature %) (:visible show))
      (seesaw/invoke-later (seesaw/scroll! tracks :to (.getBounds ^JPanel (:panel track))))
      (seesaw/alert (:frame show)
                    (str "The track \"" (su/display-title track) "\" is currently hidden by your filters.\r\n"
                          "To continue working with it, you will need to adjust the filters.")
                     :title "Can't Scroll to Hidden Track" :type :info))))

(defn- import-track
  "Imports the supplied track map into the show, after validating that
  all required parts are present."
  [show track]
  (let [missing-elements (filter (fn [k] (not (get track k)))
                                 [:signature :metadata :beat-grid :preview :detail])]
    (if (seq missing-elements)
      (seesaw/alert (:frame show)
                    (str "<html>Unable to import track, missing required elements:<br>"
                         (str/join ", " (map name missing-elements)))
                    :title "Track Import Failed" :type :error)
      (let [{:keys [filesystem]}                 show
            {:keys [signature metadata cue-list
                    beat-grid preview detail art
                    song-structure]}             track
            track-root                           (su/build-filesystem-path filesystem "tracks" signature)]
        (Files/createDirectories track-root (make-array java.nio.file.attribute.FileAttribute 0))
        (su/write-edn-path metadata (.resolve track-root "metadata.edn"))
        (when cue-list
          (write-cue-list track-root cue-list))
        (when beat-grid (write-beat-grid track-root beat-grid))
        (when preview (write-preview track-root preview))
        (when detail (write-detail track-root detail))
        (when art (write-art track-root art))
        (when song-structure (write-song-structure track-root song-structure))
        (when-let [track-contents (:contents track)]  ; In case this is being copied from an existing show.
          (su/write-edn-path track-contents (.resolve track-root "contents.edn")))
        (create-track-panel show track-root)
        (su/update-row-visibility show)
        (scroll-to-track show track)
        (sim/recompute-track-models)
        ;; Finally, flush the show to move the newly-created filesystem elements into the actual ZIP file. This
        ;; both protects against loss due to a crash, and also works around a Java bug which is creating temp files
        ;; in the same folder as the ZIP file when FileChannel/open is used with a ZIP filesystem.
        (su/flush-show! show)))))

(defn- import-from-player
  "Imports the track loaded on the specified player to the show."
  [show ^Long player]
  (let [signature (.getLatestSignatureFor signature-finder player)]
    (if (track-present? show signature)
      (seesaw/alert (:frame show) (str "Track on Player " player " is already in the Show.")
                    :title "Can’t Re-import Track" :type :error)
      (let [metadata        (.getLatestMetadataFor metadata-finder player)
            cue-list        (.getCueList metadata)
            beat-grid       (.getLatestBeatGridFor beatgrid-finder player)
            preview         (.getLatestPreviewFor waveform-finder player)
            detail          (.getLatestDetailFor waveform-finder player)
            art             (.getLatestArtFor art-finder player)
            structure-tag   (.getLatestTrackAnalysisFor analysis-finder player ".EXT" "PSSI")
            structure-bytes (when structure-tag (._raw_body structure-tag))]
        (if (not= signature (.getLatestSignatureFor signature-finder player))
          (seesaw/alert (:frame show) (str "Track on Player " player " Changed during Attempted Import.")
                        :title "Track Import Failed" :type :error)
          (do
            (import-track show {:signature      signature
                                :metadata       (extract-metadata metadata)
                                :cue-list       cue-list
                                :beat-grid      beat-grid
                                :preview        preview
                                :detail         detail
                                :art            art
                                :song-structure structure-bytes})
            (update-player-item-signature (SignatureUpdate. player signature) show)))))))

(defn- find-anlz-file
  "Given a database and track object, returns the file in which the
  track's analysis data can be found. If `ext?` is truthy, returns the
  extended analysis path, but if `ext2` has the value `2`, returns the
  second extended analysis path."
  ^File [^Database database ^RekordboxPdb$TrackRow track-row ext?]
  (let [volume    (.. database sourceFile getParentFile getParentFile getParentFile)
        raw-path  (Database/getText (.analyzePath track-row))
        subs-path (if ext?
                    (str/replace raw-path #"DAT$" (if (= 2 ext?) "2EX" "EXT"))
                    raw-path)]
    (.. volume toPath (resolve (subs subs-path 1)) toFile)))

(defn- find-waveform-preview
  "Helper function to find the best-available waveform preview, if any."
  [data-ref anlz ext ex2]
  (if ex2
    (try
      (WaveformPreview. data-ref ex2 WaveformFinder$WaveformStyle/THREE_BAND)
      (catch IllegalStateException _
        (timbre/info "No 3-band preview waveform found, checking for RGB version.")
        (find-waveform-preview data-ref anlz ext nil)))
    (if ext
      (try
        (WaveformPreview. data-ref ext WaveformFinder$WaveformStyle/RGB)
        (catch IllegalStateException _
          (timbre/info "No RGB preview waveform found, checking for blue version.")
          (find-waveform-preview data-ref anlz nil nil)))
      (when anlz (WaveformPreview. data-ref anlz WaveformFinder$WaveformStyle/BLUE)))))

(defn- find-waveform-detail
  "Helper function to find the best-available waveform detail, if any."
  [data-ref ext ex2]
  (if ex2
    (try
      (WaveformDetail. data-ref ex2 WaveformFinder$WaveformStyle/THREE_BAND)
        (catch IllegalStateException _
          (timbre/info "No 3-band waveform detail found, checking for RGB version.")
          (find-waveform-detail data-ref ext nil)))
    (when ext
      (try
        (WaveformDetail. data-ref ext WaveformFinder$WaveformStyle/RGB)
        (catch IllegalStateException _
          (timbre/info "No RGB waveform detail found, checking for blue version.")
          (WaveformDetail. data-ref ext WaveformFinder$WaveformStyle/BLUE))))))

(defn- find-art
  "Given a database and track object, returns the track's album art, if
  it has any."
  ^AlbumArt [^Database database ^RekordboxPdb$TrackRow track-row]
  (let [art-id (long (.artworkId track-row))]
    (when (pos? art-id)
      (when-let [^RekordboxPdb$ArtworkRow art-row (.. database artworkIndex (get art-id))]
        (let [volume   (.. database sourceFile getParentFile getParentFile getParentFile)
              art-path (Database/getText (.path art-row))
              art-file (.. volume toPath (resolve (subs art-path 1)) toFile)
              data-ref (DataReference. 0 CdjStatus$TrackSourceSlot/COLLECTION art-id)]
          (AlbumArt. data-ref art-file))))))

(defn- find-song-structure
  "Helper function to find the raw bytes of the song structure tag, if
  one is present in the extended track analysis file."
  [^RekordboxAnlz ext]
  (when-let [^RekordboxAnlz$TaggedSection tag (->> (.sections ext)
                                                   (filter (fn [^RekordboxAnlz$TaggedSection sec]
                                                             (instance? RekordboxAnlz$SongStructureTag (.body sec))))
                                                   first)]
    (._raw_body tag)))

(defn- import-from-media
  "Imports a track that has been parsed from a local media export, being
  very careful to close the underlying track analysis files no matter
  how we exit. If `silent?` is true, will return the names of tracks
  that are skipped because they are already in the show rather than
  displaying an error dialog about them."
  ([show database track-row]
   (import-from-media show database track-row false))
  ([show database ^RekordboxPdb$TrackRow track-row silent?]
   (let [anlz-file (find-anlz-file database track-row false)
         ext-file  (find-anlz-file database track-row true)
         ex2-file  (when (= WaveformFinder$WaveformStyle/THREE_BAND (.getPreferredStyle waveform-finder))
                     (find-anlz-file database track-row 2))
         anlz-atom (atom nil)
         ext-atom  (atom nil)
         ex2-atom  (atom nil)]
     (try
       (let [^RekordboxAnlz anlz (reset! anlz-atom (when (and anlz-file (.canRead anlz-file))
                                                     (RekordboxAnlz.
                                                      (RandomAccessFileKaitaiStream. (.getAbsolutePath anlz-file)))))
             ^RekordboxAnlz ext  (reset! ext-atom (when (and ext-file (.canRead ext-file))
                                                    (RekordboxAnlz.
                                                     (RandomAccessFileKaitaiStream. (.getAbsolutePath ext-file)))))
             ^RekordboxAnlz ex2  (reset! ex2-atom (when (and ex2-file (.canRead ex2-file))
                                                    (RekordboxAnlz.
                                                     (RandomAccessFileKaitaiStream. (.getAbsolutePath ex2-file)))))
             cue-tags            (or ext anlz)
             cue-list            (when cue-tags (CueList. cue-tags))
             data-ref            (DataReference. 0 CdjStatus$TrackSourceSlot/COLLECTION (.id track-row))
             metadata            (TrackMetadata. data-ref database cue-list)
             beat-grid           (when anlz (BeatGrid. data-ref anlz))
             preview             (find-waveform-preview data-ref anlz ext ex2)
             detail              (find-waveform-detail data-ref ext ex2)
             art                 (find-art database track-row)
             song-structure      (when ext (find-song-structure ext))
             signature           (.computeTrackSignature signature-finder (.getTitle metadata) (.getArtist metadata)
                                                         (.getDuration metadata) detail beat-grid)]
         (if (and signature (track-present? show signature))
           (if silent?
             (.getTitle metadata) ; Just return the track name rather than displaying an error about it.
             (seesaw/alert (:frame show) (str "Track \"" (.getTitle metadata) "\" is already in the Show.")
                           :title "Can’t Re-import Track" :type :error))
           (do (import-track show {:signature      signature
                                   :metadata       (extract-metadata metadata)
                                   :cue-list       cue-list
                                   :beat-grid      beat-grid
                                   :preview        preview
                                   :detail         detail
                                   :art            art
                                   :song-structure song-structure})
               (refresh-signatures show)
               nil)))  ; Inform silent mode callers we succeeded.
       (finally
         (try
           (when @anlz-atom (.. ^RekordboxAnlz @anlz-atom _io close))
           (catch Throwable t
             (timbre/error t "Problem closing parsed rekordbox file" anlz-file)))
         (try
           (when @ext-atom (.. ^RekordboxAnlz @ext-atom _io close))
           (catch Throwable t
             (timbre/error t "Problem closing parsed rekordbox file" ext-file)))
         (try
           (when @ex2-atom (.. ^RekordboxAnlz @ex2-atom _io close))
           (catch Throwable t
             (timbre/error t "Problem closing parsed rekordbox file" ex2-file))))))))

(defn- save-show
  "Saves the show to its file, making sure the latest changes are safely
  recorded. If `reopen?` is truthy, reopens the show filesystem for
  continued use."
  [show reopen?]
  (let [^JFrame window (:frame show)]
    (swap-show! show assoc-in [:contents :window]
                [(.getX window) (.getY window) (.getWidth window) (.getHeight window)]))
  (let [show                                           (latest-show show)
        {:keys [contents file ^FileSystem filesystem]} show
        triggers                                       ((requiring-resolve
                                                         'beat-link-trigger.triggers/trigger-configuration-for-show)
                                                        show)]
    (try
      (su/write-edn-path (assoc contents :triggers triggers :version su/show-file-version)
                         (su/build-filesystem-path filesystem "contents.edn"))
      (save-track-contents show)
      (.close filesystem)
      (catch Throwable t
        (timbre/error t "Problem saving" file)
        (throw t))
      (finally
        (when reopen?
          (let [[reopened-filesystem] (su/open-show-filesystem file)]
            (swap-show! show assoc :filesystem reopened-filesystem)))))))

(defn- save-show-as
  "Closes the show filesystem to flush changes to disk, copies the file
  to the specified destination, then reopens it."
  [show ^File as-file]
  (let [show                 (latest-show show)
        {:keys [^File file]} show]
    (try
      (save-show show false)
      (Files/copy (.toPath file) (.toPath as-file) su/copy-options-replace-existing)
      (catch Throwable t
        (timbre/error t "Problem saving" file "as" as-file)
        (throw t))
      (finally
        (let [[reopened-filesystem] (su/open-show-filesystem file)]
          (swap-show! show assoc :filesystem reopened-filesystem))))))

(defn build-raw-trigger-action
  "Creates the menu action to add a raw trigger to the Triggers window
  that is managed by this show."
  [show]
  (seesaw/action :handler (fn [_]
                            (try
                              ((requiring-resolve 'beat-link-trigger.triggers/create-trigger-for-show) show)
                              (catch Throwable t
                                (timbre/error t "Problem Creating Raw Trigger")
                                (seesaw/alert (:frame show) (str "<html>Unable to Create Raw Trigger.<br><br>" t)
                                              :title "Problem Creating Raw Trigger" :type :error))))
                 :name "New Raw Trigger"
                 :key "menu R"))

(defn- build-save-action
  "Creates the menu action to save a show window, making sure the file
  on disk is up-to-date."
  [show]
  (seesaw/action :handler (fn [_]
                            (try
                              (save-show show true)
                              (catch Throwable t
                                (timbre/error t "Problem Saving Show")
                                (seesaw/alert (:frame show) (str "<html>Unable to Save As " (:file show)".<br><br>" t)
                                              :title "Problem Saving Show" :type :error))))
                 :name "Save"
                 :key "menu S"))

(defn- build-save-as-action
  "Creates the menu action to save a show window to a new file, given
  the show map."
  [show]
  (seesaw/action :handler (fn [_]
                            (let [extension (util/extension-for-file-type :show)]
                              (when-let [file (chooser/choose-file (:frame show) :type :save
                                                                   :all-files? false
                                                                   :filters [["Beat Link Trigger Show files"
                                                                              [extension]]])]
                                (if (get (su/get-open-shows) file)
                                  (seesaw/alert (:frame show) "Cannot Replace an Open Show."
                                                :title "Destination is Already Open" :type :error)
                                  (when-let [file (util/confirm-overwrite-file file extension (:frame show))]
                                    (try
                                      (save-show-as show file)
                                      (catch Throwable t
                                        (timbre/error t "Problem Saving Show as" file)
                                        (seesaw/alert (:frame show) (str "<html>Unable to Save As " file ".<br><br>" t)
                                                      :title "Problem Saving Show Copy" :type :error))))))))
                 :name "Save a Copy"))

(defn- import-playlist-tracks
  "Imports all the track IDs from a playlist into a show from offline
  media, presenting a progress bar which allows the process to be
  stopped, and showing a list of all skipped tracks at the end rather
  than popping up an error dialog for each."
  [show ^Database database track-ids]
  (let [continue?    (atom true)
        skipped      (atom [])
        progress     (seesaw/progress-bar :min 0 :max (count track-ids))
        panel        (mig/mig-panel
                      :items [[(seesaw/label :text "Importing Playlist Tracks.")
                               "span, wrap 20"]
                              [progress "grow, span, wrap 16"]
                              [(seesaw/button :text "Stop"
                                              :listen [:action-performed
                                                       (fn [e]
                                                         (reset! continue? false)
                                                         (seesaw/config! e :enabled? false
                                                                         :text "Stopping…"))])
                               "span, align center"]])
        ^JFrame root (seesaw/frame :title "Offline Media Import" :on-close :dispose :content panel)]
    (when (seq track-ids)
      (seesaw/listen root :window-closed (fn [_] (reset! continue? false)))
      (seesaw/pack! root)
      (.setLocationRelativeTo root (:frame show))
      (.setAlwaysOnTop root true)
      (seesaw/show! root)
      (future
        (try
          (loop [id   (first track-ids)
                 left (rest track-ids)
                 done 1]
            (when-let [track (.. database trackIndex (get (long id)))]
              (seesaw/invoke-later
                (when-let [skipped-name (import-from-media (latest-show show) database track true)]
                  (swap! skipped conj skipped-name))))
            (seesaw/invoke-now
              (seesaw/value! progress done))
            (when (and @continue? (seq left))
              (recur (first left)
                     (rest left)
                     (inc done))))
          (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING))
          (when (seq @skipped)
            (seesaw/invoke-later
              (seesaw/alert (:frame show)
                            [(seesaw/scrollable
                              (seesaw/label :text (str "<html><p>The following tracks were not imported because "
                                                       "they were already in the show:</p><ul><li>"
                                                       (str/join "</li><li>" (sort @skipped)) "</li></ul>"))
                              :size [640 :by 480])]
                            :type :info)))
          (catch Exception e
            (timbre/error e "Problem Importing Playlist Tracks")
            (seesaw/invoke-later
              (seesaw/alert (str "<html>Unable to Import Playlist Tracks:<br><br>" (.getMessage e)
                                 "<br><br>See the log file for more details.")
                            :title "Problem Importing Playlist Tracks" :type :error)
              (.dispose root))))))))

(defn- build-import-offline-action
  "Creates the menu action to import a track from offline media, given
  the show map."
  [show]
  (seesaw/action :handler (fn [_]
                            (loop [show      (latest-show show)
                                   database (:import-database show)
                                   playlist? false]
                              (let [[^Database database chosen] (if playlist?
                                                                  (loader/choose-local-playlist (:frame show) database
                                                                                                "Change Media"
                                                                                                "Import Single Track")
                                                                  (loader/choose-local-track (:frame show) database
                                                                                             "Change Media"
                                                                                             "Import Playlist"))]
                                (when database (swap-show! show assoc :import-database database))
                                (cond
                                  (= "Change Media" chosen) ; User wants to change media.
                                  (do
                                    (try
                                      (when database (.close database))
                                      (catch Throwable t
                                        (timbre/error t "Problem closing offline media database.")))
                                    (swap-show! show dissoc :import-database)
                                    (recur (latest-show show) nil playlist?))

                                  (= "Import Single Track" chosen) ; User wants to switch to importing a single track.
                                  (recur show database false)

                                  (= "Import Playlist" chosen) ; User wants to switch to importing a playlist.
                                  (recur show database true)

                                  (some? chosen)
                                  (try
                                    (if (instance? RekordboxPdb$TrackRow chosen)
                                      (import-from-media (latest-show show) database chosen)
                                      (import-playlist-tracks show database chosen))
                                    (catch Throwable t
                                      (timbre/error t "Problem importing from offline media.")
                                      (seesaw/alert (:frame show) (str "<html>Unable to Import.<br><br>" t)
                                                    :title "Problem Finding Track Metadata" :type :error)))))))
                 :name "from Offline Media"
                 :key "menu M"))

(defn safe-check-for-player
  "Returns truthy when the specified player is found on the network, and
  does not throw an exception if we are deeply offline (not even the
  [`DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
  is running because BLT was launched in offline mode)."
  [player]
  (when (.isRunning device-finder) (.getLatestAnnouncementFrom device-finder player)))

(defn- build-import-player-action
  "Creates the menu action to import a track from a player, given the
  show map and the player number. Enables or disables as appropriate,
  with text explaining why it is disabled (but only if visible, to
  avoid mysterious extra width in the menu)."
  [show player]
  (let [visible? (safe-check-for-player player)
        reason   (describe-disabled-reason show (when (.isRunning signature-finder)
                                                  (.getLatestSignatureFor signature-finder ^Long player)))]
    (seesaw/action :handler (fn [_] (import-from-player (latest-show show) player))
                   :name (str "from Player " player (when visible? reason))
                   :enabled? (nil? reason)
                   :key (str "menu " player))))

(defn- build-import-submenu-items
  "Creates the submenu items for importing tracks from all available players
  and offline media, given the show map."
  [show]
  (concat (map (fn [player]
                 (seesaw/menu-item :action (build-import-player-action show player)
                                   :visible? (safe-check-for-player player)))
               (map inc (range 6)))
          [(build-import-offline-action show)]))

(defn- build-close-action
  "Creates the menu action to close a show window, given the show map."
  [show]
  (seesaw/action :handler (fn [_]
                            (seesaw/invoke-later
                             (let [^JFrame frame (:frame show)]
                               (.dispatchEvent frame (WindowEvent. frame WindowEvent/WINDOW_CLOSING)))))
                 :name "Close"))

(defn global-editor-update-fn
  "The function called to propagate necessary changes when a show global
  expression has been updated."
  [show kind]
  (when (= :setup kind)
    (when (util/online?)
      (run-global-function show :offline nil true))
    (run-global-function show :shutdown nil true)
    (reset! (:expression-globals show) {})
    (swap-show! show dissoc :cue-builders)
    (run-global-function show :setup nil true)
    (when (util/online?)
      (run-global-function show :online nil true)))
  (update-tracks-global-expression-icons show))

(defn build-global-editor-action
  "Creates an action which edits one of a show's global expressions."
  [show kind]
  (seesaw/action :handler (fn [_] (editors/show-show-editor kind (latest-show show) nil (:frame show)
                                                            (partial global-editor-update-fn show kind)))
                 :name (str "Edit " (get-in @editors/global-show-editors [kind :title]))
                 :tip (get-in @editors/global-show-editors [kind :tip])
                 :icon (seesaw/icon (if (str/blank? (get-in show [:contents :expressions kind]))
                                      "images/Gear-outline.png"
                                      "images/Gear-icon.png"))))

(defn- build-phrase-menu
  "Creates the Phrases menu. Pulled out as a function so that menu can be
  re-created when the show is taken out of block-tracks mode."
  ^JMenu [show]
  (seesaw/menu :text "Phrases"
               :id :phrases-menu
               :items [(seesaw/action :handler (fn [_] (phrases/new-phrase show))
                                      :name "New Phrase Trigger"
                                      :key "menu T"
                                      :tip "Adds a new phrase trigger row")
                       (seesaw/action :handler (fn [_] (phrases/sort-phrases show))
                                      :name "Sort by Comments"
                                      :tip "Sorts all phrase trigger rows by their comment text")]))


(defn- build-show-menubar
  "Creates the menu bar for a show window, given the show map."
  [show]
  (let [^File file       (:file show)
        title            (str "Expression Globals for Show " (util/trim-extension (.getPath file)))
        inspect-action   (seesaw/action :handler (fn [_] (try
                                                           (inspector/inspect @(:expression-globals show)
                                                                              :window-name title)
                                                           (catch StackOverflowError _
                                                             (util/inspect-overflowed))
                                                           (catch Throwable t
                                                             (util/inspect-failed t))))
                                        :name "Inspect Expression Globals"
                                        :tip "Examine any values set as globals by any Track Expressions.")
        ex-report-action (seesaw/action :handler (fn [_]
                                                   (when (help/help-server)
                                                     (clojure.java.browse/browse-url (su/expression-report-link file))))
                                        :name "Expression Report"
                                        :tip "Open a web page describing all expressions in the show.")
        attach-action    (seesaw/action :handler (fn [_] (su/manage-attachments show))
                                        :name "Manage Attachments"
                                        :tip "Allow files to be attached for use by show expressions.")
        actions-item     (seesaw/checkbox-menu-item :text "Enable Report Actions"
                                                    :tip (str "Allow buttons in reports to affect the show: "
                                                              "use only on secure networks."))]
    (seesaw/listen actions-item :item-state-changed
                   (fn [^ItemEvent e]
                     (swap-show! show assoc :actions-enabled (= (.getStateChange e) ItemEvent/SELECTED))))
    (seesaw/menubar :items [(seesaw/menu :text "File"
                                         :items [(build-raw-trigger-action show) (seesaw/separator)
                                                 (build-save-action show) (build-save-as-action show)
                                                 (seesaw/separator) ex-report-action actions-item
                                                 (seesaw/separator) attach-action
                                                 (seesaw/separator) (build-close-action show)])
                            (seesaw/menu :text "Tracks"
                                         :id :tracks-menu
                                         :items (concat [(:import-menu show)]
                                                        (map (partial build-global-editor-action show)
                                                             (keys @editors/global-show-editors))
                                                        [(seesaw/separator) inspect-action]))
                            (build-phrase-menu show)
                            (menus/build-help-menu)])))

(defn- update-player-item-visibility
  "Makes a player's entry in the import menu visible or invisible, given
  the device announcement describing the player and the show map."
  [^DeviceAnnouncement announcement show visible?]
  (let [show                           (latest-show show)
        ^javax.swing.JMenu import-menu (:import-menu show)
        player                         (.getDeviceNumber announcement)]
    (when (and (< player 7)  ; Ignore non-players, and attempts to make players visible when we are offline.
               (or (util/online?) (not visible?)))
      #_(timbre/info "Updating player" player "menu item visibility to" visible?)
      (let [^javax.swing.JMenuItem item (.getItem import-menu (dec player))]
        (when visible?  ; If we are becoming visible, first update the signature information we'd been ignoring before.
          (let [reason (describe-disabled-reason show (when (.isRunning signature-finder)
                                                        (.getLatestSignatureFor signature-finder player)))]
            (.setText item (str "from Player " player reason))))
        (.setVisible item visible?)))))

(defn- set-enabled-default
  "Update the show's default enabled state for tracks that do not set
  their own."
  [show enabled]
  (swap-show! show assoc-in [:contents :enabled] enabled)
  (repaint-all-track-states show))

(defn- set-loaded-only
  "Update the show UI so that all tracks or only loaded tracks are
  visible."
  [show loaded-only?]
  (swap-show! show assoc-in [:contents :loaded-only] loaded-only?)
  (su/update-row-visibility show))

(defn- filter-text-changed
  "Update the show UI so that only tracks matching the specified filter
  text, if any, are visible."
  [show text]
  (swap-show! show assoc-in [:contents :filter] (str/lower-case text))
  (su/update-row-visibility show))

(defn- resize-track-panels
  "Called when the show window has resized, to put appropriate
  constraints on the columns of the track panels."
  [panels width]
  (let [constraints (track-panel-constraints width)]
    (doseq [[^JPanel panel signature-or-uuid] panels]
      (when (string? signature-or-uuid)  ; It's a track panel.
        (seesaw/config! panel :constraints constraints)
        (.revalidate panel)))))

(defn- create-show-window
  "Create and show a new show window on the specified file."
  [^File file]
  (util/load-fonts)
  (when (util/online?)  ; Start out the finders that aren't otherwise guaranteed to be running.
    (.start util/time-finder)
    (.start signature-finder))
  (let [[^FileSystem filesystem contents] (su/open-show-filesystem file)]
    (try
      (let [^JFrame root    (seesaw/frame :title (str "Beat Link Show: " (util/trim-extension (.getPath file)))
                                          :on-close :nothing)
            import-menu     (seesaw/menu :text "Import Track")
            show            {:creating           true
                             :frame              root
                             :expression-globals (atom {})
                             :import-menu        import-menu
                             :file               file
                             :filesystem         filesystem
                             :contents           contents
                             :tracks             {}  ; Lots of info about each track, including loaded metadata.
                             :phrases            {}  ; Non-saved runtime state about each phrase trigger.
                             :panels             {}  ; Maps from JPanel to track signature or phrase UUID, for resizing.
                             :loaded             {}  ; Map from player number to signature that has been reported loaded.
                             :playing            {}  ; Map from player number to signature that has been reported playing.
                             :playing-phrases    {} ; Map from player # to map of phrase UUID to beat fit information.
                             :visible            []  ; The visible (through filters) track signatures in sorted order.
                             :visible-phrases    []}  ; Visible (through filters) phrase trigger UUIDs, in sorted order.
            tracks          (seesaw/vertical-panel :id :tracks)
            tracks-scroll   (seesaw/scrollable tracks)
            enabled-default (seesaw/combobox :id :default-enabled :model ["Never" "On-Air" "Master" "Custom" "Always"]
                                             :listen [:item-state-changed
                                                      #(set-enabled-default show (seesaw/selection %))])
            loaded-only     (seesaw/checkbox :id :loaded-only :text "Loaded Only"
                                             :selected? (boolean (:loaded-only contents)) :visible? (util/online?)
                                             :listen [:item-state-changed #(set-loaded-only show (seesaw/value %))])
            filter-field    (seesaw/text (:filter contents ""))
            top-panel       (mig/mig-panel :background "#aaa"
                                           :items [[(seesaw/label :text "Enabled Default:")]
                                                   [enabled-default]
                                                   [(seesaw/label :text "") "pushx 1, growx 1"]
                                                   [(seesaw/label :text "Filter:") "gap unrelated"]
                                                   [filter-field "pushx 4, growx 4"]
                                                   [loaded-only "hidemode 3"]])
            layout          (seesaw/border-panel :north top-panel :center tracks-scroll)
            dev-listener    (reify DeviceAnnouncementListener  ; Update the import submenu as players come and go.
                              (deviceFound [_this announcement]
                                (update-player-item-visibility announcement show true))
                              (deviceLost [_this announcement]
                                (update-player-item-visibility announcement show false)))
            mf-listener     (reify LifecycleListener  ; Hide or show all players if we go offline or online.
                              (started [_this _sender]
                                (.start util/time-finder)  ; We need this too, and it doesn't auto-restart.
                                (.start signature-finder)  ; In case we started out offline.
                                (seesaw/invoke-later
                                  (seesaw/show! loaded-only)
                                  (doseq [announcement (.getCurrentDevices device-finder)]
                                    (update-player-item-visibility announcement show true))
                                  (su/update-row-visibility show)
                                  (cues/update-cue-window-online-status show true)))
                              (stopped [_this _sender]
                                (seesaw/invoke-later
                                  (seesaw/hide! loaded-only)
                                  (doseq [announcement (.getCurrentDevices device-finder)]
                                    (update-player-item-visibility announcement show false))
                                  (su/update-row-visibility show)
                                  (cues/update-cue-window-online-status show false))))
            sig-listener    (reify SignatureListener  ; Update the import submenu as tracks come and go.
                              (signatureChanged [_this sig-update]
                                (update-player-item-signature sig-update show)
                                (seesaw/invoke-later (su/update-row-visibility show))))
            ss-listener     (reify AnalysisTagListener  ; Add newly-available phrase analysis info to tracks and show.
                              (analysisChanged [_this tag-update]
                                (if-let [song-structure (.taggedSection tag-update)]
                                  (do
                                    (when-let [signature (.getLatestSignatureFor signature-finder (.player tag-update))]
                                      (upgrade-song-structure show song-structure signature))
                                    (phrases/upgrade-song-structure (.player tag-update) (.body song-structure)))
                                  (phrases/clear-song-structure (.player tag-update)))))
            update-listener (reify DeviceUpdateListener
                              (received [_this status]
                                (try
                                  (when (and (.isRunning signature-finder)  ; Ignore packets when not yet fully online.
                                             (instance? CdjStatus status))  ; We only want CDJ information.
                                    (let [signature (.getLatestSignatureFor signature-finder status)
                                          show      (latest-show show)
                                          track     (get-in (latest-show show) [:tracks signature])]
                                      (when track
                                        (run-custom-enabled show track status))
                                      (when show  ; In case we are still creating it.
                                        (update-show-status show signature track status))))
                                  (catch Exception e
                                    (timbre/error e "Problem responding to Player status packet.")))))
            window-name     (str "show-" (.getPath file))
            close-fn        (fn [force? quitting?]
                              ;; Closes the show window and performs all necessary cleanup. If `force?` is true,
                              ;; will do so even in the presence of windows with unsaved user changes. Otherwise
                              ;; prompts the user about all unsaved changes, giving them a chance to veto the
                              ;; closure. Returns truthy if the show was closed.
                              (let [show     (latest-show show)
                                    triggers ((requiring-resolve 'beat-link-trigger.triggers/get-triggers) show)]
                                (when (and (every? (partial close-track-editors? force?) (vals (:tracks show)))
                                           (every? (partial editors/close-editor? force?)
                                                   (vals (:expression-editors show)))
                                           (every? (partial (requiring-resolve
                                                             'beat-link-trigger.triggers/close-trigger-editors?)
                                                            force?)
                                                   triggers))
                                  (.removeUpdateListener virtual-cdj update-listener)
                                  (.removeDeviceAnnouncementListener device-finder dev-listener)
                                  (.removeLifecycleListener metadata-finder mf-listener)
                                  (.removeSignatureListener signature-finder sig-listener)
                                  (.removeAnalysisTagListener analysis-finder ss-listener ".EXT" "PSSI")
                                  (doseq [track (vals (:tracks show))]
                                    (cleanup-track true track))
                                  (doseq [phrase (vals (get-in show [:contents :phrases]))]
                                    (phrases/cleanup-phrase true show phrase))
                                  (when (util/online?) (run-global-function show :offline nil (not force?)))
                                  (run-global-function show :shutdown nil (not force?))
                                  (try
                                    (save-show show false)
                                    ;; This has to come after the show is saved, because `save-show` needs to
                                    ;; save the triggers too.
                                    (doseq [trigger triggers]
                                      ((requiring-resolve 'beat-link-trigger.triggers/delete-trigger) true trigger))
                                    (catch Throwable t
                                      (timbre/error t "Problem closing Show file.")
                                      (seesaw/alert root (str "<html>Problem Closing Show.<br><br>" t)
                                                    :title "Problem Closing Show" :type :error)))
                                  (when-let [^Database database (:import-database show)]
                                    (.close database))
                                  (seesaw/invoke-later
                                    ;; Gives windows time to close first, so they don't recreate a broken show.
                                    (su/remove-file-from-open-shows! file)
                                    (sim/recompute-track-models))
                                  ;; Remove the instruction to reopen this window the next time the program runs,
                                  ;; unless we are closing it because the application is quitting.
                                  (when-not quitting? (swap! util/window-positions dissoc window-name))
                                  (.dispose root)
                                  true)))]
        (su/add-file-and-show-to-open-shows! file (assoc show :close close-fn :default-ui layout))
        (.addDeviceAnnouncementListener device-finder dev-listener)
        (.addLifecycleListener metadata-finder mf-listener)
        (.addSignatureListener signature-finder sig-listener)
        (.addAnalysisTagListener analysis-finder ss-listener ".EXT" "PSSI")
        (.addUpdateListener virtual-cdj update-listener)
        (seesaw/config! import-menu :items (build-import-submenu-items show))
        (seesaw/config! root :menubar (build-show-menubar show) :content layout)

        ;; Need to compile the show expressions before building the tracks and triggers,
        ;; so shared functions are available.
        (su/load-attachments show)
        (doseq [[kind expr] (editors/sort-setup-to-front (get-in show [:contents :expressions]))]
          (let [editor-info (get @editors/global-show-editors kind)]
            (try
              (swap-show! show assoc-in [:expression-fns kind]
                          (if (= kind :shared)
                            (expressions/define-shared-functions expr (editors/show-editor-title kind show nil))
                            (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                               (editors/show-editor-title kind show nil)
                                                               (:no-locals? editor-info))))
              (catch Exception e
                (timbre/error e (str "Problem parsing " (:title editor-info)
                                     " when loading Show. Expression:\n" expr "\n"))
                (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                   "Check the log file for details.")
                              :title "Exception during Clojure evaluation" :type :error)))))

        (create-track-panels show)
        (phrases/create-phrase-panels show)
        (su/update-row-visibility show)
        (refresh-signatures show)
        (seesaw/listen filter-field #{:remove-update :insert-update :changed-update}
                       (fn [e] (filter-text-changed show (seesaw/text e))))
        (attach-track-custom-editor-opener show nil enabled-default :enabled nil)
        (seesaw/selection! enabled-default (:enabled contents "Always"))
        (.setSize root 900 600)  ; Our default size if there isn't a position stored in the file.
        (su/restore-window-position root contents)
        (seesaw/listen root
                       :window-closing (fn [_] (close-fn false false))
                       #{:component-moved :component-resized}
                       (fn [^java.awt.event.ComponentEvent e]
                         (util/save-window-position root window-name)
                         (when (= (.getID e) java.awt.event.ComponentEvent/COMPONENT_RESIZED)
                           (let [rows (:panels (latest-show show))]
                             (resize-track-panels rows (.getWidth root))
                             (phrases/resize-phrase-panels rows (.getWidth root))))))
        (let [rows (:panels (latest-show show))]
          (resize-track-panels rows (.getWidth root))
          (phrases/resize-phrase-panels rows (.getWidth root)))
        (run-global-function show :setup nil true)
        (when (util/online?) (run-global-function show :online nil true))
        (swap-show! show dissoc :creating)
        (update-tracks-global-expression-icons show)
        (seesaw/show! root)
        (doseq [trigger-map (:triggers contents)]
          ((requiring-resolve 'beat-link-trigger.triggers/create-trigger-for-show) show trigger-map))
        (sim/recompute-track-models))
      (catch Throwable t
        (su/remove-file-from-open-shows! file)
        (.close filesystem)
        (throw t)))))

;;; External API for creating, opening, reopening, and closing shows:

(defn- open-internal
  "Opens a show file. If it is already open, just brings the window to
  the front. Returns truthy if the file was newly opened."
  [^JFrame parent ^File file]
  (let [file (.getCanonicalFile file)]
    (try
      (if-let [existing (latest-show file)]
        (.toFront ^JFrame (:frame existing))
        (do (create-show-window file)
            true))
      (catch Exception e
        (timbre/error e "Unable to open Show" file)
        (seesaw/alert parent (str "<html>Unable to Open Show " file "<br><br>" e)
                      :title "Problem Opening File" :type :error)
        false))))

(defn open
  "Prompts the user to choose a show file and tries to open it. If it
  was already open, just brings the window to the front.

  If the show has a saved window position which fits in the current
  screen configuration, it will be reopened in that position.
  Otherwise, if a non-`nil` `parent` window is supplied, the show's
  window will be centered on that, and if none of those situations
  apply it will be centered on the screen."
  [parent]
(when-let [file (chooser/choose-file parent :type :open
                                     :all-files? false
                                     :filters [["Beat Link Trigger Show files"
                                                [(util/extension-for-file-type :show)]]])]
  (open-internal parent file)))

(defn reopen-previous-shows
  "Tries to reopen any shows that were open the last time the user quit."
  []
  (doseq [window (keys @util/window-positions)]
    (when (and (string? window)
               (.startsWith ^String window "show-"))
      (when-not (open-internal nil (io/file (subs window 5)))
        (swap! util/window-positions dissoc window)))))  ; Remove saved position if show is no longer available.

(defn reopen-specified-shows
  "Tries to open any shows that were specified as command-line arguments."
  [shows]
  (doseq [show shows]
    (let [file (io/file show)]
      (if (= (util/file-type file) :show)
        (open-internal nil file)
        (seesaw/invoke-now
          (seesaw/alert nil (str "File " show " does not have requried extension: ."
                                 (util/extension-for-file-type :show))
                        :title "Unable to Open Requested Show" :type :error))))))

(defn new
  "Creates a new show file and opens a window on it. If a non-`nil`
  `parent` window is supplied, the new show's window will be centered
  on that, otherwise it will be centered on the screen."
  [parent]
  (let [extension (util/extension-for-file-type :show)]
    (when-let [^File file (chooser/choose-file parent :type :save
                                               :all-files? false
                                               :filters [["Beat Link Trigger Show files"
                                                          [extension]]])]
      (let [file (.getCanonicalFile file)]
        (if (latest-show file)
          (seesaw/alert parent "Cannot Replace an Open Show."
                        :title "Show is Already Open" :type :error)
          (when-let [file (util/confirm-overwrite-file file extension parent)]
            (try
              (Files/deleteIfExists (.toPath file))
              (let [file-uri (.toUri (.toPath file))]
                (with-open [filesystem (FileSystems/newFileSystem (java.net.URI. (str "jar:" (.getScheme file-uri))
                                                                                 (.getPath file-uri) nil)
                                                                  {"create" "true"})]
                  (su/write-edn-path {:type ::show :version su/show-file-version}
                                     (su/build-filesystem-path filesystem "contents.edn"))))
              (create-show-window file)
              (catch Throwable t
                (timbre/error t "Problem creating show")
                (seesaw/alert parent (str "<html>Unable to Create Show.<br><br>" t)
                              :title "Problem Writing File" :type :error)))))))))

(defn close-all-shows
  "Tries to close all open shows because the program is quitting. If
  `force?` is true, will do so even if they have any open editors with
  unsaved changes. Otherwise gives the user a chance to veto the
  closure so they have a chance to save their changes. Returns truthy
  if all shows have been closed."
  [force?]
  (every? (fn [show] ((:close show) force? true)) (vals (su/get-open-shows))))

(defn run-show-online-expressions
  "Called when we have gone online to run the went-online expressions of
  any already-open shows."
  []
  (doseq [show (vals (su/get-open-shows))]
    (run-global-function show :online nil true)))

(defn run-show-offline-expressions
  "Called when we are going offline to run the going-offline expressions
  of any open shows."
  []
  (doseq [show (vals (su/get-open-shows))]
    (run-global-function show :offline nil true)))


(defn midi-environment-changed
  "Called on the Swing Event Update thread by the Triggers window when
  [CoreMidi4J](https://github.com/DerekCook/CoreMidi4J) reports a
  change to the MIDI environment, so we can update each track's menu
  of available MIDI outputs. Arguments are a `seq` of all the outputs
  now available, and a `set` of the same outputs for convenient
  membership checking."
  [new-outputs output-set]
  (doseq [show (vals (su/get-open-shows))]
    (doseq [track (vals (:tracks show))]
      (let [output-menu (seesaw/select (:panel track) [:#outputs])
            old-selection (seesaw/selection output-menu)]
        (seesaw/config! output-menu :model (concat new-outputs
                                                   ;; Keep the old selection even if it disappeared.
                                                   (when-not (output-set old-selection) [old-selection])
                                                   ;; Allow deselection of a vanished output device
                                                   ;; if there are now no devices available, so
                                                   ;; tracks using custom expressions can still work.
                                                   (when (and (some? old-selection) (empty? new-outputs)) [nil])))

        ;; Keep our original selection chosen, even if it is now missing
        (seesaw/selection! output-menu old-selection))
      (show-midi-status track))
    (doseq [[uuid phrase] (:phrases show)]
      (let [output-menu (seesaw/select (:panel phrase) [:#outputs])
            old-selection (seesaw/selection output-menu)]
        (seesaw/config! output-menu :model (concat new-outputs
                                                   ;; Keep the old selection even if it disappeared.
                                                   (when-not (output-set old-selection) [old-selection])
                                                   ;; Allow deselection of a vanished output device
                                                   ;; if there are now no devices available, so
                                                   ;; tracks using custom expressions can still work.
                                                   (when (and (some? old-selection) (empty? new-outputs)) [nil])))

        ;; Keep our original selection chosen, even if it is now missing
        (seesaw/selection! output-menu old-selection))
      (phrases/show-midi-status show (get-in show [:contents :phrases uuid])))))

(defn require-version
  "Can be called by shows' Global Setup expression to display an error
  and close the show if the Beat Link Trigger version is not at least
  the one passed as a `min-version`."
  [show min-version]
  (when (neg? (.compareTo (DefaultArtifactVersion. (util/get-version))
                          (DefaultArtifactVersion. min-version)))
    (seesaw/invoke-later
     (seesaw/alert (:frame show) (str "<html>This show requires Beat Link Trigger version " min-version
                                      "<br>or later. It will now close.<br><br>")
                   :title "Newer Version Required" :type :error)
     ((:close show) true false))))

(defn block-tracks
  "Can be used to tell Beat Link Trigger the show does not want to
  import or work with tracks, to prevent the user from importing
  them (for example, shows whose purpose is to add Channels On Air
  support for additional mixers have no use for tracks). The default
  is for a show to use tracks, but you can pass a truthy value to this
  function to turn that off.

  Really fancy show files may want to present their own user
  interface, replacing the now-useless window content that is supposed
  to show the tracks in the show. They can do this by passing an
  subclass of `JComponent` to this function, and that will be
  installed as the content of the window. Passing a falsy value (or
  anything which does not derive from `JComponent`) restores the
  standard Show user interface in the window."
  [show blocked?]
  (seesaw/invoke-later
   (seesaw/config! (:frame show) :content
                   (if (instance? javax.swing.JComponent blocked?)
                     blocked?
                     (:default-ui show)))
   (let [blocked? (boolean blocked?)]  ; Normalize to `true` or `false`.
     (swap-show! show update :block-tracks?
                 (fn [were-blocked?]
                   (when (not= blocked? (boolean were-blocked?))
                     (let [^JMenuBar menu-bar (seesaw/config (:frame show) :menubar)
                           ^JMenu menu        (.getMenu menu-bar 1)]
                       (.setLabel menu (if blocked? "Expressions" "Tracks"))
                       (if blocked?
                         (do (.remove menu 0)       ; Remove the Import menu item from the Tracks/Expressions menu.
                             (.remove menu-bar 2))  ; Remove the entire Phrases menu.
                         (do (.insert menu ^JMenu (:import-menu show) 0)        ; Restore the Import menu item.
                             ;; Restore the Phrases menu. To get it in the right place we need to remove the
                             ;; Help menu first, then restore that last.
                             (.remove menu-bar 2)
                             (.add menu-bar (build-phrase-menu show) 2)
                             (.add menu-bar (menus/build-help-menu))))))
                   blocked?)))))  ; Record the current state.

(defn user-data
  "Helper function to return the user data map stored in the show."
  [show]
  (get-in (latest-show show) [:contents :user]))

(defn swap-user-data!
  "Atomically updates the custom user data map stored in the show by
  calling the specified function with the supplied arguments on the
  current value of the user data map in the specified show. Returns
  the updated user data map.

  This can be used by shows with custom user interfaces to update
  their settings in a way that will be saved inside the show file."
  [show f & args]
  (get-in (swap-show! show #(apply update-in % [:contents :user] f args))
          [(:file show) :contents :user]))

(defn simulation-state-changed
  "Called when the first shallow playback simulator window is opened, or
  the last playback simulator window has closed, to enter or exit a
  pseudo-online state. Must be called with an up-to-date view of the
  show."
  [show simulating?]
  (let [loaded-only (seesaw/select (:frame show) [:#loaded-only])]
    (if simulating? (seesaw/show! loaded-only) (seesaw/hide! loaded-only)))
  (su/update-row-visibility show)
  (cues/update-cue-window-online-status show simulating?))
