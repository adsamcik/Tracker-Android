# Tracker Android - Material 3 Expressive Design System

## 1. Brand Personality
Privacy-first, fully local location & activity tracker for Android. Reliable, secure, modern, and slightly adventurous.

## 2. Color Palette

**Seed:** `#006874` (Secure Teal). Generated via Material 3 HCT color space.
Balances trust and security (deep, reliable cool tones) with adventure and activity (vibrant, energetic warm tones).

### 2.1 Core Palette — Complete Token Reference

#### Primary: Secure Teal

| Token | Light | Dark |
|-------|-------|------|
| `primary` | `#006874` | `#4FD8EB` |
| `onPrimary` | `#FFFFFF` | `#00363D` |
| `primaryContainer` | `#97F0FF` | `#004F58` |
| `onPrimaryContainer` | `#001F24` | `#97F0FF` |

#### Secondary: Trail Slate

| Token | Light | Dark |
|-------|-------|------|
| `secondary` | `#4A6367` | `#B1CBD0` |
| `onSecondary` | `#FFFFFF` | `#1C3438` |
| `secondaryContainer` | `#CDE7EC` | `#334B4F` |
| `onSecondaryContainer` | `#051F23` | `#CDE7EC` |

#### Tertiary: Sunset Rust

| Token | Light | Dark |
|-------|-------|------|
| `tertiary` | `#98483A` | `#FFB4A8` |
| `onTertiary` | `#FFFFFF` | `#5C190D` |
| `tertiaryContainer` | `#FFDAD4` | `#7A3024` |
| `onTertiaryContainer` | `#3C0903` | `#FFDAD4` |

#### Error

| Token | Light | Dark |
|-------|-------|------|
| `error` | `#BA1A1A` | `#FFB4AB` |
| `onError` | `#FFFFFF` | `#690005` |
| `errorContainer` | `#FFDAD6` | `#93000A` |
| `onErrorContainer` | `#410002` | `#FFDAD6` |

### 2.2 Neutral & Surface

| Token | Light | Dark |
|-------|-------|------|
| `surface` | `#F8FDFF` | `#0E1415` |
| `onSurface` | `#171D1E` | `#DFE4E5` |
| `surfaceVariant` | `#DBE4E6` | `#3F484A` |
| `onSurfaceVariant` | `#3F484A` | `#BFC8CA` |
| `surfaceBright` | `#F8FDFF` | `#353B3D` |
| `surfaceDim` | `#D5DBDC` | `#0E1415` |
| `surfaceTint` | `#006874` | `#4FD8EB` |
| `surfaceContainerLowest` | `#FFFFFF` | `#060B0C` |
| `surfaceContainerLow` | `#EFF3F8` | `#151B1D` |
| `surfaceContainer` | `#EBF4F6` | `#1A2022` |
| `surfaceContainerHigh` | `#DFE8EA` | `#252B2D` |
| `surfaceContainerHighest` | `#D3DDE0` | `#303638` |
| `background` | `#FBFCFF` | `#0E1415` |
| `onBackground` | `#171D1E` | `#DFE4E5` |

### 2.3 Utility

| Token | Light | Dark |
|-------|-------|------|
| `outline` | `#6F797A` | `#899294` |
| `outlineVariant` | `#C4C7CF` | `#3F484A` |
| `inverseSurface` | `#2B3133` | `#DFE4E5` |
| `inverseOnSurface` | `#ECF2F3` | `#2B3133` |
| `inversePrimary` | `#4FD8EB` | `#006874` |
| `scrim` | `#000000` | `#000000` |

### 2.4 Semantic Colors (Extended)

Not standard M3 tokens. Provided via `CompositionLocal` or direct reference.

#### Success

| Token | Light | Dark |
|-------|-------|------|
| `success` | `#146C2E` | `#88D78A` |
| `onSuccess` | `#FFFFFF` | `#003912` |
| `successContainer` | `#A3F4A5` | `#00531E` |
| `onSuccessContainer` | `#002107` | `#A3F4A5` |

