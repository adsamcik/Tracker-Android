package com.adsamcik.tracker.impexp.exporter

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFileOrigin
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Source authored only; these exercise the real Room selector, staging, codec and export result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionLocationFileExporterTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private val exporter = LocationFileExporter()

	@Before fun setup(): Unit = runBlocking {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure()
	}
	@After fun close() = database.close()

	@Test fun `native selection includes every replacement run and excludes simultaneous unrelated sources`(): Unit = runBlocking {
		logical("chosen", 1_000, 20_000)
		val first = run("chosen", "first", 1_000, 10_000)
		val second = run("chosen", "second", 10_000, 20_000)
		nativePoint(first, 2_000)
		nativePoint(second, 11_000)
		logical("other", 1_000, 20_000)
		nativePoint(run("other", "other-run", 1_000, 20_000), 2_000)
		point(2_000) // unattributed saved data at exactly the same millisecond
		val points = export(recording("logical:chosen"))
		assertEquals(listOf(2_000L, 11_000L), points.map { it.measurement.timeMs })
		assertEquals(listOf(LocationFileOrigin.SESSION, LocationFileOrigin.SESSION), points.map { it.origin })
		assertEquals(2, points.map { it.pointId }.distinct().size)
	}

	@Test fun `display date edit cannot widen or trim original native membership`(): Unit = runBlocking {
		logical("chosen", 1_000, 20_000)
		val original = run("chosen", "original", 1_000, 20_000)
		nativePoint(original, 2_000); nativePoint(original, 18_000)
		point(30_000)
		annotation("override", 15_000, 40_000, "logical:chosen", revision = 3)
		val request = recording("logical:chosen", revision = 3)
		assertEquals(listOf(2_000L, 18_000L), export(request).map { it.measurement.timeMs })
		val staleOutput = ByteArrayOutputStream()
		assertIs<ExportResult.Error>(write(request.copy(expectedOrganizationRevision = 2), staleOutput))
		assertEquals(0, staleOutput.size())
	}

	@Test fun `genuine legacy recording uses original dates and excludes imported and native coincident points`(): Unit = runBlocking {
		val legacy = segment(1_000, 10_000)
		val local = point(2_000)
		point(9_000)
		database.locationFileIdentityDao().localIdentityFor(local.id)
		archive('d', listOf(local)) // later duplicate import does not remove original local membership
		val imported = point(2_000)
		database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity("imported-only", imported.id, true, "SESSION"))
		logical("other", 1_000, 10_000)
		nativePoint(run("other", "other-run", 1_000, 10_000), 3_000)
		annotation("override", 8_000, 30_000, "segment:${legacy.id}", revision = 1)
		point(20_000)
		val points = export(recording("segment:${legacy.id}", 1))
		assertEquals(listOf(2_000L, 9_000L), points.map { it.measurement.timeMs })
		assertTrue(points.all { it.origin == LocationFileOrigin.UNKNOWN })
	}

	@Test fun `migrated logical members use original intervals without a native date fallback`(): Unit = runBlocking {
		logical("migrated", 1_000, 20_000)
		run("migrated", "old-first", 1_000, 9_000, legacy = true)
		run("migrated", "old-second", 10_000, 20_000, legacy = true)
		point(2_000); point(11_000); point(9_500)
		assertEquals(listOf(2_000L, 11_000L), export(recording("logical:migrated")).map { it.measurement.timeMs })
	}

	@Test fun `single timestamp legacy recording keeps its actual point without inventing a duration`(): Unit = runBlocking {
		val original = segment(2_000, 2_000)
		point(2_000); point(2_001)
		assertEquals(listOf(2_000L), export(recording("segment:${original.id}")).map { it.measurement.timeMs })
	}

	@Test fun `retrospective interval includes coordinate-free points and preserves gaps and end exclusion`(): Unit = runBlocking {
		annotation("retro", 1_000, 20_000)
		point(1_000)
		val unavailable = point(10_000).copy(latE7 = null, lonE7 = null)
		database.locationSampleDao().update(unavailable)
		point(19_000); point(20_000); point(999)
		val points = export(annotationRequest("retro"))
		assertEquals(listOf(1_000L, 10_000L, 19_000L), points.map { it.measurement.timeMs })
		assertEquals(null, points[1].measurement.latE7)
		assertEquals(null, points[1].measurement.lonE7)
		// The file contains actual observations only, including the unavailable fix and real spacing.
		assertEquals(3, points.size)
	}

	@Test fun `imported selection uses exact files even outside display dates and deduplicates overlapping membership`(): Unit = runBlocking {
		val first = point(2_000)
		val second = point(30_000)
		val third = point(40_000)
		val a = archive('a', listOf(first, second))
		val b = archive('b', listOf(second, third))
		annotation("import", 1_000, 10_000, selection = listOf(a, b))
		point(5_000) // a coincident nonmember cannot enter the exported child
		val points = export(annotationRequest("import"))
		assertEquals(listOf(2_000L, 30_000L, 40_000L), points.map { it.measurement.timeMs })
		assertEquals(3, points.map { it.pointId }.distinct().size)
		assertTrue(points.all { it.origin == LocationFileOrigin.BACKGROUND })
	}

	@Test fun `missing archive and deleted exact point fail without date substitution or destination bytes`(): Unit = runBlocking {
		val saved = point(2_000)
		val selected = archive('a', listOf(saved))
		annotation("import", 1_000, 10_000, selection = listOf(selected))
		database.locationSampleDao().deleteExactIds(listOf(saved.id))
		point(2_000)
		val output = ByteArrayOutputStream()
		assertIs<ExportResult.Error>(write(annotationRequest("import"), output))
		assertEquals(0, output.size())
		annotation("missing-file", 1_000, 10_000, selection = listOf(OrganizationSourceSelector.LocationFile("b".repeat(64))))
		assertIs<ExportResult.Error>(write(annotationRequest("missing-file"), output))
		assertEquals(0, output.size())
		assertSpoolEmpty()
	}

	@Test fun `archive duplicate of a native point keeps its saved session and background origin`(): Unit = runBlocking {
		logical("native", 1_000, 10_000)
		val native = nativePoint(run("native", "run", 1_000, 10_000), 2_000)
		addBackgroundOrigin(native.id)
		val original = export(recording("logical:native")).single()
		assertEquals(LocationFileOrigin.BOTH, original.origin)
		val selected = archive('c', listOf(native)) // deduplicates into the local identity, imported=false
		annotation("duplicate", 1_000, 10_000, selection = listOf(selected))
		val reexported = export(annotationRequest("duplicate")).single()
		assertEquals(original.pointId, reexported.pointId)
		assertEquals(LocationFileOrigin.BOTH, reexported.origin)
		assertEquals(original.measurement, reexported.measurement)
	}

	@Test fun `all run and point pages are lossless beyond a single membership page`(): Unit = runBlocking {
		logical("many", 1_000, 100_000)
		for (index in 0..LocationFileExporter.PAGE_SIZE) {
			val member = run("many", "run:$index", 1_000, 100_000)
			if (index == LocationFileExporter.PAGE_SIZE) {
				for (point in 0..LocationFileExporter.PAGE_SIZE) nativePoint(member, 2_000L + point)
			} else nativePoint(member, 2_000)
		}
		val points = export(recording("logical:many"))
		assertEquals(LocationFileExporter.PAGE_SIZE * 2 + 1, points.size)
		assertEquals(points.size, points.map { it.pointId }.distinct().size)
		assertEquals(2_000L + LocationFileExporter.PAGE_SIZE, points.last().measurement.timeMs)
	}

	@Test fun `missing and orphan physical membership stop native export rather than selecting by dates`(): Unit = runBlocking {
		logical("missing", 1_000, 10_000)
		val member = run("missing", "run", 1_000, 10_000)
		nativePoint(member, 2_000)
		database.sessionSegmentDao().deleteById(member.id)
		point(2_000)
		val output = ByteArrayOutputStream()
		assertIs<ExportResult.Error>(write(recording("logical:missing"), output))
		assertEquals(0, output.size())
		logical("orphan", 1_000, 10_000)
		val valid = run("orphan", "valid", 1_000, 10_000)
		nativePoint(valid, 2_000)
		segment(1_000, 10_000, "orphan", "absent-run")
		assertIs<ExportResult.Error>(write(recording("logical:orphan"), output))
		assertEquals(0, output.size())
	}

	@Test fun `erased native run is omitted without erasing its independent sibling`(): Unit = runBlocking {
		logical("chosen", 1_000, 20_000)
		nativePoint(run("chosen", "erased", 1_000, 10_000), 2_000)
		nativePoint(run("chosen", "retained", 10_000, 20_000), 11_000)
		database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createLogicalServiceRun(
			1, SourceBrokerPurpose.SESSION_CAPTURE, "chosen", "erased", 1, 0, 20_000))
		assertEquals(listOf(11_000L), export(recording("logical:chosen")).map { it.measurement.timeMs })
	}

	@Test fun `original open bounds cannot be made exportable by editing displayed dates`(): Unit = runBlocking {
		val original = logical("open", 1_000, 10_000)
		val member = run("open", "run", 1_000, 10_000)
		nativePoint(member, 2_000)
		database.sourceSessionDao().updateSession(original.copy(cutoffAtMs = null))
		annotation("display", 1_000, 10_000, "logical:open", revision = 1)
		val output = ByteArrayOutputStream()
		assertIs<ExportResult.Error>(write(recording("logical:open", 1), output))
		assertEquals(0, output.size())
	}

	@Test fun `original epoch change rejects stale selection before destination copy`(): Unit = runBlocking {
		annotation("retro", 1_000, 10_000); point(2_000)
		val selected = annotationRequest("retro")
		database.sourceEvidenceStateDao().updateLifecycle(1, null, 10_000)
		val output = ByteArrayOutputStream()
		assertIs<ExportResult.Error>(write(selected, output))
		assertEquals(0, output.size())
		assertSpoolEmpty()
	}

	@Test fun `unrelated saved Steps and radio revision changes before export preserve closed Location selection`(): Unit = runBlocking {
		logical("chosen", 1_000, 10_000)
		nativePoint(run("chosen", "run", 1_000, 10_000), 2_000)
		val selected = recording("logical:chosen")
		val revision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		saveUnrelatedStepsAndWifi()
		assertTrue(requireNotNull(database.sourceEvidenceStateDao().get()).revision > revision)
		assertEquals(listOf(2_000L), export(selected).map { it.measurement.timeMs })
		assertEquals(12, database.stepIntervalDao().getAllBetween(0, 20_000).single().stepCount)
		assertEquals("another source", database.wifiObservationDao().getForBssid("00:11:22:33:44:55", 0, 20_000).single().ssid)
	}

	@Test fun `unrelated Steps and radio ingress during destination copy does not invalidate selected Location`(): Unit = runBlocking {
		logical("chosen", 1_000, 10_000)
		val original = nativePoint(run("chosen", "run", 1_000, 10_000), 2_000)
		val selected = recording("logical:chosen")
		val revision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		val destination = onFirstWrite { runBlocking { saveUnrelatedStepsAndWifi() } }
		assertIs<ExportResult.Success>(write(selected, destination))
		assertTrue(requireNotNull(database.sourceEvidenceStateDao().get()).revision > revision)
		val exported = mutableListOf<LocationFilePointV1>()
		LocationFileJsonV1().read(ByteArrayInputStream(destination.toByteArray())) { exported += it }
		assertEquals(listOf(original.timeMs), exported.map { it.measurement.timeMs })
		assertEquals(LocationFileOrigin.SESSION, exported.single().origin)
		assertSpoolEmpty()
	}

	@Test fun `correction is exported losslessly and changing data during destination copy invalidates success`(): Unit = runBlocking {
		annotation("retro", 1_000, 10_000)
		val original = point(2_000)
		database.withTransaction {
			database.locationSampleDao().update(original.copy(lonE7 = 170_000_000, rawPlatformSpeedMps = 4.5f))
			database.sourceEvidenceStateDao().incrementRevision(3_000)
		}
		val selected = annotationRequest("retro")
		val corrected = export(selected).single()
		assertEquals(170_000_000, corrected.measurement.lonE7)
		assertEquals(4.5f, corrected.measurement.rawSpeedMps)
		val destination = onFirstWrite {
			// This Room mutation can finish only because the export snapshot is closed before copying.
			runBlocking { database.withTransaction {
				database.locationSampleDao().update(original.copy(lonE7 = 180_000_000))
			} }
		}
		assertIs<ExportResult.Error>(write(selected, destination))
		assertTrue(destination.size() > 0) // outer transfer must discard the invalidated destination
		assertSpoolEmpty()
	}

	@Test fun `removing exact archive membership during copy fails instead of borrowing a coincident point`(): Unit = runBlocking {
		val first = point(2_000)
		val second = point(3_000)
		val selected = archive('a', listOf(first, second))
		annotation("import", 1_000, 10_000, selection = listOf(selected))
		point(3_000) // remains outside the exact archive even after the original member disappears
		val destination = onFirstWrite { runBlocking { database.withTransaction {
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM location_file_archive_point WHERE archive_identity = ? AND ordinal = 1",
				arrayOf(selected.archiveIdentity))
		} } }
		assertIs<ExportResult.Error>(write(annotationRequest("import"), destination))
		assertTrue(destination.size() > 0)
		assertSpoolEmpty()
	}

	@Test fun `adding a selected point background origin during copy invalidates the staged typed contents`(): Unit = runBlocking {
		logical("chosen", 1_000, 10_000)
		val saved = nativePoint(run("chosen", "run", 1_000, 10_000), 2_000)
		val destination = onFirstWrite { runBlocking { addBackgroundOrigin(saved.id) } }
		assertIs<ExportResult.Error>(write(recording("logical:chosen"), destination))
		assertEquals(LocationFileOrigin.BOTH, export(recording("logical:chosen")).single().origin)
		assertSpoolEmpty()
	}

	@Test fun `read-only verification rejects a missing staged point identity without recreating it`(): Unit = runBlocking {
		annotation("retro", 1_000, 10_000)
		val saved = point(2_000)
		val destination = onFirstWrite { runBlocking { database.withTransaction {
			database.openHelper.writableDatabase.execSQL("DELETE FROM location_file_identity WHERE sample_id = ?",
				arrayOf(saved.id))
		} } }
		assertIs<ExportResult.Error>(write(annotationRequest("retro"), destination))
		assertNull(database.locationFileIdentityDao().bySampleId(saved.id))
		assertSpoolEmpty()
	}

	@Test fun `epoch change during destination copy prevents successful handoff`(): Unit = runBlocking {
		annotation("retro", 1_000, 10_000); point(2_000)
		val request = annotationRequest("retro")
		val destination = onFirstWrite { runBlocking { database.sourceEvidenceStateDao().updateLifecycle(1, null, 10_000) } }
		assertIs<ExportResult.Error>(write(request, destination))
		assertSpoolEmpty()
	}

	@Test fun `organization edits during destination copy invalidate handoff`(): Unit = runBlocking {
		annotation("retro", 1_000, 10_000); point(2_000)
		val destination = onFirstWrite { runBlocking {
			val row = requireNotNull(database.organizationAnnotationDao().byId("retro"))
			database.organizationAnnotationDao().update(row.copy(revision = row.revision + 1, name = "edited"))
		} }
		assertIs<ExportResult.Error>(write(annotationRequest("retro"), destination))
		assertSpoolEmpty()
	}

	@Test fun `cancellation during post-copy content verification propagates and removes the private spool`(): Unit = runBlocking {
		annotation("retro", 1_000, 10_000); point(2_000)
		var copyingFinished = false
		var checksAfterCopy = 0
		val destination = onFirstWrite { copyingFinished = true }
		assertFailsWith<CancellationException> {
			exporter.exportSession(context, annotationRequest("retro"), destination, database) {
				if (copyingFinished && ++checksAfterCopy == 3) throw CancellationException("cancel verification")
			}
		}
		assertEquals(3, checksAfterCopy)
		assertTrue(destination.size() > 0)
		assertSpoolEmpty()
	}

	@Test fun `cancellation during staging or destination copy propagates and removes only the private spool`(): Unit = runBlocking {
		annotation("retro", 1_000, 10_000); point(2_000)
		val request = annotationRequest("retro")
		val destination = ByteArrayOutputStream()
		var checks = 0
		assertFailsWith<CancellationException> {
			exporter.exportSession(context, request, destination, database) {
				if (++checks == 3) throw CancellationException("cancel staging")
			}
		}
		assertEquals(0, destination.size())
		assertSpoolEmpty()
		assertFailsWith<CancellationException> {
			write(request, object : OutputStream() {
				override fun write(value: Int) = throw CancellationException("cancel copy")
				override fun write(bytes: ByteArray, offset: Int, length: Int) = throw CancellationException("cancel copy")
			})
		}
		assertSpoolEmpty()
	}

	private suspend fun recording(key: String, revision: Long = 0) = request(OrganizationSessionId.Recording(key), revision)
	private suspend fun annotationRequest(id: String) = request(OrganizationSessionId.Annotation(id), 0)
	private suspend fun request(id: OrganizationSessionId, revision: Long): SessionLocationFileRequest {
		val state = requireNotNull(database.sourceEvidenceStateDao().get())
		return SessionLocationFileRequest(id, revision, state.collectedDataEpoch)
	}
	private suspend fun write(request: SessionLocationFileRequest, output: OutputStream) =
		exporter.exportSession(context, request, output, database, {})
	private suspend fun export(request: SessionLocationFileRequest): List<LocationFilePointV1> {
		val output = ByteArrayOutputStream()
		assertIs<ExportResult.Success>(write(request, output))
		val points = mutableListOf<LocationFilePointV1>()
		LocationFileJsonV1().read(ByteArrayInputStream(output.toByteArray())) { points += it }
		return points
	}
	private suspend fun logical(id: String, start: Long, end: Long): LogicalTrackingSessionEntity {
		val session = LogicalTrackingSessionEntity(id, "FINALIZED", 1, 1, 1, "USER", "boot",
			start, start * 1_000_000, end, end * 1_000_000, end, null, null)
		database.sourceSessionDao().insertSession(session)
		return session
	}
	private suspend fun run(logical: String, id: String, start: Long, end: Long, legacy: Boolean = false): SessionSegment {
		val segment = segment(start, end, logical, id)
		database.sourceSessionDao().insertServiceRun(SourceServiceRunEntity(id, logical, "FINALIZED", 1, 1, 0,
			start, start * 1_000_000, end, "STOPPED", sessionSegmentId = if (legacy) null else segment.id,
			presentationAcknowledgement = if (legacy) SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
				else SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = if (legacy) null else end))
		return segment
	}
	private suspend fun segment(start: Long, end: Long, logical: String? = null, run: String? = null): SessionSegment {
		val value = SessionSegment(startTimeMs = start, endTimeMs = end, distanceM = 0f, steps = null,
			primaryActivity = null, activityConfidence = null, sampleCount = 1, source = SegmentSource.USER_CREATED,
			inferenceVersion = null, createdAt = end, logicalTrackingId = logical, serviceRunId = run)
		return value.copy(id = database.sessionSegmentDao().insert(value))
	}
	private suspend fun annotation(id: String, start: Long, end: Long, adapter: String? = null, revision: Long = 0,
		selection: List<OrganizationSourceSelector>? = null) {
		database.organizationAnnotationDao().insert(OrganizationAnnotationEntity(id, revision, end, end, adapter,
			start, end, "UTC", id, null, false, selection?.let(OrganizationSourceSelectionCodec::encode)))
	}
	private suspend fun point(time: Long): LocationSample {
		val value = LocationSample(timeMs = time, elapsedRealtimeNanos = time * 1_000_000, latE7 = 500_000_000,
			lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null, hAccM = 15f, vAccM = null,
			speedMps = null, speedAccuracyMps = null, provider = "saved", quality = SampleQuality.MEDIUM,
			motionState = null, policy = null, bucketId = null, createdAt = time, clockDomainId = "boot")
		return value.copy(id = database.locationSampleDao().insert(value))
	}
	private suspend fun nativePoint(member: SessionSegment, time: Long): LocationSample {
		val point = point(time)
		val run = requireNotNull(member.serviceRunId)
		database.locationPurposeDao().insertReceiptIfAbsent(LocationAcceptedReceiptEntity(point.id,
			"event:${point.id}", "signal:${point.id}", point.id, "a".repeat(64), LocationAcceptedReceiptIntegrity.canonicalChecksum(point),
			run, 1, point.id, "delivery:${point.id}", 0, 1, "writer", 1, 1, "physical", 1, "authorized", "boot", "boot",
			point.elapsedRealtimeNanos, point.elapsedRealtimeNanos, time, time, 0, 0))
		database.locationPurposeDao().insertPurposeIfAbsent(LocationSamplePurposeAuthorityEntity(point.id,
			SourceBrokerPurpose.SESSION_CAPTURE, run, 1, 0, 0, Long.MAX_VALUE, member.logicalTrackingId,
			run, member.id, 1, 1, null, null, null, 0, "UTC"))
		return point
	}
	private suspend fun addBackgroundOrigin(sampleId: Long) {
		val capture = database.locationPurposeDao().contextPurposes(listOf(sampleId), 2).single()
		database.locationPurposeDao().insertPurposeIfAbsent(capture.copy(
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, authorityId = "background",
			logicalTrackingId = null, serviceRunId = null, sessionSegmentId = null,
			sessionManifestRevision = null, lifecycleLeaseGeneration = null, ambientIntentId = "ambient",
			retentionPolicyId = "retention", retentionApprovalRevision = 1, zoneId = null,
		))
	}
	private suspend fun saveUnrelatedStepsAndWifi() {
		database.withTransaction {
			database.stepIntervalDao().insert(StepInterval(startTimeMs = 1_000, endTimeMs = 3_000,
				stepCount = 12, sensorValueStart = 100, sensorValueEnd = 112, sensorReset = false,
				createdAt = 3_000, sourceSignalId = "unrelated-steps"))
			database.sourceEvidenceStateDao().incrementRevision(3_000)
			database.wifiObservationDao().insert(WifiObservation(timeMs = 2_000,
				bssid = "00:11:22:33:44:55", ssid = "another source", capabilities = "[WPA2]",
				frequency = 5180, level = -45, latE7 = null, lonE7 = null,
				provenance = CoordinateProvenance.UNKNOWN, createdAt = 3_000,
				sourceSignalId = "unrelated-wifi", sourceItemIndex = 0))
			database.sourceEvidenceStateDao().incrementRevision(3_000)
		}
	}
	private suspend fun archive(key: Char, points: List<LocationSample>): OrganizationSourceSelector.LocationFile {
		val dao = database.locationFileIdentityDao()
		val identity = key.toString().repeat(64)
		dao.recordArchive(LocationFileArchiveEntity(identity, LocationFileArchiveEntity.FORMAT,
			LocationFileArchiveEntity.VERSION, 0, points.size))
		val members = points.mapIndexed { ordinal, sample ->
			val portable = dao.bySampleId(sample.id)?.portableId ?: "point:${sample.id}".also { id ->
				dao.insertIfAbsent(LocationFileIdentityEntity(id, sample.id, true, "BACKGROUND"))
			}
			LocationFileArchivePointEntity(identity, ordinal, portable, sample.id)
		}
		dao.recordArchivePoints(members)
		return OrganizationSourceSelector.LocationFile(identity)
	}
	private fun onFirstWrite(action: () -> Unit) = object : ByteArrayOutputStream() {
		private var first = true
		override fun write(bytes: ByteArray, offset: Int, length: Int) {
			if (first) { first = false; action() }
			super.write(bytes, offset, length)
		}
	}
	private fun assertSpoolEmpty() {
		assertTrue(File(context.cacheDir, "portable-export-spool").listFiles().orEmpty().none {
			it.name.startsWith("tracker-portable-export-spool-v1-portable-location-v1-")
		})
	}
}
