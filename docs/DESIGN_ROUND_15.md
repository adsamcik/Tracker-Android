# Ridgeline Design System — Round 15 Response

**From:** Lead Designer (Claude, Ridgeline)
**To:** GPT (QuietTopo)
**Round:** 15 of 30
**Status:** Component-level implementation specs — Top App Bars, Bottom Sheets, List Items, Progress Indicators, Switches, Text Fields, Menus

---

## 0. GPT Round 14 Divergences — Resolved

### 0.1 Elevation Levels: GPT's 6 vs Our 4

**Decision: Keep 4.** Compromise rejected.

GPT proposed E0–E5 (adding E4 at 3dp for "medium drawers" and E5 at 8dp for "modals/overlays"). The audit:

| GPT Level | Component | Ridgeline Mapping |
|-----------|-----------|-------------------|
| E4 (3dp) | "Medium drawers" | We don't have medium drawers. Bottom sheet peek = E2 (2dp). Expanded = E2. |
| E5 (8dp) | "Modal overlays" | Modals use `scrim` + `surfaceContainerHigh` — tonal elevation is irrelevant behind a scrim. |

Neither E4 nor E5 maps to real components in this app. Four levels cover every component in all 17 modules. Adding unused levels creates spec-code drift.

**FINAL: E0/E1/E2/E3. No change from Round 14.**

### 0.2 Responsive Gutters: GPT's 16/24/32dp by Width vs Fixed 16dp

**Decision: Accept GPT's responsive gutters — with simplification.**

GPT's proposal:

| Width Class | Gutter | GPT Token |
|-------------|--------|-----------|
| Compact (<600dp) | 16dp | `Lg` |
| Medium (600–839dp) | 24dp | `Xxl` |
| Expanded (≥840dp) | 32dp | `Xxxl` |

This is reasonable and trivially implementable. The app is phone-first, so 99% of usage hits 16dp. But small tablets (600dp Pixel Tablet in portrait) and foldables (Galaxy Fold inner screen) benefit from 24dp gutters. The cost is one `when` expression.

**Implementation:**

```kotlin
object RidgelineGutters {
    /**
     * Resolves page gutter based on window width.
     * Compact (<600dp) = 16dp, Medium (600-839dp) = 24dp, Expanded (≥840dp) = 32dp.
     */
    val horizontal: Dp
        @Composable get() {
            val config = LocalConfiguration.current
            return when {
                config.screenWidthDp < 600 -> RidgelineSpacing.Lg    // 16dp
                config.screenWidthDp < 840 -> RidgelineSpacing.Xxl   // 24dp
                else -> RidgelineSpacing.Xxxl                         // 32dp
            }
        }
}

// Usage — replaces hardcoded RidgelineSpacing.Lg in Scaffold patterns:
LazyColumn(
    contentPadding = PaddingValues(
        start = RidgelineGutters.horizontal,
        end = RidgelineGutters.horizontal,
        bottom = AppDimensions.FloatingNavBarClearance,
    ),
)
```

**FINAL: Responsive gutters accepted. `RidgelineGutters.horizontal` replaces hardcoded `RidgelineSpacing.Lg` for page gutters only. Component-internal padding stays fixed at `Lg` (16dp).**

### 0.3 Dual-Pane at ≥840dp

**Decision: Not in scope for v1. Deferred.**

Round 14 §6 already locked this: "No adaptive layouts. No multi-pane. No WindowSizeClass." The responsive gutters above are a *spacing* adjustment, not a layout change. Content remains single-column at all widths.

**FINAL: No dual-pane. Deferred per Round 14 §6.3.**

---

## 1. Top App Bar Variants

### 1.1 Which Variant Where

Three M3 variants available. Ridgeline uses two:

| Variant | Screens | Rationale |
|---------|---------|-----------|
| **`TopAppBar`** (small) | Dashboard, Statistics list, Game, Settings, Import/Export, Trip Detail, all detail/sub-screens | Default. Single-line title. Clean, minimal overhead. |
| **`CenterAlignedTopAppBar`** | Onboarding screens only | Centered titles suit symmetric, focused onboarding flows. No nav icon competing for left-align space. |
| **`LargeTopAppBar`** | ❌ Not used | Large titles waste vertical space on a data-dense tracker. The dashboard hero metric serves the "big glanceable text" role instead. |
| **`MediumTopAppBar`** | ❌ Not used | Same reasoning. Two-line titles add no value — our screen titles are short ("Statistics", "Settings", "Game"). |

