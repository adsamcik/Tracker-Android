package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.AllDayCellFactStatus
import com.adsamcik.tracker.stats.api.repository.AllDayLocationPointStatus
import com.adsamcik.tracker.stats.api.repository.AllDayReadingTime
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusQuery
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRead
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRequest
import com.adsamcik.tracker.stats.api.repository.AllDayStepsGapStatus
import com.adsamcik.tracker.stats.api.repository.AllDayStepsWindowStatus
import com.adsamcik.tracker.stats.api.repository.AllDayWifiFactStatus
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadResult
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Shared read-only source facade. Existing source readers own every qualification and total. */
@Singleton
internal class RoomAllDaySourceStatusQuery @Inject constructor(
	private val database: AppDatabase,
	private val steps: DefaultAmbientStepsHistoryRepository,
	private val wifi: RoomAmbientWifiRepository,
	private val cell: RoomAmbientCellRepository,
	@IoDispatcher private val io: CoroutineDispatcher,
) : AllDaySourceStatusQuery {
	private val nativeSteps = NativeAmbientStepsRangeReader(database)
	private val location = RetainedLocationContextReader(database)

	override suspend fun read(request: AllDaySourceStatusRequest): AllDaySourceStatusRead = withContext(io) {
		currentCoroutineContext().ensureActive()
		if (request is AllDaySourceStatusRequest.Unsupported) return@withContext AllDaySourceStatusRead.Unsupported
		if (!database.isOpen) return@withContext unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
		try {
			database.withTransaction {
				if (database.activeRetentionFloorSettlement() != null) {
					return@withTransaction unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
				}
				val evidence = database.sourceEvidenceStateDao().get()
					?: return@withTransaction unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
				if (!evidence.hasValidRangeShape()) {
					return@withTransaction unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
				}
				val snapshot = TrackingHistoryReadSnapshot(evidence.collectedDataEpoch, evidence.revision)
				when (request) {
					is AllDaySourceStatusRequest.Steps -> readSteps(request, evidence, snapshot)
					is AllDaySourceStatusRequest.Wifi -> readWifi(request, snapshot)
					is AllDaySourceStatusRequest.Cell -> readCell(request, snapshot)
					is AllDaySourceStatusRequest.Location -> readLocation(request, snapshot)
					is AllDaySourceStatusRequest.Unsupported -> AllDaySourceStatusRead.Unsupported
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: android.database.sqlite.SQLiteException) {
			unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
		} catch (_: RuntimeException) {
			unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
		}
	}

	/** History keeps its requested local/imported membership and existing count-domain composer. */
	internal suspend fun readStepsHistoryInTransaction(request: AmbientStepsHistoryRangeRequest,
		expectedRevision: Long, includeLocal: Boolean, includeImported: Boolean): AmbientStepsHistoryRead =
		steps.readBackgroundInCurrentTransaction(request, expectedRevision, includeLocal, includeImported)

	/** Preserve the existing strict History result; optional status keeps qualification gaps below. */
	internal suspend fun readWifiHistoryInTransaction(request: AmbientWifiReadRequest): AmbientWifiReadResult =
		wifi.readObservationWindowInTransaction(request)

	internal suspend fun readCellHistoryInTransaction(request: AmbientCellReadRequest): AmbientCellReadResult =
		cell.readObservationWindowInTransaction(request)

	internal suspend fun qualifyLocationInTransaction(receipts: List<LocationAcceptedReceiptEntity>): RetainedLocationContextSnapshot =
		location.qualifyInTransaction(receipts)

	private suspend fun readSteps(request: AllDaySourceStatusRequest.Steps, evidence: SourceEvidenceState,
		snapshot: TrackingHistoryReadSnapshot): AllDaySourceStatusRead {
		val days = try {
			request.range.days.map { day ->
				val date = LocalDate.ofEpochDay(day.epochDay)
				val zone = ZoneId.of(day.storedZoneId)
				AmbientStepsDayIdentity(day.epochDay, day.storedZoneId,
					date.atStartOfDay(zone).toInstant().toEpochMilli(),
					date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli())
			}
		} catch (_: RuntimeException) { return unavailable(HistoryProblemReason.TIME_UNCERTAIN) }
		return when (val read = nativeSteps.read(AmbientStepsRequestedRange(days), evidence)) {
			is NativeAmbientStepsRangeRead.DependencyOverflow -> unavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
			NativeAmbientStepsRangeRead.Unverifiable -> unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
			is NativeAmbientStepsRangeRead.Ready -> AllDaySourceStatusRead.Steps(snapshot,
				read.factsByDay.values.flatten().map { fact ->
					AllDayStepsWindowStatus(fact.logicalFactId, fact.correctionRevision,
						AmbientStepsStructuralDay(fact.day.epochDay, fact.day.storedZoneId), fact.startTimeMs, fact.endTimeMs)
				},
				read.gapsByDay.flatMap { (key, gaps) -> gaps.map { gap ->
					AllDayStepsGapStatus(AmbientStepsStructuralDay(key.epochDay, key.storedZoneId), gap.startTimeMs, gap.endTimeMs)
				} },
				read.materializingDays.mapTo(linkedSetOf()) { AmbientStepsStructuralDay(it.epochDay, it.storedZoneId) },
				read.unverifiableDays.mapTo(linkedSetOf()) { AmbientStepsStructuralDay(it.epochDay, it.storedZoneId) })
		}
	}

	private suspend fun readWifi(request: AllDaySourceStatusRequest.Wifi,
		snapshot: TrackingHistoryReadSnapshot): AllDaySourceStatusRead {
		val window = request.window
		val read = wifi.readQualifiedObservationWindowInTransaction(AmbientWifiReadRequest(
			window.fromInclusiveMs, window.toExclusiveMs, setOf(AmbientWifiOrigin.LOCAL_DEVICE), request.limit))
		return when (val result = read.result) {
			AmbientWifiReadResult.DependencyOverflow -> unavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
			AmbientWifiReadResult.StorageUnavailable -> unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
			is AmbientWifiReadResult.Snapshot -> AllDaySourceStatusRead.Wifi(snapshot, result.facts.map { fact ->
				AllDayWifiFactStatus(fact.identity, fact.semanticRevision, fact.coverage, fact.coverageStartTimeMs,
					fact.observedTimeMs, fact.latestPossibleTimeMs, fact.networks.filter { window.contains(it.observedTimeMs) }
						.map { AllDayReadingTime(it.observedTimeMs, it.wallTimeUncertaintyMs) })
			}, result.gaps, read.readGaps)
		}
	}

	private suspend fun readCell(request: AllDaySourceStatusRequest.Cell,
		snapshot: TrackingHistoryReadSnapshot): AllDaySourceStatusRead {
		val window = request.window
		val read = cell.readQualifiedObservationWindowInTransaction(AmbientCellReadRequest(
			window.fromInclusiveMs, window.toExclusiveMs, setOf(AmbientCellOrigin.LOCAL_DEVICE), request.limit))
		return when (val result = read.result) {
			AmbientCellReadResult.DependencyOverflow -> unavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
			AmbientCellReadResult.StorageUnavailable -> unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
			is AmbientCellReadResult.Snapshot -> AllDaySourceStatusRead.Cell(snapshot, result.facts.map { fact ->
				AllDayCellFactStatus(fact.identity, fact.semanticRevision, fact.coverage, fact.subscriptionCompleteness,
					fact.coverageStartTimeMs, fact.observedTimeMs, fact.latestPossibleTimeMs,
					fact.towers.filter { window.contains(it.observedTimeMs) }
						.map { AllDayReadingTime(it.observedTimeMs, it.wallTimeUncertaintyMs) })
			}, result.gaps, read.readGaps)
		}
	}

	private suspend fun readLocation(request: AllDaySourceStatusRequest.Location,
		snapshot: TrackingHistoryReadSnapshot): AllDaySourceStatusRead {
		// An imported copy does not replace the independently retained native Ambient grant.
		val receipts = database.locationPurposeDao().contextReceiptsOverWindow(request.window.fromInclusiveMs,
			request.window.toExclusiveMs, request.limit + 1)
		if (receipts.size > request.limit) return unavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
		val read = qualifyLocationInTransaction(receipts)
		return AllDaySourceStatusRead.Location(snapshot, read.points.filter { it.scope is RadioContextScope.Ambient }
			.distinctBy { it.sourceEventId }.map { point ->
				AllDayLocationPointStatus(point.sourceEventId, AllDayReadingTime(point.observedWallTimeMs, point.wallTimeUncertaintyMs))
			}, read.gaps, read.historyProblem)
	}

	private fun unavailable(reason: HistoryProblemReason) = AllDaySourceStatusRead.Unavailable(reason)
}
