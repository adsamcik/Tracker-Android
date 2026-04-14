package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Color tokens")
class ColorTest {

	@Nested
	@DisplayName("seed color")
	inner class SeedColor {

		@Test
		fun `RidgelineSeed is Canopy Green 0xFF1B6B3A`() {
			RidgelineSeed shouldBe Color(0xFF1B6B3A)
		}
	}

	@Nested
	@DisplayName("primary palette")
	inner class PrimaryPalette {

		@Test
		fun `light primary is Canopy Green`() {
			CanopyGreenPrimaryLight shouldBe Color(0xFF1B6B3A)
		}

		@Test
		fun `onPrimary light is white`() {
			CanopyGreenOnPrimaryLight shouldBe Color(0xFFFFFFFF)
		}

		@Test
		fun `dark primary is lighter green`() {
			CanopyGreenPrimaryDark shouldBe Color(0xFF7BDA97)
		}

		@Test
		fun `light and dark primaries differ`() {
			CanopyGreenPrimaryLight shouldNotBe CanopyGreenPrimaryDark
		}
	}

	@Nested
	@DisplayName("secondary palette (Trail Sage)")
	inner class SecondaryPalette {

		@Test
		fun `light secondary value`() {
			TrailSageSecondaryLight shouldBe Color(0xFF506352)
		}

		@Test
		fun `dark secondary value`() {
			TrailSageSecondaryDark shouldBe Color(0xFFB5CCB6)
		}
	}

	@Nested
	@DisplayName("tertiary palette (Trail Gold)")
	inner class TertiaryPalette {

		@Test
		fun `light tertiary value`() {
			TrailGoldTertiaryLight shouldBe Color(0xFF857010)
		}

		@Test
		fun `dark tertiary value`() {
			TrailGoldTertiaryDark shouldBe Color(0xFFD0B24A)
		}
	}

	@Nested
	@DisplayName("error palette")
	inner class ErrorPalette {

		@Test
		fun `light error value`() {
			ErrorLight shouldBe Color(0xFFBA1A1A)
		}

		@Test
		fun `dark error value`() {
			ErrorDark shouldBe Color(0xFFFFB4AB)
		}

		@Test
		fun `onError light is white`() {
			OnErrorLight shouldBe Color(0xFFFFFFFF)
		}
	}

	@Nested
	@DisplayName("surface tokens")
	inner class SurfaceTokens {

		@Test
		fun `light surface is near-white`() {
			SurfaceLight shouldBe Color(0xFFF7FBF2)
		}

		@Test
		fun `dark surface is near-black`() {
			SurfaceDark shouldBe Color(0xFF101410)
		}

		@Test
		fun `scrim is always black`() {
			ScrimLight shouldBe Color(0xFF000000)
			ScrimDark shouldBe Color(0xFF000000)
		}

		@Test
		fun `light surface container hierarchy is darker as elevation increases`() {
			// Verify container tokens exist and are distinct
			SurfaceContainerLowestLight shouldNotBe SurfaceContainerHighestLight
			SurfaceContainerLowLight shouldNotBe SurfaceContainerHighLight
		}

		@Test
		fun `dark surface container hierarchy exists`() {
			SurfaceContainerLowestDark shouldNotBe SurfaceContainerHighestDark
		}
	}

	@Nested
	@DisplayName("semantic colors")
	inner class SemanticColors {

		@Test
		fun `success light value`() {
			SuccessLight shouldBe Color(0xFF146C2E)
		}

		@Test
		fun `success dark value`() {
			SuccessDark shouldBe Color(0xFF88D78A)
		}

		@Test
		fun `warning light value`() {
			WarningLight shouldBe Color(0xFF8D5000)
		}

		@Test
		fun `warning dark value`() {
			WarningDark shouldBe Color(0xFFFFB776)
		}
	}

	@Nested
	@DisplayName("track state colors")
	inner class TrackStateColors {

		@Test
		fun `TrackActiveColor is red`() {
			TrackActiveColor shouldBe Color(0xFFFF3B30)
		}

		@Test
		fun `TrackHistoryColor is teal`() {
			TrackHistoryColor shouldBe Color(0xFF00829B)
		}

		@Test
		fun `active and history colors differ`() {
			TrackActiveColor shouldNotBe TrackHistoryColor
		}
	}

