package com.adsamcik.tracker.tracker.presentation

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummary
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
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
	private val trackingHistoryRepository: TrackingHistoryRepository? = null,
) {
	/** Reads and closes the exact saved presentation in the shared reader's Room snapshot. */
	suspend fun closeExact(binding: SessionPresentationBinding): SessionSegment = database.withTransaction {
		val dao = database.sourceSessionDao()
		val run = requireNotNull(dao.serviceRun(binding.serviceRunId))
		val session = requireNotNull(dao.session(binding.logicalTrackingId))
		val segment = requireNotNull(database.sessionSegmentDao().getById(binding.sessionSegmentId))
		check(run.logicalTrackingId == binding.logicalTrackingId && run.sessionSegmentId == binding.sessionSegmentId)
		check(segment.logicalTrackingId == binding.logicalTrackingId && segment.serviceRunId == binding.serviceRunId)
		val end = closeTime(database, session, run, segment)
		val summary = readSavedLocationCloseSummary(database, trackingHistoryRepository, binding)
		val closed = segment.copy(
			endTimeMs = end,
			distanceM = if (summary == null) {
				segment.distanceM
			} else {
				summary.distanceMeters?.toFloat()?.takeIf { it.isFinite() } ?: 0f
			},
			sampleCount = summary?.pointCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: segment.sampleCount,
		)
		database.sessionSegmentDao().update(closed)
		closed
	}

	/** A failed initialization may have saved authorized facts before opening its presentation row. */
	@Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
	suspend fun recoverMissingForRetainedEvent(eventId: String): Boolean = database.withTransaction {
		val wal = database.sourceEventWalDao().getByEventId(eventId) ?: return@withTransaction false
		val logicalId = wal.logicalTrackingId ?: return@withTransaction false
		val runId = wal.serviceRunId ?: return@withTransaction false
		val dao = database.sourceSessionDao()
		val run = dao.serviceRun(runId) ?: return@withTransaction false
		val session = dao.session(logicalId) ?: return@withTransaction false
		val cutoff = database.savedSessionCutoff(session)
		val sessionEnd = if (cutoff.bootId != null) {
			cutoff.endForBoot(wal.clockDomainId)
		} else {
			session.cutoffElapsedNanos.takeIf { session.lifecycleBootId == wal.clockDomainId } ?: Long.MAX_VALUE
		}
		if (!isRetainedRecording(wal) || run.logicalTrackingId != logicalId || run.bootId != wal.clockDomainId ||
			!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, wal.lifecycleLeaseGeneration ?: 0L) ||
			session.lifecycleLeaseGeneration < (wal.lifecycleLeaseGeneration ?: 0L) ||
			run.startedElapsedNanos > wal.observedElapsedNanos ||
			wal.observedElapsedNanos >= sessionEnd
		) {
			return@withTransaction false
		}
		val manifestRevision = wal.sessionManifestRevision ?: return@withTransaction false
		val manifest = dao.manifest(logicalId, manifestRevision) ?: return@withTransaction false
		val bindings = dao.manifestSources(logicalId, manifestRevision)
		if (!SessionManifestIntegrity.verify(manifest, bindings) || manifest.serviceRunId != runId ||
			manifest.sourcePolicyRevision != wal.sourcePolicyRevision ||
			manifest.acquisitionPlanRevision != wal.configRevision ||
			manifest.effectiveBootId != wal.clockDomainId || manifest.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos ||
			bindings.none { it.sourceKind == wal.sourceKind && it.persistenceEligible &&
				it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && it.consentEpoch == wal.captureConsentEpoch }
		) {
			return@withTransaction false
		}
		run.sessionSegmentId?.let { boundId ->
			val bound = database.sessionSegmentDao().getById(boundId)
			val exact = bound != null && bound.logicalTrackingId == logicalId && bound.serviceRunId == runId
			if (exact && run.isTerminalPendingPresentation() &&
				failedInitializationEnd(wal, run, session) != null
			) {
				check(acknowledgeQuiescedExact(SessionPresentationBinding(logicalId, runId, boundId),
					requireNotNull(run.completedAtMs)) == PresentationQuiescenceResult.ACKNOWLEDGED)
			}
			return@withTransaction exact
		}
		val recoveringCurrentFailure = isCurrentStoppingRun(session, run)
		val endTimeMs = if (recoveringCurrentFailure) {
			failedInitializationEnd(wal, run, session)
		} else {
			run.completedAtMs
		} ?: return@withTransaction false
		if ((!recoveringCurrentFailure && (run.state !in TERMINAL_RUN_STATES || session.currentServiceRunId == runId)) ||
			run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_PENDING ||
			run.startedAtMs < 0L || endTimeMs < run.startedAtMs || manifest.sessionMode !in setOf("MANUAL", "AUTOMATIC")
		) {
			return@withTransaction false
		}
		val segmentId = database.sessionSegmentDao().insert(SessionSegment(
			startTimeMs = run.startedAtMs, endTimeMs = endTimeMs, distanceM = 0f, steps = null,
			primaryActivity = null, activityConfidence = null, sampleCount = 0,
			source = if (manifest.sessionMode == "MANUAL") {
				SegmentSource.USER_CREATED
			} else {
				SegmentSource.INFERRED_LOW_CONFIDENCE
			},
			inferenceVersion = "tracker_v2", createdAt = run.startedAtMs, logicalTrackingId = logicalId, serviceRunId = runId,
		))
		check(segmentId > 0L)
		check(dao.updateServiceRun(run.copy(sessionSegmentId = segmentId)) == 1)
		if (!recoveringCurrentFailure) {
			check(acknowledgeQuiescedExact(SessionPresentationBinding(logicalId, runId, segmentId), endTimeMs) ==
				PresentationQuiescenceResult.ACKNOWLEDGED)
		}
		true
	}

	/** A failed current initialization may need its closed presentation before its saved facts can drain. */
	private suspend fun failedInitializationEnd(
		wal: SourceEventWalEntity,
		run: SourceServiceRunEntity,
		session: LogicalTrackingSessionEntity,
	): Long? {
		val cutoff = database.savedSessionCutoff(session)
		val exactClock = cutoff.bootId == run.bootId && cutoff.bootId == wal.clockDomainId &&
			cutoff.elapsedNanos != null
		return session.cutoffAtMs.takeIf { exactClock && wal.wasMeasuredWithinRun(run, cutoff.elapsedNanos) &&
			hasFailedInitializationStop(session, run) && hasCapturedAuthorization(wal) }
	}

	private fun isCurrentStoppingRun(session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity): Boolean =
		session.state == SessionLifecycleState.STOPPING.name && session.currentServiceRunId == run.serviceRunId &&
			run.state == SessionLifecycleState.STOPPING.name && run.completedAtMs == null

	private fun SourceEventWalEntity.wasMeasuredWithinRun(run: SourceServiceRunEntity, cutoffNanos: Long?): Boolean {
		val cutoff = cutoffNanos ?: return false
		val start = observedIntervalStartNanos ?: observedElapsedNanos
		return start >= run.startedElapsedNanos && start <= observedElapsedNanos &&
			observedElapsedNanos < cutoff && receivedElapsedNanos >= observedElapsedNanos
	}

	private suspend fun hasFailedInitializationStop(
		session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
	): Boolean {
		val dao = database.sourceSessionDao()
		val stop = dao.firstStopIntent(session.logicalTrackingId) ?: return false
		val manifest = dao.manifest(session.logicalTrackingId, stop.manifestRevision) ?: return false
		return stop.stopReason == "INITIALIZATION_FAILURE" && manifest.serviceRunId == run.serviceRunId &&
			stop.requestedElapsedRealtimeNanos >= run.startedElapsedNanos &&
			SessionManifestIntegrity.verify(manifest,
				dao.manifestSources(session.logicalTrackingId, stop.manifestRevision))
	}

	/** Shutdown may recover a segment after component initialization returned no presentation receipt. */
	suspend fun recoveredInitializationBinding(
		logicalTrackingId: String,
		serviceRunId: String,
	): SessionPresentationBinding? = database.withTransaction {
		val dao = database.sourceSessionDao()
		val run = dao.serviceRun(serviceRunId) ?: return@withTransaction null
		val session = dao.session(logicalTrackingId) ?: return@withTransaction null
		val segment = run.sessionSegmentId?.let { database.sessionSegmentDao().getById(it) }
			?: return@withTransaction null
		if (hasRecoveredInitializationBinding(session, run, segment)) {
			SessionPresentationBinding(logicalTrackingId, serviceRunId, segment.id)
		} else {
			null
		}
	}

	private suspend fun hasRecoveredInitializationBinding(
		session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		segment: SessionSegment,
	): Boolean {
		val cutoff = database.savedSessionCutoff(session)
		val closed = segment.logicalTrackingId == session.logicalTrackingId &&
			segment.serviceRunId == run.serviceRunId && segment.startTimeMs == run.startedAtMs &&
			segment.endTimeMs == session.cutoffAtMs
		val terminal = run.isTerminalPendingPresentation() && run.logicalTrackingId == session.logicalTrackingId &&
			session.currentServiceRunId != run.serviceRunId
		return cutoff.bootId == run.bootId && closed && terminal && hasFailedInitializationStop(session, run)
	}

	private suspend fun hasCapturedAuthorization(wal: SourceEventWalEntity): Boolean {
		val revision = wal.authorizationRevision ?: return false
		val broker = database.sourceBrokerDao()
		val rows = broker.authorizationRevision(wal.sourceKind, wal.registrationGeneration, revision)
		val authorization = runCatching { rows.toAuthorizationSnapshotOrNull() }.getOrNull() ?: return false
		val member = authorization.authorizedMembers.singleOrNull { it.matchesCapturedEvent(wal) }
		if (!authorization.matchesCapturedSnapshot(wal) || member == null ||
			wal.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return false
		}
		val ids = authorization.authorizedMembers.mapNotNull { it.demandId }
		val demands = broker.demandsByIds(ids)
		val expected = SourceBrokerAuthorization.rows(wal.sourceKind, wal.registrationGeneration, revision, demands,
			authorization.effectiveBootId, authorization.effectiveElapsedRealtimeNanos, rows.first().effectiveWallTimeMs)
		return ids.distinct().size == authorization.authorizedMembers.size && demands.size == ids.size &&
			expected.sortedBy { it.memberId } == rows.sortedBy { it.memberId }
	}

	private fun SourceAuthorizationSnapshot.matchesCapturedSnapshot(wal: SourceEventWalEntity): Boolean =
		authorizationFingerprint == wal.authorizationFingerprint && effectiveBootId == wal.clockDomainId &&
			effectiveElapsedRealtimeNanos <= (wal.observedIntervalStartNanos ?: wal.observedElapsedNanos)

	private suspend fun isRetainedRecording(wal: SourceEventWalEntity): Boolean {
		val evidence = database.sourceEvidenceStateDao().get() ?: return false
		val observedTimeMs = wal.wallTimeMs
		val uncertaintyMs = wal.wallTimeUncertaintyMs
		if (observedTimeMs == null || uncertaintyMs == null) {
			return false
		}
		val earliest = CanonicalRetentionBounds.location(observedTimeMs, uncertaintyMs) ?: return false
		val retainedFromMs = evidence.retainedFromMs
		return wal.hasQualifiedIntegrity() && wal.capturedCollectedDataEpoch == evidence.collectedDataEpoch &&
			wal.admissionOrdinal > evidence.deletedSourceEventHighWaterOrdinal &&
			(retainedFromMs == null || earliest >= retainedFromMs)
	}

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
		val physicalSegment = newSegment.copy(
			startTimeMs = run.startedAtMs,
			endTimeMs = run.startedAtMs,
			createdAt = run.startedAtMs,
		)
		val insertedId = database.sessionSegmentDao().insert(physicalSegment)
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
			segment = physicalSegment.copy(id = insertedId),
			created = true,
		)
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

