package com.adsamcik.tracker.points.database

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PointsDatabase")
class PointsDatabaseTest {

	@Nested
	inner class CompanionObject {
		@Test
		fun `databaseName is points_database`() {
			PointsDatabase.databaseName shouldBe "points_database"
		}
	}
}
