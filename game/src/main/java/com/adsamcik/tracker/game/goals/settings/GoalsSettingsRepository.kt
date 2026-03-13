package com.adsamcik.tracker.game.goals.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

// Contract:
// Inputs: Proto DataStore file, legacy SharedPreferences for one-time migration.
// Outputs: Flow<GoalsSettingsState>; mutation via suspend setters.
// Failure: Corruption -> emit default settings; logged (debug). No exceptions leak outward.

/** Immutable snapshot of goals-related preferences. */
data class GoalsSettingsState(
    val notificationsEnabled: Boolean,
    val dailyStepGoal: Int,
    val weeklyStepGoal: Int,
    val weeklyProgressDailyLimit: Float,
    val challengesEnabled: Boolean = true,
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

    /** Toggle challenges feature. */
    suspend fun setChallengesEnabled(enabled: Boolean)
}

private object GoalsSettingsSerializer : Serializer<GoalsSettingsProto> {
    override val defaultValue: GoalsSettingsProto = GoalsSettingsProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): GoalsSettingsProto = try {
        GoalsSettingsProto.parseFrom(input)
    } catch (e: Exception) {
        Log.w("GoalsSettings", "Corruption while reading goals settings proto – using defaults", e)
        defaultValue
    }

    override suspend fun writeTo(t: GoalsSettingsProto, output: OutputStream) { t.writeTo(output) }
}

private val Context.goalsSettingsDataStore: DataStore<GoalsSettingsProto> by dataStore(
    fileName = "goals_settings.pb",
    serializer = GoalsSettingsSerializer
)

/** DataStore-backed implementation. */
class DefaultGoalsSettingsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher
) : GoalsSettingsRepository {

    private val prefs: Preferences
        get() = Preferences(context)

    private val notificationDefault by lazy {
        context.getString(R.string.settings_game_goals_notification_enabled_default).toBoolean()
    }

    private val dailyStepDefault by lazy {
        context.getString(R.string.settings_game_goals_day_steps_default).toInt()
    }

    private val weeklyStepDefault by lazy {
        context.getString(R.string.settings_game_goals_week_steps_default).toInt()
    }

    private val dailyLimitDefault by lazy {
        context.getString(R.string.settings_game_goals_week_steps_daily_percentage_default).toFloat()
    }

    override val data: Flow<GoalsSettingsState> = context.goalsSettingsDataStore.data
        .onStart { ensureMigrated() }
        .map { proto ->
            GoalsSettingsState(
                notificationsEnabled = proto.notificationsEnabled,
                dailyStepGoal = proto.dailyStepGoal.takeIf { it > 0 } ?: dailyStepDefault,
                weeklyStepGoal = proto.weeklyStepGoal.takeIf { it > 0 } ?: weeklyStepDefault,
                weeklyProgressDailyLimit = proto.weeklyDailyLimit.takeIf { it > 0f } ?: dailyLimitDefault,
                challengesEnabled = proto.challengesEnabled,
            )
        }

    override fun current(): GoalsSettingsState {
        // Fallback to reading from legacy SharedPreferences if DataStore not yet loaded
        return readFromPreferences()
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        withContext(io) {
            context.goalsSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setNotificationsEnabled(enabled)
                    .build()
            }
        }
    }

    override suspend fun setDailyStepGoal(steps: Int) {
        val safeValue = steps.coerceAtLeast(1)
        withContext(io) {
            context.goalsSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setDailyStepGoal(safeValue)
                    .build()
            }
        }
    }

    override suspend fun setWeeklyStepGoal(steps: Int) {
        val safeValue = steps.coerceAtLeast(1)
        withContext(io) {
            context.goalsSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setWeeklyStepGoal(safeValue)
                    .build()
            }
        }
    }

    override suspend fun setWeeklyDailyLimit(fraction: Float) {
        val safeValue = fraction.coerceIn(MIN_DAILY_PORTION, MAX_DAILY_PORTION)
        withContext(io) {
            context.goalsSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setWeeklyDailyLimit(safeValue)
                    .build()
            }
        }
    }

    override suspend fun setChallengesEnabled(enabled: Boolean) {
        withContext(io) {
            context.goalsSettingsDataStore.updateData { current ->
                current.toBuilder()
                    .setChallengesEnabled(enabled)
                    .build()
            }
        }
    }

    private suspend fun ensureMigrated() {
        val current = context.goalsSettingsDataStore.data.first()
        if (current.legacyMigrated) return

        // One-time import from legacy SharedPreferences
        val legacyState = readFromPreferences()

        context.goalsSettingsDataStore.updateData { proto ->
            proto.toBuilder()
                .setNotificationsEnabled(legacyState.notificationsEnabled)
                .setDailyStepGoal(legacyState.dailyStepGoal)
                .setWeeklyStepGoal(legacyState.weeklyStepGoal)
                .setWeeklyDailyLimit(legacyState.weeklyProgressDailyLimit)
                .setChallengesEnabled(legacyState.challengesEnabled)
                .setLegacyMigrated(true)
                .build()
        }
    }

    // Legacy migration helper: reads from SharedPreferences for one-time DataStore migration.
    // Sync reads acceptable here as this runs only during migration or as fallback.
    @Suppress("DEPRECATION")
    private fun readFromPreferences(): GoalsSettingsState {
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
        val challengesEnabled = prefs.getBoolean(
            context.getString(R.string.settings_game_challenge_enable_key),
            context.getString(R.string.settings_game_challenge_enable_default).toBoolean()
        )

        return GoalsSettingsState(
            notificationsEnabled = notificationsEnabled,
            dailyStepGoal = dailyGoal,
            weeklyStepGoal = weeklyGoal,
            weeklyProgressDailyLimit = portion,
            challengesEnabled = challengesEnabled,
        )
    }

    companion object {
        private val MIN_DAILY_PORTION: Float = (1f / Time.WEEK_IN_DAYS.toFloat())
        private const val MAX_DAILY_PORTION: Float = 1f
    }
}
