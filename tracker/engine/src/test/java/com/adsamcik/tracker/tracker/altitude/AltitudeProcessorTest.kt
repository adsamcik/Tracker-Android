package com.adsamcik.tracker.tracker.altitude

import android.content.Context
import android.location.Location
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AltitudeProcessorTest {

	private lateinit var context: Context
	private lateinit var geoidAltitudeConverter: FakeGeoidAltitudeConverter
	private lateinit var processor: AltitudeProcessor

	@Before
	fun setup() {
		context = mockk(relaxed = true)
		geoidAltitudeConverter = FakeGeoidAltitudeConverter()
		processor = AltitudeProcessor(
			verticalAccuracyThresholdM = 20f,
			geoidAltitudeConverter = geoidAltitudeConverter
		)
	}

	@Test
	fun `returns null when location has no altitude`() {
		val location = createLocation(altitude = null)

		processor.process(context, location).shouldBeNull()
		geoidAltitudeConverter.conversionCount shouldBe 0
	}

	@Test
	fun `uses injected geoid converter result as MSL altitude`() {
		val converter = FakeGeoidAltitudeConverter { 420.0 }
		val processor = AltitudeProcessor(
			verticalAccuracyThresholdM = 20f,
			geoidAltitudeConverter = converter
		)
		val location = createLocation(altitude = 500.0, verticalAccuracy = 5f)

		val result = processor.process(context, location)

		result shouldBe 420.0
		converter.conversionCount shouldBe 1
	}

	@Test
	fun `returns null when geoid converter cannot produce MSL altitude`() {
		val converter = FakeGeoidAltitudeConverter { null }
		val processor = AltitudeProcessor(
			verticalAccuracyThresholdM = 20f,
			geoidAltitudeConverter = converter
		)
		val location = createLocation(altitude = 500.0, verticalAccuracy = 5f)

		processor.process(context, location).shouldBeNull()
		converter.conversionCount shouldBe 1
	}

	@Test
	fun `rejects altitude with poor vertical accuracy`() {
		val location = createLocation(altitude = 500.0, verticalAccuracy = 25f)

		processor.process(context, location).shouldBeNull()
	}

	@Test
	fun `accepts altitude with good vertical accuracy`() {
		val location = createLocation(altitude = 500.0, verticalAccuracy = 10f)

		processor.process(context, location).shouldNotBeNull()
	}

	@Test
	fun `accepts altitude with no vertical accuracy info`() {
		val location = createLocation(altitude = 500.0, verticalAccuracy = null)

		processor.process(context, location).shouldNotBeNull()
	}

	@Test
	fun `accepts altitude at exact threshold`() {
		val location = createLocation(altitude = 500.0, verticalAccuracy = 20f)

		processor.process(context, location).shouldNotBeNull()
	}

	@Test
	fun `first value passes through with minimal change`() {
		val location = createLocation(altitude = 500.0, verticalAccuracy = 5f)

		processor.process(context, location).shouldNotBeNull()
	}

	@Test
	fun `smooths consecutive readings via Kalman`() {
		val loc1 = createLocation(altitude = 500.0, verticalAccuracy = 5f)
		val result1 = processor.process(context, loc1)
		result1.shouldNotBeNull()

		val loc2 = createLocation(altitude = 600.0, verticalAccuracy = 5f)
		val result2 = processor.process(context, loc2)
		result2.shouldNotBeNull()

		abs(result2 - result1) shouldBeLessThan 100.0
	}

	@Test
	fun `isFusionCalibrated is false initially`() {
		processor.isFusionCalibrated shouldBe false
	}

	@Test
	fun `isFusionCalibrated becomes true after GPS plus barometer`() {
		val location = createLocation(altitude = 500.0, verticalAccuracy = 5f)

		processor.processWithBarometer(context, location, baroPressureHpa = 955f)

		processor.isFusionCalibrated shouldBe true
	}

	@Test
	fun `isFusionCalibrated stays false with GPS only`() {
		val location = createLocation(altitude = 500.0, verticalAccuracy = 5f)

		processor.process(context, location)

		processor.isFusionCalibrated shouldBe false
	}

	@Test
	fun `reset clears fusion calibration`() {
		val location = createLocation(altitude = 500.0, verticalAccuracy = 5f)
		processor.processWithBarometer(context, location, baroPressureHpa = 955f)
		processor.isFusionCalibrated shouldBe true

		processor.reset()

		processor.isFusionCalibrated shouldBe false
	}

	@Test
	fun `processWithBarometer with null pressure behaves like process`() {
		val loc1 = createLocation(altitude = 500.0, verticalAccuracy = 5f)
		val loc2 = createLocation(altitude = 500.0, verticalAccuracy = 5f)
		val proc1 = AltitudeProcessor(
			verticalAccuracyThresholdM = 20f,
			geoidAltitudeConverter = FakeGeoidAltitudeConverter()
		)
		val proc2 = AltitudeProcessor(
			verticalAccuracyThresholdM = 20f,
			geoidAltitudeConverter = FakeGeoidAltitudeConverter()
		)

		val result1 = proc1.process(context, loc1)
		val result2 = proc2.processWithBarometer(context, loc2, baroPressureHpa = null)

		result1.shouldNotBeNull()
		result2.shouldNotBeNull()
		result1 shouldBe result2
	}

	@Test
	fun `barometer fusion dampens GPS noise`() {
		val loc1 = createLocation(altitude = 500.0, verticalAccuracy = 10f)
		processor.processWithBarometer(context, loc1, baroPressureHpa = 955f)
		val loc2 = createLocation(altitude = 500.0, verticalAccuracy = 10f)
		processor.processWithBarometer(context, loc2, baroPressureHpa = 955f)

		val loc3 = createLocation(altitude = 530.0, verticalAccuracy = 10f)
		val result = processor.processWithBarometer(context, loc3, baroPressureHpa = 955f)

		result.shouldNotBeNull()
		abs(result - 500.0) shouldBeLessThan abs(result - 530.0)
	}

	@Test
	fun `custom vertical accuracy threshold is respected`() {
		val strictProcessor = AltitudeProcessor(
			verticalAccuracyThresholdM = 5f,
			geoidAltitudeConverter = FakeGeoidAltitudeConverter()
		)
		val location = createLocation(altitude = 500.0, verticalAccuracy = 10f)

		strictProcessor.process(context, location).shouldBeNull()
	}

	@Test
	fun `lenient threshold accepts poor accuracy`() {
		val lenientProcessor = AltitudeProcessor(
			verticalAccuracyThresholdM = 50f,
			geoidAltitudeConverter = FakeGeoidAltitudeConverter()
		)
		val location = createLocation(altitude = 500.0, verticalAccuracy = 40f)

		lenientProcessor.process(context, location).shouldNotBeNull()
	}

	@Test
	fun `reset clears EMA state`() {
		processor.process(context, createLocation(altitude = 500.0, verticalAccuracy = 5f))
		processor.process(context, createLocation(altitude = 510.0, verticalAccuracy = 5f))

		processor.reset()

		processor.process(context, createLocation(altitude = 800.0, verticalAccuracy = 5f)).shouldNotBeNull()
	}

	private fun createLocation(
		altitude: Double? = null,
		verticalAccuracy: Float? = null,
		latitude: Double = 50.0,
		longitude: Double = 14.0
	): Location {
		return Location("test").apply {
			this.latitude = latitude
			this.longitude = longitude
			time = System.currentTimeMillis()
			if (altitude != null) {
				this.altitude = altitude
			}
			if (verticalAccuracy != null) {
				this.verticalAccuracyMeters = verticalAccuracy
			}
		}
	}

	private class FakeGeoidAltitudeConverter(
		private val convert: (Location) -> Double? = { it.altitude }
	) : GeoidAltitudeConverter {
		var conversionCount = 0
			private set

		override fun toMslAltitude(context: Context, location: Location): Double? {
			conversionCount++
			return convert(location)
		}
	}
}
