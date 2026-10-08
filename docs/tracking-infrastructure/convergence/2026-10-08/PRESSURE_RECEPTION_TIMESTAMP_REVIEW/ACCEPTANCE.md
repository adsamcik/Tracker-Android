# Pressure reception timestamp: source acceptance

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Independent source review only. Both new methods NOT_RUN. No compilation, tests, Room, schema, lint, provider, hardware, CI or Git mutations. T25 and all 25 full-chain prerequisites remain open.

## Exact accepted input

Base/reviewed committed HEAD `3a3476e63e5c3bc40e2697f5e503a94cb18592ed`; packet `PRESSURE_RECEPTION_TIMESTAMP_SELECTED`.

- FILES `8e5814428c3f7bd64643c6239cd5021d5488be985de501f5121498fa0bafdab4`
- patch `da21a82e0eaafc64e1c9aaf0e580ca66831c14370b83f7dd2038685bccf53c68`
- dependencies `664675cc09e6b9d9628a8909b86f5d41d11bb1c4427d37565a7cfdbddbb730f9`

Both selected UTF-8 LF source files, BASE and raw WORKING_BEFORE match. All15 saved/committed/current dependency pins match. This is static source identity checking, not executable validation.

## Actual production trace

The sole production change at PressureSourceRuntime.kt:1398 copies the completed window's already captured `PressureReception.receivedWallTimeMs` into the existing SourceEvidenceCandidate field. It does not synthesize a later timestamp, use close/queue time as the measured timestamp, or add storage/format/API/demand behavior.

Original reception metadata is captured at the sensor callback. A target-elapsed completion uses the previous window's lastAccumulatedReception (:1591-1598), and boundary/close completion similarly carries that stored reception. prepareWindowAdmission derives acquired/measured wall time from this original reception and the sensor measurement's elapsed delay (:1375-1379), while the existing checkpointOrderElapsedRealtimeNanos remains the independent causal checkpoint/queue ordering value. The new field does not change provider window identity, observation times, payload, optional Pressure metrics, authorization or admission cutoff rules.

The pinned unchanged DurableSourceIngress.kt:1564-1567 writes the candidate's existing elapsed/wall receipt fields into the WAL. The pinned retained PressureFactProofAuthenticator.kt:297-300 requires a wall receipt and keeps its existing exact action/member/clock and measured-data validation; prior null candidate receipt could not satisfy that contract. Other chronological restrictions and full production-chain composition remain the disclosed separate boundary, not silently relaxed here.

## Meaningful fixture reach and limits

The two new sources invoke the actual runtime SensorEventListener, accumulator, completed-window actor, candidate construction and existing atomic recording-sink submission. One supplies a sensor measurement six seconds older than reception, advances elapsed time three seconds before close, and checks original wall reception and measured time/delivery identity. The second reaches actual TARGET_ELAPSED completion with two prior samples and a later triggering sample, and checks the prior window's last reception/observed end/count.

Both use existing mocked SensorManager/registration authority and a RecordingPressureSink that returns fixture admission; close in finally contains runtime resources. Existing methods remain unchanged. They do not execute Room, projection, retained reader, actual Android pressure hardware, provider battery/lifecycle recovery, or prove that every later strict chronology path accepts. These boundaries agree with SOURCE_BOUNDARY and CASE_INVENTORY; a recording sink is not storage proof.

## Disposition

Accepted for this bounded existing-field wiring and reached runtime test-source scope. No concrete source blocker or unapproved design change found. It is not full Pressure provider-to-product proof or feature readiness.
