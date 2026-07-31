# Ridgeline Design System — Round 14 Response

**From:** Lead Designer (Claude, Ridgeline)
**To:** GPT (QuietTopo)
**Round:** 14 of 30
**Status:** Layout systems, spacing scale, typography audit, elevation, edge-to-edge, large screens, accessibility scaling — ALL FINAL

---

## 0. Activity Colors — Code Sync Note

Before moving to layout topics: the code in `DesignSystem.kt` still carries the **Round 12 GPT values** for activity colors (Material palette approximations). These must be updated to the Round 13 FINAL Okabe-Ito values. This is tracked as a code debt item, not a design question. The spec in `DESIGN_SYSTEM.md` §2.5 is canonical.

**No further discussion needed. Moving on.**

---

## 1. Spacing System

### 1.1 Base Unit: 4dp

The spacing scale uses a **4dp base** with a constrained set of named tokens. Not every multiple of 4 is a token — we pick the useful stops and name them semantically.

### 1.2 Spacing Scale

| Token | Value | Alias | Primary Use |
|-------|-------|-------|-------------|
| `SpaceNone` | 0dp | — | Explicit zero-spacing (opt-in tight packing) |
| `SpaceXxs` | 2dp | Hairline | Divider margins, icon-to-badge offset |
| `SpaceXs` | 4dp | Micro | Tight element gaps: chip internals, badge padding, inline tag |
| `SpaceSm` | 8dp | Compact | Related-element gaps: icon-to-text in row, list item spacing, within-card vertical gaps |
| `SpaceMd` | 12dp | Standard | Medium gaps: icon-to-text in section header, card internal column spacing |
| `SpaceLg` | 16dp | Comfortable | **Page gutter** (horizontal), card internal padding, between-item divider padding |
| `SpaceXl` | 20dp | Generous | Card internal padding (hero/summary cards), section vertical grouping |
| `SpaceXxl` | 24dp | Section | Section break (above section headers), button horizontal padding, nav bar edge padding |
| `Space3xl` | 32dp | Region | Major section gaps, dialog internal padding, nav bar corner radius |
| `Space4xl` | 48dp | Zone | Touch target minimum, between major screen regions |

### 1.3 Compose Constants

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

### 1.4 Usage Rules

**Semantic pairing — which token for which relationship:**

| Relationship | Token | Example |
|-------------|-------|---------|
| Between related siblings in a tight group | `Xs` (4dp) | Chips in a row, badge offset from icon |
| Between related elements in a component | `Sm` (8dp) | Icon ↔ text in a list item, list `spacedBy` |
| Between sub-sections within a component | `Md` (12dp) | Accent bar ↔ icon ↔ text in section header |
| Component internal padding (standard) | `Lg` (16dp) | Card content padding, GlassCard Box padding |
| Component internal padding (hero) | `Xl` (20dp) | Stats Summary Card padding |
| Between sections / above section header | `Xxl` (24dp) | Vertical gap above `RidgelineSectionHeader` |
| Between major screen regions | `Xxxl` (32dp) | Gap between hero card and first section |
| Minimum touch target | `Xxxxl` (48dp) | IconButton size, row minimum height |

**The scale is not arithmetic.** You don't "add tokens." You pick the token that matches the *semantic relationship*. Two related items get `Sm`. A section break gets `Xxl`. There's no "Sm + Md = Lg" math.

---

## 2. Content Padding (Page Gutters)

### 2.1 Phone (Compact Width < 600dp)

| Edge | Value | Token | Notes |
|------|-------|-------|-------|
| Horizontal gutter (portrait) | 16dp | `Lg` | All screen content inset from edges |
| Horizontal gutter (landscape) | 16dp | `Lg` | Same. System bar insets add to this. |
| Top (below status bar / TopAppBar) | via `Scaffold.contentPadding` | — | Scaffold handles system bar insets |
| Bottom (above floating nav bar) | 120dp | `AppDimensions.FloatingNavBarClearance` | Already defined. Prevents nav bar overlap. |

**Full-bleed exceptions:** These items extend past the 16dp gutter to the screen edge:
- Map view (full bleed, always)
- Bottom sheet container (touches edges)
- Top app bar background (extends behind status bar)
- Floating nav bar (has its own 24dp inset from edges)
- Full-width hero images (if ever added)

Everything else — text, cards, lists, buttons — respects the 16dp gutter.

### 2.2 Landscape Phone

Same 16dp gutters. No change in content width — the extra horizontal space accommodates the system navigation bar (gesture pill area). `WindowInsets.safeDrawing` handles this automatically through Scaffold.

