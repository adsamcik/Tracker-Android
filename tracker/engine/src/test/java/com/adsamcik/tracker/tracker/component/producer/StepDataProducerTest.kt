package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StepDataProducerTest {

	private lateinit var observer: TrackerDataProducerObserver
	private lateinit var producer: StepDataProducer

	@Before
	fun setUp() {
		observer = mockk(relaxed = true)
		producer = StepDataProducer(observer)
	}

	private fun createSensorEvent(sensorType: Int, value: Float): SensorEvent {
		val event = createEmptySensorEvent()
		val sensorMock = mockk<Sensor>(relaxed = true)
		every { sensorMock.type } returns sensorType

		// Set the sensor field via reflection
		val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
		sensorField.isAccessible = true
		sensorField.set(event, sensorMock)

		// Set the values array
		event.values[0] = value

		return event
	}

	private fun createEmptySensorEvent(): SensorEvent {
		val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
		constructor.isAccessible = true
		return constructor.newInstance(3)
	}

	private fun createBuilder(): TrackingCycleBuilder {
		return TrackingCycleBuilder(System.currentTimeMillis(), System.nanoTime())
	}

	private fun createStepSensorContext(
		hasFeature: Boolean = true,
		sensor: Sensor? = mockk(relaxed = true),
		registrationSucceeds: Boolean = true,
	): Context {
		val context = mockk<Context>()
		val packageManager = mockk<PackageManager>()
		val sensorManager = mockk<SensorManager>(relaxed = true)
		every { context.packageManager } returns packageManager
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
		every {
			packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
		} returns hasFeature
		every { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns sensor
		if (sensor != null) {
			every { sensor.fifoMaxEventCount } returns 0
			every {
				sensorManager.registerListener(
					producer,
					sensor,
					SensorManager.SENSOR_DELAY_NORMAL,
					0,
				)
			} returns registrationSucceeds
		}
		return context
	}

	private fun getLastStepCount(): Int {
		val field = StepDataProducer::class.java.getDeclaredField("lastStepCount")
		field.isAccessible = true
		return field.getInt(producer)
	}

	private fun getStepCountSinceLastCollection(): Int {
		val field = StepDataProducer::class.java.getDeclaredField("stepCountSinceLastCollection")
		field.isAccessible = true
		return field.getInt(producer)
	}

	private fun setStepCountSinceLastCollection(value: Int) {
		val field = StepDataProducer::class.java.getDeclaredField("stepCountSinceLastCollection")
		field.isAccessible = true
		field.setInt(producer, value)
	}

	private fun setFlushState(sensorManager: SensorManager, batchingEnabled: Boolean) {
		StepDataProducer::class.java.getDeclaredField("sensorManager").apply {
			isAccessible = true
			set(producer, sensorManager)
		}
		StepDataProducer::class.java.getDeclaredField("batchingEnabled").apply {
			isAccessible = true
			setBoolean(producer, batchingEnabled)
		}
	}


	@Test
	fun `first sensor event initializes lastStepCount without accumulating`() {
		val event = createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f)
		producer.onSensorChanged(event)

		getLastStepCount() shouldBe 100
		getStepCountSinceLastCollection() shouldBe 0
	}

	@Test
	fun `second event accumulates step difference`() {
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 110f))

		getLastStepCount() shouldBe 110
		getStepCountSinceLastCollection() shouldBe 10
	}

	@Test
	fun `multiple events accumulate correctly`() {
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 105f))
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 120f))

		getLastStepCount() shouldBe 120
		getStepCountSinceLastCollection() shouldBe 20
	}

	@Test
	fun `overflow resets by adding current count`() {
		// Simulate: lastStepCount is high, then sensor resets to low value
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 1000f))
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 5f))

		getLastStepCount() shouldBe 5
		// On overflow (lastStepCount > stepCount), adds stepCount directly
		getStepCountSinceLastCollection() shouldBe 5
	}

	@Test
	fun `ignores non-step-counter sensor events`() {
		val event = createSensorEvent(Sensor.TYPE_ACCELEROMETER, 100f)
		producer.onSensorChanged(event)

		getLastStepCount() shouldBe -1
		getStepCountSinceLastCollection() shouldBe 0
	}

	@Test
	fun `counter reset exactly to zero emits a durable reset interval`() {
		// First event sets lastStepCount
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 50f))
		// Android can reset the cumulative counter to exactly zero after reboot/sensor restart.
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 0f))

		getLastStepCount() shouldBe 0
		getStepCountSinceLastCollection() shouldBe 0
		val builder = createBuilder()
		producer.onDataRequest(builder)
		builder.stepDelta shouldBe 0
		builder.stepSensorReset shouldBe true
	}

	@Test
	fun `same step count produces zero delta`() {
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))

		getStepCountSinceLastCollection() shouldBe 0
	}


	@Test
	fun `sets step count in temp data and resets counter`() {
		// Simulate accumulated steps
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 115f))

		val builder = createBuilder()
		producer.onDataRequest(builder)

		builder.stepDelta shouldBe 15
		getStepCountSinceLastCollection() shouldBe 0
	}

	@Test
	fun `does not emit a step interval when no steps accumulated`() {
		val builder = createBuilder()
		producer.onDataRequest(builder)

		builder.stepDelta.shouldBeNull()
	}

	@Test
	fun `consecutive data requests return correct incremental counts`() {
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 110f))

		val builder1 = createBuilder()
		producer.onDataRequest(builder1)
		builder1.stepDelta shouldBe 10

		// More steps after first collection
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 118f))

		val builder2 = createBuilder()
		producer.onDataRequest(builder2)
		builder2.stepDelta shouldBe 8
	}

	@Test
	fun `disable and re-enable drops steps from the disabled interval`() = runTest {
		val context = createStepSensorContext()
		producer.canBeEnabled = true
		producer.onEnable(context)
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 110f))

		producer.onDisable(context)
		producer.onEnable(context)
		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 120f))

		val baselineBuilder = createBuilder()
		producer.onDataRequest(baselineBuilder)
		baselineBuilder.stepDelta.shouldBeNull()

		producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 125f))
		val enabledIntervalBuilder = createBuilder()
		producer.onDataRequest(enabledIntervalBuilder)

		enabledIntervalBuilder.stepDelta shouldBe 5
		enabledIntervalBuilder.stepSensorValueStart shouldBe 120
		enabledIntervalBuilder.stepSensorValueEnd shouldBe 125
	}

	@Test
	fun `enable fails when the step counter is unavailable`() = runTest {
		producer.canBeEnabled = true

		shouldThrow<IllegalStateException> {
			producer.onEnable(createStepSensorContext(sensor = null))
		}

		producer.isEnabled shouldBe false
	}

	@Test
	fun `enable fails when the listener cannot be registered`() = runTest {
		producer.canBeEnabled = true

		shouldThrow<IllegalStateException> {
			producer.onEnable(createStepSensorContext(registrationSucceeds = false))
		}

		producer.isEnabled shouldBe false
	}

	@Test
	fun `does not set data when step count is negative`() {
		setStepCountSinceLastCollection(-1)

		val builder = createBuilder()
		producer.onDataRequest(builder)

		builder.stepDelta shouldBe null
	}


	@Test
	fun `does nothing and does not throw`() {
		// Should be a no-op
		producer.onAccuracyChanged(null, 0)
		producer.onAccuracyChanged(mockk(relaxed = true), Sensor.TYPE_STEP_COUNTER)
	}

	@Test
	fun `rejected batched sensor flush remains retryable`() = runTest {
		val sensorManager = mockk<SensorManager>()
		every { sensorManager.flush(producer) } returns false
		setFlushState(sensorManager, batchingEnabled = true)

		shouldThrow<IllegalStateException> {
			producer.flushPendingEvents()
		}
	}

	@Test
	fun `timed out batched sensor flush remains retryable`() = runTest {
		val sensorManager = mockk<SensorManager>()
		every { sensorManager.flush(producer) } returns true
		setFlushState(sensorManager, batchingEnabled = true)

		shouldThrow<IllegalStateException> {
			producer.flushPendingEvents()
		}
	}

	@Test
	fun `late callback settles only the timed out flush before retrying`() = runTest {
		val sensorManager = mockk<SensorManager>()
		every { sensorManager.flush(producer) } returns true
		setFlushState(sensorManager, batchingEnabled = true)
		shouldThrow<IllegalStateException> {
			producer.flushPendingEvents()
		}

		val retry = async { producer.flushPendingEvents() }
		runCurrent()
		producer.onFlushCompleted(null)
		runCurrent()
		producer.onFlushCompleted(null)

		retry.await()
		io.mockk.verify(exactly = 2) { sensorManager.flush(producer) }
	}

	@Test
	fun `non-batched sensor does not request an unnecessary flush`() = runTest {
		val sensorManager = mockk<SensorManager>(relaxed = true)
		setFlushState(sensorManager, batchingEnabled = false)

		producer.flushPendingEvents()

		io.mockk.verify(exactly = 0) { sensorManager.flush(any()) }
	}


	@Test
	fun `NEW_STEPS_ARG has expected value`() {
		StepDataProducer.NEW_STEPS_ARG shouldBe "newSteps"
	}
}
