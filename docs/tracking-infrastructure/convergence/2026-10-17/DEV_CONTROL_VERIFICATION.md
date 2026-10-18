# Dev tracking control verification — updated 2026-10-18

Scope: repair the existing emulator and verify My location, manual Start, saved Location delivery,
and End in the Dev app. This is not a claim that every provider or physical-device battery and
background behavior has been validated.

Older damaged test sessions with incomplete terminal presentation/export metadata are not claimed
to be portable-export ready by this control repair. Their stored readings have not been discarded
or their processing cursors skipped to obtain a passing Start.

## Repair

- [x] Preserve the existing AVD's paired data/encryption disks before its approved reset.
- [x] Restore the same configured `Android_17_Google_Play` AVD; no new AVD or API matrix.
- [x] Review settings supersession, startup lock reentry and foreground Start compensation fixes.
- [x] Share the saved Location summary and bounded route preview across live presentation.
- [x] Renew the exact coordinator lease while slow Start/reconfiguration operations run.
- [x] Qualify retained Location against its original lease membership after a later operation.
- [x] Replay historical Location through the running persistence owner, preserving original session
  attribution and isolating historical curation from current-session analytics.
- [x] Defer offline replay immediately if that persistence owner is busy; retain cleanup ownership.
- [x] Keep live/retiring service ownership authoritative when startup recovery encounters End.
- [x] Bound Activity provider waits and preserve unfinished requests in the existing cleanup journal.
- [x] Recover an exact missing historical session binding before retrying retained Location/Cell.
- [x] Give each End cleanup retry a fresh operational deadline, preserving the original recording cutoff.
- [x] Reuse saved policy/consent across reboot, retaining exact observation and provider ownership.
- [x] Accept canonical adaptive Activity plans and existing sealed callback-tail evidence.
- [x] Keep healthy capture usable while an unresolved sibling remains fenced until End retirement.
- [x] Retain interrupted Activity/Wi-Fi readings using their original finite provider authority,
  preserving unknown Stop timing and partial coverage.
- [x] Let a newer manual Start retire an exact abandoned handoff that never accepted foreground
  delivery or attempted a provider; preserve accepted and active owners.
- [x] Route the saved unaccepted descriptor to that existing preparation decision; replace it only
  after Room accepts the new Start, preserving it on refusal.
- [x] Claim the prepared envelope through cancellable startup work and the existing lifecycle lease,
  leaving foreground admission under the current Start/Stop checks.
- [x] Forward all four source admission methods, including atomic sensor checkpoints and deliveries.
- [x] Accept a genuinely empty provider's zero high water in raw retirement and completeness readers.
- [x] Preserve Location identity through metadata-only manifest/lease refreshes of the same grant.
- [x] Settle authenticated post-End Location callbacks without saving them or blocking later sessions;
  preserve the raw callback and independently qualified all-day observations.
- [x] Start a new Wi-Fi aggregate when valid readings have different authority or bounds; retry exact
  retained missing-segment cases through the existing writer after presentation binding.
- [x] Use the existing cleanup grace period for source retirement and remove per-byte Formatter
  allocations from the hot digest paths without changing their output.
- [x] Independent review of the production changes and regression sources; findings addressed.

## Final acceptance

- [x] Build and verify the debug-signed `Advention Dev` / `com.adsamcik.tracker.dev` APK.
- [ ] Install that final APK in place on the existing emulator.
- [ ] My location requests a fresh fix with tracking off, shows its marker and zooms to it.
- [ ] Manual Start reaches Recording and fresh fixes reach canonical storage and the saved-data UI.
- [ ] End returns to Ready and finalizes the same session without a new app crash.
- [x] Commit and integrate the host-verified changes into local `dev/v10`; runtime acceptance remains blocked.

The final APK is built (SHA-256
`FD1FC3F6E22FE5E6A1CD94D2E592A4705067502B48FF344D999FFF6E8276F081`), with version
`10.0.0-dev`, package `com.adsamcik.tracker.dev`, label `Advention Dev`, debug signing and
ARM64/x86_64 support. Host checks passed; runtime acceptance is blocked below.
Earlier screenshots of Recording and recovered old samples are not acceptance evidence for fresh
saved-data delivery or successful End.

### Host checks

| Check | Result |
| --- | --- |
| Dev compilation, Hilt and APK packaging | Passed; exact final APK signature verified |
| Dev lint | Passed, 45 warnings; baseline unchanged |
| Scoped Detekt | Passed on all 27 changed Kotlin files |
| Affected engine regressions | 552 passed in the final batch |
| Affected database regressions | 18 passed; unchanged result reused by the final batch |
| Unchanged Cell and codec regressions | 108 passed in R4; valid results reused |

The current source freeze is `build/verification-20261018/recovery-input-r6.json` (27 Kotlin files).
The final batch is `recovery-host-checks-r6b.log` (exit 0, 9m 14s). All 27 source hashes match.
The [current result](CONTROL_RECOVERY_RESULT.json) records exact suites, source hashes, APK identity
and the runtime limitation. There are 678 distinct passing tests across the final affected results
and unchanged R4 results, with zero failures, errors or skips. No failed result is counted as passing.
R5's test-compilation error was fixed by declaring the SQL fixture's mixed values as `arrayOf<Any>`;
the final engine test compilation and execution passed. No rule, assertion or baseline was weakened.
The failed prior batches and original XML remain preserved under `build/verification-20261018`.
The prior 484-test control checkpoint and the broader October 16 gates remain historical receipts;
the full repository aggregates were not rerun for this repair.

### Runtime blocker — October 18

