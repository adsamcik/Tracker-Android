package com.adsamcik.tracker.game.goals.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.game.preferences.GamePreferenceKeys
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
        GamePreferenceKeys.GOALS_NOTIFICATION_ENABLED_DEFAULT
    }

    private val dailyStepDefault by lazy {
        GamePreferenceKeys.GOALS_DAY_STEPS_DEFAULT
    }

    private val weeklyStepDefault by lazy {
        GamePreferenceKeys.GOALS_WEEK_STEPS_DEFAULT
    }

    private val dailyLimitDefault by lazy {
        GamePreferenceKeys.GOALS_WEEK_STEPS_DAILY_PERCENTAGE_DEFAULT
    }

    override val data: Flow<GoalsSettingsState> = context.goalsSettingsDataStore.data
        .onStart { ensureMigrated() }
        .map { proto ->
            GoalsSettingsState(
                notificationsEnabled = proto.notificationsEnabled,
                dailyStepGoal = proto.dailyStepGoal.takeIf { it > 0 } ?: dailyStepDefault,
                weeklyStepGoal = proto.weeklyStepGoal.takeIf { it > 0 } ?: weeklyStepDefault,
                weeklyProgressDailyLimit = proto.weeklyDailyLimit.takeIf { it > 0f } ?: dailyLimitDefault,
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
                .setLegacyMigrated(true)
                .build()
        }
    }

    // Legacy migration helper: reads from SharedPreferences for one-time DataStore migration.
    // Sync reads acceptable here as this runs only during migration or as fallback.
    @Suppress("DEPRECATION")
    private fun readFromPreferences(): GoalsSettingsState {
        val notificationsEnabled = prefs.getBoolean(
            GamePreferenceKeys.GOALS_NOTIFICATION_ENABLED,
            notificationDefault
        )
        val dailyGoal = prefs.getIntStringKey(
            GamePreferenceKeys.GOALS_DAY_STEPS,
            dailyStepDefault,
        )
        val weeklyGoal = prefs.getIntStringKey(
            GamePreferenceKeys.GOALS_WEEK_STEPS,
            weeklyStepDefault,
        )
        val portion = prefs.getFloatStringKey(
            GamePreferenceKeys.GOALS_WEEK_STEPS_DAILY_PERCENTAGE,
            dailyLimitDefault,
        ).coerceIn(MIN_DAILY_PORTION, MAX_DAILY_PORTION)

        return GoalsSettingsState(
            notificationsEnabled = notificationsEnabled,
            dailyStepGoal = dailyGoal,
            weeklyStepGoal = weeklyGoal,
            weeklyProgressDailyLimit = portion,
        )
    }

    companion object {
        private val MIN_DAILY_PORTION: Float = (1f / Time.WEEK_IN_DAYS.toFloat())
        private const val MAX_DAILY_PORTION: Float = 1f
    }
}
