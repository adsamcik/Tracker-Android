# Implementation-agent prompt: evidence-independent V10 work after research intake

You are the implementation agent for Tracker Android V10. Work in the local repository and implement
only the evidence-independent corrections and executable contracts described below. Do not infer
permission to redesign adjacent subsystems, tune empirical parameters, push/publish changes, or discard
existing worktree changes.

## Repository and authority

- Repository: `https://github.com/adsamcik/Tracker-Android`
- Immutable review baseline: `fb2684d53ea2900780feb3438e736fbcd1b26c3f`
- Required delivery branch: `dev/v10`. Confirm it with `git branch --show-current` before editing.
  If the worktree is on another branch, do not switch a dirty worktree or create a side branch;
  stop and ask for the correct `dev/v10` worktree.
- First read the repository's `AGENTS.md`, then:
  - `docs/DEV_V10_INTEGRATION_REPORT.md`
  - `docs/dev-v10-research-prompts/RESEARCH_SYNTHESIS_STATUS.md`
  - the current git status and diff.
- Existing modified or untracked files belong to the user or another agent. Preserve them. If an
  in-scope file is already modified, understand and retain those changes rather than replacing the
  file wholesale. The user has explicitly authorized local commits of the completed, verified work
  to `dev/v10`; that authorization does not extend to unrelated pre-existing files, pushing, opening
  a PR, resetting, cleaning, or checking out over changes.
- `docs/DEV_V10_INTEGRATION_REPORT.md` and the complete
  `docs/dev-v10-research-prompts/` directory, including this prompt, are intentional in-scope
  output. If they are still untracked at task start, review and include them in a dedicated
  documentation/planning commit rather than leaving them behind. Do not infer that another
  arbitrary untracked file belongs to this task.
- Repository behavior and tests outrank this prompt if the working copy has deliberately evolved
  since the immutable baseline. Report material divergence instead of silently forcing an obsolete
  call shape.

## Outcome

Land the smallest coherent vertical slices that are already justified without field tuning:

1. fail-closed containment of the current untrusted `.osm.pbf` importer, plus valid conformance
   fixtures and dependency/error-contract groundwork for a separately reviewed safe intake;
2. a shared checked-coordinate/circular-longitude foundation and the remaining bounded Pack-03
   correctness fixes;
3. altitude datum correctness and explicit conversion provenance;
4. a common deterministic evidence/replay foundation that supports altitude and segmentation
   without collecting unencrypted sensitive traces;
5. a bounded segmentation-determinism slice: remove host-clock replay dependence, preserve a
   canonical timed observation, and characterize V1 under cadence/batch/gap transformations without
   replacing its user-facing rules;
6. only after the primary slices are green, the bounded honest-compliance corrections already
   selected by Pack 04, if they do not conflict with concurrent work.

All six independent research areas have been reviewed. Do not start the final OSM graph/storage
redesign or claim that research supplied its measurements. Pack 02 selects a fail-closed resource
architecture and benchmark protocol, but it contains no real-device charge coefficients, dynamic
budgets, or surrogate-versus-composite decision. Pack 06 has selected a replay/invariance contract but has
not selected live support horizons, reorder bounds, evidence rates, thresholds, checkpoint policy,
or an HSMM. Pack 03 has selected the logical longitude/coordinate contract; it has not selected an
R-tree, an exact production geodesic implementation, or the final node/edge/cell schema.

## Workstream P0 — offline PBF fail-closed containment (release-blocking, do first)

### Why this is authorized now

The immutable implementation uses unmodified `osmpbf:1.6.1` `BlockInputStream`, trusts provider
size metadata, retains unbounded ways/references, materializes generated protobuf blocks before
Tracker callbacks, and batches only by way count. Exact cached bytecode also allocates zlib output
from attacker-controlled `raw_size`, performs one `inflate()` call with assertion-only completion,
and treats any `EOFException` as successful completion. The current cancellation flag is never set,
and Worker catches `Throwable` before attempting cleanup/notification. The importer is not safe for
an untrusted user-selected file.

### Required containment

1. Add one centrally owned offline-PBF capability policy. It must default to unavailable in every
   release artifact and must not be a user preference, remote flag, or UI-only check. An explicit
   internal/test build wiring may expose characterization, but production safety must not depend on
   callers remembering to consult it.
