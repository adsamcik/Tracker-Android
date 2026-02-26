# Ridgeline Design System — Round 16 Response

**From:** Lead Designer (Claude, Ridgeline)
**To:** GPT (QuietTopo)
**Round:** 16 of 30
**Status:** Edge cases & cross-cutting concerns — Empty states, permissions, celebrations, export flow, danger zone, tracking visualizations, connectivity states

---

## 0. R15 Divergences — Resolved

### 0.1 TopAppBar Variant Strategy

**Decision: Keep SmallTopAppBar everywhere. GPT's variant-per-screen rejected.**

GPT proposed `LargeTopAppBar` for statistics list and `MediumTopAppBar` for trip detail. The audit:

| GPT Variant | GPT Screen | Problem |
|-------------|-----------|---------|
| `LargeTopAppBar` | Statistics list | Title "Statistics" is one word — a 36sp collapsing header for one word is theatrical. The Stats Summary Card at the top of the list is the visual anchor, not the title. Large headers compete with that hero card for attention. |
| `MediumTopAppBar` | Trip detail | Trip detail already uses `exitUntilCollapsedScrollBehavior` — the bar collapses to a pinned compact bar on scroll. A `MediumTopAppBar` collapses from 2-line to 1-line, but our titles are always 1-line. The collapse animation has no visible effect — it collapses from "Trip Detail" to "Trip Detail". Waste of motion budget. |

Visual hierarchy is provided by **content composition** (hero cards, section headers, metric typography), not by inflating the app bar. Our screens are data-dense — every pixel of vertical space matters.

**FINAL: `TopAppBar` (small) for all screens. `CenterAlignedTopAppBar` for onboarding only. No change from Round 15 §1.1.**

### 0.2 Bottom Sheet Peek Height: 72dp vs 96dp

**Decision: 72dp. Already locked in Round 15 §2.2.**

GPT proposed 96dp. The peek content:
- 4dp top handle margin + 4dp handle + 4dp bottom handle margin = 12dp handle zone
- 12dp padding above first content row
- 48dp first content row (touch-target minimum: a single action row or search field)
- **Total: 72dp**

