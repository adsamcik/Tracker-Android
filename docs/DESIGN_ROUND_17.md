# Ridgeline Design System — Round 17: Cross-Review

**From:** Lead Designer (Claude, Ridgeline)
**Round:** 17 of 30
**Status:** CROSS-REVIEW — Consistency audit, gap analysis, final implementation specs

---

## 0. R16 Divergence Resolutions

### 0.1 TopAppBar Variants — ACCEPT GPT's Approach

**Decision: Variant-per-screen, with constraints.**

Revisiting R16 position. GPT's argument has merit for one specific case:

| Screen | Variant | Rationale |
|--------|---------|-----------|
| Statistics list | `LargeTopAppBar` | Title "Statistics" gains visual weight when collapsed into pinned bar on scroll. The collapsing behavior provides a satisfying spatial transition that signals "this is a list you'll scroll." The Stats Summary Card is 120dp+ below — no visual competition with the title at top. |
| All other screens | `TopAppBar` (small) | No change. Dashboard, Settings, Game, Import/Export, Trip Detail, sub-screens — all small. |
| Onboarding | `CenterAlignedTopAppBar` | No change. |

**Why accept now:** The Statistics screen is the *only* screen where users regularly scroll through 50+ items. `LargeTopAppBar` with `exitUntilCollapsedScrollBehavior` gives the screen a distinct identity within the app — it's the "history browser." Every other screen is either a dashboard (pinned), settings (pinned), or detail view (small+collapse). One `LargeTopAppBar` is a deliberate choice, not inconsistency.

**FINAL: `LargeTopAppBar` for Statistics list only. All others unchanged.**

### 0.2 Peek Height — HOLD: 72dp

No change. GPT's 96dp rejected per R16 analysis.

### 0.3 Menu Max Items — HOLD: 7

No change.

### 0.4 Celebrations — HOLD: Snackbar

GPT's bottom sheet celebration rejected. Snackbar is lighter, less interruptive, and compounds naturally with existing `UnlockAnnouncementBanner`. No change.

---

## 1. INCONSISTENCIES Found Across Rounds 1–16

### 1.1 Shape Scale vs Named Shapes — CONFLICTING SPECS

**Problem:** DESIGN_SYSTEM.md §8 defines a 5-level diagonal shape scale with dp values:

| Level | Major (TL/BR) | Minor (TR/BL) |
|-------|---------------|----------------|
| L1 | 6dp | 2dp |
| L2 | 10dp | 3dp |
| L3 | 14dp | 4dp |
| L4 | 20dp | 6dp |
| L5 | 24dp | 8dp |

But `Shape.kt` in the codebase uses **percentage-based** identity shapes:

```kotlin
// Shape.kt — current code
val TerrainCardShape = RoundedCornerShape(topStartPercent=15, topEndPercent=4, bottomEndPercent=15, bottomStartPercent=4)
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // NOT L1 (6dp/2dp)
    small = RoundedCornerShape(8.dp),        // NOT L2 (10dp/3dp)
    medium = TerrainCardShape,               // Percentage, NOT L3 dp values
    large = MomentumPillShape,               // Percentage, NOT L4 dp values
    extraLarge = DialogShape,                // 28dp, NOT L5 (24dp/8dp)
)
```

**Resolution:** The spec says `AppShapes(extraSmall=L1, small=L2, medium=L3, large=L4, extraLarge=L5) each as RoundedCornerShape(topStart=major, topEnd=minor, bottomEnd=major, bottomStart=minor)`. The code doesn't match. Fix:

```kotlin
// CORRECT mapping per DESIGN_SYSTEM.md §8
val ShapeL1 = RoundedCornerShape(topStart = 6.dp, topEnd = 2.dp, bottomEnd = 6.dp, bottomStart = 2.dp)
val ShapeL2 = RoundedCornerShape(topStart = 10.dp, topEnd = 3.dp, bottomEnd = 10.dp, bottomStart = 3.dp)
val ShapeL3 = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomEnd = 14.dp, bottomStart = 4.dp)
val ShapeL4 = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
val ShapeL5 = RoundedCornerShape(topStart = 24.dp, topEnd = 8.dp, bottomEnd = 24.dp, bottomStart = 8.dp)

val AppShapes = Shapes(
    extraSmall = ShapeL1,
    small = ShapeL2,
    medium = ShapeL3,
    large = ShapeL4,
    extraLarge = ShapeL5,
)
```

**Identity shapes remain separate:** `WaypointShape`, `MomentumPillShape`, `TerrainCardShape` (percentage-based) are not part of the 5-level scale. They're used for FABs, buttons, and hero cards respectively.

