package com.adsamcik.tracker.shared.base.database

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseVersionPolicyTest {
	@Test
	fun `released legacy v12 is separate from the unreleased active v27 and v28 schemas`() {
		assertEquals(12, LAST_RELEASED_LEGACY_DATABASE_VERSION)
		assertEquals(27, INITIAL_ACTIVE_DATABASE_VERSION)
		assertEquals(28, CURRENT_DATABASE_VERSION)
		assertEquals(INITIAL_ACTIVE_DATABASE_VERSION + 1, CURRENT_DATABASE_VERSION)
		assertEquals(26, AppDatabase.legacyPublicMigrationsThroughV26.maxOf { it.endVersion })
		assertEquals(
			listOf(INITIAL_ACTIVE_DATABASE_VERSION to CURRENT_DATABASE_VERSION),
			AppDatabase.activeMigrations.map { it.startVersion to it.endVersion },
		)
	}
}
