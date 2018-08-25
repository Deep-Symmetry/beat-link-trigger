(ns beat-link-trigger.carabiner
  "Communicates with a local Carabiner daemon to participate in an
  Ableton Link session."
  (:require [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.net Socket]
           [java.awt.event ItemEvent]
           [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncement DeviceAnnouncementListener LifecycleListener
            VirtualCdj DeviceUpdate CdjStatus MixerStatus MasterListener]))

(defonce ^{:private true
           :doc "When connected, holds the socket used to communicate
  with Carabiner, values which track the peer count and tempo reported
  by the Ableton Link session and the target tempo we are trying to
  maintain (when applicable), and the `:running` flag which can be
  used to gracefully terminate that thread. (The `:last` entry is used
  to assign unique integers to each `:running` value as we are started
  and stopped, so a leftover background thread from a previous run can
  know when it is stale and should exit.)"}
  client (atom {:port 17000
                :latency 20
                :last 0}))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to configure and
  control the connection to the Carabiner daemon."}
  carabiner-window (atom nil))

(def device-finder
  "The object that tracks the arrival and departure of devices on the
  DJ Link network."
  (DeviceFinder/getInstance))

(def virtual-cdj
  "The object which can obtained detailed player status information."
  (VirtualCdj/getInstance))

(def bpm-tolerance
  "The amount by which the Link tempo can differ from our target tempo
  without triggering an adjustment."
  0.00001)

(defn active?
  "Checks whether there is currently an active connection to a
  Carabiner daemon."
  []
  (:running @client))

(defn sync-enabled?
  "Checks whether we have an active connection and are in any Sync Mode
  other than Off."
  []
  (let [state @client]
    (and (:running state)
         (not= :off (:sync-mode state)))))

(defn sync-triggers?
  "Checks whether we have an active connection for which Link triggers
  are controlling the tempo."
  []
  (let [state @client]
    (and (:running state)
         (= :triggers (:sync-mode state)))))

(defn sync-full?
  "Checks whether we have an active connection and are configured for
  bidirectional synchronization between Ableton Link and Pioneer Pro
  DJ Link."
  []
  (let [state @client]
    (and (:running state)
         (= :full (:sync-mode state)))))

