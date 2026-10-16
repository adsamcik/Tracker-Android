package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.OwnerMaintenanceSession
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private data class OwnerlessStepsWalCandidate(
	val candidate: StepsWalPruneCandidate,
	val ownerKey: StepsCountDomainOwnerLookupKey,
	val wal: SourceEventWalEntity,
	val runKey: OwnerlessStepsManifestRunKey,
	val manifestRevision: Long,
)

private data class OwnerlessShadowCapture(
	val manifest: SessionManifestVersionEntity,
	val binding: SessionManifestSourceEntity,
	val captureMode: Long,
)

internal suspend fun StepsCountDomainStore.authenticateWalPruneOwnerKeys(
	candidates: List<StepsWalPruneCandidate>,
	session: OwnerMaintenanceSession,
	timelineCache: OwnerlessStepsManifestTimelineCache,
): List<StepsCountDomainOwnerLookupKey> {
	val sqlite = database.openHelper.writableDatabase
	val ownerKeys = mutableListOf<StepsCountDomainOwnerLookupKey>()
	val ownerlessCandidates = mutableListOf<OwnerlessStepsWalCandidate>()
	candidates.forEach { candidate ->
		currentCoroutineContext().ensureActive()
		requireActiveSnapshot(session)
		val ownerKey = candidate.ownerKey ?: return@forEach
		if (sqlite.hasExactCountDomainOwner(ownerKey)) {
			ownerKeys += ownerKey
		} else {
			sqlite.requireAuthenticMissingCountDomainOwnerKeys(listOf(ownerKey))
			ownerlessCandidates += prepareOwnerlessShadowStepsWal(
				candidate = candidate,
				ownerKey = ownerKey,
			)
		}
	}
	val timelines = loadOwnerlessStepsManifestAuthority(
		candidates = ownerlessCandidates,
		session = session,
		timelineCache = timelineCache,
	)
	ownerlessCandidates.forEach { candidate ->
		currentCoroutineContext().ensureActive()
		requireOwnerlessShadowStepsWal(
			candidate = candidate,
			timeline = timelines[candidate.runKey]
				?: throw CountDomainStoredEvidenceException(),
			session = session,
		)
	}
	return ownerKeys
}

@Suppress("ComplexCondition", "LongMethod")
private suspend fun StepsCountDomainStore.prepareOwnerlessShadowStepsWal(
	candidate: StepsWalPruneCandidate,
	ownerKey: StepsCountDomainOwnerLookupKey,
): OwnerlessStepsWalCandidate {
	val wal = database.sourceEventWalDao()
		.getByAdmissionOrdinal(candidate.cursor.admissionOrdinal)
		?: throw CountDomainStoredEvidenceException()
	val sqlite = database.openHelper.writableDatabase
	if (!wal.matchesOwnerlessPruneCandidate(candidate) ||
		!wal.hasOwnerlessStepsIdentity() || !wal.hasOwnerlessStepsAuthority() ||
		!wal.hasOwnerlessStepsCaptureScope() ||
		!sqlite.hasExactOwnerlessStepsWalAttributionStorage(wal) ||
		!hasCurrentOwnerlessStepsRegistration(wal) ||
		candidate.ownerlessRunKey != OwnerlessStepsManifestRunKey(
			requireNotNull(wal.logicalTrackingId),
			requireNotNull(wal.serviceRunId),
		)
	) {
		throw CountDomainStoredEvidenceException()
	}
	return OwnerlessStepsWalCandidate(
		candidate = candidate,
		ownerKey = ownerKey,
		wal = wal,
		runKey = requireNotNull(candidate.ownerlessRunKey),
		manifestRevision = requireNotNull(wal.sessionManifestRevision),
	)
}

private suspend fun StepsCountDomainStore.hasCurrentOwnerlessStepsRegistration(wal: SourceEventWalEntity): Boolean {
	val state = database.sourceEvidenceStateDao().get() ?: return false
	if (state.collectedDataEpoch != wal.capturedCollectedDataEpoch) {
		return false
	}
	val registration = database.sourceBrokerDao().registration(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		wal.registrationGeneration,
	) ?: return false
	return database.openHelper.writableDatabase
		.hasExactOwnerlessStepsProviderRegistrationStorage(registration) &&
		registration.authenticatesOwnerlessStepsWal(wal)
}

private fun SourceEventWalEntity.matchesOwnerlessPruneCandidate(candidate: StepsWalPruneCandidate): Boolean =
	admissionOrdinal == candidate.cursor.admissionOrdinal && eventId == candidate.eventId &&
		createdAtMs == candidate.cursor.createdAtMs &&
		authorizationPurposeEligibilityMask == candidate.authorizationPurposeEligibilityMask

