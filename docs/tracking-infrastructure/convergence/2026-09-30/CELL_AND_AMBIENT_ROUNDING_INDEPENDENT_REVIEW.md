# Cell downstream and ambient rounding: independent static review

Status: `IMPLEMENTED_UNVALIDATED`. Review-only source inspection; no production edits in the reviewed
root slice and no executed gates, schema tools, Git, devices or provider activation.

## Accepted statically

- `CellWalQualificationAdapter.kt` authenticates version-six min/max items inside the physical
  registration lifetime and receipt. A later invalid sibling no longer discards earlier eligible data.
  Version five keeps its original whole-callback authentication path.
- `CellWalCarrierAuthenticator.kt` computes version-six parent upper bounds from accepted child
  wall times and rounding uncertainty. `CellCapturedFactClassifier.kt` stores that qualification;
  replay reconstructs the original WAL clock uncertainty rather than repeatedly adding rounding.
- `CellHistoryComposer.kt` reconstructs callback/authorization snapshots and uses the existing pure
  qualifier plus `cellPurposeWindow`, requiring exact chosen-purpose members. Membership can
  survive authorization rotation while a new consent cannot claim earlier data.
- `CellWalQualificationAdapterTest.kt` authors physical retirement/sibling retention, fractional
  uncertainty, near-latest sibling parent bounds and unchanged replay fixtures.
  `CellHistoryRepositoryRoomTest.kt` authors real retained per-reading tower history across rotation
  and rejection of a fact claiming permission before the demand began.
- Ambient Wi-Fi and Cell projectors extend their upper uncertainty for fractional accepted child
  mapping. The two real ingress fixtures target a near-latest rounded sibling and exact duplicate
  replay, with no change to the legacy whole-summary path.

## Concrete remaining companion

`RoomAmbientWifiRepository.kt:285` and `RoomAmbientCellRepository.kt:293` copy nominal native
coverage start into the API/portable parent. Native child lower uncertainty can precede this nominal
value: the new tests explicitly allow child lower bound at
`fact.coverageStartTimeMs - fact.wallTimeUncertaintyMs`, and retained readers already enforce that
measured lower bound. The exact portable file contract must carry the measured lower bound or
otherwise align the source-owned representation. The stricter imported detail predicate must not
be weakened to tolerate inconsistent native exports. Reported to the parent before dependent edits.

Midnight-crossing group policy and historical capture groups remain separately pending boundaries;
this review does not accept those full chains or final implementation assembly. Compilation,
Room/Hilt generation, authored test execution, lifecycle/device behavior and integration readiness
remain deferred to convergence.

## Coordinator companion repair

The native API conversion now exposes measured coverage start (nominal start
minus saved uncertainty), rejecting impossible negative bounds. The existing
portable constructors carry that full interval. Observation-date/day selection
is unchanged. The coordinator statically reviewed the narrow conversion and
the three focused actual-Room conversion/portable interval scenarios alongside
the two ingress/replay scenarios. No schema/dependency or import relaxation is
added; all tests remain authored and unexecuted. Ordinary-reader wiring is a
separate slice and excluded from this source selection.
