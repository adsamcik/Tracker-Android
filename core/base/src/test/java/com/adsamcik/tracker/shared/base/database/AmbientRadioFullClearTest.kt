package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Full-clear contract for parent-owned AppDatabase integration. */
@RunWith(RobolectricTestRunner::class)
class AmbientRadioFullClearTest {
	private lateinit var database: AppDatabase

	@BeforeTest
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `full clear removes both ambient radio authorities and fact stores`() = runTest {
		database.ambientWifiFactDao().insertAuthority(
			AmbientWifiAuthorityIntegrity.create(
				1L,
				AmbientWifiAuthorityEntity.STATE_ACTIVE,
				1L,
				1L,
				"privacy:wifi:ambient:v1",
				1L,
				0L,
				0L,
				"boot-1",
				1L,
				1L,
			),
		)
		database.ambientCellFactDao().insertAuthority(
			AmbientCellAuthorityIntegrity.create(
				1L,
				AmbientCellAuthorityEntity.STATE_ACTIVE,
				1L,
				1L,
				"privacy:cell:ambient:v1",
				1L,
				0L,
				0L,
				"boot-1",
				1L,
				1L,
			),
		)

		database.clearAllTables()

		assertNull(database.ambientWifiFactDao().latestAuthority())
		assertNull(database.ambientCellFactDao().latestAuthority())
		assertEquals(0L, database.ambientWifiFactDao().localFactCount())
		assertEquals(0L, database.ambientCellFactDao().localFactCount())
		assertEquals(0L, database.ambientWifiFactDao().importedFactCount())
		assertEquals(0L, database.ambientCellFactDao().importedFactCount())
		assertEquals(0L, database.ambientWifiFactDao().importedGapCount())
		assertEquals(0L, database.ambientCellFactDao().importedGapCount())
	}
}
