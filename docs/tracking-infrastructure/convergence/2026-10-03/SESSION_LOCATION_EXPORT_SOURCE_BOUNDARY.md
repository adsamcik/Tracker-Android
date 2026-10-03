# Exact session Location file selection

Status: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**, 2026-10-03.
Owner: `/root/transfer_adversary_1003`. Parent supplied baseline
`80eb0b0cbe748971b256524e776b2aa3c87b4b40`.

## Selected source

`SESSION_LOCATION_EXPORT_FILES.json` pins four source paths and 24 newly authored, unexecuted cases.
`SESSION_LOCATION_EXPORT_SELECTION.patch` contains only those four deltas. The mixed
`TrackingHistoryReadDao` has a parent-provided HEAD before snapshot and an after snapshot under
`SESSION_LOCATION_EXPORT_SELECTED`. Its two unrelated held working hunks are excluded. Its working
before hash is recorded separately; do not stage that DAO wholesale. The existing standalone
Location date-export methods and point writer remain unchanged.

The existing source-owned Location export reader now has an internal exact-session overload. It
uses existing organization IDs/revisions, the original collected-data epoch, retention/deletion fences,
Room queries, file IDs, format and private export spool. It adds no provider request, persistence
table, format, Hilt binding, registry, UI or public session export action.

## Source trace

- **Native:** select the logical recording, traverse every original service-run and raw-segment
  page, validate reciprocal ownership and compare complete counts. The unique segment run index
  makes count equality meaningful. Original closed bounds and acknowledged retirement are checked;
  edited display dates cannot change membership. The only new DAO query is the raw keyset page;
  unlike the presentation query it leaves orphan/mismatched rows visible. A deleted capture run
  stays excluded while an independently retained sibling can export. Query pages contain at most
  256 members; there is no new whole-session member cap.
- **Migrated logical/physical legacy:** preserve original closed segment intervals and actual
  stored measurements. Imported-only point identities and another native session's points cannot
  enter through a coincident timestamp. A genuine local point remains a local member after a later
  file deduplicates into it. Equal original start/end bounds keep a real singleton point; no
  duration is invented. Overlapping migrated legacy intervals are rejected rather than duplicated.
- **Retrospective local annotation:** the approved interval intentionally selects actual saved
  points in `[from,to)`, independent of current tracking settings. Coordinate-free points and real
  observation spacing remain in the typed file; no points or timestamps are synthesized.
- **Imported annotation:** decode only its existing typed selectors. For each Location archive,
  require its current epoch, complete retained membership, contiguous ordinals and canonical
  portable-ID binding. Read exact sample IDs and deduplicate overlapping archives within the
  existing format point bound. Missing/deleted members fail rather than falling back to dates.
  The existing reported origin is preserved for imported points; native duplicates use saved
  descriptive purpose flags. None of those labels grants acquisition/enrichment authority.

The existing IO dispatcher stages a complete typed file within one Room snapshot. A transient SHA-256
checksum of that exact typed output is compared with a read-only reserialization of the same
selection before and after caller destination copy. One codec/measurement/origin conversion is
shared by staging and both checks. Only staging may assign an absent portable point ID; rechecks
look up existing IDs and fail if one disappeared. No global source revision is compared: unrelated
Steps/radio collection can continue. Original epoch, organization and retention/deletion state are
still checked inside each snapshot. No database transaction is held across destination I/O. Errors return the existing export
result; cancellation propagates and closes the private spool. A post-copy invalidation can leave
bytes in the caller destination: the existing outer transfer owner must discard those bytes and
withhold success. This internal prerequisite does not perform the final user-visible handoff.

## Separate prerequisites and exclusions

The coordinator's `LONGEST_TRIP_SELECTED/LONGEST_TRIP_FILES.json` pins the separately selected
prerequisites (manifest SHA256 `658580e07b43d3486b1066c3093bcc9ce0cb52d2d2610b573f43a1082814b76c`,
patch SHA256 `f243043752f61134b335d6d8f46acb08dd79a3860c0503811085961a4bbdc75a`):
`LocationPurposeDao.savedLogicalTripPointPage`,
`savedLegacyTripPointPage`, exact-ID `savedFileSamples`, and `LocationReadScope.HISTORY_RETAINED` /
`HISTORY_CURRENT_IMPORT_COPY`. Their pending source selection/commit must accompany this slice.
The held `importedHistorySampleIds` query is unused and excluded. Committed organization/archive
DAOs, metadata codec, identity ownership, location codec/spool and existing module dependencies are
reused; feature import/export depends on stats API, never stats implementation.

The complete exact-session port, all-six-source availability preview, detail/exclusion UI, typed
bundle write/import integration and pending native Steps/radio import-membership choices are still
unfinished. This prerequisite exposes no partial bundle option and does not close the session
bundle checklist item or any full-chain assembly prerequisite.

## Authored scenarios and evidence limit

The 24 cases use the actual Room database, exporter, spool and Location codec. They cover replacement
runs/unrelated points; display edits/stale revision; original legacy bounds and imported exclusion;
migrated membership; real singleton timestamps; retrospective gaps/end boundaries; exact archives
and overlap dedup; missing archive/deleted point; native duplicate BOTH origin; 257 runs and 257 tail
points; missing/orphan native members; erase sibling independence; missing original end despite a
display edit; original epoch; current corrections and post-copy mutation without a revision bump;
post-copy organization edit; actual saved Steps/Wi-Fi plus global revision changes before/during
export; removed exact archive membership; a new saved background origin; lost point identity with
no recreation; post-copy epoch change; staging/destination/verification cancellation and private cleanup. Existing
standalone date-export cases remain unchanged.

Only source inspection, authorship, scoped patch construction and byte hashes were performed.
No test, compiler, Gradle, lint, schema, diff-check, device, provider, CI or integration gate ran.
No Git operation or recursive delegation was performed by this author. Independent source review
and the deferred full convergence batch remain required.
