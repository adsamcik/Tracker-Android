package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.challenge.progression.UnlockableFeature
import com.adsamcik.tracker.game.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
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

/** Provides reactive progression state (level, XP, streak, trophy summary). */
@HiltViewModel
class ProgressionViewModel @Inject constructor(
	private val gameRepository: GameRepository
) : ViewModel() {

	private val _unlockEvents = MutableSharedFlow<UnlockEvent>(extraBufferCapacity = 1)
	val unlockEvents: SharedFlow<UnlockEvent> = _unlockEvents

	val playerProfile = gameRepository.getPlayerProfile()
		.stateIn(viewModelScope, SharingStarted.Lazily, null)

	val streak = gameRepository.getStreak()
		.stateIn(viewModelScope, SharingStarted.Lazily, null)

	val trophySummary = gameRepository.getTrophySummary()
		.stateIn(
			viewModelScope,
			SharingStarted.Lazily,
			com.adsamcik.tracker.game.repository.TrophySummaryUi(0, 0, 0, 0)
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
}