2. Enforce the policy at every boundary:
   - the Settings UI does not offer an actionable import control and explains that offline map
     import is temporarily unavailable;
   - `OsmImportController.enqueue` refuses to enqueue while unavailable;
   - `OsmImportWorker.doWork` independently returns a stable typed/unavailable failure before URI
     open, private-file creation, foreground parse work, or database insertion;
   - direct construction/invocation tests prove controller/UI bypass cannot reach the parser.
3. Use a stable error code such as `PBF_IMPORT_UNAVAILABLE`, mapped separately from redacted user
   text. Do not include the URI, provider/display name, exception message, OSM IDs, tags, or content
   in output data, notification reasons, or telemetry.
4. Do not delete the parser or importer code. Keep it gated for focused characterization and for the
   future safe implementation. Remove or correct KDoc/test claims that 100 MiB plus a distinct-node
   cap bounds RSS or makes attacker-supplied PBF safe.
5. Add tests for the release-default capability, UI/controller behavior, direct Worker invocation,
   zero URI opens, zero parser calls, zero OSM rows, zero new private job files, stable error code,
   and redaction. Tests must not depend only on the normal UI path.

### Safe-intake groundwork allowed behind the gate

After containment is green, the following isolated groundwork is worthwhile if it remains unused by
the production Worker and does not displace the primary workstreams:

1. Replace the parser test's positive writer with a valid `OSMHeader` preceding `OSMData`, and use
   homogeneous `PrimitiveGroup`s. Preserve headerless and mixed-group outputs as named negative
   fixtures. Add small deterministic truncated-prefix/header/body, invalid-length, zlib-`raw_size`,
   and repeated-two-node-way fixtures; never create a test that intentionally OOMs the process.
2. Add configuration-specific dependency assertions and a strict direct protobuf constraint after
   running `dependencyInsight` for debug, release, and OSM unit-test/runtime configurations. The
   `osmpbf` POM declares 4.33.2 while Tracker declares 4.35.1 only in app debug; do not guess which
   version to pin without the resolved graphs and compatibility tests.
3. Introduce stable typed parser/resource/storage/unsupported/transient-I/O failure categories and
   redacted mapping. Do not catch `OutOfMemoryError` or broad `Throwable` as routine recovery, and do
   not retry deterministic input or policy failures.

Do **not** re-enable import in this workstream. Do not attach a merely stricter zlib reader to the
old generated-block/unbounded-way/output pipeline and call it safe. Re-enablement requires one
private actual-byte-counted snapshot, strict framing and exact raw/zlib decoding, valid header/
feature/primitive structure, safe primitive streaming or proven wire preflight, one measured
coexistence-aware memory/disk/work ledger, multidimensional output batching, way-plus-cell
transactions under per-import authority, granular cancellation, idempotent kill/reboot cleanup,
allocation-before-check tests, fuzzing, and Pack 02's lowest-device benchmark gate. Treat a custom
protobuf decoder as a separate security-reviewed task.

## Workstream 0 — checked coordinates and circular longitude (bounded, evidence-independent)

### Defects to remove

At the immutable baseline, Tracker has incompatible longitude and bbox rules across the PBF parser,
grid, speed lookup, street resolver, matcher geometry, cache, JSON import, and session distance:

- speed projection subtracts longitude `Int`s before widening;
- the PBF parser uses a narrow dateline interval for cells but persists raw numeric extrema;
- speed lookup uses a fixed longitude padding and fixed 3x3 neighborhood at all latitudes;
- `cellKey(+180°)` differs from `cellKey(-180°)`;
- point-neighbor enumeration accepts arbitrary radii and forms bounds/allocation sizes in `Int`;
- optional Wi-Fi/cell coordinates can carry provenance after both coordinates are cleared;
- PBF quantization and polyline decoding narrow without a complete finite/Earth-range contract;
- private normalizers, deltas, bbox membership, distance constants, and quantizers have drifted.

The current working copy already widens/wraps speed projection and makes JSON coordinates an atomic,
finite, range-checked pair. Preserve those changes. They are incomplete: speed coverage is not yet
latitude-aware, parser persistence still disagrees with consumers, generic ordered-wide bbox repair
is ambiguous, grid/cache identity is not canonical, neighbor allocation is not bounded, and cleared
coordinates currently retain supplied provenance.

### Required shared contract

1. Add pure Kotlin Multiplatform primitives in a cycle-free shared module. Prefer `:core:model`
   unless current module ownership proves a better already-shared home. Keep DAOs, Android APIs,
   logging, OSM product thresholds, and any JVM-only geodesic adapter outside common code.

