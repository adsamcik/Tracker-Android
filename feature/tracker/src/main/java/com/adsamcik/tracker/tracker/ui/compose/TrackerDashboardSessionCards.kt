package com.adsamcik.tracker.tracker.ui.compose

import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatTrackedSteps
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.tracker.R
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SessionOverviewCard(
    session: TrackerSession,
    isTracking: Boolean,
    pathPoints: List<com.adsamcik.tracker.shared.base.data.Location>?,
    onMapClick: () -> Unit,
    onDetailClick: ((Long) -> Unit)? = null,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val resources = context.resources
    val settings = TrackerSettingsQuick.snapshot(context)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val sessionEnd = when {
        session.end > session.start -> session.end
        isTracking -> Time.nowMillis
        else -> session.start
    }
    val durationMillis = (sessionEnd - session.start).coerceAtLeast(0L)
    val durationText = durationMillis.formatAsDuration(context)

    val distanceText = resources.formatDistance(
        session.distanceInM,
        digits = if (session.distanceInM >= 1000f) 1 else 2,
        unit = settings.lengthSystem
    )
    val stepCounterSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
    val stepsText = session.steps.formatTrackedSteps(stepCounterSupported)
    val updatesText = context.getString(R.string.collection_count_value, session.collections)
    val sessionAge = DateUtils.getRelativeTimeSpanString(
        session.start,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

    val generateSummary = remember(session, settings) {
        {
            buildString {
                appendLine("Session Summary")
                appendLine("Duration: $durationText")
                appendLine("Distance: $distanceText (${session.distanceInM}m)")
                appendLine("Steps: $stepsText")
                appendLine("Updates: ${session.collections}")
                appendLine("Started: $sessionAge")
                if (session.distanceOnFootInM > 0) {
                    appendLine("On foot: ${resources.formatDistance(session.distanceOnFootInM, 1, settings.lengthSystem)}")
                }
                if (session.distanceInVehicleInM > 0) {
                    appendLine("In vehicle: ${resources.formatDistance(session.distanceInVehicleInM, 1, settings.lengthSystem)}")
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onDetailClick?.invoke(session.id) },
                onLongClick = {
                    val sessionSummary = generateSummary()
                    copyToClipboard(context, haptics, "Session", sessionSummary)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = context.getString(R.string.tracker_session_summary_copied),
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            )
            .semantics {
                contentDescription = "Session overview: $durationText duration, $distanceText distance"
                onClick(label = context.getString(R.string.tracker_copy_session_summary)) {
                    true
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.tracker_session_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Spacer(Modifier.weight(1f))
                
                IconButton(
                    onClick = {
                        val summary = generateSummary()
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, summary)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, null)
                        context.startActivity(shareIntent)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.tracker_share_session_summary),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                Spacer(Modifier.size(12.dp))

                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.tracker_long_press_to_copy),
                    modifier = Modifier
                        .size(14.dp)
                        .alpha(0.5f),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (isTracking) {
                    Badge(containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)) {
                        Text(
                            text = stringResource(R.string.notification_tracking_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = durationText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(R.string.duration_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionMetric(
                    label = stringResource(R.string.tracker_distance_title),
                    value = distanceText,
                    modifier = Modifier.weight(1f)
                )
                SessionMetric(
                    label = stringResource(R.string.tracker_steps_title),
                    value = stepsText,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionMetric(
                    label = stringResource(R.string.tracker_collections_title),
                    value = updatesText,
                    modifier = Modifier.weight(1f)
                )
                SessionMetric(
                    label = stringResource(R.string.tracker_session_age),
                    value = sessionAge,
                    modifier = Modifier.weight(1f)
                )
            }
            
            if (pathPoints != null && pathPoints.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onMapClick() }
                ) {
                    SessionPathPreview(
                        points = pathPoints,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f))
                    )
                    
                    // Overlay hint
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tracker_view_map),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
internal fun SessionPathPreview(
    points: List<com.adsamcik.tracker.shared.base.data.Location>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.tertiary
    val endColor = MaterialTheme.colorScheme.error
    val pathDescription = "Session route preview with ${points.size} points"
    
    val pathProgress = remember { Animatable(0f) }
    
    LaunchedEffect(points) {
        pathProgress.snapTo(0f)
        pathProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 800,
                easing = FastOutSlowInEasing
            )
        )
    }
    
    androidx.compose.foundation.Canvas(
        modifier = modifier.semantics { 
            contentDescription = pathDescription
        }
    ) {
        if (points.size < 2) return@Canvas

        var minLat = Double.MAX_VALUE
        var maxLat = Double.MIN_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = Double.MIN_VALUE

        points.forEach { p ->
            minLat = minOf(minLat, p.latitude)
            maxLat = maxOf(maxLat, p.latitude)
            minLon = minOf(minLon, p.longitude)
            maxLon = maxOf(maxLon, p.longitude)
        }

        val latRange = maxLat - minLat
        val lonRange = maxLon - minLon

        if (latRange == 0.0 && lonRange == 0.0) return@Canvas

        val latPadding = if (latRange == 0.0) 0.001 else latRange * 0.1
        val lonPadding = if (lonRange == 0.0) 0.001 else lonRange * 0.1

        val drawMinLat = minLat - latPadding
        val drawMaxLat = maxLat + latPadding
        val drawMinLon = minLon - lonPadding
        val drawMaxLon = maxLon + lonPadding

        val drawLatRange = drawMaxLat - drawMinLat
        val drawLonRange = drawMaxLon - drawMinLon

        val width = size.width
        val height = size.height
        
        val screenPoints = points.map { p ->
            val x = ((p.longitude - drawMinLon) / drawLonRange).toFloat() * width
            val y = (1 - ((p.latitude - drawMinLat) / drawLatRange)).toFloat() * height
            androidx.compose.ui.geometry.Offset(x, y)
        }
        
        val pointsToDraw = (screenPoints.size * pathProgress.value).toInt().coerceAtLeast(2)
        
        val path = androidx.compose.ui.graphics.Path()
        screenPoints.take(pointsToDraw).forEachIndexed { index, offset ->
            if (index == 0) {
                path.moveTo(offset.x, offset.y)
            } else {
                path.lineTo(offset.x, offset.y)
            }
        }

        drawPath(
            path = path,
            color = primaryColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            ),
            alpha = 0.6f
        )
        
        val startPoint = screenPoints.firstOrNull()
        if (startPoint != null && pathProgress.value > 0.05f) {
            drawCircle(
                color = startColor,
                radius = 6.dp.toPx(),
                center = startPoint,
                alpha = pathProgress.value
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = startPoint,
                alpha = pathProgress.value
            )
        }
        
        val endPoint = screenPoints.lastOrNull()
        if (endPoint != null && pathProgress.value > 0.9f) {
            val endAlpha = ((pathProgress.value - 0.9f) / 0.1f).coerceIn(0f, 1f)
            drawCircle(
                color = endColor,
                radius = 6.dp.toPx(),
                center = endPoint,
                alpha = endAlpha
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = endPoint,
                alpha = endAlpha
            )
        }
    }
}
