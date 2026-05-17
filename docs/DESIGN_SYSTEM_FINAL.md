# Tracker Android — Ridgeline Design System

> **Version:** 1.0 — Final (Round 30 of 30)
> **Status:** All decisions locked. This is the definitive developer reference.
> **Seed:** `#1B6B3A` (Canopy Green) · **Theme:** `AppTheme` backed by Material 3 `MaterialTheme` · **Strategy:** Single composition root, no shim

---

## PART 0 — QUICK REFERENCE

### 0.1 At-a-Glance Cheat Sheet

```
┌──────────────────────────────────────────────────────────────────┐
│  RIDGELINE — Tracker Android Design Language                     │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  SEED        #1B6B3A  Canopy Green (HCT: H≈145° C≈48 T≈38)     │
│  TERTIARY    #857010 / #D0B24A  Trail Gold (H≈49°)              │
│  THEME ROOT  MaterialTheme via AppTheme                          │
│                                                                  │
│  FONTS       Display/Headline/Title: Outfit (Google Fonts)       │
│              Body/Label: Inter (or system Roboto)                │
│              Metrics: Roboto Mono (FontFamily.Monospace)         │
│                                                                  │
│  SHAPE DNA   Diagonal asymmetry, 3:1 major:minor ratio          │
│              L1=6/2  L2=10/3  L3=14/4  L4=20/6  L5=24/8 (dp)   │
│              Identity: WaypointShape, MomentumPillShape          │
│                                                                  │
│  SPACING     4dp base grid, 10 tokens: 0/2/4/8/12/16/20/24/32/48│
│  ELEVATION   4 levels: Flat(0) Raised(1) Floating(2) Overlay(6) │
│                                                                  │
│  GLASS       G0=solid  G1=blur10/α0.85  G2=blur18/α0.78  G3=blur26/α0.72           │
│              Pre-API 31: opaque surfaceContainer + border fallback        │
│                                                                  │
│  MOTION      6 springs: Snap(1500/0.75) Settle(400/1.0)         │
│              Respond(800/0.82) Crest(300/0.55)                   │
│              Drift(50/1.0) Surge(180/0.58)                       │
│              Card stagger: 60ms, cap 6                           │
│                                                                  │
│  NAV BAR     80dp height, 32dp radius, G3 glass                 │
│              Pill 32×16dp, selected-label-only                   │
│              Material Symbols Rounded weight 500                 │
│                                                                  │
│  ACTIVITY    Walk #007051/#52C5A6  Run #A34800/#EF8C3D           │
│  COLORS      Ride #00659E/#5AADDC  Vehicle #97396D/#D490B6       │
│  (L/D)       Still #546E7A/#90A4AE  Unknown #616161/#9E9E9E     │
│                                                                  │
│  SEMANTIC    Success #146C2E/#88D78A  Warning #8D5000/#FFB776    │
│                                                                  │
│  ACCESSIBILITY  WCAG AA · 48dp targets · 200% font · TalkBack   │
│                 Reduced motion + reduced transparency support     │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

### 0.2 Token Inventory

| Category | Artifact Type | Count | Location |
|----------|---------------|-------|----------|
| **Color tokens** | Palette values (light + dark) | ~70 pairs | `RidgelineColorTokens.kt` |
| **Activity colors** | Mode-adaptive pairs | 6 activities × 2 modes | `RidgelineColorTokens.kt` |
| **Semantic colors** | Success + Warning sets | 2 × 4 tokens × 2 modes | `RidgelineColorTokens.kt` |
| **Glass tokens** | GlassTier enum | 4 tiers (G0–G3) | `GlassSurface.kt` |
| **Typography styles** | Material type scale | 15 styles | `RidgelineTypography.kt` |
| **Spacing tokens** | Dp constants | 10 tokens | `RidgelineSpacing.kt` |
| **Shape tokens** | Corner shapes | 5 scale levels + 3 identity | `RidgelineShapes.kt` |
| **Elevation tokens** | Tonal + shadow pairs | 4 levels | `RidgelineElevation.kt` |
| **Motion tokens** | Spring specs + durations | 6 springs + 7 durations | `RidgelineMotion.kt` |
| **Composable primitives** | Reusable DS composables | 18 | `app/.../ui/designsystem/` |
| **Token objects** | Kotlin objects | 17 | `sutils/.../style/compose/` |
| **TOTAL DS artifacts** | | **35** | |

**25 Material 3 components used AS-IS** (no wrapper): Switch, Checkbox, RadioButton, TextField, OutlinedTextField, DatePicker, DatePickerDialog, TimePicker, Snackbar, SnackbarHost, TooltipBox, PlainTooltip, RichTooltip, ModalBottomSheet, AlertDialog, BasicAlertDialog, LinearProgressIndicator, CircularProgressIndicator, AssistChip, FilterChip, InputChip, SuggestionChip, SegmentedButton, ExposedDropdownMenuBox, DropdownMenuItem.

---

### 0.3 Decision Log — 10 Most Important Locked Decisions

| # | Decision | Value | Rationale |
|---|----------|-------|-----------|
| 1 | **Brand seed color** | `#1B6B3A` Canopy Green | Forest-green/emerald aligns with "adventurous/outdoorsy" brand. Teal was a placeholder. HCT H≈145° C≈48 T≈38 — true green, not blue-green. |
| 2 | **Theme root** | `AppTheme` backed by `MaterialTheme` | Current dependencies do not expose public `MaterialExpressiveTheme` or `MaterialTheme.motionScheme`; consumers still use standard `MaterialTheme` accessors and Ridgeline motion tokens. |
| 3 | **Migration strategy** | Direct replacement, no shim | 100% Compose codebase, all screens use `MaterialTheme.colorScheme.*`. A shim layer would add drift risk with zero benefit. |
| 4 | **Shape language** | Diagonal asymmetry, 3:1 ratio | Terrain-inspired identity. Major corners (TL/BR) at 3× minor (TR/BL). Creates the "ridgeline" visual signature. |
| 5 | **Activity color palette** | Okabe-Ito mode-adaptive | Color-blind friendly (6 hues spanning 164°→327°). Independent of brand seed. Mode-adaptive for WCAG AA in both themes. |
| 6 | **Glass system** | 4-tier GlassTier enum | G0 solid → G3 max blur. Pre-API 31 falls back to opaque surface + border. No noise texture (Compose limitation). |
| 7 | **Typography stack** | Outfit / Inter / Roboto Mono | Outfit for brand headlines, Inter (or system Roboto) for body at zero APK cost, Roboto Mono for metric values with tabular numerals. |
| 8 | **Spacing base** | 4dp grid, 10 tokens | Consistent rhythm from 0dp–48dp. Responsive gutters at 16/24/32dp breakpoints. |
| 9 | **Motion vocabulary** | 6 named springs | Personality-driven: Snap for immediacy, Settle for layout, Respond for feedback, Crest for celebration, Drift for ambient, Surge for drama. |
| 10 | **Accessibility floor** | WCAG AA, 48dp, 200%, TalkBack | Non-negotiable v1 minimum. Reduced motion replaces springs with snaps. Reduced transparency replaces glass with opaque surfaces. |

---

## PART I — TOKENS

---

### 1. Color System

#### 1.1 Brand Seed & Generation Rules

**Canonical seed:** `#1B6B3A` (Canopy Green)

