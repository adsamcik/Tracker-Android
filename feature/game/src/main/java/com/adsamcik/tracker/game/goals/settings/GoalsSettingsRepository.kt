package com.adsamcik.tracker.game.goals.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import com.adsamcik.tracker.game.preferences.GamePreferenceKeys
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.Preferences
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

data class RememberedGameSetup(
	val goalValue: Int,
	val difficulty: String?,
)

data class GoalsSettingsState(
	val notificationsEnabled: Boolean,
	val dailyStepGoal: Int,
	val weeklyStepGoal: Int,
	val weeklyProgressDailyLimit: Float,
	val gameHapticsEnabled: Boolean,
	val quietCoachingEnabled: Boolean,
	val rememberLastSetup: Boolean,
	val rememberedOutrunSetup: RememberedGameSetup?,
	val rememberedTerritorySetup: RememberedGameSetup?,
	val rememberedZenSetup: RememberedGameSetup?,
	val dailyGoalReachedPeriod: Int?,
	val weeklyGoalReachedPeriod: Int?,
	val rememberedFuseRunSetup: RememberedGameSetup? = null,
	val rememberedSwitchbackSetup: RememberedGameSetup? = null,
) {
	companion object {
		fun defaults(): GoalsSettingsState = GoalsSettingsState(
			notificationsEnabled = GoalsSettingsDefaults.NOTIFICATIONS_ENABLED,
			dailyStepGoal = GoalsSettingsDefaults.DAILY_STEP_GOAL,
			weeklyStepGoal = GoalsSettingsDefaults.WEEKLY_STEP_GOAL,
			weeklyProgressDailyLimit = GoalsSettingsDefaults.WEEKLY_DAILY_LIMIT,
			gameHapticsEnabled = GoalsSettingsDefaults.GAME_HAPTICS_ENABLED,
			quietCoachingEnabled = GoalsSettingsDefaults.QUIET_COACHING_ENABLED,
			rememberLastSetup = GoalsSettingsDefaults.REMEMBER_LAST_SETUP,
			rememberedOutrunSetup = null,
			rememberedTerritorySetup = null,
			rememberedZenSetup = null,
			dailyGoalReachedPeriod = null,
			weeklyGoalReachedPeriod = null,
			rememberedFuseRunSetup = null,
			rememberedSwitchbackSetup = null,
		)
	}
}

internal object GoalsSettingsDefaults {
	const val NOTIFICATIONS_ENABLED: Boolean = GamePreferenceKeys.GOALS_NOTIFICATION_ENABLED_DEFAULT
	const val DAILY_STEP_GOAL: Int = GamePreferenceKeys.GOALS_DAY_STEPS_DEFAULT
	const val WEEKLY_STEP_GOAL: Int = GamePreferenceKeys.GOALS_WEEK_STEPS_DEFAULT
	const val WEEKLY_DAILY_LIMIT: Float =
		GamePreferenceKeys.GOALS_WEEK_STEPS_DAILY_PERCENTAGE_DEFAULT
	const val GAME_HAPTICS_ENABLED: Boolean = true
	const val QUIET_COACHING_ENABLED: Boolean = true
	const val REMEMBER_LAST_SETUP: Boolean = false
}

interface GoalsSettingsRepository {
	val data: Flow<GoalsSettingsState>

	suspend fun setNotificationsEnabled(enabled: Boolean)
	suspend fun setDailyStepGoal(steps: Int)
	suspend fun setWeeklyStepGoal(steps: Int)
	suspend fun setWeeklyDailyLimit(fraction: Float)
	suspend fun setGameHapticsEnabled(enabled: Boolean)
	suspend fun setQuietCoachingEnabled(enabled: Boolean)
	suspend fun setRememberLastSetup(enabled: Boolean)
	suspend fun setRememberedOutrunSetup(goalMeters: Int, difficulty: String?)
	suspend fun setRememberedTerritorySetup(goalCells: Int, difficulty: String?)
	suspend fun setRememberedZenSetup(goalMinutes: Int, difficulty: String?)
	suspend fun setRememberedFuseRunSetup(goalCharges: Int, difficulty: String?)
	suspend fun setRememberedSwitchbackSetup(goalTurns: Int, difficulty: String?)
	suspend fun setDailyGoalReachedPeriod(period: Int?)
	suspend fun setWeeklyGoalReachedPeriod(period: Int?)
}

