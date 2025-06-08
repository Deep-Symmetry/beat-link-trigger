(ns beat-link-trigger.help
  "Serves the embedded copy of the user guide, and offers network
  troubleshooting assistance."
  (:require [clojure.string :as str]
            [clojure.java.browse]
            [compojure.route :as route]
            [compojure.core :as compojure]
            [ring.middleware.defaults :as ring-defaults]
            [ring.util.response :as response]
            [org.httpkit.server :as server])
  (:import [org.deepsymmetry.beatlink VirtualCdj]))


(defn show-landing-page
  "The home page for the embedded web server, currently does not do
  much, nobody is expected to ever try to load it."
  [_]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "Beat Link Trigger."})

(compojure/defroutes all-routes
  (compojure/GET "/" [] show-landing-page)
  (compojure/GET "/guide" [] (response/redirect "/guide/index.html"))
  (route/resources "/guide/" {:root       "user_guide"
                              :mime-types {"blt"    "application/x-beat-link-triggers-configuration"
                                           "bltx"   "application/x-beat-link-trigger-export"
                                           "blts"   "application/x-beat-link-trigger-show"
                                           "maxpat" "application/x-maxmsp-patch-file"}})
  ;; Routes for show reports.
  (route/resources "/resources/" {:root "reports"})
  (compojure/GET "/show/reports/expressions" [show]
                 ((requiring-resolve 'beat-link-trigger.expression-report/expressions-report) show))
  (compojure/GET "/show/edit-show-expression" [show kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/edit-show-expression) show kind))
  (compojure/GET "/show/edit-track-expression" [show track kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/edit-track-expression) show track kind))
  (compojure/GET "/show/simulate-track-expression" [show track kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/simulate-track-expression) show track kind))
  (compojure/GET "/show/edit-track-cue-expression" [show track cue kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/edit-track-cue-expression)
                  show track cue kind))
  (compojure/GET "/show/simulate-track-cue-expression" [show track cue kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/simulate-track-cue-expression)
                  show track cue kind))
  (compojure/GET "/show/edit-phrase-expression" [show phrase kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/edit-phrase-expression) show phrase kind))
  (compojure/GET "/show/simulate-phrase-expression" [show phrase kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/simulate-phrase-expression) show phrase kind))
  (compojure/GET "/show/edit-phrase-cue-expression" [show phrase cue kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/edit-phrase-cue-expression)
                  show phrase cue kind))
  (compojure/GET "/show/simulate-phrase-cue-expression" [show phrase cue kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/simulate-phrase-cue-expression)
                  show phrase cue kind))
  (compojure/GET "/show/edit-trigger-expression" [uuid kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/edit-trigger-expression) uuid kind))
  (compojure/GET "/show/simulate-trigger-expression" [uuid kind]
                 ((requiring-resolve 'beat-link-trigger.expression-report/simulate-trigger-expression) uuid kind))

  ;; We give up!
  (route/not-found "<p>Page not found.</p>"))

;; Once the server is started, holds a map where :port contains the
;; port number on which the server is listening, and :stop contains
;; the function that will shut it down.
(defonce ^:private running-server (atom nil))

(defn help-server
  "Starts the embedded web server, if needed, and returns the port
  number on which it can be reached, or `nil` if it could not be
  started."
  []
  (locking running-server
    (when-not @running-server
      (let [handler (ring-defaults/wrap-defaults all-routes ring-defaults/site-defaults)]
        (loop [port 9889]
          (try
            (reset! running-server {:port port
                                    :stop (server/run-server handler {:port port})})
            (catch java.net.BindException _))  ; We will just retry until we are out of ports.
          (when (and (not @running-server) (< port 65535))
            (recur (inc port))))))
    (:port @running-server)))

(defn stop-server
  "Shuts down the embedded web server, if it is running."
  []
  (locking @running-server
    (when @running-server
      ((:stop @running-server))
      (reset! running-server nil))))

(defn user-guide-link
  "Makes sure the local user guide is being served, then returns a URL
  that will reach the specified section (or the front page if none was
  specified)."
  ([]
   (user-guide-link "README.html"))
  ([section]
   (when-let [port (help-server)]
     (str "http://127.0.0.1:" port "/guide/beat-link-trigger/" section))))

(defn show-user-guide
  "Opens a web browser window on the locally-served user guide copy,
  starting the embedded web server if needed."
  []
  (when-let [guide-url (user-guide-link)]
    (clojure.java.browse/browse-url guide-url)))

(defn- describe-ipv4-addresses
  "Produces a compact summary of the IPv4 addresses (if any) attached to
  a network interface."
  [^java.net.NetworkInterface interface]
  (let [candidates (filter java.net.InterfaceAddress/.getBroadcast (.getInterfaceAddresses interface))]
    (if (seq candidates)
      (str/join ", " (map (fn [^java.net.InterfaceAddress addr]
                            (str (.getHostAddress (.getAddress addr)) "/" (.getNetworkPrefixLength addr)))
                          candidates))
      "No IPv4 addresses")))

(defn- describe-interface
  "Produces a troubleshooting summary about a network interface."
  [^java.net.NetworkInterface interface]
  (let [display-name (.getDisplayName interface)
        raw-name     (.getName interface)]
    (str display-name
         (when (not= display-name raw-name) (str " (" raw-name ")"))
         ": " (describe-ipv4-addresses interface))))

(defn list-network-interfaces
  "Describes the network interfaces present in the system."
  []
  (->> (java.util.Collections/list (java.net.NetworkInterface/getNetworkInterfaces))
       (filter (fn [^java.net.NetworkInterface iface]
                 (and (.isUp iface) (not (.isLoopback iface)))))
       (map describe-interface)
       sort))

(defn list-conflicting-network-interfaces
  "When there was more than one interface on the network on which we saw
  DJ Link traffic, returns a list of their descriptions. Otherwise
  returns `nil`."
  []
  (let [interfaces (.getMatchingInterfaces (VirtualCdj/getInstance))]
    (when (> (count interfaces) 1)
      (->> interfaces
           (map describe-interface)
           sort))))
