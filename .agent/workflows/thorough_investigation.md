---
description: Protocol for conducting thorough investigations before implementation
---

# Thorough Investigation Protocol

This workflow guides you through a deep-dive investigation to ensure all aspects of a task are understood before coding.

## 1. Context & Scope Definition

- [ ] **Read Recent Context**: Review the last few items in `task.md` and any recent `implementation_plan.md`.
- [ ] **Identify Core Modules**: Determine which modules (e.g., `app`, `tracker`, `sbase`) are primarily affected.
- [ ] **List Key Files**: Identify the main files involved in the request.

## 2. Deep Code Search (The "Thorough" Part)

- [ ] **Global String Search**: Search for key terms (class names, variable constants, string resource keys) across the ENTIRE codebase, not just the module you are working in.
  - Use `grep_search` with `SearchPath="g:\\Github\\Tracker-Android"` (root).
- [ ] **Find Usages**: For every class or function you plan to modify:
  - Check for **Inheritors** (if it's an open class or interface).
  - Check for **Overrides** (if it's a virtual function).
  - Check for **Call Sites** in `sbase`, `sutils`, and feature modules.
- [ ] **Resource Analysis**:
  - If modifying `strings.xml` or `colors.xml`, check for references in `AndroidManifest.xml`, layouts (if any XML left), and Compose `stringResource`/`colorResource`.
  - Check for **Theme Attributes** (`?attr/`) usage if changing colors.

## 3. Dependency & Build Impact

- [ ] **Check Build Configuration**:
  - If adding dependencies, check `build.gradle.kts` in the target module AND `settings.gradle.kts` / `buildSrc` / `libs.versions.toml` for version management.
  - Ensure no conflict with `sbase` or shared dependencies.
- [ ] **Manifest Review**:
  - If adding components (Activities, Services, Receivers), check `AndroidManifest.xml`.
  - specific to this app: Check for specific "merged" manifest issues if working in a library module.

## 4. UI & State Analysis (Compose Specific)

- [ ] **Preview Check**: Search for `@Preview` annotations related to the UI you are changing.
- [ ] **State Flow**: Trace where the state comes from (ViewModel, Repository, DataStore).
  - Verify if state is `SavedStateHandle` backed (survives process death).
- [ ] **Theme consistency**: Ensure usage of `MaterialTheme.colorScheme` or the app's custom `Theme` object.

## 5. Test Coverage Assessment

- [ ] **Find Existing Tests**:
  - Search for `*Test.kt` files matching the modified classes.
  - Check `unit_test_failure_log.txt` to see if related tests are already known failures.
- [ ] **Identify Missing Coverage**: Note down scenarios that are NOT covered by existing tests.

## 6. Output & Planning

- [ ] **Update Implementation Plan**:
  - Add a specific "Investigation Findings" section to `implementation_plan.md`.
  - List potential "Gotchas" or "Risks" discovered.
  - Refine the "Verification Plan" based on found tests (or lack thereof).
