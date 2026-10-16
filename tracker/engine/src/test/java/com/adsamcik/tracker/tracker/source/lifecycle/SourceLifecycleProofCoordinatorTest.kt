package com.adsamcik.tracker.tracker.source.lifecycle

import com.adsamcik.tracker.shared.base.database.TRACKING_HISTORY_INVALIDATION_TABLES
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusQuery
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRead
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRequest
import com.adsamcik.tracker.stats.api.repository.AllDayStepsGapStatus
import com.adsamcik.tracker.stats.api.repository.AllDayStepsWindowStatus
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
import com.adsamcik.tracker.stats.api.repository.HistoryObservationWindow
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.QueryableProofState
import com.adsamcik.tracker.stats.api.repository.RecordingProofState
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupRequest
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupResult
import com.adsamcik.tracker.stats.api.repository.SourceFactQueryRepository
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofStage
import com.adsamcik.tracker.stats.api.repository.SourceProofWriterIdentity
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

// One lifecycle proof fixture compares saved-source stages, sibling isolation and invalidation behavior
// consistently.
@Suppress("LargeClass")
class SourceLifecycleProofCoordinatorTest {

	@Test
	fun `empty all-day status neither subscribes nor queries any source`() = runTest {
		val subscriptions = AtomicInteger()
		val counters = CallCounters()
		val reads = AtomicInteger()
		val coordinator = SourceLifecycleProofCoordinator(callbackFlow {
			subscriptions.incrementAndGet(); trySend(Unit); awaitClose {}
		}, successfulDescriptors(counters), successfulQueryRepository(counters), StandardTestDispatcher(testScheduler),
			AllDaySourceStatusQuery { reads.incrementAndGet(); allDayStepsRead() })
		coordinator.observeAllDay(emptySet()).first().sources shouldBe emptyMap()
		subscriptions.get() shouldBe 0
		reads.get() shouldBe 0
		counters.adapterCreations.get() shouldBe 0
		counters.queries.get() shouldBe 0
	}

	@Test
	fun `all-day Steps status uses only the requested real provider window with no WAL stage`() = runTest {
		val counters = CallCounters()
		val seen = mutableListOf<AllDaySourceStatusRequest>()
		val expected = allDayStepsRead()
		val request = allDayStepsRequest()
		val coordinator = SourceLifecycleProofCoordinator(flowOf(Unit), successfulDescriptors(counters),
			successfulQueryRepository(counters), StandardTestDispatcher(testScheduler), AllDaySourceStatusQuery {
				seen += it; expected
			})
		coordinator.observeAllDay(setOf(request)).first().sources shouldBe mapOf(TrackingSource.STEPS to expected)
		seen shouldBe listOf(request)
		expected.windows.single().identity shouldBe "provider-window"
		expected.windows.single().semanticRevision shouldBe 2L
		counters.adapterCreations.get() shouldBe 0
		counters.recording.get() shouldBe 0
		counters.materialized.get() shouldBe 0
		counters.queries.get() shouldBe 0
	}

