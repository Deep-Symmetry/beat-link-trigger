(ns beat-link-trigger.carabiner
  "Communicates with a local Carabiner daemon to participate in an
  Ableton Link session."
  (:require [taoensso.timbre :as timbre])
  (:import [java.net Socket]))

(defonce ^{:private true
           :doc "When connected, holds the socket used to communicate
  with Carabiner, the future which is processing its responses, and
  the running flag which can be used to gracefully terminate that
  thread."}
  client (atom {:last 0}))

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
  "Makes sure the Link tempo is close enough to our target value, and
  adjusts it if needed."
  []
  (let [state @client]
    (when (> (Math/abs (- (:link-bpm state 0.0) (:target-bpm state))) bpm-tolerance)
      (send-message (str "bpm " (:target-bpm state))))))

(defn- handle-status
  "Processes a status update from Carabiner."
  [status]
  (swap! client assoc :link-bpm (double (:bpm  status)))
  (check-tempo)
  (println "Link bpm:" (:bpm status)))

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
                  'status (handle-status (clojure.edn/read reader))
                  (timbre/error "Unrecognized message from Carabiner:" message)))
              (swap! client dissoc :running)))
          (catch Exception e
            (timbre/error e "Problem reading from Carabiner.")))
        (recur)))
    (.close socket)))

(defn- validate-tempo
  "Makes sure a tempo request is a reasonable number of beats per minute."
  [bpm]
  (if (< 0.9 bpm 400.0)
    (double bpm)
    (throw (IllegalArgumentException. "Tempo must be between 1 and 400 BPM"))))

(defn connect
  "Try to establish a connection to Carabiner, with a target BPM
  value. Returns truthy if the initial open succeeded. If port is not
  specified, the default Carabiner port of 17000 is used."
  ([bpm]
   (connect bpm 17000))
  ([bpm port]
   (swap! client (fn [oldval]
                   (if (:running oldval)
                     (assoc oldval :target-bpm (validate-tempo bpm))
                     (try
                       (let [socket (java.net.Socket. "127.0.0.1" port)
                             running (inc (:last oldval))
                             processor (future (response-handler socket running))]
                         {:running running
                          :last running
                          :processor processor
                          :socket socket
                          :target-bpm (validate-tempo bpm)})
                       (catch Exception e
                         (timbre/warn e "Unable to connect to Carabiner"))))))
   (active?)))

(defn disconnect
  "Shut down any active Carabiner connection."
  []
  (swap! client (fn [oldval]
                  (when (:running oldval)
                    (.close (:socket oldval))
                    (future-cancel (:processor oldval)))
                  (dissoc oldval :running)))
  nil)

(defn set-tempo
  "Sets the tempo of the Link session to the specified number of beats
  per minute."
  [bpm]
  (ensure-active)
  (swap! client assoc :target-bpm (validate-tempo bpm))
  (check-tempo))
