# Ridgeline Design System — Round 10 Response

**From:** Lead Designer (Claude, Ridgeline)  
**To:** GPT (QuietTopo)  
**Round:** 10 of 30  
**Status:** CONVERGENCE ROUND — All 4 legacy items resolved with binding decisions

---

## Part A: FINAL Binding Decisions — Legacy Disagreements

---

### 1. Display Font — ✅ RESOLVED: Outfit everywhere, with concession

**FINAL DECISION: Outfit for Display/Headline/Title. System font for Body/Label. No Manrope.**

I'm holding Outfit but offering a concrete, measurable concession GPT can accept:

**Concession:** If a future accessibility audit (run against Czech locale, `cs-CZ`, at 200% font scaling on a 360dp-wide device) shows that Outfit renders any diacritic glyph (`ě`, `š`, `č`, `ř`, `ž`, `ů`, `ď`, `ť`, `ň`) with clipping, overlap, or sub-pixel artifacts at any style from `displaySmall` (44sp) through `titleSmall` (14sp), we switch **that specific style** to Manrope. This is a testable, falsifiable criterion — not a matter of opinion.

**Why this resolves it:**
- GPT's concern was Czech diacritics at small display sizes. This gives a measurable escape hatch.
- The codebase already has all 13 styles tuned to Outfit metrics. No changes needed today.
- If the audit passes (which it will — Outfit v6+ handles Latin Extended correctly), the question is permanently closed.

**Implemented spec (unchanged from Round 9):**
```
Display (64sp, 52sp, 44sp) → Outfit Bold
Headline (36sp, 32sp, 28sp) → Outfit SemiBold
Title (22sp, 18sp, 14sp)    → Outfit Medium
Body (16sp, 14sp, 12sp)     → System (Noto Sans / Roboto)
Label (14sp, 12sp, 11sp)    → System (Noto Sans / Roboto)
```

**Code:** `Typography.kt` already implements this scale. Font family swap from `FontFamily.Default` to `OutfitFontFamily` happens when Outfit is bundled as a resource.

---

### 2. System Name — ✅ RESOLVED: Ridgeline with acknowledged alias

**FINAL DECISION: "Ridgeline" is the design system name everywhere.**

**Concession acknowledged:** `QuietTopoConfig` remains acceptable as an internal Kotlin class name for theme configuration. This was already conceded in Round 9 and stands.

**Binding rule:**
- Documentation headers, token prefixes, design system references → **Ridgeline**
- Kotlin internal class for theme config wiring → `QuietTopoConfig` is acceptable (not required)
- No "QuietTopo" in user-facing strings, README, or external docs

This is settled. The vocabulary (`WaypointShape`, `TerrainCardShape`, `Trail Slate`, `SecureSnap`, `SpatialGlide`) derives from the Ridgeline mountain/terrain metaphor and is already in the codebase.

---

### 3. Glass under Reduced Motion — ✅ RESOLVED: Compromise accepted

**FINAL DECISION: Glass blur stays by default. GPT's toggle is accepted as an ADDITIVE accessibility option — but scoped differently.**

**Compromise:** Instead of GPT's "Allow depth effects" toggle (which creates a 3-way interaction matrix), I accept a simpler scope:

| Setting | Glass Treatment | Rationale |
|---------|----------------|-----------|
| Default | Blur + tint + border | Full glass effect |
| `REDUCE_ANIMATIONS` only | Blur + tint + border (static) | Blur is not animation |
| `REDUCE_TRANSPARENCY` only | Solid `surfaceContainer` + border | Platform-correct |
| Both | Solid `surfaceContainer` + border | Maximum accessibility |
| **In-app "Simplified surfaces" toggle** | Solid `surfaceContainer` + border | **NEW — user override** |

**What changed:** I'm accepting that some users may want solid surfaces even without the system `REDUCE_TRANSPARENCY` flag. The in-app toggle is a simple boolean in DataStore preferences, not a third interaction axis — it acts as a local override that maps to the same solid treatment.

**Implementation:**
```kotlin
// In GlassCard / any glass component:
val reduceTransparency = LocalReduceTransparency.current
val simplifiedSurfaces = LocalSimplifiedSurfaces.current // from DataStore
val useGlass = !reduceTransparency && !simplifiedSurfaces

Surface(
    color = if (useGlass) {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    },
    // ... blur via Haze only when useGlass == true
)
```

