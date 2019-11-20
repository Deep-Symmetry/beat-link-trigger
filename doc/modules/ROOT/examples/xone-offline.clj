;; Close the mixer MIDI connection if we opened one.
(swap! globals
  (fn [current]
    (when-let [device (:mixer-midi current)]
      (.close (:transmitter device)))
    (dissoc current :mixer-midi)))

;; Update the MIDI status indicators to show we are now offline.
(xone-repaint-midi-indicators globals)  ;; <1>
