# Tracebox / Tracker product handover

Date: 2026-08-16
Tracker integration branch: `dev/v10`
Tracker qualification source: `63c0be268e0b1cbfb140c66cc079e56a39eabff6`
Tracebox release source: `75651a0f43ac7e8308cab3814e7e1f0241018cf4`

## Activation status

Tracker's production catalog points to the immutable `0.1.0-alpha.5` package. Annotated tag
`v0.1.0-alpha.5` (tag object `d5aa7408ae8398d5b931b13e858943ef062c6ee2`) resolves to source
commit `75651a0f43ac7e8308cab3814e7e1f0241018cf4` and tree
`b66944db50db1d70474ed34e28497d7bb7c1438e` on remote Tracebox `main`.

All ten AARs are available from GitHub Packages and the
[public prerelease](https://github.com/adsamcik/Tracebox/releases/tag/v0.1.0-alpha.5). Tracker's
strict dependency metadata and `release/release-inputs.json` bind the resolved graph and direct
artifacts to that release. A local candidate remains validation evidence, never a production
dependency, and must not shadow the catalog pin through Maven Local.

## Product integration completed

- Tracebox installs from `Application.attachBaseContext()` before content providers and Hilt
  startup. The `:tracebox_handler` process returns before generated Hilt
  `Application.onCreate()` and initializes no Tracker graph.
- Tracker uses immutable `LogTemplate` values and classified primitive arguments. Dynamic
  templates, exception messages, coordinates, network identifiers, URIs, filenames, user text,
  database content, and stable user/device identifiers are excluded.
- Unexpected failures keep the throwable for structural stack capture; low-volume lifecycle
  breadcrumbs and independently gated performance timings remain.
- Tracker requests the standard policy, persists the requested policy, and exposes the same standard
  policy through restore defaults.
- The diagnostics UI is resource-backed. Tracker packages every declared locale, provides localized
  title/summary resources, disables upload, and enables review, save, share, policy controls, reset,
  and complete diagnostic deletion.
- Approved package bytes are bounded and explicitly disposable. Replacement, policy change,
  save/share completion, deletion, and screen disposal retire owned bytes and staging.
- App-wide collected-data deletion quiesces Tracker, deletes Tracker data/export watermarks, then
  requests complete Tracebox deletion. Partial handler deletion retains a durable no-backup marker
  and retries at startup.
- Android cloud backup and device-to-device transfer are disabled and exclude every app-storage
  domain.
- Release validation retains managed source/line metadata and records R8 mapping, native symbols,
  source identity, dependency coordinates, and build identity for exact-match offline
  retrace/symbolication.
- Tracker's license UI loads Tracebox's canonical notice resource and verifies all pinned Crashpad
  component sections.

## Published release evidence

Tracebox host and release readiness passed on JDK 21 at the exact release commit in
[candidate CI run 31920730963](https://github.com/adsamcik/Tracebox/actions/runs/31920730963).
The bounded host checks cover toolchain/lock validation, generated-artifact drift, malicious
corpora, schema goldens, Gradle plugin contracts, Rust format/clippy/workspace tests, native host
CTest, Android JVM/fixture contracts, release lint, and the static no-network boundary.

The locked native build produced all four Tracker ABIs and passed the library's prebuilt checks,
including 16 KiB alignment requirements for 64-bit binaries:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

The protected [release run 31921376667](https://github.com/adsamcik/Tracebox/actions/runs/31921376667)
published all ten modules and attached all ten AARs plus the checksum and legal files to the
prerelease. Every Maven POM and AAR endpoint returned HTTP 200. Independently downloaded Maven and
release AARs matched the published checksum file byte-for-byte. Tracker then resolved the complete
ten-module graph from GitHub Packages with strict verification enabled.

The alpha.5 native AAR contains `libtracebox_crashpad.so` for `arm64-v8a`, `armeabi-v7a`, `x86`,
and `x86_64`. Its AAR hash and each embedded ABI payload were checked against the published
checksum and Tracebox's reviewed native-input lock. Tracker's release input allowlist now requires
all four ABIs.

Tracebox also has a separately committed isolated-publication workflow. It publishes all ten
modules with `-PtraceboxLocalRepository=<path> publishFoundation`, rejects the user's global
`~/.m2/repository`, and fails closed when `CI` is present.

## Runtime qualification boundary

Tracebox's committed personal-release evidence
`evidence/personal-release/API36-x86_64-4096-alpha4.json` records 13/13 passing checks on the
representative API 36 x86_64/4 KiB emulator. Alpha.5 changes only release engineering and the
versioned hash lock for the already-qualified native inputs; it does not change runtime code,
public API, schema, or privacy behavior. The alpha.4 emulator evidence therefore remains the
accepted runtime qualification for this release lineage, while the exact alpha.5 host, package,
ABI, and consumer checks close the release delta.

Tracker's product wiring is protected by focused bootstrap, handler-isolation, policy persistence,
localized UI, upload-disabled, deletion, backup exclusion, license, telemetry, and architecture
tests. The downstream alpha.5 smoke then passed on the representative API 36 x86_64/4 KiB
emulator (`Codex_Tracebox_Release_API36`): strict `:app:installDebug` completed 657 actionable
tasks, two cold launches completed without a fatal exception, the application and private handler
were simultaneously live, and both processes loaded the packaged x86_64
`libtracebox_crashpad.so`.

The same smoke observed persisted policy/profile/control and identity state across force-stop and
restart, the marked no-backup Tracebox root, a durable/ready localized diagnostics screen, exact
review of one 109-byte privacy-transformed package, Android sharing with no upload action, and
removal of the staged `.tbdiag` after screen disposal. The structured cross-repository result is
`Tracebox/evidence/personal-release/tracker-alpha5-integration.json` at Tracebox commit
`570951f` or later. Deterministic tests remain authoritative for failure-boundary and deletion
interleavings that a smoke is not intended to replace.

## Canonical history achieved

Canonical Tracebox history means the reviewed release commit is present on remote `main` and an
annotated immutable tag points to that exact commit. This has been achieved for `v0.1.0-alpha.5`.
It makes the source independently retrievable and lets Tracker attest the tag object, source
commit/tree, package metadata, and AAR digests. It did not require rewriting or squashing working
history.

Tracker's pre-integration gates passed with no local candidate properties and strict dependency
verification:

- clean `releaseValidation`: 1,172 actionable tasks; exact alpha.5 coordinates, the four-ABI native
  set, R8 mapping, native-symbol archive, Tracker source identity, and build identity were recorded;
- `ciUnitTest`: 994 actionable tasks and all repository-owned host/JVM suites passed;
- `ciCheck --continue`: 1,987 actionable tasks and the complete lint, Detekt, architecture,
  dependency-metadata, release-evidence, and host-test aggregate passed.

Those gates were rerun after the integration branch was rebased onto the latest local `dev/v10`;
the verified integration was then merged as commit
`63c0be268e0b1cbfb140c66cc079e56a39eabff6`.

## Local candidate command

```text
# Tracebox
./gradlew.bat -PtraceboxVersion=<unique-candidate-version> \
  -PtraceboxLocalRepository=<disposable-repository> \
  publishFoundation

# Tracker
./gradlew.bat -PtraceboxLocalRepository=<disposable-repository> \
  -PtraceboxVersionOverride=<unique-candidate-version> \
  --dependency-verification=off ciUnitTest
```

These properties are local validation seams only. Production and CI always resolve the
catalog-pinned immutable package with strict dependency verification.
