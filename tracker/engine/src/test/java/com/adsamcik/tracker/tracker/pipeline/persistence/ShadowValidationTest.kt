package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
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
import com.adsamcik.tracker.stats.api.signal.PolicySignal
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
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Shadow validation test — safety gate for PersistenceProcessor correctness.
 *
 * Proves that [PersistenceProcessor] produces the expected database rows for
 * location, cell, wifi, pressure, step, and activity data.
 *
 * Each test creates synthetic tracking signals, runs them through `onSignal()` + `onFlush()`
 * (or `onStop()`), captures the entities sent to each DAO, and asserts field-level parity
 * with the expected output.
 */
@DisplayName("Shadow Validation — PersistenceProcessor vs old writers")
class ShadowValidationTest {

	private lateinit var locationDao: LocationSampleDao
	private lateinit var cellDao: CellSampleDao
	private lateinit var wifiDao: WifiObservationDao
	private lateinit var pressureDao: PressureSampleDao
	private lateinit var stepDao: StepIntervalDao
	private lateinit var activityDao: ActivitySnapshotDao
	private lateinit var pendingSignalDao: PendingSignalDao
	private lateinit var durableBuffer: DurableSignalBuffer
	private lateinit var errorCollector: PersistenceErrorCollector
	private lateinit var processor: PersistenceProcessor

	private val transactor = object : TrackingPersistenceTransactor {
		override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
	}

	@BeforeEach
	fun setup() {
		locationDao = mockk(relaxed = true)
		cellDao = mockk(relaxed = true)
		wifiDao = mockk(relaxed = true)
		pressureDao = mockk(relaxed = true)
		stepDao = mockk(relaxed = true)
		activityDao = mockk(relaxed = true)
		pendingSignalDao = mockk(relaxed = true)
		durableBuffer = mockk(relaxed = true)
		errorCollector = mockk(relaxed = true)

		coEvery { locationDao.insert(any<Collection<LocationSample>>()) } returns emptyList()
		coEvery { cellDao.insert(any<Collection<CellSample>>()) } returns emptyList()
		coEvery { wifiDao.insert(any<Collection<WifiObservation>>()) } returns emptyList()
		coEvery { pressureDao.insert(any<Collection<PressureSample>>()) } returns emptyList()
		coEvery { stepDao.insert(any<Collection<StepInterval>>()) } returns emptyList()
		coEvery { activityDao.insert(any<Collection<ActivitySnapshot>>()) } returns emptyList()
		coEvery { durableBuffer.hasPendingEntries() } returns false
		coEvery { durableBuffer.checkpoint(any()) } returns emptyList()

		processor = PersistenceProcessor(
			locationSampleDao = locationDao,
			cellSampleDao = cellDao,
			wifiObservationDao = wifiDao,
			pressureSampleDao = pressureDao,
			stepIntervalDao = stepDao,
			activitySnapshotDao = activityDao,
			pendingSignalDao = pendingSignalDao,
			durableBuffer = durableBuffer,
			transactor = transactor,
			errorCollector = errorCollector,
		)
	}

	private suspend fun startProcessor() {
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
	}

	/** Capture the most recent collection insert for a given DAO. */
	private inline fun <reified T : Any> captureInserted(
		daoVerify: () -> Unit,
	): List<T> {
		val captured = slot<Collection<T>>()
		daoVerify()
		return if (captured.isCaptured) captured.captured.toList() else emptyList()
	}

	// -----------------------------------------------------------------------
	// Realistic signal factories — European coordinates (Brno, CZ)
	// -----------------------------------------------------------------------

	private fun fullLocationSignal(
		latDeg: Double = 49.2,
		lonDeg: Double = 16.6,
		accuracy: Float = 5f,
		speed: Float = 1.5f,
		altitudeM: Float = 300f,
		rawGpsAltitudeM: Float = 295f,
		verticalAccuracyM: Float = 3f,
		speedAccuracyMps: Float = 0.5f,
		provider: String = "fused",
	) = LocationSignal(
		coordinate = CoordinateE7(
			lat = LatE7.fromDegrees(latDeg),
			lon = LonE7.fromDegrees(lonDeg),
		),
		horizontalAccuracyM = accuracy,
		speed = SpeedMps(speed),
		altitudeM = altitudeM,
		rawGpsAltitudeM = rawGpsAltitudeM,
		verticalAccuracyM = verticalAccuracyM,
		speedAccuracyMps = speedAccuracyMps,
		provider = provider,
	)

