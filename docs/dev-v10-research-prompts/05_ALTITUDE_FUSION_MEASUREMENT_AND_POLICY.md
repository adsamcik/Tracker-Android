# Research prompt 05: altitude fusion measurement and policy

You are a principal researcher in Android sensors, GNSS altitude, atmospheric pressure, geodesy,
robust state estimation, time-series experiments, and mobile data quality. You have internet access
and no local checkout. Inspect Tracker through the immutable GitHub links below.

Tracker recently fixed several clear altitude defects. The remaining questions require measurement.
Design the instrumentation contract, field study, and evidence-based decision framework needed before
changing pressure aggregation, datum handling, outlier rejection, recalibration, or fusion-state
persistence. Do not prematurely tune a Kalman filter from literature defaults.

## Immutable repository target and required entry points

Review exactly commit `fb2684d53ea2900780feb3438e736fbcd1b26c3f`:

- [Commit](https://github.com/adsamcik/Tracker-Android/commit/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Tree](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [`BarometerDataProducer`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/component/producer/BarometerDataProducer.kt) and [tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/test/java/com/adsamcik/tracker/tracker/component/producer/BarometerDataProducerTest.kt)
- [`TrackingCycle`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/data/collection/TrackingCycle.kt) and [`TrackingCycleBuilder`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/data/collection/TrackingCycleBuilder.kt)
- [`PressureSample`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/PressureSample.kt) and [`PressureSampleDao`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/PressureSampleDao.kt)
- [`AltitudeProcessor`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/altitude/AltitudeProcessor.kt)
- [`AltitudeFusionEngine`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/altitude/AltitudeFusionEngine.kt) and [tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/test/java/com/adsamcik/tracker/tracker/altitude/AltitudeFusionEngineTest.kt)
- [`AltitudeKalmanFilter`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/altitude/AltitudeKalmanFilter.kt) and [tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/test/java/com/adsamcik/tracker/tracker/altitude/AltitudeKalmanFilterTest.kt)
- [`GeoidAltitudeConverter`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/altitude/GeoidAltitudeConverter.kt)
- [Altitude integration in `LocationTrackerComponent`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/component/consumer/data/LocationTrackerComponent.kt)
- [`ResearchTracePayloadExporter`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/feature/import-export/src/debug/java/com/adsamcik/tracker/impexp/exporter/research/ResearchTracePayloadExporter.kt), [encryption wrapper](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/feature/import-export/src/debug/java/com/adsamcik/tracker/impexp/exporter/research/EncryptedResearchTraceExporter.kt), and [offline decoder contract](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/tools/research-trace/README.md)

Trace raw pressure from `SensorEvent` through collection, persistence, GPS conversion, calibration,
filter update, and displayed/persisted altitude. Verify reset/lifecycle behavior and actual trace fields.
Code findings must link tight line ranges, show reachability, assign `P0`–`P3`, and name the replay,
unit, or device experiment that could prove a correction.

## Evidence requirements

Browse and cite sources inline. Use:

1. Official Android `Sensor`, `SensorEvent`, pressure-sensor, timestamp, batching, accuracy, and power
   documentation/source where needed.
2. Official Android/AndroidX location and MSL-altitude conversion documentation/source applicable to
   API 26–37.
3. GNSS and barometric-altimetry standards or peer-reviewed research.
4. Primary robust-filtering/state-estimation literature.
5. Device/vendor documentation only for explicitly identified device behavior.

For every paper or dataset, give sensors, device class, environment, sampling rate, ground truth,
error metric, and limits on applying it to commodity Android phones. Treat values such as sensor
noise, weather drift, innovation gates, process noise, and calibration interval as hypotheses unless
the evidence directly matches Tracker's environment.

Separate external fact, commit-pinned repository fact, inference, proposed experiment, and open
product decision. Do not claim physical-device measurements you did not perform. Privacy and
research-data handling are part of the design, not an appendix.

## Verified Tracker dossier

The dossier was verified locally at the target commit, but it is orientation rather than a substitute
for review. Confirm each relevant claim from the linked code and report any discrepancy.

- Local-first Android app, API 26 minimum, API 37 target; tracking can run with the screen off.
- A pressure `SensorEventListener` registers at `SENSOR_DELAY_NORMAL`.
- The current producer validates pressure, then accumulates only `sum` and `count` between Tracker
  collection cycles. On request it emits one arithmetic mean and discards individual event timestamps,
  ordering, variance, min/max, and gap structure.
- A persisted pressure sample represents that cycle-level reading and has wall-clock milliseconds,
  elapsed-realtime nanoseconds, pressure hPa, and a standard-atmosphere derived altitude.
- Tracking collection cadence varies with policy and user settings, so one emitted mean may represent
  different numbers and spans of raw sensor events.
- The fusion path uses monotonic elapsed time for filter timing.
- Fusion is a one-dimensional Kalman-style altitude/vertical-velocity estimator.
- GPS altitude is converted toward mean sea level through AndroidX. If conversion is unavailable or
  throws, the current converter silently returns raw ellipsoidal `Location.altitude`; downstream data
  has no datum-status flag.
- GPS measurement variance uses reported vertical accuracy squared when valid, otherwise a default
  equivalent to 15 m standard deviation.
- Barometer measurement variance is currently a fixed value equivalent to 1 m standard deviation.
- Barometer sea-level pressure is calibrated from a simultaneous GPS MSL altitude and pressure and is
  recalibrated on a five-minute interval.
- A recent fix prevents the same pressure from being used both to derive the GPS-calibrated sea-level
  pressure and immediately as an independent barometer update in that calibration cycle.
- No explicit innovation/outlier rejection exists. One bad GPS altitude can influence barometer
  calibration until the next recalibration.
- Filter and calibration state are not persisted. Process restart or re-enabling the feature requires
  reacquisition.
- Elevation gain/loss accumulation was recently fixed so gradual climbs are not lost merely because
  individual samples are below a one-metre threshold.
- A debug-only encrypted research-trace exporter exists. It records raw provider location observations,
  accepted/fused location samples, run boundaries, wall and elapsed clocks, and manual markers. It does
  **not** currently record individual pressure events, cycle variance, datum-conversion outcome,
  calibration events, innovations, covariances, rejected measurements, or filter state.

## Required research and design

### A. Establish platform semantics

Verify and explain:

- what `SensorEvent.timestamp` means, its clock domain, precision, reboot behavior, and relationship to
  `elapsedRealtimeNanos` across supported Android versions;
- delivery versus event time, batching/FIFO behavior, duplicate or delayed events, accuracy callbacks,
  and whether `SENSOR_DELAY_NORMAL` implies any guaranteed rate;
- pressure sensor units, valid range, accuracy/status meaning, temperature compensation expectations,
  and vendor variability;
- Android `Location` altitude datum, MSL altitude APIs/conversion, vertical accuracy semantics, and
  failure modes across API 26–37;
- whether conversion fallback to ellipsoidal altitude can be reliably detected and represented;
- lifecycle/process-death constraints relevant to sensor registration and state restoration.

Provide an API/version matrix. Do not assume API 34+ behavior on API 26.

### B. Define a research logging contract

Specify the minimum versioned event schema needed to answer the deferred questions. Include, when
available:

- trace/run/boot identity;
- wall time, raw sensor event time, receipt elapsed time, and collection-cycle boundaries;
- raw pressure, sensor accuracy/status, sensor vendor/name/version/resolution/power/min/max delay and
  FIFO metadata;
- every raw GPS/other provider altitude, coordinate, horizontal/vertical accuracy, speed, provider,
  mock flag, delivery age, and acquisition mode;
- MSL conversion result, original datum, output datum, fallback reason, and geoid model/API path;
- cycle aggregation count/span/mean/variance/min/max and the exact aggregate passed to fusion;
- filter prediction state/covariance, measurement noise, GPS and barometer innovations, normalized
  innovations, accepted/rejected decisions, calibration inputs/output, and state after update;
- process/session lifecycle and resets;
- manual/external truth markers and synchronization uncertainty;
- algorithm/configuration version.

Recommend which data should remain debug-only and ephemeral, which may reuse existing persisted
pressure rows, and which must never be included in production telemetry. Minimize precise-location
exposure and define encryption, retention, redaction, consent, transfer, and deletion expectations.

### C. Compare pressure-sample handling

Evaluate:

1. current per-cycle arithmetic mean;
2. latest valid event at/before fusion time;
3. timestamp-aware mean/median or robust aggregate over a bounded window;
4. sequential filter updates for each raw pressure event;
5. resampling/interpolation to a fixed time grid;
6. another evidence-supported method.

For each, analyze latency, smoothing, phase lag, cadence dependence, timestamp correctness, variance
estimation, CPU/power, burst delivery, gaps, and fast vertical motion such as stairs/elevators. Explain
how to avoid treating multiple highly correlated events as independent evidence and how uncertainty
should change with count/span rather than blindly shrink as `1/n`.

Do not choose until you state the field evidence that would discriminate the alternatives.

### D. Design robust calibration and outlier decisions

Research innovation gating, robust filters, adaptive noise, calibration quality gates, and barometric
drift handling relevant to a low-dimensional mobile altitude filter. Address:

- GPS altitude spikes and implausibly optimistic/missing vertical accuracy;
- pressure spikes, sensor discontinuities, elevator/HVAC effects, weather fronts, and indoor/outdoor
  transitions;
- correlation introduced when GPS calibrates the barometer;
- recalibration interval versus innovation/quality-triggered recalibration;
- protection from one bad calibration lasting minutes;
- gap/reacquisition semantics;
- datum mismatch detection;
- numeric stability and covariance floors/ceilings.

Give formulas and diagnostics, but express thresholds as quantities to estimate from traces. If you
propose a chi-square/NIS gate, state its assumptions and how they will be checked rather than copying a
confidence percentile mechanically.

### E. Decide how datum provenance should work

Compare:

- silently using ellipsoidal altitude when MSL conversion fails;
- returning no MSL altitude;
- retaining the numeric value with explicit datum/provenance and excluding it from incompatible
  calculations;
- estimating/converting later when geoid data becomes available.

Address display, gain/loss, calibration, export/import, persistence migration, and mixed-datum series.
Recommend a product/data contract and a backward-compatibility strategy. Explain which decision is
possible before field data and which requires measuring conversion failures.

### F. Evaluate fusion-state persistence

Define the state that would be required to restore the filter safely: state vector/covariance,
calibrated sea-level pressure, timestamps/clock identity, sensor identity, datum, configuration version,
and environment staleness. Compare no persistence, short in-process pause, encrypted process-death
checkpoint, and session-bound persistence. Account for reboot resetting monotonic clocks, weather
change, changed device/sensor, app upgrade, and long downtime.

Recommend persistence only if a measurable reacquisition problem justifies it. Define restore
eligibility and mandatory fallback to cold start.

### G. Specify the field study

Design repeatable scenarios across at least two or three device/sensor classes:

- stationary indoor/outdoor over minutes and hours;
- level walking with GNSS drift;
- surveyed stairs and repeated elevator trips;
- gradual hill ascent/descent with independently measured elevation profile;
- cycling/driving with road-grade changes;
- tunnel/urban-canyon/poor-GNSS transitions;
- airplane/large pressure changes only if safely and practically collectable;
- screen-off/background collection and variable Tracker cadences;
- process restart, sensor disable/enable, reboot, and long pause;
- weather change where a reference station is available.

For each, define ground truth, synchronization, repetitions, exclusion rules, metadata, and metrics:
absolute error by datum, relative change, gain/loss, vertical-rate error, lag, overshoot, settling time,
calibration failures, NIS/innovation distribution, rejection precision/recall where labels exist, and
energy/CPU/storage. Include a pre-registered analysis split so thresholds are not tuned and evaluated
on the same traces.

## Required output

Return one self-contained Markdown report:

1. **Decision summary** — what can change now, what must wait for traces, and why.
2. **Android/platform semantics matrix** — API range, guarantee, source, implication.
3. **Versioned research-event schema** — fields, units, clock, nullability, privacy class.
4. **Pressure handling comparison** — hypotheses and discriminating measurements.
5. **Calibration/outlier framework** — methods, assumptions, diagnostics, tuning protocol.
6. **Altitude datum contract** — product/data recommendation and migration impact.
7. **State-persistence decision framework** — restore state, staleness, rejection rules.
8. **Field-study protocol** — scenarios, ground truth, devices, repetitions, metrics.
9. **Analysis plan** — plots/statistics, train/tune/test split, acceptance gates.
10. **Privacy and secure-handling protocol.**
11. **Implementation-independent acceptance tests** — synthetic plus replay tests.
12. **Unknowns requiring local/device validation.**
13. **Evidence ledger** — source authority, version/date, supported claim, limitations.

End with **Do not tune from literature alone** and list every parameter that must remain provisional.
