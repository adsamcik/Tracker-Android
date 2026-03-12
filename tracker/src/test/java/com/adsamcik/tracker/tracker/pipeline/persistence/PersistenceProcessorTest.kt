package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.signal.WifiNetworkReading
import com.adsamcik.tracker.stats.api.signal.WifiSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PersistenceProcessor")
class PersistenceProcessorTest {

	private lateinit var locationDao: LocationSampleDao
	private lateinit var cellDao: CellSampleDao
	private lateinit var wifiDao: WifiObservationDao
	private lateinit var pressureDao: PressureSampleDao
	private lateinit var stepDao: StepIntervalDao
	private lateinit var activityDao: ActivitySnapshotDao
	private lateinit var errorCollector: PersistenceErrorCollector
	private lateinit var processor: PersistenceProcessor

	@BeforeEach
	fun setup() {
		locationDao = mockk(relaxed = true)
		cellDao = mockk(relaxed = true)
		wifiDao = mockk(relaxed = true)
		pressureDao = mockk(relaxed = true)
		stepDao = mockk(relaxed = true)
		activityDao = mockk(relaxed = true)
		errorCollector = mockk(relaxed = true)

		coEvery { locationDao.insert(any<Collection<LocationSample>>()) } returns emptyList()
		coEvery { cellDao.insert(any<Collection<CellSample>>()) } returns emptyList()
		coEvery { wifiDao.insert(any<Collection<WifiObservation>>()) } returns emptyList()
		coEvery { pressureDao.insert(any<Collection<PressureSample>>()) } returns emptyList()
		coEvery { stepDao.insert(any<Collection<StepInterval>>()) } returns emptyList()
		coEvery { activityDao.insert(any<Collection<ActivitySnapshot>>()) } returns emptyList()

		processor = PersistenceProcessor(
			locationSampleDao = locationDao,
			cellSampleDao = cellDao,
			wifiObservationDao = wifiDao,
			pressureSampleDao = pressureDao,
			stepIntervalDao = stepDao,
			activitySnapshotDao = activityDao,
			errorCollector = errorCollector,
		)
	}

