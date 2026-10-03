# Independent measured-radio prerequisite review — 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Reviewer:
`/root/fresh_bundle_context_review_0930`. Static source review only. No production
edits, recursive delegation, Git, compiler, tests, schema generation, diff checks,
provider/device activation, CI or integration were performed.

## Result and closed findings

No remaining actionable defect was found in the final **14-path prerequisite
selection**. It is coherent for an unvalidated implementation commit; this is not
approval of the held full History, radio exporter or complete assembly.

- **M1 — closed statically.** The first selection subtracted a floored span from
  an already rounded latest wall timestamp. With callback `10,000,000 ns`, latest
  reading `9,600,000 ns`, earliest `8,900,000 ns` and callback wall time at midnight,
  that placed the parent lower bound after its earliest child's full uncertainty
  interval. Wi-Fi projector lines 398–402 and Cell lines 380–384 now map the original
  callback anchor directly to the earliest accepted reading, matching the unchanged
  child conversion. `AmbientRadioMeasuredInterval.kt:23–30` recovers older clipped
  bounds conservatively with quotient-plus-remainder ceiling; both new DAO queries
  use the same arithmetic. No elapsed-span addition can overflow. Unsupported
  negative bounds remain unavailable. Four added ingress cases exercise this
  counterexample across UTC/Prague midnight, actual child bounds and duplicate
  replay; the added read-side case checks actual Room discovery, API and portable
  bounds. The existing fractional and zero-span expectations were coherently
  adapted. These are authored cases, not executed evidence.
- **M2 — closed statically.** The first retained-reader selection performed a
  page-wide invalid-interval check before epoch/high-water filtering. One stale or
  unsupported row could therefore hide independently valid siblings.
  `RetainedAmbientRadioReader.kt:19–33` and `112–126` now filter epoch/high-water
  first and add a per-row provenance gap for an unsupported current interval.
  Valid rows still pass the existing lineage/authority/checksum path. The two added
  real Room pairing cases at `RoomRetainedAmbientRadioContextTest.kt:217–247` assert
  a surviving valid observation beside unsupported previous/current-epoch rows,
  and distinguish retention from provenance gaps. Existing strict ordinary reads
  still refuse non-retention qualification gaps; optional paired consumers keep
  independently qualified rows with explicit gaps.

The original reviewed read packet was manifest
`e3653e1772bdcde3c1652a388560080dc7678dfe41b30edce28e523ee9d8afab`,
patch `4e8ea8daee910e0998a67bc76e5031f77979efe28aef2a484e08b647f03abc97`.
Those original findings are preserved above; final pins below supersede that input.

## Requirement and dependency trace

The inherited full user requests and approved answers require independent sources,
one measured-date rule, local calendar days, original observation identities and
unsplit real aggregate windows. The new selected-date export behavior (in-date
readings plus optional complete sessions, initially On) is approved separately and
its remaining file representation/callers are excluded here.

- Both projectors preserve accepted indexes, item timestamps, uncertainties,
  per-purpose consent/retention qualification and the existing logical identity
  recipe. Existing replay checks saved checksum/children and returns Duplicate
  before constructing a new fact; older clipped rows are not rewritten.
- The entity change only permits coverage before the saved structural day. Ordered
  nonnegative coverage and the latest observation's exact date/zone/day remain
  required. UTC and Prague's 23-hour DST day fixtures retain one group and original
  child identities. No columns, indexes, migration, file version or daily storage
  partition were introduced.
- Observation-window queries are bounded candidate discovery, including a real
  group's earlier coverage and uncertainty. Existing ordinary observation-time and
  stored-day queries remain unchanged. Imported discovery uses saved portable
  bounds and current semantic revision; it grants no native authority.
- The repositories reuse their existing qualification/composition path and expose
  only an internal observation-window entry point. Native-to-API mapping checks the
  original saved checksum before deriving bounds; source-owned portable conversion
  retains the fact ID and revision. Existing portable V1 accepts coverage before
  the latest observation's structural day, and existing child readers still require
  each full uncertainty interval inside the parent.
- Retained qualification applies the recovered current floor to the effective fact
  and exact aggregate owner, while authenticating all correction ancestors against
  their original approval floors. Registration, authorization, epoch, erase
  footprints, cursor and checksum checks remain in place. No current setting or
  unrelated source grants a historical purpose. Cancellation is not swallowed;
  existing bounded Room reads and typed dependency limits remain in use.
