package com.adsamcik.tracker.shared.preferences.map

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MapSettingsRepositoryTest {

	private lateinit var repository: FakeMapSettingsRepository

	@BeforeEach
	fun setUp() {
		repository = FakeMapSettingsRepository()
	}

	private class FakeMapSettingsRepository : MapSettingsRepository {
		private val _data = MutableStateFlow(MapSettingsState())
		override val data: Flow<MapSettingsState> = _data

		override suspend fun setQuality(quality: Float) {
			_data.value = _data.value.copy(quality = quality)
		}

		override suspend fun setMaxHeatPoints(maxHeat: Int) {
			_data.value = _data.value.copy(maxHeatPoints = maxHeat)
		}

		override suspend fun setVisitThresholdSeconds(seconds: Int) {
			_data.value = _data.value.copy(visitThresholdSeconds = seconds)
		}
	}

	@Nested
	inner class `initial state` {
		@Test
		fun `data emits default state initially`() = runTest {
			repository.data.first() shouldBe MapSettingsState()
		}
	}

	@Nested
	inner class `setQuality` {
		@Test
		fun `updates quality value`() = runTest {
			repository.setQuality(2.5f)
			repository.data.first().quality shouldBe 2.5f
		}
	}

	@Nested
	inner class `setMaxHeatPoints` {
		@Test
		fun `updates max heat points value`() = runTest {
			repository.setMaxHeatPoints(100)
			repository.data.first().maxHeatPoints shouldBe 100
		}
	}

	@Nested
	inner class `setVisitThresholdSeconds` {
		@Test
		fun `updates visit threshold value`() = runTest {
			repository.setVisitThresholdSeconds(60)
			repository.data.first().visitThresholdSeconds shouldBe 60
		}
	}

	@Nested
	inner class `cumulative updates` {
		@Test
		fun `multiple updates are cumulative`() = runTest {
			repository.setQuality(3.0f)
			repository.setMaxHeatPoints(50)
			repository.setVisitThresholdSeconds(30)

			val state = repository.data.first()
			state.quality shouldBe 3.0f
			state.maxHeatPoints shouldBe 50
			state.visitThresholdSeconds shouldBe 30
		}
	}
}
