package com.adsamcik.tracker.tracker.source.pressure

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PressureLifecycleGraphArchitectureTest {
	private val projectRoot: File by lazy {
		generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
			.first { it.resolve("settings.gradle.kts").isFile }
	}

	@Test
	fun `Pressure deletion module uniquely binds the runtime and persistence barriers`() {
		val mainRoot = projectRoot.resolve("tracker/engine/src/main")
		val productionSources = mainRoot.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.map { it.readText() }
			.toList()
		val module = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/deletion/" +
				"PressureDeletionModule.kt",
		).readText()
		val sourcePipeline = projectRoot.resolve(
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/di/SourcePipelineModule.kt",
		).readText()

		assertTrue("@InstallIn(SingletonComponent::class)" in module)
		assertTrue("implementation: RuntimePressureSourceEraseBarrier" in module)
		assertTrue("): PressureSourceEraseBarrier" in module)
		assertTrue("implementation: PersistenceLegacyPressureWriterLifecycleBarrier" in module)
		assertTrue("): LegacyPressureWriterLifecycleBarrier" in module)
		assertEquals(
			1,
			productionSources.sumOf {
				Regex("""\): PressureSourceEraseBarrier\b""").findAll(it).count()
			},
		)
		assertEquals(
			1,
			productionSources.sumOf {
				Regex("""\): LegacyPressureWriterLifecycleBarrier\b""").findAll(it).count()
			},
		)
		assertFalse("PressureSourceEraseBarrier" in sourcePipeline)
		assertFalse("LegacyPressureWriterLifecycleBarrier" in sourcePipeline)
	}

	@Test
	fun `bound lifecycle implementations and shared dependencies remain singleton scoped`() {
		val runtime = source("source/pressure/RuntimePressureSourceEraseBarrier.kt")
		val persistenceBarrier =
			source("source/pressure/PersistenceLegacyPressureWriterLifecycleBarrier.kt")
		val persistence = source("pipeline/persistence/PersistenceProcessor.kt")
		val lease = source("pipeline/persistence/TrackingPersistenceLifecycleLease.kt")

		assertTrue(
			Regex("""@Singleton\s+internal class RuntimePressureSourceEraseBarrier""")
				.containsMatchIn(runtime),
		)
		assertTrue(
			Regex(
				"""@Singleton\s+internal class PersistenceLegacyPressureWriterLifecycleBarrier""",
			).containsMatchIn(persistenceBarrier),
		)
		assertTrue("private val persistenceProcessor: PersistenceProcessor" in persistenceBarrier)
		assertTrue(
			"private val persistenceLifecycleLease: ExclusiveTrackingPersistenceLifecycleLease" in
				persistenceBarrier,
		)
		assertTrue(Regex("""@Singleton\s+class PersistenceProcessor""").containsMatchIn(persistence))
		assertTrue(
			Regex("""@Singleton\s+internal class ExclusiveTrackingPersistenceLifecycleLease""")
				.containsMatchIn(lease),
		)
	}

	private fun source(relativePath: String): String = projectRoot.resolve(
		"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/$relativePath",
	).readText()
}