The toggle lives in Settings → Accessibility (if we add that section) or Settings → Display. Single boolean. No matrix.

---

### 4. Mini FAB — ✅ RESOLVED: No mini FAB, MapToolButton is 52dp

**FINAL DECISION: No 40dp mini FAB component exists in Ridgeline. Period.**

GPT's approach (40dp visual + 48dp touch via `minimumInteractiveComponentSize`) is technically valid per M3 spec, but **we don't need it** because:

1. The codebase already has `MapToolButton` at 52dp — exceeds both M3 (48dp) and WCAG 2.5.8 (44dp) minimums.
2. There is no use case in this app where 52dp is too large. Map tools, dashboard actions, and sheet controls all work at 52dp.
3. Introducing a 40dp component creates a second size variant that developers will misuse.

**Binding rule:** All interactive circular buttons in the system are either:
- **56dp** — Standard FAB (`TrackingFAB`)
- **52dp** — `MapToolButton` / tool buttons
- **48dp** — `IconButton` (Material 3 default, used in top bars and inline actions)

No 40dp variant. If GPT's future specs reference a "mini FAB," it maps to our 52dp `MapToolButton`.

---

## Part B: New Topics — Implementation-Ready Specs

---

### 5. Dashboard Layout

The dashboard already has a solid implementation in `DashboardScreen.kt` → `IdleContent.kt`. This spec codifies the architecture and fills gaps.

#### 5.1 Compose Structure

```
DashboardScreen (Scaffold)
├── TopBar: DashboardTopBar
│   ├── Left: "Tracker" titleLarge
│   ├── Center: PolicyTier badge (if active)
│   └── Right: [Points chip] [Settings icon] [Game icon?]
├── FAB: TrackingFAB (WaypointShape, 56dp, FabPosition.End)
├── SnackbarHost
└── Content (by mode):
    ├── EMPTY → EmptyStateContent (centered)
    ├── IDLE → IdleContent (LazyColumn)
    └── TRACKING → TrackingContent (live data)
```

#### 5.2 Idle Dashboard — Card Order & Specs

The `IdleContent` LazyColumn uses `Arrangement.spacedBy(12.dp)` with `16.dp` horizontal padding. Cards appear in this fixed order:

| # | Key | Component | Card Type | Height | Condition |
|---|-----|-----------|-----------|--------|-----------|
| 1 | `motivational` | `MotivationalText` | Inline text, no card | Auto | Always |
| 2 | `today_progress` | `TodayProgressCard` | `Card(surfaceContainer)` | Auto (~120dp) | Always |
| 3 | `streak` | `StreakBanner` | `Card(tertiaryContainer)` | 64dp | `streakState.current > 0` |
| 4 | `challenges` | `ChallengeCardsRow` | Horizontal `LazyRow` of `GlassCard` | 140dp | `activeChallenges.isNotEmpty()` |
| 5 | `last_session` | `LastSessionCard` | `GlassCard` | Auto (~160dp) | `sessionData != null` |
| 6 | `recent_trips` | `RecentTripsCard` | `Card(surfaceContainer)` | Auto | `recentTrips.isNotEmpty()` |
| 7 | `exploration` | `ExplorationCard` | `GlassCard` | Auto (~100dp) | `hasExplorationData` |
| 8 | `bottom_spacer` | `Spacer` | — | 120dp | Always (FAB clearance) |

#### 5.3 TodayProgressCard — Detailed Spec

Already implemented correctly. Key design tokens:

```kotlin
Card(
    containerColor = surfaceContainer,
    shape = MaterialTheme.shapes.large,  // MomentumPillShape (asymmetric)
) {
    Row(padding = 20.dp) {
        // Left column
        Column {
            Text("Today", titleMedium, onSurfaceVariant)
            Text(distanceText, displaySmall, Bold, onSurface)  // Hero metric
            Spacer(8.dp)
            Row(spacedBy = 16.dp) {
                SecondaryMetric(label, value)  // labelMedium + bodyMedium SemiBold
            }
        }
        // Right: GoalProgressRings (when gamification enabled)
        GoalProgressRings(size = 80.dp, strokeWidth = 6.dp)
    }
}
```

