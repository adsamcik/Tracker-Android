# Shared saved moving average: V1 review finding

Review only. IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All seven authored methods NOT_RUN.
Exact input is recorded in V1_INPUTS.json. Thirteen operations (six production updates, five
existing test updates and two unused feature-local deletions) and nine dependency pins were
source-inspected. Current disjoint source commits do not change these inputs.

## Finding: preserve the finite reader's existing non-finite failure result

The selected SavedLocationSummaryReader.result at lines 110-116 filters non-finite accumulated
distance to null, but passes movingAverageSpeedMetersPerSecond without the same condition.
The new SavedLocationSummary invariant at lines 47-48 requires a non-null average to have a
non-null distance. The accumulator getter at lines 207-211 can return zero or the prior ratio
when accumulated distance is non-finite. Therefore the already-existing explicit non-finite
failure branch at lines 156-158 now throws while constructing its partial snapshot. Its
RuntimeException catch attempts the same invalid construction again; the outer reader can only
return Unavailable, losing the otherwise available points and failure context.

This is a source-proven handler/invariant mismatch, not a claimed executed numerical or provider
reproduction. The shared existing Location.distance uses an unclamped Haversine expression,
and the reader already explicitly handles non-finite distances. No numerical probes ran.

Smallest repair: calculate the existing finite distance once in result and supply the average only
when that distance is valid; keep the existing partial/problem and cancellation behavior. Do not
weaken the API invariant, add a new calculator or broaden acquisition/continuity rules. Author a
focused reachable failure-source case through an existing seam if available. Recheck the revised
exact selection before acceptance.

## Other inspected behavior

- The original distance accumulator retains its continuity ID, next sequence, same clock, strict
  increasing elapsed/wall time and 120-second gap rules. Moving distance and duration are constant
  memory and included in the existing temporary snapshot/resume state.
- Native pairs use elapsed time; compatibility pairs use their existing wall-time continuity.
  Identical positions exclude stationary elapsed time; a measured stationary pair is zero, while
  no pair remains absent. Rejected points and gaps are never bridges.
- Finite pages and live seed/append/rebuild, authority/epoch/deletion qualification and cancellation
  handling remain shared. The slice adds no observer, persistence, provider request or per-fix
  growing-session scan.
- The nullable API field is appended with a default; existing source callers and Hilt wiring remain
  compatible. Tracker's existing saved-summary collector displays this field with the same
  localized Partial/Waiting/Unavailable formatting, preserving a genuine zero.
- The superseded feature-local PathMetricsAccumulator has no actual production callers; its class
  and own test are the only references. Other tracking/map/numerical consumers are not removed.
- Three accumulator methods, one qualified Room finite method, one qualified Room live method,
  one formatter method and one Compose method use the actual bounded contracts claimed. Existing
  Room grant/provider setup is explicitly seeded; Compose injects the shared snapshot. The pure
  reversed-wall method does not prove wall-clock handling through ordered Room pages.

No other concrete blocker found in this bounded slice. Operation/provider/Android timing, all
execution gates and the wider numerical/annotation/calendar/source architecture remain outside
this acceptance. All 25 full-chain prerequisites and T25 remain open. V1 is held for the finding
above; this document does not declare implementation completion or integrated readiness.
