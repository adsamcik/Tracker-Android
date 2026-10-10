package com.adsamcik.tracker.tracker.source.location

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.model.AltitudeContractVersions
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalFailure
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.sourceQualityFromStableFlags
import com.adsamcik.tracker.stats.api.PolicyTier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.ZoneId
import javax.inject.Inject

internal enum class LocationWalAdapterRejection {
	MISSING_EVENT,
	WRONG_SOURCE,
	INCOMPLETE_CAPTURE_BINDING,
	WAL_INTEGRITY_MISMATCH,
	SOURCE_SEQUENCE_MISMATCH,
	DELIVERY_IDENTITY_MISMATCH,
	DELIVERY_TOO_LARGE,
	MALFORMED_DELIVERY,
	UNSUPPORTED_PAYLOAD,
	MISSING_HISTORICAL_PLAN,
	HISTORICAL_PLAN_MISMATCH,
	MISSING_REGISTRATION,
	REGISTRATION_MISMATCH,
	MISSING_AUTHORIZATION,
	AUTHORIZATION_MISMATCH,
	MISSING_POLICY,
	POLICY_MISMATCH,
	MISSING_CONSENT,
	CONSENT_MISMATCH,
	MISSING_SESSION,
	SESSION_MISMATCH,
	MANIFEST_TIMELINE_UNVERIFIABLE,
	MANIFEST_MISMATCH,
	MISSING_SEGMENT,
	SEGMENT_MISMATCH,
	INVALID_STORED_ZONE,
	DELETED_EVIDENCE,
	RETAINED_EVIDENCE,
	TEMPORAL_AUTHORITY_UNVERIFIABLE,
	MOCK_PROVENANCE_UNVERIFIABLE,
}

internal sealed interface LocationWalAdapterResult {
	data class Evaluated(
		val qualification: LocationObservationQualification,
		val acquisitionMetadata: LocationWalAcquisitionMetadata =
			LocationWalAcquisitionMetadata.UNKNOWN,
	) : LocationWalAdapterResult

	data class Rejected(val reason: LocationWalAdapterRejection) : LocationWalAdapterResult
}

internal data class LocationWalAcquisitionMetadata(
	val acquisitionMode: LocationAcquisitionMode,
	val requestPriority: LocationRequestPriority,
	val requiredAccuracyMeters: Float,
	val policyTier: PolicyTier,
	val policyName: String,
	val curationVersion: Int,
	val altitudeModelVersion: Int,
	val altitudeEstimatorVersion: Int,
	val altitudeCalibrationVersion: Int,
) {
	val isQualified: Boolean
		get() = requestPriority != LocationRequestPriority.UNKNOWN &&
			requiredAccuracyMeters.isFinite() &&
			requiredAccuracyMeters >= 0f &&
			policyTier != PolicyTier.OFF &&
			policyName.isNotBlank() &&
			policyName != "UNKNOWN" &&
			curationVersion > 0 &&
			altitudeModelVersion >= 0 &&
			altitudeEstimatorVersion >= 0 &&
			altitudeCalibrationVersion >= 0

	companion object {
		val UNKNOWN = LocationWalAcquisitionMetadata(
			LocationAcquisitionMode.UNKNOWN,
			LocationRequestPriority.UNKNOWN,
			Float.NaN,
			PolicyTier.OFF,
			"UNKNOWN",
			0,
			0,
			0,
			0,
		)
	}
}

internal const val PROTECTED_LOCATION_CANONICAL_CURATION_VERSION = 1

internal fun interface ProtectedLocationWalQualifier {
	suspend fun qualify(eventId: SourceEventId): LocationWalAdapterResult
	suspend fun qualifyAmbient(eventId: SourceEventId): LocationAmbientQualification =
		LocationAmbientQualification.NotEligible
}

/**
 * Dormant read-only bridge from the canonical source WAL to the pure Location qualifier.
 *
 * The adapter accepts only an event identity. Every provider value and every authority field is
 * recovered and authenticated in one Room snapshot; no caller can supply a fix or a mutable
 * latest-plan pointer. Retained v1 rows terminate in a typed mock-provenance rejection because
 * their payload did not store that immutable provider flag. Canonical v2 rows can return a command,
 * but this adapter never persists it, so the established Location writer remains the sole writer.
 */
