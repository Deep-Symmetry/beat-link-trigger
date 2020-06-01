(ns beat-link-trigger.overlay
  "Serves a customizable overlay page for use with OBS Studio."
  (:require [clojure.edn :as edn]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.content-type]
            [ring.middleware.params]
            [org.httpkit.server :as server]
            [selmer.parser :as parser]
            [beat-link-trigger.expressions :as expr]
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.show :as show]
            [beat-link-trigger.util :as util]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre]
            [clojure.string :as str])
  (:import [beat_link_trigger.tree_node ITemplateParent]
           [java.awt.image BufferedImage]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [javax.imageio ImageIO]
           [javax.swing JFrame JTree]
           [javax.swing.tree DefaultMutableTreeNode DefaultTreeModel]
           [org.deepsymmetry.beatlink LifecycleListener VirtualCdj Util
            DeviceAnnouncement DeviceUpdate Beat CdjStatus MixerStatus MediaDetails
            CdjStatus$TrackSourceSlot CdjStatus$TrackType]
           [org.deepsymmetry.beatlink.data DataReference TimeFinder MetadataFinder SignatureFinder
            PlaybackState TrackPositionUpdate SlotReference TrackMetadata AlbumArt ColorItem SearchableItem
            WaveformDetailComponent WaveformPreviewComponent]))

(defonce ^{:private true
           :doc     "Holds the overlay web server when it is running."}
  server (atom nil))

(def overlay-template-name
  "The file name of the overlay template which renders the root folder
  of the overlay web server."
  "overlay.html")

(def default-templates-path
  "The resource path within our jar from which templates are loaded
  until the user specifies their own template path."
  (clojure.java.io/resource "beat_link_trigger/templates"))

(defn format-source-slot
  "Converts the Java enum value representing the slot from which a track
  was loaded to a nice human-readable string."
  [slot]
  (util/case-enum slot
    CdjStatus$TrackSourceSlot/NO_TRACK   "No Track"
    CdjStatus$TrackSourceSlot/CD_SLOT    "CD Slot"
    CdjStatus$TrackSourceSlot/SD_SLOT    "SD Slot"
    CdjStatus$TrackSourceSlot/USB_SLOT   "USB Slot"
    CdjStatus$TrackSourceSlot/COLLECTION "rekordbox"
    "Unknown Slot"))

(defn format-track-type
  "Converts the Java enum value representing the type of track that
  was loaded in a player to a nice human-readable string."
  [track]
  (util/case-enum track
    CdjStatus$TrackType/NO_TRACK         "No Track"
    CdjStatus$TrackType/CD_DIGITAL_AUDIO "CD Digital Audio"
    CdjStatus$TrackType/REKORDBOX        "rekordbox"
    CdjStatus$TrackType/UNANALYZED       "Unanalyzed"
    "Unknown"))

(defn- reformat-sample-metadata
  "Adjusts track metadata from the form stored in a show file to the
  form reported by the overlay server, for sample data purposes."
  [metadata ^DataReference data-reference]
  (-> metadata
      (assoc :added (:data-added metadata)
             :id (.-rekordboxId data-reference)
             :slot (format-source-slot (.-slot data-reference))
             :starting-tempo (/ (:tempo metadata) 100.0)
             :type "rekordbox"
             :year (+ (rand-int 100) 1920))
      (dissoc :date-added :tempo)))

(defn read-sample-track
  "Reads the sample track data stored for the given player number."
  [player]
  (let [path (java.nio.file.Paths/get (.toURI (.getResource VirtualCdj (str "/beat_link_trigger/sampleTracks/"
                                                                            player))))
        ref  (DataReference. player
                             (rand-nth [CdjStatus$TrackSourceSlot/USB_SLOT CdjStatus$TrackSourceSlot/SD_SLOT])
                             (inc (rand-int 3000)))]
    {:art       (show/read-art path ref)
     :beat-grid (show/read-beat-grid path ref)
     :cue-list  (show/read-cue-list path)
     :detail    (show/read-detail path ref)
     :preview   (show/read-preview path ref)
     :metadata  (-> (.getResourceAsStream VirtualCdj (str "/beat_link_trigger/sampleTracks/" player "/metadata.edn"))
                    slurp
                    edn/read-string
                    (reformat-sample-metadata ref))}))

(def sample-track-data
  "Holds the simulated data used to help people work on overlays when
  they can't go online. Delayed so we don't build it until the first
  time it's needed."
  (delay
   (into {} (for [player (range 1 3)]
              [player (read-sample-track player)]))))

(def simulated-players
  "A map from player number to simulated track number used when
  building dummy data for working on overlay templates while offline.
  This can be changed to reflect your actual player setup. Currently
  there are only two simulated tracks, numbered 1 and 2 available.
  They can be loaded in multiple players, or a player can be marked as
  present but empty by setting its track number to `nil`."
  (atom {1 1
         2 2}))

