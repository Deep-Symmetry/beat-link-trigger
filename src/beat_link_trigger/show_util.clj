(ns beat-link-trigger.show-util
  "Defines utility functions used by both show and cue windows."
  (:require [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.util :as util]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [fipp.edn :as fipp]
            [inspector-jay.core :as inspector]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [beat_link_trigger.util MidiChoice]
           [org.deepsymmetry.beatlink CdjStatus$TrackSourceSlot]
           [org.deepsymmetry.beatlink.data AlbumArt BeatGrid CueList DataReference WaveformDetail WaveformPreview]
           [org.deepsymmetry.beatlink.dbserver Message]
           [java.io File]
           [java.nio.file Files FileSystem FileSystems OpenOption Path StandardOpenOption]
           [java.util UUID]
           [javax.swing JComponent JFrame]))

(defonce ^{:private true
           :doc "Holds the map of open shows; keys are the file,
  values are a map containing the root of the window, the file (for
  ease of updating the entry), the ZIP filesystem providing
  heierarcical access to the contents of the file, and the map
  describing them."}
  open-shows (atom {}))

(defn get-open-shows
  "Get the current map of open shows; keys are the file, values are a
  map containing the root of the window, the file (for ease of
  updating the entry), the ZIP filesystem providing heierarcical
  access to the contents of the file, and the map describing them."
  []
  @open-shows)

(defn build-filesystem-path
  "Construct a path in the specified filesystem; translates from
  idiomatic Clojure to Java interop with the `java.nio` package."
  ^Path [^FileSystem filesystem & elements]
  (.getPath filesystem (first elements) (into-array String (rest elements))))

(defn read-edn-path
  "Parse the file at the specified path as EDN, and return the results."
  [path]
  #_(timbre/info "Reading from" path "in filesystem" (.getFileSystem path))
  (edn/read-string {:readers @prefs/prefs-readers} (String. (Files/readAllBytes path) "UTF-8")))

(def ^{:private true
       :tag     "[Ljava.nio.file.StandardOpenOption;"}
  write-edn-options
  "The Filesystem options used when writing EDN data into a show file path."
  (into-array [StandardOpenOption/CREATE StandardOpenOption/TRUNCATE_EXISTING StandardOpenOption/WRITE]))

(defn write-edn-path
  "Write the supplied data as EDN to the specified path, truncating any previously existing file."
  [data ^Path path]
  #_(timbre/info "Writing" data "to" path "in filesystem" (.getFileSystem path))
  (binding [*print-length* nil
            *print-level* nil]
    (let [^String formatted (with-out-str (fipp/pprint data))]
      (Files/write path (.getBytes formatted  "UTF-8") write-edn-options))))

(defn open-show-filesystem
  "Opens a show file as a ZIP filesystem so the individual elements
  inside of it can be accessed and updated. In the process verifies
  that the file is, in fact, a properly formatted Show ZIP file.
  Returns the opened and validated filesystem and the parsed contents
  map."
  [^File file]
  (try
    (let [filesystem (FileSystems/newFileSystem (.toPath file) (.getContextClassLoader (Thread/currentThread)))
          contents   (read-edn-path (build-filesystem-path filesystem "contents.edn"))]
      (when-not (= (:type contents) :beat-link-trigger.show/show)
        (throw (java.io.IOException. "Chosen file does not contain a Beat Link Trigger Show.")))
      (when-not (= (:version contents) 1)
        (throw (java.io.IOException. "Chosen Show is not supported by this version of Beat Link Trigger.")))
      [filesystem contents])
    (catch java.nio.file.ProviderNotFoundException e
      (throw (java.io.IOException. "Chosen file is not readable as a Show" e)))))

(defn latest-show
  "Returns the current version of the show given a potentially stale
  copy. `show-or-file` can either be a show map or the File from which one was
  loaded."
  [show-or-file]
  (if (instance? java.io.File show-or-file)
    (get @open-shows show-or-file)
    (get @open-shows (:file show-or-file))))

(defn latest-track
  "Returns the current version of a track given a potentially stale
  copy."
  [track]
  (get-in @open-shows [(:file track) :tracks (:signature track)]))

(defn latest-show-and-track
  "Returns the latest version of the show to which the supplied track
  belongs, and the latest version of the track itself."
  [track]
  (let [show (get @open-shows (:file track))]
    [show (get-in show [:tracks (:signature track)])]))

(defn swap-show!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  specified show."
  [show f & args]
  (swap! open-shows #(apply update % (:file show) f args)))

(defn swap-track!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  specified track, which must be a full track map."
  [track f & args]
  (swap! open-shows #(apply update-in % [(:file track) :tracks (:signature track)] f args)))

(defn swap-signature!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  track with the specified signature. The value of `show` can either
  be a full show map, or the File from which one was loaded."
  [show signature f & args]
  (let [show-file (if (instance? java.io.File show) show (:file show))]
    (swap! open-shows #(apply update-in % [show-file :tracks signature] f args))))

(defn build-track-path
  "Creates an up-to-date path into the current show filesystem for the
  content of the track with the given signature."
  ^Path [show signature]
  (let [show (latest-show show)]
    (build-filesystem-path (:filesystem show) "tracks" signature)))

(defn latest-phrase
  "Returns the current version of a phrase trigger given a potentially
  stale copy."
  [show phrase]
  (get-in (latest-show show) [:contents :phrases (:uuid phrase)]))

(defn latest-show-and-phrase
  "Returns the latest version of the show to which the supplied phrase
  trigger belongs, and the latest version of the phrase trigger
  itself."
  [show phrase]
  (let [show (latest-show show)]
    [show (get-in show [:contents :phrases (:uuid phrase)])]))

(defn swap-phrase!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  specified phrase, which can either be a UUID or a full phrase
  trigger map."
  [show phrase-or-uuid f & args]
  (let [uuid (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))]
    (swap! open-shows #(apply update-in % [(:file show) :contents :phrases uuid] f args))))

(def ^{:tag "[Ljava.nio.file.OpenOption;"}
  empty-open-options
  "The Filesystem options used for default behavior."
  (make-array OpenOption 0))

(defn gather-byte-buffers
  "Collects all the sqeuentially numbered files with the specified
  prefix and suffix for the specified track into a vector of byte
  buffers."
  [prefix suffix ^Path track-root]
  (loop [byte-buffers []
         idx          0]
    (let [file-path   (.resolve track-root (str prefix idx suffix))
          next-buffer (when (Files/isReadable file-path)
                        (with-open [file-channel (Files/newByteChannel file-path empty-open-options)]
                          (let [buffer (java.nio.ByteBuffer/allocate (.size file-channel))]
                            (.read file-channel buffer)
                            (.flip buffer))))]
        (if next-buffer
          (recur (conj byte-buffers next-buffer) (inc idx))
          byte-buffers))))

(def ^{:tag DataReference} dummy-reference
  "A meaningless data reference we can use to construct metadata items
  from the show file."
  (DataReference. 0 CdjStatus$TrackSourceSlot/COLLECTION 0))

(defn read-art
  "Loads album art for an imported track. Returns `nil` if none is
  found. If it should have a particular simulated source, you can pass
  `data-reference`, otherwise a meaningless dummy one is used."
  ([^Path track-root]
   (read-art track-root dummy-reference))
  ([^Path track-root ^DataReference data-reference]
   (let [path (.resolve track-root "art.jpg")]
     (when (Files/isReadable path)
       (let [bytes (Files/readAllBytes path)]
         (AlbumArt. data-reference (java.nio.ByteBuffer/wrap bytes)))))))

(defn read-beat-grid
  "Re-creates a [`BeatGrid`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/BeatGrid.html)
  from an imported track. Returns `nil` if none is found. If it should
  have a particular simulated source, you can pass `data-reference`,
  otherwise a meaningless dummy one is used."
  ([^Path track-root]
   (read-beat-grid track-root dummy-reference))
  ([^Path track-root ^DataReference data-reference]
   (when (Files/isReadable (.resolve track-root "beat-grid.edn"))
     (let [grid-vec (read-edn-path (.resolve track-root "beat-grid.edn"))
           beats    (int-array (map int (nth grid-vec 0)))
           times    (long-array (nth grid-vec 1))
           tempos   (if (> (count grid-vec) 2) ; Cope with older show beat grids that lack tempos.
                      (int-array (map int (nth grid-vec 2))) ; We have real tempo values.
                      (int-array (count beats)))] ; Just use zero for all tempos.
       (BeatGrid. data-reference beats tempos times)))))

(defn read-cue-list
  "Re-creates a CueList object from an imported track. Returns `nil` if
  none is found."
  [^Path track-root]
  (if (Files/isReadable (.resolve track-root "cue-list.dbserver"))
    (with-open [input-stream (Files/newInputStream (.resolve track-root "cue-list.dbserver") empty-open-options)
                data-stream  (java.io.DataInputStream. input-stream)]
      (CueList. (Message/read data-stream)))
    (let [tag-byte-buffers (gather-byte-buffers "cue-list-" ".kaitai" track-root)
          ext-byte-buffers (gather-byte-buffers "cue-extended-" ".kaitai" track-root)]
      (when (seq tag-byte-buffers) (CueList. tag-byte-buffers ext-byte-buffers)))))

(defn read-detail
  "Re-creates a [`WaveformDetail`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/WaveformDetail.html)
  from an imported track. Returns `nil` if none is found. If it should
  have a particular simulated source, you can pass `data-reference`,
  otherwise a meaningless dummy one is used."
  ([^Path track-root]
   (read-detail track-root dummy-reference))
  ([^Path track-root ^DataReference data-reference]
   (let [[path color?] (if (Files/isReadable (.resolve track-root "detail-color.data"))
                         [(.resolve track-root "detail-color.data") true]
                         [(.resolve track-root "detail.data") false])]
     (when (Files/isReadable path)
       (let [bytes (Files/readAllBytes path)]
         (WaveformDetail. data-reference (java.nio.ByteBuffer/wrap bytes) color?))))))

(defn read-preview
  "Re-creates a [`WaveformPreview`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/WaveformPreview.html)
  from an imported track. Returns `nil` if none is found. If it should
  have a particular simulated source, you can pass `data-reference`,
  otherwise a meaningless dummy one is used."
  ([^Path track-root]
   (read-preview track-root dummy-reference))
  ([^Path track-root ^DataReference data-reference]
   (let [[path color?] (if (Files/isReadable (.resolve track-root "preview-color.data"))
                         [(.resolve track-root "preview-color.data") true]
                         [(.resolve track-root "preview.data") false])]
     (when (Files/isReadable path)
       (let [bytes (Files/readAllBytes path)]
         (WaveformPreview. data-reference (java.nio.ByteBuffer/wrap bytes) color?))))))

(defn read-song-structure
  "Loads song structure (phrase analysis information) for an imported
  track. Returns `nil` if none is found. Otherwise, the raw bytes from
  which it can be recreated."
  [^Path track-root]
  (let [path (.resolve track-root "song-structure.kaitai")]
    (when (Files/isReadable path)
      (Files/readAllBytes path))))

(defn flush-show!
  "Closes the ZIP filesystem so that changes are written to the actual
  show file, then reopens it."
  [show]
  (let [{:keys [^File file ^FileSystem filesystem]} show]
    (swap! open-shows update file dissoc :filesystem)
    (try
      (.close filesystem)
      (catch Throwable t
        (timbre/error t "Problem flushing show filesystem!"))
      (finally
        (let [[reopened-filesystem] (open-show-filesystem file)]
          (swap! open-shows assoc-in [file :filesystem] reopened-filesystem))))))

(defn add-file-and-show-to-open-shows!
  "Adds a file and its show to the map of open shows. Should only be
  called by the show itself when it reaches the appropriate stage of
  opening."
  [file show]
  (swap! open-shows assoc file show))

(defn remove-file-from-open-shows!
  "Takes a show file out of the map of open shows. Done as part of the
  show closing, this should only be called by the show's shutdown
  function."
  [file]
  (swap! open-shows dissoc file))

(defn swap-cue!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  specified cue. The value of `track` must be a full track map, but
  the value of `cue` can either be a UUID or a full cue map."
  [track cue f & args]
  (let [uuid (if (instance? UUID cue) cue (:uuid cue))]
    (swap-track! track #(apply update-in % [:contents :cues :cues uuid] f args))))

(defn find-cue
  "Accepts either a UUID or a cue, and looks up the cue in the latest
  version of the track."
  [track uuid-or-cue]
  (let [track (latest-track track)]
    (get-in track [:contents :cues :cues (if (instance? UUID uuid-or-cue)
                                           uuid-or-cue
                                           (:uuid uuid-or-cue))])))

(defn swap-phrase-cue!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  specified cue, within a phrase trigger section whose `section` is
  one of `:start`, `:loop`, `:end`, or `:fill`. The value of `phrase`
  must be a full phrase trigger map, but the value of `cue` can either
  be a UUID or a full cue map."
  [show phrase section cue f & args]
  (let [uuid (if (instance? UUID cue) cue (:uuid cue))]
    (swap-phrase! show phrase #(apply update-in % [:contents :cues :cues section uuid] f args))))

(defn find-phrase-cue
  "Accepts either a UUID or a cue, and looks up the cue in the latest
  version of the phrase trigger section of the specified `section` (one
  of `:start`, `:loop`, `:end`, or `:fill`)."
  [show phrase section uuid-or-cue]
  (let [phrase (latest-phrase show phrase)]
    (get-in phrase [:contents :cues :cues section (if (instance? UUID uuid-or-cue)
                                                    uuid-or-cue
                                                    (:uuid uuid-or-cue))])))

(defn restore-window-position
  "Tries to put the window back in the position where it was saved in
  the show `contents`. If no saved position is found, or if the saved
  position is within 100 pixels of going off the bottom right of the
  screen, the window is instead positioned centered on the screen, or
  on the parent component if one was supplied."
  ([^JFrame window contents]
   (restore-window-position window contents nil))
  ([^JFrame window contents ^JFrame parent]
   (let [[x y width height] (:window contents)
         dm (.getDisplayMode (.getDefaultScreenDevice (java.awt.GraphicsEnvironment/getLocalGraphicsEnvironment)))]
     (if (or (nil? x)
             (> x (- (.getWidth dm) 100))
             (> y (- (.getHeight dm) 100)))
       (.setLocationRelativeTo window parent)
       (.setBounds window x y width height)))))

(defn display-title
  "Returns the title of a track, or the string [no title] if it is
  empty"
  [track]
  (let [title (:title (:metadata track))]
    (if (str/blank? title) "[no title]" title)))

(defn track-inspect-action
  "Creates the menu action which allows a track's local bindings to be
  inspected. Offered in the popups of both track rows and their cue
  rows."
  [track]
  (seesaw/action :handler (fn [_] (try
                                    (inspector/inspect @(:expression-locals track)
                                                       :window-name (str "Expression Locals for "
                                                                         (display-title track)))
                                    (catch StackOverflowError _
                                      (util/inspect-overflowed))
                                    (catch Throwable t
                                      (util/inspect-failed t))))
                 :name "Inspect Expression Locals"
                 :tip "Examine any values set as Track locals by its Expressions."))

(defn phrase-inspect-action
  "Creates the menu action which allows a phrase trigger's local
  bindings to be inspected. Offered in the popups of both phrase rows
  and their cue rows."
  [phrase]
  (seesaw/action :handler (fn [_]
                            (let [comment (get-in phrase [:contents :comment])
                                  title (if (str/blank? comment) "[uncommented]" comment)]
                              (try
                                (inspector/inspect @(:expression-locals phrase)
                                                   :window-name (str "Expression Locals for " title))
                                (catch StackOverflowError _
                                  (util/inspect-overflowed))
                                (catch Throwable t
                                  (util/inspect-failed t)))))
                 :name "Inspect Expression Locals"
                 :tip "Examine any values set as Phrase Trigger locals by its Expressions."))

(defn random-beat
  "Creates a [`Beat`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html)
  with random attributes for simulating expression calls."
  []
  (util/simulate-beat {:beat          (inc (rand-int 4))
                       :device-number (inc (rand-int 4))
                       :bpm           (+ 4000 (rand-int 12000))}))

(defn random-cdj-status
  "Creates a [`CdjStatus`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html)
  with random attributes for simulating expression calls. If provided,
  the supplied options are used to further configure the object."
  ([]
   (random-cdj-status {}))
  ([options]
   (let [device (inc (rand-int 4))]
     (util/simulate-player-status (merge {:bb            (inc (rand-int 4))
                                          :beat          (rand-int 2000)
                                          :device-number device
                                          :bpm           (+ 4000 (rand-int 12000))
                                          :d-r           device
                                          :s-r           2
                                          :t-r           1
                                          :rekordbox     (inc (rand-int 1000))
                                          :f 0x40}
                                         options)))))

(defn random-beat-or-status
  "Creates either a [`Beat`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html)
  or a [`CdjStatus`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html)
  with random attributes for simulating expression calls. If provided,
  the supplied options are used to further configure the `CdjStatus`
  object when one is being created."
  ([]
   (random-beat-or-status {}))
  ([options]
   (if (zero? (rand-int 2))
     (random-beat)
     (random-cdj-status options))))

(defn random-beat-and-position
  "Creates random, mutually consistent
  [`Beat`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html)
  and
  [`TrackPositionUpdate`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TrackPositionUpdate.html)
  objects for simulating expression calls, using the track
  [`BeatGrid`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/BeatGrid.html)."
  [track]
  (let [^BeatGrid grid (:grid track)
        beat           (inc (rand-int (.beatCount grid)))
        time           (.getTimeWithinTrack grid beat)]
    [(util/simulate-beat {:beat          (.getBeatWithinBar grid beat)
                          :device-number (inc (rand-int 4))
                          :bpm           (+ 4000 (rand-int 12000))})
     (org.deepsymmetry.beatlink.data.TrackPositionUpdate. (System/nanoTime) time beat true true 1.0 false grid)]))

(defn get-chosen-output
  "Return the MIDI output to which messages should be sent for a given
  track, opening it if this is the first time we are using it, or
  reusing it if we already opened it. Returns `nil` if the output can
  not currently be found (it was disconnected, or present in a loaded
  file but not on this system).
  to be reloaded."
  [track]
  (when-let [^MidiChoice selection (get-in (latest-track track) [:contents :midi-device])]
    (let [device-name (.full_name selection)]
      (or (get @util/opened-outputs device-name)
          (try
            (let [new-output (midi/midi-out (java.util.regex.Pattern/quote device-name))]
              (swap! util/opened-outputs assoc device-name new-output)
              new-output)
            (catch IllegalArgumentException e  ; The chosen output is not currently available
              (timbre/debug e "Track using nonexisting MIDI output" device-name))
            (catch Exception e  ; Some other problem opening the device
              (timbre/error e "Problem opening device" device-name "(treating as unavailable)")))))))

(defn no-output-chosen
  "Returns truthy if the MIDI output menu for a track is empty, which
  will probably only happen if there are no MIDI outputs available on
  the host system, but we still want to allow non-MIDI expressions to
  operate."
  [track]
  (not (get-in (latest-track track) [:contents :midi-device])))

(defn enabled?
  "Checks whether the track is enabled, given its configuration and
  current state. `show` must be a current view of the show, it will
  not be looked up because this function is also used inside of a
  `swap!` that updates the show."
  [show track]
  (let [track         (get-in show [:tracks (:signature track)])
        output        (get-chosen-output track)
        track-setting (get-in track [:contents :enabled])
        show-setting  (get-in show [:contents :enabled])
        setting       (if (= track-setting "Default")
                        show-setting
                        track-setting)]
    (if (or output (no-output-chosen track))
      (case setting
        "Always" true
        "On-Air" ((set (vals (:on-air show))) (:signature track))
        "Master" ((set (vals (:master show))) (:signature track))
        "Custom" (get-in track [:expression-results :enabled])
        false)
      false)))

(defn update-track-gear-icon
  "Determines whether the gear button for a track should be hollow or
  filled in, depending on whether any cues or expressions have been
  assigned to it."
  ([track]
   (update-track-gear-icon track (seesaw/select (:panel track) [:#gear])))
  ([track gear]
   (let [track (latest-track track)]
     (seesaw/config! gear :icon (if (and
                                     (empty? (get-in track [:contents :cues :cues]))
                                     (every? clojure.string/blank? (vals (get-in track [:contents :expressions]))))
                                  (seesaw/icon "images/Gear-outline.png")
                                  (seesaw/icon "images/Gear-icon.png"))))))

(defn update-phrase-gear-icon
  "Determines whether the gear button for a phrase trigger should be
  hollow or filled in, depending on whether any cues or expressions
  have been assigned to it."
  ([show phrase]
   (update-phrase-gear-icon show phrase (seesaw/select (:panel phrase) [:#gear])))
  ([show phrase gear]
   (let [phrase (latest-phrase show phrase)]
     (seesaw/config! gear :icon (if (and
                                     (every? empty? (vals (get-in phrase [:contents :cues :cues])))
                                     (every? clojure.string/blank? (vals (get-in phrase [:contents :expressions]))))
                                  (seesaw/icon "images/Gear-outline.png")
                                  (seesaw/icon "images/Gear-icon.png"))))))

(defn repaint-preview
  "Tells the track's preview component to repaint itself because the
  overlaid cues have been edited in the cue window."
  [track]
  (when-let [preview-canvas ^JComponent (:preview-canvas track)]
    (.repaint preview-canvas)))

(defn update-row-visibility
  "Determines the tracks and phrases that should be visible given the
  filter text (if any) and state of the Only Loaded checkbox if we are
  online. Updates the show's `:visible` key to hold a vector of the
  visible track signatures, sorted by title then artist then
  signature. Then uses that to update the contents of the `tracks`
  panel appropriately."
  [show]
  (let [show            (latest-show show)
        tracks          (seesaw/select (:frame show) [:#tracks])
        text            (get-in show [:contents :filter] "")
        tracks-only?    (str/starts-with? text "track:")
        phrases-only?   (str/starts-with? text "phrase:")
        text            (str/replace text #"^(track:\s*)|(phrase:\s*)" "")
        loaded-only?    (get-in show [:contents :loaded-only])
        visible-tracks  (filter (fn [track]
                                  (and
                                   (not phrases-only?)
                                   (or (str/blank? text) (str/includes? (:filter track) text))
                                   (or (not loaded-only?) (not (util/online?))
                                       ((set (vals (.getSignatures util/signature-finder))) (:signature track)))))
                                (vals (:tracks show)))
        sorted-tracks   (sort-by (juxt #(str/lower-case (or (get-in % [:metadata :title]) ""))
                                       #(str/lower-case (or (get-in % [:metadata :artist]) ""))
                                       :signature)
                                 visible-tracks)
        visible-phrases (filter identity
                                (for [uuid (get-in show [:contents :phrase-order])]
                                  (let [target (get-in show [:phrases uuid :filter] "")]
                                    (when (and
                                           (not tracks-only?)
                                           (or (str/blank? text)
                                               (str/includes? target text))
                                           (or (not loaded-only?) (not (util/online?))
                                               true ;; TODO: Add test for activation of the phrase trigger here.
                                               ))
                                      (get-in show [:contents :phrases uuid])))))]
    (swap-show! show assoc :visible (mapv :signature sorted-tracks)
                :vis-phrases (mapv :uuid visible-phrases))
    (doall (map (fn [row color]
                  (seesaw/config! (:panel row) :background color))
                sorted-tracks (cycle ["#eee" "#ddd"])))
    (doall (map (fn [row color]
                  (seesaw/config! (get-in show [:phrases (:uuid row) :panel]) :background color))
                visible-phrases (drop (count sorted-tracks) (cycle ["#eef" "#ddf"]))))
    (when tracks  ; If the show has a custom user panel installed, this will be nil.
      (seesaw/config! tracks :items (concat (map :panel sorted-tracks)
                                            (map (fn [{:keys [uuid]}]
                                                   (get-in show [:phrases uuid :panel]))
                                                 visible-phrases)
                                            [:fill-v])))))
