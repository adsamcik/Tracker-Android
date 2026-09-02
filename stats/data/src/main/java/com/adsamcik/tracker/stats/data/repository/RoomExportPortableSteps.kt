package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntrySink
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Room-backed portable Steps exporter with an all-before-I/O snapshot boundary. */
@Singleton
internal class RoomExportPortableSteps @Inject constructor(
	private val reader: PortableStepsRoomReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableSteps {
	override suspend fun export(
		request: ExportPortableStepsRequest,
		sink: PortableStepsEntrySink,
	): ExportPortableStepsResult = withContext(ioDispatcher) {
		val snapshot = try {
			reader.read(request)
		} catch (cancelled: CancellationException) {
			if (!currentCoroutineContext().isActive) {
				throw cancelled
			}
			return@withContext ExportPortableStepsResult.RetryableFailure(
				PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: Exception) {
			return@withContext ExportPortableStepsResult.RetryableFailure(
				PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		when (snapshot) {
			is PortableStepsSnapshot.Outcome -> snapshot.result
			is PortableStepsSnapshot.Ready -> {
				snapshot.entries.forEach { entry ->
					currentCoroutineContext().ensureActive()
					sink.emit(entry)
				}
				ExportPortableStepsResult.Exported(snapshot.entries.size)
			}
		}
	}
}
