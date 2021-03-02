(ns beat-link-trigger.show-phrases
  "Implements phrase trigger features for Show files, including their
  cue editing windows."
  (:require [beat-link-trigger.editors :as editors]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.show-cues :as cues]
            [beat-link-trigger.show-util :as su :refer [latest-show latest-phrase latest-show-and-phrase
                                                        swap-show! swap-phrase! swap-phrase-runtime!
                                                        phrase-runtime-info find-phrase-cue swap-phrase-cue!]]
            [beat-link-trigger.util :as util]
            [clojure.set]
            [clojure.string :as str]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [beat_link_trigger.util MidiChoice]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$SongStructureTag]
           [java.awt BasicStroke Color Cursor Graphics2D Rectangle RenderingHints]
           [java.awt.event InputEvent MouseEvent]
           [java.awt.geom Rectangle2D$Double]
           [java.util UUID]
           [javax.swing JComponent JFrame JOptionPane JPanel JScrollPane]
           [javax.swing.text JTextComponent]
           [jiconfont.icons.font_awesome FontAwesome]
           [jiconfont.swing IconFontSwing]))

(defn- run-phrase-function
  "Checks whether the phrase trigger has a custom function of the
  specified kind installed and if so runs it with the supplied status
  argument and the track local and global atoms. Returns a tuple of
  the function return value and any thrown exception. If `alert?` is
  `true` the user will be alerted when there is a problem running the
  function."
  [show phrase kind status alert?]
  (let [[show phrase] (latest-show-and-phrase show phrase)
        runtime-info  (su/phrase-runtime-info show phrase)]
    (when-let [expression-fn (get-in runtime-info [:expression-fns kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(expression-fn status {:locals (:expression-locals runtime-info)
                                  :show   show
                                  :phrase phrase} (:expression-globals show)) nil])
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/show-editor-title kind show phrase) ":\n"
                               (get-in phrase [:contents :expressions kind])))
          (when alert? (seesaw/alert (str "<html>Problem running phrase trigger " (name kind) " expression.<br><br>" t)
                                     :title "Exception in Show Phrase Trigger Expression" :type :error))
          [nil t])))))

(defn run-cue-function
  "Checks whether the cue has a custom function of the specified kind
  installed and if so runs it with the supplied status or beat
  argument, the cue, and the track local and global atoms. Returns a
  tuple of the function return value and any thrown exception. If
  `alert?` is `true` the user will be alerted when there is a problem
  running the function."
  [show phrase section cue kind status-or-beat alert?]
  (let [[show phrase] (latest-show-and-phrase show phrase)
        runtime-info  (su/phrase-runtime-info show phrase)
        cue           (find-phrase-cue show phrase section cue)]
    (when-let [expression-fn (get-in runtime-info [:cues :expression-fns (:uuid cue) kind])]
      (try
        (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
          [(expression-fn status-or-beat {:locals  (:expression-locals runtime-info)
                                          :show    show
                                          :track   phrase
                                          :section section
                                          :cue     cue}
                          (:expression-globals show)) nil])
        (catch Throwable t
          (timbre/error t (str "Problem running " (editors/phrase-cue-editor-title kind phrase section cue) ":\n"
                               (get-in cue [:expressions kind])))
          (when alert? (seesaw/alert (str "<html>Problem running phrase " (name section) " cue " (name kind)
                                          " expression.<br><br>" t)
                                     :title "Exception in Show Cue Expression" :type :error))
          [nil t])))))

(defn update-cue-gear-icon
  "Determines whether the gear button for a cue should be hollow or
  filled in, depending on whether any expressions have been assigned
  to it."
  [show phrase section cue gear]
  (let [cue (find-phrase-cue show phrase section cue)]
    (seesaw/config! gear :icon (if (every? clojure.string/blank? (vals (:expressions cue)))
                                 (seesaw/icon "images/Gear-outline.png")
                                 (seesaw/icon "images/Gear-icon.png")))))

;; TODO: Continue porting over functions from start of show-cues

(defn- paint-phrase-state
    "Draws a representation of the state of the phrase trigger, including whether
  it is enabled and whether it has been selected and is playing."
  [show uuid c ^Graphics2D g]
  (let [w        (double (seesaw/width c))
        h        (double (seesaw/height c))
        outline  (java.awt.geom.Ellipse2D$Double. 1.0 1.0 (- w 2.5) (- h 2.5))
        show     (latest-show show)
        phrase    (get-in show [:contents :phrases uuid])
        enabled? false  ; TODO: (enabled? show phrase)
        active?  false] ; TODO: TBD!
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (when active? ; Draw the inner filled circle showing the track is loaded or playing.
      (.setPaint g (if enabled? Color/green Color/lightGray))
      (.fill g (java.awt.geom.Ellipse2D$Double. 4.0 4.0 (- w 8.0) (- h 8.0))))

    ;; Draw the outer circle that reflects the enabled state.
    (.setStroke g (java.awt.BasicStroke. 2.0))
    (.setPaint g (if enabled? Color/green Color/red))
    (.draw g outline)
    (when-not enabled?
      (.clip g outline)
      (.draw g (java.awt.geom.Line2D$Double. 1.0 (- h 1.5) (- w 1.5) 1.0)))))

(def canvas-margin
  "The number of pixels left black around the border of the canvases on
  which sections and cues are drawn."
  4)

(defn total-bars
  "Returns the sum of the bar sizes of all four sections of a phrase,
  which must be current."
  [{:keys [start-bars loop-bars end-bars fill-bars]
    :or   {start-bars 0
           loop-bars  1
           end-bars   0
           fill-bars  1}}]
  (+ start-bars loop-bars end-bars fill-bars))

(defn- bar-spacing
  "Calculate how many pixels apart each bar occurs given the total
  number of bars in the phrase and width of the component in which
  they are being rendered."
  [bars width]
  (quot (- width (* 2 canvas-margin)) bars))

(defn bar-x
  "Calculate the x coordinate of a bar given the bar spacing."
  [bar spacing] (+ canvas-margin (* bar spacing)))

