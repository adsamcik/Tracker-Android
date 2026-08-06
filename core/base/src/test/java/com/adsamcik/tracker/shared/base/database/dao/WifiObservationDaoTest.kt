package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiObservationDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: WifiObservationDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.wifiObservationDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `scan summary counts durable scan identities instead of timestamp collisions`() = runTest {
		dao.insert(
			listOf(
				observation(sourceSignalId = "scan-a", sourceItemIndex = 0, bssid = "00:00:00:00:00:01"),
				observation(sourceSignalId = "scan-a", sourceItemIndex = 1, bssid = "00:00:00:00:00:02"),
				// A separate scan can share the same millisecond on batched/fast callbacks.
				observation(sourceSignalId = "scan-b", sourceItemIndex = 0, bssid = "00:00:00:00:00:03"),
			),
		)

		val summary = dao.getScanSummary()
		summary.totalObservations shouldBe 3L
		summary.distinctScanTimes shouldBe 2L
	}

	private fun observation(
		sourceSignalId: String,
		sourceItemIndex: Int,
		bssid: String,
	) = WifiObservation(
		timeMs = 1_700_000_000_000L,
		bssid = bssid,
		ssid = "test",
		capabilities = "[WPA2]",
		frequency = 2_412,
		level = -60,
		latE7 = null,
		lonE7 = null,
		provenance = CoordinateProvenance.UNKNOWN,
		createdAt = 1_700_000_000_000L,
		sourceSignalId = sourceSignalId,
		sourceItemIndex = sourceItemIndex,
	)
}
