package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.ActivityCapturedSourceDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.ActivityCapturedSourceDeletionResult
import com.adsamcik.tracker.shared.base.database.EraseNextImportedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.PortableActivityTransferRetryableReason
import com.adsamcik.tracker.shared.base.database.RoomEraseNextImportedActivity
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseBlockedReason
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseRequest
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseResult
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseRetryableReason
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseUnverifiableReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomActivitySourceEraseTest {
	@Test
	fun `local revocation and quiescence block before imported mutation`() = runTest {
		val local = ActivityCapturedLocalErase {
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
			)
		}
		val imported = mockk<RoomEraseNextImportedActivity>()
		val subject = RoomActivitySourceErase(local, imported, StandardTestDispatcher(testScheduler))

		subject.erase(request()) shouldBe ActivitySourceEraseResult.Blocked(
			ActivitySourceEraseBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
		)
		coVerify(exactly = 0) { imported.eraseNext(any(), any()) }
	}

	@Test
	fun `local and one-lineage imported continuation report exact source counts`() = runTest {
		val local = ActivityCapturedLocalErase {
			ActivityCapturedSourceDeletionResult.Deleted(2, 3, 1, 2)
		}
		val imported = mockk<RoomEraseNextImportedActivity>()
		coEvery { imported.eraseNext(EPOCH, DELETED_AT) } returnsMany listOf(
			EraseNextImportedActivityResult.ErasedLive(2, 3),
			EraseNextImportedActivityResult.ErasedRetained(2, 5),
			EraseNextImportedActivityResult.Complete,
		)
		val subject = RoomActivitySourceErase(local, imported, StandardTestDispatcher(testScheduler))

		subject.erase(request()) shouldBe ActivitySourceEraseResult.Erased(
			localLogicalWindowCount = 2,
			localRevisionCount = 3,
			localRegistrationPlanCount = 1,
			localFencedServiceRunCount = 2,
			importedLiveEntryCount = 1,
			importedRetainedEntryCount = 1,
			importedPhysicalRunCount = 5,
		)
		coVerify(exactly = 3) { imported.eraseNext(EPOCH, DELETED_AT) }
	}

	@Test
	fun `durable partial imported progress remains explicit on retryable failure`() = runTest {
		val imported = mockk<RoomEraseNextImportedActivity>()
		coEvery { imported.eraseNext(EPOCH, DELETED_AT) } returnsMany listOf(
			EraseNextImportedActivityResult.ErasedLive(1, 1),
			EraseNextImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			),
		)
		val subject = RoomActivitySourceErase(
			ActivityCapturedLocalErase {
				ActivityCapturedSourceDeletionResult.Deleted(1, 1, 0, 1)
			},
			imported,
			StandardTestDispatcher(testScheduler),
		)

		subject.erase(request()) shouldBe ActivitySourceEraseResult.RetryableFailure(
			reason = ActivitySourceEraseRetryableReason.STORAGE_UNAVAILABLE,
			localProductErasedBeforeFailure = true,
			importedEntriesErasedBeforeFailure = 1,
		)
	}

	@Test
	fun `corrupt imported authority fails closed and cancellation propagates`() = runTest {
		val imported = mockk<RoomEraseNextImportedActivity>()
		coEvery { imported.eraseNext(EPOCH, DELETED_AT) } returns
			EraseNextImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT,
			)
		val subject = RoomActivitySourceErase(
			ActivityCapturedLocalErase { ActivityCapturedSourceDeletionResult.AlreadyDeleted },
			imported,
			StandardTestDispatcher(testScheduler),
		)
		subject.erase(request()) shouldBe ActivitySourceEraseResult.Unverifiable(
			ActivitySourceEraseUnverifiableReason.IMPORTED_ORIGIN_IDENTITY_CONFLICT,
		)

		coEvery { imported.eraseNext(EPOCH, DELETED_AT) } throws
			CancellationException("cancelled")
		shouldThrow<CancellationException> { subject.erase(request()) }
	}

	@Test
	fun `empty local and imported stores are already erased without permanent retry`() = runTest {
		val imported = mockk<RoomEraseNextImportedActivity>()
		coEvery { imported.eraseNext(EPOCH, DELETED_AT) } returns
			EraseNextImportedActivityResult.Complete
		val subject = RoomActivitySourceErase(
			ActivityCapturedLocalErase { ActivityCapturedSourceDeletionResult.AlreadyDeleted },
			imported,
			StandardTestDispatcher(testScheduler),
		)

		subject.erase(request()) shouldBe ActivitySourceEraseResult.AlreadyErased
	}

	private fun request() = ActivitySourceEraseRequest(EPOCH, 9L, DELETED_AT)

	private companion object {
		const val EPOCH = 7L
		const val DELETED_AT = 10_000L
	}
}
