# Independent source recheck — all-day shared status V3

## Decision

**Accepted for this narrow static source selection. AD1 and AD2 are resolved.** No additional blocker found in the affected source or fixture seams. This remains **IMPLEMENTED_UNVALIDATED**: all 33 authored scenarios are NOT_RUN; no compilation, tests, lint, schema generation/drift, diff-check, CI, devices, providers or Git mutations were performed. This does not close any full-chain assembly gate or establish integration/device readiness.

Independent review-only agent: `fresh_radio_schema_review_1004`. Original user messages, approved read-only all-day reader reuse, one all-day Steps choice, and the latest tracking-architecture priority were inherited. No recursive delegation.

## Exact reviewed bytes

- `PACKET_PINS.json`: `06e7e87fb02452495c1871eb664e2082ff5f9d7f54fd9e0ab16cc2e00291eefc`.
- `FILES.json`: `e9725aeb68b1ed202fcd6fbff8383a22ed9359c4828b322bf5e0e00ce0b156e7`.
- `SELECTION.patch`: `e3d13f99dc5c4e04f1580f005a8eaea982404e3550ab727885e9f73248f7774f`.
- `AD1_REPAIR.patch`: `cd7c7d64f1773c7dd4879c21d8f7fe948509ce42e46991cb63b9e1000c85140b`.
- `DEPENDENCY_INPUTS.json`: `3d106d945a5eda78c6fe85a21510b736608aa2899f5622dfa969c3b0d9202086`.
- `SOURCE_BOUNDARY.md`: `0fd6f225d6f1ead420c79c8a7d55df6c063fe28aca2dccb3793d642c27bd86e3`.

All 17 pinned metadata files, all 46 applicable BASE/WORKING_BEFORE/WORKING_AFTER/SELECTED artifacts for 13 selected paths, and all 57 dependency hashes match. Only three selected paths differ from V2: the façade, History predicate visibility and Location test. Ten selected paths retain their reviewed V2 pins.

Affected SELECTED pins:

- `RoomAllDaySourceStatusQuery.kt`: `ee928670b4789939d46348003c39b8720b190e98087f1a7d8b0250efbe800984`.
- `UnifiedHistoryRangeReader.kt`: `83b2a25aabdebaf20fa70613a0b9d764e14f997ffec86d93c6d124466ff22823`.
- `RetainedLocationContextReaderTest.kt`: `09976a7b6b61a702afe787c6527b4ca2d5cf64b550194ba2937332c8943384d2`.

The rejected V2 review is preserved and pinned at `PRESERVED_V2_SOURCE_REVIEW.md` (`7d7057c5674f12f891983696a5c53789f94b081ec1e96362f5445fad2fe8ad1b`).

## Repairs traced

**AD1:** The existing History `SourceEvidenceState.hasValidRangeShape` predicate changes only from private to internal. The new façade invokes it immediately after reading the existing state and before snapshot construction or source-specific qualification. Invalid epoch/revision, retention floor, deletion high-water or update time returns the existing `Unavailable(INTEGRITY_UNAVAILABLE)`. History semantics remain unchanged; no parallel validator, new data model or proof mechanism is introduced.

The added real Room case first establishes a valid retained native Location result, independently sets each of five authority fields negative, expects the typed unavailable result, and checks both saved points and malformed state are unchanged by the read. It restores each field and re-establishes the positive result between mutations, then compares the final state to the original. This is a meaningful authored corruption case, not a helper-only mirror. It has not been executed.

**AD2:** The fixture is now accurately named “excludes unverifiable points while retaining local history”. The inventory/boundary no longer claims that an unmarked foreign-ID sample demonstrates authenticated imported-only behavior. The separate actual imported-copy/native-sibling case remains unchanged. No new import behavior or storage was added.

## Committed dependencies and retained scope

The four replaced frozen dependencies independently match committed HEAD `1056464a1376e3574f3a744a6eaf5e3402accaf7` after line-ending normalization: `DefaultAmbientStepsHistoryRepository`, `AmbientStepsFactRevisionDao`, `LocationPurposeDao` and `RadioObservationChildDao`. The V2 trace remains applicable: their held recent/session, registration-plan and export-owner draft additions are not required by this selected background call chain. No held companion is silently selected.

The actual History consumer, native Steps provider window/revision metadata, separate count composer, strict History radio results, valid sibling status with typed gaps, measured child timestamps, native Ambient Location qualification, Off-retained reads, bounded transactions, cancellation/invalidation and Hilt module seams retain the reviewed V2 behavior. The repair introduces no sensor activation, numeric totals, schema, backup feature or framework. Validation and all 25 full chains remain pending.

