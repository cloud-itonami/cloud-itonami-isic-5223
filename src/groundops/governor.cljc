(ns groundops.governor
  "Airport Ground Operations Governor -- the independent compliance
  layer that earns GroundOps-LLM the right to commit. The LLM has no
  notion of whether a jurisdiction's airport/ground-handling authority
  law is official, whether an engagement's own airport-facility-permit
  / ground-handling-operator-license record has actually been
  independently verified and registered, whether an open ramp-safety
  concern is still unresolved on an engagement, or when a drafted
  proposal has drifted into actually FINALIZING an airport/ground-
  safety-clearance decision rather than merely coordinating around
  one, so this MUST be a separate system able to *reject* a proposal
  and fall back to HOLD.

  SCOPE, the single invariant this governor exists to enforce: this
  actor is an airport-ground-handling OPERATIONS COORDINATION actor,
  NOT direct ramp-safety authority and NOT ground-equipment control.
  It never clears a ramp/apron area as safe, never overrides a
  de-icing protocol or de-icing-fluid-holdover-time requirement, and
  never signs off any airport/ground-safety clearance. Those acts are
  always either (a) structurally absent from this actor's closed
  op-allowlist, or (b) an explicit HARD, PERMANENT, un-overridable
  block if a proposal's own text drifts toward one anyway
  (`finalize-clearance-scope-violations` below) -- defense in depth,
  not merely 'the op list doesn't include it'.

  KNOWN BUG CLASS THIS GOVERNOR DELIBERATELY AVOIDS: a scope-exclusion
  term list phrased as a bare noun (e.g. \"safety\", \"ramp\",
  \"de-icing\") will accidentally match inside this actor's OWN mock
  advisor's default rationale text for a legitimate, allowed proposal
  -- `groundops.groundopsllm/propose-flag-ramp-safety-concern` itself
  legitimately talks about ramp hazards, FOD and de-icing-fluid
  holdover time as the CONTENT of a concern being flagged, and
  `no-spec-basis`-style hard holds would then fire on the actor's own
  happy path. This governor's `finalize-clearance-phrases` are
  therefore phrased as the FINALIZATION/EXECUTION ACTION itself
  (\"finalize the ramp-safety clearance\", \"clear the ramp as safe\",
  \"override the de-icing protocol\") rather than the bare topic noun
  -- see `test/groundops/governor_contract_test.clj`'s
  `default-advisor-proposals-never-self-trip-finalize-clearance-scope`
  test, which asserts this directly against every default proposal for
  all four ops.

  Five checks, ALL HARD violations: a human approver CANNOT override
  them. The confidence/high-stakes gate is SOFT: it asks a human to
  look (low confidence, or `:flag-ramp-safety-concern`'s dedicated
  stake) -- but see `groundops.phase`: `:flag-ramp-safety-concern` is
  NEVER a member of any phase's `:auto` set either. Two independent
  layers agree that flagging a ramp-safety concern always reaches a
  human.

    1. Op not allowed             -- the closed allowlist
                                      (`allowed-ops`) is the ONLY
                                      vocabulary this actor has.
                                      Evaluated UNCONDITIONALLY.
    2. Effect not :propose        -- this actor NEVER actuates
                                      directly; every committed record
                                      is a coordination draft, never a
                                      real-world act. Evaluated
                                      UNCONDITIONALLY.
    3. Finalize-clearance scope   -- the proposal's own summary/
                                      rationale/cites text must not
                                      contain an airport/ground-safety-
                                      clearance FINALIZATION action
                                      phrase. A HARD, PERMANENT block --
                                      never overridable by a human
                                      approver, because a human should
                                      never be offered the choice to
                                      approve a proposal that has
                                      drifted into finalizing a ramp
                                      clearance or overriding a
                                      de-icing protocol.
    4. Facility unverified        -- INDEPENDENTLY verify the
                                      engagement's own `:facility-
                                      verified?` ground truth is true
                                      before ANY of the four ops may
                                      proceed. Evaluated
                                      UNCONDITIONALLY.
    5. Open ramp-hazard blocks    -- an unresolved ramp-safety concern
                                      already on file (`:ramp-hazard-
                                      raised? true` AND `:ramp-hazard-
                                      resolved? false`) blocks
                                      `:log-service-record`/`:schedule-
                                      ground-operation`/`:coordinate-
                                      equipment-maintenance` on that
                                      engagement (NOT `:flag-ramp-
                                      safety-concern` itself, which must
                                      always remain reachable to report
                                      further detail on an already-open
                                      concern).

  A sixth check, no-spec-basis, is evaluated UNCONDITIONALLY across all
  four ops: every coordination proposal must cite an official airport/
  ground-handling authority source for the engagement's own
  jurisdiction (`groundops.facts`), never an invented one."
  (:require [clojure.string :as str]
            [groundops.facts :as facts]
            [groundops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed ground-handling-operations-coordination op-allowlist.
  This actor has NO other vocabulary -- a proposal naming any other op
  is a HARD violation, not merely unrecognized."
  #{:log-service-record :schedule-ground-operation
    :flag-ramp-safety-concern :coordinate-equipment-maintenance})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Flagging a ramp-safety concern is the one op in this domain that
  always escalates -- a single-member set, the SAME structural shape
  every sibling's own always-escalate stake establishes."
  #{:ground/flag-ramp-safety-concern})

(def finalize-clearance-phrases
  "Airport/ground-safety-clearance FINALIZATION/EXECUTION action
  phrases -- phrased as the ACT of deciding/executing, never a bare
  topic noun (\"safety\"/\"ramp\"/\"de-icing\" alone are NOT here --
  see the ns docstring's KNOWN BUG CLASS note). Any proposal whose
  summary/rationale/cites text contains one of these
  (case-insensitive) is a HARD, PERMANENT, un-overridable block."
  #{"finalize the ramp-safety clearance"
    "finalize the ramp safety clearance"
    "finalize the ground-safety clearance"
    "clear the ramp as safe"
    "clear the apron as safe"
    "issue the ramp-safety clearance"
    "sign off the ramp as safe for operations"
    "override the de-icing protocol"
    "override the de-icing holdover time"
    "waive the de-icing holdover time"
    "bypass the de-icing holdover time"
    "approve the ramp for operations despite the reported hazard"
    "clear the reported hazard as resolved without inspection"})

;; ----------------------------- checks -----------------------------

(defn- op-not-allowed-violations
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " は閉じたground-handling-operations-coordination許可リストの範囲外 -- このアクターは実行しない")}]))

(defn- effect-not-propose-violations
  [_request proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "effect=" (:effect proposal) " は :propose ではない -- このアクターは直接actuateしない")}]))

(defn- finalize-clearance-scope-violations
  "The proposal's own text drifting toward FINALIZING an airport/
  ground-safety-clearance decision is a HARD, PERMANENT block -- see
  ns docstring."
  [_request proposal]
  (let [text (str/lower-case (str (:summary proposal) " " (:rationale proposal) " " (pr-str (:cites proposal))))]
    (when (some #(str/includes? text %) finalize-clearance-phrases)
      [{:rule :finalize-clearance-scope-violation
        :detail "提案テキストが空港/地上安全当局の最終判断/実行行為(ランプ・クリアランス確定、除氷プロトコル無視等)に該当する -- 恒久的にブロック、人間承認でも解除不可"}])))

(defn- no-spec-basis-violations
  "Every coordination proposal must cite an official airport/ground-
  handling authority source for the engagement's own jurisdiction --
  never invent one. Evaluated UNCONDITIONALLY across all four ops."
  [{:keys [subject]} st proposal]
  (let [sv (store/service st subject)
        iso3 (:jurisdiction sv)]
    (when (or (empty? (:cites proposal)) (not (facts/known-jurisdiction? iso3)))
      [{:rule :no-spec-basis
        :detail (str iso3 " の公式spec-basisの引用が無い提案は法域要件として扱えない")}])))

(defn- facility-unverified-violations
  "INDEPENDENTLY verify the engagement's own `:facility-verified?`
  ground truth is true before ANY of the four ops may proceed.
  Evaluated UNCONDITIONALLY."
  [{:keys [subject]} st]
  (let [sv (store/service st subject)]
    (when-not (true? (:facility-verified? sv))
      [{:rule :facility-unverified
        :detail (str subject " は独立検証済みの空港施設許可/地上取扱業許可記録が無い -- いかなる提案も進められない")}])))

(defn- open-ramp-hazard-violations
  "An unresolved ramp-safety concern already on file blocks the OTHER
  three ops on that engagement -- `:flag-ramp-safety-concern` itself
  is exempt so the safety-reporting channel always stays open."
  [{:keys [op subject]} st]
  (when (not= op :flag-ramp-safety-concern)
    (let [sv (store/service st subject)]
      (when (and (true? (:ramp-hazard-raised? sv)) (not (true? (:ramp-hazard-resolved? sv))))
        [{:rule :open-ramp-hazard-blocks-op
          :detail (str subject " は未解決のランプ安全上の懸念がある -- flag以外の提案は進められない")}]))))

(defn check
  "Censors a GroundOps-LLM proposal against the governor rules. Returns
  {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (finalize-clearance-scope-violations request proposal)
                           (no-spec-basis-violations request st proposal)
                           (facility-unverified-violations request st)
                           (open-ramp-hazard-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
