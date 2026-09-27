# Remaining design decisions — 2026-09-27

Status: **PARTIALLY APPROVED.** The user approved 48-hour CONTROL-only retention and optional default-off Wi-Fi/Cell notification hosting. Selected-data file behavior needs clarification. The user requires a clean backup of all data; the previous archive-only recommendation is superseded, with the restore choice awaiting confirmation. This packet does not reopen source independence, five balance levels, sessionless background collection, retrospective session organization, or the approved all-day adaptive Location refinement.

Phase: **IMPLEMENTATION_ONLY**. This is decision preparation, not production implementation, executed validation, assembly completion, provider activation or local integration.

## D1 — CONTROL-only retention

Approved lifetime: **48 hours** for raw Activity CONTROL-only evidence used by automatic start/continuation. Exact user answer: "48 hours retention". This replaces the earlier 24-hour recommendation; it does not create an Activity history product.

- The existing short trigger freshness/expiry rules remain unchanged. Keeping a record for 48 hours does not permit a stale automatic start.
- On expiry, control effects become ineligible/cancelled before payload cleanup. Clean bounded batches at the next permitted startup/control/maintenance opportunity.
- Same-boot monotonic authority prevents a restart or retry from renewing retention. On new boot or unprovable clock continuity, expire old CONTROL-only evidence conservatively.
- Independently captured Activity follows its own consent/retention. Shared evidence required by capture must retain exact capture proof before CONTROL-only payload is retired.
- Preserve only necessary idempotency/deletion fences and minimal accepted-start provenance after expiry; no coordinates, radio identities, raw provider extras or browsable motion history.
- No exact cleanup-time promise or secure-erasure claim: an app that does not execute cannot prune rows at a precise deadline, and raw SQLite files can contain residual bytes.

Alternatives presented: delete after the terminal decision (least stored data, less troubleshooting context); seven days (more troubleshooting history and more retained operational data).

Existing detailed contract: `DATA_GAP_CLOSURE.md`, section 10. Implementation remains in the existing control/outbox/maintenance machinery.

## D2 — Optional Wi-Fi/Cell notification hosting

Approved option: offer **Keep running with notification**, **off by default**, in Wi-Fi/Cell background details. Exact user answer to that proposal: "Yes".

- Each selected, consented background source may request the existing shared TrackerService host. Reuse an already compatible host and account for its marginal cost once.
- The option improves passive listener lifetime. It does not guarantee fresh observations, enable tracked Location, create a session, grant active scan attempts, or replenish a live radio budget.
- Opportunistic listening remains available with this option off. Disabling the host option retires only its host demand; ending a live session preserves independently enabled background demand.
- Required Android protected-access permissions and service/start restrictions remain enforced. OS requirements involving Location permission/device Location are separate from enrolling the Location tracking source.
- Store the choice on the existing immutable per-source policy; old rows remain null/false. Policy-only residency changes preserve the independently established background consent epoch.
- Permission loss, user stop, process loss and reboot use the existing authority/recovery rules. No perpetual wake lock, recurring scan loop or forced restart is added.

Alternative presented: opportunistic listening only for this version, with honest availability limitations and no dedicated background-radio hosting option.

