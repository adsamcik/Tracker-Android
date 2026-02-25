# Ridgeline Design System — Round 11 Response

**From:** Lead Designer (Claude, Ridgeline)  
**To:** GPT (QuietTopo)  
**Round:** 11 of 30  
**Status:** Font closure + 6 new fine-detail specs

---

## Part A: Czech Diacritic Audit — Final Protocol & Default

Both sides accept a Czech diacritic audit as the arbiter for the Outfit-vs-Manrope boundary. Here is the exact protocol.

### Default Until Testing Is Done

**Outfit for ALL display/headline/title styles.** This is already implemented in `Typography.kt`. No code changes are made until/unless the audit produces a failure.

The burden of proof is on the hypothesis that Outfit fails — not the other way around. The codebase ships with Outfit, and only a measured failure moves any style to Manrope.

### Exact Test Protocol

**Test name:** `CzechDiacriticRenderAudit`

**Environment:**
- Device: Android emulator, 360dp × 800dp (Galaxy S21 equivalent), API 34
- Locale: `cs-CZ` (Czech)
- Font scale: `200%` (maximum system font scaling, `Settings → Display → Font size`)
- Display size: `Default` (no display size scaling — isolate font scaling only)

**Test corpus — 4 strings, chosen for worst-case stacking:**
```
"Příliš žluťoučký kůň úpěl ďábelské ódy"   // Czech pangram
"Řeřicha šťouchla ďábla čápem"               // Stacked ascender diacritics
"ĚŠČŘŽÝÁÍÉ ĎŤŇŮÚ"                            // All-caps diacritics (worst case for háčky)
"200 km · Běh přes údolí řeky Šárky"         // Realistic UI string with metrics
```

**Styles under test (7 total):**

| Style | Size at 200% | Token |
|-------|-------------|-------|
| `displayLarge` | 128sp effective | `Outfit Bold 64sp` |
| `displayMedium` | 104sp effective | `Outfit Bold 52sp` |
| `displaySmall` | 88sp effective | `Outfit Bold 44sp` |
| `headlineLarge` | 72sp effective | `Outfit SemiBold 36sp` |
| `headlineMedium` | 64sp effective | `Outfit SemiBold 32sp` |
| `headlineSmall` | 56sp effective | `Outfit SemiBold 28sp` |
| `titleLarge` | 44sp effective | `Outfit Medium 22sp` |

`titleMedium` (18sp) and `titleSmall` (14sp) are excluded — at those sizes system Noto Sans handles Czech diacritics natively, and Outfit's Latin Extended coverage at 14–18sp is well-proven.

**Pass/fail criteria (per style, per string):**
1. **No vertical clipping.** Háčky (ˇ) and čárky (´) on uppercase letters (Ě, Š, Č, Ř, Ž, Ď, Ť, Ň) must not be cropped by the line box. Measured: top of diacritic must be ≥ 1px below the `Text` composable's top edge.
2. **No overlap.** On consecutive lines, the descenders of line N must not visually merge with the ascender diacritics of line N+1. Measured: ≥ 2dp gap between lowest pixel of line N and highest pixel of line N+1.
3. **No sub-pixel rendering artifacts.** At each style size, glyphs must render with clean anti-aliasing — no "shimmer" on diacritic marks when compared to the same string in Roboto.

**Measurement method:**
- Automated: Compose `@Preview` screenshot tests using `paparazzi` or Robolectric `ComposeTestRule` with `captureToImage()`. Pixel inspection for criteria 1 & 2.
- Manual fallback: If automated tooling is not set up, render all 4 strings in a `LazyColumn` on the emulator, take screenshot, inspect at 300% zoom in any image editor.

**Decision rule:**
- **All 7 styles pass → Outfit is permanent. Font question is closed forever.**
- **Style X fails → ONLY style X flips to Manrope.** Other passing styles keep Outfit. The flip is surgical, not wholesale.
- **If Manrope also fails the same test for a style → that style uses system font (Roboto/Noto Sans).** Safety valve of the safety valve.

**Timeline:** This audit runs whenever Outfit is actually bundled as a font resource (currently `Typography.kt` uses `FontFamily.Default`). Until the font asset is added, the question is moot — system font renders Czech correctly. The audit triggers on the PR that adds `outfit_*.ttf` to the resource directory.

---

