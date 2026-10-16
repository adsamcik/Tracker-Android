package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedPortableWalTargetRow
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// These are the operation's captured snapshot inputs; no authority is read again or bundled into mutable state.
@Suppress("LongParameterList")
internal suspend fun AppDatabase.validatePortableActivityTerminalProjectionSettlement(
	limits: PortableActivityReadLimits,
	laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	sessions: Map<String, LogicalTrackingSessionEntity>,
	runs: List<SourceServiceRunEntity>,
	lineagesByRun: Map<String, List<ActivityCapturedLineage>>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
	collectedDataEpoch: Long,
) {
	if (sessions.values.any { session -> !session.hasTerminalPortableActivityBounds() }) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
	}
	val runIds = runs.map(SourceServiceRunEntity::serviceRunId)
	val capturedRuns = validatedPortableActivityCapturedRuns(runs, manifestsByRun, sourcesByRun)
	val completeness = loadPortableActivityCompleteness(limits, runIds)
	val completenessTargetByRun = validatedPortableActivityCompletenessTargets(completeness, runs, runIds, capturedRuns)
	val retainedWal = PortableActivityWalReader(this, limits).readTargets(
		runs = runs,
		manifestsByRun = manifestsByRun,
		sourcesByRun = sourcesByRun,
		collectedDataEpoch = collectedDataEpoch,
	)
	validatePortableActivityCompletenessAuthority(
		limits = limits,
		rows = completeness,
		retainedWalRows = retainedWal.rows,
		runs = runs,
		manifestsByRun = manifestsByRun,
		sourcesByRun = sourcesByRun,
		collectedDataEpoch = collectedDataEpoch,
	)
	val retainedWalTargetByRun = retainedWal.targetByRun
	if (retainedWalTargetByRun.any { (runId, retainedTarget) ->
			retainedTarget > (completenessTargetByRun[runId] ?: 0L)
		}
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
	}
	val targetByRun = completenessTargetByRun.mapValues { (runId, completenessTarget) ->
		completenessTarget?.let { target ->
			maxOf(target, retainedWalTargetByRun[runId] ?: 0L)
		}
	}
	val groupTarget = targetByRun.values.filterNotNull().maxOrNull()
		?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)

	val lane = readPortableActivityProjectionLane(laneExecutionAuthority, sessions, manifestsByRun, groupTarget)
	validatePortableActivitySessionAdmission(sessions, runs, targetByRun)
	validatePortableActivityFactAdmission(lane, lineagesByRun, targetByRun)
	validatePortableActivityProjectionDrain(limits, runIds, lane, groupTarget)
}

private fun validatedPortableActivityCapturedRuns(
	runs: List<SourceServiceRunEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
): Set<String> {
	val capturedRuns = linkedSetOf<String>()
	runs.forEach { run ->
		val manifests = manifestsByRun[run.serviceRunId].orEmpty()
		val sources = sourcesByRun[run.serviceRunId].orEmpty()
		if (!run.hasValidPortableActivityManifests(manifests, sources) || sources.any { source ->
				source.hasUnboundPortableActivityCapture(manifests)
			}
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		if (manifests.any { manifest ->
			sources.any { source -> source.isPortableCapturedActivityMember(manifest) }
		}) {
			capturedRuns += run.serviceRunId
		}
	}
	return capturedRuns
}

private fun validatedPortableActivityCompletenessTargets(
	completeness: List<SourceSessionCompletenessEntity>,
	runs: List<SourceServiceRunEntity>,
	runIds: List<String>,
	capturedRuns: Set<String>,
): Map<String, Long?> {
	if (!hasValidPortableCompleteness(completeness, runs) ||
		completeness.mapTo(hashSetOf(), SourceSessionCompletenessEntity::serviceRunId) != runIds.toSet()
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	if (completeness.any { row -> !row.appDrainComplete }) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
	}
	val completenessTargetByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId)
		.mapValues { (_, rows) ->
			rows.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull()
		}
	if (capturedRuns.any { runId -> completenessTargetByRun[runId] == null }) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	return completenessTargetByRun
}

