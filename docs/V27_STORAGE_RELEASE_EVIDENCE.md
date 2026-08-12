# V27 storage release evidence

> **Recorded:** 2026-08-12<br>
> **Scope:** Public v26 to v27 storage boundary<br>
> **Status:** V27 storage verification is complete. Automated, packaged-APK, and disposable-AVD evidence is recorded below; physical radio/provider behavior is optional follow-up.

This is the release-facing evidence record for the implementation plan in
[`V27_STORAGE_RELEASE_IMPLEMENTATION_PLAN.md`](V27_STORAGE_RELEASE_IMPLEMENTATION_PLAN.md).
It records what was actually run; an emulator or seeded fixture is never presented as real
phone sensor evidence.

## Frozen release contract

- Active database: `main_database_v27`, Room schema version 27.
- Legacy vault: `main_database`, preserved as the released v26 database until the user explicitly
  deletes it after a successful import or a successfully closed optional export.
- Generated schema identity: `41c2760812c5930200201761a25d3e81`.
- Generated schema: 51 tables, zero `legacy_*` tables or columns, and none of the 14 rejected
  tables.
- Mechanical registration audit: all 51 `AppDatabase` entity registrations resolve to exactly
  the same 51 generated schema tables, with no missing, duplicate, or unresolved entry.

## Complete v26 disposition matrix

| V26 table | V27 action |
|---|---|
| `activity` | Import user-defined activity metadata. |
| `network_operator` | Skip unread reference/cache data; cell samples retain MCC/MNC. |
| `location_sample` | Import canonical coordinates/altitude and synthesize `location_observation`. |
| `step_interval` | Import irreplaceable source history. |
| `activity_snapshot` | Import irreplaceable source history. |
| `cell_sample` | Import canonical source history and normalize stable source identity/item index. |
| `wifi_observation` | Import canonical source history and normalize stable source identity/item index. |
| `tracker_run` | Skip old runtime diagnostics. |
| `session_segment` | Import user-visible history using canonical primary activity. |
| `daily_summary` | Import compatible historical summaries directly. |
| `live_stats` | Reset source state; retain the fresh canonical v27 Room table. |
| `frequent_place` | Skip removed incomplete feature state. |
| `inferred_trip` | Skip removed incomplete feature state. |
| `trip_leg` | Skip removed incomplete feature state. |
| `exploration_cell` | Import earned exploration progress. |
| `exploration_streak` | Import earned exploration progress. |
| `achievement_progress` | Skip and report the incompatible v26 representation; retain it in the vault. |
| `personal_record` | Skip removed incomplete/derived feature state. |
| `route_cache` | Skip removed cache state. |
| `export_log` | Skip old operational history. |
| `storage_size_snapshot` | Skip removed diagnostic/chart state. |
| `domain_event` | Reset the superseded event stream. |
| `domain_event_cursor` | Reset cursors tied to the superseded event stream. |
| `pressure_sample` | Import irreplaceable source history. |
| `ski_run_segment` | Import user-visible feature history. |
| `pending_signal` | Skip and report transient unfinished work; retain it in the vault. |

The importer therefore copies 12 released source tables, derives one observation per imported
location, and reports intentionally retained-only rows without mutating the source.

## Automated verification

- `./gradlew.bat testDebugUnitTest --continue --no-daemon` completed successfully in 22m 44s
  across 961 actionable tasks; all current-tree `TEST-*.xml` reports contained zero failures or
  errors. The outer shell timed out before printing the final line, but the owned daemon recorded
  `BUILD SUCCESSFUL` and `Runtime.exit(0)`.
- After the final recovery, preference, and instrumentation-contract edits, the same command was
  rerun serially and completed successfully in 6m 34s across 961 tasks (54 executed, 907
  up-to-date). The shell wrapper again timed out early; the owned daemon recorded
  `BUILD SUCCESSFUL`, `Runtime.exit(0)`, and current-tree XML contained zero failure/error hits.