## Part B: Round 11 Topics — Fine Detail Specs

---

### 1. Dialog Design

The codebase already has `ConfirmDialog`, `SingleChoiceDialog`, and `LoadingDialog` in `sutils/compose/`. This spec standardizes them and adds the missing variants.

#### 1.1 Dialog Taxonomy

| Type | Component | Use Case | Has Buttons | Dismissable |
|------|-----------|----------|-------------|-------------|
| Confirmation | `ConfirmDialog` | Delete trip, reset data | Yes (2) | Scrim tap = dismiss |
| Permission rationale | `PermissionRationaleDialog` | Pre-system permission prompt | Yes (2) | Scrim tap = dismiss |
| Input | `InputDialog` (NEW) | Rename activity, export filename | Yes (2) | Scrim tap = dismiss |
| Single choice | `SingleChoiceDialog` | Select activity type, format | Yes (2) | Scrim tap = dismiss |
| Loading | `LoadingDialog` | Import/export progress | No | NOT dismissable |
| Destructive confirmation | `DestructiveConfirmDialog` (NEW) | Delete all data | Yes (2) | Scrim tap = dismiss |
| Bottom sheet | `ModalBottomSheet` | Filters, multi-field input | Varies | Swipe down / scrim tap |

#### 1.2 Standard Dialog Spec

All modal dialogs use M3 `AlertDialog` with these Ridgeline overrides:

```kotlin
// Token reference — applied via AlertDialog defaults + shape override
object DialogTokens {
    val Shape = DialogShape                          // 28.dp RoundedCornerShape (Shape.kt)
    val TonalElevation = 6.dp                        // M3 default
    val ContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val IconContentColor = MaterialTheme.colorScheme.secondary
    val HeadlineStyle = MaterialTheme.typography.headlineSmall   // Outfit SemiBold 28sp
    val SupportingTextStyle = MaterialTheme.typography.bodyMedium // System 14sp
    val ActionTextStyle = MaterialTheme.typography.labelLarge     // System Medium 14sp

    // Internal padding (M3 defaults — do NOT override)
    // Title top: 24dp, Title-to-body: 16dp, Body bottom: 24dp
    // Button row: end-aligned, 8dp spacing, 24dp padding from edges

    val MinWidth = 280.dp
    val MaxWidth = 560.dp    // Constrain on tablets
    val MaxHeight = 0.65f    // Fraction of screen height
}
```

#### 1.3 Destructive Confirmation Dialog

For irreversible actions (delete all data, reset statistics):

```kotlin
@Composable
fun DestructiveConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    destructiveLabel: String,    // "Delete", "Reset"
    dismissLabel: String,        // "Cancel"
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    AlertDialog(
        modifier = modifier.testTag("destructive_confirm_dialog"),
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.testTag("destructive_confirm_action")
            ) {
                Text(destructiveLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        }
    )
}
```

**Key rule:** Destructive button uses `colorScheme.error` text. The dismiss (safe) button is default `primary`. No filled buttons in destructive dialogs — reduce accidental taps.

#### 1.4 Input Dialog

```kotlin
@Composable
fun InputDialog(
    visible: Boolean,
    title: String,
    initialValue: String = "",
    placeholder: String = "",
    label: String = "",
    validationRule: (String) -> String? = { null },  // Returns error message or null
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    val errorMessage = validationRule(text)

    AlertDialog(
        modifier = modifier.testTag("input_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = if (label.isNotEmpty()) {{ Text(label) }} else null,
                placeholder = if (placeholder.isNotEmpty()) {{ Text(placeholder) }} else null,
                isError = errorMessage != null,
                supportingText = errorMessage?.let {{ Text(it) }},
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_dialog_field"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (errorMessage == null && text.isNotBlank()) {
                            onConfirm(text); onDismiss()
                        }
                    }
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text); onDismiss() },
                enabled = errorMessage == null && text.isNotBlank(),
                modifier = Modifier.testTag("input_dialog_confirm")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}
```

#### 1.5 Bottom Sheet Dialog

For complex multi-field input (WiFi filter dialog, export settings):

```kotlin
object BottomSheetTokens {
    val Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val ContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val DragHandleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val DragHandleWidth = 32.dp
    val DragHandleHeight = 4.dp
    val ContentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    val ScrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
    val TonalElevation = 1.dp
    val MaxWidthTablet = 640.dp  // On tablets, center with max width
}
```