#### Warning

| Token | Light | Dark |
|-------|-------|------|
| `warning` | `#8D5000` | `#FFB776` |
| `onWarning` | `#FFFFFF` | `#4A2800` |
| `warningContainer` | `#FFDCC1` | `#6B3D00` |
| `onWarningContainer` | `#2D1600` | `#FFDCC1` |

### 2.5 Contextual Colors

Mode-independent unless noted.

| Token | Value | On-Color | Usage |
|-------|-------|----------|-------|
| `trackActive` | `#FF3B30` | `#FFFFFF` | Live recording pulse, active indicator |
| `trackHistory` | `#00829B` | `#FFFFFF` | Past track lines on map |
| `activityWalk` | `#00E5FF` | `#001F26` | Walking activity chip/badge |
| `activityRun` | `#FF9100` | `#2A1700` | Running activity chip/badge |
| `activityRide` | `#2979FF` | `#FFFFFF` | Cycling activity chip/badge |

### 2.6 Glass & Translucency Tokens

| Token | Light | Dark | Notes |
|-------|-------|------|-------|
| `glassTintAlpha` | 0.78 | 0.82 | Surface color overlay on blur |
| `glassBorderAlpha` | 0.30 | 0.20 | `outlineVariant` border opacity |
| `glassBorderWidth` | 1dp | 1dp | |
| `glassBlurRadius` | 20dp | 20dp | Haze blur amount |
| `glassNoise` | **none** | **none** | No noise texture. Final. |
| `topoContourAlpha` (card) | 0.05 | 0.07 | `onSurface` contour lines on cards |
| `topoContourAlpha` (empty bg) | 0.07 | 0.09 | Background empty state contours |
| `topoContourAlpha` (tinted) | 0.03 | 0.04 | Tinted decorative contours |

### 2.7 Dark Mode Specifics

Beyond color token swaps, dark mode applies these adjustments:

**Tonal elevation.** M3's `surfaceColorAtElevation()` auto-applies `surfaceTint` (`#4FD8EB`) as an overlay at higher elevations. No custom code needed — built into M3.

**Glass adjustments.** Backdrop blur is more dramatic in dark mode (bright content shows through). Compensate:
*   Tint alpha 0.78 → 0.82 (more opaque to prevent content bleed)
*   Border alpha 0.30 → 0.20 (dark surfaces self-define edges; heavy borders look harsh)

**Topographic contour lines.** Dark surfaces absorb detail — increase contour alpha:
*   Cards: 0.05 → 0.07
*   Empty backgrounds: 0.07 → 0.09
*   Tinted: 0.03 → 0.04

**Shadows.** Drop shadows are invisible on dark surfaces. Do NOT add artificial borders to compensate. Rely on:
1.  Tonal elevation (lighter surface = higher elevation)
2.  Existing glass border treatment
3.  Content contrast (text/icons on elevated surface are sufficient)

**Track active indicator.** Same `#FF3B30` in both modes — high-urgency, always pops. WCAG AA contrast met against both light surface (`#F8FDFF`, ratio 4.53:1) and dark surface (`#0E1415`, ratio 5.12:1).

**Map overlay UI.** When map uses dark basemap, metric cards use standard dark scheme. No special transparency — glass blur composites with the map layer.

**Reduced transparency fallback.** If user has "Reduce Transparency" accessibility setting, replace all glass tints with opaque `surfaceContainer` and remove blur. Borders remain.

## 3. Typography Scale
*   **Primary Typeface (Display, Headline, Title):** Outfit (Google Fonts)
*   **Secondary Typeface (Body, Label):** Inter (Google Fonts)
*   **Feature:** Tabular Figures (`tnum`) for all numeric data.

