package com.adsamcik.tracker.tracker.source.ambient.steps

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidAmbientStepsProviderReadersTest {
	@Test
	fun `Health Connect preserves explicit covered zero but not missing evidence`() = runTest {
		val window = window()
		val zero = HealthConnectAmbientStepsProviderReader { 0L }
		val missing = HealthConnectAmbientStepsProviderReader { null }

		zero.read(window, END_MS) shouldBe AmbientStepsProviderAggregate(
			provider = AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
			window = window,
			stepCount = 0L,
			observedAtMs = END_MS,
		)
		missing.read(window, END_MS) shouldBe null
	}

	@Test
	fun `Local Recording accepts one exact bucket and sums its bounded values`() = runTest {
		val window = window()
		val subject = LocalRecordingAmbientStepsProviderReader {
			listOf(LocalRecordingAggregateBucket(START_MS, END_MS, listOf(2L, 3L, 5L)))
		}

		val result = requireNotNull(subject.read(window, END_MS + 1_000L))

		result.provider shouldBe AmbientStepsProvider.LOCAL_RECORDING_STEPS
		result.stepCount shouldBe 10L
		result.window shouldBe window
		result.observedAtMs shouldBe END_MS + 1_000L
	}

	@Test
	fun `Local Recording empty response is missing rather than fabricated zero`() = runTest {
		val window = window()

		LocalRecordingAmbientStepsProviderReader { emptyList() }
			.read(window, END_MS) shouldBe null
		LocalRecordingAmbientStepsProviderReader {
			listOf(LocalRecordingAggregateBucket(START_MS, END_MS, emptyList()))
		}.read(window, END_MS) shouldBe null
	}

	@Test
	fun `Local Recording rejects extra mismatched negative and overflowing aggregates`() = runTest {
		val window = window()

		shouldThrow<IllegalStateException> {
			LocalRecordingAmbientStepsProviderReader {
				listOf(
					LocalRecordingAggregateBucket(START_MS, END_MS, listOf(1L)),
					LocalRecordingAggregateBucket(START_MS, END_MS, listOf(1L)),
				)
			}.read(window, END_MS)
		}
		shouldThrow<IllegalStateException> {
			LocalRecordingAmbientStepsProviderReader {
				listOf(LocalRecordingAggregateBucket(START_MS, END_MS + 1_000L, listOf(1L)))
			}.read(window, END_MS)
		}
		shouldThrow<IllegalStateException> {
			LocalRecordingAmbientStepsProviderReader {
				listOf(LocalRecordingAggregateBucket(START_MS, END_MS, listOf(-1L)))
			}.read(window, END_MS)
		}
		shouldThrow<ArithmeticException> {
			LocalRecordingAmbientStepsProviderReader {
				listOf(LocalRecordingAggregateBucket(START_MS, END_MS, listOf(Long.MAX_VALUE, 1L)))
			}.read(window, END_MS)
		}
	}

	@Test
	fun `read windows are positive second aligned and bounded to a civil day`() {
		shouldThrow<IllegalArgumentException> {
			AmbientStepsProviderReadWindow(START_MS, START_MS)
		}
		shouldThrow<IllegalArgumentException> {
			AmbientStepsProviderReadWindow(START_MS + 1L, END_MS)
		}
		shouldThrow<IllegalArgumentException> {
			AmbientStepsProviderReadWindow(START_MS, START_MS + 26L * 60L * 60L * 1_000L)
		}
	}

	@Test
	fun `future-ended windows cannot be stamped as observed`() = runTest {
		shouldThrow<IllegalArgumentException> {
			HealthConnectAmbientStepsProviderReader { 1L }
				.read(window(), END_MS - 1L)
		}
	}

	private fun window() = AmbientStepsProviderReadWindow(START_MS, END_MS)

	private companion object {
		const val START_MS = 1_000L
		const val END_MS = START_MS + 60L * 60L * 1_000L
	}
}
