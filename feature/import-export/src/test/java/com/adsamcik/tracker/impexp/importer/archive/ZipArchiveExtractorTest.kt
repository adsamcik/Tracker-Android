package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.shared.base.extension.openInputStream
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
		val output = ByteArrayOutputStream()
		ZipOutputStream(output).use { zip ->
			entries.forEach { (name, data) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(data)
				zip.closeEntry()
			}
		}
		return output.toByteArray()
	}

	private fun mockFile(
		bytes: ByteArray?,
		isDirectory: Boolean = false,
		declaredLength: Long = bytes?.size?.toLong() ?: 0L,
	): DocumentFile {
		val file = mockk<DocumentFile> {
			every { this@mockk.isDirectory } returns isDirectory
			every { length() } returns declaredLength
		}
		every { file.openInputStream(mockContext) } answers {
			bytes?.let(::ByteArrayInputStream)
		}
		return file
	}

	private suspend fun extractAll(
		extractor: ZipArchiveExtractor,
		file: DocumentFile,
	): List<Pair<String, ByteArray>> = buildList {
		extractor.extract(
			context = mockContext,
			file = file,
			shouldExtract = { true },
			consume = { stream -> add(stream.fileName to stream.readBytes()) },
		) shouldBe true
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
		private val onClose: () -> Unit,
	) : FilterInputStream(delegate) {
		private var closed = false

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
		fun `throws IllegalArgumentException when file is a directory`() = runTest {
			assertFailsWith<IllegalArgumentException> {
				extractor.extract(mockContext, mockFile(null, isDirectory = true), { true }, {})
			}
		}

		@Test
		fun `returns false when openInputStream returns null`() = runTest {
			extractor.extract(mockContext, mockFile(null), { true }, {}) shouldBe false
		}

		@Test
		fun `rejects source whose declared compressed size exceeds cap`() = runTest {
			val file = mockFile(
				bytes = byteArrayOf(),
				declaredLength = ZipArchiveExtractor.MAX_COMPRESSED_INPUT_BYTES + 1,
			)
			assertFailsWith<IOException> {
				extractor.extract(mockContext, file, { true }, {})
			}
		}
	}

	@Nested
	@DisplayName("Extraction")
	inner class Extraction {
		@Test
		fun `entry metadata and content are delivered to consumer`() = runTest {
			val entries = extractAll(
				extractor,
				mockFile(buildZipBytes(listOf("track.gpx" to "<gpx/>".toByteArray()))),
			)
			entries.single().first shouldBe "track.gpx"
			entries.single().second shouldBe "<gpx/>".toByteArray()
		}

		@Test
		fun `archive source closes after extraction`() = runTest {
			val zipBytes = buildZipBytes(listOf("export.json" to "{}".toByteArray()))
			val archiveStream = CloseTrackingInputStream(ByteArrayInputStream(zipBytes))
			val file = mockk<DocumentFile> {
				every { isDirectory } returns false
				every { length() } returns zipBytes.size.toLong()
			}
			every { file.openInputStream(mockContext) } returns archiveStream

			extractor.extract(mockContext, file, { true }, { it.readBytes() }) shouldBe true
			archiveStream.closed shouldBe true
		}

		@Test
		fun `temp file is deleted before next entry is materialized`() = runTest {
			var created = 0
			val lazyExtractor = ZipArchiveExtractor(
				tempFileFactory = { directory ->
					if (created > 0) cachedFiles().shouldBeEmpty()
					created++
					File.createTempFile("zip-entry-", ".tmp", directory)
				},
			)
			val zip = buildZipBytes(
				listOf(
					"one.gpx" to "one".toByteArray(),
					"two.gpx" to "two".toByteArray(),
					"three.gpx" to "three".toByteArray(),
				)
			)
			val consumed = mutableListOf<String>()

			lazyExtractor.extract(
				mockContext,
				mockFile(zip),
				shouldExtract = { true },
				consume = { stream ->
					cachedFiles().size shouldBe 1
					consumed += stream.readBytes().decodeToString()
				},
			) shouldBe true

			consumed shouldContainExactly listOf("one", "two", "three")
			created shouldBe 3
			cachedFiles().shouldBeEmpty()
		}

		@Test
		fun `many entries open temp input streams one at a time`() = runTest {
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

			val extracted = extractAll(countingExtractor, mockFile(buildZipBytes(entries)))

			extracted.map { it.second.decodeToString() } shouldContainExactly
				entries.map { it.second.decodeToString() }
			openedStreams shouldBe entries.size
			maxOpenStreams shouldBe 1
			currentOpenStreams shouldBe 0
			cachedFiles().shouldBeEmpty()
		}

		@Test
		fun `materialized temp file is deleted when later extraction fails`() = runTest {
			var createdTempFiles = 0
			val failingExtractor = ZipArchiveExtractor(
				tempFileFactory = { importCacheDir ->
					createdTempFiles++
					if (createdTempFiles == 2) throw IOException("Simulated temp file failure")
					File.createTempFile("zip-entry-", ".tmp", importCacheDir)
				}
			)
			val zip = buildZipBytes(
				listOf(
					"first.gpx" to "first".toByteArray(),
					"second.gpx" to "second".toByteArray(),
				)
			)

			assertFailsWith<IOException> {
				failingExtractor.extract(mockContext, mockFile(zip), { true }, { it.readBytes() })
			}
			cachedFiles().shouldBeEmpty()
		}

		@Test
		fun `empty and directory-only archives consume no entries`() = runTest {
			extractAll(extractor, mockFile(buildZipBytes(emptyList()))).shouldBeEmpty()

			val output = ByteArrayOutputStream()
			ZipOutputStream(output).use { zip ->
				zip.putNextEntry(ZipEntry("subdir/"))
				zip.closeEntry()
			}
			extractAll(extractor, mockFile(output.toByteArray())).shouldBeEmpty()
		}

		@Test
		fun `unsafe traversal entries are skipped`() = runTest {
			val entries = extractAll(
				extractor,
				mockFile(
					buildZipBytes(
						listOf(
							"../evil.gpx" to "bad".toByteArray(),
							"tracks/safe.gpx" to "good".toByteArray(),
						)
					)
				),
			)
			entries.map { it.first } shouldContainExactly listOf("tracks/safe.gpx")
		}
	}

	@Nested
	@DisplayName("Merge-import classification")
	inner class MergeImportClassification {
		@Test
		fun `Tracker database backup is recognized even when manifest follows database entries`() {
			val archive = buildZipBytes(
				listOf(
					"databases/main_database_v27" to "SQLite database bytes".toByteArray(),
					"manifest.json" to (
						"""{"format":"tracker-database-backup","version":1,"databases":[]}"""
					).toByteArray(),
				)
			)

			extractor.classifyForMergeImport(mockContext, mockFile(archive)) shouldBe
				ZipArchiveClassification.TRACKER_DATABASE_BACKUP
		}

		@Test
		fun `ordinary portable archive remains eligible for merge import`() {
			val archive = buildZipBytes(
				listOf(
					"track.gpx" to "<gpx/>".toByteArray(),
					"export.json" to "{}".toByteArray(),
				)
			)

			extractor.classifyForMergeImport(mockContext, mockFile(archive)) shouldBe
				ZipArchiveClassification.GENERAL_IMPORT
		}
	}

	@Nested
	@DisplayName("Resource limits")
	inner class ResourceLimits {
		@Test
		fun `archive exceeding maximum entry count is rejected`() = runTest {
			val entries = (0..ZipArchiveExtractor.MAX_ENTRY_COUNT).map { index ->
				"entry-$index.json" to byteArrayOf()
			}
			val file = mockFile(buildZipBytes(entries))

			val failure = assertFailsWith<IOException> {
				extractor.extract(mockContext, file, { true }, { it.readBytes() })
			}

			failure.message.orEmpty().contains("entry count limit") shouldBe true
			cachedFiles().shouldBeEmpty()
		}

		@Test
		fun `entry exceeding maximum compression ratio is rejected`() = runTest {
			val highlyCompressible = ByteArray(4 * 1024 * 1024) { 'A'.code.toByte() }
			val file = mockFile(buildZipBytes(listOf("bomb.json" to highlyCompressible)))

			val failure = assertFailsWith<IOException> {
				extractor.extract(mockContext, file, { true }, { it.readBytes() })
			}

			failure.message.orEmpty().contains("compression ratio") shouldBe true
			cachedFiles().shouldBeEmpty()
		}
	}
}
