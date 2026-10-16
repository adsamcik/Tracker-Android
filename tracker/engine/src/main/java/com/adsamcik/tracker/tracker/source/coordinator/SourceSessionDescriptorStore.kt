package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationDebt
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationSourcePlan
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession.ActiveSession
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.Base64

internal suspend fun TrackerServiceSourceSession.persistCatalogReconfigurationDebt(
	session: ActiveSession,
	request: SessionReconfigureRequest,
	deferredSources: Set<SourceKind>,
): Boolean {
	val stored = currentStoredDescriptor(session) ?: return false
	val debt = request.toCatalogReconfigurationDebt(session, deferredSources, sourcePlanCodec)
	return replaceCatalogDebt(stored, session, debt).also { replaced ->
		if (replaced) {
			session.catalogReconfigurationDebt = debt
		}
	}
}

internal suspend fun TrackerServiceSourceSession.updateCatalogReconfigurationDebtSources(
	session: ActiveSession,
	deferredSources: Set<SourceKind>,
): Boolean {
	val activeDebt = session.catalogReconfigurationDebt ?: return false
	val stored = currentStoredDescriptor(session) ?: return false
	val currentDebt = stored.catalogReconfigurationDebt
		?.takeIf { debt -> debt == activeDebt }
		?: return false
	val replacementDebt = currentDebt.copy(
		deferredSourceMask = deferredSources.toSourceSessionMask(),
	)
	return replaceCatalogDebt(
		stored,
		session,
		replacementDebt,
	).also { replaced ->
		if (replaced) {
			session.catalogReconfigurationDebt = replacementDebt
		}
	}
}

internal suspend fun TrackerServiceSourceSession.clearCatalogReconfigurationDebt(session: ActiveSession): Boolean {
	if (session.catalogReconfigurationDebt == null) {
		return true
	}
	val stored = currentStoredDescriptor(session) ?: return false
	if (stored.catalogReconfigurationDebt == null) {
		session.catalogReconfigurationDebt = null
		return true
	}
	return replaceCatalogDebt(stored, session, null).also { replaced ->
		if (replaced) {
			session.catalogReconfigurationDebt = null
		}
	}
}

// Keep accepted-identity, absent-debt and exact stored-debt rejection exits before any replacement.
@Suppress("ReturnCount")
internal suspend fun TrackerServiceSourceSession.clearCatalogDebtSatisfiedBySuccessfulStart(
	session: ActiveSession,
	allowMissingAppliedIdentity: Boolean = false,
): Boolean {
	if (!allowMissingAppliedIdentity &&
		session.appliedSourcePlanIdentity != session.desiredSourcePlanIdentity
	) {
		return true
	}
	if (session.catalogReconfigurationDebt == null &&
		session.origin != SessionStartOrigin.RECOVERY
	) {
		return true
	}
	val stored = currentStoredDescriptor(session) ?: return false
	val inheritedDebt = stored.catalogReconfigurationDebt
	if (inheritedDebt == null) {
		session.catalogReconfigurationDebt = null
		return true
	}
	session.catalogReconfigurationDebt = inheritedDebt
	return replaceCatalogDebt(stored, session, null).also { replaced ->
		if (replaced) {
			session.catalogReconfigurationDebt = null
		}
	}
}

private suspend fun TrackerServiceSourceSession.currentStoredDescriptor(
	session: ActiveSession,
): ActiveTrackingSessionDescriptor? = when (val result = activeTrackingSessionStore.read()) {
	is ActiveTrackingSessionStoreResult.Failure -> null
	is ActiveTrackingSessionStoreResult.Success -> result.descriptor?.takeIf { stored ->
		stored.logicalTrackingId == session.logicalTrackingId &&
			stored.serviceRunId == session.serviceRunId
	}
}

internal suspend fun TrackerServiceSourceSession.replaceCatalogDebt(
	stored: ActiveTrackingSessionDescriptor,
	session: ActiveSession,
	debt: CatalogReconfigurationDebt?,
): Boolean {
	val replacement = stored.copy(
		catalogReconfigurationDebt = debt,
		appliedSourcePlanIdentity = session.appliedSourcePlanIdentity,
		desiredSourcePlanIdentity = session.desiredSourcePlanIdentity,
	)
	if (replacement == stored) {
		return true
	}
	return when (val result = activeTrackingSessionStore.replaceExact(stored, replacement)) {
		is ActiveTrackingSessionStoreResult.Failure -> false
		is ActiveTrackingSessionStoreResult.Success -> result.descriptor == replacement
	}
}

internal suspend fun TrackerServiceSourceSession.persistSourcePlanIdentities(session: ActiveSession): Boolean {
	val stored = currentStoredDescriptor(session) ?: return false
	val replacement = stored.copy(
		appliedSourcePlanIdentity = session.appliedSourcePlanIdentity,
		desiredSourcePlanIdentity = session.desiredSourcePlanIdentity,
	)
	if (replacement == stored) {
		return true
	}
	return when (val result = activeTrackingSessionStore.replaceExact(stored, replacement)) {
		is ActiveTrackingSessionStoreResult.Failure -> false
		is ActiveTrackingSessionStoreResult.Success -> result.descriptor == replacement
	}
}

private fun SessionReconfigureRequest.toCatalogReconfigurationDebt(
	session: ActiveSession,
	deferredSources: Set<SourceKind>,
	codec: SourcePlanCodec,
): CatalogReconfigurationDebt {
	val policyRevision = requireNotNull(plan.sourcePolicyRevision)
	return CatalogReconfigurationDebt(
		logicalTrackingId = session.logicalTrackingId,
		serviceRunId = session.serviceRunId,
		sourcePolicyRevision = policyRevision,
		desiredPlanGeneration = desiredPlanGeneration,
		desiredPlanFingerprint = requireNotNull(desiredPlanFingerprint),
		requestedPlanRevision = plan.revision,
		requestedPlanId = plan.planId,
		requestedPlanCreatedAtMs = plan.createdAtMs,
		requestedPlans = plan.plans.values
			.sortedBy { sourcePlan -> sourcePlan.source.stableCode }
			.map { sourcePlan ->
				val encoded = codec.encode(sourcePlan)
				CatalogReconfigurationSourcePlan(
					sourceStableCode = sourcePlan.source.stableCode,
					payloadVersion = encoded.formatVersion,
					payloadBase64 = Base64.getEncoder().encodeToString(encoded.bytes),
					payloadChecksum = encoded.checksum,
				)
			},
		deferredSourceMask = deferredSources.toSourceSessionMask(),
		clockDomainId = clockDomainId,
		zoneId = zoneId,
		foregroundCapabilityFlags = foregroundCapabilityFlags,
		controlDependencyMask = controlDependencies.toSourceSessionMask(),
	)
}
