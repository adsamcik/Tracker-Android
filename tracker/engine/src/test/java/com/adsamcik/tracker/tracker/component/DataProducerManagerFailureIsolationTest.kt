package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that [DataProducerManager.getData] isolates individual producer
 * failures so that one crashing producer does not prevent others from
 * contributing data to the collection cycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataProducerManagerFailureIsolationTest {

	private lateinit var context: Context

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
	}

	@Test
	fun `failing producer does not prevent other producers from running`() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		val dispatchers = testDispatchers(testDispatcher)
		val manager = DataProducerManager(context, dispatchers)

		val throwingProducer = ThrowingProducer(RuntimeException("sensor failure"))
		val recordingProducer = RecordingProducer()

		activateProducer(manager, throwingProducer)
		activateProducer(manager, recordingProducer)

		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = System.nanoTime(),
		)
		manager.getData(cycle)

		assertTrue(recordingProducer.wasInvoked, "Recording producer should have been called despite sibling failure")
	}

	@Test
	fun `CancellationException still propagates`() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		val dispatchers = testDispatchers(testDispatcher)
		val manager = DataProducerManager(context, dispatchers)

		val cancellingProducer = ThrowingProducer(CancellationException("cancelled"))
		activateProducer(manager, cancellingProducer)

		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = System.nanoTime(),
		)
		assertFailsWith<CancellationException> {
			manager.getData(cycle)
		}
	}

	@Test
	fun `all producers succeed when none throw`() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		val dispatchers = testDispatchers(testDispatcher)
		val manager = DataProducerManager(context, dispatchers)

		val producer1 = RecordingProducer()
		val producer2 = RecordingProducer()

		activateProducer(manager, producer1)
		activateProducer(manager, producer2)

		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = System.nanoTime(),
		)
		manager.getData(cycle)

		assertTrue(producer1.wasInvoked)
		assertTrue(producer2.wasInvoked)
	}

	// --- Helpers ---

	private fun activateProducer(manager: DataProducerManager, component: TrackerDataProducerComponent) {
		val field = DataProducerManager::class.java.getDeclaredField("activeProducerList")
		field.isAccessible = true
		@Suppress("UNCHECKED_CAST")
		val list = field.get(manager) as java.util.concurrent.CopyOnWriteArrayList<TrackerDataProducerComponent>
		list.add(component)
	}

	private fun testDispatchers(dispatcher: CoroutineDispatcher) = object : DispatchersProvider {
		override val main: CoroutineDispatcher = dispatcher
		override val default: CoroutineDispatcher = dispatcher
		override val io: CoroutineDispatcher = dispatcher
		override val unconfined: CoroutineDispatcher = dispatcher
	}

	private class ThrowingProducer(private val exception: Exception) : TrackerDataProducerComponent(
		object : TrackerDataProducerObserver {
			override fun onStateChange(shouldBeEnabled: Boolean, component: TrackerDataProducerComponent) {}
		}
	) {
		override val preferenceKey: String = "throwing-producer"
		override val preferenceDefault: Boolean = false
		override fun onDataRequest(builder: TrackingCycleBuilder) {
			throw exception
		}
	}

	private class RecordingProducer : TrackerDataProducerComponent(
		object : TrackerDataProducerObserver {
			override fun onStateChange(shouldBeEnabled: Boolean, component: TrackerDataProducerComponent) {}
		}
	) {
		var wasInvoked = false
			private set

		override val preferenceKey: String = "recording-producer"
		override val preferenceDefault: Boolean = false
		override fun onDataRequest(builder: TrackingCycleBuilder) {
			wasInvoked = true
		}
	}
}