(defn item-label
  "Given a searchable item, if it is not nil, returns its label."
  [^SearchableItem item]
  (when item (.-label item)))

(defn color-name
  "Given a non-nil color item, returns its name."
  [^ColorItem color]
  (when (and color (not (ColorItem/isNoColor (.color color))))
    (.-colorName color)))

(defn color-code
  "Given a non-nil color item, returns the CSS color code for it."
  [^ColorItem color]
  (when (and color (not (ColorItem/isNoColor (.color color))))
    (format "#%06x" (bit-and (.. color color getRGB) 0xffffff))))

(defn format-metadata
  "Builds a map describing the metadata of the track loaded in the
  specified player, if any, in a format convenient for use in the
  overlay template."
  [player]
  (when-let [metadata (.getLatestMetadataFor expr/metadata-finder player)]
    {:id              (.. metadata trackReference rekordboxId)
     :slot            (format-source-slot (.. metadata trackReference slot))
     :type            (format-track-type (.-trackType metadata))
     :title           (.getTitle metadata)
     :album           (item-label (.getAlbum metadata))
     :artist          (item-label (.getArtist metadata))
     :color-name      (color-name (.getColor metadata))
     :color           (color-code (.getColor metadata))
     :comment         (.getComment metadata)
     :added           (.getDateAdded metadata)
     :duration        (.getDuration metadata)
     :genre           (item-label (.getGenre metadata))
     :key             (item-label (.getKey metadata))
     :label           (item-label (.getLabel metadata))
     :original-artist (item-label (.getOriginalArtist metadata))
     :rating          (.getRating metadata)
     :remixer         (.getRemixer metadata)
     :starting-tempo  (/ (.getTempo metadata) 100.0)
     :year            (.getYear metadata)}))

(defn format-pitch
  "Formats a pitch as it is displayed on a player."
  [pitch]
  (let [abs-pitch     (Math/abs pitch)
        format-string (if (< abs-pitch 20.0) "%5.2f" "%5.1f")
        formatted     (String/format java.util.Locale/ROOT format-string (to-array [abs-pitch]))
        sign          (if (= formatted " 0.00") " " (if (neg? pitch) "-" "+"))]
    (str sign formatted "%")))

(defn describe-status
  "Builds a parameter map with useful information obtained from the
  latest status packet received from the specified device number."
  [number]
  (when-let [status (.getLatestStatusFor expr/virtual-cdj number)]
    (let [bpm       (.getBpm status)
          bpm-valid (not= bpm 65535)]
      (merge
       ;; First the basic information available from any status update.
       {:beat-within-bar  (when (.isBeatWithinBarMeaningful status) (.getBeatWithinBar status))
        :track-bpm        (when bpm-valid (/ bpm 100.0))
        :tempo            (when bpm-valid (.getEffectiveTempo status))
        :pitch            (Util/pitchToPercentage (.getPitch status))
        :pitch-display    (format-pitch (Util/pitchToPercentage (.getPitch status)))
        :pitch-multiplier (Util/pitchToMultiplier (.getPitch status))
        :is-synced        (.isSynced status)
        :is-tempo-master  (.isTempoMaster status)}
       (when (instance? CdjStatus status)
         ;; We can add a bunch more stuff that only CDJs provide.
         {:beat-number           (.getBeatNumber status)
          :cue-countdown         (.getCueCountdown status)
          :cue-countdown-display (.formatCueCountdown status)
          :firmware-version      (.getFirmwareVersion status)
          :track-number          (.getTrackNumber status)
          :track-source-player   (.getTrackSourcePlayer status)
          :is-at-end             (.isAtEnd status)
          :is-bpm-only-synced    (.isBpmOnlySynced status)
          :is-busy               (.isBusy status)
          :is-cued               (.isCued status)
          :is-looping            (.isLooping status)
          :is-on-air             (.isOnAir status)
          :is-paused             (.isPaused status)
          :is-playing            (.isPlaying status)
          :is-playing-backwards  (.isPlayingBackwards status)
          :is-playing-cdj-mode   (.isPlayingCdjMode status)
          :is-playing-forwards   (.isPlayingForwards status)
          :is-playing-vinyl-mode (.isPlayingVinylMode status)
          :is-searching          (.isSearching status)
          :is-track-loaded       (.isTrackLoaded status)})))))

(defn format-time
  "Given a track time in milliseconds, explodes it into a map that also
  includes minutes, seconds, frames, and frame-tenths, as displayed on
  a CDJ."
  [ms]
  (let [half-frames  (mod (Util/timeToHalfFrame ms) 150)
        minutes      (long (/ ms 60000))
        seconds      (long (/ (mod ms 60000) 1000))
        frames       (long (/ half-frames 2))
        frame-tenths (if (even? half-frames) 0 5)]
    {:raw-milliseconds ms
     :minutes          minutes
     :seconds          seconds
     :frames           frames
     :frame-tenths     frame-tenths
     :display          (format "%02d:%02d:%02d.%d" minutes seconds frames frame-tenths)}))

