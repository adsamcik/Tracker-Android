# Dev/v10 adversarial research integration report

> **Last verified:** 2026-07-22
> **Research packs:** 10, each pinned to `94c964d03`
> **Final `dev/v10` tip:** `fb2684d53`
> **Final verification:** `BUILD SUCCESSFUL` — full-repository test suite passed across all 28 modules, 1004 tasks

## Executive summary

Ten independent adversarial research packs covering domain-event replay safety,
UI/navigation/lifecycle behavior, geospatial search, the map pipeline, activity
detectors, tracking policy, import/export, the OSM road graph, geospatial route
processing, and altitude sensor fusion were evaluated against the actual codebase.
Every claim was checked against current code before work was scoped. Confirmed
defects were selectively implemented and independently cross-reviewed before being
merged and pushed to `dev/v10`; stale, overstated, unreachable, and speculative
claims were not treated as defects merely because they appeared in an external
report.

The resulting changes span the ten review packs and the related database
migration/retention work described below. The final branch tip is `fb2684d53`.
As final confirmation, the full-repository test suite passed across all 28 modules:
`BUILD SUCCESSFUL`, 1004 tasks.

## Post-integration correction — 2026-07-22

This erratum supersedes the overly broad publication claim in the historical OSM
section below; it does not change the scope of the work that landed at
`fb2684d53`.

`BUILDING`/`READY` gating prevents consumers from seeing a `BUILDING` import only
when no other `READY` import keeps the global ready-import count nonzero. In the
reachable multi-region case, region A can be `READY` while region B is
`BUILDING`. The global gate remains open and candidate queries do not filter
ways or cells by the owning import's status, so road matching and speed lookup
can observe B's partially written graph. Global OSM-way-ID replacement can also
transfer a shared road from A to B; cancelling, failing, or deleting B can then
remove road data A still needs.

Neither risk is corrected by the historical integration. If V10 ships multiple
retained regions, a safe multi-region ownership and publication model is a
release-gate design item. The intended follow-up is [the research-prompt
overview](dev-v10-research-prompts/README.md), especially [research pack
01](dev-v10-research-prompts/01_OSM_MULTI_REGION_STORAGE_AND_PUBLICATION.md).

**Verified bounded post-integration corrections:** After their focused
regression classes passed on 2026-07-22, the sibling OSM speed-limit lookup was
partially corrected for antimeridian-safe candidate selection, bbox filtering,
and local longitude geometry. JSON Wi-Fi and cell coordinate pairs were also
aligned with location validation: only complete, finite, in-range pairs are
converted to E7, while invalid optional coordinates preserve their observation.
Pack 03 subsequently proves that these are partial fixes: speed coverage is still
not latitude-aware, parser bbox persistence still disagrees with its cell interval,
grid/polar enumeration remains incompletely bounded, and cleared coordinates still
retain incompatible provenance. These corrections also do not address the
multi-region publication or ownership risks above.

### Post-research planning decision — 2026-07-22

Research pack 01 and its local audit select **per-import immutable generations**
for V10: logical `UNIQUE(import_id, osm_way_id)` way identity, import-scoped cell
identity, transactional way-plus-cell batches, unreachable `BUILDING`
generations, version-aware deterministic READY precedence, `ABORT` rather than
`REPLACE`, revision-keyed cache invalidation, stale-worker authority tokens, and a globally
gated cell-index rebuild. The representative schema is not implementation-ready:
it must also retain the OSM node, direction, and limit-provenance semantics
identified by matcher research, with its physical representation constrained by
the PBF resource policy and still-unrun device/storage benchmarks. See the cumulative
[research synthesis status](dev-v10-research-prompts/RESEARCH_SYNTHESIS_STATUS.md).

A second independent answer to pack 01 refines, but does not replace, that
decision. A surrogate `way_instance_id` is the provisional physical key because
it can keep the larger cell table narrower, while the import/OSM-ID pair remains
the enforced logical identity. Pack 02 did not perform those measurements; its
benchmark protocol must confirm or reject that trade-off before the physical key
is frozen.
For overlapping copies of one OSM way ID, prefer the highest available OSM
element version and fall back to publication revision plus stable import identity
when PBF metadata is absent or tied. Winner selection must occur before final
spatial filtering so an older geometry cannot leak into a cell vacated by the
newer winner.

The proposed synthetic recovery migration is not a release requirement. The
migration ledger identifies schema 12 as the last released version; OSM tables
first appear in unreleased migration 28→29 and schema 40 is also unreleased.
Revise the unreleased OSM migrations/schema directly and require development
installs to reset/re-import OSM data unless preserving internal-build data is
separately requested.

