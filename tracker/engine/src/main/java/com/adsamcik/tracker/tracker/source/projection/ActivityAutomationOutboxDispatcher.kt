package com.adsamcik.tracker.tracker.source.projection

import android.content.Context
import android.os.SystemClock
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.api.ActivityAutomationDeliveryEnvelope
import com.adsamcik.tracker.tracker.api.ActivityAutomationDeliveryResult
import com.adsamcik.tracker.tracker.api.ActivityAutomationStartContext
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.api.activityTransitionCallbackEnqueueDeadlineElapsedRealtimeNanos
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ActivityAutomationOutboxDispatcher @Inject internal constructor(
	private val database: AppDatabase,
	private val validator: ActivityAutomationEffectValidator,
	private val consumer: ActivityAutomationEffectConsumer,
) : ActivityAutomationDrainSignal {
	private val drainMutex = Mutex()
	private val _drainRequired = MutableStateFlow(true)

	/**
	 * Process-local wake-up hint for the app-owned retry driver. It starts true so a cold process
	 * always probes durable work left by a previous process. The outbox remains the source of truth.
	 */
	val drainRequired: StateFlow<Boolean> = _drainRequired.asStateFlow()

	override fun requestDrain() {
		_drainRequired.value = true
	}

	/**
	 * Compatibility entry point used by event-driven callers. It drains one bounded page and marks
	 * the app-owned driver as required whenever work may remain or the head effect asks for retry.
	 */
	suspend fun drain(
		limit: Int = DEFAULT_BATCH_SIZE,
		startPermit: ActivityAutomationStartPermit = ActivityAutomationStartPermit.None,
		elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
	): Int = runSerializedDrain {
		require(limit > 0)
		val batch = drainBatch(limit, startPermit, elapsedRealtimeNanos)
		_drainRequired.value = batch.retryBlocked || batch.selectedCount == limit
		batch.deliveredCount
	}

	/**
	 * Drains ordered Activity automation effects to a bounded quiescent point. A retryable head is
	 * never skipped, preserving event order; a full batch budget yields [MorePending] so the
	 * app-owned driver can yield before continuing instead of monopolizing startup.
	 */
	suspend fun drainToQuiescence(
		batchSize: Int = DEFAULT_BATCH_SIZE,
		maxBatches: Int = DEFAULT_MAX_BATCHES,
		startPermit: ActivityAutomationStartPermit = ActivityAutomationStartPermit.None,
		elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
	): ActivityAutomationDrainResult = runSerializedDrain {
		require(batchSize > 0)
		require(maxBatches > 0)
		var deliveredCount = 0
		var terminalCount = 0
		repeat(maxBatches) {
			val batch = drainBatch(batchSize, startPermit, elapsedRealtimeNanos)
			deliveredCount += batch.deliveredCount
			terminalCount += batch.terminalCount
			if (batch.retryBlocked) {
				_drainRequired.value = true
				return@runSerializedDrain ActivityAutomationDrainResult.Retryable(
					deliveredCount = deliveredCount,
					terminalCount = terminalCount,
				)
			}
			if (batch.selectedCount < batchSize) {
				_drainRequired.value = false
				return@runSerializedDrain ActivityAutomationDrainResult.Complete(
					deliveredCount = deliveredCount,
					terminalCount = terminalCount,
				)
			}
		}
		_drainRequired.value = true
		ActivityAutomationDrainResult.MorePending(
			deliveredCount = deliveredCount,
			terminalCount = terminalCount,
		)
	}

	private suspend fun drainBatch(
		limit: Int,
		startPermit: ActivityAutomationStartPermit,
		elapsedRealtimeNanos: () -> Long,
	): ActivityAutomationDrainBatch {
		var delivered = 0
		var terminal = 0
		val dao = database.sourceProjectionStateDao()
		val effects = dao
			.pendingOutbox(
				ActivityAutomationProjection.ID,
				ActivityAutomationProjection.VERSION,
				ActivityAutomationProjection.OUTBOX_KIND,
				limit,
			)
		for (effect in effects) {
			val decoded = try {
				decode(effect.payload, effect.admissionOrdinal)
			} catch (_: Exception) {
				terminal += dao.markOutboxTerminal(
					effect.stableId,
					TERMINAL_INVALID_PAYLOAD,
					System.currentTimeMillis(),
				)
				continue
			}
			when (val validation = validator.validate(decoded)) {
				ActivityAutomationEffectValidation.LifecycleIntentAccepted -> {
					if (dao.markOutboxDelivered(effect.stableId, System.currentTimeMillis()) == 1) {
						delivered++
					}
					continue
				}
				is ActivityAutomationEffectValidation.Terminal -> {
					terminal += dao.markOutboxTerminal(
						effect.stableId,
						validation.disposition,
						System.currentTimeMillis(),
					)
					continue
				}
				is ActivityAutomationEffectValidation.Eligible -> {
					when (consumer.deliver(
						effectStableId = effect.stableId,
						evidence = decoded,
						controlConsentEpoch = validation.controlConsentEpoch,
						automationEpoch = validation.automationEpoch,
						startContext = startPermit.contextFor(decoded, elapsedRealtimeNanos),
					)) {
						ActivityAutomationDeliveryResult.ACCEPTED,
						ActivityAutomationDeliveryResult.LIFECYCLE_INTENT_ACCEPTED,
						-> {
							if (dao.markOutboxDelivered(effect.stableId, System.currentTimeMillis()) == 1) {
								delivered++
							}
						}
						ActivityAutomationDeliveryResult.RETRY,
						ActivityAutomationDeliveryResult.START_REQUESTED,
						-> return ActivityAutomationDrainBatch(
							selectedCount = effects.size,
							deliveredCount = delivered,
							terminalCount = terminal,
							retryBlocked = true,
						)
						ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED ->
							terminal += dao.markOutboxTerminal(
								effect.stableId,
								TERMINAL_POLICY_SUPPRESSED,
								System.currentTimeMillis(),
							)
						ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED ->
							terminal += dao.markOutboxTerminal(
								effect.stableId,
								TERMINAL_START_CONTEXT_EXPIRED,
								System.currentTimeMillis(),
							)
					}
				}
				}
		}
		return ActivityAutomationDrainBatch(
			selectedCount = effects.size,
			deliveredCount = delivered,
			terminalCount = terminal,
			retryBlocked = false,
		)
	}

	private suspend fun <T> runSerializedDrain(block: suspend () -> T): T = try {
		drainMutex.withLock { block() }
	} catch (failure: Throwable) {
		_drainRequired.value = true
		throw failure
	}

	private fun decode(
		payload: ByteArray,
		admissionOrdinal: Long,
	): ActivityAutomationDeliveryEnvelope =
		DataInputStream(ByteArrayInputStream(payload)).use { input ->
			val kind = input.readInt()
			val activity = activityFromStableCode(input.readInt())
			val confidence = input.readInt()
			val transition = input.readInt()
			val clockDomainId = input.readUTF()
			val observedElapsedRealtimeNanos = input.readLong()
			val receivedElapsedRealtimeNanos = input.readLong()
			val registrationGeneration = input.readLong()
			val authorizationRevision = input.readLong()
			val authorizationFingerprint = input.readUTF()
			val collectedDataEpoch = input.readLong()
			val automationEpoch = input.readLong()
			require(input.available() == 0)
			ActivityAutomationDeliveryEnvelope(
				admissionOrdinal = admissionOrdinal,
				activityType = activity,
				confidence = confidence,
				transitionType = if (kind == ActivityAutomationProjection.KIND_TRANSITION) {
					ActivityTransitionType.entries.singleOrNull { it.value == transition }
				} else {
					null
				},
				clockDomainId = clockDomainId,
				observedElapsedRealtimeNanos = observedElapsedRealtimeNanos,
				receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
				registrationGeneration = registrationGeneration,
				authorizationRevision = authorizationRevision,
				authorizationFingerprint = authorizationFingerprint,
				collectedDataEpoch = collectedDataEpoch,
				automationEpoch = automationEpoch,
			)
		}

	private fun activityFromStableCode(code: Int): DetectedActivityType = when (code) {
		0 -> DetectedActivityType.STILL
		1 -> DetectedActivityType.WALKING
		2 -> DetectedActivityType.RUNNING
		3 -> DetectedActivityType.ON_BICYCLE
		4 -> DetectedActivityType.IN_VEHICLE
		5 -> DetectedActivityType.ON_FOOT
		6 -> DetectedActivityType.TILTING
		else -> DetectedActivityType.UNKNOWN
	}

	private companion object {
		const val DEFAULT_BATCH_SIZE = 100
		const val DEFAULT_MAX_BATCHES = 10
		const val TERMINAL_POLICY_SUPPRESSED = "CURRENT_POLICY_SUPPRESSED_AUTOMATION"
		const val TERMINAL_START_CONTEXT_EXPIRED = "START_CONTEXT_EXPIRED"
		const val TERMINAL_INVALID_PAYLOAD = "INVALID_AUTOMATION_EFFECT_PAYLOAD"
	}
}

