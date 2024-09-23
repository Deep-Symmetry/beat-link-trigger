(ns beat-link-trigger.players
  "Provides the user interface for seeing the status of active
  players, as well as telling players to load tracks."
  (:require [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.track-loader :as track-loader]
            [beat-link-trigger.simulator :as simulator]
            [beat-link-trigger.util :as util]
            [clojure.core.async :as async :refer [<! >!!]]
            [clojure.java.io :as io]
            [clojure.string]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.awt Color Font Graphics2D RenderingHints]
           [java.awt.event MouseEvent WindowEvent]
           [javax.imageio ImageIO]
           [javax.swing JFrame JLabel]
           [org.deepsymmetry.beatlink CdjStatus CdjStatus$TrackSourceSlot CdjStatus$TrackType
            DeviceAnnouncement DeviceAnnouncementListener DeviceFinder DeviceUpdate
            LifecycleListener MediaDetailsListener VirtualCdj]
           [org.deepsymmetry.beatlink.data AlbumArt AlbumArtListener AnalysisTagFinder AnalysisTagListener
            ArtFinder MetadataFinder MountListener OpusProvider SearchableItem SlotReference TimeFinder TrackMetadata
            TrackMetadataListener TrackPositionUpdate WaveformDetail WaveformDetailComponent WaveformFinder
            WaveformPreviewComponent]
           [jiconfont.icons.font_awesome FontAwesome]
           [jiconfont.swing IconFontSwing]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to view player state
  and instruct them to load tracks."}
  player-window (atom nil))

(defonce ^{:private true
           :doc "Controls whether the window should show waveform
  details. Consulted at creation time."}
  should-show-details (atom true))

(defn show-details
  "Controls whether the window should show waveform details. Used when
  creating the window, so if you want to have a new value take effect,
  you will need to close and reopen the window after changing it."
  [show]
  (reset! should-show-details (boolean show)))

(defonce ^{:doc "Stores the top left corner of where the user moved
  the window the last time it was used, so it can be positioned in the
  same place the next time it is opened."}
  window-position (atom nil))

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

(def ^TimeFinder time-finder
  "A convenient reference to the [Beat Link
  `TimeFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TimeFinder.html)
  singleton."
  (TimeFinder/getInstance))

(def ^WaveformFinder waveform-finder
  "A convenient reference to the [Beat Link
  `WaveformFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/WaveformFinder.html)
  singleton."
  (WaveformFinder/getInstance))

(def ^AnalysisTagFinder analysis-finder
  "A convenient reference to the [Beat Link
  `AnalysisTagFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/AnalysisTagFinder.html)
  singleton."
  (AnalysisTagFinder/getInstance))

(def ^ArtFinder art-finder
  "A convenient reference to the [Beat Link
  `ArtFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/ArtFinder.html)
  singleton."
  (ArtFinder/getInstance))

(def ^OpusProvider opus-provider
  "A convenient reference to the [Beat Link
  `OpusProvider`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/OpusProvider.html)
  singleton."
  (OpusProvider/getInstance))

