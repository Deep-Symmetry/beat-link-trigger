(ns beat-link-trigger.track-loader
  "Provides the user interface for exploring the menu trees of the
  available media databases, and loading tracks into players."
  (:require [beat-link-trigger.tree-node]
            [beat-link-trigger.util :as util]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import beat_link_trigger.tree_node.IMenuEntry
           beat_link_trigger.util.PlayerChoice
           [java.awt.event WindowEvent]
           [java.util.concurrent.atomic AtomicInteger]
           [javax.swing JTree]
           [javax.swing.tree DefaultMutableTreeNode DefaultTreeModel TreeNode TreePath]
           [org.deepsymmetry.beatlink CdjStatus CdjStatus$TrackSourceSlot CdjStatus$TrackType
            DeviceAnnouncement DeviceAnnouncementListener DeviceFinder DeviceUpdate DeviceUpdateListener
            LifecycleListener VirtualCdj]
           [org.deepsymmetry.beatlink.data MenuLoader MetadataFinder MountListener SlotReference]
           [org.deepsymmetry.beatlink.dbserver Message Message$MenuItemType]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to pick a track and
  tell a player to load it."} loader-window
  (atom nil))

(def menu-loader
  "The object that provides methods for loading menus from a rekordbox
  database server."
  (MenuLoader/getInstance))

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
                         "make sure the <strong>Request Track Metadata?</strong> option is checked,<br>"
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
  [item]
  (.get Message/MENU_ITEM_TYPE_MAP (.getValue (nth (.arguments item) 6))))

(defn- menu-item-label
  "Retrieve the label to be displayed on a menu item given the response
  message containing the item."
  [item]
  (str (.getValue (nth (.arguments item) 3))))

(defn- menu-item-label-2
  "Retrieve the seconary label associated with a menu item given the
  response message containing the item."
  [item]
  (str (.getValue (nth (.arguments item) 5))))

(defn- menu-item-id
  "Retrieve the primary ID associated with menu item (e.g. the track ID
  for a track entry), given the response message containing the item."
  [item]
  (.getValue (nth (.arguments item) 1)))

(defmulti menu-item-node
  "A multi-method that will create the appropriate kind of menu item
  tree node given the response message that contains it, and the slot
  reference that will allow it to communicate with the appropriate
  dbserver database to load itself or its children."
  (fn [^Message item ^SlotReference slot-reference] (menu-item-kind item)))

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
      (isMenu [] false)
      (isTrack [] false)
      (isSearch [] false)
      (loadChildren [_]))
    false)))

(defn- unloaded?
  "Checks whether a node still needs to be loaded (does not yet have its children)."
  [^DefaultMutableTreeNode node]
  (zero? (.getChildCount node)))

(defn- attach-node-children
  "Given a list of menu items which have been loaded as a node's
  children, adds them to the node. If none were found, adds an inert
  child to explain that the node was empty. If `all-handler` is
  supplied, it is called to create the ALL menu item when one is
  present."
  ([^DefaultMutableTreeNode node items ^SlotReference slot-reference]
   (attach-node-children node items slot-reference nil))
  ([^DefaultMutableTreeNode node items ^SlotReference slot-reference all-handler]
   (if (seq items)
     (doseq [^Message item items]

       (.add node (if (and (= (menu-item-kind item) Message$MenuItemType/ALL) all-handler)
                    (all-handler item)
                    (menu-item-node item slot-reference))))
     (.add node (empty-node)))))

;; Creates a menu item node for the History menu.
(defmethod menu-item-node Message$MenuItemType/HISTORY_MENU history-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestHistoryMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for a history playlist
(defmethod menu-item-node Message$MenuItemType/HISTORY_PLAYLIST history-playlist-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestHistoryPlaylistFrom menu-loader slot-reference 0 (menu-item-id item))
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
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestTrackMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for the Playlist menu.
(defmethod menu-item-node Message$MenuItemType/PLAYLIST_MENU playlist-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestPlaylistMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for a playlist folder.
(defmethod menu-item-node Message$MenuItemType/FOLDER folder-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestPlaylistItemsFrom metadata-finder
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
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestPlaylistItemsFrom metadata-finder
                                                               (.player slot-reference) (.slot slot-reference)
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
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestArtistMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for the Album menu.
(defmethod menu-item-node Message$MenuItemType/ALBUM_MENU album-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestAlbumMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for the Genre menu.
(defmethod menu-item-node Message$MenuItemType/GENRE_MENU genre-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestGenreMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

(defn- create-all-genre-artist-albums-node
  "Handles the ALL menu item when listing genre artist albums. Creates
  an appropriate node to implement it."
  [^Message item ^SlotReference slot-reference genre-id]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ALBUMS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestGenreArtistAlbumTrackMenuFrom menu-loader slot-reference 0 genre-id -1 -1)
                               slot-reference))))
   true))

