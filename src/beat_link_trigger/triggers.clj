(ns beat-link-trigger.triggers
  "Implements the main window, displaying a list of triggers that send
  events in response to changes in CDJ states."
  (:require [beat-link-trigger.carabiner :as carabiner]
            [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.nrepl :as nrepl]
            [beat-link-trigger.players :as players]
            [beat-link-trigger.playlist-writer :as writer]
            [beat-link-trigger.overlay :as overlay]
            [beat-link-trigger.track-loader :as track-loader]
            [beat-link-trigger.settings-loader :as settings-loader]
            [beat-link-trigger.simulator :as sim]
            [beat-link-trigger.show :as show]
            [beat-link-trigger.show-util :as show-util]
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.util :as util]
            [beat-carabiner.core :as beat-carabiner]
            [clojure.set]
            [clojure.string]
            [fipp.edn :as fipp]
            [inspector-jay.core :as inspector]
            [overtone.midi :as midi]
            [seesaw.bind :as bind]
            [seesaw.chooser :as chooser]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre]
            [thi.ng.color.core :as color])
  (:import [beat_link_trigger.util PlayerChoice]
           [java.awt Color Graphics2D RenderingHints]
           [java.awt.event WindowEvent]
           [javax.swing JFrame JMenu JMenuItem JCheckBoxMenuItem JRadioButtonMenuItem]
           [org.deepsymmetry.beatlink BeatFinder BeatListener CdjStatus CdjStatus$TrackSourceSlot
            DeviceAnnouncementListener DeviceFinder DeviceUpdateListener LifecycleListener Util VirtualCdj]
           [org.deepsymmetry.beatlink.data AnalysisTagFinder ArtFinder BeatGridFinder CrateDigger MetadataFinder
            SearchableItem SignatureFinder TimeFinder TrackMetadata WaveformFinder]
           [beat_link_trigger.util MidiChoice]
           [org.deepsymmetry.electro Metronome]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDeviceProvider]))

