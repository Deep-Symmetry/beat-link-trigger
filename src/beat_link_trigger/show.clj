(ns beat-link-trigger.show
  "A higher-level interface for creating cues within the beat grids of
  efficiently recognized tracks, with support for offline loading of
  tracks and editing of cues."
  (:require [beat-link-trigger.util :as util]
            [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.track-loader :as loader]
            [beat-link-trigger.prefs :as prefs]
            [clojure.edn :as edn]
            [fipp.edn :as fipp]
            [inspector-jay.core :as inspector]
            [me.raynes.fs :as fs]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.icon :as icon]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.sound.midi Sequencer Synthesizer]
           [java.awt Color Font RenderingHints]
           [java.awt.event WindowEvent]
           [java.lang.ref SoftReference]
           [java.nio.file Path Files FileSystems OpenOption CopyOption StandardCopyOption StandardOpenOption]
           [org.deepsymmetry.beatlink Beat BeatFinder BeatListener CdjStatus CdjStatus$TrackSourceSlot
            DeviceAnnouncement DeviceAnnouncementListener DeviceFinder DeviceUpdateListener LifecycleListener
            MixerStatus Util VirtualCdj]
           [org.deepsymmetry.beatlink.data AlbumArt ArtFinder BeatGrid BeatGridFinder CueList DataReference
            MetadataFinder SearchableItem SignatureFinder SignatureListener SignatureUpdate TimeFinder
            TrackMetadata TrackPositionUpdate
            WaveformDetail WaveformDetailComponent WaveformFinder WaveformPreview WaveformPreviewComponent]
           [org.deepsymmetry.beatlink.dbserver Message]
           [org.deepsymmetry.cratedigger Database]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz]
           [io.kaitai.struct RandomAccessFileKaitaiStream]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDestination CoreMidiDeviceProvider CoreMidiSource]))

(def device-finder
  "A convenient reference to the DeviceFinder singleton."
  (DeviceFinder/getInstance))

(def virtual-cdj
  "A convenient reference to the VirtualCdj singleton."
  (VirtualCdj/getInstance))

(def metadata-finder
  "A convenient reference to the MetadataFinder singleton."
  (MetadataFinder/getInstance))

(def art-finder
  "A convenient reference to the ArtFinder singleton."
  (org.deepsymmetry.beatlink.data.ArtFinder/getInstance))

(def beatgrid-finder
  "A convenient reference to the BeatGridFinder singleton."
  (org.deepsymmetry.beatlink.data.BeatGridFinder/getInstance))

(def waveform-finder
  "A convenient reference to the WaveformFinder singleton."
  (org.deepsymmetry.beatlink.data.WaveformFinder/getInstance))

(def signature-finder
  "A convenient reference to the SingatureFinder singleton."
  (SignatureFinder/getInstance))

(def time-finder
  "A convenient reference to the TimeFinder singleton."
  (TimeFinder/getInstance))

(defonce ^{:private true
           :doc "The map of open shows; keys are the file, values are
  a map containing the root of the window, the file (for ease of
  updating the entry), the ZIP filesystem providing heierarcical
  access to the contents of the file, and the map describing them."}
  open-shows (atom {}))

;;; This section defines a bunch of utility functions that are used by
;;; both the Show and Cues windows.

(defn online?
  "A helper function that checks if we are currently online."
  []
  (.isRunning metadata-finder))

(defn- build-filesystem-path
  "Construct a path in the specified filesystem; translates from
  idiomatic Clojure to Java interop with the `java.nio` package."
  [filesystem & elements]
  (.getPath filesystem (first elements) (into-array String (rest elements))))

(defn- read-edn-path
  "Parse the file at the specified path as EDN, and return the results."
  [path]
  #_(timbre/info "Reading from" path "in filesystem" (.getFileSystem path))
  (edn/read-string {:readers @prefs/prefs-readers} (String. (Files/readAllBytes path) "UTF-8")))

(defn- write-edn-path
  "Write the supplied data as EDN to the specified path, truncating any previously existing file."
  [data path]
  #_(timbre/info "Writing" data "to" path "in filesystem" (.getFileSystem path))
  (binding [*print-length* nil
            *print-level* nil]
    (Files/write path (.getBytes (with-out-str (fipp/pprint data)) "UTF-8")
                 (into-array [StandardOpenOption/CREATE StandardOpenOption/TRUNCATE_EXISTING
                              StandardOpenOption/WRITE]))))

(defn- open-show-filesystem
  "Opens a show file as a ZIP filesystem so the individual elements
  inside of it can be accessed and updated. In the process verifies
  that the file is, in fact, a properly formatted Show ZIP file.
  Returns the opened and validated filesystem and the parsed contents
  map."
  [file]
  (try
    (let [filesystem (FileSystems/newFileSystem (.toPath file) nil)
          contents (read-edn-path (build-filesystem-path filesystem "contents.edn"))]
      (when-not (= (:type contents) ::show)
        (throw (java.io.IOException. "Chosen file does not contain a Beat Link Trigger Show.")))
      (when-not (= (:version contents) 1)
        (throw (java.io.IOException. "Chosen Show is not supported by this version of Beat Link Trigger.")))
      [filesystem contents])
    (catch java.nio.file.ProviderNotFoundException e
      (throw (java.io.IOException. "Chosen file is not readable as a Show" e)))))

(defn- latest-show
  "Returns the current version of the show given a potentially stale
  copy."
  [show]
  (get @open-shows (:file show)))

(defn- latest-track
  "Returns the current version of a track given a potentially stale
  copy."
  [track]
  (get-in @open-shows [(:file track) :tracks (:signature track)]))

(defn- assoc-track-content
  "Updates the show to associate the supplied key and value into the
  specified track's contents map. Track can either be a string
  signature or a full track map, and k-or-ks can either be a single
  key or a sequence of keys to associate a value deeper into the
  contents map."
  [show track k-or-ks v]
  (let [signature (if (string? track) track (:signature track))
        ks (if (sequential? k-or-ks) k-or-ks [k-or-ks])]
    (swap! open-shows assoc-in (concat [(:file show) :tracks signature :contents] ks) v)))

(defn- flush-show
  "Closes the ZIP fileystem so that changes are written to the actual
  show file, then reopens it."
  [show]
  (let [{:keys [file filesystem]} show]
    (swap! open-shows update file dissoc :filesystem)
    (try
      (.close filesystem)
      (catch Throwable t
        (timbre/error t "Problem flushing show filesystem!"))
      (finally
        (let [[reopened-filesystem] (open-show-filesystem file)]
          (swap! open-shows assoc-in [file :filesystem] reopened-filesystem))))))

(defn- write-message-path
  "Writes the supplied Message to the specified path, truncating any previously existing file."
  [message path]
  (with-open [channel (java.nio.channels.FileChannel/open path (into-array [StandardOpenOption/WRITE
                                                                            StandardOpenOption/CREATE_NEW]))]
    (.write message channel)))

(defn- track-present?
  "Checks whether there is already a track with the specified signature
  in the Show."
  [show signature]
  (Files/exists (build-filesystem-path (:filesystem (latest-show show)) "tracks" signature)
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
        [(expression-fn status {:show show} (:expression-globals show)) nil]
        (catch Throwable t
          (timbre/error t "Problem running show global " kind " expression,"
                        (get-in show [:contents :expressions kind]))
          (when alert? (seesaw/alert (str "<html>Problem running show global " (name kind) " expression.<br><br>" t)
                                     :title "Exception in Show Expression" :type :error))
          [nil t])))))

(defn- run-track-function
  "Checks whether the track has a custom function of the specified kind
  installed and if so runs it with the supplied status argument and
  the track local and global atoms. Returns a tuple of the function
  return value and any thrown exception. If `alert?` is `true` the
  user will be alerted when there is a problem running the function."
  [show track kind status alert?]
  (let [show  (latest-show show)
        track (latest-track track)]
    (when-let [expression-fn (get-in track [:expression-fns kind])]
      (try
        [(expression-fn status {:locals (:expression-locals track)
                                :show   show
                                :track  track} (:expression-globals show)) nil]
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/show-editor-title kind show track) ":\n"
                               (get-in track [:contents :expressions kind])))
          (when alert? (seesaw/alert (str "<html>Problem running track " (name kind) " expression.<br><br>" t)
                                     :title "Exception in Show Track Expression" :type :error))
          [nil t])))))

