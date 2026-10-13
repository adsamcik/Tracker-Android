# Approved everyday tracking gaps — 2026-10-13

Status: **IMPLEMENTED_UNVALIDATED**. This is an implementation continuation,
not a declaration that tracking assembly, convergence or device testing is complete.

The user approved all six recommendations from the everyday review, using the
existing shared tracking flow and a fresh independent reviewer. Work starts at
`365f082fd552c94e52972f56a75fb4d671e00cb1` in `codex/tracking-verification-1010`.
Local `dev/v10` remains unchanged at `13f1a99871b59d29b3d059f374fc415490da6943`.

## Completed implementation slices

| Input | Change |
| --- | --- |
| `f27573053ef11a4712e1081539dadd26606e3b63` | Recover independently trustworthy native Steps days in the existing Room snapshot; keep shared-authority damage unavailable and strict numeric readers unchanged. |
| `83891b06d21460d9214ebc3eb3528a2b35d34f56` | Preserve healthy live source results after a sibling reader failure, retaining identity/snapshot checks and withholding numeric qualification from failed products. |
| `f473524ef9668e76dfdfb7c282910649b9cfe33e` | Repair access for selected sources from Settings, reuse existing permission prompts, preserve choices and Off, and avoid requiring all-day Location for session-only use. |
| `a508c4ec7052438a375fbda68ce697001b54f915` | Share the existing per-day display reader between live screens, including local midnight/resume refresh, honest complete/recorded/missing states and localized daily labels. |
| `9c2ee7c50e5c37b71302e8564ef81a80a8500e15` | Wire the live screens to that display; distinguish End session from Turn off tracking, retain in-app automatic pause choices and keep End usable after permission loss. |
| `b464f16ea272483966565b65209e4a121c93069c` | Organize supported provider-only Steps through the shared Start/End/Off lifetime; attach repaired physical sources to the same session and recover unpublished first joins without losing saved data. |

All six approved everyday-gap implementations and their focused test sources are
committed. This closes these implementation slices only; execution and complete
tracking convergence remain pending.

## Provider-only Steps and shared source repair

The approved **manual Start** also works when the chosen Steps source uses a
supported on-device provider and the phone has no direct counter. It creates a
session annotation over existing all-day data, with no second counter, copied
observations or unnecessary foreground service. Provider measurements keep their
real windows; crossing the session boundary does not produce an invented count.
The physical automatic-start capabilities are unchanged by this bounded slice.

Session preparation, durable Start acceptance and End/Off use the existing shared
command and startup boundaries. The existing accepted-Start receipt publishes
session visibility even if the original caller is cancelled. Unaccepted
provisional starts are hidden during stop or process recovery.

When another chosen source becomes available, settings/permission repair, app
resume and completion of startup reuse the existing prepared Android Start flow
to attach the first physical run. The session keeps its ID, original start time,
name and original accepted command. New collection starts at the join time;
there is no invented capture before permission or readiness.

If that first join fails before publishing data, the existing coordinator retires
its exact caller authority and removes only the unpublished provisional setup.
The original Steps organization remains usable, and a later foreground repair
can retry with fresh run/token/caller identities. Runtime attempts or any saved
capture evidence instead follow normal recovery, preserving data. The final
review rechecked interrupted delivery after foreground acceptance against the
same pre-application boundary. Started or attempted runtimes, existing segments
and non-first manifests follow ordinary recovery rather than repeated rollback.

## Independent review and simplicity

A fresh review-only Astra agent received all 169 original user messages,
including answers, corrections and the latest approval, plus the source, diff and
the implementation-only boundary. Its final independent static review covered
baseline `365f082fd552c94e52972f56a75fb4d671e00cb1` through the frozen source now
committed at `b464f16ea272483966565b65209e4a121c93069c`.

The review found provider-session cancellation, source-join, interrupted-Start,
startup-opportunity and failed-join lineage defects. Each was repaired and
rechecked, including preservation of shared radio WAL and retained capture
evidence. The final review identified no remaining actionable finding in these
six slices and no unapproved scope expansion. This is static source review, not
execution or provider acceptance. There was no recursive delegation.

Tracing the actual History screens confirmed that the existing organization list
already exposes provider sessions and measured History decorates existing Steps
windows. An additional grouped-history reader change was removed after review:
the current screens do not need it. No new acquisition pipeline, duplicate count
store or periodic UI wakeup was added.

## Authored verification source; execution deferred

- Settings source-selection/permission repair, failed-save behavior and foreground
  source-join triggers.
- Compose End/Off menu/actions, permission-loss End access, daily Steps states and
  session-independent visibility; notification commands for manual, automatic and
  background collection.
- Native Steps damaged-day isolation and live source-union failure isolation.
- Daily display subscription/cancellation, resume, time-zone changes, local DST
  midnight and stale-day rejection.
- Provider-only metadata Start/End, exact command fencing, acceptance publication,
  interrupted Start and source-join/recovery/retry scenarios.

No compilation, tests, schema checks, lint, Detekt, `git diff --check`, provider
activation, emulator/device, battery or CI validation was executed in this
continuation. Written tests are not passing evidence. Earlier dated host results
remain evidence for their exact earlier inputs only.

## Remaining convergence work

All 25 full-chain prerequisites remain open. Full app/Hilt/Room and released-data
upgrade verification, the complete `ciUnitTest`/`ciCheck` gates, provider/device
evidence and final local `dev/v10` integration remain separate outstanding work.
The preserved original candidates and drafts remain intact; no push or cleanup is
claimed.
