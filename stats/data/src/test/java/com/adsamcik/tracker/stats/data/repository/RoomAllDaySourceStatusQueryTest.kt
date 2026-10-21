package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.TRACKING_HISTORY_INVALIDATION_TABLES
import androidx.room.InvalidationTracker
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRead
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRequest
import com.adsamcik.tracker.stats.api.repository.HistoryObservationWindow
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.Test as RoomTest
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAllDaySourceStatusQueryTest {

	@Test
	fun `closed storage reports an actual Activity read unavailable without borrowing another source`() = runTest {
		val fixture = Fixture(StandardTestDispatcher(testScheduler))
		fixture.closeStorage()

		assertEquals(AllDaySourceStatusRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE),
			fixture.query.read(AllDaySourceStatusRequest.Sensor(TrackingSource.ACTIVITY, HistoryObservationWindow(0L, 2_000L))))

		fixture.assertNoOtherSourceAccess()
	}

	@Test
	fun `closed storage reports an actual Pressure read unavailable without borrowing another source`() = runTest {
		val fixture = Fixture(StandardTestDispatcher(testScheduler))
		fixture.closeStorage()

		assertEquals(AllDaySourceStatusRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE),
			fixture.query.read(AllDaySourceStatusRequest.Sensor(TrackingSource.PRESSURE, HistoryObservationWindow(0L, 2_000L))))

		fixture.assertNoOtherSourceAccess()
	}

	@Test
	fun `cancelling an actual sensor read preserves cancellation without dependency access`() = runTest {
		for (source in listOf(TrackingSource.ACTIVITY, TrackingSource.PRESSURE)) {
			val fixture = Fixture(StandardTestDispatcher(testScheduler))
			// Capture read's own outcome, rather than only asserting that its outer job was cancelled.
			val outcome = CompletableDeferred<Result<AllDaySourceStatusRead>>()
			val pending = launch(start = CoroutineStart.UNDISPATCHED) {
				outcome.complete(runCatching {
					fixture.query.read(AllDaySourceStatusRequest.Sensor(source, HistoryObservationWindow(0L, 2_000L)))
				})
			}
			// The real facade has entered read and suspended while handing off to its IO dispatcher.
			assertFalse(outcome.isCompleted)
			pending.cancel(CancellationException("All-day status request cancelled"))
			pending.join()

			assertIs<CancellationException>(outcome.await().exceptionOrNull())
			fixture.assertNoDependencyAccess()
		}
	}

	private class Fixture(io: CoroutineDispatcher) {
		// Strict mocks make even a database-open check or a swallowed reader failure observable.
		private val database: AppDatabase = mockk()
		private val steps: DefaultAmbientStepsHistoryRepository = mockk()
		private val wifi: RoomAmbientWifiRepository = mockk()
		private val cell: RoomAmbientCellRepository = mockk()
		val query = RoomAllDaySourceStatusQuery(database, steps, wifi, cell, io)
		fun closeStorage() { every { database.isOpen } returns false }
		fun assertNoOtherSourceAccess() { verify { listOf(steps, wifi, cell) wasNot Called } }

		fun assertNoDependencyAccess() {
			verify { listOf(database, steps, wifi, cell) wasNot Called }
		}
	}
}