(defn- repaint-states
  "Causes the two track state indicators to redraw themselves to reflect
  a change in state."
  [show signature]
  (let [panel (get-in (latest-show show) [:tracks signature :panel])]
    (seesaw/repaint! (seesaw/select panel [:#loaded-state]))
    (seesaw/repaint! (seesaw/select panel [:#playing-state]))))

(defn repaint-all-states
  "Causes the track state indicators for all tracks in a show to redraw
  themselves to reflect a change in state."
  [show]
  (doseq [signature (keys (:tracks (latest-show show)))]
    (repaint-states show signature)))

(defn- update-track-enabled
  "Updates either a the track or default enabled filter stored result to the value passed in.
  Currently we only store results at the track level, byt maybe
  someday shows themselves will be enabled/disabled too."
  [show track enabled?]
  (let [ks    [:tracks (:signature track) :expression-results :enabled]
        shows (swap! open-shows update (:file show)
                     (fn [show]
                       (-> show
                           (assoc-in [:last :enabled] (get-in show ks))
                           (assoc-in ks enabled?))))]
    (when (not= enabled? (get-in shows [(:file show) :last :enabled]))
      (repaint-states show (:signature track)))))

(defn- run-custom-enabled
  "Invokes the custom enabled filter assigned to a track (or to the
  show, if the track is set to Default and the show is set to Custom),
  if any, recording the result in the track data. `show` and `track`
  must be up-to-date."
  [show track status]
  (if (= "Custom" (get-in track [:contents :enabled]))
    (update-track-enabled show track (boolean (first (run-track-function show track :enabled status false))))
    (when (and (= "Default" (get-in track [:contents :enabled])) (= "Custom" (get-in show [:contents :enabled])))
      (update-track-enabled show track (boolean (first (run-global-function show :enabled status false)))))))

(defn- enabled?
  "Checks whether the track is enabled, given its configuration and
  current state. `show` must be a current view of the show, it will
  not be looked up because this function is also used inside of a
  `swap!` that updates the show."
  [show track]
  (let [track         (get-in show [:tracks (:signature track)])
        track-setting (get-in track [:contents :enabled])
        show-setting  (get-in show [:contents :enabled])
        setting       (if (= track-setting "Default")
                        show-setting
                        track-setting)]
    (case setting
      "Always"     true
      "On-Air"     ((set (vals (:on-air show))) (:signature track))
      "Master"     ((set (vals (:master show))) (:signature track))
      "Custom"     (get-in track [:expression-results :enabled])
      false)))

(defn- describe-disabled-reason
  "Returns a text description of why import from a player is disabled
  based on an associated track signature, or `nil` if it is not
  disabled, given the show map and a possibly-`nil` track signature."
  [show signature]
  (cond
    (nil? signature)                " (no track signature)"
    (track-present? show signature) " (already imported)"
    :else                           nil))

(defn get-chosen-output
  "Return the MIDI output to which messages should be sent for a given
  track, opening it if this is the first time we are using it, or
  reusing it if we already opened it. Returns `nil` if the output can
  not currently be found (it was disconnected, or present in a loaded
  file but not on this system).
  to be reloaded."
  [track]
  (when-let [selection (get-in (latest-track track) [:contents :midi-device])]
    (let [device-name (.full_name selection)]
      (or (get @util/opened-outputs device-name)
          (try
            (let [new-output (midi/midi-out device-name)]
              (swap! util/opened-outputs assoc device-name new-output)
              new-output)
            (catch IllegalArgumentException e  ; The chosen output is not currently available
              (timbre/debug e "Track using nonexisting MIDI output" device-name))
            (catch Exception e  ; Some other problem opening the device
              (timbre/error e "Problem opening device" device-name "(treating as unavailable)")))))))

(defn- read-cue-list
  "Re-creates a CueList object from an imported track. Returns `nil` if
  none is found."
  [track-root]
  (if (Files/isReadable (.resolve track-root "cue-list.dbserver"))
    (with-open [input-stream (Files/newInputStream (.resolve track-root "cue-list.dbserver") (make-array OpenOption 0))
                data-stream  (java.io.DataInputStream. input-stream)]
      (CueList. (Message/read data-stream)))
    (loop [tag-byte-buffers []
           idx              0]
      (let [file-path (.resolve track-root (str "cue-list-" idx ".kaitai"))
            next-buffer (when (Files/isReadable file-path)
                          (with-open [file-channel (Files/newByteChannel file-path (make-array OpenOption 0))]
                            (let [buffer (java.nio.ByteBuffer/allocate (.size file-channel))]
                              (.read file-channel buffer)
                              (.flip buffer))))]
        (if next-buffer
          (recur (conj tag-byte-buffers next-buffer) (inc idx))
          (when (seq tag-byte-buffers) (CueList. tag-byte-buffers)))))))

(def ^:private dummy-reference
  "A meaningless data reference we can use to construct metadata items
  from the show file."
  (DataReference. 0 CdjStatus$TrackSourceSlot/COLLECTION 0))

(defn read-beat-grid
  "Re-creates a BeatGrid object from an imported track. Returns `nil` if
  none is found."
  [track-root]
  (when (Files/isReadable (.resolve track-root "beat-grid.edn"))
    (let [grid-vec (read-edn-path (.resolve track-root "beat-grid.edn"))
          beats (int-array (map int (nth grid-vec 0)))
          times (long-array (nth grid-vec 1))]
      (BeatGrid. dummy-reference beats times))))

(defn read-preview
  "Re-creates a WaveformPreview object from an imported track. Returns
  `nil` if none is found."
  [track-root]
  (let [[path color?] (if (Files/isReadable (.resolve track-root "preview-color.data"))
                        [(.resolve track-root "preview-color.data") true]
                        [(.resolve track-root "preview.data") false])]
    (when (Files/isReadable path)
      (let [bytes (Files/readAllBytes path)]
        (WaveformPreview. dummy-reference (java.nio.ByteBuffer/wrap bytes) color?)))))

(defn read-detail
  "Re-creates a WaveformDetail object from an imported track. Returns
  `nil` if none is found."
  [track-root]
  (let [[path color?] (if (Files/isReadable (.resolve track-root "detail-color.data"))
                        [(.resolve track-root "detail-color.data") true]
                        [(.resolve track-root "detail.data") false])]
    (when (Files/isReadable path)
      (let [bytes (Files/readAllBytes path)]
        (WaveformDetail. dummy-reference (java.nio.ByteBuffer/wrap bytes) color?)))))

(defn read-art
  "Loads album art for an imported track. Returns `nil` if none is
  found."
  [track-root]
  (let [path (.resolve track-root "art.jpg")]
    (when (Files/isReadable path)
      (let [bytes (Files/readAllBytes path)]
        (AlbumArt. dummy-reference (java.nio.ByteBuffer/wrap bytes))))))

(defn- build-track-path
  "Creates an up-to-date path into the current show filesystem for the
  content of the track with the given signature."
  [show signature]
  (let [show (latest-show show)]
    (build-filesystem-path (:filesystem show) "tracks" signature)))

(defn- restore-window-position
  "Tries to put the window back in the position where it was saved in
  the show `contents`. If no saved position is found, or if the saved
  position is within 100 pixels of going off the bottom right of the
  screen, the window is instead positioned centered on the screen, or
  on the parent component if one was supplied."
  ([window contents]
   (restore-window-position window contents nil))
  ([window contents parent]
   (let [[x y width height] (:window contents)
         dm (.getDisplayMode (.getDefaultScreenDevice (java.awt.GraphicsEnvironment/getLocalGraphicsEnvironment)))]
     (if (or (nil? x)
             (> x (- (.getWidth dm) 100))
             (> y (- (.getHeight dm) 100)))
       (.setLocationRelativeTo window parent)
       (.setBounds window x y width height)))))


;;; This next section implements the Cues window and Cue rows.

(defn- update-cue-visibility
  "Determines the cues that should be visible given the filter text (if
  any) and state of the Only Entered checkbox if we are online.
  Updates the tracks cues' `:visible` key to hold a vector of the
  visible cue UUIDs, sorted by their start and end beats followed by
  their comment and UUID. Then uses that to update the contents of the
  `cues` panel appropriately."
  [track]
  (let [track         (latest-track track)
        cues          (seesaw/select (get-in track [:cues-editor :frame]) [:#cues])
        text          (get-in track [:contents :cues :filter])
        entered-only? (get-in track [:contents :cues :entered-only])
        visible-cues (filter (fn [cue]
                               (and
                                (or (clojure.string/blank? text) (clojure.string/includes? (:comment cue) text))
                                (or (not entered-only?) (not (online?)
                                                             ;; TODO: Check if the cue is in an entered state!
                                                             ))))
                             (vals (get-in track [:contents :cues :cues])))
        sorted-cues (sort-by (juxt :start :end :comment :uuid)
                             visible-cues)]
    (swap! open-shows assoc-in [(:file track) :tracks (:signature track) :contents :cues :visible]
           (mapv :uuid sorted-cues))
    (doall (map (fn [cue color]
                  (seesaw/config! (:panel cue) :background color))
                sorted-cues (cycle ["#eee" "#ddd"])))
    (seesaw/config! cues :items (concat (map :panel sorted-cues) [:fill-v]))))

(defn- set-entered-only
  "Update the cues UI so that all cues or only entered cues are
  visible."
  [track entered-only?]
  (swap! open-shows assoc-in [(:file track) :tracks (:signature track) :contents :cues :entered-only] entered-only?)
  (update-cue-visibility track))

(defn- set-auto-scroll
  "Update the cues UI so that the waveform automatically tracks the
  furthest position played."
  [track wave auto?]
  (swap! open-shows assoc-in [(:file track) :tracks (:signature track) :contents :cues :auto-scroll] auto?)
  (.setAutoScroll wave (and auto? (online?)))
  (seesaw/scroll! wave :to [:point 0 0]))

(defn- set-zoom
  "Updates the cues UI so that the waveform is zoomed out by the
  specified factor."
  [track wave zoom]
  (swap! open-shows assoc-in [(:file track) :tracks (:signature track) :contents :cues :zoom] zoom)
  (.setScale wave zoom))

(defn- cue-filter-text-changed
  "Update the cues UI so that only cues matching the specified filter
  text, if any, are visible."
  [track text]
  (swap! open-shows assoc-in [(:file track) :tracks (:signature track) :contents :cues :filter]
         (clojure.string/lower-case text))
  (update-cue-visibility track))

(defn- save-cue-window-position
  "Update the saved dimensions of the cue editor window, so it can be
  reopened in the same state."
  [track window]
  (swap! open-shows assoc-in [(:file track) :tracks (:signature track) :contents :cues :window]
         [(.getX window) (.getY window) (.getWidth window) (.getHeight window)]))

(defn- update-cue-window-online-status
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
            (.setAutoScroll (:wave editor) (and auto? online?))
            (seesaw/scroll! (:wave editor) :to [:point 0 0])))
        (update-cue-visibility track)))))

(defn- paint-beat-selection
  "Draws the selected beat range, if any, on top of the waveform, to
  support creation of new cues at particular locations."
  [track wave graphics]
  (when-let [[start end] (get-in (latest-track track) [:cues-editor :selection])]
    (let [^java.awt.Graphics2D g2 (.create graphics)
          x                       (.getXForBeat wave start)
          w                       (- (.getXForBeat wave end) x)]
      (.setComposite g2 (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER (float 0.5)))
      (.setPaint g2 Color/white)
      #_(.setStroke graphics (java.awt.BasicStroke. 2.0))
      (.fill g2 (java.awt.geom.Rectangle2D$Double. (double x) 0.0 (double w) (double (.getHeight wave))))
      (.dispose g2))))

(defn- handle-wave-drag
  "Processes a mouse drag in the wave detail component, used to adjust
  beat ranges for creating cues."
  [track ^WaveformDetail wave ^BeatGrid grid ^java.awt.event.MouseEvent e]
  (let [track       (latest-track track)
        x           (.getX e)
        beat        (.getBeatForX wave x)
        [start end] (get-in track [:cues-editor :selection])]
    (when start  ; If there is no valid selection, there is nothing to extend.
      ;; We are trying to adjust an existing selection. Move the end that was nearest to the mouse.
      (let [start-distance (Math/abs (- beat start))
            end-distance   (Math/abs (- beat end))]
        (swap! open-shows assoc-in [(:file track) :tracks (:signature track) :cues-editor :selection]
               (if (< start-distance end-distance)
                 [(max 1 beat) end]
                 [start (min (.beatCount grid) (inc beat))])))
      (.repaint wave))))

(defn- handle-wave-click
  "Processes a mouse click in the wave detail component, used for
  setting up beat ranges for creating cues."
  [track ^WaveformDetail wave ^BeatGrid grid ^java.awt.event.MouseEvent e]
  (let [track     (latest-track track)
        x         (.getX e)
        shift     (> (bit-and (.getModifiersEx e) java.awt.event.MouseEvent/SHIFT_DOWN_MASK) 0)
        beat      (.getBeatForX wave x)
        selection (get-in track [:cues-editor :selection])]
    (if (and shift selection)
      ;; We are trying to adjust an existing selection; we can handle it as a drag.
      (handle-wave-drag track wave grid e)
      ;; We are starting a new selection.
      (if (< 0 beat (.beatCount grid))  ; Was the click in a valid place to make a selection?
        (swap! open-shows assoc-in [(:file track) :tracks (:signature track) :cues-editor :selection]
               [beat (inc beat)])  ; Yes, set new selection.
        (swap! open-shows update-in [(:file track) :tracks (:signature track) :cues-editor] dissoc :selection)))
    (.repaint wave)))

(defn- create-cues-window
  "Create and show a new cues window for the specified show and track.
  Must be supplied current versions of `show` and `track.`"
  [show track parent]
  (let [track-root   (build-track-path show (:signature track))
        root         (seesaw/frame :title (str "Cues for Track: " (get-in track [:metadata :title]))
                                   :on-close :nothing)
        grid         (read-beat-grid track-root)
        wave         (WaveformDetailComponent. (read-detail track-root) (read-cue-list track-root) grid)
        zoom-slider  (seesaw/slider :id :zoom :min 1 :max 32 :value (get-in track [:contents :cues :zoom] 4)
                                    :listen [:state-changed #(set-zoom track wave (seesaw/value %))])
        filter-field (seesaw/text (get-in track [:contents :cues :filter] ""))
        entered-only (seesaw/checkbox :id :entered-only :text "Entered Only" :visible? (online?)
                                      :selected? (boolean (get-in track [:contents :cues :entered-only]))
                                      :listen [:item-state-changed #(set-entered-only track (seesaw/value %))])
        auto-scroll  (seesaw/checkbox :id :auto-scroll :text "Auto-Scroll" :visible? (online?)
                                      :selected? (boolean (get-in track [:contents :cues :auto-scroll]))
                                      :listen [:item-state-changed #(set-auto-scroll track wave (seesaw/value %))])
        top-panel    (mig/mig-panel :background "#aaa"
                                    :items [[(seesaw/label :text "Filter:")]
                                            [filter-field "pushx 4, growx 4"]
                                            [entered-only "hidemode 3"]
                                            [(seesaw/label :text "") "pushx1, growx1"]
                                            [auto-scroll "hidemode 3"]
                                            [zoom-slider]
                                            [(seesaw/label :text "Zoom") "wrap"]
                                            [(seesaw/scrollable wave) "span, width 100%, height 110, wrap"]])
        cues         (seesaw/vertical-panel :id :cues)
        cues-scroll  (seesaw/scrollable cues)
        layout       (seesaw/border-panel :north top-panel :center cues-scroll)
        close-fn     (fn [force?]
                       ;; Closes the cues window and performs all necessary cleanup. If `force?` is true,
                       ;; will do so even in the presence of windows with unsaved user changes. Otherwise
                       ;; prompts the user about all unsaved changes, giving them a chance to veto the
                       ;; closure. Returns truthy if the window was closed.
                       (let [show (latest-show show)]
                         ;; TODO: Look at close-fn in create-show-window for some of what we need to do.
                         (swap! open-shows update-in [(:file show) :tracks (:signature track)] dissoc :cues-editor)
                         (.dispose root)
                         true))]
    (swap! open-shows update-in [(:file show) :tracks (:signature track)] assoc :cues-editor {:frame    root
                                                                                              :wave     wave
                                                                                              :grid     grid
                                                                                              :close-fn close-fn})
    (.setScale wave (seesaw/value zoom-slider))
    (.setAutoScroll wave (and (seesaw/value auto-scroll) (online?)))
    (.setOverlayPainter wave (proxy [org.deepsymmetry.beatlink.data.OverlayPainter] []
                               (paintOverlay [component graphics]
                                 (paint-beat-selection track component graphics))))
    (seesaw/listen wave
                   :mouse-pressed (fn [e] (handle-wave-click track wave grid e))
                   :mouse-dragged (fn [e] (handle-wave-drag track wave grid e)))
    (seesaw/config! root :content layout)
    ;; TODO: create-cue-panels track
    (update-cue-visibility track)
    ;; TODO: Update UUIDs of entered/cues
    (seesaw/listen filter-field #{:remove-update :insert-update :changed-update}
                   (fn [e] (cue-filter-text-changed track (seesaw/text e))))
    (.setSize root 800 600)
    (restore-window-position root (get-in track [:contents :cues]) parent)
    (seesaw/listen root
                   :window-closing (fn [e] (close-fn false))
                   #{:component-moved :component-resized}
                   (fn [e]
                     (save-cue-window-position track root)))
    (seesaw/show! root)))

(defn- open-cues
  "Creates, or brings to the front, a window for editing cues attached
  to the specified track in the specified show. Returns truthy if the
  window was newly opened."
  [show track parent]
  (let [show (latest-show show)
        track (get-in show [:tracks (:signature track)])]
    (if-let [existing (:cues-editor track)]
      (.toFront (:frame existing))
      (do (create-cues-window show track parent)
          true))))

;;; This next section implements the Show window and Track rows.

(defn- update-playing-text
  "Formats the text describing the players that are playing a track, and
  sets it into the proper UI label."
  [show signature playing]
  (let [text         (if (empty? playing)
                       "--"
                       (clojure.string/join ", " (sort playing))) ;; TODO: Make Master players amber?
        playing-label (seesaw/select (get-in (latest-show show) [:tracks signature :panel]) [:#playing])]
    (seesaw/invoke-later
     (seesaw/config! playing-label :text text))))

(defn update-playback-position
  "Updates the position and color of the playback position bar for the
  specified player in the track preview and, if there is an open Cues
  editor window, in its waveform detail."
  [show signature player]
  (when-let [position (.getLatestPositionFor time-finder player)]
    (when-let [preview-loader (get-in show [:tracks signature :preview])]
      (when-let [preview (preview-loader)]
        (.setPlaybackState preview player (.milliseconds position) (.playing position))))
    (when-let [cues-editor (get-in (latest-show show) [:tracks signature :cues-editor])]
      (.setPlaybackState (:wave cues-editor) player (.milliseconds position) (.playing position)))))

(defn- players-signature-set
  "Given a map from player number to signature, returns the the set of
  player numbers whose value matched a particular signature."
  [player-map signature]
  (reduce (fn [result [k v]]
            (if (= v signature)
              (conj result k)
              result))
          #{}
          player-map))

(defn- send-stopped-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a track is no longer playing. `track` must be current."
  [show track status]
  (try
    (when-let [output (get-chosen-output track)]
      (let [{:keys [playing-message playing-note playing-channel]} (:contents track)]
        (case playing-message
          "Note" (midi/midi-note-off output playing-note (dec playing-channel))
          "CC"   (midi/midi-control output playing-note 0 (dec playing-channel))
          nil)))
    (run-track-function show track :stopped status false)
    (catch Exception e
      (timbre/error e "Problem reporting stopped track."))))

(defn- no-longer-playing
  "Reacts to the fact that the specified player is no longer playing the
  specified track. If this left the track with no player playing it,
  run the track's Stopped expression, if there is one. Must be passed
  a current view of the show and the previous track state. If we
  learned about the stoppage from a status update, it will be in
  `status`."
  [show player track status tripped-changed]
  (let [signature   (:signature track)
        now-playing (players-signature-set (:playing show) signature)]
    (when (or tripped-changed (empty? now-playing))
      (when (:tripped track)  ; This tells us it was formerly tripped, because we are run on the last state.
        (send-stopped-messages show track status))
      (repaint-states show signature))
    (update-playing-text show signature now-playing)
    (update-playback-position show signature player)))

(defn- update-loaded-text
  "Formats the text describing the players that have a track loaded, and
  sets it into the proper UI label."
  [show signature loaded]
  (let [text         (if (empty? loaded)
                       "--"
                       (clojure.string/join ", " (sort loaded))) ;; TODO: Make on-air players red?
        loaded-label (seesaw/select (get-in (latest-show show) [:tracks signature :panel]) [:#players])]
    (seesaw/invoke-later
     (seesaw/config! loaded-label :text text))))

(defn- send-unloaded-messages
  "Sends the appropriate MIDI messages and runs the custom expression to
  indicate that a track is no longer loaded. `track` must be current."
  [show track]
  (try
    (when-let [output (get-chosen-output track)]
      (let [{:keys [loaded-message loaded-note loaded-channel]} (:contents track)]
        (case loaded-message
          "Note" (midi/midi-note-off output loaded-note (dec loaded-channel))
          "CC"   (midi/midi-control output loaded-note 0 (dec loaded-channel))
          nil)))
    (run-track-function show track :unloaded nil false)
    (catch Exception e
      (timbre/error e "Problem reporting unloaded track."))))

(defn- no-longer-loaded
  "Reacts to the fact that the specified player no longer has the
  specified track loaded. If this leaves track not loaded in any
  player, run the track's Unloaded expression, if there is one. Must
  be passed a current view of the show and the previous track state."
  [show player track tripped-changed]
  (when-let [listener (get-in track [:listeners player])]
    (.removeTrackPositionListener time-finder listener)
    (swap! open-shows update-in [(:file show) :tracks (:signature track) :listeners] dissoc player))
  (let [signature  (:signature track)
        now-loaded (players-signature-set (:loaded show) signature)]
    (when (or tripped-changed (empty? now-loaded))
      (when (:tripped track)  ; This tells us it was formerly tripped, because we are run on the last state.
        (send-unloaded-messages show track))
      (repaint-states show signature))
    (update-loaded-text show signature now-loaded)
    (update-playing-text show signature (players-signature-set (:playing show) signature))

    (when-let [preview-loader (get-in show [:tracks signature :preview])]
      (when-let [preview (preview-loader)]
        #_(timbre/info "clearing for player" player)
        (.clearPlaybackState preview player)))
    (when-let [cues-editor (get-in show [:tracks signature :cues-editor])]
      (.clearPlaybackState (:wave cues-editor) player))))

(defn- add-position-listener
  "Adds a track position listener for the specified player to the time
  finder, making very sure this happens only once. This is used to
  provide us with augmented information whenever this player reports a
  beat, so we can use it to determine which cues to activate and
  deactivate, and make it available to the track's Beat expression."
  [show player track]
  (let [shows (swap! open-shows update-in [(:file show) :tracks (:signature track) :listeners player]
                     (fn [listener]
                       (or listener
                           (proxy [org.deepsymmetry.beatlink.data.TrackPositionBeatListener] []
                             (movementChanged [position])
                             (newBeat [^Beat beat ^TrackPositionUpdate position]
                               (let [show (latest-show show)
                                     track (get-in show [:tracks (:signature track)])]
                                 ;; TODO: Enter/exit any affected cues.
                                 (future
                                   (try
                                     (when (enabled? show track)
                                       (run-track-function show track :beat position false))
                                     (catch Exception e
                                       (timbre/error e "Problem reporting track beat."))))))))))
        listener (get-in shows [(:file show) :tracks (:signature track) :listeners player])]
    (.addTrackPositionListener time-finder player listener)))

(defn- now-loaded
  "Reacts to the fact that the specified player now has the specified
  track loaded. If this is the first player to load the track, run the
  track's Loaded expression, if there is one. Must be passed a current
  view of the show and track."
  [show player track tripped-changed]
  (add-position-listener show player track)
  (let [signature  (:signature track)
        now-loaded (players-signature-set (:loaded show) signature)]
    (when (or tripped-changed (= #{player} now-loaded))  ; This is the first player to load the track.
      (when (:tripped track)
        (try
          (when-let [output (get-chosen-output track)]
            (let [{:keys [loaded-message loaded-note loaded-channel]} (:contents track)]
              (case loaded-message
                "Note" (midi/midi-note-on output loaded-note 127 (dec loaded-channel))
                "CC"   (midi/midi-control output loaded-note 126 (dec loaded-channel))
                nil)))
          (run-track-function show track :loaded nil false)
          (catch Exception e
            (timbre/error e "Problem reporting loaded track."))))
      (repaint-states show signature))
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
        now-playing (players-signature-set (:playing show) signature)]
    (when (or tripped-changed (= #{player} now-playing))  ; This is the first player to play the track.
      (when (:tripped track)
        (try
          (when-let [output (get-chosen-output track)]
            (let [{:keys [playing-message playing-note playing-channel]} (:contents track)]
              (case playing-message
                "Note" (midi/midi-note-on output playing-note 127 (dec playing-channel))
                "CC"   (midi/midi-control output playing-note 127 (dec playing-channel))
                nil)))
          (run-track-function show track :playing status false)
          (catch Exception e
            (timbre/error e "Problem reporting playing track."))))
      (repaint-states show signature))
    (update-playing-text show signature now-playing)
    (update-playback-position show signature player)))

(defn- capture-current-state
  "Copies the expression-relevant show state into the `:last` key,
  so that changes can be examined after a `swap!` operation, and
  appropriate expressions run and user interface updates made."
  [show]
  (let [state (select-keys show [:loaded :playing :tracks])]
    (assoc show :last state)))

(defn trip?
  "Checks whether the track should fire its deferred loaded and playing
  expressions, given that it has just become enabled. Called within
  `swap!` so `show` and `track` can be trusted to have current
  values."
  [show track]
  (or ((set (vals (:loaded show))) (:signature track))
      ((set (vals (:playing show))) (:signature track))))

(defn- update-track-status
  "As part of `update-show-status` below, set the `:tripped` state of
  the track appropriately based on the current show configuration and
  status packet. Called within `swap!` so simply returns the new value.
  If `track` is `nil`, returns `show` unmodified."
  [show track ^CdjStatus status]
  (if track
    (assoc-in show [:tracks (:signature track) :tripped]
              (boolean (and (enabled? show track)
                            (trip? show track))))
    show))

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
        (timbre/info "Switching between two tracks." old-loaded signature)
        (when old-track
          (when old-playing (no-longer-playing show player old-track status false))
          (no-longer-loaded show player old-track false))
        (when track
          (now-loaded show player track false)
          (when is-playing (now-playing show player track status false))))

      (and (not= (:tripped old-track) (:tripped track)))
      (do  ; This is an overall activation/deactivation.
        (timbre/info "Track changing tripped to " (:tripped track))
        (if (:tripped track)
          (do  ; Track is now active.
            (when (seq (players-signature-set (:loaded show) signature))
              (now-loaded show player track true))
            (when is-playing (now-playing show player track status true)))
          (do  ; Track is no longer active.
            (when old-playing (no-longer-playing show player old-track status true))
            (when old-track (no-longer-loaded show player old-track true)))))

      :else
      (when track  ; Track is not changing tripped state, but we may be reporting a new playing state.
        (when (and old-playing (not is-playing))
          (timbre/info "Track stopped playing naturally.")
          (no-longer-playing show player old-track status false))
        (when (and is-playing (not old-playing))
          (timbre/info "Track started playing naturally.")
          (now-playing show player track status false))))

    (when track
      (when (:tripped track) (future (run-track-function show track :tracked status false)))
      (update-playback-position show signature player))))

(defn- update-show-status
  "Adjusts the track state to reflect a new status packet received from
  a player that has it loaded. `track` may be `nil` if the track is
  unrecognized or not part of the show."
  [show track ^CdjStatus status]
  (let [player    (.getDeviceNumber status)
        signature (:signature track)
        track     (when track (latest-track track))
        shows     (swap! open-shows update (:file show)
                         (fn [show]
                           (-> show
                               capture-current-state
                               (assoc-in [:playing player] (when (.isPlaying status) signature))
                               (assoc-in [:on-air player] (when (.isOnAir status) signature))
                               (assoc-in [:master player] (when (.isTempoMaster status) signature))
                               (update-track-status track status))))
        show      (get shows (:file show))
        track     (when track (get-in show [:tracks signature]))]
    (deliver-change-events show signature track player status)))

(defn- update-player-item-signature
  "Makes a player's entry in the import menu enabled or disabled (with
  an explanation), given the track signature that has just been
  associated with the player, updates the affected track(s) sets of
  loaded players, and runs any expressions that need to be informed
  about the loss of the former signature or gain of a new signature.
  Also removes any position being tracked for a playe that has lost
  its signature. It is important that this function be idempotent
  because it needs to be called redundantly when importing new
  tracks."
  [^SignatureUpdate sig-update show]
  (let [player                         (.player sig-update)
        signature                      (.signature sig-update)
        ^javax.swing.JMenu import-menu (:import-menu show)
        disabled-reason                (describe-disabled-reason show signature)
        ^javax.swing.JMenuItem item    (.getItem import-menu (dec player))]
    (.setEnabled item (nil? disabled-reason))
    (.setText item (str "from Player " player disabled-reason))
    (let [shows (swap! open-shows update (:file show)
                       (fn [show]
                         (-> show
                             capture-current-state
                             (assoc-in [:loaded player] signature)
                             (update :playing dissoc player)
                             (update :on-air dissoc player)
                             (update :master dissoc player))))
          show  (get shows (:file show))
          track (when signature (get-in show [:tracks signature]))]
      (deliver-change-events show signature track player nil))))

(defn- refresh-signatures
  "Reports the current track signatures on each player; this is done
  after each new track import, and when first creating a show window,
  to get all the tracks aware of the pre-existing state."
  [show]
  (when (.isRunning signature-finder)
    (doseq [[player signature] (.getSignatures signature-finder)]
      (update-player-item-signature (SignatureUpdate. player signature) show))))

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

(defn- write-cue-list
  "Writes the cue list for a track being imported to the show
  filesystem."
  [track-root ^CueList cue-list]
  (if (nil? (.rawMessage cue-list))
    (util/doseq-indexed idx [tag-byte-buffer (.rawTags cue-list)]
      (.rewind tag-byte-buffer)
      (let [bytes     (byte-array (.remaining tag-byte-buffer))
            file-name (str "cue-list-" idx ".kaitai")]
        (.get tag-byte-buffer bytes)
        (Files/write (.resolve track-root file-name) bytes (make-array OpenOption 0))))
    (write-message-path (.rawMessage cue-list) (.resolve track-root "cue-list.dbserver"))))

(defn write-beat-grid
  "Writes the beat grid for a track being imported to the show
  filesystem."
  [track-root ^BeatGrid beat-grid]
  (let [grid-vec [(mapv #(.getBeatWithinBar beat-grid (inc %)) (range (.beatCount beat-grid)))
                  (mapv #(.getTimeWithinTrack beat-grid (inc %)) (range (.beatCount beat-grid)))]]
    (write-edn-path grid-vec (.resolve track-root "beat-grid.edn"))))

(defn write-preview
  "Writes the waveform preview for a track being imported to the show
  filesystem."
  [track-root ^WaveformPreview preview]
  (let [bytes (byte-array (.. preview getData remaining))
        file-name (if (.isColor preview) "preview-color.data" "preview.data")]
    (.. preview getData (get bytes))
    (Files/write (.resolve track-root file-name) bytes (make-array OpenOption 0))))

(defn write-detail
  "Writes the waveform detail for a track being imported to the show
  filesystem."
  [track-root ^WaveformDetail detail]
  (let [bytes (byte-array (.. detail getData remaining))
        file-name (if (.isColor detail) "detail-color.data" "detail.data")]
    (.. detail getData (get bytes))
    (Files/write (.resolve track-root file-name) bytes (make-array OpenOption 0))))

(defn write-art
  "Writes album art for a track imported to the show filesystem."
  [track-root ^AlbumArt art]
  (let [bytes (byte-array (.. art getRawBytes remaining))]
    (.. art getRawBytes (get bytes))
    (Files/write (.resolve track-root "art.jpg") bytes (make-array OpenOption 0))))

(defn- show-midi-status
  "Set the visibility of the Enabled checkbox and the text and color
  of its label based on whether the currently-selected MIDI output can
  be found. This function must be called on the Swing Event Update
  thread since it interacts with UI objects."
  [track]
  (try
    (let [panel (:panel track)
          enabled-label (seesaw/select panel [:#enabled-label])
          enabled (seesaw/select panel [:#enabled])
          #_state #_(seesaw/select track [:#state])]
      (if-let [output (get-chosen-output track)]
        (do (seesaw/config! enabled-label :foreground "white")
            (seesaw/value! enabled-label "Enabled:")
            (seesaw/config! enabled :visible? true)
            #_(seesaw/config! state :visible? true))
        (do (seesaw/config! enabled-label :foreground "red")
            (seesaw/value! enabled-label "Not found.")
            (seesaw/config! enabled :visible? false)
            #_(seesaw/config! state :visible? false))))
    (catch Exception e
      (timbre/error e "Problem showing Track MIDI status."))))

(defn- update-track-gear-icon
  "Determines whether the gear button for a track should be hollow or
  filled in, depending on whether any expressions have been assigned
  to it."  ; TODO: Or cues, once implemented?
  [track gear]
  (seesaw/config! gear :icon (if (every? empty? (vals (get-in (latest-track track) [:contents :expressions])))
                               (seesaw/icon "images/Gear-outline.png")
                               (seesaw/icon "images/Gear-icon.png"))))

(defn update-tracks-global-expression-icons
  "Updates the icons next to expressions in the Tracks menu to
  reflect whether they have been assigned a non-empty value."
  [show]
  (let [show (latest-show show)
        menu (seesaw/select (:frame show) [:#tracks-menu])]
    (doseq [i (range (.getItemCount menu))]
      (let [item (.getItem menu i)]
        (when item
          (let [label (.getText item)]
            (cond (= label "Edit Global Setup Expression")
                  (.setIcon item (seesaw/icon (if (empty? (get-in show [:contents :expressions :setup]))
                                                "images/Gear-outline.png"
                                                "images/Gear-icon.png")))

                  (= label "Edit Default Enabled Filter Expression")
                  (.setIcon item (seesaw/icon (if (empty? (get-in show [:contents :expressions :enabled]))
                                                "images/Gear-outline.png"
                                                "images/Gear-icon.png")))

                  (= label "Edit Global Shutdown Expression")
                  (.setIcon item (seesaw/icon (if (empty? (get-in show [:contents :expressions :shutdown]))
                                                "images/Gear-outline.png"
                                                "images/Gear-icon.png"))))))))))

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
                                                    (empty? (get-in (or track show)
                                                                    [:contents :expressions (keyword kind)]))
                                                    (editors/show-show-editor
                                                     open-shows (keyword kind) show track panel
                                                     (if gear
                                                       #(update-track-gear-icon track gear)
                                                       #(update-tracks-global-expression-icons show))))))))))

(defn- attach-track-message-visibility-handler
  "Sets up an action handler so that when one of the message menus is
  changed, the appropriate UI elements are shown or hidden."
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

(defn- update-track-visibility
  "Determines the tracks that should be visible given the filter
  text (if any) and state of the Only Loaded checkbox if we are
  online. Updates the show's `:visible` key to hold a vector of the
  visible track signatures, sorted by title then artist then
  signature. Then uses that to update the contents of the `tracks`
  panel appropriately."
  [show]
  (let [show           (latest-show show)
        tracks         (seesaw/select (:frame show) [:#tracks])
        text           (get-in show [:contents :filter])
        loaded-only?   (get-in show [:contents :loaded-only])
        visible-tracks (filter (fn [track]
                                 (and
                                  (or (clojure.string/blank? text) (clojure.string/includes? (:filter track) text))
                                  (or (not loaded-only?) (not (online?))
                                      ((set (vals (.getSignatures signature-finder))) (:signature track)))))
                               (vals (:tracks show)))
        sorted-tracks  (sort-by (juxt #(clojure.string/lower-case (get-in % [:metadata :title] ""))
                                      #(clojure.string/lower-case (get-in % [:metadata :artist] ""))
                                      :signature)
                                visible-tracks)]
    (swap! open-shows assoc-in [(:file show) :visible] (mapv :signature sorted-tracks))
    (doall (map (fn [track color]
                  (seesaw/config! (:panel track) :background color))
                sorted-tracks (cycle ["#eee" "#ddd"])))
    (seesaw/config! tracks :items (concat (map :panel sorted-tracks) [:fill-v]))))

(defn- build-filter-target
  "Creates a string that can be matched against to filter a track by
  text substring, taking into account the custom comment assigned to
  the track in the show, if any."
  [metadata comment]
  (let [comment          (or comment (:comment metadata))
        metadata-strings (vals (select-keys metadata [:album :artist :genre :label :original-artist :remixer :title]))]
    (clojure.string/lower-case (clojure.string/join "\0" (filter identity (concat metadata-strings [comment]))))))

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
      (let [result (.get @reference)]  ; See if our soft reference holds the object we need.
        (if (some? result)
          result  ; Yes, we can return same instance we last created.
          (let [next-object (loader)]
            (when next-object (timbre/info "soft loaded" next-object))
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
  (let [bounds    (java.awt.Rectangle.)
        size-opts (concat (when-let [size (or minimum-size
                                              (when-let [wrapped (loader)]
                                                (.getMinimumSize wrapped)))]
                            [:minimum-size size])
                          (when-let [size (or maximum-size
                                              (when-let [wrapped (loader)]
                                                (.getMaxiumSize wrapped)))]
                            [:maximum-size size])
                          (when-let [size (or preferred-size
                                              (when-let [wrapped (loader)]
                                                (.getPreferredSize wrapped)))]
                            [:preferred-size size]))
        canvas    (apply seesaw/canvas (concat [:opaque? false] size-opts))
        delegate  (proxy [org.deepsymmetry.beatlink.data.RepaintDelegate] []
                    (repaint [x y w h]
                      #_(timbre/info "delegating repaint" x y w h)
                      (.repaint canvas x y w h)))]
    (seesaw/config! canvas :paint (fn [canvas graphics]
                                    (when-let [component (loader)]
                                      (.setRepaintDelegate component delegate)
                                      (.getBounds canvas bounds)
                                      (.setBounds component bounds)
                                      (.paint component graphics))))
    canvas))

(defn- create-track-art
  "Creates the softly-held widget that represents a track's artwork, if
  it has any, or just a blank space if it has none."
  [show signature]
  (let [art-loader (soft-object-loader #(read-art (build-track-path show signature)))]
    (seesaw/canvas :size [80 :by 80] :opaque? false
                   :paint (fn [component graphics]
                            (when-let [art (art-loader)]
                              (when-let [image (.getImage art)]
                                (.drawImage graphics image 0 0 nil)))))))

(defn- create-preview-loader
  "Creates the loader function that can (re)create a track preview
  component as needed."
  [show signature metadata]
  (soft-object-loader
   (fn []
     (let [track-root (build-track-path show signature)
           preview    (read-preview track-root)
           cue-list   (read-cue-list track-root)]
       #_(timbre/info "Created" (:title metadata) "maxHeight:" (.maxHeight preview)
                      "segmentCount:" (.segmentCount preview))
       (WaveformPreviewComponent. preview (:duration metadata) cue-list)))))

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
        (write-edn-path contents (.resolve (build-track-path show (:signature track)) "contents.edn"))
        (swap! open-shows assoc-in [(:file show) :tracks (:signature track) :original-contents] contents)))))

(defn- format-artist-album
  [metadata]
  (clojure.string/join ": " (filter identity (map util/remove-blanks [(:artist metadata) (:album metadata)]))))

(defn- track-panel-constraints
  "Calculates the proper layout constraints for a track panel to look
  right at a given window width."
  [width]
  (let [text-width (max 180 (int (/ (- width 142) 4)))
        preview-width (max 408 (* text-width 3))]
    ["" (str "[]unrelated[fill, " text-width "]unrelated[fill, " preview-width "]")]))

(defn- edit-cues-action
  "Creates the menu action which opens the track's cue editor window."
  [show track panel gear]
  (seesaw/action :handler (fn [_] (open-cues show track panel))
                 :name "Edit Track Cues"
                 :tip "Set up cues that react to particular sections of the track being played."
                 :icon (if (empty? (get-in track [:contents :cues :cues]))
                         "images/Gear-outline.png"
                         "images/Gear-icon.png")))

(defn- track-editor-actions
  "Creates the popup menu actions corresponding to the available
  expression editors for a given track."
  [show track panel gear]
  (for [[kind spec] editors/show-track-editors]
    (let [update-fn (fn []
                      (when (= kind :setup)  ; Clean up then run the new setup function
                        (run-track-function show track :shutdown nil true)
                        (reset! (:expression-locals track) {})
                        (run-track-function show track :setup nil true))
                      (update-track-gear-icon track gear))]
      (seesaw/action :handler (fn [e] (editors/show-show-editor
                                       open-shows kind (latest-show show)
                                       (latest-track track) panel update-fn))
                     :name (str "Edit " (:title spec))
                     :tip (:tip spec)
                     :icon (if (empty? (get-in (latest-track track) [:contents :expressions kind]))
                             "images/Gear-outline.png"
                             "images/Gear-icon.png")))))

(defn- track-inspect-action
  "Creates the menu action which allows a track's local bindings to be
  inspected."
  [track]
  (seesaw/action :handler (fn [_]
                            (inspector/inspect @(:expression-locals track)
                                               :window-name (str "Expression Locals for "
                                                                 (:title (:metadata track)))))
                 :name "Inspect Expression Locals"
                 :tip "Examine any values set as Track locals by its Expressions."))

(declare import-track)

(defn- track-copy-actions
  "Returns a set of menu actions which offer to copy the track to any
  other open shows which do not already contain it."
  [show track]
  (let [show       (latest-show show)
        track      (latest-track track)
        track-root (build-track-path show (:signature track))]
    (filter identity
            (map (fn [other-show]
                   (when (and (not= (:file show) (:file other-show))
                              (not (get-in other-show [:tracks (:signature track)])))
                     (seesaw/action :handler (fn [_]
                                               (let [new-track (merge (select-keys track [:signature :metadata
                                                                                          :contents])
                                                                      {:cue-list  (read-cue-list track-root)
                                                                       :beat-grid (read-beat-grid track-root)
                                                                       :preview   (read-preview track-root)
                                                                       :detail    (read-detail track-root)
                                                                       :art       (read-art track-root)})]
                                                 (import-track other-show new-track)
                                                 (refresh-signatures other-show)))
                                    :name (str "Copy to Show \"" (fs/base-name (:file other-show) true) "\""))))
                 (vals @open-shows)))))

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

(defn- delete-action
  "Creates the menu action which deletes a track after confirmation."
  [show track panel]
  (seesaw/action :handler (fn [_]
                            (when (seesaw/confirm panel (str "This will irreversibly remove the track, losing any\r\n"
                                                             "configuration, expressions, and cues created for it.")
                                                  :type :question :title "Delete Track?")
                              (try
                                (let [show       (latest-show show)
                                      track-root (build-track-path show (:signature track))]
                                  (doseq [path (-> (Files/walk (.toAbsolutePath track-root)
                                                               (make-array java.nio.file.FileVisitOption 0))
                                                   (.sorted #(compare (str %2) (str %1)))
                                                   .iterator
                                                   iterator-seq)]
                                    #_(timbre/info "Trying to delete:" (str path))
                                    #_(timbre/info "Exists?" (Files/isReadable path))
                                    (Files/delete path)
                                    #_(timbre/info "Still there?" (Files/isReadable path))))
                                (swap! open-shows update (:file show) expunge-deleted-track track panel)
                                (refresh-signatures show)
                                (update-track-visibility show)
                                (catch Exception e
                                  (timbre/error e "Problem deleting track")
                                  (seesaw/alert "Problem deleting track:" e)))))
                 :name "Delete Track"))

(defn- paint-state
  "Draws a representation of the state of the track, including whether
  it is enabled and whether any players have it loaded or playing (as
  deterimined by the keyword passed in `k`)."
  [show signature k c g]
  (let [w        (double (seesaw/width c))
        h        (double (seesaw/height c))
        outline  (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        show     (latest-show show)
        track    (get-in show [:tracks signature])
        enabled? (enabled? show track)
        active?  (seq (players-signature-set (k show) signature))]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (when active? ; Draw the inner filled circle showing the track is loaded or playing
      (.setPaint g (if enabled? Color/green Color/lightGray))
      (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))

    ;; Draw the outer circle that reflects the enabled state
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if enabled? Color/green Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(defn- create-track-panel
  "Creates a panel that represents a track in the show. Updates tracking
  indexes appropriately."
  [show track-root]
  (let [signature      (first (clojure.string/split (str (.getFileName track-root)), #"/")) ; ZipFS gives trailing '/'!
        metadata       (read-edn-path (.resolve track-root "metadata.edn"))
        contents-path  (.resolve track-root "contents.edn")
        contents       (when (Files/isReadable contents-path) (read-edn-path contents-path))
        comment        (or (:comment contents) (:comment metadata))
        update-comment (fn [c]
                         (let [comment (seesaw/text c)]
                           (swap! open-shows assoc-in [(:file show) :tracks signature :contents :comment] comment)
                           (swap! open-shows assoc-in [(:file show) :tracks signature :filter]
                                  (build-filter-target metadata comment))))
        comment-field  (seesaw/text :id :comment :paint (partial util/paint-placeholder "Comment")
                                    :text comment :listen [:document update-comment])
        preview-loader (create-preview-loader show signature metadata)
        soft-preview   (create-track-preview preview-loader)
        outputs        (util/get-midi-outputs)
        gear           (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
        panel          (mig/mig-panel :constraints (track-panel-constraints (.getWidth (:frame show)))
                                      :items
                                      [[(create-track-art show signature) "spany 4"]
                                       [(seesaw/label :text (:title metadata)
                                                      :font (util/get-display-font :bitter Font/ITALIC 14)
                                                      :foreground :yellow)
                                        "width 60:120"]
                                       [soft-preview "spany 4, wrap"]

                                       [(seesaw/label :text (format-artist-album metadata)
                                                      :font (util/get-display-font :bitter Font/BOLD 13)
                                                      :foreground :green)
                                        "width 60:120, wrap"]

                                       [comment-field "wrap"]

                                       [(seesaw/label :text "Players:") "split 4, gap unrelated"]
                                       [(seesaw/label :id :players :text "--")]
                                       [(seesaw/label :text "Playing:") "gap unrelated"]
                                       [(seesaw/label :id :playing :text "--") "wrap unrelated, gapafter push"]

                                       [gear "spanx, split"]

                                       ["MIDI Output:" "gap unrelated"]
                                       [(seesaw/combobox :id :outputs
                                                         :model (concat outputs
                                                                        (when-let [chosen (:midi-device contents)]
                                                                          (when-not ((set outputs) chosen)
                                                                            [chosen])))
                                                         :selected-item nil  ; So update below saves default.
                                                         :listen [:item-state-changed
                                                                  #(assoc-track-content show signature :midi-device
                                                                                        (seesaw/selection %))])]

                                       ["Loaded:" "gap unrelated"]
                                       [(seesaw/canvas :id :loaded-state :size [18 :by 18] :opaque? false
                                                       :tip "Outer ring shows track enabled, inner light when loaded."
                                                       :paint (partial paint-state show signature :loaded))]
                                       ["Message:"]
                                       [(seesaw/combobox :id :loaded-message :model ["None" "Note" "CC" "Custom"]
                                                         :selected-item nil  ; So update below saves default.
                                                         :listen [:item-state-changed
                                                                  #(assoc-track-content show signature :loaded-message
                                                                                        (seesaw/selection %))])]
                                       [(seesaw/spinner :id :loaded-note

                                                        :model (seesaw/spinner-model (or (:loaded-note contents) 126)
                                                                                     :from 1 :to 127)
                                                        :listen [:state-changed
                                                                 #(assoc-track-content show signature :loaded-note
                                                                                       (seesaw/value %))])
                                        "hidemode 3"]

                                       [(seesaw/label :id :loaded-channel-label :text "Channel:")
                                        "gap unrelated, hidemode 3"]
                                       [(seesaw/spinner :id :loaded-channel
                                                        :model (seesaw/spinner-model (or (:loaded-channel contents) 1)
                                                                                     :from 1 :to 16)
                                                        :listen [:state-changed
                                                                 #(assoc-track-content show signature :loaded-channel
                                                                                       (seesaw/value %))])
                                        "hidemode 3"]

                                       ["Playing:" "gap unrelated"]
                                       [(seesaw/canvas :id :playing-state :size [18 :by 18] :opaque? false
                                                       :tip "Outer ring shows track enabled, inner light when playing."
                                                       :paint (partial paint-state show signature :playing))]
                                       ["Message:"]
                                       [(seesaw/combobox :id :playing-message :model ["None" "Note" "CC" "Custom"]
                                                         :selected-item nil  ; So update below saves default.
                                                         :listen [:item-state-changed
                                                                  #(assoc-track-content show signature :playing-message
                                                                                        (seesaw/selection %))])]
                                       [(seesaw/spinner :id :playing-note
                                                        :model (seesaw/spinner-model (or (:playing-note contents) 127)
                                                                                     :from 1 :to 127)
                                                        :listen [:state-changed
                                                                 #(assoc-track-content show signature :playing-note
                                                                                       (seesaw/value %))])
                                        "hidemode 3"]

                                       [(seesaw/label :id :playing-channel-label :text "Channel:")
                                        "gap unrelated, hidemode 3"]
                                       [(seesaw/spinner :id :playing-channel
                                                        :model (seesaw/spinner-model (or (:playing-channel contents) 1)
                                                                                     :from 1 :to 16)
                                                        :listen [:state-changed
                                                                 #(assoc-track-content show signature :playing-channel
                                                                                       (seesaw/value %))])
                                        "hidemode 3"]  ; TODO: Playing state indicator canvas.

                                       [(seesaw/label :id :enabled-label :text "Enabled:") "gap unrelated"]
                                       [(seesaw/combobox :id :enabled
                                                         :model ["Default" "Never" "On-Air" "Master" "Custom" "Always"]
                                                         :selected-item nil  ; So update below saves default.
                                                         :listen [:item-state-changed
                                                                  #(do (assoc-track-content show signature :enabled
                                                                                            (seesaw/value %))
                                                                       (repaint-states show signature))])
                                        "hidemode 3"]])

        track          {:file              (:file show)
                        :signature         signature
                        :metadata          metadata
                        :contents          contents
                        :original-contents contents
                        :panel             panel
                        :filter            (build-filter-target metadata comment)
                        :preview           preview-loader
                        :expression-locals (atom {})
                        :creating          true ; Suppress popup expression editors when reopening a show.
                        :loaded            #{}  ; The players that have this loaded.
                        :playing           #{}} ; The players actively playing this.

        popup-fn       (fn [e] (concat [(edit-cues-action show track panel gear) (seesaw/separator)]
                                       (track-editor-actions show track panel gear)
                                       [(seesaw/separator) (track-inspect-action track) (seesaw/separator)]
                                       (track-copy-actions show track)
                                       [(seesaw/separator) (delete-action show track panel)]))]

    (swap! open-shows assoc-in [(:file show) :tracks signature] track)
    (swap! open-shows assoc-in [(:file show) :panels panel] signature)

    ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
    ;; or right click on the gear button. Also set the proper initial gear appearance.
    (seesaw/config! [panel gear] :popup popup-fn)
    (seesaw/listen gear
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (popup-fn e))]
                                      (util/show-popup-from-button gear popup e))))
    (update-track-gear-icon track gear)

    ;; Update output status when selection changes, giving a chance for the other handlers to run first
    ;; so the data is ready. Also sets them up to automatically open the expression editor for the Custom
    ;; Enabled Filter if "Custom" is chosen as the Message.
    (seesaw/listen (seesaw/select panel [:#outputs])
                   :item-state-changed (fn [_] (seesaw/invoke-later (show-midi-status track))))
    (attach-track-message-visibility-handler show track "loaded" gear)
    (attach-track-message-visibility-handler show track "playing" gear)

    ;; Establish the saved or initial settings of the UI elements, which will also record them for the
    ;; future, and adjust the interface, thanks to the already-configured item changed listeners.
    (seesaw/selection! (seesaw/select panel [:#outputs]) (or (:midi-device contents) (first outputs)))
    (seesaw/selection! (seesaw/select panel [:#loaded-message]) (or (:loaded-message contents) "None"))
    (seesaw/selection! (seesaw/select panel [:#playing-message]) (or (:playing-message contents) "None"))
    (seesaw/selection! (seesaw/select panel [:#enabled]) (or (:enabled contents) "Default"))

    ;; In case this is the inital creation of the track, record the defaulted values of the numeric inputs too.
    ;; This will have no effect if they were loaded.
    (assoc-track-content show signature :loaded-note (seesaw/value (seesaw/select panel [:#loaded-note])))
    (assoc-track-content show signature :loaded-channel (seesaw/value (seesaw/select panel [:#loaded-channel])))
    (assoc-track-content show signature :playing-note (seesaw/value (seesaw/select panel [:#playing-note])))
    (assoc-track-content show signature :playing-channel (seesaw/value (seesaw/select panel [:#playing-channel])))

    (doseq [[kind expr] (editors/sort-setup-to-front (get-in track [:contents :expressions]))]
      (let [editor-info (get editors/show-track-editors kind)]
        (try
          (swap! open-shows assoc-in [(:file show) :tracks signature :expression-fns kind]
                 (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                    (editors/show-editor-title kind show track)))
              (catch Exception e
                (timbre/error e (str "Problem parsing " (:title editor-info)
                                     " when loading Show. Expression:\n" expr "\n"))
                (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                   "Check the log file for details.")
                              :title "Exception during Clojure evaluation" :type :error)))))

    ;; TODO: Parse/sort cues for the track, parse expressions for the cues.

    ;; We are done creating the track, so arm the menu listeners to automatically pop up expression editors when
    ;; the user requests a custom message.
    (swap! open-shows update-in [(:file show) :tracks signature] dissoc :creating)))

(defn- close-track-editors?
  "Tries closing all open expression and cue editors for the track. If
  `force?` is true, simply closes them even if they have unsaved
  changes. Othewise checks whether the user wants to save any unsaved
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
  [force? show track]
  (when (close-track-editors? force? track)
    (let [show  (latest-show show)
          track (get-in show [:tracks (:signature track)])]
      (when (:tripped track)
        (when ((set (vals (:playing show))) (:signature track))
          (send-stopped-messages show track nil))
        (when ((set (vals (:loaded show))) (:signature track))
          (send-unloaded-messages show track)))
      (doseq [listener (vals (:listeners track))]
        (.removeTrackPositionListener time-finder listener))
      (swap! open-shows update-in [(:file show) :tracks (:signature track)] dissoc :listeners)
      (run-track-function show track :shutdown nil (not force?)))
    true))

(defn- create-track-panels
  "Creates all the panels that represent tracks in the show."
  [show]
  (let [tracks-path (build-filesystem-path (:filesystem show) "tracks")]
    (when (Files/isReadable tracks-path)  ; We have imported at least one track.
      (doseq [track-path (Files/newDirectoryStream tracks-path)]
        (create-track-panel show track-path)))))

(defn- import-track
  "Imports the supplied track map into the show, after validating that
  all required parts are present."
  [show track]
  (let [missing-elements (filter (fn [k] (not (get track k)))
                                 [:signature :metadata :beat-grid :preview :detail])]
    (if (seq missing-elements)
      (seesaw/alert (:frame show)
                    (str "<html>Unable to import track, missing required elements:<br>"
                         (clojure.string/join ", " (map name missing-elements)))
                    :title "Track Import Failed" :type :error)
      (let [{:keys [file filesystem frame contents]} show
            {:keys [signature metadata cue-list
                    beat-grid preview detail art]}   track
            track-root                               (build-filesystem-path filesystem "tracks" signature)]
        (Files/createDirectories track-root (make-array java.nio.file.attribute.FileAttribute 0))
        (write-edn-path metadata (.resolve track-root "metadata.edn"))
        (when cue-list
          (write-cue-list track-root cue-list))
        (when beat-grid (write-beat-grid track-root beat-grid))
        (when preview (write-preview track-root preview))
        (when detail (write-detail track-root detail))
        (when art (write-art track-root art))
        (when-let [track-contents (:contents track)]  ; In case this is being copied from an existing show.
          (write-edn-path track-contents (.resolve track-root "contents.edn")))
        (create-track-panel show track-root)
        (update-track-visibility show)
        ;; Finally, flush the show to move the newly-created filesystem elements into the actual ZIP file. This
        ;; both protects against loss due to a crash, and also works around a Java bug which is creating temp files
        ;; in the same folder as the ZIP file when FileChannel/open is used with a ZIP filesystem.
        (flush-show show)))))

(defn- import-from-player
  "Imports the track loaded on the specified player to the show."
  [show player]
  (let [signature (.getLatestSignatureFor signature-finder player)]
    (if (track-present? show signature)
      (seesaw/alert (:frame show) (str "Track on Player " player " is already in the Show.")
                    :title "Can’t Re-import Track" :type :error)
      (let [metadata  (.getLatestMetadataFor metadata-finder player)
            cue-list  (.getCueList metadata)
            beat-grid (.getLatestBeatGridFor beatgrid-finder player)
            preview   (.getLatestPreviewFor waveform-finder player)
            detail    (.getLatestDetailFor waveform-finder player)
            art       (.getLatestArtFor art-finder player)]
        (if (not= signature (.getLatestSignatureFor signature-finder player))
          (seesaw/alert (:frame show) (str "Track on Player " player " Changed during Attempted Import.")
                        :title "Track Import Failed" :type :error)
          (do
            (import-track show {:signature signature
                                :metadata  (extract-metadata metadata)
                                :cue-list  cue-list
                                :beat-grid beat-grid
                                :preview   preview
                                :detail    detail
                                :art       art})
            (update-player-item-signature (SignatureUpdate. player signature) show)))))))

(defn- find-anlz-file
  "Given a database and track object, returns the file in which the
  track's analysis data can be found. If `ext?` is true, returns the
  extended analysis path."
  [database track-row ext?]
  (let [volume    (.. database sourceFile getParentFile getParentFile getParentFile)
        raw-path  (Database/getText (.analyzePath track-row))
        subs-path (if ext?
                    (clojure.string/replace raw-path #"DAT$" "EXT")
                    raw-path)]
    (.. volume toPath (resolve (subs subs-path 1)) toFile)))

(defn- find-waveform-preview
  "Helper function to find the best-available waveform preview, if any."
  [data-ref anlz ext]
  (if ext
    (try
      (WaveformPreview. data-ref ext)
      (catch IllegalStateException e
        (timbre/info "No color preview waveform found, chcking for blue version.")
        (find-waveform-preview data-ref anlz nil)))
    (when anlz (WaveformPreview. data-ref anlz))))

(defn- find-art
  "Given a database and track object, returns the track's album art, if
  it has any."
  [database track-row]
  (let [art-id (long (.artworkId track-row))]
    (when (pos? art-id)
      (when-let [art-row (.. database artworkIndex (get art-id))]
        (let [volume   (.. database sourceFile getParentFile getParentFile getParentFile)
              art-path (Database/getText (.path art-row))
              art-file (.. volume toPath (resolve (subs art-path 1)) toFile)
              data-ref (DataReference. 0 CdjStatus$TrackSourceSlot/COLLECTION art-id)]
          (AlbumArt. data-ref art-file))))))

(defn- import-from-media
  "Imports a track that has been parsed from a local media export, being
  very careful to close the underlying track analysis files no matter
  how we exit."
  [show database track-row]
  (let [anlz-file (find-anlz-file database track-row false)
        ext-file  (find-anlz-file database track-row true)
        anlz-atom (atom nil)
        ext-atom  (atom nil)]
    (try
      (let [_         (reset! anlz-atom (when (and anlz-file (.canRead anlz-file))
                                          (RekordboxAnlz.
                                           (RandomAccessFileKaitaiStream. (.getAbsolutePath anlz-file)))))
            _         (reset! ext-atom (when (and ext-file (.canRead ext-file))
                                         (RekordboxAnlz. (RandomAccessFileKaitaiStream. (.getAbsolutePath ext-file)))))
            cue-list  (when @anlz-atom (CueList. @anlz-atom))
            data-ref  (DataReference. 0 CdjStatus$TrackSourceSlot/COLLECTION (.id track-row))
            metadata  (TrackMetadata. data-ref database cue-list)
            beat-grid (when @anlz-atom (BeatGrid. data-ref @anlz-atom))
            preview   (find-waveform-preview data-ref @anlz-atom @ext-atom)
            detail    (when @ext-atom (WaveformDetail. data-ref @ext-atom))
            art       (find-art database track-row)
            signature (.computeTrackSignature signature-finder (.getTitle metadata) (item-label (.getArtist metadata))
                                              (.getDuration metadata) detail beat-grid)]
        (if (and signature (track-present? show signature))
          (seesaw/alert (:frame show) (str "Track \"" (.getTitle metadata) "\" is already in the Show.")
                        :title "Can’t Re-import Track" :type :error)
          (import-track show {:signature signature
                              :metadata  (extract-metadata metadata)
                              :cue-list  cue-list
                              :beat-grid beat-grid
                              :preview   preview
                              :detail    detail
                              :art       art}))
        (refresh-signatures show))
      (finally
        (try
          (when @anlz-atom (.. @anlz-atom _io close))
          (catch Throwable t
            (timbre/error t "Problem closing parsed rekordbox file" anlz-file)))
        (try
          (when @ext-atom (.. @ext-atom _io close))
          (catch Throwable t
            (timbre/error t "Problem closing parsed rekordbox file" ext-file)))))))

(defn- save-show
  "Saves the show to its file, making sure the latest changes are safely
  recorded. If `reopen?` is truthy, reopens the show filesystem for
  continued use."
  [show reopen?]
  (let [window (:frame show)]
    (swap! open-shows update-in [(:file show) :contents]
           merge {:window   [(.getX window) (.getY window) (.getWidth window) (.getHeight window)]}))
  (let [show                               (latest-show show)
        {:keys [contents file filesystem]} show]
    (try
      (write-edn-path contents (build-filesystem-path filesystem "contents.edn"))
      (save-track-contents show)
      (.close filesystem)
      (catch Throwable t
        (timbre/error t "Problem saving" file)
        (throw t))
      (finally
        (when reopen?
          (let [[reopened-filesystem] (open-show-filesystem file)]
            (swap! open-shows assoc-in [file :filesystem] reopened-filesystem)))))))

(defn- save-show-as
  "Closes the show filesystem to flush changes to disk, copies the file
  to the specified destination, then reopens it."
  [show as-file]
  (let [show                            (latest-show show)
        {:keys [frame file filesystem]} show]
    (try
      (save-show show false)
      (Files/copy (.toPath file) (.toPath as-file) (into-array [StandardCopyOption/REPLACE_EXISTING]))
      (catch Throwable t
        (timbre/error t "Problem saving" file "as" as-file)
        (throw t))
      (finally
        (let [[reopened-filesystem] (open-show-filesystem file)]
          (swap! open-shows assoc-in [file :filesystem] reopened-filesystem))))))

(defn- build-save-action
  "Creates the menu action to save a show window, making sure the file
  on disk is up-to-date."
  [show]
  (seesaw/action :handler (fn [e]
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
  (seesaw/action :handler (fn [e]
                            (let [extension (util/extension-for-file-type :show)]
                              (when-let [file (chooser/choose-file (:frame show) :type :save
                                                                   :all-files? false
                                                                   :filters [["BeatLinkTrigger Show files"
                                                                              [extension]]])]
                                (if (get @open-shows file)
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

(defn- build-import-offline-action
  "Creates the menu action to import a track from offline media, given
  the show map."
  [show]
  (seesaw/action :handler (fn [e]
                            (loop [show (latest-show show)]
                              (let [result (loader/choose-local-track (:frame show) (:import-database show)
                                                                      "Change Media")]
                                (if (string? result) ; User wants to change media
                                  (do
                                    (try
                                      (.close (:import-database show))
                                      (catch Throwable t
                                        (timbre/error t "Problem closing offline media database.")))
                                    (swap! open-shows update-in [(:file show)] dissoc :import-database)
                                    (recur (latest-show show)))
                                  (when-let [[database track-row] result]
                                    (swap! open-shows assoc-in [(:file show) :import-database] database)
                                    (try
                                      (import-from-media (latest-show show) database track-row)
                                      (catch Throwable t
                                        (timbre/error t "Problem importing from offline media.")
                                        (seesaw/alert (:frame show) (str "<html>Unable to Import.<br><br>" t)
                                                      :title "Problem Finding Track Metadata" :type :error))))))))
                 :name "from Offline Media"
                 :key "menu M"))

(defn safe-check-for-player
  "Returns truthy when the specified player is found on the network, and
  does not throw an exception if we are deeply offline (not even the
  DeviceFinder is running because BLT was launched in offline mode)."
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
                                                  (.getLatestSignatureFor signature-finder player)))]
    (seesaw/action :handler (fn [e] (import-from-player (latest-show show) player))
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
               (map inc (range 4)))
          [(build-import-offline-action show)]))

(defn- build-close-action
  "Creates the menu action to close a show window, given the show map."
  [show]
  (seesaw/action :handler (fn [e]
                            (seesaw/invoke-later
                             (.dispatchEvent (:frame show) (WindowEvent. (:frame show) WindowEvent/WINDOW_CLOSING))))
                 :name "Close"))

(defn build-global-editor-action
  "Creates an action which edits one of a show's global expressions."
  [show kind]
  (seesaw/action :handler (fn [e] (editors/show-show-editor open-shows kind (latest-show show) nil (:frame show)
                                                            (fn []
                                                              (when (= :setup kind)
                                                                (run-global-function show :shutdown nil true)
                                                                (reset! (:expression-globals show) {})
                                                                (run-global-function show :setup nil true))
                                                              (update-tracks-global-expression-icons show))))
                 :name (str "Edit " (get-in editors/global-show-editors [kind :title]))
                 :tip (get-in editors/global-show-editors [kind :tip])
                 :icon (seesaw/icon (if (empty? (get-in show [:contents :expressions kind]))
                                      "images/Gear-outline.png"
                                      "images/Gear-icon.png"))))

(defn- build-show-menubar
  "Creates the menu bar for a show window, given the show map and the
  import submenu."
  [show]
  (let [title          (str "Expression Globals for Show " (util/trim-extension (.getPath (:file show))))
        inspect-action (seesaw/action :handler (fn [e] (inspector/inspect @(:expression-globals show)
                                                                          :window-name title))
                                      :name "Inspect Expression Globals"
                                      :tip "Examine any values set as globals by any Track Expressions.")]
    (seesaw/menubar :items [(seesaw/menu :text "File"
                                         :items [(build-save-action show) (build-save-as-action show) (seesaw/separator)
                                                 (build-close-action show)])
                            (seesaw/menu :text "Tracks"
                                         :id :tracks-menu
                                         :items (concat [(:import-menu show)]
                                                        (map (partial build-global-editor-action show)
                                                              (keys editors/global-show-editors))
                                                        [(seesaw/separator) inspect-action]))
                            (menus/build-help-menu)])))

(defn- update-player-item-visibility
  "Makes a player's entry in the import menu visible or invisible, given
  the device announcement describing the player and the show map."
  [^DeviceAnnouncement announcement show visible?]
  (let [show                           (latest-show show)
        ^javax.swing.JMenu import-menu (:import-menu show)
        player                         (.getNumber announcement)]
    (when (and (< player 5)  ; Ignore non-players, and attempts to make players visible when we are offline.
               (or (online?) (not visible?)))
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
  (swap! open-shows assoc-in [(:file show) :contents :enabled] enabled)
  (repaint-all-states show))

(defn- set-loaded-only
  "Update the show UI so that all tracks or only loaded tracks are
  visible."
  [show loaded-only?]
  (swap! open-shows assoc-in [(:file show) :contents :loaded-only] loaded-only?)
  (update-track-visibility show))

(defn- filter-text-changed
  "Update the show UI so that only tracks matching the specified filter
  text, if any, are visible."
  [show text]
  (swap! open-shows assoc-in [(:file show) :contents :filter] (clojure.string/lower-case text))
  (update-track-visibility show))

(defn- resize-track-panels
  "Called when the show window has resized, to put appropriate
  constraints on the columns of the track panels."
  [panels width]
  (let [constraints (track-panel-constraints width)]
    (doseq [panel panels]
      (seesaw/config! panel :constraints constraints)
      (.revalidate panel))))

(defn- create-show-window
  "Create and show a new show window on the specified file."
  [file]
  (util/load-fonts)
  (when (online?)  ; Start out the finders that aren't otherwise guaranteed to be running.
    (.start time-finder)
    (.start signature-finder))
  (let [[filesystem contents] (open-show-filesystem file)]
    (try
      (let [root            (seesaw/frame :title (str "Beat Link Show: " (util/trim-extension (.getPath file)))
                                          :on-close :nothing)
            import-menu     (seesaw/menu :text "Import Track")
            show            {:creating    true
                             :frame       root
                             :expression-globals (atom {})
                             :import-menu import-menu
                             :file        file
                             :filesystem  filesystem
                             :contents    contents
                             :tracks      {}  ; Lots of info about each track, including loaded metadata.
                             :panels      {}  ; Maps from panel object to track signature, for updating visibility.
                             :loaded      {}  ; Map from player number to signature that has been reported loaded.
                             :playing     {}  ; Map from player number to signature that has been reported playing.
                             :entered     {}  ; Map from player number to UUID of cue that has been reported entered.
                             :active      {}  ; Map from player number to UUID of cue that has been reported active.
                             :visible     []} ; The visible (through filters) track signatures in sorted order.
            tracks          (seesaw/vertical-panel :id :tracks)
            tracks-scroll   (seesaw/scrollable tracks)
            enabled-default (seesaw/combobox :id :default-enabled :model ["Never" "On-Air" "Master" "Custom" "Always"]
                                             :listen [:item-state-changed
                                                      #(set-enabled-default show (seesaw/selection %))])
            loaded-only     (seesaw/checkbox :id :loaded-only :text "Loaded Only"
                                             :selected? (boolean (:loaded-only contents)) :visible? (online?)
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
                              (deviceFound [this announcement]
                                (update-player-item-visibility announcement show true))
                              (deviceLost [this announcement]
                                (update-player-item-visibility announcement show false)))
            mf-listener     (reify LifecycleListener  ; Hide or show all players if we go offline or online.
                              (started [this sender]
                                (.start time-finder)  ; We need this too, and it doesn't auto-restart.
                                (.start signature-finder)  ; In case we started out offline.
                                (seesaw/invoke-later
                                 (seesaw/show! loaded-only)
                                 (doseq [announcement (.getCurrentDevices device-finder)]
                                   (update-player-item-visibility announcement show true))
                                 (update-track-visibility show)
                                 (update-cue-window-online-status show true)))
                              (stopped [this sender]
                                (seesaw/invoke-later
                                 (seesaw/hide! loaded-only)
                                 (doseq [announcement (.getCurrentDevices device-finder)]
                                   (update-player-item-visibility announcement show false))
                                 (update-track-visibility show)
                                 (update-cue-window-online-status show false))))
            sig-listener    (reify SignatureListener  ; Update the import submenu as tracks come and go.
                              (signatureChanged [this sig-update]
                                (update-player-item-signature sig-update show)
                                (seesaw/invoke-later (update-track-visibility show))))
            update-listener (reify DeviceUpdateListener
                              (received [this status]
                                (try
                                  (when (and (.isRunning signature-finder)  ; Ignore packets when not yet fully online.
                                             (instance? CdjStatus status))  ; We only want CDJ information.
                                    (let [signature (.getLatestSignatureFor signature-finder status)
                                          show      (latest-show show)
                                          track     (get-in (latest-show show) [:tracks signature])]
                                      (when track
                                        (run-custom-enabled show track status)
                                        (update-show-status show track status))))
                                  (catch Exception e
                                    (timbre/error e "Problem responding to Player status packet.")))))
            window-name     (str "show-" (.getPath file))
            close-fn        (fn [force? quitting?]
                              ;; Closes the show window and performs all necessary cleanup. If `force?` is true,
                              ;; will do so even in the presence of windows with unsaved user changes. Otherwise
                              ;; prompts the user about all unsaved changes, giving them a chance to veto the
                              ;; closure. Returns truthy if the show was closed.
                              (let [show (latest-show show)]
                                (when (and (every? (partial close-track-editors? false) (vals (:tracks show)))
                                           (every? (partial editors/close-editor? false)
                                                   (vals (:expression-editors show))))
                                  (.removeUpdateListener virtual-cdj update-listener)
                                  (.removeDeviceAnnouncementListener device-finder dev-listener)
                                  (.removeLifecycleListener metadata-finder mf-listener)
                                  (.removeSignatureListener signature-finder sig-listener)
                                  (doseq [track (vals (:tracks show))]
                                    (cleanup-track true show track))
                                  (run-global-function show :shutdown nil (not force?))
                                  (try
                                    (save-show show false)
                                    (catch Throwable t
                                      (timbre/error t "Problem closing Show file.")
                                      (seesaw/alert root (str "<html>Problem Closing Show.<br><br>" t)
                                                    :title "Problem Closing Show" :type :error)))
                                  (when-let [database (:import-database show)]
                                    (.close database))
                                  (seesaw/invoke-later
                                   ;; Gives windows time to close first, so they don't recreate a broken show.
                                   (swap! open-shows dissoc file))
                                  ;; Remove the instruction to reopen this window the next time the program runs,
                                  ;; unless we are closing it because the application is quitting.
                                  (when-not quitting? (swap! util/window-positions dissoc window-name))
                                  (.dispose root)
                                  true)))]
        (swap! open-shows assoc file (assoc show :close close-fn))
        (.addDeviceAnnouncementListener device-finder dev-listener)
        (.addLifecycleListener metadata-finder mf-listener)
        (.addSignatureListener signature-finder sig-listener)
        (.addUpdateListener virtual-cdj update-listener)
        (seesaw/config! import-menu :items (build-import-submenu-items show))
        (seesaw/config! root :menubar (build-show-menubar show) :content layout)
        (create-track-panels show)
        (update-track-visibility show)
        (refresh-signatures show)
        (seesaw/listen filter-field #{:remove-update :insert-update :changed-update}
                       (fn [e] (filter-text-changed show (seesaw/text e))))
        (attach-track-custom-editor-opener show nil enabled-default :enabled nil)
        (seesaw/selection! enabled-default (:enabled contents "Always"))
        (.setSize root 900 600)  ; Our default size if there isn't a position stored in the file.
        (restore-window-position root contents)
        (seesaw/listen root
                       :window-closing (fn [e] (close-fn false false))
                       #{:component-moved :component-resized}
                       (fn [e]
                         (util/save-window-position root window-name)
                         (when (= (.getID e) java.awt.event.ComponentEvent/COMPONENT_RESIZED)
                           (resize-track-panels (keys (:panels (latest-show show))) (.getWidth root)))))
        (resize-track-panels (keys (:panels (latest-show show))) (.getWidth root))
        (doseq [[kind expr] (editors/sort-setup-to-front (get-in show [:contents :expressions]))]
          (let [editor-info (get editors/global-show-editors kind)]
            (try
              (swap! open-shows assoc-in [file :expression-fns kind]
                     (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                        (editors/show-editor-title kind show nil)))
              (catch Exception e
                (timbre/error e (str "Problem parsing " (:title editor-info)
                                     " when loading Show. Expression:\n" expr "\n"))
                (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                   "Check the log file for details.")
                              :title "Exception during Clojure evaluation" :type :error)))))
        (run-global-function show :setup nil true)
        (swap! open-shows update file dissoc :creating)
        (update-tracks-global-expression-icons show)
        (seesaw/show! root))
      (catch Throwable t
        (swap! open-shows dissoc file)
        (.close filesystem)
        (throw t)))))

;;; External API for creating, opening, reopeining, and closing shows:

(defn- open-internal
  "Opens a show file. If it is already open, just brings the window to
  the front. Returns truthy if the file was newly opened."
  [parent file]
  (let [file (.getCanonicalFile file)]
    (try
      (if-let [existing (get @open-shows file)]
        (.toFront (:frame existing))
        (do (create-show-window file)
            true))
      (catch Exception e
        (timbre/error e "Unable to open Show" file)
        (seesaw/alert parent (str "<html>Unable to Open Show " file "<br><br>" e)
                      :title "Problem Opening File" :type :error)
        false))))

(defn open
  "Let the user choose a show file and tries to open it. If already
  open, just brings the window to the front."
  [parent]
(when-let [file (chooser/choose-file parent :type :open
                                     :all-files? false
                                     :filters [["BeatLinkTrigger Show files" [(util/extension-for-file-type :show)]]])]
  (open-internal parent file)))

(defn reopen-previous-shows
  "Tries to reopen any shows that were open the last time the user quit."
  []
  (doseq [window (keys @util/window-positions)]
    (when (and (string? window)
               (.startsWith window "show-"))
      (when-not (open-internal nil (clojure.java.io/file (subs window 5)))
        (swap! util/window-positions dissoc window)))))  ; Remove saved position if show is no longer available.

(defn new
  "Creates a new show file and opens a window on it."
  [parent]
  (let [extension (util/extension-for-file-type :show)]
    (when-let [file (chooser/choose-file parent :type :save
                                         :all-files? false
                                         :filters [["BeatLinkTrigger Show files"
                                                    [extension]]])]
      (let [file (.getCanonicalFile file)]
        (if (get @open-shows file)
          (seesaw/alert parent "Cannot Replace an Open Show."
                        :title "Show is Already Open" :type :error)
          (when-let [file (util/confirm-overwrite-file file extension parent)]
            (try
              (Files/deleteIfExists (.toPath file))
              (let [file-uri (.toUri (.toPath file))]
                (with-open [filesystem (FileSystems/newFileSystem (java.net.URI. (str "jar:" (.getScheme file-uri))
                                                                                 (.getPath file-uri) nil)
                                                                  {"create" "true"})]
                  (write-edn-path {:type ::show :version 1} (build-filesystem-path filesystem "contents.edn"))))
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
  (every? (fn [show] ((:close show) force? true)) (vals @open-shows)))