(defn- create-all-genre-artists-node
  "Handles the ALL menu item when listing genre artists. Creates an
  appropriate node to implement it."
  [^Message item ^SlotReference slot-reference genre-id]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ARTISTS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestGenreArtistAlbumMenuFrom menu-loader slot-reference 0 genre-id -1)
                               slot-reference
                               (fn [item]  ; Special handler for the All Albums item.
                                 (create-all-genre-artist-albums-node item slot-reference genre-id))))))
   true))

;; Creates a menu item node for a genre.
(defmethod menu-item-node Message$MenuItemType/GENRE genre-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (let [genre-id (menu-item-id item)]
           (attach-node-children node (.requestGenreArtistMenuFrom menu-loader slot-reference 0 genre-id)
                                 slot-reference
                                 (fn [item]  ; Special handler the All Artists item.
                                   (create-all-genre-artists-node item slot-reference genre-id)))))))
   true))

;; Creates a menu item node for an artist.
(defmethod menu-item-node Message$MenuItemType/ARTIST artist-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestArtistAlbumMenuFrom menu-loader slot-reference 0 (menu-item-id item))
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
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestAlbumTrackMenuFrom menu-loader slot-reference 0 (menu-item-id item))
                               slot-reference))))
   true))

;; Creates a menu item node for a track.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE track-title-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (isMenu [] false)
     (isTrack [] true)
     (isSearch [] false)
     (loadChildren [_]))
   false))

;; Creates a menu item node for a track with the artist name.
(defmethod menu-item-node Message$MenuItemType/TRACK_TITLE_AND_ARTIST track-title-and-artist-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (str (menu-item-label item)
                       (when-let [artist (menu-item-label-2 item)] (str "—" artist))))
     (getId [] (menu-item-id item))
     (getSlot [] slot-reference)
     (isMenu [] false)
     (isTrack [] true)
     (isSearch [] false)
     (loadChildren [_]))
   false))

(defn- empty-search-node
  "Creates the node that explains what to do when a search has not been started."
  []
  (empty-node "[Type text in the search field above to see matches.]"))

;; Creates a menu item node for the search interface.
(defmethod menu-item-node Message$MenuItemType/SEARCH_MENU search-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] 0)
     (getSlot [] slot-reference)
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] true)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (.add node (empty-search-node)))))
   true))


;; Creates a menu item node for unrecognized entries.
(defmethod menu-item-node :default unrecognized-item-node
  [^Message item ^SlotReference slot-reference]
  (let [kind (or (menu-item-kind item)
                 (format "0x%x" (.getValue (nth (.arguments item) 6))))]  ; Show number if we can't name it.
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (str (menu-item-label item) " [unrecognized (" kind ")" "]"))
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (isMenu [] false)
       (isTrack [] false)
       (isSearch [] false)
       (loadChildren [_]))
     false)))

(defn- slot-label
  "Assembles the name used to describe a particular player slot, given
  the slot reference."
  [^SlotReference slot-reference]
  (str "Player " (.player slot-reference) " "
                   (util/case-enum (.slot slot-reference)
                     CdjStatus$TrackSourceSlot/SD_SLOT "SD"
                     CdjStatus$TrackSourceSlot/USB_SLOT "USB")))

(defn- slot-node
  "Creates the tree node that will allow access to the media database in
  a particular player slot."
  [^SlotReference slot-reference]
  (let [label (slot-label slot-reference)]
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] label)
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (isMenu [] true)
       (isTrack [] false)
       (isSearch [] false)
       (loadChildren [^javax.swing.tree.TreeNode node]
         (when (unloaded? node)
           (attach-node-children node (.requestRootMenuFrom menu-loader slot-reference 0) slot-reference))))
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
     (isMenu [] true)
     (isTrack [] false)
     (isSearch [] false)
     (loadChildren [_]))
   true))

