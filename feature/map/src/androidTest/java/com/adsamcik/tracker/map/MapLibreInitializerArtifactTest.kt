package com.adsamcik.tracker.map

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.module.http.HttpRequestUtil

/** Verifies the factory setter against the bundled MapLibre Android artifact, without network I/O. */
@RunWith(AndroidJUnit4::class)
class MapLibreInitializerArtifactTest {

    @Test
    fun bundled_artifact_holds_the_exact_gateway_factory_after_pending_installation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Initialize once so HttpRequestImpl's real static class can be inspected safely, then
        // restore the initializer's pre-SDK state to exercise its pending-installation path.
        assertTrue(runBlocking { MapLibreInitializer.initialize(context) })
        val clientField = Class.forName(HTTP_REQUEST_IMPL_CLASS)
            .getDeclaredField("client")
            .apply { isAccessible = true }
        val originalFactory = clientField.get(null) as Call.Factory
        val sentinelFactory = Call.Factory {
            error("Factory installation must not create an HTTP call")
        }

        try {
            MapLibreInitializer.reset()
            assertEquals(
                MapLibreHttpFactoryInstallResult.PendingSdkInitialization,
                MapLibreInitializer.setHttpCallFactory(sentinelFactory),
            )

            assertTrue(runBlocking { MapLibreInitializer.initialize(context) })

            assertSame(sentinelFactory, clientField.get(null))
            assertTrue(MapLibreInitializer.isOnlineReady.value)
        } finally {
            HttpRequestUtil.setOkHttpClient(originalFactory)
            MapLibreInitializer.reset()
        }
    }

    private companion object {
        const val HTTP_REQUEST_IMPL_CLASS =
            "org.maplibre.android.module.http.HttpRequestImpl"
    }
}
