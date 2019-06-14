(ns dev
  (:require
    [datomic.api :as d]
    [crux.api :as crux]))

(defn datomic-conn
  []
  (let [id (str (java.util.UUID/randomUUID))]
    (d/create-database (str "datomic:mem://" id))
    (d/connect (str "datomic:mem://" id))))

(defn crux-system
  []
  (crux/start-standalone-system
    {:kv-backend "crux.kv.rocksdb.RocksKv"
     :event-log-dir "event"
     :db-dir "db"}))
