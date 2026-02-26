package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.isSystemInDarkTheme

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

// --- COLORS ---
object AppColors {
    val NeonLime = Color(0xFFCCFF00)
    val DeepVoid = Color(0xFF0A0A0A)
    // Preserving these for now as constants if needed, but components will use Scheme
    val GlassShale = Color(0x331A1A1A)
    val WhiteHighEmphasis = Color(0xFFFFFFFF)
    val WhiteMediumEmphasis = Color(0xB3FFFFFF)
    val WhiteLowEmphasis = Color(0x66FFFFFF)
    
    // Activity colors — light mode (Okabe-Ito hues, FINAL Round 13)
    val ActivityWalkLight = Color(0xFF007051)
    val ActivityRunLight = Color(0xFFA34800)
    val ActivityRideLight = Color(0xFF00659E)
    val ActivityVehicleLight = Color(0xFF97396D)
    val ActivityStillLight = Color(0xFF546E7A)
    val ActivityUnknownLight = Color(0xFF616161)

    // Activity colors — dark mode (Okabe-Ito hues, FINAL Round 13)
    val ActivityWalkDark = Color(0xFF52C5A6)
    val ActivityRunDark = Color(0xFFEF8C3D)
    val ActivityRideDark = Color(0xFF5AADDC)
    val ActivityVehicleDark = Color(0xFFD490B6)
    val ActivityStillDark = Color(0xFF90A4AE)
    val ActivityUnknownDark = Color(0xFF9E9E9E)

    // On-colors for activity chip/badge fills
    val OnActivityLight = Color(0xFFFFFFFF)
    val OnActivityWalkDark = Color(0xFF002418)
    val OnActivityRunDark = Color(0xFF2E1500)
    val OnActivityRideDark = Color(0xFF001D2E)
    val OnActivityVehicleDark = Color(0xFF2A0A1E)
    val OnActivityStillDark = Color(0xFF0C1F28)
    val OnActivityUnknownDark = Color(0xFF1A1A1A)
    
    // Gradients
    val MainGradient = Brush.verticalGradient(
        colors = listOf(DeepVoid, Color(0xFF121212))
    )

    /** Resolves mode-adaptive activity colors. Call from @Composable context. */
    object Adaptive {
        val ActivityWalk: Color @Composable get() = if (isSystemInDarkTheme()) ActivityWalkDark else ActivityWalkLight
        val ActivityRun: Color @Composable get() = if (isSystemInDarkTheme()) ActivityRunDark else ActivityRunLight
        val ActivityRide: Color @Composable get() = if (isSystemInDarkTheme()) ActivityRideDark else ActivityRideLight
        val ActivityVehicle: Color @Composable get() = if (isSystemInDarkTheme()) ActivityVehicleDark else ActivityVehicleLight
        val ActivityStill: Color @Composable get() = if (isSystemInDarkTheme()) ActivityStillDark else ActivityStillLight
        val ActivityUnknown: Color @Composable get() = if (isSystemInDarkTheme()) ActivityUnknownDark else ActivityUnknownLight
    }
}

// --- SHAPES ---
// Moved to Shape.kt

// --- COMPONENTS ---

/**
 * A glass-morphism card with a subtle border and blurred background feel (simulated).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = TerrainCardShape,
    showBorder: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .then(
                if (showBorder) {
                    Modifier.border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        shape
                    )
                } else {
                    Modifier
                }
            ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * Large metric text for visibility while moving.
 */
@Composable
fun MetricText(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueSize: TextUnit = 48.sp
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = valueSize,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum" // Tabular numbers
            ),
            color = valueColor
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.5.sp
            ),
            color = labelColor
        )
    }
}

/**
 * Primary action button styled for the outdoor theme.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MomentumPillShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                if (icon != null) {
                    icon()
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(end = 8.dp))
                }
                Text(
                    text = text.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
            }
        }
    }
}