(defn describe-times
  "Builds a parameter map with information about the playback and
  remaining time for the specified player, when available."
  [number]
  (when (.isRunning expr/time-finder)
    (let [played (.getTimeFor expr/time-finder number)]
      (when-not (neg? played)
        (merge {:time-played (format-time played)}
               (when-let [detail (.getLatestDetailFor expr/waveform-finder number)]
                 (let [remain (max 0 (- (.getTotalTime detail) played))]
                   {:time-remaining (format-time remain)})))))))

(defn device-kind
  "Returns a keyword identifying the type of a device on a DJ Link
  network, given its number. Used for grouping in the template, since
  generally it will care only about players, rather than mixers or
  rekordbox collections."
  [number]
  (cond
    (< number 0x10) :players
    (or (< number 0x20) (> number 0x28)) :collections
    :else :mixers))

(defn describe-device
  "Builds a template parameter map entry describing a device found on
  the network."
  [^DeviceAnnouncement device]
  (let [number (.getDeviceNumber device)]
    (merge {:number  number
            :name    (.getDeviceName device)
            :address (.. device getAddress getHostAddress)
            :kind    (device-kind number)}
           (when-let [metadata (format-metadata number)]
             {:track metadata})
           (describe-status number)
           (describe-times number))))

(defn simulate-cdj-status
  "Creates randomized sample data describing status information for a
  player with the specified number and loaded track (which can be
  `nil`). `master` is the number of the simulated tempo master
  device, so our status will be set accordingly."
  [number track master]
  (let [position         (when-let [metadata (:metadata track)]
                           (rand-int (* 1000 (dec (:duration metadata)))))
        beat-grid        (:beat-grid track)
        beat             (when (and beat-grid position) (.findBeatAtTime beat-grid position))
        bar-beat         (when beat (.getBeatWithinBar beat-grid beat))
        pitch            (+ 1048576 (- (rand-int 200000) 100000))
        pitch-multiplier (Util/pitchToMultiplier pitch)
        track-bpm        (get-in track [:metadata :starting-tempo])]
    (merge {:beat-within-bar       (or bar-beat (inc (rand-int 4)))
            :tempo                 (* (or track-bpm 128.0) pitch-multiplier)
            :pitch                 (Util/pitchToPercentage pitch)
            :pitch-display         (format-pitch (Util/pitchToPercentage pitch))
            :pitch-multiplier      (Util/pitchToMultiplier pitch)
            :is-synced             false
            :is-tempo-master       (= number master)
            :beat-number           (or beat 0)
            :cue-countdown         511
            :cue-countdown-display "--.-"
            :firmware-version      "0.42"
            :track-number          (if track (inc (rand-int 50)) 0)
            :track-source-player   (if track number 0)
            :is-at-end             false
            :is-bpm-only-synced    false
            :is-busy               false
            :is-cued               false
            :is-looping            false
            :is-on-air             (rand-nth [true false])
            :is-paused             true
            :is-playing            false
            :is-playing-backwards  false
            :is-playing-cdj-mode   false
            :is-playing-forwards   false
            :is-playing-vinyl-mode false
            :is-searching          false
            :is-track-loaded       (boolean track)}
           (when track-bpm
             {:track-bpm track-bpm})
           (when position
             {:time-played (format-time position)})
           (when (and position (:detail track))
             {:time-remaining (format-time (max 0 (- (.getTotalTime (:detail track)) position)))}))))

(defn simulate-player
  "Creates randomized sample data describing a player with the specified
  number and loaded track (which can be `nil`)."
  [number track master]
  (merge
   {:number  number
    :name    (rand-nth ["CDJ-900nexus" "CDJ-2000nexus" "CDJ-900nxs2" "CDJ-2000nxs2" "XDJ-XZ"])
    :address "127.0.0.1"
    :kind    :players}
   (when track
     {:track (:metadata track)})
   (simulate-cdj-status number track master)))

(defn- build-simulated-players
  "Creates the player entries corresponding to the configuration in
  `simulated-players`. `master` specifies the number of the player
  that should be the tempo master, or 0 if none should."
  [master]
  (into {}
        (for [[number track] @simulated-players]
          [number (simulate-player number (get @sample-track-data track) master)])))

(defn build-simulated-params
  "Creates dummy overlay template parameters to support working on
  templates when you can't be online with actual hardware."
  []
  (let [potential-masters (map first (filter (fn [[_ track]]
                                               (some? (get @sample-track-data track))) @simulated-players))
        master            (rand-nth (conj potential-masters 0))
        players           (build-simulated-players master)]
    (merge {:players players}
           (when (pos? master)
             {:master (get players master)}))))

