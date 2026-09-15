package com.adsamcik.tracker.tracker.source.summary

import android.database.sqlite.SQLiteException
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryValue
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericHistoryDay
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericRangeRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericRangeReader
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarAuthority
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionWindow
import com.adsamcik.tracker.stats.api.repository.StepsNumericExactDecisionRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.tracker.source.deletion.StepsDailySummaryRepairComposer
import com.adsamcik.tracker.tracker.source.deletion.StepsDayNumericComposition
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPlan
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPreflight
import com.adsamcik.tracker.tracker.source.deletion.stepsNumericReadQueryBounds
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

/** Room-backed, read-only Steps totals for completeness-sensitive product decisions. */
@Singleton
class RoomStepsNumericSummaryRepository @Inject constructor(
	private val database: AppDatabase,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val ambientStepsReader: AmbientStepsNumericRangeReader,
) : StepsNumericSummaryRepository, StepsNumericDecisionRepository {
	internal constructor(
		database: AppDatabase,
		ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, NoAmbientStepsNumericRangeReader)

	override fun observe(request: StepsNumericSummaryRequest): Flow<StepsNumericSummary> =
		observeBatch(listOf(request)).map { batch -> batch.summaries.single() }

	override fun observeBatch(
		requests: List<StepsNumericSummaryRequest>,
	): Flow<StepsNumericSummaryBatch> = observeDecisionBatch(requests)
		.map { batch -> batch.toSummaryBatch(requests.size) }
		.distinctUntilChanged()

	override fun observeDecisionBatch(
		requests: List<StepsNumericSummaryRequest>,
	): Flow<StepsNumericDecisionBatch> {
		requireValidBatch(requests)
		return observedInvalidations()
			.map { readDecisionBatch(requests) }
			.catch { failure ->
				if (failure is CancellationException) {
					throw failure
				}
				emit(StepsNumericDecisionBatch.StorageUnavailable)
			}
			.distinctUntilChanged()
	}

	private fun observedInvalidations(): Flow<Set<String>> =
		database.invalidationTracker.createFlow(
			*NUMERIC_SUMMARY_DEPENDENCY_TABLES,
			emitInitialState = true,
		)
			.conflate()

	override suspend fun read(request: StepsNumericSummaryRequest): StepsNumericSummary =
		readBatch(listOf(request)).summaries.single()

	override suspend fun readBatch(
		requests: List<StepsNumericSummaryRequest>,
	): StepsNumericSummaryBatch = readDecisionBatch(requests).toSummaryBatch(requests.size)

	override suspend fun readDecisionBatch(
		requests: List<StepsNumericSummaryRequest>,
	): StepsNumericDecisionBatch {
		requireValidBatch(requests)
		return withContext(ioDispatcher) {
			try {
				readDecisionBatchInTransaction(requests)
			} catch (cancellation: CancellationException) {
				currentCoroutineContext().ensureActive()
				if (database.isOpen) {
					throw cancellation
				}
				StepsNumericDecisionBatch.StorageUnavailable
			} catch (_: Exception) {
				StepsNumericDecisionBatch.StorageUnavailable
			}
		}
	}

	override suspend fun readExactDecisionBatch(
		requests: List<StepsNumericExactDecisionRequest>,
	): StepsNumericDecisionBatch {
		requireValidBatch(requests.map(StepsNumericExactDecisionRequest::request))
		return withContext(ioDispatcher) {
			try {
				readExactDecisionBatchInTransaction(requests)
			} catch (cancellation: CancellationException) {
				currentCoroutineContext().ensureActive()
				if (database.isOpen) throw cancellation
				StepsNumericDecisionBatch.StorageUnavailable
			} catch (_: Exception) {
				StepsNumericDecisionBatch.StorageUnavailable
			}
		}
	}

	private suspend fun readDecisionBatchInTransaction(
		requests: List<StepsNumericSummaryRequest>,
	): StepsNumericDecisionBatch = database.useReaderConnection { connection ->
		connection.deferredTransaction {
			val before = database.sourceEvidenceStateDao().get()
				?: return@deferredTransaction StepsNumericDecisionBatch.StorageUnavailable
			val baseWindows = requests.map { request -> readInCurrentTransaction(request) }
			val windows = mergeAmbientStepsWindows(
				baseWindows,
				before.revision,
			) ?: return@deferredTransaction StepsNumericDecisionBatch.StorageUnavailable
			val after = database.sourceEvidenceStateDao().get()
				?: return@deferredTransaction StepsNumericDecisionBatch.StorageUnavailable
			check(before.revision == after.revision) {
				"Source evidence changed during a Steps decision snapshot"
			}
			StepsNumericDecisionBatch.Snapshot(
				sourceEvidenceRevision = before.revision,
				windows = windows,
			)
		}
	}

	private suspend fun readExactDecisionBatchInTransaction(
		requests: List<StepsNumericExactDecisionRequest>,
	): StepsNumericDecisionBatch = database.useReaderConnection { connection ->
		connection.deferredTransaction {
			val before = database.sourceEvidenceStateDao().get()
				?: return@deferredTransaction StepsNumericDecisionBatch.StorageUnavailable
			val baseWindows = requests.map { exact -> readInCurrentTransaction(exact) }
			val windows = mergeAmbientStepsWindows(
				baseWindows,
				before.revision,
			) ?: return@deferredTransaction StepsNumericDecisionBatch.StorageUnavailable
			val after = database.sourceEvidenceStateDao().get()
				?: return@deferredTransaction StepsNumericDecisionBatch.StorageUnavailable
			check(before.revision == after.revision) {
				"Source evidence changed during an exact historical Steps decision snapshot"
			}
			StepsNumericDecisionBatch.Snapshot(before.revision, windows)
		}
	}

	private suspend fun readInCurrentTransaction(
		exact: StepsNumericExactDecisionRequest,
	): StepsNumericWindowDraft {
		val zoneByDay = linkedMapOf<Long, ZoneId>()
		for (day in exact.calendarAuthority.days) {
			zoneByDay[day.epochDay] = parseZone(day.zoneId)
				?: return StepsNumericWindowDraft(
					decisionWindow(
						exact.request,
						calendarUnavailable(),
						exact.calendarAuthority,
					),
					baseComposition = null,
				)
		}
		if (stepsNumericReadQueryBounds(zoneByDay) == null) {
			return StepsNumericWindowDraft(
				decisionWindow(
					exact.request,
					calendarUnavailable(),
					exact.calendarAuthority,
				),
				baseComposition = null,
			)
		}
		val composition = StepsDailySummaryRepairComposer(database).composeForNumericRead(zoneByDay)
		return StepsNumericWindowDraft(
			decisionWindow(
				exact.request,
				composition.toNumericSummary(exact.request, zoneByDay),
				exact.calendarAuthority,
			),
			composition,
		)
	}

	@Suppress("LongMethod", "ReturnCount")
	private suspend fun readInCurrentTransaction(
		request: StepsNumericSummaryRequest,
	): StepsNumericWindowDraft {
		val summaries = database.dailySummaryDao().getBetween(
			request.firstEpochDay,
			request.lastEpochDayInclusive,
		)
		val summariesByDay = summaries.associateBy(DailySummaryEntity::dateEpochDay)
		if (summariesByDay.size != summaries.size || summariesByDay.keys.any { day ->
			day !in request.firstEpochDay..request.lastEpochDayInclusive
		}) {
			return StepsNumericWindowDraft(
				decisionWindow(
					request,
					calendarUnavailable(),
					StepsNumericCalendarAuthority.Unavailable,
				),
				baseComposition = null,
			)
		}
		val zoneByDay = linkedMapOf<Long, ZoneId>()
		for (epochDay in request.firstEpochDay..request.lastEpochDayInclusive) {
			val storedZone = summariesByDay[epochDay]?.let { summary ->
				parseZone(
					summary.calendarZoneId
						?: return StepsNumericWindowDraft(
							decisionWindow(
								request,
								calendarUnavailable(),
								StepsNumericCalendarAuthority.Unavailable,
							),
							baseComposition = null,
						),
				) ?: return StepsNumericWindowDraft(
					decisionWindow(
						request,
						calendarUnavailable(),
						StepsNumericCalendarAuthority.Unavailable,
					),
					baseComposition = null,
				)
			}
			zoneByDay[epochDay] = storedZone
				?: parseZone(request.fallbackCalendarZoneId)
				?: return StepsNumericWindowDraft(
					decisionWindow(
						request,
						calendarUnavailable(),
						StepsNumericCalendarAuthority.Unavailable,
					),
					baseComposition = null,
				)
		}
		val authority = StepsNumericCalendarAuthority.Exact(
			zoneByDay.map { (epochDay, zoneId) ->
				StepsNumericCalendarDay(epochDay, zoneId.id)
			},
		)
		if (stepsNumericReadQueryBounds(zoneByDay) == null) {
			return StepsNumericWindowDraft(
				decisionWindow(request, calendarUnavailable(), authority),
				baseComposition = null,
			)
		}
		val composition = StepsDailySummaryRepairComposer(database).composeForNumericRead(zoneByDay)
		return StepsNumericWindowDraft(
			decisionWindow(request, composition.toNumericSummary(request, zoneByDay), authority),
			composition,
		)
	}

	private fun requireValidBatch(requests: List<StepsNumericSummaryRequest>) {
		require(requests.size in 1..StepsNumericSummaryBatch.MAX_SUMMARY_COUNT) {
			"Steps numeric summary batch requires one or two requested windows"
		}
	}

	private suspend fun mergeAmbientStepsWindows(
		drafts: List<StepsNumericWindowDraft>,
		expectedSourceEvidenceRevision: Long,
	): List<StepsNumericDecisionWindow>? {
		val structuralDays = drafts.flatMap { draft ->
			val window = draft.window
			(window.calendarAuthority as? StepsNumericCalendarAuthority.Exact)
				?.days
				.orEmpty()
				.map { AmbientStepsStructuralDay(it.epochDay, it.zoneId) }
		}.distinct().sortedWith(
			compareBy(AmbientStepsStructuralDay::epochDay)
				.thenBy(AmbientStepsStructuralDay::storedZoneId),
		)
		if (structuralDays.isEmpty()) return drafts.map(StepsNumericWindowDraft::window)
		val ambient = try {
			ambientStepsReader.readNumericRangeInCurrentTransaction(
				AmbientStepsHistoryRangeRequest(structuralDays),
				expectedSourceEvidenceRevision,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			return null
		} catch (_: IllegalArgumentException) {
			return drafts.map { it.window.copyWithSummary(sourceEvidenceUnavailable()) }
		} catch (_: IllegalStateException) {
			return drafts.map { it.window.copyWithSummary(sourceEvidenceUnavailable()) }
		} catch (_: ArithmeticException) {
			return drafts.map { it.window.copyWithSummary(sourceEvidenceUnavailable()) }
		}
		if (ambient == AmbientStepsNumericRangeRead.StorageUnavailable) return null
		if (ambient is AmbientStepsNumericRangeRead.Snapshot &&
			ambient.sourceEvidenceRevision != expectedSourceEvidenceRevision
		) {
			return drafts.map { it.window.copyWithSummary(sourceEvidenceUnavailable()) }
		}
		return drafts.map { draft ->
			draft.withAmbientSteps(ambient)
		}
	}

	private fun storageUnavailableBatch(size: Int) = StepsNumericSummaryBatch(
		summaries = List(size) { storageUnavailable() },
	)

	private fun StepsNumericDecisionBatch.toSummaryBatch(size: Int): StepsNumericSummaryBatch =
		when (this) {
			is StepsNumericDecisionBatch.Snapshot -> {
				check(windows.size == size) { "Steps decision batch changed requested window count" }
				StepsNumericSummaryBatch(windows.map(StepsNumericDecisionWindow::summary))
			}
			StepsNumericDecisionBatch.StorageUnavailable -> storageUnavailableBatch(size)
		}

	private fun parseZone(zoneId: String): ZoneId? = try {
		ZoneId.of(zoneId)
	} catch (_: DateTimeException) {
		null
	}

	private fun calendarUnavailable() = StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
	)

	private fun storageUnavailable() = StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE,
	)

	private fun decisionWindow(
		request: StepsNumericSummaryRequest,
		summary: StepsNumericSummary,
		calendarAuthority: StepsNumericCalendarAuthority,
	): StepsNumericDecisionWindow = StepsNumericDecisionWindow(
		request = request,
		summary = summary,
		calendarAuthority = calendarAuthority,
		sourceResultDigest = stepsSourceResultDigest(request, calendarAuthority, summary),
	)

	internal companion object {
		val NUMERIC_SUMMARY_DEPENDENCY_TABLES = arrayOf(
			"daily_summary",
			"logical_tracking_session",
			"source_service_run",
			"session_manifest_version",
			"session_manifest_source",
			"source_policy_authority",
			"source_policy",
			"source_consent_epoch",
			"source_destination_owner",
			"source_authorization",
			"source_demand",
			"source_registration_state",
			"source_session_completeness",
			"source_product_projection_lane",
			"source_projection_failure",
			"source_evidence_state",
			"source_deletion_fence",
			"step_fact_revision",
			"session_segment",
			"imported_steps_entry",
			"imported_steps_run",
			"imported_steps_manifest",
			"import_job_receipt",
			"import_entry_receipt",
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
		)
	}
}

