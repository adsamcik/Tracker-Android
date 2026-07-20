# External research brief: time-preserving presence overlays for Tracker Android

You are a geospatial-algorithm engineer, GIS rendering specialist, trajectory-analysis researcher, Android/Room architect, and mobile performance researcher advising the implementation team for Tracker Android.

## Task

Research and produce an implementation-ready architecture for converting timestamped location evidence into honest, time-preserving MapLibre overlays.

You do **not** have access to the repository. Do not claim to have inspected, built, tested, or verified its code. The implementation snapshot below was supplied by the repository team and may be incomplete. Use it as the integration context for your research, identify assumptions explicitly, and mark questions that the implementation agent must verify in the repository.

Your output will be handed to another model that does have repository access and will implement the selected design. Return one self-contained Markdown report in your response. Do not describe repository edits as already completed. End with a concise handoff containing the recommended decisions, the most important correctness risks, the first implementation slice, and a checklist of facts the implementation agent must verify locally.

## Product context and non-negotiable goals

Tracker is a single-user, local-first Android app. Location and activity data remain on-device; the core feature must not require a server, telemetry, cloud routing, or network access. Local user-imported OSM data may be used when available. The application uses Kotlin, Coroutines/Flow, Room, WorkManager, Hilt, Jetpack Compose, and MapLibre Compose.

The primary analytical quantity is accumulated estimated presence time in seconds. The system must show:

1. where the user spent time;
2. where the user likely travelled during plausibly continuous intervals;
3. how strong or weak the evidence is for each spatial claim;
4. which tracked durations remain spatially unresolved;
5. optional views such as visit count, route frequency, recency, movement mode, and altitude bands.

The following invariants are mandatory:

- Every canonical tracked interval contributes its duration exactly once to one of stationary dwell, movement, or unresolved duration.
- A duration may be represented by multiple spatial hypotheses only when their probabilities sum to one; hypotheses must not multiply time.
- Stationary time must not also count as movement time.
- Sampling frequency must not create additional time or heat.
- Interpolation and map matching must redistribute duration, never create it.
- Raw seconds, evidence provenance, confidence, display normalization, colour, and opacity are separate concepts.
- Uncertain data must render less precisely, not merely less brightly.
- Large, implausible, or unsupported gaps remain unresolved rather than becoming false routes.
- Full rebuilds and incremental processing must be deterministic for the same source snapshot, model/config version, and grid version.
- Retention may delete raw evidence only after the derived representation is complete and recoverably checkpointed through the deletion horizon.

## Supplied repository and implementation snapshot

The following information was supplied by the implementation team. Treat it as **Supplied context**, not as independently verified evidence. Use the named modules and types to make your recommendations concrete, but do not invent method signatures or unlisted behaviour.

- `:core:base` owns the main Room database, entities, DAOs, migrations, and schema exports. Room schema changes require a migration, exported schema, and migration tests.
- `:core:model` contains Room-free shared models.
- `:stats:api`, `:stats:engine`, and `:stats:data` contain analytics contracts, algorithms, and persistence adapters. Preserve module boundaries; do not make map presentation own canonical analytics.
- `:feature:map` owns MapLibre presentation, its UDF `MapStore`, layer registry, rendering bridge, viewport budgets, and map UI.
- `:domain:osm` contains an offline OSM PBF importer, spatial way index, and HMM/Viterbi matcher. The current matcher is not a general routing graph and has material cross-way/path-ambiguity limitations; do not describe it as full offline routing without evidence.
- `:tracker:engine` owns provider ingress and tracking persistence. The overlay design must consume its evidence without redesigning the location-collection product.
- WorkManager-based retention and maintenance live in `:app` and must coordinate with derived-data checkpoints.

The current uncommitted working tree introduces a conservative v1 presence/dwell baseline:

