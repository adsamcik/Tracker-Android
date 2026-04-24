package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private infix fun TextUnit.shouldBeLargerThan(other: TextUnit) {
	(this.value > other.value) shouldBe true
}

@DisplayName("Typography tokens")
class TypographyTest {

	@Nested
	@DisplayName("font families")
	inner class FontFamilies {

		@Test
		fun `DisplayFontFamily is system default`() {
			DisplayFontFamily shouldBe FontFamily.Default
		}

		@Test
		fun `BodyFontFamily is system default`() {
			BodyFontFamily shouldBe FontFamily.Default
		}
	}

	@Nested
	@DisplayName("display scale")
	inner class DisplayScale {

		// Sizes track the M3 spec (57/45/36sp). The oversized 64/52/44sp previously used truncated
		// live speed HUDs like "12.4 km/h" on 360dp screens.
		@Test
		fun `displayLarge is 57sp Bold`() {
			AppTypography.displayLarge.fontSize shouldBe 57.sp
			AppTypography.displayLarge.fontWeight shouldBe FontWeight.Bold
			AppTypography.displayLarge.lineHeight shouldBe 64.sp
		}

		@Test
		fun `displayMedium is 45sp Bold`() {
			AppTypography.displayMedium.fontSize shouldBe 45.sp
			AppTypography.displayMedium.fontWeight shouldBe FontWeight.Bold
			AppTypography.displayMedium.lineHeight shouldBe 52.sp
		}

		@Test
		fun `displaySmall is 36sp Bold`() {
			AppTypography.displaySmall.fontSize shouldBe 36.sp
			AppTypography.displaySmall.fontWeight shouldBe FontWeight.Bold
			AppTypography.displaySmall.lineHeight shouldBe 44.sp
		}

		@Test
		fun `display sizes decrease Large to Small`() {
			AppTypography.displayLarge.fontSize shouldBeLargerThan AppTypography.displayMedium.fontSize
			AppTypography.displayMedium.fontSize shouldBeLargerThan AppTypography.displaySmall.fontSize
		}
	}

	@Nested
	@DisplayName("headline scale")
	inner class HeadlineScale {

		@Test
		fun `headlineLarge is 36sp SemiBold`() {
			AppTypography.headlineLarge.fontSize shouldBe 36.sp
			AppTypography.headlineLarge.fontWeight shouldBe FontWeight.SemiBold
		}

		@Test
		fun `headlineMedium is 32sp SemiBold`() {
			AppTypography.headlineMedium.fontSize shouldBe 32.sp
			AppTypography.headlineMedium.fontWeight shouldBe FontWeight.SemiBold
		}

		@Test
		fun `headlineSmall is 28sp SemiBold`() {
			AppTypography.headlineSmall.fontSize shouldBe 28.sp
			AppTypography.headlineSmall.fontWeight shouldBe FontWeight.SemiBold
		}

		@Test
		fun `headline sizes decrease Large to Small`() {
			AppTypography.headlineLarge.fontSize shouldBeLargerThan AppTypography.headlineMedium.fontSize
			AppTypography.headlineMedium.fontSize shouldBeLargerThan AppTypography.headlineSmall.fontSize
		}
	}

	@Nested
	@DisplayName("title scale")
	inner class TitleScale {

		@Test
		fun `titleLarge is 22sp Medium`() {
			AppTypography.titleLarge.fontSize shouldBe 22.sp
			AppTypography.titleLarge.fontWeight shouldBe FontWeight.Medium
		}

		@Test
		fun `titleMedium is 18sp Medium`() {
			AppTypography.titleMedium.fontSize shouldBe 18.sp
			AppTypography.titleMedium.fontWeight shouldBe FontWeight.Medium
		}

		@Test
		fun `titleSmall is 14sp Medium`() {
			AppTypography.titleSmall.fontSize shouldBe 14.sp
			AppTypography.titleSmall.fontWeight shouldBe FontWeight.Medium
		}
	}

	@Nested
	@DisplayName("body scale")
	inner class BodyScale {

		@Test
		fun `bodyLarge is 16sp Normal`() {
			AppTypography.bodyLarge.fontSize shouldBe 16.sp
			AppTypography.bodyLarge.fontWeight shouldBe FontWeight.Normal
		}

		@Test
		fun `bodyMedium is 14sp Normal`() {
			AppTypography.bodyMedium.fontSize shouldBe 14.sp
			AppTypography.bodyMedium.fontWeight shouldBe FontWeight.Normal
		}

		@Test
		fun `bodySmall is 12sp Normal`() {
			AppTypography.bodySmall.fontSize shouldBe 12.sp
			AppTypography.bodySmall.fontWeight shouldBe FontWeight.Normal
		}
	}

	@Nested
	@DisplayName("label scale")
	inner class LabelScale {

		@Test
		fun `labelLarge is 14sp Medium`() {
			AppTypography.labelLarge.fontSize shouldBe 14.sp
			AppTypography.labelLarge.fontWeight shouldBe FontWeight.Medium
		}

		@Test
		fun `labelMedium is 12sp Medium`() {
			AppTypography.labelMedium.fontSize shouldBe 12.sp
			AppTypography.labelMedium.fontWeight shouldBe FontWeight.Medium
		}

		@Test
		fun `labelSmall is 11sp Medium`() {
			AppTypography.labelSmall.fontSize shouldBe 11.sp
			AppTypography.labelSmall.fontWeight shouldBe FontWeight.Medium
		}
	}

	@Nested
	@DisplayName("type hierarchy consistency")
	inner class Hierarchy {

		@Test
		fun `display is at least as large as headline`() {
			// M3 spec makes displaySmall (36sp) equal to headlineLarge (36sp); enforce >= rather
			// than strict > so future spec tweaks don't trip this invariant.
			(AppTypography.displaySmall.fontSize.value >= AppTypography.headlineLarge.fontSize.value) shouldBe true
		}

		@Test
		fun `headline is larger than title`() {
			AppTypography.headlineSmall.fontSize shouldBeLargerThan AppTypography.titleLarge.fontSize
		}

		@Test
		fun `all line heights are greater than font sizes`() {
			val styles = listOf(
				AppTypography.displayLarge,
				AppTypography.displayMedium,
				AppTypography.displaySmall,
				AppTypography.headlineLarge,
				AppTypography.headlineMedium,
				AppTypography.headlineSmall,
				AppTypography.titleLarge,
				AppTypography.titleMedium,
				AppTypography.titleSmall,
				AppTypography.bodyLarge,
				AppTypography.bodyMedium,
				AppTypography.bodySmall,
				AppTypography.labelLarge,
				AppTypography.labelMedium,
				AppTypography.labelSmall,
			)
			styles.forEach { style ->
				style.lineHeight shouldBeLargerThan style.fontSize
			}
		}

		@Test
		fun `uses DisplayFontFamily for display and headline styles`() {
			AppTypography.displayLarge.fontFamily shouldBe DisplayFontFamily
			AppTypography.headlineLarge.fontFamily shouldBe DisplayFontFamily
			AppTypography.titleLarge.fontFamily shouldBe DisplayFontFamily
		}

		@Test
		fun `uses BodyFontFamily for body and label styles`() {
			AppTypography.bodyLarge.fontFamily shouldBe BodyFontFamily
			AppTypography.labelLarge.fontFamily shouldBe BodyFontFamily
		}
	}
}
