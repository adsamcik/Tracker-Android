package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.shared.base.extension.openInputStream
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@DisplayName("ZipArchiveExtractor")
class ZipArchiveExtractorTest {

	private val extractor = ZipArchiveExtractor()
	private val mockContext = mockk<Context>()
	private lateinit var cacheDir: File

	@BeforeEach
	fun setUp() {
		mockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
		cacheDir = File("build/test-zip-cache-${System.nanoTime()}").apply { mkdirs() }
		every { mockContext.cacheDir } returns cacheDir
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
		cacheDir.deleteRecursively()
	}

	private fun buildZipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
		val baos = ByteArrayOutputStream()
		ZipOutputStream(baos).use { zos ->
			entries.forEach { (name, data) ->
				zos.putNextEntry(ZipEntry(name))
				zos.write(data)
				zos.closeEntry()
			}
		}
		return baos.toByteArray()
	}

	private fun mockFile(
		stream: java.io.InputStream?,
		isDirectory: Boolean = false
	): DocumentFile {
		val file = mockk<DocumentFile> {
			every { this@mockk.isDirectory } returns isDirectory
		}
		every { file.openInputStream(mockContext) } returns stream
		return file
	}

	@Nested
	@DisplayName("Properties")
	inner class Properties {

		@Test
		fun `supportedExtensions contains only zip`() {
			extractor.supportedExtensions shouldContainExactly listOf("zip")
		}
	}

	@Nested
	@DisplayName("Preconditions")
	inner class Preconditions {

		@Test
		fun `throws IllegalArgumentException when file is a directory`() {
			val file = mockFile(stream = null, isDirectory = true)
			assertThrows<IllegalArgumentException> {
				extractor.extract(mockContext, file)
			}
		}
	}

	@Nested
	@DisplayName("Null stream handling")
	inner class NullStream {

		@Test
		fun `returns null when openInputStream returns null`() {
			val file = mockFile(stream = null)
			extractor.extract(mockContext, file).shouldBeNull()
		}
	}

	@Nested
	@DisplayName("Extraction")
	inner class Extraction {

		@Test
		fun `returns non-null sequence for valid zip with one entry`() {
			val zipBytes = buildZipBytes(listOf("data.gpx" to "content".toByteArray()))
			val file = mockFile(ByteArrayInputStream(zipBytes))

			extractor.extract(mockContext, file).shouldNotBeNull()
		}

		@Test
		fun `first entry has correct filename`() {
			val zipBytes = buildZipBytes(listOf("track.gpx" to "<gpx/>".toByteArray()))
			val file = mockFile(ByteArrayInputStream(zipBytes))

			val result = extractor.extract(mockContext, file)
			result.shouldNotBeNull()
			result.first().fileName shouldBe "track.gpx"
		}

		@Test
		fun `first entry extension is parsed correctly`() {
			val zipBytes = buildZipBytes(listOf("export.json" to "{}".toByteArray()))
			val file = mockFile(ByteArrayInputStream(zipBytes))

			val result = extractor.extract(mockContext, file)
			result.shouldNotBeNull()
			result.first().extension shouldBe "json"
		}

		@Test
		fun `entry content remains readable after extractor returns`() {
			val zipBytes = buildZipBytes(listOf("export.json" to """{"locations":[]}""".toByteArray()))
			val file = mockFile(ByteArrayInputStream(zipBytes))

			val result = extractor.extract(mockContext, file)
			result.shouldNotBeNull()
			val text = result.first().bufferedReader().use { it.readText() }
			text shouldBe """{"locations":[]}"""
		}

		@Test
		fun `empty zip returns non-null sequence with no elements`() {
			val zipBytes = buildZipBytes(emptyList())
			val file = mockFile(ByteArrayInputStream(zipBytes))

			val result = extractor.extract(mockContext, file)
			result.shouldNotBeNull()
			result.toList().shouldBeEmpty()
		}

		@Test
		fun `directory-only zip returns non-null empty sequence`() {
			// Zip containing only a directory entry — no regular files
			val baos = ByteArrayOutputStream()
			ZipOutputStream(baos).use { zos ->
				zos.putNextEntry(ZipEntry("subdir/"))
				zos.closeEntry()
			}
			val file = mockFile(ByteArrayInputStream(baos.toByteArray()))

			val result = extractor.extract(mockContext, file)
			result.shouldNotBeNull()
			result.toList().shouldBeEmpty()
		}

		@Test
		fun `unsafe traversal entries are skipped`() {
			val zipBytes = buildZipBytes(
				listOf(
					"../evil.gpx" to "bad".toByteArray(),
					"tracks/safe.gpx" to "good".toByteArray(),
				)
			)
			val file = mockFile(ByteArrayInputStream(zipBytes))

			val result = extractor.extract(mockContext, file)
			result.shouldNotBeNull()
			result.map { it.fileName }.toList() shouldContainExactly listOf("tracks/safe.gpx")
		}
	}
}
