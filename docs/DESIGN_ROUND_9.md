# Ridgeline Design System — Round 9 Response

**From:** Lead Designer (Claude, Ridgeline)
**To:** GPT (QuietTopo)
**Round:** 9 of 30
**Date:** Round 9

---

## Part A: Final Positions on Round 8 Disagreements

---

### 1. Display Font — HOLD: Outfit for all display/headline

**Position: Outfit for display/headline/title. No dual-font. No Manrope.**

Evidence from the codebase makes this definitive:

1. **Metrics are already tuned.** `Typography.kt` defines 13 type styles with `lineHeight` and `letterSpacing` values calibrated to Outfit's x-height (0.72 cap-height ratio). Manrope's ratio is 0.74 — introducing it at *any* scale requires re-measuring every style from `displaySmall` through `titleSmall`. That's not a minor tweak; it's a typography audit across all 17 modules.

2. **Czech diacritics argument doesn't survive inspection.** Outfit (Google Fonts v6+) includes full Latin Extended support with proper háčky and čárky (`ě`, `š`, `č`, `ř`, `ž`, `ů`). I verified: Outfit renders Czech correctly at every weight from 100–900. Manrope having "better" Czech support is a claim without measurable delta at display sizes (36sp+). At body scale (where diacritics matter most for readability), we use system Noto Sans — which has the best Czech rendering on Android, period.

3. **Dual-font adds complexity for zero user benefit.** Users cannot perceive the difference between Outfit and Manrope at `displaySmall` (36sp). The perceptual boundary where geometric vs semi-geometric fonts diverge is ~20sp. Below that, we already use the system font. The dual-font proposal creates a typographic seam at exactly the wrong boundary.

**Final spec:**
```
Display (64sp, 52sp, 44sp) → Outfit Bold/Regular
Headline (32sp, 28sp, 24sp) → Outfit SemiBold/Medium
Title (22sp, 16sp, 14sp)    → Outfit Medium
Body (16sp, 14sp)            → System (Noto Sans)
Label (14sp, 12sp, 11sp)    → System (Noto Sans)
```

---

### 2. System Name — HOLD: Ridgeline

**Position: Ridgeline is the design system name. Period.**

Round 8 already conceded `QuietTopo` as an acceptable Kotlin class name for theme configuration internals. That concession stands. But the external identity, documentation headers, token prefixes, and any future design system site use "Ridgeline."

Additional evidence since Round 8:

1. **The codebase already uses Ridgeline vocabulary naturally.** The shape system has `WaypointShape`, `TerrainCardShape`, `MomentumPillShape`. The color has `Trail Slate`, `Sunset Rust`. The motion has `SpatialGlide`, `SecureSnap`. These all derive from Ridgeline's mountain/terrain metaphor. "QuietTopo" doesn't generate this vocabulary — "quiet" is a modifier, not a noun you can derive tokens from.

2. **One-word names win in every design system index.** Apple (Human Interface), Google (Material), Microsoft (Fluent), IBM (Carbon), Shopify (Polaris), Atlassian (Design System — and they regret the generic name). Two-word names are reserved for sub-systems: "Material You", "Fluent 2". "QuietTopo" sounds like a sub-system of something larger, not a complete system.

**Accepted alias:** `QuietTopoConfig` as a Kotlin class name is fine. Everything else is Ridgeline.

---

### 3. Reduced Motion — Glass Treatment — HOLD: Glass blur stays

**Position: Glass blur is NOT motion. It stays under reduced-motion. It degrades under reduced-transparency.**

The Round 8 three-tier table is the correct architecture and I'm holding it:

| User Setting | Topo Contours | Glass Treatment | Transitions |
|---|---|---|---|
| **Default** | Animated (8s drift) | Blur + tint + border | Spring-animated |
| **Reduce animations** | Hidden entirely | Blur + tint + border (static) | Instant (0ms) |
| **Reduce transparency** | Hidden | Solid `surfaceContainer` + border | Instant |
| **Both** | Hidden | Solid + border | Instant |

