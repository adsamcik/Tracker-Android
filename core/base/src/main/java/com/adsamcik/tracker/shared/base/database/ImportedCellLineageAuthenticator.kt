package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity

internal data class AuthenticatedImportedCellRevision(
	val header: ImportedCellEntryRevisionEntity,
	val entry: PortableCapturedCellEntryV1,
)

internal data class AuthenticatedImportedCellLineage(
	val revisions: List<AuthenticatedImportedCellRevision>,
	val receipts: List<ImportedCellReceiptEntity>,
)

internal class ImportedCellLineageFailure(
	val reason: PortableCellImportUnverifiableReason,
) : IllegalArgumentException(reason.name)

/** Reconstructs every retained row back through the one canonical Cell-v1 value contract. */
// One canonical Cell lineage contract owns bounds, receipts, reconstruction and aggregate relationships.
@Suppress("TooManyFunctions")
internal object ImportedCellLineageAuthenticator {
	fun authenticate(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedCellEntryRevisionEntity>,
		receipts: List<ImportedCellReceiptEntity>,
		runs: List<ImportedCellRunEntity>,
		observations: List<ImportedCellObservationEntity>,
		retainedState: SourceEvidenceState? = null,
	): AuthenticatedImportedCellLineage {
		authenticateLineageBounds(headers, receipts, runs, observations)
		if (headers.isEmpty()) {
			if (receipts.isNotEmpty() || runs.isNotEmpty() || observations.isNotEmpty()) {
				corrupt()
			}
			return AuthenticatedImportedCellLineage(emptyList(), emptyList())
		}

		authenticateRevisionHeaders(identity, expectedCollectedDataEpoch, headers, retainedState)
		authenticateRevisionReceipts(identity, expectedCollectedDataEpoch, headers, receipts, retainedState)

		authenticateChildOwnership(headers, runs, observations, expectedCollectedDataEpoch, retainedState)

		val reconstructed = headers.map { header -> reconstructRevision(header, runs, observations) }
		reconstructed.zipWithNext().forEach { (previous, next) ->
			if (!next.entry.isMonotonicCorrectionOf(previous.entry)) {
				corrupt()
			}
		}
		return AuthenticatedImportedCellLineage(reconstructed, receipts)
	}

	private fun authenticateLineageBounds(
		headers: List<ImportedCellEntryRevisionEntity>,
		receipts: List<ImportedCellReceiptEntity>,
		runs: List<ImportedCellRunEntity>,
		observations: List<ImportedCellObservationEntity>,
	) {
		if (headers.size > ImportedCellDao.MAX_REVISIONS_PER_ENTRY) {
			overflow()
		}
		if (receipts.size > ImportedCellDao.MAX_RECEIPTS_PER_ENTRY) {
			overflow()
		}
		if (runs.size > ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE) {
			overflow()
		}
		if (observations.size > ImportedCellDao.MAX_OBSERVATION_ROWS_PER_LINEAGE) {
			overflow()
		}
	}

	private fun authenticateRevisionHeaders(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedCellEntryRevisionEntity>,
		retainedState: SourceEvidenceState?,
	) {
		val expectedRevisions = (1L..headers.size.toLong()).toList()
		if (headers.map { it.importRevision } != expectedRevisions ||
			headers.map { it.contentChecksum }.distinct().size != headers.size ||
			headers.any {
				it.identity != identity || !(retainedState?.acceptsRetainedEpoch(it.collectedDataEpoch) ?:
                    (it.collectedDataEpoch == expectedCollectedDataEpoch))
			}
		) {
			corrupt()
		}
	}

	private fun authenticateRevisionReceipts(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedCellEntryRevisionEntity>,
		receipts: List<ImportedCellReceiptEntity>,
		retainedState: SourceEvidenceState?,
	) {
		if (receipts.any {
			it.entryIdentity != identity || !(retainedState?.acceptsRetainedEpoch(it.collectedDataEpoch) ?:
                (it.collectedDataEpoch == expectedCollectedDataEpoch))
		}) {
			corrupt()
		}
		if (receipts.distinctBy { it.importJobId to it.importEntryKey }.size != receipts.size) {
			corrupt()
		}
		val headerByRevision = headers.associateBy { it.importRevision }
		if (receipts.any { receipt ->
			val header = headerByRevision[receipt.entryImportRevision]
			header == null || receipt.entryContentChecksum != header.contentChecksum
		}) {
			corrupt()
		}
		if (headers.any { header ->
			receipts.none { receipt -> receipt.exactlyOwns(header) }
		}) {
			corrupt()
		}
	}