(defn update-section-boundaries
  "Recalculates the cached information that makes it easier to know
  where each section starts and ends, for the purposes of painting and
  interpreting mouse clicks. Sets the key `:sections` in the runtime
  (unsaved) phrase information to a map with the following content:

  `:start`, `:loop`, `:end`, and `:fill` are tuples of the starting
  bar (inclusive) and ending bar (exclusive) of each section. `:start`
  and `:end` will be missing if those sections have zero length.

  `:total-bars` is the sum of the sizes of all sections in the phrase.

  `:intervals` is an interval map which can be queried using `su/iget`
  to determine which section, if any, falls at the specified bar."
  [show phrase-or-uuid]
  (let [uuid                         (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))
        show                         (latest-show show)
        phrase                       (get-in show [:contents :phrases uuid])
        {:keys [start-bars loop-bars
                end-bars fill-bars]} phrase
        start                        (when (pos? start-bars) [0 start-bars])
        loop                         (if-let [[_ start-end] start]
                                       [start-end (+ start-end loop-bars)]
                                       [0 loop-bars])
        end                          (when (pos? end-bars) (let [[_ loop-end] loop] [loop-end (+ loop-end end-bars)]))
        fill                         (if-let [[_ end-end] end]
                                       [end-end (+ end-end fill-bars)]
                                       (let [[_ loop-end] loop] [loop-end (+ loop-end fill-bars)]))]
    (swap-show! show assoc-in [:phrases uuid :sections]
                (merge {:total-bars (+ start-bars loop-bars end-bars fill-bars)
                        :loop       loop
                        :fill       fill
                        :intervals  (as-> util/empty-interval-map $
                                      (if start (apply util/iassoc $ (concat start [:start])) $)
                                      (apply util/iassoc $ (concat loop [:loop]))
                                      (if end (apply util/iassoc $ (concat end [:end])) $)
                                      (apply util/iassoc $ (concat fill [:fill])))}
                       (when start {:start start})
                       (when end {:end end})))))

(defn- paint-phrase-preview
  "Draws the compact view of the phrase shown within the Show window
  row, identifying the relative sizes of the sections, and positions
  of cues within them."
  [show uuid c ^Graphics2D g]
  (let [w        (double (seesaw/width c))
        h        (double (seesaw/height c))
        show     (latest-show show)
        phrase   (get-in show [:contents :phrases uuid])
        sections (get-in show [:phrases uuid :sections])
        active?  false ; TODO: TBD!
        bars     (total-bars phrase)
        spacing  (bar-spacing bars w)
        stroke   (.getStroke g)
        stripe   (fn [color y [from-bar to-bar]]  ; Paint one of the section stripes.
                   (.setPaint g color)
                   (.fillRect g (+ canvas-margin (* from-bar spacing)) y (dec (* (- to-bar from-bar) spacing)) 3))
        fence    (fn [[_from-bar to-bar]]  ; Paint one of the section boundary fences.
                   (let [x (bar-x to-bar spacing)]
                     (.drawLine g x canvas-margin x (- h canvas-margin))))]

    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)

    (.setPaint g Color/black)
    (.fill g (java.awt.geom.Rectangle2D$Double. 0.0 0.0 w h))

    ;; Paint the section stripes.
    (let [y (- h canvas-margin 2)]
      (when-let [start (:start sections)]
        (stripe su/phrase-start-color y start))
      (stripe su/phrase-loop-color y (:loop sections))
      (when-let [end (:end sections)]
        (stripe su/phrase-end-color y end))
      (stripe su/phrase-fill-color y (:fill sections))

      ;; Paint the section boundaries.
      (.setPaint g Color/white)
      (.setStroke g (BasicStroke. 1 BasicStroke/CAP_BUTT BasicStroke/JOIN_ROUND 1.0
                                  (float-array [3.0 3.0]) 1.0))
      (when-let [start (:start sections)]
        (fence start))
      (fence (:loop sections))
      (when-let [end (:end sections)]
        (fence end)))
    (.setStroke g stroke)

    (when (>= spacing 4)  ; There's enough room to draw bar lines.
      (.setPaint g Color/red)
      (doseq [bar (range (inc bars))]
        (let [x (bar-x bar spacing)]
          (.drawLine g x canvas-margin x (+ canvas-margin 4))
          (.drawLine g x (- h canvas-margin 8) x (- h canvas-margin 4)))))

    (let [beat-spacing (quot spacing 4)]
      (when (>= beat-spacing 4)  ; There is enough room to draw beat lines.
        (.setPaint g Color/white)
        (doseq [bar (range bars)]
          (doseq [beat (range 1 4)]
            (let [x (+ (bar-x bar spacing) (* beat beat-spacing))]
              (.drawLine g x canvas-margin x (+ canvas-margin 4))
              (.drawLine g x (- h canvas-margin 9) x (- h canvas-margin 5)))))))

    ;; TODO: Draw the cues and selection.
    ))

;; TODO: Before hooking this up, the cues window needs to be updated
;; to cope with phrase trigger cues!
(defn- edit-cues-action
  "Creates the menu action which opens the phrase trigger's cue editor
  window."
  [show phrase panel]
  (seesaw/action :handler (fn [_] (cues/open-cues show phrase panel))
                 :name "Edit Phrase Cues"
                 :tip "Set up cues that react to particular sections of the phrase being played."
                 :icon (if (every? empty? (vals (get-in (latest-phrase show phrase) [:cues :cues])))
                         "images/Gear-outline.png"
                         "images/Gear-icon.png")))

(defn- phrase-missing-expression?
  "Checks whether the expression body of the specified kind is empty for
  the specified phrase trigger."
  [show phrase kind]
  (str/blank? (get-in (latest-phrase show phrase) [:expressions kind])))

(defn- phrase-editor-actions
  "Creates the popup menu actions corresponding to the available
  expression editors for a given phrase trigger."
  [show phrase panel gear]
  (for [[kind spec] @editors/show-phrase-editors]
    (let [update-fn (fn []
                      (when (= kind :setup)  ; Clean up then run the new setup function
                        (run-phrase-function show phrase :shutdown nil true)
                        (let [runtime-info (su/phrase-runtime-info (latest-show show) phrase)]
                          (reset! (:expression-locals runtime-info) {}))
                        (run-phrase-function show phrase :setup nil true))
                      (su/update-phrase-gear-icon show phrase gear))]
      (seesaw/action :handler (fn [_] (editors/show-show-editor kind (latest-show show)
                                       (latest-phrase show phrase) panel update-fn))
                     :name (str "Edit " (:title spec))
                     :tip (:tip spec)
                     :icon (if (phrase-missing-expression? show phrase kind)
                             "images/Gear-outline.png"
                             "images/Gear-icon.png")))))

