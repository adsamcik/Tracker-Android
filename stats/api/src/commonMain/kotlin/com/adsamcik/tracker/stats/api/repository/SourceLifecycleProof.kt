package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource

/** Opaque immutable source evidence identity, normally one durable WAL event id. */
@JvmInline
value class SourceEvidenceIdentity(val value: String) {
	init {
		require(value.isNotBlank())
		require(value.length <= MAX_IDENTITY_LENGTH)
	}

	override fun toString(): String = "SourceEvidenceIdentity"
}

/** Opaque source-local product identity. It is not a session, trip, or location identity. */
@JvmInline
value class SourceFactIdentity(val value: String) {
	init {
		require(value.isNotBlank())
		require(value.length <= MAX_IDENTITY_LENGTH)
	}

	override fun toString(): String = "SourceFactIdentity"
}

private const val MAX_IDENTITY_LENGTH = 512

/**
 * Exact native session or all-day scope within which one source's saved lifecycle evidence is
 * evaluated.
 */
sealed interface SourceProofScope {
	/**
	 * Exact logical tracking and service-run identity for a source lifecycle proof.
	 */
	data class Run(
		val logicalTrackingId: String,
		val serviceRunId: String,
	) : SourceProofScope {
		init {
			require(logicalTrackingId.isNotBlank())
			require(serviceRunId.isNotBlank())
		}
	}

	/**
	 * Exact nonblank all-day source scope identity, independent of sessions.
	 */
	data class Ambient(
		val scopeIdentity: String,
	) : SourceProofScope {
		init {
			require(scopeIdentity.isNotBlank())
		}
	}
}

/**
 * Exact authority requested for a source lifecycle proof.
 *
 * The key deliberately carries no sample count, location, distance, or trip identity. A missing
 * numeric product therefore cannot be interpreted as zero.
 */
data class SourceProofKey(
	val source: TrackingSource,
	val purpose: TrackingPurpose,
	val evidenceIdentity: SourceEvidenceIdentity,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val collectedDataEpoch: Long,
	val scope: SourceProofScope,
) {
	init {
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(collectedDataEpoch >= 0L)
	}
}

/**
 * Separate recording, materialization and query milestones; success in one stage does not imply
 * the others.
 */
enum class SourceProofStage {
	RECORDING,
	MATERIALIZED,
	QUERYABLE,
}

/**
 * Coverage carried by the selected source evidence without exposing source payload values.
 *
 * For a historical correction key, this remains the selected revision's coverage even while the
 * materialized lineage identifies the current product head.
 */
sealed interface SourceProofCoverage {
	/**
	 * Nonempty half-open elapsed-realtime interval in nanoseconds under the provider's clock domain.
	 */
	data class ProviderInterval(
		val startInclusiveNanos: Long,
		val endExclusiveNanos: Long,
	) : SourceProofCoverage {
		init {
			require(startInclusiveNanos >= 0L)
			require(endExclusiveNanos > startInclusiveNanos)
		}
	}

	/**
	 * One provider observation time in elapsed-realtime nanoseconds.
	 */
	data class ProviderPoint(
		val observedAtNanos: Long,
	) : SourceProofCoverage {
		init {
			require(observedAtNanos >= 0L)
		}
	}

	/**
	 * Nonempty half-open observed UTC wall-time interval in milliseconds.
	 */
	data class WallInterval(
		val startInclusiveMs: Long,
		val endExclusiveMs: Long,
	) : SourceProofCoverage {
		init {
			require(startInclusiveMs >= 0L)
			require(endExclusiveMs > startInclusiveMs)
		}
	}

	/**
	 * This evidence does not declare a measurable coverage interval.
	 */
	data object Unspecified : SourceProofCoverage
}

/**
 * Recorded coverage classification, distinguishing complete, partial, confirmed empty and unknown.
 */
enum class SourceProofCompleteness {
	COMPLETE,
	PARTIAL,
	CONFIRMED_EMPTY,
	UNKNOWN,
}

/**
 * Exact projection identifier/version and binding/owner generations for the writer of saved source
 * facts.
 */
data class SourceProofWriterIdentity(
	val projectionId: String,
	val projectionVersion: Int,
	val bindingGeneration: Long,
	val ownerGeneration: Long,
) {
	init {
		require(projectionId.isNotBlank())
		require(projectionVersion > 0)
		require(bindingGeneration > 0L)
		require(ownerGeneration > 0L)
	}
}

