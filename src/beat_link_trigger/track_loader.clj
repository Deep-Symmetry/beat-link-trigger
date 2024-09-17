(ns beat-link-trigger.track-loader
  "Provides the user interface for exploring the menu trees of the
  available media databases, whether obtained from dbserver or Crate
  Digger (either over the network or from a locally mounted media
  filesystem). For tracks obtained from CDJs, we provide an interface
  for loading tracks into players. When working with the local
  filesytem we provide a simple track selection interface so they can
  be added to show windows."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.tree-node]
            [beat-link-trigger.util :as util]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [seesaw.chooser :as chooser]
            [taoensso.timbre :as timbre])
  (:import [beat_link_trigger.tree_node IMenuEntry ISearchEntry]
           [beat_link_trigger.util PlayerChoice]
           [java.awt.event WindowEvent]
           [java.io File]
           [java.util.concurrent.atomic AtomicInteger]
           [javax.swing JButton JComboBox JDialog JFrame JTree]
           [javax.swing.tree DefaultMutableTreeNode DefaultTreeModel TreeNode TreePath]
           [org.deepsymmetry.beatlink CdjStatus CdjStatus$TrackSourceSlot CdjStatus$TrackType
            DeviceAnnouncement DeviceAnnouncementListener DeviceFinder DeviceUpdateListener
            LifecycleListener VirtualCdj]
           [org.deepsymmetry.beatlink.data MenuLoader MetadataFinder MountListener SlotReference]
           [org.deepsymmetry.beatlink.dbserver Message Message$MenuItemType NumberField StringField]
           [org.deepsymmetry.cratedigger Archivist Archivist$ArchiveListener Database Database$PlaylistFolderEntry]
           [org.deepsymmetry.cratedigger.pdb RekordboxPdb$TrackRow RekordboxPdb$AlbumRow
            RekordboxPdb$ArtistRow RekordboxPdb$GenreRow]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to pick a track and
  tell a player to load it."} loader-window
  (atom nil))

(def ^MenuLoader menu-loader
  "A convenient reference to the [Beat Link
  `MenuLoader`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MenuLoader.html)
  singleton."
  (MenuLoader/getInstance))

(def ^DeviceFinder device-finder
  "A convenient reference to the [Beat Link
  `DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
  singleton."
  (DeviceFinder/getInstance))

(def ^VirtualCdj virtual-cdj
  "A convenient reference to the [Beat Link
  `VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
  singleton."
  (VirtualCdj/getInstance))