2. Expose distinct checked-boundary and cyclic APIs. Names may follow repository convention, but the
   types/operations must make these semantics difficult to confuse:

   - checked latitude from E7/degrees: finite and within `[-90°,90°]`;
   - checked longitude from E7/degrees: finite and within inclusive input range
     `[-180°,180°]`, then canonicalized to `[-180°,180°)`;
   - checked coordinate pair: no half-valid state, `+180° -> -180°`, signed zero -> positive zero,
     exact-pole longitude -> zero;
   - arbitrary cyclic longitude normalization in degrees and E7;
   - positive delta `[0°,360°)` and signed shortest delta `[-180°,180°)`, resolving the exact 180°
     tie to `-180°`;
   - wrap-safe short-arc interpolation;
   - `CircularLongitudeInterval(startE7, eastwardSpanE7)` with explicit Point and Full states,
     membership, expansion, minimal-polyline construction under shortest-edge semantics, and
     one/two ordinary-range adapters;
   - conservative angular/E7 bounds for a finite non-negative metre radius at a valid latitude,
     returning Full longitude when the cap reaches a pole.

3. Arbitrary-`Long` normalization must not compute an overflowable `value + HALF_WORLD_E7` before
   `floorMod`. If a specialized helper remains for already-bounded coordinate deltas, make that
   precondition explicit and test its entire permitted range. Never subtract longitude `Int`s before
   widening.

4. Use one checked nearest-E7 quantizer after finite/range validation. The selected V10 behavior is
   Kotlin `roundToInt`: ties toward positive infinity. Do not use `roundToInt`, `round(...).toInt()`,
   `toInt`, clamping, or saturation as a substitute for validation. Keep a compatibility note/test
   for exact half-E7 values; do not add a format version unless exact historic byte reproduction is
   an actual repository requirement.

5. Migrate existing `LatE7`/`LonE7` factories and consumers carefully. Preserve stable serialized
   field names and released-data decoding. Read-time canonicalization is required for spatial
   equality/index/cache operations; a destructive rewrite of historical location rows is not.
   If exact raw import reproduction is needed, retain the raw external value as evidence separately
   from canonical spatial identity.

### Required bounded integrations

1. **PBF/parser and persisted bboxes**

   - Validate parsed degrees before E7 narrowing. Invalid nodes/ways are rejected with a classified,
     testable reason/count; they are not saturated or wrapped.
   - Build one circular interval for a way and use it for both cell membership and persisted bbox
     semantics. In the current unreleased columns, persist the interval's directed endpoints
     (`start`, `end`) so a crossing is explicit. Do not persist raw extrema after indexing a different
     interval.
   - Make speed lookup, street resolver, reindexer, and matcher bbox adapters interpret those fields
     identically. Remove generic `ordered width > 180° means complement` construction. If a temporary
     legacy-development adapter is retained, gate it by an explicit producer/schema version or
     recompute from decoded geometry; do not let it define new data.
   - Because every OSM schema is unreleased, update the current schema/tests and require development
     data to reset/re-import. Do not add a production geometry-decoding migration solely to preserve
     internal builds. Do not start the final Pack-01 generation/node/edge schema here.

2. **Speed/street candidate coverage**

   - Derive latitude and longitude candidate bounds from each caller's metre radius using the shared
     conservative WGS-84 bound. Preserve speed's 50 m and street resolution's 60 m thresholds as
     separate caller policy.
   - Use latitude-aware wrapped longitude cell coverage and the same interval expansion for bbox
     prefiltering. Calculate E7 expansion in `Long` and clamp latitude to Earth range.
   - If a search cap reaches a pole, return an explicit Full-longitude coverage state. Do not divide
     by an arbitrary cosine floor and pretend a partial neighborhood is complete.
   - Candidate incompleteness must fail closed: use a bounded range/batched query or return an
     explicit unavailable/abstention result. Do not silently truncate cells and then return a normal
     nearest-road/speed-limit answer.

