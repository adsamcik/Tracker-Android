package com.adsamcik.tracker.activity.ui

import android.content.Context
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Basic persistence tests for SessionActivity CRUD mirroring logic used by SessionActivityActivityCompose.
 * These are not UI tests – they validate DB layer expectations the composable relies upon.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class SessionActivityActivityComposeTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @BeforeEach
    fun setup() {
    context = RuntimeEnvironment.getApplication().applicationContext
    db = AppDatabase.testDatabase(context)
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun addEditDeleteActivity_flow() = runBlocking {
        val dao = db.activityDao()

        // Add
        val inserted = SessionActivity(0, "Hike", null)
        val id = dao.insert(inserted)
        id shouldBeGreaterThan 0

        // Read
        val all = dao.getAll()
        all shouldHaveSize 1
        all.first().name shouldBe "Hike"

        // Edit
        val updated = all.first().copy(name = "Trail Walk")
        dao.update(updated)
        val afterUpdate = dao.getAll()
        afterUpdate.first().name shouldBe "Trail Walk"

        // Delete
        dao.delete(updated.id)
        val afterDelete = dao.getAll()
        afterDelete.shouldBeEmpty()
    }

    @Test
    fun undoDelete_scenario() = runBlocking {
        val dao = db.activityDao()
        val id = dao.insert(SessionActivity(0, "Swim", null))
        val original = dao.getAll().first()
        // Simulate swipe remove (removed from list but not yet deleted) then undo -> no DB delete
        // Compose flow only deletes after snackbar dismissal without undo, so here we just assert existing row remains.
        val before = dao.getAll()
        before shouldHaveSize 1
        before.first().id shouldBe original.id

        // Simulate final delete path
        dao.delete(original.id)
        val after = dao.getAll()
        after.shouldBeEmpty()
    }
}
