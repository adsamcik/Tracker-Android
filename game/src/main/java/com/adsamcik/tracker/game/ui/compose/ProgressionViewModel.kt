package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Provides reactive progression state (level, XP, streak, trophy summary). */
@HiltViewModel
class ProgressionViewModel @Inject constructor(
	private val gameRepository: GameRepository
) : ViewModel() {

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
}
