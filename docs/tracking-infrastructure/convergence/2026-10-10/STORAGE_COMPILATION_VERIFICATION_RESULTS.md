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
- `checkRoomSchemaDrift` passed in frozen batch nine after the exact reviewed
  diagnostics artifacts resolved. The complete build and quality aggregates still fail.

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
pass-four and pass-five reports were preserved before later reruns. Pass-six
Preferences, Statistics and Export XML reports are preserved under `pass6`.

| Batch | Requested tasks | Outcome |
| --- | --- | --- |
| 1 | `:core:base:kspDebugKotlin :app:assembleDebug :core:base:compileDebugAndroidTestKotlin` | Exit 1; 11m51s, 505 tasks. Room 29 generated; concrete Kotlin defects and unavailable packages blocked completion. |
| 2 | `:app:assembleDebug :app:assembleDev :core:base:compileDebugAndroidTestKotlin ciUnitTest ciCheck` | Exit 1; 36m29s, 2018 tasks. Compilation, tests and quality checks ran where dependencies permitted. |
| 3 | Core/base Android-test compilation and unit tests, preferences, model, stats API, tracking API, schema drift | Exit 1; 12m21s, 203 tasks. Concrete storage/fixture failures were reviewed and repaired. Schema gate blocked. |
| 4 | Affected host modules plus tracking API, Tracker, Statistics and Export release lint | Exit 1; 22m44s, 729 tasks. Core/base 2061 tests/4 failures; Tracker 142/5. Preferences 492 and tracking API 129 pass. Statistics and Export test source compilation failed. Three requested lint gates pass; Export lint fails. |
| 5 | Full app Debug/Dev, core Android-test compilation, `ciUnitTest` and `ciCheck` | Frozen input `074782e1ae94c077918f47c86778972fd605fc40`; exit 1, 27m20s, 2080 tasks. Core/base 2062/0 failures, Tracker 142/0; Preferences 492/1, Export 768/8 with one skip. Statistics test compilation failed. All four affected release lint tasks pass. App/package and full quality gates remain blocked or failed. |
| 6 | Preferences, Statistics and Export host tests; Export release lint | Frozen input `63bee1c761f796e15f825b85b04b2f0c50b8a79d`; exit 1, 6m49s, 475 tasks. Preferences 492/1, Statistics 580/8, Export 774/1 with one skip. Statistics test compilation and Export release lint pass. |
| 7 | Focused authoritative settings, Statistics screen/History and radio codec host tests | Frozen input `d30badadcb95ee71515fc8603a78318c064b0ce8`; exit 0, 2m9s, 262 tasks. All 81 selected tests pass; the date-picker and full affected suites are outside this subset. |

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
| `frozen-convergence-pass-5.log` | `607f328cfa2bffe27a19d3086b3c4331d32fa3fd85e3c9fcbe2545af127583a9` |
| `affected-host-pass-6.log` | `d73f728d878b1bd32fe37b7f15259900fe129f70b8b441ff2a13d7d51b414a0e` |
| `focused-host-pass-7.log` | `58f94d22601c07c2a7ca36163ecf7b2751e72f7637b481762be70be0ad7244c8` |

Pass five used the complete pass-two command plus the short-temp init script.
Pass six requested `:data:preferences:testDebugUnitTest
:feature:statistics:testDebugUnitTest :feature:import-export:testDebugUnitTest
:feature:import-export:lintRelease` with the same init script and batch flags.
Neither frozen batch had concurrent source edits. Pass seven used the same flags
and init script with task-local `--tests` selectors for
`AuthoritativeTrackingParamsRepositoryTest` (18), `StatsScreenComposeTest` (36),
`MeasuredHistoryContentComposeTest` (20) and `RadioSourceFileJsonV2CodecTest` (7).
Its XML reports are preserved under `pass7`. The count is selected host executions,
not complete-module or unique full-repository coverage.

## Recorded passing host coverage