private data class StepsNumericWindowDraft(
	val window: StepsNumericDecisionWindow,
	val baseComposition: StepsDayRepairPreflight?,
)

private fun StepsNumericWindowDraft.withAmbientSteps(
	ambient: AmbientStepsNumericRangeRead,
): StepsNumericDecisionWindow {
	if (baseComposition == null) return window
	val decision = window
	val calendar = decision.calendarAuthority as? StepsNumericCalendarAuthority.Exact
		?: return decision
	val merged = when (ambient) {
		is AmbientStepsNumericRangeRead.Snapshot -> {
			val byDay = ambient.days.associateBy { day ->
				AmbientStepsStructuralDay(day.day.epochDay, day.day.storedZoneId)
			}
			val selected = calendar.days.map { day ->
				byDay[AmbientStepsStructuralDay(day.epochDay, day.zoneId)]
					?: return decision.copyWithSummary(sourceEvidenceUnavailable())
			}
			mergeAmbientNumericDays(baseComposition, selected)
		}
		is AmbientStepsNumericRangeRead.DependencyOverflow,
		is AmbientStepsNumericRangeRead.Unavailable,
		-> sourceEvidenceUnavailable()
		AmbientStepsNumericRangeRead.StorageUnavailable -> return decision
	}
	return decision.copyWithSummary(merged)
}