**Empty state (no activity today):**
```kotlin
Text(
    "No activity recorded yet today",
    bodyLarge,
    onSurface.copy(alpha = 0.7f)
)
```

#### 5.4 Empty State — First Launch

```kotlin
EmptyStateCard(
    icon = Icons.Outlined.Explore,           // 48dp, primary
    title = "Start your first trip",          // titleLarge, onSurface
    subtitle = "Tap the button below...",     // bodyMedium, onSurfaceVariant
    modifier = Modifier.padding(32.dp)
)
// EmptyStateCard uses GlassCard internally with centered Column
// Icon: 48dp in 72dp circle (primaryContainer background)
// Spacing: icon → title = 16dp, title → subtitle = 8dp
```

#### 5.5 Pull-to-Refresh

**Decision: No pull-to-refresh.** This is a local-only app. Data updates are event-driven (tracking state changes, database writes). There's no remote source to "refresh" from. Adding pull-to-refresh would:
1. Create false expectations of remote sync
2. Add unnecessary state complexity
3. Violate the privacy-first mental model

Data updates propagate via `StateFlow` from Room → ViewModel → UI automatically.

---

### 6. Settings Architecture — Visual Design

The existing `RootSettingsScreen.kt` and `SettingsComponents.kt` establish the pattern. This spec codifies it.

#### 6.1 Settings Navigation Structure

```
RootSettings
├── Core
│   ├── Tracking → TrackingSettingsScreen
│   ├── Data → DataSettingsScreen
│   └── Activities → ActivitySettingsScreen
├── General (inline)
│   ├── Length system (DialogList)
│   ├── Auto unit switch (Switch)
│   ├── Speed format (DialogList)
│   └── Language → System settings
├── Modules
│   ├── Map → MapSettingsScreen
│   ├── Game → GameSettingsScreen
│   └── Statistics → StatisticsSettingsScreen
├── About (inline)
│   ├── About app (info display)
│   ├── Licenses → LicenseActivity
│   ├── Privacy policy → External URL
│   └── Send feedback → GitHub Issues
└── Debug (conditional: BuildConfig.DEBUG || developerMode)
    └── Debug → DebugSettingsScreen
```

#### 6.2 Settings Group Card — Component Spec

Already implemented as `SettingsGroupCard`. Codified tokens:

```kotlin
@Composable
fun SettingsGroupCard(
    title: String? = null,      // titleMedium heading outside the card
    icon: ImageVector? = null,  // 20dp section-orientation icon
    tone: SettingsItemTone = SettingsItemTone.Default,
    content: @Composable ColumnScope.() -> Unit
) {
    // Title and icon outside card
    // Card:
    //   containerColor = surfaceContainer
    //   shape = default (medium = TerrainCardShape)
    //   horizontalPadding = 16.dp (on Card modifier)
    //   SettingsRowDivider inset = 72.dp (aligned with text after 40dp icon tile)
}
```

**Group spacing:** 12–16.dp top margin between groups. Every destination uses grouped cards; loose rows are not mixed with card groups.

#### 6.3 Settings Item Variants

| Variant | Component | Leading | Trailing | Touch Target |
|---------|-----------|---------|----------|-------------|
| Navigation | `SettingsItem` | Unique icon in a 40dp themed tile | None | Full row, min 56dp height |
| Switch | `SwitchSettingsItem` | Optional icon tile | `Switch` | Entire row uses switch semantics |
| Static value | `SettingsSummaryItem` | Optional icon tile | None | Non-clickable metadata row |
| Dialog picker | `DialogListPreference` | Optional icon tile | Selected-value pill | Full row → single-choice dialog |
| Slider | `SliderSettingsItem` | None | Selected-value pill | Slider track + thumb |
| Notice | `SettingsNoticeCard` | Optional 22dp icon | None | Neutral, info, warning, or danger tone |

**Typography within items:**
- Headline: `titleMedium` (default from `ListItem`)
- Subtitle: `bodySmall`, `onSurfaceVariant`
- Value: `labelLarge` in a `secondaryContainer` pill so the current selection is visually explicit

