(ns groundops.registry
  "Pure-function ground-handling-coordination record construction -- an
  append-only draft log for the four operations this actor coordinates:
  service-record logging, ground-operation scheduling, ramp-safety-
  concern flagging, and ground-support-equipment maintenance
  coordination.

  CRITICAL SCOPE NOTE: every record this namespace builds is a
  COORDINATION DRAFT, never a ramp/ground-safety-clearance decision.
  This actor does not clear a ramp area as safe, does not override a
  de-icing protocol, and does not finalize any airport/ground-safety
  clearance -- those are always either a hard, permanent governor
  block (`groundops.governor`'s finalize-clearance-scope check) or
  entirely outside this actor's closed op-allowlist. See README
  `Scope`.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real airport-operations system. It builds the RECORD a
  ground-operations coordinator would keep, not a real-world ramp-
  safety act itself."
  (:require [clojure.string :as str]))

(def op->code
  "op -> short record-kind code used in the record id, matching this
  actor's closed op-allowlist exactly."
  {:log-service-record             "LOG"
   :schedule-ground-operation      "SCH"
   :flag-ramp-safety-concern       "RSC"
   :coordinate-equipment-maintenance "MNT"})

(def op->kind
  "op -> the `:kind` tag stored on the committed record."
  {:log-service-record             "ground-service-record-log-draft"
   :schedule-ground-operation      "ground-operation-schedule-draft"
   :flag-ramp-safety-concern       "ramp-safety-concern-flag-draft"
   :coordinate-equipment-maintenance "equipment-maintenance-coordination-draft"})

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's/authority's act, not this actor's. This actor never
  issues a ramp-safety-clearance or facility-permit credential; it only
  drafts a coordination record. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-coordination-record
  "Validate + construct a ground-handling-coordination DRAFT record for
  `op` (one of `groundops.governor/allowed-ops`) -- pure function, does
  not touch any real airport-operations system; it builds the RECORD a
  ground-operations coordinator would keep. `groundops.governor`
  independently re-verifies the engagement's own facility-verified
  ground truth and open-ramp-hazard status before this is ever allowed
  to commit."
  [op service-id jurisdiction sequence]
  (when-not (contains? op->code op)
    (throw (ex-info "register-coordination-record: op must be in the closed allowlist" {:op op})))
  (when-not (and service-id (not= service-id ""))
    (throw (ex-info "register-coordination-record: service_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "register-coordination-record: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "register-coordination-record: sequence must be >= 0" {})))
  (let [code (op->code op)
        kind (op->kind op)
        record-id (str (str/upper-case jurisdiction) "-" code "-" (zero-pad sequence 6))
        record {"record_id" record-id
                "kind" kind
                "op" (name op)
                "service_id" service-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "record_id" record-id
     "certificate" (unsigned-certificate kind record-id record-id)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
