(ns beat-link-trigger.about
  "An informational About box."
  (:require [clojure.java.browse]
            [environ.core :refer [env]]
            [seesaw.core :as seesaw]
            [seesaw.graphics :as graphics])
  (:import [java.awt RenderingHints]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

(defn get-version
  "Returns the version tag from the project.clj file, either from the
  environment variable set up by Leiningen, if running in development
  mode, or from the jar manifest if running from a production build."
  []
  (or (env :beat-link-trigger-version)
      (when-let [pkg (.getPackage (class get-version))]
        (.getSpecificationVersion pkg))
      "DEV"))  ; Must be running in dev mode embedded in some other project

(defonce ^{:private true
           :doc "Holds the About window when it is open."}
  frame (atom nil))

(defn- paint-backdrop
  "Draw the animated Deep Symmetry backdrop in a component."
  [c g backdrop start]
  (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
  (let [w (seesaw/width c)
        h (seesaw/height c)]
    (.translate g (/ w 2.0) (/ h 2.0))
    (.rotate g (/ (- start (System/currentTimeMillis)) 4000.0))
    (.drawImage g backdrop (- (/ w 2.0)) (- (/ h 2.0)) w h nil)))

(defn- create-about-panel
  "Create the panel containing about information, given the function
  which paints the animated backdrop."
  [paint-fn]
  (let [source-button (seesaw/button :text "Source" :bounds [300 300 70 30] :cursor :default)
        panel (seesaw/border-panel
               :center (seesaw/xyz-panel
                        :id :xyz :background "black"
                        :paint paint-fn :cursor :hand
                        :items [(seesaw/label :text (str "<html>Version:<br>" (get-version) "</html>")
                                              :foreground "white"
                                              :bounds [0 0 120 40])
                                source-button]))]
    (seesaw/listen panel
                   :component-resized (fn [e]
                                        (let [w (seesaw/width panel)
                                              h (seesaw/height panel)]
                                          (seesaw/config! source-button :bounds [(- w 70) (- h 30) :* :*])))
                   :mouse-clicked (fn [e]
                                    (clojure.java.browse/browse-url "http://deepsymmetry.org")))
    (seesaw/listen source-button
                   :mouse-clicked (fn [e]
                                    (clojure.java.browse/browse-url "https://github.com/brunchboy/beat-link-trigger")))
    panel))

(defn- create-frame
  "Create a window with an animated backdrop, and call `content-fn` to
  create the elements to be displayed in front. Used for both the
  About window, and the Looking for DJ Link Devices window.
  `content-fn` will be called with the function that should be used to
  paint the backdrop; it should assign that as the `:paint` option of
  the container it creates."
  [content-fn & {:keys [title] :or {title "About BeatLinkTrigger"}}]
  (let [backdrop (ImageIO/read (clojure.java.io/resource "images/Backdrop.png"))
        start (System/currentTimeMillis)
        paint-fn (fn [c g] (paint-backdrop c g backdrop start))
        root (seesaw/frame :title title :on-close :dispose
                           :minimum-size [400 :by 400]
                           :content (content-fn paint-fn))
        animator (future (loop [] (Thread/sleep 15) (.repaint root) (recur)))]
    (seesaw/listen root
                   :window-closed (fn [e]  ; Clean up our resources and record we are closed
                                    (future-cancel animator)
                                    (reset! frame nil))
                   :component-resized (fn [e]  ; Stay square
                                        (let [w (seesaw/width root)
                                              h (seesaw/height root)]
                                          (when (not= w h)
                                            (let [side (Math/min w h)]
                                              (seesaw/config! root :size [side :by side]))))))
    (.setLocationRelativeTo root nil)
    (seesaw/show! root)
    root))

(defn show
  "Show the About window."
  []
  (seesaw/invoke-later
   (.toFront (swap! frame #(or % (create-frame create-about-panel))))))

(defn- create-searching-panel
  "Create the panel explaining that we are searching for DJ Link
  devices, given the function which paints the animated backdrop."
  [paint-fn]
  (let [panel (seesaw/xyz-panel
               :id :xyz :background "black"
               :paint paint-fn
               :items [(seesaw/progress-bar :indeterminate? true :bounds [10 350 380 20])])]
    panel))

(defn create-searching-frame
  "Create and show a frame that explains we are looking for devices."
  []
  (seesaw/invoke-now
   (let [searching (create-frame create-searching-panel :title "Looking for DJ Link devices…")]
     (seesaw/config! searching :resizable? false :on-close :nothing)
     (.toFront searching)
     searching)))
