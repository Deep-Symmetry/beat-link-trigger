(ns beat-link-trigger.settings
  "Provides the user interface for configuring some Beat Link Trigger
  preferences."
  (:require [beat-link-trigger.carabiner :as carabiner]
            [beat-link-trigger.overlay :as overlay]
            [beat-link-trigger.players :as players]
            [beat-link-trigger.prefs :as prefs]
            [clojure.set :as set]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.awt.event ItemEvent]
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

(defn- create-window
  "Builds an interface in which the user can adjust preferences."
  []
  (seesaw/invoke-later
   (let [^JFrame root   (seesaw/frame :title "Beat Link Trigger Settings" :on-close :dispose :resizable? true)
         defaults       (prefs/get-preferences)
         syncable       (fn [mode] (#{"Passive" "Full"} mode))  ; Is this mode one the user can sync to Ableton?
         settings-panel (mig/mig-panel
                         :items [[(seesaw/label :text "Waveform Style:") "align right"]
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
                                  "wrap 20"]

                                 ["When Going Online:" "span, align center, wrap unrelated"]

                                 [""]
                                 [(seesaw/checkbox :id :player-status :text "Show Player Status?"
                                                   :selected? (:show-player-status defaults)
                                                   :listen [:action (fn [e]
                                                                      (prefs/put-preferences
                                                                       (-> (prefs/get-preferences)
                                                                           (assoc :show-player-status
                                                                                  (seesaw/value e)))))])
                                  "wrap unrelated"]

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
                                  "wrap unrelated"]

                                 [(seesaw/label :text "OBS Overlays:") "align right"]
                                 [(seesaw/checkbox :id :run :text "Run web server?"
                                                   :selected? (:run-obs-overlay defaults)
                                                   :listen [:action (fn [e]
                                                                      (prefs/put-preferences
                                                                       (-> (prefs/get-preferences)
                                                                           (assoc :run-obs-overlay
                                                                                  (seesaw/value e)))))])
                                  "wrap unrelated"]])]
      (reset! settings-window root)
      (seesaw/listen root :window-closed (fn [_] (reset! settings-window nil)))
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

    (when (:run-obs-overlay preferences)
      (overlay/run-server))))
