(ns beat-link-trigger.expression-report
  "Generates and supports the report of show expressions."
  (:require [beat-link-trigger.show :as show]
            [beat-link-trigger.show-cues :as cues]
            [beat-link-trigger.show-phrases :as phrases]
            [beat-link-trigger.show-util :as su]
            [beat-link-trigger.util :as util]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.route :as route]
            [hiccup.core :as hiccup]
            [hiccup.page :as page]
            [hiccup.util]
            [seesaw.core :as seesaw])
  (:import [java.text SimpleDateFormat]
           [java.util Date UUID]))

(defn expression-report-success-response
  "Formats a response that an expression was simulated successfully."
  []
   {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string
             {:status "Success."})})

(defn expression-report-error-response
  "Formats a response that will cause the expressions report to show an
  error modal in response to an action button."
  ([message]
   (expression-report-error-response message nil))
  ([message title]
   {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string
             {:error {:title title
                      :details message}})}))

(def action-security-warning
  "A message reminding people to only enable report actions on secure networks."
  [:p [:em "Be sure to only do this on secure networks, where you trust any "
                      "device that would be able to connect to Beat Link Trigger."]])

(defn show-not-found
  "Helper expression used by request handlers from the expressions report
   to report that the specified show could not be found."
  []
  (expression-report-error-response
   (hiccup/html [:p "There is no open show with the requested file path. "
                 "You&rsquo;ll need to re-open it and re-enable report actions "
                 "if you want the action buttons to work."]
                [:br]
                action-security-warning)
   "Show Not Found"))

(defn show-not-enabled
  "Helper expression used by request handlers from the expressions report
   to report that the specified show has not enabled report actions"
  []
  (expression-report-error-response
   (hiccup/html [:p "You must choose " [:strong  "Enable Report Actions"] " in the Show's " [:strong "File "]
                 "menu in order for action buttons to work."]
                [:br]
                action-security-warning)
   "Show Actions Not Enabled"))

(defn track-not-found
  "Helper expression used by request handlers from the expressions report
   to report that the specified track could not be found."
  []
  (expression-report-error-response
   (hiccup/html [:p "There is no track with the specified signature in the chosen show."]
                [:br]
                [:p "You may want to refresh the report to view the current state of the show."])
   "Track Not Found"))

(defn phrase-not-found
  "Helper expression used by request handlers from the expressions report
   to report that the specified phrase could not be found."
  []
  (expression-report-error-response
   (hiccup/html [:p "There is no phrase with the specified UUID in the chosen show."]
                [:br]
                [:p "You may want to refresh the report to view the current state of the show."])
   "Phrase Not Found"))

(defn cue-not-found
  "Helper expression used by request handlers from the expressions report
   to report that the specified show could not be found."
  [context]
  (expression-report-error-response
   (hiccup/html [:p "There is no cue with the requested UUID in the specified " context "."]
                [:br]
                [:p "You may want to refresh the report to view the current state of the show."])
   "Cue Not Found"))

(defn unrecognized-expression
  "Helper expression used by request handlers from the expressions report
   to report that the requested expression type is not known."
  []
  (expression-report-error-response "There is no expression of the specified kind."
                                    "Unexpected Error"))

(defn editor-opened-in-background
  "Helper expression used by request handlers from the expressions report
   to let the user know how to find the editor they just opened."
  []
  (expression-report-error-response
   (hiccup/html [:p "The editor has been opened, but you&rsquo;ll need to "
                 "switch back to Beat Link Trigger to work with it."])
   "Expression Editor Opened"))

(defn window-brought-to-front
  ([window]
   (window-brought-to-front window nil))
  ([window scroll-target]
   (expression-report-error-response
    (hiccup/html (vec (concat [:p "The " window " window has been opened or brought to the front, "]
                              (when scroll-target
                                ["and the " scroll-target " has been scrolled into view, "])
                              ["but you&rsquo;ll need to switch to Beat Link Trigger to work with it."])))
    "Window Adjusted")))

