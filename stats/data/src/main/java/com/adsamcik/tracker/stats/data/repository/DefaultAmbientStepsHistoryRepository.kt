@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageAuthenticator
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailure
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailureReason
import com.adsamcik.tracker.shared.base.database.authenticateAllAmbientStepsFences
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsRecentDayCandidate
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryDay
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryDayRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryDependency
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryFactOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryFactOriginKind
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentCursor
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRepository
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryValue
import com.adsamcik.tracker.stats.api.repository.AmbientStepsImportedDisposition
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericHistoryDay
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericRangeRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericRangeReader
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericStructuralDayRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsSessionOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientStepsSessionPartition
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage as ApiStepsHistoryCoverage
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Production historical composition over native, portable-origin, and session partitions. */
@Singleton
@Suppress("LargeClass")
internal class DefaultAmbientStepsHistoryRepository @Inject constructor(
	private val database: AppDatabase,
	private val importedDao: ImportedAmbientStepsDao,
	private val stepsSelector: StepsSegmentHistorySelector,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AmbientStepsHistoryRepository, AmbientStepsNumericRangeReader {
	override suspend fun discoverNumericStructuralDaysInCurrentTransaction(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		sessionCalendarDays: List<AmbientStepsStructuralDay>,
		expectedSourceEvidenceRevision: Long,
	): AmbientStepsNumericStructuralDayRead {
		require(firstEpochDay <= lastEpochDayInclusive)
		require(
			lastEpochDayInclusive.toULong() - firstEpochDay.toULong() <
				AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT.toULong(),
		)
		val evidence = database.sourceEvidenceStateDao().get()
			?: return AmbientStepsNumericStructuralDayRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.SOURCE_EVIDENCE_STATE_MISSING,
			)
		if (evidence.revision != expectedSourceEvidenceRevision) {
			return AmbientStepsNumericStructuralDayRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		}
		val zonesByDay = linkedMapOf<Long, LinkedHashSet<String>>()
		sessionCalendarDays.forEach { day ->
			if (day.epochDay in firstEpochDay..lastEpochDayInclusive) {
				zonesByDay.getOrPut(day.epochDay, ::linkedSetOf).add(day.storedZoneId)
			}
		}
		val native = database.ambientStepsFactRevisionDao().discoverStructuralDaysForEpochRange(
			writerId = com.adsamcik.tracker.shared.base.database.data
				.AmbientStepsFactRevisionEntity.WRITER_ID,
			writerVersion = com.adsamcik.tracker.shared.base.database.data
				.AmbientStepsFactRevisionEntity.WRITER_VERSION,
			firstEpochDay = firstEpochDay,
			lastEpochDayInclusive = lastEpochDayInclusive,
			limit = MAX_NUMERIC_STRUCTURAL_DAY_CANDIDATES + 1,
		)
		if (native.size > MAX_NUMERIC_STRUCTURAL_DAY_CANDIDATES) {
			return AmbientStepsNumericStructuralDayRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		}
		native.forEach { row ->
			AmbientStepsDayIdentity(
				row.structuralEpochDay,
				row.storedZoneId,
				row.structuralDayStartTimeMs,
				row.structuralDayEndTimeMs,
			)
			zonesByDay.getOrPut(row.structuralEpochDay, ::linkedSetOf).add(row.storedZoneId)
		}
		val imported = importedDao.latestDaysForEpochRange(
			firstEpochDay,
			lastEpochDayInclusive,
			MAX_NUMERIC_STRUCTURAL_DAY_CANDIDATES + 1,
		)
		val fences = importedDao.fencesForEpochRange(
			firstEpochDay,
			lastEpochDayInclusive,
			MAX_NUMERIC_STRUCTURAL_DAY_CANDIDATES + 1,
		)
		if (imported.size > MAX_NUMERIC_STRUCTURAL_DAY_CANDIDATES ||
			fences.size > MAX_NUMERIC_STRUCTURAL_DAY_CANDIDATES
		) {
			return AmbientStepsNumericStructuralDayRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		}
		imported.forEach { row ->
			zonesByDay.getOrPut(row.structuralEpochDay, ::linkedSetOf).add(row.storedZoneId)
		}
		fences.forEach { row ->
			zonesByDay.getOrPut(row.structuralEpochDay, ::linkedSetOf).add(row.storedZoneId)
		}
		if (zonesByDay.values.any { it.size != 1 }) {
			return AmbientStepsNumericStructuralDayRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
			)
		}
		if (zonesByDay.isEmpty()) return AmbientStepsNumericStructuralDayRead.NoAmbientAuthority
		val result = zonesByDay.map { (epochDay, zones) ->
			AmbientStepsStructuralDay(epochDay, zones.single())
		}.sortedWith(
			compareBy(AmbientStepsStructuralDay::epochDay)
				.thenBy(AmbientStepsStructuralDay::storedZoneId),
		)
		return AmbientStepsNumericStructuralDayRead.Exact(evidence.revision, result)
	}

	override suspend fun readDay(
		request: AmbientStepsHistoryDayRequest,
	): AmbientStepsHistoryRead = readRange(AmbientStepsHistoryRangeRequest(listOf(request.day)))

	override suspend fun readRange(
		request: AmbientStepsHistoryRangeRequest,
	): AmbientStepsHistoryRead = withContext(ioDispatcher) {
		if (!database.isOpen) return@withContext AmbientStepsHistoryRead.StorageUnavailable
		try {
			database.withTransaction {
				val evidence = database.sourceEvidenceStateDao().get()
					?: return@withTransaction AmbientStepsHistoryRead.Unavailable(
						AmbientStepsHistoryUnavailableReason.SOURCE_EVIDENCE_STATE_MISSING,
					)
				readRangeInCurrentTransaction(request, evidence.revision, includeSessions = true)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			AmbientStepsHistoryRead.StorageUnavailable
		} catch (_: IllegalArgumentException) {
			AmbientStepsHistoryRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		} catch (_: IllegalStateException) {
			AmbientStepsHistoryRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		} catch (_: ArithmeticException) {
			AmbientStepsHistoryRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		} catch (_: DateTimeException) {
			AmbientStepsHistoryRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		}
	}

	override suspend fun readNumericRangeInCurrentTransaction(
		request: AmbientStepsHistoryRangeRequest,
		expectedSourceEvidenceRevision: Long,
	): AmbientStepsNumericRangeRead = when (
		val read = readRangeInCurrentTransaction(
			request,
			expectedSourceEvidenceRevision,
			includeSessions = false,
		)
	) {
		is AmbientStepsHistoryRead.Snapshot -> AmbientStepsNumericRangeRead.Snapshot(
			read.sourceEvidenceRevision,
			read.days.map { AmbientStepsNumericHistoryDay(it.day, it.total) },
		)
		is AmbientStepsHistoryRead.DependencyOverflow ->
			AmbientStepsNumericRangeRead.DependencyOverflow(read.dependency)
		is AmbientStepsHistoryRead.Unavailable ->
			AmbientStepsNumericRangeRead.Unavailable(read.reason)
		AmbientStepsHistoryRead.StorageUnavailable -> AmbientStepsNumericRangeRead.StorageUnavailable
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun readRangeInCurrentTransaction(
		request: AmbientStepsHistoryRangeRequest,
		expectedSourceEvidenceRevision: Long,
		includeSessions: Boolean,
	): AmbientStepsHistoryRead {
		require(expectedSourceEvidenceRevision >= 0L)
		val evidence = database.sourceEvidenceStateDao().get()
			?: return AmbientStepsHistoryRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.SOURCE_EVIDENCE_STATE_MISSING,
			)
		check(evidence.revision == expectedSourceEvidenceRevision) {
			"Ambient Steps source evidence changed before range composition"
		}
		val days = request.days.map { it.toInternalDayOrNull() ?: return AmbientStepsHistoryRead.Unavailable(
			AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
		) }
		val range = AmbientStepsRequestedRange(days)
		val native = when (val read = NativeAmbientStepsRangeReader(database).read(range, evidence)) {
			is NativeAmbientStepsRangeRead.Ready -> read
			is NativeAmbientStepsRangeRead.DependencyOverflow ->
				return AmbientStepsHistoryRead.DependencyOverflow(read.dependency)
			NativeAmbientStepsRangeRead.Unverifiable ->
				return AmbientStepsHistoryRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
				)
		}
		val imported = when (
			val read = ImportedAmbientStepsRangeReader(database, importedDao).read(range, evidence)
		) {
			is ImportedAmbientStepsRangeRead.Ready -> read
			is ImportedAmbientStepsRangeRead.DependencyOverflow ->
				return AmbientStepsHistoryRead.DependencyOverflow(read.dependency)
			ImportedAmbientStepsRangeRead.Unverifiable ->
				return AmbientStepsHistoryRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
				)
		}
		val sessions = if (includeSessions) {
			when (val read = AmbientStepsSessionRangeReader(database, stepsSelector).read(range, evidence)) {
				is AmbientStepsSessionRangeRead.Ready -> read.sessions
				is AmbientStepsSessionRangeRead.DependencyOverflow ->
					return AmbientStepsHistoryRead.DependencyOverflow(read.dependency)
				AmbientStepsSessionRangeRead.Unverifiable ->
					return AmbientStepsHistoryRead.Unavailable(
						AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
					)
			}
		} else {
			emptyList()
		}
		val products = days.map { day ->
			composePublicDay(day, native, imported, sessions)
		}
		check(database.sourceEvidenceStateDao().get()?.revision == expectedSourceEvidenceRevision) {
			"Ambient Steps source evidence changed during range composition"
		}
		return AmbientStepsHistoryRead.Snapshot(expectedSourceEvidenceRevision, products)
	}

	override suspend fun readRecent(
		request: AmbientStepsHistoryRecentRequest,
	): AmbientStepsHistoryRecentRead = withContext(ioDispatcher) {
		if (!database.isOpen) return@withContext AmbientStepsHistoryRecentRead.StorageUnavailable
		try {
			database.withTransaction {
				val evidence = database.sourceEvidenceStateDao().get()
					?: return@withTransaction AmbientStepsHistoryRecentRead.Unavailable(
						AmbientStepsHistoryUnavailableReason.SOURCE_EVIDENCE_STATE_MISSING,
					)
				val candidates = recentCandidates(request, evidence)
				val accepted = candidates.take(request.limit)
				if (accepted.isEmpty()) {
					return@withTransaction AmbientStepsHistoryRecentRead.Page(
						evidence.revision,
						emptyList(),
						null,
					)
				}
				val range = readRangeInCurrentTransaction(
					AmbientStepsHistoryRangeRequest(
						accepted.map(AmbientStepsRecentCandidate::day).sortedWith(
							compareBy(AmbientStepsStructuralDay::epochDay)
								.thenBy(AmbientStepsStructuralDay::storedZoneId),
						),
					),
					evidence.revision,
					includeSessions = true,
				)
				when (range) {
					is AmbientStepsHistoryRead.Snapshot -> {
						val byDay = range.days.associateBy(AmbientStepsHistoryDay::day)
						AmbientStepsHistoryRecentRead.Page(
							sourceEvidenceRevision = range.sourceEvidenceRevision,
							days = accepted.map { candidate ->
								requireNotNull(byDay[candidate.day])
							},
							next = accepted.lastOrNull()?.takeIf {
								candidates.size > request.limit
							}?.toCursor(
								evidence.revision,
								evidence.collectedDataEpoch,
							),
						)
					}
					is AmbientStepsHistoryRead.DependencyOverflow ->
						AmbientStepsHistoryRecentRead.DependencyOverflow(range.dependency)
					is AmbientStepsHistoryRead.Unavailable ->
						AmbientStepsHistoryRecentRead.Unavailable(range.reason)
					AmbientStepsHistoryRead.StorageUnavailable ->
						AmbientStepsHistoryRecentRead.StorageUnavailable
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedAmbientStepsLineageFailure) {
			if (failure.reason == ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW) {
				AmbientStepsHistoryRecentRead.DependencyOverflow(
					AmbientStepsHistoryDependency.IMPORTED_DAYS,
				)
			} else {
				AmbientStepsHistoryRecentRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
				)
			}
		} catch (_: SQLiteException) {
			AmbientStepsHistoryRecentRead.StorageUnavailable
		} catch (_: IllegalArgumentException) {
			AmbientStepsHistoryRecentRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		} catch (_: IllegalStateException) {
			AmbientStepsHistoryRecentRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		} catch (_: ArithmeticException) {
			AmbientStepsHistoryRecentRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		} catch (_: DateTimeException) {
			AmbientStepsHistoryRecentRead.Unavailable(
				AmbientStepsHistoryUnavailableReason.CORRUPT_RETAINED_STATE,
			)
		}
	}

	override fun observeRange(
		request: AmbientStepsHistoryRangeRequest,
	): Flow<AmbientStepsHistoryRead> = database.invalidationTracker.createFlow(
		*AMBIENT_HISTORY_DEPENDENCY_TABLES,
		emitInitialState = true,
	).conflate().map {
		readRange(request)
	}.catch { failure ->
		if (failure is CancellationException) throw failure
		if (failure is SQLiteException) {
			emit(AmbientStepsHistoryRead.StorageUnavailable)
		} else {
			throw failure
		}
	}.distinctUntilChanged()

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun recentCandidates(
		request: AmbientStepsHistoryRecentRequest,
		evidence: SourceEvidenceState,
	): List<AmbientStepsRecentCandidate> {
		request.before?.let { before ->
			check(before.sourceEvidenceRevision == evidence.revision &&
				before.collectedDataEpoch == evidence.collectedDataEpoch
			) { "Ambient Steps recent cursor belongs to another evidence snapshot" }
			check(AmbientStepsStructuralDay(before.epochDay, before.storedZoneId)
				.toInternalDayOrNull()?.let { day ->
					before.publicDayIdentity == day.publicDayIdentity()
				} == true
			) { "Ambient Steps recent cursor has invalid public structural authority" }
		}
		val nativeOwner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
		)
		val hasNativeOwner =
			nativeOwner?.owner == SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS
		if (!hasNativeOwner && (
				database.ambientStepsFactRevisionDao().countAll() != 0L ||
					database.ambientStepsImportStateDao().countCursors() != 0L ||
					database.ambientStepsImportStateDao().countGaps() != 0L
				)
		) {
			error("Native Ambient Steps state has no destination owner")
		}
		val native = if (hasNativeOwner) {
			database.ambientStepsFactRevisionDao().discoverStructuralDayPage(
				writerId = com.adsamcik.tracker.shared.base.database.data
					.AmbientStepsFactRevisionEntity.WRITER_ID,
				writerVersion = com.adsamcik.tracker.shared.base.database.data
					.AmbientStepsFactRevisionEntity.WRITER_VERSION,
				beforeLatestWindowEndTimeMs = null,
				beforeEpochDay = null,
				beforeStoredZoneId = null,
				limit = MAX_RECENT_NORMALIZATION_CANDIDATES + 1,
			).map { row ->
				AmbientStepsRecentCandidate(
					AmbientStepsStructuralDay(row.structuralEpochDay, row.storedZoneId),
					row.latestWindowEndTimeMs,
				)
			}
		} else {
			emptyList()
		}
		val imported = importedDao.recentDayCandidatePage(
			null,
			null,
			null,
			null,
			MAX_RECENT_NORMALIZATION_CANDIDATES + 1,
		).map(ImportedAmbientStepsRecentDayCandidate::toRecentCandidate)
		if (native.size > MAX_RECENT_NORMALIZATION_CANDIDATES ||
			imported.size > MAX_RECENT_NORMALIZATION_CANDIDATES
		) {
			throw ImportedAmbientStepsLineageFailure(
				ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
			)
		}
		val ordered = (native + imported).groupBy(AmbientStepsRecentCandidate::day)
			.map { (day, rows) ->
				AmbientStepsRecentCandidate(
					day,
					rows.maxOf(AmbientStepsRecentCandidate::latestEvidenceTimeMs),
				)
		}.sortedWith(RECENT_AMBIENT_STEPS_ORDER).let { candidates ->
			request.before?.let { before ->
				val cursor = AmbientStepsRecentCandidate(
					AmbientStepsStructuralDay(before.epochDay, before.storedZoneId),
					before.latestEvidenceTimeMs,
				)
				candidates.filter { RECENT_AMBIENT_STEPS_ORDER.compare(it, cursor) > 0 }
			} ?: candidates
		}
		val bounded = ordered.take(request.limit + 1)
		if (bounded.isEmpty()) return emptyList()
		val minimumEpochDay = bounded.minOf { it.day.epochDay }
		val maximumEpochDay = bounded.maxOf { it.day.epochDay }
		val span = maximumEpochDay.toULong() - minimumEpochDay.toULong()
		check(span < AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT.toULong()) {
		"Ambient Steps recent candidates exceed the bounded structural-day span"
		}
		return bounded
	}

	internal companion object {
		private const val MAX_NUMERIC_STRUCTURAL_DAY_CANDIDATES =
			AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT * 2
		private const val MAX_RECENT_NORMALIZATION_CANDIDATES = 1_024
		val AMBIENT_HISTORY_DEPENDENCY_TABLES = arrayOf(
			"source_evidence_state",
			"source_destination_owner",
			"source_policy_authority",
			"source_policy",
			"source_consent_epoch",
			"source_demand",
			"provider_registration_generation",
			"source_authorization",
			"source_registration_state",
			"ambient_steps_fact_revision",
			"ambient_steps_import_cursor",
			"ambient_steps_import_gap",
			"ambient_steps_import_authority_transition",
			"imported_ambient_steps_archive",
			"imported_ambient_steps_receipt",
			"imported_ambient_steps_archive_day",
			"imported_ambient_steps_day_revision",
			"imported_ambient_steps_fact",
			"imported_ambient_steps_gap",
			"imported_ambient_steps_day_fence",
			"imported_ambient_steps_protected_identity",
			"imported_ambient_steps_source_fence",
			"logical_tracking_session",
			"source_service_run",
			"session_segment",
			"session_manifest_version",
			"session_manifest_source",
			"source_session_completeness",
			"source_product_projection_lane",
			"source_projection_failure",
			"source_deletion_fence",
			"step_fact_revision",
			"imported_steps_entry",
			"imported_steps_run",
			"imported_steps_manifest",
			"import_job_receipt",
			"import_entry_receipt",
		)
	}
}

