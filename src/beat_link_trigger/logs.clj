(ns beat-link-trigger.logs
  "Sets up logging and helps the user find the logs folder."
  (:require [beat-link-trigger.util :as util]
            [clojure.string]
            [me.raynes.fs :as fs]
            [seesaw.core :as seesaw]
            [seesaw.util]
            [taoensso.timbre.appenders.community.rotor :as rotor]
            [taoensso.timbre :as timbre]))

(defonce ^{:private true
           :doc "The temporary directory into which we will log."}
  log-path (atom (fs/temp-dir "blt_logs")))

(def logs-action
  "The menu action which opens the logs folder."
  (seesaw/action :handler (fn [_]
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

(defn- init-logging-internal
  "Performs the actual initialization of the logging environment,
  protected by the delay below to insure it happens only once."
  []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread e]
       (timbre/error e "Uncaught exception on" (.getName thread)))))
  (timbre/set-config!
   {:min-level :info #_ [["taoensso.*" :error] ["*" :debug]]

    ;; Control log filtering by namespaces/patterns. Useful for turning off
    ;; logging in noisy libraries, etc.:
    :ns-filter #{"*"} #_{:deny #{"taoensso.*"} :allow #{"*"}}

    :middleware [] ; (fns [data]) -> ?data, applied left->right

    :timestamp-opts {:pattern  "yyyy-MMM-dd HH:mm:ss.SSS"
                     :locale   :jvm-default
                     :timezone (java.util.TimeZone/getDefault)}

    :output-fn   timbre/default-output-fn ; (fn [data]) -> string
    :output-opts {:stacktrace-fonts {}}
    })

  ;; Install the desired log appenders
  (let [max-size 200000
        backlog  5]
    (install-appenders max-size backlog)

    ;; Add the inital log lines that identify build and Java information.
    (timbre/info "Beat Link Trigger version" (util/get-version) "built" (or (util/get-build-date) "not yet"))
    (timbre/info "Java version" (util/get-java-version))
    (timbre/info "Operating system version" (util/get-os-version))
    (timbre/info "Log files can grow to" max-size "bytes, with" backlog "backlog files.")))

(defonce ^{:private true
           :doc "Used to ensure log initialization takes place exactly once."}
  initialized (delay (init-logging-internal)))

(defn init-logging
  "Set up the logging environment."
  []
  @initialized) ; Resolve the delay, causing initialization to happen if it has not yet.
