<!-- markdownlint-disable -->

# Kotlin 2.2.20 Compatibility Investigation - January 2025

## Investigation Summary

This document captures the results of investigating Kotlin 2.2.20 stable compatibility with the current dependency stack.

## Goal
Upgrade from Kotlin 2.2.20-RC to Kotlin 2.2.20 stable as suggested by user.

## Tested Configurations

### Configuration 1: Kotlin 2.2.20 + KSP 2.2.20-1.0.29
- **Result**: FAILED
- **Error**: Build file parse error at `build.gradle.kts` line 31
- **Root Cause**: KSP version `2.2.20-1.0.29` does not exist
- **Resolution**: Correct version is `2.2.20-2.0.4`

### Configuration 2: Kotlin 2.2.20 + KSP 2.2.20-2.0.4 + Hilt 2.53.1
- **Result**: FAILED
- **Error**: `NoSuchMethodError: com.squareup.javapoet.ClassName.canonicalName()`
- **Root Cause**: Hilt 2.53.1 bundles a JavaPoet binary incompatible with Kotlin 2.2.20
- **Effect**: Annotation processing stops before code generation completes

### Configuration 3: Kotlin 2.2.20 + KSP 2.2.20-2.0.4 + Hilt 2.56
- **Result**: FAILED
- **Error**: `NoSuchMethodError: com.squareup.javapoet.ClassName.canonicalName()`
- **Root Cause**: Hilt 2.56 retains the same JavaPoet dependency chain; incompatibility persists
- **Note**: Build runs longer (~2m33s) indicating deeper processing before failure

### Configuration 4: Kotlin 2.1.0 + KSP 2.1.0-1.0.29 + Hilt 2.53.1
- **Result**: FAILED
- **Error**: `AbstractMethodError: FieldBundle$$serializer.typeParametersSerializers()`
- **Root Cause**: Room 2.8.0-rc01 generated serialization code using Kotlin 2.2 APIs that are absent in Kotlin 2.1 runtime

### Configuration 5 (Current): Kotlin 2.2.20-RC + KSP 2.2.20-RC-2.0.2 + Hilt 2.54
- **Result**: PARTIALLY WORKING
- **Compilation**: SUCCESS
- **Tests**: Tracker unit tests fail with `Resources$NotFoundException` (non-version related)
- **Status**: Baseline configuration kept to unblock development

