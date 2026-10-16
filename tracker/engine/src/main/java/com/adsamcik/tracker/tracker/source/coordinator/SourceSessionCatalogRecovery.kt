package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.baselineBalanceLevel
import com.adsamcik.tracker.shared.preferences.tracking.captureParticipates
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationDebt
import com.adsamcik.tracker.tracker.resilience.CatalogReconfigurationSourcePlan
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession.ActiveSession
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

internal suspend fun TrackerServiceSourceSession.retryDurableCatalogReconfiguration():
	SourceSessionReconfigureOutcome? =
	mutex.withLock {
		val session = active ?: return@withLock null
		val stored = readCurrentCatalogDescriptor(session) ?: return@withLock null
		val debt = stored.catalogReconfigurationDebt ?: return@withLock null
		session.catalogReconfigurationDebt = debt
		retryCurrentCatalogDebt(stored, session, debt)
	}

private suspend fun TrackerServiceSourceSession.readCurrentCatalogDescriptor(
	session: ActiveSession,
): ActiveTrackingSessionDescriptor? {
	val stored = when (val result = activeTrackingSessionStore.read()) {
		is ActiveTrackingSessionStoreResult.Failure -> {
			settingsStatusProvider.publishFailure(
				SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
			)
			return null
		}
		is ActiveTrackingSessionStoreResult.Success -> result.descriptor
	} ?: return null
	if (stored.logicalTrackingId != session.logicalTrackingId ||
		stored.serviceRunId != session.serviceRunId
	) {
		return null
	}
	return stored
}

private suspend fun TrackerServiceSourceSession.retryCurrentCatalogDebt(
	stored: ActiveTrackingSessionDescriptor,
	session: ActiveSession,
	debt: CatalogReconfigurationDebt,
): SourceSessionReconfigureOutcome? {
	if (debt.desiredPlanFingerprint ==
		session.appliedSourcePlanIdentity?.planFingerprint &&
		debt.desiredPlanGeneration == session.appliedSourcePlanIdentity?.generation
	) {
		return clearObsoleteCatalogDebt(stored, session)
	}
	val currentInputs = session.desiredInputs
	val currentPolicyRevision = currentInputs.settings.sourcePolicyRevision
	if (!debt.matchesCurrentSessionInputs(session, currentInputs, currentPolicyRevision)) {
		return clearObsoleteCatalogDebt(stored, session)
	}
	return reconstructAndApplyCatalogDebt(stored, session, currentInputs, debt)
}

private suspend fun TrackerServiceSourceSession.reconstructAndApplyCatalogDebt(
	stored: ActiveTrackingSessionDescriptor,
	session: ActiveSession,
	currentInputs: SourceSessionPlanInputs,
	debt: CatalogReconfigurationDebt,
): SourceSessionReconfigureOutcome {
	val revision = (database.sourcePlanStateDao().latestRevision()?.revision ?: 0L) + 1L
	val plan = debt.toPlan(revision, currentInputs.settings, currentInputs.environment.liveSources) ?: run {
		return rejectInvalidCatalogDebt(stored, session)
	}
	val reconstructedFingerprint = session.fingerprint(currentInputs, plan)
	if (reconstructedFingerprint != debt.desiredPlanFingerprint) {
		return rejectInvalidCatalogDebt(stored, session)
	}
	return applyRetriedCatalogRequest(session, currentInputs, debt, plan)
}

/** Clear only the exact obsolete saved debt before reporting the retry as unchanged. */
private suspend fun TrackerServiceSourceSession.clearObsoleteCatalogDebt(
	stored: ActiveTrackingSessionDescriptor,
	session: ActiveSession,
): SourceSessionReconfigureOutcome? {
	if (!replaceCatalogDebt(stored, session, null)) {
		settingsStatusProvider.publishFailure(
			SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
		)
		return null
	}
	session.catalogReconfigurationDebt = null
	return SourceSessionReconfigureOutcome.Unchanged
}

private suspend fun TrackerServiceSourceSession.rejectInvalidCatalogDebt(
	stored: ActiveTrackingSessionDescriptor,
	session: ActiveSession,
): SourceSessionReconfigureOutcome {
	if (replaceCatalogDebt(stored, session, null)) {
		session.catalogReconfigurationDebt = null
	}
	settingsStatusProvider.publishFailure(SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_INVALID)
	return SourceSessionReconfigureOutcome.Rejected(
		SessionReconfigureResult.InvalidState(SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_INVALID),
	)
}