/** Real native leaves remain readable independently of tracking settings and temporary acquisition logs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomAllDaySensorStatusQueryTest {
	private lateinit var database: AppDatabase
	private lateinit var query: RoomAllDaySourceStatusQuery

	@Before fun setup() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		query = RoomAllDaySourceStatusQuery(database, mockk(), mockk(), mockk(), Dispatchers.IO)
	}
	@After fun close() { database.close() }

	@RoomTest fun `shared history observer receives actual native Activity and Pressure table invalidations`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(updatedAtMs = 1L))
		val changes = Channel<Set<String>>(Channel.UNLIMITED)
		val observer = object : InvalidationTracker.Observer(TRACKING_HISTORY_INVALIDATION_TABLES.toTypedArray()) {
			override fun onInvalidated(tables: Set<String>) { changes.trySend(tables) }
		}
		database.invalidationTracker.addObserver(observer)
		try {
			database.activitySnapshotDao().insert(activity("changed-activity", 1_000L))
			database.pressureSampleDao().insert(pressure("changed-pressure", 2_000L))
			val observed = withContext(Dispatchers.IO) {
				withTimeout(10_000L) {
					var tables = emptySet<String>()
					while (!tables.containsAll(setOf("activity_snapshot", "pressure_sample"))) {
						tables += changes.receive()
					}
					tables
				}
			}
			assertEquals(true, observed.containsAll(setOf("activity_snapshot", "pressure_sample")))
		} finally {
			database.invalidationTracker.removeObserver(observer)
			changes.close()
		}
	}

	@RoomTest fun `native observations keep their original identity clock and Pressure window without current consent`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L))
		database.activitySnapshotDao().insert(activity("native", 1_000L))
		database.activitySnapshotDao().insert(activity("imported", 1_100L).copy(observationStamp = ObservationStampColumns(
			capabilityFlags = AllDaySensorObservationIdentity.IMPORTED_CAPABILITY)))
		database.activitySnapshotDao().insert(activity("legacy", 1_200L).copy(sourceSignalId = "older-session"))
		database.pressureSampleDao().insert(pressure("native-pressure", 2_000L))

		val activity = assertIs<AllDaySourceStatusRead.Sensor>(query.read(request(TrackingSource.ACTIVITY)))
		assertEquals(4L, activity.snapshot.collectedDataEpoch)
		assertEquals(listOf(AllDaySensorObservationIdentity.activity("native")), activity.observations.map { it.identity })
		assertEquals(1_000L, activity.observations.single().observedTimeMs)
		assertEquals(null, activity.observations.single().wallTimeUncertaintyMs)
		val pressure = assertIs<AllDaySourceStatusRead.Sensor>(query.read(request(TrackingSource.PRESSURE)))
		assertEquals(AllDaySensorObservationIdentity.pressure("native-pressure"), pressure.observations.single().identity)
		assertEquals(1_000_000_000L, pressure.observations.single().windowStartElapsedRealtimeNanos)
		assertEquals(2_000_000_000L, pressure.observations.single().windowEndElapsedRealtimeNanos)
		assertEquals(true, pressure.observations.single().isPartial)
		assertEquals(null, pressure.problem)
	}

	@RoomTest fun `imported rows cannot consume the native status limit for Activity or Pressure`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(updatedAtMs = 1L))
		val imported = ObservationStampColumns(capabilityFlags = AllDaySensorObservationIdentity.IMPORTED_CAPABILITY)
		database.activitySnapshotDao().insert(activity("imported-first", 1_000L).copy(observationStamp = imported))
		database.activitySnapshotDao().insert(activity("native", 2_000L))
		database.pressureSampleDao().insert(pressure("imported-first", 1_000L).copy(observationStamp = imported))
		database.pressureSampleDao().insert(pressure("native", 2_000L))
		for (source in listOf(TrackingSource.ACTIVITY, TrackingSource.PRESSURE)) {
			val read = assertIs<AllDaySourceStatusRead.Sensor>(query.read(request(source).copy(limit = 1)))
			assertEquals(1, read.observations.size)
			assertEquals(2_000L, read.observations.single().observedTimeMs)
			assertEquals(null, read.problem)
		}
		database.activitySnapshotDao().insert(activity("native-second", 2_500L))
		database.pressureSampleDao().insert(pressure("native-second", 2_500L))
		for (source in listOf(TrackingSource.ACTIVITY, TrackingSource.PRESSURE)) {
			assertEquals(AllDaySourceStatusRead.Unavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW),
				query.read(request(source).copy(limit = 1)))
		}
	}

	@RoomTest fun `saved floor and malformed Activity row do not erase independently valid sensor readings`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 4L,
			retainedFromMs = 1_500L, updatedAtMs = 1L))
		database.activitySnapshotDao().insert(activity("expired", 1_000L))
		database.activitySnapshotDao().insert(activity("malformed", 1_600L).copy(confidence = 101))
		database.activitySnapshotDao().insert(activity("retained", 1_900L))
		database.pressureSampleDao().insert(pressure("pressure", 2_000L))

		val activity = assertIs<AllDaySourceStatusRead.Sensor>(query.read(request(TrackingSource.ACTIVITY)))
		assertEquals(listOf(AllDaySensorObservationIdentity.activity("retained")), activity.observations.map { it.identity })
		assertEquals(HistoryProblemReason.INTEGRITY_UNAVAILABLE, activity.problem)
		assertEquals(1, assertIs<AllDaySourceStatusRead.Sensor>(query.read(request(TrackingSource.PRESSURE))).observations.size)
		assertEquals(AllDaySourceStatusRead.Unavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW),
			query.read(request(TrackingSource.ACTIVITY).copy(limit = 1)))
	}

	@RoomTest fun `Pressure selection uses its measurement date and exclusive upper bound without cutting its window`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(updatedAtMs = 1L))
		database.pressureSampleDao().insert(pressure("pressure", 2_000L))
		val before = AllDaySourceStatusRequest.Sensor(TrackingSource.PRESSURE, HistoryObservationWindow(0L, 2_000L))
		assertEquals(emptyList(), assertIs<AllDaySourceStatusRead.Sensor>(query.read(before)).observations)
		val at = before.copy(window = HistoryObservationWindow(2_000L, 2_001L))
		val selected = assertIs<AllDaySourceStatusRead.Sensor>(query.read(at)).observations.single()
		assertEquals(2_000L, selected.observedTimeMs)
		assertEquals(1_000_000_000L, selected.windowStartElapsedRealtimeNanos)
	}

	private fun request(source: TrackingSource) = AllDaySourceStatusRequest.Sensor(source, HistoryObservationWindow(0L, 3_000L))
	private fun activity(id: String, time: Long) = ActivitySnapshot(timeMs = time, activityType = 7,
		confidence = 90, isTransition = true, createdAt = time, sourceSignalId = AllDaySensorObservationIdentity.activity(id))
	private fun pressure(id: String, time: Long) = PressureSample(timeMs = time, elapsedRealtimeNanos = 2_000_000_000L,
		pressureHpa = 1_010f, altitudeM = 0f, bucketId = null, createdAt = time,
		sourceSignalId = AllDaySensorObservationIdentity.pressure(id), sampleCount = 2,
		windowStartElapsedRealtimeNanos = 1_000_000_000L, windowEndElapsedRealtimeNanos = 2_000_000_000L,
		observationStamp = ObservationStampColumns(capabilityFlags = "PRESSURE_QUALIFICATION:PARTIAL"))
}