The user's reconnection restored D: readability, and the same existing API 37 AVD booted successfully.
The R4 Dev APK installed in place and My location displayed a centered marker, rendered street tiles
and a 50 m scale. With tracking Off, consistent before/after database snapshots showed zero new
Location WAL entries, saved points or accepted observations after the tap. That is R4 evidence,
not acceptance of the final R5 APK.

Before final installation, D: disappeared again. Windows `.NET Directory.GetLogicalDrives()` lists
only C:, `Get-Item`/`Get-PSDrive` report that D: does not exist, and `emulator-5554` is offline.
ADB reconnect was attempted; `install -r` failed before deploying the final APK. The final APK,
host results and source are on C:. Final My location/Start/saved-route/End acceptance therefore
remains unchecked. No replacement, cloned or older-API AVD was used, and no data was cleared.
The same D: AVD/image must be readable again before these runtime checks can finish.

Earlier in this run, an independent read-only check found Windows Application Error 1005/1000 and disk 153 I/O
retries, with `0xC0000483` / `0xC0000006` failures. Reads fail for the emulator/QEMU binaries, the
AVD's live `userdata-qemu.img.qcow2`, `config.ini`, `hardware-qemu.ini`, and its API 37.2 image directory.
No app data was cleared to bypass this failure.

The existing C: emulator binary runs and reports the same 37.2.12 / build 16428233 version. C: has
enough space for the 5.70 GiB AVD, but relocation cannot preserve files that D: cannot read. Its only
installed system image is API 36, which is not an allowed fallback. No replacement/cloned AVD or
older-API test was created. Restored access to the existing D: AVD/image is required for final
runtime acceptance. The user requested a retry on October 18; both Python and .NET file reads were
retried, and .NET again reported a fatal device hardware error for the same `config.ini`. Host checks
continue without a replacement AVD.

### Intermediate receipts

The Dev APK built at 19:29 on October 17 (SHA-256
`F1B7B2505EF37872586F4A7EBE3994E90E302005BC7F210B08D057D5B283F1A2`)
installed successfully without clearing app data. With tracking Off, My location obtained a fresh
high-accuracy request and displayed a centered marker at a 50 m map scale. Basemap tiles did not
render in that capture. This APK predates the bounded Activity change.

That APK did **not** pass manual Start acceptance. The first attempt accepted foreground delivery
but finalized with initialization failure before Recording. A diagnostic retry reached an accepted
Location runtime and an applying Activity runtime, then also stopped before Recording. Debugger
heap inspection perturbed the retry, so it is diagnostic evidence rather than a timing benchmark.

The final-controls host batch compiled/packaged the APK, passed scoped Detekt, and ran 268 engine
tests: 265 passed and three failed on 100 ms success-fixture cleanup deadlines. The focused replay
and prepared-lease-renewal regressions passed. Those fixture budgets now use the existing five-second
Room budget and require completed cleanup; their focused rerun is pending. Their 11 affected scenarios subsequently passed. The bounded Activity backend passed 20 cases and its cleanup worker passed three; the cleanup-store suite needs its Windows AtomicFile fixture rerun. Dev lint remains pending after an offline missing-artifact failure. The online follow-up resolved its models but was canceled after the final recovery changes were assembled; the frozen final batch is authoritative.

A later Start with the bounded Activity APK (SHA-256
`AFB4836369557724E18B1179ABC5EA470D0ECAD19660493864FE2D48F6A547D0`)
reached visible Recording with no debugger attached. End persisted its original cutoff, but source
cleanup timed out and retries reused the expired cutoff-based deadline. This is diagnostic evidence,
not successful End acceptance. Source-global Activity/Wi-Fi/Cell failures also blocked settlement:
valid prior-boot settings and canonical adaptive Activity plans were being rejected. Their exact
retained rows will be requalified through the normal writer; no cursor skip or raw-data deletion is
part of recovery.

The subsequent Dev APK (`11A54F1DCEDA8F42858125B249C4366E98EE7825F88FA5C7641A7EF22080E946`)
also passed My location with tracking Off: fresh high-accuracy delivery, centered marker and 50 m
scale, with no new saved Location points after the tap. Its manual Start did not pass. A no-debugger
attempt left an unaccepted prepared session; a separate bounded event-only diagnostic attempt
confirmed `PREPARED_FOREGROUND_COMPLETION_TIMED_OUT`. That diagnostic is not a timing benchmark.
The missing retry and interrupted-history cases are now included in the next frozen input.

The initial frozen regression batch ran 794 tests: 783 passed and eleven failed on five fixture or
obsolete-expectation corrections. Original XML is preserved. Production was unchanged by those
corrections, but the later runtime repairs change Activity/Wi-Fi and coordinator behavior, so their
affected classes and the corrected cases are being rerun. The superseded batch was cancelled after
the final recovery sources were assembled; its unfinished lint is not a passing result.

## Environment and limits

The target is the pre-existing Android 17 / API 37 Google Play x86_64 AVD with 16 KB pages,
hardware graphics and injected GPS fixes. The Dev package is debuggable, debug signed and retains
its Dev label, package and version suffix; ARM64 remains supported.

The original emulator disk backup is retained at `D:/AVD/repair-backup-20261017`. Raw task logs,
screenshots, database snapshots and exact source hashes are retained under
`build/tracking-control-evidence-20261017/verification-20261017`. Other app testing on this shared emulator was
paused with the user's confirmation. The same AVD was cold-booted with unavailable UWB/NFC hardware disabled; its repeatedly restarting `com.google.android.nfc` emulator package was disabled for user 0. This is reversible (`pm enable --user 0 com.google.android.nfc`) and does not change Tracker or phone settings. The initial cold boot produced system-wide ANRs; a settled
warm launch was used to isolate Tracker behavior. Physical GPS, Steps hardware, all six provider
chains and long-running power/background tests remain separate from these controls.
