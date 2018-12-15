(ns beat-link-trigger.util
  "Provides commonly useful utility functions."
  (:require [seesaw.core :as seesaw]
            [overtone.midi :as midi]
            [me.raynes.fs :as fs])
  (:import [org.deepsymmetry.beatlink DeviceFinder]
           [java.awt Color Font GraphicsEnvironment RenderingHints]
           [javax.sound.midi Sequencer Synthesizer]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDestination CoreMidiDeviceProvider CoreMidiSource]))

(def ^:private project-version
  (delay (clojure.edn/read-string (slurp (clojure.java.io/resource "beat_link_trigger/version.edn")))))

(defn get-version
  "Returns the version information set up by lein-v."
  []
  (:version @project-version))

(defn get-java-version
  "Returns the version of Java in which we are running."
  []
  (str (System/getProperty "java.version")
       (when-let [vm-name (System/getProperty "java.vm.name")]
         (str ", " vm-name))
       (when-let [vendor (System/getProperty "java.vm.vendor")]
         (str ", " vendor))))

(defn get-os-version
  "Returns the operating system and version in which we are running."
  []
  (str (System/getProperty "os.name") " " (System/getProperty "os.version")))

(defn get-build-date
  "Returns the date this jar was built, if we are running from a jar."
  []
  (let [a-class    (class get-version)
        class-name (str (.getSimpleName a-class) ".class")
        class-path (str (.getResource a-class class-name))]
    (when (clojure.string/starts-with? class-path "jar")
      (let [manifest-path (str (subs class-path 0 (inc (clojure.string/last-index-of class-path "!")))
                               "/META-INF/MANIFEST.MF")]
        (with-open [stream (.openStream (java.net.URL. manifest-path))]
          (let [manifest   (java.util.jar.Manifest. stream)
                attributes (.getMainAttributes manifest)]
            (.getValue attributes "Build-Timestamp")))))))

(def ^:private file-types
  "A map from keywords identifying the kinds of files we work with to
  the filename extensions we create them with and require them to
  have."
  {:configuration  "blt"
   :trigger-export "bltx"
   :metadata       "bltm"
   :playlist       "csv"
   :show           "bls"})

(def ^:private file-extensions
  "A map from filename extensions we use to the corresponding keyword
  identifying that type of file."
  (clojure.set/map-invert file-types))

(defn extension-for-file-type
  "Given a keyword identifying one of the types of files we work with,
  return the corresponding filename extension we require."
  [file-type]
  (file-types file-type))

(defn file-type-for-extension
  "Given a filename extension, return the keyword identifying it as one
  of the types of files we work with, or `nil` if we don't recognize
  it."
  [extension]
  (file-extensions extension))

(defn file-type
  "Given a file, return the keyword identifying it as one of the types
  of files we work with, or, `nil` if we don't recognize it."
  [file]
  (file-type-for-extension (fs/extension (clojure.java.io/file file))))

(defn trim-extension
  "Removes a file extension from the end of a string."
  [s]
  (let [dot (.lastIndexOf s ".")]
    (if (pos? dot) (subs s 0 dot) s)))

(defn confirm-overwrite-file
  "If the specified file already exists, asks the user to confirm that
  they want to overwrite it. If `required-extension` is supplied, that
  extension is added to the end of the filename, if it is not already
  present, before checking. Returns the file to write if the user
  confirmed overwrite, or if a conflicting file did not already exist,
  and `nil` if the user said to cancel the operation. If a non-`nil`
  window is passed in `parent`, the confirmation dialog will be
  centered over it."
  [file required-extension parent]
  (when file
    (let [required-extension (if (.startsWith required-extension ".")
                               required-extension
                               (str "." required-extension))
          file (if (or (nil? required-extension) (.. file (getAbsolutePath) (endsWith required-extension)))
                 file
                 (clojure.java.io/file (str (.getAbsolutePath file) required-extension)))]
      (or (when (not (.exists file)) file)
          (let [confirm (seesaw/dialog
                         :content (str "Replace existing file?\nThe file " (.getName file)
                                       " already exists, and will be overwritten if you proceed.")
                         :type :warning :option-type :yes-no)]
            (.pack confirm)
            (.setLocationRelativeTo confirm parent)
            (let [result (when (= :success (seesaw/show! confirm)) file)]
              (seesaw/dispose! confirm)
              result))))))