internal data class LegacyGoalsSettings(
	val notificationsEnabled: Boolean,
	val dailyStepGoal: Int,
	val weeklyStepGoal: Int,
	val weeklyProgressDailyLimit: Float,
	val dailyGoalReachedPeriod: Int?,
	val weeklyGoalReachedPeriod: Int?,
) {
	companion object {
		fun defaults(): LegacyGoalsSettings = LegacyGoalsSettings(
			notificationsEnabled = GoalsSettingsDefaults.NOTIFICATIONS_ENABLED,
			dailyStepGoal = GoalsSettingsDefaults.DAILY_STEP_GOAL,
			weeklyStepGoal = GoalsSettingsDefaults.WEEKLY_STEP_GOAL,
			weeklyProgressDailyLimit = GoalsSettingsDefaults.WEEKLY_DAILY_LIMIT,
			dailyGoalReachedPeriod = null,
			weeklyGoalReachedPeriod = null,
		)
	}
}

internal fun interface LegacyGoalsSettingsSource {
	suspend fun read(): LegacyGoalsSettings
}

private class PreferencesLegacyGoalsSettingsSource(
	context: Context,
) : LegacyGoalsSettingsSource {
	private val preferences = Preferences(context)

	@Suppress("DEPRECATION")
	override suspend fun read(): LegacyGoalsSettings {
		val defaults = LegacyGoalsSettings.defaults()
		return LegacyGoalsSettings(
			notificationsEnabled = preferences.fetchBoolean(
				GamePreferenceKeys.GOALS_NOTIFICATION_ENABLED,
				defaults.notificationsEnabled,
			),
			dailyStepGoal = preferences.fetchIntStringKey(
				GamePreferenceKeys.GOALS_DAY_STEPS,
				defaults.dailyStepGoal,
			).coerceAtLeast(1),
			weeklyStepGoal = preferences.fetchIntStringKey(
				GamePreferenceKeys.GOALS_WEEK_STEPS,
				defaults.weeklyStepGoal,
			).coerceAtLeast(1),
			weeklyProgressDailyLimit = preferences.fetchFloatStringKey(
				GamePreferenceKeys.GOALS_WEEK_STEPS_DAILY_PERCENTAGE,
				defaults.weeklyProgressDailyLimit,
			).coerceIn(MIN_DAILY_PORTION, MAX_DAILY_PORTION),
			dailyGoalReachedPeriod = preferences.fetchInt(
				GamePreferenceKeys.GOALS_DAY_REACHED,
				ABSENT_REACHED_PERIOD,
			).takeIf { it >= 0 },
			weeklyGoalReachedPeriod = preferences.fetchInt(
				GamePreferenceKeys.GOALS_WEEK_REACHED,
				ABSENT_REACHED_PERIOD,
			).takeIf { it >= 0 },
		)
	}
}

private object GoalsSettingsSerializer : Serializer<GoalsSettingsProto> {
	override val defaultValue: GoalsSettingsProto = GoalsSettingsProto.getDefaultInstance()

	override suspend fun readFrom(input: InputStream): GoalsSettingsProto = try {
		GoalsSettingsProto.parseFrom(input)
	} catch (exception: InvalidProtocolBufferException) {
		Log.w(
			"GoalsSettings",
			"Corruption while reading goals settings proto; using defaults",
			exception,
		)
		defaultValue
	}

	override suspend fun writeTo(t: GoalsSettingsProto, output: OutputStream) {
		t.writeTo(output)
	}
}

private val Context.goalsSettingsDataStore: DataStore<GoalsSettingsProto> by dataStore(
	fileName = "goals_settings.pb",
	serializer = GoalsSettingsSerializer,
)
private val goalsSettingsMigrationMutex = Mutex()

