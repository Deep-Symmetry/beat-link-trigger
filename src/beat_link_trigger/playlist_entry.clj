(ns beat-link-trigger.playlist-entry
  "Defines the IPlaylistEntry interface used in rendering the tree
  view of available playlists. In its own namespace to avoid problems
  of class incompatibility when the namespaces using it are reloaded
  during development.")

(definterface IPlaylistEntry
    (^int getId [])
    (^boolean isFolder [])
    (^void loadChildren [^javax.swing.tree.TreeNode node]))
