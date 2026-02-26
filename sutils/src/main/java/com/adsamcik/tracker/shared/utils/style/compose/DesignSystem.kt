package com.adsamcik.tracker.shared.utils.style.compose

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- DIMENSIONS ---

object AppDimensions {
    /** Bottom padding to clear the floating navigation bar. */
    val FloatingNavBarClearance = 120.dp
}

/** Ridgeline spacing scale. 4dp base, semantic token names. */
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

/** Responsive page gutters by window width. */
object RidgelineGutters {
    val horizontal: Dp
        @Composable get() {
            val config = LocalConfiguration.current
            return when {
                config.screenWidthDp < 600 -> RidgelineSpacing.Lg
                config.screenWidthDp < 840 -> RidgelineSpacing.Xxl
                else -> RidgelineSpacing.Xxxl
            }
        }
}

// --- ELEVATION ---

data class ElevationPair(val tonal: Dp, val shadow: Dp)

object RidgelineElevation {
    val Flat = ElevationPair(tonal = 0.dp, shadow = 0.dp)
    val Raised = ElevationPair(tonal = 1.dp, shadow = 1.dp)
    val Floating = ElevationPair(tonal = 2.dp, shadow = 2.dp)
    val Overlay = ElevationPair(tonal = 6.dp, shadow = 6.dp)
}

// --- GLASS SYSTEM ---

/**
 * Glass-morphism tiers. Higher tiers = more blur + more transparency.
 * Pre-API 31: blur unavailable, falls back to opaque surface + border.
 *
 * Blur is applied by modules that depend on Haze (e.g., app module).
 * This enum provides the token values; actual blur rendering is done
 * at the call site via Haze's hazeEffect modifier.
 */
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

/** Resolves the border color for a glass tier based on current theme. */
@Composable
fun GlassTier.borderColor(): Color {
    val alpha = if (isSystemInDarkTheme()) darkBorderAlpha else lightBorderAlpha
    return MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)
}

/** Resolves the tint color for a glass tier based on current theme. */
@Composable
fun GlassTier.tintColor(): Color {
    val alpha = if (isSystemInDarkTheme()) darkTintAlpha else lightTintAlpha
    return MaterialTheme.colorScheme.surfaceContainer.copy(alpha = alpha)
}

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

// --- ACTIVITY COLORS (Okabe-Ito, independent of brand seed) ---

object ActivityColors {
    val WalkLight = Color(0xFF007051)
    val RunLight = Color(0xFFA34800)
    val RideLight = Color(0xFF00659E)
    val VehicleLight = Color(0xFF97396D)
    val StillLight = Color(0xFF546E7A)
    val UnknownLight = Color(0xFF616161)

    val WalkDark = Color(0xFF52C5A6)
    val RunDark = Color(0xFFEF8C3D)
    val RideDark = Color(0xFF5AADDC)
    val VehicleDark = Color(0xFFD490B6)
    val StillDark = Color(0xFF90A4AE)
    val UnknownDark = Color(0xFF9E9E9E)

    val OnLight = Color(0xFFFFFFFF)
    val OnWalkDark = Color(0xFF002418)
    val OnRunDark = Color(0xFF2E1500)
    val OnRideDark = Color(0xFF001D2E)
    val OnVehicleDark = Color(0xFF2A0A1E)
    val OnStillDark = Color(0xFF0C1F28)
    val OnUnknownDark = Color(0xFF1A1A1A)

    object Adaptive {
        val Walk: Color @Composable get() = if (isSystemInDarkTheme()) WalkDark else WalkLight
        val Run: Color @Composable get() = if (isSystemInDarkTheme()) RunDark else RunLight
        val Ride: Color @Composable get() = if (isSystemInDarkTheme()) RideDark else RideLight
        val Vehicle: Color @Composable get() = if (isSystemInDarkTheme()) VehicleDark else VehicleLight
        val Still: Color @Composable get() = if (isSystemInDarkTheme()) StillDark else StillLight
        val Unknown: Color @Composable get() = if (isSystemInDarkTheme()) UnknownDark else UnknownLight
    }
}

// --- COMPONENTS ---

/**
 * Ridgeline card defaults for use with Material3 [Card] composables.
 * Use this when you need a [Card] (e.g., for Column content) instead of [GlassCard] (Box content).
 */
object RidgelineCardDefaults {
    /** Standard card container color — `surfaceContainerHigh` for visible card identity. */
    val containerColor @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** Standard card content color. */
    val contentColor @Composable get() = MaterialTheme.colorScheme.onSurface

    /** Standard card shape — L4 diagonal asymmetry (20/6dp). */
    val shape @Composable get() = MaterialTheme.shapes.large
}

/**
 * Ridgeline glass-morphism card.
 *
 * Default shape is [MaterialTheme.shapes.large] (L4: 20/6dp diagonal asymmetry)
 * which gives the pronounced "ridgeline" visual signature.
 * Uses `surfaceContainerHigh` for visible card contrast against the background.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    tier: GlassTier = GlassTier.G2,
    content: @Composable BoxScope.() -> Unit,
) {
    val borderColor = tier.borderColor()

    Surface(
        modifier = modifier.then(
            if (tier.lightBorderAlpha > 0f || tier.darkBorderAlpha > 0f) {
                Modifier.border(BorderStroke(1.dp, borderColor), shape)
            } else Modifier
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        tonalElevation = RidgelineElevation.Raised.tonal,
        shadowElevation = RidgelineElevation.Raised.shadow,
    ) {
        Box(
            modifier = Modifier.padding(RidgelineSpacing.Lg),
            content = content,
        )
    }
}

/**
 * Large metric text for visibility while moving.
 * Uses monospace (Roboto Mono) for tabular digit stability.
 */
@Composable
fun MetricText(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueSize: TextUnit = 48.sp,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = valueSize,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
            color = valueColor,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.5.sp,
            ),
            color = labelColor,
        )
    }
}

/**
 * Primary action button styled with MomentumPillShape.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MomentumPillShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RidgelineSpacing.Xxl, vertical = RidgelineSpacing.Lg),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                if (icon != null) {
                    icon()
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(end = RidgelineSpacing.Sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                )
            }
        }
    }
}