(defn- sending-status?
  "Checks whether we are currently sending status packets, which is
  required to reliably obtain metadata for non-rekordbox tracks."
  []
  ((resolve 'beat-link-trigger.triggers/real-player?)))

(defn- opus-quad?
  "Checks whether we are operating in Opus Quad compatiblity
  mode rather than Pro DJ Link mode."
  []
  (.inOpusQuadCompatibilityMode virtual-cdj))

(def magnify-cursor
  "A custom cursor that indicates something can be magnified."
  (delay (.createCustomCursor (java.awt.Toolkit/getDefaultToolkit)
                              (.getImage ^javax.swing.ImageIcon (seesaw/icon "images/Magnify-cursor.png"))
                              (java.awt.Point. 6 6)
                              "Magnify")))

(defonce ^{:private true
           :doc "Keeps track of whether we have already warned about
  media we can't get without using a standard player number."}
  report-given (atom false))

(defn- report-limited-metadata
  "Displays a warning explaining that you must use a standard player
  number to get metadata for the kind of track loaded on a player. We
  only do this once per session per type of media to avoid annoying
  people."
  [parent player]
  (when (compare-and-set! report-given false true)
    (seesaw/invoke-later
     (seesaw/alert parent
                   (str "<html>We can't get Title and Aritst information for the non-rekordbox<br>"
                        "track in Player " player " unless you enable <strong>Use Real Player Number?</strong><br>"
                        "in the <strong>Network</strong> menu.</html>")
                   :title "Beat Link Trigger isn't using a real Player Number" :type :warning))))

(defn time-played
  "If possible, returns the number of milliseconds of track the
  specified player has played."
  [^Long n]
  (if-let [simulator (simulator/for-player n)]
    (:time simulator)
    (and (.isRunning time-finder) (.getTimeFor time-finder n))))

(defn precise?
  "Checks whether we are receving precise position packets for the
  specified player."
  [^Long n]
  (when-not (simulator/for-player n)
    (when (.isRunning time-finder)
      (when-let [position (.getLatestPositionFor time-finder n)]
        (.-precise position)))))

(defn- playing?
  "Returns `true` if the specified player can be determined to be
  playing right now."
  [n]
  (and (.isRunning virtual-cdj)
       (when-let [^CdjStatus status (.getLatestStatusFor virtual-cdj (int n))]
         (.isPlaying status))))

(defn- paint-player-number
  "Draws the player number being monitored by a row, updating the
  color to reflect its play state. Arguments are the player number,
  the component being drawn, and the graphics context in which drawing
  is taking place."
  [n c ^Graphics2D g]
  (let [w       (double (seesaw/width c))
        center  (/ w 2.0)
        h       (double (seesaw/height c))
        outline (java.awt.geom.RoundRectangle2D$Double. 1.0 1.0 (- w 2.0) (- h 2.0) 10.0 10.0)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if (playing? n)
                   Color/GREEN Color/DARK_GRAY))
    (.draw g outline)
    (.setFont g (util/get-display-font :orbitron Font/PLAIN 12))
    (let [frc    (.getFontRenderContext g)
          bounds (.getStringBounds (.getFont g) "Player" frc)]
      (.drawString g "Player" (float (- center (/ (.getWidth bounds) 2.0))) (float (+ (.getHeight bounds) 2.0))))
    (.setFont g (util/get-display-font :orbitron Font/BOLD 42))
    (let [frc    (.getFontRenderContext g)
          num    (str n)
          bounds (.getStringBounds (.getFont g) num frc)]
      (.drawString g num (float (- center (/ (.getWidth bounds) 2.0))) (float (- h 5.0))))))

(defn- on-air?
  "Returns `true` if the specified player can be determined to be
  on the air right now."
  [n]
  (and (.isRunning virtual-cdj)
       (when-let [^CdjStatus status (.getLatestStatusFor virtual-cdj (int n))]
         (.isOnAir status))))

(def near-black
  "A gray that is close to black."
  (Color. 20 20 20))

(defn- paint-on-air
  "Draws an indication of whether the player being monitored by a row
  has reported itself as being on the air. Arguments are the player
  number, the component being drawn, and the graphics context in which
  drawing is taking place."
  [n c ^Graphics2D g]
  (let [w       (double (seesaw/width c))
        center  (/ w 2.0)
        h       (double (seesaw/height c))
        outline (java.awt.geom.RoundRectangle2D$Double. 1.0 1.0 (- w 2.0) (- h 2.0) 10.0 10.0)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if (on-air? n) Color/RED near-black))
    (.draw g outline)
    (.setFont g (util/get-display-font :teko Font/BOLD 18))
    (let [frc    (.getFontRenderContext g)
          bounds (.getStringBounds (.getFont g) "On Air" frc)]
      (.drawString g "On Air" (float (- center (/ (.getWidth bounds) 2.0))) (float (- h 4.0))))))

(defn- current-beat
  "Determine, if possible, the current beat within a musical bar which
  the specified player is playing or paused on."
  [n]
  (let [result (and (.isRunning time-finder)
                    (when-let [^TrackPositionUpdate update (.getLatestPositionFor time-finder (int n))]
                      (.getBeatWithinBar update)))]
    (when (and result (<= 1 result 4))
      result)))

(defn- paint-beat
  "Draws a graphical representation of the current beat
  position (within a musical bar) of the specified player, if known.
  Arguments are the player number, the component being drawn, and the
  graphics context in which drawing is taking place."
  [n _ ^Graphics2D g]
  (try
    (let [image-data (io/resource (str "images/BeatMini-" (or (current-beat n) "blank") ".png"))
          image (javax.imageio.ImageIO/read image-data)]
      (.drawImage g image 0 0 nil))
    (catch Exception e
      (timbre/error e "Problem loading beat indicator image"))))

(defn- time-left
  "Figure out the number of milliseconds left to play for a given
  player, given the player number and time played so far."
  [^Long n played]
  (when-let [^WaveformDetail detail (or (get-in (simulator/for-player n) [:track :detail])
                                        (.getLatestDetailFor waveform-finder n))]
    (max 0 (- (.getTotalTime detail) played))))

(defn- format-time
  "Formats a number for display as a two-digit time value, or -- if it
  cannot be."
  ^String [t]
  (if t
    (let [t (int t)]
      (if (< t 100)
        (format "%02d" t)
        "--"))
    "--"))

(defn- pre-nexus
  "Checks whether the specified player number seems to be an older,
  pre-nexus player, for which we cannot obtain time information."
  [^Long n]
  (when-not (simulator/simulating?)
    (when-let [^CdjStatus u (try
                              (.getLatestStatusFor virtual-cdj n)
                              (catch Exception _))]  ; Absorb when virtual-cdj shuts down because we went offline.
      (.isPreNexusCdj u))))

(defn- paint-time
  "Draws time information for a player. Arguments are player number, a
  boolean flag indicating we are drawing remaining time, the component
  being drawn, and the graphics context in which drawing is taking
  place. Used both by this window and by playback simulators."
  [n remain _ ^Graphics2D g]
  (let [played     (time-played n)
        ms         (when (and played (>= played 0)) (if remain (time-left n played) played))
        min        (format-time (when ms (/ ms 60000)))
        sec        (format-time (when ms (/ (mod ms 60000) 1000)))
        half-frame (when ms (mod (org.deepsymmetry.beatlink.Util/timeToHalfFrame ms) 150))
        frame      (format-time (when half-frame (/ half-frame 2)))
        frac-frame (if half-frame (if (even? half-frame) "0" "5") "-")]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setPaint g (if remain (Color. 255 200 200) (Color/WHITE)))
    (.setFont g (util/get-display-font :teko Font/PLAIN 16))
    (.drawString g (if remain "Remain" (if (precise? n) "Precise Time" "Time")) (int 4) (int 16))
    (if (and (not ms) (pre-nexus n))
        (do  ; Report that we can't display time information, as this is a pre-nexus device.
          (.setFont g (util/get-display-font :teko Font/PLAIN 19))
          (.drawString g "[Pre-nexus, no data.]" (int 2) (int 37)))
      (do ; Normal time display.
        (.setFont g (util/get-display-font :segment Font/PLAIN 20))
        (.drawString g min (int 2) (int 40))
        (.fill g (java.awt.geom.Rectangle2D$Double. 42.0 34.0 2.0 3.0))
        (.fill g (java.awt.geom.Rectangle2D$Double. 42.0 24.0 2.0 3.0))
        (.drawString g sec (int 45) (int 40))
        (.fill g (java.awt.geom.Rectangle2D$Double. 84.0 34.0 2.0 3.0))
        (.fill g (java.awt.geom.Rectangle2D$Double. 84.0 24.0 2.0 3.0))
        (.drawString g frame (int 87) (int 40))
        (.setFont g (util/get-display-font :teko Font/BOLD 10))
        (.drawString g "M" (int 34) (int 40))
        (.drawString g "S" (int 77) (int 40))
        (.drawString g "F" (int 135) (int 40))
        (.fill g (java.awt.geom.Rectangle2D$Double. 120.0 37.0 2.0 3.0))
        (.setFont g (util/get-display-font :segment Font/PLAIN 16))
        (.drawString g frac-frame (int 122) (int 40))))))

(defn- tempo-values
  "Look up the current playback pitch percentage and effective tempo for
  the specified player, returning them when available as a vector
  followed by boolean indications of whether the device is the current
  tempo master and whether it is synced, and if that sync has degraded
  to BPM-only."
  [^Long n]
  (when-let [^DeviceUpdate u (when (.isRunning time-finder) (.getLatestUpdateFor time-finder n))]
    (let [^CdjStatus cdj-status (.getLatestStatusFor virtual-cdj n)]
      [(org.deepsymmetry.beatlink.Util/pitchToPercentage (.getPitch u))
       (when (not= 65535 (.getBpm u)) (.getEffectiveTempo u))  ; Detect when tempo is not valid
       (.isTempoMaster u)
       (true? (when cdj-status (.isSynced cdj-status)))
       (true? (when cdj-status (.isBpmOnlySynced cdj-status)))])))

(defn- format-pitch
  "Formats a number for display as a pitch value, with one or two
  decimal places (and always using period as the decimal point, since
  that is the only separator available in the DSEG7 font)."
  [pitch]
  (let [format-string (if (< pitch 20.0) "%5.2f" "%5.1f")]
    (String/format java.util.Locale/ROOT format-string (to-array [pitch]))))

(defn format-tempo
  "Formats a tempo for display, showing an unloaded track as \"--.-\",
  and always using period as the decimal point, since that is the only
  separator available in the DSEG7 font."
  [tempo]
  (if (nil? tempo)
    " --.-"
    (String/format java.util.Locale/ROOT "%5.1f" (to-array [tempo]))))

(defn- paint-tempo
  "Draws tempo information for a player. Arguments are player number,
  the component being drawn, and the graphics context in which drawing
  is taking place."
  [n _ ^Graphics2D g]
  (when-let [[pitch tempo master synced bpm-only] (tempo-values n)]
    (let [abs-pitch       (Math/abs (double pitch))]
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setPaint g (Color/WHITE))
      (.setFont g (util/get-display-font :teko Font/PLAIN 16))
      (.drawString g "Tempo" (int 4) (int 16))
      (.setFont g (util/get-display-font :teko Font/BOLD 20))
      (.drawString g (if (< abs-pitch 0.025) " " (if (neg? pitch) "-" "+")) (int 2) (int 38))
      (.setFont g (util/get-display-font :segment Font/PLAIN 16))
      (.drawString g (clojure.string/replace (format-pitch abs-pitch) " " "!") (int 4) (int 40))
      (.setFont g (util/get-display-font :teko Font/PLAIN 14))
      (.drawString g "%" (int 56) (int 40)))
    (when synced
      (when bpm-only (.setPaint g Color/YELLOW))
      (let [frame (java.awt.geom.RoundRectangle2D$Double. 40.0 4.0 24.0 15.0 4.0 4.0)]
        (.fill g frame)
        (.setPaint g Color/BLACK)
        (.setFont g (util/get-display-font :teko Font/PLAIN (if bpm-only 15 14)))
        (.drawString g (if bpm-only "BPM" "SYNC") (int 42) (int 16))))
    (.setPaint g (if master Color/ORANGE Color/WHITE))
    (let [frame        (java.awt.geom.RoundRectangle2D$Double. 68.0 1.0 50.0 38.0 8.0 8.0)
          clip         (.getClip g)
          tempo-string (clojure.string/replace (format-tempo tempo) " " "!")]
      (.draw g frame)
      (.setFont g (util/get-display-font :teko Font/PLAIN 14))
      (if master
        (do (.setClip g frame)
            (.fill g (java.awt.geom.Rectangle2D$Double. 68.0 27.0 50.0 13.0))
            (.setColor g (Color/BLACK))
            (.drawString g "MASTER" (int 77) (int 38))
            (.setClip g clip))
        (.drawString g "BPM" (int 97) (int 36)))
      (.setFont g (util/get-display-font :segment Font/PLAIN 16))
      (.setColor g (if master Color/ORANGE Color/WHITE))
      (.drawString g (subs tempo-string 0 4) (int 68) (int 22))
      (.setFont g (util/get-display-font :segment Font/PLAIN 12))
      (.drawString g (subs tempo-string 4) (int 107) (int 22)))))

