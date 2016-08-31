(ns beat-link-trigger.media
  "Provides the user interface for assigning media collections to
  particular player slots during show setup."
  (:require [clojure.java.browse]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder CdjStatus CdjStatus$TrackSourceSlot VirtualCdj]))

(defn show-no-devices
  "Report that media cannot be assigned because no DJ Link devices are
  visible on the network."
  [trigger-frame]
  (seesaw/invoke-now (javax.swing.JOptionPane/showMessageDialog
                      trigger-frame
                      "No DJ Link devices are visible, so there is nowhere to assign media."
                      "No Devices Available"
                      javax.swing.JOptionPane/WARNING_MESSAGE)))

(defn show-no-media
  "Report that media has not been configured, and offer to open the manual
  or Global Setup Expresion editor."
  [trigger-frame editor-fn]
  (let [options (to-array ["View User Guide" "Edit Global Setup" "Cancel"])
        choice (seesaw/invoke-now
                        (javax.swing.JOptionPane/showOptionDialog
                         trigger-frame
                         (str "No media libraries have been set up in the Expression Globals.\n"
                              "This must be done before they can be assigned to player slots.")
                         "No Media Entries Found"
                         javax.swing.JOptionPane/YES_NO_OPTION javax.swing.JOptionPane/ERROR_MESSAGE nil
                         options (aget options 0)))]
            (case choice
              0 (clojure.java.browse/browse-url (str "https://github.com/brunchboy/beat-link-trigger/"
                                                     "blob/master/doc/README.adoc#matching-tracks"))
              1 (seesaw/invoke-soon (editor-fn))  ; Show global setup editor
              nil)))

(def ^{:private true
       :doc "Holds the frame allowing the user to assign media to player slots."}
  media-window (atom nil))

(defn- create-player-row
  "Create a row for assigning media to the slots of a player, given
  its number."
  [globals n color]
  (let [set-media (fn [slot e]
                    (let [title (seesaw/value (seesaw/to-widget e))
                          chosen (when-not (clojure.string/blank? title) (clojure.edn/read-string title))]
                      (swap! globals assoc-in [:media-locations n slot] chosen)))
        media-model (concat [""] (map str (sort (keys (:media @globals)))))
        usb-slot (seesaw/combobox :model media-model
                                  :listen [:item-state-changed (fn [e] (set-media :usb e))])
        sd-slot (seesaw/combobox :model media-model
                                 :listen [:item-state-changed (fn [e] (set-media :sd e))])]
    (seesaw/value! usb-slot (str (get-in @globals [:media-locations n :usb])))
    (seesaw/value! sd-slot (str (get-in @globals [:media-locations n :sd])))
    (mig/mig-panel
     :background color
     :items [[(str "Player " n ".") "align right"]

             ["USB:" "gap unrelated"]
             [usb-slot]

             ["SD:" "gap unrelated"]
             [sd-slot]])))

(defn- create-player-rows
  "Creates the rows for each visible player in the Media Locations
  window."
  [globals]
  (map (fn [n color]
         (create-player-row globals n color))
       (sort (map #(.getDeviceNumber %) (filter #(instance? CdjStatus %) (VirtualCdj/getLatestStatus))))
       (cycle ["#eee" "#ccc"])))

(defn- make-window-visible
  "Ensures that the Media Locations window is centered on the triggers
  window, in front, and shown."
  [trigger-frame]
  (.setLocationRelativeTo @media-window trigger-frame)
  (seesaw/show! @media-window)
  (.toFront @media-window))

(defn- create-window
  "Creates the Media Locations window."
  [trigger-frame globals]
  (try
    (let [root (seesaw/frame :title "Media Locations"
                             :on-close :dispose)
          players (seesaw/vertical-panel :id :players)]
      (seesaw/config! root :content players)
      (seesaw/config! players :items (create-player-rows globals))
      (seesaw/pack! root)
      (seesaw/listen root :window-closed (fn [_] (reset! media-window nil)))
      (reset! media-window root)
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating Media Locations window."))))

(defn show-window
  "Open the Media Locations window if it is not already open."
  [trigger-frame globals editor-fn]
  (cond
    (or (not (DeviceFinder/isActive)) (empty? (DeviceFinder/currentDevices)))
    (show-no-devices trigger-frame)

    (empty? (keys (:media @globals)))
    (show-no-media trigger-frame editor-fn)

    (not @media-window)
    (create-window trigger-frame globals)

    :else
    (make-window-visible trigger-frame)))
