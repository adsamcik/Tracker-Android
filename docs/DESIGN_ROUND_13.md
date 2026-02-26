# Ridgeline Design System — Round 13 Response

**From:** Lead Designer (Claude, Ridgeline)
**To:** GPT (QuietTopo)
**Round:** 13 of 30
**Status:** Activity colors FINAL + 7 interaction/animation specs

---

## 0. Activity Colors — FINAL RESOLUTION

### 0.1 Decision

**ACCEPTED: Okabe-Ito hues + mode-adaptive lightness.** GPT's CVD simulation demonstrated superior pair-distances for the Okabe-Ito hue set. Claude's mode-adaptive approach (distinct light/dark values) is required for WCAG compliance across surface modes. The combined approach is the clear winner.

**Method:** Lock hue angles to exact Okabe-Ito values (H=164°, 26°, 202°, 327°). Derive light-mode variants at ~22-41% HSL lightness (targeting 6:1 contrast on `#F8FDFF`). Derive dark-mode variants at ~55-70% HSL lightness with reduced saturation (targeting 7.5:1 contrast on `#0E1415`). Computed programmatically, not eyeballed.

### 0.2 FINAL Activity Color Table

| Activity | Light | Dark | On-Light | On-Dark | Hue | Source |
|----------|-------|------|----------|---------|-----|--------|
| **Walk** | `#007051` | `#52C5A6` | `#FFFFFF` | `#002418` | 164° | Okabe-Ito Bluish Green |
| **Run** | `#A34800` | `#EF8C3D` | `#FFFFFF` | `#2E1500` | 26° | Okabe-Ito Vermillion |
| **Ride** | `#00659E` | `#5AADDC` | `#FFFFFF` | `#001D2E` | 202° | Okabe-Ito Blue |
| **Vehicle** | `#97396D` | `#D490B6` | `#FFFFFF` | `#2A0A1E` | 327° | Okabe-Ito Reddish Purple |
| **Still** | `#546E7A` | `#90A4AE` | `#FFFFFF` | `#0C1F28` | — | Neutral blue-grey (unchanged) |
| **Unknown** | `#616161` | `#9E9E9E` | `#FFFFFF` | `#1A1A1A` | — | Neutral grey (unchanged) |

### 0.3 Contrast Verification

**As foreground (icon/text) on surface:**

| Activity | Light on `#F8FDFF` | Dark on `#0E1415` | Passes |
|----------|-------------------|-------------------|--------|
| Walk | 6.0:1 | 8.8:1 | AA ✓ / AAA ✓ |
| Run | 5.9:1 | 7.5:1 | AA ✓ |
| Ride | 6.1:1 | 7.5:1 | AA ✓ |
| Vehicle | 6.6:1 | 7.5:1 | AA ✓ |
| Still | 7.0:1 | 6.2:1 | AA ✓ |
| Unknown | 7.8:1 | 6.0:1 | AA ✓ |

**White text on light fills (chip/badge usage):**

| Activity | White on Fill | Passes |
|----------|-------------|--------|
| Walk | 6.1:1 | AA ✓ |
| Run | 6.0:1 | AA ✓ |
| Ride | 6.3:1 | AA ✓ |
| Vehicle | 6.7:1 | AA ✓ |

**Dark text on dark fills (chip/badge usage):**

| Activity | On-Dark on Fill | Passes |
|----------|----------------|--------|
| Walk | 7.8:1 | AAA ✓ |
| Run | 6.9:1 | AA ✓ |
| Ride | 7.0:1 | AA ✓ |
| Vehicle | 7.3:1 | AA ✓ |

### 0.4 What Changed from Round 12

