(ns cleancert.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean contractor through
  intake -> certification scan (escalate/approve) -> safety-concern flag
  (escalate/approve) -> re-clean scheduling (escalate/approve), then
  shows HARD-hold scenarios: a mis-wired request whose own `:effect` is
  not `:propose`, an unrecognized op, a certification scan against an
  UNVERIFIED/unregistered vessel, a certification scan with an
  implausible residue-ppm sensor reading, a re-clean scheduled against
  equipment with no on-file failed certification, a proposal that tries
  to ACTUATE cleaning equipment directly (permanently blocked, no
  override), a double-schedule of the same re-clean window, a
  cleaning-job patch with an implausible duration, and a proposal that
  tries to self-issue a certification through a side channel
  (permanently blocked, no override).

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario -- the
  same 'exercise the failure mode directly, never only via a happy-path
  actuation' discipline this fleet establishes."
  (:require [langgraph.graph :as g]
            [cleancert.store :as store]
            [cleancert.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :cleaning-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-cleaning-completion job-001 on tank-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-cleaning-completion :effect :propose :subject "job-001"
                        :patch {:equipment-id "tank-001" :method :chemical-wash
                                :duration-minutes 45.0 :chemicals-used ["degreaser-a"]}}
                       coordinator))

    (println "== certification-scan cert-1 on tank-001 (residue 10ppm < threshold 50ppm -> :certify, escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :certification-scan :effect :propose :subject "cert-1"
                       :value {:equipment-id "tank-001" :residue-ppm 10.0}}
                      coordinator)]
      (println r)
      (println "-- human contractor-operator approves --")
      (println (approve! actor "t2")))

    (println "== flag-safety-concern concern-1 on tank-001 (confined-space entry, always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:equipment-id "tank-001" :hazard-type :confined-space-entry
                               :severity :high :description "タンク内部への進入が必要、酸欠リスク"}}
                      coordinator)]
      (println r)
      (println "-- human contractor-operator approves --")
      (println (approve! actor "t3")))

    (println "== schedule-recleaning rcl-1 on washer-002 (on-file FAILED certification -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :schedule-recleaning :effect :propose :subject "rcl-1"
                       :value {:equipment-id "washer-002" :scheduled-date "2026-08-01"
                               :actuate-equipment? false}}
                      coordinator)]
      (println r)
      (println "-- human contractor-operator approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-cleaning-completion with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-cleaning-completion :effect :direct-write :subject "job-001"
                        :patch {:equipment-id "tank-001" :method :chemical-wash}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :dispense-solvent-directly :effect :propose :subject "tank-001"}
                       coordinator))

    (println "== certification-scan cert-2 on vessel-003 (UNVERIFIED/unregistered confined-space vessel -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :certification-scan :effect :propose :subject "cert-2"
                        :value {:equipment-id "vessel-003" :residue-ppm 5.0}}
                       coordinator))

    (println "== certification-scan cert-3 on tank-001 with an implausible residue-ppm reading -> HARD hold ==")
    (println (exec-op actor "t8"
                       {:op :certification-scan :effect :propose :subject "cert-3"
                        :value {:equipment-id "tank-001" :residue-ppm 99999999.0}}
                       coordinator))

    (println "== schedule-recleaning rcl-2 on tank-001 (no on-file FAILED certification -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :schedule-recleaning :effect :propose :subject "rcl-2"
                        :value {:equipment-id "tank-001" :scheduled-date "2026-08-01"
                                :actuate-equipment? false}}
                       coordinator))

    (println "== schedule-recleaning rcl-3 on washer-002 with :actuate-equipment? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-recleaning :effect :propose :subject "rcl-3"
                        :value {:equipment-id "washer-002" :scheduled-date "2026-09-01"
                                :actuate-equipment? true}}
                       coordinator))

    (println "== schedule-recleaning rcl-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :schedule-recleaning :effect :propose :subject "rcl-1"
                        :value {:equipment-id "washer-002" :scheduled-date "2026-08-01"
                                :actuate-equipment? false}}
                       coordinator))

    (println "== log-cleaning-completion job-002 on tank-001 with an implausible duration -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-cleaning-completion :effect :propose :subject "job-002"
                        :patch {:equipment-id "tank-001" :duration-minutes 999999.0}}
                       coordinator))

    (println "== log-cleaning-completion job-003 on tank-001 attempting to self-issue a certification via a side channel -> HARD hold, PERMANENT ==")
    (println (exec-op actor "t13"
                       {:op :log-cleaning-completion :effect :propose :subject "job-003"
                        :patch {:equipment-id "tank-001" :certified? true}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft certification records ==")
    (doseq [r (store/certification-history db)] (println r))

    (println "\n== draft recleaning records ==")
    (doseq [r (store/recleaning-history db)] (println r))))