The local audit also establishes an independent P0 release gate. Tracker enables
WAL while pinning requery sqlite-android 3.49.0, and all ABIs in the resolved AAR
contain the February 2025 SQLite 3.49.0 source. SQLite's official
[WAL-reset advisory](https://sqlite.org/wal.html#the_wal_reset_bug) places that
version in the affected range and recommends upgrading to a fixed release.
Runtime source-ID telemetry is useful evidence, but is not a mitigation. A fixed
engine must be selected or built and its source ID verified for every packaged
ABI before release.

Research pack 02 and its local audit also turn the offline `.osm.pbf` importer
into a separate P0 release gate. The current path is streaming only in the sense
that it does not retain the whole source file. It is not bounded against hostile
input: pass 1 retains an unbounded number of ways and reference arrays, so
arbitrarily many drivable ways that repeat the same two node IDs bypass the
distinct-node cap; a 1,000-way parser batch can feed a 5,000-way Worker buffer
whose per-way cell arrays may each contain 20,000 `Long`s; and generated
`PrimitiveBlock` object graphs are built before Tracker's callbacks can apply an
application resource policy.

The exact cached `osmpbf:1.6.1` bytecode confirms the library-level attack path.
Its zlib branch allocates `byte[raw_size]` from an input-controlled field, copies
the compressed `ByteString` to another array, calls `Inflater.inflate()` once,
and checks completion only with a normally disabled Java assertion. Its block
loop catches any `EOFException` as normal completion, so clean EOF is not
distinguished from a truncated prefix/header/body. Tracker also trusts nullable
provider size metadata as a required pre-open bound, never connects its mutable
cancellation flag to coroutine cancellation, catches `Throwable` (including
OOM), exposes raw URI/exception text through errors, and writes ways and cells in
separate DAO transactions. Startup does delete old `BUILDING` database imports;
that is real protection, but it cannot reconcile source snapshots or job
directories that do not yet exist and is not an allocation-safety mechanism.

The accepted architecture keeps the simple two-pass algorithm only behind a
safe intake: copy once to a private job-scoped snapshot while counting the actual
bytes and rejecting byte 100 MiB + 1; use the snapshot for both passes; replace
the unmodified block reader with strict positive framing and exact raw/zlib
decoding; require a valid OSM header and supported required features; and count
entities, strings, tags, ways, total references, distinct nodes, geometry, cells,
rows, disk, and work in one checked coexistence-aware ledger before allocation or
write. The format ceilings are `<64 KiB` for a BlobHeader and `<32 MiB` decoded
for a Blob; `<16 MiB` is the format's compatibility recommendation, not a proof
of device affordability. The 100 MiB source limit, 2,000 references per way, and
20,000 cells per way are explicit Tracker product policies, not substitutes for
the combined budget.

Do not re-enable untrusted import merely after fixing zlib framing. The primitive
stage must either stream/skip unneeded protobuf fields or use a non-allocating
wire preflight whose calibrated charge is proven never to underpredict the
generated parse on held-out adversarial blocks. A bespoke streaming decoder is a
security-sensitive implementation choice requiring its own review and fuzzing,
not a shortcut to be improvised inside the broader V10 work. Until the complete
path passes allocation-before-check, malformed/truncated input, cancellation,
low-disk, process-kill/reboot, and lowest-device benchmarks, the importer should
be centrally gated off in release builds and reject direct Worker invocation
before opening the URI or creating database state.

Pack 02 deliberately does not settle dynamic numbers. Object-layout coefficients,
heap safety reserve, total way/reference/string/entity limits, output batch
budgets, cancellation intervals, CPU/work deadlines, temporary/SQLite/WAL disk
budgets, and surrogate-versus-composite graph storage still require measurements
on the lowest supported heap/API/ABI class plus held-out realistic and adversarial
extracts. The cached POM declares protobuf 4.33.2 while Tracker declares 4.35.1
only for app debug; the exact resolved debug/release/test graphs remain a build
verification item because the local Gradle diagnostic could not configure
without the project's unavailable Java 17 toolchain.

Research pack 05 and its local audit also promote one altitude item out of the
measurement-only backlog. Android defines `Location.altitude` as WGS84 ellipsoid
altitude, while MSL is a distinct property. Tracker currently returns the raw
ellipsoid value when AndroidX MSL conversion fails or produces no MSL result,
then names, calibrates, fuses, persists, exports, and aggregates that value as
MSL. The downstream path is confirmed through `PersistenceProcessor`, GPX/KML/JSON
exporters, and trip altitude/gain/loss calculations. This is a P1 semantic
correctness defect and does not require field tuning to fix.

The accepted immediate design is to fail closed for MSL, retain raw ellipsoid
evidence separately, stop overwriting the platform altitude field with processed
MSL, and carry compact datum/source/conversion metadata through collection,
durable signal recovery, Room, domain models, and exports. Historical and imported
bare altitudes remain `UNKNOWN_LEGACY` unless provenance is demonstrable; they
must not calibrate MSL or create a gain/loss delta across a datum boundary.

All numerical policy remains evidence-gated: pressure aggregation, Q/R, missing
accuracy handling, innovation/outlier rules, calibration consensus and rollback,
gap behavior, and process-death restoration. Research trace v2 cannot adjudicate
those decisions because it lacks raw pressure event time, aggregate windows,
conversion/calibration outcomes, innovations, covariance, lifecycle boundaries,
and loss accounting. Evolve it into a shared versioned evidence envelope and
build deterministic replay before tuning. Keep process-death fusion persistence
disabled until a preregistered device study demonstrates net benefit. See the
cumulative [research synthesis status](dev-v10-research-prompts/RESEARCH_SYNTHESIS_STATUS.md)
for the minimum evidence contract, privacy qualifications, and staged pilot.

Research pack 03 and its local audit select one shared geospatial contract for
V10. External/import boundaries reject incomplete, non-finite, and out-of-range
coordinates; cyclic algorithms normalize separately. Canonical spatial longitude
is `[-180°,180°)`, so accepted `+180°` becomes `-180°`, and exact-pole canonical
identity uses longitude zero. Longitude arithmetic widens before subtraction.
Circular longitude intervals are first-class `start + eastward span` values with
explicit Point and Full states; producer/schema adapters, not a generic
`ordered width > 180°` heuristic, determine legacy bbox meaning.

The immediate worthwhile slice is shared pure coordinate/interval/radius algebra,
parser-consumer bbox agreement, latitude-aware bounded speed candidate coverage,
canonical grid/cache identity, checked PBF/polyline narrowing, and an atomic
Wi-Fi/cell pair whose provenance becomes `UNKNOWN` when coordinates are cleared.
The final Pack-01 OSM schema should retain interval start/span plus an encoding
version. Pack 02 supplies the safety contract and benchmark protocol; the physical
index/topology representation remains unselected until those measurements run.

The report's WGS84 ENU plus GeographicLib fallback is not yet a production
decision. Shortest WGS84 geodesics are accepted as the exact test/oracle semantics,
but the proposed analytical guard, finite segment minimizer, Java/KMP boundary,
binary/runtime cost, polar query behavior, and real edge/near-threshold
distributions require independent fuzzing and device measurements. Do not add a
production GeographicLib dependency or `rtree_i32` merely from this static report.

## Methodology

Each research pack followed the same integration process:

1. **Verify against current code.** Claims were independently checked before being
   trusted. Several referred to behavior that was already fixed, overstated the
   severity or magnitude, or concerned dead or unwired code with no production
   impact. Those claims were explicitly filtered out rather than prompting
   speculative changes.
2. **Bound the scope.** Work was limited to concrete, reproducible defects with a
   defensible production impact.
3. **Implement and test.** Confirmed defects were corrected with focused changes
   and relevant tests.
4. **Cross-review before integration.** A second model from a different provider
   family independently reviewed each implementation before it was merged and
   pushed.

Two packs encountered competing Room schema migrations that required careful
renumbering. This reinforced a general principle for Tracker: schema migrations
are among the highest-risk and hardest-to-reverse changes in the repository. They
received additional verification rather than being resolved mechanically.

## What was landed

### Domain-event replay safety

**Scope:** `:core:base`, `:stats:*`, and `:feature:game`

The domain-event cursor was not guaranteed to advance monotonically. Consumers
also acknowledged whole batches, so a failure partway through a batch could skip
unprocessed events or cause already-applied work to run again. The exploration-cell
consumer committed its mutations and cursor acknowledgment in separate
transactions, and achievement evaluation could clear its dirty-state signal before
durably completing evaluation.

The cursor now uses ID ordering and a monotonic upsert based on `MAX()`. Game and
precision-upgrade consumers acknowledge events individually. Exploration-cell
mutations and their acknowledgment now share one database transaction.
Achievement evaluation uses a durable two-phase snapshot/acknowledge pattern.

These changes make recovery resume from the last successfully applied event,
prevent crash-window double counting, and prevent a worker crash from silently
freezing future achievement progress. Achievement unlocks remain permanent
entitlements; no revocation behavior was introduced.

### UI, navigation, and lifecycle

**Scope:** `:app`, `:feature:tracker`, `:feature:dashboard`, `:feature:map`, and
`:feature:statistics`

Tracker and Dashboard could leave their permission-request UI state open after the
system dialog resolved, on both grant and deny paths. Permission state could also
be stale after returning to the app. Other confirmed issues included a
snackbar-clear race, History undo-delete work tied to the screen composition,
non-persistent navigation/draft state, map effects running while the screen was
not visible, and navigation decisions based on hardcoded or replay-vulnerable
state.

The permission flows now close correctly and re-check permissions on resume. The
snackbar race was removed. History undo-delete timing moved to a ViewModel-owned
deadline that survives navigation away from the screen. `SavedStateHandle`
persistence was added for the onboarding draft and Settings subsection
navigation. Five map sensor/camera effects are now gated by actual screen
visibility. Tracking Settings refreshes permission state and preserves the user's
requested source toggle when applying a preset despite a denied permission.
Deep-link navigation and Trip Detail back navigation now track their real origin
and apply idempotently.

The practical result is more durable user intent across lifecycle changes, correct
permission UX, reliable undo timing, lower off-screen battery use, and navigation
that returns to the actual originating context without replaying stale commands.

### Geospatial search correctness

**Scope:** `:domain:geocoder`

The nearest-place lookup used a partial scan that could miss the true nearest
result. OSM street snapping used fixed-degree longitude padding, which becomes
increasingly inaccurate with latitude and fails around the antimeridian.

Nearest-place lookup now performs a correct full scan, supported by a regenerated
on-disk places asset format that preserves acceptable lookup performance. Street
snapping now computes latitude-aware longitude padding and handles antimeridian
wraparound.

Nearest-place results are now correct rather than dependent on an invalid search
shortcut, while street candidates are selected from a geographically valid search
area at high latitudes and near ±180° longitude.

### Map pipeline correctness and performance

**Scope:** `:feature:map`

A failed layer-data reload could leave a stale cached result from a different,
superseded request permanently displayed. Merely assigning request identifiers was
insufficient because results were not validated at the point of application.
Several layer queries also materialized unbounded result sets, the viewport cache
was unbounded, and one heatmap aggregation fetched rows for Kotlin-side grouping.

Layer reloads now use a monotonic per-request generation token that is checked when
results are applied, correctly discarding late responses under genuinely
overlapping asynchronous requests. Location-path and vehicle-compliance row
materialization is bounded, with endpoint-preserving even downsampling. The
viewport-configuration cache is capped by both byte budget and entry count.
Heatmap aggregation was moved into SQL.

The map no longer preserves data from a superseded request after a failed refresh,
and its memory and query costs are bounded more predictably on large datasets.

### Activity detectors: ski, plane, and sailing

**Scope:** `:stats:engine` and `:tracker:engine`

All three detectors used wall-clock timestamps for duration and dwell arithmetic.
NTP corrections, manual clock changes, timezone changes, or daylight-saving
transitions could therefore corrupt detector timing.

All detector duration arithmetic now uses monotonic elapsed time.

For **ski detection**, streaming and batch classification were unified. Listener
failures are isolated, `finish()` is idempotent, and persisted vertical drop and
maximum speed now come from the detector's tracked extrema rather than potentially
stale fields.

For **plane detection**, a confirmed flight now requires qualified, sustained
barometric climb evidence rather than any climb-and-cruise pattern. A pressure
freshness timeout moves the detector to an explicit unknown state during sustained
barometer gaps instead of treating the gap as a landing or continuing to accrue
airborne credit.

For **sailing detection**, activity recognition contributes a new confidence tier.
Confident vehicle or bicycle recognition is a hard exclusion, because speed alone
cannot reliably distinguish sailing from cycling or driving without water-context
sensing. Sample gaps now close an interval instead of being counted as continued
sailing.

Together, these changes make detector timing stable, reduce false positives, and
prevent missing sensor data from being interpreted as positive evidence.

### Tracking-policy backoff reachability

**Scope:** `:stats:engine`

The false-escalation backoff counted every ordinary escalation into high-accuracy
GPS tiers as false. Its maximum multiplier could raise the effective activation
threshold above the theoretical maximum confidence value. As a result, as few as
three ordinary activity cycles within an hour—for example, three short walks—could
make high-accuracy GPS permanently unreachable until process restart.

The multiplier now has a mathematically reachable ceiling. A cycle counts as false
only when the high-accuracy tier was held for a suspiciously short duration,
indicating genuine flapping. The multiplier also returns to baseline when its
rolling window expires without new offending activity.

High-accuracy tracking can no longer lock itself out through normal use, while the
backoff still dampens repeated short-lived escalation.

### Import/export correctness and security

**Scope:** `:feature:import-export` and `:core:base`

The JSON exporter discarded the counting/watermark sequence supplied to it and
queried independently, leaving its incremental watermark at zero and repeatedly
exporting the same range. Timestamp-only progress could skip rows sharing a
millisecond. Session-scoped export omitted sessions crossing a requested date
boundary and had no representation for records outside a session.

JSON export now consumes the intended sequence and advances a composite monotonic
`(timestamp, id)` cursor. Boundary-straddling sessions are included, and records
outside any session are exported in an orphaned-data bucket.

Archive imports are now resumable and content-addressed using SHA-256 job and
entry receipts. Retrying a partially failed archive skips entries already
completed. ZIP extraction streams one entry at a time rather than materializing
all temporary files up front, and enforces entry-count, compression-ratio,
total-compressed-input, and existing size controls to resist storage exhaustion
and zip bombs.

KML import no longer double-buffers each placemark. JSON Wi-Fi and cell observations
are streamed in batches instead of accumulated without a bound. Location
coordinates must be finite and within valid ranges, preventing absent or `NaN`
values from becoming a false `(0,0)` location. All format importers now round,
rather than truncate, decimal coordinates when converting to E7.

The raw SQLite import path no longer mutates the untrusted source database. ID
remapping is computed at read time, preventing attacker-defined triggers from
executing through an import-side `UPDATE`. Imports also reject files containing
triggers, views, or virtual tables. Raw-database backup export now holds a write
lock throughout the copy instead of only around the pre-copy checkpoint.

These changes restore correct incremental progress and date scoping, make retries
safe and efficient, bound archive resource use, reject malformed coordinates, and
reduce the attack surface of untrusted database imports and concurrent backups.

### OSM road graph and map matcher

**Scope:** `:domain:osm` and `:feature:map`

Antimeridian-adjacent longitude-distance arithmetic could overflow an integer,
producing invalid distances or unbounded bounding-grid allocations. Ways with
missing referenced nodes were partially accepted, fabricating straight-line
chords across missing geometry. Readers could also observe a graph while its
import was incomplete.

Longitude-distance handling now avoids the overflow class near ±180°. A way is
rejected if any referenced node is missing. Imports now expose an explicit
`BUILDING`/`READY` publication status, which prevents the road matcher and
speed-limit lookup from reading a partially imported graph only when no other
`READY` import keeps their global gate open; the reachable multi-region exception
is recorded in the [2026-07-22 erratum](#post-integration-correction--2026-07-22).
Startup cleanup removes orphaned in-progress imports after process death, with a
timestamp guard to avoid racing a legitimate restart.

The Viterbi decoder now returns an explicit no-path result when all candidate
probabilities are degenerate instead of choosing an arbitrary default path. This
is a defensive latent correction rather than a failure observed in production.
Vehicle speed compliance now uses each GPS fix's actual accuracy instead of a
constant.

The road graph is safer at the antimeridian, no longer invents geometry across
missing nodes, and has a `BUILDING`/`READY` publication state. That state alone
does not provide atomic multi-region publication or shared-road ownership
isolation; see the [2026-07-22 erratum](#post-integration-correction--2026-07-22).

### Geospatial route processing

**Scope:** `:stats:engine`

Verification found that the reviewed Douglas-Peucker simplification, GPS cleaning,
route compression, and polyline encoding pipeline is almost entirely dead or
unwired and has no current production impact. It was therefore not broadly
modified.

Two live defects were confirmed and fixed. Real-time trip-distance calculation
contained the same antimeridian integer-overflow class found in the OSM module.
Trip startup also counted the confirming departure signal's distance and steps
twice.

Real-time session segmentation now computes sane distances near the antimeridian,
and each trip starts without the previous one-cycle distance and step overcount.

### Cadence-independent segmentation follow-up (Pack 06, 2026-07-22)

Post-integration research and local verification established that the live segment
detector remains cadence-sensitive despite those two bounded fixes. The adapter
drops locationless cycles and most source timing/identity/uncertainty metadata,
maps absent steps to zero, begins stop candidacy after three still arrivals, and
sample-weights speed/activity. A later still fix can complete dwell across a long
unobserved interval. Partial-mode selection also consults the host's current wall
clock, so replaying the same historical signals at another date can change the
chosen stop timeout.

Two report boundaries were corrected locally. `SegmentDetectorProcessor.onStop()`
resets without invoking the detector's existing `forceEnd()` method, so it does
not force-complete an open trip. Detector serialization exists, but the production
pipeline neither stores generic processor checkpoints nor provides one at startup;
process-death continuity is not established. The provider-fix `sourceEventId` is
also generated per callback arrival, so it cannot by itself prove that repeated
provider callbacks are duplicates, although the outer WAL identity is stable for
Tracker retry/coalescing.

The accepted immediate work is deliberately narrower than a live redesign:
remove host execution time from V1 decisions; add a lossless versioned segmentation
observation to the common research envelope; create deterministic offline V1 replay
and baseline snapshots; and exercise authored cadence, batching, cross-batch order,
duplicate, gap, accuracy, missing-sensor, step-reset, wall-clock, and clock-domain
transformations. V1 remains the user-facing baseline. Support horizons, live
reordering, time-weighted evidence, gap/finalization policy, mode thresholds,
checkpoint ownership, shadow capture, and any HSMM remain Tracker-trace and product
decisions. Unsupported time must ultimately remain unresolved rather than being
invented as movement or stillness.

### Altitude sensor fusion

**Scope:** `:tracker:engine` and `:feature:statistics`

Displayed elevation gain and loss depended on sampling cadence. The accumulator
advanced its altitude baseline on every sample, even when the individual change
was below the one-meter noise threshold. A real gradual climb sampled frequently
could therefore report almost no gain.

The baseline now advances only when accumulated change crosses the threshold,
preserving real gradual elevation changes while retaining noise filtering.
Monotonic elapsed time is now threaded through the GPS/barometer Kalman fusion
pipeline instead of wall-clock time.

The fusion path also contained correlated measurement reuse. Barometer calibration
derived sea-level pressure from the current GPS fix, then immediately fed the same
pressure back as though it were an independent measurement. The redundant
barometer update is now skipped on the calibration cycle.

Elevation totals are no longer biased by sensor cadence, filter timing is robust
to wall-clock changes, and uncertainty no longer collapses artificially around a
single GPS-derived calibration event.

### Database migration and retention correctness

**Scope:** `:core:base` and `:app`

Trip sessions migrated from an old pre-2024 schema retained their historical
activity ID, but statistics queries ignored it. Those sessions were silently
excluded from walking, cycling, driving, and other activity-type totals.

Queries now apply a verified historical ID mapping as a read-side SQL fallback.
No stored data was rewritten and no schema change was required, keeping the
correction reversible.

Retention cleanup also treated an active session's start time as though it were an
end time, allowing a sufficiently long-running active session to be deleted.
Active sessions are now excluded from that expiry condition. Time-based cleanup
was also added for ski-run segments, which previously survived indefinitely unless
the user performed a full data wipe.

Historical statistics now include migrated sessions correctly, active tracking
cannot be purged because of its age, and ski-run data follows the retention policy.

## What was explicitly deferred, and why

### Domain-event replay safety

- **Timestamp-based event retention:** Retention still keys on event timestamp
  rather than the ID-based cursor. This is pre-existing and effectively
  unreachable with the real-time timestamps used when events are created.
- **Minor non-monotonic field write:** The field can temporarily move backward but
  self-heals during the next evaluation cycle; its severity did not justify
  expanding this pass.

### Map pipeline

- **Generation identity in one layer `enable()` path:** This path was not integrated
  with the new request-generation scheme because the layer controller's current
  call pattern makes the identified failure mode unreachable.
- **Rare stuck loading indicator:** A superseded/failed refresh interleaving can
  leave cosmetic loading state behind. It does not affect applied map data.

### OSM road graph and map matcher

- **Atomic cell-index rebuild:** Reindexing remains additive rather than using a
  shadow rebuild and atomic swap. Readers can observe a partial reindex; correcting
  this requires a broader persistence design.
- **Multi-region publication and shared-road ownership (release gate if multiple
  retained regions ship):** A `READY` region A leaves the global ready-count gate
  open while region B is `BUILDING`, and candidate queries do not filter by import
  status, so consumers can observe B's partial graph. Global-ID replacement can
  also transfer a shared road from A to B; cancellation, failure, or deletion of
  B can then remove data A still needs. This remains unimplemented and requires a
  deliberately designed ownership/publication model with Room migration review.
- **Total PBF resource safety (design resolved, implementation pending):** Distinct
  node IDs are bounded, but total ways/references, decoded object graphs, output
  amplification, disk, and work are not. Pack 02 selects fail-closed containment,
  strict intake, and one combined resource ledger. Release coefficients and final
  accept/reject envelopes still require adversarial and real-device measurement.
- **One-way road direction and OSM turn restrictions:** The matcher does not score
  one-way direction and does not ingest turn-restriction relations. Both are real
  accuracy gaps requiring matcher-algorithm changes and representative route data.
- **Vehicle-compliance end-to-end coverage:** The current test fakes the road
  matcher, so it does not provide true road-import-to-compliance coverage.
- **Sibling speed-limit antimeridian overflow (completed post-integration):** This
  bounded follow-up was outside the historical integration scope, then corrected
  and regression-tested on 2026-07-22. It does not change the deferred ownership,
  publication, reindexing, or matcher-design work.
- **Ready-region cache staleness:** Importing a second region while a lookup uses an
  earlier ready region can briefly leave stale cache state. The edge case is
  low-severity and self-healing.

### Geospatial route processing

- **Latent simplification and encoding issues:** Antimeridian distance handling,
  reversal/near-closure floating-point degeneracy, recursive simplification stack
  risk, and polyline truncation remain in the unwired pipeline. With zero current
  production callers, changing dead code was judged higher risk than leaving it
  documented.
- **Cadence-sensitive stop and transport classification:** Existing duration-based
  confirmation partially mitigates the issue, but a complete correction requires
  a time-weighted aggregation redesign rather than a local patch.

### Altitude sensor fusion

- **Pressure sample aggregation:** The producer averages all pressure samples in a
  collection cycle, discarding individual timestamps and variance. This can smooth
  or lag fast changes and needs real-device measurement before redesign.
- **Altitude datum fallback visibility:** Failed GPS-to-mean-sea-level conversion
  silently falls back to raw ellipsoidal altitude, with no flag distinguishing the
  datums. The resulting mismatch can be several tens of meters and requires an
  explicit product/data-contract decision. **Post-research status:** pack 05 and
  local reachability inspection resolve the safety decision: fail closed for MSL,
  retain ellipsoid separately, and add explicit datum/conversion metadata. Only
  the UI treatment and empirical conversion-failure frequency remain measurements.
- **Innovation and outlier rejection:** Neither GPS nor barometer inputs are
  rejected as outliers. One bad GPS fix can affect the barometer calibration
  baseline for up to five minutes; robust thresholds need recorded sensor data and
  real-device tuning.
- **Fusion-state persistence:** Filter and calibration state is not persisted, so
  process restart or session re-enable requires reacquisition. Persistence policy
  and stale-state handling need a separate design.

### Import/export

- **Duplicated migration-test fixture names:** One test lists two table names twice.
  A `Set` comparison deduplicates them, so this is harmless cosmetic cleanup.
- **Wi-Fi/cell coordinate range validation (completed post-integration):** This
  bounded follow-up was aligned with location's optional-pair contract and
  regression-tested on 2026-07-22. It preserves the observation when optional
  coordinates are unusable. **Pack-03 correction:** both coordinate fields must
  remain null and coordinate provenance must become `UNKNOWN`; preserving
  `DIRECT`/interpolated provenance without a coordinate contradicts the model.

### Database migration and retention

The historical targeted pass did not defer a defect inside its stated two-fix
scope. Pack 03 later demonstrated that the scope itself was narrower than the
shared coordinate contract: producer/consumer bbox agreement, latitude-aware speed
coverage, canonical grid/cache identity, bounded neighbor enumeration, checked
codec narrowing, and null-coordinate provenance remain concrete follow-ups.
Broader recommendations—atomic generation-based rebuilds, a cross-subsystem
maintenance lease, exact-geodesic runtime selection, R-tree adoption, and other
physical index/performance trade-offs—remain architectural or measurement-gated.

## Recommended next steps

1. **Replace the affected packaged SQLite engine before release.** Select or build
   a source containing the WAL-reset fix, verify runtime version/source ID for
   every ABI, and execute the migration, concurrent-write/checkpoint, backup, and
   recovery matrix. Telemetry alone does not close this P0 gate.
2. **Finish the shared coordinate/antimeridian contract.** Retain the current
   widening/wrap and atomic-pair fixes, then add canonical checked coordinate
   construction, circular intervals, parser-consumer bbox agreement,
   latitude-aware speed bbox/cell coverage, bounded typed grid enumeration,
   canonical grid/cache keys, checked PBF/polyline narrowing, and `UNKNOWN`
   provenance for cleared optional coordinates. Use Pack-03 WGS84 vectors as
   exact oracles; do not add the proposed production geodesic fallback yet.
3. **Implement the selected multi-region ownership/publication architecture only
   with the bounded importer contract.** Use per-import immutable generations,
   import-scoped logical ownership with a measured surrogate-versus-composite physical key,
   atomic BUILDING→READY publication, version-aware deterministic overlap
   precedence, revision-keyed cache invalidation, stale-worker authority checks,
   and a gated reindex. Revise unreleased schema 40 directly; do not add a
   production recovery migration solely for unreleased OSM data. Finalize the
   physical topology/index columns using Pack 02's benchmark protocol and the
   already-accepted Pack 03/04 semantic constraints before writing the schema.
   The logical longitude bbox is already selected as versioned start/span.
4. **Establish genuine OSM end-to-end tests before extending the matcher.** Replace
   the fake matcher in vehicle-compliance coverage with a representative imported
   graph. One-way scoring and turn-restriction support then need measurement, not
   just code: gather representative routes and quantify accuracy and performance
   before choosing algorithms or data structures.
5. **Correct altitude datum handling and build the evidence foundation before
   changing fusion policy.** Fail closed on MSL conversion, retain the provider's
   ellipsoid altitude separately, stop overloading Android's altitude field, add
   compact datum/source/conversion metadata, and prevent unknown or incompatible
   values from calibrating MSL or crossing gain/loss segments. Then add the
   versioned, encrypted, loss-accounted research contract and deterministic replay,
   pilot it on a small device/scenario set, and only then select aggregation,
   outlier, calibration, noise, gap, or persistence policy.
6. **Land segmentation determinism/replay before redesigning classification.**
   Remove host-clock dependence from V1 decisions, preserve canonical timed and
   nullable evidence, freeze deterministic V1 replay, and quantify its sensitivity
   under cadence/batching/gap transformations. Keep V1 user-facing while labeled
   Tracker traces select support horizons, reorder bounds, evidence rates, gap and
   clean-stop semantics, mode taxonomy/thresholds, and whether an explicit-duration
   rules reducer is sufficient. Do not deploy an HSMM/neural model, checkpoint
   restore, or raw-route shadow capture without their evidence, lifecycle, privacy,
   and device-cost gates. Avoid the unwired simplification/encoding pipeline unless
   it first gains a production consumer, executable contract, and owner.
7. **Fail closed on untrusted PBF now, then earn re-enablement.** Centrally gate
   the current importer so neither UI/controller nor direct Worker execution can
   reach the unsafe parser in a release build. Behind that gate, add an
   actual-byte-counted private snapshot, strict framing and exact decompression,
   header/feature/structure validation, safe protobuf preflight or streaming,
   checked combined memory/disk/work accounting, multidimensional output batches,
   typed non-retryable failures, redacted diagnostics, and independent crash
   reconciliation. Use valid authored/adversarial PBF fixtures and prove every
   rejection precedes its disallowed allocation/write. Run Pack 02's lowest-device
   benchmark matrix before selecting dynamic budgets or the physical node/edge/key
   encoding. Shadow index generations remain deferred unless measurements justify
   their complexity.
8. **Schedule cosmetic/self-healing items opportunistically.** The map loading
   indicator, cache-staleness edge case, duplicated fixture names, and
   self-healing domain-event write do not warrant displacing higher-risk work.

This report is the authoritative record for the `dev/v10` integration at
`fb2684d53`. It should be updated or superseded when a future pass implements or
re-scopes any deferred item.

## Bounded V10 implementation follow-up — 2026-07-22

This follow-up is intentionally narrower than the historical report's deferred
architecture work. It was implemented on the current `dev/v10` worktree, which
already contained the committed Tracebox trial merge after `fb2684d53`; that
unrelated branch divergence was retained rather than rewritten.

- **Untrusted offline PBF containment:** release wiring now centrally declares
  offline `.osm.pbf` import unavailable. Settings, controller, and direct Worker
  invocation independently fail closed with the redacted stable
  `PBF_IMPORT_UNAVAILABLE` code before URI access, foreground parse work,
  private-job creation, parser invocation, or OSM database insertion. The legacy
  parser remains only for explicitly test-wired characterization. This is not
  safe-intake completion and does not re-enable import.
- **Checked geographic contract:** shared KMP checked coordinate, cyclic
  longitude, circular-interval, and conservative metre-radius primitives now
  back parser, grid, bbox, matcher, speed, street, polyline, and optional-import
  coordinate paths. New OSM way bboxes are directed and versioned. Legacy
  development rows are invalidated/deleted rather than being guessed from old
  numeric extrema; import-header extrema are explicitly diagnostic/nonspatial.
  This does not implement the deferred multi-region graph/publication redesign.
- **Altitude datum contract:** altitude conversion is typed and fail closed for
  MSL. Ellipsoid input, datum/source/status/model provenance, persistence,
  signal recovery, import/export, UI, and gain/loss consumers now preserve that
  distinction rather than silently treating a failed conversion as MSL.
- **Research evidence and V1 characterization:** schema-v1 common typed
  evidence, loss/terminal accounting, deterministic codec, numerical diagnostics,
  offline altitude replay, canonical segmentation evidence, envelope-preserving
  V1 replay, and declared transformations are present. They are replay/test
  infrastructure only: no plaintext on-device evidence store, upload, normal
  analytics event, or collection UI was added. V1 no longer reads host wall time
  for its stop decision, but it is explicitly not claimed cadence independent.
  `SegmentDetectorProcessor.onStop()` still resets without `forceEnd()`, and the
  production pipeline still does not store/restore generic processor checkpoints.
- **Compliance honesty:** the production compliance mapping is extracted and
  exercised through the same provider as the registry. Only explicit OSM
  maxspeed provenance can produce a normal bucket; heuristic/unknown/unsupported
  limits and no-match, gap, ambiguous, no-path, invalid, or non-finite results
  are withheld with typed suppression diagnostics. Directed topology, turn
  restrictions, and real imported-graph end-to-end coverage remain deferred.

Focused JVM verification passed for `:stats:api:jvmTest` and
`:stats:engine:jvmTest`; the standalone research-trace decoder conformance test
also passed. Android/OSM unit tasks remain environment-blocked in this Linux
runner: its configured Windows SDK path does not resolve directly, and the
accessible mounted SDK's Build Tools 37.0.0 lacks Linux `aapt` and is reported
corrupted by Gradle. That block is not represented as a passing Android
verification.
