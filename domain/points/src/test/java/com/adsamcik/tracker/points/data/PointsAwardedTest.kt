package com.adsamcik.tracker.points.data

import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PointsAwardedTest {

	@Nested
	inner class Construction {
		@Test
		fun `primary constructor sets all fields`() {
			val awarded = PointsAwarded(
				id = 1,
				time = 1000L,
				value = Points(50.0),
				source = AwardSource.SESSION
			)

			awarded.id shouldBe 1
			awarded.time shouldBe 1000L
			awarded.value.value shouldBeExactly 50.0
			awarded.source shouldBe AwardSource.SESSION
		}

		@Test
		fun `secondary constructor defaults id to zero`() {
			val awarded = PointsAwarded(
				time = 2000L,
				value = Points(25.0),
				source = AwardSource.MINIGAME
			)

			awarded.id shouldBe 0
			awarded.time shouldBe 2000L
			awarded.value.value shouldBeExactly 25.0
			awarded.source shouldBe AwardSource.MINIGAME
		}
	}

	@Nested
	inner class AwardSourceTest {
		@Test
		fun `SESSION source has correct value`() {
			AwardSource.SESSION.value shouldBe "session"
		}

		@Test
		fun `MINIGAME source has correct value`() {
			AwardSource.MINIGAME.value shouldBe "minigame"
		}

		@Test
		fun `GOAL source has correct value`() {
			AwardSource.GOAL.value shouldBe "goal"
		}

		@Test
		fun `custom source stores arbitrary value`() {
			val custom = AwardSource("custom_event")
			custom.value shouldBe "custom_event"
		}

		@Test
		fun `sources with same value are equal`() {
			AwardSource("session") shouldBe AwardSource.SESSION
		}

		@Test
		fun `different sources are not equal`() {
			AwardSource.SESSION shouldNotBe AwardSource.MINIGAME
		}
	}

	@Nested
	inner class PointsDataConvertersTest {
		private val converters = PointsDataConverters()

		@Test
		fun `fromAwardSource returns string value`() {
			converters.fromAwardSource(AwardSource.SESSION) shouldBe "session"
		}

		@Test
		fun `toAwardSource creates from string`() {
			converters.toAwardSource("goal") shouldBe AwardSource.GOAL
		}

		@Test
		fun `fromPoints returns double value`() {
			converters.fromPoints(Points(42.5)) shouldBeExactly 42.5
		}

		@Test
		fun `toPoints creates from double`() {
			converters.toPoints(42.5) shouldBe Points(42.5)
		}

		@Test
		fun `round-trip AwardSource conversion`() {
			val original = AwardSource.MINIGAME
			val converted = converters.toAwardSource(converters.fromAwardSource(original))
			converted shouldBe original
		}

		@Test
		fun `round-trip Points conversion`() {
			val original = Points(99.99)
			val converted = converters.toPoints(converters.fromPoints(original))
			converted shouldBe original
		}
	}

	@Nested
	inner class Equality {
		@Test
		fun `awarded with same fields are equal`() {
			val a = PointsAwarded(1, 1000L, Points(10.0), AwardSource.SESSION)
			val b = PointsAwarded(1, 1000L, Points(10.0), AwardSource.SESSION)
			a shouldBe b
		}

		@Test
		fun `awarded with different ids are not equal`() {
			val a = PointsAwarded(1, 1000L, Points(10.0), AwardSource.SESSION)
			val b = PointsAwarded(2, 1000L, Points(10.0), AwardSource.SESSION)
			a shouldNotBe b
		}

		@Test
		fun `awarded with different sources are not equal`() {
			val a = PointsAwarded(1, 1000L, Points(10.0), AwardSource.SESSION)
			val b = PointsAwarded(1, 1000L, Points(10.0), AwardSource.GOAL)
			a shouldNotBe b
		}
	}
}
