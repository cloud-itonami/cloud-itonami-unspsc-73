(ns cleancert.operation-test
  "Smoke tests for the compiled CleaningOperationActor graph itself
  (build + one happy path per op). The governor's full rule contract
  (HARD holds, escalation, phase gating) is exercised in
  `cleancert.governor-contract-test` (full graph) and
  `cleancert.governor-test` (direct unit); the Store contract in
  `cleancert.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [cleancert.operation :as op]
            [cleancert.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :cleaning-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "CleaningOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-cleaning-job-logging-proposal
  (testing "Proposing a cleaning-job log auto-commits when clean (phase 3, no physical/financial risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-cleaning-completion :effect :propose :subject "job-001"
                           :patch {:equipment-id "tank-001" :method :chemical-wash}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-certification-scan
  (testing "Certification-scan decisions always escalate for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :certification-scan :effect :propose :subject "cert-1"
                           :value {:equipment-id "tank-001" :residue-ppm 10.0}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-safety-concern-escalation
  (testing "Safety concerns always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :flag-safety-concern :effect :propose :subject "concern-1"
                           :value {:equipment-id "tank-001" :hazard-type :confined-space-entry
                                   :severity :high :description "confined-space entry"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-schedule-recleaning-proposal
  (testing "Re-clean scheduling proposal is submitted and (when equipment has a failed cert on file) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :schedule-recleaning :effect :propose :subject "rcl-1"
                           :value {:equipment-id "washer-002" :scheduled-date "2026-08-01"
                                   :actuate-equipment? false}}
                          coordinator)]
      (is (some? result))
      (is (= :interrupted (:status result))))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "The domain-agnostic commit-record! path stores a raw record by :id"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))
