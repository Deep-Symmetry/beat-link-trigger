(ns beat-link-trigger.BeatLinkTrigger
  "This is configured as the main class which is run when the jar is
  executed. Its task is to parse command-line arguments and provide
  help about them if requested, before passing the parsed results to
  `beat-link-trigger.core/start`."
  (:require [clojure.string]
            [clojure.tools.cli :as cli]
            [beat-link-trigger.util :as util])
  (:gen-class))

(def cli-options
  "The command-line options supported by Beat Link Trigger."
  [["-o" "--offline" "Start in offline mode"]
   ["-s" "--show FILE" "Open addtitional show after startup"
    :multi true
    :default []
    :default-desc ""
    :update-fn conj]
   ["-S" "--suppress" "Do not reopen shows from previous run"]
   [nil "--reset FILE" "Write saved configuration to file and forget it"]
   ["-c" "--config FILE" "Use specified configuration file"]
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
  "Parse and handle any command-line arguments, then proceed with startup
  if appropriate."
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]

    ;; Handle help and error conditions
    (cond
      (:help options) (exit 0 (usage summary))
      errors          (exit 1 (str (error-msg errors) "\n\n" (usage summary)))
      (seq arguments) (exit 1 (str "Unexpected arguments: " (clojure.string/join ", " arguments) "\n\n"
                                   (usage summary))))

    ;; Proceed to load the user interface and continue startup.
    ((requiring-resolve 'beat-link-trigger.core/start) options)))