class DefaultGoalsSettingsRepository internal constructor(
	private val store: DataStore<GoalsSettingsProto>,
	private val io: CoroutineDispatcher,
	private val legacySource: LegacyGoalsSettingsSource,
) : GoalsSettingsRepository {
	constructor(
		context: Context,
		io: CoroutineDispatcher,
	) : this(
		store = context.applicationContext.goalsSettingsDataStore,
		io = io,
		legacySource = PreferencesLegacyGoalsSettingsSource(context.applicationContext),
	)

	override val data: Flow<GoalsSettingsState> = flow {
		ensureMigrated()
		emitAll(store.data.map(::toState))
	}

	override suspend fun setNotificationsEnabled(enabled: Boolean) {
		update { it.setNotificationsEnabled(enabled) }
	}

	override suspend fun setDailyStepGoal(steps: Int) {
		update { it.setDailyStepGoal(steps.coerceAtLeast(1)) }
	}

	override suspend fun setWeeklyStepGoal(steps: Int) {
		update { it.setWeeklyStepGoal(steps.coerceAtLeast(1)) }
	}

	override suspend fun setWeeklyDailyLimit(fraction: Float) {
		update {
			it.setWeeklyDailyLimit(
				fraction.coerceIn(MIN_DAILY_PORTION, MAX_DAILY_PORTION),
			)
		}
	}

	override suspend fun setGameHapticsEnabled(enabled: Boolean) {
		update { it.setGameHapticsEnabled(enabled) }
	}

	override suspend fun setQuietCoachingEnabled(enabled: Boolean) {
		update { it.setQuietCoachingEnabled(enabled) }
	}

	override suspend fun setRememberLastSetup(enabled: Boolean) {
		update { builder ->
			builder.setRememberLastSetup(enabled).also {
				if (!enabled) it.clearRememberedSetups()
			}
		}
	}

	override suspend fun setRememberedOutrunSetup(goalMeters: Int, difficulty: String?) {
		setRememberedSetup(OutrunConfiguration.GAME_ID, goalMeters, difficulty)
	}

	override suspend fun setRememberedTerritorySetup(goalCells: Int, difficulty: String?) {
		setRememberedSetup(TerritoryConfiguration.GAME_ID, goalCells, difficulty)
	}

	override suspend fun setRememberedZenSetup(goalMinutes: Int, difficulty: String?) {
		setRememberedSetup(ZenWalkConfiguration.GAME_ID, goalMinutes, difficulty)
	}

	override suspend fun setRememberedFuseRunSetup(goalCharges: Int, difficulty: String?) {
		setRememberedSetup(FuseRunConfiguration.GAME_ID, goalCharges, difficulty)
	}

	override suspend fun setRememberedSwitchbackSetup(goalTurns: Int, difficulty: String?) {
		setRememberedSetup(SwitchbackConfiguration.GAME_ID, goalTurns, difficulty)
	}

	override suspend fun setDailyGoalReachedPeriod(period: Int?) {
		update { builder ->
			if (period == null) {
				builder.clearDailyGoalReachedPeriod()
			} else {
				builder.setDailyGoalReachedPeriod(period.coerceAtLeast(0))
			}
		}
	}

	override suspend fun setWeeklyGoalReachedPeriod(period: Int?) {
		update { builder ->
			if (period == null) {
				builder.clearWeeklyGoalReachedPeriod()
			} else {
				builder.setWeeklyGoalReachedPeriod(period.coerceAtLeast(0))
			}
		}
	}

	private suspend fun setRememberedSetup(
		gameId: String,
		goalValue: Int,
		difficulty: String?,
	) {
		val setup = coerceRememberedSetup(gameId, goalValue, difficulty)
		update { builder ->
			if (!builder.rememberLastSetup) {
				builder.clearRememberedSetups()
			} else {
				builder.setRememberedSetup(gameId, setup)
			}
		}
	}

	private suspend fun update(
		transform: (GoalsSettingsProto.Builder) -> GoalsSettingsProto.Builder,
	) {
		withContext(io) {
			ensureMigrated()
			store.updateData { current -> transform(current.toBuilder()).build() }
		}
	}

	private suspend fun ensureMigrated() {
		goalsSettingsMigrationMutex.withLock {
			if (store.data.first().legacyMigrated) return
			val legacy = legacySource.read()
			store.updateData { current ->
				if (current.legacyMigrated) {
					current
				} else {
					current.toBuilder()
						.setNotificationsEnabled(legacy.notificationsEnabled)
						.setDailyStepGoal(legacy.dailyStepGoal)
						.setWeeklyStepGoal(legacy.weeklyStepGoal)
						.setWeeklyDailyLimit(legacy.weeklyProgressDailyLimit)
						.setLegacyMigrated(true)
						.apply {
							legacy.dailyGoalReachedPeriod?.let(::setDailyGoalReachedPeriod)
							legacy.weeklyGoalReachedPeriod?.let(::setWeeklyGoalReachedPeriod)
						}
						.build()
				}
			}
		}
	}

	private fun toState(proto: GoalsSettingsProto): GoalsSettingsState {
		val rememberLastSetup = proto.optionalBoolean(
			hasValue = proto.hasRememberLastSetup(),
			value = proto.rememberLastSetup,
			default = GoalsSettingsDefaults.REMEMBER_LAST_SETUP,
		)
		return GoalsSettingsState(
			notificationsEnabled = proto.optionalBoolean(
				hasValue = proto.hasNotificationsEnabled(),
				value = proto.notificationsEnabled,
				default = GoalsSettingsDefaults.NOTIFICATIONS_ENABLED,
			),
			dailyStepGoal = proto.dailyStepGoal.takeIf { it > 0 }
				?: GoalsSettingsDefaults.DAILY_STEP_GOAL,
			weeklyStepGoal = proto.weeklyStepGoal.takeIf { it > 0 }
				?: GoalsSettingsDefaults.WEEKLY_STEP_GOAL,
			weeklyProgressDailyLimit = proto.weeklyDailyLimit
				.takeIf { it in MIN_DAILY_PORTION..MAX_DAILY_PORTION }
				?: GoalsSettingsDefaults.WEEKLY_DAILY_LIMIT,
			gameHapticsEnabled = proto.optionalBoolean(
				hasValue = proto.hasGameHapticsEnabled(),
				value = proto.gameHapticsEnabled,
				default = GoalsSettingsDefaults.GAME_HAPTICS_ENABLED,
			),
			quietCoachingEnabled = proto.optionalBoolean(
				hasValue = proto.hasQuietCoachingEnabled(),
				value = proto.quietCoachingEnabled,
				default = GoalsSettingsDefaults.QUIET_COACHING_ENABLED,
			),
			rememberLastSetup = rememberLastSetup,
			rememberedOutrunSetup = proto.rememberedSetupOrNull(
				rememberLastSetup,
				OutrunConfiguration.GAME_ID,
				proto.hasRememberedOutrunGoal(),
				proto.rememberedOutrunGoal,
				proto.hasRememberedOutrunDifficulty(),
				proto.rememberedOutrunDifficulty,
			),
			rememberedTerritorySetup = proto.rememberedSetupOrNull(
				rememberLastSetup,
				TerritoryConfiguration.GAME_ID,
				proto.hasRememberedTerritoryGoal(),
				proto.rememberedTerritoryGoal,
				proto.hasRememberedTerritoryDifficulty(),
				proto.rememberedTerritoryDifficulty,
			),
			rememberedZenSetup = proto.rememberedSetupOrNull(
				rememberLastSetup,
				ZenWalkConfiguration.GAME_ID,
				proto.hasRememberedZenGoal(),
				proto.rememberedZenGoal,
				proto.hasRememberedZenDifficulty(),
				proto.rememberedZenDifficulty,
			),
			rememberedFuseRunSetup = proto.rememberedSetupOrNull(
				rememberLastSetup,
				FuseRunConfiguration.GAME_ID,
				proto.hasRememberedFuseRunGoal(),
				proto.rememberedFuseRunGoal,
				proto.hasRememberedFuseRunDifficulty(),
				proto.rememberedFuseRunDifficulty,
			),
			rememberedSwitchbackSetup = proto.rememberedSetupOrNull(
				rememberLastSetup,
				SwitchbackConfiguration.GAME_ID,
				proto.hasRememberedSwitchbackGoal(),
				proto.rememberedSwitchbackGoal,
				proto.hasRememberedSwitchbackDifficulty(),
				proto.rememberedSwitchbackDifficulty,
			),
			dailyGoalReachedPeriod = proto.dailyGoalReachedPeriod
				.takeIf { proto.hasDailyGoalReachedPeriod() },
			weeklyGoalReachedPeriod = proto.weeklyGoalReachedPeriod
				.takeIf { proto.hasWeeklyGoalReachedPeriod() },
		)
	}
}

