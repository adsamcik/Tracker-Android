package com.adsamcik.tracker.impexp.exporter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.file.LocationFileImport
import com.adsamcik.tracker.impexp.importer.file.LocationFileImportReview
import com.adsamcik.tracker.impexp.importer.file.LocationFileConflictChoice
import com.adsamcik.tracker.impexp.importer.file.LocationPointIdIndex
import com.adsamcik.tracker.impexp.importer.file.inspectLocationFile
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFileOrigin
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationFileExporterTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase

	@Before fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After fun tearDown() = database.close()

	@Test fun `saved Location points export without a tracking session and keep IDs across ranges`(): Unit = runBlocking {
		database.locationSampleDao().insert(sample(1_000L))
		database.locationSampleDao().insert(sample(2_000L))
		val exporter = LocationFileExporter()
		exporter.requiresLocationData shouldBe false
		val first = export(exporter, 1_000L..1_000L)
		val second = export(exporter, 1_000L..2_000L)

		first.size shouldBe 1
		second.size shouldBe 2
		first.single().pointId shouldBe second.first().pointId
		second.map { it.measurement.timeMs } shouldBe listOf(1_000L, 2_000L)
		second.map { it.origin } shouldBe listOf(LocationFileOrigin.UNKNOWN, LocationFileOrigin.UNKNOWN)
	}

	@Test fun `saved point with no coordinates remains in the Location file`() = runBlocking {
		database.locationSampleDao().insert(sample(1_000L).copy(latE7 = null, lonE7 = null))
		val point = export(LocationFileExporter(), 1_000L..1_000L).single()
		point.measurement.latE7 shouldBe null
		point.measurement.lonE7 shouldBe null
		Unit
	}

	@Test fun `file origin labels are based on currently visible saved purposes`() {
		val sample = sample(1_000L)
		fun row(session: Boolean, background: Boolean) = LocationPathSampleRow(
			sample, null, null, false, session, background,
		)
		row(false, false).fileOrigin() shouldBe LocationFileOrigin.UNKNOWN
		row(true, false).fileOrigin() shouldBe LocationFileOrigin.SESSION
		row(false, true).fileOrigin() shouldBe LocationFileOrigin.BACKGROUND
		row(true, true).fileOrigin() shouldBe LocationFileOrigin.BOTH
	}

	// Keep the explicit stored purpose receipts beside the export to verify session, background, and unknown origins.
	@Suppress("LongMethod")
	@Test fun `saved session point is labelled session by its stored purpose`(): Unit = runBlocking {
		val sampleId = database.locationSampleDao().insert(sample(1_000L).copy(
			sourceEventId = "event-1", sourceSignalId = "signal-1",
		))
		val purposeDao = database.locationPurposeDao()
		val sessionReceipt = LocationAcceptedReceiptEntity(
			sampleId = sampleId,
			sourceEventId = "event-1",
			sourceSignalId = "signal-1",
			sourceAdmissionOrdinal = 1L,
			walIntegrityIdentity = "a".repeat(64),
			canonicalContentChecksum = "b".repeat(64),
			sourceInstanceId = "instance-1",
			registrationGeneration = 1L,
			sourceSequence = 0L,
			deliveryIdentity = "delivery-1",
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			writerId = "writer",
			writerVersion = 1,
			configurationRevision = 1L,
			physicalConfigurationFingerprint = "physical",
			authorizationRevision = 1L,
			authorizationFingerprint = "authorized",
			clockDomainId = "boot",
			bootId = "boot",
			observedElapsedRealtimeNanos = 10L,
			receivedElapsedRealtimeNanos = 11L,
			observedWallTimeMs = 1_000L,
			receivedWallTimeMs = 1_001L,
			wallTimeUncertaintyMs = 0L,
			collectedDataEpoch = 0L,
		)
		purposeDao.insertReceiptIfAbsent(sessionReceipt)
		val sessionPurpose = LocationSamplePurposeAuthorityEntity(
			sampleId = sampleId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			authorityId = "session-grant",
			sourcePolicyRevision = 1L,
			consentEpoch = 0L,
			effectiveFromElapsedNanos = 1L,
			effectiveUntilElapsedNanos = 100L,
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			sessionSegmentId = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			ambientIntentId = null,
			retentionPolicyId = null,
			retentionApprovalRevision = null,
			scopeDeletionGeneration = 0L,
			zoneId = "UTC",
		)
		purposeDao.insertPurposeIfAbsent(sessionPurpose)

		export(LocationFileExporter(), 1_000L..1_000L).single().origin shouldBe LocationFileOrigin.SESSION

		// Turning Location off removes no saved reading. This ambient row has no active policy or
		// current consent setup, yet the user's exact data file still includes it.
		val ambientId = database.locationSampleDao().insert(sample(2_000L).copy(
			sourceEventId = "event-2", sourceSignalId = "signal-2",
		))
		purposeDao.insertReceiptIfAbsent(sessionReceipt.copy(sampleId = ambientId,
			sourceEventId = "event-2", sourceSignalId = "signal-2",
			sourceSequence = 1L,
			observedWallTimeMs = 2_000L, receivedWallTimeMs = 2_001L))
		purposeDao.insertPurposeIfAbsent(sessionPurpose.copy(sampleId = ambientId,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, authorityId = "ambient-grant",
			logicalTrackingId = null, serviceRunId = null, sessionSegmentId = null,
			sessionManifestRevision = null, lifecycleLeaseGeneration = null,
			ambientIntentId = "intent-1", retentionPolicyId = "local-retention",
			retentionApprovalRevision = 1L, zoneId = null))
		database.locationSampleDao().insert(sample(3_000L))
		export(LocationFileExporter(), 1_000L..3_000L).map { it.origin } shouldBe
			listOf(LocationFileOrigin.SESSION, LocationFileOrigin.BACKGROUND, LocationFileOrigin.UNKNOWN)
	}

    @Test fun `native short gap survives actual file export import and geographic export`(): Unit = runBlocking {
        nativePoint(1_000, 7)
        nativePoint(2_000, 9)
        nativePoint(3_000, 10)
        val points = export(LocationFileExporter(), 1_000L..3_000L)
        points.map { it.sequence } shouldBe listOf(7L, 9L, 10L)
        points.map { it.pathId }.distinct().size shouldBe 1
        val partial = export(LocationFileExporter(), 2_000L..2_000L).single()
        partial shouldBe points[1]
        database.close()
        database = AppDatabase.testDatabase(context)
        database.sourceEvidenceStateDao().ensure()
        importBytes(encode(points), "native-gap").successCount shouldBe 3
        val rows = database.locationPurposeDao().savedFileChunk(1_000, 3_000, null, null, 10)
        rows.map { it.continuitySequence } shouldBe listOf(7L, 9L, 10L)
        rows.map { it.sample.elapsedRealtimeNanos } shouldBe listOf(0L, 0L, 0L)
        rows.map { it.sample.clockDomainId }.distinct() shouldBe listOf(null)
        val gpx = ByteArrayOutputStream()
        GpxExporter().export(context, rows.asSequence().map {
            it.sample.toModel().copy(routeContinuityId = it.continuityId, routeSequence = it.continuitySequence)
        }, gpx, null).isSuccess shouldBe true
        Regex("<trkseg>").findAll(gpx.toString(Charsets.UTF_8.name())).count() shouldBe 2
        export(LocationFileExporter(), 1_000L..3_000L) shouldBe points
        importBytes(encode(listOf(partial)), "overlap").skippedCount shouldBe 1
        database.locationSampleDao().countAll() shouldBe 3L
    }

    @Test fun `reviewed native replacement uses file path and retains its original receipt`(): Unit = runBlocking {
        val sampleId = nativePoint(1_000, 7)
        val before = database.locationPurposeDao().contextReceiptsForSamples(listOf(sampleId), 10).single()
        val original = export(LocationFileExporter(), 1_000L..1_000L).single()
        importBytes(encode(listOf(original)), "identical").skippedCount shouldBe 1
        database.locationPurposeDao().savedFileSamples(listOf(sampleId)).single().continuityId shouldBe "native:1"
        val replacement = original.copy(pathId = "owner-edited-path", sequence = 42,
            measurement = original.measurement.copy(timeMs = 2_000, lonE7 = 140_010_000))
        val bytes = encode(listOf(replacement))
        val review = LocationPointIdIndex.open(context.cacheDir).use {
            inspectLocationFile(database, ByteArrayInputStream(bytes), 0, it)
        }
        importBytes(bytes, "replace", LocationFileImportReview(review,
            LocationFileConflictChoice.REPLACE_WITH_FILE)).successCount shouldBe 1
        val current = database.locationPurposeDao().savedFileSamples(listOf(sampleId)).single()
        current.continuityId shouldBe "imported-location:owner-edited-path"
        current.continuitySequence shouldBe 42L
        current.sample.elapsedRealtimeNanos shouldBe 0L
        current.sample.timeMs shouldBe 2_000L
        database.locationPurposeDao().contextReceiptsForSamples(listOf(sampleId), 10).single() shouldBe before
        export(LocationFileExporter(), 2_000L..2_000L).single() shouldBe replacement
        val olderEdit = replacement.copy(pathId = null, sequence = null,
            measurement = replacement.measurement.copy(timeMs = 3_000))
        val olderBytes = encode(listOf(olderEdit))
        val olderReview = LocationPointIdIndex.open(context.cacheDir).use {
            inspectLocationFile(database, ByteArrayInputStream(olderBytes), 0, it)
        }
        importBytes(olderBytes, "older-replace", LocationFileImportReview(olderReview,
            LocationFileConflictChoice.REPLACE_WITH_FILE)).successCount shouldBe 1
        export(LocationFileExporter(), 3_000L..3_000L).single() shouldBe olderEdit.copy(
            pathId = replacement.pathId, sequence = replacement.sequence)
    }

    private suspend fun nativePoint(time: Long, sequence: Long): Long {
        database.sourceEvidenceStateDao().ensure()
        val value = sample(time).copy(lonE7 = 140_000_000 + sequence.toInt() * 1_000,
            clockDomainId = "boot", elapsedRealtimeNanos = time * 1_000_000,
            sourceEventId = "event-$sequence", sourceSignalId = "signal-$sequence")
        val id = database.locationSampleDao().insert(value)
        database.locationPurposeDao().insertReceiptIfAbsent(LocationAcceptedReceiptEntity(
            sampleId = id, sourceEventId = requireNotNull(value.sourceEventId),
            sourceSignalId = requireNotNull(value.sourceSignalId), sourceAdmissionOrdinal = sequence + 1,
            walIntegrityIdentity = "a".repeat(64), canonicalContentChecksum = "b".repeat(64),
            sourceInstanceId = "native", registrationGeneration = 1, sourceSequence = sequence,
            deliveryIdentity = "delivery-$sequence", deliveryUnitIndex = 0, deliveryUnitCount = 1,
            writerId = "writer", writerVersion = 1, configurationRevision = 1,
            physicalConfigurationFingerprint = "physical", authorizationRevision = 1,
            authorizationFingerprint = "authorized", clockDomainId = "boot", bootId = "boot",
            observedElapsedRealtimeNanos = value.elapsedRealtimeNanos,
            receivedElapsedRealtimeNanos = value.elapsedRealtimeNanos + 1,
            observedWallTimeMs = time, receivedWallTimeMs = time + 1,
            wallTimeUncertaintyMs = 0, collectedDataEpoch = 0))
        return id
    }

    private fun encode(points: List<LocationFilePointV1>): ByteArray = ByteArrayOutputStream().also { output ->
        LocationFileJsonV1().writer(output).apply { points.forEach(::point); finish() }
    }.toByteArray()

    private suspend fun importBytes(bytes: ByteArray, job: String, review: LocationFileImportReview? = null) =
        LocationFileImport().import(context, database,
            FileImportStream(ByteArrayInputStream(bytes), "route.trackerlocation").withImportReceipt(job, 1_000)
                .withOriginalDataEpoch(0).let { stream -> review?.let(stream::withLocationReview) ?: stream })

	private suspend fun export(exporter: LocationFileExporter, range: LongRange): List<LocationFilePointV1> {
		val output = ByteArrayOutputStream()
		exporter.exportAfter(context, emptySequence(), output, range, null, null, database, {})
			.isSuccess shouldBe true
		val points = mutableListOf<LocationFilePointV1>()
		LocationFileJsonV1().read(ByteArrayInputStream(output.toByteArray())) { points += it }
		return points
	}

	private fun sample(timeMs: Long) = LocationSample(
		timeMs = timeMs,
		elapsedRealtimeNanos = 0L,
		latE7 = 500_000_000,
		lonE7 = 140_000_000,
		altitudeM = null,
		rawGpsAltitudeM = null,
		hAccM = 30f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "legacy",
		quality = SampleQuality.MEDIUM,
		motionState = null,
		policy = null,
		bucketId = null,
		createdAt = timeMs,
	)
}