**`DialogShape` (28dp full-round) is not L5.** Dialogs use their own shape.

### 1.2 Bottom Sheet Shape — TWO SPECS

**Problem:** Round 11 `BottomSheetTokens` says `topStart = 28.dp, topEnd = 28.dp` (symmetric). Round 15/16 and DESIGN_SYSTEM.md §21 say `topStart = 20.dp, topEnd = 6.dp` (asymmetric, terrain DNA).

**Resolution:** DESIGN_SYSTEM.md §21 is canonical. **Asymmetric: `topStart = 20.dp, topEnd = 6.dp`.** The Round 11 token was a pre-convergence spec superseded by Round 15. Round 11's `BottomSheetTokens.Shape` is OBSOLETE.

### 1.3 GlassCard — Border Alpha Inconsistency

**Problem:** DESIGN_SYSTEM.md §2.6 says `glassBorderAlpha` is 0.30 light / 0.20 dark. The code in `DesignSystem.kt` uses a hardcoded `0.3f` regardless of dark mode. The floating nav bar spec (§15) says "1dp `outlineVariant` at 0.30α light / 0.20α dark."

**Resolution:** Glass border alpha must be mode-adaptive:

```kotlin
val glassBorderAlpha: Float @Composable get() = if (isSystemInDarkTheme()) 0.20f else 0.30f
```

### 1.4 Activity Color Table — DESIGN_SYSTEM.md vs Round 13

**Problem:** DESIGN_SYSTEM.md §2.5 was updated with Round 13 FINAL values, but references "Okabe-Ito H=164°" etc. The code in `DesignSystem.kt` correctly uses Round 13 values. **No inconsistency — just confirming alignment.**

### 1.5 `PrimaryActionButton` Vertical Padding

**Problem:** DESIGN_SYSTEM.md §14.1 says "24dp horizontal, 12dp vertical." Code in `DesignSystem.kt` uses `horizontal = 24.dp, vertical = 16.dp`.

**Resolution:** Spec wins. **Change code to `vertical = 12.dp`.** 16dp makes buttons taller than necessary.

### 1.6 Typography — Spec vs Code for Body/Label

**Problem:** DESIGN_SYSTEM.md §3 says Body/Label use **Inter**. Round 9/10 FINAL says Body/Label use **System (Noto Sans / Roboto)**. Code uses `FontFamily.Default` (system font).

**Resolution:** Round 10 supersedes the original spec. **Body/Label = system font (Roboto/Noto Sans).** Display/Headline/Title = Outfit (pending font asset bundling). `DESIGN_SYSTEM.md §3` should read "System font (Roboto)" for Body/Label, not "Inter."

### 1.7 Section Number Collision — Two §8s

**Problem:** DESIGN_SYSTEM.md has two sections numbered "8" — §8 Shape Scale and §8 Navigation Labels. Navigation Labels should be §8b or renumbered.

**Resolution:** Renumber Navigation Labels to §8.1 (subsection of shapes) or §9. Will fix when consolidating.

---

## 2. CONTRADICTIONS Between Rounds

### 2.1 Dialog Shape — Round 11 vs Shape Scale

Round 11 `DialogTokens.Shape = DialogShape` (28dp). The shape scale has no 28dp level. This is **correct and intentional** — dialogs are M3 standard components that should not use the asymmetric terrain shape. The 28dp full-round is M3's default dialog corner radius. No conflict — dialogs are exempt from the diagonal shape language.

### 2.2 Loading States — Round 11 "No Shimmer" vs Any Future

Round 11 §2 explicitly says "No shimmer." Round 12 §7.3 says "Placeholder: Solid `surfaceContainerHigh`. No shimmer." These are consistent. **Shimmer is permanently rejected.**

### 2.3 Chip Shape — Round 12 Uses L1/L2, Shape Scale Uses Different Values

Round 12 §4.2 says `FilterChip` shape is `AppShapes.L2` (small). Round 12 §4.3 says `AssistChip` (activity) shape is `AppShapes.L1` (extraSmall). These reference the L1/L2 naming that maps to `AppShapes.extraSmall` / `AppShapes.small`. Consistent once shape scale fix from §1.1 above is applied.

### 2.4 Topo Contour Alpha — Confirmed Consistent

DESIGN_SYSTEM.md §2.6 and §2.7 define three contexts:
- Card: 0.05 light / 0.07 dark
- Empty background: 0.07 light / 0.09 dark
- Tinted decorative: 0.03 light / 0.04 dark

