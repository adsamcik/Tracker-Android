package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.file.JsonImport
import com.adsamcik.tracker.shared.base.database.dao.LocationPurposeDao
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import io.mockk.mockk
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.LocationSample as EntityLocationSample
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonExporterRoomIntegrationTest {

	private lateinit var context: Context
	private lateinit var database: AppDatabase
	private lateinit var locationSampleDao: LocationSampleDao
	private lateinit var sessionSegmentDao: SessionSegmentDao
	private val tempFiles = mutableListOf<File>()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		locationSampleDao = database.locationSampleDao()
		sessionSegmentDao = database.sessionSegmentDao()

		mockkObject(AppDatabase.Companion)
		every { AppDatabase.database(any()) } returns database
	}

	@After
	fun tearDown() {
		tempFiles.forEach { file ->
			if (file.exists()) {
				file.delete()
			}
		}
		unmockkObject(AppDatabase.Companion)
		database.close()
	}

	@Test
	fun `last session interval never widens to an overlapping older recording`() = runTest {
		val base = 1_725_000_000_000L
		locationSampleDao.insert(listOf(20_000L, 30_000L, 40_000L).map { offset ->
			createLocationSample(base + offset, 50.0, 14.0, 200f, 1f, 5f)
		})
		sessionSegmentDao.insert(createSessionSegment(base + 10_000L, base + 50_000L, 3, 100f, 0))
		val output = java.io.ByteArrayOutputStream()
		val result = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
			.exportSelectedInterval(output, (base + 25_000L)..(base + 35_000L), database) {}
		result.shouldBeInstanceOf<ExportResult.Success>().recordCount shouldBe 1
		val array = JSONArray(output.toString(Charsets.UTF_8.name()))
		val times = (0 until array.length()).flatMap { index ->
			val points = array.getJSONObject(index).getJSONArray("locations")
			(0 until points.length()).map { points.getJSONObject(it).getLong("timeMs") }
		}
		times shouldBe listOf(base + 30_000L)
	}

	// Keep persisted mixed-source fixtures and one exported JSON payload together to verify its exact range and
	// fields.
	@Suppress("LongMethod")
	@Test
	fun `Room json export keeps location data but omits unqualified session Steps`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val exporter = JsonExporter(TestDispatchersProvider(dispatcher))
		val baseTimeMs = 1_725_000_000_000L
		val inRangeSamples = listOf(
			createLocationSample(
				timeMs = baseTimeMs,
				latitude = 50.087451,
				longitude = 14.420671,
				altitudeM = 214.3f,
				speedMps = 1.3f,
				accuracyM = 4.2f,
			),
			createLocationSample(
				timeMs = baseTimeMs + 30_000L,
				latitude = 50.087981,
				longitude = 14.421219,
				altitudeM = 216.8f,
				speedMps = 1.5f,
				accuracyM = 4.8f,
			),
			createLocationSample(
				timeMs = baseTimeMs + 60_000L,
				latitude = 50.088512,
				longitude = 14.421845,
				altitudeM = 219.1f,
				speedMps = 1.6f,
				accuracyM = 5.1f,
			),
		)
		val outOfRangeSample = createLocationSample(
			timeMs = baseTimeMs - 120_000L,
			latitude = 49.0,
			longitude = 13.0,
			altitudeM = 100f,
			speedMps = 0.5f,
			accuracyM = 25f,
		)
		val dateRange = inRangeSamples.first().timeMs..inRangeSamples.last().timeMs

		locationSampleDao.insert(listOf(outOfRangeSample) + inRangeSamples)
		sessionSegmentDao.insert(
			createSessionSegment(
				startTimeMs = dateRange.first,
				endTimeMs = dateRange.last,
				sampleCount = inRangeSamples.size,
				distanceM = 185.4f,
				steps = 248,
			),
		)
		database.wifiObservationDao().insert(
			WifiObservation(
				timeMs = baseTimeMs + 15_000L,
				bssid = "00:11:22:33:44:55",
				ssid = "Tracker",
				capabilities = "[WPA2]",
				frequency = 5180,
				level = -45,
				latE7 = inRangeSamples.first().latE7,
				lonE7 = inRangeSamples.first().lonE7,
				provenance = CoordinateProvenance.DIRECT,
				createdAt = baseTimeMs + 15_000L,
			),
		)
		database.cellSampleDao().insert(
			CellSample(
				timeMs = baseTimeMs + 45_000L,
				cellId = 1234L,
				lac = 5,
				mcc = 230,
				mnc = 1,
				networkType = 13,
				signalStrength = 40,
				latE7 = inRangeSamples.last().latE7,
				lonE7 = inRangeSamples.last().lonE7,
				provenance = CoordinateProvenance.DIRECT,
				createdAt = baseTimeMs + 45_000L,
			),
		)

		locationSampleDao.countBetween(dateRange.first, dateRange.last) shouldBe inRangeSamples.size

		val outputFile = createTempExportFile()
		lateinit var exportResult: ExportResult.Success
		outputFile.outputStream().use { outputStream ->
			exportResult = exporter.export(
				context = context,
				locationData = loadPersistedLocations(dateRange),
				outputStream = outputStream,
				dateRange = dateRange,
			).shouldBeInstanceOf()
		}
		exportResult.recordCount shouldBe inRangeSamples.size
		exportResult.maxTimeMs shouldBe inRangeSamples.last().timeMs
		(exportResult.maxId ?: 0L) shouldBeGreaterThan 0L

		outputFile.exists() shouldBe true
		outputFile.length() shouldBeGreaterThan 0L

		val payload = outputFile.readText(Charsets.UTF_8)
		payload shouldContain "\"timeMs\":${inRangeSamples.first().timeMs}"
		payload shouldContain "\"timeMs\":${inRangeSamples.last().timeMs}"
		payload.contains("\"timeMs\":${outOfRangeSample.timeMs}") shouldBe false

		val record = JSONArray(payload).getJSONObject(0)
		record.getInt("schemaVersion") shouldBe 3
		val exportedLocations = record.getJSONArray("locations")
		exportedLocations.length() shouldBe inRangeSamples.size
		inRangeSamples.forEachIndexed { index, expected ->
			val exported = exportedLocations.getJSONObject(index)
			exported.getLong("timeMs") shouldBe expected.timeMs
			(abs(exported.getDouble("latitude") - expected.latitude()) < 0.0000001) shouldBe true
			(abs(exported.getDouble("longitude") - expected.longitude()) < 0.0000001) shouldBe true
			exported.getDouble("altitudeM").toFloat() shouldBe expected.altitudeM
			exported.getDouble("speedMps").toFloat() shouldBe expected.speedMps
			exported.getDouble("horizontalAccuracyM").toFloat() shouldBe expected.hAccM
		}

		val session = record.getJSONObject("session")
		session.getLong("startTimeMs") shouldBe dateRange.first
		session.getLong("endTimeMs") shouldBe dateRange.last
		session.getInt("sampleCount") shouldBe inRangeSamples.size
		session.getDouble("distanceM").toFloat() shouldBe 185.4f
		session.has("steps") shouldBe false
		record.getJSONArray("wifiObservations").getJSONObject(0).getString("bssid") shouldBe "00:11:22:33:44:55"
		record.getJSONArray("cellSamples").getJSONObject(0).getLong("cellId") shouldBe 1234L
	}

	@Test
	fun `Room JSON round trip retains optional vertical and speed accuracy`() = runTest {
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val timeMs = 1_725_000_000_000L
		val samples = listOf(
			createLocationSample(timeMs, 50.0, 14.0, 100f, 1f, 5f)
				.copy(vAccM = null, speedAccuracyMps = null),
			createLocationSample(timeMs + 1, 50.1, 14.1, 101f, 2f, 6f)
				.copy(vAccM = 0f, speedAccuracyMps = 0f),
			createLocationSample(timeMs + 2, 50.2, 14.2, 102f, 3f, 7f)
				.copy(vAccM = 2.5f, speedAccuracyMps = 0.4f),
		)
		locationSampleDao.insert(samples)
		sessionSegmentDao.insert(createSessionSegment(timeMs, timeMs + 2, samples.size, 10f, 0))
		val output = createTempExportFile()
		output.outputStream().use { stream ->
			JsonExporter(dispatchers).export(context, emptySequence(), stream, timeMs..(timeMs + 2))
				.shouldBeInstanceOf<ExportResult.Success>()
		}

		val restoredDatabase = AppDatabase.testDatabase(context)
		try {
			val result = JsonImport(dispatchers).import(
				context, restoredDatabase, FileImportStream(output.inputStream(), output.name),
			)
			result.successCount shouldBe samples.size + 1 // The existing session record is also imported.
			val restored = restoredDatabase.locationSampleDao().getChunkBetweenOrdered(
				timeMs, timeMs + 2, null, null, 10,
			)
			restored.map { it.timeMs } shouldBe samples.map { it.timeMs }
			restored.map { it.vAccM } shouldBe samples.map { it.vAccM }
			restored.map { it.speedAccuracyMps } shouldBe samples.map { it.speedAccuracyMps }
			restored.map { it.hAccM } shouldBe samples.map { it.hAccM }
			restored.map { it.speedMps } shouldBe samples.map { it.speedMps }
			restored.map { it.quality } shouldBe samples.map { it.quality }
		} finally {
			restoredDatabase.close()
		}
	}

	@Test
	fun `json export includes sessions beyond legacy five hundred row trip cap`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val exporter = JsonExporter(TestDispatchersProvider(dispatcher))
		val baseTimeMs = 1_725_000_000_000L
		val sessionCount = 505
		repeat(sessionCount) { index ->
			val start = baseTimeMs + index * 60_000L
			sessionSegmentDao.insert(
				createSessionSegment(
					startTimeMs = start,
					endTimeMs = start + 30_000L,
					sampleCount = 1,
					distanceM = index.toFloat(),
					steps = index + 1,
				),
			)
		}

		val outputFile = createTempExportFile()
		outputFile.outputStream().use { outputStream ->
			exporter.export(
				context = context,
				locationData = emptySequence(),
				outputStream = outputStream,
				dateRange = baseTimeMs..(baseTimeMs + sessionCount * 60_000L),
			).shouldBeInstanceOf<ExportResult.Success>()
		}

		JSONArray(outputFile.readText(Charsets.UTF_8)).length() shouldBe sessionCount
	}

	@Test
	fun `json export includes a straddling session with its complete own-range data`() = runTest {
		val exporter = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
		val sessionStart = 10_000L
		val sessionEnd = 30_000L
		val samples = listOf(
			createLocationSample(10_000L, 50.0, 14.0, 100f, 1f, 5f),
			createLocationSample(20_000L, 50.1, 14.1, 101f, 1f, 5f),
			createLocationSample(30_000L, 50.2, 14.2, 102f, 1f, 5f),
		)
		locationSampleDao.insert(samples)
		sessionSegmentDao.insert(
			createSessionSegment(
				startTimeMs = sessionStart,
				endTimeMs = sessionEnd,
				sampleCount = samples.size,
				distanceM = 50f,
				steps = 10,
			),
		)

		val outputFile = createTempExportFile()
		outputFile.outputStream().use { stream ->
			exporter.export(
				context = context,
				locationData = emptySequence(),
				outputStream = stream,
				dateRange = 20_000L..25_000L,
			).shouldBeInstanceOf<ExportResult.Success>()
		}

		val records = JSONArray(outputFile.readText(Charsets.UTF_8))
		records.length() shouldBe 1
		val record = records.getJSONObject(0)
		record.getJSONObject("session").getLong("startTimeMs") shouldBe sessionStart
		record.getJSONArray("locations").length() shouldBe samples.size
		record.getJSONArray("locations").getJSONObject(0).getLong("timeMs") shouldBe sessionStart
		record.getJSONArray("locations").getJSONObject(2).getLong("timeMs") shouldBe sessionEnd
	}

	@Test
	fun `json export preserves locations outside every session in an orphaned data record`() = runTest {
		val exporter = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
		val orphan = createLocationSample(40_000L, 50.3, 14.3, 103f, 1f, 5f)
		locationSampleDao.insert(orphan)

		val outputFile = createTempExportFile()
		val result = outputFile.outputStream().use { stream ->
			exporter.export(
				context = context,
				locationData = emptySequence(),
				outputStream = stream,
				dateRange = 35_000L..45_000L,
			).shouldBeInstanceOf<ExportResult.Success>()
		}

		result.recordCount shouldBe 1
		result.maxTimeMs shouldBe orphan.timeMs
		val record = JSONArray(outputFile.readText(Charsets.UTF_8)).getJSONObject(0)
		record.getBoolean("orphanedData") shouldBe true
		record.getJSONArray("locations").getJSONObject(0).getLong("timeMs") shouldBe orphan.timeMs
	}

	@Test
	fun `json export includes legacy radio readings without a Trip or Location`() = runTest {
		val timeMs = 40_000L
		database.wifiObservationDao().insert(WifiObservation(
			timeMs = timeMs, bssid = "00:11:22:33:44:55", ssid = "Tracker",
			capabilities = "[WPA2]", frequency = 2_412, level = -50,
			latE7 = null, lonE7 = null, provenance = CoordinateProvenance.UNKNOWN,
			createdAt = timeMs,
		))
		val outputFile = createTempExportFile()
		val exporter = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
		outputFile.outputStream().use { stream ->
			exporter.export(context, emptySequence(), stream, 35_000L..45_000L)
				.shouldBeInstanceOf<ExportResult.Success>()
		}
		val records = JSONArray(outputFile.readText(Charsets.UTF_8))
		records.length() shouldBe 1
		records.getJSONObject(0).getJSONArray("wifiObservations").length() shouldBe 1
		records.getJSONObject(0).has("session") shouldBe false
	}

	@Test
	fun `composite cursor skips older ids and includes a newer id at the same timestamp`() = runTest {
		val timeMs = 50_000L
		val ids = locationSampleDao.insert(
			listOf(
				createLocationSample(timeMs, 50.0, 14.0, 100f, 1f, 5f),
				createLocationSample(timeMs, 50.1, 14.1, 101f, 1f, 5f),
				createLocationSample(timeMs, 50.2, 14.2, 102f, 1f, 5f),
			),
		)

		val exportedIds = pagedLocationSequence(
			locationSampleDao = locationSampleDao,
			fromMs = timeMs,
			toMs = timeMs,
			pageSize = 2,
			initialAfterTimeMs = timeMs,
			initialAfterId = ids[1],
		).map { it.id }.toList()

		exportedIds shouldBe listOf(ids[2])
	}

	@Test
	fun `ambient-only Room geography exports as JSON3 without inventing a session`() = runTest {
		val sample = insertAmbientSample()
		val exporter = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
		val outputFile = createTempExportFile()
		val result = outputFile.outputStream().use { stream ->
			exporter.export(context, emptySequence(), stream, 35_000L..45_000L)
				.shouldBeInstanceOf<ExportResult.Success>()
		}

		result.recordCount shouldBe 1
		val records = JSONArray(outputFile.readText(Charsets.UTF_8))
		records.length() shouldBe 1
		val record = records.getJSONObject(0)
		record.getInt("schemaVersion") shouldBe 3
		record.getBoolean("orphanedData") shouldBe true
		record.has("session") shouldBe false
		val point = record.getJSONArray("locations").getJSONObject(0)
		point.getLong("timeMs") shouldBe sample.timeMs
		point.getDouble("verticalAccuracyM").toFloat() shouldBe sample.vAccM
		point.getDouble("speedAccuracyMps").toFloat() shouldBe sample.speedAccuracyMps
		point.getString("quality") shouldBe sample.quality.name
		// The ordinary capture reader remains empty: export did not create capture membership.
		locationSampleDao.countBetween(35_000L, 45_000L) shouldBe 0
	}

	@Test
	fun `JSON export retries an authority change without hiding earlier ambient history`() = runTest {
		val sample = insertAmbientSample()
		val exporter = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
		val guardedDatabase = databaseAfterAmbientPage {
			database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(
				sourceKind = 1, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, epoch = 2,
				eligible = false, persistenceEligible = false, policyRevision = 1,
				effectiveBootId = "test-boot", effectiveElapsedRealtimeNanos = 3,
				effectiveWallTimeMs = 45_000, changeReason = "USER_REVOKED",
			)))
		}
		createTempExportFile().outputStream().use { stream ->
			exporter.exportAfter(context, emptySequence(), stream, 35_000L..45_000L,
				null, null, guardedDatabase, {}).shouldBeInstanceOf<ExportResult.Error>()
		}
		val saved = database.locationPurposeDao().visiblePathChunk(35_000L, 45_000L, null, null, 10).single()
		saved.sample shouldBe sample
		saved.hasVisibleBackgroundPurpose shouldBe true
		saved.hasSessionPurpose shouldBe false
		val retryFile = createTempExportFile()
		val retry = retryFile.outputStream().use { stream ->
			exporter.export(context, emptySequence(), stream, 35_000L..45_000L)
				.shouldBeInstanceOf<ExportResult.Success>()
		}
		retry.recordCount shouldBe 1
		val records = JSONArray(retryFile.readText(Charsets.UTF_8))
		records.length() shouldBe 1
		val point = records.getJSONObject(0).getJSONArray("locations").getJSONObject(0)
		point.getLong("timeMs") shouldBe sample.timeMs
	}

	@Test
	fun `JSON export rejects a buffered ambient page when retention preparation fences reads`() = runTest {
		insertAmbientSample()
		val exporter = JsonExporter(TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
		val guardedDatabase = databaseAfterAmbientPage {
			// Epoch, floor and retention receipt are deliberately unchanged at preparation.
			database.collectedDataDeletionOperationDao().insert(CollectedDataDeletionOperationEntity(
				operationId = "retention-preparation", targetCollectedDataEpoch = 0,
				retainedFromMs = 41_000, deletedAtMs = 45_000,
				phase = CollectedDataDeletionOperationEntity.PHASE_RETENTION_PREPARED,
				updatedAtMs = 45_000,
			))
		}
		createTempExportFile().outputStream().use { stream ->
			// Empty access hook matches interactive export: the scalar Room fence must suffice.
			exporter.exportAfter(context, emptySequence(), stream, 35_000L..45_000L,
				null, null, guardedDatabase, {}).shouldBeInstanceOf<ExportResult.Error>()
		}
		database.locationPurposeDao().visiblePathChunk(35_000L, 45_000L, null, null, 10) shouldBe emptyList()
	}

	/** Real Room queries with a deterministic lifecycle transition after a page is buffered. */
	private fun databaseAfterAmbientPage(changeAuthority: suspend () -> Unit): AppDatabase {
		val actual = database.locationPurposeDao()
		var changed = false
		val intercepted = object : LocationPurposeDao by actual {
			override suspend fun visiblePathChunk(
				fromMs: Long, toMs: Long, afterTimeMs: Long?, afterId: Long?, limit: Int,
				ambientOnly: Boolean, northE7: Int?, southE7: Int?, eastE7: Int?, westE7: Int?,
			): List<LocationPathSampleRow> {
				val page = actual.visiblePathChunk(fromMs, toMs, afterTimeMs, afterId, limit,
					ambientOnly, northE7, southE7, eastE7, westE7)
				if (ambientOnly && page.isNotEmpty() && !changed) {
					changed = true
					changeAuthority()
				}
				return page
			}
		}
		return mockk<AppDatabase> {
			every { locationPurposeDao() } returns intercepted
			every { locationSampleDao() } returns database.locationSampleDao()
			every { tripDao() } returns database.tripDao()
			every { wifiObservationDao() } returns database.wifiObservationDao()
			every { cellSampleDao() } returns database.cellSampleDao()
		}
	}

	private suspend fun insertAmbientSample(): EntityLocationSample {
		val sample = createLocationSample(40_000L, 50.3, 14.3, 103f, 1f, 5f)
		val id = locationSampleDao.insert(sample)
		database.sourceEvidenceStateDao().ensure()
		database.sourcePolicyDao().ensureAuthority(SourcePolicyAuthorityEntity(
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE, currentPolicyRevision = 1,
			legacySettingsFingerprint = null, updatedAtMs = 0,
		))
		database.sourcePolicyDao().insertPolicies(listOf(SourcePolicyEntity(
			policyRevision = 1, sourceKind = 1, enabled = true, qosCode = 2,
			locationMinTimeSeconds = 10, locationMinDistanceMeters = 0, locationRequiredAccuracyMeters = 50,
			capturePersistenceEligible = false, controlPersistenceEligible = false, ambientPersistenceEligible = true,
			captureConsentEpoch = null, controlConsentEpoch = null, ambientConsentEpoch = 1,
			effectiveBootId = "test-boot", effectiveElapsedRealtimeNanos = 0,
			effectiveWallTimeMs = 0, changeReason = "TEST",
		)))
		database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(
			sourceKind = 1, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, epoch = 1,
			eligible = true, persistenceEligible = true, policyRevision = 1,
			effectiveBootId = "test-boot", effectiveElapsedRealtimeNanos = 0,
			effectiveWallTimeMs = 0, changeReason = "TEST",
		)))
		val purposes = database.locationPurposeDao()
		purposes.insertRetentionAuthority(AmbientLocationRetentionAuthorityIntegrity.create(
			AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, 1,
			AmbientLocationRetentionAuthorityEntity.STATE_ACTIVE, "location-retention", 1, 1,
			0, "test-boot", 0, 0, null,
		))
		purposes.insertReceiptIfAbsent(LocationAcceptedReceiptEntity(
			sampleId = id, sourceEventId = "event", sourceSignalId = "signal", sourceAdmissionOrdinal = 1,
			walIntegrityIdentity = "0".repeat(64), canonicalContentChecksum = "1".repeat(64),
			sourceInstanceId = "location-instance", registrationGeneration = 1, sourceSequence = 0,
			deliveryIdentity = "delivery", deliveryUnitIndex = 0, deliveryUnitCount = 1,
			writerId = "protected-location", writerVersion = 1, configurationRevision = 1,
			physicalConfigurationFingerprint = "physical", authorizationRevision = 1,
			authorizationFingerprint = "authorization", clockDomainId = "test-clock", bootId = "test-boot",
			observedElapsedRealtimeNanos = 1, receivedElapsedRealtimeNanos = 2,
			observedWallTimeMs = sample.timeMs, receivedWallTimeMs = sample.timeMs,
			wallTimeUncertaintyMs = 0, collectedDataEpoch = 0,
		))
		purposes.insertPurposeIfAbsent(LocationSamplePurposeAuthorityEntity(
			sampleId = id, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, authorityId = "ambient-authority",
			sourcePolicyRevision = 1, consentEpoch = 1, effectiveFromElapsedNanos = 0, effectiveUntilElapsedNanos = 3,
			logicalTrackingId = null, serviceRunId = null, sessionSegmentId = null,
			sessionManifestRevision = null, lifecycleLeaseGeneration = null, ambientIntentId = "ambient-intent",
			retentionPolicyId = "location-retention", retentionApprovalRevision = 1, scopeDeletionGeneration = 0,
			zoneId = null,
		))
		return sample.copy(id = id)
	}

	private suspend fun loadPersistedLocations(dateRange: LongRange): Sequence<LocationSample> {
		val collected = mutableListOf<LocationSample>()
		var afterTimeMs: Long? = null
		var afterId: Long? = null

		while (true) {
			val chunk = locationSampleDao.getChunkBetweenOrdered(
				fromMs = dateRange.first,
				toMs = dateRange.last,
				afterTimeMs = afterTimeMs,
				afterId = afterId,
				limit = 64,
			)
			if (chunk.isEmpty()) {
				break
			}

			collected += chunk.map { it.toModel() }
			val last = chunk.last()
			afterTimeMs = last.timeMs
			afterId = last.id
		}

		return collected.asSequence()
	}

	private fun createTempExportFile(): File =
		File(context.cacheDir, "json-export-room-e2e-${System.nanoTime()}.json").also(tempFiles::add)

	private fun createLocationSample(
		timeMs: Long,
		latitude: Double,
		longitude: Double,
		altitudeM: Float,
		speedMps: Float,
		accuracyM: Float,
	): EntityLocationSample = EntityLocationSample(
		timeMs = timeMs,
		elapsedRealtimeNanos = timeMs * 1_000_000L,
		latE7 = (latitude * 1e7).toInt(),
		lonE7 = (longitude * 1e7).toInt(),
		altitudeM = altitudeM,
		rawGpsAltitudeM = altitudeM,
		hAccM = accuracyM,
		vAccM = accuracyM + 1f,
		speedMps = speedMps,
		speedAccuracyMps = 0.4f,
		provider = "fused",
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		policy = "PASSIVE_LOW",
		bucketId = null,
		createdAt = timeMs,
	)

	private fun createSessionSegment(
		startTimeMs: Long,
		endTimeMs: Long,
		sampleCount: Int,
		distanceM: Float,
		steps: Int,
	): SessionSegment = SessionSegment(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = sampleCount,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = endTimeMs,
	)

	private fun EntityLocationSample.latitude(): Double = (latE7 ?: 0) / 1e7

	private fun EntityLocationSample.longitude(): Double = (lonE7 ?: 0) / 1e7
}