private fun StepsNumericDecisionWindow.copyWithSummary(
	replacement: StepsNumericSummary,
): StepsNumericDecisionWindow = copy(
	summary = replacement,
	sourceResultDigest = stepsSourceResultDigest(request, calendarAuthority, replacement),
)

private object NoAmbientStepsNumericRangeReader : AmbientStepsNumericRangeReader {
	override suspend fun readNumericRangeInCurrentTransaction(
		request: AmbientStepsHistoryRangeRequest,
		expectedSourceEvidenceRevision: Long,
	): AmbientStepsNumericRangeRead {
		val days = request.days.map { day ->
			val zone = try {
				ZoneId.of(day.storedZoneId)
			} catch (_: DateTimeException) {
				return AmbientStepsNumericRangeRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
				)
			}
			val date = try {
				LocalDate.ofEpochDay(day.epochDay)
			} catch (_: DateTimeException) {
				return AmbientStepsNumericRangeRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
				)
			}
			try {
				date.atStartOfDay(zone).toInstant().toEpochMilli()
			} catch (_: DateTimeException) {
				return AmbientStepsNumericRangeRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
				)
			} catch (_: ArithmeticException) {
				return AmbientStepsNumericRangeRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
				)
			}
			try {
				date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
			} catch (_: DateTimeException) {
				return AmbientStepsNumericRangeRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
				)
			} catch (_: ArithmeticException) {
				return AmbientStepsNumericRangeRead.Unavailable(
					AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
				)
			}
			val unavailable = AmbientStepsHistoryValue.Unavailable(
				setOf(AmbientStepsHistoryCause.NO_EVIDENCE),
			)
			AmbientStepsNumericHistoryDay(
				day = day,
				total = unavailable,
			)
		}
		return AmbientStepsNumericRangeRead.Snapshot(expectedSourceEvidenceRevision, days)
	}
}

