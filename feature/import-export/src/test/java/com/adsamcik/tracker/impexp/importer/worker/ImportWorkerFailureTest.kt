package com.adsamcik.tracker.impexp.importer.worker

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.ListenableWorker
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.PermanentImportInputException
import com.adsamcik.tracker.impexp.importer.computeImportJobId
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.PortableAmbientStepsFileImport
import com.adsamcik.tracker.shared.base.extension.openInputStream
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImportWorkerFailureTest {
	private val context = mockk<Context>()

	@BeforeEach
	fun setUp() {
		mockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
	}

	@Test
	fun `Ambient Steps direct hashing uses the format owned 32 MiB cap`() {
		importSourceReadLimit("TRACKERAMBIENTSTEPS") shouldBe
			PortableAmbientStepsFileImport.MAX_FILE_BYTES
		PortableAmbientStepsFileImport.MAX_FILE_BYTES shouldBe 32L * 1_024L * 1_024L
	}

	@Test
	fun `direct source over its exact hash boundary is terminal while the boundary is valid`() {
		val bytes = "12345".encodeToByteArray()
		val file = mockFile(bytes, "source.trackersteps")

		computeImportJobId(context, file, maxBytes = bytes.size.toLong()).length shouldBe 64
		val failure = shouldThrow<PermanentImportInputException> {
			computeImportJobId(context, file, maxBytes = bytes.size.toLong() - 1L)
		}

		classifyImportWorkerIOException(failure) shouldBe ImportWorkerIoDecision.Terminal(
			ImportResult(
				failedCount = 1,
				errors = listOf("Import source exceeds its size limit (4 bytes)."),
			),
		)
	}

	@Test
	fun `ZIP structural and resource violations are terminal worker failures`() {
		val extractor = ZipArchiveExtractor()
		val failures = listOf(
			shouldThrow<PermanentImportInputException> {
				extractor.classifyForMergeImport(
					context,
					mockFile("not a zip".encodeToByteArray()),
				)
			},
			shouldThrow<PermanentImportInputException> {
				extractor.classifyForMergeImport(
					context,
					mockFile(byteArrayOf(0x50, 0x4b, 0x03, 0x04)),
				)
			},
			shouldThrow<PermanentImportInputException> {
				extractor.classifyForMergeImport(
					context,
					mockFile(
						bytes = byteArrayOf(),
						declaredLength = ZipArchiveExtractor.MAX_COMPRESSED_INPUT_BYTES + 1L,
					),
				)
			},
			shouldThrow<PermanentImportInputException> {
				extractor.classifyForMergeImport(
					context,
					mockFile(
						buildZip(
							"bomb.json",
							ByteArray(4 * 1_024 * 1_024) { 'A'.code.toByte() },
						),
					),
				)
			},
		)

		failures.forEach { failure ->
			val decision = classifyImportWorkerIOException(failure)
			(decision is ImportWorkerIoDecision.Terminal) shouldBe true
			importWorkerResult((decision as ImportWorkerIoDecision.Terminal).result) shouldBe
				ListenableWorker.Result.failure()
		}
	}

	@Test
	fun `raw IOException and EOFException remain retryable worker failures`() {
		listOf(
			IOException("provider unavailable"),
			EOFException("provider interrupted"),
		).forEach { expected ->
			val file = mockStreamFile {
				object : InputStream() {
					override fun read(): Int = throw expected
				}
			}
			val failure = shouldThrow<IOException> {
				computeImportJobId(context, file, maxBytes = 100L)
			}

			failure shouldBe expected
			classifyImportWorkerIOException(failure) shouldBe ImportWorkerIoDecision.Retry
		}
	}

	@Test
	fun `failed no importable archive result cannot become successful work`() {
		importWorkerResult(
			ImportResult(
				failedCount = 1,
				errors = listOf("Archive contains no safe supported file entries."),
			),
		) shouldBe ListenableWorker.Result.failure()
	}

	private fun mockFile(
		bytes: ByteArray,
		name: String = "archive.zip",
		declaredLength: Long = bytes.size.toLong(),
	): DocumentFile {
		val file = mockk<DocumentFile> {
			every { this@mockk.name } returns name
			every { isDirectory } returns false
			every { length() } returns declaredLength
		}
		every { file.openInputStream(context) } answers { ByteArrayInputStream(bytes) }
		return file
	}

	private fun mockStreamFile(streamProvider: () -> InputStream): DocumentFile {
		val file = mockk<DocumentFile> {
			every { this@mockk.name } returns "source.trackersteps"
			every { isDirectory } returns false
			every { length() } returns 100L
		}
		every { file.openInputStream(context) } answers { streamProvider() }
		return file
	}

	private fun buildZip(name: String, bytes: ByteArray): ByteArray {
		val output = ByteArrayOutputStream()
		ZipOutputStream(output).use { zip ->
			zip.putNextEntry(ZipEntry(name))
			zip.write(bytes)
			zip.closeEntry()
		}
		return output.toByteArray()
	}
}
