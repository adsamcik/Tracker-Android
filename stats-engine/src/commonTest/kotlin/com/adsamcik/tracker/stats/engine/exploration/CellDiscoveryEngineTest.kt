package com.adsamcik.tracker.stats.engine.exploration

import com.adsamcik.tracker.stats.api.DiscoveryQuality
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

@DisplayName("CellDiscoveryEngine")
class CellDiscoveryEngineTest {

	private var fakeTimeMs = 1_000_000L
	private val defaultConfig = CellDiscoveryConfig(
		cellLevel = 14,
		minAccuracyM = 100f,
		briefVisitMs = 30_000L,
		visitMs = 120_000L,
		exploredMs = 300_000L,
		thoroughMs = 600_000L,
	)

	private lateinit var engine: CellDiscoveryEngine

	@BeforeEach
	fun setUp() {
		fakeTimeMs = 1_000_000L
		engine = CellDiscoveryEngine(
			config = defaultConfig,
			clock = { fakeTimeMs },
		)
	}

	// --- Coordinates for testing (sufficiently far apart to be in different cells) ---
	// NYC: 40.7128, -74.0060
	// London: 51.5074, -0.1278
	// Tokyo: 35.6762, 139.6503

	@Nested
	@DisplayName("New cell discovery")
	inner class NewCellDiscovery {

		@Test
		fun `first location emits new cell discovery`() {
			val result = engine.onLocation(
				latDeg = 40.7128,
				lngDeg = -74.0060,
				accuracyM = 10f,
				timestampMs = fakeTimeMs,
			)

			result.shouldNotBeNull()
			result.isNew shouldBe true
			result.quality shouldBe DiscoveryQuality.PASSED_THROUGH
			result.level shouldBe 14
			result.token.shouldNotBeEmpty()
		}

		@Test
		fun `moving to a different cell emits new discovery`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 5_000L
			val result = engine.onLocation(51.5074, -0.1278, 10f, fakeTimeMs)

			result.shouldNotBeNull()
			result.isNew shouldBe true
		}

