(def xone-min-on-air-value  ;; <1>
  "The MIDI value of the fader must be this far from zero (or, in the
  case of the cross-fader, from the opposite end of travel), for a
  channel to be considered on the air."
  2)

(defn xone-on-air-via-channel-faders  ;; <2>
  "Returns the channel numbers that are currently on the air based
  solely on the known positions of the channel faders, given the
  updated state of the show user data following a MIDI event."
  [state]
  (set (filter (fn [i]
                 (>= (get state (keyword (str "channel-" i)) 0) xone-min-on-air-value))
               (range 1 5))))

(defn xone-blocked-by-cross-fader  ;; <3>
  "Returns set of the channel numbers that are currently muted because
  of the cross-fader position, if any, given the updated state of the
  show user data following a MIDI event."
  [state]
  (let [fader-position (:cross-fader state 0)]
    (cond
      (< fader-position xone-min-on-air-value) (:y-channels state #{3 4})
      (< (- 127 fader-position) xone-min-on-air-value) (:x-channels state #{1 2})
      :else #{})))

(defn- xone-recompute-on-air  ;; <1>
  "Recalculates the current set of on-air channels, given an updated
  state of channel fader and cross-fader positions and channel x-y
  assignments. Updates the user interface and the CDJs accordingly."
  [state globals]
  (let [on-air (clojure.set/difference (xone-on-air-via-channel-faders state)
                                       (xone-blocked-by-cross-fader state))]
    ;; Store the computed on-air states and update the UI to reflect them.
    (swap! globals assoc :on-air on-air)
    (seesaw/repaint! (:channel-on-air @globals))

    ;; If the Virtual CDJ is running (Beat Link Trigger itself is online),
    ;; send a Channels On Air message to update the actual CDJ's state.
    ;; (We need to convert the values to integers, rather than the longs
    ;; that Clojure uses natively, to be compatible with the Beat Link API.)
    (when (.isRunning virtual-cdj)
      (.sendOnAirCommand virtual-cdj (set (map int on-air))))))

(defn- xone-build-channel-labels  ;; <2>
  "Creates the vector of four labels identifying the channel faders."
  []
  (vec (for [i (range 4)]
         (vec (concat [(seesaw/label (str (inc i))) (str "push 1" (when (= i 3) ", wrap"))])))))

(defn xone-react-to-xy-change  ;; <1>
  "Handles a change in one of the tiny XY slider values, with `channel`
  holding the channel number controlled by the slider, and `v` is the
  new position."
  [show globals channel v]
  (let [current (show/user-data show)        ; Find the current state of the X and Y channel assignments.
        x       (:x-channels current #{1 2}) ; Apply the default assignments if they haven't yet been stored.
        y       (:y-channels current #{3 4})
        new-x   (if (neg? v) (conj x channel) (disj x channel))  ; Update assignments based on the new slider value.
        new-y   (if (pos? v) (conj y channel) (disj y channel))
        state   (show/swap-user-data! show assoc :x-channels new-x :y-channels new-y)]
    (xone-recompute-on-air state globals)))

(defn xone-build-xy-sliders  ;; <2>
  "Creates the vector of four tiny horizontal sliders that specify which
  side of the cross fader, if any, each channel fader is assigned."
  [show globals]
  (apply concat
         (for [i (range 1 5)]
           (let [state       (show/user-data show)
                 saved-value (cond  ; See if the show has a saved position for this slider.
                               ((:x-channels state #{1 2}) i) -1 ; Apply default positions if not found.
                               ((:y-channels state #{3 4}) i) 1
                               :else 0)]
             [["X" "split 3, gapright 0"]
              [(seesaw/slider :orientation :horizontal ; Build the slider UI object itself.
                              :min -1
                              :max 1
                              :value saved-value
                              :listen [:state-changed #(xone-react-to-xy-change
                                                        show globals i (seesaw/value %))])
               "width 45, gapright 0"]
              ["Y" (when (= i 4) "wrap")]]))))

(defn- xone-react-to-slider-change  ;; <1>
  "Handles a change in slider value, where `k` identifies the slider and
  `v` is the new value."
  [show globals k v]
  (xone-recompute-on-air (show/swap-user-data! show assoc k v) globals))

(defn- xone-build-channel-sliders  ;; <2>
  "Creates the vector of four vertical sliders that represent the mixer
  channel faders."
  [show globals]
  (vec (for [i (range 4)]
         (let [k (keyword (str "channel-" (inc i)))]  ; Build the keyword identifying the slider.
           (seesaw/slider :orientation :vertical      ; Build the slider UI object itself.
                          :min 0
                          :max 127
                          :value (k (show/user-data show) 63)  ; Restore saved value, if one exists.
                          :listen [:state-changed #(xone-react-to-slider-change
                                                    show globals k (seesaw/value %))])))))

(defn xone-wrap-channel-element
  "Creates the MIG Layout decription for one of the channel elements,
  given its index. We want to wrap to the next line after the fourth
  one."
  [index element]
  [element (when (= 3 index) "wrap")])

(def xone-near-black
  "A gray that is close to black."
  (java.awt.Color. 20 20 20))

(defn- xone-paint-on-air-state
  "Draws an indication of whether a channel is considered to currently
  be on the air based on the mixer fader states. Arguments are the
  show globals, channel number, the component being drawn, and the
  graphics context in which drawing is taking place."
  [globals channel c g]
  (let [w       (double (seesaw/width c))
        center  (/ w 2.0)
        h       (double (seesaw/height c))
        outline (java.awt.geom.RoundRectangle2D$Double. 1.0 1.0 (- w 2.0) (- h 2.0) 10.0 10.0)]
    (.setRenderingHint g java.awt.RenderingHints/KEY_ANTIALIASING java.awt.RenderingHints/VALUE_ANTIALIAS_ON)
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if ((:on-air @globals #{}) channel) java.awt.Color/RED xone-near-black))
    (.draw g outline)
    (.setFont g (util/get-display-font :teko java.awt.Font/BOLD 18))
    (let [frc    (.getFontRenderContext g)
          bounds (.getStringBounds (.getFont g) "On Air" frc)]
      (.drawString g "On Air" (float (- center (/ (.getWidth bounds) 2.0))) (float (- h 4.0))))))

(defn- xone-build-channel-on-air-indicators
  "Builds the vector of canvases that draw the on-air states of the
  mixer channels."
  [globals]
  (vec (for [i (range 4)]
         (seesaw/canvas :size [55 :by 20] :opaque? false :paint (partial xone-paint-on-air-state globals (inc i))))))

(defn- midi-activity-color
  "Calculates the color to be used to draw the center of a MIDI activity
  indicator, based on how recently activity was seen. Fades from fully
  opaque blue to fully transparent over about half a second."
  [timestamp]
  (let [opacity (if timestamp
                  (let [elapsed (- (System/currentTimeMillis) timestamp)]
                    (max 0 (- 255 (int (/ elapsed 2)))))
                  0)]
    (java.awt.Color. 0 127 255 opacity)))

(def xone-midi-blue
  "A light blue color used for drawing the MIDI indicator rings."
  (java.awt.Color. 0 127 255))

(defn- xone-paint-midi-state
  "Draws an indication of MIDI activity for a particular slider,
  including whether the MIDI connection is online, and a grblueeen dot for
  half a second after the receipt of each message. `k` is the keyword
  associated with the slider, so we can check when the last message
  was received from it. `c` is the canvas in which we are doing our
  drawing, and `g` is the graphics context."
  [show globals k c g]
  (let [w       (double (seesaw/width c))
        h       (double (seesaw/height c))
        outline (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        online? (:mixer-midi @globals)]

    (.setRenderingHint g java.awt.RenderingHints/KEY_ANTIALIASING java.awt.RenderingHints/VALUE_ANTIALIAS_ON)

    (.setPaint g (if online?
                   (midi-activity-color (get-in @globals [:midi-timestamps k]))
                   java.awt.Color/lightGray))
    (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0)))

    ;; Draw the outer circle that reflects the online state.
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if online? xone-midi-blue java.awt.Color/red))
    (.draw g outline)
    (when-not online?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(defn- xone-build-midi-indicator
  "Bulds a canvas that draws the MIDI state of a particular slider. `k`
  is the keyword that identifies the slider in the MIDI timestamps
  map, so it can tell when there has been recent activity."
  [show globals k]
  (seesaw/canvas :size [18 :by 18] :opaque? false
                 :tip "Outer ring shows online state, inner light when MIDI received within half a second."
                 :paint (partial xone-paint-midi-state show globals k)))

(defn- xone-build-channel-midi-indicators
  "Builds the vector of canvases that draw the MIDI states of the
  channel sliders."
  [show globals]
  (vec (for [i (range 4)]
         (xone-build-midi-indicator show globals (keyword (str "channel-" (inc i)))))))

(defn- xone-repaint-midi-indicators
  "Tells all the MIDI indicators to repaint themselves because we have
  received interesting MIDI data or because our timer thread is
  telling us it is time for a UI update.
  called)."
  [globals]
  (seesaw/repaint! (:channel-indicators @globals))
  (seesaw/repaint! (:cross-indicator @globals)))

(defn xone-build-ui
  "Creates the custom user interface for the Xone:96 on-air integration.
  Displays the known fader positions, flashes indicators when MIDI
  messages are received (and makes clear when the MIDI connection is
  offline), and displays the calculated on-air results."
  [show globals]
  (let [xy-sliders      (xone-build-xy-sliders show globals)
        channel-sliders (xone-build-channel-sliders show globals)
        channel-midi    (xone-build-channel-midi-indicators show globals)
        channel-on-air  (xone-build-channel-on-air-indicators globals)
        cross-midi      (xone-build-midi-indicator show globals :cross-fader)
        cross-fader     (seesaw/slider :orientation :horizontal :min 0 :max 127
                                       :value (:cross-fader (show/user-data show) 63)
                                       :listen [:state-changed #(xone-react-to-slider-change
                                                                 show globals :cross-fader (seesaw/value %))])
        panel           (seesaw.mig/mig-panel :background "#aad"
                                              :constraints ["" "[center][center][center][center]"]
                                              :items (concat
                                                      (map-indexed xone-wrap-channel-element channel-on-air)
                                                      [["" "wrap 20"]]
                                                      xy-sliders
                                                      (xone-build-channel-labels)
                                                      (map-indexed xone-wrap-channel-element channel-midi)
                                                      (map-indexed xone-wrap-channel-element channel-sliders)
                                                      [[cross-midi "span 4, wrap"]
                                                       ["X" "span 4, split 3"]
                                                       [cross-fader]
                                                       ["Y" "wrap"]]))]
    (swap! globals assoc :channel-on-air channel-on-air :channel-indicators channel-midi
           :channel-sliders channel-sliders :cross-slider cross-fader :cross-indicator cross-midi)
    panel))

(defn- xone-update-slider
  "Update one of the sliders to a new position we have received from the
  mixer. `k` is the keyword identifying the slider, and `v` is the new
  position value."
  [globals k v]
  (let [slider (case k
                 :cross-fader (:cross-slider @globals)
                 :channel-1 (nth (:channel-sliders @globals) 0)
                 :channel-2 (nth (:channel-sliders @globals) 1)
                 :channel-3 (nth (:channel-sliders @globals) 2)
                 :channel-4 (nth (:channel-sliders @globals) 3))]
    (seesaw/value! slider v)))

(defn xone-midi-received
  "This function is called with each MIDI message received from the mixer,
  and also given access to the show globals so that it can update the known
  mixer state and determine the resulting on-air channels."
  [show globals msg]
  (try
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
        (let [state  (show/swap-user-data! show assoc recognized (:velocity msg))
              on-air (clojure.set/difference (xone-on-air-via-channel-faders state)
                                             (xone-blocked-by-cross-fader state))]

          ;; Update the UI slider to reflect the new fader position.
          (xone-update-slider globals recognized (:velocity msg))

          ;; Update our MIDI indicators to show the user we have received MIDI data.
          (swap! globals assoc-in [:midi-timestamps recognized] (System/currentTimeMillis))
          (xone-repaint-midi-indicators globals))))
    (catch Throwable t
      (timbre/error t "Problem handling MIDI event from Xone:96 mixer"))))

(defn- xone-animate-ui
  "Runs a background thread to support fading out the MIDI indicators
  over time. Also periodically sends device-on-air updates like a DJM
  does even when there is no change. Exits when the Going Offline
  expression closes the mixer connection."
  [show globals]
  (future
    (loop []
      (xone-repaint-midi-indicators globals)
      (when (.isRunning virtual-cdj)  ; Send on-air updates every half second.
        (let [last-sent-on-air (:on-air-timestamp @globals 0)
              now              (System/currentTimeMillis)]
          (when (> (- now last-sent-on-air) 500)
            (.sendOnAirCommand virtual-cdj (set (map int (:on-air @globals))))
            (swap! globals assoc :on-air-timestamp now))))
      (Thread/sleep 33)  ; Pause for about 1/30 of a second, for 30 FPS animations.
      (when (:mixer-midi @globals)
        (recur)))))  ; If we are still online, keep going.
