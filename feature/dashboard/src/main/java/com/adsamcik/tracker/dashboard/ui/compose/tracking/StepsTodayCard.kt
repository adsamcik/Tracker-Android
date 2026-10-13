package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.tracker.controller.formatTodaySteps
import com.adsamcik.tracker.tracker.R as TrackerR

/** The day's saved count stays visible independently of session source history. */
@Composable
internal fun StepsTodayCard(display: StepsDailyDisplay, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().testTag("dashboard_steps_today")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(RidgelineSpacing.Md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(TrackerR.string.tracker_steps_today_title), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(end = RidgelineSpacing.Sm))
            Text(
                display.formatTodaySteps(LocalContext.current) { it.formatReadable() },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).testTag("dashboard_steps_today_value"),
                textAlign = TextAlign.End,
            )
        }
    }
}
