package com.adsamcik.tracker.app.widget.glance

import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for accessing DI-managed providers from Glance widgets.
 * Widgets cannot use @HiltViewModel; EntryPointAccessors.fromApplication() is used instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrackerWidgetEntryPoint {
    fun trackerServiceController(): TrackerServiceController
    fun dailySummaryProvider(): DailySummaryProvider
    fun goalProgressProvider(): GoalProgressProvider
}
