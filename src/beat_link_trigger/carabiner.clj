(ns beat-link-trigger.carabiner
  "Communicates with a local Carabiner daemon to participate in an
  Ableton Link session."
  (:require [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.net Socket]))

(defonce ^{:private true
           :doc "When connected, holds the socket used to communicate
  with Carabiner, the future which is processing its responses, and
  the running flag which can be used to gracefully terminate that
  thread."}
  client (atom {:port 17000
                :latency 20
                :last 0}))

(def ^{:private true
       :doc "Holds the frame allowing the user to configure and
  control the connection to the Carabiner daemon."}
  carabiner-window (atom nil))

(def bpm-tolerance
  "The amount by which the Link tempo can differ from our target tempo
  without triggering an adjustment."
  0.00001)

(defn active?
  "Checks whether there is currently an active connection to a
  Carabiner daemon."
  []
  (:running @client))

(defn- ensure-active
  "Throws an exception if there is no active connection."
  []
  (when-not (active?)
    (throw (IllegalStateException. "No active Carabiner connection."))))

(defn- send-message
  "Sends a message to the active Carabiner daemon."
  [message]
  (ensure-active)
  (.write (.getOutputStream (:socket @client)) (.getBytes (str message))))

(defn- check-tempo
  "If we are supposed to master the Link tempo, make sure the Link
  tempo is close enough to our target value, and adjust it if needed."
  []
  (let [state @client]
    (when (and (:master state)
               (some? (:target-bpm state))
               (> (Math/abs (- (:link-bpm state 0.0) (:target-bpm state))) bpm-tolerance))
      (send-message (str "bpm " (:target-bpm state))))))

(defn- handle-status
  "Processes a status update from Carabiner."
  [status]
  (let [bpm (double (:bpm status))
        peers (int (:peers status))]
    (swap! client assoc :link-bpm bpm :link-peers peers)
    (when-let [frame @carabiner-window]
      (seesaw/value! (seesaw/select frame [:#bpm]) (format "%.2f" bpm))
      (seesaw/value! (seesaw/select frame [:#peers]) (str peers))))
  (check-tempo))

(def skew-tolerance
  "The amount by which the start of a beat can be off without
  triggering an adjustment. This can't be larger than the normal beat
  packet jitter without causing spurious readjustments."
  0.0166)

(defn handle-beat-at-time
  "Processes a beat probe response from Carabiner."
  [info]
  ;; TODO: Check whether user wants bar-phase sync as well, and if so, compare the rounded beat mod 4 to the
  ;; beat reported by the player, and adjust that as well if needed.
  (let [skew (mod (:beat info) 1.0)]
    (when (and (> skew skew-tolerance)
               (< skew (- 1.0 skew-tolerance)))
      (println "Realigning beat by" skew)
      (send-message (str "force-beat-at-time " (Math/round (:beat info)) " " (:when info) " " (:quantum info))))))

(defn- response-handler
  "A loop that reads messages from Carabiner as long as it is supposed
  to be running, and takes appropriate action."
  [socket running]
  (let [buffer (byte-array 1024)
        input (.getInputStream socket)]
    (loop []
      (when (= running (:running @client))
        (try
          (let [n (.read input buffer)]
            (if (pos? n)
              (let [message (String. buffer 0 n)
                    reader (java.io.PushbackReader. (clojure.java.io/reader (.getBytes message)))
                    cmd (clojure.edn/read reader)]
                (println "Received:" message)
                (case cmd
                  status (handle-status (clojure.edn/read reader))
                  beat-at-time (handle-beat-at-time (clojure.edn/read reader))
                  (timbre/error "Unrecognized message from Carabiner:" message)))
              (swap! client dissoc :running)))
          (catch Exception e
            (timbre/error e "Problem reading from Carabiner.")))
        (recur)))
    (.close socket)))

(defn connect
  "Try to establish a connection to Carabiner. Returns truthy if the
  initial open succeeded."
  []
  (swap! client (fn [oldval]
                  (if (:running oldval)
                    oldval
                    (try
                      (let [socket (java.net.Socket. "127.0.0.1" (:port oldval))
                            running (inc (:last oldval))
                            processor (future (response-handler socket running))]
                        (merge oldval {:running running
                                       :last running
                                       :processor processor
                                       :socket socket}))
                      (catch Exception e
                        (timbre/warn e "Unable to connect to Carabiner")
                        oldval)))))
  (active?))

(defn disconnect
  "Shut down any active Carabiner connection."
  []
  (swap! client (fn [oldval]
                  (when (:running oldval)
                    (.close (:socket oldval))
                    (future-cancel (:processor oldval)))
                  (dissoc oldval :running)))
  nil)

(defn- update-target-tempo
  "Displays the current target BPM value, if any."
  []
  (when-let [frame @carabiner-window]
    (seesaw/value! (seesaw/select frame [:#target])
                   (if-some [target (:target-bpm @client)]
                     (format "%.2f" target)
                     "---"))))

(defn- validate-tempo
  "Makes sure a tempo request is a reasonable number of beats per minute."
  [bpm]
  (if (< 0.9 bpm 400.0)
    (double bpm)
    (throw (IllegalArgumentException. "Tempo must be between 1 and 400 BPM"))))

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
  timeline, given a quantum (number of beats per bar)."
  [when quantum]
  (ensure-active)
  (send-message (str "beat-at-time " when " " quantum)))

(defn- make-window-visible
  "Ensures that the Carabiner window is centered on the triggers
  window, in front, and shown."
  [trigger-frame]
  (let [our-frame @carabiner-window]
    (.setLocationRelativeTo our-frame trigger-frame)
    (seesaw/show! our-frame)
    (.toFront our-frame)))

(defn- connect-choice
  "Respond to the user changing the state of the Connect checkbox."
  [checked]
  (if checked  ; Attempt to connect
    (if (connect)
      (do  ; Success
        (seesaw/config! (seesaw/select @carabiner-window [:#master]) :enabled? true)
        (seesaw/config! (seesaw/select @carabiner-window [:#port]) :enabled? false))
      (do  ; Failed
        (seesaw/value! (seesaw/select @carabiner-window [:#connect]) false)
        (seesaw/invoke-now (javax.swing.JOptionPane/showMessageDialog
                      @carabiner-window
                      "Unable to connect to Carabiner; make sure it is running on the specified port."
                      "Carabiner Connection failed"
                      javax.swing.JOptionPane/WARNING_MESSAGE))))
    (do  ; Disconnect
      (disconnect)
      (seesaw/config! (seesaw/select @carabiner-window [:#master]) :enabled? false)
      (seesaw/config! (seesaw/select @carabiner-window [:#port]) :enabled? true)
      (seesaw/value! (seesaw/select @carabiner-window [:#bpm]) "---")
      (seesaw/value! (seesaw/select @carabiner-window [:#peers]) "---"))))

(defn- create-window
  "Creates the Carabiner window."
  [trigger-frame]
  (try
    (let [root (seesaw/frame :title "Carabiner Connection"
                             :on-close :hide)
          panel (mig/mig-panel
                 :background "#ccc"
                 :items [[(seesaw/label :text "Carabiner Port:") "align right"]
                         [(seesaw/spinner :id :port
                                          :model (seesaw/spinner-model (:port @client) :from 1 :to 32767)
                                          :listen [:selection (fn [e]
                                                                (swap! client assoc :port (seesaw/selection e)))])]
                         [(seesaw/checkbox :id :connect :text "Connect"
                                           :listen [:action (fn [e]
                                                              (connect-choice (seesaw/value e)))]) "wrap"]

                         [(seesaw/label :text "Latency (ms):") "align right"]
                         [(seesaw/spinner :id :latency
                                          :model (seesaw/spinner-model (:latency @client) :from 0 :to 1000)
                                          :listen [:selection (fn [e]
                                                                (swap! client assoc :latency (seesaw/selection e)))])
                          "wrap"]

                         [(seesaw/label :text "Target BPM:") "align right"]
                         [(seesaw/label :id :target :text "---")]
                         [(seesaw/checkbox :id :master :text "Master" :enabled? false
                                           :listen [:item-state-changed (fn [e]
                                                                          (swap! client assoc :master
                                                                                 (seesaw/value e)))]) "wrap"]

                         [(seesaw/label :text "Link BPM:") "align right"]
                         [(seesaw/label :id :bpm :text "---") "wrap"]

                         [(seesaw/label :text "Link Peers:") "align right"]
                         [(seesaw/label :id :peers :text "---")]])]
      (seesaw/config! root :content panel)
      (seesaw/pack! root)
      (reset! carabiner-window root)
      (update-target-tempo)
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating Carabiner window."))))

(defn show-window
  "Make the Carabiner window visible, creating it if necessary."
  [trigger-frame]
  (if @carabiner-window
    (make-window-visible trigger-frame)
    (create-window trigger-frame)))
