package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

// --- DIMENSIONS ---
object AppDimensions {
    /** Bottom padding to clear the floating navigation bar. */
    val FloatingNavBarClearance = 120.dp
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
    
    val ActivityWalk = Color(0xFF00E5FF)
    val ActivityRun = Color(0xFFFF9100)
    val ActivityRide = Color(0xFF2979FF)
    
    // Gradients
    val MainGradient = Brush.verticalGradient(
        colors = listOf(DeepVoid, Color(0xFF121212))
    )

    // Celebration / Gamification (brand-locked)
    val CelebrationGlow = Color(0xFFE6FF41)
    val ChallengeVictory = Color(0xFFFFAB00)
    val StreakFlame = Color(0xFFFF6D00)
    val ExplorationSpark = Color(0xFF00E676)
    val MilestoneAurora = Color(0xFF7C4DFF)
}

// --- SHAPES ---
object AppShapes {
    // Legacy aliases — prefer MaterialTheme.shapes tokens in new code
    val GlassCard = RoundedCornerShape(24.dp)
    val Button = RoundedCornerShape(16.dp)

    // Special shapes outside the M3 scale
    val FloatingNavBar = RoundedCornerShape(32.dp)
    val TrackingFabIdle = RoundedCornerShape(28.dp)
    val TrackingFabActive = RoundedCornerShape(48.dp)
}

/**
 * M3 Expressive shape scale for the app.
 * Maps component importance to corner radius: utility (4dp) → brand hero (28dp).
 */
val TrackerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Expressive typography scale: "Confident Outdoors".
 * Black/Bold display for metrics, SemiBold headers, Bold action labels.
 * System fonts — personality comes from weight contrast (400→900), not font family.
 */
val TrackerTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Black, letterSpacing = (-0.25).sp, lineHeight = 64.sp, fontSize = 57.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 0.sp, lineHeight = 52.sp, fontSize = 45.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 0.sp, lineHeight = 44.sp, fontSize = 36.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, lineHeight = 40.sp, fontSize = 32.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, lineHeight = 36.sp, fontSize = 28.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Medium, letterSpacing = 0.sp, lineHeight = 32.sp, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, lineHeight = 28.sp, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp, lineHeight = 24.sp, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp, lineHeight = 20.sp, fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, lineHeight = 20.sp, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, lineHeight = 16.sp, fontSize = 12.sp),
)

// --- COMPONENTS ---

/**
 * A glass-morphism card with a subtle border and blurred background feel (simulated).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.GlassCard,
    showBorder: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .then(
                if (showBorder) {
                    Modifier.border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        shape
                    )
                } else {
                    Modifier
                }
            ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape
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
        shape = AppShapes.Button,
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
