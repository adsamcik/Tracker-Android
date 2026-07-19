package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeNaN
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BarometerDataProducerTest {

	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setup() {
		Dispatchers.setMain(testDispatcher)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `sea level pressure converts to approximately zero meters`() {
		val altitude = BarometerDataProducer.pressureToAltitude(1013.25f)

		abs(altitude.toDouble()) shouldBeLessThan 0.1
	}

	@Test
	fun `standard atmosphere reference pressures convert to known altitudes`() {
		val fiveHundredMeters = BarometerDataProducer.pressureToAltitude(954.6184f)
		val oneThousandMeters = BarometerDataProducer.pressureToAltitude(898.7646f)

		abs(fiveHundredMeters - 500.0) shouldBeLessThan 0.1
		abs(oneThousandMeters - 1000.0) shouldBeLessThan 0.1
	}

	@Test
	fun `invalid pressure returns NaN`() {
		BarometerDataProducer.pressureToAltitude(0f).shouldBeNaN()
		BarometerDataProducer.pressureToAltitude(-1f).shouldBeNaN()
		BarometerDataProducer.pressureToAltitude(Float.NaN).shouldBeNaN()
		BarometerDataProducer.pressureToAltitude(Float.POSITIVE_INFINITY).shouldBeNaN()
	}

	@Test
	fun `recordPressure ignores invalid readings`() {
		val producer = createProducer()
		val builder = createBuilder()

		producer.recordPressure(Float.NaN)
		producer.recordPressure(0f)
		producer.recordPressure(-1f)
		producer.recordPressure(Float.POSITIVE_INFINITY)
		producer.onDataRequest(builder)

		builder.pressure.shouldBeNull()
	}

	@Test
	fun `recordPressure averages only valid readings`() {
		val producer = createProducer()
		val builder = createBuilder()

		producer.recordPressure(Float.NaN)
		producer.recordPressure(1013.25f)
		producer.recordPressure(0f)
		producer.recordPressure(898.7646f)
		producer.onDataRequest(builder)

		val pressure = builder.pressure
		pressure.shouldNotBeNull()
		val expectedPressure = (1013.25f + 898.7646f) / 2f
		abs((pressure.pressureHpa - expectedPressure).toDouble()) shouldBeLessThan 0.001
		abs((pressure.altitudeM - BarometerDataProducer.pressureToAltitude(expectedPressure)).toDouble()) shouldBeLessThan 0.1
	}

	@Test
	fun `onDataRequest clears accumulated pressure after emission`() {
		val producer = createProducer()
		val firstBuilder = createBuilder()
		val secondBuilder = createBuilder()

		producer.recordPressure(1013.25f)
		producer.onDataRequest(firstBuilder)
		producer.onDataRequest(secondBuilder)

		firstBuilder.pressure.shouldNotBeNull()
		secondBuilder.pressure.shouldBeNull()
	}

	@Test
	fun `disable and re-enable drops pressure from the disabled interval`() = runTest {
		val producer = createProducer()
		val context = createPressureSensorContext(producer)
		producer.canBeEnabled = true
		producer.onEnable(context)
		producer.recordPressure(1013.25f)

		producer.onDisable(context)
		producer.onEnable(context)

		val emptyBuilder = createBuilder()
		producer.onDataRequest(emptyBuilder)
		emptyBuilder.pressure.shouldBeNull()

		producer.recordPressure(898.7646f)
		val enabledIntervalBuilder = createBuilder()
		producer.onDataRequest(enabledIntervalBuilder)

		enabledIntervalBuilder.pressure.shouldNotBeNull().pressureHpa shouldBe 898.7646f
	}

	@Test
	fun `enable fails when the pressure sensor is unavailable`() = runTest {
		val producer = createProducer()
		producer.canBeEnabled = true

		shouldThrow<IllegalStateException> {
			producer.onEnable(createPressureSensorContext(producer, sensor = null))
		}

		producer.isEnabled shouldBe false
	}

	@Test
	fun `enable fails when the pressure listener cannot be registered`() = runTest {
		val producer = createProducer()
		producer.canBeEnabled = true

		shouldThrow<IllegalStateException> {
			producer.onEnable(
				createPressureSensorContext(producer, registrationSucceeds = false),
			)
		}

		producer.isEnabled shouldBe false
	}

	private fun createProducer(): BarometerDataProducer =
		BarometerDataProducer(mockk<TrackerDataProducerObserver>(relaxed = true))

	private fun createPressureSensorContext(
		producer: BarometerDataProducer,
		sensor: Sensor? = mockk(relaxed = true),
		registrationSucceeds: Boolean = true,
	): Context {
		val context = mockk<Context>()
		val sensorManager = mockk<SensorManager>(relaxed = true)
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
		every { sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) } returns sensor
		if (sensor != null) {
			every {
				sensorManager.registerListener(
					producer,
					sensor,
					SensorManager.SENSOR_DELAY_NORMAL,
				)
			} returns registrationSucceeds
		}
		return context
	}

	private fun createBuilder(): TrackingCycleBuilder =
		TrackingCycleBuilder(System.currentTimeMillis(), System.nanoTime())
}