/** Process-local wake-up only; durable outbox rows remain the work authority. */
fun interface ActivityAutomationDrainSignal {
	fun requestDrain()
}

sealed interface ActivityAutomationDrainResult {
	val deliveredCount: Int
	val terminalCount: Int

	data class Complete(
		override val deliveredCount: Int,
		override val terminalCount: Int,
	) : ActivityAutomationDrainResult

	data class Retryable(
		override val deliveredCount: Int,
		override val terminalCount: Int,
	) : ActivityAutomationDrainResult

	data class MorePending(
		override val deliveredCount: Int,
		override val terminalCount: Int,
	) : ActivityAutomationDrainResult
}

private data class ActivityAutomationDrainBatch(
	val selectedCount: Int,
	val deliveredCount: Int,
	val terminalCount: Int,
	val retryBlocked: Boolean,
)

sealed interface ActivityAutomationStartPermit {
	data object None : ActivityAutomationStartPermit

	/** Only these exact admitted ordinals still own the currently executing transition callback. */
	data class FreshTransitionCallback(
		val admissionOrdinals: Set<Long>,
	) : ActivityAutomationStartPermit {
		init {
			require(admissionOrdinals.isNotEmpty())
			require(admissionOrdinals.all { it > 0L })
		}
	}
}

internal fun ActivityAutomationStartPermit.contextFor(
	effect: ActivityAutomationDeliveryEnvelope,
	elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
): ActivityAutomationStartContext = if (
	this is ActivityAutomationStartPermit.FreshTransitionCallback &&
	effect.transitionType != null &&
	effect.admissionOrdinal in admissionOrdinals &&
	elapsedRealtimeNanos() < activityTransitionCallbackEnqueueDeadlineElapsedRealtimeNanos(
		effect.observedElapsedRealtimeNanos,
		effect.receivedElapsedRealtimeNanos,
	)
) {
	ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK
} else {
	ActivityAutomationStartContext.DURABLE_REPLAY
}

