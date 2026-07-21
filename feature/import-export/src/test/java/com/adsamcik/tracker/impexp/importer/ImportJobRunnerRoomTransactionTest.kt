package com.adsamcik.tracker.impexp.importer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportJobRunnerRoomTransactionTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `importer managed nested transaction and receipt commit or roll back atomically`() = runTest {
		val runner = ImportJobRunner(RoomImportReceiptStore(database)) { 1_000L }
		runner.start(JOB_ID, "backup.db", 4L) shouldBe true

		val success = runner.importSingle(
			JOB_ID,
			FileImportStream(ByteArrayInputStream(byteArrayOf()), "first.db"),
		) {
			database.runInTransaction {
				insertExportLog("committed.db")
			}
			ImportResult(successCount = 1)
		}

		success.successCount shouldBe 1
		exportLogCount() shouldBe 1L
		database.importReceiptDao().getEntry(JOB_ID, "first.db")?.status shouldBe
			ImportEntryReceiptEntity.STATUS_SUCCESS

		val failure = runner.importSingle(
			JOB_ID,
			FileImportStream(ByteArrayInputStream(byteArrayOf()), "second.db"),
		) {
			database.runInTransaction {
				insertExportLog("rolled-back.db")
			}
			ImportResult(failedCount = 1)
		}

		failure.failedCount shouldBe 1
		exportLogCount() shouldBe 1L
		database.importReceiptDao().getEntry(JOB_ID, "second.db")?.status shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE
	}

	private fun insertExportLog(fileName: String) {
		database.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO export_log(
				format, scope, file_name, file_size_bytes, record_count,
				started_at, completed_at, status, error_message, created_at
			) VALUES ('db', 'ALL', ?, 0, 0, 0, 0, 'SUCCESS', NULL, 0)
			""".trimIndent(),
			arrayOf(fileName),
		)
	}

	private fun exportLogCount(): Long =
		database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM export_log").use {
			it.moveToFirst() shouldBe true
			it.getLong(0)
		}

	private companion object {
		const val JOB_ID = "room-transaction-job"
	}
}