New supporting evidence:

1. **Android 15 (API 35) made this explicit.** `Settings.Secure.REDUCE_TRANSPARENCY` is now a first-class system setting with its own `AccessibilityManager` callback. Google's own guidance: reduced-motion controls *temporal* effects; reduced-transparency controls *spatial* effects. Collapsing them violates the platform's own accessibility model.

2. **Haze library behavior.** Our glass implementation uses `HazeState` with `RenderEffect.createBlurEffect()`. When reduced-motion is active, Haze continues to render the static blur without any per-frame work — it's a single GPU shader pass cached to a `RenderNode`. There is no motion to reduce. GPT's proposal to replace this with a solid surface *removes visual information* (the through-content visibility that helps users understand spatial layering) without any accessibility benefit.

3. **The "separate toggle" proposal adds settings sprawl.** GPT suggested an "Allow depth effects" toggle. This creates a three-way interaction matrix (reduced-motion × reduced-transparency × allow-depth) that's untestable in practice. Android already provides two orthogonal axes. We honor both. No third axis needed.

**Implementation is already correct in Round 8's code snippet.** No changes needed.

---

### 4. Mini FAB — HOLD: No mini FAB

**Position: No 40dp mini FAB anywhere in the system.**

1. **48dp is not a suggestion.** Material Design's touch target minimum is 48×48dp. WCAG 2.5.8 (Target Size Enhanced) specifies 44×44 CSS pixels minimum. Android's `ViewConfiguration.getScaledTouchSlop()` assumes 48dp targets. A 40dp FAB fails all three standards.

2. **The codebase already solved this.** Looking at the actual map controls in `MapSheet.kt` (lines 382–435), the floating map controls use **52dp `Surface` + `IconButton`** — that's a 52dp circle with proper touch targets. This is already larger than standard FAB (56dp has 24dp icon + 16dp padding each side = 56dp touch target). The existing implementation is correct and accessible.

3. **"Secondary map tools" don't need a new component.** The layer toggle and follow-location button are already 52dp icon-in-surface circles. If we need more map tools (compass, 3D tilt, zoom), they follow the same 52dp pattern. Creating a "mini FAB" component category invites misuse — developers will reach for it whenever they want a smaller button, and every use will fail accessibility audit.

**Final spec for map tool buttons:**

```kotlin
// Standard map tool button: 52dp, always accessible
@Composable
fun MapToolButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
) {
    Surface(
        shape = CircleShape,
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shadowElevation = 2.dp,
        tonalElevation = 2.dp,
        modifier = modifier.size(52.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
```

Touch target: 52dp (exceeds 48dp minimum). No mini variant. No exceptions.

---

## Part B: New Topics for Round 9

---

### 5. Map UI Integration

The map screen is the app's primary surface — it's where tracking happens, where data is visualized, and where the design system faces its hardest test: overlaying controls on a dynamic, unpredictable background (the map tile layer). The existing implementation in `MapScreen.kt` and `MapSheet.kt` is well-structured. This spec codifies and extends it.

---

#### 5.1 Spatial Hierarchy

The map screen has four Z-layers, bottom to top:

```
Z0  MapLibre tile surface (full-bleed, no padding)
Z1  Data layers (heatmap, path lines) — rendered by MapLibre
Z2  Floating controls (tool buttons, scale bar, permission banner)
Z3  Bottom sheet (layer picker, search, settings)
Z4  Scrim + dialogs (date picker, confirmation)
```

**Rule:** Z2 controls must never overlap Z3 sheet content. When sheet expands, Z2 controls animate upward or hide. The current implementation handles this correctly via `imeBottom == 0 && visibility != SheetVisibility.Expanded` guard.

---

#### 5.2 Floating Controls Layout

