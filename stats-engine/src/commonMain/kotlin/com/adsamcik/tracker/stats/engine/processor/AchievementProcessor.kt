package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.achievement.AchievementEvaluator

/**
 * SignalProcessor wrapping [AchievementEvaluator].
 *
 * Tracks in-memory progress for live UI updates during a tracking session.
 * Emits [DomainEvent.AchievementUnlocked] when a tier increases and
 * [DomainEvent.AchievementProgress] when the value changes within the same tier.
 *
 * **Battery optimization:** when a [MetricDirtyTracker] is provided and no
 * pre-aggregated table (or the streaming aggregator state) has been written to
 * since the previous flush, [onFlush] returns an empty list IMMEDIATELY without
 * calling [metricsProvider] — so 0 DB queries, 0 snapshots, 0 events. This makes
 * the 60s achievement flush essentially free on idle AMBIENT-tier passes (the
 * common case during stationary tracking).
 *
 * Legacy callers that don't pass a dirty tracker get the old always-evaluate behaviour.
 *
 * **Migration status:** still uses the legacy [AchievementEvaluator] rather than
 * the unified `RuleEvaluator`. The unified evaluator's
 * `RuleEvaluationResult.TierUnlocked` / `ProgressUpdated` variants do not yet
 * carry the snapshot data (`nextTierTarget`, current/next tier ids) this processor
 * emits in [DomainEvent.AchievementProgress]. Migration requires either extending
 * the unified result contract or moving snapshot lookup into a per-domain result
 * handler.
 */
class AchievementProcessor(
	private val evaluator: AchievementEvaluator = AchievementEvaluator(),
	private val metricsProvider: () -> Map<String, Long> = { emptyMap() },
	private val dirtyTracker: MetricDirtyTracker? = null,
) : SignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = "achievement",
		requiredTier = PolicyTier.AMBIENT,
		flushIntervalMs = 60_000L,
		priority = 100, // Run after all other processors
	)

	private val previousProgress = mutableMapOf<String, Pair<Long, com.adsamcik.tracker.stats.api.AchievementTier?>>()

	override suspend fun onStart(context: ProcessorContext) {
		previousProgress.clear()
	}

	override fun onSignal(signal: TrackingSignal) {
		// Achievement processor doesn't process raw signals
	}

	override suspend fun onFlush(): List<DomainEvent> = runEvaluation(force = false)

	/**
	 * Stop-time final evaluation: bypass the dirty short-circuit so a periodic flush
	 * consuming the dirty bit moments before stop doesn't suppress final session
	 * achievement/progress events.
	 */
	override suspend fun onStop(): List<DomainEvent> = runEvaluation(force = true)

	private suspend fun runEvaluation(force: Boolean): List<DomainEvent> {
		// Battery-critical short-circuit: if no pre-aggregated table has changed since the
		// previous flush, we KNOW every metric value is stable, so achievements can't have
		// moved. Skip the full evaluation. consumeDirty() is an atomic swap so writes that
		// race this check land in the NEXT flush window — never lost.
		// `force = true` is used by onStop() so the final session evaluation doesn't
		// short-circuit when a periodic flush just consumed the dirty bit moments earlier.
		val tracker = dirtyTracker
		val consumed: Set<String>? = if (tracker != null && !force) {
			val set = tracker.consumeDirty()
			if (set.isEmpty()) return emptyList()
			set
		} else if (tracker != null && force) {
			// In force mode we still drain the dirty set so the next flush starts clean,
			// but we do NOT early-return on empty.
			tracker.consumeDirty()
		} else {
			null
		}

		// Re-mark consumed tables on ANY non-cancellation failure so we don't silently
		// drop the next update. Without this, an exception in metricsProvider() or
		// anywhere inside the evaluation loop would leave the dirty set empty and the
		// next ordinary flush would short-circuit even though the underlying tables had
		// changed. Cancellation must still propagate (structured concurrency).
		val events = try {
			evaluateAllMetrics()
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (t: Throwable) {
			if (tracker != null && consumed != null && consumed.isNotEmpty()) {
				tracker.markDirty(consumed)
			}
			throw t
		}
		return events
	}

	private fun evaluateAllMetrics(): List<DomainEvent> {
		val metrics = metricsProvider()
		val now = EpochMs(com.adsamcik.tracker.stats.api.platform.currentTimeMillis())
		val events = mutableListOf<DomainEvent>()

		for ((metric, value) in metrics) {
			val snapshots = evaluator.snapshots(metric, value)
			for (snap in snapshots) {
				val previous = previousProgress[snap.definition.id]
				val previousValue = previous?.first
				val previousTier = previous?.second
				val currentTier = snap.currentTier

				previousProgress[snap.definition.id] = Pair(snap.currentValue, currentTier)

				val tierChangedUpward = currentTier != null &&
					(previousTier == null || currentTier.ordinal > previousTier.ordinal)
				if (tierChangedUpward) {
					events.add(
						DomainEvent.AchievementUnlocked(
							timestampMs = now,
							processorId = descriptor.id,
							achievementId = snap.definition.id,
							tier = currentTier.name,
						),
					)
				} else if (previousValue == null || snap.currentValue != previousValue) {
					events.add(
						DomainEvent.AchievementProgress(
							timestampMs = now,
							processorId = descriptor.id,
							achievementId = snap.definition.id,
							currentValue = snap.currentValue,
							targetValue = snap.nextTierTarget ?: snap.currentValue,
						),
					)
				}
			}
		}

		return events
	}

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
