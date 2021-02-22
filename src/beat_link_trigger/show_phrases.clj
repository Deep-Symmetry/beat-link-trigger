(ns beat-link-trigger.show-phrases
  "Implements phrase trigger features for Show files, including their
  cue editing windows."
  (:require [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.show-util :as su :refer [latest-show latest-track latest-show-and-track
                                                        swap-show! swap-track! find-cue swap-cue!]]
            [beat-link-trigger.util :as util]
            [clojure.set]
            [clojure.string :as str]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [thi.ng.color.core :as color]
            [taoensso.timbre :as timbre]))

(defn new-phrase
  "Adds a new phrase trigger to the show."
  [show]
  (let [show (latest-show show)]
    ;; TODO: Create basic structure and UI panel, add it to the
    ;; `:phrases` map, the `:phrase-order` map in the show `:contents`
    ;; (initializing that to an empty vector if it doesn't already
    ;; exist), and recomputing the `:vis-phrases` vector and updating
    ;; the show rows accordingly.
    ))

(defn sort-phrases
  "Sorts the phrase triggers by their comments. `show` must be current."
  [show]
  (let [show (latest-show show)]
    ;; TODO: Perform the sort, update `:contents` `:phrase-order`, then
    ;; do a visibility refresh to update the show rows accordingly.
    ))
