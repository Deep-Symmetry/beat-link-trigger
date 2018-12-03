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
           [java.nio.file Path Files FileSystems OpenOption CopyOption StandardCopyOption StandardOpenOption]
           [org.deepsymmetry.beatlink Beat BeatFinder BeatListener CdjStatus CdjStatus$TrackSourceSlot
            DeviceAnnouncement DeviceAnnouncementListener DeviceFinder DeviceUpdateListener LifecycleListener
            MixerStatus Util VirtualCdj]
           [org.deepsymmetry.beatlink.data ArtFinder BeatGrid BeatGridFinder CueList MetadataFinder WaveformFinder
            SearchableItem SignatureFinder SignatureListener SignatureUpdate]
           [org.deepsymmetry.beatlink.dbserver Message]
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

(def art-finder
  "A convenient reference to the ArtFinder singleton."
  (org.deepsymmetry.beatlink.data.ArtFinder/getInstance))

(def beatgrid-finder
  "A convenient reference to the BeatGridFinder singleton."
  (org.deepsymmetry.beatlink.data.BeatGridFinder/getInstance))

(def waveform-finder
  "A convenient reference to the WaveformFinder singleton."
  (org.deepsymmetry.beatlink.data.WaveformFinder/getInstance))

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

(defn- latest-show
  "Returns the current version of the show given a stale copy with which
  an object was created."
  [show]
  (get @open-shows (:file show)))

(defn- flush-show
  "Closes the ZIP fileystem so that changes are written to the actual
  show file, then reopens it."
  [show]
  (let [{:keys [file filesystem]} show]
    (try
      (.close filesystem)
      (finally
        (let [[reopened-filesystem] (open-show-filesystem file)]
          (swap! open-shows assoc-in [file :filesystem] reopened-filesystem))))))

(defn- write-message-path
  "Writes the supplied Message to the specified path, truncating any previously existing file."
  [message path]
  (with-open [channel (java.nio.channels.FileChannel/open path (into-array [StandardOpenOption/WRITE
                                                                            StandardOpenOption/CREATE_NEW]))]
    (.write message channel)))

(defn- track-present?
  "Checks whether there is already a track with the specified signature
  in the Show."
  [show signature]
  (Files/exists (build-filesystem-path (:filesystem show) "tracks" signature) (make-array java.nio.file.LinkOption 0)))

(defn- describe-disabled-reason
  "Returns a text description of why import from a player is disabled
  based on an associated track signature, or `nil` if it is not
  disabled, given the show map and a possibly-`nil` track signature."
  [show signature]
  (cond
    (nil? signature)                " (no track signature)"
    (track-present? show signature) " (already imported)"
    :else                           nil))

(defn- update-player-item-signature
  "Makes a player's entry in the import menu enabled or disabled (with
  an explanation), given the track signature that has just been
  associated with the player."
  [^SignatureUpdate sig-update show]
  (let [^javax.swing.JMenu import-menu (:import-menu show)
        new-signature                  (.signature sig-update)
        disabled-reason                (describe-disabled-reason show new-signature)
        ^javax.swing.JMenuItem item    (.getItem import-menu (dec (.player sig-update)))]
    (.setEnabled item (nil? disabled-reason))
    (.setText item (str "from Player " (.player sig-update) disabled-reason))))

(defn- item-label
  "Resolves a SearchableItem label safely, returning `nil` if the item
  is itself `nil`."
  [^SearchableItem item]
  (when item (.label item)))

(defn- write-metadata
  "Writes the metadata for a track being imported to the show
  filesystem."
  [track-root ^org.deepsymmetry.beatlink.data.TrackMetadata metadata]
  (write-edn-path {:artist          (item-label (.getArtist metadata))
                   :album           (item-label (.getAlbum metadata))
                   :comment         (.getComment metadata)
                   :date-added      (.getDateAdded metadata)
                   :duration        (.getDuration metadata)
                   :genre           (item-label (.getGenre metadata))
                   :key             (item-label (.getKey metadata))
                   :label           (item-label (.getLabel metadata))
                   :original-artist (item-label (.getOriginalArtist metadata))
                   :rating          (.getRating metadata)
                   :remixer         (item-label (.getRemixer metadata))
                   :tempo           (.getTempo metadata)
                   :title           (.getTitle metadata)}
                  (.resolve track-root "metadata.edn")))

(defn- write-cue-list
  "Writes the cue list for a track being imported to the show
  filesystem."
  [track-root ^CueList cue-list]
  (if (nil? (.rawMessage cue-list))
    (util/doseq-indexed idx [tag-byte-buffer (.rawTags cue-list)]
      (.rewind tag-byte-buffer)
      (let [bytes     (byte-array (.remaining tag-byte-buffer))
            file-name (str "cue-list-" idx ".kaitai")]
        (.get tag-byte-buffer bytes)
        (Files/write (.resolve track-root file-name) bytes (make-array OpenOption 0))))
    (write-message-path (.rawMessage cue-list) (.resolve track-root "cue-list.dbserver"))))

