# Offline historical location reconstruction

Status: implemented V1 foundation and robust offline reconstruction path.

## Scope

The implementation reconstructs a completed tracking interval entirely on device from immutable
captured evidence. It does not change live tracking decisions and does not upload evidence.

The V1 pipeline is:

1. capture source and receipt timing, sequence, callback, clock-domain, boot-domain, permission, and
   sensor capability metadata;
2. derive a completed session from durable lifecycle `START`/`STOP` evidence, then read the
   clock-domain and monotonic-time-bounded evidence snapshot from Room;
3. classify observation health and convert quality problems into explicit covariance inflation;
4. run a robust local-tangent-plane constant-velocity Kalman filter;
5. run a full Rauch–Tung–Striebel backward smoothing pass;
6. derive uncertainty-aware, duration-based visit intervals;
7. atomically publish a versioned immutable reconstruction run and its source lineage.

Weak observations are retained with lower weight. Invalid coordinates, mocks, impossible accuracy,
non-monotonic samples, frozen/duplicate fixes, source age, batching correlation, and approximate
permission are represented as health/reason codes rather than being silently discarded or treated
as equally precise.

## Evidence semantics

Provider motion and curated motion are separate:

- raw provider speed, speed accuracy, bearing, and bearing accuracy are retained as measurements;
- the live curated speed estimate is stored separately and never written back into Android's
  `Location` object;
- activity, step, cell, Wi-Fi, and pressure evidence carry a common optional observation stamp;
- step and pressure windows retain their first/last monotonic timestamps and sequence bounds;
- pressure windows retain count, minimum, maximum, and standard deviation rather than only a mean.

Elapsed time is meaningful only inside its recorded clock and boot domains. Wall time remains an
interchange/indexing value and is not used to silently bridge monotonic timelines across boots.
Policy changes do not split reconstruction: tracker-run rows are policy segments, while lifecycle
evidence defines the complete session. Step intervals and activity snapshots are associated by
bounded monotonic-time proximity in the same clock/boot domain, never by unrelated row timestamps
or signal IDs.

## Persistence and publication

Schema 40 is unreleased, so the additions are part of the existing v39→v40 migration:

- raw motion, bearing, and boot-domain columns on location evidence;
- reusable observation-stamp columns on activity, step, cell, Wi-Fi, and pressure evidence;
- pressure aggregate/window columns;
- `trajectory_reconstruction_run`;
- filtered and smoothed `trajectory_state` rows;
- `trajectory_source_link` location, step, and activity source IDs plus weights, health, and reason
  codes (`state_index = -1` records assessed evidence that produced no derived state);
- future-facing `route_hypothesis` rows;
- uncertainty-bearing `visit_interval` rows.

The runner reads a source revision and publishes only if that revision is unchanged. A completed run
is inserted with all states, links, visits, and supersession metadata in one Room transaction, so
readers never observe a partially published reconstruction.

Raw-evidence retention deletes any reconstruction whose full source range would no longer remain
auditable. Room cascades that deletion to derived states, visits, hypotheses, and lineage links
before the source rows are pruned.

## Scheduling and resource policy

Reconstruction is scheduled after a successful tracker teardown, not during live tracking. The
unique WorkManager job requires battery-not-low and storage-not-low, and waits for the durable signal
write-ahead queue to drain. It processes every completed session missing the current algorithm and
configuration version, oldest first, so replaced/delayed work cannot skip a session. A source
mutation during processing causes a retry instead of publishing stale output. Deleting collected
data cancels the job and removes reconstruction rows through the database deletion path.

## Privacy branches

Precise permission uses calibrated observation uncertainty.

Approximate permission is a separate conservative branch. It quantizes published coordinates,
removes velocity detail, and enforces a 500-metre one-sigma covariance floor. It does not create route
hypotheses or sharpen approximate evidence into a precise inferred trail.

## Deliberately gated follow-ups

The schema and contracts allow later algorithms, but V1 does not pretend that unavailable evidence
exists:

- no inertial ESKF until raw, timed accelerometer/gyroscope evidence and calibration are retained;
- no IMM mode bank until traces demonstrate a measurable advantage over the robust baseline;
- no factor graph until constraints and device/runtime budgets justify it;
- no map-matched route publication until the local map importer exposes versioned topology and
  candidate quality gates;
- no pressure-to-floor or elevation claims without device-specific calibration and a weather/drift
  model.

These are evidence-gated upgrades, not missing steps in the V1 execution path.