- `./gradlew.bat lintDebug --continue --no-daemon` was run. It remains red on unrelated existing
  localization/baseline debt; the storage-related findings were fixed and the focused app lint
  rerun contained no new legacy-vault localization finding.
- `LegacyV26ImportTest`: 9 tests passed, covering clean install, exact released-v26 columns,
  older-source normalization, atomic failure, deterministic identities, auto-increment,
  downgrade file isolation, 25,000 imported locations, and export/delete/restart.
- Focused tracker recovery tests passed for `SessionCrashRecoveryTest`,
  `PersistenceRecoveryCrossSessionTest`, and `DurableSignalBufferTest`.
- `PreviousExitRecoveryCoordinatorTest`, `ForceStopSourceSessionFinalizerTest`, and
  `TrackerServiceRedeliveryRecoveryTest` passed together. They cover main-process exit selection,
  non-destructive handling of ambiguous `USER_REQUESTED` exits, positive force-stop finalization,
  all-incomplete-run closure, durable-identity redelivery, and graceful-stop protection.
- `ApplicationStartupRecoveryTest` passed after startup ordering was changed to publish database
  readiness only after deletion/import and previous-exit reconciliation complete.

## Disposable API 30 APK boundary

Target: AVD `Tracker_API30_Validation`, serial `emulator-5556`, Android API 30, x86_64,
4,096-byte pages. The unrelated `emulator-5554` and all production package data were untouched.
The debug package on this dedicated validation AVD was cleared only between isolated scenarios,
never between the v26 install and its in-place v27 replacement.

Build identities:

- Historical v26 commit: `1fde5e26a71eb8668a8abd284c3aa2a3af9cdd38`.
- Historical debug APK SHA-256:
  `EA20E6B1323CB8E3DCE89255620EF9716054546676BD350E7326C1E5581A86FC`.
- Upgrade-test current debug APK SHA-256:
  `E338C918E379310959BA02C5DC4A7BC35F8E5363EC8FFB5CFC128F8AF0F00428`.
- Final recovery/large-import debug APK SHA-256:
  `D71FF252F1CB7DAD24ED39FE784F81D9F92EB3C6AEF00E7325215819505671C3`.
- Final locally verified debug APK SHA-256:
  `EF1512D8F01E5E58A7418BBD302E78ECE4FF95E0C3010833CBE37DB648B62C99`.

The upgrade boundary was exercised with the upgrade-test APK. Recovery and large-import evidence
below was rerun with the later recovery/large-import APK. The final APK differs only by bounded
persisted-preference cleanup and Android-test dependency/contract fixes; it passed the final unit
suite and 4/4 instrumentation smoke. The historical-to-current boundary is not misrepresented as
having used a later binary.

### Upgrade/import

The historical APK created `main_database` at user version 26 and seeded three sessions. Before
replacement it passed `quick_check` and contained 8 activities, 213 locations, 3 step intervals,
3 session segments, 1 daily summary, and 3 tracker runs. Its SHA-256 was
`1304005E1900A24EA7AA2B3456E03B2A164417A59C137D0710FDA30894FCCAFC`.

The current APK replaced it with `adb install -r -t`, without uninstalling or clearing data.
The first startup completed the durable receipt
`legacy-database-v26 | main_database:v26 | COMPLETE` and produced:

- imported: activity 8, location samples 213, derived location observations 213, step intervals 3,
  session segments 3, daily summaries 1;
- retained only in the vault: tracker runs 3;
- active database: user version 27, `quick_check=ok`, WAL mode;
- source vault: user version 26, `quick_check=ok`, unchanged source SHA-256.

The imported history rendered in the app as 6 km, 7,692 steps, and three seeded trips. A new
current session was then created, proving the imported database remained usable for new writes.

### Downgrade isolation

With both app processes stopped, the v27 file-family hashes were recorded before installing the
historical APK:

| File | SHA-256 before downgrade |
|---|---|
| `main_database_v27` | `45F40D78831CF89B0F96CD46E2A5052ECFA05478F8ADFAE5CE782CE5BDFDBCDC` |
| `main_database_v27-wal` | `EABC0102A48EEC66253BAC680D0D0902BC45377646219E555BCFF68CE7160ED2` |
| `main_database_v27-shm` | `B613EB5471767C2FDDB9F0318F6D58DD61D9A9B9C8BE41EEF061D46AAAE6D171` |

The v26 APK was installed in place with the same debug signature and data preserved, successfully
opened `main_database`, and seeded one additional session. The v26 source changed as expected to
248 locations and 4 steps/segments/tracker runs, while all three v27 hashes remained byte-for-byte
identical. Reinstalling the current APK retained the completed receipt and did not re-import the
newly added v26 rows.

## Manual vault lifecycle

The Data settings UI showed source version 26, 141 kB, import complete, 441 imported rows, and
3 retained-only rows. The vault was exported through Android's Storage Access Framework to
`tracker-legacy-v26.db` before deletion.

Independent validation of the exported file:

- size: 141,312 bytes;
- SHA-256: `7573AB9F67147B1CF174815A8FDFF4D7512C58BF4FC1CB05F4B0A498B82044E9`;
- `user_version=26`, `quick_check=ok`;
- 8 activities, 248 locations, 4 step intervals, 4 session segments, 1 daily summary, and
  4 tracker runs.

The strong confirmation explicitly stated that 141 kB would be permanently removed while v27
data would remain. After confirmation, the `main_database` family and legacy controls disappeared.
A cold restart retained the v27 database (`user_version=27`, `quick_check=ok`), the COMPLETE
receipt, 213 locations, 213 observations, 3 imported step intervals, and 4 total session segments.

## Recovery and large-fixture evidence

- With `ANDROID_SERIAL=emulator-5556`,
  `./gradlew.bat verifyReleaseSqliteRuntime :core:sqlite-runtime:connectedDebugAndroidTest --no-daemon`
  passed in 14 seconds across 112 tasks. `SQLiteXRuntimeRecoveryInstrumentedTest` passed 1/1 on
  Android 11/API 30 and verified SQLite 3.53.3/source identity, WAL mode, `quick_check=ok`, and
  recovery of a committed DB+WAL snapshot with the disposable SHM file omitted. An earlier attempt
  using Gradle's unsupported `--serial` option failed before tests; serial pinning through
  `ANDROID_SERIAL` is the passing command.
- A deliberate main-process `kill -9` produced `ApplicationExitInfo.REASON_SIGNALED`, status 9.
  The first run preserved database integrity and drained pending signals but exposed a stale
  source-session lifecycle gap. After the bounded recovery fix, the repeat retained logical session
  `e7779891-0e6a-4286-9eb9-0d7c5b34e1e8`, closed old service run
  `8bfc5aa9-...` with `PROCESS_DEATH_RECOVERY`, created distinct running service run
  `db420233-...`, and updated the durable descriptor to the new run. `pending_signal` was zero,
  duplicate non-null source identities were zero, `quick_check=ok`, and `foreign_key_check` was
  empty. An explicit stop then closed the logical/current-run state and removed the descriptor.
- A disposable canonical v26 copy contained 100,248 locations, remained `user_version=26`, and
  passed integrity and foreign-key checks. Its SHA-256 was
  `F1A2C1C0D9DA2E23F90142B487DC38AB358D3D1B97635C67C8DBD00F120DB887`; logical SQLite SHA3 was
  `a87856bf0a2d864fb125de8c564bb9b6561c668f8d1c5a3ea781314d`.
  The packaged APK completed import in 36 seconds with peak observed PSS 170,982 KiB and no crash,
  ANR, OOM, or import failure. The source byte/logical hashes were unchanged. The v27 target passed
  `quick_check` and `foreign_key_check`, contained 100,248 location samples and 100,248 synthesized
  observations, and recorded a COMPLETE `main_database:v26` receipt for the 15,252,480-byte source.

## Focused API 30 instrumentation