private fun GoalsSettingsProto.optionalBoolean(
	hasValue: Boolean,
	value: Boolean,
	default: Boolean,
): Boolean = if (hasValue) value else default

private fun GoalsSettingsProto.rememberedSetupOrNull(
	rememberLastSetup: Boolean,
	gameId: String,
	hasGoal: Boolean,
	goalValue: Int,
	hasDifficulty: Boolean,
	difficulty: String,
): RememberedGameSetup? {
	if (!rememberLastSetup || (!hasGoal && !hasDifficulty)) return null
	return coerceRememberedSetup(
		gameId = gameId,
		goalValue = goalValue.takeIf { hasGoal }
			?: MiniGameConfigurations.defaultFor(gameId)?.goal?.displayValue
			?: return null,
		difficulty = difficulty.takeIf { hasDifficulty },
	)
}

private fun coerceRememberedSetup(
	gameId: String,
	goalValue: Int,
	difficulty: String?,
): RememberedGameSetup {
	val supported = MiniGameConfigurations.supportedFor(gameId)
	val default = requireNotNull(MiniGameConfigurations.defaultFor(gameId))
	val supportedGoal = supported
		.firstOrNull { it.goal.displayValue == goalValue }
		?.goal
		?: default.goal
	val supportedDifficulty = if (default.difficulty == null) {
		null
	} else {
		supported
			.firstOrNull { it.difficulty?.name == difficulty }
			?.difficulty
			?: default.difficulty
	}
	val configuration = supported.firstOrNull {
		it.goal == supportedGoal && it.difficulty == supportedDifficulty
	} ?: default
	return configuration.toRememberedSetup()
}

