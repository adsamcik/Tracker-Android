package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.SessionBundleChildV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import com.adsamcik.tracker.shared.base.extension.openInputStream
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SessionBundleStagerTest {
	private val context = mockk<Context>()
	private lateinit var cache: File

	@BeforeEach
	fun setup() {
		mockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
		cache = File("build/test-session-bundle-${System.nanoTime()}").apply { mkdirs() }
		every { context.cacheDir } returns cache
	}

	@AfterEach
	fun cleanup() {
		unmockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
		cache.deleteRecursively()
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

	private fun file(bytes: ByteArray): DocumentFile {
		val file = mockk<DocumentFile> {
			every { isDirectory } returns false
			every { length() } returns bytes.size.toLong()
		}
		every { file.openInputStream(context) } answers { ByteArrayInputStream(bytes) }
		return file
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
