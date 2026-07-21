package com.adsamcik.tracker.app.ui

import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard
import com.adsamcik.tracker.feature.statistics.api.navigation.Stats
import io.kotest.matchers.shouldBe
import org.junit.Test

class MainRootTripDetailOriginTest {

    @Test
    fun `TripDetail entered from Dashboard maps back to Dashboard`() {
        tripDetailRouteForFallback(Dashboard) shouldBe Dashboard
    }

    @Test
    fun `TripDetail entered from Statistics maps back to Statistics`() {
        tripDetailRouteForFallback(Stats) shouldBe Stats
    }
}
