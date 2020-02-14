(ns beat-link-trigger.TexturedRaven
  "An enhanced version of the [Raven
  skin](https://github.com/kirill-grouchnikov/radiance/blob/master/docs/substance/skins/dark.md#raven)
  with textured watermarks. Provides the distinctinve look and feel
  for Beat Link Trigger."
  (:gen-class :extends org.pushingpixels.substance.api.skin.RavenSkin
              :constructors {[] []}
              :exposes {watermark {:set setWatermark}}
              :post-init post-init))

(defn -post-init
  "Called after superclass constructor to install our watermark."
  [this]
  (.setWatermark this (org.pushingpixels.substance.extras.api.watermarkpack.SubstanceCrosshatchWatermark.)))
