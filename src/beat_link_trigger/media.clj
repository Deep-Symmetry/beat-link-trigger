(ns beat-link-trigger.media
  "Provides the user interface for seeing the media playing on active
  players, as well as creating metadata caches and assigning them to
  particular player slots."
  (:require [clojure.java.browse]
            [clojure.core.async :as async :refer [<! >! <!! >!!]]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre]
            [beat-link-trigger.playlist-entry]
            [beat-link-trigger.util :as util])
  (:import [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncement DeviceAnnouncementListener
            VirtualCdj DeviceUpdate CdjStatus CdjStatus$TrackSourceSlot]
           [org.deepsymmetry.beatlink.data MetadataFinder MetadataCacheCreationListener SlotReference
            WaveformListener WaveformPreviewComponent WaveformDetailComponent TimeFinder WaveformFinder]
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
                           "/fonts/Orbitron/Orbitron-Bold.ttf"
                           "/fonts/Teko/Teko-Regular.ttf"
                           "/fonts/Teko/Teko-SemiBold.ttf"]]
            (.registerFont ge (Font/createFont Font/TRUETYPE_FONT
                                               (.getResourceAsStream IPlaylistEntry font-file))))
        (reset! fonts-loaded true))))

(defn get-display-font
  "Find one of the fonts configured for use by keyword, which must be
  one of `:segment`. The `style` argument is a `java.awt.Font` style
  constant, and `size` is point size.

  Orbitron is only available in bold, but asking for bold gives you
  Orbitron Black. Segment is only available in plain. Teko is
  available in plain and bold (but we actually deliver the semibold
  version, since that looks nicer in the UI)."
  [k style size]
  (case k
    :orbitron (Font. (if (= style Font/BOLD) "Orbitron Black" "Orbitron") Font/BOLD size)
    :segment (Font. "DSEG7 Classic" Font/PLAIN size)
    :teko (Font. (if (= style Font/BOLD) "Teko SemiBold" "Teko") Font/PLAIN size)))

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

(def device-finder
  "The object that tracks the arrival and departure of devices on the
  DJ Link network."
  (DeviceFinder/getInstance))

(def virtual-cdj
  "The object which can obtained detailed player status information."
  (VirtualCdj/getInstance))

(def waveform-finder
  "The object that can obtain track waveform information."
  (WaveformFinder/getInstance))

(def time-finder
  "The object that can obtain detailed track time information."
  (TimeFinder/getInstance))

(defn time-played
  "If possible, returns the number of milliseconds of track the
  specified player has played."
  [n]
  (and (.isRunning time-finder) (.getTimeFor time-finder n)))

(defn- playing?
  "Returns `true` if the specified player can be determined to be
  playing right now."
  [n]
  (and (.isRunning virtual-cdj)
       (when-let [^CdjStatus status (.getLatestStatusFor virtual-cdj (int n))]
         (.isPlaying status))))

