# Dev/v10 research synthesis status

> Updated: 2026-07-22
> Repository evidence target: `fb2684d53ea2900780feb3438e736fbcd1b26c3f`
> Purpose: cumulative local review of the six research packs before the final cross-pack synthesis

This is a decision ledger, not a replacement for the unedited research reports. It records which
claims survived local code inspection, which recommendations change the implementation order, and
which proposed thresholds or designs still need evidence. Repository facts refer to the immutable
target above unless this file explicitly says "current working copy".

## Report intake

| Pack | Status | Local review result |
|---|---|---|
| 01 — OSM multi-region storage and publication | **Two independent reports received and reviewed** | Per-import immutable generations accepted; the second report refines physical identity and overlap precedence. |
| 02 — OSM PBF resource policy | **Received and reviewed** | Current untrusted importer is release-blocking; fail-closed containment and a strict combined-budget intake are accepted, while coefficients remain benchmark-gated. |
| 03 — geospatial and coordinate contracts | **Received and reviewed** | Shared canonical longitude, circular-interval, bounded-grid, and atomic optional-coordinate contracts are accepted; exact-geodesic runtime selection remains benchmark-gated. |
| 04 — matcher and compliance validation | **Received and reviewed** | Core repository findings accepted; feature order revised below. |
| 05 — altitude fusion | **Received and reviewed** | Datum correctness and minimum evidence instrumentation are immediate; aggregation, calibration, tuning, and persistence policy remain trace-gated. |
| 06 — cadence-independent route classification | **Received and reviewed** | Deterministic replay, lossless timed observations, and cadence-invariance contracts are accepted now; the live reducer, numerical policy, and HSMM remain trace-gated. |

## Pack 01 verdict

Both reports' central architecture is accepted: V10 should use immutable, import-scoped OSM
generations rather than a global mutable way table. This is the smallest design that makes
overlapping imports, cancellation, deletion, and publication behavior explicit and testable.

Two conclusions are stronger after local verification than they were in the report:

1. **Do not build the proposed recovery migration for released users.** The migration ledger marks
   schema 12 as the last released schema, while OSM tables first appear in unreleased migration
   28→29 and schema 40 is also unreleased. We can change the unreleased OSM schema and regenerate
   schema 40. Development/internal installs may reset and re-import OSM data. A 40→41 synthetic
   recovery import is justified only if retaining internal-build data becomes an explicit product
   requirement.
2. **The SQLite engine is now a separate P0 release gate.** Tracker pins requery sqlite-android
   3.49.0 and enables WAL. Inspection of every ABI in the cached 3.49.0 AAR found the exact SQLite
   source ID `2025-02-06 11:55:18 4a7dd425dc2a0e5082a9049c9b4a9d4f199a71583d014c24b4cfe276c5a77cde`.
   SQLite's official advisory places 3.49.0 in the affected WAL-reset range. Runtime telemetry can
   prove which engine loaded; it cannot make the affected engine safe. Select or build a fixed
   artifact, verify `sqlite_version()` and `sqlite_source_id()` on every shipped ABI, then run the
   database test matrix before release.

### Second Pack 01 report — accepted deltas

The second report is not Pack 02; it is a deeper independent answer to the same storage/publication
prompt. Its useful new conclusions are:

1. **Separate logical from physical identity.** The required logical key is
   `UNIQUE(import_id, osm_way_id)`. A surrogate `way_instance_id INTEGER PRIMARY KEY` is a plausible
   physical optimization because the much larger cell table can reference one integer rather than
   repeat an import/way composite. It is now the provisional physical design, not a final decision.
   Pack 02 supplied the correct measurement protocol but no device/database measurements; that
   protocol must compare the extra unique index on ways against narrower cell/FK indexes and actual
   cell multiplicity. Correctness must not depend on which representation wins.
2. **Prefer OSM revision when it exists.** Tracker's bundled `Osmformat.Way` exposes optional
   `hasInfo()/getInfo()`, and `Info` exposes optional `hasVersion()/getVersion()`. The parser currently
   ignores it. For copies of the same OSM way ID, the highest non-null OSM version is a better winner
   than file-import order. When all copies omit metadata, fall back to `published_revision`, then a
   stable import identity. OSM version is only comparable among copies of the same element ID.
3. **Select the winner globally before spatial filtering.** If a newer version moved outside a
   queried cell, the query must not return an older geometry merely because the older copy still has
   membership in that cell. Candidate IDs may be seeded spatially, but version ranking occurs across
   all visible READY instances of those IDs before the winning instance is required to occupy the
   requested cells.
4. **Keep multi-query matching coherent without assuming a long read transaction is free.** One
   joined candidate statement is still required. For a large HMM load split into chunks, either hold
   one read transaction or require every chunk to return the same graph epoch and restart/fail
   explicitly on change. Prefer epoch verification until device tests show a long WAL reader has
   acceptable checkpoint and disk behavior.
5. **Measure the dominant duplication before normalizing it.** Record geometry bytes, cell/index
   bytes, instances per OSM ID/version, peak WAL, query plans, and held-reader growth. Do not assume
   geometry duplication dominates the cell table, and do not add content-addressed global GC without
   those measurements.

### Second Pack 01 report — qualifications and rejections

1. Its 40→41 legacy recovery design remains rejected for the release path. Schemas 13–40 are
   unreleased, OSM first appears in migration 28→29, and a released schema-12 upgrade cannot contain
   pre-existing OSM rows while it passes through the empty intermediate OSM schemas. Update the
   unreleased migrations and final schema directly.
2. The migration 31→32 empty-index interval is a real development-build behavior, but not the
   described released-user P1: a schema-12 user has no OSM rows during that migration chain. The
   general requirement for gated future reindexing remains accepted.
3. Failure to set `cell_index_built=1` at current publication is confirmed, but current imports have
   already written their cells. It causes false state and unnecessary additive startup reindexing;
   it is folded into the publication redesign rather than treated as independent silent data loss.
4. Separate `work_id` and `attempt_id` authority columns are not yet justified. WorkManager already
   gives each replacement WorkRequest a stable UUID shared by its retries. Use one opaque persisted
   generation-authority token (which may be that UUID or a separately generated value) and record
   enough WorkManager identity for reconciliation. Add a second independent token only if lifecycle
   tests prove one cannot distinguish a real race.
5. Nullable `osm_version` is accepted. A query-relevant content hash is useful for diagnosing the
   same ID/version with different content, but it is not required to select one winner. Its canonical
   inputs, width, CPU cost, storage cost, and privacy/diagnostic retention wait for packs 02 and 04.
6. Database `CHECK`s and guarded transition triggers are worthwhile defense in depth, not accepted
   literal DDL. Room 2.8.4 has no entity annotation for arbitrary table `CHECK` constraints, and
   migration-created triggers are not represented by the entity-generated fresh-install schema.
   The implementation must install equivalent constraints for both fresh creation and migration,
   test the actual packaged database, and benchmark publication-time child counts.
7. A per-cell `grid_version` is not automatically required by the selected globally gated rebuild.
   It improves explicitness and enables shadow generations, but it widens the dominant table. Let
   Pack 02's benchmark protocol and actual measurements decide; the global metadata and import
   publication must always carry the active/target grid contract.

### Architecture selected for V10

| Concern | Decision | Important qualification |
|---|---|---|
| Way identity | Logical identity `UNIQUE(import_id, osm_way_id)`; provisional physical PK `way_instance_id`. | Compare surrogate versus composite storage/query cost with the Pack 02 benchmark matrix; the report contains no deciding measurements. Do not copy the illustrative semantic columns literally. |
| Cell identity | Import-scoped reference to the immutable way instance; provisional key `(cell_key, way_instance_id)`. | Way and all cell rows for a batch must commit in one Room transaction. Per-cell grid version remains measurement-gated. |
| Import lifecycle | `BUILDING` is never query-visible; `READY` is published atomically. | Failed/cancelled BUILDING generations can be deleted without touching a READY generation. |
| Overlap precedence | Highest non-null OSM element version wins; if metadata is absent/tied, use local `published_revision` and stable import identity. | Rank only copies of the same OSM ID, globally before final spatial filtering. Publication order is a deterministic fallback, not evidence of source freshness. |
| Conflict policy | Use default/explicit `ABORT`, never `REPLACE`, for graph identity writes. | A duplicate within one generation is an importer invariant failure and should roll back the batch/import. |
| Candidate reads | One status-, grid-, and precedence-aware statement returns complete way candidates. | Return the graph revision with the same database snapshot; do not fetch it in an unrelated preliminary read. |
| Cache invalidation | Increment a monotonic graph revision on publish and READY deletion. | Room invalidation/Flow can clear eagerly, but revision-keyed reads remain the correctness mechanism. |
| Reindex | V10 uses one globally gated rebuild; shadow generations are deferred. | Every retry clears/rebuilds the partial cell index while the gate is held. No reader may observe `REBUILDING`. |
| Import authority | One database-backed generation token serializes import/reindex/delete graph mutation. | It may be the WorkRequest UUID. The token is not a time lease; stale workers fail conditional writes and orphaned request state is reconciled. |
| Database invariants | Encode simple state/value rules with table constraints and guard cross-table publication/mutation in the database where practical. | Room fresh-install and migration paths must install the same DDL/triggers; benchmark count guards and test direct invalid transitions. |
| Emergency fallback | If the full model cannot land, enforce at most one READY-or-BUILDING region in the database. | UI-only exclusion is not acceptable. Because the feature is unreleased, disabling it is safer than shipping known destructive overlap behavior. |

### Required refinements to the report's protocol

1. **Request creation and worker authority:** create the BUILDING import/request row and install its
   generation-authority token in one app-database transaction, then enqueue a WorkRequest carrying
   the import ID and token. The WorkRequest UUID itself may be the token; do not store two authority
   values without a proven distinction. App-database and WorkManager persistence cannot
   share one transaction, so startup/request reconciliation must handle both crash windows: an
   authoritative request that was never enqueued, and a worker whose token/request is no longer
   authoritative. A replacement worker must never clean up or publish another generation.
2. **Publication transaction shape:** treat the report's SQL as logical pseudocode. A transaction
   that first reads and later tries to become a writer can fail with `SQLITE_BUSY_SNAPSHOT` under WAL
   if another writer committed. Acquire write authority first or use conditional writes whose row
   counts prove ownership, then allocate revision, mark READY with the active grid version, advance
   graph revision, and release the token in one transaction. Test the exact Room/requery behavior.
3. **Coherent reads:** the repository should expose one production candidate API. Its result should
   contain graph revision plus import/way identity and all semantics needed by matching. This avoids
   a cache entry being stamped with a revision read from a different snapshot than its candidates.
4. **Rebuild restart semantics:** atomically enter `REBUILDING` while holding writer authority, block
   import/publication/deletion, clear the target index on every attempt, rebuild all READY
   generations, and atomically publish the target grid version and new graph revision. Partial rows
   after process death remain unreachable until a successful retry.
5. **Query-plan evidence:** an effective-winner anti-join or window query is an acceptable first
   design, not a performance fact. Freeze representative overlap/query distributions and retain
   `EXPLAIN QUERY PLAN` plus latency/allocation baselines. Do not add a materialized winner table or
   window-function design unless those measurements require it.
6. **Durable source access:** the OSM picker uses `OpenDocument`, but its ViewModel does not currently
   take persistable URI permission as the general importer does. Persist the grant or copy the input
   into app-controlled staging before relying on WorkManager retry/process-death recovery.

### Cross-pack schema requirement

Pack 01 solves ownership and publication, but its illustrative `osm_way` schema would still lose
inputs that pack 04 proved necessary. The V10 schema design must be done once and retain at least:

- OSM node IDs and ordered node membership sufficient to build node-connected directed edges;
- raw and resolved one-way state, including forward, reverse, bidirectional, and unsupported dynamic
  values, rather than a single boolean;
- explicit/implicit/heuristic/unsupported speed-limit provenance plus the relevant raw tags;
- stable import/way/edge identity that can travel through matching and compliance output;
- nullable OSM element version so overlapping snapshots can prefer a known newer revision, with
  publication-order fallback when metadata is absent.

Landing only the ownership redesign now and adding these fields later would force another full graph
rewrite/re-import. Pack 02's accepted policy and still-required measurements may force bounded
representation choices, so the final physical node/edge layout waits for measured storage/parser
budgets; the semantic requirements do not.

### Pack 01 acceptance test priority

The report's exhaustive fault-injection matrix is valuable, but it should be staged. The first
implementation gate is:

1. READY A plus BUILDING B never exposes B or mutates A.
2. Cancelling/replacing B after every persisted batch leaves A unchanged.
3. Publishing overlapping B chooses the documented OSM-version/fallback winner; deleting that
   winner reveals the next eligible generation.
4. A forced failure between way and cell writes rolls the whole batch back.
5. A stale worker token cannot insert, publish, reindex, delete, or clean up another import.
6. Publish/delete revisions invalidate cache entries without a process restart.
7. Failed/restarted global reindex never exposes a partial or wrong-version index.
8. Exported schema and migration tests assert the chosen import-scoped primary/unique/foreign keys,
   ABORT behavior, and foreign-key enforcement.
9. Migration tests start at the last released schema 12; schema-40 OSM recovery is not a production
   acceptance requirement.
10. Every packaged ABI reports a SQLite source ID containing the official WAL-reset fix.
11. Higher OSM versions win independently of import order; omit-metadata copies use the documented
   publication fallback; deleting the winner reveals the next eligible copy.
12. A newer winning geometry that moved away prevents an older copy from leaking into its former
   cell, proving that ranking happens before final spatial filtering.

Low-disk tests, process kills after every protocol statement, large overlap stress, and multi-device
performance matrices remain high-value hardening work after this minimal safety gate is green.

### Local questions resolved from the second Pack 01 report

| Question | Local answer at the pinned/current build |
|---|---|
| What SQL does Room generate for way insertion? | Generated `OsmWayDao_Impl` uses exact `INSERT OR REPLACE INTO osm_way ...` and wraps the collection insert in a transactional `performSuspending` call. |
| Are foreign keys enabled by generated Room code? | Yes: generated `AppDatabase_Impl` executes `PRAGMA foreign_keys = ON`. A production Requery device test across pooled connections remains useful characterization, not an unresolved schema fact. |
| Does the bundled parser API expose OSM way version? | Yes. The resolved osmpbf 1.6.1 classes expose optional Way Info and optional Info version; Tracker's parser does not read them. |
| Is requery SQLite 3.49.0 patched for the WAL-reset bug? | No evidence of a backport exists in the binary. All four packaged ABIs contain the same February 2025 upstream 3.49.0 source ID, which predates the March 2026 fix. |
| Is speed-cache invalidation wired to graph changes? | No. `OsmSpeedLimitSource.clearCache()` is internal/test-used and no production publication/deletion call was found. Revision-keyed caching remains required. |
| Does production intentionally use several app database instances/processes? | `ObjectBaseDatabase.database()` synchronizes and caches one instance, and no application component declares a separate Android process. SQLite still uses a connection pool within that instance. |
| Is the migration backup a raw live-file copy? | No. Before migration it opens the source, requires a successful TRUNCATE checkpoint, switches to DELETE journal mode, hashes and fsyncs the copied main file, and validates schema version/integrity. It does not precompute required free space or automatically restore after migration failure. |
| How many released schema-40 databases need ownership recovery? | None by the repository's release ledger. Schema 40 is unreleased; preserving internal-build OSM data is optional scope, not a production migration requirement. |

## Pack 02 verdict

The report's security conclusion is accepted: the current `.osm.pbf` import path is not safe for an
untrusted user-selected file. Its class documentation equates streaming with bounded memory, but
the implementation and its upstream reader admit multiple allocations and retained structures that
are covered by neither the 100 MiB provider-size check nor the distinct-node cap. This is a P0
release gate independent of the multi-region P0. Until a complete bounded intake is proven, the
safe product behavior is to keep offline PBF import centrally disabled in release builds.

### Confirmed repository and dependency findings

| Report finding | Local result | Planning consequence |
|---|---|---|
| Repeating a small set of node IDs across arbitrarily many drivable ways bypasses the distinct-node cap. | Confirmed. `WayScanParser` allocates a `LongArray(refCount)` and appends a `BufferedWay` for every retained way; it limits only `nodeIds.size`. Two repeated IDs can therefore grow the way list and ref arrays until OOM. | Add pre-allocation combined reservations plus independent retained-way and total-reference counters. A distinct-node limit is not a heap envelope. |
| A single way can allocate its reference array before any per-way check. | Confirmed. Only `refCount >= 2` is checked; no maximum or checked total exists before `LongArray(refCount)`. Delta addition is also unchecked. | Enforce the declared `2..2,000` Tracker policy and total-ref/work budgets before allocation; use exact checked delta/ID arithmetic. |
| `osmpbf:1.6.1` trusts zlib `raw_size`. | Confirmed from the exact cached jar bytecode. It executes `new byte[getRawSize()]`, copies `zlibData.toByteArray()`, calls `inflate()` once, and tests `finished()` only in an assertion. | Unmodified `BlockInputStream` is forbidden for untrusted input. Validate/reserve decoded size before allocation and perform iterative exact decompression with typed failure. |
| Truncated input can be accepted as clean EOF. | Confirmed. `BlockInputStream.process()` catches every `EOFException`, calls `complete()`, and returns. | Own/fork the framing loop and distinguish EOF before any new prefix byte from truncation in the prefix, header, or body. |
| Full generated protobuf object graphs exist before Tracker callbacks. | Confirmed by call shape: the library parses `Blob`, then `BinaryParser` parses a generated `PrimitiveBlock` before `parseWays`/`parseDense` callbacks. Tracker cannot reserve per entity/string/ref before those objects exist. | Use a specialized streaming/skipping primitive decoder, or a non-allocating wire preflight whose charge is proven never to underpredict the subsequent bounded generated parse. Framing fixes alone do not close the gate. |
| Parser/Worker batches amplify cells and Room objects. | Confirmed. Parser batches 1,000 ways; Worker accumulates until at least 5,000 ways, copies the list, then constructs all way and cell entities. Each accepted way may carry 20,000 cell keys. | Batch by reserved geometry bytes, cells, rows, bindings, and database/WAL charge with pre-add flush and a single-way-too-large rejection; transact ways and cells together. |
| Cancellation flag is inert. | Confirmed. `CancellationCheck.isCancelled` is never set from the coroutine job. `ensureActive()` runs only outside each synchronous pass and between emitted batches. | Connect cancellation to the blocking reader and poll at framing/entity/ref/tag/cell/write granularity; benchmark p99 latency instead of selecting an interval by intuition. |
| Provider-reported size is treated as authoritative. | Confirmed. Worker rejects unknown/non-positive size and parser checks `KEY_FILE_SIZE` before opening the URI, but never counts bytes. Android documents `_size` as nullable when unknown. | Treat provider size as a UI/preflight hint only. Snapshot once, count actual bytes, and reject at byte 100 MiB + 1. |
| Cleanup only covers caught failures. | Partly confirmed and locally qualified. Worker catch paths delete the current database import best-effort. Startup also deletes old `BUILDING` imports with a timestamp guard. There is no job directory/snapshot journal to reconcile yet, and process death bypasses catch cleanup. | Preserve database startup recovery, but extend reconciliation to job token, source snapshot, staging, partial database generation, retry, reboot, and authority loss. |
| Catching `Throwable` makes OOM routine control flow and can leak input details. | Confirmed. Worker catches `Throwable`, then attempts cleanup/notification/output allocation; URI is persisted and included in some exception text. | Catch classified expected failures and `Exception` only where appropriate; never catch OOM as routine. Use stable redacted codes and aggregate diagnostics. |
| The positive parser fixture is not a valid conformance PBF. | Confirmed. It emits no `OSMHeader` and mixes nodes and ways in one `PrimitiveGroup`. The permissive library accepts it. | Replace it with a valid pinned fixture and retain malformed variants specifically as negative tests. |

The cached `osmpbf` POM declares protobuf-java 4.33.2. Tracker's version catalog declares 4.35.1,
but the direct declaration found locally is app `debugImplementation`, not an OSM-module or release
constraint. The exact debug/release/unit-test runtime selections remain unresolved: the requested
Gradle `dependencyInsight` could not configure because this environment has Java 21 but no project-
required Java 17 toolchain. Add a direct strict constraint and configuration-specific build tests;
do not claim either catalog number as the production runtime until that evidence exists.

### External format and platform contracts accepted

1. PBF framing uses a four-byte big-endian BlobHeader length. A BlobHeader should be `<32 KiB` and
   must be `<64 KiB`; a decoded Blob should be `<16 MiB` and must be `<32 MiB`. Tracker must preserve
   the distinction between compatibility recommendations and hard format rejection.
2. Raw and zlib payloads are required interoperable codecs. LZ4/ZSTD are optional and may be rejected
   with a typed unsupported-codec result. Exactly one payload is accepted.
3. A valid OSM file has an `OSMHeader` before its first `OSMData` block. Required features must be
   explicitly supported; V10 needs `OsmSchema-V0.6` and `DenseNodes`, rejects historical data and
   unknown required features, and does not infer support from a permissive upstream parser.
4. The current OSM Editing API advertises 2,000 nodes per way. This justifies a clear Tracker product
   compatibility ceiling for ordinary current data; it is not a PBF format or memory-safety limit.
5. Protobuf Java's size limit applies to InputStream-backed `CodedInputStream`, not the existing raw
   byte-array/ByteString materialization path. It cannot replace application structural/work budgets.
6. Android's provider size may be unknown, WorkManager persists/reschedules work, `maxMemory()` is a
   VM maximum rather than free memory, `availMem` is not absolute, and storage preflight can race.
   These APIs inform policy but none proves a future allocation or write will succeed.

### Import architecture selected now

1. **Fail closed before refactoring.** Add one centrally owned import capability that defaults off for
   release until the safe pipeline is certified. UI and controller must not enqueue; direct Worker
   execution must return a stable unavailable failure before opening a URI, making a job directory,
   or inserting a database row. Do not delete the parser—it remains available to focused tests and
   bounded implementation behind the gate.
2. **Acquire one immutable private snapshot.** For each authoritative job token, preflight disk, copy
   the provider stream once into app-private storage, count bytes with checked arithmetic, reject on
   the first byte past 100 MiB, poll cancellation, close completely, and atomically journal snapshot
   completion. Both passes consume this snapshot. Provider URI/size are not durable safety facts.
3. **Own the untrusted framing/decompression boundary.** Read only positive bounded lengths; reserve
   before arrays; require exact body reads; accept exactly one raw/zlib payload; enforce the decoded
   ceiling before allocation; inflate iteratively to exactly `raw_size`; require `finished`, no
   dictionary, and no trailing compressed bytes; close native inflater state on every path.
4. **Validate protocol state and primitive structure before materialization.** Require header order
   and features, homogeneous primitive groups, consistent parallel arrays and string IDs, bounded
   string/table/tag/entity counts, positive unique retained IDs, checked delta/scale/offset arithmetic,
   and valid Earth coordinates. Skip relations/metadata/strings that the accepted semantic schema
   does not require, while retaining Pack 01/04 inputs such as OSM version, ordered node identity,
   direction, and limit provenance.
