# Adversarial architecture, UX, and UI review: Tracker location-history heatmap

Act as a three-person review panel:

1. **Adversarial architecture reviewer** — geospatial inference, temporal reconstruction, Android
   location evidence, Kotlin/Flow, Room/SQLite, WorkManager, MapLibre, performance, and privacy.
2. **Product UX reviewer** — information architecture, user mental models, map interaction, date-range
   selection, loading/empty/error states, privacy expectations, and plain-language communication.
3. **UI and accessibility reviewer** — visual hierarchy, map readability, color and contrast, touch
   ergonomics, responsive layouts, dark/light themes, large text, TalkBack, and motion.

Each reviewer must first form an independent verdict. Then reconcile disagreements into one ordered,
implementation-ready recommendation. Be adversarial: try to disprove the design, remove unjustified
complexity, and identify where technically correct behavior would still mislead or frustrate users.

## Immutable repository target

Review the implementation at this exact commit:

- [Implementation commit `00fe627acedb4f9aa3d43ffd0408ec4ae9a02228`](https://github.com/adsamcik/Tracker-Android/commit/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228)
- [Repository tree](https://github.com/adsamcik/Tracker-Android/tree/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228)
- [Complete parent comparison](https://github.com/adsamcik/Tracker-Android/compare/6c25dab52...00fe627acedb4f9aa3d43ffd0408ec4ae9a02228)
- [Canonical feature contract](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/docs/LOCATION_HISTORY_HEATMAP_CANONICAL_CONTRACT.md)

Browse the linked public repository. Follow imports, call sites, Room relationships, migrations,
feature registration, UI state, resources, and tests. Link every code finding to the immutable GitHub
file and relevant line range. Do not say “needs repository validation” when GitHub can answer it.

Do not claim to have run the app, used TalkBack, or measured a physical device. Distinguish:

- **Verified in code**
- **Visible in an attached screenshot**
- **Inferred from code and screenshot together**
- **Requires runtime/device validation**
- **Requires product decision**

## Product goal

Tracker is a local-first, single-user Android application. The feature should provide a smooth,
attractive **Location history heatmap** that shows where retained location evidence spatially supports
time.

The intended user mental model is:

- warmer areas indicate more supported time near that area;
- broader areas indicate less precise recorded location;
- time that cannot be placed honestly is not painted;
- the map does not claim the user was stationary;
- more frequent sampling cannot create more time;
- no route is invented through sparse gaps;
- selecting different dates changes the history being viewed, not the analytical meaning of colors;
- deleting raw history removes the corresponding heatmap history unless the user explicitly chooses
  to retain a sensitive frozen summary in a future product option.

The baseline must remain on-device and work without a server, telemetry, cloud processing, online
routing, or mandatory OSM data.

## Current implementation state to verify

This commit deliberately contains two generations of design:

- The older active map path persists `presence_interval`, multiresolution cells, contribution rows,
  daily generations, and checkpoints, then renders polygon cells.
- A new pure `SPATIALLY_SUPPORTED_TIME_V1` estimator establishes exact half-open supported/unresolved
  time semantics but is not yet connected to Room or MapLibre.
- Retention-triggered legacy compaction is disabled by default, but opening the current layer may still
  materialize persistent derivatives.
- The intended replacement is raw-backed, on-demand CPU rasterization of normalized compact kernels,
  displayed through MapLibre `ImageSource`/`RasterLayer`, with a memory-only bounded LRU and no second
  heatmap blur.

Determine what is truly active, dormant, unreachable, user-triggered, and background-triggered. Do not
mistake a contract or test fixture for shipped behavior.

## Architecture evidence entry points

- [`SpatiallySupportedTimeEstimator`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/heatmap/SpatiallySupportedTimeEstimator.kt)
- [Estimator tests](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/stats/engine/src/commonTest/kotlin/com/adsamcik/tracker/stats/engine/heatmap/SpatiallySupportedTimeEstimatorTest.kt)
- [`LocationObservation`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/LocationObservation.kt)
- [`LocationObservationDao`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/LocationObservationDao.kt)
- [`LocationObservationAdapter`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/pipeline/LocationObservationAdapter.kt)
- [`TrackerRun`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/TrackerRun.kt)
- [`TrackerRunDao`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/TrackerRunDao.kt)
- [`PresenceCompactor`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/analysis/PresenceCompactor.kt)
- [`MetricAnalysisGrid`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/analysis/MetricAnalysisGrid.kt)
- [Database migration](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabaseMigrations.kt)
- [Room schema 36](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/36.json)
- [`DefaultObservedPresenceRepository`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultObservedPresenceRepository.kt)
- [`RetentionPipelineWorker`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/app/src/main/java/com/adsamcik/tracker/app/maintenance/RetentionPipelineWorker.kt)
- [`DataRetentionWorker`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/app/src/main/java/com/adsamcik/tracker/maintenance/DataRetentionWorker.kt)
- [`LegacyPresencePersistence`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/analysis/LegacyPresencePersistence.kt)

## UX and UI evidence entry points

- [`ObservedPresenceLayer`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/layers/impl/ObservedPresenceLayer.kt)
- [`HeatmapColorRamps`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/layers/impl/HeatmapColorRamps.kt)
- [Layer registry and legend](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/layers/registry/DefaultLayerRegistry.kt)
- [`MapStore`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/presentation/MapStore.kt)
- [`MapScreen`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/ui/MapScreen.kt)
- [`MapChromeHost`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/ui/controls/MapChromeHost.kt)
- [`MapControlBar`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/ui/controls/MapControlBar.kt)
- [`LayerPickerPopover`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/ui/controls/LayerPickerPopover.kt)
- [`DateRangePopover`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/ui/controls/DateRangePopover.kt)
- [`MapAccessibilitySummary`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/ui/MapAccessibilitySummary.kt)
- [`MapLibreLayerConfig`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/presentation/bridge/MapLibreLayerConfig.kt)
- [Map strings and user copy](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/res/values/strings.xml)
- [Map accessibility tests](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/test/java/com/adsamcik/tracker/map/ui/MapAccessibilitySummaryTest.kt)
- [Layer registry tests](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/test/java/com/adsamcik/tracker/map/layers/registry/DefaultLayerRegistryTest.kt)

## Required screenshot evidence packet

Actual runtime screenshots are not stored in the repository. The prompt should be supplied together
with the following privacy-safe screenshots from a synthetic trace. Use these exact filenames so the
review can cite them unambiguously:

| Filename | Required state |
|---|---|
| `01-map-default-light.png` | Map landing state, light theme, no popover |
| `02-map-default-dark.png` | Same viewport and data, dark theme |
| `03-layer-picker.png` | Layer picker showing both “Location history heatmap” and “Distinct visits” |
| `04-date-range-picker.png` | Date presets and current selection |
| `05-heatmap-city.png` | Loaded heatmap at city scale |
| `06-heatmap-neighborhood.png` | Same data at neighborhood scale |
| `07-heatmap-street.png` | Same data at street scale |
| `08-dominant-and-minor-hotspots.png` | One dominant hotspot plus several low-time locations |
| `09-broad-low-precision.png` | Broad support caused by intentionally poor precision |
| `10-sparse-travel-gap.png` | Sparse journey demonstrating unresolved middle time |
| `11-loading.png` | Initial load without an old-date frame masquerading as current |
| `12-partial.png` | Materially incomplete progressive result, if the product supports one |
| `13-no-history.png` | No tracker history for selected dates |
| `14-unmappable-history.png` | Tracker history exists but none is placeable |
| `15-error-retry.png` | Recoverable computation/render failure |
| `16-large-font.png` | 200% font scale with layer/date controls and legend visible |
| `17-landscape-small.png` | Compact-height landscape layout |
| `18-talkback-focus-order.png` | Annotated focus order or screen recording transcript |
| `19-reduced-motion.png` | Reduced-motion behavior during frame replacement |
| `20-target-reference.png` | Desired smooth visual reference, clearly labelled **reference, not current app** |

For each screenshot, provide this metadata in a companion `screenshots.md` or directly beside the
attachment:

- app commit/build variant;
- device model or emulator profile, Android version, logical resolution, density, and font scale;
- light/dark theme and contrast settings;
- selected date range, map zoom, pitch, and rotation;
- synthetic trace scenario and precision distribution;
- whether the basemap was online, cached, local, or blank;
- whether the screenshot is **actual current UI**, **debug visualization**, or **target reference**.

Never use real home, work, or routine coordinates. Never infer app behavior from the target-reference
image. If a required screenshot is unavailable, explicitly list the missing visual evidence and limit
the corresponding finding to code-level review. Do not invent a screenshot result.

The visual reviewer should inspect images at full resolution and may request crops of the legend,
controls, seams, or low-intensity regions. Cite screenshot filenames in every visual finding.

## Architecture questions

- Can the linked raw evidence actually reconstruct canonical time across batching, process death,
  reboot, mutable tracker runs, and wall-clock changes?
- Does the pure estimator conserve exact half-open time under overlaps, duplicate timestamps,
  conflicting locations, overflow, and clock-domain collisions?
- Is freshness assignment movement-safe, or can it still create endpoint dwell?
- Is approximate permission honestly unresolved until calibrated?
- Does any code still create derived storage or background CPU for users who never open the feature?
- Should the unreleased V36 persistent grid tables be retained, quarantined for one release, or removed
  before release? What migration evidence is required?
- Can retention race computation, WAL replay, cache publication, or late old inserts?
- What source snapshot/revision and retention epoch are minimally required?
- Is CPU scalar rasterization from original compact kernels feasible with the repository’s actual
  MapLibre version and lifecycle, without a second KDE?
- What must be measured on physical low/mid/high devices before choosing one image versus local tiles?

## Product UX questions

- Can a normal user distinguish “Location history heatmap” from “Distinct visits” without reading a
  technical explanation? Should both be visible at the same hierarchy?
- Does the date-range interaction make “Today,” “Yesterday,” “This week,” “Last week,” “Last month,”
  “All time,” and custom dates predictable across timezone and DST changes?
- What should happen visually and verbally during initial load, pan/zoom recomputation, date changes,
  process death, partial progress, empty history, unmappable history, and failure?
- When should the previous frame remain visible, and when would it misrepresent the newly selected
  dates or viewport?
- Is any unmapped-time notice genuinely useful? Define a qualitative materiality rule without showing
  a misleading percentage.
- Does the legend explain absolute supported-time density, or does it imply sample count, probability,
  precision, or stationary dwell?
- Can the user understand why a region is broad without exposing covariance, kernels, or accuracy
  statistics?
- Does deleting location history match what remains in derivatives, exports, screenshots, backups,
  and MapLibre caches?
- Is the feature valuable and comprehensible over a blank/offline basemap?

## UI and accessibility questions

- Does the heatmap look smooth without revealing cells, swimming with the viewport, leaking at edges,
  or broadening through a second blur?
- Do equal physical supported-time densities retain stable color across pan and zoom?
- Can low-time locations remain visible beside a dominant home-like hotspot without per-viewport
  normalization?
- Does the palette work over light/dark basemaps, satellite-like contrast, blank backgrounds, common
  color-vision deficiencies, grayscale, and increased contrast?
- Are alpha blending and basemap labels still readable, or does the overlay obscure streets and place
  context?
- Are layer/date controls, popovers, legend, notices, and retry actions visually prioritized and at
  least Android’s recommended touch size?
- Do compact width, landscape, display cutouts, gesture navigation, one-handed reach, and 200% font
  scale preserve access to every action?
- Does TalkBack announce the selected layer, selected date range, loading/empty/error state, useful map
  summary, and control state without narrating meaningless color pixels?
- Is focus order stable when popovers open and close? Does focus return to the invoking control?
- Are loading and frame replacement motions calm, cancellable, and compatible with reduced motion?
- Are screenshots/share exports semantically and visually consistent with the live map?

## Required state matrix

Audit and propose exact UI behavior for every combination that matters:

- no selected layer / layer selected;
- no history / history but unmappable / map ready;
- loading with no frame / recomputing with a valid same-selection frame / selection changed;
- progressive partial / ready / recoverable failure / fatal source failure;
- online basemap / cached basemap / blank offline background;
- precise evidence / mixed precision / approximate-only evidence;
- small unresolved duration / materially unresolved duration;
- light / dark / high contrast / large text / TalkBack / reduced motion.

For each state specify: visible map content, control availability, progress treatment, exact copy,
accessibility announcement, stale-frame policy, retry behavior, and whether any analytics change.

## Non-negotiable review rules

1. Rendering, color, viewport, zoom, cache, and UI state cannot alter canonical duration.
2. Visual smoothing cannot create a second uncertainty kernel.
3. A screenshot may reveal a visual symptom but cannot prove analytical correctness.
4. Code may prove a state exists but cannot prove it is legible, attractive, or accessible at runtime.
5. Do not recommend user-facing percentages for “mapped,” “coverage,” or “accuracy.”
6. Do not expose partitions, generations, checkpoints, backfill, covariance, cache, model versions, or
   estimator internals.
7. Do not call the layer stationary time or dwell.
8. Prefer no paint and a quiet explanation over invented precision.
9. Treat every location derivative, screenshot, and export as sensitive.
10. Prefer the smallest useful first release; defer speculative movement classification and routing.

## Required output

Return one self-contained Markdown report in this order:

1. **Three independent verdicts** — architecture, product UX, and UI/accessibility.
2. **Reconciled ship/continue verdict** — safe foundation, conditional, or stop-and-rework.
3. **Evidence inventory** — repository files and screenshots actually inspected; missing evidence.
4. **Top findings** — ranked `P0`–`P3`; every finding includes GitHub file/lines and/or screenshot
   filename, failure path, user consequence, smallest correction, and proving test.
5. **Architecture invariant audit** — pass/fail/unknown with evidence.
6. **Current-versus-target map** — what users receive now, what the target requires, and which code
   bridges the gap.
7. **End-to-end user journey** — discover → select layer → choose dates → load → pan/zoom → understand
   broad/unmapped areas → share → delete history.
8. **State matrix** — exact behavior and copy for all material states.
9. **Visual audit** — hierarchy, smoothness, seams, palette, contrast, basemap interaction, responsive
   layout, and motion, citing screenshots.
10. **Accessibility audit** — TalkBack semantics/focus, large text, touch targets, contrast, reduced
    motion, and nonvisual map summary.
11. **Copy deck** — final recommended names, labels, legend text, notices, empty/error messages, and
    accessibility descriptions; show only copy that earns its place.
12. **Rendering recommendation** — original-kernel scalar raster design tied to actual repository APIs,
    including stable intensity, interpolation, margins, date replacement, and resource lifecycle.
13. **Retention/privacy UX** — what deletion promises, what remains, and where the product must explain
    exports or external copies.
14. **Validation plan** — property, migration, concurrency, visual regression, screenshot, TalkBack,
    color-vision, responsive-layout, usability, and physical-device performance tests.
15. **Ordered patch queue** — small reversible changes with exact files, dependencies, migration risk,
    rollback, and exit criteria.
16. **Annotated redesign brief** — describe the minimum set of revised screens so a designer or image
    generator can produce faithful mockups without inventing analytics.
17. **Do-not-build-yet list** — architecture and UI concepts that should be deleted or deferred.
18. **Implementation handoff** — the first three code patches and first three screenshot/UX validations
    to perform next.

End with direct answers to both questions:

1. **What should the implementation agent change next?**
2. **What should the product show—and deliberately not show—to a normal user?**
