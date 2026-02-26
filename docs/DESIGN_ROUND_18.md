# Ridgeline Design System — Round 18: Canonicalization

**From:** Lead Designer (Claude, Ridgeline)
**Round:** 18 of 30
**Status:** CANONICALIZATION — Unified conflict resolution, missing spec completion, implementation order

---

## 0. Round 17 Integration

All GPT Round 17 findings accepted and integrated:

| Finding | Resolution | Status |
|---------|-----------|--------|
| TopAppBar: `LargeTopAppBar` for stats list | Accepted in R17 §0.1. DESIGN_SYSTEM.md §20 updated. | ✅ CLOSED |
| Gutter: responsive 16/24/32 | Already canonical in DESIGN_SYSTEM.md §7.3 | ✅ CLOSED |
| Topo alpha: 0.07f for empty bg, 0.05f for cards | Standardized in R17 §2.4. Token table in §2.6 is canonical. | ✅ CLOSED |
| Glass: "Simplified surfaces" toggle | Integrated into `GlassSurface` fallback (R17 §6.3): `LocalSimplifiedSurfaces` | ✅ CLOSED |
| Map peek: 72dp | Held in R17 §0.2. DESIGN_SYSTEM.md §21. | ✅ CLOSED |
| Missing: Date/time, number formatting, locale, snackbar tokens | Addressed in R17 §3.1–3.7 and R11 §3.3. Completed in this round. | ✅ CLOSED below |

---

## 1. UNIFIED CONFLICT RESOLUTION TABLE

Every inconsistency found across Rounds 1–17 by either model. Each row is a **binding resolution** — no further debate.

### 1.1 Resolved Conflicts (12 total)

| # | Conflict | Source A | Source B | **FINAL Resolution** | Authority |
|---|----------|----------|----------|----------------------|-----------|
| C1 | Shape scale dp vs code percentage | DESIGN_SYSTEM.md §8 (dp-based L1–L5) | `Shape.kt` (percentage-based) | **Dp-based L1–L5 wins.** Code must be rewritten per R17 §1.1. `AppShapes(extraSmall=L1…extraLarge=L5)` with `RoundedCornerShape(topStart=major, topEnd=minor, bottomEnd=major, bottomStart=minor)`. | DESIGN_SYSTEM.md §8 |
| C2 | Bottom sheet shape: symmetric vs asymmetric | R11 `BottomSheetTokens` (28dp symmetric) | DESIGN_SYSTEM.md §21 (20dp/6dp asymmetric) | **Asymmetric: `topStart=20.dp, topEnd=6.dp`.** R11 token is OBSOLETE. | DESIGN_SYSTEM.md §21 |
| C3 | Glass border alpha: static vs mode-adaptive | Code `DesignSystem.kt` (0.3f static) | DESIGN_SYSTEM.md §2.6 (0.30 light / 0.20 dark) | **Mode-adaptive.** `if (isDark) 0.20f else 0.30f`. Code must be fixed. | DESIGN_SYSTEM.md §2.6 |
| C4 | `PrimaryActionButton` vertical padding | Code (16dp) | DESIGN_SYSTEM.md §14.1 (12dp) | **12dp.** Code must be fixed. | DESIGN_SYSTEM.md §14.1 |
| C5 | Body/Label typeface: Inter vs System | DESIGN_SYSTEM.md §3 (says "Inter") | R10 FINAL + code (`FontFamily.Default`) | **System font (Roboto/Noto Sans).** DESIGN_SYSTEM.md §3 text says "Inter" but this is a documentation error. Code and R10 decision are correct. Fix doc to read "System font (Roboto / Noto Sans)" for Body/Label rows. | R10 + Code |
| C6 | Topo alpha Tier 3 empty bg: 0.06 vs 0.07 | R16 §1.2 (0.06α) | DESIGN_SYSTEM.md §2.6 token table (0.07 light) | **0.07 light / 0.09 dark** per token table. R16's 0.06 was a rounding error. | DESIGN_SYSTEM.md §2.6 |
| C7 | Dialog shape: part of L-scale or exempt? | Shape scale L5 (24dp/8dp) | R11 DialogShape (28dp full-round) | **Exempt.** Dialogs use 28dp `RoundedCornerShape(28.dp)` (M3 standard). Not part of L1–L5 scale. Intentional. | R17 §2.1 |
| C8 | Section number collision: two §8s | DESIGN_SYSTEM.md §8 (Shape Scale) | DESIGN_SYSTEM.md §8 (Navigation Labels) | **Navigation Labels becomes §8b.** Already noted as renumber target. | R17 §1.7 |
| C9 | `MetricText` font family | Code uses `FontFamily.Default` | DESIGN_SYSTEM.md §6 (FontFamily.Monospace) | **`FontFamily.Monospace`.** Code must be fixed. Monospace prevents layout shift during live tracking updates. | DESIGN_SYSTEM.md §6 |
| C10 | Snackbar shape: 8dp vs asymmetric L-scale | R11 §3.3 `SnackbarTokens.Shape = RoundedCornerShape(8.dp)` | Could theoretically use L1 (6dp/2dp) | **8dp symmetric `RoundedCornerShape(8.dp)`.** Snackbar is an M3 standard component — use M3 default shape. Not part of terrain shape language. Same exemption logic as dialogs (C7). | R11 §3.3 |
| C11 | `TopographicPattern` default alpha parameter | R17 §5.4 code default: `if (isDark) 0.07f else 0.05f` (card context) | Could default to empty-bg context | **Card context as default is correct.** Most common usage is on cards. Caller overrides via `TopoAlpha.emptyBg` for Tier 3 screens. | R17 §5.4 |
| C12 | `GlassSurface` internal content padding | R17 §6.4 code: `Modifier.padding(RidgelineSpacing.Lg)` (16dp) | Card specs vary (Trip Card 16dp, Stats Summary 20dp, Challenge 12dp) | **Remove hardcoded padding from `GlassSurface`.** The component is a _surface_, not a card. Content padding is the caller's responsibility. Different card types need different padding per §13. | This round — OVERRIDE R17 §6.4 |

