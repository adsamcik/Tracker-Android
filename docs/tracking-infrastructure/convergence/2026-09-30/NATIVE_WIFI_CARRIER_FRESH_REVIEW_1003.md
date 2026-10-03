# Independent native Wi-Fi carrier review — 2026-10-03

Reviewer: `/root/fresh_bundle_context_review_0930`. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. Static review only; no production changes, Git or convergence/device/provider gates.

## Scope and finding

Read the complete `NATIVE_WIFI_CARRIER_TRACKED.patch`, selected deltas in `NATIVE_WIFI_CARRIER_MIXED.patch`, source boundary/manifest, the actual entity/writer/authenticator/maintenance/history callers and all authored fixture branches. **No concrete defect found in this approved marker slice by static inspection.** It addresses the previous native-owner review's missing durable Wi-Fi version distinction. Complete source-file exporter classes, native-owner queries and their earlier held fixtures remain separate required selections.

The user approved retaining the actual native Wi-Fi format version. This is one nullable database field, reusing the existing version enum and authenticity predicates. There is no user-facing control, new provider, import restriction, public API or transfer framework.

## Production evidence

- `WifiCapturedFactRevisionEntity` now accepts null or the supported v5/v6 version (`WifiCapturedFactEntities.kt:133,155`). Null preserves the exact existing effect-checksum domain/parts; nonnull binds the version in the new effect domain (`310–316`). Both the entity's retained successor contract and writer/maintenance immutable correction tuples include the marker.
- `WifiCapturedFactWriter.kt:221–229` maps a first native write from the actual admitted WAL version, and an exact correction from its prior revision's marker inside the existing transaction. It never guesses an older null marker from children. Existing child production and exact revision/cursor admission remain intact (`230–310`).
- `WifiWalCarrierAuthenticator.kt:661` accepts unknown older markers but requires known marker/WAL parity. The v6 path already checks the exact full accepted child set, product fields and child integrity before fact/WAL/effect linkage (`361–393`). Therefore null does not let a v6 WAL silently tolerate missing children.
- Portable and global maintenance audits call the same new bounded detail predicate for the latest revision (`WifiCapturedFactMaintenance.kt:760,1236,1267–1278`), after authenticating lineages, cursor, epoch and WAL ownership. It queries at most 65 children against the existing 64-child carrier ceiling and uses the same shared fact/WAL predicate; unsupported/missing-all/partial detail cannot be converted to a summary. The existing evidence-to-WAL reconstruction changes visibility only (`WifiCapturedFactClassifier.kt:88`), not its fields.
- Existing ordinary history already authenticates the current fact with WAL and its saved children (`WifiHistoryComposer.kt:269–291`) and validates the complete retained lineage. Map readers use that source-owned composition; no Location request is added.
- The selected held export DAO delta returns `fact.payload_version` (`RadioObservationChildDao.kt:76–88`). The held Wi-Fi `localChildren` addition verifies each selected v6 owner's complete accepted child count, including zero retained children (`PortableWifiSourceExporter.kt:208–217`). Its previously reviewed exact selected run/revision/purpose child ownership and row checks remain required dependencies; this marker count alone is not source authority.
- `MIGRATION_28_29` adds a nullable integer with SQL `DEFAULT NULL`, retaining pre-marker rows (`RadioObservationChildMigration.kt:23`). The entity matches that shape. Schema29 is unshipped under the released-`main` decision; the existing development preflight now checks the column (`DevelopmentV28DatabaseContainment.kt:1190`) and preserves/blocks older incomplete candidate29 files. No generated Room schema was produced or verified.

## Authored evidence

The packet adds eleven new test methods and extends the existing migration method; all remain **unexecuted**:

- Migration checks null/default/type and a preserved pre-marker row; development preflight checks an older candidate29 file remains after rejection.
- Core contracts check version binding, a resealed wrong version against actual WAL, immutable null/v6 correction lineage and rejection of changed markers.
- Four real Room writer/source-export/maintenance scenarios cover v6 first write plus settled correction, no-change retained audit, ordinary expiry's refusal to delete pinned WAL, portable output, old-null correction, missing-all detail and partial detail. The “ordinary WAL expiry” case explicitly asserts **zero rows deleted** and one WAL remains; it does not prove qualification after forced WAL deletion.
- Two real Room history cases assert complete detail is visible before deleting it, then require a provenance failure for missing-all detail or a resealed contradictory marker.
- Two selected export-detail Room fixtures verify a durable v6 marker without a WAL cannot silently export missing/partial children, a complete list succeeds, and null/v5 summary fixtures remain compatible. Those fixtures deliberately omit full provider/WAL admission and remain dependent on the held exporter and exact-owner query definitions.

The finite per-fact maintenance child reads are bounded within the existing audit limits, but their cost, Hilt composition, Room/schema consistency and Android operation have not been measured or executed. Missing forced/corrupt WAL still has the existing unavailable outcome; the marker is not a replacement for source authority.

## Exact selection pins

All fourteen non-exporter whole-file hashes match `NATIVE_WIFI_CARRIER_FILES.json`. The Wi-Fi exporter whole snapshot has moved to `2b0d9588e15782ecbd434272cd38b958c4608c73efc021d5d3464fba696a238d` during root's separate imported-cursor repair. Its selected native child function still contains the exact marker delta; the UTF-8 excerpt from `internal suspend fun localChildren` through the next `private suspend fun appendImportedSessions` marker, excluding the latter, hashes to `f0f73d2dfeea1d03298a4c3da12d137b16583021191d7d66138e299132da126e`.

| Frozen review input | SHA-256 |
| --- | --- |
| Tracked patch | `9cfedd672bc104869a9ee85c615106e169064529ee2c9ed716832a83063f0914` |
| Mixed selected patch | `138bf72467a763a84399fbcdb4ca25598ab071da6e371c5290e20f1dc67632ee` |
| Source boundary | `6482647763f159630d979ee294518210c211fc0e88879a2d938170e6fcfd1049` |

Do not stage the complete mixed DAO/exporter/test paths solely from this receipt. Their preserved dependencies and other review packets must be selected coherently. This is source closure of the marker defect, not full exporter approval, passing tests, assembly completion or `dev/v10` readiness.
