package com.adsamcik.tracker.stats.data.repository

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Dormant integration-test seam for causal history-read assertions.
 *
 * Production reads retain no counters unless one explicit observation is active.
 */
@Singleton
public class TrackingHistoryIntegrationObserver @Inject constructor() {
	private val active = AtomicReference<MutableStateFlow<TrackingHistoryIntegrationSnapshot>?>(null)

	public fun startObservation(): TrackingHistoryIntegrationObservation {
		val state = MutableStateFlow(TrackingHistoryIntegrationSnapshot(emptyMap()))
		check(active.compareAndSet(null, state)) {
			"Only one tracking-history integration observation may be active"
		}
		return TrackingHistoryIntegrationObservation(this, state)
	}

	internal fun record(checkpoint: TrackingHistoryIntegrationCheckpoint) {
		active.get()?.update { snapshot -> snapshot.increment(checkpoint) }
	}

	internal fun stop(state: MutableStateFlow<TrackingHistoryIntegrationSnapshot>) {
		active.compareAndSet(state, null)
	}
}

public class TrackingHistoryIntegrationObservation internal constructor(
	private val owner: TrackingHistoryIntegrationObserver,
	private val state: MutableStateFlow<TrackingHistoryIntegrationSnapshot>,
) : AutoCloseable {
	private val closed = AtomicBoolean(false)

	public val snapshots: StateFlow<TrackingHistoryIntegrationSnapshot> = state.asStateFlow()

	public override fun close(): Unit {
		if (closed.compareAndSet(false, true)) {
			owner.stop(state)
		}
	}
}

public class TrackingHistoryIntegrationSnapshot internal constructor(
	private val counts: Map<TrackingHistoryIntegrationCheckpoint, Long>,
) {
	public fun count(checkpoint: TrackingHistoryIntegrationCheckpoint): Long =
		counts[checkpoint] ?: 0L

	internal fun increment(
		checkpoint: TrackingHistoryIntegrationCheckpoint,
	): TrackingHistoryIntegrationSnapshot = TrackingHistoryIntegrationSnapshot(
		counts + (checkpoint to Math.addExact(count(checkpoint), 1L)),
	)
}

public enum class TrackingHistoryIntegrationCheckpoint {
	SOURCE_AWARE_READ_STARTED,
	SOURCE_AWARE_READ_FINISHED,
	SOURCE_AWARE_LIFECYCLE_INVALIDATION_READ,
	PRESSURE_RECENCY_PREFLIGHT,
	PRESSURE_IMPORTED_CANDIDATE_PAGE,
	PRESSURE_IMPORTED_DETAIL,
	PRESSURE_LOCAL_DUPLICATE_SELECTOR,
	PRESSURE_LIVE_SELECTOR,
}
