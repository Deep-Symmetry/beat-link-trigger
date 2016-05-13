(ns beat-link-trigger.core
  "Send MIDI or OSC events when a CDJ starts playing."
  (:require [overtone.midi :as midi]
            [seesaw.cells]
            [seesaw.core :as seesaw])
  (:import [javax.sound.midi Sequencer Synthesizer]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDeviceProvider CoreMidiDestination CoreMidiSource]))


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

(defn string-renderer
  "Provide a way to render a string value for a more complex object within a combobox model.
  Takes a function `f` which is called with each element of the model, and returns the string
  to be rendered for it."
  [f]
  (seesaw.cells/default-list-cell-renderer (fn [this {:keys [value]}] (.setText this (str (f value))))))

(def root
  "The main frame of the user interface"
  (seesaw/frame :title "Beat Link Trigger" #_:on-close #_:exit))

(defn- midi-environment-changed
  "Called when CoreMidi4J reports a change to the MIDI environment, so we can update the menu of
  available MIDI outputs."
  []
  (let [output-menu (seesaw/select root [:#outputs])
        old-selection (seesaw/selection output-menu)
        new-outputs (get-midi-outputs)]
    (seesaw/config! output-menu :model new-outputs)  ; Update the content of the output menu
    (when ((set new-outputs) old-selection)  ; Our old selection is still available, so restore it
      (seesaw/selection! output-menu old-selection)
      (.repaint root))))  ; Work around a drawing but that was not rendering the name properly

(defn -main
  "Present a user interface when invoked as an executable jar."
  [& args]
  (seesaw/native!)  ; Adopt as native a look-and-feel as possible
  (when (CoreMidiDeviceProvider/isLibraryLoaded)  ; Request notifications when MIDI devices appear or vanish
    (CoreMidiDeviceProvider/addNotificationListener
     (reify uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification
       (midiSystemUpdated [this]
         (midi-environment-changed)))))
  ;; Build the UI
  (let [outputs (seesaw/combobox :model (get-midi-outputs) :renderer (string-renderer :name) :id :outputs)]
    (seesaw/config! root :content outputs)
    (seesaw/pack! root)
    (seesaw/show! root)))