internal fun stepsSourceResultDigest(
	request: StepsNumericSummaryRequest,
	calendarAuthority: StepsNumericCalendarAuthority,
	summary: StepsNumericSummary,
): String {
	val buffer = ByteArrayOutputStream()
	DataOutputStream(buffer).use { output ->
		output.writeInt(STEPS_SOURCE_RESULT_DIGEST_VERSION)
		output.writeLong(request.firstEpochDay)
		output.writeLong(request.lastEpochDayInclusive)
		when (calendarAuthority) {
			is StepsNumericCalendarAuthority.Exact -> {
				output.writeByte(CALENDAR_EXACT_TAG)
				output.writeInt(calendarAuthority.days.size)
				calendarAuthority.days.forEach { day ->
					output.writeLong(day.epochDay)
					output.writeSizedUtf8(day.zoneId)
				}
			}
			StepsNumericCalendarAuthority.Unavailable -> {
				output.writeByte(CALENDAR_UNAVAILABLE_TAG)
				output.writeSizedUtf8(request.fallbackCalendarZoneId)
			}
		}
		when (summary) {
			is StepsNumericSummary.Ready -> {
				output.writeByte(SUMMARY_READY_TAG)
				output.writeInt(summary.days.size)
				summary.days.forEach { day ->
					output.writeLong(day.epochDay)
					output.writeLong(day.steps)
				}
			}
			StepsNumericSummary.Materializing -> output.writeByte(SUMMARY_MATERIALIZING_TAG)
			is StepsNumericSummary.Unverifiable -> {
				output.writeByte(SUMMARY_UNVERIFIABLE_TAG)
				output.writeSizedUtf8(summary.reason.name)
			}
		}
	}
	return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()).toLowerHex()
}

