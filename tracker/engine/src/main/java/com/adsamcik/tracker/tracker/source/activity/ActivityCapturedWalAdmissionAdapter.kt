package com.adsamcik.tracker.tracker.source.activity

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalPayloadPreflightRow
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.ActivityWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.ActivityWalPayload
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.sourceQualityFromStableFlags
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DateTimeException
import java.time.ZoneId
import javax.inject.Inject

internal enum class ActivityCapturedWalAdmissionRejection {
	MISSING_EVENT,
	WRONG_SOURCE,
	CONTROL_ONLY,
	INCOMPLETE_CAPTURE_BINDING,
	WAL_INTEGRITY_MISMATCH,
	SOURCE_SEQUENCE_MISMATCH,
	DELIVERY_TOO_LARGE,
	MALFORMED_DELIVERY,
	DELIVERY_IDENTITY_MISMATCH,
	UNSUPPORTED_PAYLOAD,
	HISTORICAL_PLAN_MISMATCH,
	REGISTRATION_MISMATCH,
	AUTHORIZATION_MISMATCH,
	POLICY_MISMATCH,
	CONSENT_MISMATCH,
	SESSION_MISMATCH,
	MANIFEST_TIMELINE_UNVERIFIABLE,
	MANIFEST_MISMATCH,
	SEGMENT_MISMATCH,
	INVALID_STORED_ZONE,
	DESTINATION_OWNER_MISMATCH,
	DELETED_EVIDENCE,
	RETAINED_EVIDENCE,
	TEMPORAL_AUTHORITY_MISMATCH,
}

internal enum class ActivityCapturedWalAdmissionUnavailable {
	MISSING_IMMUTABLE_REGISTRATION_PLAN,
	UNSETTLED_FINITE_WINDOW,
	DELIVERY_ORDER_UNVERIFIABLE,
}

internal data class ActivityCapturedSettledWindow(
	val interval: ActivityProviderTimeInterval,
	val sessionSegmentId: Long,
	val storedZoneId: String,
	val manifestRevision: Long,
)

internal sealed interface ActivityCapturedWalAdmissionResult {
	data class Admitted(
		val observation: ActivityCapturedObservation,
		val acquisitionAuthority: ActivityCaptureAcquisitionAuthority,
		val settledWindow: ActivityCapturedSettledWindow,
		val deliveryIdentity: SourceDeliveryIdentity,
		val deliveryUnitIndex: Int,
		val deliveryUnitCount: Int,
	) : ActivityCapturedWalAdmissionResult

	data class ObservationRejected(
		val reason: ActivityCaptureAdmissionRejection,
	) : ActivityCapturedWalAdmissionResult

	data class Unavailable(
		val reason: ActivityCapturedWalAdmissionUnavailable,
	) : ActivityCapturedWalAdmissionResult

	data class Rejected(
		val reason: ActivityCapturedWalAdmissionRejection,
	) : ActivityCapturedWalAdmissionResult
}

/**
 * Dormant, read-only bridge from one retained Activity delivery to pure captured admission.
 *
 * This adapter deliberately stops before window coalescing or persistence. An immutable applied
 * registration-plan binding and a fully settled finite session/provider interval are required;
 * missing authority is reported rather than reconstructed from a mutable latest-plan pointer.
 */
