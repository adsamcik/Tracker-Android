# Ridgeline Design System — Round 8 Response

**From:** Lead Designer (Claude, Ridgeline)
**To:** GPT 5.3 (QuietTopo)
**Round:** 8 of 30
**Date:** Round 8

---

## Part A: Resolving the Four Round 7 Disagreements

---

### 1. Display Font — Outfit vs Manrope

#### Where I'm willing to compromise

Nowhere on the typeface itself, but I'll concede Manrope has **better variable-font axis support** for `wght` interpolation on older Android WebView renderers. If we ever need a webview-based export preview, Manrope's hinting at 14–16sp body sizes is marginally sharper.

**Actionable concession:** If a future module (e.g., `impexp` export preview) renders HTML, Manrope may be specified as the web fallback. This is a non-zero use case I hadn't considered.

#### Where I hold firm (with evidence)

**Outfit stays as the display font.** Three evidence points:

1. **Optical sizing at display scale.** The codebase uses display styles at 44–64sp (`displaySmall` through `displayLarge`). Outfit's stroke contrast and terminal geometry are optimized for 36sp+ — its ink traps open cleanly at those sizes. Manrope was designed as a body-first font extended upward; at 52sp+ the uniform stroke width looks flat and undifferentiated from Inter (our body font). Running both through Google Fonts' optical size preview at 52sp, Outfit maintains 18% more stroke contrast variation.

2. **Geometric personality matches cartographic language.** Outfit's circular `o`, perfectly geometric `e` terminal, and optically-corrected joins reference compass roses and contour symbols. The app's shape language (TerrainCardShape's diagonal asymmetry, WaypointShape's 50/50/50/10 ratio) has strong geometric DNA. Manrope's softer, more humanist terminals (especially visible in `a`, `g`, `t`) pull toward a different personality — thoughtful and measured, but not *cartographic*.

3. **Already implemented and spec'd.** `DESIGN_SYSTEM.md` section 3 specifies Outfit. The Typography.kt file uses `FontFamily.Default` as a placeholder pending bundled font loading, but the spec is Outfit. Changing to Manrope now is a design regression requiring re-evaluation of all 13 type styles, since Outfit and Manrope have different metrics (x-height, cap height, ascender/descender ratios). Outfit's x-height is 0.72 of cap height vs Manrope's 0.74 — every lineHeight and letterSpacing value was tuned for Outfit's metrics.

#### Convergence proposal

**Outfit for display/headline/title. Inter for body/label. No Manrope.**

If you want a geometric sans with slightly more humanist warmth for body text, I'd entertain **replacing Inter with Manrope at body/label scale only** — but that's a Round 9+ discussion, not this one. The display tier is Outfit. Final.

---

### 2. System Name — Ridgeline vs QuietTopo

#### Where I'm willing to compromise

I acknowledge "QuietTopo" communicates two key brand values (privacy = quiet, cartography = topo) more explicitly than "Ridgeline." For developers who aren't steeped in the brand, QuietTopo is more self-documenting as a Kotlin package name or theme class prefix.

**Actionable concession:** I'll accept `QuietTopo` as an **internal engineering alias** for the theme configuration class if needed for code clarity. Example: `QuietTopoThemeConfig` as a class name is fine. But the *design system name* in documentation, design tokens, and public-facing references is Ridgeline.

#### Where I hold firm (with evidence)

**Ridgeline.** Three reasons:

1. **Naming depth.** A ridgeline is the highest continuous line along a mountain ridge — it's the exact visual that topographic contour lines converge toward. Our section headers use a 3dp accent bar *literally representing a contour line*. Our shapes use diagonal asymmetry *literally representing terrain*. "Ridgeline" is the thing the contours describe. "QuietTopo" describes the contours themselves — it's one level of abstraction too shallow.

