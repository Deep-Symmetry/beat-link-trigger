(def xone-left-channels
  "The set of channels assigned to the left side of the cross-fader"
  #{1 2}) ;; <1>

(def xone-right-channels
  "The set of channels assigned to the right side of the cross-fader"
  #{3 4}) ;; <2>

(def xone-min-on-air-value
  "The MIDI value of the fader must be this far from zero (or, in the
  case of the cross-fader, from the opposite end of travel), for a
  channel to be considered on the air."
  2) ;; <3>

(defn xone-on-air-via-channel-faders  ;; <4>
  "Returns the channel numbers that are currently on the air based
  solely on the known positions of the channel faders, given the
  updated state of the show globals following a MIDI event."
  [state]
  (set (filter (fn [num]
                 (>= (get state (keyword (str "channel-" num)))
                     xone-min-on-air-value))
               (range 1 5))))

(defn xone-blocked-by-cross-fader
  "Returns set of the channel numbers that are currently muted because
  of the cross-fader position, if any, given the updated state of the
  show globals following a MIDI event."
  [state]
  (let [fader-position (:cross-fader state)]
    (cond
      (< fader-position xone-min-on-air-value) xone-right-channels
      (< (- 127 fader-position) xone-min-on-air-value) xone-left-channels
      :else #{})))

(defn xone-midi-received
  "This function is called with each MIDI message received from the mixer,
  and also given access to the show globals so that it can update the known
  mixer state and determine the resulting on-air channels."
  [globals msg]
  ;; Default Xone configuration sends faders as CC on MIDI Channel 16.
  (when (and (= :control-change (:command msg)) (= 15 (:channel msg)))
    ;; Check if it is one of the faders we care about.
    (when-let [recognized (get {0 :channel-1
                                1 :channel-2
                                2 :channel-3
                                3 :channel-4
                                4 :cross-fader}
                               (long (:note msg)))]
      ;; It is, so update known mixer state with the current fader value, and
      ;; calculate which channels are now on-air.
      (let [state  (swap! globals assoc recognized (:velocity msg))
            on-air (clojure.set/difference
                    (xone-on-air-via-channel-faders state)
                    (xone-blocked-by-cross-fader state))]

        ;; If the Virtual CDJ is running (Beat Link Trigger itself is online),
        ;; send a Channels On Air message to update the actual CDJ's state.
        ;; We need to convert the values to integers, rather than the longs
        ;; that Clojure uses natively, to be compatible with the Beat Link API.
        (when (.isRunning virtual-cdj)
          (.sendOnAirCommand virtual-cdj (set (map int on-air))))))))
