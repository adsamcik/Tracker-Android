package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StorageSizeSnapshotEntity - daily storage usage snapshot")
class StorageSizeSnapshotEntityTest {

	private fun snapshot(
		id: Long = 0,
		epochDay: Long = 19800L,
		databaseSizeBytes: Long = 1048576L,
		locationCount: Int = 1000,
		sessionCount: Int = 50,
		wifiCount: Int = 200,
		cellCount: Int = 150,
		explorationCellCount: Int = 300,
		routeCacheCount: Int = 25,
		createdAt: Long = 1000L
	) = StorageSizeSnapshotEntity(
		id, epochDay, databaseSizeBytes, locationCount, sessionCount,
		wifiCount, cellCount, explorationCellCount, routeCacheCount, createdAt
	)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val s = snapshot()
			s.epochDay shouldBe 19800L
			s.databaseSizeBytes shouldBe 1048576L
			s.locationCount shouldBe 1000
			s.sessionCount shouldBe 50
			s.wifiCount shouldBe 200
			s.cellCount shouldBe 150
			s.explorationCellCount shouldBe 300
			s.routeCacheCount shouldBe 25
		}

		@Test
		fun `id defaults to 0`() {
			snapshot().id shouldBe 0
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			snapshot() shouldBe snapshot()
		}

		@Test
		fun `inequality`() {
			snapshot(locationCount = 1) shouldNotBe snapshot(locationCount = 2)
		}

		@Test
		fun `copy`() {
			val s = snapshot().copy(databaseSizeBytes = 2097152L)
			s.databaseSizeBytes shouldBe 2097152L
		}
	}
}