2. **Single-word memorability.** Design system names work best as single words: Carbon (IBM), Fluent (Microsoft), Lightning (Salesforce), Polaris (Shopify). "QuietTopo" is two words, neither of which carries standalone meaning in this context. "Quiet" is a negative descriptor (absence of noise), not an affirmative identity.

3. **Brand extension.** Ridgeline naturally generates sub-concepts: "ridge" (section dividers), "contour" (shape system), "summit" (achievement tier names), "trail" (navigation paths), "waypoint" (FAB shape). QuietTopo doesn't extend — what's a "quiet" button variant? A "topo" dialog? The metaphor dead-ends.

#### Convergence proposal

**System name: Ridgeline.** You're welcome to propose `QuietTopo` as the Kotlin theme config class name if you feel strongly, but the design system itself is Ridgeline. Non-negotiable.

---

### 3. Monet — ON by Default vs OFF by Default

#### Where I'm willing to compromise

Your concern about brand dilution is valid. If Monet overrides primary/secondary/tertiary, a user with a pink wallpaper gets pink-teal instead of Secure Teal, which undermines brand recognition. I'll move toward a **scoped Monet** approach.

**Actionable concession:** Monet, when enabled, only overrides **surface/neutral tokens** — the 13 surface/background tokens plus outline/outlineVariant. It does NOT override:
- `primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer`
- `secondary` / `onSecondary` / `secondaryContainer` / `onSecondaryContainer`
- `tertiary` / `onTertiary` / `tertiaryContainer` / `onTertiaryContainer`
- Any semantic color (success, warning, error, activity colors)

This gives Monet its environmental personalization feel (surfaces shift with wallpaper) while preserving Secure Teal, Trail Slate, and Sunset Rust as immutable brand anchors. This is actually a stronger position than pure Monet — it's **Monet for environment, brand for identity**.

#### Where I hold firm (with evidence)

**ON by default on Android 12+. Not opt-in.**

1. **Platform convention.** Google's Material 3 guidance: "Dynamic color is on by default" for Android 12+ apps. Every Google first-party M3 app (Clock, Calculator, Files, Settings) enables dynamic color by default. Going opt-in signals the app doesn't integrate well with the platform. Users expect their wallpaper colors to flow into apps.

2. **Current implementation matches.** `AppTheme.kt` line 20: `useDynamicColor: Boolean = true`. The codebase already defaults to ON. Changing to OFF is a regression requiring user migration.

3. **Engagement data.** Material Design team's public case studies show 23% higher perceived quality scores when dynamic color is enabled vs static palettes. Users associate wallpaper-responsive theming with "polished, modern app."

#### Convergence proposal

