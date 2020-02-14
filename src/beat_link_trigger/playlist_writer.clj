(ns beat-link-trigger.playlist-writer
  "A window that facilitates the creation of comma-separated-value lists
  reporting tracks played over a particular period of time."
  (:require [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.util :as util]
            [clojure.data.csv :as csv]
            [clojure.java.io]
            [seesaw.chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import java.awt.event.WindowEvent
           java.util.concurrent.TimeUnit
           [javax.swing JFrame]
           [org.deepsymmetry.beatlink CdjStatus CdjStatus$TrackType CdjStatus$TrackSourceSlot
            DeviceUpdateListener LifecycleListener VirtualCdj]
           [org.deepsymmetry.beatlink.data MetadataFinder TrackMetadata SearchableItem]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to write playlist files."}
  writer-window (atom nil))

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

(def min-time-pref-key
  "The key used to store the minimum time a track must be playing in
  order to get written to the playlist in the global preferences."
  :playlist-min-play-seconds)

(def on-air-pref-key
  "The key used to store the global preference that controls whether a
  player must be On-Air for it to contribute to a playlist."
  :playlist-on-air-only)

(defn- make-window-visible
  "Ensures that the playlist writer window is in front, and shown."
  [parent]
  (let [^JFrame our-frame @writer-window]
    (util/restore-window-position our-frame :playlist-writer parent)
    (seesaw/show! our-frame)
    (.toFront our-frame)))

(def ^:private idle-status
  "The status to display when we are not recording a playlist."
  "Idle (not writing a playlist)")

(defn- format-searchable-item
  "Safely translates a (possibly-mising) SearchableItem value into
  either its name or an empty string."
  [^SearchableItem item]
  (str (when item (.label item))))

(defn- format-metadata
  "Given a track list entry structure, extracts the title, artist, and
  album if metadata is available, or explains why not as best as
  possible."
  [entry]
  (let [^CdjStatus status       (:cdj-status entry)
        ^TrackMetadata metadata (:metadata entry)]
    (if metadata
      [(.getTitle metadata)
       (format-searchable-item (.getArtist metadata))
       (format-searchable-item (.getAlbum metadata))]

      ;; We have no metadata, so describe the track as best we can.
      (util/case-enum (.getTrackType status)

        CdjStatus$TrackType/CD_DIGITAL_AUDIO
        [(str "Unknown (Audio CD track " (.getTrackNumber status) ")") "" ""]

        CdjStatus$TrackType/UNANALYZED
        ["Unknown (non-rekordbox)" "" ""]

        CdjStatus$TrackType/REKORDBOX
        ["Unknown (no metadata found)" "" ""]

        ["Unknown (unknown track type)" "" ""]))))

(defn- format-source
  "Given a track list entry structure, provides any available
  information about the player from which the track was loaded."
  [entry]
  (let [^CdjStatus status (:cdj-status entry)]
    (if (#{CdjStatus$TrackType/REKORDBOX CdjStatus$TrackType/UNANALYZED CdjStatus$TrackType/CD_DIGITAL_AUDIO}
         (.getTrackType status))
      (str "Player " (.getTrackSourcePlayer status)
           (util/case-enum (.getTrackSourceSlot status)

             CdjStatus$TrackSourceSlot/SD_SLOT
             " SD"

             CdjStatus$TrackSourceSlot/USB_SLOT
             " USB"

             CdjStatus$TrackSourceSlot/CD_SLOT
             " CD"

             CdjStatus$TrackSourceSlot/COLLECTION
             " rekordbox"

             " ?"))
      "")))

(defn- build-toggle-handler
  "Creates an event handler for the Start/Stop button."
  [button status file-atom stop-handler frame]
  (fn [_]
    (if @file-atom
      (do  ; Time to stop writing a playlist
        (stop-handler)
        (seesaw/config! button :text "Start")
        (seesaw/config! status :text idle-status)
        (reset! file-atom nil))
      (let [extension (util/extension-for-file-type :playlist)]
        (when-let [file (util/confirm-overwrite-file
                         (seesaw.chooser/choose-file frame :type :save
                                                     :filters [["Playlist CSV files" [extension]]]
                                                     :all-files? false)
                         extension
                         frame)]
          (try
            (with-open [writer (clojure.java.io/writer file)]
              (csv/write-csv writer [["Title" "Artist" "Album" "Player" "Source" "Started" "Stopped" "Play Time"]]))
            (reset! file-atom file)
            (seesaw/config! button :text "Stop")
            (seesaw/config! status :text (str "Writing to " (.getName file)))
            (catch Throwable t
              (timbre/error t "Problem creating playlist file" file))))))))

(defn- format-play-time
  "Formats the number of seconds a track has been playing as minutes:seconds"
  [seconds]
  (format "%02d:%02d" (long (/ seconds 60)) (mod seconds 60)))

(defn- write-entry-if-played-enough
  "If the supplied entry exists, represents a track that thas been
  playing for long enough to count, write out an line in the playlist
  describing it."
  [min-play-seconds playlist-file player-number entry]
  (when entry
    (let [now (System/currentTimeMillis)
          played (.toSeconds TimeUnit/MILLISECONDS (- now (:started entry)))]
      (when (and (>= played min-play-seconds)
                 playlist-file)
        (let [[title artist album] (format-metadata entry)]
          (try
            (with-open [writer (clojure.java.io/writer playlist-file :append true)]
              (csv/write-csv writer [[title artist album player-number (format-source entry)
                                      (str (java.util.Date. ^Long (:started entry))) (str (java.util.Date. now))
                                      (format-play-time played)]]))
            (catch Throwable t
              (timbre/error t "Problem adding entry to playlist file" playlist-file))))))))

(defn- track-changed?
  "Checks whether a device update indicates that a different track is
  playing than the one we have been timing."
  [^CdjStatus new-status entry]
  (when entry
    (let [^CdjStatus old-status (:cdj-status entry)]
      (or (not= (.getTrackType old-status) (.getTrackType new-status))
          (not= (.getTrackNumber old-status) (.getTrackNumber new-status))
          (not= (.getTrackSourcePlayer old-status) (.getTrackSourcePlayer new-status))
          (not= (.getTrackSourceSlot old-status) (.getTrackSourceSlot new-status))
          (not= (.getRekordboxId old-status) (.getRekordboxId new-status))))))

(defn- build-update-listener
  "Creates the update listener which keeps track of all playing tracks
  and writes playlist entries at the appropriate time. Returns a tuple
  consisting of that listener and a function that can be called to
  write out playlist entries for any tracks that have currently been
  playing long enough because the playlist file is being closed."
  [time-spinner on-air-checkbox file-atom]
  (let [playing-tracks (atom {})]
    [(reify DeviceUpdateListener
        (received [this device-update]
          (when (instance? CdjStatus device-update)
            (let [now              (System/currentTimeMillis)
                  cdj-status       ^CdjStatus device-update
                  player-number    (.getDeviceNumber cdj-status)
                  min-play-seconds (seesaw/value time-spinner)
                  on-air-required? (seesaw/value on-air-checkbox)
                  playlist-file    @file-atom]
              (swap! playing-tracks update player-number
                     (fn [old-entry]
                       (if (and
                            (.isPlaying cdj-status)
                            (or (.isOnAir cdj-status) (not on-air-required?)))
                         (if (track-changed? cdj-status old-entry)
                           (do  ; Write out old entry if it had played enough, and swap in our new one.
                             (write-entry-if-played-enough min-play-seconds playlist-file player-number old-entry)
                             {:started now
                              :cdj-status cdj-status})
                           (if old-entry
                             (if (or (:metadata old-entry) (not (.isRunning metadata-finder)))
                               old-entry  ; We are still playing a track we already have metadata for (or unavailable).
                               (assoc old-entry :metadata (.getLatestMetadataFor metadata-finder player-number)))
                             {:started now  ; We have a new entry, there was nothing there before.
                              :cdj-status cdj-status}))
                         (do  ; Not playing, so clear any existing entry, but write it out if it had played enough.
                           (when old-entry
                             (write-entry-if-played-enough min-play-seconds playlist-file player-number old-entry))
                           nil))))))))
     (fn [] ; Closing the playlist, write out any entries that deserve it.
       (let [min-play-seconds (seesaw/value time-spinner)
             playlist-file    @file-atom]
         (when playlist-file
           (doseq [[player-number entry] @playing-tracks]
             (write-entry-if-played-enough min-play-seconds playlist-file player-number entry)))))]))

(defn- min-time-pref
  "Retrieve the preferred minimum time a track must be playing in order
  to get written to the playlist."
  []
  (or (when-let [pref (min-time-pref-key (prefs/get-preferences))]
        (try
          (Long/parseUnsignedLong pref)
          (catch Exception e
            (timbre/error e "Problem parsing playlist minimum play time preference value:" pref))))
      10))

(defn- on-air-pref
  "Retrieve the preference setting for filtering out players that are
  not On-Air."
  []
  (or (when-let [pref (on-air-pref-key (prefs/get-preferences))]
        (try
          (Boolean/parseBoolean pref)
          (catch Exception e
            (timbre/error e "Problem parsing playlist on-air preference value:" pref))))
      false))

(defn- create-window
  "Creates the playlist writer window."
  []
  (try
    (let [playlist-file   (atom nil)
          time-spinner    (seesaw/spinner :id :time :model (seesaw/spinner-model (min-time-pref) :from 0 :to 60))
          on-air-checkbox (seesaw/checkbox :id :on-air :selected? (on-air-pref))
          toggle-button   (seesaw/button :id :start :text "Start")
          status-label    (seesaw/label :id :status :text idle-status)
          panel           (mig/mig-panel
                           :background "#ccc"
                           :items [[(seesaw/label :text "Minimum Play Time:") "align right"]
                                   [time-spinner]
                                   [(seesaw/label :text "seconds") "align left, wrap"]

                                   [(seesaw/label :text "On-Air Players Only?") "align right"]
                                   [on-air-checkbox]
                                   [(seesaw/label :text "") "wrap"]

                                   [(seesaw/label :text "Status:") "align right"]
                                   [status-label "span, grow, wrap 15"]

                                   [(seesaw/label :text "")]
                                   [toggle-button "span 2"]])
          ^JFrame root    (seesaw/frame :title "Playlist Writer"
                                        :content panel
                                        :on-close :dispose)
          [update-listener
           close-handler] (build-update-listener time-spinner on-air-checkbox playlist-file)
          stop-listener   (reify LifecycleListener
                            (started [this sender])  ; Nothing for us to do, we exited as soon a stop happened anyway.
                            (stopped [this sender]  ; Close our window if VirtualCdj gets shut down (we went offline).
                              (seesaw/invoke-later
                               (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))]
      (.addUpdateListener virtual-cdj update-listener)
      (.addLifecycleListener virtual-cdj stop-listener)
      (seesaw/listen root
                     :window-closed (fn [_]
                                      (.removeUpdateListener virtual-cdj update-listener)
                                      (.removeLifecycleListener virtual-cdj stop-listener)
                                      (close-handler)
                                      (reset! writer-window nil)
                                      (prefs/put-preferences
                                       (assoc (prefs/get-preferences)
                                              min-time-pref-key (seesaw/value time-spinner)
                                              on-air-pref-key (seesaw/value on-air-checkbox))))
                     :component-moved (fn [_] (util/save-window-position root :playlist-writer true)))
      (seesaw/listen toggle-button :action (build-toggle-handler toggle-button status-label playlist-file
                                                                 close-handler root))
      (seesaw/pack! root)
      #_(.setResizable root false)
      (reset! writer-window root)
      (when-not (.isRunning virtual-cdj) (.stopped stop-listener virtual-cdj)))  ; In case we went offline during setup.
    (catch Exception e
      (timbre/error e "Problem creating Playlist Writer window."))))

(defn show-window
  "Make the Playlist Writer window visible, creating it if necessary."
  [trigger-frame]
  (locking writer-window
    (when-not @writer-window (create-window)))
  (make-window-visible trigger-frame))
