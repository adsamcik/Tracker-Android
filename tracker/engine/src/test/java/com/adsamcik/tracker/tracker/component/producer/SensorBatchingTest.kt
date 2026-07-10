package com.adsamcik.tracker.tracker.component.producer

import android.hardware.Sensor
import io.kotest.matchers.ints.shouldBeExactly
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class SensorBatchingTest {

	@Test
	fun `uses bounded report latency when hardware FIFO is available`() {
		val sensor = mockk<Sensor> {
			every { fifoMaxEventCount } returns 128
		}

		SensorBatching.stepCounterMaxReportLatencyUs(sensor)
			.shouldBeExactly(SensorBatching.MAX_REPORT_LATENCY_US)
	}

	@Test
	fun `disables batching when hardware FIFO is unavailable`() {
		val sensor = mockk<Sensor> {
			every { fifoMaxEventCount } returns 0
		}

		SensorBatching.stepCounterMaxReportLatencyUs(sensor).shouldBeExactly(0)
	}
}