### Scale
*   **Display Large:** Outfit | Bold (700) | 64sp | LH: 72sp | LS: -0.25sp
*   **Display Medium:** Outfit | Bold (700) | 52sp | LH: 60sp | LS: -0.25sp
*   **Display Small:** Outfit | Bold (700) | 44sp | LH: 52sp | LS: 0sp
*   **Headline Large:** Outfit | SemiBold (600) | 36sp | LH: 44sp | LS: 0sp
*   **Headline Medium:** Outfit | SemiBold (600) | 32sp | LH: 40sp | LS: 0sp
*   **Headline Small:** Outfit | SemiBold (600) | 28sp | LH: 36sp | LS: 0sp
*   **Title Large:** Outfit | Medium (500) | 22sp | LH: 28sp | LS: 0sp
*   **Title Medium:** Outfit | Medium (500) | 18sp | LH: 24sp | LS: 0.15sp
*   **Title Small:** Outfit | Medium (500) | 14sp | LH: 20sp | LS: 0.1sp
*   **Body Large:** Inter | Regular (400) | 16sp | LH: 24sp | LS: 0.5sp
*   **Body Medium:** Inter | Regular (400) | 14sp | LH: 20sp | LS: 0.25sp
*   **Body Small:** Inter | Regular (400) | 12sp | LH: 16sp | LS: 0.4sp
*   **Label Large:** Inter | Medium (500) | 14sp | LH: 20sp | LS: 0.1sp
*   **Label Medium:** Inter | Medium (500) | 12sp | LH: 16sp | LS: 0.5sp
*   **Label Small:** Inter | Medium (500) | 11sp | LH: 16sp | LS: 0.5sp (All Caps for technical metadata)

## 4. Motion System
*   **SecureSnap:** StiffnessMedium (~1500), DampingRatioNoBouncy (1.0). Fast, definitive, stable. Used for privacy toggles, core navigation.
*   **TactileActive:** StiffnessMediumLow (~400), DampingRatio 0.65f. Energetic, responsive. Used for primary actions (Start tracking FAB).
*   **SpatialGlide:** StiffnessLow (~200), DampingRatio 0.8f. Smooth, sweeping. Used for bottom sheets, map overlays.
*   **Durations:** Micro (150ms), Short (250ms), Medium (400ms), Long (600ms).

## 5. Shape System
*   **Waypoint (FAB):** Asymmetrical. Top-left, top-right, bottom-left: 50% radius. Bottom-right: 10% radius.
*   **Momentum Pill (Buttons/Chips):** Elongated stadium. Leading edge: 50% radius. Trailing edge: 20% radius.
*   **Terrain Card (Cards):** Opposing corner symmetry. Top-left, bottom-right: 15% radius. Top-right, bottom-left: 4% radius.

## 6. Metrics Font
*   **Font:** `FontFamily.Monospace` (Roboto Mono on Android — zero APK cost, system-bundled).
*   **Scope:** Metric values ONLY — distance, speed, duration, elevation, step count numbers displayed as primary data.
*   **NOT for:** Labels, timestamps, list counts, body numerics. Those keep default Roboto with `tnum`.
*   **Rationale:** Precision-instrument feel for the dashboard "cockpit." Fixed-width digits prevent layout shift during live tracking.
*   **Compose:** `MetricText` uses `fontFamily = FontFamily.Monospace`. All other text uses `FontFamily.Default`.

## 7. Shape Scale (5-Level Diagonal)
Asymmetric 3:1 ratio (major corner : minor corner) at all levels. Matches TerrainCardShape DNA.

| Level | Role | Major (TL/BR) | Minor (TR/BL) | Use |
|-------|------|---------------|----------------|-----|
| L1 | `extraSmall` | 6dp | 2dp | Chips, badges, inline tags |
| L2 | `small` | 10dp | 3dp | Small cards, list items, toggles |
| L3 | `medium` | 14dp | 4dp | Standard cards, dialogs, sheets |
| L4 | `large` | 20dp | 6dp | Feature cards, expanded panels |
| L5 | `extraLarge` | 24dp | 8dp | Hero cards, full-width banners |

