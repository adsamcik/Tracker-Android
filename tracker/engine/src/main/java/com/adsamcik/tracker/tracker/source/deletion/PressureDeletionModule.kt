package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletion
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrier
import com.adsamcik.tracker.tracker.source.pressure.RuntimePressureSourceEraseBarrier
import com.adsamcik.tracker.tracker.source.pressure.LegacyPressureWriterLifecycleBarrier
import com.adsamcik.tracker.tracker.source.pressure.UnavailableLegacyPressureWriterLifecycleBarrier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface PressureDeletionModule {
	@Binds
	fun bindPressureSessionDeletion(
		implementation: RoomPressureSelectedSessionDeletionService,
	): PressureSessionDeletion

	@Binds
	fun bindPressureSourceEraseBarrier(
		implementation: RuntimePressureSourceEraseBarrier,
	): PressureSourceEraseBarrier

	@Binds
	fun bindLegacyPressureWriterLifecycleBarrier(
		implementation: UnavailableLegacyPressureWriterLifecycleBarrier,
	): LegacyPressureWriterLifecycleBarrier
}
