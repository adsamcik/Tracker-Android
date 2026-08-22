package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSourcePlanStoreTest {
	private lateinit var database: AppDatabase
	private lateinit var store: RoomSourcePlanStore

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		store = RoomSourcePlanStore(database, SourcePlanCodec())
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `round trip retains policy binding and collision cannot change it`() = runTest {
		val plan = SemanticAcquisitionPlanFactory().create(
			settings = TrackingParamsState(sourcePolicyRevision = 7),
			revision = 1,
			createdAtMs = 100,
			environment = SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, setOf(1)),
		)
		store.persistDesired(plan, DesiredPlanStatus.DESIRED)

		store.load(plan.revision)?.sourcePolicyRevision shouldBe 7
		shouldThrow<IllegalStateException> {
			store.persistDesired(
				plan.copy(sourcePolicyRevision = 8),
				DesiredPlanStatus.APPLYING,
			)
		}
		database.sourcePlanStateDao().revision(plan.revision)?.status shouldBe
			DesiredPlanStatus.DESIRED.name
		database.sourcePlanStateDao().revision(plan.revision)?.sourcePolicyRevision shouldBe 7
	}
}
