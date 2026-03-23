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
        every { context.applicationContext } returns applicationContext
        every { MapLibre.getInstance(any<Context>()) } returns mockk()
    }

    @AfterEach
    fun tearDown() {
        MapLibreInitializer.reset()
        Dispatchers.resetMain()
        mainDispatcher.close()
        unmockkStatic(MapLibre::class)
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
}
