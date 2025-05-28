(ns beat-link-trigger.about
  "An informational About box. Also provides the user interface for when
  Beat Link Trigger is searching for a Pioneer DJ Link network."
  (:require [beat-link-trigger.util :as util]
            [clojure.java.browse]
            [clojure.java.io]
            [seesaw.core :as seesaw])
  (:import [java.awt RenderingHints Graphics2D]
           [javax.imageio ImageIO]
           [javax.swing JButton JFrame JTextArea]))

(defonce ^{:private true
           :doc "Holds the About window when it is open."}
  frame (atom nil))

(defn- paint-backdrop
  "Draw the animated Deep Symmetry backdrop in a component."
  [c ^Graphics2D g backdrop start]
  (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
  (let [w (seesaw/width c)
        h (seesaw/height c)]
    (.translate g (/ w 2.0) (/ h 2.0))
    (.rotate g (/ (- start (System/currentTimeMillis)) 4000.0))
    (.drawImage g backdrop (- (/ w 2.0)) (- (/ h 2.0)) w h nil)))

(defn- simulate-label
  "Adjusts a JTextField so that it looks and acts like an ordinary
  JLabel. We use these so that the user can select and copy the
  version information in case they need it for submitting issues."
  [^JTextArea area]
  (.setEditable area false)
  (.setBackground area nil)
  (.setOpaque area false)
  (.setBorder area nil))

(defn- create-about-panel
  "Create the panel containing about information, given the function
  which paints the animated backdrop."
  [paint-fn]
  (let [source-button (seesaw/button :text "Source" :bounds [300 300 80 30] :cursor :default)
        version-label (seesaw/text :text (str "Version: " (util/get-version) "\n" (util/get-build-date))
                                   :multi-line? true :wrap-lines? true
                                   :foreground "white" :cursor :text
                                   :bounds [5 5 300 60])
        java-label (seesaw/text :text (str "Java Version:\n" (util/get-java-version))
                                :multi-line? true :wrap-lines? true
                                :foreground "white" :cursor :text
                                :bounds [5 300 250 100])
        panel (seesaw/border-panel
               :center (seesaw/xyz-panel
                        :id :xyz :background "black"
                        :paint paint-fn :cursor :hand
                        :items [version-label java-label source-button]))]
    (simulate-label version-label)
    (simulate-label java-label)
    (seesaw/listen panel
                   :component-resized (fn [_]
                                        (let [w (seesaw/width panel)
                                              h (seesaw/height panel)]
                                          (seesaw/config! source-button :bounds [(- w 83) (- h 33) :* :*])
                                          (seesaw/config! java-label :bounds [:* (- h 55) :* :*])))
                   :mouse-clicked (fn [_]
                                    (clojure.java.browse/browse-url "http://deepsymmetry.org")))
    (seesaw/listen source-button
                   :mouse-clicked (fn [_]
                                    (clojure.java.browse/browse-url "https://github.com/Deep-Symmetry/beat-link-trigger")))
    panel))

(defn- create-frame
  "Create a window with an animated backdrop, and call `content-fn` to
  create the elements to be displayed in front. Used for both the
  About window, and the Looking for DJ Link Devices window.
  `content-fn` will be called with the function that should be used to
  paint the backdrop; it should assign that as the `:paint` option of
  the container it creates."
  [content-fn & {:keys [title] :or {title "About Beat Link Trigger"}}]
  (let [backdrop     (ImageIO/read (clojure.java.io/resource "images/Backdrop.png"))
        start        (System/currentTimeMillis)
        paint-fn     (fn [c g] (paint-backdrop c g backdrop start))
        ^JFrame root (seesaw/frame :title title :on-close :dispose
                                   :minimum-size [400 :by 400]
                                   :content (content-fn paint-fn))
        animator     (future (loop [] (Thread/sleep 15) (.repaint root) (recur)))]
    (seesaw/listen root
                   :window-closed (fn [_]  ; Clean up our resources and record we are closed
                                    (future-cancel animator)
                                    (reset! frame nil))
                   :component-resized (fn [_]  ; Stay square
                                        (let [^Integer w (seesaw/width root)
                                              ^Integer h (seesaw/height root)]
                                          (when (not= w h)
                                            (let [side (Math/min w h)]
                                              (seesaw/config! root :size [side :by side]))))))
    ;; Don't allow resizing on Windows because for some reason, that causes all kinds of ugly issues.
    (when (.startsWith (.toLowerCase (System/getProperty "os.name")) "windows")
      (seesaw/config! root :resizable? false))
    (.setLocationRelativeTo root nil)
    (seesaw/show! root)
    root))

(defn show
  "Show the About window."
  []
  (seesaw/invoke-later
   (.toFront ^JFrame (swap! frame #(or % (create-frame create-about-panel))))))

(defn- create-searching-panel
  "Create the panel explaining that we are searching for DJ Link
  devices. `continue-offline` and `quit` are atoms that we should set
  to `true` if the user clicks the corresponding button, and
  `paint-fn` is the function which paints the animated backdrop."
  [continue-offline quit paint-fn]
  (let [^JButton continue-button (seesaw/button :text "Continue Offline"
                                                :listen [:action-performed (fn [_] (reset! continue-offline true))])
        ^JButton quit-button     (seesaw/button :text "Quit"
                                                :listen [:action-performed (fn [_] (reset! quit true))])
        buttons                  (seesaw/border-panel :opaque? false :west continue-button :east quit-button)
        panel                    (seesaw/border-panel :border 10 :background "black" :paint paint-fn
                                                      :south buttons :north (seesaw/progress-bar :indeterminate? true))]
    panel))

(defn create-searching-frame
  "Create and show a frame that explains we are looking for devices.
  `continue-offline` and `quit` are atoms that we should set to `true`
  if the user clicks the corresponding button."
  [continue-offline quit]
  (seesaw/invoke-now
   (let [searching (create-frame (partial create-searching-panel continue-offline quit)
                                 :title "Looking for DJ Link devices…")]
     (seesaw/config! searching :resizable? false :on-close :nothing)
     (seesaw/pack! searching)
     (seesaw/show! searching)
     searching)))

(defn build-troubleshooting-window
  "Creates and displays a frame that shows we are having trouble finding
  DJ Link devices, and information about the current network."
  [network-label continue-offline quit]
  (let [continue-button (seesaw/button :text "Continue Offline"
                                       :listen [:action-performed (fn [_] (reset! continue-offline true))])
        quit-button     (seesaw/button :text "Quit"
                                       :listen [:action-performed (fn [_] (reset! quit true))])
        buttons         (seesaw/border-panel :opaque? false :west continue-button :east quit-button)
        scroll          (seesaw/scrollable network-label)
        panel           (seesaw/border-panel :border 10 :center scroll :south buttons
                                             :north (seesaw/progress-bar :indeterminate? true))
        ^JFrame root    (seesaw/frame :title "No DJ Link Devices Found Yet..."
                                      :on-close :nothing
                                      :content panel)]
    (seesaw/pack! root)
    (.setLocationRelativeTo root nil)
    (seesaw/show! root)
    root))