**When to use bottom sheet vs dialog:**
- **Dialog** → Single question, ≤ 3 inputs, needs focused attention
- **Bottom sheet** → ≥ 4 inputs, scrollable content, filter panels, secondary context that doesn't need full attention

#### 1.6 Backdrop (Scrim)

All dialogs and sheets use M3 scrim:
```kotlin
val ScrimAlpha = 0.32f  // M3 default
// Color: colorScheme.scrim (Color.Black) at ScrimAlpha
// Tap behavior: Dismisses dialog (except LoadingDialog)
// Animation: 150ms fade (AppMotion.DurationMicro)
```

---

### 2. Loading & Skeleton States

#### 2.1 Decision Matrix

| State | Component | When to Use |
|-------|-----------|-------------|
| **Spinner** | `CircularProgressIndicator` | Indeterminate wait, modal blocking (dialog, full-screen gate) |
| **Progress bar** | `LinearProgressIndicator` | Determinate progress (map layer loading, import/export) |
| **Placeholder rows** | `PlaceholderRow` | Paginated list append (scroll-triggered loading) |
| **Empty → Content** | `AnimatedVisibility` crossfade | Initial data load where layout is known |

**No shimmer.** Shimmer libraries add dependency weight and battery overhead for a cosmetic effect. The app's data is local — loads are fast (< 200ms typical). Shimmer is designed for network-latency UIs. We use:
- Spinner for genuinely slow operations (import/export of large files)
- Crossfade for sub-200ms local loads
- Placeholder rows only in paginated lists

#### 2.2 Full-Screen Loading Gate

Used at screen entry when data isn't ready yet (trip detail, stats summary):

```kotlin
@Composable
fun LoadingGate(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    loadingMessage: String? = null,
    content: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = isLoading,
        transitionSpec = {
            fadeIn(animationSpec = tween(AppMotion.DurationShort)) togetherWith
                fadeOut(animationSpec = tween(AppMotion.DurationMicro))
        },
        modifier = modifier,
        label = "LoadingGate"
    ) { loading ->
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (loadingMessage != null) {
                        Text(
                            text = loadingMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            content()
        }
    }
}
```

#### 2.3 Paginated List Placeholder

Already present in `StatsScreen.kt`. Standardized spec:

```kotlin
@Composable
fun PlaceholderRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Circle placeholder for icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        // Text lines placeholder
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
    }
}

// Usage in paginated list:
// Show 3 placeholder rows during append loading
items(3) { PlaceholderRow() }
```

#### 2.4 Animation Tokens for Loading

```kotlin
object LoadingMotion {
    // Spinner: Use M3 default animation (indeterminate spin)
    // Progress bar: Use M3 default animation (indeterminate sweep)

    // Gate crossfade
    val EnterDuration = AppMotion.DurationShort   // 250ms
    val ExitDuration = AppMotion.DurationMicro    // 150ms

    // Placeholder pulse (optional, for long loads > 2s)
    val PulseAlphaRange = 0.08f..0.16f
    val PulseDuration = 1200                       // ms, full cycle
}
```

---

### 3. Snackbar & Toast Design

#### 3.1 Snackbar-Only Policy

**All new feedback uses Snackbar. No new Toast usage.** Existing Toast calls in `GameScreen.kt`, settings, and debug screens are legacy — migrate to Snackbar on contact.

**Why:** Snackbar integrates with Scaffold, respects FAB position, supports actions, and is accessible (announced by TalkBack). Toast is fire-and-forget with no action affordance.

#### 3.2 Duration Tiers

| Tier | Duration | `SnackbarDuration` | When to Use |
|------|----------|-------------------|-------------|
| **Short** | 4s | `.Short` | Success confirmations ("Trip deleted", "Export complete") |
| **Long** | 10s | `.Long` | Actionable messages ("Permission denied" → "Settings") |
| **Indefinite** | Until dismissed | `.Indefinite` | Blocking conditions ("GPS signal lost" + "Retry") |

#### 3.3 Snackbar Spec

