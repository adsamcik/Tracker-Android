package com.adsamcik.tracker.tracker.source.pressure

import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrier
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** Source-local bridge; it grants no deletion authority beyond settling the owned provider. */
@Singleton
internal class RuntimePressureSourceEraseBarrier @Inject constructor(
	private val runtime: PressureSourceRuntime,
) : PressureSourceEraseBarrier {
	override suspend fun establish(
		expectedCollectedDataEpoch: Long,
	): PressureSourceEraseBarrierResult =
		runtime.establishSourceEraseBarrier(expectedCollectedDataEpoch)
}
