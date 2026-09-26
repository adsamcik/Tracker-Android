# Stored and exported meaning — whole-product polish review

Date: 2026-09-26. Author: Astra data/export planner. **Planning and static inspection only, inside IMPLEMENTATION_ONLY.** All new choices below await approval. No production/test changes, commits, compilation, tests, schema operations, UI/device work, activation, or integration were performed.

## 1. Authority, scope, and evidence

Read TASK_CONTEXT including verbatim follow-up §10 and POLISH_REVIEW_CONTEXT in full. Preserve the user-approved direction: each supported source combination works independently; optional enrichment consumes compatible retained facts without creating demand; manual, automatic, and separately consented sessionless ambient share the engine. The first implementation proposal remains unapproved. The approved passive ambient Location obligation is not canceled by its unfinished implementation. Broader ambient Activity/Pressure support must follow existing product-purpose availability, not be invented by this review.

Unless prefixed otherwise, paths/line numbers below are relative to main checkout `C:/Users/adam-/GitHub/Tracker-Android`, clean `dev/v10` at `13f1a99871b59d29b3d059f374fc415490da6943`. Prior candidate/draft differences remain as documented in DATA_PLAN and CURRENT_CODE_INVENTORY; none is treated as assembled. Planning checkpoint `d6a813e41e` does not change production evidence.

For compact anchors, `IE/` means `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/`; `APP/` means `app/src/main/java/com/adsamcik/tracker/app/`; `API/` means `stats/api/src/androidMain/kotlin/com/adsamcik/tracker/stats/api/repository/`. These expand to real repository paths, not new modules.

Inspected actual global format UI, export activity/view model/repository, GPX/KML/JSON writers and readers, database archive/merge, source portable adapters and representative codecs/contracts, import job receipts/worker, automation model/worker, deletion/retention call sites, English resources, and existing test-source assertions. Read module build/manifest: existing API dependencies suffice; debug research/control trace exporters are explicitly absent from user format registry (`feature/import-export/build.gradle.kts:25–28`). Earlier DATA_PLAN covers writers, native readers, WAL/provenance, deletion, branch conflicts and Room changes; this report adds the file and user-journey consequences.

This is a bounded source audit, not a claim that every serializer branch or locale was executed. Device file providers, interruption timing, visual hierarchy, accessibility, localization quality and actual import/export round trips remain deferred evidence.

## 2. Format × source × purpose inventory

`FormatRegistry.kt:96–216` registers the following. Registration and source ownership are separate from provider activation and from validation.

| Current format/path | Location | Steps | Wi-Fi/Cell | Activity | Pressure | Purpose and round-trip limits |
|---|---|---|---|---|---|---|
| GPX | Coordinate/time points; only qualified Android-model MSL altitude | None | None | No captured Activity facts | None | Geography interchange. One track segment for the whole supplied sequence; no source coverage, clock uncertainty, authority, original session boundaries or sessionless purpose. Import creates imported samples/session segments. |
| KML | Timestamped points, qualified MSL altitude only | None | None | None | None | Geographic visualization; no native source authority/coverage. Import accepts Point and LineString; untimed inputs receive synthetic times. |
| JSON schema 3 | Legacy Location rows, accuracy/speed/altitude metadata | Explicitly omitted | Legacy identity-bearing `wifi_observation`/`cell_sample` only | Trip activity summary only, not captured Activity product | No pressure product | Streams overlapping legacy sessions and orphan raw rows. Not a six-source archive. Imported rows are not local accepted source facts. Some exported fields are discarded on import. |
| Database ZIP | All included database tables | Same | Same | Same, potentially runtime/control evidence in DB | Same | Raw per-database snapshots, no date range. Not preferences/files backup, not cross-database atomic. Current generic import rejects complete Tracker databases; a complete restore flow is not established. |
| Portable Steps | None | Captured Steps, v2 preferred, bounded atomic fallback v1 | None | None | None | Qualified source-specific logical entries, coverage/gaps/count-domain contracts. Imported origin remains distinct. No authority resurrection. |
| Portable ambient Steps | None | Sessionless ambient day products, v2 preferred/fallback v1 | None | None | None | Native origin is the global registry default. Imported re-export backend exists but needs an explicit imported-origin action; a Trip selector is rejected. Stored zone/day semantics preserved. |
| Portable Activity | None | None | None | Captured movement bands/confidence/coverage | None | Capture only. Automatic CONTROL evidence is not a capture export. |
| Portable Pressure | None | None | None | None | Direct hPa facts, coverage/gaps | Not calibrated elevation. Whole logical replacement members stay together even when a range selects the entry. |
| Native/ambient Wi-Fi and Cell portable backends | No fabricated coordinates | None | Source-local contracts/export/import exist | None | None | No registered global file codecs/adapters/options in this checkout. Existing backend is not an end-to-end file affordance. Complete the narrow adapters using those contracts if approved. |
| Debug research/control trace | Diagnostic research scope | Not ordinary history | Not ordinary history | Dedicated debug CONTROL evidence | Research only | Never register as ordinary user history/export or use to justify a production feature. |

