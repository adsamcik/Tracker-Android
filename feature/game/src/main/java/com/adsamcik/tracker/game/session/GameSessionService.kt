package com.adsamcik.tracker.game.session

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSource
import com.adsamcik.tracker.game.repository.DefaultGameRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.service.CoreService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Android lifecycle shell for a non-restorable foreground mini-game runtime.
 */
@AndroidEntryPoint
internal class GameSessionService : CoreService() {
	@Inject lateinit var stateStore: GameSessionStateStore
	@Inject lateinit var registry: MiniGameRegistry
	@Inject lateinit var locationSource: MiniGameLocationSource
	@Inject lateinit var scoreDao: MiniGameScoreDao
	@Inject lateinit var gameRepository: DefaultGameRepository
	@Inject lateinit var dispatchers: DispatchersProvider
	@Inject lateinit var goalsSettingsRepository: GoalsSettingsRepository

	private lateinit var runtime: GameSessionRuntime
	private lateinit var notificationUpdater: GameSessionNotificationUpdater
	private lateinit var hapticGate: GameSessionHapticGate
	private var settingsJob: Job? = null
	private var terminalStopRequested = false

	override fun onCreate() {
		super.onCreate()
		val notificationFactory = GameSessionNotificationFactory(this)
		val notificationManager = getSystemService(NotificationManager::class.java)
		notificationUpdater = GameSessionNotificationUpdater(
			clock = SystemGameSessionClock,
			factory = notificationFactory,
			postNotification = { notification ->
				notificationManager.notify(NOTIFICATION_ID, notification)
			},
		)
		val foregroundFailure = startLocationForeground(
			notificationFactory.create(GameSessionState.Idle),
		)
		if (foregroundFailure != null) {
			stateStore.publish(GameSessionState.Failed(null, foregroundFailure))
			stopSelf()
			return
		}

		hapticGate = GameSessionHapticGate(
			clock = SystemGameSessionClock,
			performer = DefaultGameSessionHapticPerformer(this),
		)
		settingsJob = launch {
			goalsSettingsRepository.data.collect(hapticGate::updateSettings)
		}
		runtime = GameSessionRuntime(
			parentScope = this,
			dispatcher = dispatchers.default,
			clock = SystemGameSessionClock,
			sessionFactory = ConfiguredGameSessionFactory(registry),
			locationSource = locationSource,
			persistence = DefaultGameSessionPersistence(scoreDao, gameRepository, dispatchers),
			statePublisher = stateStore,
			onStateUpdate = ::onRuntimeStateUpdate,
			onTerminal = ::stopAfterTerminalState,
		)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		if (!::runtime.isInitialized) {
			stopSelfResult(startId)
			return START_NOT_STICKY
		}
		if (intent == null) {
			if (!runtime.hasActiveSession) stopSelfResult(startId)
			return START_NOT_STICKY
		}

		val decoded = GameSessionIntentCodec.decode(
			action = intent.action,
			gameId = intent.getStringExtra(GameSessionIntentContract.EXTRA_GAME_ID),
			goalValue = intent.takeIf {
				it.hasExtra(GameSessionIntentContract.EXTRA_GOAL_VALUE)
			}?.getIntExtra(GameSessionIntentContract.EXTRA_GOAL_VALUE, 0),
			difficulty = intent.getStringExtra(GameSessionIntentContract.EXTRA_DIFFICULTY),
		)
		when (decoded) {
			is GameSessionIntentDecodeResult.Decoded -> {
				if (!runtime.submit(decoded.command)) {
					publishFailure(
						configuration = (decoded.command as? GameSessionCommand.Start)?.configuration,
						reason = GameSessionFailureReason.INTERNAL_ERROR,
					)
				}
			}
			is GameSessionIntentDecodeResult.Invalid -> {
				publishFailure(null, GameSessionFailureReason.INVALID_COMMAND)
			}
		}
		return START_NOT_STICKY
	}

	private fun startLocationForeground(notification: Notification): GameSessionFailureReason? = try {
		ServiceCompat.startForeground(
			this,
			NOTIFICATION_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
		)
		null
	} catch (_: SecurityException) {
		GameSessionFailureReason.PERMISSION_REQUIRED
	} catch (@Suppress("TooGenericExceptionCaught") exception: RuntimeException) {
		val isForegroundStartRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
			exception::class.java.name == "android.app.ForegroundServiceStartNotAllowedException"
		if (!isForegroundStartRestricted) throw exception
		GameSessionFailureReason.INTERNAL_ERROR
	}

	/**
	 * Losing the task or turning the screen off has no game-session semantics.
	 * Only explicit Pause/Resume/Finish intents change runtime state.
	 */
	override fun onTaskRemoved(rootIntent: Intent?) = Unit

	override fun onDestroy() {
		settingsJob?.cancel()
		if (::runtime.isInitialized) runtime.shutdown()
		if (!terminalStopRequested) {
			stopForeground(STOP_FOREGROUND_REMOVE)
		}
		super.onDestroy()
	}

	private fun onRuntimeStateUpdate(update: GameSessionStateUpdate) {
		hapticGate.onState(update.state)
		notificationUpdater.update(update)
	}

	private fun publishFailure(
		configuration: com.adsamcik.tracker.game.minigame.MiniGameConfiguration?,
		reason: GameSessionFailureReason,
	) {
		val state = GameSessionState.Failed(configuration, reason)
		stateStore.publish(state)
		onRuntimeStateUpdate(GameSessionStateUpdate(state, isCommandTransition = true))
		stopAfterTerminalState()
	}

	private fun stopAfterTerminalState() {
		if (terminalStopRequested) return
		terminalStopRequested = true
		stopForeground(STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	internal companion object {
		const val NOTIFICATION_ID: Int = 5_104
	}
}
