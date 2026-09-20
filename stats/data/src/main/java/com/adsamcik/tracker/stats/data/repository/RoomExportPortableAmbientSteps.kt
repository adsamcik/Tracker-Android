package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableReadFailure
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableReadRequest
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableRoomReader
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableSnapshot
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveV2Sink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportRetryableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportUnverifiableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface AmbientStepsPortableSnapshotSource {
	suspend fun read(request: AmbientStepsPortableReadRequest): AmbientStepsPortableSnapshot
}

/** Dormant source-local adapter; no Hilt binding or user-facing format registration exists yet. */
internal class RoomExportPortableAmbientSteps internal constructor(
	private val snapshotSource: AmbientStepsPortableSnapshotSource,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableAmbientSteps {
	@Inject constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		AmbientStepsPortableSnapshotSource { request ->
			AmbientStepsPortableRoomReader(database).read(request)
		},
		ioDispatcher,
	)

	override suspend fun export(
		request: ExportPortableAmbientStepsRequest,
		sink: PortableAmbientStepsArchiveSink,
	): ExportPortableAmbientStepsResult = withContext(ioDispatcher) {
		val snapshot = try {
			snapshotSource.read(
				AmbientStepsPortableReadRequest(request.fromInclusiveMs, request.toExclusiveMs),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return@withContext ExportPortableAmbientStepsResult.RetryableFailure(
				PortableAmbientStepsExportRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		when (snapshot) {
			AmbientStepsPortableSnapshot.NoData -> ExportPortableAmbientStepsResult.NoData
			is AmbientStepsPortableSnapshot.Unverifiable ->
				ExportPortableAmbientStepsResult.Unverifiable(snapshot.reason.toApiReason())
			is AmbientStepsPortableSnapshot.Ready -> {
				currentCoroutineContext().ensureActive()
				sink.emit(snapshot.archive)
				ExportPortableAmbientStepsResult.Exported(
					dayCount = snapshot.archive.days.size,
					factCount = snapshot.archive.days.sumOf { it.facts.size },
					gapCount = snapshot.archive.days.sumOf { it.gaps.size },
				)
			}
		}
	}
}

@Singleton
internal class RoomExportPortableAmbientStepsV2 @Inject constructor(
	private val database: AppDatabase,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableAmbientStepsV2 {
	override suspend fun export(
		request: ExportPortableAmbientStepsRequest,
		sink: PortableAmbientStepsArchiveV2Sink,
	): ExportPortableAmbientStepsResult = withContext(ioDispatcher) {
		val snapshot = try {
			AmbientStepsPortableRoomReader(database).readV2(
				AmbientStepsPortableReadRequest(request.fromInclusiveMs, request.toExclusiveMs),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return@withContext ExportPortableAmbientStepsResult.RetryableFailure(
				PortableAmbientStepsExportRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		when (snapshot) {
			AmbientStepsPortableSnapshot.NoData -> ExportPortableAmbientStepsResult.NoData
			is AmbientStepsPortableSnapshot.Unverifiable ->
				ExportPortableAmbientStepsResult.Unverifiable(snapshot.reason.toApiReason())
			is AmbientStepsPortableSnapshot.Ready -> {
				val archive = snapshot.authenticatedArchive
					?: return@withContext ExportPortableAmbientStepsResult.Unverifiable(
						PortableAmbientStepsExportUnverifiableReason.CORRUPT_RETAINED_STATE,
					)
				currentCoroutineContext().ensureActive()
				sink.emit(archive)
				ExportPortableAmbientStepsResult.Exported(
					dayCount = archive.days.size,
					factCount = archive.days.sumOf { it.product.facts.size },
					gapCount = archive.days.sumOf { it.product.gaps.size },
				)
			}
		}
	}
}

private fun AmbientStepsPortableReadFailure.toApiReason() = when (this) {
	AmbientStepsPortableReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE ->
		PortableAmbientStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE
	AmbientStepsPortableReadFailure.SOURCE_AUTHORITY_UNAVAILABLE ->
		PortableAmbientStepsExportUnverifiableReason.SOURCE_AUTHORITY_UNAVAILABLE
	AmbientStepsPortableReadFailure.DELETION_PENDING ->
		PortableAmbientStepsExportUnverifiableReason.DELETION_PENDING
	AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE ->
		PortableAmbientStepsExportUnverifiableReason.CORRUPT_RETAINED_STATE
	AmbientStepsPortableReadFailure.RETENTION_CROSSES_FACT ->
		PortableAmbientStepsExportUnverifiableReason.RETENTION_CROSSES_FACT
	AmbientStepsPortableReadFailure.MATERIALIZING ->
		PortableAmbientStepsExportUnverifiableReason.MATERIALIZING
	AmbientStepsPortableReadFailure.DEPENDENCY_OVERFLOW ->
		PortableAmbientStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW
}