Concrete portable anchors: `IE/exporter/PortableStepsExporter.kt:53–145`; `PortableAmbientStepsExporter.kt:34–79,151–164`; `AmbientStepsPortableSourceBackend.kt:16–36,54–150`; `PortableActivityExporter.kt:26–75`; `PortablePressureExporter.kt:26–92`; `API/PressurePortableTransfer.kt:3–27,58–60`. Wi-Fi: `API/WifiCapturedPortableFormatV1.kt:79–179` preserves uncertainty, stored zones, accepted/rejected counts, coverage and completeness; existing backend `tracker/engine/.../source/wifi/WifiCapturedPortableTransfer.kt:78–1138` qualifies bounded dependencies. Ambient Wi-Fi/Cell contracts and transfer implementations are in `API/AmbientWifiPortableTransfer.kt`, `AmbientCellPortableTransfer.kt`, and `stats/data/.../repository/RoomAmbient{Wifi,Cell}PortableTransfer.kt`. Cell captured transfer is currently in `core/base/.../database/CellCapturedPortableTransfer.kt`; preserve this existing ownership rather than introduce a universal exporter framework.

## 3. Concrete gaps and smallest corrections

### E1. Global export labels and preflight do not describe the selected data

`APP/settings/components/ExportFormatDialog.kt:161–226` repeats a hard-coded format enum; `supportedExportFormats()` maps registry entries through that enum with `mapNotNull`, so registry additions alone remain invisible. `app/src/main/res/values/strings.xml:252–264` recommends GPX universally, claims database backup contains “all data and settings,” and describes JSON without its radio identifiers. Actual JSON writes BSSID, SSID, capabilities, frequency, signal and cellular identity at `IE/exporter/JsonExporter.kt:549–564`. The database exporter only enumerates database files (`DatabaseExporter.kt:13–23,35–47`). These are concrete copy/content mismatches.

`ImportExportViewModel.kt:66–72` checks legacy Trip existence. **Correction to an early peer hypothesis:** the UI uses that no-data flag only when `exporter.requiresLocationData` (`ImportExportComposeActivity.kt:150`); portable Steps/Activity/Pressure are not blocked by it. However, orphan Location data and JSON radio-only legacy data can be rejected despite exportable rows; JSON inherits the Location preflight yet performs its own radio queries. `RoomImportExportDataRepository.kt` reads legacy Trip/Location DAOs directly. Do not broaden the same raw preflight to new native sources.

**Proposal:** preserve the existing screen and add a compact scope summary before format selection: selected source(s), session or between-session scope, dates/zone, local/imported origin where relevant. Format descriptions state included and omitted content. Offer only applicable actions or a precise disabled reason. Recommend GPX only for an actual route interchange selection. Reuse the registry with minimal static capability metadata rather than a second general-purpose format engine. Existing stable format IDs stay unchanged.

### E2. Date range and logical scope need an explicit contract

`JsonExporter.kt:128–146` selects overlapping trips; `:170–215` exports their entire start/end intervals. Thus a range can include observations outside its dates, and timestamp grouping can associate one raw row with more than one overlapping trip. This is not proof of exact source/session membership. Portable Pressure deliberately preserves whole replacement-run membership (`API/PressurePortableTransfer.kt:20–24`); do not “fix” all formats by clipping every row.

