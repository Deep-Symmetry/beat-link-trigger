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
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.util :as util]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.mig :as mig]
            [inspector-jay.core :as inspector]
            [taoensso.timbre :as timbre]
            [clojure.string :as str])
  (:import [javax.swing JFrame]
           [org.deepsymmetry.beatlink LifecycleListener VirtualCdj Util
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
      (merge
       ;; First the basic information available from any status update.
       {:beat-within-bar       (when (.isBeatWithinBarMeaningful status) (.getBeatWithinBar status))
        :track-bpm             (when bpm-valid (/ bpm 100.0))
        :tempo                 (when bpm-valid (.getEffectiveTempo status))
        :pitch                 (Util/pitchToPercentage (.getPitch status))
        :pitch-multiplier      (Util/pitchToMultiplier (.getPitch status))
        :is-synced             (.isSynced status)
        :is-tempo-master       (.isTempoMaster status)}
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

(defn build-params
  "Sets up the overlay template parameters based on the current playback
  state."
  []
  (merge
   (reduce (fn [result device]
             (assoc-in result [(:kind device) (:number device)] device))
           {}
           (map describe-device (.getCurrentDevices expr/device-finder)))
   (when-let [master (.getTempoMaster expr/virtual-cdj)]
     {:master (describe-device (.getLatestAnnouncementFrom expr/device-finder (.getDeviceNumber master)))})))

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
  (println player icons)
  (let [player (Long/valueOf player)
        icons  (Boolean/valueOf icons)]
    (if-let [art (.getLatestArtFor expr/art-finder player)]
      (let [baos (java.io.ByteArrayOutputStream.)]
        (javax.imageio.ImageIO/write (.getImage art) "jpg" baos)
        (-> (java.io.ByteArrayInputStream. (.toByteArray baos))
            response/response
            (response/content-type "image/jpeg")
            (response/header "Cache-Control" "max-age=1")))
      (let [missing-image-path (if icons
                                 (util/generic-media-resource player)
                                 "images/NoArt.png")]
        (-> (response/resource-response missing-image-path)
            (response/content-type "image/png")
            (response/header "Cache-Control" "max-age=1"))))))

(defn- build-routes
  "Builds the set of routes that will handle requests for the server
  under construction."
  [config]
  (compojure/routes
   (compojure/GET "/" [] (build-overlay config))
   (compojure/GET "/styles.css" [] (return-styles))
   (compojure/GET "/font/:font" [font] (return-font font))
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
  [port & {:keys [template public]}]
  (let [config {:port     port
                :template (resolve-resource template "beat_link_trigger/overlay.html")
                :public   (or public "public")}
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
           :doc     "Holds the overlay web server when it is running."}
  server (atom nil))

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

(defn- inspect
  "Opens up a window to inspect the parameters currently being given to
  the overlay template, as a coding aid."
  []
  (try
    (inspector/inspect (build-params) :window-name "OBS Overlay Template Parameters")
    (catch Throwable t
      (util/inspect-failed t))))

(defn- start
  "Try to start the configured overlay web server."
  []
  (swap! server (fn [oldval]
                  (or oldval
                      (let [port-spinner (seesaw/select @window [:#port])
                            port         (seesaw/selection port-spinner)
                            template     (get-in (prefs/get-preferences) [:overlay :template])
                            public       (get-in (prefs/get-preferences) [:overlay :public])]
                        (try
                          (let [server (start-server port :template template :public public)]
                            (seesaw/config! port-spinner :enabled? false)
                            (seesaw/config! (seesaw/select @window [:#choose-template]) :enabled? false)
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
                    (seesaw/config! (seesaw/select @window [:#choose-template]) :enabled? true)
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

(defn- choose-template
  "Allows the user to select a template file, updating the prefs and UI."
  []
  (when-let [file (chooser/choose-file
                   @window
                   :all-files? false
                   :filters [["HTML files" ["html" "htm" "djhtml"]]
                             (chooser/file-filter "All files" (constantly true))])]
    (if (.canRead file)
      (let [path (.getCanonicalPath file)]
        (prefs/put-preferences
         (assoc-in (prefs/get-preferences) [:overlay :template] path))
        (seesaw/value! (seesaw/select @window [:#template]) path)
        (seesaw/pack! @window))
      (javax.swing.JOptionPane/showMessageDialog
       @window
       "The selected file could not be read, Template has not been changed."
       "Template File Unreadable"
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
        (seesaw/pack! @window))
      (javax.swing.JOptionPane/showMessageDialog
       @window
       "The selected folder could not be read, Public Folder has not been changed."
       "Public Folder Unreadable"
       javax.swing.JOptionPane/ERROR_MESSAGE))))

(defonce ^{:private true
           :doc "Used to detect the `VirtualCdj` starting and stopping so we can react properly."}
  vcdj-lifecycle-listener
  (reify LifecycleListener
    (started [this _]  ; We are online, so the server can be started.
      (seesaw/invoke-later
       (seesaw/config! (seesaw/select @window [:#run]) :enabled? true)))
    (stopped [this _]  ; We are offline, kill the server if it was running, and disable the Run button.
      (seesaw/invoke-later
       (let [run (seesaw/select @window [:#run])]
         (when (seesaw/value run)
           (seesaw/value! run false))
         (seesaw/config! run :enabled? false))))))

(defn- make-window-visible
  "Ensures that the overlay server window is in front, and shown."
  [parent]
  (let [^JFrame our-frame @window]
    (util/restore-window-position our-frame :overlay parent)
    (seesaw/show! our-frame)
    (.toFront our-frame)

    ;; Validate template and public directory, report errors and clear values if needed.
    (when-not (.canRead (clojure.java.io/file (get-in (prefs/get-preferences) [:overlay :template])))
      (prefs/put-preferences (update (prefs/get-preferences) :overlay dissoc :template))
      (seesaw/value! (seesaw/select our-frame [:#template]) "")
      (javax.swing.JOptionPane/showMessageDialog
       our-frame
       "The selected Template file can no longer be read, and has been cleared."
       "Template File Unreadable"
       javax.swing.JOptionPane/WARNING_MESSAGE))
    (let [public (clojure.java.io/file (get-in (prefs/get-preferences) [:overlay :public]))]
      (when-not (and (.canRead public) (.isDirectory public))
        (prefs/put-preferences (update (prefs/get-preferences) :overlay dissoc :public))
        (seesaw/value! (seesaw/select our-frame [:#public]) "")
        (javax.swing.JOptionPane/showMessageDialog
         our-frame
         "The selected Public Folder can no longer be read, and has been cleared."
         "Public Folder Unreadable"
         javax.swing.JOptionPane/WARNING_MESSAGE)))))

(defn- create-window
  "Creates the overlay server window."
  [trigger-frame]
  (try
    (let [^JFrame root (seesaw/frame :title "OBS Overlay Web Server"
                                     :on-close :hide)
          port         (get-in (prefs/get-preferences) [:overlay :port] 17081)
          template     (get-in (prefs/get-preferences) [:overlay :template])
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

                                [(seesaw/label :text "Template:") "align right"]
                                [(seesaw/label :id :template :text template) "span 2"]
                                [(seesaw/button :id :choose-template :text "Choose"
                                                :listen [:action (fn [_] (choose-template))])
                                 "gap unrelated, wrap"]

                                [(seesaw/label :text "Public Folder:") "align right"]
                                [(seesaw/label :id :public :text public) "span 2"]
                                [(seesaw/button :id :choose-public :text "Choose"
                                                :listen [:action (fn [_] (choose-public-folder))])
                                 "gap unrelated, wrap"]

                                [(seesaw/button :id :inspect :text "Inspect Template Parameters" :enabled? false
                                                :listen [:action (fn [_] (inspect))])
                                 "span 4, align center"]])]

      ;; Assemble the window
      (seesaw/config! root :content panel)
      (seesaw/pack! root)
      (.setResizable root false)
      (seesaw/listen root :component-moved (fn [_] (util/save-window-position root :overlay true)))
      (reset! window root)
      (.addLifecycleListener expr/virtual-cdj vcdj-lifecycle-listener)
      (seesaw/config! (seesaw/select root [:#run]) :enabled? (online?))
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating nREPL window."))))

(defn show-window
  "Make the overlay sever window visible, creating it if necessary."
  [trigger-frame]
  (if @window
    (make-window-visible trigger-frame)
    (create-window trigger-frame)))
