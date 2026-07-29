# Dev/v10 external research prompt pack

> Prepared: 2026-07-22
> Target: Tracker Android 10.0.0, unreleased Room schema 40
> Audience: internet-enabled research agents with **no local checkout**, using immutable GitHub links

This directory contains independent, copy-paste-ready prompts for the design work deferred from the
dev/v10 adversarial integration. The prompts include a verified architecture dossier and direct links
to the public repository at one immutable commit. Researchers are expected to inspect the linked code
through GitHub, follow actual call sites and tests, and distinguish verified code behavior from
external platform or algorithm claims.

Completed reports are reviewed locally and folded into the cumulative
[research synthesis status](RESEARCH_SYNTHESIS_STATUS.md). Keep giving packs 01–06 to researchers
independently; the synthesis is for local planning and the final pack-07 decision pass, not context to
bias researchers who have not written their report yet.

## 2026-07-22 integration-record correction

The [V10 integration report](../DEV_V10_INTEGRATION_REPORT.md) now carries a
post-integration erratum: `BUILDING`/`READY` status does not keep a `BUILDING`
region invisible when another `READY` region keeps the global consumer gate open.
The resulting partial-publication and shared-road ownership risks remain
unresolved. [Research pack 01](01_OSM_MULTI_REGION_STORAGE_AND_PUBLICATION.md)
is the designated design follow-up and is a release gate if V10 ships multiple
retained regions.

## Immutable repository target

All code research must target commit `fb2684d53ea2900780feb3438e736fbcd1b26c3f`:

- [Commit](https://github.com/adsamcik/Tracker-Android/commit/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Repository tree](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f)

Do not use the moving `dev/v10` branch or default branch as evidence. When reporting a code finding,
link directly to the relevant file and tight line range at the immutable commit, trace the reachable
execution path, state the consequence, and name the test that would prove a correction. If GitHub
cannot expose generated Room code or runtime behavior, identify that boundary precisely.

## Research packs

| Pack | Question | Primary decision enabled |
|---|---|---|
| [01](01_OSM_MULTI_REGION_STORAGE_AND_PUBLICATION.md) | OSM multi-region ownership, atomic publication, deletion, and reindexing | Safe schema/publication architecture for the next Room migration |
| [02](02_OSM_PBF_RESOURCE_POLICY.md) | Adversarial PBF memory, CPU, disk, and parser limits | Enforceable import resource policy and rejection behavior |
| [03](03_GEOSPATIAL_ANTIMERIDIAN_AND_COORDINATE_CONTRACT.md) | Antimeridian-safe local geometry and imported-coordinate validation | Shared geospatial invariants and regression vectors |
| [04](04_OSM_MATCHER_AND_COMPLIANCE_VALIDATION.md) | Real end-to-end OSM tests, one-way matching, turn restrictions, and compliance accuracy | Evidence plan and matcher feature order |
| [05](05_ALTITUDE_FUSION_MEASUREMENT_AND_POLICY.md) | Pressure sampling, altitude datum, robust fusion, and restart behavior | Instrumentation contract and field-study plan before algorithm changes |
| [06](06_CADENCE_INDEPENDENT_ROUTE_CLASSIFICATION.md) | Stop/trip segmentation and transport classification under irregular sampling | Cadence-independent model contract and validation protocol |
| [07](07_CROSS_PACK_SYNTHESIS_AND_DECISION.md) | Reconcile the six completed reports | Release gates, ADR slate, sequencing, and explicit defer list |

The cumulative [implementation-agent prompt](IMPLEMENTATION_AGENT_PROMPT.md) is maintained
separately from the independent research packs. It contains only work that has survived local
review and can proceed without inventing field-derived tuning or unmeasured parser budgets. Its
delivery contract requires all completed, verified in-scope output—including these planning
artifacts—to be committed locally on `dev/v10` in explicit, reviewable commits; pushing remains a
separate authorization.

Pack 03 has now been received and locally reviewed. Its shared checked-coordinate, canonical
longitude, circular-interval, bounded-grid, and atomic optional-coordinate contracts are accepted.
Its proposed GeographicLib-backed production evaluator, exact minimizer, R-tree representation, and
numerical fallback guard remain measurement/design inputs rather than implementation instructions.

Packs 01–06 have now been received and locally reviewed (Pack 01 has two independent answers).
Pack 06 accepts a lossless timed observation, deterministic replay, cadence/batch/gap
transformations, and structural
invariance gates. It does **not** authorize a live time-weighted reducer, reorder window, support
horizons, new thresholds, checkpoint restore, shadow persistence, or HSMM. The implementation prompt
therefore adds only the host-clock determinism fix and V1 evidence/characterization foundation.

Pack 02 confirms that the current untrusted `.osm.pbf` path is not resource-safe. It accepts
fail-closed release containment, an actual-byte-counted private source snapshot, strict PBF framing
and decompression, allocation-before-check tests, one coexistence-aware resource ledger, typed
permanent failures, and crash reconciliation. It does **not** supply real-device coefficients or
choose the final graph representation; those still require its benchmark protocol. All independent
inputs are now ready for Pack 07. Pack 07 is a decision pass, not another missing research domain.

## Recommended execution

1. Preserve the unedited reports from packs 01–06 and their source ledgers. The independent pass is
   complete; do not rewrite the reports to make their conclusions agree.
2. Give every unedited report produced for packs 01–06 to a final researcher together with pack 07.
3. Keep every source URL, immutable code link, and evidence ledger. The final engineering review should
   be able to open the exact code and primary external source supporting each consequential claim.
4. Validate generated-code behavior, physical-device behavior, and final edit scope locally before
   implementation. GitHub inspection can establish local call reachability, but it cannot replace
   compilation, migration execution, parser fuzzing, or device measurement.

## Global quality bar

Every pack repeats its own evidence rules so it can be sent alone. Across the whole exercise:

- Browsing is mandatory. Memory-only answers are unacceptable.
- Prefer specifications, official documentation, upstream source code at the relevant version,
  standards, and peer-reviewed papers. Blog posts and forum answers are discovery aids, not primary
  authority.
- Attach citations directly to claims. A bibliography without claim-level citations is insufficient.
- Separate **verified external fact**, **verified repository fact**, **inference**, **proposal**, and
  **unknown requiring generated-code/local/device validation**.
- Record source version/date, retrieval date, and applicability limits. Resolve conflicting sources
  explicitly instead of silently selecting one.
- Do not fabricate benchmarks, device behavior, OSM coverage, accuracy, or repository state.
- Recommendations must include rejected alternatives, failure modes, costs, and a test or measurement
  that could falsify the recommendation.
- A useful report ends in decisions and evidence gaps, not a generic tutorial.

## Local facts shared by the packs

These were verified in the repository at dev/v10 tip `fb2684d53`:

- Single-user, local-first Android application; no server is involved in these pipelines.
- Version 10.0.0, version code 400; database schema 40 is unreleased.
- Kotlin/JVM 17; minimum Android API 26; target/compile API 37.
- Room 2.8.4. OSM parsing declares `org.openstreetmap.pbf:osmpbf:1.6.1`. That artifact's POM declares
  protobuf-java 4.33.2, while Tracker declares 4.35.1 only in the app debug configuration. Do not
  state one runtime protobuf version until `dependencyInsight` verifies each shipped/tested
  configuration and a build assertion pins the result.
- The current full-repository test log is green, but a green suite does not cover the deferred design
  risks described in these prompts.

The links are intentionally commit-pinned. Researchers should use the dossier to orient themselves,
then verify or correct it from the linked implementation rather than treating it as unquestionable.