(defn build-params
  "Sets up the overlay template parameters based on the current playback
  state."
  []
  (if (.isRunning expr/virtual-cdj)
    (merge  ; We can return actual data.
     (reduce (fn [result device]
               (assoc-in result [(:kind device) (:number device)] device))
             {}
             (map describe-device (.getCurrentDevices expr/device-finder)))
     (when-let [master (.getTempoMaster expr/virtual-cdj)]
       {:master (describe-device (.getLatestAnnouncementFrom expr/device-finder (.getDeviceNumber master)))}))
    (:simulated-params @server)))

(defn- build-overlay
  "Builds a handler that renders the overlay template configured for
  the server being built."
  []
  (fn [_]
    (-> (parser/render-file overlay-template-name (build-params))
        response/response
        (response/content-type "text/html; charset=utf-8"))))

(defn- return-styles
  "A handler that renders the default embedded stylesheet."
  []
  (-> (response/resource-response "beat_link_trigger/styles.css")
      (response/content-type "text/css")))

(defn- return-font
  "A handler that returns one of the embedded fonts, given the shorthand
  name by which we offer it."
  [font]
  (if-let [path (case font
                  "dseg7"         "DSEG/DSEG7Classic-Regular.ttf"
                  "orbitron"      "Orbitron/Orbitron-Black.ttf"
                  "orbitron-bold" "Orbitron/Orbitron-Bold.ttf"
                  "teko"          "Teko/Teko-Regular.ttf"
                  "teko-bold"     "Teko/Teko-SemiBold.ttf"
                  nil)]
    (-> (response/resource-response (str "fonts/" path))
        (response/content-type "font/ttf"))
    (response/not-found)))

(defn return-artwork
  "Returns the artwork associated with the track on the specified
  player, or a transparent image if there is none. If the query
  parameter `icons` was passed with the value `true`, then instad of a
  simple transparent image, missing track artwork is replaced by an
  icon representing the media type of the track."
  [player icons]
  (let [player (Long/valueOf player)
        icons  (Boolean/valueOf icons)]
    (if-let [art (if (.isRunning expr/art-finder)
                   (.getLatestArtFor expr/art-finder player)
                   (get-in @sample-track-data [(get @simulated-players player) :art]))]
      (let [baos (ByteArrayOutputStream.)]
        (ImageIO/write (.getImage art) "jpg" baos)
        (-> (ByteArrayInputStream. (.toByteArray baos))
            response/response
            (response/content-type "image/jpeg")
            (response/header "Cache-Control" "max-age=1")))
      (let [missing-image-path (if icons
                                 (if (.isRunning expr/virtual-cdj)
                                   (util/generic-media-resource player)
                                   (rand-nth ["images/USB.png" "images/SD.png" "images/CD_data_logo.png"] ))
                                 "images/NoArt.png")]
        (-> (response/resource-response missing-image-path)
            (response/content-type "image/png")
            (response/header "Cache-Control" "max-age=1"))))))

(defn- safe-parse-int
  "Tries to parse a value as an integer; if the value is missing or the
  parse fails, returns the supplied default value."
  [value default]
  (if value
    (try
      (Integer/valueOf value)
      (catch Throwable _
        default))
    default))

(defn simulate-wave-preview
  "Returns the waveform preview image associated with the specified
  simulated player when running offline. Renders at the specified
  size, unless it is smaller than the minimum. If omitted, uses
  default size of 408 by 56 pixels."
  [player width height]
  (let [player   (Long/valueOf player)
        width    (safe-parse-int width 408)
        height   (safe-parse-int height 56)
        params   (get-in @server [:simulated-params :players player])
        position (get-in params [:time-played :raw-milliseconds])
        samples  (get @sample-track-data (get @simulated-players player))
        cue-list (:cue-list samples)
        preview  (:preview samples)]
    (if (and preview cue-list)  ; Only try to render when the data we need is available.
      (let [component (WaveformPreviewComponent. preview (get-in params [:track :duration]) cue-list)
            min-size  (.getMinimumSize component)]
        (.setBounds component 0 0 (max width (.-width min-size)) (max height (.-height min-size)))
        (when position
          (.setPlaybackState component player position false))
        (let [bi   (BufferedImage. (.. component getSize width) (.. component getSize height)
                                   BufferedImage/TYPE_INT_ARGB)
              g    (.createGraphics bi)
              baos (ByteArrayOutputStream.)]
          (.paint component g)
          (.dispose g)
          (ImageIO/write bi "png" baos)
          (-> (ByteArrayInputStream. (.toByteArray baos))
              response/response
              (response/content-type "image/png")
              (response/header "Cache-Control" "no-store"))))
      (-> (response/resource-response "images/NoArt.png")  ; No waveform preview available, return a transparent image.
          (response/content-type "image/png")
          (response/header "Cache-Control" "max-age=1")))))

