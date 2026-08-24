package com.adsamcik.tracker.app

import com.adsamcik.tracker.tracker.permission.RuntimePermissionChange
import com.adsamcik.tracker.tracker.permission.RuntimePermissionSnapshot
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ApplicationRuntimePermissionChangeTest {
	@Test
	fun `activity control is reconciled after durable permission reconciliation`() = runTest {
		val events = mutableListOf<String>()
		val current = ALL_GRANTED.copy(hasActivityPermission = false)
		val change = RuntimePermissionChange(
			previous = ALL_GRANTED,
			current = current,
			fencedSources = emptySet(),
			fenceBoundary = null,
		)

		reconcileRuntimePermissionSnapshot(
			snapshot = current,
			reconcile = {
				events += "durable-fence"
				change
			},
			afterDurableReconciliation = { events += "activity-control" },
		) shouldBe change
		events shouldBe listOf("durable-fence", "activity-control")
	}

	@Test
	fun `foreground repair still runs after an unchanged permission snapshot`() = runTest {
		var revalidated = false

		reconcileRuntimePermissionSnapshot(
			snapshot = ALL_GRANTED,
			reconcile = { null },
			afterDurableReconciliation = { revalidated = true },
		) shouldBe null
		revalidated shouldBe true
	}

	private companion object {
		val ALL_GRANTED = RuntimePermissionSnapshot(
			hasLocationPermission = true,
			hasPreciseLocationPermission = true,
			hasCoarseLocationPermission = true,
			hasBackgroundLocationPermission = true,
			hasActivityPermission = true,
			hasReadPhonePermission = true,
			hasWifiScanPermission = true,
			hasCellScanPermission = true,
		)
	}
}
