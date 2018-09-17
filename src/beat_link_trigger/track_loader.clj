(ns beat-link-trigger.track-loader
  "Provides the user interface for exploring the menu trees of the
  available media databases, and loading tracks into players."
  (:require [beat-link-trigger.menus :as menus]
            [beat-link-trigger.tree-node]
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
      (getTrackType [] nil)
      (isSearch [] false)
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
                 (format "0x%x" (.getValue (nth (.arguments item) 6))))]  ; Show number if we can't name it.
    (DefaultMutableTreeNode.
     (proxy [Object IMenuEntry] []
       (toString [] (str (menu-item-label item) " [unrecognized (" kind ")" "]"))
       (getId [] (int -1))
       (getSlot [] slot-reference)
       (isMenu [] false)
       (getTrackType [] nil)
       (isSearch [] false)
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

(defn report-unrecognized-nodes
  "Tells the user that we don't know what to do with some nodes in the
  menu tree and offer to compose a report about them to help fix that."
  [unrecognized]
  ;; Format a report about each kind of node we did not recognize.
  (let [reports (map (fn [{:keys [node item]}]
                       (let [player (.. node getUserObject getSlot player)
                             device (.. device-finder (getLatestAnnouncementFrom player) getName)
                             menu   (clojure.string/join "->" (get-parent-list node))]
                         (str "When loading menu " menu " from device named " device ", don't understand: " item)))
                     unrecognized)]
    (doseq [report reports] (timbre/warn report))  ; First log them.
    (seesaw/invoke-later  ; Then alert the user and ask them to take action.
     (if (menus/mail-supported?)
       ;; Compose an email with all the details.
       (let [body    (clojure.string/replace (clojure.string/join "\n\n" reports) "\n" "\r\n")
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
  [^TreeNode node ^Message item]
  (let [^IMenuEntry entry (.getUserObject node)]
    (when (and (= -1 (.getId entry))
               (.contains (str node) " [unrecognized ("))
      (.getValue (nth (.arguments item) 6)))))

(defn- attach-node-children
  "Given a list of menu items which have been loaded as a node's
  children, adds them to the node. If none were found, adds an inert
  child to explain that the node was empty. If `builders` is supplied,
  it a map from menu item type to the function that should be called
  to create that kind of item in the current context (the ALL item is
  always contextual, and Key items mean different things when
  nested)."
  ([^DefaultMutableTreeNode node items ^SlotReference slot-reference]
   (attach-node-children node items slot-reference {}))
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
     (isMenu [] true)
     (getTrackType [] nil)
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
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
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
     (getTrackType [] nil)
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
     (getTrackType [] nil)
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
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
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
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
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
     (getTrackType [] nil)
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
     (getTrackType [] nil)
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
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestGenreMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

;; Creates a menu item node for all albums by a given artist in a
;; given genre. Invoked as a contextual handler for the ALL item.
(defn- create-all-genre-artist-albums-node
  "Handles the ALL menu item when listing genre artist albums. Creates
  an appropriate node to implement it."
  [genre-id ^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ALBUMS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestGenreArtistAlbumTrackMenuFrom menu-loader slot-reference 0 genre-id -1 -1)
                               slot-reference))))
   true))

;; Creates a menu item node for all artists in a given genre. Invoked
;; as a contextual handler for the ALL item.
(defn- create-all-genre-artists-node
  "Handles the ALL menu item when listing genre artists. Creates an
  appropriate node to implement it."
  [genre-id ^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ARTISTS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestGenreArtistAlbumMenuFrom menu-loader slot-reference 0 genre-id -1)
                               slot-reference
                               {Message$MenuItemType/ALL (partial create-all-genre-artist-albums-node genre-id)}))))
   true))

;; Creates a menu item node for a genre.
(defmethod menu-item-node Message$MenuItemType/GENRE genre-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (let [genre-id (menu-item-id item)]
           (attach-node-children node (.requestGenreArtistMenuFrom menu-loader slot-reference 0 genre-id)
                                 slot-reference
                                 {Message$MenuItemType/ALL (partial create-all-genre-artists-node genre-id)})))))
   true))

;; Creates a menu item node for all album tracks by an artist. Invoked
;; as a contextual handler for the ALL item.
(defn- create-all-artist-album-tracks-node
  "Handles the ALL menu item when listing artist albums. Creates an
  appropriate node to implement it."
  [artist-id ^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL TRACKS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestArtistAlbumTrackMenuFrom menu-loader slot-reference 0 artist-id -1)
                               slot-reference))))
   true))

