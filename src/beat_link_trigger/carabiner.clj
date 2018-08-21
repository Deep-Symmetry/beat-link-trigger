(ns beat-link-trigger.carabiner
  "Communicates with a local Carabiner daemon to participate in an
  Ableton Link session."
  (:require [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.net Socket]
           (org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncement DeviceAnnouncementListener LifecycleListener
                                      VirtualCdj DeviceUpdate CdjStatus MixerStatus)))

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
  "If we are configured to be fully synced (whether or not we have an
  active Carabiner connection), disable sync entirely. This is called
  when the user turns off status packets, since they are required for
  full sync to work."
  []
  (when-let [frame @carabiner-window]
    (seesaw/invoke-later
     (let [sync-mode (seesaw/select frame [:#sync-mode])]
       (when (= (seesaw/value sync-mode) "Full")
         (seesaw/value! sync-mode "Off"))))))

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

(defn- check-tempo
  "If we are supposed to master the Link tempo, make sure the Link
  tempo is close enough to our target value, and adjust it if needed."
  []
  (let [state @client]
    (when (and (= :triggers (:sync-mode state))
               (some? (:target-bpm state))
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
  (check-tempo))

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
    (update-connected-status)
    (catch Exception e
      (timbre/error e "Problem managing Carabiner read loop."))))

(defn disconnect
  "Shut down any active Carabiner connection. The run loop will notice
  that its run ID is no longer current, and gracefully terminate,
  closing its socket without processing any more responses."
  []
  (swap! client dissoc :running :socket :link-bpm :link-peers)
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
  (check-tempo))

(defn unlock-tempo
  "Allow the tempo of the Link session to be controlled by other
  participants."
  []
  (swap! client dissoc :target-bpm)
  (update-target-tempo))

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
     (send-message (str "beat-at-time " adjusted-time " 4.0")))))

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
  is enabled (connected to Carabiner and set to Master) and whether
  any Link-mode trigger has tripped."
  [c g]
  (let [w (double (seesaw/width c))
        h (double (seesaw/height c))
        outline (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        enabled? (sync-triggers?)
        tripped? (some? (:target-bpm @client))]
    (.setRenderingHint g java.awt.RenderingHints/KEY_ANTIALIASING java.awt.RenderingHints/VALUE_ANTIALIAS_ON)

    (when tripped?
      (if enabled?
        (do  ; Draw the inner filled circle showing sync is actively taking place.
          (.setPaint g java.awt.Color/green)
          (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))
        (do  ; Draw the inner gray circle showing sync would be active if we were connected and master.
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
    (started [this sender] (enable-pioneer-sync-controls true))
    (stopped [this sender] (enable-pioneer-sync-controls false))))

(defn- create-window
  "Creates the Carabiner window."
  [trigger-frame]
  (try
    (let [root  (seesaw/frame :title "Carabiner Connection"
                              :on-close :hide)
          group (seesaw/button-group)
          panel (mig/mig-panel
                 :constraints ["hidemode 3"]
                 :background "#ccc"
                 :items [[(seesaw/label :text "Carabiner Port:") "align right"]
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
                         [(seesaw/combobox :id :sync-mode :model ["Off" "Triggers" "Full"] :enabled? false
                                           :listen [:item-state-changed
                                                    (fn [e]
                                                      (when (= (.getStateChange e) java.awt.event.ItemEvent/SELECTED)
                                                        (let [new-mode (keyword
                                                                        (clojure.string/lower-case (seesaw/value e)))]
                                                          (if (and (= new-mode :full) (not (sending-status?)))
                                                            (do
                                                              (seesaw/value! (seesaw/select root [:#sync-mode]) "Off")
                                                              (report-status-requirement root))
                                                            (swap! client assoc :sync-mode new-mode)))
                                                        (seesaw/repaint!
                                                         (seesaw/select @carabiner-window [:#state]))
                                                        (when (sync-triggers?) (check-tempo))))])]
                         [(seesaw/canvas :id :state :size [18 :by 18] :opaque? false
                                         :tip "Sync state: Outer ring shows enabled, inner light when active.")
                          "wrap"]

                         [(seesaw/separator) "growx, span, wrap"]

                         [(seesaw/label :text "Target BPM:") "align right"]
                         [(seesaw/label :id :target :text "---") "wrap"]

                         [(seesaw/label :text "Link BPM:") "align right"]
                         [(seesaw/label :id :bpm :text "---") "wrap"]

                         [(seesaw/label :text "Link Peers:") "align right"]
                         [(seesaw/label :id :peers :text "---") "wrap"]

                         [(seesaw/separator) "growx, span, wrap"]

                         [(seesaw/label :text "Ableton Link:") "align right"]
                         [(seesaw/checkbox :id :sync-link :text "Sync")]
                         [(seesaw/radio :id :master-link :text "Master" :group group) "wrap"]

                         [(seesaw/checkbox :id :bar :text "Align at bar level") "skip 1, span 2, wrap"]

                         [(seesaw/separator) "growx, span, wrap"]

                         [(seesaw/label :text "Player 1:") "align right"]
                         [(seesaw/checkbox :id :sync-1 :text "Sync")]
                         [(seesaw/radio :id :master-1 :text "Master" :group group) "wrap"]

                         [(seesaw/label :text "Player 2:") "align right"]
                         [(seesaw/checkbox :id :sync-2 :text "Sync")]
                         [(seesaw/radio :id :master-2 :text "Master" :group group) "wrap"]

                         [(seesaw/label :text "Player 3:") "align right"]
                         [(seesaw/checkbox :id :sync-3 :text "Sync")]
                         [(seesaw/radio :id :master-3 :text "Master" :group group) "wrap"]

                         [(seesaw/label :text "Player 4:") "align right"]
                         [(seesaw/checkbox :id :sync-4 :text "Sync")]
                         [(seesaw/radio :id :master-4 :text "Master" :group group) "wrap"]

                         [(seesaw/label :text "Mixer:") "align right"]
                         [(seesaw/checkbox :id :sync-33 :text "Sync")]
                         [(seesaw/radio :id :master-33 :text "Master" :group group) "wrap"]])]

      ;; Attach the custom paint function to render the graphical trigger state
      (seesaw/config! (seesaw/select panel [:#state]) :paint paint-state)

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