/** Exact saved debt/current-input correspondence; reconstruction and storage remain outside. */
private fun CatalogReconfigurationDebt.matchesCurrentSessionInputs(
	session: ActiveSession,
	currentInputs: SourceSessionPlanInputs,
	currentPolicyRevision: Long?,
): Boolean = sourcePolicyRevision == currentPolicyRevision &&
	clockDomainId == currentInputs.clockDomainId &&
	zoneId == currentInputs.zoneId &&
	foregroundCapabilityFlags == session.foregroundCapabilityFlags &&
	controlDependencyMask == sourceSessionControlDependencies(session.origin).toSourceSessionMask() &&
	desiredPlanFingerprint == session.desiredSourcePlanIdentity?.planFingerprint &&
	desiredPlanGeneration == session.desiredSourcePlanIdentity?.generation

/** Sample the retry request clocks and apply it while the caller still owns the original mutex. */
private suspend fun TrackerServiceSourceSession.applyRetriedCatalogRequest(
	session: ActiveSession,
	currentInputs: SourceSessionPlanInputs,
	debt: CatalogReconfigurationDebt,
	plan: AcquisitionPlanRevision,
): SourceSessionReconfigureOutcome {
	val request = SessionReconfigureRequest(
		ownerToken = session.ownerToken,
		plan = plan,
		wallTimeMs = Time.nowMillis,
		elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
		clockDomainId = debt.clockDomainId,
		zoneId = debt.zoneId,
		foregroundCapabilityFlags = debt.foregroundCapabilityFlags,
		controlDependencies = sourceKindsFromMask(debt.controlDependencyMask),
		desiredPlanFingerprint = debt.desiredPlanFingerprint,
		desiredPlanGeneration = debt.desiredPlanGeneration,
		catalogDebtPersistedSources = sourceKindsFromMask(debt.deferredSourceMask),
		catalogDebtPersistedFingerprint = debt.desiredPlanFingerprint,
		catalogDebtPersistedGeneration = debt.desiredPlanGeneration,
	)
	val result = try {
		coordinator.reconfigure(request)
	} catch (error: CancellationException) {
		session.runtimeCleanupRequired = true
		throw error
	}
	val committedReference = when (result) {
		is SessionReconfigureResult.Applied -> result.sourceCallerAuthorityReference
		is SessionReconfigureResult.Failed -> result.sourceCallerAuthorityReference
		else -> null
	}
	if (committedReference != null &&
		!propagateSourceCallerAuthority(session, committedReference)
	) {
		session.runtimeCleanupRequired = true
		return SourceSessionReconfigureOutcome.Rejected(
			SessionReconfigureResult.InvalidState(
				SOURCE_SESSION_SOURCE_CALLER_REFERENCE_PROPAGATION_FAILED,
			),
		)
	}
	return publishRetriedCatalogOutcome(session, currentInputs, debt, request, result)
}

private suspend fun TrackerServiceSourceSession.publishRetriedCatalogOutcome(
	session: ActiveSession,
	currentInputs: SourceSessionPlanInputs,
	debt: CatalogReconfigurationDebt,
	request: SessionReconfigureRequest,
	result: SessionReconfigureResult,
): SourceSessionReconfigureOutcome {
	return when (result) {
		is SessionReconfigureResult.Applied ->
			acceptRetriedCatalogPlan(session, currentInputs, debt, result)
		is SessionReconfigureResult.Retryable -> {
			if (!persistCatalogReconfigurationDebt(session, request, result.sources)) {
				return SourceSessionReconfigureOutcome.Retryable(
					result.copy(
						failureCode = SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
					),
				)
			}
			settingsStatusProvider.publishFailure(result.failureCode)
			SourceSessionReconfigureOutcome.Retryable(result)
		}
		is SessionReconfigureResult.Deferred -> {
			settingsStatusProvider.publishFailure(result.failureCode)
			SourceSessionReconfigureOutcome.Deferred(result)
		}
		else -> {
			if (!clearCatalogReconfigurationDebt(session)) {
				return SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState(
						SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
					),
				)
			}
			settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_REJECTED")
			SourceSessionReconfigureOutcome.Rejected(result)
		}
	}
}