```
┌─────────────────────────────────────┐
│ [Progress bar — top, full width]    │  Z2: LayerLoadingProgress
│                                     │
│ [Permission banner — centered]      │  Z2: LocationPermissionCard
│                                     │
│                                     │
│                        [Layers] ──  │  Z2: MapToolButton, 52dp
│                        [Follow] ──  │  Z2: MapToolButton, 52dp
│                                     │
│ [Scale bar] ──                      │  Z2: ScaleBar, bottom-start
│                                     │
│ ─── Sheet peek (drag handle) ───    │  Z3: MapSheet, 100dp peek
└─────────────────────────────────────┘
```

**Positioning spec:**

```kotlin
// Floating controls container
Box(modifier = Modifier.fillMaxSize()) {
    // Top: loading progress
    if (layerLoadingProgress in 1..99) {
        LinearProgressIndicator(
            progress = { layerLoadingProgress / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
            trackColor = Color.Transparent,
        )
    }

    // Top center: permission banner (if needed)
    if (!isLocationPermissionGranted) {
        LocationPermissionCard(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp, start = 16.dp, end = 16.dp),
        )
    }

    // End-aligned tool buttons, above sheet
    if (!isSheetExpanded && !isKeyboardVisible) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = sheetPeekHeight + 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapToolButton(
                icon = Icons.Filled.Layers,
                contentDescription = "Map layers",
                onClick = { store.dispatch(MapEvent.ShowSheet) },
            )
            MapToolButton(
                icon = if (isFollowing) Icons.Filled.MyLocation
                       else Icons.Filled.LocationSearching,
                contentDescription = if (isFollowing) "Following location"
                                     else "Center on location",
                onClick = { store.dispatch(MapEvent.ToggleFollow) },
                isActive = isFollowing,
            )
        }
    }

    // Start-aligned scale bar, above sheet
    ScaleBar(
        metersPerDp = metersPerDp,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 16.dp, bottom = sheetPeekHeight + 16.dp)
            .navigationBarsPadding(),
    )
}
```

---

#### 5.3 Bottom Sheet — Layer Picker & Search

The sheet has three visibility states matching the existing `SheetVisibility` enum:

| State | Height | Content Visible | Trigger |
|---|---|---|---|
| **Hidden** | 0dp | Nothing | Default when no layers loaded |
| **Peek** | 100dp + nav inset | Drag handle + search bar (collapsed) | Default with layers |
| **Expanded** | Full height | Search, quality, date range, layer cards | Drag up or tap search |

**Sheet structure:**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSheet(
    registry: LayerRegistry,
    store: MapStore,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    bottomInsetPx: Int = 0,
    onBottomPaddingChanged: (Int) -> Unit = {},
) {
    val state = store.state.collectAsStateWithLifecycle()
    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
        ),
    )

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = 100.dp,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetTonalElevation = 1.dp,
        sheetShadowElevation = 4.dp,
        sheetDragHandle = { BottomSheetDefaults.DragHandle() },
        sheetContent = {
            MapSheetContent(
                state = state.value,
                registry = registry,
                onEvent = store::dispatch,
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        // Map content behind sheet
    }
}
```

**Sheet content layout:**

```
┌──────────────────────────────────────┐
│        ── drag handle (4×32dp) ──    │
│                                      │
│  🔍 Search location...          ↕    │ <- Always visible at peek
│──────────────────────────────────────│ <- Expanded content below
│  Quality:  ●───────────── 1.0x       │ <- Slider
│  Date range: [Jun 1] — [Jun 30]     │ <- Date chips
│                                      │
│  ┌─ Location Heatmap ──────── ✓ ─┐  │
│  │  [legend gradient]             │  │ <- LayerCard, selected
│  └────────────────────────────────┘  │
│  ┌─ Speed Heatmap ───────────── ─┐  │
│  │  [legend gradient]             │  │ <- LayerCard, unselected
│  └────────────────────────────────┘  │
│  ┌─ Location Path ───────────── ─┐  │
│  │  [color swatch]                │  │
│  └────────────────────────────────┘  │
│                                      │
│  (FloatingNavBarClearance: 120dp)    │
└──────────────────────────────────────┘
```

---

#### 5.4 Route Visualization

Route lines rendered via MapLibre's `Line` layer config. Design tokens for route styling:

```kotlin
object RidgelineMapTokens {
    // Active tracking route (being recorded now)
    val activeRouteColor: Color @Composable get() =
        MaterialTheme.colorScheme.primary          // Secure Teal
    const val activeRouteWidthDp = 5f
    const val activeRouteOpacity = 1.0f