internal data class AmbientStepsRequestedRange(
	val days: List<AmbientStepsDayIdentity>,
) {
	val keys = days.mapTo(linkedSetOf()) { it.historyKey }
	val firstEpochDay = days.minOf(AmbientStepsDayIdentity::epochDay)
	val lastEpochDayInclusive = days.maxOf(AmbientStepsDayIdentity::epochDay)
	val zoneIds = days.map(AmbientStepsDayIdentity::storedZoneId).distinct()
	val fromMs = days.minOf(AmbientStepsDayIdentity::startTimeMs)
	val toMs = days.maxOf(AmbientStepsDayIdentity::endTimeMs)

	init {
		require(days.isNotEmpty())
		require(keys.size == days.size)
	}
}

internal data class AmbientStepsHistoryKey(
	val epochDay: Long,
	val storedZoneId: String,
)

internal val AmbientStepsDayIdentity.historyKey: AmbientStepsHistoryKey
	get() = AmbientStepsHistoryKey(epochDay, storedZoneId)

private data class AmbientStepsRecentCandidate(
	val day: AmbientStepsStructuralDay,
	val latestEvidenceTimeMs: Long,
) {
	fun toCursor(
		sourceEvidenceRevision: Long,
		collectedDataEpoch: Long,
	) = AmbientStepsHistoryRecentCursor(
		sourceEvidenceRevision,
		collectedDataEpoch,
		latestEvidenceTimeMs,
		day.epochDay,
		day.storedZoneId,
		requireNotNull(day.toInternalDayOrNull()).publicDayIdentity(),
	)
}

