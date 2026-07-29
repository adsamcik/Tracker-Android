# Research evidence schema v2

`stats:api` owns the common, typed research-evidence contract. Version 2 remains an opt-in
research/replay boundary: it does not authorize ordinary telemetry, upload, a plaintext file,
database capture, or a collection UI. Any live capture must be explicitly consented, bounded and
encrypted by the caller.

Version 1 remains supported for historical altitude and segmentation evidence. New envelopes use
version 2 when they contain any control, logical-lifecycle, acquisition, horizontal-estimator or
replay-digest record. `ResearchEvidenceCodec` round-trips both versions and rejects unsupported
versions rather than silently degrading a V2 record.

Every `ResearchEvidenceEnvelope` carries a trace/run/session identity, a trace-scoped monotonic
sequence, declared clock domain, privacy class, algorithm versions, capability declaration,
lifecycle boundary, loss ranges, and one typed record. A final envelope may additionally carry a
`ResearchTerminalIntegrityRecord`. Its envelope count, first/last sequence, loss-range count and
saturated lost-event count make accounting explicit; an absent terminal record never means that a
trace is complete.

Loss ranges are inclusive trace-sequence intervals. They must be disjoint, and a bounded recorder
must declare every skipped envelope sequence. Raw pressure source sequences remain separate from
aggregate, location, and control sequence namespaces.

## V2 control evidence

V2 adds these Android-free record types:

- `ResearchControlEventRecord` for an ordered reducer input or named transition with correlation
  metadata;
- `ResearchTrackingLifecycleRecord` for a logical tracking run, independent of Android service
  restarts. A stopped user run must declare `USER_REQUEST` as its cause;
- `ResearchAcquisitionRecord` for both desired and actually applied provider request shapes;
- `ResearchHorizontalEstimatorRecord` for coordinate-free local-ENU estimator diagnostics;
- V2-owned fields on `ResearchGapRecord` so a logical run and monotonic bounds can own an explicit
  unknown interval;
- `ResearchControlDigestRecord` for replay output and retained V1-regression hashes.

Control timestamps are only comparable within the envelope's declared `ResearchClockDomain`.
Clock-domain changes and gaps must be represented as boundaries; no replay is allowed to manufacture
distance, stationary time, or a direct route across them. A generic event's payload is a small
string map and must never be treated as an unbounded raw-coordinate transport.

`ResearchControlTraceReplay` deterministically validates ordered V2 envelopes, lifecycle
transitions, user-run stop authority, gaps and digest material without selecting live Android
policy. It is a verifier, not a production controller.

## Capture and export boundary

The tracker-engine `ResearchControlTraceRecorder` is an opt-in bounded in-memory adapter from the
shadow control engine to V2 envelopes. It is not wired by default, writes nowhere, records buffer
overflow as a loss range, and intentionally emits no terminal-complete claim. A debug caller that
exports its snapshot must use an authenticated encryption stream (for example the existing
debug-only `EncryptedResearchTraceExporter`) and preserve the V2 envelope sequence/loss metadata.

The older debug authenticated research-trace exporter remains a historical v3 NDJSON manifest. It
declares the capabilities it actually has and marks ordinary database exports incomplete; it is not
a substitute for a live V2 control capture.
