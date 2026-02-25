package com.adsamcik.tracker.game.di

import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.shared.base.di.ActiveChallengeInfo
import com.adsamcik.tracker.shared.base.di.ActiveChallengesProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Default implementation of ActiveChallengesProvider.
 *
 * Delegates to GameRepository.getActiveChallenges() and maps ChallengeData
 * to simplified ActiveChallengeInfo for cross-module consumption.
 */
class DefaultActiveChallengesProvider(
	private val gameRepository: GameRepository,
	scope: CoroutineScope,
) : ActiveChallengesProvider {

	override val activeChallengesFlow: StateFlow<List<ActiveChallengeInfo>> =
		gameRepository.getActiveChallenges()
			.map { list ->
				list.map { data ->
					ActiveChallengeInfo(
						id = data.id,
						title = data.title,
						description = data.description,
						progress = data.progress,
						difficulty = "",
						timeRemainingMs = 0,
					)
				}
			}
			.stateIn(scope, SharingStarted.Lazily, emptyList())
}