- **HCT coordinates:** Hue ≈ 145°, Chroma ≈ 48, Tone ≈ 38
- **Personality:** Forest canopy, trail markers, topographic maps
- **Generation pipeline:** Run seed through [Material Theme Builder](https://m3.material.io/theme-builder), then hand-audit every foreground/background pair for WCAG AA (4.5:1 normal text, 3:1 large text + UI components)

```kotlin
/**
 * Ridgeline brand seed. All primary/secondary/tertiary/surface tokens
 * are derived from this value via Material 3 HCT color space.
 *
 * Do NOT change this without regenerating the entire palette
 * and re-auditing all WCAG contrast pairs.
 */
val RidgelineSeed = Color(0xFF1B6B3A)
```

**Tertiary direction:** Trail Gold — warm amber at H≈49°, complementary to green seed. Provides warmth for rewards, celebrations, and optional accent metadata.

```kotlin
val TrailGoldLight = Color(0xFF857010)
val TrailGoldDark = Color(0xFFD0B24A)
```

> **Implementation note:** The existing `Color.kt` is aligned to the implemented Canopy Green seed (`#1B6B3A`). Keep docs and token references consistent with this palette.

---

#### 1.2 Primary: Canopy Green

| Token | Light | Dark |
|-------|-------|------|
| `primary` | `#1B6B3A` | `#7BDA97` |
| `onPrimary` | `#FFFFFF` | `#003919` |
| `primaryContainer` | `#98F7B2` | `#005227` |
| `onPrimaryContainer` | `#00210D` | `#98F7B2` |

```kotlin
// Light
val CanopyGreenPrimaryLight = Color(0xFF1B6B3A)
val CanopyGreenOnPrimaryLight = Color(0xFFFFFFFF)
val CanopyGreenPrimaryContainerLight = Color(0xFF98F7B2)
val CanopyGreenOnPrimaryContainerLight = Color(0xFF00210D)

// Dark
val CanopyGreenPrimaryDark = Color(0xFF7BDA97)
val CanopyGreenOnPrimaryDark = Color(0xFF003919)
val CanopyGreenPrimaryContainerDark = Color(0xFF005227)
val CanopyGreenOnPrimaryContainerDark = Color(0xFF98F7B2)
```

**Usage:** Primary actions (FAB, buttons), key interactive elements, accent bar in section headers.
**Do NOT use:** For status indicators (use semantic colors) or activity type badges (use Okabe-Ito palette).

---

#### 1.3 Secondary: Trail Sage

Derived from the green seed's complementary earth-tone direction. Shifts from the prior "Trail Slate" (teal-derived) to a green-complementary muted sage.

| Token | Light | Dark |
|-------|-------|------|
| `secondary` | `#506352` | `#B5CCB6` |
| `onSecondary` | `#FFFFFF` | `#213526` |
| `secondaryContainer` | `#D2E8D3` | `#374B3B` |
| `onSecondaryContainer` | `#0E1F13` | `#D2E8D3` |

```kotlin
val TrailSageSecondaryLight = Color(0xFF506352)
val TrailSageOnSecondaryLight = Color(0xFFFFFFFF)
val TrailSageSecondaryContainerLight = Color(0xFFD2E8D3)
val TrailSageOnSecondaryContainerLight = Color(0xFF0E1F13)

val TrailSageSecondaryDark = Color(0xFFB5CCB6)
val TrailSageOnSecondaryDark = Color(0xFF213526)
val TrailSageSecondaryContainerDark = Color(0xFF374B3B)
val TrailSageOnSecondaryContainerDark = Color(0xFFD2E8D3)
```

**Usage:** Navigation pill indicator, secondary buttons, supporting UI chrome.
**Do NOT use:** For primary actions or data visualization.

---

#### 1.4 Tertiary: Trail Gold

Warm amber/rust complement (H≈49°). Retained from prior "Sunset Rust" intent but shifted to gold to pair with green.

| Token | Light | Dark |
|-------|-------|------|
| `tertiary` | `#857010` | `#D0B24A` |
| `onTertiary` | `#FFFFFF` | `#473A00` |
| `tertiaryContainer` | `#FFDFA0` | `#635200` |
| `onTertiaryContainer` | `#2A2000` | `#FFDFA0` |

```kotlin
val TrailGoldTertiaryLight = Color(0xFF857010)
val TrailGoldOnTertiaryLight = Color(0xFFFFFFFF)
val TrailGoldTertiaryContainerLight = Color(0xFFFFDFA0)
val TrailGoldOnTertiaryContainerLight = Color(0xFF2A2000)

val TrailGoldTertiaryDark = Color(0xFFD0B24A)
val TrailGoldOnTertiaryDark = Color(0xFF473A00)
val TrailGoldTertiaryContainerDark = Color(0xFF635200)
val TrailGoldOnTertiaryContainerDark = Color(0xFFFFDFA0)
```

**Usage:** Reward badges, challenge metadata, celebration accents, optional decorative highlights.
**Do NOT use:** For primary actions, error states, or status indicators.

---

#### 1.5 Error Palette

Standard M3 error tokens, unchanged from Material defaults.

| Token | Light | Dark |
|-------|-------|------|
| `error` | `#BA1A1A` | `#FFB4AB` |
| `onError` | `#FFFFFF` | `#690005` |
| `errorContainer` | `#FFDAD6` | `#93000A` |
| `onErrorContainer` | `#410002` | `#FFDAD6` |

```kotlin
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
```

---

#### 1.6 Neutral & Surface Tokens

Re-derived from the `#1B6B3A` green seed. Dark surfaces carry a subtle green tint (`#101410` base tone).

| Token | Light | Dark |
|-------|-------|------|
| `surface` | `#F7FBF2` | `#101410` |
| `onSurface` | `#181D18` | `#E0E4DB` |
| `surfaceVariant` | `#DCE5D5` | `#414941` |
| `onSurfaceVariant` | `#404942` | `#C0C9BC` |
| `surfaceBright` | `#F7FBF2` | `#363A34` |
| `surfaceDim` | `#D7DBD2` | `#101410` |
| `surfaceTint` | `#1B6B3A` | `#7BDA97` |
| `surfaceContainerLowest` | `#FFFFFF` | `#0B0F0B` |
| `surfaceContainerLow` | `#F1F5EC` | `#1C201B` |
| `surfaceContainer` | `#EBF0E6` | `#202520` |
| `surfaceContainerHigh` | `#E5EAE0` | `#2B2F2A` |
| `surfaceContainerHighest` | `#E0E4DB` | `#353935` |
| `background` | `#F7FBF2` | `#101410` |
| `onBackground` | `#181D18` | `#E0E4DB` |

```kotlin
// Light surfaces — green-neutral tint
val SurfaceLight = Color(0xFFF7FBF2)
val OnSurfaceLight = Color(0xFF181D18)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF1F5EC)
val SurfaceContainerLight = Color(0xFFEBF0E6)
val SurfaceContainerHighLight = Color(0xFFE5EAE0)
val SurfaceContainerHighestLight = Color(0xFFE0E4DB)

// Dark surfaces — green-tinted darks
val SurfaceDark = Color(0xFF101410)
val OnSurfaceDark = Color(0xFFE0E4DB)
val SurfaceContainerLowestDark = Color(0xFF0B0F0B)
val SurfaceContainerLowDark = Color(0xFF1C201B)
val SurfaceContainerDark = Color(0xFF202520)
val SurfaceContainerHighDark = Color(0xFF2B2F2A)
val SurfaceContainerHighestDark = Color(0xFF353935)
```

> **Note:** Dark surface base is `#101410` (green-tinted), not pure `#000000` or neutral `#121212`. This subtly reinforces the forest-green brand identity without compromising readability.

---

#### 1.7 Utility Tokens

| Token | Light | Dark |
|-------|-------|------|
| `outline` | `#717971` | `#8B938A` |
| `outlineVariant` | `#C0C9BC` | `#414941` |
| `inverseSurface` | `#2D322C` | `#E0E4DB` |
| `inverseOnSurface` | `#EEF2E9` | `#2D322C` |
| `inversePrimary` | `#7BDA97` | `#1B6B3A` |
| `scrim` | `#000000` | `#000000` |

```kotlin
val OutlineLight = Color(0xFF717971)
val OutlineVariantLight = Color(0xFFC0C9BC)
val InverseSurfaceLight = Color(0xFF2D322C)
val InverseOnSurfaceLight = Color(0xFFEEF2E9)
val InversePrimaryLight = Color(0xFF7BDA97)
val ScrimLight = Color(0xFF000000)

val OutlineDark = Color(0xFF8B938A)
val OutlineVariantDark = Color(0xFF414941)
val InverseSurfaceDark = Color(0xFFE0E4DB)
val InverseOnSurfaceDark = Color(0xFF2D322C)
val InversePrimaryDark = Color(0xFF1B6B3A)
val ScrimDark = Color(0xFF000000)
```

---

#### 1.8 Extended Semantic Colors: Success & Warning

Not standard M3 tokens. Delivered via `CompositionLocal` or direct reference from `AppColors`.

##### Success

| Token | Light | Dark |
|-------|-------|------|
| `success` | `#146C2E` | `#88D78A` |
| `onSuccess` | `#FFFFFF` | `#003912` |
| `successContainer` | `#A3F4A5` | `#00531E` |
| `onSuccessContainer` | `#002107` | `#A3F4A5` |

##### Warning

| Token | Light | Dark |
|-------|-------|------|
| `warning` | `#8D5000` | `#FFB776` |
| `onWarning` | `#FFFFFF` | `#4A2800` |
| `warningContainer` | `#FFDCC1` | `#6B3D00` |
| `onWarningContainer` | `#2D1600` | `#FFDCC1` |

```kotlin
// Semantic — Success
val SuccessLight = Color(0xFF146C2E)
val OnSuccessLight = Color(0xFFFFFFFF)
val SuccessContainerLight = Color(0xFFA3F4A5)
val OnSuccessContainerLight = Color(0xFF002107)
val SuccessDark = Color(0xFF88D78A)
val OnSuccessDark = Color(0xFF003912)
val SuccessContainerDark = Color(0xFF00531E)
val OnSuccessContainerDark = Color(0xFFA3F4A5)

// Semantic — Warning
val WarningLight = Color(0xFF8D5000)
val OnWarningLight = Color(0xFFFFFFFF)
val WarningContainerLight = Color(0xFFFFDCC1)
val OnWarningContainerLight = Color(0xFF2D1600)
val WarningDark = Color(0xFFFFB776)
val OnWarningDark = Color(0xFF4A2800)
val WarningContainerDark = Color(0xFF6B3D00)
val OnWarningContainerDark = Color(0xFFFFDCC1)

// Delivery via CompositionLocal
data class RidgelineSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    RidgelineSemanticColors(
        success = SuccessLight,
        onSuccess = OnSuccessLight,
        successContainer = SuccessContainerLight,
        onSuccessContainer = OnSuccessContainerLight,
        warning = WarningLight,
        onWarning = OnWarningLight,
        warningContainer = WarningContainerLight,
        onWarningContainer = OnWarningContainerLight,
    )
}
```

**Usage:** Goal completion (success), battery/storage warnings (warning). Never for brand decoration.
**Do NOT use:** Success green as a substitute for primary green — they are semantically distinct.

---

#### 1.9 Contextual Activity Colors (Okabe-Ito)

Accessibility-first palette. **Independent of brand seed** — these never change when the primary color changes. Each activity has light/dark adaptive variants plus a dedicated `onActivity` color per mode.

| Activity | Light | Dark | On-Light | On-Dark | Hue |
|----------|-------|------|----------|---------|-----|
| Walk | `#007051` | `#52C5A6` | `#FFFFFF` | `#002418` | 164° |
| Run | `#A34800` | `#EF8C3D` | `#FFFFFF` | `#2E1500` | 26° |
| Ride | `#00659E` | `#5AADDC` | `#FFFFFF` | `#001D2E` | 202° |
| Vehicle | `#97396D` | `#D490B6` | `#FFFFFF` | `#2A0A1E` | 327° |
| Still | `#546E7A` | `#90A4AE` | `#FFFFFF` | `#0C1F28` | 200° |
| Unknown | `#616161` | `#9E9E9E` | `#FFFFFF` | `#1A1A1A` | — |

```kotlin
object ActivityColors {
    // Light mode
    val WalkLight = Color(0xFF007051)
    val RunLight = Color(0xFFA34800)
    val RideLight = Color(0xFF00659E)
    val VehicleLight = Color(0xFF97396D)
    val StillLight = Color(0xFF546E7A)
    val UnknownLight = Color(0xFF616161)

    // Dark mode
    val WalkDark = Color(0xFF52C5A6)
    val RunDark = Color(0xFFEF8C3D)
    val RideDark = Color(0xFF5AADDC)
    val VehicleDark = Color(0xFFD490B6)
    val StillDark = Color(0xFF90A4AE)
    val UnknownDark = Color(0xFF9E9E9E)

    // On-colors (text/icon on activity background)
    val OnLight = Color(0xFFFFFFFF) // shared for all light-mode backgrounds
    val OnWalkDark = Color(0xFF002418)
    val OnRunDark = Color(0xFF2E1500)
    val OnRideDark = Color(0xFF001D2E)
    val OnVehicleDark = Color(0xFF2A0A1E)
    val OnStillDark = Color(0xFF0C1F28)
    val OnUnknownDark = Color(0xFF1A1A1A)

    // Adaptive accessors
    object Adaptive {
        val Walk: Color @Composable get() = if (isSystemInDarkTheme()) WalkDark else WalkLight
        val Run: Color @Composable get() = if (isSystemInDarkTheme()) RunDark else RunLight
        val Ride: Color @Composable get() = if (isSystemInDarkTheme()) RideDark else RideLight
        val Vehicle: Color @Composable get() = if (isSystemInDarkTheme()) VehicleDark else VehicleLight
        val Still: Color @Composable get() = if (isSystemInDarkTheme()) StillDark else StillLight
        val Unknown: Color @Composable get() = if (isSystemInDarkTheme()) UnknownDark else UnknownLight
    }
}
```

**Usage:** Activity type chips, badges, route polyline colors, session cards.
**Do NOT use:** As general-purpose accent colors. These are reserved for activity type identification only.

---

#### 1.10 Contextual Colors: Track State

| Token | Light | Dark | Usage |
|-------|-------|------|-------|
| `trackActive` | `#FF3B30` | `#FF3B30` | Live recording pulse, FAB active state |
| `trackHistory` | `#00829B` | `#00829B` | Past track lines on map |

```kotlin
val TrackActiveColor = Color(0xFFFF3B30)   // Same in both modes — high urgency
val TrackHistoryColor = Color(0xFF00829B)  // Same in both modes
```

> `trackActive` is intentionally mode-invariant. WCAG AA contrast verified: 4.53:1 against light surface `#F7FBF2`, 5.12:1 against dark surface `#101410`.

---

#### 1.11 Glass & Translucency Tokens

Glass tokens are delivered via the `GlassTier` enum. See §8 (Components) for full composable API.

| Tier | Blur | Light Tint α | Dark Tint α | Light Border α | Dark Border α |
|------|------|-------------|------------|---------------|--------------|
| G0 | 0dp | 1.00 | 1.00 | 0.00 | 0.00 |
| G1 | 10dp | 0.85 | 0.88 | 0.18 | 0.14 |
| G2 | 18dp | 0.78 | 0.82 | 0.24 | 0.18 |
| G3 | 26dp | 0.72 | 0.76 | 0.30 | 0.20 |

```kotlin
enum class GlassTier(
    val blur: Dp,
    val lightTintAlpha: Float,
    val darkTintAlpha: Float,
    val lightBorderAlpha: Float,
    val darkBorderAlpha: Float,
) {
    G0(blur = 0.dp,  lightTintAlpha = 1.00f, darkTintAlpha = 1.00f, lightBorderAlpha = 0.00f, darkBorderAlpha = 0.00f),
    G1(blur = 10.dp, lightTintAlpha = 0.85f, darkTintAlpha = 0.88f, lightBorderAlpha = 0.18f, darkBorderAlpha = 0.14f),
    G2(blur = 18.dp, lightTintAlpha = 0.78f, darkTintAlpha = 0.82f, lightBorderAlpha = 0.24f, darkBorderAlpha = 0.18f),
    G3(blur = 26.dp, lightTintAlpha = 0.72f, darkTintAlpha = 0.76f, lightBorderAlpha = 0.30f, darkBorderAlpha = 0.20f),
}
```

**Topographic contour alpha tokens:**

| Surface | Light | Dark |
|---------|-------|------|
| Cards | 0.05 | 0.07 |
| Empty-state backgrounds | 0.07 | 0.09 |
| Tinted decorative | 0.03 | 0.04 |

**Glass noise:** None. Final decision — no grain textures. Compose `RenderEffect` limitations and accessibility risk outweigh aesthetic benefit.

**Pre-API 31 fallback:** Blur is unavailable. Replace with opaque `surfaceContainer` background + 1dp `outlineVariant` border. Border alpha uses the tier's light/dark value.

**Reduced transparency fallback:** When `LocalReduceTransparency.current == true`, all glass tiers render as opaque `surfaceContainer`. Borders remain. Blur disabled.

---

#### 1.12 Dynamic Color (Monet) Policy

- **Android 12+:** Dynamic color ON by default (`useDynamicColor = true`).
- **Monet scope:** Surface/neutral tokens only (surface, surfaceContainer*, background, onSurface, outline, etc.).
- **Brand-locked (never Monet'd):** All primary, secondary, tertiary, error, success, warning, and activity colors.
- **User toggle:** "Use wallpaper colors" in appearance settings. Defaults ON on Android 12+. Hidden pre-12.

```kotlin
val colorScheme = remember(useDynamicColor, darkTheme, context) {
    when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> ridgelineDarkColorScheme()
        else -> ridgelineLightColorScheme()
    }
}
```

---

#### 1.13 Color Token Validation Checklist

Every token pair must pass before shipping:

| Check | Criterion | Tool |
|-------|-----------|------|
| Normal text (< 18sp bold / < 24sp) | ≥ 4.5:1 contrast | Accessibility Scanner |
| Large text (≥ 18sp bold / ≥ 24sp) | ≥ 3.0:1 contrast | Manual spot-check |
| UI components (icons, borders) | ≥ 3.0:1 contrast | Manual spot-check |
| Activity colors on chip backgrounds | ≥ 4.5:1 in both modes | Per-color audit |
| trackActive on surface | ≥ 4.5:1 in both modes | Fixed: 4.53:1 / 5.12:1 |
| Glass text readability | ≥ 4.5:1 even at G3 | Worst-case backdrop test |

---

### 2. Typography System

#### 2.1 Typeface Roles

| Role | Typeface | Rationale |
|------|----------|-----------|
| Display / Headline / Title | **Outfit** (Google Fonts) | Geometric sans-serif. Distinctive personality for brand-level type. Variable weight 300–700. |
| Body / Label | **Inter** (or system Roboto) | Neutral, highly legible at small sizes. Inter preferred; system Roboto as zero-APK-cost fallback. |
| Metrics (live data) | **Roboto Mono** (`FontFamily.Monospace`) | Fixed-width digits prevent layout shift during live tracking. System-bundled, zero APK cost. |

```kotlin
// Font family declarations — replace FontFamily.Default with actual font resources
val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

// Inter (if bundled) or system Roboto fallback
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
)
// Fallback: val InterFontFamily = FontFamily.Default

val MetricsFontFamily = FontFamily.Monospace // Roboto Mono, system-bundled
```

---

#### 2.2 Full Type Scale (15 Styles)

| Style | Family | Weight | Size | Line Height | Letter Spacing |
|-------|--------|--------|------|-------------|----------------|
| Display Large | Outfit | Bold (700) | 64sp | 72sp | −0.25sp |
| Display Medium | Outfit | Bold (700) | 52sp | 60sp | −0.25sp |
| Display Small | Outfit | Bold (700) | 44sp | 52sp | 0sp |
| Headline Large | Outfit | SemiBold (600) | 36sp | 44sp | 0sp |
| Headline Medium | Outfit | SemiBold (600) | 32sp | 40sp | 0sp |
| Headline Small | Outfit | SemiBold (600) | 28sp | 36sp | 0sp |
| Title Large | Outfit | Medium (500) | 22sp | 28sp | 0sp |
| Title Medium | Outfit | Medium (500) | 18sp | 24sp | 0.15sp |
| Title Small | Outfit | Medium (500) | 14sp | 20sp | 0.1sp |
| Body Large | Inter | Regular (400) | 16sp | 24sp | 0.5sp |
| Body Medium | Inter | Regular (400) | 14sp | 20sp | 0.25sp |
| Body Small | Inter | Regular (400) | 12sp | 16sp | 0.4sp |
| Label Large | Inter | Medium (500) | 14sp | 20sp | 0.1sp |
| Label Medium | Inter | Medium (500) | 12sp | 16sp | 0.5sp |
| Label Small | Inter | Medium (500) | 11sp | 16sp | 0.5sp |

```kotlin
val RidgelineTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 64.sp,
        lineHeight = 72.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
```

---

#### 2.3 Metrics Font: Monospace

**Font:** `FontFamily.Monospace` (Roboto Mono on Android — zero APK cost, system-bundled)

**Scope — USE for:**
- Distance, speed, pace, elevation, step count as primary dashboard values
- Live timer displays (`HH:mm:ss`)
- Any numeric value that updates in real-time during tracking

**Scope — DO NOT use for:**
- Labels ("Distance", "Duration")
- Timestamps in lists or cards
- List item counts, badge numbers
- Body text that happens to contain numbers

```kotlin
// MetricText composable applies monospace automatically
@Composable
fun MetricText(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.displaySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
    ),
    unitStyle: TextStyle = MaterialTheme.typography.titleMedium,
)
```

---

#### 2.4 Tabular Figures (`tnum`)

All numeric data in non-monospace text must use tabular figures to prevent layout shift when values change.

```kotlin
// Apply to any Text with changing numbers
Text(
    text = formattedValue,
    style = MaterialTheme.typography.bodyLarge.copy(
        fontFeatureSettings = "tnum",
    ),
)
```

**When to use:** Session counts, statistics values, progress percentages — anywhere proportional-width digits would cause adjacent text to jump during updates.

---

#### 2.5 Czech Diacritic & i18n Validation

The app supports EN + CS (Czech) minimum. Czech diacritics (ř, ž, ů, ď, ť, ň) include tall ascenders and descenders that can clip in tight line heights.

**Validation rules:**
- All type scale styles must render Czech pangram without clipping: "Příliš žluťoučký kůň úpěl ďábelské ódy"
- Line heights are already generous (e.g., 24sp for 16sp body) — no expected issues, but must verify in previews
- Test at both 100% and 200% font scale

---

#### 2.6 Text Scaling (200%) Behavior

- **No `maxFontSize`.** Users who set 200% need it — never cap scaling.
- **Metric hero stepping:** At 200%, Display sizes become enormous. Use `onTextLayout` to detect overflow and step down: Display → Headline as floor. Never below Headline.
- **All content must remain scrollable** at 200%. No fixed-height containers that clip text.

---

### 3. Spacing System

#### 3.1 Scale (10 Tokens, 4dp Base)

| Token | Value | Kotlin | Primary Use |
|-------|-------|--------|-------------|
| `None` | 0dp | `RidgelineSpacing.None` | Explicit zero-spacing |
| `Xxs` | 2dp | `RidgelineSpacing.Xxs` | Divider margins, icon-to-badge offset |
| `Xs` | 4dp | `RidgelineSpacing.Xs` | Chip internals, badge padding, inline tags |
| `Sm` | 8dp | `RidgelineSpacing.Sm` | Icon-to-text in row, list `spacedBy`, within-card gaps |
| `Md` | 12dp | `RidgelineSpacing.Md` | Section header icon gaps, card internal column spacing |
| `Lg` | 16dp | `RidgelineSpacing.Lg` | **Default page gutter**, card padding, between-item padding |
| `Xl` | 20dp | `RidgelineSpacing.Xl` | Hero card padding, section vertical grouping |
| `Xxl` | 24dp | `RidgelineSpacing.Xxl` | Section break above headers, button horizontal padding |
| `Xxxl` | 32dp | `RidgelineSpacing.Xxxl` | Major section gaps, dialog padding |
| `Xxxxl` | 48dp | `RidgelineSpacing.Xxxxl` | Touch target minimum, between major screen regions |

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

**Do:** Always use token references. Never write `16.dp` inline — write `RidgelineSpacing.Lg`.
**Do NOT:** Invent intermediate values (e.g., 6dp, 10dp, 28dp). If the scale doesn't fit, choose the nearest token.

---

#### 3.2 Responsive Page Gutters

Horizontal page margin adapts to window width class:

| Window Width | Gutter | Token |
|-------------|--------|-------|
| < 600dp (compact) | 16dp | `RidgelineSpacing.Lg` |
| 600–839dp (medium) | 24dp | `RidgelineSpacing.Xxl` |
| ≥ 840dp (expanded) | 32dp | `RidgelineSpacing.Xxxl` |

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

**Full-bleed exceptions:** Map content, bottom sheet container, TopAppBar background, floating navigation bar.

---

#### 3.3 Semantic Pairing Rules

| Relationship | Token | Example |
|-------------|-------|---------|
| Between siblings in tight group | `Xs` (4dp) | Icon and badge, chip row items |
| Between elements in a component | `Sm` (8dp) | Icon-to-text, list `spacedBy` |
| Sub-sections within a component | `Md` (12dp) | Card internal column spacing |
| Component internal padding | `Lg` (16dp) | Card content padding (default) |
| Hero component internal padding | `Xl` (20dp) | Featured card padding |
| Between sections / above headers | `Xxl` (24dp) | Section header top margin |
| Between major screen regions | `Xxxl` (32dp) | Top content to first card |
| Minimum touch target dimension | `Xxxxl` (48dp) | Buttons, tappable rows |

---

#### 3.4 Bottom Clearance

```kotlin
object AppDimensions {
    /** Space to reserve at bottom of scrollable content for the floating nav bar. */
    val FloatingNavBarClearance = 120.dp
}
```

Apply via `Scaffold` content padding or explicit `Spacer`:
```kotlin
Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
) { padding ->
    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + AppDimensions.FloatingNavBarClearance,
            start = RidgelineGutters.horizontal,
            end = RidgelineGutters.horizontal,
        ),
    ) { /* items */ }
}
```

---

### 4. Shape System

#### 4.1 Diagonal Shape Scale (L1–L5)

All levels follow the **3:1 major:minor ratio** — the "ridgeline" diagonal signature. Major corners are top-start and bottom-end; minor corners are top-end and bottom-start. This creates opposing-corner symmetry reminiscent of terrain contour lines.

| Level | M3 Slot | Major (TL/BR) | Minor (TR/BL) | Use |
|-------|---------|---------------|----------------|-----|
| L1 | `extraSmall` | 6dp | 2dp | Chips, badges, inline tags |
| L2 | `small` | 10dp | 3dp | Small cards, list items, toggles |
| L3 | `medium` | 14dp | 4dp | Standard cards, dialogs body, sheets |
| L4 | `large` | 20dp | 6dp | Feature cards, expanded panels |
| L5 | `extraLarge` | 24dp | 8dp | Hero cards, full-width banners |

```kotlin
val RidgelineShapes = Shapes(
    extraSmall = RoundedCornerShape(        // L1
        topStart = 6.dp,
        topEnd = 2.dp,
        bottomEnd = 6.dp,
        bottomStart = 2.dp,
    ),
    small = RoundedCornerShape(             // L2
        topStart = 10.dp,
        topEnd = 3.dp,
        bottomEnd = 10.dp,
        bottomStart = 3.dp,
    ),
    medium = RoundedCornerShape(            // L3
        topStart = 14.dp,
        topEnd = 4.dp,
        bottomEnd = 14.dp,
        bottomStart = 4.dp,
    ),
    large = RoundedCornerShape(             // L4
        topStart = 20.dp,
        topEnd = 6.dp,
        bottomEnd = 20.dp,
        bottomStart = 6.dp,
    ),
    extraLarge = RoundedCornerShape(        // L5
        topStart = 24.dp,
        topEnd = 8.dp,
        bottomEnd = 24.dp,
        bottomStart = 8.dp,
    ),
)
```

**Usage:** Access via `MaterialTheme.shapes.medium`, etc. Never construct `RoundedCornerShape(...)` inline in feature code.

**RTL behavior:** Shapes auto-mirror when `LayoutDirection.Rtl` is active. `topStart` becomes the physical top-right, preserving the leading-edge emphasis.

---

#### 4.2 Identity Shapes

Identity shapes use **percentage-based** corners and are NOT part of the 5-level scale. They serve specific semantic roles.

##### WaypointShape (FAB, waypoint markers)

Asymmetrical pin-drop silhouette: three rounded corners, one flattened to suggest a GPS waypoint marker.

```kotlin
val WaypointShape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 50,
    bottomEndPercent = 10,
    bottomStartPercent = 50,
)
```

**Usage:** `TrackingFAB` container shape only.
**Do NOT use:** For cards, buttons, or containers.

##### MomentumPillShape (primary action buttons, chips)

Asymmetric stadium: trailing edge more rounded than leading, suggesting forward motion.

```kotlin
val MomentumPillShape = RoundedCornerShape(
    topStartPercent = 20,
    topEndPercent = 50,
    bottomEndPercent = 50,
    bottomStartPercent = 20,
)
```

**Usage:** `PrimaryActionButton`, selected filter chips, navigation pill indicator.
**Do NOT use:** For cards or containers.

##### TerrainCardShape (cards — legacy alias)

The original card shape before the 5-level scale was introduced. Equivalent to the scale's DNA but using percentages.

```kotlin
val TerrainCardShape = RoundedCornerShape(
    topStartPercent = 15,
    topEndPercent = 4,
    bottomEndPercent = 15,
    bottomStartPercent = 4,
)
```

> **Migration note:** Prefer `MaterialTheme.shapes.medium` (L3) or `.large` (L4) over direct `TerrainCardShape` reference. `TerrainCardShape` is retained for backward compatibility but new code should use the scale.

---

#### 4.3 Bottom Sheet Shape

Asymmetric top corners only. Bottom corners are square (sheet extends to screen bottom).

```kotlin
val BottomSheetShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 6.dp,
    bottomEnd = 0.dp,
    bottomStart = 0.dp,
)
```

**Usage:** `ModalBottomSheet(shape = BottomSheetShape)`.

---

#### 4.4 Dialog Shape

**28dp uniform radius.** Exempt from the diagonal asymmetry system — dialogs are system-level, modal, and must feel neutral and grounded.

```kotlin
val DialogShape = RoundedCornerShape(28.dp)
```

**Usage:** `AlertDialog`, `BasicAlertDialog`, `DatePickerDialog`.
**Do NOT apply:** Diagonal shapes to dialogs.

---

### 5. Elevation System

#### 5.1 Elevation Levels (E0–E3)

Ridgeline uses a **tonal-first** elevation strategy. `tonalElevation` drives surface tint overlay (M3 built-in); `shadowElevation` is a subtle supplement for light mode edge definition.

| Level | Name | Tonal | Shadow | Use |
|-------|------|-------|--------|-----|
| E0 | Flat | 0dp | 0dp | Background content, pressed states, disabled surfaces |
| E1 | Raised | 1dp | 1dp | Cards at rest, list items, chips |
| E2 | Floating | 2dp | 2dp | Active tracking card, metric cards, FAB resting |
| E3 | Overlay | 6dp | 6dp | Floating nav bar, bottom sheets, dialogs, dragged cards |

```kotlin
object RidgelineElevation {
    val Flat = ElevationPair(tonal = 0.dp, shadow = 0.dp)
    val Raised = ElevationPair(tonal = 1.dp, shadow = 1.dp)
    val Floating = ElevationPair(tonal = 2.dp, shadow = 2.dp)
    val Overlay = ElevationPair(tonal = 6.dp, shadow = 6.dp)
}

data class ElevationPair(val tonal: Dp, val shadow: Dp)
```

**Usage in composables:**
```kotlin
Surface(
    tonalElevation = RidgelineElevation.Raised.tonal,
    shadowElevation = RidgelineElevation.Raised.shadow,
    // ...
)
```

---

#### 5.2 State-Driven Elevation

| State | Level | Rationale |
|-------|-------|-----------|
| Default / at rest | E1 (Raised) | Cards float above background |
| Pressed | E0 (Flat) | Sinks into surface on tap |
| Dragged | E3 (Overlay) | Maximum lift during reorder |
| Active tracking | E2 (Floating) | Prominent but not overlay |
| Disabled | E0 (Flat) | Merges with background |

---

#### 5.3 Dark Mode Edge Strategy

Drop shadows are invisible on dark surfaces. Ridgeline handles this with:

1. **Tonal elevation** — M3's `surfaceColorAtElevation()` auto-applies `surfaceTint` as overlay at higher levels. No custom code needed.
2. **Glass border** — 1dp `outlineVariant` border at the tier's `darkBorderAlpha`. Provides edge definition without artificial shadows.
3. **Content contrast** — text/icons on elevated surfaces provide sufficient visual separation.

**Do NOT:** Add manual borders or gradient edges to compensate for invisible dark-mode shadows.

---

### 6. Motion System

#### 6.1 Named Springs (6 Personalities)

Each spring encodes a specific personality trait of the Ridgeline motion language.

| Name | Stiffness | Damping | Character | Use |
|------|-----------|---------|-----------|-----|
| **Snap** | 1500f | 0.75f | Immediate, decisive | Tap feedback, toggles, icon swaps, privacy controls |
| **Settle** | 400f | 1.0f | Smooth, no overshoot | Layout shifts, card repositioning, list reorder |
| **Respond** | 800f | 0.82f | Quick, tiny overshoot | Value counters, metric updates, progress changes |
| **Crest** | 300f | 0.55f | Celebratory, noticeable bounce | Milestone pops, goal completion, achievement reveals |
| **Drift** | 50f | 1.0f | Slow, dreamy | Empty state float, background parallax, ambient loops |
| **Surge** | 180f | 0.58f | Theatrical, slow + bouncy | State transitions (idle↔tracking), FAB morph |

```kotlin
object RidgelineMotion {

    /** Immediate, decisive. Tap feedback, toggles, icon swaps. */
    val Snap: SpringSpec<Float> = spring(
        dampingRatio = 0.75f,
        stiffness = 1500f,
    )

    /** Smooth, no overshoot. Layout shifts, card repositioning. */
    val Settle: SpringSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 400f,
    )

    /** Quick settle, tiny overshoot. Value counters, metric updates. */
    val Respond: SpringSpec<Float> = spring(
        dampingRatio = 0.82f,
        stiffness = 800f,
    )

    /** Celebratory, noticeable bounce. Milestone pops, goal completion. */
    val Crest: SpringSpec<Float> = spring(
        dampingRatio = 0.55f,
        stiffness = 300f,
    )

    /** Slow drift, dreamy. Empty state float, background parallax. */
    val Drift: SpringSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 50f,
    )

    /** Theatrical, dramatic. State transitions (idle↔tracking), FAB morph. */
    val Surge: SpringSpec<Float> = spring(
        dampingRatio = 0.58f,
        stiffness = 180f,
    )
}
```

**Generic variants** for non-Float types:

```kotlin
// Use inline reified helper for type-safe spring access
inline fun <reified T> RidgelineMotion.snap(): SpringSpec<T> = spring(
    dampingRatio = 0.75f,
    stiffness = 1500f,
)

inline fun <reified T> RidgelineMotion.settle(): SpringSpec<T> = spring(
    dampingRatio = 1.0f,
    stiffness = 400f,
)

// ... same pattern for all 6
```

---

#### 6.2 Duration Tokens

For tween-based animations where springs are not appropriate (e.g., sequential choreography, fade-only transitions).

| Token | Value | Use |
|-------|-------|-----|
| `INSTANT_MS` | 50ms | Immediate state swap, progress bar micro-step |
| `QUICK_MS` | 150ms | Label fade, icon swap, nav label transition |
| `STANDARD_MS` | 300ms | Default tween, color transitions |
| `EMPHASIZED_MS` | 500ms | FAB enter, emphasized reveals |
| `EXPRESSIVE_MS` | 800ms | Goal ring celebration, complex choreography |
| `AMBIENT_MS` | 2000ms | Background pulse, ambient float cycle |
| `BACKGROUND_LOOP_MS` | 4000ms | Infinite ambient loops (topo drift, etc.) |

```kotlin
object RidgelineDurations {
    const val INSTANT_MS = 50
    const val QUICK_MS = 150
    const val STANDARD_MS = 300
    const val EMPHASIZED_MS = 500
    const val EXPRESSIVE_MS = 800
    const val AMBIENT_MS = 2000
    const val BACKGROUND_LOOP_MS = 4000
}
```

**Tween helpers:**

```kotlin
fun <T> tweenQuick(): AnimationSpec<T> = tween(RidgelineDurations.QUICK_MS, easing = FastOutSlowInEasing)
fun <T> tweenStandard(): AnimationSpec<T> = tween(RidgelineDurations.STANDARD_MS, easing = FastOutSlowInEasing)
fun <T> tweenEmphasized(): AnimationSpec<T> = tween(RidgelineDurations.EMPHASIZED_MS, easing = FastOutSlowInEasing)
fun <T> tweenExpressive(): AnimationSpec<T> = tween(RidgelineDurations.EXPRESSIVE_MS, easing = FastOutSlowInEasing)
```

---

#### 6.3 MotionScheme Integration

The current Material 3 dependency does not expose public `MaterialTheme.motionScheme` APIs to Kotlin callers. `MaterialExpressiveTheme` is not present in the resolved artifacts, so `AppTheme` stays on `MaterialTheme` while feature code uses public Ridgeline motion tokens:

```kotlin
val spatialDefault = ridgelineSettle<Float>()
val spatialFast = ridgelineSnap<Float>()
val spatialSlow = ridgelineDrift<Float>()

val effectsFast = tweenQuick<Float>()
val effectsDefault = tweenStandard<Float>()
val effectsSlow = tweenEmphasized<Float>()
```

**Rule:** Use Ridgeline motion tokens for standard component transitions and app-specific animations (tracking state, celebration, metric update) until public MotionScheme APIs are available. Keep `infiniteRepeatable` animations custom.

---

#### 6.4 Card Stagger Entrance

Cards enter with staggered fade + vertical translate.

| Parameter | Value |
|-----------|-------|
| Stagger delay | 60ms per card |
| Max depth | 6 cards (cards 7+ enter simultaneously with card 6) |
| Fade | 0f → 1f |
| Translate Y | 24dp → 0dp |
| Spring | `Settle` (400f, 1.0 damping) |

```kotlin
@Composable
fun StaggeredCardEntrance(
    index: Int,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val cappedIndex = index.coerceAtMost(5) // cap at 6th card (index 5)
    val delay = if (reducedMotion) 0 else cappedIndex * 60

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delay.toLong())
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else RidgelineMotion.Settle,
    )
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 24.dp,
        animationSpec = if (reducedMotion) snap() else RidgelineMotion.Settle,
    )

    Box(modifier = Modifier.alpha(alpha).offset(y = offsetY)) {
        content()
    }
}
```

---

#### 6.5 FAB Choreography

The TrackingFAB uses asymmetric in/out timing for drama.

| Transition | Spec | Duration |
|------------|------|----------|
| **Enter** (appear) | `Surge` spring (180f, 0.58 damping) | ~500ms to settle |
| **Exit** (dismiss) | `tween(150ms, FastOutSlowInEasing)` | 150ms sharp |
| **Idle → Active** | `Surge` spring + color crossfade | ~500ms |
| **Active → Paused** | `Snap` spring (icon swap only) | ~100ms |
| **Paused → Active** | `Respond` spring (icon + color resume) | ~200ms |

```kotlin
// FAB enter animation
val fabScale by animateFloatAsState(
    targetValue = if (showFab) 1f else 0f,
    animationSpec = if (showFab) RidgelineMotion.Surge else tween(150, easing = FastOutSlowInEasing),
)
```

---

#### 6.6 Bottom Sheet Springs

| Phase | Damping | Stiffness | Notes |
|-------|---------|-----------|-------|
| Expand | 0.85f | 600f | Controlled, no oscillation on fast flings |
| Dismiss | 1.0f | 600f | Critically damped, swift exit |
| Scrim fade | Synced with sheet | — | Alpha tracks sheet progress linearly |

```kotlin
val sheetExpandSpring: SpringSpec<Float> = spring(
    dampingRatio = 0.85f,
    stiffness = 600f,
)

val sheetDismissSpring: SpringSpec<Float> = spring(
    dampingRatio = 1.0f,
    stiffness = 600f,
)
```

---

#### 6.7 Goal Ring Celebration

3-phase 800ms sequence triggered when progress crosses 1.0f for the first time.

| Phase | Duration | Animation |
|-------|----------|-----------|
| 1. Color bloom | 120ms | Ring color transitions from `primary` → `success` via `Snap` spring |
| 2. Scale pulse | 220ms | Ring scales 1.0 → 1.15 → 1.0 via `Crest` spring |
| 3. Check reveal | 180ms | Checkmark icon fades in + scales from 0.6 → 1.0 |
| **Total** | ~520ms | Remaining 280ms is settle time |

```kotlin
// Phase 1: Color bloom
val ringColor by animateColorAsState(
    targetValue = if (completed) semanticColors.success else MaterialTheme.colorScheme.primary,
    animationSpec = RidgelineMotion.Snap,
)

// Phase 2: Scale pulse (triggered after phase 1 settles)
val ringScale by animateFloatAsState(
    targetValue = if (pulsing) 1.15f else 1.0f,
    animationSpec = RidgelineMotion.Crest,
)

// Phase 3: Check reveal
val checkAlpha by animateFloatAsState(
    targetValue = if (showCheck) 1f else 0f,
    animationSpec = tween(180, easing = FastOutSlowInEasing),
)
```

**Over-achievement:** When `progress > 1.0f`, draw an overlay arc in `tertiary` (Trail Gold) color past the full circle. No additional celebration — the visual overflow speaks for itself.

---

#### 6.8 Screen Transitions

| Transition | Spec |
|------------|------|
| Forward navigation | Fade 300ms + slide-in-right 15% width |
| Back navigation | Predictive back gesture (system) |
| Map ↔ content | Fade-only 300ms (no slide — map is spatial) |
| Trip detail open | Vertical slide-up 400ms + fade |

---

#### 6.9 Reduced Motion Behavior

When `LocalReducedMotion.current == true`:

| Normal | Reduced |
|--------|---------|
| All springs | `snap()` — instant transition |
| Card stagger | All cards appear simultaneously |
| FAB enter/exit | Instant show/hide |
| Goal ring celebration | Instant color change, no pulse |
| Recording dot pulse | Static dot (no `infiniteRepeatable`) |
| Topo contour drift | Hidden entirely |
| Background ambient loops | Disabled |
| Glass blur | **Retained** (blur is not an animation) |
| Screen transitions | Cut (no slide/fade) |

```kotlin
// Pattern: always check LocalReducedMotion before animating
val reducedMotion = LocalReducedMotion.current

val animatedValue by animateFloatAsState(
    targetValue = targetValue,
    animationSpec = if (reducedMotion) snap() else RidgelineMotion.Respond,
)
```

---

#### 6.10 Loading Motion

Skeleton/placeholder animation during data loads.

```kotlin
object LoadingMotion {
    val EnterDuration = RidgelineDurations.STANDARD_MS   // 300ms
    val ExitDuration = RidgelineDurations.QUICK_MS       // 150ms
    val PulseDuration = 1200                              // ms, full shimmer cycle
    const val PulseAlphaMin = 0.08f
    const val PulseAlphaMax = 0.16f
}
```

**Reduced motion:** Loading pulse becomes static at `PulseAlphaMax` (0.16f).

---


---

## PART II — COMPONENTS (Composables)

> Component APIs, signatures, key parameters, usage examples, and do/don’t rules.
> All components consume tokens from Part I via `MaterialTheme` accessors.

---


#### 7. AppTheme Setup
Ridgeline uses a single composition root that always applies `AppTheme`, backed by Material 3 `MaterialTheme`, and provides runtime accessibility locals for motion/transparency.

##### 7.1 Composable Signature
```kotlin
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalReduceTransparency = staticCompositionLocalOf { false }

@Composable
fun AppTheme(
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = remember(useDynamicColor, darkTheme, context) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> ridgelineDarkColorScheme()
            else -> ridgelineLightColorScheme()
        }
    }

    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalReduceTransparency provides reduceTransparency,
    ) {
        MaterialTheme(
            colorScheme = baseScheme,
            typography = RidgelineTypography,
            shapes = RidgelineShapes,
            content = content,
        )
    }
}
```

##### 7.2 Key Parameters
- `useDynamicColor`: Monet surfaces on Android 12+; Ridgeline brand accents remain locked to Canopy Green intent.
- `darkTheme`: explicit override for previews/tests and manual theme setting.
- `reducedMotion`: disables spring-driven motion and infinite loops.
- `reduceTransparency`: disables blur and switches glass to opaque surface containers.

##### 7.3 Usage Example
```kotlin
@Composable
fun AppRoot() {
    val prefs by settingsRepository.uiPreferences.collectAsStateWithLifecycle()
    AppTheme(
        useDynamicColor = prefs.useDynamicColor,
        darkTheme = prefs.darkTheme.isDark(),
        reducedMotion = prefs.reduceMotion,
        reduceTransparency = prefs.reduceTransparency,
    ) {
        RidgelineNavHost()
    }
}
```

##### 7.4 Do / Don't
- **Do:** wrap every feature preview/screen in `AppTheme`.
- **Do:** read `LocalReducedMotion` and `LocalReduceTransparency` inside composables that animate or blur.
- **Don't:** apply per-screen `MaterialTheme` overrides.
- **Don't:** bypass `AppTheme` in tests; use the same root for parity.

---

#### 8. GlassSurface
`GlassSurface` is the base primitive for all frosted surfaces. It standardizes blur, tint, border, and accessibility fallback across G0–G3.

##### 8.1 Composable Signature
```kotlin
enum class GlassTier(
    val blur: Dp,
    val lightTintAlpha: Float,
    val darkTintAlpha: Float,
    val lightBorderAlpha: Float,
    val darkBorderAlpha: Float,
) {
    G0(0.dp, 1.00f, 1.00f, 0.00f, 0.00f),
    G1(10.dp, 0.85f, 0.88f, 0.18f, 0.14f),
    G2(18.dp, 0.78f, 0.82f, 0.24f, 0.18f),
    G3(26.dp, 0.72f, 0.76f, 0.30f, 0.20f),
}

@Composable
fun GlassSurface(
    tier: GlassTier,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    tonalElevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
)
```

##### 8.2 Key Parameters
- `tier`: visual intensity (`G0` opaque, `G3` strongest blur).
- `shape`: defaults to Ridgeline diagonal shape from `MaterialTheme.shapes`.
- `tonalElevation`: M3 tonal layering in addition to glass tint.

##### 8.3 Fallback Strategy
```kotlin
@Composable
private fun Modifier.ridgelineGlass(
    tier: GlassTier,
): Modifier {
    val dark = isSystemInDarkTheme()
    val reduceTransparency = LocalReduceTransparency.current

    if (reduceTransparency || tier == GlassTier.G0) {
        return background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
    }

    val tintAlpha = if (dark) tier.darkTintAlpha else tier.lightTintAlpha
    val borderAlpha = if (dark) tier.darkBorderAlpha else tier.lightBorderAlpha

    return this
        .hazeEffect(state = rememberHazeState(), style = HazeStyle(blurRadius = tier.blur))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = tintAlpha), MaterialTheme.shapes.large)
        .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha),
            shape = MaterialTheme.shapes.large,
        )
}
```

##### 8.4 Usage Example
```kotlin
GlassSurface(tier = GlassTier.G2, modifier = Modifier.fillMaxWidth()) {
    Text("Today", modifier = Modifier.padding(16.dp))
}
```

##### 8.5 Do / Don't
- **Do:** use `G3` only for floating navigation and high-priority overlays.
- **Do:** keep glass noise at **none**; no grain textures.
- **Don't:** stack multiple `GlassSurface` layers in one z-plane.
- **Don't:** use glass for destructive dialogs; use opaque `surfaceContainerHighest`.

---

#### 9. GlassCard
`GlassCard` is a convenience wrapper over `GlassSurface` with Ridgeline card defaults and mandatory 16dp content padding.

##### 9.1 Composable Signature
```kotlin
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.G2,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(tier = tier, modifier = modifier, shape = shape) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}
```

##### 9.2 Key Parameters
- `tier`: default `G2` for cards.
- `contentPadding`: locked default 16dp (`RidgelineSpacing.Lg`).
- `shape`: diagonal card shape from token scale.

##### 9.3 Usage Example
```kotlin
GlassCard {
    MetricText(value = "12.43", unit = "km")
    Text("Distance", style = MaterialTheme.typography.labelLarge)
}
```

##### 9.4 Do / Don't
- **Do:** keep topographic pattern alpha at 0.05f on cards.
- **Do:** prefer `GlassCard` over custom `Card` for dashboard/stat surfaces.
- **Don't:** remove default 16dp padding for standard metric cards.
- **Don't:** put scrolling containers inside nested glass cards.

---

#### 10. Navigation
Ridgeline navigation uses a floating glass bar with 80dp height, G3 treatment, and a pill indicator.

##### 10.1 FloatingNavigationBar Signature
```kotlin
@Immutable
data class RidgelineNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

@Composable
fun RidgelineFloatingNavigationBar(
    items: List<RidgelineNavItem>,
    selectedRoute: String,
    onItemSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```

##### 10.2 Spec
- Height: `80.dp`
- Horizontal padding: `16.dp`
- Container: `GlassTier.G3`
- Corner radius: `32.dp`
- Selected indicator: pill (`height=32.dp`, horizontal inset 8dp)
- Label visibility: selected item only

##### 10.3 Usage Example
```kotlin
RidgelineFloatingNavigationBar(
    items = navItems,
    selectedRoute = currentRoute,
    onItemSelected = navController::navigateSingleTopTo,
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = 16.dp, vertical = 12.dp),
)
```

##### 10.4 Icon States
- Selected: filled icon, `onSecondaryContainer` tint, label visible.
- Unselected: outlined icon, `onSurfaceVariant` tint, no label.
- Disabled (rare): `alpha = 0.38f`, no indicator.

##### 10.5 Do / Don't
- **Do:** keep one active destination.
- **Do:** preserve 120dp bottom clearance in scroll content.
- **Don't:** show all labels in steady state.
- **Don't:** place nav bar flush to screen edge without 16dp margin.

---

#### 11. TopAppBar
Two approved variants: standard and collapsing.

##### 11.1 Standard TopAppBar Signature
```kotlin
@Composable
fun RidgelineTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
)
```

##### 11.2 Collapsing TopAppBar Signature
```kotlin
@Composable
fun RidgelineCollapsingTopAppBar(
    title: String,
    subtitle: String? = null,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
)
```

##### 11.3 Key Parameters
- `title`: required screen title, always localized.
- `subtitle`: optional context line for collapsing variant only.
- `scrollBehavior`: required for collapsing behavior and nested scroll sync.
- `navigationIcon` / `actions`: optional app chrome slots.

##### 11.4 Usage Example
```kotlin
val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
        RidgelineCollapsingTopAppBar(
            title = "Statistics",
            subtitle = "Last 30 days",
            scrollBehavior = scrollBehavior,
        )
    },
) { padding ->
    StatisticsContent(Modifier.padding(padding))
}
```

##### 11.5 Do / Don't
- **Do:** use standard bar for utility screens.
- **Do:** use collapsing only for dense analytic screens.
- **Don't:** animate title size manually; rely on M3 behavior.
- **Don't:** place primary CTA in app bar when a page already has primary FAB/action button.

---

#### 12. Cards
Ridgeline card set is limited to `MetricCard`, `ChallengeCard`, and `SessionCard`.

##### 12.1 MetricCard
```kotlin
@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    trend: String? = null,
)
```
- Use for compact KPI values.
- Default container: `GlassCard(tier = G2)`.

##### 12.2 ChallengeCard
```kotlin
@Composable
fun ChallengeCard(
    title: String,
    progress: Float,
    goalText: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
)
```
- Includes goal ring + supportive copy.
- Use tertiary accent (`Trail Gold`) for optional reward metadata only.

##### 12.3 SessionCard
```kotlin
@Composable
fun SessionCard(
    date: String,
    distance: String,
    duration: String,
    activityType: ActivityType,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
)
```
- Primary list entity for history.
- Activity chip uses Okabe-Ito adaptive palette.

##### 12.4 Key Parameters
- `MetricCard`: `label`, `value`, and `unit` are required; `trend` is optional delta text.
- `ChallengeCard`: `progress` is normalized `0f..1f` before completion, `>1f` for over-achievement visuals.
- `SessionCard`: pass preformatted `distance` and `duration` strings; `activityType` drives chip color/icon.

##### 12.5 Usage Example
```kotlin
LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item { MetricCard(label = "Distance", value = "8.4", unit = "km") }
    item { ChallengeCard(title = "Climb 500m", progress = 0.72f, goalText = "360 / 500 m", onClick = {}) }
    item { SessionCard(date = "Today", distance = "3.2 km", duration = "00:21:44", activityType = ActivityType.RUN, onClick = {}) }
}
```

##### 12.6 Do / Don't
- **Do:** keep card density consistent (`12.dp` vertical spacing between cards).
- **Do:** keep primary value above supporting metadata.
- **Don't:** create additional card archetypes without governance approval.
- **Don't:** mix opaque and glass cards in the same card group.

---

#### 13. Feedback Components
Ridgeline feedback is concise, actionable, and non-intrusive.

##### 13.1 Snackbar Host Signature
```kotlin
@Composable
fun RidgelineSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
)
```

##### 13.2 Loading Signature
```kotlin
@Composable
fun RidgelineLoadingState(
    message: String,
    modifier: Modifier = Modifier,
)
```

##### 13.3 Empty State Signature
```kotlin
@Composable
fun RidgelineEmptyStateCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
)
```

##### 13.4 Key Parameters
- `RidgelineSnackbarHost.hostState`: single source for queued feedback.
- `RidgelineLoadingState.message`: short, task-specific progress copy.
- `RidgelineEmptyStateCard`: `title`, `body`, and one primary `actionLabel` only.

##### 13.5 Usage Example
```kotlin
Scaffold(
    snackbarHost = { RidgelineSnackbarHost(hostState = snackbarHostState) },
) { padding ->
    when (uiState) {
        UiState.Loading -> RidgelineLoadingState("Loading sessions…", Modifier.padding(padding))
        UiState.Empty -> RidgelineEmptyStateCard(
            title = "No sessions yet",
            body = "Start tracking to see your first route.",
            actionLabel = "Start tracking",
            onAction = onStart,
            modifier = Modifier.padding(padding),
        )
        is UiState.Ready -> SessionList(...)
    }
}
```

##### 13.6 Do / Don't
- **Do:** keep snackbars ≤2 lines and single action.
- **Do:** use inline loading for section refresh, full-screen loading for initial fetch only.
- **Don't:** use blocking dialogs for recoverable errors.
- **Don't:** show endless indeterminate loaders without timeout or retry path.

---

#### 14. Tracking Components
Tracking UI elements are always state-driven and privacy-safe.

##### 14.1 TrackingFAB Signature
```kotlin
enum class TrackingFabState { Idle, Active, Paused }

@Composable
fun TrackingFAB(
    state: TrackingFabState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
)
```

##### 14.2 RecordingDot Signature
```kotlin
@Composable
fun RecordingDot(
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    color: Color = AppColors.Adaptive.trackActive,
)
```

##### 14.3 Goal Progress Ring Signature
```kotlin
@Composable
fun GoalProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 6.dp,
)
```

##### 14.4 Key Parameters
- `TrackingFAB.state`: sole driver for icon, color, and action affordances.
- `RecordingDot.isRecording`: controls active pulse/static rendering.
- `GoalProgressRing.progress`: clamp to `0f..1f` for base arc and draw overflow arc for values `>1f`.

##### 14.5 Usage Example
```kotlin
Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    RecordingDot(isRecording = trackingState == TrackingState.Active)
    Text(trackingState.label)
    Spacer(Modifier.weight(1f))
    GoalProgressRing(progress = dailyGoalProgress)
}
TrackingFAB(
    state = trackingFabState,
    onStart = onStart,
    onPause = onPause,
    onResume = onResume,
    onStop = onStop,
)
```

##### 14.6 Do / Don't
- **Do:** map all FAB visuals from explicit state (`Idle/Active/Paused`).
- **Do:** keep active color fixed at `#FF3B30` across light/dark.
- **Don't:** run pulsing animation when `LocalReducedMotion` is true.
- **Don't:** hide paused state; users must see data collection is suspended.

---

#### 15. Data Display Components
Data presentation prioritizes readability, stable alignment, and locale-correct formatting.

##### 15.1 MetricText Signature
```kotlin
@Composable
fun MetricText(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.displaySmall,
    unitStyle: TextStyle = MaterialTheme.typography.titleMedium,
)
```

##### 15.2 Key Parameters
- `value`: preformatted numeric string with locale decimal separator.
- `unit`: localized unit label (`km`, `m`, `min/km`, etc.).
- `valueStyle` / `unitStyle`: default to Ridgeline scale; override only for constrained layouts.

##### 15.3 Formatter Rules
- Distance: `km` with locale decimal separators and 0–2 decimals.
- Duration: `HH:mm:ss` for active sessions, compact humanized form for summaries.
- Speed/Pace: fixed width numeric block for live updates.
- Dates: `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)`.

##### 15.4 Tabular Alignment Example
```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Bottom,
) {
    MetricText(value = "07.42", unit = "km")
    Text(
        text = "00:41:03",
        style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontFeatureSettings = "tnum"
        )
    )
}
```

##### 15.5 Do / Don't
- **Do:** use `Roboto Mono` (`FontFamily.Monospace`) for primary live metrics.
- **Do:** enable tabular figures (`tnum`) for any changing numeric text.
- **Don't:** mix proportional and monospaced digits in one metric cluster.
- **Don't:** truncate critical values (distance, pace, duration); wrap or resize appropriately.

---


---

## PART III — PATTERNS (Usage Guidelines)

> Cross-cutting usage patterns, UX flows, and contextual guidance for feature developers.

---


#### 16. Empty States
Use a 3-tier model based on scope and severity of emptiness.

##### 16.1 Tier Definitions
1. **Inline (Tier 1):** small gaps within populated screens; compact row, no illustration.
2. **Card (Tier 2):** section-level absence; use `GlassCard` + icon + single CTA.
3. **Full-screen (Tier 3):** no primary content on screen; hero illustration + primary CTA + optional secondary action.

##### 16.2 Illustration Rules
- Style: 2-tone line art using `onSurfaceVariant` + `primary` accent.
- Placement: top third of Tier 3 screen.
- Motion: subtle float only when motion is enabled.

##### 16.3 Do / Don't
- **Do:** explain what happened and what to do next.
- **Do:** include privacy reassurance only in Tier 3 states.
- **Don't:** imply user blame (no “you forgot”).
- **Don't:** use decorative illustrations in Tier 1.

---

#### 17. Permissions
Permission UX is sequential, contextual, and skippable.

##### 17.1 Flow
1. Trigger from user intent (start tracking/export notify).
2. Show rationale dialog before system request (if first-time context is unclear).
3. Request one permission at a time.
4. If denied: inline banner with “Continue with limited mode” and “Open settings”.

##### 17.2 Rationale Dialog Contract
```kotlin
@Composable
fun PermissionRationaleDialog(
    title: String,
    reason: String,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
)
```

##### 17.3 Do / Don't
- **Do:** provide “Skip” in every non-critical permission flow.
- **Do:** keep copy concrete (“Needed to record your route while screen is off”).
- **Don't:** chain multiple system dialogs without user context.
- **Don't:** block app entry for optional permissions.

---

#### 18. Tracking States
Core tracking state model: `Active`, `Paused`, `Stopped` with explicit visual transitions.

##### 18.1 State Behavior
- **Active:** red recording dot, active FAB morphology, live timer increments.
- **Paused:** amber pause indicator, timer frozen, route capture suspended.
- **Stopped:** neutral state, summary CTA appears.

##### 18.2 Transition Rules
- Active → Paused: immediate icon/color swap; no destructive confirmation.
- Paused → Active: resume spring (`TactileActive`).
- Active/Paused → Stopped: confirmation sheet with save/discard actions.

##### 18.3 Do / Don't
- **Do:** announce transitions through accessibility state descriptions.
- **Don't:** infer state from color alone; include icon/text redundancy.

---

#### 19. Celebrations
Celebrations are brief acknowledgments, never modal interruptions.

##### 19.1 Goal Completion Pattern
- Trigger at first transition from `<1.0` to `>=1.0` progress.
- 3-phase ring sequence: color bloom (120ms) → pulse (220ms) → check reveal (180ms).
- Surface a snackbar with action (“View goal details”).

##### 19.2 Challenge Completion Pattern
- Slide-in banner from top with trophy icon.
- Auto-dismiss after 4s.
- Haptic: light impact once (if enabled by OS settings).

##### 19.3 Do / Don't
- **Do:** cap one celebration at a time.
- **Don't:** use confetti particles or full-screen takeovers.

---

#### 20. Export Flow
Export is explicit, preview-first, and local-only.

##### 20.1 Pattern
1. Show export preview card (format, range, estimated size, privacy note).
2. Confirm destination (Share sheet or SAF folder).
3. Show determinate progress.
4. Show completion snackbar with “Open file” action when available.

##### 20.2 Preview Card Signature
```kotlin
@Composable
fun ExportPreviewCard(
    format: ExportFormat,
    dateRange: String,
    estimatedSize: String,
    onEdit: () -> Unit,
)
```

##### 20.3 Do / Don't
- **Do:** require explicit user action for every export.
- **Do:** warn when precision/location detail is high.
- **Don't:** auto-export in background without opt-in.

---

#### 21. Topographic Pattern
Topographic lines are drawn via `Canvas` with `drawWithCache` and used sparingly.

##### 21.1 Implementation Snippet
```kotlin
fun Modifier.topographicContours(
    alpha: Float,
    spacing: Dp = 18.dp,
): Modifier = drawWithCache {
    val step = spacing.toPx()
    val path = Path().apply {
        var y = 0f
        while (y < size.height + step) {
            moveTo(0f, y)
            quadraticTo(size.width * 0.35f, y - step * 0.6f, size.width, y)
            y += step
        }
    }
    onDrawBehind {
        drawPath(path, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), style = Stroke(width = 1.dp.toPx()))
    }
}
```

##### 21.2 Placement Rules
- Cards only: `alpha = 0.05f`.
- Empty full-screen backgrounds only when needed: `alpha = 0.07f` light / `0.09f` dark.
- Never apply to text-heavy surfaces or forms.

##### 21.3 Do / Don't
- **Do:** keep lines behind content and clipped to container shape.
- **Don't:** animate contour paths continuously.

---

#### 22. Privacy Patterns
Privacy is visible in UI copy and enforced in system-facing surfaces.

##### 22.1 Notification Content
- Ongoing notification title: “Tracking active”.
- Body must not contain raw coordinates or addresses.
- Show elapsed time and coarse activity label only.

##### 22.2 Lockscreen Policy
- Default: hide metric detail on lockscreen (`VISIBILITY_PRIVATE`).
- Optional user override can reveal distance/timer only; still no coordinates.

##### 22.3 Logging Redaction
- Release logs: redact lat/lon, place names, file paths tied to user identity.
- Use structured redaction helpers; never string-concatenate raw location into logs.

##### 22.4 Do / Don't
- **Do:** include privacy reassurance in onboarding and export contexts.
- **Don't:** surface sensitive values in toasts/snackbars visible over screenshots.

---

#### 23. Internationalization & RTL
Ridgeline supports locale expansion and right-to-left layouts by default.

##### 23.1 RTL Rules
- Use `start`/`end` paddings and alignments exclusively.
- Use auto-mirrored icons when directional (`Icons.AutoMirrored.*`).
- Mirror asymmetric shapes when semantic direction changes (e.g., leading-corner emphasis).

##### 23.2 Exemptions
- Charts, maps, and compass-style orientation graphics remain physically oriented and do not mirror data axes.

##### 23.3 Do / Don't
- **Do:** verify strings at EN + CS minimum and one RTL locale.
- **Don't:** hardcode punctuation/units order; rely on localized formatting.

---


---

## PART IV — IMPLEMENTATION

> Migration strategy, testing, governance, file structure, and appendix.

---


#### 24. Migration Guide
Direct migration, screen by screen, with no compatibility shim.

##### 24.1 Screen Priority Order
1. Dashboard
2. Map
3. Statistics
4. Game
5. Settings
6. Import/Export

##### 24.2 PR Structure
- PR 1: Theme/token foundation (`AppTheme`, color tokens, motion/shape tokens).
- PR 2..N: One screen family per PR (max ~400 LOC changed).
- Keep behavior changes separate from visual refactors where possible.

##### 24.3 Verification Checklist (Per PR)
- [ ] Uses `AppTheme` root.
- [ ] No hardcoded colors/shapes/spacing in touched UI.
- [ ] 48dp minimum hit targets.
- [ ] Light + dark + 200% font previews updated.
- [ ] Reduced motion/transparency fallback verified.
- [ ] TalkBack traversal and labels verified.

---

#### 25. Testing Matrix
All DS components must ship with previews and targeted UI validation.

##### 25.1 Preview Annotation
```kotlin
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Font200", fontScale = 2.0f)
annotation class RidgelinePreviews
```

##### 25.2 Required Coverage
- **Previews:** every DS primitive + every screen shell state.
- **Screenshot tests (Paparazzi/Roborazzi):** nav bar, cards, top app bars, empty states, FAB states.
- **Accessibility tests:** contrast checks, semantic labels, focus order, touch target assertions.

##### 25.3 Minimum Test Cases
- TrackingFAB states (`Idle/Active/Paused`) visual + semantics.
- GlassSurface fallback when `LocalReduceTransparency = true`.
- GoalProgressRing completion at 99%, 100%, 140%.
- RTL screenshot baseline for core dashboard layout.

---

#### 26. Governance
Design system drift is prevented in static analysis and CI.

##### 26.1 Detekt / Static Rules
- Forbid direct `Color(...)` in feature modules (allowlist token files only).
- Forbid `RoundedCornerShape(...)` in feature modules (use tokenized shapes).
- Forbid legacy View/XML UI imports in Compose modules.

##### 26.2 CI Drift Prevention
- Token snapshot test: fail if token object changes without changelog entry.
- Screenshot diff gate on critical DS components.
- PR template requires “Ridgeline impact” section.

##### 26.3 Token Change Process
1. Open DS proposal issue with rationale + screenshots.
2. Review by design + Android maintainers.
3. Land token change in isolated PR with migration notes.
4. Update previews/screenshot baselines in same change set.

---

#### 27. File Structure
Canonical module/file layout for tokens and primitives.

##### 27.1 Tokens
```text
sutils/
  src/main/java/com/adsamcik/tracker/shared/utils/style/compose/
    AppTheme.kt
    RidgelineColorTokens.kt
    RidgelineTypography.kt
    RidgelineShapes.kt
    RidgelineSpacing.kt
    RidgelineMotion.kt
    RidgelineElevation.kt
```

##### 27.2 Components
```text
app/
  src/main/java/.../ui/designsystem/
    glass/GlassSurface.kt
    glass/GlassCard.kt
    navigation/RidgelineFloatingNavigationBar.kt
    appbar/RidgelineTopAppBar.kt
    cards/MetricCard.kt
    cards/ChallengeCard.kt
    cards/SessionCard.kt
    feedback/RidgelineSnackbarHost.kt
    feedback/RidgelineLoadingState.kt
    tracking/TrackingFAB.kt
    tracking/RecordingDot.kt
    tracking/GoalProgressRing.kt
    text/MetricText.kt
```

##### 27.3 Patterns & Guidance
```text
docs/
  DESIGN_SYSTEM.md
  DESIGN_ROUND_*.md
```

---

#### 28. Appendix

##### 28.1 Material 3 Components Used As-Is (Do Not Wrap)
1. `Switch`
2. `Checkbox`
3. `RadioButton`
4. `OutlinedTextField`
5. `TextField`
6. `DatePicker`
7. `DatePickerDialog`
8. `TimePicker`
9. `Snackbar`
10. `SnackbarHost`
11. `TooltipBox`
12. `PlainTooltip`
13. `RichTooltip`
14. `ModalBottomSheet`
15. `AlertDialog`
16. `BasicAlertDialog`
17. `LinearProgressIndicator`
18. `CircularProgressIndicator`
19. `AssistChip`
20. `FilterChip`
21. `InputChip`
22. `SuggestionChip`
23. `SegmentedButton`
24. `ExposedDropdownMenuBox`
25. `DropdownMenuItem`

##### 28.2 Rejected Alternatives
- Compatibility shim theme layer (rejected: extra drift surface, no migration benefit).
- Noise texture in glass (rejected: visual clutter, accessibility risk).
- Symmetric rounded-corner-only language (rejected: loses Ridgeline identity).
- Global bouncy spring default (rejected: conflicts with reliability/privacy tone).
- Multi-pane tablet-first redesign for v1 (rejected: scope/cost mismatch).

##### 28.3 Glossary
- **Ridgeline:** final visual language for Tracker Android.
- **Glass tier:** predefined blur/tint/border intensity (`G0`–`G3`).
- **Topo pattern:** contour-line decorative layer drawn with Canvas.
- **Primary action:** highest-priority action on a screen; one max.
- **Reduced motion/transparency:** accessibility overrides that disable animation/blur effects.


---


---

## PART V — DETAILED SPECIFICATIONS

> Pixel-level component specs, per-screen assignments, and extended patterns.
> These supplement Parts II–IV with implementation-ready detail.

---

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
*   **Map Screen:** Standard FAB (`WaypointShape`, Canopy Green, TactileActive). Quick-Stat Cards for metrics overlay.
*   **Dashboard:** Large FAB (96dp) for tracking. Stats Summary Card at top. Challenge Cards in carousel. Quick-Stat Cards for secondary metrics.
*   **Statistics Screen:** Trip Cards in list. Section headers with accent bar. Stats Summary Card for period totals.
*   **Trip Detail Screen:** Stats Summary Card for trip aggregate. Quick-Stat Cards for individual metrics. Sunset Rust for peak values.
*   **Game Screen:** Challenge Cards in carousel. Stats Summary Cards for lifetime stats. Section headers per category.
*   **Settings/Privacy Screen:** `PrimaryActionButton` for saves. `OutlinedButton` for exports. Section headers for groups, item dividers within.

### Floating Navigation Bar (Final)
*   **Height:** 80dp. M3 standard. Provides breathing room for icon pill + label.
*   **Corner radius:** 32dp (full `RoundedCornerShape`). Soft capsule, "floating island" feel.
*   **Background:** Haze blur (26dp) + surface color tint. Fallback: `GlassCard`.
*   **Tint alpha:** 0.72 light / 0.76 dark (G3). No noise. No parallax.
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

*   **Stagger interval:** `MotionTokens.STAGGER_MS` (60ms) per card.
*   **Per-card animation:** Fade 0→1 (200ms tween) + translate 24dp→0dp (`MotionTokens.Standard` spring).
*   **Max stagger depth:** 6 cards. Cards beyond the 6th appear with the 6th.
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


---

## Version & Changelog

| Version | Date | Description |
|---------|------|-------------|
| 1.0 | 2025-07-17 | Final merge of Rounds 1–30. All tokens, components, patterns, and implementation guidance locked. |
| R29 | — | Part 0 (Quick Reference) + Part I (Tokens) synthesis. |
| R28 | — | Accessibility v1 minimum bar confirmed. Theme root + migration strategy finalized. |
| R27 | — | Color seed `#1B6B3A` confirmed. Teal palette superseded. |
| R26 | — | Material 3 theme-root direction clarified for current dependency surface. |
| R23–R25 | — | Empty states, permissions, export, celebrations, testing matrix. |
| R20–R22 | — | Glass polish, elevation strategy, dark mode edge handling, animation choreography. |
| R18–R19 | — | Token pipeline, component inventory, developer experience. |
| R14–R17 | — | Layout system, navigation bar, FAB, edge-to-edge, app chrome. |
| R12–R13 | — | Color convergence, Okabe-Ito activity palette, card system. |
| R8–R11 | — | Typography stack (Outfit/Inter/Mono), metrics font, accessibility text scaling. |
| R4–R7 | — | Color seed, shape DNA, spacing scale, motion springs. |
| R1–R3 | — | Foundations, privacy constraints, Compose-only mandate. |

### Key Locked Values (Quick Verification)

| Token | Value |
|-------|-------|
| Brand seed | `#1B6B3A` (Canopy Green, HCT H≈145° C≈48 T≈38) |
| Theme root | Material 3 `MaterialTheme` via `AppTheme` |
| Glass G0 | Solid (no blur, α=1.00) |
| Glass G1 | blur=10dp, α=0.85 light / 0.88 dark |
| Glass G2 | blur=18dp, α=0.78 light / 0.82 dark |
| Glass G3 | blur=26dp, α=0.72 light / 0.76 dark |
| Spring: Snap | stiffness=1500, damping=0.75 |
| Spring: Settle | stiffness=400, damping=1.0 |
| Spring: Respond | stiffness=800, damping=0.82 |
| Spring: Crest | stiffness=300, damping=0.55 |
| Spring: Drift | stiffness=50, damping=1.0 |
| Spring: Surge | stiffness=180, damping=0.58 |
| Shape L1 | 6dp major / 2dp minor |
| Shape L2 | 10dp major / 3dp minor |
| Shape L3 | 14dp major / 4dp minor |
| Shape L4 | 20dp major / 6dp minor |
| Shape L5 | 24dp major / 8dp minor |
| Nav bar height | 80dp |
| Nav bar radius | 32dp |
| Spacing base | 4dp, 10 tokens (0/2/4/8/12/16/20/24/32/48) |
| Card stagger | 60ms, cap 6 |
| Accessibility | WCAG AA, 48dp targets, 200% font, TalkBack |

---

*This document was synthesized from 30 rounds of iterative design review. The round history is preserved in `docs/DESIGN_SYSTEM.md`. Individual round records are in `docs/DESIGN_ROUND_*.md`.*
