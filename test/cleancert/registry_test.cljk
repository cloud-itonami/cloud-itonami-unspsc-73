(ns cleancert.registry-test
  (:require [clojure.test :refer [deftest is]]
            [cleancert.registry :as r]))

;; ----------------------------- equipment-verified? / equipment-registered? / equipment-ready? -----------------------------

(deftest equipment-is-verified-when-flagged
  (is (true? (r/equipment-verified? {:id "e1" :verified? true}))))

(deftest equipment-is-not-verified-when-false-or-missing
  (is (false? (r/equipment-verified? {:id "e1" :verified? false})))
  (is (false? (r/equipment-verified? {:id "e1"}))))

(deftest equipment-is-registered-when-flagged
  (is (true? (r/equipment-registered? {:registered? true}))))

(deftest equipment-is-not-registered-when-false-or-missing
  (is (false? (r/equipment-registered? {:registered? false})))
  (is (false? (r/equipment-registered? {}))))

(deftest equipment-ready-requires-both
  (is (true? (r/equipment-ready? {:verified? true :registered? true})))
  (is (false? (r/equipment-ready? {:verified? true :registered? false})))
  (is (false? (r/equipment-ready? {:verified? false :registered? true})))
  (is (false? (r/equipment-ready? {}))))

;; ----------------------------- certification-verdict -----------------------------

(deftest reading-below-threshold-certifies
  (is (= :certify (r/certification-verdict {:certification-threshold-ppm 50.0} 10.0))))

(deftest reading-at-or-above-threshold-fails
  (is (= :fail (r/certification-verdict {:certification-threshold-ppm 50.0} 50.0))
      "exactly at threshold is not below it, only strictly under")
  (is (= :fail (r/certification-verdict {:certification-threshold-ppm 50.0} 51.0))))

(deftest missing-inputs-fail-closed
  (is (= :fail (r/certification-verdict {} 10.0)))
  (is (= :fail (r/certification-verdict {:certification-threshold-ppm 50.0} nil)))
  (is (= :fail (r/certification-verdict {:certification-threshold-ppm 50.0} "10"))))

;; ----------------------------- residue-reading-valid? -----------------------------

(deftest typical-residue-reading-is-valid
  (is (r/residue-reading-valid? 0.0))
  (is (r/residue-reading-valid? 10.0))
  (is (r/residue-reading-valid? 100000.0)))

(deftest negative-residue-reading-is-invalid
  (is (not (r/residue-reading-valid? -1.0))))

(deftest excessive-residue-reading-is-invalid
  (is (not (r/residue-reading-valid? 100000.01)))
  (is (not (r/residue-reading-valid? 99999999.0))))

(deftest non-numeric-or-missing-residue-reading-is-invalid
  (is (not (r/residue-reading-valid? nil)))
  (is (not (r/residue-reading-valid? "10"))))

;; ----------------------------- duration-valid? -----------------------------

(deftest typical-duration-is-valid
  (is (r/duration-valid? 0.0))
  (is (r/duration-valid? 45.0))
  (is (r/duration-valid? 10080.0)))

(deftest negative-duration-is-invalid
  (is (not (r/duration-valid? -1.0))))

(deftest excessive-duration-is-invalid
  (is (not (r/duration-valid? 10080.01)))
  (is (not (r/duration-valid? 999999.0))))

(deftest non-numeric-or-missing-duration-is-invalid
  (is (not (r/duration-valid? nil)))
  (is (not (r/duration-valid? "45"))))

;; ----------------------------- register-certification -----------------------------

(deftest certification-is-a-draft-not-a-signed-mark
  (let [result (r/register-certification "cert-1" "tank-001" :certify 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest certification-assigns-certification-number
  (let [result (r/register-certification "cert-1" "tank-001" :fail 7)]
    (is (= (get result "certification_number") "CERT-000007"))
    (is (= (get-in result ["record" "certification_id"]) "cert-1"))
    (is (= (get-in result ["record" "equipment_id"]) "tank-001"))
    (is (= (get-in result ["record" "decision"]) "fail"))
    (is (= (get-in result ["record" "kind"]) "certification-decision-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest certification-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-certification "" "tank-001" :certify 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-certification "cert-1" "" :certify 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-certification "cert-1" "tank-001" :maybe 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-certification "cert-1" "tank-001" :certify -1))))

;; ----------------------------- register-recleaning -----------------------------

(deftest recleaning-is-a-draft-not-a-real-dispatch
  (let [result (r/register-recleaning "rcl-1" "washer-002" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest recleaning-assigns-recleaning-number
  (let [result (r/register-recleaning "rcl-1" "washer-002" 7)]
    (is (= (get result "recleaning_number") "RCL-000007"))
    (is (= (get-in result ["record" "recleaning_id"]) "rcl-1"))
    (is (= (get-in result ["record" "kind"]) "recleaning-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest recleaning-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recleaning "" "washer-002" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recleaning "rcl-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recleaning "rcl-1" "washer-002" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-certification "cert-1" "tank-001" :certify 0)
        hist (r/append [] c1)
        c2 (r/register-certification "cert-2" "tank-001" :fail 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "CERT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "CERT-000001" (get-in hist2 [1 "record_id"])))))
