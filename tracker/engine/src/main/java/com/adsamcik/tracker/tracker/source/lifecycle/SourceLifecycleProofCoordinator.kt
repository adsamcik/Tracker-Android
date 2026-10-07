package com.adsamcik.tracker.tracker.source.lifecycle

import androidx.room.InvalidationTracker
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.TRACKING_HISTORY_INVALIDATION_TABLES
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusQuery
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRead
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRequest
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class SourceLifecycleProofSnapshot(
	val proofs: Map<TrackingSource, SourceLifecycleProof>,
) {
	init {
		require(proofs.all { (source, proof) -> proof.key.source == source })
	}
}

/** Qualified saved all-day data uses source-specific identities; it has no invented WAL stages. */
data class AllDaySourceStatusSnapshot(val sources: Map<TrackingSource, AllDaySourceStatusRead>)

/**
 * App-scoped read-only proof observer.
 *
 * Room invalidation is only a conflated wake-up signal. One requested-source reconstruction runs to
 * completion while at most the latest pending invalidation remains queued. Every emission is
 * reconstructed from durable state; there is no polling, timer, provider demand, repair, cursor
 * advance, or write.
 *
 * [observe] diagnoses an exact original acquisition/materialization chain. Its transient WAL and
 * registration dependencies may no longer exist even when the saved product remains valid. A
 * missing stage must not be used to hide that product or recreate its acquisition authority.
 * Retained history and optional map context use their source-owned saved-data readers; sessionless
 * status uses [observeAllDay]. Neither result claims that a provider is currently running.
 */
@Singleton
class SourceLifecycleProofCoordinator internal constructor(
	private val invalidations: Flow<Unit>,
	descriptors: Set<SourceLifecycleProofDescriptor>,
	private val queryRepository: SourceFactQueryRepository,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val allDayQuery: AllDaySourceStatusQuery? = null,
) {
	@Inject
	internal constructor(
		database: AppDatabase,
		catalog: SourceLifecycleProofCatalog,
		queryRepository: SourceFactQueryRepository,
		allDayQuery: AllDaySourceStatusQuery,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		invalidations = database.lifecycleInvalidations(),
		descriptors = catalog.descriptors,
		queryRepository = queryRepository,
		ioDispatcher = ioDispatcher,
		allDayQuery = allDayQuery,
	)

	private val descriptorsBySource = descriptors.associateBy(SourceLifecycleProofDescriptor::source)
		.also { bySource ->
			require(descriptors.size == TrackingSource.entries.size)
			require(bySource.keys == TrackingSource.entries.toSet())
		}

	fun observe(keys: Set<SourceProofKey>): Flow<SourceLifecycleProofSnapshot> {
		requireUniqueSourceKeys(keys)
		if (keys.isEmpty()) return flowOf(SourceLifecycleProofSnapshot(emptyMap()))
		return invalidations.conflate()
			.map {
				snapshot(keys)
			}
			.flowOn(ioDispatcher)
	}

	fun observeAllDay(requests: Set<AllDaySourceStatusRequest>): Flow<AllDaySourceStatusSnapshot> {
		require(requests.size == requests.map(AllDaySourceStatusRequest::source).distinct().size) {
			"Each source must have one bounded all-day request"
		}
		if (requests.isEmpty()) return flowOf(AllDaySourceStatusSnapshot(emptyMap()))
		return invalidations.conflate().map {
			val results = linkedMapOf<TrackingSource, AllDaySourceStatusRead>()
			for (request in requests) {
				results[request.source] = try {
					allDayQuery?.read(request)
						?: AllDaySourceStatusRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					AllDaySourceStatusRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
				}
			}
			AllDaySourceStatusSnapshot(results)
		}.flowOn(ioDispatcher)
	}

	internal suspend fun snapshot(keys: Set<SourceProofKey>): SourceLifecycleProofSnapshot {
		val keysBySource = requireUniqueSourceKeys(keys)
		return SourceLifecycleProofSnapshot(
			TrackingSource.entries.filter { it in keysBySource }.associateWith { source ->
				evaluateIsolated(keysBySource.getValue(source))
			},
		)
	}

	private fun requireUniqueSourceKeys(
		keys: Set<SourceProofKey>,
	): Map<TrackingSource, SourceProofKey> {
		val keysBySource = keys.associateBy(SourceProofKey::source)
		require(keys.size == keysBySource.size) { "Each source must have one exact requested evidence key" }
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
			if (adapter.source != key.source) {
				failed(SourceProofStage.RECORDING, reason = SourceProofReason.WRONG_SOURCE)
			} else {
				adapter.recording(key).let { result ->
					if (result is SourceProofResult.Proven && result.state.lineage.evidenceIdentity != key.evidenceIdentity) {
						failed(SourceProofStage.RECORDING, reason = SourceProofReason.FACT_IDENTITY_MISMATCH)
					} else result
				}
			}
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
			adapter.materialized(key, recording.state).let { result ->
				if (result is SourceProofResult.Proven &&
					(result.state.lineage.evidenceIdentity != key.evidenceIdentity ||
						result.state.lineage.sourceAdmissionOrdinal != recording.state.lineage.sourceAdmissionOrdinal ||
						result.state.lineage.sourceEvidenceRevision < recording.state.lineage.sourceEvidenceRevision)) {
					failed(SourceProofStage.MATERIALIZED, recording.state.lineage, SourceProofReason.FACT_IDENTITY_MISMATCH)
				} else result
			}
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
			).toProofResult(key, materialized.state)
		} catch (failure: Exception) {
			if (failure is CancellationException) throw failure
			failed(SourceProofStage.QUERYABLE, materialized.state.lineage)
		}
		return SourceLifecycleProof(key, recording, materialized, queryable)
	}

	private fun SourceFactLookupResult.toProofResult(
		key: SourceProofKey,
		materialized: MaterializedProofState,
	): SourceProofResult<QueryableProofState> = when (this) {
		is SourceFactLookupResult.Found -> if (state.lineage != materialized.lineage ||
			state.coverage != materialized.coverage || state.completeness != materialized.completeness ||
			state.readSnapshot.collectedDataEpoch != key.collectedDataEpoch) {
			failed(SourceProofStage.QUERYABLE, materialized.lineage, SourceProofReason.FACT_IDENTITY_MISMATCH)
		} else SourceProofResult.Proven(state)
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
		reason: SourceProofReason = SourceProofReason.REPOSITORY_FAILURE,
	) = SourceProofResult.Failed(
		stage,
		reason,
		lineage,
	)

}

internal fun AppDatabase.lifecycleInvalidations(): Flow<Unit> = callbackFlow {
	val observer = object : InvalidationTracker.Observer(SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES) {
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
	"location_sample_purpose_authority",
	"location_accepted_receipt",
	"collected_data_deletion_operation",
	"ambient_location_retention_authority",
	"ambient_steps_import_cursor",
	"ambient_steps_import_gap",
	"ambient_steps_import_authority_transition",
	"ambient_steps_retention_authority",
	"ambient_wifi_authority",
	"ambient_wifi_retention_authority",
	"ambient_wifi_fact_cursor",
	"ambient_cell_authority",
	"ambient_cell_retention_authority",
	"ambient_cell_fact_cursor",
)

internal val SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES =
	(TRACKING_HISTORY_INVALIDATION_TABLES + SOURCE_LIFECYCLE_PROOF_SPECIFIC_TABLES)
		.distinct()
		.toTypedArray()
