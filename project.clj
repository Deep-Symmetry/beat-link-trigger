(defproject beat-link-trigger :lein-v
  :description "Trigger events in response to CDJ activity."
  :url "https://github.com/Deep-Symmetry/beat-link-trigger"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :aot [beat-link-trigger.BeatLinkTrigger beat-link-trigger.TexturedRaven]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "0.4.500"]
                 [org.clojure/data.csv "0.1.4"]
                 [org.clojure/tools.cli "0.4.2"]
                 [clojure-humanize "0.2.2"]
                 [com.fifesoft/rsyntaxtextarea "3.0.4"]
                 [com.fifesoft/rstaui "3.0.2"]
                 [org.pushing-pixels/radiance-substance "1.0.2"]
                 [org.pushing-pixels/radiance-substance-extras "1.0.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.fzakaria/slf4j-timbre "0.3.14"]
                 [fipp "0.6.19"]
                 [inspector-jay "0.3"]
                 [me.raynes/fs "1.4.6"]
                 [org.deepsymmetry/beat-link "0.5.5-SNAPSHOT"]
                 [org.deepsymmetry/electro "0.1.3"]
                 [overtone/midi-clj "0.5.0" :exclusions [overtone/at-at]]
                 [overtone/osc-clj "0.9.0"]
                 [seesaw "1.5.0"]
                 [com.evocomputing/colors "1.0.4"]
                 [uk.co.xfactory-librarians/coremidi4j "1.2"]
                 [com.cemerick/url "0.1.1"]
                 [http-kit "2.3.0"]
                 [ring/ring-core "1.7.1"]
                 [compojure "1.6.1"]
                 [nrepl "0.6.0"]
                 [cider/cider-nrepl "0.22.3"]
                 [com.cemerick/pomegranate "1.1.0"]]
  :repositories {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}

  :profiles {:dev     {:repl-options {:init-ns beat-link-trigger.core
                                      :welcome (println "beat-link-trigger loaded.")}
                       :jvm-opts     ["-XX:-OmitStackTraceInFastThrow"]}
             :uberjar {:aot :all}}

  :main beat-link-trigger.BeatLinkTrigger
  :uberjar-name "beat-link-trigger.jar"

  ;; Add project name and version information to jar file manifest
  :manifest {"Name"                  ~#(str (clojure.string/replace (:group %) "." "/")
                                            "/" (:name %) "/")
             "Package"               ~#(str (:group %) "." (:name %))
             "Specification-Title"   ~#(:name %)
             "Specification-Version" ~#(:version %)
             "Build-Timestamp"       ~(str (java.util.Date.))}

  :plugins [[lein-shell "0.5.0"]
            [lein-resource "17.06.1"]
            [com.roomkey/lein-v "7.1.0"]]

  :middleware [lein-v.plugin/middleware]

  ;; Perform the tasks which embed the user guide before compilation, so it will be available
  ;; both in development, and in the distributed archive.
  :prep-tasks [["shell" "antora" "--fetch" "doc/embedded.yml"]
               "javac"
               "compile"
               ["v" "cache" "resources/beat_link_trigger" "edn"]]

  ;; Miseclaneous sanitary settings
  :pedantic :warn
  :min-lein-version "2.0.0")