3. **Grid and cache safety**

   - Canonicalize longitude before `cellKey`; `+180°` and `-180°` must have the same key.
   - Clamp/validate latitude cells and wrap longitude cells across exactly one 36,000-cell cycle.
   - Form min/max cells, row/column counts, and their product in `Long`; compare the product with a
     named cap before materializing. Replace ambiguous empty-array rejection and arbitrary public
     radii with a typed bounded result, iterator, or sequence of bounded batches.
   - Keep every SQL `IN` chunk under the repository's conservative bind cap. Do not allocate one
     108,000-key polar array or emit one global `IN` expression. A typed safe abstention is acceptable
     until a measured range-oriented query exists.
   - Canonicalize speed cache longitude and use floor division for symmetric negative/positive
     geographic buckets. Graph revision/epoch cache invalidation belongs to the later Pack-01
     publication work; do not invent it in this slice.

4. **Optional imported coordinates**

   - For JSON Wi-Fi/cell observations, missing, non-finite, out-of-range, or half-present coordinates
     preserve the non-location observation but set both E7 fields to null and coordinate provenance
     to `UNKNOWN`.
   - A valid checked pair survives an unknown/unrecognized provenance string, but provenance becomes
     `UNKNOWN`. Canonicalize `+180°` and exact poles before persistence.
   - Do not overload coordinate provenance to describe an attempted-but-rejected source. If that is
     operationally required, add a separate bounded diagnostic/rejection reason only after checking
     its persistence/export/privacy impact.

5. **Polyline decoding and shared consumers**

   - Validate the initial decoded latitude/longitude and every accumulated `Long` before narrowing.
     Reject integer overflow, Earth-range violations, trailing/truncated malformed data according to
     a documented codec contract.
   - Replace private longitude functions/constants in OSM matcher, speed, geocoder, parser, grid, and
     session distance with shared operations where module boundaries permit. Do not change the
     session classifier's product thresholds or cadence behavior while doing this mechanical
     consolidation.

### Required geospatial tests

At minimum add deterministic unit/property/integration tests for:

- normalization: `-180 -> -180`, `+180 -> -180`, `±540 -> -180`, `721 -> 1`, `-721 -> -1`,
  signed `-0.0 -> +0.0`, and arbitrary `Long.MIN_VALUE/MAX_VALUE` without intermediate overflow;
- shortest deltas: `179.9 -> -179.9 = +0.2°`, reverse `-0.2°`, and both exact-180° directions use
  the declared `-180°` tie;
- dateline interpolation at `t=.25/.5/.75` yields `179.95°/-180°/-179.95°`;
- interval `[179.9,-179.9]` has start `179.9°`, span `0.2°`, includes the antimeridian and excludes
  zero; polyline `[-170,0,170]` covers 340°, not the 20° complement;
- parser cell membership and persisted bbox consumers agree for a dateline PBF way;
- Prague speed coverage uses at least the conservative 50 m longitude ceiling of 7,046 E7 near
  latitude 50.0755°, and a road 45 m east is not rejected before distance evaluation;
- a 50 m query at 89° uses sufficient wrapped longitude cells; a cap at 89.9998° is explicitly Full;
- `cellKey(lat,+180°) == cellKey(lat,-180°)`, cache identity is equivalent there, and negative cache
  buckets use floor semantics;
- huge radii/count products never overflow or allocate/query beyond a declared cap;
- PBF and polyline values just inside/outside Earth and integer ranges are accepted/rejected before
  narrowing;
- optional import cases for null half-pairs, NaN, infinities, huge finite values, `+180°`, exact
  poles, signed zero, half-E7 ties, and unknown provenance follow the contract above.

Retain the supplied exact WGS-84 projection vectors as an independent oracle suite, including:

- A `(0,1799999000)`, B `(10000,-1799999000)`, P `(5000,-1800000000)` has effectively zero
  point-to-edge distance; the subtraction-before-widening mutant reports about 55.59984 m;
- Prague A `(500758596,144367523)`, B `(500758596,144388477)`, P
  `(500755000,144378000)` has WGS-84 edge length about 149.994760 m and point-to-edge distance about
  39.999022 m; it must snap at 50 m;
- 89° A `(889993284,1799730849)`, B `(890006714,1799730849)`, P
  `(890000000,1799500000)` has point-to-edge distance about 45.000011 m; it must snap;
- `(89.9998°,0°)` to `(89.9998°,180°)` is about 44.677592 m, so a conservative 50 m cap is Full
  longitude; at `89.9995°` the corresponding distance is about 111.693980 m and coverage is not Full;
- repeated vertices and endpoint projections remain finite with `t` in `[0,1]`.

