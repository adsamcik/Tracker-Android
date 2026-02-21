package com.adsamcik.tracker.sbase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class SessionDataDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: SessionDataDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.sessionDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	private fun createSession(
		start: Long = 1000L,
		end: Long = 2000L,
		isUserInitiated: Boolean = true,
		collections: Int = 5,
		distanceInM: Float = 100f,
		distanceOnFootInM: Float = 50f,
		distanceInVehicleInM: Float = 50f,
		steps: Int = 200
	) = TrackerSession(
		start = start,
		end = end,
		isUserInitiated = isUserInitiated,
		collections = collections,
		distanceInM = distanceInM,
		distanceOnFootInM = distanceOnFootInM,
		distanceInVehicleInM = distanceInVehicleInM,
		steps = steps
	)

	@Test
	fun `insert and retrieve session returns correct data`() {
		val session = createSession(start = 1000L, end = 2000L, steps = 200, distanceInM = 100f)
		val id = dao.insert(session)

		val result = dao.get(id)
		result.shouldNotBeNull()
		result.id shouldBe id
		result.start shouldBe 1000L
		result.end shouldBe 2000L
		result.steps shouldBe 200
		result.distanceInM shouldBe 100f
		result.isUserInitiated shouldBe true
	}

	@Test
	fun `get returns null for nonexistent id`() {
		dao.get(999L).shouldBeNull()
	}

	@Test
	fun `getAll returns all inserted sessions`() {
		dao.insert(createSession(start = 1000L, end = 2000L))
		dao.insert(createSession(start = 3000L, end = 4000L))

		dao.getAll() shouldHaveSize 2
	}

	@Test
	fun `getBetween returns sessions starting in range`() {
		dao.insert(createSession(start = 1000L, end = 1500L))
		dao.insert(createSession(start = 3000L, end = 3500L))
		dao.insert(createSession(start = 5000L, end = 5500L))

		val result = dao.getBetween(2000L, 4000L)
		result shouldHaveSize 1
		result[0].start shouldBe 3000L
	}

	@Test
	fun `getBetween returns empty for no matches`() {
		dao.insert(createSession(start = 1000L, end = 1500L))

		dao.getBetween(5000L, 6000L).shouldBeEmpty()
	}

	@Test
	fun `getAllBetween returns sessions active in range`() {
		dao.insert(createSession(start = 1000L, end = 3000L))
		dao.insert(createSession(start = 5000L, end = 7000L))
		dao.insert(createSession(start = 9000L, end = 11000L))

		// Sessions where end >= from AND start <= to
		val result = dao.getAllBetween(2000L, 6000L)
		result shouldHaveSize 2
	}

	@Test
	fun `getAllBetween returns empty for no overlap`() {
		dao.insert(createSession(start = 1000L, end = 2000L))

		dao.getAllBetween(5000L, 6000L).shouldBeEmpty()
	}

	@Test
	fun `update session properties persists changes`() {
		val session = createSession(start = 1000L, end = 2000L, steps = 100)
		val id = dao.insert(session)

		val inserted = dao.get(id)!!
		inserted.steps = 500
		inserted.distanceInM = 999f
		dao.update(inserted)

		val updated = dao.get(id)
		updated.shouldNotBeNull()
		updated.steps shouldBe 500
		updated.distanceInM shouldBe 999f
	}

	@Test
	fun `update preserves unchanged fields`() {
		val session = createSession(
			start = 1000L,
			end = 2000L,
			steps = 100,
			collections = 10,
			distanceOnFootInM = 75f
		)
		val id = dao.insert(session)

		val inserted = dao.get(id)!!
		inserted.steps = 500
		dao.update(inserted)

		val updated = dao.get(id)!!
		updated.collections shouldBe 10
		updated.distanceOnFootInM shouldBe 75f
		updated.start shouldBe 1000L
	}

	@Test
	fun `delete session removes it from database`() {
		val session = createSession()
		val id = dao.insert(session)

		val inserted = dao.get(id)!!
		dao.delete(inserted)

		dao.get(id).shouldBeNull()
	}

	@Test
	fun `deleteAll removes all sessions`() {
		dao.insert(createSession(start = 1000L, end = 2000L))
		dao.insert(createSession(start = 3000L, end = 4000L))

		dao.deleteAll()

		dao.getAll().shouldBeEmpty()
	}

	@Test
	fun `count returns correct number of sessions`() {
		dao.count() shouldBe 0L

		dao.insert(createSession(start = 1000L, end = 2000L))
		dao.insert(createSession(start = 3000L, end = 4000L))

		dao.count() shouldBe 2L
	}

	@Test
	fun `count with range counts overlapping sessions`() {
		dao.insert(createSession(start = 1000L, end = 2000L))
		dao.insert(createSession(start = 3000L, end = 4000L))
		dao.insert(createSession(start = 5000L, end = 6000L))

		// count(from, to) WHERE to >= start AND from <= end
		dao.count(2500L, 4500L) shouldBe 1L
	}

	@Test
	fun `getSummary aggregates all sessions`() {
		dao.insert(createSession(start = 1000L, end = 2000L, steps = 100, distanceInM = 500f, collections = 3))
		dao.insert(createSession(start = 3000L, end = 5000L, steps = 200, distanceInM = 300f, collections = 7))

		val summary = dao.getSummary()
		summary.duration shouldBe 3000L
		summary.steps shouldBe 300
		summary.distanceInM shouldBe 800f
		summary.collections shouldBe 10
	}

	@Test
	fun `getSummary with range filters by start time`() {
		dao.insert(createSession(start = 1000L, end = 2000L, steps = 100, distanceInM = 500f))
		dao.insert(createSession(start = 3000L, end = 5000L, steps = 200, distanceInM = 300f))
		dao.insert(createSession(start = 8000L, end = 9000L, steps = 999, distanceInM = 999f))

		val summary = dao.getSummary(from = 0L, to = 6000L)
		summary.steps shouldBe 300
		summary.distanceInM shouldBe 800f
	}

	@Test
	fun `getTodaySummary returns summary for time range`() = runTest {
		dao.insert(
			createSession(
				start = 1000L,
				end = 2000L,
				steps = 100,
				distanceInM = 500f,
				collections = 3
			)
		)
		dao.insert(
			createSession(
				start = 3000L,
				end = 5000L,
				steps = 200,
				distanceInM = 300f,
				collections = 7
			)
		)
		dao.insert(createSession(start = 10000L, end = 11000L, steps = 999))

		val summary = dao.getTodaySummary(0L, 6000L)
		summary.shouldNotBeNull()
		summary.sessionCount shouldBe 2
		summary.steps shouldBe 300
		summary.distanceInM shouldBe 800f
	}

	@Test
	fun `getTodaySummary returns zero counts for empty range`() = runTest {
		val summary = dao.getTodaySummary(0L, 1000L)
		summary.shouldNotBeNull()
		summary.sessionCount shouldBe 0
	}

	@Test
	fun `range returns min start and max end`() {
		dao.insert(createSession(start = 5000L, end = 6000L))
		dao.insert(createSession(start = 1000L, end = 2000L))
		dao.insert(createSession(start = 8000L, end = 10000L))

		val range = dao.range()
		range.start shouldBe 1000L
		range.endInclusive shouldBe 10000L
	}

	@Test
	fun `insert with duplicate id is ignored`() {
		val session1 = createSession(start = 1000L, end = 2000L, steps = 100)
		val id = dao.insert(session1)

		val session2 = createSession(start = 3000L, end = 4000L, steps = 999)
		session2.id = id
		dao.insert(session2)

		val result = dao.get(id)!!
		result.steps shouldBe 100
	}

	@Test
	fun `batch insert inserts multiple sessions`() {
		val sessions = listOf(
			createSession(start = 1000L, end = 2000L),
			createSession(start = 3000L, end = 4000L),
			createSession(start = 5000L, end = 6000L)
		)
		val ids = dao.insert(sessions)

		ids shouldHaveSize 3
		dao.getAll() shouldHaveSize 3
	}

	@Test
	fun `getLast returns most recently inserted session`() {
		dao.insert(createSession(start = 1000L, end = 2000L, steps = 100))
		dao.insert(createSession(start = 3000L, end = 4000L, steps = 200))
		dao.insert(createSession(start = 5000L, end = 6000L, steps = 300))

		val last = dao.getLast(1)
		last.shouldNotBeNull()
		last.steps shouldBe 300
	}

	@Test
	fun `getLast returns null for empty database`() {
		dao.getLast(1).shouldBeNull()
	}
}
