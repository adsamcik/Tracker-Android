package com.adsamcik.tracker.tracker.pipeline

import android.content.Context
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TrackingPipeline")
class TrackingPipelineTest {

	private val mockContext: Context = mockk(relaxed = true)

	private fun minimalCycleContext(): CycleContext = CycleContext(
		cycle = TestCycleFactory.minimal(),
		collectionData = mockk(relaxed = true),
	)

	/** A stage that always returns Continue. */
	private class ContinueStage(override val name: String = "Continue") : PipelineStage {
		var callCount = 0
		override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
			callCount++
			return StageResult.Continue
		}
	}

	/** A stage that always returns Skip. */
	private class SkipStage(
		override val name: String = "Skip",
		private val reason: String = "rejected",
	) : PipelineStage {
		var callCount = 0
		override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
			callCount++
			return StageResult.Skip(reason)
		}
	}

	/** A stage that always returns Error. */
	private class ErrorStage(override val name: String = "ErrorStage") : PipelineStage {
		var callCount = 0
		override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
			callCount++
			return StageResult.Error(RuntimeException("fail"), name)
		}
	}

	/** A stage that throws an exception. */
	private class ThrowingStage(override val name: String = "Throwing") : PipelineStage {
		override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
			throw IllegalStateException("boom")
		}
	}

	@Nested
	@DisplayName("Execute with all Continue stages")
	inner class AllContinue {

		@Test
		fun `executes all stages sequentially`() = runTest {
			val s1 = ContinueStage("stage1")
			val s2 = ContinueStage("stage2")
			val s3 = ContinueStage("stage3")
			val pipeline = TrackingPipeline(listOf(s1, s2, s3))

			val result = pipeline.execute(mockContext, minimalCycleContext())

			s1.callCount shouldBe 1
			s2.callCount shouldBe 1
			s3.callCount shouldBe 1
			result.completedSuccessfully shouldBe true
			result.metrics shouldHaveSize 3
		}

		@Test
		fun `metrics record Continue for each stage`() = runTest {
			val pipeline = TrackingPipeline(listOf(ContinueStage("A"), ContinueStage("B")))
			val result = pipeline.execute(mockContext, minimalCycleContext())

			result.metrics.forEach {
				it.result.shouldBeInstanceOf<StageResult.Continue>()
			}
		}

		@Test
		fun `metrics record stage names`() = runTest {
			val pipeline = TrackingPipeline(listOf(ContinueStage("First"), ContinueStage("Second")))
			val result = pipeline.execute(mockContext, minimalCycleContext())

			result.metrics.map { it.stageName } shouldBe listOf("First", "Second")
		}

		@Test
		fun `metrics record non-negative durations`() = runTest {
			val pipeline = TrackingPipeline(listOf(ContinueStage()))
			val result = pipeline.execute(mockContext, minimalCycleContext())

			result.metrics.first().durationMs shouldBe result.metrics.first().durationMs
			(result.metrics.first().durationMs >= 0) shouldBe true
		}
	}

	@Nested
	@DisplayName("Skip semantics")
	inner class SkipSemantics {

		@Test
		fun `Skip stops pipeline and marks not completed`() = runTest {
			val s1 = ContinueStage("s1")
			val skip = SkipStage("skip")
			val s3 = ContinueStage("s3")
			val pipeline = TrackingPipeline(listOf(s1, skip, s3))

			val result = pipeline.execute(mockContext, minimalCycleContext())

			s1.callCount shouldBe 1
			skip.callCount shouldBe 1
			s3.callCount shouldBe 0  // not reached
			result.completedSuccessfully shouldBe false
			result.metrics shouldHaveSize 2  // only s1 and skip
		}

		@Test
		fun `Skip reason is captured in metrics`() = runTest {
			val pipeline = TrackingPipeline(listOf(SkipStage(reason = "low battery")))
			val result = pipeline.execute(mockContext, minimalCycleContext())

			val skip = result.metrics.first().result
			skip.shouldBeInstanceOf<StageResult.Skip>()
			skip.reason shouldBe "low battery"
		}
	}

	@Nested
	@DisplayName("Error semantics (fail-open)")
	inner class ErrorSemantics {

		@Test
		fun `Error does not stop pipeline - subsequent stages still execute`() = runTest {
			val error = ErrorStage("err")
			val after = ContinueStage("after")
			val pipeline = TrackingPipeline(listOf(error, after))

			val result = pipeline.execute(mockContext, minimalCycleContext())

			error.callCount shouldBe 1
			after.callCount shouldBe 1
			result.completedSuccessfully shouldBe true
			result.metrics shouldHaveSize 2
		}

		@Test
		fun `Error is captured in metrics`() = runTest {
			val pipeline = TrackingPipeline(listOf(ErrorStage()))
			val result = pipeline.execute(mockContext, minimalCycleContext())

			val err = result.metrics.first().result
			err.shouldBeInstanceOf<StageResult.Error>()
			err.stage shouldBe "ErrorStage"
		}
	}

	@Nested
	@DisplayName("Exception handling")
	inner class ExceptionHandling {

		@Test
		fun `thrown exception is caught and wrapped as Error`() = runTest {
			val throwing = ThrowingStage("thrower")
			val after = ContinueStage("after")
			val pipeline = TrackingPipeline(listOf(throwing, after))

			val result = pipeline.execute(mockContext, minimalCycleContext())

			after.callCount shouldBe 1 // pipeline continues (fail-open)
			result.metrics shouldHaveSize 2
			val errMetric = result.metrics.first()
			errMetric.result.shouldBeInstanceOf<StageResult.Error>()
			(errMetric.result as StageResult.Error).stage shouldBe "thrower"
		}
	}

	@Nested
	@DisplayName("Empty pipeline")
	inner class EmptyPipeline {

		@Test
		fun `empty pipeline completes successfully with no metrics`() = runTest {
			val pipeline = TrackingPipeline(emptyList())
			val result = pipeline.execute(mockContext, minimalCycleContext())

			result.completedSuccessfully shouldBe true
			result.metrics shouldHaveSize 0
		}
	}

	@Nested
	@DisplayName("CycleContext passthrough")
	inner class ContextPassthrough {

		@Test
		fun `same CycleContext is passed to all stages`() = runTest {
			val capturedContexts = mutableListOf<CycleContext>()
			val capturingStage = object : PipelineStage {
				override val name = "capture"
				override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
					capturedContexts.add(cycleContext)
					return StageResult.Continue
				}
			}
			val pipeline = TrackingPipeline(listOf(capturingStage, capturingStage))
			val ctx = minimalCycleContext()

			pipeline.execute(mockContext, ctx)

			capturedContexts shouldHaveSize 2
			capturedContexts[0] shouldBe ctx
			capturedContexts[1] shouldBe ctx
		}
	}
}
