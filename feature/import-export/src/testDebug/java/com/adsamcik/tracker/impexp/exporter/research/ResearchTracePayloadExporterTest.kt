package com.adsamcik.tracker.impexp.exporter.research

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchTracePayloadExporterTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `payload streams manifest markers raw evidence runs and accepted samples`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val rangeStart = 1_725_000_000_000L
		val rangeEnd = rangeStart + 10_000L
		val runId = database.trackerRunDao().insert(
			TrackerRun(
				startTimeMs = rangeStart - 1_000L,
				endTimeMs = rangeStart + 9_000L,
				policy = "ACTIVE_MODERATE",
				policyParams = "{\"intervalMs\":30000}",
				userInitiated = false,
				createdAt = rangeStart - 1_000L,
			),
		)
		database.locationObservationDao().insert(rejectedObservation(rangeStart + 1_000L))

		val exporter = ResearchTracePayloadExporter(
			metadata = ResearchTraceMetadata(
				traceId = "trace-prague-01",
				sessionLabel = "surveyed gate pass",
				attributes = mapOf("groundTruth" to "RTK+video"),
				markers = listOf(
					ResearchTraceMarker(
						id = "gate-a-entry",
						label = "Gate A entry",
						wallTimeMs = rangeStart + 1_500L,
						elapsedRealtimeNanos = 88_000_000_000L,
						attributes = mapOf("videoFrame" to "1842"),
					),
				),
			),
			databaseProvider = { database },
			dispatchers = TestDispatchersProvider(dispatcher),
			pageSize = 1,
			nowMillis = { rangeEnd + 1_000L },
		)
		val accepted = acceptedSample(rangeStart + 2_000L)
		val outsideRange = acceptedSample(rangeEnd + 1L)
		val output = ByteArrayOutputStream()

		val result = exporter.export(
			context = context,
			locationData = sequenceOf(accepted, outsideRange),
			outputStream = output,
			dateRange = rangeStart..rangeEnd,
		)

		result shouldBe ExportResult.Success
		val records = output.toString(Charsets.UTF_8.name())
			.lineSequence()
			.filter(String::isNotBlank)
			.map { JSONObject(it) }
			.toList()
		records.first().getString("recordType") shouldBe "manifest"
		records.last().getString("recordType") shouldBe "end"

		val manifest = records.first()
		manifest.getInt("schemaVersion") shouldBe 3
		manifest.getLong("rangeStartMs") shouldBe rangeStart
		manifest.getLong("rangeEndInclusiveMs") shouldBe rangeEnd
		manifest.getJSONObject("session").getString("traceId") shouldBe "trace-prague-01"
		manifest.getJSONObject("session").getJSONObject("attributes").getString("groundTruth") shouldBe "RTK+video"
		manifest.getInt("markerCount") shouldBe 1
		val capabilities = manifest.getJSONObject("evidenceCapabilities")
		capabilities.getBoolean("rawPressureEvents") shouldBe false
		capabilities.getBoolean("canonicalSegmentationObservations") shouldBe false
		capabilities.getBoolean("truthMarkers") shouldBe true
		val loss = manifest.getJSONObject("loss")
		loss.getBoolean("lossOccurred") shouldBe false
		loss.getString("assessment") shouldBe "HISTORICAL_EXPORT_NOT_CAPTURE_TRACE"
		loss.getBoolean("replayComplete") shouldBe false

		val marker = records.single { it.getString("recordType") == "trace_marker" }
		marker.getString("id") shouldBe "gate-a-entry"
		marker.getString("label") shouldBe "Gate A entry"
		marker.getLong("wallTimeMs") shouldBe rangeStart + 1_500L
		marker.getLong("elapsedRealtimeNanos") shouldBe 88_000_000_000L
		marker.getJSONObject("attributes").getString("videoFrame") shouldBe "1842"

		val observation = records.single { it.getString("recordType") == "location_observation" }
		observation.getString("ingressDisposition") shouldBe "REJECTED_PRE_VALIDATION"
		observation.getLong("fixTimeMs") shouldBe rangeStart + 1_000L
		observation.getLong("fixElapsedRealtimeNanos") shouldBe 87_500_000_000L
		observation.getLong("receivedAtMs") shouldBe rangeStart + 1_250L
		observation.getLong("receivedElapsedRealtimeNanos") shouldBe 87_750_000_000L
		observation.getString("acquisitionMode") shouldBe "FUSED"
		observation.getString("requestPriority") shouldBe "BALANCED"
		observation.getString("permissionPrecision") shouldBe "APPROXIMATE"
		observation.getInt("batchIndex") shouldBe 1
		observation.getInt("batchSize") shouldBe 3

		val trackerRun = records.single { it.getString("recordType") == "tracker_run" }
		trackerRun.getLong("id") shouldBe runId
		trackerRun.getString("policy") shouldBe "ACTIVE_MODERATE"

		val acceptedRecord = records.single { it.getString("recordType") == "accepted_location_sample" }
		acceptedRecord.getLong("id") shouldBe 42L
		acceptedRecord.getLong("receivedElapsedRealtimeNanos") shouldBe 88_500_000_000L
		acceptedRecord.getString("permissionPrecision") shouldBe "PRECISE"
		acceptedRecord.getString("altitudeDatum") shouldBe "android_model_msl"
		acceptedRecord.getString("altitudeSource") shouldBe "gps_conversion"
		acceptedRecord.getString("altitudeConversionStatus") shouldBe "success"
		acceptedRecord.getString("rawGpsAltitudeDatum") shouldBe "wgs84_ellipsoid"
		acceptedRecord.getInt("altitudeModelVersion") shouldBe 1

		val counts = records.last().getJSONObject("counts")
		counts.getLong("traceMarker") shouldBe 1L
		counts.getLong("locationObservation") shouldBe 1L
		counts.getLong("trackerRun") shouldBe 1L
		counts.getLong("acceptedLocationSample") shouldBe 1L
	}

	@Test
	fun `payload requires an explicit non-empty selected range`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val exporter = ResearchTracePayloadExporter(
			databaseProvider = { database },
			dispatchers = TestDispatchersProvider(dispatcher),
		)
		val output = ByteArrayOutputStream()

		val result = exporter.export(context, emptySequence(), output, null)

		result.isSuccess shouldBe false
		output.toString(Charsets.UTF_8.name()) shouldBe ""
	}

	@Test
	fun `encrypted helper keeps the manual payload behind research trace envelope`() {
		val payload = ResearchTracePayloadExporter(databaseProvider = { database })

		val encrypted = payload.encrypted { "a sufficiently long passphrase".toCharArray() }

		encrypted.canSelectDateRange shouldBe true
		encrypted.mimeType shouldBe "application/vnd.tracker.research-trace"
		encrypted.extension shouldBe "trackertrace"
	}

	private fun rejectedObservation(fixTimeMs: Long): LocationObservation = LocationObservation(
		fixTimeMs = fixTimeMs,
		fixElapsedRealtimeNanos = 87_500_000_000L,
		receivedAtMs = fixTimeMs + 250L,
		receivedElapsedRealtimeNanos = 87_750_000_000L,
		deliveryAgeMs = 250L,
		latE7 = null,
		lonE7 = null,
		rawAltitudeM = null,
		hAccM = 850f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "fused",
		acquisitionMode = "FUSED",
		requestPriority = "BALANCED",
		permissionPrecision = "APPROXIMATE",
		batchIndex = 1,
		batchSize = 3,
		isMock = false,
		ingressDisposition = "REJECTED_PRE_VALIDATION",
		estimatorVersion = 1,
		calibrationVersion = 0,
		createdAt = fixTimeMs + 250L,
	)

	private fun acceptedSample(timeMs: Long): LocationSample = LocationSample(
		id = 42L,
		timeMs = timeMs,
		elapsedRealtimeNanos = 88_250_000_000L,
		latE7 = 500_874_650,
		lonE7 = 144_212_540,
		altitudeM = 220f,
		rawGpsAltitudeM = 265f,
		hAccM = 4.5f,
		vAccM = 7f,
		speedMps = 1.2f,
		speedAccuracyMps = 0.4f,
		provider = "fused",
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		policy = "ACTIVE_MODERATE",
		bucketId = null,
		createdAt = timeMs + 300L,
		receivedElapsedRealtimeNanos = 88_500_000_000L,
		deliveryAgeMs = 250L,
		acquisitionMode = "FUSED",
		requestPriority = "BALANCED",
		permissionPrecision = "PRECISE",
		batchIndex = 0,
		batchSize = 1,
		isMock = false,
		estimatorVersion = 1,
		calibrationVersion = 0,
		altitudeDatum = AltitudeDatum.ANDROID_MODEL_MSL,
		altitudeSource = AltitudeSource.GPS_CONVERSION,
		altitudeConversionStatus = AltitudeConversionStatus.SUCCESS,
		rawGpsAltitudeDatum = AltitudeDatum.WGS84_ELLIPSOID,
		altitudeModelVersion = 1,
	)
}
