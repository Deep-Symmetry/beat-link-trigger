(ns beat-link-trigger.show-simulator
  "Provides shallow playback simulation for shows when BLT is offline."
  (:require [beat-link-trigger.util :as util]
            [beat-link-trigger.show-util :as su :refer [latest-show swap-show!]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame]))

;; Used to represent one of the sample tracks available for simulation
(defrecord SampleChoice [number]
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
  simulator in the show."
  [show]
  (let [used (set (map :player (vals (:simulators show))))]
    (first (remove used (map inc (range 6))))))

(defn- player-menu-model
  "Returns the available player numbers this simulator can choose."
  [show uuid]
  (let [show      (latest-show show)
        simulator (get-in show [:simulators uuid])
        used      (set (map :player (vals (:simulators show))))]
    (sort (set/union (set (remove used (map inc (range 6)))) #{(:player simulator)}))))

(defn- recompute-player-models
  "Updates the player combo-boxes of all open windows to reflect the
  current state of the other windows."
  [show]
  (doseq [simulator (vals (:simulators (latest-show show)))]
    (let [combo   (seesaw/select (:frame simulator) [:#player])
          current (seesaw/selection combo)]
      (swap-show! show assoc-in [:simulators (:uuid simulator) :adjusting] true)
      (seesaw/config! combo :model (player-menu-model show (:uuid simulator)))
      (seesaw/selection! combo current)
      (swap-show! show update-in [:simulators (:uuid simulator)] dissoc :adjusting))))

(defn- track-menu-model
  "Returns the available tracks this simulator can choose."
  [show]
  (let [tracks (vals (:tracks (latest-show show)))]
    (sort-by #(.toString %)
             (if (empty? tracks)
               (map #(SampleChoice. (inc %)) (range 2))
               (map #(TrackChoice. (:file show) (:signature %)) tracks)))))

(defn- set-simulation-data
  "Given the track menu choice, finds and records the appropriate data
  for simulation of that track."
  [show uuid choice]
  (let [data (util/data-for-simulation :entry (if (instance? SampleChoice choice)
                                                (:number choice)
                                                [(:file choice) (:signature choice)])
                                       :include-preview? true)]
    (swap-show! show assoc-in [:simulators uuid :track] data)))

(defn recompute-track-models
  "Updates the track combo-boxes of all open windows to reflect the
  addition or removal of a track from the show."
  [show]
  (let [show       (latest-show show)
        signatures (set (keys (:tracks show)))]
    (doseq [simulator (vals (:simulators show))]
      (let [combo   (seesaw/select (:frame simulator) [:#track])
            current (seesaw/selection combo)
            model   (track-menu-model show)
            lost    (or (and (instance? TrackChoice current) (not (signatures (:signature current))))
                        (and (instance? SampleChoice current) (seq signatures)))]
        (when-not lost (swap-show! show assoc-in [:simulators (:uuid simulator) :adjusting] true))
        (seesaw/config! combo :model model)
        (if lost
          (set-simulation-data show (:uuid simulator) (first model))
          (seesaw/selection! combo current))
        (swap-show! show update-in [:simulators (:uuid simulator)] dissoc :adjusting)))))

(defn build-simulator-panel
  "Creates the UI of the simulator window, once the show has its basic
  configuration added."
  [show uuid]
  (let [simulator (get-in (latest-show show) [:simulators uuid])]
    (mig/mig-panel
     :items [["Player:"]
             [(seesaw/combobox :id :player :model (player-menu-model show uuid)
                               :selected-item (:player simulator)
                               :listen [:item-state-changed
                                        (fn [e]
                                          (let [chosen (seesaw/selection e)]
                                            (swap-show! show assoc-in [:simulators uuid :player] chosen)
                                            (when-not (get-in (latest-show show) [:simulators uuid :adjusting])
                                              (recompute-player-models show))))])]
             [(seesaw/checkbox :id :on-air :text "On-Air" :selected? true
                               :listen [:action (fn [e] (swap-show! show assoc-in [:simulators uuid :on-air]
                                                                    (seesaw/value e)))])]
             [(seesaw/checkbox :id :master :text "Master"
                               :listen [:action (fn [e]
                                                  (let [master? (seesaw/value e)]
                                                    (swap-show! show assoc-in [:simulators uuid :master] master?)
                                                    (when master?
                                                      (doseq [simulator (vals (:simulators (latest-show show)))]
                                                        (when (not= uuid (:uuid simulator))
                                                          (seesaw/value! (seesaw/select (:frame simulator) [:#master])
                                                                         false))))))])
              "wrap"]
             ["Track:"]
             [(seesaw/combobox :id :track :model (track-menu-model show)
                               :listen [:item-state-changed
                                        (fn [e]
                                          (let [chosen (seesaw/selection e)]
                                            (set-simulation-data show uuid chosen)))])
              "span 3"]])))

(defn- create-simulator
  "Creates a new shallow playback simulator for the show. Must be called
  with an up-to-date view of the show."
  [show]
  (let [uuid         (java.util.UUID/randomUUID)
        ^JFrame root (seesaw/frame :title (str "Simulator for " (util/trim-extension (.getPath (:file show))))
                                   :on-close :nothing)
        player       (choose-simulator-player show)
        close-fn     (fn []
                       (.dispose root)
                       (swap-show! show update :simulators dissoc uuid)
                       (recompute-player-models show)
                       (seesaw/config! (:simulate-item show) :enabled? true)
                       true)]
    (swap-show! show assoc-in [:simulators uuid]
                {:uuid     uuid
                 :show     (:file show)
                 :frame    root
                 :player   player
                 :sync     true
                 :master   false
                 :on-air   true
                 :playing  false
                 :pitch    0
                 :time     0
                 :close-fn close-fn})
    (seesaw/config! root :content (build-simulator-panel show uuid))
    (recompute-player-models show)
    (set-simulation-data show uuid (seesaw/selection (seesaw/select root [:#track])))
    (seesaw/listen root :window-closing (fn [_] (close-fn)))
    (seesaw/pack! root)
    (seesaw/show! root))
  (when (>= (count (:simulators show)) 5)  ; We're adding one, which gets us up to six.
    (seesaw/config! (:simulate-item show) :enabled? false)))



(defn build-simulator-action
  "Creates the menu action to open a shallow playback simulator window."
  [show]
  (seesaw/action :handler (fn [_] (create-simulator (latest-show show)))
                 :name "New Playback Simulator"
                 :tip "Open a window that offers a shallow simulation of a CDJ playing a track."))
