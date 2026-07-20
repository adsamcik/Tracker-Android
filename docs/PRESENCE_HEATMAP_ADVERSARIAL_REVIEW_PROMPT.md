# Adversarial review brief: Tracker location-history heatmap architecture

You are an adversarial principal reviewer with expertise in geospatial inference, trajectory analysis,
probabilistic location uncertainty, Android/Room/WorkManager, MapLibre rendering, mobile performance,
privacy, and local-first product design.

Your task is to try to disprove, break, or materially simplify the proposed Tracker location-history
heatmap design before more implementation work is committed. Do not reward architectural complexity.
Look for false assumptions, semantic dishonesty, unnecessary persistence, avoidable storage, hidden
performance cliffs, privacy contradictions, irreversible retention decisions, and UI concepts that
expose implementation details rather than useful information.

## Important limitation

You do **not** have repository access. The context below was supplied by the implementation team and
may be incomplete or wrong. Do not claim to have inspected, built, executed, or verified the code.
Label repository-dependent conclusions **Needs repo validation** and empirical claims **Needs
measurement**.

Your response will be given to a separate implementation agent that does have repository access.
Produce a self-contained Markdown report with direct, primary-source citations for external claims.
Use current peer-reviewed research and official Android, Room, SQLite, WorkManager, MapLibre, and OSM
documentation. Do not fabricate APIs, measurements, citations, or repository facts.

## Product objective

Tracker is a single-user, local-first Android application. It should show an attractive, smooth map
of where the user's location history supports them spending time. Location data remain on-device.
The core feature must work without a server, telemetry, cloud processing, online routing, or an OSM
download.

The user-facing visualization should resemble a continuous density heatmap rather than visible grid
squares:

- hotter colours mean more supported time;
- cooler colours mean less supported time;
- precise evidence should form tighter hotspots;
- uncertain evidence should form broader areas;
- evidence too weak to place should remain unpainted and unresolved;
- the display must not imply that the user was stationary when they may have been travelling.

The working user-facing names are **Location history heatmap** or **Time heatmap**. The internal metric
currently uses `OBSERVED_PRESENCE_V1`. Critique both the internal and product terminology and recommend
one clear user-facing name.

The UI should expose only information useful to a normal user. Terms such as UTC partition,
generation, checkpoint, posterior, compaction, and backfill must remain internal. If incomplete data
materially changes what the map shows, the UI may use a quiet message such as “Preparing older
history. Recent data is ready.” or a short explanation of unmapped time. Challenge whether even that
is necessary.

## Non-negotiable semantic invariants

Attempt to find any place where the proposed architecture violates these rules:

1. Sampling more frequently must not create more time or heat.
2. Every canonical tracked millisecond is counted exactly once as spatially supported or unresolved.
3. Spatial hypotheses may split time only when their probabilities sum to one.
4. Rendering, interpolation, smoothing, zooming, panning, and viewport clipping must not create or
   renormalize canonical time.
5. Location uncertainty changes spatial spread, not the amount of time.
6. Missing, implausible, rejected, mock, stale, or inadequately precise evidence must not become a
   precise painted location.
7. Observation-supported time includes both stops and travel. It must not be called stationary dwell.
8. Unsupported gaps must not become invented routes or filled regions.
9. The same source snapshot and model/config version must produce deterministic analytical results.
10. Display normalization, colour, opacity, confidence, evidence provenance, and canonical time are
    distinct quantities.
11. No persistent analytical cache should exist merely because a feature might someday be opened.
12. Any raw-data deletion must make its effect on future rebuildability explicit.

## Supplied repository context

Treat every item in this section as **Supplied context**, not verified fact.

- The project uses Kotlin, Coroutines/Flow, Room, WorkManager, Hilt, Jetpack Compose, and MapLibre.
- `:tracker:engine` owns provider ingress and tracking persistence.
- `:core:base` owns the main Room database, DAOs, migrations, and exported schemas.
- `:stats:api`, `:stats:engine`, and `:stats:data` own analytical contracts and implementation.
- `:feature:map` owns map presentation, a UDF `MapStore`, layer registry, MapLibre bridge, viewport
  budgets, and UI.
- `:domain:osm` has an offline importer and a limited matcher, but no qualified general routing graph.
- `:app` owns WorkManager retention and maintenance.
- Android cloud backup is reported disabled with `allowBackup=false` and `fullBackupContent=false`.

The implementation team reports that database version 36 currently introduces:

- append-only `location_observation` evidence before filtering;
- curated/accepted `location_sample` rows;
- half-open `presence_interval` rows;
- `analysis_cell`, `presence_compaction_block`, `presence_cell_contribution`, and
  `presence_compaction_checkpoint` tables;
- a `PresenceCompactor` and metric grid with 25, 50, 100, 250, 500, and 1,000 metre levels;
- exact integer-millisecond attribution using deterministic largest-remainder allocation;
- immutable UTC-day generations, source watermarks, late-arrival reconciliation, and retention gates;
- `ObservedPresenceRepository`, coverage fields, and exact reporting of presentation-omitted time;
- an `ObservedPresenceLayer` currently rendering cell polygons with a MapLibre `Fill` configuration;
- behavior stored as `UNKNOWN` with `BEHAVIOUR_NOT_CLASSIFIED`; V1 performs no stationary/movement
  classification.

The reported V1 temporal model is deliberately conservative but should be attacked:

- adjacent usable fixes no more than five minutes apart receive midpoint-bounded support;
- an isolated fix receives at most one minute of support on each side;
- remaining tracker-run time is spatially unresolved;
- effective `r90` is currently `1.42 * reported horizontal accuracy`, with a 50 m uncalibrated floor,
  a 500 m approximate-permission floor, and no spatial accumulation above 500 m;
- a resolution is eligible only when `r90 <= 0.5 * cell size`;
- a normalized isotropic Gaussian is sampled over a nearby 3×3 cell neighbourhood;
- every eligible resolution is currently persisted independently.

## Revised product direction requiring adversarial review

The team is considering replacing permanent, proactive multiresolution grid materialization with this
lazy architecture:

1. Raw observations remain the primary evidence while retained.
2. A semantic interval representation may retain duration, spatial support, uncertainty, and provenance.
3. The heatmap is requested only when the user opens it, for the selected dates and viewport plus a
   seam-prevention margin.
4. A grid/scalar field is generated on demand, preferably in memory.
5. A smooth surface is rendered from the grid. Grid boundaries are not shown.
6. A small, strictly bounded, evictable LRU cache may speed reopening; users who never open the
   feature should consume no grid-cache storage.
7. Obsolete computation is cancelled when the date range or viewport changes.
8. There is no proactive background backfill by default. Progressive results may be used for very
   large histories.

The intended smooth renderer would reconstruct a continuous surface from occupied cells. The current
analytical model already spreads time spatially according to observation uncertainty, so rendering
must use only enough interpolation/blur to hide cell boundaries. A second broad kernel-density pass
could double-smooth the data and imply presence outside the supported region.

This direction contains an unresolved retention decision:

- If raw evidence is retained, a heatmap can be rebuilt on demand.
- If raw evidence is deleted, historical heatmaps can survive only if a smaller canonical derived
  representation remains.
- Keeping `PresenceInterval` plus centre/covariance/duration may be smaller than a multiresolution
  grid but is not free.
- Deleting both raw evidence and derived spatial evidence means old heatmaps intentionally disappear.

Do not assume which policy is correct. Compare the user value, privacy meaning, storage, performance,
rebuildability, and semantic-versioning consequences of each choice.

## Candidate architectures to attack and compare

Evaluate at least these alternatives. You may propose a better one, but must still compare it against
all four.

### A. Persisted multiresolution daily grid

Persist every supported resolution as immutable daily generations. Query full days from the grid and
compute partial-day boundaries from intervals.

### B. Raw-only on-demand computation

Persist no analytical ledger or grid. Reconstruct intervals, uncertainty, and the requested viewport
directly from raw observations every time, with only a bounded disposable cache.

### C. Canonical interval/kernel ledger plus disposable grid

Persist versioned half-open intervals containing duration, spatial support, uncertainty parameters,
behavior state, and provenance. Generate the display grid and smooth surface lazily. Do not persist a
multiresolution pyramid.

### D. Single native-resolution derived representation

Persist each supported interval once at its uncertainty-appropriate native spatial resolution or as a
compact kernel. Aggregate/coarsen lazily for display and retain only a bounded render cache.

For each alternative, quantify storage and computational growth with formulas rather than invented
benchmarks. Identify what grows with observation count, interval count, travelled distance, unique
cells, days, resolutions, viewport area, and repeated rebuilds.

## Required adversarial questions

### Semantics and temporal reconstruction

- Does “time near an area” have a defensible meaning when temporal support is assigned around sparse
  fixes?