(defn cancel-full-sync
  "If we are configured to be fully synced, fall back to passive sync.
  This is called when the user turns off status packets, since they
  are required for full sync to work."
  []
  (when-let [frame @carabiner-window]
    (seesaw/invoke-later
     (let [sync-mode (seesaw/select frame [:#sync-mode])]
       (when (= (seesaw/value sync-mode) "Full")
         (seesaw/value! sync-mode "Passive"))))))

(defn- ensure-active
  "Throws an exception if there is no active connection."
  []
  (when-not (active?)
    (throw (IllegalStateException. "No active Carabiner connection."))))

(defn- send-message
  "Sends a message to the active Carabiner daemon."
  [message]
  (ensure-active)
  (.write (.getOutputStream (:socket @client)) (.getBytes (str message) "UTF-8")))

(defn- check-link-tempo
  "If we are supposed to master the Link tempo, make sure the Link
  tempo is close enough to our target value, and adjust it if needed."
  []
  (let [state @client]
    (when (and (some? (:target-bpm state))
               (> (Math/abs (- (:link-bpm state 0.0) (:target-bpm state))) bpm-tolerance))
      (send-message (str "bpm " (:target-bpm state))))))

(defn- update-target-tempo
  "Displays the current target BPM value, if any."
  []
  (when-let [frame @carabiner-window]
    (seesaw/invoke-later
     (seesaw/value! (seesaw/select frame [:#target])
                    (if-some [target (:target-bpm @client)]
                      (format "%.2f" target)
                      "---"))
     (seesaw/repaint! (seesaw/select frame [:#state])))))

(defn- update-connected-status
  "Make the state of the window reflect the current state of our
  connection to the Carabiner daemon."
  []
  (when-let [frame @carabiner-window]
    (seesaw/invoke-later
     (let [connected (boolean (active?))]
       (seesaw/config! (seesaw/select frame [:#sync-mode]) :enabled? connected)
       (seesaw/config! (seesaw/select frame [:#port]) :enabled? (not connected))
       (seesaw/value! (seesaw/select frame [:#connect]) connected)
       (seesaw/repaint! (seesaw/select frame [:#state]))))))

(defn- update-link-status
  "Make the state of the window reflect the current state of the Link
  session."
  []
  (when-let [frame @carabiner-window]
    (seesaw/invoke-later
     (let [state @client]
       (seesaw/value! (seesaw/select frame [:#bpm]) (if-some [bpm (:link-bpm state)]
                                                      (format "%.2f" bpm)
                                                      "---"))
       (seesaw/value! (seesaw/select frame [:#peers]) (if-some [peers (:link-peers state)]
                                                        (str peers)
                                                        "---"))))))

(defn- handle-status
  "Processes a status update from Carabiner."
  [status]
  (let [bpm (double (:bpm status))
        peers (int (:peers status))]
    (swap! client assoc :link-bpm bpm :link-peers peers)
    (update-link-status))
  (check-link-tempo))

(def skew-tolerance
  "The amount by which the start of a beat can be off without
  triggering an adjustment. This can't be larger than the normal beat
  packet jitter without causing spurious readjustments."
  0.0166)

(def connect-timeout
  "How long the connection attempt to the Carabiner daemon can take
  before we give up on being able to reach it."
  5000)

(def read-timeout
  "How long reads from the Carabiner daemon should block so we can
  periodically check if we have been instructed to close the
  connection."
  2000)

(defn- handle-beat-at-time
  "Processes a beat probe response from Carabiner."
  [info]
  (let [raw-beat (Math/round (:beat info))
        beat-skew (mod (:beat info) 1.0)
        [time beat-number] (:beat @client)
        candidate-beat (if (and beat-number (= time (:when info)))
                         (let [bar-skew (- (dec beat-number) (mod raw-beat 4))
                               adjustment (if (<= bar-skew -2) (+ bar-skew 4) bar-skew)]
                           (+ raw-beat adjustment))
                         raw-beat)
        target-beat (if (neg? candidate-beat) (+ candidate-beat 4) candidate-beat)]
    (when (or (> (Math/abs beat-skew) skew-tolerance)
              (not= target-beat raw-beat))
      (timbre/info "Realigning to beat" target-beat "by" beat-skew)
      (send-message (str "force-beat-at-time " target-beat " " (:when info) " 4.0")))))

(defn- response-handler
  "A loop that reads messages from Carabiner as long as it is supposed
  to be running, and takes appropriate action."
  [socket running]
  (try
    (let [buffer (byte-array 1024)
          input (.getInputStream socket)]
      (while (and (= running (:running @client)) (not (.isClosed socket)))
        (try
          (let [n (.read input buffer)]
            (if (and (pos? n) (= running (:running @client)))  ; We got data, and were not shut down while reading
              (let [message (String. buffer 0 n "UTF-8")
                    reader (java.io.PushbackReader. (clojure.java.io/reader (.getBytes message "UTF-8")))
                    cmd (clojure.edn/read reader)]
                (timbre/debug "Received:" message)
                (case cmd
                  status (handle-status (clojure.edn/read reader))
                  beat-at-time (handle-beat-at-time (clojure.edn/read reader))
                  (timbre/error "Unrecognized message from Carabiner:" message)))
              (do  ; We read zero, means the other side closed; force our loop to terminate.
                (future
                  (seesaw/invoke-later
                   (javax.swing.JOptionPane/showMessageDialog
                    @carabiner-window
                    "Carabiner unexpectedly closed our connection; is it still running?"
                    "Carabiner Connection Closed"
                    javax.swing.JOptionPane/WARNING_MESSAGE)))
                (.close socket))))
          (catch java.net.SocketTimeoutException e
            (timbre/debug "Read from Carabiner timed out, checking if we should exit loop."))
          (catch Exception e
            (timbre/error e "Problem reading from Carabiner.")))))
    (timbre/info "Ending read loop from Carabiner.")
    (swap! client (fn [oldval]
                    (if (= running (:running oldval))
                      (dissoc oldval :running :socket :link-bpm :link-peers)  ; We are causing the ending.
                      oldval)))  ; Someone else caused the ending, so leave client alone; may be new connection.
    (.close socket)  ; Either way, close the socket we had been using to communicate, and update the window state.
    (seesaw/value! (seesaw/select @carabiner-window [:#sync-mode]) "Off")
    (update-connected-status)
    (update-link-status)
    (catch Exception e
      (timbre/error e "Problem managing Carabiner read loop."))))

(defn disconnect
  "Shut down any active Carabiner connection. The run loop will notice
  that its run ID is no longer current, and gracefully terminate,
  closing its socket without processing any more responses."
  []
  (swap! client dissoc :running :socket :link-bpm :link-peers)
  (seesaw/value! (seesaw/select @carabiner-window [:#sync-mode]) "Off")
  (update-connected-status)
  (update-link-status))

(defn connect
  "Try to establish a connection to Carabiner. Returns truthy if the
  initial open succeeded. Sets up a background thread to reject the
  connection if we have not received an initial status report from the
  Carabiner daemon within a second of opening it."
  []
  (swap! client (fn [oldval]
                  (if (:running oldval)
                    oldval
                    (try
                      (let [socket (java.net.Socket.)
                            running (inc (:last oldval))]
                        (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (:port oldval)) connect-timeout)
                        (.setSoTimeout socket read-timeout)
                        (future (response-handler socket running))
                        (merge oldval {:running running
                                       :last running
                                       :socket socket}))
                      (catch Exception e
                        (timbre/warn e "Unable to connect to Carabiner")
                        (future
                          (seesaw/invoke-later
                           (javax.swing.JOptionPane/showMessageDialog
                            @carabiner-window
                            "Unable to connect to Carabiner; make sure it is running on the specified port."
                            "Carabiner Connection failed"
                            javax.swing.JOptionPane/WARNING_MESSAGE)))
                        oldval)))))
  (future (update-connected-status))
  (when (active?)
    (future
      (Thread/sleep 1000)
      (when-not (:link-bpm @client)
        (timbre/warn "Did not receive inital status packet from Carabiner daemon; disconnecting.")
        (seesaw/invoke-later
         (javax.swing.JOptionPane/showMessageDialog
          @carabiner-window
          "Did not receive expected response from Carabiner; is something else running on the specified port?"
          "Carabiner Connection Rejected"
          javax.swing.JOptionPane/WARNING_MESSAGE)
         (disconnect)))))
  (active?))

(defn valid-tempo?
  "Checks whether a tempo request is a reasonable number of beats per
  minute. Link supports the range 20 to 999 BPM. If you want something
  outside that range, pick the closest multiple or fraction; for
  example for 15 BPM, propose 30 BPM."
  [bpm]
  (< 20.0 bpm 999.0))

(defn- validate-tempo
  "Makes sure a tempo request is a reasonable number of beats per
  minute. Coerces it to a double value if it is in the legal Link
  range, otherwise throws an exception."
  [bpm]
  (if (valid-tempo? bpm)
    (double bpm)
    (throw (IllegalArgumentException. "Tempo must be between 20 and 999 BPM"))))

(defn lock-tempo
  "Starts holding the tempo of the Link session to the specified
  number of beats per minute."
  [bpm]
  (swap! client assoc :target-bpm (validate-tempo bpm))
  (update-target-tempo)
  (check-link-tempo)
  (when (= :triggers (:sync-mode @client))
    (seesaw/invoke-later
     (seesaw/value! (seesaw/select @carabiner-window [:#sync-link]) true))))

(defn unlock-tempo
  "Allow the tempo of the Link session to be controlled by other
  participants."
  []
  (swap! client dissoc :target-bpm)
  (update-target-tempo)
  (when (= :triggers (:sync-mode @client))
    (seesaw/invoke-later
     (seesaw/value! (seesaw/select @carabiner-window [:#sync-link]) false))))

(defn beat-at-time
  "Find out what beat falls at the specified time in the Link
  timeline, assuming 4 beats per bar since we are dealing with Pro DJ
  Link, and taking into account the configured latency. When the
  response comes, if we are configured to be the tempo master, nudge
  the Link timeline so that it had a beat at the same time. If a
  beat-number (ranging from 1 to the quantum) is supplied, move the
  timeline by more than a beat if necessary in order to get the Link
  session's bars aligned as well."
  ([time]
   (beat-at-time time nil))
  ([time beat-number]
   (ensure-active)
   (let [adjusted-time (- time (* (:latency @client) 1000))]
     (swap! client assoc :beat [adjusted-time beat-number])
     (send-message (str "beat-at-time " adjusted-time " 4.0"))
     (when (= :triggers (:sync-mode @client))  ; Update Align at bar level checkbox when driven by trigger
       (seesaw/invoke-later
        (seesaw/value! (seesaw/select @carabiner-window [:#bar]) (some? beat-number))))
     (seesaw/invoke-later))))

(defn- make-window-visible
  "Ensures that the Carabiner window is in front, and shown."
  []
  (let [our-frame @carabiner-window]
    (seesaw/show! our-frame)
    (.toFront our-frame)))

(defn- connect-choice
  "Respond to the user changing the state of the Connect checkbox."
  [checked]
  (if checked
    (connect)
    (disconnect)))

(defn paint-state
  "Draws a representation of the sync state, including both whether it
  is enabled (connected to Carabiner and set to a Sync Mode other than
  Off) and whether any Link-mode trigger has tripped (in Triggers
  mode), or the Ableton Link Sync or Master buttons are chosen in
  Passive and Full modes."
  [c g]
  (let [w (double (seesaw/width c))
        h (double (seesaw/height c))
        outline (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        enabled? (sync-enabled?)
        tripped? (let [state @client]
                   (or (some? (:target-bpm state))
                       (and (= :full (:sync-mode state)) (.isTempoMaster virtual-cdj))))]
    (.setRenderingHint g java.awt.RenderingHints/KEY_ANTIALIASING java.awt.RenderingHints/VALUE_ANTIALIAS_ON)

    (when tripped?
      (if enabled?
        (do  ; Draw the inner filled circle showing sync is actively taking place.
          (.setPaint g java.awt.Color/green)
          (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))
        (do  ; Draw the inner gray circle showing sync would be active if we were connected and in Triggers mode.
          (.setPaint g java.awt.Color/lightGray)
          (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))))

    ;; Draw the outer circle that reflects the enabled state
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if enabled? java.awt.Color/green java.awt.Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(defn- sending-status?
  "Checks whether we are currently sending status packets, which is
  required to set the sync mode to full."
  []
  ((resolve 'beat-link-trigger.triggers/send-status?)))

(defn- report-online-requirement
  "Displays an error explaining that we must be online in order to
  enable any sync."
  [parent]
  (seesaw/alert parent (str "Must be Online to enable any Sync Mode.\n"
                            "Please go Online using the Network menu.")
                :title "Beat Link Trigger isn't Online" :type :error))

(defn- report-status-requirement
  "Displays an error explaining that status updates must be sent in
  order to enable full sync."
  [parent]
  (seesaw/alert parent (str "Must be Sending Status Packets to set Sync Mode to Full.\n"
                            "Please enable them using the Network menu.")
                :title "Beat Link Trigger isn't sending Status Packets" :type :error))

(defn- enable-pioneer-sync-controls
  "Updates the Pioneer device sync/master control buttons to reflect
  whether we are currently online, which controls whether they are
  functional."
  [enabled]
  (doseq [i [1 2 3 4 33]]
    (seesaw/config! (seesaw/select @carabiner-window [(keyword (str "#sync-" i))]) :enabled? enabled)
    (seesaw/config! (seesaw/select @carabiner-window [(keyword (str "#master-" i))]) :enabled? enabled)))

(def ^:private virtual-cdj-lifecycle-listener
  "Responds to the Virtual CDJ starting or stopping so we can enable or
  disable the sync and master buttons appropriately."
  (reify LifecycleListener
    (started [this sender]
      (enable-pioneer-sync-controls true))
    (stopped [this sender]
      (enable-pioneer-sync-controls false)
      (seesaw/value! (seesaw/select @carabiner-window [:#sync-mode]) "Off"))))

(defn- update-device-visibility
  "Shows or hides the sync control row corresponding to a device, given its number."
  [device visible?]
  (when (#{1 2 3 4 33} device)
    (letfn [(update-elem [prefix]
              (let [target [(keyword (str "#" prefix "-" device))]]
                (seesaw/visible! (seesaw/select @carabiner-window target) visible?)))]
      (seesaw/invoke-soon
       (doseq [elem ["player" "sync" "master"]]
         (update-elem elem))
       (seesaw/pack! @carabiner-window)))))

(defn- start-sync-state-updates
  "Creates and starts the thread which updates the Sync and Master UI to
  reflect changes initiated on the devices themselves."
  [frame]
  (future
    (loop []
      (try
        (seesaw/invoke-later
         ;; First update the states of the actual device rows
         (doseq [status (.getLatestStatus virtual-cdj)]
           (let [device        (.getDeviceNumber status)
                 master-button (seesaw/select frame [(keyword (str "#master-" device))])
                 sync-box      (seesaw/select frame [(keyword (str "#sync-" device))])]
             (when  (and (.isTempoMaster status) (not (seesaw/value master-button)))
               (let [changed (:master-command-sent @client)]
                 (when (or (nil? changed) (> (- (System/currentTimeMillis) changed) 250))
                   (seesaw/value! master-button true))))
             (when (not= (seesaw/value sync-box) (.isSynced status))
               (let [changed (get-in @client [:sync-command-sent (long device)])]
                 (when (or (nil? changed) (> (- (System/currentTimeMillis) changed) 250))
                   (seesaw/value! sync-box (.isSynced status))))))))
        ;; Then update the state of the Ableton Link (Virtual CDJ) row
        (let [master-button (seesaw/select frame [:#master-link])
              sync-box   (seesaw/select frame [:#sync-link])]
          (when  (and (.isTempoMaster virtual-cdj) (not (seesaw/value master-button)))
               (let [changed (:master-command-sent @client)]
                 (when (or (nil? changed) (> (- (System/currentTimeMillis) changed) 250))
                   (seesaw/value! master-button true))))
             (when (not= (seesaw/value sync-box) (.isSynced virtual-cdj))
               (let [changed (get-in @client [:sync-command-sent (long (.getDeviceNumber virtual-cdj))])]
                 (when (or (nil? changed) (> (- (System/currentTimeMillis) changed) 250))
                   (seesaw/value! sync-box (.isSynced virtual-cdj))))))

        (Thread/sleep 100)
        (catch Exception e
          (timbre/warn e "Problem updating Carabiner device Sync/Master button states.")))
      (recur))))

(defn- sync-box-changed
  "Called when one of the device Sync checkboxes has been toggled. Makes
  sure the corresponding device's sync state is in agreement (we may
  not need to do anything, because our state change may be in response
  to a report from the device itself.)"
  [^ItemEvent event device]
  (let [selected (= (.getStateChange event) ItemEvent/SELECTED)
        enabled  (.isSynced (.getLatestStatusFor virtual-cdj device))]
    (when (not= selected enabled)
      (swap! client assoc-in [:sync-command-sent device] (System/currentTimeMillis))
      (.sendSyncModeCommand virtual-cdj device selected))))

(defn- master-button-changed
  "Called when one of the device Master radio buttons has been toggled. Makes
  sure the corresponding device's Master state is in agreement (we may
  not need to do anything, because our state change may be in response
  to a report from the device itself.)"
  [^ItemEvent event device]
  (when (= (.getStateChange event) ItemEvent/SELECTED)  ; This is the new master
    (when-not (.isTempoMaster (.getLatestStatusFor virtual-cdj device))  ; But it doesn't know it yet
      (swap! client assoc :master-command-sent (System/currentTimeMillis))
      (.appointTempoMaster virtual-cdj device))))

(defn- build-device-sync-rows
  "Creates the GUI elements which allow you to view and manipulate the
  sync and tempo master states of the Pioneer devies found on the
  network. Takes the button group to which the Master radio buttons
  should belong."
  [group]
  (apply concat
         (for [device [1 2 3 4 33]]
           (let [label (if (= device 33) "Mixer:" (str "Player " device " :"))]
             [[(seesaw/label :id (keyword (str "player-" device)) :text label) "align right"]
              [(seesaw/checkbox :id (keyword (str "sync-" device)) :text "Sync"
                                :listen [:item-state-changed (fn [^ItemEvent e]
                                                               (sync-box-changed e device))])]
              [(seesaw/radio :id (keyword (str "master-" device)) :text "Master" :group group
                             :listen [:item-state-changed (fn [^ItemEvent e]
                                                            (master-button-changed e device))]) "wrap"]]))))

(defonce ^{:private true
           :doc "Responds to the coming and going of devices, updating visibility of
  the corresponding sync control elements."}
  device-announcement-listener
  (reify DeviceAnnouncementListener
    (deviceFound [this announcement]
      (update-device-visibility (.getNumber announcement) true))
    (deviceLost [this announcement]
      (update-device-visibility (.getNumber announcement) false))))

(defonce ^{:private true
           :doc "Responds to tempo changes and beat packets from the
  master player when we are controlling the Ableton Link tempo (in
  Passive or Full mode)."}
  master-listener
  (reify MasterListener

    (masterChanged [this update])  ; Nothing we need to do here, we don't care which device is the master.

    (tempoChanged [this tempo]
      (if (valid-tempo? tempo)
        (lock-tempo tempo)
        (unlock-tempo)))

    (newBeat [this beat]
      (try
        (when (and (.isRunning virtual-cdj) (.isTempoMaster beat))
          (beat-at-time (long (/ (.getTimestamp beat) 1000))
                        (when (:bar @client) (.getBeatWithinBar beat))))
        (catch Exception e
          (timbre/error e "Problem responding to beat packet in Carabiner."))))))

(defn- tie-ableton-to-pioneer
  "Start forcing the Ableton Link to follow the tempo and beats (and
  maybe bars) of the Pioneer master player."
  []
  (.addMasterListener virtual-cdj master-listener)
  (.tempoChanged master-listener (.getMasterTempo virtual-cdj)))

(defn- free-ableton-from-pioneer
  "Stop forcing Ableton Link to follow the Pioneer master player."
  []
  (.removeMasterListener virtual-cdj master-listener)
  (unlock-tempo))

(defn- link-sync-state-changed
  "Event handler for when the Link Sync checkbox has changed state.
  Update the Virtual CDJ sync state accordingly if necessary (this may
  be happening in response to a change that started there), and if our
  Sync mode is Passive or Full, unless we are the tempo master, start
  tying the Ableton Link tempo to the Pioneer DJ Link tempo master."
  [synced]
  (when (not= (.isSynced virtual-cdj) synced)
    (swap! client assoc-in [:sync-command-sent (.getDeviceNumber virtual-cdj)] (System/currentTimeMillis))
    (.setSynced virtual-cdj synced))
  (when (and ({:passive :full} (:sync-mode @client))
             (not (.isTempoMaster virtual-cdj)))
    (if synced
      (tie-ableton-to-pioneer)
      (free-ableton-from-pioneer))))

(defn- tie-pioneer-to-ableton
  "Start forcing the Pioneer tempo and beat grid to follow Ableton
  Link."
  []
  (free-ableton-from-pioneer)  ; When we are master, we don't follow anyone else
  (.setTempo virtual-cdj (:link-bpm @client))
  ;; TODO: We need to align the virtual-cdj metronome to the Link timeline.
  (.becomeTempoMaster virtual-cdj)
  ;; TODO: We need to update the tempo and metronome when we get Link updates.
  (.setPlaying virtual-cdj true))

(defn- free-pioneer-from-ableton
  "Stop forcing the Pioneer tempo and beat grid to follow Ableton Link."
  []
  (.setPlaying virtual-cdj false)
  ;; TODO: Is there anything we need to do to stop updating the tempo and metronome when we get Link updates?
  (when (and ({:passive :full} (:sync-mode @client))  ; If we had been synced too, that now matters again.
             (.isSynced virtual-cdj))
    (tie-ableton-to-pioneer)))

(defn- link-master-state-changed
  "Event handler for when the Link Master radio button has changed
  state. Update the Virtual CDJ master state accordingly if
  necessary (this may be happening in response to a change that
  started there), and if our Sync Mode is Full, start or stop tying
  the Pioneer tempo and beat grid to Ableton Link."
  [master]
  (if master
    (do
      (when (= :full (:sync-mode @client))
        (when (not (.isTempoMaster virtual-cdj))
          (swap! client assoc :master-command-sent (System/currentTimeMillis)))
        (tie-pioneer-to-ableton)))
    (free-pioneer-from-ableton)))

(defn- sync-mode-changed
  "Event handler for the Sync Mode selector. Valdiates that the desired
  mode is consistent with the current state, and if so, updates the
  relevant interface elements and sets up the new state."
  [new-mode root]
  (cond
    (and (not= new-mode :off) (not (.isRunning virtual-cdj)))
    (do
      (seesaw/value! (seesaw/select root [:#sync-mode]) "Off")
      (report-online-requirement root))

    (and (= new-mode :full) (not (sending-status?)))
    (do
      (seesaw/value! (seesaw/select root [:#sync-mode]) "Passive")
      (report-status-requirement root))

    :else
    (do
      (seesaw/config! (seesaw/select root [:#sync-link]) :enabled? (#{:passive :full} new-mode))
      (seesaw/config! (seesaw/select root [:#bar]) :enabled? (#{:passive :full} new-mode))
      (seesaw/config! (seesaw/select root [:#master-link]) :enabled? (= :full new-mode))
      (if ({:passive :full} new-mode)
        (do
          (link-sync-state-changed (.isSynced virtual-cdj))  ; This is now relevant, even if it wasn't before.
          (if (and (= :full new-mode) (.isTempoMaster virtual-cdj))
            (tie-pioneer-to-ableton)))
        (do
          (free-ableton-from-pioneer)
          (free-pioneer-from-ableton)))

      (when (= :triggers new-mode)
        (seesaw/value! (seesaw/select root [:#sync-link]) false))  ; Will get set on next update if trigger active

      (swap! client assoc :sync-mode new-mode)
      (seesaw/repaint! (seesaw/select root [:#state]))
      (when (sync-triggers?) (check-link-tempo)))))

(defn- create-window
  "Creates the Carabiner window."
  [trigger-frame]
  (try
    (let [root  (seesaw/frame :title "Carabiner Connection"
                              :on-close :hide)
          group (seesaw/button-group)
          state (seesaw/canvas :id :state :size [18 :by 18] :opaque? false
                               :tip "Sync state: Outer ring shows enabled, inner light when active.")
          panel (mig/mig-panel
                 :constraints ["hidemode 3"]
                 :background "#ccc"
                 :items (concat
                         [[(seesaw/label :text "Carabiner Port:") "align right"]
                          [(seesaw/spinner :id :port
                                           :model (seesaw/spinner-model (:port @client) :from 1 :to 32767)
                                           :listen [:selection (fn [e]
                                                                 (swap! client assoc :port (seesaw/selection e)))])]
                          [(seesaw/checkbox :id :connect :text "Connect"
                                            :listen [:action (fn [e]
                                                               (connect-choice (seesaw/value e)))]) "span 2, wrap"]

                          [(seesaw/label :text "Latency (ms):") "align right"]
                          [(seesaw/spinner :id :latency
                                           :model (seesaw/spinner-model (:latency @client) :from 0 :to 1000)
                                           :listen [:selection (fn [e]
                                                                 (swap! client assoc :latency (seesaw/selection e)))])
                           "wrap"]

                          [(seesaw/label :text "Sync Mode:") "align right"]
                          [(seesaw/combobox :id :sync-mode :model ["Off" "Triggers" "Passive" "Full"] :enabled? false
                                            :listen [:item-state-changed
                                                     (fn [^ItemEvent e]
                                                       (when (= (.getStateChange e) ItemEvent/SELECTED)
                                                         (sync-mode-changed
                                                          (keyword (clojure.string/lower-case (seesaw/value e)))
                                                          root)))])]
                          [state "wrap"]

                          [(seesaw/separator) "growx, span, wrap"]

                          [(seesaw/label :text "Target BPM:") "align right"]
                          [(seesaw/label :id :target :text "---") "wrap"]

                          [(seesaw/label :text "Link BPM:") "align right"]
                          [(seesaw/label :id :bpm :text "---") "wrap"]

                          [(seesaw/label :text "Link Peers:") "align right"]
                          [(seesaw/label :id :peers :text "---") "wrap"]

                          [(seesaw/separator) "growx, span, wrap"]

                          [(seesaw/label :text "Ableton Link:") "align right"]
                          [(seesaw/checkbox :id :sync-link :text "Sync" :enabled? false
                                            :listen [:item-state-changed (fn [^ItemEvent e]
                                                                           (link-sync-state-changed
                                                                            (= (.getStateChange e)
                                                                               ItemEvent/SELECTED)))])]
                          [(seesaw/radio :id :master-link :text "Master" :group group :enabled? false
                                         :listen [:item-state-changed (fn [^ItemEvent e]
                                                                        (condp = (.getStateChange e)
                                                                          ItemEvent/SELECTED
                                                                          (link-master-state-changed true)

                                                                          ItemEvent/DESELECTED
                                                                          (link-master-state-changed false)

                                                                          nil))]) "wrap"]

                          [(seesaw/checkbox :id :bar :text "Align at bar level" :enabled? false
                                            :listen [:item-state-changed (fn [^ItemEvent e]
                                                                           (swap! client assoc :bar
                                                                                  (= (.getStateChange e)
                                                                                     ItemEvent/SELECTED)))])
                           "skip 1, span 2, wrap"]

                          [(seesaw/separator) "growx, span, wrap"]]

                         (build-device-sync-rows group)))]

      ;; Attach the custom paint function to render the graphical trigger state
      (seesaw/config! state :paint paint-state)

      ;; Assemble the window
      (seesaw/config! root :content panel)
      (seesaw/pack! root)
      (.setResizable root false)
      (reset! carabiner-window root)
      (update-connected-status)
      (update-link-status)
      (update-target-tempo)

      (.addLifecycleListener virtual-cdj virtual-cdj-lifecycle-listener)
      (enable-pioneer-sync-controls (.isRunning virtual-cdj))  ; Set proper initial state.
      (.addDeviceAnnouncementListener device-finder device-announcement-listener)
      (doseq [device [1 2 3 4 33]]  ; Set proper initial state
        (update-device-visibility device (and (.isRunning device-finder)
                                              (some? (.getLatestAnnouncementFrom device-finder device)))))
      (start-sync-state-updates root)
      (.setLocationRelativeTo root trigger-frame)
      (make-window-visible))
    (catch Exception e
      (timbre/error e "Problem creating Carabiner window."))))

(defn show-window
  "Make the Carabiner window visible, creating it if necessary."
  [trigger-frame]
  (if @carabiner-window
    (make-window-visible)
    (create-window trigger-frame)))