;; Functions that support the show expressions report action buttions.

(defn edit-show-expression
  "Helper function used by requests from the expressions report
  requesting an editor window for an global expression."
  [path kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if (str/blank? kind)
        (do  ; Just bring the show window to the front.
          (seesaw/invoke-later (seesaw/show! (:frame show)))
          (window-brought-to-front "Show"))
        (if (contains? @@(requiring-resolve 'beat-link-trigger.editors/global-show-editors) (keyword kind))
          (do
            (seesaw/invoke-later
             ((requiring-resolve 'beat-link-trigger.editors/show-show-editor) (keyword kind) show nil (:frame show)
              (partial show/global-editor-update-fn show kind)))
            (editor-opened-in-background))
          (unrecognized-expression)))
      (show-not-enabled))
    (show-not-found)))

(defn simulate-track-expression
  "Helper function used by requests from the expressions report
  requesting simulation of an expression in a track."
  [path signature kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if-let [track (get-in show [:tracks signature])]
        (binding [util/*simulating* (util/data-for-simulation :entry [(:file show) (:signature track)])]
          (if-let [[update-binding create-status] (show/track-random-status-for-simulation (keyword kind))]
            (binding [util/*simulating* (update-binding)]
              (show/run-track-function track (keyword kind) (create-status) true)
              (expression-report-success-response))
            (unrecognized-expression)))
        (track-not-found))
      (show-not-enabled))
    (show-not-found)))

(defn edit-track-expression
  "Helper function used by requests from the expressions report
  requesting an editor window for an expression in a track."
  [path signature kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if-let [track (get-in show [:tracks signature])]
        (if (str/blank? kind)
          (do  ; Just bring the show window to the front and scroll to the track.
            (seesaw/invoke-later
             (seesaw/show! (:frame show))
             (show/scroll-to-track show track))  ; This can block with a modal.
            (window-brought-to-front "Show" "track"))
          (if (contains? @@(requiring-resolve 'beat-link-trigger.editors/show-track-editors) (keyword kind))
            (seesaw/invoke-now
             (let [panel (:panel track)
                   gear  (seesaw/select panel [:#gear])]
               ((requiring-resolve 'beat-link-trigger.editors/show-show-editor) (keyword kind) show track panel
                (partial show/track-editor-update-fn kind track gear))
               (editor-opened-in-background)))
            (unrecognized-expression)))
        (track-not-found))
      (show-not-enabled))
    (show-not-found)))

(defn simulate-track-cue-expression
  "Helper function used by requests from the expressions report
  requesting simulation of an expression in a track cue."
  [path signature cue-uuid kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if-let [track (get-in show [:tracks signature])]
        (if-let [cue (su/find-cue track (UUID/fromString cue-uuid))]
          (binding [util/*simulating* (util/data-for-simulation :entry [(:file show) (:signature track)])]
            (if-let [[update-binding create-status] (cues/random-status-for-simulation (keyword kind))]
              (binding [util/*simulating* (update-binding)]
                ;; TODO: Set up :last-entry-event for :ended expression? see show-cues/cue-simulate-actions
                (cues/run-cue-function track cue (keyword kind) (create-status) true)
                (expression-report-success-response))
              (unrecognized-expression)))
          (cue-not-found "track"))
        (track-not-found))
      (show-not-enabled))
    (show-not-found)))

(defn edit-track-cue-expression
  "Helper function used by requests from the expressions report
  requesting an editor window for an expression in a track cue."
  [path signature cue-uuid kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if-let [track (get-in show [:tracks signature])]
        (if-let [cue (su/find-cue track (UUID/fromString cue-uuid))]
          (do
            (when-not (get-in track [:cues-editor :panels (:uuid cue)])
              ;; Make sure the cues editor window is open before trying to edit a cue expression.
              (seesaw/invoke-now (cues/open-cues track (:frame show))))
            (seesaw/invoke-now
               (let [track (su/latest-track track)
                     panel (get-in track [:cues-editor :panels (:uuid cue)])
                     gear  (when panel (seesaw/select panel [:#gear]))]
                 (if gear
                   (if (str/blank? kind)
                     (do  ; Just scroll the cues editor to this cue and bring it to the front.
                       (seesaw/show! (get-in track [:cues-editor :frame]))
                       (seesaw/invoke-later (cues/scroll-to-cue track cue))  ; This can block with a modal!
                       (window-brought-to-front "Cues Editor" "cue"))
                     (if (contains? @@(requiring-resolve 'beat-link-trigger.editors/show-cue-editors) (keyword kind))
                       (do
                         ((requiring-resolve 'beat-link-trigger.editors/show-cue-editor) (keyword kind) track cue panel
                          (fn []
                            (cues/update-all-linked-cues track cue)
                            (when gear (cues/update-cue-gear-icon track cue gear))))
                         (editor-opened-in-background))
                       (unrecognized-expression)))
                   (expression-report-error-response
                    "The Cues Editor window for the track could not be found or created."
                    "Problem Opening Editor")))))
          (cue-not-found "track"))
        (track-not-found))
      (show-not-enabled))
    (show-not-found)))

(defn simulate-phrase-expression
  "Helper function used by requests from the expressions report
  requesting simulation of an expression in a phrase."
  [path uuid kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if-let [phrase (get-in show [:contents :phrases (UUID/fromString uuid)])]
        (binding [util/*simulating* (util/data-for-simulation :phrases-required? true)]
          (if-let [[update-binding create-status] (phrases/phrase-random-status-for-simulation (keyword kind))]
            (binding [util/*simulating* (update-binding)]
              (phrases/run-phrase-function show phrase (keyword kind) (create-status) true)
              (expression-report-success-response))
            (unrecognized-expression)))
        (phrase-not-found))
      (show-not-enabled))
    (show-not-found)))

(defn edit-phrase-expression
  "Helper function used by requests from the expressions report
  requesting an editor window for an expression in a phrase trigge."
  [path uuid kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if-let [phrase (get-in show [:contents :phrases (UUID/fromString uuid)])]
        (if (str/blank? kind)
          (do  ; Just bring the show window to the front and scroll to the phrase trigger.
            (seesaw/invoke-later
             (seesaw/show! (:frame show))
             (phrases/scroll-to-phrase show phrase))  ; This can block with a modal.
            (window-brought-to-front "Show" "phrase trigger"))
          (if (contains? @@(requiring-resolve 'beat-link-trigger.editors/show-phrase-editors) (keyword kind))
            (seesaw/invoke-now
             (let [panel (:panel (su/phrase-runtime-info show phrase))
                   gear  (seesaw/select panel [:#gear])]
               ((requiring-resolve 'beat-link-trigger.editors/show-show-editor) (keyword kind) show phrase panel
                (partial phrases/phrase-editor-update-fn kind show phrase gear))
               (editor-opened-in-background)))
            (unrecognized-expression)))
        (phrase-not-found))
      (show-not-enabled))
    (show-not-found)))

(defn simulate-phrase-cue-expression
  "Helper function used by requests from the expressions report
  requesting simulation of an expression in a phrase cue."
  [path uuid cue-uuid kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if-let [phrase (get-in show [:contents :phrases (UUID/fromString uuid)])]
        (if-let [cue (su/find-cue phrase (UUID/fromString cue-uuid))]
          (binding [util/*simulating* (util/data-for-simulation :phrases-required? true)]
            (if-let [[update-binding create-status] (cues/random-status-for-simulation (keyword kind))]
              (binding [util/*simulating* (update-binding)]
                ;; TODO: Set up :last-entry-event for :ended expression? see show-cues/cue-simulate-actions
                (cues/run-cue-function phrase cue (keyword kind) (create-status) true)
                (expression-report-success-response))
              (unrecognized-expression)))
          (cue-not-found "phrase"))
        (phrase-not-found))
      (show-not-enabled))
    (show-not-found)))

(defn edit-phrase-cue-expression
  "Helper function used by requests from the expressions report
  requesting an editor window for an expression in a phrase cue."
  [path uuid cue-uuid kind]
  (if-let [show (su/latest-show (io/file path))]
    (if (:actions-enabled show)
      (if-let [phrase (get-in show [:contents :phrases (UUID/fromString uuid)])]
        (if-let [cue (su/find-cue phrase (UUID/fromString cue-uuid))]
          (do
            (when-not (get-in phrase [:cues-editor :panels (:uuid cue)])
              ;; Make sure the cues editor window is open before trying to edit a cue expression.
              (seesaw/invoke-now (cues/open-cues phrase (:frame show))))
            (seesaw/invoke-now
               (let [[show phrase] (su/latest-show-and-phrase show phrase)
                     panel (get-in (su/phrase-runtime-info show phrase) [:cues-editor :panels (:uuid cue)])
                     gear  (when panel (seesaw/select panel [:#gear]))]
                 (if gear
                   (if (str/blank? kind)
                     (do  ; Just scroll the cues editor to this cue and bring it to the front.
                       (seesaw/show! (get-in phrase [:cues-editor :frame]))
                       (seesaw/invoke-later (cues/scroll-to-cue phrase cue))  ; This can block with a modal!
                       (window-brought-to-front "Cues Editor" "cue"))
                     (if (contains? @@(requiring-resolve 'beat-link-trigger.editors/show-cue-editors) (keyword kind))
                       (do
                         ((requiring-resolve 'beat-link-trigger.editors/show-cue-editor) (keyword kind) phrase cue panel
                          (fn []
                            (cues/update-all-linked-cues phrase cue)
                            (when gear (cues/update-cue-gear-icon phrase cue gear))))
                         (editor-opened-in-background))
                       (unrecognized-expression)))
                   (expression-report-error-response
                    "The Cues Editor window for the phrase could not be found or created."
                    "Problem Opening Editor")))))
          (cue-not-found "phrase"))
        (phrase-not-found))
      (show-not-enabled))
    (show-not-found)))

(defn- expression-section
  "Builds a section of the expressions report of `body` is not empty."
  [title id button-code button-title expressions]
  (when (seq expressions)
    [:div
     [:h2.title.is-4.mt-2.mb-0 {:id id} title
      (when button-code
        [:a.button.is-small.is-link.ml-5 {:href  (str "javascript:" button-code)
                                          :title button-title}
             [:img {:src   "/resources/cog.svg"
                    :width 15}]])]
     [:table.table
      [:thead
       [:tr [:td "Expression"] [:td {:colspan 2} "Actions"] [:td "Value"]]]
      [:tbody
       expressions]]]))

(defn describe-show-global-expression
  "When a global expression of a particular kind is not empty, builds a
  table row for it."
  [show editors kind]
  (let [value   (get-in show [:contents :expressions kind])
        default (get-in show [:contents :enabled])]
    (when-not (str/blank? value)
      [:tr
       [:td [:div.tooltip (get-in editors [kind :title]) [:span.tooltiptext (get-in editors [kind :tip])]]
        (when (and (= kind :enabled) (not= default "Custom"))
          [:span.has-text-danger [:br] "Inactive: Enabled Default is &ldquo;" default "&rdquo;"])]
       [:td]
       [:td [:a.button.is-small.is-link {:href  (str "javascript:editShowExpression('" (name kind) "');")
                                         :title "Edit"}
             [:img {:src   "/resources/pen-solid.svg"
                    :width 12}]]]
       [:td [:pre.expression [:code.expression.language-clojure value]]]])))

(defn- global-expressions
  "Builds the report of show global expressions."
  [show]
  (expression-section
   "Show-Level (Global) Expressions" "global"
   "editShowExpression();" "Bring this Show window to front"
   (let [editors @@(requiring-resolve 'beat-link-trigger.editors/global-show-editors)]
     (filter identity (map (partial describe-show-global-expression show editors)
                           (keys editors))))))

(defn- comment-or-untitled
  "Returns the supplied comment, unless that is blank, in which case
  returns \"Untitled\"."
  [comment]
  (if (str/blank? comment) "Untitled" comment))

(defn cue-expression-disabled-warning
  "Builds a warning message if the expression is not currently in use by
  the cue because the message for that event is set to something other
  than Custom."
  [cue kind]
  (case kind
    (:entered :exited)
    (let [message (get-in cue [:events :entered :message])]
      (when (not= message "Custom")
        [:span.has-text-danger [:br] "Inactive: Enabled Message is &ldquo;" message "&rdquo;"]))

    :started-on-beat
    (let [message (get-in cue [:events :started-on-beat :message])]
      (when (not= message "Custom")
        [:span.has-text-danger [:br] "Inactive: On-Beat Message is &ldquo;" message "&rdquo;"]))

    :started-late
    (let [late-message (get-in cue [:events :started-late :message])
          on-beat-message (get-in cue [:events :started-on-beat :message])]
      (if (= late-message "Same")
        (when (not= on-beat-message "Custom")
          [:span.has-text-danger [:br] "Inactive: On-Beat Message is &ldquo;" on-beat-message "&rdquo;"])
        (when (not= late-message "Custom")
          [:span.has-text-danger [:br] "Inactive: Late Message is &ldquo;" late-message "&rdquo;"])))

    :ended
    (let [late-message (get-in cue [:events :started-late :message])
          on-beat-message (get-in cue [:events :started-on-beat :message])
          late-message (if (= late-message "Same") on-beat-message late-message)]
      (when-not ((set [on-beat-message late-message]) "Custom")
        [:span.has-text-danger [:br] "Inactive: Neither On-Beat or Late is &ldquo;Custom&rdquo;"]))

    nil))

(defn- describe-track-cue-expression
  [signature cue editors kind]
  (let [value (get-in cue [:expressions kind])]
    (when-not (str/blank? value)
      [:tr
       [:td [:div.tooltip (get-in editors [kind :title]) [:span.tooltiptext (get-in editors [kind :tip])]]
        (cue-expression-disabled-warning cue kind)]
       [:td [:a.button.is-small.is-link {:href  (str "javascript:simulateTrackCueExpression('" signature "','"
                                                     (:uuid cue) "','" (name kind) "');")
                                         :title "Simulate"}
             [:img {:src   "/resources/play-solid.svg"
                    :width 12}]]]
       [:td [:a.button.is-small.is-link {:href  (str "javascript:editTrackCueExpression('" signature "','"
                                                     (:uuid cue) "','" (name kind) "');")
                                         :title "Edit"}
             [:img {:src   "/resources/pen-solid.svg"
                    :width 12}]]]
       [:td [:pre.code.expression [:code.expression.language-clojure value]]]])))

(defn- track-cue-expressions
  "Builds the report of expressions for a particular track cue."
  [signature track cue]
  (let [editors @@(requiring-resolve 'beat-link-trigger.editors/show-cue-editors)]
    (expression-section
     (str "Cue &ldquo;" (comment-or-untitled (:comment cue)) "&rdquo; in Track &ldquo;"
          (get-in track [:metadata :title]) "&rdquo;")
     (str "track-" signature "-cue-" (:uuid cue))
     (str "editTrackCueExpression('" signature "','" (:uuid cue) "');") "Scroll Cues Editor to this Cue"
     (filter identity (map (partial describe-track-cue-expression signature cue editors)
                           (keys editors))))))

(defn track-expression-disabled-warning
  "Builds a warning message if the expression is not currently in use by
  the track because the message for that event is set to something
  other than Custom."
  [track kind]
  (case kind
    (:loaded :unloaded)
    (let [message (get-in track [:contents :loaded-message])]
      (when (not= message "Custom")
        [:span.has-text-danger [:br] "Inactive: Loaded Message is &ldquo;" message "&rdquo;"]))

    (:playing :stopped)
    (let [message (get-in track [:contents :playing-message])]
      (when (not= message "Custom")
        [:span.has-text-danger [:br] "Inactive: Playing Message is &ldquo;" message "&rdquo;"]))

    :enabled
    (let [message (get-in track [:contents :enabled])]
      (when (not= message "Custom")
        [:span.has-text-danger [:br] "Inactive: Enabled Filter is &ldquo;" message "&rdquo;"]))

    nil))

(defn- describe-track-expression
  [signature track editors kind]
  (let [value (get-in track [:contents :expressions kind])]
    (when-not (str/blank? value)
      [:tr
       [:td [:div.tooltip (get-in editors [kind :title]) [:span.tooltiptext (get-in editors [kind :tip])]]
        (track-expression-disabled-warning track kind)]
       [:td (when (get-in editors [kind :simulate])
              [:a.button.is-small.is-link {:href  (str "javascript:simulateTrackExpression('" signature "','"
                                                       (name kind) "');")
                                           :title "Simulate"}
               [:img {:src   "/resources/play-solid.svg"
                      :width 12}]])]
       [:td [:a.button.is-small.is-link {:href  (str "javascript:editTrackExpression('" signature "','"
                                                     (name kind) "');")
                                         :title "Edit"}
             [:img {:src   "/resources/pen-solid.svg"
                    :width 12}]]]
       [:td [:pre.code.expression [:code.expression.language-clojure value]]]])))

(defn- track-expressions
  "Builds the report of expressions for a particular track, and any of
  its cues."
  [[signature track]]
  (let [editors     @@(requiring-resolve 'beat-link-trigger.editors/show-track-editors)
        track-level (expression-section
                     (str "Track &ldquo;" (get-in track [:metadata :title]) "&rdquo;")
                     (str "track-" signature)
                     (str "editTrackExpression('" signature "');") "Scroll Show to this Track"
                     (filter identity (map (partial describe-track-expression  signature track editors)
                                           (keys editors))))
        cue-level (filter identity (map (partial track-cue-expressions signature track)
                                        (vals (get-in track [:contents :cues :cues]))))]
    [:div (concat [track-level] cue-level)]))

(defn- describe-phrase-cue-expression
  [uuid cue editors kind]
  (let [value (get-in cue [:expressions kind])]
    (when-not (str/blank? value)
      [:tr
       [:td [:div.tooltip (get-in editors [kind :title]) [:span.tooltiptext (get-in editors [kind :tip])]]
        (cue-expression-disabled-warning cue kind)]
       [:td [:a.button.is-small.is-link {:href  (str "javascript:simulatePhraseCueExpression('" uuid "','"
                                                     (:uuid cue) "','" (name kind) "');")
                                         :title "Simulate"}
             [:img {:src   "/resources/play-solid.svg"
                    :width 12}]]]
       [:td [:a.button.is-small.is-link {:href  (str "javascript:editPhraseCueExpression('" uuid "','"
                                                     (:uuid cue) "','" (name kind) "');")
                                         :title "Edit"}
             [:img {:src   "/resources/pen-solid.svg"
                    :width 12}]]]
       [:td [:pre.code.expression [:code.expression.language-clojure value]]]])))

(defn- phrase-cue-expressions
  "Builds the report of expressions for a particular phrase trigger cue."
  [uuid phrase cue]
  (let [editors   @@(requiring-resolve 'beat-link-trigger.editors/show-cue-editors)]
    (expression-section
     (str "Cue &ldquo;" (comment-or-untitled (:comment cue)) "&rdquo; in Phrase Trigger &ldquo;"
          (comment-or-untitled (:comment phrase)) "&rdquo;")
     (str "phrase-" uuid "-cue-" (:uuid cue))
     (str "editPhraseCueExpression('" uuid "','" (:uuid cue) "');") "Scroll Cues Editor to this Cue"
     (filter identity (map (partial describe-phrase-cue-expression uuid cue editors)
                           (keys editors))))))

(defn phrase-expression-disabled-warning
  "Builds a warning message if the expression is not currently in use by
  the phrase because the message for that event is set to something
  other than Custom."
  [phrase kind]
  (case kind
    (:playing :stopped)
    (let [message (:message phrase)]
      (when (not= message "Custom")
        [:span.has-text-danger [:br] "Inactive: Playing Message is &ldquo;" message "&rdquo;"]))

    :enabled
    (let [message (:enabled phrase)]
      (when (not= message "Custom")
        [:span.has-text-danger [:br] "Inactive: Enabled Filter is &ldquo;" message "&rdquo;"]))

    nil))

(defn- describe-phrase-expression
  [uuid phrase editors kind]
  (let [value (get-in phrase [:expressions kind])]
    (when-not (str/blank? value)
      [:tr
       [:td [:div.tooltip (get-in editors [kind :title]) [:span.tooltiptext (get-in editors [kind :tip])]]
        (phrase-expression-disabled-warning phrase kind)]
       [:td (when (get-in editors [kind :simulate])
              [:a.button.is-small.is-link {:href  (str "javascript:simulatePhraseExpression('" uuid "','"
                                                       (name kind) "');")
                                           :title "Simulate"}
               [:img {:src   "/resources/play-solid.svg"
                      :width 12}]])]
       [:td [:a.button.is-small.is-link {:href  (str "javascript:editPhraseExpression('" uuid "','"
                                                     (name kind) "');")
                                         :title "Edit"}
             [:img {:src   "/resources/pen-solid.svg"
                    :width 12}]]]
       [:td [:pre.code.expression [:code.expression.language-clojure value]]]])))

(defn- phrase-expressions
  "Builds the report of expressions for a particular track, and any of
  its cues."
  [[uuid phrase]]
  (let [editors     @@(requiring-resolve 'beat-link-trigger.editors/show-phrase-editors)
        phrase-level (expression-section
                      (str "Phrase Trigger &ldquo;" (comment-or-untitled (:comment phrase)) "&rdquo;")
                      (str "phrase-" uuid)
                      (str "editPhraseExpression('" uuid "');") "Scroll Show to this Phrase Trigger"
                      (filter identity (map (partial describe-phrase-expression uuid phrase editors)
                                           (keys editors))))
        cue-level (filter identity (map (partial phrase-cue-expressions uuid phrase)
                                        (vals (get-in phrase [:cues :cues]))))]
    [:div (concat [phrase-level] cue-level)]))

(defn expressions-report
  "Return an HTML report of all the expressions used in the specified show."
  [path]
  (if-let [show (su/latest-show (io/file path))]
    (let [when (Date.)]
      (page/html5
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title (str "Expressions in Show " path)]
        (page/include-css "/resources/bulma.min.css" "/resources/highlight.min.css" "/resources/report.css")
        (page/include-js "/resources/highlight.min.js")
        [:script
         (str "var showFile='" (hiccup.util/url-encode path) "';\n")
         "hljs.highlightAll();"]
        (page/include-js "/resources/expression-report.js")
        [:body {:onfocus "closeAllModals();"}
         [:section.section
          [:div.container
           [:h1.title "Expressions in Show " [:span.has-text-primary path]]
           [:p.subtitle "Report generated at " (.format (SimpleDateFormat. "HH:mm:ss yyyy/dd/MM") when) "."]
           (global-expressions show)
           (filter identity (map track-expressions (:tracks show)))
           (filter identity (map phrase-expressions (get-in show [:contents :phrases])))]]
         [:div.modal {:id "error-modal"}
          [:div.modal-background]
          [:div.modal-card
           [:header.modal-card-head
            [:p.modal-card-title {:id "error-modal-title"} "Modal title"]
            [:button.delete {:aria-label "close"}]]
           [:section.modal-card-body {:id "error-modal-body"}
            "Modal content here!"]
           [:footer.modal-card-foot
            [:button.button "OK"]]]]]]))
    (route/not-found "<p>Show not found.</p>")))
