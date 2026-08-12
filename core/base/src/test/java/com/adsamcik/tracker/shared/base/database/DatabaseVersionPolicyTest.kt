package com.adsamcik.tracker.shared.base.database

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseVersionPolicyTest {
	@Test
	fun `unreleased schema work uses one version after released version 26`() {
		assertEquals(27, CURRENT_DATABASE_VERSION)
		assertEquals(26, AppDatabase.legacyPublicMigrationsThroughV26.maxOf { it.endVersion })
		assertEquals(0, AppDatabase.activeMigrations.size)
	}
}
