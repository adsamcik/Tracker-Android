package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.ui.unit.dp
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DesignSystem tokens")
class DesignSystemTest {

	@Nested
	@DisplayName("AppDimensions")
	inner class AppDimensionsTest {

		@Test
		fun `FloatingNavBarClearance is 120dp`() {
			AppDimensions.FloatingNavBarClearance shouldBe 120.dp
		}
	}

	@Nested
	@DisplayName("RidgelineSpacing")
	inner class RidgelineSpacingTest {

		@Test
		fun `None is 0dp`() {
			RidgelineSpacing.None shouldBe 0.dp
		}

		@Test
		fun `Xxs is 2dp`() {
			RidgelineSpacing.Xxs shouldBe 2.dp
		}

		@Test
		fun `Xs is 4dp`() {
			RidgelineSpacing.Xs shouldBe 4.dp
		}

		@Test
		fun `Sm is 8dp`() {
			RidgelineSpacing.Sm shouldBe 8.dp
		}

		@Test
		fun `Md is 12dp`() {
			RidgelineSpacing.Md shouldBe 12.dp
		}

		@Test
		fun `Lg is 16dp`() {
			RidgelineSpacing.Lg shouldBe 16.dp
		}

		@Test
		fun `Xl is 20dp`() {
			RidgelineSpacing.Xl shouldBe 20.dp
		}

		@Test
		fun `Xxl is 24dp`() {
			RidgelineSpacing.Xxl shouldBe 24.dp
		}

		@Test
		fun `Xxxl is 32dp`() {
			RidgelineSpacing.Xxxl shouldBe 32.dp
		}

		@Test
		fun `Xxxxl is 48dp`() {
			RidgelineSpacing.Xxxxl shouldBe 48.dp
		}

		@Test
		fun `spacing scale increases monotonically`() {
			val scale = listOf(
				RidgelineSpacing.None,
				RidgelineSpacing.Xxs,
				RidgelineSpacing.Xs,
				RidgelineSpacing.Sm,
				RidgelineSpacing.Md,
				RidgelineSpacing.Lg,
				RidgelineSpacing.Xl,
				RidgelineSpacing.Xxl,
				RidgelineSpacing.Xxxl,
				RidgelineSpacing.Xxxxl,
			)
			for (i in 0 until scale.size - 1) {
				(scale[i] < scale[i + 1]) shouldBe true
			}
		}
	}

	@Nested
	@DisplayName("ElevationPair")
	inner class ElevationPairTest {

		@Test
		fun `stores tonal and shadow values`() {
			val pair = ElevationPair(tonal = 4.dp, shadow = 2.dp)
			pair.tonal shouldBe 4.dp
			pair.shadow shouldBe 2.dp
		}

		@Test
		fun `data class equality works`() {
			val a = ElevationPair(tonal = 1.dp, shadow = 1.dp)
			val b = ElevationPair(tonal = 1.dp, shadow = 1.dp)
			a shouldBe b
		}

		@Test
		fun `different values are not equal`() {
			val a = ElevationPair(tonal = 1.dp, shadow = 1.dp)
			val b = ElevationPair(tonal = 2.dp, shadow = 2.dp)
			a shouldNotBe b
		}
	}

	@Nested
	@DisplayName("RidgelineElevation")
	inner class RidgelineElevationTest {

		@Test
		fun `Flat has zero elevation`() {
			RidgelineElevation.Flat shouldBe ElevationPair(tonal = 0.dp, shadow = 0.dp)
		}

		@Test
		fun `Raised has 1dp elevation`() {
			RidgelineElevation.Raised shouldBe ElevationPair(tonal = 1.dp, shadow = 1.dp)
		}

		@Test
		fun `Floating has 2dp elevation`() {
			RidgelineElevation.Floating shouldBe ElevationPair(tonal = 2.dp, shadow = 2.dp)
		}

		@Test
		fun `Overlay has 6dp elevation`() {
			RidgelineElevation.Overlay shouldBe ElevationPair(tonal = 6.dp, shadow = 6.dp)
		}

		@Test
		fun `elevation increases Flat to Overlay`() {
			(RidgelineElevation.Flat.tonal < RidgelineElevation.Raised.tonal) shouldBe true
			(RidgelineElevation.Raised.tonal < RidgelineElevation.Floating.tonal) shouldBe true
			(RidgelineElevation.Floating.tonal < RidgelineElevation.Overlay.tonal) shouldBe true
		}
	}

