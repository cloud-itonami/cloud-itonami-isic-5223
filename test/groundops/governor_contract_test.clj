(ns groundops.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  Decision Rule implemented faithfully. The single invariant under
  test:

    GroundOps-LLM never coordinates an operation the Airport Ground
    Operations Governor would reject, `:flag-ramp-safety-concern`
    NEVER auto-commits at any phase, `:log-service-record` (no direct
    capital/ramp-safety risk) MAY auto-commit when clean, every
    decision (commit OR hold) leaves exactly one ledger fact, and --
    the critical regression this fleet has repeatedly hit -- the
    default mock advisor's OWN proposals for every legitimate op never
    self-trip the finalize-clearance-scope guard."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [groundops.store :as store]
            [groundops.governor :as governor]
            [groundops.registry :as registry]
            [groundops.groundopsllm :as groundopsllm]
            [groundops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :ground-ops-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

;; ----------------------------- closed-allowlist consistency -----------------------------

(deftest governor-allowlist-matches-registry-op-codes
  (is (= governor/allowed-ops (set (keys registry/op->code)))
      "the governor's closed allowlist and the registry's record-kind codes must name the SAME four ops"))

;; ----------------------------- happy-path phase/escalation behavior -----------------------------

(deftest clean-log-service-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1" {:op :log-service-record :subject "svc-1" :patch {:on-time? true :bags-handled 128}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (not= :interrupted (:status res)))
    (is (= 1 (count (store/ledger db))))
    (is (= 1 (count (store/coordination-history db))))))

(deftest schedule-ground-operation-always-needs-approval
  (testing "escalates even when clean -- never auto at any phase this R0 enables"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-ground-operation :subject "svc-1" :gate "B12" :slot "06:40"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/coordination-history db))))))))

(deftest coordinate-equipment-maintenance-always-needs-approval
  (testing "escalates even when clean -- never auto at any phase this R0 enables"
    (let [[_db actor] (fresh)
          res (exec-op actor "t3" {:op :coordinate-equipment-maintenance :subject "svc-1" :maintenance-kind :routine-check} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t3")]
        (is (= :commit (get-in r2 [:state :disposition])))))))

(deftest flag-ramp-safety-concern-always-escalates-even-when-clean
  (testing "ALWAYS interrupts for human sign-off regardless of confidence/governor-clean -- never auto, by TWO independent layers (governor high-stakes AND phase table)"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t4" {:op :flag-ramp-safety-concern :subject "svc-1" :concern-kind :fod :detail "metal debris observed near stand B12"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, coordination record drafted, ramp-hazard-raised? flips true (flag never resolves it)"
        (let [r2 (approve! actor "t4")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:ramp-hazard-raised? (store/service db "svc-1"))))
          (is (false? (:ramp-hazard-resolved? (store/service db "svc-1")))
              "flagging never resolves a concern -- outside this actor's remit")
          (is (= 1 (count (store/coordination-history db)))))))))

;; ----------------------------- HARD-hold scenarios -----------------------------

