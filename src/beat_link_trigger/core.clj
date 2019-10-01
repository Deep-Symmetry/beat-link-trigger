(ns beat-link-trigger.core
  "Top level organization for starting up the interface, logging, and
  managing online presence."
  (:require [beat-link-trigger.about :as about]
            [beat-link-trigger.logs :as logs]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.show :as show]
            [beat-link-trigger.help :as help]
            [beat-link-trigger.util :as util]
            [beat-link-trigger.triggers :as triggers]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj]
           [java.awt GraphicsEnvironment]
           [javax.swing UIManager]))

(defn finish-startup
  "Called when we have successfully gone online, or the user has said
  they want to proceed offline."
  []
  (seesaw/invoke-now
   (when (triggers/start)             ; Set up the Triggers window, and check if it was created for the first time.
     (show/reopen-previous-shows))))  ; If so also reopen any Show windows the user had open during their last session.

(defn try-going-online
  "Search for a DJ link network, presenting a UI in the process."
  []
  (let [searching     (about/create-searching-frame)
        real-player   (triggers/real-player?)]
    (loop []
      (timbre/info "Trying to go online, Use Real Player Number?" real-player)
      (.setUseStandardPlayerNumber (VirtualCdj/getInstance) real-player)
      (if (try (.start (VirtualCdj/getInstance)) ; Make sure we can see some DJ Link devices and start the VirtualCdj
               (catch Exception e
                 (timbre/warn e "Unable to create Virtual CDJ")
                 (seesaw/invoke-now
                  (seesaw/hide! searching)
                  (seesaw/alert (str "<html>Unable to create Virtual CDJ<br><br>" e)
                                :title "DJ Link Connection Failed" :type :error))))
        (do  ; We succeeded in finding a DJ Link network
          (seesaw/invoke-soon (seesaw/dispose! searching))
          (timbre/info "Went online, using player number" (.getDeviceNumber (VirtualCdj/getInstance)))

          ;; Provide warnings about network topology problems
          (when-let [interfaces (seq (help/list-conflicting-network-interfaces))]
            (seesaw/invoke-now
             (seesaw/alert (str "<html>Found multiple network interfaces on the DJ Link network.<br>"
                                "This can lead to duplicate packets and unreliable results:<br><br>"
                                (clojure.string/join "<br>" interfaces))
                           :title "Network Configuration Problem" :type :warning)))

          (when-let [unreachables (seq (.findUnreachablePlayers (VirtualCdj/getInstance)))]
            (let [descriptions (map #(str (.getName %) " (" (.getHostAddress (.getAddress %)) ")") unreachables)]
              (seesaw/invoke-now
               (seesaw/alert (str "<html>Found devices on multiple networks, and DJ Link can only use one.<br>"
                                  "We will not be able to communicate with the following device"
                                  (when (> (count unreachables) 1) "s") ":<br><br>"
                                  (clojure.string/join "<br>" (sort descriptions)))
                             :title "Network Configuration Problem" :type :error)))))

        (do
          (seesaw/invoke-now (seesaw/hide! searching))  ; No luck so far, ask what to do
          (let [network (help/list-network-interfaces)]
            (timbre/info "Failed going online. Found no DJ Link devices on network interfaces."
                         (clojure.string/join "; " network))
            (let [options (to-array ["Try Again" "Quit" "Continue Offline"])
                  choice  (seesaw/invoke-now
                           (javax.swing.JOptionPane/showOptionDialog
                            nil
                            (str "<html>No DJ Link devices were seen on any network.<br><br>"
                                 "The following network interfaces were found:<br>"
                                 (clojure.string/join "<br>" network) "<br>&nbsp;")
                            "No DJ Link Devices Found"
                            javax.swing.JOptionPane/YES_NO_OPTION javax.swing.JOptionPane/ERROR_MESSAGE nil
                            options (aget options 0)))]
              (case choice
                0 (do (seesaw/invoke-now (seesaw/show! searching)) (recur)) ; Try Again
                2 (seesaw/invoke-soon (seesaw/dispose! searching))          ; Continue Offline
                (System/exit 1))))))))  ; Quit, or just closed the window, which means the same

  (finish-startup))

(defn start
  "Set up logging, set up our user interface look-and-feel, then make
  sure we can start the Virtual CDJ. If all went well, present the
  Triggers interface. Called when jar startup has detected a
  recent-enough Java version to succcessfully load this namespace."
  ([]
   (start false))
  ([offline]
   (logs/init-logging)
   (timbre/info "Beat Link Trigger starting.")

   ;; Set up a dynamic class loader so that users can add new jars or Maven dependencies for their
   ;; expression code at runtime.
   (let [cl (clojure.lang.DynamicClassLoader. (clojure.lang.RT/baseLoader))]
     (.bindRoot Compiler/LOADER cl)
     (.setContextClassLoader (Thread/currentThread) cl)

     ;; Switch to the Swing Event Dispatch Thread to configure the user interface.
     (seesaw/invoke-now
      (seesaw/native!)  ; Adopt as native a look-and-feel as possible.
      (System/setProperty "apple.laf.useScreenMenuBar" "false")  ; Except put menus in frames.
      (try  ; Install our custom dark and textured look-and-feel on top of it.
        (let [skin-class (Class/forName "beat_link_trigger.TexturedRaven")]
          (org.pushingpixels.substance.api.SubstanceCortex$GlobalScope/setSkin (.newInstance skin-class)))
        (catch ClassNotFoundException e
          (timbre/warn "Unable to find our look and feel class, did you forget to run \"lein compile\"?")))

      ;; Use our dynamic class loader on the Swing thread too.
      (.setContextClassLoader (Thread/currentThread) cl)

      ;; If we are running under Java 9 or later on the Mac, and have one of the overly-skinny default system
      ;; fonts, but can swap back to Lucida Grande, do so now.
      (when (and (when-let [font-name (.getName (UIManager/get "MenuBar.font"))]
                   (.startsWith font-name "."))
                 (some #(= "Lucida Grande" %)
                       (.getAvailableFontFamilyNames (GraphicsEnvironment/getLocalGraphicsEnvironment))))
        (doseq [[k v] (filter identity (for [[k v] (UIManager/getDefaults)]
                                         (when (and (instance? javax.swing.plaf.FontUIResource v)
                                                    (.startsWith (.getName v) "."))
                                           [k v])))]
          (UIManager/put k (javax.swing.plaf.FontUIResource. "Lucida Grande" (.getStyle v) (.getSize v))))))

     ;; If we are on a Mac, hook up our About handler where users expect to find it, and add a Quit handler
     ;; that saves the state, and gives users a chance to veto losing unsaved editor windows.
     (menus/install-mac-about-handler)
     (menus/install-mac-quit-handler)

     ;; Restore saved window positions if they exist
     (when-let [saved (:window-positions (prefs/get-preferences))]
       (reset! util/window-positions saved))


     (if offline
       (finish-startup)       ; User did not want to go online.
       (try-going-online)))))  ; Normal startup, try finding a Pioneer DJ Link network.