(defn return-wave-preview
  "Returns the waveform preview image associated with the specified
  player. Renders at the specified size, unless it is smaller than the
  minimum. If omitted, uses default size of 408 by 56 pixels."
  [player width height]
  (if (.isRunning expr/metadata-finder)
    (let [player   (Integer/valueOf player)  ; We can return real data.
          width    (safe-parse-int width 408)
          height   (safe-parse-int height 56)
          track    (.getLatestMetadataFor expr/metadata-finder player)
          position (.getLatestPositionFor expr/time-finder player)
          preview  (.getLatestPreviewFor expr/waveform-finder player)]
      (if preview  ; Only try to render when there is a waveform preview available.
        (let [component (WaveformPreviewComponent. preview track)
              min-size  (.getMinimumSize component)]
          (.setBounds component 0 0 (max width (.-width min-size)) (max height (.-height min-size)))
          (when position
            (.setPlaybackState component player (.-milliseconds position) (.-playing position)))
          (let [bi   (BufferedImage. (.. component getSize width) (.. component getSize height)
                                     BufferedImage/TYPE_INT_ARGB)
                g    (.createGraphics bi)
                baos (ByteArrayOutputStream.)]
            (.paint component g)
            (.dispose g)
            (ImageIO/write bi "png" baos)
            (-> (ByteArrayInputStream. (.toByteArray baos))
                response/response
                (response/content-type "image/png")
                (response/header "Cache-Control" "no-store"))))
        (-> (response/resource-response "images/NoArt.png")  ; No waveform preview available, return a transparent image.
            (response/content-type "image/png")
            (response/header "Cache-Control" "max-age=1"))))
    (simulate-wave-preview player width height)))

(defn simulate-wave-detail
  "Returns the waveform detail image associated with the specified
  simulated player when running offline. Renders at the specified
  `width` and `height`, unless they are smaller than the minimum. If
  not specified, uses the minimum size supported by the component that
  draws the graphics. If `scale` is provided, sets the amount by which
  the waveform should be zoomed in: 1 means full size, 2 is half size,
  and so on. The default scale is 4."
  [player width height scale]
  (let [player    (Integer/valueOf player)
        width     (safe-parse-int width 0)
        height    (safe-parse-int height 0)
        scale     (safe-parse-int scale 4)
        params    (get-in @server [:simulated-params :players player])
        position  (get-in params [:time-played :raw-milliseconds])
        samples   (get @sample-track-data (get @simulated-players player))
        cue-list  (:cue-list samples)
        beat-grid (:beat-grid samples)
        detail    (:detail samples)]
    (if detail  ; Only try to render when there is a waveform detail available.
      (let [component (WaveformDetailComponent. detail cue-list beat-grid)
            min-size  (.getMinimumSize component)]
        (.setBounds component 0 0 (max width (.-width min-size)) (max height (.-height min-size)))
        (.setScale component scale)
        (when position
          (.setPlaybackState component player position false))
        (let [bi   (BufferedImage. (.. component getSize width)
                                   (.. component getSize height) BufferedImage/TYPE_INT_ARGB)
              g    (.createGraphics bi)
              baos (ByteArrayOutputStream.)]
          (.paint component g)
          (.dispose g)
          (ImageIO/write bi "png" baos)
          (-> (ByteArrayInputStream. (.toByteArray baos))
              response/response
              (response/content-type "image/png")
              (response/header "Cache-Control" "no-store"))))
      (-> (response/resource-response "images/NoArt.png")  ; No waveform detail available, return a transparent image.
          (response/content-type "image/png")
          (response/header "Cache-Control" "max-age=1")))))

(defn return-wave-detail
  "Returns the waveform detail image associated with the specified
  player. Renders at the specified `width` and `height`, unless they
  are smaller than the minimum. If not specified, uses the minimum
  size supported by the component that draws the graphics. If `scale`
  is provided, sets the amount by which the waveform should be zoomed
  in: 1 means full size, 2 is half size, and so on. The default scale
  is 4."
  [player width height scale]
  (if (.isRunning expr/metadata-finder)
    (let [player    (Integer/valueOf player)  ; We can return actual data.
          width     (safe-parse-int width 0)
          height    (safe-parse-int height 0)
          scale     (safe-parse-int scale 4)
          track     (.getLatestMetadataFor expr/metadata-finder player)
          position  (.getLatestPositionFor expr/time-finder player)
          detail (.getLatestDetailFor expr/waveform-finder player)]
      (if detail  ; Only try to render when there is a waveform detail available.
        (let [component (WaveformDetailComponent. detail
                                                  (when track (.getCueList track))
                                                  (.getLatestBeatGridFor expr/beatgrid-finder player))
              min-size  (.getMinimumSize component)]
          (.setBounds component 0 0 (max width (.-width min-size)) (max height (.-height min-size)))
          (.setScale component scale)
          (when position
            (.setPlaybackState component player (.-milliseconds position) (.-playing position)))
          (let [bi   (BufferedImage. (.. component getSize width)
                                     (.. component getSize height) BufferedImage/TYPE_INT_ARGB)
                g    (.createGraphics bi)
                baos (ByteArrayOutputStream.)]
            (.paint component g)
            (.dispose g)
            (ImageIO/write bi "png" baos)
            (-> (ByteArrayInputStream. (.toByteArray baos))
                response/response
                (response/content-type "image/png")
                (response/header "Cache-Control" "no-store"))))
        (-> (response/resource-response "images/NoArt.png")  ; No waveform detail available, return a transparent image.
            (response/content-type "image/png")
            (response/header "Cache-Control" "max-age=1"))))
    (simulate-wave-detail player width height scale)))

