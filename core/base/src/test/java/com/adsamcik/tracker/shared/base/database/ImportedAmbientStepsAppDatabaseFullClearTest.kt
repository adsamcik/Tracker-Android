package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsSourceFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.deletionScopeIdentity
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedAmbientStepsAppDatabaseFullClearTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var dao: ImportedAmbientStepsDao

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(FILE_DATABASE_NAME)
		database = AppDatabase.testDatabase(context)
		dao = database.importedAmbientStepsDao()
	}

	@After
	fun tearDown() {
		if (::database.isInitialized) database.close()
		if (::context.isInitialized) context.deleteDatabase(FILE_DATABASE_NAME)
	}

	@Test
	fun `suspend full clear preserves old authority and admits unrelated new epoch lineage`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(revision = 4L, collectedDataEpoch = EPOCH),
		)
		val old = seedLineage("old", LocalDate.of(2026, 1, 1), EPOCH, 8L)
		val sourceFence = ImportedAmbientStepsSourceFenceEntity.reopened(
			ImportedAmbientStepsSourceFenceEntity.completed(
				ImportedAmbientStepsSourceFenceEntity.create(EPOCH, 2L, 100L),
				101L,
			),
			reopenedConsentEpoch = 3L,
			reopenedAtMs = 102L,
		)
		dao.insertSourceFence(sourceFence)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = EPOCH + 1L,
			retainedFromMs = null,
			updatedAtMs = old.receivedAtMs + 1L,
		)

		assertPayloadCleared()
		dao.authenticateAllAmbientStepsFences(EPOCH + 1L)
		assertFence(old, EPOCH + 1L, 5L)
		assertProtected(old)
		assertCountDomainOwnerFences(old.day, EPOCH + 1L)
		requireNotNull(dao.sourceFence()).also { retained ->
			retained.collectedDataEpoch shouldBe EPOCH + 1L
			retained.revokedConsentEpoch shouldBe sourceFence.revokedConsentEpoch
			retained.deletedAtMs shouldBe sourceFence.deletedAtMs
			retained.deletionCompleted shouldBe true
			retained.completedAtMs shouldBe sourceFence.completedAtMs
			retained.reopenedConsentEpoch shouldBe sourceFence.reopenedConsentEpoch
			retained.reopenedAtMs shouldBe sourceFence.reopenedAtMs
		}
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.collectedDataEpoch shouldBe EPOCH + 1L
			state.revision shouldBe 5L
		}

		val unrelated = fixture("unrelated", LocalDate.of(2026, 1, 2), EPOCH + 1L, 3L)
		dao.protectedIdentityOwners(
			unrelated.protectedIdentities,
			unrelated.protectedIdentities.size + 1,
		)
			shouldBe emptyList()
		insertLineage(unrelated)
		dao.loadAuthenticatedAmbientStepsLineage(
			unrelated.day.identity.value,
			EPOCH + 1L,
		).latest.day shouldBe unrelated.day

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = EPOCH + 2L,
			retainedFromMs = null,
			updatedAtMs = unrelated.receivedAtMs + 1L,
		)

		assertPayloadCleared()
		dao.authenticateAllAmbientStepsFences(EPOCH + 2L)
		assertFence(old, EPOCH + 2L, 6L)
		assertFence(unrelated, EPOCH + 2L, 6L)
		assertProtected(old)
		assertProtected(unrelated)
		assertCountDomainOwnerFences(old.day, EPOCH + 1L)
		assertCountDomainOwnerFences(unrelated.day, EPOCH + 2L)
		dao.fenceCount() shouldBe 2L
		requireNotNull(dao.sourceFence()).also { retained ->
			retained.collectedDataEpoch shouldBe EPOCH + 2L
			retained.deletionCompleted shouldBe true
			retained.completedAtMs shouldBe sourceFence.completedAtMs
			retained.reopenedConsentEpoch shouldBe sourceFence.reopenedConsentEpoch
			retained.reopenedAtMs shouldBe sourceFence.reopenedAtMs
		}
	}

	@Test
	fun `synchronous full clear uses stored epoch and preserves ambient fences`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(revision = 7L, collectedDataEpoch = EPOCH),
		)
		val lineage = seedLineage("sync", LocalDate.of(2026, 2, 1), EPOCH, 5L)

		AppDatabase.deleteAllCollectedData(database)

		assertPayloadCleared()
		dao.authenticateAllAmbientStepsFences(EPOCH + 1L)
		assertFence(lineage, EPOCH + 1L, 8L)
		assertProtected(lineage)
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.collectedDataEpoch shouldBe EPOCH + 1L
			state.revision shouldBe 8L
		}
	}

	@Test
	@Suppress("LongMethod")
	fun `file backed full clear retains ambient authority across reopen and repeated clear`() = runTest {
		database.close()
		database = openFileDatabase()
		dao = database.importedAmbientStepsDao()
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(revision = 11L, collectedDataEpoch = EPOCH),
		)
		val lineage = seedLineage("file-backed", LocalDate.of(2026, 5, 1), EPOCH, 9L)
		val sourceFence = ImportedAmbientStepsSourceFenceEntity.reopened(
			ImportedAmbientStepsSourceFenceEntity.completed(
				ImportedAmbientStepsSourceFenceEntity.create(EPOCH, 4L, 200L),
				201L,
			),
			reopenedConsentEpoch = 5L,
			reopenedAtMs = 202L,
		)
		dao.insertSourceFence(sourceFence)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = EPOCH + 1L,
			retainedFromMs = null,
			updatedAtMs = lineage.receivedAtMs + 1L,
		)

		reopenFileDatabase()
		assertPayloadCleared()
		dao.authenticateAllAmbientStepsFences(EPOCH + 1L)
		assertFence(lineage, EPOCH + 1L, 12L)
		assertProtected(lineage)
		assertSourceFence(sourceFence, EPOCH + 1L)
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.collectedDataEpoch shouldBe EPOCH + 1L
			state.revision shouldBe 12L
		}
		val protectedAfterFirstClear = dao.protectedIdentitiesForDay(
			lineage.day.identity.value,
			ImportedAmbientStepsDao.MAX_PROTECTED_IDENTITIES_PER_DAY,
		)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = EPOCH + 2L,
			retainedFromMs = null,
			updatedAtMs = lineage.receivedAtMs + 2L,
		)

		reopenFileDatabase()
		assertPayloadCleared()
		dao.authenticateAllAmbientStepsFences(EPOCH + 2L)
		assertFence(lineage, EPOCH + 2L, 13L)
		assertProtected(lineage)
		assertSourceFence(sourceFence, EPOCH + 2L)
		dao.fenceCount() shouldBe 1L
		dao.protectedIdentitiesForDay(
			lineage.day.identity.value,
			ImportedAmbientStepsDao.MAX_PROTECTED_IDENTITIES_PER_DAY,
		) shouldBe protectedAfterFirstClear
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.collectedDataEpoch shouldBe EPOCH + 2L
			state.revision shouldBe 13L
		}
	}

	@Test
	fun `ambient payload deletion failure rolls back marker epoch fences and payload`() = runTest {
		val before = SourceEvidenceState(revision = 9L, collectedDataEpoch = EPOCH)
		database.sourceEvidenceStateDao().ensure(before)
		val lineage = seedLineage("rollback", LocalDate.of(2026, 3, 1), EPOCH, 6L)
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER reject_ambient_archive_delete " +
				"BEFORE DELETE ON imported_ambient_steps_archive " +
				"BEGIN SELECT RAISE(ABORT, '$ROLLBACK_MARKER'); END",
		)

		val failure = requireNotNull(runCatching {
			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = null,
				updatedAtMs = lineage.receivedAtMs + 1L,
			)
		}.exceptionOrNull())

		failure.hasMessageInCauseChain(ROLLBACK_MARKER) shouldBe true
		database.sourceEvidenceStateDao().get() shouldBe before
		dao.fence(lineage.day.identity.value) shouldBe null
		dao.protectedIdentityCount() shouldBe 0L
		dao.archive(lineage.archive.identity.value) shouldBe lineage.archiveEntity
		dao.dayRevisionCount() shouldBe 1L
		dao.factCount() shouldBe 1L
	}

	@Test
	fun `missing portable product ownership rolls back ambient full clear preparation`() = runTest {
		val before = SourceEvidenceState(revision = 9L, collectedDataEpoch = EPOCH)
		database.sourceEvidenceStateDao().ensure(before)
		val lineage = seedLineage("missing-owner", LocalDate.of(2026, 3, 2), EPOCH, 6L)
		val orphanGraph = lineage.day.withExplicitUnprovenCountDomain().countDomainGraph
		database.importedPortableStepsCountDomainDao().insertAuthenticatedGraph(
			orphanGraph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		database.importedPortableStepsCountDomainDao().insertBinding(
			ImportedPortableStepsCountDomainBindingEntity(
				productKind =
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				productIdentity = identity(
					AmbientStepsPortableIdentityKind.DAY,
					"missing-session-product",
				).value,
				productRevision = 1L,
				graphIdentity = orphanGraph.identity.value,
				sourceSchemaVersion = 1,
			),
		)

		shouldThrow<IllegalArgumentException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = null,
				updatedAtMs = lineage.receivedAtMs + 1L,
			)
		}

		database.sourceEvidenceStateDao().get() shouldBe before
		dao.fence(lineage.day.identity.value) shouldBe null
		dao.archive(lineage.archive.identity.value) shouldBe lineage.archiveEntity
		database.importedPortableStepsCountDomainDao()
			.graph(orphanGraph.identity.value)?.graphIdentity shouldBe orphanGraph.identity.value
	}

	@Test
	fun `conflicting portable owner authority rolls back ambient full clear preparation`() = runTest {
		val before = SourceEvidenceState(revision = 9L, collectedDataEpoch = EPOCH)
		database.sourceEvidenceStateDao().ensure(before)
		val lineage = seedLineage("conflicting-owner", LocalDate.of(2026, 3, 3), EPOCH, 6L)
		val graph = lineage.day.withExplicitUnprovenCountDomain().countDomainGraph
		val owner = graph.ownerRevisions.single()
		val conflict = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
			ownerKind = owner.ownerKind.name,
			ownerIdentity = owner.ownerIdentity.value,
			scopeIdentity = owner.scopeIdentity.value,
			latestSourceRevision = owner.ownerRevision,
			latestOwnerEffectChecksum = owner.ownerEffectChecksum.value,
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			productIdentity = identity(
				AmbientStepsPortableIdentityKind.DAY,
				"other-day-product",
			).value,
			graphIdentity = graph.identity.value,
			fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_SELECTED_DELETE,
			collectedDataEpoch = EPOCH,
			fencedAtMs = lineage.receivedAtMs,
		)
		database.importedPortableStepsCountDomainDao().insertOwnerFences(listOf(conflict))

		shouldThrow<IllegalArgumentException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = null,
				updatedAtMs = lineage.receivedAtMs + 1L,
			)
		}

		database.sourceEvidenceStateDao().get() shouldBe before
		dao.fence(lineage.day.identity.value) shouldBe null
		dao.archive(lineage.archive.identity.value) shouldBe lineage.archiveEntity
		database.importedPortableStepsCountDomainDao().ownerFences(
			listOf(owner.ownerIdentity.value),
			2,
		) shouldBe listOf(conflict)
	}

	@Test
	fun `epoch and revision exhaustion abort before ambient authority changes`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				revision = Long.MAX_VALUE,
				collectedDataEpoch = EPOCH,
			),
		)
		val revisionLineage = seedLineage(
			"revision-overflow",
			LocalDate.of(2026, 4, 1),
			EPOCH,
			4L,
		)

		shouldThrow<ArithmeticException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = null,
				updatedAtMs = revisionLineage.receivedAtMs + 1L,
			)
		}
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.revision shouldBe Long.MAX_VALUE
			state.collectedDataEpoch shouldBe EPOCH
		}
		dao.fenceCount() shouldBe 0L
		dao.archiveCount() shouldBe 1L

		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET revision = 0, collected_data_epoch = ? WHERE id = 1",
			arrayOf(Long.MAX_VALUE),
		)
		shouldThrow<ArithmeticException> {
			AppDatabase.deleteAllCollectedData(database)
		}
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.revision shouldBe 0L
			state.collectedDataEpoch shouldBe Long.MAX_VALUE
		}
		dao.fenceCount() shouldBe 0L
		dao.archiveCount() shouldBe 1L
	}

	private fun openFileDatabase(): AppDatabase = AppDatabase.fileBuilder(
		context,
		FILE_DATABASE_NAME,
	)
		.allowMainThreadQueries()
		.build()

	private fun reopenFileDatabase() {
		database.close()
		database = openFileDatabase()
		dao = database.importedAmbientStepsDao()
	}

	private suspend fun seedLineage(
		tag: String,
		date: LocalDate,
		epoch: Long,
		stepCount: Long,
	): AmbientLineage {
		val lineage = fixture(tag, date, epoch, stepCount)
		insertLineage(lineage)
		return lineage
	}

	private suspend fun insertLineage(lineage: AmbientLineage) {
		dao.insertArchive(lineage.archiveEntity)
		dao.insertDayRevision(lineage.dayRevision)
		dao.insertFacts(listOf(lineage.factEntity))
		dao.insertArchiveDays(listOf(lineage.archiveDay))
		dao.insertReceipt(lineage.receipt)
	}

	private suspend fun assertCountDomainOwnerFences(
		day: PortableAmbientStepsDayV1,
		epoch: Long,
	) {
		val graph = day.withExplicitUnprovenCountDomain().countDomainGraph
		database.importedPortableStepsCountDomainDao().ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		).also { fences ->
			fences.map { it.ownerIdentity }.toSet() shouldBe
				graph.roots.map { it.ownerIdentity.value }.toSet()
			fences.all {
				it.fenceKind ==
					com.adsamcik.tracker.shared.base.database.data
						.ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR &&
					it.collectedDataEpoch == epoch
			} shouldBe true
		}
	}

	@Suppress("LongMethod")
	private fun fixture(
		tag: String,
		date: LocalDate,
		epoch: Long,
		stepCount: Long,
	): AmbientLineage {
		val startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
		val endMs = date.plusDays(1L).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
		val fact = PortableAmbientStepsFactV1.create(
			identity(AmbientStepsPortableIdentityKind.FACT, "fact-$tag"),
			startMs,
			endMs,
			stepCount,
		)
		val day = PortableAmbientStepsDayV1.create(
			identity = identity(AmbientStepsPortableIdentityKind.DAY, "day-$tag"),
			structuralEpochDay = date.toEpochDay(),
			storedZoneId = "UTC",
			structuralDayStartTimeMs = startMs,
			structuralDayEndTimeMs = endMs,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			partialCauses = emptyList(),
			retainedStepCount = stepCount,
			facts = listOf(fact),
			gaps = emptyList(),
		)
		val archive = PortableAmbientStepsArchiveV1.create(listOf(day))
		val jobId = "job-$tag"
		val archiveKey = "archive-$tag"
		val receiptIdentity = ImportedAmbientStepsIdentity.receipt(jobId, archiveKey)
		return AmbientLineage(
			archive = archive,
			day = day,
			archiveEntity = ImportedAmbientStepsArchiveEntity(
				archiveIdentity = archive.identity.value,
				contentChecksum = archive.contentChecksum.value,
				sourceFormat = AmbientStepsPortableFormatV1.FORMAT,
				sourceSchemaVersion = AmbientStepsPortableFormatV1.SCHEMA_VERSION,
				encodedByteCount = 512L,
				dayCount = 1,
				factCount = 1,
				gapCount = 0,
				collectedDataEpoch = epoch,
				firstReceivedAtMs = endMs,
			),
			dayRevision = ImportedAmbientStepsDayRevisionEntity(
				dayIdentity = day.identity.value,
				importRevision = 1L,
				supersedesImportRevision = null,
				archiveIdentity = archive.identity.value,
				dayContentChecksum = day.contentChecksum.value,
				deletionScopeIdentity = day.deletionScopeIdentity.value,
				structuralEpochDay = date.toEpochDay(),
				storedZoneId = "UTC",
				structuralDayStartTimeMs = startMs,
				structuralDayEndTimeMs = endMs,
				retainedFromTimeMs = null,
				coverage = PortableAmbientStepsCoverage.COMPLETE.name,
				partialCauses = "",
				retainedStepCount = stepCount,
				factCount = 1,
				gapCount = 0,
				collectedDataEpoch = epoch,
				receivedAtMs = endMs,
			),
			factEntity = ImportedAmbientStepsFactEntity(
				dayIdentity = day.identity.value,
				dayImportRevision = 1L,
				factIdentity = fact.identity.value,
				contentChecksum = fact.contentChecksum.value,
				intervalStartTimeMs = startMs,
				intervalEndTimeMs = endMs,
				stepCount = stepCount,
			),
			archiveDay = ImportedAmbientStepsArchiveDayEntity(
				archiveIdentity = archive.identity.value,
				ordinal = 0,
				dayIdentity = day.identity.value,
				dayContentChecksum = day.contentChecksum.value,
				boundDayImportRevision = 1L,
				factCount = 1,
				gapCount = 0,
			),
			receipt = ImportedAmbientStepsReceiptEntity(
				importJobId = jobId,
				archiveKey = archiveKey,
				receiptIdentity = receiptIdentity,
				sourceName = "backup-$tag.trackerambientsteps",
				receivedAtMs = endMs,
				archiveIdentity = archive.identity.value,
				archiveContentChecksum = archive.contentChecksum.value,
				collectedDataEpoch = epoch,
			),
			protectedIdentities = listOf(
				archive.identity.value,
				day.identity.value,
				fact.identity.value,
				day.deletionScopeIdentity.value,
				receiptIdentity,
			),
			receivedAtMs = endMs,
		)
	}

	private suspend fun assertPayloadCleared() {
		dao.archiveCount() shouldBe 0L
		dao.receiptCount() shouldBe 0L
		dao.archiveDayCount() shouldBe 0L
		dao.dayRevisionCount() shouldBe 0L
		dao.factCount() shouldBe 0L
		dao.gapCount() shouldBe 0L
	}

	private suspend fun assertFence(
		lineage: AmbientLineage,
		epoch: Long,
		revision: Long,
	) {
		requireNotNull(dao.fence(lineage.day.identity.value)).also { fence ->
			fence.fenceKind shouldBe ImportedAmbientStepsDayFenceEntity.FENCE_FULL_CLEAR
			fence.collectedDataEpoch shouldBe epoch
			fence.sourceEvidenceRevision shouldBe revision
		}
	}

	private suspend fun assertSourceFence(
		expected: ImportedAmbientStepsSourceFenceEntity,
		epoch: Long,
	) {
		dao.sourceFence() shouldBe ImportedAmbientStepsSourceFenceEntity.reepoch(expected, epoch)
	}

	private suspend fun assertProtected(lineage: AmbientLineage) {
		val retained = dao.protectedIdentityOwners(
			lineage.protectedIdentities,
			lineage.protectedIdentities.size + 1,
		)
		retained.map { it.protectedIdentity }.toSet() shouldBe lineage.protectedIdentities.toSet()
		retained.map { it.identityKind }.toSet() shouldBe setOf(
			ImportedAmbientStepsProtectedIdentityEntity.ARCHIVE,
			ImportedAmbientStepsProtectedIdentityEntity.DAY,
			ImportedAmbientStepsProtectedIdentityEntity.FACT,
			ImportedAmbientStepsProtectedIdentityEntity.DELETION_SCOPE,
			ImportedAmbientStepsProtectedIdentityEntity.RECEIPT,
		)
	}

	private fun Throwable.hasMessageInCauseChain(marker: String): Boolean =
		generateSequence(this) { it.cause }.any { it.message?.contains(marker) == true }

	private data class AmbientLineage(
		val archive: PortableAmbientStepsArchiveV1,
		val day: PortableAmbientStepsDayV1,
		val archiveEntity: ImportedAmbientStepsArchiveEntity,
		val dayRevision: ImportedAmbientStepsDayRevisionEntity,
		val factEntity: ImportedAmbientStepsFactEntity,
		val archiveDay: ImportedAmbientStepsArchiveDayEntity,
		val receipt: ImportedAmbientStepsReceiptEntity,
		val protectedIdentities: List<String>,
		val receivedAtMs: Long,
	)

	private companion object {
		const val EPOCH = 7L
		const val FILE_DATABASE_NAME = "imported-ambient-steps-full-clear"
		const val ROLLBACK_MARKER = "reject ambient archive deletion"
	}
}