### 1.2 Non-Conflicts (Confirmed Aligned)

| Item | Status |
|------|--------|
| Activity colors (DESIGN_SYSTEM.md §2.5 vs R13 FINAL) | ✅ Aligned. Code matches. |
| Loading states: no shimmer (R11 §2 + R12 §7.3) | ✅ Aligned. Permanently rejected. |
| Chip shapes referencing L1/L2 (R12 §4.2–4.3) | ✅ Aligned once C1 shape fix applied. |
| Celebration policy: Snackbar only, no confetti (R17 §0.4) | ✅ Aligned. |
| Map peek height: 72dp (R17 §0.2) | ✅ Aligned. |
| Menu max items: 7 (R17 §0.3) | ✅ Aligned. |

---

## 2. MISSING SPECS — Complete Resolution

### 2.1 Date/Time Pickers

**Addressed in R17 §3.1. Completing with binding tokens:**

| Aspect | Spec |
|--------|------|
| Component | M3 `DatePickerDialog` (single) / `DateRangePickerDialog` (range) |
| When used | Statistics filter, Export scope (custom range) |
| Colors | M3 defaults: `primary` selection, `surfaceContainerHigh` dialog background |
| Shape | `DialogShape` (28dp full-round) — same exemption as all dialogs |
| Time picker | **None.** No user-editable time fields exist. Sessions are auto-timestamped. |
| Input mode | Modal (calendar grid). No text-input date entry — too error-prone for this context. |
| Range constraints | Start ≤ End. Start defaults to earliest session date. End defaults to today. |
| Empty state | If no sessions exist in selected range, show `InlineEmptyState` (Tier 1). |
| Locale | Date format follows system locale (`DateFormat.getDateInstance(DateFormat.MEDIUM, locale)`). |

**FINAL. No time picker. No custom date component.**

### 2.2 Number Formatting & Locale Contract

**Addressed in R17 §3.2. Binding precision table:**

| Metric Type | Decimal Places | Format Example (en-US) | Format Example (cs-CZ) | Font |
|-------------|---------------|------------------------|------------------------|------|
| Distance (km/mi) | 1 | "12.3 km" | "12,3 km" | `Monospace` for value, `Default` for unit |
| Distance short (<1km) | 0 | "845 m" | "845 m" | `Monospace` / `Default` |
| Speed (km/h, mph) | 1 | "4.2 km/h" | "4,2 km/h" | `Monospace` / `Default` |
| Altitude (m/ft) | 0 | "1,234 m" | "1 234 m" | `Monospace` / `Default` |
| Duration | HH:MM:SS | "02:45:12" | "02:45:12" | `Monospace` (full string) |
| Steps | 0 | "12,345" | "12 345" | `Monospace` |
| Percentage | 0 | "85%" | "85 %" | `Default` (not a primary metric) |
| Coordinates | 6 (GPS) | "49.195061, 16.606836" | "49.195061, 16.606836" | `Monospace` — always period decimal (GPS standard) |
| Calories | 0 | "1,234 kcal" | "1 234 kcal" | `Monospace` / `Default` |

**Locale rules:**
- Decimal separator: system locale (`NumberFormat.getNumberInstance(locale)`)
- Thousands separator: system locale
- Unit placement: value + space + unit. **Always a space** between number and unit abbreviation.
- Exception: percentage in Czech uses "85 %" (space before %) per ČSN. English uses "85%" (no space). Follow system locale convention via `NumberFormat.getPercentInstance(locale)`.
- Coordinates: **always period decimal** regardless of locale. GPS standard. Displayed only in debug mode; redacted in release.

**Unit system:**
- User preference stored in DataStore: `metric` (default) or `imperial`
- Conversions happen at the formatting layer, never in storage. Database always stores meters, meters/second, etc.
- Unit abbreviations: "km", "mi", "m", "ft", "km/h", "mph", "m/s" — not localized (international standard symbols).

**Compose helper (FINAL):**

