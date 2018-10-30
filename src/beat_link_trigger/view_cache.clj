(ns beat-link-trigger.view-cache
  "Provides an interface for viewing the contents of a metadata cache
  file, so triggers can be worked on without requiring access to an
  actual player and media stick."
  (:require [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink.data MetadataFinder DataReference MetadataCache]
           [javax.swing JFileChooser]))

(def ^:private metadata-finder
  "The object that can obtain track metadata and manage cache files."
  (MetadataFinder/getInstance))

(def ^:private slot
  "The slot reference we always use when building metadata from the
  cache file."
  (org.deepsymmetry.beatlink.CdjStatus$TrackSourceSlot/USB_SLOT))

(defn- make-window-visible
  "Ensures that a window is centered on the triggers
  window, in front, and shown."
  [parent-frame new-frame]
  (.setLocationRelativeTo new-frame parent-frame)
  (seesaw/show! new-frame)
  (.toFront new-frame))

(defn- create-model
  "Creates the Swing table model to show the metadata cache
  contents."
  [^MetadataCache cache]
  (let [column-names ["rekordbox id" "Title" "Artist"]
        ids (.getTrackIds cache)]
    (proxy [javax.swing.table.AbstractTableModel] []
      (getColumnCount [] (count column-names))
      (getRowCount [] (count ids))
      (isCellEditable [row col] false)
      (getColumnName [col] (nth column-names col))
      (getValueAt [row col]
        (let [id (nth ids row)]
          (if (zero? col)
            id
            (let [reference (DataReference. 1 slot id)
                  data (.getTrackMetadata cache nil reference)]
              (case col
                1 (.getTitle data)
                2 (.label (.getArtist data)))))))
      (setValueAt [s row col]))))

(defn- create-view
  "Create the window showing the contents of a chosen cache file."
  [parent-frame cache]
  (let [root (seesaw/frame :title (str "Contents of " (.getName cache))
                           :size [800 :by 400]
                           :on-close :dispose
                           :content (seesaw/scrollable (seesaw/table :model (create-model cache))))]
    (seesaw/listen root :window-closed (fn [e] (.close cache)))
    (make-window-visible parent-frame root)))

(defn choose-file
  "Provides an interface for the user to choose a file to list. The
  argument is a parent frame on which to center the chooser dialog and
  cavhe view window, if desired."
  [parent]
  (when-let [file (chooser/choose-file
                   parent
                   :all-files? false
                   :filters [["BeatLink metadata cache" ["bltm"]]])]
    (try
      (let [cache (MetadataCache. file)]
        (create-view parent cache))
      (catch Exception e
        (timbre/error e "Problem opening metadata cache" file)
        (seesaw/alert (str "<html>Unable to open Metadata Cache.<br><br>" e)
                      :title "Problem Opening Cache File" :type :error)))))