private suspend fun AppDatabase.readPortableActivityProjectionLane(
	laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	sessions: Map<String, LogicalTrackingSessionEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	groupTarget: Long,
): SourceProductProjectionLaneEntity {
	val lane = sourceProjectionStateDao().productLane(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		bindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		projectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
	) ?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	if (!laneExecutionAuthority.owns(lane) || !hasValidPortableActivityLane(lane)) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	if (!lane.hasPortableActivityCaptureCoverage(sessions, manifestsByRun)) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}

	if (groupTarget < lane.activationOrdinal ||
		lane.captureAdmissionCutoffOrdinal?.let { cutoff -> groupTarget > cutoff } == true
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	return lane
}

private fun SourceProductProjectionLaneEntity.hasPortableActivityCaptureCoverage(
	sessions: Map<String, LogicalTrackingSessionEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
): Boolean {
	val requiredModeMask = sessions.values.fold(0L) { mask, session ->
		mask or when (session.sessionMode) {
			"MANUAL" -> MANUAL_SESSION_CAPTURE_MASK
			"AUTOMATIC" -> AUTOMATIC_SESSION_CAPTURE_MASK
			else -> abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
	}
	return requiredModeMask != 0L && captureModeMask and requiredModeMask == requiredModeMask &&
		manifestsByRun.values.flatten().all { manifest -> activatedRolloutRevision <= manifest.rolloutRevision }
}

private fun validatePortableActivitySessionAdmission(
	sessions: Map<String, LogicalTrackingSessionEntity>,
	runs: List<SourceServiceRunEntity>,
	targetByRun: Map<String, Long?>,
) {
	sessions.values.forEach { session ->
		val sessionTarget = runs.asSequence()
			.filter { run -> run.logicalTrackingId == session.logicalTrackingId }
			.mapNotNull { run -> targetByRun[run.serviceRunId] }
			.maxOrNull()
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (session.finalAdmissionOrdinal?.let { final -> final >= sessionTarget } != true) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
	}
}

private fun validatePortableActivityFactAdmission(
	lane: SourceProductProjectionLaneEntity,
	lineagesByRun: Map<String, List<ActivityCapturedLineage>>,
	targetByRun: Map<String, Long?>,
) {
	lineagesByRun.forEach { (runId, lineages) ->
		val target = targetByRun[runId]
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (lineages.asSequence().flatMap { lineage -> lineage.revisions.asSequence() }
			.flatMap { revision -> revision.evidence.asSequence() }
			.any { evidence ->
				evidence.sourceAdmissionOrdinal < lane.activationOrdinal ||
					evidence.sourceAdmissionOrdinal > target
			}
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
	}
}

private suspend fun AppDatabase.validatePortableActivityProjectionDrain(
	limits: PortableActivityReadLimits,
	runIds: List<String>,
	lane: SourceProductProjectionLaneEntity,
	groupTarget: Long,
) {
	val failures = trackingHistoryReadDao().terminalFailuresForServiceRuns(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		serviceRunIds = runIds,
		afterOrdinal = lane.activationOrdinal - 1L,
		throughOrdinal = groupTarget,
		limit = limits.maximumTerminalFailures + 1,
	)
	if (failures.size > limits.maximumTerminalFailures) {
		portableActivityDependencyOverflow()
	}
	if (failures.isNotEmpty()) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	if (lane.contiguousAdmissionOrdinal < groupTarget) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING)
	}
}

internal fun LogicalTrackingSessionEntity.hasTerminalPortableActivityBounds(): Boolean {
	if (state !in TERMINAL_SESSION_STATES || currentServiceRunId != null) {
		return false
	}
	return completedAtMs != null && cutoffAtMs != null && cutoffElapsedNanos != null
}

internal fun SourceServiceRunEntity.hasTerminalPortableActivityRun(): Boolean =
	state in TERMINAL_RUN_STATES && completedAtMs != null &&
		presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED

internal fun SourceServiceRunEntity.hasValidPortableActivityManifests(
	manifests: List<SessionManifestVersionEntity>,
	sources: List<SessionManifestSourceEntity>,
): Boolean = SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(this, manifests) &&
	manifests.all { manifest ->
		SessionManifestIntegrity.verify(
			manifest,
			sources.filter { source -> source.belongsToPortableActivityManifest(manifest) },
		)
	}

private fun SessionManifestSourceEntity.hasUnboundPortableActivityCapture(
	manifests: List<SessionManifestVersionEntity>,
): Boolean {
	if (sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
		purpose != SessionManifestPurposeCode.SESSION_CAPTURE || !persistenceEligible
	) {
		return false
	}
	return manifests.singleOrNull { manifest -> belongsToPortableActivityManifest(manifest) }
		?.let { manifest -> isPortableCapturedActivityMember(manifest) } != true
}

