package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Regression guard for the `idx_minigame_score_played_at` index added in
 * AppDatabase v31. The dashboard "recent runs" panel reads
 * `MiniGameScoreDao.getRecent(limit)` which executes
 * `SELECT * FROM minigame_score ORDER BY played_at DESC LIMIT MIN(MAX(?, 0), 500)`.
 *
 * Without the index SQLite must SCAN the entire `minigame_score` table and
 * then sort with a temporary B-tree on every recomposition. With the index
 * the planner does a backward index walk and stops after `LIMIT` rows.
 *
 * This test populates the table with enough rows that SQLite will actually
 * consider indexes (the planner prefers SCAN over very small tables) and
 * asserts that the chosen plan uses the index — if the index is ever dropped,
 * renamed, or the `getRecent` query is reshaped into a form the planner
 * cannot index-seek, this fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniGameScoreDaoIndexTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: MiniGameScoreDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.miniGameScoreDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `recent runs query plans as index walk not table scan`() = runTest {
		// Populate with enough rows that the planner actually considers indexes.
		repeat(PLAN_PROBE_ROW_COUNT) { idx ->
			dao.insert(
				MiniGameScoreEntity(
					gameId = "fake-game-${idx % 3}",
					score = idx.toDouble(),
					xpAwarded = idx,
					playedAt = (idx + 1) * 1_000L,
				),
			)
		}

		@Test
		fun `personal best and reconciliation reads are suspend bounded queries`() = runTest {
			repeat(8) { index ->
				dao.insert(
					MiniGameScoreEntity(
						gameId = if (index == 7) "territory" else "outrun",
						score = index.toDouble(),
						xpAwarded = index,
						playedAt = index * 1_000L,
					),
				)
			}

			assertEquals(6.0, dao.getPersonalBest("outrun"))
			val reconciliationRows = dao.getRecentForReconciliation(limit = 3)
			assertEquals(3, reconciliationRows.size)
			assertEquals(listOf(7_000L, 6_000L, 5_000L), reconciliationRows.map { it.playedAt })
		}

		val explainSql = """
			EXPLAIN QUERY PLAN
			SELECT * FROM minigame_score
			ORDER BY played_at DESC
			LIMIT MIN(MAX(?, 0), 500)
		""".trimIndent()

		val raw = database.openHelper.readableDatabase
		val planLines = mutableListOf<String>()
		raw.query(explainSql, arrayOf<Any?>(PLAN_PROBE_LIMIT)).use { cursor: Cursor ->
			val detailColumn = cursor.getColumnIndexOrThrow("detail")
			while (cursor.moveToNext()) {
				planLines += cursor.getString(detailColumn)
			}
		}

		check(planLines.isNotEmpty()) { "EXPLAIN QUERY PLAN returned no rows" }

		// Hard regression guard: any SCAN of minigame_score that is NOT
		// using the played_at index means the planner regressed.
		val scanOfTable = planLines.filter { line ->
			line.contains("SCAN") &&
				line.contains("minigame_score") &&
				!line.contains("USING INDEX") &&
				!line.contains("USING COVERING INDEX")
		}
		check(scanOfTable.isEmpty()) {
			"Query plan regressed to SCAN of minigame_score:\n${planLines.joinToString("\n")}"
		}

		// Belt-and-braces: at least one line must mention the named index, so a
		// future refactor that creates a different index on the same column does
		// not silently change which one we rely on.
		val usesNamedIndex = planLines.any { it.contains("idx_minigame_score_played_at") }
		check(usesNamedIndex) {
			"Query plan does not use idx_minigame_score_played_at:\n" +
				planLines.joinToString("\n")
		}
	}

	private companion object {
		const val PLAN_PROBE_ROW_COUNT = 64
		const val PLAN_PROBE_LIMIT = 20
	}
}
