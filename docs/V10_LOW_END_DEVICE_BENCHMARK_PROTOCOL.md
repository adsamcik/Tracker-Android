# V10 low-end-device benchmark evidence

This protocol creates reviewable evidence for the V10 import/recovery gates. It is intentionally a collection protocol, not a claim that the gates have passed. In particular, it does not enable PBF import and it does not substitute an emulator for a low-end physical device.

The collector is [collect-v10-import-benchmark.ps1](../tools/v10-benchmarks/collect-v10-import-benchmark.ps1). It requires an explicitly supplied device serial, detects common emulator identities, and rejects them unless an operator deliberately passes `-AllowEmulator`. Emulator output is useful for harness development only; it is not release evidence.

## Preconditions

- Use the selected low-end physical device and record the serial supplied to the collector. The JSON captures its model, API level, ABI list, fingerprint, and `MemTotal`/`MemAvailable` values.
- Install the exact build under test. For app-private paths, use a debuggable build so `adb shell run-as com.adsamcik.tracker` works. Use `-StorageAccessMode Shell` only for paths deliberately stored in shared external storage.
- Select the actual database, WAL, private snapshot, and temporary-storage paths for the build. Do not replace unavailable paths with guessed paths: a missing path is meaningful evidence.
- Supply a synchronous workload command that waits for completion. It must invoke a safe import coordinator or a purpose-built fixture; this collector deliberately has no parser or import implementation of its own.
- Do not put secrets in `-WorkloadCommand`: the command and its SHA-256 are written to the JSON evidence.

## Run template

Replace every angle-bracket value with the real device, build-specific paths, and fixture. The example intentionally does not name a PBF parser command because import remains disabled until the safe coordinator and fixture are available.

```powershell
$serial = '<physical-device-serial>'
$package = 'com.adsamcik.tracker'

.\tools\v10-benchmarks\collect-v10-import-benchmark.ps1 `
  -DeviceSerial $serial `
  -PackageName $package `
  -DatabasePath '<absolute-app-database-path>' `
  -SnapshotPath '<absolute-private-snapshot-path>' `
  -TempPath '<absolute-temporary-storage-path>' `
  -WorkloadId '<fixture-and-scenario-id>' `
  -WorkloadCommand 'adb -s <physical-device-serial> shell <synchronous-safe-coordinator-command>' `
  -FixturePath '<local-fixture-or-directory>' `
  -OutputPath 'docs/evidence/v10/<device>-<workload>-<utc>.json'
```

`WalPath` defaults to `DatabasePath-wal`; provide it explicitly if the app uses a different path. The collector refuses to overwrite an existing evidence file unless `-OverwriteOutput` is supplied.

For a deliberate cancellation scenario, issue the cancellation command after the requested delay and preserve the non-zero exit when that is the expected outcome:

```powershell
  -CancellationCommand 'adb -s <physical-device-serial> shell <cancel-safe-coordinator-command>' `
  -CancelAfterSeconds 15 `
  -AllowNonZeroWorkloadExit
```

The workload command is run through `cmd.exe` and must wait for the device operation itself. Its wall time and host-command CPU time are recorded. The collector also snapshots app-process CPU jiffies before and after; it marks that delta non-comparable if the process changes during the run. A timeout stops only the local command shell, so inspect the device and the residue fields before another run.

## Required evidence fields

Commit the resulting JSON with the V10 evidence after checking that it contains the following, rather than transcribing numbers into a report:

| Review question | JSON field |
| --- | --- |
| Was this a real low-end device and exact build? | `device`, `repository` |
| Was the workload and fixture reproducible? | `workload.id`, `workload.fixture_hashes`, `workload.execution.command_sha256` |
| Did managed/native/total PSS grow within the chosen budget? | `memory_kb.baseline`, `memory_kb.final` |
| What wall and CPU time did it use? | `workload.execution.wall_ms`, `host_command_cpu_ms`, `app_cpu.delta` |
| Did SQLite, WAL, snapshots, or temp storage grow or remain behind? | `storage`, `residue` |
| Was cancellation requested, did it exit, and did it leave residue? | `workload.execution.cancellation`, `workload.execution.exit_code`, `residue` |

No numeric budget is prescribed here. The product/release owner must choose memory, disk, duration, cancellation, and recovery acceptance criteria before a result can pass a release gate.

## Minimum scenario matrix

Run the selected low-end physical device with the same evidence schema for:

1. A normal successful safe-coordinator import fixture.
2. A checked cancellation at a known work boundary.
3. A restart/recovery case that confirms unpublished/private data is not published and residual temporary/snapshot storage follows the selected cleanup policy.
4. A multi-region/overlap fixture that verifies ownership and publication behavior.

The current repository has safe-intake building blocks and import-generation work, but it does not yet provide a release-safe coordinator plus approved PBF fixtures. Likewise, graph-key/import-to-render comparison fixtures must use the real persisted graph and production renderer. Until those workloads exist and real-device JSON evidence is committed, this protocol cannot be used to claim PBF intake, recovery, graph ownership, or low-end-device benchmarks complete.
