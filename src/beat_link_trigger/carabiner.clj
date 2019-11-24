(ns beat-link-trigger.carabiner
  "Communicates with a local Carabiner daemon to participate in an
  Ableton Link session, using the beat-carabiner library."
  (:require [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.util :as util]
            [beat-carabiner.core :as beat-carabiner]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.net Socket]
           [java.awt.event ItemEvent]
           [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncement DeviceAnnouncementListener LifecycleListener
            VirtualCdj DeviceUpdate CdjStatus MixerStatus MasterListener]
           [org.deepsymmetry.electro Metronome Snapshot]))

(defonce ^{:private true
           :doc "Holds our own notion of the sync mode as chosen in
  the menu, which extends the three supported by beat-carabiner to
  include `:triggers`, in which calls from individual triggers
  configured to `Link` mode can switch beat-carabiner between `:off`
  and `:passive`.

  Also tracks some timestamps used in managing master and sync mode
  changes requsted by the user."}

  state (atom {:sync-mode :off}))

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

(defn sync-enabled?
  "Checks whether we have an active connection and are in any sync mode
  other than `:off` (using our expanded set of modes, which includes
  `:triggers`)."
  []
  (and (beat-carabiner/active?)
       (not= :off (:sync-mode @state))))

(defn sync-triggers?
  "Checks whether we have an active connection for which Link triggers
  are controlling the tempo."
  []
  (and (beat-carabiner/active?)
       (= :triggers (:sync-mode @state))))

(defn sync-full?
  "Checks whether we have an active connection and are configured for
  bidirectional synchronization between Ableton Link and Pioneer Pro
  DJ Link."
  []
  (let [bc-state (beat-carabiner/state)]
    (and (:running bc-state)
         (= :full (:sync-mode bc-state)))))

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

(defn- update-target-tempo
  "Displays the current target BPM value, if any."
  []
  (when-let [frame @carabiner-window]
    (seesaw/invoke-later
     (seesaw/value! (seesaw/select frame [:#target])
                    (if-some [target (:target-bpm (beat-carabiner/state))]
                      (format "%.2f" target)
                      "---"))
     (seesaw/repaint! (seesaw/select frame [:#state])))))

(defn- update-connected-status
  "Make the state of the window reflect the current state of our
  connection to the Carabiner daemon."
  []
  (when-let [frame @carabiner-window]
    (seesaw/invoke-later
     (let [connected (boolean (beat-carabiner/active?))]
       (seesaw/config! (seesaw/select frame [:#sync-mode]) :enabled? connected)
       (seesaw/config! (seesaw/select frame [:#port]) :enabled? (not connected))
       (seesaw/value! (seesaw/select frame [:#connect]) connected)
       (seesaw/repaint! (seesaw/select frame [:#state]))))))

(defn- update-link-status
  "Make the state of the window reflect the current state of the Link
  session. Gets registered as a status listener with
  beat-carabiner (immediately below) so it will be called with the
  updated state whenever an update is received from Carabiner. Also
  updates the Link Sync checkbox if we are currently in `:triggers`
  sync mode to reflect whether a trigger is currently in control of
  the Link tempo."
  [state]
  (when-let [frame @carabiner-window]
    (update-target-tempo)
    (seesaw/invoke-later
     (seesaw/value! (seesaw/select frame [:#bpm-spinner]) (or (:link-bpm state) 0.0))
     (seesaw/value! (seesaw/select frame [:#peers]) (if-some [peers (:link-peers state)]
                                                      (str peers)
                                                      "---"))
     (when (= :triggers (:sync-mode state))
       (seesaw/value! (seesaw/select frame [:#sync-link]) (boolean (:target-bpm state)))))))

;; Arrange to update our window whenever the link state changes.
(beat-carabiner/add-status-listener update-link-status)

;; Display a warning message if the wrong version of Carabiner is detected.
(beat-carabiner/add-bad-version-listener (fn [message]
                                           (seesaw/invoke-later
                                            (javax.swing.JOptionPane/showMessageDialog
                                             @carabiner-window
                                             message
                                             "Carabiner Upgrade Recommended"
                                             javax.swing.JOptionPane/WARNING_MESSAGE))))

;; Update our user interface when the Carabiner connection closes, and
;; show a warning if it happened unexpectedly.
(beat-carabiner/add-disconnection-listener (fn [unexpected?]
                                             (when unexpected?
                                               (seesaw/invoke-later
                                                (javax.swing.JOptionPane/showMessageDialog
                                                 @carabiner-window
                                                 "Carabiner unexpectedly closed our connection; is it still running?"
                                                 "Carabiner Connection Closed"
                                                 javax.swing.JOptionPane/WARNING_MESSAGE)))
                                             (seesaw/value! (seesaw/select @carabiner-window [:#sync-mode]) "Off")
                                             (update-connected-status)
                                             (update-link-status (beat-carabiner/state))))

(defn disconnect
  "Shut down any active Carabiner connection and update our interface."
  []
  (beat-carabiner/disconnect)
  (seesaw/value! (seesaw/select @carabiner-window [:#sync-mode]) "Off")
  (update-connected-status)
  (update-link-status (beat-carabiner/state)))

(defn connect
  "Try to establish a connection to Carabiner. Returns truthy if the
  initial open succeeded. Sets up a background thread to reject the
  connection if we have not received an initial status report from the
  Carabiner daemon within a second of opening it."
  []
  (letfn [(failure-fn [message]
            (seesaw/invoke-later
             (javax.swing.JOptionPane/showMessageDialog
              @carabiner-window
              message
              "Carabiner Connection failed"
              javax.swing.JOptionPane/WARNING_MESSAGE)))]
    (let [result (beat-carabiner/connect failure-fn)]
      (future (update-connected-status))
      result)))

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
   (beat-carabiner/beat-at-time time beat-number)
   (when (= :triggers (:sync-mode @state))  ; Update Align at bar level checkbox when driven by trigger
       (seesaw/invoke-later
        (seesaw/value! (seesaw/select @carabiner-window [:#bar]) (some? beat-number))))))

(defn- make-window-visible
  "Ensures that the Carabiner window is in front, and shown."
  [parent]
  (let [our-frame @carabiner-window]
    (util/restore-window-position our-frame :carabiner parent)
    (seesaw/show! our-frame)
    (.toFront our-frame)))

(defn- connect-choice
  "Respond to the user changing the state of the Connect checkbox."
  [checked]
  (if checked
    (connect)
    (disconnect)))

(defn- paint-state
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
        tripped? (let [state (beat-carabiner/state)]
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
  ((resolve 'beat-link-trigger.triggers/real-player?)))

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
                            "Please select Use Real Player Number in the Network menu,\n"
                            "then go offline and back online to enable them.")
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

(def sync-hysteresis
  "The number of milliseconds to wait for sync state to settle after
  sending a sync command, so our UI does not get into a terrible
  feedback loop."
  250)

(def master-hysteresis
  "The number of milliseconds to wait for sync state to settle after
  sending a tempo master assignment command, so our UI does not get
  into a terrible feedback loop."
  300)

(defn- start-sync-state-updates
  "Creates and starts the thread which updates the Sync and Master UI to
  reflect changes initiated on the devices themselves, and keeps the
  Virtual CDJ's timeline aligned with Ableton Link's when it is
  playing."
  [frame]
  (future
    (loop []
      (try
        (when (.isRunning virtual-cdj)  ; Skip this if we are currently offline.
          (seesaw/invoke-later
           ;; First update the states of the actual device rows
           (doseq [status (filter #(#{1 2 3 4 33} (long (.getDeviceNumber %))) (.getLatestStatus virtual-cdj))]
             (let [device        (long (.getDeviceNumber status))
                   master-button (seesaw/select frame [(keyword (str "#master-" device))])
                   sync-box      (seesaw/select frame [(keyword (str "#sync-" device))])]
               (when  (and (.isTempoMaster status) (not (seesaw/value master-button)))
                 (let [changed (:master-command-sent @state)]
                   (when (or (nil? changed) (> (- (System/currentTimeMillis) changed) master-hysteresis))
                     (seesaw/value! master-button true))))
               (when (not= (seesaw/value sync-box) (.isSynced status))
                 (let [changed (get-in @state [:sync-command-sent device])]
                   (when (or (nil? changed) (> (- (System/currentTimeMillis) changed) sync-hysteresis))
                     (seesaw/value! sync-box (.isSynced status)))))))
           ;; Then update the state of the Ableton Link (Virtual CDJ) row
           (let [master-button (seesaw/select frame [:#master-link])
                 sync-box      (seesaw/select frame [:#sync-link])]
             (when  (and (.isTempoMaster virtual-cdj) (not (seesaw/value master-button)))
               (let [changed (:master-command-sent @state)]
                 (when (or (nil? changed) (> (- (System/currentTimeMillis) changed) master-hysteresis))
                   (seesaw/value! master-button true))))
             (when (not= (seesaw/value sync-box) (.isSynced virtual-cdj))
               (let [changed (get-in @state [:sync-command-sent (long (.getDeviceNumber virtual-cdj))])]
                 (when (or (nil? changed) (> (- (System/currentTimeMillis) changed) sync-hysteresis))
                   (seesaw/value! sync-box (.isSynced virtual-cdj))))))))

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
      (swap! state assoc-in [:sync-command-sent device] (System/currentTimeMillis))
      (.sendSyncModeCommand virtual-cdj device selected))))

(defn- master-button-changed
  "Called when one of the device Master radio buttons has been toggled. Makes
  sure the corresponding device's Master state is in agreement (we may
  not need to do anything, because our state change may be in response
  to a report from the device itself.)"
  [^ItemEvent event device]
  (when (= (.getStateChange event) ItemEvent/SELECTED)  ; This is the new master
    (when-not (.isTempoMaster (.getLatestStatusFor virtual-cdj device))  ; But it doesn't know it yet
      (swap! state assoc :master-command-sent (System/currentTimeMillis))
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

(defn- link-sync-state-changed
  "Event handler for when the Link Sync checkbox has changed state.
  Update the Virtual CDJ sync state accordingly if necessary (this may
  be happening in response to a change that started there)."
  [synced]
  #_(timbre/debug "link-sync-state-changed" synced (:sync-mode @state) "master?" (.isTempoMaster virtual-cdj))
  (when (not= (.isSynced virtual-cdj) synced)
    (swap! state assoc-in [:sync-command-sent (.getDeviceNumber virtual-cdj)] (System/currentTimeMillis))
    (beat-carabiner/sync-link synced)))

(defn- link-master-state-changed
  "Event handler for when the Link Master radio button has changed
  state. Update the Virtual CDJ master state accordingly if
  necessary (this may be happening in response to a change that
  started there), and if our Sync Mode is Full, start or stop tying
  the Pioneer tempo and beat grid to Ableton Link."
  [master]
  (when (and master (not (.isTempoMaster virtual-cdj)))
    (swap! state assoc :master-command-sent (System/currentTimeMillis)))
  (beat-carabiner/link-master master))

(defn- sync-mode-changed
  "Event handler for the Sync Mode selector. Valdiates that the desired
  mode is consistent with the current state, and if so, updates the
  relevant interface elements and sets up the new state."
  [new-mode root]
  (swap! state assoc :sync-mode new-mode)
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
      (beat-carabiner/set-sync-mode new-mode)
      (seesaw/config! (seesaw/select root [:#sync-link]) :enabled? (#{:passive :full} new-mode))
      (seesaw/config! (seesaw/select root [:#bar]) :enabled? (#{:passive :full} new-mode))
      (seesaw/config! (seesaw/select root [:#master-link]) :enabled? (= :full new-mode))

      (when (= :triggers new-mode)
        (seesaw/value! (seesaw/select root [:#sync-link]) false))  ; Will get set on next update if trigger active

      (seesaw/repaint! (seesaw/select root [:#state])))))

(defn- create-shift-tracker
  "Registers a keyboard listener so the BPM spinner's step value can be
  adjusted to move by whole beats per minute when shift is held down."
  [spinner-model]
  (let [dispatcher (proxy [Object java.awt.KeyEventDispatcher] []
                     (dispatchKeyEvent [e]
                       (.setStepSize spinner-model (if (.isShiftDown e) 1.0 0.01))
                       false))]
    (.. java.awt.KeyboardFocusManager getCurrentKeyboardFocusManager (addKeyEventDispatcher dispatcher))))

(defn- create-window
  "Creates the Carabiner window."
  [trigger-frame]
  (try
    (let [settings (:carabiner (prefs/get-preferences))]   ; Restore any changed connection settings.
      (when-let [port (:port settings)]
        (beat-carabiner/set-carabiner-port port))
      (when-let [latency (:latency settings)]
        (beat-carabiner/set-latency latency))
      (when-let [bars (:bars settings)]
        (beat-carabiner/set-sync-bars bars)))
    (let [root      (seesaw/frame :title "Carabiner Connection"
                                  :on-close :hide)
          group     (seesaw/button-group)
          state     (seesaw/canvas :id :state :size [18 :by 18] :opaque? false
                                   :tip "Sync state: Outer ring shows enabled, inner light when active.")
          bpm-model (javax.swing.SpinnerNumberModel. 120.0, 20.0, 999.0, 0.01)
          link-bpm  (seesaw/spinner :id :bpm-spinner
                                    :model bpm-model
                                    :listen [:selection (fn [e]
                                                          (when (seesaw/config e :enabled?)
                                                            (let [tempo (seesaw/selection e)]
                                                              (beat-carabiner/set-link-tempo tempo))))]
                                    :enabled? false)
          bpm       (seesaw/label :id :bpm :text "---")
          panel     (mig/mig-panel
                     :constraints ["hidemode 3"]
                     :background "#ccc"
                     :items (concat
                             [[(seesaw/label :text "Carabiner Port:") "align right"]
                              [(seesaw/spinner :id :port
                                               :model (seesaw/spinner-model (:port (beat-carabiner/state))
                                                                            :from 1 :to 32767)
                                               :listen [:selection (fn [e]
                                                                     (let [port (seesaw/selection e)]
                                                                       (beat-carabiner/set-carabiner-port port)
                                                                       (prefs/put-preferences
                                                                        (assoc-in (prefs/get-preferences)
                                                                                  [:carabiner :port] port))))])]
                              [(seesaw/checkbox :id :connect :text "Connect"
                                                :listen [:action (fn [e]
                                                                   (connect-choice (seesaw/value e)))]) "span 2, wrap"]

                              [(seesaw/label :text "Latency (ms):") "align right"]
                              [(seesaw/spinner :id :latency
                                               :model (seesaw/spinner-model (:latency (beat-carabiner/state))
                                                                            :from 0 :to 1000)
                                               :listen [:selection (fn [e]
                                                                     (let [latency (seesaw/selection e)]
                                                                       (beat-carabiner/set-latency latency)
                                                                       (prefs/put-preferences
                                                                        (assoc-in (prefs/get-preferences)
                                                                                  [:carabiner :latency] latency))))])
                               "wrap"]

                              [(seesaw/label :text "Sync Mode:") "align right"]
                              [(seesaw/combobox :id :sync-mode
                                                :model ["Off" "Triggers" "Passive" "Full"] :enabled? false
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
                              [link-bpm "wrap"]

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
                                                                              (do
                                                                                (link-master-state-changed true)
                                                                                (seesaw/config! link-bpm
                                                                                                :enabled? true))

                                                                              ItemEvent/DESELECTED
                                                                              (do
                                                                                (link-master-state-changed false)
                                                                                (seesaw/config! link-bpm
                                                                                                :enabled? false))

                                                                              nil))]) "wrap"]

                              [(seesaw/checkbox :id :bar :text "Align at bar level"
                                                :enabled? (:bars (beat-carabiner/state))
                                                :listen [:item-state-changed
                                                         (fn [^ItemEvent e]
                                                           (let [bars (= (.getStateChange e) ItemEvent/SELECTED)]
                                                             (beat-carabiner/set-sync-bars bars)
                                                             (prefs/put-preferences
                                                              (assoc-in (prefs/get-preferences)
                                                                        [:carabiner :bars] bars))))])
                               "skip 1, span 2, wrap"]

                              [(seesaw/separator) "growx, span, wrap"]]

                             (build-device-sync-rows group)))]

      ;; Attach the custom paint function to render the graphical trigger state
      (seesaw/config! state :paint paint-state)

      ;; Set up the BPM spinner's editor and shift key listener
      (.setEditor link-bpm (javax.swing.JSpinner$NumberEditor. link-bpm "##0.00"))
      ;; The shift key listener has been removed until I can resolve the bad interaction between the shift key
      ;; and the SpinnerEditor: If you press or release the shift key while trying to edit the text of the spinner,
      ;; for example because you want to shift-select, it changes the step size which cancels the edit.
      ;; On the bright side, the inability to step by whole numbers is offset by the ability to type the tempo
      ;; you want for big jumps.
      #_(create-shift-tracker bpm-model)

      ;; Assemble the window
      (seesaw/config! root :content panel)
      (seesaw/pack! root)
      (.setResizable root false)
      (seesaw/listen root :component-moved (fn [e] (util/save-window-position root :carabiner true)))
      (reset! carabiner-window root)
      (update-connected-status)
      (update-link-status (beat-carabiner/state))
      (update-target-tempo)

      (.addLifecycleListener virtual-cdj virtual-cdj-lifecycle-listener)
      (enable-pioneer-sync-controls (.isRunning virtual-cdj))  ; Set proper initial state.
      (.addDeviceAnnouncementListener device-finder device-announcement-listener)
      (doseq [device [1 2 3 4 33]]  ; Set proper initial state
        (update-device-visibility device (and (.isRunning device-finder)
                                              (some? (.getLatestAnnouncementFrom device-finder device)))))
      (start-sync-state-updates root)
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating Carabiner window."))))

(defn show-window
  "Make the Carabiner window visible, creating it if necessary."
  [trigger-frame]
  (if @carabiner-window
    (make-window-visible trigger-frame)
    (create-window trigger-frame)))

(defn- require-frame
  "Throws an exception if the Carabiner window has not yet been opened.
  Otherwise, returns the window."
  []
  (let [frame @carabiner-window]
    (when-not frame
      (throw (Exception. "Carabiner window has not been opened.")))
    frame))

(defn- require-connection
  "Throws an exception if we are not connected to the Carabiner daemon."
  []
  (when-not (beat-carabiner/active?)
    (throw (Exception. "Must be connected to Carabiner to set sync."))))

(defn- require-online
  "Throws an exception if BLT is not currently online."
  []
  (when-not (.isRunning virtual-cdj)
    (throw (Exception. "BLT must be online to set Carabiner sync."))))

(defn sync-mode
  "Check or change the sync mode of Carabiner, for use by custom
  expressions. With no arguments, returns the current sync mode, one
  of `:off`, `:triggers`, `:passive`, or `:full`. If the `mode`
  argument is supplied, tries to choose the specified sync mode. This
  throws an exception if it is not currently possible to change sync
  mode (e.g. not connected to Carabiner, or BLT is offline), or if the
  chosen sync mode is not recognized."
  ([]
   (:sync-mode @state))
  ([mode]
   (if-let [value (get {:off      "Off"
                        :triggers "Triggers"
                        :passive  "Passive"
                        :full     "Full"}
                       mode)]
     (do
       (require-connection)
       (require-online)
       (if (and (= mode :full) (not (sending-status?)))
         (throw (Exception. "Must be using a real player number to enable Full Carabiner sync.")))
       (seesaw/invoke-now
        (seesaw/value! (seesaw/select @carabiner-window [:#sync-mode]) value)))
     (throw (Exception. (str "Unrecognized sync mode: " mode))))))

(defn sync-link
  "Check or change whether we are currently syncing the Ableton Link
  session to the DJ Link network. With no arguments, returns truthy if
  this kind of sync is taking place. If the `enable?` argument is
  supplied, starts or stops the sync accordingly."
  ([]
   (seesaw/invoke-now
    (seesaw/value (seesaw/select (require-frame) [:#sync-link]))))
  ([enable?]
   (seesaw/invoke-now
    (seesaw/value! (seesaw/select (require-frame) [:#sync-link]) enable?)
    enable?)))  ; Don't return the actual checkbox.

(defn master-ableton
  "Checks whether the Ableton Link session is currently controlling the
  tempo of the DJ Link network."
  []
  (require-connection)
  (require-online)
  (seesaw/invoke-now
   (seesaw/value (seesaw/select @carabiner-window [:#master-link]))))

(defn appoint-ableton-master
  "Causes the DJ Link network to follow the tempo of the Ableton Link
  session. This can only be done when Carabiner is connected and the
  Sync Mode is Full."
  []
  (require-connection)
  (require-online)
  (seesaw/invoke-now
   (let [button (seesaw/select @carabiner-window [:#master-link])]
     (if (seesaw/config button :enabled?)
       (do (seesaw/value! button true)
           true)  ; Don't return the radio button itself.
       (throw (Exception. "Can only set Ableton Link as Tempo Master when Sync Mode is Full."))))))

(defn align-bars
  "Check or change whether we are currently aligning with Ableton Link
  at the bar level, rather than for just beats. With no arguments,
  returns truthy if bar alignment taking place. If the `enable?`
  argument is supplied, starts or stops aligning full measures."
  ([]
   (seesaw/invoke-now
    (seesaw/value (seesaw/select (require-frame) [:#bar]))))
  ([enable?]
   (seesaw/invoke-now
    (seesaw/value! (seesaw/select (require-frame) [:#bar]) enable?)
    enable?)))  ; Don't return the checkbox itself.

(defn sync-device
  "Helper function to make it easy to check the Sync state of a Pioneer
  device by passing just its number, or to turn Sync on or off by
  passing a second argument to specify the desired state."
  ([device]
   (if-let [status (.getLatestStatusFor virtual-cdj device)]
     (.isSynced status)
     (throw (Exception. (str "Device " device " not found on network.")))))
  ([device sync?]
   (.sendSyncModeCommand virtual-cdj device sync?)))

(defn master-device
  "Helper function to make it easy to check the Master state of a
  Pioneer device by passing its number. To assign a new Tempo Master,
  use `appoint-tempo-master`."
  [device]
  (if-let [status (.getLatestStatusFor virtual-cdj device)]
    (.isTempoMaster status)
    (throw (Exception. (str "Device " device " not found on network.")))))

(defn appoint-master-device
  "Helper function to make it easy to tell a Pioneer device to become
  Tempo Master by passing its number."
  [device]
  (.appointTempoMaster virtual-cdj device))