- The shared helper fits the existing `:core:base` dependency of `:stats:data` and
  `:tracker:engine`; no new production dependency, storage abstraction or provider
  activation was added. The paired authority fixture uses existing stats-data
  production readers, without relying on the held R11 History implementation.

## Exact final selection pins

Read packet: `MEASURED_RADIO_READ_SELECTED/MEASURED_RADIO_READ_FILES.json`
SHA-256 `299feef5f8bf57f64e1a0a55e67ba3a0fc4888dec263c820d6a4eccec4c54300`;
patch `b715626f6520ca2f28a161e6de06f06a9deffe6181b322fb78e9e4d3c0275534`;
boundary `acbd68795c9e45fc668a7b51b6b6e303e8f1624a8c94f2db8f711784617248f6`.

Source packet: `RADIO_MEASURED_SOURCE_FILES.json`
SHA-256 `0f6e4ca60452d81b0df9ad358acac189b54a3e5ae5286408179e929a522412c9`;
patch `516dda7e74cebb893d99272ae3f2481d34ba106d24695dfeaa7bc002e846a6f7`;
boundary `ebee2f9475c84de6cc1119069c092af018374847ecdb928b8ab1b14725a51d85`.

All eight read-packet before/after snapshot hashes and actual working hashes were
independently checked against the manifest. All six source-packet actual and before
snapshot hashes likewise matched. Mixed-path acceptance is limited to the selected
after snapshots and patches, not whole dirty files. The manifests retain exact
repository paths and distinguish working-byte hashes from LF selection hashes.

| Selected file | SHA-256 of selected after bytes |
| --- | --- |
| AmbientRadioMeasuredInterval.kt | `b1c474346df34d9d1593568019e5db92c56273ac0a1d8729bf2bd45553670dd4` |
| AmbientWifiFactDao.kt | `241e698b3788457663bd66940a218b48409b0aa8c44735d8674c8eb4d8ec6c05` |
| AmbientCellFactDao.kt | `73be717deba220c66e16cf5771b2dbf31bb564ffa553459a4c5af73a08b07d2c` |
| RoomAmbientWifiRepository.kt | `b4734008da7f500a39bd681237d7c1f82c7ef81e6c5a638753dd82e4df52df13` |
| RoomAmbientCellRepository.kt | `b00925819664a4af723c0a28c7e78f5307a4abd12d7fd3ed7f83bab283a540e4` |
| RetainedAmbientRadioReader.kt | `74cfe88966e3675753bdee73373ce7f6265e4f891a389d318914e66eb4cb1107` |
| AmbientRadioMeasuredBoundsRoomTest.kt | `c6d34eb3e688842ce3ec11f5482692d9ee8c53026419bdfcc56b3b6f3ade32b5` |
| RoomRetainedAmbientRadioContextTest.kt | `00ba5053a674a05c078882fa13f1f09bafd1df1db4110dd99e0a99b19ae21743` |
| AmbientWifiFactProjector.kt | `3c77707fc64c280752a2e9651a43ad0f3c0da4987d950f78c0ea7419f2737884` |
| AmbientCellFactProjector.kt | `249bc9beeb1f768ba0616c1df93a3ec03f4c69741457d64317e3414f01181ce9` |
| AmbientWifiFacts.kt | `afe7824313d8c7f49cf8ab6afee48754d6793a1f5f500fc3a667460d2f203362` |
| AmbientCellFacts.kt | `3ecc350e5e3f59da82e495693bdc807bcf7ad06ff2a39b770d971e449d04e3bd` |
| WifiDurableSourceIngressTest.kt | `79f0e39ba6b01165cc0eee640b65c1023043d44a8f56c8771f7af6607e280490` |
| CellDurableSourceIngressTest.kt | `5db2c4d6d07d23ae283c3de629361040136a3d723f3ccdb0ef2cf8598a764af2` |

## Remaining evidence limits

Fifteen new cases are authored and unexecuted: eight actual ingress cases, five
Room bounds/discovery cases and two real authority/pairing cases. Two existing
bounds cases were adapted. Engine tests assert their actual stored children;
read-side bounds tests assert API/portable bounds but do not execute a whole radio
file round trip. The paired cases establish meaningful test source for M2, not a
passing runtime claim.

Full History grouping/UI, selected-only radio file representation, complete-session
switch/callers, imported overlap organization, whole held exporters, schema/Hilt
convergence, provider/lifecycle/battery/device evidence and integration remain open.
All 25 assembly chains remain pending. No complete-feature or release claim follows
from this source-only prerequisite review.
