package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.net.Uri
import com.adsamcik.tracker.impexp.importer.ImportAdmissionEntryPoint
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import dagger.hilt.android.EntryPointAccessors
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationPreviewTransferAdmissionTest {
    @Test
    fun `preview reconciles and rejects transfer before index source or collected database work`() = runTest {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val events = mutableListOf<String>()
        val gate = object : TrackingStartupGate {
            override val isReady = true
            override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
                events += "reconcile"
                return TrackingStartupResult.Ready(false, 0L)
            }
            override suspend fun <T> withReadyGenerationTransfer(expectedGeneration: Long, operation: suspend () -> T): T? {
                events += "transfer:$expectedGeneration"
                return null
            }
        }
        val graph = mockk<ImportAdmissionEntryPoint> { every { trackingStartupGate() } returns gate }
        mockkStatic(EntryPointAccessors::class)
        try {
            every { EntryPointAccessors.fromApplication(context, ImportAdmissionEntryPoint::class.java) } returns graph
            runCatching { LocationFileImportPreviewer.inspect(context, Uri.parse("content://tracker/location")) }
                .isFailure shouldBe true
            events shouldBe listOf("reconcile", "transfer:0")
            verify(exactly = 0) { graph.dispatchersProvider() }
            verify(exactly = 0) { graph.collectedDataLifecycleStore() }
            verify(exactly = 0) { context.cacheDir }
        } finally { unmockkStatic(EntryPointAccessors::class) }
    }
}
