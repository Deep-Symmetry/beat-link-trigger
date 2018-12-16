(ns beat-link-trigger.menus
  "Provides support for menu options used in various other
  namespaces."
  (:require [beat-link-trigger.about :as about]
            [beat-link-trigger.help :as help]
            [beat-link-trigger.logs :as logs]
            [beat-link-trigger.util :as util]
            [cemerick.url :as url]
            [seesaw.core :as seesaw]
            [seesaw.util]
            [taoensso.timbre :as timbre])
  (:import [java.awt Desktop Desktop$Action]))

(defn on-mac?
  "Do we seem to be running on a Mac?"
  []
  (-> (System/getProperty "os.name")
      .toLowerCase
      (clojure.string/includes? "mac")))

(defn install-mac-about-handler
  "If we are running on a Mac, install our About handler."
  []
  (when (on-mac?)
    (try
      (if (< (Float/valueOf (System/getProperty "java.specification.version")) 9.0)
        (eval '(.setAboutHandler (com.apple.eawt.Application/getApplication) ; Use old, Mac-specific approach.
                                 (proxy [com.apple.eawt.AboutHandler] []
                                   (handleAbout [_]
                                     (beat-link-trigger.about/show)))))
        (eval '(.setAboutHandler (java.awt.Desktop/getDesktop) ; Java 9 or later has a cross-platform way to do it.
                           (proxy [java.awt.desktop.AboutHandler] []
                             (handleAbout [_]
                               (beat-link-trigger.about/show))))))
      (catch Throwable t
        (timbre/error t "Unable to install Mac \"About\" handler.")))))

(defonce ^{:private true
       :doc "Holds the function, if any, that is waiting for an indication of whether it is OK to quit."}
  quit-response-fn (atom nil))