	private fun ImportedCellReceiptEntity.exactlyOwns(header: ImportedCellEntryRevisionEntity): Boolean =
		entryImportRevision == header.importRevision &&
			importJobId == header.importJobId &&
			importEntryKey == header.importEntryKey &&
			importSourceName == header.importSourceName &&
			receivedAtMs == header.receivedAtMs &&
			entryContentChecksum == header.contentChecksum

	private fun authenticateChildOwnership(
		headers: List<ImportedCellEntryRevisionEntity>,
		runs: List<ImportedCellRunEntity>,
		observations: List<ImportedCellObservationEntity>,
		expectedCollectedDataEpoch: Long,
		retainedState: SourceEvidenceState?,
	) {
		val headerKeys = headers.map { it.identity to it.importRevision }.toSet()
		if (runs.any {
			(it.entryIdentity to it.entryImportRevision) !in headerKeys ||
				!(retainedState?.acceptsRetainedEpoch(it.collectedDataEpoch) ?:
                    (it.collectedDataEpoch == expectedCollectedDataEpoch)) || it.scopeDeletionGeneration != 0L
		}) {
			corrupt()
		}
		val runKeys = runs.map { Triple(it.entryIdentity, it.entryImportRevision, it.identity) }.toSet()
		if (runKeys.size != runs.size) {
			corrupt()
		}
		if (observations.any {
			Triple(it.entryIdentity, it.entryImportRevision, it.runIdentity) !in runKeys
		}) {
			corrupt()
		}
	}

	private fun reconstructRevision(
		header: ImportedCellEntryRevisionEntity,
		runs: List<ImportedCellRunEntity>,
		observations: List<ImportedCellObservationEntity>,
	): AuthenticatedImportedCellRevision {
		val revisionRuns = runs.filter { it.entryImportRevision == header.importRevision }
		if (revisionRuns.size !in 1..CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
			corrupt()
		}
		val values = revisionRuns.map { run ->
			val children = observations
				.filter {
					it.entryImportRevision == header.importRevision && it.runIdentity == run.identity
				}
				.map(::toPortableObservation)
				.sortedWith(PORTABLE_OBSERVATION_ORDER)
			toPortableRun(run, children)
		}.sortedWith(PORTABLE_RUN_ORDER)
		val entry = PortableCapturedCellEntryV1(
			format = header.sourceFormat,
			schemaVersion = header.sourceSchemaVersion,
			identity = PortableCellOpaqueIdentity(header.identity),
			contentChecksum = PortableCellDigest(header.contentChecksum),
			sessionMode = PortableCellSessionMode.valueOf(header.sessionMode),
			startTimeMs = header.startTimeMs,
			endTimeMs = header.endTimeMs,
			subscriptionGrouping = PortableCellSubscriptionGrouping.valueOf(
				header.subscriptionGrouping,
			),
			runs = values,
		)
		authenticateEntryGraph(entry)
		return AuthenticatedImportedCellRevision(header, entry)
	}

