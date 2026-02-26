# Design Rounds 20–22: Final Convergence

**Status:** LOCKED. Binding decisions for corner cases, animation choreography, and developer experience.

---

## R19 Reconciliation (Pre-Amble)

### Component Count

GPT counted 30 composables + 14 primitives (44). The earlier synthesis counted 54. Code audit reveals **42 composable functions + 17 design primitives = 59 total artifacts**.

The discrepancy: many of those 42 composables are screen-layout composables (e.g., `IdleContent`, `TrackingContent`, `TrackingStatsGrid`) rather than reusable design-system primitives. Splitting properly:

| Category | Count | Examples |
|----------|-------|---------|
| **DS Primitives** (composable) | 18 | `GlassCard`, `MetricText`, `PrimaryActionButton`, `GlassMetricCard`, `GoalProgressRings`, `RidgelineSectionHeader`, `InlineEmptyState`, `EmptyStateCard`, `TrackingFAB`, `RecordingDot`, `PolicyTierChip`, `TechBadge`, `EncouragementBanner`, `SeasonDots`, `ChallengeCard`, `EmptyChallengeCard`, `PermissionRationaleBanner`, `AppTheme` |
| **Screen Compositions** (composable) | 24 | `IdleContent`, `TrackingContent`, `TodayProgressCard`, etc. — consume DS primitives, not part of the DS contract |
| **Token Objects** (non-composable) | 17 | `RidgelineSpacing`, `AppColors`, `AppMotion`, `MotionTokens`, `AppShapes`, named shapes, color schemes, `LoadingMotion`, `LocalReducedMotion` |

**Canonical count for the Ridgeline Design System: 18 composable primitives + 17 token objects = 35 design system artifacts.** Screen compositions are consumers, not part of the DS API surface.

### Color Palette: MaterialKolor vs Hand-Crafted

The seed `#1B6B3A` referenced by GPT is **not used**. The actual seed is `#006874` (Secure Teal), and the palette is **hand-crafted Material 3 HCT** — not generated via MaterialKolor. `Color.kt` contains manually curated values verified against M3 HCT color space. MaterialKolor is not a dependency.

**Decision: LOCKED.** Hand-crafted HCT palette with seed `#006874`. No MaterialKolor dependency.

### Semantic Colors

