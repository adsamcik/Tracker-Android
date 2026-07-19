package com.adsamcik.tracker.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GameSettingsUiState {
	data object Loading : GameSettingsUiState

	data class Content(
		val settings: GoalsSettingsState,
	) : GameSettingsUiState

	data class Error(
		val cause: Throwable,
	) : GameSettingsUiState
}

@HiltViewModel
class GameSettingsViewModel @Inject constructor(
	private val goalsSettingsRepository: GoalsSettingsRepository,
) : ViewModel() {

	private val _uiState = MutableStateFlow<GameSettingsUiState>(GameSettingsUiState.Loading)
	val uiState: StateFlow<GameSettingsUiState> = _uiState.asStateFlow()

	init {
		viewModelScope.launch {
			goalsSettingsRepository.data
				.catch { cause -> _uiState.value = GameSettingsUiState.Error(cause) }
				.collect { settings -> _uiState.value = GameSettingsUiState.Content(settings) }
		}
	}

	fun setDailyStepGoal(steps: Int) {
		viewModelScope.launch {
			updateSettings { goalsSettingsRepository.setDailyStepGoal(steps) }
		}
	}

	fun setWeeklyStepGoal(steps: Int) {
		viewModelScope.launch {
			updateSettings { goalsSettingsRepository.setWeeklyStepGoal(steps) }
		}
	}

	fun setWeeklyDailyLimit(fraction: Float) {
		viewModelScope.launch {
			updateSettings { goalsSettingsRepository.setWeeklyDailyLimit(fraction) }
		}
	}

	fun setNotificationsEnabled(enabled: Boolean) {
		viewModelScope.launch {
			updateSettings { goalsSettingsRepository.setNotificationsEnabled(enabled) }
		}
	}

	fun setGameHapticsEnabled(enabled: Boolean) {
		viewModelScope.launch {
			updateSettings { goalsSettingsRepository.setGameHapticsEnabled(enabled) }
		}
	}

	fun setQuietCoachingEnabled(enabled: Boolean) {
		viewModelScope.launch {
			updateSettings { goalsSettingsRepository.setQuietCoachingEnabled(enabled) }
		}
	}

	fun setRememberLastSetup(enabled: Boolean) {
		viewModelScope.launch {
			updateSettings { goalsSettingsRepository.setRememberLastSetup(enabled) }
		}
	}

	private suspend fun updateSettings(update: suspend () -> Unit) {
		try {
			update()
		} catch (cause: Throwable) {
			_uiState.value = GameSettingsUiState.Error(cause)
		}
	}
}
