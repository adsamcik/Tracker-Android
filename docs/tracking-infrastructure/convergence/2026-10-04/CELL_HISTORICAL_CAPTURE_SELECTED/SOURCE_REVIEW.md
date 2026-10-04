# Independent source review — historical Cell capture and shared radio receipts

Status: **BLOCKED by R7 and R8**, IMPLEMENTED_UNVALIDATED. No compilation, tests, lint, Room generation/drift, gates, Git mutations, providers, devices or integration were executed. All 25 full-chain assembly prerequisites remain open.

## Exact input

- Root FILES.json: `4454dcbd97ab7bd55b874a936cdae28c1333b0234753b62516629b4bf37f322b`.
- Root SELECTION.patch: `a6eb461b93892a3cb699ecba78ff65c826fd5cc76d8a3e1c326c3ef70f53680b`.
- PREDECESSOR_RECONCILIATION.patch: `0a4a417c4aaa908b2a0b9dd09639a9996ef7a5470b356d0c24b076eccd511c5f`.
- Frozen author PACKET.json: `91d3a5723e6809c61abd27269eb2a8804e1d0ff369304154bc0f23b18ebc8607`.
- Frozen AUTHORING.patch: `f34faec3a2db0f0d476ac7119bc1c6d4f40b4da73cae38491243a8d39b687643`.

Static byte comparison confirmed all 23 author after snapshots and all 47 frozen dependency snapshots match their recorded hashes. Root's selected files preserve these after bytes with four explicit predecessor inputs; those four narrow inputs were independently reviewed: mandatory Wi-Fi child authentication in lifecycle/exact lookup, malformed Cell deletion-fence entity recovery, and fixture repairs that use real settled authority and check fact/WAL authentication. No unrelated source selection or new architecture was found there.

## R7 — Cell lifecycle and exact lookup skip mandatory saved children

Selected `DefaultSourceLifecycleProofAdapter.kt:1880` and `DefaultSourceFactQueryRepository.kt:973` authenticate the original WAL and reconstructed per-purpose indexes, then call the fact/index overload of CellWalCarrierAuthenticator. Neither reads `cellSessionChildren`. Thus deleting or corrupting a saved mandatory v6 tower row can still yield a Proven materialization or a trustworthy exact fact, while `CellHistoryComposer.kt:666` reads the same saved child rows and fails their authentication. The existing authenticator overload at `CellWalCarrierAuthenticator.kt:313-327` already validates their exact identity, values and count. This is the same missing dependency the Wi-Fi predecessor repair fixes.

Use the existing bounded child reader plus existing authenticator overload in both Cell helpers, preserving per-purpose selection equality and aggregate-owner validation. Cell's existing delivery bound is 256 items, so its lookahead is 257 rather than Wi-Fi's 65. Author a real saved-child deletion/corruption cross-reader scenario; do not claim a pass before convergence execution.

## R8 — source erase still treats valid same-run policy siblings as collisions

Selected `CellCapturedFactMaintenance.kt:844,874-879` and `WifiCapturedFactMaintenance.kt:423,453` key retained event authorities by `(eventId, logicalRunScopeDigest)` and then compare the entire RetainedCaptureEventIdentity. That identity also includes sourcePolicyRevision and captureConsentEpoch (`RetainedCaptureScopeIdentity.kt:28-34`). Two independently qualified historical groups for the same callback and service run can legitimately have different policy revisions. The coordinator appends policy-reconciliation manifests while retaining the same run (`AuthoritativeSessionCoordinator.kt:2291-2353`); its physical radio registration can remain unchanged. Both fact receipts can be retained under the correct unchanged primary key, but source erase rejects their policy difference as DELETION_FENCE_CONFLICT.

Keep the separate common raw-event check of ordinal/WAL identity. Distinguish the independently authenticated policy/consent event authorities during in-memory collision/deduplication, while deduplicating the actual run deletion fence by its existing scope. No new storage or API is needed. Cover same-run, two-policy receipts through actual source erase, in addition to the existing different-run fixture.

## Accepted source boundaries outside those findings

- Existing fact primary keys, logical fact IDs and original raw callback WAL identity remain unchanged. The nonunique secondary event index is required and separately accepted in RADIO_RETENTION_EVENT_INDEX_REVISED_SELECTED; the initial rejected schema packet remains preserved.
- Receipt persistence and both writers use exact fact keys with an independent common original event identity guard. Multiple fact receipts are permitted without arbitrary first-event selection or resurrection of an exactly retained fact.
- Lifecycle event-only receipt lookups are bounded to two candidates under the requested run/policy/consent; ambiguity fails. Exact fact lookup uses the requested fact primary key. The legacy unscoped byEvent uses bounded singleOrNull and has no production callers.
- Cell v6 capture obtains historical per-reading groups from the existing authorization/demand timeline, independently validates each session's manifest/policy/consent/provider interval, keeps original item indexes and tower details, and never rewrites the admitted WAL to impersonate a historical callback. Older payload semantics remain on the original path.
- Group writes stay in the existing Room transaction; cancellation or a thrown late writer rejection rolls back sibling writes. Explicitly deleted/expired historical groups do not suppress independently valid sibling groups. Session cutoff applies to measured data for v6, without denying valid delayed admission merely because it arrived after End.
- Cell history requalifies the original callback separately from each captured purpose and checks saved children. Portable retention bounds use the authenticated original wall anchor. Different local measured days remain original measurements rather than fabricated continuous scanning.
- Thirteen authored cases include actual v6 ingress/Room writer, independent historical/background siblings, cancellation, source deletion, retention receipts, portable midnight scope, shared receipt ambiguity and exact history lookup. They are meaningful source fixtures but all are unexecuted. The Wi-Fi retained-source erase case is a storage/consumer fixture, not proof that Wi-Fi historical capture is implemented.

## Explicit remaining scope

Equivalent Wi-Fi historical capture, mixed future-item envelope rejection, sparse selected-date export selection, full provider/lifecycle/device/battery coverage and convergence/integration remain outside this packet and open. R7/R8 require a preserved separate repair selection and independent recheck before claiming this runtime packet accepted. This receipt does not declare any whole source chain or feature complete.
