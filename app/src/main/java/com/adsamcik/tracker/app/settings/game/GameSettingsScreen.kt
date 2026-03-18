package com.adsamcik.tracker.app.settings.game

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.app.settings.GameSettingsViewModel
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem

@Composable
fun GameSettingsScreen(
    viewModel: GameSettingsViewModel = hiltViewModel()
) {
    val challengeEnabled by viewModel.challengeEnabled.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Challenges section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.game.R.string.settings_game_challenge_category_title))
        }

        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_title),
                checked = challengeEnabled,
                onCheckedChange = { viewModel.setChallengeEnabled(it) }
            )
        }

        // Goals section
        item {
            SectionHeader(stringResource(com.adsamcik.tracker.game.R.string.settings_game_goals_category_title))
        }

        item {
            Text(
                text = stringResource(com.adsamcik.tracker.R.string.settings_game_goals_managed_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