```kotlin
object RidgelineFormat {
    @Composable
    fun distance(meters: Double, unit: LengthUnit): String {
        val locale = currentLocale()
        val converted = unit.convert(meters)
        val format = NumberFormat.getNumberInstance(locale).apply {
            if (converted >= 1000) { maximumFractionDigits = 0; minimumFractionDigits = 0 }
            else if (converted >= 1) { maximumFractionDigits = 1; minimumFractionDigits = 1 }
            else { maximumFractionDigits = 0; minimumFractionDigits = 0 }
        }
        val suffix = if (converted >= 1) unit.abbreviation else unit.smallAbbreviation
        return "${format.format(if (converted >= 1) converted else meters)} $suffix"
    }

    @Composable
    fun speed(metersPerSecond: Double, unit: SpeedUnit): String {
        val locale = currentLocale()
        val format = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1; minimumFractionDigits = 1
        }
        return "${format.format(unit.convert(metersPerSecond))} ${unit.abbreviation}"
    }

    @Composable
    fun altitude(meters: Double, unit: LengthUnit): String {
        val locale = currentLocale()
        val format = NumberFormat.getIntegerInstance(locale)
        return "${format.format(unit.convert(meters).roundToInt())} ${unit.smallAbbreviation}"
    }

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    @Composable
    fun steps(count: Int): String {
        val locale = currentLocale()
        return NumberFormat.getIntegerInstance(locale).format(count)
    }

    @Composable
    private fun currentLocale(): Locale =
        LocalContext.current.resources.configuration.locales[0]
}
```

**FINAL. Database stores SI. Formatting is locale-aware at the UI layer.**

### 2.3 Snackbar Tokens

**Fully specified in R11 §3.3. Confirming canonical values:**

```kotlin
object SnackbarTokens {
    // Colors — M3 defaults, DO NOT override
    val ContainerColor = inverseSurface        // dark in light mode, light in dark mode
    val ContentColor = inverseOnSurface
    val ActionColor = inversePrimary
    val DismissActionColor = inverseOnSurface

    // Shape — M3 default, NOT terrain shape
    val Shape = RoundedCornerShape(8.dp)

    // Elevation — E3 (Floating)
    val TonalElevation = 6.dp
    val ShadowElevation = 6.dp

    // Positioning
    val BottomPadding = 16.dp                  // Above floating nav bar
    val BottomPaddingWithFab = 72.dp           // Above FAB (56dp + 16dp)
    val HorizontalPadding = 16.dp              // Matches page gutter (compact)
    val MaxWidth = 560.dp                       // Tablet constraint

    // Duration tiers
    // Short (4s): success confirmations
    // Long (10s): actionable messages
    // Indefinite: blocking conditions — MUST have dismiss action
}
```

**Convenience extensions (FINAL):**

```kotlin
suspend fun SnackbarHostState.showSuccess(message: String) {
    showSnackbar(message = message, duration = SnackbarDuration.Short)
}

suspend fun SnackbarHostState.showActionable(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val result = showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) onAction()
}

suspend fun SnackbarHostState.showError(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val result = showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
}
```

**FINAL. M3 defaults for visual tokens. Snackbar is exempt from terrain shape language.**

### 2.4 Haptics Matrix

**Fully specified in R13 §6.2. Confirming complete trigger→type mapping:**

| # | Trigger | Haptic Type | API 31+ | Pre-31 Fallback | Category |
|---|---------|------------|---------|-----------------|----------|
| H1 | Recording start | Heavy | `CONFIRM` | `LongPress` | Critical state |
| H2 | Recording stop | Heavy | `CONFIRM` | `LongPress` | Critical state |
| H3 | Recording pause/resume | Light tick | `CLOCK_TICK` | `TextHandleMove` | Sub-state |
| H4 | Achievement unlocked | Pattern | `createWaveform` | `LongPress` ×2 | Celebration |
| H5 | Activity type changed | Light tick | `CLOCK_TICK` | `TextHandleMove` | Passive info |
| H6 | Drag reorder: pickup | Medium | `GESTURE_START` | `LongPress` | Spatial |
| H7 | Drag reorder: position change | Light tick | `GESTURE_THRESHOLD_ACTIVATE` | `TextHandleMove` | Spatial |
| H8 | Drag reorder: drop | Medium | `GESTURE_END` | `LongPress` | Spatial |
| H9 | Bottom sheet snap | Light tick | `CLOCK_TICK` | `TextHandleMove` | Spatial |
| H10 | Toggle switch | Light tick | `CLOCK_TICK` | `TextHandleMove` | State change |
| H11 | Delete confirmed | Medium | `REJECT` | `LongPress` | Destructive |
| H12 | Map long-press place | Medium | `LONG_PRESS` | `LongPress` | Confirm target |
| H13 | Nav bar long-press (tooltip) | Light tick | `LONG_PRESS` | `LongPress` | Spatial |
| H14 | Selection mode enter (long-press card) | Medium | `LONG_PRESS` | `LongPress` | State change |
| H15 | Selection checkbox toggle | Light tick | `CLOCK_TICK` | `TextHandleMove` | State change |

**No haptic (explicit exclusions):** FAB press, card tap, button tap, nav bar selection, scroll/fling, dialog open/close, chip selection, snackbar appear/dismiss, date picker selection.

**Achievement waveform (API 31+):**
```kotlin
createWaveform(
    timings = longArrayOf(0, 50, 30, 50, 30, 80),
    amplitudes = intArrayOf(0, 80, 0, 80, 0, 180),
    repeat = -1,  // no repeat
)
```

**Pre-31 fallback:** Two sequential `LongPress` feedbacks with 100ms delay.

**Implementation class:** `RidgelineHaptics` per R13 §6.4. Five methods: `confirmAction()`, `tick()`, `reject()`, `gestureStart()`, `gestureEnd()`. Add `selectionEnter()` (delegates to `confirmAction()`) for H14.

**Accessibility:** Haptics respect system "Touch vibration" setting automatically via `View.performHapticFeedback()`. No custom check needed.