Date pickers currently assign selected milliseconds as a system-zone instant (`ImportExportComposeActivity.kt:486–526`), while default range includes time of day (`:139–140`) and labels show date/time. A day picker and an exact instant picker must not silently have different end-day meaning. Portable adapters convert inclusive millisecond endpoints into half-open ranges (`PortableStepsExporter.kt:142–145`); day actions use stored zone and next-day start (`AmbientStepsPortableSourceBackend.kt:116–145`). Preserve that DST-safe day calculation.

**Approval choice:** recommend exact requested interval for geographic point export, and whole logical entries for source portable export, with a pre-export line such as “Includes complete sessions that overlap these dates.” Keep JSON schema-3 compatibility; disclose its whole-session selection now. Changing its existing default to clipping requires a separate explicit decision and a schema/metadata strategy for partial session summaries. Show the effective timezone and interval; don't relabel stored structural days into the current device zone or sum a cross-midnight entry twice. Reject invalid/reversed dates before creating a destination.

### E3. Share text is Trip-shaped even for source-only files

All share actions call `resolveShareTripSummary` (`ImportExportComposeActivity.kt:375–386`). It looks up legacy trips wholly within the selected range and can display distance/duration/activity independently of the actual source file (`ImportExportViewModel.kt:143–195`; `RoomImportExportDataRepository.kt:36–55`; `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/TripDao.kt:57–71`). This is a different selection from JSON's overlapping-trip export. The existing explicit refusal to claim legacy Trip.steps is correct (`ImportExportViewModel.kt:188–189`).

**Proposal:** derive share text from the exact successful export scope/result. A Steps file should say “Steps history · 12–14 September”; an ambient file should identify “Between sessions.” No unrelated distance, route pin, or trip name. Keep optional share commentary short; put machine precision in the file. The file result needs a small scope/count-unit summary, not a new tracking model. `ExportResult.kt:17–22` currently has only a generic record count and Location progress; `ExportDocumentResult.Success` drops that result metadata. “Records” cannot mean interchangeably Location samples, source entries and ambient days.

### E4. Geography formats do not preserve all stored meaning

GPX emits one `trkseg` (`GpxExporter.kt:83–86`) and only coordinate/time/MSL elevation (`:89–105`). KML emits points (`KmlExporter.kt:45–58`). Both intentionally omit unknown/relative/ellipsoid altitude; preserve this safeguard. JSON explicitly preserves altitude datum/source/conversion/model versions (`JsonExporter.kt:525–541`), and importer preserves them (`JsonImport.kt:282–297,328–334`).

New passive ambient Location must remain observed points, not be piped into the existing one-segment route exporter. Likewise multiple sessions/gaps must not become a claimed continuous route. Recommend use existing point-oriented KML where its limitations are suitable, and offer GPX only for a qualified session route until a specifically approved waypoint/segment policy exists. No new universal format is required for the immediate reader work; any portable Location contract retaining sessionless purpose and provenance is a named data-design decision, not silently “covered by JSON.”

JSON import currently ignores serialized vertical accuracy, speed accuracy, provider and quality, then assigns `vAccM=null`, `speedAccuracyMps=null`, `provider="import"`, `quality=MEDIUM` (`JsonImport.kt:299–323`; writer `JsonExporter.kt:542–546`). Imported origin must stay imported, but original measurement quality/uncertainty should not be conflated with import origin. **Proposal:** preserve representable descriptive fields through the existing imported storage path while retaining no local source authority; where a field cannot be represented, explicitly disclose loss. Do not copy incoming live writer authority or trust an incoming quality enum as local authentication.

KML untimed geometry currently receives current-time and fixed-spaced synthetic timestamps (`KmlImport.kt:49–50,88,151–171`), then imported samples and user-created segments (`:245–265`). Source-derived risk: display/history/awards can treat invented times as measured times unless origin/time quality is retained. Recommend explicitly mark imported estimated timing or reject untimed geometry for time-based history pending approval; preserve compatibility for existing timed files. This is a bounded import-semantics choice, not an excuse to create fake source sessions.

### E5. Scheduled export can widen scope and does not cover all sources

`ExportBackupPlan.kt:39–72` exposes only GPX/KML/JSON/database. `ExportPlanWorker.kt:324–332` resolves LastSession through latest legacy Trip and returns null when missing; downstream null means unbounded history (`:169–170`, exporter defaults). **Required correction:** missing/unsupported selection is an explicit no-data result, never EntireHistory. Source-aware LastSession must use the exact approved history selector when that action is added.

