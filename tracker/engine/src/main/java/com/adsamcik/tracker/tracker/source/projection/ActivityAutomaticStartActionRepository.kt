package com.adsamcik.tracker.tracker.source.projection

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.tracker.api.ActivityAutomationDeliveryEnvelope
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.source.hasAuthoritativeConsentReference
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton

internal data class ActivityAutomaticStartReservation(
	val trigger: AutomaticTrackingStartTrigger,
	val effectStableId: String,
	val evidence: ActivityAutomationDeliveryEnvelope,
	val controlConsentEpoch: Long,
	val reservedAtMs: Long,
) {
	init {
		require(evidence.automationEpoch == trigger.automationEpoch) {
			"Activity evidence and automatic trigger epochs must match"
		}
	}
}

internal sealed interface ActivityAutomaticStartReserveResult {
	data class Reserved(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartReserveResult

	data class Existing(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartReserveResult

	data class ConflictingCurrent(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartReserveResult

	data class Rejected(val reason: String) : ActivityAutomaticStartReserveResult
}

internal sealed interface ActivityAutomaticStartRequestAuthorization {
	data class Authorized(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartRequestAuthorization

	data class AlreadyRequested(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartRequestAuthorization

	data class Rejected(val reason: String) : ActivityAutomaticStartRequestAuthorization
}

internal sealed interface ActivityAutomaticStartAcceptance {
	data class Accepted(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartAcceptance

	data class Pending(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartAcceptance

	data class Terminal(val reason: String) : ActivityAutomaticStartAcceptance
	data object Missing : ActivityAutomaticStartAcceptance
}

/** Result consumed by TrackerService before it creates any logical session state. */
internal sealed interface ActivityAutomaticStartServiceValidation {
	data class Valid(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartServiceValidation

	data class LifecycleIntentAlreadyAccepted(val action: ActivityAutomaticStartActionEntity) :
		ActivityAutomaticStartServiceValidation

	data class Rejected(val reason: String) : ActivityAutomaticStartServiceValidation
}

/**
 * Activity-specific durable handshake around the one non-replayable Transition callback start.
 *
 * A normal `startForegroundService()` return is intentionally absent from this repository's
 * acceptance vocabulary. It means only that Android did not reject the enqueue synchronously.
 * Completion requires a matching `session_lifecycle_intent_version` row.
 */
@Singleton
class ActivityAutomaticStartActionRepository @Inject constructor(
	private val database: AppDatabase,
	private val startupGate: TrackingStartupGate,
) {
	internal suspend fun reserve(
		request: ActivityAutomaticStartReservation,
	): ActivityAutomaticStartReserveResult {
		val startupGeneration = startupGate.currentGeneration
		if (!startupGate.isReady) {
			return ActivityAutomaticStartReserveResult.Rejected("TRACKING_STARTUP_NOT_READY")
		}
		val result = database.withTransaction {
			val trigger = request.trigger
			if (trigger.startContext != AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK ||
				trigger.intendedCaptureSourceMask <= 0L ||
				trigger.intendedForegroundServiceTypeMask < 0L
			) {
				return@withTransaction ActivityAutomaticStartReserveResult.Rejected(
					"INVALID_ACTIVITY_TRANSITION_START_ENVELOPE",
				)
			}
			val evidenceState = database.sourceEvidenceStateDao().get()
			if (evidenceState?.collectedDataEpoch != trigger.collectedDataEpoch) {
				return@withTransaction ActivityAutomaticStartReserveResult.Rejected(
					"STALE_COLLECTED_DATA_EPOCH",
				)
			}
			val action = request.toEntity()
			currentControlAuthorityFailure(action)?.let { reason ->
				return@withTransaction ActivityAutomaticStartReserveResult.Rejected(reason)
			}
			val current = database.activityAutomaticStartActionDao().current()
			if (current == null) {
				check(database.activityAutomaticStartActionDao().insertIfSlotFree(action) == 1L)
				return@withTransaction ActivityAutomaticStartReserveResult.Reserved(action)
			}
			if (current.triggerId == action.triggerId) {
				return@withTransaction if (current.matches(action)) {
					ActivityAutomaticStartReserveResult.Existing(current)
				} else {
					ActivityAutomaticStartReserveResult.Rejected("TRIGGER_IDENTITY_COLLISION")
				}
			}

			val reusable = when (current.status) {
				ActivityAutomaticStartActionEntity.STATUS_TERMINAL -> true
				ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED ->
					current.acceptedLogicalTrackingId
						?.let { database.sourceSessionDao().session(it) }
						?.state
						?.let(::isTerminalSessionState) == true
				else -> false
			}
			if (!reusable) {
				return@withTransaction ActivityAutomaticStartReserveResult.ConflictingCurrent(current)
			}
			check(
				database.activityAutomaticStartActionDao().clearCompletedSlot(
					current.triggerId,
					current.collectedDataEpoch,
				) == 1,
			) { "Activity automatic-start slot changed while being reused" }
			check(database.activityAutomaticStartActionDao().insertIfSlotFree(action) == 1L)
			ActivityAutomaticStartReserveResult.Reserved(action)
		}
		if (!startupGate.isReady || startupGate.currentGeneration != startupGeneration) {
			if (result is ActivityAutomaticStartReserveResult.Reserved ||
				result is ActivityAutomaticStartReserveResult.Existing
			) {
				markTerminalExact(
					request.trigger,
					request.reservedAtMs,
					"TRACKING_STARTUP_GENERATION_CLOSED_DURING_RESERVATION",
				)
			}
			return ActivityAutomaticStartReserveResult.Rejected(
				"TRACKING_STARTUP_GENERATION_CLOSED_DURING_RESERVATION",
			)
		}
		return result
	}

	/**
	 * Last durable CAS before the Android call. START_REQUESTED is committed first so a service that
	 * arrives immediately can validate the exact envelope. A later cold drain never reissues it.
	 */
	internal suspend fun authorizeExternalStart(
		trigger: AutomaticTrackingStartTrigger,
		requestedAtMs: Long,
	): ActivityAutomaticStartRequestAuthorization {
		val startupGeneration = startupGate.currentGeneration
		if (!startupGate.isReady) {
			return ActivityAutomaticStartRequestAuthorization.Rejected("TRACKING_STARTUP_NOT_READY")
		}
		val result = database.withTransaction {
			val state = database.sourceEvidenceStateDao().get()
			if (state?.collectedDataEpoch != trigger.collectedDataEpoch) {
				return@withTransaction ActivityAutomaticStartRequestAuthorization.Rejected(
					"STALE_COLLECTED_DATA_EPOCH",
				)
			}
			val action = database.activityAutomaticStartActionDao().action(trigger.triggerId)
				?: return@withTransaction ActivityAutomaticStartRequestAuthorization.Rejected(
					"AUTOMATIC_START_RESERVATION_MISSING",
				)
			if (!action.matches(trigger)) {
				return@withTransaction ActivityAutomaticStartRequestAuthorization.Rejected(
					"AUTOMATIC_START_ENVELOPE_MISMATCH",
				)
			}
			currentControlAuthorityFailure(action)?.let { reason ->
				return@withTransaction ActivityAutomaticStartRequestAuthorization.Rejected(reason)
			}
			when (action.status) {
				ActivityAutomaticStartActionEntity.STATUS_RESERVED -> {
					if (database.activityAutomaticStartActionDao().markStartRequested(
						trigger.triggerId,
						trigger.collectedDataEpoch,
						requestedAtMs,
					) != 1
					) {
						return@withTransaction ActivityAutomaticStartRequestAuthorization.Rejected(
							"AUTOMATIC_START_CAS_LOST",
						)
					}
					ActivityAutomaticStartRequestAuthorization.Authorized(
						action.copy(
							status = ActivityAutomaticStartActionEntity.STATUS_START_REQUESTED,
							startRequestedAtMs = requestedAtMs,
						),
					)
				}
				ActivityAutomaticStartActionEntity.STATUS_START_REQUESTED ->
					ActivityAutomaticStartRequestAuthorization.AlreadyRequested(action)
				else -> ActivityAutomaticStartRequestAuthorization.Rejected(
					"AUTOMATIC_START_NOT_REQUESTABLE_${action.status}",
				)
			}
		}
		if (result is ActivityAutomaticStartRequestAuthorization.Authorized &&
			(!startupGate.isReady || startupGate.currentGeneration != startupGeneration)
		) {
			markTerminalExact(trigger, requestedAtMs, "TRACKING_STARTUP_GENERATION_CLOSED")
			return ActivityAutomaticStartRequestAuthorization.Rejected(
				"TRACKING_STARTUP_GENERATION_CLOSED",
			)
		}
		return result
	}

	/** Exact delayed-intent/deletion fence called by TrackerService before coordinator start. */
	internal suspend fun validateForService(
		trigger: AutomaticTrackingStartTrigger,
	): ActivityAutomaticStartServiceValidation {
		val startupGeneration = startupGate.currentGeneration
		if (!startupGate.isReady) {
			return ActivityAutomaticStartServiceValidation.Rejected("TRACKING_STARTUP_NOT_READY")
		}
		val result = database.withTransaction { validateForLifecycleIntentTransaction(trigger) }
		return if (startupGate.isReady && startupGate.currentGeneration == startupGeneration) {
			result
		} else {
			ActivityAutomaticStartServiceValidation.Rejected(
				"TRACKING_STARTUP_GENERATION_CLOSED",
			)
		}
	}

	/**
	 * Coordinator seam. Call this from the same Room transaction that inserts the lifecycle intent;
	 * deliberately does not open its own transaction. This is the final policy, consent, observed
	 * authorization, and collected-data-epoch CAS before durable lifecycle-intent acceptance.
	 */
	internal suspend fun validateForLifecycleIntentTransaction(
		trigger: AutomaticTrackingStartTrigger,
	): ActivityAutomaticStartServiceValidation {
		val state = database.sourceEvidenceStateDao().get()
		if (state?.collectedDataEpoch != trigger.collectedDataEpoch) {
			return ActivityAutomaticStartServiceValidation.Rejected("STALE_COLLECTED_DATA_EPOCH")
		}
		val action = database.activityAutomaticStartActionDao()
			.requestedActionInCurrentDataEpoch(trigger.triggerId, trigger.collectedDataEpoch)
			?: database.activityAutomaticStartActionDao().action(trigger.triggerId)
			?: return ActivityAutomaticStartServiceValidation.Rejected(
				"AUTOMATIC_START_ACTION_MISSING",
			)
		if (!action.matches(trigger)) {
			return ActivityAutomaticStartServiceValidation.Rejected(
				"AUTOMATIC_START_ENVELOPE_MISMATCH",
			)
		}
		return when (action.status) {
			ActivityAutomaticStartActionEntity.STATUS_START_REQUESTED -> {
				currentControlAuthorityFailure(action)?.let { reason ->
					return ActivityAutomaticStartServiceValidation.Rejected(reason)
				}
				ActivityAutomaticStartServiceValidation.Valid(action)
			}
			ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED ->
				if (action.acceptedLogicalTrackingId
					?.let { database.sourceSessionDao().session(it) }
					?.state
					?.let(::isTerminalSessionState) == true
				) {
					ActivityAutomaticStartServiceValidation.Rejected(
						"AUTOMATIC_START_ACCEPTED_SESSION_TERMINAL",
					)
				} else {
					ActivityAutomaticStartServiceValidation.LifecycleIntentAlreadyAccepted(action)
				}
			else -> ActivityAutomaticStartServiceValidation.Rejected(
				"AUTOMATIC_START_ACTION_${action.status}",
			)
		}
	}

	/**
	 * Infers the acknowledgement after a crash between lifecycle-intent commit and explicit action
	 * update. A still-unexpired requested action remains pending because Android may yet deliver the
	 * already-enqueued service intent; the external call is never replayed.
	 */
	internal suspend fun reconcileLifecycleIntentAcceptance(
		triggerId: String,
		currentElapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): ActivityAutomaticStartAcceptance = database.withTransaction {
		val action = database.activityAutomaticStartActionDao().action(triggerId)
			?: return@withTransaction ActivityAutomaticStartAcceptance.Missing
		val state = database.sourceEvidenceStateDao().get()
		if (state?.collectedDataEpoch != action.collectedDataEpoch) {
			return@withTransaction ActivityAutomaticStartAcceptance.Terminal(
				"STALE_COLLECTED_DATA_EPOCH",
			)
		}
		if (action.status == ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED) {
			// Outbox acknowledgement records the exact lifecycle-intent commit, not whether the
			// accepted session is still live when a cold drain eventually observes it. Service
			// validation below remains stricter and rejects redelivery into a terminal session.
			return@withTransaction ActivityAutomaticStartAcceptance.Accepted(action)
		}
		if (action.status == ActivityAutomaticStartActionEntity.STATUS_TERMINAL) {
			return@withTransaction ActivityAutomaticStartAcceptance.Terminal(
				action.terminalReason ?: "AUTOMATIC_START_TERMINAL",
			)
		}

		val intent = database.sourceSessionDao().lifecycleIntentByTriggerId(triggerId)
		if (intent == null) {
			currentControlAuthorityFailure(action)?.let { reason ->
				database.activityAutomaticStartActionDao().markTerminal(
					triggerId,
					action.collectedDataEpoch,
					wallTimeMs,
					reason,
				)
				return@withTransaction ActivityAutomaticStartAcceptance.Terminal(reason)
			}
			if (currentElapsedRealtimeNanos <= action.expiresElapsedRealtimeNanos) {
				return@withTransaction ActivityAutomaticStartAcceptance.Pending(action)
			}
			database.activityAutomaticStartActionDao().markTerminal(
				triggerId,
				action.collectedDataEpoch,
				wallTimeMs,
				"AUTOMATIC_START_LIFECYCLE_INTENT_EXPIRED",
			)
			return@withTransaction ActivityAutomaticStartAcceptance.Terminal(
				"AUTOMATIC_START_LIFECYCLE_INTENT_EXPIRED",
			)
		}

		val manifest = database.sourceSessionDao().manifest(
			intent.logicalTrackingId,
			intent.manifestRevision,
		)
		val bindings = database.sourceSessionDao().manifestSources(
			intent.logicalTrackingId,
			intent.manifestRevision,
		)
		val session = database.sourceSessionDao().session(intent.logicalTrackingId)
		val serviceRun = manifest?.serviceRunId?.let { serviceRunId ->
			database.sourceSessionDao().serviceRun(serviceRunId)
		}
		val activityControlBinding = bindings.singleOrNull { binding ->
			binding.sourceKind == SourceKind.ACTIVITY.stableCode &&
				binding.purpose == MANIFEST_PURPOSE_CONTROL
		}
		if (!action.matches(intent) ||
			manifest == null || !SessionManifestIntegrity.verify(manifest, bindings) ||
			session?.currentServiceRunId != manifest?.serviceRunId ||
			serviceRun?.logicalTrackingId != intent.logicalTrackingId ||
			manifest?.sourcePolicyRevision != action.sourcePolicyRevision ||
			bindings.captureSourceMask() != action.intendedCaptureSourceMask ||
			activityControlBinding?.consentEpoch != action.controlConsentEpoch ||
			serviceRun?.desiredForegroundCapabilityFlags != action.intendedForegroundServiceTypeMask
		) {
			database.activityAutomaticStartActionDao().markTerminal(
				triggerId,
				action.collectedDataEpoch,
				wallTimeMs,
				"AUTOMATIC_START_LIFECYCLE_IDENTITY_COLLISION",
			)
			return@withTransaction ActivityAutomaticStartAcceptance.Terminal(
				"AUTOMATIC_START_LIFECYCLE_IDENTITY_COLLISION",
			)
		}
		if (database.activityAutomaticStartActionDao().markLifecycleIntentAccepted(
			triggerId = triggerId,
			collectedDataEpoch = action.collectedDataEpoch,
			logicalTrackingId = intent.logicalTrackingId,
			intentRevision = intent.intentRevision,
			acceptedAtMs = wallTimeMs,
		) != 1
		) {
			return@withTransaction ActivityAutomaticStartAcceptance.Terminal(
				"AUTOMATIC_START_ACCEPTANCE_CAS_LOST",
			)
		}
		ActivityAutomaticStartAcceptance.Accepted(
			action.copy(
				status = ActivityAutomaticStartActionEntity.STATUS_LIFECYCLE_INTENT_ACCEPTED,
				lifecycleIntentAcceptedAtMs = wallTimeMs,
				acceptedLogicalTrackingId = intent.logicalTrackingId,
				acceptedIntentRevision = intent.intentRevision,
			),
		)
	}

	internal suspend fun markTerminalExact(
		trigger: AutomaticTrackingStartTrigger,
		wallTimeMs: Long,
		reason: String,
	): Boolean = database.withTransaction {
		val current = database.activityAutomaticStartActionDao().action(trigger.triggerId)
		if (current == null || !current.matches(trigger)) return@withTransaction false
		database.activityAutomaticStartActionDao().markTerminal(
			trigger.triggerId,
			trigger.collectedDataEpoch,
			wallTimeMs,
			reason,
		) == 1
	}

	internal suspend fun markReservedReplayExpired(
		triggerId: String,
		effectStableId: String,
		collectedDataEpoch: Long,
		wallTimeMs: Long,
	): Boolean = database.withTransaction {
		val current = database.activityAutomaticStartActionDao().action(triggerId)
		if (current?.effectStableId != effectStableId ||
			current.collectedDataEpoch != collectedDataEpoch ||
			current.status != ActivityAutomaticStartActionEntity.STATUS_RESERVED
		) {
			return@withTransaction false
		}
		database.activityAutomaticStartActionDao().markTerminal(
			triggerId,
			collectedDataEpoch,
			wallTimeMs,
			"AUTOMATIC_START_CALLBACK_ENDED_BEFORE_REQUEST",
		) == 1
	}

	private suspend fun currentControlAuthorityFailure(
		action: ActivityAutomaticStartActionEntity,
	): String? {
		database.activityAutomaticRegistrationFailure(
			registrationGeneration = action.registrationGeneration,
			bootId = action.bootId,
			collectedDataEpoch = action.collectedDataEpoch,
			observedElapsedRealtimeNanos = action.observedElapsedRealtimeNanos,
		)?.let { return it }
		val automationAuthority = database.activityAutomationEpochDao().current()
		if (automationAuthority?.epoch != action.automationEpoch) {
			return "AUTOMATIC_START_AUTOMATION_EPOCH_SUPERSEDED"
		}
		if (automationAuthority.bootClockDomainId != action.bootId ||
			action.observedElapsedRealtimeNanos <
				automationAuthority.effectiveElapsedRealtimeNanos
		) {
			return "AUTOMATIC_START_EVIDENCE_PREDATES_AUTOMATION_EPOCH"
		}
		if (!automationAuthority.automaticControlEnabled ||
			automationAuthority.lockSuppressed ||
			automationAuthority.powerSaverSuppressed
		) {
			return "AUTOMATIC_START_AUTOMATION_RUNTIME_SUPPRESSED"
		}
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			authority.currentPolicyRevision != action.sourcePolicyRevision
		) {
			return "AUTOMATIC_START_SOURCE_POLICY_SUPERSEDED"
		}
		val activityPolicy = policyDao.policyAtRevision(
			action.sourcePolicyRevision,
			SourceKind.ACTIVITY.stableCode,
		) ?: return "AUTOMATIC_START_CONTROL_CONSENT_SUPERSEDED"
		if (activityPolicy.controlConsentEpoch != action.controlConsentEpoch) {
			return "AUTOMATIC_START_CONTROL_CONSENT_SUPERSEDED"
		}
		if (!policyDao.hasAuthoritativeConsentReference(
				policy = activityPolicy,
				purpose = POLICY_PURPOSE_CONTROL,
				requirePersistenceEligible = false,
			)
		) {
			return "AUTOMATIC_START_CONTROL_CONSENT_INELIGIBLE"
		}
		val authorization = database.sourceBrokerDao().authorizationAt(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			registrationGeneration = action.registrationGeneration,
			bootId = action.bootId,
			observedElapsedRealtimeNanos = action.observedElapsedRealtimeNanos,
		).toAuthorizationSnapshotOrNull()
		if (authorization?.authorizationRevision != action.authorizationRevision ||
			authorization.authorizationFingerprint != action.authorizationFingerprint ||
			authorization.purposeEligibilityMask and
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART == 0L
		) {
			return "AUTOMATIC_START_OBSERVED_AUTHORIZATION_SUPERSEDED"
		}
		val controlMember = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.CONTROL_AUTOSTART
		}
		if (controlMember?.consentEpoch != action.controlConsentEpoch ||
			controlMember.sourcePolicyRevision != action.sourcePolicyRevision
		) {
			return "AUTOMATIC_START_OBSERVED_CONTROL_IDENTITY_MISMATCH"
		}
		return null
	}
}

internal suspend fun AppDatabase.activityAutomaticRegistrationFailure(
	registrationGeneration: Long,
	bootId: String,
	collectedDataEpoch: Long,
	observedElapsedRealtimeNanos: Long,
): String? {
	val brokerDao = sourceBrokerDao()
	val registration = brokerDao.registration(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		registrationGeneration = registrationGeneration,
	) ?: return "AUTOMATIC_START_REGISTRATION_MISSING"
	registration.activityAutomaticStaticFailure(
		bootId,
		collectedDataEpoch,
		observedElapsedRealtimeNanos,
	)?.let { return it }
	val registrationAtObservedTime = brokerDao.registrationAtObservedTime(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		registrationGeneration = registration.registrationGeneration,
		sourceInstanceId = registration.sourceInstanceId,
		bootId = bootId,
		physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
		observedElapsedRealtimeNanos = observedElapsedRealtimeNanos,
	)
	return when {
		registrationAtObservedTime != null -> null
		registration.retiredElapsedRealtimeNanos?.let { cutoff ->
			observedElapsedRealtimeNanos >= cutoff
		} == true -> "AUTOMATIC_START_EVIDENCE_AT_OR_AFTER_REGISTRATION_CUTOFF"
		else -> "AUTOMATIC_START_REGISTRATION_NOT_ELIGIBLE_AT_OBSERVED_TIME"
	}
}

private fun ProviderRegistrationGenerationEntity.activityAutomaticStaticFailure(
	bootId: String,
	collectedDataEpoch: Long,
	observedElapsedRealtimeNanos: Long,
): String? {
	val acceptedAt = acceptedElapsedRealtimeNanos
	return when {
		clockDomainId != bootId || this.collectedDataEpoch != collectedDataEpoch ->
			"AUTOMATIC_START_REGISTRATION_IDENTITY_MISMATCH"
		status == ProviderRegistrationGenerationEntity.STATUS_FAILED ->
			"AUTOMATIC_START_REGISTRATION_FAILED"
		acceptedAt == null || observedElapsedRealtimeNanos < acceptedAt ->
			"AUTOMATIC_START_EVIDENCE_PREDATES_REGISTRATION_ACCEPTANCE"
		status !in ACTIVITY_AUTOMATIC_ELIGIBLE_REGISTRATION_STATUSES ->
			"AUTOMATIC_START_REGISTRATION_NOT_ELIGIBLE_AT_OBSERVED_TIME"
		else -> null
	}
}

private fun ActivityAutomaticStartReservation.toEntity() = ActivityAutomaticStartActionEntity(
	triggerId = trigger.triggerId,
	effectStableId = effectStableId,
	admissionOrdinal = evidence.admissionOrdinal,
	triggerKind = trigger.kind,
	bootId = trigger.bootId,
	observedElapsedRealtimeNanos = trigger.observedElapsedRealtimeNanos,
	receivedElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
	expiresElapsedRealtimeNanos = trigger.expiresElapsedRealtimeNanos,
	automationEpoch = trigger.automationEpoch,
	sourcePolicyRevision = trigger.sourcePolicyRevision,
	controlConsentEpoch = controlConsentEpoch,
	collectedDataEpoch = trigger.collectedDataEpoch,
	requestedCaptureSourceMask = trigger.requestedCaptureSourceMask,
	intendedCaptureSourceMask = trigger.intendedCaptureSourceMask,
	intendedForegroundServiceTypeMask = trigger.intendedForegroundServiceTypeMask,
	registrationGeneration = evidence.registrationGeneration,
	authorizationRevision = evidence.authorizationRevision,
	authorizationFingerprint = evidence.authorizationFingerprint,
	startOrigin = ActivityAutomaticStartActionEntity.START_ORIGIN_ACTIVITY_TRANSITION_CALLBACK,
	status = ActivityAutomaticStartActionEntity.STATUS_RESERVED,
	reservedAtMs = reservedAtMs,
	startRequestedAtMs = null,
	lifecycleIntentAcceptedAtMs = null,
	acceptedLogicalTrackingId = null,
	acceptedIntentRevision = null,
	terminalAtMs = null,
	terminalReason = null,
)

private fun ActivityAutomaticStartActionEntity.matches(
	other: ActivityAutomaticStartActionEntity,
): Boolean = copy(
	status = other.status,
	reservedAtMs = other.reservedAtMs,
	startRequestedAtMs = other.startRequestedAtMs,
	lifecycleIntentAcceptedAtMs = other.lifecycleIntentAcceptedAtMs,
	acceptedLogicalTrackingId = other.acceptedLogicalTrackingId,
	acceptedIntentRevision = other.acceptedIntentRevision,
	terminalAtMs = other.terminalAtMs,
	terminalReason = other.terminalReason,
) == other

private fun ActivityAutomaticStartActionEntity.matches(
	trigger: AutomaticTrackingStartTrigger,
): Boolean = triggerId == trigger.triggerId &&
	triggerKind == trigger.kind &&
	bootId == trigger.bootId &&
	observedElapsedRealtimeNanos == trigger.observedElapsedRealtimeNanos &&
	receivedElapsedRealtimeNanos == trigger.receivedElapsedRealtimeNanos &&
	expiresElapsedRealtimeNanos == trigger.expiresElapsedRealtimeNanos &&
	automationEpoch == trigger.automationEpoch &&
	sourcePolicyRevision == trigger.sourcePolicyRevision &&
	collectedDataEpoch == trigger.collectedDataEpoch &&
	requestedCaptureSourceMask == trigger.requestedCaptureSourceMask &&
	intendedCaptureSourceMask == trigger.intendedCaptureSourceMask &&
	intendedForegroundServiceTypeMask == trigger.intendedForegroundServiceTypeMask &&
	startOrigin == trigger.startContext.name

private fun ActivityAutomaticStartActionEntity.matches(
	intent: com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity,
): Boolean = triggerId == intent.triggerId &&
	triggerKind == intent.triggerKind &&
	bootId == intent.triggerBootId &&
	observedElapsedRealtimeNanos == intent.triggerObservedElapsedRealtimeNanos &&
	receivedElapsedRealtimeNanos == intent.triggerReceivedElapsedRealtimeNanos &&
	expiresElapsedRealtimeNanos == intent.triggerExpiresElapsedRealtimeNanos &&
	automationEpoch == intent.automationEpoch &&
	collectedDataEpoch == intent.triggerCollectedDataEpoch

private fun List<com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity>
	.captureSourceMask(): Long = asSequence()
	.filter { it.purpose == "SESSION_CAPTURE" }
	.fold(0L) { mask, source ->
		require(source.sourceKind in 1..Long.SIZE_BITS) { "Invalid source kind ${source.sourceKind}" }
		mask or (1L shl (source.sourceKind - 1))
	}

private fun isTerminalSessionState(state: String): Boolean = state in setOf(
	"FINALIZED",
	"CLOSED",
	"FAILED",
)

private const val POLICY_PURPOSE_CONTROL = "CONTROL"
private const val MANIFEST_PURPOSE_CONTROL = "CONTROL"
private val ACTIVITY_AUTOMATIC_ELIGIBLE_REGISTRATION_STATUSES = setOf(
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
	ProviderRegistrationGenerationEntity.STATUS_RETIRING,
	ProviderRegistrationGenerationEntity.STATUS_RETIRED,
)