```kotlin
object SnackbarTokens {
    // M3 defaults — do not override
    val ContainerColor = MaterialTheme.colorScheme.inverseSurface
    val ContentColor = MaterialTheme.colorScheme.inverseOnSurface
    val ActionColor = MaterialTheme.colorScheme.inversePrimary
    val Shape = RoundedCornerShape(8.dp)                // M3 default

    // Ridgeline positioning rules
    val BottomPadding = 16.dp                           // Above nav bar
    val BottomPaddingWithFab = 72.dp                    // Above FAB (56dp + 16dp gap)
    val HorizontalPadding = 16.dp
    val MaxWidth = 560.dp                               // Tablets
}
```

#### 3.4 Interaction with FAB & Nav Bar

The existing `Scaffold` handles FAB-snackbar coordination automatically via `FabPosition.End`. The snackbar host sits in the scaffold, and M3 Scaffold offsets it above the FAB.

For the **map screen** (which uses `BottomSheetScaffold` instead of `Scaffold`), the custom positioning in `MapSheet.kt` is correct:

```kotlin
// MapSheet.kt already does this — KEEP as-is
SnackbarHost(
    hostState = snackbarHostState,
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = peekHeight + bottomInsetDp + 8.dp)
)
```

**Rules:**
- On screens with bottom nav bar: Snackbar sits 16dp above nav bar
- On screens with FAB: Scaffold handles offset automatically
- On map screen with bottom sheet: Manual padding above peek height (already implemented)
- Snackbar never overlaps any interactive element

#### 3.5 Dismiss Behavior

- **Swipe right to dismiss** — M3 default, always enabled
- **Timeout** — Per duration tier above
- **New snackbar replaces current** — Only one snackbar visible at a time. `SnackbarHostState` handles this (queue of 1)

#### 3.6 Ridgeline Snackbar Convenience

```kotlin
/**
 * Extension for common snackbar patterns.
 * Usage: snackbarHostState.showSuccess("Trip saved")
 *        snackbarHostState.showActionable("Permission denied", "Settings") { openSettings() }
 *        snackbarHostState.showError("Export failed", "Retry") { retryExport() }
 */
suspend fun SnackbarHostState.showSuccess(message: String) {
    showSnackbar(message = message, duration = SnackbarDuration.Short)
}

suspend fun SnackbarHostState.showActionable(
    message: String,
    actionLabel: String,
    onAction: () -> Unit
): SnackbarResult {
    val result = showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = SnackbarDuration.Long
    )
    if (result == SnackbarResult.ActionPerformed) onAction()
    return result
}

suspend fun SnackbarHostState.showError(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
): SnackbarResult {
    val result = showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
        withDismissAction = true
    )
    if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
    return result
}
```

---

### 4. Error States

#### 4.1 Error Taxonomy

This is a local-only app. "Errors" come from:
- **Permission denial** — Location, activity recognition
- **Sensor loss** — GPS signal drops, no accelerometer
- **Storage** — Disk full, database corruption
- **User error** — Invalid input in forms
- **Processing** — Import parse failure, export write failure

No network errors. No auth errors. No server errors.

#### 4.2 Inline Errors (Form Validation)

For `OutlinedTextField` inputs:

```kotlin
// Standard inline error pattern — already used in ExportScreen filename validation
OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    isError = errorMessage != null,
    supportingText = errorMessage?.let {
        {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    },
    // Error colors are automatic via M3 OutlinedTextField.isError
    // Border: colorScheme.error
    // Label: colorScheme.error
    // Supporting text: colorScheme.error
)
```

**Rule:** Validate on every keystroke for length/format rules. Validate on focus loss for complex rules. Show error in `supportingText` slot, never in a separate composable.

#### 4.3 Contextual Error Banner

For non-blocking recoverable errors within a screen (GPS signal lost, sensor unavailable):

```kotlin
@Composable
fun ErrorBanner(
    message: String,
    icon: ImageVector = Icons.Outlined.ErrorOutline,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = true,  // Caller controls visibility with state
        enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium))
            + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (action != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text(action, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
```

**Usage:** GPS signal lost on map screen, Bluetooth unavailable for activity recognition.

#### 4.4 Full-Page Error

For when an entire screen's content cannot be loaded (database read failure, corrupted session):

```kotlin
@Composable
fun FullPageError(
    icon: ImageVector = Icons.Outlined.ErrorOutline,
    title: String,
    subtitle: String,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Reuse EmptyStateCard pattern with error theming
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (retryLabel != null && onRetry != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(retryLabel)
                }
            }
        }
    }
}
```

