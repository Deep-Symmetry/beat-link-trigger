(ns beat-link-trigger.expressions
  "Support for compiling and invoking user expressions for the triggers
  window and show windows."
  (:require [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.walk])
  (:import [org.deepsymmetry.beatlink DeviceUpdate Beat CdjStatus MixerStatus]))


(def convenience-bindings
  "Identifies symbols which can be used inside a user expression when
  processing a particular kind of device update, along with the
  expression that will be used to automatically bind that symbol if it
  is used in the expression, and the documentation to show the user
  what the binding is for."
  {DeviceUpdate {:bindings {'address            {:code
                                                 '(.getAddress status)
                                                 :doc "The address of the device from which this update was received."}
                            'bar-meaningful?    {:code '(.isBeatWithinBarMeaningful status)
                                                 :doc
                                                 "Will be <code>true</code> if this update is coming from a device where <code>beat-within-bar</code> can reasonably be expected to have musical significance, because it respects the way a track was configured within rekordbox."}
                            'beat?              {:code '(instance? Beat status)
                                                 :doc  "Will be <code>true</code> if this update is announcing a new beat."}
                            'beat-within-bar    {:code '(.getBeatWithinBar status)
                                                 :doc  "The position within a measure of music at which the most recent beat fell (a value from 1 to 4, where 1 represents the down beat). This value will be accurate for players when the track was properly configured within rekordbox (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize down beats with players, however, so this value is meaningless when coming from the mixer. The usefulness of this value can be checked with <code>bar-meaningful?</code>"}
                            'cdj?               {:code '(or (instance? CdjStatus status)
                                                            (and (instance? Beat status)
                                                                 (< (.getDeviceNumber status 17))))
                                                 :doc  "Will be <code>true</code> if this update is reporting the status of a CDJ."}
                            'device-name        {:code '(.getDeviceName status)
                                                 :doc  "The name reported by the device sending the update."}
                            'device-number      {:code '(.getDeviceNumber status)
                                                 :doc  "The player/device number sending the update."}
                            'effective-tempo    {:code '(.getEffectiveTempo status)
                                                 :doc  "The effective tempo reflected by this update, which reflects both its track BPM and pitch as needed."}
                            'mixer?             {:code '(or (instance? MixerStatus status)
                                                            (and (instance? Beat status)
                                                                 (> (.getDeviceNumber status) 32)))
                                                 :doc  "Will be <code>true</code> if this update is reporting the status of a Mixer."}
                            'next-cue           {:code '(let [device-update (extract-device-update status)]
                                                          (when-let [reached (playback-time device-update)]
                                                            (if-let [data util/*simulating*]
                                                              (when-let [cue-list (:cue-list data)]
                                                                (.findEntryAfter cue-list reached))
                                                              (when-let [metadata (.getLatestMetadataFor
                                                                                   (org.deepsymmetry.beatlink.data.MetadataFinder/getInstance)
                                                                                   device-update)]
                                                                (when-let [cue-list (.getCueList metadata)]
                                                                  (.findEntryAfter cue-list reached))))))
                                                 :doc  "The next rekordbox cue that will be reached in the track being played, if any. This will be <code>nil</code> unless the <code>TimeFinder</code> is running or if the question doesn't make sense for the device that sent the status update. The easiest way to make sure the <code>TimeFinder</code> is running is to open the Player Status window. If the player is sitting right on a cue, both this and <code>previous-cue</code> will be equal to that cue."}
                            'pitch-multiplier   {:code '(Util/pitchToMultiplier (.getPitch status))
                                                 :doc
                                                 "Represents the current device pitch (playback speed) as a multiplier ranging from 0.0 to 2.0, where normal, unadjusted pitch has the multiplier 1.0, and zero means stopped."}
                            'pitch-percent      {:code '(Util/pitchToPercentage (.getPitch status))
                                                 :doc
                                                 "Represents the current device pitch (playback speed) as a percentage ranging from -100% to +100%, where normal, unadjusted pitch has the value 0%."}
                            'previous-cue       {:code '(let [device-update (extract-device-update status)]
                                                          (when-let [reached (playback-time device-update)]
                                                            (if-let [data util/*simulating*]
                                                              (when-let [cue-list (:cue-list data)]
                                                                (.findEntryBefore cue-list reached))
                                                              (when-let [metadata (.getLatestMetadataFor
                                                                                   (org.deepsymmetry.beatlink.data.MetadataFinder/getInstance)
                                                                                   device-update)]
                                                                (when-let [cue-list (.getCueList metadata)]
                                                                  (.findEntryBefore cue-list reached))))))
                                                 :doc  "The rekordbox cue that was most recently passed in the track being played, if any. This will be <code>nil</code> unless the <code>TimeFinder</code> is running or if the question doesn't make sense for the device that sent the status update. The easiest way to make sure the <code>TimeFinder</code> is running is to open the Player Status window. If the player is sitting right on a cue, both this and <code>next-cue</code> will be equal to that cue."}
                            'raw-bpm            {:code '(.getBpm status)
                                                 :doc
                                                 "Get the raw track BPM at the time of the beat. This is an integer representing the BPM times 100, so a track running at 120.5 BPM would be represented by the value 12050."}
                            'raw-pitch          {:code '(.getPitch status)
                                                 :doc
                                                 "Get the raw device pitch at the time of the beat. This is an integer ranging from 0 to 2,097,152, which corresponds to a range between completely stopping playback to playing at twice normal tempo.
<p>See <code>pitch-multiplier</code> and <code>pitch-percent</code> for more useful forms of this information."}
                            'timestamp          {:code '(.getTimestamp status)
                                                 :doc  "Records the nanosecond at which we received this update."}
                            'track-bpm          {:code '(/ (.getBpm status) 100.0)
                                                 :doc
                                                 "Get the track BPM at the time of the beat. This is a floating point value ranging from 0.0 to 65,535. See <code>effective-tempo</code> for the speed at which it is currently playing."}
                            'track-time-reached {:code '(playback-time status)
                                                 :doc  "How far into the track has been played, in milliseconds. This will be <code>nil</code> unless the <code>TimeFinder</code> is running or if the question doesn't make sense for the device that sent the status update. The easiest way to make sure the <code>TimeFinder</code> is running is to open the Player Status window."}}}

   ;; Shared bindings for obtaining metadata about the track associated with a status object.
   :metadata {:bindings {'track-album    {:code '(if-let [data util/*simulating*]
                                                   (get-in data [:metadata :album])
                                                   (when (some? track-metadata)
                                                     (when-let [album (.getAlbum track-metadata)] (.label album))))
                                          :doc  "The album of the loaded track, if metadata is available."}
                         'track-artist   {:code '(if-let [data util/*simulating*]
                                                   (get-in data [:metadata :artist])
                                                   (when (some? track-metadata)
                                                     (when-let [artist (.getArtist track-metadata)] (.label artist))))
                                          :doc  "The artist of the loaded track, if metadata is available."}
                         'track-comment  {:code '(if-let [data util/*simulating*]
                                                   (get-in data [:metadata :comment])
                                                   (when (some? track-metadata)
                                                     (when-let [comment (.getComment track-metadata)] comment)))
                                          :doc  "The comment assigned to the loaded track, if metadata is available."}
                         'track-genre    {:code '(if-let [data util/*simulating*]
                                                   (get-in data [:metadata :genre])
                                                   (when (some? track-metadata)
                                                     (when-let [genre (.getGenre track-metadata)] (.label genre))))
                                          :doc  "The genre of the loaded track, if metadata is available."}
                         'track-key      {:code '(if-let [data util/*simulating*]
                                                   (get-in data [:metadata :key])
                                                   (when (some? track-metadata)
                                                     (when-let [key (.getKey track-metadata)] (.label key))))
                                          :doc  "The key of the loaded track, if metadata is available."}
                         'track-label    {:code '(if-let [data util/*simulating*]
                                                   (get-in data [:metadata :label])
                                                   (when (some? track-metadata)
                                                     (when-let [label (.getLabel track-metadata)] (.label label))))
                                          :doc  "The label of the loaded track, if metadata is available."}
                         'track-length   {:code '(if-let [data util/*simulating*]
                                                   (get-in data [:metadata :duration])
                                                   (when (some? track-metadata) (.getDuration track-metadata)))
                                          :doc  "The length in seconds of the loaded track, if metadata is available."}
                         'track-metadata {:code '(when-not util/*simulating*
                                                   (.getLatestMetadataFor
                                                    (org.deepsymmetry.beatlink.data.MetadataFinder/getInstance)
                                                    (extract-device-update status)))
                                          :doc  "The metadata object for the loaded track, if one is available."}
                         'track-title    {:code '(if-let [data util/*simulating*]
                                                   (get-in data [:metadata :title])
                                                   (when (some? track-metadata) (.getTitle track-metadata)))
                                          :doc  "The title of the loaded track, if metadata is available."}}}

   ;; Shared bindings for Beat objects and beat-tpu tuples.
   :beat {:bindings {'bar-number  {:code '(if-let [data util/*simulating*]
                                            (when-let [beat (current-beat status)]
                                              (when-let [grid (:beat-grid data)]
                                                (.getBarNumber grid beat)))
                                            (current-bar status))
                                   :doc  "Identifies the bar in which the beat that just played falls. This counter starts at bar 1 as the track is played, and increments on each downbeat.<p>When the track being played has not been analyzed by rekordbox, or is being played on a non-nexus player, or the <code>TimeFinder</code> is not running, this information is not available, and the value -1 is reported."}
                     'beat-number {:code '(or (current-beat status) -1)
                                   :doc  "Identifies the beat of the track that is currently being played. This counter starts at beat 1 as the track is played, and increments on each beat. When the player is paused at the start of the track before playback begins, the value reported is 0.<p> When the track being played has not been analyzed by rekordbox, or is being played on a non-nexus player, this information is not available, and the value -1 is reported."}

                     'looping?      {:code '(.isLooping (.getLatestStatusFor (VirtualCdj/getInstance)
                                                                             (extract-device-update status)))
                                    :doc  "Is the player currently playing a loop?"}
                     'on-air?       {:code '(.isOnAir (.getLatestStatusFor (VirtualCdj/getInstance)
                                                                           (extract-device-update status)))
                                     :doc  "Is the CDJ on the air? A player is considered to be on the air when it is connected to a mixer channel that is not faded out. Only Nexus mixers and later seem to support this capability."}
                     'tempo-master? {:code '(.isTempoMaster (extract-device-update status))
                                     :doc  "Was this beat sent by the current tempo master?"}}}

   Beat {:inherit  [DeviceUpdate :beat :metadata]
         :bindings {'beat          {:code 'status
                                    :doc  "The raw beat message received from the player."}
                    'beat-number   {:code '(or (current-beat status) -1)
                                    :doc  "Identifies the beat of the track that just played. This counter starts at beat 1 as the track is played, and increments on each beat.<p>When the track being played has not been analyzed by rekordbox, or is being played on a non-nexus player, or the <code>TimeFinder</code> is not running, this information is not available, and the value -1 is reported."}
                    'tempo-master? {:code '(.isTempoMaster status)
                                    :doc  "Was this beat sent by the current tempo master?"}
                    'on-air?       {:code '(.isOnAir (.getLatestStatusFor (VirtualCdj/getInstance) status))
                                    :doc  "Is the CDJ on the air? A player is considered to be on the air when it is connected to a mixer channel that is not faded out. Only Nexus mixers seem to support this capability."}
                    'looping?      {:code '(.isLooping (.getLatestStatusFor (VirtualCdj/getInstance) status))
                                    :doc  "Is the player currently playing a loop?"}}}

   MixerStatus {:inherit  [DeviceUpdate]
                :bindings {'tempo-master? {:code '(.isTempoMaster status)
                                           :doc  "Is this mixer the current tempo master?"}}}

   CdjStatus {:inherit  [DeviceUpdate :metadata]
              :bindings {'at-end?             {:code '(.isAtEnd status)
                                               :doc  "Is the player currently stopped at the end of a track?"}
                         'bar-number          {:code '(when-let [grid (.getLatestBeatGridFor beatgrid-finder device-update)]
                                                        (.getBarNumber grid (.getBeatNumber status)))
                                               :doc  "Identifies the bar in which the beat that just played falls. This counter starts at bar 1 as the track is played, and increments on each downbeat.<p>When the track being played has not been analyzed by rekordbox, or is being played on a non-nexus player, or the <code>TimeFinder</code> is not running, this information is not available, and the value -1 is reported."}
                         'beat-number         {:code '(.getBeatNumber status)
                                               :doc
                                               "Identifies the beat of the track that is currently being played. This counter starts at beat 1 as the track is played, and increments on each beat. When the player is paused at the start of the track before playback begins, the value reported is 0.<p> When the track being played has not been analyzed by rekordbox, or is being played on a non-nexus player, this information is not available, and the value -1 is reported."}
                         'busy?               {:code '(.isBusy status)
                                               :doc  "Will be <code>true</code> if the player is doing anything."}
                         'cue-countdown       {:code '(.getCueCountdown status)
                                               :doc
                                               "How many beats away is the next cue point in the track? If there is no saved cue point after the current play location, or if it is further than 64 bars ahead, the value 511 is returned (and the CDJ will display &ldquo;--.- bars&rdquo;. As soon as there are just 64 bars (256 beats) to go before the next cue point, this value becomes 256. This is the point at which the CDJ starts to display a countdown, which it displays as  &ldquo;63.4 Bars&rdquo;.<p> As each beat goes by, this value decrements by 1, until the cue point is about to be reached, at which point the value is 1 and the CDJ displays &ldquo;00.1 Bars&rdquo;. On the beat on which the cue point was saved the value is 0  and the CDJ displays &ldquo;00.0 Bars&rdquo;. On the next beat, the value becomes determined by the next cue point (if any) in the track."}
                         'cue-countdown-text  {:code '(.formatCueCountdown status)
                                               :doc  "Contains the information from <code>cue-countdown</code> formatted the way it would be displayed on the player, e.g. &ldquo;07.4&rdquo; or &ldquo;--.-&rdquo;."}
                         'cued?               {:code '(.isCued status)
                                               :doc  "Is the player currently cued (paused at the cue point)?"}
                         'looping?            {:code '(.isLooping status)
                                               :doc  "Is the player currently playing a loop?"}
                         'on-air?             {:code '(.isOnAir status)
                                               :doc
                                               "Is the CDJ on the air? A player is considered to be on the air when it is connected to a mixer channel that is not faded out. Only Nexus mixers seem to support this capability."}
                         'paused?             {:code '(.isPaused status)
                                               :doc  "Is the player currently paused?"}
                         'playing?            {:code '(.isPlaying status)
                                               :doc  "Is the player currently playing a track?"}
                         'rekordbox-id        {:code '(.getRekordboxId status)
                                               :doc  "The rekordbox id of the loaded track. Will be 0 if no track is loaded. If the track was loaded from an ordinary audio CD in the CD slot, this will just be the track number."}
                         'synced?             {:code '(.isSynced status)
                                               :doc  "Is the player currently in Sync mode?"}
                         'tempo-master?       {:code '(.isTempoMaster status)
                                               :doc  "Is this player the current tempo master?"}
                         'track-number        {:code '(.getTrackNumber status)
                                               :doc  "The track number of the loaded track. Identifies the track within a playlist or other scrolling list of tracks in the CDJ's browse interface."}
                         'track-source-player {:code '(.getTrackSourcePlayer status)
                                               :doc  "Which player was the track loaded from? Returns the device number, or 0 if there is no track loaded."}
                         'track-source-slot   {:code '(track-source-slot status)
                                               :doc  "Which slot was the track loaded from? Values are <code>:no-track</code>, <code>:cd-slot</code>, <code>:sd-slot</code>, <code>:usb-slot</code>, or <code>:unknown</code>."}
                         'track-type          {:code '(track-type status)
                                               :doc  "What kind of track was loaded? Values are <code>:no-track</code>, <code>:cd-digital-audio</code>, <code>:rekordbox</code>, or <code>:unknown</code>."}}}

   ;; A tuple of [Beat TrackPositionUpdate]
   :beat-tpu {:inherit  [:beat :metadata]
              :bindings {'address         {:code '(.getAddress (extract-device-update status))
                                           :doc  "The address of the device from which this beat was received."}
                         'bar-meaningful? {:code '(.isBeatWithinBarMeaningful (extract-device-update status))
                                           :doc
                                           "Will be <code>true</code> if this beat is coming from a device where <code>beat-within-bar</code> can reasonably be expected to have musical significance, because it respects the way a track was configured within rekordbox."}

                         'beat               {:code '(first status)
                                              :doc  "The raw beat message received from the player."}
                         'beat?              {:code 'true
                                              :doc  "Will be <code>true</code> as this update is announcing a new beat."}
                         'beat-within-bar    {:code '(.getBeatWithinBar (first status))
                                              :doc  "The position within a measure of music at which the beat fell (a value from 1 to 4, where 1 represents the down beat). This value will be accurate for players when the track was properly configured within rekordbox (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize down beats with players, however, so this value is meaningless when coming from the mixer. The usefulness of this value can be checked with <code>bar-meaningful?</code>"}
                         'cdj?               {:code '(< (.getDeviceNumber (extract-device-update status) 17))
                                              :doc  "Will be <code>true</code> if this beat is reporting the status of a CDJ."}
                         'device-name        {:code '(.getDeviceName (extract-device-update status))
                                              :doc  "The name reported by the device sending the beat."}
                         'device-number      {:code '(.getDeviceNumber (extract-device-update status))
                                              :doc  "The player/device number sending the beat."}
                         'effective-tempo    {:code '(.getEffectiveTempo (extract-device-update status))
                                              :doc  "The effective tempo reflected by this beat, which reflects both its track BPM and pitch as needed."}
                         'mixer?             {:code '(> (.getDeviceNumber (extract-device-update status)) 32)
                                              :doc  "Will be <code>true</code> if this beat came from a Mixer."}
                         'next-cue           {:code '(let [device-update (extract-device-update status)]
                                                          (when-let [reached (playback-time device-update)]
                                                            (if-let [data util/*simulating*]
                                                              (when-let [cue-list (:cue-list data)]
                                                                (.findEntryAfter cue-list reached))
                                                              (when-let [metadata (.getLatestMetadataFor
                                                                                   (org.deepsymmetry.beatlink.data.MetadataFinder/getInstance)
                                                                                   device-update)]
                                                                (when-let [cue-list (.getCueList metadata)]
                                                                  (.findEntryAfter cue-list reached))))))
                                              :doc  "The next rekordbox cue that will be reached in the track being played, if any. This will be <code>nil</code> unless the <code>TimeFinder</code> is running or if the question doesn't make sense for the device that sent the status update. The easiest way to make sure the <code>TimeFinder</code> is running is to open the Player Status window. If the player is sitting right on a cue, both this and <code>previous-cue</code> will be equal to that cue."}
                         'pitch-multiplier   {:code '(Util/pitchToMultiplier (.getPitch (extract-device-update status)))
                                              :doc  "Represents the current device pitch (playback speed) as a multiplier ranging from 0.0 to 2.0, where normal, unadjusted pitch has the multiplier 1.0, and zero means stopped."}
                         'pitch-percent      {:code '(Util/pitchToPercentage (.getPitch (extract-device-update status)))
                                              :doc  "Represents the current device pitch (playback speed) as a percentage ranging from -100% to +100%, where normal, unadjusted pitch has the value 0%."}
                         'previous-cue       {:code '(let [device-update (extract-device-update status)]
                                                          (when-let [reached (playback-time device-update)]
                                                            (if-let [data util/*simulating*]
                                                              (when-let [cue-list (:cue-list data)]
                                                                (.findEntryBefore cue-list reached))
                                                              (when-let [metadata (.getLatestMetadataFor
                                                                                   (org.deepsymmetry.beatlink.data.MetadataFinder/getInstance)
                                                                                   device-update)]
                                                                (when-let [cue-list (.getCueList metadata)]
                                                                  (.findEntryBefore cue-list reached))))))
                                              :doc  "The rekordbox cue that was most recently passed in the track being played, if any. This will be <code>nil</code> unless the <code>TimeFinder</code> is running or if the question doesn't make sense for the device that sent the status update. The easiest way to make sure the <code>TimeFinder</code> is running is to open the Player Status window. If the player is sitting right on a cue, both this and <code>next-cue</code> will be equal to that cue."}
                         'raw-bpm            {:code '(.getBpm (extract-device-update status))
                                              :doc  "Get the raw track BPM at the time of the beat. This is an integer representing the BPM times 100, so a track running at 120.5 BPM would be represented by the value 12050."}
                         'raw-pitch          {:code '(.getPitch (extract-device-update status))
                                              :doc  "Get the raw device pitch at the time of the beat. This is an integer ranging from 0 to 2,097,152, which corresponds to a range between completely stopping playback to playing at twice normal tempo.
<p>See <code>pitch-multiplier</code> and <code>pitch-percent</code> for more useful forms of this information."}
                         'timestamp          {:code '(.getTimestamp (extract-device-update status))
                                              :doc  "Records the nanosecond at which we received this beat."}
                         'track-bpm          {:code '(/ (.getBpm (extract-device-update status)) 100.0)
                                              :doc
                                              "Get the track BPM at the time of the beat. This is a floating point value ranging from 0.0 to 65,535. See <code>effective-tempo</code> for the speed at which it is currently playing."}
                         'track-position     {:code '(second status)
                                              :doc  "The raw TrackPositionUpdate object built from the beat."}
                         'track-time-reached {:code '(.milliseconds (second status))
                                              :doc
                                              "How far into the track has been played, in thousandths of a second."}}}})

(def ^:private metadata-bindings
  "The convenience bindings which require the track metadata to be
  available as the first binding in order to compile."
  (set (keys (get-in convenience-bindings [:metadata :bindings]))))

(defn bindings-for-update-class
  "Returns the convenience bindings which should be made available for
  the specified device update class, including any inherited ones."
  [c]
  (let [leaf (get convenience-bindings c)
        ancestors (apply merge (map bindings-for-update-class (:inherit leaf)))]
    (merge ancestors (:bindings leaf))))

(defn gather-convenience-bindings
  "Scans a Clojure form for any occurrences of the convenience symbols
  we know how to make available with useful values, and returns the
  symbols found along with the expressions to which they should be
  bound. If `nil-status?` is `true`, the bindings must be built in a
  way that protects against the possibility of `status` being `nil`."
  [form available-bindings nil-status?]
  (let [result (atom (sorted-map))]
    (clojure.walk/postwalk (fn [elem]
                             (when-let [binding (get available-bindings elem)]
                               (swap! result assoc elem (if nil-status?
                                                          `(when ~'status ~(:code binding))
                                                          (:code binding)))))
                           (clojure.walk/macroexpand-all form))
    ;; If we have any metadata bindings, make sure to bind track-metadata as the first binding form,
    ;; since the others rely on it.
    (if (seq (clojure.set/intersection metadata-bindings (set (keys @result))))
      (let [binding (get available-bindings 'track-metadata)]
        (apply concat ['track-metadata (if nil-status?
                                         `(when ~'status ~(:code binding))
                                         (:code binding))]
               (seq (dissoc @result 'track-metadata))))
      (apply concat (seq @result)))))

(defmacro wrap-user-expression
  "Takes a Clojure form containing a user expression, adds bindings
  for any convenience symbols that were found in it, and builds a
  function that accepts a status object, binds the convenience symbols
  based on the status, and returns the results of evaluating the user
  expression in that context. If `nil-status?` is `true`, the bindings
  must be built in a way that protects against the possibility of
  `status` being `nil`. If `no-locals?` is `true`, this is a
  function (like a global setup function) which should have no
  `locals` binding available."
  [body fn-sym available-bindings nil-status? no-locals?]
  (let [bindings (gather-convenience-bindings body available-bindings nil-status?)]
    (if no-locals?
      `(fn ~fn-sym ~'[status trigger-data globals]
         ~(if (seq bindings)
            `(let [~@bindings]
               ~body)
            body))
      `(fn ~fn-sym ~'[status {:keys [locals] :as trigger-data} globals]
         ~(if (seq bindings)
            `(let [~@bindings]
               ~body)
            body)))))

(defn expressions-namespace
  "Returns the symbol representing the namespace in which expressions
  should be compiled and run for the specified show, or for the
  Triggers window if `show` is absent or `nil`. Also makes sure the
  namespace has been loaded, or created in the case of a show-specific
  namespace."
  ([]
   (expressions-namespace nil))
  ([show]
   (if show
     (let [ns-base "beat-link-trigger.expressions.show"
           expr-ns (symbol (str ns-base "-" (:uuid show)))]
       (when-not (find-ns expr-ns)
         (let [src (-> (io/resource "beat_link_trigger/expressions/show.clj")
                       slurp
                       (str/replace-first ns-base (name expr-ns)))]
           (load-string src)))   ; Load it.
       expr-ns)
     (let [expr-ns 'beat-link-trigger.expressions.triggers]
       (when-not (find-ns expr-ns)
         (require expr-ns))  ; Make sure it's loaded
       expr-ns))))

(defn build-user-expression
  "Takes a string that a user has entered as a custom expression, adds
  bindings for any convenience symbols that were found in it, and
  builds a function that accepts a `status` object, binds the
  convenience symbols based on the status, and returns the results of
  evaluating the user expression in that context.

  The final argument is a map containing keyword arguments that modify
  the way the expression is built.

  The `:description` describes the purpose of the expression in a
  human-oriented way and is reported as the file name in any exception
  arising during parsing or execution of the expression.

  The `:fn-sym` is the symbol provided to name the function at compile
  time, which helps understand the origin of exceptions that occur
  when the function is executing. It is similar to description, but
  must be more terse and follow the rules of Clojure symbols.

  If `:nil-status?` is `true`, the bindings must be built in a way that
  protects against the possibility of `status` being `nil`.

  If `:no-locals?` is `true`, this is a function (like a global setup
  function) which should have no `locals` binding available.

  If `:raw-for-show` is not `nil`, though this is an expression being
  compiled for the Triggers window (and so `:show` must be `nil`), it
  is for a raw trigger that belongs to the specified show, and the
  expression should be given access to that show's namespace by
  aliasing `show-shared` during compilation.

  If `:show` is `nil`, the expression is being built for the Triggers
  window, otherwise it is built for the specified show."

  ;;was [expr available-bindings nil-status? title no-locals? show]
  [expr available-bindings {:keys [description fn-sym nil-status? no-locals? raw-for-show show]
                            :or   {description "Unknown Expression"
                                   fn-sym      'unknown-expression
                                   nil-status? false
                                   no-locals?  false}}]
  (binding [*ns* (the-ns (expressions-namespace show))]
    (when raw-for-show (alias 'show-shared (expressions-namespace raw-for-show)))
    (try
      (let [reader (rt/indexing-push-back-reader expr 1 description)
            eof    (Object.)
            forms  (take-while #(not= % eof) (repeatedly #(r/read reader false eof)))]
        (eval `(wrap-user-expression (do ~@forms) ~fn-sym ~available-bindings ~nil-status? ~no-locals?)))
      (finally
        (when raw-for-show (ns-unalias *ns* 'show-shared))))))

(defn define-shared-functions
  "Takes a string that a user has entered as shared functions for
  expressions, and evaluates it in the expressions namespace. The
  `title` describes the shared function context and is reported as the
  file name in any exception arising during parsing the expression.

  If `show` is `nil`, the expression is being built for the Triggers
  window, otherwise it is built for the specified show."
  ([expr title]
   (define-shared-functions expr title nil))
  ([expr title show]
   (binding [*ns* (the-ns (expressions-namespace show))]
     (let [reader (rt/indexing-push-back-reader expr 1 title)
           eof (Object.)
           forms (take-while #(not= % eof) (repeatedly #(r/read reader false eof)))]
       (doseq [form forms]
         (eval form))))))
