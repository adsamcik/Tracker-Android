# Independent Cell authorization-rotation and portable-settlement trace

Phase: IMPLEMENTATION_ONLY. Status: IMPLEMENTED_UNVALIDATED. Review only; no source edits, Git mutations, compilation, tests, gates or provider/device activity. This note supplements the preserved rejected runtime receipt and does not change it.

Reviewed original author PACKET.json `91d3a5723e6809c61abd27269eb2a8804e1d0ff369304154bc0f23b18ebc8607`, using its exact after/dependency snapshots.

## R9 hypothesis — first historical authorization clips later readings

**Not reproduced by source trace; no implementation change recommended.**

Example: an unchanged capture demand is present in authorization revisions 1 and 2; an unrelated demand change causes revision2; capture ends in revision3; one callback arrives in revision3 containing its authorized readings from both earlier revisions. `RadioPurposeItems.historicalMembers()` keeps revision1's member, because its demand ID is unchanged. Cell's callback fallback retains that original member.

This first member authenticates the immutable session/manifest/policy/consent binding; it is not used as a next-revision cutoff for v6:

- CellWalQualificationAdapter.kt:360-368 obtains `cellPurposeWindow` and uses its `continuousUntilNanos`, overriding the next authorization's start.
- CellPurposeItemQualification.kt:70-87 walks backward/forward over consecutive timeline revisions containing the same demand; start/end also intersect real demand and provider retirement bounds. It selects every independently qualified item within that continuous interval.
- CellWalQualificationAdapter.kt:498-504 uses `continuousFromNanos` and `continuousUntilNanos` for the fact's temporal authority, including consent end. Both original per-reading indexes reach classification; the admitted WAL remains unchanged.
- CellCapturedFactWriter.kt:738-764 deliberately skips exact start/end snapshot equality for v6 purpose-qualified data and validates those same continuous bounds.
- CellCapturedFactMaintenance.kt:1583-1606 and 2586-2618 use the same continuous bounds rather than the first member's next authorization start.
- CellHistoryComposer.kt:562-568 and 654-661 checks the same purpose window and overrides next-authorization end for per-reading facts; its original member fingerprint is still independently authenticated.

Therefore an unrelated authorization rotation retaining the exact capture demand does not clip later readings. A real capture/manifest/policy change replaces the immutable demand and remains an independent group. Merely changing Cell to use the latest member for Wi-Fi parity would alter its stored fact authority without fixing a demonstrated bug. The existing first member is valid under this contract. No execution result is claimed.

## R10 — no-observation session export waits for unrelated physical retirement

**Concrete open source finding.** This differs from the Wi-Fi all-demand guard and should not be described as automatic parity.

CellCapturedPortableTransfer.kt:1065-1089 builds evidence-backed settlements from this selected session's authenticated facts and WAL carriers, then skips the separate settlement guard for them. Consequently a session with saved evidence is not blocked by the specific guard below.

For a completed session A with no captured observation/WAL but a valid source completeness row, lines1090-1099 call `authenticatePortableCellSettlement`. At1664 it requires `ProviderRegistrationGenerationEntity.hasExactPortableCellSettlementShape`. That predicate at1778-1795 requires physical `retiredAtMs` and `retiredElapsedRealtimeNanos`; COMPLETE additionally requires physical `STATUS_RETIRED` and ORDERLY_STOP. If session B or independently chosen background collection continues using the shared physical Cell registration, it remains ACTIVE. A's exact capture demand may already be retired and its own closure acknowledged, yet exporting A's truthful no-observation/gap entry fails as unverifiable until that unrelated owner stops.

The authorization loop at1744-1751 correctly filters selected demands to A's exact logical ID/run; it does not impose Wi-Fi's all-demand membership condition. The demonstrated coupling is the mandatory physical-retirement precondition on the no-evidence path. Resolve it using the existing exact per-purpose demand/closing authority for the selected session; keep actual shared provider authentication and conservative malformed-authority handling. Author an actual Room case with A finalized and no observations, its demand retired, and B/background still keeping the physical registration active. Existing historical Cell fixtures all have evidence and do not cover this path.

R7 and R8 from the original runtime review remain open until separately frozen repairs are independently rechecked. All 25 full-chain prerequisites and final gates remain open.
