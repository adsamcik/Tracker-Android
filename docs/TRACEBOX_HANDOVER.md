# Tracebox / Tracker v10 handover

Date: 2026-07-31
Scope: personal-project release, host tests plus one API 36 x86_64 emulator

## Repositories and branches

- Tracker: `https://github.com/adsamcik/Tracker-Android.git`, branch `dev/v10`
- Tracebox: `https://github.com/adsamcik/Tracebox.git`, branch
  `codex/personal-project-scope`, handoff commit
  `ee6c6854a9a799de3964e27fc92cd23642f059e8`

Pull both branches before continuing. The Tracker commit intentionally contains the complete
pre-existing v10 working tree as requested, not only the Tracebox migration.

## Current outcome

Tracker is hard-migrated to Tracebox:

- Tracebox is an unconditional app dependency; there is no trial/product flavor switch.
- Tracebox is installed from `Application.attachBaseContext()` before normal Hilt startup.
- The dedicated `:tracebox_handler` process is excluded from Tracker application startup.
- `core/logging`, `core/logging-api`, the old crash handler/database/export screens, ad-hoc
  `TrackerLog`/`PointLog`/feature logger wrappers, and their tests/resources are removed.
- The old `standard` and `traceboxTrial` source sets and all trial controllers/screens are removed.
- Firebase/Crashlytics plugins, dependencies, workflows, examples, and active documentation are
  removed.
- Tracker diagnostics use the fixed Tracebox catalog in `core/diagnostics`; the settings UI exposes
  Tracebox diagnostics directly.
- Active documentation and QC instructions no longer advertise the deleted crash/log viewers.

The re-audit found no active legacy logger/crash/Firebase implementation, module, source set,
flavor gate, uncaught handler, or Android logging call. Historical archive documents and ignored
linked worktrees may still mention or contain old code; they are not part of the active `dev/v10`
tree and should not be treated as migration leftovers.

Tracker currently pins:

```text
io.github.tracebox:tracebox:0.1.0-personal.2c968863
```

The pin is in `gradle/libs.versions.toml`; the reproduction command is in `README.md`. It must stay
on that known local artifact until the final Tracebox native candidate below is rebuilt and passes
the emulator gate.

## Tracebox work completed after the pinned candidate

The current Tracebox branch contains the production capture/storage and personal-release harness
work that followed commit `2c9688630c11d6d43805a5853c1ae9bf4fdc7a17`, including:

- deterministic primary-process native-readiness recovery;
- handler shutdown/drain and dedicated-process isolation fixes;
- activity visibility tracking and realistic ANR qualification;
- fatal-scenario relaunch without `am force-stop`;
- strict handoff-only evidence detection in the emulator harness;
- Crashpad pending `<uuid>.dmp + <uuid>.meta` recovery and exact orphan-sidecar cleanup;
- bounded stale `<uuid>.lock` retirement only after positive handler quiescence;
- recovery of exact `DEAD`/`HANDOFF_FAILED` lifecycle registrations while continuing to reject
  `PROTOCOL_ERROR`;
- native Crashpad publication handling that validates/syncs the dump, moves it no-replace, syncs
  the handoff directory, deletes metadata, and syncs the pending directory;
- correct lifecycle classification for real peer EOF: a zero-length `SOCK_SEQPACKET` receive is
  considered `DEAD` only when `POLLHUP`/`POLLERR` also proves disconnect;
- a 2.25-second bounded dead-client handoff window instead of the previous 250 ms window.

The last emulator failure was diagnosed rather than left unexplained. The observed SIGSEGV created
a valid 159,992-byte `.dmp` and 32-byte `.meta`, but the lifecycle journal ended as
`PROTOCOL_ERROR`. The handler saw `POLLIN | POLLHUP`, received EOF, misclassified it as malformed
protocol, and skipped handoff. The old 250 ms wait and missing metadata-sidecar support were
additional failure paths. The current source fixes all three.

## Validation already completed

Tracker before this handoff:

- full host unit suite: 2,154 suites, 9,617 tests, 0 failures/errors, 6 skipped;
- clean debug and release assembly passed;
- APK manifest/dependency/native packaging checks passed;
- static hard-migration audit passed after the documentation cleanup in this commit.

Tracebox current source:

- `:android:tracebox-storage:testDebugUnitTest`: passed;
- focused `CrashpadHandoffIngestorTest`: 35 tests passed, including live/quiesced lock handling,
  `DEAD`/`HANDOFF_FAILED` recovery, `PROTOCOL_ERROR` rejection, and sidecar quota release;
- native CMake/CTest: 11/11 passed after the final EOF classification fix;
- `git diff --check`: passed before handoff.

Stored evidence:

- `Tracebox/evidence/phase5/personal-release-host-readiness.json`: earlier 14-gate host pass; rerun
  because source changed afterward.
- `Tracebox/evidence/personal-release/API36-x86_64-4096-20260731-113115.json`: 12/13 emulator
  scenarios passed; only `FAULT.CPP_SEGV` failed, before the final native fix described above.
- `Tracebox/evidence/personal-release/API36-x86_64-4096-personal-release-final.json`: earlier
  personal-release run retained for comparison.

No claim is made that the final source has passed Android runtime certification yet.

## Required continuation on the next device

1. Pull both branches and verify both working trees are clean.
2. Install/use the pinned toolchains declared by Tracebox.
3. Rebuild ignored Android native binaries from the committed source:

   ```powershell
   pwsh -NoProfile -File tools\crashpad\Build-Crashpad.ps1
   ```

   `android/tracebox-native/src/main/jniLibs` is intentionally ignored, so these `.so` files do
   not travel in Git.

4. Re-run the focused host tests:

   ```powershell
   .\gradlew.bat :android:tracebox-storage:testDebugUnitTest `
     :android:tracebox:testDebugUnitTest --offline --no-daemon
   ```

5. Run the personal emulator gate on one API 36 x86_64, 4 KiB emulator:

   ```powershell
   pwsh -NoProfile -File tools\verify\Invoke-PersonalReleaseEmulator.ps1 `
     -Serial <emulator-serial>
   ```

   Expected result: all 13 representative scenarios pass, especially `FAULT.CPP_SEGV`; pending
   `.dmp`, `.meta`, and `.lock` files must be retired after import and runtime must return to
   `DURABLE/READY`.

6. Run the complete host readiness script once source is frozen:

   ```powershell
   pwsh -NoProfile -File tools\verify\Invoke-Phase5HostReadiness.ps1
   ```

7. Commit any resulting evidence/source fix in Tracebox. Publish all Tracebox Android modules to
   Maven Local using a version derived from that final Tracebox commit.
8. Update Tracker’s `tracebox` version and README command from
   `0.1.0-personal.2c968863` to the final version.
9. Re-run Tracker unit tests plus clean debug/release assembly, then install a Tracker debug APK on
   the same emulator and smoke-test startup, diagnostics, handler restart, and one intentional
   crash/relaunch.

Only validation, native artifact regeneration, final repinning, and any defects revealed by those
checks should remain. Do not restore a flavor gate, legacy logger bridge, second crash handler, or
compatibility database.
