package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureFactRetentionAuthorityDaoTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `source erase retention pages seek the declared parent primary key`() {
		val primaryKeyIndex = database.openHelper.readableDatabase.query(
			"PRAGMA index_list('pressure_fact_retention_authority')",
		).use { cursor ->
			val name = cursor.getColumnIndexOrThrow("name")
			val origin = cursor.getColumnIndexOrThrow("origin")
			buildList {
				while (cursor.moveToNext()) {
					if (cursor.getString(origin) == "pk") add(cursor.getString(name))
				}
			}.single()
		}
		val primaryKeyColumns = buildList {
			database.openHelper.readableDatabase.query(
				"PRAGMA index_info('$primaryKeyIndex')",
			).use { cursor ->
				val name = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(name))
			}
		}
		primaryKeyColumns shouldBe listOf(
			"writer_projection_id",
			"writer_projection_version",
			"logical_fact_id",
		)

		val firstPlan = queryPlan(
			"EXPLAIN QUERY PLAN SELECT * FROM pressure_fact_retention_authority " +
				"WHERE writer_projection_id = ? AND writer_projection_version = ? " +
				"ORDER BY logical_fact_id LIMIT ?",
			arrayOf<Any?>(
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
				256,
			),
		)
		val nextPlan = queryPlan(
			"EXPLAIN QUERY PLAN SELECT * FROM pressure_fact_retention_authority " +
				"WHERE writer_projection_id = ? AND writer_projection_version = ? " +
				"AND logical_fact_id > ? ORDER BY logical_fact_id LIMIT ?",
			arrayOf<Any?>(
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
				"cursor",
				256,
			),
		)

		listOf(firstPlan, nextPlan).forEach { plan ->
			check(plan.none { detail ->
				detail.contains("SCAN pressure_fact_retention_authority") ||
					detail.contains("USE TEMP B-TREE")
			}) { "Pressure retention source-erase page is not an indexed keyset: $plan" }
			check(plan.any { detail ->
				detail.contains(primaryKeyIndex) && detail.contains("writer_projection_id=?") &&
					detail.contains("writer_projection_version=?")
			}) { "Pressure retention source-erase page did not seek the primary key: $plan" }
		}
	}

	private fun queryPlan(sql: String, arguments: Array<Any?>): List<String> = buildList {
		database.openHelper.readableDatabase.query(sql, arguments).use { cursor ->
			val detail = cursor.getColumnIndexOrThrow("detail")
			while (cursor.moveToNext()) add(cursor.getString(detail))
		}
	}
}
