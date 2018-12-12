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
  "Returns the current version of the show given a stale copy with which
  an object was created."
  [show]
  (get @open-shows (:file show)))

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
  track's Stopped expression, if there is one."
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
  Unloaded expression, if there is one."
  [show player signature]
  (let [shows      (swap! open-shows update-in [(:file show) :tracks signature :loaded] disj player)
        now-loaded (get-in shows [(:file show) :tracks signature :loaded])]
    (when (empty? now-loaded)
      ;; TODO: Run the Unloaded expression.
      ;; TODO: Stop the position animation loop.
      (when-let [preview ((get-in (latest-show show) [:tracks signature :preview]))]
        (.clearPlaybackState preview)))
    (update-loaded-text show signature now-loaded)))

(defn- now-loaded
  "Performs the bookeeping to reflect that the specified player now has
  the track with the specified signature loaded. If this is the first
  player to load the track, run the track's Loaded expression, if
  there is one."
  [show player signature]
  (let [shows      (swap! open-shows update-in [(:file show) :tracks signature :loaded] conj player)
        now-loaded (get-in shows [(:file show) :tracks signature :loaded])]
    (when (= #{player} now-loaded)
      ;; TODO: Run the Loaded expression.
      )
    (update-loaded-text show signature now-loaded)
    (when-let [position (.getLatestPositionFor time-finder player)]
      (when-let [preview ((get-in (latest-show show) [:tracks signature :preview]))]
        (.setPlaybackState preview player (.milliseconds position) (.playing position))))
    ;; TODO: Start an animation loop to update playback position markers and active cue indicators.
    ))

(defn- now-playing
  "Performs the bookeeping to reflect that the specified player is now
  playing the track with the specified signature. If this is the first
  player playing the track, run the track's Started expression, if
  there is one."
  [show player signature]
  ;; TODO: flesh out!
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
    (.setText item (str "from Player " (.player sig-update) disabled-reason)))
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
      (now-loaded show (.player sig-update) (.signature sig-update)))))

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

(defn- write-metadata
  "Writes the metadata for a track being imported to the show
  filesystem."
  [track-root ^org.deepsymmetry.beatlink.data.TrackMetadata metadata]
  (write-edn-path {:artist          (item-label (.getArtist metadata))
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
                   :title           (.getTitle metadata)}
                  (.resolve track-root "metadata.edn")))

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

(defn- format-artist-album
  [metadata]
  (clojure.string/join ": " (filter identity (map util/remove-blanks [(:artist metadata) (:album metadata)]))))

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
        panel          (mig/mig-panel :constraints [""
                                                    "[]unrelated[fill, 160]unrelated[fill, 408]"]
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
                                                         :selected-item (or (:midi-device contents) (first outputs))
                                                         :listen [:item-state-changed
                                                                  #(assoc-track-content show signature :midi-device
                                                                                        (seesaw/selection %))])]
                                       ])]
    (swap! open-shows assoc-in [(:file show) :tracks signature]
           {:signature         signature
            :metadata          metadata
            :contents          contents
            :original-contents contents
            :panel             panel
            :filter            (build-filter-target metadata comment)
            :preview           preview-loader
            :loaded            #{} ; The players that have this loaded.
            :playing           #{}}) ; The players actively playing this.
    ;; Record the initial setting of the MIDI Output choice in case this is a brand new track.
    (assoc-track-content show signature :midi-device (seesaw/selection (seesaw/select panel [:#outputs])))
    (swap! open-shows assoc-in [(:file show) :panels panel] signature)))

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
            {:keys [signature metadata beat-grid
                    preview detail art]}             track
            track-root                               (build-filesystem-path filesystem "tracks" signature)]
        (Files/createDirectories track-root (make-array java.nio.file.attribute.FileAttribute 0))
        (write-metadata track-root metadata)
        (when-let [cue-list (.getCueList metadata)]
          (write-cue-list track-root cue-list))
        (when beat-grid (write-beat-grid track-root beat-grid))
        (when preview (write-preview track-root preview))
        (when detail (write-detail track-root detail))
        (when art (write-art track-root art))
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
            beat-grid (.getLatestBeatGridFor beatgrid-finder player)
            preview   (.getLatestPreviewFor waveform-finder player)
            detail    (.getLatestDetailFor waveform-finder player)
            art       (.getLatestArtFor art-finder player)]
        (if (not= signature (.getLatestSignatureFor signature-finder player))
          (seesaw/alert (:frame show) (str "Track on Player " player " Changed during Attempted Import.")
                        :title "Track Import Failed" :type :error)
          (do
            (import-track show {:signature signature
                                :metadata  metadata
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
                              :metadata  metadata
                              :beat-grid beat-grid
                              :preview   preview
                              :detail    detail
                              :art       art}))
        (refresh-signatures))
      (finally
        (try
          (when @anlz-atom (.. @anlz-atom _io close))
          (catch Throwable t
            (timbre/error t "Problem closing parsed rekordbox file" anlz-file)))
        (try
          (when @ext-atom (.. @ext-atom _io close))
          (catch Throwable t
            (timbre/error t "Problem closing parsed rekordbox file" ext-file)))))))

