(ns beat-link-trigger.BeatLinkTrigger
  "This is configured as the main class which is run when the jar is
  executed. Its task is to check whether the Java version is high
  enough to support running beat-link-trigger, and if so, proceed to
  initializing it. Otherwise, display a dialog explaining the issue
  and offering to take the user to the download page."
  (:require [clojure.java.browse :as browse]
            [clojure.string]
            [clojure.tools.cli :as cli]
            [beat-link-trigger.util :as util])
  (:import [javax.swing JOptionPane])
  (:gen-class))

(def cli-options
  "The command-line options supported by Beat Link Trigger."
  [["-o" "--offline" "Start in offline mode"]
   [nil "--reset FILE" "Write saved configuration to file and clear it"]
   ["-h" "--help" "Display help information and exit"]])

(defn- println-err
  "Prints objects to stderr followed by a newline."
  [& more]
  (binding [*out* *err*]
    (apply println more)))

(defn usage
  "Print message explaining command-line invocation options."
  [options-summary]
  (clojure.string/join
   \newline
   [(str "Beat Link Trigger " (util/get-version) " " (util/get-build-date))
    ""
    "Options:"
    options-summary
    ""
    "Please see https://github.com/Deep-Symmetry/beat-link-trigger for user guide."]))

(defn error-msg
  "Format an error message related to command-line invocation."
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn exit
  "Terminate execution with a message to the command-line user."
  [status msg]
  (if (zero? status)
    (println msg)
    (println-err msg))
  (System/exit status))

(defn -main
  "Check the Java version and either proceed or offer to download a newer one."
  [& args]
  (when (< (Float/valueOf (System/getProperty "java.specification.version")) 1.8)
    (let [options (to-array ["Download" "Cancel"])
          choice  (JOptionPane/showOptionDialog
                   nil
                   (str "To run BeatLinkTrigger you will need to install a current\n"
                        "Java Runtime Environment, or at least Java 1.8.")
                   "Newer Java Version Required"
                   JOptionPane/YES_NO_OPTION JOptionPane/ERROR_MESSAGE nil
                   options (aget options 0))]
      (when (zero? choice) (browse/browse-url "https://openjdk.java.net")))
    (System/exit 1))

  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]

    ;; Handle help and error conditions
    (cond
      (:help options) (exit 0 (usage summary))
      errors          (exit 1 (str (error-msg errors) "\n\n" (usage summary)))
      (seq arguments) (exit 1 (str "Unexpected arguments: " (clojure.string/join ", " arguments) "\n\n"
                                   (usage summary))))

    ;; We have a recent enough Java version that it is safe to load the user interface.
    ((requiring-resolve 'beat-link-trigger.core/start) options)))
