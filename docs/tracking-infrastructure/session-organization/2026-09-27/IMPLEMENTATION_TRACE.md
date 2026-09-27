# Session organization implementation trace

2026-09-27. **IMPLEMENTED_UNVALIDATED; IMPLEMENTATION_ONLY.**

Original request: **Implement session organization. Add retrospective sessions, names/activity
labels and editable closed intervals using the planned annotation model.** Original task history,
settled provider freedom/ambient/organization decisions and supplied AGENTS.md remain applicable.
Candidate: codex/source-native-map-context-root, base 5b57140041. Main dev/v10 remains 13f1a99871.
No Gradle, compilation, tests, lint, Detekt, generated Room schema/drift, diff-check, emulator/device,
UI evaluator, battery, CI, release, rollout, activation, integration or push was performed.

## Implemented local organization

| Requirement | Production source and boundary |
| --- | --- |
| One annotation layer | OrganizationAnnotationEntity/OrganizationAnnotationSchema; local UUID, revision, creation/update time, optional unique tagged recording adapter, optional bounds, saved zone, name and existing activity-label reference. No second live lifecycle or copied facts. |
| Retrospective sessions | RoomSessionOrganizationRepository.create validates nonnegative finite half-open bounds, positive interval and end no later than confirmation-time Clock. Overlap and periods without observations are legal. |
| Names and labels | Trimmed optional names, maximum 200 Unicode code points; existing built-in/user activity labels; deleted user label clears reference and advances annotation revision. Built-ins remain virtual references. |
| Editable closed intervals | Repository edit preserves instants unless an explicit interval change is supplied, including zone-only changes. It rechecks acquisition state, annotation revision, current data epoch and label existence inside the transaction. Live recordings permit name/label only. |
| Virtual existing recordings | OrganizationAnnotationDao.visible unifies annotations with logical and true legacy/imported-segment adapters. One logical organization spans physical runs. Materialized or hidden adapters suppress originals before range filtering and limiting. Acquisition cutoff, not drain completion, supplies native end. Creation manifest supplies native display zone. Unknown/reversed clock bounds stay uncertain. |
| Grouping-only removal | Free groups delete; linked groups suppress their adapter and clear descriptive payload. Missing underlying recording permits orphan edit/removal, without changing independently retained observations. Live removal is rejected. |
| History UI | HistoryRoute exposes Add session and paged Sessions; editor shows date/time, saved IANA zone, optional name/label, field-local failures, explicit stale Reload and grouping-only removal confirmation. Existing paged recording browsing/detail/map/delete/Undo remains reachable through Browse recordings. Original recording detail uses its own authority and bounds. |
| DST/zone correctness | SessionEditorTime rejects nonexistent local times and requires choosing an offset for repeated times. Apply time zone preserves resolved instants; unapplied/invalid zones cannot save. Existing missing bounds appear blank; a name-only save cannot invent an interval. |
| Calendar discovery | Metadata presence marker is separate from existing observed intensity/metrics. Continuing groups overlap each applicable day; exclusive midnight end does not mark the next day. Month metadata is bounded to100 and labels incomplete markers; selecting a day opens fully paged groups. |
| Privacy/stale editors | Existing shared RetentionAuthorityOperationLease covers DataStore snapshot through bounded Room operations; conservative guard bootstrap fails closed. Active settlement blocks reads/writes. Full-clear removes annotations with the parent transaction. ViewModel removes private draft/list/calendar on epoch change, gates late callbacks and uses exact ID lookup for Reload beyond the first page. |
| Retention | Annotation expiration joins the approved trip-retention transaction/cutoff. Fully expired free groups delete; expired linked payload is cleared/suppressed before old segment deletion; unnecessary suppression prunes after its adapter disappears. Crossing groups preserve their explicit original bounds. No new retention preference or default. |
| Storage composition | AppDatabase entity/DAO, owned additive DDL, fresh/migration label trigger, strict selected preflight columns/indexes/marker, metadata/shared history invalidation and singleton Hilt platform/common bindings. Older development assemblies are contained; released/unknown data are preserved. Generated Room parity remains deferred. |

## Authored regression sources (uncompiled/unrun)

- SessionOrganizationRoomTest: empty and overlapping groups, metadata-only writes, custom-label-first
  bootstrap, deleted label revision, acquisition cutoff/manifest zone, live edit fencing, moved
  override selection, grouping-only removal, orphan editing, stale revision/epoch, uncertain clock
  repair, Unicode/future/zone validation, tied125-group paging/prepend, exact lookup outside first100
  and real metadata-mutation invalidation.
- OrganizationAnnotationLifecycleTest: actual parent full-clear, approved cutoff/crossing bounds,
  reference-only label sequence versus unknown/source highwaters.
- DevelopmentV28DatabaseContainmentTest and ImportedSourceSchemaAssemblyTest: previous assembly
  containment, annotation table/index/label cleanup requirements and owned DDL/TableInfo parity.
- SessionEditorTimeTest / OrganizationCalendarDaysTest: DST gap/overlap, exact subsecond input,
  malformed drafts, half-open cross-day presence and unavailable metadata.
- SessionOrganizationEditorComposeTest / SessionOrganizationViewModelTest: uncertain name-only
  save, disabled live bounds/removal, removal wording/cancel, cleared label display, privacy epoch
  clearing, late callback rejection, retained stale draft and exact Reload outside initial page.

These are authored coverage, not passing results. Independent source review is recorded separately.

## Explicit assembly boundaries

This slice provides local/native/legacy **organization metadata and editing**. Metadata does not
prove captured sources, distance, movement, Steps, consent, map joins, erase/export scope or XP.
It neither starts nor enriches collection. Actual recorded metrics and original recording actions
continue through existing qualified consumers. Uncertain/orphan inherited times stay unknown until
explicitly corrected; no recording is fabricated from a group.

The whole six-source structural history/detail union, portable organization selectors/import
re-keying/session bundle and full backup/restore are separate file/history assembly work, with
format/restore decisions still recorded in DECISION_CLOSURE. No new format is advertised. This
implementation does not claim those consumers/serializers already understand annotation bounds.
Only explicitly selected output formats may be emitted. The final whole-input freeze, generated
v28 schema, migration/Room/Hilt/Compose gates and practical device proof remain open. Protected
root paths and preserved drafts are untouched; selected V1 families remain selected.
