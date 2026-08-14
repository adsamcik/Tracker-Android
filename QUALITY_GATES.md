# Quality gates

The repository-owned local and CI contract is:

```bash
./gradlew ciUnitTest
./gradlew ciCheck --continue
```

`ciUnitTest` and `ciCheck` are defined by the root verification convention plugin. Do not replace
either aggregate with a task-name inference such as `testDebugUnitTest`: that task does not cover
the Kotlin Multiplatform (KMP) JVM and Android host suites.

## Gradle task contract

### `ciUnitTest`

`ciUnitTest` runs 36 explicitly owned tasks. It first runs the included-build contract test
`:build-logic:convention:test`. It also runs `<module>:testDebugUnitTest` for every Android
application or library below:

- `:app`
- `:core:base`
- `:core:common`
- `:core:diagnostics`
- `:core:network`
- `:core:sqlite-runtime`
- `:core:testing`
- `:core:ui`
- `:data:preferences`
- `:domain:geocoder`
- `:domain:points`
- `:feature:activity`
- `:feature:dashboard`
- `:feature:dashboard:api`
- `:feature:game`
- `:feature:game:api`
- `:feature:import-export`
- `:feature:map`
- `:feature:map:api`
- `:feature:statistics`
- `:feature:statistics:api`
- `:feature:tracker`
- `:sensor:activity`
- `:sensor:activity-api`
- `:stats:data`
- `:tracker:api`
- `:tracker:engine`

It also runs both `<module>:jvmTest` and `<module>:testAndroidHostTest` for each KMP module:

- `:core:model`
- `:stats:api`
- `:stats:engine`
- `:tracker:control`

The common test source sets are exercised through those declared targets. A new Android or KMP
module is not covered until its exact tasks are added to `QualityGateContract` and the contract test
is updated.

### `ciCheck`

`ciCheck` depends on these exact root tasks:

| Task | Contract |
| --- | --- |
| `ciUnitTest` | All Android JVM plus KMP JVM/Android host tests listed above. |
| `ciLint` | `<module>:lintRelease` for every one of the 27 Android modules listed above. |
| `detekt` | Repository-wide Kotlin/Kotlin DSL analysis using `detekt.yml`. |
| `checkRoomSchemaDrift` | Runs `:core:base:kspDebugKotlin` and `:domain:points:kspDebugKotlin`, then fails if either module's `schemas/` path is dirty. |
| `ciArchitectureCheck` | Runs `testDebugUnitTest` in `:app`, `:core:common`, `:core:ui`, `:feature:activity`, `:feature:dashboard`, `:feature:game`, `:feature:statistics`, `:feature:tracker`, `:sensor:activity`, and `:tracker:api`, which own the repository architecture/module-boundary tests. |
| `verifyReleaseSqliteRuntime` | Verifies the vendored native SQLite binary and every Android application release runtime classpath/ABI. |

Gradle de-duplicates tasks shared by `ciUnitTest` and `ciArchitectureCheck`. `--continue` is used in
CI so independent failures and their reports are collected; it does not make any dependency
non-blocking.

`QualityGateContractTest` in `build-logic/convention` asserts that every KMP suite and representative
Detekt, lint, Room, architecture, and native failure task is reachable from `ciCheck`.

## Baseline policy

Detekt uses the checked-in `detekt.yml` and `detekt-baseline.xml`. The reviewed baseline records 5,144
finding IDs. Detekt configuration validation and warnings-as-errors are enabled, and an
unbaselined finding fails `detekt`.

Android lint is blocking for applications and libraries (`abortOnError = true`). Each Android module
has a release-variant `lint-baseline.xml`; the initial reviewed set records 1,590 existing findings.
An unbaselined finding fails that module's `lintRelease` and therefore `ciLint` and `ciCheck`.

Baselines are debt ledgers, not suppress-all switches. Fix findings and shrink baselines normally.
If a finding must be accepted, regenerate only the affected baseline and review every added entry:

```bash
./gradlew detektBaseline
./gradlew detekt

# After removing only the affected module's lint-baseline.xml:
GRADLE_OPTS=-Dlint.baselines.continue=true ./gradlew :module:lintRelease --no-build-cache
./gradlew :module:lintRelease --no-build-cache
```

PowerShell uses
`$env:GRADLE_OPTS = "-Dlint.baselines.continue=true"` before the same Gradle command. Never commit a
baseline refresh without inspecting the XML diff and rerunning the task without the generation
property.

## GitHub Actions contract

`.github/workflows/android.yml` is the required build workflow:

| Job ID | Display name | Purpose | Artifact |
| --- | --- | --- | --- |
| `source_hygiene` | `Source hygiene` | Rejects unused string resources. | None. |
| `locale_hygiene` | `Locale hygiene` | Validates every completed locale resource set. | None. |
| `quality_gates` | `Repository quality gates` | Resolves Tracebox, runs `ciCheck`, and verifies Android/KMP XML test evidence. | `quality-gate-reports`: HTML/XML test reports, Android lint reports, and Detekt reports; retained 7 days. |
| `build_validation` | `Build release outputs` | Builds and verifies minified and diagnostic release APKs. | `app-outputs`: APK outputs; retained 7 days. |
| `ci` | `CI` | Stable aggregate that fails unless every preceding Android CI job succeeds. | None. |

The exact `quality-gate-reports` paths are `**/build/reports/tests/**`,
`**/build/test-results/**/*.xml`, `**/build/reports/lint-results-*/**`,
`**/build/reports/lint-results-*.*`, and `build/reports/detekt/**`. The exact `app-outputs` paths are
`app/build/outputs/**` and `*/build/outputs/**`.

The one stable Android workflow status for branch protection is **`Android CI / CI`**. Subordinate
jobs may be renamed or split without changing that required context.

`.github/workflows/codeql.yml` runs CodeQL Action v4 on pushes and pull requests targeting `main` or
`dev/v10`, plus its weekly schedule. Its `analyze` matrix covers `actions` and `java-kotlin`; SARIF is
published to GitHub code scanning rather than an Actions artifact.

`.github/workflows/codacy.yml` is deliberately advisory. It provides some incremental value through
a second vendor's rules and SARIF, but overlaps Detekt, Android lint, and CodeQL and can depend on the
optional `CODACY_PROJECT_TOKEN`. The workflow and job are labeled advisory, the job may continue on
error, and its result must never be a required merge check. The old README grade badge was removed
so the advisory scanner is not presented as a repository gate.

## Required post-merge GitHub settings

This change does not modify GitHub settings. After it is pushed and the new workflows have completed
once, configure the ruleset or branch protection for both `main` and `dev/v10` as follows:

1. Enable **Require status checks to pass before merging**.
2. Enable **Require branches to be up to date before merging**.
3. Require exactly these status contexts:
   - `Android CI / CI`
   - `CodeQL Advanced / Analyze (actions)`
   - `CodeQL Advanced / Analyze (java-kotlin)`
4. Do not require `Android CI` subordinate jobs or
   `Codacy advisory scan / Codacy advisory (not a merge gate)`.
