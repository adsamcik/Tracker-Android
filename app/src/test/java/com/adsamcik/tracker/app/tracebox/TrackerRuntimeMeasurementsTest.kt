package com.adsamcik.tracker.app.tracebox

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.api.LogArgument
import dev.tracebox.api.LogCategory
import dev.tracebox.api.LogLevel
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.PerformanceMeasurement
import dev.tracebox.api.Privacy
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.TraceboxLogger
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerRuntimeMeasurementsTest {
	@Test
	fun `disabled performance policy reads no platform measurements`() = runTest {
		val logger = RecordingLogger(performanceEnabled = false)
		val source = FakeMeasurementSource()
		val measurements = TrackerRuntimeMeasurements(
			logger = logger,
			source = source,
			scope = this,
			dispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		measurements.recordStartup()
		measurements.onStart(TEST_OWNER)
		measurements.onStop(TEST_OWNER)

		source.startupReads shouldBe 0
		source.resourceReads shouldBe 0
		logger.events.shouldBeEmpty()
	}

	@Test
	fun `startup records bounded public process clocks`() = runTest {
		val logger = RecordingLogger(performanceEnabled = true)
		val source = FakeMeasurementSource(
			startedAtElapsedRealtimeMs = 1_000L,
			elapsedRealtimeMs = 2_750L,
			processCpuTimeMs = 340L,
		)
		val measurements = TrackerRuntimeMeasurements(
			logger = logger,
			source = source,
			scope = this,
			dispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		measurements.recordStartup()

		logger.events.single().template shouldBe
			TrackerTraceboxTemplates.APPLICATION_STARTUP_MEASUREMENT
		logger.events.single().renderedArguments() shouldContainExactly listOf("1750", "340")
		logger.events.single().arguments.forEach { argument ->
			PrivacyConfiguration.defaults().render(argument).privacy shouldBe Privacy.PUBLIC
		}
	}

	@Test
	fun `lifecycle boundaries record battery power memory and foreground entries`() = runTest {
		val logger = RecordingLogger(performanceEnabled = true)
		val source = FakeMeasurementSource()
		val measurements = TrackerRuntimeMeasurements(
			logger = logger,
			source = source,
			scope = this,
			dispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		measurements.onStart(TEST_OWNER)
		measurements.onStop(TEST_OWNER)

		source.resourceReads shouldBe 2
		logger.events.map(RecordedEvent::template) shouldContainExactly listOf(
			TrackerTraceboxTemplates.APPLICATION_BATTERY_POWER_MEASUREMENT,
			TrackerTraceboxTemplates.APPLICATION_MEMORY_MEASUREMENT,
			TrackerTraceboxTemplates.APPLICATION_BATTERY_POWER_MEASUREMENT,
			TrackerTraceboxTemplates.APPLICATION_MEMORY_MEASUREMENT,
		)
		logger.events[0].renderedArguments() shouldContainExactly listOf(
			"true", "1", "true", "true", "82", "true", "true", "4200000",
			"true", "14000000", "true", "false", "true",
		)
		logger.events[1].renderedArguments() shouldContainExactly listOf(
			"true", "1", "12345", "23456", "34567", "10",
		)
		logger.events[2].renderedArguments().take(2) shouldContainExactly listOf("false", "1")
		logger.events.flatMap { it.arguments.asIterable() }.forEach { argument ->
			PrivacyConfiguration.defaults().render(argument).privacy shouldBe Privacy.PUBLIC
		}
	}

	@Test
	fun `unsupported battery properties have explicit availability fields`() = runTest {
		val logger = RecordingLogger(performanceEnabled = true)
		val source = FakeMeasurementSource(
			resourceSnapshot = SNAPSHOT.copy(
				batteryServiceAvailable = false,
				batteryPercent = null,
				charging = false,
				chargeCounterUah = null,
				energyCounterNwh = null,
			),
		)
		val measurements = TrackerRuntimeMeasurements(
			logger = logger,
			source = source,
			scope = this,
			dispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		measurements.onStart(TEST_OWNER)

		logger.events.first().renderedArguments() shouldContainExactly listOf(
			"true", "1", "false", "false", "0", "false", "false", "0",
			"false", "0", "true", "false", "true",
		)
	}

	@Test
	fun `platform sampling failure preserves throwable without a dynamic message`() = runTest {
		val logger = RecordingLogger(performanceEnabled = true)
		val failure = IllegalStateException("must not become a log argument")
		val source = FakeMeasurementSource(resourceFailure = failure)
		val measurements = TrackerRuntimeMeasurements(
			logger = logger,
			source = source,
			scope = this,
			dispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		measurements.onStart(TEST_OWNER)

		logger.events.shouldBeEmpty()
		logger.failure shouldBe failure
		logger.failureTemplate shouldBe
			TrackerTraceboxTemplates.APPLICATION_RESOURCE_MEASUREMENT_FAILED
		logger.failureArguments.shouldBeEmpty()
	}

	private class FakeMeasurementSource(
		private val startedAtElapsedRealtimeMs: Long = 1_000L,
		private val elapsedRealtimeMs: Long = 2_000L,
		private val processCpuTimeMs: Long = 300L,
		private val resourceSnapshot: TrackerResourceSnapshot = SNAPSHOT,
		private val resourceFailure: RuntimeException? = null,
	) : TrackerRuntimeMeasurementSource {
		var startupReads = 0
		var resourceReads = 0

		override fun processStartedAtElapsedRealtimeMs(): Long = startedAtElapsedRealtimeMs.also {
			startupReads++
		}

		override fun elapsedRealtimeMs(): Long = elapsedRealtimeMs.also { startupReads++ }

		override fun processCpuTimeMs(): Long = processCpuTimeMs.also { startupReads++ }

		override fun resourceSnapshot(): TrackerResourceSnapshot {
			resourceReads++
			resourceFailure?.let { throw it }
			return resourceSnapshot
		}
	}

	private class RecordingLogger(private val performanceEnabled: Boolean) : TraceboxLogger {
		val events = mutableListOf<RecordedEvent>()
		var failure: Throwable? = null
		var failureTemplate: LogTemplate? = null
		var failureArguments: List<LogArgument> = emptyList()

		override fun isEnabled(level: LogLevel, category: LogCategory): Boolean =
			performanceEnabled && level == LogLevel.DEBUG && category == LogCategory.PERFORMANCE

		override fun log(level: LogLevel, template: LogTemplate, vararg arguments: LogArgument) = Unit

		override fun error(
			throwable: Throwable,
			template: LogTemplate,
			vararg arguments: LogArgument,
		) {
			failure = throwable
			failureTemplate = template
			failureArguments = arguments.toList()
		}

		override fun performanceEvent(template: LogTemplate, vararg arguments: LogArgument) {
			events += RecordedEvent(template, arguments)
		}

		override fun performanceStart(
			template: LogTemplate,
			vararg arguments: LogArgument,
		): PerformanceMeasurement = error("Duration measurement is not expected")
	}

	private data class RecordedEvent(
		val template: LogTemplate,
		val arguments: Array<out LogArgument>,
	) {
		fun renderedArguments(): List<String> = arguments.map {
			PrivacyConfiguration.defaults().render(it).text
		}
	}

	private class TestLifecycleOwner : LifecycleOwner {
		override val lifecycle: Lifecycle = LifecycleRegistry(this)
	}

	private companion object {
		val TEST_OWNER = TestLifecycleOwner()
		val SNAPSHOT = TrackerResourceSnapshot(
			batteryServiceAvailable = true,
			batteryPercent = 82,
			charging = true,
			chargeCounterUah = 4_200_000,
			energyCounterNwh = 14_000_000L,
			interactive = true,
			deviceIdle = false,
			powerSave = true,
			processPssKiB = 12_345L,
			javaHeapUsedKiB = 23_456L,
			nativeHeapAllocatedKiB = 34_567L,
			trimLevel = 10,
		)
	}
}
