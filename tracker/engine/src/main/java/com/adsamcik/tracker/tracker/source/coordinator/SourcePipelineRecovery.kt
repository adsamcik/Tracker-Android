package com.adsamcik.tracker.tracker.source.coordinator

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjectionLane
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationStartPermit
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecovery
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecoveryResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

@Singleton
class SourcePipelineRecovery private constructor(
	private val legacyRecovery: LegacyV27ProjectionRecovery,
	private val coordinator: TrackingCoordinator,
	private val activityProjectionLane: ActivityAutomationProjectionLane,
	private val activityEffects: ActivityAutomationOutboxDispatcher,
	applicationScope: CoroutineScope?,
	@Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
	private val legacyMutex = Mutex()
	private val activityEffectMutex = Mutex()
	private val committedWorkSignals = Channel<Unit>(Channel.CONFLATED)
	@Volatile private var terminalLegacyResult: LegacyV27ProjectionRecoveryResult? = null

	@Inject
	constructor(
		legacyRecovery: LegacyV27ProjectionRecovery,
		coordinator: TrackingCoordinator,
		activityProjectionLane: ActivityAutomationProjectionLane,
		activityEffects: ActivityAutomationOutboxDispatcher,
		@ApplicationScope applicationScope: CoroutineScope,
	) : this(
		legacyRecovery,
		coordinator,
		activityProjectionLane,
		activityEffects,
		applicationScope,
		Unit,
	)

	internal constructor(
		legacyRecovery: LegacyV27ProjectionRecovery,
		coordinator: TrackingCoordinator,
		activityProjectionLane: ActivityAutomationProjectionLane,
		activityEffects: ActivityAutomationOutboxDispatcher,
	) : this(
		legacyRecovery,
		coordinator,
		activityProjectionLane,
		activityEffects,
		null,
		Unit,
	)

	init {
		applicationScope?.launch {
			for (ignored in committedWorkSignals) {
				// The WAL/outbox is the durable retry owner. A failed hint is retried by the next
				// admission or normal startup/maintenance recovery; never spin in process.
				runCatchingNonCancellation { drainCommittedWork() }
			}
		}
	}

	/** Process-local hint only; the durable outbox remains the source of truth. */
	val activityAutomationDrainRequired: StateFlow<Boolean>
		get() = activityEffects.drainRequired

	/**
	 * Conflated process-local hint. Provider actors return immediately after the Room commit instead
	 * of awaiting unrelated projection/effect work; the committed WAL remains authoritative.
	 */
	fun requestCommittedWorkDrain() {
		committedWorkSignals.trySend(Unit)
	}

	/** Recovers durable projections only; it deliberately cannot invoke application consumers. */
	suspend fun recoverDurableState(): SourceRecoveryResult {
		val legacyResult = recoverLegacyOncePerProcess()
		if (legacyResult !is LegacyV27ProjectionRecoveryResult.NotRequired &&
			legacyResult !is LegacyV27ProjectionRecoveryResult.Complete
		) {
			throw LegacyV27ProjectionRecoveryNotReadyException(legacyResult)
		}
		val owner = "source-recovery:${UUID.randomUUID()}"
		val drain = coordinator.drainAvailable(owner)
		return SourceRecoveryResult(
			drain = drain,
			activityEffectsDelivered = 0,
			trackingFramesDelivered = 0,
			legacyRecovery = legacyResult,
		)
	}

	/** Drains the live Activity automation effects after their owner has declared readiness. */
	suspend fun drainCommittedWork(): SourceRecoveryResult = activityEffectMutex.withLock {
		drainCommittedWorkLocked(ActivityAutomationStartPermit.None)
	}

	/**
	 * The exact Activity callback path may spend its Android transition-callback start exemption only
	 * on effects admitted by that callback. Holding [activityEffectMutex] before projection prevents
	 * the app replay driver from consuming those new effects with a weaker context first.
	 */
	suspend fun drainCommittedActivityCallbackWork(
		callbackAdmissionOrdinals: Set<Long>,
		throughAdmissionOrdinal: Long,
		awaitAuthorityReady: suspend () -> Unit,
	): SourceRecoveryResult = drainCommittedActivityCallbackWork(
		callbackAdmissionOrdinals,
		throughAdmissionOrdinal,
		SystemClock::elapsedRealtimeNanos,
		awaitAuthorityReady,
	)

	internal suspend fun drainCommittedActivityCallbackWork(
		callbackAdmissionOrdinals: Set<Long>,
		throughAdmissionOrdinal: Long,
		elapsedRealtimeNanos: () -> Long,
		awaitAuthorityReady: suspend () -> Unit,
	): SourceRecoveryResult = activityEffectMutex.withLock {
		require(callbackAdmissionOrdinals.isNotEmpty())
		require(throughAdmissionOrdinal >= callbackAdmissionOrdinals.max())
		val durable = SourceRecoveryResult(
			drain = activityProjectionLane.drainThrough(throughAdmissionOrdinal),
			activityEffectsDelivered = 0,
			trackingFramesDelivered = 0,
			legacyRecovery = LegacyV27ProjectionRecoveryResult.NotRequired,
		)
		if (durable.drain !is CoordinatorDrainResult.Complete) return@withLock durable
		val permit = ActivityAutomationStartPermit.FreshTransitionCallback(
			callbackAdmissionOrdinals,
		)
		var delivered = 0
		suspend fun drainBoundedPages(): ActivityAutomationDrainResult {
			while (true) {
				val pass = activityEffects.drainToQuiescence(
					startPermit = permit,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
				)
				delivered += pass.deliveredCount
				if (pass !is ActivityAutomationDrainResult.MorePending) return pass
				// The BroadcastReceiver's outer timeout is the total callback budget.
				yield()
			}
		}
		var activityDrain = drainBoundedPages()
		if (activityDrain is ActivityAutomationDrainResult.Retryable) {
			// Keep the exact callback permit reserved while cold-process policy collectors become
			// coherent. Cancellation is bounded by ActivityReceiver and leaves the outbox pending.
			awaitAuthorityReady()
			activityDrain = drainBoundedPages()
		}
		durable.copy(
			activityEffectsDelivered = delivered,
			activityAutomationDrain = activityDrain,
			// Tracking-frame publication is unrelated to the live Android-start exemption. Its
			// durable v2 outbox remains pending under containment.
			trackingFramesDelivered = 0,
		)
	}

	private suspend fun drainCommittedWorkLocked(
		startPermit: ActivityAutomationStartPermit,
	): SourceRecoveryResult {
		val durable = recoverDurableState()
		if (durable.drain !is CoordinatorDrainResult.Complete) return durable
		return durable.copy(
			activityEffectsDelivered = activityEffects.drain(startPermit = startPermit),
			// The v2 tracking-frame outbox is containment-only durable state. The legacy cycle pipeline
			// remains authoritative until a typed exactly-once handoff replaces this boundary.
			trackingFramesDelivered = 0,
		)
	}

	/**
	 * App-owned post-authority drain. The startup gate must already have completed durable recovery;
	 * this method therefore invokes only the Activity automation consumer and cannot race a second
	 * legacy/current projection recovery pass into application effects.
	 */
	suspend fun drainActivityAutomationEffects(): ActivityAutomationDrainResult =
		activityEffectMutex.withLock {
			activityEffects.drainToQuiescence(
				startPermit = ActivityAutomationStartPermit.None,
			)
		}

	private suspend fun recoverLegacyOncePerProcess(): LegacyV27ProjectionRecoveryResult {
		terminalLegacyResult?.let { return it }
		return legacyMutex.withLock {
			terminalLegacyResult ?: legacyRecovery.recover().also { result ->
				if (result is LegacyV27ProjectionRecoveryResult.NotRequired ||
					result is LegacyV27ProjectionRecoveryResult.Complete
				) terminalLegacyResult = result
			}
		}
	}
}

data class SourceRecoveryResult(
	val drain: CoordinatorDrainResult,
	val activityEffectsDelivered: Int,
	val trackingFramesDelivered: Int,
	val legacyRecovery: LegacyV27ProjectionRecoveryResult =
		LegacyV27ProjectionRecoveryResult.NotRequired,
	val activityAutomationDrain: ActivityAutomationDrainResult? = null,
)

class LegacyV27ProjectionRecoveryNotReadyException(
	val result: LegacyV27ProjectionRecoveryResult,
) : IllegalStateException("Released-v27 projection recovery is not terminal: $result")
