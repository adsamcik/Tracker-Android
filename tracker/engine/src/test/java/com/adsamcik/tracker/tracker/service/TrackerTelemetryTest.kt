package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import dev.tracebox.api.LogCategory
import dev.tracebox.api.LogLevel
import dev.tracebox.api.PerformanceMeasurement
import dev.tracebox.api.Privacy
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.TraceboxLogger
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackerTelemetryTest {
	@Test
	fun `coordinator session summary uses one static template and public numeric arguments`() {
		val logger = RecordingTraceboxLogger()
		val metrics = TrackingCoordinatorMetrics(
			projectionDrainCount = 1,
			projectedEventCount = 2,
			projectionDrainNanos = 3,
			planRevisionCount = 4,
			trackingFrameCount = 5,
			trackingFrameWakeLockNanos = 6,
			motionPolicyChangeCount = 7,
			stationaryOptimizationCount = 8,
			fullFidelityRestoreCount = 9,
		)

		logger.recordCoordinatorSessionMetrics(metrics)

		logger.level shouldBe LogLevel.INFO
		logger.template.contains('$') shouldBe false
		logger.template.windowed(2).count { it == "{}" } shouldBe 9
		logger.arguments.toList() shouldContainExactly (1L..9L).toList()
		logger.arguments.forEach { argument ->
			PrivacyConfiguration.defaults().render(argument).privacy shouldBe Privacy.PUBLIC
		}
	}

	private class RecordingTraceboxLogger : TraceboxLogger {
		lateinit var level: LogLevel
		lateinit var template: String
		lateinit var arguments: Array<out Any?>

		override fun isEnabled(level: LogLevel, category: LogCategory): Boolean = true

		override fun log(level: LogLevel, template: String, vararg arguments: Any?) {
			this.level = level
			this.template = template
			this.arguments = arguments
		}

		override fun error(throwable: Throwable, template: String, vararg arguments: Any?) =
			error("Throwable logging is not expected in this test")

		override fun performanceStart(
			template: String,
			vararg arguments: Any?,
		): PerformanceMeasurement = throw AssertionError("Performance logging is not expected in this test")
	}
}