(defn save-track-contents
  "Saves the contents maps for any tracks that have changed them."
  [show]
  (doseq [track (vals (:tracks (latest-show show)))]
    (let [contents (:contents track)]
      (when (not= contents (:original-contents track))
        (write-edn-path contents (.resolve (build-track-path show (:signature track)) "contents.edn"))
        (swap! open-shows assoc-in [(:file show) :tracks (:signature track) :original-contents] contents)))))

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

(defn- save-all-open-shows
  "Updates and closes the ZIP filesystems associated with any open
  shows, so they get flushed out to the show files. Called when the
  virtual machine is exiting as a shutdown hook to make sure changes
  get saved. It is not safe to call this at any other time because it
  makes the shows unusable."
  []
  (doseq [show (vals @open-shows)]
    (timbre/info "Closing Show due to shutdown:" (:file show))
    (try
      (save-show show false)
      (catch Throwable t
        (timbre/error t "Problem saving show" (:file show))))))

(defonce ^{:private true
           :doc "Register the shutdown hook the first time this
  namespace is loaded."}
  shutdown-registered (do
                        (.addShutdownHook (Runtime/getRuntime) (Thread. save-all-open-shows))
                        true))

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
                            (when-let [file (chooser/choose-file (:frame show) :type :save
                                                                 :all-files? false
                                                                 :filters [["BeatLinkTrigger Show files"
                                                                            ["blts"]]])]
                              (if (get @open-shows file)
                                (seesaw/alert (:frame show) "Cannot Replace an Open Show."
                                              :title "Destination is Already Open" :type :error)
                                (when-let [file (util/confirm-overwrite-file file "blts" (:frame show))]

                                  (try
                                    (save-show-as show file)
                                    (catch Throwable t
                                      (timbre/error t "Problem Saving Show as" file)
                                      (seesaw/alert (:frame show) (str "<html>Unable to Save As " file ".<br><br>" t)
                                                    :title "Problem Saving Show Copy" :type :error)))))))
                 :name "Save As"))

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

(defn- build-show-menubar
  "Creates the menu bar for a show window, given the show map and the
  import submenu."
  [show]
  (seesaw/menubar :items [(seesaw/menu :text "File"
                                       :items [(build-save-action show) (build-save-as-action show) (seesaw/separator)
                                               (build-close-action show)])
                          (seesaw/menu :text "Track"
                                       :items [(:import-menu show)])
                          (menus/build-help-menu)]))

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
  (let [text-width (max 180 (int (/ (- width 140) 4)))
        preview-width (max 408 (* text-width 3))]
    (doseq [panel panels]
      (seesaw/config! panel :constraints
                      ["" (str "[]unrelated[fill, " text-width "]unrelated[fill, " preview-width "]")])
      (.revalidate panel))))

(defn- create-show-window
  "Create and show a new show window on the specified file."
  [file]
  (util/load-fonts)
  (let [[filesystem contents] (open-show-filesystem file)]
    (try
      (let [root            (seesaw/frame :title (str "Beat Link Show: " (.getPath file)) :on-close :dispose)
            import-menu     (seesaw/menu :text "Import Track")
            show            {:frame       root
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
            window-name     (str "show-" (.getPath file))]
        (swap! open-shows assoc file show)
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
        (.setSize root 800 600)  ; Our default size if there isn't a position stored in the file.
        (restore-window-position root contents)
        (seesaw/listen root
                       :window-closed
                       (fn [e]
                         (.removeDeviceAnnouncementListener device-finder dev-listener)
                         (.removeLifecycleListener metadata-finder mf-listener)
                         (.removeSignatureListener signature-finder sig-listener)
                         (try
                           (save-show show false)
                           (catch Throwable t
                             (timbre/error t "Problem closing Show file.")
                             (seesaw/alert root (str "<html>Problem Closing Show.<br><br>" t)
                                           :title "Problem Closing Show" :type :error)))
                         (swap! open-shows dissoc file)
                         (swap! util/window-positions dissoc window-name))
                       #{:component-moved :component-resized}
                       (fn [e]
                         (util/save-window-position root window-name)
                         (when (= (.getID e) java.awt.event.ComponentEvent/COMPONENT_RESIZED)
                           (resize-track-panels (keys (:panels (latest-show show))) (.getWidth root)))))
        (resize-track-panels (keys (:panels (latest-show show))) (.getWidth root))
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
                                     :filters [["BeatLinkTrigger Show files" ["blts"]]])]
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
  (when-let [file (chooser/choose-file parent :type :save
                                       :all-files? false
                                       :filters [["BeatLinkTrigger Show files"
                                                  ["blts"]]])]
    (let [file (.getCanonicalFile file)]
      (if (get @open-shows file)
        (seesaw/alert parent "Cannot Replace an Open Show."
                      :title "Show is Already Open" :type :error)
        (when-let [file (util/confirm-overwrite-file file "blts" parent)]
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
                            :title "Problem Writing File" :type :error))))))))