internal class ActivityCapturedWalAdmissionAdapter @Inject constructor(
	private val database: AppDatabase,
	private val planCodec: SourcePlanCodec,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	suspend fun admit(eventId: SourceEventId): ActivityCapturedWalAdmissionResult =
		admit(eventId, requireCurrentDestinationOwner = true)

	/** Historical proof authenticates immutable fact provenance instead of mutable current ownership. */
	internal suspend fun admitHistorical(
		eventId: SourceEventId,
	): ActivityCapturedWalAdmissionResult =
		admit(eventId, requireCurrentDestinationOwner = false)

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun admit(
		eventId: SourceEventId,
		requireCurrentDestinationOwner: Boolean,
	): ActivityCapturedWalAdmissionResult =
		database.withTransaction transaction@{
			val walDao = database.sourceEventWalDao()
			val selectedPreflight = walDao.payloadPreflightByEventId(eventId.value)
				?: return@transaction rejected(ActivityCapturedWalAdmissionRejection.MISSING_EVENT)
			if (selectedPreflight.sourceKind != ACTIVITY_SOURCE) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.WRONG_SOURCE)
			}
			if (selectedPreflight.payloadBytes > MAX_ACTIVITY_PAYLOAD_BYTES) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE)
			}
			if (selectedPreflight.payloadBytes <= 0L) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY)
			}
			val selected = walDao.boundedPayloadByEventId(
				eventId = eventId.value,
				maximumPayloadBytes = MAX_ACTIVITY_PAYLOAD_BYTES,
			) ?: return@transaction rejected(ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY)
			if (!selectedPreflight.matches(selected)) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY)
			}
			if (!selected.hasQualifiedIntegrity()) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.WAL_INTEGRITY_MISMATCH)
			}
			// A valid control-only envelope has no captured product to decode.
			if (selected.authorizationPurposeEligibilityMask and SourceBrokerPurpose.ALL_MASK !=
				selected.authorizationPurposeEligibilityMask
			) {
				return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.INCOMPLETE_CAPTURE_BINDING,
			)
			}
			if (selected.authorizationPurposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.CONTROL_ONLY)
			}
			if (selected.sourceSequence !in 0..MAX_DURABLE_SOURCE_SEQUENCE) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.SOURCE_SEQUENCE_MISMATCH)
			}
			val deliveryIdentity = selected.deliveryIdentity?.let { identity ->
				runCatching { SourceDeliveryIdentity(identity) }.getOrNull()
			} ?: return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.INCOMPLETE_CAPTURE_BINDING,
			)
			val selectedUnitIndex = selected.deliveryUnitIndex
			val declaredUnitCount = selected.deliveryUnitCount
			if (selectedUnitIndex == null || declaredUnitCount == null || declaredUnitCount <= 0 ||
				selectedUnitIndex !in 0 until declaredUnitCount
			) {
				return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.INCOMPLETE_CAPTURE_BINDING,
			)
			}
			if (declaredUnitCount > MAX_DELIVERY_UNITS) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE)
			}

			val deliveryPreflight = walDao.deliveryPayloadPreflight(
				sourceKind = ACTIVITY_SOURCE,
				collectedDataEpoch = selected.capturedCollectedDataEpoch,
				clockDomainId = selected.clockDomainId,
				deliveryIdentity = deliveryIdentity.value,
				limit = MAX_DELIVERY_UNITS + 1,
			)
			if (deliveryPreflight.size > MAX_DELIVERY_UNITS ||
				deliveryPreflight.any { row -> row.payloadBytes > MAX_ACTIVITY_PAYLOAD_BYTES }
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE)
			}
			if (deliveryPreflight.size != declaredUnitCount ||
				deliveryPreflight.any { row -> row.payloadBytes <= 0L } ||
				deliveryPreflight.mapNotNull { row -> row.deliveryUnitIndex } !=
					deliveryPreflight.indices.toList() ||
				deliveryPreflight.any { row -> row.deliveryUnitCount != declaredUnitCount }
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY)
			}
			val deliveryRows = walDao.deliveryEventsWithBoundedPayload(
				sourceKind = ACTIVITY_SOURCE,
				collectedDataEpoch = selected.capturedCollectedDataEpoch,
				clockDomainId = selected.clockDomainId,
				deliveryIdentity = deliveryIdentity.value,
				maximumPayloadBytes = MAX_ACTIVITY_PAYLOAD_BYTES,
				limit = MAX_DELIVERY_UNITS + 1,
			)
			if (deliveryRows.size != deliveryPreflight.size) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY)
			}
			val decodedDelivery = DecodedActivityWalUnit.decodeCompleteDelivery(deliveryRows, selected)
				?: return@transaction rejected(ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY)
			val selectedUnit = decodedDelivery.getOrNull(selectedUnitIndex)
				?: return@transaction rejected(ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY)
			if (!selectedUnit.wal.hasSamePersistedValueAs(selected)) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY)
			}
			when (DecodedActivityWalUnit.canonicalDeliveryVerdict(decodedDelivery, deliveryIdentity)) {
				DeliveryIdentityVerdict.MATCH -> Unit
				DeliveryIdentityVerdict.MISMATCH -> return@transaction rejected(
					ActivityCapturedWalAdmissionRejection.DELIVERY_IDENTITY_MISMATCH,
				)
				DeliveryIdentityVerdict.UNVERIFIABLE -> return@transaction unavailable(
					ActivityCapturedWalAdmissionUnavailable.DELIVERY_ORDER_UNVERIFIABLE,
				)
			}

			val logicalTrackingId = selected.logicalTrackingId
			val serviceRunId = selected.serviceRunId
			val configurationRevision = selected.configRevision
			val physicalFingerprint = selected.physicalConfigurationFingerprint
			val authorizationRevision = selected.authorizationRevision
			val authorizationFingerprint = selected.authorizationFingerprint
			val sourcePolicyRevision = selected.sourcePolicyRevision
			val captureConsentEpoch = selected.captureConsentEpoch
			val manifestRevision = selected.sessionManifestRevision
			val leaseGeneration = selected.lifecycleLeaseGeneration
			// The complete saved capture reference tuple must be present before authority lookups.
			@Suppress("ComplexCondition")
			if (logicalTrackingId == null || serviceRunId == null || configurationRevision == null ||
				physicalFingerprint == null || authorizationRevision == null ||
				authorizationFingerprint == null || sourcePolicyRevision == null ||
				captureConsentEpoch == null || manifestRevision == null || leaseGeneration == null ||
				selected.planAttribution != PlanAttribution.CAPTURED_REGISTRATION.ordinal
			) {
				return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.INCOMPLETE_CAPTURE_BINDING,
			)
			}

			val evidenceState = database.sourceEvidenceStateDao().get()
				?: return@transaction rejected(ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE)
			if (evidenceState.collectedDataEpoch != selected.capturedCollectedDataEpoch ||
				decodedDelivery.any { unit ->
					unit.wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
				}
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE)
			}
			if (database.sourceDeletionFenceDao().contains(
					sourceKind = ACTIVITY_SOURCE,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
						sourceKind = ACTIVITY_SOURCE,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						logicalTrackingId = logicalTrackingId,
						serviceRunId = serviceRunId,
					),
				)
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE)
			}
			if (evidenceState.retainedFromMs?.let { retainedFromMs ->
				decodedDelivery.any { unit ->
					unit.wal.earliestPossibleWallTimeMs()?.let { it < retainedFromMs } ?: true
				}
			} == true) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.RETAINED_EVIDENCE)
			}

			val currentOwner = if (requireCurrentDestinationOwner) {
				database.sourceDestinationOwnerDao().get(
					SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
					SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
				)
			} else {
				null
			}
			if (requireCurrentDestinationOwner) {
				val currentBinding = currentOwner?.ownerGeneration
					?.let(SourceWriterGenerationContract::bindingGenerationForCanonicalOwner)
				if (currentOwner?.owner != SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS ||
					currentBinding == null ||
					SourceWriterGenerationContract.captureModeMaskForKnownBinding(currentBinding) == null
				) {
					return@transaction rejected(
					ActivityCapturedWalAdmissionRejection.DESTINATION_OWNER_MISMATCH,
				)
				}
			}

			val planDao = database.sourcePlanStateDao()
			val planHeader = planDao.revision(configurationRevision)
			val desiredPlan = planDao.desiredPlans(configurationRevision)
				.singleOrNull { plan -> plan.sourceKind == ACTIVITY_SOURCE }
			if (planHeader?.sourcePolicyRevision != sourcePolicyRevision || desiredPlan == null ||
				desiredPlan.revision != configurationRevision ||
				desiredPlan.payloadVersion !in setOf(
					SOURCE_PLAN_PAYLOAD_VERSION, SourcePlanCodec.ADAPTIVE_ACTIVITY_FORMAT_VERSION,
				)
			) {
				return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.HISTORICAL_PLAN_MISMATCH,
			)
			}
			val registrationPlan = database.activityCapturedFactDao().registrationPlanBinding(
				selected.sourceInstanceId,
				selected.registrationGeneration,
			) ?: return@transaction unavailable(
				ActivityCapturedWalAdmissionUnavailable.MISSING_IMMUTABLE_REGISTRATION_PLAN,
			)
			if (!registrationPlan.matchesCapturedActivityPlan(desiredPlan, selected)) {
				return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.HISTORICAL_PLAN_MISMATCH,
			)
			}

			val brokerDao = database.sourceBrokerDao()
			val registrationAuthentication = database.authenticateHistoricalProviderRegistration(
				sourceKind = ACTIVITY_SOURCE,
				registrationGeneration = selected.registrationGeneration,
				sourceInstanceId = selected.sourceInstanceId,
				clockDomainId = selected.clockDomainId,
				physicalConfigurationFingerprint = physicalFingerprint,
				collectedDataEpoch = selected.capturedCollectedDataEpoch,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				observedStartNanos = selected.observedElapsedNanos,
				observedEndNanos = selected.observedElapsedNanos,
				observedWallTimeMs = requireNotNull(selected.wallTimeMs),
				currentClockDomainId = bootClockDomainProvider.current(),
			)
			val authenticatedRegistration =
				registrationAuthentication as?
					ProviderRegistrationIntervalAuthentication.Authenticated
			if (authenticatedRegistration == null) {
				return@transaction rejected(
					ActivityCapturedWalAdmissionRejection.REGISTRATION_MISMATCH,
				)
			}
			val registration = authenticatedRegistration.registration
			val registrationStart = authenticatedRegistration.interval.startInclusiveNanos
			val registrationEnd = authenticatedRegistration.interval.endExclusiveNanos
			if (registration.captureCallbackBarrierAuthorizationRevision < authorizationRevision
			) {
				return@transaction unavailable(
				ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
			)
			}
			if (registration.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.REGISTRATION_MISMATCH)
			}

			val authorization = brokerDao.authorizationRevision(
				ACTIVITY_SOURCE,
				selected.registrationGeneration,
				authorizationRevision,
			).toAuthorizationSnapshotOrNull()
				?: return@transaction rejected(ActivityCapturedWalAdmissionRejection.AUTHORIZATION_MISMATCH)
			val observedAuthorization = brokerDao.authorizationAt(
				ACTIVITY_SOURCE,
				selected.registrationGeneration,
				selected.clockDomainId,
				selected.observedElapsedNanos,
			).toAuthorizationSnapshotOrNull()
			val demandIds = authorization.authorizedMembers.mapNotNull { member -> member.demandId }
			val demands = brokerDao.demandsByIds(demandIds)
			val recomputedAuthorization = runCatching {
				SourceBrokerAuthorization.rows(
					sourceKind = ACTIVITY_SOURCE,
					registrationGeneration = selected.registrationGeneration,
					authorizationRevision = authorizationRevision,
					demands = demands,
					effectiveBootId = authorization.effectiveBootId,
					effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
				)
			}.getOrNull()
			val captureMember = authorization.authorizedMembers.singleOrNull { member ->
				member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible
			}
			// The recomputed member set and selected capture member must match the saved authorization tuple.
			@Suppress("ComplexCondition")
			if (authorization.isDenied || observedAuthorization?.sameAuthorityAs(authorization) != true ||
				demandIds.size != authorization.authorizedMembers.size ||
				demandIds.distinct().size != demandIds.size || demands.size != demandIds.size ||
				recomputedAuthorization?.sortedBy { row -> row.memberId } !=
					authorization.members.sortedBy { row -> row.memberId } ||
				authorization.authorizationFingerprint != authorizationFingerprint ||
				authorization.purposeEligibilityMask != selected.authorizationPurposeEligibilityMask ||
				authorization.effectiveBootId != selected.clockDomainId || captureMember == null ||
				captureMember.logicalTrackingId != logicalTrackingId ||
				captureMember.serviceRunId != serviceRunId ||
				captureMember.manifestRevision != manifestRevision ||
				captureMember.lifecycleLeaseGeneration != leaseGeneration ||
				captureMember.sourcePolicyRevision != sourcePolicyRevision ||
				captureMember.consentEpoch != captureConsentEpoch
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.AUTHORIZATION_MISMATCH)
			}
			val nextAuthorizationBoundary = database.activityCapturedFactDao()
				.nextAuthorizationBoundary(
					sourceKind = ACTIVITY_SOURCE,
					registrationGeneration = selected.registrationGeneration,
					bootId = selected.clockDomainId,
					effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
					authorizationRevision = authorization.authorizationRevision,
				)
			val authorizationEnd = nextAuthorizationBoundary ?: Long.MAX_VALUE
			if (authorizationEnd <= authorization.effectiveElapsedRealtimeNanos) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.AUTHORIZATION_MISMATCH)
			}

			val policyDao = database.sourcePolicyDao()
			val policyAuthority = policyDao.authority()
			val policy = policyDao.policyAtRevision(sourcePolicyRevision, ACTIVITY_SOURCE)
			// Saved choices survive reboot; elapsed boundaries are comparable only in the same boot.
			@Suppress("ComplexCondition")
			if (policyAuthority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
				policyAuthority.currentPolicyRevision < sourcePolicyRevision || policy == null ||
				!policy.enabled || !policy.capturePersistenceEligible ||
				policy.captureConsentEpoch != captureConsentEpoch ||
				(policy.effectiveBootId == selected.clockDomainId &&
					policy.effectiveElapsedRealtimeNanos > selected.observedElapsedNanos)
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.POLICY_MISMATCH)
			}
			val consent = policyDao.consentEpoch(
				ACTIVITY_SOURCE,
				SourceBrokerPurpose.SESSION_CAPTURE,
				captureConsentEpoch,
			)
			// Compare the historical consent eligibility and original boot/time boundary together.
			@Suppress("ComplexCondition")
			if (consent == null || !consent.eligible || !consent.persistenceEligible ||
				consent.policyRevision > sourcePolicyRevision ||
				(consent.effectiveBootId == selected.clockDomainId &&
					consent.effectiveElapsedRealtimeNanos > selected.observedElapsedNanos)
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.CONSENT_MISMATCH)
			}

			val sessionDao = database.sourceSessionDao()
			val session = sessionDao.session(logicalTrackingId)
			val run = sessionDao.serviceRun(serviceRunId)
			// Compare the saved session/run/lease ownership and original run boundary together.
			@Suppress("ComplexCondition")
			if (session == null || run == null || run.logicalTrackingId != logicalTrackingId ||
				!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, leaseGeneration) ||
				session.logicalTrackingId != logicalTrackingId ||
				session.lifecycleLeaseGeneration < leaseGeneration ||
				run.startedElapsedNanos > selected.observedElapsedNanos
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH)
			}
			val maximumDeliveryOrdinal = decodedDelivery.maxOf { unit -> unit.wal.admissionOrdinal }
			val maximumDeliverySequence = decodedDelivery.maxOf { unit -> unit.wal.sourceSequence }
			val interruptedTerminalRun = session.isInterruptedTerminalRun(run)
			if (!session.hasValidLifecycleShape(maximumDeliveryOrdinal, run) ||
				!run.hasValidLifecycleShape() || !run.hasValidRelationshipTo(session)
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH)
			}
			val completeness = sessionDao.completenessForServiceRun(logicalTrackingId, serviceRunId)
				.singleOrNull { row ->
					row.sourceKind == ACTIVITY_SOURCE &&
						row.sourceInstanceId == selected.sourceInstanceId &&
						row.registrationGeneration == selected.registrationGeneration
				}
			val completenessSettled = completeness?.isExactSettledActivityTail(
					maximumDeliveryOrdinal,
					maximumDeliverySequence,
				) == true
			// An interrupted old stop can miss its acknowledgement after callback admission was
			// already sealed. The exact retired registration seal proves only the saved app tail;
			// it does not turn the provider's unobservable completeness into complete coverage.
			val sealedTail = if (completenessSettled && !interruptedTerminalRun) {
				null
			} else {
				brokerDao.captureAdmissionBarrier(ACTIVITY_SOURCE, selected.registrationGeneration)
			}
			val sealedTailSettled = sealedTail.hasSettledActivityTail(
				registration, selected, maximumDeliveryOrdinal, maximumDeliverySequence,
			)
			if (!completenessSettled && !sealedTailSettled) {
				return@transaction unavailable(
					ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
				)
			}
			val terminalWindow = session.state in ACTIVITY_CAPTURED_TERMINAL_STATES &&
				run.state in ACTIVITY_CAPTURED_TERMINAL_STATES
			val currentIntent = session.currentIntentRevision?.let { intentRevision ->
				sessionDao.lifecycleIntent(logicalTrackingId, intentRevision)
			}
			val settledStoppingWindow = session.isExactSettledStoppingWindow(
				run,
				currentIntent,
				maximumDeliveryOrdinal,
			)
			if (!terminalWindow && !settledStoppingWindow) {
				return@transaction unavailable(
				ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
			)
			}
			val savedCutoff = database.savedSessionCutoff(session)
			val sessionEnd = session.capturedActivitySessionEnd(
				savedCutoff, selected.clockDomainId, interruptedTerminalRun && sealedTailSettled,
			)
			val finalAdmissionOrdinal = session.finalAdmissionOrdinal
				?: sealedTail?.lastAdmissionOrdinal?.takeIf { interruptedTerminalRun && sealedTailSettled }
			// Process death may leave only a wall cutoff. Keep that uncertainty instead of borrowing
			// another boot's elapsed clock; the retired registration seal bounds the saved app tail.
			if (interruptedTerminalRun && session.cutoffElapsedNanos == null && decodedDelivery.any { unit ->
				unit.wal.latestPossibleWallTimeMs()?.let { it >= requireNotNull(session.cutoffAtMs) } != false
			}) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH)
			}
			if (sessionEnd == null || finalAdmissionOrdinal == null ||
				maximumDeliveryOrdinal > finalAdmissionOrdinal
			) {
				return@transaction unavailable(
				ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
			)
			}
			if (sessionEnd <= run.startedElapsedNanos || selected.observedElapsedNanos >= sessionEnd) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH)
			}

			val manifests = sessionDao.manifestsForServiceRun(serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
			if (manifests.size > MAX_MANIFESTS_PER_RUN ||
				!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests)
			) {
				return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.MANIFEST_TIMELINE_UNVERIFIABLE,
			)
			}
			val allManifestSources = database.trackingHistoryReadDao().manifestSources(
				listOf(serviceRunId),
				MAX_MANIFEST_SOURCE_ROWS + 1,
			)
			if (allManifestSources.size > MAX_MANIFEST_SOURCE_ROWS || manifests.any { manifest ->
				!SessionManifestIntegrity.verify(
					manifest,
					allManifestSources.filter { source -> source.belongsTo(manifest) },
				)
			}) {
				return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.MANIFEST_TIMELINE_UNVERIFIABLE,
			)
			}
			val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
			if (manifestIndex < 0) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MANIFEST_MISMATCH)
			}
			val manifest = manifests[manifestIndex]
			val manifestSources = allManifestSources.filter { source -> source.belongsTo(manifest) }
			if (manifestSources.any { source ->
				SourceKind.entries.none { kind -> kind.stableCode == source.sourceKind } ||
					source.purpose !in SessionManifestPurposeCode.ALL
			}) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MANIFEST_MISMATCH)
			}
			val activityBinding = manifestSources.singleOrNull { source ->
				source.sourceKind == ACTIVITY_SOURCE &&
					source.purpose == SourceBrokerPurpose.SESSION_CAPTURE
			} ?: return@transaction rejected(ActivityCapturedWalAdmissionRejection.MANIFEST_MISMATCH)
			// Compare this saved manifest to its captured session, plan, consent and QoS binding together.
			@Suppress("ComplexCondition")
			if (manifest.logicalTrackingId != logicalTrackingId ||
				manifest.serviceRunId != serviceRunId ||
				manifest.sessionMode != session.sessionMode ||
				manifest.sourcePolicyRevision != sourcePolicyRevision ||
				manifest.acquisitionPlanRevision != configurationRevision ||
				manifest.effectiveBootId != selected.clockDomainId ||
				manifest.effectiveElapsedRealtimeNanos > selected.observedElapsedNanos ||
				!activityBinding.isExactCapturedActivityBinding(captureConsentEpoch, manifest.sessionMode) ||
				activityBinding.qosCode != policy.qosCode
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MANIFEST_MISMATCH)
			}
			if (requireCurrentDestinationOwner &&
				currentOwner?.ownerGeneration != activityBinding.writerOwnerGeneration
			) {
				return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.DESTINATION_OWNER_MISMATCH,
			)
			}
			val nextManifestStart = manifests.getOrNull(manifestIndex + 1)
				?.effectiveElapsedRealtimeNanos ?: sessionEnd
			if (selected.observedElapsedNanos >= nextManifestStart) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.MANIFEST_MISMATCH)
			}
			try {
				ZoneId.of(manifest.zoneId)
			} catch (_: DateTimeException) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.INVALID_STORED_ZONE)
			}
			val segmentId = run.sessionSegmentId
				?: return@transaction rejected(ActivityCapturedWalAdmissionRejection.SEGMENT_MISMATCH)
			val segment = database.sessionSegmentDao().getById(segmentId)
			if (segment == null || segment.logicalTrackingId != logicalTrackingId ||
				segment.serviceRunId != serviceRunId
			) {
				return@transaction rejected(ActivityCapturedWalAdmissionRejection.SEGMENT_MISMATCH)
			}

			val temporalAuthority = runCatching {
				ActivityCaptureTemporalAuthority(
					providerAcceptance = ActivityProviderTimeInterval(registrationStart, registrationEnd),
					authorizationEffect = ActivityProviderTimeInterval(
						authorization.effectiveElapsedRealtimeNanos,
						authorizationEnd,
					),
					sessionRunEffect = ActivityProviderTimeInterval(run.startedElapsedNanos, sessionEnd),
				)
			}.getOrNull() ?: return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.TEMPORAL_AUTHORITY_MISMATCH,
			)
			val captureAuthority = runCatching {
				selected.toCapturedActivityAuthority(temporalAuthority)
			}.getOrNull() ?: return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.TEMPORAL_AUTHORITY_MISMATCH,
			)
			val acquisitionAuthority = runCatching {
				captureAuthority.withHistoricalActivityConfiguration(desiredPlan, planCodec)
			}.getOrNull() ?: return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.HISTORICAL_PLAN_MISMATCH,
			)
			val settledStart = maxOf(
				registrationStart,
				authorization.effectiveElapsedRealtimeNanos,
				run.startedElapsedNanos,
				manifest.effectiveElapsedRealtimeNanos,
				policy.effectiveElapsedRealtimeNanos.takeIf { policy.effectiveBootId == selected.clockDomainId } ?: 0L,
				consent.effectiveElapsedRealtimeNanos.takeIf { consent.effectiveBootId == selected.clockDomainId } ?: 0L,
				registrationPlan.appliedAtElapsedRealtimeNanos,
			)
			val settledEnd = minOf(registrationEnd, authorizationEnd, sessionEnd, nextManifestStart)
			val settledInterval = runCatching {
				ActivityProviderTimeInterval(settledStart, settledEnd)
			}.getOrNull() ?: return@transaction rejected(
				ActivityCapturedWalAdmissionRejection.TEMPORAL_AUTHORITY_MISMATCH,
			)
			if (selected.observedElapsedNanos !in settledInterval) {
				return@transaction rejected(
					ActivityCapturedWalAdmissionRejection.TEMPORAL_AUTHORITY_MISMATCH,
				)
			}
			val admittedEvent = selectedUnit.toAdmittedEvent()
			return@transaction when (val admission = ActivityCapturedObservationAdmission.admit(
				admittedEvent,
				acquisitionAuthority,
			)) {
				is ActivityCaptureAdmissionResult.Rejected ->
					ActivityCapturedWalAdmissionResult.ObservationRejected(admission.reason)
				is ActivityCaptureAdmissionResult.Captured -> ActivityCapturedWalAdmissionResult.Admitted(
					observation = admission.observation,
					acquisitionAuthority = acquisitionAuthority,
					settledWindow = ActivityCapturedSettledWindow(
						interval = settledInterval,
						sessionSegmentId = segmentId,
						storedZoneId = manifest.zoneId,
						manifestRevision = manifest.manifestRevision,
					),
					deliveryIdentity = deliveryIdentity,
					deliveryUnitIndex = selectedUnitIndex,
					deliveryUnitCount = declaredUnitCount,
				)
			}
		}
}