internal class LocationWalQualificationAdapter @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
	private val bootClockDomainProvider: BootClockDomainProvider,
) : ProtectedLocationWalQualifier {
	/** The same physical WAL authentication, with independent sessionless product authority. */
	override suspend fun qualifyAmbient(eventId: SourceEventId): LocationAmbientQualification = database.withTransaction {
		fun reject(reason: LocationWalAdapterRejection) = LocationAmbientQualification.Rejected(reason)
		val physical = try { authenticatePhysical(eventId) } catch (failure: LocationPhysicalEvidenceRejected) {
			return@withTransaction reject(failure.reason)
		}
		val wal = physical.wal
		if (wal.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_AMBIENT_PRODUCT == 0L) {
			return@withTransaction LocationAmbientQualification.NotEligible
		}
		val ordinal = wal.admissionOrdinal
		val lifecycle = database.sourceEvidenceStateDao().get()
			?: return@withTransaction reject(LocationWalAdapterRejection.DELETED_EVIDENCE)
		if (lifecycle.collectedDataEpoch != wal.capturedCollectedDataEpoch || ordinal <= lifecycle.deletedSourceEventHighWaterOrdinal) {
			return@withTransaction reject(LocationWalAdapterRejection.DELETED_EVIDENCE)
		}
		if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
			return@withTransaction reject(LocationWalAdapterRejection.RETAINED_EVIDENCE)
		}
		val knownQualityMask = SourceQualityFlag.entries.fold(0L) { mask, flag -> mask or flag.bit }
		if (wal.qualityFlags and knownQualityMask != wal.qualityFlags ||
			wal.qualityConfidence?.let { !it.isFinite() || it !in 0f..1f } == true) {
			return@withTransaction reject(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		}
		val wall = wal.wallTimeMs ?: return@withTransaction reject(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		val uncertainty = wal.wallTimeUncertaintyMs ?: return@withTransaction reject(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		val earliest = runCatching { Math.subtractExact(wall, uncertainty) }.getOrNull()
		if (earliest == null || earliest < 0 || uncertainty < 0 ||
			wal.receivedWallTimeMs?.let { it < 0L } != false ||
			runCatching { Math.addExact(wall, uncertainty) }.isFailure ||
			wal.observedElapsedNanos <= 0 || wal.receivedElapsedNanos < wal.observedElapsedNanos ||
			lifecycle.retainedFromMs?.let { earliest < it } == true
		) return@withTransaction reject(LocationWalAdapterRejection.RETAINED_EVIDENCE)
		val broker = database.sourceBrokerDao()
		val authorizationRevision = wal.authorizationRevision
			?: return@withTransaction reject(LocationWalAdapterRejection.MISSING_AUTHORIZATION)
		val rows = broker.authorizationRevisionBounded(LOCATION_SOURCE, wal.registrationGeneration, authorizationRevision, 17)
		if (rows.size > 16) return@withTransaction reject(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		val authorization = rows.toAuthorizationSnapshotOrNull()
			?: return@withTransaction reject(LocationWalAdapterRejection.MISSING_AUTHORIZATION)
		val observed = broker.authorizationAtBounded(LOCATION_SOURCE, wal.registrationGeneration, wal.clockDomainId, wal.observedElapsedNanos, 17)
			.toAuthorizationSnapshotOrNull()
		val ids = authorization.authorizedMembers.mapNotNull { it.demandId }
		val demands = broker.demandsByIds(ids)
		val recomputed = runCatching {
			SourceBrokerAuthorization.rows(LOCATION_SOURCE, wal.registrationGeneration, authorizationRevision,
				demands, authorization.effectiveBootId, authorization.effectiveElapsedRealtimeNanos,
				authorization.members.first().effectiveWallTimeMs)
		}.getOrNull()
		if (authorization.isDenied || observed != authorization || ids.distinct().size != authorization.authorizedMembers.size ||
			demands.size != ids.size || recomputed?.sortedBy { it.memberId } != rows.sortedBy { it.memberId } ||
			authorization.authorizationFingerprint != wal.authorizationFingerprint ||
			!hasExactQualifiedMembership(wal, authorization, demands) || authorization.effectiveBootId != wal.clockDomainId
		) return@withTransaction reject(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		val member = authorization.authorizedMembers.singleOrNull {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible &&
				it.logicalTrackingId == null && it.serviceRunId == null
		} ?: return@withTransaction LocationAmbientQualification.NotEligible
		val demand = demands.singleOrNull { it.demandId == member.demandId }
			?: return@withTransaction reject(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		val plan = try { actualRegistrationPlan(wal) } catch (failure: LocationPhysicalEvidenceRejected) {
			return@withTransaction reject(failure.reason)
		} ?: return@withTransaction reject(LocationWalAdapterRejection.MISSING_HISTORICAL_PLAN)
		val registrationProof = database.authenticateHistoricalProviderRegistration(
			sourceKind = LOCATION_SOURCE,
			registrationGeneration = wal.registrationGeneration,
			sourceInstanceId = wal.sourceInstanceId,
			clockDomainId = wal.clockDomainId,
			physicalConfigurationFingerprint = requireNotNull(wal.physicalConfigurationFingerprint),
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			expectedProviderResidency = if (plan.passiveSystemDelivery)
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE else
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = wal.observedElapsedNanos,
			observedEndNanos = wal.observedElapsedNanos,
			observedWallTimeMs = wall,
			currentClockDomainId = bootClockDomainProvider.current(),
		)
		if (registrationProof is ProviderRegistrationIntervalAuthentication.Rejected) {
			return@withTransaction reject(if (registrationProof.reason == ProviderRegistrationIntervalFailure.MISSING_REGISTRATION)
				LocationWalAdapterRejection.MISSING_REGISTRATION else LocationWalAdapterRejection.REGISTRATION_MISMATCH)
		}
		val policyDao = database.sourcePolicyDao()
		val policy = policyDao.policyAtRevision(demand.sourcePolicyRevision, LOCATION_SOURCE)
			?: return@withTransaction reject(LocationWalAdapterRejection.MISSING_POLICY)
		val consent = policyDao.consentEpoch(LOCATION_SOURCE, SourceBrokerPurpose.AMBIENT_PRODUCT, demand.consentEpoch)
		if (!policy.hasExactEligibleAmbientConsentReference(consent) ||
			(policy.effectiveBootId == wal.clockDomainId && policy.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos) || consent == null ||
			(consent.effectiveBootId == wal.clockDomainId && consent.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos)
		) return@withTransaction reject(LocationWalAdapterRejection.CONSENT_MISMATCH)
		val retention = database.locationPurposeDao().retentionAuthorityAt(
			AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, wal.clockDomainId, wal.observedElapsedNanos)
		if (retention == null || !retention.isActive ||
			!AmbientLocationRetentionAuthorityIntegrity.isAuthentic(retention) ||
			retention.approvalRevision != demand.liveAmbientRetentionApprovalRevision || retention.opaquePolicyId != demand.liveAmbientRetentionPolicyId ||
			retention.sourcePolicyRevision != demand.sourcePolicyRevision || retention.ambientConsentEpoch != demand.consentEpoch ||
			retention.collectedDataEpoch != wal.capturedCollectedDataEpoch || retention.effectiveWallTimeMs > wall
		) return@withTransaction reject(LocationWalAdapterRejection.RETAINED_EVIDENCE)
		val maximumAccuracy = policy.locationRequiredAccuracyMeters?.toFloat()
			?: return@withTransaction reject(LocationWalAdapterRejection.POLICY_MISMATCH)
		val maximumAge = runCatching { Math.multiplyExact(demand.maximumAgeMs, 1_000_000L) }.getOrDefault(Long.MAX_VALUE)
		val quality = sourceQualityFromStableFlags(wal.qualityFlags, wal.qualityConfidence)
		if (physical.payload.isMock == null || wal.payloadVersion !in setOf(LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION, LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION) ||
			wal.receivedElapsedNanos - wal.observedElapsedNanos > maximumAge ||
			SourceQualityFlag.CACHED in quality.flags ||
			(SourceQualityFlag.APPROXIMATE in quality.flags) == plan.preciseLocationAvailable ||
			LocationQualifiedObservationQualifier.payloadRejection(physical.payload, maximumAccuracy) != null
		) return@withTransaction reject(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		LocationAmbientQualification.Qualified(LocationAmbientFactCommand(wal, physical.payload,
			LocationAmbientAuthority(demand.demandId, demand.sourcePolicyRevision, demand.consentEpoch,
				retention.opaquePolicyId, retention.approvalRevision, lifecycle.collectedDataEpoch,
				lifecycle.retainedFromMs, maximumAge, maximumAccuracy),
			LocationWalAcquisitionMetadata(plan.canonicalAcquisitionMode(), plan.canonicalRequestPriority(), maximumAccuracy,
				policy.qosCode.toLocationPolicyTier(), policy.qosCode.toLocationPolicyName(),
				PROTECTED_LOCATION_CANONICAL_CURATION_VERSION, AltitudeContractVersions.MODEL_VERSION,
				AltitudeContractVersions.ESTIMATOR_VERSION, AltitudeContractVersions.CALIBRATION_VERSION)))
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	override suspend fun qualify(eventId: SourceEventId): LocationWalAdapterResult =
		database.withTransaction {
		val physical = try { authenticatePhysical(eventId) } catch (failure: LocationPhysicalEvidenceRejected) {
			return@withTransaction rejected(failure.reason)
		}
		val wal = physical.wal
		val deliveryIdentity = physical.deliveryIdentity
		val deliveryUnitIndex = requireNotNull(wal.deliveryUnitIndex)
		val deliveryUnitCount = requireNotNull(wal.deliveryUnitCount)
		val configRevision = wal.configRevision
		val policyRevision = wal.sourcePolicyRevision
		val consentEpoch = wal.captureConsentEpoch
		val manifestRevision = wal.sessionManifestRevision
		val leaseGeneration = wal.lifecycleLeaseGeneration
		val logicalTrackingId = wal.logicalTrackingId
		val serviceRunId = wal.serviceRunId
		val physicalFingerprint = wal.physicalConfigurationFingerprint
		val authorizationRevision = wal.authorizationRevision
		val authorizationFingerprint = wal.authorizationFingerprint
		if (configRevision == null || policyRevision == null || consentEpoch == null ||
			manifestRevision == null || leaseGeneration == null || logicalTrackingId == null ||
			serviceRunId == null || physicalFingerprint == null || authorizationRevision == null ||
			authorizationFingerprint == null || wal.planAttribution != PlanAttribution.CAPTURED_REGISTRATION.ordinal ||
			wal.observedIntervalStartNanos != wal.observedElapsedNanos || wal.activityAutomationEpoch != null
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		}

		val planDao = database.sourcePlanStateDao()
		val planHeader = planDao.revision(configRevision)
		val desiredPlan = planDao.desiredPlan(configRevision, LOCATION_SOURCE)
		if (planHeader == null || desiredPlan == null) {
			return@withTransaction rejected(LocationWalAdapterRejection.MISSING_HISTORICAL_PLAN)
		}
		val requestedPlan = runCatching { planCodec.decode(desiredPlan.payload) as? LocationPlan }.getOrNull()
		val plan = try { actualRegistrationPlan(wal) } catch (failure: LocationPhysicalEvidenceRejected) {
			return@withTransaction rejected(failure.reason)
		} ?: requestedPlan
		val reencodedPlan = requestedPlan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
		if (desiredPlan.payloadVersion != (if (requestedPlan?.adaptiveProfileVersion != null) 2 else PLAN_PAYLOAD_VERSION) || desiredPlan.revision != configRevision ||
			desiredPlan.sourceKind != LOCATION_SOURCE || plan == null || !plan.enabled ||
			!plan.hasSupportedHistoricalShape() ||
			plan.revision != configRevision || reencodedPlan == null ||
			!reencodedPlan.bytes.contentEquals(desiredPlan.payload) ||
			reencodedPlan.checksum != desiredPlan.payloadChecksum ||
			plan.physicalConfigurationFingerprint() != physicalFingerprint ||
			planHeader.sourcePolicyRevision != policyRevision
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}

		val brokerDao = database.sourceBrokerDao()
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
				?: return@withTransaction rejected(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING),
			currentClockDomainId = bootClockDomainProvider.current(),
		)
		val authenticatedRegistration = when (registrationAuthentication) {
			is ProviderRegistrationIntervalAuthentication.Authenticated ->
				registrationAuthentication
			is ProviderRegistrationIntervalAuthentication.Rejected ->
				return@withTransaction rejected(
					if (registrationAuthentication.reason ==
						ProviderRegistrationIntervalFailure.MISSING_REGISTRATION
					) {
						LocationWalAdapterRejection.MISSING_REGISTRATION
					} else {
						LocationWalAdapterRejection.REGISTRATION_MISMATCH
					},
				)
		}
		val registration = authenticatedRegistration.registration
		val registrationStart = authenticatedRegistration.interval.startInclusiveNanos
		val registrationEnd = authenticatedRegistration.interval.endExclusiveNanos

		val authorization = brokerDao.authorizationRevision(
			LOCATION_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
		).toAuthorizationSnapshotOrNull()
			?: return@withTransaction rejected(LocationWalAdapterRejection.MISSING_AUTHORIZATION)
		val observedAuthorization = brokerDao.authorizationAt(
			LOCATION_SOURCE,
			wal.registrationGeneration,
			wal.clockDomainId,
			wal.observedElapsedNanos,
		).toAuthorizationSnapshotOrNull()
		val captureMember = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
				member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
				member.manifestRevision == manifestRevision &&
				member.lifecycleLeaseGeneration == leaseGeneration &&
				member.sourcePolicyRevision == policyRevision && member.consentEpoch == consentEpoch
		}
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
				authorization.members.sortedBy { it.memberId } || observedAuthorization != authorization ||
			captureMember == null || authorization.isDenied ||
			authorization.authorizationFingerprint != authorizationFingerprint ||
			!hasExactQualifiedMembership(wal, authorization, authorizationDemands) ||
			authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			authorization.effectiveBootId != wal.clockDomainId
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		val nextAuthorization = brokerDao.nextAuthorizationRevision(
			LOCATION_SOURCE,
			wal.registrationGeneration,
			authorization.authorizationRevision,
		).toAuthorizationSnapshotOrNull()
		val authorizationEnd = authorizationEndExclusive(
			registrationEnd,
			authorization,
			nextAuthorization,
		)
			?: return@withTransaction rejected(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)

		val policyDao = database.sourcePolicyDao()
		val policyAuthority = policyDao.authority()
		val policy = policyDao.policyAtRevision(policyRevision, LOCATION_SOURCE)
		if (policy == null) return@withTransaction rejected(LocationWalAdapterRejection.MISSING_POLICY)
		val maximumAccuracy = policy.locationRequiredAccuracyMeters
		if (policyAuthority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			policyAuthority.currentPolicyRevision < policyRevision || !policy.enabled ||
			!policy.capturePersistenceEligible || policy.captureConsentEpoch != consentEpoch ||
			maximumAccuracy == null || maximumAccuracy <= 0 || policy.qosCode !in 1..3 ||
			(policy.effectiveBootId == wal.clockDomainId &&
				policy.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos)
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.POLICY_MISMATCH)
		}
		val consent = policyDao.consentEpoch(
			LOCATION_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch,
		) ?: return@withTransaction rejected(LocationWalAdapterRejection.MISSING_CONSENT)
		val latestConsent = policyDao.latestConsentEpoch(LOCATION_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE)
		if (!consent.eligible || !consent.persistenceEligible || consent.policyRevision > policyRevision ||
			(consent.effectiveBootId == wal.clockDomainId &&
				consent.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos) ||
			latestConsent == null || latestConsent.epoch < consentEpoch
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.CONSENT_MISMATCH)
		}

		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId)
		val session = sessionDao.session(logicalTrackingId)
		if (run == null || session == null) {
			return@withTransaction rejected(LocationWalAdapterRejection.MISSING_SESSION)
		}
		if (run.logicalTrackingId != logicalTrackingId || run.bootId != wal.clockDomainId ||
			run.leaseGeneration != leaseGeneration || session.logicalTrackingId != logicalTrackingId ||
			session.lifecycleLeaseGeneration < leaseGeneration || run.startedElapsedNanos > wal.observedElapsedNanos ||
			session.cutoffElapsedNanos?.let { wal.observedElapsedNanos >= it } == true
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.SESSION_MISMATCH)
		}
		val manifests = sessionDao.manifestsForServiceRun(serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
		if (manifests.size > MAX_MANIFESTS_PER_RUN ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
		}
		val allManifestSources = database.trackingHistoryReadDao().manifestSources(
			listOf(serviceRunId),
			MAX_MANIFEST_SOURCE_ROWS + 1,
		)
		if (allManifestSources.size > MAX_MANIFEST_SOURCE_ROWS || manifests.any { manifest ->
			!SessionManifestIntegrity.verify(
				manifest,
				allManifestSources.filter { source ->
					source.logicalTrackingId == manifest.logicalTrackingId &&
						source.manifestRevision == manifest.manifestRevision
				},
			)
		}) {
			return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
		}
		val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		if (manifestIndex < 0) return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		val manifest = manifests[manifestIndex]
		val manifestSources = allManifestSources.filter { source ->
			source.logicalTrackingId == logicalTrackingId && source.manifestRevision == manifestRevision
		}
		if (manifestSources.any { source ->
			SourceKind.entries.none { it.stableCode == source.sourceKind } ||
				source.purpose !in SessionManifestPurposeCode.ALL
		}) {
			return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		}
		val capturedSources = manifestSources
			.filter { it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && it.persistenceEligible }
			.mapNotNull { source -> SourceKind.entries.singleOrNull { it.stableCode == source.sourceKind } }
			.toSet()
		val controlSources = manifestSources
			.filter { it.purpose == SessionManifestPurposeCode.CONTROL }
			.mapNotNull { source -> SourceKind.entries.singleOrNull { it.stableCode == source.sourceKind } }
			.toSet()
		val locationManifestSource = manifestSources.singleOrNull { source ->
			source.sourceKind == LOCATION_SOURCE &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		}
		if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId ||
			manifest.sourcePolicyRevision != policyRevision ||
			manifest.acquisitionPlanRevision != configRevision || manifest.effectiveBootId != wal.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos ||
			locationManifestSource == null || locationManifestSource.consentEpoch != consentEpoch ||
			locationManifestSource.qosCode != policy.qosCode || SourceKind.LOCATION !in capturedSources
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		}
		val writerBindingGeneration = locationManifestSource.locationCaptureBindingGenerationOrNull()
			?: return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		val sessionModeMask = when (manifest.sessionMode) {
			"MANUAL" -> 1L
			"AUTOMATIC" -> 2L
			else -> return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		}
		if (requireNotNull(SourceWriterGenerationContract.captureModeMaskForKnownBinding(
				writerBindingGeneration)) and sessionModeMask == 0L
		) return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		if (runCatching { ZoneId.of(manifest.zoneId) }.isFailure) {
			return@withTransaction rejected(LocationWalAdapterRejection.INVALID_STORED_ZONE)
		}
		val segmentId = run.sessionSegmentId
		val segment = segmentId?.let { database.sessionSegmentDao().getById(it) }
		if (segment == null) return@withTransaction rejected(LocationWalAdapterRejection.MISSING_SEGMENT)
		if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId) {
			return@withTransaction rejected(LocationWalAdapterRejection.SEGMENT_MISMATCH)
		}

		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return@withTransaction rejected(LocationWalAdapterRejection.DELETED_EVIDENCE)
		if (evidenceState.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.DELETED_EVIDENCE)
		}
		val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
		val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, registrationEnd, authorizationEnd)
		val temporal = runCatching {
			LocationCaptureTemporalAuthority(
				providerRegistration = interval(registrationStart, registrationEnd),
				authorization = interval(authorization.effectiveElapsedRealtimeNanos, authorizationEnd),
				sourcePolicy = interval(if (policy.effectiveBootId == wal.clockDomainId) policy.effectiveElapsedRealtimeNanos else 0L, authorizationEnd),
				captureConsent = interval(if (consent.effectiveBootId == wal.clockDomainId) consent.effectiveElapsedRealtimeNanos else 0L, authorizationEnd),
				sessionManifest = interval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
				lifecycleLease = interval(run.startedElapsedNanos, minOf(sessionEnd, registrationEnd)),
			)
		}.getOrNull() ?: return@withTransaction rejected(
			LocationWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
		)
		val captureAuthority = runCatching {
			LocationCaptureAuthority(
				logicalTrackingId = LogicalTrackingId(logicalTrackingId),
				serviceRunId = ServiceRunId(serviceRunId),
				sessionSegmentId = segment.id,
				capturedSources = capturedSources,
				controlSources = controlSources,
				sourceInstanceId = SourceInstanceId(wal.sourceInstanceId),
				registrationGeneration = wal.registrationGeneration,
				configurationRevision = configRevision,
				physicalConfigurationFingerprint = physicalFingerprint,
				authorizationRevision = authorizationRevision,
				authorizationFingerprint = authorizationFingerprint,
				purposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
				sourcePolicyRevision = policyRevision,
				captureConsentEpoch = consentEpoch,
				sessionManifestRevision = manifestRevision,
				writerBindingGeneration = writerBindingGeneration,
				lifecycleLeaseGeneration = leaseGeneration,
				capturedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
				clockDomainId = wal.clockDomainId,
				zoneId = manifest.zoneId,
				permissionPrecision = if (plan.preciseLocationAvailable) {
					LocationPermissionPrecision.PRECISE
				} else {
					LocationPermissionPrecision.APPROXIMATE
				},
				temporalAuthority = temporal,
				acquisitionConfiguration = LocationHistoricalAcquisitionConfiguration(
					maximumObservationAgeNanos = minOf(
						requireNotNull(requestedPlan).maximumLocationEvidenceAgeNanos(),
						runCatching { Math.multiplyExact(authorizationDemands.single {
							it.demandId == captureMember.demandId
						}.maximumAgeMs, 1_000_000L) }.getOrDefault(Long.MAX_VALUE),
					),
					maximumHorizontalAccuracyMeters = maximumAccuracy.toFloat(),
				),
			)
		}.getOrNull() ?: return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		val wallTime = wal.wallTimeMs
		val receivedWallTime = wal.receivedWallTimeMs
		val wallUncertainty = wal.wallTimeUncertaintyMs
		val knownQualityMask = SourceQualityFlag.entries.fold(0L) { mask, flag -> mask or flag.bit }
		if (wallTime == null ||
			(wal.payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION &&
				(receivedWallTime == null || receivedWallTime < 0L)) ||
			wallUncertainty == null || wallUncertainty < 0L ||
			wal.qualityFlags and knownQualityMask != wal.qualityFlags ||
			wal.qualityConfidence?.let { confidence -> !confidence.isFinite() || confidence !in 0f..1f } == true
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		}
		val earliestPossibleWallTimeMs = CanonicalRetentionBounds.location(
			wallTime,
			wallUncertainty,
		) ?: return@withTransaction rejected(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		if (evidenceState.retainedFromMs?.let { retainedFrom ->
			earliestPossibleWallTimeMs < retainedFrom
		} == true) {
			return@withTransaction rejected(LocationWalAdapterRejection.RETAINED_EVIDENCE)
		}
		val selectedPayload = physical.payload
		if (wal.payloadVersion < LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION ||
			selectedPayload.isMock == null
		) {
			// Retained Location WAL payload v1 did not carry the platform mock-provider bit. Supplying
			// `false` would manufacture immutable evidence, so legacy bytes remain typed unverifiable.
			return@withTransaction rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE)
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
		return@withTransaction LocationWalAdapterResult.Evaluated(
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

	/** Ingress narrows eligibility independently for each fix in a batched physical delivery. */
	private fun hasExactQualifiedMembership(wal: SourceEventWalEntity, authorization: SourceAuthorizationSnapshot,
		demands: List<SourceDemandEntity>): Boolean {
		val age = wal.receivedElapsedNanos - wal.observedElapsedNanos
		if (age < 0) return false
		val eligible = authorization.authorizedMembers.filter { member ->
			val demand = demands.singleOrNull { it.demandId == member.demandId } ?: return@filter false
			age <= runCatching { Math.multiplyExact(demand.maximumAgeMs, 1_000_000L) }.getOrDefault(Long.MAX_VALUE)
		}
		val mask = eligible.fold(0L) { bits, member -> bits or SourceBrokerPurpose.mask(requireNotNull(member.purpose)) }
		if (mask == 0L || mask != wal.authorizationPurposeEligibilityMask) return false
		val captures = eligible.filter { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE && it.persistenceEligible }
		if (captures.size > 1) return false
		val capture = captures.singleOrNull()
		return wal.logicalTrackingId == capture?.logicalTrackingId && wal.serviceRunId == capture?.serviceRunId &&
			wal.sourcePolicyRevision == capture?.sourcePolicyRevision && wal.captureConsentEpoch == capture?.consentEpoch &&
			wal.sessionManifestRevision == capture?.manifestRevision && wal.lifecycleLeaseGeneration == capture?.lifecycleLeaseGeneration &&
			wal.planAttribution == (if (capture == null) PlanAttribution.RECEIVE_TIME_ONLY else PlanAttribution.CAPTURED_REGISTRATION).ordinal
	}

	private suspend fun actualRegistrationPlan(wal: SourceEventWalEntity): LocationPlan? {
		val revision = wal.configRevision ?: return null
		val authorizationRevision = wal.authorizationRevision ?: return null
		val stored = database.locationPurposeDao().registrationPlan(wal.registrationGeneration, revision, authorizationRevision)
			?: return null
		val plan = runCatching { planCodec.decode(stored.payload) as? LocationPlan }.getOrNull()
		val encoded = plan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
		if (stored.sourceInstanceId != wal.sourceInstanceId || stored.payloadVersion != when {
			plan?.passiveSystemDelivery == true -> 4
			plan?.adaptiveProfileVersion != null -> 2
			else -> PLAN_PAYLOAD_VERSION
		} ||
			stored.physicalConfigurationFingerprint != wal.physicalConfigurationFingerprint || plan == null ||
			!plan.enabled || !plan.hasSupportedHistoricalShape() || plan.revision != revision || encoded == null ||
			!encoded.bytes.contentEquals(stored.payload) || encoded.checksum != stored.payloadChecksum ||
			plan.physicalConfigurationFingerprint() != stored.physicalConfigurationFingerprint
		) throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		return plan
	}

	private suspend fun authenticatePhysical(eventId: SourceEventId): LocationPhysicalEvidence {
		val walDao = database.sourceEventWalDao()
		val selectedPreflight = walDao.payloadPreflightByEventId(eventId.value)
			?: throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.MISSING_EVENT)
		if (selectedPreflight.sourceKind != LOCATION_SOURCE) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.WRONG_SOURCE)
		}
		if (selectedPreflight.payloadBytes > MAX_LOCATION_PAYLOAD_BYTES) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE)
		}
		if (selectedPreflight.payloadBytes <= 0L) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		val wal = walDao.boundedPayloadByEventId(eventId.value, MAX_LOCATION_PAYLOAD_BYTES)
			?: throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		if (wal.eventId != selectedPreflight.eventId || wal.sourceKind != selectedPreflight.sourceKind ||
			wal.capturedCollectedDataEpoch != selectedPreflight.capturedCollectedDataEpoch ||
			wal.clockDomainId != selectedPreflight.clockDomainId ||
			wal.deliveryIdentity != selectedPreflight.deliveryIdentity ||
			wal.deliveryUnitIndex != selectedPreflight.deliveryUnitIndex ||
			wal.deliveryUnitCount != selectedPreflight.deliveryUnitCount ||
			wal.payload.size.toLong() != selectedPreflight.payloadBytes
		) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		if (!wal.hasQualifiedIntegrity()) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.WAL_INTEGRITY_MISMATCH)
		}
		if (wal.payloadVersion !in SUPPORTED_LOCATION_PAYLOAD_VERSIONS) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.UNSUPPORTED_PAYLOAD)
		}
		if (wal.sourceSequence < 0L || wal.sourceSequence > MAX_DURABLE_SOURCE_SEQUENCE) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
		}
		val sequenceRow = walDao.identityBySourceSequence(
			LOCATION_SOURCE,
			wal.sourceInstanceId,
			wal.sourceSequence,
		)
		if (sequenceRow?.eventId != wal.eventId || sequenceRow.admissionOrdinal != wal.admissionOrdinal ||
			sequenceRow.integrityIdentity != wal.integrityIdentity
		) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
		}

		val deliveryIdentity = wal.deliveryIdentity?.let { value ->
			runCatching { SourceDeliveryIdentity(value) }.getOrNull()
		} ?: throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		val deliveryUnitIndex = wal.deliveryUnitIndex
		val deliveryUnitCount = wal.deliveryUnitCount
		if (deliveryUnitIndex == null || deliveryUnitCount == null ||
			deliveryUnitIndex !in 0 until deliveryUnitCount
		) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		}
		if (deliveryUnitCount > MAX_LOCATION_DELIVERY_UNITS) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE)
		}
		val deliveryPreflight = walDao.deliveryPayloadPreflight(
			sourceKind = LOCATION_SOURCE,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			clockDomainId = wal.clockDomainId,
			deliveryIdentity = deliveryIdentity.value,
			limit = MAX_LOCATION_DELIVERY_UNITS + 1,
		)
		if (deliveryPreflight.size > MAX_LOCATION_DELIVERY_UNITS ||
			deliveryPreflight.any { it.payloadBytes > MAX_LOCATION_PAYLOAD_BYTES }
		) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE)
		}
		if (deliveryPreflight.size != deliveryUnitCount ||
			deliveryPreflight.any { it.payloadBytes <= 0L || it.deliveryUnitCount != deliveryUnitCount } ||
			deliveryPreflight.mapNotNull { it.deliveryUnitIndex } != deliveryPreflight.indices.toList() ||
			deliveryPreflight[deliveryUnitIndex].eventId != wal.eventId
		) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		val delivery = walDao.deliveryEventsWithBoundedPayload(
			sourceKind = LOCATION_SOURCE,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			clockDomainId = wal.clockDomainId,
			deliveryIdentity = deliveryIdentity.value,
			maximumPayloadBytes = MAX_LOCATION_PAYLOAD_BYTES,
			limit = MAX_LOCATION_DELIVERY_UNITS + 1,
		)
		if (delivery.size != deliveryPreflight.size || delivery.indices.any { index ->
			val row = delivery[index]
			val preflight = deliveryPreflight[index]
			row.eventId != preflight.eventId || row.deliveryUnitIndex != preflight.deliveryUnitIndex ||
				row.deliveryUnitCount != preflight.deliveryUnitCount ||
				row.payload.size.toLong() != preflight.payloadBytes
		}) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		val decodedDelivery = decodeAndAuthenticateDelivery(delivery, wal)
			?: throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		if (decodedDelivery.getOrNull(deliveryUnitIndex)?.wal?.hasSamePersistedValueAs(wal) != true) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		if (sourceDeliveryIdentity(canonicalDeliveryBytes(decodedDelivery)).value != deliveryIdentity.value) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH)
		}
		return LocationPhysicalEvidence(wal, decodedDelivery[deliveryUnitIndex].payload, deliveryIdentity)
	}

	private data class LocationPhysicalEvidence(
		val wal: SourceEventWalEntity,
		val payload: LocationFixPayload,
		val deliveryIdentity: SourceDeliveryIdentity,
	)

	private class LocationPhysicalEvidenceRejected(val reason: LocationWalAdapterRejection) : IllegalStateException()

	private fun decodeAndAuthenticateDelivery(
		delivery: List<SourceEventWalEntity>,
		target: SourceEventWalEntity,
	): List<DecodedLocationDeliveryUnit>? {
		val declaredCount = target.deliveryUnitCount ?: return null
		val targetIndex = target.deliveryUnitIndex ?: return null
		if (declaredCount !in 1..MAX_LOCATION_DELIVERY_UNITS || targetIndex !in 0 until declaredCount ||
			delivery.size != declaredCount ||
			delivery.mapNotNull(SourceEventWalEntity::deliveryUnitIndex) != delivery.indices.toList()
		) return null
		val firstSequence = delivery.first().sourceSequence
		val lastSequence = runCatching {
			Math.addExact(firstSequence, declaredCount.toLong() - 1L)
		}.getOrNull() ?: return null
		if (firstSequence < 0L || lastSequence > MAX_DURABLE_SOURCE_SEQUENCE) return null
		val decoded = delivery.mapIndexed { index, row ->
			if (row.payload.size !in 1..MAX_LOCATION_PAYLOAD_BYTES ||
				!row.hasQualifiedIntegrity() || row.deliveryUnitCount != declaredCount ||
				row.deliveryIdentity != target.deliveryIdentity || row.sourceKind != LOCATION_SOURCE ||
				row.payloadVersion != target.payloadVersion ||
				row.providerDedupKey != null || row.admissionOrdinal <= 0L ||
				row.sourceInstanceId != target.sourceInstanceId ||
				row.registrationGeneration != target.registrationGeneration ||
				row.physicalConfigurationFingerprint != target.physicalConfigurationFingerprint ||
				row.configRevision != target.configRevision ||
				row.capturedCollectedDataEpoch != target.capturedCollectedDataEpoch ||
				row.clockDomainId != target.clockDomainId || row.activityAutomationEpoch != null ||
				row.observedIntervalStartNanos != row.observedElapsedNanos ||
				row.receivedElapsedNanos < row.observedElapsedNanos ||
				(row.payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION &&
					row.receivedWallTimeMs == null) ||
				row.receivedWallTimeMs != target.receivedWallTimeMs ||
				row.wallTimeMs == null || row.wallTimeUncertaintyMs == null ||
				row.wallTimeUncertaintyMs !in 0L..1L ||
				row.sourceSequence != runCatching { Math.addExact(firstSequence, index.toLong()) }.getOrNull()
			) return null
			val payload = runCatching {
				payloadCodec.decode(SourceKind.LOCATION, row.payloadVersion, row.payload) as? LocationFixPayload
			}.getOrNull() ?: return null
			val canonicalPayload = runCatching { payloadCodec.encode(payload, row.payloadVersion) }.getOrNull()
				?: return null
			if (!canonicalPayload.bytes.contentEquals(row.payload) ||
				canonicalPayload.checksum != row.payloadChecksum
			) return null
			DecodedLocationDeliveryUnit(row, payload)
		}
		return decoded.takeIf { units -> units[targetIndex].wal.hasSamePersistedValueAs(target) }
	}

	private fun SourceEventWalEntity.hasSamePersistedValueAs(other: SourceEventWalEntity): Boolean =
		admissionOrdinal == other.admissionOrdinal &&
			eventId == other.eventId &&
			providerDedupKey == other.providerDedupKey &&
			deliveryIdentity == other.deliveryIdentity &&
			deliveryUnitIndex == other.deliveryUnitIndex &&
			deliveryUnitCount == other.deliveryUnitCount &&
			logicalTrackingId == other.logicalTrackingId &&
			serviceRunId == other.serviceRunId &&
			sourceKind == other.sourceKind &&
			sourceInstanceId == other.sourceInstanceId &&
			registrationGeneration == other.registrationGeneration &&
			physicalConfigurationFingerprint == other.physicalConfigurationFingerprint &&
			authorizationRevision == other.authorizationRevision &&
			authorizationPurposeEligibilityMask == other.authorizationPurposeEligibilityMask &&
			authorizationFingerprint == other.authorizationFingerprint &&
			sourceSequence == other.sourceSequence &&
			configRevision == other.configRevision &&
			planAttribution == other.planAttribution &&
			clockDomainId == other.clockDomainId &&
			observedElapsedNanos == other.observedElapsedNanos &&
			observedIntervalStartNanos == other.observedIntervalStartNanos &&
			receivedElapsedNanos == other.receivedElapsedNanos &&
			receivedWallTimeMs == other.receivedWallTimeMs &&
			wallTimeMs == other.wallTimeMs &&
			wallTimeUncertaintyMs == other.wallTimeUncertaintyMs &&
			capturedCollectedDataEpoch == other.capturedCollectedDataEpoch &&
			activityAutomationEpoch == other.activityAutomationEpoch &&
			sourcePolicyRevision == other.sourcePolicyRevision &&
			captureConsentEpoch == other.captureConsentEpoch &&
			sessionManifestRevision == other.sessionManifestRevision &&
			lifecycleLeaseGeneration == other.lifecycleLeaseGeneration &&
			acquiredAtMs == other.acquiredAtMs &&
			qualityFlags == other.qualityFlags &&
			qualityConfidence == other.qualityConfidence &&
			payloadVersion == other.payloadVersion &&
			payload.contentEquals(other.payload) &&
			payloadChecksum == other.payloadChecksum &&
			integrityIdentity == other.integrityIdentity &&
			createdAtMs == other.createdAtMs

	private fun authorizationEndExclusive(
		registrationEnd: Long,
		authorization: SourceAuthorizationSnapshot,
		next: SourceAuthorizationSnapshot?,
	): Long? {
		if (next == null) return registrationEnd
		if (next.authorizationRevision <= authorization.authorizationRevision) return null
		if (next.effectiveBootId != authorization.effectiveBootId ||
			next.effectiveElapsedRealtimeNanos < authorization.effectiveElapsedRealtimeNanos
		) return null
		return minOf(registrationEnd, next.effectiveElapsedRealtimeNanos)
	}

	private fun canonicalDeliveryBytes(units: List<DecodedLocationDeliveryUnit>): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				val payloadVersion = units.first().wal.payloadVersion
				output.writeInt(LOCATION_DELIVERY_MAGIC)
				output.writeInt(
                    if (payloadVersion >= LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION) {
                        LOCATION_OPTIONAL_ACCURACY_DELIVERY_VERSION
                    } else if (payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION) {
						LOCATION_MOCK_PROVENANCE_DELIVERY_VERSION
					} else {
						LOCATION_LEGACY_DELIVERY_VERSION
					},
				)
				output.writeInt(units.size)
				units.forEach { unit ->
					val row = unit.wal
					val payload = unit.payload
					val providerBytes = payload.provider.encodeToByteArray()
					output.writeInt(providerBytes.size)
					output.write(providerBytes)
					output.writeLong(row.observedElapsedNanos)
					output.writeLong(if (row.wallTimeUncertaintyMs == DERIVED_WALL_UNCERTAINTY_MS) 0L else requireNotNull(row.wallTimeMs))
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.latitudeDegrees))
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.longitudeDegrees))
                    if (payloadVersion >= LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION) {
                        output.writeOptionalFloat(payload.horizontalAccuracyMeters)
                    } else {
                        output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.horizontalAccuracyMeters)))
                    }
					output.writeOptionalDouble(payload.altitudeMeters)
					output.writeOptionalFloat(payload.verticalAccuracyMeters)
					output.writeOptionalFloat(payload.speedMetersPerSecond)
					output.writeOptionalFloat(payload.bearingDegrees)
					if (payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION) {
						output.writeBoolean(requireNotNull(payload.isMock))
					}
				}
			}
			bytes.toByteArray()
		}

	private fun DataOutputStream.writeOptionalDouble(value: Double?) {
		writeBoolean(value != null)
		if (value != null) writeLong(java.lang.Double.doubleToRawLongBits(value))
	}

	private fun DataOutputStream.writeOptionalFloat(value: Float?) {
		writeBoolean(value != null)
		if (value != null) writeInt(java.lang.Float.floatToRawIntBits(value))
	}

	private data class DecodedLocationDeliveryUnit(
		val wal: SourceEventWalEntity,
		val payload: LocationFixPayload,
	)

	private companion object {
		const val LOCATION_SOURCE = 1
		const val PLAN_PAYLOAD_VERSION = 1
		const val MAX_LOCATION_DELIVERY_UNITS = 256
		// DataOutputStream.writeUTF permits 65,535 provider bytes; nullable accuracy adds one flag.
		const val MAX_LOCATION_PAYLOAD_BYTES = 65_535 + 52
		const val MAX_DURABLE_SOURCE_SEQUENCE = Long.MAX_VALUE - 1L
		const val MAX_MANIFESTS_PER_RUN = 256
		const val MAX_MANIFEST_SOURCE_ROWS = 4_096
		const val LOCATION_DELIVERY_MAGIC = 0x4c4f4342
		const val LOCATION_LEGACY_DELIVERY_VERSION = 1
		const val LOCATION_MOCK_PROVENANCE_DELIVERY_VERSION = 2
		const val LOCATION_OPTIONAL_ACCURACY_DELIVERY_VERSION = 3
		const val DERIVED_WALL_UNCERTAINTY_MS = 1L
		val SUPPORTED_LOCATION_PAYLOAD_VERSIONS =
			setOf(LOCATION_LEGACY_DELIVERY_VERSION, LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
                LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION)
	}
}

