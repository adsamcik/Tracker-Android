package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.R

/**
 * Expressive Tracking Button
 *
 * A redesigned tracking control with Material 3 Expressive styling:
 * - Morphing shape: Rounded square (idle) → Circle (tracking)
 * - Animated ring progress indicator when tracking
 * - Soft glow effect during active tracking
 * - Smooth icon transitions with scale animations
 * - Clear state distinction through color and shape
 */
@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TrackingFAB(
    isTracking: Boolean,
    hasPermission: Boolean,
    onToggleTracking: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tracking_button")
    
    // Morphing corner radius: More rounded when tracking
    val cornerRadius by animateFloatAsState(
        targetValue = if (isTracking) 50f else 28f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "corner_radius"
    )
    
    // Subtle pulse when tracking
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTracking) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // Glow ring animation - rotating gradient effect when tracking
    val glowRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_rotation"
    )
    
    // Alpha for glow effect
    val glowAlpha by animateFloatAsState(
        targetValue = if (isTracking) 0.6f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "glow_alpha"
    )
    
    // Icon scale for press feedback
    val iconScale by animateFloatAsState(
        targetValue = if (isTracking) 0.9f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "icon_scale"
    )
    
    val buttonSize = 96.dp
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    val fabStartDesc = stringResource(R.string.description_tracking_start)
    val fabStopDesc = stringResource(R.string.description_tracking_stop)
    val fabNoPermissionDesc = stringResource(R.string.description_tracking_start_no_permission)
    
    val containerColor = when {
        !hasPermission -> surfaceVariant
        isTracking -> errorColor
        else -> primaryColor
    }
    
    val contentColor = when {
        !hasPermission -> MaterialTheme.colorScheme.onSurfaceVariant
        isTracking -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onPrimary
    }
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(buttonSize + 28.dp) // Extra space for glow + stroke
    ) {
        // Outer glow ring when tracking
        if (isTracking) {
            Canvas(
                modifier = Modifier
                    .size(buttonSize + 20.dp)
                    .alpha(glowAlpha)
                    .graphicsLayer { rotationZ = glowRotation }
            ) {
                val sweepGradient = Brush.sweepGradient(
                    0f to errorColor.copy(alpha = 0.8f),
                    0.25f to errorColor.copy(alpha = 0.2f),
                    0.5f to errorColor.copy(alpha = 0.8f),
                    0.75f to errorColor.copy(alpha = 0.2f),
                    1f to errorColor.copy(alpha = 0.8f)
                )
                
                drawCircle(
                    brush = sweepGradient,
                    radius = size.minDimension / 2,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                )
            }
        }
        
        // Main button with morphing shape
        Surface(
            onClick = {
                if (!hasPermission) onRequestPermission() else onToggleTracking()
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius.dp),
            color = containerColor,
            shadowElevation = if (isTracking) 8.dp else 6.dp,
            tonalElevation = if (isTracking) 4.dp else 2.dp,
            modifier = Modifier
                .size(buttonSize)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .testTag("tracking_fab")
                .semantics {
                    contentDescription = when {
                        !hasPermission -> fabNoPermissionDesc
                        isTracking -> fabStopDesc
                        else -> fabStartDesc
                    }
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                // Icon with animated transitions
                val icon: ImageVector = when {
                    !hasPermission -> Icons.Default.LocationOff
                    isTracking -> Icons.Default.Stop
                    else -> Icons.Default.PlayArrow
                }
                val description = when {
                    !hasPermission -> stringResource(R.string.description_tracking_start)
                    isTracking -> stringResource(R.string.description_tracking_stop)
                    else -> stringResource(R.string.description_tracking_start)
                }
                
                AnimatedContent(
                    targetState = icon,
                    label = "fab_icon",
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(300)) + 
                            androidx.compose.animation.scaleIn(initialScale = 0.8f, animationSpec = tween(300)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(200)) +
                                    androidx.compose.animation.scaleOut(targetScale = 0.8f, animationSpec = tween(200))
                            )
                    }
                ) { targetIcon ->
                    Icon(
                        imageVector = targetIcon,
                        contentDescription = description,
                        tint = contentColor,
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    )
                }
            }
        }
    }
}

@Composable
internal fun LockBanner(onClick: () -> Unit) {
    val context = LocalContext.current
    // Card(onClick = ...) provides proper Role.Button semantics + ripple; raw Modifier.clickable
    // on an inner Row would be announced as non-interactive to TalkBack.
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .testTag("lock_banner")
            .semantics { contentDescription = context.getString(R.string.description_recharge_settings) }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.tracker_lock_icon_desc), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = stringResource(R.string.settings_disabled_recharge_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.tracker_settings_icon_desc), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// region Phase 4a: PolicyTierChip

/**
 * Compact chip displaying and toggling the tracking precision mode in the top bar.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PolicyTierChip(
    tier: PolicyTier,
    precisionModePreset: TrackingPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isBatterySaver = precisionModePreset == TrackingPreset.POWER_SAVE
    val label = stringResource(
        if (isBatterySaver) {
            R.string.tracking_preset_battery_saver_title
        } else {
            R.string.tracking_preset_high_precision_title
        }
    )
    val nextLabel = stringResource(
        if (isBatterySaver) {
            R.string.tracking_preset_high_precision_title
        } else {
            R.string.tracking_preset_battery_saver_title
        }
    )
    val policyLabel = when (tier) {
        PolicyTier.AMBIENT -> stringResource(R.string.policy_tier_ambient)
        PolicyTier.ACTIVE -> stringResource(R.string.policy_tier_active)
        PolicyTier.PRECISION -> stringResource(R.string.policy_tier_precision)
        PolicyTier.OFF -> stringResource(R.string.policy_tier_ambient)
    }
    val hintText = stringResource(R.string.tracker_precision_chip_hint, nextLabel)
    val containerColor = when {
        isBatterySaver -> MaterialTheme.colorScheme.surfaceVariant
        tier == PolicyTier.PRECISION -> MaterialTheme.colorScheme.secondaryContainer
        tier == PolicyTier.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when {
        isBatterySaver -> MaterialTheme.colorScheme.onSurfaceVariant
        tier == PolicyTier.PRECISION -> MaterialTheme.colorScheme.onSecondaryContainer
        tier == PolicyTier.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        modifier = modifier
            .padding(end = 4.dp)
            .semantics {
                contentDescription = "$label. ${context.getString(R.string.policy_tier_status_prefix)} $policyLabel. $hintText"
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isBatterySaver) Icons.Default.LocationOff else Icons.Default.LocationSearching,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}

// endregion
