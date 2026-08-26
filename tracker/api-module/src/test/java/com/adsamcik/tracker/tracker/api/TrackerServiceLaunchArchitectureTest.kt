package com.adsamcik.tracker.tracker.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.junit.jupiter.api.Test

class TrackerServiceLaunchArchitectureTest {
	private val projectRoot by lazy(::findProjectRoot)

	@Test
	fun `TrackerService has exactly one Android foreground-service launch owner`() {
		val launchOwners = listOf(
			projectRoot.resolve("tracker/api-module/src/main"),
			projectRoot.resolve("tracker/engine/src/main"),
		).flatMap { root ->
			root.walkTopDown()
				.filter { it.isFile && it.extension == "kt" }
				.filter { "ContextCompat.startForegroundService(" in it.readText() }
				.map { it.relativeTo(projectRoot).invariantSeparatorsPath }
				.toList()
		}

		launchOwners shouldBe listOf(
			"tracker/api-module/src/main/java/com/adsamcik/tracker/tracker/api/TrackerServiceApi.kt",
		)
	}

	@Test
	fun `all queued entry points reserve off caller thread and use private prepared delivery`() {
		val api = projectRoot.resolve(
			"tracker/api-module/src/main/java/com/adsamcik/tracker/tracker/api/TrackerServiceApi.kt",
		).readText()
		val service = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/service/TrackerService.kt",
		).readText()
		val authority = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/resilience/" +
				"SharedPreferencesTrackingLifecycleCommandAuthority.kt",
		).readText()

		api shouldContain Regex(
			"entryPoint\\.applicationScope\\(\\)\\.launch\\s*\\{\\s*" +
				"val command = entryPoint\\.trackingLifecycleCommandAuthority\\(\\)\\.reserveStart",
		)
		api shouldContain Regex(
			"entryPoint\\.applicationScope\\(\\)\\.launch\\s*\\{\\s*" +
				"val command = entryPoint\\.trackingLifecycleCommandAuthority\\(\\)\\.reserveStop",
		)
		api shouldContain "suspend fun startServiceAndAwaitEnqueue("
		api shouldContain "enqueuePreparedStart(appContext, prepared, preparedCommand)"
		api shouldContain "createPreparedStartIntent(context.applicationContext, prepared, command.generation)"
		api shouldContain "ARG_PREPARED_SOURCE_MASK_HINT"
		api shouldNotContain "createStartIntent("
		api shouldNotContain "createRestartIntent("
		service shouldContain "intent?.preparedTrackingStartTokenOrNull()"
		service shouldContain "intent.preparedForegroundHintOrNull()"
		service shouldNotContain "toAutomaticTrackingStartTrigger()"
		service shouldContain Regex(
			"finally\\s*\\{\\s*rollbackRejectedPreparedStartRuntime\\(\\)\\s*" +
				"startSingleFlight\\.set\\(false\\)",
		)
		service shouldContain Regex(
			"private fun rollbackRejectedPreparedStartRuntime\\(\\).*?" +
				"this\\.sessionInfo = null.*?activeSessionDescriptor = null.*?" +
				"sessionStartOrigin = null.*?foregroundStarted = false",
			RegexOption.DOT_MATCHES_ALL,
		)
		authority shouldContain "private val commandGate = Mutex()"
		authority shouldContain "withContext(NonCancellable + ioDispatcher)"
		authority shouldNotContain "java.util.concurrent.Semaphore"
	}

	@Test
	fun `manual user entry points use the typed acknowledged start boundary`() {
		val entryPoints = listOf(
			"feature/dashboard/src/main/java/com/adsamcik/tracker/dashboard/ui/compose/DashboardRoute.kt",
			"feature/tracker/src/main/java/com/adsamcik/tracker/tracker/ui/compose/TrackerRoute.kt",
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/shortcut/ShortcutActivity.kt",
			"app/src/main/java/com/adsamcik/tracker/app/widget/glance/WidgetActions.kt",
		)
		entryPoints.forEach { relativePath ->
			val source = projectRoot.resolve(relativePath).readText()
			source shouldContain "TrackerServiceApi.requestManualTrackingStart("
			source shouldNotContain "TrackerServiceApi.startService("
			source shouldNotContain "TrackerServiceApi.startServiceAndAwaitEnqueue("
		}
		entryPoints.take(2).forEach { relativePath ->
			val source = projectRoot.resolve(relativePath).readText()
			source shouldContain "onRequestPermission = { requestManualStart() }"
			source shouldContain "ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION"
			source shouldContain "ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION"
			source shouldContain "ManualTrackingStartPrerequisite.LOCATION_SERVICES"
			source shouldContain "ManualTrackingStartRepairNavigation"
			source shouldContain ".openLocationServicesSettings(context)"
		}
		entryPoints.takeLast(2).forEach { relativePath ->
			projectRoot.resolve(relativePath).readText() shouldContain
				"ManualTrackingStartRepairNavigation.createDashboardIntent("
		}
		projectRoot.resolve(entryPoints.first()).readText() shouldContain
			"ManualTrackingStartRepairNavigation.consumeDashboardReevaluationRequest(intent)"
		val navigation = projectRoot.resolve(
			"tracker/api-module/src/main/java/com/adsamcik/tracker/tracker/api/ManualTrackingStart.kt",
		).readText()
		navigation shouldContain "putExtra(EXTRA_REEVALUATE_MANUAL_START, true)"
		navigation shouldNotContain "putExtra(EXTRA_REEVALUATE_MANUAL_START, prerequisite.ordinal)"
		navigation shouldContain "Settings.ACTION_LOCATION_SOURCE_SETTINGS"
		navigation shouldContain "catch (_: ActivityNotFoundException)"
	}

	@Test
	fun `specialUse subtype states the bounded user-visible signal purpose`() {
		val manifest = projectRoot.resolve("tracker/engine/src/main/AndroidManifest.xml").readText()

		manifest shouldContain "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
		manifest shouldContain
			"User-visible tracking session collection of pressure, Wi-Fi scan, and cell-radio context"
		manifest shouldNotContain "android:value=\"SignalTracking\""
	}

	private fun findProjectRoot(): File {
		var candidate = File(System.getProperty("user.dir") ?: ".").absoluteFile
		while (!candidate.resolve("settings.gradle.kts").isFile) {
			candidate = candidate.parentFile ?: error("Unable to locate project root")
		}
		return candidate
	}
}
