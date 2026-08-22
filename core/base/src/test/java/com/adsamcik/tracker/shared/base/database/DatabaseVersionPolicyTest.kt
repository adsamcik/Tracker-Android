package com.adsamcik.tracker.shared.base.database

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseVersionPolicyTest {
	@Test
	fun `version 28 starts the additive active database migration chain`() {
		assertEquals(28, CURRENT_DATABASE_VERSION)
		assertEquals(26, AppDatabase.legacyPublicMigrationsThroughV26.maxOf { it.endVersion })
		assertEquals(listOf(27 to 28), AppDatabase.activeMigrations.map { it.startVersion to it.endVersion })
	}
}
