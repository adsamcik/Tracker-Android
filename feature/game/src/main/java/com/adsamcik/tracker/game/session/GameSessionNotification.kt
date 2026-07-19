package com.adsamcik.tracker.game.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import kotlin.math.roundToInt

internal object GameSessionNotificationChannels {
	fun ensureCreated(context: Context) {
		val manager = context.getSystemService(NotificationManager::class.java)
		val channelId = context.getString(R.string.game_session_channel_id)
		if (manager.getNotificationChannel(channelId) != null) return
		manager.createNotificationChannel(
			NotificationChannel(
				channelId,
				context.getString(R.string.game_session_channel_name),
				NotificationManager.IMPORTANCE_LOW,
			).apply {
				description = context.getString(R.string.game_session_channel_description)
				setSound(null, null)
				enableVibration(false)
				vibrationPattern = null
				enableLights(false)
				setShowBadge(false)
				lockscreenVisibility = Notification.VISIBILITY_PRIVATE
			},
		)
	}
}

internal class GameSessionNotificationFactory(
	private val context: Context,
) {
	fun create(state: GameSessionState): Notification {
		GameSessionNotificationChannels.ensureCreated(context)
		val channelId = context.getString(R.string.game_session_channel_id)
		val publicVersion = NotificationCompat.Builder(context, channelId)
			.setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setContentTitle(context.getString(R.string.game_session_notification_public))
			.setContentText(context.getString(R.string.game_session_notification_public))
			.build()

		val builder = NotificationCompat.Builder(context, channelId)
			.setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
			.setPublicVersion(publicVersion)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setShowWhen(false)
			.setContentTitle(context.getString(R.string.game_session_notification_title))
			.setContentText(contentText(state))
			.setContentIntent(contentIntent())

		when (state) {
			is GameSessionState.Active -> {
				builder.addAction(commandAction(GameSessionCommand.Pause))
				.addAction(commandAction(GameSessionCommand.Finish))
			}
			is GameSessionState.Paused -> {
				builder.addAction(commandAction(GameSessionCommand.Resume))
				.addAction(commandAction(GameSessionCommand.Finish))
			}
			else -> Unit
		}
		return builder.build()
	}

	private fun contentText(state: GameSessionState): String = when (state) {
		GameSessionState.Idle -> context.getString(R.string.game_session_notification_starting)
		is GameSessionState.Starting -> context.getString(
			R.string.game_session_notification_preparing,
			gameName(state.configuration),
		)
		is GameSessionState.Active -> context.getString(
			R.string.game_session_notification_active,
			gameName(state.configuration),
			progressPercent(state.snapshot.goalProgress),
			formatElapsed(state.snapshot.elapsedActiveTimeMs),
		)
		is GameSessionState.Paused -> context.getString(
			R.string.game_session_notification_paused,
			gameName(state.configuration),
		)
		is GameSessionState.Finishing -> context.getString(R.string.game_session_notification_finishing)
		is GameSessionState.Finished -> context.getString(R.string.game_session_notification_finished)
		is GameSessionState.Failed -> context.getString(R.string.game_session_notification_failed)
	}

	private fun gameName(configuration: MiniGameConfiguration): String = context.getString(
		when (configuration.gameId) {
			OutrunConfiguration.GAME_ID -> R.string.minigame_outrun_name
			TerritoryConfiguration.GAME_ID -> R.string.minigame_territory_name
			ZenWalkConfiguration.GAME_ID -> R.string.minigame_zenwalk_name
			FuseRunConfiguration.GAME_ID -> R.string.minigame_fuserun_name
			SwitchbackConfiguration.GAME_ID -> R.string.minigame_switchback_name
			else -> R.string.minigame_scores_unknown_game
		},
	)

	private fun progressPercent(progress: MiniGameGoalProgress): Int = when (progress) {
		MiniGameGoalProgress.NotConfigured -> 0
		is MiniGameGoalProgress.Tracked -> (progress.fraction * 100.0).roundToInt()
	}

	private fun formatElapsed(elapsedMs: Long): String {
		val totalSeconds = elapsedMs / 1_000L
		return context.getString(
			R.string.minigame_session_elapsed_format,
			totalSeconds / 60L,
			totalSeconds % 60L,
		)
	}

	private fun contentIntent(): PendingIntent {
		val intent = Intent().apply {
			setClassName(context.packageName, MAIN_ACTIVITY_CLASS_NAME)
			putExtra(NAVIGATE_TO_EXTRA, GAME_DESTINATION)
			addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		}
		return PendingIntent.getActivity(
			context,
			CONTENT_REQUEST_CODE,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	private fun commandAction(command: GameSessionCommand): NotificationCompat.Action {
		val encoded = GameSessionIntentCodec.encode(command)
		val intent = Intent(context, GameSessionService::class.java).apply {
			action = encoded.action
		}
		val (title, requestCode) = when (command) {
			GameSessionCommand.Pause ->
				R.string.game_session_action_pause to PAUSE_REQUEST_CODE
			GameSessionCommand.Resume ->
				R.string.game_session_action_resume to RESUME_REQUEST_CODE
			GameSessionCommand.Finish ->
				R.string.game_session_action_finish to FINISH_REQUEST_CODE
			is GameSessionCommand.Start -> error("Start is not a notification action")
		}
		return NotificationCompat.Action.Builder(
			0,
			context.getString(title),
			PendingIntent.getService(
				context,
				requestCode,
				intent,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
			),
		).build()
	}

	private companion object {
		const val MAIN_ACTIVITY_CLASS_NAME = "com.adsamcik.tracker.app.activity.MainActivityCompose"
		const val NAVIGATE_TO_EXTRA = "navigate_to"
		const val GAME_DESTINATION = "game"
		const val CONTENT_REQUEST_CODE = 5_100
		const val PAUSE_REQUEST_CODE = 5_101
		const val RESUME_REQUEST_CODE = 5_102
		const val FINISH_REQUEST_CODE = 5_103
	}
}

internal class GameSessionNotificationUpdater(
	private val clock: GameSessionClock,
	private val factory: GameSessionNotificationFactory,
	private val postNotification: (Notification) -> Unit,
) {
	private var lastOrdinaryUpdateAtMs: Long? = null

	fun update(update: GameSessionStateUpdate): Boolean {
		val now = clock.elapsedRealtimeMs()
		if (!update.isCommandTransition) {
			val last = lastOrdinaryUpdateAtMs
			if (last != null && now - last < ORDINARY_UPDATE_INTERVAL_MS) return false
			lastOrdinaryUpdateAtMs = now
		} else if (lastOrdinaryUpdateAtMs == null) {
			lastOrdinaryUpdateAtMs = now
		}
		postNotification(factory.create(update.state))
		return true
	}

	private companion object {
		const val ORDINARY_UPDATE_INTERVAL_MS: Long = 5_000L
	}
}
