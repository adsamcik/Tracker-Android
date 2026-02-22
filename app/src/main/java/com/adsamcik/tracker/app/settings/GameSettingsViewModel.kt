package com.adsamcik.tracker.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GameSettingsViewModel @Inject constructor(
    private val goalsSettingsRepository: GoalsSettingsRepository
) : ViewModel() {

    private val _challengeEnabled = MutableStateFlow(true)
    val challengeEnabled: StateFlow<Boolean> = _challengeEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            goalsSettingsRepository.data.collect { _challengeEnabled.value = it.challengesEnabled }
        }
    }

    fun setChallengeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            goalsSettingsRepository.setChallengesEnabled(enabled)
        }
    }
}
