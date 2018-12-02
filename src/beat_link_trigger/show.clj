(ns beat-link-trigger.show
  "A higher-level interface for creating cues within the beat grids of
  efficiently recognized tracks, with support for offline loading of
  tracks and editing of cues."
  (:require [beat-link-trigger.util :as util]
            [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.track-loader :as loader]
            [beat-link-trigger.prefs :as prefs]
            [clojure.edn :as edn]
            [fipp.edn :as fipp]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.icon :as icon]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.sound.midi Sequencer Synthesizer]
           [java.awt.event WindowEvent]
           [java.nio.file Path Files FileSystems OpenOption CopyOption StandardCopyOption]
           [org.deepsymmetry.beatlink Beat BeatFinder BeatListener CdjStatus CdjStatus$TrackSourceSlot
            DeviceAnnouncement DeviceAnnouncementListener DeviceFinder DeviceUpdateListener MixerStatus Util VirtualCdj]
           [org.deepsymmetry.beatlink.data ArtFinder BeatGridFinder MetadataFinder WaveformFinder SearchableItem
            SignatureFinder SignatureListener SignatureUpdate]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDestination CoreMidiDeviceProvider CoreMidiSource]))

(def device-finder
  "A convenient reference to the DeviceFinder singleton."
  (DeviceFinder/getInstance))

(def virtual-cdj
  "A convenient reference to the VirtualCdj singleton."
  (VirtualCdj/getInstance))

(def metadata-finder
  "A convenient reference to the MetadataFinder singleton."
  (MetadataFinder/getInstance))

(def signature-finder
  "A convenient reference to the SingatureFinder singleton."
  (SignatureFinder/getInstance))