For the current planar production evaluator, use these vectors to prove wrap/candidate correctness
with a tolerance appropriate to the documented approximation. Do not claim millimetre production
accuracy. A test-only exact oracle is acceptable if dependency licensing/versioning is reviewed;
checked-in independently generated expected values are also sufficient for this phase.

### Explicit Pack-03 non-goals

Do not in this workstream:

- add GeographicLib or another exact-geodesic library to the production hot path;
- implement the report's ENU error guard or an ellipsoidal point-to-segment minimizer as though its
  constants were already validated;
- change nearest-road/snap thresholds, matcher candidate K/radius, HMM sigma/beta, or ranking policy;
- add `rtree_i32`, replace the cell index, or select the final import-scoped physical schema;
- materialize/query every polar cell merely to satisfy a theoretical maximum;
- rewrite released historical coordinates or guess whether an ambiguous old ordered bbox is a
  complement without producer/schema evidence.

Record for the later benchmark: imported edge-length distribution, candidate and near-threshold
distance gaps, exact-fallback rate under candidate guards, polar/full-coverage frequency, candidate
counts, query plans, latency/allocation, binary-size impact, and any KMP/platform boundary. Those
measurements select the production geodesic/index implementation; they are not reasons to leave the
bounded correctness work untested.

## Workstream 1 — altitude datum correctness (primary)

### Defect to remove

Android defines `android.location.Location.altitude` as altitude above the WGS84 reference
ellipsoid. AndroidX exposes MSL as a distinct property. The current
`AndroidXGeoidAltitudeConverter` returns the raw ellipsoid value when conversion throws or no MSL
value is present. That bare `Double` is named MSL and can calibrate the pressure baseline, update the
filter, overwrite the platform altitude field, persist to `location_sample.alt_m`, export, and enter
maximum-altitude and gain/loss calculations.

### Required behavior

1. Replace the converter's bare nullable result with an explicit typed outcome. It must distinguish
   at least:
   - successful Android-model MSL conversion;
   - input without an altitude / conversion not attempted;
   - converter returned without an MSL value;
   - invalid input;
   - I/O failure;
   - unexpected failure.

   Only the successful result contains an MSL measurement. Do not put exception messages, paths, or
   stack traces in durable product fields. Preserve normal cancellation semantics anywhere a
   suspend boundary is introduced.

2. Do not overwrite `android.location.Location.altitude` with MSL or fused MSL. If AndroidX needs a
   mutable object, use a defensive copy for conversion. Preserve the provider object/value as the
   raw WGS84 ellipsoid observation.

3. Carry the processed estimate separately through the real pipeline. Inspect and update all
   relevant boundaries, including:
   - `AltitudeProcessor` and `AltitudeFusionEngine`;
   - `LocationTrackerComponent`;
   - `MutableCollectionData`;
   - `SignalDispatchStage` / `SignalAdapter` / `LocationSignal`;
   - `SignalSerializer` and durable pending-signal recovery;
   - `PersistenceProcessor`;
   - Room `LocationSample`, the Room-free model, and their mappers;
   - supported import/export paths and trip altitude/gain/loss consumers.

4. Use a compact production contract. At minimum, a processed altitude must travel with versioned
   values equivalent to:
   - datum: Android-model MSL, fused Android-model MSL, relative barometric, WGS84 ellipsoid, or
     unknown legacy;
   - source: GPS conversion, fused GPS/barometer, barometer/prediction where actually applicable,
     imported, or legacy/unknown;
   - conversion status;
   - estimator/calibration/model version identifiers where needed to interpret it.

   Prefer enums with stable serialized/database names and forward-compatible decoding. Do not store
   a transform list or source-event-ID array in every production row; detailed lineage belongs in a
   research trace.

5. Preserve valid barometric continuation semantics. A failed conversion means no GPS MSL update
   and no MSL calibration from that fix. It does not necessarily invalidate a previously calibrated,
   datum-compatible barometric/predicted estimate. If the current fusion API cannot state which
   sources were used, return a typed fusion result rather than guessing provenance in the caller.

6. Historical and imported bare altitudes default to `UNKNOWN_LEGACY` unless the exact schema or
   file-format path proves a compatible datum. Do not treat a prior KDoc as proof. A migration or
   decoder default must be explicit and tested.

