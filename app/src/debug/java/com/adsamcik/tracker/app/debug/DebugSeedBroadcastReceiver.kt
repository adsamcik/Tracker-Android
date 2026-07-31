package com.adsamcik.tracker.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DebugSeedBroadcastReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DebugSeedEntryPoint {
        fun testDataSeeder(): TestDataSeeder
        fun dispatchersProvider(): DispatchersProvider
        @ApplicationScope fun appScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        val action = intent.action ?: return
        if (action !in DEBUG_ACTIONS) return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugSeedEntryPoint::class.java,
        )
        val pendingResult = goAsync()

        entryPoint.appScope().launch(entryPoint.dispatchersProvider().io) {
            try {
                when (action) {
                    ACTION_SEED_SESSIONS -> entryPoint.testDataSeeder().seedSessions(
                        count = intent.readIntExtra(EXTRA_COUNT, DEFAULT_COUNT),
                        distanceKm = intent.readDoubleExtra(EXTRA_DISTANCE_KM, DEFAULT_DISTANCE_KM),
                        profile = intent.getStringExtra(EXTRA_PROFILE) ?: DEFAULT_PROFILE,
                    )
                    ACTION_RESET_ONBOARDING -> entryPoint.testDataSeeder().resetOnboarding()
                    ACTION_RESET_ALL_DATA -> entryPoint.testDataSeeder().resetAllData()
                }
            } catch (_: Exception) {
                return@launch
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun Intent.readIntExtra(name: String, defaultValue: Int): Int {
        val value = extras?.getCompat(name)
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull() ?: defaultValue
            else -> getIntExtra(name, defaultValue)
        }
    }

    private fun Intent.readDoubleExtra(name: String, defaultValue: Double): Double {
        val value = extras?.getCompat(name)
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: defaultValue
            else -> getDoubleExtra(name, defaultValue)
        }
    }

    @Suppress("DEPRECATION")
    private fun Bundle.getCompat(name: String): Any? = get(name)

    companion object {
        const val ACTION_SEED_SESSIONS = "com.adsamcik.tracker.debug.SEED_SESSIONS"
        const val ACTION_RESET_ONBOARDING = "com.adsamcik.tracker.debug.RESET_ONBOARDING"
        const val ACTION_RESET_ALL_DATA = "com.adsamcik.tracker.debug.RESET_ALL_DATA"
        const val EXTRA_COUNT = "count"
        const val EXTRA_DISTANCE_KM = "distance_km"
        const val EXTRA_PROFILE = "profile"

        private const val DEFAULT_COUNT = 5
        private const val DEFAULT_DISTANCE_KM = 5.0
        private const val DEFAULT_PROFILE = "walk"
        private val DEBUG_ACTIONS = setOf(
            ACTION_SEED_SESSIONS,
            ACTION_RESET_ONBOARDING,
            ACTION_RESET_ALL_DATA,
        )
    }
}
