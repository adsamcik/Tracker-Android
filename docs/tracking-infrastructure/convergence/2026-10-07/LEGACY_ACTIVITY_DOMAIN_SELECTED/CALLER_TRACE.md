# Exact meaning and caller trace

Base 7da374b5f4c8a1706525991aafc2c884ebda5226; main59fda665c7099d10b16aa60f505de88bd05d3131.

main SessionActivity primary key is auto-generated; NativeSessionActivity uses separate negative
IDs. TrackerSession.session_activity_id references those labels. main12 database identity is
603376ce119f1b4f1d3e4866aec15b4e; copied JSON SHA
0aa12ca5a69f2a9542ce6f3f4f9b75255f3b358ea68e113791fc66bd1b7d7831.

AppDatabase legacyPublicMigrationsThroughV26 -> LegacyImportRoomCallback -> LegacyV26Importer ->
LegacyV26DatabaseNormalizer -> disposable copied main12 database -> MIGRATION_12_13 writes NULL
primary_activity and original label in legacy_activity_id -> MIGRATION_20_21 retains it ->
TableCopySpec session_segment copies primary_activity -> TripDao.getById -> nullable Trip ->
DomainModelMappers.toModel preserves NULL / DefaultTripRepository NULL legacy source => UNKNOWN.

The former last importer step instead used COALESCE(primary_activity, legacy_activity_id), so
NULL plus custom label7 became Walking in DefaultTripRepository. Removing only that fallback
preserves canonical detected values and distinguishes user labels. Label backfill is separate.

Production open uses LegacyDatabaseUpgradeCoordinator and ObjectBaseDatabase configured callbacks.
The existing test fixture calls the same importer callback/normalization chain with framework
SQLite and Room; it does not prove the production SQLiteX factory or startup driver by execution.