- Can midpoint support or isolated ±1 minute support paint a moving user in the wrong place?
- Can overlapping tracker runs, location-disabled periods, duplicate fix times, batched callbacks,
  late delivery, reboot, or wall-clock changes create missing or duplicated time?
- Is `PresenceInterval` actually canonical evidence, a model-specific derived artifact, or an
  unnecessary duplication of raw data?
- What is lost when the estimator version changes after raw evidence has been deleted?
- Should tracking time without a usable location be included in this feature's denominator at all?

### Accuracy and spatial uncertainty

- Is Android-reported horizontal accuracy safely convertible with the fixed `1.42` factor in all
  relevant providers and API conditions?
- Are the 50 m and 500 m floors defensible, too conservative, or falsely precise?
- Is an isotropic Gaussian appropriate in streets, buildings, tunnels, urban canyons, approximate
  permission regions, and batched fixes?
- Does a 3×3 support truncate meaningful mass or distort broad uncertainty?
- Does gating precision by half the cell size create discontinuities or unstable appearance?
- Can uncertainty be represented more simply without storing every resolution?

### Grid necessity and storage

- Is a grid needed analytically, or only as a rendering acceleration structure?
- What is the minimum persistent representation required for an all-time heatmap after raw retention?
- Is persisting multiple independent resolution levels unjustified duplication?
- Can coarse levels be derived exactly from one native representation despite mixed precision?
- How should superseded generations be pruned, and what happens after repeated late arrivals?
- How large can every proposed table and index become under stationary, urban walking, daily driving,
  long-distance travel, and pathological continuous-tracking traces?
- Would SQLite row/index overhead dominate the actual values?
- Can a user who never opens the heatmap avoid all feature-specific derived storage and background CPU?

### On-demand performance

- Can raw or interval-based computation meet interactive latency for a month, year, and all-time query
  on low- and mid-range Android devices?
- How should work be streamed, bounded, cancelled, and progressively rendered?
- How far outside the viewport must data be queried to avoid heatmap seams?
- What happens during rapid pan, zoom, date changes, process death, and memory pressure?
- Does a bounded LRU cache materially help, and what should its key and invalidation inputs be?
- Is a disk cache justified after first use, or should the cache remain memory-only?
- At what measured threshold should local raster/vector tiles replace viewport GeoJSON?

### Smooth rendering truthfulness

- Should the smooth surface be reconstructed from cell centres, cell polygons, a raster field, or the
  original spatial kernels?
- How can MapLibre interpolation hide squares without performing a second misleading KDE?
- How should kernel radius relate to analytical cell size, latitude, zoom, and device pixel density?
- Can blur paint across rivers, barriers, or large unresolved gaps?
- How can colour represent absolute time consistently across viewport and zoom while still revealing
  low-intensity areas near a dominant hotspot?
- Will log scaling, clipping, opacity, or heatmap normalization cause equal canonical values to look
  different after panning?
- How should antimeridian crossings, poles, high latitudes, and viewport/tile edges behave?
- Does the renderer need separate treatment for observed, inferred, and unresolved evidence?

### Retention and privacy

- What does “delete old location data” mean if a spatially revealing derived heatmap remains?
- Is a compact derived representation meaningfully less privacy-sensitive than raw observations?
- Should deleting raw history also delete every heatmap artifact by default?
- Can retention race with an on-demand build, late observation, cache write, or process death?
- Is a durable checkpoint needed if no permanent grid exists?
- Does disabling Android backup fully cover database files, WAL/SHM files, exports, diagnostics,
  user-selected document destinations, and device-to-device transfer?

### User experience

- Is “Observed presence” understandable, or should the feature be “Location history heatmap” or
  “Time heatmap”?
- Which facts are genuinely useful to a user: incomplete older history, unmapped tracking time,
  approximate-location spread, cache size, or none of these?
- When should the UI silently show available results, show a spinner, or show a quiet notice?
- Could a percentage such as “82% mapped” confuse users or imply analytical accuracy?
- How should the UI explain broad heat caused by low precision without technical terminology?
- Can empty and partial states be distinguished without exposing internal processing concepts?
- What accessibility description communicates intensity without overstating exact location?

### Future classification and movement

- Does the V1 design leave a clean path to `STATIONARY`, `MOVING`, and `UNKNOWN`, or does it bake
  presence assumptions into storage and APIs?
- Should a future stationary-time layer be separate from the location-history heatmap?
- Should movement corridors share the same grid, use line/corridor structures, or remain a separate
  overlay?
