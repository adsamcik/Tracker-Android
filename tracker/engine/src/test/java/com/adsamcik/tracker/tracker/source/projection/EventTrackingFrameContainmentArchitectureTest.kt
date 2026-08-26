package com.adsamcik.tracker.tracker.source.projection

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class EventTrackingFrameContainmentArchitectureTest {
	private val projectRoot: File by lazy {
		generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
			.first { it.resolve("settings.gradle.kts").isFile }
	}

	@Test
	fun `production has no live generic event frame consumer or dispatcher`() {
		val forbiddenSymbols = listOf(
			"EventTrackingFrameConsumer",
			"EventTrackingFrameOutboxDispatcher",
		)
		val references = projectRoot.resolve("tracker/engine/src/main").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.flatMap { source ->
				val text = source.readText()
				forbiddenSymbols.asSequence()
					.filter(text::contains)
					.map { symbol ->
						"${source.relativeTo(projectRoot).invariantSeparatorsPath}:$symbol"
					}
			}
			.toList()

		assertEquals(emptyList(), references)
		val recovery = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/" +
				"SourcePipelineRecovery.kt",
		).readText()
		assertFalse("EventTrackingFrame" in recovery)
		assertTrue("trackingFramesDelivered = 0" in recovery)
	}

	@Test
	fun `only activity automation is bound as a live production projection`() {
		val module = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/di/SourcePipelineModule.kt",
		).readText()
		val projectionProviders = Regex("""fun (provide[A-Za-z0-9]+Projection)\(""")
			.findAll(module)
			.map { it.groupValues[1] }
			.toList()

		assertEquals(listOf("provideActivityAutomationProjection"), projectionProviders)
		assertFalse("provideEventTrackingFrameProjection" in module)
		assertFalse("provideLocationDomainProjection" in module)
		assertFalse("provideExplicitTrackingJoinProjection" in module)
		assertFalse("provideConsumerMigrationRegistry" in module)
	}

	@Test
	fun `dormant v2 conversion and frozen v27 bridge remain available for released data`() {
		val projection = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/projection/" +
				"EventTrackingFrameProjection.kt",
		).readText()
		val legacyRecovery = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/projection/legacy/" +
				"LegacyV27ProjectionRecovery.kt",
		).readText()
		val legacyBridge = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/projection/legacy/" +
				"LegacyV27EventFrameBridge.kt",
		).readText()

		assertTrue("const val VERSION = 2" in projection)
		assertTrue("context.recordOutbox(" in projection)
		assertTrue("bridgeEventFrames(" in legacyRecovery)
		assertTrue("Frozen typed-destination bridge" in legacyBridge)
	}

	@Test
	fun `shadow installation is inert and obsolete shadow reachability is not public`() {
		val coordinatorRoot = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator",
		)
		val rolloutState = coordinatorRoot.resolve("TrackingRolloutState.kt").readText()
		val rolloutStore = coordinatorRoot.resolve("TrackingRolloutStateStore.kt").readText()

		assertTrue("\n\t\tfun eventCanonical(" in rolloutState)
		assertFalse("eventShadow" in rolloutState)
		assertTrue("\n\tsuspend fun installInertShadowLane(" in rolloutStore)
		assertFalse("installAndActivateShadowLane" in rolloutStore)
		assertFalse("rearmAndActivateShadowLane" in rolloutStore)

		val obsoleteProductionCalls = projectRoot.resolve("tracker/engine/src/main").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.filterNot { it.name in setOf("TrackingRolloutState.kt", "TrackingRolloutStateStore.kt") }
			.flatMap { source ->
				val text = source.readText()
				sequenceOf(
					"eventShadow",
					"installAndActivateShadowLane",
					"rearmAndActivateShadowLane",
				)
					.filter(text::contains)
					.map { symbol ->
						"${source.relativeTo(projectRoot).invariantSeparatorsPath}:$symbol"
					}
			}
			.toList()

		assertEquals(emptyList(), obsoleteProductionCalls)
	}
}
