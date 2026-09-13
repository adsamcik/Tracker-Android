package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Room-backed Pressure exporter with an all-before-external-I/O snapshot boundary. */
@Singleton
internal class RoomExportPortablePressure @Inject constructor(
	private val reader: PortablePressureRoomReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportPortablePressure {
	override suspend fun export(
		request: ExportPortablePressureRequest,
		sink: PortablePressureEntrySink,
	): ExportPortablePressureResult = withContext(ioDispatcher) {
		val snapshot = try {
			reader.read(request)
		} catch (cancelled: CancellationException) {
			if (!currentCoroutineContext().isActive) throw cancelled
			return@withContext ExportPortablePressureResult.RetryableFailure(
				PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: Exception) {
			return@withContext ExportPortablePressureResult.RetryableFailure(
				PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		when (snapshot) {
			is PortablePressureSnapshot.Outcome -> snapshot.result
			is PortablePressureSnapshot.Ready -> {
				snapshot.entries.forEach { entry ->
					currentCoroutineContext().ensureActive()
					sink.emit(entry)
				}
				ExportPortablePressureResult.Exported(snapshot.entries.size)
			}
		}
	}
}
