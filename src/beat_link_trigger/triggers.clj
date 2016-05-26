(ns beat-link-trigger.triggers
  "Implements the list of triggers that send events when a CDJ starts
  playing."
  (:require [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.logs :as logs]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.prefs :as prefs]
            [overtone.midi :as midi]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.icon :as icon]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.sound.midi Sequencer Synthesizer]
           [java.awt RenderingHints]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDeviceProvider CoreMidiDestination CoreMidiSource]
           [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Beat CdjStatus MixerStatus Util]))

(defn- enabled?
  "Check whether a trigger is enabled."
  [trigger]
  (case (seesaw/value (seesaw/select trigger [:#enabled]))
    "Always" true
    "On-Air" (:on-air @(seesaw/user-data trigger))
    "Custom" (get-in @(seesaw/user-data trigger) [:expression-results :enabled])
    false))

(defn- run-custom-enabled
  "Invokes the custom enabled filter assigned to a trigger, if any,
  recording the result in the trigger user data."
  [status trigger]
  (when (= "Custom" (seesaw/value (seesaw/select trigger [:#enabled])))
    (swap! (seesaw/user-data trigger)
           (fn [data]
             (assoc-in data [:expression-results :enabled]
                    (when-let [custom-fn (get-in data [:expression-fns :enabled])]
                      (try
                        (custom-fn status)
                        (catch Exception e
                          (timbre/error e "Problem running Enabled expression,"
                                        (get-in data [:expressions :enabled]))))))))))

(defn- is-better-match?
  "Checks whether the current status packet represents a better
  matching device for a trigger to track than the one it is currently
  tracking. The best kind of match is both enabled and playing, second
  best is at least enabled, third best is playing. Ties are broken in
  favor of the player with the lowest number.

  In order to determine the enabled state, we need to run the custom
  enabled function if there is one, so that will be called for all
  incoming packets.

  We always match if we are the same device as the last match, even if
  it is a downgrade, to make sure we update the match score and
  relinquish control on the next packet from a better match."
  [status trigger]
  (run-custom-enabled status trigger)
  (let [this-device (.getDeviceNumber status)
        match-score (+ (if (enabled? trigger) 1024 0)
                       (if (.isPlaying status) 512 0)
                       (- this-device))
        [existing-score existing-device] (:last-match @(seesaw/user-data trigger))
        better (or (= existing-device this-device)
                   (when (some? existing-device) (nil? (DeviceFinder/getLatestAnnouncementFrom existing-device)))
                   (> match-score (or existing-score -256)))]
    (when better
      (swap! (seesaw/user-data trigger) assoc :last-match [match-score this-device]))))

(defn- matching-player-number?
  "Checks whether a CDJ status update matches a trigger, handling the
  special cases of the Master Player and Any Player. For Any Player we
  want to stay tracking the same Player most of the time, so we will
  keep track of the last one we matched, and change only if this is a
  better match."
  [status trigger player-selection]
  (let []
    (and (some? player-selection)
         (or (= (:number player-selection) (.getDeviceNumber status))
             (and (zero? (:number player-selection)) (.isTempoMaster status))
             (and (neg? (:number player-selection)) (is-better-match? status trigger))))))

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
  "Returns a sorted list of the player watching choices, including
  options to watch Any Player and the Master Player."
  []
  (for [i (range -1 5)]
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
  (try
    (when-let [output (get-chosen-output trigger)]
      (let [note (seesaw/value (seesaw/select trigger [:#note]))
            channel (dec (seesaw/value (seesaw/select trigger [:#channel])))
            message (seesaw/value (seesaw/select trigger [:#message]))]
        (timbre/info "Reporting activation:" message note "on channel" (inc channel))
        (if (= "Note" message)
          (midi/midi-note-on output note 127 channel)
          (midi/midi-control output note 127 channel))))
    (catch Exception e
        (timbre/error e "Problem reporting player activation."))))

(defn- report-deactivation
  "Send a message indicating the player a trigger is watching has
  started playing, as long as the chosen output exists."
  [trigger]
  (try
    (when-let [output (get-chosen-output trigger)]
      (let [note (seesaw/value (seesaw/select trigger [:#note]))
            channel (dec (seesaw/value (seesaw/select trigger [:#channel])))
            message (seesaw/value (seesaw/select trigger [:#message]))]
        (timbre/info "Reporting deactivation:" message note "on channel" (inc channel))
        (if (= "Note" message)
          (midi/midi-note-off output note channel)
          (midi/midi-control output note 0 channel)))
      )
    (catch Exception e
        (timbre/error e "Problem reporting player deactivation."))))

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
    (str (.getDeviceNumber status) (if (.isPlaying status) " Playing" " Stopped")
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
  (try
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
              (do (seesaw/config! status-label :foreground "cyan")
                  (seesaw/value! status-label (build-status-label status)))
              (do (seesaw/config! status-label :foreground "red")
                  (seesaw/value! status-label (cond (some? status) "Non-Player status received."
                                                    (not (VirtualCdj/isActive)) "Offline."
                                                    :else "No status received."))))))))
    (catch Exception e
      (timbre/error e "Problem showing Trigger Player status."))))

(defn- show-midi-status
  "Set the visibility of the Enabled checkbox and the text and color
  of its label based on whether the currently-selected MIDI output can
  be found."
  [trigger]
  (try
    (let [enabled-label (seesaw/select trigger [:#enabled-label])
          enabled (seesaw/select trigger [:#enabled])
          state (seesaw/select trigger [:#state])]
      (if-let [output (get-chosen-output trigger)]
        (do (seesaw/config! enabled-label :foreground "white")
            (seesaw/value! enabled-label "Enabled:")
            (seesaw/config! enabled :visible? true)
            (seesaw/config! state :visible? true))
        (do (seesaw/config! enabled-label :foreground "red")
            (seesaw/value! enabled-label "Not found.")
            (seesaw/config! enabled :visible? false)
            (seesaw/config! state :visible? false))))
    (catch Exception e
      (timbre/error e "Problem showing Trigger MIDI status."))))

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
                (doseq [editor (vals (:expression-editors @(seesaw/user-data trigger)))]
                  (editors/retitle editor)))
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
    (.setColor g java.awt.Color/gray)
    (.drawString g text (.. c (getInsets) left)
                 (+ (.. g (getFontMetrics) (getMaxAscent)) (.. c (getInsets) top)))))

(defn paint-state
  "Draws a representation of the state of the trigger, including both
  whether it is enabled and whether it has tripped (or would have, if
  it were not disabled)."
  [trigger c g]
  (let [w (double (seesaw/width c))
        h (double (seesaw/height c))
        outline (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        enabled? (enabled? trigger)
        state @(seesaw/user-data trigger)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (if (:tripped state)
      (do  ; Draw the inner filled circle showing the trigger is tripped
        (.setPaint g java.awt.Color/green)
        (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))
      (when (:playing state)  ; Draw the inner gray circle showing it would trip if it were not disabled
        (.setPaint g java.awt.Color/lightGray)
        (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0)))))

    ;; Draw the outer circle that reflects the enabled state
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if enabled? java.awt.Color/green java.awt.Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(defn- show-popup-from-button
  "Displays the popup menu when the gear button is clicked as an
  ordinary mouse event."
  [target popup event]
  (.show popup target (.x (.getPoint event)) (.y (.getPoint event))))

(defn- create-trigger-row
  "Create a row for watching a player in the trigger window. If `m` is
  supplied, it is a map containing values to recreate the row from a
  saved version."
  ([]
   (create-trigger-row nil))
  ([m]
   (let [outputs (get-midi-outputs)
         gear (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-icon.png"))
         panel (mig/mig-panel
                :id :panel
                :items [[(seesaw/label :id :index :text "1.") "align right"]
                        [(seesaw/text :id :comment :paint (partial paint-placeholder "Comment")) "span, grow, wrap"]

                        [gear]
                        ["Watch:" "alignx trailing"]
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
                        [(seesaw/canvas :id :state :size [18 :by 18] :opaque? false
                                        :tip "Trigger state: Outer ring shows enabled, inner light when tripped.")
                         "wrap, hidemode 1"]]

                :user-data (atom {:playing false :tripped false}))
         delete-action (seesaw/action :handler (fn [e]
                                                 (try
                                                   (doseq [editor (vals (:expression-editors
                                                                         @(seesaw/user-data panel)))]
                                                     (editors/dispose editor))
                                                   (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                                   :items (remove #(= % panel) (get-triggers)))
                                                   (adjust-to-new-trigger)
                                                   (.pack @trigger-frame)
                                                   (catch Exception e
                                                     (timbre/error e "Problem deleting Trigger."))))
                                      :name "Delete Trigger")
         ;; TODO: Build this up from map of available editor types
         edit-enabled-action (seesaw/action :handler (fn [e] (editors/show-trigger-editor :enabled panel))
                                            :name "Edit Enabled Expression")
         popup-fn (fn [e] (concat [edit-enabled-action] (when (> (count (get-triggers)) 1) [delete-action])))]

     ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
     ;; or right click on the gear button.
     (seesaw/config! [panel gear] :popup popup-fn)
     (seesaw/listen gear
                    :mouse-pressed (fn [e]
                                     (let [popup (seesaw/popup :items (popup-fn e))]
                                       (show-popup-from-button gear popup e))))

     ;; Attach the custom paint function to render the graphical trigger state
     (seesaw/config! (seesaw/select panel [:#state]) :paint (partial paint-state panel))

     ;; Update the trigger state when the enabled state changes, and open an editor window if Custom is
     ;; chosen and the custom expression is empty
     (let [enabled-menu (seesaw/select panel [:#enabled])]
       (seesaw/listen enabled-menu
        :action-performed (fn [e]
                            (seesaw/repaint! (seesaw/select panel [:#state]))
                            (when (and (= "Custom" (seesaw/selection enabled-menu))
                                       (empty? (get-in @(seesaw/user-data panel) [:expressions :enabled])))
                              (editors/show-trigger-editor :enabled panel)))))

     (seesaw/listen (seesaw/select panel [:#players])
                    :item-state-changed (fn [e]  ; Update player status when selection changes
                                          (show-device-status panel)))
     (seesaw/listen (seesaw/select panel [:#outputs])
                    :item-state-changed (fn [e]  ; Update output status when selection changes
                                          (show-midi-status panel)))
     (when (some? m)
       (when-let [exprs (:expressions m)]
         (swap! (seesaw/user-data panel) assoc :expressions exprs)
         (doseq [[kind expr] exprs]
           (try
             (swap! (seesaw/user-data panel) assoc-in [:expression-fns kind]
                    (expressions/build-user-expression expr (get-in editors/trigger-editors [kind :bindings])))
             (catch Exception e
               (swap! (seesaw/user-data panel) assoc :expression-load-error true)
               (timbre/error e (str "Problem parsing " (get-in editors/trigger-editors [kind :title])
                                    " when loading Triggers. Expression:\n" expr "\n"))))))
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
                 :key "menu T"))

(defn- close-all-editors
  "Close any custom expression editors windows that are open, in
  preparation for deleting all triggers."
  []
  (doseq [trigger (get-triggers)]
    (doseq [editor (vals (:expression-editors @(seesaw/user-data trigger)))]
      (editors/dispose editor))))

(def ^:private clear-triggers-action
  "The menu action which empties the Trigger list."
  (seesaw/action :handler (fn [e]
                            (try
                              (let [confirm (seesaw/dialog
                                             :content "Clear Triggers?\nYou will be left with one default Trigger."
                                             :type :warning :option-type :yes-no)]
                                (.pack confirm)
                                (.setLocationRelativeTo confirm @trigger-frame)
                                (when (= :success (seesaw/show! confirm))
                                  (close-all-editors)
                                  (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                  :items [(create-trigger-row)])
                                  (adjust-to-new-trigger))
                                (seesaw/dispose! confirm))
                              (catch Exception e
                                (timbre/error e "Problem clearing Trigger list."))))
                 :name "Clear Triggers"))

(defn- trigger-configuration
  "Returns the current Trigger window configuration, so it can be
  saved and recreated."
  []
  (vec (for [trigger (get-triggers)]
         (-> (seesaw/value trigger)
             (dissoc :status :enabled-label :index)
             (merge (when-let [exprs (:expressions @(seesaw/user-data trigger))]
                      {:expressions exprs}))))))

(defn- save-triggers-to-preferences
  "Saves the current Trigger window configuration to the application
  preferences."
  []
  (prefs/put-preferences (assoc (prefs/get-preferences) :triggers (trigger-configuration))))

;; Register the custom readers needed to read back in the defrecords that we use,
;; including under the old package name before they were moved to the triggers namespace.
(prefs/add-reader 'beat_link_trigger.triggers.PlayerChoice map->PlayerChoice)
(prefs/add-reader 'beat_link_trigger.triggers.MidiChoice map->MidiChoice)
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
                 :key "menu S"))

(declare recreate-trigger-rows)

(defn- check-for-parse-error
  "Called after loading the triggers from a file or the preferences to
  see if there were problems parsing any of the custom expressions. If
  so, reports that to the user and clears the warning flags."
  []
  (let [failed (filter identity (for [trigger (get-triggers)]
                                  (when (:expression-load-error @(seesaw/user-data trigger))
                                    (swap! (seesaw/user-data trigger) dissoc :expression-load-error)
                                    (editors/trigger-index trigger))))]
    (when (seq failed)
      (seesaw/alert (str "<html>Unable to use an expression for Trigger "
                         (clojure.string/join ", " failed) ".<br><br>"
                         "Check the log file for details.")
                    :title "Exception during Clojure evaluation" :type :error))))

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
                                  (timbre/error e "Problem loading" file)
                                  (seesaw/alert (str "<html>Unable to Load.<br><br>" e)
                                                :title "Problem Reading File" :type :error)))
                              (check-for-parse-error)))
                 :name "Load"
                 :key "menu L"))

(defn- midi-environment-changed
  "Called when CoreMidi4J reports a change to the MIDI environment, so we can update the menu of
  available MIDI outputs."
  []
  (seesaw/invoke-later  ; Need to move to the AWT event thread, since we interact with GUI objects
   (try
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
         (show-midi-status trigger)))
     (catch Exception e
       (timbre/error e "Problem responding to change in MIDI environment.")))))

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
      (try
        (doseq [trigger (get-triggers)]
          (let [status-label (seesaw/select trigger [:#status])
                player-menu (seesaw/select trigger [:#players])
                selection (seesaw/selection player-menu)]
            (when (and (instance? CdjStatus status) (matching-player-number? status trigger selection))
              (when-not (neg? (:number selection))
                (run-custom-enabled status trigger))  ; This was already done if Any Player is the selection
              (update-player-state trigger (.isPlaying status) (.isOnAir status))
              (seesaw/invoke-later
               (seesaw/config! status-label :foreground "cyan")
               (seesaw/value! status-label (build-status-label status))))))
        (catch Exception e
          (timbre/error e "Problem responding to Player status packet."))))))

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

(defn- translate-custom-enabled
  "Convert from the old format for storing a single custom expression
  into the new extensible approach, so we can read old preference file
  versions."
  [trigger]
  (merge (translate-enabled-values trigger)
         (when-let [expr (:custom-enabled trigger)]
           {:expressions {:enabled expr}})))

(defn- recreate-trigger-rows
  "Reads the preferences and recreates any trigger rows that were
  specified in them. If none were found, returns a single, default
  trigger."
  []
  (close-all-editors)
  (let [triggers (:triggers (prefs/get-preferences))]
    (if (seq triggers)
      (vec (for [trigger triggers]
             (create-trigger-row (translate-custom-enabled trigger))))
      [(create-trigger-row)])))

(defn- create-trigger-window
  "Create and show the trigger window."
  []
  (try
    (let [root (seesaw/frame :title "Beat Link Triggers" :on-close :exit
                             :menubar (seesaw/menubar
                                       :items [(seesaw/menu :text "File"
                                                            :items (concat [load-action save-action
                                                                            (seesaw/separator) logs/logs-action]
                                                                           menus/non-mac-actions))
                                               (seesaw/menu :text "Triggers"
                                                            :items [new-trigger-action clear-triggers-action])]))
          panel (seesaw/scrollable (seesaw/vertical-panel
                                    :id :triggers
                                    :items (recreate-trigger-rows)))]
      (seesaw/config! root :content panel)
      (reset! trigger-frame root)
      (adjust-to-new-trigger)
      (seesaw/show! root)
      (check-for-parse-error))
    (catch Exception e
      (timbre/error e "Problem creating Trigger window."))))

(defn start
  "Create the Triggers window, and register all the notification
  handlers it needs in order to stay up to date with events on the
  MIDI and DJ Link networks."
  []
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
  (when (VirtualCdj/isActive) (VirtualCdj/addUpdateListener status-listener)))  ; React to changes in player state