**Icon rule:** use a distinct pictogram for each user concept. Reusing an icon for a section heading and its primary row is acceptable; unrelated rows in one card must not share an icon.

Tracking sources use a dedicated native-vector family (`ic_tracking_source_*`) rather than generic Material symbols. Each drawable uses a 32dp viewport, rounded strokes, a distinct silhouette, and Compose-applied theme tint inside a 40–44dp `primaryContainer` tile. The same source asset must appear in simple switches and advanced frequency controls. Validate additions at their rendered 24–27dp size in both light and dark themes; do not judge source artwork only at editor zoom.

#### 6.4 Danger Zone — Design Spec

The `DataSettingsScreen` already implements this correctly. Codified pattern:

```kotlin
SettingsGroupCard(
    title = "Danger Zone",
    icon = Icons.Filled.DeleteForever,
    tone = SettingsItemTone.Danger,
) {
    SettingsItem(
        title = "Remove all collected data",
        subtitle = "This action cannot be undone",
        icon = Icons.Filled.DeleteForever,
        tone = SettingsItemTone.Danger,
        onClick = { showDeleteDialog = true },
    )
}

// Confirmation dialog
AlertDialog(
    icon = { Icon(Icons.Filled.Warning, tint = error) },
    title = { Text("Delete all data?", titleLarge) },
    text = { Text("This will permanently...", bodyMedium) },
    confirmButton = {
        Button(
            colors = ButtonDefaults.buttonColors(
                containerColor = error,
                contentColor = onError,
            ),
        ) { Text("Delete Everything") }
    },
    dismissButton = {
        TextButton(...) { Text("Cancel") }  // No color override
    },
    shape = DialogShape,  // 28dp rounded, NOT TerrainCardShape
)
```

**Rules for dangerous actions:**
1. Always behind a confirmation dialog with explicit destructive language
2. Confirm button uses `error` / `onError` colors — never `primary`
3. Dialog uses `DialogShape` (28dp symmetric) — not the asymmetric `AppShapes`
4. Icon in dialog header: `Icons.Filled.Warning` tinted `error`
5. Never auto-dismiss; user must explicitly confirm or cancel

#### 6.5 Version Info Card — Easter Egg

Already implemented: 7-tap on version card enables developer mode. Tokens:

```kotlin
Card(
    containerColor = surfaceVariant,
    modifier = padding(horizontal = 16.dp, vertical = 8.dp)
) {
    Column(padding = 16.dp, spacedBy = 4.dp) {
        Text(title, titleMedium, onSurfaceVariant)
        Text(version, bodyMedium, onSurfaceVariant)
        // Countdown (when tapping): bodySmall, primary color
    }
}
```

---

### 7. Trip Detail Screen — Full Spec

The existing `TripDetailRoute.kt` provides the scaffold. This spec extends it with the complete visual design.

#### 7.1 Screen Structure

```
TripDetailRoute (Scaffold)
├── TopAppBar
│   ├── NavigationIcon: ArrowBack
│   ├── Title: "Trip Details" (titleLarge)
│   └── Actions: MoreVert → DropdownMenu
│       ├── "Export GPX"
│       ├── "Export KML"
│       ├── "Share"
│       └── "Delete" (error color text)
└── Content: verticalScroll Column
    ├── HeaderCard (GlassCard)
    │   ├── Route icon in 56dp circle (primaryContainer bg)
    │   ├── Start time (titleMedium, SemiBold)
    │   └── Duration (bodyMedium, onSurfaceVariant)
    ├── MapPreview (TODO: future)
    │   └── Static map thumbnail, 200dp height, TerrainCardShape clip
    ├── PrimaryMetricsRow
    │   ├── MetricCard: Distance (weight 1f)
    │   └── MetricCard: Steps (weight 1f)
    ├── SecondaryMetricsRow (if data available)
    │   ├── MetricCard: Avg Speed
    │   └── MetricCard: Max Speed
    ├── ActivityBreakdown (if multiple activities detected)
    │   └── Horizontal stacked bar + legend
    ├── DeveloperMetrics (expandable)
    │   ├── MetricCard: Sample Count
    │   └── MetricCard: Primary Mode
    └── Spacer(120.dp) — nav bar clearance
```

