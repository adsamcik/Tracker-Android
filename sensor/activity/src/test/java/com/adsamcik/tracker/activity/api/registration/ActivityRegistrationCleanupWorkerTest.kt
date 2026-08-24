package com.adsamcik.tracker.activity.api.registration

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActivityRegistrationCleanupWorkerTest {
	private val arbiter = mockk<ActivityRegistrationArbiter>()

	@Test
	fun `completed cleanup never opens durable demand reconciliation`() = runTest {
		coEvery { arbiter.retryPendingProviderCleanup() } returns ActivityProviderCleanupResult.COMPLETE

		runActivityRegistrationCleanup(arbiter) shouldBe
			ActivityRegistrationCleanupWorkOutcome.COMPLETE

		coVerify(exactly = 0) { arbiter.reconcileDurableDemands() }
	}

	@Test
	fun `retryable cleanup preserves unique work retry`() = runTest {
		coEvery { arbiter.retryPendingProviderCleanup() } returns ActivityProviderCleanupResult(
			complete = false,
			pendingCount = 1,
			retryable = true,
			failureCode = ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
		)

		runActivityRegistrationCleanup(arbiter) shouldBe
			ActivityRegistrationCleanupWorkOutcome.RETRY

		coVerify(exactly = 0) { arbiter.reconcileDurableDemands() }
	}

	@Test
	fun `corrupt journal terminates work and keeps Activity failed closed`() = runTest {
		coEvery { arbiter.retryPendingProviderCleanup() } returns ActivityProviderCleanupResult(
			complete = false,
			pendingCount = 0,
			retryable = false,
			failureCode = ActivityRegistrationFailureCode.PROVIDER_CLEANUP_STATE_INVALID,
		)

		runActivityRegistrationCleanup(arbiter) shouldBe
			ActivityRegistrationCleanupWorkOutcome.FAILED

		coVerify(exactly = 0) { arbiter.reconcileDurableDemands() }
	}

}