- Room database version 36 and new `location_observation`, `presence_interval`, `analysis_cell`, `dwell_compaction_block`, `dwell_cell_contribution`, and `dwell_compaction_checkpoint` tables.
- An append-only pre-filter observation record in `LocationObservation`, while `LocationSample` remains the accepted/curated stream.
- `DwellCompactor`, which currently gives usable observations bounded temporal support and records the rest of tracker-run time as unresolved. It intentionally does not yet perform behavioural or movement inference.
- A versioned metric grid with 25, 50, 100, 250, 500, and 1,000 metre levels and uncertainty-weighted Gaussian attribution.
- Immutable UTC-day compaction generations, late-arrival reconciliation, watermarks, and retention gating.
- `DwellMapRepository`/`DefaultDwellMapRepository`, which query committed full-day blocks plus exact boundary fragments and choose a display resolution based on uncertainty and cell budget.
- `DwellHeatmapLayer`, which currently emits viewport GeoJSON fill cells with absolute log-scaled dwell weights through the existing `MapLibreLayerConfig` bridge.
- Existing `location_heatmap`, `TemporalHeatmapAggregator`, `LocationPathLayer`, `TiledDataSource`, and other visual pipelines. These may provide reusable rendering machinery, but fix density, visit density, a drawn polyline, and conserved presence time are not interchangeable semantics.

Additional v1 details supplied for research and critique:

- Canonical intervals are half-open and intended to cover tracker-run time without overlap. Resolved states are `OBSERVED` and `INFERRED`; unavailable spatial support is `UNRESOLVED` with a reason. V1 currently produces observed and unresolved intervals but no inferred movement.
- Adjacent usable fixes no more than five minutes apart receive midpoint-bounded temporal support. An isolated usable fix receives at most one minute of support on each side. Remaining tracker-run coverage becomes unresolved.
- Current horizontal uncertainty uses a global fallback rather than a device-calibrated model: nominal `r90 = 1.42 * reportedHorizontalAccuracy`, with a 50 m uncalibrated floor and a 500 m approximate-permission floor. Claims with effective r90 above 500 m are not accumulated spatially.
- The current finest-resolution gate only uses a cell size when `r90 <= 0.5 * cellSize`. A normalized isotropic Gaussian is sampled over nearby cells; each supported resolution is materialized independently so each pyramid level should conserve the same resolved duration.
- Full UTC days are materialized as immutable generations. Interactive queries combine committed full-day blocks with freshly materialized boundary fragments. Large history backfills are bounded and may return a `MATERIALIZING` coverage state.
- A map request supplies a preferred resolution and maximum cell count. The repository may coarsen the resolution to respect uncertainty or the cell budget. Returned cells retain expected, observed, and inferred seconds separately.
- The current dwell renderer converts cells to GeoJSON polygons and a MapLibre `Fill` config. Display weight is a capped `ln(1 + seconds)` scale with eight hours as the reference maximum; the default time window is 30 days. Zoom selects from the fixed metric resolutions.
- The existing offline OSM matcher loads nearby imported driveable road ways through a coarse spatial index, projects each observation to at most six candidates within 50 m, and applies Viterbi scoring. Reported location accuracy is clamped to a 4–30 m emission sigma; transition scoring uses route-versus-direct-distance disagreement with an 8 m scale plus a fixed way-switch penalty. It splits runs after 60 seconds or 200 m. It follows centreline geometry when consecutive matches lie on the same way, but it does not perform general graph routing between arbitrary ways. Cross-way output is limited to snapped endpoints within 30 m, and it does not currently expose calibrated hypothesis probabilities.
- Existing map rendering supports point heatmaps, heat-like line layers, ordinary/gradient lines, filled polygons, composite layers, viewport caches, and performance budgets. Reuse is possible, but canonical analytical values must not inherit legacy visualization semantics.

Explicitly analyse this semantic concern: v1 assigns all observed temporal support around usable fixes to the dwell accumulator without stationary/movement classification. Its value therefore appears to be observed presence time, not strictly stationary dwell time. Recommend whether the product name, schema semantics, estimator, or some combination must change before release. Mark the exact code behaviour as **Needs repo validation**.

## Source evidence available in the app