#### 7.2 MetricCard — Detailed Spec

Already implemented, codified:

```kotlin
@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    GlassCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = labelMedium,           // 12sp, Medium, 0.5sp tracking
                color = onSurfaceVariant,
            )
            Spacer(4.dp)
            Text(
                text = value,
                style = titleLarge,            // 22sp, Medium → overridden Bold
                fontWeight = FontWeight.Bold,
                color = valueColor,
                fontFeatureSettings = "tnum",  // Tabular figures for alignment
            )
        }
    }
}
// Internal padding: 16dp (from GlassCard default)
// Card shape: TerrainCardShape (asymmetric)
// Card color: surfaceContainerLow with 1dp tonal elevation
```

#### 7.3 Header Card

```kotlin
GlassCard(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = CenterVertically) {
        // Icon badge
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(primaryContainer),  // NOT primary.copy(alpha)
            contentAlignment = Center,
        ) {
            Icon(
                Icons.Filled.Route,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = onPrimaryContainer,
            )
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(startTime, titleMedium, SemiBold, onSurface)
            Text(duration, bodyMedium, Normal, onSurfaceVariant)
        }
    }
}
```

**Design note:** The existing code uses `primary.copy(alpha = 0.2f)` for the icon background. The correct Ridgeline token is `primaryContainer` — it achieves the same visual effect but responds correctly to dynamic color.

#### 7.4 Export Actions

Menu items in the overflow `DropdownMenu`:

```kotlin
DropdownMenuItem(
    text = { Text("Export GPX") },
    leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
    onClick = { /* share intent with GPX file */ }
)
DropdownMenuItem(
    text = { Text("Export KML") },
    leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
    onClick = { /* share intent with KML file */ }
)
DropdownMenuItem(
    text = { Text("Share") },
    leadingIcon = { Icon(Icons.Outlined.Share, null) },
    onClick = { /* plain text summary share */ }
)
// Divider between export and destructive
HorizontalDivider()
DropdownMenuItem(
    text = { Text("Delete", color = error) },
    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = error) },
    onClick = { showDeleteDialog = true }
)
```

#### 7.5 Delete Confirmation

Already implemented in `DeleteConfirmationDialog`. One refinement — the confirm button should use error colors:

```kotlin
AlertDialog(
    shape = DialogShape,  // 28dp symmetric
    confirmButton = {
        TextButton(
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("Delete") }
    },
)
```

#### 7.6 State Handling

Already implemented with sealed class:

```kotlin
sealed class TripDetailState {
    data object Loading           // → CircularProgressIndicator, centered
    data class Loaded(trip)       // → TripOverview
    data object NotFound          // → EmptyStateCard(ErrorOutline, "Trip not found")
    data class Error(message)     // → EmptyStateCard + Retry button
}
```

---

### 8. Gamification UI — Full Spec

The `GameScreen.kt` is already well-structured. This spec codifies the visual system.

#### 8.1 Screen Structure

```
GameScreen (Box + LazyColumn)
├── Title: "Game" (titleLarge, padding horizontal 16dp)
├── HeroLevelCard
│   ├── Level badge (56dp circle, primary)
│   ├── XP progress bar
│   └── Streak info row
├── PointsCard (GlassCard)
│   ├── Star icon in 56dp circle
│   ├── Points value (displaySmall, Bold)
│   └── Points label (labelMedium)
├── StepsCard (GlassCard)
│   ├── Walk icon + title
│   └── Today/Week stat pairs with progress bars
├── ExplorationCard
├── AchievementCard
├── SectionHeader: "Active Challenges"
├── ActiveChallengesRow (horizontal scroll)
├── SectionHeader: "Mini-Games"
├── MiniGamesGrid
└── TrophySummaryCard
```

#### 8.2 HeroLevelCard — Detailed Spec

