(ns io.dominic.datomic->crux.core
  (:require
    [datomic.api :as d]
    [crux.api :as crux]
    [manifold.stream :as s])
  (:import
    [datomic Attribute]))

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

(defn dt-id->crux-id
  [eid]
  (java.net.URI. (str "datomic:mem://foo/" eid)))

(defn- merge-with-f
  "Like merge-with, but using custom key merge function"
  [f & ms]
  (reduce
    #(reduce
       (fn [m [k v]]
         (if (contains? m k)
           (update m k (f k) v)
           (assoc m k v)))
       %1 %2)
    {} ms))

(defn invoke
  [f attr v]
  (if (= Attribute/CARDINALITY_MANY (.cardinality attr))
    (map f v)
    (f v)))

(defn dt-transaction->crux-tx
  [latest-db last-t transaction]
  (let [{:keys [t data]} transaction
        db (d/as-of latest-db t)]
    (conj
      (mapv (fn [doc]
              (let [crux-id (dt-id->crux-id (:db/id doc))]
                (if (seq (dissoc doc :db/id))
                  [:crux.tx/put crux-id (assoc doc :crux.db/id crux-id)]
                  [:crux.tx/delete crux-id])))
            (map
              (fn [doc]
                (into {}
                      (map (fn [[k v]]
                             (let [attr (d/attribute db k)]
                               (if (= Attribute/TYPE_REF (:value-type (d/attribute db k)))
                                 [k (invoke (comp dt-id->crux-id :db/id) attr v)]
                                 [k v])))
                           doc)))
              (remove :db.install/partition
                      (remove :db/cardinality
                              (map #(d/pull db '[*] %) (distinct (map #(.e %) data)))))))
      [:crux.tx/cas ::progress
       (when last-t
         {:crux.db/id ::progress
          ::t last-t})
       {:crux.db/id ::progress
        ::t t}])))

(defn convert-next
  [n db log crux-db]
  (let [last-t (::t (crux/entity crux-db ::progress))
        tx-range (d/tx-range log (some-> last-t inc) nil)]
    (map (fn [[{:keys [t]} transaction]] (dt-transaction->crux-tx db t transaction))
         (partition
           2 1
           (cons {:t last-t} (take n tx-range))))))

(comment
  (partition 2 1 (cons {:t nil} (d/tx-range (d/log conn) nil nil)))
  (crux/entity (crux/db system) ::progress)
  (convert-next 100 (d/db conn) (d/log conn) (crux/db system))

  (doseq [tx (convert-next 100 (d/db conn) (d/log conn) (crux/db system))]
    (crux/submit-tx system tx)))

(comment
  (defonce conn (datomic-conn))
  (defonce system (crux-system))

  (do
    @(d/transact conn
                 [{:db/id #db/id [:db.part/db]
                   :db/ident :something/title
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/index true
                   :db.install/_attribute :db.part/db}
                  {:db/id #db/id [:db.part/db]
                   :db/ident :something/favorite-numbers
                   :db/valueType :db.type/long
                   :db/cardinality :db.cardinality/many
                   :db/index false
                   :db.install/_attribute :db.part/db}])

    @(d/transact conn
                 [{:db/id #db/id [:db.part/db]
                   :db/ident :something/else
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/index false}
                  {:db/id #db/id [:db.part/db]
                   :db/ident :something/ref
                   :db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/one
                   :db/index false}]))

  (do
    @(d/transact conn
                 [{:something/title "AAAaaa"
                   :something/favorite-numbers [1 2 6 9]}
                  {:something/title "BBBbbb"
                   :something/favorite-numbers [13 666]}])
    @(d/transact conn
                 [{:something/title "CCCccc"
                   :something/favorite-numbers [666 777]}])
    @(d/transact conn
                 [[:db/add
                   (d/q '{:find [?e .]
                          :where [[?e :something/title "AAAaaa"]]}
                        (d/db conn))
                   :something/favorite-numbers 7878]
                  [:db/add
                   (d/q '{:find [?e .]
                          :where [[?e :something/title "AAAaaa"]]}
                        (d/db conn))
                   :something/else "AAAzalot"]])
    @(d/transact conn
                 [[:db.fn/retractEntity
                   (d/q '{:find [?e .]
                          :where [[?e :something/title "BBBbbb"]]}
                        (d/db conn))]])

    @(d/transact conn
                 [[:db/add
                   (d/q '{:find [?e .]
                          :where [[?e :something/title "AAAaaa"]]}
                        (d/db conn))
                   :something/ref
                   (d/q '{:find [?e .]
                          :where [[?e :something/title "CCCccc"]]}
                        (d/db conn))]]))


  (crux/q (crux/db system)
          '{:find [?e]
            :where [[?e :something/title]]
            :full-results? true})

  (do
    @(d/transact conn
                 [{:something/title "ZZZzzz"}
                  {:something/title "YYYyyy"
                   :something/favorite-numbers [10 10 30]}])
    @(d/transact conn
                 [[:db/add
                   (d/q '{:find [?e .]
                          :where [[?e :something/title "AAAaaa"]]}
                        (d/db conn))
                   :something/ref
                   (d/q '{:find [?e .]
                          :where [[?e :something/title "ZZZzzz"]]}
                        (d/db conn))]]))

  (crux/q (crux/db system)
          '{:find [?e]
            :where [[?e :something/title "ZZZzzz"]]
            :full-results? true}))

(defn subscribe-datomic
  [stream f]
  (let [s (s/batch 100 1 stream)]
    (loop []
      (when-let [_ @(s/take! s)]
        (f)
        (recur)))))

(defn wrap-datomic-queue
  [queue]
  (let [s (s/stream)]
    (s/connect queue s)
    s))

(comment
  (d/tx-report-queue conn)
  (def dt-s (wrap-datomic-queue (d/tx-report-queue conn)))

  (s/description dt-s)

  (seq (d/tx-range (d/log conn) nil nil))
  (crux/entity (crux/db system) ::progress)

  (doto
    (Thread.
      (fn []
        (subscribe-datomic
          dt-s
          (fn []
            (doseq [tx (convert-next 100 (d/db conn) (d/log conn) (crux/db system))]
              (crux/submit-tx system tx))))))
    (.start)))

(comment
  (d/tx-report-queue conn))
