(defproject beat-link-trigger "0.4.0-SNAPSHOT"
  :description "Trigger events in response to CDJ activity."
  :url "https://github.com/brunchboy/beat-link-trigger"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :aot [beat-link-trigger.TexturedRaven]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/data.csv "0.1.4"]
                 [com.fifesoft/rsyntaxtextarea "2.6.1"]
                 [local/radiance-neon "0.9.0"]
                 [local/radiance-substance "0.9.0"]
                 [local/radiance-substance-extras "0.9.0"]
                 [local/radiance-trident "0.9.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.fzakaria/slf4j-timbre "0.3.8"]
                 [environ "1.1.0"]
                 [fipp "0.6.12"]
                 [inspector-jay "0.3"]
                 [me.raynes/fs "1.4.6"]
                 [org.deepsymmetry/beat-link "0.3.7"]
                 [overtone/midi-clj "0.5.0" :exclusions [overtone/at-at]]
                 [overtone/osc-clj "0.9.0"]
                 [seesaw "1.5.0"]
                 [uk.co.xfactory-librarians/coremidi4j "1.1"]]
  :repositories {"project" "file:repo"}

  :profiles {:dev     {:repl-options {:init-ns beat-link-trigger.core
                                      :welcome (println "beat-link-trigger loaded.")}
                       :jvm-opts     ["-XX:-OmitStackTraceInFastThrow"]}
             :uberjar {:aot :all}}

  :main beat-link-trigger.BeatLinkTrigger
  :uberjar-name "beat-link-trigger.jar"
  ;; :jvm-opts ["--add-modules" "java.xml.bind"]

  ;; Add project name and version information to jar file manifest
  :manifest {"Name"                   ~#(str (clojure.string/replace (:group %) "." "/")
                                             "/" (:name %) "/")
             "Package"                ~#(str (:group %) "." (:name %))
             "Specification-Title"    ~#(:name %)
             "Specification-Version"  ~#(:version %)
             "Implementation-Version" ~(str (java.util.Date.))}

  :plugins [[lein-environ "1.1.0"]]

  :min-lein-version "2.0.0")