7. Downstream computations must operate only on compatible continuous segments:
   - never calibrate an MSL pressure baseline from ellipsoid or unknown altitude;
   - never create a gain/loss delta across a datum change, unknown datum, clock-domain change, reboot,
     or unresolved discontinuity;
   - do not label unknown/ellipsoid altitude as MSL in UI or export;
   - reset the comparison baseline at a segment boundary instead of bridging it.

8. Schema 40 and all OSM schemas are unreleased; schema 12 is the last released database in the
   repository ledger. For altitude columns, follow the actual migration path from the last released
   schema and keep fresh-install/migration schemas identical. Do not create a 40→41 migration solely
   to preserve internal development data. Update exported Room schemas and migration tests as the
   repository convention requires. If altitude-bearing schemas were released earlier, preserve
   those user rows as unknown rather than destroying them.

9. Correct semantic documentation and naming: current pressure values emitted into
   `PressureReading`, `PressureSignal`, and `pressure_sample` are cycle aggregates, not raw sensor
   events. Correct misleading KDocs and local names, but do not widen the production pressure table
   with mean/median/MAD/variance fields before a runtime method is selected.

### Required altitude tests

Add focused tests at the lowest useful layer and at least one real pipeline/persistence boundary:

- conversion success returns MSL with the correct status;
- absent MSL output, `IOException`, `IllegalArgumentException`, and unexpected failure never return
  ellipsoid as MSL;
- raw ellipsoid altitude is retained on conversion failure;
- failed conversion cannot calibrate or GPS-update the MSL filter;
- a previously valid compatible barometric estimate, if supported, has truthful provenance;
- the Android platform altitude field is not repurposed;
- durable signal round-trip preserves datum/source/status and backward decoding chooses unknown;
- Room persistence and entity/domain mapping preserve the contract;
- gain/loss and maximum-altitude logic do not bridge or label unknown/incompatible segments;
- JSON and any other altitude-bearing exports preserve or conservatively omit datum-sensitive data;
- released-schema migration and fresh install produce the same final schema and safe defaults.

Use deterministic values that make an ellipsoid/MSL substitution obvious. Tests must prove negative
reachability, not merely the converter's return value.

## Workstream 2 — common deterministic evidence foundation (secondary)

The current debug research exporter is schema v2 and records locations, tracker runs, markers, and
accepted samples. It lacks the raw pressure and estimator decisions required to tune altitude and
the source timing/missingness/lifecycle records required to evaluate segmentation. Do not pretend
that cycle aggregates or the current `SegmentSignal` can reconstruct discarded events.

Implement the safe foundation, not an unreviewed research-data collection product:

1. Define a versioned common research envelope with trace/run/session identity, schema and algorithm
   versions, monotonic clock-domain identity, event sequence, privacy class, lifecycle boundaries,
   feature/capability flags, loss ranges/counts, and terminal integrity counts.
2. Define typed altitude records for:
   - pressure sensor descriptor;
   - raw pressure event and validity decision;
   - aggregate window and source-event sequence range;
   - location/ellipsoid observation and conversion outcome;
   - calibration proposal/decision/lineage;
   - filter prediction/update/decision and diagnostic state/covariance;
   - reset, gap, pause, restart, reboot, and trace-loss events;
   - optional truth markers.
3. Define typed segmentation records for:
   - one canonical observation with event epoch time, acquisition elapsed time and clock domain,
     receipt time, sequence, declared identity scopes, policy/lifecycle, and capability flags;
   - optional raw/curated location and terminal acceptance decision, including horizontal/speed
     accuracy, batch position/size, acquisition/request/permission metadata, and source identity;
   - nullable step evidence with source span/total/reset, and activity value/freshness/source-time
     capability without inventing the full probability list when it was discarded upstream;
   - explicit gap, pause, restart, reboot, trace-loss, truth boundary/mode, and manual-correction
     records;
   - versioned reducer output containing boundary interval, decision time, state/mode or `UNKNOWN`,
     uncertainty/coverage, reason/provenance, and algorithm/config version.
4. Use trace-scoped monotonic integer sequence IDs and contiguous ranges for hot pressure events.
   Do not allocate UUIDs or repeated source-ID lists for every 5 Hz sample. UUIDs remain appropriate
   for trace/run/session and existing durable provider-fix identity.
5. Add an in-memory recorder/test sink and deterministic offline replay API first. Altitude replay
   must order by source event time within one declared clock domain, reject or explicitly classify
   future, duplicate, out-of-order, cross-domain, and cross-reboot events, and emit reproducible
   decisions. Segmentation replay must preserve recorded order as evidence, expose a separately
   declared canonical/event-time ordering policy, and never compare elapsed values across domains.