Round 16 §1.2 references "0.06α, 8s drift loop" for the Tier 3 empty state background. This is the *empty background* context rounded (0.07 → 0.06 for consistency with the hero card overlay). **Minor discrepancy — standardize to 0.07 per the token table.**

---

## 3. MISSING SPECS — What Hasn't Been Addressed

### 3.1 Date/Time Pickers

**Not yet specified.** The app needs date picking for:
- Statistics: Filter sessions by date range
- Export: "Last 7 days" / custom date window
- Trip detail: Edit session date (if allowed)

**Spec:**
- **Use M3 `DatePicker` / `DateRangePicker`.** No custom picker.
- **Variant:** `DatePickerDialog` for single date, `DateRangePickerDialog` for range.
- **Colors:** M3 defaults (primary for selection, surfaceContainerHigh for dialog).
- **No time picker needed.** Sessions are auto-timestamped. No user-editable time fields exist.

### 3.2 Number Formatting & Locale-Specific Separators

**Not yet specified.** The app displays distances, speeds, durations, step counts.

**Spec:**
- **Distances:** Formatted via `DecimalFormat` with locale-aware separators. `tnum` feature on. "12,345.6 km" (en-US) / "12 345,6 km" (cs-CZ).
- **Speeds:** Same locale formatting. One decimal place. "4.2 km/h" / "4,2 km/h".
- **Durations:** `HH:MM:SS` format. No locale variation — colons are universal.
- **Step counts:** Locale-aware thousands separator. "12,345" / "12 345".
- **Coordinates:** Always period decimal separator regardless of locale (GPS standard). Displayed only in debug mode. Redacted in release per privacy rules.
- **Percentages:** "85%" — no space between number and symbol (en-US/cs-CZ both).

**Compose helper:**
```kotlin
@Composable
fun formattedDistance(meters: Double, unit: LengthUnit): String {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val format = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 1
    }
    return "${format.format(unit.convert(meters))} ${unit.abbreviation}"
}
```

### 3.3 Onboarding → Main Transition Animation

**Not yet specified.** The exact visual transition from the onboarding success screen to the main dashboard.

**Spec:**
- **Transition:** Shared Z-axis (forward). Same as settings drill-down (Round 13 §1.4).
- **Timing:** `defaultSpatialSpec()` (~300ms).
- **Behavior:** Onboarding slides down + fades out. Dashboard slides up + fades in.
- **One-shot:** Navigation graph replaces onboarding backstack. No back to onboarding.

### 3.4 Notification Design (Tracking + Export)

**Not yet specified as visual design.** Round 16 mentions notifications in permission context but not layout.

**Spec:**
- **Tracking notification (ongoing):**
  - Small icon: Custom outline (`R.drawable.ic_notification_tracking`)
  - Title: "Tracking" / "Tracking · Passive"
  - Text: "2.3 km · 00:45:12" (updates every 5s)
  - Actions: "Stop" (stop tracking), "Pause" (if supported)
  - Channel: "Tracking" (high importance for ongoing)
  - Color: `primary` (`#006874`)

- **Export complete notification:**
  - Small icon: `R.drawable.ic_notification_export`
  - Title: "Export complete"
  - Text: "session_2024-01-15.gpx"
  - Action: "Share" (share intent)
  - Channel: "Exports" (default importance)
  - Auto-cancel: yes

### 3.5 Tooltip Positioning Edge Cases

Round 13 §3 defines tooltip specs but doesn't address:
- **Bottom of screen:** Tooltip flips to above the target (M3 default behavior — no custom code needed).
- **Near floating nav bar:** Tooltip must not overlap the 80dp nav bar. M3 `TooltipDefaults` handles this if `windowInsets` are correctly propagated.

No action needed — M3 handles these. Documenting for completeness.

### 3.6 Selection State for Cards

**Not yet specified.** Multi-select for batch operations (e.g., select sessions for export, select trips to delete).

**Spec:**
- **Trigger:** Long-press on any selectable card enters selection mode.
- **Visual:** Selected card gets `primaryContainer` overlay at 0.12α + 2dp `primary` border.
- **Header:** TopAppBar transitions to selection mode: "N selected" title, leading close (X) icon, trailing "Select all" / "Delete" actions.
- **Exit:** Back press, close icon, or completing the batch action.
- **Selection checkbox:** 24dp, `Checkbox` in top-end corner of card. Appears on enter selection mode (expand animation).
- **Reduced motion:** Checkbox appears instantly.

