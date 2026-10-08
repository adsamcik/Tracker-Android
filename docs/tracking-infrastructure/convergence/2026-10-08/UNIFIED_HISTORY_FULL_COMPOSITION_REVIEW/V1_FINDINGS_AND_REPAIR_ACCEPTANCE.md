# Provisional whole-History composition review and focused repairs

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Static source inspection only. Every test NOT_RUN. No Gradle, compilation, test, lint, schema, device/provider, CI, Git mutation or integration was performed. T25 and all 25 full-chain prerequisites remain open.

## V1 disposition: blocked, preserve before replacing

The provisional 116-path V1 composition uses the old History supplier for `DefaultTrackingHistoryRepository.kt`. Its selected constructor at lines 71–115 omits the committed ab25 saved Location update signal, finite and live reader fields, the real `readSavedLocationSummary` and `observeLiveSavedLocationSummary` overrides, and the injected signal forwarding. The API defaults return an unavailable finite result and a one-emission fallback. Consequently this source can remain type-compatible while disconnecting the implemented shared Location values used by Tracker, notifications and widgets. A manifest/supplier hash match does not establish correct composition with later committed source.

Repair: use exact committed ab25 repository bytes, then change only the paging generic needed by the measured History contract. Retain all committed finite/live readers and signal injection. No new architecture is required.

A separate practical remaining consumer gap is count-only released Wi-Fi: the approved `legacy_location_wifi_count` table and `LegacyPreservedDataDao.wifiCounts` preserve original measured IDs/times/counts, but V1's radio discovery reads `legacy_wifi_child` only and its model/readers/UI provide no count-only measurement. This is an explicit pending preservation consumer, not a regression or database loss. Root is authoring its bounded existing-data companion; no fabricated network or session/purpose should be introduced.

## Focused repaired facade: source accepted

`UNIFIED_HISTORY_FACADE_WIRING_REPAIR_SELECTED` has two exact LF paths. Its production change is only the paging item generic on top of committed ab25. All finite/live fields, overrides and the real injected update signal remain byte-identical. All 25 dependency pins match their declared committed or frozen companion inputs.

The added actual facade Room case uses the production repository through the public history interfaces, real finite session/window readers, the actual measured RangeReader/PagingSource, and real live append reader plus update signal. It reaches saved summaries and measured Location through real Room. Mocked unrelated entry-page collaborators and boot provider do not substitute for these readers. Seeded writer grants remain fixtures. This case has not run and proves neither Hilt runtime nor provider activation.

## Native name interval repair: source accepted

`MEASURED_NATIVE_NAME_INTERVAL_SELECTED` has seven exact LF paths; all 20 dependency pins match. The optional 46-line helper reuses existing annotation and logical-session metadata. Effective start/end come from edited values then original saved bounds, matching the existing organization reader. Hidden/missing/blank/malformed names supply no label. Real active open sessions may retain an open end; closed unknown-end records do not acquire an invented interval. Point half-open and genuine full-window boundaries, including Pressure's existing strict end, are maintained without altering original observations, their qualification, UTC times or counts. Cancellation propagates; optional metadata read failure loses only names.

Four native Location/Activity/Pressure/Steps leaf callers use this bounded lookup. Six actual Room metadata cases plus one existing real sealed Steps measured case cover edits/bounds and verify that editing an interval removes only the name while its count, row and discovery keys remain unchanged. All seven cases NOT_RUN. Retrospective/imported/radio decoration and overlapping retrospective-name presentation remain separate; this does not claim those are implemented.

## Pending versus empty repair: source accepted

`MEASURED_HISTORY_PENDING_EMPTY_SELECTED` has two exact LF paths; all 12 dependency pins match. The actual existing composable shows a clean empty state only after refresh and append are NotLoading and append reports completion. An empty incomplete frontier retains a progress state. A real refresh/append error exposes Retry and does not claim no saved data. The existing Pager owns progression; no manual loader, timeout or rejection ceiling was added.

Three actual Compose/PagingData load-state cases are authored, and the earlier real Pager test with three empty nonterminal frontiers is retained byte-for-byte outside these additions. Framework responses and payloads are fixtures. The installed Paging version's automatic progression is not proven until deferred execution; all four cases NOT_RUN.

## Other bounded composition trace and remaining boundaries

The V1 selected bytes and declared suppliers match their input manifests; the reviewed resolved committed/frozen dependencies match as declared. RangeReader filters sources before raw discovery, reads raw heads with bounded keyset pages, qualifies only consumed selected parents, preserves independent valid siblings and cancellation, and advances past rejected raw keys. It performs no provider activation. Query-only candidate fields introduce no new schema. Room invalidation includes original Stop intents and typed source/import/deletion/organization tables. Paged API/implementation/UI types are coherent in the selected suppliers. Source-local window readers preserve actual original windows and counter domains; strict daily/numeric totals are unchanged. Timeline/selected Calendar-day callers use displayed local dates and actual next local midnight, including DST, while saved timestamps remain UTC.

This is bounded source acceptance of the three repair packets, not acceptance of a refreshed whole assembly. The refreshed composition must include these exact accepted sources, the count-only Wi-Fi companion and correct supplier dispositions. Calendar month source presence, retrospective overlapping names, provider execution, Room/SQL/Hilt compilation, performance and full migration/device/CI gates remain open. T13/T14/T17 are not declared complete.
