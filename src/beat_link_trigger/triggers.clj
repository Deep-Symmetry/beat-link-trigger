(ns beat-link-trigger.triggers
  "Implements the list of triggers that send events when a CDJ starts
  playing."
  (:require [beat-link-trigger.about :as about]
            [beat-link-trigger.carabiner :as carabiner]
            [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.logs :as logs]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.players :as players]
            [beat-link-trigger.auto-cache :as auto]
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.util :as util]
            [fipp.edn :as fipp]
            [inspector-jay.core :as inspector]
            [overtone.midi :as midi]
            [seesaw.bind :as bind]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.icon :as icon]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import java.awt.RenderingHints
           [javax.sound.midi Sequencer Synthesizer]
           [org.deepsymmetry.beatlink Beat BeatFinder BeatListener CdjStatus CdjStatus$TrackSourceSlot
            DeviceAnnouncementListener DeviceFinder DeviceUpdateListener MixerStatus Util VirtualCdj]
           [org.deepsymmetry.beatlink.data ArtFinder BeatGridFinder MetadataFinder WaveformFinder SearchableItem]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDestination CoreMidiDeviceProvider CoreMidiSource]))

(defonce ^{:doc "Provides a space for trigger expressions to store
  values they want to share across triggers."}
  expression-globals (atom {}))

(defonce ^{:private true
           :doc "Holds the trigger window, through which we can access and
  manipulate the triggers themselves."}
  trigger-frame
  (atom nil))

(def metadata-finder
  "A convenient reference to the MetadataFinder singleton."
  (MetadataFinder/getInstance))

(defn- initial-global-user-data
  "Create the values to assign the user-data atom for the window
  as a whole"
  []
  (merge {:global true} (select-keys (prefs/get-preferences) [:request-metadata?])))

(defn- global-user-data
  "Locates the user data attached to the whole triggers frame, for
  working with global expressions."
  []
  (if (nil? @trigger-frame)
    (atom (initial-global-user-data)) ; Don't crash during initial window setup
    (seesaw/user-data (seesaw/config @trigger-frame :content))))

(defn request-metadata?
  "Checks whether the user wants us to actively request metadata."
  []
  (boolean (:request-metadata? @(global-user-data))))

(defn- enabled?
  "Check whether a trigger is enabled."
  ([trigger]
   (let [data @(seesaw/user-data trigger)]
     (enabled? trigger data)))
  ([trigger data]
   (case (get-in data [:value :enabled])
     "Always" true
     "On-Air" (:on-air data)
     "Custom" (get-in data [:expression-results :enabled])
     false)))

(defn- run-trigger-function
  "Checks whether the trigger has a custom function of the specified
  kind installed, and if so runs it with the supplied status argument
  and the trigger local and global atoms. Returns a tuple of the
  function return value and any thrown exception. If `alert?` is
  `true` the user will be alerted when there is a problem running the
  function."
  [trigger kind status alert?]
  (let [data @(seesaw/user-data trigger)]
    (when-let [custom-fn (get-in data [:expression-fns kind])]
      (try
        [(custom-fn status data expression-globals) nil]
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/editor-title kind trigger false) ":\n"
                               (get-in data [:expressions kind])))
          (when alert?
            (seesaw/alert (str "<html>Problem running trigger " (name kind) " expression.<br><br>" t)
                          :title "Exception in Custom Expression" :type :error))
          [nil t])))))

(defn- run-custom-enabled
  "Invokes the custom enabled filter assigned to a trigger, if any,
  recording the result in the trigger user data."
  [status trigger]
  (when (= "Custom" (get-in @(seesaw/user-data trigger) [:value :enabled]))
    (let [[enabled? _] (run-trigger-function trigger :enabled status false)]
      (swap! (seesaw/user-data trigger) assoc-in [:expression-results :enabled] enabled?))))

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
                   (when (some? existing-device) (nil? (.getLatestAnnouncementFrom (DeviceFinder/getInstance)
                                                                                   existing-device)))
                   (> match-score (or existing-score -256)))]
    (when better
      (swap! (seesaw/user-data trigger) assoc :last-match [match-score this-device]))))