### 3.7 Landscape Keyboard Interaction

Not specified. When keyboard opens in landscape mode, available screen height is minimal.

**Spec:**
- **Dialogs with text fields:** `verticalScroll()` on content column. Keyboard lifts content via `WindowInsets.ime`.
- **Bottom sheets with forms:** Sheet adjusts above keyboard. Content scrolls within sheet.
- **No landscape-specific layouts.** Same as portrait but narrower vertical space — scrollable containers handle it.

---

## 4. IMPLEMENTATION GAPS — Needed Compose Components

### 4.1 Components Specified But Not Yet Implemented

| Component | Defined In | Status |
|-----------|-----------|--------|
| `RidgelineSectionHeader` | DESIGN_SYSTEM §9 | **Needs creation** |
| `RidgelineDivider` | Round 12 §3.3 | **Needs creation** |
| `ActivityChip` | Round 12 §4.3 | **Needs creation** |
| `RidgelineSwitch` (with ListItem) | Round 15 §5.2 | **Needs creation** |
| `RidgelineTopAppBar` | Round 15 §1.4 | **Needs creation** |
| `RidgelineModalSheet` | Round 15 §2.3 | **Needs creation** |
| `RidgelineDragHandle` | Round 15 §2.4 | **Needs creation** |
| `PermissionRationaleBanner` | Round 16 §2.3 | **Needs creation** |
| `DestructiveConfirmDialog` | Round 11 §1.3 | **Needs creation** |
| `InputDialog` | Round 11 §1.4 | **Needs creation** |
| `LoadingGate` | Round 11 §2.2 | **Needs creation** |
| `PlaceholderRow` | Round 11 §2.3 | **Needs creation** |
| `GpsStatusChip` | Round 16 §6.2 | **Needs creation** |
| `DeleteAllDataFlow` | Round 16 §5.3 | **Needs creation** |
| `TrailProgressBar` | Round 15 §4.6 | **Needs creation** |
| `GoalProgressRing` | Round 15 §4.5 | **Needs creation** |
| `AdaptiveMetricText` | Round 14 §7.4 | **Needs creation** |
| `TopographicPattern` | DESIGN_SYSTEM §2.6 | **Needs Canvas implementation** (see §5 below) |
| `GlassSurface` | Core design system | **Needs full spec** (see §6 below) |
| `MapToolButton` | Round 9 | Partially exists in `MapSheet.kt` |
| `RecordingDot` | Round 16 §6.2 | **Needs creation** |

### 4.2 Components That Exist and Match Spec

| Component | Location | Matches Spec? |
|-----------|---------|---------------|
| `GlassCard` | `DesignSystem.kt` | ⚠️ Partially — missing mode-adaptive border alpha, missing glass blur |
| `MetricText` | `DesignSystem.kt` | ⚠️ Partially — should use `FontFamily.Monospace` per §6 |
| `PrimaryActionButton` | `DesignSystem.kt` | ⚠️ Needs vertical padding fix (16dp → 12dp) |
| `EmptyStateCard` | `EmptyStateCard.kt` | ✅ Matches Tier 2 spec |
| `AppShapes` | `Shape.kt` | ❌ Needs full rewrite per §1.1 |
| `AppMotion` | `Motion.kt` | ✅ Matches spec |
| `RidgelineSpacing` | `DesignSystem.kt` | ✅ Matches spec |
| `RidgelineGutters` | `DesignSystem.kt` | ✅ Matches spec |

---

## 5. Topographic Pattern — FINAL Canvas Implementation

The topographic contour pattern is the signature visual texture of Ridgeline. It appears as background decoration on cards (Tier 2 empty states), the Tier 3 onboarding hero, and optionally as subtle card texture.

### 5.1 Design Intent

Contour lines evoke topographic maps — the brand's core metaphor. They are **decorative only**, never interactive, never carrying information. They provide subtle texture that distinguishes Ridgeline surfaces from plain M3 surfaces.

### 5.2 Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `contourAlpha` | `Float` | Context-dependent | Opacity of contour strokes (see token table §2.6) |
| `strokeWidth` | `Dp` | `2.dp` | Width of contour lines |
| `color` | `Color` | `onSurface` | Base color (alpha applied on top) |
| `animated` | `Boolean` | `true` | Whether contours drift (8s cycle). `false` when `LocalReducedMotion` is true |
| `seed` | `Int` | `0` | Random seed for path variation. Same seed = same pattern |

### 5.3 Path Generation

Two cubic Bézier paths that cross the component diagonally, evoking elevation contour lines. NOT random — deterministic from seed for consistency across recompositions.

