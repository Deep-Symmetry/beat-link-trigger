(ns beat-link-trigger.core
  "Top level organization for starting up the interface, logging, and
  managing online presence."
  (:require [beat-link-trigger.about :as about]
            [beat-link-trigger.help :as help]
            [beat-link-trigger.logs :as logs]
            [beat-link-trigger.menus :as menus]
            [beat-link-trigger.prefs :as prefs]
            [beat-link-trigger.settings :as settings]
            [beat-link-trigger.show :as show]
            [beat-link-trigger.show-util :as su :refer [in-show-ns]]
            [beat-link-trigger.triggers :as triggers]
            [beat-link-trigger.util :as util :refer [in-core-ns in-triggers-ns]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceAnnouncement DeviceFinder VirtualCdj]))

(def ^DeviceFinder device-finder
  "A convenient reference to the [Beat Link
  `DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
  singleton."
  (DeviceFinder/getInstance))

(def ^VirtualCdj virtual-cdj
  "A convenient reference to the [Beat Link
  `VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
  singleton."
  (VirtualCdj/getInstance))

(def dynamic-class-loader
  "The class loader that supports dynamic additions, for users to be
  able to pull in new dependencies. This gets initialized in `start`."
  (atom nil))

(defn finish-startup
  "Called when we have successfully gone online, or the user has said
  they want to proceed offline. If present, the `show` and `suppress`
  keys come from our command-line arguments, telling us which shows
  should be opened."
  [{:keys [show suppress]}]
  (seesaw/invoke-now
   (.setContextClassLoader (Thread/currentThread) @dynamic-class-loader)  ; This is getting reset somewhere.
   (when (triggers/start)             ; Set up the Triggers window, and check if it was created for the first time.
     ;; This is inital startup, so see if we are supposed to open any shows, including those the user had open
     ;; during their previous session.
     (when-not suppress
       (show/reopen-previous-shows))
     (show/reopen-specified-shows show))))

(defn- build-network-description
  "Returns a string suitable for creating a `JLabel` describing the
  current network configuration when we are having trouble finding DJ
  Link devices."
  []
  (let [network (help/list-network-interfaces)]
    #_(timbre/info "Failed going online. Found no DJ Link devices on network interfaces."
                 (str/join "; " network))
    (str "<html>No DJ Link devices were seen on any network, still looking.<br><br>"
         "The following network interfaces were found:<br>"
         (str/join "<br>" network) "<br>&nbsp;")))