/**
 * Exact fact revision selected by the requested evidence.
 *
 * The enclosing [SourceProofLineage] continues to carry the selected evidence identity and
 * admission, while its materialized fact fields describe the current product head.
 */
data class SourceProofSelectedEvidenceLineage(
	val semanticRevision: Long,
	val supersededByCurrentSemanticRevision: Long? = null,
) {
	init {
		require(semanticRevision > 0L)
		require(
			supersededByCurrentSemanticRevision == null ||
				supersededByCurrentSemanticRevision > semanticRevision,
		)
	}
}

/**
 * One stable evidence-to-fact lineage.
 *
 * [sourceAdmissionOrdinal] and [sourceEvidenceRevision] anchor the evidence snapshot. The fact,
 * semantic revision, writer, and cursor describe the current product head after durable
 * materialization. [selectedEvidenceLineage] records which revision the requested evidence
 * produced and whether the current head superseded it.
 */
data class SourceProofLineage(
	val evidenceIdentity: SourceEvidenceIdentity,
	val sourceAdmissionOrdinal: Long,
	val sourceEvidenceRevision: Long,
	val factIdentity: SourceFactIdentity? = null,
	val semanticRevision: Long? = null,
	val writer: SourceProofWriterIdentity? = null,
	val cursorRevision: Long? = null,
	val selectedEvidenceLineage: SourceProofSelectedEvidenceLineage? = null,
) {
	init {
		require(sourceAdmissionOrdinal > 0L)
		require(sourceEvidenceRevision >= 0L)
		val hasMaterializedIdentity = factIdentity != null
		require((semanticRevision != null) == hasMaterializedIdentity)
		require((writer != null) == hasMaterializedIdentity)
		require((cursorRevision != null) == hasMaterializedIdentity)
		require(semanticRevision == null || semanticRevision > 0L)
		require(cursorRevision == null || cursorRevision > 0L)
		selectedEvidenceLineage?.let { selected ->
			require(hasMaterializedIdentity)
			val currentSemanticRevision = requireNotNull(semanticRevision)
			require(selected.semanticRevision <= currentSemanticRevision)
			require(
				if (selected.semanticRevision == currentSemanticRevision) {
					selected.supersededByCurrentSemanticRevision == null
				} else {
					selected.supersededByCurrentSemanticRevision == currentSemanticRevision
				},
			)
		}
	}
}

/**
 * Source lineage and coverage attached to one lifecycle milestone.
 */
sealed interface SourceProofStageState {
	val lineage: SourceProofLineage
	val coverage: SourceProofCoverage
	val completeness: SourceProofCompleteness
}

/**
 * Verified admission-stage lineage, coverage and completeness for the selected source scope.
 */
data class RecordingProofState(
	override val lineage: SourceProofLineage,
	override val coverage: SourceProofCoverage,
	override val completeness: SourceProofCompleteness,
) : SourceProofStageState {
	init {
		require(lineage.factIdentity == null)
	}
}

/**
 * Verified canonical-writer lineage, coverage and completeness for saved facts.
 */
data class MaterializedProofState(
	override val lineage: SourceProofLineage,
	override val coverage: SourceProofCoverage,
	override val completeness: SourceProofCompleteness,
) : SourceProofStageState {
	init {
		require(lineage.factIdentity != null)
	}
}

/**
 * Verified reader-visible lineage and coverage bound to the exact History read snapshot.
 */
data class QueryableProofState(
	override val lineage: SourceProofLineage,
	override val coverage: SourceProofCoverage,
	override val completeness: SourceProofCompleteness,
	val readSnapshot: TrackingHistoryReadSnapshot,
) : SourceProofStageState {
	init {
		require(lineage.factIdentity != null)
		require(readSnapshot.collectedDataEpoch >= 0L)
		require(readSnapshot.sourceEvidenceRevision >= lineage.sourceEvidenceRevision)
	}
}

/**
 * Named provider, policy, permission, writer, query, deletion and retention causes for an unproven
 * source stage.
 */