The team reports the following evidence as present or potentially joinable. Base the algorithm research on these inputs, but state which are required, optional, or unsafe to use without calibration. Put uncertain persistence/join/clock details in the implementation-agent verification checklist:

- latitude/longitude and wall-clock fix time;
- fix and receive monotonic elapsed time;
- callback delivery age, batch index, and batch size;
- horizontal and vertical accuracy;
- fused/geoid-corrected and raw GPS altitude;
- speed and speed accuracy;
- provider, request priority, acquisition mode, permission precision, and mock status;
- accepted/rejected/stale ingress disposition;
- tracking policy and tracker-run boundaries;
- activity/motion classifications, steps, pressure/barometer evidence, and neighbouring samples;
- bearing and bearing accuracy are desired but are not reported as persisted in the supplied v1 schema; do not assume availability;
- local OSM availability, road/path coverage, topology, and matcher confidence.

Where desired evidence is missing, distinguish among adding it at provider ingress, deriving it reproducibly, leaving it unavailable, and postponing the dependent feature. Do not silently invent inputs.

## Research method and evidence requirements

Use current external research to resolve the algorithmic and platform questions that cannot be answered from the supplied snapshot. Prefer:

- peer-reviewed trajectory, stay-point, map-matching, spatial uncertainty, and probabilistic inference papers;
- official Android location, Room, WorkManager, and performance documentation;
- official MapLibre Native/Style Specification documentation and relevant primary implementation material;
- authoritative OSM data-model and tagging documentation;
- primary documentation for any proposed spatial index or file/tile format.

Search for more recent work where it materially affects the recommendation. Cite sources with direct links next to the claims they support, then include a compact bibliography. Explain how each important research result applies to this offline Android product rather than merely listing papers. Distinguish established results from your engineering inference. Avoid secondary blog posts when a paper, specification, or official document is available.

Do not fabricate citations, quotations, benchmark values, API capabilities, or consensus. If browsing or source retrieval is unavailable, state that limitation and label unsupported literature claims **Needs research validation**.

## Required analysis

### 1. Assess the supplied baseline and prepare a repository-validation plan

Reconstruct the expected data path from provider callback to raw observation, accepted sample, canonical interval, analytical cell, repository query, layer configuration, and MapLibre rendering using only the supplied snapshot. Include retention and rebuild paths. Identify every link that the implementation agent must confirm in code.

For each important claim use one of these labels:

- **Supplied context** — stated in this prompt but not independently verified;
- **Research-supported** — supported by a cited primary external source;
- **Proposed** — recommended target design;
- **Inference** — reasoned conclusion from available evidence;
- **Unknown** — insufficient evidence;
- **Needs repo validation** — must be checked by the implementation agent in code, schema, or tests;
- **Needs empirical validation** — requires measurement, device testing, calibration data, or ground truth.

Analyse the likely correctness requirements and produce concrete repository checks for at least:

- half-open interval coverage and global non-overlap;
- overlapping/orphan tracker runs and sessions with location disabled;
- duplicate timestamps, out-of-order delivery, late old fixes, and concurrent writes;
- wall-clock changes, reboot boundaries, and monotonic-time fallbacks;
- UTC partition boundaries and exact partial-day queries;
- current-day materialization and replacement safety;
- source high-watermarks and raw-data deletion safety;
- rebuild after estimator/config/grid changes;
- mass conservation at each resolution level;
- uncertainty gating, missing accuracy, coarse permission, and rejected/mock evidence;
- antimeridian, poles, high latitudes, projection distortion, and grid-origin stability;
- viewport truncation and whether dropping hottest/lowest cells changes analytical truth or only presentation;
- UI wording, legends, accessibility summaries, empty/partial/materializing states, and saved layer IDs;
- export/import, privacy-policy, backup, and storage-accounting implications.

Identify likely correctness risks, architectural debt, and naming/semantic mismatches separately from future enhancements. Phrase suspected implementation defects as questions or **Needs repo validation**, not as confirmed bugs.

### 2. Define the canonical semantic ledger

Specify exact meanings and units for:

