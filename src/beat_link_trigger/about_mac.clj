(ns beat-link-trigger.about-mac
  "This namespace can only be loaded on the Mac, on versions of Java
  earlier than Java 9, because it depends on classes only available
  there. It tells the Mac Java environment to use our own About box
  rather than the spectacularly lame default one."
  (:require [beat-link-trigger.about :as about])
  (:import [com.apple.eawt AboutHandler Application]))

(defn install-handler []
  (.setAboutHandler (Application/getApplication)
                    (proxy [AboutHandler] []
                      (handleAbout [_]
                        (about/show)))))