### 5.4 FINAL Compose Implementation

```kotlin
/**
 * Decorative topographic contour lines.
 * Draws two cubic Bézier paths as a background texture.
 *
 * Usage:
 * - Card background: `contourAlpha = topoContourAlpha(card)`
 * - Empty state background: `contourAlpha = topoContourAlpha(emptyBg)`
 * - Tinted decorative: `contourAlpha = topoContourAlpha(tinted)`
 */
@Composable
fun TopographicPattern(
    modifier: Modifier = Modifier,
    contourAlpha: Float = if (isSystemInDarkTheme()) 0.07f else 0.05f,
    strokeWidth: Dp = 2.dp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    animated: Boolean = true,
    seed: Int = 0,
) {
    val reducedMotion = LocalReducedMotion.current
    val shouldAnimate = animated && !reducedMotion

    // 8-second infinite drift cycle
    val infiniteTransition = if (shouldAnimate) {
        rememberInfiniteTransition(label = "topo_drift")
    } else null

    val driftOffset = infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "topo_drift_offset",
    )

    val strokeWidthPx = with(LocalDensity.current) { strokeWidth.toPx() }
    val contourColor = color.copy(alpha = contourAlpha)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val drift = (driftOffset?.value ?: 0f) * h * 0.1f

        // Path 1: Upper contour — flows from left to right with gentle curve
        val path1 = Path().apply {
            moveTo(x = -w * 0.1f, y = h * 0.3f + drift)
            cubicTo(
                x1 = w * 0.25f, y1 = h * 0.15f + drift,
                x2 = w * 0.55f, y2 = h * 0.45f + drift,
                x3 = w * 1.1f,  y3 = h * 0.25f + drift,
            )
        }

        // Path 2: Lower contour — offset and inverted curve
        val path2 = Path().apply {
            moveTo(x = -w * 0.05f, y = h * 0.65f + drift * 0.7f)
            cubicTo(
                x1 = w * 0.3f,  y1 = h * 0.55f + drift * 0.7f,
                x2 = w * 0.65f, y2 = h * 0.8f + drift * 0.7f,
                x3 = w * 1.1f,  y3 = h * 0.6f + drift * 0.7f,
            )
        }

        drawPath(
            path = path1,
            color = contourColor,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
        )
        drawPath(
            path = path2,
            color = contourColor,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
        )
    }
}
```

### 5.5 Topo Alpha Helper

```kotlin
object TopoAlpha {
    /** On cards (GlassCard, Tier 2 empty states). */
    val card: Float @Composable get() = if (isSystemInDarkTheme()) 0.07f else 0.05f
    /** On empty screen backgrounds (Tier 3). */
    val emptyBg: Float @Composable get() = if (isSystemInDarkTheme()) 0.09f else 0.07f
    /** Tinted decorative overlays. */
    val tinted: Float @Composable get() = if (isSystemInDarkTheme()) 0.04f else 0.03f
}
```

### 5.6 Usage

```kotlin
// Inside an EmptyStateCard (Tier 2)
Box {
    TopographicPattern(
        modifier = Modifier.matchParentSize(),
        contourAlpha = TopoAlpha.card,
    )
    // Card content on top
}

// Full-screen onboarding hero (Tier 3)
Box {
    TopographicPattern(
        modifier = Modifier.fillMaxSize(),
        contourAlpha = TopoAlpha.emptyBg,
    )
    // Onboarding content
}
```

### 5.7 Accessibility

- **Reduce animations:** `animated = false` → static paths, no drift.
- **Reduce transparency:** Pattern is hidden entirely (per Round 10 §3 accessibility table). Check `LocalReduceTransparency`.
- **Both:** Hidden.

---

## 6. GlassSurface — FINAL Implementation Spec

`GlassSurface` is the core design system component for frosted-glass material treatment. It combines backdrop blur, surface tint, border, and optional topographic texture.

### 6.1 Tier Enum

Three tiers control the glass intensity:

```kotlin
enum class GlassTier {
    /** Standard cards, list items. Subtle glass. */
    STANDARD,
    /** Floating nav bar, bottom sheet. Medium glass. */
    ELEVATED,
    /** Hero overlays, map metric cards. Strong glass. */
    PROMINENT,
}
```

### 6.2 Token Table

