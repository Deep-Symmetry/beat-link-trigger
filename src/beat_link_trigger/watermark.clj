(ns beat-link-trigger.watermark
  "A Clojure recreation of the cross-hatch watermark that used to be
  part of the Raven skin in Substance."
  (:import [java.awt Color Rectangle GraphicsDevice Graphics2D]
           [java.awt.image BufferedImage]
           org.pushingpixels.substance.api.colorscheme.SubstanceColorScheme
           org.pushingpixels.substance.api.SubstanceSkin
           org.pushingpixels.substance.api.watermark.SubstanceWatermark
           org.pushingpixels.substance.internal.utils.SubstanceCoreUtilities))

(defonce ^:private crosshatch-image
  (atom nil))

(defn- collect-device-bounds
  "Computes the union of a rectangle and the bounds of a graphics
  device, for use as a reducing function that gets the full display
  bounds when there are multiple displays."
  [^Rectangle r ^GraphicsDevice d]
  (.union r (.. d getDefaultConfiguration getBounds)))

(defn- stamp-colors
  "Determine the colors to be used for drawing the watermark."
  [scheme preview?]
  (if preview?
    [(if (.isDark scheme) Color/white Color/black)
     Color/lightGray
     (if (.isDark scheme) Color/black Color/white)]
    [(.getWatermarkDarkColor scheme)
     (.getWatermarkStampColor scheme)
     (.getWatermarkLightColor scheme)]))

(defn- draw-crosshatch
  "Draws the specified portion of the watermark image."
  [^SubstanceSkin skin ^Graphics2D graphics x y width height preview?]
  (let [scheme              (.getWatermarkColorScheme skin)
        [stamp-color-dark
         stamp-color-all
         stamp-color-light] (stamp-colors scheme preview?)
        tile                (SubstanceCoreUtilities/getBlankImage 4 4)
        g2d                 (.create graphics)]
    (.setRGB tile 0 0 (.getRGB stamp-color-dark))
    (.setRGB tile 2 2 (.getRGB stamp-color-dark))
    (.setRGB tile 0 1 (.getRGB stamp-color-light))
    (.setRGB tile 2 3 (.getRGB stamp-color-light))
    (.setComposite g2d (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC_OVER 0.4))
    (doseq [row (range y (+ y height) 4)
            col (range x (+ x width) 4)]
      (.drawImage g2d tile col row nil))
    (.dispose g2d)
    true))

(def crosshatch
  "A watermark that draws an interesting grill-like texture that looks
  great in the background of the UI elements."

  (reify SubstanceWatermark

    (drawWatermarkImage [_ graphics c x y width height]
      (when (.isShowing c)
        (let [dx (.. c getLocationOnScreen x)
              dy (.. c getLocationOnScreen y)]
          (.drawImage graphics @crosshatch-image x y (+ x width) (+ y height) (+ x dx) (+ y dy)
                      (+ x dx width) (+ y dy height) nil))))

    (updateWatermarkImage [this skin]
      (let [ge             (java.awt.GraphicsEnvironment/getLocalGraphicsEnvironment)
            gds            (.getScreenDevices ge)
            virtual-bounds (reduce collect-device-bounds (Rectangle.) gds)
            screen-width   (.width virtual-bounds)
            screen-height  (.height virtual-bounds)
            _              (reset! crosshatch-image (SubstanceCoreUtilities/getBlankImage screen-width screen-height))
            graphics       (.. @crosshatch-image getGraphics create)
            status (draw-crosshatch skin graphics 0 0 screen-width screen-height false)]
        (.dispose graphics)
        status))

    (previewWatermark [_ graphics skin x y width height]
      (draw-crosshatch skin graphics x y width height true))

    (getDisplayName [this]
      "Crosshatch")

    (dispose [_]
      (reset! crosshatch-image nil))))
