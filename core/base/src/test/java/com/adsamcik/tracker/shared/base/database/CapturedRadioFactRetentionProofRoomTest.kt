package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturedRadioFactRetentionProofRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `source erase is isolated and full clear removes every captured radio proof`() = runTest {
		val cell = proof(SourceDestinationOwnerEntity.SOURCE_CELL, "cell-event", "1")
		val wifi = proof(SourceDestinationOwnerEntity.SOURCE_WIFI, "wifi-event", "8")
		database.persistCapturedRadioFactRetentionProofs(listOf(cell, wifi))

		database.capturedRadioFactRetentionProofDao()
			.deleteSource(SourceDestinationOwnerEntity.SOURCE_CELL) shouldBe 1
		database.capturedRadioFactRetentionProofDao()
			.allForSource(SourceDestinationOwnerEntity.SOURCE_CELL) shouldBe emptyList()
		database.capturedRadioFactRetentionProofDao()
			.allForSource(SourceDestinationOwnerEntity.SOURCE_WIFI) shouldBe listOf(wifi)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = 1L,
			retainedFromMs = 100L,
			updatedAtMs = 300L,
		)

		database.capturedRadioFactRetentionProofDao().countAll() shouldBe 0L
	}

	@Test
	fun `retention proof binds writer scope generations and exact cursor revision`() {
		val proof = proof(SourceDestinationOwnerEntity.SOURCE_CELL, "cell-event", "1")

		authenticates(proof) shouldBe true
		authenticates(
			proof,
			bindingGeneration = proof.writerBindingGeneration + 1L,
		) shouldBe false
		authenticates(
			proof,
			ownerGeneration = proof.writerOwnerGeneration + 1L,
		) shouldBe false
		authenticates(
			proof,
			cursorRevision = proof.cursorRevision + 1L,
		) shouldBe false
		authenticates(
			proof,
			scopeDeletionGeneration = proof.scopeDeletionGeneration + 1L,
		) shouldBe false
	}

	private fun authenticates(
		proof: CapturedRadioFactRetentionProofEntity,
		bindingGeneration: Long = proof.writerBindingGeneration,
		ownerGeneration: Long = proof.writerOwnerGeneration,
		cursorRevision: Long = proof.cursorRevision,
		scopeDeletionGeneration: Long = proof.scopeDeletionGeneration,
	): Boolean = proof.authenticatesRequest(
		expectedSourceKind = proof.sourceKind,
		expectedWriterProjectionId = proof.writerProjectionId,
		expectedWriterProjectionVersion = proof.writerProjectionVersion,
		expectedWriterBindingGeneration = bindingGeneration,
		expectedWriterOwnerGeneration = ownerGeneration,
		expectedLogicalFactId = proof.logicalFactId,
		expectedSemanticRevision = proof.semanticRevision,
		expectedCursorRevision = cursorRevision,
		expectedSourceEventId = proof.sourceEventId,
		expectedSourceAdmissionOrdinal = proof.sourceAdmissionOrdinal,
		expectedSourcePolicyRevision = proof.sourcePolicyRevision,
		expectedCaptureConsentEpoch = proof.captureConsentEpoch,
		expectedCollectedDataEpoch = proof.collectedDataEpoch,
		expectedScopeDeletionGeneration = scopeDeletionGeneration,
		expectedLogicalRunScopeDigest = proof.logicalRunScopeDigest,
		currentRetainedFromMs = proof.retainedFromMs,
	)

	private fun proof(
		sourceKind: Int,
		eventId: String,
		hashDigit: String,
	): CapturedRadioFactRetentionProofEntity {
		val projectionId = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
		} else {
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
		}
		val projectionVersion = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		} else {
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
		}
		val bindingGeneration = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
			SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION
		} else {
			SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION
		}
		val logicalFactId = hashDigit.repeat(64)
		val mutationId = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
			CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, 1L)
		} else {
			WifiCapturedFactRevisionIntegrity.mutationId(logicalFactId, 1L)
		}
		val unsigned = CapturedRadioFactRetentionProofEntity(
			sourceKind = sourceKind,
			writerProjectionId = projectionId,
			writerProjectionVersion = projectionVersion,
			writerBindingGeneration = bindingGeneration,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = mutationId,
			factEffectChecksum = "3".repeat(64),
			sourceEventId = eventId,
			sourceAdmissionOrdinal = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) 1L else 2L,
			walIntegrityIdentity = "4".repeat(64),
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 0L,
			logicalTrackingId = "logical",
			serviceRunId = "run",
			scopeDeletionGeneration = 0L,
			logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind,
				SourceBrokerPurpose.SESSION_CAPTURE,
				"logical",
				"run",
			),
			cursorRevision = 1L,
			cursorMutationId = mutationId,
			cursorEffectChecksum = "3".repeat(64),
			cursorSourceAdmissionOrdinal =
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) 1L else 2L,
			cursorUpdatedAtMs = 10L,
			aggregateOwnerLogicalFactId = null,
			aggregateOwnerSemanticRevision = null,
			aggregateOwnerSourceEventId = null,
			aggregateOwnerSourceAdmissionOrdinal = null,
			aggregateOwnerWalIntegrityIdentity = null,
			aggregateOwnerEffectChecksum = null,
			aggregateOwnerCursorRevision = null,
			retainedFromMs = 100L,
			retainedAtMs = 200L,
			reason = CapturedRadioFactRetentionProofEntity.REASON_DIRECT_FLOOR,
			effectChecksum = "",
		)
		return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
	}
}
