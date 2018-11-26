(ns beat-link-trigger.show
  "A higher-level interface for creating cues within the beat grids of
  efficiently recognized tracks, with support for offline loading of
  tracks and editing of cues."
  (:require [beat-link-trigger.util :as util]
            [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.track-loader :as loader]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [seesaw.chooser :as chooser]
            [seesaw.icon :as icon]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.sound.midi Sequencer Synthesizer]
           [java.awt.event WindowEvent]
           [org.deepsymmetry.beatlink Beat BeatFinder BeatListener CdjStatus CdjStatus$TrackSourceSlot
            DeviceAnnouncementListener DeviceFinder DeviceUpdateListener MixerStatus Util VirtualCdj]
           [org.deepsymmetry.beatlink.data ArtFinder BeatGridFinder MetadataFinder WaveformFinder SearchableItem
            SignatureFinder SignatureListener]
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

(defn- build-save-action
  "Creates the menu action to save a show window to a new file, given
  the root frame of the window."
  [root]
  (seesaw/action :handler (fn [e]
                            (when-let [file (chooser/choose-file root :type :save
                                                                 :all-files? false
                                                                 :filters [["BeatLinkTrigger Show files"
                                                                            ["blts"]]])]
                              (when-let [file (util/confirm-overwrite-file file "blts" root)]
                                (try
                                  (seesaw/alert "TODO: Implement saving!" :title "Saving Unfinished" :type :error)
                                  (catch Exception e
                                    (seesaw/alert root (str "<html>Unable to Save.<br><br>" e)
                                                  :title "Problem Writing File" :type :error))))))
                 :name "Save As"
                 :key "menu S"))

(defn- build-import-offline-action
  "Creates the menu action to import a track from offline media, given
  the root frame of the window."
  [root]
  (seesaw/action :handler (fn [e]
                            (when-let [[database track-row] (loader/choose-local-track root)]
                              (try
                                (seesaw/alert "TODO: Implement Importing!" :title "Importing Unfinished" :type :error)
                                (catch Exception e
                                  (seesaw/alert root (str "<html>Unable to Import.<br><br>" e)
                                                :title "Problem Finding Track Metadata" :type :error)))))
                 :name "from Offline Media"
                 :key "menu M"))

(defn- build-import-player-action
  "Creates the menu action to import a track from a player, given the
  root frame of the window and the player number. Enables or disables
  as appropriate, with text explaining why it is disabled."
  [root player]
  (let [signature (.getLatestSignatureFor signature-finder player)
        enabled?  (some? signature) ; TODO: Make sure the signature is not already present in the show.
        reason    (when-not enabled? " (no track signature)")]

    (seesaw/action :handler (fn [e]
                              (seesaw/alert root "TODO: Implement Importing from Player!" :title "Unfinished"
                                            :type :error))
                   :name (str "from Player " player reason)
                   :enabled? enabled?
                   :key (str "menu " player))))

(defn- build-import-submenu-items
  "Creates the submenu items for importing tracks from all available players
  and offline media, given the root frame of the window."
  [root]
  (let [current-players (sort (map (fn [status]
                                     (let [player (.getNumber status)]
                                       (when (< player 5) player)))
                                    (.getCurrentDevices device-finder)))]
    (concat (map (fn [player] (build-import-player-action root player)) (filter identity current-players))
            [(build-import-offline-action root)])))

(defn- build-close-action
  "Creates the menu action to close a show window."
  [root]
  (seesaw/action :handler (fn [e]
                            (seesaw/invoke-later
                             (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING))))
                 :name "Close"))

(defn- build-show-menubar
  "Creates the menu bar for a show window, given the root frame of the
  window and the import submenu."
  [root import-submenu]
  (seesaw/menubar :items [(seesaw/menu :text "File"
                                       :items (concat [(build-save-action root) (seesaw/separator)
                                                       import-submenu
                                                       (seesaw/separator) (build-close-action root)]))
                          (menus/build-help-menu)]))

(defonce ^{:private true
           :doc "The map of open shows; keys are the file, values are
           the root of the window."}
  open-shows (atom {}))

(defn- create-show-window
  "Create and show a new show window on the specified file."
  [file]
  (try
    (let [root         (seesaw/frame :title (str "Beat Link Show: " (.getPath file)) :on-close :dispose)
          import-menu  (seesaw/menu :text "Import Track")
          rebuild-im   (fn []
                         (.removeAll import-menu)
                         (seesaw/config! import-menu :items (build-import-submenu-items root)))
          dev-listener (reify DeviceAnnouncementListener  ; Update the import submenu as players come and go
                         (deviceFound [this _] (rebuild-im))
                         (deviceLost [this _] (rebuild-im)))
          sig-listener (reify SignatureListener  ; Update the import submenu as tracks come and go
                         (signatureChanged [this _] (rebuild-im)))]
      (swap! open-shows assoc file root)
      ;; TODO: Once we have component inside the frame, set up its user-data as an atom containing our
      ;; configuration information, including the file!
      (.addDeviceAnnouncementListener device-finder dev-listener)
      (.addSignatureListener signature-finder sig-listener)
      (rebuild-im)
      (seesaw/config! root :menubar (build-show-menubar root import-menu))
      (.setSize root 800 600)
      (.setLocationRelativeTo root nil)
      (seesaw/listen root :window-closed
                     (fn [e]
                       (swap! open-shows dissoc file)
                       (.removeDeviceAnnouncementListener device-finder dev-listener)
                       (.removeSignatureListener signature-finder sig-listener)))
      (seesaw/show! root))

    (catch Exception e
      (timbre/error e "Problem creating Show window."))))

(defn open
  "Opens a show file. If it is already open, just brings the window to
  the front."
  [parent]
(when-let [file (chooser/choose-file parent :type :open
                                     :all-files? false
                                     :filters [["BeatLinkTrigger Show files" ["blts"]]])]
  (let [file (.getCanonicalFile file)]
    (try
      (slurp file)  ; TODO: Actually read and use contents!
      (if-let [existing (get @open-shows file)]
      (.toFront existing)
      (create-show-window file))
    (catch Exception e
      (seesaw/alert parent (str "<html>Unable to Open Show.<br><br>" e)
                    :title "Problem Reading File" :type :error))))))

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
            (spit file "Nothing real yet!")  ; TODO: Actually write initial structure!
            (create-show-window file)
            (catch Exception e
              (seesaw/alert parent (str "<html>Unable to Create Show.<br><br>" e)
                            :title "Problem Writing File" :type :error))))))))
