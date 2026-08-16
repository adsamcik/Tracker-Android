package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorTelemetry
import com.adsamcik.tracker.tracker.source.coordinator.WakeupPlanner
import com.adsamcik.tracker.tracker.source.coordinator.WakeupPriority
import com.adsamcik.tracker.tracker.source.coordinator.WakeupRequest
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal fun interface ElapsedRealtimeClock {
	fun nowMs(): Long
}

internal object AndroidElapsedRealtimeClock : ElapsedRealtimeClock {
	override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

internal interface SourceWakeupScheduler {
	suspend fun schedule(request: WakeupRequest, action: suspend () -> Unit)
	suspend fun cancel(id: String)
}

/**
 * One in-process timer for every source-native polling attempt while TrackerService is active.
 * It deliberately does not claim to wake a suspended CPU; the service wake lock and Android
 * provider callbacks define that boundary.
 */
@Singleton
internal class CoalescingSourceWakeupScheduler @Inject constructor(
	private val planner: WakeupPlanner,
	@ApplicationScope private val scope: CoroutineScope,
	private val telemetry: TrackingCoordinatorTelemetry,
) : SourceWakeupScheduler {
	private val mutex = Mutex()
	private val tasks = linkedMapOf<String, ScheduledSourceTask>()
	private var driver: Job? = null

	override suspend fun schedule(request: WakeupRequest, action: suspend () -> Unit) {
		mutex.withLock {
			tasks[request.id] = ScheduledSourceTask(request, action)
			restartDriverLocked()
		}
	}

	override suspend fun cancel(id: String) {
		mutex.withLock {
			tasks.remove(id)
			restartDriverLocked()
		}
	}

	private fun restartDriverLocked() {
		driver?.cancel()
		driver = null
		val next = planner.plan(tasks.values.map(ScheduledSourceTask::request), clock.nowMs()).firstOrNull()
			?: return
		driver = scope.launch {
			delay((next.executeAtElapsedRealtimeMs - clock.nowMs()).coerceAtLeast(0L))
			val due = mutex.withLock {
				driver = null
				val planned = planner.plan(
					tasks.values.map(ScheduledSourceTask::request),
					clock.nowMs(),
				).firstOrNull()
				val ready = planned?.requests.orEmpty().mapNotNull { tasks.remove(it.id) }
				restartDriverLocked()
				ready
			}
			if (due.isNotEmpty()) telemetry.recordSourceTimerWakeup(due.size)
			due.forEach { task -> scope.launch { task.action() } }
		}
	}

	internal companion object {
		var clock: ElapsedRealtimeClock = AndroidElapsedRealtimeClock
	}
}

private data class ScheduledSourceTask(
	val request: WakeupRequest,
	val action: suspend () -> Unit,
)

internal fun sourceWakeupRequest(
	id: String,
	source: SourceKind,
	earliestElapsedRealtimeMs: Long,
	windowMs: Long,
) = WakeupRequest(
	id = id,
	source = source,
	earliestElapsedRealtimeMs = earliestElapsedRealtimeMs,
	latestElapsedRealtimeMs = earliestElapsedRealtimeMs + windowMs.coerceAtLeast(0L),
	priority = WakeupPriority.OPPORTUNISTIC,
)