(def ^MetadataFinder metadata-finder
  "A convenient reference to the [Beat Link
  `MetadataFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html)
  singleton."
  (MetadataFinder/getInstance))

(defn- explain-navigation-failure
  "Called when the user has asked to explore a player's media menus, and
  metadata cannot be requested. Try to explain the issue to the user."
  [^Exception e]
  (timbre/error e "Problem Accessing Player Media Menus")
  (let [device (.getDeviceNumber virtual-cdj)]
    (if (> device 4)
      (seesaw/alert (str "<html>Beat Link Trigger is using device number " device ". "
                         "To access menus<br>from the current players, "
                         "it needs to use number 1, 2, 3, or 4.<br>"
                         "Please use the <strong>Network</strong> menu in the "
                         "<strong>Triggers</strong> window to go offline,<br>"
                         "make sure the <strong>Use Real Player Number?</strong> option is checked,<br>"
                         "then go back online and try again."
                         (when (>= (count (util/visible-player-numbers)) 4)
                           (str
                            "<br><br>Since there are currently four real players on the network, you<br>"
                            "will get more reliable results if you are able to turn one of them<br>"
                            "off before coming back online.")))
                    :title "Unable to Request Metadata" :type :error)
      (seesaw/alert (str "<html>Unable to Access Player Menus:<br><br>" (.getMessage e)
                         "<br><br>See the log file for more details.")
                     :title "Problem Accessing Player Menus" :type :error))))

(defn- menu-item-kind
  "Looks up the kind of a menu item given the response message
  containing the item. May return `nil` if we do not recognize the
  menu item type code."
  ^Message$MenuItemType [^Message item]
  (.get Message/MENU_ITEM_TYPE_MAP (.getValue ^NumberField (nth (.arguments item) 6))))

(defn- menu-item-label
  "Retrieve the label to be displayed on a menu item given the response
  message containing the item."
  ^String [^Message item]
  (str (.getValue ^StringField (nth (.arguments item) 3))))

(defn- menu-item-label-2
  "Retrieve the seconary label associated with a menu item given the
  response message containing the item."
  ^String [^Message item]
  (str (.getValue ^StringField (nth (.arguments item) 5))))

(defn- menu-item-id
  "Retrieve the primary ID associated with menu item (e.g. the track ID
  for a track entry), given the response message containing the item."
  ^long [^Message item]
  (.getValue ^NumberField (nth (.arguments item) 1)))

(defmulti menu-item-node
  "A multi-method that will create the appropriate kind of menu item
  tree node given the response message that contains it, and the slot
  reference that will allow it to communicate with the appropriate
  dbserver database to load itself or its children."
  (fn [^Message item _] (menu-item-kind item)))

(defn- empty-node
  "Creates an inert child to explain that its parent is empty. Unless a
  custom label is given, uses [Empty]."
  ([]
   (empty-node "[Empty]"))
  ([label]
   (DefaultMutableTreeNode.
    (proxy [Object IMenuEntry] []
      (toString [] label)
      (getId [] (int 0))
      (getSlot [] nil)
      (getTrackType [] nil)
      (loadChildren [_]))
    false)))

(defn- unloaded?
  "Checks whether a node still needs to be loaded (does not yet have its children)."
  [^DefaultMutableTreeNode node]
  (zero? (.getChildCount node)))

;; Creates a menu item node for unrecognized entries.
(defmethod menu-item-node :default unrecognized-item-node
  [^Message item ^SlotReference slot-reference]
  (let [kind (or (menu-item-kind item)
                 (format "0x%x" (.getValue ^NumberField (nth (.arguments item) 6))))]  ; Show number if can't name it.
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (str (menu-item-label item) " [unrecognized (" kind ")" "]"))
       (getId [] (int -1))
       (getSlot [] slot-reference)
       (getTrackType [] nil)
       (loadChildren [_]))
     false)))

(defn- get-parent-list
  "Traverses the parent chain of a node collecting them in a list, to
  reverse the order."
  [^TreeNode node]
  (loop [current (.getParent node)
         result  '()]
    (if (nil? (.getParent current))
      result
      (recur (.getParent current)
             (conj result current)))))

(defn file-track-node
  "Creates a node that represents a track available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another. If `show-artist` is `true` the artist name will be appended
  to the track name (for disambiguation at the top-level track list)."
  [^Database database ^RekordboxPdb$TrackRow track ^SlotReference slot show-artist]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString []
       (let [artist-name (when-let [^RekordboxPdb$ArtistRow artist
                                    (when show-artist (.get (.artistIndex database) (.artistId track)))]
                           (Database/getText (.name artist)))]
         (str (Database/getText (.title track))
              (when-not (str/blank? artist-name)
                (str "—" artist-name)))))
     (getId [] (int (.id track)))
     (getSlot [] slot)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

(defn- mark-if-still-empty
  "If, after loading a node, there are still no elements in it, create a
  marker node to make it clear that it is actually empty, and not just
  still loading."
  [^DefaultMutableTreeNode node]
  (when (unloaded? node)
    (.add node (empty-node))))

(defn- file-tracks-node
  "Creates a node that contains all the tracks available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another."
  [^Database database ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "Tracks")
     (getId [] (int 0))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [title-tracks (.. database trackTitleIndex values)]
           (doseq [track-id title-tracks]
             (.add node (file-track-node database (.get (.trackIndex database) track-id) slot true))))
         (mark-if-still-empty node))))
   true))

(declare file-playlist-node)

(defn file-playlist-folder-node
  "Creates a node that represents a playlist folder available in an
  exported rekordbox database file. Optionally has an associated slot
  if it is being used with Crate Digger to load tracks from one player
  onto another."
  [^Database database ^SlotReference slot id folder-name]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] folder-name)
     (getId [] (int id))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [^Database$PlaylistFolderEntry entry (.. database playlistFolderIndex (get id))]
           (when entry
             (if (.isFolder entry)
               (.add node (file-playlist-folder-node database slot (.id entry) (.name entry)))
               (.add node (file-playlist-node database slot (.id entry) (.name entry))))))
         (mark-if-still-empty node))))
   true))

(defn file-playlist-node
  "Creates a node that represents a playlist available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another."
  [^Database database ^SlotReference slot id playlist-name]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] playlist-name)
     (getId [] (int id))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [^long track-id (.. database playlistIndex (get id))]
           (when-let [^RekordboxPdb$TrackRow track (.. database trackIndex (get track-id))]
             (.add node (file-track-node database track slot true))))
         (mark-if-still-empty node))))
   true))

(defn file-history-playlist-node
  "Creates a node that represents a history playlist available in an
  exported rekordbox database file. Optionally has an associated slot
  if it is being used with Crate Digger to load tracks from one player
  onto another."
  [^Database database ^SlotReference slot id playlist-name]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] playlist-name)
     (getId [] (int id))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [^long track-id (.. database historyPlaylistIndex (get id))]
           (when-let [^RekordboxPdb$TrackRow track (.. database trackIndex (get track-id))]
             (.add node (file-track-node database track slot true))))
         (mark-if-still-empty node))))
   true))

(defn file-history-node
  "Creates a node that represents the history folder available in an
  exported rekordbox database file. Optionally has an associated slot
  if it is being used with Crate Digger to load tracks from one player
  onto another."
  [^Database database ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "History")
     (getId [] (int 0))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [[name id] (.historyPlaylistNameIndex database)]
           (.add node (file-history-playlist-node database slot id name)))
         (mark-if-still-empty node))))
   true))

(defn file-album-node
  "Creates a node that represents an album available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another."
  [^Database database ^RekordboxPdb$AlbumRow album ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (Database/getText (.name album)))
     (getId [] (int 0))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [track-id (.. database trackAlbumIndex (get (.id album)))]
           (.add node (file-track-node database (.get (.trackIndex database) track-id) slot true)))
         (mark-if-still-empty node))))
   true))

(defn- file-albums-node
  "Creates a node that contains all the albums available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another."
  [^Database database ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "Albums")
     (getId [] (int 0))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [name-albums (.. database albumNameIndex values)]
           (doseq [album-id name-albums]
             (.add node (file-album-node database (.get (.albumIndex database) album-id) slot))))
         (mark-if-still-empty node))))
   true))

(defn file-artist-node
  "Creates a node that represents an artist available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another."
  [^Database database ^RekordboxPdb$ArtistRow artist ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (Database/getText (.name artist)))
     (getId [] (int 0))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [track-id (.. database trackArtistIndex (get (.id artist)))]
           (.add node (file-track-node database (.get (.trackIndex database) track-id) slot false)))
         (mark-if-still-empty node))))
   true))

(defn- file-artists-node
  "Creates a node that contains all the artists available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another."
  [^Database database ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "Artists")
     (getId [] (int 0))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [name-artists (.. database artistNameIndex values)]
           (doseq [artist-id name-artists]
             (.add node (file-artist-node database (.get (.artistIndex database) artist-id) slot))))
         (mark-if-still-empty node))))
   true))

(defn file-genre-node
  "Creates a node that represents a genre available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another."
  [^Database database ^RekordboxPdb$GenreRow genre ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (Database/getText (.name genre)))
     (getId [] (int 0))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [track-id (.. database trackGenreIndex (get (.id genre)))]
           (.add node (file-track-node database (.get (.trackIndex database) track-id) slot true)))
         (mark-if-still-empty node))))
   true))

(defn- file-genres-node
  "Creates a node that contains all the genres available in an exported
  rekordbox database file. Optionally has an associated slot if it is
  being used with Crate Digger to load tracks from one player onto
  another."
  [^Database database ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "Genres")
     (getId [] (int 0))
     (getSlot [] slot)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [name-genres (.. database genreNameIndex values)]
           (doseq [genre-id name-genres]
             (.add node (file-genre-node database (.get (.genreIndex database) genre-id) slot))))
         (mark-if-still-empty node))))
   true))

(defn- empty-search-node
  "Creates the node that explains what to do when a search has not been started."
  []
  (empty-node "[Select this section, then type text in the search field above to see matches.]"))

(defn- file-search-node
  "Creates a node that kicks off a search of an exported rekordbox
  database file. Optionally has an associated slot if it is being used
  with Crate Digger to load tracks from one player onto another."
  [^Database database ^SlotReference slot]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry ISearchEntry] []
     (toString [] "Search")
     (getId [] (int 0))
     (getSlot []  ; Return a dummy slot even though file searches don't use it because the search UI needs one.
       (or slot (SlotReference/getSlotReference 0 CdjStatus$TrackSourceSlot/NO_TRACK)))
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (.add node (empty-search-node))))
     (getDatabase [] database))
   true))

(defn- add-file-node-children
  "Creates all the nodes that can be used to search a downloaded or
  local rekordbox database file and adds them to the supplied parent
  node. If the database was downloaded from an active player, the
  nodes created will have an associated slot reference so they can be
  used to load tracks onto another player."
  [^Database database ^DefaultMutableTreeNode node ^SlotReference slot]
  (.add node (file-playlist-folder-node database slot 0 "Playlists"))
  (.add node (file-search-node database slot))
  (.add node (file-tracks-node database slot))
  (.add node (file-artists-node database slot))
  (.add node (file-albums-node database slot))
  (.add node (file-genres-node database slot))
  (.add node (file-history-node database slot)))

(defn- attach-file-node-children
  "Given a list of file nodes which have been loaded as a file node's
  children, adds them to the node. If none were found, adds an inert
  child to explain that the node was empty."
  [^DefaultMutableTreeNode node items _]
  (if (seq items)
    (doseq [item items]
      (.add node item))
    (.add node (empty-node))))

(defn report-unrecognized-nodes
  "Tells the user that we don't know what to do with some nodes in the
  menu tree and offer to compose a report about them to help fix that."
  [unrecognized]
  ;; Format a report about each kind of node we did not recognize.
  (let [reports (map (fn [{:keys [^DefaultMutableTreeNode node item]}]
                       (let [entry ^IMenuEntry (.getUserObject node)
                             player (.. entry getSlot player)
                             device (.. device-finder (getLatestAnnouncementFrom player) getDeviceName)
                             menu   (str/join "->" (get-parent-list node))]
                         (str "When loading menu " menu " from device named " device ", don't understand: " item)))
                     unrecognized)]
    (doseq [report reports] (timbre/warn report))  ; First log them.
    (seesaw/invoke-later  ; Then alert the user and ask them to take action.
     (if (menus/mail-supported?)
       ;; Compose an email with all the details.
       (let [body    (str/replace (str/join "\n\n" reports) "\n" "\r\n")
             message (str "While trying to load the menu from the player, a value was received\n"
                          "that we don't know how to handle. Would you like to send the details\n"
                          "to Deep Symmetry to help fix this?\n\n"
                          "If you agree, an email message will be created that you can review\n"
                          "and edit before sending.\n\n"
                          "If you can't send anything right now, please still consider saying \"Yes\"\n"
                          "and saving the email until you can send it, or until you can copy\n"
                          "its content into an Issue that you open on the project's GitHub page.\n\n")
             options (to-array ["Yes" "No"])
             choice (javax.swing.JOptionPane/showOptionDialog
                     nil message "Submit Issue about Unrecognized Menu Items?"
                     javax.swing.JOptionPane/YES_NO_OPTION javax.swing.JOptionPane/WARNING_MESSAGE nil
                     options (aget options 0))]
         (when (zero? choice)
           (menus/report-issue body)))
       ;; Can't compose email, so encourage user to open an issue.
       (seesaw/alert (str "<html>While trying to load the menu from the player, a value was received<br>"
                          "that we don't know how to handle. If you can, please look in the log files<br>"
                          "(using the Help menu) to find the details that were just reported, and copy<br>"
                          "them into an email to james@deepsymmetry.org, or paste them into an issue<br>"
                          "that you open against the project on GitHub (you can access that page from<br>"
                          "the Help menu as well).<br><br>"
                          "If you can do that, it will help us figure out how to fix this.")
                     :title "Unrecognized Menu Items" :type :warning)))))

(defn unrecognized-node?
  "Checks whether the supplied tree node holds an unrecognized menu
  item, so we can gather them and offer to report on them. Returns
  `nil` if the node is recognized, or the menu item type code that was
  not recognized."
  [^DefaultMutableTreeNode node ^Message item]
  (let [^IMenuEntry entry (.getUserObject node)]
    (when (and (= -1 (.getId entry))
               (.contains (str node) " [unrecognized ("))
      (.getValue ^NumberField (nth (.arguments item) 6)))))

(defn- attach-menu-node-children
  "Given a list of menu items which have been loaded as a node's
  children, adds them to the node. If none were found, adds an inert
  child to explain that the node was empty. If `builders` is supplied,
  it a map from menu item type to the function that should be called
  to create that kind of item in the current context (the ALL item is
  always contextual, and Key items mean different things when
  nested)."
  ([^DefaultMutableTreeNode node items ^SlotReference slot-reference]
   (attach-menu-node-children node items slot-reference {}))
  ([^DefaultMutableTreeNode node items ^SlotReference slot-reference builders]
   (if (seq items)
     (let [unrecognized (atom {})]
       (doseq [^Message item items]
         (let [builder (builders (menu-item-kind item) menu-item-node)
               child (builder item slot-reference)]
           (.add node child)
           (when-let [kind (unrecognized-node? child item)]  ; We found an unrecognized item to report on.
             (swap! unrecognized assoc kind {:node child :item item}))))  ; Keep track of only one of each kind.
       (when (seq @unrecognized) (report-unrecognized-nodes (vals @unrecognized))))
     (.add node (empty-node)))))

;; Creates a menu item node for the History menu.
(defmethod menu-item-node Message$MenuItemType/HISTORY_MENU history-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestHistoryMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for the Hot Cue Bank menu.
(defmethod menu-item-node Message$MenuItemType/HOT_CUE_BANK_MENU hot-cue-bank-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]))
   false))

;; Creates a menu item node for a history playlist
(defmethod menu-item-node Message$MenuItemType/HISTORY_PLAYLIST history-playlist-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestHistoryPlaylistFrom menu-loader slot-reference 0 (menu-item-id item))
                               slot-reference))))
   true))

;; Creates a menu item node for the Track menu.
(defmethod menu-item-node Message$MenuItemType/TRACK_MENU track-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTrackMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for the Playlist menu.
(defmethod menu-item-node Message$MenuItemType/PLAYLIST_MENU playlist-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestPlaylistMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for a playlist folder.
(defmethod menu-item-node Message$MenuItemType/FOLDER folder-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestPlaylistItemsFrom metadata-finder
                                                               (.player slot-reference) (.slot slot-reference)
                                                               0 (menu-item-id item) true)
                               slot-reference))))
   true))

;; Creates a menu item node for a Playlist.
(defmethod menu-item-node Message$MenuItemType/PLAYLIST playlist-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node
          (.requestPlaylistItemsFrom metadata-finder (.player slot-reference) (.slot slot-reference)
                                     0 (menu-item-id item) false)
          slot-reference))))
   true))

;; Creates a menu item node for the Artist menu.
(defmethod menu-item-node Message$MenuItemType/ARTIST_MENU artist-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestArtistMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

(defn- create-all-original-artist-albums-node
  "Handles the ALL menu item when listing original artists. Creates an
  appropriate node to implement it."
  [artist-id _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ALBUMS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestOriginalArtistAlbumTrackMenuFrom menu-loader slot-reference 0 artist-id -1) slot-reference))))
   true))

(defn- create-original-artist-node
  "Creates a menu item node for an original artist. Invoked as a
  contextual handler for the ORIGINAL ARTIST item within the remixer
  menu."
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [artist-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestOriginalArtistAlbumMenuFrom menu-loader slot-reference 0 artist-id) slot-reference
            {Message$MenuItemType/ALL (partial create-all-original-artist-albums-node artist-id)})))))
   true))

;; Creates a menu item node for the Original Artist menu.
(defmethod menu-item-node Message$MenuItemType/ORIGINAL_ARTIST_MENU original-artist-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestOriginalArtistMenuFrom menu-loader slot-reference 0) slot-reference
          {Message$MenuItemType/ARTIST create-original-artist-node}))))
   true))

(defn- create-all-remixer-albums-node
  "Handles the ALL menu item when listing remixers. Creates an
  appropriate node to implement it."
  [artist-id _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ALBUMS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestRemixerAlbumTrackMenuFrom menu-loader slot-reference 0 artist-id -1) slot-reference))))
   true))

(defn- create-remixer-node
  "Creates a menu item node for a remixer. Invoked as a contextual
  handler for the ARTIST item within the remixer menu."
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [artist-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestRemixerAlbumMenuFrom menu-loader slot-reference 0 artist-id) slot-reference
            {Message$MenuItemType/ALL (partial create-all-remixer-albums-node artist-id)})))))
   true))

;; Creates a menu item node for the Remixer menu.
(defmethod menu-item-node Message$MenuItemType/REMIXER_MENU remixer-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestRemixerMenuFrom menu-loader slot-reference 0) slot-reference
          {Message$MenuItemType/ARTIST create-remixer-node}))))
   true))

;; Creates a menu item node for the Album menu.
(defmethod menu-item-node Message$MenuItemType/ALBUM_MENU album-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestAlbumMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for the Color menu.
(defmethod menu-item-node Message$MenuItemType/COLOR_MENU color-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestColorMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for the color pink.
(defmethod menu-item-node Message$MenuItemType/COLOR_PINK color-pink-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByColorFrom menu-loader slot-reference 0 1) slot-reference))))
   true))

;; Creates a menu item node for the color red.
(defmethod menu-item-node Message$MenuItemType/COLOR_RED color-red-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByColorFrom menu-loader slot-reference 0 2) slot-reference))))
   true))

;; Creates a menu item node for the color orange.
(defmethod menu-item-node Message$MenuItemType/COLOR_ORANGE color-orange-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByColorFrom menu-loader slot-reference 0 3) slot-reference))))
   true))

;; Creates a menu item node for the color yellow.
(defmethod menu-item-node Message$MenuItemType/COLOR_YELLOW color-yellow-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByColorFrom menu-loader slot-reference 0 4) slot-reference))))
   true))

;; Creates a menu item node for the color green.
(defmethod menu-item-node Message$MenuItemType/COLOR_GREEN color-green-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByColorFrom menu-loader slot-reference 0 5) slot-reference))))
   true))

;; Creates a menu item node for the color aqua.
(defmethod menu-item-node Message$MenuItemType/COLOR_AQUA color-aqua-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByColorFrom menu-loader slot-reference 0 6) slot-reference))))
   true))

;; Creates a menu item node for the color blue.
(defmethod menu-item-node Message$MenuItemType/COLOR_BLUE color-blue-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByColorFrom menu-loader slot-reference 0 7) slot-reference))))
   true))

;; Creates a menu item node for the color purple.
(defmethod menu-item-node Message$MenuItemType/COLOR_PURPLE color-purple-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByColorFrom menu-loader slot-reference 0 8) slot-reference))))
   true))

;; Creates a menu item node for the Bit Rate menu.
(defmethod menu-item-node Message$MenuItemType/BIT_RATE_MENU bit-rate-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestBitRateMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for the Time menu.
(defmethod menu-item-node Message$MenuItemType/TIME_MENU time-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTimeMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for a track duration from Time menu.
(defmethod menu-item-node Message$MenuItemType/DURATION duration-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-id item) " minutes"))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByTimeFrom menu-loader slot-reference 0 (menu-item-id item))
                                    slot-reference))))
   true))

;; Creates a menu item node for the Year menu.
(defmethod menu-item-node Message$MenuItemType/YEAR_MENU year-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestYearMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for all tracks created in a given
;; decade. Invoked as a contextual handler for the ALL item.
(defn- create-all-years-decade-node
  "Handles the ALL menu item when listing genre artist albums. Creates
  an appropriate node to implement it."
  [decade _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL YEARS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByDecadeAndYear menu-loader slot-reference 0 decade -1)
                               slot-reference))))
   true))

;; Creates a menu item node for all tracks created in a given
;; decade and year. Invoked as a contextual handler for the YEAR item.
(defn- create-year-node
  "Handles the ALL menu item when listing genre artist albums. Creates
  an appropriate node to implement it."
  [decade ^Message item ^SlotReference slot-reference]
  (let [year (menu-item-id item)]
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (str year))
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (getTrackType [] nil)
       (loadChildren [^DefaultMutableTreeNode node]
         (when (unloaded? node)
           (attach-menu-node-children node (.requestTracksByDecadeAndYear menu-loader slot-reference 0 decade year)
                                      slot-reference))))
     true)))

;; Creates a menu item node for a decade within the Time menu.
(defmethod menu-item-node Message$MenuItemType/YEAR decade-node
  [^Message item ^SlotReference slot-reference]
  (let [decade (menu-item-id item)]
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (str decade "s"))
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (getTrackType [] nil)
       (loadChildren [^DefaultMutableTreeNode node]
         (when (unloaded? node)
           (attach-menu-node-children node (.requestYearsByDecadeFrom menu-loader slot-reference 0 decade)
                                      slot-reference
                                      {Message$MenuItemType/ALL (partial create-all-years-decade-node decade)
                                       Message$MenuItemType/YEAR (partial create-year-node decade)}))))
     true)))

;; Creates a menu item node for the Genre menu.
(defmethod menu-item-node Message$MenuItemType/GENRE_MENU genre-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestGenreMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

(defn- create-all-genre-artist-albums-node
  "Handles the ALL menu item when listing genre artist albums. Creates
  an appropriate node to implement it."
  [genre-id artist-id _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ALBUMS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestGenreArtistAlbumTrackMenuFrom menu-loader slot-reference 0 genre-id artist-id -1)
          slot-reference))))
   true))

(defn- create-genre-artist-album-node
  "Contextual handler for the ALBUM menu item when listing genre artist
  albums. Creates an appropriate node to implement it."
  [genre-id artist-id ^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [album-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestGenreArtistAlbumTrackMenuFrom menu-loader slot-reference 0 genre-id artist-id album-id)
            slot-reference
            {Message$MenuItemType/ALL (partial create-all-genre-artist-albums-node genre-id artist-id)})))))
   true))

(defn- create-all-genre-artists-node
  "Handles the ALL menu item when listing genre artists. Creates an
  appropriate node to implement it."
  [genre-id _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ARTISTS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestGenreArtistAlbumMenuFrom menu-loader slot-reference 0 genre-id -1) slot-reference
          {Message$MenuItemType/ALBUM_TITLE (partial create-genre-artist-album-node genre-id -1)
           Message$MenuItemType/ALL (partial create-all-genre-artist-albums-node genre-id -1)}))))
   true))

(defn- create-genre-artist-node
  "Contextual handler for the ARTIST menu item when listing genre
  artists. Creates an appropriate node to implement it."
  [genre-id ^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [artist-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestGenreArtistAlbumMenuFrom menu-loader slot-reference 0 genre-id artist-id) slot-reference
            {Message$MenuItemType/ALBUM_TITLE (partial create-genre-artist-album-node genre-id artist-id)
             Message$MenuItemType/ALL (partial create-all-genre-artist-albums-node genre-id artist-id)})))))
   true))

;; Creates a menu item node for a genre.
(defmethod menu-item-node Message$MenuItemType/GENRE genre-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [genre-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestGenreArtistMenuFrom menu-loader slot-reference 0 genre-id) slot-reference
            {Message$MenuItemType/GENRE (partial create-genre-artist-node genre-id)
             Message$MenuItemType/ALL (partial create-all-genre-artists-node genre-id)})))))
   true))

;; Creates a menu item node for the Label menu.
(defmethod menu-item-node Message$MenuItemType/LABEL_MENU label-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestLabelMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

(defn- create-all-label-artist-albums-node
  "Handles the ALL menu item when listing label artist albums. Creates
  an appropriate node to implement it."
  [label-id artist-id _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ALBUMS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestLabelArtistAlbumTrackMenuFrom menu-loader slot-reference 0 label-id artist-id -1)
          slot-reference))))
   true))

(defn- create-label-artist-album-node
  "Contextual handler for the ALBUM menu item when listing label artist
  albums. Creates an appropriate node to implement it."
  [label-id artist-id ^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [album-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestLabelArtistAlbumTrackMenuFrom menu-loader slot-reference 0 label-id artist-id album-id)
            slot-reference
            {Message$MenuItemType/ALL (partial create-all-label-artist-albums-node label-id artist-id)})))))
   true))

(defn- create-all-label-artists-node
  "Handles the ALL menu item when listing label artists. Creates an
  appropriate node to implement it."
  [label-id _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ARTISTS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestLabelArtistAlbumMenuFrom menu-loader slot-reference 0 label-id -1) slot-reference
          {Message$MenuItemType/ALBUM_TITLE (partial create-label-artist-album-node label-id -1)
           Message$MenuItemType/ALL (partial create-all-label-artist-albums-node label-id -1)}))))
   true))

(defn- create-label-artist-node
  "Contextual handler for the ARTIST menu item when listing label
  artists. Creates an appropriate node to implement it."
  [label-id ^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [artist-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestLabelArtistAlbumMenuFrom menu-loader slot-reference 0 label-id artist-id) slot-reference
            {Message$MenuItemType/ALBUM_TITLE (partial create-label-artist-album-node label-id artist-id)
             Message$MenuItemType/ALL (partial create-all-label-artist-albums-node label-id artist-id)})))))
   true))

;; Creates a menu item node for a label.
(defmethod menu-item-node Message$MenuItemType/LABEL label-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [label-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestLabelArtistMenuFrom menu-loader slot-reference 0 label-id) slot-reference
            {Message$MenuItemType/ARTIST (partial create-label-artist-node label-id)
             Message$MenuItemType/ALL (partial create-all-label-artists-node label-id)})))))
   true))

;; Creates a menu item node for all album tracks by an artist. Invoked
;; as a contextual handler for the ALL item.
(defn- create-all-artist-album-tracks-node
  "Handles the ALL menu item when listing artist albums. Creates an
  appropriate node to implement it."
  [artist-id _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL TRACKS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestArtistAlbumTrackMenuFrom menu-loader slot-reference 0 artist-id -1) slot-reference))))
   true))

;; Creates a menu item node for all albums by an artist. Invoked as a
;; contextual handler for the ALL item.
(defn- create-all-artist-albums-node
  "Handles the ALL menu item when listing artists. Creates an
  appropriate node to implement it."
  [artist-id _ ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ALBUMS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children
          node (.requestArtistAlbumMenuFrom menu-loader slot-reference 0 artist-id) slot-reference
          {Message$MenuItemType/ALL (partial create-all-artist-album-tracks-node artist-id)}))))
   true))

;; Creates a menu item node for an artist.
(defmethod menu-item-node Message$MenuItemType/ARTIST artist-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [artist-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestArtistAlbumMenuFrom menu-loader slot-reference 0 artist-id) slot-reference
            {Message$MenuItemType/ALL (partial create-all-artist-albums-node artist-id)})))))
   true))

;; Creates a menu item node for the Key menu.
(defmethod menu-item-node Message$MenuItemType/KEY_MENU key-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestKeyMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for a key neighbor menu; invoked as a
;; contextual handler for the Key item when it is found inside another
;; Key item.
(defn- create-key-neighbor-node
  "Handles the Key menu item when already listing a Key. Creates an
  appropriate node for the list of key neighbors up to a specific
  distance around the circle of fifths."
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (menu-item-label item))
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (getTrackType [] nil)
       (loadChildren [^DefaultMutableTreeNode node]
         (when (unloaded? node)
           (let [distance (.getValue ^NumberField (first (.arguments item)))
                 key-id   (menu-item-id item)]
             (attach-menu-node-children
              node (.requestTracksByKeyAndDistanceFrom menu-loader slot-reference 0 key-id distance) slot-reference)))))
     true))

;; Creates a menu item node for a Key. Will build child Key items as
;; key neighbor nodes.
(defmethod menu-item-node Message$MenuItemType/KEY key-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (let [key-id (menu-item-id item)]
           (attach-menu-node-children
            node (.requestKeyNeighborMenuFrom menu-loader slot-reference 0 key-id) slot-reference
            {Message$MenuItemType/KEY create-key-neighbor-node})))))
   true))

;; Creates a menu item node for a Bit Rate.
(defmethod menu-item-node Message$MenuItemType/BIT_RATE bit-rate-node
  [^Message item ^SlotReference slot-reference]
  (let [bit-rate (menu-item-id item)]
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (str bit-rate " Kbps"))
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (getTrackType [] nil)
       (loadChildren [^DefaultMutableTreeNode node]
         (when (unloaded? node)
           (attach-menu-node-children
            node (.requestTracksByBitRateFrom menu-loader slot-reference 0 bit-rate) slot-reference))))
     true)))

;; Creates a menu item node for the Rating menu.
(defmethod menu-item-node Message$MenuItemType/RATING_MENU rating-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestRatingMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

(defn format-rating
  "Formats a numeric rating as a string of zero through five stars in a
  field of periods."
  [rating]
  (str/join (take 5 (concat (take rating (repeat "*")) (repeat ".")))))

;; Creates a menu item node for a rating node.
(defmethod menu-item-node Message$MenuItemType/RATING rating-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (format-rating (menu-item-id item)))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestTracksByRatingFrom menu-loader slot-reference 0 (menu-item-id item))
                               slot-reference))))
   true))

;; Creates a menu item node for the BPM menu.
(defmethod menu-item-node Message$MenuItemType/BPM_MENU bpm-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestBpmMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

(defn- format-tempo
  "Formats a tempo value, dividing it by 100 and rounding (although they
  always seem to be multiples of 100 in the menus I have seen)."
  [bpm]
  (str (Math/round (/ bpm 100.0))))

;; Creates a menu item node for a tempo range menu; invoked as a
;; contextual handler for the Tempo item when it is found inside
;; another Tempo item.
(defn- create-tempo-range-node
  "Handles the Tempo menu item when already listing a Tempo. Creates an
  appropriate node for the list of tempo ranges up to a specific
  percentage away from the base BPM."
  [tempo ^Message item ^SlotReference slot-reference]
  (let [distance (menu-item-id item)]
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (str (format-tempo tempo) (when-not (zero? distance) (str " +/- " distance "%"))))
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (getTrackType [] nil)
       (loadChildren [^DefaultMutableTreeNode node]
         (when (unloaded? node)
           (attach-menu-node-children node (.requestTracksByBpmRangeFrom menu-loader slot-reference 0 tempo distance)
                                 slot-reference))))
     true)))

;; Creates a menu item node for a tempo. Will build child tempo items
;; as tempo ranges.
(defmethod menu-item-node Message$MenuItemType/TEMPO tempo-node
  [^Message item ^SlotReference slot-reference]
  (let [tempo (menu-item-id item)]
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (format-tempo tempo))
       (getId [] (int tempo))
       (getSlot [] slot-reference)
       (getTrackType [] nil)
       (loadChildren [^DefaultMutableTreeNode node]
         (when (unloaded? node)
           (attach-menu-node-children node (.requestBpmRangeMenuFrom menu-loader slot-reference 0 tempo)
                                 slot-reference
                                 {Message$MenuItemType/TEMPO (partial create-tempo-range-node tempo)}))))
     true)))

;; Creates a menu item node for a filesystem track; invoked as a
;; contextual handler for the Track Title item when it is found inside
;; the Folder menu or a filesystem Folder item.
(defn- create-filesystem-track-node
  "Handles the Track Title menu item when listing the Folder menu or a
  filesystem Folder item. Creates a Track node whose track type is
  \"unanalyzed\" rather than \"rekordbox\"."
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/UNANALYZED)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a filesystem folder; invoked as a
;; contextual handler for the Folder item when it is found inside
;; the Folder menu or another Folder item.
(defn- create-filesystem-folder-node
  "Handles the Folder menu item when listing the Folder menu or another
  filesystem Folder item. Creates an appropriate node for the contents
  of that filesystem folder."
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestFolderMenuFrom menu-loader slot-reference 0 (menu-item-id item))
                               slot-reference
                               {Message$MenuItemType/FOLDER create-filesystem-folder-node
                                Message$MenuItemType/TRACK_TITLE create-filesystem-track-node}))))
   true))

;; Creates a menu item node for the Folder menu. Will build child
;; folder items as filesystem folders, rather than the default
;; playlist folders we otherwise see, and child track items with
;; track types of "unanalyzed" rather than the default rekordbox.
(defmethod menu-item-node Message$MenuItemType/FOLDER_MENU folder-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestFolderMenuFrom menu-loader slot-reference 0 -1) slot-reference
                               {Message$MenuItemType/FOLDER create-filesystem-folder-node
                                Message$MenuItemType/TRACK_TITLE create-filesystem-track-node}))))
   true))

;; Creates a menu item node for the Filename menu.
(defmethod menu-item-node Message$MenuItemType/FILENAME_MENU filename-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestFilenameMenuFrom menu-loader slot-reference 0)
                                    slot-reference))))
   true))

;; Creates a menu item node for an album.
(defmethod menu-item-node Message$MenuItemType/ALBUM_TITLE album-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (attach-menu-node-children node (.requestAlbumTrackMenuFrom menu-loader slot-reference 0 (menu-item-id item))
                               slot-reference))))
   true))

;; Creates a menu item node for a rekordbox track.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE track-title-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track with the album name.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_ALBUM track-title-and-album-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [album (menu-item-label-2 item)] (str "—" album))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track with the artist name.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_ARTIST track-title-and-artist-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [artist (menu-item-label-2 item)] (str "—" artist))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track with BPM (which we ignore).
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_BPM track-title-and-bpm-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track with color (which we ignore).
(defmethod menu-item-node Message$MenuItemType/TRACK_LIST_ENTRY_BY_COLOR track-title-and-color-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track and comment.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_COMMENT track-title-and-comment-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [comment (menu-item-label-2 item)] (str "—" comment))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track and date added.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_DATE_ADDED track-title-and-date-added-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [date (menu-item-label-2 item)] (str "—" date))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track with DJ play count (which we ignore).
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_DJ_PLAY_COUNT track-title-and-play-count-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))
;; Creates a menu item node for a rekordbox track and genre.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_GENRE track-title-and-genre-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [genre (menu-item-label-2 item)] (str "—" genre))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track and key.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_KEY track-title-and-key-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [k (menu-item-label-2 item)] (str "—" k))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track and label.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_LABEL track-title-and-label-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [label (menu-item-label-2 item)] (str "—" label))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track and original artist.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_ORIGINAL_ARTIST track-title-and-original-artist-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [artist (menu-item-label-2 item)] (str "—" artist))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track with bit rate (which we ignore).
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_RATE track-title-and-rate-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track with rating (which we ignore).
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_RATING track-title-and-rating-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track and remixer.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_REMIXER track-title-and-remixer-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [remixer (menu-item-label-2 item)] (str "—" remixer))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a rekordbox track with time (which we ignore).
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_TIME track-title-and-time-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (loadChildren [_]))
   false))




;; Creates a menu item node for the search interface. This special node is also a search entry,
;; and returns nil for getDatabase to indicate dbserver queries should be used to perform the search.
(defmethod menu-item-node Message$MenuItemType/SEARCH_MENU search-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry ISearchEntry] []
     (toString [] (menu-item-label item))
     (getId [] 0)
     (getSlot [] slot-reference)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (.add node (empty-search-node))))
     (getDatabase [] nil))
   true))


(defn- slot-label
  "Assembles the name used to describe a particular player slot, given
  the slot reference."
  [^SlotReference slot-reference]
  (let [raw    (.player slot-reference)
        number (cond
                 (zero? raw) ""
                 (< raw 41)  (bit-and raw 0x0f)
                 :else       (- raw 40))
        kind   (cond
                 (zero? raw)  "File"
                 (< raw 0x10) "Player "
                 (< raw 0x20) "Computer "
                 (> raw 0x28) "Mobile "
                 :else        "Mixer ")
        base   (str kind number
                    (util/case-enum (.slot slot-reference)
                      CdjStatus$TrackSourceSlot/SD_SLOT " SD"
                      CdjStatus$TrackSourceSlot/USB_SLOT " USB"
                      CdjStatus$TrackSourceSlot/COLLECTION " Collection"
                      CdjStatus$TrackSourceSlot/NO_TRACK ""))
        extra  (when-let [details (.getMediaDetailsFor metadata-finder slot-reference)]
                 (str (when-let [name (if (= kind "Computer")
                                        (.. device-finder
                                            (getLatestAnnouncementFrom (.player slot-reference))
                                            getDeviceName)
                                        (.name details))] (str ": " name))
                      (util/media-contents details)))]
    (str base extra)))

(defn- slot-node
  "Creates the tree node that will allow access to the media database in
  a particular player slot."
  [^SlotReference slot-reference]
  (let [label    (slot-label slot-reference)]
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] label)
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (getTrackType [] nil)
       (loadChildren [^DefaultMutableTreeNode node]
         (when (unloaded? node)
           (let [database (.findDatabase (org.deepsymmetry.beatlink.data.CrateDigger/getInstance) slot-reference)]
             (if (and database (not (.getUseStandardPlayerNumber virtual-cdj)))
               (add-file-node-children database node slot-reference)
               (attach-menu-node-children node (.requestRootMenuFrom menu-loader slot-reference 0) slot-reference))))))
     true)))

(defn- root-node
  "Creates the root node that will hold all the available media
  databases, and doesn't have to lazily load anything."
  []
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "Load Track From:")
     (getId [] (int 0))
     (getSlot [] nil)
     (getTrackType [] nil)
     (loadChildren [_]))
   true))

(defn- find-slot-node
  "Searches the tree for the node responsible for talking to the
  specified player slot, returning it if it exists. Must be invoked on
  the Swing event dispatch thread."
  [^JTree tree ^SlotReference slot]
  (let [^TreeNode root (.. tree getModel getRoot)]
    (loop [index 0]
      (when (< index (.getChildCount root))
        (let [^DefaultMutableTreeNode child (.. tree getModel (getChild root index))
              ^IMenuEntry entry             (.getUserObject child)]
          (if (= (.getSlot entry) slot)
            child
            (recur (inc index))))))))

(defn- remove-slot-node
  "Searches the tree for the node responsible for talking to the
  specified player slot, removing it if it exists. If there are no
  slots left to load from, closes the window. Must be invoked on the
  Swing event dispatch thread."
  [^JTree tree ^SlotReference slot ^LifecycleListener stop-listener]
  (when-let [node (find-slot-node tree slot)]
    (.removeNodeFromParent ^DefaultTreeModel (.getModel tree) node)
    (let [model (.getModel tree)]
      (when (zero? (.getChildCount model (.getRoot model)))
        (.stopped stop-listener metadata-finder)))))

(defn- add-slot-node
  "Adds a node responsible for talking to the specified player slot to
  the tree. Must be invoked on the Swing event dispatch thread.
  Ignores slots we don't support."
  [^JTree tree ^SlotReference slot]
  (when (#{CdjStatus$TrackSourceSlot/SD_SLOT
           CdjStatus$TrackSourceSlot/USB_SLOT
           CdjStatus$TrackSourceSlot/COLLECTION} (.slot slot))
    (let [^DefaultMutableTreeNode node (slot-node slot)
          ^DefaultMutableTreeNode root (.. tree getModel getRoot)]
      ;; Find the node we should be inserting the new one in front of, if any.
      (loop [index 0]
        (if (< index (.getChildCount root))
          (let [sibling (.. tree getModel (getChild root index))]
            (if (neg? (.compareTo (str node) (str sibling)))
              (let [^DefaultTreeModel model (.getModel tree)]
                (.insertNodeInto model node root index))  ; Found node we should be in front of.
              (recur (inc index))))
          (let [^DefaultTreeModel model (.getModel tree)]
            (.insertNodeInto model node root index)))))))  ; We go at the end of the root.

(defn- build-media-nodes
  "Create the top-level media database nodes, which will lazily load any
  child menus from the corresponding database server when they are
  expanded."
  [tree mounted-slots]
  (doseq [slot mounted-slots]
    (add-slot-node tree slot)))

(defn- expand-and-select-slot-node
  "Expands and selects the specified tree node, used for positioning the
  user at the right place when they have chosen to load a track from a
  particular media slot."
  [^JTree tree ^DefaultMutableTreeNode node]
  (let [^DefaultTreeModel model (.getModel tree)
        node-path               (TreePath. (to-array [(.getRoot model) node]))]
    (.setSelectionPath tree node-path)
    (.expandPath tree node-path)
    (.scrollPathToVisible tree node-path)))

(defn- trim-to-search-node-path
  "If the supplied tree path belongs to a Search menu entry, returns the
  start of the path which leads to the Search node itself. Otherwise
  returns `nil`."
  ^TreePath [^TreePath path]
  (when (and path (> (.getPathCount path) 1))
    (loop [result path]
      (let [^DefaultMutableTreeNode node (.getLastPathComponent result)]
        (when-let [item (.getUserObject node)]
          (if (instance? ISearchEntry item)
            result
            (when (> (.getPathCount result) 2)
              (recur (.getParentPath result)))))))))

(defn add-device
  "Adds a newly-found player to the destination player combo box,
  keeping entries sorted."
  [^javax.swing.JComboBox players number]
  (when (<= 1 number 4)
    (let [^javax.swing.DefaultComboBoxModel model (.getModel players)
          player                                  (PlayerChoice. number)]
      (loop [index 0]
        (if (< index (.getSize model))
          (let [^PlayerChoice current (.getElementAt model index)]
            (if (< number (.number current))
              (.insertElementAt model player index)  ; Found player we belong in front of.
              (recur (inc index))))
          (.insertElementAt model player index))))))  ; We go at the end of the list.

(defn remove-device
  "Removes a newly-departed player from the destination player combo
  box. If that leaves us with no players, close the window."
  [^javax.swing.JComboBox players number ^LifecycleListener stop-listener]
  (let [^javax.swing.DefaultComboBoxModel model (.getModel players)]
      (loop [index 0]
        (when (< index (.getSize model))
          (let [^PlayerChoice candidate (.getElementAt model index)]
            (if (= number (.number candidate))
              (.removeElementAt model index)
              (recur (inc index)))))))
  (when (zero? (.getItemCount players))  ; No players left, close the window.
    (.stopped stop-listener metadata-finder)))

(defn build-device-choices
  "Sets up the initial content of the destination player combo box."
  [^javax.swing.JComboBox players]
  (doseq [^DeviceAnnouncement announcement (.getCurrentDevices device-finder)]
    (add-device players (.getDeviceNumber announcement)))
  (.setSelectedIndex players 0))

(defn- update-selected-player
  "Event handler for the player choice menu to update the atom tracking
  the selected player state."
  [selected-player e]
  (let [^Long number      (when-let [^PlayerChoice selection (seesaw/selection e)]
                            (.number selection))
        ^CdjStatus status (when number (.getLatestStatusFor virtual-cdj number))]
    (reset! selected-player {:number   number
                             :playing  (and status (.isPlaying status))
                             :cued     (and status (.isCued status))
                             :xdj-xz   (str/starts-with? (.getDeviceName status) "XDJ-XZ")
                             :cdj-3000 (str/starts-with? (.getDeviceName status) "CDJ-3000")})))

(defn- configure-partial-search-ui
  "Show (with appropriate content) or hide the label and buttons
  allowing a partial search to be continued, depending on the search
  state."
  [search-partial search-button total loaded]
  (let [unfinished (boolean (and total (< loaded total)))
        next-size (when unfinished (+ loaded (min 1000 loaded)))]
    (seesaw/config! [search-partial search-button] :visible? unfinished)
    (when unfinished
      (seesaw/text! search-partial (str "Showing " loaded " of " total "."))
      (seesaw/text! search-button (str "Load " (if (< next-size total) next-size "All"))))))

(defn- configure-search-ui
  "Loads the search interface elements from any values that were saved
  for the search the last time it was active."
  [search-label search-field search-partial search-button searches selected-search]
  (let [{:keys [text total ^TreePath path] :or {text ""}} (get @searches selected-search) ; Check saved search config.
        ^DefaultMutableTreeNode node                      (when path (.getLastPathComponent path))]
    (seesaw/text! search-label (str "Search " (slot-label selected-search) ":"))
    (seesaw/text! search-field text)
    (seesaw/invoke-later
     (seesaw/request-focus! search-field))
    (configure-partial-search-ui search-partial search-button total (.getChildCount node))
    (swap! searches assoc :current selected-search)))  ; Record and enable the UI for the new search.

(defn- file-track-matches
  "Return nodes for every track in an exported rekordbox database that
  matches the supplied search text."
  [^Database database ^SlotReference slot text]
  (mapcat (fn [^java.util.Map$Entry title-entry]
            (let [title (.getKey title-entry)]
              (when (str/includes? (str/lower-case title) text)
                (map (fn [track-id]
                       (file-track-node database (.get (.trackIndex database) track-id) slot true))
                     (.getValue title-entry)))))
          (.. database trackTitleIndex entrySet)))

(defn- file-artist-matches
  "Return nodes for every artist in an exported rekordbox database that
  matches the supplied search text."
  [^Database database ^SlotReference slot text]
  (mapcat (fn [^java.util.Map$Entry name-entry]
            (let [artist-name (.getKey name-entry)]
              (when (str/includes? (str/lower-case artist-name) text)
                (map (fn [artist-id]
                       (file-artist-node database (.get (.artistIndex database) artist-id) slot))
                     (.getValue name-entry)))))
          (.. database artistNameIndex entrySet)))

(defn- file-album-matches
  "Return nodes for every album in an exported rekordbox database that
  matches the supplied search text."
  [^Database database ^SlotReference slot text]
  (mapcat (fn [^java.util.Map$Entry name-entry]
            (let [album-name (.getKey name-entry)]
              (when (str/includes? (str/lower-case album-name) text)
                (map (fn [album-id]
                       (file-album-node database (.get (.albumIndex database) album-id) slot))
                     (.getValue name-entry)))))
           (.. database albumNameIndex entrySet)))

(defn- file-search
  "Run a search on an exported rekordbox database file. We always return
  complete results because the search is running locally."
  [^Database database ^SlotReference slot text ^AtomicInteger total]
  (let [text (str/lower-case text)
        results      (concat (file-track-matches database slot text)
                             (file-artist-matches database slot text)
                             (file-album-matches database slot text))]
    (.set total (count results))
    (sort #(compare (str/lower-case (str %1)) (str/lower-case (str %2))) results)))

(defn- search-text-changed
  "Start a new search because the user has changed the search text,
  unless there is no active search so this must be reloading the text
  area when switching to a different existing search."
  [text search-partial search-button searches ^JTree tree]
  (when-let [^SlotReference slot-reference (:current @searches)]
    (swap! searches assoc-in [slot-reference :text] text)
    (let [{:keys [^TreePath path]}     (get @searches slot-reference)
          ^DefaultMutableTreeNode node (.getLastPathComponent path)
          total                        (AtomicInteger. 25)
          ^ISearchEntry entry          (.getUserObject node)
          database                     (.getDatabase entry)
          results                      (when-not (str/blank? text)
                                         (if database
                                           (file-search database slot-reference text total)
                                           (.requestSearchResultsFrom menu-loader (.player slot-reference)
                                                                      (.slot slot-reference)
                                                                      0 text total)))]
      (.removeAllChildren node)
      (if (empty? results)
        (do
          (.add node (if (str/blank? text) (empty-search-node) (empty-node "[No matches.]")))
          (swap! searches update slot-reference dissoc :total)
          (configure-partial-search-ui search-partial search-button nil 0))
        (do
          (if database
            (attach-file-node-children node results slot-reference)
            (attach-menu-node-children node results slot-reference))
          (swap! searches assoc-in [slot-reference :total] (.get total))
          (configure-partial-search-ui search-partial search-button (.get total) (.getChildCount node))))
      (.setSelectionPath tree path)  ; Keep the search active in case the previous selection is gone.
      (.nodeStructureChanged ^DefaultTreeModel (.getModel tree) node))))

(defn- search-load-more
  "The user has asked to load more from the active search."
  [search-partial search-button searches ^JTree tree]
  (when-let [^SlotReference slot-reference (:current @searches)]
    (let [{:keys [text total ^TreePath path]} (get @searches slot-reference)
          ^DefaultMutableTreeNode node     (.getLastPathComponent path)
          loaded                           (.getChildCount node)
          batch-size                       (min 1000 loaded (- total loaded))
          results                          (.requestMoreSearchResultsFrom menu-loader (.player slot-reference)
                                                                          (.slot slot-reference)
                                                                          0 text loaded batch-size)]
      (attach-menu-node-children node results slot-reference)
      (configure-partial-search-ui search-partial search-button total (.getChildCount node))
      (.nodesWereInserted ^DefaultTreeModel (.getModel tree) node (int-array (range loaded (+ loaded batch-size)))))))

(defn- xdj-xz-load-problem
  "The XDJ-XZ can only be told to load a track if rekordbox or rekordbox
  mobile is on the network. This checks for that, and reports the
  problem if it is not true."
  []
  (if (empty? (filter (fn [^DeviceAnnouncement device]
                        (let [number (.getDeviceNumber device)]
                          (or (< 0x10 number 0x20)
                              (< 0x28 number 0x30))))
                      (.getCurrentDevices device-finder)))
    "XDJ-XZ won't load tracks without rekordbox on network."
    ""))

(defn- create-window
  "Builds an interface in which the user can choose a track and load it
  into a player. If `slot` is not `nil`, the corresponding slot will
  be initially chosen as the track source. Returns the frame if
  creation succeeded."
   [^SlotReference slot]
  (seesaw/invoke-later
   (let [valid-slots (filter (fn [^SlotReference mounted]
                               (#{CdjStatus$TrackSourceSlot/USB_SLOT
                                  CdjStatus$TrackSourceSlot/SD_SLOT
                                  CdjStatus$TrackSourceSlot/COLLECTION} (.slot mounted)))
                             (.getMountedMediaSlots metadata-finder))]
     (if (seq valid-slots)
       (try
         (let [selected-track     (atom nil)
               selected-player    (atom {:number nil :playing false :cued false :xdj-xz false :cdj-3000 false})
               searches           (atom {})
               ^JFrame root       (seesaw/frame :title "Load Track on a Player"
                                                :on-close :dispose :resizable? true)
               slots-model        (DefaultTreeModel. (root-node) true)
               ^JTree slots-tree  (seesaw/tree :model slots-model :id :tree)
               slots-scroll       (seesaw/scrollable slots-tree)
               load-button        (seesaw/button :text "Load" :enabled? false)
               play-button        (seesaw/button :text "Play")
               problem-label      (seesaw/label :text "" :foreground "red")
               update-load-ui     (fn []
                                    (let [playing (:playing @selected-player)
                                          cued    (:cued @selected-player)
                                          xdj-xz  (:xdj-xz @selected-player)
                                          problem (cond (nil? @selected-track) "No track chosen."
                                                        playing                "Can't load while playing."
                                                        xdj-xz                 (xdj-xz-load-problem)
                                                        :else                  "")
                                          no-play (when (or xdj-xz (:cdj-3000 @selected-player))
                                                    (str (if xdj-xz "XDJ-XZ" "CDJ-3000") " can't "))]
                                      (seesaw/value! problem-label problem)
                                      (seesaw/config! load-button :enabled? (empty? problem))
                                      (seesaw/config! play-button :text
                                                      (if playing
                                                        (if no-play (str no-play "Stop") "Stop and Cue")
                                                        (if no-play (str no-play "Play") "Play if Cued")))
                                      (seesaw/config! play-button :enabled? (and (not no-play) (or playing cued)))))
               player-changed     (fn [e]
                                    (update-selected-player selected-player e)
                                    (update-load-ui))
               ^JComboBox players (seesaw/combobox :id :players
                                                   :listen [:item-state-changed player-changed])
               player-panel       (mig/mig-panel :background "#ddd"
                                                 :items [[(seesaw/label :text "Load on:")]
                                                         [players] [load-button] [problem-label "push"]
                                                         [play-button]])
               search-label       (seesaw/label :text "")
               search-field       (seesaw/text "")
               search-partial     (seesaw/label "Showing 0 of 0.")
               search-button      (seesaw/button :text "Load All")
               search-panel       (mig/mig-panel :background "#eee"
                                                 :items [[search-label] [search-field "pushx, growx"]
                                                         [search-partial "hidemode 3, gap unrelated"]
                                                         [search-button "hidemode 3"]])
               layout             (seesaw/border-panel
                                   :center slots-scroll
                                   :south player-panel)
               mouse-listener     (proxy [java.awt.event.MouseAdapter] []
                                    (mousePressed [^java.awt.event.MouseEvent e]
                                      (when (and (seesaw/config load-button :enabled?) (= 2 (.getClickCount e)))
                                        (.doClick ^JButton load-button))))
               stop-listener      (reify LifecycleListener
                                    (started [_this _]) ; Nothing to do, we exited as soon a stop happened anyway.
                                    (stopped [_this _]  ; Close our window if MetadataFinder stops (we need it).
                                      (seesaw/invoke-later
                                       (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))
               dev-listener       (reify DeviceAnnouncementListener
                                    (deviceFound [_this announcement]
                                      (seesaw/invoke-later (add-device players (.getDeviceNumber announcement))))
                                    (deviceLost [_this announcement]
                                      (seesaw/invoke-later
                                       (remove-device players (.getDeviceNumber announcement) stop-listener))))
               mount-listener     (reify MountListener
                                    (mediaMounted [_this slot]
                                      (seesaw/invoke-later (add-slot-node slots-tree slot)))
                                    (mediaUnmounted [_this slot]
                                      (swap! searches dissoc slot)
                                      (seesaw/invoke-later (remove-slot-node slots-tree slot stop-listener))))
               status-listener    (reify DeviceUpdateListener
                                    (received [_this status]
                                      (let [player @selected-player]
                                        (when (and (= (.getDeviceNumber status) (:number player))
                                                   (or (not= (.isPlaying ^CdjStatus status) (:playing player))
                                                       (not= (.isCued ^CdjStatus status) (:cued player))))
                                          (swap! selected-player assoc
                                                 :playing (.isPlaying ^CdjStatus status)
                                                 :cued (.isCued ^CdjStatus status))
                                          (update-load-ui)))))
               remove-listeners   (fn []
                                    (.removeMountListener metadata-finder mount-listener)
                                    (.removeLifecycleListener metadata-finder stop-listener)
                                    (.removeDeviceAnnouncementListener device-finder dev-listener)
                                    (.removeUpdateListener virtual-cdj status-listener))]
           (.setSelectionMode (.getSelectionModel slots-tree)
                              javax.swing.tree.TreeSelectionModel/SINGLE_TREE_SELECTION)
           (.addMouseListener slots-tree mouse-listener)
           (.addMountListener metadata-finder mount-listener)
           (.addDeviceAnnouncementListener device-finder dev-listener)
           (.addUpdateListener virtual-cdj status-listener)
           (build-media-nodes slots-tree valid-slots)
           (build-device-choices players)
           (reset! loader-window root)
           (.addLifecycleListener metadata-finder stop-listener)
           (seesaw/listen root :window-closed (fn [_]
                                                (reset! loader-window nil)
                                                (remove-listeners)))
           (seesaw/listen slots-tree
                          :tree-will-expand
                          (fn [^javax.swing.event.TreeExpansionEvent e]
                            (let [^DefaultMutableTreeNode node (.. e (getPath) (getLastPathComponent))
                                  ^IMenuEntry entry            (.getUserObject node)]
                              (.loadChildren entry node)))
                          :selection
                          (fn [^javax.swing.event.TreeSelectionEvent e]
                            (reset! selected-track
                                    (when (.isAddedPath e)
                                      (let [^DefaultMutableTreeNode node (.. e (getPath) (getLastPathComponent))
                                            ^IMenuEntry entry            (.getUserObject node)]
                                        (when (.getTrackType entry)
                                          [(.getSlot entry) (.getId entry) (.getTrackType entry)]))))
                            (update-load-ui)
                            (let [search-path                         (when (.isAddedPath e)
                                                                        (trim-to-search-node-path (.getPath e)))
                                  ^DefaultMutableTreeNode search-node (when search-path
                                                                        (.expandPath slots-tree search-path)
                                                                        (.. search-path getLastPathComponent))
                                  ^IMenuEntry selected-entry          (when search-node (.getUserObject search-node))
                                  selected-search                     (when selected-entry (.getSlot selected-entry))]
                              (when (not= selected-search (:current @searches))
                                (swap! searches dissoc :current)  ; Suppress UI responses during switch to new search.
                                (if selected-search
                                  (do
                                    (swap! searches assoc-in [selected-search :path] search-path)
                                    (configure-search-ui search-label search-field search-partial search-button
                                                         searches selected-search)
                                    (seesaw/add! layout [search-panel :north]))
                                  (seesaw/remove! layout search-panel))))))
           (try  ; Expand the node for the slot we are supposed to be loading from, or the first slot if none given.
             (if-let [node (find-slot-node slots-tree slot)]
               (expand-and-select-slot-node slots-tree node)
               (.expandRow slots-tree 1))
             (catch IllegalStateException e
               (explain-navigation-failure e)
               (.stopped stop-listener metadata-finder))
             (catch Throwable t
               (.stopped stop-listener metadata-finder)  ; Clean up the window if we are blowing up...
               (throw t)))  ; ...but do rethrow the exception so the user sees the issue.
           (seesaw/listen load-button
                          :action-performed
                          (fn [_]
                            (let [[^SlotReference slot-reference
                                   ^int track
                                   ^CdjStatus$TrackType track-type] @selected-track
                                  ^Long selected-player             (.number ^PlayerChoice (.getSelectedItem players))]
                              (.sendLoadTrackCommand virtual-cdj selected-player track
                                                     (.player slot-reference) (.slot slot-reference) track-type))))
           (seesaw/listen play-button
                          :action-performed
                          (fn [_]
                            (let [player     @selected-player
                                  player-set #{(int (:number player))}
                                  start-set  (if (:playing @selected-player) #{} player-set)
                                  stop-set   (if (:playing @selected-player) player-set #{})]
                              (.sendFaderStartCommand virtual-cdj start-set stop-set))))
           (seesaw/listen search-field #{:remove-update :insert-update :changed-update}
                          (fn [e]
                            (when (:current @searches)
                              (search-text-changed (seesaw/text e) search-partial search-button searches slots-tree))))
           (seesaw/listen search-button
                          :action-performed
                          (fn [_]
                            (search-load-more search-partial search-button searches slots-tree)))

           (when-not (.isRunning metadata-finder)  ; In case it shut down during our setup.
             (when @loader-window (.stopped stop-listener metadata-finder)))  ; Give up unless we already did.
           (if @loader-window
             (do  ; We made it! Show the window.
               (seesaw/config! root :content layout)
               (.setSize root 800 600)
               (.setLocationRelativeTo root nil)
               root)
             (do  ; Something failed, clean up.
               (remove-listeners)
               (.dispose root))))
         (catch Exception e
           (timbre/error e "Problem Loading Track")
           (seesaw/alert (str "<html>Unable to Load Track on Player:<br><br>" (.getMessage e)
                              "<br><br>See the log file for more details.")
                         :title "Problem Loading Track" :type :error)))
       (seesaw/alert "There is no media mounted in any player media slot."
                     :title "Nowhere to Load Tracks From" :type :error)))))

(defn show-dialog
  "Displays an interface in which the user can choose a track and load
  it into a player. If `slot` is provided, the corresponding slot will
  be initially chosen as the track source."
  ([]
   (show-dialog nil))
  ([^SlotReference slot]
   (seesaw/invoke-later
    (locking loader-window
      (if-not @loader-window
        (create-window slot)
        (let [tree (seesaw/select @loader-window [:#tree])]
          (when-let [node (find-slot-node tree slot)]
            (expand-and-select-slot-node tree node))))
      (seesaw/invoke-later
       (when-let [^JFrame window @loader-window]
         (seesaw/show! window)
         (.toFront window)))))))

(defn find-pdb
  "Locates the rekordbox export database file in the supplied media
  filesystem. `media-root` can either be a File object representing
  the root of some media containing a rekordbox export, or the
  pathname of such a file."
  ^File [media-root]
  (let [pdb (io/file media-root "PIONEER" "rekordbox" "export.pdb")]
    (if (.canRead pdb)
      pdb
      (let [pdb (io/file media-root ".PIONEER" "rekordbox" "export.pdb")]
        (when (.canRead pdb)
          pdb)))))

(defn find-pdb-recursive
  "Searches recursively through a set of subdirectories for rekordbox
  media exports. As long as `depth` is non-zero we are willing to go
  down another layer through a recursive call with a decremented depth
  value."
  ^File [^File root depth]
  (if-let [found (find-pdb root)]
    [found]
    (and (pos? depth)
         (filter identity (flatten (map (fn [^File f]
                                          (and (.isDirectory f)
                                               (find-pdb-recursive f (dec depth))))
                                        (.listFiles root)))))))

(def rekordbox-export-filter
  "A file filter that matches directories that contain exported
  rekordbox media. To avoid taking a huge amount of time searching, we
  are only willing to go down through a small number of directories to
  find the root of the media."
  (chooser/file-filter "rekordbox media" #(seq (find-pdb-recursive % 3))))

(defn- describe-pdb-media
  "Returns the root pathname of the media containing a rekordbox
  collection export."
  [^File pdb-file]
  (.. pdb-file getParentFile getParentFile getParentFile getAbsolutePath))

(defn- offline-file-track-node
  "Creates the root node for working with an offline rekordbox exported
  database file and choosing tracks."
  [^Database database]
  (let [node (DefaultMutableTreeNode.
              (proxy [Object IMenuEntry] []
                (toString [] (str "Choose Track from:"))
                (getId [] (int 0))
                (getSlot [] nil)
                (getTrackType [] nil)
                (loadChildren [_])))]
    (add-file-node-children database node nil)
    node))

(defn offline-playlist-only-node
  "Creates a node that represents a playlist available in an exported
  rekordbox database file when the only thing that can be loaded is
  playlists themselves."
  [^Database _database id playlist-name]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] playlist-name)
     (getId [] (int id))
     (getSlot [] nil)
     (getTrackType [] nil)
     (loadChildren [_]))
   false))

(defn- offline-playlist-only-folder-node
  "Creates a node that represents a playlist folder available in an
  exported rekordbox database file when the only thing that can be
  chosen is playlists themselves."
  [^Database database id folder-name]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] folder-name)
     (getId [] (int id))
     (getSlot [] nil)
     (getTrackType [] nil)
     (loadChildren [^DefaultMutableTreeNode node]
       (when (unloaded? node)
         (doseq [^Database$PlaylistFolderEntry entry (.. database playlistFolderIndex (get id))]
           (when entry
             (if (.isFolder entry)
               (.add node (offline-playlist-only-folder-node database (.id entry) (.name entry)))
               (.add node (offline-playlist-only-node database (.id entry) (.name entry))))))
         (mark-if-still-empty node))))
   true))

(defn- offline-file-playlist-node
  "Creates the root node for working with an offline rekordbox exported
  database file and choosing playlists."
  [^Database database]
  (let [node (DefaultMutableTreeNode.
              (proxy [Object IMenuEntry] []
                (toString [] (str "Choose Playlist from:"))
                (getId [] (int 0))
                (getSlot [] nil)
                (getTrackType [] nil)
                (loadChildren [_])))]
    (.add node (offline-playlist-only-folder-node database 0 "Playlists"))
    node))

(defn- create-local-track-chooser-dialog
  "Builds an interface in which the user can choose a track from a
  locally mounted media filesystem for offline inclusion into a show.
  Returns the frame if creation succeeded. If `parent` is not nil, the
  dialog will be centered on it rather than in the middle of the
  screen. `pdb` must be a Database object that contains a parsed
  rekordbox `export.pdb` database. If `extra-labels` are provided,
  they are used to create additional buttons at the bottom of the
  dialog which, when clicked, return the text of the label rather than
  the usual [database track] tuple.

  This function must be invoked on the Swing Event Dispatch thread."
  [^JFrame parent ^Database pdb extra-labels]
  (try
    (let [selected-track   (atom nil)
          searches         (atom {})
          file-model       (DefaultTreeModel. (offline-file-track-node pdb) true)
          ^JTree file-tree (seesaw/tree :model file-model :id :tree)
          file-scroll      (seesaw/scrollable file-tree)
          choose-button    (seesaw/button :text "Choose Track" :enabled? false)
          cancel-button    (seesaw/button :text "Cancel")
          extra-buttons    (map (fn [text] (seesaw/button :text text)) extra-labels)
          update-choose-ui (fn []
                             (seesaw/config! choose-button :enabled? (some? @selected-track)))
          search-label     (seesaw/label :text "Search:")
          search-field     (seesaw/text "")
          search-partial   (seesaw/label "") ; Not used in this dialog variant, expected by search UI.
          search-button    (seesaw/button :text "Load All") ; Also not used but expected by search UI.
          search-panel     (mig/mig-panel :background "#eee"
                                          :items [[search-label] [search-field "pushx, growx"]])
          layout           (seesaw/border-panel :center file-scroll)
          ^JDialog dialog  (seesaw/dialog :content layout :options (concat [choose-button cancel-button] extra-buttons)
                                          :title (str "Choose Track from " (describe-pdb-media (.sourceFile pdb)))
                                          :default-option choose-button :modal? true)
          mouse-listener   (proxy [java.awt.event.MouseAdapter] []
                             (mousePressed [^java.awt.event.MouseEvent e]
                               (when (and @selected-track (= 2 (.getClickCount e)))
                                 (.doClick ^JButton choose-button))))]
      (.setSelectionMode (.getSelectionModel file-tree) javax.swing.tree.TreeSelectionModel/SINGLE_TREE_SELECTION)
      (.setSize dialog 800 600)
      (.setLocationRelativeTo dialog parent)
      (seesaw/listen file-tree
                     :tree-will-expand
                     (fn [^javax.swing.event.TreeExpansionEvent e]
                       (let [^DefaultMutableTreeNode node (.. e (getPath) (getLastPathComponent))
                             ^IMenuEntry entry            (.getUserObject node)]
                         (.loadChildren entry node)))
                     :selection
                     (fn [^javax.swing.event.TreeSelectionEvent e]
                       (try
                         (reset! selected-track
                                 (when (.isAddedPath e)
                                   (let [^DefaultMutableTreeNode node (.. e (getPath) (getLastPathComponent))
                                         ^IMenuEntry entry            (.getUserObject node)]
                                     (when (.getTrackType entry) (.getId entry)))))
                         (update-choose-ui)
                         (let [search-path                         (when (.isAddedPath e)
                                                                     (trim-to-search-node-path (.getPath e)))
                               ^DefaultMutableTreeNode search-node (when search-path
                                                                     (.expandPath file-tree search-path)
                                                                     (.getLastPathComponent search-path))
                               ^IMenuEntry selected-entry          (when search-node (.. search-node getUserObject))
                               selected-search                     (when selected-entry (.getSlot selected-entry))]
                           (when (not= selected-search (:current @searches))
                             (swap! searches dissoc :current) ; Suppress UI responses during switch to new search.
                             (if selected-search
                               (do
                                 (swap! searches assoc-in [selected-search :path] search-path)
                                 (configure-search-ui search-label search-field search-partial search-button
                                                      searches selected-search)
                                 (seesaw/text! search-label (str "Search " (describe-pdb-media (.sourceFile pdb)) ":"))
                                 (seesaw/add! layout [search-panel :north]))
                               (seesaw/remove! layout search-panel))))
                         (catch Throwable t
                           (timbre/error t "Problem responding to file tree click.")))))
      (.addMouseListener file-tree mouse-listener)
      (seesaw/listen choose-button :action-performed
                     (fn [_]
                       (seesaw/return-from-dialog dialog [pdb (.. pdb trackIndex (get (long @selected-track)))])))
      (seesaw/listen cancel-button :action-performed (fn [_] (seesaw/return-from-dialog dialog nil)))
      (doseq [button extra-buttons]
        (let [text (seesaw/text button)]
          (seesaw/listen button :action-performed (fn [_] (seesaw/return-from-dialog dialog [pdb text])))))
      (seesaw/listen search-field #{:remove-update :insert-update :changed-update}
                     (fn [e]
                       (when (:current @searches)
                         (search-text-changed (seesaw/text e) search-partial search-button searches file-tree))))
      (seesaw/show! dialog))
    (catch Exception e
      (timbre/error e "Problem Choosing Track")
      (seesaw/alert (str "<html>Unable to Choose Track from Media Export:<br><br>" (.getMessage e)
                         "<br><br>See the log file for more details.")
                    :title "Problem Choosing Track" :type :error))))

(defn- create-local-playlist-chooser-dialog
  "Builds an interface in which the user can choose a playlist from a
  locally mounted media filesystem for offline inclusion into a show.
  Returns the frame if creation succeeded. If `parent` is not nil, the
  dialog will be centered on it rather than in the middle of the
  screen. `pdb` must be a Database object that contains a parsed
  rekordbox `export.pdb` database. If `extra-labels` are provided,
  they are used to create additional buttons at the bottom of the
  dialog which, when clicked, return the text of the label rather than
  the usual [database playlist] tuple.

  This function must be invoked on the Swing Event Dispatch thread."
   [^JFrame parent ^Database pdb extra-labels]
  (try
    (let [selected-playlist (atom nil)
          file-model        (DefaultTreeModel. (offline-file-playlist-node pdb) true)
          ^JTree file-tree  (seesaw/tree :model file-model :id :tree)
          file-scroll       (seesaw/scrollable file-tree)
          choose-button     (seesaw/button :text "Choose Playlist" :enabled? false)
          cancel-button     (seesaw/button :text "Cancel")
          extra-buttons     (map (fn [text] (seesaw/button :text text)) extra-labels)
          update-choose-ui  (fn []
                             (seesaw/config! choose-button :enabled? (some? @selected-playlist)))
          layout            (seesaw/border-panel :center file-scroll)
          ^JDialog dialog   (seesaw/dialog :content layout :options (concat [choose-button cancel-button] extra-buttons)
                                          :title (str "Choose Playlist from " (describe-pdb-media (.sourceFile pdb)))
                                          :default-option choose-button :modal? true)
          mouse-listener    (proxy [java.awt.event.MouseAdapter] []
                             (mousePressed [^java.awt.event.MouseEvent e]
                               (when (and @selected-playlist (= 2 (.getClickCount e)))
                                 (.doClick ^JButton choose-button))))]
      (.setSelectionMode (.getSelectionModel file-tree) javax.swing.tree.TreeSelectionModel/SINGLE_TREE_SELECTION)
      (.setSize dialog 800 600)
      (.setLocationRelativeTo dialog parent)
      (seesaw/listen file-tree
                     :tree-will-expand
                     (fn [^javax.swing.event.TreeExpansionEvent e]
                       (let [^DefaultMutableTreeNode node (.. e (getPath) (getLastPathComponent))
                             ^IMenuEntry entry            (.getUserObject node)]
                         (.loadChildren entry node)))
                     :selection
                     (fn [^javax.swing.event.TreeSelectionEvent e]
                       (try
                         (reset! selected-playlist
                                 (when (.isAddedPath e)
                                   (let [^DefaultMutableTreeNode node (.. e (getPath) (getLastPathComponent))
                                         ^IMenuEntry entry            (.getUserObject node)]
                                     (when-not (.getAllowsChildren node) (.getId entry)))))
                         (update-choose-ui)
                         (catch Throwable t
                           (timbre/error t "Problem responding to file tree click.")))))
      (.addMouseListener file-tree mouse-listener)
      (seesaw/listen choose-button :action-performed
                     (fn [_]
                       (seesaw/return-from-dialog dialog [pdb (.. pdb playlistIndex (get (long @selected-playlist)))])))
      (seesaw/listen cancel-button :action-performed (fn [_] (seesaw/return-from-dialog dialog nil)))
      (doseq [button extra-buttons]
        (let [text (seesaw/text button)]
          (seesaw/listen button :action-performed (fn [_] (seesaw/return-from-dialog dialog [pdb text])))))
      (.expandPath file-tree (.getPathForRow file-tree 1))
      (seesaw/show! dialog))
    (catch Exception e
      (timbre/error e "Problem Choosing Playlist")
      (seesaw/alert (str "<html>Unable to Choose Playlist from Media Export:<br><br>" (.getMessage e)
                         "<br><br>See the log file for more details.")
                    :title "Problem Choosing Playlist" :type :error))))

(defn choose-media-export
  "Presents a modal dialog allowing the selection of a locally mounted
  rekordbox media export filesystem. If one is successfully chosen,
  returns the parsed database export. If `parent` is supplied, the
  dialog will be centered on it, rather than in the middle of the
  screen."
  ([]
   (choose-media-export nil))
  ([parent]
   (seesaw/invoke-now
     (let [root (chooser/choose-file parent :selection-mode :dirs-only :all-files? true :type
                                     "Choose Rekordbox Media"
                                     :filters [rekordbox-export-filter]
                                     :remember-directory? false)]
       (when root
         (let [candidates (find-pdb-recursive root 3)]
           (cond
             (empty? candidates)
             (seesaw/alert "No rekordbox export found in the chosen directory."
                           :title "Unable to Locate Database" :type :error)

             (> (count candidates) 1)
             (seesaw/alert parent (str "Multiple recordbox exports found in the chosen directory.\n"
                                       "Please pick a specific media export:\n"
                                       (str/join "\n" (map describe-pdb-media candidates)))
                           :title "Ambiguous Database Choice" :type :error)

             :else
             (or (Database. (first candidates))
                 (seesaw/alert parent "Could not find exported rekordbox database."
                               :title "Nowhere to Load Tracks From" :type :error)))))))))

(defn choose-local-track
  "Presents a modal dialog allowing the selection of a track from a
  locally mounted media filesystem. If `parent` is supplied, the
  dialogs will be centered on it rather than in the middle of the
  screen. If `database` is supplied, uses that already-parsed
  rekordbox export file; otherwise starts by prompting the user to
  choose a media volume to parse. Returns a tuple of the database and
  the chosen track object, or `nil` if the user canceled. If
  `extra-labels` are provided, they are used to create additional
  buttons at the bottom of the dialog which, when clicked, return the
  text of the label rather than the usual [database track] tuple."
  ([]
   (choose-local-track nil))
  ([parent]
   (choose-local-track parent nil))
  ([parent ^Database database & extra-labels]
   (seesaw/invoke-now
    (if (and database (.. database sourceFile canRead))  ; Trying to reuse a database, make sure file is still there.
      (create-local-track-chooser-dialog parent database extra-labels)
      (if-let [pdb (choose-media-export parent)]
        (create-local-track-chooser-dialog parent pdb extra-labels)
        (seesaw/alert parent "Could not find exported rekordbox database."
                      :title "Nowhere to Load Tracks From" :type :error))))))

(defn choose-local-playlist
  "Presents a modal dialog allowing the selection of a playlist from a
  locally mounted media filesystem. If `parent` is supplied, the
  dialogs will be centered on it rather than in the middle of the
  screen. If `database` is supplied, uses that already-parsed
  rekordbox export file; otherwise starts by prompting the user to
  choose a media volume to parse. Returns a tuple of the database and
  the chosen playlist object, or `nil` if the user canceled. If
  `extra-labels` are provided, they are used to create additional
  buttons at the bottom of the dialog which, when clicked, return the
  text of the label rather than the usual [database playlist] tuple."
  ([]
   (choose-local-playlist nil))
  ([parent]
   (choose-local-playlist parent nil))
  ([parent ^Database database & extra-labels]
   (seesaw/invoke-now
    (if (and database (.. database sourceFile canRead))  ; Trying to reuse a database, make sure file is still there.
      (create-local-playlist-chooser-dialog parent database extra-labels)
      (if-let [pdb (choose-media-export parent)]
        (create-local-playlist-chooser-dialog parent pdb extra-labels)
        (seesaw/alert parent "Could not find exported rekordbox database."
                      :title "Nowhere to Load Playlists From" :type :error))))))

(defn create-metadata-archive
  "Prompt the user to select some mounted rekordbox media, then for a
  file in which to archive its metadata for use with the Opus Quad.
  `parent` is the window over which the UI should be centered, if any."
  [parent]
  (when-let [^Database database (choose-media-export parent)]
    (let [extension (util/extension-for-file-type :metadata-archive)]
      (try
        (when-let [^File file (chooser/choose-file parent :type "Create Metadata Archive"
                                                   :all-files? false
                                                   :filters [["Beat Link Metadata Archives" [extension]]])]
          (let [continue?    (atom true)
                progress     (seesaw/progress-bar :indeterminate? true :min 0 :max 1000)
                panel        (mig/mig-panel
                              :items [[(seesaw/label :text
                                                     (str "<html>Creating metadata archive, in file <strong>"
                                                          (.getName file) "</strong>:</html>"))
                                       "span, wrap 20"]
                                      [progress "grow, span, wrap 16"]
                                      [(seesaw/button :text "Cancel"
                                                      :listen [:action-performed
                                                               (fn [e]
                                                                 (reset! continue? false)
                                                                 (seesaw/config! e :enabled? false
                                                                                 :text "Canceling…"))])
                                       "span, align center"]])
                ^JFrame root (seesaw/frame :title "Archiving Metadata" :on-close :dispose :content panel)
                listener (reify Archivist$ArchiveListener
                           (continueCreating [_this finished-count total-count]
                             (seesaw/invoke-later
                               (seesaw/config! progress :max total-count :indeterminate? false)
                               (seesaw/value! progress finished-count)
                               (when (or (not @continue?) (>= finished-count total-count))
                                 (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING))))
                             @continue?))]
            (when-let [^File file (util/confirm-overwrite-file file extension parent)]
              (seesaw/listen root :window-closed (fn [_] (reset! continue? false)))
              (seesaw/pack! root)
              (.setLocationRelativeTo root parent)
              (seesaw/show! root)
              (future
                (try
                  (.createArchive (Archivist/getInstance) database file listener)
                  (catch Exception e
                    (timbre/error e "Problem Creating Metadata Archive")
                    (seesaw/invoke-later
                      (seesaw/alert (str "<html>Unable to Create Metadata Cache:<br><br>" (.getMessage e)
                                         "<br><br>See the log file for more details.")
                                    :title "Problem Creating Cache" :type :error)
                      (.dispose root))))))))
        (catch Exception e
          (timbre/error e "Problem Setting Up Metadata Archive")
          (seesaw/invoke-later
            (seesaw/alert (str "<html>Unable to Create Metadata Cache:<br><br>" (.getMessage e)
                               "<br><br>See the log file for more details.")
                          :title "Problem Creating Cache" :type :error)))
        (finally
          (.close database))))))
