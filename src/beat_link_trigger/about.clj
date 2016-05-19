(ns beat-link-trigger.about
  "An informational About box."
  (:require [clojure.java.browse]
            [environ.core :refer [env]]
            [seesaw.core :as seesaw]
            [seesaw.graphics :as graphics])
  (:import [java.awt.image BufferedImage]
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

(defn- paint-frame
  "Draw the custom graphics in the About window."
  [c g backdrop start]
  (let [w (seesaw/width c)
        h (seesaw/height c)]
    (.translate g (/ w 2.0) (/ h 2.0))
    (.rotate g (/ (- start (System/currentTimeMillis)) 4000.0))
    (.drawImage g backdrop (- (/ w 2.0)) (- (/ h 2.0)) w h nil)))

(defn- create-about-panel
  "Create the panel containing about information, given the function
  which paints the animated backdrop."
  [paint-fn]
  (let [source-button (seesaw/button :text "Source" :bounds [300 300 70 30])
        panel (seesaw/border-panel
               :center (seesaw/xyz-panel
                        :id :xyz :background "black"
                        :paint paint-fn
                        :items [(seesaw/label :text (str "<html>Version:<br>" (get-version) "</html>")
                                              :foreground "white"
                                              :bounds [0 0 120 40])
                                source-button]))]
    (seesaw/listen panel
                   :component-resized (fn [e]
                                        (let [w (seesaw/width panel)
                                              h (seesaw/height panel)]
                                          (seesaw/config! source-button :bounds [(- w 70) (- h 30) :* :*]))))
    (seesaw/listen source-button
                   :mouse-clicked (fn [e]
                                    (clojure.java.browse/browse-url "https://github.com/brunchboy/beat-link-trigger")))
    panel))

(defn- create-frame
  "Create the About window."
  []
  (let [backdrop (ImageIO/read (clojure.java.io/resource "images/Backdrop.png"))
        start (System/currentTimeMillis)
        paint-fn (fn [c g] (paint-frame c g backdrop start))
        root (seesaw/frame :title "About BeatLinkTrigger" :on-close :dispose
                           :minimum-size [400 :by 400]
                           :content (create-about-panel paint-fn))
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
    (seesaw/show! root)
    (.setLocationRelativeTo root nil)
    root))

(defn show
  "Show the About window."
  []
  (seesaw/invoke-later
   (.toFront (swap! frame #(or % (create-frame))))))

