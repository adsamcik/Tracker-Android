package com.adsamcik.tracker.impexp.importer.archive

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.impexp.importer.ImportJobRunner
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.PermanentImportInputException
import com.adsamcik.tracker.impexp.importer.RoomImportReceiptStore
import com.adsamcik.tracker.impexp.importer.file.ImportTransactionMode
import com.adsamcik.tracker.impexp.importer.file.LocationFileImport
import com.adsamcik.tracker.impexp.importer.file.radioEntryKey
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.SessionBundleChildV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import com.adsamcik.tracker.impexp.portable.WifiSourceFileV2
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiReceiptEntity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiGap
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real staged typed bytes, generic job receipts and Room publication; authored, not executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionBundleMetadataRoomTest {
	private lateinit var context: Application
	private lateinit var db: AppDatabase
	private lateinit var runner: ImportJobRunner
	private val fixtures = mutableListOf<File>()

	@Before fun setup() = runTest {
		context = ApplicationProvider.getApplicationContext()
		db = AppDatabase.testDatabase(context)
		db.sourceEvidenceStateDao().ensure()
		runner = ImportJobRunner(RoomImportReceiptStore(db), verifyCollectedDataAccess = {
			if (db.sourceEvidenceStateDao().get()?.collectedDataEpoch != 0L) throw IOException("Recorded data was cleared")
		}, originalDataEpoch = 0L, nowMs = { 1_000L })
	}
	@After fun close() { db.close(); fixtures.forEach { it.delete() } }

	@Test fun `metadata only file is offered for import and its receipt prevents resurrection after removal`() = runTest {
		DataImport().supportedExtensions.contains(SessionBundleManifestCodecV1.EXTENSION) shouldBe true
		stage(emptyList()).use { bundle ->
			runner.start("empty", "holiday.trackersession", 100) shouldBe true
			receive(bundle, "empty").successCount shouldBe 1
			val row = db.organizationAnnotationDao().visible(null, null, 10).single()
			row.importedSourceSelection shouldBe "[]"
			row.importedActivityLabel shouldBe "Hike"
			db.organizationAnnotationDao().delete(row.localId!!) shouldBe 1
			var preparedAgain = false
			runner.importSessionBundle("empty", bundle,
				transactionModeForChild = { error("No children") }, importChild = { error("No children") },
				importMetadata = { error("A successful metadata receipt must not resurrect the session") },
				prepareMetadata = { preparedAgain = true })
			preparedAgain shouldBe false
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
		}
	}

	@Test fun `same Location bytes in different session files keep exact duplicate membership`() = runTest {
		val bytes = locationBytes("point-a")
		val definition = child(0, LocationFileJsonV1.FORMAT, 1, LocationFileJsonV1.EXTENSION, bytes)
		stage(listOf(definition), name = "First").use { first ->
			runner.start("first", "first.trackersession", 100)
			receive(first, "first").successCount shouldBe 2
		}
		// A separate saved point at precisely the same timestamp has no relationship to this bundle.
		val unrelated = locationBytes("unrelated")
		LocationFileImport().import(context, db, com.adsamcik.tracker.impexp.importer.FileImportStream(
			java.io.ByteArrayInputStream(unrelated), "other.trackerlocation")
			.withImportReceipt("outside", 1_000).withOriginalDataEpoch(0)).successCount shouldBe 1
		stage(listOf(definition), name = "Second").use { second ->
			runner.start("second", "second.trackersession", 100)
			val result = receive(second, "second")
			result.successCount shouldBe 1
			result.skippedCount shouldBe 1
			val rows = db.organizationAnnotationDao().visible(null, null, 10)
			rows.size shouldBe 2
			val memberships = rows.map { OrganizationSourceSelectionCodec.decode(it.importedSourceSelection!!) }
			memberships.distinct().size shouldBe 1
			val identity = (memberships.first().single() as OrganizationSourceSelector.LocationFile).archiveIdentity
			db.locationFileIdentityDao().archivePointPage(identity, -1, 10).map { it.portableId } shouldBe listOf("point-a")
			db.locationFileIdentityDao().archivePointCounts(identity, null).membershipCount shouldBe 1
		}
	}

	@Test fun `failed second child preserves first source and retry does not call it again`() = runTest {
		val first = child(0, LocationFileJsonV1.FORMAT, 1, LocationFileJsonV1.EXTENSION, locationBytes("first"))
		val second = child(1, LocationFileJsonV1.FORMAT, 1, LocationFileJsonV1.EXTENSION, locationBytes("second"))
		stage(listOf(first, second)).use { bundle ->
			runner.start("retry", "retry.trackersession", 100)
			var firstCalls = 0
			val failed = receive(bundle, "retry", childImport = { stream ->
				if (stream.fileName == second.first.path) ImportResult(failedCount = 1)
				else { firstCalls++; LocationFileImport().import(context, db, stream) }
			})
			failed.failedCount shouldBe 1
			db.locationFileIdentityDao().byPortableId("first")?.portableId shouldBe "first"
			db.locationFileIdentityDao().byPortableId("second") shouldBe null
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
			receive(bundle, "retry", childImport = { stream ->
				if (stream.fileName == first.first.path) firstCalls++
				LocationFileImport().import(context, db, stream)
			}).failedCount shouldBe 0
			firstCalls shouldBe 1
			OrganizationSourceSelectionCodec.decode(db.organizationAnnotationDao().visible(null, null, 10)
				.single().importedSourceSelection!!).size shouldBe 2
		}
	}

	@Test fun `temporary preparation read failure retries without reimporting the durable child`() = runTest {
		val definition = child(0, LocationFileJsonV1.FORMAT, 1, LocationFileJsonV1.EXTENSION, locationBytes("read-retry"))
		stage(listOf(definition)).use { bundle ->
			runner.start("read-retry", "retry.trackersession", 100)
			var failNextRead = false
			val original = bundle.children.single()
			val failingOnce = object : File(original.file.path) {
				override fun getPath(): String {
					if (failNextRead) {
						failNextRead = false
						throw IOException("Temporary staged-file read failure")
					}
					return super.getPath()
				}
			}
			val input = StagedSessionBundle(bundle.manifest, bundle.manifestBytes,
				listOf(original.copy(file = failingOnce)))
			var prepared: PreparedSessionBundleMetadata? = null
			var childCalls = 0
			val failed = assertFailsWith<IOException> {
				runner.importSessionBundle("read-retry", input, { ImportTransactionMode.IMPORTER_MANAGED },
					{ childCalls++; LocationFileImport().import(context, db, it) },
					{ SessionBundleMetadataImport.publish(db, "read-retry", 0, 1_000, checkNotNull(prepared)) },
					prepareMetadata = {
						failNextRead = true
						prepared = SessionBundleMetadataImport.prepare(context, input)
					})
			}
			(failed is PermanentImportInputException) shouldBe false
			db.importReceiptDao().getEntry("read-retry", original.receiptKey)?.status shouldBe ImportEntryReceiptEntity.STATUS_SUCCESS
			db.locationFileIdentityDao().byPortableId("read-retry")?.portableId shouldBe "read-retry"
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
			receive(input, "read-retry", childImport = { childCalls++; error("Durable child must be skipped") })
			childCalls shouldBe 1
			OrganizationSourceSelectionCodec.decode(db.organizationAnnotationDao().visible(null, null, 10)
				.single().importedSourceSelection!!).size shouldBe 1
		}
	}

	@Test fun `metadata storage failure rolls back annotation and metadata receipt while retaining source child`() = runTest {
		val child = child(0, LocationFileJsonV1.FORMAT, 1, LocationFileJsonV1.EXTENSION, locationBytes("point-a"))
		stage(listOf(child)).use { bundle ->
			runner.start("atomic", "atomic.trackersession", 100)
			db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_bundle BEFORE INSERT ON organization_annotation " +
				"BEGIN SELECT RAISE(ABORT, 'metadata write interrupted'); END")
			assertFailsWith<SQLiteException> { receive(bundle, "atomic") }
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
			db.importReceiptDao().getEntry("atomic", bundle.children.single().receiptKey)?.status shouldBe ImportEntryReceiptEntity.STATUS_SUCCESS
			db.importReceiptDao().getEntry("atomic", "session-bundle-metadata-v1")?.status shouldBe ImportEntryReceiptEntity.STATUS_FAILURE
			db.locationFileIdentityDao().byPortableId("point-a")?.portableId shouldBe "point-a"
			db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_bundle")
			receive(bundle, "atomic", childImport = { error("Durable child must not be imported again") }).failedCount shouldBe 0
			db.organizationAnnotationDao().visible(null, null, 10).size shouldBe 1
		}
	}

	@Test fun `skipped child with no saved source receipt cannot publish a partial session`() = runTest {
		val first = child(0, LocationFileJsonV1.FORMAT, 1, LocationFileJsonV1.EXTENSION, locationBytes("kept"))
		val blocked = child(1, LocationFileJsonV1.FORMAT, 1, LocationFileJsonV1.EXTENSION, locationBytes("blocked"))
		stage(listOf(first, blocked)).use { bundle ->
			runner.start("blocked", "blocked.trackersession", 100)
			val failure = assertFailsWith<PermanentImportInputException> {
				receive(bundle, "blocked", childImport = { stream ->
					if (stream.fileName == blocked.first.path) {
						// An importer can finish with a retained/deleted record skipped and no source receipt.
						ImportResult(skippedCount = 1, errors = listOf("Source data is no longer retained"))
					} else LocationFileImport().import(context, db, stream)
				})
			}
			failure.toImportResult().successCount shouldBe 1
			failure.toImportResult().skippedCount shouldBe 1
			failure.toImportResult().failedCount shouldBe 1
			db.locationFileIdentityDao().byPortableId("kept")?.portableId shouldBe "kept"
			db.locationFileIdentityDao().byPortableId("blocked") shouldBe null
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
			bundle.children.forEach {
				db.importReceiptDao().getEntry("blocked", it.receiptKey)?.status shouldBe ImportEntryReceiptEntity.STATUS_SUCCESS
			}
			db.importReceiptDao().getEntry("blocked", "session-bundle-metadata-v1")?.status shouldBe ImportEntryReceiptEntity.STATUS_FAILURE
			// Generic success for a skipped child cannot turn it into real source membership on retry.
			val retry = assertFailsWith<PermanentImportInputException> {
				receive(bundle, "blocked", childImport = { error("Completed children must retain their original receipts") })
			}
			retry.toImportResult().successCount shouldBe 1
			retry.toImportResult().skippedCount shouldBe 1
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
		}
	}

	@Test fun `every declared source claim requires its real saved receipt before metadata commits`() = runTest {
		val rawId = "a".repeat(64)
		val prefixedId = "sha256:$rawId"
		val claims = buildList<Pair<HistorySource, SessionBundleSourceClaim>> {
			add(HistorySource.LOCATION to SessionBundleSourceClaim.Location(rawId, 1))
			listOf(HistorySource.STEPS, HistorySource.ACTIVITY, HistorySource.PRESSURE,
				HistorySource.WIFI, HistorySource.CELL).forEach { source ->
				val id = if (source == HistorySource.STEPS || source == HistorySource.PRESSURE) prefixedId else rawId
				add(source to SessionBundleSourceClaim.Captured(source, id, id, "missing-source-receipt"))
			}
			listOf(HistorySource.WIFI, HistorySource.CELL).forEach { source ->
				add(source to SessionBundleSourceClaim.BackgroundRadio(source, rawId, rawId, "missing-source-receipt"))
			}
			add(HistorySource.STEPS to SessionBundleSourceClaim.BackgroundSteps(prefixedId, prefixedId,
				"missing-source-receipt", listOf(prefixedId to prefixedId)))
		}
		claims.forEachIndexed { index, (source, claim) ->
			stage(emptyList()).use { bundle ->
				val job = "missing-$index"
				runner.start(job, "$job.trackersession", 100)
				// Prepared claims isolate receipt resolution; the preceding staged test uses real typed bytes.
				val prepared = PreparedSessionBundleMetadata(bundle.manifest.copy(selectedSources = listOf(source.name.lowercase())),
					listOf(claim))
				assertFailsWith<PermanentImportInputException> {
					runner.importSessionBundle(job, bundle, { error("No children") }, { error("No children") },
						{ SessionBundleMetadataImport.publish(db, job, 0, 1_000, prepared) })
				}
				db.importReceiptDao().getEntry(job, "session-bundle-metadata-v1")?.status shouldBe ImportEntryReceiptEntity.STATUS_FAILURE
				db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
			}
		}
	}

	@Test fun `original epoch change before metadata prevents stale annotation publication`() = runTest {
		stage(emptyList()).use { bundle ->
			runner.start("cleared", "cleared.trackersession", 100)
			val prepared = SessionBundleMetadataImport.prepare(context, bundle)
			assertFailsWith<IOException> {
				runner.importSessionBundle("cleared", bundle, { ImportTransactionMode.IMPORTER_MANAGED },
					{ error("No children") },
					{ SessionBundleMetadataImport.publish(db, "cleared", 0, 1_000, prepared) },
					prepareMetadata = { db.openHelper.writableDatabase.execSQL("UPDATE source_evidence_state SET collected_data_epoch=1") })
			}
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
			db.importReceiptDao().getEntry("cleared", "session-bundle-metadata-v1") shouldBe null
		}
	}

	@Test fun `source version mismatch reports the retained child prefix and publishes no annotation`() = runTest {
		stage(listOf(child(0, LocationFileJsonV1.FORMAT, 2, LocationFileJsonV1.EXTENSION, locationBytes("point-a")))).use { bundle ->
			runner.start("version", "version.trackersession", 100)
			val failure = assertFailsWith<PermanentImportInputException> { receive(bundle, "version") }
			failure.toImportResult().successCount shouldBe 1
			failure.toImportResult().failedCount shouldBe 1
			db.locationFileIdentityDao().byPortableId("point-a")?.portableId shouldBe "point-a"
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
		}
	}

	@Test fun `cancelled Location preparation stops at a point before later invalid data`() = runTest {
		// The wrapper validates hashes, not the child's typed contents. A malformed later line
		// distinguishes prompt point-level cancellation from merely cancelling the final hash.
		val lines = locationBytes("point-a").toString(Charsets.UTF_8).lineSequence().take(2).joinToString("\n")
		val bytes = "$lines\ninvalid trailing record\n".toByteArray()
		stage(listOf(child(0, LocationFileJsonV1.FORMAT, 1, LocationFileJsonV1.EXTENSION, bytes))).use { bundle ->
			val preparationJob = Job()
			val original = bundle.children.single()
			val cancelOnOpen = object : File(original.file.path) {
				override fun getPath(): String {
					preparationJob.cancel()
					return super.getPath()
				}
			}
			val input = StagedSessionBundle(bundle.manifest, bundle.manifestBytes,
				listOf(original.copy(file = cancelOnOpen)))
			assertFailsWith<CancellationException> {
				withContext(preparationJob) { SessionBundleMetadataImport.prepare(context, input) }
			}
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
		}
	}

	@Test fun `expired annotation is skipped atomically and its completed receipt does not resurrect it`() = runTest {
		stage(emptyList()).use { bundle ->
			runner.start("expired", "expired.trackersession", 100)
			db.openHelper.writableDatabase.execSQL("UPDATE source_evidence_state SET retained_from_ms=200")
			receive(bundle, "expired").skippedCount shouldBe 1
			db.importReceiptDao().getEntry("expired", "session-bundle-metadata-v1")?.status shouldBe ImportEntryReceiptEntity.STATUS_SUCCESS
			db.openHelper.writableDatabase.execSQL("UPDATE source_evidence_state SET retained_from_ms=NULL")
			runner.importSessionBundle("expired", bundle, { error("No children") }, { error("No children") },
				{ error("The skipped metadata receipt must not re-create expired data") })
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
		}
	}

	@Test fun `actual Wi-Fi file uses its source archive receipt and a mismatched receipt cannot publish`() = runTest {
		val gap = AmbientWifiPortableIntegrity.createGap(AmbientWifiGap("a".repeat(64), AmbientWifiOrigin.LOCAL_DEVICE,
			0, 100, 200, "UTC", "provider unavailable"))
		val archive = AmbientWifiPortableIntegrity.createArchive("b".repeat(64), emptyList(), listOf(gap))
		val radio = WifiSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0, sessions = emptyList(),
			background = archive, backgroundChildren = emptyList())
		val bytes = ByteArrayOutputStream().also { RadioSourceFileJsonV2Codec().writeWifi(it, radio) }.toByteArray()
		stage(listOf(child(0, RadioSourceFileV2.WIFI_FORMAT, 2, RadioSourceFileV2.WIFI_EXTENSION, bytes))).use { bundle ->
			runner.start("wifi", "wifi.trackersession", 100)
			val key = radioEntryKey(bundle.children.single().receiptKey, archive.archiveId)
			val receipt = ImportedAmbientWifiReceiptEntity("wifi", key, "wifi", "c".repeat(64), archive.contentChecksum, 0, 1_000)
			val failure = assertFailsWith<PermanentImportInputException> {
				receive(bundle, "wifi", childImport = {
					db.ambientWifiFactDao().insertImportReceipt(receipt)
					ImportResult(successCount = 1)
				})
			}
			failure.toImportResult().successCount shouldBe 1
			db.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
			db.openHelper.writableDatabase.execSQL("UPDATE imported_ambient_wifi_receipt SET archive_id='${archive.archiveId}'")
			receive(bundle, "wifi", childImport = { error("Child receipt is already successful") }).failedCount shouldBe 0
			OrganizationSourceSelectionCodec.decode(db.organizationAnnotationDao().visible(null, null, 10)
				.single().importedSourceSelection!!) shouldBe listOf(OrganizationSourceSelector.BackgroundRadioArchive(HistorySource.WIFI, archive.archiveId))
		}
	}

	private suspend fun receive(bundle: StagedSessionBundle, jobId: String,
		childImport: suspend (com.adsamcik.tracker.impexp.importer.FileImportStream) -> ImportResult = {
			LocationFileImport().import(context, db, it)
		}): ImportResult {
		var prepared: PreparedSessionBundleMetadata? = null
		return runner.importSessionBundle(jobId, bundle, { ImportTransactionMode.IMPORTER_MANAGED }, childImport,
			{ SessionBundleMetadataImport.publish(db, jobId, 0, 1_000, checkNotNull(prepared)) },
			prepareMetadata = { prepared = SessionBundleMetadataImport.prepare(context, bundle) })
	}

	private suspend fun stage(children: List<Pair<SessionBundleChildV1, ByteArray>>, name: String = "Walk"): StagedSessionBundle {
		val sources = children.map { when (it.first.formatId) {
			LocationFileJsonV1.FORMAT -> "location"
			RadioSourceFileV2.WIFI_FORMAT -> "wifi"
			else -> error("Fixture format")
		} }.distinct()
		val manifest = SessionBundleManifestV1(name, "Hike", 100, 200, "UTC", sources, children.map { it.first }, emptyList())
		val file = File.createTempFile("bundle-fixture-", ".trackersession", context.cacheDir).also(fixtures::add)
		ZipOutputStream(file.outputStream()).use { zip ->
			(children.map { it.first.path to it.second } +
				(SessionBundleManifestCodecV1.MANIFEST_PATH to SessionBundleManifestCodecV1.encode(manifest))).forEach { (path, bytes) ->
				zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
			}
		}
		return SessionBundleStager().stage(context, DocumentFile.fromFile(file))
	}

	private fun child(index: Int, format: String, version: Int, extension: String, bytes: ByteArray) =
		SessionBundleChildV1("sources/$index/data.$extension", format, version, bytes.size.toLong(),
			MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) },
			100, 200, true) to bytes

	private fun locationBytes(id: String) = listOf(
		"""{"format":"tracker-location","version":1}""",
		"""{"type":"point","id":"$id","origin":"unknown","timeMs":100,"latE7":500000000,"lonE7":140000000,"provider":"gps","quality":"high","permissionPrecision":"FINE","isMock":false,"altitudeDatum":"unknown_legacy","altitudeSource":"unknown_legacy","altitudeConversionStatus":"unknown_legacy","rawGpsAltitudeDatum":"unknown_legacy"}""",
		"""{"type":"end","pointCount":1}""",
	).joinToString("\n", postfix = "\n").toByteArray()
}
