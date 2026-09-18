package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectedDataDeletionOperationRoomTest {
	@Test
	fun `committed operation receipt prevents a repeated physical clear`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(context)
		try {
			val first = AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "delete-operation-1",
				collectedDataEpoch = 1L,
				retainedFromMs = 100L,
				updatedAtMs = 100L,
			)
			first.phase shouldBe
				CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED
			val revisionAfterClear = requireNotNull(database.sourceEvidenceStateDao().get()).revision
			database.pendingSignalDao().insertAll(
				listOf(
					PendingSignalEntity(
						signalId = "post-clear-sentinel",
						sessionId = 1L,
						envelopeVersion = 1,
						payloadChecksum =
							"1280fde14031e7b67bce77ff73860e29dbb51d1f3698679d28f2f93ed1beb128",
						signalJson = """{"type":"tracking_signal","payload":{"ts":1,"ern":0}}""",
						createdAt = 101L,
					),
				),
			)

			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "delete-operation-1",
				collectedDataEpoch = 1L,
				retainedFromMs = 100L,
				updatedAtMs = 100L,
			) shouldBe first

			database.pendingSignalDao().countAll() shouldBe 1
			requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe
				revisionAfterClear
		} finally {
			database.close()
		}
	}

	@Test
	fun `writer rearm phase advances only the exact deletion operation`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(context)
		try {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "delete-operation-2",
				collectedDataEpoch = 1L,
				retainedFromMs = 100L,
				updatedAtMs = 100L,
			)

			database.collectedDataDeletionOperationDao().compareAndSetPhase(
				operationId = "delete-operation-2",
				targetCollectedDataEpoch = 1L,
				expectedPhase = CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
				newPhase = CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED,
				updatedAtMs = 101L,
			) shouldBe 1
			database.collectedDataDeletionOperationDao().compareAndSetPhase(
				operationId = "delete-operation-2",
				targetCollectedDataEpoch = 1L,
				expectedPhase = CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
				newPhase = CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED,
				updatedAtMs = 102L,
			) shouldBe 0
		} finally {
			database.close()
		}
	}
}
