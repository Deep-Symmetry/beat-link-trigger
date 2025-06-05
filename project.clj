(defproject beat-link-trigger :lein-v
  :description "Trigger events and automate shows in response to CDJ activity."
  :url "https://github.com/Deep-Symmetry/beat-link-trigger"
  :scm {:name "git" :url "https://github.com/Deep-Symmetry/beat-link-trigger"}
  :license {:name "Eclipse Public License 2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.async "1.8.741"]
                 [org.clojure/data.csv "1.1.0"]
                 [org.clojure/tools.cli "1.1.230"]
                 [org.deepsymmetry/electro "0.1.4"]
                 [org.deepsymmetry/beat-link "8.0.0-SNAPSHOT"]
                 [beat-carabiner "8.0.0-SNAPSHOT"]
                 [cheshire "6.0.0"]
                 [cider/cider-nrepl "0.56.0"]
                 [clj-commons/pomegranate "1.2.24"]
                 [com.cemerick/url "0.1.1"]
                 [com.fifesoft/rstaui "3.3.1"]
                 [com.fifesoft/rsyntaxtextarea "3.6.0"]
                 [com.formdev/flatlaf "3.6"]
                 [com.fzakaria/slf4j-timbre "0.4.1"]
                 [com.github.Dansoftowner/jSystemThemeDetector "3.9.1"]
                 [com.github.jiconfont/jiconfont-font_awesome "4.7.0.1"]
                 [com.github.jiconfont/jiconfont-swing "1.0.1"]
                 [com.taoensso/timbre "6.7.1"]
                 [compojure "1.7.1"]
                 [fipp "0.6.27"]
                 [hiccup "1.0.5"]
                 [http.async.client "1.4.0"]
                 [http-kit "2.8.0"]
                 [inspector-jay "0.3" :exclusions [org.clojure/core.memoize]]
                 [javax.servlet/servlet-api "2.5"]
                 [me.raynes/fs "1.4.6"]
                 [nrepl "1.3.1"]
                 [org.apache.maven/maven-artifact "3.9.9"]
                 [overtone/midi-clj "0.5.0" :exclusions [overtone/at-at]]
                 [overtone/osc-clj "0.9.0"]
                 [ring/ring-core "1.12.2"]
                 [ring/ring-defaults "0.5.0"]
                 [seesaw "1.5.0"]
                 [selmer "1.12.62"]
                 [thi.ng/color "1.5.1"]
                 [uk.co.xfactory-librarians/coremidi4j "1.6"]]
  :repositories {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"
                 "jitpack"            "https://jitpack.io"}

  :profiles {:dev     {:repl-options {:init-ns beat-link-trigger.core
                                      :welcome (println "beat-link-trigger loaded.")}
                       :jvm-opts     ["-XX:-OmitStackTraceInFastThrow"]}
             :uberjar {:aot      :all
                       :jvm-opts ["-Djava.awt.headless=true"]}}

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
            [com.roomkey/lein-v "7.2.0"]]

  :middleware [lein-v.plugin/middleware]

  ;; Perform the tasks which embed the user guide before compilation,
  ;; so it will be available both in development, and in the
  ;; distributed archive. Then compile the adapter class we need to
  ;; work with the Radiance GUI look and feel, and set up the resource
  ;; that allows runtime access to the build version information.
  :prep-tasks [["shell" "npm" "run" "local-docs"]
               "javac"
               "compile"
               ["v" "cache" "resources/beat_link_trigger" "edn"]]

  ;; Miscellaneous sanitary settings
  :pedantic :warn
  :min-lein-version "2.0.0")
