package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceProjectionStateDao
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider

internal class StepsWriterTransitionBoundary private constructor(
	private val database: AppDatabase,
	private val startupGateProvider: Provider<TrackingStartupGate>,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val clock: Clock,
	private val legacyStepsWriterBoundary: LegacyStepsWriterTransitionBoundary,
) {
	@Inject
	constructor(
		database: AppDatabase,
		startupGateProvider: Provider<TrackingStartupGate>,
		bootClockDomainProvider: BootClockDomainProvider,
		clock: Clock,
		persistenceProcessorProvider: Provider<PersistenceProcessor>,
	) : this(
		database,
		startupGateProvider,
		bootClockDomainProvider,
		clock,
		PersistenceLegacyStepsWriterTransitionBoundary(persistenceProcessorProvider),
	)

	suspend fun run(
		phase: StepsWriterTransitionPhase,
		operation: suspend (LifecycleLeaseToken) -> StepsWriterTransitionResult,
	): StepsWriterTransitionResult {
		val startupGate = startupGateProvider.get()
		if (startupGate.reconcile() !is TrackingStartupResult.Ready) {
			return StepsWriterTransitionResult.StartupUnavailable(phase)
		}
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationOperation(startupGeneration) {
			withLease(phase, operation)
		} ?: StepsWriterTransitionResult.StartupUnavailable(phase)
	}

	suspend fun runIfLegacyWriterQuiescent(
		operation: suspend () -> StepsWriterTransitionResult,
	): StepsWriterTransitionResult? = legacyStepsWriterBoundary.runIfQuiescent(operation)

	suspend fun requireLease(lease: LifecycleLeaseToken) {
		if (!leaseIsCurrent(lease)) {
			blocked(StepsWriterTransitionBlocker.SESSION_LEASE_LOST)
		}
	}

	private suspend fun withLease(
		phase: StepsWriterTransitionPhase,
		operation: suspend (LifecycleLeaseToken) -> StepsWriterTransitionResult,
	): StepsWriterTransitionResult {
		val ownerToken = "steps-writer-transition:${UUID.randomUUID()}"
		val lease = acquireLease(ownerToken) ?: return StepsWriterTransitionResult.Busy(phase)
		return try {
			try {
				operation(lease)
			} catch (blocked: StepsWriterTransitionBlockedException) {
				StepsWriterTransitionResult.Blocked(phase, blocked.blocker)
			}
		} finally {
			releaseLease(lease)
		}
	}

	private suspend fun acquireLease(ownerToken: String): LifecycleLeaseToken? =
		database.withTransaction {
			val clockSample = validClockSample() ?: return@withTransaction null
			val dao = database.sourceProjectionStateDao()
			val inserted = dao.insertLeaseIfAbsent(clockSample.newLease(ownerToken))
			if (inserted < 0L && !renewLease(dao, ownerToken, clockSample)) {
				return@withTransaction null
			}
			val current = dao.lease(StepsWriterDestination.SESSION_LEASE)
				?: return@withTransaction null
			if (current.ownerToken != ownerToken || current.bootId != clockSample.bootId) {
				return@withTransaction null
			}
			LifecycleLeaseToken(
				StepsWriterDestination.SESSION_LEASE,
				ownerToken,
				clockSample.bootId,
				current.generation,
			)
		}

	private suspend fun renewLease(
		dao: SourceProjectionStateDao,
		ownerToken: String,
		clockSample: StepsWriterLeaseClockSample,
	): Boolean = dao.acquireOrRenewLease(
		leaseName = StepsWriterDestination.SESSION_LEASE,
		ownerToken = ownerToken,
		bootId = clockSample.bootId,
		nowMs = clockSample.nowMs,
		expiresAtMs = clockSample.expiresAtMs,
		nowElapsedNanos = clockSample.nowElapsedNanos,
		expiresElapsedNanos = clockSample.expiresElapsedNanos,
	) == 1

	private fun validClockSample(): StepsWriterLeaseClockSample? {
		val bootId = bootClockDomainProvider.current()
		val nowElapsedNanos = clock.elapsedRealtimeNanos()
		val nowMs = clock.currentTimeMillis()
		if (bootId.isBlank() || nowElapsedNanos < 0L || nowMs < 0L) {
			return null
		}
		if (nowElapsedNanos > Long.MAX_VALUE - LEASE_DURATION_NANOS ||
			nowMs > Long.MAX_VALUE - LEASE_DURATION_MILLIS
		) {
			return null
		}
		return StepsWriterLeaseClockSample(
			bootId,
			nowElapsedNanos,
			nowMs,
			nowElapsedNanos + LEASE_DURATION_NANOS,
			nowMs + LEASE_DURATION_MILLIS,
		)
	}

	private suspend fun leaseIsCurrent(lease: LifecycleLeaseToken): Boolean {
		val current = database.sourceProjectionStateDao().lease(lease.leaseName) ?: return false
		val currentBootId = bootClockDomainProvider.current()
		val nowElapsedNanos = clock.elapsedRealtimeNanos()
		if (currentBootId.isBlank() || nowElapsedNanos < 0L || lease.bootId != currentBootId) {
			return false
		}
		return current.ownerToken == lease.ownerToken &&
			current.bootId == lease.bootId &&
			current.generation == lease.generation &&
			current.expiresElapsedRealtimeNanos > nowElapsedNanos
	}

	private suspend fun releaseLease(lease: LifecycleLeaseToken) {
		database.sourceProjectionStateDao().releaseLease(
			leaseName = lease.leaseName,
			ownerToken = lease.ownerToken,
			bootId = lease.bootId,
			generation = lease.generation,
			nowMs = clock.currentTimeMillis().coerceAtLeast(0L),
			nowElapsedNanos = clock.elapsedRealtimeNanos(),
		)
	}

	companion object {
		private const val LEASE_DURATION_NANOS = 30_000_000_000L
		private const val LEASE_DURATION_MILLIS = 30_000L

		fun forTest(
			database: AppDatabase,
			dependencies: StepsWriterTransitionTestDependencies,
		): StepsWriterTransitionBoundary = StepsWriterTransitionBoundary(
			database,
			Provider { dependencies.startupGate },
			dependencies.bootClockDomainProvider,
			dependencies.clock,
			dependencies.legacyStepsWriterBoundary,
		)
	}
}

private data class StepsWriterLeaseClockSample(
	val bootId: String,
	val nowElapsedNanos: Long,
	val nowMs: Long,
	val expiresElapsedNanos: Long,
	val expiresAtMs: Long,
) {
	fun newLease(ownerToken: String) = SourceCoordinatorLeaseEntity(
		leaseName = StepsWriterDestination.SESSION_LEASE,
		ownerToken = ownerToken,
		acquiredAtMs = nowMs,
		expiresAtMs = expiresAtMs,
		bootId = bootId,
		generation = 1L,
		acquiredElapsedRealtimeNanos = nowElapsedNanos,
		expiresElapsedRealtimeNanos = expiresElapsedNanos,
	)
}

internal interface LegacyStepsWriterTransitionBoundary {
	suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T?

	companion object {
		val ALWAYS_QUIESCENT = object : LegacyStepsWriterTransitionBoundary {
			override suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T = operation()
		}
	}
}

private class PersistenceLegacyStepsWriterTransitionBoundary(
	private val persistenceProcessorProvider: Provider<PersistenceProcessor>,
) : LegacyStepsWriterTransitionBoundary {
	override suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T? =
		persistenceProcessorProvider.get().withLegacyStepsWriterQuiesced(operation)
}
