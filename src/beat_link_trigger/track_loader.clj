(ns beat-link-trigger.track-loader
  "Provides the user interface for exploring the menu trees of the
  available media databases, and loading tracks into players."
  (:require [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.tree-node]
            [beat-link-trigger.util :as util]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import beat_link_trigger.tree_node.IMenuEntry
           [javax.swing.tree DefaultMutableTreeNode DefaultTreeModel TreeNode]
           [org.deepsymmetry.beatlink CdjStatus CdjStatus$TrackSourceSlot CdjStatus$TrackType
            DeviceAnnouncementListener DeviceFinder DeviceUpdate LifecycleListener VirtualCdj]
           [org.deepsymmetry.beatlink.data MenuLoader MetadataFinder MountListener SlotReference]
           [org.deepsymmetry.beatlink.dbserver Message Message$MenuItemType]))

(defonce ^{:private true
           :doc     "Holds the singleton instance of the menu loader for our convenient use."}
  menu-loader (MenuLoader/getInstance))

(defn- explain-navigation-failure
  "Called when the user has asked to explore a player's media menus, and
  metadata cannot be requested. Try to explain the issue to the user."
  [^Exception e]
  (timbre/error e "Problem Accessing Player Media Menus")
  (let [device (.getDeviceNumber (VirtualCdj/getInstance))]
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
     (loadChildren [^javax.swing.tree.TreeNode node]
       (doseq [entry (.requestHistoryMenuFrom menu-loader slot-reference 0)]
         (.add node (menu-item-node entry slot-reference)))))
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
     (loadChildren [^javax.swing.tree.TreeNode node]
       (doseq [entry (.requestHistoryPlaylistFrom menu-loader slot-reference 0 (menu-item-id item))]
         (.add node (menu-item-node entry slot-reference)))))
   true))

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
     (loadChildren [^javax.swing.tree.TreeNode node]
       (doseq [entry (.requestHistoryPlaylistFrom menu-loader slot-reference 0 (menu-item-id item))]
         (.add node (menu-item-node entry slot-reference)))))
   false))

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
       (loadChildren [^javax.swing.tree.TreeNode node]))
     false)))

(defn- slot-node
  "Creates the tree node that will allow access to the media database in
  a particular player slot."
  [^SlotReference slot-reference]
  (let [label (str "Player " (.player slot-reference) " "
                   (expressions/case-enum (.slot slot-reference)
                     CdjStatus$TrackSourceSlot/CD_SLOT "CD"
                     CdjStatus$TrackSourceSlot/USB_SLOT "USB"))]
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] label)
       (getId [] (int 0))
       (getSlot [] slot-reference)
       (isMenu [] true)
       (isTrack [] false)
       (loadChildren [^javax.swing.tree.TreeNode node]
         (doseq [entry (.requestRootMenuFrom menu-loader slot-reference 0)]
           (.add node (menu-item-node entry slot-reference)))))
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
     (loadChildren [^javax.swing.tree.TreeNode node]))
   true))

(defn- build-media-nodes
  "Create the top-level media database nodes, which will lazily load any
  child menus from the corresponding database server when they are
  expanded."
  [mounted-slots]
  (let [root (root-node)
        children (map slot-node mounted-slots)]
    (doseq [child (sort-by str children)]
      (.add root child))
    root))

(defn show-dialog
  "Presents an interface in which the user can choose a track and load
  it into a player. If `slot` is provided, the corresponding slot will
  be initially chosen as the track source."
  ([]
   (show-dialog nil))
  ([^SlotReference slot]
   ;; TODO: Set up an event listener to add/remove slots as media comes and goes.
   ;; TODO: Need to add rekordbox collection slots for any rekordbox computers found on the network.
   ;; TODO: Make window go away if we go offline or stop requesting metadata.
   (seesaw/invoke-later
    (let [valid-slots (filter #(#{CdjStatus$TrackSourceSlot/USB_SLOT CdjStatus$TrackSourceSlot/SD_SLOT} (.slot %))
                              (.getMountedMediaSlots (MetadataFinder/getInstance)))]
      (if (seq valid-slots)
        (try
          (let [selected-id (atom nil)
                root        (seesaw/frame :title "Load Track on Player"
                                          :on-close :dispose :resizable? true)
                model       (DefaultTreeModel. (build-media-nodes valid-slots) true)
                tree        (seesaw/tree :model model)
                load-button (seesaw/button :text "Load" :enabled? false)
                panel       (mig/mig-panel :items [[(seesaw/scrollable tree) "span, grow, wrap"]
                                                   [load-button "wrap"]])
                failed      (atom false)]
            (.setSelectionMode (.getSelectionModel tree) javax.swing.tree.TreeSelectionModel/SINGLE_TREE_SELECTION)
            (seesaw/listen tree
                           :tree-will-expand
                           (fn [e]
                             (let [^TreeNode node    (.. e (getPath) (getLastPathComponent))
                                   ^IMenuEntry entry (.getUserObject node)]
                               (.loadChildren entry node)))
                           :selection
                           (fn [e]
                             (reset! selected-id
                                     (when (.isAddedPath e)
                                       (let [^IMenuEntry entry (.. e (getPath) (getLastPathComponent) (getUserObject))]
                                         (when (.isTrack entry)
                                           (.getId entry)))))
                             (seesaw/config! load-button :enabled? (some? @selected-id))))
            (.setVisibleRowCount tree 20)
            (try
              (.expandRow tree 1)
              (catch IllegalStateException e
                (explain-navigation-failure e)
                (reset! failed true)))
            (seesaw/listen load-button
                           :action-performed
                           (fn [action]
                             ;; TODO: Send the command to load the track.
                             (.dispose root)))
            (seesaw/config! root :content panel)
            (seesaw/pack! root)
            (.setLocationRelativeTo root nil)
            (if @failed
              (.dispose root)
              (seesaw/show! root)))
          (catch Exception e
            (timbre/error e "Problem Loading Track")
            (seesaw/alert (str "<html>Unable to Load Track on Player:<br><br>" (.getMessage e)
                               "<br><br>See the log file for more details.")
                          :title "Problem Loading Track" :type :error)))
        (seesaw/alert "There is no media mounted in any player media slot."
                      :title "Nowhere to Load Tracks From" :type :error))))))