*   **Named shapes kept:** WaypointShape (FAB, percentage-based), MomentumPillShape (buttons, percentage-based) — these are identity shapes, not part of the scale.
*   **Compose mapping:** `AppShapes(extraSmall=L1, small=L2, medium=L3, large=L4, extraLarge=L5)` each as `RoundedCornerShape(topStart=major, topEnd=minor, bottomEnd=major, bottomStart=minor)`.

## 8. Navigation Labels
*   **Compact width (< 600dp):** Active destination label only. Inactive destinations show icon only.
*   **Medium/Expanded width (≥ 600dp):** Active label + adjacent (±1) destination labels visible.
*   **First-use hint:** On first app launch, all labels visible for 3 seconds, then animate to active-only. Stored in DataStore `nav_labels_hint_shown` boolean.

## 9. Section Headers & Dividers

### Section Header
*   **Accent bar:** 3dp wide × 20dp tall vertical bar, `colorScheme.primary`, rounded ends (1.5dp radius). Left-aligned, vertically centered with text.
*   **Typography:** `titleMedium` (18sp, Medium, 24sp line height, 0.15sp letter spacing).
*   **Text color:** `colorScheme.onSurface` — the accent bar carries the brand color; text stays neutral for hierarchy.
*   **Case:** Sentence case. Never ALL-CAPS (reserved for `labelSmall` metadata).
*   **Optional icon:** 20dp, `colorScheme.onSurfaceVariant`, placed between accent bar and text. 8dp gap to bar, 8dp gap to text.
*   **Layout:** `Row(verticalAlignment = CenterVertically)` → accent bar → 12dp spacer → optional icon → 8dp spacer → text.
*   **Spacing:** 24dp above (section break), 12dp below (tight coupling to content). First header on screen: 16dp top instead of 24dp.
*   **Start padding:** 16dp from screen edge (accent bar starts at 16dp).

```kotlin
// Compose spec
@Composable
fun RidgelineSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier.padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accent bar
        Box(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(1.5.dp),
                )
        )
        Spacer(Modifier.width(12.dp))
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

### Trail-Line Divider
*   **Style:** Straight `HorizontalDivider`. No S-curves — they add visual noise, require custom Canvas, and don't scale across screen widths.
*   **Thickness:** 1dp.
*   **Color:** `colorScheme.outlineVariant` at 0.38 alpha (M3 standard disabled/subtle alpha).
*   **Padding:** Start 52dp (clears accent bar zone: 16dp screen + 3dp bar + 12dp gap + ~21dp icon/text zone), end 16dp. This asymmetric bleed mirrors the diagonal shape language.
*   **Spacing:** 16dp above the divider. No bottom spacing (the next section header's 24dp top margin handles it).
*   **Between-items divider (within a section):** Start 16dp, end 16dp, 0.24 alpha — lighter than section dividers.

```kotlin
// Section divider (between sections)
HorizontalDivider(
    modifier = Modifier.padding(start = 52.dp, end = 16.dp),
    thickness = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
)

// Item divider (within a section)
HorizontalDivider(
    modifier = Modifier.padding(horizontal = 16.dp),
    thickness = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
)
```

## 10. Empty States (3-Tier System)

### Tier 1: Inline Empty
*   **Trigger:** Empty list or section within a screen that has OTHER content present (e.g., "No achievements" in game screen with points still showing).
*   **Visual:** No card. Centered column: icon (48dp, `primary` at 0.3 alpha) + text (`bodyMedium`, `onSurfaceVariant`).
*   **Height:** Max 80dp. Compact, doesn't dominate.
*   **Copy tone:** Functional, brief. "No sessions this week." / "No achievements yet."
*   **Action:** None. Context makes the next step obvious.

```kotlin
@Composable
fun InlineEmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

