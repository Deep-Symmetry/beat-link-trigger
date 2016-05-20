(ns beat-link-trigger.prefs
  "Functions for managing application preferences"
  (:require [clojure.edn :as edn]
            [beat-link-trigger.about :as about])
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

(defn get-preferences
  "Returns the current values of the user preferences, creating them
  if they did not exist."
  []
  (if-let [existing (.get (prefs-node) "prefs" nil)]
    (edn/read-string {:readers @prefs-readers} existing)
    {:beat-link-trigger-version (about/get-version)}))

(defn put-preferences
  "Updates the user preferences to reflect the map supplied."
  [m]
  (let [prefs (prefs-node)]
    (.put prefs "prefs" (prn-str m))
    (.flush prefs)))
