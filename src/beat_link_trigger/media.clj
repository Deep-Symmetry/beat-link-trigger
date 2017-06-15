(ns beat-link-trigger.media
  "Provides the user interface for seeing the media playing on active
  players, as well as creating metadata caches and assigning them to
  particular player slots."
  (:require [clojure.java.browse]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre]
            [beat-link-trigger.playlist-entry]
            [beat-link-trigger.util :as util])
  (:import [org.deepsymmetry.beatlink DeviceFinder CdjStatus CdjStatus$TrackSourceSlot VirtualCdj]
           [org.deepsymmetry.beatlink.data MetadataFinder MetadataCacheCreationListener SlotReference
            WaveformListener WaveformPreviewComponent WaveformDetailComponent]
           [beat_link_trigger.playlist_entry IPlaylistEntry]
           [java.awt GraphicsEnvironment Font Color RenderingHints]
           [java.awt.event WindowEvent]
           [javax.swing JFileChooser JTree]
           [javax.swing.tree TreeNode DefaultMutableTreeNode DefaultTreeModel]))

(defonce fonts-loaded
  (atom false))

(defn load-fonts
  "Load and register the fonts we will use to draw on the display, if
  they have not already been."
  []
  (or @fonts-loaded
      (let [ge (GraphicsEnvironment/getLocalGraphicsEnvironment)]
        (doseq [font-file ["/fonts/DSEG/DSEG7Classic-Regular.ttf"
                           "/fonts/Orbitron/Orbitron-Black.ttf"
                           "/fonts/Orbitron/Orbitron-Bold.ttf"]]
            (.registerFont ge (Font/createFont Font/TRUETYPE_FONT
                                               (.getResourceAsStream IPlaylistEntry font-file))))
        (reset! fonts-loaded true))))

(defn get-display-font
  "Find one of the fonts configured for use by keyword, which must be
  one of `:segment`. The `style` argument is a `java.awt.Font` style
  constant, and `size` is point size.

  Orbitron is only available in bold, but asking for bold gives you
  Orbitron Black. Segment is only available in plain."
  [k style size]
  (case k
    :segment (Font. "DSEG7 Classic" Font/PLAIN size)
    :orbitron (Font. (if (= style Font/BOLD) "Orbitron Black" "Orbitron") Font/BOLD size)))

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
         listener  (reify MetadataCacheCreationListener
                     (cacheCreationContinuing [this last-track finished-count total-count]
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
         (.createMetadataCache (MetadataFinder/getInstance)
                               (SlotReference/getSlotReference player slot) (or playlist-id 0) file listener)
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
           (doseq [entry (.requestPlaylistItemsFrom (MetadataFinder/getInstance) player slot 0 id true)]
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

(def ^{:private true
       :doc "Holds the frame allowing the user to view player state
  and create and assign metadata caches to player slots."}
  media-window (atom nil))

(def player-row-width
  "The width of a player row in pixels."
  500)

(def player-row-height
  "The height of a player row in pixels, when no waveform detail is
  showing."
  300)

(defn- paint-player-number
  "Draws the player number being monitored by a row, updating the
  color to reflect its play state. Arguments are the player number,
  the component being drawn, and the graphics context in which drawing
  is taking place."
  [n c g]
  (let [w       (double (seesaw/width c))
        center  (/ w 2.0)
        h       (double (seesaw/height c))
        outline (java.awt.geom.RoundRectangle2D$Double. 1.0 1.0 (- w 2.0) (- h 2.0) 10.0 10.0)
        vcdj    (VirtualCdj/getInstance)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if (and (.isRunning vcdj)
                          (when-let [^CdjStatus status (.getLatestStatusFor vcdj (int n))] (.isPlaying status)))
                   Color/GREEN Color/DARK_GRAY))
    (.draw g outline)
    (.setFont g (get-display-font :orbitron Font/PLAIN 12))
    (let [frc    (.getFontRenderContext g)
          bounds (.getStringBounds (.getFont g) "Player" frc)]
      (.drawString g "Player" (float (- center (/ (.getWidth bounds) 2.0))) (float (+ (.getHeight bounds) 2.0))))
    (.setFont g (get-display-font :orbitron Font/BOLD 42))
    (let [frc    (.getFontRenderContext g)
          num    (str n)
          bounds (.getStringBounds (.getFont g) num frc)]
      (.drawString g num (float (- center (/ (.getWidth bounds) 2.0))) (float (- h 5.0))))))

(defn- create-player-row
  "Create a row a player, given its number."
  [n]
  (let [size [player-row-width :by player-row-height]
        preview (WaveformPreviewComponent. (int n))
        player (seesaw/canvas :size [56 :by 56] :opaque? false :paint (partial paint-player-number n))]
    
    (mig/mig-panel
     :id (keyword (str "player-" n))
     :background (Color/BLACK)
     :constraints ["" "" "[200!]"]
     :items [[player "left, bottom"] [preview "gapx 10px, right, bottom"]])
    ;; TODO: Set :visible? based on:
    ;; (set (map #(.getDeviceNumber %) (filter #(instance? CdjStatus %) (.getLatestStatus (VirtualCdj/getInstance)))))
    ;; TODO: Add a custom :paint function
    ;; TODO: Add update listener to repaint elements when play state, etc. change
    ))

(defn- create-player-rows
  "Creates the rows for each visible player in the Media Locations
  window."
  []
  (map create-player-row (range 1 5)))

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
                             :on-close :hide)
          players (seesaw/vertical-panel :id :players)]
      (seesaw/config! root :content players)
      (seesaw/config! players :items (create-player-rows))
      (seesaw/pack! root)
      (reset! media-window root)
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating Media Locations window."))))

(defn show-window
  "Open the Media Locations window if it is not already open."
  [trigger-frame globals editor-fn]
  (when-not @media-window (create-window trigger-frame globals))
  (make-window-visible trigger-frame))

(defn update-window
  "If the Media Locations window is showing, update it to reflect any
  changes which might have occurred to available players and
  assignable media. If `ms` is supplied, delay for that many
  milliseconds in the background in order to give the CDJ state time
  to settle down."
  ([globals]
   (when-let [root @media-window]
     ;; TODO: This can be replaced with device update listeners that just make the proper rows visible.
     ))
  ([globals ms]
   (when @media-window
     (future
       (Thread/sleep ms)
       (seesaw/invoke-later (update-window globals))))))