Incremental logic (`ExportPlanWorker.kt:259–304`) uses a Location `(time,id)` watermark. JSON determines progress from Location alone (`JsonExporter.kt:621–638`) and selects a changed session by a Location after that cursor (`:319–336`), while radio rows have no independent progress. It cannot promise a complete delta for radio-only additions, late historical insertions, corrected facts or deletions. Existing `settings_incremental_backups_summary` says “Only export new data since last backup” (`app/.../res/values/strings.xml:30`), too broad for these semantics. Rolling “month/year” is fixed 30/365 days (`ExportPlanWorker.kt:335–343`), not calendar period arithmetic.

**Proposal:** retain existing Location append export, label it “New location points” and describe its boundary. For JSON recommend nonincremental scoped snapshots until a source-specific complete delta exists; do not invent a multi-source delta protocol during polish. Disable irrelevant date/cadence options for database snapshots. Name rolling windows honestly (for example “last 30 days”) or seek calendar-duration approval. New source portable automation is a separate bounded extension using existing source contracts, not implied by adding a global file picker option.

### E6. Backup, import, and recovery must distinguish archive from restore

DatabaseExporter copies all `Context.databaseList` files one at a time with per-file locks; its own contract explicitly disclaims atomic cross-database snapshot (`:13–23,62–98`). It contains no preferences, arbitrary files, map resources or guaranteed deletion sidecar set. It may contain operational/control tables not intended for ordinary source history. Do not describe it as a complete device/app restore or a source-selective export.

`DatabaseImport.kt:76–82` rejects complete Tracker signatures; `:477–514` allows only user-data tables and excludes lifecycle, leases, cursors, queues and audit authority. Generic compatible-row merge is transactional and foreign-key checked. `:539` says Tracker backups require restore. No production full restore route was found in the inspected app/import/migration sources. Therefore current database export→generic import is **not** a safe supported round trip, and the existing picker copy implying it merges its own backup must change. Do not add a destructive restore confirmation to the existing merge operation: it does not replace the database.

Recommend copy: “Database archive — database files only. Settings and downloaded resources are not included. Tracker database archives cannot be restored from this import screen.” If a full restore is later approved, it must reconcile the database, authoritative epoch/deletion sidecars and policy/consent stores together, invalidate foreign runtime ownership and require safe startup repair. It is a separate delivery slice; do not bypass current rejection or apply frozen importer drafts wholesale.

The ordinary import picker starts `DataImporter.import` immediately (`APP/settings/data/DataSettingsScreen.kt:81–85`); source jobs use content fingerprints, entry receipts and startup-generation fences (`IE/importer/DataImporter.kt:95–340,398–424`, `ImportWorker.kt:225–327`). Preserve duplicate/deleted-scope blocking and complete-file authentication before source-local mutation (`PortableStepsFileImport.kt:72–133`). Archive imports can retain earlier committed entries when a later entry fails; source-managed entries have their own transaction boundary. Use an outcome with imported/skipped/failed counts and a short reason, not an “all imported” sentence for a partial archive. No need to add confirmation to every harmless merge; a concise explanation and actionable result is enough unless a new destructive action is approved.

### E7. Failure, deletion and retention must match visible results

Manual `exportToDocument` deletes failed outputs, but its CancellationException path immediately rethrows without deleting an already-created file (`ImportExportViewModel.kt:85–121`), and deletion Boolean results are ignored. The automated worker does delete partial output on cancellation/error (`ExportPlanWorker.kt:216–241`), but its cleanup result also needs honest handling. Recommend one existing-output cleanup path using cancellation-safe IO and a “Could not remove incomplete file” state only when cleanup actually fails. Do not report Export complete before output is closed and accepted as successful.

Global deletion already pauses export/import writers, uses durable epoch/deletion barriers and removes diagnostics/points (`APP/settings/CollectedDataDeletionService.kt:272–288,557–602,966–1009`). Retention establishes a floor and removes migration backups before raw deletion, then resets export watermarks (`app/.../maintenance/DataRetentionWorker.kt:222–267,542`). Preserve these safeguards. The manual raw export path needs the same qualification/fence discipline as the scheduled/import paths when Location authority is tightened; do not bypass accepted-receipt/deletion qualification by paging raw samples.

