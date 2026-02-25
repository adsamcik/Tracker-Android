package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.engine.achievement.AchievementEvaluator

/**
 * SignalProcessor wrapping [AchievementEvaluator].
 * Evaluates accumulated metrics against achievements and emits unlock events.
 *
 * This is a "consumer" processor — it does not process raw signals but rather
 * reacts to accumulated metrics from other processors (via the metric provider).
 */
class AchievementProcessor(
	private val evaluator: AchievementEvaluator = AchievementEvaluator(),
	private val metricsProvider: () -> Map<String, Long> = { emptyMap() },
) : SignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = "achievement",
		requiredTier = PolicyTier.AMBIENT,
		flushIntervalMs = 60_000L,
		priority = 100, // Run after all other processors
	)

	private val pendingEvents = mutableListOf<DomainEvent>()
	private val previousProgress = mutableMapOf<String, Pair<Long, com.adsamcik.tracker.stats.api.AchievementTier?>>()

	override suspend fun onStart(context: ProcessorContext) {
		pendingEvents.clear()
		previousProgress.clear()
	}

	override fun onSignal(signal: TrackingSignal) {
		// Achievement processor doesn't process raw signals
	}

	override suspend fun onFlush(): List<DomainEvent> {
		val metrics = metricsProvider()
		val now = EpochMs(com.adsamcik.tracker.stats.api.platform.currentTimeMillis())

		for ((metric, value) in metrics) {
			val snapshots = evaluator.evaluate(metric, value, previousProgress)
			for (snap in snapshots) {
				previousProgress[snap.definition.id] = Pair(snap.currentValue, snap.currentTier)

				if (snap.currentTier != null) {
					pendingEvents.add(
						DomainEvent.AchievementUnlocked(
							timestampMs = now,
							processorId = descriptor.id,
							achievementId = snap.definition.id,
							tier = snap.currentTier!!.name,
						),
					)
				} else {
					pendingEvents.add(
						DomainEvent.AchievementProgress(
							timestampMs = now,
							processorId = descriptor.id,
							achievementId = snap.definition.id,
							currentValue = snap.currentValue,
							targetValue = snap.nextTierTarget ?: 0L,
						),
					)
				}
			}
		}

		val events = pendingEvents.toList()
		pendingEvents.clear()
		return events
	}

	override suspend fun onStop(): List<DomainEvent> = onFlush()

	override fun checkpoint(): ByteArray {
		val baos = java.io.ByteArrayOutputStream()
		val dos = java.io.DataOutputStream(baos)
		dos.writeInt(previousProgress.size)
		for ((key, pair) in previousProgress) {
			dos.writeUTF(key)
			dos.writeLong(pair.first)
			dos.writeBoolean(pair.second != null)
			if (pair.second != null) dos.writeInt(pair.second!!.ordinal)
		}
		dos.flush()
		return baos.toByteArray()
	}

	override fun restore(state: ByteArray) {
		val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(state))
		previousProgress.clear()
		val size = dis.readInt()
		repeat(size) {
			val key = dis.readUTF()
			val value = dis.readLong()
			val hasTier = dis.readBoolean()
			val tier = if (hasTier) com.adsamcik.tracker.stats.api.AchievementTier.entries[dis.readInt()] else null
			previousProgress[key] = Pair(value, tier)
		}
	}
}
