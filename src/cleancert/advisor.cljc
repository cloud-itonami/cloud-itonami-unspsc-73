(ns cleancert.advisor
  "CleaningAdvisor -- the *contained intelligence node* for the
  industrial-cleaning-and-certification back-office coordination actor.

  It normalizes cleaning-job-completion patches (method/duration/
  chemicals-used), drafts a certification-scan decision from a robot's
  post-clean residue-ppm sensor reading, drafts a safety-concern flag,
  and drafts a re-clean scheduling proposal against a piece of
  equipment. CRITICAL: it is a smart-but-untrusted advisor. It returns
  a *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a real wash/spray/dispense-equipment
  actuation or a signed certification mark -- see README `What this
  actor does NOT do`. Every output is censored downstream by
  `cleancert.governor` before anything touches the SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `cleancert.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:cleaning-job/upsert
                                 ; :certification/decide
                                 ; :safety-concern/flag
                                 ; :recleaning/schedule} propose-shaped
                                 ; effects, NEVER a direct
                                 ; wash/spray/dispense-equipment-control
                                 ; effect
     :stake      kw|nil         ; :coordination/safety-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `cleancert.governor`
  HARD-holds any request that doesn't, so a mis-wired caller can never
  reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [cleancert.registry :as registry]
            [cleancert.store :as store]
            [langchain.model :as model]))

(defn- log-cleaning-completion
  "Cleaning-job intake upsert -- the advisor only normalizes/validates
  the patch; it does not invent the method, duration, or
  chemicals-used. High confidence, low stakes -- administrative
  logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "洗浄作業記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :cleaning-job/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- certification-scan
  "Draft a certify/fail decision from a robot's post-clean residue-ppm
  sensor reading against a piece of equipment. The advisor reports what
  it can see (equipment verified?/registered?, the independently
  computed verdict) in its rationale, but `cleancert.governor` NEVER
  trusts this report -- it independently re-derives verified?/
  registered? and the certify/fail verdict from the equipment's own
  stored threshold before any commit is possible. This mock advisor
  itself always reports the honestly-recomputed verdict (it has no
  incentive to lie); the governor's own independent recompute exists
  for the LLM-advisor path and any compromised/hallucinating advisor."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        residue-ppm (:residue-ppm value)
        eq (store/equipment-unit db equipment-id)
        ready? (and eq (registry/equipment-ready? eq))
        verdict (when eq (registry/certification-verdict eq residue-ppm))]
    {:summary    (str subject " 向け認証判定提案 (residue-ppm=" residue-ppm ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (if eq
                   (str "equipment-verified?=" (registry/equipment-verified? eq)
                        " equipment-registered?=" (registry/equipment-registered? eq)
                        " threshold-ppm=" (:certification-threshold-ppm eq)
                        " verdict=" verdict)
                   (str equipment-id " が見つかりません"))
     :cites      (if eq [equipment-id] [])
     :effect     :certification/decide
     :value      (assoc value :decision (or verdict :fail))
     :stake      nil
     :confidence (if (and ready? (registry/residue-reading-valid? residue-ppm)) 0.9 0.3)}))

(defn- flag-safety-concern
  "Draft a confined-space-entry / hazardous-chemical-residue concern
  (the two categories OSHA 29 CFR 1910.146 / 1910.1200 single out).
  ALWAYS `:stake :coordination/safety-concern` -- a safety concern is
  NEVER a proposal the advisor may quietly downgrade to low-stakes, and
  it is never gated on the referenced equipment being verified (a
  concern can be raised about ANY equipment, verified or not -- see
  README `What this actor does NOT do` re: never blocking
  safety-relevant reporting on an administrative technicality). See
  `cleancert.phase`: no phase ever adds this op to a phase's `:auto`
  set; `cleancert.governor` also always escalates on
  `:coordination/safety-concern`. Two independent layers agree,
  deliberately."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (and equipment-id (store/equipment-unit db equipment-id))]
    {:summary    (str subject " 向け安全懸念報告 (" (:hazard-type value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (str "hazard-type=" (:hazard-type value)
                      " severity=" (:severity value)
                      " description=" (:description value))
     :cites      (if eq [equipment-id] [])
     :effect     :safety-concern/flag
     :value      value
     :stake      :coordination/safety-concern
     :confidence 0.9}))

(defn- schedule-recleaning
  "Draft a re-clean scheduling proposal against a piece of equipment
  with an on-file FAILED certification. The advisor reports the
  equipment's own on-file certification status in its rationale, but
  `cleancert.governor` NEVER trusts it: it independently re-derives the
  equipment's own `:last-certified-status` before any commit is
  possible."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (store/equipment-unit db equipment-id)
        ready? (and eq (registry/equipment-ready? eq))
        failed? (and eq (= :fail (:last-certified-status eq)))]
    {:summary    (str subject " 向け再洗浄予定提案"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (if eq
                   (str "equipment-verified?=" (registry/equipment-verified? eq)
                        " equipment-registered?=" (registry/equipment-registered? eq)
                        " last-certified-status=" (:last-certified-status eq)
                        " actuate-equipment?=" (boolean (:actuate-equipment? value)))
                   (str equipment-id " が見つかりません"))
     :cites      (if eq [equipment-id] [])
     :effect     :recleaning/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? failed? (not (:actuate-equipment? value))) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-cleaning-completion   (log-cleaning-completion db request)
    :certification-scan        (certification-scan db request)
    :flag-safety-concern       (flag-safety-concern db request)
    :schedule-recleaning       (schedule-recleaning db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは産業用洗浄・認証コーディネーターの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:cleaning-job/upsert|:certification/decide|"
       ":safety-concern/flag|:recleaning/schedule) "
       ":stake(:coordination/safety-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証または未登録の設備に対する作業を提案してはいけません。"
       "洗浄設備(タンク・噴霧・薬剤ディスペンサ等)の直接操作(actuate)を絶対に提案してはいけません"
       "(この actor は提案のみを行い、実行は一切行いません)。"
       "残留物センサー読取値と矛盾する認証判定(:decision)を報告してはいけません。"
       "認証(certification)を自己発行する提案をしてはいけません。"))

(defn- facts-for [st {:keys [op value]}]
  (case op
    :log-cleaning-completion    {}
    :certification-scan         {:equipment (store/equipment-unit st (:equipment-id value))}
    :flag-safety-concern        {:equipment (and (:equipment-id value)
                                                  (store/equipment-unit st (:equipment-id value)))}
    :schedule-recleaning        {:equipment (store/equipment-unit st (:equipment-id value))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `cleancert.governor`
  escalates/holds -- an LLM hiccup can never auto-decide a
  certification, auto-flag a concern, or auto-schedule a re-clean."
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
  {:t          :cleancert-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