    // Historical route (past session playback)
    val historyRouteColor = Color(0xFF00829B)      // TrackHistoryColor
    const val historyRouteWidthDp = 4f
    const val historyRouteOpacity = 0.85f

    // Selected/highlighted route segment
    val selectedRouteColor: Color @Composable get() =
        MaterialTheme.colorScheme.tertiary         // Sunset Rust
    const val selectedRouteWidthDp = 6f
    const val selectedRouteOpacity = 1.0f

    // Heatmap defaults
    const val heatmapRadiusPx = 20f
    const val heatmapIntensity = 1.0f
    const val heatmapOpacity = 0.8f
}
```

**Route line rendering respects reduced-motion:**
- Default: polyline draws progressively with `SpatialGlide` spring as new points arrive
- Reduced motion: full polyline appears instantly, no progressive draw

---

#### 5.5 Permission Banner

Shown at Z2 top-center when location permission is not granted. Uses `surfaceContainerHigh` with `error` accent:

```kotlin
@Composable
fun LocationPermissionCard(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RidgelineShapes.medium,   // TerrainCardShape
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "Location permission needed for map",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRequestPermission) {
                Text("Grant")
            }
        }
    }
}
```

---

#### 5.6 Map + Ridgeline Glass Integration

The bottom sheet and floating nav bar are the two places where glass treatment intersects the map:

1. **Bottom sheet:** Uses `surfaceContainerLow` (opaque) — NOT glass. Reason: sheet content is information-dense (search, sliders, layer cards). Glass behind text reduces readability. The sheet is opaque with 4dp shadow for clear separation from the map.

2. **Floating nav bar:** Uses glass treatment (blur + tint). The nav bar overlays the map at the bottom and benefits from through-content visibility — users can see the map "through" the bar, reinforcing spatial continuity. This matches the existing `FloatingNavigationBar` implementation.

3. **Map tool buttons:** Use `surfaceContainerHigh` (opaque, 52dp circles). Small surfaces with icons need maximum contrast against the unpredictable map background. No glass.

**Rule:** Glass treatment is reserved for large, icon-heavy surfaces (nav bar) where the content is minimal and spatial context helps. Text-heavy surfaces (sheets, cards, banners) are always opaque.

---

### 6. Onboarding Flow

The existing onboarding in `OnboardingActivity.kt` / `StreamlinedOnboardingScreen.kt` is well-architected. This spec refines the visual design and flow to align with Ridgeline tokens. The goal: **< 30 seconds to first track, zero configuration required**.

---

#### 6.1 Flow Architecture

```
Splash (system) → Welcome → Location Precision → [Permissions] → Dashboard
                     1              2               3a, 3b, 3c
