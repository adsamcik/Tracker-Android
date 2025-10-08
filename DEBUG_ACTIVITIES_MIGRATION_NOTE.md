# Debug Activities Migration Note

**Priority:** LOW  
**Status:** Deferred (debug-only tooling)  
**Date Created:** 2025-10-05

---

## Overview

Two legacy view-based debug activities remain in the codebase:

1. `StatusActivity` – displays tracker system status (lock states, WorkManager jobs)
2. `LogViewerActivity` – displays log entries from Room database

Both are debug-only tools, rarely used in production scenarios. They can be migrated to Compose and folded into the existing `DebugRoute` after higher-priority user-facing migrations are complete.

---

## Current State

### StatusActivity

**File:** `app/src/main/java/com/adsamcik/tracker/app/activity/debug/StatusActivity.kt`  
**Lines:** 113

**Purpose:** Displays runtime state of tracking system:

- TrackerLocker time lock status
- TrackerLocker charge lock status
- Combined lock state
- WorkManager wait-for-recharge job status

**Implementation Details:**

- Extends `DetailActivity` (legacy base class)
- Uses XML View-based UI: `ConstraintLayout` + `TextView` created programmatically
- Dynamically generates key-value pairs with color-coded values (green=true, red=false, blue=other)
- Reads state synchronously from `TrackerLocker` and `WorkManager`

**Launch Point:**

- `DebugPage.kt` line 57: preference click handler
- Registered in `AndroidManifest.xml` (exported=true, line 110)

**Dependencies:**

- `TrackerLocker` (tracker module)
- `WorkManager` (AndroidX)
- `DetailActivity` (legacy base; uses `StyleController`)

**Blockers:** None (pure display logic)

---

### LogViewerActivity

**File:** `app/src/main/java/com/adsamcik/tracker/app/activity/debug/LogViewerActivity.kt`  
**Lines:** 78

**Purpose:** Displays structured logs from Room database (`LogDatabase.genericLogDao()`).

**Implementation Details:**

- Extends `DetailActivity`
- Uses RecyclerView + custom adapter (`BaseRecyclerAdapter<LogData, ViewHolder>`)
- Inflates XML layout: `R.layout.layout_log_item`
- Loads last 1000 log entries ordered descending (coroutine on `Dispatchers.Default`)
- Displays timestamp, source, message, and data fields

**Launch Point:**

- Imported in `DebugPage.kt` line 14, but **NOT actively launched** (no preference click handler found)
- No registered activity in `AndroidManifest.xml` (activity exists but not declared)

**Dependencies:**

- `LogDatabase` + `LogData` (logger module)
- `BaseRecyclerAdapter` (recycler lib)
- XML layout: `layout/layout_log_item.xml`
- `DetailActivity` base

**Blockers:** None (pure display logic)

**Note:** Appears to be **orphaned** — imported but not actively used in current debug menu.

---

## Existing Compose Debug Route

**File:** `app/src/main/java/com/adsamcik/tracker/app/debug/DebugRoute.kt`  
**Lines:** 76

**Current Features:**

- Clear preferences (with confirmation dialog)
- Scaffold + basic column layout

**Opportunity:**

- Can accommodate additional debug sections:
  - System status section (replacing `StatusActivity`)
  - Log viewer section (replacing `LogViewerActivity`)
  - Existing actions from `DebugPage` (dummy data, crash tools, notifications)

---

## Migration Strategy (Deferred)

### Phase 1: Integrate StatusActivity into DebugRoute

1. Add "System Status" expandable section or separate bottom-sheet/dialog
2. Read `TrackerLocker` state + `WorkManager` jobs reactively (Flow if possible, else remember + launched effect)
3. Display key-value pairs using Compose `Text` with color-coded styling
4. Remove `StatusActivity.kt`
5. Remove preference click handler from `DebugPage.kt`
6. Remove manifest entry

### Phase 2: Integrate LogViewerActivity into DebugRoute

1. Add "Log Viewer" button/section
2. Query `LogDatabase` via repository/ViewModel pattern
3. Display logs in `LazyColumn` with stable keys (log ID)
4. Format timestamp, source, message, data fields
5. Optionally add filtering/search UI
6. Remove `LogViewerActivity.kt`
7. Remove import from `DebugPage.kt`
8. Delete `layout/layout_log_item.xml` if unused elsewhere

### Phase 3: Consolidate DebugPage into DebugRoute

1. Migrate remaining preference-based actions (crash viewer, test crash, hello world notification)
2. Replace `DebugPage` PreferenceFragment with direct navigation to `DebugRoute`
3. Remove `DebugPage.kt` entirely

---

## Anti-Pattern Notes (Per Copilot Instructions)

**Current Violations:**

- ❌ XML layouts (`layout_log_item.xml`)
- ❌ View-based UI (`ConstraintLayout`, `TextView`, `RecyclerView` in Activity context)
- ❌ `DetailActivity` base class (legacy)
- ❌ Synchronous state reads without Flow/StateFlow reactivity

**North Star Alignment:**

- ✅ Replace with Compose UI
- ✅ Use StateFlow/Flow for tracker state observation
- ✅ Fold into `DebugRoute` as modular sections
- ✅ Remove XML layouts
- ✅ Delete legacy base classes when last consumer removed

---

## Effort Estimate

**Low** (1-2 hours per activity)

**Rationale:**

- Pure display logic, no complex business rules
- No network/permissions/lifecycle complexity
- Straightforward state mapping to Compose UI
- Main effort: ensuring reactive state observation for `StatusActivity`

---

## Prioritization Rationale

**Why Low Priority:**

- Debug-only features (not user-facing)
- Rarely accessed (developer/QA tooling)
- No blocking issues (work correctly as-is)
- No performance/battery impact
- No privacy concerns (local data display)

**When to Prioritize:**

- After all user-facing screens migrated to Compose
- When removing `DetailActivity` base class
- When consolidating debug tooling for maintainability
- If adding new debug features (batch migration opportunity)

---

## Related Work

**Completed:**

- ✅ `DebugRoute` created (basic scaffold)
- ✅ Dummy data seeding migrated to Compose dialogs in `DebugPage`

**Pending (also low-priority debug):**

- `CrashViewerActivity`
- `CrashManagerActivity`
- `CrashExportActivity`

**Blockers:** None

---

## LLM Prompt Suggestion (Future)

```kotlin
// Copilot: Migrate StatusActivity to DebugRoute section.
// Add "System Status" expandable card displaying TrackerLocker state + WorkManager jobs.
// Use Flow/StateFlow for reactive updates. Remove StatusActivity.kt + manifest entry.
```

```kotlin
// Copilot: Migrate LogViewerActivity to DebugRoute section.
// Add "Log Viewer" button opening bottom sheet with LazyColumn of LogData entries.
// Query via repository pattern. Remove LogViewerActivity.kt + layout_log_item.xml.
```

---

## Approval for Deferral

This note documents the known legacy debug activities and provides a clear migration path. No immediate action required. Revisit when:

1. Compose migration Phase 3 (debug/admin) prioritized
2. `DetailActivity` removal becomes necessary
3. New debug features justify batch refactor

---

**Signed off:** Low-priority debug tooling deferred per architectural guidance.
