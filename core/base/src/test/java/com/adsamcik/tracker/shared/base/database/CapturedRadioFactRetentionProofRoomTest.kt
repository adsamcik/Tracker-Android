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
	fun `both radio sources persist final retention receipts without lending their original contract`() = runTest {
		for (source in listOf(SourceDestinationOwnerEntity.SOURCE_CELL, SourceDestinationOwnerEntity.SOURCE_WIFI)) {
			val original = proof(source, "original-$source", "1")
			val unsigned = proof(source, "final-$source", "2").copy(writerBindingGeneration = 2L, writerOwnerGeneration = 4L)
			val final = unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
			database.persistCapturedRadioFactRetentionProofs(listOf(original, final))
			val saved = database.capturedRadioFactRetentionProofDao().allForSource(source)
			saved shouldBe listOf(original, final)
			authenticates(saved.first()) shouldBe true
			authenticates(saved.last()) shouldBe true
			authenticates(saved.last(), bindingGeneration = 1L, ownerGeneration = 2L) shouldBe false
			final.authenticatesSourceErase(source, 0L, final.retainedFromMs, final.retainedAtMs) shouldBe true
			final.authenticatesSourceErase(source, 1L, final.retainedFromMs, final.retainedAtMs) shouldBe false
		}
	}

	@Test
	fun `retained radio receipts reject mixed contained and unknown writer contracts`() {
		for (source in listOf(SourceDestinationOwnerEntity.SOURCE_CELL, SourceDestinationOwnerEntity.SOURCE_WIFI)) {
			val original = proof(source, "event-$source", "1")
			kotlin.test.assertFailsWith<IllegalArgumentException> { original.copy(writerBindingGeneration = 2L, writerOwnerGeneration = 2L) }
			kotlin.test.assertFailsWith<IllegalArgumentException> { original.copy(writerBindingGeneration = 1L, writerOwnerGeneration = 4L) }
			kotlin.test.assertFailsWith<IllegalArgumentException> { original.copy(writerBindingGeneration = 2L, writerOwnerGeneration = 5L) }
			kotlin.test.assertFailsWith<IllegalArgumentException> { original.copy(writerBindingGeneration = 3L, writerOwnerGeneration = 6L) }
		}
	}

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

	@Test
	fun `one original callback retains independent session facts for both radio sources`() = runTest {
		for (source in listOf(SourceDestinationOwnerEntity.SOURCE_CELL, SourceDestinationOwnerEntity.SOURCE_WIFI)) {
			val first = proof(source, "shared-$source", "1")
			val unsignedSecond = proof(source, "shared-$source", "2").copy(
				logicalTrackingId = "second-logical", serviceRunId = "second-run",
				logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(source,
					SourceBrokerPurpose.SESSION_CAPTURE, "second-logical", "second-run"))
			val second = unsignedSecond.copy(effectChecksum = unsignedSecond.calculatedEffectChecksum())
			database.persistCapturedRadioFactRetentionProofs(listOf(first, second))
			database.persistCapturedRadioFactRetentionProofs(listOf(first, second))
			val dao = database.capturedRadioFactRetentionProofDao()
			dao.allForSource(source) shouldBe listOf(first, second)
			dao.byEvent(source, first.sourceEventId) shouldBe null
			dao.eventCandidatesForRun(source, first.sourceEventId, "logical", "run", 1L, 1L) shouldBe listOf(first)
			dao.eventCandidatesForRun(source, first.sourceEventId, "second-logical", "second-run", 1L, 1L) shouldBe listOf(second)
			dao.sourceErasePage(source, first.sourceAdmissionOrdinal, first.logicalFactId, 2) shouldBe listOf(second)
		}
		database.capturedRadioFactRetentionProofDao().countAll() shouldBe 4L
	}

	@Test
	fun `same event with conflicting raw identity is rejected without losing its existing sibling`() = runTest {
		val source = SourceDestinationOwnerEntity.SOURCE_CELL
		val first = proof(source, "shared-cell", "1")
		database.persistCapturedRadioFactRetentionProofs(listOf(first))
		val unsigned = proof(source, "shared-cell", "2").copy(walIntegrityIdentity = "5".repeat(64))
		val conflicting = unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
		kotlin.test.assertFailsWith<IllegalStateException> {
			database.persistCapturedRadioFactRetentionProofs(listOf(conflicting))
		}
		database.capturedRadioFactRetentionProofDao().allForSource(source) shouldBe listOf(first)
	}

	@Test
	fun `run lookup refuses to choose one of two conflicting logical facts within the same scope`() = runTest {
		val source = SourceDestinationOwnerEntity.SOURCE_CELL
		val first = proof(source, "ambiguous-cell", "1")
		val second = proof(source, "ambiguous-cell", "2")
		database.persistCapturedRadioFactRetentionProofs(listOf(first, second))
		val candidates = database.capturedRadioFactRetentionProofDao().eventCandidatesForRun(
			source, "ambiguous-cell", "logical", "run", 1L, 1L)
		candidates.size shouldBe 2
		candidates.singleOrNull() shouldBe null
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

	// Keep the complete retained radio row and its checksum together as one fixture value.
	@Suppress("LongMethod")
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
			sourceAdmissionOrdinal = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
				1L
			} else {
				2L
			},
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
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
					1L
				} else {
					2L
				},
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
