package com.adsamcik.tracker.shared.preferences.settings

import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrackerSettingsRepositoryTest {

	private class FakeTrackerSettingsRepository : TrackerSettingsRepository {
		private val _data = MutableStateFlow(TrackerSettingsState.DEFAULT)
		override val data: Flow<TrackerSettingsState> = _data

		override suspend fun setAutoUnitSwitch(enabled: Boolean) {
			_data.value = _data.value.copy(autoUnitSwitch = enabled)
		}

		override suspend fun setLengthSystem(system: LengthSystem) {
			_data.value = _data.value.copy(lengthSystem = system)
		}

		override suspend fun setSpeedFormat(format: SpeedFormat) {
			_data.value = _data.value.copy(speedFormat = format)
		}
	}

	@Nested
	inner class `settings state defaults` {
		@Test
		fun `DEFAULT has autoUnitSwitch disabled`() {
			TrackerSettingsState.DEFAULT.autoUnitSwitch shouldBe false
		}

		@Test
		fun `DEFAULT has Metric length system`() {
			TrackerSettingsState.DEFAULT.lengthSystem shouldBe LengthSystem.Metric
		}

		@Test
		fun `DEFAULT has Hour speed format`() {
			TrackerSettingsState.DEFAULT.speedFormat shouldBe SpeedFormat.Hour
		}
	}

	@Nested
	inner class `settings state data class` {
		@Test
		fun `equality for identical states`() {
			val a = TrackerSettingsState(true, LengthSystem.Imperial, SpeedFormat.Minute)
			val b = TrackerSettingsState(true, LengthSystem.Imperial, SpeedFormat.Minute)
			a shouldBe b
		}

		@Test
		fun `inequality for different states`() {
			val a = TrackerSettingsState(true, LengthSystem.Metric, SpeedFormat.Hour)
			val b = TrackerSettingsState(false, LengthSystem.Metric, SpeedFormat.Hour)
			a shouldNotBe b
		}

		@Test
		fun `copy preserves unmodified fields`() {
			val original = TrackerSettingsState.DEFAULT
			val copied = original.copy(autoUnitSwitch = true)
			copied.autoUnitSwitch shouldBe true
			copied.lengthSystem shouldBe original.lengthSystem
			copied.speedFormat shouldBe original.speedFormat
		}

		@Test
		fun `all LengthSystem values can be used`() {
			LengthSystem.entries.forEach { system ->
				val state = TrackerSettingsState(false, system, SpeedFormat.Hour)
				state.lengthSystem shouldBe system
			}
		}

		@Test
		fun `all SpeedFormat values can be used`() {
			SpeedFormat.entries.forEach { format ->
				val state = TrackerSettingsState(false, LengthSystem.Metric, format)
				state.speedFormat shouldBe format
			}
		}
	}

	@Nested
	inner class `interface contract` {
		private lateinit var repository: FakeTrackerSettingsRepository

		@BeforeEach
		fun setUp() {
			repository = FakeTrackerSettingsRepository()
		}

		@Test
		fun `data emits default state initially`() = runTest {
			repository.data.first() shouldBe TrackerSettingsState.DEFAULT
		}

		@Test
		fun `setAutoUnitSwitch updates state`() = runTest {
			repository.setAutoUnitSwitch(true)
			repository.data.first().autoUnitSwitch shouldBe true
		}

		@Test
		fun `setLengthSystem updates state`() = runTest {
			repository.setLengthSystem(LengthSystem.Imperial)
			repository.data.first().lengthSystem shouldBe LengthSystem.Imperial
		}

		@Test
		fun `setSpeedFormat updates state`() = runTest {
			repository.setSpeedFormat(SpeedFormat.Second)
			repository.data.first().speedFormat shouldBe SpeedFormat.Second
		}

		@Test
		fun `multiple updates are cumulative`() = runTest {
			repository.setAutoUnitSwitch(true)
			repository.setLengthSystem(LengthSystem.Sailing)
			repository.setSpeedFormat(SpeedFormat.Minute)

			val state = repository.data.first()
			state.autoUnitSwitch shouldBe true
			state.lengthSystem shouldBe LengthSystem.Sailing
			state.speedFormat shouldBe SpeedFormat.Minute
		}
	}
}
