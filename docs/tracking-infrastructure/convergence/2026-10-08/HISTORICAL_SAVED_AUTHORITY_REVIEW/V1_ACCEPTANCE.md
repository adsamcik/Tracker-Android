# Historical saved authority — independent source review V1

Status: bounded source/test-source acceptance; IMPLEMENTED_UNVALIDATED. Every case NOT_RUN.

Reviewed frozen packet: `HISTORICAL_SAVED_AUTHORITY_SELECTED`, 43 selected paths (27 production, 16 test/support). Original human messages, corrections and approval “Keep valid saved readings after changes” retained. No gates, source execution, device/provider activation, Git mutations or recursive delegation performed.

## Exact source input

- FILES: `c1b6807bb2b01414b51aafc6f75edb87afe375b352ba1ce03c92060c8a358b6c`.
- SELECTION.patch: `82e90614808aa21ac859fdb46c4f33bddf8b85772057b6d46f636c801ff9d621`.
- V1 dependencies: `89f7fb9d283d81b8db12b008b97f47f6f014877fff1cbaaa41d5455421bd6d6e`.
- All 43 selected SHA values match the manifest and use LF; BASE and WORKING_BEFORE raw bytes match their pins. V2 Location source carried with source-equivalent EOL normalization.

## Findings and acceptance

No concrete blocker found in this bounded production repair and authored source cases.

1. Retained reads use exact original named manifest, immutable fact/receipt/authorization and original registration clock. The latest mutable run lease/operation boot no longer replaces the historical recording generation or clock. Numeric original <= current generation is not sufficient alone: the existing exact START action/member/WAL/receipt/manifest lineage checks remain in their respective source readers. Source/purpose, epoch, retained floor and deletion checks remain.
2. The new primitive SavedSessionCutoff reads the earliest FINALIZED intent, immediate ACTIVE predecessor and existing checksum, with exact persisted logical wall/elapsed cutoff. A copied retry cannot become the original Stop. Missing/corrupt original clock preserves independently authentic measured bounds with existing Partial/Stop incomplete outcomes. No new store, inferred timestamp or acquisition authority is introduced.
3. Lifecycle checksum encoding moved unchanged into the existing base layer. Physical hasValidServiceRunTimeline and current command/admission predicates remain exact. Historical-only timeline uses immutable prepared clock; current run/session operation pair checks remain where already required.
4. Activity/Cell maintenance keeps exact original source member, consent, registration/authorization and correction lineage. Saved Steps/native portable reader preserve original manifest clock and recording lifetime. Location saved qualification does not require optional accuracy, while optional map enrichment still requires usable accuracy. Canonical receipt and original sealed purpose remain independently checked.
5. Saved Location summary reads finite keyset batches without an arbitrary total point cap, keeps disconnected sections separate, preserves a valid prefix as partial on continuation failure, and propagates cancellation. Existing actual status/native/range read callers use saved qualification. Full measured Timeline/Calendar dispatcher remains a separate held companion, not proven here.
6. Authored cases reach actual Room/source readers, real coordinator Start→End original-cutoff production, maintenance/export and existing shared invalidation flow. Generation/boot quality-change fixtures are explicitly scalar Room mutations rather than physical provider proof. Fixtures preserve original WAL/actions and independently assert nonempty data; corruption/future generation/deletion and ambient sibling cases test the remaining fences. 26 new historical plus 23 carried Location cases are source only, all NOT_RUN.

## Current composition disposition

Current committed HEAD at this review: `fe03d75a74014adc1453c2c449d5770df3c1dd08`.

Three V1 dependency pins contain additive already accepted Steps continuity from `8ef34ae3f5`: SourceBrokerDao, AuthoritativeSessionCoordinator and DurableSourceIngress. Inspection confirms no original Stop/checksum producer mutation and no changed historical acquisition contract. The new actual coordinator Start→End fixture remains source-compatible. Final metadata must be refreshed to these exact committed dependencies before selection.

Three raw hash drifts are CRLF→LF only: RoomAllDaySourceStatusQuery, UnifiedHistoryRangeReader and RetainedLocationContextReaderTest. Normalized source text is exactly equal. The preserved V1 pins must remain available; this note does not silently replace their provenance.

## Explicit limitations

- ActivityCapturedPortableTransfer and CellCapturedPortableTransfer still contain separate latest-run/operation-boot historical checks; this bounded packet does not claim those native export chains are repaired. Root has their exact follow-up trace.
- Pressure retains pre-existing wall-time start/cutoff guards. This packet makes no new claim about cross-boot wall-clock adjustment mapping or acquisition; original measurement timestamps remain unchanged.
- Location effective imported correction reader, live-total observer and complete measured History source companions remain separate assembly work.
- No compile/test/migration/schema/device/battery/full-chain result is asserted. T25 and all final assembly/integration gates remain open.
