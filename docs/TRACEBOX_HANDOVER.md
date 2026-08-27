# Tracebox / Tracker product handover

Date: 2026-08-16
Tracker integration branch: `dev/v10`
Tracker activation source: `76ccc1c93153da1f5d82ee2e74121776f4a4d4b6`
Tracebox release source: `b49e8fda94e0bd65859eb080cacd624d9b72a7f5`

## Activation status

Tracker's production catalog points to the immutable `0.1.0-alpha.7` package. Annotated tag
`v0.1.0-alpha.7` (tag object `9888d2b8ab2741516ae44125ec0742a2007d96a1`) resolves to source
commit `b49e8fda94e0bd65859eb080cacd624d9b72a7f5` and tree
`4d9b6f98579c431f99e2731118b332fc27b07fee` on remote Tracebox `main`.

All ten AARs are available from GitHub Packages and the
[public prerelease](https://github.com/adsamcik/Tracebox/releases/tag/v0.1.0-alpha.7). Tracker's
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
- The performance category also supports bounded instantaneous observations. Tracker uses it for a
  single process-start elapsed/CPU sample and lifecycle-bound battery/power and memory snapshots;
  the standard policy leaves this disabled and no polling or aggregation is introduced.
- Tracker exposes process foreground entries, coalesced in-process source-timer firings/requests,
  and existing frame wake-lock duration as distinct fields. None is represented as privileged
  Android system-wide wakeup history.
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
[CI run 31947413036](https://github.com/adsamcik/Tracebox/actions/runs/31947413036).
The bounded host checks cover toolchain/lock validation, generated-artifact drift, malicious
corpora, schema goldens, Gradle plugin contracts, Rust format/clippy/workspace tests, native host
CTest, Android JVM/fixture contracts, release lint, and the static no-network boundary.

The locked reusable Tracebox AAR produced all four library ABIs and passed its prebuilt checks,
including 16 KiB alignment requirements for 64-bit binaries:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

The protected [release run 31948121433](https://github.com/adsamcik/Tracebox/actions/runs/31948121433)
published all ten modules and attached all ten AARs plus the checksum and legal files to the
prerelease. Every Maven POM and AAR endpoint returned HTTP 200. Independently downloaded Maven and
release AARs matched the published checksum file byte-for-byte. Tracker then resolved the complete
ten-module graph from GitHub Packages with strict verification enabled.

The alpha.7 native AAR contains `libtracebox_crashpad.so` for `arm64-v8a`, `armeabi-v7a`, `x86`,
and `x86_64`. Its AAR hash and each embedded ABI payload were checked against the published
checksum and Tracebox's reviewed native-input lock. Tracker deliberately filters production,
development-from-release, and release-validation artifacts to `arm64-v8a`; other AAR payloads are
not part of Tracker's supported phone ABI surface.

Tracebox also has a separately committed isolated-publication workflow. It publishes all ten
modules with `-PtraceboxLocalRepository=<path> publishFoundation`, rejects the user's global
`~/.m2/repository`, and fails closed when `CI` is present.

## Runtime qualification boundary

Tracebox's committed personal-release evidence
`evidence/personal-release/API36-x86_64-4096-alpha4.json` records 13/13 passing checks on the
representative API 36 x86_64/4 KiB emulator. The downstream alpha.5 Tracker smoke remains evidence
for startup, native loading, policy, UI, review/share, and staging-cleanup paths that alpha.7 leaves
unchanged. Alpha.7 adds reduced repeated OOM and bounded stack-overflow capture, immediate
handler-death withdrawal and coalesced recovery, storage-pressure write suspension with explicit
retry points, and bounded UID-wide multiprocess exit-history import. The exact alpha.7 host,
release, published-consumer, and Tracker deterministic tests close those runtime deltas;
emulator-only process-death injection remains in the bounded manual qualification lane.

Tracker's product wiring is protected by focused bootstrap, handler-isolation, policy persistence,
localized UI, upload-disabled, deletion, backup exclusion, license, telemetry, and architecture
tests. The downstream alpha.5 baseline smoke passed on the representative API 36 x86_64/4 KiB
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
annotated immutable tag points to that exact commit. This has been achieved for `v0.1.0-alpha.7`.
It makes the source independently retrievable and lets Tracker attest the tag object, source
commit/tree, package metadata, and AAR digests. It did not require rewriting or squashing working
history.

At Tracker activation source `76ccc1c93153da1f5d82ee2e74121776f4a4d4b6`, the complete
ten-module graph resolved from GitHub Packages with Maven Local disabled, the 24 focused
release-attestation tests passed, canonical selective metadata retained 16 reviewed components,
and strict `:app:compileReleaseKotlin` completed 415 actionable tasks. Repository integration is
accepted only after the rebased branch also passes `ciUnitTest`, `ciCheck --continue`, and clean
`releaseValidation`; those aggregate gates remain the authoritative final record.

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
