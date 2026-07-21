package com.adsamcik.tracker.shared.preferences.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
