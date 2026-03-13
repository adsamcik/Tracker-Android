package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.GoalProgress
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Today Summary widget (4x2): distance hero metric, secondary stats, goal progress.
 * Tapping opens the app.
 */
class TodaySummaryWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary: DailySummary?
        val goalProgress: GoalProgress

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                TrackerWidgetEntryPoint::class.java,
            )

            summary = withContext(Dispatchers.IO) {
                entryPoint.dailySummaryProvider().fetchTodaySummary()
            }
            goalProgress = entryPoint.goalProgressProvider().goalProgressFlow.value
        } catch (_: Exception) {
            // Hilt not initialized or DB unavailable - show empty state.
            provideContent {
                GlanceTheme {
                    TodaySummaryContent(
                        summary = null,
                        goalProgress = GoalProgress(
                            stepsToday = 0,
                            goalSteps = 0,
                            gamificationEnabled = false,
                        ),
                        context = context,
                    )
                }
            }
            return
        }

        provideContent {
            GlanceTheme {
                TodaySummaryContent(
                    summary = summary,
                    goalProgress = goalProgress,
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun TodaySummaryContent(
    summary: DailySummary?,
    goalProgress: GoalProgress,
    context: Context,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(16.dp)
            .padding(16.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
    ) {
        // Header
        Text(
            text = context.getString(R.string.widget_today),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )

        if (summary == null || summary.isEmpty) {
            Spacer(modifier = GlanceModifier.height(12.dp))
            Text(
                text = context.getString(R.string.widget_no_data_today),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 14.sp,
                ),
            )
        } else {
            Spacer(modifier = GlanceModifier.height(4.dp))

            // Hero: Distance
            Text(
                text = WidgetFormatters.formatDistance(context, summary.totalDistanceM),
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = context.getString(R.string.widget_distance),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )

            Spacer(modifier = GlanceModifier.height(8.dp))

            // Secondary stats row
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                StatItem(
                    label = context.getString(R.string.widget_steps),
                    value = WidgetFormatters.formatSteps(summary.totalSteps),
                    modifier = GlanceModifier.defaultWeight(),
                )
                StatItem(
                    label = context.getString(R.string.widget_duration),
                    value = WidgetFormatters.formatDuration(summary.totalDurationMs),
                    modifier = GlanceModifier.defaultWeight(),
                )
                StatItem(
                    label = context.getString(R.string.widget_sessions),
                    value = summary.sessionCount.toString(),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }

            // Goal progress
            if (goalProgress.gamificationEnabled && goalProgress.goalSteps > 0) {
                Spacer(modifier = GlanceModifier.height(8.dp))
                GoalProgressBar(goalProgress, context)
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
            ),
        )
    }
}

@Composable
private fun GoalProgressBar(goalProgress: GoalProgress, context: Context) {
    val progressText = context.getString(
        R.string.widget_goal_progress,
        WidgetFormatters.formatSteps(goalProgress.stepsToday),
        WidgetFormatters.formatSteps(goalProgress.goalSteps),
    )
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(R.string.widget_goal_label),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = progressText,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = WidgetFormatters.formatGoalProgress(goalProgress.progress),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/**
 * BroadcastReceiver for the Today Summary widget.
 */
class TodaySummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodaySummaryWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateScheduler.schedulePeriodicUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateScheduler.cancelPeriodicUpdates(context)
    }
}