private fun rejected(reason: ActivityCapturedWalAdmissionRejection) =
	ActivityCapturedWalAdmissionResult.Rejected(reason)

private fun unavailable(reason: ActivityCapturedWalAdmissionUnavailable) =
	ActivityCapturedWalAdmissionResult.Unavailable(reason)

private fun SourceEventWalEntity.hasSamePersistedValueAs(other: SourceEventWalEntity): Boolean =
	this == other.copy(payload = payload) && payload.contentEquals(other.payload)

private fun SourceEventWalPayloadPreflightRow.matches(wal: SourceEventWalEntity): Boolean =
	eventId == wal.eventId && sourceKind == wal.sourceKind &&
	capturedCollectedDataEpoch == wal.capturedCollectedDataEpoch &&
	clockDomainId == wal.clockDomainId && deliveryIdentity == wal.deliveryIdentity &&
	deliveryUnitIndex == wal.deliveryUnitIndex && deliveryUnitCount == wal.deliveryUnitCount &&
	payloadBytes == wal.payload.size.toLong()

// Compares the complete immutable delivery-authority tuple without consulting live state.
@Suppress("CyclomaticComplexMethod")
private fun SourceEventWalEntity.hasSameDeliveryAuthorityAs(
	other: SourceEventWalEntity,
): Boolean = logicalTrackingId == other.logicalTrackingId &&
	serviceRunId == other.serviceRunId && sourceKind == other.sourceKind &&
	sourceInstanceId == other.sourceInstanceId &&
	registrationGeneration == other.registrationGeneration &&
	physicalConfigurationFingerprint == other.physicalConfigurationFingerprint &&
	authorizationRevision == other.authorizationRevision &&
	authorizationPurposeEligibilityMask == other.authorizationPurposeEligibilityMask &&
	authorizationFingerprint == other.authorizationFingerprint &&
	configRevision == other.configRevision && planAttribution == other.planAttribution &&
	clockDomainId == other.clockDomainId && receivedElapsedNanos == other.receivedElapsedNanos &&
	capturedCollectedDataEpoch == other.capturedCollectedDataEpoch &&
	sourcePolicyRevision == other.sourcePolicyRevision &&
	captureConsentEpoch == other.captureConsentEpoch &&
	sessionManifestRevision == other.sessionManifestRevision &&
	lifecycleLeaseGeneration == other.lifecycleLeaseGeneration

