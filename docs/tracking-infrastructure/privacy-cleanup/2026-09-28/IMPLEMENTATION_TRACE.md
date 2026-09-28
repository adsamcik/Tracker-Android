# Privacy cleanup implementation trace — 2026-09-28

Status: **IMPLEMENTED_UNVALIDATED** source slices, not complete privacy cleanup. The
tracking-infrastructure assembly gate remains open. No Gradle, compilation, tests, lint,
schema check, emulator, device, CI, release or rollout validation has run for these slices.

## Authored and independently source-reviewed

- `6c5d299230`: 48-hour Activity CONTROL expiry uses the boot/elapsed clock, makes due
  pending actions ineligible, scans outbox in bounded pages and prunes eligible raw WAL
  only after retained proof is saved. A separate WorkManager owner runs even when ordinary
  source retention is disabled. Focused worker, dispatcher and Room test source was authored.
- `f9b45c7993`: raw pruning preserves shared Ambient carriers and unknown future Activity
  effect versions; captured siblings remain until the exact capture lane has drained.
- `abed68821e`: manual and scheduled export failures/cancellation attempt to remove their
  unfinished destination. Failed manual deletion reports its URI while the export screen
  remains present; scheduled deletion failure carries the path/URI in an exception. Focused
  test source was authored.
- `986c47bb54`: full clear removes exact app-owned temporary import, spool and share
  directories after import and scheduled export work are quiesced. A failure retains the
  deletion marker and names the residual path so startup can retry. Focused test source
  was authored.

The independent review found no further concrete defect in those source slices. It
identified the still-open manual export/full-clear race: the Export screen can keep
writing its private share file while full clear removes it. No runtime result is implied
by this static review.

## Open decisions and implementation

- The existing database-only ZIP cannot be called a complete backup. Decide whether to
  retain it as a plainly labeled legacy database archive or hide it until the complete
  backup exists. If retained, its actual database list and sensitive operational scope
  need reviewed preflight and due CONTROL cleanup before the file is published.
- Decide whether Clear all also removes completed scheduled files in the app's private
  `files/exports` directory. The current cleanup intentionally covers temporary files only.
- Decide how Clear all handles a currently running manual Export-screen write and how to
  show any external destination whose unfinished file could not be removed after the
  screen closes. Then implement and author a focused race/recovery test.
- The separate complete all-data backup and recoverable restore still require the pending
  restore-mode choice and full durable-store inventory. None of the commits above claims
  that existing database ZIPs include settings or user-imported files.

Do not integrate this branch into `dev/v10`, activate providers/writers or run the
deferred convergence gates before the full input set is frozen under `AGENTS.md`.