```kotlin
@Composable
fun HeroLevelCard(
    level: Int,
    xpIntoCurrentLevel: Long,
    xpForNextLevel: Long,
    streakCount: Int,
    streakBest: Int,
    freezeCount: Int,
) {
    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column {
            Row(verticalAlignment = CenterVertically) {
                // Level badge
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(primary),
                    contentAlignment = Center,
                ) {
                    Text(
                        text = level.toString(),
                        style = headlineMedium,  // 32sp
                        fontWeight = Bold,
                        color = onPrimary,
                    )
                }
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text("Level $level", titleLarge, Bold, onSurface)
                    Spacer(4.dp)
                    // XP progress bar
                    LinearProgressIndicator(
                        progress = { xpProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = primary,
                        trackColor = onSurfaceVariant.copy(alpha = 0.2f),
                    )
                    Text(
                        "$xpIntoCurrentLevel / $xpForNextLevel XP",
                        labelSmall,
                        onSurfaceVariant,
                        Modifier.padding(top = 4.dp),
                    )
                }
            }
            // Streak row
            Spacer(16.dp)
            Row(Modifier.fillMaxWidth(), SpaceEvenly) {
                StatChip(icon = 🔥, label = "Streak", value = "$streakCount days")
                StatChip(icon = ⭐, label = "Best", value = "$streakBest days")
                StatChip(icon = ❄️, label = "Freezes", value = "$freezeCount left")
            }
        }
    }
}
```

**StatChip spec:**
```kotlin
// Inline chip, no outline, just icon + label + value stacked
Column(horizontalAlignment = CenterHorizontally) {
    Icon(icon, size = 20.dp, tint = onSurfaceVariant)
    Text(label, labelSmall, onSurfaceVariant)  // 11sp
    Text(value, bodyMedium, SemiBold, onSurface)  // 14sp
}
```

#### 8.3 Challenge Cards — Horizontal Scroll

```kotlin
@Composable
fun ActiveChallengesRow(challenges: List<ChallengeUi>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(challenges, key = { it.id }) { challenge ->
            ChallengeCard(
                challenge = challenge,
                modifier = Modifier.width(260.dp),  // Fixed width for scroll
            )
        }
    }
}
```

**Individual ChallengeCard:**
```kotlin
GlassCard(modifier.heightIn(min = 120.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = CenterVertically) {
        // Left: Trophy icon
        Icon(Icons.Outlined.EmojiEvents, size = 32.dp, tint = primary)
        
        // Center: Title + description + progress bar
        Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f)) {
            Text(title, titleMedium, Bold, onSurface, maxLines = 1, ellipsis)
            Text(description, bodyMedium, onSurfaceVariant, maxLines = 2, ellipsis)
            // Progress bar: 4dp height, 2dp corner radius
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(onSurfaceVariant.copy(alpha = 0.2f))
            ) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(primary))
            }
        }
        
        // Right: percentage
        Text("${(progress * 100).toInt()}%", labelLarge, primary)
    }
}
```

#### 8.4 Achievement Badges — Visual System

Achievement states mapped to color tokens:

| State | Badge Background | Icon Tint | Border |
|-------|-----------------|-----------|--------|
| Locked | `surfaceContainerHigh` | `onSurfaceVariant.copy(0.4f)` | `outlineVariant.copy(0.3f)` |
| In Progress | `secondaryContainer` | `onSecondaryContainer` | `secondary.copy(0.5f)` |
| Earned (Bronze) | `tertiaryContainer` | `onTertiaryContainer` | `tertiary` |
| Earned (Silver) | `surfaceContainerHighest` | `onSurface` | `outline` |
| Earned (Gold) | `primaryContainer` | `onPrimaryContainer` | `primary` |

**Badge composable:**
```kotlin
@Composable
fun AchievementBadge(
    icon: ImageVector,
    tier: AchievementTier,  // LOCKED, IN_PROGRESS, BRONZE, SILVER, GOLD
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val (bgColor, iconTint, borderColor) = tier.colors()  // Mapped from table above
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.5.dp, borderColor, CircleShape),
        contentAlignment = Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(size * 0.5f),  // Icon is 50% of badge
            tint = iconTint,
        )
    }
}
```

#### 8.5 Progress Rings

Used in `GoalProgressRings` (dashboard) and `HeroLevelCard` (game):

```kotlin
@Composable
fun ProgressRing(
    progress: Float,          // 0f..1f
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    strokeWidth: Dp = 6.dp,
    trackColor: Color = onSurfaceVariant.copy(alpha = 0.15f),
    progressColor: Color = primary,
) {
    Canvas(modifier.size(size).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
    }) {
        // Track arc: full circle
        drawArc(trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false,
            style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round))
        // Progress arc
        drawArc(progressColor, startAngle = -90f, sweepAngle = 360f * progress,
            useCenter = false, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round))
    }
}
```

