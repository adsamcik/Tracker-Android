package com.adsamcik.tracker.tracker.source.summary

import android.database.sqlite.SQLiteException
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.AmbientStepsDisplayStructuralDayRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryValue
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericHistoryDay
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericRangeRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericRangeReader
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericStructuralDayRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayDay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayValue
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
import com.adsamcik.tracker.tracker.source.deletion.StepsRetainedDayAuthorityDiscovery
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

	override fun observeDisplay(request: StepsNumericSummaryRequest): Flow<StepsDailyDisplay> =
		observedInvalidations()
			.map { readDisplay(request) }
			.catch { failure ->
				if (failure is CancellationException) throw failure
				emit(StepsDailyDisplay.StorageUnavailable)
			}
			.distinctUntilChanged()

	override suspend fun readDisplay(request: StepsNumericSummaryRequest): StepsDailyDisplay =
		withContext(ioDispatcher) {
			try {
				database.useReaderConnection { connection ->
					connection.deferredTransaction<StepsDailyDisplay> { readDisplayInCurrentTransaction(request) }
				}
			} catch (cancellation: CancellationException) {
				currentCoroutineContext().ensureActive()
				if (database.isOpen) throw cancellation
				StepsDailyDisplay.StorageUnavailable
			} catch (_: Exception) {
				StepsDailyDisplay.StorageUnavailable
			}
		}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun readDisplayInCurrentTransaction(
		request: StepsNumericSummaryRequest,
	): StepsDailyDisplay {
		val evidence = database.sourceEvidenceStateDao().get() ?: return StepsDailyDisplay.StorageUnavailable
		val summaries = database.dailySummaryDao().getBetween(request.firstEpochDay, request.lastEpochDayInclusive)
		val summariesByDay = summaries.groupBy(DailySummaryEntity::dateEpochDay)
		val composer = StepsDailySummaryRepairComposer(database)
		val sessionDiscovery = composer.discoverRetainedDayAuthorities(
			request.firstEpochDay, request.lastEpochDayInclusive, evidence.retainedFromMs,
		)
		val sessionZones = (sessionDiscovery as? StepsRetainedDayAuthorityDiscovery.Ready)
			?.zoneByDay.orEmpty().toMutableMap()
		val unverifiableSessionDays = linkedSetOf<Long>()
		// An unrelated damaged run must not hide independent days. Retry only after the shared
		// discovery fails, retaining its full qualifiers and this same Room snapshot. A damaged
		// multi-day run still blocks every day that depends on its authority.
		if (sessionDiscovery is StepsRetainedDayAuthorityDiscovery.Unverifiable) {
			for (day in request.firstEpochDay..request.lastEpochDayInclusive) {
				when (val discovered = composer.discoverRetainedDayAuthorities(day, day, evidence.retainedFromMs)) {
					is StepsRetainedDayAuthorityDiscovery.Ready -> sessionZones.putAll(discovered.zoneByDay)
					StepsRetainedDayAuthorityDiscovery.Unverifiable -> unverifiableSessionDays += day
				}
			}
		}
		val invalidDays = linkedMapOf<Long, StepsDailyDisplayValue.Unavailable>()
		val storedCandidates = mutableListOf<AmbientStepsStructuralDay>()
		for (epochDay in request.firstEpochDay..request.lastEpochDayInclusive) {
			val stored = summariesByDay[epochDay].orEmpty()
			if (stored.size > 1 || stored.any { it.calendarZoneId?.let(::parseZone) == null }) {
				invalidDays[epochDay] = displayCalendarUnavailable()
				continue
			}
			stored.singleOrNull()?.calendarZoneId?.let { zone ->
				storedCandidates += AmbientStepsStructuralDay(epochDay, zone)
			}
			sessionZones[epochDay]?.let { zone -> storedCandidates += AmbientStepsStructuralDay(epochDay, zone.id) }
		}
		val discovery = ambientStepsReader.discoverDisplayStructuralDaysInCurrentTransaction(
			request.firstEpochDay, request.lastEpochDayInclusive, storedCandidates.distinct().sortedWith(
				compareBy(AmbientStepsStructuralDay::epochDay).thenBy(AmbientStepsStructuralDay::storedZoneId),
			), evidence.revision,
		)
		val discoveredByDay = when (discovery) {
			is AmbientStepsDisplayStructuralDayRead.Snapshot -> {
				if (discovery.sourceEvidenceRevision != evidence.revision ||
					discovery.days.any { it.epochDay !in request.firstEpochDay..request.lastEpochDayInclusive } ||
					discovery.calendarConflictEpochDays.any { it !in request.firstEpochDay..request.lastEpochDayInclusive }
				) return displayUnavailableSnapshot(request, evidence.revision)
				discovery.calendarConflictEpochDays.forEach { invalidDays[it] = displayCalendarUnavailable() }
				discovery.days.associateBy(AmbientStepsStructuralDay::epochDay)
			}
			is AmbientStepsDisplayStructuralDayRead.Unavailable -> return displayUnavailableSnapshot(
				request, evidence.revision,
				if (discovery.reason == AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE) {
					StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE
				} else StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)
			AmbientStepsDisplayStructuralDayRead.StorageUnavailable -> return StepsDailyDisplay.StorageUnavailable
		}
		val zoneByDay = linkedMapOf<Long, ZoneId>()
		val boundsByDay = linkedMapOf<Long, Pair<Long, Long>>()
		for (epochDay in request.firstEpochDay..request.lastEpochDayInclusive) {
			if (epochDay in invalidDays) continue
			val stored = discoveredByDay[epochDay]?.storedZoneId ?: sessionZones[epochDay]?.id
				?: summariesByDay[epochDay]?.singleOrNull()?.calendarZoneId
			val zone = parseZone(stored ?: request.fallbackCalendarZoneId)
			val bounds = zone?.let { stepsNumericReadQueryBounds(mapOf(epochDay to it)) }
			if (zone == null || bounds == null) {
				invalidDays[epochDay] = displayCalendarUnavailable()
			} else {
				zoneByDay[epochDay] = zone
				boundsByDay[epochDay] = bounds.fromMs to bounds.toMs
			}
		}
		// Overlapping travel days cannot both claim the same real count interval. Only the involved
		// days lose their number; valid neighbours are still read in this one bounded transaction.
		val orderedBounds = boundsByDay.entries.sortedBy { it.value.first }
		for (leftIndex in orderedBounds.indices) {
			val left = orderedBounds[leftIndex]
			for (rightIndex in leftIndex + 1 until orderedBounds.size) {
				val right = orderedBounds[rightIndex]
				if (right.value.first >= left.value.second) break
				invalidDays[left.key] = displayCalendarUnavailable()
				invalidDays[right.key] = displayCalendarUnavailable()
			}
		}
		invalidDays.keys.forEach { zoneByDay.remove(it) }
		val structuralDays = zoneByDay.map { (day, zone) -> AmbientStepsStructuralDay(day, zone.id) }
		val ambient = if (structuralDays.isEmpty()) null else ambientStepsReader.readNumericRangeInCurrentTransaction(
			AmbientStepsHistoryRangeRequest(structuralDays), evidence.revision,
		)
		if (ambient == AmbientStepsNumericRangeRead.StorageUnavailable) return StepsDailyDisplay.StorageUnavailable
		if (ambient is AmbientStepsNumericRangeRead.Snapshot && ambient.sourceEvidenceRevision != evidence.revision) {
			return displayUnavailableSnapshot(request, evidence.revision)
		}
		val ambientByDay = (ambient as? AmbientStepsNumericRangeRead.Snapshot)?.days.orEmpty().associateBy { it.day }
		val sessionReadZones = zoneByDay.filter { (day, _) ->
			evidence.retainedFromMs?.let { floor -> boundsByDay.getValue(day).first < floor } != true
		}
		val sessionBase = if (sessionReadZones.isEmpty() || sessionDiscovery is StepsRetainedDayAuthorityDiscovery.Unverifiable) {
			null
		} else composer.composeForDisplayRead(sessionReadZones)
		val sessionBasesByDay = if (sessionDiscovery is StepsRetainedDayAuthorityDiscovery.Unverifiable ||
			sessionBase is StepsDayRepairPreflight.Unsupported
		) {
			sessionReadZones.mapValues { (day, zone) ->
				if (day in unverifiableSessionDays) null else composer.composeForDisplayRead(mapOf(day to zone))
			}
		} else sessionReadZones.mapValues { sessionBase }
		val days = (request.firstEpochDay..request.lastEpochDayInclusive).map { day ->
			val zone = zoneByDay[day]
			val value = invalidDays[day] ?: if (zone == null) displayCalendarUnavailable() else {
				val source = ambientByDay[AmbientStepsStructuralDay(day, zone.id)]?.total
				val sessionDayBase = sessionBasesByDay[day]
				val base = (sessionDayBase as? StepsDayRepairPreflight.Ready)?.plans
					?.singleOrNull { it.epochDay == day && it.zoneId == zone }?.numericSteps
				if (source is AmbientStepsHistoryValue.Unavailable &&
					source.causes == setOf(AmbientStepsHistoryCause.NO_EVIDENCE) &&
					evidence.retainedFromMs?.let { floor -> boundsByDay.getValue(day).first < floor } == true
				) StepsDailyDisplayValue.Unavailable(StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					setOf(AmbientStepsHistoryCause.RETAINED))
				else displayDayValue(source, ambient, base, sessionDayBase,
					day in (sessionDayBase as? StepsDayRepairPreflight.Ready)?.materializingEpochDays.orEmpty())
			}
			StepsDailyDisplayDay(day, value)
		}
		check(database.sourceEvidenceStateDao().get()?.revision == evidence.revision) {
			"Source evidence changed during a Steps display snapshot"
		}
		return StepsDailyDisplay.Snapshot(evidence.revision, days)
	}

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
			val baseWindows = requests.map { request ->
				readInCurrentTransaction(request, before.revision)
			}
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
		expectedSourceEvidenceRevision: Long,
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
		val evidence = database.sourceEvidenceStateDao().get()
			?: return StepsNumericWindowDraft(
				decisionWindow(
					request,
					storageUnavailable(),
					StepsNumericCalendarAuthority.Unavailable,
				),
				baseComposition = null,
			)
		val sessionAuthority = when (
			val discovery = StepsDailySummaryRepairComposer(database).discoverRetainedDayAuthorities(
				request.firstEpochDay,
				request.lastEpochDayInclusive,
				evidence.retainedFromMs,
			)
		) {
			is StepsRetainedDayAuthorityDiscovery.Ready -> discovery.zoneByDay
			StepsRetainedDayAuthorityDiscovery.Unverifiable ->
				return StepsNumericWindowDraft(
					decisionWindow(
						request,
						sourceEvidenceUnavailable(),
						StepsNumericCalendarAuthority.Unavailable,
					),
					baseComposition = null,
				)
		}
		val persistedSessionZones = mutableListOf<AmbientStepsStructuralDay>()
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
			storedZone?.let { zone ->
				persistedSessionZones += AmbientStepsStructuralDay(epochDay, zone.id)
			}
			sessionAuthority[epochDay]?.let { zone ->
				persistedSessionZones += AmbientStepsStructuralDay(epochDay, zone.id)
			}
		}
		if (persistedSessionZones.groupBy(AmbientStepsStructuralDay::epochDay)
				.values.any { days -> days.map(AmbientStepsStructuralDay::storedZoneId).distinct().size != 1 }
		) {
			return StepsNumericWindowDraft(
				decisionWindow(
					request,
					calendarUnavailable(),
					StepsNumericCalendarAuthority.Unavailable,
				),
				baseComposition = null,
			)
		}
		val uniqueSessionZones = persistedSessionZones.distinct().sortedWith(
			compareBy(AmbientStepsStructuralDay::epochDay)
				.thenBy(AmbientStepsStructuralDay::storedZoneId),
		)
		val discovery = ambientStepsReader.discoverNumericStructuralDaysInCurrentTransaction(
			request.firstEpochDay,
			request.lastEpochDayInclusive,
			uniqueSessionZones,
			expectedSourceEvidenceRevision,
		)
		val discoveredByDay = when (discovery) {
			is AmbientStepsNumericStructuralDayRead.Exact -> {
				if (discovery.sourceEvidenceRevision != expectedSourceEvidenceRevision) {
					return StepsNumericWindowDraft(
						decisionWindow(
							request,
							sourceEvidenceUnavailable(),
							StepsNumericCalendarAuthority.Unavailable,
						),
						baseComposition = null,
					)
				}
				discovery.days.associateBy(AmbientStepsStructuralDay::epochDay)
			}
			AmbientStepsNumericStructuralDayRead.NoAmbientAuthority -> {
				emptyMap()
			}
			is AmbientStepsNumericStructuralDayRead.Unavailable ->
				return StepsNumericWindowDraft(
					decisionWindow(
						request,
						if (discovery.reason ==
							AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE
						) calendarUnavailable() else sourceEvidenceUnavailable(),
						StepsNumericCalendarAuthority.Unavailable,
					),
					baseComposition = null,
				)
			AmbientStepsNumericStructuralDayRead.StorageUnavailable ->
				return StepsNumericWindowDraft(
					decisionWindow(
						request,
						storageUnavailable(),
						StepsNumericCalendarAuthority.Unavailable,
					),
					baseComposition = null,
				)
		}
		val structuralDays = (request.firstEpochDay..request.lastEpochDayInclusive).map { epochDay ->
			discoveredByDay[epochDay] ?: run {
				val zone = sessionAuthority[epochDay]
					?: summariesByDay[epochDay]?.calendarZoneId?.let(::parseZone)
					?: parseZone(request.fallbackCalendarZoneId)
					?: return StepsNumericWindowDraft(
						decisionWindow(
							request,
							calendarUnavailable(),
							StepsNumericCalendarAuthority.Unavailable,
						),
						baseComposition = null,
					)
				AmbientStepsStructuralDay(epochDay, zone.id)
			}
		}
		val zoneByDay = linkedMapOf<Long, ZoneId>()
		structuralDays.forEach { day ->
			zoneByDay[day.epochDay] = parseZone(day.storedZoneId)
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
			"provider_registration_generation",
			"ambient_steps_retention_authority",
			"ambient_steps_native_replay_footprint",
			"steps_count_domain_receipt",
			"steps_count_domain_owner_revision",
			"steps_count_domain_completeness_marker",
			"steps_count_domain_schema_marker",
			"imported_steps_count_domain_graph",
			"imported_steps_count_domain_receipt",
			"imported_steps_count_domain_owner_revision",
			"imported_steps_count_domain_completeness",
			"imported_steps_count_domain_root",
			"imported_steps_count_domain_binding",
			"imported_steps_count_domain_owner_fence",
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
		is AmbientStepsNumericRangeRead.DependencyOverflow -> sourceEvidenceUnavailable()
		is AmbientStepsNumericRangeRead.Unavailable ->
			if (ambient.reason == AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE) {
				StepsNumericSummary.Unverifiable(
					StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
				)
			} else {
				sourceEvidenceUnavailable()
			}
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
	override suspend fun discoverNumericStructuralDaysInCurrentTransaction(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		sessionCalendarDays: List<AmbientStepsStructuralDay>,
		expectedSourceEvidenceRevision: Long,
	): AmbientStepsNumericStructuralDayRead =
		AmbientStepsNumericStructuralDayRead.NoAmbientAuthority

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
	is StepsDayRepairPreflight.Ready -> if (materializingEpochDays.isNotEmpty()) StepsNumericSummary.Materializing
		else plans.toNumericSummary(request, zoneByDay)
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

private fun displayCalendarUnavailable() = StepsDailyDisplayValue.Unavailable(
	StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
	setOf(AmbientStepsHistoryCause.STRUCTURAL_AUTHORITY_CONFLICT),
)

private fun displayUnavailableSnapshot(
	request: StepsNumericSummaryRequest,
	revision: Long,
	reason: StepsNumericUnverifiableReason = StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
) = StepsDailyDisplay.Snapshot(
	revision,
	(request.firstEpochDay..request.lastEpochDayInclusive).map { day ->
		StepsDailyDisplayDay(day, StepsDailyDisplayValue.Unavailable(reason))
	},
)

private fun displayDayValue(
	ambient: AmbientStepsHistoryValue?,
	ambientRead: AmbientStepsNumericRangeRead?,
	session: StepsDayNumericComposition?,
	base: StepsDayRepairPreflight?,
	dayMaterializing: Boolean,
): StepsDailyDisplayValue {
	when (ambient) {
		is AmbientStepsHistoryValue.Exact -> return StepsDailyDisplayValue.Complete(ambient.count)
		is AmbientStepsHistoryValue.Partial -> return if (ambient.causes.any { it in UNSAFE_DISPLAY_COUNT_CAUSES }) {
			StepsDailyDisplayValue.Unavailable(StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE, ambient.causes)
		} else StepsDailyDisplayValue.Recorded(ambient.count, ambient.causes)
		is AmbientStepsHistoryValue.Unavailable -> {
			if (ambient.causes != setOf(AmbientStepsHistoryCause.NO_EVIDENCE)) {
				return if (ambient.causes == setOf(AmbientStepsHistoryCause.MATERIALIZING)) {
					StepsDailyDisplayValue.Materializing
				} else StepsDailyDisplayValue.Unavailable(
					if (ambient.causes.any { it == AmbientStepsHistoryCause.EXPLICIT_GAP ||
						it == AmbientStepsHistoryCause.PARTIAL_COVERAGE }) {
						StepsNumericUnverifiableReason.PARTIAL_CAPTURE
					} else StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					ambient.causes,
				)
			}
		}
		null -> return StepsDailyDisplayValue.Unavailable(
			if ((ambientRead as? AmbientStepsNumericRangeRead.Unavailable)?.reason ==
				AmbientStepsHistoryUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE) {
				StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE
			} else StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
	}
	return when (session) {
		is StepsDayNumericComposition.Complete -> if (dayMaterializing) {
			StepsDailyDisplayValue.Recorded(session.steps, setOf(AmbientStepsHistoryCause.MATERIALIZING))
		} else StepsDailyDisplayValue.Complete(session.steps)
		StepsDayNumericComposition.NotCaptured -> if (dayMaterializing) {
			StepsDailyDisplayValue.Materializing
		} else StepsDailyDisplayValue.Unavailable(StepsNumericUnverifiableReason.NOT_CAPTURED)
		StepsDayNumericComposition.PartialCapture -> StepsDailyDisplayValue.Unavailable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
		null -> if (base == StepsDayRepairPreflight.Materializing) StepsDailyDisplayValue.Materializing else
			StepsDailyDisplayValue.Unavailable(StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}
}

private val UNSAFE_DISPLAY_COUNT_CAUSES = setOf(
	AmbientStepsHistoryCause.FACT_OVERLAP,
	AmbientStepsHistoryCause.ORIGIN_IDENTITY_CONFLICT,
	AmbientStepsHistoryCause.COUNT_OVERFLOW,
	AmbientStepsHistoryCause.STRUCTURAL_AUTHORITY_CONFLICT,
	AmbientStepsHistoryCause.SOURCE_AUTHORITY_UNVERIFIABLE,
	AmbientStepsHistoryCause.SESSION_OVERLAP,
	AmbientStepsHistoryCause.SESSION_OWNERSHIP_UNVERIFIABLE,
	AmbientStepsHistoryCause.SESSION_COUNT_EXCEEDS_TOTAL,
)
