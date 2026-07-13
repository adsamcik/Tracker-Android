package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.rule.RuleEvaluationResult
import com.adsamcik.tracker.stats.api.rule.RuleEvaluator
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs

/**
 * SignalProcessor backed by the unified achievement [RuleRegistry] and [RuleEvaluator].
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
 * The [previousProgress] map remains an in-memory overlay so live session updates do not
 * re-emit before the background worker has persisted `achievement_progress`.
 */
class AchievementProcessor(
	private val registry: RuleRegistry,
	private val metricsProvider: () -> MetricSnapshot = { MetricSnapshot.Empty },
	private val dirtyTracker: MetricDirtyTracker? = null,
	private val ruleEvaluator: RuleEvaluator = RuleEvaluator,
) : SignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = "achievement",
		requiredTier = PolicyTier.AMBIENT,
		flushIntervalMs = 60_000L,
		priority = 100, // Run after all other processors
	)

	private val previousProgress = mutableMapOf<String, Pair<Long, AchievementTier?>>()
	private val previousPacingDays = mutableMapOf<String, Long>()

	override suspend fun onStart(context: ProcessorContext) {
		previousProgress.clear()
		previousPacingDays.clear()
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
		// moved. Skip the full evaluation. consumeDirty(LIVE) is an atomic swap on the
		// per-consumer state — writes that race this check land in the NEXT LIVE flush
		// window, never lost. The PERSISTENCE consumer view (used by AchievementWorker)
		// is NOT affected by this drain, so the in-session live flush can never starve
		// the background persistence run of its dirty bits (R1+R2 round-6 finding).
		// `force = true` is used by onStop() so the final session evaluation doesn't
		// short-circuit when a periodic flush just consumed the dirty bit moments earlier.
		val tracker = dirtyTracker
		val consumed: Set<String>? = if (tracker != null && !force) {
			val set = tracker.consumeDirty(MetricDirtyTracker.Consumer.LIVE)
			if (set.isEmpty()) return emptyList()
			set
		} else if (tracker != null && force) {
			// In force mode we still drain the LIVE consumer's set so the next flush
			// starts clean for that view, but we do NOT early-return on empty.
			tracker.consumeDirty(MetricDirtyTracker.Consumer.LIVE)
		} else {
			null
		}

		val instances = if (consumed != null && consumed.isNotEmpty() && !force) {
			registry.instancesAffectedByTables(consumed)
		} else {
			registry.allInstances()
		}
		if (instances.isEmpty()) return emptyList()

		// Re-mark consumed tables on ANY non-cancellation failure so we don't silently
		// drop the next update. Without this, an exception in metricsProvider() or
		// anywhere inside the evaluation loop would leave the dirty set empty and the
		// next ordinary flush would short-circuit even though the underlying tables had
		// changed. Cancellation must still propagate (structured concurrency).
		val events = try {
			evaluateAllMetrics(instances)
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

	private fun evaluateAllMetrics(instances: List<RuleInstance>): List<DomainEvent> {
		val metrics = metricsProvider()
		if (metrics.asMap().isEmpty()) return emptyList()

		val now = EpochMs(com.adsamcik.tracker.stats.api.platform.currentTimeMillis())
		val events = mutableListOf<DomainEvent>()

		for (instance in instances) {
			if (instance.rule.metric !in metrics.asMap()) continue
			evaluateOne(instance, metrics, now)?.let(events::add)
		}

		return events
	}

	private fun evaluateOne(
		instance: RuleInstance,
		metrics: MetricSnapshot,
		now: EpochMs,
	): DomainEvent? {
		val definition = instance.attachment as? AchievementDefinition
		var pacingChanged = false
		if (definition != null && definition.minimumActiveDays > 1) {
			val pacingDays = metrics.asMap()[definition.pacingMetric]
			// The live session snapshot intentionally contains only cheap streaming
			// metrics. In that case defer paced tiers to the persisted evaluator.
			if (pacingDays == null) return null
			pacingChanged = previousPacingDays.put(instance.rule.id, pacingDays.toLong()) != pacingDays.toLong()
			if (!definition.isEligible(pacingDays.toLong())) return null
		}
		val cached = previousProgress[instance.rule.id]
		var effectiveInstance = if (previousProgress.containsKey(instance.rule.id)) {
			instance.copy(previousValue = cached?.first, previousTier = cached?.second)
		} else {
			instance
		}
		val currentValue = metrics.valueOf(instance.rule.metric).toLong()
		// Each catalog tier is an independent single-target rule. Once that tier is
		// unlocked it has no further progress to report; higher tiers have their own
		// rule instances. Refresh the cache silently so later day/metric changes do
		// not produce completed-tier noise.
		if (effectiveInstance.previousTier != null) {
			previousProgress[instance.rule.id] = currentValue to effectiveInstance.previousTier
			return null
		}
		if (pacingChanged) effectiveInstance = effectiveInstance.copy(previousValue = null)
		return when (val result = ruleEvaluator.evaluate(effectiveInstance, currentValue)) {
			is RuleEvaluationResult.Unchanged -> null
			is RuleEvaluationResult.TierUnlocked -> {
				previousProgress[instance.rule.id] = Pair(result.currentValue, result.unlocked)
				DomainEvent.AchievementUnlocked(
					timestampMs = now,
					processorId = descriptor.id,
					achievementId = instance.rule.id,
					tier = result.unlocked.name,
				)
			}
			is RuleEvaluationResult.ProgressUpdated -> {
				previousProgress[instance.rule.id] = Pair(
					result.currentValue,
					highestUnlockedTier(instance, result.currentValue),
				)
				DomainEvent.AchievementProgress(
					timestampMs = now,
					processorId = descriptor.id,
					achievementId = instance.rule.id,
					currentValue = result.currentValue,
					targetValue = result.nextTierTarget ?: result.currentValue,
				)
			}
		}
	}

	private fun highestUnlockedTier(instance: RuleInstance, currentValue: Long): AchievementTier? {
		return when (val target = instance.rule.target) {
			is RuleTarget.Single -> if (currentValue.toDouble() >= target.value) target.tier else null
			is RuleTarget.Tiered -> target.tiers.entries
				.sortedBy { it.key.ordinal }
				.lastOrNull { (_, threshold) -> currentValue >= threshold }
				?.key
		}
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
		previousPacingDays.clear()
		val size = dis.readInt()
		repeat(size) {
			val key = dis.readUTF()
			val value = dis.readLong()
			val hasTier = dis.readBoolean()
			val tier = if (hasTier) AchievementTier.entries[dis.readInt()] else null
			previousProgress[key] = Pair(value, tier)
		}
	}
}
