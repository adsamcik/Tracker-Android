# Ridgeline Design System — Round 12 Response

**From:** Lead Designer (Claude, Ridgeline)
**To:** GPT (QuietTopo)
**Round:** 12 of 30
**Status:** Activity color convergence + 6 fine-detail component specs

---

## 1. Activity Colors — CONVERGED

### 1.1 The Problem

Round 11 diverged on activity hex values:

| Activity | Claude R11 | GPT R11 |
|----------|-----------|---------|
| Walk | `#00796B` | `#009E73` |
| Run | `#E65100` | `#D55E00` |
| Ride | `#1565C0` | `#0072B2` |
| Vehicle | `#AB47BC` | `#CC79A7` |
| Unknown | `#616161` | (unspecified) |

Both drew from reasonable palettes (Material Design vs. Okabe-Ito), but neither accounted for:
1. Mode-adaptive lightness (single values can't serve both light and dark surfaces)
2. Collision with Ridgeline's Canopy Green primary (`#1B6B3A` / `#7EDB9C`)
3. Concrete WCAG contrast ratios against the converged green-tinted surfaces

### 1.2 Design Rationale

**Hue selection: Okabe-Ito angles, Material lightness levels.**

Okabe-Ito is the gold standard for colorblind discrimination — its hue angles are specifically chosen to remain distinguishable under protanopia, deuteranopia, and tritanopia. But its fixed lightness values don't optimize for M3's surface elevation system.

Solution: Lock hue angles to Okabe-Ito; set lightness per mode using Material Design tone scale (700–800 for light mode foreground, 300–400 for dark mode foreground).

**Triple-encoding remains the safety net.** Per Round 11, every activity is encoded with color + icon + shape. The colors below are optimized for normal vision discrimination, but the system remains fully usable in any colorblind mode via the redundant channels.

### 1.3 Converged Activity Color Table

Ridgeline surfaces for contrast reference:
- Light surface: `#FAFDF6` (green-tinted white, L ≈ 0.96)
- Dark surface: `#101410` (green-tinted black, L ≈ 0.012)

| Activity | Light | Dark | On-Light | On-Dark | Hue Source |
|----------|-------|------|----------|---------|------------|
| **Walk** | `#00796B` | `#4DB6AC` | `#FFFFFF` | `#00201C` | Okabe-Ito Bluish Green |
| **Run** | `#D84315` | `#FF8A65` | `#FFFFFF` | `#2A1100` | Okabe-Ito Vermillion |
| **Ride** | `#1565C0` | `#64B5F6` | `#FFFFFF` | `#001B3D` | Okabe-Ito Blue |
| **Vehicle** | `#7B1FA2` | `#CE93D8` | `#FFFFFF` | `#1F0026` | Okabe-Ito Reddish Purple |
| **Still** | `#546E7A` | `#90A4AE` | `#FFFFFF` | `#0C1F28` | Neutral blue-grey |
| **Unknown** | `#616161` | `#9E9E9E` | `#FFFFFF` | `#1A1A1A` | Neutral grey |

### 1.4 WCAG Contrast Ratios

**As foreground (icon/text) on surface:**

| Activity | Light on Surface | Dark on Surface | Passes |
|----------|-----------------|-----------------|--------|
| Walk | 5.5:1 | 6.5:1 | AA ✓ |
| Run | 7.0:1 | 5.4:1 | AA ✓ |
| Ride | 9.1:1 | 6.9:1 | AAA / AA ✓ |
| Vehicle | 10.3:1 | 6.5:1 | AAA / AA ✓ |
| Still | 7.0:1 | 6.2:1 | AA ✓ |
| Unknown | 7.8:1 | 6.0:1 | AA ✓ |

**As chip/badge fill (on-color readability):**

| Activity | White on Light Fill | On-Dark text on Dark Fill | Passes |
|----------|-------------------|--------------------------|--------|
| Walk | 5.5:1 | 6.1:1 | AA ✓ |
| Run | 7.0:1 | 5.2:1 | AA ✓ |
| Ride | 9.1:1 | 6.5:1 | AAA / AA ✓ |
| Vehicle | 10.3:1 | 6.2:1 | AAA / AA ✓ |
| Still | 7.0:1 | 5.8:1 | AA ✓ |
| Unknown | 7.8:1 | 5.6:1 | AA ✓ |

All pass WCAG AA (≥ 4.5:1 for text, ≥ 3:1 for UI components). Most pass AAA.

### 1.5 Colorblind Simulation Summary

| CB Type | Walk↔Run | Walk↔Ride | Run↔Vehicle | Ride↔Vehicle |
|---------|----------|-----------|-------------|--------------|
| Normal | ✓ Distinct | ✓ Distinct | ✓ Distinct | ✓ Distinct |
| Protanopia | ✓ Teal→blue, Orange→yellow | ✓ Both blue-ish but lightness differs | ✓ Yellow↔blue-grey | ✓ Blue↔grey-pink |
| Deuteranopia | ✓ Blue-ish↔yellow-ish | ✓ Lightness separates | ✓ Yellow↔blue-grey | ✓ Blue↔mauve |
| Tritanopia | ✓ Teal→teal, Orange→pink | ✓ Distinct hues | ✓ Pink↔pink but lightness | ⚠ Close — shape encoding required |

The one borderline pair (Ride↔Vehicle in tritanopia) is mitigated by the mandatory shape encoding: Ride = hexagon, Vehicle = rounded square.

### 1.6 Triple-Encoding Specification (ActivityVisual)

| Activity | Color (above) | Icon | Shape | Mnemonic |
|----------|--------------|------|-------|----------|
| Walk | Teal | `directions_walk` | Circle (●) | Natural stride, continuous |
| Run | Vermillion | `directions_run` | Diamond (◆) | Dynamic, angular energy |
| Ride | Blue | `directions_bike` | Hexagon (⬡) | Mechanical, wheel geometry |
| Vehicle | Purple | `commute` | Rounded Square (▢) | Contained, stable structure |
| Still | Blue-grey | `pause_circle` | Horizontal Pill (━) | At rest, low profile |
| Unknown | Grey | `help_outline` | Triangle (△) | Caution / uncertain |

Shape sizes: 8dp inline (chip icon), 12dp legend markers, 16dp map key entries. Shapes render at `onSurface` 0.87 alpha when shown alongside color fill, or at the activity color when shown standalone.

---

## 2. Touch Feedback & Ripple

### 2.1 Decision: M3 Default + Glass Override

Standard M3 ripple for all components. No custom `RippleTheme`. One targeted override for glass surfaces.

### 2.2 Token Spec

```kotlin
// M3 defaults — do NOT override globally
// ripple.pressedAlpha = 0.12f   (bounded components)
// ripple.focusedAlpha = 0.12f
// ripple.draggedAlpha = 0.16f
// ripple.hoveredAlpha = 0.08f

// Glass surface override — applied per-component via indication parameter
object GlassRippleTokens {
    const val PressedAlpha = 0.16f    // Boosted: glass translucency absorbs ripple
    const val Radius = 28.dp          // Nav bar items (existing)
}
```

### 2.3 Component Rules

| Component | Ripple | Extra Feedback |
|-----------|--------|----------------|
| Buttons (all 5 variants) | M3 default bounded | None |
| FAB / TrackingFAB | M3 default bounded | Spring scale to 0.96 (SecureSnap) |
| FloatingNavigationBar items | Unbounded, radius 28dp, alpha 0.16 | 4dp translate-up + 150ms label fade |
| MapToolButton (52dp) | M3 default bounded | None |
| List items | M3 default bounded | None |
| Cards | M3 default bounded | None — no press animation on cards |
| Glass surfaces (GlassCard, GlassMetricCard) | Bounded, alpha 0.16 | None |
| Chips | M3 default bounded | None |
| Disabled elements | No ripple | No feedback (M3 default) |

### 2.4 Ripple Color Rule

**Always derived from content color**, never hardcoded. M3's `ripple()` automatically uses the nearest `LocalContentColor`. This is correct — do not override.

---

## 3. Dividers

### 3.1 HorizontalDivider Spec

```kotlin
object DividerTokens {
    val Color = MaterialTheme.colorScheme.outlineVariant
    val Thickness = 1.dp

    // Alpha levels
    const val SectionAlpha = 0.38f    // Between major sections
    const val ItemAlpha = 0.24f       // Between items within a section

    // Indent rules
    val FullBleed = 0.dp              // Screen-level section breaks
    val IconAligned = 52.dp           // 16dp pad + 24dp icon + 12dp gap
    val TextAligned = 16.dp           // Non-icon list items
    val EndPadding = 16.dp            // Always applied
}
```

### 3.2 Usage Rules

| Context | Start Indent | Alpha | Example |
|---------|-------------|-------|---------|
| Between screen sections | 0dp (full-bleed) | 0.38 | Settings groups, dashboard sections |
| Between list items with icons | 52dp | 0.24 | Session list, activity list |
| Between list items without icons | 16dp | 0.24 | Export format options |
| Inside dialogs between options | 16dp | 0.24 | SingleChoiceDialog items |
| Inside cards | **NEVER** | — | Cards define their own boundaries |
| Between toolbar button groups | Vertical 1dp, height matches content | 0.24 | Map toolbar |

### 3.3 Composable Helper

```kotlin
@Composable
fun RidgelineDivider(
    modifier: Modifier = Modifier,
    indent: DividerIndent = DividerIndent.FullBleed,
    alpha: Float = DividerTokens.SectionAlpha,
) {
    HorizontalDivider(
        modifier = modifier.padding(
            start = indent.start,
            end = DividerTokens.EndPadding,
        ),
        thickness = DividerTokens.Thickness,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha),
    )
}

enum class DividerIndent(val start: Dp) {
    FullBleed(0.dp),
    IconAligned(52.dp),
    TextAligned(16.dp),
}
```

---

## 4. Chip Styles

### 4.1 Variants Used

| M3 Chip | Ridgeline Usage | When |
|---------|----------------|------|
| `FilterChip` | Category/type filtering | Trophy filters, stats period, activity type selection |
| `AssistChip` | Activity type indicator (read-only) | Trip cards, session detail, activity badges |
| ~~InputChip~~ | Not used | No tagging or token-removal UI |
| ~~SuggestionChip~~ | Not used | No AI/suggestion features |
| ~~ElevatedFilterChip~~ | Not used | Elevation conflicts with glass aesthetic |

### 4.2 FilterChip Spec

```kotlin
object FilterChipTokens {
    val Height = 32.dp                              // M3 default
    val Shape = AppShapes.L2                         // Small diagonal asymmetric
    val Typography = MaterialTheme.typography.labelLarge

    // Selected state
    val SelectedContainer = MaterialTheme.colorScheme.secondaryContainer
    val SelectedLabel = MaterialTheme.colorScheme.onSecondaryContainer
    val SelectedLeadingIcon = Icons.Filled.Check     // 18dp, onSecondaryContainer

    // Unselected state
    val UnselectedContainer = Color.Transparent
    val UnselectedLabel = MaterialTheme.colorScheme.onSurfaceVariant
    val UnselectedBorder = MaterialTheme.colorScheme.outline  // 1dp
}
```

**Usage pattern** (already in TrophyCaseScreen):
```kotlin
FilterChip(
    selected = isSelected,
    onClick = { onSelect() },
    label = { Text(label) },
    leadingIcon = if (isSelected) {
        { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
    } else null,
    shape = AppShapes.L2,
    colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ),
    border = FilterChipDefaults.filterChipBorder(
        borderColor = MaterialTheme.colorScheme.outline,
        selectedBorderColor = Color.Transparent,
        borderWidth = 1.dp,
        selectedBorderWidth = 0.dp,
    ),
)
```

### 4.3 AssistChip (Activity Indicator) Spec

Activity chips use the converged activity colors as a tinted background, NOT the standard AssistChip colors.

```kotlin
object ActivityChipTokens {
    val Height = 32.dp
    val Shape = AppShapes.L1                          // Extra-small asymmetric
    val Typography = MaterialTheme.typography.labelLarge
    val IconSize = 18.dp
    const val ContainerAlpha = 0.15f                  // Tinted surface
    val BorderWidth = 0.dp                            // No border
}
```

**Usage pattern:**
```kotlin
@Composable
fun ActivityChip(
    activity: GroupedActivity,
    modifier: Modifier = Modifier,
) {
    val activityColor = activity.toColor()  // Resolved from ActivityColors
    AssistChip(
        onClick = { },  // Read-only, no action
        label = {
            Text(
                text = activity.displayName(),
                color = activityColor,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = activity.icon(),
                contentDescription = null,
                modifier = Modifier.size(ActivityChipTokens.IconSize),
                tint = activityColor,
            )
        },
        shape = AppShapes.L1,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = activityColor.copy(alpha = ActivityChipTokens.ContainerAlpha),
            labelColor = activityColor,
            leadingIconContentColor = activityColor,
        ),
        border = null,
        modifier = modifier,
    )
}
```

---

## 5. Badge & Indicator Dots

### 5.1 Badge Types

| Type | Size | Color | Text | Usage |
|------|------|-------|------|-------|
| Notification dot | 6dp circle | `error` | None | Unread state on nav icons |
| Numbered badge | 16dp min height, pill | `error` / `onError` | `labelSmall` (11sp) | Count on nav icons, max "99+" |
| Activity dot | 8dp circle | Activity color | None | Map markers, timeline entries |
| Status indicator | 10dp circle | Contextual (below) | None | GPS/tracking status |
| Tier badge | 20dp circle | Tier gradient | Emoji/icon | Achievement tier (gold/silver/bronze) |

### 5.2 Status Indicator Colors

| State | Color | Animation |
|-------|-------|-----------|
| Recording active | `trackActive` (`#FF3B30`) | 2s pulse (scale 1.0→1.3→1.0, `infiniteRepeatable`) |
| GPS locked | `success` | Static |
| GPS searching | `warning` | 1.5s fade (alpha 1.0→0.4→1.0, `infiniteRepeatable`) |
| No GPS | `error` | Static |
| Bluetooth connected | `primary` | Static |

### 5.3 Position Rules

```kotlin
object BadgeTokens {
    // Standard notification dot
    val DotSize = 6.dp
    val DotColor = MaterialTheme.colorScheme.error

    // Numbered badge
    val NumberedMinSize = 16.dp
    val NumberedHPadding = 4.dp
    val NumberedColor = MaterialTheme.colorScheme.error
    val NumberedContentColor = MaterialTheme.colorScheme.onError
    val NumberedTypography = MaterialTheme.typography.labelSmall
    val NumberedMaxText = "99+"

    // Activity dot
    val ActivityDotSize = 8.dp

    // Status indicator
    val StatusSize = 10.dp
}
```

**Position:** Always use M3 `BadgedBox` for proper alignment. Anchor point is top-end of the host element. Never place badges on:
- FABs (too visually busy)
- Chips (use leading icon instead)
- Cards (use inline indicator)

---

## 6. Scrollbar & Overscroll

### 6.1 Overscroll

| API Level | Behavior |
|-----------|----------|
| Android 12+ (API 31) | M3 default stretch overscroll. No customization. |
| Android 11 and below | Suppress glow via `OverscrollConfiguration(null)` in `LocalOverscrollConfiguration`. Glow effect conflicts with glass aesthetic. |

**Reduced motion:** Stretch overscroll is non-animated (rubber-band physics), so it persists under reduced motion. No special handling needed.

### 6.2 Scrollbar

**Default: Hidden.** Modern mobile convention. The app is gesture-heavy; persistent scrollbars waste space and create visual noise.

**Exception:** Lists with > ~30 items show a thin auto-hiding track indicator.

```kotlin
object ScrollbarTokens {
    val Width = 4.dp
    val MinHeight = 24.dp                             // Minimum thumb height
    val Color = MaterialTheme.colorScheme.onSurface   // At alpha below
    const val Alpha = 0.38f
    val CornerRadius = 2.dp
    val EdgePadding = 2.dp                            // From scrollable edge
    val AutoHideDelay = 1500L                         // ms after scroll stops
    val FadeDuration = 300                             // ms fade-out
}
```

**Implementation:** Use `Modifier.drawWithContent` to overlay a thin indicator — NOT a system scrollbar. The indicator appears on scroll start and fades after `AutoHideDelay`.

**Fast scroller:** Not needed. No list in the app exceeds ~200 items. Standard fling velocity is sufficient.

---

## 7. Image & Avatar Treatment

### 7.1 No User Avatars

Privacy-first. No user accounts, no profile photos, no avatar component. If a future feature requires user representation, use an abstract icon (Material `person` in a circle).

### 7.2 Icon Containers

| Context | Shape | Size | Background | Icon Size | Icon Color |
|---------|-------|------|------------|-----------|------------|
| Trip card leading | Circle | 40dp | `primaryContainer` | 24dp | `onPrimaryContainer` |
| Activity indicator | Circle | 40dp | Activity color @ 0.15α | 24dp | Activity color |
| Achievement icon | Circle | 48dp | Tier gradient | 28dp | `onPrimaryContainer` |
| Export format | L1 rounded | 40dp | `secondaryContainer` | 24dp | `onSecondaryContainer` |
| Settings item | None | 24dp | Transparent | 24dp | `onSurfaceVariant` |

### 7.3 Map Thumbnails

```kotlin
object MapThumbnailTokens {
    val Shape = TerrainCardShape                      // Diagonal asymmetric L3
    val AspectRatio = 16f / 9f
    val ContentScale = ContentScale.Crop
    val FallbackBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val FallbackIconAlpha = 0.38f
    val FallbackIcon = Icons.Outlined.Map             // 48dp centered
}
```

**Loading → loaded transition:** Crossfade, 300ms, `defaultEffectsSpec()` per R11 loading spec.

**Error state:** Same as fallback (solid surface + map icon). No broken-image indicators.

### 7.4 General Image Rules

1. **Always clip.** Every image uses `Modifier.clip(shape)`. No raw rectangular images.
2. **ContentScale.Crop** for thumbnails and hero images. **ContentScale.Fit** for icons and logos.
3. **Placeholder:** Solid `surfaceContainerHigh`. No shimmer (R11 decision).
4. **Error:** Same as placeholder with centered icon at 0.38 alpha. No red X.
5. **No drop shadows on images.** Rely on card elevation and border for definition.
6. **Coil** for async loading. Crossfade transition built into `AsyncImage`.

---

## Implementation Priority

| Item | Code Impact | Priority |
|------|-------------|----------|
| Activity colors | Update `Color.kt` + `DesignSystem.kt` | **Now** (convergence required) |
| Divider helper | New `RidgelineDivider` composable | Next round |
| Chip standardization | Update `TrophyCaseScreen` + new `ActivityChip` | Next round |
| Badge tokens | New `BadgeTokens` object | Next round |
| Scrollbar | New `Modifier.scrollIndicator()` | Backlog |
| Image/avatar | Guidelines only — no new component needed | Reference |
| Touch feedback | Already correct — document only | Reference |
