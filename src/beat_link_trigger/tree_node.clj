(ns beat-link-trigger.tree-node
  "Defines the interfaces used in rendering the tree views of available
  playlists and dbserver menus. In its own namespace to avoid problems
  of class incompatibility when the namespaces using it are reloaded
  during development.")

(definterface IPlaylistEntry
    (^int getId [])
    (^boolean isFolder [])
    (^void loadChildren [^javax.swing.tree.TreeNode node]))

(definterface IMenuEntry
  (^int getId [])
  (^org.deepsymmetry.beatlink.data.SlotReference getSlot [])
  (^boolean isMenu [])
  (^boolean isTrack [])
  (^void loadChildren [^javax.swing.tree.TreeNode node]))
