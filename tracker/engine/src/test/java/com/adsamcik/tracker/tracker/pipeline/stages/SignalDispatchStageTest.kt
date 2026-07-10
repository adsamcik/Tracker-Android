package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.TestCycleFactory
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SignalDispatchStageTest {
	private val context: Context = mockk(relaxed = true)

	@Test
	fun `uses latest tier from provider for each signal`() = runTest {
		val processor = CapturingProcessor()
		val processorPipeline = ProcessorPipeline(
			processors = setOf(processor),
			scope = this,
		)
		var currentTier = PolicyTier.AMBIENT
		val stage = SignalDispatchStage(
			processorPipelineProvider = { processorPipeline },
			currentTierProvider = { currentTier },
		)

		processorPipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		stage.process(context, cycleContext(timestampMs = 1_000L))
		currentTier = PolicyTier.PRECISION
		stage.process(context, cycleContext(timestampMs = 2_000L))

		processor.observedTiers.shouldContainExactly(PolicyTier.AMBIENT, PolicyTier.PRECISION)
	}

	@Test
	fun `dispatches cached activity context with separate freshness metadata`() = runTest {
		val processor = CapturingProcessor()
		val processorPipeline = ProcessorPipeline(
			processors = setOf(processor),
			scope = this,
		)
		val stage = SignalDispatchStage(
			processorPipelineProvider = { processorPipeline },
			currentTierProvider = { PolicyTier.AMBIENT },
		)
		val activity = ActivityInfo(activityType = 7, confidence = 80)

		processorPipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		stage.process(
			context,
			cycleContext(
				TrackingCycle(
					timestampMs = 1_000L,
					elapsedRealtimeNanos = 1_000_000_000L,
					activity = activity,
					activityFresh = false,
				),
				activity,
			),
		)
		stage.process(
			context,
			cycleContext(
				TrackingCycle(
					timestampMs = 2_000L,
					elapsedRealtimeNanos = 2_000_000_000L,
					activity = activity,
					activityFresh = true,
				),
				activity,
			),
		)

		processor.observedSignals[0].activity.shouldNotBeNull()
		processor.observedSignals[0].activityFresh shouldBe false
		processor.observedSignals[1].activity.shouldNotBeNull()
		processor.observedSignals[1].activityFresh shouldBe true
	}

	private fun cycleContext(timestampMs: Long): CycleContext {
		return CycleContext(
			cycle = TestCycleFactory.minimal(timestampMs = timestampMs),
			collectionData = MutableCollectionData(timestampMs),
		)
	}

	private fun cycleContext(cycle: TrackingCycle, observedActivity: ActivityInfo): CycleContext {
		val collectionData = mockk<MutableCollectionData>(relaxed = true) {
			every { activity } returns observedActivity
		}
		return CycleContext(
			cycle = cycle,
			collectionData = collectionData,
		)
	}

	private class CapturingProcessor : SignalProcessor {
		val observedTiers = mutableListOf<PolicyTier>()
		val observedSignals = mutableListOf<TrackingSignal>()

		override val descriptor = ProcessorDescriptor(
			id = "capturing-policy-tier",
			requiredTier = PolicyTier.AMBIENT,
			flushIntervalMs = Long.MAX_VALUE,
			priority = 0,
		)

		override suspend fun onStart(context: ProcessorContext) = Unit

		override fun onSignal(signal: TrackingSignal) {
			observedSignals += signal
			signal.policy?.tier?.let(observedTiers::add)
		}

		override suspend fun onFlush(): List<DomainEvent> = emptyList()

		override suspend fun onStop(): List<DomainEvent> = emptyList()

		override fun checkpoint(): ByteArray? = null

		override fun restore(state: ByteArray) = Unit
	}
}