- Could future OSM matching multiply time across candidate routes or create false precision?
- Which future-facing abstractions are justified now, and which are speculative complexity that should
  be removed?

## Edge cases the review must try to break

At minimum, reason through:

- phone stationary all night with GNSS drift;
- one isolated fix during a long tracker run;
- dense stationary sampling versus sparse stationary sampling;
- walking, cycling, driving, high-speed rail, ferry, and underground travel;
- urban canyon and indoor observations;
- approximate-location permission;
- accuracy changing abruptly between adjacent fixes;
- several-minute and several-hour gaps;
- duplicate timestamps and distinct observations sharing a timestamp;
- out-of-order and late old fixes;
- overlapping and orphan tracker runs;
- tracking with location disabled;
- wall-clock change and reboot;
- repeated daily routes versus continuously novel travel;
- date ranges crossing UTC midnight and local daylight-saving transitions;
- antimeridian and high-latitude travel;
- a dominant home hotspot plus many low-time locations;
- panning while an expensive all-time calculation is running;
- raw retention during computation;
- process death during computation or cache publication;
- no OSM data, incomplete OSM data, and misleading nearby roads.

## Required validation and falsification plan

For every recommended architecture, define tests that could prove it wrong. Include:

- property tests for exact integer time conservation;
- sampling-rate invariance tests;
- deterministic results under input reordering;
- interval non-overlap and half-open boundary tests;
- late-arrival and concurrent-retention tests;
- raw-versus-derived rebuild equivalence;
- uncertainty calibration experiments with ground truth;
- synthetic and recorded stationary/movement traces;
- antimeridian, latitude, viewport-edge, and zoom-stability visual tests;
- smooth-render double-blur and leakage tests;
- low/mid/high device benchmarks for month/year/all-time requests;
- peak memory, cancellation latency, frame stability, battery, cache growth, database growth, and cold
  reopen measurements;
- privacy deletion tests proving which raw, derived, cache, WAL, export, and backup artifacts remain.

Do not invent acceptance numbers. Label suggested targets **Proposed budget** and identify the device and
trace measurements needed to set them.

## Required output structure

Return the review in this order:

1. **Verdict** — choose the architecture you would approve, reject, or approve conditionally.
2. **Top ten failure modes** — ranked by severity and likelihood, with the violated invariant.
3. **Strongest case against the proposed lazy design**.
4. **Strongest case against the persisted multiresolution design**.
5. **Architecture comparison** — A/B/C/D decision table covering correctness, latency, memory,
   storage, battery, privacy, retention, rebuildability, migration complexity, and future evolution.
6. **Recommended minimum architecture** — remove everything not justified for the first useful release.
7. **Canonical data contract** — what is raw evidence, what is durable derived truth, and what is only
   an evictable rendering cache.
8. **On-demand computation and cancellation design** — Kotlin/Flow-oriented pseudocode and ownership
   across the supplied modules.
9. **Smooth MapLibre rendering design** — source form, weights, interpolation, radius/blur, colour
   scale, viewport margin, cache key, and seam prevention without double smoothing.
10. **Retention and deletion policy options** — explicitly state what historical functionality is lost
    under each option.
11. **User-facing design** — recommended name, default behavior, and the exact minimal copy shown only
    when useful. Keep technical details out of the UI.
12. **Storage and performance model** — formulas, worst-case reasoning, and measurements required.
13. **Falsification matrix** — test, expected invariant, failure signal, and likely remediation.
14. **Implementation-agent checklist** — exact repository facts to inspect before changing code.
15. **Phased change plan** — small reversible slices, migration implications, rollback, and deletion of
    obsolete persisted structures.
16. **Sources** — direct primary links and a short explanation of how each source affected the verdict.

## Review discipline

- Be adversarial, not agreeable.
- A simpler design wins unless complexity has a measured or correctness-based justification.
- Separate canonical analytics from rendering caches.
- Treat every spatially revealing derivative as sensitive location data.
- Prefer explicit loss of unavailable history over silently retaining data contrary to user intent.
- Prefer unresolved output over invented precision.
- Do not recommend ML without a concrete on-device advantage, calibration plan, and fallback.
- Do not require OSM or network access for the baseline heatmap.
- Do not expose engineering terminology in user-facing copy.
- Do not present storage or performance guesses as measurements.
- Identify which existing V1 structures should be retained, converted to caches, or removed.
- End with a one-page handoff containing the final recommendation, decisions still requiring product
  input, the first implementation slice, and the five most important local repository checks.