5. **Use one coexistence-aware ledger.** Managed heap, parser/native overhead diagnostics, source and
   staging disk, DB/WAL growth, output rows/bindings, and structural work counters share checked
   reserve-before-allocate/write accounting. A convenience floor must never override a safety-derived
   budget. Release a charge only when its backing references/resources are unreachable/closed.
6. **Keep two passes initially.** A safe primitive target-node table plus retained bounded ways is the
   simplest architecture for the current 100 MiB product scope. Spill/staged SQLite is the next
   option only if measured realistic city acceptance is inadequate; external sorting/joining is not
   justified unless large-region import becomes an explicit product requirement.
7. **Bound output in every dimension.** Validate all refs before coordinate arrays, reserve polyline
   and cell output, flush before adding a way that would cross any limit, reject a single way that
   cannot fit, insert cell rows without materializing an unbounded entity list, and commit ways plus
   cells in one explicit database transaction under Pack 01 authority.
8. **Classify failure and retry.** Malformed, unsupported, resource, memory-policy, and storage-policy
   failures are deterministic permanent failures. Retry only a bounded explicitly classified
   transient I/O case. User text is stable and redacted; telemetry contains aggregate counts/peaks,
   policy/runtime versions, and failure code—not URI, names, IDs, coordinates, tags, or content.
9. **Reconcile independently of catches.** Normal cleanup and startup/worker-entry/reboot recovery
   are idempotent and authority-aware. They remove abandoned snapshots/staging and partial BUILDING
   generations without allowing a stale/replacement worker to touch another generation.

### Numeric and physical choices not supplied by the report

Pack 02 is a policy and benchmark design, not benchmark output. Do not freeze any of these from the
report's examples:

- Java/object/native charge coefficients or the required heap safety reserve;
- total retained ways, total refs, distinct target nodes, strings/tags/entities, or decoded work;
- cancellation polling counts, CPU/wall deadlines, or performance SLA;
- geometry/cell/row/Room/SQLite/WAL batch budgets or disk cleanup reserve;
- universal low/middle/high device acceptance rates;
- primitive-table load factor/layout, spill threshold, or whether spill is needed;
- surrogate versus composite physical graph keys, per-cell grid version, or topology encoding.

The `<64 KiB` header and `<32 MiB` decoded Blob rules are format limits. The 100 MiB source,
2,000 refs per retained way, and 20,000 cells per way are explicit initial product policies. A
`<16 MiB` decoded operational ceiling is a defensible compatibility starting point, but it remains
subject to corpus validation and the device ledger; none of these independent ceilings makes their
combined maxima safe.

### Pack 02 proving and benchmark gate

Before re-enablement, add boundary-minus/exact/plus tests for every fixed limit and adversarial cases
for negative/overflow/truncated framing; compression bombs, under/overstated `raw_size`, dictionary
and trailing streams; missing/duplicate/late headers and unknown features; mixed groups and parallel-
array mismatch; entity/string/tag floods; repeated-reference way floods; duplicate/overflowing IDs
and coordinates; extreme cells/output; unknown provider size; cancellation; low disk; transaction
failure; retry; kill/force-stop/reboot. An allocation/reservation spy must prove rejection occurs
before the prohibited allocation or write.

Then run immutable hashed small-town, dense-city, metro, rural, long-road, antimeridian, high-tag,
and synthetic adversarial workloads on the lowest supported physical device/API/ABI (including
32-bit if shipped), a representative middle device, a high-memory characterization device, and
constrained emulators. Record phase heap/native/PSS, allocation/GC, CPU/wall, cancellation p99,
source/block/entity/ref/string/cell/row counters, temp/DB/WAL high-water, exit reason, and residue.
The charge model fails if any held-out observed managed-memory delta exceeds its charge. One named
city succeeding is not a release policy.

## Pack 03 verdict

The report's central diagnosis is accepted. Tracker does not merely have isolated antimeridian
bugs; it has several incompatible interpretations of the same longitude and bbox fields. The
smallest worthwhile correction is a shared checked coordinate/circular-longitude contract, not
another private helper in each consumer.

### Accepted repository findings

The following findings were reproduced at the pinned commit:

1. `OsmSpeedLimitSource` subtracts longitude `Int`s before widening in both segment and query
   projection. Valid dateline operands can overflow before conversion to `Double`; the supplied
   mutant vector then reports about 55.6 m for a point lying on the edge and misses the 50 m snap.
2. `OsmPbfStreamingParser` derives a narrow circular longitude interval for cell membership but
   persists raw numeric longitude extrema. A dateline way can therefore have narrow cell rows and a
   wide/ambiguous persisted bbox. Speed lookup reads it linearly; street resolution reads it as an
   eastward interval. Neither interpretation is the parser's intended narrow arc.
3. Speed lookup uses one fixed 5,000-E7 padding for both latitude and longitude and a fixed 3x3 cell
   neighborhood. The longitude search becomes too narrow at Prague latitude and increasingly wrong
   toward a pole.
4. `OsmGridIndex.cellKey` distinguishes `+180°` from `-180°`. `cellAndNeighbors` accepts arbitrary
   `Int` radii, forms latitude bounds and allocation products in `Int`, and can overflow or allocate
   an excessive array. The import bbox path's pre-allocation `Long` count and 20,000-cell cap are the
   better pattern, although its empty-array rejection result is not typed.
5. JSON Wi-Fi/cell readers at the pinned commit validate and convert latitude and longitude
   independently. They can persist half a coordinate pair, saturate an out-of-range finite value
   through integer conversion, and retain specific provenance for unusable coordinates.
6. PBF degree-to-E7 conversion has no explicit finite/Earth-range check. `PolylineE7Codec.decode`
   narrows accumulated `Long`s without checking integer and Earth-coordinate postconditions.
7. Longitude normalization, positive/signed delta, local distance constants, bbox membership, and
   projection logic are duplicated across OSM, geocoder, and session-segmentation code. Existing
   `LatE7`/`LonE7.fromDegrees` truncate, can turn NaN into zero through `toInt`, rely on a
   post-conversion constructor check for other out-of-range values, and treat both antimeridian
   representations as distinct longitude values.
8. The speed cache buckets with truncating division and no longitude canonicalization, so equivalent
   antimeridian points and symmetric negative/positive buckets do not have a declared identity.

### Current-working-copy reconciliation

The uncommitted bounded fixes are useful but do not close Pack 03:

| Area | Improvement already present | Remaining contract defect |
|---|---|---|
| Speed projection | Longitude operands are widened and shortest-delta wrapped. | The bbox still uses fixed longitude padding; candidate cells still use the default radius of one at every latitude. |
| Dateline cells/bboxes | Speed lookup uses wrapped neighbors and contains a legacy ordered-wide heuristic. | Parser persistence still disagrees with its cell interval; geocoder still consumes that ambiguity differently. A generic `ordered width > 180° => complement` rule misreads genuinely wide paths. |
| Optional JSON coordinates | The pair is now atomic, finite, and range-checked. | Cleared coordinates still retain supplied provenance, contrary to the model's `UNKNOWN` null-coordinate meaning; `+180°` and pole identity are not canonicalized. |
| Grid safety | Import bbox enumeration counts in `Long` and is capped. | Point-neighbor enumeration is still unbounded/`Int`-sized, and `cellKey(+180°)` remains distinct from `cellKey(-180°)`. |
| Geometry codecs | Dateline polyline encoding widens before subtraction. | PBF quantization and decoded accumulated coordinates still lack checked range/narrowing. |

The historical integration report's phrase "corrected for ... bounding boxes" is therefore too
broad. Those changes fixed concrete overflow/wrap cases and remain worth retaining, but Pack 03
shows that producer/consumer bbox agreement and latitude-aware speed candidate coverage are still
open.

### External contracts accepted

1. Android's official [`Location`](https://developer.android.com/reference/android/location/Location)
   API permits inclusive latitude `[-90,90]` and longitude
   `[-180,180]`; that is an input boundary, not a requirement to preserve two internal identities
   for the same antimeridian.
