(ns beat-link-trigger.logs
  "Sets up logging and helps the user find the logs folder."
  (:require [beat-link-trigger.util :as util]
            [me.raynes.fs :as fs]
            [seesaw.core :as seesaw]
            [seesaw.util]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [taoensso.timbre :as timbre]))

(defonce ^{:private true
           :doc "The temporary directory into which we will log."}
  log-path (atom (fs/temp-dir "blt_logs")))

(def logs-action
  "The menu action which opens the logs folder."
  (seesaw/action :handler (fn [e]
                            (.open (java.awt.Desktop/getDesktop) @log-path))
                 :name "Open Logs Folder"))

(defn install-appenders
  "Create and install a set of appenders which rotate the file at the
  configured path, with the specified maximum size and number of
  backlog files."
  [max-size backlog]
  (timbre/merge-config!
   {:appenders {:rotor (rotor/rotor-appender {:path (fs/file @log-path "blt.log")
                                              :max-size max-size
                                              :backlog backlog})}}))

(defn output-fn
  "Log format (fn [data]) -> string output fn.
  You can modify default options with `(partial default-output-fn <opts-map>)`.
  This is based on timbre's default, but removes the hostname."
  ([data] (output-fn nil data))
  ([{:keys [no-stacktrace? stacktrace-fonts] :as opts} data]
   (let [{:keys [level ?err_ vargs_ msg_ ?ns-str hostname_
                 timestamp_ ?line]} data]
     (str
             @timestamp_       " "
       (clojure.string/upper-case (name level))  " "
       "[" (or ?ns-str "?") ":" (or ?line "?") "] - "
       (force msg_)
       (when-not no-stacktrace?
         (when-let [err (force ?err_)]
           (str "\n" (timbre/stacktrace err (assoc opts :stacktrace-fonts {})))))))))

(defn- init-logging-internal
  "Performs the actual initialization of the logging environment,
  protected by the delay below to insure it happens only once."
  []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread e]
       (timbre/error e "Uncaught exception on" (.getName thread)))))
  (timbre/set-config!
   {:level    :info ; #{:trace :debug :info :warn :error :fatal :report}
    :enabled? true

    ;; Control log filtering by namespaces/patterns. Useful for turning off
    ;; logging in noisy libraries, etc.:
    :ns-whitelist [] #_ ["my-app.foo-ns"]
    :ns-blacklist [] #_ ["taoensso.*"]

    :middleware [] ; (fns [data]) -> ?data, applied left->right

    :timestamp-opts {:pattern  "yyyy-MMM-dd HH:mm:ss"
                     :locale   :jvm-default
                     :timezone (java.util.TimeZone/getDefault)}

    :output-fn output-fn ; (fn [data]) -> string
    })

  ;; Install the desired log appenders
  (let [max-size 200000
        backlog  5]
    (install-appenders max-size backlog))

  ;; Add the inital log lines that identify build and Java information.
  (timbre/info "Beat Link Trigger version" (util/get-version) "built" (or (util/get-build-date) "not yet"))
  (timbre/info "Java version" (util/get-java-version))
  (timbre/info "Operating system version" (util/get-os-version))
  (timbre/info "Log files can grow to" max-size "bytes, with" backlog "backlog files."))

(defonce ^{:private true
           :doc "Used to ensure log initialization takes place exactly once."}
  initialized (delay (init-logging-internal)))

(defn init-logging
  "Set up the logging environment."
  []
  @initialized) ; Resolve the delay, causing initialization to happen if it has not yet.
