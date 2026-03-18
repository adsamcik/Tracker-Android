package com.adsamcik.tracker.sbase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
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
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class ActivityDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: ActivityDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.activityDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	@Test
	fun `insert and retrieve activity by id`() = runTest {
		val activity = SessionActivity(name = "Running", iconName = "ic_running")
		val id = dao.insert(activity)

		val result = dao.get(id)
		result.shouldNotBeNull()
		result.id shouldBe id
		result.name shouldBe "Running"
		result.iconName shouldBe "ic_running"
	}

	@Test
	fun `get returns null for nonexistent id`() = runTest {
		dao.get(999L).shouldBeNull()
	}

	@Test
	fun `getAll returns all activities`() = runTest {
		dao.insert(SessionActivity(name = "Running"))
		dao.insert(SessionActivity(name = "Walking"))
		dao.insert(SessionActivity(name = "Cycling"))

		dao.getAll() shouldHaveSize 3
	}

	@Test
	fun `getAllUser returns only user-created activities`() = runTest {
		// User activities get auto-generated positive IDs
		dao.insert(SessionActivity(name = "Custom Running"))
		dao.insert(SessionActivity(name = "Custom Walking"))

		// Native activity with negative ID
		dao.insert(SessionActivity(id = -1, name = "Native Walking", iconName = "ic_walk"))

		val userActivities = dao.getAllUser()
		userActivities shouldHaveSize 2
		userActivities.all { it.id >= 0 } shouldBe true
	}

	@Test
	fun `getAllUser returns empty when only native activities exist`() = runTest {
		dao.insert(SessionActivity(id = -1, name = "Native Walking"))
		dao.insert(SessionActivity(id = -2, name = "Native Running"))

		dao.getAllUser().shouldBeEmpty()
	}

	@Test
	fun `getAll includes both user and native activities`() = runTest {
		dao.insert(SessionActivity(name = "Custom Activity"))
		dao.insert(SessionActivity(id = -1, name = "Native Walking"))

		dao.getAll() shouldHaveSize 2
	}

	@Test
	fun `find returns activity with matching name`() = runTest {
		dao.insert(SessionActivity(name = "Running"))
		dao.insert(SessionActivity(name = "Walking"))

		val result = dao.find("Walking")
		result.shouldNotBeNull()
		result.name shouldBe "Walking"
	}

	@Test
	fun `find returns null for nonexistent name`() = runTest {
		dao.insert(SessionActivity(name = "Running"))

		dao.find("Swimming").shouldBeNull()
	}

	@Test
	fun `find is case sensitive`() = runTest {
		dao.insert(SessionActivity(name = "Running"))

		dao.find("running").shouldBeNull()
	}

	@Test
	fun `delete by id removes activity`() = runTest {
		val id = dao.insert(SessionActivity(name = "Running"))

		dao.delete(id)

		dao.get(id).shouldBeNull()
	}

	@Test
	fun `delete by id does not affect other activities`() = runTest {
		val id1 = dao.insert(SessionActivity(name = "Running"))
		val id2 = dao.insert(SessionActivity(name = "Walking"))

		dao.delete(id1)

		dao.get(id1).shouldBeNull()
		dao.get(id2).shouldNotBeNull()
	}

	@Test
	fun `delete by object removes activity`() = runTest {
		val activity = SessionActivity(name = "Running")
		val id = dao.insert(activity)

		val inserted = dao.get(id)!!
		dao.delete(inserted)

		dao.get(id).shouldBeNull()
	}

	@Test
	fun `update activity persists changes`() = runTest {
		val id = dao.insert(SessionActivity(name = "Runing", iconName = "ic_old"))

		val activity = dao.get(id)!!
		val updated = SessionActivity(id = activity.id, name = "Running", iconName = "ic_running")
		dao.update(updated)

		val result = dao.get(id)!!
		result.name shouldBe "Running"
		result.iconName shouldBe "ic_running"
	}

	@Test
	fun `insert with null iconName stores null`() = runTest {
		val id = dao.insert(SessionActivity(name = "Custom"))

		val result = dao.get(id)!!
		result.iconName.shouldBeNull()
	}

	@Test
	fun `insert native activity with negative id preserves id`() = runTest {
		val nativeActivity = SessionActivity(id = -5, name = "Driving", iconName = "ic_car")
		dao.insert(nativeActivity)

		val result = dao.get(-5)
		result.shouldNotBeNull()
		result.id shouldBe -5
		result.name shouldBe "Driving"
	}

	@Test
	fun `batch insert inserts all activities`() = runTest {
		val activities = listOf(
			SessionActivity(name = "Running"),
			SessionActivity(name = "Walking"),
			SessionActivity(name = "Cycling")
		)
		val ids = dao.insert(activities)

		ids shouldHaveSize 3
		dao.getAll() shouldHaveSize 3
	}
}
