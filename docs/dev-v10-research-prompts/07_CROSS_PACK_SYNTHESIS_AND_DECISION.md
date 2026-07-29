# Research prompt 07: cross-pack synthesis and engineering decision preparation

You are the principal research integrator for Tracker Android V10. You have internet access and no
local checkout. Inspect the public repository at the immutable target below. You will also receive six
independent reports produced from the companion prompts:

1. OSM multi-region storage/publication/reindexing.
2. OSM PBF resource policy.
3. Antimeridian geometry and coordinate validation.
4. OSM matcher/compliance validation.
5. Altitude fusion measurement and policy.
6. Cadence-independent route classification.

Your job is not to summarize them sequentially. Reconcile conflicting evidence, independently verify
the highest-impact claims, identify hidden dependencies, and prepare a conservative decision package
for engineers who do have repository access.

If one or more reports are absent, clearly mark the synthesis incomplete and continue only where
evidence is sufficient. Never invent the missing report's conclusions.

## Immutable repository target

Review exactly commit `fb2684d53ea2900780feb3438e736fbcd1b26c3f`:

- [Commit](https://github.com/adsamcik/Tracker-Android/commit/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Tree](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f)

The six source reports should contain prompt-specific immutable code links. Re-open the code behind
every proposed release blocker and architecture decision; follow call sites and tests far enough to
establish reachability. Never use a moving branch as code evidence. Each consolidated code finding
must link a tight line range at this commit, show the execution path, assign `P0`–`P3`, give the
smallest safe correction, and name the proving test.

## Mandatory evidence and integrity rules

- Browse the primary sources behind every proposed P0/P1 decision and every claim on which a schema,
  security limit, or field-study design depends. Do not trust a citation merely because another agent
  supplied it.
- Prefer official specifications/documentation/source and peer-reviewed research. Record the exact
  version/date and applicability.
- Preserve disagreement. Do not use majority vote or average incompatible recommendations.
- Distinguish:
  - **verified external fact**;
  - **verified commit-pinned repository fact**;
  - **unverified or corrected Tracker dossier claim**;
  - **research inference**;
  - **engineering proposal**;
  - **unknown requiring generated-code or local execution**;
  - **unknown requiring real-device/field measurement**;
  - **product decision**.
- A recommendation must name its failure modes, rejected alternative, reversible fallback, and
  acceptance evidence.
- Do not claim to have executed tests, migrated a database, parsed a PBF, or collected device traces.
  GitHub inspection is expected, but generated/runtime behavior must remain explicitly unverified.
- Avoid false precision in time estimates. Use relative scope (`S`, `M`, `L`, `XL`) plus the drivers of
  uncertainty.

## Verified Tracker baseline

The baseline was verified locally at the target commit, but the synthesis agent must confirm every
claim that affects a decision and explicitly correct discrepancies found in the linked code.

- Single-user, local-first Android application, version 10.0.0; API 26 minimum and API 37 target.
- Kotlin/JVM 17, Room 2.8.4, unreleased database schema 40.
- OSM parsing declares openstreetmap PBF Java 1.6.1. Its POM declares protobuf-java 4.33.2, while
  Tracker's catalog declares 4.35.1 and the direct app declaration appears debug-only. Treat the
  exact debug/release/test runtime graph as unresolved until configuration-specific dependency
  evidence proves it; do not repeat 4.35.1 as a production fact merely from the catalog.
- A large adversarial integration pass has already landed broad fixes across replay safety, lifecycle,
  geospatial search, maps, activity detectors, import/export, OSM, route processing, altitude, and
  retention. The current full-repository test log is green.
- The next plan should be bounded. It must not reopen already-corrected systems without new evidence.

Confirmed remaining/high-priority context:

1. **OSM multi-region publication/ownership**
   - Global OSM way ID is the current primary key; each way has only one owning import.
   - Overlap plus replace insertion can transfer ownership.
   - Candidate queries are not status-filtered. If A is READY while B is BUILDING, readers can observe
     B's partial rows; B failure/deletion can remove shared rows A still needs.
   - Multi-region retention and per-region deletion are user-visible.
2. **PBF resources**
   - Input is capped at 100 MiB and distinct referenced nodes have an adaptive cap.
   - Total buffered driveable ways and total references are not capped, permitting repeated-reference
     memory growth.
   - The supplied Pack 02 report alleges that exact `osmpbf:1.6.1` code allocates zlib output from
     input-controlled `raw_size`, performs incomplete/assertion-dependent inflation checks, accepts
     truncation as normal EOF, and materializes generated protobuf object graphs before Tracker
     callbacks. Re-open the exact upstream tag/artifact source and verify each claim.
   - Tracker trusts provider-reported size instead of counting bytes, its cancellation flag is not
     connected to coroutine cancellation, and way-count batching can amplify geometry/cell/Room/WAL
     output. Verify the complete path and separate format limits from product/dynamic budgets.
3. **Bounded correctness**
   - A sibling 50 m speed-snap path subtracts E7 longitudes in 32-bit arithmetic before widening.
   - Wi-Fi/cell JSON coordinates are finite-checked but not range-checked as a valid pair.
4. **Matcher tests**
   - No checked-in PBF fixture covers import through real matcher and compliance.
   - One-way direction is not scored; turn restrictions are not imported.
5. **Altitude**
   - Raw pressure event timing/variance and fusion innovations are not logged.
   - Datum fallback is silent; outlier rejection and filter-state persistence are undecided.
6. **Route classification**
   - Duration guards exist, but some evidence remains per-sample/cadence-sensitive.
   - No representative labeled multi-cadence corpus exists.
   - A separate simplification/compression pipeline is largely unwired and out of scope.

Cross-report boundaries that the final synthesis must preserve:

- Pack 01 selects import-scoped immutable ownership semantically, but neither Pack 01 nor Pack 02
  contains the device/database measurements needed to choose surrogate versus composite physical
  keys. Pack 02 supplies a benchmark protocol, not results.
- A strict PBF framing/zlib fix alone does not make the importer safe if generated block graphs,
  retained ways/references, output cells/entities, disk, cancellation, and crash cleanup remain
  unbounded. Treat importer availability and safe re-enablement as separate decisions.
- Pack 03 selects logical coordinate/circular-longitude semantics, not an R-tree or production
  GeographicLib/minimizer. Pack 04 selects future semantic inputs, not matcher thresholds.
- Pack 05 selects altitude datum correctness and evidence requirements, not Q/R, aggregation,
  outlier, calibration, gap, or persistence parameters. Pack 06 selects deterministic evidence and
  invariants, not a live reducer, reorder horizon, thresholds, checkpoint policy, or HSMM.
- Pack 01 reports a potentially affected packaged SQLite 3.49.0 WAL engine. Independently verify the
  exact source ID, official affected/fixed range, ABI packaging, and reachability before assigning
  the release classification; runtime telemetry is evidence, not a fix.

## Synthesis tasks

### A. Audit source quality and conflicts

For each report:

- identify its five most consequential claims;
- check whether each cited source actually supports the claim;
- flag secondary-source dependence, version mismatch, extrapolation, or missing applicability context;
- independently verify at least every claim used in a release-blocking decision;
- record unresolved disagreements and the experiment or repository inspection that would settle them.

Reject recommendations that are attractive but unsupported. If a source is inaccessible, say so and
reduce confidence rather than repeating the claim.

### B. Build the dependency graph

Determine ordering constraints such as:

- whether OSM end-to-end fixtures must precede ownership migration or can be developed alongside it;
- which candidate-query identities depend on the ownership schema;
- whether antimeridian helpers should land before matcher fixture or feature work;
- whether PBF resource limits depend on disk-staging/schema decisions;
- whether the current PBF entry point must be centrally disabled before any parser refactor, and
  exactly which full-pipeline evidence is required before re-enablement;
- whether an actual-byte-counted private source snapshot should precede two-pass parsing and how its
  disk/journal cleanup shares Pack 01's authority/reconciliation protocol;
- whether safe primitive streaming or wire preflight must be designed alongside Pack 01/03/04
  semantic retention so memory optimization does not discard OSM version/node/direction/limit data;
- whether reindex gating is a prerequisite for safe schema migration;
- whether one-way/restriction experiments require the new ownership model;
- which altitude instrumentation is required before any fusion decision;
- which route trace schema can share clock/privacy infrastructure with altitude research.

Show critical paths, opportunities for independent work, and changes that would create throwaway code
if done too early.

### C. Define release gates

Classify each work item as:

- **V10 release blocker**;
- **V10 hardening with high ROI but not a blocker**;
- **instrument now, decide after data**;
- **post-V10 product improvement**;
- **opportunistic cleanup**;
- **explicitly do not pursue**.

For every proposed blocker, state:

- concrete reachable user or security consequence;
- evidence and confidence;
- smallest safe resolution;
- feature-scope fallback if the resolution cannot land (for example constraining or disabling
  multi-region OSM rather than shipping unsafe semantics);
- acceptance tests/measurements;
- why deferral is or is not responsible.

Do not turn speculative algorithm accuracy into a blocker without evidence. Conversely, do not
downgrade reachable data-integrity or resource-exhaustion failures because imported OSM can be
re-created.

For PBF specifically, decide all three independently: (1) immediate release containment, (2) the
architecture and proving tests required for safe re-enablement, and (3) dynamic numbers that remain
blocked on real-device/held-out workloads. Do not approve guessed heap coefficients, total counts,
batch sizes, timeouts, cancellation intervals, disk reserves, or graph-key representation.

### D. Prepare architecture decision records

Draft concise ADR outlines for decisions that are ready. Likely candidates include:

- OSM way ownership and import generation/publication;
- cell-index rebuild visibility (global gate versus shadow generation);
- PBF combined resource budget and rejection/staging policy;
- canonical longitude/coordinate contract;
- altitude research-event schema and datum provenance;
- cadence-independent evidence interval semantics.

Each ADR outline must contain context, decision, considered alternatives, consequences, invariants,
rollback/fallback, validation, and still-open questions. Do not mark an ADR accepted when field evidence
is explicitly missing; mark it `proposed` or `experimental`.

For the PBF ADR, separate fixed format/product policy from measured device policy: `<64 KiB`
BlobHeader and `<32 MiB` decoded Blob are alleged format hard limits; `<16 MiB` is a compatibility
recommendation; 100 MiB source, 2,000 refs per retained way, and 20,000 cells per way are proposed
Tracker product ceilings. Independently verify their authorities and never claim independent maxima
form a safe combined heap/disk envelope.

### E. Produce an implementation-independent test program

Unify duplicate test recommendations into a minimal suite with maximum fault coverage:

- deterministic unit/property/fuzz tests;
- Room migration and foreign-key tests;
- failure injection and process-death/retry tests;
- generated PBF parser and full-pipeline fixtures;
- Android resource benchmarks;
- trace replay/resampling tests;
- real-device altitude and classification studies.

For each test layer, name the invariant, oracle, fixture/data requirement, and which decision it gates.
Separate CI tests from long-running benchmarks and field studies.

Require valid positive PBF conformance fixtures (`OSMHeader` before homogeneous `OSMData`) plus
malformed/adversarial fixtures and boundary-minus/exact/plus cases. Include an allocation/reservation
spy so rejection-before-allocation/write is testable without relying on process OOM. Process kill,
force-stop, retry, reboot, unknown provider size, low disk, and stale-worker authority belong in the
program, not just caught-exception unit tests.

### F. Define measurement and privacy reuse

Determine whether altitude and cadence research can share a versioned encrypted trace envelope,
clock/boot identity, lifecycle markers, device metadata policy, consent, retention, decoder, and
analysis tooling while keeping feature-specific records separate. Identify privacy risks of precise
location, motion/activity, pressure, device sensor metadata, and external truth markers.

Recommend the minimum common research infrastructure that avoids two incompatible trace systems
without expanding production telemetry.

### G. Create the final sequence

Prepare a staged plan that begins with preservation/correction of the integration record and then
orders:

- small bounded fixes;
- security/resource hardening;
- OSM schema/publication correctness;
- E2E test infrastructure;
- evidence collection;
- post-evidence algorithm changes;
- defer/delete items.

Use milestone exit criteria rather than calendar promises. Include stop conditions that prevent a
research stream from consuming engineering time without producing decision-grade evidence.

## Required output

Return one self-contained Markdown decision report in this order:

1. **Executive verdict** — no more than one page; release readiness and top decisions.
2. **Research integrity audit** — consequential claims, citation verification, confidence, conflicts.
3. **Cross-pack dependency graph** — preferably a compact diagram plus explanatory text.
4. **Release-gate matrix** — item, consequence, evidence, classification, fallback, exit criteria.
5. **Recommended architecture decisions** — accepted/proposed/experimental ADR outlines.
6. **Sequenced engineering program** — milestones, prerequisites, exit criteria, stop conditions.
7. **Unified verification program** — CI, migration, failure injection, benchmark, replay, field study.
8. **Common research-data/privacy contract.**
9. **Explicit defer/delete list** — and the trigger that could justify revisiting each item.
10. **Repository-validation checklist** — exact facts local engineers must confirm before coding.
11. **Product decisions needed** — only choices that cannot be resolved technically.
12. **Evidence gaps and commissioned follow-ups** — narrowly scoped, not another broad review.
13. **Consolidated evidence ledger** — deduplicated primary sources with version/date and limitations.

End with three short sections:

- **Start next** — at most five concrete deliverables.
- **Do not start yet** — work blocked on schema, fixtures, or field evidence.
- **Definition of research complete** — conditions under which further browsing is unlikely to change
  the engineering decision.
