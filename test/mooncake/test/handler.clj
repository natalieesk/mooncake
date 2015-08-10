(ns mooncake.test.handler
  (:require [midje.sweet :refer :all]
            [clj-http.client :as http]
            [stonecutter-oauth.client :as soc]
            [mooncake.handler :as h]))


(def ten-oclock "2015-01-01T10:00:00.000Z")
(def eleven-oclock "2015-01-01T11:00:00.000Z")
(def twelve-oclock "2015-01-01T12:00:00.000Z")

(fact "index handler displays activities retrieved from activity sources"
      (let [an-activity-src-url "https://an-activity.src"
            another-activity-src-url "https://another-activity.src"]
        (h/index {:context
                  {:activity-sources
                   {:an-activity-src an-activity-src-url
                    :another-activity-src another-activity-src-url}}}) => (every-checker
                                                                            (contains {:status 200})
                                                                            (contains {:body (contains "JDog")})
                                                                            (contains {:body (contains "KCat")}))
        (provided
          (http/get an-activity-src-url {:accept :json
                                         :as :json-string-keys})       => {:body [{"actor" {"@type" "Person"
                                                                                            "displayName" "JDog"}
                                                                                   "published" ten-oclock}]}
          (http/get another-activity-src-url {:accept :json
                                              :as :json-string-keys})  => {:body [{"actor" {"@type" "Person"
                                                                                            "displayName" "KCat"}
                                                                                   "published" twelve-oclock}]})))

(future-fact "stonecutter-sign-in handler delegates to the stonecutter client library"
      (h/stonecutter-sign-in ...stonecutter-config... ...request...) => ...stonecutter-sign-in-redirect...
      (provided
        (soc/authorisation-redirect-response ...stonecutter-config...) => ...stonecutter-sign-in-redirect...))