internal interface ActivityAutomationEffectConsumer {
	suspend fun deliver(
		effectStableId: String,
		evidence: ActivityAutomationDeliveryEnvelope,
		controlConsentEpoch: Long,
		automationEpoch: Long,
		startContext: ActivityAutomationStartContext,
	): ActivityAutomationDeliveryResult
}

@Singleton
internal class BackgroundTrackingActivityAutomationEffectConsumer @Inject constructor(
	@ApplicationContext private val context: Context,
	private val automaticStartActions: ActivityAutomaticStartActionRepository,
) : ActivityAutomationEffectConsumer {
	override suspend fun deliver(
		effectStableId: String,
		evidence: ActivityAutomationDeliveryEnvelope,
		controlConsentEpoch: Long,
		automationEpoch: Long,
		startContext: ActivityAutomationStartContext,
	): ActivityAutomationDeliveryResult {
		if (evidence.transitionType != null) {
			val triggerId = "activity-transition:${evidence.clockDomainId}:${evidence.admissionOrdinal}"
			when (val acceptance = automaticStartActions.reconcileLifecycleIntentAcceptance(
				triggerId = triggerId,
				currentElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
				wallTimeMs = System.currentTimeMillis(),
			)) {
				is ActivityAutomaticStartAcceptance.Accepted ->
					return ActivityAutomationDeliveryResult.LIFECYCLE_INTENT_ACCEPTED
				is ActivityAutomaticStartAcceptance.Terminal ->
					return ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
				is ActivityAutomaticStartAcceptance.Pending -> when (acceptance.action.status) {
					com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
						.STATUS_START_REQUESTED -> return ActivityAutomationDeliveryResult.START_REQUESTED
					com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
						.STATUS_RESERVED -> if (
						startContext == ActivityAutomationStartContext.DURABLE_REPLAY
					) {
						automaticStartActions.markReservedReplayExpired(
							triggerId,
							effectStableId,
							evidence.collectedDataEpoch,
							System.currentTimeMillis(),
						)
						return ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
					}
					else -> Unit
				}
				ActivityAutomaticStartAcceptance.Missing -> Unit
			}
		}
		return BackgroundTrackingApi.handleDurableActivityEvidence(
			context = context,
			evidence = evidence,
			controlConsentEpoch = controlConsentEpoch,
			currentAutomationEpoch = automationEpoch,
			startContext = startContext,
			requestAutomaticStart = { trigger ->
				requestAutomaticStart(trigger, effectStableId, evidence, controlConsentEpoch)
			},
		)
	}

	private suspend fun requestAutomaticStart(
		trigger: com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger,
		effectStableId: String,
		evidence: ActivityAutomationDeliveryEnvelope,
		controlConsentEpoch: Long,
	): ActivityAutomationDeliveryResult {
		val nowElapsed = SystemClock.elapsedRealtimeNanos()
		val nowWall = System.currentTimeMillis()
		when (val acceptance = automaticStartActions.reconcileLifecycleIntentAcceptance(
			trigger.triggerId,
			nowElapsed,
			nowWall,
		)) {
			is ActivityAutomaticStartAcceptance.Accepted ->
				return ActivityAutomationDeliveryResult.LIFECYCLE_INTENT_ACCEPTED
			is ActivityAutomaticStartAcceptance.Terminal ->
				return ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
			is ActivityAutomaticStartAcceptance.Pending -> when (acceptance.action.status) {
				com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
					.STATUS_START_REQUESTED -> return ActivityAutomationDeliveryResult.START_REQUESTED
				com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
					.STATUS_RESERVED -> Unit
				else -> return ActivityAutomationDeliveryResult.RETRY
			}
			ActivityAutomaticStartAcceptance.Missing -> Unit
		}

		if (trigger.startContext !=
			com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
				.ACTIVITY_TRANSITION_CALLBACK
		) {
			return ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
		}
		when (val reservation = automaticStartActions.reserve(
			ActivityAutomaticStartReservation(
				trigger = trigger,
				effectStableId = effectStableId,
				evidence = evidence,
				controlConsentEpoch = controlConsentEpoch,
				reservedAtMs = nowWall,
			),
		)) {
			is ActivityAutomaticStartReserveResult.Rejected ->
				return ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
			is ActivityAutomaticStartReserveResult.ConflictingCurrent ->
				return ActivityAutomationDeliveryResult.RETRY
			is ActivityAutomaticStartReserveResult.Existing -> when (reservation.action.status) {
				com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
					.STATUS_START_REQUESTED -> return ActivityAutomationDeliveryResult.START_REQUESTED
				com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
					.STATUS_LIFECYCLE_INTENT_ACCEPTED ->
					return ActivityAutomationDeliveryResult.LIFECYCLE_INTENT_ACCEPTED
				com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
					.STATUS_TERMINAL ->
					return ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
				else -> Unit
			}
			is ActivityAutomaticStartReserveResult.Reserved -> Unit
		}

		return when (automaticStartActions.authorizeExternalStart(trigger, nowWall)) {
			is ActivityAutomaticStartRequestAuthorization.AlreadyRequested ->
				ActivityAutomationDeliveryResult.START_REQUESTED
			is ActivityAutomaticStartRequestAuthorization.Rejected ->
				ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
			is ActivityAutomaticStartRequestAuthorization.Authorized -> {
				if (automaticStartActions.validateForService(trigger) !is
					ActivityAutomaticStartServiceValidation.Valid
				) {
					automaticStartActions.markTerminalExact(
						trigger,
						System.currentTimeMillis(),
						"AUTOMATIC_START_AUTHORITY_CLOSED_BEFORE_ANDROID_CALL",
					)
					return ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
				}
				val enqueued = try {
					com.adsamcik.tracker.tracker.api.TrackerServiceApi.startServiceAndAwaitEnqueue(
						context,
						isUserInitiated = false,
						automaticTrigger = trigger,
					)
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: RuntimeException) {
					false
				}
				if (enqueued) {
					ActivityAutomationDeliveryResult.START_REQUESTED
				} else {
					automaticStartActions.markTerminalExact(
						trigger,
						System.currentTimeMillis(),
						"ANDROID_FOREGROUND_SERVICE_START_NOT_ENQUEUED",
					)
					ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
				}
			}
		}
	}
}