	private fun toPortableObservation(row: ImportedCellObservationEntity) =
		PortableCapturedCellObservationV1(
			identity = PortableCellOpaqueIdentity(row.identity),
			semanticRevision = row.semanticRevision,
			supersedesSemanticRevision = row.supersedesSemanticRevision,
			aggregateOwnerIdentity = row.aggregateOwnerIdentity?.let(::PortableCellOpaqueIdentity),
			aggregateOwnerSemanticRevision = row.aggregateOwnerSemanticRevision,
			contentChecksum = PortableCellDigest(row.contentChecksum),
			coverageStartTimeMs = row.coverageStartTimeMs,
			observedTimeMs = row.observedTimeMs,
			latestPossibleTimeMs = row.latestPossibleTimeMs,
			wallTimeUncertaintyMs = row.wallTimeUncertaintyMs,
			storedZoneId = row.storedZoneId,
			childCompleteness = PortableCellChildCompleteness.valueOf(row.childCompleteness),
			subscriptionGrouping = PortableCellSubscriptionGrouping.valueOf(row.subscriptionGrouping),
			submittedChildCount = row.submittedChildCount,
			acceptedChildCount = row.acceptedChildCount,
			staleChildCount = row.staleChildCount,
			futureTimeChildCount = row.futureTimeChildCount,
			missingTimeChildCount = row.missingTimeChildCount,
			clockUnverifiableChildCount = row.clockUnverifiableChildCount,
			authorityMismatchChildCount = row.authorityMismatchChildCount,
			unsupportedTechnologyChildCount = row.unsupportedTechnologyChildCount,
			observationCount = row.observationCount,
			registeredObservationCount = row.registeredObservationCount,
			gsmCount = row.gsmCount,
			cdmaCount = row.cdmaCount,
			wcdmaCount = row.wcdmaCount,
			tdscdmaCount = row.tdscdmaCount,
			lteCount = row.lteCount,
			nrCount = row.nrCount,
			qualityUnknownCount = row.qualityUnknownCount,
			qualityNoneOrUnknownCount = row.qualityNoneOrUnknownCount,
			qualityPoorCount = row.qualityPoorCount,
			qualityModerateCount = row.qualityModerateCount,
			qualityGoodCount = row.qualityGoodCount,
			qualityGreatCount = row.qualityGreatCount,
			weakObservationCount = row.weakObservationCount,
			knownQualityObservationCount = row.knownQualityObservationCount,
			allKnownQualityIsWeak = row.allKnownQualityIsWeak,
			qualityFlags = row.qualityFlags,
			qualityConfidence = row.qualityConfidence,
		).also {
			if (it.latestPossibleTimeMs != exactLatest(it.observedTimeMs, it.wallTimeUncertaintyMs)) {
				corrupt()
			}
		}

	private fun toPortableRun(
		row: ImportedCellRunEntity,
		observations: List<PortableCapturedCellObservationV1>,
	) = PortableCapturedCellRunV1(
		identity = PortableCellOpaqueIdentity(row.identity),
		deletionScopeDigest = PortableCellDeletionScopeDigest(row.deletionScopeDigest),
		contentChecksum = PortableCellDigest(row.contentChecksum),
		startTimeMs = row.startTimeMs,
		endTimeMs = row.endTimeMs,
		captureCoverage = PortableCellCaptureCoverage.valueOf(row.captureCoverage),
		availability = PortableCellRunAvailability.valueOf(row.availability),
		acquisitionCompleteness = PortableCellAcquisitionCompleteness.valueOf(
			row.acquisitionCompleteness,
		),
		retentionLoss = row.retentionLoss,
		subscriptionGrouping = PortableCellSubscriptionGrouping.valueOf(row.subscriptionGrouping),
		observations = observations,
	)

	internal fun authenticateIncoming(entry: PortableCapturedCellEntryV1) {
		if (entry.runs.size > CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
			throw ImportedCellLineageFailure(PortableCellImportUnverifiableReason.RUN_OVERFLOW)
		}
		authenticateIncomingObservationBounds(entry)
		authenticateEntryGraph(entry)
	}

	private fun authenticateIncomingObservationBounds(entry: PortableCapturedCellEntryV1) {
		if (entry.runs.any {
			it.observations.size > CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_RUN
		}) {
			throw ImportedCellLineageFailure(PortableCellImportUnverifiableReason.OBSERVATION_OVERFLOW)
		}
		val observationCount = entry.runs.sumOf { it.observations.size }
		if (observationCount > CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY) {
			throw ImportedCellLineageFailure(
				PortableCellImportUnverifiableReason.TOTAL_OBSERVATION_OVERFLOW,
			)
		}
	}

