package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletion
import com.adsamcik.tracker.stats.api.repository.ActivitySourceErase
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedLocalErase
import com.adsamcik.tracker.tracker.source.activity.RoomActivityCapturedLocalErase
import com.adsamcik.tracker.tracker.source.activity.RoomActivitySourceErase
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

	@Binds
	fun bindActivityCapturedLocalErase(
		implementation: RoomActivityCapturedLocalErase,
	): ActivityCapturedLocalErase

	@Binds
	fun bindActivitySourceErase(
		implementation: RoomActivitySourceErase,
	): ActivitySourceErase
}
