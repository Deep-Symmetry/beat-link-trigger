(ns beat-link-trigger.show-util
  "Defines utility functions used by both show and cue windows."
  (:require [beat-link-trigger.help :as help]
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.util :as util]
            [clojure.edn :as edn]
            [clojure.java.browse]
            [clojure.string :as str]
            [fipp.edn :as fipp]
            [hiccup.util]
            [inspector-jay.core :as inspector]
            [overtone.midi :as midi]
            [me.raynes.fs :as fs]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre]
            [thi.ng.color.core :as color]
            [thi.ng.math.core :as thing-math])
  (:import [beat_link_trigger.util MidiChoice]
           [org.deepsymmetry.beatlink Beat CdjStatus$PlayState1 CdjStatus$TrackSourceSlot Util]
           [org.deepsymmetry.beatlink.data AlbumArt BeatGrid CueList DataReference TrackPositionUpdate
            WaveformDetail WaveformFinder$WaveformStyle WaveformPreview]
           [org.deepsymmetry.beatlink.dbserver Message]
           [java.awt Color]
           [java.io File]
           [java.nio.file Files FileSystem FileSystems OpenOption Path StandardOpenOption]
           [java.util UUID]
           [javax.swing JComponent JDialog JFrame JList JPanel]))

(defonce ^{:private true
           :doc "Holds the map of open shows; keys are the file,
  values are a map containing the root of the window, the file (for
  ease of updating the entry), the ZIP filesystem providing
  heierarcical access to the contents of the file, and the map
  describing them."}
  open-shows (atom {}))

(def show-file-version
  "Holds the version of show files we create and are capable of reading."
  2)

(defn get-open-shows
  "Get the current map of open shows; keys are the file, values are a
  map containing the root of the window, the file (for ease of
  updating the entry), the ZIP filesystem providing heierarcical
  access to the contents of the file, and the map describing them."
  []
  @open-shows)

(def min-beat-distance
  "The number of nanoseconds that must have elapsed since the last
  beat packet was received before we can trust the beat number in a
  status packet."
  (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS 5))

(defn capture-current-state
  "Copies the expression-relevant show state into the `:last` key,
  so that changes can be examined after a `swap!` operation, and
  appropriate expressions run and user interface updates made."
  [show]
  (let [state (select-keys show [:loaded :playing :tracks :playing-phrases :phrases])]
    (assoc show :last state)))

(defonce ^{:private true
           :doc "Holds a map from phrase UUIDs to the files of the
  shows in which they belong, to enable them to be more conveniently
  passed around and manipulated, even though they can't have that
  link as an internal element like tracks, because they live entirely
  within the saved content of the show."}
  phrase-show-files (atom {}))

(defn track?
  "Given a cue context, returns truthy if it is a track map."
  [context]
  (:signature context))

(defn phrase?
  "Given a cue context, returns truthy if it is a phrase trigger map."
  [context]
  (:uuid context))

(defn phrase-added
  "Records that a phrase exists in a show, so that its show can be
  easily found for updates."
  [show phrase-or-uuid]
  (let [uuid (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))]
    (swap! phrase-show-files assoc uuid (:file show))))

(defn phrase-removed
  "Records that a phrase no longer exists in a show."
  [phrase-or-uuid]
  (let [uuid (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))]
    (swap! phrase-show-files dissoc uuid)))

(defn show-file-from-phrase
  "Looks up the file of the show to which a phrase belongs, given its
  registered UUID."
  [phrase-or-uuid]
  (let [uuid (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))]
    (get @phrase-show-files uuid)))

(defn show-from-phrase
  "Looks up the show to which a phrase belongs, given its registered
  UUID."
  [phrase-or-uuid]
  (let [uuid (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))]
    (get @open-shows (get @phrase-show-files uuid))))

(defn show-from-context
  "Looks up the show to which a track or phrase belongs."
  [context]
  (if (track? context)
    (get @open-shows (:file context))
    (show-from-phrase context)))

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