private fun interval(startInclusiveNanos: Long, endExclusiveNanos: Long) =
	LocationProviderTimeInterval(startInclusiveNanos, endExclusiveNanos)

internal fun LocationPlan.maximumLocationEvidenceAgeNanos(): Long {
	val maximumAgeMs = if (adaptiveProfileVersion == 1) 120_000L else {
		maxOf(requestedIntervalMs, maximumBatchDelayMs).coerceAtLeast(1L)
	}
	return if (maximumAgeMs > Long.MAX_VALUE / 1_000_000L) {
		Long.MAX_VALUE
	} else {
		maximumAgeMs * 1_000_000L
	}
}

private fun LocationPlan.hasSupportedHistoricalShape(): Boolean =
	requestedIntervalMs > 0L && minimumUpdateIntervalMs >= 0L &&
		minimumUpdateIntervalMs <= requestedIntervalMs &&
		minimumDisplacementMeters.isFinite() && minimumDisplacementMeters >= 0f &&
		maximumBatchDelayMs >= 0L && (probeDurationMs == null || probeDurationMs > 0L)

private fun LocationPlan.canonicalAcquisitionMode(): LocationAcquisitionMode = when (backend) {
	LocationBackend.FUSED -> LocationAcquisitionMode.FUSED
	LocationBackend.FRAMEWORK -> when (mode) {
		LocationMode.PASSIVE -> LocationAcquisitionMode.PLATFORM_PASSIVE
		LocationMode.HIGH_ACCURACY, LocationMode.PROBE -> LocationAcquisitionMode.PLATFORM_GPS
		else -> LocationAcquisitionMode.UNKNOWN // Existing wire enum has no network-provider label.
	}
}