**FINAL. 15 triggers. No additions without design review.**

### 2.5 Onboarding → Main Transition

**Addressed in R17 §3.3. Binding spec:**

| Aspect | Value |
|--------|-------|
| Type | Shared Z-axis (forward) |
| Motion token | `MaterialTheme.motionScheme.defaultSpatialSpec()` (~300ms) |
| Outgoing | Onboarding screen scales down to 0.92 + fades to 0α |
| Incoming | Dashboard scales up from 1.08 + fades from 0α |
| Navigation | `navController.navigate("dashboard") { popUpTo("onboarding") { inclusive = true } }` — no back to onboarding |
| One-shot | `DataStore` pref `onboarding_complete = true`. Checked at app startup. |
| Reduced motion | Instant cut (0ms, no scale). |

**FINAL.**

### 2.6 Notification Design Tokens

**Addressed in R17 §3.4. Completing with channel hierarchy:**

#### Channels

| Channel ID | Name | Importance | Description |
|-----------|------|-----------|-------------|
| `tracking` | Tracking | High (`IMPORTANCE_HIGH`) | Ongoing tracking foreground service |
| `exports` | Exports | Default (`IMPORTANCE_DEFAULT`) | Export completion |
| `achievements` | Achievements | Low (`IMPORTANCE_LOW`) | Achievement unlocks, milestones |

#### Tracking Notification (Ongoing)

