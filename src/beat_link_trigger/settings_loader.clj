(ns beat-link-trigger.settings-loader
  "Provides the user inerface for configuring a My Settings profile and
  sending it to players found on the network."
  (:require [beat-link-trigger.track-loader :as track-loader]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [beat_link_trigger.util PlayerChoice]
           [java.awt.event WindowEvent]
           [org.deepsymmetry.beatlink CdjStatus DeviceAnnouncement DeviceAnnouncementListener DeviceFinder
            DeviceUpdate DeviceUpdateListener LifecycleListener PlayerSettings VirtualCdj]))

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

(defn- create-window
  "Builds an interface in which the user can adjust their My Settings
  preferences and load them into a player."
  []
  (seesaw/invoke-later
   (try
     (let [selected-player  (atom {:number nil :playing false})
           root             (seesaw/frame :title "My Settings" :on-close :dispose :resizable? false)
           load-button      (seesaw/button :text "Load" :enabled? false)
           problem-label    (seesaw/label :text "" :foreground "red")
           update-load-ui   (fn []
                              (let [playing (:playing @selected-player)
                                    problem (if playing "Can't load while playing." "")]
                                (seesaw/value! problem-label problem)
                                (seesaw/config! load-button :enabled? (empty? problem))))
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
                                                   [players] [load-button] [problem-label "push"]])
           layout           (seesaw/border-panel :south player-panel)
           stop-listener    (reify LifecycleListener
                              (started [this _]) ; Nothing to do, we exited as soon as a stop happened anyway.
                              (stopped [this _]  ; Close our window if VirtualCdj stops (we need it).
                                (seesaw/invoke-later
                                 (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))
           dev-listener     (reify DeviceAnnouncementListener
                              (deviceFound [this announcement]
                                (seesaw/invoke-later (track-loader/add-device players (.getDeviceNumber announcement))))
                              (deviceLost [this announcement]
                                (seesaw/invoke-later (track-loader/remove-device players (.getDeviceNumber announcement)
                                                                                 stop-listener))))
           status-listener  (reify DeviceUpdateListener
                              (received [this status]
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
                        (let [^PlayerSettings settings (PlayerSettings.)
                              ^Long selected-player    (.number ^PlayerChoice (.getSelectedItem players))]
                          ;; TODO: Adjust settings based on UI.
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