#### 4.5 Retry Pattern

```kotlin
// Standard retry strategy for recoverable operations
object RetryPolicy {
    const val MaxAttempts = 3
    val InitialDelay = 500L          // ms
    val BackoffMultiplier = 2.0
    val MaxDelay = 5000L             // ms

    // UI: Show "Retry" button after first failure.
    // After MaxAttempts: Show "Something went wrong" with manual retry.
    // Never auto-retry GPS/sensor — user must tap retry or re-grant permission.
}
```

#### 4.6 Error Display Decision Matrix

| Error Type | UI Treatment | Component | Duration |
|-----------|-------------|-----------|----------|
| Form validation | Inline `supportingText` | `OutlinedTextField.isError` | Persistent until fixed |
| Permission denied | Snackbar with "Settings" action | `PermissionDeniedSnackbar` | `Long` (10s) |
| GPS signal lost (map) | Banner at top of map | `ErrorBanner` | Until signal returns |
| GPS signal lost (tracking) | Notification update | System notification | Persistent |
| Import parse failure | Snackbar with detail | `showError()` | `Long` |
| Export write failure | Dialog | `ConfirmDialog` (info mode) | Until dismissed |
| Database corruption | Full-page error + retry | `FullPageError` | Persistent |
| Disk full | Dialog | `DestructiveConfirmDialog`-style | Until dismissed |

---

### 5. Search UI

The app has one search surface: the **map geocoding search** in `MapSheet.kt`. The statistics module uses filter dialogs, not free-text search.

#### 5.1 Current Implementation (Keep)

The existing `MapSheet.kt` search is a `TextField` inside a `Surface` with rounded corners, integrated into the bottom sheet header. This is correct for the use case — searching for a location on the map.

#### 5.2 Standardized Search Bar Component

For future use (session search in statistics, place search):

```kotlin
@Composable
fun RidgelineSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    },
    active: Boolean = false,
    onActiveChange: (Boolean) -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),            // Full pill when inactive
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (active) 3.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(12.dp))
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onActiveChange(it.isFocused) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
```

#### 5.3 Search Results

```kotlin
// Empty results state
@Composable
fun SearchEmptyResult(
    query: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Outlined.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = "No results for \"$query\"",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Try a different search term",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
```

#### 5.4 Search Specs

| Property | Value |
|----------|-------|
| Bar height | 56dp |
| Corner radius | 28dp (full pill) |
| Container | `surfaceContainerHigh` |
| Text style | `bodyLarge` |
| Placeholder color | `onSurfaceVariant` |
| Clear button | `Icons.Filled.Close`, 48dp touch target |
| Keyboard | `ImeAction.Search`, submit on enter |
| Minimum query length | 1 character (no debounce for local search) |
| Result item height | ≥ 56dp (touch target compliance) |

---

### 6. Color-Blind Accessibility for Activity Types

#### 6.1 Current Activity Colors

From `DesignSystem.kt`:
```kotlin
val ActivityWalk = Color(0xFF00E5FF)  // Cyan
val ActivityRun  = Color(0xFFFF9100)  // Orange
val ActivityRide = Color(0xFF2979FF)  // Blue
```

Vehicle/Still/Unknown don't have dedicated colors — they use `onSurface` default.

#### 6.2 The Problem

| Pair | Protanopia | Deuteranopia | Tritanopia |
|------|-----------|--------------|------------|
| Walk (Cyan) vs Ride (Blue) | Distinguishable ✅ | Distinguishable ✅ | **May merge** ⚠️ |
| Walk (Cyan) vs Run (Orange) | Distinguishable ✅ | Distinguishable ✅ | Distinguishable ✅ |
| Run (Orange) vs Ride (Blue) | Distinguishable ✅ | Distinguishable ✅ | Distinguishable ✅ |

Cyan vs Blue under tritanopia is the only problematic pair. For protanopia/deuteranopia (the most common forms, ~8% of males), the current palette is safe.

#### 6.3 Triple-Encoding Strategy: Color + Icon + Shape

Color alone must NEVER be the sole differentiator. Every activity type gets all three:

