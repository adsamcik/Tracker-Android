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
		// moved. Skip the full evaluation. The generation-aware LIVE snapshot remains
		// pending until evaluation succeeds; writes that race this check receive a newer
		// generation and survive acknowledgement for the next flush. PERSISTENCE remains
		// independent, so the live flush cannot starve the background worker.
		// `force = true` is used by onStop() so the final session evaluation doesn't
		// short-circuit when a periodic flush just consumed the dirty bit moments earlier.
		val tracker = dirtyTracker
		val dirtySnapshot = if (tracker != null) {
			tracker.snapshotDirty(MetricDirtyTracker.Consumer.LIVE)
		} else {
			null
		}
		if (!force && dirtySnapshot?.isEmpty == true) return emptyList()
		val dirtyTables = dirtySnapshot?.tables

		val instances = if (!dirtyTables.isNullOrEmpty() && !force) {
			registry.instancesAffectedByTables(dirtyTables)
		} else {
			registry.allInstances()
		}
		if (instances.isEmpty()) {
			if (tracker != null && dirtySnapshot != null) {
				tracker.acknowledgeDirty(MetricDirtyTracker.Consumer.LIVE, dirtySnapshot)
			}
			return emptyList()
		}

		val events = try {
			evaluateAllMetrics(instances)
		} catch (t: Throwable) {
			// No acknowledgement on failure or cancellation: the snapshot remains
			// pending for the next flush.
			throw t
		}
		if (tracker != null && dirtySnapshot != null) {
			tracker.acknowledgeDirty(MetricDirtyTracker.Consumer.LIVE, dirtySnapshot)
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
}
