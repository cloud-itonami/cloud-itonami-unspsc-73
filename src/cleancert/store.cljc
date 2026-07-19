(ns cleancert.store
  "SSoT for the industrial-cleaning-and-certification back-office
  coordination actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite -- the same seam every `cloud-itonami-*` actor in
  this fleet uses.

  Scope note: like its siblings (`cloud-itonami-isic-3091`'s own
  `motomfg.store`, `cloud-itonami-isic-2599`'s own
  `metalfabmfg.store`), this build ships a single `MemStore` backend
  only (atom of EDN) -- the deterministic default for dev/tests/demo,
  no deps.

  Three kinds of entity live here:
    - `equipment`      -- a cleanable unit's own record (tank, washer,
                           vessel, line). `:verified?`/`:registered?`
                           track whether it has actually been inspected/
                           commissioned and is on file; `:certification-
                           threshold-ppm` is the registered residue
                           ceiling a post-clean scan is judged against;
                           `:last-certified-status` (`:certify`/`:fail`/
                           nil) is the equipment's own ground-truth
                           cumulative certification state.
    - `recleanings`     -- a scheduled re-clean-window DRAFT against a
                           piece of equipment (`cleancert.registry`'s
                           `register-recleaning`). Dedicated
                           `:scheduled?` double-schedule guard (never a
                           `:status` value -- the same discipline every
                           prior governor's guards establish).
    - `cleaning-jobs`   -- a logged completed-cleaning-job record
                           (method/duration/chemicals-used), keyed by
                           job id.

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which job was logged, which
  certification decision was made against a verified/registered
  equipment unit and at what independently-recomputed residue-ppm
  verdict, which re-clean was scheduled against equipment with an
  on-file failed certification, which safety concern was flagged' is
  always a query over an immutable log -- the audit trail a contractor
  or downstream client trusting this coordinator needs."
  (:require [cleancert.registry :as registry]))

(defprotocol Store
  (equipment-unit [s id])
  (all-equipment [s])
  (cleaning-job [s id])
  (certification [s equipment-id] "the equipment's own latest committed certification record, or nil")
  (recleaning [s id])
  (safety-concerns [s] "the append-only safety-concern log")
  (ledger [s])
  (certification-history [s] "the append-only certification-decision history (cleancert.registry drafts)")
  (recleaning-history [s] "the append-only recleaning-schedule history (cleancert.registry drafts)")
  (next-certification-sequence [s] "next certification-number sequence")
  (next-recleaning-sequence [s] "next recleaning-number sequence")
  (recleaning-already-scheduled? [s recleaning-id] "has this re-clean window already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-equipment [s equipment] "replace/seed the equipment directory (map id->equipment)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-equipment []
  {"tank-001" {:id "tank-001" :kind :degreasing-tank
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

;; ----------------------------- shared commit logic -----------------------------

(defn- decide-certification!
  "Backend-agnostic `:certification/decide` -- drafts the
  certification-decision record via `cleancert.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s certification-id equipment-id decision]
  (let [seq-n (next-certification-sequence s)
        result (registry/register-certification certification-id equipment-id decision seq-n)]
    {:result result
     :patch {:decision decision
             :certification-number (get result "certification_number")}}))

(defn- schedule-recleaning!
  "Backend-agnostic `:recleaning/schedule` -- drafts the
  recleaning-schedule record via `cleancert.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s recleaning-id equipment-id]
  (let [seq-n (next-recleaning-sequence s)
        result (registry/register-recleaning recleaning-id equipment-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :recleaning-number (get result "recleaning_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (equipment-unit [_ id] (get-in @a [:equipment id]))
  (all-equipment [_] (sort-by :id (vals (:equipment @a))))
  (cleaning-job [_ id] (get-in @a [:cleaning-jobs id]))
  (certification [_ equipment-id] (get-in @a [:certification-by-equipment equipment-id]))
  (recleaning [_ id] (get-in @a [:recleanings id]))
  (safety-concerns [_] (:safety-concerns @a))
  (ledger [_] (:ledger @a))
  (certification-history [_] (:certification-history @a))
  (recleaning-history [_] (:recleaning-history @a))
  (next-certification-sequence [_] (:certification-sequence @a 0))
  (next-recleaning-sequence [_] (:recleaning-sequence @a 0))
  (recleaning-already-scheduled? [_ recleaning-id]
    (boolean (get-in @a [:recleanings recleaning-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :cleaning-job/upsert)
      (swap! a update-in [:cleaning-jobs (first path)] merge (assoc value :id (first path)))

      (= effect :certification/decide)
      (let [certification-id (first path)
            equipment-id (:equipment-id value)
            decision (:decision value)
            {:keys [result patch]} (decide-certification! s certification-id equipment-id decision)]
        (swap! a (fn [state]
                   (-> state
                       (update :certification-sequence (fnil inc 0))
                       (update :certification-history registry/append result)
                       (assoc-in [:certification-by-equipment equipment-id] (merge value patch))
                       (assoc-in [:equipment equipment-id :last-certified-status] decision))))
        result)

      (= effect :safety-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :safety-concerns conj concern)
        concern)

      (= effect :recleaning/schedule)
      (let [recleaning-id (first path)
            equipment-id (:equipment-id value)
            {:keys [result patch]} (schedule-recleaning! s recleaning-id equipment-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :recleaning-sequence (fnil inc 0))
                       (update-in [:recleanings recleaning-id] merge (assoc value :id recleaning-id) patch)
                       (update :recleaning-history registry/append result))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above.
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-equipment [s equipment] (when (seq equipment) (swap! a assoc :equipment equipment)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:equipment {} :cleaning-jobs {} :recleanings {}
                      :records {} :safety-concerns []
                      :certification-by-equipment {}
                      :ledger [] :certification-sequence 0 :certification-history []
                      :recleaning-sequence 0 :recleaning-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained equipment set --
  one verified+registered degreasing tank never yet scanned (clean
  certification-scan happy path, residue-ppm below its own threshold),
  one verified+registered parts washer with an on-file FAILED
  certification (schedule-recleaning happy path), one UNVERIFIED/
  unregistered confined-space vessel (blocks any certification scan or
  re-clean scheduling proposed against it) -- so the actor + demo +
  tests run offline. Returns `s` (thread-friendly with `->`)."
  [s]
  (with-equipment s (sample-equipment))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
