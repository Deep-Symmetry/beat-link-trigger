(ns beat-link-trigger.settings-loader
  "Provides the user inerface for configuring a My Settings profile and
  sending it to players found on the network."
  (:require [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.track-loader :as track-loader]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [beat_link_trigger.util PlayerChoice]
           [java.awt.event WindowEvent]
           [org.deepsymmetry.beatlink CdjStatus DeviceAnnouncementListener DeviceFinder
            DeviceUpdateListener LifecycleListener PlayerSettings PlayerSettings$AutoCueLevel
            PlayerSettings$AutoLoadMode PlayerSettings$Illumination
            PlayerSettings$JogMode PlayerSettings$JogWheelDisplay
            PlayerSettings$LcdBrightness PlayerSettings$Language
            PlayerSettings$PadButtonBrightness PlayerSettings$PhaseMeterType PlayerSettings$PlayMode
            PlayerSettings$QuantizeMode PlayerSettings$TempoRange PlayerSettings$TimeDisplayMode
            PlayerSettings$Toggle PlayerSettings$VinylSpeedAdjust VirtualCdj]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to adjust settings
  and tell a player to load them."} loader-window
  (atom nil))

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

(defn- enum-picker
  "Creates a menu for choosing a setting based on one of the special
  enums used by `PlayerSettings`, and handles initializing it based on
  the current preferences, as well as updating the settings object and
  preferences appropriately when the user chooses a different value."
  [enum settings defaults field-name]
  (let [field         (.. settings getClass (getDeclaredField field-name))
        value-of      (.. enum (getDeclaredMethod "valueOf" (into-array Class [String])))
        display-value (.. enum (getDeclaredField "displayValue"))]

    ;; If there is a stored preference for this setting, establish it.
    (when-let [default (get defaults field-name)]
      (. field (set settings (. value-of (invoke enum (to-array [default]))))))

    ;; Create the combo box with a model that uses the specified enumeration constants,
    ;; and a custom cell renderer which draws the displayValue, rather than the persistence-oriented
    ;; toString() value, and install a state change handler which updates both the settings
    ;; object and the stored preferences values to reflect the user's wish.
    (let [box  (seesaw/combobox :model (.getEnumConstants enum)
                                :selected-item (. field (get settings))
                                :listen [:item-state-changed
                                         (fn [e]
                                           (when-let [selection (seesaw/selection e)]
                                             (. field (set settings selection))
                                             (prefs/put-preferences (update-in (prefs/get-preferences) [:my-settings]
                                                                               assoc field-name (str selection)))))])
          orig (.getRenderer box)
          draw (proxy [javax.swing.DefaultListCellRenderer] []
                 (getListCellRendererComponent [the-list value index is-selected has-focus]
                   (let [display (. display-value (get value))]
                     (.getListCellRendererComponent orig the-list display index is-selected has-focus))))]
      (.setRenderer box draw)
      box)))

(defn- create-window
  "Builds an interface in which the user can adjust their My Settings
  preferences and load them into a player."
  []
  (seesaw/invoke-later
   (try
     (let [selected-player  (atom {:number nil :playing false})
           root             (seesaw/frame :title "My Settings" :on-close :dispose :resizable? true)
           load-button      (seesaw/button :text "Load" :enabled? false)
           problem-label    (seesaw/label :text "" :foreground "red")
           update-load-ui   (fn []
                              (let [playing (:playing @selected-player)
                                    problem (if playing "Playing." "")]
                                (seesaw/value! problem-label problem)
                                (seesaw/config! load-button :enabled? (str/blank? problem))))
           player-changed   (fn [e]
                              (let [^Long number      (when-let [^PlayerChoice selection (seesaw/selection e)]
                                                        (.number selection))
                                    ^CdjStatus status (when number (.getLatestStatusFor virtual-cdj number))]
                                (reset! selected-player {:number number :playing (and status (.isPlaying status))})
                                (update-load-ui)))
           players          (seesaw/combobox :id :players
                                             :listen [:item-state-changed player-changed])
           player-panel     (mig/mig-panel :background "#ddd"
                                           :items [[(seesaw/label :text "Load on:")]
                                                   [players "gap unrelated"]
                                                   [problem-label "push, gap unrelated"]
                                                   [load-button]])
           settings         (PlayerSettings.)
           defaults         (:my-settings (prefs/get-preferences))
           settings-panel   (mig/mig-panel  ; Starts with DJ Settings
                             :items [[(seesaw/label :text "Play Mode:") "align right"]
                                     [(enum-picker PlayerSettings$PlayMode settings defaults "autoPlayMode") "wrap"]

                                     [(seesaw/label :text "Eject/Load Lock:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "ejectLoadLock") "wrap"]

                                     [(seesaw/label :text "Needle Lock:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "needleLock") "wrap"]

                                     [(seesaw/label :text "Quantize Beat Value:") "align right"]
                                     [(enum-picker PlayerSettings$QuantizeMode settings defaults "quantizeBeatValue")
                                      "wrap"]

                                     [(seesaw/label :text "Hot Cue Auto Load:") "align right"]
                                     [(enum-picker PlayerSettings$AutoLoadMode settings defaults "autoLoadMode")
                                      "wrap"]

                                     [(seesaw/label :text "Hot Cue Color:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "hotCueColor") "wrap"]

                                     [(seesaw/label :text "Auto Cue Level:") "align right"]
                                     [(enum-picker PlayerSettings$AutoCueLevel settings defaults "autoCueLevel") "wrap"]

                                     [(seesaw/label :text "Time Display Mode:") "align right"]
                                     [(enum-picker PlayerSettings$TimeDisplayMode settings defaults "timeDisplayMode")
                                      "wrap"]

                                     [(seesaw/label :text "Auto Cue:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "autoCue") "wrap"]

                                     [(seesaw/label :text "Jog Mode:") "align right"]
                                     [(enum-picker PlayerSettings$JogMode settings defaults "jogMode") "wrap"]

                                     [(seesaw/label :text "Tempo Range:") "align right"]
                                     [(enum-picker PlayerSettings$TempoRange settings defaults "tempoRange") "wrap"]

                                     [(seesaw/label :text "Master Tempo:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "masterTempo") "wrap"]

                                     [(seesaw/label :text "Quantize:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "quantize") "wrap"]

                                     [(seesaw/label :text "Sync:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "sync") "wrap"]

                                     [(seesaw/label :text "Phase Meter:") "align right"]
                                     [(enum-picker PlayerSettings$PhaseMeterType settings defaults "phaseMeterType")
                                      "wrap"]

                                     [(seesaw/label :text "Vinyl Speed Adjust:") "align right"]
                                     [(enum-picker PlayerSettings$VinylSpeedAdjust settings defaults "vinylSpeedAdjust")
                                      "wrap unrelated"]

                                     ;; Display (LCD) settings
                                     [(seesaw/label :text "Language:") "align right"]
                                     [(enum-picker PlayerSettings$Language settings defaults "language") "wrap"]

                                     [(seesaw/label :text "LCD Brightness:") "align right"]
                                     [(enum-picker PlayerSettings$LcdBrightness settings defaults "lcdBrightness")
                                      "wrap"]

                                     [(seesaw/label :text "Jog Wheel LCD Brightness:") "align right"]
                                     [(enum-picker PlayerSettings$LcdBrightness settings defaults
                                                   "jogWheelLcdBrightness") "wrap"]

                                     [(seesaw/label :text "Jog Wheel Display Mode:") "align right"]
                                     [(enum-picker PlayerSettings$JogWheelDisplay settings defaults "jogWheelDisplay")
                                      "wrap unrelated"]

                                     ;; Display (Indicator) settings
                                     [(seesaw/label :text "Slip Flashing:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "slipFlashing") "wrap"]

                                     [(seesaw/label :text "On Air Display:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "onAirDisplay") "wrap"]

                                     [(seesaw/label :text "Jog Ring Brightness:") "align right"]
                                     [(enum-picker PlayerSettings$Illumination settings defaults "jogRingIllumination")
                                      "wrap"]

                                     [(seesaw/label :text "Jog Ring Indicator:") "align right"]
                                     [(enum-picker PlayerSettings$Toggle settings defaults "jogRingIndicator") "wrap"]

                                     [(seesaw/label :text "Disc Slot Illumination:") "align right"]
                                     [(enum-picker PlayerSettings$Illumination settings defaults "discSlotIllumination")
                                      "wrap"]

                                     [(seesaw/label :text "Pad/Button Brightness:") "align right"]
                                     [(enum-picker PlayerSettings$PadButtonBrightness settings defaults
                                                   "padButtonBrightness") "wrap"]])
           layout           (seesaw/border-panel :center (seesaw/scrollable settings-panel) :south player-panel)
           stop-listener    (reify LifecycleListener
                              (started [_this _]) ; Nothing to do, we exited as soon as a stop happened anyway.
                              (stopped [_this _]  ; Close our window if VirtualCdj stops (we need it).
                                (seesaw/invoke-later
                                 (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))
           dev-listener     (reify DeviceAnnouncementListener
                              (deviceFound [_this announcement]
                                (seesaw/invoke-later (track-loader/add-device players (.getDeviceNumber announcement))))
                              (deviceLost [_this announcement]
                                (seesaw/invoke-later (track-loader/remove-device players (.getDeviceNumber announcement)
                                                                                 stop-listener))))
           status-listener  (reify DeviceUpdateListener
                              (received [_this status]
                                (let [player @selected-player]
                                  (when (and (= (.getDeviceNumber status) (:number player))
                                             (not= (.isPlaying ^CdjStatus status) (:playing player)))
                                    (swap! selected-player assoc :playing (.isPlaying ^CdjStatus status))
                                    (update-load-ui)))))
           remove-listeners (fn []
                              (.removeLifecycleListener virtual-cdj stop-listener)
                              (.removeDeviceAnnouncementListener device-finder dev-listener)
                              (.removeUpdateListener virtual-cdj status-listener))]
       (.addDeviceAnnouncementListener device-finder dev-listener)
       (.addUpdateListener virtual-cdj status-listener)
       (track-loader/build-device-choices players)
       (reset! loader-window root)
       (.addLifecycleListener virtual-cdj stop-listener)
       (seesaw/listen root :window-closed (fn [_]
                                            (reset! loader-window nil)
                                            (remove-listeners)))
       (seesaw/listen load-button
                      :action-performed
                      (fn [_]
                        (let [^Long selected-player (.number ^PlayerChoice (.getSelectedItem players))]
                          (.sendLoadSettingsCommand virtual-cdj selected-player settings))))

       (when-not (.isRunning virtual-cdj)  ; In case it shut down during our setup.
         (when @loader-window (.stopped stop-listener virtual-cdj)))  ; Give up unless we already did.
       (if @loader-window
         (do  ; We made it! Show the window.
           (seesaw/config! root :content layout)
           (seesaw/pack! root)
           (.setLocationRelativeTo root nil)  ; TODO: Save/restore this window position?
           root)
         (do  ; Something failed, clean up.
           (remove-listeners)
           (.dispose root))))
     (catch Exception e
       (timbre/error e "Problem Loading Settings")
       (seesaw/alert (str "<html>Unable to Load Settings on Player:<br><br>" (.getMessage e)
                              "<br><br>See the log file for more details.")
                         :title "Problem Loading Settings" :type :error)))))

(defn show-dialog
  "Displays an interface in whcih the user can adjust their preferred
  player settings and load them into players."
  []
  (seesaw/invoke-later
   (locking loader-window
     (when-not @loader-window (create-window))
     (seesaw/invoke-later
      (when-let [window @loader-window]
        (seesaw/show! window)
        (.toFront window))))))