### Tier 2: Section Empty
*   **Trigger:** Primary content area is empty but screen shell is intact (e.g., statistics list empty, export history empty).
*   **Visual:** `GlassCard` wrapper. Icon (64dp, `primary` at 0.4 alpha) + title (`titleMedium`, `onSurface`) + subtitle (`bodyMedium`, `onSurfaceVariant`) + optional `TextButton` action.
*   **Background:** Static topo-contour lines at 0.04 alpha for texture. Two cubic Bézier paths, 2dp stroke, `onSurface` color.
*   **Copy tone:** Functional with trail flavor. "No trails recorded yet" not "No sessions found."
*   **Action:** `TextButton` (not filled). Label: verb-first. "Record a trail" / "Import data."
*   **Maps to:** Existing `sutils/EmptyStateCard` component.

### Tier 3: Full-Screen Empty (Onboarding)
*   **Trigger:** First-time use OR app-level zero-data state (dashboard completely empty).
*   **Visual:** Full-width card, `surfaceContainerHigh` background. Animated floating icon (72dp, `primaryContainer` circle, 2s ease float cycle, 5% scale pulse at 3s). Animated background trail lines (two cubic paths drifting at 8s linear loop, 0.06 alpha). Title (`headlineSmall`, Bold) + subtitle + feature highlights grid (2×2 chips) + directional hint.
*   **Copy tone:** Thematic, inviting, first-person. "Your trail begins here" / "Start exploring."
*   **Action:** `PrimaryActionButton` (filled, `MomentumPillShape`). Never a text button — this is the primary conversion moment.
*   **Maps to:** Existing `dashboard/EmptyStateCard` component.
*   **Reduced motion:** When `LocalReducedMotion` is true, disable float/pulse/path animations. Show static icon and background.

### Tier Selection Rule
| Condition | Tier | Example |
|-----------|------|---------|
| Section within populated screen is empty | Tier 1 | Game screen, no achievements yet |
| Screen's primary list/content is empty | Tier 2 | Statistics with zero trips |
| Entire app has zero data (first launch) | Tier 3 | Dashboard, never tracked |

## 11. Component Specifications
*   **Map Screen:** Waypoint FAB (Secure Teal, TactileActive). Terrain Card for metrics overlay (Roboto Mono metric values).
*   **Statistics Screen:** Terrain Cards for trips. Momentum Pill for activity chips. Tinted surfaces. Section headers with accent bar.
*   **Trip Detail Screen:** Terrain Cards for data segments. Sunset Rust for peak metrics. Roboto Mono for distance/duration.
*   **Settings/Privacy Screen:** Momentum Pill for toggles. SecureSnap motion. Section headers for groups, item dividers within.

### Floating Navigation Bar (Final)
*   **Height:** 80dp. M3 standard. Provides breathing room for icon pill + label.
*   **Corner radius:** 32dp (full `RoundedCornerShape`). Soft capsule, "floating island" feel.
*   **Background:** Haze blur (20dp) + surface color tint. Fallback: `GlassCard`.
*   **Tint alpha:** 0.78 light / 0.82 dark. No noise. No parallax.
*   **Border:** 1dp `outlineVariant` at 0.30α light / 0.20α dark.
*   **Active indicator pill:** 56×32dp, 16dp radius, `secondaryContainer`.
*   **Icons:** 24dp. Outlined inactive (`onSurfaceVariant`), filled active (`onSecondaryContainer`).
*   **Labels:** Selected-only, `labelSmall`. 150ms fade + 4dp translate-up on selection. Inactive: icon only.
*   **Press:** 0.96 scale spring (SecureSnap). Long-press: tooltip + haptic.
*   **Horizontal padding:** 24dp from screen edge. Vertical: 24dp from bottom.
*   **Clearance:** `AppDimensions.FloatingNavBarClearance = 120.dp` for content scroll padding.
