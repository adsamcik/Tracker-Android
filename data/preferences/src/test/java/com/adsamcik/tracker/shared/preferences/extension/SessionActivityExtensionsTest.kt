package com.adsamcik.tracker.shared.preferences.extension

import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionActivityExtensionsTest {

	private fun activity(id: Long, name: String) = SessionActivity(id, name, null)

	@Nested
	inner class `isMaritimeActivity` {
		@Test
		fun `WATER_VEHICLE native activity is maritime`() {
			activity(NativeSessionActivity.WATER_VEHICLE.id, "anything").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with sail in name is maritime`() {
			activity(99L, "Sailing trip").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with boat in name is maritime`() {
			activity(99L, "Motor boat").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with ship in name is maritime`() {
			activity(99L, "Cruise Ship").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with yacht in name is maritime`() {
			activity(99L, "Yacht racing").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with ferry in name is maritime`() {
			activity(99L, "Ferry ride").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with canoe in name is maritime`() {
			activity(99L, "Canoe adventure").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with kayak in name is maritime`() {
			activity(99L, "Kayak trip").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with rowing in name is maritime`() {
			activity(99L, "Morning rowing").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `custom activity with water in name is maritime`() {
			activity(99L, "Water sports").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `case insensitive matching works`() {
			activity(99L, "SAILING").isMaritimeActivity() shouldBe true
			activity(99L, "kayAK").isMaritimeActivity() shouldBe true
		}

		@Test
		fun `walking activity is not maritime`() {
			activity(NativeSessionActivity.WALKING.id, "Walking").isMaritimeActivity() shouldBe false
		}

		@Test
		fun `running activity is not maritime`() {
			activity(NativeSessionActivity.RUNNING.id, "Running").isMaritimeActivity() shouldBe false
		}

		@Test
		fun `generic vehicle activity is not maritime`() {
			activity(NativeSessionActivity.VEHICLE.id, "Driving").isMaritimeActivity() shouldBe false
		}
	}

	@Nested
	inner class `isAviationActivity` {
		@Test
		fun `AIR_VEHICLE native activity is aviation`() {
			activity(NativeSessionActivity.AIR_VEHICLE.id, "anything").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with fly in name is aviation`() {
			activity(99L, "Fly around").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with flight in name is aviation`() {
			activity(99L, "International flight").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with plane in name is aviation`() {
			activity(99L, "Small plane").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with aircraft in name is aviation`() {
			activity(99L, "Light aircraft").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with helicopter in name is aviation`() {
			activity(99L, "Helicopter tour").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with balloon in name is aviation`() {
			activity(99L, "Hot air balloon").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with glider in name is aviation`() {
			activity(99L, "Glider flight").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with aviation in name is aviation`() {
			activity(99L, "General aviation").isAviationActivity() shouldBe true
		}

		@Test
		fun `custom activity with air in name is aviation`() {
			activity(99L, "Air show").isAviationActivity() shouldBe true
		}

		@Test
		fun `case insensitive matching works`() {
			activity(99L, "HELICOPTER").isAviationActivity() shouldBe true
			activity(99L, "Balloon").isAviationActivity() shouldBe true
		}

		@Test
		fun `walking activity is not aviation`() {
			activity(NativeSessionActivity.WALKING.id, "Walking").isAviationActivity() shouldBe false
		}

		@Test
		fun `water vehicle activity is not aviation`() {
			activity(NativeSessionActivity.WATER_VEHICLE.id, "Boat").isAviationActivity() shouldBe false
		}
	}

	@Nested
	inner class `getPreferredLengthSystem` {
		@Test
		fun `returns Sailing for maritime activity`() {
			activity(NativeSessionActivity.WATER_VEHICLE.id, "boat").getPreferredLengthSystem() shouldBe LengthSystem.Sailing
		}

		@Test
		fun `returns Flying for aviation activity`() {
			activity(NativeSessionActivity.AIR_VEHICLE.id, "plane").getPreferredLengthSystem() shouldBe LengthSystem.Flying
		}

		@Test
		fun `returns null for walking activity`() {
			activity(NativeSessionActivity.WALKING.id, "Walking").getPreferredLengthSystem() shouldBe null
		}

		@Test
		fun `returns null for cycling activity`() {
			activity(NativeSessionActivity.BICYCLE.id, "Cycling").getPreferredLengthSystem() shouldBe null
		}

		@Test
		fun `returns null for generic vehicle`() {
			activity(NativeSessionActivity.VEHICLE.id, "Driving").getPreferredLengthSystem() shouldBe null
		}

		@Test
		fun `maritime keyword takes priority over aviation keyword`() {
			// "water" matches maritime, so should return Sailing
			activity(99L, "water").getPreferredLengthSystem() shouldBe LengthSystem.Sailing
		}

		@Test
		fun `returns Flying for custom activity with flight keyword`() {
			activity(99L, "My flight").getPreferredLengthSystem() shouldBe LengthSystem.Flying
		}

		@Test
		fun `returns Sailing for custom activity with sailing keyword`() {
			activity(99L, "Sailing race").getPreferredLengthSystem() shouldBe LengthSystem.Sailing
		}
	}
}
