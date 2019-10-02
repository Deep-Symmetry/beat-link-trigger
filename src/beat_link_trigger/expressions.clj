(ns beat-link-trigger.expressions
  "A namespace in which user-entered custom expressions will be
  evaluated, which provides support for making them easier to write."
  (:require [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [beat-link-trigger.util :as util]
            [cemerick.pomegranate :as pomegranate]
            [cemerick.pomegranate.aether :as aether]
            [overtone.midi :as midi]
            [overtone.osc :as osc]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Util
            DeviceAnnouncement DeviceUpdate Beat CdjStatus MixerStatus MediaDetails
            CdjStatus$TrackSourceSlot CdjStatus$TrackType]
           [org.deepsymmetry.beatlink.data TimeFinder MetadataFinder SignatureFinder
            PlaybackState TrackPositionUpdate SlotReference TrackMetadata AlbumArt]
           [java.net InetAddress InetSocketAddress DatagramPacket DatagramSocket]))


;;; These first definitions are intended for use by user expressions and shared functions:

(def default-repositories
  "Have our add-dependencies function default to searching Clojars as
  well as Maven Central."
  (merge aether/maven-central
         {"clojars" "https://clojars.org/repo"}))

(defn add-library
  "Allow expression code to add a new Maven dependency at runtime by
  specifying its coordinate, and optionally the repositories to
  search."
  [coordinate & {:keys [repositories] :or {repositories default-repositories}}]
  (pomegranate/add-dependencies :coordinates [coordinate] :repositories repositories))

(defn add-libraries
  "Allow expression code to add multiple new Maven dependency at runtime
  by specifying their coordinates, and optionally the repositories to
  search."
  [coordinates & {:keys [repositories] :or {repositories default-repositories}}]
  (pomegranate/add-dependencies :coordinates coordinates :repositories repositories))

(defn extend-classpath
  "Allow expression code to add a local Jar file or directory to the
  classpath at runtime."
  [jar-or-dir]
  (pomegranate/add-classpath jar-or-dir))

(defn track-source-slot
  "Converts the Java enum value representing the slot from which a track
  was loaded to a more convenient Clojure keyword."
  [status]
  (util/case-enum (.getTrackSourceSlot status)
    CdjStatus$TrackSourceSlot/NO_TRACK   :no-track
    CdjStatus$TrackSourceSlot/CD_SLOT    :cd-slot
    CdjStatus$TrackSourceSlot/SD_SLOT    :sd-slot
    CdjStatus$TrackSourceSlot/USB_SLOT   :usb-slot
    CdjStatus$TrackSourceSlot/COLLECTION :collection
    :unknown))

(defn track-type
  "Converts the Java enum value representing the type of track that
  was loaded to a more convenient Clojure keyword."
  [status]
  (util/case-enum (.getTrackType status)
    CdjStatus$TrackType/NO_TRACK         :no-track
    CdjStatus$TrackType/CD_DIGITAL_AUDIO :cd-digital-audio
    CdjStatus$TrackType/REKORDBOX        :rekordbox
    :unknown))

(defn playback-time
  "Obtains the current playback time of the player that sent the
  supplied device update, or `nil` if we don't know."
  [device-update]
  (let [time-finder (TimeFinder/getInstance)]
    (when (.isRunning time-finder)
      (let [result (.getTimeFor time-finder device-update)]
        (when-not (neg? result)
          result)))))

(defn extract-raw-cue-update
  "Given a status value from a show cue's started-on-beat or
  started-late expression, returns the raw device update object
  associated with it, which will be the first element of a tuple in
  the case of the started-on-beat expression."
  [cue-status]
  (if (instance? org.deepsymmetry.beatlink.DeviceUpdate cue-status)
    cue-status
    (first cue-status)))


;;; The remainder of the functions in this namespace are used by the
;;; expression compiler, and are not intended for users to interact
;;; with directly.