private fun SourceEventWalEntity.earliestPossibleWallTimeMs(): Long? {
	val wall = wallTimeMs ?: return null
	val uncertainty = wallTimeUncertaintyMs ?: return null
	return if (uncertainty >= wall) {
		0L
	} else {
		wall - uncertainty
	}
}


private fun SessionManifestSourceEntity.belongsTo(
	manifest: com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity,
): Boolean = logicalTrackingId == manifest.logicalTrackingId &&
	manifestRevision == manifest.manifestRevision

private fun SessionManifestSourceEntity.isExactCapturedActivityBinding(
	consentEpoch: Long,
	sessionMode: String,
): Boolean {
	val binding = writerBindingGeneration ?: return false
	val supportedModes = SourceWriterGenerationContract.captureModeMaskForKnownBinding(binding)
		?: return false
	val requestedMode = when (sessionMode) {
		"MANUAL" -> 1L
		"AUTOMATIC" -> 2L
		else -> return false
	}
	return persistenceEligible && this.consentEpoch == consentEpoch &&
		hasExactCapturedActivityWriter(binding) &&
		supportedModes and requestedMode != 0L
}

// Complete saved destination/owner/projection tuple for the already resolved binding generation.
@Suppress("ComplexCondition")
private fun SessionManifestSourceEntity.hasExactCapturedActivityWriter(binding: Long): Boolean =
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
	writerOwnerGeneration == SourceWriterGenerationContract.canonicalOwnerGeneration(binding) &&
	writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID &&
	writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION

