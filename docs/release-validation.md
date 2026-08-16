# Release validation evidence

`releaseValidation` is a non-deploying verification task for the release artifact Play would
review. It builds `bundleRelease`, uses the pinned bundletool classpath and the installed SDK's
`aapt2` to create a universal APK set, and writes evidence beneath `build/release-evidence/`.
Bundletool applies only its local debug key to that representative APK; the task never supplies
release signing material or publishes anything. Production signing and Play publication remain
manual.

Run it from a clean commit with an Android SDK, JDK 21, and Python 3 available:

```text
./gradlew releaseValidation --no-configuration-cache --no-daemon --stacktrace --console=plain
```

The compact `release-manifest.json` binds the source commit/tree and unique app version to the
AAB, APK set, representative APK, merged manifest, BundleConfig protobuf/JSON, R8 mapping,
native-symbol archive, dependency metadata, Room schemas, vendored SQLite input, MapLibre and
Tracebox coordinates, and CI run identity. Every recorded file has a SHA-256 digest.
The workflow runs `checkRoomSchemaDrift` first and hashes every recursively nested, Git-tracked
Room schema JSON file.

The native-symbol archive deterministically contains every symbol table AGP can extract from the
packaged libraries. The manifest lists each covered ABI/library and explicitly lists packaged
prebuilt libraries for which the upstream AAR exposes no extractable symbol table. AGP can extract
tables for additional ABIs offered by an upstream AAR even when application packaging filters those
ABIs out; the collector excludes those unshipped tables and still rejects an unknown library within
the packaged ARM64 ABI.

Native validation is allowlist-based and fails for unknown or missing `.so` files. It checks
every ELF LOAD segment, requires 16 KiB alignment for the 64-bit Play requirement
(`arm64-v8a`), rejects every non-ARM64 packaged ABI, requires GNU RELRO
for every `.so`, requires `PAGE_ALIGNMENT_16K` in the bundle configuration, and runs
`zipalign -c -P 16 -v 4` on the generated universal APK. Controlled bad-input fixtures run via
`testReleaseEvidence` and prove the alignment, RELRO, allowlist, fixed-coordinate, digest, and
manifest-completeness gates fail closed.

## Cryptographically closed inputs

The Gradle wrapper distribution is SHA-256 pinned. GitHub Actions use reviewed full commit SHAs;
Dependabot proposes grouped weekly updates against `dev/v10` so version/SHA changes remain normal
reviewed pull requests.

Gradle dependency verification is deliberately selective. Strict checksums cover:

- every `io.github.tracebox` module in the fixed `0.1.0-alpha.7` release graph;
- the MapLibre Compose and packaged `org.maplibre.gl:android-sdk` inputs;
- the AndroidX Graphics Path and DataStore artifacts that contribute native libraries; and
- the pinned bundletool artifact and metadata used to create the representative APK set.

Other Central artifacts are explicitly trusted by scoped rules rather than copied into a large,
high-maintenance checksum blanket. The vendored SQLite AAR is outside Gradle module verification;
the release task validates its recorded SHA-256 and SHA3-256 directly.

Tracebox `0.1.0-alpha.7` is a fixed alpha, never a SNAPSHOT. The reviewed inputs bind it to tag
`v0.1.0-alpha.7`, annotated tag object `9888d2b8ab2741516ae44125ec0742a2007d96a1`, source commit
`b49e8fda94e0bd65859eb080cacd624d9b72a7f5`, source tree
`4d9b6f98579c431f99e2731118b332fc27b07fee`, and the direct AAR SHA-256 values in
`release/release-inputs.json`.

## Reviewed update procedure

After intentionally changing one of the selected coordinates, review its publisher/source and
resolved release graph, then regenerate and reduce metadata:

```text
./gradlew --write-verification-metadata sha256 :app:dependencies --configuration releaseRuntimeClasspath
./gradlew --write-verification-metadata sha256 dependencies --configuration releaseBundletool
./gradlew --write-verification-metadata sha256 :app:releaseOssLicensesTask --no-configuration-cache
python tools/selective_verification_metadata.py
python tools/selective_verification_metadata.py --check
./gradlew testReleaseEvidence
./gradlew releaseValidation
```

Inspect every retained checksum change before committing. Do not use lenient verification for a
release decision: it reports mismatches without failing the build.

References: [Android 16 KB guidance](https://developer.android.com/guide/practices/page-sizes),
[zipalign](https://developer.android.com/tools/zipalign), and
[Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html).
