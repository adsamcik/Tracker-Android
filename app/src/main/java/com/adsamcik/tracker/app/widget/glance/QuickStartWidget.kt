package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.adsamcik.tracker.R
import dagger.hilt.android.EntryPointAccessors

/**
 * Quick Start widget (2×1): minimal start/stop toggle with duration text.
 */
class QuickStartWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning: Boolean
        val durationText: String?

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                TrackerWidgetEntryPoint::class.java,
            )
            val controller = entryPoint.trackerServiceController()
            isRunning = controller.isServiceRunning
            val session = controller.sessionFlow.value
            durationText = if (isRunning && session != null) {
                val elapsed = System.currentTimeMillis() - session.start
                WidgetFormatters.formatDuration(elapsed)
            } else {
                null
            }
        } catch (_: Exception) {
            // Hilt not initialized yet (e.g., during restore after OOM kill).
            // Show safe default.
            provideContent {
                GlanceTheme {
                    QuickStartContent(
                        isRunning = false,
                        durationText = null,
                        context = context,
                    )
                }
            }
            return
        }

        provideContent {
            GlanceTheme {
                QuickStartContent(
                    isRunning = isRunning,
                    durationText = durationText,
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun QuickStartContent(
    isRunning: Boolean,
    durationText: String?,
    context: Context,
) {
    val backgroundColor = if (isRunning) {
        GlanceTheme.colors.primary
    } else {
        GlanceTheme.colors.surfaceVariant
    }
    val textColor = if (isRunning) {
        GlanceTheme.colors.onPrimary
    } else {
        GlanceTheme.colors.onSurface
    }
    val statusText = if (isRunning) {
        context.getString(R.string.widget_stop_tracking)
    } else {
        context.getString(R.string.widget_start_tracking)
    }

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(backgroundColor)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionRunCallback<ToggleTrackingAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier.defaultWeight(),
        ) {
            Text(
                text = statusText,
                style = TextStyle(
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (durationText != null) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = durationText,
                    style = TextStyle(
                        color = textColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Image(
            provider = ImageProvider(
                if (isRunning) R.drawable.ic_widget_stop else R.drawable.ic_widget_play,
            ),
            contentDescription = statusText,
            modifier = GlanceModifier.size(32.dp),
            colorFilter = ColorFilter.tint(textColor),
        )
    }
}

/**
 * BroadcastReceiver for the Quick Start widget.
 */
class QuickStartWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickStartWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateScheduler.schedulePeriodicUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateScheduler.cancelPeriodicUpdates(context)
    }
}
