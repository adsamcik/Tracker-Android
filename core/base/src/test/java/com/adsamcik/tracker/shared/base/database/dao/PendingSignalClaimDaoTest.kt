package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingSignalClaimDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var pendingDao: PendingSignalDao
	private lateinit var claimDao: PendingSignalClaimDao
	private lateinit var quarantineDao: QuarantinedSignalDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		pendingDao = database.pendingSignalDao()
		claimDao = database.pendingSignalClaimDao()
		quarantineDao = database.quarantinedSignalDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `claim is compare-and-set, ordered by admission id, and increments attempts`() = runTest {
		pendingDao.insertAll(
			listOf(
				pending("signal-1", 3_000L),
				pending("signal-2", 1_000L),
				pending("signal-3", 2_000L),
			),
		)

		val firstOwner = claimDao.claimOldestAvailable(
			claimToken = "owner-a",
			nowMs = 10_000L,
			leaseExpiresAtMs = 20_000L,
			limit = 2,
		)
		firstOwner.map(PendingSignalEntity::signalId) shouldContainExactly listOf("signal-1", "signal-2")
		firstOwner.map(PendingSignalEntity::deliveryAttemptCount) shouldContainExactly listOf(1, 1)

		val secondOwner = claimDao.claimOldestAvailable(
			claimToken = "owner-b",
			nowMs = 10_001L,
			leaseExpiresAtMs = 20_001L,
			limit = 2,
		)
		secondOwner.map(PendingSignalEntity::signalId) shouldContainExactly listOf("signal-3")

		claimDao.deleteClaimedByIds(firstOwner.map(PendingSignalEntity::id), "wrong-owner") shouldBe 0
		pendingDao.countAll() shouldBe 3
		claimDao.deleteClaimedByIds(firstOwner.map(PendingSignalEntity::id), "owner-a") shouldBe 2
		pendingDao.countAll() shouldBe 1
	}

	@Test
	fun `admission resolves an already committed stable signal identity`() = runTest {
		val initial = pending("signal-ambiguous-retry", 1_000L).copy(
			capturedEpoch = 3L,
			acquiredAtMs = 900L,
		)
		val original = pendingDao.insertOrResolveEntities(listOf(initial)).single()

		// This models a retry after the original SQLite commit succeeded but the caller did not
		// receive the insert result. The retry may observe a newer lifecycle epoch, but it must use
		// the original durable row's metadata when deciding whether the WAL entry can publish.
		val resolved = pendingDao.insertOrResolveEntities(
			listOf(
				initial.copy(
					sessionId = 8L,
					createdAt = 2_000L,
					capturedEpoch = 4L,
					acquiredAtMs = 2_000L,
				),
			),
		).single()

		resolved.id shouldBe original.id
		resolved.capturedEpoch shouldBe 3L
		resolved.acquiredAtMs shouldBe 900L
		pendingDao.countAll() shouldBe 1
	}

	@Test
	fun `expired lease can be reclaimed and permanent failure moves atomically to quarantine`() = runTest {
		pendingDao.insertAll(listOf(pending("signal-1", 1_000L)))

		claimDao.claimOldestAvailable(
			claimToken = "expired-owner",
			nowMs = 1_000L,
			leaseExpiresAtMs = 2_000L,
			limit = 1,
		)
		val reclaimed = claimDao.claimOldestAvailable(
			claimToken = "current-owner",
			nowMs = 2_000L,
			leaseExpiresAtMs = 3_000L,
			limit = 1,
		).single()
		reclaimed.deliveryAttemptCount shouldBe 2

		claimDao.quarantineClaimed(
			signal = QuarantinedSignalEntity(
				sourcePendingId = reclaimed.id,
				signalId = reclaimed.signalId,
				sessionId = reclaimed.sessionId,
				envelopeVersion = reclaimed.envelopeVersion,
				payloadChecksum = reclaimed.payloadChecksum,
				signalJson = reclaimed.signalJson,
				createdAt = reclaimed.createdAt,
				acquiredAtMs = reclaimed.acquiredAtMs,
				deliveryAttemptCount = reclaimed.deliveryAttemptCount,
				failureReason = "malformed_payload",
				quarantinedAt = 2_001L,
			),
			claimToken = "current-owner",
		) shouldBe true

		pendingDao.countAll() shouldBe 0
		quarantineDao.countAll() shouldBe 1
	}

	@Test
	fun `live unclaimed permanent failure moves atomically to quarantine`() = runTest {
		pendingDao.insertAll(listOf(pending("live-invalid-steps", 1_000L)))
		val row = pendingDao.getBySignalIds(listOf("live-invalid-steps")).single()

		claimDao.quarantineUnclaimed(
			QuarantinedSignalEntity(
				sourcePendingId = row.id,
				signalId = row.signalId,
				sessionId = row.sessionId,
				envelopeVersion = row.envelopeVersion,
				payloadChecksum = row.payloadChecksum,
				signalJson = row.signalJson,
				createdAt = row.createdAt,
				acquiredAtMs = row.acquiredAtMs,
				deliveryAttemptCount = row.deliveryAttemptCount,
				failureReason = "STEPS_WRITER_PROVENANCE_INVALID",
				quarantinedAt = 1_001L,
			),
		) shouldBe true

		pendingDao.countAll() shouldBe 0
		quarantineDao.countAll() shouldBe 1
	}

	@Test
	fun `raw retention removes quarantine by source acquisition time`() = runTest {
		quarantineDao.insert(
			quarantined("expired", createdAt = 5_000L, acquiredAtMs = 999L, quarantinedAt = 5_001L),
		)
		quarantineDao.insert(
			quarantined("retained", createdAt = 500L, acquiredAtMs = 1_000L, quarantinedAt = 5_002L),
		)

		quarantineDao.deleteAcquiredBefore(1_000L) shouldBe 1

		quarantineDao.observeRecent().first().map { it.signalId } shouldContainExactly
			listOf("retained")
	}

	@Test
	fun `lost lease cannot create a quarantine record`() = runTest {
		pendingDao.insertAll(listOf(pending("signal-1", 1_000L)))

		val staleOwner = claimDao.claimOldestAvailable(
			claimToken = "stale-owner",
			nowMs = 1_000L,
			leaseExpiresAtMs = 2_000L,
			limit = 1,
		).single()
		claimDao.claimOldestAvailable(
			claimToken = "current-owner",
			nowMs = 2_000L,
			leaseExpiresAtMs = 3_000L,
			limit = 1,
		).single()

		claimDao.quarantineClaimed(
			signal = QuarantinedSignalEntity(
				sourcePendingId = staleOwner.id,
				signalId = staleOwner.signalId,
				sessionId = staleOwner.sessionId,
				envelopeVersion = staleOwner.envelopeVersion,
				payloadChecksum = staleOwner.payloadChecksum,
				signalJson = staleOwner.signalJson,
				createdAt = staleOwner.createdAt,
				acquiredAtMs = staleOwner.acquiredAtMs,
				deliveryAttemptCount = staleOwner.deliveryAttemptCount,
				failureReason = "malformed_payload",
				quarantinedAt = 2_001L,
			),
			claimToken = "stale-owner",
		) shouldBe false

		pendingDao.countAll() shouldBe 1
		quarantineDao.countAll() shouldBe 0
	}

	private fun pending(signalId: String, createdAt: Long) = PendingSignalEntity(
		signalId = signalId,
		sessionId = 7L,
		envelopeVersion = 1,
		payloadChecksum = "checksum-$signalId",
		signalJson = "{\"signal\":\"$signalId\"}",
		createdAt = createdAt,
	)

	private fun quarantined(
		signalId: String,
		createdAt: Long,
		acquiredAtMs: Long,
		quarantinedAt: Long,
	) = QuarantinedSignalEntity(
		sourcePendingId = createdAt,
		signalId = signalId,
		sessionId = 1L,
		envelopeVersion = 1,
		payloadChecksum = null,
		signalJson = "{}",
		createdAt = createdAt,
		acquiredAtMs = acquiredAtMs,
		deliveryAttemptCount = 1,
		failureReason = "test",
		quarantinedAt = quarantinedAt,
	)
}