private fun LocationPlan.canonicalRequestPriority(): LocationRequestPriority = when (mode) {
	LocationMode.DISABLED -> LocationRequestPriority.UNKNOWN
	LocationMode.PASSIVE -> LocationRequestPriority.PASSIVE
	LocationMode.LOW_POWER -> LocationRequestPriority.LOW_POWER
	LocationMode.BALANCED -> LocationRequestPriority.BALANCED
	LocationMode.HIGH_ACCURACY,
	LocationMode.PROBE,
	-> if (preciseLocationAvailable) {
		LocationRequestPriority.HIGH_ACCURACY
	} else {
		LocationRequestPriority.BALANCED
	}
}

private fun Int.toLocationPolicyTier(): PolicyTier = when (this) {
	1, 2 -> PolicyTier.ACTIVE
	3 -> PolicyTier.PRECISION
	else -> error("Unsupported captured Location QoS $this")
}

private fun Int.toLocationPolicyName(): String = when (this) {
	1 -> "SOURCE_QOS_BATTERY_SAVER"
	2 -> "SOURCE_QOS_BALANCED"
	3 -> "SOURCE_QOS_RESPONSIVE"
	else -> error("Unsupported captured Location QoS $this")
}

private fun rejected(reason: LocationWalAdapterRejection) = LocationWalAdapterResult.Rejected(reason)
