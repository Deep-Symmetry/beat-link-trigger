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
           [java.awt Font]
           [java.awt.event WindowEvent]
           [java.lang.ref SoftReference]
           [java.nio.file Path Files FileSystems OpenOption CopyOption StandardCopyOption StandardOpenOption]
           [org.deepsymmetry.beatlink Beat BeatFinder BeatListener CdjStatus CdjStatus$TrackSourceSlot
            DeviceAnnouncement DeviceAnnouncementListener DeviceFinder DeviceUpdateListener LifecycleListener
            MixerStatus Util VirtualCdj]
           [org.deepsymmetry.beatlink.data AlbumArt ArtFinder BeatGrid BeatGridFinder CueList DataReference
            MetadataFinder WaveformFinder
            SearchableItem SignatureFinder SignatureListener SignatureUpdate TimeFinder TrackMetadata
            WaveformDetail WaveformDetailComponent WaveformPreview WaveformPreviewComponent]
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
    (try
      (.close filesystem)
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
  (Files/exists (build-filesystem-path (:filesystem show) "tracks" signature) (make-array java.nio.file.LinkOption 0)))

(defn- describe-disabled-reason
  "Returns a text description of why import from a player is disabled
  based on an associated track signature, or `nil` if it is not
  disabled, given the show map and a possibly-`nil` track signature."
  [show signature]
  (cond
    (nil? signature)                " (no track signature)"
    (track-present? show signature) " (already imported)"
    :else                           nil))

(defn- no-longer-playing
  "Performs the bookeeping to reflect that the specified player is no
  longer playing the track with the specified signature. If this
  causes the track to stop having any player playing it, run the
  track's Stopped expression, if there is one. Must be passed a
  current view of the show."
  [show player signature]
  ;; TODO: flesh out!
  )

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

(defn- no-longer-loaded
  "Performs the bookeeping to reflect that the specified player no
  longer has the track with the specified signature loaded. If this
  causes the track to stop being loaded in any player, run the track's
  Unloaded expression, if there is one. Must be passed a current view
  of the show."
  [show player signature]
  (let [shows      (swap! open-shows update-in [(:file show) :tracks signature :loaded] disj player)
        now-loaded (get-in shows [(:file show) :tracks signature :loaded])]
    (when (empty? now-loaded)
      ;; TODO: Run the Unloaded expression.
      ;; TODO: Stop the position animation loop.
      (when-let [preview-loader (get-in show [:tracks signature :preview])]
        (when-let [preview (preview-loader)]
          (.clearPlaybackState preview))))
    (update-loaded-text show signature now-loaded)))

(defn- now-loaded
  "Performs the bookeeping to reflect that the specified player now has
  the track with the specified signature loaded. If this is the first
  player to load the track, run the track's Loaded expression, if
  there is one. Must be passed a current view of the show."
  [show player signature]
  (let [shows      (swap! open-shows update-in [(:file show) :tracks signature :loaded] conj player)
        now-loaded (get-in shows [(:file show) :tracks signature :loaded])]
    (when (= #{player} now-loaded)  ; This is the first player to load the track.
      ;; TODO: Run the Loaded expression.
      )
    (update-loaded-text show signature now-loaded)
    (when-let [position (.getLatestPositionFor time-finder player)]
      (when-let [preview-loader (get-in show [:tracks signature :preview])]
        (when-let [preview (preview-loader)]
          (.setPlaybackState preview player (.milliseconds position) (.playing position)))))
    ;; TODO: Start an animation loop to update playback position markers and active cue indicators.
    ))

(defn- now-playing
  "Performs the bookeeping to reflect that the specified player is now
  playing the track with the specified signature. If this is the first
  player playing the track, run the track's Started expression, if
  there is one. Must be passed a current view of the show."
  [show player signature]
    ;; TODO: flesh out! Use key :playing
  )

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
  (let [show                           (latest-show show)
        ^javax.swing.JMenu import-menu (:import-menu show)
        disabled-reason                (describe-disabled-reason show (.signature sig-update))
        ^javax.swing.JMenuItem item    (.getItem import-menu (dec (.player sig-update)))]
    (.setEnabled item (nil? disabled-reason))
    (.setText item (str "from Player " (.player sig-update) disabled-reason))
    (when-let [track (get-in show [:tracks (.signature sig-update)])]  ; Only do this if the track is part of the show.
      (let [old-loaded  (volatile! nil)
            old-playing (volatile! nil)]
        (locking open-shows
          (let [show (latest-show show)]
            (vreset! old-loaded (get-in show [:loaded (.player sig-update)]))
            (vreset! old-playing (get-in show [:playing (.player sig-update)]))
            (swap! open-shows assoc-in [(:file show) :loaded (.player sig-update)] (.signature sig-update))
            (swap! open-shows assoc-in [(:file show) :playing (.player sig-update)] nil)))
        (when (and @old-playing (not= @old-playing (.signature sig-update)))
          (no-longer-playing show (.player sig-update) @old-playing))
        (when (and @old-loaded (not= @old-loaded (.signature sig-update)))
          (no-longer-loaded show (.player sig-update) @old-loaded))
        (when (and (.signature sig-update) (not= (.signature sig-update) @old-loaded))
          (now-loaded show (.player sig-update) (.signature sig-update)))))))

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

