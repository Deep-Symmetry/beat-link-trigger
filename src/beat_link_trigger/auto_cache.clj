(ns beat-link-trigger.auto-cache
  "Provides an interface for selecting a set of metadata cache files
  that will be automatically attached to player slots when media is
  mounted that matches the cache file."
  (:require [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.mig :as mig]
            [clojure.contrib.humanize :as humanize]
            [clojure.contrib.inflect :as inflect]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink.data MetadataFinder]
           [org.deepsymmetry.beatlink MediaDetails]
           [javax.swing JFileChooser]))

(defonce ^{:private true
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

(defn- row-icon
  "Returns the appropriate icon to be used for a row, depending on
  whether the metadata cache contains media details."
  [details]
  (seesaw/icon (if details "images/info.png" "images/warn.png")))

(defn- row-alert
  "Displays the appropriate alert when a row's info or warning button
  has been pressed, depending on whether the metadata cache contains
  media details."
  [panel details playlist tracks]
  (let [content (str "<br><br>Cache contains: "
                     (if (pos? playlist) "A single playlist of " "All ")
                     tracks " " (inflect/pluralize-noun tracks "track") ".")]
    (if details
      (seesaw/alert panel (str "<html>Media “" (.name details) "”, created " (.creationDate details)
                               ", size " (humanize/filesize (.totalSize details))
                               " (" (humanize/filesize (.freeSpace details)) " free).<br>"
                               "Media contains: " (.trackCount details) " "
                               (inflect/pluralize-noun (.trackCount details) "track")
                               " and " (.playlistCount details) " "
                               (inflect/pluralize-noun (.playlistCount details) "playlist") "."
                               content)
                    :title (str "Metadata Cache Details") :type :info)
      (seesaw/alert panel (str "<html>This metadata cache file was created by an older version of<br>"
                               "Beat Link Trigger, and has no media details recorded in it.<br><br>"
                               "If you can re-create it using the current version, that will<br>"
                               "be more easily and reliably matched with mounted media."
                               content)
                    :title "Cache is Missing Media Details" :type :warning))))

(defn- get-cache-details
  "Returns the media details object (if any), source playlist number,
  and track count for a metadata cache file."
  [file]
  (let [cache (.openMetadataCache metadata-finder file)]
    (try
      [(.getCacheMediaDetails metadata-finder cache)
       (.getCacheSourcePlaylist metadata-finder cache)
       (.getCacheTrackCount metadata-finder cache)]
      (finally (.close cache)))))

(defn- create-file-rows
  "Creates a set of mig-panel rows that represent the currently
  configured auto-attach files."
  [panel]
  (let [files (.getAutoAttachCacheFiles metadata-finder)
        items (or (when (empty? files)
                    [[["No Auto-Attach Cache Files Configured" "pushx, align center, wrap"]]])
                  (for [file files]
                    (let [[details playlist tracks] (get-cache-details file)]
                      [[(seesaw/label :text (.getAbsolutePath file)) "pushx"]
                       [(seesaw/button :id :info :icon (row-icon details)
                                       :listen [:action-performed (fn [e] (row-alert panel details playlist tracks))])]
                       [(seesaw/button :text "Remove"
                                       :listen [:action-performed (fn [e]
                                                                    (.removeAutoAttacheCacheFile metadata-finder file)
                                                                    (create-file-rows panel))])
                        "wrap"]])))]
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
      (let [[details playlist tracks] (get-cache-details file)]
        (when-not details (row-alert panel details playlist tracks)))  ; Warn upon adding if outdated.
      (catch Exception e
        (timbre/error e "Problem auto-attaching" file)
        (seesaw/alert panel (str "<html>Unable to Auto-Attach Metadata Cache.<br><br>" e)
                      :title "Problem Auto-Attaching File" :type :error))))
  (create-file-rows panel))

(defn- create-window
  "Creates the Auto Attach window."
  [trigger-frame]
  (try
    (let [root       (seesaw/frame :title "Auto Attach Cache Files"
                                   :size [600 :by 200]
                                   :on-close :dispose)
          files      (mig/mig-panel)
          add-button (seesaw/button :text "Add File"
                                    :listen [:action-performed (fn [e] (choose-file files))])
          panel      (seesaw/border-panel
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