private fun DataOutputStream.writeSizedUtf8(value: String) {
	val bytes = value.toByteArray(StandardCharsets.UTF_8)
	writeInt(bytes.size)
	write(bytes)
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
	for (byte in this@toLowerHex) {
		val unsigned = byte.toInt() and 0xff
		append(HEX_DIGITS[unsigned ushr 4])
		append(HEX_DIGITS[unsigned and 0x0f])
	}
}

private const val STEPS_SOURCE_RESULT_DIGEST_VERSION = 1
private const val CALENDAR_EXACT_TAG = 1
private const val CALENDAR_UNAVAILABLE_TAG = 2
private const val SUMMARY_READY_TAG = 1
private const val SUMMARY_MATERIALIZING_TAG = 2
private const val SUMMARY_UNVERIFIABLE_TAG = 3
private const val HEX_DIGITS = "0123456789abcdef"

internal fun StepsDayRepairPreflight.toNumericSummary(
	request: StepsNumericSummaryRequest,
	zoneByDay: Map<Long, ZoneId>,
): StepsNumericSummary = when (this) {
	is StepsDayRepairPreflight.Ready -> plans.toNumericSummary(request, zoneByDay)
	StepsDayRepairPreflight.Materializing -> StepsNumericSummary.Materializing
	is StepsDayRepairPreflight.Unsupported -> StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
	)
}

private fun List<StepsDayRepairPlan>.toNumericSummary(
	request: StepsNumericSummaryRequest,
	zoneByDay: Map<Long, ZoneId>,
): StepsNumericSummary {
	val expectedDays = (request.firstEpochDay..request.lastEpochDayInclusive).toList()
	val orderedPlans = sortedBy { plan -> plan.epochDay }
	if (orderedPlans.map { plan -> plan.epochDay } != expectedDays ||
		orderedPlans.any { plan -> zoneByDay[plan.epochDay] != plan.zoneId }
	) {
		return sourceEvidenceUnavailable()
	}
	return orderedPlans.summarizeNumericPlans()
}

private fun List<StepsDayRepairPlan>.summarizeNumericPlans(): StepsNumericSummary = when {
	any { plan -> plan.numericSteps == StepsDayNumericComposition.PartialCapture } ->
		StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
	all { plan -> plan.numericSteps == StepsDayNumericComposition.NotCaptured } ->
		StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED)
	any { plan -> plan.numericSteps == StepsDayNumericComposition.NotCaptured } ->
		StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
	else -> completedNumericSummary()
}

private fun List<StepsDayRepairPlan>.completedNumericSummary(): StepsNumericSummary {
	val days = mapNotNull { plan ->
		(plan.numericSteps as? StepsDayNumericComposition.Complete)?.let { complete ->
			StepsNumericDay(plan.epochDay, complete.steps)
		}
	}
	return if (days.size == size) {
		StepsNumericSummary.Ready(days)
	} else {
		sourceEvidenceUnavailable()
	}
}

private fun sourceEvidenceUnavailable() = StepsNumericSummary.Unverifiable(
	StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
)