| Token | Value |
|-------|-------|
| Small icon | `R.drawable.ic_notification_tracking` (custom outline, 24dp monochrome) |
| Color | `#006874` (primary) — tints icon on API 21+ |
| Title | "Tracking" or "Tracking · Passive" |
| Content text | "2.3 km · 00:45:12" (updates every 5s) |
| Actions | "Stop" (max 1 action to keep notification compact) |
| Category | `NotificationCompat.CATEGORY_SERVICE` |
| Ongoing | `true` — not swipe-dismissible |
| Priority | `PRIORITY_LOW` (no sound/vibrate — it's persistent) |
| When | Session start timestamp (shows elapsed in notification shade) |

#### Export Complete Notification

| Token | Value |
|-------|-------|
| Small icon | `R.drawable.ic_notification_export` (custom outline, 24dp monochrome) |
| Color | `#006874` (primary) |
| Title | "Export complete" |
| Content text | Filename: "session_2024-01-15.gpx" |
| Actions | "Share" (share intent) |
| Auto-cancel | `true` |
| Category | `NotificationCompat.CATEGORY_STATUS` |

#### Achievement Notification

| Token | Value |
|-------|-------|
| Small icon | `R.drawable.ic_notification_achievement` (custom outline) |
| Color | `#006874` (primary) |
| Title | Achievement name |
| Content text | Achievement description |
| Actions | "View" (deep link to Game screen) |
| Auto-cancel | `true` |
| Category | `NotificationCompat.CATEGORY_STATUS` |

**FINAL. Three channels. No sound/vibration customization — follow system defaults per channel importance.**

### 2.7 Card Selection State

**Addressed in R17 §3.6. Binding spec:**

| Aspect | Value |
|--------|-------|
| Trigger | Long-press on any selectable card |
| Visual: selected card | `primaryContainer` overlay at 0.12α + 2dp `primary` border |
| Visual: unselected card (during selection mode) | Normal appearance, no change |
| Checkbox | 24dp `Checkbox`, top-end corner of card. Scale-in animation on enter selection mode (`MaterialTheme.motionScheme.fastSpatialSpec()`) |
| TopAppBar in selection mode | Contextual: "N selected" title, leading close (✕) `IconButton`, trailing actions ("Select all", "Delete"/"Export") |
| Exit selection mode | Back press, close icon, or completing batch action |
| Reduced motion | Checkbox appears instantly (no scale animation) |
| Haptic | H14 (medium, `LONG_PRESS`) on enter; H15 (tick, `CLOCK_TICK`) on each toggle |
| Accessibility | `stateDescription = "Selected"/"Not selected"` on each card. TopAppBar announces count change via `LiveRegion.Polite`. |
| Screens using selection | Statistics (batch export/delete), Import/Export (batch delete) |

**FINAL.**

### 2.8 Landscape Keyboard Interaction

**Addressed in R17 §3.7. Binding spec:**

| Context | Behavior |
|---------|----------|
| Dialogs with text fields | `verticalScroll()` on content column. `WindowInsets.ime` lifts content. |
| Bottom sheets with forms | Sheet adjusts above keyboard. Content scrolls within sheet. `Modifier.imePadding()` on sheet content. |
| Search fields | TopAppBar search uses `Modifier.imePadding()`. Results list continues below. |
| No landscape-specific layouts | Same as portrait. Scrollable containers handle reduced vertical space. |
| Min visible content | At least the focused text field + one action button must remain visible above keyboard. |

**FINAL. No landscape-specific layouts. IME insets handle everything.**

### 2.9 Accessibility Matrix — Complete

Consolidating all accessibility settings across the design system:

| Setting | Topo Contours | Glass Treatment | Animations | Haptics | Font Scaling |
|---------|--------------|-----------------|------------|---------|-------------|
| **Default** | Animated (8s drift) | Blur + tint + border | Spring-animated | Full map (H1–H15) | System default |
| **Reduce animations** | Hidden entirely | Blur + tint + border (static) | Instant snap (0ms) | Full (haptics ≠ animations) | No change |
| **Reduce transparency** | Hidden | Solid `surfaceContainer` + border | No change | No change | No change |
| **Simplified surfaces** (in-app DataStore) | Hidden | Solid `surfaceContainer` + border | No change | No change | No change |
| **200% font scaling** | No change | No change | No change | No change | All text scales. Metric hero steps down: displayLarge→displaySmall→headlineLarge. Nav bar labels hidden, icon-only + tooltip. |
| **TalkBack** | `contentDescription = null` (decorative) | No change | No change | No change | Snackbar announced. Selection count `LiveRegion.Polite`. All cards have role descriptions. |

**CompositionLocals (FINAL list):**

```kotlin
val LocalReducedMotion = compositionLocalOf { false }
val LocalReduceTransparency = compositionLocalOf { false }
val LocalSimplifiedSurfaces = compositionLocalOf { false }
```

- `LocalReducedMotion`: sourced from `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` or Compose's `LocalReducedMotion` API
- `LocalReduceTransparency`: sourced from `Settings.Secure.REDUCE_TRANSPARENCY` (API 35+), defaults to `false` on older APIs
- `LocalSimplifiedSurfaces`: sourced from DataStore user preference `simplified_surfaces: Boolean`

**FINAL.**

---

## 3. MICRO-DIVERGENCE RESOLUTION

Points where Claude and GPT positions have differed. **Binding resolution** for each:

### 3.1 Celebration Intensity

| Position | Claude | GPT |
|----------|--------|-----|
| First session | Snackbar | Bottom sheet with animation |
| **FINAL** | **Snackbar.** Lighter, less interruptive. No modal celebrations. Settled in R17 §0.4. |

### 3.2 Map Peek Height

| Position | Claude | GPT |
|----------|--------|-----|
| Peek height | 72dp | 96dp |
| **FINAL** | **72dp.** Maximizes map real estate. Drag handle + first content row fits. Settled in R17 §0.2. |

### 3.3 `GlassSurface` Content Padding

| Position | R17 §6.4 (had 16dp internal) | This round (C12 above) |
|----------|------|------|
| **FINAL** | **No internal padding.** `GlassSurface` is a material/surface, not an opinionated card. Callers apply their own padding per card spec (Trip=16dp, StatsSummary=20dp, Challenge=12dp). `GlassCard` convenience wrapper may add 16dp as a default. |

### 3.4 Snackbar Shape: Terrain vs M3

| Position | Could use L1 asymmetric | M3 8dp symmetric |
|----------|------------------------|-------------------|
| **FINAL** | **M3 8dp symmetric.** Snackbar is a system-level transient. Terrain shapes are for persistent surfaces the user interacts with. Same exemption as dialogs. |

### 3.5 Typography Doc: "Inter" vs "System font"

| Position | DESIGN_SYSTEM.md §3 says "Inter" | Code + R10 says system font |
|----------|----------------------------------|----------------------------|
| **FINAL** | **System font for Body/Label.** DESIGN_SYSTEM.md §3 table rows for Body/Label should read "System font (Roboto / Noto Sans)" not "Inter." The doc header already says "System font" after R10 corrections — the per-row "Inter" labels are stale. |

### 3.6 Remaining Zero-Divergence Confirmation

All other design decisions from Rounds 1–17 are **converged between both models.** No outstanding disagreements on:
- Color palette, semantic colors, activity colors
- Motion system (3 springs + 4 durations)
- Spacing scale (10 tokens, 4dp base)
- Shape scale values (L1–L5 dp values)
- Elevation strategy (E0–E3)
- Navigation bar spec (80dp, 32dp radius, glass treatment)
- Button hierarchy (5 variants + FAB)
- Empty state tiers (3 tiers)
- Permission flow (3-step graceful degradation)
- Loading states (no shimmer, crossfade + placeholders)
- Dialog taxonomy (6 types)
- Tooltip system (3 tiers)
- Dynamic color policy (Monet scoped to surfaces)
- Tracking state visualizations (6 states)
- Export flow, Delete All Data flow
- Drag & drop (map layers only)
- Dark mode specifics

**No further micro-divergences exist.**

---

## 4. IMPLEMENTATION PRIORITY ORDER

21 components + 5 file modifications ordered by dependency. Components that other components depend on must be built first.

### 4.1 Dependency Graph

```
Tier 0 (Foundation — no dependencies, enable everything else):
  Accessibility.kt → GlassSurface.kt, TopographicPattern.kt, all motion
  Shape.kt rewrite → All cards, sheets, dialogs, buttons
  DesignSystem.kt fixes → GlassCard, MetricText, PrimaryActionButton

Tier 1 (Core surfaces — needed by most UI):
  TopographicPattern.kt → GlassSurface.kt, EmptyStates
  GlassSurface.kt → All glass-treated components

Tier 2 (Shared components — used across multiple screens):
  Components.kt → Screen implementations
  Snackbar.kt → All user feedback
  AdaptiveText.kt → Dashboard, Trip Detail

Tier 3 (Structural components — screen scaffolding):
  TopAppBars.kt → All screens
  Sheets.kt → Map, export, filters
  Dialogs.kt → Settings, export, delete flows

Tier 4 (Feature components — specific screens):
  Progress.kt → Dashboard, Game, export
  Loading.kt → All screens (loading states)
  Permissions.kt → Onboarding, settings
  EmptyStates.kt (InlineEmptyState) → Statistics, Map, Game

Tier 5 (Module-specific — live in feature modules):
  GpsStatusChip.kt → Dashboard (tracker module)
  MapToolButton.kt → Map (map module)
  TrackingStateVisuals.kt → Dashboard (tracker/dashboard)
  FirstSessionCelebration.kt → Dashboard (app module)
```

### 4.2 Ordered Implementation Plan

| Priority | # | Item | File | Action | Depends On | Effort |
|----------|---|------|------|--------|-----------|--------|
| **T0** | 1 | Accessibility CompositionLocals | `Accessibility.kt` | CREATE | — | S |
| **T0** | 2 | Shape scale rewrite | `Shape.kt` | MODIFY | — | S |
| **T0** | 3 | DesignSystem.kt fixes | `DesignSystem.kt` | MODIFY | — | S |
|  |  | ↳ Fix `PrimaryActionButton` vertical padding (16→12dp) | | | | |
|  |  | ↳ Fix `MetricText` font (Default→Monospace) | | | | |
|  |  | ↳ Fix `GlassCard` border alpha (mode-adaptive) | | | | |
| **T0** | 4 | DESIGN_SYSTEM.md doc fixes | `DESIGN_SYSTEM.md` | MODIFY | — | S |
|  |  | ↳ Fix §3 Body/Label "Inter" → "System font" | | | | |
|  |  | ↳ Fix §8 numbering collision (§8b) | | | | |
| **T1** | 5 | Topographic pattern | `TopographicPattern.kt` | CREATE | #1 | M |
| **T1** | 6 | GlassSurface | `GlassSurface.kt` | CREATE | #1, #5 | M |
|  |  | ↳ GlassTier enum, GlassTokens, GlassTokenValues | | | | |
|  |  | ↳ GlassSurface composable (NO internal padding — C12) | | | | |
|  |  | ↳ Update GlassCard in DesignSystem.kt to delegate | | | | |
| **T2** | 7 | Shared components | `Components.kt` | CREATE | #2 | M |
|  |  | ↳ RidgelineSectionHeader | | | | |
|  |  | ↳ RidgelineDivider + DividerIndent | | | | |
|  |  | ↳ RidgelineSwitch (with ListItem) | | | | |
|  |  | ↳ RidgelineDragHandle | | | | |
|  |  | ↳ RecordingDot | | | | |
|  |  | ↳ ActivityChip | | | | |
| **T2** | 8 | Snackbar extensions | `Snackbar.kt` | CREATE | — | S |
| **T2** | 9 | Adaptive metric text | `AdaptiveText.kt` | CREATE | — | S |
| **T2** | 10 | RidgelineFormat (number formatting) | `Format.kt` | CREATE | — | M |
| **T3** | 11 | Top app bars | `TopAppBars.kt` | CREATE | #2 | S |
|  |  | ↳ RidgelineTopAppBar | | | | |
|  |  | ↳ DashboardTopBar (with RecordingDot slot) | | | | |
| **T3** | 12 | Sheet wrappers | `Sheets.kt` | CREATE | #2, #6 | M |
|  |  | ↳ RidgelineModalSheet | | | | |
|  |  | ↳ MapScreenScaffold | | | | |
| **T3** | 13 | Dialog variants | `Dialogs.kt` | CREATE | — | M |
|  |  | ↳ DestructiveConfirmDialog | | | | |
|  |  | ↳ InputDialog | | | | |
|  |  | ↳ DeleteAllDataFlow (3-step) | | | | |
| **T4** | 14 | Progress components | `Progress.kt` | CREATE | #7 | M |
|  |  | ↳ TrailProgressBar | | | | |
|  |  | ↳ GoalProgressRing | | | | |
|  |  | ↳ TrackingProgressBar | | | | |
|  |  | ↳ RidgelineLoadingIndicator | | | | |
| **T4** | 15 | Loading components | `Loading.kt` | CREATE | — | S |
|  |  | ↳ LoadingGate | | | | |
|  |  | ↳ PlaceholderRow | | | | |
| **T4** | 16 | Permission components | `Permissions.kt` | CREATE | #7 | S |
|  |  | ↳ PermissionRationaleBanner | | | | |
|  |  | ↳ PermissionStage enum | | | | |
| **T4** | 17 | InlineEmptyState | `EmptyStates.kt` | MODIFY (add) | — | S |
| **T5** | 18 | GPS status chip | `GpsStatusChip.kt` (tracker) | CREATE | #7 | S |
| **T5** | 19 | Map tool button | `MapToolButton.kt` (map) | CREATE (extract) | — | S |
| **T5** | 20 | Tracking state visuals | `TrackingStateVisuals.kt` | CREATE | #7, #11 | M |
| **T5** | 21 | First session celebration | `FirstSessionCelebration.kt` | CREATE | #8 | S |
| **T5** | 22 | RidgelineHaptics | `Haptics.kt` (sutils) | CREATE | — | S |

**Effort key:** S = <50 LOC, M = 50–150 LOC, L = 150+ LOC

### 4.3 Critical Path

The longest dependency chain is:

```
Accessibility.kt (#1) → TopographicPattern.kt (#5) → GlassSurface.kt (#6) → Sheets.kt (#12) → MapScreenScaffold
```

**Recommendation:** Implement #1–#4 first (all T0, all S effort, total ~100 LOC). This unblocks everything else and fixes all code inconsistencies.

---

## 5. CORRECTED `GlassSurface` (C12 Fix)

Per resolution C12, removing the hardcoded internal padding from R17 §6.4:

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
            if (showTopoPattern && useGlass && tier == GlassTier.STANDARD) {
                TopographicPattern(
                    modifier = Modifier.matchParentSize(),
                    contourAlpha = TopoAlpha.card,
                )
            }
            // NO internal padding — caller applies per card spec
            Box(content = content)
        }
    }
}
```

**`GlassCard` convenience wrapper (keeps backward compat):**

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
) {
    Box(
        modifier = Modifier.padding(RidgelineSpacing.Lg),  // 16dp default card padding
        content = content,
    )
}
```

