package com.adsamcik.tracker.impexp.importer

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveExtractor
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.base.extension.openInputStream
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImportJobRunnerTest {
	private val context = mockk<Context>()
	private lateinit var cacheDir: File

	@BeforeEach
	fun setUp() {
		mockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
		cacheDir = File("build/test-import-runner-${System.nanoTime()}").apply { mkdirs() }
		every { context.cacheDir } returns cacheDir
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic("com.adsamcik.tracker.shared.base.extension.FileExtensionsKt")
		cacheDir.deleteRecursively()
	}

	@Test
	fun `retry after mid archive IOException skips entries that already committed`() = runTest {
		val zipBytes = buildZip(
			"first.json" to "first",
			"second.json" to "second",
			"third.json" to "third",
		)
		val file = mockArchive(zipBytes)
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store) { 123L }
		val calls = mutableMapOf<String, Int>()
		runner.start(JOB_ID, "backup.zip", zipBytes.size.toLong()) shouldBe true

		assertFailsWith<IOException> {
			runner.importArchive(JOB_ID, context, file, ZipArchiveExtractor()) { stream ->
				calls[stream.fileName] = calls.getOrDefault(stream.fileName, 0) + 1
				if (stream.fileName == "second.json") throw IOException("simulated interruption")
				ImportResult(successCount = 1)
			}
		}

		val retried = runner.importArchive(JOB_ID, context, file, ZipArchiveExtractor()) { stream ->
			calls[stream.fileName] = calls.getOrDefault(stream.fileName, 0) + 1
			ImportResult(successCount = 1)
		}

		retried.successCount shouldBe 3
		calls shouldContainExactly mapOf(
			"first.json" to 1,
			"second.json" to 2,
			"third.json" to 1,
		)
	}

	@Test
	fun `completed content addressed job is a no-op when selected again`() = runTest {
		val bytes = """{"locations":[]}""".toByteArray()
		val firstFile = mockFile("backup.json", bytes)
		val sameContentCopy = mockFile("backup.json", bytes)
		val firstJobId = computeImportJobId(context, firstFile)
		val repeatedJobId = computeImportJobId(context, sameContentCopy)
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store) { 456L }
		var importCalls = 0

		firstJobId shouldBe repeatedJobId
		runner.start(firstJobId, "backup.json", bytes.size.toLong()) shouldBe true
		val result = runner.importSingle(
			jobId = firstJobId,
			stream = FileImportStream(ByteArrayInputStream(bytes), "backup.json"),
		) {
			importCalls++
			ImportResult(successCount = 1)
		}
		runner.completeIfSuccessful(firstJobId, result)

		runner.start(repeatedJobId, "backup.json", bytes.size.toLong()) shouldBe false
		importCalls shouldBe 1
	}

	@Test
	fun `failed import remains incomplete and can be selected again`() = runTest {
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store) { 789L }
		val failure = ImportResult(
			failedCount = 1,
			errors = listOf("Full database restore is required"),
		)

		runner.start(JOB_ID, "backup.zip", 100L) shouldBe true
		runner.completeIfSuccessful(JOB_ID, failure)

		store.jobStatus(JOB_ID) shouldBe ImportJobReceiptEntity.STATUS_IN_PROGRESS
		runner.start(JOB_ID, "backup.zip", 100L) shouldBe true
	}

	private fun mockArchive(bytes: ByteArray): DocumentFile = mockFile("backup.zip", bytes).also {
		every { it.isDirectory } returns false
	}

	private fun mockFile(name: String, bytes: ByteArray): DocumentFile {
		val file = mockk<DocumentFile> {
			every { this@mockk.name } returns name
			every { length() } returns bytes.size.toLong()
		}
		every { file.openInputStream(context) } answers { ByteArrayInputStream(bytes) }
		return file
	}

	private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
		val output = ByteArrayOutputStream()
		ZipOutputStream(output).use { zip ->
			entries.forEach { (name, content) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(content.toByteArray())
				zip.closeEntry()
			}
		}
		return output.toByteArray()
	}

	private class FakeImportReceiptStore : ImportReceiptStore {
		private val jobs = mutableMapOf<String, ImportJobReceiptEntity>()
		private val entries = mutableMapOf<Pair<String, String>, ImportEntryReceiptEntity>()

		override suspend fun <T> transaction(block: suspend () -> T): T {
			val jobsBefore = jobs.toMap()
			val entriesBefore = entries.toMap()
			return try {
				block()
			} catch (failure: Throwable) {
				jobs.clear()
				jobs.putAll(jobsBefore)
				entries.clear()
				entries.putAll(entriesBefore)
				throw failure
			}
		}

		override suspend fun getJob(jobId: String): ImportJobReceiptEntity? = jobs[jobId]

		override suspend fun insertJob(job: ImportJobReceiptEntity) {
			jobs.putIfAbsent(job.jobId, job)
		}

		override suspend fun markJobInProgress(
			jobId: String,
			sourceName: String,
			sourceSizeBytes: Long,
			updatedAt: Long,
		) {
			jobs[jobId] = jobs.getValue(jobId).copy(
				sourceName = sourceName,
				sourceSizeBytes = sourceSizeBytes,
				status = ImportJobReceiptEntity.STATUS_IN_PROGRESS,
				completedAt = null,
				updatedAt = updatedAt,
			)
		}

		override suspend fun markJobComplete(jobId: String, completedAt: Long) {
			jobs[jobId] = jobs.getValue(jobId).copy(
				status = ImportJobReceiptEntity.STATUS_COMPLETE,
				completedAt = completedAt,
				updatedAt = completedAt,
			)
		}

		override suspend fun getEntry(
			jobId: String,
			entryKey: String,
		): ImportEntryReceiptEntity? = entries[jobId to entryKey]

		override suspend fun putEntry(entry: ImportEntryReceiptEntity) {
			entries[entry.jobId to entry.entryKey] = entry
		}

		fun jobStatus(jobId: String): String? = jobs[jobId]?.status
	}

	private companion object {
		const val JOB_ID = "test-job"
	}
}
