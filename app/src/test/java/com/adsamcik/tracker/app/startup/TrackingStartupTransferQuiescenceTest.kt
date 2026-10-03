package com.adsamcik.tracker.app.startup

import io.kotest.matchers.shouldBe
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/** Actual deletion barrier with a physically owned stream and delayed cancellation cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingStartupTransferQuiescenceTest {
    @Test
    fun `clear joins output close and noncancellable cleanup before destructive work`() = runTest {
        val barrier = TrackingStartupDeletionBarrier(1_000L)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val output = object : OutputStream() {
            override fun write(value: Int) { events += "write" }
            override fun close() { events += "close" }
        }
        val owner = launch {
            barrier.withTransfer(0L, { true }) {
                try {
                    output.use {
                        it.write(1)
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        finishCleanup.await()
                        events += "cleanup"
                    }
                }
            }
        }
        entered.await()
        barrier.beginCloseAdmission()
        val deletion = async {
            barrier.awaitQuiescence()
            events += "database-clear"
            events += "private-file-clear"
        }
        cleaning.await()
        runCurrent()
        deletion.isCompleted shouldBe false
        owner.isCompleted shouldBe false
        events shouldBe listOf("write", "close")
        finishCleanup.complete(Unit)
        deletion.await()
        owner.join()
        events shouldBe listOf("write", "close", "cleanup", "database-clear", "private-file-clear")
    }

    @Test
    fun `physical drain timeout stays closed and retries the same unfinished transfer`() = runTest {
        val barrier = TrackingStartupDeletionBarrier(50L)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var admissions = 0
        val owner = launch {
            barrier.withTransfer(0L, { true }) {
                admissions++
                try { entered.complete(Unit); awaitCancellation() }
                finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); finishCleanup.await() }
                }
            }
        }
        entered.await()
        val close = async { runCatching { barrier.closeAdmission() } }
        cleaning.await()
        advanceTimeBy(51L)
        runCurrent()
        close.await().isFailure shouldBe true
        barrier.isClosed shouldBe true
        barrier.currentGeneration shouldBe 0L
        runCatching { barrier.reopen() }.isFailure shouldBe true
        barrier.withTransfer(0L, { true }) { admissions++ } shouldBe null
        finishCleanup.complete(Unit)
        owner.join()
        barrier.closeAdmission()
        barrier.currentGeneration shouldBe 1L
        barrier.reopen()
        barrier.withTransfer(0L, { true }) { admissions++ } shouldBe null
        barrier.withTransfer(1L, { true }) { admissions++; "new" } shouldBe "new"
        admissions shouldBe 2
    }

    @Test
    fun `closing cancels only admitted child work and rejects a delayed registration`() = runTest {
        val barrier = TrackingStartupDeletionBarrier(1_000L)
        val entered = CompletableDeferred<Unit>()
        val unrelatedRelease = CompletableDeferred<Unit>()
        val unrelated = async { unrelatedRelease.await(); "provider-owner-alive" }
        val transfer = launch {
            barrier.withTransfer(0L, { true }) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        barrier.closeAdmission()
        transfer.join()
        unrelated.isActive shouldBe true
        var delayedReads = 0
        barrier.withTransfer(0L, { true }) { delayedReads++ } shouldBe null
        delayedReads shouldBe 0
        unrelatedRelease.complete(Unit)
        unrelated.await() shouldBe "provider-owner-alive"
    }

    @Test
    fun `physical transfer does not hold the startup control plane mutex`() = runTest {
        val barrier = TrackingStartupDeletionBarrier(1_000L)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transfer = async {
            barrier.withTransfer(0L, { true }) { entered.complete(Unit); release.await(); "file" }
        }
        entered.await()
        barrier.withStartupRecovery({ "closed" }) { "control-plane" } shouldBe "control-plane"
        release.complete(Unit)
        transfer.await() shouldBe "file"
    }
}
