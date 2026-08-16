package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import dev.tracebox.api.LogCategory
import dev.tracebox.api.LogArgument
import dev.tracebox.api.LogLevel
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.PerformanceMeasurement
import dev.tracebox.api.Privacy
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.TraceboxLogger
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackerTelemetryTest {
	@Test
	fun `coordinator summary renders public primitive counters without general timings`() {
		val logger = RecordingTraceboxLogger(performanceEnabled = false)

		logger.recordCoordinatorSessionMetrics(METRICS)

		logger.level shouldBe LogLevel.INFO
		logger.template shouldBe TrackerTraceboxTemplates.TRACKING_COORDINATOR_SESSION_COUNTS
		logger.renderedArguments() shouldContainExactly listOf(
			"1", "2", "4", "5", "10", "11", "7", "8", "9",
		)
		logger.arguments.forEach { argument ->
			PrivacyConfiguration.defaults().render(argument).privacy shouldBe Privacy.PUBLIC
		}
		logger.performanceCalls shouldBe 0
		logger.enabledChecks shouldContainExactly listOf(LogLevel.DEBUG to LogCategory.PERFORMANCE)
	}

	@Test
	fun `coordinator timings use the independently gated performance category`() {
		val logger = RecordingTraceboxLogger(performanceEnabled = true)

		logger.recordCoordinatorSessionMetrics(METRICS)

		logger.performanceCalls shouldBe 1
		logger.performanceTemplate shouldBe TrackerTraceboxTemplates.TRACKING_COORDINATOR_SESSION_TIMINGS
		logger.renderedPerformanceArguments() shouldContainExactly listOf("3", "6")
		logger.performanceArguments.forEach { argument ->
			PrivacyConfiguration.defaults().render(argument).privacy shouldBe Privacy.PUBLIC
		}
		logger.performanceSucceeded shouldBe true
	}

	private class RecordingTraceboxLogger(
		private val performanceEnabled: Boolean,
	) : TraceboxLogger {
		lateinit var level: LogLevel
		lateinit var template: LogTemplate
		lateinit var arguments: Array<out LogArgument>
		val enabledChecks = mutableListOf<Pair<LogLevel, LogCategory>>()
		var performanceCalls = 0
		lateinit var performanceTemplate: LogTemplate
		lateinit var performanceArguments: Array<out LogArgument>
		var performanceSucceeded = false

		override fun isEnabled(level: LogLevel, category: LogCategory): Boolean {
			enabledChecks += level to category
			return performanceEnabled
		}

		override fun log(level: LogLevel, template: LogTemplate, vararg arguments: LogArgument) {
			this.level = level
			this.template = template
			this.arguments = arguments
		}

		override fun error(
			throwable: Throwable,
			template: LogTemplate,
			vararg arguments: LogArgument,
		) =
			error("Throwable logging is not expected in this test")

		override fun performanceStart(
			template: LogTemplate,
			vararg arguments: LogArgument,
		): PerformanceMeasurement {
			performanceCalls++
			performanceTemplate = template
			performanceArguments = arguments
			return object : PerformanceMeasurement {
				override fun success() {
					performanceSucceeded = true
				}

				override fun failure() = Unit
				override fun cancelled() = Unit
			}
		}

		fun renderedArguments(): List<String> = arguments.map {
			PrivacyConfiguration.defaults().render(it).text
		}

		fun renderedPerformanceArguments(): List<String> = performanceArguments.map {
			PrivacyConfiguration.defaults().render(it).text
		}
	}

	private companion object {
		val METRICS = TrackingCoordinatorMetrics(
			projectionDrainCount = 1,
			projectedEventCount = 2,
			projectionDrainNanos = 3,
			planRevisionCount = 4,
			trackingFrameCount = 5,
			trackingFrameWakeLockNanos = 6,
			sourceTimerWakeupCount = 10,
			sourceTimerRequestCount = 11,
			motionPolicyChangeCount = 7,
			stationaryOptimizationCount = 8,
			fullFidelityRestoreCount = 9,
		)
	}
}
