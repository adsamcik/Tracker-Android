package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmbientStepsHistoryRepositoryTest {
	@Test
	fun `range and recent contracts stay bounded and retain stored zone identity`() {
		val day = AmbientStepsStructuralDay(10L, "Europe/Prague")
		assertEquals(listOf(day), AmbientStepsHistoryRangeRequest(listOf(day)).days)
		assertFailsWith<IllegalArgumentException> {
			AmbientStepsHistoryRangeRequest(emptyList())
		}
		assertFailsWith<IllegalArgumentException> {
			AmbientStepsHistoryRangeRequest(
				List(AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT + 1) { index ->
					AmbientStepsStructuralDay(index.toLong(), "UTC")
				},
			)
		}
		assertFailsWith<IllegalArgumentException> {
			AmbientStepsHistoryRecentRequest(0)
		}
		assertFailsWith<IllegalArgumentException> {
			AmbientStepsHistoryRecentCursor(1L, 10L, "UTC", "not-opaque")
		}
	}

	@Test
	fun `covered zero and partial zero remain distinct typed values`() {
		assertEquals(0L, AmbientStepsHistoryValue.Exact(0L).count)
		val partial = AmbientStepsHistoryValue.Partial(
			0L,
			setOf(AmbientStepsHistoryCause.PARTIAL_COVERAGE),
		)
		assertEquals(0L, partial.count)
		assertEquals(setOf(AmbientStepsHistoryCause.PARTIAL_COVERAGE), partial.causes)
	}

	@Test
	fun `fact provenance requires kind matched archive ownership`() {
		val fact = "sha256:" + "a".repeat(64)
		val archive = "sha256:" + "b".repeat(64)
		assertEquals(
			archive,
			AmbientStepsHistoryFactOrigin(
				AmbientStepsHistoryFactOriginKind.PORTABLE_IMPORT,
				fact,
				1L,
				archive,
			).archiveIdentity,
		)
		assertFailsWith<IllegalArgumentException> {
			AmbientStepsHistoryFactOrigin(
				AmbientStepsHistoryFactOriginKind.PORTABLE_IMPORT,
				fact,
				1L,
			)
		}
		assertFailsWith<IllegalArgumentException> {
			AmbientStepsHistoryFactOrigin(
				AmbientStepsHistoryFactOriginKind.NATIVE_PROVIDER,
				fact,
				1L,
				archive,
			)
		}
	}
}