(defn- find-slot-node
  "Searches the tree for the node responsible for talking to the
  specified player slot, returning it if it exists. Must be invoked on
  the Swing event dispatch thread."
  [^JTree tree ^SlotReference slot]
  (let [root (.. tree getModel getRoot)]
    (loop [index 0]
      (when (< index (.getChildCount root))
        (let [child (.. tree getModel (getChild root index))]
          (if (= (.. child getUserObject getSlot) slot)
            child
            (recur (inc index))))))))

(defn- remove-slot-node
  "Searches the tree for the node responsible for talking to the
  specified player slot, removing it if it exists. Must be invoked on
  the Swing event dispatch thread."
  [^JTree tree ^SlotReference slot]
  (when-let [node (find-slot-node tree slot)]
    (.removeNodeFromParent (.getModel tree) node)))

(defn- add-slot-node
  "Adds a node responsible for talking to the specified player slot to
  the tree. Must be invoked on the Swing event dispatch thread.
  Ignores slots we don't support."
  [^JTree tree ^SlotReference slot]
  (when (#{CdjStatus$TrackSourceSlot/SD_SLOT CdjStatus$TrackSourceSlot/USB_SLOT} (.slot slot))
    (let [node (slot-node slot)
          root (.. tree getModel getRoot)]
      ;; Find the node we should be inserting the new one in front of, if any.
      (loop [index 0]
        (if (< index (.getChildCount root))
          (let [sibling (.. tree getModel (getChild root index))]
            (if (neg? (.compareTo (.toString node) (.toString sibling)))
              (.. tree getModel (insertNodeInto node root index))  ; Found node we should be in front of.
              (recur (inc index))))
          (.. tree getModel (insertNodeInto node root index)))))))  ; We go at the end of the root.

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
  [tree node]
  (let [model (.getModel tree)
        node-path (TreePath. (to-array [(.getRoot model) node]))]
    (.setSelectionPath tree node-path)
    (.expandPath tree node-path)))

(defn- trim-to-search-node-path
  "If the supplied tree path belongs to a Search menu entry, returns the
  start of the path which leads to the Search node itself. Otherwise
  returns `nil`."
  [^TreePath path]
  (loop [result path]
    (if (.. result getLastPathComponent getUserObject isSearch)
      result
      (when (> (.getPathCount path) 3)
        (recur (.getParentPath result))))))

(defn- add-device
  "Adds a newly-found player to the destination player combo box,
  keeping entries sorted."
  [players number]
  (when (<= 1 number 4)
    (let [model  (.getModel players)
          player (PlayerChoice. number)]
      (loop [index 0]
        (if (< index (.getSize model))
          (let [current (.getElementAt model index)]
            (if (< number (.number current))
              (.insertElementAt model player index)  ; Found player we belong in front of.
              (recur (inc index))))
          (.insertElementAt model player index))))))  ; We go at the end of the list.

(defn- remove-device
  "Removes a newly-departed player from the destination player combo
  box."
  [players number]
  (let [model (.getModel players)]
    (loop [index 0]
      (when (< index (.getSize model))
        (if (= number (.number (.getElementAt model index)))
          (.removeElementAt model index)
          (recur (inc index)))))))

(defn- build-device-choices
  "Sets up the initial content of the destination player combo box."
  [players]
  (doseq [^DeviceAnnouncement announcement (.getCurrentDevices device-finder)]
    (add-device players (.getNumber announcement)))
  (.setSelectedIndex players 0))

(defn- update-selected-player
  "Event handler for the player choice menu to update the atom tracking
  the selected player state."
  [selected-player e]
  (let [number (when-let [selection (seesaw/selection e)]
                 (.number selection))]
    (reset! selected-player {:number number
                             :playing (and number
                                           (.. virtual-cdj (getLatestStatusFor number) isPlaying))})))

(defn- configure-partial-search-ui
  "Show (with appropriate content) or hide the label and buttons
  allowing a partial search to be continued, depending on the search
  state."
  [search-partial search-button total loaded]
  (let [unfinished (boolean (and total (< loaded total)))
        next-size (when unfinished (+ loaded (Math/min 1000 loaded)))]
    (seesaw/config! [search-partial search-button] :visible? unfinished)
    (when unfinished
      (seesaw/text! search-partial (str "Showing " loaded " of " total "."))
      (seesaw/text! search-button (str "Load " (if (< next-size total) next-size "All"))))))