---

## 6. COMPLETE COMPONENT INVENTORY

Every design system component, its canonical specification round, and its status:

| # | Component | Spec Source | Shape | Elevation | Status |
|---|-----------|-----------|-------|-----------|--------|
| 1 | `AppTheme` | R8–R10 | — | — | ✅ Exists |
| 2 | `AppShapes` (L1–L5) | §8, R17 §1.1 | L1–L5 dp scale | — | ❌ Needs rewrite |
| 3 | `WaypointShape` | §5 | Percentage identity | — | ✅ Exists |
| 4 | `MomentumPillShape` | §5 | Percentage identity | — | ✅ Exists |
| 5 | `TerrainCardShape` | §5 | Percentage identity | — | ✅ Exists |
| 6 | `DialogShape` | R11 | 28dp full-round | — | ✅ Exists |
| 7 | `AppMotion` | §4, R10 | — | — | ✅ Exists |
| 8 | `RidgelineSpacing` | §7 | — | — | ✅ Exists |
| 9 | `RidgelineGutters` | §7.3 | — | — | ✅ Exists |
| 10 | `GlassSurface` | R17 §6, R18 §5 | Caller's choice | Per tier | ❌ Create |
| 11 | `GlassCard` | R17 §6.6 | L3 (medium) | E1 | ⚠️ Exists, needs delegation |
| 12 | `TopographicPattern` | R17 §5 | — | — | ❌ Create |
| 13 | `TopoAlpha` | R17 §5.5 | — | — | ❌ Create |
| 14 | `MetricText` | §6 | — | — | ⚠️ Exists, fix font |
| 15 | `PrimaryActionButton` | §14.1 | MomentumPill | — | ⚠️ Exists, fix padding |
| 16 | `RidgelineSectionHeader` | §9 | — | — | ❌ Create |
| 17 | `RidgelineDivider` | R12 §3.3 | — | — | ❌ Create |
| 18 | `ActivityChip` | R12 §4.3 | L1 | — | ❌ Create |
| 19 | `RidgelineSwitch` | R15 §5.2 | — | — | ❌ Create |
| 20 | `RidgelineTopAppBar` | R15 §1.4 | — | — | ❌ Create |
| 21 | `RidgelineModalSheet` | R15 §2.3 | 20dp/6dp asym | E2 | ❌ Create |
| 22 | `RidgelineDragHandle` | R15 §2.4 | — | — | ❌ Create |
| 23 | `PermissionRationaleBanner` | R16 §2.3 | L3 | — | ❌ Create |
| 24 | `DestructiveConfirmDialog` | R11 §1.3 | DialogShape | — | ❌ Create |
| 25 | `InputDialog` | R11 §1.4 | DialogShape | — | ❌ Create |
| 26 | `LoadingGate` | R11 §2.2 | — | — | ❌ Create |
| 27 | `PlaceholderRow` | R11 §2.3 | — | — | ❌ Create |
| 28 | `GpsStatusChip` | R16 §6.2 | L1 | — | ❌ Create |
| 29 | `DeleteAllDataFlow` | R16 §5.3 | — | — | ❌ Create |
| 30 | `TrailProgressBar` | R15 §4.6 | — | — | ❌ Create |
| 31 | `GoalProgressRing` | R15 §4.5 | — | — | ❌ Create |
| 32 | `AdaptiveMetricText` | R14 §7.4 | — | — | ❌ Create |
| 33 | `RecordingDot` | R16 §6.2 | Circle (8dp) | — | ❌ Create |
| 34 | `EmptyStateCard` | R10 | L4 | E1 | ✅ Exists |
| 35 | `InlineEmptyState` | §10 Tier 1 | — | — | ❌ Create |
| 36 | `MapToolButton` | R9 | Circle (52dp) | E2 | ⚠️ Partial |
| 37 | `RidgelineHaptics` | R13 §6.4 | — | — | ❌ Create |
| 38 | `RidgelineFormat` | R18 §2.2 | — | — | ❌ Create |
| 39 | `SnackbarTokens` + extensions | R11 §3.3, R18 §2.3 | 8dp symmetric | E3 | ❌ Create |
| 40 | `Accessibility` locals | R18 §2.9 | — | — | ❌ Create |

