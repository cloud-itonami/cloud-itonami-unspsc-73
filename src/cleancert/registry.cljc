(ns cleancert.registry
  "Pure-function domain logic for the industrial-cleaning-and-
  certification back-office coordination actor -- equipment
  verification, the certification verdict (residue-ppm vs a piece of
  equipment's own registered threshold), reading-plausibility
  validation, and draft certification/recleaning-schedule record
  construction.

  No pre-existing `kotoba-lang/cleancert`-style capability library
  exists for this vertical, so the domain logic lives here as pure
  functions, re-verified INDEPENDENTLY by `cleancert.governor` -- the
  same 'ground truth, not self-report' discipline every sibling
  actor's own registry establishes (e.g. `motomfg.registry`,
  `supplydist.governor`'s stock arithmetic): never trust a proposal's
  own self-reported decision when the inputs needed to recompute it
  independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to a real cleaning-equipment/sensor system. It builds the DRAFT
  record a cleaning contractor would keep (a certification decision, a
  scheduled re-clean window), not the act of actuating a wash/spray/
  dispense system and never the act of self-issuing a real, signed
  certification mark (see README `Robotics premise` and
  `cleancert.governor`'s permanent scope blocks).

  SCOPE: UNSPSC segment 73 (Industrial Production and Manufacturing
  Services) -- here illustrated as an independent industrial
  equipment-cleaning and post-clean-certification contractor: a
  cleaning/inspection robot performs the wash/degrease/decontaminate
  pass and a post-clean sensor scan (residue, contamination); this
  actor coordinates the back-office record-keeping around that work
  (cleaning-job logging, certification-scan decisions, safety-concern
  flagging, re-clean scheduling). It never actuates the cleaning
  equipment directly and never signs a real certification mark."
  )

;; ----------------------------- constants -----------------------------

(def residue-ppm-min
  "Physical floor for a post-clean residue/contamination sensor
  reading (zero residue is the best possible outcome, never negative)."
  0.0)

(def residue-ppm-max
  "Physical ceiling for a post-clean residue/contamination sensor
  reading. A reading above this is implausible sensor/QC data (e.g. a
  saturated or miscalibrated sensor), not a real scan result -- the
  governor rejects it rather than let it drive a certify/fail
  decision."
  100000.0)

(def duration-minutes-min 0.0)

(def duration-minutes-max
  "Physical ceiling for a single logged cleaning job's duration -- one
  week in minutes. A reading beyond this is a fabricated or
  data-entry-error duration, not a real single cleaning job."
  10080.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned, not
  merely referenced from an unverified request)? A pure predicate over
  the equipment's own permanent field -- no proposal inspection
  needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the contractor's own
  equipment registry)? Scanning or scheduling work against equipment
  that is not on file and registered is the exact scope violation this
  actor's HARD invariant ('equipment record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY certification scan or re-clean schedule
  may be proposed against it. Two independent facts on the equipment's
  own permanent record, neither inferred from the advisor's own
  rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- certification verdict -----------------------------

(defn certification-verdict
  "INDEPENDENT recompute of the certify/fail decision from a raw
  `residue-ppm` sensor reading against `equipment`'s own registered
  `:certification-threshold-ppm` -- the arithmetic ground truth
  `cleancert.governor` cross-checks a proposal's own claimed `:decision`
  against. `:fail` whenever the reading is missing, non-numeric, or at
  or above the threshold; `:certify` only for a plausible reading
  strictly below it. Never trusts a proposal's own claimed decision."
  [equipment residue-ppm]
  (let [threshold (:certification-threshold-ppm equipment)]
    (if (and (number? residue-ppm) (number? threshold)
             (< (double residue-ppm) (double threshold)))
      :certify
      :fail)))

(defn residue-reading-valid?
  "Is `residue-ppm` a physically plausible post-clean sensor reading?
  Rejects nil, non-numbers, negative values, and values beyond
  `residue-ppm-max` -- a fabricated or sensor-error reading, never let
  through as a real scan fact."
  [residue-ppm]
  (and (number? residue-ppm)
       (>= (double residue-ppm) (double residue-ppm-min))
       (<= (double residue-ppm) (double residue-ppm-max))))

(defn duration-valid?
  "Is `duration-minutes` a physically plausible single-cleaning-job
  duration? Rejects nil, non-numbers, negative values, and values
  beyond `duration-minutes-max` -- a fabricated or data-entry-error
  duration, never let through as a real logged job."
  [duration-minutes]
  (and (number? duration-minutes)
       (>= (double duration-minutes) duration-minutes-min)
       (<= (double duration-minutes) duration-minutes-max)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human contractor-operator's act, not this actor's. This actor
  NEVER self-issues a real, signed cleaning-certification mark (see
  README `What this actor does NOT do`)."
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

(defn register-certification
  "Validate + construct the CERTIFICATION-DECISION DRAFT -- a
  residue-ppm-grounded certify/fail decision against a verified,
  registered piece of equipment. Pure function -- does not dispatch or
  sign anything; it builds the RECORD a cleaning contractor would keep.
  `cleancert.governor` independently re-verifies the equipment's own
  verified/registered ground truth and the decision's own arithmetic
  (`certification-verdict`) before this is ever allowed to commit."
  [certification-id equipment-id decision sequence]
  (when-not (and certification-id (not= certification-id ""))
    (throw (ex-info "certification: certification_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "certification: equipment_id required" {})))
  (when-not (#{:certify :fail} decision)
    (throw (ex-info "certification: decision must be :certify or :fail" {})))
  (when (< sequence 0)
    (throw (ex-info "certification: sequence must be >= 0" {})))
  (let [certification-number (str "CERT-" (zero-pad sequence 6))
        record {"record_id" certification-number
                "kind" "certification-decision-draft"
                "certification_id" certification-id
                "equipment_id" equipment-id
                "decision" (name decision)
                "immutable" true}]
    {"record" record "certification_number" certification-number
     "certificate" (unsigned-certificate "CleaningCertification" certification-number certification-number)}))

(defn register-recleaning
  "Validate + construct the RECLEANING-SCHEDULE DRAFT -- a proposed
  re-clean window against a piece of equipment with an on-file FAILED
  certification. Pure function -- does not actuate any wash/spray/
  dispense system or dispatch the robot; it builds the RECORD a
  cleaning contractor would keep. `cleancert.governor` independently
  re-verifies the equipment's own on-file failed-certification ground
  truth before this is ever allowed to commit."
  [recleaning-id equipment-id sequence]
  (when-not (and recleaning-id (not= recleaning-id ""))
    (throw (ex-info "recleaning: recleaning_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "recleaning: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "recleaning: sequence must be >= 0" {})))
  (let [recleaning-number (str "RCL-" (zero-pad sequence 6))
        record {"record_id" recleaning-number
                "kind" "recleaning-schedule-draft"
                "recleaning_id" recleaning-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "recleaning_number" recleaning-number
     "certificate" (unsigned-certificate "RecleaningSchedule" recleaning-number recleaning-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
