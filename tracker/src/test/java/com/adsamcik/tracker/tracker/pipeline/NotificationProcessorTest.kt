package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NotificationProcessorTest {

	private lateinit var processor: NotificationProcessor

	@BeforeEach
	fun setUp() {
		processor = NotificationProcessor()
	}

	@Nested
	inner class Descriptor {
		@Test
		fun `id is notification`() {
			processor.descriptor.id shouldBe "notification"
		}

		@Test
		fun `required tier is AMBIENT`() {
			processor.descriptor.requiredTier shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `flush interval is MAX_VALUE`() {
			processor.descriptor.flushIntervalMs shouldBe Long.MAX_VALUE
		}

		@Test
		fun `priority is 100`() {
			processor.descriptor.priority shouldBe 100
		}
	}

	@Nested
	inner class NoOpBehavior {
		@Test
		fun `onSignal accepts signal without error`() {
			val signal = TrackingSignal(timestampMs = EpochMs(1_000L))
			processor.onSignal(signal)
		}

		@Test
		fun `onFlush returns empty list`() = runTest {
			processor.onFlush().shouldBeEmpty()
		}

		@Test
		fun `onStop returns empty list`() = runTest {
			processor.onStop().shouldBeEmpty()
		}

		@Test
		fun `checkpoint returns null`() {
			processor.checkpoint() shouldBe null
		}

		@Test
		fun `restore does not throw`() {
			processor.restore(byteArrayOf(1, 2, 3))
		}

		@Test
		fun `onStart then onSignal then onFlush is safe`() = runTest {
			processor.onStart(
				ProcessorContext(
					startTimestamp = EpochMs(1_000L),
				),
			)
			processor.onSignal(TrackingSignal(timestampMs = EpochMs(2_000L)))
			processor.onFlush().shouldBeEmpty()
			processor.onStop().shouldBeEmpty()
		}
	}
}
