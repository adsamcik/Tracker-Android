# SQLite runtime durability observability

`ObjectBaseDatabase` enables Room WAL mode and opens its file-backed databases through
`SQLiteXSupportSQLiteOpenHelperFactory`, but build-time dependency checks are not proof of the
SQLite binary or durability settings actually active on a device. Every file-backed database
opened through that base class emits one best-effort `event=sqlite_runtime` diagnostic.

The event records the runtime `sqlite_version()` and `sqlite_source_id()`, plus the effective
`journal_mode`, `synchronous`, and `wal_autocheckpoint` PRAGMAs. It also records main-database and
`-wal` file sizes and, when WAL is active, the result of
`PRAGMA wal_checkpoint(PASSIVE)`: `wal_checkpoint_busy`, `wal_frames`, and
`wal_checkpointed_frames`.

The diagnostic samples WAL size before issuing the passive checkpoint. The checkpoint is
non-blocking and is observability only; it is not a full checkpoint, a physical-durability
guarantee, or a replacement for process-crash/power-loss testing. A held reader can therefore be
seen as an incomplete checkpoint or elevated frame count without delaying normal database open.

Diagnostics never modify `synchronous`, checkpoint configuration, or journal mode. They are
best-effort: a failed diagnostic query is recorded as an unavailable field and never prevents the
database from opening. Logcat receives the event even before app logging is initialized; if the
application diagnostic facade has already been installed when the database opens, it receives the
same event. The event contains a database class label only; it never contains the database path.

## Dependency status

Release builds now package the official SQLite Android binding at SQLite `3.53.3`, vendored at
`core/sqlite-runtime/libs/sqlite-android-3530300.aar`. Its upstream AAR SHA3-256 is
`d7a6e906a0d06472b56ef7bb4824a6be7b5eb5f162b24be0a0bad2e0c917ed93`; all four packaged ABIs
(`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`) contain source ID
`2026-06-26 20:14:12 d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62`.
This source postdates the SQLite `3.51.3` WAL-reset fix. The Room integration is isolated in
`:core:sqlite-runtime`; `:core:base` and `:core:logging` select its
`SQLiteXSupportSQLiteOpenHelperFactory`, so the app, points, and logging databases use the fixed
binding without exposing its `org.sqlite` API to feature modules.

The runtime telemetry is intentionally retained: it confirms the loaded version/source on real
devices, while migration, concurrent-write/checkpoint, process-crash, and sudden-power-loss
durability remain separate evidence requirements.

## Release gate

`verifyReleaseSqliteRuntime` validates the vendored AAR and the standard app release classpaths.
Every release variant's assemble, bundle, and package tasks also depend on that variant's linkage
check, so optional/private flavors are verified when they are actually built without forcing their
credentials during the standard aggregate check. The gate fails closed if the AAR is absent, below
`3.51.3`, has a different upstream SHA3-256, lacks any required ABI, embeds a native library without
the fixed source ID, or the selected release classpath does not resolve those verified native
libraries. That prevents project wiring or dependency resolution changes from silently returning a
release package to another SQLite runtime. Runtime telemetry and real-device durability testing
remain mandatory evidence after packaging.
