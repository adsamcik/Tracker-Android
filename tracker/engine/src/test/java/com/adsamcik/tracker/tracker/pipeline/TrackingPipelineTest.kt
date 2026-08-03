package com.adsamcik.tracker.tracker.pipeline

import android.content.Context
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TrackingPipelineTest {
	private val context: Context = mockk(relaxed = true)

	private fun cycleContext(): CycleContext = CycleContext(
		cycle = TestCycleFactory.minimal(),
		collectionData = mockk(relaxed = true),
	)

	private class RecordingStage(
		private val id: String,
		private val calls: MutableList<String>,
		private val result: StageResult = StageResult.Continue,
		private val failure: Throwable? = null,
	) : PipelineStage {
		override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
			calls += id
			failure?.let { throw it }
			return result
		}
	}

	@Test
	fun `executes stages in order`() = runTest {
		val calls = mutableListOf<String>()
		val pipeline = TrackingPipeline(
			listOf(
				RecordingStage("first", calls),
				RecordingStage("second", calls),
				RecordingStage("third", calls),
			),
		)

		pipeline.execute(context, cycleContext())

		calls shouldBe listOf("first", "second", "third")
	}

	@Test
	fun `skip stops later stages`() = runTest {
		val calls = mutableListOf<String>()
		val pipeline = TrackingPipeline(
			listOf(
				RecordingStage("first", calls),
				RecordingStage("skip", calls, result = StageResult.Skip),
				RecordingStage("unreached", calls),
			),
		)

		pipeline.execute(context, cycleContext())

		calls shouldBe listOf("first", "skip")
	}

	@Test
	fun `ordinary stage failure continues with the next stage`() = runTest {
		val calls = mutableListOf<String>()
		val pipeline = TrackingPipeline(
			listOf(
				RecordingStage("failed", calls, failure = IllegalStateException("boom")),
				RecordingStage("after", calls),
			),
		)

		pipeline.execute(context, cycleContext())

		calls shouldBe listOf("failed", "after")
	}

	@Test
	fun `cancellation propagates and stops later stages`() = runTest {
		val calls = mutableListOf<String>()
		val pipeline = TrackingPipeline(
			listOf(
				RecordingStage("cancelled", calls, failure = CancellationException("stop")),
				RecordingStage("unreached", calls),
			),
		)

		shouldThrow<CancellationException> {
			pipeline.execute(context, cycleContext())
		}

		calls shouldBe listOf("cancelled")
	}

	@Test
	fun `passes the same cycle context to every stage`() = runTest {
		val captured = mutableListOf<CycleContext>()
		val stage = object : PipelineStage {
			override suspend fun process(
				context: Context,
				cycleContext: CycleContext,
			): StageResult {
				captured += cycleContext
				return StageResult.Continue
			}
		}
		val expected = cycleContext()

		TrackingPipeline(listOf(stage, stage)).execute(context, expected)

		captured shouldBe listOf(expected, expected)
	}
}
