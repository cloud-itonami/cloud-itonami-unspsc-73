(ns cleancert.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT actuate cleaning equipment directly...
  does NOT self-issue a real certification mark') implemented
  faithfully through the FULL compiled graph. The single invariant
  under test:

    CleaningAdvisor never certifies equipment, flags a safety concern,
    or schedules a re-clean the Industrial Cleaning Governor would
    reject; `:certification-scan`/`:flag-safety-concern`/
    `:schedule-recleaning` NEVER auto-commit at any phase;
    `:log-cleaning-completion` (no physical/financial risk) MAY
    auto-commit when clean; and every decision (commit OR hold) leaves
    exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [cleancert.store :as store]
            [cleancert.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :cleaning-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-cleaning-completion-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-cleaning-completion :effect :propose :subject "job-001"
                   :patch {:equipment-id "tank-001" :method :chemical-wash}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :chemical-wash (:method (store/cleaning-job db "job-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest certification-scan-always-needs-approval
  (testing "certification decisions are never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :certification-scan :effect :propose :subject "cert-1"
                     :value {:equipment-id "tank-001" :residue-ppm 10.0}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :certify (:last-certified-status (store/equipment-unit db "tank-001"))))
        (is (= 1 (count (store/certification-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-cleaning-completion :effect :direct-write :subject "job-001"
                     :patch {:equipment-id "tank-001"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :dispense-solvent-directly :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest equipment-not-verified-is-held-and-unoverridable
  (testing "scanning against an unverified/unregistered vessel -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :certification-scan :effect :propose :subject "cert-2"
                     :value {:equipment-id "vessel-003" :residue-ppm 5.0}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:equipment-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/certification-history db))))))

(deftest invalid-residue-reading-is-held
  (testing "an implausible residue-ppm sensor reading -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :certification-scan :effect :propose :subject "cert-3"
                     :value {:equipment-id "tank-001" :residue-ppm 99999999.0}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:invalid-residue-reading} (-> (store/ledger db) last :basis)))
      (is (empty? (store/certification-history db))))))

(deftest no-failed-certification-is-held-and-unoverridable
  (testing "scheduling a re-clean with no on-file failed certification -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :schedule-recleaning :effect :propose :subject "rcl-1"
                     :value {:equipment-id "tank-001" :scheduled-date "2026-08-01"
                             :actuate-equipment? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:no-failed-certification} (-> (store/ledger db) last :basis)))
      (is (empty? (store/recleaning-history db))))))

(deftest equipment-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-equipment? true -> HOLD, PERMANENT, never reaches request-approval even though the equipment is verified, registered and has a failed cert on file"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-recleaning :effect :propose :subject "rcl-2"
                     :value {:equipment-id "washer-002" :scheduled-date "2026-09-01"
                             :actuate-equipment? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:equipment-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/recleaning-history db))))))

(deftest certification-authority-is-held-and-permanently-blocked
  (testing "a proposal that sets :certified? true outside the gated certification path -> HOLD, PERMANENT, never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t8b"
                    {:op :log-cleaning-completion :effect :propose :subject "job-002"
                     :patch {:equipment-id "tank-001" :certified? true}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:certification-authority-blocked} (-> (store/ledger db) last :basis)))
      (is (not (true? (:certified? (store/cleaning-job db "job-002"))))
          "fabricated self-certification never lands in the SSoT"))))

(deftest schedule-recleaning-double-schedule-is-held
  (testing "scheduling the SAME re-clean record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :schedule-recleaning :effect :propose :subject "rcl-3"
                                  :value {:equipment-id "washer-002" :scheduled-date "2026-08-01"
                                          :actuate-equipment? false}} coordinator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :schedule-recleaning :effect :propose :subject "rcl-3"
                                   :value {:equipment-id "washer-002" :scheduled-date "2026-08-01"
                                           :actuate-equipment? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/recleaning-history db))) "still only the one earlier schedule"))))

(deftest invalid-duration-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-cleaning-completion :effect :propose :subject "job-003"
                                  :patch {:equipment-id "tank-001" :duration-minutes 999999.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-duration} (-> (store/ledger db) last :basis)))
    (is (not= 999999.0 (:duration-minutes (store/cleaning-job db "job-003"))) "fabricated duration never lands in the SSoT")))

(deftest safety-concern-always-escalates-even-high-confidence
  (testing "flag-safety-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                    :value {:equipment-id "tank-001" :hazard-type :confined-space-entry
                                            :severity :high :description "confined-space entry required"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t11")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/safety-concerns db))))))))

(deftest safety-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t12" {:op :flag-safety-concern :effect :propose :subject "concern-2"
                                :value {:equipment-id "tank-001" :hazard-type :hazardous-chemical-residue
                                        :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t12")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/safety-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-recleaning-always-needs-approval
  (testing "a CLEAN re-clean scheduling proposal is never auto-eligible -- always escalates, even against equipment with a failed cert on file"
    (let [[db actor] (fresh)
          res (exec-op actor "t13" {:op :schedule-recleaning :effect :propose :subject "rcl-4"
                                    :value {:equipment-id "washer-002" :scheduled-date "2026-08-01"
                                            :actuate-equipment? false}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t13")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/recleaning-history db))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-cleaning-completion :effect :propose :subject "job-001"
                          :patch {:equipment-id "tank-001" :method :chemical-wash}} coordinator)
      (exec-op actor "b" {:op :log-cleaning-completion :effect :propose :subject "job-002"
                          :patch {:equipment-id "tank-001" :duration-minutes -1.0}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
