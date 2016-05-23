(ns beat-link-trigger.core
  "Send MIDI or OSC events when a CDJ starts playing."
  (:require [beat-link-trigger.about :as about]
            [beat-link-trigger.logs :as logs]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.triggers :as triggers]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj]))

(defn start
  "Set up logging, make sure we can start the Virtual CDJ, then
  present the Triggers interface. Called when jar startup has detected
  a recent-enough Java version to succcessfully load this namespace."
  [& args]
  (seesaw/native!)  ; Adopt as native a look-and-feel as possible
  (logs/init-logging)
  (timbre/info "Beat Link Trigger starting.")
  (menus/install-mac-about-handler)
  (let [searching (about/create-searching-frame)]
    (loop []
      (if (try (VirtualCdj/start)  ; Make sure we can see some DJ Link devices and start the VirtualCdj
               (catch Exception e
                 (timbre/log e "Unable to create Virtual CDJ")
                 (seesaw/hide! searching)
                 (seesaw/alert (str "<html>Unable to create Virtual CDJ<br><br>" e)
                               :title "DJ Link Connection Failed" :type :error)))
        (seesaw/dispose! searching)  ; We succeeded in finding a DJ Link network
        (do
          (seesaw/hide! searching)  ; No luck so far, ask what to do
          (let [options (to-array ["Try Again" "Quit" "Continue Offline"])
                choice (javax.swing.JOptionPane/showOptionDialog
                        nil "No DJ Link devices were seen on any network. Search again?"
                        "No DJ Link Devices Found"
                        javax.swing.JOptionPane/YES_NO_OPTION javax.swing.JOptionPane/ERROR_MESSAGE nil
                        options (aget options 0))]
            (case choice
              0 (do (seesaw/show! searching) (recur))  ; Try Again
              2 (do (seesaw/dispose! searching) (DeviceFinder/stop))  ; Continue Offline
              (System/exit 1)))))))     ; Quit, or just closed the window, which means the same

  (triggers/start))  ; We are online, or the user said to continue offline, so set up the Triggers window.