private val RECENT_AMBIENT_STEPS_ORDER =
	compareByDescending<AmbientStepsRecentCandidate>(AmbientStepsRecentCandidate::latestEvidenceTimeMs)
		.thenByDescending { it.day.epochDay }
		.thenBy { it.day.storedZoneId }

private fun ImportedAmbientStepsRecentDayCandidate.toRecentCandidate() =
	AmbientStepsRecentCandidate(
		AmbientStepsStructuralDay(structuralEpochDay, storedZoneId),
		latestEvidenceTimeMs,
	)

private fun AmbientStepsStructuralDay.toInternalDayOrNull(): AmbientStepsDayIdentity? = try {
	val zone = ZoneId.of(storedZoneId)
	val date = LocalDate.ofEpochDay(epochDay)
	AmbientStepsDayIdentity(
		epochDay,
		storedZoneId,
		date.atStartOfDay(zone).toInstant().toEpochMilli(),
		date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli(),
	)
} catch (_: DateTimeException) {
	null
} catch (_: ArithmeticException) {
	null
}

private fun AmbientStepsDayIdentity.publicDayIdentity(): String =
	AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.DAY,
		"$epochDay|$storedZoneId|$startTimeMs|$endTimeMs",
	).value

private fun composePublicDay(
	day: AmbientStepsDayIdentity,
	native: NativeAmbientStepsRangeRead.Ready,
	imported: ImportedAmbientStepsRangeRead.Ready,
	sessions: List<QualifiedSessionStepsWindow>,
): AmbientStepsHistoryDay {
	val key = day.historyKey
	val facts = native.factsByDay[key].orEmpty() + imported.factsByDay[key].orEmpty()
	val gaps = native.gapsByDay[key].orEmpty() + imported.gapsByDay[key].orEmpty()
	val daySessions = sessions.mapNotNull { it.forDay(day) }
	val base = composeAmbientStepsDay(
		day,
		facts,
		gaps,
		daySessions,
		imported.causesByDay[key].orEmpty(),
	)
	val product = when {
		key in native.unverifiableDays -> {
			val unavailable = AmbientStepsNumericValue.Unavailable(
				setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
			)
			base.copy(total = unavailable, betweenSession = unavailable)
		}
		key in native.materializingDays -> {
			val unavailable = AmbientStepsNumericValue.Unavailable(
				setOf(AmbientStepsDayCause.AMBIENT_MATERIALIZING),
			)
			base.copy(total = unavailable, betweenSession = unavailable)
		}
		else -> base
	}
	val origins = facts.map { fact ->
		AmbientStepsHistoryFactOrigin(
			kind = when (fact.origin) {
				QualifiedAmbientStepsFactOrigin.LOCAL_PROVIDER ->
					AmbientStepsHistoryFactOriginKind.NATIVE_PROVIDER
				QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT ->
					AmbientStepsHistoryFactOriginKind.PORTABLE_IMPORT
			},
			opaqueFactIdentity = fact.portableIdentity,
			correctionRevision = fact.correctionRevision,
			archiveIdentity = fact.importedProvenance?.archiveIdentity,
		)
	}.distinct().sortedWith(
		compareBy(AmbientStepsHistoryFactOrigin::opaqueFactIdentity)
			.thenBy { it.kind.ordinal }
			.thenBy(AmbientStepsHistoryFactOrigin::correctionRevision),
	)
	return AmbientStepsHistoryDay(
		day = AmbientStepsStructuralDay(day.epochDay, day.storedZoneId),
		opaqueDayIdentity = day.publicDayIdentity(),
		structuralDayStartTimeMs = day.startTimeMs,
		structuralDayEndTimeMs = day.endTimeMs,
		total = product.total.toPublicValue(),
		inSession = product.inSession.map { session ->
			AmbientStepsSessionPartition(
				session.startTimeMs,
				session.endTimeMs,
				session.stepCount,
				when (session.origin) {
					QualifiedSessionStepsOrigin.LOCAL_CAPTURE ->
						AmbientStepsSessionOrigin.LOCAL_CAPTURE
					QualifiedSessionStepsOrigin.PORTABLE_IMPORT ->
						AmbientStepsSessionOrigin.PORTABLE_IMPORT
				},
			)
		}.sortedWith(
			compareBy(AmbientStepsSessionPartition::startTimeMs)
				.thenBy(AmbientStepsSessionPartition::endTimeMs)
				.thenBy { it.origin.ordinal },
		),
		betweenSession = product.betweenSession.toPublicValue(),
		factOrigins = origins,
		importedDisposition = imported.dispositionByDay[key] ?: AmbientStepsImportedDisposition.NONE,
	)
}

