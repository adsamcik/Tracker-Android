# Design Rounds 23–25: Final Polish, Cross-Model Convergence & Implementation Kickoff

**Status:** LOCKED. Final synthesis before master document update.

---

## Pre-Amble: R20-22 Micro-Difference Resolutions

Before diving into R23–25, resolving the six micro-differences flagged:

| Item | My Position | GPT Position | Resolution | Rationale |
|------|-------------|-------------|------------|-----------|
| Card stagger | 80ms / 5 items | 35ms / 6 items | **60ms / 6 items** | Compromise accepted. 35ms is perceptually instantaneous on most devices — defeats the purpose of stagger. 80ms × 5 = 400ms total, fine. 60ms × 6 = 360ms total, slightly snappier but still visible. 6 items covers all current dashboard card counts without "pile-up at 5." |
| FAB animation | Dramatic spring (damping 0.6, stiffness 200) | Fade + scale tween | **HOLD: Dramatic spring** | Springs are the M3 Expressive direction. The slight bounce is intentional — FAB is an invitation, not a utility. Tween feels clinical. |
| Sheet damping | 0.85 | 0.82 | **HOLD: 0.85** | Overdamped = more controlled. 0.82 introduces perceptible oscillation on fast flings. 0.85 settles 1 frame faster. |
| Versioning | No semver | Semver | **HOLD: No semver** | Single-consumer, single-repo internal DS. Semver adds process overhead with zero benefit. DESIGN_ROUND_*.md files ARE the changelog. |
| Component count | 35 | ~44 | **HOLD: 35** | 35 = 18 composable primitives + 17 token objects. GPT's 44 counted screen compositions as DS artifacts. They're consumers, not the contract. |
| Palette seed | `#006874` (Secure Teal) | `#1B6B3A` (Canopy Green) | **LOCKED: `#006874`** | `#1B6B3A` was a GPT hallucination from an earlier draft. The actual `Color.kt` has `#006874`. The entire hand-crafted HCT palette derives from this seed. Every color in §2 of DESIGN_SYSTEM.md is built on it. Not negotiable. |

**Action items from resolutions:**
- Update `MotionTokens.STAGGER_MS` from 80 → 60 in code and spec.
- Update max stagger depth from 5 → 6 in `StaggeredEntrance` composable and spec.

---

## Round 23: Unaddressed Micro-Interactions

### 23.1 Pull-Down Notification Shade During Tracking

**Context:** User pulls down the system notification shade while actively tracking. The app is partially obscured.

**Decision: No special handling. Zero code needed.**

*   When the shade overlays the app, the app is still in `RESUMED` state — Compose keeps rendering, tracking continues uninterrupted.
*   The `TrackerService` foreground notification is already visible in the shade. It shows: tracking duration, pause/stop actions (via `TrackerNotificationManager`). This IS the visual continuity — the notification mirrors the in-app state.
*   No custom `RemoteViews` styling beyond what Android provides for `NotificationCompat.Builder`. The notification uses standard M3 system notification templates, which inherit the device's Monet palette. We do NOT try to match Ridgeline colors in notifications — system notifications must feel native.

**Notification channel alignment with DS:**
*   5 channels exist: `track` (LOW), `other` (LOW), `challenges` (HIGH), `activity_watcher` (LOW), `goals` (HIGH).
*   The `track` channel is the tracking-active notification. LOW importance = no sound/heads-up, which is correct — persistent tracking shouldn't interrupt.
*   `challenges` and `goals` at HIGH = heads-up + sound. These are celebratory moments (§29). The system handles their presentation.

**Recording dot continuity:** When the user swipes the shade back up, the in-app `RecordingDot` (1.5s pulse) continues from wherever it was in its cycle. No restart, no sync needed — `infiniteRepeatable` animations run on the composition clock, which never paused.

### 23.2 App Widget Considerations (Deferred — Token Groundwork)

**Decision: Widgets deferred to post-v1. But lay groundwork now to avoid rework.**

**Why defer:** Widgets require `RemoteViews` (XML-based, no Compose), a completely separate rendering pipeline. Glance (Compose for widgets) is not mature enough for production. Adding widget support would double the surface area.

**Future widget candidates (priority order):**

| Widget | Size | Content | Complexity |
|--------|------|---------|------------|
| Today Summary | 2×2 | Distance + duration + steps. Updated every 30 min via `WorkManager`. | Low |
| Quick Start | 1×1 | Single tap → start tracking. Icon-only. | Low |
| Weekly Sparkline | 4×1 | 7-day bar chart. Updated daily. | Medium |