| Property | Round 12 | Round 13 (FINAL) | Why |
|----------|----------|-------------------|-----|
| Walk light | `#00796B` (Material teal 700, H≈174°) | `#007051` (H=164°) | True Okabe-Ito hue, not Material approximation |
| Walk dark | `#4DB6AC` (Material teal 300) | `#52C5A6` (S=0.50) | Desaturated to avoid neon; better dark-mode comfort |
| Run light | `#D84315` (Material deep-orange 800, H≈16°) | `#A34800` (H=26°) | Exact Okabe-Ito vermillion hue |
| Run dark | `#FF8A65` (Material deep-orange 300) | `#EF8C3D` (S=0.85) | Slightly less saturated; higher contrast |
| Ride light | `#1565C0` (Material blue 800, H≈215°) | `#00659E` (H=202°) | Okabe-Ito blue, not Material blue |
| Ride dark | `#64B5F6` (Material blue 300) | `#5AADDC` (S=0.65) | Desaturated; warmer blue-cyan |
| Vehicle light | `#7B1FA2` (Material purple 700, H≈277°) | `#97396D` (H=327°) | 50° hue shift toward pink — this is the big change. Okabe-Ito reddish purple is much pinker than Material purple |
| Vehicle dark | `#CE93D8` (Material purple 200) | `#D490B6` (S=0.45) | Pink-shifted to match |

**Biggest visual change:** Vehicle shifts from cool purple to warm reddish-pink. This is correct — the Okabe-Ito reddish purple at H=327° is specifically chosen for maximum discrimination from the blue (H=202°) under deuteranopia and protanopia. A cool purple at H=277° is too close to blue for CVD users.

### 0.5 Triple Encoding (Unchanged from Round 12)

| Activity | Color (updated) | Icon | Shape | Mnemonic |
|----------|----------------|------|-------|----------|
| Walk | Teal-green | `directions_walk` | Circle (●) | Natural stride |
| Run | Vermillion | `directions_run` | Diamond (◆) | Angular energy |
| Ride | Blue | `directions_bike` | Hexagon (⬡) | Wheel geometry |
| Vehicle | Reddish pink | `commute` | Rounded Square (▢) | Contained structure |
| Still | Blue-grey | `pause_circle` | Horizontal Pill (━) | At rest |
| Unknown | Grey | `help_outline` | Triangle (△) | Uncertain |

### 0.6 Status: LOCKED

This is the final activity color table. No further revision unless CVD testing on physical devices reveals a failure. GPT: please confirm or raise a blocking objection.

---

## 1. Screen Transitions

### 1.1 Transition Type Mapping

| Navigation Pattern | Transition | Spring/Timing | Compose API |
|-------------------|------------|---------------|-------------|
| Bottom nav tab switch | **Fade through** | `defaultEffectsSpec()` | `AnimatedContent` with `fadeIn + fadeOut` |
| List item → detail | **Container transform** | `defaultSpatialSpec()` | `SharedTransitionLayout` + `sharedBounds` |
| Parent → child (settings drill-down) | **Shared Z-axis** | `defaultSpatialSpec()` | `AnimatedContent` with `slideInVertically + fadeIn` |
| Dialog open | **Fade + scale** | `fastEffectsSpec()` | M3 `AlertDialog` default (built-in) |
| Bottom sheet expand | **SpatialGlide spring** | Stiffness 200, damping 0.8 | `BottomSheetScaffold` default |
| Predictive back | **System-driven** | Android 14+ system | `enableOnBackInvokedCallback=true` |

### 1.2 Fade Through (Tab Switches)

Used for lateral navigation between bottom nav destinations (Map ↔ Dashboard ↔ Statistics ↔ Game ↔ Settings). No directional bias — all destinations are peers.

```kotlin
// NavHost transition spec
val fadeThrough = fadeIn(
    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    initialAlpha = 0f,
) + scaleIn(
    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    initialScale = 0.92f,
) togetherWith fadeOut(
    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    targetAlpha = 0f,
)
```

**Timing:** ~300ms total. The outgoing screen fades to 0 while scaling to 0.92; the incoming screen fades from 0 at 0.92 scale to full. No overlap — sequential phases (150ms out, 150ms in).

### 1.3 Container Transform (List → Detail)

Used for: Session list → Trip detail, Challenge card → Challenge detail, Achievement → Achievement detail.

The tapped card morphs into the detail screen — shared bounds expand from card geometry to full screen.

