package com.adsamcik.tracker.stats.engine.exploration

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

@DisplayName("S2CellId")
class S2CellIdTest {

	@Nested
	@DisplayName("fromLatLng")
	inner class FromLatLng {

		@Test
		fun `origin produces a valid cell ID`() {
			val cellId = S2CellId.fromLatLng(0.0, 0.0)
			cellId shouldNotBe 0L
		}

		@Test
		fun `same input always produces same output`() {
			val a = S2CellId.fromLatLng(40.7128, -74.0060, 14)
			val b = S2CellId.fromLatLng(40.7128, -74.0060, 14)
			a shouldBe b
		}

		@Test
		fun `different levels produce different cell IDs`() {
			val a = S2CellId.fromLatLng(40.7128, -74.0060, 10)
			val b = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			a shouldNotBe b
		}

		@Test
		fun `different locations produce different cell IDs at fine level`() {
			val nyc = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			val london = S2CellId.fromLatLng(51.5074, -0.1278, 16)
			nyc shouldNotBe london
		}

		@Test
		fun `nearby points at coarse level produce same cell`() {
			val a = S2CellId.fromLatLng(40.7128, -74.0060, 9)
			val b = S2CellId.fromLatLng(40.7130, -74.0062, 9)
			a shouldBe b
		}

		@Test
		fun `default level is 14`() {
			val withDefault = S2CellId.fromLatLng(40.7128, -74.0060)
			val withExplicit = S2CellId.fromLatLng(40.7128, -74.0060, 14)
			withDefault shouldBe withExplicit
		}

		@Test
		fun `level 0 produces valid cell`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 0)
			S2CellId.level(cellId) shouldBe 0
		}

		@Test
		fun `level 30 produces valid cell`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 30)
			S2CellId.level(cellId) shouldBe 30
		}

		@Test
		fun `all levels 0 to 30 produce correct level`() {
			for (level in 0..30) {
				val cellId = S2CellId.fromLatLng(51.5, -0.1, level)
				S2CellId.level(cellId) shouldBe level
			}
		}

		@Test
		fun `rejects level below 0`() {
			assertThrows<IllegalArgumentException> {
				S2CellId.fromLatLng(0.0, 0.0, -1)
			}
		}

		@Test
		fun `rejects level above MAX_LEVEL`() {
			assertThrows<IllegalArgumentException> {
				S2CellId.fromLatLng(0.0, 0.0, 31)
			}
		}

		@Test
		fun `face is determined by dominant axis`() {
			// (lat=0, lng=0) => x=1 dominant => face 0
			S2CellId.face(S2CellId.fromLatLng(0.0, 0.0, 30)) shouldBe 0

			// (lat=0, lng=90) => y=1 dominant => face 1
			S2CellId.face(S2CellId.fromLatLng(0.0, 90.0, 30)) shouldBe 1

			// (lat=90, lng=0) => z=1 dominant => face 2
			S2CellId.face(S2CellId.fromLatLng(90.0, 0.0, 30)) shouldBe 2

			// (lat=0, lng=180) => x=-1 dominant => face 3
			S2CellId.face(S2CellId.fromLatLng(0.0, 180.0, 30)) shouldBe 3

			// (lat=-90, lng=0) => z=-1 dominant => face 5
			S2CellId.face(S2CellId.fromLatLng(-90.0, 0.0, 30)) shouldBe 5
		}

		@Test
		fun `face is always in range 0 to 5`() {
			val locations = listOf(
				0.0 to 0.0,
				0.0 to 90.0,
				90.0 to 0.0,
				0.0 to 180.0,
				0.0 to -90.0,
				-90.0 to 0.0,
				45.0 to 45.0,
				-45.0 to -135.0,
			)
			locations.forEach { (lat, lng) ->
				val cellId = S2CellId.fromLatLng(lat, lng, 16)
				S2CellId.face(cellId) shouldBeInRange 0..5
			}
		}
	}

	@Nested
	@DisplayName("Token Conversion")
	inner class TokenConversion {

		@Test
		fun `token is non-empty for valid cell`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 14)
			val token = S2CellId.toToken(cellId)
			token.shouldNotBeEmpty()
		}

		@Test
		fun `token is lowercase hex`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			val token = S2CellId.toToken(cellId)
			token.toCharArray().forEach { ch ->
				(ch in '0'..'9' || ch in 'a'..'f') shouldBe true
			}
		}

		@Test
		fun `token does not end with zero`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			val token = S2CellId.toToken(cellId)
			token.last() shouldNotBe '0'
		}

		@Test
		fun `zero cell ID produces X token`() {
			S2CellId.toToken(0L) shouldBe "X"
		}

		@Test
		fun `X token produces zero cell ID`() {
			S2CellId.fromToken("X") shouldBe 0L
		}

		@Test
		fun `round-trip token to cell ID`() {
			val original = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			val token = S2CellId.toToken(original)
			val restored = S2CellId.fromToken(token)
			restored shouldBe original
		}

		@Test
		fun `round-trip for origin`() {
			val original = S2CellId.fromLatLng(0.0, 0.0, 16)
			val token = S2CellId.toToken(original)
			val restored = S2CellId.fromToken(token)
			restored shouldBe original
		}

		@Test
		fun `round-trip at all levels`() {
			for (level in 0..30) {
				val original = S2CellId.fromLatLng(48.8566, 2.3522, level)
				val token = S2CellId.toToken(original)
				val restored = S2CellId.fromToken(token)
				restored shouldBe original
			}
		}

		@Test
		fun `different cells produce different tokens`() {
			val tokenA = S2CellId.toToken(S2CellId.fromLatLng(40.7128, -74.0060, 14))
			val tokenB = S2CellId.toToken(S2CellId.fromLatLng(51.5074, -0.1278, 14))
			tokenA shouldNotBe tokenB
		}

		@Test
		fun `token length is at most 16 characters`() {
			for (level in 0..30) {
				val token = S2CellId.toToken(S2CellId.fromLatLng(35.6762, 139.6503, level))
				(token.length <= 16) shouldBe true
			}
		}

		@Test
		fun `coarser levels produce shorter tokens`() {
			val tokenFine = S2CellId.toToken(S2CellId.fromLatLng(40.7128, -74.0060, 30))
			val tokenCoarse = S2CellId.toToken(S2CellId.fromLatLng(40.7128, -74.0060, 4))
			tokenFine.length shouldBeGreaterThan tokenCoarse.length
		}
	}

	@Nested
	@DisplayName("Parent Relationship")
	inner class ParentRelationship {

		@Test
		fun `parent level matches requested level`() {
			val child = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			val parentId = S2CellId.parent(child, 13)
			S2CellId.level(parentId) shouldBe 13
		}

		@Test
		fun `parent at same level is identity`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			val same = S2CellId.parent(cellId, 16)
			same shouldBe cellId
		}

		@Test
		fun `parent at level 0 has level 0`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			val root = S2CellId.parent(cellId, 0)
			S2CellId.level(root) shouldBe 0
		}

		@Test
		fun `parent at higher level than child throws`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 10)
			assertThrows<IllegalArgumentException> {
				S2CellId.parent(cellId, 11)
			}
		}

		@Test
		fun `transitive parent relationship holds`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 20)
			val parentDirect = S2CellId.parent(cellId, 10)
			val parentVia15 = S2CellId.parent(S2CellId.parent(cellId, 15), 10)
			parentDirect shouldBe parentVia15
		}

		@Test
		fun `nearby children share same parent`() {
			val a = S2CellId.fromLatLng(40.7128, -74.0060, 20)
			val b = S2CellId.fromLatLng(40.7129, -74.0061, 20)
			// At a coarser level they should share a parent
			val parentA = S2CellId.parent(a, 12)
			val parentB = S2CellId.parent(b, 12)
			parentA shouldBe parentB
		}

		@Test
		fun `parent preserves face`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 20)
			val parentId = S2CellId.parent(cellId, 5)
			S2CellId.face(cellId) shouldBe S2CellId.face(parentId)
		}

		@Test
		fun `all intermediate parent levels are consistent`() {
			val cellId = S2CellId.fromLatLng(48.8566, 2.3522, 25)
			for (level in 0..25) {
				val parentId = S2CellId.parent(cellId, level)
				S2CellId.level(parentId) shouldBe level
			}
		}
	}

	@Nested
	@DisplayName("Center LatLng")
	inner class CenterLatLng {

		@Test
		fun `origin cell centre is near origin`() {
			val cellId = S2CellId.fromLatLng(0.0, 0.0, 16)
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (0.0 plusOrMinus 0.01)
			lng shouldBe (0.0 plusOrMinus 0.01)
		}

		@Test
		fun `NYC cell centre is near NYC`() {
			val cellId = S2CellId.fromLatLng(40.7128, -74.0060, 16)
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (40.7128 plusOrMinus 0.01)
			lng shouldBe (-74.0060 plusOrMinus 0.01)
		}

		@Test
		fun `Tokyo cell centre is near Tokyo`() {
			val cellId = S2CellId.fromLatLng(35.6762, 139.6503, 16)
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (35.6762 plusOrMinus 0.01)
			lng shouldBe (139.6503 plusOrMinus 0.01)
		}

		@Test
		fun `Sydney cell centre is near Sydney`() {
			val cellId = S2CellId.fromLatLng(-33.8688, 151.2093, 16)
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (-33.8688 plusOrMinus 0.01)
			lng shouldBe (151.2093 plusOrMinus 0.01)
		}

		@Test
		fun `finer levels give more precise centres`() {
			val targetLat = 40.7128
			val targetLng = -74.0060
			val coarse = S2CellId.fromLatLng(targetLat, targetLng, 10)
			val fine = S2CellId.fromLatLng(targetLat, targetLng, 20)
			val (coarseLat, coarseLng) = S2CellId.toLatLng(coarse)
			val (fineLat, fineLng) = S2CellId.toLatLng(fine)
			val coarseError = abs(coarseLat - targetLat) + abs(coarseLng - targetLng)
			val fineError = abs(fineLat - targetLat) + abs(fineLng - targetLng)
			(fineError <= coarseError) shouldBe true
		}

		@Test
		fun `round-trip preserves location at high level`() {
			val targetLat = 51.5074
			val targetLng = -0.1278
			val cellId = S2CellId.fromLatLng(targetLat, targetLng, 25)
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (targetLat plusOrMinus 0.001)
			lng shouldBe (targetLng plusOrMinus 0.001)
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCases {

		@Test
		fun `north pole near 90 degrees`() {
			val cellId = S2CellId.fromLatLng(89.999, 0.0, 16)
			val (lat, _) = S2CellId.toLatLng(cellId)
			lat shouldBe (89.999 plusOrMinus 0.01)
		}

		@Test
		fun `south pole near minus 90 degrees`() {
			val cellId = S2CellId.fromLatLng(-89.999, 0.0, 16)
			val (lat, _) = S2CellId.toLatLng(cellId)
			lat shouldBe (-89.999 plusOrMinus 0.01)
		}

		@Test
		fun `exact north pole`() {
			val cellId = S2CellId.fromLatLng(90.0, 0.0, 16)
			S2CellId.face(cellId) shouldBe 2
			val (lat, _) = S2CellId.toLatLng(cellId)
			lat shouldBe (90.0 plusOrMinus 0.01)
		}

		@Test
		fun `exact south pole`() {
			val cellId = S2CellId.fromLatLng(-90.0, 0.0, 16)
			S2CellId.face(cellId) shouldBe 5
			val (lat, _) = S2CellId.toLatLng(cellId)
			lat shouldBe (-90.0 plusOrMinus 0.01)
		}

		@Test
		fun `equator at prime meridian`() {
			val cellId = S2CellId.fromLatLng(0.0, 0.0, 16)
			S2CellId.face(cellId) shouldBe 0
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (0.0 plusOrMinus 0.01)
			lng shouldBe (0.0 plusOrMinus 0.01)
		}

		@Test
		fun `antimeridian positive side`() {
			val cellId = S2CellId.fromLatLng(0.0, 179.999, 16)
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (0.0 plusOrMinus 0.01)
			abs(lng) shouldBe (179.999 plusOrMinus 0.01)
		}

		@Test
		fun `antimeridian negative side`() {
			val cellId = S2CellId.fromLatLng(0.0, -179.999, 16)
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (0.0 plusOrMinus 0.01)
			abs(lng) shouldBe (179.999 plusOrMinus 0.01)
		}

		@Test
		fun `exactly on date line 180 degrees`() {
			val cellId = S2CellId.fromLatLng(0.0, 180.0, 16)
			cellId shouldNotBe 0L
			S2CellId.level(cellId) shouldBe 16
		}

		@Test
		fun `exactly on date line minus 180 degrees`() {
			val cellId = S2CellId.fromLatLng(0.0, -180.0, 16)
			cellId shouldNotBe 0L
			S2CellId.level(cellId) shouldBe 16
		}

		@Test
		fun `negative longitude quadrants`() {
			val cellId = S2CellId.fromLatLng(-33.8688, -58.4519, 14) // Buenos Aires area
			cellId shouldNotBe 0L
			val (lat, lng) = S2CellId.toLatLng(cellId)
			lat shouldBe (-33.8688 plusOrMinus 0.1)
			lng shouldBe (-58.4519 plusOrMinus 0.1)
		}

		@Test
		fun `zero cell ID has level minus 1`() {
			S2CellId.level(0L) shouldBe -1
		}

		@Test
		fun `level 0 cell on each face`() {
			val points = listOf(
				0.0 to 0.0,      // face 0
				0.0 to 90.0,     // face 1
				90.0 to 0.0,     // face 2
				0.0 to 180.0,    // face 3
				0.0 to -90.0,    // face 4
				-90.0 to 0.0,    // face 5
			)
			points.forEachIndexed { expectedFace, (lat, lng) ->
				val cellId = S2CellId.fromLatLng(lat, lng, 0)
				S2CellId.level(cellId) shouldBe 0
				S2CellId.face(cellId) shouldBe expectedFace
			}
		}
	}
}
