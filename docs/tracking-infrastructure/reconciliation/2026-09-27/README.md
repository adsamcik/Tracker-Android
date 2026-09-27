# Implementation input reconciliation — 2026-09-27

Task: "Reconcile implementation inputs. Inventory committed candidates and preserved drafts, select their final versions, and resolve overlapping changes without losing existing work."

Status: **input reconciliation completed; IMPLEMENTED_UNVALIDATED**. This closes input selection and overlap resolution for a coherent continuation checkpoint. The complete tracking implementation, global convergence freeze, execution gates, provider/writer activation and integration into dev/v10 remain unfinished.

## Selected continuation

Main remains clean local `dev/v10` at `13f1a99871b59d29b3d059f374fc415490da6943`. Continue in `codex/source-native-map-context-root`, rooted at `63ef296026e440e4a5667235fe0769a03004eb47` plus the inventory checkpoint `3a84f5878a`. No source ref was reset, merged away, renamed or deleted.

| Input | Final selection |
| --- | --- |
| ROOT `63ef296026…` | Keep newer adaptive Location, common accepted receipt/purpose associations, native radio references, resolver and ambient DI |
| LIFECYCLE `f786563167…` | Latest V1 lifecycle/carrier, Pressure correction/retention, radio/event retention, canonical receipt and bounded proof-query families; exclude incompatible Location retention reader/pruner and dependent test hunks |
| UI `487a2fc98f…` | Two committed source-aware composition/Stop changes; exclude dirty separate ambient card/polling |
| AUDIT `4facc94f40…` | Six committed corrections, with service retirement reconciled into ROOT's single live teardown/background host |
| LOCATION `1c17c27e15…` | Preserve V2 committed input; defer execution/catalog broadening until exact binding transport is assembled |
| RADIO, 22 dirty files over main | Preserve entire V2 draft; defer execution/import/writer broadening until exact-generation history, retention and proof agree |
| Older audit `a8fd308df9…` | Preserve all 21 fixes; retain useful unique behavior/test gaps as explicit adaptation work, not an assumed duplicate |

The manifest names **180 selected paths, including 55 test-source paths**, all 54 local-head dispositions, all 295 non-ancestral commit dispositions (including merge-only residuals), all 56 draft dispositions and all 47 overlapping-path resolutions. Counts describe selection, not passing tests or implementation completion. `RESOLUTION_NOTES.md` explains the important choices and exact continuation gaps.

## Preservation receipts

- `INPUT_INVENTORY.json`: 51 registered worktrees, 54 local heads, 295 distinct non-ancestral commits and original status/byte hashes for 56 draft files in five dirty worktrees. One stale/prunable registration remains untouched.
- `REFERENCE_AND_PRESERVATION_SCOPE.json`: 137 original refs: 54 heads, 70 remote-tracking refs, nine tags and four Codex snapshot refs; five unrelated `.external-wip` files recorded and untouched. No fetch or push.
- `PRESERVED_DRAFTS.zip`: immutable byte copy of all 56 live drafts, with `DRAFT_INDEX.json`; archive/member hashes are recorded in the final manifest. This is a developer preservation artifact, not a Tracker export or app backup format.
- `SELECTION_MANIFEST.json`: authoritative final input/path/draft/ref selection. `OVERLAP_RESOLUTIONS.json` covers every inventoried overlapping path.
- `MERGE_PREVIEW.json`: historical exploratory union, explicitly superseded; never apply its abandoned V2/retention versions.
- `PRESERVATION_RECEIPT.json`: final static byte/ref/status comparison. No runtime claim is made by this receipt.

Ancestry proves reachability; patch equivalence is narrower source evidence, not a behavior proof. `git cherry` omits merge-only residuals, which are explicitly recorded in the selection manifest. Older branch dates/titles were not used as proof that their behavior was retained.

## Protected and historical material

The six protected root paths in `handover/2026-09-15/QUARANTINE.md` and frozen importer/awards drafts are excluded from application/staging. Their historical receipt exists, but the bounded current repository-doc search found no old quarantine payload/archive/bundle; it cannot recover original draft bytes by itself. Do not regenerate missing originals or claim that they are in the live-draft archive. The original sources and unrelated work remain preserved.

## Phase boundary

Allowed: production/test-source authorship, static source review, preservation inventory, exact-path staging and local `IMPLEMENTED_UNVALIDATED` commits. No Gradle, compilation, executed tests, lint, Detekt, schema generation/drift, diff-check, device/emulator/UI, battery, CI, release, activation, dev/v10 integration or push occurred. The independent review is static, not a substitute for later execution or on-device evidence.

Settled product decisions remain in `planning/2026-09-26-provider-freedom/DECISION_CLOSURE.md`: 48h CONTROL-only retention; optional default-off Wi-Fi/Cell notification host; only the selected export format; clean all-data backup required, restore choice pending. This checkpoint changes none of those decisions and introduces no new format or restore design.
