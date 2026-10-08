# Static production trace

This is source inspection, not execution evidence.

## Startup and shared import workflow

InfrastructureModule provides AppDatabase through its existing singleton. Production
AppDatabase.setupDatabase installs LegacyImportRoomCallback. Room creates the declared target
tables and runs onCreate callbacks in its creation transaction. LegacyImportRoomCallback invokes
LegacyV26Importer while that transaction owns the target writes.

LegacyV26Importer retains LegacyDatabaseRepository.withFileFamilyLock and
LegacyV26DatabaseNormalizer.prepare. Older main12 data is normalized on a disposable copy;
the released original database is never migrated in place. Recognized normalized26–34 inputs
retain the existing canonical requirements. Optional original columns copy their real SQLite
value types when present and SQL NULL otherwise. An absent optional raw compatibility table
returns zero; a present malformed table fails required-column validation before copy.

The importer copies operators and the two compatibility tables, then the existing source
tables, original fields and label adapters. The completion row remains the same
`import_job_receipt` contract and job ID. The existing legacy Location observation derivation
and canonical compatibility identity backfill remain intact. This preserves legacy identity,
not native registration/consent. LegacyDatabaseUpgradeCoordinator continues checking the
same durable receipt before ready publication. Reopen invokes onOpen rather than onCreate.

DataImporter/ImportJobRunner and its RoomImportReceiptStore already use ImportJobReceiptEntity
and the same Room job table; this slice explicitly uses STATUS_COMPLETE from that shared
contract. The feature's suspend runner cannot be called by core/base during synchronous
onCreate without a dependency cycle and an unnecessary new workflow. Protected DatabaseImport
is unchanged and is not used for startup normalization; its portable merge policy is separate.

## Schema and existing consumers

Four existing entities append only nullable original values with null defaults. The two
compatibility entities declare the already-normalized columns and existing Wi-Fi count indexes.
The same Parcelable NetworkOperator restores the released composite text primary key.
AppDatabase registers all three and exposes one small typed DAO; existing DI continues to
provide the database. No extra provider binding, writer, owner or repository is introduced.

The existing unshipped MIGRATION_28_29 adds the columns and tables. Fresh Room creates them
from entities; the current containment preflight includes their names, types, keys and indexes.
Raw fields do not change legacy radio mirror contents or grant a new native source purpose.
Their row IDs still join the original radio parents. Physical child deletion remains owned
by the existing parent mirrors and parent lifetime.

backfillLegacySessionLabels writes existing organization_annotation fields using
`recording_adapter_key = 'segment:' || id`, a deterministic target-local annotation ID,
revision 1 and the existing segment created_at value. Optional name/zone/bounds stay null.
RoomSessionOrganizationRepository resolves the same adapter and Activity catalog, including
virtual native labels. primary_activity remains a separate detected-activity domain.
Missing catalog entries remain unresolved; the raw legacy_activity_id stays on the segment.

## Clear, retention and bounds

AppDatabase.deleteAllCollectedDataForTesting delegates the existing exact clear implementation.
The existing deletion transaction, old-data epoch fences, source retirement and replay
protection remain unchanged. deleteCollectedRows deletes the three new tables before clearing
the remaining collected rows, and excludes LEGACY_IMPORT_JOB_ID from job receipt deletion.

RetentionPipelineWorker.purgeWifiCellData calls deleteWifiCountsOlderThan inside its existing
pending-signal check, Room transaction and authority rechecks. purgeTripData calls
deleteRejectedSessionsOlderThan between parent retention and annotation cleanup under its
existing guarded transaction. Rejected rows are deleted only when both original bounds are
nonnegative and older than cutoff. Operator names have no observation timestamp and survive
time retention. The source-specific raw tables have no invented ownership grant.

The actual all-source legacy radio erase path does not exist: wifiObservationDao.deleteAll and
cellSampleDao.deleteAll are used by the current full-clear wrapper, while ambient/capture
maintenance is purpose-scoped. This slice deliberately does not widen those existing purposes.

Pending History invalidation dependencies when readers start using these tables:
`legacy_location_wifi_count`, `legacy_rejected_tracker_session`, `network_operator`.
The shared list is unchanged because the current History readers do not yet depend on them.

## Authored fixture boundaries

The host tests use the real AppDatabase, legacy normalization/callback, generated Room DAOs,
SQLite original files and exact full-clear method. The parent-retention case composes real DAO
operations in one Room transaction; it does not instantiate the worker or prove startup/retention
authority races. Android migration source uses existing MigrationTestHelper and the production
28→29 migration, then the configured Room builder. All are NOT_RUN. It relies on deferred v29
schema generation. No test demonstrates a live Android sensor, map renderer, user UI or external
file export/import. No test claims the app-level file cleanup coordinator ran.
