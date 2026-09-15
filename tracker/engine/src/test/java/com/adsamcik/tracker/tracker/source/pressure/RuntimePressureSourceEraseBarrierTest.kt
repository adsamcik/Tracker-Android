package com.adsamcik.tracker.tracker.source.pressure

import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RuntimePressureSourceEraseBarrierTest {
	@Test
	fun `adapter forwards the exact collected-data epoch without adding authority`() = runTest {
		val runtime = mockk<PressureSourceRuntime>()
		val expected = PressureSourceEraseBarrierResult.Established(7L)
		coEvery { runtime.establishSourceEraseBarrier(3L) } returns expected

		RuntimePressureSourceEraseBarrier(runtime).establish(3L) shouldBe expected

		coVerify(exactly = 1) { runtime.establishSourceEraseBarrier(3L) }
	}
}
