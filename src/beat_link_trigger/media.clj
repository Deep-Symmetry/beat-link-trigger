(ns beat-link-trigger.media
  "Provides the user interface for assigning media collections to
  particular player slots during show setup."
  (:require [clojure.java.browse]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder CdjStatus$TrackSourceSlot]))

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

(defn show-window
  "Open the Media Locations window if it is not already open."
  [trigger-frame globals editor-fn]
  (cond
    (or (not (DeviceFinder/isActive)) (empty? (DeviceFinder/currentDevices)))
    (show-no-devices trigger-frame)

    (empty? (keys (:media @globals)))
    (show-no-media trigger-frame editor-fn)))
