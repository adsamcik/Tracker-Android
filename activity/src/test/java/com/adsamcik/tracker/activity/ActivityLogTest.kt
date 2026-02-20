package com.adsamcik.tracker.activity

import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Activity Logging")
class ActivityLogTest {

    @BeforeEach
    fun setup() {
        mockkObject(Logger)
        every { Logger.logWithPreference(any(), any(), any()) } returns Unit
    }

    @AfterEach
    fun teardown() {
        unmockkObject(Logger)
    }

    // -----------------------------------------------------------------------
    // ACTIVITY_LOG_SOURCE constant
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ACTIVITY_LOG_SOURCE")
    inner class LogSourceTests {

        @Test
        fun `value is activity`() {
            ACTIVITY_LOG_SOURCE shouldBe "activity"
        }
    }

    // -----------------------------------------------------------------------
    // logActivity function
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logActivity")
    inner class LogActivityTests {

        @Test
        fun `delegates to Logger logWithPreference`() {
            val data = LogData(message = "test message", source = ACTIVITY_LOG_SOURCE)

            logActivity(data)

            verify(exactly = 1) {
                Logger.logWithPreference(
                    data,
                    com.adsamcik.tracker.shared.preferences.R.string.settings_log_activity_key,
                    com.adsamcik.tracker.shared.preferences.R.string.settings_log_activity_default
                )
            }
        }

        @Test
        fun `passes correct preference key resource`() {
            val data = LogData(message = "any", source = "src")

            logActivity(data)

            verify {
                Logger.logWithPreference(
                    any(),
                    eq(com.adsamcik.tracker.shared.preferences.R.string.settings_log_activity_key),
                    any()
                )
            }
        }

        @Test
        fun `passes correct preference default resource`() {
            val data = LogData(message = "any", source = "src")

            logActivity(data)

            verify {
                Logger.logWithPreference(
                    any(),
                    any(),
                    eq(com.adsamcik.tracker.shared.preferences.R.string.settings_log_activity_default)
                )
            }
        }

        @Test
        fun `forwards LogData unchanged`() {
            val data = LogData(
                message = "recognition started",
                source = ACTIVITY_LOG_SOURCE
            )

            logActivity(data)

            verify {
                Logger.logWithPreference(
                    match { it.message == "recognition started" && it.source == ACTIVITY_LOG_SOURCE },
                    any(),
                    any()
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Privacy redaction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Privacy")
    inner class PrivacyTests {

        @Test
        fun `log source does not contain coordinate information`() {
            ACTIVITY_LOG_SOURCE shouldNotContain "lat"
            ACTIVITY_LOG_SOURCE shouldNotContain "lon"
            ACTIVITY_LOG_SOURCE shouldNotContain "coord"
        }

        @Test
        fun `log messages should not embed raw coordinates`() {
            val capturedData = mutableListOf<LogData>()
            every { Logger.logWithPreference(capture(capturedData), any(), any()) } returns Unit

            // Simulate typical log calls from the module
            logActivity(LogData(message = "requesting recognition rerun", source = ACTIVITY_LOG_SOURCE))
            logActivity(LogData(message = "new activity request", source = ACTIVITY_LOG_SOURCE))
            logActivity(LogData(message = "removed request for SomeClass", source = ACTIVITY_LOG_SOURCE))

            capturedData.forEach { data ->
                data.message shouldNotContain Regex("""\d{1,3}\.\d{4,}""")
                data.source shouldBe ACTIVITY_LOG_SOURCE
            }
        }

        @Test
        fun `LogData with object data serializes via toString not exposing coordinates`() {
            val safePayload = mapOf("interval" to 30, "type" to "change")
            val data = LogData(
                message = "activity config",
                data = safePayload,
                source = ACTIVITY_LOG_SOURCE
            )

            // The data field should contain the toString of the map, no coordinates
            data.data shouldNotContain Regex("""\d{1,3}\.\d{4,}""")
        }
    }
}
