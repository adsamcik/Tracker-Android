# Independent source review: live radio freshness

**Decision: REJECTED for F1 below. IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.**

Reviewed the original user requirements and settled decisions, the five immutable BASE/WORKING_BEFORE/SELECTED paths, their selected patch, and 24 frozen dependencies. Static inspection only. No production edits, recursive delegation, Git mutations, provider activation, compilation, tests, schema gates, lint, device/UI/battery, CI or integration work occurred. Eleven authored cases remain **NOT_RUN**. This receipt does not close any full-chain prerequisite.

## Exact input

- Source BASE: `fa340240624710a5e2c77cd8883492515f4b6b39`.
- Frozen dependency HEAD: `03fe9a70fb5e483b036887c745bd570adcc66769`.
- FILES.json: `0fa98ff6b8fbb0f254dac7de6f709913cd7611265c31273f994abbb69b96994f`.
- SELECTION.patch: `ffd4f37d703e9ad73e968939dfa953934482e7172b67c420c56e9b801e7c4610`.
- DEPENDENCIES.json: `93bc3bbf6c204226edd970548402538b6f21ac73690e8a630fbc6ac41840ab79`.
- SOURCE_BOUNDARY.md: `c47bd70f7875523b6ccd296257c1d0362dedfaea2bc326e8c9cfb2e5a7234d99`.
- TEST_SOURCE_INVENTORY.json: `d89f1dea99d79e77eed1d2f1e32f3fdbb04586f257962015e6e671a8bb6517ef`.

All five available BASE, WORKING_BEFORE and SELECTED snapshots and all 24 dependency snapshots match their manifest hashes. The manifest also records actual-after hashes; there is no WORKING_AFTER snapshot directory, so those moving-file hashes are not independent acceptance inputs. Held thermal and test drafts remain excluded.

Additional exact source context was read from the stated dependency HEAD, with normalized LF SHA-256:

| Path | SHA-256 |
| --- | --- |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/SharedWifiSourceController.kt` | `57ed0d1121a950b4a73ffbac4c6019530b5bec3cbf3169d445d24c406db0771e` |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/SharedCellSourceController.kt` | `4e32e04fe943c9d138ff6f1c36f2dc24f933eb17f2ba1d6b21836dc0a1e1c5aa` |
| `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/RadioPurposeItemQualifier.kt` | `50f52cfd58e71d1613302c753a4d131375a984afc80bb7e1f8fc695ed71eecfd` |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ingress/DurableSourceIngress.kt` | `2d4c86ec144d7cf5ea9b0b05ed3cb9ddb6f530ca2001e9fd98fc161964618775` |

## F1 — Background-only admission can satisfy a stricter live capture

**Correctness blocker, both sources.** Selected `ConnectivityRuntimeSupport.kt:222–240` checks current capture membership and the 60-second desired freshness target, but does not check that the observation qualifies for that capture's own immutable maximum age. Its callers mark the live request satisfied at selected `WifiSourceRuntime.kt:1120–1132` and `CellSourceRuntime.kt:1098–1116` after any successful shared delivery admission.

The real route preserves a deliberately broader physical observation set:

1. `SharedWifiSourceController.kt:355–361` and `SharedCellSourceController.kt:351–355` use `demands.maxOf(maximumAgeMs)`, keeping readings useful to an independently authorized background owner.
2. `RadioPurposeItemQualifier.kt:160–171` separately enforces each immutable demand's requested time, retirement boundary and maximum age. A 30-second-old item with a 10-second custom capture contract and a 10-minute background contract qualifies only for background.
3. `DurableSourceIngress.kt:892–913` retains the exact callback header and permits a durable shared delivery for eligible background items even if there are no eligible capture items. A durable receipt therefore establishes delivery admission, not capture admission.
4. The new helper returns true for that same current-owner, same-boot, 30-second-old header. Both actors cancel their live request and mark their finite budget satisfied, although the source cannot produce a saved capture fact from this item.

This violates the approved separation of desired freshness from genuine stricter legacy/custom contracts and the requirement that independently valid sibling purposes remain usable. The source's declared header still has capture membership by design; reading that membership as item qualification is incorrect.

**Smallest repair:** retain the current owner, plan, boot, processing-age and earlier-request fences, and require an item that also qualifies for the exact current capture demand. Reuse the existing immutable demand/per-item authority rules through a bounded existing repository seam; do not narrow the physical payload or invent another allowance, table or universal proof framework. Missing or unverifiable capture constraints must leave the live request eligible. Add one actual actor regression per radio with authentic shared authorization and independent background/capture age contracts, showing that the background item remains admitted, the capture has no qualified item and the queued active request is still available. Also preserve a positive capture-qualified case.

## Remaining reviewed behavior

No additional concrete source blocker found in the selected delta. The successfully admitted prepared Wi-Fi delivery retains its identity, original clocks and existing cutoff salvage. Cell retains its snapshot identity and outstanding-request completion. Provider receipt time does not refresh an observation; actor processing delay counts toward freshness. Exact current owner/plan, cutoff, retirement, same boot and old request context fences are preserved. The Wi-Fi pre-reservation budget check blocks an already cancelled queued wakeup from spending an unused allowance. Existing finite budgets, carried deadlines, platform backoff and preference-based request ownership remain in use; no periodic freshness cadence or new persistence was introduced.

The eleven new scenarios use real runtime actors with mocked provider/registration/admission boundaries and a preference-allowance test double. They support source review of their stated clocks, owner changes and cancellation behavior, but they do not exercise the independent per-purpose durable admission subset that exposes F1. No executed results exist.

Preserve this rejected packet and receipt. Review a separately pinned repaired selection before root selection or commit.
