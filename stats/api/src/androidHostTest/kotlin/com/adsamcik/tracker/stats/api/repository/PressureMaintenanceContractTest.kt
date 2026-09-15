package com.adsamcik.tracker.stats.api.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PressureMaintenanceContractTest {
	@Test
	fun `retention request consumes one exact published authority snapshot`() {
		TruncateImportedPressureRetentionRequest(
			expectedCollectedDataEpoch = 7L,
			expectedSourceEvidenceRevision = 4L,
			retainedFromMs = 1_000L,
			retainedAtMs = 2_000L,
		).expectedSourceEvidenceRevision shouldBe 4L

		shouldThrow<IllegalArgumentException> {
			TruncateImportedPressureRetentionRequest(7L, -1L, 1_000L, 2_000L)
		}
	}

	@Test
	fun `source erase contract returns exact origin-specific counts`() = runTest {
		val expected = ErasePressureSourceResult.Erased(
			localFactRevisionCount = 2,
			localWalEventCount = 1,
			legacySampleCount = 3,
			importedEntryCount = 1,
			importedRevisionCount = 2,
			importedRunCount = 2,
			importedWindowCount = 4,
			fencedLocalRunCount = 1,
		)
		val service = object : ErasePressureSource {
			override suspend fun erase(
				request: ErasePressureSourceRequest,
			): ErasePressureSourceResult = expected
		}

		service.erase(
			ErasePressureSourceRequest(7L, 4L, 9L, 5L, 8L, 2_000L),
		) shouldBe expected
	}

	@Test
	fun `barrier distinguishes import-only erase from settled local provider`() = runTest {
		val importOnly = object : PressureSourceEraseBarrier {
			override suspend fun establish(
				expectedCollectedDataEpoch: Long,
			): PressureSourceEraseBarrierResult = PressureSourceEraseBarrierResult.NoLocalProvider(
				PressureSourceEraseBarrierToken(expectedCollectedDataEpoch, null, 1L),
			)

			override suspend fun verifySettled(
				token: PressureSourceEraseBarrierToken,
			): PressureSourceEraseBarrierVerification =
				PressureSourceEraseBarrierVerification.Verified
		}

		importOnly.establish(7L) shouldBe PressureSourceEraseBarrierResult.NoLocalProvider(
			PressureSourceEraseBarrierToken(7L, null, 1L),
		)
	}
}
