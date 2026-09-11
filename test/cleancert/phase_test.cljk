(ns cleancert.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:certification-scan` and `:schedule-recleaning` must
  NEVER be members of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [cleancert.phase :as phase]))

(deftest certification-scan-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a certification decision"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :certification-scan))
          (str "phase " n " must not auto-commit :certification-scan")))))

(deftest flag-safety-concern-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :flag-safety-concern))
        (str "phase " n " must not auto-commit :flag-safety-concern"))))

(deftest schedule-recleaning-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :schedule-recleaning))
        (str "phase " n " must not auto-commit :schedule-recleaning"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-cleaning-completion carries no physical/financial risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-cleaning-completion} (:auto (get phase/phases 3))))))

(deftest certification-scan-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :certification-scan))
  (is (not (contains? (:writes (get phase/phases 2)) :certification-scan)))
  (is (not (contains? (:writes (get phase/phases 1)) :certification-scan))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-cleaning-completion} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :certification-scan} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-recleaning} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-cleaning-completion} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-cleaning-completion} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