private fun hasValidPortableCompleteness(
	rows: List<SourceSessionCompletenessEntity>,
	runs: List<SourceServiceRunEntity>,
): Boolean {
	val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
	return runsById.size == runs.size && rows.groupBy(SourceSessionCompletenessEntity::serviceRunId)
		.all { (runId, runRows) ->
			val run = runsById[runId] ?: return@all false
			val generations = runRows.map(SourceSessionCompletenessEntity::registrationGeneration)
			if (generations.distinct().size != generations.size) {
				return@all false
			}
			val highWaters = runRows.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
				.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal)
			if (!highWaters.zipWithNext().all { (left, right) -> right > left }) {
				return@all false
			}
			runRows.all { row -> row.hasValidPortableShape(run) }
		}
}

// Validate the complete saved completeness-row shape, including its synthetic zero-generation variants.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceSessionCompletenessEntity.hasValidPortableShape(
	run: SourceServiceRunEntity,
): Boolean {
	val unresolvedStart = unresolvedSequenceStart
	val unresolvedEnd = unresolvedSequenceEnd
	if (logicalTrackingId != run.logicalTrackingId || serviceRunId != run.serviceRunId ||
		sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY || sourceInstanceId.isBlank() ||
		registrationGeneration < 0L || lastAdmissionOrdinal?.let { ordinal -> ordinal <= 0L } == true ||
		lastSourceSequence?.let { sequence -> sequence < 0L } == true ||
		(lastAdmissionOrdinal == null) != (lastSourceSequence == null) ||
		(unresolvedStart == null) != (unresolvedEnd == null) ||
		unresolvedStart?.let { start -> start <= 0L || start > requireNotNull(unresolvedEnd) } == true ||
		providerCoverage !in PORTABLE_PROVIDER_COVERAGES || stopStatus !in PORTABLE_STOP_STATUSES ||
		(stopStatus == COMPLETE_STOP_STATUS && !appDrainComplete) || updatedAtMs < 0L
	) {
		return false
	}
	if (registrationGeneration > 0L) {
		return sourceInstanceId !in SYNTHETIC_ACTIVITY_INSTANCES
	}
	if (lastAdmissionOrdinal != null || lastSourceSequence != null || unresolvedStart != null ||
		unresolvedEnd != null || providerCoverage != UNOBSERVABLE_PROVIDER_COVERAGE
	) {
		return false
	}
	return when (sourceInstanceId) {
		NOT_OWNED_ACTIVITY_INSTANCE -> stopStatus == COMPLETE_STOP_STATUS && appDrainComplete
		UNRESOLVED_ACTIVITY_INSTANCE ->
			stopStatus in PROVIDER_UNAVAILABLE_STOP_STATUSES && !appDrainComplete
		UNREGISTERED_ACTIVITY_INSTANCE ->
			stopStatus in setOf(COMPLETE_STOP_STATUS, "PROVIDER_FAILED") && appDrainComplete
		else -> false
	}
}

// Validate one saved lane and its complete ACTIVE/RETIRED record shape; no cursor is advanced here.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun hasValidPortableActivityLane(lane: SourceProductProjectionLaneEntity): Boolean {
	if (lane.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
		lane.bindingGeneration != SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION ||
		lane.projectionId != SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID ||
		lane.projectionVersion != SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION ||
		lane.captureModeMask <= 0L ||
		lane.captureModeMask and PORTABLE_SESSION_CAPTURE_MASK.inv() != 0L ||
		lane.productStage !in PORTABLE_ACTIVITY_PRODUCT_STAGES || lane.activatedRolloutRevision <= 0L ||
		lane.activationOrdinal <= 0L || lane.installedAtMs < 0L || lane.updatedAtMs < lane.installedAtMs
	) {
		return false
	}
	val minimumCursor = lane.activationOrdinal - 1L
	val cutoff = lane.captureAdmissionCutoffOrdinal
	if (lane.contiguousAdmissionOrdinal < minimumCursor ||
		(cutoff != null && (cutoff < minimumCursor || lane.contiguousAdmissionOrdinal > cutoff))
	) {
		return false
	}
	return when (lane.status) {
		SourceProductProjectionLaneEntity.STATUS_ACTIVE ->
			lane.retentionRequired && cutoff == null && lane.terminalDisposition == null &&
				lane.terminalAtMs == null
		SourceProductProjectionLaneEntity.STATUS_RETIRED -> {
			val terminalAt = lane.terminalAtMs ?: return false
			!lane.retentionRequired &&
				lane.terminalDisposition ==
				SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
				cutoff != null && lane.contiguousAdmissionOrdinal == cutoff &&
				terminalAt >= lane.installedAtMs && lane.updatedAtMs >= terminalAt
		}
		else -> false
	}
}

