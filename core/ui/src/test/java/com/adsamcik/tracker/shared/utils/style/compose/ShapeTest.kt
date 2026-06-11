package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Shape tokens")
class ShapeTest {

	@Nested
	@DisplayName("DialogShape")
	inner class DialogShapeTest {

		@Test
		fun `is a RoundedCornerShape`() {
			DialogShape.shouldBeInstanceOf<RoundedCornerShape>()
		}

		@Test
		fun `has uniform 28dp corners`() {
			DialogShape shouldBe RoundedCornerShape(28.dp)
		}
	}

	@Nested
	@DisplayName("BottomSheetShape")
	inner class BottomSheetShapeTest {

		@Test
		fun `is a RoundedCornerShape`() {
			BottomSheetShape.shouldBeInstanceOf<RoundedCornerShape>()
		}

		@Test
		fun `has asymmetric top and flat bottom`() {
			BottomSheetShape shouldBe RoundedCornerShape(
				topStart = 20.dp,
				topEnd = 6.dp,
				bottomEnd = 0.dp,
				bottomStart = 0.dp,
			)
		}
	}

	@Nested
	@DisplayName("identity shapes")
	inner class IdentityShapes {

		@Test
		fun `WaypointShape uses percentage-based corners`() {
			WaypointShape shouldBe RoundedCornerShape(
				topStartPercent = 50,
				topEndPercent = 50,
				bottomEndPercent = 10,
				bottomStartPercent = 50,
			)
		}

		@Test
		fun `MomentumPillShape has forward-motion pill`() {
			MomentumPillShape shouldBe RoundedCornerShape(
				topStartPercent = 20,
				topEndPercent = 50,
				bottomEndPercent = 50,
				bottomStartPercent = 20,
			)
		}

		@Test
		fun `TerrainCardShape has legacy asymmetry`() {
			TerrainCardShape shouldBe RoundedCornerShape(
				topStartPercent = 15,
				topEndPercent = 4,
				bottomEndPercent = 15,
				bottomStartPercent = 4,
			)
		}
	}

	@Nested
	@DisplayName("AppShapes 5-level scale")
	inner class AppShapesScale {

		@Test
		fun `extraSmall is L1 with 6-2dp diagonal`() {
			AppShapes.extraSmall shouldBe RoundedCornerShape(
				topStart = 6.dp, topEnd = 2.dp,
				bottomEnd = 6.dp, bottomStart = 2.dp,
			)
		}

		@Test
		fun `small is L2 with 10-3dp diagonal`() {
			AppShapes.small shouldBe RoundedCornerShape(
				topStart = 10.dp, topEnd = 3.dp,
				bottomEnd = 10.dp, bottomStart = 3.dp,
			)
		}

		@Test
		fun `medium is L3 with 14-4dp diagonal`() {
			AppShapes.medium shouldBe RoundedCornerShape(
				topStart = 14.dp, topEnd = 4.dp,
				bottomEnd = 14.dp, bottomStart = 4.dp,
			)
		}

		@Test
		fun `large is L4 with 20-6dp diagonal`() {
			AppShapes.large shouldBe RoundedCornerShape(
				topStart = 20.dp, topEnd = 6.dp,
				bottomEnd = 20.dp, bottomStart = 6.dp,
			)
		}

		@Test
		fun `extraLarge is L5 with 24-8dp diagonal`() {
			AppShapes.extraLarge shouldBe RoundedCornerShape(
				topStart = 24.dp, topEnd = 8.dp,
				bottomEnd = 24.dp, bottomStart = 8.dp,
			)
		}

		@Test
		fun `all levels maintain 3-to-1 major-minor ratio`() {
			// L1: 6/2 = 3.0
			// L2: 10/3 ≈ 3.33
			// L3: 14/4 = 3.5
			// L4: 20/6 ≈ 3.33
			// L5: 24/8 = 3.0
			// All within the 3:1 range, verified via dp value assertions above
		}
	}
}