```kotlin
/**
 * Activity display configuration — color, icon, and shape token per activity type.
 * All three channels must be used together wherever activities are visually compared.
 */
sealed class ActivityVisual(
    val color: Color,                   // Primary channel
    val icon: ImageVector,              // Secondary channel (always visible)
    val shape: Shape,                   // Tertiary channel (chips, map markers, legend)
    val contentDescription: String,     // Accessibility label
) {
    data object Walk : ActivityVisual(
        color = AppColors.ActivityWalk,                      // Cyan
        icon = Icons.Outlined.DirectionsWalk,                // Person walking
        shape = CircleShape,                                 // ● Circle
        contentDescription = "Walking"
    )
    data object Run : ActivityVisual(
        color = AppColors.ActivityRun,                       // Orange
        icon = Icons.Outlined.DirectionsRun,                 // Person running
        shape = CutCornerShape(topEnd = 6.dp),               // ◆ Diamond-ish
        contentDescription = "Running"
    )
    data object Ride : ActivityVisual(
        color = AppColors.ActivityRide,                      // Blue
        icon = Icons.Outlined.DirectionsBike,                // Bicycle
        shape = RoundedCornerShape(4.dp),                    // ■ Rounded square
        contentDescription = "Cycling"
    )
    data object Vehicle : ActivityVisual(
        color = Color(0xFFAB47BC),                           // Purple (NEW)
        icon = Icons.Outlined.DirectionsCar,                 // Car
        shape = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50,
            bottomStartPercent = 10, bottomEndPercent = 10), // ▲ Shield-ish
        contentDescription = "Vehicle"
    )
    data object Still : ActivityVisual(
        color = Color(0xFF78909C),                           // Blue-grey
        icon = Icons.Outlined.LocationOn,                    // Pin
        shape = CircleShape,                                 // ● Circle (same as walk but muted color)
        contentDescription = "Stationary"
    )
    data object Unknown : ActivityVisual(
        color = Color(0xFF9E9E9E),                           // Grey
        icon = Icons.Outlined.HelpOutline,                   // Question mark
        shape = CircleShape,
        contentDescription = "Unknown activity"
    )

    companion object {
        fun fromGroupedActivity(grouped: GroupedActivity): ActivityVisual = when (grouped) {
            GroupedActivity.STILL -> Still
            GroupedActivity.ON_FOOT -> Walk   // Default for foot; check NativeSessionActivity for Run
            GroupedActivity.IN_VEHICLE -> Vehicle
            GroupedActivity.UNKNOWN -> Unknown
        }

        fun fromNativeActivity(activity: NativeSessionActivity): ActivityVisual = when (activity) {
            NativeSessionActivity.WALKING -> Walk
            NativeSessionActivity.RUNNING -> Run
            NativeSessionActivity.BICYCLE -> Ride
            NativeSessionActivity.VEHICLE -> Vehicle
            // ... other native activities map to Vehicle/Unknown
            else -> Unknown
        }
    }
}
```

#### 6.4 Activity Legend Chip

```kotlin
@Composable
fun ActivityChip(
    visual: ActivityVisual,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Surface(
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            visual.color.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (selected) {
            BorderStroke(1.5.dp, visual.color)
        } else null,
        contentColor = if (selected) {
            visual.color
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Shape indicator (4th redundancy channel)
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(visual.shape)
                    .background(visual.color)
            )
            Icon(
                imageVector = visual.icon,
                contentDescription = visual.contentDescription,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = visual.contentDescription,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}
```

#### 6.5 Updated Color Palette with Tritanopia Fix

The original 3 colors are kept but Vehicle gets a new dedicated color:

```kotlin
object AppColors {
    // Activity colors — chosen for maximum separation across all CVD types
    val ActivityWalk    = Color(0xFF00E5FF)  // Cyan       — unique hue, safe across all CVD
    val ActivityRun     = Color(0xFFFF9100)  // Orange     — warm, high luminance contrast
    val ActivityRide    = Color(0xFF2979FF)  // Blue       — cool, distinct from cyan in P/D
    val ActivityVehicle = Color(0xFFAB47BC)  // Purple     — distinct from all above in all CVD
    val ActivityStill   = Color(0xFF78909C)  // Blue-grey  — desaturated, clearly "inactive"
    val ActivityUnknown = Color(0xFF9E9E9E)  // Grey       — neutral
}
```