(defn- build-routes
  "Builds the set of routes that will handle requests for the server
  under construction."
  [config]
  (compojure/routes
   (compojure/GET "/" [] (build-overlay))
   (compojure/GET "/styles.css" [] (return-styles))
   (compojure/GET "/font/:font" [font] (return-font font))
   (compojure/GET "/artwork/:player{[0-9]+}" [player icons] (return-artwork player icons))
   (compojure/GET "/wave-preview/:player{[0-9]+}" [player width height] (return-wave-preview player width height))
   (compojure/GET "/wave-detail/:player{[0-9]+}" [player width height scale]
                  (return-wave-detail player width height scale))
   (route/files "/public/" {:root (:public config)})
   (route/not-found "<p>Page not found.</p>")))

(defn start-server
  "Creates, starts, and returns an overlay server on the specified port.
  Optional keyword arguments allow you to supply a `:templates`
  directory that will contain the Selmer templates including the base
  `overlay.html`, and a `:public` directory that will be served as
  `/public`, instead of the defaults which come from inside the
  application resources. You can later shut down the server by pasing
  the value that was returned by this function to `stop-server`."
  [port & {:keys [templates public]}]
  (selmer.parser/set-resource-path! (or templates default-templates-path))
  (let [config {:port             port
                :public           (or public "public")
                :simulated-params (build-simulated-params)}
        routes (build-routes config)
        app    (-> routes
                   ring.middleware.content-type/wrap-content-type
                   ring.middleware.params/wrap-params)
        server (server/run-server app {:port port})]
    (assoc config :stop server)))

(defn stop-server
  "Shuts down the supplied overlay web server (which must have been
  started by `start-server`)."
  [server]
  ((:stop server)))


;; This secton provides the user interface for configuring and running
;; the overlay web server.

(defn- online?
  "Check whether we are in online mode, with all the required
  beat-link finder objects running."
  []
  (and (.isRunning expr/device-finder) (.isRunning expr/virtual-cdj)))

