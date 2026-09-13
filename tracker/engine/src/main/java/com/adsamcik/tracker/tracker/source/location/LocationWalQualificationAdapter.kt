package com.adsamcik.tracker.tracker.source.location

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
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
	TEMPORAL_AUTHORITY_UNVERIFIABLE,
}

internal sealed interface LocationWalAdapterResult {
	data class Evaluated(
		val qualification: LocationObservationQualification,
	) : LocationWalAdapterResult

	data class Rejected(val reason: LocationWalAdapterRejection) : LocationWalAdapterResult
}

/**
 * Dormant read-only bridge from the canonical source WAL to the pure Location qualifier.
 *
 * The adapter accepts only an event identity. Every provider value and every authority field is
 * recovered and authenticated in one Room snapshot; no caller can supply a fix or a mutable
 * latest-plan pointer. It deliberately returns a command without persisting it, so the established
 * Location writer remains the sole canonical writer.
 */
internal class LocationWalQualificationAdapter @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
) {
	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	suspend fun qualify(eventId: SourceEventId): LocationWalAdapterResult = database.withTransaction {
		val walDao = database.sourceEventWalDao()
		val wal = walDao.getByEventId(eventId.value)
			?: return@withTransaction rejected(LocationWalAdapterRejection.MISSING_EVENT)
		if (wal.sourceKind != LOCATION_SOURCE) {
			return@withTransaction rejected(LocationWalAdapterRejection.WRONG_SOURCE)
		}
		if (!wal.hasQualifiedIntegrity()) {
			return@withTransaction rejected(LocationWalAdapterRejection.WAL_INTEGRITY_MISMATCH)
		}
		val sequenceRow = walDao.getBySourceSequence(
			LOCATION_SOURCE,
			wal.sourceInstanceId,
			wal.sourceSequence,
		)
		if (sequenceRow?.eventId != wal.eventId || sequenceRow.admissionOrdinal != wal.admissionOrdinal ||
			sequenceRow.integrityIdentity != wal.integrityIdentity
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
		}

		val deliveryIdentity = wal.deliveryIdentity?.let { value ->
			runCatching { SourceDeliveryIdentity(value) }.getOrNull()
		} ?: return@withTransaction rejected(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		val delivery = walDao.deliveryEvents(
			sourceKind = LOCATION_SOURCE,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			clockDomainId = wal.clockDomainId,
			deliveryIdentity = deliveryIdentity.value,
			limit = MAX_LOCATION_DELIVERY_UNITS + 1,
		)
		if (delivery.size > MAX_LOCATION_DELIVERY_UNITS) {
			return@withTransaction rejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE)
		}
		val decodedDelivery = decodeAndAuthenticateDelivery(delivery, wal)
			?: return@withTransaction rejected(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		if (sourceDeliveryIdentity(canonicalDeliveryBytes(decodedDelivery)).value != deliveryIdentity.value) {
			return@withTransaction rejected(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH)
		}
		val payload = decodedDelivery[requireNotNull(wal.deliveryUnitIndex)].payload

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
		val plan = runCatching { planCodec.decode(desiredPlan.payload) as? LocationPlan }.getOrNull()
		val reencodedPlan = plan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
		if (desiredPlan.payloadVersion != PLAN_PAYLOAD_VERSION || desiredPlan.revision != configRevision ||
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
		val registration = brokerDao.registration(LOCATION_SOURCE, wal.registrationGeneration)
			?: return@withTransaction rejected(LocationWalAdapterRejection.MISSING_REGISTRATION)
		val registrationStart = registration.acceptedElapsedRealtimeNanos
		if (registrationStart == null || registration.sourceInstanceId != wal.sourceInstanceId ||
			registration.physicalConfigurationFingerprint != physicalFingerprint ||
			registration.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			registration.clockDomainId != wal.clockDomainId ||
			registration.status !in setOf(
				ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			) ||
			registrationStart > wal.observedElapsedNanos ||
			registration.retiredElapsedRealtimeNanos?.let { wal.observedElapsedNanos >= it } == true
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.REGISTRATION_MISMATCH)
		}

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
			authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			authorization.effectiveBootId != wal.clockDomainId
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		val authorizationEnd = authorizationEndExclusive(
			registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE,
			authorization,
			brokerDao.latestAuthorization(LOCATION_SOURCE, wal.registrationGeneration)
				.toAuthorizationSnapshotOrNull(),
			wal.registrationGeneration,
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
			maximumAccuracy == null || maximumAccuracy <= 0 || policy.effectiveBootId != wal.clockDomainId ||
			policy.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos
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
			consent.effectiveBootId != wal.clockDomainId ||
			consent.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos ||
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
		val registrationEnd = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, registrationEnd, authorizationEnd)
		val temporal = runCatching {
			LocationCaptureTemporalAuthority(
				providerRegistration = interval(registrationStart, registrationEnd),
				authorization = interval(authorization.effectiveElapsedRealtimeNanos, authorizationEnd),
				sourcePolicy = interval(policy.effectiveElapsedRealtimeNanos, authorizationEnd),
				captureConsent = interval(consent.effectiveElapsedRealtimeNanos, authorizationEnd),
				sessionManifest = interval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
				lifecycleLease = interval(run.startedElapsedNanos, minOf(sessionEnd, registrationEnd)),
			)
		}.getOrNull() ?: return@withTransaction rejected(
			LocationWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
		)
		val authority = runCatching {
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
					maximumObservationAgeNanos = plan.maximumLocationEvidenceAgeNanos(),
					maximumHorizontalAccuracyMeters = maximumAccuracy.toFloat(),
				),
			)
		}.getOrNull() ?: return@withTransaction rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH)
		val wallTime = wal.wallTimeMs
		val wallUncertainty = wal.wallTimeUncertaintyMs
		val knownQualityMask = SourceQualityFlag.entries.fold(0L) { mask, flag -> mask or flag.bit }
		if (wallTime == null || wallUncertainty == null || wal.qualityFlags and knownQualityMask != wal.qualityFlags ||
			wal.qualityConfidence?.let { confidence -> !confidence.isFinite() || confidence !in 0f..1f } == true
		) {
			return@withTransaction rejected(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		}
		val evidence = LocationDurableObservationEvidence(
			sourceEventId = eventId,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			walIntegrityIdentity = wal.integrityIdentity,
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = requireNotNull(wal.deliveryUnitIndex),
			deliveryUnitCount = requireNotNull(wal.deliveryUnitCount),
			capturedAuthority = authority,
			clockAuthority = LocationDurableClockAuthority(
				clockDomainId = wal.clockDomainId,
				observedElapsedRealtimeNanos = wal.observedElapsedNanos,
				receivedElapsedRealtimeNanos = wal.receivedElapsedNanos,
				observedWallTimeMs = wallTime,
				wallTimeUncertaintyMs = wallUncertainty,
			),
			payloadVersion = wal.payloadVersion,
			payload = payload,
			quality = sourceQualityFromStableFlags(wal.qualityFlags, wal.qualityConfidence),
			// Canonical Location WAL payload v1 has no mock bit. This false value describes that
			// exact format rather than inferring a later mutable platform state.
			isMock = false,
		)
		LocationWalAdapterResult.Evaluated(
			LocationQualifiedObservationQualifier.qualify(
				input = LocationObservationInput(
					origin = LocationObservationOrigin.PROVIDER_CALLBACK,
					outcome = LocationProviderOutcome.FIX,
					attemptedAuthority = authority,
					durableEvidence = evidence,
				),
				expectedAuthority = authority,
				currentDeletionAuthority = LocationDeletionAuthority(
					currentCollectedDataEpoch = evidenceState.collectedDataEpoch,
					retainedFromWallTimeMs = evidenceState.retainedFromMs,
				),
			),
		)
	}

	private fun decodeAndAuthenticateDelivery(
		delivery: List<SourceEventWalEntity>,
		target: SourceEventWalEntity,
	): List<DecodedLocationDeliveryUnit>? {
		val declaredCount = target.deliveryUnitCount ?: return null
		if (declaredCount !in 1..MAX_LOCATION_DELIVERY_UNITS || delivery.size != declaredCount ||
			delivery.mapNotNull(SourceEventWalEntity::deliveryUnitIndex) != delivery.indices.toList()
		) return null
		val firstSequence = delivery.first().sourceSequence
		return delivery.mapIndexed { index, row ->
			if (!row.hasQualifiedIntegrity() || row.deliveryUnitCount != declaredCount ||
				row.deliveryIdentity != target.deliveryIdentity || row.sourceKind != LOCATION_SOURCE ||
				row.sourceInstanceId != target.sourceInstanceId ||
				row.registrationGeneration != target.registrationGeneration ||
				row.physicalConfigurationFingerprint != target.physicalConfigurationFingerprint ||
				row.authorizationRevision != target.authorizationRevision ||
				row.authorizationFingerprint != target.authorizationFingerprint ||
				row.authorizationPurposeEligibilityMask != target.authorizationPurposeEligibilityMask ||
				row.configRevision != target.configRevision || row.planAttribution != target.planAttribution ||
				row.logicalTrackingId != target.logicalTrackingId || row.serviceRunId != target.serviceRunId ||
				row.sourcePolicyRevision != target.sourcePolicyRevision ||
				row.captureConsentEpoch != target.captureConsentEpoch ||
				row.sessionManifestRevision != target.sessionManifestRevision ||
				row.lifecycleLeaseGeneration != target.lifecycleLeaseGeneration ||
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
	}

	private suspend fun authorizationEndExclusive(
		registrationEnd: Long,
		authorization: SourceAuthorizationSnapshot,
		latest: SourceAuthorizationSnapshot?,
		registrationGeneration: Long,
	): Long? {
		if (latest == null || latest.authorizationRevision < authorization.authorizationRevision) return null
		if (latest.authorizationRevision == authorization.authorizationRevision) return registrationEnd
		val nextRevision = runCatching { Math.addExact(authorization.authorizationRevision, 1L) }.getOrNull()
			?: return null
		val next = database.sourceBrokerDao().authorizationRevision(
			LOCATION_SOURCE,
			registrationGeneration,
			nextRevision,
		).toAuthorizationSnapshotOrNull() ?: return null
		if (next.effectiveBootId != authorization.effectiveBootId ||
			next.effectiveElapsedRealtimeNanos < authorization.effectiveElapsedRealtimeNanos
		) return null
		return minOf(registrationEnd, next.effectiveElapsedRealtimeNanos)
	}

	private fun canonicalDeliveryBytes(units: List<DecodedLocationDeliveryUnit>): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(LOCATION_DELIVERY_MAGIC)
				output.writeInt(LOCATION_DELIVERY_VERSION)
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
					output.writeInt(java.lang.Float.floatToRawIntBits(payload.horizontalAccuracyMeters))
					output.writeOptionalDouble(payload.altitudeMeters)
					output.writeOptionalFloat(payload.verticalAccuracyMeters)
					output.writeOptionalFloat(payload.speedMetersPerSecond)
					output.writeOptionalFloat(payload.bearingDegrees)
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
		const val MAX_MANIFESTS_PER_RUN = 256
		const val MAX_MANIFEST_SOURCE_ROWS = 4_096
		const val LOCATION_DELIVERY_MAGIC = 0x4c4f4342
		const val LOCATION_DELIVERY_VERSION = 1
		const val DERIVED_WALL_UNCERTAINTY_MS = 1L
	}
}

private fun interval(startInclusiveNanos: Long, endExclusiveNanos: Long) =
	LocationProviderTimeInterval(startInclusiveNanos, endExclusiveNanos)

private fun LocationPlan.maximumLocationEvidenceAgeNanos(): Long {
	val maximumAgeMs = maxOf(requestedIntervalMs, maximumBatchDelayMs).coerceAtLeast(1L)
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

private fun rejected(reason: LocationWalAdapterRejection) = LocationWalAdapterResult.Rejected(reason)
