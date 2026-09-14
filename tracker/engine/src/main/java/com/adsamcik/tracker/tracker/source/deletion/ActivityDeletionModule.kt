package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletion
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface ActivityDeletionModule {
	@Binds
	fun bindActivitySessionDeletion(
		implementation: RoomActivitySelectedSessionDeletionService,
	): ActivitySessionDeletion
}
