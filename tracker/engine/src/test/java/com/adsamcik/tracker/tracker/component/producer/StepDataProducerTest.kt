package com.adsamcik.tracker.tracker.component.producer

import android.hardware.Sensor
import android.hardware.SensorEvent
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.robolectric.annotation.Config

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("StepDataProducer")
class StepDataProducerTest {

	private lateinit var observer: TrackerDataProducerObserver
	private lateinit var producer: StepDataProducer

	@BeforeEach
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

	@Nested
	@DisplayName("onSensorChanged")
	inner class OnSensorChanged {

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
		fun `zero step count event after initialization does not accumulate`() {
			// First event sets lastStepCount
			producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 50f))
			// Second event with stepCount = 0 is skipped (stepCount > 0 check fails)
			producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 0f))

			getLastStepCount() shouldBe 0
			getStepCountSinceLastCollection() shouldBe 0
		}

		@Test
		fun `same step count produces zero delta`() {
			producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))
			producer.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, 100f))

			getStepCountSinceLastCollection() shouldBe 0
		}
	}

	@Nested
	@DisplayName("onDataRequest")
	inner class OnDataRequest {

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
		fun `reports zero steps when no steps accumulated`() {
			val builder = createBuilder()
			producer.onDataRequest(builder)

			builder.stepDelta shouldBe 0
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
		fun `does not set data when step count is negative`() {
			mockkObject(Reporter)
			every { Reporter.report(any<String>()) } returns Unit

			setStepCountSinceLastCollection(-1)

			val builder = createBuilder()
			producer.onDataRequest(builder)

			builder.stepDelta shouldBe null
			unmockkObject(Reporter)
		}
	}

	@Nested
	@DisplayName("onAccuracyChanged")
	inner class OnAccuracyChanged {

		@Test
		fun `does nothing and does not throw`() {
			// Should be a no-op
			producer.onAccuracyChanged(null, 0)
			producer.onAccuracyChanged(mockk(relaxed = true), Sensor.TYPE_STEP_COUNTER)
		}
	}

	@Nested
	@DisplayName("companion constants")
	inner class CompanionConstants {

		@Test
		fun `NEW_STEPS_ARG has expected value`() {
			StepDataProducer.NEW_STEPS_ARG shouldBe "newSteps"
		}
	}
}
