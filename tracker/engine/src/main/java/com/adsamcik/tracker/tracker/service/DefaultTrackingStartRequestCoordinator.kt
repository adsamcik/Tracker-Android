package com.adsamcik.tracker.tracker.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasBackgroundLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.api.TrackingStartRequest
import com.adsamcik.tracker.tracker.api.TrackingStartRequestCoordinator
import com.adsamcik.tracker.tracker.api.activityTransitionCallbackCleanupDeadlineElapsedRealtimeNanos
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.LogicalTrackingLifecycleState
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingRedeliveryRecoveryReservation
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.coordinator.AndroidStartDeliveryMetadata
import com.adsamcik.tracker.tracker.source.coordinator.AuthoritativeSessionCoordinator
import com.adsamcik.tracker.tracker.source.coordinator.ClaimedPreparedSessionStart
import com.adsamcik.tracker.tracker.source.coordinator.PlanResolutionContext
import com.adsamcik.tracker.tracker.source.coordinator.PreparedSessionClaimResult
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartPreparationResult
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartResult
import com.adsamcik.tracker.tracker.source.coordinator.ServiceRunContinuationAuthority
import com.adsamcik.tracker.tracker.source.coordinator.SourceConstraint
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionPlanInputs
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStartRequest
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession
import com.adsamcik.tracker.tracker.source.coordinator.TrackingSessionOwnership
import com.adsamcik.tracker.tracker.source.coordinator.captureModeFor
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Room-first implementation shared by manual, automatic, and exact recovery start origins. */
@Singleton
internal class DefaultTrackingStartRequestCoordinator @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: AppDatabase,
	private val trackingStartupGate: TrackingStartupGate,
	private val trackingParamsRepository: TrackingParamsRepository,
	private val activeTrackingSessionStore: ActiveTrackingSessionStore,
	private val sourceSession: TrackerServiceSourceSession,
	private val authoritativeSessionCoordinator: AuthoritativeSessionCoordinator,
	private val trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val collectionMotionController: CollectionMotionController,
	private val activityAutomationEpochAuthority: ActivityAutomationEpochAuthority,
	private val trackingLifecycleCommandAuthority: TrackingLifecycleCommandAuthority,
) : TrackingStartRequestCoordinator {
	private val powerManager: PowerManager = context.getSystemServiceTyped(Context.POWER_SERVICE)

	override suspend fun prepare(request: TrackingStartRequest): TrackingStartPreparationResult =
		prepareInternal(request)

	private suspend fun prepareInternal(
		request: TrackingStartRequest,
		exactContinuationAuthority: ServiceRunContinuationAuthority? = null,
	): TrackingStartPreparationResult {
		if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) {
			return TrackingStartPreparationResult.Rejected("TRACKING_STARTUP_NOT_READY")
		}
		val startupGeneration = trackingStartupGate.currentGeneration
		return trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			prepareUnderReadyGeneration(
				request = request,
				exactContinuationAuthority = exactContinuationAuthority,
				startupGeneration = startupGeneration,
			)
		} ?: TrackingStartPreparationResult.Rejected("TRACKING_STARTUP_GENERATION_CLOSED")
	}

	/**
	 * Performs the complete Room/descriptor preparation while deletion is excluded for one exact
	 * startup generation. Callers already holding that lease use this directly to avoid nesting the
	 * non-reentrant startup mutex.
	 */
	private suspend fun prepareUnderReadyGeneration(
		request: TrackingStartRequest,
		exactContinuationAuthority: ServiceRunContinuationAuthority?,
		startupGeneration: Long,
	): TrackingStartPreparationResult {
		val bootId = bootClockDomainProvider.current()
		val descriptorResolution = resolveDescriptor(request, bootId)
		val resolved = when (descriptorResolution) {
			is TrackingStartDescriptorResolution.Failure ->
				return TrackingStartPreparationResult.Rejected(descriptorResolution.code)
			TrackingStartDescriptorResolution.AlreadyActive ->
				return TrackingStartPreparationResult.AlreadyActive
			is TrackingStartDescriptorResolution.Resolved -> descriptorResolution
		}
		val continuationAuthority = exactContinuationAuthority ?: resolved.continuationAuthority
		if (exactContinuationAuthority != null &&
			resolved.continuationAuthority?.previousServiceRunId !=
				exactContinuationAuthority.previousServiceRunId
		) return TrackingStartPreparationResult.Rejected("REDELIVERY_DESCRIPTOR_CHANGED")
		val settings = trackingParamsRepository.data.first()
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration ||
			bootClockDomainProvider.current() != bootId
		) return TrackingStartPreparationResult.Rejected("TRACKING_STARTUP_GENERATION_CLOSED")

		val automaticExpected = resolved.origin == SessionStartOrigin.AUTOMATIC_BACKGROUND_START
		val currentAutomationEpoch = if (automaticExpected) {
			activityAutomationEpochAuthority.currentForValidation().epoch
		} else {
			null
		}
		validateAutomaticStartAtRuntime(
			automaticStartExpected = automaticExpected,
			trigger = request.automaticTrigger,
			currentBootId = bootId,
			currentElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
			currentPolicyRevision = settings.sourcePolicyRevision,
			currentAutomationEpoch = currentAutomationEpoch,
			hasActivityPermission = context.hasActivityPermission,
		)?.let(TrackingStartPreparationResult::Rejected)?.let { return it }

		val configuredSources = configuredForegroundSources(settings)
		val requestedSources = if (automaticExpected) {
			sourceKindsFromMask(requireNotNull(request.automaticTrigger).requestedCaptureSourceMask)
				?: return TrackingStartPreparationResult.Rejected(
					"AUTOMATIC_START_REQUESTED_MASK_INVALID",
				)
		} else {
			configuredSources
		}
		if (automaticExpected && requestedSources != configuredSources) {
			return TrackingStartPreparationResult.Rejected("AUTOMATIC_START_REQUESTED_MASK_STALE")
		}
		val rollout = try {
			trackingRolloutStateStore.load()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return TrackingStartPreparationResult.Rejected("TRACKING_ROLLOUT_UNAVAILABLE")
		}
		val captureMode = captureModeFor(
			resolved.descriptor.isUserInitiated,
			resolved.descriptor.isAmbient,
		)
		val reachableRequestedSources = requestedSources.filterTo(linkedSetOf()) { source ->
			rollout.isCaptureReachable(source, captureMode)
		}
		val acceptedSources = resolveAcceptedSources(reachableRequestedSources, resolved.origin)
		if (acceptedSources.isEmpty()) {
			return TrackingStartPreparationResult.Rejected("TRACKING_CAPTURE_UNAVAILABLE")
		}
		val foregroundMask = foregroundServiceTypeMask(Build.VERSION.SDK_INT, acceptedSources)
			?: return TrackingStartPreparationResult.Rejected("TRACKING_FOREGROUND_TYPE_UNAVAILABLE")
		if (automaticExpected && !automaticForegroundEnvelopeMatches(
			trigger = requireNotNull(request.automaticTrigger),
			requestedSources = requestedSources,
			acceptedSources = acceptedSources,
			sdkInt = Build.VERSION.SDK_INT,
		)) return TrackingStartPreparationResult.Rejected(
			"AUTOMATIC_START_FOREGROUND_ENVELOPE_STALE",
		)

		val token = PreparedTrackingStartToken(UUID.randomUUID().toString())
		var roomPrepared = false
		try {
			val ownership = TrackingSessionOwnership.resolve(rollout, settings, captureMode)
			val planInputs = sourcePlanInputs(settings, acceptedSources, resolved.origin, bootId)
			val preparation = sourceSession.prepareAndroidStartUnderReadyGeneration(
				SourceSessionStartRequest(
					rollout = rollout,
					ownership = ownership,
					logicalTrackingId = resolved.descriptor.logicalTrackingId,
					serviceRunId = resolved.descriptor.serviceRunId,
					origin = resolved.origin,
					captureMode = captureMode,
					continuationAuthority = continuationAuthority,
					automaticTrigger = request.automaticTrigger,
					foregroundCapabilityFlags = foregroundMask,
					planInputs = planInputs,
					ownerToken = "prepared-start:${token.value}",
				),
				AndroidStartDeliveryMetadata(
					token = token,
					commandGeneration = request.command.generation,
					isUserInitiated = resolved.descriptor.isUserInitiated,
					isAmbient = resolved.descriptor.isAmbient,
				),
				startupGeneration = startupGeneration,
			)
			when (preparation) {
				is SessionStartPreparationResult.Prepared -> roomPrepared = true
				SessionStartPreparationResult.AlreadyActive ->
					return TrackingStartPreparationResult.AlreadyActive
				SessionStartPreparationResult.Busy ->
					return TrackingStartPreparationResult.Rejected("SESSION_COORDINATOR_BUSY")
				is SessionStartPreparationResult.Rejected ->
					return TrackingStartPreparationResult.Rejected(preparation.failureCode)
			}
			when (val stored = activeTrackingSessionStore.save(resolved.descriptor)) {
				is ActiveTrackingSessionStoreResult.Success -> Unit
				is ActiveTrackingSessionStoreResult.Failure -> {
					compensatePrepared(token, request.command.generation, "ACTIVE_DESCRIPTOR_SAVE_FAILED")
					resolved.previousDescriptor?.let { activeTrackingSessionStore.clearExact(it) }
					return TrackingStartPreparationResult.Rejected("ACTIVE_DESCRIPTOR_SAVE_FAILED")
				}
			}
			if (!trackingStartupGate.isReady ||
				trackingStartupGate.currentGeneration != startupGeneration ||
				bootClockDomainProvider.current() != bootId
			) {
				compensatePrepared(token, request.command.generation, "STARTUP_CLOSED_AFTER_PREPARE")
				clearResolvedDescriptors(resolved)
				return TrackingStartPreparationResult.Rejected("STARTUP_CLOSED_AFTER_PREPARE")
			}
			return TrackingStartPreparationResult.Prepared(
				token = token,
				startupGeneration = startupGeneration,
				preparedSourceMaskHint = sourceMask(acceptedSources),
				preparedStartIsUserInitiatedHint = resolved.descriptor.isUserInitiated,
			)
		} catch (cancelled: CancellationException) {
			if (roomPrepared) try {
				runBoundedStartPreparationCancellationCleanup(request.automaticTrigger) {
					compensatePrepared(token, request.command.generation, "START_PREPARATION_CANCELLED")
					clearResolvedDescriptors(resolved)
				}
			} catch (_: Throwable) {
				// Preserve cancellation; exact PREPARED state is left for startup reconciliation.
			}
			throw cancelled
		} catch (failure: Exception) {
			if (roomPrepared) {
				compensatePrepared(token, request.command.generation, "START_PREPARATION_FAILED")
				clearResolvedDescriptors(resolved)
			}
			return TrackingStartPreparationResult.Rejected(
				failure.message?.takeIf(String::isNotBlank) ?: "START_PREPARATION_FAILED",
			)
		}
	}

	/**
	 * Converts Android's delivery of an already-active prepared token into a distinct recovery
	 * envelope. A still-STARTING token keeps the ordinary exact-claim path; ACTIVE is never claimed
	 * as STARTING or rewritten in place.
	 */
	suspend fun resolveAndroidRedelivery(
		redeliveredToken: PreparedTrackingStartToken,
		redeliveredCommand: TrackingStartCommand,
	): AndroidRedeliveryStartResolution {
		if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) {
			return AndroidRedeliveryStartResolution.Deferred
		}
		val startupGeneration = trackingStartupGate.currentGeneration
		return trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			resolveAndroidRedeliveryUnderReadyGeneration(
				redeliveredToken = redeliveredToken,
				redeliveredCommand = redeliveredCommand,
				startupGeneration = startupGeneration,
			)
		} ?: AndroidRedeliveryStartResolution.Deferred
	}

	private suspend fun resolveAndroidRedeliveryUnderReadyGeneration(
		redeliveredToken: PreparedTrackingStartToken,
		redeliveredCommand: TrackingStartCommand,
		startupGeneration: Long,
	): AndroidRedeliveryStartResolution {
		val run = database.sourceSessionDao().serviceRunByDeliveryToken(redeliveredToken.value)
			?: return AndroidRedeliveryStartResolution.Rejected("REDELIVERED_START_NOT_FOUND")
		if (run.startCommandGeneration != redeliveredCommand.generation) {
			return AndroidRedeliveryStartResolution.Rejected("REDELIVERED_START_COMMAND_MISMATCH")
		}
		val bootId = bootClockDomainProvider.current()
		if (run.state == com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState.STARTING.name &&
			run.completedAtMs == null && run.bootId == bootId
		) {
			return AndroidRedeliveryStartResolution.OriginalPreparedStart(
				redeliveredToken,
				redeliveredCommand,
			)
		}
		val exactAuthority = ServiceRunContinuationAuthority(
			previousServiceRunId = run.serviceRunId,
			previousDeliveryToken = redeliveredToken,
			previousCommandGeneration = redeliveredCommand.generation,
		)
		val storedDescriptor = when (val stored = activeTrackingSessionStore.read()) {
			is ActiveTrackingSessionStoreResult.Failure -> null
			is ActiveTrackingSessionStoreResult.Success -> stored.descriptor
		}
		val descriptorMatchesRun = storedDescriptor != null &&
			storedDescriptor.logicalTrackingId == run.logicalTrackingId &&
			storedDescriptor.serviceRunId == run.serviceRunId &&
			storedDescriptor.isUserInitiated &&
			storedDescriptor.lifecycleState == LogicalTrackingLifecycleState.ACTIVE
		if (run.state != com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState.ACTIVE.name ||
			run.completedAtMs != null || !run.startIsUserInitiated || !descriptorMatchesRun ||
			run.androidDeliveryState !=
				com.adsamcik.tracker.tracker.source.coordinator.AndroidStartDeliveryState
					.FOREGROUND_ACCEPTED.name
		) {
			finalizeRejectedRedelivery(
				run.logicalTrackingId,
				exactAuthority,
				storedDescriptor,
				bootId,
				"REDELIVERY_ACTIVE_ENVELOPE_INVALID",
			)
			return AndroidRedeliveryStartResolution.Rejected("REDELIVERY_ACTIVE_ENVELOPE_INVALID")
		}
		val recoveryCommand = when (
			val reservation = trackingLifecycleCommandAuthority
				.reserveRedeliveryRecoveryStart(redeliveredCommand)
		) {
			is TrackingRedeliveryRecoveryReservation.Reserved -> reservation.command
			TrackingRedeliveryRecoveryReservation.Stale -> {
				finalizeRejectedRedelivery(
					run.logicalTrackingId,
					exactAuthority,
					storedDescriptor,
					bootId,
					"REDELIVERY_COMMAND_STALE",
				)
				return AndroidRedeliveryStartResolution.Rejected("REDELIVERY_COMMAND_STALE")
			}
			is TrackingRedeliveryRecoveryReservation.BlockedByStop ->
				return AndroidRedeliveryStartResolution.BlockedByStop(reservation.stop)
		}
		val preparation = prepareUnderReadyGeneration(
			request = TrackingStartRequest(
				command = recoveryCommand,
				isUserInitiated = true,
				isAmbient = requireNotNull(storedDescriptor).isAmbient,
				recoveryDescriptor = storedDescriptor,
			),
			exactContinuationAuthority = exactAuthority,
			startupGeneration = startupGeneration,
		)
		return when (preparation) {
			is TrackingStartPreparationResult.Prepared -> AndroidRedeliveryStartResolution.Prepared(
				preparation = preparation,
				command = recoveryCommand,
			)
			TrackingStartPreparationResult.AlreadyActive -> {
				finalizeRejectedRedelivery(
					run.logicalTrackingId,
					exactAuthority,
					storedDescriptor,
					bootId,
					"REDELIVERY_RECOVERY_NOT_PREPARED",
				)
				AndroidRedeliveryStartResolution.Rejected("REDELIVERY_RECOVERY_NOT_PREPARED")
			}
			is TrackingStartPreparationResult.Rejected -> {
				val failureCode = "REDELIVERY_RECOVERY_${preparation.failureCode}"
				finalizeRejectedRedelivery(
					run.logicalTrackingId,
					exactAuthority,
					storedDescriptor,
					bootId,
					failureCode,
				)
				AndroidRedeliveryStartResolution.Rejected(failureCode)
			}
		}
	}

	private suspend fun finalizeRejectedRedelivery(
		logicalTrackingId: String,
		authority: ServiceRunContinuationAuthority,
		descriptor: ActiveTrackingSessionDescriptor?,
		bootId: String,
		failureCode: String,
	) {
		val finalized = authoritativeSessionCoordinator.finalizeUnrecoverableContinuation(
			logicalTrackingId = logicalTrackingId,
			authority = authority,
			currentBootId = bootId,
			elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
			wallTimeMs = System.currentTimeMillis(),
			failureCode = failureCode,
		)
		if (finalized && descriptor != null) activeTrackingSessionStore.clearExact(descriptor)
	}

	override suspend fun markAndroidStartEnqueued(
		token: PreparedTrackingStartToken,
		command: com.adsamcik.tracker.tracker.resilience.TrackingStartCommand,
	): Boolean {
		val startupGeneration = trackingStartupGate.currentGeneration
		return trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			authoritativeSessionCoordinator.markAndroidStartEnqueued(
				token,
				command.generation,
				System.currentTimeMillis(),
			)
		} ?: false
	}

	override fun <T> withStartupEnqueuePermit(
		startupGeneration: Long,
		operation: () -> T,
	): T? = trackingStartupGate.withReadyGeneration(startupGeneration, operation)

	override suspend fun compensate(
		token: PreparedTrackingStartToken,
		command: com.adsamcik.tracker.tracker.resilience.TrackingStartCommand,
		failureCode: String,
	) {
		val startupGeneration = trackingStartupGate.currentGeneration
		trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			val run = database.sourceSessionDao().serviceRunByDeliveryToken(token.value)
			val descriptor = when (val stored = activeTrackingSessionStore.read()) {
				is ActiveTrackingSessionStoreResult.Success -> stored.descriptor?.takeIf { current ->
					run != null && current.logicalTrackingId == run.logicalTrackingId &&
						current.serviceRunId == run.serviceRunId
				}
				is ActiveTrackingSessionStoreResult.Failure -> null
			}
			if (compensatePrepared(token, command.generation, failureCode) && descriptor != null) {
				clearPreparedDescriptor(descriptor)
			}
		}
	}

	suspend fun claimForService(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
	): TrackingServicePreparedStartClaim {
		if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) {
			return TrackingServicePreparedStartClaim.Deferred
		}
		val startupGeneration = trackingStartupGate.currentGeneration
		return trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			claimForServiceUnderReadyGeneration(token, commandGeneration, startupGeneration)
		} ?: TrackingServicePreparedStartClaim.Deferred
	}

	private suspend fun claimForServiceUnderReadyGeneration(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
		startupGeneration: Long,
	): TrackingServicePreparedStartClaim {
		val bootId = bootClockDomainProvider.current()
		val claim = when (val result = authoritativeSessionCoordinator.claimAndroidStart(
			token,
			commandGeneration,
			bootId,
			SystemClock.elapsedRealtimeNanos(),
			System.currentTimeMillis(),
		)) {
			is PreparedSessionClaimResult.Claimed -> result.start
			is PreparedSessionClaimResult.Rejected ->
				return TrackingServicePreparedStartClaim.Rejected(result.failureCode)
		}
		val settings = trackingParamsRepository.data.first()
		val automaticExpected = claim.startOrigin == SessionStartOrigin.AUTOMATIC_BACKGROUND_START
		val automationEpoch = if (automaticExpected) {
			activityAutomationEpochAuthority.currentForValidation().epoch
		} else {
			null
		}
		val failure = validateAutomaticStartAtRuntime(
			automaticStartExpected = automaticExpected,
			trigger = claim.automaticTrigger,
			currentBootId = bootId,
			currentElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
			currentPolicyRevision = settings.sourcePolicyRevision,
			currentAutomationEpoch = automationEpoch,
			hasActivityPermission = context.hasActivityPermission,
		) ?: if (settings.sourcePolicyRevision != claim.sourcePolicyRevision) {
			"PREPARED_START_SOURCE_POLICY_STALE"
		} else {
			null
		}
		val requestedSources = if (automaticExpected) {
			claim.automaticTrigger?.let { sourceKindsFromMask(it.requestedCaptureSourceMask) }
		} else {
			configuredForegroundSources(settings)
		}
		val rollout = try {
			trackingRolloutStateStore.load()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			compensatePrepared(token, commandGeneration, "PREPARED_START_ROLLOUT_UNAVAILABLE")
			return TrackingServicePreparedStartClaim.Rejected(
				"PREPARED_START_ROLLOUT_UNAVAILABLE",
			)
		}
		val preparedRolloutRevision = database.sourceSessionDao()
			.serviceRun(claim.serviceRunId)
			?.rolloutRevision
		val captureMode = captureModeFor(claim.isUserInitiated, claim.isAmbient)
		val acceptedSources = requestedSources?.let { requested ->
			resolveAcceptedSources(
				requested.filterTo(linkedSetOf()) { source ->
					rollout.isCaptureReachable(source, captureMode)
				},
				claim.startOrigin,
			)
		}
		val expectedMask = acceptedSources?.let {
			foregroundServiceTypeMask(Build.VERSION.SDK_INT, it)
		}
		val envelopeFailure = failure ?: when {
			!trackingStartupGate.isReady || trackingStartupGate.currentGeneration != startupGeneration ->
				"TRACKING_STARTUP_GENERATION_CLOSED"
			bootClockDomainProvider.current() != bootId -> "PREPARED_START_BOOT_CHANGED"
			preparedRolloutRevision == null || rollout.revision != preparedRolloutRevision ->
				"PREPARED_START_ROLLOUT_STALE"
			requestedSources == null -> "PREPARED_START_REQUESTED_MASK_INVALID"
			acceptedSources != claim.acceptedSources -> "PREPARED_START_CAPABILITIES_CHANGED"
			expectedMask != claim.desiredForegroundCapabilityFlags -> "PREPARED_START_FGS_MASK_CHANGED"
			else -> null
		}
		if (envelopeFailure != null) {
			compensatePrepared(token, commandGeneration, envelopeFailure)
			return TrackingServicePreparedStartClaim.Rejected(envelopeFailure)
		}
		val descriptor = when (val stored = activeTrackingSessionStore.read()) {
			is ActiveTrackingSessionStoreResult.Failure -> null
			is ActiveTrackingSessionStoreResult.Success -> stored.descriptor?.takeIf { current ->
				current.logicalTrackingId == claim.logicalTrackingId &&
					current.serviceRunId == claim.serviceRunId &&
					current.isUserInitiated == claim.isUserInitiated &&
					current.isAmbient == claim.isAmbient
			}
		}
		if (descriptor == null) {
			compensatePrepared(token, commandGeneration, "PREPARED_START_DESCRIPTOR_MISSING")
			return TrackingServicePreparedStartClaim.Rejected("PREPARED_START_DESCRIPTOR_MISSING")
		}
		return TrackingServicePreparedStartClaim.Claimed(
			claim = claim,
			descriptor = descriptor,
			settings = settings,
			planInputs = sourcePlanInputs(
				settings,
				requireNotNull(acceptedSources),
				claim.startOrigin,
				bootId,
			),
			startupGeneration = startupGeneration,
		)
	}

	suspend fun markForegroundAccepted(
		claim: ClaimedPreparedSessionStart,
		commandGeneration: Long,
		startupGeneration: Long,
	): Boolean = trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
		authoritativeSessionCoordinator.markPreparedForegroundAccepted(
			claim.token,
			commandGeneration,
			bootClockDomainProvider.current(),
			SystemClock.elapsedRealtimeNanos(),
			System.currentTimeMillis(),
		)
	} ?: false

	suspend fun apply(
		prepared: TrackingServicePreparedStartClaim.Claimed,
		commandGeneration: Long,
	): SessionStartResult? = trackingStartupGate.withReadyGenerationOperation(
		prepared.startupGeneration,
	) {
		sourceSession.applyPreparedAndroidStart(
			prepared.claim,
			commandGeneration,
			prepared.planInputs,
		)
	}

	private suspend fun resolveDescriptor(
		request: TrackingStartRequest,
		bootId: String,
	): TrackingStartDescriptorResolution {
		val stored = when (val result = activeTrackingSessionStore.read()) {
			is ActiveTrackingSessionStoreResult.Failure ->
				return TrackingStartDescriptorResolution.Failure("ACTIVE_DESCRIPTOR_READ_FAILED")
			is ActiveTrackingSessionStoreResult.Success -> result.descriptor
		}
		return resolveTrackingStartDescriptor(
			request = request,
			stored = stored,
			bootId = bootId,
			changedAtEpochMs = System.currentTimeMillis(),
		)
	}

	private suspend fun compensatePrepared(
		token: PreparedTrackingStartToken,
		commandGeneration: Long,
		failureCode: String,
	): Boolean = authoritativeSessionCoordinator.compensatePreparedAndroidStart(
		token,
		commandGeneration,
		failureCode,
		bootClockDomainProvider.current(),
		SystemClock.elapsedRealtimeNanos(),
		System.currentTimeMillis(),
	)

	private suspend fun clearPreparedDescriptor(descriptor: ActiveTrackingSessionDescriptor) {
		activeTrackingSessionStore.clearExact(descriptor)
	}

	private suspend fun clearResolvedDescriptors(
		resolved: TrackingStartDescriptorResolution.Resolved,
	) {
		clearPreparedDescriptor(resolved.descriptor)
		resolved.previousDescriptor?.let { clearPreparedDescriptor(it) }
	}

	private fun resolveAcceptedSources(
		requestedSources: Set<SourceKind>,
		origin: SessionStartOrigin,
	): Set<SourceKind> = acceptedForegroundSources(
		requestedSources,
		context.foregroundSourceCapabilities(origin),
	)

	private suspend fun sourcePlanInputs(
		settings: TrackingParamsState,
		acceptedSources: Set<SourceKind>,
		startOrigin: SessionStartOrigin,
		bootId: String,
	): SourceSessionPlanInputs {
		val packageManager = context.packageManager
		val locationFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
		val wifiFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
		val cellFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))
		val constraints = mapOf(
			SourceKind.LOCATION to SourceConstraint(
				hardwareAvailable = locationFeature,
				permissionGranted = context.hasLocationPermission,
				foregroundCapabilityLegal = SourceKind.LOCATION in acceptedSources,
				backgroundStartLegal = isLocationSourceLegalForStartOrigin(
					Build.VERSION.SDK_INT,
					startOrigin,
					context.hasLocationPermission,
					context.hasBackgroundLocationPermission,
				),
			),
			SourceKind.ACTIVITY to SourceConstraint(
				hardwareAvailable = Assist.isPlayServicesAvailable(context),
				permissionGranted = context.hasActivityPermission,
				foregroundCapabilityLegal = SourceKind.ACTIVITY in acceptedSources,
			),
			SourceKind.STEPS to SourceConstraint(
				hardwareAvailable = context.hasStepCounterSensor,
				permissionGranted = context.hasActivityPermission,
				foregroundCapabilityLegal = SourceKind.STEPS in acceptedSources,
			),
			SourceKind.PRESSURE to SourceConstraint(
				hardwareAvailable = context.hasPressureSensor,
				foregroundCapabilityLegal = SourceKind.PRESSURE in acceptedSources,
			),
			SourceKind.WIFI to SourceConstraint(
				hardwareAvailable = wifiFeature,
				permissionGranted = context.hasWifiScanPermission,
				foregroundCapabilityLegal = SourceKind.WIFI in acceptedSources,
			),
			SourceKind.CELL to SourceConstraint(
				hardwareAvailable = cellFeature,
				permissionGranted = context.hasCellScanPermission,
				foregroundCapabilityLegal = SourceKind.CELL in acceptedSources,
			),
		)
		return SourceSessionPlanInputs(
			settings = settings,
			environment = SourcePlanEnvironment(
				locationBackend = TrackerTimerManager.getSelectedLocationBackend(context),
				preciseLocationAvailable = ContextCompat.checkSelfPermission(
					context,
					Manifest.permission.ACCESS_FINE_LOCATION,
				) == PackageManager.PERMISSION_GRANTED,
				subscriptionIds = emptySet(),
			),
			resolutionContext = PlanResolutionContext(
				constraints = constraints,
				powerSaver = powerManager.isPowerSaveMode,
				doze = powerManager.isDeviceIdleMode,
				severeThermalPressure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
					powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
				motionProfile = collectionMotionController.policy.value.acquisitionProfile,
			),
			demands = emptyList(),
			clockDomainId = bootId,
		)
	}

}