(defn- paint-player-number
  "Draws the player number being monitored by a row, updating the
  color to reflect its play state. Arguments are the player number,
  the component being drawn, and the graphics context in which drawing
  is taking place."
  [n c g]
  (let [w       (double (seesaw/width c))
        center  (/ w 2.0)
        h       (double (seesaw/height c))
        outline (java.awt.geom.RoundRectangle2D$Double. 1.0 1.0 (- w 2.0) (- h 2.0) 10.0 10.0)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if (playing? n)
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

(defn- current-beat
  "Determine, if possible, the current beat within a musical bar which
  the specified player is playing or paused on."
  [n]
  (let [result (and (.isRunning time-finder)
                    (when-let [^DeviceUpdate update (.getLatestUpdateFor time-finder (int n))]
                      (.getBeatWithinBar update)))]
    (when (and result (<= 1 result 4))
      result)))

(defn- paint-beat
  "Draws a graphical representation of the current beat
  position (within a musical bar) of the specified player, if known.
  Arguments are the player number, the component being drawn, and the
  graphics context in which drawing is taking place."
  [n c g]
  (try
    (let [image-data (clojure.java.io/resource (str "images/BeatMini-" (or (current-beat n) "blank") ".png"))
          image (javax.imageio.ImageIO/read image-data)]
      (.drawImage g image 0 0 nil))
    (catch Exception e
      (timbre/error e "Problem loading beat indicator image"))))

(defn- time-left
  "Figure out the number of milliseconds left to play for a given
  player, given the player number and time played so far."
  [n played]
  (let [detail (.getLatestDetailFor waveform-finder n)]
    (when detail (max 0 (- (.getTotalTime detail) played)))))

(defn- format-time
  "Formats a number for display as a two-digit time value, or -- if it
  cannot be."
  [t]
  (if t
    (let [t (int t)]
      (if (< t 100)
        (format "%02d" t)
        "--"))
    "--"))

(defn- paint-time
  "Draws time information for a player. Arguments are player number, a
  boolean flag indicating we are drawing remaining time, the component
  being drawn, and the graphics context in which drawing is taking
  place."
  [n remain c g]
  (let [played      (time-played n)
        ms          (when (and played (>= played 0)) (if remain (time-left n played) played))
        min         (format-time (when ms (/ ms 60000)))
        sec         (format-time (when ms (/ (mod ms 60000) 1000)))
        half-frame  (when ms (mod (org.deepsymmetry.beatlink.Util/timeToHalfFrame ms) 150))
        frame       (format-time (when half-frame (/ half-frame 2)))
        frac-frame  (if half-frame (if (even? half-frame) "0" "5") "-")]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setPaint g (if remain (Color. 255 200 200) (Color/WHITE)))
    (.setFont g (get-display-font :teko Font/PLAIN 16))
    (.drawString g (if remain "Remain" "Time") (int 4) (int 16))
    (.setFont g (get-display-font :segment Font/PLAIN 20))
    (.drawString g min (int 2) (int 40))
    (.fill g (java.awt.geom.Rectangle2D$Double. 42.0 34.0 2.0 3.0))
    (.fill g (java.awt.geom.Rectangle2D$Double. 42.0 24.0 2.0 3.0))
    (.drawString g sec (int 45) (int 40))
    (.fill g (java.awt.geom.Rectangle2D$Double. 84.0 34.0 2.0 3.0))
    (.fill g (java.awt.geom.Rectangle2D$Double. 84.0 24.0 2.0 3.0))
    (.drawString g frame (int 87) (int 40))
    (.setFont g (get-display-font :teko Font/BOLD 10))
    (.drawString g "M" (int 34) (int 40))
    (.drawString g "S" (int 77) (int 40))
    (.drawString g "F" (int 135) (int 40))
    (.fill g (java.awt.geom.Rectangle2D$Double. 120.0 37.0 2.0 3.0))
    (.setFont g (get-display-font :segment Font/PLAIN 16))
    (.drawString g frac-frame (int 122) (int 40))))

(defn- tempo-values
  "Look up the current playback pitch percentage and effective tempo
  for the specified player, returning them when available as a vector
  followed by a boolean indication of whether the device is the
  current tempo master."
  [n]
  (when-let [^DeviceUpdate u (when (.isRunning time-finder) (.getLatestUpdateFor time-finder n))]
    [(org.deepsymmetry.beatlink.Util/pitchToPercentage (.getPitch u))
     (.getEffectiveTempo u)
     (.isTempoMaster u)]))

(defn- paint-tempo
  "Draws tempo information for a player. Arguments are player number,
  the component being drawn, and the graphics context in which drawing
  is taking place."
  [n c g]
  (when-let [[pitch tempo master] (tempo-values n)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setPaint g (Color/WHITE))
    (.setFont g (get-display-font :teko Font/PLAIN 16))
    (.drawString g "Tempo" (int 4) (int 16))
    (.setFont g (get-display-font :teko Font/BOLD 20))
    (.drawString g (if (> 0.025 (Math/abs pitch)) " " (if (neg? pitch) "-" "+")) (int 2) (int 38))
    (.setFont g (get-display-font :segment Font/PLAIN 16))
    (.drawString g (clojure.string/replace (format "%5.1f" (Math/abs pitch)) " " "!") (int 4) (int 40))
    (.setFont g (get-display-font :teko Font/PLAIN 14))
    (.drawString g "%" (int 56) (int 40))
    (when master (.setPaint g Color/ORANGE))
    (let [frame        (java.awt.geom.RoundRectangle2D$Double. 68.0 1.0 50.0 38.0 8.0 8.0)
          clip         (.getClip g)
          tempo-string (clojure.string/replace (format "%5.1f" tempo) " " "!")]
      (.draw g frame)
      (.setFont g (get-display-font :teko Font/PLAIN 14))
      (if master
        (do (.setClip g frame)
            (.fill g (java.awt.geom.Rectangle2D$Double. 68.0 27.0 50.0 13.0))
            (.setColor g (Color/BLACK))
            (.drawString g "MASTER" (int 77) (int 38))
            (.setClip g clip))
        (.drawString g "BPM" (int 97) (int 36)))
      (.setFont g (get-display-font :segment Font/PLAIN 16))
      (.setColor g (if master Color/ORANGE Color/WHITE))
      (.drawString g (subs tempo-string 0 4) (int 68) (int 22))
      (.setFont g (get-display-font :segment Font/PLAIN 12))
      (.drawString g (subs tempo-string 4) (int 107) (int 22)))))

(defn- create-player-row
  "Create a row a player, given the shutdown channel and player
  number."
  [shutdown-chan n]
  (let [preview      (WaveformPreviewComponent. (int n))
        beat         (seesaw/canvas :size [55 :by 5] :opaque? false :paint (partial paint-beat n))
        last-beat    (atom nil)
        player       (seesaw/canvas :size [56 :by 56] :opaque? false :paint (partial paint-player-number n))
        last-playing (atom nil)
        time         (seesaw/canvas :size [140 :by 40] :opaque? false :paint (partial paint-time n false))
        last-time    (atom nil)
        remain       (seesaw/canvas :size [140 :by 40] :opaque? false :paint (partial paint-time n true))
        tempo        (seesaw/canvas :size [120 :by 40] :opaque? false :paint (partial paint-tempo n))
        last-tempo   (atom nil)
        row          (mig/mig-panel
                      :id (keyword (str "player-" n))
                      :background (Color/BLACK)
                      :items [[beat "bottom"] [time ""] [remain ""] [tempo "wrap"]
                              [player "left, bottom"] [preview "right, bottom, span"]])
        dev-listener (reify DeviceAnnouncementListener
                       (deviceFound [this announcement]
                         (when (= n (.getNumber announcement))
                           (seesaw/invoke-soon
                            (seesaw/config! row :visible? true)))
                         nil)
                       (deviceLost [this announcement]
                         (when (= n (.getNumber announcement))
                           (seesaw/invoke-soon
                            (seesaw/config! row :visible? false)))
                         nil))]
    (.addDeviceAnnouncementListener device-finder dev-listener)   ; React to our device coming and going
    (when-not (.getLatestAnnouncementFrom device-finder (int n))  ; We are starting out with no device
      (seesaw/config! row :visible? false))
    (async/go  ; Clean up when the window closes
      (<! shutdown-chan)  ; Parks until the window is closed
      (.removeDeviceAnnouncementListener device-finder dev-listener)
      (.setMonitoredPlayer preview 0))
    (async/go  ; Animation loop
      (while (nil? (async/poll! shutdown-chan))
        (try
          (async/alt!
            shutdown-chan nil
            (async/timeout 20) (do
                                 (when (not= @last-playing (playing? n))
                                   (reset! last-playing (playing? n))
                                   (seesaw/repaint! player))
                                 (when (not= @last-time (time-played n))
                                   (reset! last-time (time-played n))
                                   (seesaw/repaint! [time remain]))
                                 (when (not= @last-beat (current-beat n))
                                   (reset! last-beat (current-beat n))
                                   (seesaw/repaint! beat))
                                 (when (not= @last-tempo (tempo-values n))
                                   (reset! last-tempo (tempo-values n))
                                   (seesaw/repaint! tempo))))
          (catch Exception e
            (timbre/error e "Problem updating player status row")))))
    ;; TODO: Set :visible? based on:
    ;; (set (map #(.getDeviceNumber %) (filter #(instance? CdjStatus %) (.getLatestStatus (VirtualCdj/getInstance)))))
    ;; TODO: Add a custom :paint function
    ;; TODO: Add update listener to repaint elements when play state, etc. change
    row))

(defn- create-player-rows
  "Creates the rows for each visible player in the Media Locations
  window. A value will be delivered to `shutdown-chan` when the window
  is closed, telling the row to unregister any event listeners and
  exit any animation loops."
  [shutdown-chan]
  (map (partial create-player-row shutdown-chan) (range 1 5)))

(defn- make-window-visible
  "Ensures that the Player Status window is centered on the triggers
  window, in front, and shown."
  [trigger-frame]
  (.setLocationRelativeTo @media-window trigger-frame)
  (seesaw/show! @media-window)
  (.toFront @media-window))

(defn- create-window
  "Creates the Media Locations window."
  [trigger-frame globals]
  (try
    (load-fonts)
    (let [shutdown-chan (async/promise-chan)
          root          (seesaw/frame :title "Player Status"
                                      :on-close :dispose)
          players       (seesaw/vertical-panel :id :players)]
      (seesaw/config! root :content players)
      (seesaw/config! players :items (create-player-rows shutdown-chan))
      (seesaw/listen root :window-closed (fn [e] (>!! shutdown-chan :done) (reset! media-window nil)))
      (seesaw/pack! root)
      (.setResizable root false)
      (reset! media-window root)
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating Player Status window."))))

(defn show-window
  "Open the Player Status window if it is not already open."
  [trigger-frame globals editor-fn]
  (locking media-window
    (when-not @media-window (create-window trigger-frame globals)))
  (make-window-visible trigger-frame))

(defn update-window
  "If the Player Status window is showing, update it to reflect any
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
