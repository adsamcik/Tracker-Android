package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2Sink
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

/** V2 archive adapter kept separate so existing v1 call sites remain overload-free. */
@Singleton
internal class RoomExportPortableStepsV2 @Inject constructor(
	private val reader: PortableStepsRoomReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableStepsV2 {
	override suspend fun export(
		request: ExportPortableStepsRequest,
		sink: PortableStepsArchiveV2Sink,
	): ExportPortableStepsResult = withContext(ioDispatcher) {
		val snapshot = try {
			reader.readV2(request)
		} catch (cancelled: CancellationException) {
			if (!currentCoroutineContext().isActive) throw cancelled
			return@withContext ExportPortableStepsResult.RetryableFailure(
				PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: Exception) {
			return@withContext ExportPortableStepsResult.RetryableFailure(
				PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		when (snapshot) {
			is PortableStepsV2Snapshot.Outcome -> snapshot.result
			is PortableStepsV2Snapshot.Ready -> {
				val archive = try {
					PortableStepsArchiveV2.create(snapshot.entries)
				} catch (_: IllegalArgumentException) {
					return@withContext ExportPortableStepsResult.Unverifiable(
						com.adsamcik.tracker.stats.api.repository
							.PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					)
				}
				currentCoroutineContext().ensureActive()
				sink.emit(archive)
				ExportPortableStepsResult.Exported(archive.entries.size)
			}
		}
	}
}
