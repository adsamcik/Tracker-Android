package com.adsamcik.tracker.tracker.ui.compose

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
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.controller.formatTodaySteps

@Composable
internal fun TrackerStepsTodayCard(display: StepsDailyDisplay, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().testTag("tracker_steps_today")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tracker_steps_today_title), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(
                display.formatTodaySteps(LocalContext.current) { it.formatReadable() },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).testTag("tracker_steps_today_value"),
                textAlign = TextAlign.End,
            )
        }
    }
}