private fun SourceAuthorizationEntity.matchesCapturedEvent(wal: SourceEventWalEntity): Boolean {
	val session = logicalTrackingId == wal.logicalTrackingId && serviceRunId == wal.serviceRunId &&
		manifestRevision == wal.sessionManifestRevision && lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration
	return purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible && session &&
		sourcePolicyRevision == wal.sourcePolicyRevision && consentEpoch == wal.captureConsentEpoch
}

private suspend fun closeTime(
	database: AppDatabase,
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	segment: SessionSegment,
): Long {
	val currentManifest = session.currentManifestRevision?.let {
		database.sourceSessionDao().manifest(session.logicalTrackingId, it)
	}
	val ownsLogicalCutoff = session.currentServiceRunId == run.serviceRunId ||
		currentManifest?.serviceRunId == run.serviceRunId
	return session.cutoffAtMs?.takeIf { ownsLogicalCutoff } ?: run.completedAtMs ?: segment.endTimeMs
}

private suspend fun readSavedLocationCloseSummary(
	database: AppDatabase,
	trackingHistoryRepository: TrackingHistoryRepository?,
	binding: SessionPresentationBinding,
): SavedLocationSummary? {
	val repository = trackingHistoryRepository ?: return null
	val summary = repository.readSavedLocationSummary(
		SavedLocationSummarySelection.Session(binding.sessionSegmentId),
	)
	check(summary is SavedLocationSummaryRead.Snapshot) { "Saved session summary is not available for closing" }
	check(summary.selection == SavedLocationSummarySelection.Session(binding.sessionSegmentId))
	val evidence = requireNotNull(database.sourceEvidenceStateDao().get())
	check(evidence.collectedDataEpoch == summary.readSnapshot.collectedDataEpoch &&
		evidence.revision == summary.readSnapshot.sourceEvidenceRevision)
	return summary.summary
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
