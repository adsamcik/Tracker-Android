# Native Wi-Fi carrier version — source boundary

Status: **IMPLEMENTED_UNVALIDATED**. Authored 2026-10-03; no build, compiler, test, lint, schema generation/drift, device, UI, CI, provider activation, or integration was executed. Root owns exact staging/commits and the independent review.

## Approved behavior

One nullable `wifi_captured_fact_revision.payload_version` records the actual observation carrier for new native writes. It distinguishes v5 summary facts from v6 scans whose saved network list must be complete. It is an existing source format version, not a signature, import restriction, new pipeline, or additional user control. User-edited imported files retain their existing optional-detail structural rules.

Only current `main` was released; candidate schema29 has not shipped. Its existing 28→29 migration gains this nullable column. No schema version30 or generated schema artifact was created. The existing development preflight contains incompatible older candidate29 databases before Room opens, preserving their files.

Older facts remain null. No format is inferred from child presence and no unauthenticated SQL backfill changes their checksums. Null uses the exact previous `wifi-captured-effect-v1` parts/domain; nonnull uses a new digest domain which binds the version to the existing effect. Corrections preserve the initial marker, including null.

## Static trace

- New writer facts store the admitted WAL's actual version. A correction reads its exact prior revision's marker inside the existing Room write transaction.
- Entity integrity, writer lineage and maintenance lineage treat the marker as immutable. Shared `WifiWalCarrierAuthenticator.hasExactWalLink` checks known marker/WAL parity. Existing history and lifecycle readers already use that shared predicate and retained lineage check.
- Native portable and retention audit reuse that predicate with the current revision's bounded child query (64+one lookahead per fact). A v6 missing-all, partial, changed or mismatched list is rejected; v5/null legacy summaries remain compatible. This adds finite per-fact child reads to the already bounded audit; performance has not been measured.
- `SourceEventWalDao` already pins WAL referenced by retained native radio facts. Ordinary expiry cannot detach those facts. Forced/corrupt missing WAL remains unavailable through the existing source reader; this change does not grant native authority to an unattested summary.
- Held source-file Wi-Fi export discovers `fact.payload_version` with its exact current owners and requires each selected v6 accepted child count, even if no child remains. Its default/null compatibility and imported format rules are unchanged.
- Source-owned history and paired map readers inherit the WAL/version predicate through `WifiHistoryComposer`; no Location demand or duplicate reader is introduced.

## Authored scenarios, not execution evidence

- Migration keeps a pre-marker fact null and checks nullable/default column shape.
- Development29 lacking the new column is blocked and its database file remains present.
- Core contracts: known version is checksum-bound; resealed wrong version fails WAL parity; null and v6 correction chains preserve marker, cross-marker changes fail.
- Real Room production writer: first v6 write, settled correction, retained/no-change audit, ordinary pinned WAL expiry, and portable source export; old null row correction remains null and source-exportable.
- Real Room source export/retention: missing-all v6 children fail; partial children fail.
- Real Room history: valid marked detail list is visible; missing-all or resealed wrong marker fails honestly.
- Real Room selected export detail seam: v6 marker survives without WAL, missing-all/partial fail; complete v6 and null/v5 summaries retain their intended behavior.

## Exact assembly

`NATIVE_WIFI_CARRIER_FILES.json` records hashes and selection rules. `NATIVE_WIFI_CARRIER_TRACKED.patch` contains the twelve entirely owned tracked-file diffs. `NATIVE_WIFI_CARRIER_MIXED.patch` contains only this slice's three mixed-path deltas against their preserved draft state; held untracked exporter/test paths must first exist before those deltas can be applied. The DAO's existing native owner queries and held exporter/test dependencies require separate review/selection. Never stage those entire mixed paths solely from this receipt.

No claim of complete provider assembly, passing tests, on-device reliability or dev/v10 integration readiness is made.