6. Add deterministic segmentation transformations for non-informative densification, bounded
   thinning, timestamp jitter, batch regrouping, cross-batch permutation, exact known duplicates,
   long gaps, degraded accuracy, jumps, missing channels, step resets, wall-clock changes, and
   clock-domain boundaries. The generators themselves need exact authored tests and loss/provenance
   output; do not invent V1 acceptance tolerances.
7. Add conformance tests tying schema documentation, encoder, decoder, capability flags, loss
   accounting, and terminal counts together. Correct the stale `presence_interval` claim in
   `tools/research-trace/README.md` unless the real exporter is deliberately restored to emit it.
8. Do not add an on-device plaintext raw-pressure or raw-route database/file, auto-upload, hidden
   capture, or normal analytics event. Do not expose a collection UI merely because the app is a
   debug build. Live/on-device capture waits until explicit consent, authenticated encryption, key
   handling, bounded buffering, deletion/withdrawal, coordinate-free mode, per-privacy-class
   capability declarations, and no-plaintext-staging behavior are implemented and reviewed together.
9. Existing authenticated export should remain streaming and fail closed. A schema-v3 manifest must
   declare which altitude and segmentation evidence capabilities were enabled and whether any loss
   occurred; an ordinary historical export must not claim replay completeness it does not have.

Add numerical diagnostic snapshots and randomized property tests for finite state, covariance
symmetry/positive semidefiniteness, monotonic timing, and determinism. A Joseph-form covariance
update is permitted only if equivalence tests against the current well-conditioned baseline and
long randomized numerical tests pass. Do not change estimator behavior merely to make a diagnostic
test convenient.

## Workstream 3 — segmentation determinism and V1 characterization (secondary)

Implement only the evidence-independent Pack-06 slice:

1. Remove `System.currentTimeMillis`/host execution time from `SessionSegmentDetector` decisions.
   Pass the current input trace time explicitly into partial-mode and stop-timeout selection. Add a
   regression test replaying the same historical signals under different injected host dates and
   require field-equivalent ordered events.
2. Do not claim that this makes V1 monotonic. Its epoch timestamps, three-cycle stop candidacy,
   sample-weighted features, location requirement, missing-step projection, gap behavior, and
   classifier remain the baseline until V2. Do not retune them in this workstream.
3. Build a pure canonical-observation-to-legacy-V1 projection and deterministic offline V1 runner on
   Workstream 2's records. The canonical record retains locationless and nullable evidence; only the
   explicitly named legacy projection may reproduce V1's current lossy behavior.
4. Freeze a small authored baseline corpus and versioned V1 output snapshots. Run every Pack-06
   transformation through it and emit deterministic sensitivity/conservation diagnostics: event
   sequence, state duration, distance/steps, boundary deltas, mode, unresolved/loss counts, and
   representation metadata. Expected cadence failures are characterization, not green invariance.
5. Add exact structural tests for record round-trip, identity-scope labeling, no cross-domain elapsed
   comparison, no fabricated evidence in the canonical layer, deterministic runner output, and
   transformation correctness. Express the future V2 invariants as a reusable contract suite, but do
   not force V1 to pass invariants it demonstrably violates.
6. Preserve the current user-facing processor wiring and events except for the host-clock fix. Do not
   process locationless cycles in V1, change missing-step behavior, add a live reorder buffer,
   persist a shadow reducer, modify stop/finalization semantics, or enable checkpoint restore in this
   slice; each would change product behavior or require unselected policy.

Document two verified lifecycle boundaries in the handoff: `SegmentDetectorProcessor.onStop()`
resets without calling `forceEnd()`, and generic processor checkpoints are not stored/restored by the
production pipeline. Do not "fix" either until clean-stop semantics and durable checkpoint ownership
are explicitly selected.

## Workstream 4 — honest vehicle-compliance output (only after primary work is green)

If the relevant files are not under concurrent modification, implement the bounded Pack-04 slice:

1. Extract the production compliance provider from `DefaultLayerRegistry` so tests invoke the same
   mapping used by the app.
2. Ensure zero/non-finite/unknown/unsupported/no-match/no-path/gap/ambiguous/ineligible-limit cases
   cannot become one of the five normal compliance buckets. `NaN` must not map to `AT_LIMIT`.