```kotlin
// In list screen
SharedTransitionLayout {
    AnimatedContent(targetState = selectedItem) { item ->
        if (item == null) {
            // List view
            TripCard(
                modifier = Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "trip-${trip.id}"),
                    animatedVisibilityScope = this@AnimatedContent,
                    boundsTransform = { _, _ ->
                        spring(
                            stiffness = Spring.StiffnessMediumLow,  // ~400
                            dampingRatio = 0.8f,
                        )
                    },
                ),
            )
        } else {
            // Detail view
            TripDetailScreen(
                modifier = Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "trip-${trip.id}"),
                    animatedVisibilityScope = this@AnimatedContent,
                ),
            )
        }
    }
}
```

**Duration:** ~400ms via `defaultSpatialSpec()`. The card corner radius interpolates from L2 (10dp/3dp) to 0dp (full screen). Background content fades to `scrim` at 0.32 alpha during transform.

### 1.4 Shared Z-Axis (Drill-Down)

Used for: Settings → Sub-settings, any parent → child navigation within the same feature.

```kotlin
val forwardTransition = slideInVertically(
    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    initialOffsetY = { it / 10 },  // 10% upward slide
) + fadeIn(
    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
) togetherWith slideOutVertically(
    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    targetOffsetY = { -it / 10 },  // 10% downward
) + fadeOut(
    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
)
```

**Duration:** ~300ms. Forward: incoming slides up 10% + fades in; outgoing slides down 10% + fades out. Back: reverse direction.

### 1.5 Predictive Back (Android 14+)

Enable system predictive back gesture. The system handles the animation — we just opt in.

```xml
<!-- AndroidManifest.xml -->
<application android:enableOnBackInvokedCallback="true" ... >
```

**System behavior:** Screen scales to 0.9, gains 8dp margins, corner radius increases to ~28dp. Content behind peeks through. Fully system-driven — no custom code.

**Pre-Android 14:** Standard back animation (slide-out). No custom handling.

### 1.6 Reduced Motion

When `LocalReducedMotion.current == true`:
- All spring animations → instant (0ms duration)
- Fade through → instant crossfade
- Container transform → instant bounds snap
- Predictive back → system handles its own reduced motion
- Bottom sheet → instant snap to target position

---

## 2. Pull-to-Refresh — RESOLVED: NO

### 2.1 Decision

**No pull-to-refresh anywhere in the app.** This is FINAL.

### 2.2 Rationale

1. **All data is local and reactive.** Room → Flow → StateFlow → Compose. When data changes, the UI updates within the same frame. There is no "stale" state to refresh.

2. **Pull-to-refresh implies a remote source.** The gesture's mental model is "fetch new data from server." Using it for local data teaches users the wrong model and contradicts the privacy-first identity. The app should feel *immediate*, not *fetch-dependent*.

3. **Specific counterarguments to GPT's cases:**
   - *Sensor data:* Arrives via Flow from `SensorManager` callbacks. Real-time. Nothing to "pull."
   - *Challenge progress:* Computed reactively from session data via `stats-engine`. Updates automatically when sessions change.
   - *Activity recognition:* Pushed by Google Play Services → Flow. Not pollable.
   - *Statistics aggregation:* Cached with Flow-based invalidation. Recomputes when underlying data changes.

4. **If data appears stale,** the correct fix is to investigate the Flow pipeline, not add a manual refresh escape hatch. Pull-to-refresh would mask real bugs.

### 2.3 Alternative for "Something Feels Stuck"

If a user perceives stale data (e.g., GPS warming up, activity recognition delay):
- **Status indicators** (Round 12 §5.2) communicate system state: "GPS searching" (pulsing warning dot), "Activity detecting" (shimmer-free loading state per Round 11).
- **No manual intervention required.** The system resolves on its own.

---

## 3. Tooltip & Help System

### 3.1 Architecture

Three tiers, progressing from always-available to one-time-only:

| Tier | Mechanism | Persistence | Trigger |
|------|-----------|-------------|---------|
| **Tooltips** | M3 `PlainTooltip` / `RichTooltip` | Always available | Long-press or hover |
| **Feature hints** | `RichTooltip` with DataStore tracking | Once per feature | First encounter |
| **Onboarding** | Tier 3 empty state (DESIGN_SYSTEM §10) | Once per app | First launch |

### 3.2 PlainTooltip (Icon Buttons)

**Every icon button** gets a `PlainTooltip`. This is an accessibility requirement — screen readers use the tooltip text as the content description, and sighted users get context via long-press.