(defn write-beat-grid
  "Writes the beat grid for a track being imported to the show
  filesystem."
  [track-root ^BeatGrid beat-grid]
  (let [grid-vec [(mapv #(.getBeatWithinBar beat-grid (inc %)) (range (.beatCount beat-grid)))
                  (mapv #(.getTimeWithinTrack beat-grid (inc %)) (range (.beatCount beat-grid)))]]
    (write-edn-path grid-vec (.resolve track-root "beat-grid.edn"))))

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

(defn write-preview
  "Writes the waveform preview for a track being imported to the show
  filesystem."
  [track-root ^WaveformPreview preview]
  (let [bytes (byte-array (.. preview getData remaining))
        file-name (if (.isColor preview) "preview-color.data" "preview.data")]
    (.. preview getData (get bytes))
    (Files/write (.resolve track-root file-name) bytes (make-array OpenOption 0))))

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

(defn write-detail
  "Writes the waveform detail for a track being imported to the show
  filesystem."
  [track-root ^WaveformDetail detail]
  (let [bytes (byte-array (.. detail getData remaining))
        file-name (if (.isColor detail) "detail-color.data" "detail.data")]
    (.. detail getData (get bytes))
    (Files/write (.resolve track-root file-name) bytes (make-array OpenOption 0))))

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

(defn write-art
  "Writes album art for a track imported to the show filesystem."
  [track-root ^AlbumArt art]
  (let [bytes (byte-array (.. art getRawBytes remaining))]
    (.. art getRawBytes (get bytes))
    (Files/write (.resolve track-root "art.jpg") bytes (make-array OpenOption 0))))

(defn read-art
  "Loads album art for an imported track. Returns `nil` if none is
  found."
  [track-root]
  (let [path (.resolve track-root "art.jpg")]
    (when (Files/isReadable path)
      (let [bytes (Files/readAllBytes path)]
        (AlbumArt. dummy-reference (java.nio.ByteBuffer/wrap bytes))))))

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

(defn- update-gear-icon
  "Determines whether the gear button for a track should be hollow or
  filled in, depending on whether any expressions have been assigned
  to it."  ; TODO: Or cues, once implemented?
  [track gear]
  (seesaw/config! gear :icon (if (every? empty? (vals (get-in (latest-track track) [:contents :expressions])))
                               (seesaw/icon "images/Gear-outline.png")
                               (seesaw/icon "images/Gear-icon.png"))))

(defn- attach-message-visibility-handler
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
                                       (let [choice (seesaw/selection message-menu)
                                             track  (latest-track track)]
                                         (when (and (= "Custom" choice)
                                                    (not (:creating track))
                                                    (empty? (get-in track [:contents :expressions (keyword kind)]))
                                                    (editors/show-show-editor open-shows (keyword kind)
                                                                              (latest-show show) track panel
                                                                              #(update-gear-icon panel gear))))
                                         (if (= "None" choice)
                                           (seesaw/hide! [note-spinner label channel-spinner])
                                           (seesaw/show! [note-spinner label channel-spinner])))))))

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
        sorted-tracks  (sort-by (juxt #(get-in % [:metadata :title])
                                      #(get-in % [:metadata :artist]) :signature)
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

(defn- build-track-path
  "Creates an up-to-date path into the current show filesystem for the
  content of the track with the given signature."
  [show signature]
  (let [show (latest-show show)]
    (build-filesystem-path (:filesystem show) "tracks" signature)))

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
          (when alert?
            (seesaw/alert (str "<html>Problem running track " (name kind) " expression.<br><br>" t)
                          :title "Exception in Show Track Expression" :type :error))
          [nil t])))))

(defn- format-artist-album
  [metadata]
  (clojure.string/join ": " (filter identity (map util/remove-blanks [(:artist metadata) (:album metadata)]))))

(defn- track-panel-constraints
  "Calculates the proper layout constraints for a track panel to look
  right at a given window width."
  [width]
  (let [text-width (max 180 (int (/ (- width 140) 4)))
        preview-width (max 408 (* text-width 3))]
    ["" (str "[]unrelated[fill, " text-width "]unrelated[fill, " preview-width "]")]))

(defn- edit-cues-action
  "Creates the menu action which opens the track's cue editor window."
  [show track panel gear]
  (seesaw/action :handler (fn [_]
                            (seesaw/alert panel "Not yet implemented!"))
                 :name "Edit Track Cues"
                 :tip "Set up cues that react to particular sections of the track being played."
                 :icon (if (empty? (get-in track [:contents :cues]))
                         "images/Gear-outline.png"
                         "images/Gear-icon.png")))

(defn- editor-actions
  "Creates the popup menu actions corresponding to the available
  expression editors for a given track."
  [show track panel gear]
  (for [[kind spec] editors/show-track-editors]
    (let [update-fn (fn []
                      (when (= kind :setup)  ; Clean up then run the new setup function
                        (run-track-function show track :shutdown nil true)
                        (reset! (:expression-locals track) {})
                        (run-track-function show track :setup nil true))
                      (update-gear-icon track gear))]
      (seesaw/action :handler (fn [e] (editors/show-show-editor
                                       open-shows kind (latest-show show)
                                       (latest-track track) panel update-fn))
                     :name (str "Edit " (:title spec))
                     :tip (:tip spec)
                     :icon (if (empty? (get-in track [:contents :expressions kind]))
                             "images/Gear-outline.png"
                             "images/Gear-icon.png")))))

(defn- inspect-action
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

(defn- copy-actions
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
                                                 (import-track other-show new-track)))
                                    :name (str "Copy to Show \"" (fs/base-name (:file other-show) true) "\""))))
                 (vals @open-shows)))))

