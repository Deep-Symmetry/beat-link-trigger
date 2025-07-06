;; Try to open the MIDI input with which we receive data from the mixer. Edit the MIDI
;; device name in the first line if needed to match your Xone's MIDI input device name.
(let [mixer-device-name "XONE"]
  (try
    ;; Open a MIDI input with the specified name, which should be coming from the mixer.
    (let [device (midi/midi-in mixer-device-name)]
      (swap! globals assoc :mixer-midi device)

      ;; Arrange for xone-midi-received to be called whenever we get a message from the mixer.
      (midi/midi-handle-events device xone-midi-received))

    ;; Start the UI animation thread that draws the MIDI activity.
    (xone-animate-ui)  ;; <1>

    (catch Throwable t
      (timbre/warn t "Unable to open Xone MIDI device" mixer-device-name)
      (seesaw/invoke-later
        (seesaw/alert (:frame show)
                      (str "<html>The XoneOnAir show was unable to open a MIDI port matching \"" mixer-device-name
                           "\"<br>to receive messages from the mixer. Please make sure the mixer<br>"
                           "is connected, verify the device name that is showing up, and if needed<br>"
                           "edit the definition of <code>mixer-device-name</code> in the Came Online Expression."
                           "<br><br>Take Beat Link Trigger offline and back online to try again.")
                      :title "MIDI Device Not Found, On-Air Detection Disabled"
                      :type :error)))))