(defn- read-cue-list
  "Re-creates a CueList object from an imported track."
  [track-root]
  (if (Files/isReadable (.resolve track-root "cue-list.dbserver"))
    (with-open [input-stream (Files/newInputStream (.resolve track-root "cue-list.dbserver") (make-array OpenOption 0))
                data-stream  (java.io.DataInputStream. input-stream)]
      (CueList. (Message/read data-stream)))
    (loop [tag-byte-buffers []
           idx              0]
      (let [file-path (.resolve track-root (str "cue-list-" idx ".kaitai"))
            next-buffer (when (Files/isReadable file-path)
                          (with-open [file-channel (Files/newByteChannel file-path (make-array OpenOption 0))]
                            (let [buffer (java.nio.ByteBuffer/allocate (.size file-channel))]
                              (.read file-channel buffer)
                              (.flip buffer))))]
        (if next-buffer
          (recur (conj tag-byte-buffers next-buffer) (inc idx))
          (CueList. tag-byte-buffers))))))

(defn- import-track
  "Imports the supplied track map into the show, after validating that
  all required parts are present."
  [show track]
  (let [missing-elements (filter (fn [k] (not (get track k)))
                                 [:signature :metadata :beat-grid :preview :detail])]
    (if (seq missing-elements)
      (seesaw/alert (:frame show)
                    (str "<html>Unable to import track, missing required elements:<br>"
                         (clojure.string/join ", " (map name missing-elements)))
                    :title "Track Import Failed" :type :error)
      (let [{:keys [file filesystem frame contents]}              show
            {:keys [signature metadata beat-grid preview detail]} track
            track-root                                            (build-filesystem-path filesystem "tracks" signature)]
        (Files/createDirectories track-root (make-array java.nio.file.attribute.FileAttribute 0))
        (write-metadata track-root metadata)
        (write-cue-list track-root (.getCueList metadata))
        ;; Finally, flush the show to move the newly-created filesystem elements into the actual ZIP file. This
        ;; both protects against loss due to a crash, and also works around a Java bug which is creating temp files
        ;; in the same folder as the ZIP file when FileChannel/open is used with a ZIP filesystem.
        (flush-show show)))))

(defn- import-from-player
  "Imports the track loaded on the specified player to the show."
  [show player]
  (let [signature (.getLatestSignatureFor signature-finder player)]
    (if (track-present? show signature)
      (seesaw/alert (:frame show) (str "Track on Player " player " is already in the Show.")
                    :title "Can’t Re-import Track" :type :error)
      (let [metadata  (.getLatestMetadataFor metadata-finder player)
            beat-grid (.getLatestBeatGridFor beatgrid-finder player)
            preview   (.getLatestPreviewFor waveform-finder player)
            detail    (.getLatestDetailFor waveform-finder player)
            art       (.getLatestArtFor art-finder player)]
        (if (not= signature (.getLatestSignatureFor signature-finder player))
          (seesaw/alert (:frame show) (str "Track on Player " player " Changed during Attempted Import.")
                        :title "Track Import Failed" :type :error)
          (do
            (import-track show {:signature signature
                                :metadata       metadata
                                :beat-grid      beat-grid
                                :preview        preview
                                :detail         detail
                                :art            art})
            (update-player-item-signature (SignatureUpdate. player signature) (latest-show show))))))))

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
                                    (save-show-as (latest-show show) file)
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
                                ;; TODO: Remember to call (latest-show show) !
                                (seesaw/alert "TODO: Implement Importing!" :title "Importing Unfinished" :type :error)
                                (catch Exception e
                                  (seesaw/alert (:frame show) (str "<html>Unable to Import.<br><br>" e)
                                                :title "Problem Finding Track Metadata" :type :error)))))
                 :name "from Offline Media"
                 :key "menu M"))

(defn- build-import-player-action
  "Creates the menu action to import a track from a player, given the
  show map and the player number. Enables or disables as appropriate,
  with text explaining why it is disabled (but only if visible, to
  avoid mysterious extra width in the menu)."
  [show player]
  (let [visible? (some? (.getLatestAnnouncementFrom device-finder player))
        reason   (describe-disabled-reason show (when (.isRunning signature-finder)
                                                  (.getLatestSignatureFor signature-finder player)))]
    (seesaw/action :handler (fn [e] (import-from-player (latest-show show) player))
                   :name (str "from Player " player (when visible? reason))
                   :enabled? (nil? reason)
                   :key (str "menu " player))))

