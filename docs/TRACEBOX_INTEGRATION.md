# Tracker and Tracebox integration

Tracker and Tracebox are developed as two repositories with one product contract. Tracebox owns
local crash capture, ANR capture, structural diagnostics, package review, package lifecycle, and
diagnostic deletion. Tracker owns installation timing, privacy-safe call sites, user navigation,
product copy, runtime defaults, and the decision to expose only save/share actions.

## Runtime contract

- Tracker installs Tracebox during `Application.attachBaseContext()`, before content providers and
  Hilt startup. The private `:tracebox_handler` process skips Tracker graph initialization.
- Tracebox is the only crash and diagnostics backend in every Tracker variant. There is no legacy
  logger, alternate crash handler, diagnostic database, migration flavor, or fallback writer.
- Tracker log templates are static. Arguments are limited to public structural counts, durations,
  booleans, and enums. Precise coordinates, tracked identifiers, exception messages, and arbitrary
  domain objects do not enter diagnostic calls.
- Tracebox treats strings and unknown objects as private by default, formats only after the runtime
  gate, and does not persist exception messages.
- Release diagnostics are payload-free. Tracebox has no networking permission, endpoint, HTTP
  client, upload worker, analytics integration, or automatic egress path.
- Managed capture remains available when the optional native runtime cannot initialize. Native
  readiness reports an explicit degraded state instead of disabling managed crash, ANR, exit, and
  structural diagnostics. A production release must nevertheless package the native library for
  all Tracker ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- Tracker persists the requested runtime policy. A fresh install starts with
  `TraceboxPolicy.standard()`, and the UI's restore-defaults action reapplies that same product
  default rather than a separate UI-only profile.

## User disclosure and deletion

Tracker embeds Tracebox's reusable diagnostics screen under Settings. The casual action prepares a
package for review and then opens Android sharing; saving is also available. Direct upload is
disabled. The advanced controls expose local status, capture/runtime policy, reset, and diagnostic
deletion. Every library status, capture label, duration, approval, failure, save/share, and
deletion message is resolved through Tracebox's resource-backed string contract. Tracker supplies
localized product title and description resources and packages every declared app locale.

Reviewed packages are short-lived capabilities. Tracebox retires their byte arrays after upload,
save, share, replacement, or screen disposal. The package cannot be reused after retirement.

There are two deletion entry points:

1. The diagnostics screen deletes every Tracebox-owned record and staged package.
2. Tracker's app-wide collected-data deletion invokes the same complete Tracebox deletion after
   Tracker data and export watermarks. If handler-owned data is temporarily unavailable, Tracker
   retains a durable deletion marker and retries the full transaction on startup.

Both deletion paths perform blocking storage work away from the main thread.

Android cloud backup and device-to-device transfer are disabled for the application and exclude
every credential- and device-protected root, file, database, shared-preference, and external
domain. Tracebox state cannot bypass the product deletion contract through Android restore.

## License contract

Tracker's open-source license screen loads Tracebox's canonical
`tracebox_third_party_notices` resource from the pinned AAR. It verifies and displays the complete
sections for Crashpad, mini_chromium, linux-syscall-support, zlib, googletest, and Chromium build
tools. Tracker does not maintain a second copy that could drift from Tracebox's source lock.

## Local candidate validation

An unpublished Tracebox candidate must never replace or shadow Tracker's immutable dependency in a
developer's global Maven cache. Publish it to an isolated repository with a unique candidate
version, then opt Tracker into both local-only seams:

```text
# Tracebox repository
./gradlew.bat -PtraceboxVersion=<candidate-version> \
  -PtraceboxLocalRepository=<isolated-repository> \
  publishFoundation

# Tracker repository
./gradlew.bat -PtraceboxLocalRepository=<isolated-repository> \
  -PtraceboxVersionOverride=<candidate-version> \
  --dependency-verification=off ciUnitTest
```

Tracebox publishes all ten modules directly to the named disposable repository and rejects both CI
use and the user's global `~/.m2/repository`. Tracker resolves that path before authenticated
package repositories, suppresses Maven Local while it is active, and applies the version override
to every `io.github.tracebox`
module in the resolved graph. Both properties are rejected whenever `CI` is present, so release and
CI builds continue to use only the catalog-pinned immutable package and strict verification
metadata.

## Immutable release activation

Local candidate success is necessary but does not change Tracker's production dependency. To
activate a new Tracebox release:

1. Put the reviewed Tracebox commit on the repository's remote `main`, then run the complete host,
   Android, Rust, generated-artifact, AAR, ABI, and 16 KiB alignment gates from that exact history.
2. Publish one immutable version containing all ten Android modules. Do not overwrite an existing
   version.
3. Resolve the clean consumer smoke project against that package and verify the exact AAR set.
4. Update Tracker's single Tracebox version in `gradle/libs.versions.toml`, selective dependency
   verification checksums, and `release/release-inputs.json` source/tag/artifact attestations.
5. Run Tracker's `ciUnitTest`, `ciCheck --continue`, and `releaseValidation` without either local
   candidate property.

“Canonical Tracebox history” means the release commit is present on protected remote `main` and an
annotated immutable tag points to that same commit. It prevents a tag or package from naming
unpublished local-only history, makes the source independently retrievable, and lets Tracker bind
the tag object, source commit/tree, and AAR digests in `release/release-inputs.json`.

Tracker release validation retains R8 `SourceFile` and `LineNumberTable` metadata and records the
mapping, native-symbol archive, Tracebox coordinates, source identity, and build identity. Offline
retrace/symbolication must match the exact release identity; it never guesses across builds.

Until that immutable package exists, Tracker's default build deliberately remains on its last
attested Tracebox release. The local override is validation evidence, not a production dependency.
