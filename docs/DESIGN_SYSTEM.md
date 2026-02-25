# Tracker Android - Material 3 Expressive Design System

## 1. Brand Personality
Privacy-first, fully local location & activity tracker for Android. Reliable, secure, modern, and slightly adventurous.

## 2. Color Palette
Balances trust and security (deep, reliable cool tones) with adventure and activity (vibrant, energetic warm tones).

### Primary: Secure Teal
*   **Light Mode:** Primary: `#006874`, On-Primary: `#FFFFFF`, Primary Container: `#97F0FF`, On-Primary Container: `#001F24`
*   **Dark Mode:** Primary: `#4FD8EB`, On-Primary: `#00363D`, Primary Container: `#004F58`, On-Primary Container: `#97F0FF`

### Secondary: Trail Slate
*   **Light Mode:** Secondary: `#4A6367`, On-Secondary: `#FFFFFF`, Secondary Container: `#CDE7EC`, On-Secondary Container: `#051F23`
*   **Dark Mode:** Secondary: `#B1CBD0`, On-Secondary: `#1C3438`, Secondary Container: `#334B4F`, On-Secondary Container: `#CDE7EC`

### Tertiary: Sunset Rust (Expressive Accent)
*   **Light Mode:** Tertiary: `#98483A`, On-Tertiary: `#FFFFFF`, Tertiary Container: `#FFDAD4`, On-Tertiary Container: `#3C0903`
*   **Dark Mode:** Tertiary: `#FFB4A8`, On-Tertiary: `#5C190D`, Tertiary Container: `#7A3024`, On-Tertiary Container: `#FFDAD4`

### Neutral / Surface (Tinted with Primary)
*   **Light Mode:** Surface: `#F8FDFF`, Surface Container: `#EBF4F6`, On-Surface: `#171D1E`, Outline: `#6F797A`
*   **Dark Mode:** Surface: `#0E1415`, Surface Container: `#1A2022`, On-Surface: `#DFE4E5`, Outline: `#899294`

### Semantic Colors
*   **Error:** Light: `#BA1A1A` / `#FFDAD6` | Dark: `#FFB4AB` / `#93000A`
*   **Success:** Light: `#146C2E` / `#A3F4A5` | Dark: `#88D78A` / `#00531E`
*   **Warning:** Light: `#8D5000` / `#FFDCC1` | Dark: `#FFB776` / `#6B3D00`

### Contextual Colors
*   **Track Active:** `#FF3B30`
*   **Track History:** `#00829B`

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
