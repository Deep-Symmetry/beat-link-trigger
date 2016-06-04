(ns beat-link-trigger.prefs
  "Functions for managing application preferences"
  (:require [clojure.edn :as edn]
            [fipp.edn :as fipp]
            [beat-link-trigger.about :as about]
            [taoensso.timbre :as timbre])
  (:import java.util.prefs.Preferences))

(defonce ^{:private true
           :doc "The custom readers needed to read in our preferences"}
  prefs-readers
  (atom {}))

(defn add-reader
  "Add a custom reader function that can be used to parse an
  application object out of the saved preferences."
  [tag f]
  (swap! prefs-readers assoc tag f))

(defn- prefs-node
  "Return the node at which we store our preferences."
  []
  (.node (Preferences/userRoot) "org.deepsymmetry.beat_link_trigger"))

(defn- empty-preferences
  "Returns the basic framework of an empty set of preferences."
  []
  {:beat-link-trigger-version (about/get-version)})

(defn convert-longs-to-integers
  "Walks the map built from the preferences, changing any Long
  values to Integers, since some Swing objects cannot cope with being
  set to a Long value."
  [elem]
  (cond
    (record? elem)  ; Don't dive into elements of our custom menu objects
    elem

    (map-entry? elem)
    (let [[k v] elem]
      (clojure.lang.MapEntry. k (convert-longs-to-integers v)))

    (or (sequential? elem) (map? elem))
    (clojure.walk/walk #(convert-longs-to-integers %) identity elem)

    (instance? Long elem)
    (int elem)

    :else elem))

(defn get-preferences
  "Returns the current values of the user preferences, creating them
  if they did not exist."
  []
  (if-let [existing (.get (prefs-node) "prefs" nil)]
    (try
      (convert-longs-to-integers (edn/read-string {:readers @prefs-readers} existing))
      (catch Exception e
        (timbre/error e "Problem reading preferences, starting with empty set.")
        (empty-preferences)))
    (empty-preferences)))

(defn put-preferences
  "Updates the user preferences to reflect the map supplied."
  [m]
  (try
    (let [prefs (prefs-node)]
      (.put prefs "prefs" (prn-str (merge m {:beat-link-trigger-version (about/get-version)})))
      (.flush prefs))
    (catch Exception e
      (timbre/error e "Problem saving preferences."))))

(defn save-to-file
  "Saves the preferences to a text file."
  [file]
  (spit file (with-out-str (fipp/pprint (get-preferences)))))

(defn valid-file?
  "Checks whether the specified file seems to be a valid save file. If
  so, returns it; otherwiser returns nil."
  ([file]
   (valid-file? :beat-link-trigger-version file))
  ([required-key file]
   (try
     (with-open [in (java.io.PushbackReader. (clojure.java.io/reader file))]
       (let [m (edn/read {:readers @prefs-readers} in)]
         (when (some? (get m required-key))
           m)))
     (catch Exception e
       (timbre/info e "Problem reading save file" file)))))

(defn read-file
  "Load a file with our custom readers but do not store the results in
  the preferences. Used to import individual triggers."
  ([file]
   (read-file :beat-link-trigger-version file))
  ([required-key file]
   (if (valid-file? required-key file)
     (with-open [in (java.io.PushbackReader. (clojure.java.io/reader file))]
       (convert-longs-to-integers (edn/read {:readers @prefs-readers} in)))
     (throw (IllegalArgumentException. (str "Unreadable file: " file))))))

(defn load-from-file
  "Read the preferences from a text file."
  [file]
  (if (valid-file? file)
    (let [m (read-file file)]
      (put-preferences m)
      m)))