(defn visible-player-numbers
  "Return the list of players numbers currently visible on the
  network (ignoring our virtual player, and any mixers or rekordbox
  instances)."
  []
  (filter #(< % 16) (map #(.getNumber %) (.getCurrentDevices (DeviceFinder/getInstance)))))


(defn remove-blanks
  "Converts an empty string to a `nil` value so `or` will reject it."
  [s]
  (if (clojure.string/blank? s)
    nil
    s))

(defn paint-placeholder
  "A function which will paint placeholder text in a text field if the
  user has not added any text of their own, since Swing does not have
  this ability built in. Takes the text of the placeholder, the
  component into which it should be painted, and the graphics content
  in which painting is taking place."
  [text c g]
  (when (zero? (.. c (getText) (length)))
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setColor g java.awt.Color/gray)
    (.drawString g text (.. c (getInsets) left)
                 (+ (.. g (getFontMetrics) (getMaxAscent)) (.. c (getInsets) top)))))

(defn show-popup-from-button
  "Displays a popup menu when the gear button is clicked as an
  ordinary mouse event."
  [target popup event]
  (.show popup target (.x (.getPoint event)) (.y (.getPoint event))))

(defmacro case-enum
  "Like `case`, but explicitly dispatch on Java enum ordinals."
  {:style/indent 1}
  [e & clauses]
  (letfn [(enum-ordinal [e] `(let [^Enum e# ~e] (.ordinal e#)))]
    `(case ~(enum-ordinal e)
       ~@(concat
          (mapcat (fn [[test result]]
                    [(eval (enum-ordinal test)) result])
                  (partition 2 clauses))
          (when (odd? (count clauses))
            (list (last clauses)))))))

(defmacro doseq-indexed
  "Analogous to map-indexed for doseq: iterate for side-effects with an
  index."
  {:style/indent 2}
  [index-sym [item-sym coll] & body]
  `(doseq [[~index-sym ~item-sym] (map list (range) ~coll)]
     ~@body))

;; Used to represent the available players in the Triggers window
;; Watch menu, and the Load Track on a Player window. The `toString`
;; method tells Swing how to display it, and the number is what we
;; need for comparisons.
(defrecord PlayerChoice [number]
  Object
  (toString [_] (cond
                  (neg? number) "Any Player"
                  (zero? number) "Master Player"
                  :else (str "Player " number))))


;; Used to represent the available MIDI outputs in the output menu. The `toString` method
;; tells Swing how to display it, so we can suppress the CoreMidi4J prefix.
(defrecord MidiChoice [full-name]
  Object
  (toString [_] (clojure.string/replace full-name #"^CoreMIDI4J - " "")))

(defonce ^{:doc "Tracks window positions so we can try to restore them
  in the configuration the user had established."}
  window-positions (atom {}))

(defn usable-midi-device?
  "Returns true if a MIDI device should be visible. Filters out non-CoreMidi4J devices when that library
  is active."
  [device]
  (or (not (CoreMidiDeviceProvider/isLibraryLoaded))
      (let [raw-device (:device device)]
        (or (instance? Sequencer raw-device) (instance? Synthesizer raw-device)
            (instance? CoreMidiDestination raw-device) (instance? CoreMidiSource raw-device)))))

(defn get-midi-outputs
  "Returns all available MIDI output devices as menu choice model objects"
  []
  (map #(MidiChoice. (:name %)) (filter usable-midi-device? (sort-by :name (midi/midi-sinks)))))

(defonce ^{:private true
           :doc "Keeps track of whether we have loaded our custom fonts yet."}
  fonts-loaded
  (atom false))

(defn load-fonts
  "Load and register the fonts we will use to draw on the display, if
  they have not already been."
  []
  (or @fonts-loaded
      (let [ge (GraphicsEnvironment/getLocalGraphicsEnvironment)]
        (doseq [font-file ["/fonts/DSEG/DSEG7Classic-Regular.ttf"
                           "/fonts/Orbitron/Orbitron-Black.ttf"
                           "/fonts/Orbitron/Orbitron-Bold.ttf"
                           "/fonts/Teko/Teko-Regular.ttf"
                           "/fonts/Teko/Teko-SemiBold.ttf"
                           "/fonts/Bitter/Bitter-Bold.ttf"
                           "/fonts/Bitter/Bitter-Italic.ttf"
                           "/fonts/Bitter/Bitter-Regular.ttf"]]
            (.registerFont ge (Font/createFont Font/TRUETYPE_FONT
                                               (.getResourceAsStream MidiChoice font-file))))
        (reset! fonts-loaded true))))

(defn save-window-position
  "Saves the position of a window under the specified keyword. If
  `no-size?` is supplied with a `true` value, only the location is
  saved, and the window size is not recorded."
  ([window k]
   (save-window-position window k false))
  ([window k no-size?]
   (swap! window-positions assoc k (if no-size?
                                     [(.getX window) (.getY window)]
                                     [(.getX window) (.getY window) (.getWidth window) (.getHeight window)]))))

(defonce ^{:doc "Holds a map of all the MIDI output devices we have
  opened, keyed by their names, so we can reuse them."}
  opened-outputs
  (atom {}))

(defn restore-window-position
  "Tries to put a window back where in the position where it was saved
  (under the specified keyword). If no saved position is found, or if
  the saved position is within 100 pixels of going off the bottom
  right of the screen, the window is instead positioned centered on
  the supplied parent window; if `parent` is nil, `window` is centered
  on the screen."
  [window k parent]
  (let [[x y width height] (get @window-positions k)
        dm (.getDisplayMode (.getDefaultScreenDevice (java.awt.GraphicsEnvironment/getLocalGraphicsEnvironment)))]
    (if (or (nil? x)
            (> x (- (.getWidth dm) 100))
            (> y (- (.getHeight dm) 100)))
      (.setLocationRelativeTo window parent)
      (if (nil? width)
        (.setLocation window x y)
        (.setBounds window x y width height)))))

(defn get-display-font
  "Find one of the fonts configured for use by keyword, which must be
  one of `:segment`. The `style` argument is a `java.awt.Font` style
  constant, and `size` is point size.

  Bitter is available in plain, bold, or italic. Orbitron is only
  available in bold, but asking for bold gives you Orbitron Black.
  Segment is only available in plain. Teko is available in plain and
  bold (but we actually deliver the semibold version, since that looks
  nicer in the UI)."
  [k style size]
  (case k
    :bitter (Font. "Bitter" style size)
    :orbitron (Font. (if (= style Font/BOLD) "Orbitron Black" "Orbitron") Font/BOLD size)
    :segment (Font. "DSEG7 Classic" Font/PLAIN size)
    :teko (Font. (if (= style Font/BOLD) "Teko SemiBold" "Teko") Font/PLAIN size)))
