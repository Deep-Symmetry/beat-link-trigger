#_{:clj-kondo/ignore [:unused-namespace :unused-refer :unused-import :refer-all]}
(ns beat-link-trigger.expressions.triggers
  "The namespace into which user-entered custom expressions for the
  Triggers window will be compiled, which provides support for making
  them easier to write. We require, refer, and import a lot of things
  not used in this file, simply for the convenience of expressions
  that may be created by users later."
  (:require [clojure.repl :refer :all]
            [clojure.set]
            [clojure.string :as str]
            [beat-carabiner.core :as beat-carabiner]
            [beat-link-trigger.carabiner :as carabiner]
            [beat-link-trigger.help :as help]
            [beat-link-trigger.overlay :as overlay]
            [beat-link-trigger.players :as players]
            [beat-link-trigger.playlist-writer :as playlist-writer]
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.show :as show]
            [beat-link-trigger.show-util :as su]
            [beat-link-trigger.simulator :as sim]
            [beat-link-trigger.socket-picker :as socket-picker]
            [beat-link-trigger.triggers :as triggers]
            [beat-link-trigger.util :as util]
            [cemerick.pomegranate :as pomegranate]
            [cemerick.pomegranate.aether :as aether]
            [http.async.client :as http]
            [overtone.midi :as midi]
            [overtone.osc :as osc]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Util
            DeviceAnnouncement DeviceUpdate Beat CdjStatus MixerStatus MediaDetails
            CdjStatus$TrackSourceSlot CdjStatus$TrackType]
           [org.deepsymmetry.beatlink.data BeatGrid MetadataFinder SignatureFinder TimeFinder
            PlaybackState TrackPositionUpdate SlotReference TrackMetadata AlbumArt]
           [java.awt Color]
           [java.net InetAddress InetSocketAddress DatagramPacket DatagramSocket]))



;;; These first definitions are intended for use by user expressions and shared functions:

(defonce ^{:doc "Provides a space for trigger expressions to store
  values they want to share across triggers. Visible to other
  namespaces so that, for example, Show expressions can access them."}
  globals (atom {}))

(def default-repositories
  "Have our add-dependencies function default to searching Clojars as
  well as Maven Central."
  (merge aether/maven-central
         {"clojars" "https://clojars.org/repo"}))

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

(def ^org.deepsymmetry.beatlink.data.ArtFinder art-finder
  "A convenient reference to the [Beat Link
  `ArtFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/ArtFinder.html)
  singleton."
  (org.deepsymmetry.beatlink.data.ArtFinder/getInstance))

(def ^org.deepsymmetry.beatlink.data.BeatGridFinder beatgrid-finder
  "A convenient reference to the [Beat Link
  `BeatGridFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/BeatGridFinder.html)
  singleton."
  (org.deepsymmetry.beatlink.data.BeatGridFinder/getInstance))

(def ^SignatureFinder signature-finder
  "A convenient reference to the [Beat Link
  `SingatureFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/SignatureFinder.html)
  singleton."
  (SignatureFinder/getInstance))

(def ^TimeFinder time-finder
  "A convenient reference to the [Beat Link
  `TimeFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TimeFinder.html)
  singleton."
  (TimeFinder/getInstance))

