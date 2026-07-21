package com.adsamcik.tracker.dashboard.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.dashboard.data.DashboardLayout
import com.adsamcik.tracker.dashboard.data.DashboardLayoutRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardViewModelPermissionTest {

    @Test
    fun `granted system result clears permission request state`() {
        val viewModel = createViewModel()
        viewModel.requestPermission()

        viewModel.onPermissionResult(granted = true)

        viewModel.hasLocationPermission.value shouldBe true
        viewModel.permissionDenied.value shouldBe false
        viewModel.showLocationPermissionRequest.value shouldBe false
    }

    @Test
    fun `denied system result clears permission request state`() {
        val viewModel = createViewModel()
        viewModel.requestPermission()

        viewModel.onPermissionResult(granted = false)

        viewModel.hasLocationPermission.value shouldBe false
        viewModel.permissionDenied.value shouldBe true
        viewModel.showLocationPermissionRequest.value shouldBe false
    }

    private fun createViewModel(): DashboardViewModel {
        val layoutRepository = mockk<DashboardLayoutRepository>()
        every { layoutRepository.layout } returns flowOf(DashboardLayout())

        val trackerStateReader = mockk<TrackerStateReader>()
        every { trackerStateReader.isServiceRunningFlow } returns MutableStateFlow(false)

        val lockManager = mockk<LockManager>()
        every { lockManager.isLockedFlow } returns MutableStateFlow(false)

        val trackingParamsRepository = mockk<TrackingParamsRepository>()
        every { trackingParamsRepository.data } returns flowOf(TrackingParamsState())

        return DashboardViewModel(
            appContext = ApplicationProvider.getApplicationContext<Context>(),
            dispatchers = mockk(relaxed = true),
            appDatabaseProvider = mockk(),
            layoutRepository = layoutRepository,
            sessionInsightsGenerator = mockk(relaxed = true),
            widgetRegistry = mockk(relaxed = true),
            trackerStateReader = trackerStateReader,
            lockManager = lockManager,
            dailySummaryProvider = mockk(),
            dailyPointsProviderFactory = mockk(),
            goalProgressProviderFactory = mockk(),
            trackingParamsRepository = trackingParamsRepository,
        )
    }
}
