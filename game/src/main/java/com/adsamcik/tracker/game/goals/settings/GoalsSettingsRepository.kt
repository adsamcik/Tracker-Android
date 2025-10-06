package com.adsamcik.tracker.game.goals.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** Immutable snapshot of goals-related preferences. */
data class GoalsSettingsState(
    val notificationsEnabled: Boolean,
    val dailyStepGoal: Int,
    val weeklyStepGoal: Int,
    val weeklyProgressDailyLimit: Float
)

/** Repository boundary exposing goals settings as reactive state plus mutation APIs. */
interface GoalsSettingsRepository {
    /** Continuous stream of goals settings. */
    val data: Flow<GoalsSettingsState>

    /** Synchronous read of current settings (used for initial state). */
    fun current(): GoalsSettingsState

    /** Persist notification toggle. */
    suspend fun setNotificationsEnabled(enabled: Boolean)

    /** Persist daily step goal (expects positive values). */
    suspend fun setDailyStepGoal(steps: Int)

    /** Persist weekly step goal (expects positive values). */
    suspend fun setWeeklyStepGoal(steps: Int)

    /** Persist maximum daily portion of the weekly goal (fraction 0-1). */
    suspend fun setWeeklyDailyLimit(fraction: Float)
}

/** Default SharedPreferences-backed implementation (legacy bridge while DataStore migration completes). */
class DefaultGoalsSettingsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher
) : GoalsSettingsRepository {

    private val prefs: Preferences
        get() = Preferences.getPref(context)

    private val sharedPreferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    private val observedKeys by lazy {
        setOf(
            context.getString(R.string.settings_game_goals_notification_enabled_key),
            context.getString(R.string.settings_game_goals_day_steps_key),
            context.getString(R.string.settings_game_goals_week_steps_key),
            context.getString(R.string.settings_game_goals_week_steps_daily_percentage_key)
        )
    }

    private val notificationDefault by lazy {
        context.getString(R.string.settings_game_goals_notification_enabled_default).toBoolean()
    }

    override val data: Flow<GoalsSettingsState> = callbackFlow {
        // Emit initial state immediately.
        trySend(current())

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key in observedKeys) {
                trySend(current())
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)

        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    override fun current(): GoalsSettingsState = readState()

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        withContext(io) {
            prefs.edit {
                setBoolean(R.string.settings_game_goals_notification_enabled_key, enabled)
            }
        }
    }

    override suspend fun setDailyStepGoal(steps: Int) {
        val safeValue = steps.coerceAtLeast(1)
        withContext(io) {
            prefs.edit {
                setInt(R.string.settings_game_goals_day_steps_key, safeValue)
            }
        }
    }

    override suspend fun setWeeklyStepGoal(steps: Int) {
        val safeValue = steps.coerceAtLeast(1)
        withContext(io) {
            prefs.edit {
                setInt(R.string.settings_game_goals_week_steps_key, safeValue)
            }
        }
    }

    override suspend fun setWeeklyDailyLimit(fraction: Float) {
        val safeValue = fraction.coerceIn(MIN_DAILY_PORTION, MAX_DAILY_PORTION)
        withContext(io) {
            val key = context.getString(R.string.settings_game_goals_week_steps_daily_percentage_key)
            prefs.edit {
                setFloat(key, safeValue)
            }
        }
    }

    private fun readState(): GoalsSettingsState {
        val notificationsEnabled = prefs.getBoolean(
            context.getString(R.string.settings_game_goals_notification_enabled_key),
            notificationDefault
        )
        val dailyGoal = prefs.getIntResString(
            R.string.settings_game_goals_day_steps_key,
            R.string.settings_game_goals_day_steps_default
        )
        val weeklyGoal = prefs.getIntResString(
            R.string.settings_game_goals_week_steps_key,
            R.string.settings_game_goals_week_steps_default
        )
        val portion = prefs.getFloatResString(
            R.string.settings_game_goals_week_steps_daily_percentage_key,
            R.string.settings_game_goals_week_steps_daily_percentage_default
        ).coerceIn(MIN_DAILY_PORTION, MAX_DAILY_PORTION)

        return GoalsSettingsState(
            notificationsEnabled = notificationsEnabled,
            dailyStepGoal = dailyGoal,
            weeklyStepGoal = weeklyGoal,
            weeklyProgressDailyLimit = portion
        )
    }

    companion object {
        private val MIN_DAILY_PORTION: Float = (1f / Time.WEEK_IN_DAYS.toFloat())
        private const val MAX_DAILY_PORTION: Float = 1f
    }
}