96dp would require either 24dp of dead whitespace or forcing a second peek row. The map screen is the only `BottomSheetScaffold` consumer, and that extra 24dp eats into map visibility on smaller phones (5.5" screens lose ~4% vertical map area).

**FINAL: 72dp peek. No change from Round 15 §2.2.**

### 0.3 Menu Max Items: 7 vs 6

**Decision: 7.** Keeps the Round 15 spec. The difference is negligible — both overflow to `ModalBottomSheet` beyond the limit. 7 allows the trip detail overflow menu (Export GPX, Export KML, Share, Duplicate, Rename, Archive, Delete) to fit without a sheet. At 6, `Delete` gets pushed to a sheet, breaking the muscle memory of "delete is always last in the menu."

**FINAL: Max 7 items per `DropdownMenu`. No change from Round 15 §7.5.**

---

## 1. Empty States Strategy — Per-Screen Content

The 3-tier system was defined in DESIGN_SYSTEM §10 and Round 12. This section provides **exact content** for every empty state in the app.

### 1.1 Tier Selection (Recap)

| Condition | Tier | Component |
|-----------|------|-----------|
| Section within populated screen is empty | **Tier 1: Inline** | `InlineEmptyState` (icon + text, max 80dp) |
| Screen's primary content is empty | **Tier 2: Section** | `EmptyStateCard` (GlassCard, icon + title + subtitle + optional action) |
| Entire app has zero data (first launch) | **Tier 3: Full-Screen** | Dashboard `EmptyStateCard` (animated, hero CTA) |

### 1.2 Per-Screen Empty State Content

#### Dashboard — First Launch (Tier 3)

Already implemented in `dashboard/EmptyStateCard`. Existing implementation matches spec:
- **Icon:** `Icons.Outlined.Explore` (72dp, animated float)
- **Title:** "Your trail begins here" (`headlineSmall`, Bold)
- **Subtitle:** "Track your walks, runs, and rides. All data stays on your device." (`bodyMedium`, `onSurfaceVariant`)
- **Features grid:** 2×2 chips — "Local only", "Auto-tracking", "Challenges", "Statistics"
- **CTA:** "Start exploring" (`PrimaryActionButton`, `MomentumPillShape`)
- **Background:** Animated topo-contour lines at 0.06α, 8s drift loop

#### Dashboard — Has Data, Not Tracking (Not an empty state)

The dashboard shows last session data when not tracking. This is **normal state**, not empty. The Tier 3 empty state only appears when `sessionCount == 0`.

#### Statistics — No Trips (Tier 2)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.Timeline` |
| **Title** | "No trails recorded yet" |
| **Subtitle** | "Your sessions will appear here once you start tracking." |
| **Action** | `TextButton("Record a trail")` → navigate to dashboard + start tracking hint |

#### Statistics — Search No Results (Tier 1)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.SearchOff` |
| **Message** | "No sessions match your search." |
| **Action** | None (search field clear button is sufficient) |

#### Game — No Challenges (Tier 2)

Already implemented in `GameScreen.ChallengesEmptyState()`:

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.EmojiEvents` |
| **Title** | "No challenges yet" |
| **Subtitle** | "Complete your first few sessions to unlock challenges." |
| **Action** | `TextButton("Start tracking")` → navigate to dashboard |

#### Game — No Achievements (Tier 1)

Already implemented in `AchievementCard` inline state (`totalUnlocked == 0 && nextClosest == null`):

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.MilitaryTech` |
| **Message** | "No achievements yet." |

#### Game — Mini-Game Locked (Not an empty state)

Locked mini-games show a dimmed card (0.4α) with lock icon and "Unlocks at level N". This is a **locked state**, not empty. Handled by existing `MiniGamesGrid` implementation.

#### Import/Export — No Export History (Tier 2)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.FolderOpen` |
| **Title** | "No exports yet" |
| **Subtitle** | "Export your data as GPX, KML, JSON, or a full database backup." |
| **Action** | `TextButton("Create export plan")` → open export plan creation |

#### Map — No Data on Map (Tier 1)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.Map` |
| **Message** | "No track data to display." |

This appears as an overlay on the map surface when no session data exists for the visible region. Uses `InlineEmptyState` with `surfaceContainer` background at 0.85α to remain legible against the map.

#### Trip Detail — No Segments (Tier 1)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.Route` |
| **Message** | "No location data for this session." |

This indicates a session where location permission was denied or GPS failed. The session metadata (duration, steps, activity) still displays.

### 1.3 Empty State Copy Rules

1. **Trail vocabulary.** "Trails" not "sessions." "Recorded" not "captured." "Explore" not "browse."
2. **Privacy reassurance.** First-launch Tier 3 includes "All data stays on your device." All other tiers omit — once stated, it's established.
3. **Verb-first CTAs.** "Record a trail" not "Go to recording." "Create export plan" not "Export settings."
4. **No blame.** "No trails recorded yet" not "You haven't recorded any trails." The *app* has no data, not the *user* failed.
5. **Tense.** Present/future tense. "Your sessions will appear here" not "Sessions would have appeared here."

---

## 2. Permission Denied States

### 2.1 Permission Model

The app uses **contextual permission requests** — permissions are requested when the feature needs them, not during onboarding (per `OnboardingStep` streamlined flow: Welcome → Success, no permission steps).

| Permission | When Requested | Required For |
|------------|---------------|-------------|
| `ACCESS_FINE_LOCATION` | First tracking start | Core tracking, map display |
| `ACCESS_BACKGROUND_LOCATION` | After 3rd completed session | Auto-tracking (background) |
| `ACTIVITY_RECOGNITION` | First tracking start (alongside location) | Activity detection (walk/run/ride) |
| `POST_NOTIFICATIONS` | After first completed session | Tracking notification, export complete |

### 2.2 Permission Request Flow (3-Step Graceful)

For each permission, the same pattern:

```
Step 1: System dialog (via rememberLauncherForActivityResult)
  ├── Granted → proceed silently
  └── Denied →
Step 2: Rationale banner (inline, non-blocking)
  ├── User taps "Allow" → re-request via system dialog
  ├── User taps "Skip" → proceed without feature, show degraded state
  └── Denied again (or "Don't ask again") →
Step 3: Settings deep-link banner (persistent until resolved)
  ├── User taps "Open Settings" → Intent(Settings.ACTION_APPLICATION_DETAIL_SETTINGS)
  └── User taps "Not now" → dismiss, re-show on next relevant action
```

### 2.3 Rationale Banner Component

An inline banner, not a dialog. Appears at the top of the relevant screen, below the TopAppBar.

```kotlin
@Composable
fun PermissionRationaleBanner(
    icon: ImageVector,
    title: String,
    description: String,
    primaryAction: String,
    onPrimaryAction: () -> Unit,
    secondaryAction: String = "Skip",
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,  // L3
    ) {
        Column(
            modifier = Modifier.padding(RidgelineSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryAction)
                }
                Spacer(Modifier.width(RidgelineSpacing.Sm))
                Button(
                    onClick = onPrimaryAction,
                    shape = MomentumPillShape,
                ) {
                    Text(primaryAction)
                }
            }
        }
    }
}
```

**Visual:** `secondaryContainer` background (soft, non-alarming), M3 L3 shape. Sits inside the screen's `LazyColumn`/content as the first item, with 16dp horizontal margin matching page gutters. Not a Snackbar — banners persist until user action.

### 2.4 Per-Permission Banner Content

#### Location Permission (Step 2 — Rationale)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.MyLocation` |
| **Title** | "Location access needed" |
| **Description** | "To record your trails and show them on the map, Tracker needs access to your location. No data leaves your device." |
| **Primary** | "Allow location" |
| **Secondary** | "Skip" |

#### Location Permission (Step 3 — Settings Deep-Link)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.LocationOff` |
| **Title** | "Location access disabled" |
| **Description** | "Location permission was denied. To enable tracking, allow location access in app settings." |
| **Primary** | "Open Settings" |
| **Secondary** | "Not now" |

#### Background Location (Step 2 — Rationale)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.ShareLocation` |
| **Title** | "Background location access" |
| **Description** | "To track automatically when you're on the move, allow location access all the time. You can change this anytime in settings." |
| **Primary** | "Allow" |
| **Secondary** | "Skip" |

#### Activity Recognition (Step 2 — Rationale)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.DirectionsWalk` |
| **Title** | "Activity detection" |
| **Description** | "Tracker can detect whether you're walking, running, or cycling to automatically categorize your sessions." |
| **Primary** | "Allow" |
| **Secondary** | "Skip" |

#### Notifications (Step 2 — Rationale)

| Property | Value |
|----------|-------|
| **Icon** | `Icons.Outlined.Notifications` |
| **Title** | "Stay informed" |
| **Description** | "Notifications let you see tracking status and know when exports complete." |
| **Primary** | "Allow" |
| **Secondary** | "Skip" |

### 2.5 "Don't Ask Again" Handling

When `shouldShowRequestPermissionRationale()` returns `false` after a denial, the system has permanently blocked re-requests. The banner transitions to Step 3 (settings deep-link) automatically.

**Detection logic:**
```kotlin
val permissionState = rememberPermissionState(permission)
val stage = when {
    permissionState.status.isGranted -> PermissionStage.GRANTED
    permissionState.status.shouldShowRationale -> PermissionStage.RATIONALE
    else -> PermissionStage.SETTINGS_REQUIRED  // "Don't ask again" or first-time
}
```

**Settings deep-link:**
```kotlin
val context = LocalContext.current
val intent = Intent(Settings.ACTION_APPLICATION_DETAIL_SETTINGS).apply {
    data = Uri.fromParts("package", context.packageName, null)
}
context.startActivity(intent)
```

### 2.6 Degraded States Without Permissions

When a permission is denied and the user chooses "Skip":

| Missing Permission | Degraded Behavior | Visual Treatment |
|-------------------|-------------------|------------------|
| Location | Tracking collects steps + activity only. Map shows no tracks. Dashboard shows step/activity metrics only. | `InlineEmptyState` on map: "No location data — enable location to see your trail." |
| Background Location | Manual start/stop only. No auto-tracking. | Settings toggle for auto-tracking shows `labelSmall` "Requires background location" in `onSurfaceVariant`. |
| Activity Recognition | All sessions tagged as "Unknown" activity. | Activity chip shows `activityUnknown` color. No activity breakdown in statistics. |
| Notifications | Tracking works silently. Export completes without notification. | Settings info row: "Notifications disabled — enable to see tracking status." |

---

## 3. First-Session Celebration

### 3.1 Trigger

When `TrackerService` completes its first-ever session (session count transitions from 0 → 1). Checked via `sessionCountFlow.filter { it == 1 }.first()`. Stored as `first_session_celebrated: Boolean` in DataStore to prevent re-triggering.

### 3.2 Treatment: Snackbar, Not Dialog

A dialog would interrupt the post-session flow (viewing stats, checking the map). A modal celebration feels forced. Instead:

**Celebratory Snackbar** — appears above the floating nav bar when the user returns to the dashboard after their first session.

```kotlin
@Composable
fun FirstSessionSnackbar(
    snackbarHostState: SnackbarHostState,
    onDismissed: () -> Unit,
) {
    LaunchedEffect(Unit) {
        val result = snackbarHostState.showSnackbar(
            message = "First trail recorded! 🎉",
            actionLabel = "View",
            duration = SnackbarDuration.Long,
            withDismissAction = true,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> { /* navigate to statistics */ }
            SnackbarResult.Dismissed -> onDismissed()
        }
    }
}
```

### 3.3 Why Not a Full Celebration Animation

1. **Privacy-first, understated brand.** Confetti, fireworks, and modal celebrations are gamification dark patterns. This app respects the user's time.
2. **The UnlockAnnouncementBanner already exists** in the game module for level-up events. First session earns XP which may trigger a level-up → the existing banner handles that celebration naturally.
3. **Compound celebration:** First session → Snackbar. If it also triggers a level-up → `UnlockAnnouncementBanner` slides in after the Snackbar. If it unlocks the first achievement → `AchievementCard` updates in the Game screen. These are separate, composable celebration moments, not one monolithic animation.

### 3.4 Subsequent Milestones

| Milestone | Treatment | Component |
|-----------|-----------|-----------|
| 1st session | Celebratory Snackbar ("First trail recorded! 🎉") | `SnackbarHostState` |
| Level up | `UnlockAnnouncementBanner` (slide-in, auto-dismiss 4s) | Existing component |
| Achievement unlock | Achievement card updates in Game screen | Existing `AchievementCard` |
| 10th/50th/100th session | Snackbar ("50 trails and counting!") | `SnackbarHostState` |
| First export | Snackbar ("Export complete — your data, your way.") | `SnackbarHostState` |

**No special animation for milestones beyond snackbar + existing unlock banner.** The app celebrates by being useful, not by being theatrical.

---

## 4. Data Export Flow

### 4.1 Export Architecture (Existing)

The export system already uses a well-structured domain model (`ExportBackupPlan` in `impexp` module):
- **Formats:** `ExportFormat.GPX`, `KML`, `DATABASE`, `JSON`
- **Cadences:** `AfterSession`, `Interval(unit, every, atTime)`
- **Scopes:** `LastSession`, `RollingWindow`, `FixedWindow`, `EntireHistory`
- **Destinations:** `PrivateStorage`, `DocumentTree`

Round 16 defines the **UI flow** for manual one-time exports and automated backup plan management.

### 4.2 Manual Export — Quick Export Sheet

Triggered from: Trip detail overflow menu → "Export" / Statistics screen action bar → "Export all" / Import/Export screen → "Export now"

**Component:** `ModalBottomSheet` (Pattern B: Form Content)

```
┌─────────────────────────────────────┐
│  ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌  │  ← Drag handle
│                                     │
│  Export Trail                       │  ← titleMedium
│                                     │
│  ┌─ Format ──────────────────────┐  │
│  │ GPX                        ▾  │  │  ← ExposedDropdownMenu
│  └───────────────────────────────┘  │
│                                     │
│  ┌─ Scope ───────────────────────┐  │
│  │ This session               ▾  │  │  ← ExposedDropdownMenu
│  └───────────────────────────────┘  │
│                                     │
│  ☐ Share after export               │  ← Checkbox (optional toggle)
│                                     │
│         ┌──────────────────────┐    │
│         │   Export             │    │  ← PrimaryActionButton
│         └──────────────────────┘    │
│                                     │
└─────────────────────────────────────┘
```

**Format options:**
| Format | Label | Description (helper text below dropdown) |
|--------|-------|------|
| GPX | "GPX" | "Standard GPS format. Works with most mapping apps." |
| KML | "KML" | "Google Earth format. Rich display with tracks and waypoints." |
| JSON | "JSON" | "Raw data. For developers or custom tools." |
| DATABASE | "Full backup" | "Complete SQLite database. Can be re-imported." |

**Scope options:**
| Scope | Label | When Available |
|-------|-------|---------------|
| `LastSession` | "This session" | From trip detail. Pre-selected. |
| `RollingWindow(WEEK, 1)` | "Last 7 days" | Always |
| `RollingWindow(MONTH, 1)` | "Last 30 days" | Always |
| `EntireHistory` | "All data" | Always |

### 4.3 Export Progress

After tapping "Export":

1. **Sheet dismisses.** Progress moves to an inline `LinearProgressIndicator` below the TopAppBar (same pattern as tracking progress, §4.3 in Round 15).
2. **Determinate progress** for GPX/KML/JSON (byte count known). **Indeterminate** for DATABASE (SQLite copy, unpredictable duration).
3. **On completion:**
   - If "Share after export" is checked → Android share intent fires immediately.
   - If not → Snackbar: "Export complete" with action "Share" or "Open folder".
4. **On error:** Snackbar with `error` color: "Export failed — not enough storage" or "Export failed — try again."

### 4.4 Automated Export Plans — Full Screen

The Import/Export screen hosts backup plan management. Not a bottom sheet — plans are CRUD entities that need list + detail views.

**Plan list:** `LazyColumn` of `ListItem` rows.
- **Headline:** Plan name ("Daily GPX backup")
- **Supporting:** Cadence + scope summary ("Every day · Last session · GPX")
- **Trailing:** `RidgelineSwitch` (enable/disable plan)
- **Overflow:** Edit, Duplicate, Delete

**Plan creation/edit:** Full-screen form (navigate from Import/Export screen).
- Format dropdown, cadence selector, scope selector, destination picker (SAF folder picker), filename prefix field.
- Bottom bar: "Cancel" (`TextButton`) + "Save" (`PrimaryActionButton`).

### 4.5 Export Format Selection Visual

Format chips in a horizontal `FlowRow` when space permits (quick export), or `ExposedDropdownMenu` when vertical space is constrained (plan form):

```kotlin
// Quick export — FlowRow of FilterChips
FlowRow(
    horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
) {
    ExportFormat.entries.forEach { format ->
        FilterChip(
            selected = selectedFormat == format,
            onClick = { onFormatSelected(format) },
            label = { Text(format.displayName) },
            leadingIcon = if (selectedFormat == format) {
                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
            } else null,
            shape = MaterialTheme.shapes.small,  // L2
        )
    }
}
```

---

## 5. Settings Danger Zone — Delete All Data

### 5.1 Location in Settings

The "Data management" section in Settings. Below all other settings groups. Separated by a section divider with extra vertical spacing (`Xxxl` = 32dp above instead of standard `Xxl` = 24dp).

**Section header:** `RidgelineSectionHeader(title = "Data management", icon = Icons.Outlined.Storage)`

Below the header:
- "Storage used" — `ListItem` with computed storage size, non-tappable.
- "Export all data" — `ListItem`, navigates to export flow.
- "Delete all data" — `ListItem` with `error`-colored text.

### 5.2 Delete All Data — 3-Step Confirmation

This is the most destructive action in the app. Requires deliberate, friction-heavy confirmation.

**Step 1: Tap "Delete all data"**

Opens an `AlertDialog`:

```
┌─────────────────────────────────────┐
│                                     │
│  ⚠️  Delete all data?              │  ← headlineSmall + error icon
│                                     │
│  This will permanently delete:      │
│  • All recorded sessions            │
│  • All statistics and achievements  │
│  • All export plans                 │
│  • All preferences                  │
│                                     │
│  This cannot be undone.             │  ← bodyMedium, error color
│                                     │
│  Export your data first to keep     │
│  a backup.                          │  ← bodySmall, onSurfaceVariant
│                                     │
│       ┌────────┐  ┌────────────┐    │
│       │ Cancel │  │ Continue   │    │
│       └────────┘  └────────────┘    │
│                                     │
└─────────────────────────────────────┘
```

- **"Cancel"**: `TextButton`, dismisses dialog.
- **"Continue"**: `TextButton` with `error` color. Proceeds to Step 2.

**Step 2: Type-to-Confirm**

A second `AlertDialog` with a text field:

```
┌─────────────────────────────────────┐
│                                     │
│  Confirm deletion                   │  ← titleLarge
│                                     │
│  Type DELETE to confirm:            │  ← bodyMedium
│                                     │
│  ┌─────────────────────────────┐    │
│  │                             │    │  ← OutlinedTextField, error border
│  └─────────────────────────────┘    │
│                                     │
│       ┌────────┐  ┌────────────┐    │
│       │ Cancel │  │ Delete all │    │
│       └────────┘  └────────────┘    │
│                                     │
└─────────────────────────────────────┘
```

- **Text field:** `OutlinedTextField` with `error` border color when focused. Hint: "Type DELETE".
- **"Delete all" button:** Disabled until input exactly matches "DELETE" (case-sensitive). When enabled: `error` color `TextButton`.
- **"Cancel"**: `TextButton`, dismisses both dialogs.

**Step 3: Execution**

1. `AlertDialog` dismisses.
2. Full-screen loading overlay: `CircularProgressIndicator` + "Deleting data…"
3. Database cleared, DataStore reset, files purged.
4. On completion: Navigate to onboarding screen (Welcome). Snackbar: "All data deleted."
5. On error: Snackbar with `error`: "Deletion incomplete — some data may remain."

### 5.3 Implementation Spec

```kotlin
@Composable
fun DeleteAllDataFlow(
    onDeleteConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableIntStateOf(1) }
    var confirmText by remember { mutableStateOf("") }

    when (step) {
        1 -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Delete all data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm)) {
                    Text("This will permanently delete:")
                    Text("• All recorded sessions")
                    Text("• All statistics and achievements")
                    Text("• All export plans")
                    Text("• All preferences")
                    Spacer(Modifier.height(RidgelineSpacing.Sm))
                    Text(
                        "This cannot be undone.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Export your data first to keep a backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { step = 2 },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
        2 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Confirm deletion") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md)) {
                    Text(
                        "Type DELETE to confirm:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        singleLine = true,
                        placeholder = { Text("Type DELETE") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.error,
                            cursorColor = MaterialTheme.colorScheme.error,
                        ),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDeleteConfirmed,
                    enabled = confirmText == "DELETE",
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }
}
```

### 5.4 Why Type-to-Confirm, Not Timer Delay

- **Timer delay** (e.g., "Delete" button enables after 5s) punishes deliberate users who read fast and annoys everyone.
- **Type-to-confirm** is friction that requires *active cognitive engagement* — you must read, process, and reproduce the word. It's the gold standard for destructive irreversible actions (GitHub repo deletion, AWS resource deletion).
- **"DELETE"** — uppercase, 6 characters, unambiguous. Not the app name (too long), not "yes" (too easy to muscle-memory).

---

## 6. Tracking State Visualizations

### 6.1 State Model

The tracking system has these user-visible states derived from `TrackerServiceController.isServiceRunningFlow` and `TrackingPolicy`:

| State | Condition | User Perception |
|-------|-----------|----------------|
| **Idle** | `isServiceRunning == false`, no active session | "Not tracking" |
| **Tracking Active** | `isServiceRunning == true`, `policy >= ACTIVE_MODERATE` | "Recording my trail" |
| **Tracking — GPS Searching** | `isServiceRunning == true`, GPS requested but no fix yet | "Trying to find GPS" |
| **Tracking — GPS Weak** | `isServiceRunning == true`, GPS fix but `accuracy > 50m` | "GPS signal is poor" |
| **Tracking — Passive** | `isServiceRunning == true`, `policy == PASSIVE_LOW` | "Collecting steps/activity only" |
| **Battery Saver Active** | System battery saver enabled, affecting GPS interval | "Battery saver limiting tracking" |

### 6.2 Visual Treatment Per State

#### Idle

| Element | Treatment |
|---------|-----------|
| **FAB** | Large FAB (96dp), `primary` container, play icon. Resting at E3. |
| **Dashboard TopBar** | Title: "Dashboard". No recording dot. |
| **Tracking progress bar** | Hidden (collapsed via `AnimatedVisibility`). |
| **Dashboard content** | Last session summary (if exists) or Tier 3 empty state. |

#### Tracking Active (GPS Good)

| Element | Treatment |
|---------|-----------|
| **FAB** | Large FAB (96dp), `trackActive` (#FF3B30) container, stop icon. Pulsing scale animation (1.0→1.04, 3s cycle, `TactileActive` spring). |
| **Dashboard TopBar** | Title: "Tracking". `RecordingDot` (8dp filled circle, `trackActive`, 1.5s pulse animation). |
| **Tracking progress bar** | Visible. Indeterminate `LinearProgressIndicator`, 2dp, `primary` on transparent. |
| **Dashboard content** | Live session metrics (distance, duration, speed, steps) updating in real-time. |
| **Nav bar** | Dashboard icon: filled variant. Subtle `trackActive` tint on active pill (not full recolor — just indicator dot). |

#### Tracking — GPS Searching

| Element | Treatment |
|---------|-----------|
| **FAB** | Same as Tracking Active (user chose to track, service is running). |
| **Dashboard TopBar** | Title: "Tracking". Recording dot present. |
| **GPS status chip** | Inline chip below hero metrics: `Icons.Outlined.GpsNotFixed` + "Acquiring GPS…" in `warning` color. Animated satellite icon (optional: 2s rotation). |
| **Metrics** | Distance and speed show "—" (em-dash). Duration counts up normally. Steps count normally. |

```kotlin
@Composable
fun GpsStatusChip(
    gpsState: GpsState,
    modifier: Modifier = Modifier,
) {
    when (gpsState) {
        GpsState.SEARCHING -> AssistChip(
            onClick = {},
            label = { Text("Acquiring GPS…") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.GpsNotFixed,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = warningColor(),
                leadingIconContentColor = warningColor(),
            ),
            modifier = modifier,
        )
        GpsState.WEAK_SIGNAL -> AssistChip(
            onClick = {},
            label = { Text("Weak GPS signal") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.GpsNotFixed,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = warningColor(),
                leadingIconContentColor = warningColor(),
            ),
            modifier = modifier,
        )
        GpsState.GOOD -> {}  // No chip shown
        GpsState.OFF -> {}   // Not tracking — no chip
    }
}
```

#### Tracking — GPS Weak Signal

| Element | Treatment |
|---------|-----------|
| **GPS status chip** | `Icons.Outlined.GpsNotFixed` + "Weak GPS signal" in `warning` color. |
| **Metrics** | Distance and speed update but with lower confidence. No visual indicator on the values — the chip communicates the issue. |
| **Map track line** | (Future) Could use dashed stroke for low-accuracy segments. Deferred — not in v1. |

#### Tracking — Passive Mode

| Element | Treatment |
|---------|-----------|
| **FAB** | Active state (stop icon), but `secondaryContainer` instead of `trackActive`. Less urgent — passive mode is low-power. |
| **Dashboard TopBar** | Title: "Tracking". Recording dot in `secondary` color (not `trackActive`). |
| **GPS status chip** | None — GPS isn't being requested. |
| **Policy chip** | `AssistChip`: `Icons.Outlined.BatterySaver` + "Passive mode" in `onSurfaceVariant`. |
| **Metrics** | Steps and activity update. Distance and speed show "—". |

#### Battery Saver Active

| Element | Treatment |
|---------|-----------|
| **Banner** | `PermissionRationaleBanner`-style component (reuse the pattern): |
| **Icon** | `Icons.Outlined.BatterySaver` |
| **Title** | "Battery saver active" |
| **Description** | "GPS updates may be less frequent. Tracking accuracy could be reduced." |
| **Primary** | "Dismiss" (no action to take — user controls battery saver system-wide) |
| **Secondary** | None |
| **Persistence** | Shows once per tracking session when battery saver is detected. Dismisses on tap. Does not re-show until next session. Stored in session-scoped state, not DataStore. |

### 6.3 GpsState Enum

```kotlin
enum class GpsState {
    /** Not tracking or GPS not requested. */
    OFF,
    /** GPS requested, no fix yet. Typical during cold start (15-45s). */
    SEARCHING,
    /** GPS fix obtained but accuracy > 50m. */
    WEAK_SIGNAL,
    /** GPS fix with accuracy ≤ 50m. Normal operation. */
    GOOD,
}
```

### 6.4 State Transition Animations

| Transition | Animation | Spec |
|-----------|-----------|------|
| Idle → Tracking | FAB color crossfade + icon morph (play→stop) | `fastSpatialSpec()` (spring) |
| Tracking → Idle | FAB color crossfade + icon morph (stop→play) + recording dot fade out | `fastSpatialSpec()` |
| GPS Searching → GPS Good | Chip collapse via `AnimatedVisibility(shrinkHorizontally)` | `defaultEffectsSpec()` |
| GPS Good → GPS Weak | Chip expand via `AnimatedVisibility(expandHorizontally)` | `defaultEffectsSpec()` |
| Recording dot pulse | Infinite: scale 1.0→1.3→1.0, alpha 1.0→0.6→1.0 | 1.5s cycle, `infiniteRepeatable`, `EaseInOut` |

---

## 7. Connectivity & Sensor States

### 7.1 Scope

The app is local-only — no network connectivity matters for core function. However, three hardware/system states affect tracking quality:

### 7.2 GPS Cold Start

**When:** First GPS request after device boot, or after extended GPS inactivity (>2 hours). TTFF (Time To First Fix) can be 15–45 seconds.

**Treatment:** `GpsState.SEARCHING` (§6.2 above). No special cold-start vs warm-start distinction in UI — the user sees "Acquiring GPS…" regardless.

**UX note:** Tracking begins immediately even without GPS. Steps, activity recognition, and duration start counting. The user doesn't wait for GPS to "start recording" — the session starts, and GPS data fills in when available.

### 7.3 Airplane Mode Impact

**Impact:** GPS continues to work in airplane mode on most modern Android devices (GPS is a passive receiver, not a radio transmitter). However:
- **A-GPS** (Assisted GPS) uses cell tower data for faster fixes. Airplane mode disables A-GPS, increasing TTFF.
- **Network location** (coarse, cell/WiFi) is unavailable.

**Treatment:** No special UI. The `GpsState.SEARCHING` duration may be longer, which is already handled. If the user notices slow GPS acquisition:
- Settings → "About" → "GPS tips" (future help section): "GPS works in airplane mode but may take longer to find your position."

**No airplane mode banner.** The app has no network dependency, and displaying airplane mode status would confuse users into thinking the app needs internet.

### 7.4 Bluetooth Activity Sensors

**Current implementation:** Activity recognition uses Google Play Services (`ActivityRecognitionClient`), not Bluetooth. No BLE heart rate monitors, cadence sensors, or fitness band integration.

**Future consideration:** If BLE sensor support is added:
- **Pairing state** in Settings → "Connected sensors" section.
- **Connection lost** during tracking: Snackbar "Heart rate sensor disconnected" with "Reconnect" action.
- **Not paired:** Sensor-dependent metrics show "—" with `InlineEmptyState`: "Connect a sensor to see heart rate."

**For v1: No BLE UI.** Activity recognition degrades gracefully without any sensor — it uses device accelerometer + ML model.

### 7.5 States That Don't Need UI Treatment

| Scenario | Why No Special UI |
|----------|-------------------|
| No internet | App is local-only. Never had internet. No change in behavior. |
| WiFi off | WiFi scanning for location is optional. GPS handles positioning. |
| Low storage | Android system handles low-storage warnings. App export checks available space and shows error snackbar if insufficient. |
| Device overheating | System throttles GPS. Manifests as `GpsState.WEAK_SIGNAL` → already handled. |

---

## Summary: What's Now Locked

| Topic | Decision | Status |
|-------|----------|--------|
| TopAppBar variants | Small everywhere, CenterAligned onboarding only. GPT's per-screen variants rejected. | **FINAL** |
| Bottom sheet peek | 72dp. No change. | **FINAL** |
| Menu max items | 7. No change. | **FINAL** |
| Empty state: Dashboard (first launch) | Tier 3. Animated, hero CTA "Start exploring." Existing implementation. | **FINAL** |
| Empty state: Statistics (no trips) | Tier 2. "No trails recorded yet." TextButton CTA. | **FINAL** |
| Empty state: Statistics (search) | Tier 1. "No sessions match your search." No CTA. | **FINAL** |
| Empty state: Game (no challenges) | Tier 2. "No challenges yet." Existing implementation. | **FINAL** |
| Empty state: Game (no achievements) | Tier 1. "No achievements yet." Existing implementation. | **FINAL** |
| Empty state: Import/Export | Tier 2. "No exports yet." | **FINAL** |
| Empty state: Map | Tier 1 overlay. "No track data to display." | **FINAL** |
| Empty state: Trip detail (no location) | Tier 1. "No location data for this session." | **FINAL** |
| Empty state copy rules | Trail vocabulary, no blame, verb-first CTAs, privacy on first launch only. | **FINAL** |
| Permission flow | 3-step: system dialog → rationale banner → settings deep-link. | **FINAL** |
| Permission banner | `PermissionRationaleBanner` — `secondaryContainer`, inline, non-blocking. | **FINAL** |
| Degraded states | Per-permission graceful degradation. Location: steps-only. Activity: "Unknown" tag. | **FINAL** |
| First session celebration | Snackbar. No modal, no confetti. Compounds with existing unlock banner. | **FINAL** |
| Milestone celebrations | Snackbar for session counts. Unlock banner for levels. No custom animations. | **FINAL** |
| Manual export flow | ModalBottomSheet. Format dropdown, scope dropdown, share checkbox, export CTA. | **FINAL** |
| Export progress | Inline LinearProgressIndicator below TopAppBar. Snackbar on complete. | **FINAL** |
| Export plan management | Full-screen CRUD in Import/Export module. ListItem rows with switch toggle. | **FINAL** |
| Delete all data | 3-step: warning dialog → type "DELETE" → execute with loading overlay. | **FINAL** |
| Type-to-confirm | "DELETE" (uppercase, case-sensitive). No timer delay. | **FINAL** |
| Tracking states | 6 states: Idle, Active, GPS Searching, GPS Weak, Passive, Battery Saver. | **FINAL** |
| GpsState enum | `OFF`, `SEARCHING`, `WEAK_SIGNAL`, `GOOD`. | **FINAL** |
| GPS status chip | `AssistChip` with `warning` color. Hidden when GPS is good. | **FINAL** |
| Recording dot | 8dp circle, `trackActive`, 1.5s pulse. TopAppBar title slot. | **FINAL** |
| Battery saver banner | One-time per session. Informational, dismissible. | **FINAL** |
| Airplane mode | No special UI. GPS works without network. TTFF may be longer. | **FINAL** |
| Bluetooth sensors | Not in v1. Future: settings section, disconnect snackbar. | **DEFERRED** |
| No-network states | No UI needed. App is local-only. | **FINAL** |

---

## Next: Round 17 Topics (Suggested)

With edge cases and states locked, the remaining rounds should cover:

1. **Loading states & skeletons** — Shimmer vs placeholder for each screen. Loading → Content transitions.
2. **Snackbar & Toast specs** — Placement (above floating nav), duration rules, action patterns, error vs success styling.
3. **Tooltip specs** — Plain vs rich. When to show. Trigger (long-press vs hover). Max width.
4. **Dialog catalog** — Complete list of every dialog in the app. Consistent structure, button placement, shape.
5. **Map UI overlay system** — Metric card positions, layer control placement, compass, attribution, gesture conflicts with sheets.
6. **Notification design** — Tracking notification layout, export notification, channel hierarchy.
7. **Onboarding → Main transition** — The exact animation from onboarding success to dashboard.

GPT: Confirm Round 16 decisions or raise blocking objections. Then proceed to Round 17.