(defn- configure-search-ui
  "Loads the search interface elements from any values that were saved
  for the search the last time it was active."
  [search-label search-field search-partial search-button searches selected-search]
  (let [{:keys [text total path] :or {text ""}} (get @searches selected-search)  ; Check saved search config.
        node                                    (.getLastPathComponent path)]
    (seesaw/text! search-label (str "Search " (slot-label selected-search) ":"))
    (seesaw/text! search-field text)
    (configure-partial-search-ui search-partial search-button total (.getChildCount node))
    (swap! searches assoc :current selected-search)))  ; Record and enable the UI for the new search.

(defn- search-text-changed
  "Start a new search because the user has changed the search text,
  unless there is no active search so this must be reloading the text
  area when switching to a different existing search."
  [text search-partial search-button searches tree]
  (when-let [^SlotReference slot-reference (:current @searches)]
    (swap! searches assoc-in [slot-reference :text] text)
    (let [{:keys [path]} (get @searches slot-reference)
          node           (.getLastPathComponent path)
          total          (AtomicInteger. 25)
          results        (when-not (clojure.string/blank? text)
                           (.requestSearchResultsFrom menu-loader (.player slot-reference) (.slot slot-reference)
                                                      0 text total))]
      (.removeAllChildren node)
      (if (empty? results)
        (do
          (.add node (if (clojure.string/blank? text) (empty-search-node) (empty-node "[No matches.]")))
          (swap! searches update slot-reference dissoc :total)
          (configure-partial-search-ui search-partial search-button nil 0))
        (do
          (attach-node-children node results slot-reference)
          (swap! searches assoc-in [slot-reference :total] (.get total))
          (configure-partial-search-ui search-partial search-button (.get total) (.getChildCount node))))
      (.setSelectionPath tree path)  ; Keep the search active in case the previous selection is gone.
      (.nodeStructureChanged (.getModel tree) node))))