(defn- matching-player-number?
  "Checks whether a CDJ status update matches a trigger, handling the
  special cases of the Master Player and Any Player. For Any Player we
  want to stay tracking the same Player most of the time, so we will
  keep track of the last one we matched, and change only if this is a
  better match. This should only be called with full-blown status
  updates, not beats."
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
                  (neg? number) "Any Player"
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

(defn get-chosen-output
  "Return the MIDI output to which messages should be sent for a given
  trigger, opening it if this is the first time we are using it, or
  reusing it if we already opened it. Returns `nil` if the output can
  not currently be found (it was disconnected, or present in a loaded
  file but not on this system). When available, `data` contains the
  map retrieved from the trigger `user-data` atom so it does not need
  to be reloaded."
  ([trigger]
   (get-chosen-output trigger @(seesaw/user-data trigger)))
  ([trigger data]
   (when-let [selection (get-in data [:value :outputs])]
     (let [device-name (.full_name selection)]
       (or (get @opened-outputs device-name)
           (try
             (let [new-output (midi/midi-out device-name)]
               (swap! opened-outputs assoc device-name new-output)
               new-output)
             (catch IllegalArgumentException e ; The chosen output is not currently available
               (timbre/debug e "Trigger using nonexisting MIDI output" device-name))))))))

(def ^:private clock-message
  "A MIDI timing clock message that can be sent by any trigger that is
  sending clock pulses."
  (javax.sound.midi.ShortMessage. javax.sound.midi.ShortMessage/TIMING_CLOCK))

(def ^:private start-message
  "A MIDI clock start message that can be sent by any trigger that is
  sending clock pulses."
  (javax.sound.midi.ShortMessage. javax.sound.midi.ShortMessage/START))

(def ^:private stop-message
  "A MIDI clock stop message that can be sent by any trigger that is
  sending clock pulses."
  (javax.sound.midi.ShortMessage. javax.sound.midi.ShortMessage/STOP))

(def ^:private continue-message
  "A MIDI clock continue message that can be sent by any trigger that
  is sending clock pulses."
  (javax.sound.midi.ShortMessage. javax.sound.midi.ShortMessage/CONTINUE))

(defn- clock-interval
  "Calculate how many milliseconds there should be between clock
  pulses to represent the tempo reported by the latest status update
  seen by the current trigger, as found cached in the user data."
  [data]
  (/ 2500.0 (if-let [cached-status(:status data)]
              (if (= 65535 (.getBpm cached-status))
                120.0  ; Default to 120 bpm if the player has not loaded a track
                (.getEffectiveTempo cached-status))
              120.0)))  ; Default to 120 bpm if we lost status information momentarily

(defn- clock-sender
  "The loop which sends MIDI clock messages to synchronize a MIDI
  device with the tempo received from beat-link, as long as the
  trigger is enabled."
  [trigger]
  (try
    (timbre/info "Midi clock thread starting for Trigger"
                 (:index (seesaw/value trigger)))
    (loop [adjustment 0.0]  ; The amount we should subtract from the interval to fix missed timing
      (let [data @(seesaw/user-data trigger)
            output (get-chosen-output trigger data)
            interval (- (clock-interval data) adjustment)  ; The ideal amount we should sleep
            ms (Math/round (max 0.0 interval))  ; The actual amount we will try to sleep
            error (- ms interval)  ; The difference between ideal and actual sleep time
            target (+ (System/currentTimeMillis) ms)]  ; The time we are trying to wake up
        (when (some? output)  ; Send a clock pulse as long as our MIDI output is present
          (midi/midi-send-msg (:receiver output) clock-message -1))

        #_(timbre/info "adjustment" adjustment "interval" interval "ms" ms "error" error)
        (when (pos? ms) (Thread/sleep ms)) ; Sleep the appropriate amount based on the tempo

        (let [data @(seesaw/user-data trigger)]  ; Get updated data
          (when (and (= "Clock" (:message (:value data))) (enabled? trigger data))
            ;; We are still enabled, and still set to send clock messages, so continue the loop
            (let [miss (- target (System/currentTimeMillis))]
              #_(timbre/info "miss" miss "new adjustment" (- error miss))
              (recur (- error miss)))))))  ; Accumulate both kinds of error for next interval calculation
    (catch InterruptedException e)               ; No error to log, just asked to end ourselves
    (catch Throwable t
      (timbre/error t "Problem running MIDI clock loop, exiting, for Trigger"
                    (:index (seesaw/value trigger)))))
  (timbre/info "Midi Clock thread ending for Trigger"
               (:index (seesaw/value trigger))))

(defn- start-clock
  "Checks for, and creates if necessary, a thread that sends MIDI
  clock pulses based on the effective BPM of the watched player.
  `trigger-data` contains the map retrieved from the trigger
  `user-data` atom which is in the process of being updated, to save
  us having to look it up again."
  [trigger trigger-data]
  (when (or (nil? (:clock trigger-data)) (not (.isAlive (:clock trigger-data))))
    (swap! (seesaw/user-data trigger)
           (fn [data]
             (if (and (:clock data) (.isAlive (:clock data)))
               data  ; Someone already started it, so we can leave it as-is
               (assoc data :clock
                      (let [thread (Thread. #(clock-sender trigger))]
                        (.setPriority thread (dec Thread/MAX_PRIORITY))
                        (.start thread)
                        thread)))))))

(defn- stop-clock
  "Checks for, and stops and cleans up if necessary, any clock
  synchronization thread that might be running on the trigger.
  `trigger-data` contains the map retrieved from the trigger
  `user-data` atom which is in the process of being updated, to save
  us having to look it up again."
  [trigger trigger-data]
  (when (:clock trigger-data)
    (swap! (seesaw/user-data trigger)
           (fn [data]
             (when-let [thread (:clock data)]
               (when (.isAlive thread) (.interrupt thread)))
             (dissoc data :clock)))))

(defn- report-activation
  "Send a message indicating the player a trigger is watching has
  started playing, as long as the chosen output exists. `data`
  contains the map retrieved from the trigger `user-data` atom which
  is in the process of being updated, to save us from having to look
  it up again."
  [trigger status data]
  (try
    (let [{:keys [note channel message send start]} (:value data)]
      (timbre/info "Reporting activation:" message note "on channel" channel)
      (when-let [output (get-chosen-output trigger data)]
        (case message
          "Note" (midi/midi-note-on output note 127 (dec channel))
          "CC" (midi/midi-control output note 127 (dec channel))
          "Clock" (when send
                    (midi/midi-send-msg (:receiver output) (if (= "Start" start) start-message continue-message) -1))
          nil))
      (run-trigger-function trigger :activation status false))
    (catch Exception e
      (timbre/error e "Problem reporting player activation."))))

(defn- report-deactivation
  "Send a message indicating the player a trigger is watching has
  stopped playing, as long as the chosen output exists. `data`
  contains the map retrieved from the trigger `user-data` atom which
  is in the process of being updated, to save us from having to look
  it up again."
  [trigger status data]
  (try
    (let [{:keys [note channel message stop]} (:value data)]
      (timbre/info "Reporting deactivation:" message note "on channel" channel)
      (when-let [output (get-chosen-output trigger data)]
        (case message
          "Note" (midi/midi-note-off output note (dec channel))
          "CC" (midi/midi-control output note 0 (dec channel))
          "Clock" (when stop (midi/midi-send-msg (:receiver output) stop-message -1))
          nil))
      (when (= message "Link") (carabiner/unlock-tempo))
      (run-trigger-function trigger :deactivation status false))
    (catch Exception e
      (timbre/error e "Problem reporting player deactivation."))))

(defn- update-player-state
  "If the Playing state of a device being watched by a trigger has
  changed, send appropriate messages, start or stop its associated
  clock synchronization thread, and record the new state. Finally, run
  the Tracked Update Expression, if there is one, and we actually
  received a status update."
  [trigger playing on-air status]
  ;; TODO: See if any of the state tracking should be updated to take advantage of new TimeFinder consolidations.
  (let [old-data @(seesaw/user-data trigger)
        updated (swap! (seesaw/user-data trigger)
                       (fn [data]
                         (let [tripped (and playing (enabled? trigger))]
                           (merge data {:playing playing :on-air on-air :tripped tripped}
                                  (when (some? status) {:status status})))))]
    (let [tripped (:tripped updated)]
      (when-not (= tripped (:tripped old-data))
        (if tripped
          (report-activation trigger status updated)
          (report-deactivation trigger status updated)))
      (when (and tripped (= "Link" (:message (:value updated))))
        (let [tempo (.getEffectiveTempo status)]
          (if (carabiner/valid-tempo? tempo)
            (carabiner/lock-tempo tempo)
            (carabiner/unlock-tempo)))))
    (if (and (= "Clock" (:message (:value updated))) (enabled? trigger updated))
      (start-clock trigger updated)
      (stop-clock trigger updated)))
  (when (some? status)
    (run-trigger-function trigger :tracked status false))
  (seesaw/repaint! (seesaw/select trigger [:#state])))

(defn describe-track
  "Identifies a track with the best information available from its
  status update. If a non-nil `custom-description` is available, use
  it. Otherwise, honor the user preference setting to display either
  the rekordbox id information associated with it (when available), or
  simply the track's position within its playlist."
  [status custom-description]
  (cond
    (some? custom-description)
    custom-description

    (and (pos? (.getRekordboxId status)) (not (:tracks-using-playlists? @(global-user-data))))
    (str "Track id " (.getRekordboxId status) " [" (.getTrackSourcePlayer status) ":"
         (expressions/case-enum (.getTrackSourceSlot status)
           CdjStatus$TrackSourceSlot/USB_SLOT "usb"
           CdjStatus$TrackSourceSlot/SD_SLOT "sd"
           CdjStatus$TrackSourceSlot/COLLECTION "rb"
           "?")
         "]")

    :else
    (str "Track #" (.getTrackNumber status))))

(defn- extract-label
  "Given a `SearchableItem` from track metadata, extracts the string
  label. If passed `nil` simply returns `nil`."
  [^SearchableItem item]
  (when item
    (.label item)))

(defn- format-metadata
  "Include the appropriate track metadata items for display. If a
  non-nil `custom-summary` is available, use it. Otherwise show the
  track title, an em dash, and the track artist."
  [status metadata custom-summary]
  (let [summary (or custom-summary (str (.getTitle metadata) "&mdash;" (extract-label (.getArtist metadata))))]
    (str "<br>&nbsp;&nbsp; " summary)))

(defn build-status-label
  "Create a brief textual summary of a player state given a status
  update object from beat-link, and track description and metadata
  summary overrides from the trigger's custom expression
  locals (either of which may be nil, which means to build the
  standard description and summary for the track)."
  [status track-description metadata-summary]
  (let [beat (.getBeatNumber status)
        metadata (when (.isRunning metadata-finder) (.getLatestMetadataFor metadata-finder status))
        using-metadata? (or metadata metadata-summary)]
    (str (when using-metadata? "<html>")
         (.getDeviceNumber status) (if (.isPlaying status) " Playing" " Stopped")
         (when (.isTempoMaster status) ", Master")
         (when (.isOnAir status) ", On-Air")
         ", " (describe-track status track-description)
         ", " (if (= 65535 (.getBpm status)) "--.-" (format "%.1f" (.getEffectiveTempo status))) " BPM ("
         (format "%+.2f%%" (Util/pitchToPercentage (.getPitch status))) ")"
         (cond
           (neg? beat) ", beat n/a"
           (zero? beat) ", lead-in"
           :else (str ", beat " beat " (" (inc (quot (dec beat) 4)) "." (inc (rem (dec beat) 4)) ")"))
         (when using-metadata? (format-metadata status metadata metadata-summary)))))

(defn- online?
  "Check whether we are in online mode, with all the required
  beat-link finder objects running."
  []
  (and (.isRunning (DeviceFinder/getInstance)) (.isRunning (VirtualCdj/getInstance))))

(defn- show-device-status
  "Set the device satus label for a trigger outside of the context of
  receiving an update from the device (for example, the user chose a
  device in the menu which is not present on the network, or we just
  received a notification from the DeviceFinder that the device has
  disappeared. In either case, we are already on the Swing Event
  Update thread."
  [trigger]
  (try
    (let [player-menu (seesaw/select trigger [:#players])
          selection (seesaw/selection player-menu)
          status-label (seesaw/select trigger [:#status])
          track-description (:track-description @(:locals @(seesaw/user-data trigger)))
          metadata-summary (:metadata-summary @(:locals @(seesaw/user-data trigger)))]
      (if (nil? selection)
        (do (seesaw/config! status-label :foreground "red")
            (seesaw/value! status-label "No Player selected.")
            (update-player-state trigger false false nil))
        (let [found (when (online?) (.getLatestAnnouncementFrom (DeviceFinder/getInstance) (int (.number selection))))
              status (when (online?) (.getLatestStatusFor (VirtualCdj/getInstance) (int (.number selection))))]
          (if (nil? found)
            (do (seesaw/config! status-label :foreground "red")
                (seesaw/value! status-label (if (online?) "Player not found." "Offline."))
                (update-player-state trigger false false nil))
            (if (instance? CdjStatus status)
              (do (seesaw/config! status-label :foreground "cyan")
                  (seesaw/value! status-label (build-status-label status track-description metadata-summary)))
              (do (seesaw/config! status-label :foreground "red")
                  (seesaw/value! status-label (cond (some? status) "Non-Player status received."
                                                    (not (online?)) "Offline."
                                                    :else "No status received."))))))))
    (catch Exception e
      (timbre/error e "Problem showing Trigger Player status."))))

(defn- show-midi-status
  "Set the visibility of the Enabled checkbox and the text and color
  of its label based on whether the currently-selected MIDI output can
  be found. This function must be called on the Swing Event Update
  thread since it interacts with UI objects."
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

(defn- get-triggers
  "Returns the list of triggers that currently exist."
  []
  (when-let [frame @trigger-frame]
    (seesaw/config (seesaw/select frame [:#triggers]) :items)))

(defn- adjust-triggers
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

(defn- run-global-function
  "Checks whether the trigger frame has a custom function of the
  specified kind installed, and if so runs it with a nil status and
  trigger local atom, and the trigger global atom. Returns a tuple of
  the function return value and any thrown exception. If `alert?` is
  `true` the user will be alerted when there is a problem running the
  function."
  [kind]
  (let [data @(global-user-data)]
    (when-let [custom-fn (get-in data [:expression-fns kind])]
      (try
        [(custom-fn nil nil expression-globals) nil]
        (catch Throwable t
          (timbre/error t "Problem running global " kind " expression,"
                        (get-in data [:expressions kind]))
          (seesaw/alert (str "<html>Problem running global " (name kind) " expression.<br><br>" t)
                        :title "Exception in Custom Expression" :type :error)
          [nil t])))))

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

(defn- cleanup-trigger
  "Process the removal of a trigger, either via deletion, or importing
  a different trigger on top of it."
  [trigger]
  (run-trigger-function trigger :shutdown nil true)
  (seesaw/selection! (seesaw/select trigger [:#enabled]) "Never")   ; Ensures any clock thread stops
  (doseq [editor (vals (:expression-editors @(seesaw/user-data trigger)))]
      (editors/dispose editor)))

(defn- delete-trigger
  "Removes a trigger row from the window, running its shutdown
  function if needed, closing any editor windows associated with it,
  and readjusting any triggers that remain."
  [trigger]
  (try
    (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                    :items (remove #(= % trigger) (get-triggers)))
    (cleanup-trigger trigger)
    (adjust-triggers)
    (.pack @trigger-frame)
    (catch Exception e
      (timbre/error e "Problem deleting Trigger."))))

(declare update-global-expression-icons)

(defn- delete-all-triggers
  "Closes any global expression editors, then removes all triggers,
  running their own shutdown functions, and finally runs the global
  shutdown function."
  []
  (doseq [trigger (get-triggers)]
    (delete-trigger trigger))
  (doseq [editor (vals (:expression-editors @(global-user-data)))]
    (editors/dispose editor))
  (run-global-function :shutdown)
  (reset! expression-globals {})
  (reset! (global-user-data) (initial-global-user-data))
  (update-global-expression-icons))

(defn- update-gear-icon
  "Determines whether the gear button for a trigger should be hollow
  or filled in, depending on whether any expressions have been
  assigned to it."
  [trigger gear]
  (seesaw/config! gear :icon (if (every? empty? (vals (:expressions @(seesaw/user-data trigger))))
                               (seesaw/icon "images/Gear-outline.png")
                               (seesaw/icon "images/Gear-icon.png"))))

(declare export-trigger)
(declare import-trigger)

(defn- initial-trigger-user-data
  "Create the values to assign the user-data atom for a freshly
  created trigger."
  []
  {:creating true :playing false :tripped false :locals (atom {})})

(defn- load-trigger-from-map
  "Repopulate the content of a trigger row from a map as obtained from
  the preferences, a save file, or an export file."
  ([trigger m]
   (load-trigger-from-map trigger m (seesaw/select trigger [:#gear])))
  ([trigger m gear]
   (reset! (seesaw/user-data trigger) (initial-trigger-user-data))
   (when-let [exprs (:expressions m)]
     (swap! (seesaw/user-data trigger) assoc :expressions exprs)
     (doseq [[kind expr] exprs]
       (let [editor-info (get editors/trigger-editors kind)]
         (try
           (swap! (seesaw/user-data trigger) assoc-in [:expression-fns kind]
                  (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                     (editors/editor-title kind trigger false)))
           (catch Exception e
             (swap! (seesaw/user-data trigger) assoc :expression-load-error true)
             (timbre/error e (str "Problem parsing " (:title editor-info)
                                  " when loading Triggers. Expression:\n" expr "\n")))))))
   (seesaw/value! trigger m)
   (swap! (seesaw/user-data trigger) dissoc :creating)
   (let [[_ exception] (run-trigger-function trigger :setup nil false)]
     (when exception
       (swap! (seesaw/user-data trigger) assoc :expression-load-error true)))
   (update-gear-icon trigger gear)))

(defn cache-value
  "Make a copy of the values of the UI elements of a trigger into its
  user data map, so that it can safely be used by threads other than
  the Swing Event Dispatch thread. The alternative would be for other
  threads to use `invoke-now` to call functions on the Event Dispatch
  thread to read the values, but that would be far too slow for time
  sensitive high priority threads that are processing status and beat
  packets."
  [e]
  (let [trigger (.getParent (seesaw/to-widget e))]
       (swap! (seesaw/user-data trigger) assoc :value (seesaw/value trigger))))

(defn- create-trigger-row
  "Create a row for watching a player in the trigger window. If `m` is
  supplied, it is a map containing values to recreate the row from a
  saved version."
  ([]
   (create-trigger-row nil))
  ([m]
   (let [outputs (get-midi-outputs)
         gear (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
         panel (mig/mig-panel
                :id :panel
                :items [[(seesaw/label :id :index :text "1.") "align right"]
                        [(seesaw/text :id :comment :paint (partial paint-placeholder "Comment")) "span, grow, wrap"]

                        [gear]
                        ["Watch:" "alignx trailing"]
                        [(seesaw/combobox :id :players :model (get-player-choices)
                                          :listen [:item-state-changed cache-value])]

                        [(seesaw/label :id :status :text "Checking...")  "gap unrelated, span, wrap"]

                        ["MIDI Output:" "span 2, alignx trailing"]
                        [(seesaw/combobox :id :outputs :model (concat outputs  ; Add selection even if not available
                                                                      (when (and (some? m)
                                                                                 (not ((set outputs) (:outputs m))))
                                                                        [(:outputs m)]))
                                          :listen [:item-state-changed cache-value])]

                        ["Message:" "gap unrelated"]
                        [(seesaw/combobox :id :message :model ["Note" "CC" "Clock" "Link" "Custom"]
                                          :listen [:item-state-changed cache-value])]

                        [(seesaw/spinner :id :note :model (seesaw/spinner-model 127 :from 1 :to 127)
                                         :listen [:state-changed cache-value]) "hidemode 3"]
                        [(seesaw/checkbox :id :send :selected? true :visible? false
                                          :listen [:state-changed cache-value]) "hidemode 3"]
                        [(seesaw/checkbox :id :bar :text "Align at bar level        " :selected? true :visible? false
                                          :listen [:state-changed cache-value]) "hidemode 3"]

                        [(seesaw/label :id :channel-label :text "Channel:") "gap unrelated, hidemode 3"]
                        [(seesaw/combobox :id :start :model ["Start" "Continue"] :visible? false
                                          :listen [:item-state-changed cache-value]) "hidemode 3"]

                        [(seesaw/spinner :id :channel :model (seesaw/spinner-model 1 :from 1 :to 16)
                                         :listen [:state-changed cache-value]) "hidemode 3"]
                        [(seesaw/checkbox :id :stop :text "Stop" :selected? true :visible? false
                                          :listen [:item-state-changed cache-value]) "hidemode 3"]

                        [(seesaw/label :id :enabled-label :text "Enabled:") "gap unrelated"]
                        [(seesaw/combobox :id :enabled :model ["Never" "On-Air" "Custom" "Always"]
                                          :listen [:item-state-changed cache-value]) "hidemode 1"]
                        [(seesaw/canvas :id :state :size [18 :by 18] :opaque? false
                                        :tip "Trigger state: Outer ring shows enabled, inner light when tripped.")
                         "wrap, hidemode 1"]]

                :user-data (atom (initial-trigger-user-data)))
         export-action (seesaw/action :handler (fn [_] (export-trigger panel))
                                      :name "Export Trigger")
         import-action (seesaw/action :handler (fn [_] (import-trigger panel))
                                      :name "Import Trigger")
         delete-action (seesaw/action :handler (fn [_] (delete-trigger panel))
                                      :name "Delete Trigger")
         inspect-action (seesaw/action :handler (fn [_] (inspector/inspect @(:locals @(seesaw/user-data panel))
                                                                           :window-name "Trigger Expression Locals"))
                                       :name "Inspect Expression Locals"
                                       :tip "Examine any values set as Trigger locals by its Expressions.")
         editor-actions (fn []
                          (for [[kind spec] editors/trigger-editors]
                            (let [update-fn (fn []
                                              (when (= kind :setup)  ; Clean up then run the new setup function
                                                (run-trigger-function panel :shutdown nil true)
                                                (reset! (:locals @(seesaw/user-data panel)) {})
                                                (run-trigger-function panel :setup nil true))
                                              (update-gear-icon panel gear))]
                              (seesaw/action :handler (fn [e] (editors/show-trigger-editor kind panel update-fn))
                                             :name (str "Edit " (:title spec))
                                             :tip (:tip spec)
                                             :icon (if (empty? (get-in @(seesaw/user-data panel) [:expressions kind]))
                                                     (seesaw/icon "images/Gear-outline.png")
                                                     (seesaw/icon "images/Gear-icon.png"))))))
         popup-fn (fn [e] (concat (editor-actions)
                                  [(seesaw/separator) inspect-action (seesaw/separator) import-action export-action]
                                  (when (> (count (get-triggers)) 1) [delete-action])))]

     ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
     ;; or right click on the gear button.
     (seesaw/config! [panel gear] :popup popup-fn)
     (seesaw/listen gear
                    :mouse-pressed (fn [e]
                                     (let [popup (seesaw/popup :items (popup-fn e))]
                                       (show-popup-from-button gear popup e))))

     ;; Attach the custom paint function to render the graphical trigger state
     (seesaw/config! (seesaw/select panel [:#state]) :paint (partial paint-state panel))

     ;; Add listener to update the cached values when the comment changes, since the trigger cannot be
     ;; found from the DocumentEvent as it can for the other events.
     (let [comment (seesaw/select panel [:#comment])]
       (seesaw/listen comment :document (fn [_] (cache-value comment))))

     ;; Update the trigger state when the enabled state changes, and open an editor window if Custom is
     ;; chosen and the enabled filter expression is empty.
     (let [enabled-menu (seesaw/select panel [:#enabled])]
       (seesaw/listen enabled-menu
        :action-performed (fn [e]
                            (seesaw/repaint! (seesaw/select panel [:#state]))
                            (when (and (= "Custom" (seesaw/selection enabled-menu))
                                       (empty? (get-in @(seesaw/user-data panel) [:expressions :enabled])))
                              (editors/show-trigger-editor :enabled panel #(update-gear-icon panel gear))))))

     (seesaw/listen (seesaw/select panel [:#players])
                    :item-state-changed (fn [_]  ; Update player status when selection changes, clear any cached status
                                          (swap! (seesaw/user-data panel) dissoc :status)
                                          (show-device-status panel)))
     (seesaw/listen (seesaw/select panel [:#outputs])
                    :item-state-changed (fn [_]  ; Update output status when selection changes
                                          (show-midi-status panel)))

     ;; Tie the enabled state of the start/continue menu to the send checkbox
     (let [{:keys [send start]} (seesaw/group-by-id panel)]
       (bind/bind (bind/selection send)
                  (bind/property start :enabled?)))

     ;; Open an editor window if Custom is chosen for a message type and the activation expression is empty,
     ;; and open the Carabiner Connection window if Link is chosen and there is no current connection.
     ;; Also swap channel and note values for start/stop options when Clock is chosen, and for bar alignment
     ;; when Link is chosen.
     (let [message-menu (seesaw/select panel [:#message])]
       (seesaw/listen message-menu
        :action-performed (fn [_]
                            (let [choice (seesaw/selection message-menu)
                                  {:keys [note send channel-label start channel stop bar outputs]} (seesaw/group-by-id
                                                                                                    panel)]
                              (when (and (= "Custom" choice)
                                         (not (:creating @(seesaw/user-data panel)))
                                         (empty? (get-in @(seesaw/user-data panel) [:expressions :activation])))
                                (editors/show-trigger-editor :activation panel #(update-gear-icon panel gear)))
                              (when (and (= "Link" choice)
                                         (not (carabiner/active?)))
                                (carabiner/show-window @trigger-frame))
                              (cond
                                (= "Clock" choice) (do (seesaw/hide! [note channel-label channel bar])
                                                       (seesaw/show! [send start stop]))
                                (= "Link" choice) (do (seesaw/hide! [note channel-label channel send start stop])
                                                      (seesaw/show! [bar]))
                                :else (do (seesaw/show! [note channel-label channel])
                                          (seesaw/hide! [send start stop bar])))))))

     (when (some? m) ; If there was a map passed to us to recreate our content, apply it now
       (load-trigger-from-map panel m gear))
     (swap! (seesaw/user-data panel) dissoc :creating)
     (show-device-status panel)
     (cache-value gear)  ; Cache the initial values of the choice sections
     panel)))

(def ^:private new-trigger-action
  "The menu action which adds a new Trigger to the end of the list."
  (seesaw/action :handler (fn [e]
                            (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                            :items (concat (get-triggers) [(create-trigger-row)]))
                            (adjust-triggers))
                 :name "New Trigger"
                 :key "menu T"))

(def ^:private carabiner-action
  "The menu action which opens the Carabiner configuration window."
  (seesaw/action :handler (fn [e] (carabiner/show-window @trigger-frame))
                 :name "Ableton Link: Carabiner Connection"))

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
                                  (delete-all-triggers)
                                  (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                  :items [(create-trigger-row)])
                                  (adjust-triggers))
                                (seesaw/dispose! confirm))
                              (catch Exception e
                                (timbre/error e "Problem clearing Trigger list."))))
                 :name "Clear Triggers"))

(defn- format-trigger
  "Organizes the portions of a trigger which are saved or exported."
  [trigger]
  (-> (seesaw/value trigger)
             (dissoc :status :enabled-label :index)
             (merge (when-let [exprs (:expressions @(seesaw/user-data trigger))]
                      {:expressions exprs}))))

(defn- export-trigger
  "Saves a single trigger to a file for exchange or archival
  purposes."
  [trigger]
  (when-let [file (chooser/choose-file @trigger-frame :type "Export"
                                       :all-files? false
                                       :filters [["Trigger Export files" ["bltx"]]])]
    (when-let [file (util/confirm-overwrite-file file "bltx" @trigger-frame)]
      (try
        (spit file (with-out-str (fipp/pprint {:beat-link-trigger-export (about/get-version)
                                               :item                     (format-trigger trigger)})))
        (catch Exception e
          (seesaw/alert (str "<html>Unable to Export.<br><br>" e)
                        :title "Problem Writing File" :type :error))))))

(defn- trigger-configuration
  "Returns the current Trigger window configuration, so it can be
  saved and recreated."
  []
  (vec (for [trigger (get-triggers)]
         (format-trigger trigger))))

(defn- save-triggers-to-preferences
  "Saves the current Trigger window configuration to the application
  preferences."
  []
  (prefs/put-preferences (merge (prefs/get-preferences)
                                {:triggers (trigger-configuration)}
                                (when-let [exprs (:expressions @(global-user-data))]
                                  {:expressions exprs})
                                (select-keys @(global-user-data) [:tracks-using-playlists? :request-metadata?]))))

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
                            (when-let [file (chooser/choose-file @trigger-frame :type :save
                                                                 :all-files? false
                                                                 :filters [["BeatLinkTrigger configuration files"
                                                                            ["blt"]]])]
                              (when-let [file (util/confirm-overwrite-file file "blt" @trigger-frame)]
                                (try
                                  (prefs/save-to-file file)
                                  (catch Exception e
                                    (seesaw/alert (str "<html>Unable to Save.<br><br>" e)
                                                  :title "Problem Writing File" :type :error))))))
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
                                             :all-files? false
                                             :filters [["BeatLinkTrigger configuration files" ["blt"]]
                                                       (chooser/file-filter "All files" (constantly true))])]
                              (try
                                (prefs/load-from-file file)
                                (delete-all-triggers)
                                (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                :items (recreate-trigger-rows))
                                (adjust-triggers)
                                (catch Exception e
                                  (timbre/error e "Problem loading" file)
                                  (seesaw/alert (str "<html>Unable to Load.<br><br>" e)
                                                :title "Problem Reading File" :type :error)))
                              (check-for-parse-error)))
                 :name "Load"
                 :key "menu L"))

(def ^:private auto-action
  "The menu action which allows configuration of auto-attached
  metadata cache files."
  (seesaw/action :handler (fn [e] (auto/show-window @trigger-frame))
                 :name "Auto-Attach Metadata Caches"
                 :key "menu M"))

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
  (reify DeviceUpdateListener
    (received [this status]
      (try
        (doseq [trigger (get-triggers)]
          (let [selection (get-in @(seesaw/user-data trigger) [:value :players])]
            (when (and (instance? CdjStatus status) (matching-player-number? status trigger selection))
              (when-not (neg? (:number selection))
                (run-custom-enabled status trigger))  ; This was already done if Any Player is the selection
              (update-player-state trigger (.isPlaying status) (.isOnAir status) status)
              (seesaw/invoke-later
               (let [status-label (seesaw/select trigger [:#status])
                     track-description (:track-description @(:locals @(seesaw/user-data trigger)))
                     metadata-summary (:metadata-summary @(:locals @(seesaw/user-data trigger)))]
                 (seesaw/config! status-label :foreground "cyan")
                 (seesaw/value! status-label (build-status-label status track-description metadata-summary)))))))
        (catch Exception e
          (timbre/error e "Problem responding to Player status packet."))))))

(defonce ^{:private true
           :doc "Responds to beat packets and runs any registered beat
  expressions, and performs beat alignment for any tripped triggers
  that are assigned to Ableton Link."}
  beat-listener
  (reify BeatListener
    (newBeat [this beat]
      (try
        (doseq [trigger (get-triggers)]
          (let [data @(seesaw/user-data trigger)
                value (:value data)
                selection (:players value)]
            (when (and (some? selection)
                       (or (neg? (:number selection))
                           (= (:number selection) (.getDeviceNumber beat))
                           (and (zero? (:number selection))
                                (.isRunning (VirtualCdj/getInstance)) (.isTempoMaster beat))))
              (run-trigger-function trigger :beat beat false)
              (when (and (:tripped data) (= "Link" (:message value)) (carabiner/master?))
                (carabiner/beat-at-time (long (/ (.getTimestamp beat) 1000))
                                        (when (:bar value) (.getBeatWithinBar beat)))))))
        (catch Exception e
          (timbre/error e "Problem responding to beat packet."))))))

(defonce ^{:private true
           :doc "Responds to the arrival or departure of DJ Link
  devices by updating our user interface appropriately."}
  device-listener
  (reify DeviceAnnouncementListener
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

(defn- import-trigger
  "Replaces the content of a single trigger with a previously exported
  version."
  [trigger]
  (when-let [file (chooser/choose-file
                   @trigger-frame
                   :all-files? false
                   :filters [["Trigger Export files" ["bltx"]]
                             (chooser/file-filter "All files" (constantly true))])]
                              (try
                                (cleanup-trigger trigger)
                                (let [m (prefs/read-file :beat-link-trigger-export file)]
                                  (load-trigger-from-map trigger (translate-custom-enabled (:item m))))
                                (catch Exception e
                                  (timbre/error e "Problem importing" file)
                                  (seesaw/alert (str "<html>Unable to Import.<br><br>" e)
                                                :title "Problem Importing Trigger" :type :error)))
                              (check-for-parse-error)))

(defn- recreate-trigger-rows
  "Reads the preferences and recreates any trigger rows that were
  specified in them. If none were found, returns a single, default
  trigger. Also updates the global setup and shutdown expressions,
  running them as needed, and sets the default track description."
  []
  (let [m (prefs/get-preferences)]
    (.doClick (seesaw/select @trigger-frame [(if (:tracks-using-playlists? m) :#track-position :#track-id)]))
    (.setSelected (seesaw/select @trigger-frame [:#request-metadata]) (true? (:request-metadata? m)))
    (when-let [exprs (:expressions m)]
      (swap! (global-user-data) assoc :expressions exprs)
      (doseq [[kind expr] exprs]
        (let [editor-info (get editors/global-editors kind)]
          (try
            (swap! (global-user-data) assoc-in [:expression-fns kind]
                   (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                      (editors/editor-title kind nil true)))
            (catch Exception e
              (timbre/error e (str "Problem parsing " (:title editor-info)
                                   " when loading Triggers. Expression:\n" expr "\n"))
              (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                 "Check the log file for details.")
                            :title "Exception during Clojure evaluation" :type :error)))))
      (run-global-function :setup))
    (update-global-expression-icons)
    (let [triggers (:triggers m)]
      (if (seq triggers)
        (vec (for [trigger triggers]
               (create-trigger-row (translate-custom-enabled trigger))))
        [(create-trigger-row)]))))

(defn build-global-editor-action
  "Creates an action which edits one of the global expressions."
  [kind]
  (seesaw/action :handler (fn [e] (editors/show-trigger-editor kind (seesaw/config @trigger-frame :content)
                                                               (fn []
                                                                 (when (= :setup kind)
                                                                   (run-global-function :shutdown)
                                                                   (reset! expression-globals {})
                                                                   (run-global-function :setup))
                                                                 (update-global-expression-icons))))
                 :name (str "Edit " (get-in editors/global-editors [kind :title]))
                 :tip (get-in editors/global-editors [kind :tip])
                 :icon (seesaw/icon (if (empty? (get-in @(global-user-data) [:expressions kind]))
                                       "images/Gear-outline.png"
                                       "images/Gear-icon.png"))))

(defn- acceptable-metadata-state-for-player-status
  "Check whether we are currently requesting metadata, as the user has
  asked to show the player status window. If not, warn the user about
  reduced functionality and how they should fix that. Return a truthy
  value if we should proceed to show the player status window."
  []
  (or (request-metadata?)
      (let [options (to-array ["Cancel" "Turn On Metadata Requests" "Open Anyway"])
            message (str "Beat Link Trigger is not currently configured to request track metadata.\n"
                         "Most of the Player Status Window features require track metadata to work.\n\n"
                         "Would you like to turn on the Request Track Metadata feature before opening\n"
                         "the Player Status window?")
            choice  (seesaw/invoke-now
                     (javax.swing.JOptionPane/showOptionDialog
                      nil message "Metadata Requests Recommended for Player Status"
                      javax.swing.JOptionPane/YES_NO_CANCEL_OPTION javax.swing.JOptionPane/WARNING_MESSAGE nil
                      options (aget options 2)))]
        (case choice
          0 nil  ; Cancel.
          1 (do  ; Turn on Metadata Requests.
              (.setSelected (seesaw/select @trigger-frame [:#request-metadata]) true)
              nil)
          true))))  ; Open Anyway.

(defn- show-player-status
  "Try to show the player status window, giving the user appropriate
  feedback if the current environment is not appropriate, or even not
  ideal. A Seesaw event handler, but we ignore the event argument."
  [_]
  (if (.isRunning (VirtualCdj/getInstance))
    (when (acceptable-metadata-state-for-player-status)
      (players/show-window @trigger-frame expression-globals))
    (seesaw/alert "Must be Online to show Player Status window."
                  :title "Beat Link Trigger is Offline" :type :error)))

(def ^:private player-status-action
  "The menu action which opens the Player Status window."
  (seesaw/action :handler show-player-status
                 :name "Show Player Status"
                 :key "menu P"))

(declare go-offline)

(defn- try-go-active
  "Helper method that tries going out of passive mode and gives a nice
  error message if we can't because a metadata cache is being
  created."
  []
  (try
    (.setPassive metadata-finder false)
    (catch IllegalStateException e
      (.setSelected (seesaw/select @trigger-frame [:#request-metadata]) false)
      (seesaw/alert "Cannot actively request metadata while a metadata cache is being created."
                    :title "Cache Creation In Progress" :type :error))))

(defn- actively-request-metadata
  "Try to start gathering metadata if we are online. Warn the user if
  our device number will make that unreliable, and give them choices
  about how to proceed."
  []
  (when (online?)
    (if (> (.getDeviceNumber (VirtualCdj/getInstance)) 4)
      (let [players (count (util/visible-player-numbers))
            options (to-array (if (> players 1)
                                ["Cancel" "Use Unreliable Metadata" "Go Offline"]
                                ["Cancel" "Go Offline"]))
            mode    (if (> players 1)
                      javax.swing.JOptionPane/YES_NO_CANCEL_OPTION
                      javax.swing.JOptionPane/YES_NO_OPTION)
            message (str "Beat Link Trigger is using device number " (.getDeviceNumber (VirtualCdj/getInstance))
                         ".\nTo reliably request metadata, it needs to use number 1, 2, 3, or 4.\n\n"
                         (cond
                           (= players 1)
                           (str "Since there is only one player on the network, unreliable metadata cannot\n"
                                "be used. You will need to go offline and then back online, which will use\n"
                                "an available player number that allows reliable metadata requests.\n\n")

                           (< players 4)
                           (str "Since there are fewer than 4 CDJs on the network, all you need to do is\n"
                                "go offline and then back online, and it will be able to use one of the\n"
                                "unused device numbers, which will work great.\n\n")

                           :else
                           (str "Please go offline, turn off one of the four CDJs currently on the network,\n"
                                "then go back online, which will let us use that player's device number.\n\n"))

                         (when (> players 1)
                           (str "You may also choose to use unreliable metadata, which will work unless all\n"
                                "of the CDJs load tracks from a media slot on the same player, and which\n"
                                "may cause problems for DJs trying to use Link Info.\n\n"
                                "Alternatively, you can create a metadata cache from the media in a player,\n"
                                "and use that without turning on active metadata requests.")))
            choice (seesaw/invoke-now
                    (javax.swing.JOptionPane/showOptionDialog
                     nil message "Incompatible Device Number for Metadata Requests"
                     mode javax.swing.JOptionPane/ERROR_MESSAGE nil
                     options (aget options (dec (count options)))))]
        (cond
          (zero? choice) ; Cancel.
          (.setSelected (seesaw/select @trigger-frame [:#request-metadata]) false)

          (and (> players 1) (= choice 1)) ; Use unreliable metadata requests.
          (try-go-active)

          :else ; Go offline.
          (.setSelected (seesaw/select @trigger-frame [:#online]) false)))
      (try-go-active))))  ; We can reliably request metadata.

(declare go-online)

(defn- build-trigger-menubar
  "Creates the menu bar for the trigger window."
  []
  (let [inspect-action (seesaw/action :handler (fn [e] (inspector/inspect @expression-globals
                                                                          :window-name "Expression Globals"))
                                      :name "Inspect Expression Globals"
                                      :tip "Examine any values set as globals by any Trigger Expressions.")
        using-playlists? (:tracks-using-playlists? @(global-user-data))
        online-item (seesaw/checkbox-menu-item :text "Online?" :id :online :selected? (online?))
        metadata-item (seesaw/checkbox-menu-item :text "Request Track Metadata?" :id :request-metadata
                                                 :selected? (request-metadata?))
        bg (seesaw/button-group)
        track-submenu (seesaw/menu :text "Default Track Description"
                                   :items [(seesaw/radio-menu-item :text "recordbox id [player:slot]" :id :track-id
                                                                   :selected? (not using-playlists?) :group bg)
                                           (seesaw/radio-menu-item :text "playlist position" :id :track-position
                                                                   :selected? using-playlists? :group bg)])]
    (seesaw/listen bg :selection
                   (fn [e]
                     (when-let [s (seesaw/selection bg)]
                       (swap! (global-user-data) assoc :tracks-using-playlists? (= (seesaw/id-of s) :track-position)))))
    (seesaw/listen online-item :item-state-changed
                   (fn [e]
                     (if (= (.getStateChange e) java.awt.event.ItemEvent/SELECTED)
                       (go-online)
                       (go-offline))))
    (seesaw/listen metadata-item :item-state-changed
                   (fn [e]
                     (swap! (global-user-data) assoc :request-metadata? (= (.getStateChange e)
                                                                           java.awt.event.ItemEvent/SELECTED))
                     (if (request-metadata?)
                       (actively-request-metadata)
                       (.setPassive metadata-finder true))))
    (seesaw/menubar :items [(seesaw/menu :text "File"
                                         :items (concat [load-action save-action
                                                         (seesaw/separator) auto-action
                                                         (seesaw/separator) logs/logs-action]
                                                        menus/non-mac-actions))
                            (seesaw/menu :text "Triggers"
                                         :items (concat [new-trigger-action (seesaw/separator)]
                                                        (map build-global-editor-action (keys editors/global-editors))
                                                        [(seesaw/separator)
                                                         track-submenu inspect-action
                                                         (seesaw/separator) clear-triggers-action])
                                         :id :triggers-menu)

                            (seesaw/menu :text "Network"
                                         :items [online-item metadata-item player-status-action
                                                 (seesaw/separator) carabiner-action]
                                         :id :network-menu)])))

(defn update-global-expression-icons
  "Updates the icons next to expressions in the Trigger menu to
  reflect whether they have been assigned a non-empty value."
  []
  (let [menu (seesaw/select @trigger-frame [:#triggers-menu])]
    (doseq [i (range (.getItemCount menu))]
      (let [item (.getItem menu i)]
        (when item
          (let [label (.getText item)]
            (cond (= label "Edit Global Setup Expression")
                  (.setIcon item (seesaw/icon (if (empty? (get-in @(global-user-data) [:expressions :setup]))
                                                "images/Gear-outline.png"
                                                "images/Gear-icon.png")))

                  (= label "Edit Global Shutdown Expression")
                  (.setIcon item (seesaw/icon (if (empty? (get-in @(global-user-data) [:expressions :shutdown]))
                                                "images/Gear-outline.png"
                                                "images/Gear-icon.png"))))))))))

(defn- create-trigger-window
  "Create and show the trigger window."
  []
  (try
    (let [root (seesaw/frame :title "Beat Link Triggers" :on-close :exit
                             :menubar (build-trigger-menubar))
          triggers (seesaw/vertical-panel :id :triggers)
          panel (seesaw/scrollable triggers :user-data (atom (initial-global-user-data)))]
      (seesaw/config! root :content panel)
      (reset! trigger-frame root)
      (seesaw/config! triggers :items (recreate-trigger-rows))
      (adjust-triggers)
      (seesaw/show! root)
      (check-for-parse-error))
    (catch Exception e
      (timbre/error e "Problem creating Trigger window."))))

(defn- start-other-finders
  "Starts up the full complement of metadata-related finders that we
  use."
  []
  (.start metadata-finder)
  (.start (ArtFinder/getInstance))
  (.start (BeatGridFinder/getInstance))
  (.setFindDetails (WaveformFinder/getInstance) true)
  (.start (WaveformFinder/getInstance)))

(defn start
  "Create the Triggers window, and register all the notification
  handlers it needs in order to stay up to date with events on the
  MIDI and DJ Link networks. If the window already exists, just bring
  it to the front, to support returning to online operation."
  []
  (if @trigger-frame
    (do
      (rebuild-all-device-status)
      (seesaw/show! @trigger-frame))
    (do
      ;; Request notifications when MIDI devices appear or vanish
      (when (CoreMidiDeviceProvider/isLibraryLoaded)
        (CoreMidiDeviceProvider/addNotificationListener
         (reify uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification
           (midiSystemUpdated [this]
             (midi-environment-changed)))))

      ;; Open the trigger window
      (create-trigger-window)
      (.addShutdownHook (Runtime/getRuntime) (Thread. save-triggers-to-preferences))

      ;; Be able to react to players coming and going
      (.addDeviceAnnouncementListener (DeviceFinder/getInstance) device-listener)
      (.addUpdateListener (VirtualCdj/getInstance) status-listener)
      (rebuild-all-device-status)  ; In case any came or went while we were setting up the listener
      (.addBeatListener (BeatFinder/getInstance) beat-listener))) ; Allow triggers to respond to beats
  (.start (BeatFinder/getInstance))
  (.setPassive metadata-finder true) ; Start out conservatively
  (when (online?) (start-other-finders))
  (when (request-metadata?) (actively-request-metadata)))

(defn go-offline
  "Transition to an offline state, updating the UI appropriately."
  []
  (.stop (WaveformFinder/getInstance))
  (.stop (BeatGridFinder/getInstance))
  (.stop (ArtFinder/getInstance))
  (.stop metadata-finder)
  (.stop (BeatFinder/getInstance))
  (.stop (VirtualCdj/getInstance))
  (Thread/sleep 200)  ; Wait for straggling update packets
  (rebuild-all-device-status))

(defn go-online
  "Try to transition to an online state, updating the UI appropriately."
  []
  (seesaw/hide! @trigger-frame)
  (future ((resolve 'beat-link-trigger.core/try-going-online))
          (if (online?)
            (start-other-finders)
            (.setSelected (seesaw/select @trigger-frame [:#online]) false))))
