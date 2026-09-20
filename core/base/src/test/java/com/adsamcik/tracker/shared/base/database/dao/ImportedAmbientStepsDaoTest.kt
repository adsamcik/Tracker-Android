package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.authenticateAllAmbientStepsFences
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedAmbientStepsLineage
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.deletionScopeIdentity
import com.adsamcik.tracker.shared.model.steps.portable.identity
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
class ImportedAmbientStepsDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: ImportedAmbientStepsDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
		dao = database.importedAmbientStepsDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `complete lineage authenticates and source fence survives payload cascade`() = runTest {
		val fact = PortableAmbientStepsFactV1.create(
			identity(AmbientStepsPortableIdentityKind.FACT, "fact"),
			0L,
			DAY_END,
			0L,
		)
		val day = PortableAmbientStepsDayV1.create(
			identity = identity(AmbientStepsPortableIdentityKind.DAY, "day"),
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = DAY_END,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			partialCauses = emptyList(),
			retainedStepCount = 0L,
			facts = listOf(fact),
			gaps = emptyList(),
		)
		val archive = PortableAmbientStepsArchiveV1.create(listOf(day))
		dao.insertArchive(
			ImportedAmbientStepsArchiveEntity(
				archive.identity.value,
				archive.contentChecksum.value,
				AmbientStepsPortableFormatV1.FORMAT,
				1,
				1,
				1,
				0,
				EPOCH,
				DAY_END,
			),
		)
		dao.insertDayRevision(
			ImportedAmbientStepsDayRevisionEntity(
				day.identity.value,
				1L,
				null,
				archive.identity.value,
				day.contentChecksum.value,
				day.deletionScopeIdentity.value,
				0L,
				"UTC",
				0L,
				DAY_END,
				null,
				"COMPLETE",
				"",
				0L,
				1,
				0,
				EPOCH,
				DAY_END,
			),
		)
		dao.insertFacts(listOf(
			ImportedAmbientStepsFactEntity(
				day.identity.value,
				1L,
				fact.identity.value,
				fact.contentChecksum.value,
				0L,
				DAY_END,
				0L,
			),
		))
		dao.insertArchiveDays(listOf(
			ImportedAmbientStepsArchiveDayEntity(
				archive.identity.value,
				0,
				day.identity.value,
				day.contentChecksum.value,
				1L,
				1,
				0,
			),
		))
		dao.insertReceipt(
			ImportedAmbientStepsReceiptEntity(
				"job",
				"archive",
				com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity.receipt(
					"job",
					"archive",
				),
				"backup.trackerambientsteps",
				DAY_END,
				archive.identity.value,
				archive.contentChecksum.value,
				512L,
				EPOCH,
			),
		)
		val lineage = dao.loadAuthenticatedAmbientStepsLineage(day.identity.value, EPOCH)
		lineage.latest.day shouldBe day
		val markers = lineage.protectedIdentities()
		val fence = ImportedAmbientStepsDayFenceEntity.create(
			dayIdentity = day.identity.value,
			deletionScopeIdentity = day.deletionScopeIdentity.value,
			fenceKind = ImportedAmbientStepsDayFenceEntity.FENCE_SELECTED_DELETE,
			collectedDataEpoch = EPOCH,
			sourceEvidenceRevision = 0L,
			fencedAtMs = DAY_END,
			retainedFromMs = null,
			latestImportRevision = 1L,
			latestContentChecksum = day.contentChecksum.value,
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = DAY_END,
			revisionCount = 1,
			archiveCount = 1,
			factRowCount = 1,
			gapRowCount = 0,
			protectedIdentities = markers,
			lineageChecksum = lineage.lineageChecksum,
		)
		dao.insertFence(fence)
		dao.insertProtectedIdentities(markers)
		dao.authenticateAllAmbientStepsFences(EPOCH)
		dao.deleteDayLineage(day.identity.value) shouldBe 1
		dao.deleteOrphanArchives() shouldBe 1

		dao.dayRevisionCount() shouldBe 0L
		dao.archiveCount() shouldBe 0L
		dao.fence(day.identity.value) shouldBe fence
		dao.protectedIdentitiesForDay(
			day.identity.value,
			ImportedAmbientStepsDao.MAX_PROTECTED_IDENTITIES_PER_DAY,
		).size shouldBe 5
	}

	private fun identity(kind: AmbientStepsPortableIdentityKind, value: String) =
		AmbientStepsPortableOpaqueIdentity.derive(kind, value)

	private companion object {
		const val EPOCH = 7L
		const val DAY_END = 86_400_000L
	}
}
