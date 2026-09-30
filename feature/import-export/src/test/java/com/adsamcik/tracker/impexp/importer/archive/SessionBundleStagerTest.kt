package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.CellSourceFileV2
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.SessionBundleChildV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import com.adsamcik.tracker.impexp.portable.WifiSourceFileV2
import com.adsamcik.tracker.impexp.exporter.SessionBundleArchiveWriter
import com.adsamcik.tracker.impexp.importer.computeImportJobId
import com.adsamcik.tracker.shared.base.extension.openInputStream
import com.adsamcik.tracker.stats.api.repository.AmbientCellGap
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiGap
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionBundleStagerTest {
	private val context = mockk<Context>()
	private lateinit var cache: File

	@Before
	fun setup() {
		mockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
		cache = File("build/test-session-bundle-${System.nanoTime()}").apply { mkdirs() }
		every { context.cacheDir } returns cache
	}

	@After
	fun cleanup() {
		unmockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
		cache.deleteRecursively()
	}

	@Test
	fun `cancelled copy removes its partial private file`() = runTest {
		val copyJob = Job()
		val source = mockk<DocumentFile>()
		val input = object : ByteArrayInputStream("first block".toByteArray()) {
			override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
				super.read(buffer, offset, length).also { copyJob.cancel() }
		}
		every { source.openInputStream(context) } returns input
		shouldThrow<CancellationException> {
			withContext(copyJob) { SessionBundleStager().stageSource(context, source) }
		}
		File(cache, SessionBundleStager.CACHE_DIRECTORY).listFiles().orEmpty().size shouldBe 0
	}

	@Test
	fun `failed temporary file cleanup is reported`() {
		val nonEmptyDirectory = File(cache, "blocked-copy").apply {
			mkdirs()
			File(this, "remaining").writeText("x")
		}
		shouldThrow<IOException> { StagedSessionSource(nonEmptyDirectory).close() }
		val manifest = SessionBundleManifestV1(null, null, 100, 200, "UTC", emptyList(),
			emptyList(), emptyList())
		val bundle = StagedSessionBundle(manifest, SessionBundleManifestCodecV1.encode(manifest),
			listOf(StagedSessionChild("child", "receipt", nonEmptyDirectory, 1L, "a")))
		shouldThrow<IOException> { bundle.close() }
	}

	@Test
	fun `picked session is copied once before hashing and importing`() = runTest {
		val manifest = SessionBundleManifestV1("Walk", null, 100, 200, "UTC", emptyList(),
			emptyList(), emptyList())
		val archive = zip(listOf(SessionBundleManifestCodecV1.MANIFEST_PATH to
			SessionBundleManifestCodecV1.encode(manifest)))
		val picked = file(archive)
		val stager = SessionBundleStager()
		stager.stageSource(context, picked).use { privateCopy ->
			computeImportJobId(context, privateCopy.document,
				SessionBundleManifestCodecV1.MAX_ARCHIVE_BYTES).isNotBlank() shouldBe true
			stager.stage(context, privateCopy.document).use { staged ->
				staged.manifest shouldBe manifest
			}
		}
		verify(exactly = 1) { picked.openInputStream(context) }
		SessionBundleStager.clearOwnedStaging(context)
		File(cache, SessionBundleStager.CACHE_DIRECTORY).exists() shouldBe false
	}

	@Test
	fun `all declared bytes are checked before a child can be imported`() = runTest {
		val payload = "typed source bytes".toByteArray()
		val path = "sources/0/steps.trackersteps"
		val child = SessionBundleChildV1(path, "portable-steps-v1", 1, payload.size.toLong(),
			payload.sha256(), 100, 200, true)
		val manifest = SessionBundleManifestV1("Walk", null, 100, 200, "UTC", listOf("steps"),
			listOf(child), emptyList())
		val archive = file(zip(listOf(
			path to payload,
			SessionBundleManifestCodecV1.MANIFEST_PATH to SessionBundleManifestCodecV1.encode(manifest),
		)))
		SessionBundleStager().stage(context, archive).use { staged ->
			staged.manifest shouldBe manifest
			staged.children.single().importStream().use { it.readBytes().contentEquals(payload) shouldBe true }
			staged.children.single().file.exists() shouldBe true
		}
		File(cache, SessionBundleStager.CACHE_DIRECTORY).listFiles().orEmpty().size shouldBe 0
	}

	@Test
	fun `registered Wi-Fi and Cell source files stage with their own formats and extensions`() = runTest {
		val codec = RadioSourceFileJsonV2Codec()
		val wifi = wifiFile()
		val cell = cellFile()
		val wifiBytes = ByteArrayOutputStream().also { codec.writeWifi(it, wifi) }.toByteArray()
		val cellBytes = ByteArrayOutputStream().also { codec.writeCell(it, cell) }.toByteArray()
		val wifiPath = "sources/0/wifi.${RadioSourceFileV2.WIFI_EXTENSION}"
		val cellPath = "sources/1/cell.${RadioSourceFileV2.CELL_EXTENSION}"
		val children = listOf(
			SessionBundleChildV1(wifiPath, RadioSourceFileV2.WIFI_FORMAT, RadioSourceFileV2.VERSION,
				wifiBytes.size.toLong(), wifiBytes.sha256(), 100, 200, true),
			SessionBundleChildV1(cellPath, RadioSourceFileV2.CELL_FORMAT, RadioSourceFileV2.VERSION,
				cellBytes.size.toLong(), cellBytes.sha256(), 100, 200, true),
		)
		val manifest = SessionBundleManifestV1("Radio observations", null, 100, 200, "UTC",
			listOf("wifi", "cell"), children, emptyList())
		val archive = file(zip(listOf(
			SessionBundleManifestCodecV1.MANIFEST_PATH to SessionBundleManifestCodecV1.encode(manifest),
			wifiPath to wifiBytes,
			cellPath to cellBytes,
		)))
		SessionBundleStager().stage(context, archive).use { staged ->
			staged.manifest shouldBe manifest
			staged.children.map { it.path } shouldBe listOf(wifiPath, cellPath)
			staged.children[0].importStream().use { codec.readWifi(it) shouldBe wifi }
			staged.children[1].importStream().use { codec.readCell(it) shouldBe cell }
		}
		File(cache, SessionBundleStager.CACHE_DIRECTORY).listFiles().orEmpty().size shouldBe 0
	}

	@Test
	fun `registered exact Location file stages with its current source format`() = runTest {
		val payload = listOf(
			"""{"format":"tracker-location","version":1}""",
			"""{"type":"point","id":"saved-point","origin":"unknown","timeMs":100,"latE7":500000000,"lonE7":140000000,"provider":"gps","quality":"high","permissionPrecision":"FINE","isMock":false,"altitudeDatum":"unknown_legacy","altitudeSource":"unknown_legacy","altitudeConversionStatus":"unknown_legacy","rawGpsAltitudeDatum":"unknown_legacy"}""",
			"""{"type":"end","pointCount":1}""",
		).joinToString("\n", postfix = "\n").toByteArray()
		val path = "sources/0/location.${LocationFileJsonV1.EXTENSION}"
		val child = SessionBundleChildV1(path, LocationFileJsonV1.FORMAT, LocationFileJsonV1.VERSION,
			payload.size.toLong(), payload.sha256(), 100, 200, false)
		val manifest = SessionBundleManifestV1(null, null, 100, 200, "UTC", listOf("location"),
			listOf(child), emptyList())
		SessionBundleStager().stage(context, file(zip(listOf(
			SessionBundleManifestCodecV1.MANIFEST_PATH to SessionBundleManifestCodecV1.encode(manifest),
			path to payload,
		)))).use { staged ->
			staged.manifest shouldBe manifest
			staged.children.single().importStream().use { input ->
				LocationFileJsonV1().read(input) { point -> point.pointId shouldBe "saved-point" } shouldBe 1
			}
		}
		File(cache, SessionBundleStager.CACHE_DIRECTORY).listFiles().orEmpty().size shouldBe 0
	}

	@Test
	fun `radio format cannot claim another source extension`() = runTest {
		val payload = ByteArrayOutputStream().also {
			RadioSourceFileJsonV2Codec().writeWifi(it, wifiFile())
		}.toByteArray()
		val path = "sources/0/wifi.${RadioSourceFileV2.CELL_EXTENSION}"
		val child = SessionBundleChildV1(path, RadioSourceFileV2.WIFI_FORMAT, RadioSourceFileV2.VERSION,
			payload.size.toLong(), payload.sha256(), 100, 200, true)
		val manifest = SessionBundleManifestV1(null, null, 100, 200, "UTC", listOf("wifi"),
			listOf(child), emptyList())
		shouldThrow<InvalidSessionBundle> {
			SessionBundleStager().stage(context, file(zip(listOf(
				SessionBundleManifestCodecV1.MANIFEST_PATH to SessionBundleManifestCodecV1.encode(manifest),
				path to payload,
			))))
		}
		File(cache, SessionBundleStager.CACHE_DIRECTORY).listFiles().orEmpty().size shouldBe 0
	}

	@Test
	fun `damaged child leaves no staged payload`() = runTest {
		val path = "sources/0/steps.trackersteps"
		val child = SessionBundleChildV1(path, "portable-steps-v1", 1, 3,
			"0".repeat(64), 100, 200, true)
		val manifest = SessionBundleManifestV1(null, null, 100, 200, "UTC", listOf("steps"),
			listOf(child), emptyList())
		shouldThrow<InvalidSessionBundle> {
			SessionBundleStager().stage(context, file(zip(listOf(
			SessionBundleManifestCodecV1.MANIFEST_PATH to SessionBundleManifestCodecV1.encode(manifest),
			path to "bad".toByteArray(),
		))))
		}
		File(cache, SessionBundleStager.CACHE_DIRECTORY).listFiles().orEmpty().size shouldBe 0
	}

	@Test
	fun `writer output with repetitive source passes the same bounded extractor`() = runTest {
		val path = "sources/0/steps.trackersteps"
		val source = File(cache, "large.trackersteps").apply { writeText("x".repeat(100_000)) }
		val child = SessionBundleChildV1(path, "portable-steps-v1", 1, source.length(),
			source.readBytes().sha256(), 100, 200, true)
		val manifest = SessionBundleManifestV1(null, null, 100, 200, "UTC", listOf("steps"),
			listOf(child), emptyList())
		val output = ByteArrayOutputStream()
		SessionBundleArchiveWriter().write(context, manifest, mapOf(path to source), output)
		SessionBundleStager().stage(context, file(output.toByteArray())).use { staged ->
			staged.manifest shouldBe manifest
			staged.children.single().sizeBytes shouldBe source.length()
		}
	}

	private fun file(bytes: ByteArray): DocumentFile {
		val file = mockk<DocumentFile> {
			every { isDirectory } returns false
			every { length() } returns bytes.size.toLong()
		}
		every { file.openInputStream(context) } answers { ByteArrayInputStream(bytes) }
		return file
	}

	private fun wifiFile(): WifiSourceFileV2 {
		val gap = AmbientWifiPortableIntegrity.createGap(AmbientWifiGap(
			identity = "a".repeat(64), origin = AmbientWifiOrigin.LOCAL_DEVICE,
			structuralEpochDay = 0L, startTimeMs = 100L, endTimeMs = 200L,
			storedZoneId = "UTC", reason = "provider unavailable"))
		return WifiSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0,
			sessions = emptyList(), background = AmbientWifiPortableIntegrity.createArchive(
				"b".repeat(64), emptyList(), listOf(gap)), backgroundChildren = emptyList())
	}

	private fun cellFile(): CellSourceFileV2 {
		val gap = AmbientCellPortableIntegrity.createGap(AmbientCellGap(
			identity = "c".repeat(64), origin = AmbientCellOrigin.LOCAL_DEVICE,
			structuralEpochDay = 0L, startTimeMs = 100L, endTimeMs = 200L,
			storedZoneId = "UTC", reason = "provider unavailable"))
		return CellSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
			includeSessionsAcrossDates = true, observationsOutsideDates = 0,
			sessions = emptyList(), background = AmbientCellPortableIntegrity.createArchive(
				"d".repeat(64), emptyList(), listOf(gap)), backgroundChildren = emptyList())
	}

	private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
		ZipOutputStream(out).use { zip -> entries.forEach { (path, bytes) ->
			zip.putNextEntry(ZipEntry(path))
			zip.write(bytes)
			zip.closeEntry()
		} }
	}.toByteArray()

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