(defn- delete-action
  "Creates the menu action which deletes a track after confirmation."
  [show track panel]
  (seesaw/action :handler (fn [_]
                            (when (seesaw/confirm panel (str "This will irreversibly remove the track, losing any\r\n"
                                                             "configuration, expressions, and cues created for it.")
                                                  :type :question :title "Delete Track?")
                              (try
                                (let [track-root (build-track-path show (:signature track))]
                                  (doseq [path (-> (Files/walk (.toAbsolutePath track-root)
                                                               (make-array java.nio.file.FileVisitOption 0))
                                                   (.sorted #(compare (str %2) (str %1)))
                                                   .iterator
                                                   iterator-seq)]
                                    (Files/delete path)))
                                (swap! open-shows update-in [(:file show) :tracks] dissoc (:signature track))
                                (swap! open-shows update-in [(:file show) :panels] dissoc panel)
                                (refresh-signatures show)
                                (update-track-visibility show)
                                (flush-show show)
                                (catch Exception e
                                  (timbre/error e "Problem deleting track")
                                  (seesaw/alert "Problem deleting track:" e)))))
                 :name "Delete Track"))

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

                                       ["Loaded Message:" "gap unrelated"]
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
                                        "hidemode 3"]  ; TODO: Loaded state indicator canvas.

                                       ["Playing Message:" "gap unrelated"]
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
                                                                  #(assoc-track-content show signature :enabled
                                                                                        (seesaw/value %))])
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
                                       (editor-actions show track panel gear)
                                       [(seesaw/separator) (inspect-action track) (seesaw/separator)]
                                       (copy-actions show track)
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
    (update-gear-icon track gear)

    ;; Update output status when selection changes, giving a chance for the other handlers to run first
    ;; so the data is ready.
    (seesaw/listen (seesaw/select panel [:#outputs])
                   :item-state-changed (fn [_] (seesaw/invoke-later (show-midi-status track))))
    (attach-message-visibility-handler show track "loaded" gear)
    (attach-message-visibility-handler show track "playing" gear)

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

    ;; We are done creating the track, so arm the menu listeners to automatically pop up expression editors when
    ;; the user requests a custom message.
    (swap! open-shows update-in [(:file show) :tracks signature] dissoc :creating)))