**Rule: One variant per screen. Never nest or switch variants dynamically.**

### 1.2 Scroll Behavior

| Screen | Behavior | Implementation | Rationale |
|--------|----------|----------------|-----------|
| **Dashboard** | `pinnedScrollBehavior` | Bar always visible | Active tracking needs persistent access to status indicators (recording dot, lock badge). |
| **Statistics** | `enterAlwaysScrollBehavior` | Hides on scroll down, shows on any scroll up | Long trip lists need maximum vertical space. Quick return on up-scroll is sufficient. |
| **Trip Detail** | `exitUntilCollapsedScrollBehavior` | Collapses to pinned compact bar | Detail screens have a natural "hero then scroll" pattern. Bar collapses after initial context is absorbed. |
| **Game** | `enterAlwaysScrollBehavior` | Same as Statistics | Challenge lists benefit from maximum space. |
| **Settings** | `pinnedScrollBehavior` | Always visible | Settings are scanned, not scrolled deeply. Pinned bar provides persistent orientation. |
| **Import/Export** | `pinnedScrollBehavior` | Always visible | Short content, no need to reclaim space. |
| **Onboarding** | `pinnedScrollBehavior` | Always visible | Step context must remain visible. |

### 1.3 Color & Elevation

All top app bars use the same color configuration:

```kotlin
colors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
```

- **Resting:** `surface` (E0, flat) — merges with background.
- **Scrolled:** `surfaceContainerLow` (subtle elevation tint) — provides visual separation from scrolling content without a hard shadow line.
- **Action icons:** `onSurfaceVariant` — secondary emphasis. Navigation icon gets full `onSurface`.

### 1.4 Canonical Implementation

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidgelineTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
```

### 1.5 Dashboard TopAppBar — Special Case

The dashboard top bar has additional elements (recording indicator, policy chip, points chip). It uses the same `TopAppBar` variant but with a custom `title` slot:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardTopBar(
    isTracking: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isTracking) {
                    RecordingDot(modifier = Modifier.padding(end = RidgelineSpacing.Sm))
                }
                Text(
                    text = if (isTracking) "Tracking" else "Dashboard",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier,
    )
}
```

### 1.6 Back Navigation Pattern

All detail/sub-screens use the same back button:

```kotlin
navigationIcon = {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_navigate_back),
        )
    }
}
```

**Never use a hamburger menu icon.** This app has no drawer navigation.

---

## 2. Bottom Sheet Specs

### 2.1 Which Variant Where

| Variant | Screen | Use Case |
|---------|--------|----------|
| **`BottomSheetScaffold`** (persistent) | Map screen | Map controls, layer selection, search. Always available. User drags between peek/half/full. |
| **`ModalBottomSheet`** | Everywhere else (on demand) | Trip actions, filter selection, export options, session detail quick-view. Appears on user action, dismisses on tap-outside. |

**Rule: Only the map screen uses `BottomSheetScaffold`. All other sheets are `ModalBottomSheet`.**

### 2.2 BottomSheetScaffold — Map Screen

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreenScaffold(
    mapContent: @Composable () -> Unit,
    sheetContent: @Composable ColumnScope.() -> Unit,
) {
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
        ),
    )

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContent = sheetContent,
        sheetPeekHeight = 72.dp,
        sheetShape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 6.dp,  // Asymmetric — Ridgeline terrain DNA (3:1 ratio)
        ),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetTonalElevation = 2.dp,    // E2 — Lifted
        sheetShadowElevation = 2.dp,
        sheetDragHandle = { RidgelineDragHandle() },
    ) {
        mapContent()
    }
}
```

**Peek height: 72dp.** Calculation: 4dp top drag handle margin + 4dp handle height + 4dp bottom handle margin + 12dp top padding + 48dp first content row (touch target minimum). This ensures the drag handle and first actionable row are visible at peek.

### 2.3 ModalBottomSheet — Standard Pattern

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidgelineModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 6.dp,  // Asymmetric
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        dragHandle = { RidgelineDragHandle() },
        contentWindowInsets = { WindowInsets.safeDrawing },
        content = content,
    )
}
```

### 2.4 Drag Handle

M3's default drag handle is fine functionally but we match the Ridgeline shape language:

```kotlin
@Composable
fun RidgelineDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = RidgelineSpacing.Xs)  // 4dp above and below
            .width(32.dp)
            .height(4.dp)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(2.dp),
            ),
    )
}
```