internal sealed interface TrackingStartDescriptorResolution {
	data class Resolved(
		val descriptor: ActiveTrackingSessionDescriptor,
		val origin: SessionStartOrigin,
		val previousDescriptor: ActiveTrackingSessionDescriptor? = null,
		val continuationAuthority: ServiceRunContinuationAuthority? = null,
	) : TrackingStartDescriptorResolution

	data class Failure(val code: String) : TrackingStartDescriptorResolution
	data object AlreadyActive : TrackingStartDescriptorResolution
}

/** Resolves logical continuity independently from the actual Android start origin. */
internal fun resolveTrackingStartDescriptor(
	request: TrackingStartRequest,
	stored: ActiveTrackingSessionDescriptor?,
	bootId: String,
	changedAtEpochMs: Long,
): TrackingStartDescriptorResolution {
	val explicitRecovery = request.recoveryDescriptor
	if (explicitRecovery != null) {
		if (stored != explicitRecovery || !explicitRecovery.isRestartEligibleForBoot(bootId)) {
			return TrackingStartDescriptorResolution.Failure("RECOVERY_DESCRIPTOR_STALE")
		}
		return TrackingStartDescriptorResolution.Resolved(
			descriptor = explicitRecovery.forNewServiceRun(changedAtEpochMs),
			origin = SessionStartOrigin.RECOVERY,
			previousDescriptor = explicitRecovery,
			continuationAuthority = ServiceRunContinuationAuthority(explicitRecovery.serviceRunId),
		)
	}
	if (request.isUserInitiated && stored?.isRestartEligibleForBoot(bootId) == true) {
		return TrackingStartDescriptorResolution.Resolved(
			descriptor = stored.forNewServiceRun(changedAtEpochMs),
			origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
			previousDescriptor = stored,
			continuationAuthority = ServiceRunContinuationAuthority(stored.serviceRunId),
		)
	}
	if (stored != null) return TrackingStartDescriptorResolution.AlreadyActive
	if (!request.isUserInitiated && request.automaticTrigger == null) {
		return TrackingStartDescriptorResolution.Failure(AUTOMATIC_START_TRIGGER_MISSING)
	}
	return TrackingStartDescriptorResolution.Resolved(
		descriptor = ActiveTrackingSessionDescriptor(
			isUserInitiated = request.isUserInitiated,
			isAmbient = request.isAmbient,
			policyTier = PolicyTier.OFF,
			restartBootId = bootId,
			restartToken = UUID.randomUUID().toString(),
		),
		origin = if (request.isUserInitiated) {
			SessionStartOrigin.MANUAL_FOREGROUND_START
		} else {
			SessionStartOrigin.AUTOMATIC_BACKGROUND_START
		},
	)
}

