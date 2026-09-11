(ns groundops.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-ramp-safety-concern` must NEVER be a member of
  any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [groundops.phase :as phase]))

(deftest flag-ramp-safety-concern-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a ramp-safety-concern flag"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-ramp-safety-concern))
          (str "phase " n " must not auto-commit :flag-ramp-safety-concern")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-service-record carries no direct capital/ramp-safety risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-service-record} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-service-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-ground-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-equipment-maintenance} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-ramp-safety-concern} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-service-record} :commit)))))

(deftest gate-auto-commits-the-one-eligible-op-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-service-record} :commit)))))