(def ^org.deepsymmetry.beatlink.data.WaveformFinder waveform-finder
  "A convenient reference to the [Beat Link
  `WaveformFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/WaveformFinder.html)
  singleton."
  (org.deepsymmetry.beatlink.data.WaveformFinder/getInstance))

(defn extract-device-update
  "Allow expressions which might receive either a simple device update
  or a beat-tpu vector to find the device update in either."
  ^DeviceUpdate [status]
  (if (instance? DeviceUpdate status)
    status
    (first status)))

(defn add-library
  "Allow expression code to add a new Maven dependency at runtime by
  specifying its coordinate, and optionally the repositories to
  search."
  [coordinate & {:keys [repositories] :or {repositories default-repositories}}]
  (pomegranate/add-dependencies :coordinates [coordinate] :repositories repositories))

(defn add-libraries
  "Allow expression code to add multiple new Maven dependency at runtime
  by specifying their coordinates, and optionally the repositories to
  search."
  [coordinates & {:keys [repositories] :or {repositories default-repositories}}]
  (pomegranate/add-dependencies :coordinates coordinates :repositories repositories))

(defn extend-classpath
  "Allow expression code to add a local Jar file or directory to the
  classpath at runtime."
  [jar-or-dir]
  (pomegranate/add-classpath jar-or-dir))

(defn track-source-slot
  "Converts the Java enum value representing the slot from which a track
  was loaded to a more convenient Clojure keyword."
  [^CdjStatus status]
  (util/case-enum (.getTrackSourceSlot status)
    CdjStatus$TrackSourceSlot/NO_TRACK   :no-track
    CdjStatus$TrackSourceSlot/CD_SLOT    :cd-slot
    CdjStatus$TrackSourceSlot/SD_SLOT    :sd-slot
    CdjStatus$TrackSourceSlot/USB_SLOT   :usb-slot
    CdjStatus$TrackSourceSlot/COLLECTION :collection
    :unknown))

(defn track-type
  "Converts the Java enum value representing the type of track that
  was loaded to a more convenient Clojure keyword."
  [^CdjStatus status]
  (util/case-enum (.getTrackType status)
    CdjStatus$TrackType/NO_TRACK         :no-track
    CdjStatus$TrackType/CD_DIGITAL_AUDIO :cd-digital-audio
    CdjStatus$TrackType/REKORDBOX        :rekordbox
    CdjStatus$TrackType/UNANALYZED       :unanalyzed
    :unknown))

(defn playback-time
  "Obtains the current playback time of the player that sent the
  supplied device update, or `nil` if we don't know."
  [^DeviceUpdate device-update]
  (if-let [data util/*simulating*]
    (:time data)
    (when (.isRunning time-finder)
      (let [result (.getTimeFor time-finder device-update)]
        (when-not (neg? result)
          result)))))

(defn current-beat
  "Obtains the current beat number of the player that sent the
  supplied status, or `nil` if we don't know."
  [status]
  (cond
    (instance? DeviceUpdate status)
    (if-let [data util/*simulating*]
      (:beat data)
      (when (.isRunning time-finder)
        (let [^DeviceUpdate device-update status
              position                    (.getLatestPositionFor time-finder device-update)]
          (when position
            (.-beatNumber position)))))

    (vector? status)  ; A beat-tpu tuple.
    (.beatNumber ^TrackPositionUpdate (second status))))

(defn current-bar
  "Obtains the current bar number of the player that sent the
  supplied status object, or `nil` if we don't know."
  [status]
  (when-let [beat (current-beat status)]
    (if-let [data util/*simulating*]
      (when-let [^BeatGrid grid (:grid data)]
        (.getBarNumber grid beat))
      (when-let [grid (.getLatestBeatGridFor beatgrid-finder (extract-device-update status))]
        (.getBarNumber grid beat)))))

(defn extract-device-number
  "Obtains the device number that is responsible for the current
  expression running."
  [status]
  (cond
    (instance? DeviceUpdate status)
    (DeviceUpdate/.getDeviceNumber status)

    (vector? status)  ; A beat-tpu tuple
    (Beat/.getDeviceNumber (first status))))

(def extract-raw-cue-update
  "Given a status value from a show cue's started-on-beat or
  started-late expression, returns the raw device update object
  associated with it, which will be the first element of a tuple in
  the case of the started-on-beat expression. This is redundant with
  extract-device-update, but was published in the Break Buddy
  integration example, so is kept for backwards compatibility."
  extract-device-update)

(defn set-overlay-background-color
  "Sets the color that will be used to draw the background in the
  waveform views served by the OBS overlay server, can be changed to
  support different template designs."
  [color]
  ((requiring-resolve 'beat-link-trigger.overlay/set-wave-background-color) color))

(defn set-overlay-indicator-color
  "Sets the color that will be used to draw tick marks and paused
  playback indicators in waveform views served by the OBS overlay
  server, can be changed to support different template designs."
  [color]
  ((requiring-resolve 'beat-link-trigger.overlay/set-wave-indicator-color) color))

(defn set-overlay-emphasis-color
  "Sets the color that will be used to draw tick marks and paused
  playback indicators in waveform views served by the OBS overlay
  server, can be changed to support different template designs."
  [color]
  ((requiring-resolve 'beat-link-trigger.overlay/set-wave-emphasis-color) color))

(defn register-cue-builder
  "Registers a function that can be selected to customize cues when they
  are being added from a show's cue library. Must be passed the show,
  a non-empty name by which the function can be chosen, and the
  function itself.

  The cue bilder will be called with the show, the context (track or
  phrase trigger) in which the cue is being placed, the runtime-info
  for that context, and the raw cue as it came out of the library. It
  should return an appropriately modified version of the cue, or `nil`
  to cancel the placement of the library cue."
  [show builder-name builder]
  (when (str/blank? builder-name)
    (throw (Exception. "builder-name cannot be empty")))
  (su/swap-show! show assoc-in [:cue-builders (str/trim builder-name)] builder))

(defn unregister-cue-builder
  "Removes the function, if any, registered in the show as a cue builder
  under the specified name. This function is probably unnecessary,
  because editing the Global Setup Expression, where cue builders are
  registers, starts out with a clean slate each time."
  [show builder-name]
  (when-not (str/blank? builder-name)
    (su/swap-show! show update :cue-builders dissoc (str/trim builder-name))))

(defn replace-artist-line
  "Allows customization of what is displayed in the artist line of the
  player status window. Must be supplied a function that takes two
  arguments, the track metadata and the player number, and returns the
  string to be displayed."
  [f]
  (reset! @(requiring-resolve 'beat-link-trigger.players/artist-label-fn) f))
