(ns mooncake.controller.create-account
  (:require
    [mooncake.helper :as mh]
    [mooncake.validation :as v]
    [mooncake.db.user :as user]
    [mooncake.view.create-account :as cav]))

(defn show-create-account [request]
  (if (mh/authenticated? request)
    (mh/enlive-response (cav/create-account request) request)
    (mh/redirect-to request :sign-in)))

(defn- account-created-response [request user]
  (let [updated-session (-> (:session request)
                            (dissoc :auth-provider-user-id)
                            (assoc :username (:username user)))]
    (-> (mh/redirect-to request :feed)
        (assoc :session updated-session))))

(defn- create-account-response [store request username]
  (let [auth-provider-user-id (get-in request [:session :auth-provider-user-id])
        created-user (user/create-user! store auth-provider-user-id username)]
    (account-created-response request created-user)))

(defn is-username-duplicate? [store username]
  (boolean (user/find-user store username)))

(defn create-account [store request]
  (if (mh/authenticated? request)
    (let [username (get-in request [:params :username])
          duplicate-username-fn (partial is-username-duplicate? store)]
      (if-let [username-validation-errors (v/validate-username username duplicate-username-fn)]
        (-> request
            (assoc-in [:context :error-m] username-validation-errors)
            (assoc-in [:context :params :username] username)
            show-create-account)
        (create-account-response store request username)))
    (mh/redirect-to request :sign-in)))