Existing detailed contract: `RUNTIME_GAP_CLOSURE.md`, section 3.1. Android documents passive scan-completion listening and scan restrictions in its [Wi-Fi scanning guidance](https://developer.android.com/develop/connectivity/wifi/wifi-scan); foreground hosts remain subject to [service-type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types). Source/device evidence is still required before claiming reliable operation.

The existing all-day Location approval supersedes the older proposal's default-off active-Location option. This decision does not change that approval.

## D3 — Portable and interchange files

**Awaiting clarification.** Exact user answer: "I don't get what this is about". This is not approval of the format proposal below.

Plain-language distinction:

- **Backup:** save all durable Tracker data and settings to restore the app later (D4).
- **Export:** save only a selected session or date range to share or use elsewhere. A Tracker file preserves the selected source data and labels; GPX/KML carry geographic data for other apps and cannot represent every Tracker source.
- Show when an export must include a complete measurement/day rather than an exact time slice. Do not invent a time for imported coordinates that have none. Supporting separately browsable undated geometry is a larger alternative, not silently included.

Recommendation: keep existing typed source formats; add the planned exact `.trackerlocation` point format and small session bundle using existing import job/entry receipts. Keep JSON3, GPX and KML as clearly described interchange formats.

- A session bundle carries local annotation metadata plus unchanged typed source files. Imports create a local annotation copy; they cannot restore current native provider ownership, consent, leases or automatic-start authority.
- Exact point identities/source receipts handle duplicate observations across files. Imported annotations select their declared source entries, not every record with a coincident timestamp.
- Disclose whole logical-entry/day expansion before export or erase. Do not clip or prorate immutable Steps/aggregate windows to imply exact membership.
- Preserve actual quality, origin, gaps and altitude datum; unknown values remain unknown. JSON3 retains its existing geographic/whole-entry compatibility scope rather than becoming a universal replacement format.
- GPX connects only proven routes and represents sessionless/unproven points appropriately. The existing partial implementation must be reconciled with this full contract.
- New KML imports accept valid timestamped Points, including timestamps appearing after coordinates in a Placemark. Report unsupported untimed geometry and multi-point geometry lacking per-point timing; do not invent timestamps.
- Preserve existing stored imports as legacy descriptive data. Do not guess and rewrite their historical timing.
- Retain the documented bounded parser/file/bundle limits and existing partial-import, retry, cancellation, retention and original-job-epoch fences.

Alternative presented: additionally retain untimed KML as explicitly undated geometry. That requires a separate time-quality/storage/presentation path and expands implementation beyond the recommended initial format support.

Existing detailed contract: `DATA_GAP_CLOSURE.md`, sections 7–8, and `PRODUCT_GAP_CLOSURE.md`, section 8.

## D4 — Full backup and restore; legacy archives and schedules

**User intent settled; restore behavior awaiting confirmation.** Exact user correction: "The point of database export was a clean way to backup all data". The previous recommendation to retain only an advanced raw database artifact without complete restore is superseded. Merely relabelling the current archive would not satisfy the requirement.

### Proposed complete backup contract

- One **Back up / Restore backup** operation, separate from selected-data exports. Back up all durable Tracker data and settings: every supported source's retained facts and historical proof, sessions/annotations, imports, goals/game progress, saved source/balance/automation preferences, export-plan definitions, and user-owned durable files such as an imported custom map.
- Inventory all actual owned stores before implementation: Room/other databases, DataStore, SharedPreferences and durable files/sidecars. Classify operational authority explicitly rather than treating an arbitrary filesystem copy as a working restore. Exclude only temporary caches, temporary output/staging and assets already bundled with the application. Do not omit a user-imported map as if it were a cache.
- Use a versioned, bounded archive manifest with exact registered paths, lengths and hashes. Extend existing archive/file-safety machinery where it fits; do not invent a second generic import engine. The portable session-bundle limits are not automatically full-backup limits: a custom map/full history can be larger. Record concrete limits before production implementation.
- Settle pending deletion/retention and CONTROL expiry, then create one coherent snapshot across databases, preferences and durable files under existing lifecycle/maintenance ownership. No mixed-time database/settings copy or unreviewed newly discovered file. Fail without publishing a successful backup if a consistent safe snapshot cannot be obtained.
- Restore validates the whole archive and supported schema/version before modifying current data. Stage and preserve the current installation until recoverable replacement commits; interruption, corruption, disk exhaustion, unknown newer versions and path/hash failures must leave either the old installation or the complete restored one recoverable. Do not use destructive migration or partially replace live stores.
- Restored historical source proof remains historical. Provider leases/generations, pending CONTROL starts, active-session recovery commands, device permissions and external URI grants must not become current execution authority. Restore retained selections/settings, but start in a stopped state; the user explicitly resumes after reviewing current-device requirements. Saved schedule definitions need a valid destination and explicit re-enable before they execute.
- No network upload, cloud service, encryption dependency or backup merge protocol is added by this proposal. The local backup is sensitive and the user chooses its destination.

Recommended restore UX: **replace current Tracker data after confirmation**, showing backup date and scope, with recoverable replacement. Alternative: restore only into an empty installation; smaller replacement surface, but requires clearing/reinstalling before recovery. Do not implement either choice until answered.

### Existing archives and scheduled files

These details remain proposed, not approved by the answer about backup intent:

- Keep old archive files readable by a dedicated restore preflight. A database-only legacy archive is not a complete settings/files backup; show missing categories and do not silently reset them or promise complete restore. Exact legacy restore support requires the owned-store/schema inventory before implementation. Generic import continues to reject complete Tracker archives rather than merge internal tables.
- Preserve existing raw schedule definitions/destinations/watermarks. Do not silently turn a database-only job into a larger full-app backup. The proposed migration is paused review with an explicit explanation and opt-in to the new complete-backup scope; the prior advanced-archive-only migration is superseded.
- Existing JSON-incremental scope correction, compatible GPX/KML watermarks, exact LastSession/no-file behavior and cancellation/full-clear cleanup still need their documented focused source changes. Do not claim that the new backup answer approves every prior schedule migration or file format.
- Cleanup uses exact app-owned staging/share paths and durable cleanup debt on failure. External backup/export copies cannot be recalled. Logical cleanup is not forensic erasure, and an older backup intentionally contains the historical state when it was made.

Source inventory and historical archive evidence: `DATA_GAP_CLOSURE.md`, sections 8–10. This D4 replaces their old no-restore recommendation; full backup is additional production/test-source work before assembly freeze, not an existing capability.

Required focused test source after approval: populated backup/restore across every registered durable store; nondefault DataStore/SharedPreferences settings; custom map inclusion and bundled-map regeneration; source proof/labels/goals/progress roundtrip; active writers and pending deletion at snapshot time; schema/version mismatch; oversized/truncated/path-traversing/hash-invalid archives; disk exhaustion and interruption at each replacement stage; old installation recovery; no replay of CONTROL, provider ownership, active sessions, rewards or scheduled exports; legacy archive missing-settings disclosure; cancellation cleanup and full-clear races. These are future test-authoring obligations, not passing evidence.

## Current source evidence

- `DatabaseExporter.kt` discovers actual `Context.databaseList()` files and copies complete files, one consistent file at a time; it has no row/date filtering or cross-database atomic snapshot.
- Tracking/map/online-map settings and export plans use DataStore; they are not included by `databaseList()`. `BasemapManager` keeps the user's imported `files/basemap/custom.pmtiles` outside databases; the bundled `default.pmtiles` can be recreated.
- `DatabaseImport` explicitly rejects complete Tracker backups with `TrackerDatabaseRestoreRequired`. No complete restore path was found in the bounded import/export, core, app and engine search. The current UI claim "all data and settings" is not supported by the database-only exporter.
- `ExportPlanWorker.resolveDateRange()` currently returns null for missing LastSession. The worker subsequently treats absent bounds as the full range. This is the scope-widening behavior to replace.
- `KmlImport.importCoordinates()` currently increments a synthesized timestamp per point. This is the invented temporal membership to replace for new imports.
- Existing GPX continuity/ambient JSON work and all-day Location are committed at `fa6f51e80a`, still IMPLEMENTED_UNVALIDATED. Reuse that source slice; complete only the documented remaining contracts.

## Closure procedure

1. Record the user's exact answers for D1–D4 below; do not infer approval from elapsed time or preselected options.
2. Resolve any user-requested variation against the existing Runtime/Data/Product contracts; ask only about a new material conflict.
3. Update the affected contract recommendations to the approved choices, preserving historical proposal/review evidence and already settled Location behavior.
4. Commit the decision-only slice locally with IMPLEMENTED_UNVALIDATED. The remaining production work follows its dependency order; the global validation/activation/integration gate remains closed.

## User answers

- D1 CONTROL retention: **approved 48 hours**; exact answer "48 hours retention".
- D2 radio hosting: **approved optional/off by default**; exact answer "Yes" to that explicit question.
- D3 portable files: **clarification pending**; exact answer "I don't get what this is about". Follow-up asks whether to retain selected Tracker files alongside GPX/KML, accurate whole-entry scope and no invented timestamps.
- D4 backup purpose: **all-data backup required**; exact answer "The point of database export was a clean way to backup all data". Follow-up proposes all durable data/settings/files, omits only caches/bundled assets, and asks replacement-after-confirmation versus empty-installation-only restore. No automatic schedule migration is approved yet.