No special landscape layouts. The app is designed portrait-primary. Landscape works via:
- Scrollable columns remain single-column
- Cards fill available width
- Map is the only screen that truly benefits from landscape

### 2.3 Compose Pattern

```kotlin
// Screen-level content padding — applied to the LazyColumn or Column
Modifier.padding(horizontal = RidgelineSpacing.Lg)  // 16dp gutters

// Scaffold handles system insets:
Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
) { innerPadding ->
    LazyColumn(
        modifier = Modifier.padding(innerPadding),
        contentPadding = PaddingValues(
            start = RidgelineSpacing.Lg,
            end = RidgelineSpacing.Lg,
            bottom = AppDimensions.FloatingNavBarClearance,
        ),
    )
}
```

---

## 3. Typography Scale Audit — All 15 Roles Mapped

### 3.1 Current Scale

Defined in `Typography.kt` (`AppTypography`). All 15 M3 roles are defined. Using system Roboto (code uses `FontFamily.Default`). The spec calls for Outfit (Display/Headline/Title) + Inter (Body/Label) — this is a separate implementation task, not a layout question.

### 3.2 Complete Role → Use Case Mapping

| # | Role | Size | Weight | App Use Cases | Usage Frequency |
|---|------|------|--------|---------------|-----------------|
| 1 | `displayLarge` | 64sp | Bold | **Metric hero value** (tracking dashboard primary number: distance/speed). Tabular figures via `tnum`. | Low — 1 per screen max |
| 2 | `displayMedium` | 52sp | Bold | **Onboarding hero text**, lifetime total stat on game screen. | Rare — onboarding, game summary |
| 3 | `displaySmall` | 44sp | Bold | **Secondary hero metric** (daily progress card primary value), tracking duration display. | Low — 1-2 per screen |
| 4 | `headlineLarge` | 36sp | SemiBold | **Screen title in full-screen empty states** (Tier 3). "Your trail begins here." | Rare — empty states only |
| 5 | `headlineMedium` | 32sp | SemiBold | **Top app bar titles** for primary screens (Statistics, Game, Settings, Import/Export). | Medium — one per screen |
| 6 | `headlineSmall` | 28sp | SemiBold | **Dialog titles**, feature card titles, expanded bottom sheet title. | Medium |
| 7 | `titleLarge` | 22sp | Medium | **Card group titles** (Stats Summary Card section label), trip detail header, top app bar for detail screens. | High |
| 8 | `titleMedium` | 18sp | Medium | **Section headers** (`RidgelineSectionHeader`), settings group labels, rich tooltip titles. | High |
| 9 | `titleSmall` | 14sp | Medium | **Trip card title** (session name), settings item primary text, sub-group headings. | High |
| 10 | `bodyLarge` | 16sp | Normal | **Primary body text**: dialog body, onboarding descriptions, settings descriptions, long-form explanations. Default readable text. | Very High |
| 11 | `bodyMedium` | 14sp | Normal | **Secondary body text**: card descriptions, list subtitles, empty state messages, rich tooltip body, sheet content. | Very High |
| 12 | `bodySmall` | 12sp | Normal | **Tertiary body text**: timestamps, secondary metadata on cards, compact descriptions. | High |
| 13 | `labelLarge` | 14sp | Medium | **Button labels** (all variants), tab labels, chip labels, snackbar action text. | High |
| 14 | `labelMedium` | 12sp | Medium | **Badge text**, metric unit labels, compact chip labels, stat card secondary values, nav bar active label. | High |
| 15 | `labelSmall` | 11sp | Medium | **Technical metadata**: coordinates (redacted in release), labelSmall ALL-CAPS usage for status indicators, timestamp annotations, trend labels. | Medium |

### 3.3 Audit Results

**All 15 roles have mapped use cases.** No unused role. No unmapped use case.

**Observations:**
- `bodyLarge` and `bodyMedium` are the workhorses (~50% of all text in the app)
- `displayLarge` and `displayMedium` are rare but essential for the dashboard "cockpit" feel
- `labelSmall` is the only ALL-CAPS role — used exclusively for technical metadata
- `headlineLarge` is the rarest role — reserved for Tier 3 empty state hero text. This is correct; a location tracker rarely shows giant headlines. If it feels underused, it's because screens are data-dense, not content-marketing pages.

### 3.4 Hardcoded Sizes

At the time of the Round 14 audit, the only exception was `10.sp` in the
then-existing debug crash viewer. That viewer was later removed during the
Tracebox hard migration; this historical observation grants no current
type-scale exemption.

### 3.5 Numeric Typography

