(ns beat-link-trigger.media
  "Provides the user interface for assigning media collections to
  particular player slots during show setup."
  (:require [clojure.java.browse]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre]
            [beat-link-trigger.playlist-entry]
            [beat-link-trigger.util :as util])
  (:import [org.deepsymmetry.beatlink DeviceFinder CdjStatus CdjStatus$TrackSourceSlot VirtualCdj MetadataFinder
            MetadataCreationUpdateListener SlotReference]
           [beat_link_trigger.playlist_entry IPlaylistEntry]
           [java.awt.event WindowEvent]
           [javax.swing JFileChooser JTree]
           [javax.swing.tree TreeNode DefaultMutableTreeNode DefaultTreeModel]))

;; TODO: Support optional additional argument to download only a single playlist when implemented.
(defn create-metadata-cache
  "Downloads metadata for the specified player and media slot,
  creating a cache in the specified file. If `playlist-id` is
  supplied, only the playlist with that ID will be downloaded,
  otherwise all tracks will be downloaded. Provides a progress bar
  during the download process, and allows the user to cancel it."
  ([player slot file]
   (create-metadata-cache player slot file nil))
  ([player slot file playlist-id]
   (let [continue? (atom true)
         progress  (seesaw/progress-bar :indeterminate? true :min 0 :max 1000)
         latest    (seesaw/label :text "Gathering tracks…")
         panel     (mig/mig-panel
                    :items [[(seesaw/label :text (str "<html>Creating " (if playlist-id "playlist" "full")
                                                      " metadata cache for player " player
                                                      ", " (if (= slot CdjStatus$TrackSourceSlot/USB_SLOT) "USB" "SD")
                                                      " slot, in file <strong>" (.getName file) "</strong>:</html>"))
                             "span, wrap"]
                            [latest "span, wrap 20"]
                            [progress "grow, span, wrap 16"]
                            [(seesaw/button :text "Cancel"
                                            :listen [:action-performed (fn [e]
                                                                         (reset! continue? false)
                                                                         (seesaw/config! e :enabled? false
                                                                                         :text "Canceling…"))])
                             "span, align center"]])
         root      (seesaw/frame :title "Downloading Metadata" :on-close :dispose :content panel)
         trim-name (fn [track]
                     (let [title (.getTitle track)]
                       (if (< (count title) 40)
                         title
                         (str (subs title 0 39) "…"))))
         listener  (reify MetadataCreationUpdateListener
                     (cacheUpdateContinuing [this last-track finished-count total-count]
                       (seesaw/invoke-later
                        (seesaw/config! progress :max total-count :indeterminate? false)
                        (seesaw/value! progress finished-count)
                        (seesaw/config! latest :text (str "Added " finished-count " of " total-count
                                                          ": " (trim-name last-track)))
                        (when (or (not @continue?) (>= finished-count total-count))
                          (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING))))
                       @continue?))]
     (seesaw/listen root :window-closed (fn [e] (reset! continue? false)))
     (.pack root)
     (.setLocationRelativeTo root nil)
     (seesaw/show! root)
     (future
       (try
         ;; To load all tracks we pass a playlist ID of 0
         (MetadataFinder/createMetadataCache (SlotReference/getSlotReference player slot) (or playlist-id 0)
                                             file listener)
         (catch Exception e
           (timbre/error e "Problem creating metadata cache.")
           (seesaw/alert (str "<html>Problem gathering metadata: " (.getMessage e)
                              "<br><br>Check the log file for details.</html>")
                         :title "Exception creating metadata cache" :type :error)
           (seesaw/invoke-later (.dispose root))))))))

(defn- playlist-node
  "Create a node in the playlist selection tree that can lazily load
  its children if it is a folder."
  [player slot title id folder?]
  (let [unloaded (atom true)]
    (DefaultMutableTreeNode.
     (proxy [Object IPlaylistEntry] []
       (toString [] (str title))
       (getId [] (int id))
       (isFolder [] (true? folder?))
       (loadChildren [^javax.swing.tree.TreeNode node]
         (when (and folder? @unloaded)
           (reset! unloaded false)
           (timbre/info "requesting playlist folder" id)
           (doseq [entry (MetadataFinder/requestPlaylistItemsFrom player slot 0 id true)]
             (let [entry-name (.getValue (nth (.arguments entry) 3))
                   entry-kind (.getValue (nth (.arguments entry) 6))
                   entry-id (.getValue (nth (.arguments entry) 1))]
               (.add node (playlist-node player slot entry-name (int entry-id) (true? (= entry-kind 1)))))))))
     (true? folder?))))

