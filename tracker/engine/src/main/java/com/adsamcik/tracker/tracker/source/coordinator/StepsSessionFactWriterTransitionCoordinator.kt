package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the source-specific one-writer transition for the Steps session-fact destination.
 *
 * Initial activation remains an explicit release action. Full deletion may only reconstruct a
 * writer generation that was already canonical; it never performs the first cutover.
 */
@Singleton
class StepsSessionFactWriterTransitionCoordinator @Inject internal constructor(
	private val engine: StepsWriterTransitionEngine,
) {
	internal constructor(
		database: AppDatabase,
		executableLaneCatalog: ExecutableSourceLaneCatalog,
		dependencies: StepsWriterTransitionTestDependencies,
	) : this(
		StepsWriterTransitionEngine.forTest(database, executableLaneCatalog, dependencies),
	)

	/** Promotes the verified Steps shadow lane to the canonical destination writer. */
	suspend fun activateCandidate(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult = engine.activateCandidate(expectedRolloutRevision, updatedAtMs)

	/** Fences candidate capture and begins the drain required before rollback. */
	suspend fun beginCandidateRollback(
		expectedRolloutRevision: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult = engine.beginCandidateRollback(
		expectedRolloutRevision,
		updatedAtMs,
	)

	/** Retires a drained candidate lane and restores legacy destination ownership. */
	suspend fun completeCandidateRollback(
		expectedContainedRolloutRevision: Long,
		expectedCutoffOrdinal: Long,
		updatedAtMs: Long,
	): StepsWriterTransitionResult = engine.completeCandidateRollback(
		expectedContainedRolloutRevision,
		expectedCutoffOrdinal,
		updatedAtMs,
	)

	/**
	 * Reconstructs an empty writer generation after all collected rows were cleared.
	 *
	 * The deletion service calls this while its durable marker and startup barrier remain closed.
	 */
	suspend fun rearmAfterFullDeletion(updatedAtMs: Long) {
		require(updatedAtMs >= 0L)
		try {
			engine.rearmAfterFullDeletion(updatedAtMs)
		} catch (blocked: StepsWriterTransitionBlockedException) {
			throw IllegalStateException(
				"Steps writer deletion re-arm blocked: ${blocked.blocker}",
				blocked,
			)
		}
	}
}

internal data class StepsWriterTransitionTestDependencies(
	val startupGate: TrackingStartupGate,
	val bootClockDomainProvider: BootClockDomainProvider = BootClockDomainProvider { "test-boot" },
	val clock: Clock = FixedStepsWriterTransitionClock,
	val legacyStepsWriterBoundary: LegacyStepsWriterTransitionBoundary =
		LegacyStepsWriterTransitionBoundary.ALWAYS_QUIESCENT,
	val requestStepsDrain: () -> Unit = {},
	val faultInjector: StepsWriterTransitionFaultInjector = StepsWriterTransitionFaultInjector.NONE,
)
