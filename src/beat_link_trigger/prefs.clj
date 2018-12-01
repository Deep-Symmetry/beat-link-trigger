(ns beat-link-trigger.prefs
  "Functions for managing application preferences"
  (:require [clojure.edn :as edn]
            [fipp.edn :as fipp]
            [beat-link-trigger.util :as util]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import java.util.prefs.Preferences))

(defonce ^{:doc "The custom readers needed to read in our preferences.
  Also used by the Show file reader."}
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
  {:beat-link-trigger-version (util/get-version)})

(defn- concatenate-preference-entries
  "Iterates over however many preferences entries were needed to store
  the trigger configuration, concatenating them into a single
  string."
  []
  (locking (prefs-node)
    (when-let [current (.get (prefs-node) "prefs" nil)]
      (loop [result     current
             next-index 1
             next-chunk (.get (prefs-node) (str "prefs-" next-index) nil)]
        (if (nil? next-chunk)
          result
          (recur (str result next-chunk) (inc next-index) (.get (prefs-node) (str "prefs-" (inc next-index)) nil)))))))

(defn get-preferences
  "Returns the current values of the user preferences, creating them
  if they did not exist."
  []
  (if-let [existing (concatenate-preference-entries)]
    (try
      (edn/read-string {:readers @prefs-readers} existing)
      (catch Exception e
        (timbre/error e "Problem reading preferences, starting with empty set.")
        (empty-preferences)))
    (empty-preferences)))

(defn- split-preference-entries
  "Chops up a string representing the current trigger configuration
  into as many preference entries as required to hold it, and stores
  them."
  [node value]
  (loop [index 0]
    (let [offset (* index Preferences/MAX_VALUE_LENGTH)
          remain (- (count value) offset)
          entry  (subs value offset (+ offset (min remain Preferences/MAX_VALUE_LENGTH)))]
      (.put node (str "prefs" (when (pos? index) (str "-" index))) entry)
      (when (> remain (count entry))
        (recur (inc index))))))

(defn put-preferences
  "Updates the user preferences to reflect the map supplied. Returns
  true if successful."
  [m]
  (try
    (let [prefs (prefs-node)]
      (locking prefs
        (.clear prefs)
        (binding [*print-length* nil
                  *print-level* nil]
          (split-preference-entries prefs (prn-str (merge m {:beat-link-trigger-version (util/get-version)}))))
        (.flush prefs))
      true)
    (catch Exception e
      (timbre/error e "Problem saving preferences.")
      (seesaw/alert (str "<html>Problem saving preferences.<br><br>" e)
                    :title "Unable to Save Preferences" :type :error))))

(defn save-to-file
  "Saves the preferences to a text file."
  [file]
  (spit file (binding [*print-length* nil
                       *print-level* nil]
               (with-out-str (fipp/pprint (get-preferences))))))

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
       (edn/read {:readers @prefs-readers} in))
     (throw (IllegalArgumentException. (str "Unreadable file: " file))))))

(defn load-from-file
  "Read the preferences from a text file."
  [file]
  (try
    (let [m (read-file file)]
      (put-preferences m)
      m)
    (catch Exception e
      (timbre/error e "Problem reading preferences.")
      (seesaw/alert (str "<html>Problem reading preferences.<br><br>" e)
                    :title "Unable to Read Preferences" :type :error))))