private fun com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot.sameAuthorityAs(
	other: com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot,
): Boolean = authorizationRevision == other.authorizationRevision &&
	authorizationFingerprint == other.authorizationFingerprint &&
	purposeEligibilityMask == other.purposeEligibilityMask &&
	effectiveBootId == other.effectiveBootId &&
	effectiveElapsedRealtimeNanos == other.effectiveElapsedRealtimeNanos &&
	members.sortedBy { row -> row.memberId } == other.members.sortedBy { row -> row.memberId }

private data class DecodedActivityWalUnit(
	val wal: SourceEventWalEntity,
	val payload: SourcePayload,
) {
	val providerTime: Long = payload.providerTimeOrNull() ?: error("Activity provider time missing")
	val kindCode: Int = if (payload is ActivityRecognitionPayload) {
		KIND_RECOGNITION
	} else {
		KIND_TRANSITION
	}
	val activityType: Int = when (payload) {
		is ActivityRecognitionPayload -> payload.activityType
		is ActivityTransitionPayload -> payload.activityType
		else -> error("Unsupported Activity payload")
	}
	val detailCode: Int = when (payload) {
		is ActivityRecognitionPayload -> payload.confidencePercent
		is ActivityTransitionPayload -> payload.transitionType
		else -> error("Unsupported Activity payload")
	}
	val canonicalKey = ActivityCanonicalKey(providerTime, kindCode, activityType, detailCode)

	fun toAdmittedEvent(): AdmittedSourceEvent<out SourcePayload> =
		AdmittedSourceEvent(
			eventId = SourceEventId(wal.eventId),
			admissionOrdinal = wal.admissionOrdinal,
			evidence = com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate(
				providerDedupKey = wal.providerDedupKey,
				logicalTrackingId = wal.logicalTrackingId?.let(::LogicalTrackingId),
				serviceRunId = wal.serviceRunId?.let(::ServiceRunId),
				source = SourceKind.ACTIVITY,
				sourceInstanceId = SourceInstanceId(wal.sourceInstanceId),
				registrationGeneration = wal.registrationGeneration,
				physicalConfigurationFingerprint = wal.physicalConfigurationFingerprint,
				authorizationRevision = wal.authorizationRevision,
				registrationPurposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
				registrationEligibilityFingerprint = wal.authorizationFingerprint,
				sourceSequence = wal.sourceSequence,
				configRevision = wal.configRevision,
				planAttribution = PlanAttribution.entries[wal.planAttribution],
				clockDomainId = wal.clockDomainId,
				observedElapsedRealtimeNanos = wal.observedElapsedNanos,
				receivedElapsedRealtimeNanos = wal.receivedElapsedNanos,
				wallTimeMs = wal.wallTimeMs,
				wallTimeUncertaintyMs = wal.wallTimeUncertaintyMs,
				capturedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
				activityAutomationEpoch = wal.activityAutomationEpoch,
				sourcePolicyRevision = wal.sourcePolicyRevision,
				captureConsentEpoch = wal.captureConsentEpoch,
				sessionManifestRevision = wal.sessionManifestRevision,
				lifecycleLeaseGeneration = wal.lifecycleLeaseGeneration,
				acquiredAtMs = wal.acquiredAtMs,
				quality = sourceQualityFromStableFlags(wal.qualityFlags, wal.qualityConfidence),
				payloadVersion = wal.payloadVersion,
				payload = payload,
			),
		)

	companion object {
		@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
		fun decodeCompleteDelivery(
			rows: List<SourceEventWalEntity>,
			selected: SourceEventWalEntity,
		): List<DecodedActivityWalUnit>? {
			val declaredCount = selected.deliveryUnitCount ?: return null
			if (rows.size != declaredCount || rows.mapNotNull { it.deliveryUnitIndex } != rows.indices.toList()) {
				return null
			}
			val firstSequence = rows.firstOrNull()?.sourceSequence ?: return null
			return rows.mapIndexed { index, wal ->
				decodeDeliveryUnit(wal, selected, index, declaredCount, firstSequence) ?: return null
			}
		}

		private fun decodeDeliveryUnit(
			wal: SourceEventWalEntity,
			selected: SourceEventWalEntity,
			index: Int,
			declaredCount: Int,
			firstSequence: Long,
		): DecodedActivityWalUnit? {
			val expectedSequence = runCatching {
				Math.addExact(firstSequence, index.toLong())
			}.getOrNull() ?: return null
			if (!wal.matchesDeliveryUnit(selected, index, declaredCount, expectedSequence)) {
				return null
			}
			val authenticated = ActivityWalCarrierAuthenticator.authenticate(wal)
				as? ActivityWalCarrierAuthentication.Authenticated ?: return null
			val payload = authenticated.carrier.payload.toSourcePayload()
			return DecodedActivityWalUnit(wal, payload)
		}

		// Complete saved delivery identity, authority and sequence correspondence before carrier decoding.
		@Suppress("ComplexCondition")
		private fun SourceEventWalEntity.matchesDeliveryUnit(
			selected: SourceEventWalEntity,
			index: Int,
			declaredCount: Int,
			expectedSequence: Long,
		): Boolean = sourceKind == ACTIVITY_SOURCE && deliveryIdentity == selected.deliveryIdentity &&
			deliveryUnitCount == declaredCount && deliveryUnitIndex == index &&
			hasSameDeliveryAuthorityAs(selected) && sourceSequence in 0..MAX_DURABLE_SOURCE_SEQUENCE &&
			sourceSequence == expectedSequence

		fun canonicalDeliveryVerdict(
			units: List<DecodedActivityWalUnit>,
			expectedIdentity: SourceDeliveryIdentity,
		): DeliveryIdentityVerdict {
			if (units.zipWithNext().any { (left, right) -> left.canonicalKey > right.canonicalKey }) {
				return DeliveryIdentityVerdict.MISMATCH
			}

			val sameTimeTransitions = units.indices
				.filter { units[it].payload is ActivityTransitionPayload }
				.groupBy { units[it].providerTime }
			val providerOrders = IntArray(units.size)
			val ambiguousGroups = mutableListOf<List<Int>>()
			var candidateCount = 1L
			for (group in sameTimeTransitions.values) {
				val semanticKeys = group.map { units[it].canonicalKey }
				if (semanticKeys.distinct().size == 1) {
					group.forEachIndexed { order, unitIndex -> providerOrders[unitIndex] = order }
				} else {
					val permutations = boundedFactorial(group.size, MAX_DELIVERY_ORDER_CANDIDATES)
						?: return DeliveryIdentityVerdict.UNVERIFIABLE
					if (candidateCount > MAX_DELIVERY_ORDER_CANDIDATES / permutations) {
						return DeliveryIdentityVerdict.UNVERIFIABLE
					}
					candidateCount *= permutations
					ambiguousGroups += group
				}
			}
			fun matchesAt(groupIndex: Int): Boolean {
				if (groupIndex == ambiguousGroups.size) {
					return sourceDeliveryIdentity(canonicalDeliveryBytes(units, providerOrders)) ==
						expectedIdentity
				}
				val group = ambiguousGroups[groupIndex]
				return permutations(group.size).any { permutation ->
					group.forEachIndexed { index, unitIndex ->
						providerOrders[unitIndex] = permutation[index]
					}
					matchesAt(groupIndex + 1)
				}
			}
			return if (matchesAt(0)) {
				DeliveryIdentityVerdict.MATCH
			} else {
				DeliveryIdentityVerdict.MISMATCH
			}
		}

		private fun canonicalDeliveryBytes(
			units: List<DecodedActivityWalUnit>,
			providerOrders: IntArray,
		): ByteArray = ByteBuffer.allocate(DELIVERY_HEADER_BYTES + units.size * DELIVERY_EVENT_BYTES)
			.order(ByteOrder.BIG_ENDIAN)
			.putInt(ACTIVITY_DELIVERY_MAGIC)
			.putInt(ACTIVITY_DELIVERY_VERSION)
			.putInt(units.size)
			.apply {
				units.forEachIndexed { index, unit ->
					putLong(unit.providerTime)
					putInt(unit.kindCode)
					putInt(unit.activityType)
					putInt(unit.detailCode)
					putInt(providerOrders[index])
				}
			}
			.array()

		private fun permutations(size: Int): Sequence<IntArray> = sequence {
			val values = IntArray(size) { it }
			suspend fun SequenceScope<IntArray>.emitFrom(index: Int) {
				if (index == values.size) {
					yield(values.copyOf())
					return
				}
				for (candidate in index until values.size) {
					val held = values[index]
					values[index] = values[candidate]
					values[candidate] = held
					emitFrom(index + 1)
					values[candidate] = values[index]
					values[index] = held
				}
			}
			emitFrom(0)
		}

		private fun boundedFactorial(size: Int, limit: Long): Long? {
			var result = 1L
			for (factor in 2..size) {
				if (result > limit / factor) {
					return null
				}
				result *= factor
			}
			return result
		}
	}
}