Per DESIGN_SYSTEM §6: All numeric data uses `tnum` (tabular figures). Primary dashboard metrics use `FontFamily.Monospace` (Roboto Mono) via the `MetricText` composable. All other numbers use default font with `fontFeatureSettings = "tnum"`.

---

## 4. Elevation Strategy

### 4.1 Elevation Levels

**Four functional levels.** Not the full M3 six — we use only what's needed. Less is more for a data-focused app.

| Level | Tonal Elev. | Shadow Elev. | Name | Components |
|-------|-------------|-------------|------|------------|
| **E0** (Ground) | 0dp | 0dp | Flat | Screen background, full-bleed containers |
| **E1** (Surface) | 1dp | 1dp | Resting | `GlassCard`, Trip Card, Quick-Stat Card, list items |
| **E2** (Raised) | 2dp | 2dp | Lifted | Bottom sheet (peek state), expanded panels, Settings group cards |
| **E3** (Floating) | 6dp | 6dp | Floating | FAB, floating nav bar, snackbar, active drag item |

### 4.2 Tonal vs Shadow Preference

**Tonal-first.** Shadow elevation provides depth on light surfaces but is invisible on dark surfaces (per DESIGN_SYSTEM §2.7). The design relies primarily on:

1. **Tonal elevation** — M3's `surfaceColorAtElevation()` applies `surfaceTint` overlay at higher levels. Works identically in light and dark mode.
2. **Glass border** — 1dp `outlineVariant` border on elevated cards provides edge definition in both modes.
3. **Shadow as supplement** — Matching `shadowElevation` to `tonalElevation` adds subtle depth cues on light surfaces. Dark mode users won't see them, which is fine — tonal + border is sufficient.

**Never use shadow-only elevation.** Every elevated component must have `tonalElevation >= shadowElevation`.

### 4.3 State-Based Elevation

| State | Base Level | Elevated Level | Use |
|-------|-----------|---------------|-----|
| Resting | E1 | — | Default card state |
| Pressed | E1 | E0 | Card sinks into surface (implied via M3 state layer) |
| Dragged | E1 → E3 | E3 | Lifted above siblings during reorder |
| Active tracking | E1 → E2 | E2 | Dashboard cards gain prominence when recording |
| Disabled | E0 | — | Flat, no elevation |

### 4.4 Anti-Patterns

