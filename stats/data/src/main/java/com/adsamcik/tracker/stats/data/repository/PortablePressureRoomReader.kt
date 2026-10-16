package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.PORTABLE_PRESSURE_ENTRY_ORDER
import com.adsamcik.tracker.stats.api.repository.PORTABLE_PRESSURE_RUN_ORDER
import com.adsamcik.tracker.stats.api.repository.PORTABLE_PRESSURE_WINDOW_ORDER
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Creates a bounded, fully authenticated Pressure transfer snapshot in one Room transaction.
 *
 * Local entries retain the live source selector's manifest, policy, writer, correction, deletion,
 * and retention authority. Portable-origin entries pass their independent imported-lineage and
 * local privacy evaluator. This adapter never converts either origin into the other's authority.
 */
@Singleton
// One Pressure transfer snapshot shares native/imported ownership qualification and typed wire projections.
@Suppress("TooManyFunctions")
internal class PortablePressureRoomReader @Inject constructor(
	private val database: AppDatabase,
	private val selector: PressureHistorySelector,
	private val importedEvaluator: ImportedPressureHistoryEvaluator,
) {
	internal constructor(
		database: AppDatabase,
		selector: PressureHistorySelector,
	) : this(database, selector, ImportedPressureHistoryEvaluator(database))

	suspend fun read(request: ExportPortablePressureRequest): PortablePressureSnapshot =
		database.withTransaction {
			try {
				readInTransaction(request)
			} catch (abort: PortablePressureSnapshotAbort) {
				PortablePressureSnapshot.Outcome(
					ExportPortablePressureResult.Unverifiable(abort.reason),
				)
			}
		}

	suspend fun read(request: ExportSelectedPortablePressureRequest): PortablePressureSnapshot =
		database.withTransaction {
			try {
				readSelectedInTransaction(request)
			} catch (abort: PortablePressureSnapshotAbort) {
				PortablePressureSnapshot.Outcome(ExportPortablePressureResult.Unverifiable(abort.reason))
			}
		}

	private suspend fun readSelectedInTransaction(
		request: ExportSelectedPortablePressureRequest,
	): PortablePressureSnapshot {
		currentCoroutineContext().ensureActive()
		val state = database.sourceEvidenceStateDao().get()
		if (state == null || state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
			abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		val entries = linkedMapOf<PortablePressureOpaqueIdentity, PortablePressureEntryV1>()
		var runCount = 0
		var windowCount = 0
		fun addEntry(entry: PortablePressureEntryV1) {
			val previous = entries[entry.identity]
			if (previous != null) {
				if (previous != entry) {
					abort(PortablePressureExportUnverifiableReason.CONFLICTING_ORIGIN_IDENTITY)
				}
				return
			}
			runCount += entry.runs.size
			windowCount += entry.runs.sumOf { it.windows.size }
			if (entries.size >= PressurePortableFormatV1.MAX_ENTRIES ||
				runCount > PressurePortableFormatV1.MAX_TOTAL_RUNS ||
				windowCount > PressurePortableFormatV1.MAX_TOTAL_WINDOWS) {
				abort(PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			entries[entry.identity] = entry
		}
		appendSelectedNativeEntries(request, ::addEntry)
		appendSelectedImportedEntries(request, ::addEntry)
		return portableSnapshot(entries.values)
	}

	private suspend fun appendSelectedNativeEntries(
		request: ExportSelectedPortablePressureRequest,
		addEntry: (PortablePressureEntryV1) -> Unit,
	) {
		for (selectedIds in request.nativeSegmentIds.distinct().chunked(CANDIDATE_PAGE_SIZE)) {
			currentCoroutineContext().ensureActive()
			val seeds = database.trackingHistoryReadDao().segments(selectedIds)
			if (seeds.map { it.id }.toSet() != selectedIds.toSet()) {
				abort(PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			val physical = selector.selectManyInTransaction(seeds,
				memberBudget = CANDIDATE_PAGE_SIZE * PressurePortableFormatV1.MAX_RUNS_PER_ENTRY)
			val logical = composeWithOriginalHeaders(physical)
			for (seed in seeds) {
				currentCoroutineContext().ensureActive()
				val entry = logical.singleOrNull { group -> group.physicalMembers.any { it.segment.id == seed.id } }
					?: abort(PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
				portableEntry(entry)?.let(addEntry)
			}
		}
	}

	private suspend fun appendSelectedImportedEntries(
		request: ExportSelectedPortablePressureRequest,
		addEntry: (PortablePressureEntryV1) -> Unit,
	) {
		for (selectedIds in request.importedEntryIdentities.distinct().chunked(CANDIDATE_PAGE_SIZE)) {
			currentCoroutineContext().ensureActive()
			val candidates = database.importedPressureDao().selectedHistoryCandidates(selectedIds.map { it.value })
			val byIdentity = candidates.groupBy { it.identity }
			if (byIdentity.keys != selectedIds.mapTo(hashSetOf()) { it.value } ||
                byIdentity.values.any { it.size != 1 }) {
				abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
			for (candidate in candidates) {
				currentCoroutineContext().ensureActive()
				when (val evaluation = importedEvaluator.evaluateCandidateInTransaction(candidate)) {
					is ImportedPressureHistoryEvaluation.Readable -> {
						requireCompatibleNativeOwner(evaluation)
						if (!evaluation.isReExportable) {
							abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
						}
						addEntry(evaluation.latest.entry)
					}
					is ImportedPressureHistoryEvaluation.Retained ->
                        abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
					is ImportedPressureHistoryEvaluation.Unverifiable ->
						abort(evaluation.reason.exportFailureReason())
				}
			}
		}
	}

	private fun ImportedPressureHistoryFailure.exportFailureReason(): PortablePressureExportUnverifiableReason =
		if (this == ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW) {
			PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW
		} else {
			PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE
		}

	private fun portableSnapshot(entries: Collection<PortablePressureEntryV1>): PortablePressureSnapshot =
		if (entries.isEmpty()) {
			PortablePressureSnapshot.Outcome(ExportPortablePressureResult.NoEntries)
		} else {
			PortablePressureSnapshot.Ready(entries.sortedWith(PORTABLE_PRESSURE_ENTRY_ORDER))
		}

	private suspend fun composeWithOriginalHeaders(
		physical: List<PressurePhysicalHistory>,
	): List<PressureLogicalHistoryEntry> {
		val logicalIds = physical.mapNotNull { it.segment.logicalTrackingId }.distinct()
		return PressureLogicalHistoryComposer.compose(physical,
			database.sourceSessionDao().sessions(logicalIds).associateBy { it.logicalTrackingId })
	}

	private suspend fun readInTransaction(
		request: ExportPortablePressureRequest,
	): PortablePressureSnapshot {
		val importedEntries = readImportedEntries(request)
		val entries = readNativeEntries(request)
		val localByIdentity = entries.associateBy { it.identity }
		importedEntries.forEach { importedEntry ->
			val local = localByIdentity[importedEntry.identity]
			when {
				local == null -> entries += importedEntry
				local == importedEntry -> Unit
				else -> abort(PortablePressureExportUnverifiableReason.CONFLICTING_ORIGIN_IDENTITY)
			}
		}
		if (entries.size > PressurePortableFormatV1.MAX_ENTRIES ||
			entries.sumOf { it.runs.size } > PressurePortableFormatV1.MAX_TOTAL_RUNS ||
			entries.sumOf { entry -> entry.runs.sumOf { it.windows.size } } >
			PressurePortableFormatV1.MAX_TOTAL_WINDOWS
		) {
			abort(PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}

		return portableSnapshot(entries)
	}

	private suspend fun readImportedEntries(
		request: ExportPortablePressureRequest,
	): List<PortablePressureEntryV1> {
		val imported = when (
			val selection = importedEvaluator.selectForExportInTransaction(request)
		) {
			is ImportedPressureHistorySelection.Available -> selection.evaluations
			is ImportedPressureHistorySelection.Unverifiable -> abort(selection.reason.exportFailureReason())
		}
		return imported.mapNotNull { evaluation ->
			when (evaluation) {
				is ImportedPressureHistoryEvaluation.Readable -> {
					requireCompatibleNativeOwner(evaluation)
					if (evaluation.isReExportable) {
						evaluation.latest.entry
					} else {
						null
					}
				}
				is ImportedPressureHistoryEvaluation.Retained -> null
				is ImportedPressureHistoryEvaluation.Unverifiable -> abort(evaluation.reason.exportFailureReason())
			}
		}
	}

	private suspend fun readNativeEntries(
		request: ExportPortablePressureRequest,
	): MutableList<PortablePressureEntryV1> {
		val entries = mutableListOf<PortablePressureEntryV1>()
		val exportedLogicalIds = hashSetOf<String>()
		var beforeRecencyStartMs: Long? = null
		var beforeRecencySegmentId: Long? = null
		var candidateCount = 0
		var runCount = 0
		var windowCount = 0
		fun addEntry(entry: PortablePressureEntryV1) {
			runCount += entry.runs.size
			windowCount += entry.runs.sumOf { it.windows.size }
			if (runCount > PressurePortableFormatV1.MAX_TOTAL_RUNS ||
				windowCount > PressurePortableFormatV1.MAX_TOTAL_WINDOWS
			) {
				abort(PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			entries += entry
		}

		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = PressurePortableFormatV1.MAX_ENTRIES - candidateCount
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, remaining + 1)
			val page = database.pressureFactRevisionDao().portablePressureLogicalHistoryCandidatePage(
				fromInclusiveMs = request.fromInclusiveMs,
				toExclusiveMs = request.toExclusiveMs,
				limit = pageLimit,
				beforeLogicalRecencyStartMs = beforeRecencyStartMs,
				beforeLogicalRecencySegmentId = beforeRecencySegmentId,
			)
			if (page.isEmpty()) {
				break
			}
			if (page.size > remaining) {
				abort(PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			validateCandidatePage(page, beforeRecencyStartMs, beforeRecencySegmentId)
			candidateCount += page.size

			appendNativeCandidatePage(page, exportedLogicalIds, ::addEntry)

			val last = page.last()
			beforeRecencyStartMs = last.logicalRecencyStartMs
			beforeRecencySegmentId = last.logicalRecencySegmentId
			if (page.size < pageLimit) {
				break
			}
		}

		return entries
	}

	private suspend fun appendNativeCandidatePage(
		page: List<com.adsamcik.tracker.shared.base.database.dao.PressureLogicalHistoryCandidate>,
		exportedLogicalIds: MutableSet<String>,
		addEntry: (PortablePressureEntryV1) -> Unit,
	) {
		val physical = selector.selectManyInTransaction(
			segments = page.map { it.segment },
			memberBudget = CANDIDATE_PAGE_SIZE * PressurePortableFormatV1.MAX_RUNS_PER_ENTRY,
		)
		val logicalById = composeWithOriginalHeaders(physical).mapNotNull { entry ->
			(entry.identity as? PressureHistoryEntryIdentity.Logical)?.let { identity ->
				identity.logicalTrackingId to entry
			}
		}.toMap()

		page.forEach { candidate ->
			currentCoroutineContext().ensureActive()
			val logicalId = candidate.segment.logicalTrackingId
				?.takeIf(String::isNotBlank)
				?: abort(PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			if (!exportedLogicalIds.add(logicalId)) {
				abort(PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			val logical = logicalById[logicalId]
				?: abort(PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			portableEntry(logical)?.let(addEntry)
		}
	}

	/** Complete exact owners are comparison inputs; they never widen a selected output. */
	internal suspend fun nativeEntriesForComparisonInTransaction(
		identities: Sequence<String>,
	): List<PressureLogicalHistoryEntry> {
		val seeds = database.nativePortableOwnerSeeds(
			com.adsamcik.tracker.shared.base.database.data.NativePortableOwnership.PRESSURE,
			identities,
			PRESSURE_HISTORY_SEGMENT_BATCH_CAP,
		)
		val entries = selector.selectLogicalBySegmentIdsInTransaction(seeds.map { it.id })
		if (entries.any { entry ->
				entry.physicalMembers.any { PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW in it.reasons }
			}) {
			throw NativePortableOwnerBudgetExceeded()
		}
		val logicalIds = entries.mapNotNull {
			(it.identity as? PressureHistoryEntryIdentity.Logical)?.logicalTrackingId
		}.toSet()
		if (logicalIds != seeds.map { it.logicalTrackingId }.toSet()) {
			throw NativePortableOwnerMembershipInvalid()
		}
		return entries
	}

	private suspend fun requireCompatibleNativeOwner(
		evaluation: ImportedPressureHistoryEvaluation.Readable,
	) {
		val imported = evaluation.latest.entry
		val owners = readNativeOwnersForComparison(imported)
		for (owner in owners) {
			val identity = (owner.identity as? PressureHistoryEntryIdentity.Logical)
				?: abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			val ownerId = PortablePressureOpaqueIdentity.derive(
				PortablePressureIdentityKind.LOGICAL_ENTRY, identity.logicalTrackingId)
			if (ownerId != imported.identity || !evaluation.isReExportable ||
				portableEntryForComparison(owner) != imported) {
				abort(PortablePressureExportUnverifiableReason.CONFLICTING_ORIGIN_IDENTITY)
			}
		}
	}

	private suspend fun readNativeOwnersForComparison(
		imported: PortablePressureEntryV1,
	): List<PressureLogicalHistoryEntry> {
		val keys = sequence {
			yield(imported.identity.value)
			for (run in imported.runs) {
				yield(run.identity.value)
				for (window in run.windows) yield(window.identity.value)
			}
		}
		return try {
			nativeEntriesForComparisonInTransaction(keys)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: NativePortableOwnerBudgetExceeded) {
			abort(PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW)
		} catch (overflow: PressureHistoryPageDependencyOverflow) {
			abort(when (overflow.reason) {
				com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT,
				com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason.LOGICAL_MEMBERSHIP_LIMIT,
				com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED ->
					PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW
				else -> PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE
			})
		} catch (_: RuntimeException) {
			abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
	}

	/** Pure exact-content comparison seam; failure can only prevent duplicate suppression. */
	internal fun portableEntryForComparison(
		entry: PressureLogicalHistoryEntry,
	): PortablePressureEntryV1? = try {
		portableEntry(entry)
	} catch (_: PortablePressureSnapshotAbort) {
		null
	}

	private fun portableEntry(entry: PressureLogicalHistoryEntry): PortablePressureEntryV1? {
		val identity = entry.identity as? PressureHistoryEntryIdentity.Logical
			?: abort(PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (entry.physicalMembers.any { it.materialization == PressureHistoryMaterialization.MATERIALIZING }) {
			abort(PortablePressureExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
		entry.physicalMembers.firstOrNull {
			it.materialization == PressureHistoryMaterialization.FAILED
		}?.let { failed -> abort(failed.exportFailureReason()) }

		val runs = entry.physicalMembers.map(::portableRun).sortedWith(PORTABLE_PRESSURE_RUN_ORDER)
		if (runs.none { it.windows.isNotEmpty() || it.retentionLoss }) {
			return null
		}
		if (entry.originalClosedBounds == null) {
			abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		return portableValue {
			PortablePressureEntryV1.create(
				identity = PortablePressureOpaqueIdentity.derive(
					PortablePressureIdentityKind.LOGICAL_ENTRY,
					identity.logicalTrackingId,
				),
				// The source file preserves original run bounds. Session organization is separate.
				startTimeMs = runs.minOf(PortablePressureRunV1::startTimeMs),
				endTimeMs = runs.maxOf(PortablePressureRunV1::endTimeMs),
				runs = runs,
			)
		}
	}

	private fun portableRun(history: PressurePhysicalHistory): PortablePressureRunV1 {
		val capture = history.captureAuthority as? HistoricalCaptureAuthority.Exact
			?: abort(PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val serviceRunId = history.segment.serviceRunId?.takeIf(String::isNotBlank)
			?: abort(PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val capturedForWholeRun = capture.revisions.all { revision ->
			TrackingSourceComponent.PRESSURE in revision.capturedSources
		}
		return portableValue {
			PortablePressureRunV1(
				identity = PortablePressureOpaqueIdentity.derive(
					PortablePressureIdentityKind.PHYSICAL_RUN,
					serviceRunId,
				),
				startTimeMs = history.segment.startTimeMs,
				endTimeMs = history.segment.endTimeMs,
				capturedForWholeRun = capturedForWholeRun,
				availability = history.portableAvailability(),
				coverage = history.coverage.toPortableCoverage(),
				retentionLoss = PressureHistoryReason.RETENTION_TRUNCATED in history.reasons,
				windows = history.windows.map(::portableWindow)
					.sortedWith(PORTABLE_PRESSURE_WINDOW_ORDER),
			)
		}
	}

	@Suppress("LongMethod")
	private fun portableWindow(window: PressureHistoryWindow): PortablePressureWindowV1 =
		portableValue {
			PortablePressureWindowV1.create(
				identity = PortablePressureOpaqueIdentity.derive(
					PortablePressureIdentityKind.WINDOW,
					com.adsamcik.tracker.shared.base.database.data.NativePortableOwnership.pressureWindowIdentity(
						window.writerProjectionId, window.writerProjectionVersion, window.logicalFactId),
				),
				intervalStartTimeMs = window.intervalStartTimeMs,
				intervalEndTimeMs = window.intervalEndTimeMs,
				wallTimeUncertaintyMs = window.wallTimeUncertaintyMs,
				observedDurationNanos = window.windowEndElapsedRealtimeNanos -
					window.windowStartElapsedRealtimeNanos,
				sampleCount = window.sampleCount,
				expectedSampleCount = window.expectedSampleCount,
				meanHectopascals = window.meanHectopascals,
				sumSquaredDeviations = window.sumSquaredDeviations,
				minimumHectopascals = window.minimumHectopascals,
				maximumHectopascals = window.maximumHectopascals,
				firstHectopascals = window.firstHectopascals,
				latestHectopascals = window.lastHectopascals,
				slopeHectopascalsPerSecond = window.slopeHectopascalsPerSecond,
				rSquared = window.rSquared,
				sensorAccuracy = window.sensorAccuracy.toPortableSensorAccuracy(),
				effectiveSamplePeriodMicros = window.effectiveSamplePeriodMicros,
				effectiveMaximumReportLatencyMicros =
					window.effectiveMaximumReportLatencyMicros,
				targetWindowDurationNanos = window.targetWindowDurationNanos,
				maximumInterSampleGapNanos = window.maximumInterSampleGapNanos,
				closure = window.closureKind.toPortableClosure(),
				qualification = window.qualification.toPortableQualification(),
				sourceQualityFlags = window.sourceQualityFlags,
				sourceQualityConfidence = window.sourceQualityConfidence,
				zoneId = window.zoneId,
			)
		}

	private fun PressurePhysicalHistory.portableAvailability(): PortablePressureAvailability =
		when (availability) {
			PressureHistoryAvailability.AVAILABLE -> if (windows.isEmpty()) {
				PortablePressureAvailability.NO_RETAINED_OBSERVATION
			} else {
				PortablePressureAvailability.RETAINED
			}
			PressureHistoryAvailability.DISABLED -> PortablePressureAvailability.DISABLED
			PressureHistoryAvailability.DELETED -> PortablePressureAvailability.DELETED
			PressureHistoryAvailability.UNAVAILABLE -> PortablePressureAvailability.UNAVAILABLE
		}

	private fun PressureHistoryCoverage.toPortableCoverage(): PortablePressureCoverage = when (this) {
		PressureHistoryCoverage.NONE -> PortablePressureCoverage.NONE
		PressureHistoryCoverage.COMPLETE -> PortablePressureCoverage.COMPLETE
		PressureHistoryCoverage.PARTIAL -> PortablePressureCoverage.PARTIAL
		PressureHistoryCoverage.UNKNOWN -> PortablePressureCoverage.UNKNOWN
	}

	private fun String.toPortableSensorAccuracy(): PortablePressureSensorAccuracy = when (this) {
		"UNKNOWN" -> PortablePressureSensorAccuracy.UNKNOWN
		"UNRELIABLE" -> PortablePressureSensorAccuracy.UNRELIABLE
		"LOW" -> PortablePressureSensorAccuracy.LOW
		"MEDIUM" -> PortablePressureSensorAccuracy.MEDIUM
		"HIGH" -> PortablePressureSensorAccuracy.HIGH
		else -> abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun String.toPortableClosure(): PortablePressureWindowClosure = when (this) {
		"TARGET_ELAPSED" -> PortablePressureWindowClosure.TARGET_ELAPSED
		"SOURCE_BOUNDARY" -> PortablePressureWindowClosure.SOURCE_BOUNDARY
		else -> abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun String.toPortableQualification(): PortablePressureWindowQualification = when (this) {
		"COMPLETE" -> PortablePressureWindowQualification.COMPLETE
		"PARTIAL" -> PortablePressureWindowQualification.PARTIAL
		else -> abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun PressurePhysicalHistory.exportFailureReason():
		PortablePressureExportUnverifiableReason = when {
		PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW in reasons ->
			PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW
		reasons.any { it in captureAttributionFailures } ->
			PortablePressureExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE
		else -> PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE
	}

	private fun validateCandidatePage(
		page: List<com.adsamcik.tracker.shared.base.database.dao.PressureLogicalHistoryCandidate>,
		beforeStartMs: Long?,
		beforeSegmentId: Long?,
	) {
		val cursors = buildList {
			if (beforeStartMs != null && beforeSegmentId != null) {
				add(beforeStartMs to beforeSegmentId)
			}
			addAll(page.map { it.logicalRecencyStartMs to it.logicalRecencySegmentId })
		}
		if (page.any { candidate ->
			candidate.logicalRecencyStartMs < 0L || candidate.logicalRecencySegmentId <= 0L
		}) {
			abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		if (cursors.zipWithNext().any { (left, right) ->
			right.first > left.first || right.first == left.first && right.second >= left.second
		}) {
			abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
	}

	private inline fun <T> portableValue(block: () -> T): T = try {
		block()
	} catch (abort: PortablePressureSnapshotAbort) {
		throw abort
	} catch (_: IllegalArgumentException) {
		abort(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun abort(reason: PortablePressureExportUnverifiableReason): Nothing =
		throw PortablePressureSnapshotAbort(reason)

	private class PortablePressureSnapshotAbort(
		val reason: PortablePressureExportUnverifiableReason,
	) : RuntimeException(null, null, false, false)

	private companion object {
		const val CANDIDATE_PAGE_SIZE = 4
		val captureAttributionFailures = setOf(
			PressureHistoryReason.LEGACY_UNATTRIBUTED,
			PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE,
			PressureHistoryReason.LOGICAL_MANIFEST_REVISION_UNION_INVALID,
			PressureHistoryReason.SERVICE_RUN_MISSING,
			PressureHistoryReason.SERVICE_RUN_MEMBERSHIP_MISMATCH,
			PressureHistoryReason.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
			PressureHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
			PressureHistoryReason.MANIFEST_MISSING,
			PressureHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH,
			PressureHistoryReason.MANIFEST_INTEGRITY_FAILED,
			PressureHistoryReason.SOURCE_POLICY_ATTRIBUTION_INVALID,
		)
	}
}

internal sealed interface PortablePressureSnapshot {
	data class Ready(val entries: List<PortablePressureEntryV1>) : PortablePressureSnapshot {
		init {
			require(entries.isNotEmpty())
			require(entries == entries.sortedWith(PORTABLE_PRESSURE_ENTRY_ORDER))
		}
	}

	data class Outcome(val result: ExportPortablePressureResult) : PortablePressureSnapshot
}
