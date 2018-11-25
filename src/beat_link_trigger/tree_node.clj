(ns beat-link-trigger.tree-node
  "Defines the interfaces used in rendering the tree views of available
  playlists, dbserver menus, and Crate Digger database file entries.
  In its own namespace to avoid problems of class incompatibility when
  the namespaces using it are reloaded during development.")

(definterface IPlaylistEntry
    (^int getId [])
    (^boolean isFolder [])
    (^void loadChildren [^javax.swing.tree.TreeNode node]))

(definterface IMenuEntry
  (^int getId [])
  (^org.deepsymmetry.beatlink.data.SlotReference getSlot [])
  (^org.deepsymmetry.beatlink.CdjStatus$TrackType getTrackType [])  ; nil if this is not a track
  (^boolean isSearch [])
  (^void loadChildren [^javax.swing.tree.TreeNode node]))