/**
 * Runs cancellation compensation only inside the Activity callback's absolute cleanup reserve.
 * A timeout intentionally leaves exact PREPARED state and its descriptor to startup reconciliation.
 */
internal suspend fun runBoundedStartPreparationCancellationCleanup(
	automaticTrigger: AutomaticTrackingStartTrigger?,
	elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
	cleanup: suspend () -> Unit,
): Boolean {
	val timeoutMs = automaticTrigger?.let { trigger ->
		val deadline = activityTransitionCallbackCleanupDeadlineElapsedRealtimeNanos(trigger)
		val now = elapsedRealtimeNanos()
		if (deadline <= now) 0L else (deadline - now) / START_CLEANUP_NANOS_PER_MILLISECOND
	} ?: DEFAULT_START_PREPARATION_CLEANUP_TIMEOUT_MS
	if (timeoutMs <= 0L) return false
	return withContext(NonCancellable) {
		withTimeoutOrNull(timeoutMs) {
			cleanup()
			true
		} ?: false
	}
}

private const val START_CLEANUP_NANOS_PER_MILLISECOND = 1_000_000L
private const val DEFAULT_START_PREPARATION_CLEANUP_TIMEOUT_MS = 250L

internal sealed interface AndroidRedeliveryStartResolution {
	/** Startup/deletion authority is not stable yet; the exact token must remain untouched. */
	data object Deferred : AndroidRedeliveryStartResolution

	data class OriginalPreparedStart(
		val token: PreparedTrackingStartToken,
		val command: TrackingStartCommand,
	) : AndroidRedeliveryStartResolution

	data class Prepared(
		val preparation: TrackingStartPreparationResult.Prepared,
		val command: TrackingStartCommand,
	) : AndroidRedeliveryStartResolution

	data class BlockedByStop(val stop: TrackingStopCommand) : AndroidRedeliveryStartResolution
	data class Rejected(val failureCode: String) : AndroidRedeliveryStartResolution
}

internal sealed interface TrackingServicePreparedStartClaim {
	/** Startup/deletion authority changed before claim; the exact prepared start remains durable. */
	data object Deferred : TrackingServicePreparedStartClaim

	data class Claimed(
		val claim: ClaimedPreparedSessionStart,
		val descriptor: ActiveTrackingSessionDescriptor,
		val settings: TrackingParamsState,
		val planInputs: SourceSessionPlanInputs,
		val startupGeneration: Long,
	) : TrackingServicePreparedStartClaim

	data class Rejected(val failureCode: String) : TrackingServicePreparedStartClaim
}
