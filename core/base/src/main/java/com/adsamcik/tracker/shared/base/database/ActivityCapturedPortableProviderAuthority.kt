package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedPortableWalTargetRow
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedPlanIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedDesiredPlan

// Keep the original complete snapshot inputs explicit; no provider authority is read again.
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
internal suspend fun AppDatabase.validatePortableActivityCompletenessAuthority(
	limits: PortableActivityReadLimits,
	rows: List<SourceSessionCompletenessEntity>,
	retainedWalRows: List<ActivityCapturedPortableWalTargetRow>,
	runs: List<SourceServiceRunEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
	collectedDataEpoch: Long,
) {
	val positiveRows = rows.filter { row -> row.registrationGeneration > 0L }
	if (positiveRows.isEmpty()) {
		return
	}
	val generations = positiveRows.map(SourceSessionCompletenessEntity::registrationGeneration).distinct()
	val plans = loadPortableActivityRegistrationPlans(limits, generations)
	val expectedPlanKeys = positiveRows.mapTo(hashSetOf()) { row ->
		row.sourceInstanceId to row.registrationGeneration
	}
	val plansByKey = plans.associateBy { plan -> plan.sourceInstanceId to plan.registrationGeneration }
	if (plansByKey.size != plans.size || plansByKey.keys != expectedPlanKeys) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	if (retainedWalRows.any { wal ->
			val plan = plansByKey[wal.sourceInstanceId to wal.registrationGeneration]
			plan == null || wal.configRevision != plan.configurationRevision ||
				wal.physicalConfigurationFingerprint != plan.physicalConfigurationFingerprint
		}
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	val desiredPlans = loadPortableActivityDesiredPlans(
		limits,
		plans.map(ActivityCapturedRegistrationPlanEntity::configurationRevision).distinct(),
	)
	val desiredByRevision = desiredPlans.associateBy(SourceDesiredPlanEntity::revision)
	if (desiredByRevision.size != desiredPlans.size ||
		desiredByRevision.keys != plans.mapTo(hashSetOf(), ActivityCapturedRegistrationPlanEntity::configurationRevision)
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	val providers = loadPortableActivityProviderRegistrations(limits, generations)
	val providersByGeneration = providers.associateBy(ProviderRegistrationGenerationEntity::registrationGeneration)
	if (providersByGeneration.size != providers.size || providersByGeneration.keys != generations.toSet()) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	val runIds = runs.map(SourceServiceRunEntity::serviceRunId)
	val actions = loadPortableActivityLifecycleActions(limits, runIds)
	val completeAuthorizationRows = loadPortableActivityAuthorizationTimeline(limits, generations)
	val authorizationsByKey = completeAuthorizationRows.groupBy { authorization ->
		PortableActivityAuthorizationKey(
			authorization.registrationGeneration,
			authorization.authorizationRevision,
		)
	}
	val demandsById = validatePortableActivityAuthorizationRevisions(limits, authorizationsByKey)
	val authorizationTimelines = validatePortableActivityAuthorizationTimelines(
		providersByGeneration,
		authorizationsByKey,
	)
	val authorizations = authorizationsByKey.values.flatten()
	validatePortableActivityWalAuthorizations(
		retainedWalRows,
		authorizationTimelines,
		providersByGeneration,
		demandsById,
	)
	val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
	positiveRows.forEach { row ->
		val plan = plansByKey[row.sourceInstanceId to row.registrationGeneration]
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val desired = desiredByRevision[plan.configurationRevision]
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val provider = providersByGeneration[row.registrationGeneration]
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val decodedPlan = ActivityCapturedPlanIntegrity.decode(desired)
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val manifests = manifestsByRun[row.serviceRunId].orEmpty()
		val sources = sourcesByRun[row.serviceRunId].orEmpty()
		val run = runsById[row.serviceRunId]
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val matchingManifests = manifests.filter { manifest ->
			manifest.acquisitionPlanRevision == plan.configurationRevision &&
				sources.any { source -> source.isPortableCapturedActivityMember(manifest) }
		}
		val acceptedActions = actions.filter { action ->
			action.serviceRunId == row.serviceRunId &&
				action.sourceInstanceId == row.sourceInstanceId &&
				action.registrationGeneration == row.registrationGeneration &&
				action.status == PORTABLE_START_ACCEPTED &&
				action.desiredState == PORTABLE_ACTION_STARTED
		}
		val capturedAuthorizations = authorizations.filter { authorization ->
			authorization.serviceRunId == row.serviceRunId &&
				authorization.registrationGeneration == row.registrationGeneration &&
				authorization.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
				authorization.persistenceEligible
		}
		if (!plan.matchesPortableActivityDesiredPlan(desired, decodedPlan) ||
			matchingManifests.isEmpty() || acceptedActions.isEmpty() || capturedAuthorizations.isEmpty()
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		if (!provider.matchesPortableActivityOwner(row) ||
			matchingManifests.any { it.effectiveBootId != provider.clockDomainId } ||
			!provider.hasPortableActivityAcceptance(plan, collectedDataEpoch)
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		validatePortableActivityCaptureClosingAuthority(
			row = row,
			run = run,
			provider = provider,
			timeline = authorizationTimelines[row.registrationGeneration].orEmpty(),
			retainedWalRows = retainedWalRows,
		)
		acceptedActions.forEach { action ->
			validatePortableAcceptedAction(action, run, matchingManifests, sources, provider, plan)
		}
		capturedAuthorizations.forEach { authorization ->
			validatePortableCaptureAuthorization(
				authorization,
				run,
				manifests,
				sources,
				provider,
				acceptedActions,
			)
		}
	}
}

// Fixed saved accepted/retired timestamps and status fields; this does not walk a timeline.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun ProviderRegistrationGenerationEntity.hasValidPortableChronology(
	plan: ActivityCapturedRegistrationPlanEntity,
): Boolean {
	val acceptedWall = acceptedAtMs ?: return false
	val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
	if (reservedAtMs > acceptedWall || reservedElapsedRealtimeNanos > acceptedElapsed ||
		plan.appliedAtElapsedRealtimeNanos != acceptedElapsed
	) {
		return false
	}
	return when (status) {
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
			retiredAtMs == null && retiredElapsedRealtimeNanos == null && failureCode == null
		ProviderRegistrationGenerationEntity.STATUS_RETIRING,
		ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		-> retiredAtMs?.let { retiredWall -> retiredWall >= acceptedWall } == true &&
			retiredElapsedRealtimeNanos?.let { retiredElapsed -> retiredElapsed >= acceptedElapsed } == true
		else -> false
	}
}

private fun ProviderRegistrationGenerationEntity.hasValidPortableActivityResidency(): Boolean =
	when (providerResidency) {
		ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ->
			!providerProcessIncarnationId.isNullOrBlank()
		ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE ->
			providerProcessIncarnationId == null
		else -> false
	}

// Resolve the exact saved manifest/source, then keep the complete accepted-action field contract visible.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun validatePortableAcceptedAction(
	action: LifecycleDesiredActionEntity,
	run: SourceServiceRunEntity,
	matchingManifests: List<SessionManifestVersionEntity>,
	sources: List<SessionManifestSourceEntity>,
	provider: ProviderRegistrationGenerationEntity,
	plan: ActivityCapturedRegistrationPlanEntity,
) {
	val manifest = matchingManifests.singleOrNull { candidate ->
		candidate.manifestRevision == action.manifestRevision
	} ?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	val source = sources.singleOrNull { candidate -> candidate.isPortableCapturedActivityMember(manifest) }
		?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	val acknowledgedWall = action.acknowledgedAtMs
	val acknowledgedElapsed = action.acknowledgedElapsedRealtimeNanos
	if (action.actionId.isBlank() || action.logicalTrackingId != run.logicalTrackingId ||
		action.serviceRunId != run.serviceRunId || action.actionRevision <= 0L ||
		action.actionFamily != PORTABLE_SOURCE_RUNTIME_ACTION ||
		action.sourceKind != SourceDestinationOwnerEntity.SOURCE_ACTIVITY ||
		action.desiredState != PORTABLE_ACTION_STARTED ||
		action.desiredPlanRevision != plan.configurationRevision ||
		action.sourcePolicyRevision != manifest.sourcePolicyRevision ||
		action.consentEpoch != source.consentEpoch || action.startOrigin != manifest.startOrigin ||
		action.bootId != manifest.effectiveBootId || action.bootId != provider.clockDomainId ||
		!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, action.leaseGeneration) ||
		action.attemptCount <= 0 ||
		action.status != PORTABLE_START_ACCEPTED || action.failureCode != null ||
		action.retryTrigger != null || action.sourceInstanceId != provider.sourceInstanceId ||
		action.registrationGeneration != provider.registrationGeneration ||
		action.requestedAtMs < run.startedAtMs ||
		action.requestedAtMs < manifest.effectiveWallTimeMs ||
		action.requestedElapsedRealtimeNanos < run.startedElapsedNanos ||
		action.requestedElapsedRealtimeNanos < manifest.effectiveElapsedRealtimeNanos ||
		acknowledgedWall == null || acknowledgedElapsed == null ||
		acknowledgedWall < action.requestedAtMs ||
		acknowledgedElapsed < action.requestedElapsedRealtimeNanos ||
		acknowledgedWall < requireNotNull(provider.acceptedAtMs) ||
		acknowledgedElapsed < requireNotNull(provider.acceptedElapsedRealtimeNanos) ||
		acknowledgedElapsed < plan.appliedAtElapsedRealtimeNanos ||
		acknowledgedWall > requireNotNull(run.completedAtMs) ||
		provider.retiredAtMs?.let { retired -> acknowledgedWall > retired } == true ||
		provider.retiredElapsedRealtimeNanos?.let { retired ->
			acknowledgedElapsed > retired
		} == true
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
}

// One saved authorization binding, including its exact accepted-action match; no timeline reconstruction.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun validatePortableCaptureAuthorization(
	authorization: SourceAuthorizationEntity,
	run: SourceServiceRunEntity,
	manifests: List<SessionManifestVersionEntity>,
	sources: List<SessionManifestSourceEntity>,
	provider: ProviderRegistrationGenerationEntity,
	acceptedActions: List<LifecycleDesiredActionEntity>,
) {
	val manifestRevision = authorization.manifestRevision
	val manifest = manifests.singleOrNull { candidate ->
		candidate.manifestRevision == manifestRevision
	} ?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	val source = sources.singleOrNull { candidate -> candidate.isPortableCapturedActivityMember(manifest) }
		?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	val acceptedElapsed = requireNotNull(provider.acceptedElapsedRealtimeNanos)
	val acceptedWall = requireNotNull(provider.acceptedAtMs)
	if (authorization.isDenyAll || authorization.memberId.isBlank() ||
		authorization.authorizationRevision <= 0L ||
		authorization.authorizationFingerprint.isBlank() ||
		authorization.purpose != SourceBrokerPurpose.SESSION_CAPTURE ||
		authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
		authorization.logicalTrackingId != run.logicalTrackingId ||
		authorization.serviceRunId != run.serviceRunId ||
		!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, authorization.lifecycleLeaseGeneration) ||
		authorization.sourcePolicyRevision != manifest.sourcePolicyRevision ||
		authorization.consentEpoch != source.consentEpoch || !authorization.persistenceEligible ||
		authorization.effectiveBootId != provider.clockDomainId ||
		authorization.effectiveBootId != manifest.effectiveBootId ||
		authorization.effectiveElapsedRealtimeNanos < run.startedElapsedNanos ||
		authorization.effectiveElapsedRealtimeNanos < manifest.effectiveElapsedRealtimeNanos ||
		authorization.effectiveElapsedRealtimeNanos < acceptedElapsed ||
		authorization.effectiveWallTimeMs < run.startedAtMs ||
		authorization.effectiveWallTimeMs < manifest.effectiveWallTimeMs ||
		authorization.effectiveWallTimeMs < acceptedWall ||
		authorization.effectiveWallTimeMs > requireNotNull(run.completedAtMs) ||
		provider.retiredElapsedRealtimeNanos?.let { retired ->
			authorization.effectiveElapsedRealtimeNanos > retired
		} == true || provider.retiredAtMs?.let { retired ->
			authorization.effectiveWallTimeMs > retired
		} == true || acceptedActions.none { action ->
			action.manifestRevision == manifest.manifestRevision &&
				action.leaseGeneration == authorization.lifecycleLeaseGeneration &&
				action.bootId == authorization.effectiveBootId
		}
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
}

private fun ActivityCapturedRegistrationPlanEntity.matchesPortableActivityDesiredPlan(
	desired: SourceDesiredPlanEntity,
	decodedPlan: ActivityCapturedDesiredPlan,
): Boolean {
	if (desiredPlanPayloadVersion != desired.payloadVersion ||
		!desiredPlanPayload.contentEquals(desired.payload) ||
		desiredPlanPayloadChecksum != desired.payloadChecksum
	) {
		return false
	}
	return desiredPlanPayloadChecksum == portableActivitySha256(desiredPlanPayload) &&
		bindingIdentity == calculatedBindingIdentity() && decodedPlan.enabled &&
		physicalConfigurationFingerprint == decodedPlan.physicalConfigurationFingerprint
}

private fun ProviderRegistrationGenerationEntity.matchesPortableActivityOwner(
	row: SourceSessionCompletenessEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY &&
	sourceInstanceId == row.sourceInstanceId && registrationGeneration == row.registrationGeneration &&
	ownerScope == PORTABLE_ACTIVITY_PROVIDER_OWNER_SCOPE

private fun ProviderRegistrationGenerationEntity.hasPortableActivityAcceptance(
	plan: ActivityCapturedRegistrationPlanEntity,
	collectedDataEpoch: Long,
): Boolean {
	if (physicalConfigurationFingerprint != plan.physicalConfigurationFingerprint ||
		this.collectedDataEpoch != collectedDataEpoch || acceptedAtMs == null || acceptedElapsedRealtimeNanos == null
	) {
		return false
	}
	return hasValidPortableActivityResidency() && status in PORTABLE_ACCEPTED_PROVIDER_STATUSES &&
		hasValidPortableChronology(plan)
}

private const val PORTABLE_SOURCE_RUNTIME_ACTION = "SOURCE_RUNTIME"

private const val PORTABLE_ACTION_STARTED = "STARTED"

private const val PORTABLE_START_ACCEPTED = "START_ACCEPTED"

private val PORTABLE_ACTIVITY_PROVIDER_OWNER_SCOPE =
	"source-broker:${SourceDestinationOwnerEntity.SOURCE_ACTIVITY}"

private val PORTABLE_ACCEPTED_PROVIDER_STATUSES = setOf(
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
	ProviderRegistrationGenerationEntity.STATUS_RETIRING,
	ProviderRegistrationGenerationEntity.STATUS_RETIRED,
)