enum class SourceProofReason {
	EVIDENCE_NOT_FOUND,
	FACT_NOT_FOUND,
	CONTROL_IS_NOT_CAPTURE_EVIDENCE,
	AMBIENT_PROOF_NOT_IMPLEMENTED,
	WRONG_SOURCE,
	WRONG_PURPOSE,
	SCOPE_MISMATCH,
	POLICY_MISMATCH,
	CONSENT_MISMATCH,
	DATA_EPOCH_MISMATCH,
	PRE_EFFECTIVE,
	STALE,
	ACCURACY_UNQUALIFIED,
	BASELINE_ONLY,
	ZERO_DELTA,
	WINDOW_INCOMPLETE,
	REQUEST_ONLY,
	CACHE_ONLY,
	THROTTLED,
	PROVIDER_FAILURE,
	PERMISSION_UNAVAILABLE,
	OS_LIMITED,
	IMMUTABLE_AUTHORITY_MISSING,
	UNSUPPORTED_PAYLOAD,
	MALFORMED_EVIDENCE,
	INTEGRITY_FAILURE,
	MATERIALIZATION_PENDING,
	PROJECTION_RETRYABLE_FAILURE,
	PROJECTION_TERMINAL_FAILURE,
	WRITER_MISMATCH,
	WRITER_GENERATION_MISMATCH,
	CURSOR_BEHIND,
	RECEIPT_MISSING,
	FACT_IDENTITY_MISMATCH,
	PARTIAL_COMPLETENESS,
	QUERY_MISS,
	REPOSITORY_FAILURE,
	DELETED,
	RETENTION_EXPIRED,
}

/**
 * One stage's qualified state or a named incomplete, unavailable or failure boundary.
 */
sealed interface SourceProofResult<out S : SourceProofStageState> {
	/**
	 * Qualified state for this specific lifecycle stage, not an assertion that later stages have
	 * completed.
	 */
	data class Proven<S : SourceProofStageState>(val state: S) : SourceProofResult<S>

	/**
	 * The named lifecycle stage is still settling; any known lineage remains attached.
	 */
	data class Pending(
		val stage: SourceProofStage,
		val reason: SourceProofReason,
		val lineage: SourceProofLineage? = null,
	) : SourceProofResult<Nothing>

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(
		val stage: SourceProofStage,
		val reason: SourceProofReason,
		val lineage: SourceProofLineage? = null,
	) : SourceProofResult<Nothing>

	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(
		val stage: SourceProofStage,
		val reason: SourceProofReason,
		val lineage: SourceProofLineage? = null,
	) : SourceProofResult<Nothing>

	/**
	 * The stage's saved evidence was explicitly deleted or expired by retention.
	 */
	data class Deleted(
		val stage: SourceProofStage,
		val reason: SourceProofReason,
		val lineage: SourceProofLineage? = null,
	) : SourceProofResult<Nothing> {
		init {
			require(reason == SourceProofReason.DELETED ||
				reason == SourceProofReason.RETENTION_EXPIRED)
		}
	}
}

/**
 * Independent recording, materialization and query results for the same source proof key.
 */
data class SourceLifecycleProof(
	val key: SourceProofKey,
	val recording: SourceProofResult<RecordingProofState>,
	val materialized: SourceProofResult<MaterializedProofState>,
	val queryable: SourceProofResult<QueryableProofState>,
)

/**
 * Exact proof key and compatible materialized state supplied to the source-owned saved-fact
 * lookup.
 */
data class SourceFactLookupRequest(
	val key: SourceProofKey,
	val materialized: MaterializedProofState,
) {
	init {
		require(materialized.lineage.evidenceIdentity == key.evidenceIdentity)
	}
}

/**
 * Queryable state or the named scope/read failure; a lookup does not create recording authority.
 */
sealed interface SourceFactLookupResult {
	/**
	 * Contains qualified reader-visible lineage and the exact saved-data snapshot.
	 */
	data class Found(val state: QueryableProofState) : SourceFactLookupResult
	/**
	 * No matching saved source entry or selection remains.
	 */
	data class NotFound(val reason: SourceProofReason = SourceProofReason.QUERY_MISS) :
		SourceFactLookupResult
	/**
	 * The selected fact was deleted or expired by retention.
	 */
	data class Deleted(val reason: SourceProofReason) : SourceFactLookupResult {
		init {
			require(reason == SourceProofReason.DELETED ||
				reason == SourceProofReason.RETENTION_EXPIRED)
		}
	}
	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(val reason: SourceProofReason) : SourceFactLookupResult
	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(val reason: SourceProofReason) : SourceFactLookupResult
}

/**
 * Lifecycle-facing adapter for exact source-fact identity lookup.
 *
 * Production implementations must delegate to [TrackingHistoryRepository.lookupSourceFact] so
 * callers cannot bypass source-union membership, collision, and presentation qualification.
 */
fun interface SourceFactQueryRepository {
	/**
	 * Delegates the exact materialized lineage to the source-qualified History lookup without
	 * activation or mutation.
	 */
	suspend fun lookup(request: SourceFactLookupRequest): SourceFactLookupResult
}