private fun QualifiedSessionStepsWindow.forDay(
	day: AmbientStepsDayIdentity,
): QualifiedSessionStepsWindow? {
	if (endTimeMs <= day.startTimeMs || startTimeMs >= day.endTimeMs) return null
	if (storedZoneId != day.storedZoneId) return this
	if (startTimeMs >= day.startTimeMs && endTimeMs <= day.endTimeMs) return this
	val clippedStart = maxOf(startTimeMs, day.startTimeMs)
	val clippedEnd = minOf(endTimeMs, day.endTimeMs)
	return copy(
		startTimeMs = clippedStart,
		endTimeMs = clippedEnd,
		stepCount = null,
		compatibility = SessionAmbientCompatibility.Unproven,
	)
}

private fun AmbientStepsNumericValue.toPublicValue(): AmbientStepsHistoryValue = when (this) {
	is AmbientStepsNumericValue.Exact -> AmbientStepsHistoryValue.Exact(count)
	is AmbientStepsNumericValue.Partial -> AmbientStepsHistoryValue.Partial(
		count,
		causes.mapTo(linkedSetOf(), AmbientStepsDayCause::toPublicCause),
	)
	is AmbientStepsNumericValue.Unavailable -> AmbientStepsHistoryValue.Unavailable(
		causes.mapTo(linkedSetOf(), AmbientStepsDayCause::toPublicCause),
	)
}

