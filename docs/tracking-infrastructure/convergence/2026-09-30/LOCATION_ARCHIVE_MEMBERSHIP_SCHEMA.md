# Location archive membership — schema input

**IMPLEMENTED_UNVALIDATED**, 2026-09-30. Production and test sources only; no
compilation, Room schema generation, tests, activation or integration performed.

This is the planned source-owned association for one validated Location file,
including already saved and retention-skipped points. The existing verified child
content identity supplies the archive identity; generic import job/entry receipts
continue to own completion. No new import receipt/status/revision protocol exists.

The coordinator must add the following definitions to the selected development
28→29 migration and v29 preflight before freeze. Historical v28 stays unchanged.

```sql
CREATE TABLE IF NOT EXISTS location_file_archive (
  identity TEXT NOT NULL,
  source_format TEXT NOT NULL,
  source_schema_version INTEGER NOT NULL,
  collected_data_epoch INTEGER NOT NULL,
  point_count INTEGER NOT NULL,
  PRIMARY KEY(identity)
);
CREATE TABLE IF NOT EXISTS location_file_archive_point (
  archive_identity TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  portable_id TEXT NOT NULL,
  sample_id INTEGER,
  PRIMARY KEY(archive_identity, ordinal),
  FOREIGN KEY(archive_identity) REFERENCES location_file_archive(identity)
    ON UPDATE NO ACTION ON DELETE CASCADE,
  FOREIGN KEY(sample_id) REFERENCES location_sample(id)
    ON UPDATE NO ACTION ON DELETE SET NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_location_archive_point_identity
  ON location_file_archive_point(archive_identity, portable_id);
CREATE INDEX IF NOT EXISTS idx_location_archive_point_sample
  ON location_file_archive_point(sample_id);
```

All columns have no SQL default. The `identity` primary-key position is 1; child
`archive_identity`/`ordinal` positions are 1/2. `sample_id` is nullable. The reader
checks original/current epochs, complete declared association count, current
retention and the exact portable-ID→sample association in a bounded Room snapshot.
Deleting a sample sets its association to null; replay never rebinds that old
selector. Full collected-data clear deletes archive parents and cascades members.

Room registration adds `LocationFileArchiveEntity` and
`LocationFileArchivePointEntity` to `AppDatabase`. Its actual collected-data clear
calls `locationFileIdentityDao().deleteAllArchives()` before deleting samples.
The archive DAO shares the existing `locationFileIdentityDao()` binding.
