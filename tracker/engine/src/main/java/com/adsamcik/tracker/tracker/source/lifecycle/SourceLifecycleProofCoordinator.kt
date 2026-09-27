package com.adsamcik.tracker.tracker.source.lifecycle

import androidx.room.InvalidationTracker
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.TRACKING_HISTORY_INVALIDATION_TABLES
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.QueryableProofState
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupRequest
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupResult
import com.adsamcik.tracker.stats.api.repository.SourceFactQueryRepository
import com.adsamcik.tracker.stats.api.repository.SourceLifecycleProof
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.SourceProofStage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class SourceLifecycleProofSnapshot(
	val proofs: Map<TrackingSource, SourceLifecycleProof>,
) {
	init {
		require(proofs.keys == TrackingSource.entries.toSet())
	}
}

/**
 * App-scoped read-only proof observer.
 *
 * Room invalidation is only a conflated wake-up signal. One six-source reconstruction runs to
 * completion while at most the latest pending invalidation remains queued. Every emission is
 * reconstructed from durable state; there is no polling, timer, provider demand, repair, cursor
 * advance, or write.
 */
@Singleton
class SourceLifecycleProofCoordinator internal constructor(
	private val invalidations: Flow<Unit>,
	descriptors: Set<SourceLifecycleProofDescriptor>,
	private val queryRepository: SourceFactQueryRepository,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
	@Inject
	internal constructor(
		database: AppDatabase,
		catalog: SourceLifecycleProofCatalog,
		queryRepository: SourceFactQueryRepository,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		invalidations = database.lifecycleInvalidations(),
		descriptors = catalog.descriptors,
		queryRepository = queryRepository,
		ioDispatcher = ioDispatcher,
	)

	private val descriptorsBySource = descriptors.associateBy(SourceLifecycleProofDescriptor::source)
		.also { bySource ->
			require(bySource.keys == TrackingSource.entries.toSet())
		}

	fun observe(keys: Set<SourceProofKey>): Flow<SourceLifecycleProofSnapshot> {
		requireCompleteKeySet(keys)
		return invalidations.conflate()
			.map {
				snapshot(keys)
			}
			.flowOn(ioDispatcher)
	}

	internal suspend fun snapshot(keys: Set<SourceProofKey>): SourceLifecycleProofSnapshot {
		val keysBySource = requireCompleteKeySet(keys)
		return SourceLifecycleProofSnapshot(
			TrackingSource.entries.associateWith { source ->
				evaluateIsolated(keysBySource.getValue(source))
			},
		)
	}

	private fun requireCompleteKeySet(
		keys: Set<SourceProofKey>,
	): Map<TrackingSource, SourceProofKey> {
		val keysBySource = keys.associateBy(SourceProofKey::source)
		require(keys.size == TrackingSource.entries.size)
		require(keysBySource.keys == TrackingSource.entries.toSet())
		return keysBySource
	}

	private suspend fun evaluateIsolated(key: SourceProofKey): SourceLifecycleProof {
		val adapter = try {
			descriptorsBySource.getValue(key.source).adapterFactory.create()
		} catch (failure: Exception) {
			if (failure is CancellationException) throw failure
			return SourceLifecycleProof(
				key = key,
				recording = failed(SourceProofStage.RECORDING),
				materialized = failed(SourceProofStage.MATERIALIZED),
				queryable = failed(SourceProofStage.QUERYABLE),
			)
		}
		val recording = try {
			adapter.recording(key)
		} catch (failure: Exception) {
			if (failure is CancellationException) throw failure
			failed(SourceProofStage.RECORDING)
		}
		if (recording !is SourceProofResult.Proven) {
			return SourceLifecycleProof(
				key,
				recording,
				recording.propagate(SourceProofStage.MATERIALIZED),
				recording.propagate(SourceProofStage.QUERYABLE),
			)
		}

		val materialized = try {
			adapter.materialized(key, recording.state)
		} catch (failure: Exception) {
			if (failure is CancellationException) throw failure
			failed(SourceProofStage.MATERIALIZED, recording.state.lineage)
		}
		if (materialized !is SourceProofResult.Proven) {
			return SourceLifecycleProof(
				key,
				recording,
				materialized,
				materialized.propagate(SourceProofStage.QUERYABLE),
			)
		}

		val queryable = try {
			queryRepository.lookup(
				SourceFactLookupRequest(key, materialized.state),
			).toProofResult(materialized.state)
		} catch (failure: Exception) {
			if (failure is CancellationException) throw failure
			failed(SourceProofStage.QUERYABLE, materialized.state.lineage)
		}
		return SourceLifecycleProof(key, recording, materialized, queryable)
	}

	private fun SourceFactLookupResult.toProofResult(
		materialized: MaterializedProofState,
	): SourceProofResult<QueryableProofState> = when (this) {
		is SourceFactLookupResult.Found -> SourceProofResult.Proven(state)
		is SourceFactLookupResult.NotFound -> SourceProofResult.Pending(
			SourceProofStage.QUERYABLE,
			reason,
			materialized.lineage,
		)
		is SourceFactLookupResult.Deleted -> SourceProofResult.Deleted(
			SourceProofStage.QUERYABLE,
			reason,
			materialized.lineage,
		)
		is SourceFactLookupResult.Unavailable -> SourceProofResult.Unavailable(
			SourceProofStage.QUERYABLE,
			reason,
			materialized.lineage,
		)
		is SourceFactLookupResult.Failed -> SourceProofResult.Failed(
			SourceProofStage.QUERYABLE,
			reason,
			materialized.lineage,
		)
	}

	private fun SourceProofResult<*>.propagate(
		stage: SourceProofStage,
	): SourceProofResult<Nothing> = when (this) {
		is SourceProofResult.Proven -> error("A proven stage must be evaluated, not propagated")
		is SourceProofResult.Pending -> SourceProofResult.Pending(stage, reason, lineage)
		is SourceProofResult.Unavailable -> SourceProofResult.Unavailable(stage, reason, lineage)
		is SourceProofResult.Failed -> SourceProofResult.Failed(stage, reason, lineage)
		is SourceProofResult.Deleted -> SourceProofResult.Deleted(stage, reason, lineage)
	}

	private fun failed(
		stage: SourceProofStage,
		lineage: com.adsamcik.tracker.stats.api.repository.SourceProofLineage? = null,
	) = SourceProofResult.Failed(
		stage,
		SourceProofReason.REPOSITORY_FAILURE,
		lineage,
	)

}

internal fun AppDatabase.lifecycleInvalidations(): Flow<Unit> = callbackFlow {
	val observer = object : InvalidationTracker.Observer(*SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES) {
		override fun onInvalidated(tables: Set<String>) {
			trySend(Unit)
		}
	}
	invalidationTracker.addObserver(observer)
	trySend(Unit)
	awaitClose {
		invalidationTracker.removeObserver(observer)
	}
}

internal val SOURCE_LIFECYCLE_PROOF_SPECIFIC_TABLES = listOf(
	"source_projection_join_state",
	"source_event_retention_proof",
	"location_observation",
	"location_observation_decision",
	"location_sample",
)

internal val SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES =
	(TRACKING_HISTORY_INVALIDATION_TABLES + SOURCE_LIFECYCLE_PROOF_SPECIFIC_TABLES)
		.distinct()
		.toTypedArray()
