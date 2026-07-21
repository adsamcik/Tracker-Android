# SQLite runtime durability observability

`ObjectBaseDatabase` enables Room WAL mode, but a dependency declaration alone is not proof of
the SQLite binary or durability settings actually active on a device. Every file-backed database
opened through that base class now emits one best-effort `event=sqlite_runtime` diagnostic.

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

The version catalog remains pinned to `com.github.requery:sqlite-android:3.49.0`. As of this
change, this is the newest reproducible release published by that coordinate. Requery's upstream
repository has later, unreleased work, but no published fixed release suitable for a dependency
catalog pin; an unversioned branch or snapshot would make builds non-reproducible and still does
not provide the required SQLite 3.51.3 WAL-reset fix.

When the binding publishes a compatible SQLite 3.51.3-or-newer artifact, update the catalog and
verify the emitted `sqlite_version` and `sqlite_source_id` on real devices. The runtime telemetry
is intentionally retained after that upgrade because process-crash durability and sudden-power-loss
durability remain separate measurements.
