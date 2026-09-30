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
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

	@Test
	fun `already cancelled export does not inspect its child or touch output`() = runTest {
		val cache = File("build/test-session-write-${System.nanoTime()}").apply { mkdirs() }
		try {
			val context = mockk<Context> { every { cacheDir } returns cache }
			val source = File(cache, "source.trackersteps").apply { writeText("source") }
			val path = "sources/0/steps.trackersteps"
			val details = manifest(path, source)
			var inspections = 0
			val child = object : File(source.path) {
				override fun isFile(): Boolean {
					inspections++
					return true
				}
			}
			val output = ByteArrayOutputStream()
			val attempt = async {
				currentCoroutineContext().cancel()
				SessionBundleArchiveWriter().write(context, details, mapOf(path to child), output)
			}
			assertFailsWith<CancellationException> { attempt.await() }
			inspections shouldBe 0
			output.size() shouldBe 0
			File(cache, "portable-export-spool").exists() shouldBe false
		} finally { cache.deleteRecursively() }
	}

	@Test
	fun `cancellation at archive source open stops before a later size error and deletes spool`() = runTest {
		val cache = File("build/test-session-write-${System.nanoTime()}").apply { mkdirs() }
		try {
			val context = mockk<Context> { every { cacheDir } returns cache }
			val source = File(cache, "source.trackersteps").apply { writeText("source") }
			val empty = File(cache, "empty").apply { writeText("") }
			val path = "sources/0/steps.trackersteps"
			val details = manifest(path, source)
			val output = ByteArrayOutputStream()
			var opens = 0
			var returned = false
			val attempt = async {
				val caller = currentCoroutineContext()
				val child = object : File(source.path) {
					override fun isFile(): Boolean = true
					override fun length(): Long = details.children.single().sizeBytes
					override fun getPath(): String {
						opens++
						// The first open validates the hash; the second begins ZIP copying.
						if (opens == 2) {
							caller.cancel()
							return empty.path
						}
						return source.path
					}
				}
				SessionBundleArchiveWriter().write(context, details, mapOf(path to child), output)
				returned = true
			}
			assertFailsWith<CancellationException> { attempt.await() }
			opens shouldBe 2
			returned shouldBe false
			output.size() shouldBe 0
			File(cache, "portable-export-spool").listFiles().orEmpty().size shouldBe 0
		} finally { cache.deleteRecursively() }
	}

	private fun manifest(path: String, source: File): SessionBundleManifestV1 =
		SessionBundleManifestV1("Walk", "Walking", 100, 200, "UTC", listOf("steps"),
			listOf(SessionBundleChildV1(path, "portable-steps-v1", 1, source.length(),
				MessageDigest.getInstance("SHA-256").digest(source.readBytes())
					.joinToString("") { "%02x".format(it.toInt() and 0xff) }, 100, 200, true)),
			emptyList())
}
