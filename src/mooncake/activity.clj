(ns mooncake.activity
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [mooncake.db.activity :as adb]
            [mooncake.config :as config]))

(defn add-index [i [k v]]
  [k (assoc v :index i)])

(defn add-source-indices [source-map]
  (->>
    source-map
    (sort-by key)
    (map add-index (range))
    (into {})))

(defn parse-activity-source-yaml [yaml]
  (-> yaml
      yaml/parse-string
      add-source-indices))

(defn load-activity-sources-from-resource [activity-resource-name]
  (log/info (format "Loading activity sources from resource [%s]" activity-resource-name))
  (-> activity-resource-name
      io/resource
      slurp
      parse-activity-source-yaml))

(defn load-activity-sources-from-file [file-name]
  (log/info (format "Loading activity sources from file [%s]" file-name))
  (-> file-name
      slurp
      parse-activity-source-yaml))

(defn load-activity-sources [config-m]
  (if-let [f (config/activity-source-file config-m)]
    (load-activity-sources-from-file f)
    (load-activity-sources-from-resource "activity-sources.yml")))

(defn is-signed-activity-src? [url]
  #_(try
    (let [test-response (http/get url {:accept :json :as :json})]
      (au/is-signed-response? test-response))
    (catch Exception e
      (log/error (str "Activity url provided did not respond as expected: " url))
      false))
  true)

(defn retrieve-activities [store activity-source-keys params]
  (adb/fetch-activities-by-activity-sources-and-types store activity-source-keys params))

(defn total-count-by-feed [store activity-source-keys]
  (adb/fetch-total-count-by-sources-and-types store activity-source-keys))

(defn is-last-page? [page-number total-activities]
  (try
    (== (Math/ceil (/ total-activities config/activities-per-page)) page-number)
    (catch Exception e
      false)))

(defn parse-page-number [page-number-str]
  (try
    (Integer/parseInt (or page-number-str "1"))
    (catch Exception e
           nil)))

(defn last-page-number [total-activities]
  (if (= total-activities 0)
    1
    (Math/ceil (/ total-activities config/activities-per-page))))

(defn page-number-is-in-correct-range [page-number total-activities]
  (when page-number
    (and (> page-number 0) (<= page-number (last-page-number total-activities)))))

(defn activity-src-preferences->feed-query [preferences-for-an-activity-src]
  (let [selected-types (map :id (filter :selected (:activity-types preferences-for-an-activity-src)))]
    (when-not (empty? selected-types)
      {:activity-src (name (:id preferences-for-an-activity-src))
       :type         selected-types})))