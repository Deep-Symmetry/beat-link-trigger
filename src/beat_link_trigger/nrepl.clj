(ns beat-link-trigger.nrepl
  "Allows people to use more full-featured Clojure development
  environments while working with Beat Link Trigger."
  (:require [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.util :as util]
            [nrepl.server :as nrepl-server]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame]))

(defonce ^{:private true
           :doc     "Holds the nREPL server when it is running."}
  server (atom nil))

(defonce ^{:private true
           :doc     "Holds the frame allowing the user to configure and
  control the nREPL server."}
  window (atom nil))

(defn- cider-nrepl-handler
  "Lazily load the CIDER nREPL handler as required by the current
  implementation."
  []
  (require 'cider.nrepl)
  (ns-resolve 'cider.nrepl 'cider-nrepl-handler))

(defn- javadoc-available?
  "Checks whether the jdk.javadoc module is available, since it is
  required by cider-nrepl, and may not be under JDK 11 and later."
  []
  (try (Class/forName "jdk.javadoc.doclet.Doclet")
       true
       (catch Throwable _
         false)))

(defn- start
  "Try to start the nREPL server."
  []
  (swap! server (fn [oldval]
                  (or oldval
                      (let [port-spinner   (seesaw/select @window [:#port])
                            port           (seesaw/selection port-spinner)
                            cider-checkbox (seesaw/select @window [:#cider])
                            cider          (seesaw/value cider-checkbox)]
                        (try
                          (let [server (if cider
                                         (if (javadoc-available?)
                                           (nrepl-server/start-server :port port :handler (cider-nrepl-handler))
                                           (throw (IllegalStateException. "jdk-javadoc module required for CIDER")))
                                         (nrepl-server/start-server :port port))]
                            (seesaw/config! cider-checkbox :enabled? false)
                            (seesaw/config! port-spinner :enabled? false)
                            server)
                          (catch IllegalStateException _
                            (future
                              (seesaw/invoke-later
                               (javax.swing.JOptionPane/showMessageDialog
                                @window
                                (str "The CIDER handler requires the jdk.javadoc module, which is not available.\n"
                                     "If you are using Java 11 or later, you need to launch Beat Link Trigger\n"
                                     "using a command like the following:\n\n"
                                     "java -add-modules jdk.javadoc -jar beat-link-trigger.jar")
                                "jdk.javadoc module required for cider-nrepl"
                                javax.swing.JOptionPane/WARNING_MESSAGE)))
                            (seesaw/value! (seesaw/select @window [:#run]) false)
                            nil)
                          (catch Exception e
                            (timbre/warn e "Unable to start nREPL server")
                            (future
                              (seesaw/invoke-later
                               (javax.swing.JOptionPane/showMessageDialog
                                @window
                                "Unable to start nREPL server on the chosen port, perhaps another process is using it?"
                                "nREPL server startup failed"
                                javax.swing.JOptionPane/WARNING_MESSAGE)))
                            (seesaw/value! (seesaw/select @window [:#run]) false)
                            nil)))))))

(defn- stop
  "Try to stop the nREPL server."
  []
  (swap! server (fn [oldval]
                  (when oldval
                    (try
                      (nrepl-server/stop-server oldval)
                      (catch Exception e
                        (timbre/warn e "Problem stopping nREPL server")
                            (future
                              (seesaw/invoke-later
                               (javax.swing.JOptionPane/showMessageDialog
                                @window
                                "Problem stopping nREPL server, check the log file for details."
                                "nREPL server shutdown failed"
                                javax.swing.JOptionPane/WARNING_MESSAGE)))))
                    (seesaw/config! (seesaw/select @window [:#cider]) :enabled? true)
                    (seesaw/config! (seesaw/select @window [:#port]) :enabled? true))
                  nil)))

(defn- run-choice
  "Handles user toggling the Run checkbox."
  [checked]
  (if checked
    (start)
    (stop)))

(defn- make-window-visible
  "Ensures that the nREPL window is in front, and shown."
  [parent]
  (let [^JFrame our-frame @window]
    (util/restore-window-position our-frame :nrepl parent)
    (seesaw/show! our-frame)
    (.toFront our-frame)))

(defn- create-window
  "Creates the nREPL window."
  [trigger-frame]
  (try
    (let [^JFrame root (seesaw/frame :title "nREPL Server"
                                     :on-close :hide)
          port         (get-in (prefs/get-preferences) [:nrepl :port] 17001)
          cider        (get-in (prefs/get-preferences) [:nrepl :cider] false)
          panel        (mig/mig-panel
                        :items [[(seesaw/label :text "nREPL Port:") "align right"]
                                [(seesaw/spinner :id :port
                                                 :model (seesaw/spinner-model port :from 1 :to 65535)
                                                 :listen [:selection (fn [e]
                                                                       (prefs/put-preferences
                                                                        (assoc-in (prefs/get-preferences) [:nrepl :port]
                                                                                  (seesaw/selection e))))])]
                                [(seesaw/checkbox :id :run :text "Run"
                                                  :listen [:action (fn [e] (run-choice (seesaw/value e)))])
                                 "wrap"]

                                [(seesaw/label :text "Inject CIDER handler?") "span 2, align right"]
                                [(seesaw/checkbox :id :cider :text "Inject"
                                                  :selected? cider
                                                  :listen [:action (fn [e]
                                                                     (prefs/put-preferences
                                                                      (assoc-in (prefs/get-preferences) [:nrepl :cider]
                                                                                (seesaw/value e))))])
                                 "wrap"]])]

      ;; Assemble the window
      (seesaw/config! root :content panel)
      (seesaw/pack! root)
      (.setResizable root false)
      (seesaw/listen root :component-moved (fn [_] (util/save-window-position root :nrepl true)))
      (reset! window root)
      (make-window-visible trigger-frame))
    (catch Exception e
      (timbre/error e "Problem creating nREPL window."))))

(defn show-window
  "Make the nREPL window visible, creating it if necessary."
  [trigger-frame]
  (if @window
    (make-window-visible trigger-frame)
    (create-window trigger-frame)))
