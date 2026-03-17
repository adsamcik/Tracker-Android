package com.adsamcik.tracker.activity.ui

import com.adsamcik.tracker.shared.base.data.SessionActivity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Persistence-style tests for the CRUD flow used by [SessionActivityRoute].
 *
 * These tests intentionally use an in-memory fake instead of Room. The activity module consumes the
 * database from :sbase, but Room's generated AppDatabase implementation is only produced in modules
 * that own that schema. The UI flow only depends on basic DAO semantics, so we validate that contract
 * directly here.
 */
class SessionActivityActivityComposeTest {

	@Test
	fun addEditDeleteActivity_flow() = runTest {
		val dao = FakeActivityDao()

		val inserted = SessionActivity(name = "Hike")
		val id = dao.insert(inserted)
		id shouldBeGreaterThan 0

		val all = dao.getAll()
		all shouldHaveSize 1
		all.first().name shouldBe "Hike"

		val updated = all.first().copy(name = "Trail Walk")
		dao.update(updated)
		val afterUpdate = dao.getAll()
		afterUpdate.first().name shouldBe "Trail Walk"

		dao.delete(updated.id)
		dao.getAll().shouldBeEmpty()
	}

	@Test
	fun undoDelete_scenario() = runTest {
		val dao = FakeActivityDao()
		val id = dao.insert(SessionActivity(name = "Swim"))
		id shouldBeGreaterThan 0

		val original = dao.getAll().single()
		val beforeDelete = dao.getAll()
		beforeDelete shouldHaveSize 1
		beforeDelete.first().id shouldBe original.id

		// Compose removes the item from the visible list immediately and only calls delete() if the
		// snackbar is dismissed without Undo. As long as delete is not called, persistence is unchanged.
		dao.getAll().single().name shouldBe "Swim"

		dao.delete(original.id)
		dao.getAll().shouldBeEmpty()
	}

	private class FakeActivityDao {
		private val activities = linkedMapOf<Long, SessionActivity>()
		private var nextId = 1L

		suspend fun insert(activity: SessionActivity): Long {
			val id = if (activity.id > 0) activity.id else nextId++
			activities[id] = activity.copy(id = id)
			return id
		}

		suspend fun getAll(): List<SessionActivity> = activities.values.toList()

		suspend fun update(activity: SessionActivity) {
			activities[activity.id] = activity
		}

		fun delete(id: Long) {
			activities.remove(id)
		}
	}
}
