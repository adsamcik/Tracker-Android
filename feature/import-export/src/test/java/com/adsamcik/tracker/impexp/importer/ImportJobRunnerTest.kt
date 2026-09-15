package com.adsamcik.tracker.impexp.importer

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.ImportTransactionMode
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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
	fun `archives without a safe supported file fail and never complete their job`() = runTest {
		val archives = listOf(
			buildZip(),
			buildZip("directory/" to ""),
			buildZip("../unsafe.json" to "{}"),
			buildZip("notes.txt" to "unsupported"),
		)

		var unsupportedCalls = 0
		archives.forEachIndexed { index, bytes ->
			val jobId = "no-importable-$index"
			val store = FakeImportReceiptStore()
			val runner = ImportJobRunner(store) { 100L }
			runner.start(jobId, "archive.zip", bytes.size.toLong()) shouldBe true

			val result = runner.importArchive(
				jobId = jobId,
				context = context,
				file = mockArchive(bytes),
				extractor = ZipArchiveExtractor(),
				isImportableEntry = { it.fileName.endsWith(".json") },
			) { stream ->
				unsupportedCalls++
				ImportResult(
					skippedCount = 1,
					errors = listOf("Unsupported ${stream.fileName}"),
				)
			}
			runner.completeIfSuccessful(jobId, result)

			result.failedCount shouldBe 1
			result.errors.last() shouldBe NO_IMPORTABLE_ARCHIVE_ENTRY_ERROR
			store.jobStatus(jobId) shouldBe ImportJobReceiptEntity.STATUS_IN_PROGRESS
		}
		unsupportedCalls shouldBe 0
	}

	@Test
	fun `headerless archive is permanent and its job remains incomplete`() = runTest {
		val bytes = "not a zip".encodeToByteArray()
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store) { 200L }
		runner.start(JOB_ID, "archive.zip", bytes.size.toLong()) shouldBe true

		val failure = assertFailsWith<PermanentImportInputException> {
			runner.importArchive(
				jobId = JOB_ID,
				context = context,
				file = mockArchive(bytes),
				extractor = ZipArchiveExtractor(),
			) {
				ImportResult(successCount = 1)
			}
		}

		failure.committedPrefix shouldBe ImportResult.EMPTY
		store.jobStatus(JOB_ID) shouldBe ImportJobReceiptEntity.STATUS_IN_PROGRESS
	}

	@Test
	fun `permanent archive failure retains the committed supported prefix`() = runTest {
		val bytes = buildZipPayloads(
			"first.json" to """{"first":true}""".encodeToByteArray(),
			"bomb.json" to ByteArray(4 * 1_024 * 1_024) { 'A'.code.toByte() },
		)
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store) { 300L }
		runner.start(JOB_ID, "archive.zip", bytes.size.toLong()) shouldBe true
		var calls = 0

		val failure = assertFailsWith<PermanentImportInputException> {
			runner.importArchive(
				jobId = JOB_ID,
				context = context,
				file = mockArchive(bytes),
				extractor = ZipArchiveExtractor(),
				isImportableEntry = { it.fileName.endsWith(".json") },
			) {
				calls++
				ImportResult(successCount = 1)
			}
		}

		val result = failure.toImportResult()
		result.successCount shouldBe 1
		result.skippedCount shouldBe 0
		result.failedCount shouldBe 1
		result.errors shouldBe listOf(requireNotNull(failure.message))
		calls shouldBe 1
		store.entryStatus(JOB_ID, failureEntryKey(store, JOB_ID)) shouldBe
			ImportEntryReceiptEntity.STATUS_SUCCESS
		store.jobStatus(JOB_ID) shouldBe ImportJobReceiptEntity.STATUS_IN_PROGRESS
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

	@Test
	fun `importer managed entry runs before its success receipt transaction`() = runTest {
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store) { 321L }
		runner.start(JOB_ID, "steps.trackersteps", 100L) shouldBe true
		var importerObservedTransaction = true

		val result = runner.importSingle(
			jobId = JOB_ID,
			stream = FileImportStream(ByteArrayInputStream(byteArrayOf()), "steps.trackersteps"),
			transactionMode = ImportTransactionMode.IMPORTER_MANAGED,
		) {
			importerObservedTransaction = store.isInTransaction
			ImportResult(successCount = 1)
		}

		result shouldBe ImportResult(successCount = 1)
		importerObservedTransaction shouldBe false
		store.lastPutWasTransactional shouldBe true
		store.entryStatus(JOB_ID, "steps.trackersteps") shouldBe
			ImportEntryReceiptEntity.STATUS_SUCCESS
	}

	@Test
	fun `worker managed entry retains the existing enclosing receipt transaction`() = runTest {
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store) { 654L }
		runner.start(JOB_ID, "track.gpx", 100L) shouldBe true
		var importerObservedTransaction = false

		runner.importSingle(
			jobId = JOB_ID,
			stream = FileImportStream(ByteArrayInputStream(byteArrayOf()), "track.gpx"),
		) {
			importerObservedTransaction = store.isInTransaction
			ImportResult(successCount = 1)
		}

		importerObservedTransaction shouldBe true
		store.lastPutWasTransactional shouldBe true
	}

	@Test
	fun `importer managed permanent failure records only its failure receipt transactionally`() = runTest {
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store) { 987L }
		runner.start(JOB_ID, "steps.trackersteps", 100L) shouldBe true
		var importerObservedTransaction = true

		val result = runner.importSingle(
			jobId = JOB_ID,
			stream = FileImportStream(ByteArrayInputStream(byteArrayOf()), "steps.trackersteps"),
			transactionMode = ImportTransactionMode.IMPORTER_MANAGED,
		) {
			importerObservedTransaction = store.isInTransaction
			ImportResult(failedCount = 1, errors = listOf("conflict"))
		}

		result shouldBe ImportResult(failedCount = 1, errors = listOf("conflict"))
		importerObservedTransaction shouldBe false
		store.lastPutWasTransactional shouldBe true
		store.entryStatus(JOB_ID, "steps.trackersteps") shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE
	}

	@Test
	fun `source receipt uses durable job start across failed import retries`() = runTest {
		val store = FakeImportReceiptStore()
		var now = 100L
		val runner = ImportJobRunner(store, nowMs = { now })
		runner.start(JOB_ID, "pressure.trackerpressure", 10L)
		val receipts = mutableListOf<FileImportReceiptContext>()

		assertFailsWith<IOException> {
			runner.importSingle(
				JOB_ID,
				FileImportStream("pressure.trackerpressure", "direct-entry", { ByteArrayInputStream(byteArrayOf(1)) }),
				ImportTransactionMode.IMPORTER_MANAGED,
			) { stream ->
				receipts += requireNotNull(stream.importReceipt)
				stream.read() shouldBe 1
				throw IOException("source failure")
			}
		}
		now = 200L
		runner.start(JOB_ID, "pressure.trackerpressure", 10L)
		runner.importSingle(
			JOB_ID,
			FileImportStream("pressure.trackerpressure", "direct-entry", { ByteArrayInputStream(byteArrayOf(1)) }),
			ImportTransactionMode.IMPORTER_MANAGED,
		) { stream ->
			receipts += requireNotNull(stream.importReceipt)
			ImportResult(successCount = 1)
		}

		receipts shouldBe List(2) {
			FileImportReceiptContext(JOB_ID, "direct-entry", "pressure.trackerpressure", 100L)
		}
	}

	@Test
	fun `archive sources receive exact distinct entry receipt keys and one durable job time`() = runTest {
		val bytes = buildZip("one.json" to "one", "two.json" to "two")
		val file = mockArchive(bytes)
		val runner = ImportJobRunner(FakeImportReceiptStore(), nowMs = { 300L })
		runner.start(JOB_ID, "archive.zip", bytes.size.toLong())
		val receipts = mutableListOf<FileImportReceiptContext>()

		runner.importArchive(JOB_ID, context, file, ZipArchiveExtractor()) { stream ->
			val receipt = requireNotNull(stream.importReceipt)
			receipt.entryKey shouldBe stream.receiptKey
			receipt.sourceName shouldBe stream.fileName
			receipts += receipt
			ImportResult(successCount = 1)
		}

		receipts.size shouldBe 2
		receipts.map { it.jobId }.toSet() shouldBe setOf(JOB_ID)
		receipts.map { it.receivedAtMs }.toSet() shouldBe setOf(300L)
		receipts.map { it.entryKey }.distinct().size shouldBe 2
	}

	@Test
	fun `source importer is not called without durable job provenance`() = runTest {
		var called = false
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store)

		assertFailsWith<IOException> {
			runner.importSingle(JOB_ID, createSourceStream()) {
				called = true
				ImportResult(successCount = 1)
			}
		}
		called shouldBe false
		store.getEntry(JOB_ID, "source.json") shouldBe null
		store.lastPutWasTransactional shouldBe null
	}

	@Test
	fun `completed job cannot create a new source failure receipt`() = runTest {
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store)
		runner.start(JOB_ID, "source.json", 1L)
		runner.completeIfSuccessful(JOB_ID, ImportResult(successCount = 1))
		var called = false

		assertFailsWith<IOException> {
			runner.importSingle(JOB_ID, createSourceStream(), ImportTransactionMode.IMPORTER_MANAGED) {
				called = true
				ImportResult(successCount = 1)
			}
		}

		called shouldBe false
		store.getEntry(JOB_ID, "source.json") shouldBe null
		store.lastPutWasTransactional shouldBe null
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `job completion cannot race an importer managed source callback`() = runTest {
		val store = FakeImportReceiptStore()
		val runner = ImportJobRunner(store)
		runner.start(JOB_ID, "source.json", 1L)
		val entered = CompletableDeferred<Unit>()
		val resume = CompletableDeferred<Unit>()
		val importing = async {
			runner.importSingle(JOB_ID, createSourceStream(), ImportTransactionMode.IMPORTER_MANAGED) {
				entered.complete(Unit)
				resume.await()
				ImportResult(successCount = 1)
			}
		}
		entered.await()
		val completing = async {
			runner.completeIfSuccessful(JOB_ID, ImportResult(successCount = 1))
		}
		runCurrent()

		completing.isCompleted shouldBe false
		store.jobStatus(JOB_ID) shouldBe ImportJobReceiptEntity.STATUS_IN_PROGRESS
		resume.complete(Unit)
		importing.await()
		completing.await()
		store.jobStatus(JOB_ID) shouldBe ImportJobReceiptEntity.STATUS_COMPLETE
	}

	private fun createSourceStream() = FileImportStream(
		ByteArrayInputStream(byteArrayOf(1)),
		"source.json",
	)

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
		return buildZipPayloads(*entries.map { it.first to it.second.toByteArray() }.toTypedArray())
	}

	private fun buildZipPayloads(vararg entries: Pair<String, ByteArray>): ByteArray {
		val output = ByteArrayOutputStream()
		ZipOutputStream(output).use { zip ->
			entries.forEach { (name, content) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(content)
				zip.closeEntry()
			}
		}
		return output.toByteArray()
	}

	private class FakeImportReceiptStore : ImportReceiptStore {
		private val jobs = mutableMapOf<String, ImportJobReceiptEntity>()
		private val entries = mutableMapOf<Pair<String, String>, ImportEntryReceiptEntity>()
		private var transactionDepth = 0
		val isInTransaction: Boolean get() = transactionDepth > 0
		var lastPutWasTransactional: Boolean? = null
			private set

		override suspend fun <T> transaction(block: suspend () -> T): T {
			val jobsBefore = jobs.toMap()
			val entriesBefore = entries.toMap()
			transactionDepth++
			return try {
				block()
			} catch (failure: Throwable) {
				jobs.clear()
				jobs.putAll(jobsBefore)
				entries.clear()
				entries.putAll(entriesBefore)
				throw failure
			} finally {
				transactionDepth--
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
			lastPutWasTransactional = isInTransaction
			entries[entry.jobId to entry.entryKey] = entry
		}

		fun jobStatus(jobId: String): String? = jobs[jobId]?.status

		fun entryStatus(jobId: String, entryKey: String): String? = entries[jobId to entryKey]?.status

		fun entryKeys(jobId: String): Set<String> =
			entries.keys.filter { it.first == jobId }.mapTo(linkedSetOf()) { it.second }
	}

	private fun failureEntryKey(store: FakeImportReceiptStore, jobId: String): String =
		store.entryKeys(jobId).single()

	private companion object {
		const val JOB_ID = "test-job"
	}
}
