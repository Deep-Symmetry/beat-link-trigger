(ns beat-link-trigger.overlay
  "Serves a customizable overlay page for use with OBS Studio."
  (:require [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.content-type]
            [ring.middleware.params]
            [org.httpkit.server :as server]
            [selmer.parser :as parser]
            [beat-link-trigger.expressions :as expr]
            [beat-link-trigger.util :as util])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Util
            DeviceAnnouncement DeviceUpdate Beat CdjStatus MixerStatus MediaDetails
            CdjStatus$TrackSourceSlot CdjStatus$TrackType]
           [org.deepsymmetry.beatlink.data TimeFinder MetadataFinder SignatureFinder
            PlaybackState TrackPositionUpdate SlotReference TrackMetadata AlbumArt ColorItem SearchableItem]))

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
    CdjStatus$TrackType/REKORDBOX        "Rekordbox"
    CdjStatus$TrackType/UNANALYZED       "Unanalyzed"
    "Unknown"))

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

(defn describe-status
  "Builds a parameter map with useful information obtained from the
  latest status packet received from the specified device number."
  [number]
  (when-let [status (.getLatestStatusFor expr/virtual-cdj number)]
    (let [bpm       (.getBpm status)
          bpm-valid (not= bpm 65535)]
      {:beat-number           (.getBeatNumber status)
       :beat-within-bar       (when (.isBeatWithinBarMeaningful status) (.getBeatWithinBar status))
       :track-bpm             (when bpm-valid (/ bpm 100.0))
       :cue-countdown         (.getCueCountdown status)
       :cue-countdown-display (.formatCueCountdown status)
       :tempo                 (when bpm-valid (.getEffectiveTempo status))
       :firmware-version      (.getFirmwareVersion status)
       :pitch                 (Util/pitchToPercentage (.getPitch status))
       :pitch-multiplier      (Util/pitchToMultiplier (.getPitch status))
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
       :is-synced             (.isSynced status)
       :is-tempo-master       (.isTempoMaster status)
       :is-track-loaded       (.isTrackLoaded status)})))

(defn format-time
  "Given a track time in milliseconds, explodes it into a map that also
  includes minutes, seconds, frames, and frame-tenths, as displayed on
  a CDJ."
  [ms]
  (let [half-frames (mod (Util/timeToHalfFrame ms) 150)]
    {:milliseconds ms
     :minutes      (long (/ ms 60000))
     :seconds      (long (/ (mod ms 60000) 1000))
     :frames       (long (/ half-frames 2))
     :frame-tenths (if (even? half-frames) 0 5)}))

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

(defn describe-device
  "Builds a template parameter map entry describing a device found on
  the network."
  [^DeviceAnnouncement device]
  (let [number (.getDeviceNumber device)]
    {number (merge {:number number
                    :name   (.getDeviceName device)
                    :address (.. device getAddress getHostAddress)}
                   (when-let [metadata (format-metadata number)]
                     {:track metadata})
                   (describe-status number)
                   (describe-times number))}))

(defn build-params
  "Sets up the overlay template parameters based on the current playback
  state."
  []
  {:players (apply merge (map describe-device (.getCurrentDevices expr/device-finder)))})

(defn- build-overlay
  "Builds a handler that renders the overlay template configured for
  the server being built."
  [config]
  (fn [_]
    (-> (parser/render-file (:template config) (build-params))
        response/response
        (response/content-type "text/html; charset=utf-8"))))

(defn- return-styles
  "A handler that renders the default embedded stylesheet."
  []
  (-> (response/resource-response "beat_link_trigger/styles.css")
      (response/content-type "text/css")))

(defn return-artwork
  "Returns the artwork associated with the track on the specified
  player, or a transparent image if there is none. If the query
  parameter `icons` was passed with the value `true`, then instad of a
  simple transparent image, missing track artwork is replaced by an
  icon representing the media type of the track."
  [player icons]
  (println player icons)
  (let [player (Long/valueOf player)
        icons  (Boolean/valueOf icons)]
    (if-let [art (.getLatestArtFor expr/art-finder player)]
      (let [baos (java.io.ByteArrayOutputStream.)]
        (javax.imageio.ImageIO/write (.getImage art) "jpg" baos)
        (-> (java.io.ByteArrayInputStream. (.toByteArray baos))
            response/response
            (response/content-type "image/jpeg")))
      (let [missing-image-path (if icons
                                 (util/generic-media-resource player)
                                 "images/NoArt.png")]
        (-> (response/resource-response missing-image-path)
            (response/content-type "image/png"))))))

(defn- build-routes
  "Builds the set of routes that will handle requests for the server
  under construction."
  [config]
  (compojure/routes
   (compojure/GET "/" [] (build-overlay config))
   (compojure/GET "/styles.css" [] (return-styles))
   (compojure/GET "/artwork/:player{[0-9]+}" [player icons] (return-artwork player icons))
   (route/files "/public/" {:root (:public config)})
   (route/not-found "<p>Page not found.</p>")))

(defn- resolve-resource
  "Handles the optional resource overrides when starting the server. If
  one has been supplied, treat it as a file. Otherwise resolve
  `default-path` within our class path."
  [override default-path]
  (if override
    (.toURL (.toURI (io/file override)))
    default-path))

(defn start-server
  "Creates, starts, and returns an overlay server on the specified port.
  Optional keyword arguments allow you to supply a `:template` file
  that will be used to render the overlay and a `:public` directory
  that will be served as `/public` instead of the defaults which come
  from inside the application resources. You can later shut down the
  server by pasing the value that was returned by this function to
  `stop-server`."
  [port & {:keys [template public show]}]
  (let [config {:port     port
                :template (resolve-resource template "beat_link_trigger/overlay.html")
                :public   (or public "public")}
        routes (build-routes config)
        app    (-> routes
                   ring.middleware.content-type/wrap-content-type
                   ring.middleware.params/wrap-params)
        server (server/run-server app {:port port})]
    (println config)
    ;; TODO: Get rid of this, make it a separate function we call from the UI.
    (when show (browse/browse-url (str "http://127.0.0.1:" port "/")))
    (assoc config :stop server)))

(defn stop-server
  "Shuts down the supplied overlay web server (which must have been
  started by `start-server`)."
  [server]
  ((:stop server)))