;; Creates a menu item node for all albums by an artist. Invoked as a
;; contextual handler for the ALL item.
(defn- create-all-artist-albums-node
  "Handles the ALL menu item when listing artists. Creates an
  appropriate node to implement it."
  [artist-id ^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] "[ALL ALBUMS]")
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestArtistAlbumMenuFrom menu-loader slot-reference 0 artist-id)
                               slot-reference
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
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (let [artist-id (menu-item-id item)]
           (attach-node-children node (.requestArtistAlbumMenuFrom menu-loader slot-reference 0 artist-id)
                                 slot-reference
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
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestKeyMenuFrom menu-loader slot-reference 0) slot-reference))))
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
       (isMenu [] true)
       (getTrackType [] nil)
       (isSearch [] false)
       (loadChildren [^javax.swing.tree.TreeNode node]
         (when (unloaded? node)
           (let [distance (.getValue (first (.arguments item)))
                 key-id   (menu-item-id item)]
             (attach-node-children node (.requestTracksByKeyAndDistanceFrom menu-loader slot-reference 0
                                                                            key-id distance)
                                   slot-reference)))))
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
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (let [key-id (menu-item-id item)]
           (attach-node-children node (.requestKeyNeighborMenuFrom menu-loader slot-reference 0 key-id)
                                 slot-reference
                                 {Message$MenuItemType/KEY create-key-neighbor-node})))))
   true))

;; Creates a menu item node for the Rating menu.
(defmethod menu-item-node Message$MenuItemType/RATING_MENU rating-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] (int 0))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestRatingMenuFrom menu-loader slot-reference 0) slot-reference))))
   true))

(defn format-rating
  "Formats a numeric rating as a string of zero through five stars in a
  field of periods."
  [rating]
  (apply str (take 5 (concat (take rating (repeat "*")) (repeat ".")))))

;; Creates a menu item node for a rating node.
(defmethod menu-item-node Message$MenuItemType/RATING rating-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (format-rating (menu-item-id item)))
     (getId [] (int (menu-item-id item)))
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestTracksByRatingFrom menu-loader slot-reference 0 (menu-item-id item))
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
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestBpmMenuFrom menu-loader slot-reference 0) slot-reference))))
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
       (isMenu [] true)
       (getTrackType [] nil)
       (isSearch [] false)
       (loadChildren [^javax.swing.tree.TreeNode node]
         (when (unloaded? node)
           (attach-node-children node (.requestTracksByBpmRangeFrom menu-loader slot-reference 0 tempo distance)
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
       (isMenu [] true)
       (getTrackType [] nil)
       (isSearch [] false)
       (loadChildren [^javax.swing.tree.TreeNode node]
         (when (unloaded? node)
           (attach-node-children node (.requestBpmRangeMenuFrom menu-loader slot-reference 0 tempo)
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
     (isMenu [] false)
     (getTrackType [] CdjStatus$TrackType/UNANALYZED)
     (isSearch [] false)
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
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestFolderMenuFrom menu-loader slot-reference 0 (menu-item-id item))
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
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestFolderMenuFrom menu-loader slot-reference 0 -1) slot-reference
                               {Message$MenuItemType/FOLDER create-filesystem-folder-node
                                Message$MenuItemType/TRACK_TITLE create-filesystem-track-node}))))
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
     (getTrackType [] nil)
     (isSearch [] false)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (attach-node-children node (.requestAlbumTrackMenuFrom menu-loader slot-reference 0 (menu-item-id item))
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
     (isMenu [] false)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (isSearch [] false)
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
     (isMenu [] false)
     (getTrackType [] CdjStatus$TrackType/REKORDBOX)
     (isSearch [] false)
     (loadChildren [_]))
   false))

(defn- empty-search-node
  "Creates the node that explains what to do when a search has not been started."
  []
  (empty-node "[Select this section, then type text in the search field above to see matches.]"))

;; Creates a menu item node for the search interface.
(defmethod menu-item-node Message$MenuItemType/SEARCH_MENU search-menu-node
  [^Message item ^SlotReference slot-reference]
  (DefaultMutableTreeNode.
   (proxy [Object IMenuEntry] []
     (toString [] (menu-item-label item))
     (getId [] 0)
     (getSlot [] slot-reference)
     (isMenu [] true)
     (getTrackType [] nil)
     (isSearch [] true)
     (loadChildren [^javax.swing.tree.TreeNode node]
       (when (unloaded? node)
         (.add node (empty-search-node)))))
   true))