(defn install-mac-quit-handler
  "If we are running on a Mac, install our Quit handler."
  []
  (when (on-mac?)
    (binding [*ns* (the-ns 'beat-link-trigger.menus)]
      (if (< (Float/valueOf (System/getProperty "java.specification.version")) 9.0)
        (eval '(.setQuitHandler (com.apple.eawt.Application/getApplication) ; Use old, Mac-specific approach.
                                (proxy [com.apple.eawt.QuitHandler] []
                                  (handleQuitRequestWith [e response]
                                    (reset! quit-response-fn
                                            (fn [proceed?]
                                              (if proceed?
                                                (.performQuit response)
                                                (.cancelQuit response))))
                                    ((resolve 'beat-link-trigger.triggers/quit))))))
        (eval '(.setQuitHandler (java.awt.Desktop/getDesktop) ; Java 9 or later has a cross-platform way to do it.
                                (proxy [java.awt.desktop.QuitHandler] []
                                  (handleQuitRequestWith [e response]
                                    (reset! quit-response-fn
                                            (fn [proceed?]
                                              (if proceed?
                                                (.performQuit response)
                                                (.cancelQuit response))))
                                    ((resolve 'beat-link-trigger.triggers/quit))))))))))

(defn respond-to-quit-request
  "If we were asked to quit by the host operating system, let it know
  that it is OK to proceed, or that the user chose to cancel the
  operation due to unsaved changes."
  [proceed?]
  (when-let [response-handler @quit-response-fn]  ; First check if there is a request to respond to!
    (response-handler proceed?)))

(defn non-mac-file-actions
  "Return The actions which are automatically available in the
  Application menu on the Mac, but must be added to the File menu on
  other platforms. This value will be empty when running on the Mac.
  `quit` the function that should be used to gracefully quit the
  application."
  [quit]
  (when-not (on-mac?)
    [(seesaw/separator)
     (seesaw/action :handler (fn [e] quit)
                    :name "Exit")]))

(defn mail-supported?
  "Checks whether the runtime supports opening the default mail client."
  []
  (and (Desktop/isDesktopSupported)
       (.. Desktop getDesktop (isSupported Desktop$Action/MAIL))))

(defn compose-mail
  "Launches the system email client to send a message with the supplied
  initial subject and body. Use \\r\\n inside the body to separate lines."
  [subject body]
  (let [uri (java.net.URI. (str "mailto:james@deepsymmetry.org?subject=" (url/url-encode subject)
                                "&body=" (url/url-encode body)))]
    (.. Desktop getDesktop (mail uri))))

(def gitter-chat-url
  "Where people discuss Beat Link Trigger."
  "https://gitter.im/brunchboy/beat-link-trigger")

(def project-home-url
  "The GitHub front page for the project."
  "https://github.com/Deep-Symmetry/beat-link-trigger")

(def project-wiki-url
  "The community wiki for the project."
  "https://github.com/Deep-Symmetry/beat-link-trigger/wiki")

(def issues-url
  "The project Issues page on GitHub."
  "https://github.com/Deep-Symmetry/beat-link-trigger/issues")

(def default-issue-description
  "The placeholder text to insert when we do not have an issue of our
  own to report."
  (str "(Please fill in as many details as you can here.\r\n"
       "  * What did you expect and what actually happened?\r\n"
       "  * Can you include screen shots if they will help explain it?\r\n"
       "  * Can you copy and paste relevant sections of the logs (found "
       "under Help->Open Logs Folder) showing stack traces when things "
       "were going wrong?\r\n"
       " The more you can share, the more likely we will be able to help.)"))

(defn- version-block
  "Builds an email chunk reporting the versions of the application,
  Java, and operating system."
  []
  (str "Beat Link Trigger version: " (util/get-version)
       (when-let [built (util/get-build-date)]
         (str ", built " built))
       "\r\n"
       "Java version: " (util/get-java-version) "\r\n"
       "Operating system: " (util/get-os-version) "\r\n\r\n"))

(defn report-issue
  "If the system email client can be launched, composes an email with
  information useful for reporting an issue. If `text` is supplied it
  is used as the initial issue description (lines must be separated
  using \\r\\n). The user can still edit the email and decide whether
  or not they want to send it.

  If we can't launch an email client, we simply try to open the
  project Issues page in a web browser and let the user take it from
  there."
  ([]
   (report-issue nil))
  ([text]
   (if (mail-supported?)
     (let [body (str (version-block)
                     "Issue description:\r\n"
                     (or text default-issue-description))]
       (compose-mail "Beat Link Trigger issue report" body))
     (clojure.java.browse/browse-url issues-url))))

(defn send-greeting
  []
  (compose-mail "Greetings from a Beat Link Trigger User"
                (str "Hi, James!\r\n\r\n"
                     "My name is: \r\n"
                     "I live in: \r\n"
                     "The cool things I am using (or plan to use) Beat Link Trigger for are: \r\n\r\n\r\n"
                     "And, some less-fun but nerdy version information...\r\n"
                     (version-block))))

(defn build-help-menu
  "Creates the help menu, which will also include the actions which are
  automatically available in the Application menu on the Mac, but must
  be added to the Help menu on other platforms."
  []
  (seesaw/menu :text "Help"
               :items (concat (when-not (on-mac?)
                                [(seesaw/action :handler (fn [_]
                                                           (try
                                                             (about/show)
                                                             (catch Exception e
                                                               (timbre/error e "Problem showing About window."))))
                                                :name "About BeatLinkTrigger")
                                 (seesaw/separator)])
                              [(seesaw/action :handler (fn [_] (help/show-user-guide))
                                              :name "User Guide (local)")
                               logs/logs-action (seesaw/separator)
                               (seesaw/action :handler (fn [_] (clojure.java.browse/browse-url gitter-chat-url))
                                              :name "Discuss on Gitter (web)")
                               (seesaw/action :handler (fn [_] (clojure.java.browse/browse-url project-home-url))
                                              :name "Project Home (web)")
                               (seesaw/action :handler (fn [_] (clojure.java.browse/browse-url project-wiki-url))
                                              :name "Project Wiki (web)")
                               (seesaw/separator)
                               (seesaw/action :handler (fn [_] (report-issue))
                                              :name (str "Report Issue (" (if (mail-supported?) "email" "web") ")"))]
                              (when (mail-supported?)
                                [(seesaw/separator)
                                 (seesaw/action :handler (fn [_] (send-greeting)) :name "Send User Greeting (email)")]))
               :id :help-menu))