Core/base's fifth-batch XML contains 289 suites, 2062 tests, no failures, errors or
skips. This includes 24 `LegacyV26ImportTest` cases with the actual released
version-12 input and preservation cases, 57 development-v28 containment cases,
radio child migration, full-clear, source-session lifecycle, radio admission and
retention-cutoff regression cases. The 24 cases are not 24 independent full-app
upgrade paths. Core main and Android-test sources compiled; no instrumented
migration was executed.

Tracker's fifth-batch XML contains 18 suites, 142 tests, no failures, errors or
skips. The reviewed Start continuation/disposal and Czech notification dialog
fixtures now pass. The Points module contains 114 passing host tests, including
its released-database fixture. The app's three real upgrade-coordinator fixtures
remain unexecuted because app compilation is blocked.

Other passing report inventories include tracking API 129, model 29 and stats API
904 test executions across their configured host/JVM targets. Some fifth-batch
tasks reused existing up-to-date results; these are not represented as newly
executed or unique tests. Exact module inventories are retained with the logs.

Pass six confirms the generic radio decoding fix and successful file transfers
through the existing import receipts. Its only Export failure is the exception
type for strict trailing JSON. The remaining Preferences failure is a coroutine
continuation-release exception in the new Unconfined test fixture, not a failed
policy assertion. Statistics ran for the first time after its compilation repairs
and exposed eight UI test failures. Their reviewed repairs and reruns remain open.

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
- `82b24181c5`: export translations and import-worker lint indentation.
- `074782e1ae`: provisional evidence record and fifth-batch frozen input.
- `63bee1c761`: radio generic decoding, Compose imports, historical export and
  transfer/scheduler fixture repairs.
- `d30badadcb`: exact malformed-JSON classification, queued bootstrap fixture,
  existing Statistics control interactions and sixth-batch evidence.

Commits remain labelled `IMPLEMENTED_UNVALIDATED` while full convergence is open.
Passing individual host suites does not make the complete tracking implementation
verified. No broad baseline expansion, suppressed failure or dependency substitution
has been used.

## Frozen batches eight and nine

Both used committed input `12735255e37cb34424bf3b48b988a6cef67d2dfa`
and the exact reviewed alpha.7 local mirror. No source was edited during either run.

Batch eight was **interrupted**, not passed: the Windows system drive ran out of
space while redirecting Gradle output. Its wrapper returned an unreliable zero,
which is rejected because the log has no build completion marker. Log SHA-256:
`622e6ac39788788cbad04154d0c7076741720f640d858caafea5868396b00da7`.
After confirming no Java build remained, only the inactive Gradle 9.5.1 and 9.8.0
`transforms` cache directories were removed. Checked absolute targets and before/
after inventories are preserved; sources, drafts, reviewed dependencies and test
evidence were retained. This recovered about 3.7 GB. Batch nine used a guarded
wrapper that checks native launch, logging and completion failures.

Batch nine completed **exit 1**, 22m20s, 2,300 tasks (305 executed, 8 from cache,
1,987 up to date). Its complete log SHA-256 is
`f99544d77403211d8233afa4697bcf8b562f4c2975e5c90189a76b2eb7223a48`.
The reports were archived under `build/verification-20261010/pass9` before repairs.

Freshly executed full suites:

| Module | Tests | Failures | Skips |
| --- | ---: | ---: | ---: |
| Preferences | 492 | 0 | 0 |
| Statistics UI | 580 | 0 | 0 |
| Import/export | 774 | 0 | 1 |
| Map | 877 | 0 | 3 |
| Diagnostics | 51 | 1 | 0 |
| Activity sensor | 335 | 2 | 0 |

Export retains one existing symlink host assumption skip; Map retains three
pre-existing disabled Android-framework cases. No test was disabled by these
repairs. Statistics includes the repaired
calendar date-picker case. Previously passing core/base 2,062, Tracker UI 142,
released import/Points and API results were up to date in this batch and are not
reported as newly executed. Room schema drift, Activity API dependency checks,
vendored/release SQLite linkage and release dependency-metadata checks passed.
This does not prove application Hilt or an APK: production Stats/data and tracking
engine compilation failed, blocking application Debug/Dev assembly and upgrade
orchestration tests. The aggregate `ciUnitTest` and `ciCheck` did not pass.
Detekt still reports 14,695 findings; rules and baseline remain unchanged.

