package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.di.LocalDailyPointsProvider
import com.adsamcik.tracker.shared.base.di.LocalDailySummaryProvider
import com.adsamcik.tracker.shared.base.di.LocalLockManager
import com.adsamcik.tracker.shared.base.di.LocalTrackerController
import com.adsamcik.tracker.testing.data.TestDataFactory
import com.adsamcik.tracker.testing.fake.FakeLockManager
import com.adsamcik.tracker.testing.fake.FakeTrackerServiceController
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fakeController = FakeTrackerServiceController()
    private val fakeLockManager = FakeLockManager()
    private val fakeDailyPointsProvider = FakeDailyPointsProvider()
    private val fakeDailySummaryProvider = FakeDailySummaryProvider()

    @Test
    fun whenTrackingAndSpeedAvailable_showsSpeed() {
        // Arrange
        fakeController.updateServiceRunning(true)
        fakeController.updateSessionInfo(TrackerSessionInfo(isInitiatedByUser = true))
        
        val locationWithSpeed = TestDataFactory.createLocation(speed = 5.0f) // 5 m/s
        val collectionData = MutableCollectionData(System.currentTimeMillis()).apply {
            location = locationWithSpeed
        }
        fakeController.updateCollectionData(collectionData)

        // Act
        composeRule.setContent {
            CompositionLocalProvider(
                LocalTrackerController provides fakeController,
                LocalLockManager provides fakeLockManager,
                LocalDailyPointsProvider provides fakeDailyPointsProvider,
                LocalDailySummaryProvider provides fakeDailySummaryProvider
            ) {
                TrackerRoute()
            }
        }

        // Assert
        // "Speed" title should be visible when speed > 0
        composeRule.onNodeWithText("SPEED").assertIsDisplayed()
    }
}