private fun SourceEventWalEntity.hasOwnerlessStepsIdentity(): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && hasQualifiedIntegrity() &&
		sourceSequence >= 0L && registrationGeneration > 0L && sourceInstanceId.isNotBlank() &&
		!physicalConfigurationFingerprint.isNullOrBlank() && wallTimeMs?.let { it == acquiredAtMs } == true

private fun SourceEventWalEntity.hasOwnerlessStepsAuthority(): Boolean =
	authorizationRevision?.let { it > 0L } == true && !authorizationFingerprint.isNullOrBlank() &&
		authorizationPurposeEligibilityMask in 1L..STEPS_WAL_ALLOWED_PURPOSE_MASK &&
		authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L

private fun SourceEventWalEntity.hasOwnerlessStepsCaptureScope(): Boolean =
	hasOwnerlessStepsConfiguration() && hasOwnerlessStepsConsent()

private fun SourceEventWalEntity.hasOwnerlessStepsConfiguration(): Boolean =
	configRevision?.let { it > 0L } == true && sourcePolicyRevision?.let { it > 0L } == true &&
		lifecycleLeaseGeneration?.let { it > 0L } == true

private fun SourceEventWalEntity.hasOwnerlessStepsConsent(): Boolean =
	!logicalTrackingId.isNullOrBlank() && !serviceRunId.isNullOrBlank() &&
		captureConsentEpoch?.let { it >= 0L } == true && sessionManifestRevision?.let { it > 0L } == true

private suspend fun StepsCountDomainStore.loadOwnerlessStepsManifestAuthority(
	candidates: List<OwnerlessStepsWalCandidate>,
	session: OwnerMaintenanceSession,
	timelineCache: OwnerlessStepsManifestTimelineCache,
): Map<OwnerlessStepsManifestRunKey, OwnerlessStepsManifestRunTimeline> {
	val sessionDao = database.sourceSessionDao()
	val candidatesByRun = candidates.groupBy(OwnerlessStepsWalCandidate::runKey)
	return buildMap {
		for ((runKey, runCandidates) in candidatesByRun) {
			currentCoroutineContext().ensureActive()
			requireActiveSnapshot(session)
			val timeline = timelineCache.getOrLoad(runKey) {
				val run = sessionDao.serviceRun(runKey.serviceRunId)
					?.takeIf {
						it.serviceRunId == runKey.serviceRunId &&
							it.logicalTrackingId == runKey.logicalTrackingId
					}
					?: throw CountDomainStoredEvidenceException()
				val manifests = authenticateOwnerlessStepsServiceRunManifestTimeline(
					run,
				) { afterRevision, pageLimit ->
					sessionDao.rawManifestsForServiceRunAfterRevision(
						logicalTrackingId = runKey.logicalTrackingId,
						serviceRunId = runKey.serviceRunId,
						afterRevision = afterRevision,
						limit = pageLimit,
					)
				}
				OwnerlessStepsManifestRunTimeline(runKey, run, manifests)
			}
			authenticateOwnerlessStepsManifestSourceBindings(
				timeline = timeline,
				manifestRevisions = runCandidates.map(
					OwnerlessStepsWalCandidate::manifestRevision,
				),
			) { revisionChunk, bindingLimit ->
				requireActiveSnapshot(session)
				sessionDao.rawManifestSourcesForRevisions(
					logicalTrackingId = runKey.logicalTrackingId,
					manifestRevisions = revisionChunk,
					limit = bindingLimit,
				)
			}
			checkpointAndRequireUnchanged(
				session,
				StepsCountDomainMaintenanceCheckpoint.WAL_OWNERLESS_RUN_AUTHENTICATED,
			)
			put(runKey, timeline)
		}
	}
}

@Suppress("ComplexCondition", "LongMethod")
private suspend fun StepsCountDomainStore.requireOwnerlessShadowStepsWal(
	candidate: OwnerlessStepsWalCandidate,
	timeline: OwnerlessStepsManifestRunTimeline,
	session: OwnerMaintenanceSession,
) {
	requireActiveSnapshot(session)
	val capture = ownerlessShadowCapture(candidate, timeline)
		?: throw CountDomainStoredEvidenceException()
	if (!hasOwnerlessShadowLane(candidate.wal, capture) ||
		!database.openHelper.writableDatabase.hasNoShadowProductOwners(candidate)
	) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun ownerlessShadowCapture(
	candidate: OwnerlessStepsWalCandidate,
	timeline: OwnerlessStepsManifestRunTimeline,
): OwnerlessShadowCapture? {
	val manifest = timeline.manifestsByRevision[candidate.manifestRevision]
	val sources = timeline.sourcesByRevision[candidate.manifestRevision]
	return if (manifest != null && sources != null &&
		manifest.matchesOwnerlessStepsCapture(candidate, timeline.run)
	) {
		val binding = sources.singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}
		val captureMode = manifest.sessionMode.stepsCaptureModeMaskOrNull()
		if (binding != null && captureMode != null && binding.hasExactStepsWriterBinding(candidate.wal)) {
			OwnerlessShadowCapture(manifest, binding, captureMode)
		} else {
			null
		}
	} else {
		null
	}
}

