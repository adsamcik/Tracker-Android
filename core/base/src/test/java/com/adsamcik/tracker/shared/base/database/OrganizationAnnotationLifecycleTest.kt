package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
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
class OrganizationAnnotationLifecycleTest {
	private lateinit var db: AppDatabase
	@Before fun setup() { db = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>()) }
	@After fun close() = db.close()
	@Test fun `actual parent full-clear removes annotations and advances epoch atomically`() = runTest {
		db.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		db.organizationAnnotationDao().insert(annotation("named", 100, 200))
		AppDatabase.deleteAllCollectedData(db, collectedDataEpoch = 1, retainedFromMs = null, updatedAtMs = 1_000)
		db.organizationAnnotationDao().byId("named") shouldBe null
		db.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
	}
	@Test fun `approved trip cutoff expires metadata while crossing groups keep original bounds`() = runTest {
		val dao = db.organizationAnnotationDao()
		dao.insert(annotation("expired", 100, 200))
		dao.insert(annotation("crossing", 100, 300))
		db.withTransaction { dao.purgeFreeBefore(200); dao.suppressExpiredOverrides(200, 1_000); dao.pruneUnusedSuppression() }
		dao.byId("expired") shouldBe null
		dao.byId("crossing")?.startTimeMs shouldBe 100L
		dao.byId("crossing")?.endTimeMs shouldBe 300L
	}
	@Test fun `reference label sequence is safe but unknown or source sequence highwaters remain blocked`() = runTest {
		db.activityDao().insert(SessionActivity(name = "Custom"))
		(db.bootstrapSourceEvidenceForRetention(0, null, 1) is SourceEvidenceRetentionBootstrapResult.Ready) shouldBe true
		db.openHelper.writableDatabase.execSQL("DELETE FROM source_evidence_state")
		db.openHelper.writableDatabase.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES(NULL,5)")
		db.bootstrapSourceEvidenceForRetention(0, null, 2) shouldBe SourceEvidenceRetentionBootstrapResult.ConflictingEvidence
		db.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence WHERE name IS NULL")
		db.openHelper.writableDatabase.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES('session_segment',5)")
		db.bootstrapSourceEvidenceForRetention(0, null, 3) shouldBe SourceEvidenceRetentionBootstrapResult.ConflictingEvidence
	}
	private fun annotation(id: String, from: Long, to: Long) = OrganizationAnnotationEntity(id, 1, 1, 1,
		null, from, to, "UTC", "Private name", null, false)
}
