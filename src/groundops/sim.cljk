(ns groundops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks svc-1 through a clean
  ground-handling-coordination lifecycle (log -> schedule -> coordinate
  equipment maintenance -> flag a ramp-safety concern), then shows what
  flagging the concern does to the SAME engagement (blocks further
  logging), then walks through the remaining HARD-hold scenarios: an
  unknown jurisdiction with no spec-basis, a facility whose permit has
  not been independently verified, and an engagement that already has
  an unresolved ramp-safety concern on file (which still allows
  flagging, just not the other three ops).

  Like every sibling actor's own new checks, this actor's checks
  (`facility-unverified?`, `open-ramp-hazard-blocks-op?`, `finalize-
  clearance-scope-violation?`) are evaluated directly at proposal time
  rather than via a separate screening op -- following the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline this fleet establishes."
  (:require [langgraph.graph :as g]
            [groundops.store :as store]
            [groundops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :ground-ops-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-service-record svc-1 (JPN, clean -- auto-commits, no capital/safety risk) ==")
    (println (exec-op actor "t1" {:op :log-service-record :subject "svc-1"
                                  :patch {:on-time? true :bags-handled 128}} operator))

    (println "== schedule-ground-operation svc-1 (escalates -- human approves) ==")
    (let [r (exec-op actor "t2" {:op :schedule-ground-operation :subject "svc-1" :gate "B12" :slot "06:40"} operator)]
      (println r)
      (println (approve! actor "t2")))

    (println "== coordinate-equipment-maintenance svc-1 (escalates -- human approves) ==")
    (let [r (exec-op actor "t3" {:op :coordinate-equipment-maintenance :subject "svc-1" :maintenance-kind :routine-check} operator)]
      (println r)
      (println (approve! actor "t3")))

    (println "== flag-ramp-safety-concern svc-1 (ALWAYS escalates -- human approves) ==")
    (let [r (exec-op actor "t4" {:op :flag-ramp-safety-concern :subject "svc-1"
                                 :concern-kind :fod :detail "metal debris observed near stand B12"} operator)]
      (println r)
      (println "-- human ground-safety coordinator approves the FLAG (not a ramp-clearance determination) --")
      (println (approve! actor "t4")))

    (println "== log-service-record svc-1 AGAIN (now blocked -- open unresolved ramp-safety concern -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :log-service-record :subject "svc-1" :patch {:on-time? false}} operator))

    (println "== log-service-record svc-2 (ATL, no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-service-record :subject "svc-2" :patch {:on-time? true}} operator))

    (println "== log-service-record svc-3 (JPN, facility permit NOT independently verified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-service-record :subject "svc-3" :patch {:on-time? true}} operator))

    (println "== schedule-ground-operation svc-4 (JPN, already has an open unresolved ramp-safety concern -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :schedule-ground-operation :subject "svc-4" :gate "A3"} operator))

    (println "== flag-ramp-safety-concern svc-4 (still reachable even with an open concern -- escalates -- human approves) ==")
    (let [r (exec-op actor "t9" {:op :flag-ramp-safety-concern :subject "svc-4"
                                 :concern-kind :de-icing-holdover :detail "holdover time exceeded, re-treatment requested"} operator)]
      (println r)
      (println (approve! actor "t9")))

    (println "== coordinate-equipment-maintenance svc-5 (USA, clean -- escalates -- human approves) ==")
    (let [r (exec-op actor "t10" {:op :coordinate-equipment-maintenance :subject "svc-5" :maintenance-kind :parts-request} operator)]
      (println r)
      (println (approve! actor "t10")))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft ground-handling-coordination records ==")
    (doseq [r (store/coordination-history db)] (println r))))