| Token | Standard | Elevated | Prominent |
|-------|----------|----------|-----------|
| `tintAlpha` (light) | 0.78 | 0.82 | 0.70 |
| `tintAlpha` (dark) | 0.82 | 0.86 | 0.75 |
| `blurRadius` | 20dp | 20dp | 24dp |
| `borderAlpha` (light) | 0.30 | 0.30 | 0.20 |
| `borderAlpha` (dark) | 0.20 | 0.20 | 0.12 |
| `borderWidth` | 1dp | 1dp | 1dp |
| `tonalElevation` | 1dp (E1) | 2dp (E2) | 6dp (E3) |
| `shadowElevation` | 1dp | 2dp | 6dp |
| Topo contours | Optional | No | No |

### 6.3 Parameters

```kotlin
/**
 * Frosted-glass material surface. Core Ridgeline design system component.
 *
 * Combines backdrop blur (via Haze library), surface tint, border, and
 * optional topographic texture into a unified material treatment.
 *
 * Falls back to solid `surfaceContainer` when:
 * - System "Reduce Transparency" is enabled
 * - In-app "Simplified surfaces" DataStore pref is true
 * - Haze is unavailable (pre-API 31 without RenderEffect)
 *
 * @param tier Controls glass intensity and elevation.
 * @param shape Shape of the surface. Defaults to L3 (medium diagonal).
 * @param showBorder Whether to draw the outline border.
 * @param showTopoPattern Whether to overlay decorative contour lines (Standard tier only).
 * @param modifier Modifier for the container.
 * @param content Content composable.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.STANDARD,
    shape: Shape = MaterialTheme.shapes.medium,
    showBorder: Boolean = true,
    showTopoPattern: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
)
```

### 6.4 FINAL Compose Implementation

```kotlin
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.STANDARD,
    shape: Shape = MaterialTheme.shapes.medium,
    showBorder: Boolean = true,
    showTopoPattern: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val reduceTransparency = LocalReduceTransparency.current
    val simplifiedSurfaces = LocalSimplifiedSurfaces.current

    val useGlass = !reduceTransparency && !simplifiedSurfaces

    val tokens = remember(tier, isDark) { GlassTokens.forTier(tier, isDark) }

    val containerColor = if (useGlass) {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = tokens.tintAlpha)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val borderModifier = if (showBorder) {
        Modifier.border(
            width = tokens.borderWidth,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = tokens.borderAlpha),
            shape = shape,
        )
    } else {
        Modifier
    }

    Surface(
        modifier = modifier.then(borderModifier),
        shape = shape,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = tokens.tonalElevation,
        shadowElevation = tokens.shadowElevation,
    ) {
        Box {
            // Optional topo contour texture (Standard tier only, glass mode only)
            if (showTopoPattern && useGlass && tier == GlassTier.STANDARD) {
                TopographicPattern(
                    modifier = Modifier.matchParentSize(),
                    contourAlpha = TopoAlpha.card,
                )
            }

            // Content with padding
            Box(
                modifier = Modifier.padding(RidgelineSpacing.Lg),
                content = content,
            )
        }
    }

    // Note: Actual Haze backdrop blur integration requires wrapping the
    // parent content with HazeState. The blur is applied at the HazeChild
    // level, not inside this component. This component provides the tint,
    // border, elevation, and fallback. Blur setup is the caller's
    // responsibility via Haze modifiers.
}

/** Resolved token values for a glass tier + dark mode combination. */
data class GlassTokenValues(
    val tintAlpha: Float,
    val blurRadius: Dp,
    val borderAlpha: Float,
    val borderWidth: Dp,
    val tonalElevation: Dp,
    val shadowElevation: Dp,
)

object GlassTokens {
    fun forTier(tier: GlassTier, isDark: Boolean): GlassTokenValues = when (tier) {
        GlassTier.STANDARD -> GlassTokenValues(
            tintAlpha = if (isDark) 0.82f else 0.78f,
            blurRadius = 20.dp,
            borderAlpha = if (isDark) 0.20f else 0.30f,
            borderWidth = 1.dp,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
        )
        GlassTier.ELEVATED -> GlassTokenValues(
            tintAlpha = if (isDark) 0.86f else 0.82f,
            blurRadius = 20.dp,
            borderAlpha = if (isDark) 0.20f else 0.30f,
            borderWidth = 1.dp,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        )
        GlassTier.PROMINENT -> GlassTokenValues(
            tintAlpha = if (isDark) 0.75f else 0.70f,
            blurRadius = 24.dp,
            borderAlpha = if (isDark) 0.12f else 0.20f,
            borderWidth = 1.dp,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        )
    }
}
```

### 6.5 Usage Guide

