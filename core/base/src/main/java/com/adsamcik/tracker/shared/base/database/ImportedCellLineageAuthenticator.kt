package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
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
internal object ImportedCellLineageAuthenticator {
	fun authenticate(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedCellEntryRevisionEntity>,
		receipts: List<ImportedCellReceiptEntity>,
		runs: List<ImportedCellRunEntity>,
		observations: List<ImportedCellObservationEntity>,
	): AuthenticatedImportedCellLineage {
		if (headers.size > ImportedCellDao.MAX_REVISIONS_PER_ENTRY) overflow()
		if (receipts.size > ImportedCellDao.MAX_RECEIPTS_PER_ENTRY) overflow()
		if (runs.size > ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE) overflow()
		if (observations.size > ImportedCellDao.MAX_OBSERVATION_ROWS_PER_LINEAGE) overflow()
		if (headers.isEmpty()) {
			if (receipts.isNotEmpty() || runs.isNotEmpty() || observations.isNotEmpty()) corrupt()
			return AuthenticatedImportedCellLineage(emptyList(), emptyList())
		}

		val expectedRevisions = (1L..headers.size.toLong()).toList()
		if (headers.map { it.importRevision } != expectedRevisions ||
			headers.map { it.contentChecksum }.distinct().size != headers.size ||
			headers.any {
				it.identity != identity || it.collectedDataEpoch != expectedCollectedDataEpoch
			}
		) corrupt()
		if (receipts.any {
			it.entryIdentity != identity || it.collectedDataEpoch != expectedCollectedDataEpoch
		}) corrupt()
		if (receipts.distinctBy { it.importJobId to it.importEntryKey }.size != receipts.size) corrupt()
		val headerByRevision = headers.associateBy { it.importRevision }
		if (receipts.any { receipt ->
			val header = headerByRevision[receipt.entryImportRevision]
			header == null || receipt.entryContentChecksum != header.contentChecksum
		}) corrupt()
		if (headers.any { header ->
			receipts.none { receipt ->
				receipt.entryImportRevision == header.importRevision &&
					receipt.importJobId == header.importJobId &&
					receipt.importEntryKey == header.importEntryKey &&
					receipt.importSourceName == header.importSourceName &&
					receipt.receivedAtMs == header.receivedAtMs &&
					receipt.entryContentChecksum == header.contentChecksum
			}
		}) corrupt()

		val headerKeys = headers.map { it.identity to it.importRevision }.toSet()
		if (runs.any {
			(it.entryIdentity to it.entryImportRevision) !in headerKeys ||
				it.collectedDataEpoch != expectedCollectedDataEpoch || it.scopeDeletionGeneration != 0L
		}) corrupt()
		val runKeys = runs.map { Triple(it.entryIdentity, it.entryImportRevision, it.identity) }.toSet()
		if (runKeys.size != runs.size) corrupt()
		if (observations.any {
			Triple(it.entryIdentity, it.entryImportRevision, it.runIdentity) !in runKeys
		}) corrupt()

		val reconstructed = headers.map { header ->
			val revisionRuns = runs.filter { it.entryImportRevision == header.importRevision }
			if (revisionRuns.size !in 1..CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) corrupt()
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
			AuthenticatedImportedCellRevision(header, entry)
		}
		reconstructed.zipWithNext().forEach { (previous, next) ->
			if (!next.entry.isMonotonicCorrectionOf(previous.entry)) corrupt()
		}
		return AuthenticatedImportedCellLineage(reconstructed, receipts)
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
			if (it.latestPossibleTimeMs != exactLatest(it.observedTimeMs, it.wallTimeUncertaintyMs)) corrupt()
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
		if (entry.runs.any {
			it.observations.size > CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_RUN
		}) throw ImportedCellLineageFailure(PortableCellImportUnverifiableReason.OBSERVATION_OVERFLOW)
		val observationCount = entry.runs.sumOf { it.observations.size }
		if (observationCount > CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY) {
			throw ImportedCellLineageFailure(
				PortableCellImportUnverifiableReason.TOTAL_OBSERVATION_OVERFLOW,
			)
		}
		authenticateEntryGraph(entry)
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
		) corrupt()
		val opaque = buildSet {
			add(entry.identity.value)
			addAll(runIdentities)
			addAll(observationIdentities)
		}
		if (opaque.size != 1 + runIdentities.size + observationIdentities.size ||
			scopes.any { it in opaque }
		) corrupt()
		entry.runs.forEach { run ->
			val byIdentity = run.observations.associateBy { it.identity.value }
			run.observations.forEach { dependent ->
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
				) corrupt()
				if (dependent.latestPossibleTimeMs != exactLatest(
						dependent.observedTimeMs,
						dependent.wallTimeUncertaintyMs,
					)
				) corrupt()
				val ownerIdentity = dependent.aggregateOwnerIdentity?.value ?: return@forEach
				val owner = byIdentity[ownerIdentity] ?: corrupt()
				if (owner.aggregateOwnerIdentity != null ||
					owner.semanticRevision != dependent.aggregateOwnerSemanticRevision ||
					owner.coverageStartTimeMs != dependent.coverageStartTimeMs ||
					owner.observedTimeMs != dependent.observedTimeMs ||
					owner.latestPossibleTimeMs != dependent.latestPossibleTimeMs ||
					owner.wallTimeUncertaintyMs != dependent.wallTimeUncertaintyMs ||
					owner.storedZoneId != dependent.storedZoneId ||
					!dependent.hasSameIdentityFreeAggregateAs(owner)
				) corrupt()
			}
		}
	}

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
	if (this == previous || contentChecksum == previous.contentChecksum) return false
	if (identity != previous.identity || sessionMode != previous.sessionMode ||
		startTimeMs != previous.startTimeMs || endTimeMs != previous.endTimeMs ||
		subscriptionGrouping != previous.subscriptionGrouping || runs.size != previous.runs.size
	) return false
	var changed = false
	for ((current, prior) in runs.zip(previous.runs)) {
		if (current.identity != prior.identity ||
			current.deletionScopeDigest != prior.deletionScopeDigest ||
			current.startTimeMs != prior.startTimeMs || current.endTimeMs != prior.endTimeMs ||
			current.captureCoverage != prior.captureCoverage ||
			current.acquisitionCompleteness != prior.acquisitionCompleteness ||
			current.subscriptionGrouping != prior.subscriptionGrouping ||
			(prior.retentionLoss && !current.retentionLoss)
		) return false
		if (prior.availability == PortableCellRunAvailability.NOT_CAPTURED ||
			prior.availability == PortableCellRunAvailability.NO_RETAINED_OBSERVATION
		) {
			if (current != prior) return false
			continue
		}
		if (current.availability != prior.availability ||
			current.retentionLoss != prior.retentionLoss
		) changed = true
		val priorByIdentity = prior.observations.associateBy { it.identity }
		val currentByIdentity = current.observations.associateBy { it.identity }
		if (!priorByIdentity.keys.containsAll(currentByIdentity.keys)) return false
		if (priorByIdentity.size != currentByIdentity.size) {
			if (!current.retentionLoss) return false
			changed = true
		}
		for ((identity, observation) in currentByIdentity) {
			val before = priorByIdentity.getValue(identity)
			if (observation.aggregateOwnerIdentity != before.aggregateOwnerIdentity) return false
			if (observation.semanticRevision == before.semanticRevision) {
				if (observation != before) return false
				continue
			}
			val nextRevision = try {
				Math.addExact(before.semanticRevision, 1L)
			} catch (_: ArithmeticException) {
				return false
			}
			if (observation.semanticRevision != nextRevision) return false
			if (!observation.hasMaterialDifferenceFrom(before)) return false
			changed = true
		}
	}
	return changed
}

private fun PortableCapturedCellObservationV1.hasMaterialDifferenceFrom(
	previous: PortableCapturedCellObservationV1,
): Boolean = copy(
	semanticRevision = previous.semanticRevision,
	supersedesSemanticRevision = previous.supersedesSemanticRevision,
	contentChecksum = previous.contentChecksum,
) != previous

private val PORTABLE_OBSERVATION_ORDER = compareBy<PortableCapturedCellObservationV1>(
	PortableCapturedCellObservationV1::coverageStartTimeMs,
	PortableCapturedCellObservationV1::observedTimeMs,
	{ it.identity.value },
)
private val PORTABLE_RUN_ORDER = compareBy<PortableCapturedCellRunV1>(
	PortableCapturedCellRunV1::startTimeMs,
	{ it.identity.value },
)
