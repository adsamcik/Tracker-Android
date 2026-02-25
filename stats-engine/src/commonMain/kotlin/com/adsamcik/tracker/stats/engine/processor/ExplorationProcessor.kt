package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.engine.exploration.CellDiscoveryEngine

/**
 * SignalProcessor wrapping [CellDiscoveryEngine].
 * Discovers S2 cells from location signals and emits CellDiscovered domain events.
 */
class ExplorationProcessor(
	private val engine: CellDiscoveryEngine = CellDiscoveryEngine(),
) : SignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = "exploration",
		requiredTier = PolicyTier.ACTIVE,
		flushIntervalMs = 60_000L,
		priority = 30,
	)

	private val pendingEvents = mutableListOf<DomainEvent>()

	override suspend fun onStart(context: ProcessorContext) {
		if (context.checkpoint != null) {
			restore(context.checkpoint!!)
		}
	}

	override fun onSignal(signal: TrackingSignal) {
		val location = signal.location ?: return

		val discovery = engine.onLocation(
			latDeg = location.coordinate.lat.toDegrees(),
			lngDeg = location.coordinate.lon.toDegrees(),
			accuracyM = location.horizontalAccuracyM,
			timestampMs = signal.timestampMs.raw,
		)

		if (discovery != null && discovery.isNew) {
			pendingEvents.add(
				DomainEvent.CellDiscovered(
					timestampMs = signal.timestampMs,
					processorId = descriptor.id,
					cellToken = discovery.token,
					level = discovery.level,
				),
			)
		}
	}

	override suspend fun onFlush(): List<DomainEvent> {
		val events = pendingEvents.toList()
		pendingEvents.clear()
		return events
	}

	override suspend fun onStop(): List<DomainEvent> {
		engine.finalize(com.adsamcik.tracker.stats.api.platform.currentTimeMillis())
		return onFlush()
	}

	override fun checkpoint(): ByteArray = engine.serialize()
	override fun restore(state: ByteArray) { engine.deserialize(state) }
}
