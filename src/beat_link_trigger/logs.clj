(ns beat-link-trigger.logs
  "Sets up logging and helps the user find the logs folder."
  (:require [me.raynes.fs :as fs]
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

(defn- create-appenders
  "Create a set of appenders which rotate the file at the specified path."
  []
  {:rotor (rotor/rotor-appender {:path (fs/file @log-path "blt.log")
                                 :max-size 100000
                                 :backlog 5})})

(defonce ^{:private true
           :doc "The default log appenders, which rotate between files
           in a logs subdirectory."}
  appenders (atom (create-appenders)))

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
   {:level :info  ; #{:trace :debug :info :warn :error :fatal :report}
    :enabled? true

    ;; Control log filtering by namespaces/patterns. Useful for turning off
    ;; logging in noisy libraries, etc.:
    :ns-whitelist  [] #_["my-app.foo-ns"]
    :ns-blacklist  [] #_["taoensso.*"]

    :middleware [] ; (fns [data]) -> ?data, applied left->right

    :timestamp-opts {:pattern "yyyy-MMM-dd HH:mm:ss"
                     :locale :jvm-default
                     :timezone (java.util.TimeZone/getDefault)}

    :output-fn output-fn ; (fn [data]) -> string
    })

  ;; Install the desired log appenders
  (timbre/merge-config!
   {:appenders @appenders}))

(defonce ^{:private true
           :doc "Used to ensure log initialization takes place exactly once."}
  initialized (delay (init-logging-internal)))

(defn init-logging
  "Set up the logging environment."
  ([] ;; Resolve the delay, causing initialization to happen if it has not yet.
   @initialized)
  ([appenders-map] ;; Override the default appenders, then initialize as above.
   (reset! appenders appenders-map)
   (init-logging)))
