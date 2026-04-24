package com.adsamcik.tracker.game.challenge.database.migration

import android.database.SQLException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ChallengeMigrationTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		ChallengeDatabase::class.java.canonicalName,
		FrameworkSQLiteOpenHelperFactory()
	)

	@Test
	@Throws(IOException::class)
	fun migrate2To3_createsUnifiedTable() {
		val db = helper.createDatabase(TEST_DB, 2)

		// Insert legacy data: an entry + an explorer challenge
		db.execSQL(
			"INSERT INTO entry (id, type, start_time, end_time, difficulty) VALUES (1, 0, 1000, 2000, 2)"
		)
		db.execSQL(
			"INSERT INTO challenge_explorer (entry_id, locationCount, requiredLocationCount, completed) VALUES (1, 5, 10, 0)"
		)

		// Insert a walk distance challenge
		db.execSQL(
			"INSERT INTO entry (id, type, start_time, end_time, difficulty) VALUES (2, 1, 3000, 4000, 1)"
		)
		db.execSQL(
			"INSERT INTO challenge_walk_distance (entry_id, distanceInM, requiredDistanceInM, completed) VALUES (2, 500.0, 1000.0, 1)"
		)

		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).apply {
			val cursor = query("SELECT COUNT(*) FROM challenge")
			with(cursor) {
				assertTrue(moveToFirst())
				assertEquals(2, getInt(0))
			}
			cursor.close()

			// Verify explorer migration
			val explorerCursor = query("SELECT type, difficulty, required_value, current_value, is_completed FROM challenge WHERE type = 'Explorer'")
			with(explorerCursor) {
				assertTrue(moveToFirst())
				assertEquals("Explorer", getString(0))
				assertEquals("MEDIUM", getString(1))
				assertEquals(10.0, getDouble(2), 0.01)
				assertEquals(5.0, getDouble(3), 0.01)
				assertEquals(0, getInt(4))
			}
			explorerCursor.close()

			// Verify walk distance migration
			val walkCursor = query("SELECT type, difficulty, required_value, current_value, is_completed FROM challenge WHERE type = 'WalkDistance'")
			with(walkCursor) {
				assertTrue(moveToFirst())
				assertEquals("WalkDistance", getString(0))
				assertEquals("EASY", getString(1))
				assertEquals(1000.0, getDouble(2), 0.01)
				assertEquals(500.0, getDouble(3), 0.01)
				assertEquals(1, getInt(4))
			}
			walkCursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate3To4_createsProgressionTables() {
		val db = helper.createDatabase(TEST_DB, 3)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4).apply {
			// Verify all progression tables exist
			for (table in listOf("challenge_history", "xp_ledger", "player_profile", "challenge_streak", "challenge_personal_record")) {
				val cursor = query("SELECT COUNT(*) FROM $table")
				with(cursor) {
					assertTrue("Table $table should exist and be queryable", moveToFirst())
					assertEquals("Table $table should be empty", 0, getInt(0))
				}
				cursor.close()
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate4To5_createsMinigameScoreTable() {
		val db = helper.createDatabase(TEST_DB, 4)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).apply {
			val cursor = query("SELECT COUNT(*) FROM minigame_score")
			with(cursor) {
				assertTrue(moveToFirst())
				assertEquals(0, getInt(0))
			}
			cursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate2To5_fullChain() {
		val db = helper.createDatabase(TEST_DB, 2)

		// Insert a legacy step challenge
		db.execSQL(
			"INSERT INTO entry (id, type, start_time, end_time, difficulty) VALUES (1, 2, 1000, 2000, 3)"
		)
		db.execSQL(
			"INSERT INTO challenge_step (entry_id, stepCount, requiredStepCount, completed) VALUES (1, 7500, 10000, 0)"
		)

		db.close()

		helper.runMigrationsAndValidate(
			TEST_DB, 5, true,
			MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5
		).apply {
			// Verify data survived full chain
			val cursor = query("SELECT type, difficulty FROM challenge WHERE type = 'Step'")
			with(cursor) {
				assertTrue(moveToFirst())
				assertEquals("Step", getString(0))
				assertEquals("HARD", getString(1))
			}
			cursor.close()

			// Verify new tables exist
			val scoreCursor = query("SELECT COUNT(*) FROM minigame_score")
			with(scoreCursor) {
				assertTrue(moveToFirst())
				assertEquals(0, getInt(0))
			}
			scoreCursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate5To6_dedupsXpLedgerOnSourceAndSourceId() {
		val db = helper.createDatabase(TEST_DB, 5)
		// Three duplicates sharing (source, source_id) = ('CHALLENGE', 1).
		// MIGRATION_5_6 keeps the MIN(id) row (id=1).
		db.execSQL("INSERT INTO xp_ledger (id, amount, source, source_id, earned_at) VALUES (1, 100, 'CHALLENGE', 1, 1000)")
		db.execSQL("INSERT INTO xp_ledger (id, amount, source, source_id, earned_at) VALUES (2, 100, 'CHALLENGE', 1, 2000)")
		db.execSQL("INSERT INTO xp_ledger (id, amount, source, source_id, earned_at) VALUES (3, 100, 'CHALLENGE', 1, 3000)")
		// A distinct (source, source_id) must survive.
		db.execSQL("INSERT INTO xp_ledger (id, amount, source, source_id, earned_at) VALUES (4, 50, 'CHALLENGE', 2, 4000)")
		// NULL source_id rows are excluded from dedup (all must survive).
		db.execSQL("INSERT INTO xp_ledger (id, amount, source, source_id, earned_at) VALUES (5, 25, 'BONUS', NULL, 5000)")
		db.execSQL("INSERT INTO xp_ledger (id, amount, source, source_id, earned_at) VALUES (6, 25, 'BONUS', NULL, 6000)")
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6).apply {
			// id=1 kept, id=2 & 3 removed; id=4,5,6 kept.
			query("SELECT id FROM xp_ledger ORDER BY id").use { cursor ->
				val ids = mutableListOf<Int>()
				while (cursor.moveToNext()) ids.add(cursor.getInt(0))
				assertEquals(listOf(1, 4, 5, 6), ids)
			}

			// Attempting to insert another (CHALLENGE, 1) now fails the unique
			// index.
			var uniqueRejected = false
			try {
				execSQL(
					"INSERT INTO xp_ledger (id, amount, source, source_id, earned_at) VALUES (10, 1, 'CHALLENGE', 1, 10000)"
				)
			} catch (_: SQLException) {
				uniqueRejected = true
			}
			assertTrue("index_xp_ledger_source_source_id must be UNIQUE", uniqueRejected)

			// NULL source_id is exempt from the unique constraint (SQLite
			// treats NULL as distinct in UNIQUE indices), so multiple rows
			// should still insert.
			execSQL("INSERT INTO xp_ledger (id, amount, source, source_id, earned_at) VALUES (11, 1, 'BONUS', NULL, 11000)")
			query("SELECT COUNT(*) FROM xp_ledger WHERE source_id IS NULL").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(3, cursor.getInt(0))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate2To6_fullChain_preservesV6UniqueIndex() {
		// v1 is an auto-migration tracked by the Room compiler; the earliest
		// schema MigrationTestHelper can seed is v2, which is what the last
		// released app version actually shipped once devices finished the
		// 1→2 auto-migration. The chain 2→6 is the real release→dev/v10 path.
		helper.createDatabase(TEST_DB, 2).close()

		helper.runMigrationsAndValidate(
			TEST_DB, 6, true,
			MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
		).apply {
			// v6 unique index present and enforced end-to-end.
			query("PRAGMA index_list('xp_ledger')").use { cursor ->
				val names = buildSet {
					while (cursor.moveToNext()) {
						add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
					}
				}
				assertTrue(
					"Missing unique index 'index_xp_ledger_source_source_id' after full chain",
					names.contains("index_xp_ledger_source_source_id")
				)
			}

			// Round-trip a progression row through v4 (minigame_score came at
			// v4→v5) to confirm the schema at v6 is actually usable.
			execSQL(
				"INSERT INTO minigame_score (id, game_id, score, xp_awarded, played_at) " +
					"VALUES (1, 'reflex', 123.5, 50, 1700000000000)"
			)
			query("SELECT game_id, score, xp_awarded FROM minigame_score WHERE id = 1").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("reflex", cursor.getString(0))
				assertEquals(123.5, cursor.getDouble(1), 0.001)
				assertEquals(50, cursor.getInt(2))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate2To3_handlesMissingLegacyTablesGracefully() {
		// Simulates DB restored from a backup where a legacy sub-table was
		// missing. MIGRATION_2_3's `tableExists` guard must not crash, and
		// the unified `challenge` table must still be created empty.
		val db = helper.createDatabase(TEST_DB, 2)
		db.execSQL("DROP TABLE challenge_step")
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).apply {
			query("SELECT COUNT(*) FROM challenge").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0, cursor.getInt(0))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate2To6_createsAllV6TablesFromEmptyV2() {
		helper.createDatabase(TEST_DB, 2).close()

		helper.runMigrationsAndValidate(
			TEST_DB, 6, true,
			MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
		).apply {
			val expected = listOf(
				"challenge",
				"challenge_history",
				"xp_ledger",
				"player_profile",
				"challenge_streak",
				"challenge_personal_record",
				"minigame_score"
			)
			expected.forEach { table ->
				query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(table))
					.use { cursor ->
						assertTrue(cursor.moveToFirst())
						assertEquals("Expected table $table to exist after v2→v6 migration", 1, cursor.getInt(0))
					}
			}
		}
	}

	companion object {
		private const val TEST_DB = "challenge-migration-test"
	}
}
