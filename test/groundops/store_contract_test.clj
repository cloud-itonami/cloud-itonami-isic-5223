(ns groundops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [groundops.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/service s "svc-1"))))
      (is (true? (:facility-verified? (store/service s "svc-1"))))
      (is (false? (:ramp-hazard-raised? (store/service s "svc-1"))))
      (is (false? (:facility-verified? (store/service s "svc-3"))))
      (is (true? (:ramp-hazard-raised? (store/service s "svc-4"))))
      (is (false? (:ramp-hazard-resolved? (store/service s "svc-4"))))
      (is (= ["svc-1" "svc-2" "svc-3" "svc-4" "svc-5"]
             (mapv :id (store/all-services s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/coordination-history s)))
      (is (zero? (store/next-sequence s "JPN" :log-service-record))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "a :propose commit drafts a coordination record and advances the per-jurisdiction+op sequence"
        (store/commit-record! s {:effect :propose :op :log-service-record :path ["svc-1"]})
        (is (= "JPN-LOG-000000" (get (first (store/coordination-history s)) "record_id")))
        (is (= "ground-service-record-log-draft" (get (first (store/coordination-history s)) "kind")))
        (is (= 1 (count (store/coordination-history s))))
        (is (= 1 (store/next-sequence s "JPN" :log-service-record)))
        (is (false? (:ramp-hazard-raised? (store/service s "svc-1")))
            "log-service-record never touches ramp-hazard ground truth"))
      (testing "flag-ramp-safety-concern commit sets ramp-hazard-raised? true but never resolved?"
        (store/commit-record! s {:effect :propose :op :flag-ramp-safety-concern :path ["svc-1"]})
        (is (= "JPN-RSC-000000" (get (first (filter #(= "ramp-safety-concern-flag-draft" (get % "kind")) (store/coordination-history s))) "record_id")))
        (is (true? (:ramp-hazard-raised? (store/service s "svc-1"))))
        (is (false? (:ramp-hazard-resolved? (store/service s "svc-1")))))
      (testing "sequences are independent per jurisdiction+op pair"
        (store/commit-record! s {:effect :propose :op :log-service-record :path ["svc-1"]})
        (is (= 2 (store/next-sequence s "JPN" :log-service-record)))
        (is (= 1 (store/next-sequence s "JPN" :flag-ramp-safety-concern))))
      (testing "a non-:propose effect is never committed"
        (let [before (count (store/coordination-history s))]
          (store/commit-record! s {:effect :ramp/clear-as-safe :op :log-service-record :path ["svc-1"]})
          (is (= before (count (store/coordination-history s))) "governor should never let this reach the store, but the store itself is also defensive")))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/service s "nope")))
    (is (= [] (store/all-services s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/coordination-history s)))
    (is (zero? (store/next-sequence s "JPN" :log-service-record)))
    (store/with-services s {"x" {:id "x" :facility "ZZZ" :handler "c"
                                :facility-verified? true
                                :ramp-hazard-raised? false :ramp-hazard-resolved? false
                                :jurisdiction "JPN" :status :active}})
    (is (= "c" (:handler (store/service s "x"))))))
