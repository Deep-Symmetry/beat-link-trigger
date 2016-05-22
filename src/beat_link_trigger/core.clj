(ns beat-link-trigger.core
  "Send MIDI or OSC events when a CDJ starts playing."
  (:require [beat-link-trigger.about :as about]
            [beat-link-trigger.prefs :as prefs]
            [me.raynes.fs :as fs]
            [overtone.midi :as midi]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [seesaw.util]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [taoensso.timbre :as timbre])
  (:import [javax.sound.midi Sequencer Synthesizer]
           [java.awt RenderingHints]
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

(defn- matching-player-number?
  "Checks whether a CDJ status update matches a trigger, handling
  the special case of the Master Player."
  [status trigger]
  (let [player-menu (seesaw/select trigger [:#players])
        selection (seesaw/selection player-menu)]
    (and (some? selection)
         (or (= (:number selection) (.getDeviceNumber status))
             (and (zero? (:number selection)) (.isTempoMaster status))))))

;; Used to represent the available players in the Watch menu. The `toString` method tells
;; Swing how to display it, and the number is what we need for comparisons.
(defrecord PlayerChoice [number]
  Object
  (toString [_] (cond
                  (neg? number) "Any Player"  ; Requires more thought about how to implement
                  (zero? number) "Master Player"
                  :else (str "Player " number))))

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

(defn get-player-choices
  "Returns a sorted list of the player choices. This used to only
  return currently-visible players, but we now return all valid
  choices because you might be loading a configuration you want to
  edit in an offline setting."
  []
  (for [i (range 5)]
    (PlayerChoice. i)))

(defonce ^{:private true
           :doc "Holds a map of all the MIDI output devices we have
  opened, keyed by their names, so we can reuse them."}
  opened-outputs (atom {}))

(defn- get-chosen-output
  "Return the MIDI output to which messages should be sent for a given
  trigger, opening it if this is the first time we are using it, or
  reusing it if we already opened it. Returns `nil` if the output can
  not currently be found (it was disconnected, or present in a loaded
  file but not on this system)."
  [trigger]
  (let [output-menu (seesaw/select trigger [:#outputs])
        selection (seesaw/selection output-menu)
        device-name (.full_name selection)]
    (or (get @opened-outputs device-name)
        (try
          (let [new-output (midi/midi-out device-name)]
            (swap! opened-outputs assoc device-name new-output)
            new-output)
          (catch IllegalArgumentException e  ; The chosen output is not currently available
            (timbre/debug e "Trigger using nonexisting MIDI output" device-name))))))

(defn- report-activation
  "Send a message indicating the player a trigger is watching has
  started playing, as long as the chosen output exists."
  [trigger]
  (when-let [output (get-chosen-output trigger)]
    (let [note (seesaw/value (seesaw/select trigger [:#note]))
          channel (dec (seesaw/value (seesaw/select trigger [:#channel])))
          message (seesaw/value (seesaw/select trigger [:#message]))]
      (timbre/info "Reporting activation:" message note "on channel" (inc channel))
      (if (= "Note" message)
        (midi/midi-note-on output note 127 channel)
        (midi/midi-control output note 127 channel)))))

(defn- report-deactivation
  "Send a message indicating the player a trigger is watching has
  started playing, as long as the chosen output exists."
  [trigger]
  (when-let [output (get-chosen-output trigger)]
    (let [note (seesaw/value (seesaw/select trigger [:#note]))
          channel (dec (seesaw/value (seesaw/select trigger [:#channel])))
          message (seesaw/value (seesaw/select trigger [:#message]))]
      (timbre/info "Reporting deactivation:" message note "on channel" (inc channel))
      (if (= "Note" message)
        (midi/midi-note-off output note channel)
        (midi/midi-control output note 0 channel)))))

(defn- enabled?
  "Check whether a trigger is enabled."
  [trigger]
  ;; For now, we just look at the checkbox, but soon we will potentially be using custom logic
  (case (seesaw/value (seesaw/select trigger [:#enabled]))
    "Always" true
    "On-Air" (:on-air @(seesaw/user-data trigger))
    "Custom" (:custom-enabled-result @(seesaw/user-data trigger))
    false))

(defn- update-player-state
  "If the Playing state of a device being watched by a trigger has
  changed, send an appropriate message and record the new state."
  [trigger playing on-air]
  (swap! (seesaw/user-data trigger)
         (fn [data]
           (let [tripped (and playing (enabled? trigger))]
             (when (not= tripped (:tripped data))
               (if tripped (report-activation trigger) (report-deactivation trigger)))
             (assoc data :playing playing :on-air on-air :tripped tripped))))
  (seesaw/repaint! (seesaw/select trigger [:#state])))

(defn build-status-label
  "Create a brief textual summary of a player state given a status
  update object from beat-link."
  [status]
  (let [beat (.getBeatNumber status)]
    (str (if (.isPlaying status) "Playing" "Stopped")
         (when (.isTempoMaster status) ", Master")
         (when (.isOnAir status) ", On-Air")
         ", Track #" (.getTrackNumber status)
         ", " (if (= 65535 (.getBpm status)) "--.-" (format "%.1f" (.getEffectiveTempo status))) " BPM ("
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
          (update-player-state trigger false false))
      (let [found (when (DeviceFinder/isActive) (DeviceFinder/getLatestAnnouncementFrom (int (.number selection))))
            status (when (VirtualCdj/isActive) (VirtualCdj/getLatestStatusFor (int (.number selection))))]
        (if (nil? found)
          (do (seesaw/config! status-label :foreground "red")
              (seesaw/value! status-label (if (DeviceFinder/isActive) "Player not found." "Offline."))
              (update-player-state trigger false false))
          (if (instance? CdjStatus status)
            (do (seesaw/config! status-label :foreground "black")
                (seesaw/value! status-label (build-status-label status)))
            (do (seesaw/config! status-label :foreground "red")
                (seesaw/value! status-label (cond (some? status) "Non-Player status received."
                                                  (not (VirtualCdj/isActive)) "Offline."
                                                  :else "No status received.")))))))))

(defn- show-midi-status
  "Set the visibility of the Enabled checkbox and the text and color
  of its label based on whether the currently-selected MIDI output can
  be found."
  [trigger]
  (let [enabled-label (seesaw/select trigger [:#enabled-label])
        enabled (seesaw/select trigger [:#enabled])
        state (seesaw/select trigger [:#state])]
    (if-let [output (get-chosen-output trigger)]
      (do (seesaw/config! enabled-label :foreground "black")
          (seesaw/value! enabled-label "Enabled:")
          (seesaw/config! enabled :visible? true)
          (seesaw/config! state :visible? true))
      (do (seesaw/config! enabled-label :foreground "red")
          (seesaw/value! enabled-label "Not found.")
          (seesaw/config! enabled :visible? false)
          (seesaw/config! state :visible? false)))))

(defonce ^{:private true
           :doc "Holds the trigger window, through which we can access and
  manipulate the triggers themselves."}
  trigger-frame 
  (atom nil))

(defn- get-triggers
  "Returns the list of triggers that currently exist."
  []
  (when-let [frame @trigger-frame]
    (seesaw/config (seesaw/select frame [:#triggers]) :items)))

(defn- enabled-editor-title
  "Return the text to use as the title bar in a window editing the
  custom enabled expression for a trigger."
  [trigger]
  (let [index (:index (seesaw/value trigger))]
    (str "Trigger " (subs index 0 (dec (count index))) " Custom Enabled Expression")))

(defn- adjust-to-new-trigger
  "Called when a trigger is added or removed to restore the proper
  alternation of background colors, expand the window if it still fits
  the screen, and update any other user interface elements that might
  be affected."
  []
  (doall (map (fn [trigger color index]
                (seesaw/config! trigger :background color)
                (seesaw/config! (seesaw/select trigger [:#index])
                                :text (str (inc index) "."))
                (when-let [editor (:custom-enabled-editor @(seesaw/user-data trigger))]
                  (seesaw/config! editor :title (enabled-editor-title trigger))))
              (get-triggers) (cycle ["#eee" "#ddd"]) (range)))
  (when (< 100 (- (.height (.getBounds (.getGraphicsConfiguration @trigger-frame)))
                                            (.height (.getBounds @trigger-frame))))
                              (.pack @trigger-frame)))

(defn paint-placeholder
  "A function which will paint placeholder text in a text field if the
  user has not added any text of their own, since Swing does not have
  this ability built in. Takes the text of the placeholder, the
  component into which it should be painted, and the graphics content
  in which painting is taking place."
  [text c g]
  (when (zero? (.. c (getText) (length)))
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setColor g (.getDisabledTextColor c))
    (.drawString g text (.. c (getInsets) left)
                 (+ (.. g (getFontMetrics) (getMaxAscent)) (.. c (getInsets) top)))))

(defn paint-state
  "Draws a representation of the state of the trigger, including both
  whether it is enabled and whether it has tripped (or would have, if
  it were not disabled)."
  [trigger c g]
  (let [w (double (seesaw/width c))
        h (double (seesaw/height c))
        outline (java.awt.geom.Ellipse2D$Double. 0.0 0.0 (dec w) (dec h))
        enabled? (enabled? trigger)
        state @(seesaw/user-data trigger)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (if (:tripped state)
      (do  ; Draw the inner filled circle showing the trigger is tripped
        (.setPaint g java.awt.Color/green)
        (.fill g (java.awt.geom.Ellipse2D$Double. 3.0 3.0 (- w 6.5) (- h 6.5))))
      (when (:playing state)  ; Draw the inner gray circle showing it would trip if it were not disabled
        (.setPaint g java.awt.Color/gray)
        (.fill g (java.awt.geom.Ellipse2D$Double. 3.0 3.0 (- w 6.5) (- h 6.5)))))

    ;; Draw the outer circle that reflects the enabled state
    (.setPaint g (if enabled? java.awt.Color/green java.awt.Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 0.0 (dec h) (dec w) 0.0)))))

(defn- create-editor-window
  "Create and show a window for editing Clojure code."
  [title text save-fn]
  (let [root (seesaw/frame :title title :on-close :dispose
                           #_:menubar #_(seesaw/menubar
                                     :items [(seesaw/menu :text "File" :items (concat [load-action save-action]
                                                                                      non-mac-actions)
                                                          :mnemonic (seesaw.util/to-mnemonic-keycode \F))
                                             (seesaw/menu :text "Triggers"
                                                          :items [new-trigger-action clear-triggers-action]
                                                          :mnemonic (seesaw.util/to-mnemonic-keycode \T))]))
        editor (org.fife.ui.rsyntaxtextarea.RSyntaxTextArea. 16 80)
        scroll-pane (org.fife.ui.rtextarea.RTextScrollPane. editor)
        save-button (seesaw/button :text "Update" :listen [:action (fn [e] (save-fn (.getText editor)))])]
    (.setSyntaxEditingStyle editor org.fife.ui.rsyntaxtextarea.SyntaxConstants/SYNTAX_STYLE_CLOJURE)
    (seesaw/config! root :content (mig/mig-panel :items [[scroll-pane "grow 100 100, wrap"]
                                                         [save-button "push, align center"]]))
    (seesaw/config! editor :id :source)
    (seesaw/value! root {:source text})
    (seesaw/pack! root)))

(defn update-enabled-expression
  "Called when the editor for a custom enabled expression is ending
  and the user has asked to update the expression with new text."
  [trigger text]
  (swap! (seesaw/user-data trigger) dissoc :custom-enabled-fn)  ; In case the parse fails, leave nothing there.
  (try
    (swap! (seesaw/user-data trigger) assoc :custom-enabled-fn (eval (read-string (str "(fn [status] " text ")"))))
    (when-let [root (:custom-enabled-editor @(seesaw/user-data trigger))] (.dispose root)) ; Close the editor
    (swap! (seesaw/user-data trigger)
           (fn [data]
             (assoc (dissoc data :custom-enabled-editor)
                    :custom-enabled text)))
    (catch Exception e
      (timbre/error e "Problem parsing custom Enabled expression.")
      (seesaw/alert (str "<html>Unable to use custom Enabled expression.<br><br>" e
                         "<br><br>You may wish to check the log file for the detailed stack trace.")
                               :title "Exception during Clojure Evaluation" :type :error))))

(defn- show-enabled-editor
  "If there is currently no editor open for the custom enabled
  expression for a trigger, create it. If it exists, bring it to the
  front."
  [trigger]
  (let [editor (or (:custom-enabled-editor @(seesaw/user-data trigger))
                   (create-editor-window (enabled-editor-title trigger)
                                         (:custom-enabled @(seesaw/user-data trigger))
                                         (fn [text] (update-enabled-expression trigger text))))]
    (.setLocationRelativeTo editor trigger)
    (swap! (seesaw/user-data trigger) assoc :custom-enabled-editor editor)
    (seesaw/show! editor)
    (.toFront editor)))

(defn- create-trigger-row
  "Create a row for watching a player in the trigger window. If `m` is
  supplied, it is a map containing values to recreate the row from a
  saved version."
  ([]
   (create-trigger-row nil))
  ([m]
   (let [outputs (get-midi-outputs)
         panel (mig/mig-panel
                :id :panel
                :items [[(seesaw/label :id :index :text "1.") "align right"]
                        [(seesaw/text :id :comment :paint (partial paint-placeholder "Comment")) "span, grow, wrap"]

                        ["Watch:" "span 2, alignx trailing"]
                        [(seesaw/combobox :id :players :model (get-player-choices))]

                        [(seesaw/label :id :status :text "Checking...")  "gap unrelated, span, wrap"]

                        ["MIDI Output:" "span 2, alignx trailing"]
                        [(seesaw/combobox :id :outputs :model (concat outputs  ; Add selection even if not available
                                                                      (when (and (some? m)
                                                                                 (not ((set outputs) (:outputs m))))
                                                                        [(:outputs m)])))]

                        ["Message:" "gap unrelated"]
                        [(seesaw/combobox :id :message :model ["Note" "CC"])]

                        [(seesaw/spinner :id :note :model (seesaw/spinner-model 127 :from 1 :to 127))]

                        ["Channel:" "gap unrelated"]
                        [(seesaw/spinner :id :channel :model (seesaw/spinner-model 1 :from 1 :to 16))]

                        [(seesaw/label :id :enabled-label :text "Enabled:") "gap unrelated"]
                        [(seesaw/combobox :id :enabled :model ["Never" "On-Air" "Custom" "Always"]) "hidemode 1"]
                        [(seesaw/canvas :id :state :size [18 :by 18] :opaque? false)  "wrap, hidemode 1"]]

                :user-data (atom {:playing false :tripped false}))
         delete-action (seesaw/action :handler (fn [e]
                                                 (when-let [editor (:custom-enabled-editor @(seesaw/user-data panel))]
                                                   (seesaw/dispose! editor))
                                                 (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                                 :items (remove #(= % panel) (get-triggers)))
                                                 (adjust-to-new-trigger)
                                                 (.pack @trigger-frame))
                                      :name "Delete Trigger")
         edit-enabled-action (seesaw/action :handler (fn [e] (show-enabled-editor panel))
                                      :name "Edit Enabled Expression")]
     ;; Create our contextual menu
     (seesaw/config! panel :popup (fn [e] (when (> (count (get-triggers)) 1) [edit-enabled-action delete-action])))

     ;; Attach the custom paint function to render the graphical trigger state
     (seesaw/config! (seesaw/select panel [:#state]) :paint (partial paint-state panel))

     ;; Update the trigger state when the enabled state changes, and open an editor window if Custom is
     ;; chosen and the custom expression is empty
     (let [enabled-menu (seesaw/select panel [:#enabled])]
       (seesaw/listen enabled-menu
        :action-performed (fn [e]
                            (seesaw/repaint! (seesaw/select panel [:#state]))
                            (when (and (= "Custom" (seesaw/selection enabled-menu))
                                       (empty? (:custom-enabled @(seesaw/user-data panel))))
                              (show-enabled-editor panel)))))

     (seesaw/listen (seesaw/select panel [:#players])
                    :item-state-changed (fn [e]  ; Update player status when selection changes
                                          (show-device-status panel)))
     (seesaw/listen (seesaw/select panel [:#outputs])
                    :item-state-changed (fn [e]  ; Update output status when selection changes
                                          (show-midi-status panel)))
     (when (some? m)
       (when-let [custom-enabled (:custom-enabled m)]
         (swap! (seesaw/user-data panel) assoc :custom-enabled custom-enabled)
         (try
           (swap! (seesaw/user-data panel) assoc :custom-enabled-fn
                  (eval (read-string (str "(fn [status] " custom-enabled ")"))))
           (catch Exception e
             (swap! (seesaw/user-data panel) assoc :custom-enabled-load-error true)
             (timbre/error e (str "Problem parsing custom Enabled expression when loading Triggers. Expression:\n"
                                  custom-enabled "\n")))))
       (seesaw/value! panel m))
     (show-device-status panel)
     panel)))

(def ^:private new-trigger-action
  "The menu action which adds a new Trigger to the end of the list."
  (seesaw/action :handler (fn [e]
                            (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                            :items (concat (get-triggers) [(create-trigger-row)]))
                            (adjust-to-new-trigger))
                 :name "New Trigger"
                 :key "menu T"
                 :mnemonic (seesaw.util/to-mnemonic-keycode \T)))

(def ^:private clear-triggers-action
  "The menu action which empties the Trigger list."
  (seesaw/action :handler (fn [e]
                            (let [confirm (seesaw/dialog
                                           :content "Clear Triggers?\nYou will be left with one default Trigger."
                                           :type :warning :option-type :yes-no)]
                              (.pack confirm)
                              (.setLocationRelativeTo confirm @trigger-frame)
                              (when (= :success (seesaw/show! confirm))
                                (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                :items [(create-trigger-row)])
                                (adjust-to-new-trigger))
                              (seesaw/dispose! confirm)))
                 :name "Clear Triggers"))

(defn- trigger-configuration
  "Returns the current Trigger window configuration, so it can be
  saved and recreated."
  []
  (vec (for [trigger (get-triggers)]
         (-> (seesaw/value trigger)
             (dissoc :status :enabled-label :index)
             (merge (when-let [custom-enabled (:custom-enabled @(seesaw/user-data trigger))]
                      {:custom-enabled custom-enabled}))))))

(defn- save-triggers-to-preferences
  "Saves the current Trigger window configuration to the application
  preferences."
  []
  (prefs/put-preferences (assoc (prefs/get-preferences) :triggers (trigger-configuration))))

;; Register the custom readers needed to read back in the defrecords that we use.
(prefs/add-reader 'beat_link_trigger.core.PlayerChoice map->PlayerChoice)
(prefs/add-reader 'beat_link_trigger.core.MidiChoice map->MidiChoice)

(def ^:private save-action
  "The menu action which saves the configuration to a user-specified file."
  (seesaw/action :handler (fn [e]
                            (save-triggers-to-preferences)
                            (when-let [file (chooser/choose-file @trigger-frame :type :save)]
                              (try
                                (prefs/save-to-file file)
                                (catch Exception e
                                  (seesaw/alert (str "<html>Unable to Save.<br><br>" e)
                               :title "Problem Writing File" :type :error)))))
                 :name "Save"
                 :key "menu S"
                 :mnemonic (seesaw.util/to-mnemonic-keycode \S)))

(declare recreate-trigger-rows)

(defn- check-for-parse-error
  "Called after loading the triggers from a file or the preferences to
  see if there were problems parsing any of the custom Enable
  expressions. If so, reports that to the user and clears the warning
  flags."
  []
  (let [failed (filter identity (for [trigger (get-triggers)]
                                  (when (:custom-enabled-load-error @(seesaw/user-data trigger))
                                    (swap! (seesaw/user-data trigger) dissoc :custom-enabled-load-error)
                                    (let [label (seesaw/value (seesaw/select trigger [:#index]))]
                                      (subs label 0 (dec (count label)))))))]
    (when (seq failed)
      (seesaw/alert (str "<html>Unable to use custom Enabled expression for Trigger "
                         (clojure.string/join ", " failed) ".<br><br>"
                         "Check the log file for details.")
                    :title "Exception during Clojure Evaluation" :type :error))))

(def ^:private load-action
  "The menu action which loads the configuration from a user-specified file."
  (seesaw/action :handler (fn [e]
                            (when-let [file (chooser/choose-file
                                             @trigger-frame
                                             :filters [(chooser/file-filter "BeatLinkTrigger Files"
                                                                            prefs/valid-file?)])]
                              (try
                                (prefs/load-from-file file)
                                (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                :items (recreate-trigger-rows))
                                (adjust-to-new-trigger)
                                (catch Exception e
                                  (seesaw/alert (str "<html>Unable to Load.<br><br>" e)
                                                :title "Problem Reading File" :type :error)))
                              (check-for-parse-error)))
                 :name "Load"
                 :key "menu L"
                 :mnemonic (seesaw.util/to-mnemonic-keycode \L)))

(defonce ^{:private true
           :doc "The temporary directory into which we will log."}
  log-path (atom (fs/temp-dir "blt_logs")))

(def ^:private logs-action
  "The menu action which opens the logs folder."
  (seesaw/action :handler (fn [e]
                            (.open (java.awt.Desktop/getDesktop) @log-path))
                 :name "Open Logs Folder"))

(def ^:private non-mac-actions
  "The actions which are automatically available in the Application
  menu on the Mac, but must be added to the File menu on other
  platforms. This value will be empty when running on the Mac."
  (when-not (on-mac?)
    [(seesaw/separator)
     (seesaw/action :handler (fn [e] (about/show))
                    :name "About BeatLinkTrigger"
                    :mnemonic (seesaw.util/to-mnemonic-keycode \A))
     (seesaw/separator)
     (seesaw/action :handler (fn [e] (System/exit 0))
                    :name "Exit"
                    :mnemonic (seesaw.util/to-mnemonic-keycode \x))]))

(defn- midi-environment-changed
  "Called when CoreMidi4J reports a change to the MIDI environment, so we can update the menu of
  available MIDI outputs."
  []
  (seesaw/invoke-later  ; Need to move to the AWT event thread, since we interact with GUI objects
   (let [new-outputs (get-midi-outputs)]
     ;; Remove any opened outputs that are no longer available in the MIDI environment
     (swap! opened-outputs #(apply dissoc % (clojure.set/difference (set (keys %)) (set new-outputs))))

     (doseq [trigger (get-triggers)] ; Update the output menus in all trigger rows
       (let [output-menu (seesaw/select trigger [:#outputs])
             old-selection (seesaw/selection output-menu)]
         (seesaw/config! output-menu :model (concat new-outputs  ; Keep the old selection even if it disappeared
                                                    (when-not ((set new-outputs) old-selection) [old-selection])))

         ;; Keep our original selection chosen, even if it is now missing
         (seesaw/selection! output-menu old-selection))
       (show-midi-status trigger)))))

(defn- rebuild-all-device-status
  "Updates all player status descriptions to reflect the devices
  currently found on the network. Called when the set of available
  devices changes."
  []
  (doseq [trigger (get-triggers)]
    (show-device-status trigger)))

(defonce ^{:private true
           :doc "Responds to player status packets and updates the
  state of any triggers watching them."}
  status-listener
  (reify org.deepsymmetry.beatlink.DeviceUpdateListener
    (received [this status]
      (doseq [trigger (get-triggers)]
        (let [status-label (seesaw/select trigger [:#status])]
          (when (and (instance? CdjStatus status) (matching-player-number? status trigger))
            (when (= "Custom" (seesaw/value (seesaw/select trigger [:#enabled])))
              (swap! (seesaw/user-data trigger)
                       (fn [data]
                         (assoc data :custom-enabled-result
                                (when-let [custom-fn (:custom-enabled-fn data)]
                                  (try
                                    (custom-fn status)
                                    (catch Exception e
                                      (timbre/error e "Problem running custom Enabled expression,"
                                                    (:custom-enabled data)))))))))
            (update-player-state trigger (.isPlaying status) (.isOnAir status))
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

(defn- translate-enabled-values
  "Convert from the old true/false model of enabled stored in early
  preference file versions to the new choices so they load correctly."
  [trigger]
  (if-some [enabled (:enabled trigger)]
    (assoc trigger :enabled (case enabled
                              true "Always"
                              false "Never"
                              enabled))
    trigger))

(defn- recreate-trigger-rows
  "Reads the preferences and recreates any trigger rows that were
  specified in them. If none were found, returns a single, default
  trigger."
  []
  (doseq [trigger (get-triggers)]
    (when-let [editor (:custom-enabled-editor @(seesaw/user-data trigger))]
      (seesaw/dispose! editor)))  ; Close any custom enabled expression editors that were open
  (let [triggers (:triggers (prefs/get-preferences))]
    (if (seq triggers)
      (vec (for [trigger triggers]
             (create-trigger-row (translate-enabled-values trigger))))
      [(create-trigger-row)])))

(defn- create-trigger-window
  "Create and show the trigger window."
  []
  (let [root (seesaw/frame :title "Beat Link Triggers" :on-close :exit
                           :menubar (seesaw/menubar
                                     :items [(seesaw/menu :text "File"
                                                          :items (concat [load-action save-action
                                                                          (seesaw/separator) logs-action]
                                                                                      non-mac-actions)
                                                          :mnemonic (seesaw.util/to-mnemonic-keycode \F))
                                             (seesaw/menu :text "Triggers"
                                                          :items [new-trigger-action clear-triggers-action]
                                                          :mnemonic (seesaw.util/to-mnemonic-keycode \T))]))
        panel (seesaw/scrollable (seesaw/vertical-panel
                                  :id :triggers
                                  :items (recreate-trigger-rows)))]
    (seesaw/config! root :content panel)
    (reset! trigger-frame root)
    (adjust-to-new-trigger)
    (seesaw/show! root)
    (check-for-parse-error)))

(defn- install-mac-about-handler
  "If we are running on a Mac, load the namespace that only works
  there (and is only needed there) to install our About handler."
  []
  (when (on-mac?)
    (require '[beat-link-trigger.mac-about])
    ((resolve 'beat-link-trigger.mac-about/install-handler))))

(defn- create-appenders
  "Create a set of appenders which rotate the file at the specified path."
  []
  {:rotor (rotor/rotor-appender {:path (fs/file @log-path "blt.log")
                                 :max-size 100000
                                 :backlog 5})})

(defonce ^{:private true
           :doc "The default log appenders, which rotate between files
           in a logs subdirectory."}
  appenders (atom (create-appenders)))

(defn- init-logging-internal
  "Performs the actual initialization of the logging environment,
  protected by the delay below to insure it happens only once."
  []
  (timbre/set-config!
   {:level :info  ; #{:trace :debug :info :warn :error :fatal :report}
    :enabled? true

    ;; Control log filtering by namespaces/patterns. Useful for turning off
    ;; logging in noisy libraries, etc.:
    :ns-whitelist  [] #_["my-app.foo-ns"]
    :ns-blacklist  [] #_["taoensso.*"]

    :middleware [] ; (fns [data]) -> ?data, applied left->right

    :timestamp-opts {:pattern "yyyy-MMM-dd HH:mm:ss"
                     :locale :jvm-default
                     :timezone (java.util.TimeZone/getDefault)}

    :output-fn timbre/default-output-fn ; (fn [data]) -> string
    })

  ;; Install the desired log appenders
  (timbre/merge-config!
   {:appenders @appenders}))

(defonce ^{:private true
           :doc "Used to ensure log initialization takes place exactly once."}
  initialized (delay (init-logging-internal)))

(defn init-logging
  "Set up the logging environment."
  ([] ;; Resolve the delay, causing initialization to happen if it has not yet.
   @initialized)
  ([appenders-map] ;; Override the default appenders, then initialize as above.
   (reset! appenders appenders-map)
   (init-logging)))

(defn start
  "Set up logging, make sure we can start the Virtual CDJ, then
  present a user interface. Called when jar startup has detected a
  recent-enough Java version to succcessfully load this namespace."
  [& args]
  (seesaw/native!)  ; Adopt as native a look-and-feel as possible
  (init-logging)
  (timbre/info "Beat Link Trigger starting.")
  (install-mac-about-handler)
  (let [searching (about/create-searching-frame)]
    (loop []
      (if (try (VirtualCdj/start)  ; Make sure we can see some DJ Link devices and start the VirtualCdj
               (catch Exception e
                 (timbre/log e "Unable to create Virtual CDJ")
                 (seesaw/hide! searching)
                 (seesaw/alert (str "<html>Unable to create Virtual CDJ<br><br>" e)
                               :title "DJ Link Connection Failed" :type :error)))
        (seesaw/dispose! searching)  ; We succeeded in finding a DJ Link network
        (do
          (seesaw/hide! searching)  ; No luck so far, ask what to do
          (let [options (to-array ["Try Again" "Quit" "Continue Offline"])
                choice (javax.swing.JOptionPane/showOptionDialog
                        nil "No DJ Link devices were seen on any network. Search again?"
                        "No DJ Link Devices Found"
                        javax.swing.JOptionPane/YES_NO_OPTION javax.swing.JOptionPane/ERROR_MESSAGE nil
                        options (aget options 0))]
            (case choice
              0 (do (seesaw/show! searching) (recur))  ; Try Again
              2 (do (seesaw/dispose! searching) (DeviceFinder/stop))  ; Continue Offline
              (System/exit 1)))))))     ; Quit, or just closed the window, which means the same

  ;; Request notifications when MIDI devices appear or vanish
  (when (CoreMidiDeviceProvider/isLibraryLoaded)
    (CoreMidiDeviceProvider/addNotificationListener
     (reify uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification
       (midiSystemUpdated [this]
         (midi-environment-changed)))))

  ;; Open the trigger window
  (create-trigger-window)
  (.addShutdownHook (Runtime/getRuntime) (Thread. save-triggers-to-preferences))

  (DeviceFinder/addDeviceAnnouncementListener device-listener)  ; Be able to react to players coming and going
  (rebuild-all-device-status)  ; In case any came or went while we were setting up the listener
  (when (VirtualCdj/isActive) (VirtualCdj/addUpdateListener status-listener))) ; React to changes in player state
