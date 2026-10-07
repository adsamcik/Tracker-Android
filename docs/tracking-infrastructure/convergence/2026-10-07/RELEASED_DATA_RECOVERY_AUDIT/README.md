# Released-data recovery audit

REVIEW_ONLY / IMPLEMENTATION_ONLY. Every referenced/authored test remains NOT_RUN.
No build/test/schema/lint/diff/CI/device/provider gate, Git mutation or recursive delegation.
Only this audit and the separately authorized clean-HEAD selection packet were authored.
Shared production/test paths, protected root files and frozen importer drafts are untouched.

Released baseline: main59fda665c7099d10b16aa60f505de88bd05d3131, Room schema12. Prior trace:
bf52dc1145168a9de3bb3a68a03df611ac832d48. Historical audit baseline:
7da374b5f4c8a1706525991aafc2c884ebda5226, candidate29. PINS.json preserves 8 main and 43 baseline
source inputs; all43 are unchanged since the prior trace. The main12 JSON is byte-identical to
core/base's copied12 JSON, SHA0aa12ca5a69f2a9542ce6f3f4f9b75255f3b358ea68e113791fc66bd1b7d7831,
identity603376ce119f1b4f1d3e4866aec15b4e. This audits source, not generated29 schema agreement.

## Actual upgrade and preservation boundaries

ObjectBaseDatabase's production builder -> configured AppDatabase callbacks ->
LegacyImportRoomCallback -> LegacyV26Importer -> LegacyDatabaseRepository family lock ->
LegacyV26DatabaseNormalizer. main_database stays the original vault. Normalization/checkpoint
and disposable staging copy use the frozen 12-to26 conversion; Room creates main_database_v27
at current29 and the importer copies its specified subset atomically in onCreate.
LegacyDatabaseUpgradeCoordinator publishes Ready only after opening target and checking its
completion marker. Original vault deletion requires an external completed copy because active
recovery is known incomplete. Therefore the gaps here are absent active representation, not
physical loss of the preserved original file.

DATA_MAPPING.json records the exact omissions. MIGRATION_12_13 and MIGRATION_20_21 preserve extra
original values in staging; LegacyV26Importer.importedTables omits their columns and both legacy
compatibility tables. network_operator is explicitly skipped. Radio child mirrors installed before
the import correctly copy/update imported legacy radio rows but cannot recover omitted metadata.
Location's canonical E7 values remain available; separate original precision does not.

## Concrete correctness blocker and separated fix

main SessionActivity IDs identify chosen labels; native label IDs are a separate negative domain.
MIGRATION_12_13 deliberately sets primary_activity=NULL and saves legacy_activity_id. The importer
replaces NULL with that arbitrary label through COALESCE. TripDao preserves the resulting value;
DefaultTripRepository maps7 to Walking,8 to Running,1 to Cycling,0 to Driving. A custom label7 named
My cycling label is therefore falsely Walking. The SQL source sketch is NOT_RUN. The separately
reviewable LEGACY_ACTIVITY_DOMAIN_SELECTED packet removes only the fallback and authors actual
populated schema12/compatible26 Room+TripDao cases. That routine fix is now selected at
6c7dd22d737063ccf107f2c320312e59d8c361a8; the importer no longer substitutes the chosen label for
detected Activity. Other audit mapping inputs remain unchanged. Label backfill/storage is not implemented.

## Relevant existing test source and what it actually covers

- LegacyV26ImportTest.populated released v12: real committed12 schema -> actual normalization and
  Room callback; asserts basic row counts, network name/tower ID and untouched original source.
  Its fixture uses label7 named Walk, masking the activity-domain collision. It does not assert
  preservation of the missing columns/tables, nor production SQLiteX/Hilt startup execution.
- Other LegacyV26ImportTest methods cover legacy-null edge rows, explicit canonical26 data,
  optional fallback absence, compatible additive34, rejected35, low storage, retry, isolated
  downgraded writer, a25k-row copy and the original vault export/delete/reopen boundary.
- RadioObservationChildMigrationTest directly invokes 28-to29 on explicit SQLite parent fixtures,
  checks nullable old Steps origin/carrier markers, legacy child data, copy triggers and index
  ownership. It is not the complete Room migration gate.
- DevelopmentV28DatabaseContainmentTest authors real preflight classifications plus composed
  guarded delegates. Fresh29/absent Steps origin/carrier/group/child scenarios preserve/block
  incomplete development inputs. Physical SQLiteX instrumentation remains a separate NOT_RUN gate.
- DefaultTrackingParamsRepositoryTest and RoomSourcePolicyRepositoryTest author single-Steps
  projection and consent-after-setup behavior. Unsupported old split intent is not a released
  migration requirement; only main's single toggle is released.

## Smallest preservation proposal

PRESERVATION_PROPOSAL.md lists exact target reuse, required additions and cleanup authority.
It is a proposal requiring approval; no new field/table/annotation/UX is implemented. T18 overall,
all prerequisites and final convergence remain open. No stale-schema finding or unexecuted-test
status is substituted for a concrete production gap.

## Proposal V2 factual and scope clarification

REVIEW_V1 preserves the prior exact proposal, mapping, README and hash manifest. Proposal V2
corrects NetworkOperator to its actual current Parcelable-without-Entity form, restores the
released annotation/import on the same class, keeps optional normalized26 compatibility tables
absent-safe, and leaves finite untimed operator names until explicit all-Cell/full clear.
No new storage or cleanup behavior is implemented or approved by this documentation revision.
PINS.json remains the historical source snapshot; it is not a current-code completion claim.