**Total: 40 items. 8 exist, 5 need fixes, 27 need creation.**

---

## 7. DESIGN SYSTEM COMPLETENESS CHECKLIST

| Domain | Fully Specified? | Canonical Source |
|--------|-----------------|-----------------|
| Color palette (brand + semantic + contextual + activity) | ✅ | DESIGN_SYSTEM.md §2 |
| Typography scale (15 roles) | ✅ | DESIGN_SYSTEM.md §3 (fix "Inter" → "System font") |
| Metrics font (Monospace) | ✅ | DESIGN_SYSTEM.md §6 |
| Spacing system (10 tokens + gutters) | ✅ | DESIGN_SYSTEM.md §7 |
| Shape system (L1–L5 + 3 identity shapes) | ✅ | DESIGN_SYSTEM.md §8 |
| Motion system (3 springs + 4 durations) | ✅ | DESIGN_SYSTEM.md §4 |
| Elevation strategy (E0–E3) | ✅ | DESIGN_SYSTEM.md §16 |
| Glass material (3 tiers) | ✅ | R17 §6, R18 §5 |
| Topographic pattern | ✅ | R17 §5 |
| Card specs (4 types + selection state) | ✅ | DESIGN_SYSTEM.md §13, R18 §2.7 |
| Button hierarchy (5 + FAB) | ✅ | DESIGN_SYSTEM.md §14 |
| Navigation bar | ✅ | DESIGN_SYSTEM.md §15 |
| Top app bar | ✅ | DESIGN_SYSTEM.md §20 |
| Bottom sheets | ✅ | DESIGN_SYSTEM.md §21 |
| List items | ✅ | DESIGN_SYSTEM.md §22 |
| Progress indicators | ✅ | DESIGN_SYSTEM.md §23 |
| Switches & toggles | ✅ | DESIGN_SYSTEM.md §24 |
| Text fields | ✅ | DESIGN_SYSTEM.md §25 |
| Menus | ✅ | DESIGN_SYSTEM.md §26 |
| Empty states (3 tiers + per-screen) | ✅ | DESIGN_SYSTEM.md §10, §27 |
| Permission flow | ✅ | DESIGN_SYSTEM.md §28, R16 |
| Celebrations & milestones | ✅ | DESIGN_SYSTEM.md §29 |
| Export flow | ✅ | DESIGN_SYSTEM.md §30 |
| Delete all data | ✅ | DESIGN_SYSTEM.md §31 |
| Tracking states (6 states) | ✅ | DESIGN_SYSTEM.md §32 |
| Connectivity & sensors | ✅ | DESIGN_SYSTEM.md §33 |
| Date/time pickers | ✅ | R18 §2.1 |
| Number formatting & locale | ✅ | R18 §2.2 |
| Snackbar tokens | ✅ | R11 §3.3, R18 §2.3 |
| Haptics matrix (15 triggers) | ✅ | R13 §6, R18 §2.4 |
| Onboarding → main transition | ✅ | R18 §2.5 |
| Notification design (3 channels) | ✅ | R18 §2.6 |
| Card selection state | ✅ | R18 §2.7 |
| Landscape keyboard | ✅ | R18 §2.8 |
| Accessibility matrix (6 settings) | ✅ | R18 §2.9 |
| Dynamic color (Monet) | ✅ | DESIGN_SYSTEM.md §11 |
| Edge-to-edge & system bars | ✅ | DESIGN_SYSTEM.md §17 |
| Large screen policy | ✅ | DESIGN_SYSTEM.md §18 |
| Text scaling (200%) | ✅ | DESIGN_SYSTEM.md §19 |
| Loading states (no shimmer) | ✅ | R11 §2 |
| Dialog taxonomy (6 types) | ✅ | R11 §1 |
| Tooltips (3 tiers) | ✅ | R13 §3 |
| Drag & drop (map layers) | ✅ | R13 §5 |
| Dark mode specifics | ✅ | DESIGN_SYSTEM.md §2.7 |
| Screen transitions | ✅ | R13 §1 |