(defn- create-window
  "Builds an interface in which the user can choose a track and load it
  into a player. If `slot` is not `nil`, the corresponding slot will
  be initially chosen as the track source. Returns the frame if
  creation succeeded."
   [^SlotReference slot]
  ;; TODO: Need to add rekordbox collection slots for any rekordbox computers found on the network.
  (seesaw/invoke-later
   (let [valid-slots (filter #(#{CdjStatus$TrackSourceSlot/USB_SLOT CdjStatus$TrackSourceSlot/SD_SLOT} (.slot %))
                             (.getMountedMediaSlots metadata-finder))]
     (if (seq valid-slots)
       (try
         (let [selected-track   (atom nil)
               selected-player  (atom {:number nil :playing false})
               searches         (atom {})
               root             (seesaw/frame :title "Load Track on a Player"
                                              :on-close :dispose :resizable? true)
               slots-model      (DefaultTreeModel. (root-node) true)
               slots-tree       (seesaw/tree :model slots-model :id :tree)
               slots-scroll     (seesaw/scrollable slots-tree)
               load-button      (seesaw/button :text "Load" :enabled? false)
               play-button      (seesaw/button :text "Play")
               problem-label    (seesaw/label :text "" :foreground "red")
               update-load-ui   (fn []
                                  (let [playing (:playing @selected-player)
                                        problem (cond (nil? @selected-track) "No track chosen."
                                                      playing                "Can't load while playing."
                                                      :else                  "")]
                                    (seesaw/value! problem-label problem)
                                    (seesaw/config! load-button :enabled? (empty? problem))
                                    (seesaw/config! play-button :text (if playing "Stop" "Play"))))
               player-changed   (fn [e]
                                  (update-selected-player selected-player e)
                                  (update-load-ui))
               players          (seesaw/combobox :id :players
                                                 :listen [:item-state-changed player-changed])
               player-panel     (mig/mig-panel :background "#ddd"
                                               :items [[(seesaw/label :text "Load on:")]
                                                       [players] [load-button] [problem-label "push"]
                                                       [play-button]])
               search-label     (seesaw/label :text "")
               search-field     (seesaw/text "")
               search-partial   (seesaw/label "Showing 0 of 0.")
               search-button    (seesaw/button :text "Load All")
               search-panel     (mig/mig-panel :background "#eee"
                                               :items [[search-label] [search-field "pushx, growx"]
                                                       [search-partial "hidemode 3, gap unrelated"]
                                                       [search-button "hidemode 3"]])
               layout           (seesaw/border-panel
                                 :center slots-scroll
                                 :south player-panel)
               stop-listener    (reify LifecycleListener
                                  (started [this sender]) ; Nothing to do, we exited as soon a stop happened anyway.
                                  (stopped [this sender]  ; Close our window if MetadataFinder stops (we need it).
                                    (seesaw/invoke-later
                                     (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))
               dev-listener     (reify DeviceAnnouncementListener
                                  (deviceFound [this announcement]
                                    (seesaw/invoke-later (add-device players (.getNumber announcement))))
                                  (deviceLost [this announcement]
                                    (if (empty? (.getCurrentDevices device-finder))
                                      (.stopped stop-listener))  ; If we lose all devices, close the window.
                                    (seesaw/invoke-later (remove-device players (.getNumber announcement)))))
               mount-listener   (reify MountListener
                                  (mediaMounted [this slot]
                                    (seesaw/invoke-later (add-slot-node slots-tree slot)))
                                  (mediaUnmounted [this slot]
                                    (swap! searches dissoc slot)
                                    (seesaw/invoke-later (remove-slot-node slots-tree slot))))
               status-listener  (reify DeviceUpdateListener
                                  (received [this status]
                                    (let [player @selected-player]
                                      (when (and (= (.getDeviceNumber status) (:number player))
                                                 (not= (.isPlaying status) (:playing player)))
                                        (swap! selected-player assoc :playing (.isPlaying status))
                                        (update-load-ui)))))
               remove-listeners (fn []
                                  (.removeMountListener metadata-finder mount-listener)
                                  (.removeLifecycleListener metadata-finder stop-listener)
                                  (.removeDeviceAnnouncementListener device-finder dev-listener)
                                  (.removeUpdateListener virtual-cdj status-listener))]
           (.setSelectionMode (.getSelectionModel slots-tree) javax.swing.tree.TreeSelectionModel/SINGLE_TREE_SELECTION)
           (.addMountListener metadata-finder mount-listener)
           (.addDeviceAnnouncementListener device-finder dev-listener)
           (.addUpdateListener virtual-cdj status-listener)
           (build-media-nodes slots-tree valid-slots)
           (build-device-choices players)
           (reset! loader-window root)
           (.addLifecycleListener metadata-finder stop-listener)
           (seesaw/listen root :window-closed (fn [e]
                                                (reset! loader-window nil)
                                                (remove-listeners)))
           (seesaw/listen slots-tree
                          :tree-will-expand
                          (fn [e]
                            (let [^TreeNode node    (.. e (getPath) (getLastPathComponent))
                                  ^IMenuEntry entry (.getUserObject node)]
                              (.loadChildren entry node)))
                          :selection
                          (fn [e]
                            (reset! selected-track
                                    (when (.isAddedPath e)
                                      (let [^IMenuEntry entry (.. e (getPath) (getLastPathComponent) (getUserObject))]
                                        (when (.isTrack entry)
                                          [(.getSlot entry) (.getId entry)]))))
                            (update-load-ui)
                            (let [search-path     (when (.isAddedPath e)
                                                    (trim-to-search-node-path (.getPath e)))
                                  search-node     (when search-path
                                                     (.expandPath slots-tree search-path)
                                                     (.. search-path getLastPathComponent))
                                  selected-search (when search-node (.. search-node getUserObject getSlot))]
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
               (.stopped stop-listener metadata-finder)))
           (seesaw/listen load-button
                          :action-performed
                          (fn [_]
                            (let [[slot-reference track] @selected-track
                                  selected-player        (.number (.getSelectedItem players))]
                              (.sendLoadTrackCommand virtual-cdj selected-player track
                                                     (.player slot-reference) (.slot slot-reference)
                                                     CdjStatus$TrackType/REKORDBOX))))
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
       (when-let [window @loader-window]
         (seesaw/show! window)
         (.toFront window)))))))