	private fun fullSignal(
		timestampMs: Long = BASE_TIME,
		elapsedRealtimeNanos: Long = BASE_ELAPSED_NANOS,
		location: LocationSignal? = fullLocationSignal(),
		activity: ActivitySignal? = ActivitySignal(DetectedActivityType.WALKING, ActivityConfidence(85)),
		steps: StepSignal? = StepSignal(
			stepDelta = StepCount(15),
			totalStepsSinceBoot = 5000L,
			sensorValueStart = 4985,
			sensorValueEnd = 5000,
			sensorReset = false,
		),
		cells: CellSignal? = CellSignal(
			towers = listOf(
				CellTowerReading(
					cellId = 12345L,
					mcc = "230",
					mnc = "01",
					networkType = 13, // LTE
					signalStrength = -85,
				),
			),
		),
		wifi: WifiSignal? = WifiSignal(
			networks = listOf(
				WifiNetworkReading(
					bssid = "AA:BB:CC:DD:EE:FF",
					ssid = "TestNetwork",
					capabilities = "[WPA2-PSK-CCMP][ESS]",
					frequency = 2412,
					level = -65,
				),
			),
		),
		pressure: PressureSignal? = PressureSignal(pressureHpa = 1013.25f, altitudeM = 120f),
		policy: PolicySignal? = PolicySignal(tier = PolicyTier.ACTIVE, policyName = "ACTIVE_BALANCED"),
	) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		location = location,
		activity = activity,
		steps = steps,
		cells = cells,
		wifi = wifi,
		pressure = pressure,
		policy = policy,
	)

	// -----------------------------------------------------------------------
	// 1. Location parity
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("1 — Location parity")
	inner class LocationParity {

		@Test
		fun `GPS fix with all fields produces correct LocationSample`() = runTest {
			startProcessor()
			processor.onSignal(fullSignal())
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			val rows = captured.captured.toList()
			rows shouldHaveSize 1

			val sample = rows.first()
			sample.timeMs shouldBe BASE_TIME
			sample.elapsedRealtimeNanos shouldBe BASE_ELAPSED_NANOS
			sample.latE7 shouldBe LatE7.fromDegrees(49.2).raw
			sample.lonE7 shouldBe LonE7.fromDegrees(16.6).raw
			sample.altitudeM shouldBe 300f
			sample.rawGpsAltitudeM shouldBe 295f
			sample.hAccM shouldBe 5f
			sample.vAccM shouldBe 3f
			sample.speedMps shouldBe 1.5f
			sample.speedAccuracyMps shouldBe 0.5f
			sample.provider shouldBe "fused"
			sample.quality shouldBe SampleQuality.HIGH
			sample.motionState shouldBe MotionState.MOVING
			sample.policy shouldBe "ACTIVE_BALANCED"
			sample.bucketId.shouldBeNull()
		}

		@Test
		fun `accuracy below 10m classified HIGH`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(location = fullLocationSignal(accuracy = 5f)),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().quality shouldBe SampleQuality.HIGH
		}

		@Test
		fun `accuracy at 10m classified MEDIUM — boundary`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(location = fullLocationSignal(accuracy = 10f)),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().quality shouldBe SampleQuality.MEDIUM
		}

		@Test
		fun `accuracy at 49m classified MEDIUM`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(location = fullLocationSignal(accuracy = 49f)),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().quality shouldBe SampleQuality.MEDIUM
		}

		@Test
		fun `accuracy at 50m classified LOW — boundary`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(location = fullLocationSignal(accuracy = 50f)),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().quality shouldBe SampleQuality.LOW
		}

		@Test
		fun `accuracy at 500m classified LOW`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(location = fullLocationSignal(accuracy = 500f)),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().quality shouldBe SampleQuality.LOW
		}

		@Test
		fun `zero accuracy classified HIGH`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(location = fullLocationSignal(accuracy = 0f)),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().quality shouldBe SampleQuality.HIGH
		}

		@Test
		fun `STILL activity produces STILL motion state`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(
					activity = ActivitySignal(DetectedActivityType.STILL, ActivityConfidence(90)),
				),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().motionState shouldBe MotionState.STILL
		}

		@Test
		fun `UNKNOWN activity produces UNKNOWN motion state`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(
					activity = ActivitySignal(DetectedActivityType.UNKNOWN, ActivityConfidence(50)),
				),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().motionState shouldBe MotionState.UNKNOWN
		}

		@Test
		fun `null activity produces null motion state`() = runTest {
			startProcessor()
			processor.onSignal(fullSignal(activity = null))
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().motionState.shouldBeNull()
		}

		@Test
		fun `GPS provider preserved`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(location = fullLocationSignal(provider = "gps")),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().provider shouldBe "gps"
		}

		@Test
		fun `network provider preserved`() = runTest {
			startProcessor()
			processor.onSignal(
				fullSignal(location = fullLocationSignal(provider = "network")),
			)
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().provider shouldBe "network"
		}

		@Test
		fun `no policy produces null policy field`() = runTest {
			startProcessor()
			processor.onSignal(fullSignal(policy = null))
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured.first().policy.shouldBeNull()
		}
	}

	// -----------------------------------------------------------------------
	// 2. Pressure parity
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("2 — Pressure parity")
	inner class PressureParity {

		@Test
		fun `pressure reading produces correct PressureSample`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				elapsedRealtimeNanos = BASE_ELAPSED_NANOS,
				pressure = PressureSignal(pressureHpa = 1013.25f, altitudeM = 120f),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<PressureSample>>()
			coVerify(exactly = 1) { pressureDao.insert(capture(captured)) }
			val rows = captured.captured.toList()
			rows shouldHaveSize 1

			val sample = rows.first()
			sample.timeMs shouldBe BASE_TIME
			sample.elapsedRealtimeNanos shouldBe BASE_ELAPSED_NANOS
			sample.pressureHpa shouldBe 1013.25f
			sample.altitudeM shouldBe 120f
			sample.bucketId.shouldBeNull()
		}

		@Test
		fun `low pressure reading at high altitude`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				elapsedRealtimeNanos = BASE_ELAPSED_NANOS,
				pressure = PressureSignal(pressureHpa = 540.2f, altitudeM = 5000f),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<PressureSample>>()
			coVerify(exactly = 1) { pressureDao.insert(capture(captured)) }
			val sample = captured.captured.first()
			sample.pressureHpa shouldBe 540.2f
			sample.altitudeM shouldBe 5000f
		}
	}

	// -----------------------------------------------------------------------
	// 3. Cell parity
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("3 — Cell parity")
	inner class CellParity {

		@Test
		fun `cell tower with location produces correct CellSample with DIRECT provenance`() = runTest {
			startProcessor()

			val location = fullLocationSignal()
			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				location = location,
				cells = CellSignal(
					towers = listOf(
						CellTowerReading(
							cellId = 12345L,
							mcc = "230",
							mnc = "01",
							networkType = 13,
							signalStrength = -85,
						),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<CellSample>>()
			coVerify(exactly = 1) { cellDao.insert(capture(captured)) }
			val rows = captured.captured.toList()
			rows shouldHaveSize 1

			val cell = rows.first()
			cell.timeMs shouldBe BASE_TIME
			cell.cellId shouldBe 12345L
			cell.lac shouldBe 0
			cell.mcc shouldBe 230
			cell.mnc shouldBe 1
			cell.networkType shouldBe 13
			cell.signalStrength shouldBe -85
			cell.latE7 shouldBe LatE7.fromDegrees(49.2).raw
			cell.lonE7 shouldBe LonE7.fromDegrees(16.6).raw
			cell.provenance shouldBe CoordinateProvenance.DIRECT
		}

		@Test
		fun `cell tower without location produces UNKNOWN provenance and null coords`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				cells = CellSignal(
					towers = listOf(
						CellTowerReading(
							cellId = 67890L,
							mcc = "262",
							mnc = "02",
							networkType = 3,
							signalStrength = -70,
						),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<CellSample>>()
			coVerify(exactly = 1) { cellDao.insert(capture(captured)) }
			val cell = captured.captured.first()
			cell.latE7.shouldBeNull()
			cell.lonE7.shouldBeNull()
			cell.provenance shouldBe CoordinateProvenance.UNKNOWN
		}

		@Test
		fun `multiple cell towers produce one row per tower`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				location = fullLocationSignal(),
				cells = CellSignal(
					towers = listOf(
						CellTowerReading(cellId = 111L, mcc = "230", mnc = "01", networkType = 13, signalStrength = -80),
						CellTowerReading(cellId = 222L, mcc = "230", mnc = "02", networkType = 3, signalStrength = -95),
						CellTowerReading(cellId = 333L, mcc = "262", mnc = "01", networkType = 7, signalStrength = -75),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<CellSample>>()
			coVerify(exactly = 1) { cellDao.insert(capture(captured)) }
			val rows = captured.captured.toList()
			rows shouldHaveSize 3

			rows[0].cellId shouldBe 111L
			rows[0].mcc shouldBe 230
			rows[0].mnc shouldBe 1

			rows[1].cellId shouldBe 222L
			rows[1].mcc shouldBe 230
			rows[1].mnc shouldBe 2

			rows[2].cellId shouldBe 333L
			rows[2].mcc shouldBe 262
			rows[2].mnc shouldBe 1

			// All share the same timestamp and location
			rows.forEach { cell ->
				cell.timeMs shouldBe BASE_TIME
				cell.latE7 shouldBe LatE7.fromDegrees(49.2).raw
				cell.lonE7 shouldBe LonE7.fromDegrees(16.6).raw
				cell.provenance shouldBe CoordinateProvenance.DIRECT
			}
		}

		@Test
		fun `non-numeric MCC-MNC default to zero`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				cells = CellSignal(
					towers = listOf(
						CellTowerReading(
							cellId = 999L,
							mcc = "unknown",
							mnc = "",
							networkType = 1,
							signalStrength = -100,
						),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<CellSample>>()
			coVerify(exactly = 1) { cellDao.insert(capture(captured)) }
			val cell = captured.captured.first()
			cell.mcc shouldBe 0
			cell.mnc shouldBe 0
		}

		@Test
		fun `empty cell tower list produces no rows`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				cells = CellSignal(towers = emptyList()),
			)
			processor.onSignal(signal)
			processor.onFlush()

			coVerify(exactly = 0) { cellDao.insert(any<Collection<CellSample>>()) }
		}
	}

	// -----------------------------------------------------------------------
	// 4. WiFi parity
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("4 — WiFi parity")
	inner class WifiParity {

		@Test
		fun `wifi scan produces correct WifiObservation with DIRECT provenance`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				location = fullLocationSignal(),
				wifi = WifiSignal(
					networks = listOf(
						WifiNetworkReading(
							bssid = "AA:BB:CC:DD:EE:FF",
							ssid = "TestNetwork",
							capabilities = "[WPA2-PSK-CCMP][ESS]",
							frequency = 2412,
							level = -65,
						),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<WifiObservation>>()
			coVerify(exactly = 1) { wifiDao.insert(capture(captured)) }
			val rows = captured.captured.toList()
			rows shouldHaveSize 1

			val wifi = rows.first()
			wifi.timeMs shouldBe BASE_TIME
			wifi.bssid shouldBe "AA:BB:CC:DD:EE:FF"
			wifi.ssid shouldBe "TestNetwork"
			wifi.capabilities shouldBe "[WPA2-PSK-CCMP][ESS]"
			wifi.frequency shouldBe 2412
			wifi.level shouldBe -65
			wifi.latE7 shouldBe LatE7.fromDegrees(49.2).raw
			wifi.lonE7 shouldBe LonE7.fromDegrees(16.6).raw
			wifi.provenance shouldBe CoordinateProvenance.DIRECT
		}

		@Test
		fun `wifi without location gets UNKNOWN provenance`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				wifi = WifiSignal(
					networks = listOf(
						WifiNetworkReading(
							bssid = "11:22:33:44:55:66",
							ssid = "OpenWifi",
							capabilities = "[ESS]",
							frequency = 5180,
							level = -80,
						),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<WifiObservation>>()
			coVerify(exactly = 1) { wifiDao.insert(capture(captured)) }
			val wifi = captured.captured.first()
			wifi.latE7.shouldBeNull()
			wifi.lonE7.shouldBeNull()
			wifi.provenance shouldBe CoordinateProvenance.UNKNOWN
		}

		@Test
		fun `empty SSID replaced with unknown marker`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				wifi = WifiSignal(
					networks = listOf(
						WifiNetworkReading(
							bssid = "FF:FF:FF:FF:FF:FF",
							ssid = "",
							capabilities = "",
							frequency = 2437,
							level = -90,
						),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<WifiObservation>>()
			coVerify(exactly = 1) { wifiDao.insert(capture(captured)) }
			captured.captured.first().ssid shouldBe "<unknown>"
		}

		@Test
		fun `Unicode SSID preserved`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				wifi = WifiSignal(
					networks = listOf(
						WifiNetworkReading(
							bssid = "00:11:22:33:44:55",
							ssid = "Kavárna ☕ Čerstvá",
							capabilities = "[WPA3-SAE]",
							frequency = 5745,
							level = -55,
						),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<WifiObservation>>()
			coVerify(exactly = 1) { wifiDao.insert(capture(captured)) }
			captured.captured.first().ssid shouldBe "Kavárna ☕ Čerstvá"
		}

		@Test
		fun `multiple wifi networks produce one row per network`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				location = fullLocationSignal(),
				wifi = WifiSignal(
					networks = listOf(
						WifiNetworkReading(bssid = "AA:AA:AA:AA:AA:AA", ssid = "Net1", capabilities = "", frequency = 2412, level = -50),
						WifiNetworkReading(bssid = "BB:BB:BB:BB:BB:BB", ssid = "Net2", capabilities = "", frequency = 5180, level = -70),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<WifiObservation>>()
			coVerify(exactly = 1) { wifiDao.insert(capture(captured)) }
			val rows = captured.captured.toList()
			rows shouldHaveSize 2
			rows[0].bssid shouldBe "AA:AA:AA:AA:AA:AA"
			rows[1].bssid shouldBe "BB:BB:BB:BB:BB:BB"
		}
	}

	// -----------------------------------------------------------------------
	// 5. Empty signal
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("5 — Empty signal")
	inner class EmptySignal {

		@Test
		fun `all-null TrackingSignal produces zero rows in any table`() = runTest {
			startProcessor()

			val signal = TrackingSignal(timestampMs = EpochMs(BASE_TIME))
			processor.onSignal(signal)
			processor.onFlush()

			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 0) { cellDao.insert(any<Collection<CellSample>>()) }
			coVerify(exactly = 0) { wifiDao.insert(any<Collection<WifiObservation>>()) }
			coVerify(exactly = 0) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 0) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 0) { activityDao.insert(any<Collection<ActivitySnapshot>>()) }
		}
	}

	// -----------------------------------------------------------------------
	// 6. Partial signal
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("6 — Partial signal")
	inner class PartialSignal {

		@Test
		fun `location-only signal produces only LocationSample rows`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				elapsedRealtimeNanos = BASE_ELAPSED_NANOS,
				location = fullLocationSignal(),
			)
			processor.onSignal(signal)
			processor.onFlush()

			// Location was written
			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			captured.captured shouldHaveSize 1

			// Nothing else
			coVerify(exactly = 0) { cellDao.insert(any<Collection<CellSample>>()) }
			coVerify(exactly = 0) { wifiDao.insert(any<Collection<WifiObservation>>()) }
			coVerify(exactly = 0) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 0) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 0) { activityDao.insert(any<Collection<ActivitySnapshot>>()) }
		}

		@Test
		fun `pressure-only signal produces only PressureSample rows`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				pressure = PressureSignal(pressureHpa = 980f, altitudeM = 400f),
			)
			processor.onSignal(signal)
			processor.onFlush()

			coVerify(exactly = 1) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 0) { cellDao.insert(any<Collection<CellSample>>()) }
			coVerify(exactly = 0) { wifiDao.insert(any<Collection<WifiObservation>>()) }
			coVerify(exactly = 0) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 0) { activityDao.insert(any<Collection<ActivitySnapshot>>()) }
		}

		@Test
		fun `steps and activity without location produce only those tables`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				steps = StepSignal(
					stepDelta = StepCount(20),
					totalStepsSinceBoot = 8000L,
					sensorValueStart = 7980,
					sensorValueEnd = 8000,
					sensorReset = false,
				),
				activity = ActivitySignal(DetectedActivityType.WALKING, ActivityConfidence(90)),
			)
			processor.onSignal(signal)
			processor.onFlush()

			coVerify(exactly = 1) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 1) { activityDao.insert(any<Collection<ActivitySnapshot>>()) }
			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 0) { cellDao.insert(any<Collection<CellSample>>()) }
			coVerify(exactly = 0) { wifiDao.insert(any<Collection<WifiObservation>>()) }
			coVerify(exactly = 0) { pressureDao.insert(any<Collection<PressureSample>>()) }
		}

		@Test
		fun `step fields map correctly to StepInterval`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				steps = StepSignal(
					stepDelta = StepCount(42),
					totalStepsSinceBoot = 12345L,
					sensorValueStart = 12303,
					sensorValueEnd = 12345,
					sensorReset = true,
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<StepInterval>>()
			coVerify(exactly = 1) { stepDao.insert(capture(captured)) }
			val step = captured.captured.first()
			step.startTimeMs shouldBe BASE_TIME
			step.endTimeMs shouldBe BASE_TIME
			step.stepCount shouldBe 42
			step.sensorValueStart shouldBe 12303
			step.sensorValueEnd shouldBe 12345
			step.sensorReset shouldBe true
		}

		@Test
		fun `activity fields map correctly to ActivitySnapshot`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				activity = ActivitySignal(
					type = DetectedActivityType.IN_VEHICLE,
					confidence = ActivityConfidence(72),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<ActivitySnapshot>>()
			coVerify(exactly = 1) { activityDao.insert(capture(captured)) }
			val snapshot = captured.captured.first()
			snapshot.timeMs shouldBe BASE_TIME
			snapshot.activityType shouldBe DetectedActivityType.IN_VEHICLE.ordinal
			snapshot.confidence shouldBe 72
			snapshot.isTransition shouldBe false
		}
	}

	// -----------------------------------------------------------------------
	// 7. Batch flush
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("7 — Batch flush")
	inner class BatchFlush {

		@Test
		fun `10 location signals all flushed correctly`() = runTest {
			startProcessor()

			val signals = (0 until 10).map { i ->
				TrackingSignal(
					timestampMs = EpochMs(BASE_TIME + i * 1000L),
					elapsedRealtimeNanos = BASE_ELAPSED_NANOS + i * 1_000_000_000L,
					location = fullLocationSignal(
						latDeg = 49.2 + i * 0.001,
						lonDeg = 16.6 + i * 0.001,
					),
				)
			}
			signals.forEach { processor.onSignal(it) }
			processor.onFlush()

			val captured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
			val rows = captured.captured.toList()
			rows shouldHaveSize 10

			// Verify each row has the right timestamp and coordinates
			rows.forEachIndexed { i, sample ->
				sample.timeMs shouldBe BASE_TIME + i * 1000L
				sample.latE7 shouldBe LatE7.fromDegrees(49.2 + i * 0.001).raw
				sample.lonE7 shouldBe LonE7.fromDegrees(16.6 + i * 0.001).raw
			}
		}

		@Test
		fun `15 pressure signals flushed across multiple batches`() = runTest {
			startProcessor()

			// Pressure batch size is 5, so 15 signals → 3 batches
			repeat(15) { i ->
				processor.onSignal(
					TrackingSignal(
						timestampMs = EpochMs(BASE_TIME + i * 500L),
						elapsedRealtimeNanos = BASE_ELAPSED_NANOS + i * 500_000_000L,
						pressure = PressureSignal(
							pressureHpa = 1013.25f - i * 0.1f,
							altitudeM = 120f + i * 0.5f,
						),
					),
				)
			}
			processor.onFlush()

			// Should be 3 batch inserts (5, 5, 5) based on PRESSURE_BATCH_SIZE=5
			coVerify(exactly = 3) { pressureDao.insert(any<Collection<PressureSample>>()) }
		}

		@Test
		fun `mixed signal types all flushed in one pass`() = runTest {
			startProcessor()

			// Send 3 full signals with all data types
			repeat(3) { i ->
				processor.onSignal(
					fullSignal(timestampMs = BASE_TIME + i * 1000L),
				)
			}
			processor.onFlush()

			// Each type should have been flushed once with 3 items
			val locCapture = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(locCapture)) }
			locCapture.captured shouldHaveSize 3

			val pressCapture = slot<Collection<PressureSample>>()
			coVerify(exactly = 1) { pressureDao.insert(capture(pressCapture)) }
			pressCapture.captured shouldHaveSize 3

			// Cells: 3 signals × 1 tower each = 3 CellSamples
			val cellCapture = slot<Collection<CellSample>>()
			coVerify(exactly = 1) { cellDao.insert(capture(cellCapture)) }
			cellCapture.captured shouldHaveSize 3

			// Wifi: 3 signals × 1 network each = 3 WifiObservations
			val wifiCapture = slot<Collection<WifiObservation>>()
			coVerify(exactly = 1) { wifiDao.insert(capture(wifiCapture)) }
			wifiCapture.captured shouldHaveSize 3
		}

		@Test
		fun `double flush does not re-insert — buffers cleared after first flush`() = runTest {
			startProcessor()

			processor.onSignal(fullSignal())
			processor.onFlush()
			processor.onFlush() // second flush with empty buffers

			// Only one insert per DAO
			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 1) { pressureDao.insert(any<Collection<PressureSample>>()) }
		}
	}

	// -----------------------------------------------------------------------
	// 8. Final flush on stop
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("8 — Final flush on stop")
	inner class FinalFlushOnStop {

		@Test
		fun `buffered data is written when onStop is called`() = runTest {
			startProcessor()

			processor.onSignal(fullSignal(timestampMs = BASE_TIME))
			processor.onSignal(fullSignal(timestampMs = BASE_TIME + 1000L))

			val events = processor.onStop()
			events.shouldBeEmpty()

			// Locations were flushed
			val locCapture = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(locCapture)) }
			locCapture.captured shouldHaveSize 2

			// Pressure was flushed
			val pressCapture = slot<Collection<PressureSample>>()
			coVerify(exactly = 1) { pressureDao.insert(capture(pressCapture)) }
			pressCapture.captured shouldHaveSize 2

			// Steps were flushed
			val stepCapture = slot<Collection<StepInterval>>()
			coVerify(exactly = 1) { stepDao.insert(capture(stepCapture)) }
			stepCapture.captured shouldHaveSize 2

			// Activities were flushed
			val actCapture = slot<Collection<ActivitySnapshot>>()
			coVerify(exactly = 1) { activityDao.insert(capture(actCapture)) }
			actCapture.captured shouldHaveSize 2
		}

		@Test
		fun `onStop after onFlush only writes data added since last flush`() = runTest {
			startProcessor()

			// First signal → flush
			processor.onSignal(fullSignal(timestampMs = BASE_TIME))
			processor.onFlush()

			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }

			// Second signal → stop
			processor.onSignal(fullSignal(timestampMs = BASE_TIME + 1000L))
			processor.onStop()

			// Two total inserts: one from flush, one from stop
			coVerify(exactly = 2) { locationDao.insert(any<Collection<LocationSample>>()) }
		}

		@Test
		fun `onStop with empty buffers does not insert`() = runTest {
			startProcessor()

			processor.onStop()

			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 0) { cellDao.insert(any<Collection<CellSample>>()) }
			coVerify(exactly = 0) { wifiDao.insert(any<Collection<WifiObservation>>()) }
			coVerify(exactly = 0) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 0) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 0) { activityDao.insert(any<Collection<ActivitySnapshot>>()) }
		}
	}

	// -----------------------------------------------------------------------
	// 9. Edge cases
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("9 — Edge cases")
	inner class EdgeCases {

		@Test
		fun `all activity types map to correct ordinal values`() = runTest {
			val types = listOf(
				DetectedActivityType.STILL,
				DetectedActivityType.WALKING,
				DetectedActivityType.RUNNING,
				DetectedActivityType.ON_BICYCLE,
				DetectedActivityType.IN_VEHICLE,
				DetectedActivityType.ON_FOOT,
				DetectedActivityType.TILTING,
				DetectedActivityType.UNKNOWN,
			)

			for (type in types) {
				setup() // reset mocks
				startProcessor()

				processor.onSignal(
					TrackingSignal(
						timestampMs = EpochMs(BASE_TIME),
						activity = ActivitySignal(type, ActivityConfidence(75)),
					),
				)
				processor.onFlush()

				val captured = slot<Collection<ActivitySnapshot>>()
				coVerify(exactly = 1) { activityDao.insert(capture(captured)) }
				captured.captured.first().activityType shouldBe type.ordinal
			}
		}

		@Test
		fun `locomotion activity types produce MOVING motion state`() = runTest {
			val movingTypes = listOf(
				DetectedActivityType.WALKING,
				DetectedActivityType.RUNNING,
				DetectedActivityType.ON_FOOT,
				DetectedActivityType.IN_VEHICLE,
				DetectedActivityType.ON_BICYCLE,
			)

			for (type in movingTypes) {
				setup()
				startProcessor()

				processor.onSignal(
					fullSignal(
						activity = ActivitySignal(type, ActivityConfidence(80)),
					),
				)
				processor.onFlush()

				val captured = slot<Collection<LocationSample>>()
				coVerify(exactly = 1) { locationDao.insert(capture(captured)) }
				captured.captured.first().motionState shouldBe MotionState.MOVING
			}
		}

		@Test
		fun `very large cell ID — 5G NR NCI range`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				cells = CellSignal(
					towers = listOf(
						CellTowerReading(
							cellId = 68_719_476_735L, // max 36-bit NCI
							mcc = "310",
							mnc = "260",
							networkType = 20,
							signalStrength = -110,
						),
					),
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<CellSample>>()
			coVerify(exactly = 1) { cellDao.insert(capture(captured)) }
			captured.captured.first().cellId shouldBe 68_719_476_735L
		}

		@Test
		fun `createdAt is populated — non-zero`() = runTest {
			startProcessor()
			processor.onSignal(fullSignal())
			processor.onFlush()

			val locCapture = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(locCapture)) }
			val createdAt = locCapture.captured.first().createdAt
			// createdAt should be a reasonable epoch time (after year 2000)
			(createdAt > 946_684_800_000L) shouldBe true
		}

		@Test
		fun `autoGenerate id defaults to zero for new entities`() = runTest {
			startProcessor()
			processor.onSignal(fullSignal())
			processor.onFlush()

			val locCapture = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(locCapture)) }
			// Room auto-generates IDs; we pass 0 which tells Room to generate
			locCapture.captured.first().id shouldBe 0L
		}

		@Test
		fun `sensor reset flag preserved in StepInterval`() = runTest {
			startProcessor()

			val signal = TrackingSignal(
				timestampMs = EpochMs(BASE_TIME),
				steps = StepSignal(
					stepDelta = StepCount(0),
					totalStepsSinceBoot = 0L,
					sensorValueStart = 99999,
					sensorValueEnd = 0,
					sensorReset = true,
				),
			)
			processor.onSignal(signal)
			processor.onFlush()

			val captured = slot<Collection<StepInterval>>()
			coVerify(exactly = 1) { stepDao.insert(capture(captured)) }
			val step = captured.captured.first()
			step.sensorReset shouldBe true
			step.stepCount shouldBe 0
			step.sensorValueStart shouldBe 99999
			step.sensorValueEnd shouldBe 0
		}

		@Test
		fun `full cycle — signal, flush, more signals, stop`() = runTest {
			startProcessor()

			// Phase 1: signal + flush
			processor.onSignal(fullSignal(timestampMs = BASE_TIME))
			processor.onFlush()

			// Phase 2: more signals + stop
			processor.onSignal(fullSignal(timestampMs = BASE_TIME + 1000L))
			processor.onSignal(fullSignal(timestampMs = BASE_TIME + 2000L))
			processor.onStop()

			// Two flush calls total: one explicit, one from onStop
			coVerify(exactly = 2) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 2) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 2) { cellDao.insert(any<Collection<CellSample>>()) }
			coVerify(exactly = 2) { wifiDao.insert(any<Collection<WifiObservation>>()) }
			coVerify(exactly = 2) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 2) { activityDao.insert(any<Collection<ActivitySnapshot>>()) }
		}
	}

	companion object {
		private const val BASE_TIME = 1_700_000_000_000L
		private const val BASE_ELAPSED_NANOS = 123_456_789_000_000L
	}
}
