# Tracker Android — Ridgeline Design System

> **Status:** Rounds 1–28 of 30 complete. All decisions locked. Rounds 29–30 = final prose synthesis.

---

## Round 26–28: Final Resolutions

### Color Seed: `#1B6B3A` (Canopy Green) — CONFIRMED

The user's stated vision is **"forest green / emerald, adventurous / outdoorsy."** The prior `#006874` (Secure Teal) is a blue-green that reads as clinical, not forest. It was a placeholder from early M3 theming, not an intentional brand decision.

**Decision:** `#1B6B3A` (Canopy Green) is the canonical Ridgeline seed. All primary, secondary, tertiary, and surface tokens must be re-derived from this seed via the Material 3 HCT color space. The teal palette in `Color.kt` and in the token tables below is **superseded** and will be replaced during implementation.

- **Seed:** `#1B6B3A` — hue ≈ 145° (true green), chroma ≈ 48, tone ≈ 38
- **Brand personality alignment:** Forest canopy, trail markers, topographic maps — matches "adventurous/outdoorsy"
- **Tertiary direction:** Warm amber/rust (complementary to green) — preserves the existing Sunset Rust intent
- **Activity colors:** Unchanged (Okabe-Ito accessible palette, independent of brand seed)
- **Implementation:** Run `#1B6B3A` through [Material Theme Builder](https://m3.material.io/theme-builder) to generate full light/dark schemes, then hand-audit WCAG AA on every token pair

### Theme Root: `AppTheme` backed by `MaterialTheme` — CURRENT

**Decision:** `AppTheme` remains the single composition root and currently delegates to `androidx.compose.material3.MaterialTheme` with Ridgeline color, typography, and shape tokens. The resolved Material 3 artifacts do not expose a public `MaterialExpressiveTheme` composable or public `MaterialTheme.motionScheme` accessor to Kotlin callers, so the code and docs intentionally avoid claiming those APIs until the dependency supports them.

**Current behavior:**
- Feature code consumes tokens via standard `MaterialTheme` accessors.
- Ridgeline motion tokens are the approved public animation specs for route transitions, standard effects, app-specific animations, and infinite loops.
- Revisit a direct `MaterialExpressiveTheme` swap only when the dependency provides that API.

**Current `AppTheme.kt`:**
```kotlin
MaterialTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes, content = content)
```

### Migration Strategy: Direct Replacement, No Shim — CONFIRMED

**Decision:** No compatibility shim layer. The codebase is already 100% Compose with all 24+ screens consuming `MaterialTheme.colorScheme.*` tokens. A shim would create dual-system drift risk with zero benefit.

**Approach:**
1. Update `Color.kt` token values (teal → green) — affects all screens instantly
2. Keep `AppTheme.kt` on `MaterialTheme` until `MaterialExpressiveTheme` is available from dependencies
3. Verify screen-by-screen in priority order (Dashboard → Map → Statistics → Game → Settings → Import/Export)
4. One PR per screen for review, but the token swap itself is a single atomic PR

**Why no shim:**
- All screens already use `MaterialTheme.colorScheme.*` — no legacy `View`/XML/`AppCompat` theme references
- Token values are centralized in `Color.kt` — single source of truth
- Shim layers (e.g., `Bridge` themes, `CompositionLocal` overrides) add indirection, confuse contributors, and eventually need removal

### Accessibility v1 Minimum Bar — CONFIRMED

| Requirement | Criteria | Verification |
|-------------|----------|--------------|
| **Contrast** | WCAG AA: 4.5:1 normal text, 3:1 large text (≥18sp bold / ≥24sp) & UI components | Accessibility Scanner, manual spot-check |
| **Touch targets** | ≥ 48dp on all interactive elements | Compose `Modifier.minimumInteractiveComponentSize()`, preview inspection |
| **Font scaling** | Full functionality at 200% system font scale; all content scrollable | Preview at `fontScale = 2.0f`, manual QA |
| **Screen reader** | `contentDescription` on all interactive & informative images/icons; meaningful traversal order | TalkBack walkthrough per screen |
| **State announcements** | `stateDescription` on toggles/switches ("On"/"Off"); `Role.Switch`/`Role.Checkbox` | Code review + TalkBack |
| **Reduced motion** | `LocalReducedMotion` respected: springs → instant, animation loops → static | Toggle "Remove animations" in Developer Options |
| **Reduced transparency** | Glass blur → opaque `surfaceContainer`; contour lines hidden | Toggle accessibility setting |
| **Focus indicators** | Default Compose focus rings; no custom suppression | Keyboard/D-pad navigation test |

**Not in v1 (deferred):**
- WCAG AAA (7:1) contrast
- Switch Access / external switch device testing
- Custom `AccessibilityNodeInfo` for complex widgets (map, charts)
- Automated accessibility CI gate (lint rule deferred to post-v1)

---

## Master Document Outline

> Implementation-first: tokens (values) → components (composables) → patterns (usage) → migration → testing → governance.
> Every section number is final. Sub-section numbers are stable anchors for cross-references.

---

### PART 0 — QUICK REFERENCE

#### 0.1 At-a-Glance Card
One-page cheat sheet: seed color, font stack, shape DNA, motion springs, spacing scale, key composable names. Print-friendly. No prose.

#### 0.2 Design System Inventory
Canonical artifact count: 18 composable primitives + 17 token objects. Table listing every artifact, its file path, and its category. (Currently §40)

#### 0.3 Decision Log Index
Table mapping each round (R1–R28) to its primary decisions and the sections they landed in.

---

### PART I — TOKENS (Values)

#### 1. Color System
Canonical brand seed (`#1B6B3A`), HCT derivation rules, and complete light/dark token tables.

##### 1.1 Brand Seed & Generation Rules
Seed value, HCT coordinates, Material Theme Builder pipeline, hand-audit checklist. (Supersedes §2 header)

##### 1.2 Primary: Canopy Green
Full 4-token set (primary, onPrimary, primaryContainer, onPrimaryContainer) × light/dark. (Replaces §2.1 Primary)

##### 1.3 Secondary: Trail Sage
Derived secondary palette. Name TBD post-generation — likely shifts from "Trail Slate" to a green-complementary earth tone. (Replaces §2.1 Secondary)

##### 1.4 Tertiary: Sunset Rust
Warm amber/rust complement. Retained intent, values re-derived from green seed. (Replaces §2.1 Tertiary)

##### 1.5 Error Palette
Standard M3 error tokens, unchanged. (§2.1 Error)

##### 1.6 Neutral & Surface Tokens
Surface stack (Lowest → Highest), background, onSurface, outline variants. Re-derived from green seed. (Replaces §2.2)

##### 1.7 Utility Tokens
Inverse, scrim, surfaceTint. (Replaces §2.3)

##### 1.8 Extended Semantic Colors: Success, Warning
Non-M3 custom tokens. CompositionLocal delivery. (§2.4)

##### 1.9 Contextual Activity Colors (Okabe-Ito)
Walk, run, ride, vehicle, still, unknown — accessibility-first palette. Independent of brand seed. (§2.5)

##### 1.10 Glass & Translucency Tokens
Tint alpha, border alpha/width, blur radius, contour alphas. Light/dark variants. (§2.6)

##### 1.11 Dark Mode Color Adjustments
Tonal elevation behavior, glass tuning, contour alpha bumps, shadow policy, trackActive handling. (§2.7)

##### 1.12 Dynamic Color (Monet) Policy
Android 12+ scope rules: surfaces are Monet'd, brand colors are locked. User toggle behavior. (§11)

##### 1.13 Color Token Validation Checklist
WCAG AA contrast audit matrix for every foreground/background pair. Must pass before any token ships.

---

#### 2. Typography System
Typeface selection, full 12-style scale, numeric rendering rules, and metrics font policy.

##### 2.1 Typeface Roles
Display/Headline/Title: Outfit (Google Fonts). Body/Label: System font (Roboto/Noto Sans). Rationale: zero APK cost for body, brand identity for headlines. (§3 header)

##### 2.2 Full Type Scale (12 Styles)
Display L/M/S, Headline L/M/S, Title L/M/S, Body L/M/S, Label L/M/S. Weight, size, line height, letter spacing for each. (§3 Scale)

##### 2.3 Metrics Font: Monospace
`FontFamily.Monospace` for primary data readouts only. Scope rules (what qualifies vs. what doesn't). (§6)

##### 2.4 Tabular Figures (`tnum`)
All numeric data in non-monospace text uses tabular figures to prevent layout shift. Font feature setting.

##### 2.5 Czech Diacritic & i18n Audit Criteria
Line height validation for tall diacritics (ř, ž, ů). Ascender/descender clipping test requirements. (R9–R10)

##### 2.6 Text Scaling (200%) Behavior
No `maxFontSize`. Metric hero stepping rules (Display → Headline floor). Overflow detection via `onTextLayout`. (§19)

---

#### 3. Spacing System
4dp-base scale, responsive gutters, semantic pairing rules.

##### 3.1 Scale (10 Tokens: None → Xxxxl)
Full table: None(0), Xxs(2), Xs(4), Sm(8), Md(12), Lg(16), Xl(20), Xxl(24), Xxxl(32), Xxxxl(48). `RidgelineSpacing` object. (§7.1, §7.2)

##### 3.2 Responsive Page Gutters
`RidgelineGutters.horizontal`: 16dp compact / 24dp medium / 32dp expanded. Full-bleed exceptions. (§7.3)

##### 3.3 Semantic Pairing Rules
Which token for which relationship (siblings, components, sections, regions). (§7.4)

##### 3.4 Bottom Clearance
`AppDimensions.FloatingNavBarClearance = 120.dp`. Scaffold `contentWindowInsets` pattern. (§7.3)

---

#### 4. Shape System
Asymmetric diagonal DNA, 5-level scale, identity shapes.

##### 4.1 Diagonal Shape Scale (L1–L5)
3:1 major:minor ratio. ExtraSmall(6/2) → ExtraLarge(24/8). `AppShapes` mapping. (§8)

##### 4.2 Identity Shapes: Waypoint & Momentum Pill
WaypointShape (FAB, percentage-based). MomentumPillShape (buttons, asymmetric stadium). Not part of scale. (§5, §8)

##### 4.3 Bottom Sheet Shape
Asymmetric top corners: topStart 20dp, topEnd 6dp. Terrain DNA at container level. (§21)

##### 4.4 Dialog Shape
28dp uniform radius. M3 standard. (§8)

---

#### 5. Elevation System
Tonal-first 4-level strategy with shadow supplement.

##### 5.1 Elevation Levels (E0–E3)
Ground(0/0), Resting(1/1), Lifted(2/2), Floating(6/6). Component mapping. (§16)

##### 5.2 State-Driven Elevation
Pressed → E0, Dragged → E3, Active tracking → E2, Disabled → E0. (§16)

##### 5.3 Glass Border as Dark-Mode Edge
1dp `outlineVariant` border replaces invisible shadows in dark mode. (§16)

---

#### 6. Motion System
Named springs, duration tokens, choreography framework, MotionScheme integration.

##### 6.1 Named Springs
SecureSnap (NoBouncy, Medium stiffness), TactileActive (0.65 damping, MediumLow), SpatialGlide (0.8 damping, Low). `AppMotion` object. (§4)

##### 6.2 Duration Tokens
Micro(150ms), Short(250ms), Medium(400ms), Long(600ms). (§4)

##### 6.3 Loading Motion
Enter/Exit/Pulse durations, pulse alpha range. `LoadingMotion` object. (§4)

##### 6.4 MotionScheme Integration
The current Material 3 dependency does not expose public `MaterialTheme.motionScheme` APIs to Kotlin callers. Use `RidgelineMotion`, `ridgeline*` generic springs, and tween helpers for standard transitions and app-specific animations until MotionScheme becomes public. (RC-fix clarification)

##### 6.5 Staggered Card Entrance
60ms stagger, 6-card max depth, fade+translate per card. (§38.1)

##### 6.6 FAB Choreography
Enter/exit/tracking-morph specs. Scale + fade + color animation. (§38.2)

##### 6.7 Bottom Sheet Springs
Expand (0.85 damping, 600 stiffness), dismiss (critically damped), scrim sync. (§38.3)

##### 6.8 Goal Ring Animation
Incremental sweep, 3-phase completion (color → pulse → check), over-achievement arc. (§38.4)

##### 6.9 Screen Transitions
Forward (fade+slide), back (predictive), map (fade-only), trip detail (vertical slide). (§38.5)

##### 6.10 Reduced Motion Behavior
All springs → instant snap. Animation loops → static. Glass blur retained (not an animation). (§12)

---

### PART II — COMPONENTS (Composables)

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
    G1(8.dp, 0.90f, 0.92f, 0.18f, 0.14f),
    G2(14.dp, 0.84f, 0.88f, 0.24f, 0.18f),
    G3(20.dp, 0.78f, 0.82f, 0.30f, 0.20f),
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

### PART III — PATTERNS (Usage Guidelines)

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

### PART IV — IMPLEMENTATION

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

### Round Coverage Verification (R1–R28)

| Rounds | Theme | Sections |
|--------|-------|----------|
| R1–R3 | Foundations, privacy, Compose-only constraints | §0.1, §21.5, §19 |
| R4–R7 | Color seed, shapes, spacing, motion | §1, §3, §4, §6 |
| R8–R11 | Typography, metrics font, accessibility text | §2, §20.1 |
| R12–R13 | Color convergence, activity palette, card system | §1.9, §10 |
| R14–R17 | Layout, navigation, FAB, edge-to-edge, app chrome | §3, §9, §18 |
| R18–R19 | Token pipeline, component inventory, DX | §0.2, §21 |
| R20–R22 | Polish: glass, elevation, dark mode, choreography | §1.10, §5, §6.5–6.9 |
| R23–R25 | Empty states, permissions, export, celebrations, testing | §13–§17, §20 |
| R26–R28 | Color resolution, theme root, migration, accessibility bar | Resolutions above, §19, §20.2 |

## 1. Brand Personality
Privacy-first, fully local location & activity tracker for Android. Reliable, secure, modern, and slightly adventurous.

## 2. Color Palette

**Seed:** `#1B6B3A` (Canopy Green). Generated via Material 3 HCT color space.
Balances trust and security (deep, reliable cool tones) with adventure and activity (vibrant, energetic warm tones).

### 2.1 Core Palette — Complete Token Reference

#### Primary: Canopy Green

| Token | Light | Dark |
|-------|-------|------|
| `primary` | `#1B6B3A` | `#7BDA97` |
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
| `surfaceTint` | `#1B6B3A` | `#7BDA97` |
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
| `inversePrimary` | `#7BDA97` | `#1B6B3A` |
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
*   **Map Screen:** Standard FAB (`WaypointShape`, Canopy Green, TactileActive). Quick-Stat Cards for metrics overlay.
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

### 30.3 Settings Visual Grammar

*   Every settings destination uses `SettingsGroupCard`; do not mix loose rows with grouped cards on the same page.
*   Section titles may use a 20dp orientation icon. Each row uses a distinct concept icon in a 40dp themed tile.
*   Tracking sources use the native `ic_tracking_source_*` vector family in both simple and advanced controls. Keep a 32dp viewport, rounded geometry, distinct silhouettes, and Compose theme tint; visually verify each asset at its rendered 24–27dp size in light and dark themes.
*   Current picker and slider values use `SettingsValuePill`, never supporting-text prose.
*   Related rows are separated with the shared 72dp-inset `SettingsRowDivider`.
*   Explanations use `SettingsNoticeCard` with neutral/info/warning/danger tones instead of page-specific banner implementations.
*   Static metadata uses `SettingsSummaryItem`; interactive rows retain full-row button, switch, or radio semantics.
*   Destructive groups and rows use `SettingsItemTone.Danger` so irreversible actions cannot be mistaken for ordinary navigation.

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