private fun AmbientStepsDayCause.toPublicCause(): AmbientStepsHistoryCause = when (this) {
	AmbientStepsDayCause.NO_AMBIENT_FACT -> AmbientStepsHistoryCause.NO_EVIDENCE
	AmbientStepsDayCause.AMBIENT_GAP -> AmbientStepsHistoryCause.EXPLICIT_GAP
	AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL -> AmbientStepsHistoryCause.PARTIAL_COVERAGE
	AmbientStepsDayCause.AMBIENT_FACT_OVERLAP -> AmbientStepsHistoryCause.FACT_OVERLAP
	AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT ->
		AmbientStepsHistoryCause.ORIGIN_IDENTITY_CONFLICT
	AmbientStepsDayCause.AMBIENT_COUNT_OVERFLOW -> AmbientStepsHistoryCause.COUNT_OVERFLOW
	AmbientStepsDayCause.AMBIENT_DAY_AUTHORITY_MISMATCH ->
		AmbientStepsHistoryCause.STRUCTURAL_AUTHORITY_CONFLICT
	AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE ->
		AmbientStepsHistoryCause.SOURCE_AUTHORITY_UNVERIFIABLE
	AmbientStepsDayCause.AMBIENT_MATERIALIZING -> AmbientStepsHistoryCause.MATERIALIZING
	AmbientStepsDayCause.AMBIENT_IMPORTED_DELETED -> AmbientStepsHistoryCause.DELETED
	AmbientStepsDayCause.AMBIENT_IMPORTED_RETAINED -> AmbientStepsHistoryCause.RETAINED
	AmbientStepsDayCause.SESSION_OUTSIDE_DAY -> AmbientStepsHistoryCause.SESSION_OUTSIDE_DAY
	AmbientStepsDayCause.SESSION_VALUE_UNAVAILABLE ->
		AmbientStepsHistoryCause.SESSION_VALUE_UNAVAILABLE
	AmbientStepsDayCause.SESSION_OVERLAP -> AmbientStepsHistoryCause.SESSION_OVERLAP
	AmbientStepsDayCause.SESSION_PROVIDER_COMPATIBILITY_UNPROVEN ->
		AmbientStepsHistoryCause.SESSION_OWNERSHIP_UNVERIFIABLE
	AmbientStepsDayCause.SESSION_NOT_COVERED_BY_COMPATIBLE_AMBIENT_FACT ->
		AmbientStepsHistoryCause.SESSION_NOT_COVERED
	AmbientStepsDayCause.SESSION_COUNT_EXCEEDS_AMBIENT_TOTAL ->
		AmbientStepsHistoryCause.SESSION_COUNT_EXCEEDS_TOTAL
}

private sealed interface ImportedAmbientStepsRangeRead {
	data class Ready(
		val factsByDay: Map<AmbientStepsHistoryKey, List<QualifiedAmbientStepsFact>>,
		val gapsByDay: Map<AmbientStepsHistoryKey, List<EffectiveAmbientStepsGap>>,
		val causesByDay: Map<AmbientStepsHistoryKey, Set<AmbientStepsDayCause>>,
		val dispositionByDay: Map<AmbientStepsHistoryKey, AmbientStepsImportedDisposition>,
	) : ImportedAmbientStepsRangeRead

	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : ImportedAmbientStepsRangeRead

	data object Unverifiable : ImportedAmbientStepsRangeRead
}

