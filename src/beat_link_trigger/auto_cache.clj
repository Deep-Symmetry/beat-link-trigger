(ns beat-link-trigger.auto-cache
  "Provides an interface for selecting a set of metadata cache files
  that will be automatically attached to player slots when media is
  mounted that matches the cache file."
  (:require [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink.data MetadataFinder]
           [javax.swing JFileChooser]))

(def ^{:private true
       :doc "Holds the frame allowing the user to manage automatic
  cache file attachment."}
  auto-window (atom nil))

(def metadata-finder
  "The object that can obtain track metadata and manage cache files."
  (MetadataFinder/getInstance))

(defn- make-window-visible
  "Ensures that the Auto Attach window is centered on the triggers
  window, in front, and shown."
  [trigger-frame]
  (.setLocationRelativeTo @auto-window trigger-frame)
  (seesaw/show! @auto-window)
  (.toFront @auto-window))

(defn- create-file-rows
  "Creates a set of mig-panel rows that represent the currently
  configured auto-attach files."
  [panel]
  (let [files (.getAutoAttachCacheFiles metadata-finder)
        items (or (when (empty? files)
                    [[["No Auto-Attach Cache Files Configured" "pushx, align center, wrap"]]])
                  (for [file files]
                    [[(seesaw/label :text (.getAbsolutePath file)) "pushx"]
                     [(seesaw/button :text "Remove"
                                     :listen [:action-performed (fn [e]
                                                                  (.removeAutoAttacheCacheFile metadata-finder file)
                                                                  (create-file-rows panel))])
                      "wrap"]]))]
    (seesaw/config! panel :items (vec (apply concat items)))))

(defn- choose-file
  "Provides an interface for the user to choose a file to add."
  [panel]
  (when-let [file (chooser/choose-file
                   @auto-window
                   :all-files? false
                   :filters [["BeatLink metadata cache" ["bltm"]]])]
    (try
      (.addAutoAttachCacheFile metadata-finder file)
      (catch Exception e
        (timbre/error e "Problem auto-attaching" file)
        (seesaw/alert (str "<html>Unable to Auto-Attach Metadata Cache.<br><br>" e)
                      :title "Problem Auto-Attaching File" :type :error))))
  (create-file-rows panel))

(defn- create-window
  "Creates the Auto Attach window."
  [trigger-frame]
  (try
    (let [root            (seesaw/frame :title "Auto Attach Cache Files"
                                        :size [400 :by 200]
                                        :on-close :dispose)
          files (mig/mig-panel)
          add-button (seesaw/button :text "Add File"
                                    :listen [:action-performed (fn [e] (choose-file files))])
          panel           (seesaw/border-panel
                           :center (seesaw/scrollable files)
                           :south (mig/mig-panel :items [[add-button "pushx, align center"]]))]
      (seesaw/config! root :content panel)
      (create-file-rows files)
      (seesaw/listen root :window-closed (fn [e] (reset! auto-window nil)))
      (reset! auto-window root)
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating Auto Attach window."))))

(defn show-window
  "Open the Auto Attach window if it is not already open."
  [trigger-frame]
  (locking auto-window
    (when-not @auto-window (create-window trigger-frame)))
  (make-window-visible trigger-frame))
