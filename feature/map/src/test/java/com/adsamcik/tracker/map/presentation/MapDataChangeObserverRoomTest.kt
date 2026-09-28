package com.adsamcik.tracker.map.presentation

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapDataChangeObserverRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun completeNativeDependencyRegistrationAndCommittedEpochFenceInvalidateBothInputs(): Unit = runBlocking {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		val observer = MapDataChangeObserver(database)
		val events = Channel<MapDataChange>(Channel.UNLIMITED)
		val collector = launch(Dispatchers.Default) { observer.changes().collect { events.send(it) } }
		try {
			// Room registers every native fact/cursor/receipt/authority table before this handshake.
			withTimeout(5_000L) { events.receive() } shouldBe MapDataChange.Ready
			database.withTransaction {
				database.sourceEvidenceStateDao().updateLifecycle(
					epoch = 1L, retainedFromMs = 10L, updatedAtMs = 20L,
				) shouldBe 1
			}
			withTimeout(5_000L) { events.receive() } shouldBe MapDataChange.Committed(
				setOf(MapDataSource.Location, MapDataSource.Wifi, MapDataSource.Cell),
			)
			database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
		} finally {
			collector.cancelAndJoin()
			events.close()
		}
	}
}
