# All-day Activity and Pressure

Date: 2026-10-22. Base: `a1837faa532bc8a9a25d14a83c3304c70be5961a`.
Delivery is the enclosing local `dev/v10` commit; no remote push.

## Behavior and ownership

- The existing Activity and Barometer choices enable sessionless recording. No
  extra switches or settings migration are required. Sessions use the same
  providers and temporarily prefer session quality; End leaves all-day recording
  active. Turn off tracking retires both purposes and preserves saved history.
- `AmbientSensorPurposeOwner` uses the existing five-level planner, motion,
  battery policy and publication/recovery flow. Activity uses the existing
  registration arbiter; `SharedPressureSourceController` owns one Pressure
  listener across both lifetimes. Pressure uses the existing foreground service.
- Automatic-start Activity evidence remains a separate, nonrecording purpose.
  It cannot populate Activity history unless recording was independently enabled.
- No database schema, dependency, network or telemetry changes are introduced.

## Saved data and product trace

- `AllDaySensorProjection` consumes the durable source events into the existing
  `ActivitySnapshot` and `PressureSample` tables. It preserves original identity,
  timestamp, Pressure measurement window, quality and incomplete coverage.
  Source-local checkpoints permit replay without pinning unrelated source logs.
- `RoomAllDaySourceStatusQuery` and the measured History readers use these native
  rows without requiring a session or the latest tracking choices. History adds
  exact optional session names and keeps unknown confidence/altitude unknown.
  Both native tables now invalidate the shared History reader.
- The existing Activity and Pressure file choices include native observations
  alongside optional V1 session data. V1 remains readable. Exports page the
  database into the existing private spool before writing the destination;
  overlapping imports preserve identity and add missing observations once.
- Session bundles select exact native observation IDs. Source erase, retention,
  Clear all and the existing data-only archive include these same tables. Existing
  deletion fences stop erased source epochs from reappearing through replay.

## Verification

The Dev APK, Kotlin/Java/Hilt wiring, Room generation and scoped Detekt pass.
Generated schema version 30 is unchanged. The APK is `com.adsamcik.tracker.dev`,
version `10.0.0-dev` (400), labelled **Advention Dev**, debuggable, target API 37.
APK SHA-256: `6CEF9D6144A38A304F3307431914FE2ACA58B3B1619B0A7270169038DC494845`.

**918 focused host tests pass, with zero failures, errors or skips.** Results
reconcile suites by module and class, replacing earlier failures with the final
passing rerun and reusing unchanged passing inputs:

| Area | Passing cases |
| --- | ---: |
| Database maintenance, retention and erasure | 116 |
| Settings and retention authority | 106 |
| Shared Activity arbiter | 61 |
| Tracking contracts and availability | 35 |
| Shared owners, ingress, projection and recovery | 325 |
| Statistics API | 3 |
| Native status, History and session organization | 105 |
| Source files and session bundles | 87 |
| History UI | 22 |
| Settings UI | 58 |

Batch 5 builds the final production APK; subsequent changes affect only four
test fixtures. Batch 6 repeats the engine and statistics groups; batch 7 passes
the final isolated 58-case registration class and scoped Detekt. Frozen hashes
for all 116 changed source/resource files match. Local raw logs, source hashes
and JUnit receipts are under `build/verification-20261022`. The reconciled receipt
SHA-256 is `01E063824875AC62F7181E7530394E722A17AC3B58A2531F8FAA433D864A1B0D`.

Tests use the original Android/JVM test classes, classpaths and configurations in
a temporary `verifyAllDayOnBuildJdk` task on JDK 21, preserving the established
native-SQLite host workaround. No production toolchain setting is changed.

Review and regression work corrected a Pressure quality-change continuity gap,
kept caller transport EOF distinct from malformed staged JSON, and covered exact
native broker admission without a session. Older strict mocks and Windows-long
database fixture filenames were repaired without relaxing production checks or
observable assertions. A review-only independent agent inspected the combined
flow, persistence, readers, transfers, cleanup and corrections; no static findings
remain.

This feature run does not claim a fresh complete `ciUnitTest`/`ciCheck` run or
real Activity/Pressure delivery, screen-off recovery, reboot or battery acceptance.
The existing API 37 emulator was in use by another project; it was not interrupted.
Hardware/provider and background battery checks remain practical device work.