**Dual ring (distance + steps on dashboard):**
```kotlin
// Outer ring: distance goal, primary color, 80dp, 6dp stroke
// Inner ring: steps goal, tertiary color, 60dp, 5dp stroke
// Center: percentage text, labelLarge, Bold
Box(contentAlignment = Center) {
    ProgressRing(distanceProgress, size = 80.dp, strokeWidth = 6.dp, progressColor = primary)
    ProgressRing(stepsProgress, size = 60.dp, strokeWidth = 5.dp, progressColor = tertiary)
    Text("${(distanceProgress * 100).toInt()}%", labelLarge, Bold, onSurface)
}
```

#### 8.6 Trophy Summary Card

```kotlin
GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
    Column {
        Row(Modifier.fillMaxWidth(), SpaceBetween, CenterVertically) {
            Text("Trophies", titleMedium, Bold, onSurface)
            TextButton(onClick = onViewTrophyCase) {
                Text("View All", labelLarge, primary)
            }
        }
        Spacer(12.dp)
        Row(Modifier.fillMaxWidth(), SpaceEvenly) {
            TrophyCount(emoji = "🥇", count = goldCount, color = Color(0xFFFFD700))
            TrophyCount(emoji = "🥈", count = silverCount, color = Color(0xFFC0C0C0))
            TrophyCount(emoji = "🥉", count = bronzeCount, color = Color(0xFFCD7F32))
        }
        Text(
            "Total: $totalCompleted completed",
            bodySmall, onSurfaceVariant,
            Modifier.padding(top = 8.dp),
        )
    }
}
```

---

## Part C: Cross-Cutting Tokens Reference

### Spacing Scale (used across all new specs)

| Token | Value | Usage |
|-------|-------|-------|
| `spacingXs` | 4.dp | Between label and value, tight pairs |
| `spacingS` | 8.dp | Between related elements |
| `spacingM` | 12.dp | LazyColumn item spacing, card-to-card |
| `spacingL` | 16.dp | Horizontal page padding, section gaps |
| `spacingXl` | 20.dp | Card internal padding (hero cards) |
| `spacingXxl` | 32.dp | Empty state padding, large separations |

### Card Elevation

| Context | Tonal | Shadow |
|---------|-------|--------|
| GlassCard | 1.dp | 1.dp |
| SettingsGroupCard | 0.dp | 0.dp |
| MetricCard | 1.dp | 1.dp |
| Dialog | 6.dp | 6.dp |

### Touch Targets (binding, no exceptions)

| Component | Size | Minimum |
|-----------|------|---------|
| FAB (tracking) | 56dp | 56dp |
| MapToolButton | 52dp | 52dp |
| IconButton (toolbar) | 48dp | 48dp |
| ListItem row | Full width | 56dp height |
| Switch | 52×32dp visual | 48dp touch (via M3 default) |

---

## Summary of Binding Decisions

| Item | Decision | Status |
|------|----------|--------|
| Display Font | Outfit everywhere + falsifiable Czech audit escape hatch | ✅ CLOSED |
| System Name | Ridgeline (QuietTopoConfig class alias accepted) | ✅ CLOSED |
| Glass + Reduced Motion | Platform two-axis + in-app "Simplified surfaces" boolean | ✅ CLOSED |
| Mini FAB | No 40dp variant. 52dp MapToolButton is the smallest circle button | ✅ CLOSED |
| Dashboard Layout | LazyColumn, 7 card slots, no pull-to-refresh | ✅ SPECIFIED |
| Settings Architecture | SettingsGroupCard pattern, danger zone with error colors | ✅ SPECIFIED |
| Trip Detail Screen | GlassCard metrics, overflow menu exports, error-color delete | ✅ SPECIFIED |
| Gamification UI | HeroLevelCard, 5-tier achievement badges, dual progress rings | ✅ SPECIFIED |

All 4 legacy items are resolved with binding decisions. All 4 new topics have implementation-ready Compose specs with dp values, color tokens, and typography roles.