(defonce ^{:doc "Provides a space for trigger expressions to store
  values they want to share across triggers. Visible to other
  namespaces so that, for example, Show expressions can access them."}
  expression-globals (atom {}))

;; Make the expression globals conveniently available when compiling
;; shared functions too.
(in-ns 'beat-link-trigger.expressions)

#_{:clj-kondo/ignore [:unresolved-namespace]}
(def globals
  "The Beat Link Trigger expression globals"
  beat-link-trigger.triggers/expression-globals)
(in-ns 'beat-link-trigger.triggers)

(defonce ^{:private true
           :doc "Holds the trigger window, through which we can access and
  manipulate the triggers themselves."}
  trigger-frame
  (atom nil))

(defn- online-menu-item
  "Helper function to find the Online menu item, which is often toggled
  by code."
  ^JCheckBoxMenuItem []
  (seesaw/select @trigger-frame [:#online]))

(def ^DeviceFinder device-finder
  "A convenient reference to the [Beat Link
  `DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
  singleton."
  (DeviceFinder/getInstance))

(def ^VirtualCdj virtual-cdj
  "A convenient reference to the [Beat Link
  `VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
  singleton."
  (VirtualCdj/getInstance))

(def ^MetadataFinder metadata-finder
  "A convenient reference to the [Beat Link
  `MetadataFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html)
  singleton."
  (MetadataFinder/getInstance))

;; Register the custom readers needed to read back in the defrecords that we use.
(prefs/add-reader 'beat_link_trigger.util.PlayerChoice util/map->PlayerChoice)
(prefs/add-reader 'beat_link_trigger.util.MidiChoice util/map->MidiChoice)
;; For backwards compatibility:
;; Also register under the old package names before they were moved to the util namespace.
(prefs/add-reader 'beat_link_trigger.triggers.PlayerChoice util/map->PlayerChoice)
(prefs/add-reader 'beat_link_trigger.core.PlayerChoice util/map->PlayerChoice)
(prefs/add-reader 'beat_link_trigger.triggers.MidiChoice util/map->MidiChoice)
(prefs/add-reader 'beat_link_trigger.core.MidiChoice util/map->MidiChoice)

(defn- initial-trigger-prefs
  "Create the values to assign the trigger preferences map."
  []
  (merge {:global true}
         (select-keys (prefs/get-preferences) [:send-status? :tracks-using-playlists?])))

(defonce ^{:private true
           :doc "Global trigger configuration and expressions."}
  trigger-prefs
  (atom (initial-trigger-prefs)))

(defn quit
  "Gracefully attempt to quit, giving the user a chance to veto so they
  can save unsaved changes in editor windows. Essentially, click the
  close box of the Triggers window, if it is open; otherwise we are in
  a state where there is nothing to save anyway, so just exit."
  []
  (if @trigger-frame
    (.dispatchEvent ^JFrame @trigger-frame (WindowEvent. @trigger-frame WindowEvent/WINDOW_CLOSING))
    (System/exit 0)))

(defn real-player?
  "Checks whether the user wants to pose as a real player, numbered 1
  through 4, and send status update packets. This is the only way we
  can take charge of other players' tempo, and get metadata for CD and
  unanalyzed media."
  []
  (boolean (:send-status? @trigger-prefs)))

(defn- enabled?
  "Check whether a trigger is enabled."
  ([trigger]
   (let [data @(seesaw/user-data trigger)]
     (enabled? trigger data)))
  ([_ data]
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
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(custom-fn status data expression-globals) nil])
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/triggers-editor-title kind trigger false) ":\n"
                               (get-in data [:expressions kind])))
          (when alert?
            (seesaw/alert (str "<html>Problem running trigger " (name kind) " expression.<br><br>" t)
                          :title "Exception in Trigger Expression" :type :error))
          [nil t])))))

(defn- run-custom-enabled
  "Invokes the custom enabled filter assigned to a trigger, if any,
  recording the result in the trigger user data."
  [status trigger]
  (when (= "Custom" (get-in @(seesaw/user-data trigger) [:value :enabled]))
    (let [[enabled? _] (run-trigger-function trigger :enabled status false)]
      (swap! (seesaw/user-data trigger) assoc-in [:expression-results :enabled] enabled?))))

(defn- device-present?
  "Checks whether a device is on the network by number. Works both when
  online and when simulated playback is happening."
  [device-number]
  (boolean (or (sim/for-player device-number)
               (some? (when (util/online?) (.getLatestAnnouncementFrom device-finder device-number))))))

(defn- latest-status-for
  "Returns the latest status packet received from a device by number.
  Works both when online and when simulated playback is happening."
  [device-number]
  (or (:latest-status (sim/for-player device-number))
      (when (util/online?) (when (util/online?) (.getLatestStatusFor virtual-cdj device-number)))))

(defn- is-better-match?
  "Checks whether the current status packet represents a better
  matching device for a trigger to track than the one it is currently
  tracking. The best kind of match is both enabled and playing, second
  best is at least enabled, third best is playing. Ties are broken in
  favor of the player with the lowest number.

  In order to determine the enabled state, we need to run the custom
  enabled function if it is in use, so that will be called for all
  incoming packets for such triggers."
  [^CdjStatus status trigger]
  (let [data                             @(seesaw/user-data trigger)
        enable-mode                      (get-in data [:value :enabled])
        custom-enable                    (= enable-mode "Custom")
        custom-result                    (when custom-enable
                                           (first (run-trigger-function trigger :enabled status false)))
        would-enable?                    (case enable-mode
                                           "Always" true
                                           "On-Air" (.isOnAir status)
                                           "Custom" custom-result
                                           false)
        this-device                      (.getDeviceNumber status)
        match-score                      (+ (if would-enable? 1024 0)
                                            (if (.isPlaying status) 512 0)
                                            (- this-device))
        [existing-score existing-device] (:last-match data)
        best                             (or (when (some? existing-device) (not (device-present? existing-device)))
                                             (>= match-score (or existing-score -256)))]
    (when (or best (= this-device existing-device))
      (swap! (seesaw/user-data trigger) assoc :last-match [match-score this-device]))
    (when (and best custom-enable)
      (swap! (seesaw/user-data trigger) assoc-in [:expression-results :enabled] custom-result))
    best))

(defn- matching-player-number?
  "Checks whether a CDJ status update matches a trigger, handling the
  special cases of the Master Player and Any Player. For Any Player we
  want to stay tracking the same Player most of the time, so we will
  keep track of the last one we matched, and change only if this is a
  better match. This should only be called with full-blown status
  updates, not beats."
  [^CdjStatus status trigger player-selection]
  (and (some? player-selection)
       (or (= (:number player-selection) (.getDeviceNumber status))
           (and (zero? (:number player-selection)) (.isTempoMaster status))
           (and (neg? (:number player-selection)) (is-better-match? status trigger)))))

(defn get-player-choices
  "Returns a sorted list of the player watching choices, including
  options to watch Any Player and the Master Player."
  []
  (for [i (range -1 5)]
    (PlayerChoice. i)))

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
  ([_ data]
   (when-let [^MidiChoice selection (get-in data [:value :outputs])]
     (let [device-name (.full_name selection)]
       (or (get @util/opened-outputs device-name)
           (try
             (let [new-output (midi/midi-out (str "^" (java.util.regex.Pattern/quote device-name) "$"))]
               (swap! util/opened-outputs assoc device-name new-output)
               new-output)
             (catch IllegalArgumentException e  ; The chosen output is not currently available
               (timbre/debug e "Trigger using nonexisting MIDI output" device-name))
             (catch Exception e  ; Some other problem opening the device
               (timbre/error e "Problem opening device" device-name "(treating as unavailable)"))))))))

(defn no-output-chosen
  "Returns truthy if the MIDI output menu for a trigger is empty, which
  will probably only happen if there are no MIDI outputs available on
  the host system, but we still want to allow non-MIDI expressions to
  operate."
  ([trigger]
   (no-output-chosen trigger @(seesaw/user-data trigger)))
  ([_ data]
   (not (get-in data [:value :outputs]))))

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

(defn- clock-sender
  "The loop which sends MIDI clock messages to synchronize a MIDI device
  with the tempo received from beat-link, as long as the trigger is
  enabled and our `running` atom holds a `true` value."
  [trigger ^Metronome metro running]
  (try
    (timbre/info "Midi clock thread starting for Trigger"
                 (:index (seesaw/value trigger)))
    (let [last-beat-sent (atom nil)]
      (loop []
        (let [trigger-data  @(seesaw/user-data trigger)
              output        (get-chosen-output trigger trigger-data)
              snapshot      (.getSnapshot metro)
              beat          (.getBeat snapshot)
              next-beat-due (.getTimeOfBeat snapshot (inc beat))
              sleep-ms      (- next-beat-due (System/currentTimeMillis))]
          (when (and (some? output) (not= beat @last-beat-sent))
            ;; Send a clock pulse if we are on a new beat as long as our MIDI output is present.
            (midi/midi-send-msg (:receiver output) clock-message -1)
            (reset! last-beat-sent beat))

          (if (> sleep-ms 5)  ; Long enough to actually try sleeping until we are closer to due.
            (try
              (Thread/sleep (- sleep-ms 5))
              (catch InterruptedException _))
            (loop [target-time (+ (System/nanoTime)
                                  (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS sleep-ms))]
              (when (and (not (Thread/interrupted))
                         (< (System/nanoTime) target-time))
                (recur target-time))))  ; Busy-waiting, woo hoo, hope you didn't need this core!

          (when (and @running (= "Clock" (:message (:value trigger-data))) (enabled? trigger trigger-data))
            (recur)))))  ;; We are still running, enabled, and set to send clock messages, so continue the loop.
    (catch Throwable t
      (timbre/error t "Problem running MIDI clock loop, exiting, for Trigger"
                    (:index (seesaw/value trigger)))))
  (timbre/info "Midi Clock thread ending for Trigger"
               (:index (seesaw/value trigger))))

(defn- clock-tempo
  "Calculate how many MIDI clock messages per minute there should be
  based on the latest status update seen by the trigger, as found
  cached in the user data (MIDI sends 24 clock pulses per quarter
  note, so we multiply the trigger tempo by 24.) If the Expression
  Global `:use-fixed-sync-bpm` has a value, we pretend the playing
  track has that BPM and calculate the effective tempo based on the
  player pitch."
  [trigger-data]
  (let [bpm-override (:use-fixed-sync-bpm @expression-globals)
        base-tempo   (if-let [^CdjStatus cached-status (:status trigger-data)]
                       (if (= 65535 (.getBpm cached-status))
                         (or bpm-override 120.0) ; Default to 120 bpm if the player has not loaded a track.
                         (if bpm-override
                           (* (org.deepsymmetry.beatlink.Util/pitchToMultiplier (.getPitch cached-status)) bpm-override)
                           (.getEffectiveTempo cached-status)))
                       (or bpm-override 120.0))]  ; Default to 120 bpm if we lost status information momentarily.
    (* base-tempo 24.0)))

(defonce ^{:doc "How much the tempo must change (in beats per minute) before we adjust the MIDI clock rate."}
  midi-clock-sensitivity
  (atom 0.01))

(defn- clock-running?
  "Checks whether the supplied trigger data contains an active MIDI
  clock sender thread. If so, returns a truthy value after alerting
  the thread if a tempo change has occurred so that it can react
  immediately. The actual value returned when the clock thread is
  running will be a tuple of the thread, its metronome, and its
  running flag, to make it easy to shut down when necessary."
  [trigger-data]
  (let [[^Thread clock-thread ^Metronome metro :as all] (:clock trigger-data)]
    (when (and clock-thread (.isAlive clock-thread))
      (let [tempo (clock-tempo trigger-data)]
        (when (> (Math/abs (- tempo (.getTempo metro))) @midi-clock-sensitivity)
          (.setTempo metro tempo)      ; Tempo has changed, update metronome, and...
          (.interrupt clock-thread)))  ; wake up the thread so it can adjust immediately.
      all)))

(defn- start-clock
  "Checks for, and creates if necessary, a thread that sends MIDI clock
  pulses based on the effective BPM of the watched player. If the
  thread is already running, but the tempo has changed from what it is
  sending, interrupts the thread so it can immediately react to the
  new tempo."
  [trigger]
  (try
    (swap! (seesaw/user-data trigger)
           (fn [trigger-data]
             (if (clock-running? trigger-data)
               trigger-data  ; Clock is already running, and we have alerted it to tempo changes, so leave it as-is.
               (let [running      (atom true)  ; We need to create a new thread and its metronome and shutdown flag.
                     metro        (Metronome.)
                     clock-thread (Thread. #(clock-sender trigger metro running) "MIDI clock sender")]
                 (.setTempo metro (clock-tempo trigger-data))
                 (.setDaemon clock-thread true)
                 (.setPriority clock-thread (dec Thread/MAX_PRIORITY))
                 (.start clock-thread)
                 (assoc trigger-data :clock [clock-thread metro running])))))
    (catch Throwable t
      (timbre/error t "Problem trying to start or update MIDI clock thread."))))


(defn- stop-clock
  "Checks for, and stops and cleans up if necessary, any clock
  synchronization thread that might be running on the trigger. To
  optimize the common case when we don't need to do anything,
  `trigger-data` contains the already-loaded trigger user data."
  [trigger trigger-data]
  (when (clock-running? trigger-data)  ; Skip entirely if we have nothing to do.
    (try
      (swap! (seesaw/user-data trigger)
             (fn [trigger-data]
               (when-let [[^Thread clock-thread _ running] (clock-running? trigger-data)]
                 (reset! running false)
                 (.interrupt clock-thread))
               (dissoc trigger-data :clock)))
      (catch Throwable t
        (timbre/error t "Problem trying to stop MIDI clock thread.")))))

(defn- report-activation
  "Send a message indicating the player a trigger is watching has
  started playing, as long as the chosen output exists. `data`
  contains the map retrieved from the trigger `user-data` atom which
  is in the process of being updated, to save us from having to look
  it up again. If `real?` is passed with a falsy value, this is being
  done because the user chose to simulate the MIDI message or function
  call, so interaction with Carabiner and MIDI Clock are suppressed."
  ([trigger status data]
   (report-activation trigger status data true))
  ([trigger status data real?]
   (try
     (let [{:keys [note channel message send start start-stop]} (:value data)]
       (timbre/debug "Reporting activation:" message note "on channel" channel)
       (when-let [output (get-chosen-output trigger data)]
         (case message
           "Note"  (midi/midi-note-on output note 127 (dec channel))
           "CC"    (midi/midi-control output note 127 (dec channel))
           "Clock" (when (and send real?)
                     (midi/midi-send-msg (:receiver output) (if (= "Start" start) start-message continue-message) -1))
           nil))
       (when (and real? (= message "Link") (carabiner/sync-triggers?) start-stop)
         (beat-carabiner/start-transport))
       (run-trigger-function trigger :activation status (not real?)))
     (catch Exception e
       (timbre/error e "Problem reporting player activation.")))))

(defn- report-deactivation
  "Send a message indicating the player a trigger is watching has
  stopped playing, as long as the chosen output exists. `data`
  contains the map retrieved from the trigger `user-data` atom which
  is in the process of being updated, to save us from having to look
  it up again. If `real?` is passed with a falsy value, this is being
  done because the user chose to simulate the MIDI message or function
  call, so interaction with Carabiner and MIDI Clock are suppressed."
  ([trigger status data]
   (report-deactivation trigger status data true))
  ([trigger status data real?]
   (try
     (let [{:keys [note channel message stop start-stop]} (:value data)]
       (timbre/debug "Reporting deactivation:" message note "on channel" channel)
       (when-let [output (get-chosen-output trigger data)]
         (case message
           "Note"  (midi/midi-note-off output note (dec channel))
           "CC"    (midi/midi-control output note 0 (dec channel))
           "Clock" (when (and stop real?) (midi/midi-send-msg (:receiver output) stop-message -1))
           nil))
       (when (and real? (= message "Link") (carabiner/sync-triggers?))
         (beat-carabiner/unlock-tempo)
         (when start-stop (beat-carabiner/stop-transport)))
       (run-trigger-function trigger :deactivation status (not real?)))
     (catch Exception e
       (timbre/error e "Problem reporting player deactivation.")))))

(defn- update-player-state
  "If the Playing state of a device being watched by a trigger has
  changed, send appropriate messages, start/update or stop its
  associated clock synchronization thread, and record the new state.
  Finally, run the Tracked Update Expression, if there is one, and we
  actually received a status update."
  [trigger playing on-air ^CdjStatus status]
  (let [old-data @(seesaw/user-data trigger)
        updated  (swap! (seesaw/user-data trigger)
                        (fn [data]
                          (let [tripped (and playing (enabled? trigger))]
                            (merge data
                                   {:playing playing :on-air on-air :tripped tripped}
                                   (when (some? status) {:status status})))))]
    (let [tripped (:tripped updated)]
      (when-not (= tripped (:tripped old-data))
        (if tripped
          (report-activation trigger status updated)
          (report-deactivation trigger status updated)))
      (when (and tripped (= "Link" (:message (:value updated))))
        (let [tempo (if-let [bpm-override (:use-fixed-sync-bpm @expression-globals)]
                      (* (org.deepsymmetry.beatlink.Util/pitchToMultiplier (.getPitch status)) bpm-override)
                      (.getEffectiveTempo status))]
          (when (carabiner/sync-triggers?)
            (if (beat-carabiner/valid-tempo? tempo)
              (beat-carabiner/lock-tempo tempo)
              (beat-carabiner/unlock-tempo))))))
    (if (and (= "Clock" (:message (:value updated))) (enabled? trigger updated))
      (start-clock trigger)
      (stop-clock trigger updated)))
  (when (some? status)
    (run-trigger-function trigger :tracked status false))
  (seesaw/repaint! (seesaw/select trigger [:#state])))

(defn describe-track
  "Identifies a track with the best information available from its
  status update. If a non-`nil` `custom-description` is available, use
  it. Otherwise, honor the user preference setting to display either
  the rekordbox id information associated with it (when available), or
  simply the track's position within its playlist."
  [^CdjStatus status custom-description]
  (cond
    (some? custom-description)
    custom-description

    (and (pos? (.getRekordboxId status)) (not (:tracks-using-playlists? @trigger-prefs)))
    (str "Track id " (.getRekordboxId status) " [" (.getTrackSourcePlayer status) ":"
         (util/case-enum (.getTrackSourceSlot status)
           CdjStatus$TrackSourceSlot/USB_SLOT "usb"
           CdjStatus$TrackSourceSlot/SD_SLOT "sd"
           CdjStatus$TrackSourceSlot/CD_SLOT "cd"
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
  [^TrackMetadata metadata custom-summary]
  (let [summary (or custom-summary (str (.getTitle metadata) "&mdash;" (extract-label (.getArtist metadata))))]
    (str "<br>&nbsp;&nbsp; " summary)))

(defn build-status-label
  "Create a brief textual summary of a player state given a status
  update object from beat-link, and track description and metadata
  summary overrides from the trigger's custom expression
  locals (either of which may be `nil`, which means to build the
  standard description and summary for the track)."
  [^CdjStatus status track-description metadata-summary]
  (let [beat (.getBeatNumber status)
        [metadata metadata-summary] (if (.isRunning metadata-finder)
                                      [(.getLatestMetadataFor metadata-finder status) metadata-summary]
                                      (let [sim-data (get-in (sim/for-player (.getDeviceNumber status))
                                                             [:track :metadata])]
                                        [nil (or metadata-summary
                                                 (str (:title sim-data) "&mdash;" (:artist sim-data)))]))
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
         (when using-metadata? (format-metadata metadata metadata-summary)))))

(defn- show-device-status
  "Set the device satus label for a trigger outside of the context of
  receiving an update from the device (for example, the user chose a
  device in the menu which is not present on the network, or we just
  received a notification from the DeviceFinder that the device has
  disappeared. In either case, we are already on the Swing Event
  Update thread."
  [trigger]
  (try
    (let [player-menu             (seesaw/select trigger [:#players])
          ^PlayerChoice selection (seesaw/selection player-menu)
          status-label            (seesaw/select trigger [:#status])
          track-description       (:track-description @(:locals @(seesaw/user-data trigger)))
          metadata-summary        (:metadata-summary @(:locals @(seesaw/user-data trigger)))]
      (if (nil? selection)
        (do (seesaw/config! status-label :foreground "red")
            (seesaw/value! status-label "No Player selected.")
            (update-player-state trigger false false nil))
        (let [device-number (int (.number selection))
              found         (device-present? device-number)
              status        (latest-status-for device-number)]
          (if found
            (if (instance? CdjStatus status)
              (do (seesaw/config! status-label :foreground "cyan")
                  (seesaw/value! status-label (build-status-label status track-description metadata-summary)))
              (do (seesaw/config! status-label :foreground "red")
                  (seesaw/value! status-label (cond (some? status)       "Non-Player status received."
                                                    (not (util/online?)) "Offline."
                                                    :else                "No status received."))))
            (do (seesaw/config! status-label :foreground "red")
                (seesaw/value! status-label (if (util/online?) "Player not found." "Offline."))
                (update-player-state trigger false false nil))))))
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
          enabled       (seesaw/select trigger [:#enabled])
          state         (seesaw/select trigger [:#state])
          output        (get-chosen-output trigger)]
      (if (or output (no-output-chosen trigger))
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
  "Returns the list of triggers that currently exist. If `show` is
  supplied, returns the triggers that belong to that show (if `show`
  is supplied but `nil`, returns the triggers that exist independently
  of any show)."
  ([]
   (when-let [frame @trigger-frame]
     (seesaw/config (seesaw/select frame [:#triggers]) :items)))
  ([show]
   (filter #(= (:file show) (:show-file @(seesaw/user-data %))) (get-triggers))))

(defn- trigger-color
  "Calculates the color that should be used as the background color for a
  trigger row, given the trigger index and show index."
  [trigger-index show-index]
  (let [base-color (if (odd? trigger-index) "#eee" "#ddd")]
    (if (zero? show-index)
      base-color
      (let [luminance (-> base-color color/css color/luminance)
            hue       (mod (* show-index 62.5) 360.0)
            color     (color/hsla (/ hue 360.0) 0.95 luminance)]
        (Color. @(color/as-int24 color))))))

(defn- adjust-triggers
  "Called when a trigger is added or removed to restore the proper
  alternation of background colors and identification of source shows.
  Also resize the window if it still fits the screen, and update any
  other user interface elements that might be affected."
  []
  (when (seq (get-triggers))
    (loop [triggers       (get-triggers)
           trigger-index  1
           show-index     0
           last-show-file nil]
      (let [trigger    (first triggers)
            remaining  (rest triggers)
            show-file  (:show-file @(seesaw/user-data trigger))
            show-index (if (= show-file last-show-file) show-index (inc show-index))]
        (seesaw/config! trigger :background (trigger-color trigger-index show-index))
        (seesaw/config! (seesaw/select trigger [:#index]) :text (str trigger-index "."))
        (if (= show-file last-show-file)
          (seesaw/config! (seesaw/select trigger [:#from-show]) :visible? false)
          (seesaw/config! (seesaw/select trigger [:#from-show]) :visible? true
                          :text (str "Triggers from Show " (util/trim-extension (.getPath show-file)) ":")))
        (doseq [editor (vals (:expression-editors @(seesaw/user-data trigger)))]
          (editors/retitle editor))
        (when (seq remaining)
          (recur remaining
                 (inc trigger-index)
                 show-index
                 show-file)))))
  (let [^JFrame frame @trigger-frame]
    (when (< 100 (- (.height (.getBounds (.getGraphicsConfiguration frame)))
                    (.height (.getBounds frame))))
      (.pack frame))))

(defn- run-global-function
  "Checks whether the trigger frame has a custom function of the
  specified kind installed, and if so runs it with a nil status and
  trigger local atom, and the trigger global atom. Returns a tuple of
  the function return value and any thrown exception."
  [kind]
  (let [data @trigger-prefs]
    (when-let [custom-fn (get-in data [:expression-fns kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(custom-fn nil nil expression-globals) nil])
        (catch Throwable t
          (timbre/error t "Problem running global " kind " expression,"
                        (get-in data [:expressions kind]))
          (seesaw/alert (str "<html>Problem running global " (name kind) " expression.<br><br>" t)
                        :title "Exception in Custom Expression" :type :error)
          [nil t])))))

(defn- paint-state
  "Draws a representation of the state of the trigger, including both
  whether it is enabled and whether it has tripped (or would have, if
  it were not disabled)."
  [trigger c ^Graphics2D g]
  (let [w (double (seesaw/width c))
        h (double (seesaw/height c))
        outline (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        enabled? (enabled? trigger)
        state @(seesaw/user-data trigger)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (if (:tripped state)
      (do  ; Draw the inner filled circle showing the trigger is tripped
        (.setPaint g Color/green)
        (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))
      (when (:playing state)  ; Draw the inner gray circle showing it would trip if it were not disabled
        (.setPaint g Color/lightGray)
        (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0)))))

    ;; Draw the outer circle that reflects the enabled state
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if enabled? Color/green Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(defn close-trigger-editors?
  "Tries closing all open expression editors for the trigger. If
  `force?` is true, simply closes them even if they have unsaved
  changes. Othewise checks whether the user wants to save any unsaved
  changes. Returns truthy if there are none left open the user wants
  to deal with."
  [force? trigger]
  (every? (partial editors/close-editor? force?) (vals (:expression-editors @(seesaw/user-data trigger)))))

(defn- cleanup-trigger
  "Process the removal of a trigger, either via deletion, or importing a
  different trigger on top of it. If `force?` is true, any unsaved
  expression editors will simply be closed. Otherwise, they will block
  the trigger removal, which will be indicated by this function
  returning falsey."
  [force? trigger]
  (when (close-trigger-editors? force? trigger)
    (run-trigger-function trigger :shutdown nil true)
    (seesaw/selection! (seesaw/select trigger [:#enabled]) "Never")  ; Ensures any clock thread stops.
    true))

(defn delete-trigger
  "Removes a trigger row from the window, running its shutdown function
  if needed, closing any editor windows associated with it, and
  readjusting any triggers that remain. If `force?` is true, editor
  windows will be closed even if they have unsaved changes, otherwise
  the user will be given a chance to cancel the operation. Returns
  truthy if the trigger was actually deleted."
  [force? trigger]
  (try
    (when (close-trigger-editors? force? trigger)
      (cleanup-trigger true trigger)
      (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                      :items (remove #(= % trigger) (get-triggers)))
      (adjust-triggers)
      (.pack ^JFrame @trigger-frame)
      true)
    (catch Exception e
      (timbre/error e "Problem deleting Trigger."))))

(declare update-global-expression-icons)

(defn- delete-all-triggers
  "Closes any global expression editors, then removes all non-show
  triggers, running their own shutdown functions, and finally runs the
  global offline (if we were online) and shutdown functions. If
  `force?` is true, editor windows will be closed even if they have
  unsaved changes, otherwise the user will be given a chance to cancel
  the operation. Returns truthy if the trigger was actually deleted."
  [force?]
  (when (and (every? (partial close-trigger-editors? force?) (get-triggers nil))
             (every? (partial editors/close-editor? force?) (vals (:expression-editors @trigger-prefs))))
    (doseq [trigger (get-triggers nil)]
      (delete-trigger true trigger))
    (when (util/online?) (run-global-function :offline))
    (run-global-function :shutdown)
    (reset! expression-globals {})
    (reset! trigger-prefs (initial-trigger-prefs))
    (update-global-expression-icons)
    true))

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
     (doseq [[kind expr] (editors/sort-setup-to-front exprs)]
       (let [editor-info (get editors/trigger-editors kind)]
         (try
           (swap! (seesaw/user-data trigger) assoc-in [:expression-fns kind]
                  (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                     (editors/triggers-editor-title kind trigger false)
                                                     (:no-locals? editor-info)))
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

(defn- missing-expression?
  "Checks whether the expression body of the specified kind is empty
  given a trigger panel."
  [trigger kind]
  (empty? (get-in @(seesaw/user-data trigger) [:expressions kind])))

(defn- simulate-enabled?
  "Checks whether the specified event type can be simulated for the
  given trigger (its message is Note or CC, or there is a non-empty
  expression body)."
  [trigger event]
  (let [message (get-in @(seesaw/user-data trigger) [:value :message])]
    (or (#{"Note" "CC"} message) (not (missing-expression? trigger event)))))

(defn- create-trigger-row
  "Create a row for watching a player in the trigger window. If `m` is
  supplied, it is a map containing values to recreate the row from a
  saved version. If `index` is supplied, it is the initial index to
  assign the trigger (so exceptions logged during load can be
  meaningful), otherwise 1 is assumed (and will get renumbered by
  `adjust-triggers`)."
  ([]
   (create-trigger-row nil 1))
  ([m index]
   (let [outputs (util/get-midi-outputs)
         gear    (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
         panel   (mig/mig-panel
                  :id :panel
                  :items [[(seesaw/label :id :from-show :text "Triggers from no Show." :visible? false :halign :center)
                           "hidemode 3, span, grow, wrap unrelated"]
                          [(seesaw/label :id :index :text (str index ".")) "align right"]
                          [(seesaw/text :id :comment :paint (partial util/paint-placeholder "Comment"))
                           "span, grow, wrap"]

                          [gear]
                          ["Watch:" "alignx trailing"]
                          [(seesaw/combobox :id :players :model (get-player-choices)
                                            :listen [:item-state-changed cache-value])]

                          [(seesaw/label :id :status :text "Checking...")  "gap unrelated, span, wrap"]

                          ["MIDI Output:" "span 2, alignx trailing"]
                          [(seesaw/combobox :id :outputs
                                            :model (concat outputs
                                                           ;; Preserve existing selection even if now missing.
                                                           (when (and (some? m) (not ((set outputs) (:outputs m))))
                                                             [(:outputs m)])
                                                           ;; Offer escape hatch if no MIDI devices available.
                                                           (when (and (:outputs m) (empty? outputs))
                                                             [nil]))
                                            :listen [:item-state-changed cache-value])]

                          ["Message:" "gap unrelated"]
                          [(seesaw/combobox :id :message :model ["Note" "CC" "Clock" "Link" "Custom"]
                                            :listen [:item-state-changed cache-value])]

                          [(seesaw/spinner :id :note :model (seesaw/spinner-model 127 :from 1 :to 127)
                                           :listen [:state-changed cache-value]) "hidemode 3"]
                          [(seesaw/checkbox :id :send :selected? true :visible? false
                                            :listen [:state-changed cache-value]) "hidemode 3"]
                          [(seesaw/checkbox :id :bar :text "Align bars" :selected? true :visible? false
                                            :listen [:state-changed cache-value]) "hidemode 3"]
                          [(seesaw/checkbox :id :start-stop :text "Start/Stop" :selected? false :visible? false
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
         export-action  (seesaw/action :handler (fn [_] (export-trigger panel))
                                       :name "Export Trigger")
         import-action  (seesaw/action :handler (fn [_] (import-trigger panel))
                                       :name "Import Trigger")
         delete-action  (seesaw/action :handler (fn [_] (delete-trigger true panel))
                                       :name "Delete Trigger")
         inspect-action (seesaw/action :handler (fn [_] (try
                                                          (inspector/inspect @(:locals @(seesaw/user-data panel))
                                                                             :window-name "Trigger Expression Locals")
                                                          (catch StackOverflowError _
                                                            (util/inspect-overflowed))
                                                          (catch Throwable t
                                                            (util/inspect-failed t))))
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
                              (seesaw/action :handler (fn [_] (editors/show-trigger-editor kind panel update-fn))
                                             :name (str "Edit " (:title spec))
                                             :tip (:tip spec)
                                             :icon (if (missing-expression? panel kind)
                                                     (seesaw/icon "images/Gear-outline.png")
                                                     (seesaw/icon "images/Gear-icon.png"))))))
         sim-actions    (fn []
                          [(seesaw/action :name "Activation"
                                          :enabled? (simulate-enabled? panel :activation)
                                          :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation)]
                                                             (report-activation panel (show-util/random-cdj-status)
                                                                                @(seesaw/user-data panel) false))))
                           (seesaw/action :name "Beat"
                                          :enabled? (not (missing-expression? panel :beat))
                                          :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation)]
                                                             (run-trigger-function panel :beat
                                                                                   (show-util/random-beat) true))))
                           (seesaw/action :name "Tracked Update"
                                          :enabled? (not (missing-expression? panel :tracked))
                                          :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation)]
                                                             (run-trigger-function
                                                              panel :tracked (show-util/random-cdj-status) true))))
                           (seesaw/action :name "Deactivation"
                                          :enabled? (simulate-enabled? panel :deactivation)
                                          :handler (fn [_] (binding [util/*simulating* (util/data-for-simulation)]
                                                             (report-deactivation panel (show-util/random-cdj-status)
                                                                                  @(seesaw/user-data panel) false))))])
         popup-fn       (fn [_] (concat (editor-actions)
                                        [(seesaw/separator) (seesaw/menu :text "Simulate" :items (sim-actions))
                                         inspect-action (seesaw/separator) import-action export-action]
                                        (when (or (:show-file @(seesaw/user-data panel))
                                                  (> (count (get-triggers nil)) 1))
                                          [delete-action])))]

     ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
     ;; or right click on the gear button.
     (seesaw/config! [panel gear] :popup popup-fn)
     (seesaw/listen gear
                    :mouse-pressed (fn [e]
                                     (let [popup (seesaw/popup :items (popup-fn e))]
                                       (util/show-popup-from-button gear popup e))))

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
        :action-performed (fn [_]
                            (seesaw/repaint! (seesaw/select panel [:#state]))
                            (when (and (= "Custom" (seesaw/selection enabled-menu))
                                       (empty? (get-in @(seesaw/user-data panel) [:expressions :enabled])))
                              (editors/show-trigger-editor :enabled panel #(update-gear-icon panel gear))))))

     (seesaw/listen (seesaw/select panel [:#players])
                    :item-state-changed (fn [_]  ; Update player status when selection changes
                                          (seesaw/invoke-later  ; Make sure menu value cache update has happened.
                                           (swap! (seesaw/user-data panel) dissoc :status)  ; Clear cached status.
                                           (show-device-status panel))))
     (seesaw/listen (seesaw/select panel [:#outputs])
                    :item-state-changed (fn [_]  ; Update output status when selection changes.
                                          ;; We need to do this later to ensure the other item-state-changed
                                          ;; handler has had a chance to update the trigger data first.
                                          (seesaw/invoke-later (show-midi-status panel))))

     ;; Tie the enabled state of the start/continue menu to the send checkbox
     (let [{:keys [send start]} (seesaw/group-by-id panel)]
       (bind/bind (bind/selection send)
                  (bind/property start :enabled?)))

     ;; Open an editor window if Custom is chosen for a message type and the activation expression is empty,
     ;; and open the Carabiner Connection window if Link is chosen and there is no current connection.
     ;; Also swap channel and note values for start/stop options when Clock is chosen, and for bar alignment
     ;; and Link start/stop checkboxes when Link is chosen.
     (let [message-menu (seesaw/select panel [:#message])]
       (seesaw/listen message-menu
        :action-performed (fn [_]
                            (let [choice                                (seesaw/selection message-menu)
                                  {:keys [note send channel-label start
                                          channel stop bar start-stop]} (seesaw/group-by-id panel)]
                              (when (and (= "Custom" choice)
                                         (not (:creating @(seesaw/user-data panel)))
                                         (empty? (get-in @(seesaw/user-data panel) [:expressions :activation])))
                                (editors/show-trigger-editor :activation panel #(update-gear-icon panel gear)))
                              (when (and (= "Link" choice)
                                         (not (beat-carabiner/active?)))
                                (carabiner/show-window @trigger-frame))
                              (cond
                                (= "Clock" choice) (do (seesaw/hide! [note channel-label channel bar start-stop])
                                                       (seesaw/show! [send start stop]))
                                (= "Link" choice)  (do (seesaw/hide! [note channel-label channel send start stop])
                                                       (seesaw/show! [bar start-stop]))
                                :else              (do (seesaw/show! [note channel-label channel])
                                                       (seesaw/hide! [send start stop bar start-stop])))))))

     (when (some? m) ; If there was a map passed to us to recreate our content, apply it now
       (load-trigger-from-map panel m gear))
     (swap! (seesaw/user-data panel) dissoc :creating)
     (show-device-status panel)
     (show-midi-status panel)
     (cache-value gear)  ; Cache the initial values of the choice sections
     panel)))

(defonce ^{:private true
           :doc     "The menu action which adds a new Trigger to the end of the list of non-show triggers."}
  new-trigger-action
  (delay
   (seesaw/action :handler (fn [_]
                             (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                             :items (concat (get-triggers nil) [(create-trigger-row)]
                                                            (filter #(some? (:show-file @(seesaw/user-data %)))
                                                                    (get-triggers))))
                             (adjust-triggers))
                  :name "New Trigger"
                  :key "menu T")))

(defn create-trigger-for-show
  "Creates a new trigger that belongs to the show that was opened from
  the specified file. If `m` is supplied, it is a map containing the
  contents with which the trigger should be recreated."
  ([show]
   (create-trigger-for-show show {}))
  ([show m]
   (let [triggers        (get-triggers)
         triggers-before (take-while #(not= (:file show) (:show-file @(seesaw/user-data %))) triggers)
         show-triggers   (get-triggers show)
         trigger-index   (+ (count triggers-before) (count show-triggers) 1)
         new-trigger     (create-trigger-row m trigger-index)
         triggers-after  (drop (dec trigger-index) triggers)]
     (swap! (seesaw/user-data new-trigger) assoc :show-file (:file show))
     (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                     :items (concat triggers-before show-triggers [new-trigger] triggers-after))
     (adjust-triggers))))

(defonce ^{:private true
           :doc "The menu action which opens the Carabiner configuration window."}
  carabiner-action
  (delay (seesaw/action :handler (fn [_] (carabiner/show-window @trigger-frame))
                        :name "Ableton Link: Carabiner Connection")))

(defonce ^{:private true
           :doc "The menu action which opens the nREPL configuration window."}
  nrepl-action
  (delay (seesaw/action :handler (fn [_] (nrepl/show-window @trigger-frame))
                        :name "nREPL: Clojure IDE Connection")))

(defonce ^{:private true
           :doc "The menu action which opens the Load Track window."}
  load-track-action
  (delay (seesaw/action :handler (fn [_] (track-loader/show-dialog))
                        :name "Load Track on Player" :enabled? false)))

(defonce ^{:private true
           :doc "The menu action which opens the Load Settings window."}
  load-settings-action
  (delay (seesaw/action :handler (fn [_] (settings-loader/show-dialog))
                        :name "Load Settings on Player" :enabled? false)))

(defonce ^{:private true
           :doc     "The menu action which empties the Trigger list."}
  clear-triggers-action
  (delay
   (seesaw/action :handler (fn [_]
                             (try
                               (let [^java.awt.Window confirm
                                     (seesaw/dialog :content (str "Clear Triggers?\n"
                                                                  "You will be left with one default Trigger.")
                                                    :type :warning :option-type :yes-no)]
                                 (.pack confirm)
                                 (.setLocationRelativeTo confirm @trigger-frame)
                                 (when (= :success (seesaw/show! confirm))
                                   (delete-all-triggers true)
                                   (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                   :items (concat [(create-trigger-row)] (get-triggers)))
                                   (adjust-triggers))
                                 (seesaw/dispose! confirm))
                               (catch Exception e
                                 (timbre/error e "Problem clearing Trigger list."))))
                  :name "Clear Triggers")))

(defn- format-trigger
  "Organizes the portions of a trigger which are saved or exported."
  [trigger]
  (-> (seesaw/value trigger)
             (dissoc :status :channel-label :enabled-label :index :from-show)
             (merge (when-let [exprs (:expressions @(seesaw/user-data trigger))]
                      {:expressions exprs}))))

(defn- export-trigger
  "Saves a single trigger to a file for exchange or archival
  purposes."
  [trigger]
  (let [extension (util/extension-for-file-type :trigger-export)]
    (when-let [file (chooser/choose-file @trigger-frame :type "Export"
                                         :all-files? false
                                         :filters [["Trigger Export files" [extension]]])]
      (when-let [file (util/confirm-overwrite-file file extension @trigger-frame)]
        (try
          (spit file (with-out-str (fipp/pprint {:beat-link-trigger-export (util/get-version)
                                                 :item                     (format-trigger trigger)})))
          (catch Exception e
            (seesaw/alert (str "<html>Unable to Export.<br><br>" e)
                          :title "Problem Writing File" :type :error)))))))

(defn trigger-configuration-for-show
  "Returns the list of trigger configurations for all triggers belonging
  to the specified show, so they can be saved along with the show when
  that is being saved."
  [show]
  (vec (for [trigger (get-triggers show)]
         (format-trigger trigger))))

(defn- trigger-configuration
  "Returns the current Trigger window configuration, so it can be
  saved and recreated."
  []
  (trigger-configuration-for-show nil))

(defn- save-triggers-to-preferences
  "Saves the current Trigger window configuration to the application
  preferences."
  []
  (prefs/put-preferences (merge (prefs/get-preferences)
                                {:triggers         (trigger-configuration)
                                 :window-positions @util/window-positions}
                                (when-let [exprs (:expressions @trigger-prefs)]
                                  {:expressions exprs})
                                (select-keys @trigger-prefs [:tracks-using-playlists? :send-status?]))))

(defonce ^{:private true
           :doc "The menu action which saves the configuration to the preferences."}
  save-action
  (delay (seesaw/action :handler (fn [_] (save-triggers-to-preferences))
                        :name "Save"
                        :key "menu S")))

(defonce ^{:private true
           :doc "The menu action which saves the configuration to a user-specified file."}
  save-as-action
  (delay
   (seesaw/action :handler (fn [_]
                             (when (save-triggers-to-preferences)
                               (let [extension (util/extension-for-file-type :configuration)]
                                 (when-let [file (chooser/choose-file @trigger-frame :type :save
                                                                      :all-files? false
                                                                      :filters [["BeatLinkTrigger configuration files"
                                                                                 [extension]]])]
                                   (when-let [file (util/confirm-overwrite-file file extension @trigger-frame)]
                                     (try
                                       (prefs/save-to-file file)
                                       (catch Exception e
                                         (seesaw/alert (str "<html>Unable to Save.<br><br>" e)
                                                       :title "Problem Writing File" :type :error))))))))
                  :name "Save to File")))

(declare recreate-trigger-rows)

(defn- check-for-parse-error
  "Called after loading the triggers from a file or the preferences to
  see if there were problems parsing any of the custom expressions. If
  so, reports that to the user and clears the warning flags."
  []
  (let [failed (filter identity (for [trigger (get-triggers nil)]
                                  (when (:expression-load-error @(seesaw/user-data trigger))
                                    (swap! (seesaw/user-data trigger) dissoc :expression-load-error)
                                    (editors/trigger-index trigger))))]
    (when (seq failed)
      (seesaw/alert (str "<html>Unable to use an expression for Trigger "
                         (clojure.string/join ", " failed) ".<br><br>"
                         "Check the log file for details.")
                    :title "Exception during Clojure evaluation" :type :error))))

(defonce ^{:private true
           :doc "The menu action which loads the configuration from a user-specified file."}
  load-action
  (delay
   (seesaw/action :handler (fn [_]
                             (let [extension (util/extension-for-file-type :configuration)]
                               (when-let [file (chooser/choose-file
                                                @trigger-frame
                                                :all-files? false
                                                :filters [["BeatLinkTrigger configuration files" [extension]]
                                                          (chooser/file-filter "All files" (constantly true))])]
                                 (try
                                   (when (delete-all-triggers false)
                                     (prefs/load-from-file file)
                                     (seesaw/config! (seesaw/select @trigger-frame [:#triggers])
                                                     :items (concat (recreate-trigger-rows) (get-triggers)))
                                     (adjust-triggers)
                                     (when (util/online?) (run-global-function :online)))
                                   (catch Exception e
                                     (timbre/error e "Problem loading" file)
                                     (seesaw/alert (str "<html>Unable to Load.<br><br>" e)
                                                   :title "Problem Reading File" :type :error)))
                                 (check-for-parse-error))))
                  :name "Load from File"
                  :key "menu L")))

(defn- midi-environment-changed
  "Called when CoreMidi4J reports a change to the MIDI environment, so we can update the menu of
  available MIDI outputs."
  []
  (seesaw/invoke-later  ; Need to move to the AWT event thread, since we interact with GUI objects
   (try
     (let [new-outputs (util/get-midi-outputs)
           output-set  (set (map #(.full_name %) new-outputs))]
       ;; Remove any opened outputs that are no longer available in the MIDI environment
       (swap! util/opened-outputs  #(apply dissoc % (clojure.set/difference (set (keys %)) output-set)))

       (doseq [trigger (get-triggers)] ; Update the output menus in all trigger rows
         (let [output-menu   (seesaw/select trigger [:#outputs])
               old-selection (seesaw/selection output-menu)]
           (seesaw/config! output-menu :model (concat new-outputs
                                                      ;; Keep the old selection even if it is now missing.
                                                      (when-not (output-set old-selection) [old-selection])
                                                      ;; Allow deselection of a vanished output device
                                                      ;; if there are now no devices available, so
                                                      ;; tracks using custom expressions can still work.
                                                      (when (and (some? old-selection) (empty? new-outputs)) [nil])))

           ;; Keep our original selection chosen, even if it is now missing.
           (seesaw/selection! output-menu old-selection))
         (show-midi-status trigger))
       (show/midi-environment-changed new-outputs output-set))
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
    (received [_this status]
      (try
        (doseq [trigger (get-triggers)]
          (let [selection (get-in @(seesaw/user-data trigger) [:value :players])]
            (when (and (instance? CdjStatus status) (matching-player-number? status trigger selection))
              (let [^CdjStatus status status]
                (when-not (neg? (:number selection))
                  (run-custom-enabled status trigger)) ; This was already done if Any Player is the selection
                (update-player-state trigger (.isPlaying status) (.isOnAir status) status)
                (seesaw/invoke-later
                 (let [status-label (seesaw/select trigger [:#status])
                       track-description (:track-description @(:locals @(seesaw/user-data trigger)))
                       metadata-summary (:metadata-summary @(:locals @(seesaw/user-data trigger)))]
                   (seesaw/config! status-label :foreground "cyan")
                   (seesaw/value! status-label (build-status-label status track-description metadata-summary))))))))
        (catch Exception e
          (timbre/error e "Problem responding to Player status packet."))))))

(defonce ^{:private true
           :doc "Responds to beat packets and runs any registered beat
  expressions, and performs beat alignment for any tripped triggers
  that are assigned to Ableton Link."}
  beat-listener
  (reify BeatListener
    (newBeat [_this beat]
      (try
        (doseq [trigger (get-triggers)]
          (let [data @(seesaw/user-data trigger)
                value (:value data)
                selection (:players value)]
            (when (and (some? selection)
                       (or (= (:number selection) (.getDeviceNumber beat))
                           (and (zero? (:number selection))
                                (.isRunning virtual-cdj) (.isTempoMaster beat))
                           (and (neg? (:number selection))  ; For Any Player, make sure beat's from the tracked player.
                                (= (get-in data [:last-match 1]) (.getDeviceNumber beat)))))
              (run-trigger-function trigger :beat beat false)
              (when (and (:tripped data) (= "Link" (:message value)) (carabiner/sync-triggers?))
                (carabiner/beat-at-time (long (/ (.getTimestamp beat) 1000))
                                        (when (:bar value) (.getBeatWithinBar beat)))))))
        (catch Exception e
          (timbre/error e "Problem responding to beat packet."))))))

(def offline-cooldown-ms
  "The amount of time to wait for things to stabilize after starting the
  process of going offline, before trying to go back online
  automatically."
  500)

(defonce ^{:private true
           :doc "Responds to the arrival or departure of DJ Link
  devices by updating our user interface appropriately. If we were
  online and lost the last device, drop offline and then switch to
  trying to go back online until the user cancels that, for reliable
  headless operation."}
  device-listener
  (reify DeviceAnnouncementListener
    (deviceFound [_this _announcement]
      (rebuild-all-device-status))
    (deviceLost [_this _announcement]
      (rebuild-all-device-status)
      (when (and (util/online?) (empty? (.getCurrentDevices device-finder)))
        ;; We are online but lost the last DJ Link device. Switch back to looking for the network.
        (future
          (seesaw/invoke-now  ; Go offline.
           (.setSelected (online-menu-item) false))
          (Thread/sleep offline-cooldown-ms)  ; Give things a chance to stabilize.
          (seesaw/invoke-now  ; Finally, start trying to go back online, unless/until the user decides to give up.
           (.setSelected (online-menu-item) true)))))))

(declare go-offline)

(defonce ^{:private true
           :doc "Used to detect the `VirtualCdj` shutting down
           unexpectedly due to network problems, so we can
           recognize that we are offline and try to recover."}
  vcdj-lifecycle-listener
  (reify LifecycleListener
    (started [_this _]) ; Nothing to do.
    (stopped [_this _]
      (future
        (go-offline true) ; Indicate this is a special case of going offline even though VirtualCdj is offline already.
        (seesaw/invoke-now
         ;; Update the Online menu state, which calls `go-offline` again but that is a no-op this time.
         (.setSelected (online-menu-item) false))
        (Thread/sleep offline-cooldown-ms)  ; Give things a chance to stabilize.
        (seesaw/invoke-now  ; Finally, start trying to go back online, unless/until the user decides to give up.
         (.setSelected (online-menu-item) true))))))

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
  (let [extension (util/extension-for-file-type :trigger-export)]
    (when-let [file (chooser/choose-file
                     @trigger-frame
                     :all-files? false
                     :filters [["Trigger Export files" [extension]]
                               (chooser/file-filter "All files" (constantly true))])]
      (try
        (cleanup-trigger true trigger)
        (let [m (prefs/read-file :beat-link-trigger-export file)]
          (load-trigger-from-map trigger (translate-custom-enabled (:item m))))
        (catch Exception e
          (timbre/error e "Problem importing" file)
          (seesaw/alert (str "<html>Unable to Import.<br><br>" e)
                        :title "Problem Importing Trigger" :type :error)))
      (check-for-parse-error))))

(defn- recreate-trigger-rows
  "Reads the preferences and recreates any trigger rows that were
  specified in them. If none were found, returns a single, default
  trigger. Also updates the global setup and shutdown expressions,
  running them as needed, and sets the default track description."
  []
  (let [m (prefs/get-preferences)]
    (.doClick ^JRadioButtonMenuItem (seesaw/select @trigger-frame [(if (:tracks-using-playlists? m)
                                                                     :#track-position :#track-id)]))
    (.setSelected ^JMenuItem (seesaw/select @trigger-frame [:#send-status]) (true? (:send-status? m)))
    (when-let [exprs (:expressions m)]
      (swap! trigger-prefs assoc :expressions exprs)
      (doseq [[kind expr] (editors/sort-setup-to-front exprs)]
        (let [editor-info (get editors/global-trigger-editors kind)]
          (try
            (swap! trigger-prefs assoc-in [:expression-fns kind]
                   (if (= kind :shared)
                     (expressions/define-shared-functions expr (editors/triggers-editor-title kind nil true))
                     (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                        (editors/triggers-editor-title kind nil true)
                                                        (:no-locals? editor-info))))
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
        (vec (map-indexed (fn [index trigger]
                            (create-trigger-row (translate-custom-enabled trigger) (inc index)))
                          triggers))
        [(create-trigger-row)]))))

(defn build-global-editor-action
  "Creates an action which edits one of the global expressions."
  [kind]
  (seesaw/action :handler (fn [_] (editors/show-trigger-editor kind (seesaw/config @trigger-frame :content)
                                                               (fn []
                                                                 (when (= :setup kind)
                                                                   (run-global-function :shutdown)
                                                                   (reset! expression-globals {})
                                                                   (run-global-function :setup))
                                                                 (update-global-expression-icons))))
                 :name (str "Edit " (get-in editors/global-trigger-editors [kind :title]))
                 :tip (get-in editors/global-trigger-editors [kind :tip])
                 :icon (seesaw/icon (if (empty? (get-in @trigger-prefs [:expressions kind]))
                                       "images/Gear-outline.png"
                                       "images/Gear-icon.png"))))

(defn- show-player-status-handler
  "Try to show the player status window, giving the user appropriate
  feedback if the current environment is not appropriate, or even not
  ideal. A Seesaw event handler, but we ignore the event argument."
  [_]
  (if (.isRunning virtual-cdj)
    (players/show-window @trigger-frame expression-globals)
    (seesaw/alert "Must be Online to show Player Status window."
                  :title "Beat Link Trigger is Offline" :type :error)))

(defonce ^{:private true
           :doc "The menu action which opens the Player Status window."}
  player-status-action
  (delay (seesaw/action :handler show-player-status-handler
                        :name "Show Player Status"
                        :key "menu P"
                        :enabled? false)))

(defn show-player-status
  "Try to show the player status window, giving the user appropriate
  feedback if the current environment is not appropriate, or even not
  ideal. Ensures the use of the Event Dispatch Thread so that UI
  elements can be created safely, and does nothing if called before
  the Triggers window has been created."
  []
  (when @trigger-frame
    (seesaw/invoke-later (show-player-status-handler nil))))

(defonce ^{:private true
           :doc "The menu action which opens the Playlist Writer window."}
  playlist-writer-action
  (delay (seesaw/action :handler (fn [_]
                                   (if (.isRunning virtual-cdj)
                                     (writer/show-window @trigger-frame)
                                     (seesaw/alert "Must be Online to show Playlist Writer window."
                                                   :title "Beat Link Trigger is Offline" :type :error)))
                        :name "Write Playlist" :enabled? false)))

(defonce ^{:private true
           :doc "The action which opens the OBS overlay web server window."}
  overlay-server-action
  (delay (seesaw/action :handler (fn [_]
                                   (overlay/show-window @trigger-frame))
                        :name "OBS Overlay Web Server" :enabled? true)))

(defonce ^{:private true
           :doc "The menu action which opens a shallow playback simulator."}
  open-simulator-item
  (delay (let [item (seesaw/menu-item :visible? (not (util/online?)))]
           (seesaw/config! item :action (sim/build-simulator-action item))
           item)))

(defn- actively-send-status
  "Try to start sending status update packets if we are online and are
  using a valid player number. If we are not using a valid player
  number, tell the user why this can't be done.

  When we are sending status update packets, we are also able to
  actively request metadata of all types from the dbserver instances
  running on the other players, so we can request things like CD-Text
  based information that Crate Digger can't obtain."
  []
  (when (util/online?)
    (if (> (.getDeviceNumber virtual-cdj) 4)
      (let [players (count (util/visible-player-numbers))
            options (to-array ["Cancel" "Go Offline"])
            message (str "Beat Link Trigger is using device number " (.getDeviceNumber virtual-cdj)
                         ".\nTo act like a real player, it needs to use number 1, 2, 3, or 4.\n\n"
                         (if (< players 4)
                           (str "Since there are fewer than 4 CDJs on the network, all you need to do is\n"
                                "go offline and then back online, and it will be able to use one of the\n"
                                "unused device numbers, which will work great.\n\n")

                           (str "Please go offline, turn off one of the four CDJs currently on the network,\n"
                                "then go back online, which will let us use that player's device number.\n\n")))
            choice (seesaw/invoke-now
                    (javax.swing.JOptionPane/showOptionDialog
                     nil message "Need to Change Device Number"
                     javax.swing.JOptionPane/YES_NO_OPTION javax.swing.JOptionPane/ERROR_MESSAGE nil
                     options (aget options (dec (count options)))))]
        (if (zero? choice)
          (.setSelected ^JMenuItem (seesaw/select @trigger-frame [:#send-status]) false)  ; Cancel.
          (.setSelected (online-menu-item) false)))     ; Go offline.
      (do (.setSendingStatus virtual-cdj true)  ; We can do it.
          (.setPassive metadata-finder false)))))

(declare go-online)

(defn- online-menu-name
  "Expands the content of the Online? menu option to show the current
  player number if we are online."
  []
  (str "Online?"
       (when (util/online?)
         (str "  [We are Player " (.getDeviceNumber virtual-cdj) "]"))))

(defn- build-trigger-menubar
  "Creates the menu bar for the trigger window."
  []
  (let [inspect-action   (seesaw/action :handler (fn [_] (try
                                                           (inspector/inspect @expression-globals
                                                                              :window-name "Trigger Expression Globals")
                                                           (catch StackOverflowError _
                                                            (util/inspect-overflowed))
                                                          (catch Throwable t
                                                            (util/inspect-failed t))))
                                        :name "Inspect Expression Globals"
                                        :tip "Examine any values set as globals by any Trigger Expressions.")
        new-show-action  (seesaw/action :handler (fn [_] (show/new @trigger-frame))
                                        :name "New Show"
                                        :tip "Create an interface for conveniently assigning cues to tracks."
                                        :key "menu N")
        open-show-action (seesaw/action :handler (fn [_] (show/open @trigger-frame))
                                        :name "Open Show"
                                        :tip "Opens an already-created show interface."
                                        :key "menu O")
        using-playlists? (:tracks-using-playlists? @trigger-prefs)
        online-item      (seesaw/checkbox-menu-item :text (online-menu-name) :id :online :selected? (util/online?))
        real-item        (seesaw/checkbox-menu-item :text "Use Real Player Number?" :id :send-status
                                                    :selected? (real-player?))
        bg               (seesaw/button-group)
        track-submenu    (seesaw/menu :text "Default Track Description"
                                      :items [(seesaw/radio-menu-item :text "recordbox id [player:slot]" :id :track-id
                                                                      :selected? (not using-playlists?) :group bg)
                                              (seesaw/radio-menu-item :text "playlist position" :id :track-position
                                                                      :selected? using-playlists? :group bg)])]
    (seesaw/listen bg :selection
                   (fn [_]
                     (when-let [s (seesaw/selection bg)]
                       (swap! trigger-prefs assoc :tracks-using-playlists? (= (seesaw/id-of s) :track-position)))))
    (seesaw/listen online-item :item-state-changed
                   (fn [^java.awt.event.ItemEvent e]
                     (if (= (.getStateChange e) java.awt.event.ItemEvent/SELECTED)
                       (go-online)
                       (go-offline))))
    (seesaw/listen real-item :item-state-changed
                   (fn [^java.awt.event.ItemEvent e]
                     (swap! trigger-prefs assoc :send-status? (= (.getStateChange e)
                                                                 java.awt.event.ItemEvent/SELECTED))
                     (if (real-player?)
                       (actively-send-status)
                       (do
                         (carabiner/cancel-full-sync)
                         (.setSendingStatus virtual-cdj false)))))
    (seesaw/menubar :items [(seesaw/menu :text "File"
                                         :items (concat [@save-action @save-as-action @load-action
                                                         (seesaw/separator) new-show-action open-show-action
                                                         (seesaw/separator) @playlist-writer-action]
                                                        (menus/non-mac-file-actions quit)))
                            (seesaw/menu :text "Triggers"
                                         :items (concat [@new-trigger-action (seesaw/separator)]
                                                        (map build-global-editor-action (keys editors/global-trigger-editors))
                                                        [(seesaw/separator)
                                                         track-submenu inspect-action
                                                         (seesaw/separator) @clear-triggers-action])
                                         :id :triggers-menu)

                            (seesaw/menu :text "Network"
                                         :items [online-item real-item
                                                 (seesaw/separator)
                                                 @open-simulator-item
                                                 @player-status-action @load-track-action @load-settings-action
                                                 (seesaw/separator)
                                                 @carabiner-action @overlay-server-action @nrepl-action]
                                         :id :network-menu)
                            (menus/build-help-menu)])))

(defn update-global-expression-icons
  "Updates the icons next to expressions in the Trigger menu to
  reflect whether they have been assigned a non-empty value."
  []
  (let [^JMenu menu (seesaw/select @trigger-frame [:#triggers-menu])
        exprs       {"Edit Shared Functions"           :shared
                     "Edit Global Setup Expression"    :setup
                     "Edit Came Online Expression"     :online
                     "Edit Going Offline Expression"   :offline
                     "Edit Global Shutdown Expression" :shutdown}]
    (doseq [i (range (.getItemCount menu))]
      (let [^JMenuItem item (.getItem menu i)]
        (when item
          (when-let [expr (get exprs (.getText item))]
            (.setIcon item (seesaw/icon (if (empty? (get-in @trigger-prefs [:expressions expr]))
                                                                 "images/Gear-outline.png"
                                                                 "images/Gear-icon.png")))))))))

(defn- create-trigger-window
  "Create and show the trigger window."
  []
  (try
    (let [root (seesaw/frame :title "Beat Link Triggers" :on-close :nothing
                             :menubar (build-trigger-menubar))
          triggers (seesaw/vertical-panel :id :triggers)
          panel (seesaw/scrollable triggers :user-data trigger-prefs)]
      (seesaw/config! root :content panel)
      (reset! trigger-frame root)
      (seesaw/config! triggers :items (recreate-trigger-rows))
      (adjust-triggers)
      (util/restore-window-position root :triggers nil)
      (seesaw/show! root)
      (check-for-parse-error)
      (seesaw/listen root
                     :window-closing
                     (fn [_]
                       (save-triggers-to-preferences)
                       (if (and (show/close-all-shows false)
                                (delete-all-triggers false))
                         (do
                           (writer/close-window)
                           (when (beat-carabiner/active?)
                             (beat-carabiner/disconnect)
                             (Thread/sleep 250))  ; Give any spawned daemon time to exit gracefully.
                           (menus/respond-to-quit-request true)  ; In case it came from the OS
                           (System/exit 0))
                         (menus/respond-to-quit-request false)))

                     #{:component-moved :component-resized}
                     (fn [_] (util/save-window-position root :triggers))))
    (catch Exception e
      (timbre/error e "Problem creating Trigger window."))))

(defn- reflect-online-state
  "Updates the File and Network menus so they are appropriate for
  whether we are currently online or not. If online, we show the
  player number in the `Online?` option, and enable the options which
  require that state (but we hide the Simulator option). Otherwise we
  disable the online-dependent options and show the Simulator option."
  []
  (seesaw/invoke-soon
   (try
     (seesaw/config! [@playlist-writer-action @load-track-action @load-settings-action @player-status-action]
                     :enabled? (util/online?))
     (.setText (online-menu-item) (online-menu-name))
     (if (util/online?)
       (seesaw/hide! @open-simulator-item)
       (seesaw/show! @open-simulator-item))
     (catch Throwable t
       (timbre/error t "Problem updating interface to reflect online state")))))

(defn- start-other-finders
  "Starts up the full complement of metadata-related finders that we
  use. Also updates the Online menu item to show our player number."
  []
  (.start metadata-finder)
  (.start (CrateDigger/getInstance))
  (.start (SignatureFinder/getInstance))
  (.start (ArtFinder/getInstance))
  (.start (BeatGridFinder/getInstance))
  (.start (TimeFinder/getInstance))
  (.setFindDetails (WaveformFinder/getInstance) true)
  (.start (WaveformFinder/getInstance))
  (.start (AnalysisTagFinder/getInstance))
  (reflect-online-state))

(defn start
  "Create the Triggers window, and register all the notification
  handlers it needs in order to stay up to date with events on the
  MIDI and DJ Link networks. When we are supposed to go online, try
  doing so, and run any custom Came Online expressions. If the window
  already exists, just bring it to the front, to support returning to
  online operation. Returns truthy if the window was created for the
  first time."
  []
  (let [already-created @trigger-frame]
    (if @trigger-frame
      (do
        (rebuild-all-device-status)
        (seesaw/show! @trigger-frame))
      (do
        ;; Request notifications when MIDI devices appear or vanish
        (CoreMidiDeviceProvider/addNotificationListener
         (reify uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification
           (midiSystemUpdated [_this]
             (midi-environment-changed))))

        ;; Open the trigger window
        (create-trigger-window)

        ;; Be able to react to players coming and going
        (.addDeviceAnnouncementListener device-finder device-listener)
        (.addUpdateListener virtual-cdj status-listener)
        (rebuild-all-device-status)  ; In case any came or went while we were setting up the listener
        (.addBeatListener (BeatFinder/getInstance) beat-listener)))  ; Allow triggers to respond to beats
    (try
      (.start (BeatFinder/getInstance))
      (catch java.net.BindException e
        (timbre/error e "Unable to start Beat Finder, is rekordbox or another instance running? Staying offline.")
        (seesaw/invoke-now
         (seesaw/alert @trigger-frame
                       (str "<html>Unable to listen for beat packets, socket is in use.<br>"
                            "Is rekordbox or another DJ Link program running?")
                       :title "Failed to Go Online" :type :error)
         (.stop virtual-cdj)
         (.setSelected (online-menu-item) false)))
      (catch Throwable t
        (timbre/error t "Problem starting Beat Finder, staying offline.")
        (seesaw/invoke-now
         (seesaw/alert @trigger-frame
                       (str "<html>Unable to listen for beat packets, check the log file for details.<br><br>" t)
                       :title "Problem Trying to Go Online" :type :error)
         (.stop virtual-cdj)
         (.setSelected (online-menu-item) false))))
    (.setPassive metadata-finder true)  ; Start out conservatively
    (when (util/online?)
      (start-other-finders)
      (.addLifecycleListener virtual-cdj vcdj-lifecycle-listener))  ; React when VirtualCdj shuts down unexpectedly.
    (when (real-player?) (actively-send-status))
    (when (util/online?)
      (run-global-function :online)
      (show/run-show-online-expressions))

    (not already-created)))  ; Indicate whether this was the first creation of the Triggers window

(defn go-offline
  "Transition to an offline state, running any custom Going Offline
  expression and then updating the UI appropriately. If `was-online?`
  is passed, it reports whether we had been online. In normal
  circumstances, this does not need to be passed, and our current
  online state is checked. However, in the special case of reacting to
  the `VirtualCdj` unexpectedly shutting itself down due to network
  problems, we want to be able to run expressions and do proper
  cleanup."
  ([]
   (go-offline (util/online?)))
  ([was-online?]
   (.removeLifecycleListener virtual-cdj vcdj-lifecycle-listener)  ; No longer care when it stops.
   (when was-online?  ; Don't do all this if we got here from a failed attempt to go online.
     (show/run-show-offline-expressions)
     (run-global-function :offline)
     (.stop (WaveformFinder/getInstance))
     (.stop (BeatGridFinder/getInstance))
     (.stop (ArtFinder/getInstance))
     (.stop metadata-finder)
     (.stop (BeatFinder/getInstance))
     (.stop (org.deepsymmetry.beatlink.dbserver.ConnectionManager/getInstance))
     (.stop virtual-cdj)
     (Thread/sleep 200))  ; Wait for straggling update packets
   (reflect-online-state)
   (rebuild-all-device-status)))

(defn go-online
  "Try to transition to an online state, updating the UI appropriately."
  []
  (future
    (seesaw/invoke-now
     (seesaw/hide! @trigger-frame)
     (sim/close-all-simulators))
    ((resolve 'beat-link-trigger.core/try-going-online))
    (when-not (util/online?)
      (seesaw/invoke-now  ; We failed to go online, so update the menu to reflect that.
       (.setSelected (online-menu-item) false)))))
