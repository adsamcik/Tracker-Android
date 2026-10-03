package com.adsamcik.tracker.map.export

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.maplibre.android.snapshotter.MapSnapshotter

class MapSnapshotRendererTest {

	@Test
	fun `snapshot symbol text respects system font scaling`() {
		MapSnapshotRenderer.snapshotTextSizePx(textSizeSp = 12f, fontScale = 1.5f) shouldBe 18f
	}

	@Test
	@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
	fun `IO cancellation waits for native cancellation on the creator thread`() = runBlocking {
		newSingleThreadContext("snapshot-main").use { main ->
			newSingleThreadContext("clear-io").use { io ->
				val started = CompletableDeferred<Unit>()
				val cancelling = CompletableDeferred<Unit>()
				val finishNativeCancellation = CountDownLatch(1)
				var creatorThread: Thread? = null
				var cancellingThread: Thread? = null
				var nativeCancellationThread: Thread? = null
				val snapshotter = mockk<MapSnapshotter> {
					every { start(any(), any()) } answers {
						creatorThread = Thread.currentThread()
						started.complete(Unit)
						Unit
					}
					every { cancel() } answers {
						nativeCancellationThread = Thread.currentThread()
						cancelling.complete(Unit)
						check(finishNativeCancellation.await(5, TimeUnit.SECONDS))
						Unit
					}
				}
				val render = async(main) { MapSnapshotRenderer.awaitSnapshot(snapshotter, main) }
				try {
					withTimeout(5_000) { started.await() }
					withContext(io) {
						cancellingThread = Thread.currentThread()
						render.cancel()
					}
					withTimeout(5_000) { cancelling.await() }
					nativeCancellationThread shouldBe creatorThread
					(nativeCancellationThread === cancellingThread) shouldBe false
					render.isCompleted shouldBe false
				} finally {
					finishNativeCancellation.countDown()
					render.cancel()
					withTimeout(5_000) { render.join() }
				}
				verify(exactly = 1) { snapshotter.cancel() }
			}
		}
	}

	@Test
	@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
	fun `native start failure still finishes creator-thread cleanup`() = runBlocking {
		newSingleThreadContext("snapshot-main").use { main ->
			val failure = IllegalStateException("Native start failed")
			var creatorThread: Thread? = null
			var cleanupThread: Thread? = null
			val snapshotter = mockk<MapSnapshotter> {
				every { start(any(), any()) } answers {
					creatorThread = Thread.currentThread()
					throw failure
				}
				every { cancel() } answers { cleanupThread = Thread.currentThread(); Unit }
			}
			runCatching {
				withContext(main) { MapSnapshotRenderer.awaitSnapshot(snapshotter, main) }
			}.exceptionOrNull() shouldBe failure
			cleanupThread shouldBe creatorThread
			verify(exactly = 1) { snapshotter.cancel() }
		}
	}
}
