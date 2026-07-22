package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.dao.OrderedAltitudeSampleRow
import com.adsamcik.tracker.shared.base.database.dao.PlayerProfileDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.XpLedgerDao
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.stats.data.geo.CountryBoundaryLookup
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DefaultAchievementMetricsProviderAltitudeTest {

	private val provider = DefaultAchievementMetricsProvider(
		dailySummaryDao = mockk<DailySummaryDao>(relaxed = true),
		explorationCellDao = mockk<ExplorationCellDao>(relaxed = true),
		explorationStreakDao = mockk<ExplorationStreakDao>(relaxed = true),
		sessionSegmentDao = mockk<SessionSegmentDao>(relaxed = true),
		exportLogDao = mockk<ExportLogDao>(relaxed = true),
		xpLedgerDao = mockk<XpLedgerDao>(relaxed = true),
		playerProfileDao = mockk<PlayerProfileDao>(relaxed = true),
		miniGameScoreDao = mockk<MiniGameScoreDao>(relaxed = true),
		locationSampleDao = mockk<LocationSampleDao>(relaxed = true),
		countryLookup = mockk<CountryBoundaryLookup>(relaxed = true),
		achievementProgressDao = mockk<AchievementProgressDao>(relaxed = true),
	)

	private val totalAscent = DefaultAchievementMetricsProvider::class.java
		.getDeclaredMethod("totalAscent", List::class.java)
		.also { it.isAccessible = true }

	@Test
	fun `total ascent resets instead of bridging unknown datum clock and reference boundaries`() {
		val rows = listOf(
			row(100f, AltitudeDatum.ANDROID_MODEL_MSL, "boot-a"),
			row(110f, AltitudeDatum.FUSED_ANDROID_MODEL_MSL, "boot-a"), // +10
			row(1_000f, AltitudeDatum.UNKNOWN_LEGACY, "boot-a"),
			row(120f, AltitudeDatum.ANDROID_MODEL_MSL, "boot-a"), // New baseline, not +10.
			row(130f, AltitudeDatum.RELATIVE_BAROMETRIC, "boot-a"), // Datum boundary.
			row(140f, AltitudeDatum.RELATIVE_BAROMETRIC, "boot-a"), // +10.
			row(200f, AltitudeDatum.RELATIVE_BAROMETRIC, "boot-b"), // Clock boundary.
			row(220f, AltitudeDatum.RELATIVE_BAROMETRIC, "boot-b"), // +20.
		)

		invokeTotalAscent(rows) shouldBe 40.0
	}

	private fun row(
		altitudeM: Float,
		datum: AltitudeDatum,
		clockDomainId: String?,
	) = OrderedAltitudeSampleRow(altitudeM, datum, clockDomainId)

	private fun invokeTotalAscent(rows: List<OrderedAltitudeSampleRow>): Double =
		totalAscent.invoke(provider, rows) as Double
}
