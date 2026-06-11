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
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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
		stream: InputStream?,
		isDirectory: Boolean = false
	): DocumentFile {
		val file = mockk<DocumentFile> {
			every { this@mockk.isDirectory } returns isDirectory
		}
		every { file.openInputStream(mockContext) } returns stream
		return file
	}

	private fun cachedFiles(): List<File> = cacheDir.walkTopDown().filter { it.isFile }.toList()

	private class CloseTrackingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
		var closed: Boolean = false
			private set

		override fun close() {
			closed = true
			super.close()
		}
	}

	private class CloseCountingInputStream(
			delegate: InputStream,
			private val onClose: () -> Unit
	) : FilterInputStream(delegate) {
		private var closed: Boolean = false

		override fun close() {
			if (closed) return
			closed = true
			try {
				super.close()
			} finally {
				onClose()
			}
		}
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
			val archiveStream = CloseTrackingInputStream(ByteArrayInputStream(zipBytes))
			val file = mockFile(archiveStream)

			val result = extractor.extract(mockContext, file)
			result.shouldNotBeNull()
			archiveStream.closed shouldBe true
			val text = result.first().bufferedReader().use { it.readText() }
			text shouldBe """{"locations":[]}"""
		}

		@Test
		fun `temp files are deleted when extracted stream closes`() {
			val content = "<gpx/>".toByteArray()
			val zipBytes = buildZipBytes(listOf("track.gpx" to content))
			val file = mockFile(ByteArrayInputStream(zipBytes))

			val result = extractor.extract(mockContext, file)
			result.shouldNotBeNull()
			val stream = result.single()

			try {
				cachedFiles().size shouldBe 1
				stream.readBytes() shouldBe content
			} finally {
				stream.close()
			}
			cachedFiles().shouldBeEmpty()
		}

		@Test
		fun `many entries open temp input streams lazily and one at a time`() {
			var openedStreams = 0
			var currentOpenStreams = 0
			var maxOpenStreams = 0
			val countingExtractor = ZipArchiveExtractor(
					tempInputStreamFactory = { tempFile ->
						openedStreams++
						currentOpenStreams++
						maxOpenStreams = maxOf(maxOpenStreams, currentOpenStreams)
						CloseCountingInputStream(tempFile.inputStream()) {
							currentOpenStreams--
						}
					}
			)
			val entries = (0 until 64).map { index ->
				"entry-$index.gpx" to "content-$index".toByteArray()
			}
			val zipBytes = buildZipBytes(entries)
			val file = mockFile(ByteArrayInputStream(zipBytes))

			val result = countingExtractor.extract(mockContext, file)
			result.shouldNotBeNull()
			val streams = result.toList()

			openedStreams shouldBe 0
			streams.size shouldBe entries.size
			streams.forEachIndexed { index, stream ->
				stream.use {
					it.readBytes() shouldBe entries[index].second
				}
				currentOpenStreams shouldBe 0
			}
			openedStreams shouldBe entries.size
			maxOpenStreams shouldBe 1
			cachedFiles().shouldBeEmpty()
		}

		@Test
		fun `materialized temp files are deleted when later entry extraction fails`() {
			var createdTempFiles = 0
			val failingExtractor = ZipArchiveExtractor(
					tempFileFactory = { importCacheDir ->
						createdTempFiles++
						if (createdTempFiles == 2) throw IOException("Simulated temp file failure")
						File.createTempFile("zip-entry-", ".tmp", importCacheDir)
					}
			)
			val zipBytes = buildZipBytes(
					listOf(
							"first.gpx" to "first".toByteArray(),
							"second.gpx" to "second".toByteArray(),
					)
			)
			val file = mockFile(ByteArrayInputStream(zipBytes))

			assertThrows<IOException> {
				failingExtractor.extract(mockContext, file)
			}
			cachedFiles().shouldBeEmpty()
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
