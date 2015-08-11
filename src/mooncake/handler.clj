(ns mooncake.handler
  (:require [scenic.routes :as scenic]
            [ring.adapter.jetty :as ring-jetty]
            [ring.util.response :as r]
            [ring.middleware.defaults :as ring-mw]
            [clojure.java.io :as io]
            [stonecutter-oauth.client :as soc]
            [mooncake.routes :as routes]
            [mooncake.config :as config]
            [mooncake.translation :as t]
            [mooncake.view.index :as i]
            [mooncake.view.error :as error]
            [mooncake.helper :as mh]
            [mooncake.activity :as a]
            [mooncake.middleware :as m]))

(def default-context {:translator (t/translations-fn t/translation-map)})

(defn index [request]
  (let [activity-sources (get-in request [:context :activity-sources])
        activities (a/retrieve-activities activity-sources)]
    (mh/enlive-response (i/index (assoc-in request [:context :activities] activities)) default-context)))

(defn stonecutter-sign-in [stonecutter-config request]
  (soc/authorisation-redirect-response stonecutter-config))

(defn stonecutter-callback [stonecutter-config request]
  (let [config-m (get-in request [:context :config-m])
        auth-code (get-in request [:params :code])
        token-response (soc/request-access-token! stonecutter-config auth-code)
        auth-provider-user-id (:user-id token-response)]
    (-> (r/redirect (routes/absolute-path config-m :index))
        (assoc-in [:session :user-id] auth-provider-user-id))))

(defn stub-activities [request]
  (-> "stub-activities.json"
      io/resource
      slurp
      r/response
      (r/content-type "application/json")))

(defn not-found [request]
  (-> (error/not-found-error)
      (mh/enlive-response default-context)
      (r/status 404)))

(defn forbidden-err-handler [req]
  (-> (error/forbidden-error)
      (mh/enlive-response default-context)
      (r/status 403)))

(defn create-stonecutter-config [config-m]
  (soc/configure (config/auth-url config-m)
                 (config/client-id config-m)
                 (config/client-secret config-m)
                 (str (config/base-url config-m) "/d-cent-callback")))

(defn site-handlers [config-m]
  (let [stonecutter-config (create-stonecutter-config config-m)]
    (-> {:index index
         :stub-activities stub-activities
         :stonecutter-sign-in (partial stonecutter-sign-in stonecutter-config)
         :stonecutter-callback (partial stonecutter-callback stonecutter-config)}
        (m/wrap-handlers #(m/wrap-handle-403 % forbidden-err-handler) #{}))))

(defn wrap-defaults-config [secure?]
  (-> (if secure? (assoc ring-mw/secure-site-defaults :proxy true) ring-mw/site-defaults)
      (assoc-in [:session :cookie-attrs :max-age] 3600)
      (assoc-in [:session :cookie-name] "mooncake-session")))

(defn create-app [config-m activity-sources]
  (-> (scenic/scenic-handler routes/routes (site-handlers config-m) not-found)
      (ring-mw/wrap-defaults (wrap-defaults-config (config/secure? config-m)))
      (m/wrap-config config-m)
      (m/wrap-activity-sources activity-sources)
      m/wrap-translator))

(def app (create-app (config/create-config) a/activity-sources))

(defn -main [& args]
  (let [config-m (config/create-config)]
    (ring-jetty/run-jetty app {:port (config/port config-m)
                               :host (config/host config-m)})))
