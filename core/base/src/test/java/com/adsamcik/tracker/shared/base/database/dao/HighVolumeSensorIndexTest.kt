package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Query-plan regression guards for the highest-volume sensor tables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HighVolumeSensorIndexTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `latest step and retention seek by interval end`() {
		assertPlanUsesIndex(
			sql = "EXPLAIN QUERY PLAN SELECT * FROM step_interval ORDER BY end_time_ms DESC LIMIT 1",
			index = "idx_step_interval_end_time",
		)
		assertPlanUsesIndex(
			sql = "EXPLAIN QUERY PLAN DELETE FROM step_interval WHERE end_time_ms < ?",
			arguments = arrayOf<Any?>(1_000L),
			index = "idx_step_interval_end_time",
		)
	}

	@Test
	fun `cell export cursor seeks by stable time and id order`() {
		assertPlanUsesIndex(
			sql = """
				EXPLAIN QUERY PLAN
				SELECT * FROM cell_sample
				WHERE time_ms >= ? AND time_ms <= ?
					AND (time_ms > ? OR (time_ms = ? AND id > ?))
				ORDER BY time_ms ASC, id ASC
				LIMIT ?
			""".trimIndent(),
			arguments = arrayOf<Any?>(0L, Long.MAX_VALUE, 100L, 100L, 1L, 100),
			index = "idx_cell_sample_time_id",
		)
	}

	@Test
	fun `wifi export cursor and bssid history use composite indexes`() {
		assertPlanUsesIndex(
			sql = """
				EXPLAIN QUERY PLAN
				SELECT * FROM wifi_observation
				WHERE time_ms >= ? AND time_ms <= ?
					AND (time_ms > ? OR (time_ms = ? AND id > ?))
				ORDER BY time_ms ASC, id ASC
				LIMIT ?
			""".trimIndent(),
			arguments = arrayOf<Any?>(0L, Long.MAX_VALUE, 100L, 100L, 1L, 100),
			index = "idx_wifi_obs_time_id",
		)
		assertPlanUsesIndex(
			sql = """
				EXPLAIN QUERY PLAN
				SELECT * FROM wifi_observation
				WHERE bssid = ? AND time_ms >= ? AND time_ms <= ?
				ORDER BY time_ms
			""".trimIndent(),
			arguments = arrayOf<Any?>("AA:BB:CC:DD:EE:FF", 0L, Long.MAX_VALUE),
			index = "idx_wifi_obs_bssid_time",
		)
	}

	private fun assertPlanUsesIndex(
		sql: String,
		arguments: Array<Any?> = emptyArray(),
		index: String,
	) {
		val plan = buildList {
			database.openHelper.readableDatabase.query(sql, arguments).use { cursor: Cursor ->
				val detailColumn = cursor.getColumnIndexOrThrow("detail")
				while (cursor.moveToNext()) add(cursor.getString(detailColumn))
			}
		}
		check(plan.any { it.contains(index) }) {
			"Query plan does not use $index:\n${plan.joinToString("\n")}"
		}
	}
}