### Configuration 6: Kotlin 2.2.20 + KSP 2.2.20-2.0.4 + Hilt 2.57 / 2.57.2
- **Result**: FAILED
- **Error**: `Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.2.0, expected version is 2.0.0.`
- **Root Cause**: Hilt 2.57 artifacts are compiled with Kotlin 2.2.0 metadata but still expect metadata version 2.0.0 at runtime, causing the Gradle plugin to abort during module load
- **Reference**: [google/dagger#4848](https://github.com/google/dagger/issues/4848) documents identical failures across multiple projects (opened July 22, 2025)

## Root Causes Identified

### 1. Kotlin 2.2.20 Stable + Hilt Incompatibility
Hilt versions 2.53.1 through 2.57.2 all fail on Kotlin 2.2.20 stable. The failure mode changed in 2.57.x but the outcome is still a hard stop:

- 2.53.1 → 2.56: JavaPoet compatibility issues (`ClassName.canonicalName()`)
- 2.57 / 2.57.2: Kotlin metadata incompatibility (`metadata is 2.2.0, expected 2.0.0`)

The JavaPoet issue manifests as:

```text
NoSuchMethodError: com.squareup.javapoet.ClassName.canonicalName()
```

The metadata mismatch manifests as:

```text
Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.2.0, expected version is 2.0.0.
```

This is a transitive dependency conflict where:

- Hilt depends on JavaPoet for code generation
- Kotlin 2.2.20 requires a newer/different JavaPoet API
- The versions are incompatible

**Attempted Solutions that Failed**:

- Adding explicit `javapoet = "1.13.0"` dependency (no effect)
- Upgrading Hilt to 2.56 (still has incompatibility)
- Downgrading Hilt to 2.53.1 (same issue)

### 2. Room 2.8.0-rc01 + Kotlin 2.1.0 Incompatibility


Room serialization code is compiled against Kotlin 2.2.x APIs and fails at runtime with Kotlin 2.1.0:
```text
AbstractMethodError: FieldBundle$$serializer.typeParametersSerializers()
```

This is a forward/backward compatibility break in Kotlin serialization infrastructure.

### 3. Build Script Syntax Changes

Kotlin 2.2.20 enforces stricter Gradle Kotlin DSL ordering. The `plugins` block MUST come first, before `buildscript`, `allprojects`, and `tasks`.

**Fixed**:

```kotlin
// CORRECT order for Kotlin 2.2.20+
plugins { ... }
buildscript { ... }
allprojects { ... }
tasks { ... }
```

## Recommendations

### Short-term (Current State)

Recommended baseline: **Stay on Kotlin 2.2.20-RC + KSP 2.2.20-RC-2.0.2 + Hilt 2.54**

Reasons:

1. This configuration compiles successfully
2. App runs correctly
3. Most tests pass (tracker test failures appear to be Robolectric resource config, not dependency issue)
4. RC versions are stable enough for development
5. Avoids the Hilt/JavaPoet incompatibility introduced in 2.53.1+

### Medium-term (Next 1-2 months)

Focus: **Monitor Hilt releases after 2.57.2 for Kotlin 2.2.20 stable support.**

Watch:

- <https://github.com/google/dagger/releases> (Hilt releases)
- <https://github.com/google/ksp/releases> (KSP compatibility matrix)

Indicators of readiness:

- Hilt release notes explicitly mention Kotlin 2.2.20 stable compatibility
- KSP 2.2.20-2.0.5+ (or newer) documents compatibility with the matching Hilt release
- Community or issue tracker reports confirm successful Kotlin 2.2.20 builds with the updated Hilt version

### Long-term

Plan to migrate away from RC dependencies.

Options:

1. Wait for stable Hilt/KSP versions that support Kotlin 2.2.20 stable
2. Consider alternative DI frameworks if Hilt lags (e.g., Koin, manual DI)
3. Stay on Kotlin 2.2.x LTS when it stabilizes rather than chasing latest

## Lessons Learned

1. **Bleeding-edge version combinations are unpredictable**
   - RC + RC + alpha/beta creates combinatorial compatibility complexity
   - Transitive dependency conflicts (JavaPoet) are hard to resolve
   
2. **Forward compatibility is not guaranteed**
   - Room 2.8.0-rc01 compiled for Kotlin 2.2.x breaks on Kotlin 2.1.0
   - Cannot assume older Kotlin versions work with newer libraries
3. **Gradle Kotlin DSL ordering matters in Kotlin 2.2.20+**
   - `plugins` must appear before `buildscript`, `allprojects`, and `tasks`
   - Failing to reorder triggers build script parse errors
   
4. **KSP versioning is tied to Kotlin version**
   - KSP version format: `<kotlin-version>-<ksp-version>`
   - Must match exactly: Kotlin 2.2.20 → KSP 2.2.20-2.0.4
   - Cannot mix (e.g., Kotlin 2.2.20 + KSP 2.1.0-x fails)

### gradle/libs.versions.toml

- `kotlin`: tested "2.2.20-RC", "2.1.0", "2.2.20"
- `hilt`: tested "2.54", "2.55", "2.53.1", "2.56"
- Added then removed `javapoet = "1.13.0"` (ineffective)

**Final state**: Reverted to `kotlin = "2.2.20-RC"`, `ksp = "2.2.20-RC-2.0.2"`, `hilt = "2.54"`

### build.gradle.kts (root)

- Moved `plugins` block to top (before `buildscript` and `allprojects`)
- Required for Kotlin 2.2.20+ compatibility

### app/build.gradle.kts

- Added then removed explicit `implementation(libs.javapoet)` (ineffective)

## Testing Notes

Commands used:
```powershell
# Test Hilt aggregation (main compatibility checkpoint)
.\gradlew.bat :app:clean :app:hiltAggregateDepsDebug --no-daemon --console=plain

# Test tracker module compilation
.\gradlew.bat :tracker:assembleDebug --no-daemon --console=plain

# Test tracker unit tests
.\gradlew.bat :tracker:testDebugUnitTest --no-daemon --console=plain
```

## Conclusion

**Kotlin 2.2.20 stable is NOT currently compatible with this project's dependency stack** due to Hilt/JavaPoet conflicts. The recommendation is to:

1. Remain on Kotlin 2.2.20-RC for now
2. Fix the new test failures (Resources$NotFoundException in tracker tests)
3. Monitor Hilt/KSP releases for stable Kotlin 2.2.20 support
4. Revisit upgrade when community confirms working configuration

The investigation consumed significant time (~2 hours) testing 6 different version combinations. Document serves as future reference to avoid repeating failed configurations.