A file already saved/shared outside the app cannot be retracted by source deletion. Say this at deletion scope explanation where relevant, not as repeated warnings on every history card. Verify app-private export/share cache cleanup against the global delete adapter before claiming those copies are deleted; this review did not establish every private-file cleanup path. Imported tombstones/correction lineage must survive re-export or be handled with the existing no-resurrection semantics. A retained partial history may be exportable with explicit gaps; distinguish it from unverifiable data that the source exporter must refuse.

Turning off future capture or losing current OS permission is **not** deletion. Existing history and compatible retained Location context must remain readable when their historical authority remains valid. On policy/consent/epoch changes, invalidate and requalify both enrichment inputs; do not blanket-hide lawful captured history. Ambient revocation follows the approved source-purpose lifecycle policy, with independent consent and retention; never infer capture authorization from imported content or from current enabled toggles.

## 4. Six-source and whole-app meaning checklist

### Time, units, and unknown values across every layer

Persisted epoch time, monotonic elapsed time, boot/clock provenance, uncertainty and stored structural-day zone retain their distinct meanings. Localized display dates and a user-selected view zone are presentation only: they must not reallocate Steps count domains, rewrite recorded day ownership, or authenticate a cross-boot join. Export machine numbers with explicit units and locale-independent encoding (ms/ns, metres, metres/second, hPa, dBm/MHz where actually applicable); retain UTC instants or explicit offsets plus stored zone/day when the source contract requires them. A formatted UI value is not the serialization source. Unknown/null is not zero, absent evidence is not a confirmed empty scan, and partial coverage is not a complete-day result. Portable source dependencies, completeness and timing provenance stay in files even when the card displays a short count.

Native Wi-Fi/Cell observation/reading children can include repeated encounters. Count them using the actual source contract, never label them unique APs/towers/networks. Direct Pressure remains hPa unless a separately qualified altitude product is selected. Radio map context remains optional secondary data with compatible purpose and both inputs qualified; it must not create a new export authority or make the primary radio file unusable without coordinates. Imported context cannot claim native accepted proof. OS permission revocation, future source toggle-off, ambient consent revocation, source deletion, and retention expiry each use their own lifecycle semantics; only the last applicable authority check decides whether retained data can be read/exported.

| Surface/data class | Truth to preserve | Required connection |
|---|---|---|
| Manual/automatic captured Location | Accepted observation versus route; retained receipt and uncertainty | Qualified paired reads for map/history/export; no per-row proof query fanout. |
| Passive ambient Location | Sessionless, separately consented points; no fake route/session | S5 scope/storage/export/deletion decision before optional ambient-to-ambient map context. |
| Captured Steps | Exact/reconciled count, overlap domains, gaps, corrections | Same qualified source entry in detail/file; no legacy Trip.steps fallback. |
| Ambient Steps | Structural day, zone, shared overlap/count domains, local/imported origin | Global default currently native only; source-local re-export must select imported origin explicitly. |
| Captured/ambient Wi-Fi | Observations/scans/results have distinct count units; no unique AP identity | Portable adapter preserves submitted/accepted/rejected counts, completeness and correction lineage; map only optional compatible observed context. |
| Captured/ambient Cell | Aggregate observations, availability/completeness; no inferred tower location | Same scope/origin discipline; retain existing Cell-specific portable contracts. |
| Captured Activity | Movement bands/confidence; recorded source versus automatic trigger | Portable capture only; CONTROL and its eventual TTL remain separate. |
| Pressure | Direct hPa measurement and uncertainty/coverage | Preserve unit in display and file; never substitute calibrated altitude or ski elevation. |
| Imported data, all formats | Foreign descriptive provenance, no live provider/session permission | Imported labels and actions; replay/conflict/deleted-scope handling; local accepted context not manufactured. |
| Live/dashboard/history/detail/maps | Displayed counts/scope correspond to exact export selector | Product owns hierarchy/action placement; no no-data collapse for coverage-only or partial evidence. |
| Reports/goals/awards/game | Qualified source metric versus legacy aggregate or nonpersisted game location | Whole-app role traces these; no source-export implies goal eligibility. Global points clear verified above. |
| Setup/settings/permissions | Acquisition choice versus stored history lifecycle | Keep source off-history readable; export never requests acquisition or creates demand. |
| Diagnostics/research | Payload-free normal diagnostics versus explicitly scoped debug traces | No ordinary CONTROL/history export; raw DB archive warning is broader than source-file warning. |
| Resources/maps/network | Downloaded assets and preferences absent from DB ZIP | No “all settings/resources” backup claim; no new network/export service. |
| Widgets/shortcuts/notifications | Same source semantics and completion state | Source unavailable ≠ zero, partial success ≠ complete; whole-app/product reports own UI traces. |

