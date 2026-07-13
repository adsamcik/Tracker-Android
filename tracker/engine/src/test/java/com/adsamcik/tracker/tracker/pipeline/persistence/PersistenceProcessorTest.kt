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
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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
	private lateinit var pendingSignalDao: PendingSignalDao
	private lateinit var durableBuffer: DurableSignalBuffer
	private lateinit var errorCollector: PersistenceErrorCollector
	private lateinit var processor: PersistenceProcessor

	/**
	 * Transactor that simply runs the block. It preserves the key property the
	 * production transactor relies on: an exception in the block propagates (so
	 * the WAL rows are never acknowledged and buffers are retained). It cannot
	 * simulate real rollback of already-issued mock inserts, which is fine — the
	 * tests assert acknowledgement/retention, not physical rollback.
	 */
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
		coEvery { pendingSignalDao.countByIds(any()) } answers {
			firstArg<List<Long>>().size
		}

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
	@DisplayName("durable staging")
	inner class DurableStaging {

		@Test
		fun `every handled signal is staged for durable checkpointing`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			val a = signalWithLocation(timestampMs = 1_000_000L)
			val b = signalWithPressure(timestampMs = 1_000_500L)
			processor.onSignal(a)
			processor.onSignal(b)

			verify(exactly = 1) { durableBuffer.stage(a) }
			verify(exactly = 1) { durableBuffer.stage(b) }
		}

		@Test
		fun `flush checkpoints staged signals before persisting destinations`() = runTest {
			coEvery { durableBuffer.checkpoint(any()) } coAnswers {
				listOf(10L, 11L).also(firstArg<(List<Long>) -> Unit>())
			}

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())

			processor.onFlush()

			coVerify(exactly = 1) { durableBuffer.checkpoint(any()) }
			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
		}

		@Test
		fun `checkpoint failure preserves staged signals and skips the flush`() = runTest {
			coEvery { durableBuffer.checkpoint(any()) } throws RuntimeException("wal write failed")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())

			processor.onFlush()

			// No destination write and no WAL acknowledgement happened.
			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 0) { pendingSignalDao.deleteByIds(any()) }
			coVerify(exactly = 1) {
				errorCollector.reportError(match<PersistenceError> { it.operation == "checkpoint" })
			}
		}

		@Test
		fun `cancellation after destination commit still acknowledges committed buffers`() = runTest {
			var firstCheckpoint = true
			coEvery { durableBuffer.checkpoint(any()) } coAnswers {
				if (firstCheckpoint) {
					firstCheckpoint = false
					listOf(41L).also(firstArg<(List<Long>) -> Unit>())
				} else {
					emptyList()
				}
			}
			coEvery { pendingSignalDao.countByIds(listOf(41L)) } returns 0
			lateinit var flushJob: Job
			val cancelAfterCommitTransactor = object : TrackingPersistenceTransactor {
				override suspend fun <R> inTransaction(block: suspend () -> R): R {
					val result = block()
					flushJob.cancel()
					return result
				}
			}
			val cancelSafeProcessor = PersistenceProcessor(
				locationSampleDao = locationDao,
				cellSampleDao = cellDao,
				wifiObservationDao = wifiDao,
				pressureSampleDao = pressureDao,
				stepIntervalDao = stepDao,
				activitySnapshotDao = activityDao,
				pendingSignalDao = pendingSignalDao,
				durableBuffer = durableBuffer,
				transactor = cancelAfterCommitTransactor,
				errorCollector = errorCollector,
			)
			cancelSafeProcessor.onStart(
				ProcessorContext(startTimestamp = EpochMs(0L), sessionId = 1L),
			)
			cancelSafeProcessor.onSignal(signalWithLocation())

			flushJob = launch { cancelSafeProcessor.onFlush() }
			flushJob.join()
			cancelSafeProcessor.onFlush()

			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(41L)) }
		}

		@Test
		fun `transaction timeout reconciles a commit before retrying buffered rows`() = runTest {
			var checkpointed = false
			coEvery { durableBuffer.checkpoint(any()) } coAnswers {
				val ids = if (checkpointed) emptyList() else listOf(43L)
				checkpointed = true
				ids.also(firstArg<(List<Long>) -> Unit>())
			}
			coEvery { pendingSignalDao.countByIds(listOf(43L)) } returns 0
			val commitThenStallTransactor = object : TrackingPersistenceTransactor {
				override suspend fun <R> inTransaction(block: suspend () -> R): R {
					block()
					awaitCancellation()
				}
			}
			val timeoutSafeProcessor = PersistenceProcessor(
				locationSampleDao = locationDao,
				cellSampleDao = cellDao,
				wifiObservationDao = wifiDao,
				pressureSampleDao = pressureDao,
				stepIntervalDao = stepDao,
				activitySnapshotDao = activityDao,
				pendingSignalDao = pendingSignalDao,
				durableBuffer = durableBuffer,
				transactor = commitThenStallTransactor,
				errorCollector = errorCollector,
			)
			timeoutSafeProcessor.onStart(
				ProcessorContext(startTimestamp = EpochMs(0L), sessionId = 1L),
			)
			timeoutSafeProcessor.onSignal(signalWithLocation())

			timeoutSafeProcessor.onFlush()
			timeoutSafeProcessor.onFlush()

			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(43L)) }
		}

		@Test
		fun `restart retries typed buffers retained after final checkpoint failure`() = runTest {
			coEvery { durableBuffer.checkpoint(any()) } throws RuntimeException("wal write failed")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L), sessionId = 1L))
			processor.onSignal(signalWithLocation())
			shouldThrow<IllegalStateException> {
				processor.onStop()
			}

			coEvery { durableBuffer.checkpoint(any()) } coAnswers {
				listOf(42L).also(firstArg<(List<Long>) -> Unit>())
			}
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(1L), sessionId = 2L))

			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(42L)) }
			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
		}
	}

	@Nested
	@DisplayName("transactional persist + acknowledge")
	inner class TransactionalPersist {

		@Test
		fun `successful flush acknowledges exactly the checkpointed WAL ids`() = runTest {
			coEvery { durableBuffer.checkpoint(any()) } coAnswers {
				listOf(10L, 11L).also(firstArg<(List<Long>) -> Unit>())
			}

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())
			processor.onSignal(signalWithPressure())

			processor.onFlush()

			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 1) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(10L, 11L)) }
		}

		@Test
		fun `large WAL acknowledgement is deleted within SQLite bind limits`() = runTest {
			val ids = (1L..901L).toList()
			coEvery { durableBuffer.checkpoint(any()) } coAnswers {
				ids.also(firstArg<(List<Long>) -> Unit>())
			}

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onFlush()

			coVerify(exactly = 1) {
				pendingSignalDao.deleteByIds(ids.take(PersistenceProcessor.ACK_DELETE_BATCH_SIZE))
			}
			coVerify(exactly = 1) {
				pendingSignalDao.deleteByIds(ids.drop(PersistenceProcessor.ACK_DELETE_BATCH_SIZE))
			}
			coVerify(exactly = 2) { pendingSignalDao.deleteByIds(any()) }
		}

		@Test
		fun `destination failure retains buffers and does not acknowledge WAL`() = runTest {
			var checkpointCall = 0
			coEvery { durableBuffer.checkpoint(any()) } coAnswers {
				val ids = if (checkpointCall++ == 0) listOf(10L, 11L) else emptyList()
				ids.also(firstArg<(List<Long>) -> Unit>())
			}
			coEvery { cellDao.insert(any<Collection<CellSample>>()) } throws RuntimeException("DB error")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithCells(location = signalWithLocation().location))

			processor.onFlush()

			// The transaction failed: WAL rows must NOT be acknowledged.
			coVerify(exactly = 0) { pendingSignalDao.deleteByIds(any()) }
			coVerify(exactly = 1) {
				errorCollector.reportError(match<PersistenceError> { it.operation == "flush" })
			}

			// Buffers and pending ids are retained: a later successful flush
			// persists the cell and acknowledges the original WAL ids.
			coEvery { cellDao.insert(any<Collection<CellSample>>()) } returns emptyList()
			processor.onFlush()

			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(10L, 11L)) }
		}

		@Test
		fun `CancellationException propagates and keeps data retryable`() = runTest {
			coEvery { durableBuffer.checkpoint(any()) } coAnswers {
				listOf(10L).also(firstArg<(List<Long>) -> Unit>())
			}
			coEvery {
				locationDao.insert(any<Collection<LocationSample>>())
			} throws CancellationException("cancelled")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())

			var caught = false
			try {
				processor.onFlush()
			} catch (_: CancellationException) {
				caught = true
			}

			caught shouldBe true
			coVerify(exactly = 0) { pendingSignalDao.deleteByIds(any()) }
		}
	}

	@Nested
	@DisplayName("startup recovery")
	inner class StartupRecovery {

		@Test
		fun `recovery peeks pending entries and persists then acknowledges them`() = runTest {
			coEvery { durableBuffer.peekBatch(any()) } returnsMany listOf(
				listOf(
					DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(timestampMs = 1_000_000L)),
					DurableSignalBuffer.PeekedSignal(2L, signalWithPressure(timestampMs = 1_000_500L)),
				),
				emptyList(),
			)

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L), sessionId = 42L))

			verify(exactly = 1) { durableBuffer.setSessionId(42L) }
			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 1) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(1L, 2L)) }
		}

		@Test
		fun `corrupted WAL rows are acknowledged so recovery makes progress`() = runTest {
			coEvery { durableBuffer.peekBatch(any()) } returnsMany listOf(
				listOf(
					DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(timestampMs = 1_000_000L)),
					DurableSignalBuffer.PeekedSignal(2L, null), // corrupted
					DurableSignalBuffer.PeekedSignal(3L, signalWithPressure(timestampMs = 1_000_500L)),
				),
				emptyList(),
			)

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			// All peeked ids — including the corrupted one — are acknowledged in
			// the same transaction, so the WAL always drains and never loops.
			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(1L, 2L, 3L)) }
			coVerify(exactly = 2) { durableBuffer.peekBatch(any()) }
		}

		@Test
		fun `recovery persist failure leaves WAL rows and retries on next flush`() = runTest {
			coEvery { durableBuffer.peekBatch(any()) } returnsMany listOf(
				listOf(DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(timestampMs = 1_000_000L))),
				emptyList(),
			)
			coEvery {
				locationDao.insert(any<Collection<LocationSample>>())
			} throws RuntimeException("DB error")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			// Persist failed during recovery: the row is NOT acknowledged and the
			// loop stops (single peek) to avoid spinning.
			coVerify(exactly = 0) { pendingSignalDao.deleteByIds(any()) }
			coVerify(exactly = 1) { durableBuffer.peekBatch(any()) }

			// A later flush (with a healthy DAO) retries and acknowledges it.
			coEvery { locationDao.insert(any<Collection<LocationSample>>()) } returns emptyList()
			processor.onFlush()

			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(1L)) }
		}

		@Test
		fun `successful recovery retry continues draining later WAL batches`() = runTest {
			coEvery { durableBuffer.peekBatch(any()) } returnsMany listOf(
				listOf(DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(1_000_000L))),
				listOf(DurableSignalBuffer.PeekedSignal(2L, signalWithLocation(2_000_000L))),
				emptyList(),
			)
			coEvery {
				locationDao.insert(any<Collection<LocationSample>>())
			} throws RuntimeException("DB error")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			coEvery { locationDao.insert(any<Collection<LocationSample>>()) } returns emptyList()
			processor.onFlush()

			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(1L)) }
			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(2L)) }
			coVerify(exactly = 3) { durableBuffer.peekBatch(any()) }
		}
	}

	@Nested
	@DisplayName("onSignal buffering")
	inner class OnSignalBuffering {

		@Test
		fun `onSignal buffers location when present`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			processor.onSignal(signalWithLocation())

			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }

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

		@Test
		fun `onStop fails when final signals cannot be checkpointed durably`() = runTest {
			coEvery { durableBuffer.checkpoint(any()) } throws IllegalStateException("WAL unavailable")
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())

			shouldThrow<IllegalStateException> {
				processor.onStop()
			}

			coVerify(exactly = 0) { pendingSignalDao.deleteByIds(any()) }
			coVerify(exactly = 1) {
				errorCollector.reportError(match<PersistenceError> { it.operation == "checkpoint" })
			}
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
