(defproject beat-link-trigger :lein-v
  :description "Trigger events and automate shows in response to CDJ activity."
  :url "https://github.com/Deep-Symmetry/beat-link-trigger"
  :scm {:name "git" :url "https://github.com/Deep-Symmetry/beat-link-trigger"}
  :license {:name "Eclipse Public License 2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :aot [beat-link-trigger.BeatLinkTrigger beat-link-trigger.TexturedRaven]
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.681"]
                 [org.clojure/data.csv "1.0.1"]
                 [org.clojure/tools.cli "1.0.219"]
                 [org.deepsymmetry/electro "0.1.4"]
                 [org.deepsymmetry/beat-link "7.3.0-SNAPSHOT"]
                 [beat-carabiner "7.3.0-SNAPSHOT"]
                 [cheshire "5.12.0"]
                 [cider/cider-nrepl "0.41.0"]
                 [clj-commons/pomegranate "1.2.23"]
                 [com.cemerick/url "0.1.1"]
                 [com.fifesoft/rstaui "3.3.1"]
                 [com.fifesoft/rsyntaxtextarea "3.3.4"]
                 [com.fzakaria/slf4j-timbre "0.4.0"]
                 [com.github.jiconfont/jiconfont-font_awesome "4.7.0.1"]
                 [com.github.jiconfont/jiconfont-swing "1.0.1"]
                 [com.taoensso/timbre "6.3.1"]
                 [compojure "1.7.0"]
                 [fipp "0.6.26"]
                 [hiccup "1.0.5"]
                 [http.async.client "1.4.0"]
                 [http-kit "2.7.0"]
                 [inspector-jay "0.3" :exclusions [org.clojure/core.memoize]]
                 [javax.servlet/servlet-api "2.5"]
                 [me.raynes/fs "1.4.6"]
                 [nrepl "1.0.0"]
                 [org.apache.maven/maven-artifact "3.9.5"]
                 [org.pushing-pixels/radiance-substance "1.0.2"]
                 [org.pushing-pixels/radiance-substance-extras "1.0.2"]
                 [overtone/midi-clj "0.5.0" :exclusions [overtone/at-at]]
                 [overtone/osc-clj "0.9.0"]
                 [ring/ring-core "1.10.0"]
                 [ring/ring-defaults "0.4.0"]
                 [seesaw "1.5.0"]
                 [selmer "1.12.59"]
                 [thi.ng/color "1.5.1"]
                 [uk.co.xfactory-librarians/coremidi4j "1.6"]]
  :repositories {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}

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