- total tracked duration;
- stationary dwell seconds;
- movement seconds;
- unresolved seconds and reason;
- observed versus inferred seconds;
- per-cell expected seconds;
- movement-corridor expected seconds;
- confidence/evidence quality;
- visit count and route frequency;
- recency or temporally decayed display values.

Define equations that make the conservation rules testable. At minimum, for every canonical interval `i` with duration `Delta t_i`, require:

```text
dwellSeconds_i + movementSeconds_i + unresolvedSeconds_i = Delta t_i
```

and, for any resolved spatial hypothesis set:

```text
sum_hypotheses(probability_h) = 1
sum_spatial_bins(expectedSeconds_bin) = resolvedSeconds_i
```

Clarify that a multiresolution pyramid preserves the same mass independently at every level; values must not be summed across resolution levels.

Recommend whether `PresenceInterval` should remain a general canonical interval with typed spatial posteriors, be extended with separate dwell/movement records, or be replaced by another versioned model. Prefer an evolutionary schema compatible with the in-progress work unless a different choice has a compelling correctness benefit.

### 3. Design adaptive interval classification

For each adjacent-evidence interval, classify or score:

- stationary dwell;
- slow local movement;
- plausible continuous travel;
- map-matchable travel;
- free-space travel;
- ambiguous movement with weighted hypotheses;
- long/unsupported gap;
- invalid evidence.

Use an adaptive score or decision system based on the evidence listed in the supplied snapshot: elapsed time, direct distance, uncertainty-region overlap, implied and reported speed, speed accuracy, neighbouring consistency, activity state, steps, acceleration/turn plausibility, tracking policy, provider/delivery quality, local OSM candidates, altitude/vertical uncertainty, and barriers where local data supports them.

Do not rely on a single fixed time or distance threshold. Provide Kotlin-oriented pseudocode, explicit feature normalization, hard rejection gates, confidence calibration hooks, and safe behaviour when fields are absent.

Account for endpoint dwell explicitly. A long pause near an endpoint must not be smeared uniformly along a route. Compare midpoint/Voronoi support, change-point or stay-point segmentation, and a small state-space model; recommend the simplest method that meets the invariants and can be validated on-device.

### 4. Define spatial posteriors and accumulation

Provide mathematical definitions and implementation details for:

- accuracy-aware stationary kernels, including the Android accuracy percentile conversion and a calibrated floor;
- robust stay centres and GNSS-jitter suppression;
- movement paths with time parameterization rather than uniform distance when endpoint speeds imply otherwise;
- free-space uncertainty corridors;
- multiple route hypotheses with normalized probabilities;
- corridor width as a function of observation uncertainty, route ambiguity, temporal gap, and map confidence;
- confidence values that remain separate from expected seconds;
- duration-preserving aggregation between grid resolutions;
- optional visit extraction and temporal decay.

Assess the current row-local metric lattice against alternatives such as Web Mercator/slippy tiles, H3, S2, quadtree, raster tiles, vector segments, or a hybrid. A recommendation to keep the current grid must address tile boundaries, latitude behaviour, antimeridian handling, spatial indexing, aggregation cost, and MapLibre conversion. A recommendation to replace it must include migration and rebuild costs.

Determine whether dwell cells and movement corridors should use different canonical structures. Do not force line-like movement into point kernels merely to reuse the current heatmap layer.

### 5. Design path inference honestly

Choose among these outcomes per continuous movement interval:

- high-confidence local OSM match;
- several weighted candidate paths;
- free-space probabilistic corridor;
- direct connection only when physically and geometrically plausible;
- unresolved gap.

Evaluate the supplied `OsmHmmMapMatcher` design against research-backed map-matching requirements, including its imported-data gate, fixed candidate radius and scoring constants, lack of a full routable graph, cross-way transitions, mode coverage, tunnels/ferries/rail/paths, and absence of exposed calibrated likelihoods. Separate conclusions supported by the supplied description from checks that require repository inspection. Specify the smallest changes needed before it can support a time-preserving movement posterior. Never claim map matching removes uncertainty.

