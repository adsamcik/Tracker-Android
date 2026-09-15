package com.adsamcik.tracker.tracker.source.pressure

import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierVerification
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseSettlement
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseVerification
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RuntimePressureSourceEraseBarrierTest {
	@Test
	fun `adapter combines provider settlement with exact legacy fence token`() = runTest {
		val runtime = mockk<PressureSourceRuntime>()
		coEvery { runtime.establishSourceEraseBarrier(3L) } returns
			PressureProviderEraseSettlement.Settled(7L)
		val boundary = object : LegacyPressureWriterLifecycleBarrier {
			override suspend fun establish(
				expectedCollectedDataEpoch: Long,
				settleProvider: suspend () -> PressureProviderEraseSettlement,
			): PressureSourceEraseBarrierResult =
				settleProvider().toBarrierResult(expectedCollectedDataEpoch, 11L)

			override suspend fun verifySettled(
				token: PressureSourceEraseBarrierToken,
				verifyProvider: suspend () -> PressureProviderEraseVerification,
			): PressureSourceEraseBarrierVerification = verifyProvider().toBarrierVerification()
		}
		val subject = RuntimePressureSourceEraseBarrier(runtime, boundary)

		subject.establish(3L) shouldBe PressureSourceEraseBarrierResult.Established(
			PressureSourceEraseBarrierToken(3L, 7L, 11L),
		)

		coVerify(exactly = 1) { runtime.establishSourceEraseBarrier(3L) }
	}

	@Test
	fun `verifySettled rechecks provider using the exact token inside caller transaction`() = runTest {
		val runtime = mockk<PressureSourceRuntime>()
		val token = PressureSourceEraseBarrierToken(3L, 7L, 11L)
		coEvery { runtime.verifySourceEraseProviderSettled(3L, 7L) } returns
			PressureProviderEraseVerification.Verified
		val boundary = object : LegacyPressureWriterLifecycleBarrier {
			override suspend fun establish(
				expectedCollectedDataEpoch: Long,
				settleProvider: suspend () -> PressureProviderEraseSettlement,
			): PressureSourceEraseBarrierResult = error("not used")

			override suspend fun verifySettled(
				token: PressureSourceEraseBarrierToken,
				verifyProvider: suspend () -> PressureProviderEraseVerification,
			): PressureSourceEraseBarrierVerification {
				token.legacyWriteFenceGeneration shouldBe 11L
				return verifyProvider().toBarrierVerification()
			}
		}

		RuntimePressureSourceEraseBarrier(runtime, boundary).verifySettled(token) shouldBe
			PressureSourceEraseBarrierVerification.Verified

		coVerify(exactly = 1) { runtime.verifySourceEraseProviderSettled(3L, 7L) }
	}
}