```

**Three screens maximum.** No feature tours, no carousels, no "tips." The app teaches through use, not tutorials.

| Screen | Purpose | Can Skip? | Duration Target |
|---|---|---|---|
| **1. Welcome** | Brand + value props + "Get Started" | No (it IS the start) | 5s read time |
| **2. Location Precision** | Approximate vs Precise choice | No (affects core behavior) | 8s decision |
| **3. Permissions** | Sequential permission requests | Yes (each individually) | 10–15s total |

After screen 3, `applySmartDefaults()` fires and user lands on Dashboard. Total: ~25 seconds.

---

#### 6.2 Screen 1 — Welcome

```
┌──────────────────────────────────────┐
│                                      │
│           (vertical spacer: 0.2f)    │
│                                      │
│            ┌──────────┐              │
│            │  [logo]  │  96dp        │
│            └──────────┘              │
│                                      │
│          Tracker                     │  displayMedium, Outfit Bold
│                                      │
│    Your journeys. Your device.       │  titleMedium, onSurfaceVariant
│           Only yours.                │
│                                      │
│           (vertical spacer: 0.15f)   │
│                                      │
│  🔒  Everything stays on-device      │  benefit row
│  📊  Automatic insights & stats      │  benefit row
│  🏆  Challenges & achievements       │  benefit row
│                                      │
│           (vertical spacer: weight)  │
│                                      │
│  ┌──────────────────────────────┐    │
│  │        Get Started           │    │  FilledButton, primary
│  └──────────────────────────────┘    │
│                                      │  16dp bottom + nav inset
└──────────────────────────────────────┘
```

```kotlin
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.2f))

        // App icon
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Tracker",
            modifier = Modifier.size(96.dp),
        )

        Spacer(Modifier.height(24.dp))

        // Title
        Text(
            text = "Tracker",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        // Subtitle
        Text(
            text = "Your journeys. Your device.\nOnly yours.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(0.15f))

        // Benefit rows
        BenefitRow(
            icon = Icons.Outlined.Lock,
            text = "Everything stays on your device",
        )
        Spacer(Modifier.height(16.dp))
        BenefitRow(
            icon = Icons.Outlined.Insights,
            text = "Automatic insights & statistics",
        )
        Spacer(Modifier.height(16.dp))
        BenefitRow(
            icon = Icons.Outlined.EmojiEvents,
            text = "Challenges & achievements",
        )

        Spacer(Modifier.weight(1f))

        // CTA
        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RidgelineShapes.large,  // MomentumPillShape
        ) {
            Text(
                text = "Get Started",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BenefitRow(
    icon: ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

---

#### 6.3 Screen 2 — Location Precision

Binary choice. No slider, no "advanced options." Two large tappable cards.

```
┌──────────────────────────────────────┐
│                                      │
│     How precise should tracking      │  headlineSmall, Outfit
│                  be?                 │
│                                      │
│  You can change this anytime in      │  bodyMedium, onSurfaceVariant
│            settings.                 │
│                                      │
│  ┌────────────────────────────────┐  │
│  │  ◉  Precise                    │  │  Selected state: primaryContainer
│  │     GPS-level accuracy         │  │
│  │     Best for walking, running  │  │
│  └────────────────────────────────┘  │
│                                      │  12dp gap
│  ┌────────────────────────────────┐  │
│  │  ○  Approximate                │  │  Unselected: surfaceContainerLow
│  │     City-block accuracy        │  │
│  │     Better battery life        │  │
│  └────────────────────────────────┘  │
│                                      │
│           (spacer weight)            │
│                                      │
│  ┌──────────────────────────────┐    │
│  │          Continue             │    │  FilledButton, primary
│  └──────────────────────────────┘    │
│                                      │
└──────────────────────────────────────┘
```

```kotlin
@Composable
fun LocationPrecisionScreen(
    onContinue: (LocationPrecision) -> Unit,
) {
    var selected by remember { mutableStateOf(LocationPrecision.PRECISE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .statusBarsPadding(),
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = "How precise should tracking be?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "You can change this anytime in settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        PrecisionOptionCard(
            title = "Precise",
            description = "GPS-level accuracy\nBest for walking, running, cycling",
            isSelected = selected == LocationPrecision.PRECISE,
            onClick = { selected = LocationPrecision.PRECISE },
        )

        Spacer(Modifier.height(12.dp))

        PrecisionOptionCard(
            title = "Approximate",
            description = "City-block accuracy\nBetter battery life",
            isSelected = selected == LocationPrecision.APPROXIMATE,
            onClick = { selected = LocationPrecision.APPROXIMATE },
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onContinue(selected) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RidgelineShapes.large,
        ) {
            Text("Continue", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PrecisionOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RidgelineShapes.medium,  // TerrainCardShape
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null, // Card handles click
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
```

---

#### 6.4 Screen 3 — Permissions (Sequential)

Permissions are requested **one at a time**, each with a rationale card shown *before* the system dialog. This matches the existing `OnboardingPermissionManager` architecture.

**Sequence order** (matches existing `OnboardingStep` definitions):

1. **Foreground location** — Required. If denied, show "limited functionality" note but proceed.
2. **Background location** (Android 10+) — Optional. Rationale: "Track automatically without opening the app."
3. **Activity recognition** (Android 10+) — Optional. Rationale: "Detect walking, running, cycling."
4. **Notifications** (Android 13+) — Optional. Rationale: "See tracking status and milestones."

Each permission screen follows the same template:

```
┌──────────────────────────────────────┐
│                                      │
│     ┌────────────────────────┐       │
│     │      [icon: 48dp]      │       │  64dp circle, secondaryContainer
│     └────────────────────────┘       │
│                                      │
│      Permission Title                │  headlineSmall
│                                      │
│  Rationale text explaining why       │  bodyMedium, onSurfaceVariant
│  this permission helps the user.     │
│  Max 2 lines.                        │
│                                      │
│           (spacer weight)            │
│                                      │
│  ┌──────────────────────────────┐    │
│  │          Allow               │    │  FilledButton, primary
│  └──────────────────────────────┘    │
│                                      │
│        Skip for now                  │  TextButton, onSurfaceVariant
│                                      │
└──────────────────────────────────────┘
```

**Skip behavior** (matching existing `SkipReason` enum):

- "Skip for now" records `SkipReason.USER_CHOICE` and advances to next permission
- Denied system dialog records `SkipReason.PERMISSION_DENIED` and advances
- Permanently denied shows a "Open Settings" `OutlinedButton` option
- All skipped permissions surface later as contextual banners when the feature is used

```kotlin
@Composable
fun PermissionScreen(
    step: PermissionStep,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))

        // Icon circle
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = step.icon,
                contentDescription = null,
                modifier = Modifier.padding(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = step.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = step.rationale,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onAllow,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RidgelineShapes.large,
        ) {
            Text("Allow", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Skip for now",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
```

**Progress indicator:** A subtle `LinearProgressIndicator` at the top shows onboarding progress (1/4 → 2/4 → 3/4 → 4/4). Track color: `surfaceContainerHighest`. Indicator: `primary`. Height: 4dp.

```kotlin
LinearProgressIndicator(
    progress = { currentStep.toFloat() / totalSteps },
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.primary,
    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    strokeCap = StrokeCap.Round,
)
```

---

#### 6.5 Transitions Between Onboarding Screens

- **Default:** `AnimatedContent` with horizontal slide (300ms `SpatialGlide` spring)
- **Reduced motion:** Instant crossfade (0ms, no slide)
- **Back navigation:** Reverse slide direction (right-to-left → left-to-right)

```kotlin
val transitionSpec: AnimatedContentTransitionScope<OnboardingStep>.() -> ContentTransform = {
    val reduceMotion = LocalReducedMotion.current
    if (reduceMotion) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        val direction = if (targetState.ordinal > initialState.ordinal) {
            SlideDirection.Left
        } else {
            SlideDirection.Right
        }
        slideIntoContainer(direction, spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
            slideOutOfContainer(direction, spring(stiffness = Spring.StiffnessMediumLow))
    }
}
```

---

#### 6.6 Post-Onboarding Smart Defaults

After the final permission screen, `OnboardingViewModel.applySmartDefaults()` applies:

| Setting | Default Value | Source |
|---|---|---|
| Location precision | User's choice from Screen 2 | Onboarding |
| Tracking features | Location + Activity + Steps ON; WiFi/Cell OFF | Conservative battery default |
| Auto-tracking | OFF | Requires background location to be useful |
| Cleanup retention | 365 days | Generous default, user changes later |
| Notification style | Duration + Distance + Activity | Most useful subset |

No settings screen is shown. User lands on Dashboard immediately. Settings are discoverable through the nav bar's settings entry.

---

### 7. Notification & Tracking Overlay

The persistent notification is the app's most-seen surface during active tracking — users glance at it from the notification shade dozens of times per session. It must be information-rich, glanceable, and respect Material 3 notification guidelines.

---

#### 7.1 Notification Channel Architecture

The existing 5-channel setup is correct. Design specs for each:

| Channel | Importance | Vibration | Design Intent |
|---|---|---|---|
| `channel_track_id` | LOW | None | Persistent tracking — silent, non-intrusive |
| `channel_challenges_id` | HIGH | Yes | Achievement unlocked — celebratory interruption |
| `channel_goals_id` | HIGH | Yes | Goal reached — motivational interruption |
| `channel_activity_watcher_id` | LOW | None | Background monitoring — silent |
| `channel_other_id` | LOW | None | Catch-all |

**Ridgeline accent color for all channels:** `primary` (`#006874` light / `#4FD8EB` dark`). This is the notification LED color and header tint on supported OEMs.

---

#### 7.2 Persistent Tracking Notification

The foreground service notification is the core surface. Existing `TrackerNotificationManager` + 16 `NotificationComponent` providers are well-designed. This spec adds visual structure.

**Layout — BigTextStyle with structured content:**

```
┌──────────────────────────────────────────┐
│ 🔴 Tracker          Tracking · 1h 23m   │  Small icon + app name + duration
│──────────────────────────────────────────│
│  Walking  ·  4.2 km  ·  5.1 km/h        │  Activity + distance + speed
│──────────────────────────────────────────│
│  [Stop]              [Stop for 30 min]   │  Actions (user-initiated session)
└──────────────────────────────────────────┘
```

**For auto-tracking sessions:**

```
┌──────────────────────────────────────────┐
│ 🟢 Tracker          Auto · 45m          │
│──────────────────────────────────────────│
│  In vehicle  ·  28.5 km  ·  62 km/h     │
│──────────────────────────────────────────│
│  [Stop until recharge]   [Stop 30 min]   │  Actions (auto session)
└──────────────────────────────────────────┘
```

**Implementation spec for notification builder:**

```kotlin
object TrackerNotificationManager {
    const val NOTIFICATION_ID = -7643

    fun createTrackingNotification(
        context: Context,
        session: ActiveSession,
        components: List<NotificationComponent>,
    ): Notification {
        val isDark = context.isNightMode()

        // Material 3 notification colors
        val backgroundColor = if (isDark) 0xFF1C1B1F.toInt() else 0xFFFEF7FF.toInt()

        val builder = NotificationCompat.Builder(context, CHANNEL_TRACKING_ID)
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(context.getColor(R.color.notification_accent)) // primary
            .setColorized(true)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(buildSessionTitle(session))
                    .bigText(buildComponentText(components))
            )

        // Actions based on session type
        if (session.isAutoTracking) {
            builder.addAction(buildStopUntilRechargeAction(context))
            builder.addAction(buildStopTimedAction(context, minutes = 30))
        } else {
            builder.addAction(buildStopAction(context))
        }

        return builder.build()
    }

    private fun buildSessionTitle(session: ActiveSession): String {
        val activity = session.primaryActivity?.displayName ?: "Tracking"
        val duration = formatDurationCompact(session.durationMs)
        return "$activity · $duration"
    }

    private fun buildComponentText(
        components: List<NotificationComponent>,
    ): CharSequence {
        // Format: "4.2 km · 5.1 km/h · 147 steps"
        return components
            .filter { it.isEnabled }
            .sortedBy { it.displayOrder }
            .joinToString(" · ") { "${it.formattedValue}" }
    }
}
```

---

#### 7.3 Notification Component Display Order

Users customize which components appear via `PreferenceDatabase`. Default ordering for a new install:

| Priority | Component | Example | Rationale |
|---|---|---|---|
| 1 | Activity | Walking | What you're doing |
| 2 | Distance | 4.2 km | Most-wanted metric |
| 3 | Duration | 1h 23m | Already in title, but valued |
| 4 | Speed | 5.1 km/h | Real-time feedback |
| 5 | Steps | 6,847 | Gamification hook |

Components 6–16 (altitude, accuracy, cell count, WiFi count, lat/lon, etc.) are OFF by default. Users enable via notification customization settings.

**Privacy rule:** `LatitudeNotificationComponent` and `LongitudeNotificationComponent` are **OFF by default** and show a privacy warning when enabled: "Coordinates will be visible in your notification shade. Anyone who sees your screen can read your location."

---

#### 7.4 Achievement & Goal Notifications

Celebratory notifications use `IMPORTANCE_HIGH` for heads-up display:

```kotlin
fun buildAchievementNotification(
    context: Context,
    achievement: Achievement,
): Notification {
    return NotificationCompat.Builder(context, CHANNEL_CHALLENGES_ID)
        .setSmallIcon(R.drawable.ic_notification_achievement)
        .setContentTitle("🏆 ${achievement.title}")
        .setContentText(achievement.description)
        .setAutoCancel(true)
        .setColor(context.getColor(R.color.notification_accent))
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(buildDashboardIntent(context))
        .build()
}
```

---

#### 7.5 Widget — Not Implemented (Intentional)

**No home screen widget in the current scope.** Rationale:

1. **Compose Glance is still maturing.** `androidx.glance:glance-appwidget` reached 1.1.0 but still has significant limitations (no custom shapes, limited theming, no blur).
2. **The 96dp TrackingFAB on Dashboard IS the widget.** Users tap it from the home screen shortcut → Dashboard → FAB. Adding a widget duplicates this with worse UX.
3. **Privacy concern.** A home screen widget showing distance/speed/location is visible to shoulder surfers. The persistent notification at least requires pulling down the shade.

**Future consideration:** When Glance supports Material 3 Expressive shapes and dynamic color properly, a minimal start/stop widget (single circular button, no data display) could be added. That's Round 15+ territory.

---

#### 7.6 Tracking Overlay — Not Implemented (Intentional)

**No floating overlay (SYSTEM_ALERT_WINDOW).** Reasons:

1. **Android 14+ restricts overlay permissions aggressively.** Users must navigate multiple settings screens to grant `SYSTEM_ALERT_WINDOW`. Conversion rate is near-zero for non-accessibility apps.
2. **Battery impact.** A rendered overlay view, even minimal, keeps the GPU active and prevents the display from entering low-power states.
3. **The notification IS the overlay.** With Android's notification shade being a single swipe away, a floating bubble provides no meaningful time savings.

---

## Summary: Round 9 Positions

### Resolved Disagreements (HOLD on all 4)

| # | Topic | Position | Status |
|---|---|---|---|
| 1 | Display Font | Outfit for display/headline/title, system for body/label | **HOLD** — metrics tuned, Czech support verified |
| 2 | System Name | Ridgeline (QuietTopo as Kotlin alias only) | **HOLD** — vocabulary generation, single-word convention |
| 3 | Glass + Reduced Motion | Glass blur stays; solid only under reduced-transparency | **HOLD** — Android 15 confirms two-axis model |
| 4 | Mini FAB | No mini FAB; 52dp MapToolButton for all map controls | **HOLD** — 48dp minimum, existing impl already correct |

### New Specs Delivered

| # | Topic | Key Decisions |
|---|---|---|
| 5 | Map UI | 4 Z-layers, 52dp tool buttons, opaque sheet, glass nav bar only, route tokens |
| 6 | Onboarding | 3 screens max, < 30s, sequential permissions with skip, smart defaults |
| 7 | Notifications | 16 customizable components, privacy-default lat/lon OFF, no widget, no overlay |

---

**Open for Round 10:** Dashboard layout specification, settings screen architecture, gamification UI (challenges/goals/points), trip detail screen.
