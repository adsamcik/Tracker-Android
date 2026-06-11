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

        // All tests in this group exercise the post-initialize path. They
        // assume Application.onCreate's call already raced past initialize()
        // (covered separately by PreInitializeStash) -- i.e. MapLibre is up
        // and HttpRequestUtil.setOkHttpClient is safe to call eagerly.
        @BeforeEach
        fun bootstrapMapLibre() {
            kotlinx.coroutines.runBlocking { MapLibreInitializer.initialize(context) }
        }

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
            // After reset we're back in pre-init state. Re-bootstrap so this
            // test exercises the post-init path consistently with its siblings.
            kotlinx.coroutines.runBlocking { MapLibreInitializer.initialize(context) }
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

    @Nested
    @DisplayName("setHttpCallFactory before initialize (pending-stash, R7 emulator finding)")
    inner class PreInitializeStash {

        // Background:
        // Application.onCreate calls setHttpCallFactory with the gateway's
        // OkHttp factory at process start. No feature has triggered
        // MapLibreInitializer.initialize() yet, so MapLibre.getInstance()
        // has NEVER been called. Eagerly calling HttpRequestUtil.setOkHttpClient
        // touches HttpRequestImpl.<clinit> -> MapLibre.validateMapLibre()
        // -> MapLibreConfigurationException. Before the stash fix the resulting
        // ExceptionInInitializerError was swallowed and the factory was
        // *never* registered -- MapLibre then silently fell back to its
        // default OkHttp client, completely bypassing the
        // NetworkPolicyAggregator kill switch / allowlist / rate limit, and
        // also bypassing the opt-in 'online map tiles' consent gate.
        // The fix stashes the factory and applies it from initialize().

        @Test
        fun `setHttpCallFactory before initialize does NOT call setOkHttpClient yet`() {
            val factory: okhttp3.Call.Factory = mockk()

            MapLibreInitializer.setHttpCallFactory(factory)

            // No eager call: would have thrown MapLibreConfigurationException
            // in production. Stash silently instead.
            verify(exactly = 0) { HttpRequestUtil.setOkHttpClient(any()) }
        }

        @Test
        fun `initialize applies a pre-stashed factory after MapLibre getInstance succeeds`() = runTest {
            val factory: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(factory)
            verify(exactly = 0) { HttpRequestUtil.setOkHttpClient(any()) }

            MapLibreInitializer.initialize(context)

            verify(exactly = 1) { MapLibre.getInstance(applicationContext) }
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `pre-stashed factory is applied exactly once even if initialize is called twice`() = runTest {
            val factory: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(factory)

            MapLibreInitializer.initialize(context)
            MapLibreInitializer.initialize(context)

            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `if initialize fails, factory stays stashed for a future successful initialize`() = runTest {
            val factory: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(factory)

            // First initialize fails (no native lib).
            every { MapLibre.getInstance(any<Context>()) } throws UnsatisfiedLinkError("no native")
            MapLibreInitializer.initialize(context) shouldBe false
            verify(exactly = 0) { HttpRequestUtil.setOkHttpClient(any()) }

            // Recover: native lib loaded, retry.
            every { MapLibre.getInstance(any<Context>()) } returns mockk()
            MapLibreInitializer.initialize(context) shouldBe true

            // Factory finally registered.
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `setHttpCallFactory after initialize still applies immediately`() = runTest {
            // The post-init eager path should not regress: most production
            // callers (other than Application.onCreate) will run after
            // initialize().
            MapLibreInitializer.initialize(context)
            val factory: okhttp3.Call.Factory = mockk()

            MapLibreInitializer.setHttpCallFactory(factory)

            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `pre-stashed null is a no-op and does not block later real factories`() = runTest {
            MapLibreInitializer.setHttpCallFactory(null)
            val factory: okhttp3.Call.Factory = mockk()
            MapLibreInitializer.setHttpCallFactory(factory)

            MapLibreInitializer.initialize(context)

            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(factory) }
        }

        @Test
        fun `last pre-stashed factory wins when called multiple times before initialize`() = runTest {
            val first: okhttp3.Call.Factory = mockk()
            val second: okhttp3.Call.Factory = mockk()

            MapLibreInitializer.setHttpCallFactory(first)
            MapLibreInitializer.setHttpCallFactory(second)
            MapLibreInitializer.initialize(context)

            // Only the latest one is applied -- first was superseded before
            // MapLibre was even bootstrapped.
            verify(exactly = 0) { HttpRequestUtil.setOkHttpClient(first) }
            verify(exactly = 1) { HttpRequestUtil.setOkHttpClient(second) }
        }
    }
}
