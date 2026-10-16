package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.persistPressureFactRetentionAuthority
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProtectedLocationCanonicalReceiptCodec
import com.adsamcik.tracker.shared.base.database.data.ProtectedLocationCanonicalReceiptPayload
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalReceiptBytes
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalReceiptStateKey
import com.adsamcik.tracker.shared.base.database.data.protectedLocationPreparedCurationStateKey
import com.adsamcik.tracker.shared.base.database.data.protectedLocationRunCurationStateKey
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupRequest
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupResult
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofSelectedEvidenceLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofWriterIdentity
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// One real source-fact lookup fixture keeps original receipts and deletion precedence comparable.
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceFactQueryRepositoryRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var repository: RoomSourceFactLookupReader

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		repository = RoomSourceFactLookupReader(database, UnconfinedTestDispatcher())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `location lookup returns only the exact canonical fact identity`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation()

		val found = repository.lookup(request())
		val missed = repository.lookup(
			request().copy(
				materialized = materialized().copy(
					lineage = materialized().lineage.copy(
						factIdentity = SourceFactIdentity("other-event"),
					),
				),
			),
		)

		(found as SourceFactLookupResult.Found).state.readSnapshot.sourceEvidenceRevision shouldBe 1L
		(missed as SourceFactLookupResult.Failed).reason shouldBe
			SourceProofReason.FACT_IDENTITY_MISMATCH
	}

	@Test
	fun `acknowledged floor expires live Location before physical prune using uncertainty`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation(wallTimeUncertaintyMs = 20L)
		database.sourceEvidenceStateDao().updateLifecycle(7L, 90L, 2L)

		val direct = repository.lookup(request())
		val production = productionRepository().lookupSourceFact(request())

		(direct as SourceFactLookupResult.Deleted).reason shouldBe
			SourceProofReason.RETENTION_EXPIRED
		(production as SourceFactLookupResult.Deleted).reason shouldBe
			SourceProofReason.RETENTION_EXPIRED

	}

	@Test
	fun `Location lower bound equal to acknowledged floor remains queryable`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation(wallTimeUncertaintyMs = 20L)
		database.sourceEvidenceStateDao().updateLifecycle(7L, 80L, 2L)

		(repository.lookup(request()) is SourceFactLookupResult.Found) shouldBe true
	}

	@Test
	fun `newer collected data epoch is terminal deletion not query miss`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(8L, null, 1L)

		val result = repository.lookup(request())

		(result as SourceFactLookupResult.Deleted).reason shouldBe SourceProofReason.DELETED
	}

	@Test
	fun `production history lookup resolves exact deletion before missing presentation`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId = "logical",
				serviceRunId = "run",
				fenceGeneration = 1L,
				collectedDataEpoch = 7L,
				deletedAtMs = 2L,
			),
		)

		val result = productionRepository().lookupSourceFact(request())

		(result as SourceFactLookupResult.Deleted).reason shouldBe SourceProofReason.DELETED
	}

	@Test
	fun `production history lookup resolves global deletion before missing presentation`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 7L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 1L,
			updatedAtMs = 2L,
		)

		val result = productionRepository().lookupSourceFact(request())

		(result as SourceFactLookupResult.Deleted).reason shouldBe SourceProofReason.DELETED
	}

	@Test
	fun `production history lookup keeps unfenced missing fact as not found`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		)

		val result = productionRepository().lookupSourceFact(stepsRequest())

		result shouldBe SourceFactLookupResult.NotFound()
	}

	@Test
	fun `Wi-Fi and Cell query misses resolve authenticated dependent retention proofs`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, 100L, 1L)
		listOf(TrackingSource.WIFI, TrackingSource.CELL).forEach { source ->
			val eventId = "retained-${source.name.lowercase()}"
			installHistoricalLane(
				sourceKind = radioSourceKind(source),
				bindingGeneration = radioBindingGeneration(source),
				projectionId = radioProjectionId(source),
				projectionVersion = radioProjectionVersion(source),
				throughOrdinal = 41L,
			)
			database.capturedRadioFactRetentionProofDao().insert(
				capturedRadioRetentionProof(source, eventId),
			)

			repository.lookup(radioRequest(source, eventId)) shouldBe
				SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
		}
	}

	@Test
	fun `corrupt captured radio retention proof fails query closed`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, 100L, 1L)
		installHistoricalLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
			bindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			throughOrdinal = 41L,
		)
		val eventId = "retained-cell"
		database.capturedRadioFactRetentionProofDao().insert(
			capturedRadioRetentionProof(TrackingSource.CELL, eventId)
				.copy(effectChecksum = "corrupt"),
		)

		repository.lookup(radioRequest(TrackingSource.CELL, eventId)) shouldBe
			SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
	}

	@Test
	fun `stale captured radio retention cursor fails query closed`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, 100L, 1L)
		installHistoricalLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
			bindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			throughOrdinal = 41L,
		)
		val eventId = "retained-cell"
		database.capturedRadioFactRetentionProofDao().insert(
			capturedRadioRetentionProof(TrackingSource.CELL, eventId),
		)
		val staleRequest = radioRequest(TrackingSource.CELL, eventId).let { request ->
			request.copy(
				materialized = request.materialized.copy(
					lineage = request.materialized.lineage.copy(cursorRevision = 2L),
				),
			)
		}

		repository.lookup(staleRequest) shouldBe
			SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
	}

	// Keep one retained lineage and its exact/stale selections together to verify the production lookup precedence.
	@Suppress("LongMethod")
	@Test
	fun `production Pressure lookup resolves exact retained lineage before missing fact and presentation`() =
		runTest {
			database.sourceEvidenceStateDao().ensure()
			database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 1L)
			installHistoricalLane(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
				throughOrdinal = 3L,
			)
			val lineage = retainedPressureLineage()
			val fact = lineage.last()
			database.persistPressureFactRetentionAuthority(
				revisions = lineage,
				retainedFromMs = 1_500L,
				retainedAtMs = 2_000L,
			)

			val result = productionRepository().lookupSourceFact(pressureRequest(fact))
			val intermediateResult = productionRepository().lookupSourceFact(
				pressureRequest(lineage[1]),
			)
			val laterRetainedIntermediateResult = productionRepository().lookupSourceFact(
				pressureRequest(fact, selectedFact = lineage[1]),
			)
			val directRetainedIntermediateResult = repository.lookup(
				pressureRequest(fact, selectedFact = lineage[1]),
			)
			val triggerResult = productionRepository().lookupSourceFact(
				pressureRequest(lineage.first()),
			)
			val staleExactRequest = pressureRequest(lineage[1]).let { request ->
				request.copy(
					materialized = request.materialized.copy(
						lineage = request.materialized.lineage.copy(
							sourceAdmissionOrdinal = lineage[1].sourceAdmissionOrdinal - 1L,
						),
					),
				)
			}
			val staleExactResult = productionRepository().lookupSourceFact(staleExactRequest)
			val staleSupersessionRequest = pressureRequest(fact).let { request ->
				request.copy(
					materialized = request.materialized.copy(
						lineage = request.materialized.lineage.copy(
							selectedEvidenceLineage = null,
						),
					),
				)
			}
			val staleSupersessionResult =
				productionRepository().lookupSourceFact(staleSupersessionRequest)

			(result as SourceFactLookupResult.Deleted).reason shouldBe
				SourceProofReason.RETENTION_EXPIRED
			(intermediateResult as SourceFactLookupResult.Failed).reason shouldBe
				SourceProofReason.INTEGRITY_FAILURE
			(laterRetainedIntermediateResult as SourceFactLookupResult.Deleted).reason shouldBe
				SourceProofReason.RETENTION_EXPIRED
			(directRetainedIntermediateResult as SourceFactLookupResult.Deleted).reason shouldBe
				SourceProofReason.RETENTION_EXPIRED
			(triggerResult as SourceFactLookupResult.Failed).reason shouldBe
				SourceProofReason.INTEGRITY_FAILURE
			(staleExactResult as SourceFactLookupResult.Failed).reason shouldBe
				SourceProofReason.INTEGRITY_FAILURE
			(staleSupersessionResult as SourceFactLookupResult.Failed).reason shouldBe
				SourceProofReason.INTEGRITY_FAILURE
		}

	@Test
	fun `Pressure historical selection before lane activation fails with current head after activation`() =
		runTest {
			database.sourceEvidenceStateDao().ensure()
			database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 1L)
			installHistoricalLane(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
				activationOrdinal = 2L,
				throughOrdinal = 3L,
			)
			val lineage = retainedPressureLineage()
			database.persistPressureFactRetentionAuthority(lineage, 1_500L, 2_000L)
			val request = pressureRequest(lineage.last(), selectedFact = lineage.first())

			(repository.lookup(request) as SourceFactLookupResult.Failed).reason shouldBe
				SourceProofReason.INTEGRITY_FAILURE
			(productionRepository().lookupSourceFact(request) as SourceFactLookupResult.Failed)
				.reason shouldBe SourceProofReason.INTEGRITY_FAILURE
		}

	@Test
	fun `unrelated retained Pressure lineage leaves selected survivor query absent`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 1L)
		installHistoricalLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			throughOrdinal = 4L,
		)
		database.persistPressureFactRetentionAuthority(
			revisions = retainedPressureLineage(),
			retainedFromMs = 1_500L,
			retainedAtMs = 2_000L,
		)
		val survivor = retainedPressureFact().copy(
			logicalFactId =
				"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:pressure-survivor",
			semanticRevision = 1L,
			mutationId =
				"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:pressure-survivor:1",
			sourceEventId = "pressure-survivor",
			sourceAdmissionOrdinal = 4L,
			intervalStartTimeMs = 2_000L,
			intervalEndTimeMs = 2_150L,
			appliedAtMs = 2_150L,
		)
		val request = pressureRequest(survivor)

		repository.lookup(request) shouldBe SourceFactLookupResult.NotFound()
		productionRepository().lookupSourceFact(request) shouldBe SourceFactLookupResult.NotFound()
	}

	@Test
	fun `live Pressure event collision fails selected and production lookup closed`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 1L)
		installHistoricalLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			throughOrdinal = 4L,
		)
		val lineage = retainedPressureLineage()
		val retained = lineage[1]
		database.pressureFactRevisionDao().insert(
			retainedPressureFact().copy(
				logicalFactId =
					"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:live-collision",
				mutationId =
					"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:live-collision:1",
				sourceEventId = retained.sourceEventId,
				sourceAdmissionOrdinal = 4L,
			),
		)
		database.persistPressureFactRetentionAuthority(lineage, 1_500L, 2_000L)
		val request = pressureRequest(retained)

		(repository.lookup(request) as SourceFactLookupResult.Failed).reason shouldBe
			SourceProofReason.INTEGRITY_FAILURE
		(productionRepository().lookupSourceFact(request) as SourceFactLookupResult.Failed)
			.reason shouldBe SourceProofReason.INTEGRITY_FAILURE
	}

	@Test
	fun `corrupt Pressure retention authority cannot downgrade missing fact to not found`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 1L)
		installHistoricalLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			throughOrdinal = 3L,
		)
		val lineage = retainedPressureLineage()
		val fact = lineage.last()
		database.persistPressureFactRetentionAuthority(
			revisions = lineage,
			retainedFromMs = 1_500L,
			retainedAtMs = 2_000L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE pressure_fact_retention_authority SET effect_checksum = 'corrupt'",
		)

		val result = productionRepository().lookupSourceFact(pressureRequest(fact))

		(result as SourceFactLookupResult.Failed).reason shouldBe
			SourceProofReason.INTEGRITY_FAILURE
	}

	@Test
	fun `corrupt intermediate Pressure revision authority fails exact lookup closed`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 1L)
		installHistoricalLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			throughOrdinal = 3L,
		)
		val lineage = retainedPressureLineage()
		database.persistPressureFactRetentionAuthority(lineage, 1_500L, 2_000L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE pressure_fact_retention_revision_authority SET effect_checksum = 'corrupt' " +
				"WHERE semantic_revision = 2",
		)

		val result = productionRepository().lookupSourceFact(pressureRequest(lineage[1]))

		(result as SourceFactLookupResult.Failed).reason shouldBe
			SourceProofReason.INTEGRITY_FAILURE
	}

	@Test
	fun `production history lookup keeps unfenced missing presentation as not found`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation()

		val result = productionRepository().lookupSourceFact(request())

		result shouldBe SourceFactLookupResult.NotFound()
	}

	@Test
	fun `wrong canonical writer generation fails against immutable receipt provenance`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation()
		val wrongWriter = materialized().copy(
			lineage = materialized().lineage.copy(
				writer = SourceProofWriterIdentity(
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION,
					SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION + 1L,
				),
			),
		)

		val result = repository.lookup(SourceFactLookupRequest(key(), wrongWriter))

		(result as SourceFactLookupResult.Failed).reason shouldBe
			SourceProofReason.WRITER_GENERATION_MISMATCH
	}

	@Test
	fun `location rows without exact WAL remain unavailable`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation(includeWal = false)

		val result = repository.lookup(request())

		(result as SourceFactLookupResult.Unavailable).reason shouldBe
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING
	}

	@Test
	fun `location rows without protected canonical receipt remain unavailable`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation(includeReceipt = false)

		val result = repository.lookup(request())

		(result as SourceFactLookupResult.Unavailable).reason shouldBe
			SourceProofReason.RECEIPT_MISSING
	}

	@Test
	fun `stale protected canonical receipt cannot prove location queryability`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation(staleReceipt = true)

		val result = repository.lookup(request())

		(result as SourceFactLookupResult.Failed).reason shouldBe
			SourceProofReason.INTEGRITY_FAILURE
	}

	@Test
	fun `canonical rejection cannot be queried as a proven location fact`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation(
			storedDecision = decision().copy(
				decision = LocationObservationDecision.REJECTED,
				reason = "CURATED_LOCATION_REJECTED",
				acceptedSampleSourceSignalId = null,
			),
			storedSample = null,
		)

		val result = repository.lookup(request())

		(result as SourceFactLookupResult.Failed).reason shouldBe
			SourceProofReason.INTEGRITY_FAILURE
	}

	@Test
	fun `retired historical lane remains valid after destination owner rearm`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 1L)
		installHistoricalLane()
		installQueryableLocation()
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
				owner = SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
				ownerGeneration =
					SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
				updatedAtMs = 1L,
			),
		)
		val current = requireNotNull(
			database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_LOCATION,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			),
		)
		database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = current.sourceKind,
			destination = current.destination,
			expectedOwner = current.owner,
			expectedOwnerGeneration = current.ownerGeneration,
			newOwner = current.owner,
			newOwnerGeneration = current.ownerGeneration + 1L,
			updatedAtMs = 2L,
		) shouldBe 1

		(repository.lookup(request()) is SourceFactLookupResult.Found) shouldBe true
	}

	@Test
	fun `query adapter preserves production history rejection without direct Room fallback`() =
		runTest {
			val history = mockk<TrackingHistoryRepository>()
			val rejection = SourceFactLookupResult.Unavailable(
				SourceProofReason.INTEGRITY_FAILURE,
			)
			coEvery { history.lookupSourceFact(request()) } returns rejection
			val adapter = DefaultSourceFactQueryRepository(history)

			adapter.lookup(request()) shouldBe rejection

			coVerify(exactly = 1) { history.lookupSourceFact(request()) }
		}

	private fun request() = SourceFactLookupRequest(key(), materialized())

	private fun stepsRequest() = SourceFactLookupRequest(
		key().copy(source = TrackingSource.STEPS),
		materialized().copy(
			lineage = materialized().lineage.copy(
				factIdentity = SourceFactIdentity("missing-steps-fact"),
				writer = SourceProofWriterIdentity(
					SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
					SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
					SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				),
			),
		),
	)

	private fun pressureRequest(
		fact: PressureFactRevisionEntity,
		selectedFact: PressureFactRevisionEntity = fact,
	) = SourceFactLookupRequest(
		key = SourceProofKey(
			source = TrackingSource.PRESSURE,
			purpose = TrackingPurpose.SESSION_CAPTURE,
			evidenceIdentity = SourceEvidenceIdentity(selectedFact.sourceEventId),
			sourcePolicyRevision = selectedFact.sourcePolicyRevision,
			captureConsentEpoch = selectedFact.captureConsentEpoch,
			collectedDataEpoch = selectedFact.collectedDataEpoch,
			scope = SourceProofScope.Run(
				selectedFact.logicalTrackingId,
				selectedFact.serviceRunId,
			),
		),
		materialized = MaterializedProofState(
			lineage = SourceProofLineage(
				evidenceIdentity = SourceEvidenceIdentity(selectedFact.sourceEventId),
				sourceAdmissionOrdinal = selectedFact.sourceAdmissionOrdinal,
				sourceEvidenceRevision = 1L,
				factIdentity = SourceFactIdentity(fact.logicalFactId),
				semanticRevision = fact.semanticRevision,
				writer = SourceProofWriterIdentity(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					fact.writerBindingGeneration,
					SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				),
				cursorRevision = fact.sourceAdmissionOrdinal,
				selectedEvidenceLineage = SourceProofSelectedEvidenceLineage(
					semanticRevision = selectedFact.semanticRevision,
					supersededByCurrentSemanticRevision = if (
						selectedFact.semanticRevision < fact.semanticRevision
					) {
						fact.semanticRevision
					} else {
						null
					},
				),
			),
			coverage = SourceProofCoverage.ProviderInterval(
				selectedFact.windowStartElapsedRealtimeNanos,
				selectedFact.windowEndElapsedRealtimeNanos,
			),
			completeness = SourceProofCompleteness.COMPLETE,
		),
	)

	private fun retainedPressureFact() = PressureFactRevisionEntity(
		logicalFactId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:pressure-retained",
		semanticRevision = 1L,
		mutationId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:pressure-retained:1",
		sourceEventId = "pressure-retained",
		sourceAdmissionOrdinal = 1L,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 1_150L,
		windowStartElapsedRealtimeNanos = 1_000_000_000L,
		windowEndElapsedRealtimeNanos = 1_150_000_000L,
		clockDomainId = "boot",
		wallTimeUncertaintyMs = 1L,
		sampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		firstProviderSequence = 1L,
		lastProviderSequence = 4L,
		firstHectopascals = 1_000f,
		lastHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 20.0,
		rSquared = 1.0,
		sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 200_000,
		targetWindowDurationNanos = 200_000_000L,
		expectedSampleCount = 4,
		maximumInterSampleGapNanos = 50_000_000L,
		closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
		qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = null,
		logicalTrackingId = "logical-pressure",
		serviceRunId = "run-pressure",
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 3L,
		captureConsentEpoch = 4L,
		collectedDataEpoch = 7L,
		effectChecksum = "lineage-effect",
		appliedAtMs = 1_150L,
	)

	private fun retainedPressureLineage(): List<PressureFactRevisionEntity> {
		val trigger = retainedPressureFact()
		return listOf(
			trigger,
			trigger.copy(
				semanticRevision = 2L,
				mutationId = "${trigger.logicalFactId}:2",
				sourceEventId = "pressure-retained-intermediate",
				sourceAdmissionOrdinal = 2L,
				intervalStartTimeMs = 1_600L,
				intervalEndTimeMs = 1_750L,
				windowStartElapsedRealtimeNanos = 1_600_000_000L,
				windowEndElapsedRealtimeNanos = 1_750_000_000L,
				firstProviderSequence = 5L,
				lastProviderSequence = 8L,
				effectChecksum = "intermediate-lineage-effect",
				appliedAtMs = 1_750L,
			),
			trigger.copy(
				semanticRevision = 3L,
				mutationId = "${trigger.logicalFactId}:3",
				sourceEventId = "pressure-retained-current",
				sourceAdmissionOrdinal = 3L,
				intervalStartTimeMs = 1_800L,
				intervalEndTimeMs = 1_950L,
				windowStartElapsedRealtimeNanos = 1_800_000_000L,
				windowEndElapsedRealtimeNanos = 1_950_000_000L,
				firstProviderSequence = 9L,
				lastProviderSequence = 12L,
				effectChecksum = "current-lineage-effect",
				appliedAtMs = 1_950L,
			),
		)
	}

	private fun productionRepository() = DefaultTrackingHistoryRepository(
		database = database,
		stepsSelector = mockk(relaxed = true),
		logicalHistoryReader = mockk(relaxed = true),
		ioDispatcher = UnconfinedTestDispatcher(),
	)

	private fun key() = SourceProofKey(
		source = TrackingSource.LOCATION,
		purpose = TrackingPurpose.SESSION_CAPTURE,
		evidenceIdentity = SourceEvidenceIdentity(EVENT_ID),
		sourcePolicyRevision = 3L,
		captureConsentEpoch = 4L,
		collectedDataEpoch = 7L,
		scope = SourceProofScope.Run("logical", "run"),
	)

	private fun materialized() = MaterializedProofState(
		lineage = SourceProofLineage(
			evidenceIdentity = SourceEvidenceIdentity(EVENT_ID),
			sourceAdmissionOrdinal = 1L,
			sourceEvidenceRevision = 1L,
			factIdentity = SourceFactIdentity(EVENT_ID),
			semanticRevision = 1L,
			writer = SourceProofWriterIdentity(
				SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
				SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
				SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION,
				SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
			),
			cursorRevision = 1L,
		),
		coverage = SourceProofCoverage.ProviderPoint(100L),
		completeness = SourceProofCompleteness.COMPLETE,
	)

	private fun radioRequest(
		source: TrackingSource,
		eventId: String,
	): SourceFactLookupRequest {
		val projectionId = radioProjectionId(source)
		val projectionVersion = radioProjectionVersion(source)
		val bindingGeneration = radioBindingGeneration(source)
		return SourceFactLookupRequest(
			key = SourceProofKey(
				source = source,
				purpose = TrackingPurpose.SESSION_CAPTURE,
				evidenceIdentity = SourceEvidenceIdentity(eventId),
				sourcePolicyRevision = 1L,
				captureConsentEpoch = 1L,
				collectedDataEpoch = 7L,
				scope = SourceProofScope.Run("logical", "run"),
			),
			materialized = MaterializedProofState(
				lineage = SourceProofLineage(
					evidenceIdentity = SourceEvidenceIdentity(eventId),
					sourceAdmissionOrdinal = 41L,
					sourceEvidenceRevision = 1L,
					factIdentity = SourceFactIdentity("1".repeat(64)),
					semanticRevision = 1L,
					writer = SourceProofWriterIdentity(
						projectionId,
						projectionVersion,
						bindingGeneration,
						SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					),
					cursorRevision = 1L,
				),
				coverage = SourceProofCoverage.ProviderInterval(10L, 20L),
				completeness = SourceProofCompleteness.COMPLETE,
			),
		)
	}

	private fun capturedRadioRetentionProof(
		source: TrackingSource,
		eventId: String,
	): CapturedRadioFactRetentionProofEntity {
		val logicalFactId = "1".repeat(64)
		val mutationId = if (source == TrackingSource.CELL) {
			com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
				.mutationId(logicalFactId, 1L)
		} else {
			com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
				.mutationId(logicalFactId, 1L)
		}
		val unsigned = CapturedRadioFactRetentionProofEntity(
			sourceKind = radioSourceKind(source),
			writerProjectionId = radioProjectionId(source),
			writerProjectionVersion = radioProjectionVersion(source),
			writerBindingGeneration = radioBindingGeneration(source),
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = mutationId,
			factEffectChecksum = "3".repeat(64),
			sourceEventId = eventId,
			sourceAdmissionOrdinal = 41L,
			walIntegrityIdentity = "4".repeat(64),
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 7L,
			logicalTrackingId = "logical",
			serviceRunId = "run",
			scopeDeletionGeneration = 0L,
			logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				radioSourceKind(source),
				SessionManifestPurposeCode.SESSION_CAPTURE,
				"logical",
				"run",
			),
			cursorRevision = 1L,
			cursorMutationId = mutationId,
			cursorEffectChecksum = "3".repeat(64),
			cursorSourceAdmissionOrdinal = 41L,
			cursorUpdatedAtMs = 50L,
			aggregateOwnerLogicalFactId = "5".repeat(64),
			aggregateOwnerSemanticRevision = 1L,
			aggregateOwnerSourceEventId = "owner-$eventId",
			aggregateOwnerSourceAdmissionOrdinal = 40L,
			aggregateOwnerWalIntegrityIdentity = "6".repeat(64),
			aggregateOwnerEffectChecksum = "7".repeat(64),
			aggregateOwnerCursorRevision = 1L,
			retainedFromMs = 100L,
			retainedAtMs = 200L,
			reason = CapturedRadioFactRetentionProofEntity.REASON_DEPENDENCY_CLOSURE,
			effectChecksum = "",
		)
		return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
	}

	private fun radioProjectionId(source: TrackingSource): String = when (source) {
		TrackingSource.WIFI -> SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
		TrackingSource.CELL -> SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
		else -> error("Not a captured radio source")
	}

	private fun radioSourceKind(source: TrackingSource): Int = when (source) {
		TrackingSource.WIFI -> SourceDestinationOwnerEntity.SOURCE_WIFI
		TrackingSource.CELL -> SourceDestinationOwnerEntity.SOURCE_CELL
		else -> error("Not a captured radio source")
	}

	private fun radioProjectionVersion(source: TrackingSource): Int = when (source) {
		TrackingSource.WIFI -> SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
		TrackingSource.CELL -> SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		else -> error("Not a captured radio source")
	}

	private fun radioBindingGeneration(source: TrackingSource): Long = when (source) {
		TrackingSource.WIFI -> SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION
		TrackingSource.CELL -> SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION
		else -> error("Not a captured radio source")
	}

	private fun observation() = LocationObservation(
		fixTimeMs = 100L,
		fixElapsedRealtimeNanos = 100L,
		receivedAtMs = 101L,
		receivedElapsedRealtimeNanos = 101L,
		deliveryAgeMs = 0L,
		latE7 = 1,
		lonE7 = 1,
		rawAltitudeM = null,
		hAccM = 1f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "test",
		acquisitionMode = "BALANCED",
		requestPriority = "BALANCED",
		permissionPrecision = "PRECISE",
		batchIndex = 0,
		batchSize = 1,
		isMock = false,
		ingressDisposition = "ACCEPTED",
		estimatorVersion = 1,
		calibrationVersion = 1,
		createdAt = 101L,
		sourceSignalId = "raw:$EVENT_ID",
		sourceEventId = EVENT_ID,
		clockDomainId = "boot",
		sourceRevision = 9L,
	)

	private fun decision() = LocationObservationDecision(
		observationSourceEventId = EVENT_ID,
		decision = LocationObservationDecision.ACCEPTED,
		writerProjectionId =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
		writerProjectionVersion =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
		writerBindingGeneration =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION,
		writerOwnerGeneration =
			SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
		acceptedSampleSourceSignalId = "protected-location-canonical:$EVENT_ID",
		sourceSignalId = "protected-location-canonical:$EVENT_ID",
		clockDomainId = "boot",
		decidedAtMs = 101L,
		sourceRevision = 9L,
	)

	// Keep one queryable Location fixture with its saved observation, decision, sample and exact canonical
	// receipt together.
	@Suppress("LongMethod")
	private suspend fun installQueryableLocation(
		includeWal: Boolean = true,
		includeReceipt: Boolean = true,
		staleReceipt: Boolean = false,
		storedDecision: LocationObservationDecision = decision(),
		storedSample: LocationSample? = sample(),
		wallTimeUncertaintyMs: Long = 0L,
	) {
		val wal = wal(wallTimeUncertaintyMs)
		if (includeWal) {
			database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(wal)
		}
		database.locationObservationDao().insert(observation())
		database.locationObservationDecisionDao().insert(listOf(storedDecision))
		if (storedSample != null) {
			database.locationSampleDao().insert(storedSample)
		}
		if (!includeReceipt) {
			return
		}

		val persistedObservation = requireNotNull(
			database.locationObservationDao().getBySourceEventId(EVENT_ID),
		)
		val persistedDecision = requireNotNull(
			database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID),
		)
		val persistedSample = database.locationSampleDao()
			.getBySourceEventIdBounded(EVENT_ID, 2)
			.singleOrNull()
		val receiptDecision = if (staleReceipt) {
			persistedDecision.copy(decidedAtMs = persistedDecision.decidedAtMs + 1L)
		} else {
			persistedDecision
		}
		database.sourceProjectionStateDao().saveJoinState(
			SourceProjectionJoinStateEntity(
				projectionId =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
				projectionVersion =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
				stateKey = protectedLocationCanonicalReceiptStateKey(EVENT_ID),
				logicalTrackingId = "logical",
				minimumRequiredOrdinal = 1L,
				payloadVersion = ProtectedLocationCanonicalReceiptCodec.VERSION,
				payload = ProtectedLocationCanonicalReceiptCodec.encode(
					ProtectedLocationCanonicalReceiptPayload(
						sourceEventId = EVENT_ID,
						sourceAdmissionOrdinal = 1L,
						walIntegrityIdentity = wal.integrityIdentity,
						acquisitionMode = "BALANCED",
						requestPriority = "BALANCED",
						requiredAccuracyMeters = 1f,
						policyTier = "BALANCED",
						policyName = "test",
						curationVersion = 1,
						altitudeModelVersion = 1,
						altitudeEstimatorVersion = 1,
						altitudeCalibrationVersion = 1,
						observation =
							persistedObservation.protectedLocationCanonicalReceiptBytes(),
						decision = receiptDecision.protectedLocationCanonicalReceiptBytes(),
						sample = persistedSample?.protectedLocationCanonicalReceiptBytes(),
						curationState = byteArrayOf(1),
					),
				),
				updatedAtMs = 101L,
			),
		)
	}

	private fun payloadState(stateKey: String) = SourceProjectionJoinStateEntity(
		projectionId =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
		projectionVersion =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
		stateKey = stateKey,
		logicalTrackingId = "logical",
		minimumRequiredOrdinal = 1L,
		payloadVersion = 1,
		payload = byteArrayOf(1, 2, 3, 4),
		updatedAtMs = 101L,
	)

	private fun sourceRun(completedAtMs: Long?) = SourceServiceRunEntity(
		serviceRunId = "run",
		logicalTrackingId = "logical",
		state = if (completedAtMs == null) {
			"ACTIVE"
		} else {
			"COMPLETED"
		},
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1L,
		startedElapsedNanos = 1L,
		completedAtMs = completedAtMs,
		completionReason = "TEST".takeIf { completedAtMs != null },
	)

	private fun wal(wallTimeUncertaintyMs: Long = 0L): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = 1L,
			eventId = EVENT_ID,
			providerDedupKey = "location-proof",
			deliveryIdentity = "location-delivery",
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = "logical",
			serviceRunId = "run",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
			sourceInstanceId = "location-instance",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "location-config",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = 1L,
			authorizationFingerprint = "authorization",
			sourceSequence = 1L,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "boot",
			observedElapsedNanos = 100L,
			observedIntervalStartNanos = 100L,
			receivedElapsedNanos = 101L,
			receivedWallTimeMs = 101L,
			wallTimeMs = 100L,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			capturedCollectedDataEpoch = 7L,
			sourcePolicyRevision = 3L,
			captureConsentEpoch = 4L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 100L,
			qualityFlags = 0L,
			qualityConfidence = 1f,
			payloadVersion = 1,
			payload = byteArrayOf(1),
			payloadChecksum = "",
			createdAtMs = 101L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
	}

	private suspend fun installHistoricalLane(
		sourceKind: Int = SourceDestinationOwnerEntity.SOURCE_LOCATION,
		bindingGeneration: Long =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION,
		projectionId: String =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
		projectionVersion: Int =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
		activationOrdinal: Long = 1L,
		throughOrdinal: Long = 1L,
	) {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = sourceKind,
				bindingGeneration = bindingGeneration,
				projectionId = projectionId,
				projectionVersion = projectionVersion,
				captureModeMask = 1L,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 1L,
				activationOrdinal = activationOrdinal,
				contiguousAdmissionOrdinal = throughOrdinal,
				captureAdmissionCutoffOrdinal = throughOrdinal,
				retentionRequired = false,
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				terminalDisposition =
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				terminalAtMs = 2L,
				installedAtMs = 1L,
				updatedAtMs = 2L,
			),
		)
	}

	private fun sample() = LocationSample(
		timeMs = 100L,
		elapsedRealtimeNanos = 100L,
		latE7 = 1,
		lonE7 = 1,
		altitudeM = null,
		rawGpsAltitudeM = null,
		hAccM = 1f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "test",
		quality = SampleQuality.HIGH,
		motionState = MotionState.UNKNOWN,
		policy = "test",
		bucketId = null,
		createdAt = 101L,
		sourceSignalId = "protected-location-canonical:$EVENT_ID",
		sourceEventId = EVENT_ID,
		clockDomainId = "boot",
		sourceRevision = 9L,
	)

	private companion object {
		const val EVENT_ID = "location-event"
	}
}
