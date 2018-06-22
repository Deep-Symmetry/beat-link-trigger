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
  (-> (System/getProperty "os.name")
      .toLowerCase
      (clojure.string/includes? "mac")))

(defn install-mac-about-handler
  "If we are running on a Mac, install our About handler."
  []
  (when (on-mac?)
    (try
      (if (< (Float/valueOf (System/getProperty "java.specification.version")) 9.0)
        (do (require '[beat-link-trigger.about-mac])   ; Use old, Mac-specific class to install the handler.
            ((resolve 'beat-link-trigger.about-mac/install-handler)))
        (do (require '[beat-link-trigger.about-java9]) ; Java 9 or later has a cross-platform way to do it instead.
            ((resolve 'beat-link-trigger.about-java9/install-handler))))
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
                    :name "About BeatLinkTrigger")
     (seesaw/separator)
     (seesaw/action :handler (fn [e] (System/exit 0))
                    :name "Exit")]))
