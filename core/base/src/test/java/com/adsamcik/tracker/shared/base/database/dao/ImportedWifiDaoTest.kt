package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunZoneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
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
class ImportedWifiDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: ImportedWifiDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		dao = database.importedWifiDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `bounded hierarchy and cross-kind owners are queryable without live authority`() = runTest {
		dao.insertEntryRevision(entry())
		dao.insertRun(run())
		dao.insertRunZone(zone())
		dao.insertObservation(observation())
		dao.insertReceipt(receipt())
		dao.insertEntryDeletion(ImportedWifiEntryDeletionEntity.create("8".repeat(64), EPOCH, 1L, 1_400L))
		dao.insertDeletionGeneration(ImportedWifiDeletionGenerationEntity.create(
			"9".repeat(64), "8".repeat(64), "a".repeat(64), EPOCH, 1L, 1_400L,
		))

		dao.entryRevisionsForAdmission(ENTRY) shouldBe listOf(entry())
		dao.receiptsForAdmission(ENTRY) shouldBe listOf(receipt())
		dao.allRunsForAdmission(ENTRY) shouldBe listOf(run())
		dao.allRunZonesForAdmission(ENTRY) shouldBe listOf(zone())
		dao.allObservationsForAdmission(ENTRY) shouldBe listOf(observation())
		dao.existingEntryIdentities(listOf(ENTRY, RUN, OBSERVATION), 4) shouldBe listOf(ENTRY)
		dao.existingRunIdentityOwners(listOf(ENTRY, RUN, OBSERVATION), 4).single().identity shouldBe RUN
		dao.existingObservationIdentityOwners(listOf(ENTRY, RUN, OBSERVATION), 4)
			.single().identity shouldBe OBSERVATION
		dao.existingRunScopeOwners(listOf(SCOPE), 2).single().runIdentity shouldBe RUN
		dao.deletionGenerationsByEntry(listOf("8".repeat(64)), 1).single().runIdentity shouldBe "9".repeat(64)
		database.sourceSessionDao().session(ENTRY) shouldBe null
		database.sourceSessionDao().serviceRun(RUN) shouldBe null
		database.wifiCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `foreign hierarchy is atomic and full collected clear removes every imported row`() = runTest {
		shouldThrow<android.database.sqlite.SQLiteConstraintException> { dao.insertRun(run()) }
		dao.insertEntryRevision(entry())
		dao.insertRun(run())
		dao.insertRunZone(zone())
		dao.insertObservation(observation())
		dao.insertReceipt(receipt())
		dao.insertEntryDeletion(ImportedWifiEntryDeletionEntity.create("8".repeat(64), EPOCH, 1L, 1_400L))
		dao.insertDeletionGeneration(ImportedWifiDeletionGenerationEntity.create(
			"9".repeat(64), "8".repeat(64), "a".repeat(64), EPOCH, 1L, 1_400L,
		))
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))

		AppDatabase.deleteAllCollectedData(database, EPOCH + 1L, null, 2_000L)

		dao.entryRevisionCount() shouldBe 0L
		dao.receiptCount() shouldBe 0L
		dao.runCount() shouldBe 0L
		dao.runZoneCount() shouldBe 0L
		dao.observationCount() shouldBe 0L
		dao.entryDeletionCount() shouldBe 0L
		dao.deletionGenerationCount() shouldBe 0L
	}

	@Test
	fun `bounded fence ownership query authenticates every source purpose without mutating authority`() = runTest {
		val fences = listOf(
			SourceDeletionFenceEntity.createLogicalServiceRun(6,
				"SESSION_CAPTURE", "cell-entry", "cell-run", 1L, EPOCH, 1_400L),
			SourceDeletionFenceEntity.createLogicalServiceRun(SourceDestinationOwnerEntity.SOURCE_WIFI,
				"CONTROL_CONTINUATION", "control-entry", "control-run", 1L, EPOCH, 1_400L),
		)
		fences.forEach { database.sourceDeletionFenceDao().upsert(it) }
		val identities = fences.map { it.scopeIdentityDigest }

		dao.deletionFenceIdentityOwners(identities, 1).size shouldBe 1
		dao.deletionFenceIdentityOwners(identities, 3).toSet() shouldBe fences.toSet()
		dao.deletionFenceIdentityOwners(listOf("f".repeat(64)), 1) shouldBe emptyList()
		dao.deletionFenceIdentityOwners(identities, 3).toSet() shouldBe fences.toSet()
		dao.entryRevisionCount() shouldBe 0L
		dao.receiptCount() shouldBe 0L
	}

	private fun entry() = ImportedWifiEntryRevisionEntity(
		ENTRY, 1L, null, ENTRY_CHECKSUM,
		ImportedWifiEntryRevisionEntity.SOURCE_FORMAT,
		ImportedWifiEntryRevisionEntity.SOURCE_SCHEMA_VERSION,
		"MANUAL", 800L, 1_200L, EPOCH, "job", "entry", "source.trackerwifi", 1_300L,
	)

	private fun receipt() = ImportedWifiReceiptEntity(
		"job", "entry", "source.trackerwifi", 1_300L, ENTRY, 1L, ENTRY_CHECKSUM, EPOCH,
	)

	private fun run() = ImportedWifiRunEntity(
		ENTRY, 1L, RUN, SCOPE, RUN_CHECKSUM, 800L, 1_200L,
		"WHOLE_RUN", "RETAINED", "COMPLETE", false, false, EPOCH, 0L,
	)

	private fun zone() = ImportedWifiRunZoneEntity(ENTRY, 1L, RUN, 0, "Europe/Prague")

	private fun observation() = ImportedWifiObservationEntity(
		ENTRY, 1L, RUN, OBSERVATION, 1L, null, null, null, OBSERVATION_CHECKSUM,
		900L, 1_000L, 1_050L, 50L, "Europe/Prague", "AVAILABLE", "COMPLETE",
		2, 2, 0, 0, 0, 2, 1, 1, 0, 0, -40, -60, -50.0, 0L, 1f,
	)

	private companion object {
		const val EPOCH = 7L
		val ENTRY = "1".repeat(64)
		val RUN = "2".repeat(64)
		val SCOPE = "3".repeat(64)
		val OBSERVATION = "4".repeat(64)
		val ENTRY_CHECKSUM = "5".repeat(64)
		val RUN_CHECKSUM = "6".repeat(64)
		val OBSERVATION_CHECKSUM = "7".repeat(64)
	}
}