(def convenience-bindings
  "Identifies symbols which can be used inside a user expression when
  processing a particular kind of device update, along with the
  expression that will be used to automatically bind that symbol if it
  is used in the expression, and the documentation to show the user
  what the binding is for."
  {DeviceUpdate {:bindings {'address            {:code
                                                 '(.getAddress status)
                                                 :doc "The address of the device from which this update was received."}
                            'beat?              {:code '(instance? Beat status)
                                                 :doc  "Will be <code>true</code> if this update is announcing a new beat."}
                            'beat-within-bar    {:code '(.getBeatWithinBar status)
                                                 :doc
                                                 "The position within a measure of music at which the most recent beat fell (a value from 1 to 4, where 1 represents the down beat). This value will be accurate for players when the track was properly configured within rekordbox (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize down beats with players, however, so this value is meaningless when coming from the mixer. The usefulness of this value can be checked with <code>bar-meaningful?</code>"}
                            'bar-meaningful?    {:code '(.isBeatWithinBarMeaningful status)
                                                 :doc
                                                 "Will be <code>true</code> if this update is coming from a device where <code>beat-within-bar</code> can reasonably be expected to have musical significance, because it respects the way a track was configured within rekordbox."}
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
                            'next-cue           {:code '(when-let [reached (playback-time status)]
                                                          (when-let [metadata (.getLatestMetadataFor
                                                                               (org.deepsymmetry.beatlink.data.MetadataFinder/getInstance)
                                                                               status)]
                                                            (when-let [cue-list (.getCueList metadata)]
                                                              (.findEntryAfter cue-list reached))))
                                                 :doc  "The next cue that will be reached in the track being played, if any. This will be <code>nil</code> unless the <code>TimeFinder</code> is running or if the question doesn't make sense for the device that sent the status update. The easiest way to make sure the <code>TimeFinder</code> is running is to open the Player Status window. If the player is sitting right on a cue, both this and <code>previous-cue</code> will be equal to that cue."}
                            'pitch-multiplier   {:code '(Util/pitchToMultiplier (.getPitch status))
                                                 :doc
                                                 "Represents the current device pitch (playback speed) as a multiplier ranging from 0.0 to 2.0, where normal, unadjusted pitch has the multiplier 1.0, and zero means stopped."}
                            'pitch-percent      {:code '(Util/pitchToPercentage (.getPitch status))
                                                 :doc
                                                 "Represents the current device pitch (playback speed) as a percentage ranging from -100% to +100%, where normal, unadjusted pitch has the value 0%."}
                            'previous-cue       {:code '(when-let [reached (playback-time status)]
                                                          (when-let [metadata (.getLatestMetadataFor
                                                                               (org.deepsymmetry.beatlink.data.MetadataFinder/getInstance)
                                                                               status)]
                                                            (when-let [cue-list (.getCueList metadata)]
                                                              (.findEntryBefore cue-list reached))))
                                                 :doc  "The cue that was most recently passed in the track being played, if any. This will be <code>nil</code> unless the <code>TimeFinder</code> is running or if the question doesn't make sense for the device that sent the status update. The easiest way to make sure the <code>TimeFinder</code> is running is to open the Player Status window. If the player is sitting right on a cue, both this and <code>next-cue</code> will be equal to that cue."}
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

   Beat {:inherit  [DeviceUpdate]
         :bindings {'tempo-master? {:code '(.isTempoMaster status)
                                    :doc  "Was this beat sent by the current tempo master?"}
                    'on-air?       {:code '(.isOnAir (.getLatestStatusFor (VirtualCdj/getInstance) status))
                                    :doc
                                    "Is the CDJ on the air? A player is considered to be on the air when it is connected to a mixer channel that is not faded out. Only Nexus mixers seem to support this capability."}}}

   MixerStatus {:inherit  [DeviceUpdate]
                :bindings {'tempo-master? {:code '(.isTempoMaster status)
                                           :doc  "Is this mixer the current tempo master?"}}}

   CdjStatus {:inherit  [DeviceUpdate]
              :bindings {'at-end?             {:code '(.isAtEnd status)
                                               :doc  "Is the player currently stopped at the end of a track?"}
                         'beat-number         {:code '(.getBeatNumber status)
                                               :doc
                                               "Identifies the beat of the track that being played. This counter starts at beat 1 as the track is played, and increments on each beat. When the player is paused at the start of the track before playback begins, the value reported is 0.<p> When the track being played has not been analyzed by rekordbox, or is being played on a non-nexus player, this information is not available, and the value -1 is reported."}
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
                         'track-album         {:code '(when (some? track-metadata) (when-let [album (.getAlbum track-metadata)] (.label album)))
                                               :doc  "The album of the loaded track, if metadata is available."}
                         'track-artist        {:code '(when (some? track-metadata) (when-let [artist (.getArtist track-metadata)] (.label artist)))
                                               :doc  "The artist of the loaded track, if metadata is available."}
                         'track-comment       {:code '(when (some? track-metadata) (when-let [comment (.getComment track-metadata)] comment))
                                               :doc  "The comment assigned to the loaded track, if metadata is available."}
                         'track-genre         {:code '(when (some? track-metadata) (when-let [genre (.getGenre track-metadata)] (.label genre)))
                                               :doc  "The genre of the loaded track, if metadata is available."}
                         'track-key           {:code '(when (some? track-metadata) (when-let [key (.getKey track-metadata)] (.label key)))
                                               :doc  "The key of the loaded track, if metadata is available."}
                         'track-label         {:code '(when (some? track-metadata) (when-let [label (.getLabel track-metadata)] (.label label)))
                                               :doc  "The label of the loaded track, if metadata is available."}
                         'track-length        {:code '(when (some? track-metadata) (.getLength track-metadata))
                                               :doc  "The length in seconds of the loaded track, if metadata is available."}
                         'track-metadata      {:code '(.getLatestMetadataFor
                                                       (org.deepsymmetry.beatlink.data.MetadataFinder/getInstance)
                                                       status)
                                               :doc  "The metadata object for the loaded track, if one is available."}
                         'track-number        {:code '(.getTrackNumber status)
                                               :doc  "The track number of the loaded track. Identifies the track within a playlist or other scrolling list of tracks in the CDJ's browse interface."}
                         'track-source-player {:code '(.getTrackSourcePlayer status)
                                               :doc  "Which player was the track loaded from? Returns the device number, or 0 if there is no track loaded."}
                         'track-source-slot   {:code '(track-source-slot status)
                                               :doc  "Which slot was the track loaded from? Values are <code>:no-track</code>, <code>:cd-slot</code>, <code>:sd-slot</code>, <code>:usb-slot</code>, or <code>:unknown</code>."}
                         'track-title         {:code '(when (some? track-metadata) (.getTitle track-metadata))
                                               :doc  "The title of the loaded track, if metadata is available."}
                         'track-type          {:code '(track-type status)
                                               :doc  "What kind of track was loaded? Values are <code>:no-track</code>, <code>:cd-digital-audio</code>, <code>:rekordbox</code>, or <code>:unknown</code>."}}}

   ;; A tuple of [Beat TrackPositionUpdate]
   :beat-tpu {:bindings {'beat             {:code '(first status)
                                            :doc  "The raw beat message received from the player."}
                         'tempo-master?    {:code '(.isTempoMaster (first status))
                                            :doc  "Was this beat sent by the current tempo master?"}
                         'on-air?          {:code '(.isOnAir (.getLatestStatusFor (VirtualCdj/getInstance) (first status)))
                                            :doc
                                            "Is the CDJ on the air? A player is considered to be on the air when it is connected to a mixer channel that is not faded out. Only Nexus mixers seem to support this capability."}
                         'address          {:code '(.getAddress (first status))
                                            :doc  "The address of the device from which this beat was received."}
                         'beat?            {:code 'true
                                            :doc  "Will be <code>true</code> as this update is announcing a new beat."}
                         'beat-within-bar  {:code '(.getBeatWithinBar (first status))
                                            :doc
                                            "The position within a measure of music at which the beat fell (a value from 1 to 4, where 1 represents the down beat). This value will be accurate for players when the track was properly configured within rekordbox (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize down beats with players, however, so this value is meaningless when coming from the mixer. The usefulness of this value can be checked with <code>bar-meaningful?</code>"}
                         'bar-meaningful?  {:code '(.isBeatWithinBarMeaningful (first status))
                                            :doc
                                            "Will be <code>true</code> if this beat is coming from a device where <code>beat-within-bar</code> can reasonably be expected to have musical significance, because it respects the way a track was configured within rekordbox."}
                         'cdj?             {:code '(< (.getDeviceNumber (first status) 17))
                                            :doc  "Will be <code>true</code> if this beat is reporting the status of a CDJ."}
                         'device-name      {:code '(.getDeviceName (first status))
                                            :doc  "The name reported by the device sending the beat."}
                         'device-number    {:code '(.getDeviceNumber (first status))
                                            :doc  "The player/device number sending the beat."}
                         'effective-tempo  {:code '(.getEffectiveTempo (first status))
                                            :doc  "The effective tempo reflected by this beat, which reflects both its track BPM and pitch as needed."}
                         'mixer?           {:code '(> (.getDeviceNumber (first status)) 32)
                                            :doc  "Will be <code>true</code> if this beat came from a Mixer."}
                         'pitch-multiplier {:code '(Util/pitchToMultiplier (.getPitch (first status)))
                                            :doc
                                            "Represents the current device pitch (playback speed) as a multiplier ranging from 0.0 to 2.0, where normal, unadjusted pitch has the multiplier 1.0, and zero means stopped."}
                         'pitch-percent    {:code '(Util/pitchToPercentage (.getPitch (first status)))
                                            :doc
                                            "Represents the current device pitch (playback speed) as a percentage ranging from -100% to +100%, where normal, unadjusted pitch has the value 0%."}
                         'raw-bpm          {:code '(.getBpm (first status))
                                            :doc
                                            "Get the raw track BPM at the time of the beat. This is an integer representing the BPM times 100, so a track running at 120.5 BPM would be represented by the value 12050."}
                         'raw-pitch        {:code '(.getPitch (first status))
                                            :doc
                                            "Get the raw device pitch at the time of the beat. This is an integer ranging from 0 to 2,097,152, which corresponds to a range between completely stopping playback to playing at twice normal tempo.