(defonce ^{:private true
           :doc "The map of open shows; keys are the file, values are
  a map containing the root of the window, the file (for ease of
  updating the entry), the ZIP filesystem providing heierarcical
  access to the contents of the file, and the map describing them."}
  open-shows (atom {}))

(defn- save-all-open-shows
  "Closes the ZIP filesystems associated with any open shows, so they
  get flushed out to the show files. Called when the virtual machine
  is exiting as a shutdown hook to make sure changes get saved. It is
  not safe to call this at any other time because it makes the shows
  unusable."
  []
  (doseq [show (vals @open-shows)]
    (timbre/info "Closing Show due to shutdown:" (:file show))
    (try
      (.close (:filesystem show))
      (catch Throwable t
        (timbre/error t "Problem closing show filesystem" (:filesystem show))))))

(defonce ^{:private true
           :doc "Register the shutdown hook the first time this
  namespace is loaded."}
  shutdown-registered (do
                        (.addShutdownHook (Runtime/getRuntime) (Thread. save-all-open-shows))
                        true))

(defn- build-filesystem-path
  "Construct a path in the specified filesystem; translates from
  idiomatic Clojure to Java interop with the `java.nio` package."
  [filesystem & elements]
  (.getPath filesystem (first elements) (into-array String (rest elements))))

(defn- read-edn-path
  "Parse the file at the specified path as EDN, and return the results."
  [path]
  #_(timbre/info "Reading from" path "in filesystem" (.getFileSystem path))
  (edn/read-string {:readers @prefs/prefs-readers} (String. (Files/readAllBytes path) "UTF-8")))

(defn- write-edn-path
  "Write the supplied data as EDN to the specified path, truncating any previously existing file."
  [data path]
  #_(timbre/info "Writing" data "to" path "in filesystem" (.getFileSystem path))
  (binding [*print-length* nil
            *print-level* nil]
    (Files/write path (.getBytes (with-out-str (fipp/pprint data)) "UTF-8") (make-array OpenOption 0))))

(defn- open-show-filesystem
  "Opens a show file as a ZIP filesystem so the individual elements
  inside of it can be accessed and updated. In the process verifies
  that the file is, in fact, a properly formatted Show ZIP file.
  Returns the opened and validated filesystem and the parsed contents
  map."
  [file]
  (try
    (let [filesystem (FileSystems/newFileSystem (.toPath file) nil)
          contents (read-edn-path (build-filesystem-path filesystem "contents.edn"))]
      (when-not (= (:type contents) ::show)
        (throw (java.io.IOException. "Chosen file does not contain a Beat Link Trigger Show.")))
      (when-not (= (:version contents) 1)
        (throw (java.io.IOException. "Chosen Show is not supported by this version of Beat Link Trigger.")))
      [filesystem contents])
    (catch java.nio.file.ProviderNotFoundException e
      (throw (java.io.IOException. "Chosen file is not readable as a Show" e)))))

(defn- save-show-as
  "Closes the show filesystem to flush changes to disk, copies the file
  to the specified destination, then reopens it."
  [show as-file]
  (let [{:keys [frame file filesystem]} show]
    (try
      (.close filesystem)
      (Files/copy (.toPath file) (.toPath as-file) (into-array [StandardCopyOption/REPLACE_EXISTING]))
      (catch Throwable t
        (timbre/error t "Problem saving" file "as" as-file)
        (throw t))
      (finally
        (let [[reopened-filesystem] (open-show-filesystem file)]
          (swap! open-shows assoc-in [file :filesystem] reopened-filesystem))))))

(defn- build-save-action
  "Creates the menu action to save a show window to a new file, given
  the show map."
  [show]
  (seesaw/action :handler (fn [e]
                            (when-let [file (chooser/choose-file (:frame show) :type :save
                                                                 :all-files? false
                                                                 :filters [["BeatLinkTrigger Show files"
                                                                            ["blts"]]])]
                              (if (get @open-shows file)
                                (seesaw/alert (:frame show) "Cannot Replace an Open Show."
                                              :title "Destination is Already Open" :type :error)
                                (when-let [file (util/confirm-overwrite-file file "blts" (:frame show))]

                                  (try
                                    (save-show-as show file)
                                    (catch Throwable t
                                      (timbre/error t "Problem Saving Show as" file)
                                      (seesaw/alert (:frame show) (str "<html>Unable to Save As " file ".<br><br>" t)
                                                    :title "Problem Saving Show Copy" :type :error)))))))
                 :name "Save As"
                 :key "menu S"))

(defn- build-import-offline-action
  "Creates the menu action to import a track from offline media, given
  the show map."
  [show]
  (seesaw/action :handler (fn [e]
                            (when-let [[database track-row] (loader/choose-local-track (:frame show))]
                              (try
                                (seesaw/alert "TODO: Implement Importing!" :title "Importing Unfinished" :type :error)
                                (catch Exception e
                                  (seesaw/alert (:frame show) (str "<html>Unable to Import.<br><br>" e)
                                                :title "Problem Finding Track Metadata" :type :error)))))
                 :name "from Offline Media"
                 :key "menu M"))

(defn- describe-disabled-reason
  "Returns a text description of why import from a player is disabled
  based on an associated track signature, or `nil` if it is not
  disabled, given the show map and a possibly-`nil` track signature."
  [show signature]
  (cond
    (nil? signature)                            " (no track signature)"
    (get-in show [:contents :tracks signature]) " (already imported)"
    :else                                       nil))

(defn- build-import-player-action
  "Creates the menu action to import a track from a player, given the
  show map and the player number. Enables or disables as appropriate,
  with text explaining why it is disabled (but only if visible, to
  avoid mysterious extra width in the menu)."
  [show player]
  (let [visible? (some? (.getLatestAnnouncementFrom device-finder player))
        reason   (describe-disabled-reason show (.getLatestSignatureFor signature-finder player))]
    (seesaw/action :handler (fn [e]
                              (seesaw/alert (:frame show) "TODO: Implement Importing from Player!" :title "Unfinished"
                                            :type :error))
                   :name (str "from Player " player (when visible? reason))
                   :enabled? (nil? reason)
                   :key (str "menu " player))))

(defn- build-import-submenu-items
  "Creates the submenu items for importing tracks from all available players
  and offline media, given the show map."
  [show]
  (concat (map (fn [player]
                 (seesaw/menu-item :action (build-import-player-action show player)
                                   :visible? (some? (.getLatestAnnouncementFrom device-finder player))))
               (map inc (range 4)))
          [(build-import-offline-action show)]))

(defn- build-close-action
  "Creates the menu action to close a show window, given the show map."
  [show]
  (seesaw/action :handler (fn [e]
                            (seesaw/invoke-later
                             (.dispatchEvent (:frame show) (WindowEvent. (:frame show) WindowEvent/WINDOW_CLOSING))))
                 :name "Close"))

(defn- build-show-menubar
  "Creates the menu bar for a show window, given the show map and the
  import submenu."
  [show import-submenu]
  (seesaw/menubar :items [(seesaw/menu :text "File"
                                       :items [(build-save-action show) (seesaw/separator)
                                               (build-close-action show)])
                          (seesaw/menu :text "Track"
                                       :items [import-submenu])
                          (menus/build-help-menu)]))

(defn- update-player-item-signature
  "Makes a player's entry in the import menu enabled or disabled (with
  an explanation), given the track signature that has just been
  associated with the player."
  [^javax.swing.JMenu import-menu ^SignatureUpdate sig-update show]
  (let [new-signature               (.signature sig-update)
        disabled-reason             (describe-disabled-reason show new-signature)
        ^javax.swing.JMenuItem item (.getItem import-menu (dec (.player sig-update)))]
    (.setEnabled item (nil? disabled-reason))
    (.setText item (str "from Player " (.player sig-update) disabled-reason))))

(defn- update-player-item-visibility
  "Makes a player's entry in the import menu visible or invisible, given
  the device announcement describing the player and the show map."
  [^javax.swing.JMenu import-menu ^DeviceAnnouncement announcement show visible?]
  (let [^javax.swing.JMenuItem item (.getItem import-menu (dec (.getNumber announcement)))]
    (when visible?  ; If we are becoming visible, first update the signature information we'd been ignoring before.
      (let [reason  (describe-disabled-reason show (.getLatestSignatureFor signature-finder (.getNumber announcement)))]
        (.setText item (str "from Player " (.getNumber announcement) reason))))
    (.setVisible item visible?)))

(defn- create-show-window
  "Create and show a new show window on the specified file."
  [file]
  (let [[filesystem contents] (open-show-filesystem file)]
    (try
      (let [root         (seesaw/frame :title (str "Beat Link Show: " (.getPath file)) :on-close :dispose)
            show         {:frame      root
                          :file       file
                          :filesystem filesystem
                          :contents   contents}
            import-menu  (seesaw/menu :text "Import Track" :items (build-import-submenu-items show))
            dev-listener (reify DeviceAnnouncementListener  ; Update the import submenu as players come and go
                           (deviceFound [this announcement]
                             (update-player-item-visibility import-menu announcement show true))
                           (deviceLost [this announcement]
                             (update-player-item-visibility import-menu announcement show false)))
            sig-listener (reify SignatureListener  ; Update the import submenu as tracks come and go
                           (signatureChanged [this sig-update]
                             (update-player-item-signature import-menu sig-update show)))]

        (swap! open-shows assoc file show)
        (.addDeviceAnnouncementListener device-finder dev-listener)
        (.addSignatureListener signature-finder sig-listener)
        (seesaw/config! root :menubar (build-show-menubar show import-menu))
        (.setSize root 800 600)  ; TODO: Can remove once we are packing the window.
        (util/restore-window-position root (str "show-" (.getPath file)) nil)
        (seesaw/listen root
                       :window-closed
                       (fn [e]
                         (.removeDeviceAnnouncementListener device-finder dev-listener)
                         (.removeSignatureListener signature-finder sig-listener)
                         (swap! open-shows (fn [shows]
                                             (let [show (get shows file)]
                                               (try
                                                 (.close (:filesystem show))
                                                 (catch Throwable t
                                                   (timbre/error t "Problem closing Show file.")
                                                   (seesaw/alert root (str "<html>Problem Closing Show.<br><br>" e)
                                                                 :title "Problem Closing Show" :type :error))))
                                             (dissoc shows file))))
                       #{:component-moved :component-resized}
                       (fn [e] (util/save-window-position root (str "show-" (.getPath file)))))
        (seesaw/show! root))
      (catch Throwable t
        (.close filesystem)
        (throw t)))))

(defn open
  "Opens a show file. If it is already open, just brings the window to
  the front."
  [parent]
(when-let [file (chooser/choose-file parent :type :open
                                     :all-files? false
                                     :filters [["BeatLinkTrigger Show files" ["blts"]]])]
  (let [file (.getCanonicalFile file)]
    (try
      (if-let [existing (get @open-shows file)]
        (.toFront (:frame existing))
        (create-show-window file))
      (catch Exception e
        (timbre/error e "Unable to open Show.")
        (seesaw/alert parent (str "<html>Unable to Open Show.<br><br>" e)
                      :title "Problem Opening File" :type :error))))))

(defn new
  "Creates a new show file and opens a window on it."
  [parent]
  (when-let [file (chooser/choose-file parent :type :save
                                       :all-files? false
                                       :filters [["BeatLinkTrigger Show files"
                                                  ["blts"]]])]
    (let [file (.getCanonicalFile file)]
      (if (get @open-shows file)
        (seesaw/alert parent "Cannot Replace an Open Show."
                      :title "Show is Already Open" :type :error)
        (when-let [file (util/confirm-overwrite-file file "blts" parent)]
          (try
            (Files/deleteIfExists (.toPath file))
            (let [file-uri (.toUri (.toPath file))]
              (with-open [filesystem (FileSystems/newFileSystem (java.net.URI. (str "jar:" (.getScheme file-uri))
                                                                               (.getPath file-uri) nil)
                                                                {"create" "true"})]
                (write-edn-path {:type ::show :version 1} (build-filesystem-path filesystem "contents.edn"))))
            (create-show-window file)
            (catch Exception e
              (seesaw/alert parent (str "<html>Unable to Create Show.<br><br>" e)
                            :title "Problem Writing File" :type :error))))))))
