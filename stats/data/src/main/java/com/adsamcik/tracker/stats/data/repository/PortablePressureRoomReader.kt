package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Creates a bounded, fully authenticated Pressure transfer snapshot in one Room transaction.
 *
 * The accepted source-local selector remains the single authority for manifests, policies,
 * consent, writer ownership, corrections, deletion, retention markers, and replacement members.
 * This adapter only privacy-minimizes already-qualified direct Pressure evidence.
 */
@Singleton
internal class PortablePressureRoomReader @Inject constructor(
	private val database: AppDatabase,
	private val selector: PressureHistorySelector,
) {
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

	private suspend fun readInTransaction(
		request: ExportPortablePressureRequest,
	): PortablePressureSnapshot {
		val entries = mutableListOf<PortablePressureEntryV1>()
		val exportedLogicalIds = hashSetOf<String>()
		var beforeRecencyStartMs: Long? = null
		var beforeRecencySegmentId: Long? = null
		var candidateCount = 0
		var runCount = 0
		var windowCount = 0

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
			if (page.isEmpty()) break
			if (page.size > remaining) abort(PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW)
			validateCandidatePage(page, beforeRecencyStartMs, beforeRecencySegmentId)
			candidateCount += page.size

			val physical = selector.selectManyInTransaction(
				segments = page.map { it.segment },
				memberBudget = CANDIDATE_PAGE_SIZE * PressurePortableFormatV1.MAX_RUNS_PER_ENTRY,
			)
			val logicalById = PressureLogicalHistoryComposer.compose(physical).mapNotNull { entry ->
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
				portableEntry(logical)?.let { entry ->
					runCount += entry.runs.size
					windowCount += entry.runs.sumOf { it.windows.size }
					if (runCount > PressurePortableFormatV1.MAX_TOTAL_RUNS ||
						windowCount > PressurePortableFormatV1.MAX_TOTAL_WINDOWS
					) {
						abort(PortablePressureExportUnverifiableReason.DEPENDENCY_OVERFLOW)
					}
					entries += entry
				}
			}

			val last = page.last()
			beforeRecencyStartMs = last.logicalRecencyStartMs
			beforeRecencySegmentId = last.logicalRecencySegmentId
			if (page.size < pageLimit) break
		}

		if (entries.isEmpty()) {
			return PortablePressureSnapshot.Outcome(ExportPortablePressureResult.NoEntries)
		}
		return PortablePressureSnapshot.Ready(entries.sortedWith(PORTABLE_PRESSURE_ENTRY_ORDER))
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
		if (runs.none { it.windows.isNotEmpty() || it.retentionLoss }) return null
		return portableValue {
			PortablePressureEntryV1.create(
				identity = PortablePressureOpaqueIdentity.derive(
					PortablePressureIdentityKind.LOGICAL_ENTRY,
					identity.logicalTrackingId,
				),
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
					factIdentity(window),
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

	private fun factIdentity(window: PressureHistoryWindow): String = buildString {
		append(window.writerProjectionId.length)
		append(':')
		append(window.writerProjectionId)
		append(':')
		append(window.writerProjectionVersion)
		append(':')
		append(window.logicalFactId.length)
		append(':')
		append(window.logicalFactId)
	}

	private fun validateCandidatePage(
		page: List<com.adsamcik.tracker.shared.base.database.dao.PressureLogicalHistoryCandidate>,
		beforeStartMs: Long?,
		beforeSegmentId: Long?,
	) {
		val cursors = buildList {
			if (beforeStartMs != null && beforeSegmentId != null) add(beforeStartMs to beforeSegmentId)
			addAll(page.map { it.logicalRecencyStartMs to it.logicalRecencySegmentId })
		}
		if (page.any { candidate ->
			candidate.logicalRecencyStartMs < 0L || candidate.logicalRecencySegmentId <= 0L
		} || cursors.zipWithNext().any { (left, right) ->
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