### 2.5 Sheet Content Patterns

Three standard content patterns inside sheets:

**Pattern A: Action List** (most common — trip overflow, export options)

```kotlin
// Inside ModalBottomSheet content
Column(modifier = Modifier.padding(bottom = RidgelineSpacing.Lg)) {
    // Optional title
    Text(
        text = "Trip Options",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            horizontal = RidgelineSpacing.Lg,
            vertical = RidgelineSpacing.Md,
        ),
    )
    // Action items — use ListItem for consistency
    ListItem(
        headlineContent = { Text("Export as GPX") },
        leadingContent = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
        modifier = Modifier.clickable { /* action */ },
    )
    ListItem(
        headlineContent = { Text("Share") },
        leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
        modifier = Modifier.clickable { /* action */ },
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = RidgelineSpacing.Lg),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
    )
    ListItem(
        headlineContent = { Text("Delete") },
        leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        colors = ListItemDefaults.colors(
            headlineColor = MaterialTheme.colorScheme.error,
            leadingIconColor = MaterialTheme.colorScheme.error,
        ),
        modifier = Modifier.clickable { /* destructive action */ },
    )
}
```

**Pattern B: Form Content** (filter selection, settings override)

```kotlin
Column(
    modifier = Modifier
        .padding(horizontal = RidgelineSpacing.Lg)
        .padding(bottom = RidgelineSpacing.Lg),
    verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
) {
    Text("Filter Sessions", style = MaterialTheme.typography.titleMedium)
    // Form fields...
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onReset) { Text("Reset") }
        Spacer(Modifier.width(RidgelineSpacing.Sm))
        Button(onClick = onApply) { Text("Apply") }
    }
}
```

**Pattern C: Info Display** (session quick-view, stat detail)

```kotlin
Column(
    modifier = Modifier.padding(RidgelineSpacing.Lg),
    verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
) {
    Text("Session Detail", style = MaterialTheme.typography.titleMedium)
    // Metric rows using Quick-Stat Card layout
    // ...
    Spacer(Modifier.height(RidgelineSpacing.Sm))
    OutlinedButton(
        onClick = onViewFull,
        modifier = Modifier.fillMaxWidth(),
        shape = MomentumPillShape,
    ) {
        Text("View Full Detail")
    }
}
```

### 2.6 Sheet Rules

1. **Max expanded height:** 90% of screen height. Content beyond that scrolls inside the sheet.
2. **Scrim alpha:** 0.32 (`scrim` color). Lighter than M3 default (0.44) — keeps map context visible.
3. **Sheet shape:** Always asymmetric top corners (20dp/6dp). Bottom corners: 0dp (sheets attach to screen bottom).
4. **No nested sheets.** If a sheet action needs more UI, navigate to a new screen.
5. **Keyboard avoidance:** `contentWindowInsets = { WindowInsets.ime }` — sheet content lifts above keyboard.
6. **Accessibility:** Sheet title is announced as heading. Action items have content descriptions.

---

## 3. List Item Specs

### 3.1 When to Use `ListItem` vs Custom `Row`

| Use `ListItem` | Use Custom `Row` |
|----------------|------------------|
| Settings items (all variants) | Dashboard metric cards (non-list layout) |
| Sheet action lists | Custom card content (Trip Card, Challenge Card) |
| Selection lists (single/multi choice) | Horizontal carousels |
| Simple info rows in detail screens | Complex multi-row layouts inside cards |

**Rule: If it's a vertically-stacked tappable row in a list, use `ListItem`. If it's a custom visual composition, use `Row`/`Column`.**

### 3.2 ListItem Variants

M3 `ListItem` supports three density levels based on content:

| Lines | Height | Leading | Trailing | Ridgeline Use Case |
|-------|--------|---------|----------|-------------------|
| **1-line** | 56dp | Icon (24dp) or Avatar (40dp) | Icon, Switch, or Checkbox | Settings toggle, sheet action item |
| **2-line** | 72dp | Icon (24dp) or Avatar (40dp) | Icon, Switch, text, or badge | Settings item with subtitle, session row |
| **3-line** | 88dp | Icon or Thumbnail (56dp) | Icon or text | Session with description + metadata, rarely used |

### 3.3 Canonical Settings Item

```kotlin
@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = subtitle?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = buildString {
                    append(title)
                    if (subtitle != null) append(": $subtitle")
                }
            },
    )
}
```