/** Reads and authenticates retained Activity WAL deliveries inside the caller's Room snapshot. */
private class PortableActivityWalReader(
	private val database: AppDatabase,
	private val limits: PortableActivityReadLimits,
) {
	suspend fun readTargets(
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
		collectedDataEpoch: Long,
	): PortableActivityWalTargetAudit {
		val selectedRows = mutableListOf<ActivityCapturedPortableWalTargetRow>()
		runs.map(SourceServiceRunEntity::serviceRunId).chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).forEach { runIds ->
			currentCoroutineContext().ensureActive()
			selectedRows += database.activityCapturedFactDao().portableCapturedWalTargets(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				serviceRunIds = runIds,
				limit = limits.maximumCapturedWalRows - selectedRows.size + 1,
			)
			if (selectedRows.size > limits.maximumCapturedWalRows) {
				portableActivityDependencyOverflow()
			}
		}
		val rows = loadDeliveryRows(selectedRows)
		val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
		validateRowIntegrity(rows, collectedDataEpoch)
		validateSelectedCaptureBindings(selectedRows, runsById, manifestsByRun, sourcesByRun)
		validatePortableWalDeliveryGroups(rows)
		return PortableActivityWalTargetAudit(
			rows = rows,
			targetByRun = selectedRows.groupBy { row -> requireNotNull(row.serviceRunId) }
				.mapValues { (_, values) -> values.maxOf { row -> row.admissionOrdinal } },
		)
	}

	private suspend fun loadDeliveryRows(
		selectedRows: List<ActivityCapturedPortableWalTargetRow>,
	): List<ActivityCapturedPortableWalTargetRow> {
		val selectedDeliveryIdentities = selectedRows.map(
			ActivityCapturedPortableWalTargetRow::deliveryIdentity,
		)
		if (selectedDeliveryIdentities.any { identity ->
				identity == null || !ACTIVITY_PORTABLE_SHA_256_HEX.matches(identity)
			}
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val rows = mutableListOf<ActivityCapturedPortableWalTargetRow>()
		selectedDeliveryIdentities.map { identity -> requireNotNull(identity) }.distinct()
			.chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).forEach { deliveryBatch ->
				currentCoroutineContext().ensureActive()
				rows += database.activityCapturedFactDao().portableCapturedWalDeliveryMembers(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
					deliveryIdentities = deliveryBatch,
					limit = limits.maximumCapturedWalRows - rows.size + 1,
				)
				if (rows.size > limits.maximumCapturedWalRows) {
					portableActivityDependencyOverflow()
				}
		}
		val deliveryEventIds = rows.map(ActivityCapturedPortableWalTargetRow::eventId)
		val selectedEventIds = selectedRows.map(ActivityCapturedPortableWalTargetRow::eventId)
		if (deliveryEventIds.distinct().size != deliveryEventIds.size ||
			selectedEventIds.distinct().size != selectedEventIds.size ||
			!deliveryEventIds.toSet().containsAll(selectedEventIds)
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		return rows
	}

	// Validate every field of each saved WAL row in original order.
	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun validateRowIntegrity(
		rows: List<ActivityCapturedPortableWalTargetRow>,
		collectedDataEpoch: Long,
	) {
		rows.forEach { row ->
			if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
				row.admissionOrdinal <= 0L ||
				row.eventId.isBlank() || row.deliveryIdentity.isNullOrBlank() ||
				!row.hasValidDeliveryShape() ||
				row.sourceSequence < 0L || row.sourceInstanceId.isBlank() ||
				row.registrationGeneration <= 0L ||
				row.physicalConfigurationFingerprint.isNullOrBlank() ||
				row.authorizationRevision?.let { revision -> revision > 0L } != true ||
				row.authorizationFingerprint.isNullOrBlank() ||
				row.authorizationPurposeEligibilityMask and SourceBrokerPurpose.ALL_MASK !=
					row.authorizationPurposeEligibilityMask ||
				row.providerDedupKey != null || row.configRevision?.let { revision -> revision > 0L } != true ||
				row.clockDomainId.isBlank() || row.observedElapsedNanos < 0L ||
				row.observedIntervalStartNanos != row.observedElapsedNanos ||
				row.receivedElapsedNanos < row.observedElapsedNanos ||
				row.wallTimeMs == null || row.wallTimeMs < 0L ||
				row.wallTimeUncertaintyMs == null || row.wallTimeUncertaintyMs < 0L ||
				row.capturedCollectedDataEpoch != collectedDataEpoch ||
				row.acquiredAtMs < 0L ||
				row.qualityConfidence?.let { confidence -> !confidence.isFinite() } == true ||
				row.payloadVersion != ACTIVITY_CAPTURED_WAL_PAYLOAD_VERSION ||
				row.payloadBytes !in 1L..MAX_ACTIVITY_CAPTURED_WAL_PAYLOAD_BYTES ||
				!ACTIVITY_PORTABLE_SHA_256_HEX.matches(row.payloadChecksum) ||
				!ACTIVITY_PORTABLE_SHA_256_HEX.matches(row.integrityIdentity) ||
				row.integrityIdentity != row.calculatedIntegrityIdentity()
			) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
	}

	// Compare each saved row with its exact run, manifest and source; all bounded lookups stay ordered.
	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun validateSelectedCaptureBindings(
		selectedRows: List<ActivityCapturedPortableWalTargetRow>,
		runsById: Map<String, SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
	) {
		selectedRows.forEach { row ->
			val runId = row.serviceRunId
			val run = runId?.let(runsById::get)
			val manifestRevision = row.sessionManifestRevision
			val manifest = manifestsByRun[runId].orEmpty().singleOrNull { candidate ->
				candidate.manifestRevision == manifestRevision
			}
			val source = sourcesByRun[runId].orEmpty().singleOrNull { candidate ->
				manifest != null && candidate.isPortableCapturedActivityMember(manifest)
			}
			if (run == null || manifest == null || source == null ||
				row.logicalTrackingId != run.logicalTrackingId ||
				row.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
				row.configRevision != manifest.acquisitionPlanRevision ||
				row.planAttribution != ACTIVITY_PORTABLE_CAPTURED_PLAN_ATTRIBUTION ||
				row.clockDomainId != manifest.effectiveBootId ||
				row.observedElapsedNanos < manifest.effectiveElapsedRealtimeNanos ||
				row.sourcePolicyRevision != manifest.sourcePolicyRevision ||
				row.captureConsentEpoch != source.consentEpoch ||
				!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, row.lifecycleLeaseGeneration)
			) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
	}

	private fun validatePortableWalDeliveryGroups(
		rows: List<ActivityCapturedPortableWalTargetRow>,
	) {
		rows.groupBy { row -> requireNotNull(row.deliveryIdentity) }.values.forEach { members ->
			val ordered = members.sortedBy { row -> row.deliveryUnitIndex }
			val first = ordered.first()
			val declaredCount = requireNotNull(first.deliveryUnitCount)
			if (ordered.mapNotNull { it.deliveryUnitIndex }.distinct().size != ordered.size ||
				ordered.any { row ->
					row.deliveryUnitCount != declaredCount ||
						!row.hasSamePortableProviderDeliveryAs(first) ||
						row.observedIntervalStartNanos != row.observedElapsedNanos
				}
			) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			if (ordered.zipWithNext().any { (previous, current) ->
					current.sourceSequence <= previous.sourceSequence
				}
			) {
				abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
	}
}

private val TERMINAL_SESSION_STATES = setOf("FINALIZED", "CLOSED", "FAILED")

private val TERMINAL_RUN_STATES = setOf("FINALIZED", "CLOSED", "FAILED")

private const val MANUAL_SESSION_CAPTURE_MASK = 1L

private const val AUTOMATIC_SESSION_CAPTURE_MASK = 2L

private const val PORTABLE_SESSION_CAPTURE_MASK =
	MANUAL_SESSION_CAPTURE_MASK or AUTOMATIC_SESSION_CAPTURE_MASK

private const val ACTIVITY_CAPTURED_WAL_PAYLOAD_VERSION = 1

private const val MAX_ACTIVITY_DELIVERY_UNITS = 256

private const val MAX_ACTIVITY_CAPTURED_WAL_PAYLOAD_BYTES = 21L

private const val COMPLETE_STOP_STATUS = "COMPLETE"

private const val UNOBSERVABLE_PROVIDER_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"

private const val NOT_OWNED_ACTIVITY_INSTANCE = "not-owned-activity"

private const val UNRESOLVED_ACTIVITY_INSTANCE = "unresolved-activity"

private const val UNREGISTERED_ACTIVITY_INSTANCE = "activity-unregistered"

private val SYNTHETIC_ACTIVITY_INSTANCES = setOf(
	NOT_OWNED_ACTIVITY_INSTANCE,
	UNRESOLVED_ACTIVITY_INSTANCE,
	UNREGISTERED_ACTIVITY_INSTANCE,
)

private val PORTABLE_ACTIVITY_PRODUCT_STAGES = setOf(
	SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
	SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
)

private val PORTABLE_PROVIDER_COVERAGES = setOf(
	"CALLBACKS_ENTERED_BEFORE_BARRIER",
	UNOBSERVABLE_PROVIDER_COVERAGE,
)

private val PORTABLE_STOP_STATUSES = setOf(
	COMPLETE_STOP_STATUS,
	"TIMED_OUT",
	"PERMISSION_LOST",
	"PROVIDER_FAILED",
	"PROCESS_RESTARTED",
)

private val PROVIDER_UNAVAILABLE_STOP_STATUSES = setOf(
	"TIMED_OUT",
	"PERMISSION_LOST",
	"PROVIDER_FAILED",
)

private data class PortableActivityWalTargetAudit(
	val rows: List<ActivityCapturedPortableWalTargetRow>,
	val targetByRun: Map<String, Long>,
)

private fun ActivityCapturedPortableWalTargetRow.calculatedIntegrityIdentity(): String {
	val legacyCanonical = listOf(
		deliveryIdentity,
		deliveryUnitIndex,
		deliveryUnitCount,
		logicalTrackingId,
		serviceRunId,
		sourceKind,
		sourceInstanceId,
		registrationGeneration,
		physicalConfigurationFingerprint,
		authorizationRevision,
		authorizationPurposeEligibilityMask,
		authorizationFingerprint,
		sourceSequence,
		providerDedupKey,
		configRevision,
		planAttribution,
		clockDomainId,
		observedElapsedNanos,
		observedIntervalStartNanos,
		receivedElapsedNanos,
		wallTimeMs,
		wallTimeUncertaintyMs,
		capturedCollectedDataEpoch,
		activityAutomationEpoch,
		sourcePolicyRevision,
		captureConsentEpoch,
		sessionManifestRevision,
		lifecycleLeaseGeneration,
		acquiredAtMs,
		qualityFlags,
		qualityConfidence,
		payloadVersion,
		payloadChecksum,
	)
	val canonical = (if (receivedWallTimeMs == null) {
		legacyCanonical
	} else {
		legacyCanonical + receivedWallTimeMs
	}).joinToString(separator = "") { value ->
		val text = value?.toString()
		if (text == null) {
			"-1:"
		} else {
			"${text.length}:$text"
		}
	}
	return portableActivitySha256(canonical.toByteArray())
}

private fun ActivityCapturedPortableWalTargetRow.hasValidDeliveryShape(): Boolean {
	val unitIndex = deliveryUnitIndex ?: return false
	val unitCount = deliveryUnitCount ?: return false
	return unitCount in 1..MAX_ACTIVITY_DELIVERY_UNITS && unitIndex in 0 until unitCount
}

@Suppress("ComplexCondition")
private fun ActivityCapturedPortableWalTargetRow.hasSamePortableProviderDeliveryAs(
	other: ActivityCapturedPortableWalTargetRow,
): Boolean =
	sourceKind == other.sourceKind &&
		sourceInstanceId == other.sourceInstanceId &&
		registrationGeneration == other.registrationGeneration &&
		physicalConfigurationFingerprint == other.physicalConfigurationFingerprint &&
		configRevision == other.configRevision &&
		clockDomainId == other.clockDomainId &&
		receivedElapsedNanos == other.receivedElapsedNanos &&
		capturedCollectedDataEpoch == other.capturedCollectedDataEpoch
