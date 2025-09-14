package com.adsamcik.tracker.activity.ui

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Basic persistence tests for SessionActivity CRUD mirroring logic used by SessionActivityActivityCompose.
 * These are not UI tests – they validate DB layer expectations the composable relies upon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionActivityActivityComposeTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
    context = RuntimeEnvironment.getApplication().applicationContext
    db = AppDatabase.testDatabase(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addEditDeleteActivity_flow() = runBlocking {
        val dao = db.activityDao()

        // Add
        val inserted = SessionActivity(0, "Hike", null)
        val id = dao.insert(inserted)
        assertTrue(id > 0)

        // Read
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("Hike", all.first().name)

        // Edit
        val updated = all.first().copy(name = "Trail Walk")
        dao.update(updated)
        val afterUpdate = dao.getAll()
        assertEquals("Trail Walk", afterUpdate.first().name)

        // Delete
        dao.delete(updated.id)
        val afterDelete = dao.getAll()
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun undoDelete_scenario() = runBlocking {
        val dao = db.activityDao()
        val id = dao.insert(SessionActivity(0, "Swim", null))
        val original = dao.getAll().first()
        // Simulate swipe remove (removed from list but not yet deleted) then undo -> no DB delete
        // Compose flow only deletes after snackbar dismissal without undo, so here we just assert existing row remains.
        val before = dao.getAll()
        assertEquals(1, before.size)
        assertEquals(original.id, before.first().id)

        // Simulate final delete path
        dao.delete(original.id)
        val after = dao.getAll()
        assertTrue(after.isEmpty())
    }
}