The offline feature must degrade cleanly when no OSM extract is installed or map data is incomplete. Importing OSM data may improve inference but cannot be required to view the user's history.

### 6. Recommend a production rendering architecture

Compare, with a firm recommendation:

- viewport GeoJSON using current `Fill`, `Heatmap`, `HeatLine`, `Line`, or `Composite` configs;
- cached vector features or vector tiles;
- locally generated raster tiles;
- a hybrid in which accumulated history and recent/revisable movement use different forms.

Integrate with the existing `DefaultLayerRegistry`, `MapStore`, `MapDataChangeObserver`, `MapLibreLayerEngine`, `PerformanceManager`, `ViewportConfigCache`, date-range controls, and MapLibre lifecycle constraints. Keep analytical storage outside `:feature:map`.

Specify sources/layers, feature properties, viewport and zoom queries, cache keys, invalidation, layer ordering, interaction, and accessibility. Address GeoJSON string memory and JNI/native transfer cost for months or years of data. Use existing tile helpers only where their semantics and invalidation model fit the new canonical data.

Define visual rules for:

- absolute seconds versus optional relative/quantile views;
- log/capped scaling and stable legend values;
- confidence-driven opacity or texture without changing time intensity;
- uncertainty-driven blur/corridor width;
- observed versus inferred styling;
- unresolved/coverage presentation;
- overlap between dwell and movement without double visual emphasis;
- stable appearance across zoom levels and tile boundaries;
- partial results, viewport cell-budget truncation, and progressive backfill.

The default view must not let one extreme hotspot hide everything else or make sparse evidence look precise.

### 7. Design incremental processing and retention

Recommend concrete Room schemas, indices, foreign-key behaviour, transaction boundaries, and version keys for:

- raw observations;
- canonical classified intervals;
- dwell posterior contributions;
- movement path/corridor hypotheses;
- unresolved-duration ledger;
- immutable partition generations;
- checkpoints and dirty-partition invalidation;
- optional tile/cache materializations.

Show algorithms for append, late arrival, correction, rollback, partition rebuild, full deterministic rebuild, model upgrade, periodic compaction, and safe raw-data deletion. Explain which stage is triggered synchronously, lazily by a map query, or in WorkManager, and impose bounded work per invocation.

Analyse asymptotic cost and provide formulas for CPU, memory, and storage growth. If numeric budgets are proposed, label them **Proposed budget** until measured on representative devices and datasets. Do not fabricate benchmarks.

### 8. Elevation and movement mode

Recommend altitude primarily as a matching/disambiguation feature and retained metadata unless vertical accuracy supports a user-visible band. Address bridge versus road, tunnel/surface, floors, lifts/stairs, and barometer drift. The primary 2D overlay must remain usable when altitude is absent or unreliable.

Recommend when movement/activity mode should affect plausibility, matching candidates, styling, or filters without allowing a noisy classifier to destroy time conservation.

### 9. Edge-case matrix

Define expected classification, spatial output, confidence, and unresolved behaviour for:

- a stationary phone with GNSS drift;
- a long stay with only a few fixes;
- walking through a building;
- lift and stair movement;
- two fixes around a curved road;
- a river/barrier between fixes;
- parallel roads;
- bridge and road below;
- tunnel or underground metro;
- high-speed train;
- ferry;
- several-minute and several-hour gaps;
- batched or stale observations;
- abrupt provider or permission-precision changes;
- missing/inaccurate OSM data;
- repeated visits;
- overlapping dwell and travel corridors;
- clock changes, reboot, duplicate fix times, and out-of-order delivery;
- antimeridian crossing and high-latitude travel.

### 10. Validation strategy

Create reproducible unit, property, migration, integration, benchmark, and visual tests. Reuse the repository's JUnit/Kotest/Robolectric patterns. Include synthetic traces plus privacy-safe recorded/research fixtures where available.

Measure:

- exact conservation of tracked time;
- no interval overlap or omission;
- sampling-rate invariance;
- incremental versus full-rebuild equivalence;
- deterministic results under input reordering;
- late-arrival and retention safety;
- spatial error against ground truth;
- stay detection error;
- false route-connection and correct-match rates;
- probability calibration and unresolved-rate trade-offs;
- grid resolution/origin sensitivity;
- antimeridian, polar, tile-boundary, and zoom stability;
- viewport query latency, compaction latency, render latency, and frame stability;
- memory, database growth, cache growth, and battery/energy cost.

Include stationary, walking, driving, rail, ferry, indoor, urban-canyon, underground, sparse-sampling, coarse-permission, and missing-OSM scenarios. State acceptance criteria where defensible; label values requiring device measurement as **Needs validation**.

## Required report structure

Present the report in this order:

1. **Executive recommendation** — the production baseline and why it fits Tracker.
2. **Assessment of the supplied implementation** — reconstructed data flow, likely strengths and risks, and explicit repository checks; do not present it as an inspection.
3. **Semantic ledger and invariants** — exact layer meanings, units, equations, and naming corrections.
4. **Current-to-target gap analysis** — what can remain, what must change, and what is optional.
5. **Target module architecture** — ownership and dependency flow across existing modules.
6. **Canonical storage and indexing** — Room entities, indices, partitions, versions, and migration approach.
7. **Segment decision model** — gates, scores, confidence, and Kotlin-oriented pseudocode.
8. **Mathematical formulation** — dwell kernels, movement corridors, hypotheses, confidence, and multiresolution conservation.
9. **Incremental/rebuild/retention algorithms** — transactions, checkpoints, invalidation, and concurrency.
10. **MapLibre implementation** — sources, configs/layers, caching, zoom behaviour, legends, and accessibility.
11. **Performance and storage model** — complexity, formulas, proposed budgets, and measurement plan.
12. **Worked examples and edge-case matrix**.
13. **Validation plan and acceptance gates**.
14. **Phased implementation plan** — small reviewable slices with supplied modules/types likely touched, proposed new components, dependencies, tests, migration impact, and rollback strategy. Do not invent existing file paths.
15. **Research conclusions and source map** — important external findings, how they affected the design, direct citations, and compact bibliography.
16. **Implementation-agent handoff** — decisions to implement, pseudocode/data contracts to carry forward, ranked risks, unresolved questions, and a repository-validation checklist.

## Phasing expectations

Use the supplied conservative v1 work as the likely first baseline, subject to research and repository validation. Prefer this progression unless evidence supports a better order:

1. Harden and correctly name the conservative observed-presence/unresolved ledger; prove migrations, time conservation, retention safety, viewport queries, and UI truthfulness.
2. Add calibrated stationary/stay classification so a true dwell layer does not absorb movement time.
3. Add canonical free-space movement corridors with duration allocation and explicit uncertainty, independent of OSM availability.
4. Integrate qualified offline OSM matching and weighted ambiguity only after the fallback corridor is correct.
5. Add separate confidence/unresolved views and optional visit, route-frequency, recency, activity-mode, and altitude filters.
6. Move from viewport GeoJSON to vector/raster tiling only when measured payload, latency, or memory thresholds justify the added complexity.

Each phase must preserve deterministic rebuildability and must not reinterpret old derived rows under a new algorithm without a version change.

## Decision discipline

- Prefer an actionable recommendation over an unranked catalogue of techniques.
- Prefer conservative uncertainty over false precision.
- Prefer deterministic/probabilistic methods over machine learning unless ML has a specific measurable advantage and a feasible on-device training/inference story.
- Separate correctness requirements from rendering polish and advanced analytics.
- Separate supplied repository context, external evidence, inference, and proposals.
- Refer to supplied module/type names to make the handoff actionable, but never imply you inspected those files. For external claims, use primary papers or official Android, Room, MapLibre, OSM, or spatial-index documentation and provide direct links.
- Do not fabricate citations, APIs, benchmark results, schema state, test results, or capabilities.
- Do not silently broaden the task into a redesign of tracking collection, cloud sync, or online routing.
