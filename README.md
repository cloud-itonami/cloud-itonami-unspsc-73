# cloud-itonami-unspsc-73

Open UNSPSC Blueprint (implemented actor) for **UNSPSC segment 73**:
Industrial Production and Manufacturing Services.

This repository publishes a forkable OSS business for an independent
industrial cleaning and certification contractor: a cleaning/inspection
robot performs equipment cleaning and post-clean certification scans under
a governor-gated actor, so an independent contractor keeps auditable
cleaning and certification records instead of renting a closed
facility-services SaaS.

**Maturity: `:implemented`** — CleaningAdvisor ⊣ Industrial Cleaning
Governor as a langgraph-clj StateGraph (`intake → advise → govern →
decide → commit/hold`, human-approval interrupt), modeled on
`cloud-itonami-isic-3091`'s motorcycle-plant-operations actor (the
closest robotics-gated, propose-only-coordination sibling shape). All
source `.cljc` (portable to JVM / ClojureScript / GraalVM), no JVM-only
interop. 83 tests / 213 assertions green, `clj-kondo` 0 errors / 0
warnings.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a cleaning/inspection robot
performs equipment/surface cleaning and a post-clean sensor scan
(residue, contamination) under an actor that proposes a
certify/re-clean decision and an independent **Industrial Cleaning
Governor** that gates it. The governor never dispatches hardware itself;
`:safety-critical` findings — confined-space entry and hazardous
chemical residue, the two hazard categories OSHA's General Industry
standards single out (29 CFR 1910.146 Permit-Required Confined Spaces;
29 CFR 1910.1200 Hazard Communication) — always require human sign-off,
regardless of the advisor's own confidence.

## What this actor does

Proposes **cleaning-and-certification back-office coordination**, not
equipment operation:
- `:log-cleaning-completion` — completed cleaning-job data logging
  (method/duration/chemicals-used against a piece of equipment;
  administrative, not an operational decision)
- `:certification-scan` — a certify/fail decision drafted from a
  robot's post-clean residue-ppm sensor reading against the equipment's
  own registered threshold
- `:flag-safety-concern` — surface a confined-space-entry or
  hazardous-chemical-residue concern (always escalates)
- `:schedule-recleaning` — propose a re-clean window against equipment
  with an on-file FAILED certification

## What this actor does NOT do

- Does NOT actuate any wash/spray/dispense/decontaminate system
  directly — the robot cleans, this actor only logs/certifies/schedules
- Does NOT self-issue a real, signed cleaning-certification mark (every
  certification this actor produces is an unsigned DRAFT; a human
  contractor-operator's sign-off is what actually certifies)
- Does NOT let a proposal's own self-reported certify/fail claim stand
  uncontested — the governor independently recomputes it from the raw
  residue-ppm reading every time
- ONLY proposes/coordinates back-office records; all actuation and
  certification signing requires explicit human authority

## Core Contract

```text
equipment/site cleaning request + prior certification history
        |
        v
Cleaning Advisor -> Industrial Cleaning Governor -> certify, or human sign-off
        |
        v
robot cleaning actions (gated) + certification record + audit ledger
```

Implemented faithfully: no automated scan can certify equipment the
governor would refuse, suppress a certification record, or downgrade a
contamination finding without governor approval and audit evidence.

## Implementation

Portable `.cljc` namespaces under `src/cleancert/`:

- `registry` — pure domain logic: equipment verified/registered ground
  truth, the independent certify/fail verdict (residue-ppm vs a piece
  of equipment's own registered threshold), reading-plausibility
  validation, draft certification/recleaning-schedule record
  construction.
- `store` — SSoT behind a `Store` protocol (`MemStore`); equipment,
  cleaning jobs, certification history, recleaning schedules, safety
  concerns and the audit ledger all live here.
- `advisor` — the contained intelligence node (`mock-advisor` default,
  `llm-advisor` swap-in); returns proposals only, grounded only in
  store facts.
- `governor` — the independent Industrial Cleaning Governor (13 HARD +
  1 SOFT check).
- `phase` — 0→3 staged rollout; only `:log-cleaning-completion` is ever
  auto-eligible, and only at phase 3.
- `operation` — the langgraph-clj StateGraph (1 run = 1 coordination
  request); `sim` drives the offline demo.

`clojure -M:dev:test` (83 tests, 213 assertions) and `clojure -M:lint`
(clj-kondo, 0 errors). `clojure -M:dev:run` drives the demo end to end,
including every HARD-hold scenario.

## Capability layer

Resolves via [`kotoba-lang/unspsc`](https://github.com/kotoba-lang/unspsc)
(UNSPSC segment `73`). Required capabilities:

- :robotics
- :telemetry
- :optimization
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
