(ns cleancert.governor-test
  "Direct unit tests against `cleancert.governor/check` with hand-crafted
  proposals -- including the certification-decision-mismatch case a
  well-behaved deterministic advisor can never itself produce (see
  `cleancert.advisor/certification-scan`, which always recomputes its
  own :decision honestly). This governor's INDEPENDENT recompute exists
  precisely for a compromised/hallucinating advisor or the LLM-advisor
  path -- exercised here directly, the same discipline
  `cleancert.governor-contract-test` applies at the full-graph level for
  every other rule."
  (:require [clojure.test :refer [deftest is testing]]
            [cleancert.store :as store]
            [cleancert.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/with-equipment st {"tank-001" {:id "tank-001" :kind :degreasing-tank
                                          :verified? true :registered? true
                                          :certification-threshold-ppm 50.0
                                          :last-certified-status nil}
                              "washer-002" {:id "washer-002" :kind :parts-washer
                                            :verified? true :registered? true
                                            :certification-threshold-ppm 25.0
                                            :last-certified-status :fail}
                              "vessel-003" {:id "vessel-003" :kind :confined-space-vessel
                                            :verified? false :registered? false
                                            :certification-threshold-ppm 30.0
                                            :last-certified-status nil}})
    st))

(def ^:private req {:op :certification-scan :effect :propose :subject "cert-x"})

(defn- scan [equipment-id residue-ppm decision]
  {:effect :certification/decide
   :value {:equipment-id equipment-id :residue-ppm residue-ppm :decision decision}
   :confidence 0.9 :stake nil})

(deftest ok-when-decision-matches-independent-recompute
  (let [st (fresh-store)
        v (governor/check req {} (scan "tank-001" 10.0 :certify) st)]
    (is (not (:hard? v)))))

(deftest hard-on-decision-mismatch-certify-when-truth-is-fail
  (testing "an advisor rubber-stamping :certify when the sensor reading itself would fail -- HARD, never let a self-report stand against contradicting evidence"
    (let [st (fresh-store)
          v (governor/check req {} (scan "tank-001" 90.0 :certify) st)]
      (is (:hard? v))
      (is (some #(= :certification-decision-mismatch (:rule %)) (:violations v))))))

(deftest hard-on-decision-mismatch-fail-when-truth-is-certify
  (let [st (fresh-store)
        v (governor/check req {} (scan "tank-001" 5.0 :fail) st)]
    (is (:hard? v))
    (is (some #(= :certification-decision-mismatch (:rule %)) (:violations v)))))

(deftest hard-on-unverified-equipment-for-certification-scan
  (let [st (fresh-store)
        v (governor/check req {} (scan "vessel-003" 5.0 :certify) st)]
    (is (:hard? v))
    (is (some #(= :equipment-not-verified (:rule %)) (:violations v)))))

(deftest hard-on-invalid-residue-reading
  (let [st (fresh-store)
        v (governor/check req {} (scan "tank-001" -5.0 :fail) st)]
    (is (:hard? v))
    (is (some #(= :invalid-residue-reading (:rule %)) (:violations v)))))

(deftest hard-on-non-propose-effect
  (let [st (fresh-store)
        v (governor/check {:op :certification-scan :effect :direct-write :subject "x"} {}
                          (scan "tank-001" 10.0 :certify) st)]
    (is (:hard? v))
    (is (some #(= :not-propose-effect (:rule %)) (:violations v)))))

(deftest hard-on-unknown-op
  (let [st (fresh-store)
        v (governor/check {:op :dispense-directly :effect :propose :subject "x"} {}
                          {:effect :cleaning-job/upsert :confidence 0.9} st)]
    (is (:hard? v))
    (is (some #(= :unknown-op (:rule %)) (:violations v)))))

(deftest hard-on-proposal-effect-outside-allowlist
  (let [st (fresh-store)
        v (governor/check req {} (assoc (scan "tank-001" 10.0 :certify) :effect :tank/dispense) st)]
    (is (:hard? v))
    (is (some #(= :equipment-control-blocked (:rule %)) (:violations v)))))

(deftest hard-on-actuate-equipment-permanent
  (let [st (fresh-store)
        v (governor/check {:op :schedule-recleaning :effect :propose :subject "rcl-x"} {}
                          {:effect :recleaning/schedule :confidence 0.9
                           :value {:equipment-id "washer-002" :actuate-equipment? true}} st)]
    (is (:hard? v))
    (is (some #(= :equipment-actuate-blocked (:rule %)) (:violations v)))))

(deftest hard-on-certification-authority-side-channel
  (let [st (fresh-store)
        v (governor/check {:op :log-cleaning-completion :effect :propose :subject "job-x"} {}
                          {:effect :cleaning-job/upsert :confidence 0.9
                           :value {:equipment-id "tank-001" :certified? true}} st)]
    (is (:hard? v))
    (is (some #(= :certification-authority-blocked (:rule %)) (:violations v)))))

(deftest certification-decide-effect-with-certified-flag-is-not-a-side-channel
  (testing "the gated :certification/decide effect itself is exempt from the side-channel block"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (scan "tank-001" 10.0 :certify) :value
                                          {:equipment-id "tank-001" :residue-ppm 10.0
                                           :decision :certify :certified? true}) st)]
      (is (not (some #(= :certification-authority-blocked (:rule %)) (:violations v)))))))

(deftest hard-on-no-failed-certification-for-recleaning
  (let [st (fresh-store)
        v (governor/check {:op :schedule-recleaning :effect :propose :subject "rcl-x"} {}
                          {:effect :recleaning/schedule :confidence 0.9
                           :value {:equipment-id "tank-001" :actuate-equipment? false}} st)]
    (is (:hard? v))
    (is (some #(= :no-failed-certification (:rule %)) (:violations v)))))

(deftest ok-recleaning-against-failed-equipment
  (let [st (fresh-store)
        v (governor/check {:op :schedule-recleaning :effect :propose :subject "rcl-x"} {}
                          {:effect :recleaning/schedule :confidence 0.9
                           :value {:equipment-id "washer-002" :actuate-equipment? false}} st)]
    (is (not (:hard? v)))))

(deftest hard-on-invalid-duration
  (let [st (fresh-store)
        v (governor/check {:op :log-cleaning-completion :effect :propose :subject "job-x"} {}
                          {:effect :cleaning-job/upsert :confidence 0.9
                           :value {:equipment-id "tank-001" :duration-minutes -5.0}} st)]
    (is (:hard? v))
    (is (some #(= :invalid-duration (:rule %)) (:violations v)))))

(deftest escalates-safety-concern-regardless-of-confidence
  (let [st (fresh-store)
        v (governor/check {:op :flag-safety-concern :effect :propose :subject "concern-x"} {}
                          {:effect :safety-concern/flag :confidence 0.99
                           :stake :coordination/safety-concern
                           :value {:equipment-id "tank-001" :hazard-type :confined-space-entry}} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (scan "tank-001" 10.0 :certify) :confidence 0.2) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
