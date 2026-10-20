package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.model.AltitudeContractVersions
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalFailure
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.sourceQualityFromStableFlags
import java.time.ZoneId

/** Ordered capture-only checks; physical and all-day authentication stay shared in the adapter. */
internal class LocationCaptureQualification(
	private val database: AppDatabase,
	private val planCodec: SourcePlanCodec,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val exactMembership: (SourceEventWalEntity, SourceAuthorizationSnapshot, List<SourceDemandEntity>) -> Boolean,
) {
	/** Called only within the adapter's surrounding Room snapshot. */
	suspend fun qualify(physical: LocationPhysicalEvidence,
		registrationPlan: suspend (SourceEventWalEntity) -> LocationPlan?,
		manifestTimeline: suspend (SourceServiceRunEntity) -> LocationManifestTimeline): LocationWalAdapterResult {
		requireCaptureBinding(physical.wal)
		val plan = loadCapturePlan(physical.wal, registrationPlan)
		val broker = loadCaptureBroker(physical.wal)
		val policy = loadCapturePolicy(physical.wal)
		val session = loadCaptureSession(physical.wal, policy.policy, manifestTimeline)
		return evaluateCapture(physical, plan, broker, policy, session)
	}

	private suspend fun loadCapturePlan(wal: SourceEventWalEntity,
		registrationPlan: suspend (SourceEventWalEntity) -> LocationPlan?): LocationCapturePlan {
		val configRevision = requireNotNull(wal.configRevision)
		val physicalFingerprint = requireNotNull(wal.physicalConfigurationFingerprint)
		val policyRevision = requireNotNull(wal.sourcePolicyRevision)
		val planDao = database.sourcePlanStateDao()
		val planHeader = planDao.revision(configRevision)
		val desiredPlan = planDao.desiredPlan(configRevision, LOCATION_SOURCE)
		if (planHeader == null || desiredPlan == null) {
			rejectLocation(LocationWalAdapterRejection.MISSING_HISTORICAL_PLAN)
		}
		val requestedPlan = runCatching { planCodec.decode(desiredPlan.payload) as? LocationPlan }.getOrNull()
		val plan = registrationPlan(wal) ?: requestedPlan
		validateRequestedLocationPlan(desiredPlan, requestedPlan, configRevision, planCodec)
		val actualPlan = plan ?: rejectLocation(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		if (!isSupportedCapturePlan(actualPlan, configRevision)) {
			rejectLocation(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}

		if (actualPlan.physicalConfigurationFingerprint() != physicalFingerprint ||
			planHeader.sourcePolicyRevision != policyRevision
		) {
			rejectLocation(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}

		return LocationCapturePlan(requireNotNull(requestedPlan), actualPlan)
	}

	private suspend fun loadCaptureBroker(wal: SourceEventWalEntity): LocationCaptureBroker {
		val authorizationFingerprint = requireNotNull(wal.authorizationFingerprint)
		val authenticatedRegistration = captureRegistration(wal)
		val registrationStart = authenticatedRegistration.interval.startInclusiveNanos
		val registrationEnd = authenticatedRegistration.interval.endExclusiveNanos

		val (authorization, authorizationDemands) = loadCaptureAuthorization(wal)
		val captureMember = authorization.authorizedMembers.singleOrNull { matchesCaptureMember(it, wal) }

		if (captureMember == null || authorization.isDenied ||
			authorization.authorizationFingerprint != authorizationFingerprint ||
			!exactMembership(wal, authorization, authorizationDemands)
		) {
			rejectLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		if (authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			authorization.effectiveBootId != wal.clockDomainId
		) {
			rejectLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		return LocationCaptureBroker(registrationStart, registrationEnd, authorization, authorizationDemands,
			requireNotNull(captureMember))
	}

	private suspend fun captureRegistration(
		wal: SourceEventWalEntity,
	): ProviderRegistrationIntervalAuthentication.Authenticated {
		val physicalFingerprint = requireNotNull(wal.physicalConfigurationFingerprint)
		val registrationAuthentication = database.authenticateHistoricalProviderRegistration(
			sourceKind = LOCATION_SOURCE,
			registrationGeneration = wal.registrationGeneration,
			sourceInstanceId = wal.sourceInstanceId,
			clockDomainId = wal.clockDomainId,
			physicalConfigurationFingerprint = physicalFingerprint,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			expectedProviderResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = wal.observedElapsedNanos,
			observedEndNanos = wal.observedElapsedNanos,
			observedWallTimeMs = wal.wallTimeMs
				?: rejectLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING),
			currentClockDomainId = bootClockDomainProvider.current(),
		)
		val authenticatedRegistration = when (registrationAuthentication) {
			is ProviderRegistrationIntervalAuthentication.Authenticated ->
				registrationAuthentication
			is ProviderRegistrationIntervalAuthentication.Rejected ->
				rejectLocation(
					if (registrationAuthentication.reason ==
						ProviderRegistrationIntervalFailure.MISSING_REGISTRATION
					) {
						LocationWalAdapterRejection.MISSING_REGISTRATION
					} else {
						LocationWalAdapterRejection.REGISTRATION_MISMATCH
					},
				)
		}
		return authenticatedRegistration
	}

	private suspend fun loadCaptureAuthorization(
		wal: SourceEventWalEntity,
	): Pair<SourceAuthorizationSnapshot, List<SourceDemandEntity>> {
		val brokerDao = database.sourceBrokerDao()
		val authorizationRevision = requireNotNull(wal.authorizationRevision)
		val authorization = brokerDao.authorizationRevision(
			LOCATION_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
		).toAuthorizationSnapshotOrNull()
			?: rejectLocation(LocationWalAdapterRejection.MISSING_AUTHORIZATION)
		val authorizationDemandIds = authorization.authorizedMembers.mapNotNull { it.demandId }
		val authorizationDemands = brokerDao.demandsByIds(authorizationDemandIds)
		val recomputedAuthorization = runCatching {
			SourceBrokerAuthorization.rows(
				sourceKind = LOCATION_SOURCE,
				registrationGeneration = wal.registrationGeneration,
				authorizationRevision = authorizationRevision,
				demands = authorizationDemands,
				effectiveBootId = authorization.effectiveBootId,
				effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
			)
		}.getOrNull()
		if (authorizationDemandIds.distinct().size != authorization.authorizedMembers.size ||
			authorizationDemands.size != authorization.authorizedMembers.size ||
			recomputedAuthorization?.sortedBy { it.memberId } !=
				authorization.members.sortedBy { it.memberId }
		) {
			rejectLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		return authorization to authorizationDemands
	}

	private suspend fun loadCapturePolicy(wal: SourceEventWalEntity): LocationCapturePolicy {
		val policyRevision = requireNotNull(wal.sourcePolicyRevision)
		val consentEpoch = requireNotNull(wal.captureConsentEpoch)
		val policyDao = database.sourcePolicyDao()
		val policyAuthority = policyDao.authority()
		val policy = policyDao.policyAtRevision(policyRevision, LOCATION_SOURCE)
		if (policy == null) {
			rejectLocation(LocationWalAdapterRejection.MISSING_POLICY)
		}
		val maximumAccuracy = policy.locationRequiredAccuracyMeters
		validateCapturePolicyHeader(policyAuthority, policy, policyRevision)
		validateCapturePolicyShape(wal, policy)
		val consent = policyDao.consentEpoch(
			LOCATION_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch,
		) ?: rejectLocation(LocationWalAdapterRejection.MISSING_CONSENT)
		val latestConsent = policyDao.latestConsentEpoch(LOCATION_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE)
		validateCaptureConsent(wal, consent, latestConsent)

		return LocationCapturePolicy(policy, consent, requireNotNull(maximumAccuracy))
	}

	private suspend fun loadCaptureSession(wal: SourceEventWalEntity, policy: SourcePolicyEntity,
		manifestTimeline: suspend (SourceServiceRunEntity) -> LocationManifestTimeline): LocationCaptureSession {
		val serviceRunId = requireNotNull(wal.serviceRunId)
		val logicalTrackingId = requireNotNull(wal.logicalTrackingId)
		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId)
		val session = sessionDao.session(logicalTrackingId)
		if (run == null || session == null) {
			rejectLocation(LocationWalAdapterRejection.MISSING_SESSION)
		}
		validateCaptureRun(wal, run, session)
		val selected = selectCaptureManifest(wal, policy, manifestTimeline(run))
		val segmentId = run.sessionSegmentId
		val segment = segmentId?.let { database.sessionSegmentDao().getById(it) }
		if (segment == null) {
			rejectLocation(LocationWalAdapterRejection.MISSING_SEGMENT)
		}
		if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId) {
			rejectLocation(LocationWalAdapterRejection.SEGMENT_MISMATCH)
		}

		return LocationCaptureSession(run, session, segment, selected)
	}

	private suspend fun evaluateCapture(physical: LocationPhysicalEvidence, plans: LocationCapturePlan,
		broker: LocationCaptureBroker, policies: LocationCapturePolicy,
		sessions: LocationCaptureSession): LocationWalAdapterResult {
		val read = LocationCaptureRead(physical, plans, broker, policies, sessions)
		val evidenceState = validateCaptureDeletion(read)
		val temporal = readCaptureTimeAuthority(read)
		val captureAuthority = buildCaptureAuthority(read, temporal)
		val evidence = buildObservationEvidence(read, captureAuthority, evidenceState)
		val plan = read.plans.plan
		val policy = read.policies.policy
		val maximumAccuracy = read.policies.maximumAccuracy
		return LocationWalAdapterResult.Evaluated(
			qualification = LocationQualifiedObservationQualifier.qualify(
				input = LocationObservationInput(
					origin = LocationObservationOrigin.PROVIDER_CALLBACK,
					outcome = LocationProviderOutcome.FIX,
					attemptedAuthority = captureAuthority,
					durableEvidence = evidence,
				),
				expectedAuthority = captureAuthority,
				currentDeletionAuthority = LocationDeletionAuthority(
					currentCollectedDataEpoch = evidenceState.collectedDataEpoch,
					retainedFromWallTimeMs = evidenceState.retainedFromMs,
				),
			),
			acquisitionMetadata = LocationWalAcquisitionMetadata(
				acquisitionMode = plan.canonicalAcquisitionMode(),
				requestPriority = plan.canonicalRequestPriority(),
				requiredAccuracyMeters = maximumAccuracy.toFloat(),
				policyTier = policy.qosCode.toLocationPolicyTier(),
				policyName = policy.qosCode.toLocationPolicyName(),
				curationVersion = PROTECTED_LOCATION_CANONICAL_CURATION_VERSION,
				altitudeModelVersion = AltitudeContractVersions.MODEL_VERSION,
				altitudeEstimatorVersion = AltitudeContractVersions.ESTIMATOR_VERSION,
				altitudeCalibrationVersion = AltitudeContractVersions.CALIBRATION_VERSION,
			),
		)
	}

	private suspend fun validateCaptureDeletion(read: LocationCaptureRead): SourceEvidenceState {
		val wal = read.physical.wal
		val authorization = read.broker.authorization
		val run = read.sessions.run
		val session = read.sessions.session
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: rejectLocation(LocationWalAdapterRejection.DELETED_EVIDENCE)
		if (evidenceState.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) {
			rejectLocation(LocationWalAdapterRejection.DELETED_EVIDENCE)
		}
		val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
		if (wal.observedElapsedNanos >= sessionEnd) {
			rejectLocation(if (hasAuthenticatedSessionCutoff(wal, authorization, session, run)) {
				LocationWalAdapterRejection.AFTER_SESSION_CUTOFF
			} else {
				LocationWalAdapterRejection.SESSION_MISMATCH
			})
		}
		return evidenceState
	}

	private suspend fun readCaptureTimeAuthority(read: LocationCaptureRead): LocationCaptureTemporalAuthority {
		val wal = read.physical.wal
		val registrationEnd = read.broker.registrationEnd
		val authorization = read.broker.authorization
		val run = read.sessions.run
		val manifests = read.sessions.selected.timeline.manifests
		val allManifestSources = read.sessions.selected.timeline.sources
		val manifestIndex = read.sessions.selected.index
		val locationManifestSource = read.sessions.selected.source
		val brokerDao = database.sourceBrokerDao()
		val sessionEnd = read.sessions.session.cutoffElapsedNanos ?: Long.MAX_VALUE
		val observedAuthorization = brokerDao.authorizationAt(
			LOCATION_SOURCE, wal.registrationGeneration, wal.clockDomainId, wal.observedElapsedNanos,
		).toAuthorizationSnapshotOrNull()
		if (hasExactObservedAuthorizationDenial(wal, authorization, observedAuthorization)) {
			rejectLocation(LocationWalAdapterRejection.AFTER_AUTHORIZATION_DENIAL)
		}
		val authorizationTimeline = database.compatibleLocationCaptureAuthorizations(authorization, observedAuthorization)
			?: rejectLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		if (authorizationTimeline.any { !SessionManifestIntegrity.hasRetainedLeaseGeneration(run,
			it.locationCaptureMember()?.lifecycleLeaseGeneration) }) {
			rejectLocation(LocationWalAdapterRejection.SESSION_MISMATCH)
		}
		val observedCaptureAuthorization = authorizationTimeline.last()
		val nextAuthorization = brokerDao.nextAuthorizationRevision(
			LOCATION_SOURCE, wal.registrationGeneration, observedCaptureAuthorization.authorizationRevision,
		).toAuthorizationSnapshotOrNull()
		val authorizationEnd = authorizationEndExclusive(registrationEnd, observedCaptureAuthorization, nextAuthorization)
			?: rejectLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		val observedManifestIndex = compatibleLocationCaptureManifestIndex(
			manifests, allManifestSources, manifestIndex, authorizationTimeline, locationManifestSource,
		) ?: rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		val manifestEnd = manifests.getOrNull(observedManifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, registrationEnd, authorizationEnd)
		val temporal = buildCaptureTimeAuthority(read, authorizationEnd, manifestEnd)
		return temporal
	}

	/** The verified original grant ended before this fix; no recording purpose can use it. */

	/** A saved End rejects this run's late callbacks even if a queued refresh publishes a newer grant. */
	private suspend fun hasAuthenticatedSessionCutoff(
		wal: SourceEventWalEntity,
		authorization: SourceAuthorizationSnapshot,
		session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
	): Boolean {
		val cutoff = database.savedSessionCutoff(session)
		val elapsed = cutoff.elapsedNanos ?: return false
		val stop = database.sourceSessionDao().firstStopIntent(session.logicalTrackingId) ?: return false
		val stopManifest = database.sourceSessionDao().manifest(session.logicalTrackingId, stop.manifestRevision)
		val sameRun = cutoff.bootId == wal.clockDomainId && run.bootId == cutoff.bootId &&
			stopManifest?.serviceRunId == run.serviceRunId
		val originalGrantPredatesObservation =
			authorization.effectiveElapsedRealtimeNanos <= wal.observedElapsedNanos
		return sameRun && originalGrantPredatesObservation && run.startedElapsedNanos <= elapsed &&
			wal.observedElapsedNanos >= elapsed
	}

	private fun validateCapturePolicyHeader(authority: SourcePolicyAuthorityEntity?,
		policy: SourcePolicyEntity, revision: Long) {
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			authority.currentPolicyRevision < revision || !policy.enabled) {
			rejectLocation(LocationWalAdapterRejection.POLICY_MISMATCH)
		}
	}

	private fun validateCapturePolicyShape(wal: SourceEventWalEntity, policy: SourcePolicyEntity) {
		val maximumAccuracy = policy.locationRequiredAccuracyMeters
		if (!policy.capturePersistenceEligible || policy.captureConsentEpoch != wal.captureConsentEpoch ||
			maximumAccuracy == null || maximumAccuracy <= 0) {
			rejectLocation(LocationWalAdapterRejection.POLICY_MISMATCH)
		}
		if (policy.qosCode !in 1..3 || (policy.effectiveBootId == wal.clockDomainId &&
			policy.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos)) {
			rejectLocation(LocationWalAdapterRejection.POLICY_MISMATCH)
		}
	}

	private fun validateCaptureConsent(wal: SourceEventWalEntity, consent: SourceConsentEpochEntity,
		latest: SourceConsentEpochEntity?) {
		if (!consent.eligible || !consent.persistenceEligible ||
			consent.policyRevision > requireNotNull(wal.sourcePolicyRevision)) {
			rejectLocation(LocationWalAdapterRejection.CONSENT_MISMATCH)
		}
		if ((consent.effectiveBootId == wal.clockDomainId &&
			consent.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos) ||
			latest == null || latest.epoch < requireNotNull(wal.captureConsentEpoch)) {
			rejectLocation(LocationWalAdapterRejection.CONSENT_MISMATCH)
		}
	}

	private fun validateCaptureRun(wal: SourceEventWalEntity, run: SourceServiceRunEntity,
		session: LogicalTrackingSessionEntity) {
		if (run.logicalTrackingId != wal.logicalTrackingId || run.bootId != wal.clockDomainId ||
			!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, wal.lifecycleLeaseGeneration)) {
			rejectLocation(LocationWalAdapterRejection.SESSION_MISMATCH)
		}
		if (session.logicalTrackingId != wal.logicalTrackingId ||
			session.lifecycleLeaseGeneration < requireNotNull(wal.lifecycleLeaseGeneration) ||
			run.startedElapsedNanos > wal.observedElapsedNanos) {
			rejectLocation(LocationWalAdapterRejection.SESSION_MISMATCH)
		}
	}
}

private data class LocationCapturePlan(val requestedPlan: LocationPlan, val plan: LocationPlan)
private data class LocationCaptureBroker(
	val registrationStart: Long,
	val registrationEnd: Long,
	val authorization: SourceAuthorizationSnapshot,
	val demands: List<SourceDemandEntity>,
	val captureMember: SourceAuthorizationEntity,
)
private data class LocationCapturePolicy(val policy: SourcePolicyEntity, val consent: SourceConsentEpochEntity,
	val maximumAccuracy: Int)
private data class LocationCaptureSession(
	val run: SourceServiceRunEntity,
	val session: LogicalTrackingSessionEntity,
	val segment: SessionSegment,
	val selected: LocationCaptureManifest,
)
private data class LocationCaptureManifest(
	val timeline: LocationManifestTimeline,
	val index: Int,
	val source: SessionManifestSourceEntity,
	val writerBindingGeneration: Long,
	val capturedSources: Set<SourceKind>,
	val controlSources: Set<SourceKind>,
)
private data class LocationCaptureRead(
	val physical: LocationPhysicalEvidence,
	val plans: LocationCapturePlan,
	val broker: LocationCaptureBroker,
	val policies: LocationCapturePolicy,
	val sessions: LocationCaptureSession,
)
/** Checked bounded manifest history reused only by one Room qualification snapshot. */
internal data class LocationManifestTimeline(val manifests: List<SessionManifestVersionEntity>,
	val sources: List<SessionManifestSourceEntity>)

private fun requireCaptureBinding(wal: SourceEventWalEntity) {
	if (!hasCaptureRevisionBinding(wal) || !hasCaptureSessionBinding(wal) || !hasCaptureAttribution(wal)
	) {
		rejectLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	}
}

private fun hasCaptureRevisionBinding(wal: SourceEventWalEntity): Boolean =
	wal.configRevision != null && wal.sourcePolicyRevision != null && wal.captureConsentEpoch != null &&
		wal.physicalConfigurationFingerprint != null && wal.authorizationRevision != null &&
		wal.authorizationFingerprint != null

private fun hasCaptureSessionBinding(wal: SourceEventWalEntity): Boolean =
	wal.sessionManifestRevision != null && wal.lifecycleLeaseGeneration != null &&
		wal.logicalTrackingId != null && wal.serviceRunId != null

private fun buildCaptureAuthority(read: LocationCaptureRead,
	temporal: LocationCaptureTemporalAuthority): LocationCaptureAuthority {
	val captureAuthority = runCatching {
		LocationCaptureAuthority(
			logicalTrackingId = LogicalTrackingId(requireNotNull(read.physical.wal.logicalTrackingId)),
			serviceRunId = ServiceRunId(requireNotNull(read.physical.wal.serviceRunId)),
			sessionSegmentId = read.sessions.segment.id,
			capturedSources = read.sessions.selected.capturedSources,
			controlSources = read.sessions.selected.controlSources,
			sourceInstanceId = SourceInstanceId(read.physical.wal.sourceInstanceId),
			registrationGeneration = read.physical.wal.registrationGeneration,
			configurationRevision = requireNotNull(read.physical.wal.configRevision),
			physicalConfigurationFingerprint = requireNotNull(read.physical.wal.physicalConfigurationFingerprint),
			authorizationRevision = requireNotNull(read.physical.wal.authorizationRevision),
			authorizationFingerprint = requireNotNull(read.physical.wal.authorizationFingerprint),
			purposeEligibilityMask = read.physical.wal.authorizationPurposeEligibilityMask,
			sourcePolicyRevision = requireNotNull(read.physical.wal.sourcePolicyRevision),
			captureConsentEpoch = requireNotNull(read.physical.wal.captureConsentEpoch),
			sessionManifestRevision = requireNotNull(read.physical.wal.sessionManifestRevision),
			writerBindingGeneration = read.sessions.selected.writerBindingGeneration,
			lifecycleLeaseGeneration = requireNotNull(read.physical.wal.lifecycleLeaseGeneration),
			capturedCollectedDataEpoch = read.physical.wal.capturedCollectedDataEpoch,
			clockDomainId = read.physical.wal.clockDomainId,
			zoneId = read.sessions.selected.timeline.manifests[read.sessions.selected.index].zoneId,
			permissionPrecision = if (read.plans.plan.preciseLocationAvailable) {
				LocationPermissionPrecision.PRECISE
			} else {
				LocationPermissionPrecision.APPROXIMATE
			},
			temporalAuthority = temporal,
			acquisitionConfiguration = LocationHistoricalAcquisitionConfiguration(
				maximumObservationAgeNanos = minOf(
					read.plans.requestedPlan.maximumLocationEvidenceAgeNanos(),
					runCatching { Math.multiplyExact(read.broker.demands.single {
						it.demandId == read.broker.captureMember.demandId
					}.maximumAgeMs, LOCATION_WAL_NANOS_PER_MILLISECOND) }.getOrDefault(Long.MAX_VALUE),
				),
				maximumHorizontalAccuracyMeters = read.policies.maximumAccuracy.toFloat(),
			),
		)
	}.getOrNull() ?: rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
	return captureAuthority
}

private fun validateObservationRetention(wal: SourceEventWalEntity,
	evidenceState: SourceEvidenceState) {
	val wallTime = wal.wallTimeMs
	val wallUncertainty = wal.wallTimeUncertaintyMs
	validateLocationWallShape(wal)
	validateLocationStoredQuality(wal)
	val earliestPossibleWallTimeMs = CanonicalRetentionBounds.location(
		requireNotNull(wallTime),
		requireNotNull(wallUncertainty),
	) ?: rejectLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	if (evidenceState.retainedFromMs?.let { retainedFrom ->
		earliestPossibleWallTimeMs < retainedFrom
	} == true) {
		rejectLocation(LocationWalAdapterRejection.RETAINED_EVIDENCE)
	}
}

private fun buildObservationEvidence(read: LocationCaptureRead, captureAuthority: LocationCaptureAuthority,
	evidenceState: SourceEvidenceState): LocationDurableObservationEvidence {
	val physical = read.physical
	val wal = read.physical.wal
	val deliveryIdentity = read.physical.deliveryIdentity
	val deliveryUnitIndex = requireNotNull(read.physical.wal.deliveryUnitIndex)
	val deliveryUnitCount = requireNotNull(read.physical.wal.deliveryUnitCount)
	validateObservationRetention(wal, evidenceState)
	val wallTime = requireNotNull(wal.wallTimeMs)
	val receivedWallTime = wal.receivedWallTimeMs
	val wallUncertainty = requireNotNull(wal.wallTimeUncertaintyMs)
	val selectedPayload = physical.payload
	if (wal.payloadVersion < LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION ||
		selectedPayload.isMock == null
	) {
		// Retained Location WAL payload v1 did not carry the platform mock-provider bit. Supplying
		// `false` would manufacture immutable evidence, so legacy bytes remain typed unverifiable.
		rejectLocation(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE)
	}
	val evidence = LocationDurableObservationEvidence(
		sourceEventId = SourceEventId(wal.eventId),
		sourceAdmissionOrdinal = wal.admissionOrdinal,
		sourceSequence = wal.sourceSequence,
		walIntegrityIdentity = wal.integrityIdentity,
		sourceDeliveryIdentity = deliveryIdentity,
		deliveryUnitIndex = deliveryUnitIndex,
		deliveryUnitCount = deliveryUnitCount,
		capturedAuthority = captureAuthority,
		clockAuthority = LocationDurableClockAuthority(
			clockDomainId = wal.clockDomainId,
			observedElapsedRealtimeNanos = wal.observedElapsedNanos,
			receivedElapsedRealtimeNanos = wal.receivedElapsedNanos,
			observedWallTimeMs = wallTime,
			receivedWallTimeMs = receivedWallTime,
			wallTimeUncertaintyMs = wallUncertainty,
		),
		payloadVersion = wal.payloadVersion,
		payload = selectedPayload,
		quality = sourceQualityFromStableFlags(wal.qualityFlags, wal.qualityConfidence),
		isMock = requireNotNull(selectedPayload.isMock),
	)
	return evidence
}

private fun hasExactObservedAuthorizationDenial(
	wal: SourceEventWalEntity,
	original: SourceAuthorizationSnapshot,
	observed: SourceAuthorizationSnapshot?,
): Boolean {
	if (observed?.isDenied != true || observed.authorizationRevision <= original.authorizationRevision) {
		return false
	}
	if (observed.effectiveBootId != wal.clockDomainId ||
		observed.effectiveElapsedRealtimeNanos < original.effectiveElapsedRealtimeNanos ||
		observed.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos) {
		return false
	}
	return observed.members == SourceBrokerAuthorization.rows(
		LOCATION_SOURCE, wal.registrationGeneration, observed.authorizationRevision, emptyList(),
		observed.effectiveBootId, observed.effectiveElapsedRealtimeNanos,
		observed.members.first().effectiveWallTimeMs,
	)
}

private fun authorizationEndExclusive(
	registrationEnd: Long,
	authorization: SourceAuthorizationSnapshot,
	next: SourceAuthorizationSnapshot?,
): Long? {
	if (next == null) {
		return registrationEnd
	}
	if (next.authorizationRevision <= authorization.authorizationRevision) {
		return null
	}
	if (next.effectiveBootId != authorization.effectiveBootId ||
		next.effectiveElapsedRealtimeNanos < authorization.effectiveElapsedRealtimeNanos
	) {
		return null
	}
	return minOf(registrationEnd, next.effectiveElapsedRealtimeNanos)
}
private fun selectCaptureManifest(wal: SourceEventWalEntity, policy: SourcePolicyEntity,
	timeline: LocationManifestTimeline): LocationCaptureManifest {
	val logicalTrackingId = requireNotNull(wal.logicalTrackingId)
	val manifestRevision = requireNotNull(wal.sessionManifestRevision)
	val (manifests, allManifestSources) = timeline
	val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
	if (manifestIndex < 0) {
		rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
	}
	val manifest = manifests[manifestIndex]
	val manifestSources = allManifestSources.filter { source ->
		source.logicalTrackingId == logicalTrackingId && source.manifestRevision == manifestRevision
	}
	if (manifestSources.any { source ->
		SourceKind.entries.none { it.stableCode == source.sourceKind } ||
			source.purpose !in SessionManifestPurposeCode.ALL
	}) {
		rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
	}
	val capturedSources = manifestSourceKinds(manifestSources, SessionManifestPurposeCode.SESSION_CAPTURE)
	val controlSources = manifestSourceKinds(manifestSources, SessionManifestPurposeCode.CONTROL)
	val locationManifestSource = manifestSources.singleOrNull { source ->
		source.sourceKind == LOCATION_SOURCE &&
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible
	}
	validateCaptureManifest(wal, policy, manifest, locationManifestSource, capturedSources)
	val writerBindingGeneration = captureWriterBinding(manifest, requireNotNull(locationManifestSource))
	return LocationCaptureManifest(timeline, manifestIndex, requireNotNull(locationManifestSource),
		writerBindingGeneration, capturedSources, controlSources)
}

private fun manifestSourceKinds(sources: List<SessionManifestSourceEntity>, purpose: String): Set<SourceKind> = sources
	.filter { it.purpose == purpose && (purpose != SessionManifestPurposeCode.SESSION_CAPTURE || it.persistenceEligible) }
	.mapNotNull { source -> SourceKind.entries.singleOrNull { it.stableCode == source.sourceKind } }.toSet()

private fun validateCaptureManifest(wal: SourceEventWalEntity, policy: SourcePolicyEntity,
	manifest: SessionManifestVersionEntity, locationManifestSource: SessionManifestSourceEntity?,
	capturedSources: Set<SourceKind>) {
	val consentEpoch = requireNotNull(wal.captureConsentEpoch)
	if (!hasCaptureManifestHeader(wal, manifest)) {
		rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
	}
	if (locationManifestSource == null || locationManifestSource.consentEpoch != consentEpoch ||
		locationManifestSource.qosCode != policy.qosCode || SourceKind.LOCATION !in capturedSources
	) {
		rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
	}
}

private const val LOCATION_SOURCE = 1
private const val PLAN_PAYLOAD_VERSION = 1

private fun validateRequestedLocationPlan(desired: SourceDesiredPlanEntity, plan: LocationPlan?, revision: Long,
	codec: SourcePlanCodec) {
	val expectedVersion = if (plan?.adaptiveProfileVersion != null) {
		SourcePlanCodec.ADAPTIVE_LOCATION_FORMAT_VERSION
	} else {
		PLAN_PAYLOAD_VERSION
	}
	if (desired.payloadVersion != expectedVersion || desired.revision != revision ||
		desired.sourceKind != LOCATION_SOURCE) {
		rejectLocation(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
	}
	val encoded = plan?.let { runCatching { codec.encode(it) }.getOrNull() }
	if (encoded == null || !encoded.bytes.contentEquals(desired.payload) || encoded.checksum != desired.payloadChecksum) {
		rejectLocation(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
	}
}

private fun isSupportedCapturePlan(plan: LocationPlan, revision: Long): Boolean =
	plan.enabled && plan.hasSupportedHistoricalShape() && plan.revision == revision

private fun matchesCaptureMember(member: SourceAuthorizationEntity, wal: SourceEventWalEntity): Boolean =
	member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
		member.logicalTrackingId == wal.logicalTrackingId && member.serviceRunId == wal.serviceRunId &&
		member.manifestRevision == wal.sessionManifestRevision &&
		member.lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration &&
		member.sourcePolicyRevision == wal.sourcePolicyRevision && member.consentEpoch == wal.captureConsentEpoch

private fun buildCaptureTimeAuthority(read: LocationCaptureRead, authorizationEnd: Long,
	manifestEnd: Long): LocationCaptureTemporalAuthority {
	val wal = read.physical.wal
	val registrationStart = read.broker.registrationStart
	val registrationEnd = read.broker.registrationEnd
	val authorization = read.broker.authorization
	val policy = read.policies.policy
	val consent = read.policies.consent
	val run = read.sessions.run
	val manifest = read.sessions.selected.timeline.manifests[read.sessions.selected.index]
	val sessionEnd = read.sessions.session.cutoffElapsedNanos ?: Long.MAX_VALUE
	val temporal = runCatching {
		LocationCaptureTemporalAuthority(
			providerRegistration = LocationProviderTimeInterval(registrationStart, registrationEnd),
			authorization = LocationProviderTimeInterval(authorization.effectiveElapsedRealtimeNanos, authorizationEnd),
			sourcePolicy = LocationProviderTimeInterval(if (policy.effectiveBootId == wal.clockDomainId) {
				policy.effectiveElapsedRealtimeNanos
			} else {
				0L
			}, authorizationEnd),
			captureConsent = LocationProviderTimeInterval(if (consent.effectiveBootId == wal.clockDomainId) {
				consent.effectiveElapsedRealtimeNanos
			} else {
				0L
			}, authorizationEnd),
			sessionManifest = LocationProviderTimeInterval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
			lifecycleLease = LocationProviderTimeInterval(run.startedElapsedNanos, minOf(sessionEnd, registrationEnd)),
		)
	}.getOrNull() ?: rejectLocation(
		LocationWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
	)
	return temporal
}

private fun validateLocationWallShape(wal: SourceEventWalEntity) {
	val uncertainty = wal.wallTimeUncertaintyMs
	if (wal.wallTimeMs == null ||
		!hasLocationReceiveWallTime(wal) ||
		uncertainty == null || uncertainty < 0L) {
		rejectLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	}
}

private fun validateLocationStoredQuality(wal: SourceEventWalEntity) {
	val knownMask = SourceQualityFlag.entries.fold(0L) { mask, flag -> mask or flag.bit }
	if (wal.qualityFlags and knownMask != wal.qualityFlags ||
		wal.qualityConfidence?.let { !it.isFinite() || it !in 0f..1f } == true) {
		rejectLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	}
}

private fun captureWriterBinding(manifest: SessionManifestVersionEntity,
	locationSource: SessionManifestSourceEntity): Long {
	val writerBindingGeneration = locationSource.locationCaptureBindingGenerationOrNull()
		?: rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
	val sessionModeMask = when (manifest.sessionMode) {
		"MANUAL" -> 1L
		"AUTOMATIC" -> 2L
		else -> rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
	}
	if (requireNotNull(SourceWriterGenerationContract.captureModeMaskForKnownBinding(
			writerBindingGeneration)) and sessionModeMask == 0L
	) {
		rejectLocation(LocationWalAdapterRejection.MANIFEST_MISMATCH)
	}
	if (runCatching { ZoneId.of(manifest.zoneId) }.isFailure) {
		rejectLocation(LocationWalAdapterRejection.INVALID_STORED_ZONE)
	}
	return writerBindingGeneration
}

private fun hasCaptureManifestHeader(wal: SourceEventWalEntity, manifest: SessionManifestVersionEntity): Boolean =
	manifest.logicalTrackingId == wal.logicalTrackingId && manifest.serviceRunId == wal.serviceRunId &&
		manifest.sourcePolicyRevision == wal.sourcePolicyRevision && manifest.acquisitionPlanRevision == wal.configRevision &&
		manifest.effectiveBootId == wal.clockDomainId && manifest.effectiveElapsedRealtimeNanos <= wal.observedElapsedNanos

private fun hasCaptureAttribution(wal: SourceEventWalEntity): Boolean =
	wal.planAttribution == PlanAttribution.CAPTURED_REGISTRATION.ordinal &&
		wal.observedIntervalStartNanos == wal.observedElapsedNanos && wal.activityAutomationEpoch == null

private fun hasLocationReceiveWallTime(wal: SourceEventWalEntity): Boolean {
	if (wal.payloadVersion < LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION) {
		return true
	}
	val received = wal.receivedWallTimeMs
	return received != null && received >= 0L
}