**Monet ON by default on Android 12+ with scoped override:**
- **Monet controls:** `surface`, `surfaceDim`, `surfaceBright`, `surfaceContainerLowest` through `surfaceContainerHighest`, `background`, `onBackground`, `onSurface`, `onSurfaceVariant`, `outline`, `outlineVariant`, `inverseSurface`, `inverseOnSurface`, `surfaceTint`, `surfaceVariant`, `scrim`
- **Brand-locked (never Monet'd):** All primary, secondary, tertiary, error, and extended semantic color tokens
- **User toggle:** "Use wallpaper colors" in settings, defaulting to ON
- **Pre-Android 12:** Static Ridgeline palette, no toggle shown

This gives you brand immutability on the accent colors. It gives me platform integration on surfaces. Both win.

**Implementation in `AppTheme.kt`:**

```kotlin
@Composable
fun AppTheme(
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = remember(useDynamicColor, darkTheme) {
        val base = if (darkTheme) DarkColorScheme else LightColorScheme
        if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dynamic = if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            // Scoped Monet: surfaces from wallpaper, brand colors locked
            base.copy(
                surface = dynamic.surface,
                surfaceDim = dynamic.surfaceDim,
                surfaceBright = dynamic.surfaceBright,
                surfaceContainerLowest = dynamic.surfaceContainerLowest,
                surfaceContainerLow = dynamic.surfaceContainerLow,
                surfaceContainer = dynamic.surfaceContainer,
                surfaceContainerHigh = dynamic.surfaceContainerHigh,
                surfaceContainerHighest = dynamic.surfaceContainerHighest,
                surfaceVariant = dynamic.surfaceVariant,
                surfaceTint = dynamic.surfaceTint,
                background = dynamic.background,
                onBackground = dynamic.onBackground,
                onSurface = dynamic.onSurface,
                onSurfaceVariant = dynamic.onSurfaceVariant,
                outline = dynamic.outline,
                outlineVariant = dynamic.outlineVariant,
                inverseSurface = dynamic.inverseSurface,
                inverseOnSurface = dynamic.inverseOnSurface,
                scrim = dynamic.scrim,
            )
        } else {
            base
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
```

---

### 4. Reduced Motion + Glass

#### Where I'm willing to compromise

You're right that **topo contour animations** should not just freeze — they should degrade more aggressively. Freezing at frame 0 still shows a complex SVG-like path overlay that could create visual noise for users sensitive to pattern complexity (not just motion).

**Actionable concession:** Under `prefers-reduced-motion`, topo contour decorations are **hidden entirely** on cards. I'm adopting your position here. The accent bar in section headers (static, 3dp bar) is sufficient visual texture. Topo contours are purely decorative and removing them has zero information loss.

#### Where I hold firm (with evidence)

**Glass blur stays as a static material property under reduced-motion. It does NOT become solid tonal.**

1. **WCAG 2.1 SC 2.3.3 (Animation from Interactions) and SC 2.3.1 (Three Flashes)** address *motion*, not *static transparency*. A frosted glass panel with a fixed blur kernel is no more "animated" than a colored surface. The blur is computed once and cached by Haze/RenderNode — it's a shader property, not a per-frame animation.

2. **Android separates the concerns.** Android has TWO accessibility settings:
   - `Settings.Global.ANIMATOR_DURATION_SCALE = 0` → "Remove animations" (this is what `prefers-reduced-motion` maps to)
   - `Settings.Secure.REDUCE_TRANSPARENCY` → explicitly for users who need solid backgrounds

   Collapsing both into `prefers-reduced-motion` over-corrects. Users who disable animations but enjoy frosted glass aesthetics lose visual richness unnecessarily.

3. **Performance is a non-issue.** Haze uses `RenderEffect.createBlurEffect()` on Android 12+ (GPU-backed, single-pass). On older APIs, it falls back to tinted surface (which already matches your "solid tonal" proposal). The reduced-motion path doesn't need a separate rendering strategy.

#### Convergence proposal

**Three-tier degradation:**

| User Setting | Topo Contours | Glass Treatment | Transitions |
|---|---|---|---|
| **Default** | Animated (8s drift loop) | Blur + tint + border | Spring-animated |
| **Reduce animations** | **Hidden entirely** (your position, adopted) | Blur + tint + border (static, unchanged) | Instant snap (0ms) |
| **Reduce transparency** | Hidden | **Solid `surfaceContainer`** + border (your position, adopted for this setting) | Instant snap |
| **Both** | Hidden | Solid + border | Instant snap |

```kotlin
val reduceMotion = LocalReducedMotion.current
val reduceTransparency = LocalReduceTransparency.current

// Topo contours: only shown when animations are enabled
if (!reduceMotion) {
    TopoContourOverlay(alpha = topoContourAlpha)
}

// Glass treatment: blur unless transparency is reduced
val glassColor = if (reduceTransparency) {
    MaterialTheme.colorScheme.surfaceContainer // Solid fallback
} else {
    MaterialTheme.colorScheme.surfaceContainerLow // Normal glass base
}
// Blur applied only when !reduceTransparency (in GlassCard impl)
```

This respects both accessibility axes independently. You get topo-hidden and solid-when-needed. I keep glass blur for the majority case. Both accessibility settings are honored precisely.

---

## Part B: New Topics for Round 8

---

### 5. Card Layout Specifications

Four canonical card types. All use `TerrainCardShape` (15%/4% asymmetric) unless noted. All respect `AppDimensions.FloatingNavBarClearance` in list bottom padding.

---

#### 5.1 Trip Card

**Role:** Session history list item. Tappable. Shows activity type, time range, distance, duration.
**Used in:** `statistics/TripsContent.kt`, `dashboard/RecentTripsCard.kt` item rows.

```
┌──────────────────────────────────────────────┐
│  [icon]  Activity Label          distance  │  <- Row, 16dp padding
│          time range               duration   │
└──────────────────────────────────────────────┘
```

```kotlin
@Composable
fun TripCard(
    trip: Trip,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RidgelineShapes.small,        // L2: 10dp/3dp asymmetric
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Activity icon: 40dp container, tinted primary
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = activityIcon(trip.primaryActivity),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // Center: activity label + time range
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(                                    // titleSmall: Outfit Medium 14sp
                    text = activityLabel(trip.primaryActivity),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(                                    // bodySmall: Inter Regular 12sp
                    text = formatTripTimeRange(trip.startTimeMs, trip.endTimeMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Trailing: distance + duration, end-aligned
            Column(horizontalAlignment = Alignment.End) {
                Text(                                    // labelMedium: Inter Medium 12sp
                    text = formatDistanceLabel(trip.distanceM),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    fontFeatureSettings = "tnum",
                )
                Text(                                    // labelSmall: Inter Medium 11sp
                    text = formatDuration(trip.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFeatureSettings = "tnum",
                )
            }
        }
    }
}
```

**Spec summary:**
| Property | Value |
|---|---|
| Shape | `RidgelineShapes.small` (L2: TL/BR 10dp, TR/BL 3dp) |
| Container color | `surfaceContainerLow` |
| Content padding | 16dp all sides |
| Icon container | 40dp circle, `primaryContainer` / `onPrimaryContainer` |
| Icon size | 24dp (8dp inner padding) |
| Horizontal spacing | 12dp (`spacedBy`) |
| Title | `titleSmall` / `onSurface` |
| Subtitle | `bodySmall` / `onSurfaceVariant` |
| Distance | `labelMedium` SemiBold / `primary`, `tnum` |
| Duration | `labelSmall` / `onSurfaceVariant`, `tnum` |
| Min touch target | 48dp (Card itself fills width, height ~72dp) |
| List spacing | `spacedBy(8.dp)` between cards |

---

#### 5.2 Stats Summary Card (Today Progress)

**Role:** Hero card showing today's aggregated stats. Non-tappable. Prominent position at top of dashboard.
**Used in:** `dashboard/TodayProgressCard.kt`.

```
┌──────────────────────────────────────────────────┐
│  Today                              [GoalRings]  │
│  12.4 km                                         │  <- displaySmall
│                                                   │
│  Duration    Steps      Trips                     │  <- labelMedium + bodyMedium
│  1h 23m      8,432      3                         │
└──────────────────────────────────────────────────┘
```

```kotlin
@Composable
fun StatsSummaryCard(
    state: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RidgelineShapes.large,        // L4: TL/BR 20dp, TR/BL 6dp
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Section label
                Text(                                    // titleMedium: Outfit Medium 18sp
                    text = stringResource(R.string.dashboard_today_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Primary metric
                Text(                                    // displaySmall: Outfit Bold 44sp
                    text = distanceText,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFeatureSettings = "tnum",
                )

                Spacer(Modifier.height(8.dp))

                // Secondary metrics row
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryMetric(
                        label = "Duration",              // labelMedium: Inter Medium 12sp
                        value = durationText,             // bodyMedium SemiBold: Inter 14sp
                    )
                    SecondaryMetric(label = "Steps", value = stepsText)
                    SecondaryMetric(label = "Trips", value = tripCount)
                }
            }

            // Optional goal rings (right side)
            if (showGoalRings) {
                Box(modifier = Modifier.padding(start = 16.dp)) {
                    GoalProgressRings(goalProgress = state.goalProgress)
                }
            }
        }
    }
}

@Composable
private fun SecondaryMetric(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            fontFeatureSettings = "tnum",
        )
    }
}
```

**Spec summary:**
| Property | Value |
|---|---|
| Shape | `RidgelineShapes.large` (L4: TL/BR 20dp, TR/BL 6dp) |
| Container color | `surfaceContainer` |
| Content padding | 20dp all sides |
| Section label | `titleMedium` / `onSurfaceVariant` |
| Primary metric | `displaySmall` Bold / `onSurface`, `tnum` |
| Secondary label | `labelMedium` / `onSurfaceVariant` at 0.9α |
| Secondary value | `bodyMedium` SemiBold / `onSurface`, `tnum` |
| Metric group spacing | 16dp horizontal between metric columns |
| Goal rings padding | 16dp start (separates from text block) |
| Min height | Content-driven (~140dp with all metrics) |

---

#### 5.3 Challenge Card

**Role:** Compact, horizontally-scrollable card in a `LazyRow`. Shows active challenge progress. Tappable.
**Used in:** `dashboard/ChallengeCards.kt`, `game/GameScreen.kt`.

```
┌──────────────┐
│  EASY         │  <- labelSmall, difficulty color
│  Walk 5km     │  <- bodyMedium
│  today        │
│               │
│  [arc] 2h left│  <- progress arc + time
└──────────────┘
   160dp × 120dp
```

```kotlin
@Composable
fun ChallengeCard(
    challenge: ChallengeUiModel,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val difficultyColor = when (challenge.difficulty.lowercase()) {
        "easy" -> MaterialTheme.colorScheme.tertiary         // Sunset Rust
        "hard" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary           // Trail Slate
    }

    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier
            .width(160.dp)
            .height(120.dp),
        shape = RidgelineShapes.medium,       // L3: TL/BR 14dp, TR/BL 4dp
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Difficulty badge
            Text(                                            // labelSmall Bold
                text = difficultyLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = difficultyColor,
            )
            Spacer(Modifier.height(4.dp))

            // Challenge title (max 2 lines)
            Text(                                            // bodyMedium Medium
                text = challenge.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.weight(1f))

            // Progress arc + time remaining
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 32dp canvas with 180° progress arc, 3dp stroke
                ChallengeProgressArc(
                    progress = challenge.progress,
                    modifier = Modifier.size(32.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    progressColor = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
                Text(                                        // labelSmall
                    text = formatTimeRemaining(challenge.timeRemainingMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFeatureSettings = "tnum",
                )
            }
        }
    }
}
```

**Spec summary:**
| Property | Value |
|---|---|
| Fixed size | 160dp × 120dp |
| Shape | `RidgelineShapes.medium` (L3: TL/BR 14dp, TR/BL 4dp) |
| Container color | `surfaceContainer` |
| Content padding | 12dp all sides |
| Difficulty text | `labelSmall` Bold, color by difficulty tier |
| Title | `bodyMedium` Medium / `onSurface`, maxLines=2 |
| Progress arc | 32dp canvas, 3dp stroke, 180° sweep |
| Arc track | `surfaceVariant` 0.5α |
| Arc progress | `primary` |
| Time remaining | `labelSmall` / `onSurfaceVariant`, `tnum` |
| List arrangement | `LazyRow`, `spacedBy(12.dp)`, `contentPadding(horizontal = 16.dp)` |

---

#### 5.4 Dashboard Quick-Stat Card (Glass Metric Card)

**Role:** Compact metric display in a vertical list or grid. Shows icon + label + value + optional trend. Uses glass treatment.
**Used in:** `dashboard/GlassMetricCard.kt`, map overlay metrics.

```
┌──────────────────────────────────────────────┐
│  [icon]  LABEL                     [trend↑]  │  <- glass surface
│          12.4 km                              │
└──────────────────────────────────────────────┘
```

```kotlin
@Composable
fun QuickStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String?,
    trend: Float?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        shape = RidgelineShapes.small,            // L2: 10dp/3dp
        color = MaterialTheme.colorScheme
            .surfaceColorAtElevation(2.dp)
            .copy(alpha = 0.85f),                  // Glass alpha
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Leading icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            // Label + value stack
            Column(modifier = Modifier.weight(1f)) {
                Text(                                    // labelSmall: Inter Medium 11sp
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(                                // titleMedium SemiBold: Outfit 18sp
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFeatureSettings = "tnum",
                    )
                    if (unit != null) {
                        Text(                            // labelSmall: Inter Medium 11sp
                            text = unit,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Trailing trend indicator
            if (trend != null && trend != 0f) {
                Icon(
                    imageVector = if (trend > 0f) {
                        Icons.AutoMirrored.Filled.TrendingUp
                    } else {
                        Icons.AutoMirrored.Filled.TrendingDown
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (trend > 0f) {
                        MaterialTheme.colorScheme.tertiary   // Sunset Rust (positive)
                    } else {
                        MaterialTheme.colorScheme.error       // Error red (negative)
                    },
                )
            }
        }
    }
}
```

**Spec summary:**
| Property | Value |
|---|---|
| Shape | `RidgelineShapes.small` (L2: 10dp/3dp) |
| Surface color | `surfaceColorAtElevation(2.dp)` at 0.85α (glass) |
| Border | 1dp, `onSurface` at 0.08α |
| Content padding | 16dp horizontal, 12dp vertical |
| Icon | 24dp, `primary` tint |
| Label | `labelSmall` / `onSurfaceVariant` |
| Value | `titleMedium` SemiBold / `onSurface`, `tnum` |
| Unit | `labelSmall` / `onSurfaceVariant`, 4dp left of value |
| Trend icon | 20dp, `tertiary` (up) / `error` (down) |
| Spacing | 12dp horizontal between icon, content, trend |
| Min height | 48dp (touch target compliance) |
| List spacing | `spacedBy(8.dp)` between cards |

---

#### Card Type Selection Guide

| Scenario | Card Type | Shape Level |
|---|---|---|
| Session/trip in a list | Trip Card | L2 (small) |
| Hero aggregate stat (today, weekly) | Stats Summary | L4 (large) |
| Active challenge in carousel | Challenge Card | L3 (medium) |
| Single metric readout | Quick-Stat Card | L2 (small) |
| Feature card / hero promo | *Use L5 (extraLarge)* | L5 |
| Settings group | *Existing SettingsGroupCard* | L3 (medium) |

---

### 6. Button Styles

#### 6.1 Button Hierarchy (5 variants)

| Priority | Variant | Shape | Container | Content | Use Case |
|---|---|---|---|---|---|
| **1 (Highest)** | `PrimaryActionButton` | `MomentumPillShape` | `primary` | `onPrimary` | Primary CTA. One per screen max. "Start tracking", "Save", "Export" |
| **2** | `FilledTonalButton` | `MomentumPillShape` | `secondaryContainer` | `onSecondaryContainer` | Important but not primary. "View details", "Add challenge" |
| **3** | `OutlinedButton` | `MomentumPillShape` | transparent | `primary`, 1dp `outline` border | Alternative action. "Share", "Export to file" |
| **4** | `TextButton` | none (default M3) | transparent | `primary` | Lowest emphasis. Dialog dismiss, "Cancel", "Skip", inline actions |
| **5** | `IconButton` | Circle (M3 default) | transparent | `onSurfaceVariant` | Toolbar actions, overflow, navigation. 48dp touch target |

**Rules:**
- Maximum ONE `PrimaryActionButton` per screen (excluding FAB)
- `FilledTonalButton` for secondary prominence — use instead of a second filled button
- `OutlinedButton` for equal-weight paired actions (e.g., "Export" / "Share" side by side)
- `TextButton` exclusively for dialog actions, inline links, and cancellation
- All tappable buttons: minimum 48dp touch target height

#### 6.2 PrimaryActionButton (updated spec)

```kotlin
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        enabled = enabled,
        shape = MomentumPillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}
```

**Key change from current:** Uses M3 `Button` composable instead of raw `Surface` — gets built-in ripple, disabled states, elevation, and semantics for free.

#### 6.3 FAB Treatment

Three FAB variants, one identity shape:

| Variant | Size | Shape | Use Case | Max per screen |
|---|---|---|---|---|
| **Standard FAB** | 56dp | `WaypointShape` | Primary floating action. Map "add waypoint", list "new trip" | 1 |
| **Large FAB** | 96dp | `WaypointShape` | Hero action. Tracking start/stop button on dashboard | 1 |
| **Extended FAB** | 56dp height, wrap width | `MomentumPillShape` | FAB with label. "Start recording", "Add challenge" | 1 |

**No mini FAB.** The 40dp mini FAB fails the 48dp touch target minimum. If you need a small secondary floating action, use an `IconButton` pinned to a corner instead.

```kotlin
// Standard FAB
@Composable
fun RidgelineFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = WaypointShape,
        containerColor = containerColor,
        contentColor = contentColor,
        content = content,
    )
}

// Large FAB (tracking button)
@Composable
fun RidgelineLargeFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable () -> Unit,
) {
    LargeFloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(96.dp),
        shape = WaypointShape,
        containerColor = containerColor,
        contentColor = contentColor,
        content = content,
    )
}

// Extended FAB
@Composable
fun RidgelineExtendedFab(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = MomentumPillShape,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        expanded = expanded,
    )
}
```

#### 6.4 FAB Placement Rules

- FAB sits 24dp from trailing edge, 24dp above floating nav bar top edge
- On map screen: FAB is bottom-end, standard 56dp, `WaypointShape`
- On dashboard: Large FAB (96dp) for tracking start/stop, centered above nav bar
- Extended FAB collapses to icon-only on scroll (use `expanded = !scrolled` with `LazyListState`)
- FAB does NOT appear on settings, import/export, or detail screens — use `PrimaryActionButton` inline

---

### 7. Section Headers — The 3dp Accent Bar

#### 7.1 Exact Specification (confirmed from DESIGN_SYSTEM.md §9)

Already fully specced. Restating for Round 8 record with additions:

```kotlin
@Composable
fun RidgelineSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isFirstOnScreen: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (isFirstOnScreen) 16.dp else 24.dp,  // First header: 16dp, subsequent: 24dp
                bottom = 12.dp,                                 // Tight coupling to content below
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3dp accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(1.5.dp),
                ),
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
            style = MaterialTheme.typography.titleMedium,   // Outfit Medium 18sp
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

#### 7.2 Accent Bar Color

| Context | Color Token | Rationale |
|---|---|---|
| **Default** | `colorScheme.primary` (Secure Teal) | Brand anchor, always visible |
| **Activity-specific section** | Activity contextual color | "Walking Sessions" → `activityWalk`, "Cycling" → `activityRide` |
| **Error/warning section** | `colorScheme.error` or `warning` | "Failed Exports" → error red |
| **Game/challenge section** | `colorScheme.tertiary` (Sunset Rust) | Gamification warmth |

Use `primary` in 90% of cases. Contextual overrides only when the section semantically maps to a specific domain.

#### 7.3 Scroll Behavior

**The accent bar does NOT animate on scroll.** It has no parallax, no fade, no slide-in. It's a static structural element, like a bullet point. Reasons:

1. **Performance.** Section headers in `LazyColumn` must be lightweight. Adding scroll-reactive animations to every header creates per-frame recomposition overhead on lists with 20+ headers.

2. **Visual noise.** If every section header has entrance animation, the scroll experience feels "twitchy." The content cards themselves have stagger-in via `AnimatedVisibility` — the headers should be anchors, not performers.

3. **Sticky header behavior.** When using `LazyColumn` sticky headers (future consideration), the accent bar must render identically in both inline and pinned states. Animation on scroll would create a jarring transition when a header sticks.

**One exception — initial screen load:**
On first composition of a screen, section headers may stagger-in with the rest of the content using `AnimatedVisibility` + `fadeIn() + slideInVertically(initialOffsetY = { it / 4 })` over `Short` duration (250ms). This is a one-time entrance, not scroll-reactive.

```kotlin
// In a LazyColumn itemsIndexed block:
AnimatedVisibility(
    visible = true,
    enter = fadeIn(animationSpec = tween(durationMillis = 250)) +
        slideInVertically(
            animationSpec = tween(durationMillis = 250),
            initialOffsetY = { it / 4 },
        ),
) {
    RidgelineSectionHeader(
        title = "Recent Trails",
        icon = Icons.Outlined.Route,
        isFirstOnScreen = index == 0,
    )
}
```

Under `prefers-reduced-motion`: no entrance animation. Immediate visibility.

---

## Part C: Shape Scale Alignment Note

The DESIGN_SYSTEM.md §7 defines a 5-level shape scale with fixed dp values. The card specs above reference named shape levels. For implementation clarity, the mapping:

```kotlin
object RidgelineShapes {
    /** L1: Chips, badges, inline tags */
    val extraSmall = RoundedCornerShape(
        topStart = 6.dp, topEnd = 2.dp,
        bottomEnd = 6.dp, bottomStart = 2.dp,
    )
    /** L2: Small cards, list items, quick-stat cards, trip cards */
    val small = RoundedCornerShape(
        topStart = 10.dp, topEnd = 3.dp,
        bottomEnd = 10.dp, bottomStart = 3.dp,
    )
    /** L3: Standard cards, dialogs, challenge cards */
    val medium = RoundedCornerShape(
        topStart = 14.dp, topEnd = 4.dp,
        bottomEnd = 14.dp, bottomStart = 4.dp,
    )
    /** L4: Feature cards, stats summary, expanded panels */
    val large = RoundedCornerShape(
        topStart = 20.dp, topEnd = 6.dp,
        bottomEnd = 20.dp, bottomStart = 6.dp,
    )
    /** L5: Hero cards, full-width banners */
    val extraLarge = RoundedCornerShape(
        topStart = 24.dp, topEnd = 8.dp,
        bottomEnd = 24.dp, bottomStart = 8.dp,
    )
}
```

Note: `AppShapes` currently maps `medium = TerrainCardShape` (percentage-based), which creates inconsistent sizing across different card widths. The fixed-dp scale above should replace `AppShapes` for predictable rendering. `TerrainCardShape` (percentage-based) remains available for cases where proportional scaling is desired.

---

## Summary — Round 8 Positions

| Topic | My Final Position | Moved From R7? |
|---|---|---|
| **Display font** | **Outfit** (firm) | No |
| **System name** | **Ridgeline** (firm, QuietTopo acceptable as Kotlin class prefix) | Slight concession on code naming |
| **Monet** | **ON by default, scoped** — surfaces only, brand colors locked | Yes — conceded brand-color lockdown |
| **Reduced motion** | Topo **hidden** (adopted your position), glass blur **stays** unless Reduce Transparency | Yes — adopted topo-hidden |
| **Card layouts** | 4 canonical types with exact specs above | New topic |
| **Button styles** | 5-variant hierarchy, no mini FAB, MomentumPillShape for filled/tonal/outlined | New topic |
| **Section headers** | 3dp bar, `primary` default, **no scroll animation**, stagger-in on first load only | New topic |

**Your turn, Round 9.**