### 3.4 ListItem Color Tokens

| Element | Token | Rationale |
|---------|-------|-----------|
| Container (default) | `Color.Transparent` | List items sit on `surface`; adding another surface layer is unnecessary. |
| Container (selected) | `secondaryContainer` | For single/multi-select lists (e.g., activity type filter). |
| Headline | `onSurface` | Primary text, full emphasis. |
| Supporting | `onSurfaceVariant` | Secondary text, reduced emphasis. |
| Leading icon | `onSurfaceVariant` | Secondary emphasis. Active/selected: `primary`. |
| Trailing icon | `onSurfaceVariant` | Functional icons (chevron, overflow). |
| Trailing switch | M3 Switch defaults | Switch manages its own colors (see §5). |
| Overline | `onSurfaceVariant` | Rarely used; available for category labels. |

### 3.5 ListItem Spacing

- **Between items in a section:** `spacedBy(0.dp)` — no gap, items touch. Item dividers provide visual separation.
- **Between items (when no divider):** `spacedBy(2.dp)` — `Xxs` hairline gap provides subtle visual breathing.
- **Item internal horizontal padding:** Handled by `ListItem` defaults (16dp start, 24dp end for trailing content). No override needed.
- **Leading icon to headline gap:** 16dp (M3 default). No override.

### 3.6 Dividers Between List Items

Per Round 12 (DESIGN_SYSTEM §9):
- **Within settings section:** Full-width item divider, 16dp horizontal padding, `outlineVariant` at 0.24α.
- **Between settings sections:** Section divider at 52dp start indent, 0.38α.
- **Sheet action list:** No dividers between normal items. Divider only before destructive actions (see §2.5 Pattern A).

---

## 4. Progress Indicators

### 4.1 When to Use Which

| Type | Determinate | Indeterminate | Ridgeline Use Case |
|------|-------------|---------------|-------------------|
| **`LinearProgressIndicator`** | ✓ | ✓ | File export/import, data processing, active tracking progress below TopAppBar |
| **`CircularProgressIndicator`** | ✓ | ✓ | Loading states (screen/section), goal progress rings |

**Selection rule:**
- **Linear** when progress is associated with a *width* (bar below top app bar, inside a card, file progress).
- **Circular** when progress is a *standalone indicator* (loading spinner, goal ring, challenge arc).
- **Determinate** when we know the total (export: bytes/total, goal: current/target).
- **Indeterminate** when duration is unknown (initial data load, GPS fix acquisition).

### 4.2 Linear Progress — Standard

```kotlin
@Composable
fun RidgelineLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
    )
}
```

### 4.3 Linear Progress — Below TopAppBar (Active Tracking)

When tracking is active, a thin linear progress bar appears directly below the top app bar. This provides persistent, non-intrusive feedback.

```kotlin
@Composable
fun TrackingProgressBar(
    isTracking: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isTracking,
        enter = expandVertically(
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        ),
        exit = shrinkVertically(
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        ),
    ) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round,
            // Indeterminate — tracking duration is unbounded
        )
    }
}
```

**Placement:** Immediately below `TopAppBar` in the `Scaffold` content, before any scrollable content. Not inside the `TopAppBar` itself.

### 4.4 Circular Progress — Loading State

```kotlin
@Composable
fun RidgelineLoadingIndicator(
    modifier: Modifier = Modifier,
) {
    CircularProgressIndicator(
        modifier = modifier.size(48.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeWidth = 4.dp,
        strokeCap = StrokeCap.Round,
    )
}
```

### 4.5 Circular Progress — Goal Ring (Determinate)

Goal progress rings on the dashboard use determinate circular progress with custom sizing:

```kotlin
@Composable
fun GoalProgressRing(
    progress: () -> Float,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize(),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeWidth = strokeWidth,
            strokeCap = StrokeCap.Round,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

### 4.6 Trail Progress Bar — FINALIZED

The "trail progress bar" concept from earlier rounds: a horizontal progress indicator styled to evoke a trail/path. Implementation: **standard `LinearProgressIndicator` with round caps and Ridgeline color tokens.** No custom Canvas, no trail texture — the round `StrokeCap` provides the organic feel, and the brand colors carry the identity.

```kotlin
@Composable
fun TrailProgressBar(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round,
    )
}
```

**Usage contexts:**
- Challenge progress in Challenge Card (inside the 160×120dp card)
- Daily goal progress in Stats Summary Card
- Export/import progress in Import/Export screen
- Achievement progress in Game screen

**Height variants:**
- **4dp:** Subtle, below TopAppBar, inside compact components.
- **8dp:** Standard, inside cards and sections. **Default.**
- **12dp:** Hero, standalone progress display (rare — only daily summary if no goal rings).

### 4.7 Progress Color Tokens

| Context | Progress Color | Track Color |
|---------|---------------|-------------|
| Default | `primary` | `surfaceContainerHighest` |
| Goal achieved (≥100%) | `success` (extended) | `successContainer` (extended) |
| Activity-specific | `activityWalk`/`Run`/`Ride`/`Vehicle` (adaptive) | `surfaceContainerHighest` |
| Error/blocked | `error` | `errorContainer` |
| Challenge (by difficulty) | `tertiary` (easy), `secondary` (medium), `error` (hard) | `surfaceContainerHighest` |

### 4.8 Anti-Patterns

- ❌ Custom animated progress (Lottie, Canvas arcs). Use M3 components.
- ❌ Indeterminate for operations with known progress (export with byte count).
- ❌ Progress inside a dialog body (use inline progress in the triggering screen).
- ❌ Multiple simultaneous progress indicators on one screen. One linear + one circular maximum.

---

## 5. Switch & Toggle Specs

### 5.1 When Switch vs Checkbox

| Control | Use Case | Ridgeline Context |
|---------|----------|-------------------|
| **Switch** | Binary on/off that takes effect *immediately*. | Settings toggles: auto-tracking, dark mode, dynamic color, battery optimization. |
| **Checkbox** | Binary selection in a *batch* that requires a confirm action. | Multi-select lists: choose sessions to export, select activity types for filter. |

**Rule: If the change applies instantly (no "Save" button), use Switch. If the user must confirm, use Checkbox.**

### 5.2 Switch Implementation

```kotlin
@Composable
fun RidgelineSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbContent: (@Composable () -> Unit)? = if (checked) {
        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
    } else {
        null
    },
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = thumbContent,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedIconColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
```

### 5.3 Switch with Icon

M3 Expressive supports `thumbContent` for icons inside the switch thumb. Ridgeline uses this:

- **Checked:** `Icons.Filled.Check` (16dp) — confirms the "on" state.
- **Unchecked:** No icon (null) — M3 default small thumb, clean.
- **Never** use `Icons.Filled.Close` for unchecked — visual noise, redundant with the track state.

### 5.4 Switch in Settings ListItem

The canonical pattern (already partially implemented in `SettingsComponents.kt`):

```kotlin
@Composable
fun SwitchSettingsItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = subtitle?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        trailingContent = {
            RidgelineSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "On" else "Off"
            },
    )
}
```

**Key details:**
- The entire row is clickable and toggles the switch.
- `Role.Switch` semantics announced by TalkBack.
- `stateDescription` provides "On"/"Off" for accessibility.
- Switch `onCheckedChange` AND row `clickable` both toggle — M3 handles dedup.

### 5.5 Custom Track Colors

No custom track colors. M3 defaults are well-tested for contrast and accessibility. The `primary`/`outline`/`surfaceContainerHighest` tokens already adapt to dynamic color and dark mode.

**Exception:** If a future "danger zone" settings section needs a destructive toggle (e.g., "Delete all data on export"), use:

```kotlin
colors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onError,
    checkedTrackColor = MaterialTheme.colorScheme.error,
)
```

This is reserved and not currently used.

---

## 6. Text Field Specs

### 6.1 When to Use Which

| Variant | Use Case | Ridgeline Context |
|---------|----------|-------------------|
| **`OutlinedTextField`** | Primary text input. Clear boundaries, high visibility. | Session naming, search, export filename, WiFi SSID entry. |
| **`TextField` (filled)** | ❌ **Not used.** | Filled text fields have lower visual priority and blend with `surfaceContainer` backgrounds. In a data-dense app, the outline provides necessary visual distinction. |

**Rule: `OutlinedTextField` everywhere. No filled text fields.**

### 6.2 Standard Implementation

```kotlin
@Composable
fun RidgelineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    isError: Boolean = errorText != null,
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    maxLength: Int? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                if (maxLength == null || newValue.length <= maxLength) {
                    onValueChange(newValue)
                }
            },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon?.let {
                { Icon(it, contentDescription = null) }
            },
            trailingIcon = trailingIcon ?: if (isError) {
                { Icon(Icons.Filled.Error, contentDescription = "Error") }
            } else {
                null
            },
            isError = isError,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = MaterialTheme.shapes.small,  // L2: 8dp rounded
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                errorLabelColor = MaterialTheme.colorScheme.error,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        // Helper text / error text / character counter row
        if (helperText != null || errorText != null || maxLength != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = RidgelineSpacing.Lg,
                        end = RidgelineSpacing.Lg,
                        top = RidgelineSpacing.Xs,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = errorText ?: helperText ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (maxLength != null) {
                    Text(
                        text = "${value.length}/$maxLength",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
```

### 6.3 Text Field States

| State | Border | Label | Helper/Counter |
|-------|--------|-------|----------------|
| **Unfocused, empty** | `outline` 1dp | Floating in border (label position) | `onSurfaceVariant` |
| **Focused, empty** | `primary` 2dp | Floated above, `primary` color | `onSurfaceVariant` |
| **Focused, with text** | `primary` 2dp | Floated above, `primary` | `onSurfaceVariant` |
| **Unfocused, with text** | `outline` 1dp | Floated above, `onSurfaceVariant` | `onSurfaceVariant` |
| **Error** | `error` 2dp | `error` color | Error text replaces helper, `error` color |
| **Disabled** | `onSurface` 0.12α | `onSurface` 0.38α | Hidden |

### 6.4 Error State Pattern

```kotlin
// Usage example — session naming with validation
var sessionName by remember { mutableStateOf("") }
val nameError = when {
    sessionName.isBlank() -> null  // Not an error until submitted
    sessionName.length < 3 -> "Name must be at least 3 characters"
    sessionName.length > 50 -> "Name must be 50 characters or fewer"
    else -> null
}

RidgelineTextField(
    value = sessionName,
    onValueChange = { sessionName = it },
    label = "Session name",
    placeholder = "Morning run",
    helperText = "Give this session a memorable name",
    errorText = nameError,
    maxLength = 50,
    leadingIcon = Icons.Outlined.Edit,
    keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Done,
    ),
)
```

### 6.5 Search Field Variant

Search fields are `OutlinedTextField` with specific affordances:

```kotlin
@Composable
fun RidgelineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onClear: () -> Unit = { onQueryChange("") },
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,  // Full-round for search
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}
```

### 6.6 Text Field Rules

1. **Always provide a label.** Placeholder alone is insufficient — it disappears on input.
2. **Helper text:** Use for formatting hints ("DD/MM/YYYY") or constraints ("Max 50 characters"). Optional.
3. **Character counter:** Show only when `maxLength` is set. Format: `23/50`.
4. **Error text replaces helper text** — never show both simultaneously.
5. **Leading icon:** Optional. Use for context (search, edit, location). 24dp, `onSurfaceVariant`.
6. **Trailing icon:** Clear button for search. Error icon for error state. Never both.
7. **Shape:** `MaterialTheme.shapes.small` (L2, 8dp) for standard fields. `shapes.extraLarge` for search pill.

---

## 7. Menu Specs

### 7.1 DropdownMenu — Standard

Used for overflow actions (trip detail "more" button, map layer selection):

```kotlin
@Composable
fun RidgelineDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,  // L2: 8dp rounded
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,   // E2 — Lifted (menus float above content)
        shadowElevation = 2.dp,
        content = content,
    )
}
```

### 7.2 DropdownMenuItem — Standard

```kotlin
// Standard item
DropdownMenuItem(
    text = {
        Text("Export as GPX", style = MaterialTheme.typography.bodyLarge)
    },
    onClick = { onExportGpx(); onDismiss() },
    leadingIcon = {
        Icon(Icons.Outlined.FileDownload, contentDescription = null)
    },
)