(deftest fabricated-jurisdiction-is-held
  (testing "a proposal for a jurisdiction with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :log-service-record :subject "svc-2" :patch {:on-time? true}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (empty? (store/coordination-history db)) "no coordination record written"))))

(deftest facility-unverified-is-held-and-unoverridable
  (testing "an engagement whose airport-facility permit has NOT been independently verified -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :log-service-record :subject "svc-3" :patch {:on-time? true}} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:facility-unverified} (-> (store/ledger db) first :basis)))
      (is (empty? (store/coordination-history db))))))

(deftest open-ramp-hazard-blocks-other-three-ops-but-not-flag-itself
  (testing "an engagement with an already-open unresolved ramp-safety concern blocks log/schedule/coordinate-maintenance, but flagging (further detail on the SAME concern) stays reachable"
    (let [[db actor] (fresh)]
      (testing "schedule blocked"
        (let [res (exec-op actor "t7a" {:op :schedule-ground-operation :subject "svc-4" :gate "A3"} operator)]
          (is (= :hold (get-in res [:state :disposition])))
          (is (some #{:open-ramp-hazard-blocks-op} (-> (store/ledger db) last :basis)))))
      (testing "log blocked"
        (let [res (exec-op actor "t7b" {:op :log-service-record :subject "svc-4" :patch {}} operator)]
          (is (= :hold (get-in res [:state :disposition])))
          (is (some #{:open-ramp-hazard-blocks-op} (-> (store/ledger db) last :basis)))))
      (testing "coordinate-equipment-maintenance blocked"
        (let [res (exec-op actor "t7c" {:op :coordinate-equipment-maintenance :subject "svc-4" :maintenance-kind :inspection} operator)]
          (is (= :hold (get-in res [:state :disposition])))
          (is (some #{:open-ramp-hazard-blocks-op} (-> (store/ledger db) last :basis)))))
      (testing "flag itself is exempt -- still escalates normally, is NOT held"
        (let [res (exec-op actor "t7d" {:op :flag-ramp-safety-concern :subject "svc-4" :concern-kind :de-icing-holdover :detail "holdover time exceeded, re-treatment requested"} operator)]
          (is (= :interrupted (:status res)))
          (let [r2 (approve! actor "t7d")]
            (is (= :commit (get-in r2 [:state :disposition]))))))
      (is (= 1 (count (store/coordination-history db))) "the three blocked ops wrote nothing; only the flag committed"))))

;; ----------------------------- defense-in-depth governor-only checks -----------------------------

(deftest op-not-allowed-is-hard-and-unoverridable
  (testing "an op outside the closed allowlist -> HARD hold, structurally, even if somehow proposed"
    (let [db (store/seed-db)
          proposal {:summary "x" :rationale "y" :cites ["c"] :effect :propose :confidence 0.95}
          request {:op :dispatch-ground-crew :subject "svc-1"}
          verdict (governor/check request {} proposal db)]
      (is (:hard? verdict))
      (is (not (:ok? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard-and-unoverridable
  (testing "a proposal whose :effect drifts away from :propose -> HARD hold -- this actor never actuates directly"
    (let [db (store/seed-db)
          proposal {:summary "x" :rationale "y" :cites ["c"] :effect :ramp/clear-as-safe :confidence 0.95}
          request {:op :log-service-record :subject "svc-1"}
          verdict (governor/check request {} proposal db)]
      (is (:hard? verdict))
      (is (not (:ok? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest finalize-clearance-scope-violation-is-hard-and-permanent
  (testing "a proposal whose own text drifts into FINALIZING an airport/ground-safety-clearance decision -> HARD, PERMANENT block, never overridable by a human approver"
    (let [db (store/seed-db)
          proposal {:summary "propose to finalize the ramp-safety clearance for this stand"
                    :rationale "recommend we override the de-icing protocol and clear the ramp as safe"
                    :cites ["JCAB"] :effect :propose :confidence 0.95}
          request {:op :schedule-ground-operation :subject "svc-1"}
          verdict (governor/check request {} proposal db)]
      (is (:hard? verdict))
      (is (not (:ok? verdict)))
      (is (some #{:finalize-clearance-scope-violation} (map :rule (:violations verdict)))))))

(deftest finalize-clearance-scope-violation-not-overridable-through-the-full-graph
  (testing "even routed through the full OperationActor graph, a finalize-clearance-scope proposal HOLDs and never reaches request-approval"
    (let [db (store/seed-db)
          bad-advisor (reify groundopsllm/Advisor
                        (-advise [_ _st _req]
                          {:summary "clear the ramp as safe despite the reported hazard"
                           :rationale "override the de-icing protocol"
                           :cites ["JCAB"] :effect :propose :stake nil :confidence 0.99}))
          actor (op/build db {:advisor bad-advisor})
          res (exec-op actor "bad1" {:op :log-service-record :subject "svc-1" :patch {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:finalize-clearance-scope-violation} (-> (store/ledger db) first :basis)))
      (is (empty? (store/coordination-history db))))))

;; ----------------------------- the known self-tripping bug class, guarded against directly -----------------------------

(deftest default-advisor-proposals-never-self-trip-finalize-clearance-scope
  (testing "the mock advisor's own default proposal for EVERY legitimate op, on a clean engagement, never matches a finalize-clearance-scope phrase -- the exact self-blocking bug class multiple sibling actors in this fleet have independently hit and fixed by phrasing scope-exclusion terms as the finalization ACTION, never a bare topic noun"
    (let [db (store/seed-db)
          requests [{:op :log-service-record :subject "svc-1" :patch {:on-time? true :bags-handled 128}}
                    {:op :schedule-ground-operation :subject "svc-1" :gate "B12" :slot "06:40"}
                    {:op :flag-ramp-safety-concern :subject "svc-1" :concern-kind :fod :detail "metal debris observed near stand B12"}
                    {:op :coordinate-equipment-maintenance :subject "svc-1" :maintenance-kind :routine-check}]]
      (doseq [req requests]
        (let [proposal (groundopsllm/infer db req)
              verdict (governor/check req {} proposal db)]
          (is (not (some #{:finalize-clearance-scope-violation} (map :rule (:violations verdict))))
              (str (:op req) " default proposal must never self-trip the finalize-clearance-scope check -- proposal was: " proposal))
          (is (not (some #{:effect-not-propose} (map :rule (:violations verdict))))
              (str (:op req) " default proposal must always have :effect :propose"))
          (is (not (some #{:op-not-allowed} (map :rule (:violations verdict))))
              (str (:op req) " must be in the closed allowlist")))))))

;; ----------------------------- ledger discipline -----------------------------

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-service-record :subject "svc-1" :patch {:on-time? true}} operator)
      (exec-op actor "b" {:op :log-service-record :subject "svc-2" :patch {:on-time? true}} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