**All 45 domains fully specified. Zero gaps remain in the core design system.**

---

## 8. REMAINING TOPICS FOR ROUNDS 19–30

With canonicalization complete, the remaining rounds address implementation refinement and edge cases:

| Round | Topic | Priority |
|-------|-------|----------|
| 19 | Map UI overlay system (metric cards, layer controls, compass, gesture conflicts) | High |
| 20 | Widget design (Glance API, 1–2 widget types) | Medium |
| 21 | Icon set audit (Material Symbols, filled vs outlined consistency) | Medium |
| 22 | String/copy style guide (tone, capitalization, punctuation) | Medium |
| 23 | RTL layout verification (asymmetric shape flipping) | Medium |
| 24 | Animation choreography (coordinated multi-element transitions) | Low |
| 25 | Dark ↔ Light transition animation | Low |
| 26 | Component testing strategy (screenshot tests, preview coverage) | Low |
| 27 | Design token export format (if needed outside Kotlin) | Low |
| 28 | Final consolidation pass — merge all round decisions into DESIGN_SYSTEM.md | High |
| 29 | Implementation verification checklist | Medium |
| 30 | Sign-off | — |

---

## Next: Round 19

**Map UI overlay system** — the most complex visual composition in the app. Map + bottom sheet + metric cards + layer controls + FAB + floating nav bar, all coordinated. Specific topics:
- Metric card positioning (above peek, trailing edge)
- Layer control panel (toggle, reorder)
- Compass/north indicator
- Attribution placement
- Gesture conflict resolution (map pan vs sheet drag vs card interaction)
- Map-specific dark mode basemap switching

GPT: Confirm Round 18 canonicalization or raise blocking objections.
