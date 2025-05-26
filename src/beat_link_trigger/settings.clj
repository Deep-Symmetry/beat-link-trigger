(ns beat-link-trigger.settings
  "Provides the user interface for configuring some Beat Link Trigger
  preferences."
  (:require [beat-link-trigger.carabiner :as carabiner]
            [beat-link-trigger.overlay :as overlay]
            [beat-link-trigger.players :as players]
            [beat-link-trigger.playlist-writer :as playlist]
            [beat-link-trigger.prefs :as prefs]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.awt.event ItemEvent]
           [java.io File]
           [javax.swing JFrame]
           [org.deepsymmetry.beatlink.data WaveformFinder WaveformFinder$WaveformStyle]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to adjust preferences."}
  settings-window
  (atom nil))

(def ^WaveformFinder waveform-finder
  "A convenient reference to the [Beat Link
  `WaveformFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/WaveformFinder.html)
  singleton."
  (WaveformFinder/getInstance))

(def wave-names
  "A map from the supported waveform styles to their descriptive
  names."
  {WaveformFinder$WaveformStyle/RGB        "RGB"
   WaveformFinder$WaveformStyle/THREE_BAND "3-Band"
   WaveformFinder$WaveformStyle/BLUE       "Blue"})

(def wave-styles
  "A map from descriptive name to the corresponding waveform style."
  (set/map-invert wave-names))

(defn- can-write-playlist?
  "Checks whether a playlist can be automatically written given the
  current settings configuration."
  ([]
   (can-write-playlist? (prefs/get-preferences)))
  ([preferences]
   (when-let [folder (:playlist-folder preferences)]
     (let [^File file (io/file folder)
           prefix     (:playlist-prefix preferences "Playlist")]
       (and (.exists file)
            (.isDirectory file)
            (.canWrite file)
            (not (str/blank? prefix)))))))

(defn- choose-playlist-folder
  "Prompt the user to choose a folder for automatically writing
  playlists."
  [root]
  (let [folder (chooser/choose-file root :selection-mode :dirs-only
                                    :type "Choose Playlist Writer Folder"
                                    :remember-directory? false)]
    (when folder
      (prefs/put-preferences
       (-> (prefs/get-preferences)
           (assoc :playlist-folder (.getCanonicalPath folder))))
      (seesaw/text! (seesaw/select root [:#folder]) (.getCanonicalPath folder))
      (seesaw/config! (seesaw/select root [:#write]) :enabled? (can-write-playlist?)))))

(defn- create-window
  "Builds an interface in which the user can adjust preferences."
  []
  (seesaw/invoke-later
    (let [^JFrame root   (seesaw/frame :title "Beat Link Trigger Settings" :on-close :dispose :resizable? true)
          defaults       (prefs/get-preferences)
          syncable       (fn [mode] (#{"Passive" "Full"} mode)) ; Is this mode one the user can sync to Ableton?
          settings-panel (mig/mig-panel
                          :items [[(seesaw/label :text "User Interface Theme:") "align right"]
                                  [(seesaw/combobox :id :theme
                                                    :model (sort (keys prefs/ui-themes))
                                                    :selected-item (prefs/ui-names
                                                                    (:ui-theme defaults :flatlaf-darcula))
                                                    :listen [:item-state-changed
                                                             (fn [^ItemEvent e]
                                                               (when (= (.getStateChange e) ItemEvent/SELECTED)
                                                                 (prefs/put-preferences
                                                                  (-> (prefs/get-preferences)
                                                                      (assoc :ui-theme
                                                                             (prefs/ui-themes (seesaw/value e)))))
                                                                 (prefs/set-ui-theme)))])
                                   "wrap"]
                                  [(seesaw/label :text "User Interface Mode:") "align right"]
                                  [(seesaw/combobox :model ["Light" "Dark" "System"]
                                                    :selected-item (str/capitalize (name (:ui-mode defaults :dark)))
                                                    :listen [:item-state-changed
                                                             (fn [^ItemEvent e]
                                                               (when (= (.getStateChange e) ItemEvent/SELECTED)
                                                                 (prefs/put-preferences
                                                                  (-> (prefs/get-preferences)
                                                                      (assoc :ui-mode
                                                                             (keyword
                                                                              (str/lower-case (seesaw/value e))))))
                                                                 (prefs/set-ui-theme)))])
                                   "wrap unrelated"]
                                  [(seesaw/label :text "Waveform Style:") "align right"]
                                  [(seesaw/combobox :id :waveform-style
                                                    :model ["RGB" "3-Band" "Blue"]
                                                    :selected-item (:waveform-style defaults "RGB")
                                                    :listen [:item-state-changed
                                                             (fn [^ItemEvent e]
                                                               (when (= (.getStateChange e) ItemEvent/SELECTED)
                                                                 (let [style (get wave-styles (seesaw/value e))]
                                                                   (prefs/put-preferences
                                                                    (-> (prefs/get-preferences)
                                                                        (assoc :waveform-style (seesaw/value e))))
                                                                   (.setPreferredStyle waveform-finder style))))])
                                   "wrap 24"]

                                  ["When Going Online:" "span, align center, wrap unrelated"]

                                  [""]
                                  [(seesaw/checkbox :id :player-status :text "Show Player Status?"
                                                    :selected? (:show-player-status defaults)
                                                    :listen [:action (fn [e]
                                                                       (prefs/put-preferences
                                                                        (-> (prefs/get-preferences)
                                                                            (assoc :show-player-status
                                                                                   (seesaw/value e)))))])
                                   "wrap 18"]

                                  [(seesaw/label :text "Carabiner Sync Mode:") "align right"]
                                  [(seesaw/combobox :id :carabiner-startup-mode
                                                    :model ["Off" "Passive" "Triggers" "Full"]
                                                    :selected-item (:carabiner-startup-mode defaults "Off")
                                                    :listen [:item-state-changed
                                                             (fn [^ItemEvent e]
                                                               (when (= (.getStateChange e) ItemEvent/SELECTED)
                                                                 (let [mode (seesaw/value e)]
                                                                   (prefs/put-preferences
                                                                    (-> (prefs/get-preferences)
                                                                        (assoc :carabiner-startup-mode mode)))
                                                                   (seesaw/config! [(seesaw/select root [:#sync-link])
                                                                                    (seesaw/select root [:#bar])]
                                                                                   :enabled? (syncable mode))
                                                                   (when (and (= mode "Full")
                                                                              (not (carabiner/sending-status?)))
                                                                     (carabiner/report-status-requirement root)))))])
                                   "wrap"]

                                  [""]
                                  [(seesaw/checkbox :id :sync-link :text "Sync Ableton Link?"
                                                    :selected? (:carabiner-sync-link defaults)
                                                    :enabled? (syncable (:carabiner-startup-mode defaults))
                                                    :listen [:action (fn [e]
                                                                       (prefs/put-preferences
                                                                        (-> (prefs/get-preferences)
                                                                            (assoc :carabiner-sync-link
                                                                                   (seesaw/value e)))))])
                                   "wrap"]

                                  [""]
                                  [(seesaw/checkbox :id :bar :text "Align at bar level?"
                                                    :selected? (:carabiner-align-bar defaults)
                                                    :enabled? (syncable (:carabiner-startup-mode defaults))
                                                    :listen [:action (fn [e]
                                                                       (prefs/put-preferences
                                                                        (-> (prefs/get-preferences)
                                                                            (assoc :carabiner-align-bar
                                                                                   (seesaw/value e)))))])
                                   "wrap 18"]

                                  [(seesaw/label :text "Playlists:") "align right"]
                                  [(seesaw/checkbox :id :write :text "Write automatically?"
                                                    :selected? (:playlist-write? defaults)
                                                    :enabled? (can-write-playlist? defaults)
                                                    :listen [:action (fn [e]
                                                                       (prefs/put-preferences
                                                                        (-> (prefs/get-preferences)
                                                                            (assoc :playlist-write?
                                                                                   (seesaw/value e)))))])
                                   "wrap"]

                                  [(seesaw/label :text "Prefix:") "align right"]
                                  [(seesaw/text :id :prefix :text (:playlist-prefix defaults "Playlist") :columns 16
                                                :listen [:document (fn [e]
                                                                     (prefs/put-preferences
                                                                      (-> (prefs/get-preferences)
                                                                          (assoc :playlist-prefix (seesaw/text e))))
                                                                     (seesaw/config! (seesaw/select root [:#write])
                                                                                     :enabled? (can-write-playlist?)))])
                                   "wrap"]

                                  [""]
                                  [(seesaw/checkbox :text "Append to existing file?"
                                                    :selected? (:playlist-append? defaults)
                                                    :listen [:action (fn [e]
                                                                       (prefs/put-preferences
                                                                        (-> (prefs/get-preferences)
                                                                            (assoc :playlist-append?
                                                                                   (seesaw/value e)))))])
                                   "wrap"]

                                  [(seesaw/label :text "Folder:") "align right"]
                                  [(seesaw/label :id :folder :text (:playlist-folder defaults))
                                   "wrap"]

                                  [""]
                                  [(seesaw/button :text "Choose"
                                                  :listen [:action-performed (fn [_]
                                                                               (choose-playlist-folder root))])
                                   "wrap 18"]

                                  [(seesaw/label :text "OBS Overlays:") "align right"]
                                  [(seesaw/checkbox :id :run :text "Run web server?"
                                                    :selected? (:run-obs-overlay defaults)
                                                    :listen [:action (fn [e]
                                                                       (prefs/put-preferences
                                                                        (-> (prefs/get-preferences)
                                                                            (assoc :run-obs-overlay
                                                                                   (seesaw/value e)))))])
                                   "wrap"]])]
      (prefs/register-ui-frame root)
      (reset! settings-window root)
      (seesaw/listen root :window-closed (fn [_]
                                           (reset! settings-window nil)
                                           (prefs/unregister-ui-frame root)))
      (seesaw/config! root :content settings-panel)
      (seesaw/pack! root)
      (.setResizable root false)
      (.setLocationRelativeTo root nil) ; TODO: Save/restore this window position?
      root)))

(defn show-dialog
  "Displays an interface in whcih the user can adjust preferences."
  []
  (seesaw/invoke-later
   (locking settings-window
     (when-not @settings-window (create-window))
     (seesaw/invoke-later
      (when-let [^JFrame window @settings-window]
        (seesaw/show! window)
        (.toFront window))))))

(defn sanitize-startup-mode
  "Called to make sure if the settings want us to enter full sync mode,
  we are using a real player number. If not, display an explanatory
  dialog and return Passive mode instead."
  [mode]
  (if (or (carabiner/sending-status?) (not= :full mode))
    mode
    (do
      (carabiner/report-status-requirement nil)
      :passive)))

(defn run-online-actions
  "Performs the actions the user has specified should happen when BLT
  goes online."
  [trigger-frame expression-globals]
  (let [preferences (prefs/get-preferences)]
    (when (:show-player-status preferences)
      (players/show-window trigger-frame expression-globals))

    (let [mode (-> (:carabiner-startup-mode preferences "Off")
                   str/lower-case
                   keyword
                   sanitize-startup-mode)]
      (when (not= :off mode)
        (let [carabiner-frame (carabiner/show-window nil)]
          (carabiner/connect)
          (try
            (carabiner/sync-mode mode)
            (when (#{:passive :full} mode)
              (carabiner/sync-link (:carabiner-sync-link preferences))
              (carabiner/align-bars (:carabiner-align-bar preferences)))
            (catch Exception e
              (timbre/error e "Carabiner sync from preferences failed.")
              (seesaw/alert carabiner-frame (str "Unable to enter Carabiner sync mode chosen in Settings.\n"
                                                 "Please see the logs for more details..")
                            :title "Carabiner Sync Failed" :type :error))))))

    (when (:playlist-write? preferences)
      (if (can-write-playlist? preferences)
        (playlist/write-playlist (:playlist-folder preferences) (:playlist-prefix preferences)
                                 (:playlist-append? preferences))
        (do (prefs/put-preferences
             (-> (prefs/get-preferences)
                 (dissoc :playlist-write?)))
            (when-let [root @settings-window]
              (seesaw/config! (seesaw/select root [:#write]) :selected? false))
            (seesaw/alert trigger-frame (str "The settings say to write a playlist, but it can’t be written.\n"
                                             "That setting is now turned off until you fix the configuration.")
                          :title "Automatic Playlist Misconfiguration" :type :error))))

    (when (:run-obs-overlay preferences)
      (overlay/run-server))))