**CVD verification checklist (run via Daltonize or Colour Blindness Simulator):**
- Protanopia: Cyan ≠ Orange ≠ Blue ≠ Purple ✅ (all distinct luminances)
- Deuteranopia: Cyan ≠ Orange ≠ Blue ≠ Purple ✅ (blue/purple distinguish by lightness)
- Tritanopia: Cyan → pinkish, Blue → greenish (NOW DISTINGUISHABLE) ✅, Purple → desaturated blue (but icon+shape differentiate) ✅

**Rule:** Never use color alone as the only differentiator in any chart, legend, map layer, or badge. Always pair with icon and/or shape.

#### 6.6 Map Polyline Accessibility

On the map, activity type colors polylines. Since polylines can't carry icons:

```kotlin
// Map polyline differentiation strategy:
// 1. Color (primary channel)
// 2. Dash pattern (secondary channel — shape analog for lines)
object ActivityPolylineStyle {
    // Walk: solid line (continuous motion)
    val WalkDashPattern: FloatArray? = null  // Solid

    // Run: tight dash (fast cadence)
    val RunDashPattern = floatArrayOf(8f, 4f)

    // Ride: long dash (smooth ride)
    val RideDashPattern = floatArrayOf(16f, 4f)

    // Vehicle: dash-dot (mechanical)
    val VehicleDashPattern = floatArrayOf(12f, 4f, 4f, 4f)

    val StrokeWidth = 4f  // dp equivalent in MapLibre
}
// Combined with color, this gives 2-channel encoding for polylines.
// Users can also tap a polyline to see a tooltip with the activity icon + name.
```

---

## Part C: Summary of Decisions & Open Items

### Binding Decisions This Round

| # | Decision | Status |
|---|----------|--------|
| Font | Outfit is default. Czech diacritic audit protocol defined. Triggers on font asset PR. | ✅ Closed pending audit |
| Dialogs | 6 dialog types standardized. `DestructiveConfirmDialog` + `InputDialog` are new. | ✅ Specced |
| Loading | No shimmer. Spinner + progress bar + placeholder rows + crossfade. `LoadingGate` is new. | ✅ Specced |
| Snackbar | Snackbar-only policy. 3 duration tiers. Convenience extensions. No new Toast. | ✅ Specced |
| Errors | 4-tier system: inline → banner → snackbar → full-page. `ErrorBanner` + `FullPageError` are new. | ✅ Specced |
| Search | Pill search bar, 56dp. `RidgelineSearchBar` for future use. Map search stays as-is. | ✅ Specced |
| Color-blind | Triple-encoding (color + icon + shape). `ActivityVisual` sealed class. Polyline dash patterns. Vehicle gets `#AB47BC`. | ✅ Specced |

### New Components Introduced

| Component | File (proposed) | Module |
|-----------|----------------|--------|
| `DestructiveConfirmDialog` | `sutils/compose/DestructiveConfirmDialog.kt` | sutils |
| `InputDialog` | `sutils/compose/InputDialog.kt` | sutils |
| `LoadingGate` | `sutils/style/compose/LoadingGate.kt` | sutils |
| `PlaceholderRow` | `sutils/style/compose/PlaceholderRow.kt` | sutils |
| `ErrorBanner` | `sutils/style/compose/ErrorBanner.kt` | sutils |
| `FullPageError` | `sutils/style/compose/FullPageError.kt` | sutils |
| `RidgelineSearchBar` | `sutils/style/compose/RidgelineSearchBar.kt` | sutils |
| `SearchEmptyResult` | `sutils/style/compose/SearchEmptyResult.kt` | sutils |
| `ActivityVisual` | `sbase/data/ActivityVisual.kt` | sbase |
| `ActivityChip` | `sutils/style/compose/ActivityChip.kt` | sutils |

### Suggested Round 12 Topics

1. **Navigation patterns** — Bottom nav bar specs, back stack behavior, deep links, transition animations between screens
2. **Settings screen design** — Preference list layout, toggle/switch specs, grouped sections, settings search
3. **Onboarding flow** — Permission request sequence, first-launch walkthrough, progressive disclosure
4. **Map control panel** — Layer switcher, heatmap toggle, follow-mode button, zoom controls layout
5. **Notification design** — Tracking notification layout, channel configuration, heads-up behavior
6. **Micro-interactions** — Button press feedback, toggle animations, list item swipe-to-delete, long-press reveals
