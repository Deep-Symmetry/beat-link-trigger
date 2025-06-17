#_{:clj-kondo/ignore [:unused-namespace :unused-referred-var :unused-import :refer-all]}
(ns beat-link-trigger.expressions.show
  "The namespace into which user-entered custom expressions for the a
  show window will be compiled, after the above namespace name is
  modified by appending a hyphen and the show's random UUID, which
  provides support for making the expressions easier to write. We
  require, refer, and import a lot of things not used in this file,
  simply for the convenience of expressions that may be created by
  users later."
  (:require [clojure.repl :refer :all]
            [clojure.set]
            [clojure.string :as str]
            [beat-carabiner.core :as beat-carabiner]
            [beat-link-trigger.carabiner :as carabiner]
            [beat-link-trigger.expressions.triggers
             :refer [default-repositories device-finder virtual-cdj metadata-finder art-finder
                     beatgrid-finder signature-finder time-finder waveform-finder extract-device-update
                     add-library add-libraries extend-classpath track-source-slot track-type playback-time
                     current-beat current-bar extract-device-number extract-raw-cue-update
                     set-overlay-background-color set-overlay-indicator-color set-overlay-emphasis-color
                     register-cue-builder unregister-cue-builder replace-artist-line]]
            [beat-link-trigger.help :as help]
            [beat-link-trigger.overlay :as overlay]
            [beat-link-trigger.players :as players]
            [beat-link-trigger.playlist-writer :as playlist-writer]
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.show :as show]
            [beat-link-trigger.show-util :as su :refer [in-show-ns]]
            [beat-link-trigger.simulator :as sim]
            [beat-link-trigger.socket-picker :as socket-picker]
            [beat-link-trigger.triggers :as triggers]
            [beat-link-trigger.util :as util :refer [in-core-ns in-triggers-ns]]
            [cemerick.pomegranate :as pomegranate]
            [cemerick.pomegranate.aether :as aether]
            [http.async.client :as http]
            [overtone.midi :as midi]
            [overtone.osc :as osc]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj Util
            DeviceAnnouncement DeviceUpdate Beat CdjStatus MixerStatus MediaDetails
            CdjStatus$TrackSourceSlot CdjStatus$TrackType]
           [org.deepsymmetry.beatlink.data BeatGrid MetadataFinder SignatureFinder TimeFinder
            PlaybackState TrackPositionUpdate SlotReference TrackMetadata AlbumArt]
           [java.awt Color]
           [java.net InetAddress InetSocketAddress DatagramPacket DatagramSocket]))


(defonce ^{:doc "Provides a space for the show expressions to store
  values they want to share across tracks, phrase triggers, and cues."}
  globals (atom {}))
