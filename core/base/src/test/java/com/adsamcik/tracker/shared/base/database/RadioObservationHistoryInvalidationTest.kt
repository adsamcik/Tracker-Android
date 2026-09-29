package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioObservationHistoryInvalidationTest {
	@Test fun `legacy radio child write refreshes unified history observer`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
		try {
			val required = setOf(
				"wifi_captured_child", "cell_captured_child", "ambient_wifi_child",
				"ambient_cell_child", "legacy_wifi_child", "legacy_cell_child",
				"imported_wifi_child", "imported_cell_child",
				"imported_ambient_wifi_child", "imported_ambient_cell_child",
				"ambient_wifi_fact_revision", "ambient_cell_fact_revision",
				"imported_ambient_wifi_fact", "imported_ambient_cell_fact",
			)
			TRACKING_HISTORY_INVALIDATION_TABLES.toSet().containsAll(required) shouldBe true
			val firstRead = CompletableDeferred<Unit>()
			val observed = async {
				database.invalidationTracker.createFlow(
					*TRACKING_HISTORY_INVALIDATION_TABLES.toTypedArray(),
					emitInitialState = true,
				).map { invalidated ->
					firstRead.complete(Unit)
					invalidated
				}.take(2).toList()
			}
			withContext(Dispatchers.Default) { withTimeout(5_000L) { firstRead.await() } }
			database.openHelper.writableDatabase.execSQL(
				"INSERT INTO legacy_wifi_child " +
					"(legacy_row_id,time_ms,bssid,ssid,capabilities,frequency,level," +
					"provenance,created_at) VALUES (1,1000,'aa:bb','Cafe','',2412,-63,'UNKNOWN',1000)",
			)
			database.invalidationTracker.refreshAsync()
			runCurrent()
			val emissions = withContext(Dispatchers.Default) { withTimeout(5_000L) { observed.await() } }
			emissions.last().contains("legacy_wifi_child") shouldBe true
		} finally {
			database.close()
		}
	}
}