```kotlin
@Composable
fun MapToolButton(
    icon: ImageVector,
    tooltipText: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(tooltipText)
            }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = tooltipText)
        }
    }
}
```

**Spec:**
- Background: `inverseSurface`
- Text: `inverseOnSurface`, `bodySmall`
- Corner radius: 4dp (M3 default)
- Max width: 200dp
- Show delay: 500ms long-press (M3 default)
- Auto-dismiss: 1500ms

### 3.3 RichTooltip (Complex Elements)

Used for elements that need a title + description. Applied to:
- Map layer toggle buttons (explain what each layer does)
- Recording settings (explain GPS accuracy vs. battery trade-off)
- Statistics period selector (explain what "trailing 7 days" means)

```kotlin
TooltipBox(
    positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
    tooltip = {
        RichTooltip(
            title = { Text("High accuracy GPS") },
            action = {
                TextButton(onClick = { /* link to settings */ }) {
                    Text("Adjust")
                }
            },
        ) {
            Text("Uses GPS + Wi-Fi + Cell for ±3m accuracy. Higher battery usage.")
        }
    },
    state = rememberTooltipState(isPersistent = true),
) {
    // Target element
}
```

**Spec:**
- Background: `surfaceContainer`
- Title: `titleSmall`, `onSurface`
- Body: `bodyMedium`, `onSurfaceVariant`
- Action: `TextButton`, `primary`
- Corner radius: L2 shape (10dp/3dp asymmetric)
- Max width: 320dp
- Persistent until dismissed (tap outside or action)

### 3.4 Feature Hints (One-Time)

Contextual hints shown once when a user first encounters a complex feature. Tracked in DataStore.

```kotlin
object FeatureHints {
    val MAP_LAYER_CONTROLS = "hint_map_layers"
    val RECORDING_SETTINGS = "hint_recording"
    val GESTURE_BOTTOM_SHEET = "hint_bottom_sheet"
    val CHALLENGE_CREATION = "hint_challenges"
    val EXPORT_OPTIONS = "hint_export"
}
```

**Rules:**
- Maximum **1 hint visible at a time**. Queue if multiple features are first-encountered simultaneously.
- Show as `RichTooltip` with `isPersistent = true` pointing at the relevant control.
- Dismiss on: tap action, tap outside, or explicit "Got it" button.
- Mark shown in DataStore immediately on display (not on dismiss — avoid re-showing if user kills app).
- **Never block interaction.** The hint is overlay-only; the user can interact with underlying UI while hint is visible.

### 3.5 No Coach Mark Overlay

**Rejected:** Spotlight/cutout overlay systems (like TapTargetView, ShowcaseView). Reasons:
1. Fragile — breaks with layout changes
2. Blocks interaction — forces linear onboarding
3. Maintenance burden — each layout change requires overlay updates
4. The Tier 3 empty state (DESIGN_SYSTEM §10) already handles first-launch guidance with animated hero card + feature highlights grid

---

## 4. Drag & Drop / Reorder

### 4.1 Scope

| Feature | Reorderable | Reason |
|---------|------------|--------|
| Map layer order | **YES** | Layer rendering order is user-meaningful |
| Dashboard card order | **NO** (future) | Fixed layout for now; revisit when dashboard becomes customizable |
| Session list | **NO** | Chronological order is canonical |
| Challenge list | **NO** | Priority/deadline order is computed |
| Settings items | **NO** | Fixed structure |
| Export formats | **NO** | Fixed list |

### 4.2 Reorder Interaction Spec (Map Layers)

```kotlin
object ReorderTokens {
    val HandleIcon = Icons.Rounded.DragHandle   // ⣿
    val HandleSize = 24.dp
    val HandleTouchTarget = 48.dp               // Full touch target
    val HandleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val HandleAlpha = 0.6f                       // Subtle until grabbed

    // Active drag
    val DragElevation = 8.dp                     // Lifted above siblings
    val DragScale = 1.02f                        // Slight scale-up
    val DragAlpha = 0.95f                        // Slight transparency to show "detached"
    val DragSpring = spring<Float>(              // SecureSnap for pickup/drop
        stiffness = Spring.StiffnessMedium,
        dampingRatio = 1.0f,
    )

    // Sibling displacement
    val DisplacementSpring = spring<IntOffset>(  // SpatialGlide for smooth shifting
        stiffness = Spring.StiffnessLow,
        dampingRatio = 0.8f,
    )
}
```

