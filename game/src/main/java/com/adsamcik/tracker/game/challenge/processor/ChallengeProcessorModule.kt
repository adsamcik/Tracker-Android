package com.adsamcik.tracker.game.challenge.processor

import com.adsamcik.tracker.game.challenge.data.ChallengeType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class ChallengeProcessorModule {

	@Binds
	@IntoMap
	@ChallengeTypeKey(ChallengeType.Step)
	abstract fun bindStep(impl: StepChallengeProcessor): ChallengeProcessor

	@Binds
	@IntoMap
	@ChallengeTypeKey(ChallengeType.WalkDistance)
	abstract fun bindWalkDistance(impl: WalkDistanceChallengeProcessor): ChallengeProcessor

	@Binds
	@IntoMap
	@ChallengeTypeKey(ChallengeType.ActiveTime)
	abstract fun bindActiveTime(impl: ActiveTimeChallengeProcessor): ChallengeProcessor

	@Binds
	@IntoMap
	@ChallengeTypeKey(ChallengeType.Explorer)
	abstract fun bindExplorer(impl: ExplorerChallengeProcessor): ChallengeProcessor
}
