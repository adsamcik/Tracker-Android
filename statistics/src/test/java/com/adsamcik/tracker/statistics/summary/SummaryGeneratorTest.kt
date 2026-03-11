package com.adsamcik.tracker.statistics.summary

import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.detail.StatisticDisplayType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SummaryGenerator")
class SummaryGeneratorTest {

	private val context: Context = mockk(relaxed = true)
	private val resources: Resources = mockk(relaxed = true)
	private val database: AppDatabase = mockk(relaxed = true)
	private val wifiObservationDao: WifiObservationDao = mockk()
	private val cellSampleDao: CellSampleDao = mockk()
	private val tripDao: TripDao = mockk(relaxed = true)

	@BeforeEach
	fun setUp() {
		mockkObject(AppDatabase.Companion)
		mockkObject(TrackerSettingsQuick)

		every { context.resources } returns resources
		every { resources.getString(any(), *anyVararg()) } answers {
			"formatted_${args.drop(1).joinToString("_")}"
		}
		every { resources.getString(any()) } answers { "str_${firstArg<Int>()}" }

		every { AppDatabase.database(any()) } returns database
		every { TrackerSettingsQuick.lengthSystem(any()) } returns LengthSystem.Metric
		every { database.wifiObservationDao() } returns wifiObservationDao
		every { database.cellSampleDao() } returns cellSampleDao
		every { database.tripDao() } returns tripDao
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	/**
	 * Stubs the raw SQL cursor returned by database.query() for segment summary queries.
	 * Columns: duration, collections (sample_count), distance, steps
	 */
	private fun stubSegmentSummaryCursor(
		duration: Long = 0L,
		collections: Int = 0,
		distanceInM: Float = 0f,
		steps: Int = 0,
	) {
		val cursor = mockk<Cursor>()
		every { cursor.moveToFirst() } returns true
		every { cursor.getLong(0) } returns duration
		every { cursor.getInt(1) } returns collections
		every { cursor.getFloat(2) } returns distanceInM
		every { cursor.getInt(3) } returns steps
		every { cursor.close() } returns Unit
		every { database.query(any<SupportSQLiteQuery>()) } returns cursor
	}

	private fun stubDaoCounts(
		wifiCount: Long = 0L,
		cellCount: Long = 0L,
		tripCount: Long = 0L,
	) {
		every { wifiObservationDao.countDistinctBssid() } returns wifiCount
		every { cellSampleDao.uniqueCount() } returns cellCount
		every { tripDao.countAllTrips() } returns tripCount
	}

	private fun stubTimedDaoCounts(
		wifiCount: Long = 0L,
		cellCount: Long = 0L,
		tripCount: Long = 0L,
	) {
		every { wifiObservationDao.countDistinctBssid(any(), any()) } returns wifiCount
		every { cellSampleDao.uniqueCount(any(), any()) } returns cellCount
		every { tripDao.countTripsBetween(any(), any()) } returns tripCount
	}

	// =====================================================================
	// buildSummary
	// =====================================================================

	@Nested
	@DisplayName("buildSummary")
	inner class BuildSummary {

		@Test
		fun `returns exactly 10 stats for session summary plus counts`() {
			stubSegmentSummaryCursor()
			stubDaoCounts()

			val result = SummaryGenerator.buildSummary(context)

			// 6 session stats + 4 count stats
			result shouldHaveSize 10
		}

		@Test
		fun `all stats have INFORMATION display type`() {
			stubSegmentSummaryCursor()
			stubDaoCounts()

			val result = SummaryGenerator.buildSummary(context)

			result.forEach { stat ->
				stat.displayType shouldBe StatisticDisplayType.INFORMATION
			}
		}

		@Test
		fun `session summary stats appear before count stats`() {
			stubSegmentSummaryCursor(
				duration = 3600000L,
				distanceInM = 5000f,
				collections = 100,
				steps = 5000,
			)
			stubDaoCounts()

			val result = SummaryGenerator.buildSummary(context)

			// First 6 stats should be session summary: time, distances, collections, steps
			result[0].nameRes shouldBe R.string.stats_time
			result[1].nameRes shouldBe R.string.stats_distance_total
			result[2].nameRes shouldBe R.string.stats_distance_on_foot
			result[3].nameRes shouldBe R.string.stats_distance_in_vehicle
			result[4].nameRes shouldBe R.string.stats_collections
			result[5].nameRes shouldBe R.string.stats_steps
		}

		@Test
		fun `count stats include location, wifi, cell, and session counts`() {
			stubSegmentSummaryCursor()
			stubDaoCounts()

			val result = SummaryGenerator.buildSummary(context)

			// Last 4 stats are counts
			result[6].nameRes shouldBe R.string.stats_location_count
			result[7].nameRes shouldBe R.string.stats_wifi_count
			result[8].nameRes shouldBe R.string.stats_cell_count
			result[9].nameRes shouldBe R.string.stats_session_count
		}

		@Test
		fun `all stat data values are non-blank strings`() {
			stubSegmentSummaryCursor(
				duration = 7200000L,
				distanceInM = 10000f,
				collections = 250,
				steps = 12000,
			)
			stubDaoCounts(
				wifiCount = 300L,
				cellCount = 50L,
				tripCount = 10L,
			)

			val result = SummaryGenerator.buildSummary(context)

			result.forEach { stat ->
				(stat.data as String).shouldNotBeBlank()
			}
		}

		@Test
		fun `zero session data produces valid stats`() {
			stubSegmentSummaryCursor()
			stubDaoCounts()

			val result = SummaryGenerator.buildSummary(context)

			result shouldHaveSize 10
			result.forEach { stat ->
				stat.displayType shouldBe StatisticDisplayType.INFORMATION
			}
		}
	}

	// =====================================================================
	// buildSevenDaySummary
	// =====================================================================

	@Nested
	@DisplayName("buildSevenDaySummary")
	inner class BuildSevenDaySummary {

		@Test
		fun `returns exactly 10 stats for seven day summary`() {
			stubSegmentSummaryCursor()
			stubTimedDaoCounts()

			val result = SummaryGenerator.buildSevenDaySummary(context)

			result shouldHaveSize 10
		}

		@Test
		fun `all stats have INFORMATION display type`() {
			stubSegmentSummaryCursor()
			stubTimedDaoCounts()

			val result = SummaryGenerator.buildSevenDaySummary(context)

			result.forEach { stat ->
				stat.displayType shouldBe StatisticDisplayType.INFORMATION
			}
		}

		@Test
		fun `seven day summary includes session stats first`() {
			stubSegmentSummaryCursor(
				duration = 1800000L,
				distanceInM = 2500f,
				steps = 3000,
			)
			stubTimedDaoCounts()

			val result = SummaryGenerator.buildSevenDaySummary(context)

			result[0].nameRes shouldBe R.string.stats_time
			result[1].nameRes shouldBe R.string.stats_distance_total
			result[2].nameRes shouldBe R.string.stats_distance_on_foot
			result[3].nameRes shouldBe R.string.stats_distance_in_vehicle
			result[4].nameRes shouldBe R.string.stats_collections
			result[5].nameRes shouldBe R.string.stats_steps
		}

		@Test
		fun `seven day count stats have correct resource IDs`() {
			stubSegmentSummaryCursor()
			stubTimedDaoCounts()

			val result = SummaryGenerator.buildSevenDaySummary(context)

			// Count stats in seven-day summary have different order than buildSummary
			result[6].nameRes shouldBe R.string.stats_session_count
			result[7].nameRes shouldBe R.string.stats_location_count
			result[8].nameRes shouldBe R.string.stats_wifi_count
			result[9].nameRes shouldBe R.string.stats_cell_count
		}

		@Test
		fun `seven day summary with typical data produces valid stats`() {
			stubSegmentSummaryCursor(
				duration = 14400000L,
				distanceInM = 25000f,
				collections = 500,
				steps = 20000,
			)
			stubTimedDaoCounts(
				wifiCount = 800L,
				cellCount = 120L,
				tripCount = 14L,
			)

			val result = SummaryGenerator.buildSevenDaySummary(context)

			result shouldHaveSize 10
			result.forEach { stat ->
				stat.displayType shouldBe StatisticDisplayType.INFORMATION
				(stat.data as String).shouldNotBeBlank()
			}
		}

		@Test
		fun `zero data seven day summary still returns correct structure`() {
			stubSegmentSummaryCursor()
			stubTimedDaoCounts()

			val result = SummaryGenerator.buildSevenDaySummary(context)

			result shouldHaveSize 10
		}
	}
}
