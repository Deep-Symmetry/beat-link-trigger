(ns beat-link-trigger.util
  "Provides commonly useful utility functions."
  (:require [seesaw.core :as seesaw]
            [environ.core :refer [env]])
  (:import [org.deepsymmetry.beatlink DeviceFinder]))

(defn get-version
  "Returns the version tag from the project.clj file, either from the
  environment variable set up by Leiningen, if running in development
  mode, or from the jar manifest if running from a production build."
  []
  (or (env :beat-link-trigger-version)
      (when-let [pkg (.getPackage (class get-version))]
        (.getSpecificationVersion pkg))
      "DEV"))  ; Must be running in dev mode embedded in some other project

(defn get-java-version
  "Returns the version of Java in which we are running."
  []
  (str (System/getProperty "java.version")
       (when-let [vm-name (System/getProperty "java.vm.name")]
         (str ", " vm-name))
       (when-let [vendor (System/getProperty "java.vm.vendor")]
         (str ", " vendor))))

(defn get-build-date
  "Returns the date this jar was built, if we are running from a jar."
  []
  (when-let [pkg (.getPackage (class get-version))]
    (.getImplementationVersion pkg)))

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
  (when file
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
              result))))))

(defn visible-player-numbers
  "Return the set of players currently visible on the
  network (ignoring our virtual player, and any mixers or rekordbox
  instances)."
  []
  (filter #(< % 16) (map #(.getNumber %) (.getCurrentDevices (DeviceFinder/getInstance)))))

(defmacro case-enum
  "Like `case`, but explicitly dispatch on Java enum ordinals."
  {:style/indent 1}
  [e & clauses]
  (letfn [(enum-ordinal [e] `(let [^Enum e# ~e] (.ordinal e#)))]
    `(case ~(enum-ordinal e)
       ~@(concat
          (mapcat (fn [[test result]]
                    [(eval (enum-ordinal test)) result])
                  (partition 2 clauses))
          (when (odd? (count clauses))
            (list (last clauses)))))))

;; Used to represent the available players in the Triggers window
;; Watch menu, and the Load Track on a Player window. The `toString`
;; method tells Swing how to display it, and the number is what we
;; need for comparisons.
(defrecord PlayerChoice [number]
  Object
  (toString [_] (cond
                  (neg? number) "Any Player"
                  (zero? number) "Master Player"
                  :else (str "Player " number))))