private fun SessionManifestVersionEntity.matchesOwnerlessStepsCapture(
	candidate: OwnerlessStepsWalCandidate,
	run: SourceServiceRunEntity,
): Boolean = logicalTrackingId == candidate.runKey.logicalTrackingId &&
	serviceRunId == candidate.runKey.serviceRunId &&
	sourcePolicyRevision == candidate.wal.sourcePolicyRevision &&
	acquisitionPlanRevision == candidate.wal.configRevision &&
	effectiveBootId == candidate.wal.clockDomainId &&
	effectiveElapsedRealtimeNanos <= candidate.wal.observedElapsedNanos &&
	run.leaseGeneration == candidate.wal.lifecycleLeaseGeneration

private fun SessionManifestSourceEntity.hasExactStepsWriterBinding(wal: SourceEventWalEntity): Boolean =
	isExactLegacyStepsWriterBinding(wal) || isExactCanonicalStepsWriterBinding(wal)

private suspend fun StepsCountDomainStore.hasOwnerlessShadowLane(
	wal: SourceEventWalEntity,
	capture: OwnerlessShadowCapture,
): Boolean {
	val projection = database.sourceProjectionStateDao()
	if (projection.registration(
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		) != null
	) {
		return false
	}
	val sqlite = database.openHelper.writableDatabase
	val lanes = projection.productLanesByProjection(
		SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
	)
	if (sqlite.hasMalformedStepsWalPruneLaneAttribution() ||
		lanes.any { !sqlite.hasExactStepsWalPruneLaneStorage(it) || !it.hasAuthenticStepsWalPruneShape() }
	) {
		return false
	}
	val lane = lanes.singleOrNull { it.coversOwnerlessCapture(wal, capture) } ?: return false
	return capture.binding.isExactLegacyStepsWriterBinding(wal) &&
		!capture.binding.isExactCanonicalStepsWriterBinding(wal) &&
		lane.isShadowAtCapture(capture.manifest)
}

private fun SourceProductProjectionLaneEntity.coversOwnerlessCapture(
	wal: SourceEventWalEntity,
	capture: OwnerlessShadowCapture,
): Boolean = (capture.binding.writerBindingGeneration == null ||
	bindingGeneration == capture.binding.writerBindingGeneration) &&
	captureModeMask and capture.captureMode != 0L &&
	activationOrdinal <= wal.admissionOrdinal && contiguousAdmissionOrdinal >= wal.admissionOrdinal &&
	captureAdmissionCutoffOrdinal?.let { it >= wal.admissionOrdinal } != false

private fun SourceProductProjectionLaneEntity.isShadowAtCapture(manifest: SessionManifestVersionEntity): Boolean =
	when (productStage) {
		SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW -> manifest.rolloutRevision >= activatedRolloutRevision
		SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL -> manifest.rolloutRevision < activatedRolloutRevision
		else -> false
	}

private fun SupportSQLiteDatabase.hasNoShadowProductOwners(candidate: OwnerlessStepsWalCandidate): Boolean =
	longForQuery(
		"SELECT COUNT(*) FROM main.steps_count_domain_receipt " +
			"WHERE owner_kind = ? AND owner_identity = ?",
		arrayOf(candidate.ownerKey.ownerKind, candidate.ownerKey.ownerIdentity),
	) == 0L &&
		longForQuery(
			"SELECT COUNT(*) FROM main.steps_count_domain_completeness_marker " +
				"WHERE owner_kind = ? AND owner_identity = ?",
			arrayOf(candidate.ownerKey.ownerKind, candidate.ownerKey.ownerIdentity),
		) == 0L &&
		longForQuery(
			"SELECT COUNT(*) FROM main.step_fact_revision " +
				"WHERE source_admission_ordinal = ? OR source_event_id = ?",
			arrayOf<Any?>(candidate.wal.admissionOrdinal, candidate.wal.eventId),
		) == 0L
