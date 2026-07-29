# Research prompt 06: cadence-independent stop, trip, and transport classification

You are a principal researcher in mobility inference, irregularly sampled time series, online change
point detection, stop/trip segmentation, transport-mode classification, GNSS uncertainty, and mobile
sensor evaluation. You have internet access and no local checkout. Inspect Tracker through the
immutable GitHub links below.

Develop a design and validation contract for making Tracker's live stop/trip segmentation and
transport-mode classification stable across different sampling cadences and gaps. The answer must
support an offline/local implementation and must distinguish what can be achieved with rules from
what would require a trained model and labeled data.

## Immutable repository target and required entry points

Review exactly commit `fb2684d53ea2900780feb3438e736fbcd1b26c3f`:

- [Commit](https://github.com/adsamcik/Tracker-Android/commit/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Tree](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [`SessionSegmentDetector`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/segment/SessionSegmentDetector.kt)
- [`SegmentDetectorConfig`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/segment/SegmentDetectorConfig.kt)
- [`TransportModeClassifier`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/segment/TransportModeClassifier.kt)
- [`SegmentDetectorProcessor`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/processor/SegmentDetectorProcessor.kt)
- [Segment API signals/events/states](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/api/src/commonMain/kotlin/com/adsamcik/tracker/stats/api)
- [`SessionSegmentDetectorTest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonTest/kotlin/com/adsamcik/tracker/stats/engine/segment/SessionSegmentDetectorTest.kt)
- [`TransportModeClassifierTest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonTest/kotlin/com/adsamcik/tracker/stats/engine/segment/TransportModeClassifierTest.kt)
- [`StreamingAggregator`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/aggregator/StreamingAggregator.kt)
- [`RouteCompressor`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/compression/RouteCompressor.kt), whose production reachability must be proven before it enters scope
- [Debug GPX fixtures](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f/app/src/debug/assets/test_tracks)

Trace timestamps, per-cycle evidence, state transitions, counters/votes, checkpoint serialization,
domain events, downstream consumers, and tests. Determine actual production reachability of route
compression instead of trusting its presence. Code findings must link tight line ranges, show the
cadence/failure path, assign `P0`–`P3`, and name a replay/property test.

## Evidence standard

Browse and cite all significant claims inline. Prefer peer-reviewed mobility/trajectory papers,
official dataset papers and licenses, official Android location/activity/step semantics, and primary
algorithm sources. For every reported method or threshold, give dataset, modes, geography, device,
sampling distribution, features, ground truth, and metrics. Explicitly discuss transferability.

Do not equate performance on dense taxi GPS with performance on adaptive phone sampling. Do not quote
one stop-radius or dwell threshold as universal. Separate external fact, commit-pinned repository
fact, inference, recommendation, and values requiring Tracker-specific labeled traces.

## Verified Tracker dossier

The dossier was verified locally at the target commit, but it is orientation rather than a substitute
for review. Confirm each relevant claim from the linked code and report any discrepancy.

- Tracker is a local-first Android app and must perform live segmentation offline with bounded memory.
- Tracking cadence varies by battery/policy tier, user settings, sensor availability, permission, OS
  batching, and movement confidence. Gaps and bursts are normal evidence states, not exceptional input.
- Relevant evidence can include monotonic sample time, wall time, optional coordinates and horizontal
  accuracy, speed and speed accuracy, distance deltas, step deltas/rate, Android activity-recognition
  hints/confidence, and tracking lifecycle/policy state.
- Recent work fixed double-counting of the departure-confirming signal's steps and distance.
- The streaming session detector uses duration-based departure/stop confirmation and persists/restores
  state. Existing duration confirmation mitigates some cadence sensitivity.
- Stop and movement evidence is nevertheless aggregated largely per arriving sample. A different
  number/distribution of samples over the same physical interval can change accumulated evidence and
  classification timing.
- At trip completion, a rule-based transport classifier uses aggregate average/max speed, total
  distance, total steps, and duration to classify modes such as walking, cycling, driving, or unknown.
- Other specialized ski/plane/sailing detectors exist but are not the target of this prompt.
- A Douglas-Peucker/route-compression/polyline-encoding pipeline is almost entirely dead or unwired.
  Do not make its latent numerical defects part of the production redesign unless a real caller is
  first established.
- The repository contains a few synthetic/debug GPX tracks, but no established labeled corpus spanning
  devices, modes, sampling policies, and irregular cadence.

## Required investigation

### A. Define cadence independence precisely

Create formal invariants for the same underlying physical journey observed at different cadences:

- resampling more densely without adding information must not create extra distance, steps, stops, or
  mode transitions;
- thinning within declared evidence limits should preserve segmentation within a bounded timing error;
- duplicate and burst-delivered observations must not add temporal weight;
- sample gaps must become unknown/unresolved intervals rather than positive stop or movement evidence;
- timing must use a monotonic clock within a boot/session identity;
- evidence intervals may contribute duration once, not once per sample;
- uncertainty must influence confidence/eligibility, not be silently treated as precise geometry;
- online and batch replay should agree within a stated latency/edge-boundary contract;
- missing sensors must degrade confidence or yield unknown, not be interpreted as negative evidence.

Define which invariants are achievable and where information theory makes the journey genuinely
ambiguous. Recommend an explicit `UNKNOWN`/unresolved state where appropriate.

### B. Compare temporal evidence models

Evaluate alternatives for irregular samples:

1. interval ownership using previous/next/midpoint support with maximum-gap limits;
2. time-weighted feature integration over piecewise-constant or interpolated intervals;
3. fixed-time resampling with masks and uncertainty;
4. online state machines with dwell timers but cadence-independent evidence rates;
5. Bayesian/HMM/semi-Markov/change-point approaches with explicit duration and missingness;
6. another method supported by research.

For each, analyze streaming latency, causality, revision of recent decisions, memory, process-death
checkpointing, gaps, out-of-order/batched fixes, noisy coordinates, adaptive cadence, and explainability.
Do not recommend a machine-learned model without a credible on-device size/runtime and labeled-data
plan.

### C. Design stop/departure segmentation

Research robust use of speed, displacement, accuracy, steps, activity recognition, and time. Address:

- stationary GNSS drift and urban canyon jumps;
- traffic lights and short pauses versus trip termination;
- slow walking, indoor motion, tunnels, and loss of fixes;
- a single confirming departure/stop observation;
- oscillation near thresholds and hysteresis;
- maximum gaps and late batches;
- process death/replay without double counting;
- open-ended active sessions;
- cadence-dependent detection latency versus true physical transition time.

Compare geometric stay-point methods, probabilistic state models, and a minimal time-weighted rule
system. Specify which evidence owns the interval between samples and how uncertain boundaries are
represented.

### D. Design transport-mode features

For walking, running if relevant, cycling, motor vehicle, rail, water, and unknown, investigate which
features remain meaningful under cadence changes:

- distance over duration rather than mean of sample speeds;
- robust speed distributions and time-above-threshold rather than sample counts;
- acceleration/turning features under irregular time;
- steps per valid time and step-counter batching/reset behavior;
- Android activity recognition as evidence with source confidence, not ground truth;
- road/rail/water context only if available offline and uncertainty-aware;
- altitude/barometer evidence only if independently trustworthy;
- trip duration/distance priors and transition constraints.

Identify confounders: slow driving versus cycling, ferry versus stationary/vehicle, rail versus road,
phone carried versus mounted, missing steps, e-bike, and mixed-mode trips. Recommend when a whole-trip
single label is insufficient and how leg segmentation could be staged without scope explosion.

### E. Define the dataset and resampling experiment

Review public mobility-mode and stop-detection datasets, licenses, raw sensors, cadences, geography,
device placement, and label quality. State which can validate algorithm mechanics and which cannot
represent Tracker.

Design a Tracker-specific privacy-safe collection protocol:

- routes/scenarios per mode and mixed-mode transitions;
- multiple Android devices and carrying positions;
- exact tracking-policy/cadence metadata;
- manual transition markers and ground truth;
- urban, rural, indoor/outdoor, tunnel, congestion, stops, and sparse coverage;
- repeated routes and independent participants if available;
- train/tune/test separation by person and route.

From each highest-quality raw trace, derive controlled variants: original, regular 1/2/5/10/30/60 s,
jittered, burst-batched, random missingness, long gaps, duplicates, out-of-order delivery, accuracy
degradation, and policy transitions. Preserve the physical ground truth while changing observation
cadence.

### F. Specify metrics and acceptance

Use boundary-aware segmentation metrics, stop/trip precision/recall, transition latency, duration and
distance conservation, mode confusion matrices/macro-F1 or justified alternatives, calibration and
abstention coverage, and disagreement across resampled variants. Report per scenario and cadence, not
only aggregate accuracy.

Define a cadence-sensitivity metric, for example variation in inferred trip boundaries, duration,
distance, and class across resamplings of the same source trace. Give acceptance criteria as a process
for selecting thresholds from data, not invented numeric promises.

Include runtime, memory, checkpoint size, battery-sensitive computation, and deterministic replay.

### G. Prepare an incremental implementation strategy

Recommend the smallest first step likely to improve invariance without requiring a trained model.
Describe a shadow/replay evaluation mode that can compare old and new classifications without changing
user-visible sessions. Explain versioning, checkpoint compatibility, observability, rollback, and how
to avoid recomputing or rewriting historical data prematurely.

Explicitly exclude the dead route-compression pipeline unless research finds a necessary production
dependency stated in the dossier—which it currently does not.

## Required output

Return one Markdown report:

1. **Recommended direction** — minimal rules, probabilistic model, or staged hybrid, with evidence.
2. **Cadence-independence contract** — formal invariants and unavoidable ambiguities.
3. **Literature applicability table** — method, data, cadence, metrics, transfer limits.
4. **Temporal evidence model comparison.**
5. **Stop/departure design** — states, interval ownership, gaps, uncertainty, lifecycle.
6. **Transport feature contract** — time-weighted features, missingness, confidence, confounders.
7. **Public dataset assessment** — licenses and appropriate uses.
8. **Tracker trace protocol and controlled resampling matrix.**
9. **Metrics and acceptance process** — including cadence-sensitivity measures.
10. **Incremental rollout/replay plan** — versions, shadow results, checkpoints, rollback.
11. **Tests** — synthetic properties, replay cases, process death, out-of-order input.
12. **Open product/local questions.**
13. **Evidence ledger** — source authority, version/date, claim, limitations.

End with **Explicit non-goals**, including hardening unwired polyline compression, inventing travel
through gaps, and deploying uncalibrated ML thresholds.