internal sealed interface ActivityAutomationEffectValidation {
	/** Matching durable lifecycle intent found before freshness checks during cold recovery. */
	data object LifecycleIntentAccepted : ActivityAutomationEffectValidation

	data class Eligible(
		val controlConsentEpoch: Long,
		val automationEpoch: Long,
	) : ActivityAutomationEffectValidation
	data class Terminal(val disposition: String) : ActivityAutomationEffectValidation
}

@Singleton
internal class ActivityAutomationEffectValidator @Inject constructor(
	private val database: AppDatabase,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val automaticStartActions: ActivityAutomaticStartActionRepository,
	private val automationEpochAuthority: ActivityAutomationEpochAuthority,
) {
	suspend fun validate(
		effect: ActivityAutomationDeliveryEnvelope,
	): ActivityAutomationEffectValidation {
		if (effect.transitionType != null) {
			val triggerId = "activity-transition:${effect.clockDomainId}:${effect.admissionOrdinal}"
			when (val acceptance = automaticStartActions.reconcileLifecycleIntentAcceptance(
				triggerId = triggerId,
				currentElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
				wallTimeMs = System.currentTimeMillis(),
			)) {
				is ActivityAutomaticStartAcceptance.Accepted ->
					return ActivityAutomationEffectValidation.LifecycleIntentAccepted
				is ActivityAutomaticStartAcceptance.Terminal ->
					return ActivityAutomationEffectValidation.Terminal(acceptance.reason)
				is ActivityAutomaticStartAcceptance.Pending,
				ActivityAutomaticStartAcceptance.Missing,
				-> Unit
			}
		}
		val authorization = database.sourceBrokerDao().authorizationAt(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			registrationGeneration = effect.registrationGeneration,
			bootId = effect.clockDomainId,
			observedElapsedRealtimeNanos = effect.observedElapsedRealtimeNanos,
		).toAuthorizationSnapshotOrNull()
		val automationAuthority = automationEpochAuthority.currentForValidation()
		return validateActivityAutomationEffectEnvelope(
			effect = effect,
			currentBootId = bootClockDomainProvider.current(),
			currentElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
			authorization = authorization,
			currentAutomationEpoch = automationAuthority.epoch,
			currentAutomationBootId = automationAuthority.bootClockDomainId,
			currentAutomationEffectiveElapsedRealtimeNanos =
				automationAuthority.effectiveElapsedRealtimeNanos,
			automaticControlEnabled = automationAuthority.automaticControlEnabled,
			lockSuppressed = automationAuthority.lockSuppressed,
			powerSaverSuppressed = automationAuthority.powerSaverSuppressed,
		)
	}
}

