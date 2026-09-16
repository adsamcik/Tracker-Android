package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WifiHistoryDeletionTest {
	@Test
	fun `selected deletion request preserves exact origin and imported revision`() {
		val imported = WifiHistorySelection.Imported(
			WifiImportedHistorySelection(
				WifiImportedHistorySelectionKey("a".repeat(64)),
				2L,
				"b".repeat(64),
			),
		)
		val request = DeleteSelectedWifiHistoryRequest(imported, 4L, 5L)

		assertEquals(imported, request.selection)
		assertEquals(
			DeleteSelectedWifiHistoryResult.AlreadyDeleted(WifiHistoryOrigin.IMPORTED),
			DeleteSelectedWifiHistoryResult.AlreadyDeleted(request.selection.origin),
		)
	}

	@Test
	fun `selected deletion rejects invalid epoch or time`() {
		val local = WifiHistorySelection.Local(WifiLocalHistorySelectionKey("c".repeat(64)))
		assertFailsWith<IllegalArgumentException> {
			DeleteSelectedWifiHistoryRequest(local, -1L, 0L)
		}
		assertFailsWith<IllegalArgumentException> {
			DeleteSelectedWifiHistoryRequest(local, 0L, -1L)
		}
	}
}