## 5. Compact proposed affordances and copy

Every item below earns space by explaining selection, loss or the next action; final wording/localization awaits approval.

- Scope header: “Wi-Fi history · 12–14 September · Recorded on this device.” Secondary line only when needed: “Includes complete sessions overlapping these dates.”
- GPX: “Route points for GPS apps. Steps, radio observations and coverage details are not included.” Do not show “Recommended” for source-only/ambient selections.
- Portable Wi-Fi after adapters exist: “Wi-Fi observations, timing and coverage. No network identities or route coordinates.” Confirm exact contract contents before release wording.
- JSON: “Locations, session summaries and legacy Wi-Fi/cell details. Other source history is not included.” Sensitivity copy must mention identifiers when present.
- Ambient export: “Between-session Steps · 12 September · Europe/Prague.” Imported re-export uses “Imported history,” not “recorded on this device.”
- Partial evidence: “Some observations are missing. Coverage details are included.” Unverifiable export: “This history cannot be exported safely. Try again.” Give a smaller-date-range action for bounded overflow where applicable, rather than retrying an impossible same request.
- Completion: “Saved 3 Steps sessions” or “Saved 2 ambient days,” using the actual count unit. A canceled/failed output is never described as complete.
- Import outcome: “Imported 3 entries · 1 skipped · 1 failed,” with reason/details/retry next to the result. Existing imported records remain available after partial archive failure.
- Consolidate duplicate sensitivity dialogs: the global picker always warns, then the activity warns again for flagged formats (`ExportFormatDialog.kt:50–64`; `ImportExportComposeActivity.kt:419–433`). Prefer one warning at the actual save/share action when scope and destination are known; retain source-specific content and direct-launch protection.

## 6. Small coherent slices and approval choices

1. **E-A: Correct descriptions and outcomes.** Existing format enum/registry parity, actual includes/omits copy, native/imported purpose labels, exact success count units, source-based share text, import partial outcome and backup/merge limitations. Product owns visible shell; data supplies meaning. No new universal file format.
2. **E-B: Close scope and cleanup defects.** LastSession no-data result, range validation/effective scope, manual deletion/retention fencing, cancellation cleanup result. Preserve qualified readers and source-local transaction boundaries. Fixes are required for the accepted behavior; implementation remains approval-gated in this planning phase.
3. **E-C: Finish source action/file adapters.** Exact source-local selectors from S4/history and existing Wi-Fi/Cell capture+ambient portable contracts; include imports/re-exports, generation-2 reader qualification and correction/deletion dependencies already in DATA_PLAN. No GPX conversion for radio and no manufactured identities. File encoding/version/extension limits and UI scope need explicit approval.
4. **E-D: Preserve import meaning.** Represent original measurement accuracy separately from imported origin; timed versus synthetic KML behavior; retain conservative altitude and strict portable receipt/checksum/tombstone contracts. New stored timing/provenance fields need the single final Room migration plan, not an independent migration head.
5. **E-E: Constrain automation honestly.** Preserve Location append cursor where valid, stop claiming general source deltas; JSON snapshot recommendation, fixed-day labels, and source-aware LastSession. Source portable automation and full database restore remain separate optional decisions, not silent scope expansion.

Approval bundle: (a) scope-first within current shell, one final sensitivity confirmation; (b) exact geographic interval versus whole portable logical entry, JSON compatibility disclosure; (c) narrow Wi-Fi/Cell file adapters with existing source contracts; (d) imported quality/timing handling—recommend preserve descriptive uncertainty and flag synthesized timing, alternative reject untimed KML; (e) truthful scheduled snapshot/append behavior rather than general delta protocol; (f) passive Location export purpose/format policy coordinated with S5. Do not reask whether independent source combinations or no-demand enrichment are wanted; those are settled.

