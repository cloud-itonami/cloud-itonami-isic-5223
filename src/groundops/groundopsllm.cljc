(ns groundops.groundopsllm
  "GroundOps-LLM client -- the *contained intelligence node* for the
  community-airport-ground-handling-operations actor.

  It drafts service/turnaround-record logging entries (de-icing,
  baggage-handling, ramp-service data), drafts ramp/gate/de-icing
  scheduling coordination proposals, drafts ramp-safety-concern
  escalation flags, and drafts ground-support-equipment maintenance
  coordination proposals. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a real airport/ground-
  safety-clearance decision. Every output is censored downstream by
  `groundops.governor` before anything touches the SSoT.

  SCOPE, stated explicitly and repeatedly because it is the single
  most important invariant this actor has: this advisor NEVER proposes
  to clear a ramp/apron area as safe, NEVER proposes to override a
  de-icing protocol or de-icing-fluid-holdover-time requirement, and
  NEVER proposes to finalize any airport/ground-safety clearance. It
  only proposes ground-handling-COORDINATION records -- logging,
  scheduling, concern-flagging, and equipment-maintenance-coordination
  drafts. `:effect` is ALWAYS `:propose`, never a direct actuation
  effect. See README `Scope`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all four ops):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis AND
                                 ;   finalize-clearance-scope gates
     :cites      [str ..]       ; facts/sources the LLM used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a real actuation
     :stake      kw|nil         ; :ground/flag-ramp-safety-concern | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [groundops.facts :as facts]
            [groundops.store :as store]
            [langchain.model :as model]))

(defn- confidence-for [sv cites]
  (if (and sv (seq cites) (:facility-verified? sv)
           (not (and (:ramp-hazard-raised? sv) (not (:ramp-hazard-resolved? sv)))))
    0.92
    0.2))

(defn- propose-log-service-record
  "Draft a ground-service-record log entry -- de-icing/baggage-
  handling/ramp-service data. Pure data logging, no actuation, the
  lowest-risk op in this domain (the ONLY one this actor's phase table
  ever allows to auto-commit, see `groundops.phase`)."
  [db {:keys [subject patch]}]
  (let [sv (store/service db subject)
        iso3 (:jurisdiction sv)
        cites (facts/citation iso3)]
    {:summary   (str subject " 地上取扱記録(除氷/手荷物取扱/ランプサービス実績)のログ提案"
                     (when (seq patch) (str " patch=" (pr-str (keys patch)))))
     :rationale (str "施設許可検証済み=" (boolean (:facility-verified? sv))
                     " -- 入力patchの正規化のみ、新規事実の生成なし。")
     :cites     (vec cites)
     :effect    :propose
     :value     (merge {:kind :ground-service-record-log} (or patch {}))
     :stake     nil
     :confidence (confidence-for sv cites)}))

