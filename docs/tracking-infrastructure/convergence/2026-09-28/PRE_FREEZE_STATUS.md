# Convergence input audit — 2026-09-28

**Status: NOT FROZEN. Assembly incomplete. IMPLEMENTATION_ONLY.** This is a static inventory of the inputs available for the requested convergence freeze. It is not the freeze receipt and does not lift the validation stop in `AGENTS.md`. Do not run Gradle, compilation, tests, lint, Detekt, Room schema drift or generation, `git diff --check`, device or UI checks, battery checks, CI, release checks, or rollout validation until the implementation assembly gate is actually complete.

The exact machine-readable inventory is [PRE_FREEZE_INPUT_AUDIT.json](PRE_FREEZE_INPUT_AUDIT.json). It lists **every** candidate-only commit SHA, changed test-source path, registered worktree, and all 54 local branch refs, plus status and byte hashes of live draft files. Worktree/draft state was captured before these audit files were written; the full local-ref list was added during independent review. The audit itself is not part of the proposed implementation input.

## Inspected refs and selection state

### September 30 attribution correction

The user's original correction named **master**, not main: “nothing that is not
on master was ever released.” The table below incorrectly attributed a main
baseline decision to that message. Main and origin/main currently resolve to the
recorded `59fda665c7` snapshot; a read-only remote heads lookup found main and no
master. Whether main is the branch the user meant is awaiting clarification.
Keep the recorded snapshot and schema inputs as audit evidence, but do not treat
this earlier wording as approval of the released migration baseline. No schema
generation, migration execution or validation was performed for this correction.

| Ref or input | Exact inspected commit | Disposition |
| --- | --- | --- |
| Clean local `dev/v10` | `13f1a99871b59d29b3d059f374fc415490da6943` | Intended integration base; unchanged. |
| Released `main` | `59fda665c7099d10b16aa60f505de88bd05d3131` | Actual released-data baseline, per the user's correction that only `main` was released. |
| `codex/source-native-map-context-root` | `03e724d096a96df19371097dc80d8988c8c6e344` | Current continuation candidate; 71 commits absent from `dev/v10`, all enumerated in the JSON. Source is `IMPLEMENTED_UNVALIDATED`, not a frozen final input. |
| 22 other preserved branch tips outside the base and candidate ancestry | Each exact ref/SHA/disposition is in `local_branch_tips` in the JSON; 17 have registered worktrees and five are additional local refs. | Preserve and compare with the candidate. A copied or reconciled hunk is not proof that an entire branch is integrated. No branch is deleted or silently accepted. |
| Dedicated convergence branch | **Absent.** | `TODO-ASSEMBLY-008` is open. No accepted, composed convergence commit exists. |

The [September 27 selection manifest](../../reconciliation/2026-09-27/SELECTION_MANIFEST.json) remains the source of earlier path and overlap dispositions: ROOT, reviewed portions of LIFECYCLE, UI and AUDIT were selected; Location V2 execution and the 22-file radio V2 draft were held. That selection is a continuation checkpoint, not a global freeze. Later candidate commits and live drafts still require comparison against it. Historical `WORK_ITEMS.json` labels three narrow slices `completed_accepted_slice`, 17 `partial_implemented`, and five `planned`; those labels do not satisfy the newer full-chain assembly definition in [PREREQUISITE_TRACE.md](../../assembly-coverage/2026-09-28/PREREQUISITE_TRACE.md), which marks all 25 dependencies pending.

### Live draft dispositions

The JSON records the exact path, Git status, byte count and SHA-256 of each of these **56** draft files in five dirty worktrees. None is included merely because it exists. All are preserved in place; the earlier [preservation archive](../../reconciliation/2026-09-27/README.md) covers its own September 27 snapshot and is not a substitute for this newer live inventory.

| Worktree / ref | Dirty paths | Current disposition |
| --- | ---: | --- |
| `codex/radio-auto-capture` at `13f1a99871b59d29b3d059f374fc415490da6943` | 22 | Held V2 radio draft; exact-generation writer/read/retention compatibility remains open. |
| `codex/location-product-query-20260925` at `1c17c27e15442b678e5e55139a55dd1ddc4b6b25` | 10 | Held Location proof/reader/schema draft; reconcile with final retained authority. |
| `codex/unified-tracking-surface` at `487a2fc98f562e5ca418e55a0d34b68d9596a206` | 10 | Held separate Dashboard ambient-card/query draft; committed UI selection was narrower. |
| `codex/audit-reconciliation-20260925` at `4facc94f402eb991ed8c51597a563f30d963c5d6` | 8 | Held Tracker UI draft pending source-aware product assembly. |
| `codex/audit-finding-fixes` at `a8fd308df926bbaa3c8598cc04bdff463bf97e73` | 6 | Held CI/architecture/release draft; assess unique relevant fixes separately. |