(def ^:private ^StandardOpenOption/1 write-edn-options
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
      (when-not (<= (:version contents) show-file-version)
        (throw (java.io.IOException. "Chosen Show requires a newer version of Beat Link Trigger.")))
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
  (when (nil? (:file show)) (throw (IllegalArgumentException. "swap-show! show cannot have a nil file.")))
  (swap! open-shows #(apply update % (:file show) f args)))

(defn swap-track!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  specified track, which must be a full track map."
  [track f & args]
  (when (nil? (:file track)) (throw (IllegalArgumentException. "swap-track! track cannot have a nil file")))
  (swap! open-shows #(apply update-in % [(:file track) :tracks (:signature track)] f args)))

(defn swap-signature!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  track with the specified signature. The value of `show` can either
  be a full show map, or the File from which one was loaded."
  [show signature f & args]
  (let [show-file (if (instance? java.io.File show) show (:file show))]
    (when (nil? show-file) (throw (IllegalArgumentException. "swap-signature! show-file cannot be nil")))
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
  ([phrase]
   (let [show (show-from-phrase phrase)]
     (get-in show [:contents :phrases (:uuid phrase)])))
  ([show phrase]
   (get-in (latest-show show) [:contents :phrases (:uuid phrase)])))

(defn latest-show-and-phrase
  "Returns the latest version of the show to which the supplied phrase
  trigger belongs, and the latest version of the phrase trigger
  itself."
  ([phrase]
   (let [show (show-from-phrase phrase)]
     [show (get-in show [:contents :phrases (:uuid phrase)])]))
  ([show phrase]
   (let [show (latest-show show)]
     [show (get-in show [:contents :phrases (:uuid phrase)])])))

(defn swap-phrase!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  specified phrase, which can either be a UUID or a full phrase
  trigger map."
  [show phrase-or-uuid f & args]
  (when (nil? (:file show)) (throw (IllegalArgumentException. "swap-phrase! show cannot have a nil show file.")))
  (let [uuid (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))]
    (swap! open-shows #(apply update-in % [(:file show) :contents :phrases uuid] f args))))

(defn swap-phrase-runtime!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current
  runtime (non-saved) information of the specified phrase, which can
  either be a UUID or a full phrase trigger map."
  [show phrase-or-uuid f & args]
  (let [uuid (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))]
    (swap! open-shows #(apply update-in % [(:file show) :phrases uuid] f args))))

(defn swap-context!
  "Delegetes to either `swap-track!` or `swap-phrase!` depending on
  whether `context` is a track or phrase trigger map. `show` can be
  `nil` if it was not conveniently available, in which case it will be
  looked up when needed."
  [show context f & args]
  (if (track? context)
    (apply swap-track! context f args)
    (apply swap-phrase! (or show (show-from-phrase context)) context f args)))

(defn swap-context-runtime!
  "Delegetes to either `swap-track!` or `swap-phrase-runtime!` depending
  on whether `context` is a track or phrase trigger map.  `show` can be
  `nil` if it was not conveniently available, in which case it will be
  looked up when needed."
  [show context f & args]
  (if (track? context)
    (apply swap-track! context f args)
    (apply swap-phrase-runtime! (or show (show-from-phrase context)) context f args)))

(def ^OpenOption/1 empty-open-options
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

(defn waveform-filename
  "Determines the name under which a waveform should be saved, given its
  type prefix and the waveform style."
  [prefix ^WaveformFinder$WaveformStyle style]
  (str prefix
       (util/case-enum style
         WaveformFinder$WaveformStyle/BLUE       ""
         WaveformFinder$WaveformStyle/RGB        "-color"
         WaveformFinder$WaveformStyle/THREE_BAND "-3band")
       ".data"))

(defn find-readable-waveform
  "Given the root path of a track, and a waveform type prefix, tries all
  waveform styles until a corresponding readable file is found within
  the track, and returns a tuple of its path and the style, or `nil`
  is no readable file was found."
  [track-root prefix]
  (loop [styles (WaveformFinder$WaveformStyle/values)]
    (let [style (first styles)
          left  (rest styles)
          path  (.resolve track-root (waveform-filename prefix style))]
      (if (Files/isReadable path)
        [path style]
        (when (seq left) (recur left))))))

(defn read-detail
  "Re-creates a [`WaveformDetail`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/WaveformDetail.html)
  from an imported track. Returns `nil` if none is found. If it should
  have a particular simulated source, you can pass `data-reference`,
  otherwise a meaningless dummy one is used."
  ([^Path track-root]
   (read-detail track-root dummy-reference))
  ([^Path track-root ^DataReference data-reference]
   (let [[path style] (find-readable-waveform track-root "detail")]
     (when path
       (let [bytes (Files/readAllBytes path)]
         (WaveformDetail. data-reference (java.nio.ByteBuffer/wrap bytes) style))))))

(defn read-preview
  "Re-creates a [`WaveformPreview`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/WaveformPreview.html)
  from an imported track. Returns `nil` if none is found. If it should
  have a particular simulated source, you can pass `data-reference`,
  otherwise a meaningless dummy one is used."
  ([^Path track-root]
   (read-preview track-root dummy-reference))
  ([^Path track-root ^DataReference data-reference]
   (let [[path style] (find-readable-waveform track-root "preview")]
     (when path
       (let [bytes (Files/readAllBytes path)]
         (WaveformPreview. data-reference (java.nio.ByteBuffer/wrap bytes) style))))))

(defn read-song-structure
  "Loads song structure (phrase analysis information) for an imported
  track. Returns `nil` if none is found. Otherwise, the raw bytes from
  which it can be recreated."
  ^byte/1 [^Path track-root]
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
  "Returns the title of a track or phrase trigger, or the string [no
  title] if an untitled track, or [uncommented] if an unnamed phrase
  trigger. `context` must be current."
  [context]
  (if (track? context)
    (let [title (:title (:metadata context))]
      (if (str/blank? title) "[no title]" title))
    (let [comment (:comment context)]
      (if (str/blank? comment) "[uncommented]" comment))))

(defn phrase-runtime-info
  "Given a current show and a phrase map or UUID, returns the runtime
  cache map (non-saved information) for that phrase."
  [show phrase-or-uuid]
  (let [uuid (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))]
    (get-in show [:phrases uuid])))

(defn latest-show-and-context
  "Given a cue context, which can either be a track map or a phrase
  trigger map, and returns a tuple containing current versions of the
  show to which the track or phrase trigger belongs, the track or
  phase trigger map itself, and (in the case of phrase triggers) its
  runtime info map. For tracks, the runtime info is present at the
  root of the track itself, so it is returned as both context and
  runtime-info."
  [context]
  (if (track? context)
    (let [[show track] (latest-show-and-track context)]
      [show track track])
    (let [show (show-from-phrase context)]
      [show (latest-phrase show context) (phrase-runtime-info show context)])))

(defn inspect-action
  "Creates the menu action which allows a track or phrase trigger's
  local bindings to be inspected. Offered in the popups of both track
  rows and their cue rows."
  [context]
  (let [[_ _ runtime-info] (latest-show-and-context context)]
       (seesaw/action :handler (fn [_] (try
                                         (timbre/info "inspecting" @(:expression-locals runtime-info))
                                         (inspector/inspect @(:expression-locals runtime-info)
                                                            :window-name (str "Expression Locals for "
                                                                              (display-title context)))
                                         (catch StackOverflowError _
                                           (util/inspect-overflowed))
                                         (catch Throwable t
                                           (util/inspect-failed t))))
                      :name "Inspect Expression Locals"
                      :tip (str "Examine any values set as " (if (track? context) "Track" "Phrase Trigger")
                                " locals by its Expressions."))))

(defn- bpm-at-beat
  "Finds the actual track tempo at the specified beat, for use when
  simulating with actual track data."
  [beat]
  (let [from-grid (.getBpm ^BeatGrid (:grid util/*simulating*) beat)]
    (if (zero? from-grid)
      (long (* (get-in util/*simulating* [:metadata :starting-tempo]) 100))
      from-grid)))

(defn random-beat
  "Creates a [`Beat`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html)
  with random attributes for simulating expression calls. If a
  simulation context has been set up, makes it consistent with that
  data. If provided, the supplied options are used to further
  configure the object."
  ([]
   (random-beat {}))
  ([options]
   (let [chosen-beat (:beat util/*simulating*)
         beat        (if chosen-beat
                       (.getBeatWithinBar ^BeatGrid (:grid util/*simulating*) chosen-beat)
                       (inc (rand-int 4)))
         bpm         (if chosen-beat
                       (bpm-at-beat chosen-beat)
                       (+ 4000 (rand-int 12000)))]
     (util/simulate-beat (merge {:beat          beat
                                 :device-number (inc (rand-int 6))
                                 :bpm           bpm}
                                ;; TODO: Add next beats/bars information.
                                options)))))

(defn position-from-random-beat
  "When simulating a beat-TPU tuple, creates the `TrackPositionUpdate`
  consistent with the simulation context and chosen `Beat`."
  [^Beat beat-object]
  (let [{:keys [beat grid time]} util/*simulating*]
    (TrackPositionUpdate. (System/nanoTime) time beat true true (Util/pitchToMultiplier (.getPitch beat-object))
                          false grid)))

(defn random-cdj-status
  "Creates a [`CdjStatus`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html)
  with random attributes for simulating expression calls. If a
  simulation context has been set up, makes it consistent with that
  data. If provided, the supplied options are used to further
  configure the object."
  ([]
   (random-cdj-status {}))
  ([options]
   (let [device         (inc (rand-int 6))
         chosen-time    (:time util/*simulating*)
         ^BeatGrid grid (:grid util/*simulating*)
         chosen-beat    (when chosen-time (.findBeatAtTime grid chosen-time))]
     (util/simulate-player-status (merge {:bb            (if chosen-beat
                                                           (.getBeatWithinBar grid chosen-beat)
                                                           (inc (rand-int 4)))
                                          :beat          (or chosen-beat (rand-int 2000))
                                          :device-number device
                                          :bpm           (if chosen-beat
                                                           (bpm-at-beat chosen-beat)
                                                           (+ 4000 (rand-int 12000)))
                                          :d-r           device
                                          :s-r           2
                                          :t-r           1
                                          :rekordbox     (inc (rand-int 1000))
                                          :f             0x40}
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
     (random-beat options)
     (random-cdj-status options))))

(defn- get-chosen-output-internal
  "Finishes the task of `get-chosen-output` (see below) after the track
  or phrase trigger specific work of finding the selection value is
  done."
  [^MidiChoice selection]
  (let [device-name (.full_name selection)]
      (or (get @util/opened-outputs device-name)
          (try
            (let [new-output (midi/midi-out (str "^" (java.util.regex.Pattern/quote device-name) "$"))]
              (swap! util/opened-outputs assoc device-name new-output)
              new-output)
            (catch IllegalArgumentException e  ; The chosen output is not currently available
              (timbre/debug e "Track or phrase trigger using nonexisting MIDI output" device-name))
            (catch Exception e  ; Some other problem opening the device
              (timbre/error e "Problem opening device" device-name "(treating as unavailable)"))))))

(defn get-chosen-output
  "Return the MIDI output to which messages should be sent for a given
  track or phrase trigger, opening it if this is the first time we are
  using it, or reusing it if we already opened it. Returns `nil` if
  the output can not currently be found (it was disconnected, or
  present in a loaded file but not on this system)."
  [context]
  (if (track? context)
    (when-let [^MidiChoice selection (get-in (latest-track context) [:contents :midi-device])]
      (get-chosen-output-internal selection))
    (when-let [^MidiChoice selection (:midi-device (latest-phrase context))]
      (get-chosen-output-internal selection))))

(defn no-output-chosen
  "Returns truthy if the MIDI output menu for a track or phrase trigger
  is empty, which will probably only happen if there are no MIDI
  outputs available on the host system, but we still want to allow
  non-MIDI expressions to operate."
  [context]
  (if (track? context)
    (not (get-in (latest-track context) [:contents :midi-device]))
    (not (:midi-device (latest-phrase context)))))

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

(defn gear-content
  "Checks for reasons that a track or phrase trigger might need to be
  filled in. Returns a set that may contain `:cues` or `:expressions`
  if either or both are present."
  [context]
  (let [[_show context] (latest-show-and-context context)
        contents        (if (phrase? context) context (:contents context))]
    (set (filter identity
                 [(when (seq (get-in contents [:cues :cues])) :cues)
                  (when-not (every? clojure.string/blank? (vals (:expressions contents))) :expressions)]))))

(defn update-gear-icon
  "Determines whether the gear button for a track or phrase trigger
  should be hollow or filled in, depending on whether any cues or
  expressions have been assigned to it."
  ([context]
   (let [[_show context runtime-info] (latest-show-and-context context)]
     (update-gear-icon context (seesaw/select (:panel runtime-info) [:#gear]))))
  ([context gear]
   (prefs/update-gear-button gear (seq (gear-content context)))))

(defn repaint-preview
  "Tells the track or phrase trigger's preview component to repaint
  itself because the overlaid cues have been edited in the cue
  window."
  [context]
  (let [[_show _context runtime-info] (latest-show-and-context context)]
    (when-let [preview-canvas ^JComponent (:preview-canvas runtime-info)]
      (.repaint preview-canvas))))

(defn update-row-visibility
  "Determines the tracks and phrases that should be visible given the
  filter text (if any) and state of the Only Loaded checkbox if we are
  online or simulating playback. Updates the show's `:visible` key to
  hold a vector of the visible track signatures, sorted by title then
  artist then signature. Then uses that to update the contents of the
  `tracks` panel appropriately. If the current dark mode setting is
  known, it can be passed in to avoid redundant work."
  ([show]
   (update-row-visibility show (prefs/dark-mode?)))
  ([show dark?]
   (let [show            (latest-show show)
         tracks          (seesaw/select (:frame show) [:#tracks])
         text            (get-in show [:contents :filter] "")
         tracks-only?    (str/starts-with? text "track:")
         phrases-only?   (str/starts-with? text "phrase:")
         text            (str/replace text #"^(track:\s*)|(phrase:\s*)" "")
         loaded-only?    (get-in show [:contents :loaded-only])
         relevant?       (or (util/online?) ((requiring-resolve 'beat-link-trigger.simulator/simulating?)))
         signatures      (if (util/online?)
                           (set (vals (.getSignatures util/signature-finder)))
                           (set (->> ((requiring-resolve 'beat-link-trigger.simulator/track-signatures)))))
         visible-tracks  (filter (fn [track]
                                   (and
                                    (not phrases-only?)
                                    (or (str/blank? text) (str/includes? (:filter track) text))
                                    (or (not loaded-only?) (not relevant?)
                                        (signatures (:signature track)))))
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
                                            (or (not loaded-only?) (not relevant?)
                                                (get-in show [:phrases uuid :tripped])))
                                       (get-in show [:contents :phrases uuid])))))]
     (swap-show! show assoc :visible (mapv :signature sorted-tracks)
                 :visible-phrases (mapv :uuid visible-phrases))
     (doall (map (fn [row color]
                   (let [panel (:panel row)]
                     (seesaw/config! panel :background color)
                     (seesaw/config! (seesaw/select panel [:#title]) :foreground (if dark? Color/yellow Color/blue))
                     (seesaw/config! (seesaw/select panel [:#artist]) :foreground (if dark? Color/green Color/blue))))
                 sorted-tracks (cycle (if dark? ["#222" "#111"] ["#eee" "#ddd"]))))
     (doall (map (fn [row color]
                   (seesaw/config! (get-in show [:phrases (:uuid row) :panel]) :background color))
                 visible-phrases (drop (count sorted-tracks) (cycle (if dark? ["#224" "#114"] ["#eef" "#ddf"])))))
     (when tracks ; If the show has a custom user panel installed, this will be nil.
       (seesaw/config! tracks :items (concat (map :panel sorted-tracks)
                                             (map (fn [{:keys [uuid]}]
                                                    (get-in show [:phrases uuid :panel]))
                                                  visible-phrases)
                                             [:fill-v]))))))

(defn hue-to-color
  "Returns a `Color` object of the given `hue` (in degrees, ranging from
  0.0 to 360.0). If `lightness` is not specified, 0.5 is used, giving
  the purest, most intense version of the hue. The color is fully
  opaque."
  (^Color [hue]
   (hue-to-color hue 0.5))
  (^Color [hue lightness]
   (let [color (color/hsla (/ hue 360.0) 1.0 lightness)]
     (Color. @(color/as-int24 color)))))

(defn color-to-hue
  "Extracts the hue number (in degrees) from a Color object. If
  colorless, red is the default."
  [^Color color]
  (* 360.0 (color/hue (color/int32 (.getRGB color)))))

(def phrase-section-positions
  "Identifies the order in which sections of a phrase appear."
  {:start 0
   :loop  1
   :end   2
   :fill  3})

(def phrase-section-colors
  "The color to draw each section of a phrase trigger."
  {:start (hue-to-color 120.0 0.8)
   :loop  (hue-to-color 240.0 0.8)
   :end   (hue-to-color 0.0 0.8)
   :fill  (hue-to-color 280.0 0.75)})

(def ^Color playback-marker-color
  "The color used for drawing standard playback markers."
  (Color. 255 0 0 235))

(defn phrase-playback-marker-color
  "Determine the color to use for drawing a playback marker in a phrase
  trigger cue canvas, which depends on the section. If `next-section`
  is not `nil`, we are playing the final beat of a section, and this
  tells us what section comes next. If it is the same as `section` we
  are looping back, so we fade out the marker through the beat.
  Otherwise we gradually fade form the color of the current section to
  the color of the next section. `fraction` tells us how far into the
  beat we have reached."
  [section next-section fraction]
  (try
    (if next-section
      (if (= section next-section)
        ;; We are looping back to the start of this section, fade to transparent over the beat.
        (let [alpha  (.getAlpha playback-marker-color)
              scaled (int (* alpha (- 1.0 fraction)))]
          (Util/buildColor (phrase-section-colors section) (Color. 255 255 255 scaled)))
        ;; We are moving to another section, interpolate between the colors over the beat.
        (let [current (color/as-rgba (color/int32 (Color/.getRGB (phrase-section-colors section))))
              next    (color/as-rgba (color/int32 (Color/.getRGB (phrase-section-colors next-section))))
              blended (Color. @(color/as-int24 (thing-math/mix current next fraction)))]
          (Util/buildColor blended playback-marker-color)))
      ;; We are not ending a section, the color is simply that of the current section.
      (Util/buildColor (phrase-section-colors section) playback-marker-color))
    (catch Exception e
      (timbre/error e "Problem computing phrase playback marker color. section:" section
                    "next-section:" next-section "fraction:" fraction)
      ;; Return generic marker.
      playback-marker-color)))

(defn swap-cue!
  "Atomically updates the map of open shows by calling the specified
  function with the supplied arguments on the current contents of the
  specified cue. The value of `context` must be a full track map or
  phrase trigger map, but the value of `cue` can either be a UUID or a
  full cue map."
  [context cue f & args]
  (let [uuid (if (instance? UUID cue) cue (:uuid cue))]
    (if (track? context)
      (swap-track! context #(apply update-in % [:contents :cues :cues uuid] f args))
      (let [[show phrase] (latest-show-and-context context)]
        (swap-phrase! show phrase #(apply update-in % [:cues :cues uuid] f args))))))

(defn find-cue
  "Accepts either a UUID or a cue, and looks up the cue in the latest
  version of the track or phrase trigger context."
  [context uuid-or-cue]
  (if (track? context)
    (let [track (latest-track context)]
      (get-in track [:contents :cues :cues (if (instance? UUID uuid-or-cue)
                                             uuid-or-cue
                                             (:uuid uuid-or-cue))]))
    (let [phrase (latest-phrase context)]
    (get-in phrase [:cues :cues (if (instance? UUID uuid-or-cue)
                                  uuid-or-cue
                                  (:uuid uuid-or-cue))]))))

(def cue-canvas-margin
  "The number of pixels left black around the border of a phrase cue
  canvas on which sections and cues are drawn."
  4)

(def cue-canvas-pixels-per-beat
  "The number of horizontal pixels taken up by each beat in a scrolling
  cue canvas when fully zoomed in."
  40)

(defn cue-canvas-preview-bar-spacing
  "Calculate how many pixels apart each bar occurs given the total
  number of bars in the phrase and width of the component in which
  they are being rendered."
  [bars width]
  (quot (- width (* 2 cue-canvas-margin)) bars))

(defn cue-canvas-preview-bar-x
  "Calculate the x coordinate of a bar given the bar spacing."
  [bar spacing] (+ cue-canvas-margin (* bar spacing)))

(defn loop-phrase-trigger-beat
  "Normalizes a beat to fit within the specified phrase trigger section,
  looping it if necessary. Returns a tuple of the possibly-looped beat
  value, and a flag indicating whether it is about to loop after this
  beat."
  [runtime-info beat section]
  (let [[start-bar end-bar] (get-in runtime-info [:sections section])
        beat-modulus        (* 4 (- end-bar start-bar))]
    [(inc (mod (dec beat) beat-modulus)) (= (dec beat-modulus) (mod (dec (long beat)) beat-modulus))]))

(defn cue-canvas-preview-x-for-beat
  "Calculate the x coordinate where a beat falls in a cue canvas preview
  component. If `fraction` is supplied, moves that much towards the
  next beat."
  ([^JPanel canvas runtime-info beat section]
   (cue-canvas-preview-x-for-beat canvas runtime-info beat section 0))
  ([^JPanel canvas runtime-info beat section fraction]
   (let [sections        (:sections runtime-info)
         [start-bar]     (section sections)
         bar             (quot (dec beat) 4)
         beat-within-bar (mod (dec beat) 4)
         bar-spacing     (cue-canvas-preview-bar-spacing (:total-bars sections) (.getWidth canvas))]
     (+ (cue-canvas-preview-bar-x (+ start-bar bar) bar-spacing)
        (* (+ beat-within-bar fraction) (quot bar-spacing 4))))))

(defn cue-canvas-preview-x-for-time
  "Calculates the x position at which notional number if milliseconds
  would fall along a phrase trigger's prefview cue canvas, as if it
  was being played at 120 BPM, and the beats were all linearly
  related. This is used for drawing the portion covered by the cues
  editor scrolling canvas, if one is open. `runtime-info` must be
  current."
  [^JPanel canvas runtime-info time]
  (let [sections    (:sections runtime-info)
        bar-spacing (cue-canvas-preview-bar-spacing (:total-bars sections) (.getWidth canvas))
        bar         (/ time 2000)]
    (cue-canvas-preview-bar-x bar bar-spacing)))

(defn cue-canvas-preview-time-for-x
  "Calculates a notional number if milliseconds into a phrase trigger
  corresponding to a point along its previe cue canvas, as if it was
  being played at 120 BPM, and the beats were all linearly related.
  This is used to center the cues editor scrolling canvas on locations
  clicked by the user. `runtime-info` must be current."
  [^JPanel canvas runtime-info x]
  (let [sections    (:sections runtime-info)
        bar-spacing (cue-canvas-preview-bar-spacing (:total-bars sections) (.getWidth canvas))
        bar         (/ (- x cue-canvas-margin) bar-spacing)]
    (long (* bar 2000))))

(def cueing-states
  "The enumeration values for a CDJ Status' `playState1` property that
  indicate that cue play is taking place, so we should not consider
  beat packets an indication that the track is actually playing."
  #{CdjStatus$PlayState1/CUE_PLAYING CdjStatus$PlayState1/CUE_SCRATCHING})


(defn expression-report-link
  "Returns the URL that can be used to open a show's expression report."
  ([^File file]
   (expression-report-link file nil))
  ([^File file anchor]
   (let [port           (help/help-server)
         anchor-segment (when anchor (str "#" anchor))]
     (str "http://127.0.0.1:" port "/show/reports/expressions?show=" (hiccup.util/url-encode (.getAbsolutePath file))
          anchor-segment))))

(defn view-expressions-in-report-action
  "Creates a vector containing the menu action which opens the expression
  report window and scrolls it to this track or phrase."
  ([show context]
   (view-expressions-in-report-action show context nil))
  ([show context cue]
   (let [track? (track? context)]
     (seesaw/action :handler (fn [_]
                               (when (help/help-server)
                                 (clojure.java.browse/browse-url
                                  (expression-report-link
                                   (:file show) ((requiring-resolve 'beat-link-trigger.editors/show-report-tag)
                                                 context cue track?)))))
                    :name "View Expressions in Report"))))

(def ^java.nio.file.StandardCopyOption/1 copy-options-replace-existing
  "Copy options list that allows an existing file to be replaced."
  (into-array [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))

(defn- list-attachments
  "Helper function to list all the attachments in the show attachments
  folder. Returns just their names."
  [^Path folder]
  (if (Files/isDirectory folder (make-array java.nio.file.LinkOption 0))
    (-> (for [^Path attachment (Files/newDirectoryStream folder)]
          (.getFileName attachment))
        sort)
    []))

(defn- load-clojure-file-before-attaching
  "Called whenever the user attaches a Clojure file to a show. Loads the
  file first, both to get it installed, and to make sure that it
  contains no errors. Returns truthy if the file loaded smoothly, so
  it is safe to attach to the show."
  [parent file]
  (try
    (load-file (.getAbsolutePath file))
    true
    (catch Throwable t
      (timbre/error t "Problem loading Clojure attachment")
      (seesaw/alert parent
                    (str "<html>Problem loading " (.getName file) ":<br><br>" (.getMessage t)
                         "<br><br>See the log file for more details.</html>")
                    :title "Problem Loading Clojure Attachment" :type :error)
      nil)))

(defn manage-attachments
  "Provides a minimal user interface that allows files to be stored as
  attachments within a show, for the use of the show's expressions,
  and for those attachments to be downloaded again or deleted."
  [show]
  ;; While someday it would be nice to allow folders to be created,
  ;; navigated into, and deleted, that will require a custom list cell
  ;; renderer to visually distinguish between them and simple files,
  ;; which is more scope than I want to bite off while proving this
  ;; concept.
  (try
    (let [selected-attachment    (atom nil)
          ^FileSystem filesystem (:filesystem show)
          ^Path attachments-root (build-filesystem-path filesystem "attachments")
          ^JList attachment-list (seesaw/listbox :model (list-attachments attachments-root))
          attachment-scroll      (seesaw/scrollable attachment-list)
          add-button             (seesaw/button :text "Add Attachment")
          done-button            (seesaw/button :text "Done")
          delete-button          (seesaw/button :text "Delete" :enabled? false)
          extract-as-button      (seesaw/button :text "Extract As…" :enabled? false)
          layout                 (seesaw/border-panel :center attachment-scroll)
          ^JDialog dialog        (seesaw/dialog :content layout
                                                :options [done-button extract-as-button delete-button add-button]
                                                :title "Manage Show Attachments")]
      (.setSize dialog 600 400)
      (.setLocationRelativeTo dialog (:frame show))
      (.setSelectionMode attachment-list javax.swing.ListSelectionModel/SINGLE_SELECTION)
      (seesaw/listen attachment-list
                     :selection (fn [_]
                                  (reset! selected-attachment (.getSelectedValue attachment-list))
                                  (seesaw/config! [delete-button extract-as-button]
                                                  :enabled? (boolean @selected-attachment))))
      (seesaw/listen done-button :action-performed (fn [_] (seesaw/return-from-dialog dialog nil)))
      (seesaw/listen add-button :action-performed
                     (fn [_]
                       (when-let [^File file (chooser/choose-file dialog :type "Add Attachment")]
                         (when (or (not (Files/exists (.resolve attachments-root (.getName file))
                                                      (make-array java.nio.file.LinkOption 0)))
                                   (util/confirm dialog
                                                 (str "The attachment ”" (.getName file)
                                                      "“ already exists, and will be "
                                                      "overwritten if you proceed.")
                                                 :type :warning
                                                 :title "Replace Existing Attachment?"))
                           (when (or (not (str/ends-with? (.getName file) ".clj"))
                                     (load-clojure-file-before-attaching dialog file))
                             (when-not (Files/isDirectory attachments-root (make-array java.nio.file.LinkOption 0))
                               (Files/createDirectory attachments-root
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
                             (Files/copy (.toPath file) (.resolve attachments-root (.getName file))
                                         copy-options-replace-existing)
                             (seesaw/config! attachment-list :model (list-attachments attachments-root)))))))
      (seesaw/listen delete-button :action-performed
                     (fn [_]
                       (when (util/confirm dialog
                                           (str "Deleting this attachment from the show "
                                                "cannot be undone.")
                                           :type :warning
                                           :title (str "Delete Attachment ”" @selected-attachment "“"))
                         (Files/delete (.resolve attachments-root @selected-attachment))
                         (seesaw/config! attachment-list :model (list-attachments attachments-root)))))
      (seesaw/listen extract-as-button :action-performed
                     (fn [_]
                       (when-let [file (chooser/choose-file dialog :type "Extract As…")]
                         (when-let [^File file (util/confirm-overwrite-file file nil dialog)]
                           (Files/copy (.resolve attachments-root @selected-attachment) (.toPath file)
                                       copy-options-replace-existing)))))
      (seesaw/show! dialog))
    (catch Exception e
      (timbre/error e "Problem Managing Attachments")
      (seesaw/alert (:frame show)
                    (str "<html>Exception while managing attachments:<br><br>" (.getMessage e)
                         "<br><br>See the log file for more details.")
                   :title "Problem Managing Attachments" :type :error))))

(defn load-attachment
  "Loads the attachment with the specified name from the show. The
  attachment must be a Clojure source file."
  [show attachment]
  (let [^FileSystem filesystem (:filesystem show)
        ^Path attachments-root (build-filesystem-path filesystem "attachments")]
    (with-open [reader (Files/newBufferedReader (.resolve attachments-root attachment))
                reader (clojure.lang.LineNumberingPushbackReader. reader)]
      (timbre/info "Loading show attachment:" (str attachment))
      (load-reader reader))))

(defn load-attachments
  "Loads all attachments found in the show whose names end in .clj"
  [show]
  (let [^FileSystem filesystem (:filesystem show)
        ^Path attachments-root (build-filesystem-path filesystem "attachments")]
    (doseq [attachment (list-attachments attachments-root)]
      (when (str/ends-with? (str attachment) ".clj")
        (load-attachment show attachment)))))

(defn in-show-ns
  "Helper function for REPL users to switch to a show namespace by picking the show."
  []
  (let [shows @open-shows]
    (if (not-empty shows)
      (do
        (println "Pick a show:")
        (let [shows-by-index (loop [[[file show] & remaining] shows
                                    index                1
                                    choices              {}]
                               (println (format "%3d: " index) (.getCanonicalPath file))
                               (if (seq remaining)
                                 (recur remaining
                                        (inc index)
                                        (assoc choices index show))
                                 (assoc choices index show)))]
          (print "Choice: ")
          (flush)
          (let [choice (read-line)]
            (println)
            (if-let [show (get shows-by-index (parse-long choice))]
              (do (println "Switching to expressions namespace for show" (.getCanonicalPath (:file show)))
                  (in-ns ((requiring-resolve 'beat-link-trigger.expressions/expressions-namespace) show)))
              (println "No show selected.")))))
      (println "No shows are open."))))

(defn symbol-prefix-for-show
  "Returns a string that can be used as a prefix for symbols used to
  identify functions compiled for this show."
  [show]
  (str "show-" (-> (fs/base-name (:file show) true)
                   str/lower-case
                   (str/replace #"(\W|_)+" "-")) ; Turn all non-alphanumeric ranges into single hyphens.
       "-"))

(defn symbol-section-for-title
  "returns a string that can be used to identify a title as part of
  symbols used to identify functions compiled for a show."
  [title]
  (if (str/blank? title)
    "untitled-"
    (-> (str/lower-case title)
        (str/replace-first #"^(\W|_)+" "") ; Get rid of any leading non-alphanumeric characters.
        (str/replace-first #"(\W|_)+$" "") ; Get rid of any trailing non-alphanumeric characters.
        (str/replace #"(\W|_)+" "-"))))    ; TUrna ll non-alphanumeric ranges into single hyphens.