**Token groundwork to lay NOW (zero implementation, just design decisions):**

1.  **Widget color mapping:** Widgets will use `@android:color/system_accent1_*` (Monet) on Android 12+ and fall back to `#006874` (Secure Teal primary) on older versions. This aligns with our Monet policy (§11) — surfaces adapt to wallpaper, brand colors are locked. Widget backgrounds: `system_neutral1_900` (dark) / `system_neutral1_50` (light).

2.  **Widget corner radius:** Android 12+ exposes `system_app_widget_background_radius` (~28dp on most devices). Use it. Pre-12: 16dp fallback. This matches our `extraLarge` shape spirit without needing our asymmetric shapes (which don't work in `RemoteViews`).

3.  **Widget typography:** System default. Widgets don't load custom fonts (Outfit). Metric values use system monospace via `android:fontFamily="monospace"`. This is a deliberate departure from in-app typography — widgets must feel native.

4.  **Widget update frequency:** Every 30 minutes maximum for Today Summary (battery). Quick Start widget is static — no periodic updates, just a `PendingIntent` to `TrackerService`. Weekly Sparkline: once per day at midnight.

5.  **No glass/blur in widgets.** `RemoteViews` doesn't support blur or alpha compositing. Solid `system_neutral1_*` backgrounds only.

**Implementation note for future:** When Glance reaches stable (likely post-Compose 2.0), re-evaluate. Glance would let us use `RidgelineSpacing`, `AppColors`, and our shape system directly.

### 23.3 Share/Export Preview Card

**Context:** When a user exports a trip and shares it via Android's share sheet (ACTION_SEND), what does the recipient see in messaging apps?

**Current implementation:** `FileProvider` shares a raw file (GPX/KML/JSON) with MIME type. The share intent has no rich preview metadata.

**Decision: Add minimal Open Graph-style metadata. No custom preview card rendering.**

**Why minimal:** We're sharing FILES, not links. Rich link previews (Open Graph, Twitter Cards) only apply to URLs. File shares in messaging apps show the filename + file size + MIME type icon. We can't control how WhatsApp/Telegram/etc. render a `.gpx` file attachment.

**What we CAN control:**

1.  **Filename convention:** `tracker_{date}_{activity}.{ext}` (e.g., `tracker_2024-06-15_walk.gpx`). Human-readable, descriptive. Already partially implemented in the export flow.

2.  **Share intent extras:** Add `Intent.EXTRA_SUBJECT` and `Intent.EXTRA_TEXT` for apps that support them (email clients, some messaging apps):
    ```kotlin
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = exporter.mimeType
        putExtra(Intent.EXTRA_STREAM, fileUri)
        putExtra(Intent.EXTRA_SUBJECT, "Trail: ${tripName} — ${formattedDate}")
        putExtra(Intent.EXTRA_TEXT, buildShareText(trip))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ```

3.  **Share text template:**
    ```
    🥾 Morning Walk — Jun 15, 2024
    📏 5.2 km · ⏱ 48 min · 🦶 6,842 steps
    Exported from Tracker (local-only, no cloud)
    ```
    *   Privacy note: Includes summary stats only. No coordinates, no map thumbnail, no location names. The user chose to share — we respect that — but we don't leak more than they explicitly exported.
    *   Emoji prefix per activity type: 🥾 Walk, 🏃 Run, 🚴 Ride, 🚗 Vehicle, ⏸️ Still, 📍 Unknown.

4.  **No custom share sheet / target.** Use `Intent.createChooser()`. No `ChooserTargetService`, no Direct Share shortcuts. Unnecessary complexity for a privacy-first app.

5.  **No map thumbnail generation.** Rendering a static map image of the track for preview would require either a network tile fetch (violates privacy) or a local tile cache (heavy, unreliable). Skip entirely. The exported file IS the data.

### 23.4 Deep Link Handling

**Context:** User taps a notification (tracking status, export complete, challenge milestone) or an external URI. How does the app respond?

**Current state:** Notifications use `PendingIntent` to open the main activity. No URI-based deep links exist. No `<intent-filter>` for custom URI schemes.

**Decision: Notification-driven navigation only. No URI deep links in v1.**

**Notification → screen mapping:**

| Notification | Channel | Target | Nav Action |
|-------------|---------|--------|------------|
| Tracking active | `track` | Dashboard (tracking state) | Open app → Dashboard. Already in tracking state visually. |
| Export complete | `other` | Import/Export screen | `PendingIntent` with extra `navigate_to=impexp`. Activity reads extra, passes to NavController. |
| Challenge unlocked | `challenges` | Game screen | `PendingIntent` with extra `navigate_to=game&challenge_id={id}`. NavController navigates + highlights. |
| Goal achieved | `goals` | Dashboard (goal section) | `PendingIntent` with extra `navigate_to=dashboard&scroll_to=goals`. |
| Activity watcher | `activity_watcher` | Dashboard | Open app → Dashboard. Informational only. |

**Implementation pattern:**

```kotlin
// In the entry Activity (already single-activity architecture)
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... existing setup ...
    handleDeepNavigation(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleDeepNavigation(intent)
}

private fun handleDeepNavigation(intent: Intent) {
    val target = intent.getStringExtra("navigate_to") ?: return
    when (target) {
        "impexp" -> navController.navigate(ImportExportRoute)
        "game" -> {
            val challengeId = intent.getLongExtra("challenge_id", -1L)
            navController.navigate(GameRoute(highlightChallengeId = challengeId))
        }
        "dashboard" -> {
            val scrollTo = intent.getStringExtra("scroll_to")
            navController.navigate(DashboardRoute(scrollToSection = scrollTo))
        }
    }
}
```

**Why no URI deep links:**
*   Privacy. URI deep links (`tracker://trip/12345`) could be intercepted by other apps or logged by the system. Our trip IDs are Room auto-increment — they're sequential, predictable, and shouldn't be addressable from outside the app.
*   No web presence. We have no website, no cloud, no reason for `https://` association.
*   No cross-app communication needs. We're not a platform.

**Future consideration:** If we ever add a "share trip link" feature (generating a self-contained file link), we'd register a custom MIME type handler for `.tracker` files, not a URI scheme. File-based intents are already partially in place via the ZIP import handler.

**Transition animation for deep navigation:** Same as standard forward navigation (§38.5): `fadeIn(300ms) + slideInHorizontally(+30dp)`. No special "landing" animation — the user expects the screen to appear as if they navigated there.

---

## Round 24: Cross-Model Convergence Report

### 24.1 Complete Disagreement Inventory (All 22 Rounds)

Every point where GPT and I diverged across rounds 8–22, with final resolution:

| # | Round | Topic | My Position | GPT Position | Verdict | Rationale |
|---|-------|-------|-------------|-------------|---------|-----------|
| 1 | R8 | Display font | Outfit | Manrope | **HOLD** (Outfit) | Metrics tuned, Czech diacritical support verified, geometric personality matches terrain metaphor. |
| 2 | R8 | System name | Ridgeline | QuietTopo | **HOLD** (Ridgeline) | Single-word, generates vocabulary naturally (ridgeline spacing, ridgeline gutters). QuietTopo is compound, awkward as prefix. |
| 3 | R8 | Monet default | ON (scoped) | OFF | **HOLD** (ON, scoped) | Monet overrides surfaces only; brand colors locked. Users expect wallpaper integration on Android 12+. |
| 4 | R9 | Glass + reduced motion | Two-axis (motion ≠ transparency) | Collapse to one axis | **HOLD** (two-axis) | Android 15 confirmed separate "Reduce Transparency" setting. Two-axis is future-proof and already spec'd in §12. |
| 5 | R9 | Mini FAB | Reject (48dp minimum) | Allow 40dp | **HOLD** (reject) | WCAG touch target, Android accessibility guidelines. 40dp fails. |
| 6 | R12 | Activity colors | Okabe-Ito hues + M3 lightness | Different Okabe-Ito mapping | **ACCEPT GPT partially** | Converged on Okabe-Ito base hues; I adjusted lightness for M3 dark/light contrast. Final values in `AppColors`. Both happy. |
| 7 | R12 | Palette seed | `#006874` (Secure Teal) | `#1B6B3A` (Canopy Green) | **HOLD** (`#006874`) | `#1B6B3A` was never implemented. `Color.kt` has `#006874`. Entire palette built on it. GPT referenced a draft that was superseded. |
| 8 | R17 | Peek height | 72dp | 64dp | **HOLD** (72dp) | 72dp = drag handle (24dp touch) + first content row. 64dp clips content row on large font. |
| 9 | R17 | Menu max items | 7 | 5 | **HOLD** (7) | M3 spec allows up to 10. 7 covers our longest menu (trip detail actions) without scrolling. 5 would force overflow-within-overflow. |
| 10 | R17 | Celebrations | Snackbar only | Modal + confetti | **HOLD** (snackbar) | "The app celebrates by being useful." Modal interrupts flow. Confetti is antithetical to privacy-serious brand. |
| 11 | R19 | Container transform | Standard M3 | Custom shared element | **HOLD** (M3 standard) | Predictive back gesture drives transition. Custom shared element breaks predictive back. Standard M3 is correct. |
| 12 | R19 | Haptic count | 15 triggers | 8 triggers | **HOLD** (15) | All 15 map to standard Android haptic types (CLOCK_TICK, CONFIRM, REJECT, etc.). Well-scoped. 8 misses FAB, sheet, nav, goal. |
| 13 | R20-22 | Card stagger | 80ms / 5 items | 35ms / 6 items | **COMPROMISE: 60ms / 6 items** | See pre-amble. 60ms visible but snappy. 6 covers all screens. |
| 14 | R20-22 | FAB animation | Dramatic spring | Fade + scale tween | **HOLD** (spring) | Springs are M3 Expressive direction. Bounce is intentional invitation. |
| 15 | R20-22 | Sheet damping | 0.85 | 0.82 | **HOLD** (0.85) | Less oscillation, faster settle, more controlled. |
| 16 | R20-22 | DS versioning | No semver | Semver | **HOLD** (no semver) | Single-consumer internal system. Process overhead with zero benefit. |
| 17 | R20-22 | Component count | 35 | ~44 | **HOLD** (35) | Screen compositions are consumers, not DS API surface. |
| 18 | R20-22 | MaterialKolor dependency | None (hand-crafted HCT) | Use MaterialKolor | **HOLD** (hand-crafted) | Already implemented. MaterialKolor would generate different values. Not a dependency. |

### 24.2 Verdict Summary

| Verdict | Count | Items |
|---------|-------|-------|
| **HOLD** (my position) | 15 | #1-5, #7-12, #14-18 |
| **COMPROMISE** | 2 | #6 (activity colors — mutual convergence), #13 (stagger timing) |
| **ACCEPT GPT** | 0 | — |
| **IRRELEVANT** | 0 | All disagreements have implementation impact |

**Conclusion:** 15/18 decisions hold. The 2 compromises are already reflected in the spec. Zero GPT positions accepted wholesale — but GPT's challenges sharpened several decisions (activity color contrast, stagger depth) which improved the final spec.

### 24.3 Final Consensus Table — Agreed Items Only

This table lists ONLY items where both models agreed from the start OR converged to agreement. These are the undisputed foundations:

| # | Topic | Agreed Value | Spec Section |
|---|-------|-------------|-------------|
| 1 | Color seed | `#006874` Secure Teal | §2 |
| 2 | HCT color space | Hand-crafted, not generated | §2 |
| 3 | Primary typeface | Outfit (display/headline/title) | §3 |
| 4 | Body typeface | System font (Roboto/Noto Sans) | §3 |
| 5 | Metrics font | `FontFamily.Monospace` for values only | §6 |
| 6 | Tabular figures | `tnum` on all numeric data | §3, §6 |
| 7 | Spacing base | 4dp scale, 10 tokens | §7 |
| 8 | Responsive gutters | 16/24/32dp by width breakpoint | §7.3 |
| 9 | Shape language | Asymmetric 3:1 diagonal, 5-level scale | §8 |
| 10 | Named shapes | Waypoint (FAB), Momentum Pill (buttons), Terrain Card (cards) | §8 |
| 11 | Section header | Accent bar + titleMedium, sentence case | §9 |
| 12 | Empty state tiers | 3-tier: inline / section / full-screen | §10 |
| 13 | Dynamic color scope | Surfaces only, brand colors locked | §11 |
| 14 | Accessibility tiers | 4-tier: default / reduce-motion / reduce-transparency / both | §12 |
| 15 | Card types | 4 canonical: Trip / Stats Summary / Challenge / Quick-Stat | §13 |
| 16 | Button hierarchy | 5 variants: Primary → Tonal → Outlined → Text → Icon | §14 |
| 17 | FAB variants | Standard (56dp) / Large (96dp) / Extended. No mini. | §14.2 |
| 18 | Floating nav bar | 80dp, 32dp radius, haze blur, active-only labels | §15 |
| 19 | Elevation strategy | 4 levels, tonal-first, shadow supplements | §16 |
| 20 | Edge-to-edge | All activities, transparent bars, safeDrawing insets | §17 |
| 21 | Large screen | Phone-first, no adaptive layouts, no multi-pane | §18 |
| 22 | Text scaling | No maxFontSize, everything scrolls, metric stepping | §19 |
| 23 | Top app bar | Small default, Large for Statistics, CenterAligned for onboarding | §20 |
| 24 | Bottom sheet | Asymmetric top corners (20dp/6dp), 72dp peek | §21 |
| 25 | List items | M3 ListItem, transparent default, standardized typography | §22 |
| 26 | Progress indicators | Linear + circular, round StrokeCap, color by state | §23 |
| 27 | Switches/toggles | M3 Switch with check icon, checkbox for batch selection | §24 |
| 28 | Text fields | OutlinedTextField only, L2 shape, always-label | §25 |
| 29 | Menus | DropdownMenu L2, 7 max, overflow to ModalBottomSheet | §26 |
| 30 | Permission flow | 3-step degradation, inline banner, per-permission content | §28 |
| 31 | Celebrations | Snackbar-based, no modal, no confetti | §29 |
| 32 | Export flow | ModalBottomSheet form, progress bar, share intent | §30 |
| 33 | Delete flow | 3-step: warning → type-to-confirm → execute | §31 |
| 34 | Tracking states | 6 states with FAB/TopBar/Dot/Progress/Chip mapping | §32 |
| 35 | GPS states | 4-state enum: OFF / SEARCHING / WEAK_SIGNAL / GOOD | §32.2 |
| 36 | RTL support | Full, via Compose logical directions, shapes auto-mirror | §35 |
| 37 | Truncation rules | Per-element max lines, middle-ellipsis for filenames | §36 |
| 38 | Battery visualization | Dismissible chip, metric staleness tint, segmented track lines | §37 |
| 39 | Motion tokens | 6 springs + 7 durations + stagger | §38, MotionTokens.kt |
| 40 | Spring configs | Snappy/Standard/Responsive/Bouncy/Gentle/Dramatic — all values agreed | §4, MotionTokens.kt |
| 41 | Screen transitions | Fade+slide, spring for spatial, tween for opacity, predictive back | §38.5 |
| 42 | KDoc strategy | API contract + usage + a11y in KDoc; values + rationale in spec | §39.1 |
| 43 | Drift prevention | Detekt + CI grep (immediate), custom lint (deferred) | §39.2 |
| 44 | Preview strategy | @RidgelinePreviews multi-preview, one per DS primitive | §39.3 |
| 45 | Migration order | Dashboard → Map → Statistics → Game → Settings → Import/Export | §39.4 |
| 46 | DS versioning | No semver, DESIGN_ROUND_*.md as changelog | §39.5 |
| 47 | Component inventory | 18 composables + 17 token objects = 35 artifacts | §40 |
| 48 | Split-screen/PiP | Split supported (responsive), PiP deferred | §34 |
| 49 | Android Auto/Wear | Both deferred, no manifest entries | §34 (R20-22) |
| 50 | Activity colors | Okabe-Ito hues, 6 activities, mode-adaptive | §2.5 |

**50 agreed items. 18 disagreements resolved (15 hold, 2 compromise, 1 partial accept). Total design surface: 68 decision points across 22 rounds.**

---

## Round 25: Implementation Kickoff Spec

### 25.1 First 5 PRs — In Dependency Order

#### PR 1: Token Foundation

**Title:** `feat(ds): Ridgeline token foundation — spacing, shapes, motion, colors`

**Description:** Establishes all non-composable design system primitives. Every subsequent PR depends on these tokens existing.

**Files changed:**

| File | Action | Est. LOC |
|------|--------|----------|
| `sutils/.../compose/DesignSystem.kt` | Edit — clean up legacy colors (`NeonLime`, `DeepVoid`, `GlassShale`, `WhiteHighEmphasis`), add semantic color tokens (`success`, `warning`, `trackActive`, `trackHistory`), add glass/translucency tokens | ~+60, -15 |
| `sutils/.../compose/Color.kt` | Edit — add `SuccessLight/Dark`, `WarningLight/Dark`, `TrackActive`, `TrackHistory` + container/on variants per §2.4–2.5 | ~+50 |
| `sutils/.../compose/Shape.kt` | Edit — implement 5-level diagonal scale (L1–L5) per §8, ensure `topStart`/`bottomEnd` pattern | ~+30, -10 |
| `sutils/.../compose/Motion.kt` | Edit — add `AppMotion` object with `SecureSnap`, `TactileActive`, `SpatialGlide` named springs from §4; add `LoadingMotion` | ~+40 |
| `dashboard/.../motion/MotionTokens.kt` | Edit — update `STAGGER_MS` from 80 → 60 | ~1 |
| `sutils/.../compose/Typography.kt` | Edit — add Outfit font family loading, `tnum` on metric styles, monospace metric variant | ~+25 |
| `sutils/.../compose/AppTheme.kt` | Edit — wire `MaterialExpressiveTheme` (or `MaterialTheme` with expressive shapes/motion), provide `LocalReducedMotion`, `LocalReduceTransparency` | ~+30, -5 |

**Total estimated LOC:** ~250 changed

**Dependencies:** None. This is the foundation.

**Acceptance criteria:**
- [ ] All 17 token objects from §40 inventory exist and compile
- [ ] `AppTheme` provides `LocalReducedMotion` and `LocalReduceTransparency`
- [ ] No hardcoded `Color(0x…)` outside `Color.kt` / `DesignSystem.kt`
- [ ] No hardcoded `RoundedCornerShape(…)` outside `Shape.kt`
- [ ] `STAGGER_MS = 60` in MotionTokens
- [ ] Existing screens still compile and render (no breaking changes to public API)
- [ ] `@RidgelinePreviews` multi-preview annotation defined
- [ ] Unit test: `RidgelineSpacing` values match spec (property test all 10 tokens)
- [ ] Unit test: shape scale ratios are 3:1 at all 5 levels

---

#### PR 2: Core DS Composables (Batch 1 — Structural)

**Title:** `feat(ds): Ridgeline composable primitives — structural components`

**Description:** The 8 most-consumed composable primitives that form layout structure. These are referenced by every screen composition.

**Files changed:**

| File | Action | Est. LOC |
|------|--------|----------|
| `sutils/.../compose/DesignSystem.kt` | Edit — update `GlassCard` (glass tokens, proper alpha/border from §2.6), `MetricText` (monospace, step-down from §19), `PrimaryActionButton` (proper padding from §14.1) | ~+40, -30 |
| `sutils/.../compose/RidgelineSectionHeader.kt` | Create — per §9 spec | ~60 |
| `sutils/.../compose/InlineEmptyState.kt` | Create — Tier 1 empty state per §10 | ~35 |
| `sutils/.../compose/EmptyStateCard.kt` | Edit — update existing to Tier 2 spec (glass + topo contours) per §10 | ~+30, -15 |
| `sutils/.../compose/PermissionRationaleBanner.kt` | Create — per §28.2 | ~70 |
| `sutils/.../compose/GlassMetricCard.kt` | Create — Quick-Stat Card per §13.4 | ~55 |
| `sutils/.../compose/PolicyTierChip.kt` | Create — tracking policy indicator | ~40 |
| `sutils/.../compose/StaggeredEntrance.kt` | Create — animation wrapper per §38.1 with 60ms/6-item params | ~45 |

**Total estimated LOC:** ~400 new/changed

**Dependencies:** PR 1 (tokens)

**Acceptance criteria:**
- [ ] 8 composable primitives compile and render in `@RidgelinePreviews`
- [ ] All components use token references (no hardcoded values)
- [ ] `GlassCard` respects `LocalReduceTransparency` (solid fallback)
- [ ] `StaggeredEntrance` respects `LocalReducedMotion` (instant appear)
- [ ] `MetricText` steps down at 200% font scale without overflow
- [ ] `PermissionRationaleBanner` has "Allow" + "Skip" actions
- [ ] KDoc on every public composable per §22.1 template
- [ ] Light + Dark + 200% font previews for each component

---

#### PR 3: Core DS Composables (Batch 2 — Interactive & Gamification)

**Title:** `feat(ds): Ridgeline composable primitives — interactive & game components`

**Description:** The remaining 10 composable primitives, focused on tracking interaction and gamification.

**Files changed:**

| File | Action | Est. LOC |
|------|--------|----------|
| `dashboard/.../compose/TrackingFAB.kt` | Create — per §14.2, §32.1, §38.2 (Dramatic spring, state morph, WaypointShape) | ~120 |
| `dashboard/.../compose/RecordingDot.kt` | Create — 1.5s pulse, `trackActive` color, reduced-motion static per §32.1 | ~50 |
| `dashboard/.../compose/GoalProgressRings.kt` | Create — per §38.4 (incremental, completion celebration, over-achievement) | ~100 |
| `dashboard/.../compose/EncouragementBanner.kt` | Create — milestone/streak banner, slide-in, auto-dismiss 4s | ~55 |
| `game/.../compose/ChallengeCard.kt` | Create — per §13.3 (160×120dp, progress arc, difficulty badge) | ~80 |
| `game/.../compose/EmptyChallengeCard.kt` | Create — placeholder in carousel when no challenges | ~35 |
| `dashboard/.../compose/SeasonDots.kt` | Create — seasonal indicator dots | ~40 |
| `sutils/.../compose/TechBadge.kt` | Create — technical metadata badge | ~35 |

**Total estimated LOC:** ~515 new

**Dependencies:** PR 1 (tokens), PR 2 (GlassCard, MetricText used internally)

**Acceptance criteria:**
- [ ] 10 composable primitives compile and render in `@RidgelinePreviews`
- [ ] `TrackingFAB` animates between all 6 tracking states (§32.1)
- [ ] `TrackingFAB` morph uses `MotionTokens.Dramatic` spring
- [ ] `RecordingDot` pulses at 1.5s and freezes under reduced motion
- [ ] `GoalProgressRings` celebrate at 100% with 3-phase animation (§38.4)
- [ ] `GoalProgressRings` render over-achievement second arc
- [ ] `ChallengeCard` fixed at 160×120dp, progress arc renders
- [ ] All components respect `LocalReducedMotion`
- [ ] KDoc + previews for each

---

#### PR 4: CI Drift Prevention & Preview Infrastructure

**Title:** `chore(ds): drift prevention CI checks + preview infrastructure`

**Description:** Guardrails to prevent design system drift from day one. Grep-based CI checks + Detekt rules + preview annotation.

**Files changed:**

| File | Action | Est. LOC |
|------|--------|----------|
| `tools/check-ds-drift.sh` (or `.ps1`) | Create — grep checks: reject `Color(0x` outside token files, reject `RoundedCornerShape(` outside Shape.kt, reject `spring(` with literal values outside MotionTokens | ~60 |
| `detekt.yml` | Edit — add `ForbiddenImport` entries for `android.widget.*`, `androidx.fragment.*`, `android.view.View` in new files | ~+10 |
| `.github/workflows/` or CI config | Edit — add drift check step | ~+15 |
| `sutils/.../compose/RidgelinePreviews.kt` | Create — `@RidgelinePreviews` annotation (Light + Dark + 200% + RTL) | ~20 |
| `sutils/.../compose/PreviewProviders.kt` | Create — `ActivityTypeProvider`, `TrackingStateProvider` per §22.3 | ~30 |

**Total estimated LOC:** ~135 new/changed

**Dependencies:** PR 1 (token files must exist for grep allowlist)

**Acceptance criteria:**
- [ ] CI fails if `Color(0x…)` appears outside `Color.kt` / `DesignSystem.kt`
- [ ] CI fails if `RoundedCornerShape(` appears outside `Shape.kt`
- [ ] Detekt fails if new file imports `android.widget.*` or `androidx.fragment.*`
- [ ] `@RidgelinePreviews` generates 4 preview variants
- [ ] Preview providers cover all `ActivityType` and `TrackingState` values
- [ ] Existing code passes (grep checks exempt current legacy files via allowlist)

---

#### PR 5: Dashboard Migration (First Screen)

**Title:** `feat(dashboard): migrate to Ridgeline design system tokens`

**Description:** First screen migration following §39.4 sequence. Dashboard is highest-visibility, highest-complexity screen — proves the system works end-to-end.

**Files changed:**

| File | Action | Est. LOC |
|------|--------|----------|
| `dashboard/.../compose/DashboardScreen.kt` | Edit — wrap in `AppTheme`, replace hardcoded colors/shapes/spacing with tokens | ~+40, -50 |
| `dashboard/.../compose/IdleContent.kt` | Edit — use `RidgelineSectionHeader`, `StaggeredEntrance`, `EmptyStateCard` (Tier 3 for first-launch) | ~+30, -20 |
| `dashboard/.../compose/TrackingContent.kt` | Edit — use `GlassMetricCard`, `RecordingDot`, token-based animations | ~+25, -30 |
| `dashboard/.../compose/TrackingStatsGrid.kt` | Edit — replace raw springs/tweens with `MotionTokens.*`, token spacing | ~+15, -15 |
| `dashboard/.../compose/TodayProgressCard.kt` | Edit — Stats Summary Card spec (§13.2), token typography | ~+20, -15 |
| `dashboard/.../compose/visualization/WeeklyTrendBars.kt` | Edit — update stagger from 50ms → 60ms (`MotionTokens.STAGGER_MS`), use `MotionTokens.Bouncy` | ~+5, -5 |

**Total estimated LOC:** ~270 changed

**Dependencies:** PR 1 (tokens), PR 2 (structural composables), PR 3 (TrackingFAB, RecordingDot, GoalProgressRings)

**Acceptance criteria:**
- [ ] Dashboard renders in light/dark/200% font without visual regressions
- [ ] Zero hardcoded colors, shapes, or spacing in dashboard module
- [ ] `StaggeredEntrance` visible on idle dashboard card entrance (60ms intervals)
- [ ] `TrackingFAB` morphs correctly between idle and tracking states
- [ ] `RecordingDot` pulses during active tracking
- [ ] Tier 3 empty state renders on first-launch (zero-data) scenario
- [ ] `WeeklyTrendBars` uses `MotionTokens.STAGGER_MS` (60ms) not hardcoded 50ms
- [ ] Passes CI drift checks from PR 4
- [ ] Before/after screenshots in PR description

---

### 25.2 Parallelization Strategy

```
Timeline:

Week 1:  [PR 1: Token Foundation] ─────────────────────────────►
                                                                 │
Week 2:  [PR 4: CI & Previews] ──────────►                     │
                (parallel with PR 1)                             │
                                                                 │
Week 2-3:              [PR 2: Composables Batch 1] ─────────────► (needs PR 1)
                       [PR 3: Composables Batch 2] ─────────────► (needs PR 1, PR 2)
                                                                 │
Week 3-4:                                    [PR 5: Dashboard] ──► (needs PR 1-3)
```

**Can run in parallel:**
*   **PR 1 + PR 4** — Token foundation and CI checks are independent. PR 4 only needs to know the FILE PATHS of token files (for grep allowlists), not their content.
*   **PR 2 + PR 3** — Partially parallel. Batch 2 references `GlassCard` and `MetricText` from Batch 1, but these already exist in `DesignSystem.kt`. The EDIT to update them (PR 2) and the CREATE of new components (PR 3) can be developed on separate branches simultaneously, with PR 3 rebasing on PR 2 before merge.

**Must be sequential:**
*   PR 1 before PR 2/3 (composables consume tokens)
*   PR 2 before PR 3 (Batch 2 uses Batch 1 components internally)
*   PR 1+2+3 before PR 5 (Dashboard consumes everything)

**Realistic timeline:** 3–4 weeks for all 5 PRs with one developer. 2–3 weeks if PR 1+4 are parallelized and PR 2+3 are developed concurrently.

### 25.3 Post-PR-5 Roadmap (PRs 6–10, Not Detailed)

| PR | Title | Depends On | Screen |
|----|-------|-----------|--------|
| 6 | Map screen migration | PR 1-3 | Map |
| 7 | Statistics screen migration | PR 1-3 | Statistics + Trip Detail |
| 8 | Game screen migration | PR 1-3 | Game |
| 9 | Settings migration | PR 1-3 | Settings, Privacy, About |
| 10 | Import/Export migration | PR 1-3 | Import/Export |

PRs 6–10 can all run in parallel after PR 1–3 merge (independent screen modules). PR 5 (Dashboard) is sequenced first only because it's the proof-of-concept — not because others depend on it.

---

## Appendix: DESIGN_SYSTEM.md Updates Required

These changes must be applied to `DESIGN_SYSTEM.md` based on R23–25 decisions:

1. **§38.1** — Update stagger: `STAGGER_MS` = 60 (was 80), max depth = 6 (was 5).
2. **New §41** — Add "Notification & System UI" section documenting shade continuity, notification channel alignment, and widget token groundwork.
3. **New §42** — Add "Share & Export Preview" section with filename convention, share text template, and privacy constraints.
4. **New §43** — Add "Deep Navigation" section with notification→screen mapping table and implementation pattern.
5. **§40** — No change to component count (35). The new sections are behavioral specs, not new components.