	private fun authenticateEntryGraph(entry: PortableCapturedCellEntryV1) {
		val runIdentities = entry.runs.map { it.identity.value }
		val scopes = entry.runs.map { it.deletionScopeDigest.value }
		val observations = entry.runs.flatMap { run ->
			run.observations.map { it to run.identity.value }
		}
		val observationIdentities = observations.map { it.first.identity.value }
		if (runIdentities.distinct().size != runIdentities.size ||
			scopes.distinct().size != scopes.size ||
			observationIdentities.distinct().size != observationIdentities.size
		) {
			corrupt()
		}
		val opaque = buildSet {
			add(entry.identity.value)
			addAll(runIdentities)
			addAll(observationIdentities)
		}
		if (opaque.size != 1 + runIdentities.size + observationIdentities.size ||
			scopes.any { it in opaque }
		) {
			corrupt()
		}
		entry.runs.forEach(::authenticateRunObservations)
	}

	private fun authenticateRunObservations(run: PortableCapturedCellRunV1) {
		val byIdentity = run.observations.associateBy { it.identity.value }
		run.observations.forEach { dependent ->
			authenticateObservationBoundsAndCounts(dependent)
			val ownerIdentity = dependent.aggregateOwnerIdentity?.value ?: return@forEach
			val owner = byIdentity[ownerIdentity] ?: corrupt()
			// A later coverage callback reuses content, but keeps its own time and zone.
			if (owner.aggregateOwnerIdentity != null ||
				owner.semanticRevision != dependent.aggregateOwnerSemanticRevision ||
				!dependent.hasSameIdentityFreeAggregateAs(owner)
			) {
				corrupt()
			}
		}
	}

	private fun authenticateObservationBoundsAndCounts(dependent: PortableCapturedCellObservationV1) {
		if (dependent.coverageStartTimeMs > Math.subtractExact(
				dependent.observedTimeMs,
				dependent.wallTimeUncertaintyMs,
			) || dependent.knownQualityObservationCount != Math.subtractExact(
				dependent.observationCount,
				dependent.qualityUnknownCount,
			) || dependent.weakObservationCount != Math.addExact(
				dependent.qualityNoneOrUnknownCount,
				dependent.qualityPoorCount,
			)
		) {
			corrupt()
		}
		if (dependent.latestPossibleTimeMs != exactLatest(
				dependent.observedTimeMs,
				dependent.wallTimeUncertaintyMs,
			)
		) {
			corrupt()
		}
	}

	// Compares the complete immutable aggregate tuple without hiding individual signal counts.
	@Suppress("CyclomaticComplexMethod")
	private fun PortableCapturedCellObservationV1.hasSameIdentityFreeAggregateAs(
		other: PortableCapturedCellObservationV1,
	): Boolean = observationCount == other.observationCount &&
		registeredObservationCount == other.registeredObservationCount && gsmCount == other.gsmCount &&
		cdmaCount == other.cdmaCount && wcdmaCount == other.wcdmaCount &&
		tdscdmaCount == other.tdscdmaCount && lteCount == other.lteCount && nrCount == other.nrCount &&
		qualityUnknownCount == other.qualityUnknownCount &&
		qualityNoneOrUnknownCount == other.qualityNoneOrUnknownCount &&
		qualityPoorCount == other.qualityPoorCount && qualityModerateCount == other.qualityModerateCount &&
		qualityGoodCount == other.qualityGoodCount && qualityGreatCount == other.qualityGreatCount &&
		weakObservationCount == other.weakObservationCount &&
		knownQualityObservationCount == other.knownQualityObservationCount &&
		allKnownQualityIsWeak == other.allKnownQualityIsWeak

	private fun exactLatest(observedTimeMs: Long, uncertaintyMs: Long): Long = try {
		Math.addExact(observedTimeMs, uncertaintyMs)
	} catch (_: ArithmeticException) {
		corrupt()
	}

	private fun overflow(): Nothing = throw ImportedCellLineageFailure(
		PortableCellImportUnverifiableReason.DEPENDENCY_OVERFLOW,
	)

	private fun corrupt(): Nothing = throw ImportedCellLineageFailure(
		PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
	)
}

internal fun PortableCapturedCellEntryV1.isMonotonicCorrectionOf(
	previous: PortableCapturedCellEntryV1,
): Boolean {
	if (isAdditiveReadingSelectionOf(previous)) {
		return true
	}
	if (this == previous || contentChecksum == previous.contentChecksum || !hasSameCellCorrectionEntryShape(previous)) {
		return false
	}
	var changed = false
	for ((current, prior) in runs.zip(previous.runs)) {
		val runChanged = cellRunCorrectionChanged(current, prior) ?: return false
		if (runChanged) {
			changed = true
		}
	}
	return changed
}