## 7. Bounded acceptance matrix for later test-source authorship and convergence

No checks were executed. Existing sources include FormatRegistryTest, ImportExportViewModelTest, Gpx/Kml/Json exporter/importer tests, portable Steps/ambient/Activity/Pressure codec/admission tests, DatabaseBackupArchiveTest, DatabaseImport tests, IncrementalExportTest and source radio transfer tests. Preserve altitude, collision, replay, size-limit and safe-import assertions already present; add cases that catch actual semantic failures rather than mirroring getters.

| Case | Expected meaning at storage → UI → file/import |
|---|---|
| Steps-only session, no Trip/Location rows | Export available through source selector; no route summary; source counts/coverage survive import as imported. |
| Wi-Fi/Cell capture and ambient independently, gen1 and gen2 retained | Correct purpose/origin, exact supported writer contract, no fake identifiers/coordinates; import creates no live authority. |
| Overlapping replacement runs/corrections and mixed-source session | One logical selection; full dependencies before limit; no double count or silent truncation. |
| Date boundary, cross-midnight, DST, device-zone change | Effective interval disclosed; structural day kept in stored zone; exact geography versus whole-entry policy exercised. |
| JSON requested interval inside a longer trip | Current whole-session behavior explicitly disclosed or deliberately changed with matching metadata, never hidden overshare. |
| JSON Location accuracy/altitude round trip | Representable precision preserved, unknown stays unknown, origin remains imported; no local writer authority imported. |
| GPX multi-session/gap and passive ambient input | No manufactured continuity; format unavailable or correct explicit points/segments policy. |
| KML missing/ordered-late timestamp | Synthetic time never silently presented as measured; existing timed point imports remain valid. |
| LastSession with no legacy trip or source-only latest session | No widening to all history; exact selector or honest unsupported/no-data result. |
| Incremental late radio/correction/deletion | No “complete delta” claim; snapshot or unsupported path as approved; watermark advances only for actual accepted progress. |
| Cancellation, SAF write failure, cleanup failure | No success; partial output removed or user told exactly which file needs cleanup. |
| Concurrent deletion/retention/source revoke during export | Fence invalidates unauthorized read/output, no resurrection; ordinary future capture-off retains lawful history. |
| Import duplicate/conflicting/deleted scope, malformed archive tail | No live ownership imported; receipts support safe retry; committed prefix and failed entries honestly reported. |
| Database archive selected for generic import | Clear unsupported restore message; existing data unchanged; no misleading “all settings restored.” |
| Portable bounded overflow, absent graph fallback, partial retained data | No truncated success; existing atomic v2→v1 fallback remains faithful; omitted capabilities and retry/smaller-scope action accurate. |

After all six-source assembly obligations are implemented or explicitly dispositioned, freeze one input set, then run the agreed convergence batch and fresh independent review. File provider/locale/accessibility and interrupted round-trip observations are part of later evidence, not established by these source reads.

## 8. Peer coordination and remaining limits

Product confirmed source-detail menus currently unavailable, Steps/Pressure selection gaps, and ambient summary count/window mismatches. Data supplied exact format omissions, corrected the mistaken portable no-data-block hypothesis, and recommended the shared exact export selector. Whole-app/Delivery received LastSession broadening, DB archive/merge distinction, JSON losses and native-radio adapter gap; its goals/game/deletion review remains authoritative for secondary UI consequences.

Root requested acquisition-versus-history correction: accepted here in §3 E7; source-toggle/permission change triggers requalification, not automatic historical erasure. Parent also confirmed no new restore flow is implied. Prior F22 control-retention correction, sequence-zero/generation defects and candidate receipt/WAL limits remain in DATA_PLAN; this report does not reclassify them as solved.

Remaining bounded context gaps: complete source-specific re-export selection for captured Steps/Activity/Pressure, all native radio codec design/version choices, every app-private file cleanup adapter, all database namespaces/cross-file restore authority, and locale/device/provider behavior. None may be hidden behind “all data,” “lossless,” “complete backup,” or “verified round trip.” No new source export is considered delivered merely because an internal backend exists.