(defn- expunge-deleted-phrase
  "Removes all the items from a show that need to be cleaned up when the
  phrase trigger has been deleted. This function is designed to be
  used in a single swap! call for simplicity and efficiency."
  [show phrase panel]
  (-> show
      (update :phrases dissoc (:uuid phrase))
      (update :panels dissoc panel)
      (update-in [:contents :phrases] dissoc (:uuid phrase))
      (update-in [:contents :phrase-order] (fn [old-order] (filterv (complement #{(:uuid phrase)}) old-order)))
      #_(update :playing remove-signature (:signature track))))  ; TODO: What is the equivalent for phrases?

(defn- close-phrase-editors?
  "Tries closing all open expression and cue editors for the phrase
  trigger. If `force?` is true, simply closes them even if they have
  unsaved changes. Otherwise checks whether the user wants to save any
  unsaved changes. Returns truthy if there are none left open the user
  wants to deal with (and in that case also closes any phrase type or
  track mood pickers the user may have opened on the phrase trigger."
  [force? show phrase]
  (let [[show phrase] (latest-show-and-phrase show phrase)
        runtime-info  (phrase-runtime-info show phrase)
        closed        (and
                       (every? (partial editors/close-editor? force?) (vals (:expression-editors runtime-info)))
                       (or (not (:cues-editor runtime-info)) ((get-in runtime-info [:cues-editor :close-fn]) force?)))]
    (when closed
      (when-let [picker (:phrase-type-picker runtime-info)] (.dispose picker))
      (when-let [picker (:track-bank-picker runtime-info)] (.dispose picker)))
    closed))

(defn cleanup-phrase
  "Process the removal of a phrase trigger, either via deletion, or
  because the show is closing. If `force?` is true, any unsaved
  expression editors will simply be closed. Otherwise, they will block
  the phrase trigger removal, which will be indicated by this function
  returning falsey. Run any appropriate custom expressions and send
  configured MIDI messages to reflect the departure of the phrase
  trigger."
  [force? show phrase]
  (when (close-phrase-editors? force? show phrase)
    (let [[show phrase] (latest-show-and-phrase show phrase)
          runtime-info (phrase-runtime-info show phrase)]
      (when (:tripped runtime-info)
        (doseq [[section cues] (get-in phrase [:cues :cues])
                cue             cues]
          #_(cues/cleanup-phrase-cue true phrase section cue))  ; TODO: Something like this?
        #_(when ((set (vals (:playing show))) (:signature track))
          (send-stopped-messages track nil))  ; TODO: And something like this?
        )
      (run-phrase-function show phrase :shutdown nil (not force?)))
    true))

(defn- delete-phrase-action
  "Creates the menu action which deletes a phrase trigger after confirmation."
  [show phrase panel]
  (seesaw/action :handler (fn [_]
                            (when (util/confirm panel
                                                (str "This will irreversibly remove the phrase trigger, losing\r\n"
                                                     "any configuration, expressions, and cues created for it.")
                                                :type :warning :title "Delete Phrase Trigger?")
                              (try
                                (cleanup-phrase true show phrase)
                                (swap-show! show expunge-deleted-phrase phrase panel)
                                #_(refresh-signatures show)  ; TODO: Is there a phrase equivalent?
                                (su/update-row-visibility show)
                                (catch Exception e
                                  (timbre/error e "Problem deleting phrase trigger")
                                  (seesaw/alert (str e) :title "Problem Deleting Phrase Trigger" :type :error)))))
                 :name "Delete Phrase Trigger"))

(defn- update-phrase-comboboxes
  "Called when a phrase triger row has been created in a show, or the
  phrase trigger contents have been replaced, to update the combo-box
  elements to reflect the phrase trigger's state."
  [phrase panel]
  (seesaw/selection! (seesaw/select panel [:#outputs]) (or (:midi-device phrase) (first (util/get-midi-outputs))))
  (seesaw/selection! (seesaw/select panel [:#message]) (or (:message phrase) "None"))
  (seesaw/selection! (seesaw/select panel [:#solo]) (or (:solo phrase) "Show"))
  (seesaw/selection! (seesaw/select panel [:#enabled]) (or (:enabled phrase) "See Below")))

(defn parse-phrase-expressions
  "Parses all of the expressions associated with a phrase trigger and
  its cues. `phrase` must be current."
  [show phrase]
  (doseq [[kind expr] (editors/sort-setup-to-front (get-in phrase [:contents :expressions]))]
    (let [editor-info (get @editors/show-track-editors kind)]  ; TODO: This should be show-phrase-editors?
        (try
          (swap-phrase! show phrase assoc-in [:expression-fns kind]
                        ;; TODO: this needs to use enw show-phrase-editor-title!
                        (expressions/build-user-expression expr (:bindings editor-info) (:nil-status? editor-info)
                                                           (editors/show-editor-title kind show phrase)))
              (catch Exception e
                (timbre/error e (str "Problem parsing " (:title editor-info)
                                     " when loading Show. Expression:\n" expr "\n"))
                (seesaw/alert (str "<html>Unable to use " (:title editor-info) ".<br><br>"
                                   "Check the log file for details.")
                              :title "Exception during Clojure evaluation" :type :error)))))
  ;; Parse any custom expressions defined for cues in the track.
  (doseq [[section cues] (vals (get-in phrase [:contents :cues :cues]))
          cue             cues]
    #_(cues/compile-cue-phrae-expressions phrase section cue)))  ; TODO: Implement!

(defn- build-filter-target
  "Creates a string that can be matched against to filter a phrase
  trigger by text substring, taking into account the custom comment
  assigned to the phrase trigger in the show, if any."
  [comment]
  (str/lower-case (or comment "")))

(defn get-chosen-output
  "Return the MIDI output to which messages should be sent for a given
  phrase trigger, opening it if this is the first time we are using
  it, or reusing it if we already opened it. Returns `nil` if the
  output can not currently be found (it was disconnected, or present
  in a loaded file but not on this system). to be reloaded."
  [show phrase]
  (when-let [^MidiChoice selection (:midi-device (latest-phrase show phrase))]
    (let [device-name (.full_name selection)]
      (or (get @util/opened-outputs device-name)
          (try
            (let [new-output (midi/midi-out (str "^" (java.util.regex.Pattern/quote device-name) "$"))]
              (swap! util/opened-outputs assoc device-name new-output)
              new-output)
            (catch IllegalArgumentException e  ; The chosen output is not currently available
              (timbre/debug e "Phrase trigger using nonexisting MIDI output" device-name))
            (catch Exception e  ; Some other problem opening the device
              (timbre/error e "Problem opening device" device-name "(treating as unavailable)")))))))

(defn no-output-chosen
  "Returns truthy if the MIDI output menu for a phrase trigger is empty,
  which will probably only happen if there are no MIDI outputs
  available on the host system, but we still want to allow non-MIDI
  expressions to operate."
  [show phrase]
  (not (:midi-device (latest-phrase show phrase))))

(defn show-midi-status
  "Set the visibility of the Enabled menu and the text and color
  of its label based on whether the currently-selected MIDI output can
  be found. This function must be called on the Swing Event Update
  thread since it interacts with UI objects."
  [show phrase]
  (try
    (let [[show phrase] (latest-show-and-phrase show phrase)
          panel         (get-in show [:phrases (:uuid phrase) :panel])
          enabled-label (seesaw/select panel [:#enabled-label])
          enabled       (seesaw/select panel [:#enabled])
          output        (get-chosen-output show phrase)]
      (if (or output (no-output-chosen show phrase))
        (do (seesaw/config! enabled-label :foreground "white")
            (seesaw/value! enabled-label "Enabled:")
            (seesaw/config! enabled :visible? true))
        (do (seesaw/config! enabled-label :foreground "red")
            (seesaw/value! enabled-label "MIDI Output not found.")
            (seesaw/config! enabled :visible? false))))
    (catch Exception e
      (timbre/error e "Problem showing Phrase Trigger MIDI status."))))

(defn- attach-phrase-custom-editor-opener
  "Sets up an action handler so that when a menu is set to Custom,
  if there is not already a custom expression of the appropriate kind
  present, an editor for that expression is automatically opened."
  [show phrase panel menu kind gear]
  (seesaw/listen menu :action-performed
                 (fn [_]
                   (let [choice        (seesaw/selection menu)
                         [show phrase] (latest-show-and-phrase show phrase)]
                     (when (and (= "Custom" choice)
                                (not (:creating phrase))
                                (str/blank?
                                 (get-in phrase [:expressions kind])))
                       ;; TODO: Implement this.
                       #_(editors/show-phrase-editor kind show phrase panel
                                                   #(su/update-phrase-gear-icon show phrase gear)))))))

(defn- attach-phrase-message-visibility-handler
  "Sets up an action handler so that when the message menu is changed,
  the appropriate UI elements are shown or hidden. Also arranges for
  the proper expression editor to be opened if Custom is chosen for
  the message type and that expression is currently empty."
  [show phrase panel gear]
  (let [message-menu    (seesaw/select panel [:#message])
        note-spinner    (seesaw/select panel [:#note])
        label           (seesaw/select panel [:#channel-label])
        channel-spinner (seesaw/select panel [:#channel])]
    (seesaw/listen message-menu
                   :action-performed (fn [_]
                                       (let [choice (seesaw/selection message-menu)]
                                         (if (= "None" choice)
                                           (seesaw/hide! [note-spinner label channel-spinner])
                                           (seesaw/show! [note-spinner label channel-spinner])))))
    (attach-phrase-custom-editor-opener show phrase panel message-menu :playing gear)))

(def phrase-types
  "Defines the types of track phrase which can be enabled for a phrase
  trigger, along with the labels that should be used in checkboxes to
  enable or disable them."
  {:low-intro   "Intro"
   :low-verse-1 "Verse 1"
   :low-verse-2 "Verse 2"
   :low-bridge  "Bridge"
   :low-chorus  "Chorus"
   :low-outro   "Outro"

   :mid-intro   "Intro"
   :mid-verse-1 "Verse 1"
   :mid-verse-2 "Verse 2"
   :mid-verse-3 "Verse 3"
   :mid-verse-4 "Verse 4"
   :mid-verse-5 "Verse 5"
   :mid-verse-6 "Verse 6"
   :mid-bridge  "Bridge"
   :mid-chorus  "Chorus"
   :mid-outro   "Outro"

   :high-intro-1  "Intro 1"
   :high-intro-2  "Intro 2"
   :high-up-1     "Up 1"
   :high-up-2     "Up 2"
   :high-up-3     "Up 3"
   :high-down     "Down"
   :high-chorus-1 "Chorus 1"
   :high-chorus-2 "Chorus 2"
   :high-outro    "Outro"})

(defn- update-phrase-type-label
  "Changes the text of the phrase types label in a phrase trigger row to
  reflect how many are enabled."
  [show phrase types-label]
  (when-not (:enabled-phrase-types (latest-phrase show phrase))
      ;; This must be a newly created phrase, and the set does not yet
      ;; exist. Initialize it to contain all possible phrase types.
      (swap-phrase! show phrase assoc :enabled-phrase-types (set (keys phrase-types))))
  (let [phrase        (latest-phrase show phrase)
        enabled-count (count (:enabled-phrase-types phrase))]
    (seesaw/text! types-label
                  (case enabled-count
                    0  "[None]"
                    25 "[All]"
                    (str "[" enabled-count "]")))))

(defn- build-phrase-type-checkbox
  "Creates a checkbox that reflects and manages the enabled state of a
  phrase type for a phrase trigger."
  [show uuid types-label phrase-type]
  (let [phrase (latest-phrase show {:uuid uuid})]
    (seesaw/checkbox :text (phrase-types phrase-type)
                     :selected? ((:enabled-phrase-types phrase) phrase-type)
                     :listen [:item-state-changed
                              (fn [e]
                                (swap-phrase! show uuid update :enabled-phrase-types
                                              (if (seesaw/value e) conj disj) phrase-type)
                                (update-phrase-type-label show phrase types-label))])))

(defn- build-checkbox-group-button
  "Creates a button that checks or unchecks an entire category of
  checkboxes."
  [selected? checkboxes]
  (seesaw/button :text (if selected? "All" "None")
                 :listen [:action (fn [_]
                                    (doseq [checkbox checkboxes]
                                      (seesaw/value! checkbox selected?)))]))

(defn- show-phrase-type-picker
  "Opens (or brings to the front, if it is already open) a window for
  selecting which phrase types a phrase trigger will activate for."
  [show uuid types-label]
  (if-let [^JFrame frame (:phrase-type-picker (phrase-runtime-info (latest-show show) uuid))]
    (.toFront frame)
    (let [low-intro   (build-phrase-type-checkbox show uuid types-label :low-intro)
          low-verse-1 (build-phrase-type-checkbox show uuid types-label :low-verse-1)
          low-verse-2 (build-phrase-type-checkbox show uuid types-label :low-verse-2)
          low-bridge  (build-phrase-type-checkbox show uuid types-label :low-bridge)
          low-chorus  (build-phrase-type-checkbox show uuid types-label :low-chorus)
          low-outro   (build-phrase-type-checkbox show uuid types-label :low-outro)
          low-types   [low-intro low-verse-1 low-verse-2 low-bridge low-chorus low-outro]

          mid-intro   (build-phrase-type-checkbox show uuid types-label :mid-intro)
          mid-verse-1 (build-phrase-type-checkbox show uuid types-label :mid-verse-1)
          mid-verse-2 (build-phrase-type-checkbox show uuid types-label :mid-verse-2)
          mid-verse-3 (build-phrase-type-checkbox show uuid types-label :mid-verse-3)
          mid-verse-4 (build-phrase-type-checkbox show uuid types-label :mid-verse-4)
          mid-verse-5 (build-phrase-type-checkbox show uuid types-label :mid-verse-5)
          mid-verse-6 (build-phrase-type-checkbox show uuid types-label :mid-verse-6)
          mid-bridge  (build-phrase-type-checkbox show uuid types-label :mid-bridge)
          mid-chorus  (build-phrase-type-checkbox show uuid types-label :mid-chorus)
          mid-outro   (build-phrase-type-checkbox show uuid types-label :mid-outro)
          mid-types   [mid-intro mid-verse-1 mid-verse-2 mid-verse-3 mid-verse-4 mid-verse-5 mid-verse-6
                       mid-bridge mid-chorus mid-outro]

          high-intro-1  (build-phrase-type-checkbox show uuid types-label :high-intro-1)
          high-intro-2  (build-phrase-type-checkbox show uuid types-label :high-intro-2)
          high-up-1     (build-phrase-type-checkbox show uuid types-label :high-up-1)
          high-up-2     (build-phrase-type-checkbox show uuid types-label :high-up-2)
          high-up-3     (build-phrase-type-checkbox show uuid types-label :high-up-3)
          high-down     (build-phrase-type-checkbox show uuid types-label :high-down)
          high-chorus-1 (build-phrase-type-checkbox show uuid types-label :high-chorus-1)
          high-chorus-2 (build-phrase-type-checkbox show uuid types-label :high-chorus-2)
          high-outro    (build-phrase-type-checkbox show uuid types-label :high-outro)
          high-types    [high-intro-1 high-intro-2 high-up-1 high-up-2 high-up-3 high-down
                         high-chorus-1 high-chorus-2 high-outro]

          ^JPanel panel (mig/mig-panel
                         :items [["Low Mood Phrases:     "]
                                 ["Mid Mood Phrases:     "]
                                 ["High Mood Phrases:" "wrap"]

                                 [low-intro]
                                 [mid-intro]
                                 [high-intro-1 "wrap"]

                                 [low-verse-1]
                                 [mid-verse-1]
                                 [high-intro-2 "wrap"]

                                 [low-verse-2]
                                 [mid-verse-2]
                                 [high-up-1 "wrap"]

                                 [low-bridge]
                                 [mid-verse-3]
                                 [high-up-2 "wrap"]

                                 [low-chorus]
                                 [mid-verse-4]
                                 [high-up-3 "wrap"]

                                 [low-outro]
                                 [mid-verse-5]
                                 [high-down "wrap"]

                                 [""]
                                 [mid-verse-6]
                                 [high-chorus-1 "wrap"]

                                 [""]
                                 [mid-bridge]
                                 [high-chorus-2 "wrap"]

                                 [""]
                                 [mid-chorus]
                                 [high-outro "wrap"]

                                 [""]
                                 [mid-outro "wrap unrelated"]

                                 [(build-checkbox-group-button false low-types)]
                                 [(build-checkbox-group-button false mid-types)]
                                 [(build-checkbox-group-button false high-types) "wrap"]

                                 [(build-checkbox-group-button true low-types)]
                                 [(build-checkbox-group-button true mid-types)]
                                 [(build-checkbox-group-button true high-types) "wrap"]])
          phrase       (latest-phrase show {:uuid uuid})
          ^JFrame root (seesaw/frame :title (str "Enabled Phrase Types for " (su/phrase-display-title phrase))
                                     :on-close :dispose :content panel
                                     :listen [:window-closing
                                              (fn [_] (swap-phrase-runtime! show uuid dissoc :phrase-type-picker))])]
      (.pack root)
      (.setLocationRelativeTo root (.getParent types-label))
      (swap-phrase-runtime! show uuid assoc :phrase-type-picker root)
      (seesaw/show! root))))

(def track-banks
  "Defines the types of track bank which can be enabled for a phrase
  trigger, along with the labels that should be used in checkboxes to
  enable or disable them."
  {:cool    "Cool"
   :natural "Natural"
   :hot     "Hot"
   :subtle  "Subtle"
   :warm    "Warm"
   :vivid   "Vivid"
   :club-1  "Club 1"
   :club-2  "Club 2"})

(defn- update-track-bank-label
  "Changes the text of the track banks label in a phrase trigger row to
  reflect how many are enabled."
  [show phrase banks-label]
  (when-not (:enabled-track-banks (latest-phrase show phrase))
      ;; This must be a newly created phrase, and the set does not yet
      ;; exist. Initialize it to contain all possible track banks.
      (swap-phrase! show phrase assoc :enabled-track-banks (set (keys track-banks))))
  (let [phrase        (latest-phrase show phrase)
        enabled-count (count (:enabled-track-banks phrase))]
    (seesaw/text! banks-label
                  (case enabled-count
                    0  "[None]"
                    8 "[All]"
                    (str "[" enabled-count "]")))))

(defn- build-track-bank-checkbox
  "Creates a checkbox that reflects and manages the enabled state of a
  track bank for a phrase trigger."
  [show uuid banks-label track-bank]
  (let [phrase (latest-phrase show {:uuid uuid})]
    (seesaw/checkbox :text (track-banks track-bank)
                     :selected? ((:enabled-track-banks phrase) track-bank)
                     :listen [:item-state-changed
                              (fn [e]
                                (swap-phrase! show uuid update :enabled-track-banks
                                              (if (seesaw/value e) conj disj) track-bank)
                                (update-track-bank-label show phrase banks-label))])))

(defn- show-track-bank-picker
  "Opens (or brings to the front, if it is already open) a window for
  selecting which track banks a trigger will activate for."
  [show uuid banks-label]
  (if-let [^JFrame frame (:track-bank-picker (phrase-runtime-info (latest-show show) uuid))]
    (.toFront frame)
    (let [cool    (build-track-bank-checkbox show uuid banks-label :cool)
          natural (build-track-bank-checkbox show uuid banks-label :natural)
          hot     (build-track-bank-checkbox show uuid banks-label :hot)
          subtle  (build-track-bank-checkbox show uuid banks-label :subtle)
          warm    (build-track-bank-checkbox show uuid banks-label :warm)
          vivid   (build-track-bank-checkbox show uuid banks-label :vivid)
          club-1  (build-track-bank-checkbox show uuid banks-label :club-1)
          club-2  (build-track-bank-checkbox show uuid banks-label :club-2)
          banks   [cool natural hot subtle warm vivid club-1 club-2]

          ^JPanel panel (mig/mig-panel
                         :items [[cool]
                                 [natural "wrap"]
                                 [hot]
                                 [subtle "wrap"]
                                 [warm]
                                 [vivid "wrap"]
                                 [club-1]
                                 [club-2 "wrap unrelated"]


                                 [(build-checkbox-group-button false banks) "spanx 2, align center, wrap"]
                                 [(build-checkbox-group-button true banks) "spanx 2, align center, wrap"]])

          ^JFrame root (seesaw/frame :title "Track Banks"
                                     :on-close :dispose :content panel
                                     :listen [:window-closing
                                              (fn [_] (swap-phrase-runtime! show uuid dissoc :track-bank-picker))])]
      (.pack root)
      (.setLocationRelativeTo root (.getParent banks-label))
      (swap-phrase-runtime! show uuid assoc :track-bank-picker root)
      (seesaw/show! root))))

(defn- phrase-panel-constraints
  "Calculates the proper layout constraints for a prhase trigger panel
  to look right at a given window width."
  [width]
  (let [text-width (max 100 (int (/ (- width 142) 4)))
        preview-width (max 890 (- width text-width 100))]
    ["" (str "[]unrelated[][]unrelated[][][fill, " preview-width "]")]))

(defn- create-phrase-panel
  "Creates a panel that represents a phrase trigger in the show. Updates
  tracking indices appropriately."
  [show uuid]
  (let [show           (latest-show show)
        phrase         (get-in show [:contents :phrases uuid])
        update-comment (fn [c]
                         (let [comment (seesaw/text c)]
                           (swap-phrase! show uuid assoc :comment comment)
                           (swap-show! show assoc-in [:phrases uuid :filter] (str/lower-case (or comment "")))))
        comment-field  (seesaw/text :id :comment :paint (partial util/paint-placeholder "Comment")
                                    :text (:comment phrase "") :listen [:document update-comment])
        preview        (seesaw/canvas :id :preview :paint (partial paint-phrase-preview show uuid))
        outputs        (util/get-midi-outputs)
        gear           (seesaw/button :id :gear :icon (seesaw/icon "images/Gear-outline.png"))
        types-label    (seesaw/label :id :types-label :text "[?]")
        types          (seesaw/button :id :phrase-types :text "Phrase Types"
                                      :listen [:action (fn [_] (show-phrase-type-picker show uuid types-label))])
        banks-label    (seesaw/label :id :banks-label :text "[All]")
        banks          (seesaw/button :id :banks :text "Track Banks"
                                      :listen [:action (fn [_] (show-track-bank-picker show uuid banks-label))])
        min-bars       (seesaw/spinner :id :min-bars :model (seesaw/spinner-model (:min-bars phrase 2) :from 2 :to 64)
                                       :enabled? (:min-bars? phrase)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :min-bars
                                                                              (seesaw/value %))])
        min-bars-cb    (seesaw/checkbox :id :min-bars-cb :text "Min bars:" :selected? (:min-bars? phrase)
                                        :listen [:item-state-changed
                                                 (fn [e]
                                                   (swap-phrase! show uuid assoc :min-bars? (seesaw/value e))
                                                   (seesaw/config! min-bars :enabled? (seesaw/value e)))])
        max-bars       (seesaw/spinner :id :max-bars :model (seesaw/spinner-model (:max-bars phrase 24) :from 1 :to 64)
                                       :enabled? (:max-bars? phrase)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :max-bars
                                                                              (seesaw/value %))])
        max-bars-cb    (seesaw/checkbox :id :max-bars-cb :text "Max bars:" :selected? (:max-bars? phrase)
                                        :listen [:item-state-changed
                                                 (fn [e]
                                                   (swap-phrase! show uuid assoc :max-bars? (seesaw/value e))
                                                   (seesaw/config! max-bars :enabled? (seesaw/value e)))])
        min-bpm        (seesaw/spinner :id :min-bpm :model (seesaw/spinner-model (:min-bpm phrase 60) :from 20 :to 200)
                                       :enabled? (:min-bpm? phrase)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :min-bpm
                                                                              (seesaw/value %))])
        min-bpm-cb     (seesaw/checkbox :id :min-bpm-cb :text "Min BPM:" :selected? (:min-bpm? phrase)
                                        :listen [:item-state-changed
                                                 (fn [e]
                                                   (swap-phrase! show uuid assoc :min-bpm? (seesaw/value e))
                                                   (seesaw/config! min-bpm :enabled? (seesaw/value e)))])
        max-bpm        (seesaw/spinner :id :max-bpm :model (seesaw/spinner-model (:max-bpm phrase 160) :from 20 :to 200)
                                       :enabled? (:max-bpm? phrase)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :max-bpm
                                                                              (seesaw/value %))])
        max-bpm-cb     (seesaw/checkbox :id :max-bpm-cb :text "Max BPM:" :selected? (:max-bpm? phrase)
                                        :listen [:item-state-changed
                                                 (fn [e]
                                                   (swap-phrase! show uuid assoc :max-bpm? (seesaw/value e))
                                                   (seesaw/config! max-bpm :enabled? (seesaw/value e)))])
        weight-label   (seesaw/label :id :weight-label :text "Weight:")
        weight         (seesaw/spinner :id :weight :model (seesaw/spinner-model (:weight phrase 1) :from 1 :to 1000)
                                       :listen [:state-changed #(swap-phrase! show uuid assoc :weight
                                                                              (seesaw/value %))])
        gap-label      (seesaw/label :text "")
        panel          (mig/mig-panel
                        :constraints (phrase-panel-constraints (.getWidth ^JFrame (:frame show)))
                        :items [[comment-field "spanx 5, growx, pushx"]
                                [preview "gap unrelated, spany 3, grow, push, wrap"]

                                ["Section sizes (bars):" "spany 2"]
                                ["Start:" "gap unrelated"]
                                [(seesaw/spinner :id :start  ;; TODO: Calculate model via fn from cues.
                                                 :model (seesaw/spinner-model (:start-bars phrase 1)
                                                                              :from 0 :to 64)
                                                 :listen [:state-changed #(do (swap-phrase! show uuid assoc :start-bars
                                                                                            (seesaw/value %))
                                                                              (update-section-boundaries show uuid)
                                                                              (.repaint preview))])]
                                ["Loop:" "gap unrelated"]
                                [(seesaw/spinner :id :loop  ;; TODO: Calculate model via fn from cues.
                                                 :model (seesaw/spinner-model (or (:loop-bars phrase) 2)
                                                                              :from 1 :to 64)
                                                 :listen [:state-changed #(do (swap-phrase! show uuid assoc :loop-bars
                                                                                            (seesaw/value %))
                                                                              (update-section-boundaries show uuid)
                                                                              (.repaint preview))])
                                 "wrap"]

                                ["End:" "gap unrelated"]
                                [(seesaw/spinner :id :end  ;; TODO: Calculate model via fn from cues.
                                                 :model (seesaw/spinner-model 1 :from 0 :to 64)
                                                 :listen [:state-changed #(do (swap-phrase! show uuid assoc :end-bars
                                                                                            (seesaw/value %))
                                                                              (update-section-boundaries show uuid)
                                                                              (.repaint preview))])]
                                ["Fill:" "gap unrelated"]
                                [(seesaw/spinner :id :fill  ;; TODO: Calculate model via fn from cues.
                                                 :model (seesaw/spinner-model 2 :from 1 :to 64)
                                                 :listen [:state-changed #(do (swap-phrase! show uuid assoc :fill-bars
                                                                                            (seesaw/value %))
                                                                              (update-section-boundaries show uuid)
                                                                              (.repaint preview))])
                                 "wrap unrelated"]

                                [gear "spanx, split"]

                                ["MIDI Output:" "gap unrelated"]
                                [(seesaw/combobox :id :outputs
                                                  :model (let [chosen (:midi-device phrase)]
                                                           (concat outputs
                                                                   ;; Preserve existing selection even if now missing.
                                                                   (when (and chosen (not ((set outputs) chosen)))
                                                                     [chosen])
                                                                   ;; Offer escape hatch if no MIDI devices available.
                                                                   (when (and chosen (empty? outputs))
                                                                     [nil])))
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-phrase! show uuid assoc :midi-device
                                                                          (seesaw/selection %))])]

                                ["Playing:" "gap unrelated"]
                                [(seesaw/canvas :id :state :size [18 :by 18] :opaque? false
                                                :tip "Outer ring shows track enabled, inner light when loaded."
                                                :paint (partial paint-phrase-state show uuid))]
                                ["Message:"]
                                [(seesaw/combobox :id :message :model ["None" "Note" "CC" "Custom"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-phrase! show uuid assoc :message
                                                                          (seesaw/selection %))])]
                                [(seesaw/spinner :id :note
                                                 :model (seesaw/spinner-model (or (:note phrase) 120)
                                                                              :from 1 :to 127)
                                                 :listen [:state-changed
                                                          #(swap-phrase! show uuid assoc :note (seesaw/value %))])
                                 "hidemode 3"]

                                [(seesaw/label :id :channel-label :text "Channel:")
                                 "gap unrelated, hidemode 3"]
                                [(seesaw/spinner :id :channel
                                                 :model (seesaw/spinner-model (or (:channel phrase) 1)
                                                                              :from 1 :to 16)
                                                 :listen [:state-changed
                                                          #(swap-phrase! show uuid assoc :channel (seesaw/value %))])
                                 "hidemode 3"]

                                ["Solo:" "gap unrelated"]
                                [(seesaw/combobox :id :solo :model ["Global" "Show" "Blend"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           #(swap-phrase! show uuid assoc :solo (seesaw/selection %))])]

                                [(seesaw/label :id :enabled-label :text "Enabled:") "gap unrelated"]
                                [(seesaw/combobox :id :enabled
                                                  :model ["See Below" "Custom"]
                                                  :selected-item nil  ; So update below saves default.
                                                  :listen [:item-state-changed
                                                           (fn [e]
                                                             (swap-phrase! show uuid assoc :enabled (seesaw/value e))
                                                             (let [visible? (not= "Custom" (seesaw/value e))]
                                                               (seesaw/config! [types types-label banks banks-label
                                                                                min-bars-cb min-bars
                                                                                max-bars-cb max-bars
                                                                                min-bpm-cb min-bpm max-bpm-cb max-bpm
                                                                                weight-label weight gap-label]
                                                                               :visible? visible?)
                                                               (when-not visible?
                                                                 (let [show         (latest-show show)
                                                                       runtime-info (phrase-runtime-info show uuid)]
                                                                   (when-let [picker (:phrase-type-picker runtime-info)]
                                                                     (.dispose picker))
                                                                   (when-let [picker (:track-bank-picker runtime-info)]
                                                                     (.dispose picker)))))
                                                             ;; TODO: (repaint-phrase-states show uuid)
                                                             )])
                                 "hidemode 2, wrap unrelated"]

                                [types "spanx 5, split 4, hidemode 3"]
                                [types-label "gap unrelated, hidemode 3"]
                                [banks "gap unrelated, hidemode 3"]
                                [banks-label "gap unrelated, hidemode 3"]
                                [min-bars-cb "spanx, split, hidemode 3"]
                                [min-bars "hidemode 3"]
                                [max-bars-cb "gap 15, hidemode 3"]
                                [max-bars "hidemode 3"]
                                [min-bpm-cb "gap 30, hidemode 3"]
                                [min-bpm "hidemode 3"]
                                [max-bpm-cb "gap 15, hidemode 3"]
                                [max-bpm "hidemode 3"]
                                [weight-label "gap 30, hidemode 3"]
                                [weight "hidemode 3"]
                                [gap-label "growx, pushx, hidemode 3"]])

        phrase (merge phrase {:uuid     uuid
                              :creating true}) ; Suppress popup expression editors when reopening a show.

        popup-fn (fn [^MouseEvent _e]  ; Creates the popup menu for the gear button or right-clicking in the phrase.
                   ;; TODO: Implement the rest of these!
                   (concat [#_(edit-cues-action phrase panel) #_(seesaw/separator)]
                           (phrase-editor-actions show phrase panel gear)
                           [#_(seesaw/separator) #_(phrase-simulate-menu phrase) (su/phrase-inspect-action show phrase)
                            (seesaw/separator)]
                           [(seesaw/separator) (delete-phrase-action show phrase panel)]))

        drag-origin (atom nil)]

    (swap-show! show assoc-in [:contents :phrases uuid] phrase)  ; information about the phrase trigger that gets saved.
    (swap-show! show assoc-in [:phrases uuid]  ; Runtime (unsaved) information about the phrase trigger.
                {:entered           {} ; Map from player # to sets of UUIDs of cues that have been entered.
                 :expression-locals (atom {})
                 :filter            (build-filter-target (:comment phrase))
                 :panel             panel})
    (swap-show! show assoc-in [:panels panel] uuid)  ; Tracks all the rows in the show window.

    ;; Create our contextual menu and make it available both as a right click on the whole row, and as a normal
    ;; or right click on the gear button. Also set the proper initial gear appearance.
    (seesaw/config! [panel gear] :popup popup-fn)
    (seesaw/listen gear :mouse-pressed (fn [e]
                                         (let [popup (seesaw/popup :items (popup-fn e))]
                                           (util/show-popup-from-button gear popup e))))
    (su/update-phrase-gear-icon show phrase gear)

    ;; TODO: The equivalent for the phrase preview once implemented.
    #_(seesaw/listen soft-preview
                   :mouse-moved (fn [e] (handle-preview-move track soft-preview preview-loader e))
                   :mouse-pressed (fn [^MouseEvent e]
                                    (reset! drag-origin {:point (.getPoint e)})
                                    (handle-preview-press track preview-loader e))
                   :mouse-dragged (fn [e] (handle-preview-drag track preview-loader e drag-origin)))

    (seesaw/listen (seesaw/select panel [:#outputs])
                   :item-state-changed (fn [_] (seesaw/invoke-later (show-midi-status show phrase))))
    (attach-phrase-message-visibility-handler show phrase panel gear)
    (attach-phrase-custom-editor-opener show phrase panel (seesaw/select panel [:#enabled]) :enabled gear)

    ;; Establish the saved or initial settings of the UI elements, which will also record them for the
    ;; future, and adjust the interface, thanks to the already-configured item changed listeners.
    (update-phrase-comboboxes phrase panel)

    ;; In case this is the inital creation of the phrase trigger, record the defaulted values of the numeric inputs.
    ;; This will have no effect if they were loaded.
    (swap-phrase! show phrase assoc :start-bars (seesaw/value (seesaw/select panel [:#start])))
    (swap-phrase! show phrase assoc :loop-bars (seesaw/value (seesaw/select panel [:#loop])))
    (swap-phrase! show phrase assoc :end-bars (seesaw/value (seesaw/select panel [:#end])))
    (swap-phrase! show phrase assoc :fill-bars (seesaw/value (seesaw/select panel [:#fill])))
    (update-section-boundaries show uuid)  ; We now have the information needed to do this.
    (swap-phrase! show phrase assoc :note (seesaw/value (seesaw/select panel [:#note])))
    (swap-phrase! show phrase assoc :channel (seesaw/value (seesaw/select panel [:#channel])))
    (swap-phrase! show phrase assoc :min-bars (seesaw/value (seesaw/select panel [:#min-bars])))
    (swap-phrase! show phrase assoc :max-bars (seesaw/value (seesaw/select panel [:#max-bars])))
    (swap-phrase! show phrase assoc :min-bpm (seesaw/value (seesaw/select panel [:#min-bpm])))
    (swap-phrase! show phrase assoc :max-bpm (seesaw/value (seesaw/select panel [:#max-bpm])))
    (swap-phrase! show phrase assoc :weight (seesaw/value (seesaw/select panel [:#weight])))

    ;; Similarly, initialize the phrase type and track bank tracking information and labels.
    (update-phrase-type-label show phrase types-label)
    (update-track-bank-label show phrase banks-label)

    #_(cues/build-cues track)  ; TODO: Implement the phrase cues equivalent.
    (parse-phrase-expressions show phrase)

    ;; We are done creating the phrase trigger, so arm the menu listeners to automatically pop up expression editors
    ;; when the user requests a custom message.
    (swap-phrase! show phrase dissoc :creating)))

(defn create-phrase-panels
  "Creates all the panels that represent phrase triggers in the show."
  [show]
  (doseq [uuid (get-in (latest-show show) [:contents :phrase-order])]
    (create-phrase-panel show uuid)))

(defn- scroll-to-phrase
  "Makes sure the specified phrase trigger is visible (it has just been
  created), or give the user a warning that the current filters have
  hidden it. If the comment field is empty, focuses on it to encourage
  the user to add one."
  ([show phrase-or-uuid]
   (scroll-to-phrase show phrase-or-uuid false))
  ([show phrase-or-uuid select-comment]
   (let [show   (latest-show show)
         uuid   (if (instance? UUID phrase-or-uuid) phrase-or-uuid (:uuid phrase-or-uuid))
         phrase (get-in show [:contents :phrases uuid])
         tracks (seesaw/select (:frame show) [:#tracks])]
     (if (some #(= uuid %) (:vis-phrases show))
       (seesaw/invoke-later
        (let [^JPanel panel (get-in show [:phrases uuid :panel])]
          (seesaw/scroll! tracks :to (.getBounds panel))
          (when select-comment
            (let [^JTextComponent comment-field (seesaw/select panel [:#comment])]
              (seesaw/request-focus! comment-field)
              (.selectAll comment-field)))))
       (seesaw/alert (:frame show)
                     (str "The phrase trigger “" (su/display-title phrase) "” is currently hidden by your filters.\r\n"
                          "To continue working with it, you will need to adjust the filters.")
                     :title "Can't Scroll to Hidden Phrase Trigger" :type :info)))))

(defn next-phrase-number
  "Calculates the phrase number to assign a newly-created phrase as a
  draft comment."
  [show]
  (let [show    (latest-show show)
        phrases (vals (get-in show [:contents :phrases]))]
    (->> phrases
         (map :comment)
         (map (fn [s]
                (if-let [[_ n] (re-matches #"(?i)\s*phrase\s*(\d+)\s*" (or s ""))]
                  (inc (Long/parseLong n))
                  0)))
         (apply max (count phrases)))))

(defn new-phrase
  "Adds a new phrase trigger to the show."
  [show]
  (let [show (latest-show show)
        uuid (UUID/randomUUID)]
    (create-phrase-panel show uuid)
    (swap-show! show update-in [:contents :phrase-order] (fnil conj []) uuid)
    (let [comment-field (seesaw/select (get-in (latest-show show) [:phrases uuid :panel]) [:#comment])]
      (seesaw/value! comment-field (str "Phrase " (next-phrase-number show)))
      (su/update-row-visibility show))
    (scroll-to-phrase show uuid true)))

(defn sort-phrases
  "Sorts the phrase triggers by their comments. `show` must be current."
  [show]
  (let [show (latest-show show)
        sorted (sort-by (juxt :comment :uuid) (vals (get-in show [:contents :phrases])))]
    (swap-show! show assoc-in[:contents :phrase-order] (mapv :uuid sorted))
    (su/update-row-visibility show)))

(defn resize-phrase-panels
  "Called when the show window has resized, to put appropriate
  constraints on the columns of the phrase panels."
  [panels width]
  (let [constraints (phrase-panel-constraints width)]
    (doseq [[^JPanel panel signature-or-uuid] panels]
      (when (instance? UUID signature-or-uuid)  ; It's a phrase trigger panel.
        (seesaw/config! panel :constraints constraints)
        (.revalidate panel)))))
