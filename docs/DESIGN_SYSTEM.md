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

Mode-adaptive. Light / Dark values listed.

| Token | Light | Dark | On-Light | On-Dark | Usage |
|-------|-------|------|----------|---------|-------|
| `trackActive` | `#FF3B30` | `#FF3B30` | `#FFFFFF` | `#FFFFFF` | Live recording pulse, active indicator |
| `trackHistory` | `#00829B` | `#00829B` | `#FFFFFF` | `#FFFFFF` | Past track lines on map |
| `activityWalk` | `#007051` | `#52C5A6` | `#FFFFFF` | `#002418` | Walking activity chip/badge (Okabe-Ito H=164°) |
| `activityRun` | `#A34800` | `#EF8C3D` | `#FFFFFF` | `#2E1500` | Running activity chip/badge (Okabe-Ito H=26°) |
| `activityRide` | `#00659E` | `#5AADDC` | `#FFFFFF` | `#001D2E` | Cycling activity chip/badge (Okabe-Ito H=202°) |
| `activityVehicle` | `#97396D` | `#D490B6` | `#FFFFFF` | `#2A0A1E` | Vehicle activity chip/badge (Okabe-Ito H=327°) |
| `activityStill` | `#546E7A` | `#90A4AE` | `#FFFFFF` | `#0C1F28` | Stationary activity indicator |
| `activityUnknown` | `#616161` | `#9E9E9E` | `#FFFFFF` | `#1A1A1A` | Unknown activity fallback |

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
*   **Secondary Typeface (Body, Label):** System font (Roboto / Noto Sans — zero APK cost)
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

## 7. Spacing System

### 7.1 Scale (4dp Base)

| Token | Value | Primary Use |
|-------|-------|-------------|
| `SpaceNone` | 0dp | Explicit zero-spacing |
| `SpaceXxs` | 2dp | Divider margins, icon-to-badge offset |
| `SpaceXs` | 4dp | Chip internals, badge padding, inline tags |
| `SpaceSm` | 8dp | Icon-to-text in row, list `spacedBy`, within-card gaps |
| `SpaceMd` | 12dp | Section header icon gaps, card internal column spacing |
| `SpaceLg` | 16dp | **Page gutter**, card padding, between-item divider padding |
| `SpaceXl` | 20dp | Hero card padding, section vertical grouping |
| `SpaceXxl` | 24dp | Section break (above headers), button horizontal padding |
| `SpaceXxxl` | 32dp | Major section gaps, dialog padding |
| `SpaceXxxxl` | 48dp | Touch target minimum, between major screen regions |

### 7.2 Compose Constants

```kotlin
object RidgelineSpacing {
    val None   =  0.dp
    val Xxs    =  2.dp
    val Xs     =  4.dp
    val Sm     =  8.dp
    val Md     = 12.dp
    val Lg     = 16.dp
    val Xl     = 20.dp
    val Xxl    = 24.dp
    val Xxxl   = 32.dp
    val Xxxxl  = 48.dp
}
```

### 7.3 Page Gutters

*   **Responsive horizontal gutter:** 16dp compact (<600dp), 24dp medium (600–839dp), 32dp expanded (≥840dp). Resolved via `RidgelineGutters.horizontal`.
*   **Full-bleed exceptions:** Map, bottom sheet container, TopAppBar background, floating nav bar.
*   **Bottom clearance:** `AppDimensions.FloatingNavBarClearance = 120.dp` for floating nav bar.
*   **Scaffold pattern:** `contentWindowInsets = WindowInsets.safeDrawing` handles system bar insets.

```kotlin
object RidgelineGutters {
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
```

### 7.4 Semantic Pairing Rules

| Relationship | Token |
|-------------|-------|
| Between siblings in tight group | `Xs` (4dp) |
| Between elements in a component | `Sm` (8dp) |
| Between sub-sections in component | `Md` (12dp) |
| Component internal padding | `Lg` (16dp) |
| Component internal padding (hero) | `Xl` (20dp) |
| Between sections / above headers | `Xxl` (24dp) |
| Between major screen regions | `Xxxl` (32dp) |
| Minimum touch target | `Xxxxl` (48dp) |

## 8. Shape Scale (5-Level Diagonal)
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

## 8b. Navigation Labels
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

## 11. Dynamic Color (Monet) Policy

