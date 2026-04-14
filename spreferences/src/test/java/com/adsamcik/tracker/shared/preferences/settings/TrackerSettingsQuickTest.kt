package com.adsamcik.tracker.shared.preferences.settings

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.emptyPreferences
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrackerSettingsQuickTest {

	private lateinit var mockContext: Context

	@BeforeEach
	fun setUp() {
		mockkObject(TrackerSettingsAccess)
	}

	@AfterEach
	fun tearDown() {
		unmockkObject(TrackerSettingsAccess)
	}

	private fun stubSnapshot(state: TrackerSettingsState) {
		mockContext = mockk {
			every { applicationContext } returns this@mockk
		}
		every { TrackerSettingsAccess.snapshot(any()) } returns state
	}

	@Nested
	inner class `snapshot` {
		@Test
		fun `delegates to TrackerSettingsAccess`() {
			val expected = TrackerSettingsState.DEFAULT
			stubSnapshot(expected)
			TrackerSettingsQuick.snapshot(mockContext) shouldBe expected
		}
	}

	@Nested
	inner class `lengthSystem` {
		@Test
		fun `returns system from snapshot`() {
			stubSnapshot(TrackerSettingsState.DEFAULT.copy(lengthSystem = LengthSystem.Imperial))
			TrackerSettingsQuick.lengthSystem(mockContext) shouldBe LengthSystem.Imperial
		}
	}

	@Nested
	inner class `effectiveLengthSystem` {
		@Test
		fun `returns base system when autoUnitSwitch is false`() {
			stubSnapshot(TrackerSettingsState(false, LengthSystem.Metric, SpeedFormat.Hour))
			val activity = SessionActivity(1L, "Sailing trip", null)
			TrackerSettingsQuick.effectiveLengthSystem(mockContext, activity) shouldBe LengthSystem.Metric
		}

		@Test
		fun `returns base system when activity is null`() {
			stubSnapshot(TrackerSettingsState(true, LengthSystem.Metric, SpeedFormat.Hour))
			TrackerSettingsQuick.effectiveLengthSystem(mockContext, null) shouldBe LengthSystem.Metric
		}

		@Test
		fun `returns Sailing for maritime activity with autoUnitSwitch enabled`() {
			stubSnapshot(TrackerSettingsState(true, LengthSystem.Metric, SpeedFormat.Hour))
			val activity = SessionActivity(NativeSessionActivity.WATER_VEHICLE.id, "boat", null)
			TrackerSettingsQuick.effectiveLengthSystem(mockContext, activity) shouldBe LengthSystem.Sailing
		}

		@Test
		fun `returns Flying for aviation activity with autoUnitSwitch enabled`() {
			stubSnapshot(TrackerSettingsState(true, LengthSystem.Metric, SpeedFormat.Hour))
			val activity = SessionActivity(NativeSessionActivity.AIR_VEHICLE.id, "airplane", null)
			TrackerSettingsQuick.effectiveLengthSystem(mockContext, activity) shouldBe LengthSystem.Flying
		}

		@Test
		fun `returns base system for non-special activity with autoUnitSwitch enabled`() {
			stubSnapshot(TrackerSettingsState(true, LengthSystem.Imperial, SpeedFormat.Hour))
			val activity = SessionActivity(NativeSessionActivity.WALKING.id, "walking", null)
			TrackerSettingsQuick.effectiveLengthSystem(mockContext, activity) shouldBe LengthSystem.Imperial
		}
	}
}
