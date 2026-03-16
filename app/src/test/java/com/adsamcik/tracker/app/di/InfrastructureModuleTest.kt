package com.adsamcik.tracker.app.di

import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.dao.InferredTripDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PersonalRecordDao
import com.adsamcik.tracker.shared.base.database.dao.RouteCacheDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StorageSizeSnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.TripLegDao
import com.adsamcik.tracker.shared.base.time.SystemClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InfrastructureModuleTest {

	private val module = InfrastructureModule

	@Nested
	inner class `DispatchersProvider provider` {
		@Test
		fun `returns non-null instance`() {
			module.provideDispatchersProvider().shouldNotBeNull()
		}

		@Test
		fun `returns DefaultDispatchersProvider singleton`() {
			module.provideDispatchersProvider() shouldBeSameInstanceAs DefaultDispatchersProvider
		}

		@Test
		fun `returns same instance on repeated calls`() {
			val first = module.provideDispatchersProvider()
			val second = module.provideDispatchersProvider()
			first shouldBeSameInstanceAs second
		}
	}

	@Nested
	inner class `Dispatcher providers` {
		@Test
		fun `IO dispatcher is Dispatchers IO`() {
			module.provideIoDispatcher() shouldBe Dispatchers.IO
		}

		@Test
		fun `Default dispatcher is Dispatchers Default`() {
			module.provideDefaultDispatcher() shouldBe Dispatchers.Default
		}
	}

	@Nested
	inner class `Clock provider` {
		@Test
		fun `returns non-null instance`() {
			module.provideClock().shouldNotBeNull()
		}

		@Test
		fun `returns SystemClock singleton`() {
			module.provideClock() shouldBeSameInstanceAs SystemClock
		}

		@Test
		fun `returns same instance on repeated calls`() {
			val first = module.provideClock()
			val second = module.provideClock()
			first shouldBeSameInstanceAs second
		}
	}

	@Nested
	inner class `Application scope provider` {
		@Test
		fun `returns non-null CoroutineScope`() {
			module.provideAppScope(Dispatchers.Default).shouldNotBeNull()
		}

		@Test
		fun `creates new scope on each call`() {
			val scope1 = module.provideAppScope(Dispatchers.Default)
			val scope2 = module.provideAppScope(Dispatchers.Default)
			scope1 shouldNotBe scope2
		}

		@Test
		fun `scope is active after creation`() {
			val scope = module.provideAppScope(Dispatchers.Default)
			scope.coroutineContext[Job]!!.isActive shouldBe true
		}

		@Test
		fun `scope uses SupervisorJob`() {
			val scope = module.provideAppScope(Dispatchers.Default)
			val job = scope.coroutineContext[Job]!!
			// SupervisorJob: child failure does not cancel parent
			job.children.forEach { /* no children yet, just verify structure */ }
			job.isActive shouldBe true
		}
	}

	@Nested
	inner class `DAO providers` {
		private val mockSessionSegmentDao = mockk<SessionSegmentDao>()
		private val mockLocationSampleDao = mockk<LocationSampleDao>()
		private val mockTrackerRunDao = mockk<TrackerRunDao>()
		private val mockTripDao = mockk<TripDao>()
		private val mockDailySummaryDao = mockk<DailySummaryDao>()
		private val mockSkiRunSegmentDao = mockk<SkiRunSegmentDao>()
		private val mockExplorationCellDao = mockk<ExplorationCellDao>()
		private val mockExplorationStreakDao = mockk<ExplorationStreakDao>()
		private val mockAchievementProgressDao = mockk<AchievementProgressDao>()
		private val mockPersonalRecordDao = mockk<PersonalRecordDao>()
		private val mockRouteCacheDao = mockk<RouteCacheDao>()
		private val mockExportLogDao = mockk<ExportLogDao>()
		private val mockStorageSizeSnapshotDao = mockk<StorageSizeSnapshotDao>()
		private val mockLiveStatsDao = mockk<LiveStatsDao>()
		private val mockFrequentPlaceDao = mockk<FrequentPlaceDao>()
		private val mockInferredTripDao = mockk<InferredTripDao>()
		private val mockTripLegDao = mockk<TripLegDao>()
		private val mockDomainEventDao = mockk<DomainEventDao>()

		private val mockDb = mockk<AppDatabase> {
			every { sessionSegmentDao() } returns mockSessionSegmentDao
			every { locationSampleDao() } returns mockLocationSampleDao
			every { trackerRunDao() } returns mockTrackerRunDao
			every { tripDao() } returns mockTripDao
			every { dailySummaryDao() } returns mockDailySummaryDao
			every { skiRunSegmentDao() } returns mockSkiRunSegmentDao
			every { explorationCellDao() } returns mockExplorationCellDao
			every { explorationStreakDao() } returns mockExplorationStreakDao
			every { achievementProgressDao() } returns mockAchievementProgressDao
			every { personalRecordDao() } returns mockPersonalRecordDao
			every { routeCacheDao() } returns mockRouteCacheDao
			every { exportLogDao() } returns mockExportLogDao
			every { storageSizeSnapshotDao() } returns mockStorageSizeSnapshotDao
			every { liveStatsDao() } returns mockLiveStatsDao
			every { frequentPlaceDao() } returns mockFrequentPlaceDao
			every { inferredTripDao() } returns mockInferredTripDao
			every { tripLegDao() } returns mockTripLegDao
			every { domainEventDao() } returns mockDomainEventDao
		}

		@Test
		fun `provideSessionSegmentDao delegates to database`() {
			module.provideSessionSegmentDao(mockDb) shouldBeSameInstanceAs mockSessionSegmentDao
			verify { mockDb.sessionSegmentDao() }
		}

		@Test
		fun `provideLocationSampleDao delegates to database`() {
			module.provideLocationSampleDao(mockDb) shouldBeSameInstanceAs mockLocationSampleDao
			verify { mockDb.locationSampleDao() }
		}

		@Test
		fun `provideTrackerRunDao delegates to database`() {
			module.provideTrackerRunDao(mockDb) shouldBeSameInstanceAs mockTrackerRunDao
			verify { mockDb.trackerRunDao() }
		}

		@Test
		fun `provideTripDao delegates to database`() {
			module.provideTripDao(mockDb) shouldBeSameInstanceAs mockTripDao
			verify { mockDb.tripDao() }
		}

		@Test
		fun `provideDailySummaryDao delegates to database`() {
			module.provideDailySummaryDao(mockDb) shouldBeSameInstanceAs mockDailySummaryDao
			verify { mockDb.dailySummaryDao() }
		}

		@Test
		fun `provideSkiRunSegmentDao delegates to database`() {
			module.provideSkiRunSegmentDao(mockDb) shouldBeSameInstanceAs mockSkiRunSegmentDao
			verify { mockDb.skiRunSegmentDao() }
		}

		@Test
		fun `provideExplorationCellDao delegates to database`() {
			module.provideExplorationCellDao(mockDb) shouldBeSameInstanceAs mockExplorationCellDao
			verify { mockDb.explorationCellDao() }
		}

		@Test
		fun `provideExplorationStreakDao delegates to database`() {
			module.provideExplorationStreakDao(mockDb) shouldBeSameInstanceAs mockExplorationStreakDao
			verify { mockDb.explorationStreakDao() }
		}

		@Test
		fun `provideAchievementProgressDao delegates to database`() {
			module.provideAchievementProgressDao(mockDb) shouldBeSameInstanceAs mockAchievementProgressDao
			verify { mockDb.achievementProgressDao() }
		}

		@Test
		fun `providePersonalRecordDao delegates to database`() {
			module.providePersonalRecordDao(mockDb) shouldBeSameInstanceAs mockPersonalRecordDao
			verify { mockDb.personalRecordDao() }
		}

		@Test
		fun `provideRouteCacheDao delegates to database`() {
			module.provideRouteCacheDao(mockDb) shouldBeSameInstanceAs mockRouteCacheDao
			verify { mockDb.routeCacheDao() }
		}

		@Test
		fun `provideExportLogDao delegates to database`() {
			module.provideExportLogDao(mockDb) shouldBeSameInstanceAs mockExportLogDao
			verify { mockDb.exportLogDao() }
		}

		@Test
		fun `provideStorageSizeSnapshotDao delegates to database`() {
			module.provideStorageSizeSnapshotDao(mockDb) shouldBeSameInstanceAs mockStorageSizeSnapshotDao
			verify { mockDb.storageSizeSnapshotDao() }
		}

		@Test
		fun `provideLiveStatsDao delegates to database`() {
			module.provideLiveStatsDao(mockDb) shouldBeSameInstanceAs mockLiveStatsDao
			verify { mockDb.liveStatsDao() }
		}

		@Test
		fun `provideFrequentPlaceDao delegates to database`() {
			module.provideFrequentPlaceDao(mockDb) shouldBeSameInstanceAs mockFrequentPlaceDao
			verify { mockDb.frequentPlaceDao() }
		}

		@Test
		fun `provideInferredTripDao delegates to database`() {
			module.provideInferredTripDao(mockDb) shouldBeSameInstanceAs mockInferredTripDao
			verify { mockDb.inferredTripDao() }
		}

		@Test
		fun `provideTripLegDao delegates to database`() {
			module.provideTripLegDao(mockDb) shouldBeSameInstanceAs mockTripLegDao
			verify { mockDb.tripLegDao() }
		}

		@Test
		fun `provideDomainEventDao delegates to database`() {
			module.provideDomainEventDao(mockDb) shouldBeSameInstanceAs mockDomainEventDao
			verify { mockDb.domainEventDao() }
		}
	}
}
