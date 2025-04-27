(ns beat-link-trigger.settings
  "Provides the user interface for configuring some Beat Link Trigger
  preferences."
  (:require [beat-link-trigger.prefs :as prefs]
            [clojure.set :as set]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig])
  (:import [java.awt.event ItemEvent]
           [javax.swing JComboBox JFrame]
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
                                   "wrap"]])]
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