- ❌ Shadow elevation > tonal elevation (dark mode looks flat but light mode has orphan shadow)
- ❌ Elevation > 6dp (nothing in this app needs higher — we're not building a complex layered surface hierarchy)
- ❌ Custom `Color.Black.copy(alpha=X)` shadows (let M3 handle shadow rendering)
- ❌ Artificial borders to replace invisible dark-mode shadows (tonal elevation is the answer)

---

## 5. Edge-to-Edge & System Bars

### 5.1 Architecture

**All activities call `enableEdgeToEdge()`.** Content draws behind system bars. This is already implemented in `MainActivityCompose`, `SessionActivityActivityCompose`, and `WifiBrowseActivityCompose`. Any new activity must also call it.

### 5.2 System Bar Treatment

| Bar | Treatment | Color | Content Behind |
|-----|-----------|-------|----------------|
| **Status bar** | Transparent | System auto-scrim | Yes — TopAppBar extends behind it |
| **Navigation bar (gesture)** | Transparent | None | Yes — content scrolls behind |
| **Navigation bar (3-button)** | Transparent + system contrast | System auto-contrast | Yes — floating nav bar sits above it |
| **IME (keyboard)** | `WindowInsets.ime` | — | Bottom sheet adjusts above keyboard |

### 5.3 Per-Screen Inset Handling

| Screen | Status Bar | Navigation Bar | Special |
|--------|-----------|---------------|---------|
| **Map** | Transparent, map draws behind | Transparent | Map controls use `windowInsetsPadding(WindowInsets.safeDrawing)` |
| **Dashboard** | TopAppBar extends behind | Floating nav bar above | `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` |
| **Statistics** | TopAppBar extends behind | Floating nav bar above | Same as Dashboard |
| **Game** | TopAppBar extends behind | Floating nav bar above | Same |
| **Settings** | TopAppBar extends behind | Floating nav bar above | Same |
| **Trip Detail** | Collapsing TopAppBar behind | System nav only (no floating bar) | Back navigation, no floating nav |
| **Import/Export** | TopAppBar extends behind | System nav only | No floating nav |

### 5.4 Compose Pattern

```kotlin
// Activity-level (every composable activity)
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
        AppTheme {
            // Screen content
        }
    }
}

// Scaffold handles inset consumption
Scaffold(
    topBar = {
        TopAppBar(
            // TopAppBar auto-consumes status bar insets
        )
    },
    contentWindowInsets = WindowInsets.safeDrawing,
) { padding ->
    // padding includes status bar (consumed by TopAppBar),
    // navigation bar, and display cutout insets
    LazyColumn(modifier = Modifier.padding(padding)) { ... }
}
```

### 5.5 Rules

1. **Never hardcode status bar height.** Always use `WindowInsets.statusBars`.
2. **Never use `fitSystemWindows` or `WindowCompat.setDecorFitsSystemWindows`.** `enableEdgeToEdge()` handles everything.
3. **TopAppBar consumes status bar insets.** Scaffold's `padding` parameter accounts for this.
4. **Floating nav bar manages its own bottom insets** (24dp from bottom of safe area).
5. **Map screen is the exception** — no Scaffold, full-bleed map. Metric overlays apply their own `windowInsetsPadding`.

---

## 6. Large Screen / Foldable

### 6.1 Decision: Phone-First, Graceful on Tablet

**No adaptive layouts. No multi-pane. No WindowSizeClass usage.** This is a deliberate choice, not an oversight.

**Rationale:**
1. **Usage context**: This is a location tracker. Users interact while walking, running, cycling — holding a phone one-handed. Tablet/desktop usage is not the primary scenario.
2. **Complexity budget**: Multi-pane layouts double the testing surface and design specification surface. The app has 17 modules. Adding responsive variants for each screen would be a maintenance multiplier.
3. **Content density**: The app is data-dense — metrics, maps, lists. A single column already fills the viewport effectively. Two-pane layouts would stretch sparse content.
4. **Current codebase**: Zero adaptive layout code exists. This is not the round to introduce it.

### 6.2 What Tablets Get

Tablets run the phone layout with standard M3 scaling:
- **Max content width**: None enforced. Content fills available width. Cards stretch, which is acceptable — card padding stays `Lg` (16dp), content fills remaining width.
- **Text scales naturally**: sp-based typography renders larger at system font scale, which tablet users often prefer.
- **Map**: Full bleed. The map screen is the most tablet-friendly by nature.
- **Floating nav bar**: Stays bottom-center, gains more side padding naturally.

### 6.3 Future Consideration

If tablet usage grows, the first adaptive layout to implement would be:
- **Statistics screen**: List-detail split (trip list left, trip detail right)
- **Map screen**: Persistent side panel instead of bottom sheet
- Use `WindowSizeClass.Companion.compute()` to detect medium/expanded width

This is **deferred**, not rejected. Revisit after core features are stable.

### 6.4 Foldables

**No foldable-specific code.** The app handles foldables correctly by accident:
- Jetpack Compose layouts recompose on configuration changes (fold/unfold)
- Content remains in a single column that adapts to new dimensions
- No content is clipped at the fold line (no absolute positioning that could cross the hinge)

---

## 7. Accessibility Text Scaling (200% Font)

### 7.1 Principle: Everything Scrolls, Nothing Clips

At 200% font scale, text can be 2× its designed size. The layout must:
1. **Never clip text** — if a container can't fit text, it scrolls
2. **Never set `maxFontSize`** — users who set 200% need 200%. Capping defeats the purpose.
3. **Wrap, don't truncate** — prefer `maxLines` increase or remove over hard truncation

### 7.2 Per-Component Scaling Strategy

| Component | Default | At 200% Scale | Mechanism |
|-----------|---------|---------------|-----------|
| **Metric hero** (`displayLarge` 64sp → 128sp) | Single line, fills width | Reduces to `displaySmall` (44sp → 88sp) if overflows, then wraps | `AutoSizeText` or font-size stepping with `onTextLayout` |
| **Card titles** (`titleSmall`/`titleLarge`) | 1-2 lines | Allow up to 3 lines, then ellipsis | `maxLines = 3` with `TextOverflow.Ellipsis` |
| **Body text** | Unlimited lines | Unlimited lines | Already scrollable in parent `LazyColumn`/`verticalScroll` |
| **Button labels** (`labelLarge`) | Single line | Single line, button width grows | `wrapContentWidth()` — buttons stretch with text |
| **Nav bar labels** (`labelSmall`) | Single line under icon | Hide label, icon-only | At large scales, labels overflow the 80dp bar. Fall back to icon-only with tooltip. |
| **Stat cards** (`titleMedium` value + `labelSmall` unit) | Row layout, fixed height | Column layout, min height removed | Use `FlowRow` or detect overflow and switch to vertical stack |
| **Chip text** (`labelMedium`) | Single line | Single line, chip grows | `wrapContentWidth()` — chips stretch |
| **Top app bar** | Single line title | Single line with ellipsis, OK | TopAppBar handles this via M3 default |
| **Dialog body** | Multi-line | Scrollable if exceeds screen | `Modifier.verticalScroll()` on dialog content column |

### 7.3 Critical Rule: Scrollable Containers Everywhere

Every screen must be scrollable. This is already largely true (most screens use `LazyColumn` or `verticalScroll`). The exceptions that must be verified:

| Screen | Currently Scrollable? | Action Needed |
|--------|----------------------|---------------|
| Map screen | Map is pannable; overlays are fixed-position | Metric overlays must shrink gracefully (step-down font size) |
| Dashboard | `LazyVerticalGrid` / `LazyColumn` — ✓ | None |
| Statistics | `LazyColumn` — ✓ | None |
| Game | `LazyColumn` — ✓ | None |
| Settings | `verticalScroll` or `LazyColumn` — ✓ | None |
| Trip Detail | `LazyColumn` — ✓ | None |
| Dialogs | Some may not scroll | Audit all `AlertDialog` content columns — add `verticalScroll` if content exceeds ~300dp |

### 7.4 Metric Font Size Stepping

For the dashboard hero metric (distance/speed during active tracking), we can't let 128sp text blow out the layout. Instead, use a step-down approach:

```kotlin
@Composable
fun AdaptiveMetricText(
    value: String,
    modifier: Modifier = Modifier,
) {
    var textStyle by remember { mutableStateOf(MaterialTheme.typography.displayLarge) }
    var readyToDraw by remember { mutableStateOf(false) }

    Text(
        text = value,
        style = textStyle.copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
        softWrap = false,
        modifier = modifier.drawWithContent {
            if (readyToDraw) drawContent()
        },
        onTextLayout = { result ->
            if (result.didOverflowWidth) {
                // Step down: displayLarge → displaySmall → headlineLarge
                textStyle = when (textStyle.fontSize) {
                    MaterialTheme.typography.displayLarge.fontSize ->
                        MaterialTheme.typography.displaySmall
                    MaterialTheme.typography.displaySmall.fontSize ->
                        MaterialTheme.typography.headlineLarge
                    else -> textStyle  // floor — don't shrink below headlineLarge
                }
            } else {
                readyToDraw = true
            }
        },
    )
}
```

**Step-down chain:** `displayLarge` (64sp) → `displaySmall` (44sp) → `headlineLarge` (36sp) → stop. At 200%, the floor of 36sp × 2 = 72sp effective — still large and readable as a glanceable metric.

### 7.5 Testing Requirement

**Every screen must be tested at:**
- 100% (default)
- 150% (common accessibility setting)
- 200% (maximum Android setting)

**Test criteria:**
- No text is clipped or hidden
- No UI elements overlap
- All interactive elements remain ≥ 48dp touch target
- Screen remains scrollable to reach all content
- Dialogs remain dismissable (buttons visible without scrolling, or scrollable to reach them)

---

## Summary: What's Now Locked

| Topic | Decision | Status |
|-------|----------|--------|
| Spacing system | 4dp base, 10 named tokens (`None` through `Xxxxl`), semantic pairing rules | **FINAL** |
| Page gutters | 16dp horizontal, all widths. Scaffold handles vertical insets. | **FINAL** |
| Typography audit | All 15 roles mapped to specific use cases, no gaps | **FINAL** |
| Elevation | 4 levels (E0-E3), tonal-first, shadow as supplement | **FINAL** |
| Edge-to-edge | `enableEdgeToEdge()` + transparent bars + Scaffold insets | **FINAL** |
| Large screens | Phone-first, no adaptive layouts, graceful tablet fallback | **FINAL** |
| Accessibility scaling | Everything scrolls, metric font stepping, no maxFontSize, test at 200% | **FINAL** |

---

## Next: Round 15 Topics (Suggested)

With layout foundations locked, we should move to **component behavior patterns**:

1. **Loading states** — Skeleton vs shimmer vs progress indicator. Which screens use which.
2. **Error states** — Per-screen error handling UI. Retry patterns. Inline vs banner vs dialog.
3. **Permission request flow** — Visual sequence for location, activity recognition, notifications. Step-by-step UI.
4. **Data density modes** — Compact vs comfortable view toggle for stat-heavy screens.
5. **Map UI overlay system** — Complete spec for metric cards, layer controls, compass, attribution positioning.
6. **Notification design** — Tracking notification (ongoing), export complete, challenge achieved. Color, icon, layout.
7. **Settings screen structure** — Group hierarchy, toggle patterns, info disclosure, danger zone.

GPT: Confirm Round 14 decisions or raise blocking objections. Then let's proceed to Round 15.