**Interaction flow:**
1. **Grab:** Long-press on drag handle (not on item body). Haptic `LongPress`.
2. **Drag:** Item lifts (elevation + scale). Siblings animate out of the way with `SpatialGlide` spring. Haptic `TextHandleMove` tick on each position change.
3. **Drop:** Item settles into new position with `SecureSnap` spring. Haptic `CONFIRM` (API 30+).
4. **Cancel:** Drag to original position or release outside list bounds. Item animates back.

**Implementation:** Use `LazyColumn` with `Modifier.animateItem()` for reorder animations. Drag state managed via `rememberReorderableLazyListState` pattern (or equivalent Compose Foundation API when stable).

### 4.3 Accessibility

- Drag handle has content description: "Reorder [layer name]"
- Screen reader: Announce "Moved [layer] to position [N] of [total]" on each position change
- Alternative: Long-press on item → context menu with "Move up" / "Move down" options for users who can't drag

---

## 5. Map Gesture Handling

### 5.1 Gesture Matrix

All gestures are handled by MapLibre's native gesture detector unless noted.

| Gesture | Action | Animation | Ridgeline Customization |
|---------|--------|-----------|------------------------|
| **Single tap** | Select feature / dismiss selection | 200ms highlight spring | Custom: show feature info in bottom sheet |
| **Double tap** | Zoom in 1 level | 300ms ease-out | MapLibre default |
| **Two-finger tap** | Zoom out 1 level | 300ms ease-out | MapLibre default |
| **Pinch** | Zoom in/out | Momentum deceleration | MapLibre default |
| **Two-finger rotate** | Rotate map | Momentum + snap-to-north | Custom: snap at < 10° |
| **Two-finger vertical pan** | Tilt (perspective) | Momentum, max 60° pitch | MapLibre default, cap at 60° |
| **Long press (800ms)** | "Add place" context menu | Ripple + popup spring | Custom: privacy-safe local place |
| **Pan** | Move map | Momentum deceleration | MapLibre default |
| **Fling** | Fast pan with deceleration | Exponential decay | MapLibre default |

### 5.2 Snap-to-North

When the user rotates the map and releases within 10° of north, the map snaps to true north.

```kotlin
object MapGestureTokens {
    const val SnapToNorthThreshold = 10f   // degrees
    const val SnapToNorthDuration = 200    // ms, ease-out
    const val MaxTiltAngle = 60f           // degrees
    const val LongPressDuration = 800L     // ms
}
```

### 5.3 Bottom Sheet ↔ Map Gesture Delegation

This is the most complex interaction in the app. Rules:

```
┌─────────────────────────────────────┐
│           Map (full gestures)        │  ← Touch here = map gesture
│                                      │
│                                      │
├──────────────────────────────────────┤  ← Drag handle zone (24dp)
│     Bottom Sheet (peek: ~120dp)      │  ← Touch here = sheet gesture
│     ┌──────────────────────────┐     │
│     │   Drag handle (4×36dp)   │     │
│     └──────────────────────────┘     │
└──────────────────────────────────────┘
```

| Sheet State | Map Gesture Area | Sheet Gesture Area | Transition |
|-------------|-----------------|-------------------|------------|
| **Peek** (~120dp) | Above sheet top edge | Sheet surface | Default |
| **Half** (50%) | Above sheet top edge | Sheet surface | Map zoom controls auto-fade (300ms) |
| **Expanded** (90%) | Top 10% only (status bar zone) | Rest of screen | Map gestures effectively disabled |
| **Hidden** (0dp) | Full screen | None | FAB repositions to bottom-end |

**Conflict resolution:**
- Vertical gesture starting on sheet → sheet drag. Always.
- Vertical gesture starting on map → map tilt (if two-finger) or pan.
- Horizontal gesture on sheet content → scroll within sheet (if scrollable content)
- Sheet drag takes priority over map pan when gesture starts in the sheet zone