2. [RFC 7946](https://datatracker.ietf.org/doc/rfc7946/) and
   [OGC API Features](https://docs.ogc.org/is/17-069r3/17-069r3.html#_parameter_bbox) both explicitly
   represent an antimeridian-crossing bbox with its
   first/west longitude greater than its third/east longitude. These support a crossing adapter but
   do not dictate Tracker's Room representation.
3. [EPSG method 9836](https://epsg.io/9836-method) supplies the standard
   geocentric-to-topocentric rotation used for an ECEF/ENU
   implementation. It does not validate this report's point-to-segment error guard.
4. [GeographicLib Java](https://geographiclib.sourceforge.io/html/java/net/sf/geographiclib/Geodesic.html)
   documents a robust WGS-84 shortest-geodesic implementation and better-than-15-nm
   geodesic calculation accuracy. That accuracy does not automatically apply to a separately written
   finite point-to-segment minimizer.
5. Kotlin documents that
   [`roundToInt`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/round-to-int.html) rounds
   ties toward positive infinity, saturates values outside
   the integer range, and rejects NaN. Tracker must validate finite Earth ranges before calling it;
   saturation is not validation.
6. SQLite documents signed 32-bit coordinates for
   [`rtree_i32`](https://www.sqlite.org/rtree.html#integer_valued_r_trees) and
   [version-dependent host-parameter limits](https://www.sqlite.org/limits.html). These make an
   integer spatial index feasible, but do not establish that it beats the
   current cell table on Tracker devices.

### Decisions accepted now

1. **Separate validation from normalization.** External/import boundaries reject missing halves,
   non-finite values, and Earth-range violations. Only explicitly cyclic operations accept arbitrary
   finite/`Long` longitude and wrap it. Corrupt persisted E7 data is rejected or quarantined, never
   silently wrapped into a different valid location.
2. **Use one canonical spatial identity.** New internal/persisted longitude identity is
   `[-180°,180°)`; accepted `+180°` becomes `-180°`, signed zero becomes positive zero, and a
   canonical point at an exact pole uses longitude zero. Read adapters normalize existing equivalent
   values. Do not destructively rewrite released raw history solely for canonicalization; retain raw
   import evidence separately if exact source reproduction is a product requirement.
3. **Widen before arithmetic.** Longitude subtraction and bbox expansion occur in `Long` or through a
   checked longitude type. A shortest signed delta is `[-180°,180°)` with the exact 180° tie resolved
   deterministically to `-180°`. An arbitrary-`Long` normalizer must avoid overflowing an intermediate
   `value + halfWorld`; constrained helpers may instead document and test their narrower precondition.
4. **Adopt `CircularLongitudeInterval(startE7, eastwardSpanE7)` in memory.** Point and Full are
   explicit, and splitting into one/two ordinary intervals happens only at a grid/SQL adapter. Do not
   infer the complement of every ordered interval wider than 180°.
5. **Make OSM bbox production and consumption identical.** The immediate correction can persist the
   parser's directed narrow endpoints in the existing unreleased columns and have every consumer use
   the shared interval adapter. The final Pack-01 schema should store start/span plus an encoding
   version; one/two conventional index rows are optional physical derivatives, not the domain value.
   Because all OSM schemas are unreleased, reset/re-import development OSM data instead of building a
   production geometry-decoding migration unless internal-data retention is explicitly requested.
6. **Derive query coverage from metres and latitude.** Candidate bbox and cell coverage must be
   conservative, wrap-aware, computed/clamped in `Long`, and Full when the cap reaches a pole. Keep
   the separate product thresholds (50 m speed, 60 m street); share the conversion machinery rather
   than silently merging product policy.
7. **Bound enumeration before allocation/querying.** Logical row/column products use `Long`, each
   materialized batch and SQL `IN` list has an explicit cap, and excessive/full-polar coverage returns
   a typed result. Correctness may use a range-oriented query or explicit abstention when exhaustive
   coverage is unavailable; it must not guess from a truncated neighborhood or materialize 108,000
   keys in one array.
8. **Make optional coordinates atomic and semantically honest.** An unusable Wi-Fi/cell pair clears
   both fields and sets coordinate provenance to `UNKNOWN` while preserving the observation. Valid
   coordinates with an unknown/unrecognized provenance remain valid but use `UNKNOWN`. If attempted
   source information is diagnostically useful, record a separate rejection reason rather than
   overloading coordinate provenance.
9. **Use one checked E7 quantizer.** The provisional V10 conversion policy is nearest E7 with
   `roundToInt`'s documented ties-toward-positive-infinity behavior, but only after finite/range
   validation and before longitude/pole canonicalization. Audit exact-half-E7 export compatibility;
   version the encoding only if byte-for-byte historic reproduction is required.
10. **Put pure primitives in a cycle-free shared layer.** `:core:model` is the provisional home: it is
    Kotlin Multiplatform, `:stats:api` already depends on it, and Android consumers receive it through
    existing core dependencies. It should own checked coordinate construction, normalization/delta,
    circular intervals, and conservative radius bounds. Caller-specific thresholds, DAOs, and
    Android/GeographicLib adapters stay outside it.

These decisions close Pack 03 as a logical input to the Pack-01 schema. Pack 02 controls the
physical import/index safety contract and prescribes the measurements needed to choose its
topology/cell representation and total budgets; it does not redefine these semantics.

### Qualifications and deferred choices

1. **Do not add GeographicLib to the production hot path yet.** Accept shortest WGS-84 geodesic
   between encoded vertices as the exact reference semantics and use the supplied vectors plus an
   independent oracle in tests. Before choosing ECEF/ENU plus an ellipsoidal fallback, independently
   fuzz the proposed guard, measure real edge-length and near-threshold/ranking distributions,
   benchmark Android CPU/allocation and binary size, and decide how the Java implementation is
   isolated from common KMP code.
2. The report's analytical ENU error interval is a useful hypothesis, not yet a proved release
   guard. Projected geodesic curvature, minimizer bracketing/tolerance, degenerate/antipodal cases,
   and nearest-way ranking must all be checked against an exact oracle. Do not turn its numerical
   constants into production thresholds from static research alone.
3. Do not add `rtree_i32` merely because SQLite supports it. First compare query plans, database and
   WAL size, import cost, candidate count, and latency against the import-scoped cell design on the
   Pack-02 device/data matrix.
4. A full-longitude polar query is logically bounded but still operationally large. Measure a
   streamed/range implementation; a typed safe abstention is preferable to a 108,000-key allocation
   or accidental global scan while a better index is pending.
5. Keep raw external values only when a format/evidence requirement needs them. Canonical spatial
   equality and index/cache identity must not depend on whether the source wrote `+180°`, `-180°`, or
   a nonzero longitude at an exact pole.

### Local questions resolved from pack 03

| Question | Local answer |
|---|---|
| Is there already a GeographicLib/geodesic build dependency? | No production or test dependency is declared. Adding it would be a new dependency/licence/size/KMP decision. |
| Is there a cycle-free shared home for the algebra? | `:core:model` is KMP; `:stats:api` already depends on it, and `:core:base` exports it to the Android consumers. Exact platform adapters can remain outside. |
| Does current speed lookup fully use the geocoder's latitude-aware behavior? | No. It switched to the wrapped variable-radius API but calls the default radius of one and retains fixed 5,000-E7 bbox padding. |
| Does the current parser persist the circular bounds it calculated? | No. It uses the circular result for cells but still writes raw numeric longitude extrema to the way bbox. |
| Is `cellAndNeighbors` allocation bounded like import bbox enumeration? | No. Import bbox count is a capped `Long`; point neighbors still form `Int` bounds/product and one `LongArray`. |
| Does any production query/aggregation select by coordinate provenance without coordinates? | No such filter was found. Provenance is persisted/exported and used when enrichment updates coordinates; the model KDoc explicitly defines `UNKNOWN` as null coordinates. Resetting it is the honest current semantic. |
| Must released users receive an OSM bbox recovery migration? | No. OSM schemas are unreleased; development data can reset/re-import. Read-time canonicalization still matters for released non-OSM coordinate history. |

## Pack 04 verdict

The report is decision-useful and mostly accurate. It changes the roadmap in two important ways:

1. Honest compliance output is a release concern of its own. It is not enough to improve matching
   later while the current layer maps an unknown/non-finite ratio to `AT_LIMIT` and loses whether a
   speed value was explicit OSM data or a road-class heuristic.
2. The current matcher should not be tuned as though it were a complete Newson–Krumm matcher.
   Across different ways it uses snapped-point straight-line distance, and its stored polylines have
   no OSM-node connectivity. Candidate radius, transition beta, and switch-penalty tuning before
   topology exists would optimize the wrong transition model.

### Accepted repository findings

| Finding | Local disposition | Consequence |
|---|---|---|
| The vehicle-compliance "E2E" test mirrors a private provider and fakes `RoadMatcher`. | Confirmed. | Keep it as a layer/component test; it is not the import-to-render release gate. |
| The speed-source integration test seeds Room directly. | Confirmed. | It does not exercise PBF parsing/publication and does not validate the compliance path. |
| `NaN` is classified as `AT_LIMIT`. | Confirmed in production and explicitly locked by tests. | P0 if the compliance layer ships: unknown/non-finite input must not receive a normal compliance color. |
| Matcher output has no status, confidence, way/import identity, direction, or speed-limit provenance. | Confirmed. | The caller cannot distinguish no candidate, no path, a gap, ambiguity, or an ineligible limit. |
| Cross-way transition distance is a straight chord and cross-way rendered geometry can be a short chord. | Confirmed. | The current HMM is not topology-consistent and can connect nearby node-disconnected roads. |
| OSM node identity is discarded after parsing. | Confirmed. | Coordinate crossings cannot be distinguished from shared-node junctions in a routable graph. |
| `oneway=-1` is collapsed to the same boolean as forward one-way, roundabouts are not read, and the matcher drops the boolean anyway. | Confirmed. | A direction enum alone is insufficient; direction must survive parsing, storage, graph construction, matching, and output. |
| Missing/unsupported maxspeed becomes a road-class number; only an explicit flag survives in Room; the matcher drops that flag. | Confirmed. | The current layer can present a heuristic as road-limit compliance. |
| Compliance uses the destination observation's speed with the source way's limit. | Confirmed. | Limit-boundary intervals require an explicit association or suppression rule. |
| The layer evenly downsamples to 30,000 points without preserving maneuver or limit boundaries. | Confirmed. | Downsampling must become transition-aware before it is trusted for sparse/long histories. |
| Android bearing is not persisted through the location observation/sample schema. | Confirmed. | A heading experiment first needs a measured-data path for bearing and bearing accuracy; do not invent heading thresholds yet. |

### Multi-region finding strengthened by local inspection

Pack 04 correctly treats overlapping-import isolation as P0, but local inspection removes two of
its remaining uncertainties:

- Room's generated `AppDatabase_Impl` executes `PRAGMA foreign_keys = ON` on open. Room-built test
  databases use the same generated implementation. The exact production/test distinction suggested
  by the report is therefore not present at this commit.
- The normal controller uses unique WorkManager work with `ExistingWorkPolicy.REPLACE`, so two imports
  are not intentionally parsed concurrently. Replacement cancels the older worker, however, and a
  persisted BUILDING batch can still coexist with an older READY region while cancellation/cleanup
  completes.

With READY import A and overlapping BUILDING import B, B's `INSERT OR REPLACE` deletes A's global
`osm_way` row, cascades deletion of A's cell rows, inserts B's row, and then inserts B's cells in a
separate DAO call. Candidate queries do not filter by READY import. If any other READY import keeps
the global gate open, B can be queried before publication. Failure/cancellation cleanup then deletes
B's replacement row instead of restoring A. This is a confirmed schema/publication defect, although
pack 01 now selects the durable storage design and overlap precedence recorded above.

### External evidence accepted

- Newson and Krumm support Gaussian emissions, Viterbi decoding, and a transition likelihood based
  on the difference between observed great-circle displacement and **routed road-network distance**.
  Their parameters and single Seattle route do not transfer directly to Tracker.
- Android documents horizontal accuracy as a 68th-percentile radius; bearing is travel direction,
  not device orientation; bearing and speed accuracy are 68% uncertainty values; elapsed realtime is
  the appropriate monotonic clock. Treating the radius directly as a one-dimensional perpendicular
  sigma is therefore a model choice, not an API guarantee.
- OSM's de facto tagging documentation supports forward, reverse, explicit override, implied
  roundabout/motorway, reversible/alternating, mode exceptions, via-node/via-way restrictions, and
  multiple non-static maxspeed forms. Preserving raw source semantics is justified.
- Osmium is a suitable pinned XML-to-PBF fixture generator. Checked-in PBF bytes plus a semantic
  oracle are preferable to assuming byte-identical output across arbitrary writers or versions.
- Android Microbenchmark is appropriate for hot matcher primitives and reports timing/allocation
  data. Whole import/database/render flows still require instrumentation or macro-level measurement.

### Qualifications and non-decisions

1. The cited Newson–Krumm paper clearly establishes routed transitions, but the report's statement
   that the paper itself mandates Tracker's exact directed-edge representation is stronger than the
   reviewed citation. A directed graph is still required for Tracker because OSM one-way legality,
   directional limits, and restriction handling require it.
2. The long `MatchStatus` and `LimitKind` enums are good design inputs, not accepted API shapes. The
   first implementation may use a smaller typed result plus explicit reason codes, provided no
   unavailable state is collapsed into an ordinary bucket.
3. The report's numerical accuracy, coverage, latency, memory, calibration, and improvement
   thresholds are explicitly proposed values. Do not turn them into release gates until baseline
   distributions, a reference device, hard scenario strata, and product risk tolerance are fixed.
4. Public trajectory datasets are supplementary. Their license must be verified for the exact asset
   before bundling, and none substitutes for frozen PBF input, Tracker sensor metadata, directed route
   truth, observed sign truth, and final compliance labels.
5. Forward-backward confidence, second-order HMMs, UBODT, ML rectification, via-way restrictions,
   conditional rules, and jurisdiction default tables remain deferred. They are not prerequisites
   for the first honest deterministic pipeline.
6. The current UI description says "configured baseline limit", while the implementation uses the
   matched way's resolved OSM/class-default value when OSM matching succeeds. The copy and source
   semantics are inconsistent and must be corrected together; changing copy alone is insufficient.

## Pack 05 verdict

The report is strong, appropriately evidence-gated, and materially changes the altitude follow-up.
Its central datum finding is not merely a documentation concern: it is a reachable P1 semantic
defect. When AndroidX conversion fails or produces no MSL result, Tracker returns raw
`Location.altitude`, names it MSL, permits it to calibrate the barometer and update the filter,
overwrites the Android `Location` altitude field, persists the result in `location_sample.alt_m`,
exports it, and consumes it in maximum-altitude and gain/loss statistics. That fallback must be
removed before collecting data intended to validate the estimator.

The report also correctly refuses to infer noise, outlier, recalibration, aggregation, or restore
parameters from papers. Literature can identify failure modes and candidate methods; only
replayable Tracker traces split by device, environment, scenario, and collection cadence can select
Tracker's policy.

### Accepted repository findings

| Finding | Local disposition | Consequence |
|---|---|---|
| `AndroidXGeoidAltitudeConverter` returns ellipsoid altitude after absent MSL output or any exception. | Confirmed. | Conversion must return a typed success/failure outcome; only successful MSL may enter MSL fusion or calibration. |
| The fallback is reachable through persistence and user outputs. | Confirmed beyond the report's GitHub-only boundary. `PersistenceProcessor` writes `LocationSignal.altitudeM`; GPX/KML/JSON export it; trip insights use every non-null value. | A fail-closed converter test alone is insufficient; pipeline, persistence, export, and statistics contracts need coverage. |
| `LocationTrackerComponent` overwrites `android.location.Location.altitude` with the processed value. | Confirmed. | The platform field is documented as WGS84 ellipsoid altitude. Carry processed MSL separately instead of repurposing that field. |
| The pressure producer retains only sum/count and emits a cycle mean at cycle time. | Confirmed. | The persisted value is an aggregate, not a raw event. Event time, receipt time, span, count, variance, gaps, and rejection evidence are currently unrecoverable. |
| One GPS/pressure pair immediately creates or replaces the calibration baseline. | Confirmed. | Record lineage and demonstrate poisoning in replay; do not invent consensus, innovation, or rollback thresholds yet. Datum gating is safe immediately. |
| Kalman constants and gates are fixed; the update has no innovation decision, robust weighting, covariance diagnostics, or gap policy. | Confirmed. Zero measurement variance is accepted and the simple covariance update has no explicit numerical guard. | Add diagnostic/property-test surfaces now; measurement and reset policies remain evidence-gated. Joseph form is optional and must be equivalence/property tested. |
| Fusion/calibration state is process-local and resettable but not persisted. | Confirmed. | Keep process-death restoration disabled. First measure cold-start harm and lifecycle discontinuities. |
| Research trace v2 omits pressure events, conversion/calibration decisions, innovations, covariance, and filter state. | Confirmed. | It cannot answer Pack 05's decisions. A minimum altitude evidence contract and deterministic replay are prerequisites. |
| The trace README lists `presence_interval`, but the exporter neither writes nor counts it. | Confirmed. | Schema documentation, encoder, decoder, terminal counts, and conformance tests must be generated or validated from one contract. |
| Shared and durable altitude comments disagree about datum. | Confirmed. | Bare altitude values at module, persistence, and import/export boundaries are unsafe without datum metadata. |

### External contracts accepted

- Android [`Location`](https://developer.android.com/reference/android/location/Location) documents
  `altitude` as meters above the WGS84 reference ellipsoid; MSL is a distinct property. Vertical
  accuracy is a 68th-percentile uncertainty for the ellipsoid altitude, and MSL accuracy is
  separately represented when available.
- [`AltitudeConverterCompat`](https://developer.android.com/reference/androidx/core/location/altitude/AltitudeConverterCompat)
  converts WGS84 altitude to MSL, must run off the main thread, and may throw `IOException` or
  `IllegalArgumentException`. A caught exception is a conversion failure, not permission to relabel
  the input.
- [`SensorEvent.timestamp`](https://developer.android.com/reference/android/hardware/SensorEvent#timestamp)
  is monotonic nanoseconds in the same time base as `elapsedRealtimeNanos()`. It is the source event
  time required for pressure/location alignment; callback or cycle time is not an equivalent
  substitute.
- Android [`SensorManager`](https://developer.android.com/reference/android/hardware/SensorManager)
  accuracy is categorical status, not a barometer variance. Compatibility
  requirements and recommendations do not establish one universal Tracker measurement-noise value
  across its API 26–37 device population.

### Decisions accepted now

1. **Fail closed on MSL conversion.** Preserve the raw ellipsoid observation separately, but never
   send it to an MSL calibration/update path, persist it as `alt_m`, export it as MSL, or use it in an
   MSL statistic.
2. **Make datum and conversion outcome explicit.** New processed altitude rows need compact,
   versioned datum/source/conversion fields. Historical or imported bare values are
   `UNKNOWN_LEGACY` unless their provenance is demonstrable. Unknown and incompatible datums do not
   cross-calibrate or contribute a cross-datum gain/loss delta.
3. **Stop overloading Android's altitude field.** Keep the provider `Location` ellipsoid altitude
   intact and carry the processed estimate as an explicit collection/signal field.
4. **Correct pressure terminology.** Current `PressureReading`, `PressureSignal`, and
   `pressure_sample` values are cycle aggregates. Rename documentation and APIs without pretending
   that missing event-level evidence can be reconstructed.
5. **Define a minimum shared research envelope.** Evolve trace v2 rather than creating an unrelated
   altitude format. Align trace/run identity, sequence, clock domain, lifecycle, privacy class,
   schema version, loss counts, and terminal integrity with matcher evidence.
6. **Build deterministic replay before policy changes.** The same ordered source stream must drive
   baseline and candidate implementations with explicit configuration versions and decision logs.
7. **Keep process-death fusion persistence disabled.** Logging pause/restart/reboot behavior is
   worthwhile now; restoration waits for demonstrated user harm and a separately reviewed secure
   checkpoint contract.

### Minimum altitude evidence contract

The report's full schema is a valuable superset. The first executable contract must capture enough
to reproduce and audit the estimator without imposing per-event UUID and object-list overhead:

- manifest/config/consent, trace/run/session identity, boot/clock-domain identity, algorithm and
  schema versions, and privacy mode;
- sensor descriptor plus raw pressure event sequence, event elapsed time, callback-receipt elapsed
  time, pressure, categorical accuracy/status, validity decision, and discontinuity/loss evidence;
- aggregate sequence and source-event sequence range, window start/end/effective time, valid and
  rejected counts, span/max gap, mean, variance, extrema, chosen value, and aggregation version;
- provider location identity and timing, ellipsoid altitude/accuracy, conversion outcome/model/path,
  and the selected MSL value when conversion succeeded;
- calibration proposal/accept/reject/replacement with source sequence ranges and prior/new state;
- prediction and measurement decisions with source type, event time, `dt`, state/covariance before
  and after, configured Q/R, innovation/innovation covariance, and accept/reject reason;
- lifecycle/reset/gap/restart/reboot markers, trace-buffer loss ranges and counts, truth markers, and
  terminal record counts.

Use monotonically increasing trace-scoped integer sequences and contiguous sequence ranges for hot
events. UUIDs remain appropriate for the trace/run/session and durable provider fix identity, not
for every 5 Hz pressure event. Full provenance chains, sensor fingerprint detail, raw routes,
innovations linked to location, and truth markers remain explicit-consent research data; production
rows should use compact enums/version identifiers. Raw research traces remain encrypted,
participant-initiated, never auto-uploaded, and independently deletable.

### Qualifications and non-decisions

1. The proposed `AltitudeValue` is the correct semantic model, not a requirement to persist a list
   of transforms and source-event IDs in every production row. Normalize the production contract to
   compact datum/source/status/model/version columns and keep detailed lineage in the research
   trace.
2. Do not immediately add every aggregate statistic to production `pressure_sample`. Add only fields
   required by an accepted runtime algorithm or durable user feature. Debug research capture owns
   raw events and the comparison superset; the existing row should first be named honestly.
3. Do not select mean/latest/median/trimmed/Huber/sequential/fixed-grid handling; Q/R; accuracy floors;
   innovations; derivative/spike/gap gates; calibration consensus/interval/rollback; or state-restore
   thresholds from this report. All remain replay and field-study decisions.
4. The report's device counts, repetition counts, 40/30/30 split, acceptance margins, and retention
   durations are proposed preregistration inputs, not adopted policy. Validate the instrument and
   truth synchronization in a small pilot before committing the full study.
5. Sensor names/vendor/version can fingerprint a device even after hashing. Use a study-specific
   salt, minimize descriptor precision, support coordinate-free indoor studies, and obtain explicit
   consent. Debug build type alone is not consent or access control.
6. Android model MSL is not automatically interchangeable with a local survey datum. Every truth
   source must state its vertical datum/model/transformation and uncertainty; comparisons are
   invalid when that relationship is unknown.

### Staged evidence program

1. **Synthetic contract stage:** conversion success/failure, mixed-datum segmentation, future-event
   rejection, out-of-order and duplicate events, zero/negative/nonfinite inputs, long gaps, restart,
   reboot, trace loss, encryption, and bitwise/tolerance-declared deterministic replay.
2. **Instrumentation pilot:** two or three materially different devices; stationary, stairs,
   elevator, outdoor grade, background/foreground, conversion failure, and process restart. The
   goal is to validate completeness, loss accounting, clock/truth alignment, privacy, storage,
   energy, and replay—not to tune.
3. **Preregistered study:** expand device/API/environment coverage only after the pilot, freeze
   primary metrics and scenario strata, split by whole device-day/run rather than points, and retain
   a blind plus leave-device-out test set.
4. **Policy selection:** compare aggregation, robust update, calibration, and cold-start candidates
   on identical replays. Accept only candidates that satisfy structural gates and preregistered
   accuracy/tail/latency/energy/storage criteria without hiding a device-class regression.

## Pack 06 verdict

The report's central diagnosis is accepted: the current segmentation path is partly duration-based
but is not cadence-independent. It discards most of the timing, identity, uncertainty, batching,
missingness, and policy data already present in `TrackingSignal`; begins stop candidacy after a fixed
number of samples; averages speed and votes activity once per arrival; treats absent steps as zero;
and lets a later still sample complete stop dwell across an unobserved interval. A denser or more
bursty representation of the same physical trip can therefore change boundaries, totals, and mode.

The proposed end state is also directionally accepted: a small, explicit-duration, bounded-memory
streaming model with `UNKNOWN`/unresolved intervals is a better product shape than a black-box
whole-trip classifier. That is an architecture target, not authority to implement the report's
illustrative horizons, evidence rates, transition rules, or HSMM. Tracker does not yet have the
labeled, multi-device, multi-policy traces needed to choose them.

### Accepted repository findings and local corrections

| Finding | Local disposition | Consequence |
|---|---|---|
| The segment adapter is lossy and drops every cycle without a curated location. | Confirmed. It also converts nullable steps to zero and drops elapsed time/domain, speed accuracy, activity freshness, source/WAL identity, batch/receipt metadata, raw/rejected-location evidence, and policy. | A V2 observation/replay boundary must be built from `TrackingSignal`, not by extending the current `SegmentSignal` one field at a time. Location must remain optional. |
| Decisions depend on execution wall time. | Confirmed. Partial mode selection calls injected `System.currentTimeMillis`, while the recorded trip timestamps may be historical during replay. | Remove the external-clock dependency immediately. Full live causal timing must use elapsed time only within one declared clock domain; epoch time remains display metadata. |
| Stop candidacy and feature aggregation are cadence-sensitive. | Confirmed. Three still arrivals begin stop pending; speed/activity are sample-weighted; one maximum speed can force rail; `totalDistanceM` is unused; missing steps can satisfy the cycle fallback. | Characterize V1 under resampling, then replace counts/sample means only after trace evidence selects support and eligibility policy. Do not retune constants in place. |
| Gaps can become positive stop/trip-duration evidence. | Confirmed. `STOP_PENDING` measures wall-time difference to the next still sample, and locationless cycles never reach the detector. | V2 must conserve unsupported time as unresolved rather than inventing stillness or movement. The support horizon and whether a long gap splits a trip are product/data decisions. |
| Detector serialization establishes production recovery. | Rejected. `serialize`/`deserialize` and processor delegation exist, but `ProcessorPipeline` never stores generic processor checkpoints and supplies no checkpoint in `ProcessorContext`. | Checkpoint framing is useful only with an explicit durable owner/cursor and atomic progress protocol. Do not claim process-death continuity today. |
| `onStop()` force-finalizes before reset. | Corrected. Current and pinned code call `detector.reset()` directly; `forceEnd()` is not invoked by `SegmentDetectorProcessor`. | A clean stop/de-escalation drops an open detector trip and only flushes events already pending. Whether user stop should finalize, suspend, or abandon is a product/lifecycle decision; process death alone must not invent an arrival. |
| Stable IDs are sufficient for detector-local provider dedupe. | Qualified. The outer WAL ID is stable across retries, but each location `sourceEventId` is a new UUID minted for each callback/fix arrival. It is not a provider-native identity stable across a repeated callback. | State exactly which duplicate class an ID can prove. Do not add a bounded recent-ID cache until the duplicate source, memory bound, and eviction/reorder interaction are defined. |
| Batched locations require ordering treatment. | Confirmed with a narrower scope. Google guarantees order within one batch, while batches may arrive out of order relative to other batches. Tracker preserves list order but has no cross-batch reorder stage before segmentation. | Offline variants must include cross-batch permutation. A live watermark/reorder horizon remains measurement-gated. |
| Activity evidence is richer at the platform boundary. | Confirmed. Google exposes detection elapsed time and the full probable-activity list, but `ActivityReceiver` keeps only the most probable item and substitutes callback receipt elapsed time for detection time. | Record this loss as a capability flag now. Preserving source event time is worthwhile; widening durable production activity records to the full list waits for a demonstrated consumer/storage need. |
| Route compression is part of the live segmentation path. | Rejected. Only its unit tests reference `RouteCompressor`; no production caller was found. | Do not tune or harden it as part of cadence work. Wire it only with a named product consumer and executable contract. |
| Trip domain events have an established user-facing consumer. | Not established. Events are persisted and ordinary module consumers advance over them, but current consumers handle `SessionEnded` and other events, not `TripStarted`/`TripCompleted`. The segment processor directly notifies the streaming aggregator on completion. | Audit the direct aggregator effect and any future consumer before changing event timing/schema. Additive diagnostic fields are not a substitute for a product contract. |

### External contracts accepted

- Android documents Unix epoch location time as non-monotonic and elapsed realtime as the reliable
  same-boot ordering clock. It must not be compared across boots/devices; Tracker's conservative
  service-session `clockDomainId` is an appropriate narrower boundary.
- Fused Location Provider documents that locations are ordered inside one batch, but batched/passive
  requests may deliver batches out of order relative to each other and may initially deliver older
  fixes. Tracker must use acquisition time, not callback order, when a future live reorder policy is
  selected.
- Google activity recognition exposes source elapsed time and the full probable list; confidences
  can overlap and do not form a mutually exclusive probability distribution. Treat them as evidence,
  not calibrated Tracker probabilities.
- Android step-counter values are cumulative since reboot while the sensor is active and may be
  delivered as an aggregate after suspend. A valid delta therefore needs explicit source/reset/span
  semantics; absence is not zero.
- HSMMs support explicit variable state durations and filtered/smoothed inference. That establishes
  relevance, not superiority for Tracker. They remain deferred until a rules baseline, labels,
  calibration/abstention tests, and on-device cost evidence exist.

### Structural contract accepted now

The report's invariants are more valuable than its illustrative constants. Adopt them as candidate
V2 gates, with exact definitions in tests:

1. identity replay is deterministic and exact known duplicates are idempotent;
2. non-informative densification cannot create evidence or move a boundary;
3. declared bounded thinning degrades uncertainty/coverage rather than silently changing truth;
4. equivalent batch groupings/order produce equivalent committed output after the declared
   watermark, and every time interval has one owner;
5. elapsed time is conserved among supported state, transition uncertainty, and unresolved gap;
6. more missingness cannot increase confidence, and missing channels are never converted to zero;
7. online and offline replay agree under the same revision horizon, including checkpoint-prefix
   equivalence and right-censored endings;
8. representation-only changes preserve source-unique distance, steps, supported duration, and
   uncertainty accounting.

These are qualitative. “Bounded thinning,” equivalence tolerances, support horizons, revision
horizon, and any allowed latency still require a frozen protocol rather than invented numbers.

### Immediate, evidence-independent work

1. Remove `System.currentTimeMillis` from detector decisions. Make V1 partial-mode selection depend
   only on the current input trace time and add a replay-at-different-host-times regression test.
   This closes execution-time nondeterminism; it does not make wall timestamps monotonic.
2. Define a versioned canonical segmentation observation in the common research envelope. Preserve
   event epoch and elapsed time/domain, receipt time, exact available identity scopes, location
   observation/decision/accuracy/speed-accuracy/batch/policy metadata, nullable steps/reset/span,
   activity value/freshness/source time capability, and lifecycle/gap/loss records. Do not require a
   location and do not fabricate fields the current collector discarded.
3. Add a deterministic in-memory/offline V1 replay adapter and baseline snapshots. Keep the existing
   user-facing detector behavior unchanged except the external-clock correction; make its lossy
   projection explicit as `legacy` rather than treating it as the V2 schema.
4. Add reusable trace transformations for densification, thinning, jitter, batch regrouping and
   cross-batch permutation, duplicates, gaps, degraded accuracy, jumps, missing channels, step reset,
   and clock-domain boundaries. Emit sensitivity/conservation diagnostics without inventing pass
   thresholds for V1.
5. Add executable structural tests for observation round-trip, capability/loss accounting,
   deterministic replay, and the transformations themselves. Candidate V2 implementations must pass
   the accepted invariants before shadow or user-visible rollout.

### Evidence-gated or rejected for this phase

- Do not replace three still cycles with an arbitrary number of seconds, select per-channel support
  horizons, add a live watermark/reorder buffer, or choose evidence rates/hysteresis from literature.
- Do not time-weight speed/activity until eligibility, interval ownership, gap handling, and robust
  sustained-speed representation are fixed and compared on the same traces.
- Do not infer rail from one maximum fix, cycling from missing/zero steps, or confident movement from
  absence. The first V2 mode output should support `UNKNOWN` and mixed-mode suspicion; user-visible
  leg segmentation waits for labeled transitions and a product decision.
- Do not persist raw routes or shadow evidence merely because the code is debug-only. Reuse Pack 05's
  consent, encryption, bounded buffering, deletion/withdrawal, loss-accounting, and coordinate-free
  study requirements.
- Do not enable generic detector process-death restore, silently rewrite old trip labels, finalize a
  trip because observations ceased, or add an HSMM/neural classifier before the field program.
- Public datasets such as STAGA, GeoLife, SHL, and ExtraSensory are useful for replay mechanics and
  adversarial transformations only. Verify the exact asset license before use or redistribution;
  they cannot select Tracker thresholds or replace Tracker device/policy traces.

### Staged segmentation evidence program

1. **Synthetic/replay foundation:** validate schema, source-time ordering, duplicate scopes,
   cross-domain rejection, interval conservation, checkpoint prefixes, right-censoring, and all
   transformation generators against authored traces.
2. **Instrumentation pilot:** use a small diverse device/policy/scenario matrix to establish whether
   acquisition/receipt/batch/activity/step/loss metadata is complete and replayable. Do not tune from
   this pilot.
3. **Preregistered field study:** record physical and semantic truth, placements, lifecycle/user
   intent, policy changes, GPS-denied areas, stops/departures, and mixed modes. Split by whole person,
   route/corridor, device, and policy—not points from the same trip.
4. **Rules comparison:** compare V1 and candidate time-based reducers on identical original and
   transformed replays using boundary intervals/latency, state time-IoU, stop/departure metrics,
   unresolved coverage, conservation, mode metrics, abstention/calibration, and cadence sensitivity.
5. **Optional model stage:** consider a compact HSMM only if labeled error patterns remain material
   after the cadence-neutral rules baseline and measured on-device runtime/memory/checkpoint/battery
   cost fits a declared device budget.

## Revised implementation order

### Phase A-preflight — fail-closed PBF containment and safe-intake foundation

Do this before any feature work that leaves `.osm.pbf` import reachable:

1. Add one centrally owned capability/release gate, defaulting to unavailable until the complete
   bounded pipeline is certified. Enforce it in UI/controller and at Worker entry. A direct Worker
   call must fail with a stable redacted code before URI open, job-directory creation, or DB insert.
2. Replace the invalid positive test writer with a valid `OSMHeader` plus homogeneous `OSMData`
   groups. Add small malformed/truncated/compression/repeated-way fixtures and an allocation/
   reservation spy. Keep the existing unsafe parser behind the gate for characterization only.
3. In a focused security-reviewed slice, add the private actual-byte-counted source snapshot and
   strict framing/raw-zlib decoder with typed errors and cancellation. Do not connect this reader to
   production import until primitive preflight/streaming, combined reservations, output accounting,
   authority-aware persistence, and crash reconciliation are also complete.
4. Pin and assert the resolved `osmpbf`/protobuf graph for every release/debug/test configuration.
   The current 4.33.2 POM versus debug-only 4.35.1 declaration is not an acceptable implicit skew.
5. Remove false “100 MiB + distinct nodes implies bounded RSS” claims and prohibit `Throwable`/OOM
   recovery, raw URI/exception leakage, and automatic retry of deterministic parser/policy failures.

This containment phase does not authorize guessed dynamic budgets or an improvised custom protobuf
parser. The feature remains unavailable until Phase B/C integration, adversarial proof, and the Pack
02 device benchmark gate all pass.

### Phase A0 — shared coordinate contract and bounded geospatial correctness

This is now evidence-independent and can proceed before the final OSM storage redesign:

1. Add checked coordinate construction, canonical longitude/delta/interpolation, conservative
   metre-radius bounds, and `CircularLongitudeInterval` to a pure shared layer. Replace private
   copies incrementally; do not combine the caller's 50 m and 60 m product thresholds.
2. Finish the current bounded correction: canonicalize grid/cache keys, compute speed candidate
   longitude cells and bbox padding from latitude, use bounded/typed enumeration, and persist the
   same directed interval the parser used to build cells. Make speed, geocoder, reindexer, and
   matcher consume one interval adapter.
3. Harden PBF quantization and polyline decoding with finite/range/accumulation checks before
   narrowing. Invalid OSM geometry is rejected with a classified count rather than wrapped.
4. Complete the JSON optional-coordinate contract: retain the Wi-Fi/cell observation, clear both
   coordinates together, and set provenance to `UNKNOWN`; canonicalize valid `+180°`/pole values.
5. Add deterministic algebra, dateline, Prague, 89°, pole, parser-consumer, allocation, cache, codec,
   and import tests. Kill subtraction-before-widening, fixed-longitude-padding, fixed-neighborhood,
   raw-linear-bbox, distinct-`+180°`, and provenance-preservation mutants.

Do not add a production GeographicLib dependency, exact-segment minimizer, R-tree, global OSM
migration, or inferred numerical ambiguity band in this phase. Exact WGS-84 vectors are the oracle;
runtime model selection waits for the Pack-02/04 benchmark and trace program.

### Phase A1 — matcher honest-output and executable-contract work

These tasks do not require representative field traces and should precede matcher feature tuning:

1. Extract the production compliance provider from `DefaultLayerRegistry` so tests execute the real
   mapping from stored samples and `MatchedEdge` values to layer input.
2. Replace total numeric classification with eligibility-aware output. Zero, non-finite, unknown,
   unsupported, heuristic-as-road-limit, no-match, no-path, unresolved boundary, and gap cases must
   be suppressed or represented as an explicitly non-compliance style.
3. Preserve the existing five buckets only for eligible finite ratios. Add focused reversal tests
   proving that `NaN`, zero/invalid limits, and missing semantics produce no compliance category.
4. Correct the layer title/description and legend semantics after the source policy is decided.
5. Establish a minimal diagnostic record containing input indices, match/unavailable reason,
   selected import/way/edge identity when present, limit kind, suppression reason, and timings.

### Phase A2 — altitude datum correctness and replay prerequisites

This work changes no empirical estimator parameter and can proceed while OSM device benchmarks and
altitude traces are pending:

1. Replace the converter's nullable/bare-double contract with a typed result. Distinguish success,
   absent output, invalid input, I/O failure, and unexpected failure without exposing sensitive
   exception content in production data.
2. Keep Android `Location.altitude` as the provider's ellipsoid value. Carry processed MSL/fused
   altitude, datum, source, conversion status, and model/version through `MutableCollectionData`,
   `LocationSignal`, durable signal serialization/recovery, Room, domain models, and supported
   export/import formats.
3. On conversion failure, retain raw ellipsoid evidence but produce no MSL GPS update and no MSL
   calibration. Add end-to-end tests proving the failed value cannot reach `alt_m`, calibration,
   gain/loss, maximum altitude, or an MSL export field.
4. Define historical/imported bare altitude as unknown unless a format-specific contract proves a
   datum. Segment downstream altitude computations by compatible datum and reset the comparison
   baseline at unknown/change/gap/clock-domain boundaries.
5. Correct pressure aggregate naming and contradictory altitude KDocs. Do not enlarge the production
   pressure table with speculative statistics.
6. Introduce versioned trace-v3 record models, conformance validation, loss accounting, and a
   deterministic in-memory/offline replay core. Start with synthetic/test sinks; do not create an
   unencrypted on-device raw-pressure store or collection UI until its consent, encryption,
   deletion, and bounded-buffer behavior pass privacy review.
7. Add numerical property tests and diagnostic snapshots. A Joseph covariance update may land only
   if baseline-equivalence and randomized finite/symmetry/PSD tests pass; do not change Q/R, gates,
   calibration, aggregation, or restore policy in this phase.

### Phase A3 — segmentation determinism and cadence-evidence foundation

This phase intentionally does not replace the live detector:

1. Remove the detector's dependency on host execution time. Pass the current input time explicitly
   into partial-mode/stop-timeout selection and prove that the same trace produces the same events
   when replayed at different host dates.
2. Add the canonical segmentation observation, legacy V1 projection, deterministic offline runner,
   versioned outputs, and baseline snapshots to the shared trace/replay foundation. Preserve nullable
   channels and capability/loss flags; do not require a curated location.
3. Add authored transformation generators and sensitivity/conservation reports for cadence,
   batching, cross-batch order, duplicates, gaps, accuracy degradation, jumps, missing sensors,
   step resets, wall-clock changes, and clock-domain boundaries.
4. Treat V1 failures under these transformations as measured baseline behavior, not as permission to
   tune its constants. Keep V1 user-facing until a candidate V2 time-based reducer has frozen rules,
   passes the structural invariants, and succeeds on held-out Tracker traces.
5. Do not add live reordering, support horizons, evidence rates, checkpoint restore, raw-route
   persistence, shadow rollout, HSMM inference, or new mode thresholds in this phase.

### Phase B — deterministic import-to-render fixture

1. Check in a small, authored OSM XML source, a PBF generated by a pinned Osmium toolchain, hashes,
   an independent semantic oracle, attribution statement, and regeneration instructions.
2. Keep a separate generated adversarial corpus for framing, decompression, header/features,
   primitive structure, repeated-reference growth, output amplification, cancellation, disk, and
   crash boundaries. Do not use the old headerless/mixed-group helper as a positive conformance file.
3. Refactor worker-only acquire/parse/persist/publish logic behind a production coordinator that
   accepts an authoritative job and private snapshot. Keep SAF, notifications, and WorkManager in
   the worker adapter; neither pass reopens the provider URI.
4. Execute the strict reader, bounded primitive parser, persistence/publication path, Room queries,
   matcher, production provider,
   layer processing, and MapLibre model construction in the canonical test.
5. Include overlapping imports, missing node, antimeridian, parallel roads, coordinate-crossing but
   node-disconnected roads, gaps, boundaries, and unavailable-limit cases. Direction/restriction
   expectations may initially be marked unsupported until their implementation phase.

### Phase C — storage/publication release gate

Pack 01 selects per-import immutable generations with import-scoped logical identity, atomic
publication, revision-keyed cache invalidation, and a globally gated index rebuild. Before changing
the schema, reconcile the physical representation with Pack 02's resource contract and measured
budgets plus Pack 04's directed-topology inputs. Then require all of:

- actual source bytes, framing/decompression, primitive structures, retained graph, output, disk,
  and work are checked/reserved before their disallowed allocation/write;
- both passes consume one completed private snapshot, and cancellation/death/reboot reconciliation
  removes abandoned job artifacts without relying on a catch block;
- BUILDING rows are unreachable by every production road consumer;
- overlapping READY imports have explicit deterministic precedence;
- failed/replaced/cancelled import B leaves READY import A byte-for-byte/semantically intact;
- deleting one retained region cannot delete roads still owned by another;
- reindexing has the same visibility and ownership rules;
- schema-40 exported-schema and migration-from-last-released tests cover the chosen identities and
  foreign keys, winner precedence, and fresh-install/migration invariant parity.

The import capability stays disabled until both the resource-safety and storage/publication gates
pass. A safe parser writing into the old global `REPLACE` schema is not releasable, and a correct
per-import schema fed by the old unbounded parser is not releasable.

Because schema 40 and every OSM migration are unreleased, revise them directly rather than carrying
forward the destructive global identity or writing a production recovery migration for internal
data. Preserve future graph inputs during this redesign: OSM node identity, raw/resolved one-way
semantics, and raw/relevant speed-limit provenance. Do not add shadow generations or a global
deduplicated way table without measurements.

### Phase D — directed topology and routed HMM

This is worthwhile if Tracker intends to ship the layer as road-specific vehicle compliance. If the
scope cannot fit, the safe product alternative is to gate/disable that layer rather than tune and
present the current chord-based matcher as trustworthy.

1. Split retained ways into node-connected directed edges. OSM node identity, not coordinate
   equality, defines connectivity.
2. Resolve forward/reverse/implicit/override direction and retain unsupported dynamic direction as
   unsupported rather than bidirectional.
3. Replace cross-way chord transitions with bounded directed route distance and explicit unreachable
   results. Return actual routed geometry rather than a visual chord.
4. Carry selected directed edge/way/import and limit provenance through compliance.
5. Only then calibrate emission sigma mapping, transition beta, route bounds, candidate radius/K, and
   any heading evidence on Tracker traces.

### Phase E — evidence-gated features

- Add uncertainty-gated bearing/displacement evidence only after bearing and bearing accuracy are
  captured and stationary invariance is demonstrated.
- Count and classify restriction relations during import. Implement the simple via-node motor-vehicle
  subset only if eligible illegal-turn errors are material after directed routing.
- Calibrate abstention and compliance coverage on held-out routes split by corridor, direction,
  device, driver, and day. Do not split individual points from one route across sets.
- Consider jurisdiction defaults only as a versioned product/maintenance commitment with region and
  applicability data. Road-class heuristics should not be presented as confirmed road limits.

## Release gates accepted now

The following are qualitative gates; they do not need field-derived numeric thresholds:

1. No BUILDING import affects any READY import query result.
2. Failure, cancellation, replacement, and region deletion preserve unrelated and overlapping READY
   data according to the declared precedence policy.
3. No unknown, unsupported, non-finite, no-path, gap, ambiguous, or ineligible-limit interval is
   rendered as one of the five normal compliance buckets.
4. A checked-in PBF reaches the production parser, persistence/publication code, Room, real matcher,
   production compliance provider, layer, and rendering model in a deterministic test.
5. Direction, overpass/underpass connectivity, routed geometry, and limit provenance have exact
   synthetic oracles before the directed matcher is enabled.
6. Performance baselines are recorded before accepting a topology implementation; final numeric
   budgets wait for a declared low-end device and representative import/trace matrix.
7. No release artifact uses an SQLite source affected by the WAL-reset bug; every packaged ABI's
   runtime version and source ID are asserted in tests/evidence.
8. No ellipsoid, unknown, failed-conversion, or incompatible-datum altitude is labeled/exported as
   MSL, used to calibrate an MSL pressure baseline, or joined across a datum boundary for gain/loss.
9. Research traces declare clock domains and loss explicitly, contain the source and decision data
   required to replay each emitted altitude, and pass authenticated-export/decode conformance
   without an unapproved plaintext staging file.
10. Process-death fusion state remains disabled until a preregistered study demonstrates benefit and
    validates same-boot/sensor/datum/config/staleness eligibility plus harmful-restore limits.
11. New spatial identity canonicalizes equivalent antimeridian and exact-pole representations;
    parser bbox persistence, grid membership, bbox filtering, cache keys, and distance consumers use
    one declared circular-longitude contract.
12. No valid metre-radius road query is narrowed by a latitude-independent longitude margin or fixed
    cell neighborhood, and no grid path allocates/query-binds an unchecked logical cell count.
13. Optional coordinate pairs are either both checked/canonical or both absent with `UNKNOWN`
    coordinate provenance; invalid PBF/polyline coordinates cannot enter Room by integer saturation
    or unchecked narrowing.
14. Segmentation replay cannot observe the host wall clock. A recorded input and declared algorithm
    version produce the same ordered events and diagnostics regardless of replay date.
15. A future V2 segmentation candidate cannot ship or enter user-visible shadow persistence until it
    proves single interval ownership, time conservation, missingness neutrality, duplicate identity
    scope, online/offline agreement, right-censoring, and checkpoint-prefix equivalence.
16. Unsupported observation time remains explicitly unresolved. Gaps, missing channels, process
    death, or absent future samples cannot be converted into stationary/moving confidence or a
    fabricated trip boundary.
17. Any research or shadow capture of raw routes/sensor evidence uses explicit consent,
    authenticated encryption, bounded loss-accounted buffering, deletion/withdrawal, and declared
    capabilities; debug build type alone is not authorization.
18. If the full safe PBF path is incomplete, offline PBF import is unavailable in release and a
    direct Worker invocation fails before reading the URI or creating database/job state.
19. Provider-reported size is never a safety bound. One private snapshot is counted exactly and
    rejects the first byte above 100 MiB; both passes read only that completed snapshot.
20. No untrusted PBF length, `raw_size`, entity/string/tag/ref count, table resize, geometry/cell
    output, Room binding, or database write occurs before a checked combined reservation accepts it.
21. Clean EOF is distinct from truncation; raw/zlib payload and header/feature/primitive structure
    obey the accepted PBF contract; protobuf InputStream limits are not claimed to protect an
    already materialized byte-array/ByteString path.
22. Resource, malformed, unsupported, and storage-policy failures are stable permanent failures,
    are not automatically retried, expose no URI/content/OSM identity, and never use OOM/`Throwable`
    as routine control flow.
23. Catch cleanup is not the recovery protocol. Cancellation, replacement, process kill, force-stop,
    and reboot leave no query-visible partial generation or orphan private snapshot/staging, and a
    stale worker cannot clean up another generation.
24. Every fixed limit has boundary-minus/exact/plus and allocation-before-check tests. Dynamic
    coefficients and budgets ship only after the lowest-device/ABI held-out benchmark charge never
    underpredicts observed managed-memory growth.

## Local questions resolved from pack 04

| Question | Answer at the pinned commit |
|---|---|
| Are Room foreign keys enabled? | Yes. Generated Room code enables them on open. |
| Can ordinary imports run concurrently? | The controller uses one unique work name with `REPLACE`; intentional parallel imports are prevented, but replacement/cancellation overlap remains. |
| Is cancellation after a persisted batch possible? | Yes. Batches are persisted before READY; caught cancellation runs best-effort cleanup. Process death leaves BUILDING rows for startup cleanup. |
| Are bearing and bearing accuracy persisted? | No. Elapsed realtime, horizontal/speed accuracy, provider, mock state, and permission precision are persisted; bearing fields are not. |
| Is speed always live Android `Location.getSpeed()`? | No. Live tracking maps the provider's `Location.speed`, but imported `LocationSample` rows can also contain speed values. The map query does not distinguish origin. |
| Can the current layer render unknown separately? | It can suppress an edge by not providing it, but it has only five normal bucket styles. A dashed/neutral uncertain path requires a new semantic/style path. |
| Does even downsampling preserve turns or limit changes? | No. It pins endpoints and selects by row index only. |
| Is production-provider extraction blocked by an obvious module/Hilt cycle? | No obvious blocker was found. The registry already depends on the road-matcher API; extraction is a bounded testability refactor. |

## Remaining evidence and final synthesis input

All six independent research areas are received. Pack 07 can now reconcile them; it is a final
decision pass, not an additional missing domain report. It must not fabricate the evidence that the
reports correctly leave open:

- Pack 02's lowest-device/ABI parser, heap/native/PSS, disk/SQLite/WAL, cancellation, and workload
  benchmark results, including surrogate-versus-composite graph storage/query measurements;
- Pack 03's runtime exact-geodesic/fallback guard and polar-query measurements;
- Pack 04's representative route labels, topology accuracy, candidate/routing cost, and calibrated
  abstention/compliance evidence;
- Pack 05's versioned altitude traces, device/scenario study, conversion-failure rate, and empirical
  fusion/calibration/gap/persistence parameters;
- Pack 06's Tracker segmentation labels, V1 transformation baselines, product gap/clean-stop/mixed-
  mode semantics, and any later V2 reducer/model measurements.

Pack 06 resolves the earlier downsampling question conservatively: do not share the current
row-index-based compliance-map downsampler with segmentation. A future shared timed-interval
transformation is acceptable only if both consumers' endpoint, gap, transition, and provenance
invariants are explicit and tested; reuse is not a release dependency.

## Conflict ledger

| Reports | Conflict or dependency | Resolution/status |
|---|---|---|
| 01 ↔ 04 | Pack 01's representative schema stores boolean one-way and a resolved numeric speed, while pack 04 proves those semantics are too lossy for directed matching and honest compliance. | Do not implement the representative SQL literally. Import-scoped logical ownership is accepted; final way/node/edge columns must preserve pack 04 semantics and satisfy Pack 02's resource contract and measurements. |
| Pack 01 report A ↔ report B | The first report uses composite physical keys and publication-order precedence; the second favors a surrogate way-instance key and OSM-version precedence. | Accept logical `UNIQUE(import_id, osm_way_id)`. Provisionally favor a surrogate for narrower cell rows but measure it with Pack 02's protocol. Prefer highest known OSM version, falling back to publication order when metadata is absent/tied. |
| Pack 01 report B ↔ release ledger | The second report classifies the 31→32 empty-index migration as a released-user P1 and proposes a full 40→41 recovery. | Released users start at schema 12 and have no OSM rows while traversing unreleased OSM migrations. Retain the future gated-reindex invariant, but reject the release priority and recovery migration as stated. |
| 01 ↔ current migration policy | Pack 01 assumes ambiguous pre-V10 OSM rows may need a synthetic recovered import. Local ledger shows all OSM schemas are unreleased. | Reject production recovery complexity. Revise unreleased migrations/schema 40; internal installs reset/re-import unless retention is explicitly requested. |
| 01 ↔ packaged SQLite | The proposed transactional protocol depends on WAL correctness, but the bundled 3.49.0 source is in SQLite's affected WAL-reset range. | Independent P0: ship a fixed source and verify every ABI. Telemetry is evidence only, not mitigation. |
| 01 ↔ 02 | Import-scoped duplication, retained topology, private source snapshot, and crash-safe staging increase storage/write/WAL costs. | Semantic ownership and safe-intake architecture are selected. Physical keys/topology, disk reserves, and batch sizes wait for the Pack 02 benchmark matrix; correctness is representation-independent. |
| 01 ↔ 03 | Pack 01's representative way schema uses ordinary numeric bbox extrema, while Pack 03 proves that this is ambiguous for dateline and genuinely wide paths. | Final logical bbox is start/span plus an encoding version; physical one/two-row index form waits for Pack-02-protocol query/size measurements. New OSM data can use directed crossing endpoints immediately because all OSM schemas are unreleased. |
| 02 ↔ current `osmpbf` | The selected safe contract requires pre-allocation checks and strict truncation/decompression behavior, while `BlockInputStream` allocates from `raw_size`, asserts rather than enforces completion, and treats any EOF exception as completion. | Do not use it unmodified on untrusted input. Own/fork the reader and retain the feature gate until primitive materialization and combined-resource safety also pass. |
| 02 ↔ protobuf dependency declarations | `osmpbf` declares protobuf 4.33.2; Tracker declares 4.35.1 only in app debug; the exact configuration graph was not resolved locally. | Add a strict direct constraint plus debug/release/test dependency assertions. Do not let implicit Gradle conflict resolution become the security/version contract. |
| 02 ↔ 03/04 semantic inputs | Aggressive skipping/selective parsing can save memory, but the final graph needs checked coordinates, OSM version, ordered node identity, direction, and limit provenance. | Skip only fields outside the accepted semantic contract. Design the bounded primitive decoder and final schema together so safety does not silently discard future matcher inputs. |
| 02 ↔ product availability | Full safe parsing is substantial and numeric budgets remain unmeasured, while the current feature is reachable and unreleased. | Centrally gate PBF import off in release first. Re-enable only after strict intake, combined ledger, storage/publication isolation, adversarial tests, and lowest-device benchmarks all pass. |
| 02 format limits ↔ device budgets | PBF specifies hard/recommended per-block ceilings, but independent maxima can coexist with retained graph/output allocations. | Enforce format ceilings and product policies, then apply the tighter dynamic combined reservation. Never infer heap safety from source or block size alone. |
| 03 ↔ current bounded fixes | Current speed code repairs overflow/wrap with an ordered-wide complement heuristic, and JSON clears unusable coordinates while preserving provenance. | Retain the overflow/wrap work, replace heuristic construction with producer/version-aware circular intervals, add latitude-aware speed coverage, and reset provenance to `UNKNOWN` when the pair is cleared. |
| 03 ↔ 04 | Pack 03 proposes exact WGS-84 edge semantics and a guarded ENU/ellipsoidal evaluator; Pack 04 needs directed topology and calibrated ranking before trusting matcher output. | Use WGS-84 shortest-geodesic vectors as the exact oracle now. Defer the production fallback/minimizer and ambiguity thresholds until topology exists and edge/near-threshold distributions, dependency cost, and guard correctness are measured. |
| 03 ↔ shared KMP code | GeographicLib Java can supply an Android/JVM oracle or adapter but cannot simply become a `commonMain` dependency. | Put coordinate/interval/radius algebra in `:core:model`; keep any exact-geodesic library behind a platform/test adapter. |
| 04 ↔ 05 | Matcher and altitude studies both need timed source events, decisions, lifecycle boundaries, privacy metadata, and authenticated exports. | Use one versioned research envelope and clock/loss conventions with subsystem-specific records and replay engines. Do not build incompatible trace formats. |
| 04 ↔ 06 | Pack 04 exposes row-index downsampling that can erase turns and limit changes; Pack 06 defines physical-time resampling for segmentation experiments. | Keep them separate. Do not route production compliance through a segmentation resampler or claim index sampling is cadence-neutral. Share only a later typed interval transformation whose consumer-specific invariants pass. |
| 05 ↔ 06 | Both studies need source event time, receipt time, clock domains, lifecycle discontinuities, loss accounting, transformations, consented capture, and deterministic replay. | Use one trace envelope and integrity model with altitude- and segmentation-specific records/runners. Raw pressure and raw route evidence keep distinct privacy classes and capability flags. |
| 06 ↔ durable identities | Pack 06 assumes stable identity can make duplicates idempotent; Tracker's WAL identity is stable across its retries, while provider-fix UUIDs are minted per callback arrival. | Define identity scope in the schema and transformation oracle. Do not claim cross-callback provider dedupe or add an arbitrary recent-ID cache. |
| 06 ↔ lifecycle/checkpoints | The detector can serialize and `forceEnd`, but production neither stores generic processor checkpoints nor calls `forceEnd` from segment `onStop`. | Fix only host-clock replay nondeterminism now. Product must choose clean-stop semantics and a durable checkpoint owner before finalization/restore changes. Process death never implies arrival. |
| 06 ↔ V1 compatibility | The accepted V2 contract forbids missing-as-zero and location-required observations, while V1 currently depends on both. | Preserve V1 through an explicitly named legacy projection for baseline/shipping. Do not silently call it V2-compliant; replace it only after evidence and rollout gates. |
| 05 ↔ Android platform field semantics | The current integration overwrites `android.location.Location.altitude` with MSL even though Android defines the field as ellipsoid altitude. | Preserve the platform value; transport processed altitude in a distinct typed field through the collection and persistence pipeline. |
| 05 ↔ existing altitude history/imports | Current rows and imported bare elevations lack reliable datum metadata. Treating comments as provenance would preserve misleading calculations. | Default to `UNKNOWN_LEGACY` unless a format/schema path proves datum. Make any loss of historical MSL labels visible as an intentional correctness/product decision, not a silent migration assumption. |
| 05 ↔ production storage | The report's full lineage value and aggregate superset would substantially widen hot production rows. | Persist compact datum/source/status/model/version fields; keep raw events, transform lineage, covariance, and detailed aggregate evidence in explicit-consent research traces. |

Still-open conflict points are measured parser/resource coefficients and graph/index storage cost,
the exact protobuf configuration graph, production exact-geodesic cost and guard validation,
diagnostic retention/privacy, segmentation clean-stop/gap/mixed-mode product semantics, and the
measured parameters for any live altitude or time-based segmentation reducer.

## Bounded implementation status — 2026-07-22

The evidence-independent V10 slices selected by this package have now been
implemented without selecting the evidence-gated parameters or final graph
architecture:

- release PBF availability is fail closed at UI, enqueue, and Worker boundaries;
  safe intake, resource coefficients, storage/publication isolation, and
  re-enablement remain release gates;
- checked/canonical coordinates, circular longitude, directed versioned OSM
  bboxes, bounded candidate coverage, and optional-coordinate provenance have a
  shared executable contract; legacy unreleased OSM rows are reset/re-imported,
  not heuristically reinterpreted;
- datum-sensitive altitude paths carry explicit provenance and fail closed rather
  than substituting ellipsoid altitude for MSL;
- common schema-v1 replay evidence, terminal/loss accounting, typed codec, and
  deterministic altitude/segmentation runners are test infrastructure only and
  do not authorize raw-trace capture or upload;
- V1 host-clock dependence is removed from stop-timeout selection and its
  authored transformation diagnostics are frozen, while V1 cadence, gap,
  locationless, clean-stop, and checkpoint semantics remain deliberately
  unresolved product behavior;
- compliance renders only explicit maxspeed provenance and suppresses uncertain
  matcher/limit outcomes. It is not a substitute for directed topology or a
  real imported-graph validation suite.

The historical debug export is still an incomplete schema-v3 manifest and does
not claim schema-v1 replay completeness. Focused JVM verification is recorded in
the integration report; Android-specific checks must be rerun in an environment
with a valid Linux Android SDK/Build Tools installation before treating them as
green.
