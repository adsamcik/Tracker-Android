# Tracebox / Tracker product handover

Date: 2026-08-15
Tracker branch: `codex/finish-tracebox-product`
Tracebox candidate source: `c38c6f26dab662b3918f25fa19a53675b996de41`

## Activation status

Tracker's production catalog still points to the last remotely published and attested package,
`0.1.0-alpha.3`. The hardened runtime has been qualified locally as the uniquely versioned
candidate `0.1.0-alpha.4-c38c6f2`, but no immutable remote Tracebox tag/package newer than
`v0.1.0-alpha.3` exists yet. A local candidate is evidence, not a production dependency.

Do not change Tracker's catalog, dependency-verification metadata, or release input attestation
until all ten `0.1.0-alpha.4` AARs are published from an annotated tag on remote Tracebox
`main`. Never overwrite alpha.3 or silently resolve a candidate from Maven Local.

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

## Candidate evidence

Tracebox host readiness passed on JDK 21 at `c38c6f2`: all 14 bounded host checks passed, including
toolchain/lock validation, generated-artifact drift, malicious corpora, schema goldens, Gradle plugin
contracts, Rust format/clippy/workspace tests, native host CTest, Android JVM/fixture contracts,
release lint, and the static no-network boundary.

The locked native build produced all four Tracker ABIs and passed the library's prebuilt checks,
including 16 KiB alignment requirements for 64-bit binaries:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

All ten candidate modules were published to a disposable file repository. Tracebox's clean-consumer
smoke resolved the exact ten AARs and byte-compared them with the verified local builds. Tracker
compiled its production and test sources against that candidate, and focused bootstrap, runtime,
UI, telemetry, architecture, localization, deletion, and license contracts passed.

Tracker also assembled the `10.0.0` (`versionCode` 400) debug APK against that exact isolated
candidate. The packaged manifest preserves `android:fullBackupContent="false"`, the restrictive
data-extraction rules, `android:extractNativeLibs="false"`, and the non-exported handler service in
`:tracebox_handler`. The APK contains `libtracebox_crashpad.so` for `arm64-v8a`, `armeabi-v7a`,
`x86`, and `x86_64`.

Tracebox also has a separately committed isolated-publication workflow. It publishes all ten
modules with `-PtraceboxLocalRepository=<path> publishFoundation`, rejects the user's global
`~/.m2/repository`, and fails closed when `CI` is present.

## Runtime qualification boundary

The committed July API 36 x86_64 emulator evidence is historical and contains a native-crash
failure; it is not evidence for this candidate. The only currently installed API 36.1 AVD is a
Google Play image with non-rootable `adbd`, while Tracebox's representative qualification requires
a rootable Google APIs/AOSP image to verify private artifacts and UID-scoped blocked egress. A
read-only cold boot of that installed AVD succeeded before APK assembly, but after assembly the host
had only 1.8 GiB free and the emulator rejected a second launch for insufficient disk space. The
candidate APK therefore was not installed or exercised on-device; static packaging and host-test
evidence must not be reported as a runtime smoke.

Before declaring the immutable alpha release ready, run one bounded current-candidate Tracebox
emulator qualification on API 36 x86_64/4 KiB. Then run the smaller Tracker smoke: launch to ready,
confirm the handler process, change/reset policy across restart, exercise save/share or complete
deletion, and inspect managed/native readiness.

## Why canonical history is required

Canonical Tracebox history means the reviewed release commit is present on protected remote
`main` and a protected annotated `v0.1.0-alpha.4` tag points to that exact commit. This is
required because the current local Tracebox `main` contains work not present on `origin/main`.
Canonicalizing it makes the source independently retrievable and lets Tracker attest the tag
object, source commit/tree, and package digests. It does not mean rewriting or squashing working
history.

Publishing is an external repository mutation and needs explicit maintainer authorization:

1. integrate the isolated-publication commit into local Tracebox `main`;
2. push the reviewed Tracebox commits to remote `main`;
3. create and push the protected annotated `v0.1.0-alpha.4` tag;
4. approve the protected release job and verify all ten immutable package endpoints;
5. update Tracker's version, strict checksums, Tracebox source/tag/AAR attestations, and native ABI
   declaration;
6. run Tracker `ciUnitTest`, `ciCheck --continue`, and `releaseValidation` without local
   overrides; then merge the Tracker feature branch into local `dev/v10`.

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
