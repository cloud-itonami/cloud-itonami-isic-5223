(ns groundops.store
  "SSoT for the community-airport-ground-handling-operations actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite --
  the same seam every prior `cloud-itonami-isic-*` actor in this fleet
  uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/groundops/store_contract_test.clj), which is the whole point:
  the actor, the Airport Ground Operations Governor and the audit
  ledger never know which SSoT they run on.

  `:facility-verified?` is GROUND TRUTH this actor CONSUMES, never
  MINTS -- it represents the engagement's own airport-facility permit
  and ground-handling-operator-license record, independently verified
  and registered by a real airport operator / civil aviation authority
  (or this actor's operator, standing in for one, in a real deployment)
  OUTSIDE this actor's own closed op-allowlist. None of `:log-service-
  record`/`:schedule-ground-operation`/`:flag-ramp-safety-concern`/
  `:coordinate-equipment-maintenance` ever sets it -- see README
  `Scope`.

  `:ramp-hazard-raised?`/`:ramp-hazard-resolved?` are dedicated
  booleans (never a single `:status` value), mirroring the SAME
  discipline every prior sibling governor's guards establish: a
  flagged, unresolved ramp-safety hazard blocks the OTHER three ops on
  that engagement (`groundops.governor`'s open-ramp-hazard check) --
  but resolving a hazard is likewise OUTSIDE this actor's own op-
  allowlist (a real airport ground-safety authority's call, not this
  actor's -- see the CRITICAL invariant: this actor never finalizes a
  ramp/ground-safety clearance)."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [groundops.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (service [s id])
  (all-services [s])
  (ledger [s])
  (coordination-history [s] "the append-only ground-handling-coordination history (groundops.registry drafts, all four op kinds)")
  (next-sequence [s jurisdiction op] "next record-id sequence for a jurisdiction+op pair")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-services [s services] "replace/seed the ground-service directory (map id->service)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained ground-handling-engagement set covering the
  clean path plus the governor's own HARD checks, so the actor + tests
  run offline."
  []
  {:services
   {"svc-1" {:id "svc-1" :facility "HND" :handler "Local Ground Services Co"
             :facility-verified? true
             :ramp-hazard-raised? false :ramp-hazard-resolved? false
             :jurisdiction "JPN" :status :active}
    "svc-2" {:id "svc-2" :facility "Atlantis Intl" :handler "Atlantis Ground Handling"
             :facility-verified? true
             :ramp-hazard-raised? false :ramp-hazard-resolved? false
             :jurisdiction "ATL" :status :active}
    "svc-3" {:id "svc-3" :facility "CTS" :handler "Local Ground Services Co"
             :facility-verified? false
             :ramp-hazard-raised? false :ramp-hazard-resolved? false
             :jurisdiction "JPN" :status :active}
    "svc-4" {:id "svc-4" :facility "FUK" :handler "Local Ground Services Co"
             :facility-verified? true
             :ramp-hazard-raised? true :ramp-hazard-resolved? false
             :jurisdiction "JPN" :status :active}
    "svc-5" {:id "svc-5" :facility "SFO" :handler "US Regional Ground Services"
             :facility-verified? true
             :ramp-hazard-raised? false :ramp-hazard-resolved? false
             :jurisdiction "USA" :status :active}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- draft-coordination-record!
  "Backend-agnostic coordination-record draft -- looks up the
  ground-service via the protocol and drafts the record for `op`,
  returns {:result .. :service-patch ..} for the caller to persist.
  `:service-patch` is ALWAYS empty except for `:flag-ramp-safety-
  concern`, which sets `:ramp-hazard-raised? true` -- flagging is the
  only one of the four ops that changes ramp-safety-relevant ground
  truth, and even then it never sets `:ramp-hazard-resolved?`
  (resolving a hazard, and finalizing any ramp/ground-safety clearance,
  is outside this actor's remit)."
  [s op service-id]
  (let [sv (service s service-id)
        seq-n (next-sequence s (:jurisdiction sv) op)
        result (registry/register-coordination-record op service-id (:jurisdiction sv) seq-n)]
    {:result result
     :service-patch (if (= op :flag-ramp-safety-concern)
                       {:ramp-hazard-raised? true}
                       {})}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (service [_ id] (get-in @a [:services id]))
  (all-services [_] (sort-by :id (vals (:services @a))))
  (ledger [_] (:ledger @a))
  (coordination-history [_] (:coordination-history @a))
  (next-sequence [_ jurisdiction op] (get-in @a [:sequences [jurisdiction op]] 0))
  (commit-record! [s {:keys [effect op path]}]
    (when (= :propose effect)
      (let [service-id (first path)
            {:keys [result service-patch]} (draft-coordination-record! s op service-id)
            jurisdiction (:jurisdiction (service s service-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences [jurisdiction op]] (fnil inc 0))
                       (cond-> (seq service-patch) (update-in [:services service-id] merge service-patch))
                       (update :coordination-history registry/append result))))
        result))
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-services [s services] (when (seq services) (swap! a assoc :services services)) s))

(defn seed-db
  "A MemStore seeded with the demo ground-service set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :sequences {} :coordination-history []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (ledger facts, coordination records) are stored
  as EDN strings so `langchain.db` doesn't expand them into
  sub-entities -- the same convention every sibling actor's store
  uses."
  {:service/id               {:db/unique :db.unique/identity}
   :ledger/seq               {:db/unique :db.unique/identity}
   :coordination/seq         {:db/unique :db.unique/identity}
   :sequence/key             {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- service->tx [{:keys [id facility handler
                            facility-verified? ramp-hazard-raised? ramp-hazard-resolved?
                            jurisdiction status]}]
  (cond-> {:service/id id}
    facility                                                  (assoc :service/facility facility)
    handler                                                     (assoc :service/handler handler)
    (some? facility-verified?)                                   (assoc :service/facility-verified? facility-verified?)
    (some? ramp-hazard-raised?)                                    (assoc :service/ramp-hazard-raised? ramp-hazard-raised?)
    (some? ramp-hazard-resolved?)                                    (assoc :service/ramp-hazard-resolved? ramp-hazard-resolved?)
    jurisdiction                                                       (assoc :service/jurisdiction jurisdiction)
    status                                                                 (assoc :service/status status)))

(def ^:private service-pull
  [:service/id :service/facility :service/handler
   :service/facility-verified? :service/ramp-hazard-raised?
   :service/ramp-hazard-resolved? :service/jurisdiction :service/status])

(defn- pull->service [m]
  (when (:service/id m)
    {:id (:service/id m) :facility (:service/facility m) :handler (:service/handler m)
     :facility-verified? (boolean (:service/facility-verified? m))
     :ramp-hazard-raised? (boolean (:service/ramp-hazard-raised? m))
     :ramp-hazard-resolved? (boolean (:service/ramp-hazard-resolved? m))
     :jurisdiction (:service/jurisdiction m) :status (:service/status m)}))

(defrecord DatomicStore [conn]
  Store
  (service [_ id]
    (pull->service (d/pull (d/db conn) service-pull [:service/id id])))
  (all-services [_]
    (->> (d/q '[:find [?id ...] :where [?e :service/id ?id]] (d/db conn))
         (map #(pull->service (d/pull (d/db conn) service-pull [:service/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (coordination-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :coordination/seq ?s] [?e :coordination/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction op]
    (or (d/q '[:find ?n . :in $ ?k
              :where [?e :sequence/key ?k] [?e :sequence/next ?n]]
            (d/db conn) (pr-str [jurisdiction op]))
        0))
  (commit-record! [s {:keys [effect op path]}]
    (when (= :propose effect)
      (let [service-id (first path)
            {:keys [result service-patch]} (draft-coordination-record! s op service-id)
            jurisdiction (:jurisdiction (service s service-id))
            key-str (pr-str [jurisdiction op])
            next-n (inc (next-sequence s jurisdiction op))]
        (d/transact! conn
                     (cond-> [{:sequence/key key-str :sequence/next next-n}
                              {:coordination/seq (count (coordination-history s)) :coordination/record (enc (get result "record"))}]
                       (seq service-patch) (conj (service->tx (assoc service-patch :id service-id)))))
        result))
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-services [s services]
    (when (seq services) (d/transact! conn (mapv service->tx (vals services)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:services ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [services]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-services s services))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo ground-service set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
