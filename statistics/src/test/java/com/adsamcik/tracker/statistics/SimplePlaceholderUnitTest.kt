package com.adsamcik.tracker.statistics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Placeholder pure JVM unit test so :statistics:testDebugUnitTest has at least one test to execute.
 * Real logic covered by instrumentation tests in androidTest. This guards against Gradle failing with
 * "No tests found" when UI tests were migrated out of the unit test source set.
 */
@DisplayName("Statistics Placeholder")
class SimplePlaceholderUnitTest {

    @Test
    fun `basic arithmetic works`() {
        (2 + 2) shouldBe 4
    }
}