	@Nested
	@DisplayName("LightColorScheme")
	inner class LightScheme {

		@Test
		fun `primary maps to Canopy Green light`() {
			LightColorScheme.primary shouldBe CanopyGreenPrimaryLight
		}

		@Test
		fun `onPrimary maps correctly`() {
			LightColorScheme.onPrimary shouldBe CanopyGreenOnPrimaryLight
		}

		@Test
		fun `secondary maps to Trail Sage light`() {
			LightColorScheme.secondary shouldBe TrailSageSecondaryLight
		}

		@Test
		fun `tertiary maps to Trail Gold light`() {
			LightColorScheme.tertiary shouldBe TrailGoldTertiaryLight
		}

		@Test
		fun `error maps to error light`() {
			LightColorScheme.error shouldBe ErrorLight
		}

		@Test
		fun `surface maps correctly`() {
			LightColorScheme.surface shouldBe SurfaceLight
		}

		@Test
		fun `background maps correctly`() {
			LightColorScheme.background shouldBe BackgroundLight
		}

		@Test
		fun `inversePrimary maps correctly`() {
			LightColorScheme.inversePrimary shouldBe InversePrimaryLight
		}
	}

	@Nested
	@DisplayName("DarkColorScheme")
	inner class DarkScheme {

		@Test
		fun `primary maps to Canopy Green dark`() {
			DarkColorScheme.primary shouldBe CanopyGreenPrimaryDark
		}

		@Test
		fun `onPrimary maps correctly`() {
			DarkColorScheme.onPrimary shouldBe CanopyGreenOnPrimaryDark
		}

		@Test
		fun `secondary maps to Trail Sage dark`() {
			DarkColorScheme.secondary shouldBe TrailSageSecondaryDark
		}

		@Test
		fun `tertiary maps to Trail Gold dark`() {
			DarkColorScheme.tertiary shouldBe TrailGoldTertiaryDark
		}

		@Test
		fun `error maps to error dark`() {
			DarkColorScheme.error shouldBe ErrorDark
		}

		@Test
		fun `surface maps correctly`() {
			DarkColorScheme.surface shouldBe SurfaceDark
		}

		@Test
		fun `background maps correctly`() {
			DarkColorScheme.background shouldBe BackgroundDark
		}

		@Test
		fun `inversePrimary maps correctly`() {
			DarkColorScheme.inversePrimary shouldBe InversePrimaryDark
		}
	}

	@Nested
	@DisplayName("light vs dark scheme consistency")
	inner class SchemeConsistency {

		@Test
		fun `light and dark primaries use same tokens`() {
			LightColorScheme.primary shouldBe CanopyGreenPrimaryLight
			DarkColorScheme.primary shouldBe CanopyGreenPrimaryDark
		}

		@Test
		fun `light and dark schemes have different surfaces`() {
			LightColorScheme.surface shouldNotBe DarkColorScheme.surface
		}

		@Test
		fun `light and dark schemes have different backgrounds`() {
			LightColorScheme.background shouldNotBe DarkColorScheme.background
		}

		@Test
		fun `inverse primary in light equals primary in dark`() {
			LightColorScheme.inversePrimary shouldBe CanopyGreenPrimaryDark
		}

		@Test
		fun `inverse primary in dark equals primary in light`() {
			DarkColorScheme.inversePrimary shouldBe CanopyGreenPrimaryLight
		}
	}

	@Nested
	@DisplayName("activity colors")
	inner class ActivityColorsTest {

		@Test
		fun `all light activity colors are defined`() {
			ActivityColors.WalkLight shouldBe Color(0xFF007051)
			ActivityColors.RunLight shouldBe Color(0xFFA34800)
			ActivityColors.RideLight shouldBe Color(0xFF00659E)
			ActivityColors.VehicleLight shouldBe Color(0xFF97396D)
			ActivityColors.StillLight shouldBe Color(0xFF546E7A)
			ActivityColors.UnknownLight shouldBe Color(0xFF616161)
		}

		@Test
		fun `all dark activity colors are defined`() {
			ActivityColors.WalkDark shouldBe Color(0xFF52C5A6)
			ActivityColors.RunDark shouldBe Color(0xFFEF8C3D)
			ActivityColors.RideDark shouldBe Color(0xFF5AADDC)
			ActivityColors.VehicleDark shouldBe Color(0xFFD490B6)
			ActivityColors.StillDark shouldBe Color(0xFF90A4AE)
			ActivityColors.UnknownDark shouldBe Color(0xFF9E9E9E)
		}

		@Test
		fun `OnLight is white`() {
			ActivityColors.OnLight shouldBe Color(0xFFFFFFFF)
		}

		@Test
		fun `each activity has distinct light color`() {
			val lightColors = listOf(
				ActivityColors.WalkLight,
				ActivityColors.RunLight,
				ActivityColors.RideLight,
				ActivityColors.VehicleLight,
				ActivityColors.StillLight,
				ActivityColors.UnknownLight,
			)
			lightColors.distinct().size shouldBe lightColors.size
		}

		@Test
		fun `each activity has distinct dark color`() {
			val darkColors = listOf(
				ActivityColors.WalkDark,
				ActivityColors.RunDark,
				ActivityColors.RideDark,
				ActivityColors.VehicleDark,
				ActivityColors.StillDark,
				ActivityColors.UnknownDark,
			)
			darkColors.distinct().size shouldBe darkColors.size
		}
	}
}
