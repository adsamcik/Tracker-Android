package com.adsamcik.tracker.shared.preferences.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectedDataLifecycleStoreTest {
	private lateinit var context: Context
	private lateinit var store: CollectedDataLifecycleStore

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		kotlinx.coroutines.runBlocking {
			resetCollectedDataLifecycleForTests(context)
		}
		store = DefaultCollectedDataLifecycleStore(context)
	}

	@Test
	fun `default lifecycle accepts all current epoch records`() = runTest {
		store.snapshot() shouldBe CollectedDataLifecycleSnapshot(
			epoch = 0L,
			retainedFromMs = null,
		)
		store.snapshot().accepts(capturedEpoch = 0L, acquiredAtMs = Long.MIN_VALUE) shouldBe true
	}

	@Test
	fun `retention lower boundary only moves forward`() = runTest {
		store.advanceRetainedFrom(200L) shouldBe CollectedDataLifecycleSnapshot(0L, 200L)
		store.advanceRetainedFrom(100L) shouldBe CollectedDataLifecycleSnapshot(0L, 200L)
		store.snapshot().accepts(capturedEpoch = 0L, acquiredAtMs = 199L) shouldBe false
		store.snapshot().accepts(capturedEpoch = 0L, acquiredAtMs = 200L) shouldBe true
	}

	@Test
	fun `full deletion advances epoch and does not weaken retention boundary`() = runTest {
		store.advanceRetainedFrom(500L)

		store.beginFullDeletion(300L) shouldBe CollectedDataLifecycleSnapshot(1L, 500L)
		store.beginFullDeletion(700L) shouldBe CollectedDataLifecycleSnapshot(2L, 700L)
		store.snapshot().accepts(capturedEpoch = 1L, acquiredAtMs = 999L) shouldBe false
		store.snapshot().accepts(capturedEpoch = 2L, acquiredAtMs = 699L) shouldBe false
	}

	@Test
	fun `operation bound full deletion advances its target epoch exactly once`() = runTest {
		store.beginFullDeletion(
			operationId = "operation-1",
			targetEpoch = 1L,
			deletedAtMs = 500L,
		) shouldBe CollectedDataLifecycleSnapshot(1L, 500L)

		store.beginFullDeletion(
			operationId = "operation-1",
			targetEpoch = 1L,
			deletedAtMs = 500L,
		) shouldBe CollectedDataLifecycleSnapshot(1L, 500L)

		shouldThrow<IllegalStateException> {
			store.beginFullDeletion(
				operationId = "operation-2",
				targetEpoch = 1L,
				deletedAtMs = 500L,
			)
		}
	}

	@Test
	fun `lifecycle transitions wait for the shared retention authority operation lease`() =
		runTest {
			val lease = RetentionAuthorityOperationLease()
			val leasedStore = DefaultCollectedDataLifecycleStore(context, lease)
			val leaseEntered = CompletableDeferred<Unit>()
			val releaseLease = CompletableDeferred<Unit>()
			val authorityOperation = async {
				lease.withOperation {
					leaseEntered.complete(Unit)
					releaseLease.await()
				}
			}
			leaseEntered.await()
			val transition = async { leasedStore.advanceRetainedFrom(200L) }
			runCurrent()

			transition.isCompleted.shouldBeFalse()
			releaseLease.complete(Unit)
			authorityOperation.await()
			transition.await() shouldBe CollectedDataLifecycleSnapshot(0L, 200L)
		}

	@Test
	fun `concurrent full deletion transitions do not lose epoch increments`() = runTest {
		(1..12).map { timestamp ->
			async(Dispatchers.IO) {
				store.beginFullDeletion(timestamp.toLong())
			}
		}.awaitAll()

		val snapshot = store.snapshot()
		snapshot.epoch shouldBe 12L
		snapshot.retainedFromMs shouldBe 12L
	}
}