(defn- propose-schedule-ground-operation
  "Draft a ramp/gate/de-icing scheduling coordination proposal --
  logistics coordination only, NOT a ramp-clearance or de-icing-
  protocol-override authorization (that remains outside this actor's
  remit, a real airport/ground-safety authority's act)."
  [db {:keys [subject gate slot]}]
  (let [sv (store/service db subject)
        iso3 (:jurisdiction sv)
        cites (facts/citation iso3)]
    {:summary   (str subject " 向けランプ/ゲート/除氷スケジュール調整案"
                     (when gate (str " gate=" gate)) (when slot (str " slot=" slot)))
     :rationale (str "施設許可検証済み=" (boolean (:facility-verified? sv))
                     " -- ゲート/除氷枠の調整提案。ランプ・クリアランス確定や除氷プロトコルの決定権限事項ではない。")
     :cites     (vec cites)
     :effect    :propose
     :value     (cond-> {:kind :ground-operation-schedule} gate (assoc :gate gate) slot (assoc :slot slot))
     :stake     nil
     :confidence (confidence-for sv cites)}))

(defn- propose-flag-ramp-safety-concern
  "Draft a ramp-safety-concern escalation flag -- surfaces a reported
  ramp-hazard/FOD (foreign-object-debris)/de-icing-fluid-holdover
  concern to a human. ALWAYS `:stake :ground/flag-ramp-safety-concern`
  -- ALWAYS escalates to human sign-off, at every phase, regardless of
  confidence or governor cleanliness (`groundops.governor`'s high-
  stakes gate AND `groundops.phase`'s phase table, which never adds
  this op to any phase's `:auto` set, independently agree). This
  advisor explicitly does NOT determine whether the ramp is safe to
  resume operations -- it only proposes that the concern be recorded
  and routed to a human."
  [db {:keys [subject concern-kind detail]}]
  (let [sv (store/service db subject)
        iso3 (:jurisdiction sv)
        cites (facts/citation iso3)]
    {:summary   (str subject " のランプ安全上の懸念(" (or concern-kind "unspecified") ")を報告"
                     (when detail (str " -- " detail)))
     :rationale (str "報告された懸念(滑走路異物混入の可能性や除氷液の保持時間超過など)を地上安全部門へ"
                     "エスカレートするための記録提案。本アクターはこの懸念についてランプの安全性そのものの"
                     "判断は行わない -- 常に人間(地上安全部門)の承認へ回付する。")
     :cites     (vec cites)
     :effect    :propose
     :value     (cond-> {:kind :ramp-safety-concern-flag}
                  concern-kind (assoc :concern-kind concern-kind)
                  detail (assoc :detail detail))
     :stake     :ground/flag-ramp-safety-concern
     :confidence 0.85}))

(defn- propose-coordinate-equipment-maintenance
  "Draft a ground-support-equipment maintenance COORDINATION proposal
  -- scheduling a maintenance slot, requesting parts, or dispatching a
  technician for tugs/loaders/de-icing rigs. This is NOT an equipment
  RELEASE / return-to-service sign-off (that remains outside this
  actor's remit, a real certifying-authority act -- see README
  `Scope`)."
  [db {:keys [subject maintenance-kind]}]
  (let [sv (store/service db subject)
        iso3 (:jurisdiction sv)
        cites (facts/citation iso3)]
    {:summary   (str subject " 向け地上支援機材整備調整案(" (or maintenance-kind "unspecified") ")")
     :rationale (str "施設許可検証済み=" (boolean (:facility-verified? sv))
                     " -- 整備スケジュール調整の提案。整備完了後の機材復帰承認(リリース)ではない。")
     :cites     (vec cites)
     :effect    :propose
     :value     (cond-> {:kind :equipment-maintenance-coordination} maintenance-kind (assoc :maintenance-kind maintenance-kind))
     :stake     nil
     :confidence (confidence-for sv cites)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-service-record               (propose-log-service-record db request)
    :schedule-ground-operation        (propose-schedule-ground-operation db request)
    :flag-ramp-safety-concern         (propose-flag-ramp-safety-concern db request)
    :coordinate-equipment-maintenance (propose-coordinate-equipment-maintenance db request)
    {:summary "未対応の操作 -- 閉じたground-handling-operations-coordination許可リストの範囲外"
     :rationale (str op) :cites [] :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域空港地上取扱事業者の運航コーディネーション・エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に :propose) "
       ":stake(:ground/flag-ramp-safety-concern か nil) :confidence(0..1)。\n"
       "重要: あなたはランプ・クリアランスの確定・除氷プロトコルの上書き・機材整備リリース承認を"
       "一切行いません。登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "施設許可検証状況やランプ安全上の懸念の有無を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [subject]}]
  {:service (store/service st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Airport Ground Operations
  Governor escalates/holds -- an LLM hiccup can never auto-commit
  anything, and can certainly never finalize an airport/ground-safety
  clearance decision."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :groundopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
