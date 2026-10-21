package com.adsamcik.tracker.impexp.portable

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.PortableActivityExporter
import com.adsamcik.tracker.impexp.exporter.PortablePressureExporter
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.file.PortableActivityFileImport
import com.adsamcik.tracker.impexp.importer.file.PortablePressureFileImport
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.ExportSelectedPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeSink
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real native rows through the user-facing source formats. No session or provider is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllDaySensorSourceFileRoomTest {
	private lateinit var source: AppDatabase
	private lateinit var target: AppDatabase
    private val opened = mutableListOf<SensorSourceFileInput>()
	private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

	@Before fun setup() = runTest {
		source = AppDatabase.testDatabase(context)
		target = AppDatabase.testDatabase(context)
		source.sourceEvidenceStateDao().ensure()
		target.sourceEvidenceStateDao().ensure()
	}

	@After fun close() { opened.forEach { it.close() }; source.close(); target.close() }

	@Test fun `Activity export selects saved dates and overlapping imports keep original identities once`() = runTest {
		val first = activity(999L)
		val selected = activity(1_000L)
		activity(2_000L)
		val exporter = PortableActivityExporter(nativeDatabaseProvider = { source }, exporterProvider = { noActivity() })
		val output = ByteArrayOutputStream()
		exporter.export(context, emptySequence(), output, 1_000L..1_999L) shouldBe ExportResult.Success(1)
		val importer = PortableActivityFileImport { error("A sessionless file must not invoke the session importer") }
		importer.import(context, target, stream(output.toByteArray(), "trackeractivity")).successCount shouldBe 1
		importer.import(context, target, stream(output.toByteArray(), "trackeractivity")).skippedCount shouldBe 1
		target.activitySnapshotDao().getBySourceSignalId(selected)?.timeMs shouldBe 1_000L
		target.activitySnapshotDao().getBySourceSignalId(first) shouldBe null
		target.activitySnapshotDao().getBySourceSignalId(selected)?.observationStamp?.capabilityFlags shouldBe
			AllDaySensorObservationIdentity.IMPORTED_CAPABILITY
		val full = ByteArrayOutputStream()
		exporter.export(context, emptySequence(), full, null) shouldBe ExportResult.Success(3)
		val merged = importer.import(context, target, stream(full.toByteArray(), "trackeractivity"))
		merged.successCount shouldBe 2
		merged.skippedCount shouldBe 1
		target.activitySnapshotDao().allDayCount() shouldBe 3
		target.sourceSessionDao().activeSession() shouldBe null
	}

	@Test fun `Pressure export and import preserve a real window and optional unknown altitude`() = runTest {
		val identity = AllDaySensorObservationIdentity.pressure("pressure-1")
		source.pressureSampleDao().insert(PressureSample(timeMs = 2_000, elapsedRealtimeNanos = 2_000_000_000,
            pressureHpa = 1_010f, altitudeM = 0f, bucketId = null, createdAt = 2_010,
			sourceSignalId = identity, sampleCount = 3, minPressureHpa = 1_000f, maxPressureHpa = 1_020f,
			standardDeviationHpa = 5f, windowStartElapsedRealtimeNanos = 1_000_000_000,
			windowEndElapsedRealtimeNanos = 2_000_000_000,
            observationStamp = ObservationStampColumns(sourceTimeMs = 2_000, clockDomainId = "boot:one",
                capabilityFlags = AllDaySensorObservationIdentity.UNKNOWN_ALTITUDE_CAPABILITY)))
		val output = ByteArrayOutputStream()
		PortablePressureExporter(exporterProvider = { noPressure() }, nativeDatabaseProvider = { source })
			.export(context, emptySequence(), output, null) shouldBe ExportResult.Success(1)
		val importer = PortablePressureFileImport(dependenciesProvider = {
			error("A sessionless file must not invoke the session importer") })
		importer.import(context, target, stream(output.toByteArray(), "trackerpressure")).successCount shouldBe 1
		val saved = requireNotNull(target.pressureSampleDao().getBySourceSignalId(identity))
		saved.timeMs shouldBe 2_000L
		saved.sampleCount shouldBe 3
		saved.windowStartElapsedRealtimeNanos shouldBe 1_000_000_000L
		saved.windowEndElapsedRealtimeNanos shouldBe 2_000_000_000L
        saved.observationStamp.capabilityFlags.orEmpty().contains(
            AllDaySensorObservationIdentity.UNKNOWN_ALTITUDE_CAPABILITY) shouldBe true
		importer.import(context, target, stream(output.toByteArray(), "trackerpressure")).skippedCount shouldBe 1
	}

	@Test fun `V2 keeps unchanged V1 sessions alongside native observations`() = runTest {
		activity(1_500)
		val original = activityEnvelope(activityEntry())
		val sessions = ByteArrayOutputStream()
		PortableActivityJsonV1Codec().encode(sessions) { sink ->
			sink.emit(original)
			ExportPortableCapturedActivityResult.Exported(1)
		}
		val output = ByteArrayOutputStream()
		AllDaySensorSourceFile.write(output, "ACTIVITY", sessions.toByteArray(),
			readSensorRows(source, "ACTIVITY", 0, 3_000))
		val read = openSourceFile(output.toByteArray().inputStream(), "ACTIVITY")
		PortableActivityJsonV1Codec().decode(requireNotNull(read.sessionInput)) shouldBe original
		read.observationCount shouldBe 1
		val legacy = openSourceFile(sessions.toByteArray().inputStream(), "ACTIVITY")
		PortableActivityJsonV1Codec().decode(requireNotNull(legacy.sessionInput)) shouldBe original
		legacy.observationCount shouldBe 0
	}

	@Test fun `V2 accepts the version after an observations array larger than the old header probe`() = runTest {
		(1L..30L).forEach { activity(it) }
		val rows = readSensorRows(source, "ACTIVITY", 0, 100)
		val reordered = buildJsonObject {
			put("observations", JsonArray(rows))
			put("sessionData", JsonNull)
			put("format", com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1.FORMAT)
			put("schemaVersion", 2)
		}.toString().toByteArray()
		(reordered.size > 4_096) shouldBe true
		val read = openSourceFile(reordered.inputStream(), "ACTIVITY")
		read.schemaVersion shouldBe 2
		read.nativeRows() shouldBe rows
		read.sessionInput shouldBe null
	}

	@Test fun `malformed native values cannot apply a valid session prefix`() = runTest {
		activity(1_500)
		val valid = readSensorRows(source, "ACTIVITY", 0, 3_000).single()
		val malformed = JsonObject(valid + ("confidence" to JsonPrimitive(101)))
		val sessions = ByteArrayOutputStream()
		PortableActivityJsonV1Codec().encode(sessions) { sink ->
			sink.emit(activityEnvelope(activityEntry()))
			ExportPortableCapturedActivityResult.Exported(1)
		}
		val output = ByteArrayOutputStream()
		AllDaySensorSourceFile.write(output, "ACTIVITY", sessions.toByteArray(), listOf(malformed))
		val importer = PortableActivityFileImport { error("Malformed files must not invoke the session importer") }
		importer.import(context, target, stream(output.toByteArray(), "trackeractivity")).failedCount shouldBe 1
		target.activitySnapshotDao().allDayCount() shouldBe 0
	}

    @Test fun `V2 truncation is invalid while transport EOF keeps its identity`() = runTest {
        activity(1_500L)
        val output = ByteArrayOutputStream()
        AllDaySensorSourceFile.write(output, "ACTIVITY", byteArrayOf(), readSensorRows(source, "ACTIVITY", 0, 3_000L))
        val bytes = output.toByteArray()
        val importer = PortableActivityFileImport { error("Invalid or interrupted files must not import sessions") }
        val truncated = stream(bytes.dropLast(1).toByteArray(), "trackeractivity")
        importer.import(context, target, truncated).failedCount shouldBe 1
        val original = EOFException("Interrupted source transport")
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(): Int {
                if (available() == 0) { throw original }
                return super.read()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) { throw original }
                return super.read(buffer, offset, length)
            }
        }
        val file = FileImportStream(input, "sensor.trackeractivity")
            .withImportReceipt("interrupted-v2", 5_000L).withOriginalDataEpoch(0L)
        val failure = shouldThrow<EOFException> { importer.import(context, target, file) }
        (failure === original) shouldBe true
        target.activitySnapshotDao().allDayCount() shouldBe 0
    }

	@Test fun `original import epoch and retention floor apply to native observations`() = runTest {
		activity(999)
		activity(1_000)
		val rows = readSensorRows(source, "ACTIVITY", 0, 2_000)
		target.sourceEvidenceStateDao().updateLifecycle(0, 1_000, 2_000)
		val bytes = ByteArrayOutputStream()
		AllDaySensorSourceFile.write(bytes, "ACTIVITY", byteArrayOf(), rows)
		val input = openSourceFile(bytes.toByteArray().inputStream(), "ACTIVITY")
		val retained = AllDaySensorPortableRows.import(target, "ACTIVITY", input, 0)
		retained.successCount shouldBe 1
		retained.skippedCount shouldBe 1
		target.sourceEvidenceStateDao().updateLifecycle(1, null, 3_000)
		AllDaySensorPortableRows.import(target, "ACTIVITY", input, 0).failedCount shouldBe 1
		target.activitySnapshotDao().allDayCount() shouldBe 1
	}

	@Test fun `paged native export crosses the database page boundary without omissions`() = runTest {
		(1L..501L).forEach { activity(it) }
		val output = ByteArrayOutputStream()
		PortableActivityExporter(nativeDatabaseProvider = { source }, exporterProvider = { noActivity() })
			.export(context, emptySequence(), output, null) shouldBe ExportResult.Success(501)
		val importer = PortableActivityFileImport { error("No session capture payload") }
		importer.import(context, target, stream(output.toByteArray(), "trackeractivity")).successCount shouldBe 501
		target.activitySnapshotDao().allDayCount() shouldBe 501
		target.activitySnapshotDao().getBySourceSignalId("all-day:activity:event-1")?.timeMs shouldBe 1L
		target.activitySnapshotDao().getBySourceSignalId("all-day:activity:event-501")?.timeMs shouldBe 501L
	}

    private suspend fun openSourceFile(input: java.io.InputStream, source: String): SensorSourceFileInput =
        AllDaySensorSourceFile.open(input, source, context).also(opened::add)

	private suspend fun activity(time: Long): String {
		val id = AllDaySensorObservationIdentity.activity("event-$time")
		source.activitySnapshotDao().insert(ActivitySnapshot(timeMs = time, activityType = 7,
			confidence = 75, isTransition = true, createdAt = time + 100, sourceSignalId = id))
		return id
	}

	private fun stream(bytes: ByteArray, extension: String) = FileImportStream(bytes.inputStream(), "sensor.$extension")
		.withImportReceipt("native-file-job", 5_000).withOriginalDataEpoch(0)

	private fun noActivity() = object : ExportPortableCapturedActivity {
		override suspend fun export(request: ExportPortableCapturedActivityRequest, sink: PortableActivityEnvelopeSink) =
			ExportPortableCapturedActivityResult.NoEntries
		override suspend fun export(request: ExportSelectedPortableCapturedActivityRequest, sink: PortableActivityEnvelopeSink) =
			ExportPortableCapturedActivityResult.NoEntries
	}

	private fun noPressure() = object : ExportPortablePressure {
		override suspend fun export(request: ExportPortablePressureRequest, sink: PortablePressureEntrySink) =
			ExportPortablePressureResult.NoEntries
		override suspend fun export(request: ExportSelectedPortablePressureRequest, sink: PortablePressureEntrySink) =
			ExportPortablePressureResult.NoEntries
	}
}

internal suspend fun readSensorRows(database: AppDatabase, source: String, from: Long, to: Long): List<JsonObject> {
    val rows = mutableListOf<JsonObject>()
    AllDaySensorPortableRows.forEach(database, source, from, to) { rows += it }
    return rows
}

internal suspend fun SensorSourceFileInput.nativeRows(): List<JsonObject> {
    val rows = mutableListOf<JsonObject>()
    forEachObservation { rows += it }
    return rows
}