private class ImportedAmbientStepsRangeReader(
	private val database: AppDatabase,
	private val dao: ImportedAmbientStepsDao,
) {
	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	suspend fun read(
		range: AmbientStepsRequestedRange,
		evidence: SourceEvidenceState,
	): ImportedAmbientStepsRangeRead {
		try {
			dao.authenticateAllAmbientStepsFences(evidence.collectedDataEpoch)
		} catch (failure: ImportedAmbientStepsLineageFailure) {
			return when (failure.reason) {
				ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
				ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW,
				-> overflow(AmbientStepsHistoryDependency.IMPORTED_LINEAGES)
				ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
					ImportedAmbientStepsRangeRead.Unverifiable
			}
		}
		val sourceDeleted = sourceDeletionActive(evidence)
		val fences = dao.fencesForStructuralRange(
			range.firstEpochDay,
			range.lastEpochDayInclusive,
			range.zoneIds,
			MAX_IMPORTED_DAY_CANDIDATES + 1,
		)
		if (fences.size > MAX_IMPORTED_DAY_CANDIDATES) {
			return overflow(AmbientStepsHistoryDependency.IMPORTED_DAYS)
		}
		val matchingFences = fences.filter {
			AmbientStepsHistoryKey(it.structuralEpochDay, it.storedZoneId) in range.keys
		}
		if (matchingFences.any { it.collectedDataEpoch != evidence.collectedDataEpoch } ||
			matchingFences.groupBy {
				AmbientStepsHistoryKey(it.structuralEpochDay, it.storedZoneId)
			}.values.any { it.size != 1 }
		) return ImportedAmbientStepsRangeRead.Unverifiable
		val candidates = dao.latestDaysForStructuralRange(
			range.firstEpochDay,
			range.lastEpochDayInclusive,
			range.zoneIds,
			MAX_IMPORTED_DAY_CANDIDATES + 1,
		)
		if (candidates.size > MAX_IMPORTED_DAY_CANDIDATES) {
			return overflow(AmbientStepsHistoryDependency.IMPORTED_DAYS)
		}
		val selected = candidates.filter { candidate ->
			AmbientStepsHistoryKey(candidate.structuralEpochDay, candidate.storedZoneId) in range.keys
		}
		if (selected.groupBy {
				AmbientStepsHistoryKey(it.structuralEpochDay, it.storedZoneId)
			}.values.any { it.size != 1 }
		) return ImportedAmbientStepsRangeRead.Unverifiable
		if (selected.any { candidate ->
				matchingFences.any { it.dayIdentity == candidate.dayIdentity }
			}
		) return ImportedAmbientStepsRangeRead.Unverifiable
		val importedOwnedKeys = buildSet {
			matchingFences.mapTo(this) {
				AmbientStepsHistoryKey(it.structuralEpochDay, it.storedZoneId)
			}
			selected.mapTo(this) {
				AmbientStepsHistoryKey(it.structuralEpochDay, it.storedZoneId)
			}
		}
		val dispositions = range.keys.associateWith { key ->
			val fence = matchingFences.singleOrNull {
				it.structuralEpochDay == key.epochDay && it.storedZoneId == key.storedZoneId
			}
			when {
				sourceDeleted && key in importedOwnedKeys -> AmbientStepsImportedDisposition.DELETED
				fence?.fenceKind == ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION ->
					AmbientStepsImportedDisposition.RETAINED
				fence != null -> AmbientStepsImportedDisposition.DELETED
				else -> AmbientStepsImportedDisposition.NONE
			}
		}.toMutableMap()
		if (sourceDeleted) {
			val causes = dispositions.mapValues { (_, disposition) ->
				if (disposition == AmbientStepsImportedDisposition.DELETED) {
					setOf(AmbientStepsDayCause.AMBIENT_IMPORTED_DELETED)
				} else {
					emptySet()
				}
			}
			return ImportedAmbientStepsRangeRead.Ready(
				emptyMap(),
				emptyMap(),
				causes,
				dispositions,
			)
		}
		val retainedFloor = evidence.retainedFromMs
		val live = selected.filterNot { candidate ->
			val retained = retainedFloor != null && (
				candidate.structuralDayEndTimeMs <= retainedFloor ||
					candidate.structuralDayStartTimeMs < retainedFloor &&
					(candidate.retainedFromTimeMs == null ||
						candidate.retainedFromTimeMs < retainedFloor)
				)
			if (retained) {
				dispositions[AmbientStepsHistoryKey(
					candidate.structuralEpochDay,
					candidate.storedZoneId,
				)] = AmbientStepsImportedDisposition.RETAINED
			}
			retained
		}
		if (live.isEmpty()) {
			val causes = dispositions.mapValues { (_, disposition) ->
				when (disposition) {
					AmbientStepsImportedDisposition.DELETED ->
						setOf(AmbientStepsDayCause.AMBIENT_IMPORTED_DELETED)
					AmbientStepsImportedDisposition.RETAINED ->
						setOf(AmbientStepsDayCause.AMBIENT_IMPORTED_RETAINED)
					AmbientStepsImportedDisposition.NONE,
					AmbientStepsImportedDisposition.PRESENT,
					-> emptySet()
				}
			}
			return ImportedAmbientStepsRangeRead.Ready(
				emptyMap(),
				emptyMap(),
				causes,
				dispositions,
			)
		}
		val dayIds = live.map(ImportedAmbientStepsDayRevisionEntity::dayIdentity)
		val headers = dao.dayRevisionsForHistory(dayIds, MAX_IMPORTED_DAY_REVISIONS + 1)
		val directMembers = dao.archiveDaysForHistory(dayIds, MAX_IMPORTED_ARCHIVES + 1)
		val facts = dao.factsForHistory(dayIds, MAX_IMPORTED_FACTS + 1)
		val gaps = dao.gapsForHistory(dayIds, MAX_IMPORTED_GAPS + 1)
		if (headers.size > MAX_IMPORTED_DAY_REVISIONS || directMembers.size > MAX_IMPORTED_ARCHIVES ||
			facts.size > MAX_IMPORTED_FACTS || gaps.size > MAX_IMPORTED_GAPS
		) return overflow(AmbientStepsHistoryDependency.IMPORTED_LINEAGES)
		val archiveIds = directMembers.map { it.archiveIdentity }.distinct()
		val archives = archiveIds.chunked(ARCHIVE_QUERY_BATCH_SIZE).flatMap { ids ->
			dao.archives(ids, ids.size + 1)
		}
		val allMembers = mutableListOf<ImportedAmbientStepsArchiveDayEntity>()
		val receipts = mutableListOf<ImportedAmbientStepsReceiptEntity>()
		archiveIds.chunked(ARCHIVE_QUERY_BATCH_SIZE).forEach { ids ->
			val memberPage = dao.archiveDays(
				ids,
				MAX_IMPORTED_ARCHIVE_MEMBERS - allMembers.size + 1,
			)
			allMembers += memberPage
			if (allMembers.size > MAX_IMPORTED_ARCHIVE_MEMBERS) {
				return overflow(AmbientStepsHistoryDependency.IMPORTED_LINEAGES)
			}
			val receiptPage = dao.receiptsForArchives(
				ids,
				MAX_IMPORTED_RECEIPTS - receipts.size + 1,
			)
			receipts += receiptPage
			if (receipts.size > MAX_IMPORTED_RECEIPTS) {
				return overflow(AmbientStepsHistoryDependency.IMPORTED_LINEAGES)
			}
		}
		if (archives.size != archiveIds.size ||
			allMembers.size > MAX_IMPORTED_ARCHIVE_MEMBERS ||
			receipts.size > MAX_IMPORTED_RECEIPTS
		) return overflow(AmbientStepsHistoryDependency.IMPORTED_LINEAGES)
		val headersByDay = headers.groupBy(ImportedAmbientStepsDayRevisionEntity::dayIdentity)
		val factsByDay = facts.groupBy { it.dayIdentity }
		val gapsByDay = gaps.groupBy { it.dayIdentity }
		val directMembersByDay = directMembers.groupBy { it.dayIdentity }
		val archivesById = archives.associateBy { it.archiveIdentity }
		val allMembersByArchive = allMembers.groupBy { it.archiveIdentity }
		val receiptsByArchive = receipts.groupBy { it.archiveIdentity }
		val resultFacts = linkedMapOf<AmbientStepsHistoryKey, List<QualifiedAmbientStepsFact>>()
		val resultGaps = linkedMapOf<AmbientStepsHistoryKey, List<EffectiveAmbientStepsGap>>()
		val resultCauses = linkedMapOf<AmbientStepsHistoryKey, Set<AmbientStepsDayCause>>()
		dispositions.forEach { (key, disposition) ->
			when (disposition) {
				AmbientStepsImportedDisposition.DELETED ->
					resultCauses[key] = setOf(AmbientStepsDayCause.AMBIENT_IMPORTED_DELETED)
				AmbientStepsImportedDisposition.RETAINED ->
					resultCauses[key] = setOf(AmbientStepsDayCause.AMBIENT_IMPORTED_RETAINED)
				AmbientStepsImportedDisposition.NONE,
				AmbientStepsImportedDisposition.PRESENT,
				-> Unit
			}
		}
		for (candidate in live) {
			currentCoroutineContext().ensureActive()
			val relatedArchiveIds = directMembersByDay[candidate.dayIdentity].orEmpty()
				.mapTo(linkedSetOf()) { it.archiveIdentity }
			val lineage = try {
				ImportedAmbientStepsLineageAuthenticator.authenticate(
					dayIdentity = candidate.dayIdentity,
					expectedCollectedDataEpoch = evidence.collectedDataEpoch,
					headers = headersByDay[candidate.dayIdentity].orEmpty(),
					archives = relatedArchiveIds.mapNotNull(archivesById::get),
					archiveDays = relatedArchiveIds.flatMap {
						allMembersByArchive[it].orEmpty()
					},
					receipts = relatedArchiveIds.flatMap {
						receiptsByArchive[it].orEmpty()
					},
					facts = factsByDay[candidate.dayIdentity].orEmpty(),
					gaps = gapsByDay[candidate.dayIdentity].orEmpty(),
				)
			} catch (failure: ImportedAmbientStepsLineageFailure) {
				return when (failure.reason) {
					ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
					ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW,
					-> overflow(AmbientStepsHistoryDependency.IMPORTED_LINEAGES)
					ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
						ImportedAmbientStepsRangeRead.Unverifiable
				}
			}
			if (lineage.latest.header != candidate) return ImportedAmbientStepsRangeRead.Unverifiable
			val revision = lineage.latest
			val day = revision.day
			val key = AmbientStepsHistoryKey(day.structuralEpochDay, day.storedZoneId)
			if (key !in range.keys) return ImportedAmbientStepsRangeRead.Unverifiable
			val internalDay = range.days.singleOrNull { it.historyKey == key }
				?: return ImportedAmbientStepsRangeRead.Unverifiable
			if (day.structuralDayStartTimeMs != internalDay.startTimeMs ||
				day.structuralDayEndTimeMs != internalDay.endTimeMs
			) return ImportedAmbientStepsRangeRead.Unverifiable
			val provenance = ImportedAmbientStepsFactProvenance(
				revision.header.archiveIdentity,
				revision.header.dayIdentity,
				revision.header.importRevision,
			)
			resultFacts[key] = day.facts.map { fact ->
				QualifiedAmbientStepsFact(
					logicalFactId = fact.identity.value,
					day = internalDay,
					startTimeMs = fact.intervalStartTimeMs,
					endTimeMs = fact.intervalEndTimeMs,
					stepCount = fact.stepCount,
					provenance = null,
					portableIdentity = fact.identity.value,
					origin = QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT,
					importedProvenance = provenance,
					correctionRevision = revision.header.importRevision,
					contentChecksum = fact.contentChecksum.value,
				)
			}
			resultGaps[key] = day.gaps.map {
				EffectiveAmbientStepsGap(it.intervalStartTimeMs, it.intervalEndTimeMs)
			}
			resultCauses[key] = day.toAmbientStepsProductCauses()
			dispositions[key] = AmbientStepsImportedDisposition.PRESENT
		}
		return ImportedAmbientStepsRangeRead.Ready(
			resultFacts,
			resultGaps,
			resultCauses,
			dispositions,
		)
	}

	private suspend fun sourceDeletionActive(evidence: SourceEvidenceState): Boolean {
		val fence = dao.sourceFence() ?: return false
		if (fence.collectedDataEpoch != evidence.collectedDataEpoch) {
			throw IllegalStateException("Imported Ambient Steps source fence has a stale epoch")
		}
		val authority = database.sourcePolicyDao().authority()
		val policy = authority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		}?.let {
			database.sourcePolicyDao().policyAtRevision(
				it.currentPolicyRevision,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			)
		}
		val consent = database.sourcePolicyDao().latestConsentEpoch(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		val reset = fence.deletionCompleted &&
			policy != null && consent != null && policy.enabled &&
			policy.ambientPersistenceEligible && policy.ambientConsentEpoch == consent.epoch &&
			consent.eligible && consent.persistenceEligible &&
			consent.policyRevision == policy.policyRevision &&
			consent.epoch > fence.revokedConsentEpoch &&
			fence.reopenedConsentEpoch == consent.epoch
		return !reset
	}

	private fun overflow(dependency: AmbientStepsHistoryDependency) =
		ImportedAmbientStepsRangeRead.DependencyOverflow(dependency)

	private companion object {
		const val MAX_IMPORTED_DAY_CANDIDATES = 4_096
		const val MAX_IMPORTED_DAY_REVISIONS = 5_920
		const val MAX_IMPORTED_ARCHIVES = 65_536
		const val MAX_IMPORTED_FACTS = 65_536
		const val MAX_IMPORTED_GAPS = 16_384
		const val MAX_IMPORTED_ARCHIVE_MEMBERS = 65_536
		const val MAX_IMPORTED_RECEIPTS = 65_536
		const val ARCHIVE_QUERY_BATCH_SIZE = 400
	}
}