The next slice repairs explicit SQL binding types, stable nullable locals and
same-module type references, plus the engine's missing imports/internal visibility,
shared Cell field conversion and exhaustive Wi-Fi rejection mapping. It changes
no schema, queries, caps, permission rule or tracking activation. A new bridge
regression covers unchanged Cell fields and canonical bytes for both supported
versions. Activity's persisted callback comparison also needs to ignore the
intentionally non-persisted live Start generation. These repairs require a reviewed,
committed rerun. The Cell imported-record lookup interface decision is still pending.

## Dependency and quality blockers

Tracebox, Tracebox native and Compose UI diagnostics are pinned to
`0.1.0-alpha.7`. Earlier batches could not resolve that version. GitHub Packages responded 401 even with
the existing GitHub sign-in; its scopes do not include `read:packages`. Adding
that permission requires the user's GitHub authorization. The authorization question is unnecessary for this local batch now that the exact
reviewed mirror is available. Cached alpha.8 is not substituted.

This blocked application Debug/Dev assembly, application Hilt composition,
diagnostics and dependent engine/statistics modules, aggregate schema drift,
release linkage and completion of `ciUnitTest`/`ciCheck` in the earlier batches.

After pass seven, bounded inspection found existing alpha.7 publications in the
local Tracebox workspace. Tracker already supports the local-only
`-PtraceboxLocalRepository` validation seam; it is forbidden in CI. All ten AARs
and ten module files match the existing reviewed dependency-verification hashes.
The ten POMs match those hashes after restoring LF from their local CRLF checkout.
An isolated mirror under `build/verification-20261010/tracebox-alpha7-reviewed`
contains only the 30 exact reviewed artifact bytes; any mismatch was rejected
before resolution. No dependency pin, production repository, verification metadata
or SDK source is changed. Its inventory SHA-256 is
`e9ca95f4ac57f3f1e16f57366dcab2956f4e2a645ca3827321bd883f190dc79c`.

The preserved source archive identifies tagged commit
`b49e8fda94e0bd65859eb080cacd624d9b72a7f5` (`v0.1.0-alpha.7`), matching local
read-only Git metadata and version declarations. Archive SHA-256 is
`ba43c819f6bad859bc7c8bbdfc8ab6236eb2b0122f1dc5694df54d2d48aefbe5`.
The existing local seam can unblock the next host batch without the pending
GitHub authorization. Actual authenticated GitHub package consumption and a clean
CI checkout remain separate evidence; this local mirror does not prove them.

Dependency insight itself passed (32s), resolving Tracker host-test Compose UI to
1.11.4 under BOM 2026.06.01. Its retained log SHA-256 is
`6fe91427e0313777bb58e6e838bcdec8a176cc5872d583d8c8cb6042e3ec7f26`.

The fifth-batch root Detekt gate failed with 14,695 unsuppressed findings across
976 files. Independent XML/SARIF inspection identifies 352 findings in 33
preserved documentation snapshots and 14,343 in 943 live source files (12,708
production and 1,635 test findings). There are no reported generated/build/cache
or worktree files in this isolated input. Counts by category are style 7,857,
comments 3,936, complexity 2,704, exceptions 141, naming 52 and performance 5.
The existing baseline is unchanged. This requires source remediation and a
rerun; it is not dismissed as a passing gate.

The root task scans the whole checkout and does not exclude archived source
snapshots or nested `.worktrees` checkouts. A narrowly scoped exclusion proposal
is pending user approval; it would remove only copied, uncompiled inputs and
leave the 14,343 live findings and all rules/baselines intact. No such change has
been implemented. This report does not justify sweeping refactors or baseline
expansion. Detekt XML SHA-256 is
`dd08fdb387dffd0c4c07c506e5e7d37f9ff2148829122fa4bdbd7d6e6a6f090f`;
SARIF SHA-256 is
`a149671c8febd345311b5bfac3f63959d227c8f1ec41695ec1b93e740775a4cc`.

## Remaining evidence

- Rerun the repaired storage, released import, file-backed, UI and lint gates on
  one final committed input.
