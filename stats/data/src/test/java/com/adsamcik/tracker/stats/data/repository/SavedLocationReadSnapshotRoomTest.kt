package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedLocationReadSnapshotRoomTest {
    private lateinit var database: AppDatabase

    @Before fun setup(): Unit = runBlocking {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
        database.sourceEvidenceStateDao().ensure()
    }

    @After fun close() = database.close()

    @Test fun `cancelling a saved read releases its native transaction`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reading = async(Dispatchers.IO) {
            database.withSavedLocationReadSnapshot(Dispatchers.IO) {
                database.sourceEvidenceStateDao().get()
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
        reading.cancel()
        kotlinx.coroutines.withTimeout(5_000) { reading.join() }
        kotlinx.coroutines.withTimeout(5_000) {
            database.withTransaction {
                database.sourceEvidenceStateDao().incrementRevision(3_000)
            }
        }
        assertEquals(1L, database.sourceEvidenceStateDao().get()?.revision)
    }

    @Test fun `an off-thread inherited writer cannot silently open a different snapshot`() = runBlocking {
        database.withTransaction {
            database.sourceEvidenceStateDao().incrementRevision(3_000)
            assertFailsWith<IllegalStateException> {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    database.withSavedLocationReadSnapshot(Dispatchers.IO) {
                        fail("A sibling snapshot must not replace the owning writer snapshot")
                    }
                }
            }
            assertEquals(1L, database.sourceEvidenceStateDao().get()?.revision)
        }
    }

}