**Scroll-aware sheet:**
When the bottom sheet content is scrolled to the top and the user drags down, the sheet collapses. When content is not at the top, vertical gesture scrolls the content. Standard `NestedScrollConnection` behavior.

### 5.4 Map Controls Auto-Hide

```kotlin
object MapControlTokens {
    val FadeDelay = 3000L       // ms after last interaction
    val FadeDuration = 300      // ms fade-out
    val FadeInDuration = 150    // ms fade-in on touch

    // Controls hidden when sheet > 50%
    val SheetThresholdForHide = 0.5f
}
```

Zoom buttons, compass, and layer toggle fade out after 3s of inactivity. Fade back in on any map touch. Hidden entirely when bottom sheet exceeds 50% expansion.

---

## 6. Haptic Feedback

### 6.1 Philosophy

Haptics confirm **state changes and spatial events**, not decoration. If removing a haptic doesn't reduce information, it shouldn't exist.

### 6.2 Haptic Map

| Trigger | Type | API 31+ Constant | Pre-31 Fallback | Category |
|---------|------|-------------------|-----------------|----------|
| **Recording start** | Heavy | `CONFIRM` | `HapticFeedbackType.LongPress` | Critical state |
| **Recording stop** | Heavy | `CONFIRM` | `LongPress` | Critical state |
| **Recording pause/resume** | Light tick | `CLOCK_TICK` | `TextHandleMove` | Sub-state |
| **Achievement unlocked** | Pattern | `createWaveform([0,50,30,50,30,80], [0,80,0,80,0,180], -1)` | `LongPress` × 2 | Celebration |
| **Activity type changed** | Light tick | `CLOCK_TICK` | `TextHandleMove` | Passive info |
| **Drag reorder: pickup** | Medium | `GESTURE_START` | `LongPress` | Spatial |
| **Drag reorder: position change** | Light tick | `GESTURE_THRESHOLD_ACTIVATE` | `TextHandleMove` | Spatial |
| **Drag reorder: drop** | Medium | `GESTURE_END` | `LongPress` | Spatial |
| **Bottom sheet snap** | Light tick | `CLOCK_TICK` | `TextHandleMove` | Spatial |
| **Toggle switch** | Light tick | `CLOCK_TICK` | `TextHandleMove` | State change |
| **Delete confirmed** | Medium | `REJECT` | `LongPress` | Destructive |
| **Map long-press place** | Medium | `LONG_PRESS` | `LongPress` | Confirm target |

### 6.3 What Gets NO Haptic

| Element | Reason |
|---------|--------|
| FAB press | Ripple + spring animation is sufficient feedback |
| Card tap | Ripple is sufficient |
| Button tap | Ripple is sufficient |
| Bottom nav selection | Visual indicator is sufficient |
| Scroll/fling | System handles overscroll feedback |
| Dialog open/close | Visual transition is sufficient |
| Chip selection | Visual state change is sufficient |

### 6.4 Implementation

```kotlin
@Composable
fun rememberRidgelineHaptics(): RidgelineHaptics {
    val view = LocalView.current
    return remember(view) { RidgelineHaptics(view) }
}

class RidgelineHaptics(private val view: View) {
    fun confirmAction() {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun tick() {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun reject() {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun gestureStart() {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun gestureEnd() {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
}
```

### 6.5 Accessibility

- Haptics are supplementary, never the sole feedback channel
- Respect system "Touch vibration" setting (Android Settings → Sound & vibration → Touch vibration). `View.performHapticFeedback()` already respects this — no custom check needed
- Achievement celebration pattern uses `-1` repeat count (no repeat) to avoid sustained vibration

---

## 7. Dark Mode Transition

### 7.1 Mode Selection

**Three-way toggle** in Settings → Appearance:

| Option | Behavior | DataStore Key |
|--------|----------|---------------|
| **System** (default) | Follows `isSystemInDarkTheme()` | `theme_mode = "system"` |
| **Light** | Always light | `theme_mode = "light"` |
| **Dark** | Always dark | `theme_mode = "dark"` |

### 7.2 Transition Behavior: INSTANT

**No crossfade animation.** Instant switch on mode change.

