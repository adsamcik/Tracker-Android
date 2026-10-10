# Storage and compilation verification results

## Status

Verification is in progress. The full app build and final repository gates have
not passed. This document records executed host evidence; it does not close the
25 tracking assembly prerequisites or authorize provider activation or integration.

The original candidate is `7e2ae029eae04655b499bdd7cf4f2c5f09c38b6a`.
Repairs are isolated on `codex/tracking-verification-1010` in `.worktrees/tv1010`.
Mixed drafts remain in the original candidate checkout. Local `dev/v10` remains
at `13f1a99871b59d29b3d059f374fc415490da6943`; nothing was merged or pushed.

## Schema and released input

- Released `main`: `59fda665c7099d10b16aa60f505de88bd05d3131`.
- Its observation schema is version 12, blob
  `f50bd97e622f54511f6ebb0c5a34f4ce74669992`, with identity hash
  `603376ce119f1b4f1d3e4866aec15b4e`.
- Room generated observation schema 29 through the repository KSP task. It has
  213 entities and identity hash `afacace9401e581a90502da821d4d95a`.
- Checked-in schema 29 SHA-256 is
  `e6f22b6dda00e8d8d8bf05d52c558e839a3f3f5b138b6966c4094f034ac5b82c`.
- Independent review compared the 28-to-29 tables, columns, defaults, keys,
  indexes and migration SQL. The mapper, query and cutoff repairs add no schema
  changes. This review is distinct from the repository schema-drift gate.
- `checkRoomSchemaDrift` depends on KSP throughout the repository and remains
  blocked by the missing pinned diagnostics dependency.

## Host environment

JDK JetBrains 21.0.9, Gradle 9.6.1, Android SDK platform 37.0 and build tools
37.0.0. Commands run with `--no-daemon --no-parallel --max-workers=2
--no-configuration-cache --console=plain`, and `--continue` for gate batches.

Real file-backed Robolectric SQLite tests initially exceeded Windows native path
limits. The later batches use the temporary directory `C:/ct10` through a
test-only init script. No test, assertion or production check is disabled.
The init script SHA-256 is
`fadab02472346d45d86772b00300887f7d90de9aab2374e4532db4e29992c58e`.
No connected Android device or configured emulator is available. Host Room tests
do not prove device upgrade, physical-provider, lifecycle or battery behavior.

## Executed batches

Logs are retained in `build/verification-20261010` in the verification checkout.
An initial batch's report can be superseded by a subsequent task execution; the
pass-four core and Tracker XML reports were copied before the next rerun.

| Batch | Requested tasks | Outcome |
| --- | --- | --- |
| 1 | `:core:base:kspDebugKotlin :app:assembleDebug :core:base:compileDebugAndroidTestKotlin` | Exit 1; 11m51s, 505 tasks. Room 29 generated; concrete Kotlin defects and unavailable packages blocked completion. |
| 2 | `:app:assembleDebug :app:assembleDev :core:base:compileDebugAndroidTestKotlin ciUnitTest ciCheck` | Exit 1; 36m29s, 2018 tasks. Compilation, tests and quality checks ran where dependencies permitted. |
| 3 | Core/base Android-test compilation and unit tests, preferences, model, stats API, tracking API, schema drift | Exit 1; 12m21s, 203 tasks. Concrete storage/fixture failures were reviewed and repaired. Schema gate blocked. |
| 4 | Affected host modules plus tracking API, Tracker, Statistics and Export release lint | Exit 1; 22m44s, 729 tasks. Core/base 2061 tests/4 failures; Tracker 142/5. Preferences 492 and tracking API 129 pass. Statistics and Export test source compilation failed. Three requested lint gates pass; Export lint fails. |

The pass-four command was:

```powershell
.\gradlew.bat :core:base:compileDebugAndroidTestKotlin :core:base:testDebugUnitTest :data:preferences:testDebugUnitTest :core:model:jvmTest :core:model:testAndroidHostTest :stats:api:jvmTest :stats:api:testAndroidHostTest :tracker:api:testDebugUnitTest :feature:tracker:testDebugUnitTest :feature:statistics:testDebugUnitTest :feature:import-export:testDebugUnitTest :tracker:api:lintRelease :feature:tracker:lintRelease :feature:statistics:lintRelease :feature:import-export:lintRelease --init-script build/verification-20261010/short-temp.init.gradle --continue --no-daemon --no-parallel --max-workers=2 --no-configuration-cache --console=plain
```

Pass four launched on `94c88129f0`. Repairs were authored after affected tests
finished while other tasks continued; it is not claimed as verification of those
later repairs. The next batch must use one committed input with no concurrent edits.

| Log | SHA-256 |
| --- | --- |
| `compile-schema.log` | `b088e7e410bc9c534d3d66a36dafc47f6c2ad47d1844624f4d66e31982e7d539` |
| `convergence-pass-2.log` | `11295a11f9be917f4865d9036dd76a1392d23c19c409953e03e68ab89ec27f2e` |
| `storage-host-rerun-3.log` | `23011b3d8669e2f5e22205a787c524a121e1a4defc4c8ff770dba6d19d407d1e` |
| `affected-gates-rerun-4.log` | `724bb86deb9547f841ec77ae10c4c75c787fdaece8d66f071555e35bfe992361` |

## Repairs and independent review

The source-review record lists each repair and its scope. Committed slices:

- `2e3b975fd2`: generated/reviewed Room 29 and scoped verification input.
- `5b53672830`: production compiler fixes and released-upgrade coordinator fixture.
- `9c61280545`: concrete host fixture defects and settings publication locking.
- `8d9a506651`: missing tracking notification translations.
- `e2d789efc5`: Compose configuration reads and owner-aware rollback assertions.
- `94c88129f0`: lifecycle receipt field-count repair, correct radio source constants,
  current settings refresh, released-schema and storage fixtures.
- `728456f2bf`: preserve linked session metadata at the retention cutoff.
- `ba70ee042c`: host fixture synchronization and supported Compose test APIs.

Commits remain labelled `IMPLEMENTED_UNVALIDATED` while full convergence is open.
Passing individual host suites does not make the complete tracking implementation
verified. No broad baseline expansion, suppressed failure or dependency substitution
has been used.

## Dependency and quality blockers

Tracebox, Tracebox native and Compose UI diagnostics are pinned to
`0.1.0-alpha.7`. That version is not cached. GitHub Packages responds 401 even with
the existing GitHub sign-in; its scopes do not include `read:packages`. Adding
that permission requires the user's GitHub authorization. The pending question
requests that authorization. Cached alpha.8 is not substituted.

This blocks application Debug/Dev assembly, application Hilt composition,
diagnostics and dependent engine/statistics modules, aggregate schema drift,
release linkage and completion of `ciUnitTest`/`ciCheck`.

Dependency insight itself passed (32s), resolving Tracker host-test Compose UI to
1.11.4 under BOM 2026.06.01. Its retained log SHA-256 is
`6fe91427e0313777bb58e6e838bcdec8a176cc5872d583d8c8cb6042e3ec7f26`.

The executed root Detekt gate also failed: 14,674 unsuppressed findings across
974 files. Of these, 352 refer to preserved documentation snapshots; 14,322
remain in live source. The existing baseline is unchanged. This requires source
remediation and a rerun; it is not dismissed as a passing gate.

## Remaining evidence

- Rerun the repaired storage, released import, file-backed, UI and lint gates on
  one final committed input.
- Obtain package access and complete app compilation/Hilt, schema drift and the
  repository `ciUnitTest` and `ciCheck` aggregates.
- Repair the outstanding full quality gate failures.
- Execute the app's three released-upgrade coordinator cases, currently blocked
  by its dependency graph. The released Points host fixture has passed; the final
  released observation-import outcomes still need the rerun result.
- Keep the two pending Location gap implementations and excluded assembly drafts
  visible as separate outstanding work. They are not completed by these repairs.
- Obtain the required Android device evidence before claiming practical provider
  acceptance. No APK or integration readiness is claimed here.