	@Test
	fun `all-day source failure preserves independently requested saved siblings`() = runTest {
		val counters = CallCounters()
		val seen = mutableListOf<TrackingSource>()
		val steps = allDayStepsRead()
		val cell = AllDaySourceStatusRead.Cell(TrackingHistoryReadSnapshot(7L, 11L), emptyList(), emptyList(), emptySet())
		val coordinator = SourceLifecycleProofCoordinator(flowOf(Unit), successfulDescriptors(counters),
			successfulQueryRepository(counters), StandardTestDispatcher(testScheduler), AllDaySourceStatusQuery {
				seen += it.source
				when (it.source) {
					TrackingSource.WIFI -> error("unavailable retained Wi-Fi dependency")
					TrackingSource.CELL -> cell
					else -> steps
				}
			})
		val requests = linkedSetOf(AllDaySourceStatusRequest.Wifi(HistoryObservationWindow(0L, 2_000L)),
			AllDaySourceStatusRequest.Cell(HistoryObservationWindow(0L, 2_000L)), allDayStepsRequest())
		val result = coordinator.observeAllDay(requests).first().sources
		result shouldBe mapOf(TrackingSource.WIFI to AllDaySourceStatusRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE),
			TrackingSource.CELL to cell, TrackingSource.STEPS to steps)
		seen shouldBe listOf(TrackingSource.WIFI, TrackingSource.CELL, TrackingSource.STEPS)
		counters.adapterCreations.get() shouldBe 0
	}

	@Test
	fun `all-day status rejects two windows for one source`() = runTest {
		val counters = CallCounters()
		val coordinator = SourceLifecycleProofCoordinator(flowOf(Unit), successfulDescriptors(counters),
			successfulQueryRepository(counters), StandardTestDispatcher(testScheduler), AllDaySourceStatusQuery { allDayStepsRead() })
		assertFailsWith<IllegalArgumentException> {
			coordinator.observeAllDay(setOf(AllDaySourceStatusRequest.Wifi(HistoryObservationWindow(0L, 1_000L)),
				AllDaySourceStatusRequest.Wifi(HistoryObservationWindow(1_000L, 2_000L))))
		}
	}

	@Test
	fun `all-day invalidation keeps a complete read and only the latest pending gap update`() = runTest {
		val invalidations = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 64)
		invalidations.tryEmit(Unit)
		val release = CompletableDeferred<Unit>()
		val entered = CompletableDeferred<Unit>()
		var reads = 0
		val counters = CallCounters()
		val coordinator = SourceLifecycleProofCoordinator(invalidations, successfulDescriptors(counters),
			successfulQueryRepository(counters), StandardTestDispatcher(testScheduler), AllDaySourceStatusQuery {
				reads++
				val result = allDayStepsRead(if (reads == 1) {
					emptyList()
				} else
					{
					listOf(AllDayStepsGapStatus(AmbientStepsStructuralDay(0L, "UTC"), 1_000L, 2_000L))
				})
				if (reads == 1) { entered.complete(Unit); release.await() }
				result
			})
		val collection = async { coordinator.observeAllDay(setOf(allDayStepsRequest())).take(2).toList() }
		runCurrent(); entered.await()
		repeat(32) { invalidations.emit(Unit) }
		release.complete(Unit)
		advanceUntilIdle()
		val results = collection.await().map { it.sources.getValue(TrackingSource.STEPS) as AllDaySourceStatusRead.Steps }
		reads shouldBe 2
		results.first().gaps shouldBe emptyList()
		results.last().gaps.single().startTimeMs shouldBe 1_000L
		counters.adapterCreations.get() shouldBe 0
	}

	@Test
	fun `cancelling all-day status drains the requested read and closes its invalidation observer`() = runTest {
		val entered = CompletableDeferred<Unit>()
		val queryCancelled = AtomicInteger()
		val observerClosed = AtomicInteger()
		val counters = CallCounters()
		val coordinator = SourceLifecycleProofCoordinator(callbackFlow {
			trySend(Unit); awaitClose { observerClosed.incrementAndGet() }
		}, successfulDescriptors(counters), successfulQueryRepository(counters), StandardTestDispatcher(testScheduler),
			AllDaySourceStatusQuery {
				entered.complete(Unit)
				try { awaitCancellation() } finally { queryCancelled.incrementAndGet() }
			})
		val collection = launch { coordinator.observeAllDay(setOf(allDayStepsRequest())).collect() }
		runCurrent(); entered.await()
		collection.cancelAndJoin()
		queryCancelled.get() shouldBe 1
		observerClosed.get() shouldBe 1
		counters.adapterCreations.get() shouldBe 0
	}

	@Test
	fun `all-day cursor gap authority retention and accepted Location receipt changes invalidate status`() {
		SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES.toSet().containsAll(setOf(
			"ambient_steps_import_cursor", "ambient_steps_import_gap", "ambient_steps_import_authority_transition",
			"ambient_steps_retention_authority", "ambient_wifi_authority", "ambient_wifi_retention_authority",
			"ambient_wifi_fact_cursor", "ambient_cell_authority", "ambient_cell_retention_authority", "ambient_cell_fact_cursor",
			"location_accepted_receipt", "location_sample_purpose_authority", "ambient_location_retention_authority",
			"collected_data_deletion_operation")) shouldBe true
	}

	private fun allDayStepsRequest() = AllDaySourceStatusRequest.Steps(
		AmbientStepsHistoryRangeRequest(listOf(AmbientStepsStructuralDay(0L, "UTC"))))
	private fun allDayStepsRead(gaps: List<AllDayStepsGapStatus> = emptyList()) = AllDaySourceStatusRead.Steps(
		TrackingHistoryReadSnapshot(7L, 11L), listOf(AllDayStepsWindowStatus("provider-window", 2L,
			AmbientStepsStructuralDay(0L, "UTC"), 0L, 1_000L)), gaps, emptySet(), emptySet())

	@Test
	fun `empty observation emits without subscribing to database invalidation`() = runTest {
		val subscriptions = AtomicInteger()
		val counters = CallCounters()
		val coordinator = SourceLifecycleProofCoordinator(
			invalidations = callbackFlow {
				subscriptions.incrementAndGet()
				trySend(Unit)
				awaitClose {}
			},
			descriptors = successfulDescriptors(counters),
			queryRepository = successfulQueryRepository(counters),
			ioDispatcher = StandardTestDispatcher(testScheduler),
		)

		coordinator.observe(emptySet()).first().proofs shouldBe emptyMap()
		subscriptions.get() shouldBe 0
		counters.adapterCreations.get() shouldBe 0
		counters.queries.get() shouldBe 0
	}

	@Test
	fun `adapter for another source cannot establish recording`() = runTest {
		val counters = CallCounters()
		val key = keys().first()
		val descriptors = successfulDescriptors(counters).mapTo(linkedSetOf()) { descriptor ->
			if (descriptor.source != key.source) {
				descriptor
			} else {
				SourceLifecycleProofDescriptor(
				key.source,
				SourceLifecycleProofAdapterFactory { successfulAdapter(TrackingSource.CELL, counters) },
			)
			}
		}

		val proof = coordinator(descriptors, successfulQueryRepository(counters))
			.snapshot(setOf(key)).proofs.getValue(key.source)

		(proof.recording as SourceProofResult.Failed).reason shouldBe SourceProofReason.WRONG_SOURCE
		(proof.queryable as SourceProofResult.Failed).reason shouldBe SourceProofReason.WRONG_SOURCE
		counters.recording.get() shouldBe 0
		counters.materialized.get() shouldBe 0
		counters.queries.get() shouldBe 0
	}

	@Test
	fun `recording cannot substitute another evidence identity`() = runTest {
		val counters = CallCounters()
		val key = keys().first()
		val descriptors = successfulDescriptors(counters).mapTo(linkedSetOf()) { descriptor ->
			if (descriptor.source != key.source) {
				descriptor
			} else {
				SourceLifecycleProofDescriptor(
				key.source,
				SourceLifecycleProofAdapterFactory {
					object : SourceLifecycleProofAdapter by successfulAdapter(key.source, counters) {
						override suspend fun recording(key: SourceProofKey): SourceProofResult<RecordingProofState> =
							SourceProofResult.Proven(recordingState(key).let { state ->
								state.copy(lineage = state.lineage.copy(evidenceIdentity = SourceEvidenceIdentity("other-event")))
							})
					}
				},
			)
			}
		}

		val proof = coordinator(descriptors, successfulQueryRepository(counters))
			.snapshot(setOf(key)).proofs.getValue(key.source)

		(proof.recording as SourceProofResult.Failed).reason shouldBe SourceProofReason.FACT_IDENTITY_MISMATCH
		counters.materialized.get() shouldBe 0
		counters.queries.get() shouldBe 0
	}

	@Test
	fun `materialization cannot substitute evidence admission or regress evidence revision`() = runTest {
		for (substitution in 0..2) {
			val counters = CallCounters()
			val key = keys().first()
			val descriptors = successfulDescriptors(counters).mapTo(linkedSetOf()) { descriptor ->
				if (descriptor.source != key.source) {
					descriptor
				} else {
					SourceLifecycleProofDescriptor(
					key.source,
					SourceLifecycleProofAdapterFactory {
						object : SourceLifecycleProofAdapter by successfulAdapter(key.source, counters) {
							override suspend fun materialized(
								key: SourceProofKey,
								recording: RecordingProofState,
							): SourceProofResult<MaterializedProofState> {
								val state = materializedState(key, recording)
								val lineage = when (substitution) {
									0 -> state.lineage.copy(evidenceIdentity = SourceEvidenceIdentity("other-event"))
									1 -> state.lineage.copy(sourceAdmissionOrdinal = recording.lineage.sourceAdmissionOrdinal + 1L)
									else -> state.lineage.copy(sourceEvidenceRevision = recording.lineage.sourceEvidenceRevision - 1L)
								}
								return SourceProofResult.Proven(state.copy(lineage = lineage))
							}
						}
					},
				)
				}
			}

			val proof = coordinator(descriptors, successfulQueryRepository(counters))
				.snapshot(setOf(key)).proofs.getValue(key.source)

			(proof.recording is SourceProofResult.Proven) shouldBe true
			(proof.materialized as SourceProofResult.Failed).reason shouldBe SourceProofReason.FACT_IDENTITY_MISMATCH
			(proof.materialized as SourceProofResult.Failed).lineage shouldBe recordingState(key).lineage
			counters.queries.get() shouldBe 0
		}
	}

	@Test
	fun `every source subset proves only its selected sources including empty tracking`() = runTest {
		val allKeys = keys().toList()
		for (mask in 0 until (1 shl allKeys.size)) {
			val selected = allKeys.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
			val counters = CallCounters()
			val result = coordinator(successfulDescriptors(counters), successfulQueryRepository(counters)).snapshot(selected)
			result.proofs.keys shouldBe selected.map { it.source }.toSet()
			result.proofs.values.all { it.queryable is SourceProofResult.Proven } shouldBe true
			counters.adapterCreations.get() shouldBe selected.size
			counters.recording.get() shouldBe selected.size
			counters.materialized.get() shouldBe selected.size
			counters.queries.get() shouldBe selected.size
			counters.providerStarts.get() shouldBe 0
			counters.writes.get() shouldBe 0
		}
	}

	@Test
	fun `different evidence keys for one source cannot silently replace each other`() = runTest {
		val key = keys().first()
		val counters = CallCounters()
		assertFailsWith<IllegalArgumentException> {
			coordinator(successfulDescriptors(counters), successfulQueryRepository(counters))
				.snapshot(setOf(key, key.copy(evidenceIdentity = SourceEvidenceIdentity("another-event"))))
		}
		counters.adapterCreations.get() shouldBe 0
	}

	@Test
	fun `query cannot substitute another fact or data epoch for a proven materialization`() = runTest {
		for (replaceEpoch in listOf(false, true)) {
			val counters = CallCounters()
			val query = SourceFactQueryRepository { request ->
				val original = found(request).state
				SourceFactLookupResult.Found(if (replaceEpoch) {
					original.copy(readSnapshot = TrackingHistoryReadSnapshot(8L, 11L))
				} else {
					original.copy(lineage = original.lineage.copy(factIdentity = SourceFactIdentity("other-fact")))
				})
			}
			val key = keys().first()
			val proof = coordinator(successfulDescriptors(counters), query).snapshot(setOf(key)).proofs.getValue(key.source)
			(proof.materialized is SourceProofResult.Proven) shouldBe true
			(proof.queryable as SourceProofResult.Failed).reason shouldBe SourceProofReason.FACT_IDENTITY_MISMATCH
		}
	}

	@Test
	fun `all six sources preserve one evidence fact lineage through queryable`() = runTest {
		val counters = CallCounters()
		val coordinator = coordinator(
			descriptors = successfulDescriptors(counters),
			queryRepository = successfulQueryRepository(counters),
		)

		val snapshot = coordinator.snapshot(keys())

		snapshot.proofs.keys shouldBe TrackingSource.entries.toSet()
		snapshot.proofs.forEach { (source, proof) ->
			val recording =
				proof.recording as SourceProofResult.Proven<RecordingProofState>
			val materialized =
				proof.materialized as SourceProofResult.Proven<MaterializedProofState>
			val queryable =
				proof.queryable as SourceProofResult.Proven<QueryableProofState>
			recording.state.lineage.evidenceIdentity shouldBe proof.key.evidenceIdentity
			materialized.state.lineage.evidenceIdentity shouldBe proof.key.evidenceIdentity
			queryable.state.lineage shouldBe materialized.state.lineage
			queryable.state.readSnapshot shouldBe TrackingHistoryReadSnapshot(7L, 11L)
			proof.key.source shouldBe source
		}
		counters.recording.get() shouldBe 6
		counters.materialized.get() shouldBe 6
		counters.queries.get() shouldBe 6
	}

	@Test
	fun `one source failure does not block the other five proofs`() = runTest {
		val counters = CallCounters()
		val descriptors = successfulDescriptors(counters).mapTo(linkedSetOf()) { descriptor ->
			if (descriptor.source != TrackingSource.CELL) {
				descriptor
			} else {
				SourceLifecycleProofDescriptor(
					TrackingSource.CELL,
					SourceLifecycleProofAdapterFactory {
						object : SourceLifecycleProofAdapter {
							override val source = TrackingSource.CELL
							override suspend fun recording(key: SourceProofKey):
								SourceProofResult<RecordingProofState> =
								error("isolated read failure")
							override suspend fun materialized(
								key: SourceProofKey,
								recording: RecordingProofState,
							): SourceProofResult<MaterializedProofState> =
								error("must not be reached")
						}
					},
				)
			}
		}
		val coordinator = coordinator(descriptors, successfulQueryRepository(counters))

		val snapshot = coordinator.snapshot(keys())

		(snapshot.proofs.getValue(TrackingSource.CELL).recording as
			SourceProofResult.Failed).reason shouldBe SourceProofReason.REPOSITORY_FAILURE
		TrackingSource.entries.filterNot { it == TrackingSource.CELL }.forEach { source ->
			(snapshot.proofs.getValue(source).queryable is SourceProofResult.Proven<*>) shouldBe true
		}
		counters.queries.get() shouldBe 5
	}

	@Test
	fun `query miss and repository failure remain typed without changing prior proof`() = runTest {
		val counters = CallCounters()
		val queryRepository = SourceFactQueryRepository { request ->
			counters.queries.incrementAndGet()
			when (request.key.source) {
				TrackingSource.WIFI ->
					SourceFactLookupResult.NotFound(SourceProofReason.QUERY_MISS)
				TrackingSource.CELL ->
					SourceFactLookupResult.Failed(SourceProofReason.REPOSITORY_FAILURE)
				else -> found(request)
			}
		}
		val snapshot = coordinator(successfulDescriptors(counters), queryRepository).snapshot(keys())

		(snapshot.proofs.getValue(TrackingSource.WIFI).materialized is
			SourceProofResult.Proven) shouldBe true
		(snapshot.proofs.getValue(TrackingSource.WIFI).queryable as
			SourceProofResult.Pending).reason shouldBe SourceProofReason.QUERY_MISS
		(snapshot.proofs.getValue(TrackingSource.CELL).queryable as
			SourceProofResult.Failed).reason shouldBe SourceProofReason.REPOSITORY_FAILURE
	}

	@Test
	fun `materialization exception preserves proven recording and fails only downstream`() = runTest {
		val counters = CallCounters()
		val descriptors = successfulDescriptors(counters).mapTo(linkedSetOf()) { descriptor ->
			if (descriptor.source != TrackingSource.PRESSURE) {
				descriptor
			} else {
				SourceLifecycleProofDescriptor(
					source = TrackingSource.PRESSURE,
					adapterFactory = SourceLifecycleProofAdapterFactory {
						object : SourceLifecycleProofAdapter {
							override val source = TrackingSource.PRESSURE

							override suspend fun recording(
								key: SourceProofKey,
							): SourceProofResult<RecordingProofState> =
								SourceProofResult.Proven(recordingState(key))

							override suspend fun materialized(
								key: SourceProofKey,
								recording: RecordingProofState,
							): SourceProofResult<MaterializedProofState> =
								error("materialization unavailable")
						}
					},
				)
			}
		}

		val proof = coordinator(
			descriptors,
			successfulQueryRepository(counters),
		).snapshot(keys()).proofs.getValue(TrackingSource.PRESSURE)

		(proof.recording is SourceProofResult.Proven<*>) shouldBe true
		(proof.materialized as SourceProofResult.Failed).lineage shouldBe
			(proof.recording as SourceProofResult.Proven<RecordingProofState>).state.lineage
		(proof.queryable as SourceProofResult.Failed).lineage shouldBe
			(proof.recording as SourceProofResult.Proven<RecordingProofState>).state.lineage
		counters.queries.get() shouldBe 5
	}

	@Test
	fun `query exception preserves recording and materialized lineage`() = runTest {
		val counters = CallCounters()
		val queryRepository = SourceFactQueryRepository { request ->
			if (request.key.source == TrackingSource.WIFI) {
				error("history rejected")
			}
			found(request)
		}

		val proof = coordinator(
			successfulDescriptors(counters),
			queryRepository,
		).snapshot(keys()).proofs.getValue(TrackingSource.WIFI)

		(proof.recording is SourceProofResult.Proven<*>) shouldBe true
		(proof.materialized is SourceProofResult.Proven<*>) shouldBe true
		(proof.queryable as SourceProofResult.Failed).lineage shouldBe
			(proof.materialized as
				SourceProofResult.Proven<MaterializedProofState>).state.lineage
	}

	@Test
	fun `lifecycle invalidation includes every production history dependency`() {
		SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES.toSet()
			.containsAll(TRACKING_HISTORY_INVALIDATION_TABLES) shouldBe true
	}

	@Test
	fun `Steps proof invalidation includes every native and portable count-domain authority table`() {
		TRACKING_HISTORY_INVALIDATION_TABLES.toSet().containsAll(
			setOf(
				"steps_count_domain_receipt",
				"steps_count_domain_owner_revision",
				"steps_count_domain_completeness_marker",
				"steps_count_domain_schema_marker",
				"imported_steps_count_domain_graph",
				"imported_steps_count_domain_receipt",
				"imported_steps_count_domain_owner_revision",
				"imported_steps_count_domain_completeness",
				"imported_steps_count_domain_root",
				"imported_steps_count_domain_binding",
				"imported_steps_count_domain_owner_fence",
			),
		) shouldBe true
	}

	@Test
	fun `lifecycle invalidation includes source proof materialization and query dependencies`() {
		SOURCE_LIFECYCLE_PROOF_SPECIFIC_TABLES.toSet() shouldBe setOf(
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
		SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES.size shouldBe
			SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES.toSet().size
	}

	@Test
	fun `recording materialization projection failure and production lookup invalidate proof`() {
		val observed = SOURCE_LIFECYCLE_PROOF_OBSERVED_TABLES.toSet()

		observed.containsAll(
			setOf(
				"source_policy_authority",
				"source_desired_plan",
				"provider_registration_generation",
				"source_authorization",
				"session_manifest_source",
				"lifecycle_desired_action",
			),
		) shouldBe true
		observed.containsAll(
			setOf(
				"source_product_projection_lane",
				"wifi_captured_fact_cursor",
				"cell_captured_fact_cursor",
				"source_projection_join_state",
			),
		) shouldBe true
		("source_projection_failure" in observed) shouldBe true
		observed.containsAll(
			setOf(
				"source_event_wal",
				"source_event_retention_proof",
				"source_evidence_state",
				"source_deletion_fence",
				"location_observation",
				"location_observation_decision",
				"location_sample",
			),
		) shouldBe true
	}

	@Test
	fun `stage cancellation propagates instead of becoming repository failure`() = runTest {
		val counters = CallCounters()
		val queryRepository = SourceFactQueryRepository {
			throw CancellationException("cancelled")
		}

		assertFailsWith<CancellationException> {
			coordinator(successfulDescriptors(counters), queryRepository).snapshot(keys())
		}
	}

	@Test
	fun `lifecycle writer cursor deletion retention and partial states remain typed`() = runTest {
		val sourceResults: Map<TrackingSource, SourceProofResult<RecordingProofState>> = mapOf(
			TrackingSource.LOCATION to SourceProofResult.Deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.DELETED,
			),
			TrackingSource.ACTIVITY to SourceProofResult.Deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.RETENTION_EXPIRED,
			),
		)
		val descriptors = TrackingSource.entries.mapTo(linkedSetOf()) { source ->
			SourceLifecycleProofDescriptor(
				source,
				SourceLifecycleProofAdapterFactory {
					object : SourceLifecycleProofAdapter {
						override val source = source

						override suspend fun recording(
							key: SourceProofKey,
						): SourceProofResult<RecordingProofState> =
							sourceResults[source] ?: SourceProofResult.Proven(recordingState(key))

						override suspend fun materialized(
							key: SourceProofKey,
							recording: RecordingProofState,
						): SourceProofResult<MaterializedProofState> = when (source) {
							TrackingSource.STEPS -> SourceProofResult.Failed(
								SourceProofStage.MATERIALIZED,
								SourceProofReason.WRITER_GENERATION_MISMATCH,
								recording.lineage,
							)
							TrackingSource.PRESSURE -> SourceProofResult.Failed(
								SourceProofStage.MATERIALIZED,
								SourceProofReason.WRITER_MISMATCH,
								recording.lineage,
							)
							TrackingSource.WIFI -> SourceProofResult.Pending(
								SourceProofStage.MATERIALIZED,
								SourceProofReason.CURSOR_BEHIND,
								recording.lineage,
							)
							else -> SourceProofResult.Proven(
								materializedState(key, recording).copy(
									completeness = SourceProofCompleteness.PARTIAL,
								),
							)
						}
					}
				},
			)
		}

		val snapshot = coordinator(
			descriptors,
			SourceFactQueryRepository { request -> found(request) },
		).snapshot(keys())

		(snapshot.proofs.getValue(TrackingSource.LOCATION).recording as
			SourceProofResult.Deleted).reason shouldBe SourceProofReason.DELETED
		(snapshot.proofs.getValue(TrackingSource.ACTIVITY).recording as
			SourceProofResult.Deleted).reason shouldBe SourceProofReason.RETENTION_EXPIRED
		(snapshot.proofs.getValue(TrackingSource.STEPS).materialized as
			SourceProofResult.Failed).reason shouldBe SourceProofReason.WRITER_GENERATION_MISMATCH
		(snapshot.proofs.getValue(TrackingSource.PRESSURE).materialized as
			SourceProofResult.Failed).reason shouldBe SourceProofReason.WRITER_MISMATCH
		(snapshot.proofs.getValue(TrackingSource.WIFI).materialized as
			SourceProofResult.Pending).reason shouldBe SourceProofReason.CURSOR_BEHIND
		(snapshot.proofs.getValue(TrackingSource.CELL).queryable as
			SourceProofResult.Proven<QueryableProofState>).state.completeness shouldBe
			SourceProofCompleteness.PARTIAL
	}

	@Test
	fun `process reconstruction uses only supplied durable readers`() = runTest {
		val firstCounters = CallCounters()
		val secondCounters = CallCounters()

		val first = coordinator(
			successfulDescriptors(firstCounters),
			successfulQueryRepository(firstCounters),
		).snapshot(keys())
		val reconstructed = coordinator(
			successfulDescriptors(secondCounters),
			successfulQueryRepository(secondCounters),
		).snapshot(keys())

		reconstructed shouldBe first
	}

	@Test
	fun `one conflated observation performs bounded callbacks and cancellation closes observer`() =
		runTest {
			val counters = CallCounters()
			val activeObservers = AtomicInteger()
			val invalidations = callbackFlow {
				activeObservers.incrementAndGet()
				trySend(Unit)
				awaitClose { activeObservers.decrementAndGet() }
			}
			val coordinator = SourceLifecycleProofCoordinator(
				invalidations = invalidations,
				descriptors = successfulDescriptors(counters),
				queryRepository = successfulQueryRepository(counters),
				ioDispatcher = StandardTestDispatcher(testScheduler),
			)

			coordinator.observe(keys()).first()

			activeObservers.get() shouldBe 0
			counters.adapterCreations.get() shouldBe 6
			counters.recording.get() shouldBe 6
			counters.materialized.get() shouldBe 6
			counters.queries.get() shouldBe 6
			counters.providerStarts.get() shouldBe 0
			counters.writes.get() shouldBe 0
		}

	@Test
	fun `continuous invalidations preserve one complete snapshot and only the latest pending read`() =
		runTest {
			val invalidations = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 64)
			invalidations.tryEmit(Unit)
			val firstStarted = CompletableDeferred<Unit>()
			val releaseFirst = CompletableDeferred<Unit>()
			val locationStarts = AtomicInteger()
			val cancelledRecordings = AtomicInteger()
			val counters = CallCounters()
			val descriptors = TrackingSource.entries.mapTo(linkedSetOf()) { source ->
				SourceLifecycleProofDescriptor(
					source,
					SourceLifecycleProofAdapterFactory {
						object : SourceLifecycleProofAdapter {
							override val source = source

							override suspend fun recording(
								key: SourceProofKey,
							): SourceProofResult<RecordingProofState> {
								counters.recording.incrementAndGet()
								if (source == TrackingSource.LOCATION &&
									locationStarts.incrementAndGet() == 1
								) {
									firstStarted.complete(Unit)
									try {
										releaseFirst.await()
									} catch (cancelled: CancellationException) {
										cancelledRecordings.incrementAndGet()
										throw cancelled
									}
								}
								return SourceProofResult.Proven(recordingState(key))
							}

							override suspend fun materialized(
								key: SourceProofKey,
								recording: RecordingProofState,
							): SourceProofResult<MaterializedProofState> {
								counters.materialized.incrementAndGet()
								return SourceProofResult.Proven(materializedState(key, recording))
							}
						}
					},
				)
			}
			val coordinator = SourceLifecycleProofCoordinator(
				invalidations = invalidations,
				descriptors = descriptors,
				queryRepository = successfulQueryRepository(counters),
				ioDispatcher = StandardTestDispatcher(testScheduler),
			)
			val collection = async {
				coordinator.observe(keys()).take(2).toList()
			}

			runCurrent()
			firstStarted.await()
			repeat(32) { invalidations.emit(Unit) }
			runCurrent()
			locationStarts.get() shouldBe 1
			cancelledRecordings.get() shouldBe 0

			releaseFirst.complete(Unit)
			advanceUntilIdle()
			collection.await().size shouldBe 2
			locationStarts.get() shouldBe 2
			counters.recording.get() shouldBe 12
			counters.materialized.get() shouldBe 12
			counters.queries.get() shouldBe 12
		}

	@Test
	fun `collector cancellation still cancels an in-flight six-source snapshot`() = runTest {
		val invalidations = MutableSharedFlow<Unit>(replay = 1)
		invalidations.tryEmit(Unit)
		val started = CompletableDeferred<Unit>()
		val cancelled = CompletableDeferred<Unit>()
		val descriptors = successfulDescriptors(CallCounters()).mapTo(linkedSetOf()) { descriptor ->
			if (descriptor.source != TrackingSource.LOCATION) {
				descriptor
			} else {
				SourceLifecycleProofDescriptor(
					TrackingSource.LOCATION,
					SourceLifecycleProofAdapterFactory {
						object : SourceLifecycleProofAdapter {
							override val source = TrackingSource.LOCATION

							override suspend fun recording(
								key: SourceProofKey,
							): SourceProofResult<RecordingProofState> {
								started.complete(Unit)
								try {
									awaitCancellation()
								} finally {
									cancelled.complete(Unit)
								}
							}

							override suspend fun materialized(
								key: SourceProofKey,
								recording: RecordingProofState,
							): SourceProofResult<MaterializedProofState> =
								error("unreachable")
						}
					},
				)
			}
		}
		val coordinator = SourceLifecycleProofCoordinator(
			invalidations = invalidations,
			descriptors = descriptors,
			queryRepository = successfulQueryRepository(CallCounters()),
			ioDispatcher = StandardTestDispatcher(testScheduler),
		)
		val collection = launch { coordinator.observe(keys()).collect {} }

		runCurrent()
		started.await()
		collection.cancelAndJoin()
		cancelled.await()
	}

	private fun coordinator(
		descriptors: Set<SourceLifecycleProofDescriptor>,
		queryRepository: SourceFactQueryRepository,
	) = SourceLifecycleProofCoordinator(
		invalidations = flowOf(Unit),
		descriptors = descriptors,
		queryRepository = queryRepository,
		ioDispatcher = StandardTestDispatcher(),
	)

	private fun successfulDescriptors(counters: CallCounters) =
		TrackingSource.entries.mapTo(linkedSetOf()) { source ->
			SourceLifecycleProofDescriptor(
				source,
				SourceLifecycleProofAdapterFactory {
					counters.adapterCreations.incrementAndGet()
					successfulAdapter(source, counters)
				},
			)
		}

	private fun successfulAdapter(
		source: TrackingSource,
		counters: CallCounters,
	) = object : SourceLifecycleProofAdapter {
		override val source: TrackingSource = source

		override suspend fun recording(
			key: SourceProofKey,
		): SourceProofResult<RecordingProofState> {
			counters.recording.incrementAndGet()
			return SourceProofResult.Proven(recordingState(key))
		}

		override suspend fun materialized(
			key: SourceProofKey,
			recording: RecordingProofState,
		): SourceProofResult<MaterializedProofState> {
			counters.materialized.incrementAndGet()
			return SourceProofResult.Proven(materializedState(key, recording))
		}
	}

	private fun successfulQueryRepository(counters: CallCounters) =
		SourceFactQueryRepository { request ->
			counters.queries.incrementAndGet()
			found(request)
		}

	private fun found(request: SourceFactLookupRequest) = SourceFactLookupResult.Found(
		QueryableProofState(
			lineage = request.materialized.lineage,
			coverage = request.materialized.coverage,
			completeness = request.materialized.completeness,
			readSnapshot = TrackingHistoryReadSnapshot(7L, 11L),
		),
	)

	private fun recordingState(key: SourceProofKey) = RecordingProofState(
		lineage = SourceProofLineage(
			evidenceIdentity = key.evidenceIdentity,
			sourceAdmissionOrdinal = key.source.ordinal.toLong() + 1L,
			sourceEvidenceRevision = 10L,
		),
		coverage = SourceProofCoverage.ProviderPoint(100L),
		completeness = SourceProofCompleteness.COMPLETE,
	)

	private fun materializedState(
		key: SourceProofKey,
		recording: RecordingProofState,
	) = MaterializedProofState(
		lineage = recording.lineage.copy(
			sourceEvidenceRevision = 11L,
			factIdentity = SourceFactIdentity("fact-${key.source.name.lowercase()}"),
			semanticRevision = 1L,
			writer = SourceProofWriterIdentity("writer", 1, 1L, 1L),
			cursorRevision = recording.lineage.sourceAdmissionOrdinal,
		),
		coverage = recording.coverage,
		completeness = recording.completeness,
	)

	private fun keys(): Set<SourceProofKey> = TrackingSource.entries.mapTo(linkedSetOf()) { source ->
		SourceProofKey(
			source = source,
			purpose = TrackingPurpose.SESSION_CAPTURE,
			evidenceIdentity = SourceEvidenceIdentity("event-${source.name.lowercase()}"),
			sourcePolicyRevision = 3L,
			captureConsentEpoch = 4L,
			collectedDataEpoch = 7L,
			scope = SourceProofScope.Run("logical", "run"),
		)
	}

	private data class CallCounters(
		val adapterCreations: AtomicInteger = AtomicInteger(),
		val recording: AtomicInteger = AtomicInteger(),
		val materialized: AtomicInteger = AtomicInteger(),
		val queries: AtomicInteger = AtomicInteger(),
		val providerStarts: AtomicInteger = AtomicInteger(),
		val writes: AtomicInteger = AtomicInteger(),
	)
}