internal fun validateActivityAutomationEffectEnvelope(
	effect: ActivityAutomationDeliveryEnvelope,
	currentBootId: String,
	currentElapsedRealtimeNanos: Long,
	authorization: SourceAuthorizationSnapshot?,
	currentAutomationEpoch: Long,
	currentAutomationBootId: String = currentBootId,
	currentAutomationEffectiveElapsedRealtimeNanos: Long = 0L,
	automaticControlEnabled: Boolean = true,
	lockSuppressed: Boolean = false,
	powerSaverSuppressed: Boolean = false,
): ActivityAutomationEffectValidation {
	if (effect.clockDomainId != currentBootId) {
		return ActivityAutomationEffectValidation.Terminal("STALE_AUTOMATION_BOOT")
	}
	if (effect.observedElapsedRealtimeNanos > effect.receivedElapsedRealtimeNanos ||
		effect.receivedElapsedRealtimeNanos > currentElapsedRealtimeNanos
	) {
		return ActivityAutomationEffectValidation.Terminal("INVALID_AUTOMATION_TIME")
	}
	if (currentElapsedRealtimeNanos - effect.observedElapsedRealtimeNanos > MAX_AUTOMATION_AGE_NANOS) {
		return ActivityAutomationEffectValidation.Terminal("STALE_AUTOMATION_EVIDENCE")
	}
	if (effect.automationEpoch != currentAutomationEpoch) {
		return ActivityAutomationEffectValidation.Terminal("STALE_AUTOMATION_EPOCH")
	}
	if (effect.clockDomainId != currentAutomationBootId ||
		effect.observedElapsedRealtimeNanos < currentAutomationEffectiveElapsedRealtimeNanos
	) {
		return ActivityAutomationEffectValidation.Terminal(
			"AUTOMATION_EVIDENCE_PREDATES_EPOCH",
		)
	}
	if (!automaticControlEnabled || lockSuppressed || powerSaverSuppressed) {
		return ActivityAutomationEffectValidation.Terminal("AUTOMATION_RUNTIME_SUPPRESSED")
	}
	if (authorization == null ||
		authorization.authorizationRevision != effect.authorizationRevision ||
		authorization.authorizationFingerprint != effect.authorizationFingerprint ||
		authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_CONTROL_AUTOSTART == 0L
	) {
		return ActivityAutomationEffectValidation.Terminal("OBSOLETE_AUTOMATION_AUTHORIZATION")
	}
	val controlMembers = authorization.authorizedMembers.filter { member ->
		member.purpose == SourceBrokerPurpose.CONTROL_AUTOSTART
	}
	val controlMember = controlMembers.singleOrNull()
		?: return ActivityAutomationEffectValidation.Terminal("OBSOLETE_AUTOMATION_CONSENT")
	val consentEpoch = controlMember.consentEpoch
		?: return ActivityAutomationEffectValidation.Terminal("OBSOLETE_AUTOMATION_CONSENT")
	return ActivityAutomationEffectValidation.Eligible(consentEpoch, currentAutomationEpoch)
}

private const val MAX_AUTOMATION_AGE_NANOS = 60L * 1_000_000_000L

@Module
@InstallIn(SingletonComponent::class)
internal interface ActivityAutomationEffectConsumerModule {
	@Binds
	fun bindActivityAutomationEffectConsumer(
		implementation: BackgroundTrackingActivityAutomationEffectConsumer,
	): ActivityAutomationEffectConsumer

	@Binds
	fun bindActivityAutomationDrainSignal(
		implementation: ActivityAutomationOutboxDispatcher,
	): ActivityAutomationDrainSignal
}
