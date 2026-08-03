# Tracebox / Tracker v10 handover

Date: 2026-08-03
Scope: `Tracebox 0.1.0-alpha.2`, host tests, and one x86_64 emulator

## Branches

- Tracker: `dev/v10`
- Tracebox: `codex/personal-project-scope`
- Tracebox release tag: `v0.1.0-alpha.2`

## Final integration

Tracker is hard-migrated to Tracebox with no product flavor or compatibility
bridge:

- `tracebox`, `tracebox-native`, and `tracebox-ui-compose` are unconditional
  normal-app dependencies pinned to `0.1.0-alpha.2`;
- native code remains an explicit optional dependency for other Tracebox hosts;
- Tracebox installs before Hilt startup and its handler process skips Tracker
  application initialization;
- direct `Tracebox.log` calls replace the retired logger/fixed-code facade;
- throwable failure paths retain stack identity through
  `Tracebox.log.error(throwable, template)`;
- verbose/debug/info/warn/error levels, Logcat mirroring, performance timings,
  thresholds, and capture kinds are runtime-controlled by one persisted policy;
- tracking-cycle and OSM reindex performance boundaries use Tracebox timings;
- the library-owned Compose diagnostics screen supplies controls, status,
  review, package save/share, and deletion; and
- the old Tracker diagnostics controller, ViewModel, screen, fixed catalog, and
  their tests/resources are deleted.

Tracker's `core:diagnostics` module is now only a dependency boundary that
re-exports the base Tracebox artifact. It contains no Tracker facade.

## Privacy behavior

Tracebox accepts templates plus parameters. Primitive values and enums are
public by default; strings and unknown objects default to PII and are redacted.
Callers may use explicit privacy wrappers or register domain renderers during
installation. Formatting occurs after the runtime gate. Exception messages are
not persisted.

## Validation completed

Tracebox:

- every Android module debug unit test passed;
- base, native, and Compose UI release AAR assembly passed;
- schema compiler tests and generated-artifact drift verification passed;
- the locked Rust workspace test suite passed; and
- all ten `0.1.0-alpha.2` Android artifacts published to Maven Local.

Tracker:

- clean Kotlin compilation passed against the alpha;
- architecture hard-migration and Tracebox bootstrap tests passed;
- tracking cycle/pipeline resilience tests passed;
- affected OSM, activity-recognition, and statistics module tests passed;
- debug APK assembly passed; and
- emulator install/launch passed with a live app process, live
  `:tracebox_handler`, durable `.tbseg`, policy/identity files, and native
  emergency slots.

The attempted all-module test run was invalidated by two Gradle processes
racing on test-result files after a command-wrapper timeout. All migration
critical suites were rerun serially and passed.

## Intentionally left for alpha evaluation

Only practical validation remains: exercise policy changes, export/share,
deletion, and real crashes/ANRs during normal Tracker testing. A physical-device
matrix, OEM campaign, battery benchmark, and enterprise certification are not
required for this personal project.

Do not restore a legacy logger, crash handler, flavor switch, Tracker-owned
diagnostics screen, or fixed diagnostic catalog.
