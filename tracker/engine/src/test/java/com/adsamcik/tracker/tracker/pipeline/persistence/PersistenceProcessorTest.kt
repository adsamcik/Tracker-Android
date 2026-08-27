package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalClaimDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.dao.SourceDestinationOwnerDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.LocationObservationSignal
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PersistenceProcessor")
class PersistenceProcessorTest {

	private lateinit var locationDao: LocationSampleDao
	private lateinit var locationObservationDao: LocationObservationDao
	private lateinit var cellDao: CellSampleDao
	private lateinit var wifiDao: WifiObservationDao
	private lateinit var pressureDao: PressureSampleDao
	private lateinit var stepDao: StepIntervalDao
	private lateinit var activityDao: ActivitySnapshotDao
	private lateinit var pendingSignalDao: PendingSignalDao
	private lateinit var pendingSignalClaimDao: PendingSignalClaimDao
	private lateinit var sourceDestinationOwnerDao: SourceDestinationOwnerDao
	private lateinit var durableBuffer: DurableSignalBuffer
	private lateinit var processor: PersistenceProcessor
	private val stagedSignals = mutableListOf<TrackingSignal>()
	private var nextCheckpointId = 1L

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
		stagedSignals.clear()
		nextCheckpointId = 1L
		locationDao = mockk(relaxed = true)
		locationObservationDao = mockk(relaxed = true)
		cellDao = mockk(relaxed = true)
		wifiDao = mockk(relaxed = true)
		pressureDao = mockk(relaxed = true)
		stepDao = mockk(relaxed = true)
		activityDao = mockk(relaxed = true)
		pendingSignalDao = mockk(relaxed = true)
		pendingSignalClaimDao = mockk(relaxed = true)
		sourceDestinationOwnerDao = mockk(relaxed = true)
		durableBuffer = mockk(relaxed = true)

