(ns beat-link-trigger.util
  "Provides commonly useful utility functions."
  (:require [seesaw.core :as seesaw])
  (:import [org.deepsymmetry.beatlink DeviceFinder]))

(defn confirm-overwrite-file
  "If the specified file already exists, asks the user to confirm that
  they want to overwrite it. If `required-extension` is supplied, the
  that extension is added to the end of the filename, if it is not
  already present, before checking. Returns the file to write if the
  user confirmed overwrite, or if a conflicting file did not already
  exist, and `nil` if the user said to cancel the operation. If a
  non-`nil` window is passed in `parent`, the confirmation dialog will
  be centered over it."
  [file required-extension parent]
  (let [required-extension (if (.startsWith required-extension ".")
                             required-extension
                             (str "." required-extension))
        file (if (or (nil? required-extension) (.. file (getAbsolutePath) (endsWith required-extension)))
               file
               (clojure.java.io/file (str (.getAbsolutePath file) required-extension)))]
    (or (when (not (.exists file)) file)
        (let [confirm (seesaw/dialog
                       :content (str "Replace existing file?\nThe file " (.getName file)
                                     " already exists, and will be overwritten if you proceed.")
                       :type :warning :option-type :yes-no)]
          (.pack confirm)
          (.setLocationRelativeTo confirm parent)
          (let [result (when (= :success (seesaw/show! confirm)) file)]
            (seesaw/dispose! confirm)
            result)))))

(defn visible-player-numbers
  "Return the set of players currently visible on the
  network (ignoring our virtual player, and any mixers or rekordbox
  instances)."
  []
  (filter #(< % 16) (map #(.getNumber %) (.getCurrentDevices (DeviceFinder/getInstance)))))