(defn- close-track-editors?
  "Tries closing all open expression editors for the track. If
  `force?` is true, simply closes them even if they have unsaved
  changes. Othewise checks whether the user wants to save any unsaved
  changes. Returns truthy if there are none left open the user wants
  to deal with."
  [force? track]
  (every? (partial editors/close-editor? force?) (vals (:expression-editors (latest-track track)))))

(defn- cleanup-track
  "Process the removal of a track, either via deletion, or because the
  show is closing. If `force?` is true, any unsaved expression editors
  will simply be closed. Otherwise, they will block the track removal,
  which will be indicated by this function returning falsey."
  [force? show track]
  (when (close-track-editors? force? track)
    (run-track-function show track :shutdown nil true)
    true))

(defn- create-track-panels
  "Creates all the panels that represent tracks in the show."
  [show]
  (let [tracks-path (build-filesystem-path (:filesystem show) "tracks")]
    (when (Files/isReadable tracks-path)  ; We have imported at least one track.
      (doseq [track-path (Files/newDirectoryStream tracks-path)]
        (create-track-panel show track-path)))))

(defn update-global-expression-icons
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

                  (= label "Edit Global Shutdown Expression")
                  (.setIcon item (seesaw/icon (if (empty? (get-in show [:contents :expressions :shutdown]))
                                                "images/Gear-outline.png"
                                                "images/Gear-icon.png"))))))))))

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

