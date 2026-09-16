package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class PersistenceLegacySourceWriterTransitionBoundary @Inject constructor(
	private val persistenceProcessor: PersistenceProcessor,
	private val persistenceLifecycleLease: ExclusiveTrackingPersistenceLifecycleLease,
) : LegacySourceWriterTransitionBoundary {
	override suspend fun <T : Any> runIfQuiescent(
		source: SourceKind,
		operation: suspend () -> T,
	): T? {
		if (source !in SUPPORTED_SOURCES) return null
		return persistenceLifecycleLease.withLegacySourceWriterTransition {
			if (persistenceProcessor.hasUnrecoverablePersistenceStateForLifecycleFence() ||
				!persistenceProcessor.drainOrphanedSignals() ||
				persistenceProcessor.hasUnsettledPersistenceStateForPressureFence()
			) {
				null
			} else {
				operation()
			}
		}
	}

	private companion object {
		val SUPPORTED_SOURCES = setOf(SourceKind.ACTIVITY, SourceKind.PRESSURE)
	}
}