3. Treat road-class heuristic speed as heuristic, not a confirmed road limit. Suppress it or render
   it through an explicit non-compliance/uncertain semantic path.
4. Add a minimal typed diagnostic result with the unavailable/suppression reason and selected
   import/way/edge/limit provenance when present. Do not invent HMM thresholds.
5. Add reversal tests through the extracted production provider.

Do not implement directed topology, candidate tuning, transition-beta changes, turn restrictions,
confidence calibration, or final multi-region storage in this workstream.

## Explicitly forbidden tuning and premature design

Do not change any of the following without new user direction based on trace evidence:

- pressure mean versus latest/median/trimmed/Huber/sequential/fixed-grid handling;
- sensor registration cadence;
- GPS or barometer measurement variance, floors, ceilings, or missing-accuracy policy;
- process noise;
- innovation, normalized-innovation, derivative, spike, gap, or discontinuity thresholds;
- calibration interval, consensus, probation, rollback, or reacquisition policy;
- fusion process-death persistence/checkpointing or restore eligibility;
- field-study sample counts, split percentages, numeric acceptance margins, or retention durations;
- segmentation support horizons, evidence rates, hysteresis, cadence tolerances, live reorder/window
  bounds, gap split/finalization policy, revision horizon, mode thresholds/taxonomy, or V2 activation;
- detector process-death checkpoint ownership/restore, clean-stop finalization, raw-route shadow
  persistence, HSMM duration/emission priors, or any neural transport classifier;
- final OSM way/node/edge physical schema, unmeasured dynamic import resource coefficients/budgets,
  importer re-enablement, or matcher parameters;
- production GeographicLib/ENU fallback, point-to-geodesic minimizer tolerances, R-tree adoption, or
  polar-query policy beyond bounded safe behavior.

Keep process-death fusion persistence disabled. Keep detailed raw pressure, routes, sensor
fingerprints, innovations, covariance, calibration source lineage, and truth markers out of
production telemetry.

## Verification and handoff

1. Run formatting/static checks and focused unit/migration/integration tests for every touched
   module. Then run the broadest practical repository test task if time and environment permit.
2. Inspect generated Room schema/SQL where relevant; a green model-only test is not enough.
3. Review the final diff for accidental parameter changes, datum defaults, plaintext research data,
   unchecked coordinate narrowing, generic wide-bbox heuristics, unbounded grid materialization,
   user-worktree loss, V1 segmentation behavior changes beyond the host-clock fix, accidental
   location/step fabrication in the canonical record, any production path around the PBF gate,
   URI/content leakage, `Throwable`/OOM recovery, or broad unrelated formatting.
4. Commit every completed and verified slice locally on `dev/v10`:
   - prefer one coherent, independently revertible commit per workstream or schema-coupled vertical
     slice; use a separate commit for the research/planning documents;
   - stage explicit files or hunks—never `git add -A`/`git add .` in this pre-dirty worktree;
   - inspect `git diff --cached` before each commit and exclude unrelated pre-existing changes;
   - use descriptive conventional messages, for example `docs: add V10 research decision package`
     or `fix(osm): gate unsafe PBF import`;
   - do not amend, squash, rebase, merge, push, or open a PR unless the user separately requests it;
   - after the last commit, prove that no implementation-agent-created or deliberately adopted
     in-scope change remains unstaged/uncommitted. Unrelated initial residue may remain and must be
     listed exactly rather than absorbed merely to make `git status` appear clean.
5. Report:
   - exact behavior implemented;
   - files and schemas changed;
   - tests/commands and results;
   - commit hashes, subjects, and confirmation they are on `dev/v10`;
   - final `git status --short`, separating untouched pre-existing residue from task output;
   - any working-copy divergence from the pinned report;
   - remaining evidence-gated decisions;
   - any privacy or product decision that genuinely blocks completion.

Do not claim the road matcher or altitude estimator is more accurate merely because structural
contracts and replay/tests landed, and do not claim V1 segmentation is cadence-independent. The
completion claim is narrower: untrusted PBF import is fail-closed while its safe parser remains
uncertified; spatial identity and candidate coverage obey one bounded contract;
invalid coordinates cannot enter through saturation or partial pairs; incompatible altitudes cannot
silently enter MSL paths; provenance/loss are explicit; historical V1 replay no longer depends on
the host date; and future algorithm choices can be compared against deterministic oracles and source
streams.
