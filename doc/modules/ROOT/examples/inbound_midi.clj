(ns inbound-midi
  "A namespace that implements functions for the integration example
  that supports inbound MIDI triggers for Beat Link Trigger."
  (:require [beat-link-trigger.show :as show]
            [beat-link-trigger.util :as util]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [seesaw.mig]
            [taoensso.timbre :as timbre]))

(defn close-input
  "Closes the MIDI input, if any, associated with the show.
  Used whenever a new device has been chosen, and as part of cleanup
  when the show is shutting down."
  [globals show]
  (swap! globals update :input
         (fn [old-input]  ; Close existing connection if there was one.
           (when old-input
             (try
               (.close (:transmitter old-input))
               (seesaw/invoke-later
                 (seesaw/config! (seesaw/select (:frame show) [:#learn])
                                 :enabled? false))
               (catch Exception e
                 (timbre/warn e "Problem closing old mapped MIDI input."))))
           nil)))

(defn chosen-output
  "Return the MIDI output to which feedback messages should be sent.
  a given button, opening it if this is the first time we are using
  it, or reusing it if we already opened it. Returns `nil` if the
  output can not currently be found (it was disconnected, or present
  in a loaded file but not on this system)."
  [show]
  (when-let [selection (or (get-in (show/user-data show)
                                   [:mappings :outputs])
                           (first (util/get-midi-outputs)))]
    (let [device-name (.full_name selection)]
      (or (get @util/opened-outputs device-name)
          (try
            (let [new-output
                  (midi/midi-out
                    (str "^" (java.util.regex.Pattern/quote device-name) "$"))]
              (swap! util/opened-outputs assoc device-name new-output)
              new-output)
            (catch IllegalArgumentException e
              ;; The chosen output is not currently available.
              (timbre/debug e "MIDI mapping using nonexisting MIDI output"
                            device-name))
            (catch Exception e  ; Some other problem opening the device.
              (timbre/error e "Problem opening device" device-name
                            "(treating as unavailable)")))))))

(defn send-feedback
  "Sends a feedback message to the configured output device, if
  appropriate (and it can be found). If `on` is truthy, feedback
  is turned on, otherwise it is turned off, using the velocities
  configured in the user interface."
  [on show]
  (let [mapping  (:mappings (show/user-data show))
        velocity (if on
                   (:feedback-on mapping 127)
                   (:feedback-off mapping 0))]
    (when-let [output (chosen-output show)]
      (when (= "Note" (:feedback-message mapping))
        (if (pos? velocity)
          (midi/midi-note-on output (:feedback-note mapping 1) velocity
                             (dec (:feedback-channel mapping 1)))
          (midi/midi-note-off output (:feedback-note mapping 1)
                              (dec (:feedback-channel mapping 1)))))
      (when (= "CC" (:feedback-message mapping))
        (midi/midi-control output (:feedback-note mapping 1) velocity
                                (dec (:feedback-channel mapping 1)))))))

(defn update-devices
  "Updates the MIDI input menus and outputs to reflect a change in the
  MIDI environment. If a device which disappeared was chosen in a menu,
  however, it is kept in the menu and kept selected, so that it can be
  reconnected automatically if and when the device reappears."
  [show]
  (when-let [frame (:frame show)]
    (let [new-inputs  (util/get-midi-inputs)
          input-menu  (seesaw/select frame [:#inputs])
          old-input   (seesaw/selection input-menu)
          new-outputs (util/get-midi-outputs)
          output-menu (seesaw/select frame [:#outputs])
          old-output  (seesaw/selection output-menu)]
      (seesaw/config! input-menu :model
                      (concat new-inputs
                              (when-not ((set new-inputs) old-input)
                                        [old-input])))
      (seesaw/selection! input-menu old-input)
      (seesaw/config! output-menu :model
                      (concat new-outputs
                              (when-not ((set new-outputs) old-output)
                                        [old-output])))
      (seesaw/selection! output-menu old-output))))

(defn attach-midi-handler
  "Installs a function to be called whenever there is MIDI input from
  a newly selected device, to check whether it is mapped to our action,
  or if a mapping is currently being learned."
  [globals show new-input]
  (let [handler (fn [message]
    (let [learned   (atom false) ; So we don't react to something just learned.
          frame     (:frame show)
          indicator (when frame (seesaw/select frame [:#midi-received]))]
      ;; First handle "Learn" mode if that is active.
      (when frame
        (let [learn     (seesaw/select frame [:#learn])]
          (when (and (seesaw/value learn)
                     (#{:note-on :control-change} (:status message)))
            (seesaw/invoke-later
             (let [note? (= :note-on (:status message))]
               (seesaw/value! (seesaw/select frame [:#message])
                              (if note? "Note" "CC"))
               (seesaw/value! (seesaw/select frame [:#feedback-message])
                              (if note? "Note" "CC")))
             (seesaw/value! (seesaw/select frame [:#note])
                            (long (:note message)))
             (seesaw/value! (seesaw/select frame [:#feedback-note])
                            (long (:note message)))
             (seesaw/value! (seesaw/select frame [:#channel])
                            (long (inc (:channel message))))
             (seesaw/value! (seesaw/select frame [:#feedback-channel])
                            (long (inc (:channel message))))
             (seesaw/value! learn false)
             (swap! globals assoc :midi-received (System/currentTimeMillis))
             (seesaw/repaint! indicator)
             (reset! learned true)))))
      ;; Then, if we didn't just learn this message, see if it matches
      ;; the button mappings, and if so, toggle it.
      (when-not @learned
        (let [mapping (:mappings (show/user-data show))
              action (:action-callback @globals)]
          (when (and (or (and (#{:note-on :note-off} (:status message))
                              (= "Note" (:message mapping)))
                         (and (= :control-change (:status message))
                              (= "CC" (:message mapping))))
                     (= (:note message) (:note mapping 1))
                     (= (inc (:channel message)) (:channel mapping 1)))
            (seesaw/invoke-later
              (swap! globals assoc :midi-received (System/currentTimeMillis)
              (seesaw/repaint! indicator)
              (when action
                (try (action globals show message)
                  (catch Throwable t
                    (timbre/error t "inbound MIDI action callback")))))))))))]
    (midi/midi-handle-events new-input handler)))

(defn animate-ui
  "Runs a background thread to support fading out the MIDI indicator
  over time. Also periodically sends configured feedback messages if
  the callback function says it is time to."
  [globals show]
  (future
    (try
      (loop []
        (seesaw/repaint! (seesaw/select (:frame show) [:#midi-received]))
        (let [callback (:feedback-callback @globals)
              state    (when callback (try (boolean (callback globals show))
                                         (catch Throwable t
                                           (timbre/error t "Inbound MIDI feedback callback"))))]
          (when (not= state (:last-feedback @globals))
            (send-feedback state show)
            (swap! globals assoc :last-feedback state)))
        (Thread/sleep 33)
        (when-not (:shutdown @globals) (recur)))
      (catch Throwable t
        (timbre/error t "Inbound MIDI animate UI loop crashed")))))

(defn change-input
  "Record the MIDI input from which messages should be received
  by storing it as that show's user data. Opens a private
  connection to the input so we can add our own event handler that
  will stop being called when we close this connetion. Stores `nil`
  if the input can not currently be found (it was disconnected, or
  present in a loaded file but not on this system). Also sets
  the enabled state of the Learn button to reflect whether we are
  now online, and schedules a delayed feedback send for when the
  new device is likely ready to handle it."
  [globals show]
  (close-input globals show)
  (when-let [selection (get-in (show/user-data show)
                       [:mappings :inputs])]
    (let [device-name (.full_name selection)]
      (try
        (let [new-input (midi/midi-in
                         (java.util.regex.Pattern/quote device-name))]
          (attach-midi-handler globals show new-input)
          (swap! globals assoc :input new-input)
          (seesaw/invoke-later
            (seesaw/config! (seesaw/select (:frame show) [:#learn])
                            :enabled? true))
          (future  ; Send feedback message to new device in 2 seconds.
            (Thread/sleep 2000)
            (swap! globals dissoc :last-feedback)))
        (catch IllegalArgumentException e
          ;; The chosen output is not currently available
          (timbre/debug e "MIDI mapping using nonexisting MIDI input"
                        device-name))
        (catch Exception e  ; Some other problem opening the device
          (timbre/error e "Problem opening device" device-name
                        "(treating as unavailable)"))))))

(defn- activity-color
  "Calculates the color to be used to draw the center of a MIDI activity
  indicator, based on how recently activity was seen. Fades from fully
  opaque blue to fully transparent over about half a second."
  [timestamp]
  (let [opacity (if timestamp
                  (let [elapsed (- (System/currentTimeMillis) timestamp)]
                    (max 0 (- 255 (int (/ elapsed 2)))))
                  0)]
    (java.awt.Color. 0 127 255 opacity)))

(def midi-blue
  "A light blue color used for drawing the MIDI indicator rings."
  (java.awt.Color. 0 127 255))

(defn- paint-midi-state
  "Draws an indication of MIDI activity matching our configuration,
  including whether the MIDI connection is online, and a blue dot for
  half a second after the receipt of each message. `c` is the canvas
  in which we are doing our drawing, and `g` is the graphics context."
  [globals c g]
  (let [w       (double (seesaw/width c))
        h       (double (seesaw/height c))
        outline (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        online? (:input @globals)]
    (.setRenderingHint g java.awt.RenderingHints/KEY_ANTIALIASING
                       java.awt.RenderingHints/VALUE_ANTIALIAS_ON)
    (.setPaint g (if online?
                   (activity-color (:midi-received @globals))
                   java.awt.Color/lightGray))
    (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0)))

    ;; Draw the outer circle that reflects the online state.
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if online? midi-blue java.awt.Color/red))
    (.draw g outline)
    (when-not online?
      (swap! globals dissoc :last-feedback)
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(defn build-ui
  "Creates the user interface for the show, allowing the MIDI
  mapping to be established."
  [globals show]
  (let [data    (:mappings (show/user-data show))
        save    (fn [e] (show/swap-user-data!
                         show assoc-in
                         [:mappings (seesaw/id-of e)]
                         (seesaw/value e)))
        enable  (fn [e & selectors]
                  (let [panel (.getParent (seesaw/to-widget e))]
                    (seesaw/config! (map #(seesaw/select panel [%])
                                         selectors)
                                    :enabled? (not= "None" (seesaw/value e)))))
        outputs (util/get-midi-outputs)
        inputs  (util/get-midi-inputs)
        panel   (seesaw.mig/mig-panel
                 :items [["Description:" "alignx trailing"]
                         [(seesaw/text :id :comment
                                       :paint (partial util/paint-placeholder "Comment")
                                       :text (:comment (show/user-data show) "")
                                       :listen [:document (fn [e]
                                                            (show/swap-user-data!
                                                             show assoc :comment
                                                             (seesaw/text e)))])
                          "growx, spanx, wrap"]

                         ["MIDI Input:" "alignx trailing"]
                         [(seesaw/combobox :id :inputs
                                           :model (concat inputs
                                                          ;; Add selection even if not available.
                                                          (when (and (:inputs data)
                                                                     (not ((set inputs)
                                                                           (:inputs data))))
                                                            [(:inputs data)]))
                                           :listen [:item-state-changed
                                                    (fn [e]
                                                      (save e)
                                                      (change-input globals show))])]
                         ["Message:" "gap unrelated"]
                         [(seesaw/combobox :id :message
                                           :model ["None" "Note" "CC"]
                                           :listen [:item-state-changed
                                                    (fn [e]
                                                      (save e)
                                                      (enable e :#note :#channel))])]
                         [(seesaw/spinner :id :note
                                          :model (seesaw/spinner-model 1 :from 1 :to 127)
                                          :listen [:state-changed save] :enabled? false)]
                         ["Channel:" "gap unrelated"]
                         [(seesaw/spinner :id :channel
                                          :model (seesaw/spinner-model 1 :from 1 :to 16)
                                          :listen [:state-changed save] :enabled? false)]
                         [(seesaw/toggle :id :learn :text "Learn" :enabled? false)
                          "gap unrelated, span 2"]
                         [(seesaw/canvas :id :midi-received :size [18 :by 18] :opaque? false
                                         :tip "Lights when MIDI received within half a second"
                                         :paint (partial paint-midi-state globals))
                          "wrap unrelated"]

                         ["MIDI Output:" "alignx trailing"]
                         [(seesaw/combobox :id :outputs
                                           :model (concat outputs
                                                          ;; Add selection even if not available.
                                                          (when (and (:outputs data)
                                                                     (not ((set outputs) (:outputs data))))
                                                            [(:outputs data)]))
                                           :listen [:item-state-changed save])]
                         ["Message:" "gap unrelated"]
                         [(seesaw/combobox :id :feedback-message
                                           :model ["None" "Note" "CC"]
                                           :listen [:item-state-changed
                                                    (fn [e]
                                                      (save e)
                                                      (enable e :#feedback-note
                                                              :#feedback-channel
                                                              :#feedback-on
                                                              :#feedback-off))])]
                         [(seesaw/spinner :id :feedback-note
                                          :model (seesaw/spinner-model 1 :from 1 :to 127)
                                          :listen [:state-changed save] :enabled? false)]
                         ["Channel:" "gap unrelated"]
                         [(seesaw/spinner :id :feedback-channel
                                          :model (seesaw/spinner-model 1 :from 1 :to 16)
                                          :listen [:state-changed save] :enabled? false)]
                         ["On:" "gap unrelated"]
                         [(seesaw/spinner :id :feedback-on
                                          :model (seesaw/spinner-model 127 :from 0 :to 127)
                                          :listen [:state-changed save] :enabled? false)]
                         ["Off:" "gap unrelated"]
                         [(seesaw/spinner :id :feedback-off
                                          :model (seesaw/spinner-model 0 :from 0 :to 127)
                                          :listen [:state-changed save] :enabled? false)]])]
    (seesaw/value! panel data)))