private fun MiniGameConfiguration.toRememberedSetup(): RememberedGameSetup =
	RememberedGameSetup(
		goalValue = goal.displayValue,
		difficulty = difficulty?.name,
	)

private fun GoalsSettingsProto.Builder.clearRememberedSetups(): GoalsSettingsProto.Builder =
	clearRememberedOutrunGoal()
		.clearRememberedOutrunDifficulty()
		.clearRememberedTerritoryGoal()
		.clearRememberedTerritoryDifficulty()
		.clearRememberedZenGoal()
		.clearRememberedZenDifficulty()
		.clearRememberedFuseRunGoal()
		.clearRememberedFuseRunDifficulty()
		.clearRememberedSwitchbackGoal()
		.clearRememberedSwitchbackDifficulty()

private fun GoalsSettingsProto.Builder.setRememberedSetup(
	gameId: String,
	setup: RememberedGameSetup,
): GoalsSettingsProto.Builder = when (gameId) {
	OutrunConfiguration.GAME_ID -> setRememberedOutrunGoal(setup.goalValue).apply {
		if (setup.difficulty == null) {
			clearRememberedOutrunDifficulty()
		} else {
			setRememberedOutrunDifficulty(setup.difficulty)
		}
	}
	TerritoryConfiguration.GAME_ID -> setRememberedTerritoryGoal(setup.goalValue).apply {
		if (setup.difficulty == null) {
			clearRememberedTerritoryDifficulty()
		} else {
			setRememberedTerritoryDifficulty(setup.difficulty)
		}
	}
	ZenWalkConfiguration.GAME_ID -> setRememberedZenGoal(setup.goalValue).apply {
		if (setup.difficulty == null) {
			clearRememberedZenDifficulty()
		} else {
			setRememberedZenDifficulty(setup.difficulty)
		}
	}
	FuseRunConfiguration.GAME_ID -> setRememberedFuseRunGoal(setup.goalValue).apply {
		if (setup.difficulty == null) {
			clearRememberedFuseRunDifficulty()
		} else {
			setRememberedFuseRunDifficulty(setup.difficulty)
		}
	}
	SwitchbackConfiguration.GAME_ID -> setRememberedSwitchbackGoal(setup.goalValue).apply {
		if (setup.difficulty == null) {
			clearRememberedSwitchbackDifficulty()
		} else {
			setRememberedSwitchbackDifficulty(setup.difficulty)
		}
	}
	else -> this
}

private val MIN_DAILY_PORTION: Float = 1f / Time.WEEK_IN_DAYS.toFloat()
private const val MAX_DAILY_PORTION: Float = 1f
private const val ABSENT_REACHED_PERIOD: Int = -1