(defn- build-import-submenu-items
  "Creates the submenu items for importing tracks from all available players
  and offline media, given the show map."
  [show]
  (concat (map (fn [player]
                 (seesaw/menu-item :action (build-import-player-action show player)
                                   :visible? (and (.isRunning metadata-finder)
                                                  (some? (.getLatestAnnouncementFrom device-finder player)))))
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
  [show]
  (seesaw/menubar :items [(seesaw/menu :text "File"
                                       :items [(build-save-action show) (seesaw/separator)
                                               (build-close-action show)])
                          (seesaw/menu :text "Track"
                                       :items [(:import-menu show)])
                          (menus/build-help-menu)]))

(defn- update-player-item-visibility
  "Makes a player's entry in the import menu visible or invisible, given
  the device announcement describing the player and the show map."
  [^DeviceAnnouncement announcement show visible?]
  (let [^javax.swing.JMenu import-menu (:import-menu show)
        player                         (.getNumber announcement)]
    (when (and (< player 5)  ; Ignore non-players, and attempts to make players visible when we are offline.
               (or (.isRunning metadata-finder) (not visible?)))
      #_(timbre/info "Updating player" player "menu item visibility to" visible?)
      (let [^javax.swing.JMenuItem item (.getItem import-menu (dec player))]
        (when visible?  ; If we are becoming visible, first update the signature information we'd been ignoring before.
          (let [reason (describe-disabled-reason show (when (.isRunning signature-finder)
                                                        (.getLatestSignatureFor signature-finder player)))]
            (.setText item (str "from Player " player reason))))
        (.setVisible item visible?)))))

(defn- create-show-window
  "Create and show a new show window on the specified file."
  [file]
  (let [[filesystem contents] (open-show-filesystem file)]
    (try
      (let [root        (seesaw/frame :title (str "Beat Link Show: " (.getPath file)) :on-close :dispose)
            import-menu (seesaw/menu :text "Import Track")
            show        {:frame       root
                         :import-menu import-menu
                         :file        file
                         :filesystem  filesystem
                         :contents    contents}
            dev-listener (reify DeviceAnnouncementListener  ; Update the import submenu as players come and go.
                           (deviceFound [this announcement]
                             (update-player-item-visibility announcement show true))
                           (deviceLost [this announcement]
                             (update-player-item-visibility announcement show false)))
            mf-listener (reify LifecycleListener  ; Hide or show all players if we go offline or online.
                          (started [this sender]
                            (doseq [announcement (.getCurrentDevices device-finder)]
                              (update-player-item-visibility announcement show true)))
                          (stopped [this sender]
                            (doseq [announcement (.getCurrentDevices device-finder)]
                              (update-player-item-visibility announcement show false))))
            sig-listener (reify SignatureListener  ; Update the import submenu as tracks come and go.
                           (signatureChanged [this sig-update]
                             #_(timbre/info "signatureChanged:" sig-update)
                             (update-player-item-signature sig-update show)))
            window-name  (str "show-" (.getPath file))]
        (swap! open-shows assoc file show)
        (.addDeviceAnnouncementListener device-finder dev-listener)
        (.addLifecycleListener metadata-finder mf-listener)
        (.addSignatureListener signature-finder sig-listener)
        (seesaw/config! import-menu :items (build-import-submenu-items show))
        (seesaw/config! root :menubar (build-show-menubar show))
        (.setSize root 800 600)  ; TODO: Can remove once we are packing the window.
        (util/restore-window-position root (str "show-" (.getPath file)) nil)
        (seesaw/listen root
                       :window-closed
                       (fn [e]
                         (.removeDeviceAnnouncementListener device-finder dev-listener)
                         (.removeLifecycleListener metadata-finder mf-listener)
                         (.removeSignatureListener signature-finder sig-listener)
                         (swap! open-shows (fn [shows]
                                             (let [show (get shows file)]
                                               (try
                                                 (.close (:filesystem show))
                                                 (catch Throwable t
                                                   (timbre/error t "Problem closing Show file.")
                                                   (seesaw/alert root (str "<html>Problem Closing Show.<br><br>" e)
                                                                 :title "Problem Closing Show" :type :error))))
                                             (dissoc shows file)))
                         (swap! util/window-positions dissoc window-name))
                       #{:component-moved :component-resized}
                       (fn [e] (util/save-window-position root window-name)))
        (seesaw/show! root))
      (catch Throwable t
        (.close filesystem)
        (throw t)))))

(defn- open-internal
  "Opens a show file. If it is already open, just brings the window to
  the front. Returns truthy if the file was newly opened."
  [parent file]
  (let [file (.getCanonicalFile file)]
    (try
      (if-let [existing (get @open-shows file)]
        (.toFront (:frame existing))
        (do (create-show-window file)
            true))
      (catch Exception e
        (timbre/error e "Unable to open Show" file)
        (seesaw/alert parent (str "<html>Unable to Open Show " file "<br><br>" e)
                      :title "Problem Opening File" :type :error)
        false))))

(defn open
  "Let the user choose a show file and tries to open it. If already
  open, just brings the window to the front."
  [parent]
(when-let [file (chooser/choose-file parent :type :open
                                     :all-files? false
                                     :filters [["BeatLinkTrigger Show files" ["blts"]]])]
  (open-internal parent file)))

(defn reopen-previous-shows
  "Tries to reopen any shows that were open the last time the user quit."
  []
  (doseq [window (keys @util/window-positions)]
    (when (and (string? window)
               (.startsWith window "show-"))
      (when-not (open-internal nil (clojure.java.io/file (subs window 5)))
        (swap! util/window-positions dissoc window)))))  ; Remove saved position if show is no longer available.

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