/** Commit applied plan identity and remaining debt before publishing the accepted retry. */
private suspend fun TrackerServiceSourceSession.acceptRetriedCatalogPlan(
	session: ActiveSession,
	currentInputs: SourceSessionPlanInputs,
	debt: CatalogReconfigurationDebt,
	result: SessionReconfigureResult.Applied,
): SourceSessionReconfigureOutcome {
	val desiredIdentity = session.desiredSourcePlanIdentity
		?.takeIf { identity ->
			identity.generation == debt.desiredPlanGeneration &&
				identity.planFingerprint == debt.desiredPlanFingerprint
		}
		?: return SourceSessionReconfigureOutcome.Rejected(
			SessionReconfigureResult.InvalidState(
				SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_INVALID,
			),
		)
	if (!session.markAppliedReconfiguration(
			inputs = currentInputs,
			desiredIdentity = desiredIdentity,
			result = result,
		)
	) {
		session.runtimeCleanupRequired = true
		return SourceSessionReconfigureOutcome.Rejected(
			SessionReconfigureResult.InvalidState(
				SOURCE_SESSION_APPLIED_SOURCE_PLAN_STATE_INVALID,
			),
		)
	}
	val debtUpdated = if (result.deferredCatalogSources.isEmpty()) {
		clearCatalogReconfigurationDebt(session)
	} else {
		updateCatalogReconfigurationDebtSources(
			session,
			result.deferredCatalogSources,
		)
	}
	if (!debtUpdated) {
		return SourceSessionReconfigureOutcome.Rejected(
			SessionReconfigureResult.InvalidState(
				SOURCE_SESSION_CATALOG_RECONFIGURATION_DEBT_STORE_UNAVAILABLE,
			),
		)
	}
	settingsStatusProvider.publishApplied(result.applied)
	return SourceSessionReconfigureOutcome.Applied(result)
}

private fun CatalogReconfigurationDebt.toPlan(
	revision: Long,
	settings: TrackingParamsState,
	liveSources: Set<SourceKind>,
): AcquisitionPlanRevision? {
	return try {
		val codec = SourcePlanCodec()
		val decoded = requestedPlans.associate { stored ->
			val sourcePlan = decodeCatalogSourceSessionPlan(stored, codec, revision) ?: return null
			sourcePlan.source to sourcePlan
		}
		val deferredSources = sourceKindsFromMask(deferredSourceMask)
		val controlDependencies = sourceKindsFromMask(controlDependencyMask)
		if (deferredSources.toSourceSessionMask() != deferredSourceMask ||
			controlDependencies.toSourceSessionMask() != controlDependencyMask ||
			deferredSources.any { source -> decoded[source]?.enabled != true }
		) {
			return null
		}
		AcquisitionPlanRevision(
			revision = revision,
			balanceProfileVersion = settings.balanceProfileVersion,
			sourcePreferences = catalogSourceSessionPreferences(settings, decoded.keys, liveSources),
			planId = "catalog-retry-$sourcePolicyRevision-$revision",
			createdAtMs = Time.nowMillis,
			plans = decoded,
			sourcePolicyRevision = sourcePolicyRevision,
		)
	} catch (_: Exception) {
		null
	}
}

private fun CatalogReconfigurationDebt.decodeCatalogSourceSessionPlan(
	stored: CatalogReconfigurationSourcePlan,
	codec: SourcePlanCodec,
	revision: Long,
): SourcePlan? {
	val payload = Base64.getDecoder().decode(stored.payloadBase64)
	val sourcePlan = codec.decode(payload)
	val reencoded = codec.encode(sourcePlan)
	if (sourcePlan.source.stableCode != stored.sourceStableCode ||
		reencoded.formatVersion != stored.payloadVersion ||
		sourcePlan.revision != requestedPlanRevision
	) {
		return null
	}
	if (reencoded.checksum != stored.payloadChecksum || !reencoded.bytes.contentEquals(payload)) {
		return null
	}
	return sourcePlan.withSourceSessionCatalogRevision(revision)
}

private fun catalogSourceSessionPreferences(
	settings: TrackingParamsState,
	sources: Set<SourceKind>,
	liveSources: Set<SourceKind>,
): Map<SourceKind, com.adsamcik.tracker.tracker.source.model.SourceBalancePreference> =
	if (settings.balanceProfileVersion == null) {
		emptyMap()
	} else {
		sources.associateWith { source ->
			val level = requireNotNull(
				settings.baselineBalanceLevel(TrackingSourceComponent.fromStableCode(source.stableCode)),
			)
			val focus = settings.captureParticipates(TrackingSourceComponent.fromStableCode(source.stableCode)) &&
				source in liveSources
			com.adsamcik.tracker.tracker.source.model.SourceBalancePreference(
				if (focus) {
					maxOf(level, SourceBalanceSettings.LIVE_LEVEL)
				} else {
					level
				},
				focus,
			)
		}
	}

private fun SourcePlan.withSourceSessionCatalogRevision(revision: Long): SourcePlan = when (this) {
	is com.adsamcik.tracker.tracker.source.model.LocationPlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.ActivityPlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.StepsPlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.PressurePlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.WifiPlan -> copy(revision = revision)
	is com.adsamcik.tracker.tracker.source.model.CellPlan -> copy(revision = revision)
}

private fun sourceKindsFromMask(mask: Long): Set<SourceKind> =
	SourceKind.entries.filterTo(linkedSetOf()) { source ->
		source.stableCode in 1..Long.SIZE_BITS &&
			mask and (1L shl (source.stableCode - 1)) != 0L
	}