// Destructive item (delete, clear)
DropdownMenuItem(
    text = {
        Text(
            "Delete",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    },
    onClick = { onDelete(); onDismiss() },
    leadingIcon = {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
    },
)
```

### 7.3 ExposedDropdownMenu — Selection Fields

Used when a text field should present predefined options (activity type selection, unit system picker, export format):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidgelineExposedDropdown(
    selectedOption: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
```

### 7.4 Menu Item Specs

| Property | Value | Notes |
|----------|-------|-------|
| **Item height** | 48dp minimum | M3 default. Meets touch target requirement. |
| **Text style** | `bodyLarge` (16sp) | Readable, not cramped. |
| **Leading icon** | 24dp, `onSurfaceVariant` | Optional. Provides quick visual scan. |
| **Trailing icon/text** | `onSurfaceVariant` | Keyboard shortcut hints (not used on mobile), or checkmarks for selection. |
| **Content padding** | 12dp vertical, 16dp horizontal | M3 default via `DropdownMenuItemDefaults.contentPadding`. |
| **Dividers** | `HorizontalDivider` at 0.24α | Use sparingly — only before destructive actions or between logical groups. |
| **Max visible items** | **5.5 items** | Menu scrolls after 5.5 items visible (264dp max menu height). The "half item" peek signals scrollability. |

### 7.5 Menu Rules

1. **Max 7 items** per menu. Beyond 7, use a `ModalBottomSheet` action list (§2.5 Pattern A) instead.
2. **Group ordering:** Primary actions first → secondary → divider → destructive (last).
3. **No nested menus.** Mobile doesn't support sub-menus well. Use a sheet or new screen.
4. **Dismiss on action.** Every `onClick` must call `onDismissRequest` after the action.
5. **Positioning:** M3 auto-positions menus. Never manually offset.
6. **Icons are optional.** If one item has an icon, all items should — visual alignment matters.
7. **No checkboxes in DropdownMenu.** Use `ExposedDropdownMenu` for selection, or a bottom sheet with checkboxes for multi-select.

---

## Summary: What's Now Locked

| Topic | Decision | Status |
|-------|----------|--------|
| Elevation levels | 4 levels (E0–E3). GPT's E4/E5 rejected. | **FINAL** |
| Responsive gutters | Accepted: 16/24/32dp by window width via `RidgelineGutters.horizontal` | **FINAL** |
| Dual-pane | Deferred per Round 14. Not in v1. | **FINAL** |
| Top app bar | `TopAppBar` (small) everywhere except onboarding (`CenterAligned`). No Medium/Large. | **FINAL** |
| Top app bar scroll | Per-screen: pinned (Dashboard/Settings), enterAlways (Statistics/Game), exitUntilCollapsed (Trip Detail) | **FINAL** |
| Bottom sheets | `BottomSheetScaffold` for map only. `ModalBottomSheet` everywhere else. Asymmetric corners (20dp/6dp). | **FINAL** |
| List items | `ListItem` for all tappable rows. Transparent container. M3 default spacing. | **FINAL** |
| Progress indicators | `LinearProgressIndicator` for bars, `CircularProgressIndicator` for rings/loading. Round stroke caps. 4/8/12dp height variants. | **FINAL** |
| Trail progress bar | Standard `LinearProgressIndicator`, 8dp default, round caps, Ridgeline color tokens. No custom Canvas. | **FINAL** |
| Switch | M3 Switch with check icon when on. No custom track colors (except reserved destructive). | **FINAL** |
| Switch vs Checkbox | Switch = immediate effect. Checkbox = batch confirm. | **FINAL** |
| Text fields | `OutlinedTextField` exclusively. No filled variant. L2 shape (8dp). | **FINAL** |
| Search field | `OutlinedTextField` with full-round shape, clear button. | **FINAL** |
| Error/helper text | Below field, 4dp gap. Error replaces helper. Counter end-aligned. | **FINAL** |
| Dropdown menu | L2 shape, `surfaceContainer`, E2 elevation. Max 7 items, then use sheet. | **FINAL** |
| Exposed dropdown | `OutlinedTextField` + `ExposedDropdownMenu`. Read-only anchor. | **FINAL** |

---

## Next: Round 16 Topics (Suggested)

With component specs locked, we should move to **screen-level patterns and state handling**:

1. **Loading states** — Skeleton vs shimmer vs progress indicator. Per-screen decisions.
2. **Error states** — Inline error, banner, dialog. Retry patterns. Network-free error taxonomy.
3. **Permission request flow** — Step-by-step UI for location, activity recognition, notifications.
4. **Snackbar specs** — Placement (above floating nav), duration, action button, dismiss behavior.
5. **Tooltip specs** — Plain vs rich. When to use. Placement rules.
6. **Settings screen structure** — Complete group hierarchy, toggle/link/info patterns, danger zone.
7. **Map UI overlay system** — Metric card positions, layer control, compass, attribution.

GPT: Confirm Round 15 decisions or raise blocking objections. Then proceed to Round 16.
