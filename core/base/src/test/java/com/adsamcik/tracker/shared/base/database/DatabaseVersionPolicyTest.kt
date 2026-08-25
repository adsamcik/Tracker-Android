package com.adsamcik.tracker.shared.base.database

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseVersionPolicyTest {
	@Test
	fun `released v27 is frozen and unreleased v28 is the only active migration target`() {
		assertEquals(27, LAST_RELEASED_ACTIVE_DATABASE_VERSION)
		assertEquals(28, CURRENT_DATABASE_VERSION)
		assertEquals(LAST_RELEASED_ACTIVE_DATABASE_VERSION + 1, CURRENT_DATABASE_VERSION)
		assertEquals(26, AppDatabase.legacyPublicMigrationsThroughV26.maxOf { it.endVersion })
		assertEquals(
			listOf(LAST_RELEASED_ACTIVE_DATABASE_VERSION to CURRENT_DATABASE_VERSION),
			AppDatabase.activeMigrations.map { it.startVersion to it.endVersion },
		)
	}
}