		@Test
		fun `staying in the same cell emits nothing`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Tiny movement within the same S2 level-14 cell
			fakeTimeMs += 1_000L
			val result = engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldBeNull()
		}

		@Test
		fun `same exact coordinates do not re-emit`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 10_000L
			val result = engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldBeNull()
		}

		@Test
		fun `pre-loaded known tokens produce isNew false`() {
			// First discover a cell to learn its token
			val firstEngine = CellDiscoveryEngine(config = defaultConfig, clock = { fakeTimeMs })
			val discovery = firstEngine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)
			val knownToken = discovery!!.token

			// Create a new engine pre-loaded with that token
			val secondEngine = CellDiscoveryEngine(
				config = defaultConfig,
				clock = { fakeTimeMs },
				knownTokens = setOf(knownToken),
			)

			val result = secondEngine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldNotBeNull()
			result.isNew shouldBe false
			result.token shouldBe knownToken
		}

		@Test
		fun `revisiting a cell discovered in this session is not new`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Move away
			fakeTimeMs += 5_000L
			engine.onLocation(51.5074, -0.1278, 10f, fakeTimeMs)

			// Come back
			fakeTimeMs += 5_000L
			val result = engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldNotBeNull()
			result.isNew shouldBe false
		}

		@Test
		fun `discovery contains valid center coordinates`() {
			val result = engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldNotBeNull()
			val centerLat = result.centerLatE7 / 1e7
			val centerLon = result.centerLonE7 / 1e7

			// Center should be a valid lat/lng and map back to the same cell
			(centerLat >= -90.0 && centerLat <= 90.0) shouldBe true
			(centerLon >= -180.0 && centerLon <= 180.0) shouldBe true

			// The center of the cell should map back to the same cell token
			val cellId = S2CellId.fromLatLng(centerLat, centerLon, 14)
			S2CellId.toToken(cellId) shouldBe result.token
		}

		@Test
		fun `discovery token round-trips through S2CellId`() {
			val result = engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldNotBeNull()
			val restored = S2CellId.fromToken(result.token)
			S2CellId.toToken(restored) shouldBe result.token
			S2CellId.level(restored) shouldBe 14
		}
	}

	@Nested
	@DisplayName("Quality computation")
	inner class QualityComputation {

		@Test
		fun `less than 30s yields PASSED_THROUGH`() {
			engine.computeQuality(29_999L) shouldBe DiscoveryQuality.PASSED_THROUGH
		}

		@Test
		fun `exactly 30s yields CYCLED_THROUGH`() {
			engine.computeQuality(30_000L) shouldBe DiscoveryQuality.CYCLED_THROUGH
		}

		@Test
		fun `90s yields CYCLED_THROUGH`() {
			engine.computeQuality(90_000L) shouldBe DiscoveryQuality.CYCLED_THROUGH
		}

		@Test
		fun `exactly 120s yields TRAVERSED_ON_FOOT`() {
			engine.computeQuality(120_000L) shouldBe DiscoveryQuality.TRAVERSED_ON_FOOT
		}

		@Test
		fun `200s yields TRAVERSED_ON_FOOT`() {
			engine.computeQuality(200_000L) shouldBe DiscoveryQuality.TRAVERSED_ON_FOOT
		}

		@Test
		fun `exactly 300s yields EXPLORED`() {
			engine.computeQuality(300_000L) shouldBe DiscoveryQuality.EXPLORED
		}

		@Test
		fun `450s yields EXPLORED`() {
			engine.computeQuality(450_000L) shouldBe DiscoveryQuality.EXPLORED
		}

		@Test
		fun `exactly 600s yields THOROUGHLY_EXPLORED`() {
			engine.computeQuality(600_000L) shouldBe DiscoveryQuality.THOROUGHLY_EXPLORED
		}

		@Test
		fun `very long time yields THOROUGHLY_EXPLORED`() {
			engine.computeQuality(3_600_000L) shouldBe DiscoveryQuality.THOROUGHLY_EXPLORED
		}

		@Test
		fun `zero time yields PASSED_THROUGH`() {
			engine.computeQuality(0L) shouldBe DiscoveryQuality.PASSED_THROUGH
		}

		@Test
		fun `finalize after short stay returns null (PASSED_THROUGH already emitted)`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 5_000L
			val result = engine.finalize(fakeTimeMs)

			result.shouldBeNull()
		}

		@Test
		fun `finalize after 2 min stay returns TRAVERSED_ON_FOOT`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 120_000L
			val result = engine.finalize(fakeTimeMs)

			result.shouldNotBeNull()
			result.quality shouldBe DiscoveryQuality.TRAVERSED_ON_FOOT
		}

		@Test
		fun `finalize after 5 min stay returns EXPLORED`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 300_000L
			val result = engine.finalize(fakeTimeMs)

			result.shouldNotBeNull()
			result.quality shouldBe DiscoveryQuality.EXPLORED
		}

		@Test
		fun `finalize after 10 min stay returns THOROUGHLY_EXPLORED`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 600_000L
			val result = engine.finalize(fakeTimeMs)

			result.shouldNotBeNull()
			result.quality shouldBe DiscoveryQuality.THOROUGHLY_EXPLORED
		}

		@Test
		fun `time accumulates across multiple onLocation calls in same cell`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Multiple updates within the same cell
			fakeTimeMs += 60_000L
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 60_000L
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Total: 120s -> TRAVERSED_ON_FOOT
			val result = engine.finalize(fakeTimeMs)

			result.shouldNotBeNull()
			result.quality shouldBe DiscoveryQuality.TRAVERSED_ON_FOOT
		}

		@Test
		fun `finalize discovery is not marked as new`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 120_000L
			val result = engine.finalize(fakeTimeMs)

			result.shouldNotBeNull()
			result.isNew shouldBe false
		}

		@Test
		fun `finalize includes correct center coordinates`() {
			val entry = engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 120_000L
			val result = engine.finalize(fakeTimeMs)

			result.shouldNotBeNull()
			// Center should match the entry center (same cell)
			result.centerLatE7 shouldBe entry!!.centerLatE7
			result.centerLonE7 shouldBe entry.centerLonE7
		}
	}

	@Nested
	@DisplayName("Accuracy filtering")
	inner class AccuracyFiltering {

		@Test
		fun `null accuracy is accepted`() {
			val result = engine.onLocation(40.7128, -74.0060, null, fakeTimeMs)
			result.shouldNotBeNull()
		}

		@Test
		fun `accuracy at exactly threshold is accepted`() {
			val result = engine.onLocation(40.7128, -74.0060, 100f, fakeTimeMs)
			result.shouldNotBeNull()
		}

		@Test
		fun `accuracy below threshold is accepted`() {
			val result = engine.onLocation(40.7128, -74.0060, 50f, fakeTimeMs)
			result.shouldNotBeNull()
		}

		@Test
		fun `accuracy above threshold is rejected`() {
			val result = engine.onLocation(40.7128, -74.0060, 100.1f, fakeTimeMs)
			result.shouldBeNull()
		}

		@Test
		fun `very poor accuracy is rejected`() {
			val result = engine.onLocation(40.7128, -74.0060, 500f, fakeTimeMs)
			result.shouldBeNull()
		}

		@Test
		fun `rejected location does not start cell tracking`() {
			// Reject first location
			engine.onLocation(40.7128, -74.0060, 200f, fakeTimeMs)

			// Finalize should return null since no cell was ever entered
			val result = engine.finalize(fakeTimeMs)
			result.shouldBeNull()
		}

		@Test
		fun `rejected location does not accumulate time`() {
			// Enter a cell
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Bad accuracy update after 60s - should not accumulate
			fakeTimeMs += 60_000L
			engine.onLocation(40.7128, -74.0060, 200f, fakeTimeMs)

			// Good accuracy update immediately after
			val result = engine.finalize(fakeTimeMs)

			// Time accumulated should only be from the first entry to now (60s)
			// which gives CYCLED_THROUGH, not PASSED_THROUGH
			result.shouldNotBeNull()
			result.quality shouldBe DiscoveryQuality.CYCLED_THROUGH
		}

		@Test
		fun `custom accuracy threshold is respected`() {
			val strictConfig = defaultConfig.copy(minAccuracyM = 20f)
			val strictEngine = CellDiscoveryEngine(config = strictConfig, clock = { fakeTimeMs })

			// 25m would pass default 100m threshold but fail 20m threshold
			val result = strictEngine.onLocation(40.7128, -74.0060, 25f, fakeTimeMs)
			result.shouldBeNull()
		}
	}

	@Nested
	@DisplayName("Season computation")
	inner class SeasonComputation {

		private fun utcMillis(year: Int, month: Int, day: Int): Long {
			val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
				set(year, month, day, 12, 0, 0)
				set(Calendar.MILLISECOND, 0)
			}
			return cal.timeInMillis
		}

		@Test
		fun `January is winter`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.JANUARY, 15)) shouldBe 8
		}

		@Test
		fun `February is winter`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.FEBRUARY, 1)) shouldBe 8
		}

		@Test
		fun `March is spring`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.MARCH, 21)) shouldBe 1
		}

		@Test
		fun `April is spring`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.APRIL, 10)) shouldBe 1
		}

		@Test
		fun `May is spring`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.MAY, 31)) shouldBe 1
		}

		@Test
		fun `June is summer`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.JUNE, 1)) shouldBe 2
		}

		@Test
		fun `July is summer`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.JULY, 4)) shouldBe 2
		}

		@Test
		fun `August is summer`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.AUGUST, 15)) shouldBe 2
		}

		@Test
		fun `September is autumn`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.SEPTEMBER, 22)) shouldBe 4
		}

		@Test
		fun `October is autumn`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.OCTOBER, 31)) shouldBe 4
		}

		@Test
		fun `November is autumn`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.NOVEMBER, 15)) shouldBe 4
		}

		@Test
		fun `December is winter`() {
			CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.DECEMBER, 25)) shouldBe 8
		}

		@Test
		fun `season bits are disjoint powers of two`() {
			val spring = CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.APRIL, 1))
			val summer = CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.JULY, 1))
			val autumn = CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.OCTOBER, 1))
			val winter = CellDiscoveryEngine.seasonBit(utcMillis(2026, Calendar.JANUARY, 1))

			// Each is a unique power of 2
			(spring or summer or autumn or winter) shouldBe 15
			(spring and summer) shouldBe 0
			(autumn and winter) shouldBe 0
		}

		@Test
		fun `discovery carries correct season bit`() {
			val julyTimestamp = utcMillis(2026, Calendar.JULY, 15)
			val julyEngine = CellDiscoveryEngine(
				config = defaultConfig,
				clock = { julyTimestamp },
			)

			val result = julyEngine.onLocation(40.7128, -74.0060, 10f, julyTimestamp)

			result.shouldNotBeNull()
			result.seasonBit shouldBe 2 // Summer
		}
	}

	@Nested
	@DisplayName("Lifecycle")
	inner class Lifecycle {

		@Test
		fun `finalize with no active cell returns null`() {
			val result = engine.finalize(fakeTimeMs)
			result.shouldBeNull()
		}

		@Test
		fun `reset clears current cell`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)
			engine.reset()

			// After reset, finalize should have nothing to return
			val result = engine.finalize(fakeTimeMs + 120_000L)
			result.shouldBeNull()
		}

		@Test
		fun `reset does not clear discovered set`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)
			val countBefore = engine.discoveredCount

			engine.reset()

			engine.discoveredCount shouldBe countBefore
		}

		@Test
		fun `after reset the same cell is not new`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)
			engine.reset()

			fakeTimeMs += 5_000L
			val result = engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldNotBeNull()
			result.isNew shouldBe false
		}

		@Test
		fun `discoveredCount starts at zero with no known tokens`() {
			val freshEngine = CellDiscoveryEngine(
				config = defaultConfig,
				clock = { fakeTimeMs },
			)
			freshEngine.discoveredCount shouldBe 0
		}

		@Test
		fun `discoveredCount starts at known token count`() {
			val knownEngine = CellDiscoveryEngine(
				config = defaultConfig,
				clock = { fakeTimeMs },
				knownTokens = setOf("abc", "def", "ghi"),
			)
			knownEngine.discoveredCount shouldBe 3
		}

		@Test
		fun `discoveredCount increments on new cell`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)
			engine.discoveredCount shouldBe 1

			fakeTimeMs += 5_000L
			engine.onLocation(51.5074, -0.1278, 10f, fakeTimeMs)
			engine.discoveredCount shouldBe 2
		}

		@Test
		fun `discoveredCount does not increment on revisit`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 5_000L
			engine.onLocation(51.5074, -0.1278, 10f, fakeTimeMs)

			fakeTimeMs += 5_000L
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			engine.discoveredCount shouldBe 2
		}

		@Test
		fun `finalize clears current cell`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 120_000L
			engine.finalize(fakeTimeMs)

			// Second finalize should return null
			val result = engine.finalize(fakeTimeMs)
			result.shouldBeNull()
		}

		@Test
		fun `after finalize entering same cell is not new`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			fakeTimeMs += 120_000L
			engine.finalize(fakeTimeMs)

			fakeTimeMs += 5_000L
			val result = engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldNotBeNull()
			result.isNew shouldBe false
		}
	}

	@Nested
	@DisplayName("Custom configuration")
	inner class CustomConfiguration {

		@Test
		fun `custom cell level is used`() {
			val config = defaultConfig.copy(cellLevel = 10)
			val customEngine = CellDiscoveryEngine(config = config, clock = { fakeTimeMs })

			val result = customEngine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			result.shouldNotBeNull()
			result.level shouldBe 10
		}

		@Test
		fun `custom time thresholds affect quality`() {
			val fastConfig = defaultConfig.copy(
				briefVisitMs = 5_000L,
				visitMs = 10_000L,
				exploredMs = 20_000L,
				thoroughMs = 30_000L,
			)
			val fastEngine = CellDiscoveryEngine(config = fastConfig, clock = { fakeTimeMs })

			fastEngine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// 10s should be TRAVERSED_ON_FOOT with custom thresholds
			fakeTimeMs += 10_000L
			val result = fastEngine.finalize(fakeTimeMs)

			result.shouldNotBeNull()
			result.quality shouldBe DiscoveryQuality.TRAVERSED_ON_FOOT
		}
	}

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {

		@Test
		fun `timestamp going backwards does not produce negative accumulation`() {
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Simulate clock going backwards
			fakeTimeMs -= 5_000L
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Should not crash, time should be coerced to 0
			val result = engine.finalize(fakeTimeMs)
			// Accumulated 0ms -> PASSED_THROUGH -> null (already emitted)
			result.shouldBeNull()
		}

		@Test
		fun `entry at zero timestamp works`() {
			val result = engine.onLocation(40.7128, -74.0060, 10f, 0L)
			result.shouldNotBeNull()
		}

		@Test
		fun `accuracy at zero is accepted`() {
			val result = engine.onLocation(40.7128, -74.0060, 0f, fakeTimeMs)
			result.shouldNotBeNull()
		}

		@Test
		fun `multiple cell transitions accumulate time independently`() {
			// Enter cell A
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Stay 60s in cell A
			fakeTimeMs += 60_000L
			engine.onLocation(40.7128, -74.0060, 10f, fakeTimeMs)

			// Move to cell B (cell A accumulation stops)
			fakeTimeMs += 1_000L
			engine.onLocation(51.5074, -0.1278, 10f, fakeTimeMs)

			// Stay 120s in cell B
			fakeTimeMs += 120_000L

			val result = engine.finalize(fakeTimeMs)

			result.shouldNotBeNull()
			// Cell B had 120s -> TRAVERSED_ON_FOOT
			result.quality shouldBe DiscoveryQuality.TRAVERSED_ON_FOOT
		}
	}

}