*   **Android 12+ (S):** Dynamic color **ON by default** via `useDynamicColor = true`.
*   **Scope:** Monet overrides **surface/neutral tokens only** — surface, surfaceDim, surfaceBright, all surfaceContainer levels, surfaceVariant, surfaceTint, background, onBackground, onSurface, onSurfaceVariant, outline, outlineVariant, inverseSurface, inverseOnSurface, scrim.
*   **Brand-locked (never Monet'd):** All primary, secondary, tertiary, error, and extended semantic color tokens (success, warning, activity colors). These remain Ridgeline palette regardless of wallpaper.
*   **User toggle:** "Use wallpaper colors" in appearance settings, defaults to ON on Android 12+.
*   **Pre-Android 12:** Static Ridgeline palette only. Toggle not shown.

## 12. Accessibility Degradation Tiers

| User Setting | Topo Contours | Glass Treatment | Transitions |
|---|---|---|---|
| **Default** | Animated (8s drift loop) | Blur + tint + border | Spring-animated |
| **Reduce animations** | Hidden entirely | Blur + tint + border (static) | Instant snap (0ms) |
| **Reduce transparency** | Hidden | Solid `surfaceContainer` + border | Instant snap |
| **Both** | Hidden | Solid + border | Instant snap |

*   Glass blur is a static material property, NOT an animation. It remains under reduce-animations.
*   Android's separate "Reduce Transparency" setting triggers the solid fallback.
*   Both settings are checked independently via `LocalReducedMotion` and `LocalReduceTransparency`.

## 13. Card Layout Specifications

Four canonical card types. All use the Ridgeline asymmetric shape scale.

### 13.1 Trip Card
*   **Role:** Session history list item. Tappable.
*   **Shape:** L2 (small: TL/BR 10dp, TR/BL 3dp).
*   **Container:** `surfaceContainerLow`.
*   **Padding:** 16dp all sides.
*   **Layout:** `Row` → 40dp circle icon container (`primaryContainer`) → 12dp gap → `Column(title + subtitle)` weight(1f) → `Column(distance + duration)` end-aligned.
*   **Title:** `titleSmall` / `onSurface`.
*   **Subtitle:** `bodySmall` / `onSurfaceVariant`.
*   **Distance:** `labelMedium` SemiBold / `primary`, `tnum`.
*   **Duration:** `labelSmall` / `onSurfaceVariant`, `tnum`.
*   **Icon:** 24dp inside 40dp circle, `onPrimaryContainer` tint.
*   **List spacing:** `spacedBy(8.dp)`.

### 13.2 Stats Summary Card (Today Progress)
*   **Role:** Hero card for aggregated daily stats. Non-tappable.
*   **Shape:** L4 (large: TL/BR 20dp, TR/BL 6dp).
*   **Container:** `surfaceContainer`.
*   **Padding:** 20dp all sides.
*   **Layout:** `Row` → `Column(label + primary metric + secondary metrics row)` weight(1f) → optional goal rings 16dp start padding.
*   **Section label:** `titleMedium` / `onSurfaceVariant`.
*   **Primary metric:** `displaySmall` Bold / `onSurface`, `tnum`.
*   **Secondary labels:** `labelMedium` / `onSurfaceVariant` 0.9α.
*   **Secondary values:** `bodyMedium` SemiBold / `onSurface`, `tnum`.
*   **Metric column spacing:** 16dp horizontal.

### 13.3 Challenge Card
*   **Role:** Compact card in horizontal carousel. Tappable.
*   **Fixed size:** 160dp × 120dp.
*   **Shape:** L3 (medium: TL/BR 14dp, TR/BL 4dp).
*   **Container:** `surfaceContainer`.
*   **Padding:** 12dp all sides.
*   **Layout:** `Column` → difficulty badge → 4dp gap → title (max 2 lines) → flex spacer → progress arc row.
*   **Difficulty:** `labelSmall` Bold, color by tier (easy=`tertiary`, medium=`secondary`, hard=`error`).
*   **Title:** `bodyMedium` Medium / `onSurface`, maxLines=2.
*   **Progress arc:** 32dp canvas, 180° sweep, 3dp stroke, track=`surfaceVariant` 0.5α, fill=`primary`.
*   **Time remaining:** `labelSmall` / `onSurfaceVariant`, `tnum`.
*   **Carousel:** `LazyRow`, `spacedBy(12.dp)`, `contentPadding(horizontal = 16.dp)`.

### 13.4 Dashboard Quick-Stat Card (Glass Metric)
*   **Role:** Compact metric display with glass treatment.
*   **Shape:** L2 (small: TL/BR 10dp, TR/BL 3dp).
*   **Surface:** `surfaceColorAtElevation(2.dp)` at 0.85α.
*   **Border:** 1dp `onSurface` at 0.08α.
*   **Padding:** 16dp horizontal, 12dp vertical.
*   **Layout:** `Row` → 24dp icon (`primary`) → 12dp gap → `Column(label + value+unit)` weight(1f) → optional 20dp trend icon.
*   **Label:** `labelSmall` / `onSurfaceVariant`.
*   **Value:** `titleMedium` SemiBold / `onSurface`, `tnum`.
*   **Unit:** `labelSmall` / `onSurfaceVariant`, 4dp left of value.
*   **Trend:** `tertiary` (positive) / `error` (negative).
*   **Min height:** 48dp. **List spacing:** `spacedBy(8.dp)`.

### Card Type Selection Guide
| Scenario | Card Type | Shape Level |
|---|---|---|
| Session/trip in a list | Trip Card | L2 (small) |
| Hero aggregate stat (today, weekly) | Stats Summary | L4 (large) |
| Active challenge in carousel | Challenge Card | L3 (medium) |
| Single metric readout | Quick-Stat Card | L2 (small) |
| Feature card / hero promo | Custom | L5 (extraLarge) |
| Settings group | SettingsGroupCard | L3 (medium) |

## 14. Button Hierarchy

### 14.1 Five Variants (Priority Order)
| Priority | Variant | Shape | Container | Content | Use Case |
|---|---|---|---|---|---|
| 1 (Highest) | `PrimaryActionButton` | `MomentumPillShape` | `primary` | `onPrimary` | Primary CTA. One per screen max. |
| 2 | `FilledTonalButton` | `MomentumPillShape` | `secondaryContainer` | `onSecondaryContainer` | Important secondary. "View details" |
| 3 | `OutlinedButton` | `MomentumPillShape` | transparent | `primary`, 1dp `outline` | Alternative. "Share", "Export" |
| 4 | `TextButton` | default M3 | transparent | `primary` | Dialog dismiss, "Cancel", inline |
| 5 | `IconButton` | Circle | transparent | `onSurfaceVariant` | Toolbar, overflow. 48dp target |

*   Maximum **one** `PrimaryActionButton` per screen (excluding FAB).
*   All buttons: minimum 48dp touch target height.
*   `PrimaryActionButton` padding: 24dp horizontal, 12dp vertical.

### 14.2 FAB Treatment
| Variant | Size | Shape | Use Case |
|---|---|---|---|
| Standard FAB | 56dp | `WaypointShape` | Primary floating action (map waypoint, new trip) |
| Large FAB | 96dp | `WaypointShape` | Hero action (tracking start/stop) |
| Extended FAB | 56dp height, wrap | `MomentumPillShape` | FAB with label ("Start recording") |

*   **No mini FAB.** 40dp fails 48dp touch target minimum.
*   FAB placement: 24dp from trailing edge, 24dp above floating nav bar top.
*   Extended FAB collapses to icon-only on scroll via `expanded = !scrolled`.
*   FAB not shown on settings, import/export, or detail screens.

## 15. Per-Screen Component Specifications
*   **Map Screen:** Standard FAB (`WaypointShape`, Secure Teal, TactileActive). Quick-Stat Cards for metrics overlay.
*   **Dashboard:** Large FAB (96dp) for tracking. Stats Summary Card at top. Challenge Cards in carousel. Quick-Stat Cards for secondary metrics.
*   **Statistics Screen:** Trip Cards in list. Section headers with accent bar. Stats Summary Card for period totals.
*   **Trip Detail Screen:** Stats Summary Card for trip aggregate. Quick-Stat Cards for individual metrics. Sunset Rust for peak values.
*   **Game Screen:** Challenge Cards in carousel. Stats Summary Cards for lifetime stats. Section headers per category.
*   **Settings/Privacy Screen:** `PrimaryActionButton` for saves. `OutlinedButton` for exports. Section headers for groups, item dividers within.

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

## 16. Elevation Strategy

Four functional levels. Tonal-first — shadow supplements but never leads.

| Level | Tonal | Shadow | Name | Components |
|-------|-------|--------|------|------------|
| E0 | 0dp | 0dp | Ground | Screen background, full-bleed containers |
| E1 | 1dp | 1dp | Resting | GlassCard, Trip Card, Quick-Stat Card, list items |
| E2 | 2dp | 2dp | Lifted | Bottom sheet (peek), expanded panels, settings groups |
| E3 | 6dp | 6dp | Floating | FAB, floating nav bar, snackbar, active drag item |

*   **Tonal-first:** M3 `surfaceColorAtElevation()` applies `surfaceTint` overlay. Works in both modes.
*   **Glass border:** 1dp `outlineVariant` border provides edge definition in dark mode where shadows vanish.
*   **Shadow as supplement:** Match `shadowElevation` to `tonalElevation`. Dark mode users see tonal + border only.
*   **Rule:** `tonalElevation >= shadowElevation` always. Never shadow-only.
*   **State:** Pressed → E0 (sinks). Dragged → E3 (lifts). Active tracking → E2 (prominence). Disabled → E0.

## 17. Edge-to-Edge & System Bars

*   **All activities:** `enableEdgeToEdge()` in `onCreate`, before `setContent`.
*   **Status bar:** Transparent. TopAppBar extends behind it.
*   **Navigation bar (gesture):** Transparent. Content scrolls behind.
*   **Navigation bar (3-button):** Transparent + system auto-contrast.
*   **Scaffold:** `contentWindowInsets = WindowInsets.safeDrawing` handles all insets.
*   **Map screen exception:** No Scaffold — metric overlays use `windowInsetsPadding(WindowInsets.safeDrawing)` directly.
*   **Never** hardcode status bar height, use `fitSystemWindows`, or `WindowCompat.setDecorFitsSystemWindows`.

## 18. Large Screen Policy

**Phone-first. No adaptive layouts. No multi-pane. No WindowSizeClass.**

*   Usage context is one-handed while moving. Tablet is secondary.
*   Content fills width naturally — cards stretch, text reflows.
*   Map benefits most from larger screens (full bleed).
*   **Deferred:** If tablet usage grows, first candidate is statistics list-detail split.

## 19. Accessibility Text Scaling (200%)

*   **No `maxFontSize`.** Users who set 200% need 200%.
*   **Everything scrolls.** All screens use `LazyColumn`/`verticalScroll`. Dialogs add `verticalScroll` if content exceeds ~300dp.
*   **Metric hero stepping:** `displayLarge` (64sp) → `displaySmall` (44sp) → `headlineLarge` (36sp) floor. Uses `onTextLayout` overflow detection.
*   **Buttons stretch:** `wrapContentWidth()` — buttons grow with text.
*   **Nav bar labels:** Hide at large font scales, icon-only with tooltip fallback.
*   **Touch targets:** ≥ 48dp regardless of font scale.
*   **Test at:** 100%, 150%, 200%.

## 20. Top App Bar Specs

*   **Variant:** `TopAppBar` (small) for most screens. `LargeTopAppBar` for Statistics list only. `CenterAlignedTopAppBar` for onboarding only. No `MediumTopAppBar`.
*   **Scroll behaviors:** Pinned (Dashboard, Settings, Import/Export, Onboarding), `exitUntilCollapsedScrollBehavior` (Statistics — LargeTopAppBar, Trip Detail), `enterAlwaysScrollBehavior` (Game).
*   **Colors:** `surface` resting, `surfaceContainerLow` scrolled. Title: `onSurface`. Actions: `onSurfaceVariant`.
*   **Title style:** `titleLarge`. Single line with ellipsis.
*   **Back navigation:** `Icons.AutoMirrored.Filled.ArrowBack` on all sub-screens. No hamburger menu.

## 21. Bottom Sheet Specs

*   **`BottomSheetScaffold`:** Map screen only (persistent, peek/half/full states).
*   **`ModalBottomSheet`:** All other on-demand sheets (trip actions, filters, export options).
*   **Shape:** Asymmetric top corners — `topStart = 20.dp, topEnd = 6.dp` (terrain DNA, 3:1 ratio).
*   **Peek height:** 72dp (drag handle + first content row).
*   **Container:** `surfaceContainerLow`, tonal elevation E2 (2dp).
*   **Scrim:** `scrim` at 0.32 alpha (lighter than M3 default to keep map context).
*   **Content patterns:** Action list (ListItem rows), Form content, Info display.
*   **Rules:** Max 90% screen height, no nested sheets, keyboard avoidance via `contentWindowInsets`.

## 22. List Item Specs

*   **Use `ListItem`** for all vertically-stacked tappable rows. Custom `Row` for card content.
*   **Container:** `Color.Transparent` default, `secondaryContainer` when selected.
*   **Typography:** `bodyLarge` headline, `bodyMedium` supporting, `onSurfaceVariant` for secondary elements.
*   **Spacing:** `spacedBy(0.dp)` with dividers, or `spacedBy(2.dp)` without dividers.
*   **Dividers:** Full-width 0.24α within sections, 52dp indent 0.38α between sections.

## 23. Progress Indicator Specs

*   **Linear:** File progress, tracking bar below TopAppBar. Round `StrokeCap`. Heights: 4dp (subtle), 8dp (standard), 12dp (hero).
*   **Circular:** Loading states (48dp, 4dp stroke), goal rings (64dp, 6dp stroke).
*   **Trail progress bar:** Standard `LinearProgressIndicator`, 8dp default, round caps. No custom Canvas.
*   **Determinate** when total known (export, goals). **Indeterminate** when unknown (GPS acquisition, data load).
*   **Color tokens:** `primary` default, `success` at 100%+, activity colors per type, `error` for blocked.

## 24. Switch & Toggle Specs

*   **Switch:** Immediate-effect binary toggles (settings). M3 Switch with `Icons.Filled.Check` (16dp) when on, no icon when off.
*   **Checkbox:** Batch selection requiring confirm (multi-select export, filter).
*   **Colors:** M3 defaults (`primary`/`outline`/`surfaceContainerHighest`). No custom track colors except reserved `error` for destructive toggles.
*   **In ListItem:** Entire row clickable. `Role.Switch` semantics. `stateDescription` "On"/"Off".

## 25. Text Field Specs

*   **`OutlinedTextField` exclusively.** No filled variant.
*   **Shape:** `MaterialTheme.shapes.small` (L2, 8dp). Search fields use `shapes.extraLarge` (pill).
*   **Always provide a label.** Placeholder alone is insufficient.
*   **Helper text:** Below field, 4dp gap. Error text replaces helper (never both).
*   **Character counter:** `"23/50"` format, end-aligned, shown only when `maxLength` set.
*   **Error state:** `error` border (2dp), `error` label, error icon trailing.
*   **Search variant:** Leading search icon, trailing clear button, full-round shape.

## 26. Menu Specs

*   **DropdownMenu:** Overflow actions. Shape L2, `surfaceContainer`, E2 elevation. Max 7 items; beyond that use ModalBottomSheet.
*   **ExposedDropdownMenu:** Selection fields (activity type, unit system). Read-only `OutlinedTextField` anchor.
*   **Item height:** 48dp minimum. Text: `bodyLarge`. Icons: 24dp, `onSurfaceVariant`.
*   **Ordering:** Primary → secondary → divider → destructive (last).
*   **Rules:** No nested menus, dismiss on action, if one item has icon then all do.

## 27. Empty States — Per-Screen Content

### 27.1 Tier Selection (Recap from §10)

| Condition | Tier | Component |
|-----------|------|-----------|
| Section within populated screen is empty | **Tier 1: Inline** | `InlineEmptyState` (icon + text, max 80dp) |
| Screen's primary content is empty | **Tier 2: Section** | `EmptyStateCard` (GlassCard, icon + title + subtitle + optional action) |
| Entire app has zero data (first launch) | **Tier 3: Full-Screen** | Dashboard `EmptyStateCard` (animated, hero CTA) |

### 27.2 Per-Screen Content

| Screen | Tier | Icon | Title / Message | CTA |
|--------|------|------|----------------|-----|
| Dashboard (first launch) | 3 | `Explore` | "Your trail begins here" / "Track your walks, runs, and rides. All data stays on your device." | "Start exploring" (PrimaryActionButton) |
| Statistics (no trips) | 2 | `Timeline` | "No trails recorded yet" / "Your sessions will appear here once you start tracking." | "Record a trail" (TextButton) |
| Statistics (search empty) | 1 | `SearchOff` | "No sessions match your search." | None |
| Game (no challenges) | 2 | `EmojiEvents` | "No challenges yet" / "Complete your first few sessions to unlock challenges." | "Start tracking" (TextButton) |
| Game (no achievements) | 1 | `MilitaryTech` | "No achievements yet." | None |
| Import/Export (no exports) | 2 | `FolderOpen` | "No exports yet" / "Export your data as GPX, KML, JSON, or a full database backup." | "Create export plan" (TextButton) |
| Map (no data) | 1 | `Map` | "No track data to display." | None |
| Trip detail (no location) | 1 | `Route` | "No location data for this session." | None |

### 27.3 Copy Rules

*   Trail vocabulary: "trails" not "sessions." "Recorded" not "captured."
*   Privacy reassurance only on first-launch Tier 3.
*   Verb-first CTAs: "Record a trail" not "Go to recording."
*   No blame: "No trails recorded yet" not "You haven't recorded any trails."

## 28. Permission Denied States

### 28.1 Flow

3-step graceful degradation: system dialog → rationale banner → settings deep-link banner.

### 28.2 Rationale Banner

*   **Component:** `PermissionRationaleBanner` — inline, non-blocking, `secondaryContainer` background, M3 L3 shape.
*   **Placement:** Top of relevant screen, below TopAppBar, inside content scroll area.
*   **Actions:** Primary filled button ("Allow") + secondary text button ("Skip").

### 28.3 Per-Permission Content

| Permission | Rationale Title | Rationale Description | Settings Title |
|-----------|----------------|----------------------|---------------|
| Location | "Location access needed" | "To record your trails and show them on the map, Tracker needs access to your location. No data leaves your device." | "Location access disabled" |
| Background Location | "Background location access" | "To track automatically when you're on the move, allow location access all the time." | "Background location disabled" |
| Activity Recognition | "Activity detection" | "Tracker can detect whether you're walking, running, or cycling to automatically categorize your sessions." | "Activity detection disabled" |
| Notifications | "Stay informed" | "Notifications let you see tracking status and know when exports complete." | "Notifications disabled" |

### 28.4 Degraded States

| Missing Permission | Behavior | Visual |
|-------------------|----------|--------|
| Location | Steps + activity only. Map empty. | `InlineEmptyState` on map. |
| Background Location | Manual start/stop only. | Settings toggle label: "Requires background location." |
| Activity Recognition | All sessions tagged "Unknown." | `activityUnknown` color on chips. |
| Notifications | Silent tracking/export. | Settings info row note. |

## 29. First-Session & Milestone Celebrations

*   **First session:** Celebratory Snackbar ("First trail recorded! 🎉") with "View" action.
*   **Level up:** `UnlockAnnouncementBanner` (existing component, slide-in, auto-dismiss 4s).
*   **Achievement unlock:** `AchievementCard` updates in Game screen.
*   **Session milestones (10/50/100):** Snackbar ("50 trails and counting!").
*   **First export:** Snackbar ("Export complete — your data, your way.").
*   **No modal celebrations, no confetti.** The app celebrates by being useful.

## 30. Data Export Flow

### 30.1 Manual Quick Export

*   **Trigger:** Trip detail overflow → "Export" / Statistics → "Export all" / Import/Export → "Export now."
*   **Component:** `ModalBottomSheet` (Pattern B: Form Content).
*   **Fields:** Format (`ExposedDropdownMenu`: GPX/KML/JSON/Full backup), Scope (This session/Last 7 days/Last 30 days/All data), Share checkbox.
*   **CTA:** "Export" (`PrimaryActionButton`).
*   **Progress:** Inline `LinearProgressIndicator` below TopAppBar after sheet dismisses. Determinate for GPX/KML/JSON, indeterminate for DATABASE.
*   **Completion:** Snackbar "Export complete" with "Share" action. If share checkbox was checked, Android share intent fires immediately.

### 30.2 Automated Export Plans

*   **Location:** Import/Export screen, full-screen CRUD.
*   **List:** `ListItem` rows — plan name headline, cadence+scope supporting text, `RidgelineSwitch` trailing.
*   **Create/Edit:** Full-screen form. Format, cadence, scope, destination (SAF folder picker), filename prefix.

## 31. Settings Danger Zone — Delete All Data

### 31.1 Location

"Data management" section, below all other settings. Extra `Xxxl` (32dp) spacing above.

### 31.2 Confirmation Flow (3-Step)

1.  **Warning dialog:** Lists what will be deleted. "Cancel" / "Continue" (error-colored TextButton).
2.  **Type-to-confirm dialog:** `OutlinedTextField` with error border. Must type "DELETE" (case-sensitive). "Cancel" / "Delete all" (disabled until match, error-colored).
3.  **Execution:** Full-screen loading overlay → database clear → DataStore reset → navigate to onboarding. Snackbar: "All data deleted."

*   **Why type-to-confirm over timer delay:** Requires active cognitive engagement. Timer punishes fast readers and annoys everyone.

## 32. Tracking State Visualizations

### 32.1 States

| State | FAB | TopBar Title | Recording Dot | Progress Bar | GPS Chip |
|-------|-----|-------------|---------------|-------------|----------|
| Idle | `primary`, play icon | "Dashboard" | Hidden | Hidden | — |
| Tracking Active | `trackActive`, stop icon, pulse | "Tracking" | `trackActive`, 1.5s pulse | Indeterminate, `primary` | Hidden |
| GPS Searching | `trackActive`, stop icon | "Tracking" | `trackActive` | Indeterminate | "Acquiring GPS…" (`warning`) |
| GPS Weak | `trackActive`, stop icon | "Tracking" | `trackActive` | Indeterminate | "Weak GPS signal" (`warning`) |
| Passive Mode | `secondaryContainer`, stop icon | "Tracking" | `secondary` | Indeterminate | Hidden; "Passive mode" policy chip |
| Battery Saver | Same as active | "Tracking" | Same as active | Same as active | Dismissible banner (once per session) |

### 32.2 GpsState Enum

```kotlin
enum class GpsState { OFF, SEARCHING, WEAK_SIGNAL, GOOD }
```

*   `SEARCHING` → GPS requested, no fix yet (cold start 15–45s).
*   `WEAK_SIGNAL` → Fix obtained, accuracy > 50m.
*   `GOOD` → Accuracy ≤ 50m. Normal operation.

### 32.3 GPS Status Chip

`AssistChip` with `warning` color. Hidden when `GOOD` or `OFF`. Placed inline below hero metrics on dashboard.

## 33. Connectivity & Sensor States

*   **GPS cold start:** Handled by `GpsState.SEARCHING`. Tracking begins immediately (steps/activity count); GPS data fills in when available.
*   **Airplane mode:** No special UI. GPS is passive receiver, works in airplane mode. A-GPS disabled → longer TTFF.
*   **Bluetooth sensors:** Not in v1. Future: Settings "Connected sensors" section.
*   **No-network states:** No UI needed. App is local-only.

## 34. Split-Screen & PiP Policy

*   **Split-screen:** Supported via standard Compose responsiveness. `RidgelineGutters.horizontal` adapts to reduced width. No special detection code.
*   **PiP:** Not supported in v1. No `supportsPictureInPicture` manifest entry. Future candidate: map + recording dot + elapsed time.

## 35. RTL Layout

*   Full RTL support via Compose logical directions (`start`/`end`, not `left`/`right`).
*   Asymmetric shapes auto-mirror — `topStart`/`topEnd`/`bottomStart`/`bottomEnd` are logical.
*   `Icons.AutoMirrored.*` for directional icons. Non-directional icons do NOT mirror.
*   Map and charts/sparklines do NOT mirror — geographic and time-series content is universal.
*   Number formatting: `Locale`-aware. Arabic-Indic numerals when locale requires.
*   **Test mandate:** Preview every screen with `LayoutDirection.Rtl` before release.

## 36. Long Text Truncation Rules

| Element | Max Lines | Overflow | Notes |
|---------|-----------|----------|-------|
| Trip name (list) | 1 | Ellipsis | — |
| Trip name (detail) | 2 | Ellipsis | — |
| Challenge name (card) | 2 | Ellipsis | — |
| Challenge name (detail) | 3 | Ellipsis | — |
| Section header | 1 | Ellipsis | Keep headers concise |
| Metric value | 1 | Scale down | Step-down per §19 |
| Metric label | 1 | Ellipsis | — |
| Export filename | 1 | Middle-ellipsis | "tracker_20…240601.gpx" |
| Snackbar message | 2 | Ellipsis | ≤ 80 chars by convention |

*   User-generated text (trip names, plan names): 100-char input limit at `OutlinedTextField`.

## 37. Battery Optimization Visual Treatments

| Trigger | Visual | Duration |
|---------|--------|----------|
| Battery Saver (system) | Dismissible `AssistChip`: "Battery saver — less frequent updates", `warning` color | Once per session |
| Doze mode | No UI. Session resumes on wake | Automatic |
| Low-power mode (future) | `PolicyTierChip` variant, `secondaryContainer` | While active |

*   **Metric staleness:** When GPS fix >30s old, metric values tint `onSurfaceVariant` + trailing "(12s ago)" `labelSmall`. Instant swap, no animation.
*   **Map track lines:** Low-frequency points show segmented lines. No interpolation. Tooltip on sparse segments.

## 38. Animation Choreography

### 38.1 Staggered Card Entrance

*   **Stagger interval:** `MotionTokens.STAGGER_MS` (80ms) per card.
*   **Per-card animation:** Fade 0→1 (200ms tween) + translate 24dp→0dp (`MotionTokens.Standard` spring).
*   **Max stagger depth:** 5 cards. Cards beyond the 5th appear with the 5th.
*   **Trigger:** `LaunchedEffect(Unit)` on first composition only.
*   **Reduced motion:** All cards appear instantly.

### 38.2 FAB Appearance / Disappearance

*   **Enter:** 200ms delay after screen paint, then scale 0→1 + fade 0→1, `MotionTokens.Dramatic` spring (damping 0.6, stiffness 200). ~500ms settle.
*   **Exit:** Scale 1→0.8 + fade 1→0, `tween(150ms)`. Fast exit.
*   **Tracking morph:** Icon crossfade 200ms. Color via `animateColorAsState(Responsive)`. Size via `animateDpAsState(Dramatic)`.
*   **Reduced motion:** Instant show/hide/morph.

### 38.3 Bottom Sheet Springs

*   **Expand/collapse:** Damping 0.85, stiffness 600f. Velocity-aware. Slightly underdamped.
*   **Dismiss:** Damping 1.0, stiffness 800f. Critically damped, no bounce.
*   **Scrim:** `tween(300ms)` synced to sheet position. Max alpha 0.32.
*   **Reduced motion:** Snap to target.

### 38.4 Goal Completion Ring

*   **Incremental:** `animateFloatAsState(MotionTokens.Responsive)` on sweep angle.
*   **Completion (100%):** Phase 1 (0–200ms): color `primary`→`success`. Phase 2 (200–600ms): stroke 6dp→8dp→6dp pulse (`Bouncy`). Phase 3 (600–800ms): check icon fade-in.
*   **Over-achievement (>100%):** Second arc in `tertiary` overlapping `success` base.
*   **Reduced motion:** Instant color + static check.

### 38.5 Screen Transitions

*   **Forward:** `fadeIn(300ms) + slideInHorizontally(+30dp)` / `fadeOut(150ms)`. 100ms overlap. `MotionTokens.Standard` spring for slide.
*   **Back (predictive):** System-driven scale 0.9 + 8dp shift + corner radius increase.
*   **→ Map:** Fade only, no horizontal slide (map is spatial).
*   **→ Trip Detail:** Vertical slide up from tapped card.
*   **Reduced motion:** Instant transition.

## 39. Developer Experience

### 39.1 Documentation Split

| Content | Location |
|---------|----------|
| Token values, scales, hex codes | DESIGN_SYSTEM.md |
| Design rationale | DESIGN_SYSTEM.md |
| Component API, params, defaults | KDoc on composable |
| Usage guidance ("when to use X") | KDoc on composable |
| Accessibility behavior | KDoc on composable |
| Round decisions, history | DESIGN_ROUND_*.md |

### 39.2 Drift Prevention

**Immediate (no new deps):**
*   Detekt `ForbiddenImport`: `android.widget.*`, `androidx.fragment.*`, `android.view.View`.
*   CI grep checks: reject `Color(0x` outside `Color.kt`; reject `RoundedCornerShape(` outside `Shape.kt`.
*   PR checklist: "DS tokens used? No hardcoded colors/shapes/spacing?"

**Deferred (post-v1):**
*   Custom Compose lint module (`lint-rules/`) with `HardcodedColorDetector`, `HardcodedShapeDetector`.
*   Screenshot testing (Paparazzi/Roborazzi) when component count stabilizes.

### 39.3 Preview Strategy

*   One `@Preview` per DS primitive. Named `Preview[ComponentName]`.
*   Multi-preview annotation for DRY:

```kotlin
@Preview(name = "Light", showBackground = true, group = "Ridgeline")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, group = "Ridgeline")
@Preview(name = "200%", fontScale = 2.0f, group = "Ridgeline")
annotation class RidgelinePreviews
```

*   `@PreviewParameter` providers for: `ActivityType`, `TrackingState`.
*   No `@sample` tags. Usage examples in `@Preview` functions.

### 39.4 Migration Sequence

One screen per PR. Priority: Dashboard → Map → Statistics → Game → Settings → Import/Export.

Steps per screen:
1. Wrap in `AppTheme`.
2. Replace hardcoded colors with `colorScheme.*` / `AppColors.Adaptive.*`.
3. Replace hardcoded shapes with `MaterialTheme.shapes.*` or named shapes.
4. Replace raw `dp` with `RidgelineSpacing.*`.
5. Replace raw springs/tweens with `AppMotion.*` / `MotionTokens.*`.
6. Add `RidgelineSectionHeader` and empty states per §10.
7. Verify at 100%, 150%, 200% font scale + light/dark.

### 39.5 Design System Versioning

No semver. Single-consumer internal system.

*   **Add:** Freely. Document in DESIGN_ROUND_*.md.
*   **Modify:** Update spec + code in one PR. Verify all usages.
*   **Remove:** Grep usages, replace, remove in same PR.
*   **Guard rail:** Token used in 5+ files → dedicated PR with before/after screenshots.
*   **DESIGN_SYSTEM.md is always-current.** DESIGN_ROUND_*.md files are the changelog.

## 40. Design System Inventory

**Canonical count: 35 artifacts.**

| Category | Count | Contents |
|----------|-------|---------|
| Composable primitives | 18 | `AppTheme`, `GlassCard`, `MetricText`, `PrimaryActionButton`, `GlassMetricCard`, `GoalProgressRings`, `RidgelineSectionHeader`, `InlineEmptyState`, `EmptyStateCard`, `TrackingFAB`, `RecordingDot`, `PolicyTierChip`, `TechBadge`, `EncouragementBanner`, `SeasonDots`, `ChallengeCard`, `EmptyChallengeCard`, `PermissionRationaleBanner` |
| Token objects | 17 | `RidgelineSpacing`, `RidgelineGutters`, `AppDimensions`, `AppColors`, `AppColors.Adaptive`, `AppShapes`, `AppTypography`, `AppMotion`, `LoadingMotion`, `MotionTokens`, `LocalReducedMotion`, `LightColorScheme`, `DarkColorScheme`, `DialogShape`, `WaypointShape`, `MomentumPillShape`, `TerrainCardShape` |

Screen compositions (24+) are **consumers**, not part of the DS contract.
