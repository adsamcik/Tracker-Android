# Build and storage verification

The user explicitly requested on 2026-10-14:

> **Complete build and storage verification.** Fix the outstanding Cell-reader contract, compile the app and dependency wiring, verify database upgrades, and pass `ciUnitTest` and `ciCheck`, repairing failures.

This task-specific instruction authorizes the deferred build/storage convergence batch on the current candidate. It does not declare the entire feature assembly complete, enable providers, prove device behavior, or authorize publication. Remaining feature work stays open.

Input: branch `codex/tracking-verification-1010`, initial HEAD `56a9e21799637f9570d62ea1cc9c0a47e917c5c5`. Exact initial file hashes are preserved in `build/verification-20261014/INPUT_SNAPSHOT.json`; repair inputs and final commit are recorded after completion. Local `dev/v10` is unchanged.

## Checks

- Installed JDK 21.0.9, repository Gradle wrapper; no toolchain or SDK upgrade.
- First build/unit batch found source errors and unavailable authenticated Tracebox Alpha 7 resolution.
- An existing actual Alpha 7 local Maven publication is reused through `traceboxLocalRepository`, with no version override. This is local verification, not proof of clean remote package consumption.
- Build, migration, JVM and quality results are pending.
- Independent static review findings are being repaired; review is not execution evidence.

## Host environment repairs

Native Robolectric SQLite failed opening Windows fixture paths of 260–278 characters. The same helper opened shorter paths. Use an isolated short temporary root for the actual test JVMs; production database handling and supported APIs remain unchanged.

All 30 pinned Tracebox Alpha 7 AAR/module/POM SHA-256 values are verified in `build/verification-20261014/DEPENDENCY_PROVENANCE.json`. The existing publication POMs used CRLF; normalizing only those POMs to LF reproduces the already-pinned checksum exactly. A task-owned repository copy is used. Verification metadata and version pins are unchanged.

## Convergence repairs

- Repaired the Cell reader contract, source-qualified radio child queries and retained-original epoch handling.
- Added the generated v30 schema and exact v29-to-v30 view/correction migration; released `main` remains the upgrade baseline.
- Logical data restore preserves local source choices and quality atomically while retaining historical recording policies.
- Saved Steps and radio observations remain readable/exportable after settings changes and restore; explicit deletion and new-callback checks remain enforced.
- Activity imports persist bounded 256-row batches in the existing transaction, with cancellation and rollback intact.
- Updated stale fixtures/API wrappers and repaired compilation errors without relaxing assertions or gates.
- Independent source review found no new concrete blocker. Execution results remain pending.

The source checkpoint is `IMPLEMENTED_UNVALIDATED`; it is not a complete-feature freeze or local `dev/v10` integration.
