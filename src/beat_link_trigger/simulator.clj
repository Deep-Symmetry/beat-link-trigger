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
  (let [simulator   (get @simulators uuid)
        point       (.getPoint e)
        target-time (.getTimeForX component (.-x point))]
    (timbre/info "target-time" target-time)
    (swap! simulators assoc-in [uuid :time] target-time)
    ;; TODO: Send fake status update, or just wait until our loop runs?
    ))

(defn- set-simulation-data
  "Given the track menu choice, finds and records the appropriate data
  for simulation of that track, then lets any open shows know about
  the simulated presence of the track."
  [uuid choice]
  (let [data (util/data-for-simulation :entry (if (instance? SampleChoice choice)
                                                (:number choice)
                                                [(:file choice) (:signature choice)]))
        old  (get-in @simulators [uuid :track :preview])]
    (swap! simulators update uuid (fn [simulator]
                                    (-> simulator
                                        (assoc :track data)
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
        (seesaw/config! preview :center component)))
    (let [sig-update (SignatureUpdate. (get-in @simulators [uuid :player]) (:signature data))]
      (doseq [show (vals (su/get-open-shows))]
        (try
          ((requiring-resolve 'beat-link-trigger.show/update-player-item-signature) sig-update show)
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
                               :listen [:action (fn [e] (swap! simulators assoc-in [uuid :on-air]
                                                               (seesaw/value e)))])]
             [(seesaw/checkbox :id :master :text "Master"
                               :listen [:action (fn [e]
                                                  (let [master? (seesaw/value e)]
                                                    (swap! simulators assoc-in [uuid :master] master?)
                                                    (when master?
                                                      (doseq [simulator (vals @simulators)]
                                                        (when (not= uuid (:uuid simulator))
                                                          (seesaw/value! (seesaw/select (:frame simulator) [:#master])
                                                                         false))))))])
              "wrap"]
             ["Track:"]
             [(seesaw/combobox :id :track :model (track-menu-model)
                               :listen [:item-state-changed
                                        (fn [e]
                                          (let [chosen (seesaw/selection e)]
                                            (set-simulation-data uuid chosen)))])
              "span 3, wrap"]
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
                       (let [sig-update (SignatureUpdate. (get-in @simulators [uuid :player]) nil)
                             removed (swap! simulators dissoc uuid)]
                         (recompute-player-models)
                         (seesaw/config! simulate-item :enabled? true)
                         (doseq [show (vals (su/get-open-shows))]
                           (try
                             ((requiring-resolve 'beat-link-trigger.show/update-player-item-signature) sig-update show)
                             (catch Throwable t
                               (timbre/error t "Problem delivering simulated signature update to show" sig-update
                                             (:file show)))))
                         (when (empty? removed)
                           (doseq [show (vals (su/get-open-shows))]
                             ((requiring-resolve 'beat-link-trigger.show/simulation-state-changed) show false))))
                       true)
        created      (swap! simulators assoc uuid
                            {:uuid     uuid
                             :frame    root
                             :player   player
                             :sync     true
                             :master   false
                             :on-air   true
                             :playing  false
                             :pitch    0
                             :time     0
                             :close-fn close-fn})]
    (seesaw/config! root :content (build-simulator-panel uuid))
    (recompute-player-models)
    (set-simulation-data uuid (seesaw/selection (seesaw/select root [:#track])))
    (seesaw/listen root :window-closing (fn [_] (close-fn)))
    (seesaw/pack! root)
    (seesaw/show! root)
    (when (= (count created) 1)
      (doseq [show (vals (su/get-open-shows))]
        ((requiring-resolve 'beat-link-trigger.show/simulation-state-changed) show true)))
    (when (>= (count created) 6)
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