```kotlin
// Standard card (replaces current GlassCard)
GlassSurface(tier = GlassTier.STANDARD) {
    Text("Trip summary content")
}

// With topographic texture
GlassSurface(
    tier = GlassTier.STANDARD,
    showTopoPattern = true,
) {
    EmptyStateContent(...)
}

// Floating nav bar
GlassSurface(
    tier = GlassTier.ELEVATED,
    shape = RoundedCornerShape(32.dp),
) {
    NavigationBarContent(...)
}

// Map metric overlay
GlassSurface(
    tier = GlassTier.PROMINENT,
    shape = MaterialTheme.shapes.small,
) {
    MetricText(value = "2.4 km", label = "Distance")
}
```

### 6.6 Migration from `GlassCard`

`GlassCard` in `DesignSystem.kt` should become a thin wrapper:

```kotlin
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    showBorder: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) = GlassSurface(
    modifier = modifier,
    tier = GlassTier.STANDARD,
    shape = shape,
    showBorder = showBorder,
    content = content,
)
```

---

## 7. Token File Architecture — Complete File List

Every Kotlin file needed for the design system, with purpose and location.

### 7.1 Core Design System Files

All live in `sutils/src/main/java/com/adsamcik/tracker/shared/utils/style/compose/`.

| File | Purpose | Status |
|------|---------|--------|
| **`Color.kt`** | Light/dark M3 color schemes, brand color constants, semantic colors (success, warning), contextual colors (trackActive, trackHistory) | ✅ Exists, correct |
| **`Typography.kt`** | `AppTypography` — all 15 M3 type roles. Outfit for Display/Headline/Title, system for Body/Label | ✅ Exists, needs Outfit font family when bundled |
| **`Shape.kt`** | 5-level diagonal shape scale (L1–L5), identity shapes (Waypoint, MomentumPill, TerrainCard), `AppShapes`, `DialogShape` | ⚠️ Exists, **needs rewrite** per §1.1 |
| **`Motion.kt`** | `AppMotion` (SecureSnap, TactileActive, SpatialGlide, durations), `LoadingMotion` | ✅ Exists, correct |
| **`AppTheme.kt`** | `AppTheme` composable — Monet + fallback, dark mode, wires typography/shapes/colors | ✅ Exists, correct |
| **`DesignSystem.kt`** | `AppDimensions`, `RidgelineSpacing`, `RidgelineGutters`, `AppColors` (activity colors), `GlassCard`, `MetricText`, `PrimaryActionButton` | ⚠️ Exists, **needs updates** per §1.3, §1.5 |
| **`GlassSurface.kt`** | `GlassSurface`, `GlassTier`, `GlassTokens`, `GlassTokenValues` — core glass material component | ❌ **Needs creation** |
| **`TopographicPattern.kt`** | `TopographicPattern` Canvas composable, `TopoAlpha` tokens | ❌ **Needs creation** |
| **`Components.kt`** | Reusable design system components: `RidgelineSectionHeader`, `RidgelineDivider`, `DividerIndent`, `RidgelineSwitch`, `RidgelineDragHandle`, `RecordingDot`, `ActivityChip` | ❌ **Needs creation** |
| **`TopAppBars.kt`** | `RidgelineTopAppBar`, `DashboardTopBar` — standardized top bar wrappers | ❌ **Needs creation** |
| **`Sheets.kt`** | `RidgelineModalSheet`, `MapScreenScaffold` — standardized sheet wrappers | ❌ **Needs creation** |
| **`Dialogs.kt`** | `DestructiveConfirmDialog`, `InputDialog`, `DeleteAllDataFlow` — dialog variants beyond M3 defaults | ❌ **Needs creation** |
| **`Progress.kt`** | `TrailProgressBar`, `GoalProgressRing`, `TrackingProgressBar`, `RidgelineLoadingIndicator` | ❌ **Needs creation** |
| **`Loading.kt`** | `LoadingGate`, `PlaceholderRow` — loading state components | ❌ **Needs creation** |
| **`EmptyStates.kt`** | `InlineEmptyState` (Tier 1), `EmptyStateCard` already exists. Keep separate. | ⚠️ `EmptyStateCard.kt` exists. `InlineEmptyState` needs adding. |
| **`Permissions.kt`** | `PermissionRationaleBanner`, `PermissionStage` enum | ❌ **Needs creation** |
| **`Accessibility.kt`** | `LocalReducedMotion`, `LocalReduceTransparency`, `LocalSimplifiedSurfaces` CompositionLocals | ❌ **Needs creation** |
| **`Snackbar.kt`** | `showSuccess`, `showActionable`, `showError` extension functions on `SnackbarHostState` | ❌ **Needs creation** |
| **`AdaptiveText.kt`** | `AdaptiveMetricText` — step-down display text for 200% font scaling | ❌ **Needs creation** |
| **`ThemedApp.kt`** | Legacy wrapper. Intentionally blank. Keep for file history. | ✅ Exists, correct |

