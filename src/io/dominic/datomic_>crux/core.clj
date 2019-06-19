(ns io.dominic.datomic->crux.core
  (:require
    [datomic.api :as d]
    [crux.api :as crux]
    [manifold.stream :as s])
  (:import
    [datomic Attribute]))

(defn- dt-id->crux-id
  [uri eid]
  (java.net.URI. (str uri "/" eid)))

(defn- progress-id
  [uri]
  (dt-id->crux-id uri "io.dominic.datomic--crux.core/progress"))

(comment
  (progress-id (uri conn)))

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

(defn- invoke
  "Invoke a function against a datomic attribute, distinguishing on it's cardinality."
  [f attr v]
  (if (= Attribute/CARDINALITY_MANY (.cardinality attr))
    (map f v)
    (f v)))

(defn- dt-transaction->crux-tx
  "Convert a single datomic transaction to a crux transaction"
  [uri latest-db last-t transaction]
  (let [{:keys [t data]} transaction
        db (d/as-of latest-db t)
        tx (d/t->tx t)
        dt-txInstant (:db/txInstant (d/pull db [:db/txInstant] tx))]
    (conj
      (mapv (fn [doc]
              (let [crux-id (dt-id->crux-id uri (:db/id doc))]
                (if (seq (dissoc doc :db/id))
                  [:crux.tx/put crux-id (assoc doc :crux.db/id crux-id) dt-txInstant]
                  [:crux.tx/delete crux-id dt-txInstant])))
            (map
              (fn [doc]
                (into {}
                      (map (fn [[k v]]
                             (let [attr (d/attribute db k)]
                               (if (= Attribute/TYPE_REF (:value-type (d/attribute db k)))
                                 [k (invoke #(dt-id->crux-id uri (:db/id %)) attr v)]
                                 [k v])))
                           doc)))
              (remove :db.install/partition
                      (remove :db/cardinality
                              (map #(d/pull db '[*] %) (distinct (map #(.e %) data)))))))
      [:crux.tx/cas (progress-id uri)
       (when last-t
         {:crux.db/id (progress-id uri)
          ::t last-t})
       {:crux.db/id (progress-id uri)
        ::t t}])))

(defn- convert-next
  "Convert next n elements from the datomic db and log, preventing overlap with
  the existing progress in crux-db"
  [n uri db log crux-db]
  (let [last-t (::t (crux/entity crux-db (progress-id uri)))
        tx-range (d/tx-range log (some-> last-t inc) nil)]
    (map (fn [[{:keys [t]} transaction]] (dt-transaction->crux-tx uri db t transaction))
         (partition
           2 1
           (cons {:t last-t} (take n tx-range))))))

(comment
  (crux/entity (crux/db system) (progress-id (uri conn)))
  (dev/print-crux-txs (convert-next 100 (uri conn) (d/db conn) (d/log conn) (crux/db system)))

  (doseq [tx (convert-next 100 (uri conn) (d/db conn) (d/log conn) (crux/db system))]
    (crux/submit-tx system tx)))

(comment

  (do
    (.close system)

    (do
      (require '[clojure.java.shell :as sh])
      (sh/sh "rm" "-rf" "event" "db")
      (require 'dev)
      (def conn (dev/datomic-conn))
      (def system (dev/crux-system))))

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

(defn- subscribe-stream
  "Read from stream, and call f whenever something is written to it.  Will
  perform basic throttling."
  [stream f]
  (let [s (s/batch 100 1 stream)]
    (loop []
      (when-let [_ @(s/take! s)]
        (f)
        (recur)))))

(defn wrap-datomic-queue
  "Wrap a Datomic tx-report-queue into a manifold stream"
  [queue]
  (let [s (s/stream)]
    (s/connect queue s)
    s))

(defprotocol DatomicConnInternals
  (uri [conn]))

(extend-type datomic.peer.LocalConnection
  DatomicConnInternals
  (uri [this]
    (str "datomic:mem://" (.-dbname this))))

(defn subscribe-datomic
  "Subscribe to manifold stream of the datomic tx-report-queue, on
  transactions, will write the new transactions to Crux."
  ([stream conn system]
   (subscribe-datomic stream (uri conn) conn system))
  ([stream uri conn system]
   (subscribe-stream
     stream
     (fn []
       (doseq [tx (convert-next 100 uri (d/db conn) (d/log conn) (crux/db system))]
         (crux/submit-tx system tx))))))

(defn subscribe-datomic-bg
  "Like subscribe-datomic but operates in a thread.  Thread will close when the
  stream closes."
  [& args]
  (doto
    (Thread.
      (fn []
        (apply subscribe-datomic args)))
    (.start)))

(comment
  (d/tx-report-queue conn)
  (def dt-s (wrap-datomic-queue (d/tx-report-queue conn)))

  (s/description dt-s)

  (s/close! dt-s)

  (seq (d/tx-range (d/log conn) nil nil))
  (crux/entity (crux/db system) ::progress)

  (subscribe-datomic-bg dt-s conn system)

  (doto
    (Thread.
      (fn []
        (subscribe-datomic dt-s conn system)))
    (.start)))
