package com.adsamcik.tracker.game.di

import android.app.Application
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.shared.base.Time
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
 * Wraps ChallengeManager.activeChallenges and maps ChallengeInstance
 * to simplified ActiveChallengeInfo for cross-module consumption.
 */
class DefaultActiveChallengesProvider(
	private val application: Application,
	scope: CoroutineScope,
) : ActiveChallengesProvider {

	override val activeChallengesFlow: StateFlow<List<ActiveChallengeInfo>> =
		ChallengeManager.activeChallenges
			.map { list ->
				list.map { inst ->
					ActiveChallengeInfo(
						id = inst.data.id,
						title = inst.getTitle(application),
						description = inst.getDescription(application),
						progress = inst.progress.toFloat().coerceIn(0f, 1f),
						difficulty = inst.data.difficulty.name,
						timeRemainingMs = (inst.data.endTime - Time.nowMillis).coerceAtLeast(0),
					)
				}
			}
			.stateIn(scope, SharingStarted.Lazily, emptyList())
}
