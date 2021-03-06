(ns mooncake.test.test-helpers.db
  (:require [monger.db :as mdb]
            [monger.core :as m]
            [clojure.set :as set]
            [mooncake.db.mongo :as mongo]
            [mooncake.db.activity :as activity]
            [clj-time.core :as t]
            [clj-time.format :as f])
  (:import (java.util UUID)))

(defn find-item-with-id [data-map coll query-m]
  (some #(when (set/subset? (set query-m) (set %)) %) (vals (get data-map coll))))

(defn to-vector [value]
  (if (sequential? value)
    value
    [value]))

(defn find-by-map-query [store coll single-query-map]
  (let [all-documents (mongo/fetch-all store coll)
        documents-with-matching-key-values (fn [[k vs]] (filter #((set (to-vector vs)) (get % k)) all-documents))
        search-result-sets (->> single-query-map
                                (map documents-with-matching-key-values)
                                (map set))]
    (if (empty? search-result-sets)
      all-documents
      (apply set/intersection search-result-sets))))

(def neutral-comp-fn (constantly 0))

(defn secondary-key-sort-fn [primary-sort-result value-1 value-2 sort-key]
  (if (and (= 0 primary-sort-result) sort-key)
    (compare (get value-1 sort-key) (get value-2 sort-key))
    primary-sort-result))

(defn options-m->compare-fn [options-m]
  (if-let [primary-sort-key (first (keys (:sort options-m)))]
    (let [ordering-key (get-in options-m [:sort primary-sort-key])
          ordering-multiplier (get {:ascending 1 :descending -1} ordering-key 0)
          secondary-sort-key (second (keys (:sort options-m)))]
      (fn [value-1 value-2]
        (-> (compare (get value-1 primary-sort-key) (get value-2 primary-sort-key))
            (secondary-key-sort-fn value-1 value-2 secondary-sort-key)
            (* ordering-multiplier))))
    neutral-comp-fn))

(defn options-m->batch-fn [options-m]
  (if-let [batch-size (:limit options-m)]
    (partial take batch-size)
    identity))

(defn conj-unique [coll value]
  (if (and coll (= -1 (.indexOf coll value)))
    (conj coll value)
    coll))

(defn options-m->skip-fn [options-m]
  (let [page-number (:page-number options-m)
        batch-size (:limit options-m)]
    (if (and page-number batch-size)
      (partial drop (* batch-size (- page-number 1)))
      identity)))

(defn timestamp-fn [timestamp older-items-requested?]
  (let [comparitor (if older-items-requested? >= <=)]
    (partial filter
             #(comparitor 0 (compare (:published %) timestamp)))))

(defn id-fn [id timestamp older-items-requested?]
  (let [comparitor (if older-items-requested? > <)]
    (partial filter
             #(or (not (= (:published %) timestamp))
                  (comparitor 0 (compare (:relInsertTime %) id))))))

(defrecord MemoryStore [data]
  mongo/Store
  (fetch [this coll id]
    (-> (get-in @data [coll id])
        (dissoc :_id)))

  (fetch-all [this coll]
    (->> (vals (get @data coll))
         (map #(dissoc % :_id))))

  (find-item [this coll query-m]
    (when query-m
      (-> (find-item-with-id @data coll query-m)
          (dissoc :_id))))

  (find-items-by-alternatives [this coll value-map-vector options-m]
    (let [comp-fn (options-m->compare-fn options-m)
          batch-fn (options-m->batch-fn options-m)
          skip-fn (options-m->skip-fn options-m)]
      (->> value-map-vector
           (map #(find-by-map-query this coll %))
           (apply set/union)
           (sort comp-fn)
           skip-fn
           batch-fn)))

  (fetch-total-count-by-query [this coll value-map-vector]
    (->> value-map-vector
         (map #(find-by-map-query this coll %))
         (apply set/union)
         count))

  (store! [this coll item]
    (->> (assoc item :_id (UUID/randomUUID))
         (mongo/store-with-id! this coll :_id)))

  (store-with-id! [this coll key-param item]
    (let [item-key (key-param item)]
      (if (mongo/fetch this coll item-key)
        (throw (Exception. "Duplicate ID!"))
        (do
          (swap! data assoc-in [coll item-key] (assoc item :_id item-key))
          (dissoc item :_id)))))

  (upsert! [this coll query key-param value]
    (if-let [item (find-item-with-id @data coll query)]
      (swap! data assoc-in [coll (:_id item)] (assoc item key-param value))
      (let [id (UUID/randomUUID)]
        (swap! data assoc-in [coll id] (assoc query :_id id key-param value)))))

  (add-to-set! [this coll query key-param value]
    (if-let [item (find-item-with-id @data coll query)]
      (swap! data assoc-in [coll (:_id item)] (update item key-param conj-unique value))
      (let [id (UUID/randomUUID)]
        (swap! data assoc-in [coll id] (assoc query :_id id key-param [value])))))

  (find-items-by-timestamp-and-id [this coll value-map-vector options-m timestamp id older-items-requested?]
    (let [comp-fn (options-m->compare-fn options-m)
          batch-fn (options-m->batch-fn options-m)
          timestamp-fn (timestamp-fn timestamp older-items-requested?)
          id-fn (id-fn id timestamp older-items-requested?)]
      (->> value-map-vector
           (map #(find-by-map-query this coll %))
           (apply set/union)
           timestamp-fn
           id-fn
           (sort comp-fn)
           batch-fn))))

(defn create-in-memory-store
  ([] (create-in-memory-store {}))
  ([data] (MemoryStore. (atom data))))

(def test-db "mooncake-test")
(def test-db-uri (str "mongodb://localhost:27017/" test-db))

(defn drop-db! []
  (let [{:keys [conn db]} (m/connect-via-uri test-db-uri)]
    (mdb/drop-db db)
    (m/disconnect conn)))

(defn with-mongo-do [thing-to-do]
  (let [{:keys [db conn]} (mongo/get-mongo-db-and-conn test-db-uri)]
    (try (mdb/drop-db db)
         (thing-to-do db)
         (finally (m/disconnect conn)))))

(defn create-dummy-activities [store amount]
  (->> (range amount)
       (map (fn [counter]
              {:actor         {:name (str "TestData" counter)}
               :published     (f/unparse (f/formatters :date-time) (t/plus (t/date-time 2015 8 12) (t/seconds counter)))
               :activity-src  "test-source"
               :relInsertTime counter
               :type          "Create"}))
       (map (partial activity/store-activity! store))
       doall))