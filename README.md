# cloud-itonami-unspsc-73

Open UNSPSC Blueprint for **UNSPSC segment 73**: Industrial Production and
Manufacturing Services.

This repository designs a forkable OSS business for an independent
industrial cleaning and certification contractor: a cleaning/inspection
robot performs equipment cleaning and post-clean certification scans under
a governor-gated actor, so an independent contractor keeps auditable
cleaning and certification records instead of renting a closed
facility-services SaaS.

**Status: design blueprint, no code implemented yet.** This repository
has zero files under `src/` and no `test/` directory — the Cleaning
Advisor and Industrial Cleaning Governor described below do not exist
in code. It is not (yet) a governed Advisor⊣Governor actuation actor;
the Core Contract section specifies what that pipeline is intended to
enforce once built, not current behavior. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`), and the `cloud-itonami-assoc-*` /
`cloud-itonami-municipality-*` / `cloud-itonami-lei-*` repos for this
fleet's honest not-an-actuation-actor disclaimer pattern.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a cleaning/inspection robot
performs equipment/surface cleaning and a post-clean sensor scan
(residue, contamination) under an actor that proposes a
certify/re-clean decision and an independent **Industrial Cleaning
Governor** that gates it. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions (confined-space entry, hazardous
chemical residue) require human sign-off.

## Core Contract (design intent — not yet implemented)

```text
equipment/site cleaning request + prior certification history
        |
        v
Cleaning Advisor -> Industrial Cleaning Governor -> certify, or human sign-off
        |
        v
robot cleaning actions (gated) + certification record + audit ledger
```

**No code exists yet in this repo** — no `src/`, no `test/`, only this
design document plus `blueprint.edn` and `docs/`. Once built, no
automated scan will be able to certify equipment the governor would
refuse, suppress a certification record, or downgrade a contamination
finding without governor approval and audit evidence — but none of
that is enforced today.

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