(defonce ^{:private true
           :doc     "Holds the frame allowing the user to configure and
  control the overlay web server."}
  window (atom nil))

(defn- current-port
  "Finds the currently chosen port number."
  []
  (let [port-spinner (seesaw/select @window [:#port])]
    (seesaw/selection port-spinner)))

(defn- browse
  "Opens a browser window on the overlay server."
  []
  (try
    (browse/browse-url (str "http://127.0.0.1:" (current-port) "/"))
    (catch Exception e
      (timbre/warn e "Unable to open browser window on overlay web server")
      (javax.swing.JOptionPane/showMessageDialog
       @window
       (str "Unable to open browser window on OBS overlay web server: " e)
       "Overlay server browse failed"
       javax.swing.JOptionPane/WARNING_MESSAGE))))

(defn- start
  "Try to start the configured overlay web server."
  []
  (swap! server (fn [oldval]
                  (or oldval
                      (let [port-spinner (seesaw/select @window [:#port])
                            port         (seesaw/selection port-spinner)
                            templates    (get-in (prefs/get-preferences) [:overlay :templates])
                            public       (get-in (prefs/get-preferences) [:overlay :public])]
                        (try
                          (let [server (start-server port :templates templates :public public)]
                            (seesaw/config! port-spinner :enabled? false)
                            (seesaw/config! (seesaw/select @window [:#choose-templates]) :enabled? false)
                            (seesaw/config! (seesaw/select @window [:#choose-public]) :enabled? false)
                            (seesaw/config! (seesaw/select @window [:#browse]) :enabled? true)
                            (seesaw/config! (seesaw/select @window [:#inspect]) :enabled? true)
                            server)
                          (catch Exception e
                            (timbre/warn e "Unable to start overlay web server")
                            (future
                              (seesaw/invoke-later
                               (javax.swing.JOptionPane/showMessageDialog
                                @window
                                "Unable to start OBS overlay web server on the chosen port, is another process using it?"
                                "Overlay server startup failed"
                                javax.swing.JOptionPane/WARNING_MESSAGE)))
                            (seesaw/value! (seesaw/select @window [:#run]) false)
                            nil)))))))

(defn- stop
  "Try to stop the configured overlay web server."
  []
  (swap! server (fn [oldval]
                  (when oldval
                    (try
                      (stop-server oldval)
                      (catch Exception e
                        (timbre/warn e "Problem stopping overlay web server")
                            (future
                              (seesaw/invoke-later
                               (javax.swing.JOptionPane/showMessageDialog
                                @window
                                "Problem stopping OBS overlay web server, check the log file for details."
                                "Overlay server shutdown failed"
                                javax.swing.JOptionPane/WARNING_MESSAGE)))))
                    (seesaw/config! (seesaw/select @window [:#choose-templates]) :enabled? true)
                    (seesaw/config! (seesaw/select @window [:#choose-public]) :enabled? true)
                    (seesaw/config! (seesaw/select @window [:#browse]) :enabled? false)
                    (seesaw/config! (seesaw/select @window [:#inspect]) :enabled? false)
                    (seesaw/config! (seesaw/select @window [:#port]) :enabled? true))
                  nil)))

(defn- run-choice
  "Handles user toggling the Run checkbox."
  [checked]
  (if checked
    (start)
    (stop)))

(defn- warn-about-shared-folders
  "Displays a warning explaining the confusion that can occur if the
  public and templates folders are the same."
  []
  (let [config                     (-> (prefs/get-preferences)
                                       :overlay)
        {:keys [public templates]} config]
    (when (and public (= public templates))
      (javax.swing.JOptionPane/showMessageDialog
       @window
       (str "You have chosen the same folder for templates and public resources.\r\n"
            "Although this can work fine, it means that your templates will be\r\n"
            "accessible as, for example, /public/overlay.html, and when accessed\r\n"
            "like this, no variable substitutions will be performed.")
       "Templates mixed with Public Resources"
       javax.swing.JOptionPane/WARNING_MESSAGE))))

(defn- choose-templates-folder
  "Allows the user to select a template folder, updating the prefs and
  UI."
  []
  (when-let [folder (chooser/choose-file
                     @window
                     :selection-mode :dirs-only)]
    (if (.canRead (clojure.java.io/file folder overlay-template-name))
      (let [path (.getCanonicalPath folder)]
        (prefs/put-preferences
         (assoc-in (prefs/get-preferences) [:overlay :templates] path))
        (seesaw/value! (seesaw/select @window [:#templates]) path)
        (seesaw/pack! @window)
        (warn-about-shared-folders))
      (javax.swing.JOptionPane/showMessageDialog
       @window
       (str "Could not read file “" overlay-template-name "” in the chosen folder,\r\n"
            "Templates Folder has not been changed.")
       "Overlay Template Not Found"
       javax.swing.JOptionPane/ERROR_MESSAGE))))

(defn- choose-public-folder
  "Allows the user to select a folder whose contents will be served,
  updating the prefs and UI."
  []
  (when-let [folder (chooser/choose-file
                     @window
                     :selection-mode :dirs-only)]
    (if (.canRead folder)
      (let [path (.getCanonicalPath folder)]
        (prefs/put-preferences
         (assoc-in (prefs/get-preferences) [:overlay :public] path))
        (seesaw/value! (seesaw/select @window [:#public]) path)
        (seesaw/pack! @window)
        (warn-about-shared-folders))
      (javax.swing.JOptionPane/showMessageDialog
       @window
       "The selected folder could not be read, Public Folder has not been changed."
       "Public Folder Unreadable"
       javax.swing.JOptionPane/ERROR_MESSAGE))))

(defn- expand-node
  "Handles tree expansion of one of our parameter nodes. If its children
  have not yet been created, does so."
  [^DefaultMutableTreeNode node]
  (when (zero? (.getChildCount node))
    (let [^ITemplateParent entry (.getUserObject node)
          contents               (.contents entry)]
      (doseq [[k v] (sort contents)]
        (.add node
              (if (map? v)
                (DefaultMutableTreeNode.  ; This is a subtree.
                 (proxy [Object ITemplateParent] []
                   (toString [] (if (keyword? k) (name k) (str k)))
                   (contents [] v))
                 true)
                (DefaultMutableTreeNode.  ; This is a simple value.
                 (proxy [Object ITemplateParent] []
                   (toString [] (str (if (keyword? k) (name k) k) ": " v))
                   (contents [] v))
                 false)))))))

(defn root-node
  "Creates the root node for the template parameter inspection tree.
  Expands it before returning, since there is no expansion UI for it."
  []
  (let [node (DefaultMutableTreeNode.
              (proxy [Object ITemplateParent] []
                (toString [] (str "Overlay Template Parameters:"))
                (contents [] (build-params)))
              true)]
    (expand-node node)
    node))

(defn inspect-params
  "Opens a simplified tree inspector for viewing the current parameter
  values being supplied to the template."
  []
  (let [root          (root-node)
        model         (DefaultTreeModel. root true)
        ^JTree tree   (seesaw/tree :model model)
        scroll        (seesaw/scrollable tree)
        ^JFrame frame (seesaw/frame :content scroll :width 500 :height 600 :title "OBS Overlay Template Parameters"
                                    :on-close :dispose)]
    (.setSelectionMode (.getSelectionModel tree) javax.swing.tree.TreeSelectionModel/SINGLE_TREE_SELECTION)
    (seesaw/listen tree
                   :tree-will-expand
                   (fn [^javax.swing.event.TreeExpansionEvent e]
                     (expand-node (.. e (getPath) (getLastPathComponent)))))
    (.setRootVisible tree false)
    (.setShowsRootHandles tree true)
    (.setLocationRelativeTo frame @window)
    (seesaw/show! frame)))

(defn- make-window-visible
  "Ensures that the overlay server window is in front, and shown."
  [parent]
  (let [^JFrame our-frame          @window
        config                     (-> (prefs/get-preferences)
                                       :overlay)
        {:keys [public templates]} config]
    (util/restore-window-position our-frame :overlay parent)
    (seesaw/show! our-frame)
    (.toFront our-frame)

    ;; Validate templates and public directory, report errors and clear values if needed.
    (when (and templates
               (not (.canRead (clojure.java.io/file (str templates "/" overlay-template-name)))))
      (prefs/put-preferences (update (prefs/get-preferences) :overlay dissoc :templates))
      (seesaw/value! (seesaw/select our-frame [:#templates]) "")
      (selmer.parser/set-resource-path! default-templates-path)
      (javax.swing.JOptionPane/showMessageDialog
       our-frame
       (str "The selected Templates folder no longer contains a readable “" overlay-template-name "”,\r\n"
            "and has been cleared.")
       "Template File Not Found"
       javax.swing.JOptionPane/WARNING_MESSAGE))
    (when public
      (let [public-dir (clojure.java.io/file public)]
        (when-not (and (.canRead public-dir) (.isDirectory public-dir))
          (prefs/put-preferences (update (prefs/get-preferences) :overlay dissoc :public))
          (seesaw/value! (seesaw/select our-frame [:#public]) "")
          (javax.swing.JOptionPane/showMessageDialog
           our-frame
           "The selected Public Folder can no longer be read, and has been cleared."
           "Public Folder Unreadable"
           javax.swing.JOptionPane/WARNING_MESSAGE))))))

(defn- create-window
  "Creates the overlay server window."
  [trigger-frame]
  (try
    (let [^JFrame root (seesaw/frame :title "OBS Overlay Web Server"
                                     :on-close :hide)
          port         (get-in (prefs/get-preferences) [:overlay :port] 17081)
          templates    (get-in (prefs/get-preferences) [:overlay :templates])
          public       (get-in (prefs/get-preferences) [:overlay :public])
          panel        (mig/mig-panel
                        :background "#ccc"
                        :items [[(seesaw/label :text "Server Port:") "align right"]
                                [(seesaw/spinner :id :port
                                                 :model (seesaw/spinner-model port :from 1 :to 32767)
                                                 :listen [:selection (fn [e]
                                                                       (prefs/put-preferences
                                                                        (assoc-in (prefs/get-preferences)
                                                                                  [:overlay :port]
                                                                                  (seesaw/selection e))))])]
                                [(seesaw/button :id :browse :text "Open in Browser" :enabled? false
                                                :listen [:action (fn [_] (browse))])
                                 "gap unrelated, align center"]
                                [(seesaw/checkbox :id :run :text "Run"
                                                  :listen [:item-state-changed (fn [e] (run-choice (seesaw/value e)))])
                                 "gap unrelated, wrap"]

                                [(seesaw/label :text "Templates Folder:") "align right"]
                                [(seesaw/label :id :templates :text templates) "span 2"]
                                [(seesaw/button :id :choose-templates :text "Choose"
                                                :listen [:action (fn [_] (choose-templates-folder))])
                                 "gap unrelated, wrap"]

                                [(seesaw/label :text "Public Folder:") "align right"]
                                [(seesaw/label :id :public :text public) "span 2"]
                                [(seesaw/button :id :choose-public :text "Choose"
                                                :listen [:action (fn [_] (choose-public-folder))])
                                 "gap unrelated, wrap"]

                                [(seesaw/button :id :inspect :text "Inspect Template Parameters" :enabled? false
                                                :listen [:action (fn [_] (inspect-params))])
                                 "span 4, align center"]])]

      ;; Assemble the window
      (seesaw/config! root :content panel)
      (seesaw/pack! root)
      (.setResizable root false)
      (seesaw/listen root :component-moved (fn [_] (util/save-window-position root :overlay true)))
      (reset! window root)
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating nREPL window."))))

(defn show-window
  "Make the overlay sever window visible, creating it if necessary."
  [trigger-frame]
  (if @window
    (make-window-visible trigger-frame)
    (create-window trigger-frame)))