The six protected root paths and frozen older importer/awards drafts named in the [handover](../../handover/2026-09-15/README_START_HERE.md) remain excluded and preserved under its quarantine rule. Do not apply their historical contents wholesale or assert that missing original bytes have been recovered. One stale/prunable worktree registration is left untouched.

## Schema input, not schema freeze

Released `main` contains `sbase/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt` **version 12** and `sbase/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/12.json` with seven checked-in entities. The candidate uses `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt` **version 28**. A static count finds **197** `::class` entries in its `@Database(entities = [...])` declaration but only **79** entities in the checked-in `28.json`: the schema is already visibly stale. This count is a source comparison, not a Room schema check. Its `LAST_RELEASED_ACTIVE_DATABASE_VERSION = 27` statement is also inconsistent with the actual released branch. The candidate has `MIGRATION_27_28` and older in-development migration/import machinery, but no final populated released-v12-to-final-schema reopen proof. Candidate v27/v28 fixtures are not released-user data fixtures. The JSON pins Git blob IDs, SHA-256 and size of both released v12 inputs and SHA-256 and size of the candidate declaration, migrations, and checked-in 28 schema. These files are not frozen: approved all-day Steps, radio child identities, retained Location authority, backup/restore and other pending contracts may change them. Preserve unknown development database files for recovery. Do not regenerate or verify schema during this phase.

## Capability limits in the inspected candidate

- The 25 source-to-product prerequisites are still pending. Existing 64-subset and five-level tests exercise models; they do not prove provider → authenticated receipt → retained fact → selected history/UI/file → retirement for every solo source and supported purpose.
- Steps still has both `stepsEnabled` and `ambientStepsEnabled` in candidate settings. The approved single all-day Steps choice, session annotation behavior and supported background provider schedule are not complete.
- Wi-Fi and Cell runtime still write an empty `WITHHELD_RADIO_IDENTIFIER_TOKEN`. The approved available network names/access-point IDs and tower IDs are not yet retained as usable child observations or exported. Active-request budgets, historical per-child consent and optional host behavior need final composition.
- Dashboard permission return still invokes `requestManualStart()` anew. The approved one-use original Start across app/widget/shortcut repair with End/Off/source-change fencing is not complete.
- System-rearmable sessionless Location, shared temporary feature leases, CONTROL-only automatic authority, source-aware product surfaces, complete data-only backup/restore, final privacy cleanup and released-data migration remain incomplete as detailed in the prerequisite trace and [implementation checklist](../../IMPLEMENTATION_TODO.md).
- The final supported capability matrix cannot be asserted from uncompiled, unrun source. Android/provider limitations and gap behavior still need the approved production composition and later physical-device evidence.

## Authored tests versus missing proof

The JSON enumerates **196 test-source paths changed by the candidate relative to `dev/v10`**, each with its Git status: 64 added, 131 modified and one deleted (`SunSetRiseTest`). This is a changed-file inventory, not the total repository test count, an execution result, or sufficient coverage; the deleted file is not an authored test. By top-level module: tracker/engine 81; core/base 24; app 19; feature/import-export 19; stats/data 19; feature/dashboard 6; feature/map 6; feature/statistics 6; data/preferences 4; sensor/activity 4; feature/tracker 3; tracker/api-module 3; feature/game 2. No test or compilation was run for this audit.

[SCENARIOS.md](../../assembly-coverage/2026-09-28/SCENARIOS.md) specifies S01–S25 for solo sources, all subsets, join/leave, lifecycle, privacy, recovery, migration and product surfaces. Those are acceptance prose, not 25 executable end-to-end cases. The trace names concrete authored tests and missing production/test chains for each prerequisite. Final source-to-product tests, the populated released-v12 migration fixture and complete external-control/permission cases remain to be authored before the assembly gate can close.

## Gate decision

`TODO-ASSEMBLY-001` through `TODO-ASSEMBLY-009` remain unchecked. `WI-ASSEMBLY-GATE-001` cannot be marked complete. In particular, there is no selected and clean convergence branch, final schema, closed capability contract, complete route-level test source, or frozen command/device/environment receipt. Therefore **no convergence input is frozen** and the implementation-only stop remains active. Finish the missing production and test-source slices, reconcile held inputs, compose the dedicated branch, perform another independent static assembly review, and only then issue a freeze receipt that declares the gate complete and records the validation environment and commands. This audit authorizes none of those deferred validation commands.