<p>See <code>pitch-multiplier</code> and <code>pitch-percent</code> for more useful forms of this information."}
                         'timestamp        {:code '(.getTimestamp (first status))
                                            :doc  "Records the nanosecond at which we received this beat."}
                         'track-bpm        {:code '(/ (.getBpm (first status)) 100.0)
                                            :doc
                                            "Get the track BPM at the time of the beat. This is a floating point value ranging from 0.0 to 65,535. See <code>effective-tempo</code> for the speed at which it is currently playing."}

                         'track-position     {:code '(second status)
                                              :doc  "The raw TrackPositionUpdate object built from the beat."}
                         'beat-number        {:code '(.beatNumber (second status))
                                              :doc
                                              "Identifies the beat of the track that is being played. This counter starts at beat 1 as the track is played, and increments on each beat. When the player is paused at the start of the track before playback begins, the value reported is 0."}
                         'track-time-reached {:code '(.milliseconds (second status))
                                              :doc
                                              "How far into the track has been played, in thousandths of a second."}}}})

(def ^:private metadata-bindings
  "The convenience bindings which require the track metadata to be
  available as the first binding in order to compile."
  '#{track-artist track-comment track-genre track-key track-label track-length track-metadata track-title})

(defn bindings-for-update-class
  "Returns the convenience bindings which should be made available for
  the specified device update class, including any inherited ones."
  [c]
  (let [leaf (get convenience-bindings c)
        ancestors (apply merge (map bindings-for-update-class (:inherit leaf)))]
    (merge ancestors (:bindings leaf))))

(defn- gather-convenience-bindings
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
                           form)
    ;; If we have any metadata bindings, make sure to bind track-metadata as the first binding form,
    ;; since the others rely on it.
    (if (seq (clojure.set/intersection metadata-bindings (set (keys @result))))
      (let [binding (get available-bindings 'track-metadata)]
        (apply concat ['track-metadata (if nil-status?
                                         `(when ~'status ~(:code binding))
                                         (:code binding))]
               (seq (dissoc @result 'track-metadata))))
      (apply concat (seq @result)))))

(defmacro ^:private wrap-user-expression
  "Takes a Clojure form containing a user expression, adds bindings
  for any convenience symbols that were found in it, and builds a
  function that accepts a status object, binds the convenience symbols
  based on the status, and returns the results of evaluating the user
  expression in that context. If `nil-status?` is `true`, the bindings
  must be built in a way that protects against the possibility of
  `status` being `nil`."
  [body available-bindings nil-status?]
  (let [bindings (gather-convenience-bindings body available-bindings nil-status?)]
    `(fn ~'[status {:keys [locals] :as trigger-data} globals]
       ~(if (seq bindings)
          `(let [~@bindings]
             ~body)
          body))))

(defn build-user-expression
  "Takes a string that a user has entered as a custom expression, adds
  bindings for any convenience symbols that were found in it, and
  builds a function that accepts a status object, binds the
  convenience symbols based on the status, and returns the results of
  evaluating the user expression in that context. If `nil-status?` is
  `true`, the bindings must be built in a way that protects against
  the possibility of `status` being `nil`. The `title` describes the
  expression and is reported as the file name in any exception arising
  during parsing or execution of the expression."
  [expr available-bindings nil-status? title]
  (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
    (let [reader (rt/indexing-push-back-reader expr 1 title)
          eof (Object.)
          forms (take-while #(not= % eof) (repeatedly #(r/read reader false eof)))]
      (eval `(wrap-user-expression (do ~@forms) ~available-bindings ~nil-status?)))))

(defn define-shared-functions
  "Takes a string that a user has entered as shared functions for
  expressions, and evaluates it in the expressions namespace. The
  `title` describes the shared function context and is reported as the
  file name in any exception arising during parsing the expression."
  [expr title]
  (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
    (let [reader (rt/indexing-push-back-reader expr 1 title)
          eof (Object.)
          forms (take-while #(not= % eof) (repeatedly #(r/read reader false eof)))]
      (doseq [form forms]
        (eval form)))))
