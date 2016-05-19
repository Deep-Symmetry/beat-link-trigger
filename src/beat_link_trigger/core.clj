(ns beat-link-trigger.core
  "Send MIDI or OSC events when a CDJ starts playing."
  (:require [overtone.midi :as midi]
            [seesaw.cells]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig])
  (:import [javax.sound.midi Sequencer Synthesizer]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDeviceProvider CoreMidiDestination CoreMidiSource]
           [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Beat CdjStatus MixerStatus Util]))

(defn on-mac?
  "Do we seem to be running on a Mac?"
  []
  (try
    (Class/forName "com.apple.eawt.Application")
    true  ; We found the Mac-only Java interaction classes
    (catch Exception e
      false)))

;; Used to represent the available players in the Watch menu. The `toString` method tells
;; Swing how to display it, and the number is what we need for comparisons.
(defrecord PlayerChoice [number]
  Object
  (toString [_] (str "Player " number)))

;; Used to represent the available MIDI outputs in the output menu. The `toString` method
;; tells Swing how to display it, so we can suppress the CoreMidi4J prefix.
(defrecord MidiChoice [full-name]
  Object
  (toString [_] (clojure.string/replace full-name #"^CoreMIDI4J - " "")))

(defn usable-midi-device?
  "Returns true if a MIDI device should be visible. Filters out non-CoreMidi4J devices when that library
  is active."
  [device]
  (or (not (CoreMidiDeviceProvider/isLibraryLoaded))
      (let [raw-device (:device device)]
        (or (instance? Sequencer raw-device) (instance? Synthesizer raw-device)
            (instance? CoreMidiDestination raw-device) (instance? CoreMidiSource raw-device)))))

(defn get-midi-outputs
  "Returns all available MIDI output devices as menu choice model objects"
  []
  (map #(MidiChoice. (:name %)) (filter usable-midi-device? (midi/midi-sinks))))

;; The following should let us put the actual MIDI devices in the combo box, and have it display
;; their names, by using the options `:renderer (string-renderer :name)` in creating it. That starts
;; out working fine, but when you try to set the selection of the combo box, it displays the whole
;; MIDI object until you click on it, and gets hugely wide forever, so I am giving up and just
;; using the names alone in the combo box model for now.
(defn- string-renderer
  "Provide a way to render a string value for a more complex object within a combobox model.
  Takes a function `f` which is called with each element of the model, and returns the string
  to be rendered for it."
  [f]
  (seesaw.cells/default-list-cell-renderer (fn [this {:keys [value]}] (.setText this (str (f value))))))

(defn get-player-choices
  "Returns a sorted list of the player choices. This used to only
  return currently-visible players, but we now return all valid
  choices because you might be loading a configuration you want to
  edit in an offline setting."
  []
  (for [i (range 1 5)]
    (PlayerChoice. i)))

(defonce ^{:private true
           :doc "Holds a map of all the MIDI output devices we have
  opened, keyed by their names, so we can reuse them."}
  opened-outputs (atom {}))

(declare create-trigger-window)

(def new-trigger-action
  "The menu action which opens a new Beat Link Trigger window."
  (seesaw/action :handler (fn [e] (create-trigger-window))
                 :name "New Trigger"
                 :key "menu T"))

(defonce ^{:private true
           :doc "Holds a map whose keys are the currently-open trigger
  windows, and whose values are true if the player they are monitoring
  has been reported as active."}
  open-triggers (atom {}))


(defn- midi-environment-changed
  "Called when CoreMidi4J reports a change to the MIDI environment, so we can update the menu of
  available MIDI outputs."
  []
  (seesaw/invoke-later  ; Need to move to the AWT event thread, since we interact with GUI objects
   (let [new-outputs (get-midi-outputs)]
     (doseq [trigger (keys @open-triggers)]  ; Update the output menus in all trigger windows
       (let [output-menu (seesaw/select trigger [:#outputs])
             old-selection (seesaw/selection output-menu)]
         (seesaw/config! output-menu :model new-outputs)  ; Update the content of the output menu

         ;; If our old output selection is still available, restore it
         (when ((set new-outputs) old-selection)
           (seesaw/selection! output-menu old-selection))))

     ;; Remove any opened outputs that are no longer available in the MIDI environment
     (swap! opened-outputs #(apply dissoc % (clojure.set/difference (set (keys %)) (set new-outputs)))))))

(defn- get-chosen-output
  "Return the MIDI output to which messages should be sent for a given
  trigger, opening it if this is the first time we are using it, or
  reusing it if we already opened it."
  [trigger]
  (let [output-menu (seesaw/select trigger [:#outputs])
        selection (seesaw/selection output-menu)
        device-name (.full_name selection)]
    (or (get @opened-outputs device-name)
        (let [new-output (midi/midi-out device-name)]
          (swap! opened-outputs assoc device-name new-output)
          new-output))))

(defn- report-activation
  "Send a message indicating the player a trigger is watching has
  started playing."
  [trigger]
  (let [note (seesaw/value (seesaw/select trigger [:#note]))
        channel (dec (seesaw/value (seesaw/select trigger [:#channel])))]
    (if (= "Note" (seesaw/value (seesaw/select trigger [:#message])))
      (midi/midi-note-on (get-chosen-output trigger) note 127 channel)
      (midi/midi-control (get-chosen-output trigger) note 127 channel))))

(defn- report-deactivation
  "Send a message indicating the player a trigger is watching has
  started playing."
  [trigger]
  (let [note (seesaw/value (seesaw/select trigger [:#note]))
        channel (dec (seesaw/value (seesaw/select trigger [:#channel])))]
    (if (= "Note" (seesaw/value (seesaw/select trigger [:#message])))
      (midi/midi-note-off (get-chosen-output trigger) note channel)
      (midi/midi-control (get-chosen-output trigger) note 0 channel))))

(defn- update-device-state
  "If the device state being watched by a trigger has changed, send an
  appropriate message and record the new state."
  [trigger new-state]
  (swap! open-triggers
         (fn [triggers]
           (when (not= new-state (get triggers trigger))
             (if new-state (report-activation trigger) (report-deactivation trigger)))
           (assoc triggers trigger new-state))))

(defn build-status-label
  "Create a brief textual summary of a player state given a status
  update object from beat-link."
  [status]
  (let [beat (.getBeatNumber status)]
    (str (if (.isPlaying status) "Playing" "Stopped") ", Track #" (.getTrackNumber status)
         ", " (format "%.1f" (.getEffectiveTempo status)) " BPM ("
         (format "%+.2f%%" (Util/pitchToPercentage (.getPitch status))) ")"
         (cond
           (neg? beat) ", beat n/a"
           (zero? beat) ", lead-in"
           :else (str ", beat " beat " (" (inc (quot (dec beat) 4)) "." (inc (rem (dec beat) 4)) ")")))))

(defn- show-device-status
  "Set the device satus label for a trigger outside of the context of
  receiving an update from the device (for example, the user chose a
  device in the menu which is not present on the network, or we just
  received a notification from the DeviceFinder that the device has
  disappeared."
  [trigger]
  (let [player-menu (seesaw/select trigger [:#players])
        selection (seesaw/selection player-menu)
        status-label (seesaw/select trigger [:#status])]
    (if (nil? selection)
      (do (seesaw/config! status-label :foreground "red")
          (seesaw/value! status-label "No Player selected.")
          (update-device-state trigger false))
      (let [found (when (DeviceFinder/isActive) (DeviceFinder/getLatestAnnouncementFrom (int (.number selection))))
            status (when (VirtualCdj/isActive) (VirtualCdj/getLatestStatusFor (int (.number selection))))]
        (if (nil? found)
          (do (seesaw/config! status-label :foreground "red")
              (seesaw/value! status-label (if (DeviceFinder/isActive) "Player not found." "Offline."))
              (update-device-state trigger false))
          (if (instance? CdjStatus status)
            (do (seesaw/config! status-label :foreground "black")
                (seesaw/value! status-label (build-status-label status)))
            (do (seesaw/config! status-label :foreground "red")
                (seesaw/value! status-label (cond (some? status) "Non-Player status received."
                                                  (not (VirtualCdj/isActive)) "Offline."
                                                  :else "No status received.")))))))))

(defn- rebuild-all-device-status
  "Updates all player status descriptions to reflect the devices
  currently found on the network. Called when the set of available
  devices changes."
  []
  (doseq [trigger (keys @open-triggers)]
    (show-device-status trigger)))

(defonce ^{:private true
           :doc "Responds to player status updates and updates the
  state of any triggers watching them."}
  status-listener
  (reify org.deepsymmetry.beatlink.DeviceUpdateListener
    (received [this status]
      (doseq [trigger (keys @open-triggers)]
        (let [player-menu (seesaw/select trigger [:#players])
              selection (seesaw/selection player-menu)
              status-label (seesaw/select trigger [:#status])
              enabled (seesaw/value (seesaw/select trigger [:#enabled]))]
          (when (and (instance? CdjStatus status) (some? selection) (= (.number selection) (.getDeviceNumber status)))
            (update-device-state trigger (and enabled (.isPlaying status)))
            (seesaw/config! status-label :foreground "black")
            (seesaw/value! status-label (build-status-label status))))))))

(defonce ^{:private true
           :doc "Responds to the arrival or departure of DJ Link
  devices by updating our user interface appropriately."}
  device-listener
  (reify org.deepsymmetry.beatlink.DeviceAnnouncementListener
    (deviceFound [this announcement]
      (rebuild-all-device-status))
    (deviceLost [this announcement]
      (rebuild-all-device-status))))

(defn- create-trigger-window
  "Create a new Beat Link Trigger window."
  []
  (let [root (seesaw/frame :title "Beat Link Trigger" :on-close :dispose
                           :menubar (seesaw/menubar :items [(seesaw/menu :text "Window" :items [new-trigger-action])]))
        panel (mig/mig-panel
               :id :panel
               :items [["Watch:" "alignx trailing"]
                       [(seesaw/combobox :id :players :model (get-player-choices))]

                       [(seesaw/label :id :status :text "Checking...")  "gap unrelated, span, wrap"]

                       ["MIDI Output:" "alignx trailing"]
                       [(seesaw/combobox :id :outputs :model (get-midi-outputs))]

                       ["Message:" "gap unrelated"]
                       [(seesaw/combobox :id :message :model ["Note" "CC"])]

                       [(seesaw/spinner :id :note :model (seesaw/spinner-model 127 :from 1 :to 127))]

                       ["Channel:" "gap unrelated"]
                       [(seesaw/spinner :id :channel :model (seesaw/spinner-model 1 :from 1 :to 16))]

                       ["Enabled:" "gap unrelated"]
                       [(seesaw/checkbox :id :enabled) "wrap"]])]
    (seesaw/config! root :content panel)
    (seesaw/pack! root)
    (seesaw/listen (seesaw/select root [:#players])
                   :item-state-changed (fn [e]  ; Update player status when selection changes
                                         (show-device-status root)))
    (seesaw/listen root :window-closed (fn [e]  ; Clean up when we are closed
                                         (when (empty? (swap! open-triggers dissoc root))
                                           (System/exit 0))))  ; The last window was closed.
    (show-device-status root)
    (swap! open-triggers assoc root false)
    (seesaw/show! root)))

(defn- searching-frame
  "Create and show a frame that explains we are looking for devices."
  []
  (let [result (seesaw/frame :title "Watching Network" :on-close :dispose
                             :content (mig/mig-panel
                                       :items [["BeatLinkTrigger is looking for DJ Link devices..." "wrap"]
                                               [(seesaw/progress-bar :indeterminate? true) "span, grow"]]))]
    (seesaw/pack! result)
    (.setLocationRelativeTo result nil)
    (seesaw/show! result)))

(defn- install-mac-about-handler
  "If we are running on a Mac, load the namespace that only works
  there (and is only needed there) to install our About handler."
  []
  (when (on-mac?)
    (require '[beat-link-trigger.mac-about])
    ((resolve 'beat-link-trigger.mac-about/install-handler))))

(defn start
  "Make sure we can start the Virtual CDJ, then present a user
  interface. Called when jar startup has detected a recent-enough Java
  version to succcessfully load this namespace."
  [& args]
  (seesaw/native!)  ; Adopt as native a look-and-feel as possible
  (install-mac-about-handler)
  (let [searching (searching-frame)]
    (loop []
      (when (not (try (VirtualCdj/start)  ; Make sure we can see some DJ Link devices and start the VirtualCdj
                      (catch Exception e
                        (seesaw/hide! searching)
                        (seesaw/alert (str "<html>Unable to create Virtual CDJ<br><br>" e)
                :title "DJ Link Connection Failed" :type :error))))
        (seesaw/hide! searching)
        (let [options (to-array ["Try Again" "Quit" "Continue Offline"])
              choice (javax.swing.JOptionPane/showOptionDialog
                      nil "No DJ Link devices were seen on any network. Search again?"
                      "No DJ Link Devices Found"
                      javax.swing.JOptionPane/YES_NO_OPTION javax.swing.JOptionPane/ERROR_MESSAGE nil
                      options (aget options 0))]
          (case choice
            0 (do (seesaw/show! searching) (recur))  ; Try Again
            2 (do (seesaw/dispose! searching) (DeviceFinder/stop))  ; Continue Offline
            (System/exit 1))))))  ; Quit, or just closed the window, which means the same

  ;; Request notifications when MIDI devices appear or vanish
  (when (CoreMidiDeviceProvider/isLibraryLoaded)
    (CoreMidiDeviceProvider/addNotificationListener
     (reify uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification
       (midiSystemUpdated [this]
         (midi-environment-changed)))))

  ;; Open the first trigger window
  (create-trigger-window)

  (DeviceFinder/addDeviceAnnouncementListener device-listener)  ; Be able to react to players coming and going
  (rebuild-all-device-status)  ; In case any came or went while we were setting up the listener
  (when (VirtualCdj/isActive) (VirtualCdj/addUpdateListener status-listener))) ; React to changes in player state
