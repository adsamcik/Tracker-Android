package com.adsamcik.tracker.tracker.pipeline

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StageResult")
class StageResultTest {

	@Nested
	@DisplayName("Continue")
	inner class ContinueTests {

		@Test
		fun `Continue is a singleton`() {
			val a: StageResult = StageResult.Continue
			val b: StageResult = StageResult.Continue
			a shouldBe b
		}

		@Test
		fun `Continue is a StageResult`() {
			val result: StageResult = StageResult.Continue
			result.shouldBeInstanceOf<StageResult.Continue>()
		}
	}

	@Nested
	@DisplayName("Skip")
	inner class SkipTests {

		@Test
		fun `Skip carries a reason`() {
			val skip = StageResult.Skip("pre-validation failed")
			skip.reason shouldBe "pre-validation failed"
		}

		@Test
		fun `Skip instances with same reason are equal`() {
			val a = StageResult.Skip("duplicate")
			val b = StageResult.Skip("duplicate")
			a shouldBe b
		}

		@Test
		fun `Skip instances with different reasons are not equal`() {
			val a = StageResult.Skip("reason A")
			val b = StageResult.Skip("reason B")
			a shouldNotBe b
		}

		@Test
		fun `Skip is a StageResult`() {
			val result: StageResult = StageResult.Skip("test")
			result.shouldBeInstanceOf<StageResult.Skip>()
		}
	}

	@Nested
	@DisplayName("Error")
	inner class ErrorTests {

		@Test
		fun `Error carries exception and stage name`() {
			val ex = RuntimeException("boom")
			val error = StageResult.Error(ex, "DataCollection")
			error.exception shouldBe ex
			error.stage shouldBe "DataCollection"
		}

		@Test
		fun `Error instances with same data are equal`() {
			val ex = RuntimeException("boom")
			val a = StageResult.Error(ex, "stage")
			val b = StageResult.Error(ex, "stage")
			a shouldBe b
		}

		@Test
		fun `Error instances with different stages are not equal`() {
			val ex = RuntimeException("boom")
			val a = StageResult.Error(ex, "stageA")
			val b = StageResult.Error(ex, "stageB")
			a shouldNotBe b
		}

		@Test
		fun `Error is a StageResult`() {
			val result: StageResult = StageResult.Error(Exception(), "test")
			result.shouldBeInstanceOf<StageResult.Error>()
		}
	}

	@Nested
	@DisplayName("Exhaustive when")
	inner class ExhaustiveWhen {

		@Test
		fun `when expression covers all branches`() {
			val results: List<StageResult> = listOf(
				StageResult.Continue,
				StageResult.Skip("skipped"),
				StageResult.Error(Exception(), "stage"),
			)

			val labels = results.map { result ->
				when (result) {
					is StageResult.Continue -> "continue"
					is StageResult.Skip -> "skip"
					is StageResult.Error -> "error"
				}
			}

			labels shouldBe listOf("continue", "skip", "error")
		}
	}
}
