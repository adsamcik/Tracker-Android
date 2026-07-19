package com.adsamcik.tracker.game.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultGameSessionControllerTest {
	private lateinit var application: Application
	private lateinit var stateStore: GameSessionStateStore
	private lateinit var controller: DefaultGameSessionController

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		stateStore = GameSessionStateStore()
		controller = DefaultGameSessionController(application, stateStore)
	}

	@Test
	fun `start publishes Starting and starts foreground service with primitive extras`() {
		controller.start(MiniGameConfigurations.DEFAULT_OUTRUN)

		controller.state.value.shouldBeInstanceOf<GameSessionState.Starting>()
		val intent = shadowOf(application).nextStartedService
		intent.component?.className shouldBe GameSessionService::class.java.name
		intent.action shouldBe GameSessionIntentContract.ACTION_START
		intent.getStringExtra(GameSessionIntentContract.EXTRA_GAME_ID) shouldBe "outrun"
		intent.hasExtra(GameSessionIntentContract.EXTRA_GOAL_VALUE) shouldBe true
	}

	@Test
	fun `commands use explicit service intents and runtime alone changes active state`() {
		controller.pause()
		controller.resume()
		controller.finish()

		val shadow = shadowOf(application)
		val actions = buildList {
			repeat(3) { add(shadow.nextStartedService.action) }
		}
		actions shouldBe listOf(
			GameSessionIntentContract.ACTION_PAUSE,
			GameSessionIntentContract.ACTION_RESUME,
			GameSessionIntentContract.ACTION_FINISH,
		)
		controller.state.value shouldBe GameSessionState.Idle
	}

	@Test
	fun `finished state is retained until the next explicit start`() {
		val finished = GameSessionState.Finished(
			GameSessionResult(
				sessionId = GameSessionId("session-1"),
				configuration = MiniGameConfigurations.DEFAULT_TERRITORY,
				finalScore = 3.0,
				pointsAwarded = 50,
				completedAtMs = 100L,
				completionOutcome = com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome.FirstRun,
			),
		)
		stateStore.publish(finished)
		controller.pause()
		controller.state.value shouldBe finished

		controller.start(MiniGameConfigurations.DEFAULT_TERRITORY)
		controller.state.value.shouldBeInstanceOf<GameSessionState.Starting>()
	}

	@Test
	fun `controller state never contains raw location coordinates`() {
		controller.start(MiniGameConfigurations.DEFAULT_TERRITORY)

		controller.state.value.toString() shouldNotContain "50.0876"
		controller.state.value.toString() shouldNotContain "14.4213"
		GameSessionState.Active::class.java.declaredFields.map { it.name }.none {
			it.contains("latitude", ignoreCase = true) || it.contains("longitude", ignoreCase = true)
		} shouldBe true
	}
}