(defn generic-media-image
  "Return an image that can be used as generic album artwork for the
  type of media being played in the specified player number."
  [^Long n]
  (let [resource (util/generic-media-resource n)]
    (ImageIO/read (io/resource resource))))

(defn- paint-art
  "Draws the album art for a player. Arguments are player number, the
  component being drawn, and the graphics context in which drawing is
  taking place."
  [n _ ^Graphics2D g]
  (if-let [^AlbumArt art (try (when (.isRunning art-finder) (.getLatestArtFor art-finder (int n)))
                              (catch Exception e
                                (timbre/error e "Problem requesting album art to draw player row, leaving blank.")))]
    (if-let [image (.getImage art)]
      (.drawImage g image 0 0 80 80 nil)
      (when-let [image (generic-media-image n)]  ; No image found, try drawing a generic substitute.
      (.drawImage g image 0 0 80 80 nil)))
    (when-let [image (generic-media-image n)]  ; No actual art found, try drawing a generic substitute.
      (.drawImage g image 0 0 80 80 nil))))

#_(defn- no-players-found
  "Returns true if there are no visible players for us to display."
  []
  (empty? (filter #(<= 1 (.getDeviceNumber %) 4) (.getCurrentDevices device-finder))))

(defn- extract-label
  "Given a `SearchableItem` from track metadata, extracts the string
  label. If passed `nil` simply returns `nil`."
  [^SearchableItem item]
  (when item
    (.label item)))

(defonce ^{:private true
           :doc "Holds any waveform detail windows that the user has open,
  indexed by player number."}

  waveform-windows (atom {}))

(defn open-waveform-window
  "Creates a standalone, resizable window displaying a player waveform,
  given a player number."
  [n parent]
  (if-let [^JFrame existing (get @waveform-windows n)]
    (.toFront existing)  ; Already open, just bring to front.
    (let [key          (keyword (str "waveform-detail-" n)) ; Not open yet, so create.
          wave         (WaveformDetailComponent. n)
          zoom-slider  (seesaw/slider :id :zoom :min 1 :max 32 :value 2
                                      :listen [:state-changed #(.setScale wave (seesaw/value %))])
          zoom-panel   (seesaw/border-panel :background "#000"
                                            :center zoom-slider :east (seesaw/label :text " Zoom "))
          panel        (seesaw/border-panel :background "#000" :north zoom-panel :center wave)
          ^JFrame root (seesaw/frame :title (str "Player " n " Waveform Detail")
                                     :on-close :dispose
                                     :width 600 :height 200
                                     :content panel)]
      (swap! waveform-windows assoc n root)
      (.setScale wave 2)
      (util/restore-window-position root key parent)
      (seesaw/listen root #{:component-moved :component-resized} (fn [_] (util/save-window-position root key)))
      (seesaw/listen root :window-closed (fn [_] (swap! waveform-windows dissoc n)))
      (seesaw/show! root)
      (.toFront root))))

(defn- update-metadata-labels
  "Updates the track title and artist name when the metadata has
  changed. Let the user know if they are not going to be able to get
  metadata without using a standard player number if this is the first
  time we have seen the kind of track for which that is an issue."
  [^TrackMetadata metadata player title-label artist-label]
  (seesaw/invoke-soon
   (if metadata
     (do (seesaw/config! title-label :text (or (util/remove-blanks (.getTitle metadata)) "[no title]"))
         (seesaw/config! artist-label :text (or (util/remove-blanks (extract-label (.getArtist metadata)))
                                                "[no artist]")))
     (let [^CdjStatus status (when (.isRunning virtual-cdj) (.getLatestStatusFor virtual-cdj (int player)))
           title             (if (= CdjStatus$TrackType/NO_TRACK (when status (.getTrackType status)))
                               "[no track loaded]"
                               "[no track metadata available]")]
       (seesaw/config! title-label :text title)
       (seesaw/config! artist-label :text "n/a")
       (when (and status
                  (not (#{CdjStatus$TrackType/NO_TRACK CdjStatus$TrackType/REKORDBOX} (.getTrackType status)))
                  (not (sending-status?)))
         (report-limited-metadata @player-window player))))))

(defn update-phrase-labels
  "Updates the track mood and bank when the phrase analysis results have
  changed."
  [tag mood-label bank-label]
  (seesaw/invoke-soon
   (if tag
     (do
       (seesaw/config! mood-label :visible? true :text (util/track-mood-name tag))
       (seesaw/config! bank-label :visible? true :text (util/track-bank-name tag)))
     (seesaw/config! [mood-label bank-label] :visible? false))))

(def num-opus-usb-slots
  "The number of USB slots present on the Opus Quad."
  3)

(defn opus-slot-state
  "Returns a tuple of the label text and tooltip, if any, to describe
  the state of an Opus USB slot. Reclects whether there is even such a
  slot, and if so, whether it is empty, or if we have a metadata
  archive mounted for it."
  [slot-number]
  (if (> slot-number num-opus-usb-slots)
                         ["" nil]
                         (if-let [archive (.findArchive opus-provider slot-number)]
                           (let [path (str (.getFileSystem archive))
                                 file (io/file path)]
                             [(.getName file) path])
                           ["No metadata archive." nil])))

(defn update-opus-slot-label
  "Updates a JLabel to describes the metadata archive mounted for an Opus Quad slot, if any.
  If we have a pathname for a mounted archive, sets the label text to
  the filename part and the tooltip to the full path."
  [slot-number label]
  (let [[text tooltip] (opus-slot-state slot-number)]
    (seesaw/text! label text)
    (seesaw/config! label :tip tooltip)))

(defn- mount-media
  "Has the user choose a metadata archive, then attaches it for the
  specified Opus Quad slot."
  [slot-number label]
  (when-let [file (chooser/choose-file @player-window :type "Attach Metadata Archive"
                                       :all-files? false
                                       :filters [["Beat Link Metadata Archive"
                                                  [(util/extension-for-file-type :metadata-archive)]]])]
    (.attachMetadataArchive opus-provider file slot-number)
    (update-opus-slot-label slot-number label)))

(defn- slot-popup
  "Returns the actions that should be in a popup menu for a particular
  player media slot. Arguments are the player number, slot
  keyword (`:usb` or `:sd`), and the event triggering the popup."
  [n slot label e]
  (when (seesaw/config e :enabled?)
    (let [slot           (case slot
                             :usb CdjStatus$TrackSourceSlot/USB_SLOT
                             :sd  CdjStatus$TrackSourceSlot/SD_SLOT)
          slot-reference (SlotReference/getSlotReference (int n) slot)]
      (if (opus-quad?)
        (filter identity
                [(seesaw/action :handler (fn [_] (mount-media n label))
                                :name "Attach Metadata Archive")
                 (when (.findArchive opus-provider n)
                   (seesaw/action :handler (fn [_]
                                             (.attachMetadataArchive opus-provider nil n)
                                             (update-opus-slot-label n label))
                                  :name "Remove Metadata Archive"))])
        [(seesaw/action :handler (fn [_] (track-loader/show-dialog slot-reference))
                        :name "Load Track from Here on a Player")]))))

(defn- handle-preview-move
  "Mouse movement listener for a wave preview component; shows a tool
  tip with a cue/loop description if the mouse is hovering over the
  marker for one. Will include the DJ-assigned comment if we have
  found one. Also identifies the phrase labels when hovering over
  a phrase analyis color bar (again, if we have one)."
  [^WaveformPreviewComponent preview ^MouseEvent e]
  (.setToolTipText preview (.toolTipText preview (.getPoint e))))

(defn- media-description
  "Builds a description with as much information as we have available
  about the media mounted in a slot."
  [slot-reference]
  (let [details     (.getMediaDetailsFor metadata-finder slot-reference)
        detail-name (when details (.name details))
        media-name  (or (and (not (clojure.string/blank? detail-name)) detail-name)
                        "Mounted")]
    (str media-name (util/media-contents details))))

(defn- create-player-cell
  "Create a cell for a player, given the shutdown channel and the player
  number this row is supposed to display."
  [shutdown-chan n]
  (let [art            (seesaw/canvas :size [80 :by 80] :opaque? false :paint (partial paint-art n))
        preview        (WaveformPreviewComponent. (int n))
        on-air         (seesaw/canvas :size [55 :by 20] :opaque? false :paint (partial paint-on-air n))
        last-on-air    (atom nil)
        beat           (seesaw/canvas :size [55 :by 5] :opaque? false :paint (partial paint-beat n))
        last-beat      (atom nil)
        player         (seesaw/canvas :size [56 :by 56] :opaque? false :paint (partial paint-player-number n))
        last-playing   (atom nil)
        time           (seesaw/canvas :size [140 :by 40] :opaque? false :paint (partial paint-time n false))
        last-time      (atom nil)
        remain         (seesaw/canvas :size [140 :by 40] :opaque? false :paint (partial paint-time n true))
        last-remain    (atom nil)
        tempo          (seesaw/canvas :size [120 :by 40] :opaque? false :paint (partial paint-tempo n))
        last-tempo     (atom nil)
        title-label    (seesaw/label :text "[track metadata not available]"
                                     :font (Font. "serif" Font/ITALIC 14) :foreground :yellow)
        artist-label   (seesaw/label :text "" :font (Font. "serif" Font/BOLD 12) :foreground :green)
        usb-name       (seesaw/label :id :usb-name :text (if (opus-quad?)
                                                           (first (opus-slot-state n))
                                                           "Empty"))
        usb-gear       (seesaw/button :id :usb-gear :icon (seesaw/icon "images/Gear-outline.png") :enabled? (opus-quad?)
                                      :popup (partial slot-popup n :usb usb-name)
                                      :visible? (or (not opus-quad?) (<= n num-opus-usb-slots)))
        usb-label      (seesaw/label :id :usb-label :text (if (opus-quad?)
                                                            (if (> n num-opus-usb-slots) "" (str "USB " n ":"))
                                                            "USB:"))
        sd-name        (seesaw/label :id :sd-name :text (if (opus-quad?) "" "Empty"))
        sd-gear        (seesaw/button :id :sd-gear :icon (seesaw/icon "images/Gear-outline.png") :enabled? false
                                      :popup (partial slot-popup n :sd sd-name)
                                      :visible? (not (opus-quad?)))
        sd-label       (seesaw/label :id :sd-label :text (if (opus-quad?) "" "SD:"))
        detail         (when @should-show-details (WaveformDetailComponent. (int n)))
        zoom-slider    (when @should-show-details
                         (seesaw/slider :id :zoom :min 1 :max 32 :value 4
                                        :listen [:state-changed (fn [e]
                                                                  (.setScale detail (seesaw/value e)))]))
        zoom-label     (when @should-show-details (seesaw/label :id :zoom-label :text "Zoom"))
        mood-icon      (IconFontSwing/buildIcon FontAwesome/BOLT 13.0 Color/white)
        mood-label     (seesaw/label :id :mood-label :text "High" :icon mood-icon :halign :right :visible? false)
        bank-icon      (IconFontSwing/buildIcon FontAwesome/SLIDERS 13.0 Color/white)
        bank-label     (seesaw/label :id :bank-label :text "Natural 2" :icon bank-icon :halign :right :visible? false)
        row            (mig/mig-panel
                        :id (keyword (str "player-" n))
                        :background (Color/BLACK)
                        :items (concat [[title-label "width 280!, push, span 3, split 2"]
                                        [mood-label "right"]
                                        [art "right, spany 4, wrap, hidemode 2"]
                                        [artist-label "width 280!, span 3, split 2"]
                                        [bank-label "right, wrap unrelated"]
                                        [usb-gear "split 2, right"] [usb-label "right"]
                                        [usb-name "width 280!, span 2, wrap"]
                                        [sd-gear "split 2, right"] [sd-label "right"]
                                        [sd-name "width 280!, span 2, wrap"]]
                                       (when @should-show-details
                                         [[zoom-slider "span 4, grow, split 2"] [zoom-label "wrap"]
                                          [detail "span, grow, wrap, hidemode 3"]])
                                       [[on-air "flowy, split 2, bottom"]
                                        [beat "bottom"] [time ""] [remain ""] [tempo "wrap"]
                                        [player "left, bottom"]
                                        [preview "width 408!, height 56!, right, bottom, span"]]))
        md-listener    (reify TrackMetadataListener
                         (metadataChanged [_this md-update]
                           (when (= n (.player md-update))
                             (update-metadata-labels (.metadata md-update) n title-label artist-label)
                             (seesaw/repaint! art))))  ; In case we still have no art but need a new generic image.
        ss-listener    (reify AnalysisTagListener
                         (analysisChanged [_this tag-update]
                           (when (= n (.player tag-update))
                             (let [wrapped (when-let [wrapper (.taggedSection tag-update)]
                                             (.body wrapper))]
                               (update-phrase-labels wrapped mood-label bank-label)))))
        art-listener   (reify AlbumArtListener
                         (albumArtChanged [_this art-update]
                           (when (= n (.player art-update))
                             (seesaw/repaint! art))))
        slot-elems     (fn [^SlotReference slot-reference]
                         (when (= n (.player slot-reference))
                           (util/case-enum (.slot slot-reference)
                             CdjStatus$TrackSourceSlot/USB_SLOT [usb-gear usb-name]
                             CdjStatus$TrackSourceSlot/SD_SLOT [sd-gear sd-name]
                             nil)))
        mount-listener (reify
                         MountListener
                         (mediaMounted [_this slot-reference]
                           (let [[button label] (slot-elems slot-reference)]
                             (when button
                               (seesaw/invoke-later
                                 (seesaw/config! button :icon (seesaw/icon "images/Gear-outline.png") :enabled? true)
                                 (seesaw/config! label :text (media-description slot-reference))))))
                         (mediaUnmounted [_this slot-reference]
                           (let [[button label] (slot-elems slot-reference)]
                             (when button
                               (seesaw/invoke-soon
                                 (seesaw/config! button :icon (seesaw/icon "images/Gear-outline.png") :enabled? false)
                                 (seesaw/config! label :text "Empty")))))

                         MediaDetailsListener
                         (detailsAvailable [_this details]
                           (let [slot-reference (.-slotReference details)
                                 [_ label]      (slot-elems slot-reference)]
                             (when label
                               (seesaw/invoke-later
                                 (seesaw/config! label :text (media-description slot-reference)))))))]

    ;; Display the magnify cursor over the waveform detail component,
    ;; and open a standalone window on the waveform when it is clicked.
    (.setCursor detail @magnify-cursor)
    (seesaw/listen detail :mouse-clicked (fn [_] (open-waveform-window n detail)))

    ;; Show the slot track loading menus on ordinary mouse presses on the buttons too.
    (seesaw/listen usb-gear
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (slot-popup n :usb usb-name e))]
                                      (util/show-popup-from-button usb-gear popup e))))
    (seesaw/listen sd-gear
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (slot-popup n :sd sd-name e))]
                                      (util/show-popup-from-button sd-gear popup e))))

    ;; Add the tooltip if needed for the metadata archive mounted in our USB slot.
    (when (opus-quad?) (update-opus-slot-label n usb-name))

    ;; Show tooltips for cue/loop markers in the track preview.
    (seesaw/listen preview :mouse-moved (fn [e] (handle-preview-move preview e)))

    ;; Set up all our listeners to automatically update the interface when the environment changes.
    (.addTrackMetadataListener metadata-finder md-listener)  ; React to metadata changes.
    (.addAnalysisTagListener analysis-finder ss-listener ".EXT" "PSSI")  ; React to song structure changes.
    (.addAlbumArtListener art-finder art-listener)  ; React to artwork changes.
    (when-not (opus-quad?) (.addMountListener metadata-finder mount-listener))  ; React to media mounts and ejection.

    ;; Set the initial state of the interface.
    (when detail (.setScale detail (seesaw/value zoom-slider)))
    (update-metadata-labels (.getLatestMetadataFor metadata-finder (int n)) n title-label artist-label)
    (when-let [tag (.getLatestTrackAnalysisFor analysis-finder (int n) ".EXT" "PSSI")]
      (update-phrase-labels (.body tag) mood-label bank-label))
    (doseq [slot-reference (.getMountedMediaSlots metadata-finder)]
      (.mediaMounted mount-listener slot-reference))

    (async/go  ; Arrange to clean up when the window closes.
      (<! shutdown-chan)  ; Parks until the window is closed.
      (.removeTrackMetadataListener metadata-finder md-listener)
      (.removeAnalysisTagListener analysis-finder ss-listener ".EXT" "PSSI")
      (.removeAlbumArtListener art-finder art-listener)
      (.removeMountListener metadata-finder mount-listener)
      (.setMonitoredPlayer preview (int 0))
      (when detail (.setMonitoredPlayer detail (int 0))))
    (async/go  ; Animation loop
      (while (nil? (async/poll! shutdown-chan))
        (try
          (async/alt!
            shutdown-chan nil
            (async/timeout 20) (do
                                 (when (not= @last-playing (playing? n))
                                   (reset! last-playing (playing? n))
                                   (seesaw/repaint! player))
                                 (when (not= @last-time (time-played n))
                                   (reset! last-time (time-played n))
                                   (seesaw/repaint! [time]))
                                 (let [now-remaining (when-let [played (time-played n)] (time-left n played))]
                                   (when (not= @last-remain now-remaining)
                                     (reset! last-remain now-remaining)
                                     (seesaw/repaint! [remain])))
                                 (when (not= @last-beat (current-beat n))
                                   (reset! last-beat (current-beat n))
                                   (seesaw/repaint! beat))
                                 (when (not= @last-on-air (on-air? n))
                                   (reset! last-on-air (on-air? n))
                                   (seesaw/repaint! on-air))
                                 (when (not= @last-tempo (tempo-values n))
                                   (reset! last-tempo (tempo-values n))
                                   (seesaw/repaint! tempo))))
          (catch Exception e
            (timbre/error e "Problem updating player status row")))))
    row))

