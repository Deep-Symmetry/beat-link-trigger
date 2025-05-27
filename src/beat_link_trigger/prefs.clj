(ns beat-link-trigger.prefs
  "Functions for managing the application preferences."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [fipp.edn :as fipp]
            [beat-link-trigger.util :as util]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [java.lang.ref WeakReference]
           [java.util.prefs Preferences]
           [javax.swing JFrame SwingUtilities UIManager]
           [com.jthemedetecor OsThemeDetector]))

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
  ^Preferences []
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
    (when-let [current ^Preferences (.get (prefs-node) "prefs" nil)]
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
  [^Preferences node value]
  (loop [index 0]
    (let [offset (* index Preferences/MAX_VALUE_LENGTH)
          remain (- (count value) offset)
          entry  (subs value offset (+ offset (min remain Preferences/MAX_VALUE_LENGTH)))]
      (.put node (str "prefs" (when (pos? index) (str "-" index))) entry)
      (when (> remain (count entry))
        (recur (inc index))))))

(defn put-preferences
  "Updates the user preferences to reflect the map supplied. Returns
  `true` if successful."
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
  so, returns it; otherwiser returns `nil`."
  ([file]
   (valid-file? :beat-link-trigger-version file))
  ([required-key file]
   (try
     (with-open [in (java.io.PushbackReader. (io/reader file))]
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
     (with-open [in (java.io.PushbackReader. (io/reader file))]
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

;;; Support for user-interface themes

(def ui-names
  "A map from the keywords by which we identify supported user
  interface themes to their descriptive names."
  {:flatlaf-default "FlatLaf Default"
   :flatlaf-darcula "FlatLaf IntelliJ / Darcula"
   :flatlaf-macos   "FlatLaf macOS"
   :custom          "Custom"})

(def ui-themes
  "A map from descriptive name to the keyword by which we identify a
  supported user interface theme."
  (set/map-invert ui-names))

(def ^:private ui-frames
  "Holds the list of weak references to user interface frames that
  should be updated when the user interface theme changes."
  (atom '()))

(defn cleared?
  "Predicate that checks whether a weak reference has been cleared (is
  now empty)."
  [^WeakReference r]
  (nil? (.get r)))

(defn- register-internal
  "Holds the logic common to registering a new entry in one of the lists
  related to responding to user interface theme changes."
  [entry list-atom]
  (swap! list-atom (fn [existing] (conj (remove cleared? existing) (WeakReference. entry)))))

(defn- unregister-internal
  "Holds the logic common to removing an entry from one of the lists
  related to responding to user interface theme changes."
  [entry list-atom]
  (doseq [^WeakReference existing-ref @list-atom]
    (when (.refersTo existing-ref entry)
      (.clear existing-ref)))
  (swap! list-atom (partial remove cleared?)))

(defn register-ui-frame
  "This function adds a frame (window) to the list that will be updated
  whenever the user chooses a different interface theme, or dark mode
  turns on or off. The list holds weak references, so it does not
  prevent the frames from being garbage collected, and they will be
  cleaned out of the list when that happens."
  [frame]
  (register-internal frame ui-frames))

(defn unregister-ui-frame
  "This function removes a frame (window) from the list that will be
  updated whenever the user chooses a different interface theme, or
  dark mode turns on or off."
  [frame]
  (doseq [^WeakReference frame-ref @ui-frames]
    (when (.refersTo frame-ref frame)
      (.clear frame-ref)))
  (unregister-internal frame ui-frames))

(def ^:private gear-buttons
  "Holds the list of weak references to gear buttons that should be
  updated when the user interface theme changes."
  (atom '()))

(defn register-gear-button
  "This function adds a gear button to the list that will be updated
  whenever the user chooses a different interface theme, or dark mode
  turns on or off. The list holds weak references, so it does not
  prevent the buttons from being garbage collected, and they will be
  cleaned out of the list when that happens."
  [button]
  (register-internal button gear-buttons))

(defn unregister-gear-button
  "This function removes a gear button from the list that will be
  updated whenever the user chooses a different interface theme, or
  dark mode turns on or off."
  [button]
  (unregister-internal button gear-buttons))

(def ui-change-callbacks
  "Holds the list of weak references to functions that should be
  called when the user interface theme changes. Each will be called
  with the current dark mode state and user preferences values."
  (atom '()))

(defn register-ui-change-callback
  "This function adds a function to the list that will be called
  whenever the user chooses a different interface theme, or dark mode
  turns on or off. Each function will be called with the current dark
  mode state and user preferences values. The list holds weak
  references, so it does not prevent the functions from being garbage
  collected, and they will be cleaned out of the list when that
  happens."
  [f]
  (register-internal f ui-change-callbacks))

(defn unregister-ui-change-callback
  "This function removes a function from the list that will be called
  whenever the user chooses a different interface theme, or dark mode
  turns on or off."
  [f]
  (unregister-internal f ui-change-callbacks))

(def theme-detector
  "The object that helps us probe system theme information."
  (OsThemeDetector/getDetector))

(def ^:private custom-themes
  "The custom light and dark themes registered by user expressions, if any."
  (atom {}))

(defn dark-mode?
  "Checks whether we are currently configured to use a dark mode,
  possibly by way of the operating system. If the preferences have
  already been loaded, they can be passed in."
  ([]
   (dark-mode? (get-preferences)))
  ([preferences]
   (case (:ui-mode preferences)
     :light false
     :dark  true
     (.isDark theme-detector))))  ; Using system setting.


(defn gear-icon
  "Returns the appropriate icon to use for a gear button, depending on
  its fill state, and the user interface darkness mode. If the
  preferences have already been loaded, they can be passed as a second
  parameter to save loading them again."
  ([filled?]
   (gear-icon filled? (get-preferences)))
  ([filled? preferences]
   (seesaw/icon (str "images/Gear-"
                     (if filled? "icon" "outline")
                     (when-not (dark-mode? preferences) "-black")
                     ".png"))))

(defn update-gear-button
  "Updates the icon of a gear button to reflect a filled state, in a way
  appropriate to the current user interface darkness mode, and
  recording the filled state so the button can be updated properly if
  that darkness mode changes."
  [gear filled?]
  (seesaw/config! gear :icon (gear-icon filled?) :user-data filled?))

(defn set-ui-theme
  "Called at startup to apply the user's chosen interface theme, and
  whenever that is changed, or when the host operating system goes or
  out of dark mode. If the dark mode state is already known, and if
  the preferences have already been loaded, they can be passed in to
  avoid redundant work."
  ([]
   (let [preferences (get-preferences)]
     (set-ui-theme (dark-mode? preferences) preferences)))
  ([dark?]
   (set-ui-theme dark? (get-preferences)))
  ([dark? preferences]
   (let [theme (case [(:ui-theme preferences :flatlaf-darcula) dark?]
                 [:flatlaf-default false] (com.formdev.flatlaf.FlatLightLaf.)
                 [:flatlaf-default true]  (com.formdev.flatlaf.FlatDarkLaf.)
                 [:flatlaf-darcula false] (com.formdev.flatlaf.FlatIntelliJLaf.)
                 [:flatlaf-darcula true]  (com.formdev.flatlaf.FlatDarculaLaf.)
                 [:flatlaf-macos false]   (com.formdev.flatlaf.themes.FlatMacLightLaf.)
                 [:flatlaf-macos true]    (com.formdev.flatlaf.themes.FlatMacDarkLaf.)
                 [:custom false]          (if-let [custom-class (:light @custom-themes)]
                                            (.newInstance custom-class)
                                            (com.formdev.flatlaf.FlatIntelliJLaf.))
                 [:custom true]           (if-let [custom-class  (:dark @custom-themes)]
                                            (.newInstance custom-class)
                                            (com.formdev.flatlaf.FlatDarculaLaf.)))]
     (seesaw/invoke-later
       (try
         (UIManager/setLookAndFeel theme)
         (let [preferences (get-preferences)]
           (doseq [^WeakReference button-ref (swap! gear-buttons (partial remove cleared?))]
             (when-let [button (.get button-ref)]
               (seesaw/config! button :icon (gear-icon (seesaw/user-data button) preferences)))))
         (doseq [^WeakReference callback-ref (swap! ui-change-callbacks (partial remove cleared?))]
           (when-let [f (.get callback-ref)]
             (try
               (f dark? preferences)
               (catch Throwable t
                 (timbre/error t "Problem in user interface theme change callback")))))
         (doseq [^WeakReference frame-ref (swap! ui-frames (partial remove cleared?))]
           (when-let [^JFrame frame (.get frame-ref)]
             (SwingUtilities/updateComponentTreeUI frame)))
         (catch Throwable t
           (timbre/error t "Unable to set UI theme to" theme)))))))

(defn register-custom-theme
  "Allows users to specify a non-standard user interface look and feel.
  The arguments are a boolean indicating whether it is a dark theme,
  and the custom look and feel class to be used for that mode."
  [dark? laf]
  (when (or (not (class? laf))
            (not (.isAssignableFrom javax.swing.LookAndFeel laf)))
    (throw (IllegalArgumentException. "Can only register LookAndFeel subclasses.")))
  (swap! custom-themes assoc (if dark? :dark :light) laf)
  (set-ui-theme))
