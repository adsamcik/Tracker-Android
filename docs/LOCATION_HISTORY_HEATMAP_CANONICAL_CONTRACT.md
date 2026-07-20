# Location history heatmap: canonical V1 contract

Status: implementation baseline for the lazy, raw-backed path.

## Product meaning

The layer is called **Location history heatmap**. It shows spatially supported time from retained
location history. It does not claim stationary time, dwell, a reconstructed route, or certainty that
the user occupied the hottest point.

The user-facing explanation is deliberately small:

- Warmer areas indicate more time supported near that area.
- Broader areas mean the recorded location was less precise.
- Unmapped time is not shown.

## Canonical analytical quantity

For a selected half-open instant range, canonical tracked time is the union of valid tracker-active
spans intersecting that range. The estimator partitions that union into disjoint half-open integer
millisecond intervals. Every interval is exactly one of:

- spatially supported by one normalized compact kernel; or
- unresolved and therefore not painted.

The required invariant is exact:

`supportedMs + unresolvedMs == canonicalTrackedMs`

Observation count, zoom, viewport, grid resolution, pixels, colors, and cache state cannot change
canonical duration. Adding redundant observations cannot add time. Adding informative observations
may move time from unresolved to supported while leaving canonical time unchanged.

## V1 temporal safety

Each usable acquisition-time observation receives a bounded freshness window. That window is clipped
to tracker-active time, adjacent usable-observation midpoints, the selected range, and its clock
domain. A sparse gap beyond the freshness horizon stays unresolved. The estimator never draws a
line, inferred route, or swept corridor between fixes.

The freshness values are versioned model inputs and require calibration against recorded traces.

## Spatial safety

Kernel calibration and permission policy are separate from temporal assignment. The pure estimator
accepts a policy that either returns a normalized compact kernel or leaves evidence unresolved. This
prevents an uncalibrated fixed conversion of Android's reported accuracy, an arbitrary precise floor,
or a fixed approximate-permission radius from becoming hidden truth.

Same-time duplicates receive one temporal assignment. Spatially conflicting same-time alternatives
remain unresolved in V1. Mock evidence is never painted.

## Persistence and rendering

The first-release target is raw-only and on demand:

- no proactive heatmap compaction or backfill;
- no new persistent grid, contribution, generation, or checkpoint data;
- no persistent render cache;
- a bounded memory cache may be added after source-version invalidation exists;
- the final renderer should evaluate the original kernels into a world-aligned scalar raster and
  present it with a MapLibre image/raster layer, without a second heatmap blur.

Existing derived tables are retained but dormant until an audit proves whether any of them contain
history whose raw evidence is already gone. They must not be dropped as part of the first slice.

## Known repository evidence gap

`location_observation` retains acquisition elapsed time, receipt time, provider, accuracy, permission
precision, mock state, and a stable row ID. `tracker_run`, however, currently stores mutable wall-clock
start/end values and no durable boot/clock-domain identity or elapsed-time bounds. Therefore the Room
adapter cannot yet claim correct reconstruction across reboot or wall-clock changes. The pure
estimator requires a canonical timeline and explicit clock-domain IDs so this limitation is visible
rather than silently bridged.

## Retention consequence

With the raw-only design, heatmap history disappears when its raw location and tracker-state evidence
is deleted. Retaining a frozen historical summary would be a separate, explicit product decision and
would require a versioned interval/kernel ledger; it must not be introduced as an invisible cache.
