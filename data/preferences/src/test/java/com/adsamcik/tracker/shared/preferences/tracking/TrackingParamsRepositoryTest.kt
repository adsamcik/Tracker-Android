package com.adsamcik.tracker.shared.preferences.tracking

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrackingParamsRepositoryTest {

	private lateinit var repository: FakeTrackingParamsRepository

	@BeforeEach
	fun setUp() {
		repository = FakeTrackingParamsRepository()
	}

	/** Minimal fake that verifies the interface contract is implementable. */
	private class FakeTrackingParamsRepository : TrackingParamsRepository {
		private val _data = MutableStateFlow(TrackingParamsState())
		override val data: Flow<TrackingParamsState> = _data

		override suspend fun update(block: TrackingParamsState.() -> TrackingParamsState) {
			_data.value = _data.value.block()
		}

		override suspend fun setLocationEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(locationEnabled = enabled)
		}

		override suspend fun setActivityEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(activityEnabled = enabled)
		}

		override suspend fun setStepsEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(stepsEnabled = enabled)
		}

		override suspend fun setWifiEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(wifiEnabled = enabled)
		}

		override suspend fun setCellEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(cellEnabled = enabled)
		}

		override suspend fun setWifiNetworkEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(wifiNetworkEnabled = enabled)
		}

		override suspend fun setWifiLocationCountEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(wifiLocationCountEnabled = enabled)
		}

		override suspend fun setTransitionDetectionEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(transitionDetectionEnabled = enabled)
		}

		override suspend fun setNotificationStyled(enabled: Boolean) {
			_data.value = _data.value.copy(notificationStyled = enabled)
		}

		override suspend fun setMinDistanceMeters(meters: Int) {
			_data.value = _data.value.copy(minDistanceMeters = meters)
		}

		override suspend fun setMinTimeSeconds(seconds: Int) {
			_data.value = _data.value.copy(minTimeSeconds = seconds)
		}

		override suspend fun setRequiredAccuracyMeters(meters: Int) {
			_data.value = _data.value.copy(requiredAccuracyMeters = meters)
		}

		override suspend fun setPreset(preset: TrackingPreset) {
			_data.value = _data.value.copy(presetName = preset.name)
		}

		override suspend fun setSkiDetectionEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(skiDetectionEnabled = enabled)
		}

		override suspend fun setSailingDetectionEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(sailingDetectionEnabled = enabled)
		}

		override suspend fun setPlaneDetectionEnabled(enabled: Boolean) {
			_data.value = _data.value.copy(planeDetectionEnabled = enabled)
		}

		override suspend fun setVehicleSpeedLimitBaselineMps(mps: Double) {
			_data.value = _data.value.copy(vehicleSpeedLimitBaselineMps = mps)
		}
	}

	@Nested
	inner class `initial state` {
		@Test
		fun `data emits default state initially`() = runTest {
			val state = repository.data.first()
			state shouldBe TrackingParamsState()
		}
	}

	@Nested
	inner class `individual setters` {
		@Test
		fun `setLocationEnabled updates state`() = runTest {
			repository.setLocationEnabled(false)
			repository.data.first().locationEnabled shouldBe false
		}

		@Test
		fun `setActivityEnabled updates state`() = runTest {
			repository.setActivityEnabled(false)
			repository.data.first().activityEnabled shouldBe false
		}

		@Test
		fun `setStepsEnabled updates state`() = runTest {
			repository.setStepsEnabled(false)
			repository.data.first().stepsEnabled shouldBe false
		}

		@Test
		fun `setWifiEnabled updates state`() = runTest {
			repository.setWifiEnabled(true)
			repository.data.first().wifiEnabled shouldBe true
		}

		@Test
		fun `setCellEnabled updates state`() = runTest {
			repository.setCellEnabled(true)
			repository.data.first().cellEnabled shouldBe true
		}

		@Test
		fun `setWifiNetworkEnabled updates state`() = runTest {
			repository.setWifiNetworkEnabled(true)
			repository.data.first().wifiNetworkEnabled shouldBe true
		}

		@Test
		fun `setWifiLocationCountEnabled updates state`() = runTest {
			repository.setWifiLocationCountEnabled(true)
			repository.data.first().wifiLocationCountEnabled shouldBe true
		}

		@Test
		fun `setTransitionDetectionEnabled updates state`() = runTest {
			repository.setTransitionDetectionEnabled(false)
			repository.data.first().transitionDetectionEnabled shouldBe false
		}

		@Test
		fun `setNotificationStyled updates state`() = runTest {
			repository.setNotificationStyled(false)
			repository.data.first().notificationStyled shouldBe false
		}

		@Test
		fun `setMinDistanceMeters updates state`() = runTest {
			repository.setMinDistanceMeters(42)
			repository.data.first().minDistanceMeters shouldBe 42
		}

		@Test
		fun `setMinTimeSeconds updates state`() = runTest {
			repository.setMinTimeSeconds(30)
			repository.data.first().minTimeSeconds shouldBe 30
		}

		@Test
		fun `setRequiredAccuracyMeters updates state`() = runTest {
			repository.setRequiredAccuracyMeters(200)
			repository.data.first().requiredAccuracyMeters shouldBe 200
		}

		@Test
		fun `setPreset updates preset name`() = runTest {
			repository.setPreset(TrackingPreset.POWER_SAVE)
			repository.data.first().presetName shouldBe "POWER_SAVE"
		}

		@Test
		fun `setSkiDetectionEnabled updates state`() = runTest {
			repository.setSkiDetectionEnabled(true)
			repository.data.first().skiDetectionEnabled shouldBe true
		}
	}

	@Nested
	inner class `bulk update` {
		@Test
		fun `update applies transformation block`() = runTest {
			repository.update {
				copy(
					locationEnabled = false,
					minDistanceMeters = 99,
					presetName = "CUSTOM"
				)
			}
			val state = repository.data.first()
			state.locationEnabled shouldBe false
			state.minDistanceMeters shouldBe 99
			state.presetName shouldBe "CUSTOM"
		}
	}
}