private data class ActivityCanonicalKey(
	val providerTime: Long,
	val kindCode: Int,
	val activityType: Int,
	val detailCode: Int,
) : Comparable<ActivityCanonicalKey> {
	override fun compareTo(other: ActivityCanonicalKey): Int = compareValuesBy(
		this,
		other,
		ActivityCanonicalKey::providerTime,
		ActivityCanonicalKey::kindCode,
		ActivityCanonicalKey::activityType,
		ActivityCanonicalKey::detailCode,
	)
}

private enum class DeliveryIdentityVerdict { MATCH, MISMATCH, UNVERIFIABLE }

private const val ACTIVITY_SOURCE = 2
private const val SOURCE_PLAN_PAYLOAD_VERSION = 1
private const val MAX_DELIVERY_UNITS = 256
private const val MAX_ACTIVITY_PAYLOAD_BYTES = 21
private const val MAX_MANIFESTS_PER_RUN = 64
private const val MAX_MANIFEST_SOURCE_ROWS = 1_024
private const val MAX_DELIVERY_ORDER_CANDIDATES = 4_096L
private const val MAX_DURABLE_SOURCE_SEQUENCE = Long.MAX_VALUE - 1L
private const val ACTIVITY_DELIVERY_MAGIC = 0x41435456
private const val ACTIVITY_DELIVERY_VERSION = 2
private const val DELIVERY_HEADER_BYTES = Int.SIZE_BYTES * 3
private const val DELIVERY_EVENT_BYTES = Long.SIZE_BYTES + Int.SIZE_BYTES * 4
private const val KIND_RECOGNITION = 0
private const val KIND_TRANSITION = 1

private fun ActivityWalPayload.toSourcePayload(): SourcePayload = when (this) {
	is ActivityWalPayload.Transition -> ActivityTransitionPayload(
		activityType = activityType,
		transitionType = transitionType,
		providerElapsedRealtimeNanos = providerElapsedRealtimeNanos,
	)
	is ActivityWalPayload.Recognition -> ActivityRecognitionPayload(
		activityType = activityType,
		confidencePercent = confidencePercent,
		providerElapsedRealtimeNanos = providerElapsedRealtimeNanos,
	)
}

private fun SourcePayload.providerTimeOrNull(): Long? = when (this) {
	is ActivityRecognitionPayload -> providerElapsedRealtimeNanos
	is ActivityTransitionPayload -> providerElapsedRealtimeNanos
	else -> null
}
