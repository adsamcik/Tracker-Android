package com.adsamcik.tracker.statistics.summary

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.CellLocationDao
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.database.dao.WifiDataDao
import com.adsamcik.tracker.shared.base.database.data.TrackerSessionSummary
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
	private val database: AppDatabase = mockk()
	private val wifiDao: WifiDataDao = mockk()
	private val cellDao: CellLocationDao = mockk()
	private val locationDao: LocationDataDao = mockk()
	private val sessionDao: SessionDataDao = mockk()

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
		every { database.wifiDao() } returns wifiDao
		every { database.cellLocationDao() } returns cellDao
		every { database.locationDao() } returns locationDao
		every { database.sessionDao() } returns sessionDao
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun sessionSummary(
		duration: Long = 0L,
		collections: Int = 0,
		distanceInM: Float = 0f,
		distanceOnFootInM: Float = 0f,
		distanceInVehicleInM: Float = 0f,
		steps: Int = 0,
	) = TrackerSessionSummary(
		duration = duration,
		collections = collections,
		distanceInM = distanceInM,
		distanceOnFootInM = distanceOnFootInM,
		distanceInVehicleInM = distanceInVehicleInM,
		steps = steps,
	)

	private fun stubDaoCounts(
		locationCount: Long = 0L,
		wifiCount: Long = 0L,
		cellCount: Long = 0L,
		sessionCount: Long = 0L,
	) {
		every { locationDao.count() } returns locationCount
		every { wifiDao.count() } returns wifiCount
		every { cellDao.uniqueCount() } returns cellCount
		every { sessionDao.count() } returns sessionCount
	}

	private fun stubTimedDaoCounts(
		locationCount: Long = 0L,
		wifiCount: Long = 0L,
		cellCount: Long = 0L,
		sessionCount: Long = 0L,
	) {
		every { locationDao.count(any(), any()) } returns locationCount
		every { wifiDao.count(any(), any()) } returns wifiCount
		every { cellDao.uniqueCount(any(), any()) } returns cellCount
		every { sessionDao.count(any(), any()) } returns sessionCount
	}

	// =====================================================================
	// buildSummary
	// =====================================================================

	@Nested
	@DisplayName("buildSummary")
	inner class BuildSummary {

		@Test
		fun `returns exactly 10 stats for session summary plus counts`() {
			every { sessionDao.getSummary() } returns sessionSummary()
			stubDaoCounts()

			val result = SummaryGenerator.buildSummary(context)

			// 6 session stats + 4 count stats
			result shouldHaveSize 10
		}

		@Test
		fun `all stats have INFORMATION display type`() {
			every { sessionDao.getSummary() } returns sessionSummary()
			stubDaoCounts()

			val result = SummaryGenerator.buildSummary(context)

			result.forEach { stat ->
				stat.displayType shouldBe StatisticDisplayType.INFORMATION
			}
		}

		@Test
		fun `session summary stats appear before count stats`() {
			every { sessionDao.getSummary() } returns sessionSummary(
				duration = 3600000L,
				distanceInM = 5000f,
				distanceOnFootInM = 3000f,
				distanceInVehicleInM = 2000f,
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
			every { sessionDao.getSummary() } returns sessionSummary()
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
			every { sessionDao.getSummary() } returns sessionSummary(
				duration = 7200000L,
				distanceInM = 10000f,
				distanceOnFootInM = 6000f,
				distanceInVehicleInM = 4000f,
				collections = 250,
				steps = 12000,
			)
			stubDaoCounts(
				locationCount = 500L,
				wifiCount = 300L,
				cellCount = 50L,
				sessionCount = 10L,
			)

			val result = SummaryGenerator.buildSummary(context)

			result.forEach { stat ->
				(stat.data as String).shouldNotBeBlank()
			}
		}

		@Test
		fun `zero session data produces valid stats`() {
			every { sessionDao.getSummary() } returns sessionSummary()
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
			every { sessionDao.getSummary(any(), any()) } returns sessionSummary()
			stubTimedDaoCounts()

			val result = SummaryGenerator.buildSevenDaySummary(context)

			result shouldHaveSize 10
		}

		@Test
		fun `all stats have INFORMATION display type`() {
			every { sessionDao.getSummary(any(), any()) } returns sessionSummary()
			stubTimedDaoCounts()

			val result = SummaryGenerator.buildSevenDaySummary(context)

			result.forEach { stat ->
				stat.displayType shouldBe StatisticDisplayType.INFORMATION
			}
		}

		@Test
		fun `seven day summary includes session stats first`() {
			every { sessionDao.getSummary(any(), any()) } returns sessionSummary(
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
			every { sessionDao.getSummary(any(), any()) } returns sessionSummary()
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
			every { sessionDao.getSummary(any(), any()) } returns sessionSummary(
				duration = 14400000L,
				distanceInM = 25000f,
				distanceOnFootInM = 15000f,
				distanceInVehicleInM = 10000f,
				collections = 500,
				steps = 20000,
			)
			stubTimedDaoCounts(
				locationCount = 1000L,
				wifiCount = 800L,
				cellCount = 120L,
				sessionCount = 14L,
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
			every { sessionDao.getSummary(any(), any()) } returns sessionSummary()
			stubTimedDaoCounts()

			val result = SummaryGenerator.buildSevenDaySummary(context)

			result shouldHaveSize 10
		}
	}
}
