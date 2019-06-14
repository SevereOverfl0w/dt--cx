(ns dev
  (:require
    [datomic.api :as d]
    [crux.api :as crux]))

(defn datomic-conn
  []
  (d/create-database "datomic:mem://foo")
  (d/connect "datomic:mem://foo"))

(defn crux-system
  []
  (crux/start-standalone-system
    {:kv-backend "crux.kv.rocksdb.RocksKv"
     :event-log-dir "event"
     :db-dir "db"}))
