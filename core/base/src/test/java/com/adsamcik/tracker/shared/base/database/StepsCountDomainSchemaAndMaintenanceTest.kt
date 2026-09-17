package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsCountDomainSchemaAndMaintenanceTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		installSchema()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `DDL is idempotent and installs exact sentinel indexes foreign keys and triggers`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase

		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN (?, ?, ?, ?)",
			arrayOf(
				StepsCountDomainSchema.RECEIPT_TABLE,
				StepsCountDomainSchema.OWNER_TABLE,
				StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
				StepsCountDomainSchema.SCHEMA_MARKER_TABLE,
			),
		) shouldBe 4L
		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN (?, ?, ?)",
			arrayOf(
				StepsCountDomainSchema.AMBIENT_NO_RESURRECTION_TRIGGER,
				StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER,
				StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER,
			),
		) shouldBe 3L
		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
				"AND name IN (?, ?, ?, ?, ?, ?)",
			arrayOf(
				"idx_steps_count_domain_receipt_owner",
				"idx_steps_count_domain_receipt_compatibility",
				"idx_steps_count_domain_owner_scope",
				"idx_steps_count_domain_owner_receipt",
				"idx_steps_count_domain_owner_terminal_age",
				"idx_steps_count_domain_completeness_owner",
			),
		) shouldBe 6L
		sqlite.count(
			"SELECT COUNT(*) FROM steps_count_domain_schema_marker WHERE id = 1 " +
				"AND contract_version = 2 AND token_semantics = 'PROVIDER_COUNTER_EPOCH_V1' " +
				"AND terminal_unproven = 1",
		) shouldBe 1L
		sqlite.count(
			"SELECT COUNT(*) FROM pragma_foreign_key_list('steps_count_domain_owner_revision') " +
				"WHERE `table` = 'steps_count_domain_receipt' AND on_delete = 'RESTRICT'",
		) shouldBe 1L
		sqlite.count(
			"SELECT COUNT(*) FROM pragma_foreign_key_list(" +
				"'steps_count_domain_completeness_marker') " +
				"WHERE `table` = 'steps_count_domain_owner_revision' AND on_delete = 'CASCADE'",
		) shouldBe 3L
	}

	@Test
	fun `full clear deletes owners before receipts or preserves only terminal evidence`() = runTest {
		val store = StepsCountDomainStore(database)
		val bound = insertWal("bound", 1L, payloadVersion = 6)
		store.recordSessionWal(bound, token('a')) shouldBe StepsCountDomainWriteResult.INSERTED
		val unproven = insertWal("unproven", 2L)
		store.recordSessionWal(unproven, null) shouldBe StepsCountDomainWriteResult.INSERTED

		val sqlite = database.openHelper.writableDatabase
		shouldThrow<Exception> {
			sqlite.execSQL("DELETE FROM steps_count_domain_receipt")
		}
		database.clearStepsCountDomainEvidenceInTransaction(
			StepsCountDomainFullClearMode.PRESERVE_TERMINAL,
		) shouldBe StepsCountDomainMaintenanceResult.Applied(1, 1)
		sqlite.count("SELECT COUNT(*) FROM steps_count_domain_receipt") shouldBe 0L
		sqlite.count(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE operation = 'UNPROVEN'",
		) shouldBe 1L

		database.clearStepsCountDomainEvidenceInTransaction(
			StepsCountDomainFullClearMode.REMOVE_ALL,
		) shouldBe StepsCountDomainMaintenanceResult.Applied(1, 0)
		sqlite.count("SELECT COUNT(*) FROM steps_count_domain_owner_revision") shouldBe 0L
		sqlite.count("SELECT COUNT(*) FROM steps_count_domain_schema_marker") shouldBe 1L
	}

	@Test
	fun `terminal compaction is bounded and removes oldest opaque owners`() = runTest {
		val store = StepsCountDomainStore(database)
		repeat(3) { index ->
			store.recordSessionWal(insertWal("terminal-$index", index.toLong() + 1L), null) shouldBe
				StepsCountDomainWriteResult.INSERTED
		}

		store.compactTerminalOwners(
			maximumRetainedTerminalOwners = 1,
			batchSize = 2,
			sourceFenceAuthenticated = true,
		) shouldBe StepsCountDomainMaintenanceResult.Applied(2, 0)
		database.openHelper.writableDatabase.count(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE operation = 'UNPROVEN'",
		) shouldBe 1L
	}

	@Test
	fun `bounded WAL retention removes exact owners and orphan receipts before payload rows`() =
		runTest {
			val store = StepsCountDomainStore(database)
			val first = insertWal("wal-retention-bound", 1L, payloadVersion = 6)
			val second = insertWal("wal-retention-unproven", 2L)
			store.recordSessionWal(first, token('a')) shouldBe
				StepsCountDomainWriteResult.INSERTED
			store.recordSessionWal(second, null) shouldBe
				StepsCountDomainWriteResult.INSERTED

			store.removeSessionWalOwnersForPrune(
				safeOrdinal = second.admissionOrdinal,
				createdBeforeMs = 3L,
				limit = 2,
			) shouldBe StepsCountDomainMaintenanceResult.Applied(2, 1)
			database.openHelper.writableDatabase.count(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision",
			) shouldBe 0L
			database.openHelper.writableDatabase.count(
				"SELECT COUNT(*) FROM steps_count_domain_receipt",
			) shouldBe 0L
		}

	@Test
	fun `corrupt schema sentinel cannot be repaired by idempotent DDL or activate writers`() =
		runTest {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE steps_count_domain_schema_marker SET contract_version = 1 WHERE id = 1",
			)
			installSchema()

			StepsCountDomainStore(database).recordSessionWal(
				insertWal("sentinel-corrupt", 1L, payloadVersion = 6),
				token('a'),
			) shouldBe StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
		}

	@Test
	fun `terminal owner trigger rejects direct bind after unproven evidence`() = runTest {
		val wal = insertWal("terminal-trigger", 1L)
		StepsCountDomainStore(database).recordSessionWal(wal, null) shouldBe
			StepsCountDomainWriteResult.INSERTED
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
			wal.admissionOrdinal,
			wal.eventId,
		)

		shouldThrow<Exception> {
			database.openHelper.writableDatabase.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES (?, ?, ?, 2, " +
					"'BIND', NULL, ?, 2)",
				arrayOf(
					"SESSION_WAL",
					StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity("tracking", "run"),
					ownerIdentity,
					"b".repeat(64),
				),
			)
		}
	}

	private fun installSchema() {
		StepsCountDomainSchema.createStatements.forEach {
			database.openHelper.writableDatabase.execSQL(it)
		}
	}

	private suspend fun insertWal(
		eventId: String,
		sourceSequence: Long,
		payloadVersion: Int = 1,
	): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			eventId = eventId,
			providerDedupKey = "dedup-$eventId",
			logicalTrackingId = "tracking",
			serviceRunId = "run",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			sourceInstanceId = "owner-$sourceSequence",
			registrationGeneration = sourceSequence,
			physicalConfigurationFingerprint = "configuration-$sourceSequence",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = sourceSequence,
			configRevision = sourceSequence,
			planAttribution = 0,
			clockDomainId = "boot",
			observedElapsedNanos = sourceSequence,
			receivedElapsedNanos = sourceSequence,
			wallTimeMs = sourceSequence,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 7L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = sourceSequence,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = payloadVersion,
			payload = byteArrayOf(sourceSequence.toByte()),
			payloadChecksum = "",
			createdAtMs = sourceSequence,
		)
		val payloadSigned = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		val signed = payloadSigned.copy(
			integrityIdentity = payloadSigned.calculatedIntegrityIdentity(),
		)
		val ordinal = database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(signed)
		return signed.copy(admissionOrdinal = ordinal)
	}

	private fun token(digit: Char) =
		StepsCounterDomainToken.opaque("sha256:${digit.toString().repeat(64)}")
}

private fun SupportSQLiteDatabase.count(
	sql: String,
	args: Array<out Any?> = emptyArray(),
): Long = query(sql, args).use { cursor ->
	check(cursor.moveToFirst())
	cursor.getLong(0)
}
