package com.adsamcik.tracker.game.challenge.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import org.junit.Assert.assertEquals
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

	companion object {
		private const val TEST_DB = "challenge-migration-test"
	}
}
