package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.challenge.progression.UnlockableFeature
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StreakUi
import com.adsamcik.tracker.game.repository.TrophySummaryUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Event emitted when the player levels up and unlocks new features.
 */
data class UnlockEvent(
	val newLevel: Int,
	val unlockedFeatures: List<UnlockableFeature>,
)

data class HeroLevelUiState(
	val playerProfile: PlayerProfileUi?,
	val streak: StreakUi?,
)

/** Provides reactive progression state (level, XP, streak, trophy summary). */
@HiltViewModel
class ProgressionViewModel @Inject constructor(
	private val gameRepository: GameRepository
) : ViewModel() {

	private val _unlockEvents = MutableSharedFlow<UnlockEvent>(extraBufferCapacity = 1)
	val unlockEvents: SharedFlow<UnlockEvent> = _unlockEvents

	val heroLevelState: StateFlow<HeroLevelUiState?> = combine(
		gameRepository.getPlayerProfile(),
		gameRepository.getStreak(),
	) { playerProfile, streak ->
		HeroLevelUiState(
			playerProfile = playerProfile,
			streak = streak,
		)
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
		initialValue = null,
	)

	val trophySummary: StateFlow<TrophySummaryUi?> = gameRepository.getTrophySummary()
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
			initialValue = null,
		)

	init {
		viewModelScope.launch {
			var previousLevel: Int? = null
			gameRepository.getPlayerProfile()
				.map { it?.level ?: 1 }
				.distinctUntilChanged()
				.collect { level ->
					val prev = previousLevel
					previousLevel = level
					if (prev != null && level > prev) {
						val unlocks = UnlockableFeature.entries
							.filter { it.requiredLevel in (prev + 1)..level }
						_unlockEvents.emit(UnlockEvent(level, unlocks))
					}
				}
		}
	}

	private companion object {
		const val STATE_STOP_TIMEOUT_MS = 5_000L
	}
}
