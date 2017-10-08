(defproject beat-link-trigger "0.3.6-SNAPSHOT"
  :description "Trigger events in response to CDJ activity."
  :url "https://github.com/brunchboy/beat-link-trigger"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.443"]
                 [com.fifesoft/rsyntaxtextarea "2.6.1"]
                 [com.github.insubstantial/substance "7.3"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.fzakaria/slf4j-timbre "0.3.7"]
                 [environ "1.1.0"]
                 [fipp "0.6.10"]
                 [inspector-jay "0.3"]
                 [me.raynes/fs "1.4.6"]
                 [org.deepsymmetry/beat-link "0.3.6-SNAPSHOT"]
                 [overtone/midi-clj "0.5.0" :exclusions [overtone/at-at]]
                 [overtone/osc-clj "0.9.0"]
                 [seesaw "1.4.5"]
                 [uk.co.xfactory-librarians/coremidi4j "1.1"]]

  :profiles {:dev {:repl-options {:init-ns beat-link-trigger.core
                                  :welcome (println "beat-link-trigger loaded.")}
                   :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]}
             :uberjar {:aot :all}}

  :main beat-link-trigger.BeatLinkTrigger
  :uberjar-name "beat-link-trigger.jar"

  ;; Add project name and version information to jar file manifest
  :manifest {"Name" ~#(str (clojure.string/replace (:group %) "." "/")
                            "/" (:name %) "/")
             "Package" ~#(str (:group %) "." (:name %))
             "Specification-Title" ~#(:name %)
             "Specification-Version" ~#(:version %)}

  :plugins [[lein-environ "1.1.0"]]

  :min-lein-version "2.0.0")

