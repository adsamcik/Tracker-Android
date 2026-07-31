# SQLite runtime durability verification

`ObjectBaseDatabase` enables Room WAL mode and opens its file-backed databases
through `SQLiteXSupportSQLiteOpenHelperFactory`. The build gate verifies the
packaged SQLite binary; effective runtime and durability behavior still require
a focused emulator or device validation when that evidence is needed.

Tracker does not emit raw SQLite version, source ID, PRAGMA, checkpoint, or file
size payloads through application diagnostics. `:core:diagnostics` accepts only
fixed, payload-free diagnostic codes, and Tracebox is the sole crash and
diagnostic backend. Detailed SQLite inspection belongs in explicit test evidence,
not production logs.

## Dependency status

Release builds now package the official SQLite Android binding at SQLite `3.53.3`, vendored at
`core/sqlite-runtime/libs/sqlite-android-3530300.aar`. Its upstream AAR SHA3-256 is
`d7a6e906a0d06472b56ef7bb4824a6be7b5eb5f162b24be0a0bad2e0c917ed93`; all four packaged ABIs
(`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`) contain source ID
`2026-06-26 20:14:12 d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62`.
This source postdates the SQLite `3.51.3` WAL-reset fix. The Room integration is
isolated in `:core:sqlite-runtime`; `:core:base` selects its
`SQLiteXSupportSQLiteOpenHelperFactory`, so application and points databases use
the fixed binding without exposing its `org.sqlite` API to feature modules.

No production runtime telemetry is retained. Migration,
concurrent-write/checkpoint, process-crash, and sudden-power-loss durability are
separate, focused validation concerns.

## Release gate

`verifyReleaseSqliteRuntime` validates the vendored AAR and the standard app release classpaths.
Every release variant's assemble, bundle, and package tasks also depend on that variant's linkage
check, so optional/private flavors are verified when they are actually built without forcing their
credentials during the standard aggregate check. The gate fails closed if the AAR is absent, below
`3.51.3`, has a different upstream SHA3-256, lacks any required ABI, embeds a native library without
the fixed source ID, or the selected release classpath does not resolve those verified native
libraries. That prevents project wiring or dependency resolution changes from silently returning a
release package to another SQLite runtime. Focused emulator or device durability
validation remains separate from packaging when that evidence is required.