(defn- create-player-cells
  "Creates the cells for each visible player in the Player Status
  window. A value will be delivered to `shutdown-chan` when the window
  is closed, telling the row to unregister any event listeners and
  exit any animation loops. The `no-players` widget should be made
  visible when the last player disappears, and invisible when the
  first one appears, to alert the user what is going on."
  [shutdown-chan]
  (map (partial create-player-cell shutdown-chan) (range 1 7)))

(defn- make-window-visible
  "Ensures that the Player Status window is in front, not too far off
  the screen, and shown."
  [trigger-frame globals]
  (let [^JFrame window @player-window]
    (util/restore-window-position window :player-status trigger-frame)
    (seesaw/show! window)
    (.toFront window)
    (.setAlwaysOnTop window (boolean (:player-status-always-on-top @globals)))))

(defn build-no-player-indicator
  "Creates a label with a large border that reports the absence of any
  players on the network."
  []
  (let [^JLabel no-players (seesaw/label :text "No players are currently visible on the network."
                                         :font (util/get-display-font :orbitron Font/PLAIN 16))]
    (.setBorder no-players (javax.swing.border.EmptyBorder. 10 10 10 10))
    no-players))

(defn- update-slot-labels
  "Updates the USB/SD labels of a player cell in case the device is an
  XDJ-XZ, which has two USB slots instead."
  [cell ^DeviceAnnouncement device]
  (seesaw/invoke-soon
   (if (= "XDJ-XZ" (.getDeviceName device))
     (do
       (seesaw/value! (seesaw/select cell [:#sd-label]) "USB 1:")
       (seesaw/value! (seesaw/select cell [:#usb-label]) "USB 2:"))
     (do
       (seesaw/value! (seesaw/select cell [:#sd-label]) "SD:")
       (seesaw/value! (seesaw/select cell [:#usb-label]) "USB:")))))

(defn- default-column-calculator
  "The function to use to calculate the number of columns the player
  status window should have if the user hasn't expressed their own
  preferences via the `:player-status-columns` trigger global. Use a
  single column until we exceed two players, then two columns until we
  exceed four players."
  [num-players]
  (inc (quot (dec num-players) 2)))

(defn- player-columns
  "Determine how many columns the player status window should have,
  given the number of players that is visible on the network. Checks
  the value, if any, stored under the key `:player-status-columns` in
  the trigger globals. If that is an integer, uses it. If it is a
  function, calls it with the number of visible players, and if it
  returns an integer, uses that. Otherwise (or if there is a problem
  calling the function, uses `default-column-calculator`."
  [visible-players]
  (let [num-players     (count visible-players)
        default-columns (default-column-calculator num-players)]
    (if-let [cols (:player-status-columns @expressions/globals)]
      (cond
        (integer? cols)
        cols

        (ifn? cols)
        (try
          (let [result (cols num-players)]
            (if (integer? result)
              result
              (do
                (timbre/warn ":player-status-columns function returned non-integer result, using default rules."
                             "Ignoring returned result:" result)
                default-columns)))
          (catch Throwable t
            (timbre/error t "Problem running :player-status-columns function, using default rules.")
            default-columns))

        :else
        (do
          (timbre/warn ":player-status-columns holds neither an integer nor a function, using default rules.")
          default-columns))
      default-columns)))

(defn- players-present
  "Builds a grid to contain only the players which are currently visible
  on the netowrk, and if there are none, to contain the no-players
  indicator. If there are two or fewer players, the grid will have a
  single column, which is friendlier to the smaller screens that are
  often available front of house. If there ae four or fewer, which
  will be the case for setups prior to the CDJ-3000 and DJM-V10, then
  the grid will have two columns. And if there are more than four
  players, the grid will have three columns. The user can override
  this default logic by setting a value under the key
  `:player-status-columns` in the trigger globals.

  Also updates the USB/SD labels in case the device is an XDJ-XZ,
  which has two USB slots instead. If we are in Opus Quad mode, the
  labels will already be in the right state."
  [_ players no-players]
  (let [visible-players (keep-indexed (fn [index player]
                                        (when-let [device (.getLatestAnnouncementFrom device-finder (inc index))]
                                          (when-not (opus-quad?) (update-slot-labels player device))
                                          player))
                                      players)
        grid (seesaw/grid-panel :id :players :columns (player-columns visible-players))]

    (seesaw/config! grid :items (or (seq visible-players) [no-players]))
    grid))

(defonce ^{:doc "Even though this window was never designed to support
  resizing, and so looks terrible when resized, some users came to
  rely on using it in a maximized window. This can be set to `true` in
  the Global Setup Expression to allow them to continue to do so until
  such time as there is more explicit support for resizing that looks
  good."}
  allow-ugly-resizing (atom false))

(defn- create-window
  "Creates the Player Status window."
  []
  (try
    (util/load-fonts)
    (let [shutdown-chan (async/promise-chan)
          ^JFrame root  (seesaw/frame :title "Player Status"
                                      :on-close :dispose)
          grid          (seesaw/grid-panel :id :players :columns 1)
          players       (create-player-cells shutdown-chan)
          no-players    (build-no-player-indicator)
          dev-listener  (reify DeviceAnnouncementListener  ; Update the grid contents as players come and go
                          (deviceFound [_this _]
                            (seesaw/invoke-later
                             (seesaw/config! root
                                             :content (seesaw/scrollable (players-present grid players no-players)))
                             (seesaw/pack! root)))
                          (deviceLost [_this _]
                            (seesaw/invoke-later
                             (seesaw/config! root
                                             :content (seesaw/scrollable (players-present grid players no-players)))
                             (seesaw/pack! root))))
          stop-listener (reify LifecycleListener
                          (started [_this _sender])  ; Nothing for us to do, we exited as soon a stop happened anyway.
                          (stopped [_this _sender]  ; Close our window if VirtualCdj gets shut down (we went offline).
                            (seesaw/invoke-later
                             (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))]
      (seesaw/config! root :content (seesaw/scrollable (players-present grid players no-players)))
      (.addDeviceAnnouncementListener device-finder dev-listener)
      (.addLifecycleListener virtual-cdj stop-listener)
      (seesaw/listen root :window-closed (fn [_]
                                           (>!! shutdown-chan :done)
                                           (reset! player-window nil)
                                           (.removeDeviceAnnouncementListener device-finder dev-listener)
                                           (.removeLifecycleListener virtual-cdj stop-listener)
                                           (doseq [^JFrame detail (vals @waveform-windows)]
                                             (.dispose detail))))
      (seesaw/listen root :component-moved (fn [_] (util/save-window-position root :player-status true)))
      (seesaw/pack! root)
      (.setResizable root @allow-ugly-resizing)
      (reset! player-window root)
      (when-not (.isRunning virtual-cdj) (.stopped stop-listener virtual-cdj)))  ; In case we went offline during setup.
    (catch Exception e
      (timbre/error e "Problem creating Player Status window."))))

(defn show-window
  "Open the Player Status window if it is not already open."
  [trigger-frame globals]
  (locking player-window
    (when-not @player-window (create-window)))
  (make-window-visible trigger-frame globals))