(defn- slot-label
  "Assembles the name used to describe a particular player slot, given
  the slot reference."
  [^SlotReference slot-reference]
  (let [base (str "Player " (.player slot-reference) " "
                  (util/case-enum (.slot slot-reference)
                    CdjStatus$TrackSourceSlot/SD_SLOT "SD"
                    CdjStatus$TrackSourceSlot/USB_SLOT "USB"))
        extra (when-let [details (.getMediaDetailsFor metadata-finder slot-reference)]
                (str (when-let [name (.name details)] (str ": " name))
                     (when (pos? (.trackCount details)) (str ", " (.trackCount details) " tracks"))
                     (when (pos? (.playlistCount details)) (str ", " (.playlistCount details) " playlists"))))]
    (str base extra)))

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
       (getTrackType [] nil)
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
     (getTrackType [] nil)
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
  specified player slot, removing it if it exists. If there are no
  slots left to load from, closes the window. Must be invoked on the
  Swing event dispatch thread."
  [^JTree tree ^SlotReference slot stop-listener]
  (when-let [node (find-slot-node tree slot)]
    (.removeNodeFromParent (.getModel tree) node)
    (let [model (.getModel tree)]
      (when (zero? (.getChildCount model (.getRoot model)))
        (.stopped stop-listener metadata-finder)))))

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
  (when (and path (> (.getPathCount path) 2))
    (loop [result path]
      (when-let [item (.. result getLastPathComponent getUserObject)]
        (if (.isSearch item)
          result
          (when (> (.getPathCount path) 3)
            (recur (.getParentPath result))))))))

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
  box. If that leaves us with no players, close the window."
  [players number stop-listener]
  (let [model (.getModel players)]
      (loop [index 0]
        (when (< index (.getSize model))
          (if (= number (.number (.getElementAt model index)))
            (.removeElementAt model index)
            (recur (inc index))))))
  (when (zero? (.getItemCount players))  ; No players left, close the window.
    (.stopped stop-listener metadata-finder)))

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
        next-size (when unfinished (+ loaded (min 1000 loaded)))]
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

(defn- search-load-more
  "The user has asked to load more from the active search."
  [search-partial search-button searches tree]
  (when-let [^SlotReference slot-reference (:current @searches)]
    (let [{:keys [text total path]} (get @searches slot-reference)
          node                      (.getLastPathComponent path)
          loaded                    (.getChildCount node)
          batch-size                (min 1000 loaded (- total loaded))
          results (.requestMoreSearchResultsFrom menu-loader (.player slot-reference) (.slot slot-reference)
                                                 0 text loaded batch-size)]
      (attach-node-children node results slot-reference)
      (configure-partial-search-ui search-partial search-button total (.getChildCount node))
      (.nodesWereInserted (.getModel tree) node (int-array (range loaded (+ loaded batch-size)))))))

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
                                    (seesaw/config! play-button :text (if playing "Stop and Cue" "Play if Cued"))))
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
                                  (started [this _]) ; Nothing to do, we exited as soon a stop happened anyway.
                                  (stopped [this _]  ; Close our window if MetadataFinder stops (we need it).
                                    (seesaw/invoke-later
                                     (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))
               dev-listener     (reify DeviceAnnouncementListener
                                  (deviceFound [this announcement]
                                    (seesaw/invoke-later (add-device players (.getNumber announcement))))
                                  (deviceLost [this announcement]
                                    (seesaw/invoke-later
                                     (remove-device players (.getNumber announcement) stop-listener))))
               mount-listener   (reify MountListener
                                  (mediaMounted [this slot]
                                    (seesaw/invoke-later (add-slot-node slots-tree slot)))
                                  (mediaUnmounted [this slot]
                                    (swap! searches dissoc slot)
                                    (seesaw/invoke-later (remove-slot-node slots-tree slot stop-listener))))
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
                                        (when (.getTrackType entry)
                                          [(.getSlot entry) (.getId entry) (.getTrackType entry)]))))
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
               (.stopped stop-listener metadata-finder))
             (catch Throwable t
               (.stopped stop-listener metadata-finder)  ; Clean up the window if we are blowing up...
               (throw t)))  ; ...but do rethrow the exception so the user sees the issue.
           (seesaw/listen load-button
                          :action-performed
                          (fn [_]
                            (let [[slot-reference track track-type] @selected-track
                                  selected-player                   (.number (.getSelectedItem players))]
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
       (when-let [window @loader-window]
         (seesaw/show! window)
         (.toFront window)))))))
