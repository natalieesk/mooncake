(ns mooncake.db.mongo
  (:require [monger.core :as mcore]
            [monger.operators :as mop]
            [monger.collection :as mcoll]
            [clojure.tools.logging :as log]
            [mooncake.helper :as mh]))

(defprotocol Store
  (fetch [this coll k]
    "Find the item based on a key.")
  (fetch-all [this coll]
    "Find all items based on a collection.")
  (find-item [this coll query-m]
    "Find an item matching the query-map.")
  (find-items-by-alternatives [this coll value-map-vector options-m]
    "Find items whose properties match properties of at least one of the provided maps.")
  (find-items-by-timestamp-and-id [this coll value-map-vector options-m timestamp id older-items-requested?]
    "Find items whose published time is less than given timestamp")
  (fetch-total-count-by-query [this coll value-map-vector]
    "Get total count of items that match the query.")
  (store! [this coll item]
    "Store the given map and return it.")
  (store-with-id! [this coll key-param item]
    "Store the given map using the value of the kw key-param and return it.")
  (upsert! [this coll query key-param value]
    "Update item that corresponds to query by replacing key-param with value, or if none exist insert it.")
  (update-by-id! [this coll id item]
    "Change item that corresponds to the id to the provided item.")
  (add-to-set! [this coll query key-param value]
    "Add value to the key-param array in the item found with query, ensuring there are no duplicates."))

(defn dissoc-id [item]
  (dissoc item :_id))

(defn value->mongo-query-value [value]
  (if (sequential? value)
    {mop/$in value}
    value))

(defn value-map->mongo-query-map [value-m]
  (reduce-kv #(assoc %1 %2 (value->mongo-query-value %3)) {} value-m))

(defn value-map-vector->or-mongo-query-map [value-map-vector]
  {mop/$or (map value-map->mongo-query-map value-map-vector)})

(defn options-m->sort-query-map [options-m]
  (-> (:sort options-m)
      (mh/map-over-values {:ascending 1 :descending -1})))

(defn skip-amount [batch-size page-number]
  (if (nil? page-number)
    0
    (* (- page-number 1) batch-size)))

(defn comparitor [older-items-requested?]
  (if older-items-requested?
    mop/$lt
    mop/$gt))

(defn comparitor-with-equality [older-items-requested?]
  (if older-items-requested?
    mop/$lte
    mop/$gte))

(defrecord MongoStore [mongo-db]
  Store
  (fetch [this coll k]
    (when k
      (-> (mcoll/find-map-by-id mongo-db coll k [])
          dissoc-id)))

  (fetch-all [this coll]
    (->> (mcoll/find-maps mongo-db coll)
         (map dissoc-id)))

  (find-item [this coll query-m]
    (when query-m
      (-> (mcoll/find-one-as-map mongo-db coll query-m [])
          dissoc-id)))

  (find-items-by-alternatives [this coll value-map-vector options-m]
    (if (not-empty value-map-vector)
      (let [mongo-query-map (value-map-vector->or-mongo-query-map value-map-vector)
            sort-query-map (options-m->sort-query-map options-m)
            batch-size (:limit options-m)
            page-number (:page-number options-m)
            skip-amount (skip-amount batch-size page-number)
            aggregation-pipeline (cond-> []
                                         :always (conj {mop/$match mongo-query-map})
                                         (not (empty? sort-query-map)) (conj {mop/$sort sort-query-map})
                                         :always (conj {mop/$skip skip-amount})
                                         (not (nil? batch-size)) (conj {mop/$limit batch-size}))]
        (->> (mcoll/aggregate mongo-db coll aggregation-pipeline)
             (map dissoc-id)))
      []))

  (fetch-total-count-by-query [this coll value-map-vector]
    (if (not-empty value-map-vector)
      (let [mongo-query-map (value-map-vector->or-mongo-query-map value-map-vector)
            query-result (mcoll/aggregate mongo-db coll [{mop/$match mongo-query-map}])]
        (count query-result))
      0))

  (store! [this coll item]
    (-> (mcoll/insert-and-return mongo-db coll item)
        dissoc-id))

  (store-with-id! [this coll key-param item]
    (->> (assoc item :_id (key-param item))
         (store! this coll)))

  (upsert! [this coll query key-param value]
    (mcoll/update mongo-db coll query {mop/$set {key-param value}} {:upsert true}))

  (update-by-id! [this coll id item]
    (mcoll/update-by-id mongo-db coll id item))

  (add-to-set! [this coll query key-param value]
    (mcoll/update mongo-db coll query {mop/$addToSet {key-param value}} {:upsert true}))

  (find-items-by-timestamp-and-id [this coll value-map-vector options-m timestamp insert-time-id older-items-requested?]
    (if (not-empty value-map-vector)
      (let [mongo-query-map (value-map-vector->or-mongo-query-map value-map-vector)
            sort-query-map (options-m->sort-query-map options-m)
            timestamp-query-map {:published {(comparitor-with-equality older-items-requested?) timestamp}}
            insert-time-query-map {mop/$or [{:published {mop/$ne timestamp}}
                                            {:relInsertTime {(comparitor older-items-requested?) insert-time-id}}]}
            batch-size (:limit options-m)
            aggregation-pipeline (cond-> []
                                         :always (conj {mop/$match mongo-query-map})
                                         :always (conj {mop/$match timestamp-query-map})
                                         :always (conj {mop/$match insert-time-query-map})
                                         (not (empty? sort-query-map)) (conj {mop/$sort sort-query-map})
                                         (not (nil? batch-size)) (conj {mop/$limit batch-size}))]
        (->> (mcoll/aggregate mongo-db coll aggregation-pipeline)
             (map dissoc-id)))
      [])))


(defn create-mongo-store [mongodb]
  (MongoStore. mongodb))

(defn get-mongo-db-and-conn [mongo-uri]
  (log/debug "Connecting to mongo.")
  (let [db-and-conn (mcore/connect-via-uri mongo-uri)]
    (log/debug "Connected to mongo.")
    db-and-conn))

(defn get-mongo-db [mongo-uri]
  (:db (get-mongo-db-and-conn mongo-uri)))

