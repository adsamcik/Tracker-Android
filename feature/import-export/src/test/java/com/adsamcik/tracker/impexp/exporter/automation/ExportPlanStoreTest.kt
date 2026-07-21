package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.time.Clock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportPlanStoreTest {

	@Test
	fun `older composite watermark update does not regress stored progress`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val store = ExportPlanStore(
			context = context,
			ioDispatcher = Dispatchers.Unconfined,
			clock = object : Clock {
				override fun currentTimeMillis(): Long = 1_000L
				override fun elapsedRealtimeNanos(): Long = 0L
			},
		)
		store.plans.first().forEach { store.delete(it.id) }
		val plan = store.create(
			ExportBackupPlanDraft(
				name = "JSON",
				format = ExportFormat.JSON,
				cadence = ExportCadence.AfterSession,
				scope = ExportScope.EntireHistory,
				destination = ExportDestination.PrivateStorage(),
			),
		)

		store.updateWatermark(plan.id, 5_000L, 20L, 6_000L, 10)
		store.updateWatermark(plan.id, 6_000L, 1L, 7_000L, 4)
		store.updateWatermark(plan.id, 6_000L, 0L, 8_000L, 3)
		store.updateWatermark(plan.id, 5_999L, 99L, 9_000L, 2)

		val stored = store.getPlan(plan.id)!!
		stored.lastWatermarkMs shouldBe 6_000L
		stored.lastWatermarkId shouldBe 1L
		stored.lastCompletedAt shouldBe 7_000L
		stored.lastRecordCount shouldBe 4
	}
}