(defn try-going-online
  "Search for a DJ link network, presenting a UI in the process. If
  present, `options` holds the command-line options we were given at
  application startup, to control which shows are opened."
  ([]
   (try-going-online nil))
  ([options]
   (let [continue-offline (atom false)
         quit             (atom false)
         searching        (atom (about/create-searching-frame continue-offline quit))
         real-player?     (triggers/real-player?)
         network-label    (seesaw/invoke-now (seesaw/label))]
     (try
       (.start device-finder)  ; We are going to look for devices ourselves, so the user can interrupt us.
       (catch java.net.BindException e
         (timbre/error e "Unable to start Device Finder, is rekordbox or another instance running? Staying offline.")
         (seesaw/invoke-now
           (seesaw/alert (str "<html>Unable to search for devices, socket is in use.<br>"
                              "Is rekordbox or another DJ Link program running?")
                         :title "Failed to Go Online" :type :error)
           (seesaw/dispose! @searching)
           (reset! quit true)))
       (catch Throwable t
         (timbre/error t "Problem starting Device Finder, staying offline.")
         (seesaw/invoke-now
           (seesaw/alert (str "<html>Unable to search for devices, check the log file for details.<br><br>" t)
                         :title "Problem Trying to Go Online" :type :error)
           (seesaw/dispose! @searching)
           (reset! quit true))))
     (timbre/info "Trying to go online, Use Real Player Number?" real-player?)
     (loop [tries-before-troubleshooting 200]  ; Try for twenty seconds before switching to the troubleshooting window.
       (cond
         (not (or @continue-offline @quit (zero? tries-before-troubleshooting)
                  (seq (.getCurrentDevices device-finder))))
         (do  ; Keep looping and looking until something of interest happens.
           (Thread/sleep 100)
           (recur (dec tries-before-troubleshooting)))

         @quit ; User wants to quit.
         (do
           (timbre/info "Giving up attempt to go online, user wants to quit.")
           (seesaw/invoke-soon
             (seesaw/dispose! @searching)
             (triggers/quit)))

         @continue-offline ; User wants to continue offline.
         (do
           (timbre/info "Giving up attempt to go online, user wants to continue offline.")
           (seesaw/invoke-soon (seesaw/dispose! @searching)))

         (zero? tries-before-troubleshooting) ; It is time to show or update the troubleshooting interface.
         (do
           (if (str/blank? (seesaw/config network-label :text))
             ;; This is the first time we are displaying troubleshooting information.
             (seesaw/invoke-now
               (seesaw/dispose! @searching)
               (seesaw/config! network-label :text (build-network-description))
               (reset! searching (about/build-troubleshooting-window network-label continue-offline quit)))
             (seesaw/invoke-now  ; We are just updating the content of the troubleshooting window.
               (seesaw/config! network-label :text (build-network-description))))
           (recur 20))  ; Update every two seconds.

         :else ; We saw a DJ-Link device, and so can go online.
         (do
           (seesaw/invoke-soon (seesaw/dispose! @searching))
           (.setUseStandardPlayerNumber virtual-cdj real-player?)
           (if (try (.start virtual-cdj) ; Make sure we can start the VirtualCdj
                    (catch Exception e
                      (timbre/warn e "Unable to create Virtual CDJ")
                      (seesaw/invoke-now
                        (seesaw/alert (str "<html>Unable to create Virtual CDJ, "
                                           "check log for details.<br><br>" e)
                                      :title "DJ Link Connection Failed" :type :error))))
             (do  ; We succeeded in finding a DJ Link network.
               (timbre/info "Went online, using player number" (.getDeviceNumber virtual-cdj)
                            "and Opus Quad compatibility mode is" (.inOpusQuadCompatibilityMode virtual-cdj))

               ;; Provide warnings about network topology problems
               (when-let [interfaces (seq (help/list-conflicting-network-interfaces))]
                 (seesaw/invoke-now
                   (seesaw/alert (str "<html>Found multiple network interfaces on the DJ Link network.<br>"
                                      "This can lead to duplicate packets and unreliable results:<br><br>"
                                      (str/join "<br>" interfaces))
                                 :title "Network Configuration Problem" :type :warning)))

               (try
                 (when-let [unreachables (seq (.findUnreachablePlayers virtual-cdj))]
                   (let [descriptions (map (fn [^DeviceAnnouncement device]
                                             (str (.getDeviceName device) " (" (.getHostAddress (.getAddress device)) ")"))
                                           unreachables)]
                     (seesaw/invoke-now
                       (seesaw/alert (str "<html>Found devices on multiple networks, and DJ Link can only use one.<br>"
                                          "We will not be able to communicate with the following device"
                                          (when (> (count unreachables) 1) "s") ":<br><br>"
                                          (str/join "<br>" (sort descriptions)))
                                     :title "Network Configuration Problem" :type :error))))
                 (catch Exception e
                   (timbre/error e "Problem finding unreachable players, did network change while going online?"))))
             (do  ; We could not go online even though we see devices.
               (timbre/warn "Unable to create Virtual CDJ")
               (seesaw/invoke-now
                 (seesaw/alert "Unable to create Virtual CDJ, check the log for details."
                               :title "DJ Link Connection Failed" :type :error))))))))

   (finish-startup options)))

