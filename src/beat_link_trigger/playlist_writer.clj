(ns beat-link-trigger.playlist-writer
  "A window that facilitates the creation of comma-separated-value lists
  reporting tracks played over a particular period of time."
  (:require [clojure.core.async :as async :refer [<! >! <!! >!!]]
            [clojure.data.csv :as csv]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre]
            [beat-link-trigger.util :as util])
  (:import [org.deepsymmetry.beatlink LifecycleListener VirtualCdj DeviceUpdate CdjStatus]
           [org.deepsymmetry.beatlink.data MetadataFinder]
           [java.awt.event WindowEvent]
           [javax.swing JFileChooser]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to write playlist files."}
  writer-window (atom nil))

(def virtual-cdj
  "The object which can obtained detailed player status information."
  (VirtualCdj/getInstance))

(def metadata-finder
  "The object that can obtain track metadata."
  (MetadataFinder/getInstance))

(defn- make-window-visible
  "Ensures that the playlist writer window is in front, and shown."
  []
  (let [our-frame @writer-window]
    (seesaw/show! our-frame)
    (.toFront our-frame)))

(def ^:private idle-status
  "The status to display when we are not recording a playlist."
  "Idle (not writing a playlist)")

(defn- build-toggle-handler
  "Creates an event handler for the Start/Stop button."
  [button status file-atom frame]
  (fn [_]
    (if @file-atom
      (do  ; Time to stop writing a playlist
        (seesaw/config! button :text "Start")
        (seesaw/config! status :text idle-status)
        (reset! file-atom nil))
      (when-let [file (util/confirm-overwrite-file
                       (seesaw.chooser/choose-file frame :type :save
                                                   :filters [["Playlist CSV files" ["csv"]]]
                                                   :all-files? false)
                       "csv"
                       frame)]
        (clojure.java.io/delete-file file true)
        (with-open [writer (clojure.java.io/writer file)]
          (csv/write-csv writer [["Title" "Artist" "Album" "Player" "Started" "Stopped" "Play Time"]]))
        (reset! file-atom file)
        (seesaw/config! button :text "Stop")
        (seesaw/config! status :text (str "Writing to " (.getName file)))))))

(defn- create-window
  "Creates the playlist writer window."
  [trigger-frame]
  (try
    (let [shutdown-chan (async/promise-chan)
          playlist-file (atom nil)
          toggle-button (seesaw/button :id :start :text "Start")
          status-label  (seesaw/label :id :status :text idle-status)
          panel         (mig/mig-panel
                         :background "#ccc"
                         :items [[(seesaw/label :text "Minimum Play Time:") "align right"]
                                 [(seesaw/spinner :id :time :model (seesaw/spinner-model 10 :from 0 :to 60))]
                                 [(seesaw/label :text "seconds") "align left, wrap"]

                                 [(seesaw/label :text "Status:") "align right"]
                                 [status-label "span, grow, wrap 15"]

                                 [(seesaw/label :text "")]
                                 [toggle-button "span 2"]
                                 ])
          root          (seesaw/frame :title "Playlist Writer"
                                      :content panel
                                      :on-close :dispose)
          stop-listener (reify LifecycleListener
                          (started [this sender])  ; Nothing for us to do, we exited as soon a stop happened anyway.
                          (stopped [this sender]  ; Close our window if VirtualCdj gets shut down (we went offline).
                            (seesaw/invoke-later
                             (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))]
      (seesaw/listen root :window-closed (fn [e]
                                           (>!! shutdown-chan :done)
                                           (reset! writer-window nil)
                                           (.removeLifecycleListener virtual-cdj stop-listener)))
      (.addLifecycleListener virtual-cdj stop-listener)
      (seesaw/listen toggle-button :action (build-toggle-handler toggle-button status-label playlist-file root))
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
    (when-not @writer-window (create-window trigger-frame)))
  (make-window-visible))
