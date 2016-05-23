(ns beat-link-trigger.menus
  "Provides support for menu options used in various other
  namespaces."
  (:require [beat-link-trigger.about :as about]
            [seesaw.core :as seesaw]
            [seesaw.util]
            [taoensso.timbre :as timbre]))

(defn on-mac?
  "Do we seem to be running on a Mac?"
  []
  (try
    (Class/forName "com.apple.eawt.Application")
    true  ; We found the Mac-only Java interaction classes
    (catch Exception e
      false)))

(defn install-mac-about-handler
  "If we are running on a Mac, load the namespace that only works
  there (and is only needed there) to install our About handler."
  []
  (when (on-mac?)
    (try
      (require '[beat-link-trigger.mac-about])
      ((resolve 'beat-link-trigger.mac-about/install-handler))
      (catch Throwable t
        (timbre/error t "Unable to install Mac \"About\" handler.")))))

(def non-mac-actions
  "The actions which are automatically available in the Application
  menu on the Mac, but must be added to the File menu on other
  platforms. This value will be empty when running on the Mac."
  (when-not (on-mac?)
    [(seesaw/separator)
     (seesaw/action :handler (fn [e]
                               (try
                                 (about/show)
                                 (catch Exception e
                                   (timbre/error e "Problem showing About window."))))
                    :name "About BeatLinkTrigger"
                    :mnemonic (seesaw.util/to-mnemonic-keycode \A))
     (seesaw/separator)
     (seesaw/action :handler (fn [e] (System/exit 0))
                    :name "Exit"
                    :mnemonic (seesaw.util/to-mnemonic-keycode \x))]))


