(ns beat-link-trigger.simulator
  "Provides shallow playback simulation when BLT is offline."
  (:require [beat-link-trigger.util :as util]
            [beat-link-trigger.show-util :as su :refer [latest-show]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink.data SignatureUpdate WaveformPreviewComponent]
           [org.deepsymmetry.electro Metronome]
           [java.awt.event MouseEvent]
           [javax.swing JFrame]))

(defonce ^{:private true
           :doc "The open simulator windows, keyed by their UUID."}
  simulators
  (atom {}))

(defn simulating?
  "Checks whether there are currently any simulator windows open."
  []
  (not-empty @simulators))

(defn track-signatures
  "Returns a set of the signatures of the tracks being simulated."
  []
  (->> (vals @simulators)
       (map :track)
       (map :signature)
       set))

(defn for-player
  "Returns the simulator window with the specified player number, if one
  is open."
  [player]
  (->> (vals @simulators)
       (filter #(= (:player %) player))
       first))

(defn- build-cdj-status
  "Creates the CDJ status object to represent our current simulation state."
  [{:keys [master on-air pitch player playing sync time track]}]
  (let [{:keys [grid]} track
        chosen-beat    (.findBeatAtTime grid time)]
    (util/simulate-player-status {:bb            (if (pos? chosen-beat)
                                                   (.getBeatWithinBar grid chosen-beat)
                                                   0)
                                  :beat          (if (pos? chosen-beat) chosen-beat 0)
                                  :device-number player
                                  :bpm           (* pitch (.getBpm grid (if (pos? chosen-beat) chosen-beat 1)))
                                  :d-r           player
                                  :s-r           2
                                  :t-r           1
                                  :rekordbox     42
                                  :f             (unchecked-byte (apply + (filter identity [0x84
                                                                                            (when playing 0x40)
                                                                                            (when master 0x20)
                                                                                            (when sync 0x10)
                                                                                            (when on-air 0x8)])))})))

(defn- simulator-tick
  "Send shallow simulation events when appropriate. Called frequently by
  `simulator-loop`, below. A separate function so it can be redefined
  without having to kill and recreate the thread for changes to take
  effect during development."
  []
  (doseq [simulator (vals @simulators)]
    (when (:playing simulator)
      (let [new-time (.getBeat (:metronome simulator))]
        (when (> (.toSeconds java.util.concurrent.TimeUnit/MILLISECONDS new-time)
                 (get-in simulator [:track :metadata :duration]))
          (seesaw/invoke-now (.doClick (seesaw/select (:frame simulator) [:#play]))))
        (swap! simulators assoc-in [(:uuid simulator) :time] new-time)))
    (let [{:keys [last-status partial player playing preview time track uuid]} (get @simulators (:uuid simulator))]
      (when-not partial
        (seesaw/invoke-later
         (.setPlaybackState preview player time playing))
        (let [now     (System/nanoTime)
              elapsed (.toMillis java.util.concurrent.TimeUnit/NANOSECONDS (- now (or last-status 0)))]
          (when (>= elapsed 200)
            ;; It's time to send another simulated status packet for this player.
            (binding [util/*simulating* (assoc track :time time)]
              (let [status (build-cdj-status simulator)]
                (doseq [show (vals (su/get-open-shows))]
                  (when-let [show-track (get-in show [:tracks (:signature track)])]
                    ((requiring-resolve 'beat-link-trigger.show/run-custom-enabled) show show-track status)
                    ((requiring-resolve 'beat-link-trigger.show/update-show-status) show (:signature track)
                     show-track status)))))
            (swap! simulators assoc-in [uuid last-status] now)))))))

(defn- simulator-loop
  "The main loop of the daemon thread that sends shallow playback
  simulation events when appropriate."
  []
  (loop []
    (try
      (simulator-tick)
      (catch Throwable t
        (timbre/error t "Problem during shallow playback simulation.")))
    (Thread/sleep (if (simulating?) 1 250))
    (recur)))

(defonce ^{:private true
           :doc "Holds the thread which generates shallow playback simulation events."}
  simulator-thread
  (atom nil))

;; Used to represent one of the sample tracks available for simulation
(defrecord SampleChoice [number signature]
  Object
  (toString [_]
    (let [samples @@(requiring-resolve 'beat-link-trigger.overlay/sample-track-data)]
      (get-in samples [number :metadata :title]))))

;; Used to represent a track from a show available for simulation
(defrecord TrackChoice [file signature]
  Object
  (toString [_]
    (let [show (latest-show file)]
      (get-in show [:tracks signature :metadata :title]))))

(defn- choose-simulator-player
  "Finds the first player number that has not yet been used by a
  simulator."
  []
  (let [used (set (map :player (vals @simulators)))]
    (first (remove used (map inc (range 6))))))

(defn- player-menu-model
  "Returns the available player numbers this simulator can choose."
  [uuid]
  (let [state     @simulators
        simulator (get state uuid)
        used      (set (map :player (vals state)))]
    (sort (set/union (set (remove used (map inc (range 6)))) #{(:player simulator)}))))

(defn- recompute-player-models
  "Updates the player combo-boxes of all open windows to reflect the
  current state of the other windows."
  []
  (doseq [simulator (vals @simulators)]
    (let [combo   (seesaw/select (:frame simulator) [:#player])
          current (seesaw/selection combo)]
      (swap! simulators assoc-in [(:uuid simulator) :adjusting] true)
      (seesaw/config! combo :model (player-menu-model (:uuid simulator)))
      (seesaw/selection! combo current)
      (swap! simulators update (:uuid simulator) dissoc :adjusting))))

(defn track-menu-model
  "Returns the available tracks this simulator can choose. First builds a
  sorted list of all unique tracks in any open show, then appends
  whichever of the two sample tracks that are not present in that
  list (they are already sorted by name)."
  []
  (let [all-tracks      (mapcat (fn [show] (map #(TrackChoice. (:file show) (:signature %)) (vals (:tracks show))))
                                (vals (su/get-open-shows)))
        samples         (map (fn [[n data]]
                               (SampleChoice. n (:signature data)))
                             @@(requiring-resolve 'beat-link-trigger.overlay/sample-track-data))
        [choices _sigs] (reduce (fn [[tracks signatures] track]
                                  (if (signatures (:signature track))
                                    [tracks signatures]
                                    [(conj tracks track) (conj signatures (:signature track))]))
                                [[] #{}]
                                (concat (sort-by #(.toString %) all-tracks) samples))]
    choices))

(defn- handle-preview-press
  "Processes a mouse press over the waveform preview in a simulator
  window. Updates our current position to that point in the track."
  [uuid ^WaveformPreviewComponent component ^MouseEvent e]
  (let [point       (.getPoint e)
        target-time (.getTimeForX component (.-x point))        ]
    (.jumpToBeat (get-in @simulators [uuid :metronome]) target-time)
    (swap! simulators assoc-in [uuid :time] target-time)))

(defn- set-simulation-data
  "Given the track menu choice, finds and records the appropriate data
  for simulation of that track, then lets any open shows know about
  the simulated presence of the track."
  [uuid choice]
  (let [data (util/data-for-simulation :entry (if (instance? SampleChoice choice)
                                                (:number choice)
                                                [(:file choice) (:signature choice)]))
        old  (get-in @simulators [uuid :preview])]
    (swap! simulators update uuid (fn [simulator]
                                    (-> simulator
                                        (assoc :track (merge data (select-keys choice [:signature])))
                                        (assoc :time 0))))
    (let [preview        (seesaw/select (get-in @simulators [uuid :frame]) [:#preview])
          component      (WaveformPreviewComponent. (:preview data) (get-in data [:metadata :duration])
                                                    (:cue-list data))
          song-structure (:song-structure data)]
      (when song-structure (.setSongStructure component song-structure))
      (seesaw/listen component
                     :mouse-pressed (fn [e] (handle-preview-press uuid component e)))
      (if old
        (seesaw/replace! preview old component)
        (seesaw/config! preview :center component))
      (swap! simulators update uuid (fn [simulator] (-> simulator
                                                        (assoc :preview component)
                                                        (dissoc :partial)))))
    (let [sig-update (SignatureUpdate. (get-in @simulators [uuid :player]) (:signature choice))]
      (doseq [show (vals (su/get-open-shows))]
        (try
          ((requiring-resolve 'beat-link-trigger.show/update-player-item-signature) sig-update show)
          (su/update-row-visibility show)
          (catch Throwable t
            (timbre/error t "Problem delivering simulated signature update to show" sig-update (:file show))))))))

(defn recompute-track-models
  "Updates the track combo-boxes of all open windows to reflect the
  addition or removal of tracks or shows."
  []
  (let [model (track-menu-model)
        index (group-by :signature model)]
    (doseq [simulator (vals @simulators)]
      (let [combo (seesaw/select (:frame simulator) [:#track])
            old   (seesaw/selection combo)
            new   (first (index (:signature old)))]
        (when new (swap! simulators assoc-in [(:uuid simulator) :adjusting] true))  ; Can retain old track.
        (seesaw/config! combo :model model)
        (if new
          (seesaw/selection! combo new)
          (set-simulation-data (:uuid simulator) (first model)))  ; Lost old track.
        (swap! simulators update (:uuid simulator) dissoc :adjusting)))))

(defn handle-play-toggle
  [uuid playing?]
  (let [simulator (get @simulators uuid)]
    (if playing?
      (do
        (.jumpToBeat (:metronome simulator) (:time simulator))  ; Pick up our metronome at the current time.
        ;; If there isn't another tempo master, we are now it.
        (when (empty? (filter (fn [candidate] (and (:playing candidate) (:master candidate)
                                                   (not= uuid (:uuid candidate))))
                              (vals @simulators)))
          (when-not (:master simulator)
            (.doClick (seesaw/select (:frame simulator) [:#master])))))
      (when (:master simulator)  ; See if there is another playing simulator to hand master status over to.
        (when-let [other (first (filter (fn [candidate] (and (:playing candidate) (not= uuid (:uuid candidate))))
                                        (vals @simulators)))]
          (.doClick (seesaw/select (:frame other) [:#master])))))
    (swap! simulators assoc-in [uuid :playing] playing?)))

(defn build-simulator-panel
  "Creates the UI of the simulator window, once its basic configuration
  has been set up."
  [uuid]
  (let [simulator (get @simulators uuid)]
    (mig/mig-panel
     :items [["Player:"]
             [(seesaw/combobox :id :player :model (player-menu-model uuid)
                               :selected-item (:player simulator)
                               :listen [:item-state-changed
                                        (fn [e]
                                          (let [chosen (seesaw/selection e)]
                                            (swap! simulators assoc-in [uuid :player] chosen)
                                            (when-not (get-in @simulators [uuid :adjusting])
                                              (recompute-player-models))))])]
             [(seesaw/checkbox :id :on-air :text "On-Air" :selected? true
                               :listen [:action-performed #(swap! simulators assoc-in [uuid :on-air]
                                                                  (seesaw/value %))])]
             [(seesaw/checkbox :id :master :text "Master"
                               :listen [:action-performed (fn [e]
                                                  (let [master? (seesaw/value e)]
                                                    (swap! simulators assoc-in [uuid :master] master?)
                                                    (when master?
                                                      (doseq [simulator (vals @simulators)]
                                                        (when (not= uuid (:uuid simulator))
                                                          (.doClick
                                                           (seesaw/select (:frame simulator) [:#master])))))))])]
             [(seesaw/toggle :id :play :text "Play"
                             :listen [:action-performed #(handle-play-toggle uuid (seesaw/value %))]) "wrap"]
             ["Track:"]
             [(seesaw/combobox :id :track :model (track-menu-model)
                               :listen [:item-state-changed
                                        (fn [e]
                                          (let [chosen (seesaw/selection e)]
                                            (set-simulation-data uuid chosen)))])
              "spanx, wrap"]
             [(seesaw/border-panel :id :preview) "width 640, height 80, spanx, wrap"]])))

(defn- create-simulator
  "Creates a new shallow playback simulator. Takes a reference to the
  menu item which invokes this, so it can be disabled when all
  possible device numbers already have simulators created for them."
  [simulate-item]
  (let [uuid         (java.util.UUID/randomUUID)
        ^JFrame root (seesaw/frame :title "Shallow Playback Simulator"
                                   :on-close :nothing)
        player       (choose-simulator-player)
        close-fn     (fn []
                       (.dispose root)
                       (swap! simulators assoc-in [uuid :partial] true)
                       (let [sig-update (SignatureUpdate. (get-in @simulators [uuid :player]) nil)]
                         (recompute-player-models)
                         (seesaw/config! simulate-item :enabled? true)
                         (doseq [show (vals (su/get-open-shows))]
                           (try
                             ((requiring-resolve 'beat-link-trigger.show/update-player-item-signature) sig-update show)
                             (catch Throwable t
                               (timbre/error t "Problem delivering simulated signature update to show" sig-update
                                             (:file show)))))
                         (when (empty? (swap! simulators dissoc uuid))
                           (doseq [show (vals (su/get-open-shows))]
                             ((requiring-resolve 'beat-link-trigger.show/simulation-state-changed) show false))))
                       true)
        metronome    (Metronome.)
        created      (swap! simulators assoc uuid
                            {:uuid      uuid
                             :frame     root
                             :partial   true
                             :player    player
                             :sync      false
                             :master    false
                             :on-air    true
                             :playing   false
                             :pitch     1.0
                             :time      0
                             :metronome metronome
                             :close-fn  close-fn})]
    (.setTempo metronome (double (.toMillis java.util.concurrent.TimeUnit/MINUTES 1)))
    (seesaw/config! root :content (build-simulator-panel uuid))
    (recompute-player-models)
    (set-simulation-data uuid (seesaw/selection (seesaw/select root [:#track])))
    (seesaw/listen root :window-closing (fn [_] (close-fn)))
    (seesaw/pack! root)
    (seesaw/show! root)
    (when (= (count created) 1)
      ;; We just opened the first simulator window, so let any shows know that simulation has begun.
      (doseq [show (vals (su/get-open-shows))]
        ((requiring-resolve 'beat-link-trigger.show/simulation-state-changed) show true))
      ;; Also start up our event-sending thread if it doesn't already exist.
      (swap! simulator-thread (fn [existing]
                                (or existing
                                    (let [thread (Thread. #(simulator-loop) "Shallow Playback Simulator")]
                                      (.setDaemon thread true)
                                      (.start thread)
                                      thread)))))
    (when (>= (count created) 6)
      ;; We have simulators for all six legal player numbers, so disable the menu that crates new ones.
      (seesaw/config! simulate-item :enabled? false))))


(defn build-simulator-action
  "Creates the menu action to open a shallow playback simulator window.
   Takes a reference to the menu item which invokes it, so that can be
  disabled when all possible device numbers already have simulators
  created for them."
  [simulate-item]
  (seesaw/action :handler (fn [_] (create-simulator simulate-item))
                 :name "New Playback Simulator"
                 :tip "Open a window that offers a shallow simulation of a CDJ playing a track."))

(defn close-all-simulators
  "Close any open simulator windows. Invoked when Beat Link Trigger is
  going online."
  []
  (doseq [simulator (vals @simulators)]
    ((:close-fn simulator))))
