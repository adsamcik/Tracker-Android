package com.adsamcik.tracker.tracker.presentation

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState

/** Exact Room identity joining one physical service run to its source-neutral presentation row. */
internal data class SessionPresentationBinding(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val sessionSegmentId: Long,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(sessionSegmentId > 0L)
	}
}

internal data class OpenSessionPresentation(
	val binding: SessionPresentationBinding,
	val segment: SessionSegment,
	val created: Boolean,
)

internal enum class PresentationQuiescenceResult {
	ACKNOWLEDGED,
	ALREADY_ACKNOWLEDGED,
	MISSING,
	OWNERSHIP_MISMATCH,
	NOT_TERMINAL,
}

/**
 * Narrow lifecycle boundary for the existing session/Ski presentation pipeline.
 *
 * Room owns the exact run-to-segment relationship. The out-of-Room recovery descriptor may mirror
 * the segment id, but it never authorizes opening, resuming, acknowledging, or deleting a row.
 */
internal class SessionPresentationLifecycle(
	private val database: AppDatabase,
) {
	/**
	 * Creates and binds a presentation segment atomically, or returns the exact same-run binding.
	 * A replacement service run intentionally gets another segment under the same logical entry.
	 */
	suspend fun openOrResumeExact(
		logicalTrackingId: String,
		serviceRunId: String,
		requestedSessionSegmentId: Long?,
		newSegment: SessionSegment,
	): OpenSessionPresentation = database.withTransaction {
		validateOpenRequest(logicalTrackingId, serviceRunId, requestedSessionSegmentId, newSegment)

		val sourceSessionDao = database.sourceSessionDao()
		val logicalSession = requireNotNull(sourceSessionDao.session(logicalTrackingId)) {
			"Presentation owner logical session is missing"
		}
		val run = requireNotNull(sourceSessionDao.serviceRun(serviceRunId)) {
			"Presentation owner service run is missing"
		}
		check(logicalSession.currentServiceRunId == serviceRunId) {
			"Presentation owner service run is no longer current"
		}
		check(run.logicalTrackingId == logicalTrackingId) {
			"Presentation owner service run belongs to another logical session"
		}
		check(run.state == SessionLifecycleState.ACTIVE.name && run.completedAtMs == null) {
			"Presentation owner service run is not active"
		}
		check(
			run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING,
		) { "Presentation owner service run is already settled" }

		run.sessionSegmentId?.let { alreadyBoundId ->
			return@withTransaction resumeBoundExact(
				logicalTrackingId,
				serviceRunId,
				requestedSessionSegmentId,
				alreadyBoundId,
			)
		}

		check(requestedSessionSegmentId == null) {
			"Recovery descriptor names a segment that Room did not bind"
		}
		val insertedId = database.sessionSegmentDao().insert(newSegment)
		check(insertedId > 0L) { "Unable to create presentation segment" }
		check(
			sourceSessionDao.bindSessionSegmentExact(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				sessionSegmentId = insertedId,
			) == 1,
		) { "Presentation owner changed during segment binding" }
		OpenSessionPresentation(
			binding = SessionPresentationBinding(logicalTrackingId, serviceRunId, insertedId),
			segment = newSegment.copy(id = insertedId),
			created = true,
		)
	}

	private fun validateOpenRequest(
		logicalTrackingId: String,
		serviceRunId: String,
		requestedSessionSegmentId: Long?,
		newSegment: SessionSegment,
	) {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(requestedSessionSegmentId == null || requestedSessionSegmentId > 0L)
		require(newSegment.id == 0L)
		require(newSegment.logicalTrackingId == logicalTrackingId)
		require(newSegment.serviceRunId == serviceRunId)
	}

	private suspend fun resumeBoundExact(
		logicalTrackingId: String,
		serviceRunId: String,
		requestedSessionSegmentId: Long?,
		alreadyBoundId: Long,
	): OpenSessionPresentation {
		check(requestedSessionSegmentId == null || requestedSessionSegmentId == alreadyBoundId) {
			"Recovery descriptor names another presentation segment"
		}
		val existing = requireNotNull(database.sessionSegmentDao().getById(alreadyBoundId)) {
			"Bound presentation segment is missing"
		}
		check(
			existing.logicalTrackingId == logicalTrackingId &&
				existing.serviceRunId == serviceRunId,
		) { "Bound presentation segment belongs to another owner" }
		return OpenSessionPresentation(
			binding = SessionPresentationBinding(logicalTrackingId, serviceRunId, alreadyBoundId),
			segment = existing,
			created = false,
		)
	}

	/**
	 * Persists quiescence only for the exact terminal run and its still-owned segment.
	 * Physical empty-row reclamation deliberately remains outside this method until qualified facts
	 * from every source family can participate in the decision.
	 */
	suspend fun acknowledgeQuiescedExact(
		binding: SessionPresentationBinding,
		acknowledgedAtMs: Long,
	): PresentationQuiescenceResult = database.withTransaction {
		require(acknowledgedAtMs >= 0L)
		val sourceSessionDao = database.sourceSessionDao()
		val run = sourceSessionDao.serviceRun(binding.serviceRunId)
			?: return@withTransaction PresentationQuiescenceResult.MISSING
		presentationOwnershipResult(binding, run)?.let { result ->
			return@withTransaction result
		}
		if (!run.isTerminalPendingPresentation()) {
			return@withTransaction PresentationQuiescenceResult.NOT_TERMINAL
		}
		if (
			sourceSessionDao.acknowledgePresentationQuiescedExact(
				logicalTrackingId = binding.logicalTrackingId,
				serviceRunId = binding.serviceRunId,
				sessionSegmentId = binding.sessionSegmentId,
				acknowledgedAtMs = acknowledgedAtMs,
			) == 1
		) {
			PresentationQuiescenceResult.ACKNOWLEDGED
		} else {
			PresentationQuiescenceResult.OWNERSHIP_MISMATCH
		}
	}

	private suspend fun presentationOwnershipResult(
		binding: SessionPresentationBinding,
		run: SourceServiceRunEntity,
	): PresentationQuiescenceResult? {
		if (
			run.logicalTrackingId != binding.logicalTrackingId ||
			run.sessionSegmentId != binding.sessionSegmentId
		) {
			return PresentationQuiescenceResult.OWNERSHIP_MISMATCH
		}
		if (run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED) {
			return PresentationQuiescenceResult.ALREADY_ACKNOWLEDGED
		}
		val segment = database.sessionSegmentDao().getById(binding.sessionSegmentId)
			?: return PresentationQuiescenceResult.MISSING
		return if (
			segment.logicalTrackingId == binding.logicalTrackingId &&
			segment.serviceRunId == binding.serviceRunId
		) {
			null
		} else {
			PresentationQuiescenceResult.OWNERSHIP_MISMATCH
		}
	}

	private fun SourceServiceRunEntity.isTerminalPendingPresentation(): Boolean =
		state in TERMINAL_RUN_STATES &&
			completedAtMs != null &&
			presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING

	private companion object {
		val TERMINAL_RUN_STATES = setOf(
			SessionLifecycleState.FINALIZED.name,
			SessionLifecycleState.FAILED.name,
		)
	}
}