private sealed interface AmbientStepsSessionRangeRead {
	data class Ready(
		val sessions: List<QualifiedSessionStepsWindow>,
	) : AmbientStepsSessionRangeRead

	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : AmbientStepsSessionRangeRead

	data object Unverifiable : AmbientStepsSessionRangeRead
}

private class AmbientStepsSessionRangeReader(
	private val database: AppDatabase,
	private val stepsSelector: StepsSegmentHistorySelector,
) {
	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	suspend fun read(
		range: AmbientStepsRequestedRange,
		evidence: SourceEvidenceState,
	): AmbientStepsSessionRangeRead {
		val segmentCandidates = database.trackingHistoryReadDao().portableStepsSegmentCandidatePage(
			fromMs = range.fromMs,
			toMs = range.toMs,
			stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			limit = MAX_SESSION_CANDIDATES + 1,
			afterStartTimeMs = null,
			afterSegmentId = null,
		)
		if (segmentCandidates.size > MAX_SESSION_CANDIDATES) {
			return overflow(AmbientStepsHistoryDependency.SESSION_CANDIDATES)
		}
		val segments = segmentCandidates.filter { it.source != SegmentSource.PORTABLE_STEPS_IMPORT }
		val local = mutableListOf<QualifiedSessionStepsWindow>()
		for (batch in segments.chunked(HISTORY_SEGMENT_BATCH_CAP)) {
			currentCoroutineContext().ensureActive()
			val snapshot = loadStepsHistoryBatchSnapshot(database, batch)
			if (snapshot.dependencyOverflow != null) {
				return overflow(AmbientStepsHistoryDependency.SESSION_HISTORY)
			}
			val sessionEvidence = snapshot.evidenceState
			if (sessionEvidence?.revision != evidence.revision ||
				sessionEvidence?.collectedDataEpoch != evidence.collectedDataEpoch
			) {
				return AmbientStepsSessionRangeRead.Unverifiable
			}
			val selected = stepsSelector.selectManyWithSnapshot(batch, snapshot)
			if (selected.any {
					it.segment.logicalTrackingId.isNullOrBlank() ||
						it.segment.serviceRunId.isNullOrBlank()
				}
			) return AmbientStepsSessionRangeRead.Unverifiable
			local += selected.map { selectedEvidence ->
				val result = selectedEvidence.steps
				val serviceRunId = requireNotNull(selectedEvidence.segment.serviceRunId)
				QualifiedSessionStepsWindow(
					logicalTrackingId = requireNotNull(selectedEvidence.segment.logicalTrackingId),
					serviceRunId = serviceRunId,
					startTimeMs = selectedEvidence.segment.startTimeMs,
					endTimeMs = selectedEvidence.segment.endTimeMs,
					stepCount = result.count.takeIf {
						result.availability == StepsHistoryAvailability.AVAILABLE &&
							result.materialization == StepsHistoryMaterialization.READY &&
							result.coverage == StepsHistoryCoverage.COMPLETE
					},
					storedZoneId = snapshot.manifestsByRun[serviceRunId].orEmpty()
						.map { it.zoneId }
						.distinct()
						.singleOrNull(),
					compatibility = SessionAmbientCompatibility.Unproven,
				)
			}
		}
		val entries = database.importedStepsDao().entriesOverlapping(
			range.fromMs,
			range.toMs,
			MAX_IMPORTED_SESSION_ENTRIES + 1,
		)
		if (entries.size > MAX_IMPORTED_SESSION_ENTRIES) {
			return overflow(AmbientStepsHistoryDependency.IMPORTED_SESSION_ENTRIES)
		}
		val imported = mutableListOf<QualifiedSessionStepsWindow>()
		val reader = com.adsamcik.tracker.shared.base.database.steps.imported
			.ImportedStepsRetainedReader(database)
		for (batch in entries.chunked(
			com.adsamcik.tracker.shared.base.database.steps.imported
				.ImportedStepsRetainedReader.MAX_ENTRY_BATCH,
		)) {
			currentCoroutineContext().ensureActive()
			when (val read = reader.readEntriesInTransaction(batch.map { it.identity })) {
				is com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead.Ready -> {
					imported += read.entries.flatMap { entry ->
						entry.runs.map { run ->
							val history = entry.portableRunsById.getValue(run.identity)
								.toImportedStepsHistory(
									run.identity in entry.retentionTruncatedRunIds,
								)
							QualifiedSessionStepsWindow(
								logicalTrackingId = entry.metadata.identity,
								serviceRunId = run.identity,
								startTimeMs = run.startTimeMs,
								endTimeMs = run.endTimeMs,
								stepCount = history.count.takeIf {
									history.productState == HistoryProductState.READY &&
										history.coverage == ApiStepsHistoryCoverage.COMPLETE
								},
								storedZoneId = run.storedZoneId,
								compatibility = SessionAmbientCompatibility.Unproven,
								origin = QualifiedSessionStepsOrigin.PORTABLE_IMPORT,
							)
						}
					}
					imported += batch.filter { candidate ->
						candidate.identity in read.unverifiableEntries
					}.map { candidate ->
						QualifiedSessionStepsWindow(
							logicalTrackingId = candidate.identity,
							serviceRunId = candidate.identity,
							startTimeMs = candidate.startTimeMs,
							endTimeMs = candidate.endTimeMs,
							stepCount = null,
							storedZoneId = null,
							compatibility = SessionAmbientCompatibility.Unproven,
							origin = QualifiedSessionStepsOrigin.PORTABLE_IMPORT,
						)
					}
				}
				is com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead.Unverifiable -> {
					return if (
						read.reason == com.adsamcik.tracker.shared.base.database.steps.imported
							.ImportedStepsReadFailure.DEPENDENCY_OVERFLOW
					) {
						overflow(AmbientStepsHistoryDependency.IMPORTED_SESSION_ENTRIES)
					} else {
						AmbientStepsSessionRangeRead.Unverifiable
					}
				}
			}
		}
		val sessions = (local + imported).sortedWith(
			compareBy(QualifiedSessionStepsWindow::startTimeMs)
				.thenBy(QualifiedSessionStepsWindow::endTimeMs)
				.thenBy(QualifiedSessionStepsWindow::serviceRunId),
		)
		return AmbientStepsSessionRangeRead.Ready(sessions)
	}

	private fun overflow(dependency: AmbientStepsHistoryDependency) =
		AmbientStepsSessionRangeRead.DependencyOverflow(dependency)

	private companion object {
		const val MAX_SESSION_CANDIDATES = 256
		const val MAX_IMPORTED_SESSION_ENTRIES = 256
	}
}
