package com.adsamcik.tracker.map

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.maplibre.android.MapLibre

@DisplayName("MapLibreInitializer")
class MapLibreInitializerTest {

    private val context: Context = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        MapLibreInitializer.reset()
        mockkStatic(MapLibre::class)
        every { MapLibre.getInstance(any<Context>()) } returns mockk()
    }

    @AfterEach
    fun tearDown() {
        MapLibreInitializer.reset()
        unmockkStatic(MapLibre::class)
    }

    @Nested
    @DisplayName("initialize")
    inner class Initialize {

        @Test
        fun `transitions isReady from false to true`() {
            MapLibreInitializer.isReady.value shouldBe false

            MapLibreInitializer.initialize(context)

            MapLibreInitializer.isReady.value shouldBe true
        }

        @Test
        fun `calls MapLibre getInstance with application context`() {
            MapLibreInitializer.initialize(context)

            verify(exactly = 1) { MapLibre.getInstance(context.applicationContext) }
        }

        @Test
        fun `is idempotent - second call does not re-invoke native init`() {
            MapLibreInitializer.initialize(context)
            MapLibreInitializer.initialize(context)

            verify(exactly = 1) { MapLibre.getInstance(any<Context>()) }
            MapLibreInitializer.isReady.value shouldBe true
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {

        @Test
        fun `catches UnsatisfiedLinkError and keeps map not ready for retry`() {
            every { MapLibre.getInstance(any<Context>()) } throws UnsatisfiedLinkError("no maplibre in test")

            MapLibreInitializer.initialize(context) shouldBe false

            MapLibreInitializer.isReady.value shouldBe false
        }

        @Test
        fun `catches RuntimeException and keeps map not ready for retry`() {
            every { MapLibre.getInstance(any<Context>()) } throws RuntimeException("init failed")

            MapLibreInitializer.initialize(context) shouldBe false

            MapLibreInitializer.isReady.value shouldBe false
        }

        @Test
        fun `after error, second call retries initialization`() {
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
        fun `reset allows re-initialization`() {
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
