package com.adsamcik.tracker.shared.base.database.data

/** Source-local integrity contract for the retained settlement of a Steps service run. */
object StepsSessionCompletenessIntegrity {
	/**
	 * Accepts both real provider generations and the coordinator's explicit generation-zero
	 * unavailable/unresolved markers. Admission ordinals are global, so their generation-ordered
	 * high-water marks must increase strictly even though provider sequence numbers may restart.
	 */
	fun hasValidTimeline(
		rows: Collection<SourceSessionCompletenessEntity>,
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean {
		if (logicalTrackingId.isBlank() || serviceRunId.isBlank() ||
			rows.any { row -> !row.hasValidShape(logicalTrackingId, serviceRunId) } ||
			rows.map(SourceSessionCompletenessEntity::registrationGeneration).distinct().size != rows.size
		) {
			return false
		}
		val orderedHighWaters = rows.mapNotNull { row ->
			row.lastAdmissionOrdinal?.let { ordinal -> row.registrationGeneration to ordinal }
		}.sortedBy { (generation, _) -> generation }
		return orderedHighWaters.zipWithNext().all { (prior, current) ->
			current.second > prior.second
		}
	}

	/** Requires every row to represent a real provider registration in addition to valid history. */
	fun hasValidRegisteredTimeline(
		rows: Collection<SourceSessionCompletenessEntity>,
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean = rows.all { row -> row.registrationGeneration > 0L } &&
		hasValidTimeline(rows, logicalTrackingId, serviceRunId)

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun SourceSessionCompletenessEntity.hasValidShape(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean {
		val unresolvedStart = unresolvedSequenceStart
		val unresolvedEnd = unresolvedSequenceEnd
		if (this.logicalTrackingId != logicalTrackingId || this.serviceRunId != serviceRunId ||
			sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS || sourceInstanceId.isBlank() ||
			registrationGeneration < 0L || lastAdmissionOrdinal?.let { it <= 0L } == true ||
			lastSourceSequence?.let { it < 0L } == true ||
			(lastAdmissionOrdinal == null) != (lastSourceSequence == null) ||
			(unresolvedStart == null) != (unresolvedEnd == null) ||
			unresolvedStart?.let { start ->
				start <= 0L || start > requireNotNull(unresolvedEnd)
			} == true || providerCoverage !in PROVIDER_COVERAGE_VALUES ||
			stopStatus !in STOP_STATUS_VALUES ||
			stopStatus == COMPLETE_STOP_STATUS && !appDrainComplete || updatedAtMs < 0L
		) {
			return false
		}
		if (registrationGeneration > 0L) {
			return sourceInstanceId !in SYNTHETIC_SOURCE_INSTANCES
		}
		if (lastAdmissionOrdinal != null || lastSourceSequence != null || unresolvedStart != null ||
			unresolvedEnd != null || providerCoverage != UNOBSERVABLE_PROVIDER_COVERAGE
		) {
			return false
		}
		return when (sourceInstanceId) {
			NOT_OWNED_SOURCE_INSTANCE -> stopStatus == COMPLETE_STOP_STATUS && appDrainComplete
			UNRESOLVED_SOURCE_INSTANCE ->
				stopStatus in SYNTHETIC_UNRESOLVED_STOP_STATUSES && !appDrainComplete
			UNAVAILABLE_SOURCE_INSTANCE ->
				stopStatus in SYNTHETIC_UNAVAILABLE_STOP_STATUSES && appDrainComplete
			else -> false
		}
	}

	private const val COMPLETE_STOP_STATUS = "COMPLETE"
	private const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
	private const val UNOBSERVABLE_PROVIDER_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"
	private const val NOT_OWNED_SOURCE_INSTANCE = "not-owned-steps"
	private const val UNRESOLVED_SOURCE_INSTANCE = "unresolved-steps"
	private const val UNAVAILABLE_SOURCE_INSTANCE = "unavailable-steps"
	private val SYNTHETIC_SOURCE_INSTANCES = setOf(
		NOT_OWNED_SOURCE_INSTANCE,
		UNRESOLVED_SOURCE_INSTANCE,
		UNAVAILABLE_SOURCE_INSTANCE,
	)
	private val PROVIDER_COVERAGE_VALUES = setOf(
		COMPLETE_PROVIDER_COVERAGE,
		UNOBSERVABLE_PROVIDER_COVERAGE,
	)
	private val SYNTHETIC_UNRESOLVED_STOP_STATUSES = setOf("PROVIDER_FAILED", "TIMED_OUT")
	private val SYNTHETIC_UNAVAILABLE_STOP_STATUSES = setOf(COMPLETE_STOP_STATUS, "PROVIDER_FAILED")
	private val STOP_STATUS_VALUES = setOf(
		COMPLETE_STOP_STATUS,
		"TIMED_OUT",
		"PERMISSION_LOST",
		"PROVIDER_FAILED",
		"PROCESS_RESTARTED",
	)
}
