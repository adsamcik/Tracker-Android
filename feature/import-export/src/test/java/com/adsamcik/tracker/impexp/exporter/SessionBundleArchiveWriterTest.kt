package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.SessionBundleChildV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SessionBundleArchiveWriterTest {
	@Test
	fun `selected session format contains exactly declared typed data and its details`() = runTest {
		val cache = File("build/test-session-write-${System.nanoTime()}").apply { mkdirs() }
		try {
			val context = mockk<Context> { every { cacheDir } returns cache }
			val source = File(cache, "source.trackersteps").apply { writeText("source") }
			val path = "sources/0/steps.trackersteps"
			val manifest = manifest(path, source)
			val output = ByteArrayOutputStream()
			SessionBundleArchiveWriter().write(context, manifest, mapOf(path to source), output)
			val entries = mutableMapOf<String, ByteArray>()
			ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
				while (true) {
					val entry = zip.nextEntry ?: break
					entries[entry.name] = zip.readBytes()
					zip.closeEntry()
				}
			}
			entries.keys shouldBe setOf(path, SessionBundleManifestCodecV1.MANIFEST_PATH)
			entries.getValue(path).contentEquals(source.readBytes()) shouldBe true
			SessionBundleManifestCodecV1.decode(
				entries.getValue(SessionBundleManifestCodecV1.MANIFEST_PATH)) shouldBe manifest
		} finally { cache.deleteRecursively() }
	}

	@Test
	fun `changed child leaves chosen output untouched`() = runTest {
		val cache = File("build/test-session-write-${System.nanoTime()}").apply { mkdirs() }
		try {
			val context = mockk<Context> { every { cacheDir } returns cache }
			val source = File(cache, "source.trackersteps").apply { writeText("source") }
			val path = "sources/0/steps.trackersteps"
			val manifest = manifest(path, source)
			source.writeText("edited")
			val output = ByteArrayOutputStream()
			shouldThrow<InvalidSessionBundle> {
				SessionBundleArchiveWriter().write(context, manifest, mapOf(path to source), output)
			}
			output.size() shouldBe 0
		} finally { cache.deleteRecursively() }
	}

	@Test
	fun `highly repetitive source remains importable under ZIP ratio limit`() = runTest {
		val cache = File("build/test-session-write-${System.nanoTime()}").apply { mkdirs() }
		try {
			val context = mockk<Context> { every { cacheDir } returns cache }
			val source = File(cache, "source.trackersteps").apply {
				writeText("x".repeat(100_000))
			}
			val path = "sources/0/steps.trackersteps"
			val output = ByteArrayOutputStream()
			SessionBundleArchiveWriter().write(context, manifest(path, source),
				mapOf(path to source), output)
			ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
				val entry = zip.nextEntry
				entry.method shouldBe java.util.zip.ZipEntry.STORED
				entry.size shouldBe source.length()
			}
		} finally { cache.deleteRecursively() }
	}

	private fun manifest(path: String, source: File): SessionBundleManifestV1 =
		SessionBundleManifestV1("Walk", "Walking", 100, 200, "UTC", listOf("steps"),
			listOf(SessionBundleChildV1(path, "portable-steps-v1", 1, source.length(),
				MessageDigest.getInstance("SHA-256").digest(source.readBytes())
					.joinToString("") { "%02x".format(it.toInt() and 0xff) }, 100, 200, true)),
			emptyList())
}