(defn- build-playlist-nodes
  "Create the top-level playlist nodes, which will lazily load any
  child playlists from the player when they are expanded."
  [player slot]
  (let [root (playlist-node player slot "root", 0, true)
        playlists (playlist-node  player slot "Playlists", 0, true)]
    (.add root (playlist-node player slot "All Tracks", 0, false))
    (.add root playlists)
    root))

(defn show-cache-creation-dialog
  "Presents an interface in which the user can choose which playlist
  to cache and specify the destination file."
  [player slot]
  ;; TODO: Automatically (temporarily) enter passive mode for the duration of creating the cache,
  ;;       possibly confirming with the user.
  (seesaw/invoke-later
   (let [selected-id           (atom nil)
         root                  (seesaw/frame :title "Create Metadata Cache"
                                             :on-close :dispose)
         ^JFileChooser chooser (@#'chooser/configure-file-chooser (JFileChooser.)
                                {:all-files? false
                                 :filters    [["BeatLink metadata cache" ["bltm"]]]})
         heading               (seesaw/label :text "Choose what to cache and where to save it:")
         tree                           (seesaw/tree :model (DefaultTreeModel. (build-playlist-nodes player slot) true)
                                                     :root-visible? false)
         panel                 (mig/mig-panel :items [[heading "wrap, align center"]
                                                      [(seesaw/scrollable tree) "grow, wrap"]
                                                      [chooser]])
         ready-to-save? (fn []
                          (or (some? @selected-id)
                              (seesaw/alert "You must choose a playlist to save or All Tracks."
                                            :title "No Cache Source Chosen" :type :error)))]
     ;; TODO: Either figure out how to fix resize behavior, or prevent it.
     (.setSelectionMode (.getSelectionModel tree) javax.swing.tree.TreeSelectionModel/SINGLE_TREE_SELECTION)
     (seesaw/listen tree
                    :tree-will-expand
                    (fn [e]
                      (let [^TreeNode node        (.. e (getPath) (getLastPathComponent))
                            ^IPlaylistEntry entry (.getUserObject node)]
                        (.loadChildren entry node)))
                    :selection
                    (fn [e]
                      (reset! selected-id
                              (when (.isAddedPath e)
                                (let [entry (.. e (getPath) (getLastPathComponent) (getUserObject))]
                                  (when-not (.isFolder entry)
                                    (.getId entry)))))))
     (.setVisibleRowCount tree 10)
     (.expandRow tree 1)

     (when-let [[file-filter _] (seq (.getChoosableFileFilters chooser))]
       (.setFileFilter chooser file-filter))
     (.setDialogType chooser JFileChooser/SAVE_DIALOG)
     (seesaw/listen chooser
                    :action-performed
                    (fn [action]
                      (if (= (.getActionCommand action) JFileChooser/APPROVE_SELECTION)
                        (when (ready-to-save?)  ; Ignore the save attempt if no playlist chosen.
                          (@#'chooser/remember-chooser-dir chooser)
                          (when-let [file (util/confirm-overwrite-file (.getSelectedFile chooser) "bltm" nil)]
                            (seesaw/invoke-later (create-metadata-cache player slot file @selected-id)))
                          (.dispose root))
                        (.dispose root))))  ; They chose cancel.
     (seesaw/config! root :content panel)
     (seesaw/pack! root)
     (.setLocationRelativeTo root nil)
     (seesaw/show! root))))

;; TODO: Update to work with saved metadata caches rather than manual media information.
;; TODO: Migrate to becoming broader virtual CDJ UI. Probably rename.

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
                                  :listen [:item-state-changed (fn [e] (set-media :usb-slot e))])
        sd-slot (seesaw/combobox :model media-model
                                 :listen [:item-state-changed (fn [e] (set-media :sd-slot e))])]
    (seesaw/value! usb-slot (str (get-in @globals [:media-locations n :usb-slot])))
    (seesaw/value! sd-slot (str (get-in @globals [:media-locations n :sd-slot])))
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

(defn update-window
  "If the Media Locations window is showing, update it to reflect any
  changes which might have occurred to available players and
  assignable media. If `ms` is supplied, delay for that many
  milliseconds in the background in order to give the CDJ state time
  to settle down."
  ([globals]
   (when-let [root @media-window]
     (let [players (seesaw/config root :content)]
          (seesaw/config! players :items (create-player-rows globals))
          (seesaw/pack! root))))
  ([globals ms]
   (when @media-window
     (future
       (Thread/sleep ms)
       (seesaw/invoke-later (update-window globals))))))
