(ns beat-link-trigger.players
  "Provides the user interface for seeing the status of active
  players, as well as creating metadata caches and assigning them to
  particular player slots."
  (:require [beat-link-trigger.track-loader :as track-loader]
            [beat-link-trigger.util :as util]
            [clojure.core.async :as async :refer [<! >!!]]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import beat_link_trigger.tree_node.IPlaylistEntry
           [java.awt Color Font GraphicsEnvironment RenderingHints]
           java.awt.event.WindowEvent
           javax.imageio.ImageIO
           javax.swing.JFileChooser
           [javax.swing.tree DefaultMutableTreeNode DefaultTreeModel TreeNode]
           [org.deepsymmetry.beatlink CdjStatus CdjStatus$TrackSourceSlot CdjStatus$TrackType
            DeviceAnnouncementListener DeviceFinder DeviceUpdate LifecycleListener MediaDetailsListener VirtualCdj]
           [org.deepsymmetry.beatlink.data AlbumArtListener ArtFinder MetadataCache MetadataCacheCreationListener
            MetadataCacheListener MetadataFinder MountListener SearchableItem SlotReference TimeFinder
            TrackMetadataListener WaveformDetailComponent WaveformFinder WaveformPreviewComponent]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to view player state
  and create and assign metadata caches to player slots."}
  player-window (atom nil))

(defonce ^{:private true
           :doc "Controls whether the window should show waveform
  details. Consulted at creation time."}
  should-show-details (atom true))

(defn show-details
  "Controls whether the window should show waveform details. Used when
  creating the window, so if you want to have a new value take effect,
  you will need to close and reopen the window after changing it."
  [show]
  (reset! should-show-details (boolean show)))

(defonce ^{:doc "Stores the top left corner of where the user moved
  the window the last time it was used, so it can be positioned in the
  same place the next time it is opened."}
  window-position (atom nil))

(def device-finder
  "The object that tracks the arrival and departure of devices on the
  DJ Link network."
  (DeviceFinder/getInstance))

(def virtual-cdj
  "The object which can obtained detailed player status information."
  (VirtualCdj/getInstance))

(def metadata-finder
  "The object that can obtain track metadata."
  (MetadataFinder/getInstance))

(def waveform-finder
  "The object that can obtain track waveform information."
  (WaveformFinder/getInstance))

(def time-finder
  "The object that can obtain detailed track time information."
  (TimeFinder/getInstance))

(def art-finder
  "The object that can obtain album artwork."
  (ArtFinder/getInstance))

(defn- sending-status?
  "Checks whether we are currently sending status packets, which is
  required to reliably obtain metadata for non-rekordbox tracks."
  []
  ((resolve 'beat-link-trigger.triggers/real-player?)))

(def magnify-cursor
  "A custom cursor that indicates something can be magnified."
  (delay (.createCustomCursor (java.awt.Toolkit/getDefaultToolkit)
                              (.getImage (seesaw/icon "images/Magnify-cursor.png"))
                              (java.awt.Point. 6 6)
                              "Magnify")))

(defonce ^{:private true
           :doc "Keeps track of whether we have already warned about
  media we can't get without using a standard player number."}
  report-given (atom false))

(defn- report-limited-metadata
  "Displays a warning explaining that you must use a standard player
  number to get metadata for the kind of track loaded on a player. We
  only do this once per session per type of media to avoid annoying
  people."
  [parent player]
  (when (compare-and-set! report-given false true)
    (seesaw/invoke-later
     (seesaw/alert parent
                   (str "<html>We can't get Title and Aritst information for the non-rekordbox<br>"
                        "track in Player " player " unless you enable <strong>Use Real Player Number?</strong><br>"
                        "in the <strong>Network</strong> menu.</html>")
                   :title "Beat Link Trigger isn't using a real Player Number" :type :warning))))

(defn create-metadata-cache
  "Downloads metadata for the specified player and media slot,
  creating a cache in the specified file. If `playlist-id` is
  supplied (and not zero), only the playlist with that ID will be
  downloaded, otherwise all tracks will be downloaded. Provides a
  progress bar during the download process, and allows the user to
  cancel it. Once the cache file is created, it is automatically
  attached."
  ([player slot file]
   (create-metadata-cache player slot file 0))
  ([player slot file playlist-id]
   (let [continue? (atom true)
         slot-ref  (SlotReference/getSlotReference player slot)
         progress  (seesaw/progress-bar :indeterminate? true :min 0 :max 1000)
         latest    (seesaw/label :text "Gathering tracks…")
         panel     (mig/mig-panel
                    :items [[(seesaw/label :text (str "<html>Creating " (if (pos? playlist-id) "playlist" "full")
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
                          (when @continue?  ; We finished without being canceled, so attach the cache
                            (future
                              (try
                                (Thread/sleep 100)  ; Give the file a chance to be closed and flushed
                                (.attachMetadataCache (MetadataFinder/getInstance) slot-ref file)
                                (catch Exception e
                                  (timbre/error e "Problem attaching just-created metadata cache")))))
                          (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING))))
                       @continue?))]
     (seesaw/listen root :window-closed (fn [e] (reset! continue? false)))
     (.pack root)
     (.setLocationRelativeTo root nil)
     (seesaw/show! root)
     (future
       (try
         ;; To load all tracks we pass a playlist ID of 0
         (MetadataCache/createMetadataCache slot-ref playlist-id file listener)
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

(defn- explain-creation-failure
  "Called when the user has asked to create a metadata cache, and
  metadata cannot be requested. Try to explain the issue to the
  user."
  [^Exception e]
  (timbre/error e "Problem Creating Metadata Cache")
  (let [device (.getDeviceNumber (VirtualCdj/getInstance))]
    (if (> device 4)
      (seesaw/alert (str "<html>Beat Link Trigger is using device number " device ". "
                         "To collect metadata<br>from the current players, "
                         "it needs to use number 1, 2, 3, or 4.<br>"
                         "Please use the <strong>Network</strong> menu in the "
                         "<strong>Triggers</strong> window to go offline,<br>"
                         "make sure the <strong>Request Track Metadata?</strong> option is checked,<br>"
                         "then go back online and try again."
                         (when (>= (count (util/visible-player-numbers)) 4)
                           (str
                            "<br><br>Since there are currently four real players on the network, you<br>"
                            "will get more reliable results if you are able to turn one of them<br>"
                            "off before coming back online.")))
                    :title "Unable to Request Metadata" :type :error)
      (seesaw/alert (str "<html>Unable to Create Metadata Cache:<br><br>" (.getMessage e)
                         "<br><br>See the log file for more details.")
                     :title "Problem Creating Cache" :type :error))))

(defn show-cache-creation-dialog
  "Presents an interface in which the user can choose which playlist
  to cache and specify the destination file."
  [player slot]
  (seesaw/invoke-later
   (try
     (let [selected-id           (atom nil)
           root                  (seesaw/frame :title (str "Create Metadata Cache for Player " player " "
                                                           (if (= slot CdjStatus$TrackSourceSlot/USB_SLOT) "USB" "SD"))
                                               :on-close :dispose :resizable? false)
           extension             (util/extension-for-file-type :metadata)
           ^JFileChooser chooser (@#'chooser/configure-file-chooser (JFileChooser.)
                                  {:all-files? false
                                   :filters    [["BeatLink metadata cache" [extension]]]})
           heading               (seesaw/label :text "Choose what to cache and where to save it:")
           tree                  (seesaw/tree :model (DefaultTreeModel. (build-playlist-nodes player slot) true)
                                              :root-visible? false)
           speed                 (seesaw/checkbox :text "Performance Priority (cache slowly to avoid playback gaps)")
           panel                 (mig/mig-panel :items [[heading "wrap, align center"]
                                                        [(seesaw/scrollable tree) "grow, wrap"]
                                                        [speed "wrap, align center"]
                                                        [chooser]])
           failed                (atom false)
           ready-to-save?        (fn []
                                   (or (some? @selected-id)
                                       (seesaw/alert "You must choose a playlist to save or All Tracks."
                                                     :title "No Cache Source Chosen" :type :error)))]
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
       (try
         (.expandRow tree 1)
         (catch IllegalStateException e
           (explain-creation-failure e)
           (reset! failed true)))

       (when-let [[file-filter _] (seq (.getChoosableFileFilters chooser))]
         (.setFileFilter chooser file-filter))
       (.setDialogType chooser JFileChooser/SAVE_DIALOG)
       (seesaw/listen chooser
                      :action-performed
                      (fn [action]
                        (if (= (.getActionCommand action) JFileChooser/APPROVE_SELECTION)
                          (when (ready-to-save?)  ; Ignore the save attempt if no playlist chosen.
                            (@#'chooser/remember-chooser-dir chooser)
                            (when-let [file (util/confirm-overwrite-file (.getSelectedFile chooser) extension nil)]
                              (MetadataCache/setCachePauseInterval (if (seesaw/value speed) 1000 50))
                              (seesaw/invoke-later (create-metadata-cache player slot file @selected-id)))
                            (.dispose root))
                          (.dispose root))))  ; They chose cancel.
       (seesaw/config! root :content panel)
       (seesaw/pack! root)
       (.setLocationRelativeTo root nil)
       (if @failed
         (.dispose root)
         (seesaw/show! root)))
     (catch Exception e
       (timbre/error e "Problem Creating Metadata Cache")
       (seesaw/alert (str "<html>Unable to Create Metadata Cache:<br><br>" (.getMessage e)
                          "<br><br>See the log file for more details.")
                     :title "Problem Creating Cache" :type :error)))))

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
    (.setFont g (util/get-display-font :orbitron Font/PLAIN 12))
    (let [frc    (.getFontRenderContext g)
          bounds (.getStringBounds (.getFont g) "Player" frc)]
      (.drawString g "Player" (float (- center (/ (.getWidth bounds) 2.0))) (float (+ (.getHeight bounds) 2.0))))
    (.setFont g (util/get-display-font :orbitron Font/BOLD 42))
    (let [frc    (.getFontRenderContext g)
          num    (str n)
          bounds (.getStringBounds (.getFont g) num frc)]
      (.drawString g num (float (- center (/ (.getWidth bounds) 2.0))) (float (- h 5.0))))))

(defn- on-air?
  "Returns `true` if the specified player can be determined to be
  on the air right now."
  [n]
  (and (.isRunning virtual-cdj)
       (when-let [^CdjStatus status (.getLatestStatusFor virtual-cdj (int n))]
         (.isOnAir status))))

(def near-black
  "A gray that is close to black."
  (Color. 20 20 20))

(defn- paint-on-air
  "Draws an indication of whether the player being monitored by a row
  has reported itself as being on the air. Arguments are the player
  number, the component being drawn, and the graphics context in which
  drawing is taking place."
  [n c g]
  (let [w       (double (seesaw/width c))
        center  (/ w 2.0)
        h       (double (seesaw/height c))
        outline (java.awt.geom.RoundRectangle2D$Double. 1.0 1.0 (- w 2.0) (- h 2.0) 10.0 10.0)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if (on-air? n) Color/RED near-black))
    (.draw g outline)
    (.setFont g (util/get-display-font :teko Font/BOLD 18))
    (let [frc    (.getFontRenderContext g)
          bounds (.getStringBounds (.getFont g) "On Air" frc)]
      (.drawString g "On Air" (float (- center (/ (.getWidth bounds) 2.0))) (float (- h 4.0))))))

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

(defn- pre-nexus
  "Checks whether the specified player number seems to be an older,
  pre-nexus player, for which we cannot obtain time information."
  [n]
  (when-let [u (try
                 (.getLatestStatusFor virtual-cdj n)
                 (catch Exception e))]  ; Absorb exceptions when virtual-cdj shuts down because we went offline.
    (.isPreNexusCdj u)))

(defn- paint-time
  "Draws time information for a player. Arguments are player number, a
  boolean flag indicating we are drawing remaining time, the component
  being drawn, and the graphics context in which drawing is taking
  place."
  [n remain c g]
  (let [played     (time-played n)
        ms         (when (and played (>= played 0)) (if remain (time-left n played) played))
        min        (format-time (when ms (/ ms 60000)))
        sec        (format-time (when ms (/ (mod ms 60000) 1000)))
        half-frame (when ms (mod (org.deepsymmetry.beatlink.Util/timeToHalfFrame ms) 150))
        frame      (format-time (when half-frame (/ half-frame 2)))
        frac-frame (if half-frame (if (even? half-frame) "0" "5") "-")]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setPaint g (if remain (Color. 255 200 200) (Color/WHITE)))
    (.setFont g (util/get-display-font :teko Font/PLAIN 16))
    (.drawString g (if remain "Remain" "Time") (int 4) (int 16))
    (if (and (not ms) (pre-nexus n))
        (do  ; Report that we can't display time information, as this is a pre-nexus device.
          (.setFont g (util/get-display-font :teko Font/PLAIN 19))
          (.drawString g "[Pre-nexus, no data.]" (int 2) (int 37)))
      (do ; Normal time display.
        (.setFont g (util/get-display-font :segment Font/PLAIN 20))
        (.drawString g min (int 2) (int 40))
        (.fill g (java.awt.geom.Rectangle2D$Double. 42.0 34.0 2.0 3.0))
        (.fill g (java.awt.geom.Rectangle2D$Double. 42.0 24.0 2.0 3.0))
        (.drawString g sec (int 45) (int 40))
        (.fill g (java.awt.geom.Rectangle2D$Double. 84.0 34.0 2.0 3.0))
        (.fill g (java.awt.geom.Rectangle2D$Double. 84.0 24.0 2.0 3.0))
        (.drawString g frame (int 87) (int 40))
        (.setFont g (util/get-display-font :teko Font/BOLD 10))
        (.drawString g "M" (int 34) (int 40))
        (.drawString g "S" (int 77) (int 40))
        (.drawString g "F" (int 135) (int 40))
        (.fill g (java.awt.geom.Rectangle2D$Double. 120.0 37.0 2.0 3.0))
        (.setFont g (util/get-display-font :segment Font/PLAIN 16))
        (.drawString g frac-frame (int 122) (int 40))))))

(defn- tempo-values
  "Look up the current playback pitch percentage and effective tempo
  for the specified player, returning them when available as a vector
  followed by boolean indications of whether the device is the
  current tempo master and whether it is synced."
  [n]
  (when-let [^DeviceUpdate u (when (.isRunning time-finder) (.getLatestUpdateFor time-finder n))]
    [(org.deepsymmetry.beatlink.Util/pitchToPercentage (.getPitch u))
     (when (not= 65535 (.getBpm u)) (.getEffectiveTempo u))  ; Detect when tempo is not valid
     (.isTempoMaster u)
     (true? (when-let [cdj-status (.getLatestStatusFor virtual-cdj n)] (.isSynced cdj-status)))]))

(defn- format-pitch
  "Formats a number for display as a pitch value, with one or two
  decimal places (and always using period as the decimal point, since
  that is the only separator available in the DSEG7 font)."
  [pitch]
  (let [format-string (if (< pitch 20.0) "%5.2f" "%5.1f")]
    (String/format java.util.Locale/ROOT format-string (to-array [pitch]))))

(defn format-tempo
  "Formats a tempo for display, showing an unloaded track as \" --.-\",
  and always using period as the decimal point, since that is the only
  separator available in the DSEG7 font."
  [tempo]
  (if (nil? tempo)
    " --.-"
    (String/format java.util.Locale/ROOT "%5.1f" (to-array [tempo]))))

(defn- paint-tempo
  "Draws tempo information for a player. Arguments are player number,
  the component being drawn, and the graphics context in which drawing
  is taking place."
  [n c g]
  (when-let [[pitch tempo master synced] (tempo-values n)]
    (let [abs-pitch       (Math/abs pitch)]
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setPaint g (Color/WHITE))
      (.setFont g (util/get-display-font :teko Font/PLAIN 16))
      (.drawString g "Tempo" (int 4) (int 16))
      (.setFont g (util/get-display-font :teko Font/BOLD 20))
      (.drawString g (if (< abs-pitch 0.025) " " (if (neg? pitch) "-" "+")) (int 2) (int 38))
      (.setFont g (util/get-display-font :segment Font/PLAIN 16))
      (.drawString g (clojure.string/replace (format-pitch abs-pitch) " " "!") (int 4) (int 40))
      (.setFont g (util/get-display-font :teko Font/PLAIN 14))
      (.drawString g "%" (int 56) (int 40)))
    (when synced
      (let [frame (java.awt.geom.RoundRectangle2D$Double. 40.0 4.0 24.0 15.0 4.0 4.0)]
        (.fill g frame)
        (.setPaint g Color/BLACK)
        (.setFont g (util/get-display-font :teko Font/PLAIN 14))
        (.drawString g "SYNC" (int 42) (int 16))))
    (.setPaint g (if master Color/ORANGE Color/WHITE))
    (let [frame        (java.awt.geom.RoundRectangle2D$Double. 68.0 1.0 50.0 38.0 8.0 8.0)
          clip         (.getClip g)
          tempo-string (clojure.string/replace (format-tempo tempo) " " "!")]
      (.draw g frame)
      (.setFont g (util/get-display-font :teko Font/PLAIN 14))
      (if master
        (do (.setClip g frame)
            (.fill g (java.awt.geom.Rectangle2D$Double. 68.0 27.0 50.0 13.0))
            (.setColor g (Color/BLACK))
            (.drawString g "MASTER" (int 77) (int 38))
            (.setClip g clip))
        (.drawString g "BPM" (int 97) (int 36)))
      (.setFont g (util/get-display-font :segment Font/PLAIN 16))
      (.setColor g (if master Color/ORANGE Color/WHITE))
      (.drawString g (subs tempo-string 0 4) (int 68) (int 22))
      (.setFont g (util/get-display-font :segment Font/PLAIN 12))
      (.drawString g (subs tempo-string 4) (int 107) (int 22)))))

(defn generic-media-image
  "Return an image that can be used as generic album artwork for the
  type of media being played in the specified player number."
  [n]
  (let [^CdjStatus status (.getLatestStatusFor virtual-cdj n)]
    (if (nil? status)
      (ImageIO/read (clojure.java.io/resource "images/NoTrack.png"))
      (if (= CdjStatus$TrackType/CD_DIGITAL_AUDIO (.getTrackType status))
        (ImageIO/read (clojure.java.io/resource "images/CDDAlogo.png"))
        (util/case-enum (.getTrackSourceSlot status)

          CdjStatus$TrackSourceSlot/CD_SLOT
          (ImageIO/read (clojure.java.io/resource "images/CD_data_logo.png"))

          CdjStatus$TrackSourceSlot/COLLECTION
          (ImageIO/read (clojure.java.io/resource "images/Collection_logo.png"))

          CdjStatus$TrackSourceSlot/NO_TRACK
          (ImageIO/read (clojure.java.io/resource "images/NoTrack.png"))

          CdjStatus$TrackSourceSlot/SD_SLOT
          (ImageIO/read (clojure.java.io/resource "images/SD.png"))

          CdjStatus$TrackSourceSlot/USB_SLOT
          (ImageIO/read (clojure.java.io/resource "images/USB.png"))

          (ImageIO/read (clojure.java.io/resource "images/UnknownMedia.png")))))))

(defn- paint-art
  "Draws the album art for a player. Arguments are player number, the
  component being drawn, and the graphics context in which drawing is
  taking place."
  [n c g]
  (if-let [art (try (when (.isRunning art-finder) (.getLatestArtFor art-finder (int n)))
                      (catch Exception e
                        (timbre/error e "Problem requesting album art to draw player row, leaving blank.")))]
    (if-let [image (.getImage art)]
      (.drawImage g image 0 0 nil)
      (when-let [image (generic-media-image n)]  ; No image found, try drawing a generic substitute.
      (.drawImage g image 0 0 80 80 nil)))
    (when-let [image (generic-media-image n)]  ; No actual art found, try drawing a generic substitute.
      (.drawImage g image 0 0 80 80 nil))))

(defn- no-players-found
  "Returns true if there are no visible players for us to display."
  []
  (empty? (filter #(<= 1 (.getNumber %) 4) (.getCurrentDevices device-finder))))

(defn- extract-label
  "Given a `SearchableItem` from track metadata, extracts the string
  label. If passed `nil` simply returns `nil`."
  [^SearchableItem item]
  (when item
    (.label item)))

(defonce ^{:private true
           :doc "Holds any waveform detail windows that the user has open,
  indexed by player number."}

  waveform-windows (atom {}))

(defn open-waveform-window
  "Creates a standalone, resizable window displaying a player waveform,
  given a player number.."
  [n parent]
  (if-let [existing (get @waveform-windows n)]
    (.toFront existing)  ; Already open, just bring to front.
    (let [key         (keyword (str "waveform-detail-" n))  ; Not open yet, so create.
          wave        (WaveformDetailComponent. n)
          zoom-slider (seesaw/slider :id :zoom :min 1 :max 32 :value 2
                                     :listen [:state-changed #(.setScale wave (seesaw/value %))])
          zoom-panel  (seesaw/border-panel :background "#000"
                                           :center zoom-slider :east (seesaw/label :text " Zoom "))
          panel       (seesaw/border-panel :background "#000" :north zoom-panel :center wave)
          root        (seesaw/frame :title (str "Player " n " Waveform Detail")
                                    :on-close :dispose
                                    :width 600 :height 200
                                    :content panel)]
      (swap! waveform-windows assoc n root)
      (.setScale wave 2)
      (util/restore-window-position root key parent)
      (seesaw/listen root :component-moved (fn [e] (util/save-window-position root key)))
      (seesaw/listen root :window-closed (fn [e] (swap! waveform-windows dissoc n)))
      (seesaw/show! root)
      (.toFront root))))

(defn- update-metadata-labels
  "Updates the track title and artist name when the metadata has
  changed. Let the user know if they are not going to be able to get
  metadata without using a standard player number if this is the first
  time we have seen the kind of track for which that is an issue."
  [metadata player title-label artist-label]
  (seesaw/invoke-soon
   (if metadata
     (do (seesaw/config! title-label :text (or (util/remove-blanks (.getTitle metadata)) "[no title]"))
         (seesaw/config! artist-label :text (or (util/remove-blanks (extract-label (.getArtist metadata)))
                                                "[no artist]")))
     (let [status (when (.isRunning virtual-cdj) (.getLatestStatusFor virtual-cdj (int player)))
           title (if (= CdjStatus$TrackType/NO_TRACK (when status (.getTrackType status)))
                   "[no track loaded]"
                   "[no track metadata available]")]
       (seesaw/config! title-label :text title)
       (seesaw/config! artist-label :text "n/a")
       (when (and status
                  (not (#{CdjStatus$TrackType/NO_TRACK CdjStatus$TrackType/REKORDBOX} (.getTrackType status)))
                  (not (sending-status?)))
         (report-limited-metadata @player-window player))))))

(defn- suggest-updating-cache
  "Warn the user that the cache they have just attached lacks media details."
  []
  (seesaw/alert @player-window (str "<html>This metadata cache file was created by an older version of<br>"
                                    "Beat Link Trigger, and has no media details recorded in it.<br>"
                                    "It was attached successfully, but there is no way to be sure<br>"
                                    "it came from the same media, or that it is not outdated.<br><br>"
                                    "The sooner you can re-create it using the current version,<br>"
                                    "the more reliably you can use cached metadata.")
                :title "Cache is Missing Media Details" :type :warning))

(defn- slot-popup
  "Returns the actions that should be in a popup menu for a particular
  player media slot. Arguments are the player number, slot
  keyword (`:usb` or `:sd`), and the event triggering the popup."
  [n slot e]
  (when (seesaw/config e :enabled?)
    (let [slot           (case slot
                           :usb CdjStatus$TrackSourceSlot/USB_SLOT
                           :sd  CdjStatus$TrackSourceSlot/SD_SLOT)
          slot-reference (SlotReference/getSlotReference (int n) slot)
          rekordbox?     (when-let [details (.getMediaDetailsFor metadata-finder slot-reference)]
                           (= CdjStatus$TrackType/REKORDBOX (.mediaType details)))
          extension      (util/extension-for-file-type :metadata)]
      (filter identity
              [(seesaw/action :handler (fn [_] (track-loader/show-dialog slot-reference))
                              :name "Load Track from Here on a Player")
               (when rekordbox?
                 (seesaw/action :handler (fn [_] (show-cache-creation-dialog n slot))
                                :name "Create Metadata Cache File"))
               (seesaw/separator)
               (when (.getMetadataCache metadata-finder slot-reference)
                 (seesaw/action :handler (fn [_] (.detachMetadataCache metadata-finder slot-reference))
                                :name "Detach Metadata Cache File"))
               (when rekordbox?
                 (seesaw/action :handler (fn [e]
                                           (when-let [file (chooser/choose-file
                                                            @player-window
                                                            :all-files? false
                                                            :filters [["BeatLink metadata cache" [extension]]])]
                                             (try
                                               (.attachMetadataCache metadata-finder slot-reference file)
                                               (let [cache (.getMetadataCache metadata-finder slot-reference)]
                                                 (when (nil? (.-sourceMedia cache))
                                                   (suggest-updating-cache)))
                                               (catch Exception e
                                                 (timbre/error e "Problem attaching" file)
                                                 (seesaw/alert (str "<html>Unable to Attach Metadata Cache.<br><br>"
                                                                    (.getMessage e)
                                                                    "<br><br>See the log file for more details.")
                                                               :title "Problem Attaching File" :type :error)))))
                                :name "Attach Metadata Cache File"))]))))

(defn- describe-cache
  "Format information about an attached cache file that is short
  enough to fit in the window."
  [^MetadataCache cache]
  (str "Cached" (when (pos? (.-sourcePlaylist cache)) " (playlist)") ": "
       (.getName (clojure.java.io/file (.getName cache))) ", "
       (.-trackCount cache) " tracks"))

(defn- warn-about-stale-cache
  "Warn the user that a cache that has just attached seems outdated."
  [zip-file media-details]
  (let [raw-file (clojure.java.io/file (.getName zip-file))]
    (seesaw/alert @player-window
                  (str "<html>The metadata cache file “" (.getName raw-file) "” that was just attached<br>"
                       "for “" (.name media-details) "” is outdated; the media seems to have changed<br>"
                       "since it was created.<br><br>"
                       "It was attached successfully, but there are likely to be missing tracks.<br><br>"
                       "You should re-create it from the current media as soon as you can.")
                  :title "Metadata Cache is Stale" :type :warning)))

(defn- handle-preview-move
  "Mouse movement listener for a wave preview component; shows a tool
  tip with a cue/loop description if the mouse is hovering over the
  marker for one. Will include the DJ-assigned comment if we have
  found one."
  [preview n e]
  (let [point    (.getPoint e)
        data     (.getLatestMetadataFor metadata-finder n)
        cue-list (when data (.getCueList data))
        cues     (when cue-list (.entries cue-list))
        cue      (last (filter (fn [cue] (.contains (util/cue-preview-indicator-rectangle preview cue) point)) cues))]
    (.setToolTipText preview (when cue (util/describe-cue cue)))))

(defn- media-description
  "Builds a description with as much information as we have available
  about the media mounted in a slot."
  [slot-reference]
  (let [details     (.getMediaDetailsFor metadata-finder slot-reference)
        detail-name (when details (.name details))
        media-name  (or (and (not (clojure.string/blank? detail-name)) detail-name)
                        "Mounted")]
    (str media-name (util/media-contents details))))

(defn- create-player-cell
  "Create a cell for a player, given the shutdown channel and the player
  number this row is supposed to display."
  [shutdown-chan n]
  (let [art            (seesaw/canvas :size [80 :by 80] :opaque? false :paint (partial paint-art n))
        preview        (WaveformPreviewComponent. (int n))
        on-air         (seesaw/canvas :size [55 :by 20] :opaque? false :paint (partial paint-on-air n))
        last-on-air    (atom nil)
        beat           (seesaw/canvas :size [55 :by 5] :opaque? false :paint (partial paint-beat n))
        last-beat      (atom nil)
        player         (seesaw/canvas :size [56 :by 56] :opaque? false :paint (partial paint-player-number n))
        last-playing   (atom nil)
        time           (seesaw/canvas :size [140 :by 40] :opaque? false :paint (partial paint-time n false))
        last-time      (atom nil)
        remain         (seesaw/canvas :size [140 :by 40] :opaque? false :paint (partial paint-time n true))
        last-remain    (atom nil)
        tempo          (seesaw/canvas :size [120 :by 40] :opaque? false :paint (partial paint-tempo n))
        last-tempo     (atom nil)
        title-label    (seesaw/label :text "[track metadata not available]"
                                     :font (util/get-display-font :bitter Font/ITALIC 14) :foreground :yellow)
        artist-label   (seesaw/label :text "" :font (util/get-display-font :bitter Font/BOLD 12) :foreground :green)
        usb-gear       (seesaw/button :id :usb-gear :icon (seesaw/icon "images/Gear-outline.png") :enabled? false
                                      :popup (partial slot-popup n :usb))
        usb-label      (seesaw/label :id :usb-label :text "USB:")
        usb-name       (seesaw/label :id :usb-name :text "Empty")
        sd-gear        (seesaw/button :id :sd-gear :icon (seesaw/icon "images/Gear-outline.png") :enabled? false
                                      :popup (partial slot-popup n :sd))
        sd-label       (seesaw/label :id :sd-label :text "SD:")
        sd-name        (seesaw/label :id :sd-name :text "Empty")
        detail         (when @should-show-details (WaveformDetailComponent. (int n)))
        zoom-slider    (when @should-show-details
                         (seesaw/slider :id :zoom :min 1 :max 32 :value 4
                                        :listen [:state-changed (fn [e]
                                                                  (.setScale detail (seesaw/value e)))]))
        zoom-label     (when @should-show-details (seesaw/label :id :zoom-label :text "Zoom"))
        row            (mig/mig-panel
                        :id (keyword (str "player-" n))
                        :background (Color/BLACK)
                        :items (concat [[title-label "width 340!, push, span 3"]
                                        [art "right, spany 4, wrap, hidemode 2"]
                                        [artist-label "width 340!, span 3, wrap unrelated"]
                                        [usb-gear "split 2, right"] [usb-label "right"]
                                        [usb-name "width 280!, span 2, wrap"]
                                        [sd-gear "split 2, right"] [sd-label "right"]
                                        [sd-name "width 280!, span 2, wrap"]]
                                       (when @should-show-details
                                         [[zoom-slider "span 4, grow, split 2"] [zoom-label "wrap"]
                                          [detail "span, grow, wrap, hidemode 3"]])
                                        [[on-air "flowy, split 2, bottom"]
                                         [beat "bottom"] [time ""] [remain ""] [tempo "wrap"]
                                         [player "left, bottom"]
                                         [preview "width 408!, height 56!, right, bottom, span"]]))
        md-listener    (reify TrackMetadataListener
                         (metadataChanged [this md-update]
                           (when (= n (.player md-update))
                             (update-metadata-labels (.metadata md-update) n title-label artist-label)
                             (seesaw/repaint! art))))  ; In case we still have no art but need a new generic image.
        art-listener   (reify AlbumArtListener
                         (albumArtChanged [this art-update]
                           (when (= n (.player art-update))
                             (seesaw/repaint! art))))
        slot-elems     (fn [slot-reference]
                         (when (= n (.player slot-reference))
                           (util/case-enum (.slot slot-reference)
                             CdjStatus$TrackSourceSlot/USB_SLOT [usb-gear usb-name]
                             CdjStatus$TrackSourceSlot/SD_SLOT [sd-gear sd-name]
                             nil)))
        mount-listener (reify
                         MountListener
                         (mediaMounted [this slot-reference]
                           (let [[button label] (slot-elems slot-reference)]
                             (when button
                               (seesaw/invoke-later
                                (seesaw/config! label :text (media-description slot-reference))
                                (seesaw/config! button :icon (seesaw/icon "images/Gear-outline.png") :enabled? true)))))
                         (mediaUnmounted [this slot-reference]
                           (let [[button label] (slot-elems slot-reference)]
                             (when button
                               (seesaw/invoke-soon
                                (seesaw/config! button :icon (seesaw/icon "images/Gear-outline.png") :enabled? false)
                                (seesaw/config! label :text "Empty")))))

                         MediaDetailsListener
                         (detailsAvailable [this details]
                           (let [slot-reference (.-slotReference details)
                                 [_ label] (slot-elems slot-reference)]
                             (when label
                               (seesaw/invoke-later
                                (seesaw/config! label :text (media-description slot-reference)))))))
        cache-listener (reify MetadataCacheListener
                         (cacheAttached [this slot-reference cache]
                           (let [[button label] (slot-elems slot-reference)]
                             (when button
                               (seesaw/invoke-soon
                                (seesaw/config! button :icon (seesaw/icon "images/Gear-icon.png") :enabled? true)
                                (seesaw/config! label :text (describe-cache cache))
                                (let [cache-details   (.-sourceMedia cache)
                                      current-details (.getMediaDetailsFor metadata-finder slot-reference)]
                                  (when (and cache-details current-details (.hasChanged current-details cache-details))
                                    (warn-about-stale-cache cache current-details)))))))
                         (cacheDetached [this slot-reference]
                           (let [[button label] (slot-elems slot-reference)]
                             (when button
                               (seesaw/invoke-soon
                                (seesaw/config! button :icon (seesaw/icon "images/Gear-outline.png") :enabled? true)
                                (seesaw/config! label :text (media-description slot-reference)))))))]

    ;; Display the magnify cursor over the waveform detail component,
    ;; and open a standalone window on the waveform when it is clicked.
    (.setCursor detail @magnify-cursor)
    (seesaw/listen detail :mouse-clicked (fn [e] (open-waveform-window n detail)))

    ;; Show the slot cache popup menus on ordinary mouse presses on the buttons too.
    (seesaw/listen usb-gear
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (slot-popup n :usb e))]
                                      (util/show-popup-from-button usb-gear popup e))))
    (seesaw/listen sd-gear
                   :mouse-pressed (fn [e]
                                    (let [popup (seesaw/popup :items (slot-popup n :sd e))]
                                      (util/show-popup-from-button sd-gear popup e))))

    ;; Show tooltips for cue/loop markers in the track preview.
    (seesaw/listen preview :mouse-moved (fn [e] (handle-preview-move preview n e)))

    ;; Set up all our listeners to automatically update the interface when the environment changes.
    (.addTrackMetadataListener metadata-finder md-listener)  ; React to metadata changes.
    (.addAlbumArtListener art-finder art-listener)  ; React to artwork changes.
    (.addMountListener metadata-finder mount-listener)  ; React to media mounts and ejection.
    (.addCacheListener metadata-finder cache-listener)  ; React to metadata cache changes.

    ;; Set the initial state of the interface.
    (when detail (.setScale detail (seesaw/value zoom-slider)))
    (update-metadata-labels (.getLatestMetadataFor metadata-finder (int n)) n title-label artist-label)
    (doseq [slot-reference (.getMountedMediaSlots metadata-finder)]
      (.mediaMounted mount-listener slot-reference)
      (when-let [cache (.getMetadataCache metadata-finder slot-reference)]
        (.cacheAttached cache-listener slot-reference cache)))

    (async/go  ; Arrange to clean up when the window closes.
      (<! shutdown-chan)  ; Parks until the window is closed.
      (.removeTrackMetadataListener metadata-finder md-listener)
      (.removeAlbumArtListener art-finder art-listener)
      (.removeMountListener metadata-finder mount-listener)
      (.removeCacheListener metadata-finder cache-listener)
      (.setMonitoredPlayer preview (int 0))
      (when detail (.setMonitoredPlayer detail (int 0))))
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
                                   (seesaw/repaint! [time]))
                                 (let [now-remaining (when-let [played (time-played n)] (time-left n played))]
                                   (when (not= @last-remain now-remaining)
                                     (reset! last-remain now-remaining)
                                     (seesaw/repaint! [remain])))
                                 (when (not= @last-beat (current-beat n))
                                   (reset! last-beat (current-beat n))
                                   (seesaw/repaint! beat))
                                 (when (not= @last-on-air (on-air? n))
                                   (reset! last-on-air (on-air? n))
                                   (seesaw/repaint! on-air))
                                 (when (not= @last-tempo (tempo-values n))
                                   (reset! last-tempo (tempo-values n))
                                   (seesaw/repaint! tempo))))
          (catch Exception e
            (timbre/error e "Problem updating player status row")))))
    row))

(defn- create-player-cells
  "Creates the cells for each visible player in the Player Status
  window. A value will be delivered to `shutdown-chan` when the window
  is closed, telling the row to unregister any event listeners and
  exit any animation loops. The `no-players` widget should be made
  visible when the last player disappears, and invisible when the
  first one appears, to alert the user what is going on."
  [shutdown-chan]
  (map (partial create-player-cell shutdown-chan) (range 1 5)))

(defn- make-window-visible
  "Ensures that the Player Status window is in front, not too far off
  the screen, and shown."
  [trigger-frame globals]
  (util/restore-window-position @player-window :player-status trigger-frame)
  (seesaw/show! @player-window)
  (.toFront @player-window)
  (.setAlwaysOnTop @player-window (boolean (:player-status-always-on-top @globals))))

(defn build-no-player-indicator
  "Creates a label with a large border that reports the absence of any
  players on the network."
  []
  (let [no-players (seesaw/label :text "No players are currently visible on the network."
                                 :font (util/get-display-font :orbitron Font/PLAIN 16))]
    (.setBorder no-players (javax.swing.border.EmptyBorder. 10 10 10 10))
    no-players))

(defn- update-slot-labels
  "Updates the USB/SD labels of a player cell in case the device is an
  XDJ-XZ, which has two USB slots instead."
  [cell device]
  (seesaw/invoke-soon
   (if (= (.getName device) "XDJ-XZ")
     (do
       (seesaw/value! (seesaw/select cell [:#sd-label]) "USB 1:")
       (seesaw/value! (seesaw/select cell [:#usb-label]) "USB 2:"))
     (do
       (seesaw/value! (seesaw/select cell [:#sd-label]) "SD:")
       (seesaw/value! (seesaw/select cell [:#usb-label]) "USB:")))))

(defn- players-present
  "Builds a grid to contain only the players which are currently
  visible on the netowrk, and if there are none, to contain the
  no-players indicator. If there are two or fewer players, the grid
  will have a single column, otherwise it will have two. This is
  friendlier to the smaller screens that are often available front of
  house.

  Also updates the USB/SD labels in case the device is an XDJ-XZ,
  which has two USB slots instead."
  [grid players no-players]
  (let [visible-players (keep-indexed (fn [index player]
                                        (when-let [device (.getLatestAnnouncementFrom device-finder (inc index))]
                                          (update-slot-labels player device)
                                          player))
                                      players)
        grid (seesaw/grid-panel :id players :columns (if (< (count visible-players) 3) 1 2))]

    (seesaw/config! grid :items (or (seq visible-players) [no-players]))
    grid))

(defonce ^{:doc "Even though this window was never designed to support
  resizing, and so looks terrible when resized, some users came to
  rely on using it in a maximized window. This can be set to `true` in
  the Global Setup Expression to allow them to continue to do so until
  such time as there is more explicit support for resizing that looks
  good."}
  allow-ugly-resizing (atom false))

(defn- create-window
  "Creates the Player Status window."
  [trigger-frame globals]
  (try
    (util/load-fonts)
    (let [shutdown-chan (async/promise-chan)
          root          (seesaw/frame :title "Player Status"
                                      :on-close :dispose)
          grid          (seesaw/grid-panel :id :players :columns 1)
          players       (create-player-cells shutdown-chan)
          no-players    (build-no-player-indicator)
          dev-listener (reify DeviceAnnouncementListener  ; Update the grid contents as players come and go
                         (deviceFound [this _]
                           (seesaw/invoke-later
                            (seesaw/config! root
                                            :content (seesaw/scrollable (players-present grid players no-players)))
                            (seesaw/pack! root)))
                         (deviceLost [this _]
                           (seesaw/invoke-later
                            (seesaw/config! root
                                            :content (seesaw/scrollable (players-present grid players no-players)))
                            (seesaw/pack! root))))
          stop-listener (reify LifecycleListener
                          (started [this sender])  ; Nothing for us to do, we exited as soon a stop happened anyway.
                          (stopped [this sender]  ; Close our window if VirtualCdj gets shut down (we went offline).
                            (seesaw/invoke-later
                             (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))]
      (seesaw/config! root :content (seesaw/scrollable (players-present grid players no-players)))
      (.addDeviceAnnouncementListener device-finder dev-listener)
      (.addLifecycleListener virtual-cdj stop-listener)
      (seesaw/listen root :window-closed (fn [e]
                                           (>!! shutdown-chan :done)
                                           (reset! player-window nil)
                                           (.removeDeviceAnnouncementListener device-finder dev-listener)
                                           (.removeLifecycleListener virtual-cdj stop-listener)
                                           (doseq [detail (vals @waveform-windows)]
                                             (.dispose detail))))
      (seesaw/listen root :component-moved (fn [e] (util/save-window-position root :player-status true)))
      (seesaw/pack! root)
      (.setResizable root @allow-ugly-resizing)
      (reset! player-window root)
      (when-not (.isRunning virtual-cdj) (.stopped stop-listener virtual-cdj)))  ; In case we went offline during setup.
    (catch Exception e
      (timbre/error e "Problem creating Player Status window."))))

(defn show-window
  "Open the Player Status window if it is not already open."
  [trigger-frame globals]
  (locking player-window
    (when-not @player-window (create-window trigger-frame globals)))
  (make-window-visible trigger-frame globals))
