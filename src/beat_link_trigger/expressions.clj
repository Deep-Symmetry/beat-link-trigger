(ns beat-link-trigger.expressions
  "A namespace in which user-entered custom expressions will be
  evaluated, which provides support for making them easier to write."
  (:require [overtone.midi :as midi]
            [overtone.osc :as osc]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Beat CdjStatus MixerStatus Util]))

(defonce ^{:doc "Holds global variables shared between user expressions."}
  globals (atom {}))

(def convenience-bindings
  "The symbols which can be used inside a user expression, along with
  the expression that will be used to automatically bind that symbol
  if it is used in the expression."
  '{at-end?                     (.isAtEnd status)
    beat-number                 (.getBeatNumber status)
    beat-within-bar             (.getBeatWithinBar status)
    beat-within-bar-meaningful? (.isBeatWithinBarMeaningful status)
    busy?                       (.isBusy status)
    cue-countdown               (.getCueCountdown status)
    cue-countdown-display       (.formatCueCountdown status)
    cued?                       (.isCued status)
    effective-tempo             (.getEffectiveTempo status)
    looping?                    (.isLooping status)
    device-name                 (.getDeviceName status)
    device-number               (.getDeviceNumber status)
    on-air?                     (.isOnAir status)
    paused?                     (.isPaused status)
    pitch-multiplier            (Util/pitchToMultiplier (.getPitch status))
    pitch-percent               (Util/pitchToPercentage (.getPitch status))
    playing?                    (.isPlaying status)
    raw-pitch                   (.getPitch status)
    synced?                     (.isSynced status)
    tempo-master?               (.isTempoMaster status)
    timestamp                   (.getTimestamp status)
    track-number                (.getTrackNumber status)
    track-bpm                   (.getBpm status)})

(defn- gather-convenience-bindings
  "Scans a Clojure form for any occurrences of the convenience symbols
  we know how to make available with useful values, and returns the
  symbols found along with the expressions to which they should be
  bound."
  [form]
  (let [result (atom (sorted-map))]
    (clojure.walk/postwalk (fn [elem]
                             (when-let [binding (get convenience-bindings elem)]
                               (swap! result assoc elem binding)))
                           form)
    (apply concat (seq @result))))

(defmacro ^:private wrap-user-expression
  "Takes a Clojure form containing a user expression, adds bindings for
  any convenience symbols that were found in it, and builds a function
  that accepts a status object, binds the convenience symbols based on
  the status, and returns the results of evaluating the user
  expression in that context."
  [body]
  (let [bindings (gather-convenience-bindings body)]
    `(fn [~'status] ~(if (seq bindings)
                       `(let [~@bindings]
                          ~body)
                       body))))

(defn build-user-expression
  "Takes a string that a user has entered as a custom expression, adds
  bindings for any convenience symbols that were found in it, and
  builds a function that accepts a status object, binds the
  convenience symbols based on the status, and returns the results of
  evaluating the user expression in that context."
  [expr]
  (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
    (eval (read-string (str "(wrap-user-expression (do " expr "\n))")))))