	private fun signalWithLocation(
		timestampMs: Long = 1_000_000L,
		latDeg: Double = 50.0,
		lonDeg: Double = 14.0,
		accuracy: Float = 5f,
	): TrackingSignal = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		location = LocationSignal(
			coordinate = CoordinateE7(
				lat = LatE7.fromDegrees(latDeg),
				lon = LonE7.fromDegrees(lonDeg),
			),
			horizontalAccuracyM = accuracy,
			speed = SpeedMps(1.5f),
			altitudeM = 300f,
			rawGpsAltitudeM = 295f,
			verticalAccuracyM = 3f,
			speedAccuracyMps = 0.5f,
			provider = "fused",
		),
	)

	private fun signalWithCells(
		timestampMs: Long = 1_000_000L,
		location: LocationSignal? = null,
	): TrackingSignal = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		location = location,
		cells = CellSignal(
			towers = listOf(
				CellTowerReading(
					cellId = 12345L,
					mcc = "230",
					mnc = "01",
					networkType = 3,
					signalStrength = 25,
				),
			),
		),
	)

	private fun signalWithWifi(
		timestampMs: Long = 1_000_000L,
		location: LocationSignal? = null,
	): TrackingSignal = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		location = location,
		wifi = WifiSignal(
			networks = listOf(
				WifiNetworkReading(
					bssid = "AA:BB:CC:DD:EE:FF",
					ssid = "TestNetwork",
					capabilities = "[WPA2-PSK]",
					frequency = 2412,
					level = -65,
				),
			),
		),
	)

	private fun signalWithPressure(
		timestampMs: Long = 1_000_000L,
	): TrackingSignal = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		pressure = PressureSignal(pressureHpa = 1013.25f, altitudeM = 120f),
	)

	private val emptySignal = TrackingSignal(timestampMs = EpochMs(1_000_000L))

	@Nested
	@DisplayName("onSignal buffering")
	inner class OnSignalBuffering {

		@Test
		fun `onSignal buffers location when present`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			processor.onSignal(signalWithLocation())

			// Verify nothing flushed yet
			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }

			// Flush and verify it was buffered
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured shouldHaveSize 1

			val sample = captured.captured.first()
			sample.latE7 shouldBe LatE7.fromDegrees(50.0).raw
			sample.lonE7 shouldBe LonE7.fromDegrees(14.0).raw
			sample.quality shouldBe SampleQuality.HIGH
			sample.hAccM shouldBe 5f
			sample.altitudeM shouldBe 300f
			sample.rawGpsAltitudeM shouldBe 295f
			sample.speedMps shouldBe 1.5f
			sample.provider shouldBe "fused"
		}

		@Test
		fun `onSignal does not buffer when location absent`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			processor.onSignal(emptySignal)
			processor.onFlush()

			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
		}

		@Test
		fun `empty signal produces no buffered data`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			processor.onSignal(emptySignal)
			processor.onFlush()

			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 0) { cellDao.insert(any<Collection<CellSample>>()) }
			coVerify(exactly = 0) { wifiDao.insert(any<Collection<WifiObservation>>()) }
			coVerify(exactly = 0) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 0) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 0) { activityDao.insert(any<Collection<ActivitySnapshot>>()) }
		}
	}

	@Nested
	@DisplayName("onFlush persistence")
	inner class OnFlushPersistence {

		@Test
		fun `onFlush writes buffered locations to DAO`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			// Buffer two location signals
			processor.onSignal(signalWithLocation(timestampMs = 1_000_000L))
			processor.onSignal(signalWithLocation(timestampMs = 2_000_000L))

			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured shouldHaveSize 2
		}

		@Test
		fun `onFlush writes buffered cells to DAO`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			val locationSig = signalWithLocation().location
			processor.onSignal(signalWithCells(location = locationSig))

			processor.onFlush()

			val captured = slot<Collection<CellSample>>()
			coVerify(exactly = 1) { cellDao.insert(capture(captured)) }
			captured.captured shouldHaveSize 1

			val cell = captured.captured.first()
			cell.cellId shouldBe 12345L
			cell.mcc shouldBe 230
			cell.mnc shouldBe 1
			cell.networkType shouldBe 3
			cell.signalStrength shouldBe 25
			cell.provenance shouldBe CoordinateProvenance.DIRECT
		}

		@Test
		fun `onFlush writes buffered wifi to DAO`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			processor.onSignal(signalWithWifi())

			processor.onFlush()

			val captured = slot<Collection<WifiObservation>>()
			coVerify(exactly = 1) { wifiDao.insert(capture(captured)) }
			captured.captured shouldHaveSize 1

			val wifi = captured.captured.first()
			wifi.bssid shouldBe "AA:BB:CC:DD:EE:FF"
			wifi.ssid shouldBe "TestNetwork"
			wifi.capabilities shouldBe "[WPA2-PSK]"
			wifi.frequency shouldBe 2412
			wifi.level shouldBe -65
			wifi.provenance shouldBe CoordinateProvenance.UNKNOWN
		}

		@Test
		fun `onFlush writes buffered pressure to DAO`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			processor.onSignal(signalWithPressure())

			processor.onFlush()

			val captured = slot<Collection<PressureSample>>()
			coVerify(exactly = 1) { pressureDao.insert(capture(captured)) }
			captured.captured shouldHaveSize 1

			val sample = captured.captured.first()
			sample.pressureHpa shouldBe 1013.25f
			sample.altitudeM shouldBe 120f
		}
	}

	@Nested
	@DisplayName("onStop")
	inner class OnStop {

		@Test
		fun `onStop flushes remaining buffers`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			processor.onSignal(signalWithLocation())
			processor.onSignal(signalWithPressure())

			val events = processor.onStop()
			events.shouldBeEmpty()

			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 1) { pressureDao.insert(any<Collection<PressureSample>>()) }
		}
	}

	@Nested
	@DisplayName("error isolation")
	inner class ErrorIsolation {

		@Test
		fun `flush failure for one table does not lose other tables`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			// Cell DAO throws
			coEvery { cellDao.insert(any<Collection<CellSample>>()) } throws RuntimeException("DB locked")

			val locationSig = signalWithLocation().location
			processor.onSignal(signalWithCells(location = locationSig))
			processor.onSignal(signalWithWifi())

			processor.onFlush()

			// Cell failed, but wifi should still be written
			coVerify(exactly = 1) { wifiDao.insert(any<Collection<WifiObservation>>()) }

			// Error reported for cell
			coVerify(exactly = 1) {
				errorCollector.reportError(match<PersistenceError> { it.source == "persistence" })
			}
		}

		@Test
		fun `CancellationException propagates from flush`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			coEvery {
				locationDao.insert(any<Collection<LocationSample>>())
			} throws CancellationException("cancelled")

			processor.onSignal(signalWithLocation())

			var caught = false
			try {
				processor.onFlush()
			} catch (_: CancellationException) {
				caught = true
			}

			caught shouldBe true
		}
	}

	@Nested
	@DisplayName("quality classification")
	inner class QualityClassification {

		@Test
		fun `accuracy below 10m is HIGH`() {
			PersistenceProcessor.classifyQuality(5f) shouldBe SampleQuality.HIGH
		}

		@Test
		fun `accuracy at 10m is MEDIUM`() {
			PersistenceProcessor.classifyQuality(10f) shouldBe SampleQuality.MEDIUM
		}

		@Test
		fun `accuracy at 50m is LOW`() {
			PersistenceProcessor.classifyQuality(50f) shouldBe SampleQuality.LOW
		}

		@Test
		fun `null accuracy is COARSE`() {
			PersistenceProcessor.classifyQuality(null) shouldBe SampleQuality.COARSE
		}
	}

	@Nested
	@DisplayName("motion state inference")
	inner class MotionStateInference {

		@Test
		fun `STILL activity maps to STILL`() {
			val signal = ActivitySignal(DetectedActivityType.STILL, ActivityConfidence(80))
			PersistenceProcessor.inferMotionState(signal) shouldBe MotionState.STILL
		}

		@Test
		fun `WALKING activity maps to MOVING`() {
			val signal = ActivitySignal(DetectedActivityType.WALKING, ActivityConfidence(90))
			PersistenceProcessor.inferMotionState(signal) shouldBe MotionState.MOVING
		}

		@Test
		fun `null activity maps to null`() {
			PersistenceProcessor.inferMotionState(null) shouldBe null
		}

		@Test
		fun `UNKNOWN activity maps to UNKNOWN`() {
			val signal = ActivitySignal(DetectedActivityType.UNKNOWN, ActivityConfidence(50))
			PersistenceProcessor.inferMotionState(signal) shouldBe MotionState.UNKNOWN
		}
	}

	@Nested
	@DisplayName("cell and wifi provenance")
	inner class ProvenanceMapping {

		@Test
		fun `cells without location get UNKNOWN provenance`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			processor.onSignal(signalWithCells(location = null))
			processor.onFlush()

			val captured = slot<Collection<CellSample>>()
			coVerify(exactly = 1) { cellDao.insert(capture(captured)) }
			captured.captured.first().provenance shouldBe CoordinateProvenance.UNKNOWN
			captured.captured.first().latE7 shouldBe null
			captured.captured.first().lonE7 shouldBe null
		}

		@Test
		fun `wifi with location gets DIRECT provenance`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			val locationSig = signalWithLocation().location
			processor.onSignal(signalWithWifi(location = locationSig))
			processor.onFlush()

			val captured = slot<Collection<WifiObservation>>()
			coVerify(exactly = 1) { wifiDao.insert(capture(captured)) }
			captured.captured.first().provenance shouldBe CoordinateProvenance.DIRECT
		}
	}

	@Nested
	@DisplayName("step and activity buffering")
	inner class StepAndActivity {

		@Test
		fun `steps are buffered and flushed`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			val signal = TrackingSignal(
				timestampMs = EpochMs(1_000_000L),
				steps = StepSignal(
					stepDelta = StepCount(15),
					totalStepsSinceBoot = 5000L,
					sensorValueStart = 4985,
					sensorValueEnd = 5000,
					sensorReset = false,
				),
			)

			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<StepInterval>>()
			coVerify(exactly = 1) { stepDao.insert(capture(captured)) }
			captured.captured shouldHaveSize 1
			captured.captured.first().stepCount shouldBe 15
		}

		@Test
		fun `activity is buffered and flushed`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			val signal = TrackingSignal(
				timestampMs = EpochMs(1_000_000L),
				activity = ActivitySignal(
					type = DetectedActivityType.WALKING,
					confidence = ActivityConfidence(85),
				),
			)

			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<ActivitySnapshot>>()
			coVerify(exactly = 1) { activityDao.insert(capture(captured)) }
			captured.captured shouldHaveSize 1
			captured.captured.first().activityType shouldBe DetectedActivityType.WALKING.ordinal
			captured.captured.first().confidence shouldBe 85
		}
	}
}
