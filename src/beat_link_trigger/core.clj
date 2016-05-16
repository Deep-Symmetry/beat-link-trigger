(ns beat-link-trigger.core
  "Send MIDI or OSC events when a CDJ starts playing."
  (:require [overtone.midi :as midi]
            [seesaw.cells]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig])
  (:import [javax.sound.midi Sequencer Synthesizer]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDeviceProvider CoreMidiDestination CoreMidiSource]
           [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Beat CdjStatus MixerStatus]))


(defn usable-midi-device?
  "Returns true if a MIDI device should be visible. Filters out non-CoreMidi4J devices when that library
  is active."
  [device]
  (or (not (CoreMidiDeviceProvider/isLibraryLoaded))
      (let [raw-device (:device device)]
        (or (instance? Sequencer raw-device) (instance? Synthesizer raw-device)
            (instance? CoreMidiDestination raw-device) (instance? CoreMidiSource raw-device)))))

(defn get-midi-outputs
  "Returns all available MIDI output devices"
  []
  (filter usable-midi-device? (midi/midi-sinks)))

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

(defn get-player-numbers
  "Returns a sorted list of the player numbers of all devices which
  are reporting CDJ status information on the network at the moment."
  []
  (sort (map #(str "Player " (.getDeviceNumber %))
             (filter #(instance? CdjStatus %)
                     (map #(VirtualCdj/getLatestStatusFor %) (DeviceFinder/currentDevices))))))

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

(defn- create-trigger-window
  "Create a new Beat Link Trigger window."
  []
  (let [root (seesaw/frame :title "Beat Link Trigger" :on-close :dispose
                           :menubar (seesaw/menubar :items [(seesaw/menu :text "Window" :items [new-trigger-action])]))
        panel (mig/mig-panel
               :id :panel
               :items [["Watch:" "alignx trailing"]
                       [(seesaw/combobox :model (get-player-numbers) :id :players) "wrap"]

                       ["MIDI Output:" "alignx trailing"]
                       [(seesaw/combobox :model (map :name (get-midi-outputs)) :id :outputs)]

                       ["Message:" "gap unrelated, alignx trailing"]
                       [(seesaw/combobox :model ["Note" "CC"] :id :message)]

                       [(seesaw/spinner :model (seesaw/spinner-model 127 :from 1 :to 127) :id :note) "wrap"]])]
    (seesaw/config! root :content panel)
    (seesaw/pack! root)
    (seesaw/listen root :window-closed (fn [e]  ; Clean up when we are closed
                                         (when (empty? (swap! open-triggers dissoc root))
                                           (System/exit 0))))  ; The last window was closed.
    (swap! open-triggers assoc root false)
    (seesaw/show! root)))

(defn- midi-environment-changed
  "Called when CoreMidi4J reports a change to the MIDI environment, so we can update the menu of
  available MIDI outputs."
  []
  (seesaw/invoke-later  ; Need to move to the AWT event thread, since we interact with GUI objects
   (let [new-outputs (map :name (get-midi-outputs))]
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
        selection (seesaw/selection output-menu)]
    (or (get @opened-outputs selection)
        (let [new-output (midi/midi-out selection)]
          (swap! opened-outputs assoc selection new-output)
          new-output))))

(defn- report-activation
  "Send a message indicating the player a trigger is watching has
  started playing."
  [trigger]
  (let [note (seesaw/value (seesaw/select trigger [:#note]))]
    (if (= "Note" (seesaw/value (seesaw/select trigger [:#message])))
      (midi/midi-note-on (get-chosen-output trigger) note 127)
      (midi/midi-control (get-chosen-output trigger) note 127))))

(defn- report-deactivation
  "Send a message indicating the player a trigger is watching has
  started playing."
  [trigger]
  (let [note (seesaw/value (seesaw/select trigger [:#note]))]
    (if (= "Note" (seesaw/value (seesaw/select trigger [:#message])))
      (midi/midi-note-off (get-chosen-output trigger) note)
      (midi/midi-control (get-chosen-output trigger) note 0))))

(defn- update-device-state
  "If the device state being watched by a trigger has changed, send an
  appropriate message and record the new state."
  [trigger new-state]
  (swap! open-triggers
         (fn [triggers]
           (when (not= new-state (get triggers trigger))
             (if new-state (report-activation trigger) (report-deactivation trigger)))
           (assoc triggers trigger new-state))))

(defn- rebuild-player-menu
  "Updates the combo box of available players to reflect the ones
  currently found on the network. If the previously selected player
  has disappeared, so we should deactivate it if it had been active."
  []
  (let [new-options (get-player-numbers)]
    (doseq [trigger (keys @open-triggers)]
      (let [player-menu (seesaw/select trigger [:#players])
            old-selection (seesaw/selection player-menu)]
        (seesaw/config! player-menu :model new-options)
        (if ((set new-options) old-selection)
          (seesaw/selection! player-menu old-selection)  ; Old selection still available, restore it
          (update-device-state trigger false)))))) ; Selected player disappeared, need to deactivate if it was active


(defonce ^{:private true
           :doc "Responds to the arrival or departure of DJ Link
  devices by updating our user interface appropriately."}
  device-listener
  (reify org.deepsymmetry.beatlink.DeviceAnnouncementListener
    (deviceFound [this announcement]
      (rebuild-player-menu))
    (deviceLost [this announcement]
      (rebuild-player-menu))))

(defonce ^{:private true
           :doc "Responds to player status updates and updates the
  state of any triggers watching them."}
  status-listener
  (reify org.deepsymmetry.beatlink.DeviceUpdateListener
    (received [this status]
      (doseq [trigger (keys @open-triggers)]
        (let [player-menu (seesaw/select trigger [:#players])
              selection (seesaw/selection player-menu)]
          (when (and (instance? CdjStatus status) (= selection (str "Player " (.getDeviceNumber status))))
            (update-device-state trigger (.isPlaying status))))))))

(defn- searching-frame
  "Create and show a frame that explains we are looking for devices."
  []
  (let [result (seesaw/frame :title "Watching Network" :on-close :dispose
                             :content (mig/mig-panel
                                       :items [["beat-link-trigger is looking for DJ Link devices..." "wrap"]
                                               [(seesaw/progress-bar :indeterminate? true) "span, grow"]]))]
    (seesaw/pack! result)
    (.setLocationRelativeTo result nil)
    (seesaw/show! result)))

(defn start
  "Make sure we can start the Virtual CDJ, then present a user
  interface. Called when jar startup has detected a recent-enough Java
  version to succcessfully load this namespace."
  [& args]
  (seesaw/native!)  ; Adopt as native a look-and-feel as possible
  (let [searching (searching-frame)]
    (while (not (VirtualCdj/start))  ; Make sure we can see some DJ Link devices and start the VirtualCdj
      (seesaw/hide! searching)
      (let [options (to-array ["Try Again" "Cancel"])
            choice (javax.swing.JOptionPane/showOptionDialog
                    nil "No DJ Link devices were seen on any network. Search again?"
                    "No DJ Link Devices Found"
                    javax.swing.JOptionPane/YES_NO_OPTION javax.swing.JOptionPane/ERROR_MESSAGE nil
                    options (aget options 0))]
        (when-not (zero? choice) (System/exit 1))
        (seesaw/show! searching)))
    (seesaw/dispose! searching))

  ;; Request notifications when MIDI devices appear or vanish
  (when (CoreMidiDeviceProvider/isLibraryLoaded)
    (CoreMidiDeviceProvider/addNotificationListener
     (reify uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification
       (midiSystemUpdated [this]
         (midi-environment-changed)))))

  ;; Open the first trigger window
  (create-trigger-window)

  (DeviceFinder/addDeviceAnnouncementListener device-listener)  ; Be able to react to players coming and going
  (rebuild-player-menu)  ; In case we missed an update between creating the UI and registering the listener
  (VirtualCdj/addUpdateListener status-listener))  ; Watch for and react to changes in player state
