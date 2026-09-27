package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SourceLifecycleProofTest {
	@Test
	fun `proof key keeps exact evidence privacy epochs and run scope`() {
		val key = key()

		key.source shouldBe TrackingSource.STEPS
		key.purpose shouldBe TrackingPurpose.SESSION_CAPTURE
		key.evidenceIdentity shouldBe SourceEvidenceIdentity("event")
		key.sourcePolicyRevision shouldBe 3L
		key.captureConsentEpoch shouldBe 4L
		key.collectedDataEpoch shouldBe 5L
		key.scope shouldBe SourceProofScope.Run("logical", "run")
	}

	@Test
	fun `recording lineage cannot fabricate a materialized fact`() {
		shouldThrow<IllegalArgumentException> {
			SourceProofLineage(
				evidenceIdentity = SourceEvidenceIdentity("event"),
				sourceAdmissionOrdinal = 1L,
				sourceEvidenceRevision = 2L,
				factIdentity = SourceFactIdentity("fact"),
			)
		}
	}

	@Test
	fun `revision one evidence may describe revision three materialized and queryable head`() {
		val lineage = SourceProofLineage(
			evidenceIdentity = SourceEvidenceIdentity("event"),
			sourceAdmissionOrdinal = 1L,
			sourceEvidenceRevision = 2L,
			factIdentity = SourceFactIdentity("fact"),
			semanticRevision = 3L,
			writer = SourceProofWriterIdentity("writer", 1, 4L, 5L),
			cursorRevision = 6L,
			selectedEvidenceLineage = SourceProofSelectedEvidenceLineage(
				semanticRevision = 1L,
				supersededByCurrentSemanticRevision = 3L,
			),
		)
		val materialized = MaterializedProofState(
			lineage,
			SourceProofCoverage.ProviderPoint(7L),
			SourceProofCompleteness.PARTIAL,
		)
		val queryable = QueryableProofState(
			lineage,
			materialized.coverage,
			materialized.completeness,
			TrackingHistoryReadSnapshot(5L, 8L),
		)

		queryable.lineage shouldBe materialized.lineage
		queryable.completeness shouldBe SourceProofCompleteness.PARTIAL
		queryable.lineage.semanticRevision shouldBe 3L
		queryable.lineage.selectedEvidenceLineage shouldBe
			SourceProofSelectedEvidenceLineage(1L, 3L)
	}

	@Test
	fun `current correction query has no superseding relation`() {
		val lineage = SourceProofLineage(
			evidenceIdentity = SourceEvidenceIdentity("event-3"),
			sourceAdmissionOrdinal = 3L,
			sourceEvidenceRevision = 4L,
			factIdentity = SourceFactIdentity("fact"),
			semanticRevision = 3L,
			writer = SourceProofWriterIdentity("writer", 1, 4L, 5L),
			cursorRevision = 3L,
			selectedEvidenceLineage = SourceProofSelectedEvidenceLineage(3L),
		)
		val queryable = QueryableProofState(
			lineage = lineage,
			coverage = SourceProofCoverage.ProviderPoint(7L),
			completeness = SourceProofCompleteness.COMPLETE,
			readSnapshot = TrackingHistoryReadSnapshot(5L, 4L),
		)

		queryable.lineage.selectedEvidenceLineage shouldBe SourceProofSelectedEvidenceLineage(3L)
	}

	@Test
	fun `historical selected evidence must name its contiguous superseding revision`() {
		shouldThrow<IllegalArgumentException> {
			SourceProofLineage(
				evidenceIdentity = SourceEvidenceIdentity("event-1"),
				sourceAdmissionOrdinal = 1L,
				sourceEvidenceRevision = 4L,
				factIdentity = SourceFactIdentity("fact"),
				semanticRevision = 3L,
				writer = SourceProofWriterIdentity("writer", 1, 4L, 5L),
				cursorRevision = 3L,
				selectedEvidenceLineage = SourceProofSelectedEvidenceLineage(1L, 2L),
			)
		}
	}

	@Test
	fun `snapshot revision cannot precede materialized evidence revision`() {
		val lineage = SourceProofLineage(
			evidenceIdentity = SourceEvidenceIdentity("event"),
			sourceAdmissionOrdinal = 1L,
			sourceEvidenceRevision = 8L,
			factIdentity = SourceFactIdentity("fact"),
			semanticRevision = 1L,
			writer = SourceProofWriterIdentity("writer", 1, 1L, 1L),
			cursorRevision = 1L,
		)

		shouldThrow<IllegalArgumentException> {
			QueryableProofState(
				lineage,
				SourceProofCoverage.Unspecified,
				SourceProofCompleteness.UNKNOWN,
				TrackingHistoryReadSnapshot(5L, 7L),
			)
		}
	}

	@Test
	fun `deletion result accepts only terminal lifecycle reasons`() {
		SourceProofResult.Deleted(
			SourceProofStage.QUERYABLE,
			SourceProofReason.RETENTION_EXPIRED,
		).reason shouldBe SourceProofReason.RETENTION_EXPIRED

		shouldThrow<IllegalArgumentException> {
			SourceProofResult.Deleted(
				SourceProofStage.QUERYABLE,
				SourceProofReason.QUERY_MISS,
			)
		}
	}

	private fun key() = SourceProofKey(
		source = TrackingSource.STEPS,
		purpose = TrackingPurpose.SESSION_CAPTURE,
		evidenceIdentity = SourceEvidenceIdentity("event"),
		sourcePolicyRevision = 3L,
		captureConsentEpoch = 4L,
		collectedDataEpoch = 5L,
		scope = SourceProofScope.Run("logical", "run"),
	)
}
