(ns cleancert.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `cleancert.store` ns docstring."
  (:require [clojure.test :refer [deftest is testing]]
            [cleancert.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/equipment-unit s "tank-001"))))
    (is (true? (:registered? (store/equipment-unit s "tank-001"))))
    (is (nil? (:last-certified-status (store/equipment-unit s "tank-001"))))
    (is (true? (:verified? (store/equipment-unit s "washer-002"))))
    (is (= :fail (:last-certified-status (store/equipment-unit s "washer-002"))))
    (is (false? (:verified? (store/equipment-unit s "vessel-003"))))
    (is (false? (:registered? (store/equipment-unit s "vessel-003"))))
    (is (= ["tank-001" "vessel-003" "washer-002"] (mapv :id (store/all-equipment s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/certification-history s)))
    (is (= [] (store/recleaning-history s)))
    (is (= [] (store/safety-concerns s)))
    (is (zero? (store/next-certification-sequence s)))
    (is (zero? (store/next-recleaning-sequence s)))
    (is (false? (store/recleaning-already-scheduled? s "rcl-1")))
    (is (nil? (store/recleaning s "rcl-1")))))

(deftest fresh-store-has-no-equipment
  (let [s (store/mem-store)]
    (is (= [] (store/all-equipment s)))
    (is (nil? (store/equipment-unit s "tank-001")))))

(deftest cleaning-job-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :cleaning-job/upsert :path ["job-001"]
                             :value {:equipment-id "tank-001" :method :chemical-wash
                                     :duration-minutes 45.0}})
    (is (= :chemical-wash (:method (store/cleaning-job s "job-001"))))
    (is (= "tank-001" (:equipment-id (store/cleaning-job s "job-001"))))
    (store/commit-record! s {:effect :cleaning-job/upsert :path ["job-001"]
                             :value {:duration-minutes 60.0}})
    (is (= :chemical-wash (:method (store/cleaning-job s "job-001"))) "unrelated field preserved")
    (is (= 60.0 (:duration-minutes (store/cleaning-job s "job-001"))))))

(deftest certification-decide-commits-and-advances-sequence-and-updates-equipment
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly"
    (let [s (seeded)]
      (store/commit-record! s {:effect :certification/decide :path ["cert-1"]
                               :value {:equipment-id "tank-001" :residue-ppm 10.0 :decision :certify}})
      (is (= "CERT-000000" (get (first (store/certification-history s)) "record_id")))
      (is (= "certification-decision-draft" (get (first (store/certification-history s)) "kind")))
      (is (= 1 (count (store/certification-history s))))
      (is (= 1 (store/next-certification-sequence s)))
      (is (= :certify (:last-certified-status (store/equipment-unit s "tank-001")))
          "ground-truth equipment record updated by the commit")
      (is (= :certify (:decision (store/certification s "tank-001")))))))

(deftest safety-concern-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-1"]
                             :value {:equipment-id "tank-001" :hazard-type :confined-space-entry :severity :high}})
    (is (= 1 (count (store/safety-concerns s))))
    (is (= :confined-space-entry (:hazard-type (first (store/safety-concerns s)))))
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-2"]
                             :value {:equipment-id "washer-002" :hazard-type :hazardous-chemical-residue :severity :moderate}})
    (is (= 2 (count (store/safety-concerns s))) "append-only")))

(deftest recleaning-schedule-commits-and-advances-sequence
  (let [s (seeded)]
    (store/commit-record! s {:effect :recleaning/schedule :path ["rcl-1"]
                             :value {:equipment-id "washer-002" :scheduled-date "2026-08-01"}})
    (is (= "RCL-000000" (get (first (store/recleaning-history s)) "record_id")))
    (is (= "recleaning-schedule-draft" (get (first (store/recleaning-history s)) "kind")))
    (is (true? (:scheduled? (store/recleaning s "rcl-1"))))
    (is (= "washer-002" (:equipment-id (store/recleaning s "rcl-1"))))
    (is (= 1 (count (store/recleaning-history s))))
    (is (= 1 (store/next-recleaning-sequence s)))
    (is (true? (store/recleaning-already-scheduled? s "rcl-1")))
    (is (= "RCL-000000" (:recleaning-number (store/recleaning s "rcl-1"))))))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