The app Android-test dependency graph originally packaged JVM-only JUnit 5 engines from
`core:testing`, which duplicated `META-INF/LICENSE.md` in the test APK. The dependency scopes were
corrected at the shared test library: Jupiter APIs used by main-source test helpers are
`compileOnly`, while Jupiter tests and engines are test-only. No packaging exclusion masks future
duplicates. The test runtime dependency check and Android-test resource merge then passed.

The instrumentation contract was also brought in line with current production UI: notification
permission is requested only on API 33+, top-level navigation uses `nav_dashboard`, and tracking
waits for the dashboard's EMPTY/IDLE/TRACKING controls instead of the removed tracker FAB. With
`ANDROID_SERIAL=emulator-5556`,
`./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.tracker.app.StandardFlowsTest --no-configuration-cache --no-daemon`
passed 4/4 in 46 seconds: tracking start/stop, map, statistics, and settings. No tracking service
remained after the focused tracking flow.

## Full 26-table prelaunch matrix check

The reproducible one-time command
`./tools/device-validation/run_full_v26_matrix_check.ps1` ran against the guarded disposable target
`emulator-5556` / `Tracker_API30_Validation` using final APK SHA-256
`EF1512D8F01E5E58A7418BBD302E78ECE4FF95E0C3010833CBE37DB648B62C99`.

The script created a database directly from the checked-in frozen v26 Room JSON, including every
table/index, Room identity, Android metadata, and `user_version`, then inserted one valid
deterministic sentinel into every one of the 26 released tables. Before device staging it verified
`user_version=26`, `quick_check=ok`, an empty foreign-key check, the exact released table set, and
one row per table. Fixture size was 344,064 bytes, its frozen Room identity was
`1fdc69e65166bbcf9b2521693bdc6a54`, and SHA-256 was
`303864E8D7D6162E53D0A31ACE00B1B42D06073F3732F35703553FB5108AA9A4`.

After confirming the exact emulator/AVD and absence of the production package, the script cleared
only `com.adsamcik.tracker.debug`, installed the final APK without launching it, and staged the
fixture as `main_database`. A prelaunch listing proved `main_database_v27` did not yet exist. First
launch reached durable COMPLETE in 3.289 seconds.

Independent pulled-database and preference verification then proved:

- all 12 imported source tables reported exactly one copied row;
- `location_observation` reported and contained exactly one synthesized accepted observation;
- all 14 intentionally skipped tables reported exactly one retained-only row, removed tables were
  absent, and retained fresh-v27 tables contained none of the v26 sentinels;
- location, step, activity, pressure, cell, and Wi-Fi rows carried stable `legacy:<table>:<id>`
  source identities; cell/Wi-Fi item indexes were zero and coordinates were canonical E7 values;
- the completion receipt was exactly `legacy-database-v26 | main_database:v26 | COMPLETE` with the
  correct 344,064-byte source size;
- both source and target passed `quick_check` and foreign-key validation, and the source SHA-256
  remained byte-identical after import;
- a subsequent current-version debug session increased location samples from 1 to 36, session
  segments from 1 to 2, step intervals from 1 to 2, and tracker runs from 0 to 1 while preserving
  the imported sentinel rows and completion receipt.

The self-contained script completed end to end with PASS in 20.1 seconds. Machine-readable
artifacts are under `build/device-validation/api30-full-v26-matrix-20260812-084320/`, including the fixture
manifest, pulled before/after database families, external import preferences, run summary, and
verification report.

## Optional physical-provider follow-up

`adb devices -l` exposed only the dedicated emulator; no physical serial was connected. The full
matrix check validates storage/import behavior, canonical location/Wi-Fi/cell representations, and
new v27 writes. It deliberately does not claim that an emulator reproduces real modem, Wi-Fi scan,
location-provider, or OEM lifecycle behavior.

A serial-pinned spare phone or secondary Android user may later provide that extra integration
confidence if convenient. It is not unfinished v27 storage work or a release gate for this personal
project.