	@Nested
	@DisplayName("GlassTier")
	inner class GlassTierTest {

		@Test
		fun `G0 is opaque with no blur`() {
			GlassTier.G0.blur shouldBe 0.dp
			GlassTier.G0.lightTintAlpha shouldBe 1.0f
			GlassTier.G0.darkTintAlpha shouldBe 1.0f
			GlassTier.G0.lightBorderAlpha shouldBe 0.0f
			GlassTier.G0.darkBorderAlpha shouldBe 0.0f
		}

		@Test
		fun `G1 has 10dp blur`() {
			GlassTier.G1.blur shouldBe 10.dp
			GlassTier.G1.lightTintAlpha shouldBe 0.85f
			GlassTier.G1.darkTintAlpha shouldBe 0.88f
		}

		@Test
		fun `G2 has 18dp blur`() {
			GlassTier.G2.blur shouldBe 18.dp
			GlassTier.G2.lightTintAlpha shouldBe 0.78f
		}

		@Test
		fun `G3 has 26dp blur`() {
			GlassTier.G3.blur shouldBe 26.dp
			GlassTier.G3.lightTintAlpha shouldBe 0.72f
		}

		@Test
		fun `blur increases with tier`() {
			(GlassTier.G0.blur < GlassTier.G1.blur) shouldBe true
			(GlassTier.G1.blur < GlassTier.G2.blur) shouldBe true
			(GlassTier.G2.blur < GlassTier.G3.blur) shouldBe true
		}

		@Test
		fun `tint alpha decreases with tier (more transparent)`() {
			GlassTier.G0.lightTintAlpha shouldBeGreaterThan GlassTier.G1.lightTintAlpha
			GlassTier.G1.lightTintAlpha shouldBeGreaterThan GlassTier.G2.lightTintAlpha
			GlassTier.G2.lightTintAlpha shouldBeGreaterThan GlassTier.G3.lightTintAlpha
		}

		@Test
		fun `border alpha increases with tier (more visible border)`() {
			GlassTier.G1.lightBorderAlpha shouldBeGreaterThan GlassTier.G0.lightBorderAlpha
			GlassTier.G2.lightBorderAlpha shouldBeGreaterThan GlassTier.G1.lightBorderAlpha
			GlassTier.G3.lightBorderAlpha shouldBeGreaterThan GlassTier.G2.lightBorderAlpha
		}

		@Test
		fun `all tint alphas are in valid 0-1 range`() {
			GlassTier.entries.forEach { tier ->
				tier.lightTintAlpha shouldBeGreaterThan -0.01f
				tier.lightTintAlpha shouldBeLessThanOrEqual 1.0f
				tier.darkTintAlpha shouldBeGreaterThan -0.01f
				tier.darkTintAlpha shouldBeLessThanOrEqual 1.0f
			}
		}

		@Test
		fun `all border alphas are in valid 0-1 range`() {
			GlassTier.entries.forEach { tier ->
				tier.lightBorderAlpha shouldBeGreaterThan -0.01f
				tier.lightBorderAlpha shouldBeLessThanOrEqual 1.0f
				tier.darkBorderAlpha shouldBeGreaterThan -0.01f
				tier.darkBorderAlpha shouldBeLessThanOrEqual 1.0f
			}
		}

		@Test
		fun `enum has exactly 4 tiers`() {
			GlassTier.entries.size shouldBe 4
		}
	}

	@Nested
	@DisplayName("RidgelineSemanticColors")
	inner class SemanticColorsTest {

		@Test
		fun `data class stores all semantic color roles`() {
			val semantic = RidgelineSemanticColors(
				success = SuccessLight,
				onSuccess = OnSuccessLight,
				successContainer = SuccessContainerLight,
				onSuccessContainer = OnSuccessContainerLight,
				warning = WarningLight,
				onWarning = OnWarningLight,
				warningContainer = WarningContainerLight,
				onWarningContainer = OnWarningContainerLight,
			)

			semantic.success shouldBe SuccessLight
			semantic.onSuccess shouldBe OnSuccessLight
			semantic.warning shouldBe WarningLight
			semantic.onWarning shouldBe OnWarningLight
		}

		@Test
		fun `equality works for identical semantic colors`() {
			val a = RidgelineSemanticColors(
				success = SuccessLight, onSuccess = OnSuccessLight,
				successContainer = SuccessContainerLight, onSuccessContainer = OnSuccessContainerLight,
				warning = WarningLight, onWarning = OnWarningLight,
				warningContainer = WarningContainerLight, onWarningContainer = OnWarningContainerLight,
			)
			val b = RidgelineSemanticColors(
				success = SuccessLight, onSuccess = OnSuccessLight,
				successContainer = SuccessContainerLight, onSuccessContainer = OnSuccessContainerLight,
				warning = WarningLight, onWarning = OnWarningLight,
				warningContainer = WarningContainerLight, onWarningContainer = OnWarningContainerLight,
			)
			a shouldBe b
		}
	}
}