### 7.2 Module-Specific Files (Not in sutils)

| File | Module | Purpose |
|------|--------|---------|
| `GpsStatusChip.kt` | `tracker` | GPS state chip component |
| `TrackingStateVisuals.kt` | `tracker` or `dashboard` | Recording dot, FAB state management |
| `MapToolButton.kt` | `map` | 52dp map tool button (exists in MapSheet.kt, extract) |
| `FirstSessionCelebration.kt` | `app` or `dashboard` | First-session snackbar trigger |

### 7.3 Files to Modify (Not Create)

| File | Change Needed |
|------|---------------|
| `Shape.kt` | Rewrite `AppShapes` to use dp-based L1–L5 scale |
| `DesignSystem.kt` | Fix `PrimaryActionButton` vertical padding (16→12dp), add mode-adaptive glass border alpha, update `GlassCard` to delegate to `GlassSurface` |
| `DesignSystem.kt` | Fix `MetricText` to use `FontFamily.Monospace` |
| `DESIGN_SYSTEM.md` | Fix §3 Body/Label font (Inter → System), fix §8 numbering collision, standardize Tier 3 topo alpha to 0.07 |

---

## 8. Summary: Completeness Status

### 8.1 What's Fully Specified and Consistent

- ✅ Color palette (brand + semantic + contextual + activity)
- ✅ Typography scale (all 15 roles mapped)
- ✅ Spacing system (10 tokens, semantic pairing)
- ✅ Motion system (3 springs + 4 durations)
- ✅ Elevation strategy (4 levels)
- ✅ Navigation labels & floating nav bar
- ✅ Button hierarchy (5 variants + FAB)
- ✅ Card specifications (4 types)
- ✅ Empty states (3 tiers, per-screen content)
- ✅ Permission flow (3-step graceful degradation)
- ✅ Loading states (no shimmer, crossfade + placeholders)
- ✅ Dialog taxonomy (6 types)
- ✅ Snackbar policy (no Toast, 3 duration tiers)
- ✅ Screen transitions (fade-through, container transform, shared Z-axis)
- ✅ Accessibility (reduce motion, reduce transparency, 200% font scaling)
- ✅ Dynamic color (Monet scoped to surfaces only)
- ✅ Tracking state visualizations (6 states, GpsState enum)
- ✅ Data export flow
- ✅ Settings danger zone (3-step delete with type-to-confirm)
- ✅ Drag & drop (map layers only)
- ✅ Tooltip system (3 tiers)
- ✅ Dark mode specifics

### 8.2 What Was Missing (Now Addressed in This Round)

- ✅ Date/time pickers (§3.1)
- ✅ Number formatting / locale separators (§3.2)
- ✅ Onboarding → Main transition (§3.3)
- ✅ Notification design (§3.4)
- ✅ Card selection state (§3.6)
- ✅ Topographic pattern final implementation (§5)
- ✅ GlassSurface final implementation (§6)
- ✅ Token file architecture (§7)

### 8.3 Remaining for Rounds 18–30

| Topic | Priority | Notes |
|-------|----------|-------|
| Widget design (home screen) | Medium | Glance API, 1-2 widget types max |
| Map UI overlay positioning | Medium | Metric card placement, layer controls, compass |
| Notification channel hierarchy | Low | Tracking, exports, achievements |
| Animation choreography | Low | Coordinated animations for complex transitions |
| Dark ↔ Light transition animation | Low | How the mode switch animates |
| Icon set audit | Low | Which Material Symbols, filled vs outlined consistency |
| String/copy style guide | Low | Tone, capitalization, punctuation rules beyond empty states |
| RTL layout verification | Low | Asymmetric shapes: do TL/BR flip to TR/BL in RTL? |
| Component testing strategy | Low | Screenshot tests, preview coverage |
| Design token export format | Low | If tokens need to be consumed outside Kotlin |

---

## Next: Round 18

With the cross-review complete, Round 18 should address the highest-priority gap: **Map UI overlay system** — the most complex visual composition in the app (map + bottom sheet + metric cards + layer controls + FAB + floating nav bar, all coordinated).

GPT: Confirm Round 17 findings or raise blocking objections.