- Use the exact reviewed local artifacts to rerun app compilation/Hilt, schema
  drift and the repository `ciUnitTest` and `ciCheck` aggregates. GitHub package
  access remains a separate clean-CI check.
- Repair the outstanding full quality gate failures.
- Execute the app's three released-upgrade coordinator cases, currently blocked
  by its dependency graph. Released Points and observation-import host fixtures
  have passed; this does not replace application upgrade orchestration or device
  migration evidence.
- Keep the two pending Location gap implementations and excluded assembly drafts
  visible as separate outstanding work. They are not completed by these repairs.
- Obtain the required Android device evidence before claiming practical provider
  acceptance. No APK or integration readiness is claimed here.

## Next bounded rerun

After batch nine, the compiler slice was committed as
`f53fa305c584ec73c2a07fe334b7d858870596a9`. The reviewed callback comparison and
fixture repairs form the next frozen input. The bounded rerun compiles tracking
engine Debug/Release and executes the complete Diagnostics and Activity sensor
suites. Application/Stats-data validation remains held by the pending Cell lookup
interface decision; full quality findings and the copied-input source-selection
proposal are also unresolved. No gate or test is weakened to bypass them.

Only inactive Gradle 9.5.1/9.8.0 caches and the regeneratable local build cache were
subsequently cleared, with checked target paths and inventory retained, raising
free space above 2 GB for that rerun. No test reports or package artifacts were deleted.

## Frozen batch ten

Input `fb4019009cff30737e7e2717003e92b57008d004` was clean and unchanged while
running. Engine Debug/Release KSP and Kotlin compilation plus complete Diagnostics
and Activity sensor suites passed: **exit 0**, 6m49s, 355 tasks (37 executed,
318 up to date). Diagnostics: 5 suites, 51 tests; Activity: 49 suites, 335 tests.
All 386 have zero failures, errors or skips. The three failing batch-nine cases
are present and pass, including duplicate durable callback retry with the live
Start generation excluded. The fresh independent reviewer verified every one of
54 archived XML hashes and the log. Log SHA-256:
`5c47dadb67b6578fbb572ff82ce6a27cde552384dc36933ba63149f6a6a4c397`.
Inventory SHA-256:
`544e0bcd4e6310b7b1e7b1e802508d9a589a78138a0131ab1e88d727aa1c45a5`.

This is scoped passing evidence. It does not replace engine unit execution,
Stats/data, application/Hilt/upgrade orchestration or full aggregate gates.

## Frozen batch eleven

The same clean input was used to compile Stats/data Debug/Release and the authored
engine unit tests. **Exit 1**, 2m59s, 297 tasks (16 executed, 281 up to date).
Log SHA-256:
`947b4bf1f654a70afb0b774d38473f7f096137def54e95b0a16a786ffd9be1a1`.
Stats/data now has exactly one distinct compiler error, repeated for Debug/Release:
its existing Cell history reader needs the internal saved record ID, covered by
the pending user interface decision. All other prior Stats/data compiler diagnostics
are gone. The API and reader remain unchanged pending that decision.

Engine test compilation reached the authored fixtures and found stale imports,
old type/enum references, mixed reified SQL array types and a private pure model
conversion call. Their repairs retain the original assertions, SQL values and
query literals. The pure existing conversion is made visible only within the
engine module so the raw before/after fixture can use it without invoking a
mutating repository load. No public API or stored-data change follows. The next
reviewed compile rerun is required; the tests themselves remain NOT_RUN.

## Current limits

The latest full quality run remains failed with 14,695 findings; subsequent bounded
compiler runs do not remeasure or waive that count. The proposed copied-input
exclusion is still unapproved and unapplied, and would not resolve live findings.
Final `ciUnitTest`/`ciCheck`, application Debug/Dev, application Hilt and the three
released-data orchestration cases require completion after the pending interface
choice and remaining quality work. The original two Location gaps, complete
tracking assembly, device evidence and integration are still separate open work.
Local `dev/v10` is verified clean at `13f1a99871b59d29b3d059f374fc415490da6943`.
