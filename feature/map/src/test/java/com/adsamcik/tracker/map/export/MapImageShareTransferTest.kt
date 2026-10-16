package com.adsamcik.tracker.map.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class MapImageShareTransferTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `old request cannot render or recreate private files after clear`() = runTest(dispatcher) {
        val gate = TestGate().apply { generation = 1L }
        var rendered = false
        MapImageShareHelper(TestDispatchersProvider(dispatcher), gate)
            .renderAndShare(mockk(relaxed = true), 0L) { rendered = true; error("Old render started") } shouldBe false
        rendered shouldBe false
        gate.admitted shouldBe false
    }

    @Test
    fun `render is inside the transfer and cancellation leaves no output`() = runTest(dispatcher) {
        val gate = TestGate()
        val entered = CompletableDeferred<Unit>()
        val request = async {
            MapImageShareHelper(TestDispatchersProvider(dispatcher), gate).renderAndShare(mockk(relaxed = true), 0L) {
                gate.admitted shouldBe true
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        request.cancelAndJoin()
        gate.completed shouldBe true
    }

    @Test
    fun `PNG stream closes and file is removed before a stale handoff returns`() = runTest(dispatcher) {
        val root = kotlin.io.path.createTempDirectory("map-share-transfer-").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns root
            every { packageName } returns "com.adsamcik.tracker.test"
        }
        val gate = TestGate()
        var output: OutputStream? = null
        val bitmap = mockk<Bitmap> {
            every { compress(Bitmap.CompressFormat.PNG, 100, any()) } answers {
                gate.admitted shouldBe true
                output = thirdArg()
                output!!.write(7)
                gate.generation++ // Clear closes before the final short external handoff.
                true
            }
        }
        mockkStatic(FileProvider::class)
        try {
            every { FileProvider.getUriForFile(context, any(), any<File>()) } returns Uri.parse("content://tracker/image")
            MapImageShareHelper(TestDispatchersProvider(dispatcher), gate)
                .renderAndShare(context, 0L) { bitmap } shouldBe false
            root.resolve("sharable").listFiles()?.size shouldBe 0
            runCatching { output!!.write(1) }.isFailure shouldBe true
            verify(exactly = 0) { context.startActivity(any()) }
            gate.completed shouldBe true
        } finally {
            unmockkStatic(FileProvider::class)
            root.resolve("sharable").delete()
            root.delete()
        }
    }

    @Test
    fun `encoder failure removes partial PNG inside the admitted operation`() = runTest(dispatcher) {
        val root = kotlin.io.path.createTempDirectory("map-share-encode-").toFile()
        val context = mockk<Context>(relaxed = true) { every { filesDir } returns root }
        val gate = TestGate()
        val bitmap = mockk<Bitmap> {
            every { compress(Bitmap.CompressFormat.PNG, 100, any()) } answers {
                thirdArg<OutputStream>().write(7)
                false
            }
        }
        try {
            runCatching {
                MapImageShareHelper(TestDispatchersProvider(dispatcher), gate).renderAndShare(context, 0L) { bitmap }
            }.isFailure shouldBe true
            root.resolve("sharable").listFiles()?.size shouldBe 0
            gate.completed shouldBe true
        } finally { root.resolve("sharable").delete(); root.delete() }
    }

    private class TestGate : TrackingStartupGate {
        var generation = 0L
        var admitted = false
        var completed = false
        override val currentGeneration get() = generation
        override val isReady = true
        override val permitsNewCollection = false
        override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
        override suspend fun <T> withReadyGenerationTransfer(expectedGeneration: Long, operation: suspend () -> T): T? {
            if (!isReadyGeneration(expectedGeneration)) {
                return null
            }
            admitted = true
            return try { operation() } finally { completed = true; admitted = false }
        }
    }
}
