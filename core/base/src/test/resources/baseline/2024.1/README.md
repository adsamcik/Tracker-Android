# Release 2024.1 database fixtures

These immutable SQLite fixtures represent every Room database schema at the exact
release commit `e8586ade79569fb756ed6736184d6f55cc4ffd3f` tagged `2024.1`.

They are generated from the tag's exported Room schema JSON by:

```powershell
python tools\database-fixtures\generate_2024_1.py
```

`Release2024_1MigrationTest` migrates the real AppDatabase v10 bytes through the
complete chain to the current schema and verifies every seeded field. The
backup integration tests first checkpoint that same complete v10 database,
create and integrity-check a byte-identical private copy, verify the copy is
available before the Room upgrade callback starts, and then validate values in
both the v10 backup and migrated database. The safety copy is kept in Android's
no-backup storage for up to 30 days and can be explicitly exported from Data
Settings.

The module-specific logger and points tests open copies of their release fixtures
through current Room implementations. The release contained two alternative
schemas that both used the production filename `debug_database`; the `_sbase`
and `_logger` fixture suffixes distinguish those source variants only. Retired
Challenge progress remains intact as an immutable legacy archive, while the
Stats database is a rebuildable cache. Their session references are verified
against preserved AppDatabase IDs.

| File | Release schema | Room identity hash |
|---|---:|---|
| `main_database.db` | 10 | `0450ddcfb62c0bb907dbd58ebb962e11` |
| `debug_database_sbase.db` | 1 | `3413cc2a29d2275b27a7c2331a5caece` |
| `preference_database.db` | 1 | `0608179a3962e9cd5340a093ba0378d5` |
| `debug_database_logger.db` | 1 | `31f6daf838801d8e7a9fa006060fa58a` |
| `points_database.db` | 1 | `67361523b8053dd6727d713508ad45ab` |
| `stats_database.db` | 1 | `9bcc267288495935f80c47544aaf0bbf` |
| `challenge_database.db` | 1 | `a00ec9e9199c6d7d4cf080768b809b93` |

Do not edit the database files by hand. Change the deterministic seeder and
regenerate all copies together.
