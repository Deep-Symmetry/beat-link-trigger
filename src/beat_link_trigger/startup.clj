(ns beat-link-trigger.startup
  "This is configured as the main class which is run when the jar is
  executed. Its task is to check whether the Java version is high
  enough to support running beat-link-trigger, and if so, proceed to
  initializing it. Otherwise, display a dialog explaining the issue
  and offering to take the user to the download page."
  (:require [clojure.java.browse :as browse])
  (:import [javax.swing JOptionPane])
  (:gen-class))

(defn -main
  "Check the Java version and either proceed or offer to download a newer one."
  [& args]
  (when (< (Float/valueOf (System/getProperty "java.specification.version")) 1.7)
    (let [options (to-array ["Download" "Cancel"])
          choice (JOptionPane/showOptionDialog
                  nil
                  (str "To run beat-link-trigger you will need to install a current\n"
                       "Java Runtime Environment, or at least Java 1.7.")
                  "Newer Java Version Required"
                  JOptionPane/YES_NO_OPTION JOptionPane/ERROR_MESSAGE nil
                  options (aget options 0))]
      (when (zero? choice) (browse/browse-url "https://java.com/inc/BrowserRedirect1.jsp")))
    (System/exit 1))
  ;; We have a recent enough Java version that it is safe to load the user interface.
  (require '[beat-link-trigger.core])
  ((resolve 'beat-link-trigger.core/start)))
