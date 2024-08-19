(ns beat-link-trigger.socket-picker
  "Provides a simple user interface for picking a host and optionally
  port number, to facilitate building custom user interfaces in
  plug-and-play integration example shows."
  (:require [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig])
  (:import (javax.swing JDialog)))

(defn show
  "Creates and displays the socket picker modal dialog. Defaults to a
  hostname of `localhost` unless a value is specified with `:host`. If
  canceled, returns `nil`. Otherwise validates that hostname can be
  resolved before returning it.

  If a port number is supplied with `:port`, displays and allows that
  to be edited as well, and returns a vector of the chosen hostname
  and port number upon successful completion.

  The dialog title defaults to `Configure Socket`, but a custom title
  can be supplied with `:title`.

  If a parent frame is specified with `:parent`, the dialog is
  centered over it, otherwise it is centered on the screen.

  The label of the confirmation button is normally `Configure`, but
  this can be changes by passing a value with `:accept-label`."
  [& {:keys [host port parent title accept-label]
      :or   {host         "localhost"
             title        "Configure Socket"
             accept-label "Configure"}}]
  (let [panel           (mig/mig-panel
                         :items (concat
                                 [["Hostname or Address:" "align right"]
                                  [(seesaw/text :id :host :text (str/trim host) :columns 30) "wrap"]]
                                 (when port
                                   [["Port Number:" "align right"]
                                    [(seesaw/spinner :id :port
                                                     :model (seesaw/spinner-model port :from 0 :to 65535))]])))
        accept-button   (seesaw/button :text accept-label)
        cancel-button   (seesaw/button :text "Cancel")
        ^JDialog dialog (seesaw/dialog :content panel :options [accept-button cancel-button]
                                       :title title :default-option accept-button :modal? true)]
    (.pack dialog)
    (.setLocationRelativeTo dialog parent)
    (seesaw/listen accept-button :action-performed
                   (fn [_]
                     (let [chosen-host (str/trim (seesaw/value (seesaw/select panel [:#host])))]
                       (try
                         (java.net.InetAddress/getByName chosen-host)
                         (seesaw/return-from-dialog dialog
                                                    (if port
                                                      [chosen-host (seesaw/value (seesaw/select panel [:#port]))]
                                                      chosen-host))
                         (catch Exception e
                           (seesaw/alert dialog e
                                         :title "Unable to Resolve Hostname" :type :error))))))
    (seesaw/listen cancel-button :action-performed (fn [_] (seesaw/return-from-dialog dialog nil)))
    (seesaw/show! dialog)))
