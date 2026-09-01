package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
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
class SourceDeletionFenceDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: SourceDeletionFenceDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.sourceDeletionFenceDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun logicalRunIdentityIsStableOpaqueAndScopeSensitive() {
		val first = identity(logicalTrackingId = "logical-secret", serviceRunId = "run-secret")

		first shouldBe identity("logical-secret", "run-secret")
		first.length shouldBe 64
		first.contains("logical-secret") shouldBe false
		first.contains("run-secret") shouldBe false
		(first == identity("logical-secret", "other-run")) shouldBe false
	}

	@Test
	fun fenceUpsertAdvancesTheSameOpaqueScopeWithoutCreatingAnotherRow() = runTest {
		val first = fence(generation = 1L, epoch = 3L, deletedAtMs = 100L)
		val second = fence(generation = 2L, epoch = 4L, deletedAtMs = 200L)

		dao.upsert(first)
		dao.contains(
			sourceKind = SOURCE_STEPS,
			purpose = PURPOSE_SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = first.scopeIdentityDigest,
		) shouldBe true
		dao.upsert(second)

		dao.countAll() shouldBe 1L
		dao.get(
			sourceKind = SOURCE_STEPS,
			purpose = PURPOSE_SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = first.scopeIdentityDigest,
		) shouldBe second
	}

	@Test
	fun insertIfAbsentNeverReplacesTheFirstMonotonicScopeAuthority() = runTest {
		val first = fence(generation = 4L, epoch = 7L, deletedAtMs = 200L)
		val staleReplacement = fence(generation = 1L, epoch = 2L, deletedAtMs = 100L)

		(dao.insertIfAbsent(first) > 0L) shouldBe true
		dao.insertIfAbsent(staleReplacement) shouldBe -1L

		dao.countAll() shouldBe 1L
		dao.get(
			sourceKind = SOURCE_STEPS,
			purpose = PURPOSE_SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = first.scopeIdentityDigest,
		) shouldBe first
	}

	@Test
	fun entityRejectsAChangedAuthorityWithAStaleChecksum() {
		val valid = fence(generation = 1L, epoch = 3L, deletedAtMs = 100L)

		shouldThrow<IllegalArgumentException> {
			valid.copy(fenceGeneration = 2L)
		}
	}

	private fun identity(logicalTrackingId: String, serviceRunId: String) =
		SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SOURCE_STEPS,
			purpose = PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)

	private fun fence(generation: Long, epoch: Long, deletedAtMs: Long) =
		SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = SOURCE_STEPS,
			purpose = PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = "logical-secret",
			serviceRunId = "run-secret",
			fenceGeneration = generation,
			collectedDataEpoch = epoch,
			deletedAtMs = deletedAtMs,
		)

	private companion object {
		const val SOURCE_STEPS = 3
		const val PURPOSE_SESSION_CAPTURE = "SESSION_CAPTURE"
	}
}