		coEvery { locationDao.insert(any<Collection<LocationSample>>()) } returns emptyList()
		coEvery {
			locationObservationDao.insert(any<Collection<LocationObservation>>())
		} returns emptyList()
		coEvery { cellDao.insert(any<Collection<CellSample>>()) } returns emptyList()
		coEvery { wifiDao.insert(any<Collection<WifiObservation>>()) } returns emptyList()
		coEvery { pressureDao.insert(any<Collection<PressureSample>>()) } returns emptyList()
		coEvery { stepDao.insert(any<Collection<StepInterval>>()) } returns emptyList()
		coEvery {
			sourceDestinationOwnerDao.get(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			)
		} returns stepsOwner(
			SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
		)
		coEvery { activityDao.insert(any<Collection<ActivitySnapshot>>()) } returns emptyList()
		coEvery { durableBuffer.hasPendingEntries() } returns false
		coEvery { durableBuffer.claimBatch(any()) } returns null
		every { durableBuffer.stagingSize } answers { stagedSignals.size }
		coEvery { pendingSignalClaimDao.deleteClaimedByIds(any(), any()) } answers {
			firstArg<List<Long>>().size
		}
		coEvery { pendingSignalClaimDao.quarantineClaimed(any(), any()) } returns true
		coEvery { pendingSignalClaimDao.quarantineUnclaimed(any()) } returns true
		every { durableBuffer.stage(any<TrackingSignal>()) } answers {
			val signal = firstArg<TrackingSignal>()
			stagedSignals += signal
			DurableSignalBuffer.StagedSignal("test-staged-${stagedSignals.size}", signal)
		}
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
			val ids = stagedSignals.map { nextCheckpointId++ }
			completeCheckpoint(ids, firstArg())
		}
		coEvery { pendingSignalDao.countByIds(any()) } answers {
			firstArg<List<Long>>().size
		}

		processor = PersistenceProcessor(
			locationSampleDao = locationDao,
			locationObservationDao = locationObservationDao,
			cellSampleDao = cellDao,
			wifiObservationDao = wifiDao,
			pressureSampleDao = pressureDao,
			stepIntervalDao = stepDao,
			activitySnapshotDao = activityDao,
			pendingSignalDao = pendingSignalDao,
			pendingSignalClaimDao = pendingSignalClaimDao,
			durableBuffer = durableBuffer,
			transactor = transactor,
			sourceDestinationOwnerDao = sourceDestinationOwnerDao,
		)
	}

	private fun completeCheckpoint(
		ids: List<Long>,
		callback: (List<DurableSignalBuffer.CheckpointedSignal>) -> Unit,
		signals: List<TrackingSignal> = stagedSignals.toList(),
		stepsWriter: Pair<String, Long>? = null,
	): DurableSignalBuffer.CheckpointAdmission {
		callback(
			ids.mapIndexed { index, id ->
				DurableSignalBuffer.CheckpointedSignal(
					id = id,
					signalId = "test-signal-$id",
					signal = signals.getOrElse(index) { emptySignal },
					capturedEpoch = 0L,
					acquiredAtMs = signals.getOrElse(index) { emptySignal }.timestampMs.raw,
					stepsWriterOwner = stepsWriter?.first,
					stepsWriterOwnerGeneration = stepsWriter?.second,
				)
			},
		)
		stagedSignals.clear()
		return DurableSignalBuffer.CheckpointAdmission(
			admittedIds = ids,
			lifecycleRejectedCount = 0,
		)
	}

	private fun lifecycleAwareProcessor(
		stateDao: SourceEvidenceStateDao,
		lifecycleStore: CollectedDataLifecycleStore? = null,
	): PersistenceProcessor = PersistenceProcessor(
		locationSampleDao = locationDao,
		locationObservationDao = locationObservationDao,
		sourceEvidenceStateDao = stateDao,
		collectedDataLifecycleStore = lifecycleStore,
		cellSampleDao = cellDao,
		wifiObservationDao = wifiDao,
		pressureSampleDao = pressureDao,
		stepIntervalDao = stepDao,
		activitySnapshotDao = activityDao,
		pendingSignalDao = pendingSignalDao,
		pendingSignalClaimDao = pendingSignalClaimDao,
		durableBuffer = durableBuffer,
		transactor = transactor,
		sourceDestinationOwnerDao = sourceDestinationOwnerDao,
	)

	private fun processorWithTransactor(
		customTransactor: TrackingPersistenceTransactor,
	): PersistenceProcessor = PersistenceProcessor(
		locationSampleDao = locationDao,
		locationObservationDao = locationObservationDao,
		cellSampleDao = cellDao,
		wifiObservationDao = wifiDao,
		pressureSampleDao = pressureDao,
		stepIntervalDao = stepDao,
		activitySnapshotDao = activityDao,
		pendingSignalDao = pendingSignalDao,
		pendingSignalClaimDao = pendingSignalClaimDao,
		durableBuffer = durableBuffer,
		transactor = customTransactor,
		sourceDestinationOwnerDao = sourceDestinationOwnerDao,
	)

	private fun claimedBatch(
		signals: List<DurableSignalBuffer.PeekedSignal>,
		claimToken: String = "test-claim",
	): DurableSignalBuffer.ClaimedBatch = DurableSignalBuffer.ClaimedBatch(
		claimToken = claimToken,
		leaseExpiresAtMs = Long.MAX_VALUE,
		signals = signals,
	)

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
			altitudeDatum = AltitudeDatum.FUSED_ANDROID_MODEL_MSL,
			altitudeSource = AltitudeSource.FUSED_GPS_BAROMETER,
			altitudeConversionStatus = AltitudeConversionStatus.SUCCESS,
			rawGpsAltitudeDatum = AltitudeDatum.WGS84_ELLIPSOID,
			altitudeModelVersion = 1,
			altitudeEstimatorVersion = 1,
			altitudeCalibrationVersion = 1,
			verticalAccuracyM = 3f,
			speedAccuracyMps = 0.5f,
			provider = "fused",
		),
	)

	private fun rejectedRawObservation(): TrackingSignal = TrackingSignal(
		timestampMs = EpochMs(1_000_123L),
		elapsedRealtimeNanos = 2_000_000_000L,
		locationObservation = LocationObservationSignal(
			coordinate = null,
			horizontalAccuracyM = 25f,
			provider = "fused",
			receivedAtMs = 1_000_456L,
			receivedElapsedRealtimeNanos = 2_250_000_000L,
			acquisitionMode = "FUSED",
			requestPriority = "BALANCED",
			permissionPrecision = "APPROXIMATE",
			batchIndex = 1,
			batchSize = 3,
			ingressDisposition = "REJECTED_INVALID_COORDINATE",
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

	private fun signalWithLocationAndSteps(
		timestampMs: Long = 1_000_000L,
	): TrackingSignal = signalWithLocation(timestampMs).copy(
		steps = StepSignal(
			stepDelta = StepCount(15),
			totalStepsSinceBoot = 5_000L,
			sensorValueStart = 4_985,
			sensorValueEnd = 5_000,
		),
	)

	private fun stepsOwner(owner: String, generation: Long) = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		owner = owner,
		ownerGeneration = generation,
		updatedAtMs = 1_000L,
	)

	private fun pendingRow(
		id: Long,
		signalId: String,
		signal: TrackingSignal,
		claimToken: String? = "test-claim",
	): PendingSignalEntity {
		val encoded = SignalSerializer.encode(signal)
		return PendingSignalEntity(
			id = id,
			signalId = signalId,
			sessionId = 42L,
			envelopeVersion = encoded.envelopeVersion,
			payloadChecksum = encoded.payloadChecksum,
			signalJson = encoded.payloadJson,
			createdAt = signal.timestampMs.raw,
			acquiredAtMs = signal.timestampMs.raw,
			claimToken = claimToken,
			claimExpiresAt = Long.MAX_VALUE.takeIf { claimToken != null },
		)
	}

	private val emptySignal = TrackingSignal(timestampMs = EpochMs(1_000_000L))

	@Nested
	@DisplayName("durable staging")
	inner class DurableStaging {
		@Test
		fun `legacy Steps authority remains blocked until producer stop flushes staged work`() = runTest {
			coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				val ids = stagedSignals.map { nextCheckpointId++ }
				completeCheckpoint(
					ids = ids,
					callback = firstArg(),
					stepsWriter = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL to
						SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
				)
			}
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocationAndSteps())

			processor.withLegacyStepsWriterQuiesced { "cutover" } shouldBe null
			stagedSignals shouldHaveSize 1

			processor.onStop()

			processor.withLegacyStepsWriterQuiesced { "cutover" } shouldBe "cutover"
			stagedSignals.shouldBeEmpty()
		}

		@Test
		fun `legacy Steps authority check never waits behind another persistence operation`() = runTest {
			val entered = CompletableDeferred<Unit>()
			val release = CompletableDeferred<Unit>()
			val holder = async {
				processor.withLegacyStepsWriterQuiesced {
					entered.complete(Unit)
					release.await()
					"holder"
				}
			}
			entered.await()
			var secondOperationRan = false

			withTimeout(100L) {
				processor.withLegacyStepsWriterQuiesced {
					secondOperationRan = true
					"second"
				}
			} shouldBe null
			secondOperationRan shouldBe false

			release.complete(Unit)
			holder.await() shouldBe "holder"
		}

		@Test
		fun `rejected raw provider evidence persists without invented coordinates`() = runTest {
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(rejectedRawObservation())

			processor.onFlush()

			val captured = slot<Collection<LocationObservation>>()
			coVerify(exactly = 1) { locationObservationDao.insert(capture(captured)) }
			val row = captured.captured.single()
			row.latE7 shouldBe null
			row.lonE7 shouldBe null
			row.receivedAtMs shouldBe 1_000_456L
			row.deliveryAgeMs shouldBe 250L
			row.requestPriority shouldBe "BALANCED"
			row.permissionPrecision shouldBe "APPROXIMATE"
			row.batchIndex shouldBe 1
			row.batchSize shouldBe 3
			row.ingressDisposition shouldBe "REJECTED_INVALID_COORDINATE"
		}

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
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				completeCheckpoint(listOf(10L, 11L), firstArg())
			}

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())

			processor.onFlush()

			coVerify(exactly = 1) { durableBuffer.checkpointWithAdmission(any()) }
			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
		}

		@Test
		fun `checkpoint failure preserves staged signals and skips the flush`() = runTest {
			coEvery { durableBuffer.checkpointWithAdmission(any()) } throws RuntimeException("wal write failed")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())

			processor.onFlush()

			// No destination write and no WAL acknowledgement happened.
			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 0) { pendingSignalDao.deleteByIds(any()) }
		}

		@Test
		fun `lifecycle rejection is terminal and does not report a checkpoint failure`() = runTest {
			coEvery { durableBuffer.checkpointWithAdmission(any()) } returns
				DurableSignalBuffer.CheckpointAdmission(
					admittedIds = emptyList(),
					lifecycleRejectedCount = 1,
				)

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())

			processor.checkpointStagedSignals() shouldBe false
			processor.onStop()

			coVerify(exactly = 0) { locationDao.insert(any<Collection<LocationSample>>()) }
		}

		@Test
		fun `cancellation after destination commit still acknowledges committed buffers`() = runTest {
			var firstCheckpoint = true
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				if (firstCheckpoint) {
					firstCheckpoint = false
					completeCheckpoint(listOf(41L), firstArg())
				} else {
					DurableSignalBuffer.CheckpointAdmission.EMPTY
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
				locationObservationDao = locationObservationDao,
				cellSampleDao = cellDao,
				wifiObservationDao = wifiDao,
				pressureSampleDao = pressureDao,
				stepIntervalDao = stepDao,
				activitySnapshotDao = activityDao,
				pendingSignalDao = pendingSignalDao,
				durableBuffer = durableBuffer,
				transactor = cancelAfterCommitTransactor,
				sourceDestinationOwnerDao = sourceDestinationOwnerDao,
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
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				val ids = if (checkpointed) emptyList() else listOf(43L)
				checkpointed = true
				completeCheckpoint(ids, firstArg())
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
				locationObservationDao = locationObservationDao,
				cellSampleDao = cellDao,
				wifiObservationDao = wifiDao,
				pressureSampleDao = pressureDao,
				stepIntervalDao = stepDao,
				activitySnapshotDao = activityDao,
				pendingSignalDao = pendingSignalDao,
				durableBuffer = durableBuffer,
				transactor = commitThenStallTransactor,
				sourceDestinationOwnerDao = sourceDestinationOwnerDao,
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
			coEvery { durableBuffer.checkpointWithAdmission(any()) } throws RuntimeException("wal write failed")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L), sessionId = 1L))
			processor.onSignal(signalWithLocation())
			shouldThrow<IllegalStateException> {
				processor.onStop()
			}

		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				completeCheckpoint(listOf(42L), firstArg())
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
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				completeCheckpoint(listOf(10L, 11L), firstArg())
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
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				completeCheckpoint(ids, firstArg())
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
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				val ids = if (checkpointCall++ == 0) listOf(10L, 11L) else emptyList()
				completeCheckpoint(ids, firstArg())
			}
			coEvery { cellDao.insert(any<Collection<CellSample>>()) } throws RuntimeException("DB error")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithCells(location = signalWithLocation().location))

			processor.onFlush()

			// The transaction failed: WAL rows must NOT be acknowledged.
			coVerify(exactly = 0) { pendingSignalDao.deleteByIds(any()) }

			// Buffers and pending ids are retained: a later successful flush
			// persists the cell and acknowledges the original WAL ids.
			coEvery { cellDao.insert(any<Collection<CellSample>>()) } returns emptyList()
			processor.onFlush()

			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(10L, 11L)) }
		}

		@Test
		fun `CancellationException propagates and keeps data retryable`() = runTest {
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				completeCheckpoint(listOf(10L), firstArg())
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

		@Test
		fun `stale in-memory WAL entry is discarded while a current entry still persists`() = runTest {
			val lifecycleStateDao = mockk<SourceEvidenceStateDao>(relaxed = true)
			coEvery { lifecycleStateDao.get() } returns SourceEvidenceState(
				collectedDataEpoch = 7L,
				retainedFromMs = 5_000L,
			)
		coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				firstArg<(List<DurableSignalBuffer.CheckpointedSignal>) -> Unit>().invoke(
					listOf(
						DurableSignalBuffer.CheckpointedSignal(
							id = 10L,
							signalId = "stale-pressure",
							signal = signalWithPressure(timestampMs = 1_000L),
							capturedEpoch = 6L,
							acquiredAtMs = 1_000L,
						),
						DurableSignalBuffer.CheckpointedSignal(
							id = 11L,
							signalId = "current-pressure",
							signal = signalWithPressure(timestampMs = 6_000L),
							capturedEpoch = 7L,
							acquiredAtMs = 6_000L,
						),
					),
				)
				DurableSignalBuffer.CheckpointAdmission(
					admittedIds = listOf(10L, 11L),
					lifecycleRejectedCount = 0,
				)
			}
			val lifecycleProcessor = lifecycleAwareProcessor(lifecycleStateDao)

			lifecycleProcessor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			lifecycleProcessor.onFlush()

			val pressures = slot<Collection<PressureSample>>()
			coVerify(exactly = 1) { pressureDao.insert(capture(pressures)) }
			pressures.captured.map(PressureSample::timeMs) shouldBe listOf(6_000L)
			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(10L, 11L)) }
		}

		@Test
		fun `flush synchronizes authoritative lifecycle before publishing buffered WAL`() = runTest {
			var mirroredGuard = SourceEvidenceState(
				collectedDataEpoch = 0L,
				retainedFromMs = null,
			)
			val lifecycleStateDao = mockk<SourceEvidenceStateDao>(relaxed = true)
			coEvery { lifecycleStateDao.get() } coAnswers { mirroredGuard }
			coEvery { lifecycleStateDao.updateLifecycle(any(), any(), any()) } coAnswers {
				mirroredGuard = mirroredGuard.copy(
					collectedDataEpoch = firstArg(),
					retainedFromMs = secondArg(),
				)
				1
			}
			val lifecycleStore = mockk<CollectedDataLifecycleStore>()
			coEvery { lifecycleStore.snapshot() } returns CollectedDataLifecycleSnapshot(
				epoch = 1L,
				retainedFromMs = 5_000L,
			)
			val lifecycleProcessor = lifecycleAwareProcessor(lifecycleStateDao, lifecycleStore)

			lifecycleProcessor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			lifecycleProcessor.onSignal(signalWithPressure(timestampMs = 1_000L))
			lifecycleProcessor.onFlush()

			coVerify(exactly = 0) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 1) { pendingSignalDao.deleteByIds(listOf(1L)) }
			coVerify(exactly = 1) {
				lifecycleStateDao.updateLifecycle(1L, 5_000L, any())
			}
		}
	}

	@Nested
	@DisplayName("startup recovery")
	inner class StartupRecovery {

		@Test
		fun `candidate Steps suppress legacy writes while invalid provenance is quarantined source locally`() =
			runTest {
				val candidate = signalWithLocationAndSteps(timestampMs = 1_000_000L)
				val invalid = signalWithLocationAndSteps(timestampMs = 1_000_500L)
				val candidateEntry = DurableSignalBuffer.PeekedSignal(1L, candidate).copy(
					signalId = "candidate-steps",
					stepsWriterOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
					stepsWriterOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				)
				val invalidEntry = DurableSignalBuffer.PeekedSignal(2L, invalid).copy(
					signalId = "invalid-steps",
					stepsWriterOwner = null,
					stepsWriterOwnerGeneration = null,
				)
				coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
					claimedBatch(listOf(candidateEntry, invalidEntry)),
					null,
				)
				coEvery { pendingSignalDao.getByIds(listOf(2L)) } returns listOf(
					pendingRow(2L, "invalid-steps", invalid),
				)

				processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L), sessionId = 42L))

				val locations = slot<Collection<LocationSample>>()
				coVerify(exactly = 1) { locationDao.insert(capture(locations)) }
				locations.captured shouldHaveSize 2
				coVerify(exactly = 0) { stepDao.insert(any<Collection<StepInterval>>()) }
				coVerify(exactly = 0) { sourceDestinationOwnerDao.get(any(), any()) }
				val quarantine = slot<com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity>()
				coVerify(exactly = 1) {
					pendingSignalClaimDao.quarantineClaimed(capture(quarantine), "test-claim")
				}
				quarantine.captured.signalId shouldBe "invalid-steps"
				quarantine.captured.acquiredAtMs shouldBe invalid.timestampMs.raw
				quarantine.captured.failureReason shouldBe
					PersistenceProcessor.STEPS_WRITER_PROVENANCE_INVALID
				coVerify(exactly = 1) {
					pendingSignalClaimDao.deleteClaimedByIds(listOf(1L), "test-claim")
				}
		}

		@Test
		fun `missing Steps writer authority retains the WAL row for retry`() = runTest {
			val signal = signalWithLocationAndSteps()
			val current = DurableSignalBuffer.PeekedSignal(1L, signal).copy(
				signalId = "legacy-steps-without-authority",
				stepsWriterOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
				stepsWriterOwnerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
			)
			coEvery { durableBuffer.claimBatch(any()) } returns claimedBatch(listOf(current))
			coEvery { sourceDestinationOwnerDao.get(any(), any()) } returns null

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			coVerify(exactly = 1) { sourceDestinationOwnerDao.get(any(), any()) }
			coVerify(exactly = 0) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 0) { pendingSignalClaimDao.deleteClaimedByIds(any(), any()) }
			coVerify(exactly = 0) { pendingSignalClaimDao.quarantineClaimed(any(), any()) }
			coVerify(exactly = 1) { durableBuffer.releaseClaim("test-claim") }
		}

		@Test
		fun `stale legacy Steps generation is ABA fenced without blocking location`() = runTest {
			val signal = signalWithLocationAndSteps()
			val stale = DurableSignalBuffer.PeekedSignal(1L, signal).copy(
				signalId = "stale-legacy-steps",
				stepsWriterOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
				stepsWriterOwnerGeneration = 1L,
			)
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(listOf(stale)),
				null,
			)
			coEvery { sourceDestinationOwnerDao.get(any(), any()) } returns stepsOwner(
				SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
				3L,
			)

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 0) { stepDao.insert(any<Collection<StepInterval>>()) }
			coVerify(exactly = 1) { sourceDestinationOwnerDao.get(any(), any()) }
			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(1L), "test-claim")
			}
		}

		@Test
		fun `current noninitial legacy Steps generation is accepted with one authority read`() = runTest {
			val signal = signalWithLocationAndSteps()
			val current = DurableSignalBuffer.PeekedSignal(1L, signal).copy(
				signalId = "current-legacy-steps",
				stepsWriterOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
				stepsWriterOwnerGeneration = 3L,
			)
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(listOf(current)),
				null,
			)
			coEvery { sourceDestinationOwnerDao.get(any(), any()) } returns stepsOwner(
				SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
				3L,
			)

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			val captured = slot<Collection<StepInterval>>()
			coVerify(exactly = 1) { stepDao.insert(capture(captured)) }
			captured.captured.single().stepCount shouldBe 15
			coVerify(exactly = 1) { sourceDestinationOwnerDao.get(any(), any()) }
			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(1L), "test-claim")
			}
		}

		@Test
		fun `drain generation fence aborts the destination publish transaction`() = runTest {
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(
					listOf(
						DurableSignalBuffer.PeekedSignal(
							1L,
							signalWithLocation(timestampMs = 1_000_000L),
						),
					),
				),
				null,
			)
			var generation = 4L
			coEvery { locationDao.insert(any<Collection<LocationSample>>()) } coAnswers {
				generation = 5L
				emptyList()
			}
			var completedTransactions = 0
			val fencedProcessor = processorWithTransactor(
				object : TrackingPersistenceTransactor {
					override suspend fun <R> inTransaction(block: suspend () -> R): R {
						val result = block()
						completedTransactions++
						return result
					}
				},
			)

			shouldThrow<IllegalStateException> {
				fencedProcessor.drainOrphanedSignals {
					check(generation == 4L) { "startup generation changed" }
				}
			}

			// The stale-discard read transaction completed. The destination publish did not.
			completedTransactions shouldBe 1
			coVerify(exactly = 0) { durableBuffer.releaseClaim(any()) }
		}

		@Test
		fun `recovery claims pending entries and persists then acknowledges them`() = runTest {
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(
					listOf(
					DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(timestampMs = 1_000_000L)),
					DurableSignalBuffer.PeekedSignal(2L, signalWithPressure(timestampMs = 1_000_500L)),
				),
				),
				null,
			)

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L), sessionId = 42L))

			verify(exactly = 1) { durableBuffer.setSessionId(42L) }
			coVerify(exactly = 1) { locationDao.insert(any<Collection<LocationSample>>()) }
			coVerify(exactly = 1) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(1L, 2L), "test-claim")
			}
		}

		@Test
		fun `recovery discards stale WAL before publishing current entries`() = runTest {
			val lifecycleStateDao = mockk<SourceEvidenceStateDao>(relaxed = true)
			coEvery { lifecycleStateDao.get() } returns SourceEvidenceState(
				collectedDataEpoch = 4L,
				retainedFromMs = 5_000L,
			)
			val stale = DurableSignalBuffer.PeekedSignal(
				1L,
				signalWithPressure(timestampMs = 1_000L),
			).copy(
				signalId = "stale-recovery",
				capturedEpoch = 3L,
				acquiredAtMs = 1_000L,
			)
			val current = DurableSignalBuffer.PeekedSignal(
				2L,
				signalWithPressure(timestampMs = 6_000L),
			).copy(
				signalId = "current-recovery",
				capturedEpoch = 4L,
				acquiredAtMs = 6_000L,
			)
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(listOf(stale, current)),
				null,
			)
			val lifecycleProcessor = lifecycleAwareProcessor(lifecycleStateDao)

			lifecycleProcessor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			val pressures = slot<Collection<PressureSample>>()
			coVerify(exactly = 1) { pressureDao.insert(capture(pressures)) }
			pressures.captured.map(PressureSample::timeMs) shouldBe listOf(6_000L)
			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(1L), "test-claim")
			}
			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(2L), "test-claim")
			}
		}

		@Test
		fun `recovery synchronizes authoritative lifecycle when no new admission occurred`() = runTest {
			var mirroredGuard = SourceEvidenceState(
				collectedDataEpoch = 0L,
				retainedFromMs = null,
			)
			val lifecycleStateDao = mockk<SourceEvidenceStateDao>(relaxed = true)
			coEvery { lifecycleStateDao.get() } coAnswers { mirroredGuard }
			coEvery { lifecycleStateDao.updateLifecycle(any(), any(), any()) } coAnswers {
				mirroredGuard = mirroredGuard.copy(
					collectedDataEpoch = firstArg(),
					retainedFromMs = secondArg(),
				)
				1
			}
			val lifecycleStore = mockk<CollectedDataLifecycleStore>()
			coEvery { lifecycleStore.snapshot() } returns CollectedDataLifecycleSnapshot(
				epoch = 1L,
				retainedFromMs = 5_000L,
			)
			val stale = DurableSignalBuffer.PeekedSignal(
				1L,
				signalWithPressure(timestampMs = 1_000L),
			).copy(
				signalId = "expired-before-retention-recovery",
				capturedEpoch = 0L,
				acquiredAtMs = 1_000L,
			)
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(listOf(stale)),
				null,
			)
			val lifecycleProcessor = lifecycleAwareProcessor(lifecycleStateDao, lifecycleStore)

			lifecycleProcessor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			coVerify(exactly = 0) { pressureDao.insert(any<Collection<PressureSample>>()) }
			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(1L), "test-claim")
			}
			coVerify(exactly = 1) {
				lifecycleStateDao.updateLifecycle(1L, 5_000L, any())
			}
		}

		@Test
		fun `corrupted WAL rows are quarantined while valid rows continue`() = runTest {
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(
					listOf(
					DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(timestampMs = 1_000_000L)),
					DurableSignalBuffer.PeekedSignal(2L, null), // corrupted
					DurableSignalBuffer.PeekedSignal(3L, signalWithPressure(timestampMs = 1_000_500L)),
				),
				),
				null,
			)

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			coVerify(exactly = 1) { pendingSignalClaimDao.quarantineClaimed(any(), "test-claim") }
			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(1L, 3L), "test-claim")
			}
			coVerify(exactly = 2) { durableBuffer.claimBatch(any()) }
		}

		@Test
		fun `recovery persist failure leaves WAL rows and retries on next flush`() = runTest {
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(listOf(DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(timestampMs = 1_000_000L)))),
				claimedBatch(listOf(DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(timestampMs = 1_000_000L)))),
				null,
			)
			coEvery {
				locationDao.insert(any<Collection<LocationSample>>())
			} throws RuntimeException("DB error")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			// Persist failed during recovery: the row is not acknowledged and its
			// lease is released so a later pass can retry it.
			coVerify(exactly = 0) { pendingSignalClaimDao.deleteClaimedByIds(any(), any()) }
			coVerify(exactly = 1) { durableBuffer.releaseClaim("test-claim") }
			coVerify(exactly = 1) { durableBuffer.claimBatch(any()) }

			// A later flush (with a healthy DAO) retries and acknowledges it.
			coEvery { locationDao.insert(any<Collection<LocationSample>>()) } returns emptyList()
			processor.onFlush()

			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(1L), "test-claim")
			}
		}

		@Test
		fun `successful recovery retry continues draining later WAL batches`() = runTest {
			coEvery { durableBuffer.claimBatch(any()) } returnsMany listOf(
				claimedBatch(listOf(DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(1_000_000L)))),
				claimedBatch(listOf(DurableSignalBuffer.PeekedSignal(1L, signalWithLocation(1_000_000L)))),
				claimedBatch(listOf(DurableSignalBuffer.PeekedSignal(2L, signalWithLocation(2_000_000L)))),
				null,
			)
			coEvery {
				locationDao.insert(any<Collection<LocationSample>>())
			} throws RuntimeException("DB error")

			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))

			coEvery { locationDao.insert(any<Collection<LocationSample>>()) } returns emptyList()
			processor.onFlush()

			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(1L), "test-claim")
			}
			coVerify(exactly = 1) {
				pendingSignalClaimDao.deleteClaimedByIds(listOf(2L), "test-claim")
			}
			coVerify(exactly = 4) { durableBuffer.claimBatch(any()) }
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
			sample.altitudeDatum shouldBe AltitudeDatum.FUSED_ANDROID_MODEL_MSL
			sample.altitudeSource shouldBe AltitudeSource.FUSED_GPS_BAROMETER
			sample.altitudeConversionStatus shouldBe AltitudeConversionStatus.SUCCESS
			sample.rawGpsAltitudeDatum shouldBe AltitudeDatum.WGS84_ELLIPSOID
			sample.altitudeModelVersion shouldBe 1
			sample.estimatorVersion shouldBe 1
			sample.calibrationVersion shouldBe 1
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
			coEvery { durableBuffer.checkpointWithAdmission(any()) } throws IllegalStateException("WAL unavailable")
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L)))
			processor.onSignal(signalWithLocation())

			shouldThrow<IllegalStateException> {
				processor.onStop()
			}

			coVerify(exactly = 0) { pendingSignalDao.deleteByIds(any()) }
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
			coEvery { durableBuffer.checkpointWithAdmission(any()) } coAnswers {
				val ids = stagedSignals.map { nextCheckpointId++ }
				completeCheckpoint(
					ids = ids,
					callback = firstArg(),
					stepsWriter = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL to
						SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
				)
			}
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
