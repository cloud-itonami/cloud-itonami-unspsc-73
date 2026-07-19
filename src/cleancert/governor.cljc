(ns cleancert.governor
  "Industrial Cleaning Governor -- the independent compliance layer
  that earns the Cleaning Advisor the right to commit. The advisor has
  no notion of whether a piece of equipment it wants to certify or
  schedule a re-clean against has actually been inspected/registered,
  whether its own claimed certify/fail decision actually matches what
  the raw residue-ppm sensor reading and the equipment's own registered
  threshold say, whether a re-clean proposal secretly tries to ACTUATE
  (rather than merely draft-schedule) the wash/spray/dispense
  equipment, whether a proposal secretly tries to self-issue a real,
  signed cleaning-certification mark (an authority this actor never
  holds), or whether a re-clean is being scheduled (and billed) against
  equipment with no on-file failed certification to justify it -- so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  `:itonami.blueprint/governor` is `:industrial-cleaning-governor` (see
  blueprint.edn).

  Two of the always-escalate hazard categories this governor treats as
  high-stakes regardless of confidence -- confined-space entry and
  hazardous chemical residue -- are the exact categories OSHA's General
  Industry standards single out: 29 CFR 1910.146 (Permit-Required
  Confined Spaces) and 29 CFR 1910.1200 (Hazard Communication /
  chemical hazard disclosure). This governor does not implement those
  regulations (that is the accredited safety program's job); it only
  uses them to ground WHY a `:flag-safety-concern` proposal citing
  either category is never something an LLM confidence score gets to
  wave through.

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to
                                       coordinate? Anything else -- HARD
                                       hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:tank/dispense` or
                                       `:washer/actuate`) is the
                                       'direct cleaning-equipment
                                       control' scope violation this
                                       actor must NEVER perform -- HARD,
                                       PERMANENT, unconditional.
    4. Equipment-actuate blocked   -- does any proposal's own `:value`
                                       declare `:actuate-equipment?
                                       true`? Directly actuating a
                                       wash/spray/dispense/decontaminate
                                       system is this actor's permanent
                                       scope boundary (see README
                                       `Robotics premise`) -- HARD,
                                       PERMANENT, unconditional. No
                                       phase and no human approval can
                                       ever override this (see
                                       `cleancert.phase`: no op is ever
                                       a member of any phase's `:auto`
                                       set for this reason either --
                                       two independent layers agree).
    5. Certification authority
       blocked                     -- ANY proposal (any op) whose own
                                       `:value`/`:patch` declares
                                       `:certified? true` OUTSIDE the
                                       gated `:certification/decide`
                                       path is attempting to self-issue
                                       a real cleaning-certification
                                       mark through a side channel (e.g.
                                       slipped into a routine
                                       `:log-cleaning-completion`
                                       patch) -- an authority this actor
                                       never holds regardless of which
                                       op carries the claim -- HARD,
                                       PERMANENT, unconditional.
    6. Equipment not verified/
       registered                  -- for `:certification-scan` and
                                       `:schedule-recleaning`,
                                       INDEPENDENTLY verify the
                                       referenced equipment's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`cleancert.registry/equipment-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status.
    7. Certification-decision
       mismatch                    -- for `:certification-scan`,
                                       INDEPENDENTLY recompute the
                                       certify/fail verdict from the
                                       proposal's own `:residue-ppm`
                                       reading against the equipment's
                                       own registered `:certification-
                                       threshold-ppm`
                                       (`cleancert.registry/
                                       certification-verdict`) and
                                       compare it against the
                                       proposal's own claimed
                                       `:decision` -- a mismatch (e.g.
                                       an advisor rubber-stamping
                                       `:certify` when the sensor
                                       reading itself would fail) is
                                       HARD-held: never let a
                                       self-reported certify stand
                                       against contradicting sensor
                                       evidence.
    8. Invalid residue reading     -- for `:certification-scan`, if
                                       `:residue-ppm` is not a
                                       physically plausible reading
                                       (`cleancert.registry/residue-
                                       reading-valid?`), the proposal is
                                       rejected rather than let
                                       fabricated/sensor-error data
                                       drive a certify/fail decision.
    9. No failed certification
       on file                     -- for `:schedule-recleaning`,
                                       INDEPENDENTLY verify the
                                       equipment's own `:last-certified-
                                       status` on file is `:fail` --
                                       never trust the advisor's own
                                       claim that a re-clean is
                                       warranted. Scheduling (and
                                       billing) a re-clean against
                                       equipment with no on-file failed
                                       certification is a fabrication
                                       this governor rejects.
   10. Already scheduled           -- for `:schedule-recleaning`,
                                       refuses to schedule the SAME
                                       re-clean record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
   11. Invalid duration            -- for `:log-cleaning-completion`,
                                       if the patch declares a
                                       `:duration-minutes` that is not
                                       a physically plausible value
                                       (`cleancert.registry/duration-
                                       valid?`), the job record is
                                       rejected rather than let
                                       fabricated/data-entry-error data
                                       through.
   12. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`) -- escalate to a human
                                       contractor-operator. SOFT: the
                                       human may approve."
  (:require [cleancert.registry :as registry]
            [cleancert.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-cleaning-completion :certification-scan
    :flag-safety-concern :schedule-recleaning})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct
  wash/spray/dispense-equipment-control effect."
  #{:cleaning-job/upsert :certification/decide
    :safety-concern/flag :recleaning/schedule})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns (confined-space entry, hazardous chemical residue --
  the two categories OSHA 29 CFR 1910.146 / 1910.1200 single out) are
  the one op in this domain that always demands human eyes regardless
  of confidence."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct wash/spray/dispense-equipment control, a
  fabricated actuation effect) is this actor's central scope
  boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は洗浄設備の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- equipment-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a proposal whose own `:value`
  declares `:actuate-equipment? true` is attempting to directly actuate
  a wash/spray/dispense/decontaminate system -- this actor may only
  ever propose/schedule a DRAFT (a job log, a certification decision, a
  re-clean window), never actuate the equipment directly. No override,
  ever."
  [proposal]
  (when (true? (:actuate-equipment? (:value proposal)))
    [{:rule :equipment-actuate-blocked
      :detail "洗浄設備の直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- certification-authority-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal (any op) whose own
  `:value`/`:patch` declares `:certified? true` OUTSIDE the gated
  `:certification/decide` path is attempting to self-issue a real
  cleaning-certification mark through a side channel -- an authority
  this actor never holds. No phase and no human approval can ever
  override this."
  [proposal]
  (let [payload (or (:value proposal) (:patch proposal))]
    (when (and (true? (:certified? payload))
               (not= :certification/decide (:effect proposal)))
      [{:rule :certification-authority-blocked
        :detail "認証(certification)の自己発行提案は恒久的に禁止 -- 認証は :certification-scan 経路でのみ、かつ人間承認後にのみ成立"}])))

(defn- equipment-not-verified-violations
  "For `:certification-scan` and `:schedule-recleaning`, INDEPENDENTLY
  verify the referenced equipment exists and is both `:verified?` AND
  `:registered?` -- never trust the advisor's own report."
  [{:keys [op]} proposal st]
  (when (contains? #{:certification-scan :schedule-recleaning} op)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での提案")}]))))

(defn- certification-decision-mismatch-violations
  "For `:certification-scan`, INDEPENDENTLY recompute the certify/fail
  verdict from the proposal's own `:residue-ppm` reading against the
  equipment's own registered threshold, and compare it against the
  proposal's own claimed `:decision` -- never let a self-reported
  certify stand against contradicting sensor evidence."
  [{:keys [op]} proposal st]
  (when (= op :certification-scan)
    (let [{:keys [equipment-id residue-ppm decision]} (:value proposal)
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when (and eq decision)
        (let [truth (registry/certification-verdict eq residue-ppm)]
          (when-not (= truth decision)
            [{:rule :certification-decision-mismatch
              :detail (str equipment-id " の残留物読取値(" residue-ppm
                           ")から独立算出した判定は " truth
                           " -- 提案の自己申告判定 " decision " と不一致")}]))))))

(defn- invalid-residue-reading-violations
  "For `:certification-scan`, if `:residue-ppm` is not a physically
  plausible reading, reject rather than let fabricated/sensor-error
  data drive a certify/fail decision."
  [{:keys [op]} proposal]
  (when (= op :certification-scan)
    (let [residue-ppm (:residue-ppm (:value proposal))]
      (when-not (registry/residue-reading-valid? residue-ppm)
        [{:rule :invalid-residue-reading
          :detail (str (pr-str residue-ppm) " は物理的に妥当な残留物読取値ではない")}]))))

(defn- no-failed-certification-violations
  "For `:schedule-recleaning`, INDEPENDENTLY verify the equipment's own
  `:last-certified-status` on file is `:fail` -- never trust the
  advisor's own claim that a re-clean is warranted. Scheduling (and
  billing) a re-clean with no on-file failed certification is a
  fabrication this governor rejects."
  [{:keys [op]} proposal st]
  (when (= op :schedule-recleaning)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when (and eq (not= :fail (:last-certified-status eq)))
        [{:rule :no-failed-certification
          :detail (str equipment-id " に未合格(:fail)の登録済み認証判定が無い -- 再洗浄の予定提案には合格しなかった認証判定の記録が必要")}]))))

(defn- already-scheduled-violations
  "For `:schedule-recleaning`, refuses to schedule the SAME re-clean
  record twice, off a dedicated `:scheduled?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-recleaning)
    (when (store/recleaning-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- invalid-duration-violations
  "For `:log-cleaning-completion`, if the patch declares a
  `:duration-minutes` that is not a physically plausible value, reject
  rather than let fabricated/data-entry-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-cleaning-completion)
    (let [duration (:duration-minutes (:value proposal))]
      (when (and (some? duration) (not (registry/duration-valid? duration)))
        [{:rule :invalid-duration
          :detail (str duration " は物理的に妥当な duration-minutes の範囲外")}]))))

(defn check
  "Censors a Cleaning Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (equipment-actuate-blocked-violations proposal)
                           (certification-authority-blocked-violations proposal)
                           (equipment-not-verified-violations request proposal st)
                           (certification-decision-mismatch-violations request proposal st)
                           (invalid-residue-reading-violations request proposal)
                           (no-failed-certification-violations request proposal st)
                           (already-scheduled-violations request st)
                           (invalid-duration-violations request proposal)))
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