// A correction retains this fixed entry identity, measurement bounds and run count.
@Suppress("ComplexCondition")
private fun PortableCapturedCellEntryV1.hasSameCellCorrectionEntryShape(
	previous: PortableCapturedCellEntryV1,
): Boolean = identity == previous.identity && sessionMode == previous.sessionMode &&
	startTimeMs == previous.startTimeMs && endTimeMs == previous.endTimeMs &&
	subscriptionGrouping == previous.subscriptionGrouping && runs.size == previous.runs.size

/** Null rejects a non-monotonic run; false is a valid unchanged run. */
private fun cellRunCorrectionChanged(
	current: PortableCapturedCellRunV1, prior: PortableCapturedCellRunV1,
): Boolean? {
	if (hasConflictingCellRunCorrectionShape(current, prior)) {
		return null
	}
	if (prior.availability == PortableCellRunAvailability.NOT_CAPTURED ||
		prior.availability == PortableCellRunAvailability.NO_RETAINED_OBSERVATION
	) {
		return if (current == prior) {
			false
		} else {
			null
		}
	}
	val observationsChanged = cellObservationCorrectionsChanged(current, prior) ?: return null
	return current.availability != prior.availability || current.retentionLoss != prior.retentionLoss ||
		observationsChanged
}

private fun cellObservationCorrectionsChanged(
	current: PortableCapturedCellRunV1, prior: PortableCapturedCellRunV1,
): Boolean? {
	val priorByIdentity = prior.observations.associateBy { it.identity }
	val currentByIdentity = current.observations.associateBy { it.identity }
	if (!priorByIdentity.keys.containsAll(currentByIdentity.keys)) {
		return null
	}
	var changed = false
	if (priorByIdentity.size != currentByIdentity.size) {
		if (!current.retentionLoss) {
			return null
		}
		changed = true
	}
	for ((identity, observation) in currentByIdentity) {
		val before = priorByIdentity.getValue(identity)
		if (!observation.isValidCellObservationCorrectionOf(before)) {
			return null
		}
		if (observation.semanticRevision != before.semanticRevision) {
			changed = true
		}
	}
	return changed
}

// The full run identity, measurement and capture tuple is immutable; keep its fields visible together.
@Suppress("ComplexCondition")
private fun hasConflictingCellRunCorrectionShape(
	current: PortableCapturedCellRunV1,
	prior: PortableCapturedCellRunV1,
): Boolean = current.identity != prior.identity ||
	current.deletionScopeDigest != prior.deletionScopeDigest ||
	current.startTimeMs != prior.startTimeMs || current.endTimeMs != prior.endTimeMs ||
	current.captureCoverage != prior.captureCoverage ||
	current.acquisitionCompleteness != prior.acquisitionCompleteness ||
	current.subscriptionGrouping != prior.subscriptionGrouping ||
	(prior.retentionLoss && !current.retentionLoss)

// Preserve rejection priority for aggregate owner, unchanged revision and exact material successor.
@Suppress("ReturnCount")
private fun PortableCapturedCellObservationV1.isValidCellObservationCorrectionOf(
	before: PortableCapturedCellObservationV1,
): Boolean {
	if (aggregateOwnerIdentity != before.aggregateOwnerIdentity) {
		return false
	}
	if (semanticRevision == before.semanticRevision) {
		if (this != before) {
			return false
		}
		return true
	}
	val nextRevision = try {
		Math.addExact(before.semanticRevision, 1L)
	} catch (_: ArithmeticException) {
		return false
	}
	if (semanticRevision != nextRevision) {
		return false
	}
	if (!hasMaterialDifferenceFrom(before)) {
		return false
	}
	return true
}

private val PORTABLE_OBSERVATION_ORDER = compareBy<PortableCapturedCellObservationV1>(
	PortableCapturedCellObservationV1::coverageStartTimeMs,
	PortableCapturedCellObservationV1::observedTimeMs,
	{ it.identity.value },
)
private val PORTABLE_RUN_ORDER = compareBy<PortableCapturedCellRunV1>(
	PortableCapturedCellRunV1::startTimeMs,
	{ it.identity.value },
)
