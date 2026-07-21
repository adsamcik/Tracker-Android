package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalClaimDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end recovery test exercising the *real* [DurableSignalBuffer] + WAL
 * fake DAO to prove cross-session recovery: rows checkpointed under one session
 * are replayed and acknowledged when [PersistenceProcessor] restarts under a
 * different (newly minted) session id — the real-world process-death scenario,
 * since each session gets a fresh Room autoincrement id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersistenceRecoveryCrossSessionTest {

	private class FakePendingSignalDao : PendingSignalDao {
		val store = mutableListOf<PendingSignalEntity>()
		private var nextId = 1L

		override suspend fun insertAll(signals: List<PendingSignalEntity>): List<Long> =
			signals.map { signal ->
				val id = nextId++
				store.add(signal.copy(id = id))
				id
			}

		override suspend fun insertAllIgnoringSignalIdConflicts(
			signals: List<PendingSignalEntity>,
		): List<Long> = signals.map { signal ->
			val existing = store.firstOrNull { it.signalId == signal.signalId }
			if (existing != null) {
				-1L
			} else {
				val id = nextId++
				store.add(signal.copy(id = id))
				id
			}
		}

		override suspend fun getBySignalIds(signalIds: List<String>): List<PendingSignalEntity> =
			store.filter { it.signalId in signalIds }

		override suspend fun getOldest(sessionId: Long, limit: Int): List<PendingSignalEntity> =
			store.filter { it.sessionId == sessionId }
				.sortedBy(PendingSignalEntity::id)
				.take(limit)

		override suspend fun getOldestAcrossSessions(limit: Int): List<PendingSignalEntity> =
			store.sortedBy(PendingSignalEntity::id).take(limit)

		override suspend fun deleteByIds(ids: List<Long>) {
			store.removeAll { it.id in ids }
		}

		override suspend fun countByIds(ids: List<Long>): Int =
			store.count { it.id in ids }

		override suspend fun deleteBySession(sessionId: Long) {
			store.removeAll { it.sessionId == sessionId }
		}

		override suspend fun countForSession(sessionId: Long): Int =
			store.count { it.sessionId == sessionId }

		override suspend fun countAll(): Int = store.size

		override suspend fun hasAny(): Boolean = store.isNotEmpty()

		override fun deleteAll() {
			store.clear()
		}
	}

	private class FakePendingSignalClaimDao(
		private val pending: FakePendingSignalDao,
	) : PendingSignalClaimDao {
		private val quarantined = mutableListOf<QuarantinedSignalEntity>()

		override suspend fun selectClaimable(nowMs: Long, limit: Int): List<PendingSignalEntity> =
			pending.store
				.filter { row ->
					val expiresAt = row.claimExpiresAt
					row.claimToken == null || expiresAt == null || expiresAt <= nowMs
				}
				.sortedBy(PendingSignalEntity::id)
				.take(limit)

		override suspend fun claimIds(
			ids: List<Long>,
			claimToken: String,
			nowMs: Long,
			leaseExpiresAtMs: Long,
		): Int {
			var claimed = 0
			pending.store.replaceAll { row ->
				val expiresAt = row.claimExpiresAt
				if (
					row.id in ids &&
					(row.claimToken == null || expiresAt == null || expiresAt <= nowMs)
				) {
					claimed++
					row.copy(
						claimToken = claimToken,
						claimExpiresAt = leaseExpiresAtMs,
						deliveryAttemptCount = row.deliveryAttemptCount + 1,
					)
				} else {
					row
				}
			}
			return claimed
		}

		override suspend fun getClaimedByToken(
			claimToken: String,
			limit: Int,
		): List<PendingSignalEntity> = pending.store
			.filter { it.claimToken == claimToken }
			.sortedBy(PendingSignalEntity::id)
			.take(limit)

		override suspend fun deleteClaimedByIds(ids: List<Long>, claimToken: String): Int {
			val matches = pending.store.count { it.id in ids && it.claimToken == claimToken }
			pending.store.removeAll { it.id in ids && it.claimToken == claimToken }
			return matches
		}

		override suspend fun releaseClaim(claimToken: String): Int {
			var released = 0
			pending.store.replaceAll { row ->
				if (row.claimToken == claimToken) {
					released++
					row.copy(claimToken = null, claimExpiresAt = null)
				} else {
					row
				}
			}
			return released
		}

		override suspend fun insertQuarantine(signal: QuarantinedSignalEntity): Long {
			if (quarantined.any { it.sourcePendingId == signal.sourcePendingId }) return -1L
			quarantined += signal.copy(id = quarantined.size.toLong() + 1L)
			return quarantined.last().id
		}

		override suspend fun hasQuarantineForSource(sourcePendingId: Long): Boolean =
			quarantined.any { it.sourcePendingId == sourcePendingId }
	}

	private val passthroughTransactor = object : TrackingPersistenceTransactor {
		override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
	}

	private fun locationSignal(timestampMs: Long) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		location = LocationSignal(
			coordinate = CoordinateE7(lat = LatE7.fromDegrees(50.0), lon = LonE7.fromDegrees(14.0)),
			horizontalAccuracyM = 5f,
			speed = SpeedMps(1.5f),
			provider = "fused",
		),
	)

	private fun pressureSignal(timestampMs: Long) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		pressure = PressureSignal(pressureHpa = 1013.25f, altitudeM = 120f),
	)

	@Test
	fun `rows written under session A are recovered and acknowledged when starting under session B`() =
			runTest {
			val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
			val fakeDao = FakePendingSignalDao()
			val claimDao = FakePendingSignalClaimDao(fakeDao)
			val durableBuffer = DurableSignalBuffer(fakeDao, dispatchers, claimDao)

			// --- Session A checkpoints two signals to the WAL, then "dies". ---
			val sessionA = 100L
			durableBuffer.setSessionId(sessionA)
			durableBuffer.stage(locationSignal(1_000_000L))
			durableBuffer.stage(pressureSignal(1_000_500L))
			durableBuffer.checkpoint()

			fakeDao.store shouldHaveSize 2
			fakeDao.store.all { it.sessionId == sessionA }

			val locationDao = mockk<LocationSampleDao>(relaxed = true)
			val locationObservationDao = mockk<LocationObservationDao>(relaxed = true)
			val cellDao = mockk<CellSampleDao>(relaxed = true)
			val wifiDao = mockk<WifiObservationDao>(relaxed = true)
			val pressureDao = mockk<PressureSampleDao>(relaxed = true)
			val stepDao = mockk<StepIntervalDao>(relaxed = true)
			val activityDao = mockk<ActivitySnapshotDao>(relaxed = true)
			val errorCollector = mockk<PersistenceErrorCollector>(relaxed = true)

			val processor = PersistenceProcessor(
				locationSampleDao = locationDao,
				locationObservationDao = locationObservationDao,
				cellSampleDao = cellDao,
				wifiObservationDao = wifiDao,
				pressureSampleDao = pressureDao,
				stepIntervalDao = stepDao,
				activitySnapshotDao = activityDao,
				pendingSignalDao = fakeDao,
				pendingSignalClaimDao = claimDao,
				durableBuffer = durableBuffer,
				transactor = passthroughTransactor,
				errorCollector = errorCollector,
			)

			// --- New process: PersistenceProcessor starts under a *different*
			// session id (session B). Recovery must still replay session A's rows.
			val sessionB = 101L
			processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L), sessionId = sessionB))

			// Both signals recovered into their destination tables.
			val locationCaptured = slot<Collection<LocationSample>>()
			coVerify(exactly = 1) { locationDao.insert(capture(locationCaptured)) }
			locationCaptured.captured shouldHaveSize 1

			val pressureCaptured = slot<Collection<PressureSample>>()
			coVerify(exactly = 1) { pressureDao.insert(capture(pressureCaptured)) }
			pressureCaptured.captured shouldHaveSize 1

			// The exact WAL rows (session A's) were acknowledged — none orphaned.
			fakeDao.store.shouldBeEmpty()
			coVerify(exactly = 0) { errorCollector.reportError(any<PersistenceError>()) }
		}
}