Both GPT and prior synthesis agree on `success` (#146C2E/#88D78A) and `warning` (#8D5000/#FFB776) with full container/on variants. Already in `Color.kt` and §2.4 of the spec.

**Decision: LOCKED.** Values as specified in §2.4.

---

## Round 20: Corner Cases & Edge Behaviors

### 20.1 Split-Screen / Picture-in-Picture

**Split-screen:**
*   App functions normally. `RidgelineGutters.horizontal` already responds to `LocalConfiguration.current.screenWidthDp`, so the compact breakpoint (<600dp) handles most split-screen widths naturally.
*   Floating nav bar: remains. If width < 320dp, label display is icon-only (already defined in §8b nav labels).
*   Map: full-bleed within the split-screen pane. No layout changes needed.
*   No special split-screen detection code. M3 + responsive gutters handle it.

**Picture-in-Picture (PiP):**
*   **Not supported in v1.** The app does not declare `supportsPictureInPicture` or `android:resizeableActivity="false"`.
*   **Future candidate:** Map screen showing live track during tracking. Would show: map view + recording dot + elapsed time. No controls in PiP — tap to return.
*   **Decision:** Defer. No code changes. If added later, PiP content is a simplified `MapView` + `RecordingDot` + `MetricText(elapsed)`.

### 20.2 Android Auto / Wear OS (Future)

**Android Auto:**
*   **Not planned for v1.** Privacy-first app has no server component for Auto's media/messaging templates.
*   **Future candidate:** Passive display — live tracking metrics (speed, distance, duration) as a navigation-style screen. Read-only.
*   **Design constraint if added:** Must use Auto's `Screen`/`Template` API. DS tokens (colors, typography values) can be referenced but Auto enforces its own layout system.
*   **Decision:** Defer entirely. No Auto manifest entries.

**Wear OS:**
*   **Not planned for v1.**
*   **Future candidate:** Complication showing today's distance/steps + standalone tracking trigger.
*   **Design constraint:** Wear Material 3 is a separate design system. Ridgeline color tokens transfer; shapes/typography do not.
*   **Decision:** Defer. No Wear module.

### 20.3 Battery Optimization Visual Treatments

When the system (or user) constrains tracking frequency, the UI must communicate reduced fidelity without alarming.

**Reduced-frequency tracking states:**

| Trigger | Visual Treatment | Duration |
|---------|-----------------|----------|
| Battery Saver (system) | Dismissible `AssistChip`: "Battery saver active — less frequent updates". `warning` color. Shown once per session. | Until dismissed or session ends |
| Doze mode | No UI. Background tracking pauses naturally. Session resumes on next wake. | Automatic |
| User-selected "Low power" mode (future) | `PolicyTierChip` variant with battery icon + "Low power" label. `secondaryContainer` background. Persistent during tracking. | While mode active |

**Metric staleness indicator:** When GPS fix is >30s old during active tracking, the speed/distance `MetricText` values show a subtle `onSurfaceVariant` tint (instead of `onSurface`) + trailing `labelSmall` timestamp "(12s ago)". Returns to normal tint when fresh data arrives. No animation on the transition — instant swap.

**Track line rendering:** Low-frequency points produce visibly segmented lines on the map. No interpolation — show actual data fidelity. Tooltip on sparse segments: "Reduced accuracy — battery saver was active."

### 20.4 RTL Layout Handling

**Policy: Full RTL support via Compose mirroring defaults.**

*   `Modifier.padding(start = X, end = Y)` — already used everywhere. Never `left`/`right`.
*   `Row` arrangement: `Arrangement.Start` → mirrors automatically.
*   `Icons.AutoMirrored.*` for directional icons (back arrow, forward arrow). Non-directional icons (settings gear, map pin) do NOT mirror.
*   Asymmetric shapes: **DO mirror in RTL.** The diagonal shape language (TerrainCardShape: TL/BR major, TR/BL minor) flips to (TR/BL major, TL/BR minor). Implementation: `CompositionLocalProvider(LocalLayoutDirection provides layoutDirection)` is automatic in Compose — `RoundedCornerShape` with `topStart`/`topEnd`/`bottomStart`/`bottomEnd` already uses logical directions, so shapes auto-mirror.
*   Accent bar in `RidgelineSectionHeader`: `start`-aligned → auto-mirrors to right edge in RTL. ✓
*   Divider asymmetric padding (`start = 52dp, end = 16dp`): uses `start`/`end` → auto-mirrors. ✓
*   Map: Does NOT mirror. Map content is geographic, not directional.
*   Charts/sparklines: Do NOT mirror. Time series reads left-to-right universally.
*   Number formatting: Use `Locale`-aware formatters. Arabic-Indic numerals when locale requires.

**Testing mandate:** Preview every screen with `LocalLayoutDirection provides LayoutDirection.Rtl` before release.

### 20.5 Long Text Truncation Rules

| Element | Max Lines | Overflow | Max Chars (soft) | Example |
|---------|-----------|----------|-----------------|---------|
| Trip name (list) | 1 | `TextOverflow.Ellipsis` | — | "Morning walk throug…" |
| Trip name (detail title) | 2 | `TextOverflow.Ellipsis` | — | Wraps to 2 lines then truncates |
| Challenge name (card) | 2 | `TextOverflow.Ellipsis` | — | "Walk 10km in a sin…" |
| Challenge name (detail) | 3 | `TextOverflow.Ellipsis` | — | Full title in detail screen |
| Section header | 1 | `TextOverflow.Ellipsis` | — | Headers should be concise by design |
| Metric value | 1 | Scale down | — | `autoSizeTextSpec` or step-down (see §19) |
| Metric label | 1 | `TextOverflow.Ellipsis` | — | "AVG SPEE…" |
| Export filename | 1 | `TextOverflow.MiddleEllipsis` (custom) | — | "tracker_20…240601.gpx" |
| Snackbar message | 2 | `TextOverflow.Ellipsis` | 80 chars | Keep snackbar messages ≤ 80 chars |
| Permission rationale desc | 3 | `TextOverflow.Ellipsis` | — | Should fit in 3 lines by design |

**Middle-ellipsis utility** (for filenames):
```kotlin
fun String.middleEllipsis(maxLength: Int): String {
    if (length <= maxLength) return this
    val keep = (maxLength - 1) / 2
    return "${take(keep)}…${takeLast(keep)}"
}
```

**User-generated text** (trip names, export plan names): Enforce 100-char input limit at `OutlinedTextField`. Display truncation is safety net, not primary control.

---

## Round 21: Animation Choreography

### 21.1 Staggered Card Entrance

When a screen with multiple cards enters (e.g., Dashboard idle state with TodayProgressCard, StreakBanner, RecentTripsCard):

*   **Stagger interval:** `MotionTokens.STAGGER_MS` = **80ms** between each card.
*   **Animation per card:** Fade in (0→1 alpha, 200ms tween, `FastOutSlowInEasing`) + translate up (24dp→0dp, `MotionTokens.Standard` spring: damping 1.0, stiffness MediumLow).
*   **Max stagger depth:** 5 cards. Cards beyond the 5th appear with the 5th (no further stagger delay). Prevents long entrance sequences on scrollable lists.
*   **Trigger:** `LaunchedEffect(Unit)` on first composition. NOT on recomposition or configuration change.
*   **Reduced motion:** All cards appear instantly (snap). No fade, no translate, no stagger.

```kotlin
@Composable
fun StaggeredEntrance(
    index: Int,
    maxStagger: Int = 5,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val delay = if (reducedMotion) 0 else (index.coerceAtMost(maxStagger) * MotionTokens.STAGGER_MS)
    var visible by remember { mutableStateOf(reducedMotion) }
    
    LaunchedEffect(Unit) {
        if (!reducedMotion) {
            kotlinx.coroutines.delay(delay.toLong())
            visible = true
        }
    }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                slideInVertically(
                    initialOffsetY = { 64 }, // ~24dp in px
                    animationSpec = MotionTokens.Standard,
                ),
    ) {
        content()
    }
}
```

### 21.2 FAB Appearance / Disappearance

**Entering map screen (FAB appears):**
*   Delay: 200ms after screen transition completes (content is painted).
*   Animation: Scale from 0→1 + fade 0→1, using `MotionTokens.Dramatic` spring (damping 0.6, stiffness 200). The slight bounce is intentional — FAB is an invitation to act.
*   Duration: ~500ms total settle time.

**Leaving map screen (FAB disappears):**
*   Animation: Scale 1→0.8 + fade 1→0, `tween(150ms, FastOutSlowInEasing)`. Fast exit — don't delay the screen transition.
*   The FAB shrinks slightly while fading, not to zero scale (prevents "popping" feel).

**Tracking state morph (idle↔tracking):**
*   FAB icon crossfade: 200ms tween with `MotionTokens.Dramatic` spring for the scale pulse.
*   Color transition: `animateColorAsState` with `MotionTokens.Responsive` spring (damping 0.8, stiffness 800).
*   Size morph (Standard 56dp ↔ Large 96dp on dashboard): `animateDpAsState` with `MotionTokens.Dramatic`.

**Reduced motion:** Instant show/hide. No scale, no fade. Color change is instant.

### 21.3 Bottom Sheet Spring Parameters

**Expand (peek → half → full):**
*   Spring: Damping ratio **0.85**, stiffness **600f**. Slightly underdamped for a natural "drag release" feel that settles quickly.
*   Matches `MotionTokens.Responsive` spirit but tuned for larger displacement.
*   Velocity-aware: Fling velocity feeds into spring's initial velocity parameter.

**Collapse (full → half → peek):**
*   Same spring as expand. Symmetrical feel.

**Dismiss (peek → hidden):**
*   Spring: Damping ratio **1.0**, stiffness **800f**. Critically damped — no bounce on dismissal, it should feel decisive.

**Scrim fade:**
*   `tween(300ms, FastOutSlowInEasing)` synchronized with sheet position. Scrim alpha = `(sheetOffset / fullHeight) * 0.32f`.

**Reduced motion:** Snap to target position. Scrim instant.

### 21.4 Goal Completion Progress Ring

**Incremental progress update (value changes during tracking):**
*   Sweep angle: `animateFloatAsState` with `MotionTokens.Responsive` spring (damping 0.8, stiffness 800). Smooth catch-up to new value.

**Goal completion (progress reaches 100%):**
*   Phase 1 (0–200ms): Ring color transitions from `primary` to `success` via `animateColorAsState(MotionTokens.Snappy)`.
*   Phase 2 (200–600ms): Ring stroke width pulses 6dp → 8dp → 6dp via `MotionTokens.Bouncy` spring. Single overshoot pulse.
*   Phase 3 (600–800ms): Check icon fades in at ring center. `fadeIn(tween(200))`.
*   Total celebration: ~800ms. Subtle, satisfying, no confetti.

**Over-achievement (>100%):**
*   Ring completes full 360° sweep. Excess shown as second arc layer in `tertiary` color overlapping the `success` base.
*   No additional animation beyond normal progress spring.

**Reduced motion:** Instant color change to `success`. Static check icon. No pulse.

### 21.5 Screen-to-Screen Transition Choreography

**Default shared transitions (Material 3 Predictive Back):**
*   Forward navigation (push): `fadeIn(300ms) + slideInHorizontally(from = +30dp)` / outgoing `fadeOut(150ms)`. Outgoing starts immediately, incoming starts at 100ms (overlap).
*   Back navigation (pop): `fadeIn(200ms) + slideInHorizontally(from = -30dp)` / outgoing `fadeOut(250ms) + slideOutHorizontally(to = +30dp)`. Predictive back gesture drives the animation progress.
*   **Spring for spatial movement:** `MotionTokens.Standard` (damping 1.0, stiffness MediumLow). Critically damped — no overshoot on navigation.
*   **Fade:** `tween` (not spring) for opacity. Springs on alpha feel wrong.

**Screen-specific overrides:**

| Transition | Enter | Exit | Notes |
|-----------|-------|------|-------|
| Any → Map | `fadeIn(200ms)` | `fadeOut(150ms)` | No horizontal slide — map is spatial, not directional |
| Dashboard → Trip Detail | `fadeIn(300ms) + slideInVertically(+30dp)` | `fadeOut(150ms)` | Vertical: detail rises from the tapped card |
| Any → Settings | Default horizontal | Default horizontal | Standard push |
| Bottom sheet open | Sheet spring (§21.3) | — | Not a screen transition |

**Predictive back gesture:**
*   Compose Navigation 2.8+ handles this via `AnimatedNavHost` + system back gesture.
*   The outgoing screen scales to 0.9 and shifts 8dp in gesture direction, with rounded corners increasing. This is system-driven — no custom code.

**Reduced motion:** Instant transition. No fade, no slide.

---

## Round 22: Developer Experience

### 22.1 KDoc vs DESIGN_SYSTEM.md

| Content | Location | Rationale |
|---------|----------|-----------|
| Token values, scale tables, color hex codes | **DESIGN_SYSTEM.md only** | Single source of truth for the full system. Avoids stale duplication. |
| Design rationale ("why Okabe-Ito", "why 3:1 shape ratio") | **DESIGN_SYSTEM.md only** | Historical context lives in the spec, not code comments. |
| Component API contract (params, defaults, behavior) | **KDoc on the composable** | Standard Kotlin documentation practice. |
| Usage guidance ("when to use GlassCard vs Surface") | **KDoc on the composable** | Discoverable at call site via IDE. |
| Accessibility behavior ("reduced motion: snap") | **KDoc on the composable** | Critical runtime behavior — must be at the code. |
| Round decisions & iteration history | **DESIGN_ROUND_*.md files** | Archival. Not in DESIGN_SYSTEM.md or code. |

**KDoc template for DS composables:**
```kotlin
/**
 * [One-sentence description].
 *
 * [Usage guidance: when to use, when NOT to use.]
 *
 * Accessibility: [Reduced motion behavior. Screen reader semantics.]
 *
 * @param [param] [Description with default noted if non-obvious.]
 * @see [Related composable or section reference]
 */
```

**No `@sample` tags.** We don't have a samples module. Usage examples go in `@Preview` functions (§22.3).

### 22.2 Drift Prevention

**Tier 1: Existing tooling (immediate, no new dependencies):**
*   **Detekt** (already configured): Add `ForbiddenComment` rule entries for banned patterns:
    - `Dispatchers.IO` (use `DispatchersProvider`)
    - `GlobalScope` (use structured concurrency)
    - `SharedPreferences` (use DataStore)
*   **Detekt `ForbiddenImport`:** Block `android.widget.*`, `androidx.fragment.*`, `android.view.View` in new files. Allows existing files until migrated.
*   **Grep-based CI check** (shell script in CI):
    - Reject `Color(0x` literals outside `Color.kt` and `AppColors` — forces token usage.
    - Reject `RoundedCornerShape(` outside `Shape.kt` — forces named shape usage.
    - Reject `dp` padding literals outside `RidgelineSpacing` usage in new DS components.

**Tier 2: Custom Compose lint (deferred):**
*   Custom lint `Detector` + `IssueRegistry` in a `lint-rules/` module.
*   Candidate rules (build when drift is observed, not preemptively):
    - `HardcodedColorDetector`: Flags `Color(0x…)` in `@Composable` functions.
    - `HardcodedShapeDetector`: Flags `RoundedCornerShape()` constructor calls outside Shape.kt.
    - `MissingTokenUsageDetector`: Flags raw `dp` padding values not from `RidgelineSpacing`.
*   **Decision:** Defer custom lint to post-v1. Grep CI checks provide 80% coverage at 0% maintenance cost.

**Tier 3: Code review convention:**
*   PR checklist item: "Design system tokens used? No hardcoded colors/shapes/spacing?"
*   Reviewer shorthand: "DS drift" as a standard review comment tag.

### 22.3 Preview Strategy

**One `@Preview` per DS primitive composable.** Screen compositions get their own previews.

**Preview naming convention:** `Preview[ComponentName]` (e.g., `PreviewGlassCard`, `PreviewMetricText`).

**Standard preview annotations:**
```kotlin
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large Font", showBackground = true, fontScale = 2.0f)
@Preview(name = "RTL", showBackground = true, locale = "ar")
```

**`@PreviewParameter` providers for common data:**
```kotlin
class ActivityTypeProvider : PreviewParameterProvider<ActivityType> {
    override val values = sequenceOf(Walk, Run, Ride, Vehicle, Still, Unknown)
}

class TrackingStateProvider : PreviewParameterProvider<TrackingState> {
    override val values = sequenceOf(Idle, Active, Paused, GpsSearching)
}
```

**Preview grouping:** Use `@Preview(group = "Ridgeline")` on all DS component previews for filtering in Android Studio's preview panel.

**Multi-preview annotation (DRY):**
```kotlin
@Preview(name = "Light", showBackground = true, group = "Ridgeline")
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, group = "Ridgeline")
@Preview(name = "200% Font", showBackground = true, fontScale = 2.0f, group = "Ridgeline")
annotation class RidgelinePreviews
```

Then on each component: `@RidgelinePreviews @Composable fun PreviewGlassCard() { … }`

**No screenshot testing in v1.** Paparazzi or Roborazzi deferred until component count stabilizes.

### 22.4 Migration Guide: Existing Screens → Ridgeline Tokens

**Step-by-step per screen:**

1. **Wrap in `AppTheme`.** Replace any `MaterialTheme { }` or raw `setContent { }` with `AppTheme { }`. Verify colors resolve.

2. **Replace hardcoded colors.** Find `Color(0x…)` → replace with `MaterialTheme.colorScheme.*` or `AppColors.Adaptive.*` for activity colors.

3. **Replace hardcoded shapes.** Find `RoundedCornerShape(Xdp)` → replace with `MaterialTheme.shapes.*` (which maps to the 5-level diagonal scale) or named shapes (`TerrainCardShape`, `MomentumPillShape`, `WaypointShape`).

4. **Replace hardcoded spacing.** Find raw `dp` padding/spacing → replace with `RidgelineSpacing.*` tokens. Use the semantic pairing table (§7.4) to pick the right token.

5. **Replace raw springs/tweens.** Find `spring(…)` or `tween(…)` with literal values → replace with `AppMotion.*` or `MotionTokens.*` references.

6. **Add section headers.** Replace custom header `Text()` with `RidgelineSectionHeader()`.

7. **Add empty states.** Replace custom empty views with `InlineEmptyState` / `EmptyStateCard` per §10 tier rules.

8. **Verify at 3 font scales** (100%, 150%, 200%) and both light/dark themes.

**Migration is incremental.** One screen per PR. No big-bang. Priority order: Dashboard → Map → Statistics → Game → Settings → Import/Export.

### 22.5 Design System Versioning

**No semver. No version numbers. The DS is an internal, single-consumer system.**

**Evolution rules:**

*   **Additive changes** (new token, new component): Add freely. No migration needed. Document in next DESIGN_ROUND_*.md.
*   **Modification changes** (token value change, shape tweak): Update `DESIGN_SYSTEM.md` + implementation simultaneously in one PR. Find-and-verify all usages. No deprecation period — single consumer.
*   **Removal** (delete token/component): Grep for all usages. Replace with successor. Remove in same PR. Dead code = drift vector.
*   **Breaking shape/color changes:** Preview all affected screens before merge (§22.3 previews make this fast).

**Change tracking:** Each DESIGN_ROUND_*.md file is the changelog. DESIGN_SYSTEM.md is always-current, never historical.

**Guard rail:** If a DS token is used in 5+ files, changing it requires a dedicated PR with before/after preview screenshots in the PR description.
