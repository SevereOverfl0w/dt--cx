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

(defn print-crux-tx
  [tx-ops]
  (doseq [tx-op tx-ops]
    (case (first tx-op)
      :crux.tx/put (println "+" (str tx-op))
      :crux.tx/delete (println "-" (str tx-op))
      (println "~" (str tx-op)))))

(defn print-crux-txs
  [tx-opss]
  (doseq [tx-ops tx-opss]
    (println "=========")
    (print-crux-tx tx-ops)))
