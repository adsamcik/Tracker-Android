package com.adsamcik.tracker.map

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
@DisplayName("MapLibreInitializer")
class MapLibreInitializerTest {

    private val context: Context = mockk(relaxed = true)
    private val applicationContext: Context = mockk(relaxed = true)
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher

    @BeforeEach
    fun setup() {
        mainDispatcher = newSingleThreadContext("maplibre-main")
        Dispatchers.setMain(mainDispatcher)
        MapLibreInitializer.reset()
        mockkStatic(MapLibre::class)
        mockkStatic(HttpRequestUtil::class)
        every { context.applicationContext } returns applicationContext
        every { MapLibre.getInstance(any<Context>()) } returns mockk()
        every { HttpRequestUtil.setOkHttpClient(any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        MapLibreInitializer.reset()
        Dispatchers.resetMain()
        mainDispatcher.close()
        unmockkStatic(MapLibre::class)
        unmockkStatic(HttpRequestUtil::class)
    }

    @Nested
    @DisplayName("initialize")
    inner class Initialize {

        @Test
        fun `transitions isReady from false to true`() = runTest {
            MapLibreInitializer.isReady.value shouldBe false

            MapLibreInitializer.initialize(context)

            MapLibreInitializer.isReady.value shouldBe true
        }

        @Test
        fun `calls MapLibre getInstance with application context`() = runTest {
            MapLibreInitializer.initialize(context)

            verify(exactly = 1) { MapLibre.getInstance(applicationContext) }
        }

        @Test
        fun `is idempotent - second call does not re-invoke native init`() = runTest {
            MapLibreInitializer.initialize(context)
            MapLibreInitializer.initialize(context)

            verify(exactly = 1) { MapLibre.getInstance(any<Context>()) }
            MapLibreInitializer.isReady.value shouldBe true
        }

        @Test
        fun `marshals initialization back to main thread`() = runTest {
            var threadName: String? = null
            every { MapLibre.getInstance(any<Context>()) } answers {
                threadName = Thread.currentThread().name
                mockk()
            }

            withContext(Dispatchers.Default) {
                MapLibreInitializer.initialize(context)
            }

            threadName shouldBe "maplibre-main"
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {

        @Test
        fun `catches UnsatisfiedLinkError and keeps map not ready for retry`() = runTest {
            every { MapLibre.getInstance(any<Context>()) } throws UnsatisfiedLinkError("no maplibre in test")

            MapLibreInitializer.initialize(context) shouldBe false

            MapLibreInitializer.isReady.value shouldBe false
        }

        @Test
        fun `catches RuntimeException and keeps map not ready for retry`() = runTest {
            every { MapLibre.getInstance(any<Context>()) } throws RuntimeException("init failed")

            MapLibreInitializer.initialize(context) shouldBe false

            MapLibreInitializer.isReady.value shouldBe false
        }

        @Test
        fun `after error, second call retries initialization`() = runTest {
            every { MapLibre.getInstance(any<Context>()) } throws RuntimeException("boom")

            MapLibreInitializer.initialize(context)
            MapLibreInitializer.initialize(context)

            verify(exactly = 2) { MapLibre.getInstance(any<Context>()) }
            MapLibreInitializer.isReady.value shouldBe false
        }
    }

    @Nested
    @DisplayName("reset")
    inner class Reset {

        @Test
        fun `reset allows re-initialization`() = runTest {
            MapLibreInitializer.initialize(context)
            MapLibreInitializer.isReady.value shouldBe true

            MapLibreInitializer.reset()
            MapLibreInitializer.isReady.value shouldBe false

            MapLibreInitializer.initialize(context)
            MapLibreInitializer.isReady.value shouldBe true

            verify(exactly = 2) { MapLibre.getInstance(any<Context>()) }
        }
    }

    @Nested
    @DisplayName("setHttpCallFactory")
    inner class SetHttpCallFactory {

        @Test
        fun `forwards the factory to MapLibre's HttpRequestUtil`() {
            val factory: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(factory)
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `null factory is a silent no-op (does not unregister)`() {
            MapLibreInitializer.setHttpCallFactory(null)
            verify(exactly = 0) { HttpRequestUtil.setOkHttpClient(any()) }
        }

        @Test
        fun `same factory passed twice only registers once`() {
            val factory: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(factory)
            MapLibreInitializer.setHttpCallFactory(factory)
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `different factories each register`() {
            val first: okhttp3.Call.Factory = mockk()
            val second: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(first)
            MapLibreInitializer.setHttpCallFactory(second)
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(first) }
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(second) }
        }

        @Test
        fun `survives UnsatisfiedLinkError in unit test environment`() {
            every { HttpRequestUtil.setOkHttpClient(any()) } throws UnsatisfiedLinkError("no native lib")
            val factory: okhttp3.Call.Factory = mockk()
            // Should not throw.
            MapLibreInitializer.setHttpCallFactory(factory)
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `survives NoClassDefFoundError (HttpRequestImpl link failure in Robolectric)`() {
            // Robolectric unit tests raise NoClassDefFoundError when MapLibre's
            // native HTTP impl class fails to link. Our catch must cover
            // LinkageError (the common parent) so Application.onCreate doesn't
            // explode in 183 :app tests.
            every { HttpRequestUtil.setOkHttpClient(any()) } throws
                NoClassDefFoundError("Could not initialize class HttpRequestImpl")
            val factory: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(factory)
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `reset clears the registered factory so a new one will register again`() {
            val factory: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(factory)
            MapLibreInitializer.reset()
            MapLibreInitializer.setHttpCallFactory(factory)
            verify(exactly = 2) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `after swallowed LinkageError, second call with same factory retries (R3 round 7)`() {
            // Before the record-order fix, registeredCallFactory was set BEFORE
            // HttpRequestUtil.setOkHttpClient succeeded. If the native call
            // threw (Robolectric / missing native lib), the field was already
            // pointing at the factory -- so the identity short-circuit at the
            // top of setHttpCallFactory silently swallowed every retry, even
            // though MapLibre's process-global call factory was never actually
            // replaced. The fix moves the field assignment to AFTER the
            // setOkHttpClient call so a failed attempt does NOT poison the
            // retry path.
            every { HttpRequestUtil.setOkHttpClient(any()) }
                .throws(LinkageError("first attempt blows up"))
                .andThen(Unit)
            val factory: okhttp3.Call.Factory = mockk()

            MapLibreInitializer.setHttpCallFactory(factory)
            MapLibreInitializer.setHttpCallFactory(factory)

            // Both attempts must reach the static setter; the second is what
            // actually completes the registration.
            verify(exactly = 2) { HttpRequestUtil.setOkHttpClient(factory) }
        }
    }
}
