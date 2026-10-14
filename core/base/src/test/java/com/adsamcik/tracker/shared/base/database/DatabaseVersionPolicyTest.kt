package com.adsamcik.tracker.shared.base.database

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseVersionPolicyTest {
	@Test
	fun `released legacy v12 and known development v27 have separate boundaries`() {
		assertEquals(12, LAST_RELEASED_LEGACY_DATABASE_VERSION)
		assertEquals(27, INITIAL_ACTIVE_DATABASE_VERSION)
		assertEquals(30, CURRENT_DATABASE_VERSION)
		assertEquals(26, AppDatabase.legacyPublicMigrationsThroughV26.maxOf { it.endVersion })
		assertEquals(
			listOf(27 to 28, 28 to 29, 29 to 30),
			AppDatabase.activeMigrations.map { it.startVersion to it.endVersion },
		)
	}
}