(defn start
  "Set up logging, set up our user interface look-and-feel, then make
  sure we can start the [[virtual-cdj]]. If all went well, present the
  Triggers interface. Called when jar startup has detected a
  recent-enough Java version to succcessfully load this namespace, and
  that passes the parsed command-line options."
  ([]
   (start {}))
  ([{:keys [offline reset config] :as options}]
   (logs/init-logging)
   (timbre/info "Beat Link Trigger starting.")

   ;; Set up a dynamic class loader so that users can add new jars or Maven dependencies for their
   ;; expression code at runtime.
   (let [cl (clojure.lang.DynamicClassLoader. (clojure.lang.RT/baseLoader))]
     (.bindRoot Compiler/LOADER cl)
     (reset! dynamic-class-loader cl)
     (.setContextClassLoader (Thread/currentThread) cl)

     ;; Set the actual application name for the macOS menu bar.
     ;; Sadly, this no longer works in current macOS versions, so we
     ;; need to continue to rely on the bundle descriptor, and lack
     ;; a valid name when running in development or as a loose jar.
     (System/setProperty "apple.awt.application.name" "Beat Link Trigger")

     ;; Allow dark title bars. This is supposed to be back-ported to Java 11.0.8,
     ;; but it is not yet working either.
     (System/setProperty "apple.awt.application.appearance" "system")

     ;; Switch to the Swing Event Dispatch Thread to configure the user interface.
     (seesaw/invoke-now
       (seesaw/native!) ; Adopt as native a look-and-feel as possible.
       (System/setProperty "apple.laf.useScreenMenuBar" "false") ; Except put menus in frames.
       (prefs/set-ui-theme)

       ;; Use our dynamic class loader on the Swing thread too.
       (.setContextClassLoader (Thread/currentThread) cl))

     ;; If we are on a Mac, hook up our About and Settings handlers
     ;; where users expect to find them, and add a Quit handler that
     ;; saves the state, and gives users a chance to veto losing
     ;; unsaved editor windows.
     (menus/install-mac-about-handler)
     (menus/install-mac-settings-handler)
     (menus/install-mac-quit-handler)

     ;; If we were given instructions to reset our preferences, try to do so.
     (when-not (str/blank? reset)
       (let [file (io/file reset)]
         (if (= (util/file-type file) :configuration)
           (try
             (prefs/save-to-file file)
             (prefs/put-preferences {})  ; Wipe out the preferences we just backed up.
             (catch Throwable t
               (println "--reset: problem writing existing configuration to file" reset)
               (println "  cause:" t)
               (System/exit 1)))
           (do
             (println "--reset: file" reset "does not have required extension:"
                      (str "." (util/extension-for-file-type :configuration)))
             (System/exit 1)))))

     ;; If we were told to use a specific configuration file, try to do so.
     (when-not (str/blank? config)
       (let [file (io/file config)]
         (if (= (util/file-type file) :configuration)
           (seesaw/invoke-now
             (prefs/load-from-file file))
           (do
             (println "--config: file" config "does not have required extension:"
                      (str "." (util/extension-for-file-type :configuration)))
             (System/exit 1)))))

     (let [preferences (prefs/get-preferences)]

       ;; Restore saved window positions if they exist
       (when-let [saved (:window-positions preferences)]
         (reset! util/window-positions saved))

       ;; Set up to update the user interface theme if we should respect system light/dark settings.
       (.registerListener prefs/theme-detector
                          (reify java.util.function.Consumer
                            (accept [_this dark?]
                              (prefs/set-ui-theme dark?))))

       ;; Honor the user's preferred waveform style
       (let [selected-style (get settings/wave-styles (:waveform-style preferences "RGB"))]
         (if selected-style
           (.setPreferredStyle (org.deepsymmetry.beatlink.data.WaveformFinder/getInstance) selected-style)
           (timbre/error "Unsupported :waveform-style found in preferences:" (:waveform-style preferences)))))

     (if offline
       (finish-startup options)        ; User did not want to go online.
       (try-going-online options)))))  ; Normal startup, try finding a Pioneer DJ Link network.
