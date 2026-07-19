package com.adsamcik.tracker.game.session

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBest
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameSessionNotificationTest {
	private lateinit var context: Context
	private lateinit var factory: GameSessionNotificationFactory

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		factory = GameSessionNotificationFactory(context)
	}

	@Test
	fun `channel is low importance silent and non vibrating`() {
		GameSessionNotificationChannels.ensureCreated(context)

		val manager = context.getSystemService(NotificationManager::class.java)
		val channel = manager.getNotificationChannel(context.getString(R.string.game_session_channel_id))
		channel.importance shouldBe NotificationManager.IMPORTANCE_LOW
		channel.sound shouldBe null
		channel.shouldVibrate() shouldBe false
	}

	@Test
	fun `running notification is private redacted ongoing and has pause finish actions`() {
		val notification = factory.create(activeState(paused = false))

		notification.flags and Notification.FLAG_ONGOING_EVENT shouldBe Notification.FLAG_ONGOING_EVENT
		notification.flags and Notification.FLAG_ONLY_ALERT_ONCE shouldBe Notification.FLAG_ONLY_ALERT_ONCE
		notification.visibility shouldBe Notification.VISIBILITY_PRIVATE
		notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("50.0876") shouldBe false
		notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString() shouldBe
			context.getString(R.string.game_session_notification_public)
		notification.actions.map { it.title.toString() } shouldBe listOf(
			context.getString(R.string.game_session_action_pause),
			context.getString(R.string.game_session_action_finish),
		)
		notification.actions.map { shadowOf(it.actionIntent).savedIntent.action } shouldBe listOf(
			GameSessionIntentContract.ACTION_PAUSE,
			GameSessionIntentContract.ACTION_FINISH,
		)
		shadowOf(notification.contentIntent).savedIntent.getStringExtra("navigate_to") shouldBe "game"
	}

	@Test
	fun `paused notification swaps pause for resume`() {
		val notification = factory.create(activeState(paused = true))

		notification.actions.shouldHaveSize(2)
		notification.actions.map { it.title.toString() } shouldBe listOf(
			context.getString(R.string.game_session_action_resume),
			context.getString(R.string.game_session_action_finish),
		)
	}

	@Test
	fun `ordinary updates are throttled while command transitions update immediately`() {
		val clock = MutableClock()
		val posted = mutableListOf<Notification>()
		val updater = GameSessionNotificationUpdater(
			clock = clock,
			factory = factory,
			postNotification = posted::add,
		)

		updater.update(GameSessionStateUpdate(activeState(false), isCommandTransition = true)) shouldBe true
		updater.update(GameSessionStateUpdate(activeState(false), isCommandTransition = false)) shouldBe false
		clock.elapsedMs = 4_999L
		updater.update(GameSessionStateUpdate(activeState(false), isCommandTransition = false)) shouldBe false
		updater.update(GameSessionStateUpdate(activeState(true), isCommandTransition = true)) shouldBe true
		clock.elapsedMs = 5_000L
		updater.update(GameSessionStateUpdate(activeState(true), isCommandTransition = false)) shouldBe true
		posted.shouldHaveSize(3)
	}

	private fun activeState(paused: Boolean): GameSessionState {
		val snapshot = MiniGameSnapshot(
			phase = if (paused) MiniGamePhase.PAUSED else MiniGamePhase.ACTIVE,
			signal = MiniGameSignal.UNKNOWN,
			elapsedActiveTimeMs = 10_000L,
			goalProgress = MiniGameGoalProgress.Tracked(2.0, 5.0),
			personalBest = MiniGamePersonalBest.compare(2.0, 3.0),
			latestFeedback = null,
			visualPayload = MiniGameVisualPayload.Pending,
		)
		return if (paused) {
			GameSessionState.Paused(
				GameSessionId("session-1"),
				MiniGameConfigurations.DEFAULT_TERRITORY,
				snapshot,
			)
		} else {
			GameSessionState.Active(
				GameSessionId("session-1"),
				MiniGameConfigurations.DEFAULT_TERRITORY,
				snapshot,
			)
		}
	}

	private class MutableClock : GameSessionClock {
		var elapsedMs = 0L
		override fun elapsedRealtimeMs(): Long = elapsedMs
		override fun currentTimeMillis(): Long = 0L
	}
}