(defn import-from-media
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

(defn- run-global-function
  "Checks whether the show has a custom function of the specified kind
  installed, and if so runs it with a nil status and track local
  atom, and the show's global atom. Returns a tuple of the function
  return value and any thrown exception."
  [show kind]
  (let [show (latest-show show)]
    (when-let [expression-fn (get-in show [:expression-fns kind])]
      (try
        [(expression-fn nil nil (:expression-globals show)) nil]
        (catch Throwable t
          (timbre/error t "Problem running show global " kind " expression,"
                        (get-in show [:contents :expressions kind]))
          (seesaw/alert (str "<html>Problem running show global " (name kind) " expression.<br><br>" t)
                        :title "Exception in Show Expression" :type :error)
          [nil t])))))



(defn build-global-editor-action
  "Creates an action which edits one of a show's global expressions."
  [show kind]
  (seesaw/action :handler (fn [e] (editors/show-show-editor open-shows kind (latest-show show) nil (:frame show)
                                                            (fn []
                                                              (when (= :setup kind)
                                                                (run-global-function show :shutdown)
                                                                (reset! (:expression-globals show) {})
                                                                (run-global-function show :setup))
                                                              (update-global-expression-icons show))))
                 :name (str "Edit " (get-in editors/global-trigger-editors [kind :title]))
                 :tip (get-in editors/global-trigger-editors [kind :tip])
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
  (swap! open-shows assoc-in [(:file show) :contents :enabled] enabled))

(defn- set-loaded-only
  "Update the show UI so that all track or only loaded tracks are
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

(defn- restore-window-position
  "Tries to put the window back in the position where it was saved in
  the show `contents`. If no saved position is found, or if the saved
  position is within 100 pixels of going off the bottom right of the
  screen, the window is instead positioned centered on the screen."
  [window contents]
  (let [[x y width height] (:window contents)
        dm (.getDisplayMode (.getDefaultScreenDevice (java.awt.GraphicsEnvironment/getLocalGraphicsEnvironment)))]
    (if (or (nil? x)
            (> x (- (.getWidth dm) 100))
            (> y (- (.getHeight dm) 100)))
      (.setLocationRelativeTo window nil)
      (.setBounds window x y width height))))

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
  (let [[filesystem contents] (open-show-filesystem file)]
    (try
      (let [root            (seesaw/frame :title (str "Beat Link Show: " (util/trim-extension (.getPath file)))
                                          :on-close :nothing)
            import-menu     (seesaw/menu :text "Import Track")
            show            {:frame       root
                             :expression-globals (atom {})
                             :import-menu import-menu
                             :file        file
                             :filesystem  filesystem
                             :contents    contents
                             :tracks      {}  ; Lots of info about each track, including loaded metadata.
                             :panels      {}  ; Maps from panel object to track signature, for updating visibility.
                             :loaded      {}  ; Map from player number to signature that has been reported loaded.
                             :playing     {}  ; Map from player number to signature that has been reported playing.
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
                                (seesaw/invoke-later
                                 (seesaw/show! loaded-only)
                                 (doseq [announcement (.getCurrentDevices device-finder)]
                                   (update-player-item-visibility announcement show true))
                                 (update-track-visibility show)))
                              (stopped [this sender]
                                (seesaw/invoke-later
                                 (seesaw/hide! loaded-only)
                                 (doseq [announcement (.getCurrentDevices device-finder)]
                                   (update-player-item-visibility announcement show false))
                                 (update-track-visibility show))))
            sig-listener    (reify SignatureListener  ; Update the import submenu as tracks come and go.
                              (signatureChanged [this sig-update]
                                #_(timbre/info "signatureChanged:" sig-update)
                                (update-player-item-signature sig-update show)
                                (seesaw/invoke-later (update-track-visibility show))))
            window-name     (str "show-" (.getPath file))
            close-fn        (fn [force? quitting?]
                              ;; Closes the show window and performs all necessary cleanup If `force?` is true,
                              ;; will do so even in the presence of windows with unsaved user changes. Otherwise
                              ;; prompts the user about all unsaved changes, giving them a chance to veto the
                              ;; closure. Returns truthy if the show was closed.
                              (let [show (latest-show show)]
                                (when (and (every? (partial close-track-editors? false) (vals (:tracks show)))
                                           (every? (partial editors/close-editor? false)
                                                   (vals (:expression-editors show))))
                                  (.removeDeviceAnnouncementListener device-finder dev-listener)
                                  (.removeLifecycleListener metadata-finder mf-listener)
                                  (.removeSignatureListener signature-finder sig-listener)
                                  (doseq [track (vals (:tracks show))]
                                    (cleanup-track true show track))
                                  (run-global-function show :shutdown)
                                  (try
                                    (save-show show false)
                                    (catch Throwable t
                                      (timbre/error t "Problem closing Show file.")
                                      (seesaw/alert root (str "<html>Problem Closing Show.<br><br>" t)
                                                    :title "Problem Closing Show" :type :error)))
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
        (seesaw/config! import-menu :items (build-import-submenu-items show))
        (seesaw/config! root :menubar (build-show-menubar show) :content layout)
        (create-track-panels show)
        (update-track-visibility show)
        (refresh-signatures show)
        (seesaw/listen filter-field #{:remove-update :insert-update :changed-update}
                       (fn [e] (filter-text-changed show (seesaw/text e))))
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
              (swap! open-shows assoc-in [(:file show) :expression-fns kind]
                     (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                        (editors/show-editor-title kind show nil)))
              (catch Exception e
                (timbre/error e (str "Problem parsing " (:title editor-info)
                                     " when loading Show. Expression:\n" expr "\n"))
                (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                   "Check the log file for details.")
                              :title "Exception during Clojure evaluation" :type :error)))))
        (run-global-function show :setup)
        (update-global-expression-icons show)
        (seesaw/show! root))
      (catch Throwable t
        (swap! open-shows dissoc file)
        (.close filesystem)
        (throw t)))))

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