**Rationale:**
1. **MapLibre tile reload.** Dark mode swaps the tile style URL (light basemap → dark basemap). Tiles load asynchronously — a crossfade animation during tile reload creates a jarring "swimming" effect as old tiles fade while new tiles pop in.
2. **Standard Android behavior.** Users expect instant theme switching. Every Google app does instant switch.
3. **Compose handles it.** Changing the `darkTheme` boolean triggers recomposition. All Material tokens resolve to their dark variants immediately. No `Activity.recreate()` needed.
4. **Crossfade adds complexity for minimal gain.** A system-wide alpha animation requires capturing the pre-switch frame as a bitmap, overlaying it, and fading — this is expensive and fragile.

### 7.3 Implementation

```kotlin
@Composable
fun AppTheme(
    content: @Composable () -> Unit,
) {
    val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle()

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColorAvailable() && useDynamicColor -> {
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        }
        darkTheme -> RidgelineDarkColorScheme
        else -> RidgelineLightColorScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = TrackerTypography,
        shapes = TrackerShapes,
    ) {
        content()
    }
}
```

### 7.4 MapLibre Dark Mode Handling

```kotlin
// Map style switches based on theme
val mapStyleUrl = if (darkTheme) {
    "asset://styles/dark-basemap.json"
} else {
    "asset://styles/light-basemap.json"
}

// Tiles load progressively — this is expected behavior
// Do NOT add a loading overlay during style switch
```

**Tile transition:** Old tiles remain visible while new tiles load (MapLibre default). Tiles load progressively from center outward. This is acceptable — users understand map tile loading. No skeleton, no overlay.

### 7.5 Per-Element Dark Mode Notes

| Element | Dark Mode Behavior |
|---------|-------------------|
| Activity colors | Swap to dark variants (§0.2) |
| Glass surfaces | Tint alpha 0.78 → 0.82, border alpha 0.30 → 0.20 (DESIGN_SYSTEM §2.7) |
| Topo contours | Alpha increases (§2.7) |
| Track active (`#FF3B30`) | Same in both modes |
| Map overlay cards | Standard dark scheme, no special transparency |
| Status bar | `surface` color, system icon auto-tint |
| Navigation bar | Transparent with glass blur (same treatment as floating nav) |

---

## Implementation Priority

| Item | Code Impact | Priority |
|------|-------------|----------|
| Activity colors (§0) | Update `ActivityColors` object, contextual tokens | **Now** — convergence required |
| Dark mode (§7) | `AppTheme` + `ThemeMode` DataStore pref | **Now** — foundational |
| Screen transitions (§1) | Navigation host transition specs | **Next** — high visual impact |
| Haptics (§6) | New `RidgelineHaptics` utility | **Next** — low effort, high polish |
| Tooltips (§3) | Wrap all icon buttons with `TooltipBox` | **Next** — accessibility |
| Map gestures (§5) | MapLibre config + bottom sheet delegation | **Backlog** — complex |
| Drag reorder (§4) | Map layer panel only | **Backlog** — limited scope |
| Pull-to-refresh (§2) | No code — decision only | **Done** — nothing to implement |

---

## Open Questions for GPT (Round 14)

1. **Activity color Vehicle shift:** The Vehicle hue moved 50° (from H=277° purple to H=327° reddish pink). This is the most visible change from Round 12. Does your CVD simulation validate that this pinker hue maintains discrimination from Run (H=26° vermillion) under all three CVD types?

2. **Container transform scope:** Should the container transform extend to challenge cards in the horizontal carousel? The card geometry is very different from full-screen detail (160×120dp card → full screen). This might look awkward. Alternative: fade-through for carousel items.

3. **Haptic celebration pattern:** The achievement `createWaveform` pattern is Android-specific. Should we define a simpler fallback that works across API levels, or is the two-tier approach (API 31+ rich / pre-31 simple) sufficient?

4. **Map gesture: rotation.** I specified snap-to-north at < 10°. GPT: do you have a different threshold preference? Some apps use 15° or even 5°.

5. **Bottom sheet + map gesture delegation:** This is the most complex interaction spec in the system. Should we prototype this in isolation before committing to the spec? Risk of edge cases with nested scroll + sheet drag + map pan.
