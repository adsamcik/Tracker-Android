package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunZoneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_OBSERVATION_ORDER
import com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_RUN_ORDER
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import com.adsamcik.tracker.stats.api.repository.isReciprocalCorrectionOf

/** Re-authenticates complete stored Wi-Fi import authority before replay or correction. */
internal object ImportedWifiLineageAuthenticator {
	fun authenticate(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedWifiEntryRevisionEntity>,
		receipts: List<ImportedWifiReceiptEntity>,
		runs: List<ImportedWifiRunEntity>,
		zones: List<ImportedWifiRunZoneEntity>,
		observations: List<ImportedWifiObservationEntity>,
		retainedState: SourceEvidenceState? = null,
	): AuthenticatedImportedWifiLineage {
		if (headers.size > ImportedWifiDao.MAX_REVISIONS_PER_ENTRY) {
			failImportedWifiLineage(Reason.REVISION_OVERFLOW)
		}
		if (receipts.size > ImportedWifiDao.MAX_RECEIPTS_PER_ENTRY) {
			failImportedWifiLineage(Reason.DEPENDENCY_OVERFLOW)
		}
		if (runs.size > ImportedWifiDao.MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE) {
			failImportedWifiLineage(Reason.RUN_OVERFLOW)
		}
		if (zones.size > ImportedWifiDao.MAX_TOTAL_ZONES_PER_ENTRY_LINEAGE) {
			failImportedWifiLineage(Reason.ZONE_OVERFLOW)
		}
		if (observations.size > ImportedWifiDao.MAX_TOTAL_OBSERVATIONS_PER_ENTRY_LINEAGE) {
			failImportedWifiLineage(Reason.OBSERVATION_OVERFLOW)
		}
		authenticateHeaders(identity, expectedCollectedDataEpoch, headers, retainedState)
		authenticateReceipts(identity, expectedCollectedDataEpoch, receipts, retainedState)
		authenticateOwnedChildren(identity, expectedCollectedDataEpoch, runs, zones, observations, retainedState)
		authenticateHierarchy(identity, headers, runs, zones, observations)
		val authenticated = headers.map { header ->
			authenticateRevision(header, runs, zones, observations, receipts)
		}
		if (receipts.any { receipt -> headers.none { it.importRevision == receipt.entryImportRevision } }) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		authenticated.zipWithNext().forEach { (previous, current) ->
			if (!current.entry.isReciprocalCorrectionOf(previous.entry)) {
				failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
			}
		}
		return AuthenticatedImportedWifiLineage(authenticated, receipts)
	}

	private fun authenticateHeaders(
		identity: String,
		expectedEpoch: Long,
		headers: List<ImportedWifiEntryRevisionEntity>,
		retainedState: SourceEvidenceState?,
	) {
		if (headers.any { it.identity != identity ||
			!retainedState.retainsImportedWifiEpoch(it.collectedDataEpoch, expectedEpoch) } ||
			headers.map { it.importRevision } != (1L..headers.size.toLong()).toList() ||
			headers.any { header ->
				header.supersedesImportRevision != header.importRevision.takeIf { it > 1L }?.minus(1L)
			}
		) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun authenticateReceipts(
		identity: String,
		expectedEpoch: Long,
		receipts: List<ImportedWifiReceiptEntity>,
		retainedState: SourceEvidenceState?,
	) {
		if (receipts.any { it.entryIdentity != identity ||
			!retainedState.retainsImportedWifiEpoch(it.collectedDataEpoch, expectedEpoch) } ||
			receipts.distinctBy { it.importJobId to it.importEntryKey }.size != receipts.size
		) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun authenticateOwnedChildren(
		identity: String,
		expectedEpoch: Long,
		runs: List<ImportedWifiRunEntity>,
		zones: List<ImportedWifiRunZoneEntity>,
		observations: List<ImportedWifiObservationEntity>,
		retainedState: SourceEvidenceState?,
	) {
		if (runs.any { it.entryIdentity != identity ||
			!retainedState.retainsImportedWifiEpoch(it.collectedDataEpoch, expectedEpoch) ||
			it.scopeDeletionGeneration != 0L }
		) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (zones.any { it.entryIdentity != identity } || observations.any { it.entryIdentity != identity }
		) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun authenticateHierarchy(
		identity: String,
		headers: List<ImportedWifiEntryRevisionEntity>,
		runs: List<ImportedWifiRunEntity>,
		zones: List<ImportedWifiRunZoneEntity>,
		observations: List<ImportedWifiObservationEntity>,
	) {
		val headerKeys = headers.mapTo(linkedSetOf()) { identity to it.importRevision }
		if (runs.any { (it.entryIdentity to it.entryImportRevision) !in headerKeys } ||
			zones.any { (it.entryIdentity to it.entryImportRevision) !in headerKeys } ||
			observations.any { (it.entryIdentity to it.entryImportRevision) !in headerKeys }
		) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val runKeys = runs.mapTo(linkedSetOf()) { it.entryImportRevision to it.identity }
		if (zones.any { (it.entryImportRevision to it.runIdentity) !in runKeys } ||
			observations.any { (it.entryImportRevision to it.runIdentity) !in runKeys }
		) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun authenticateRevision(
		header: ImportedWifiEntryRevisionEntity,
		runs: List<ImportedWifiRunEntity>,
		zones: List<ImportedWifiRunZoneEntity>,
		observations: List<ImportedWifiObservationEntity>,
		receipts: List<ImportedWifiReceiptEntity>,
	): AuthenticatedImportedWifiRevision {
		val revisionRuns = runs.filter { it.entryImportRevision == header.importRevision }
		if (revisionRuns.any { it.collectedDataEpoch != header.collectedDataEpoch }) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (revisionRuns.size !in 1..ImportedWifiDao.MAX_RUNS_PER_ENTRY ||
			revisionRuns.distinctBy { it.identity }.size != revisionRuns.size ||
			revisionRuns.distinctBy { it.deletionScopeDigest }.size != revisionRuns.size
		) {
			failImportedWifiLineage(Reason.RUN_OVERFLOW)
		}
		val portableRuns = revisionRuns.map { run ->
			authenticateRun(header.importRevision, run, zones, observations)
		}.sortedWith(PORTABLE_WIFI_RUN_ORDER)
		val entry = importedWifiPortableEntry(header, portableRuns)
		authenticateRevisionReceipts(header, receipts)
		return AuthenticatedImportedWifiRevision(header, entry)
	}

	private fun authenticateRun(
		revision: Long,
		run: ImportedWifiRunEntity,
		zones: List<ImportedWifiRunZoneEntity>,
		observations: List<ImportedWifiObservationEntity>,
	): PortableCapturedWifiRunV1 {
		val runZoneIds = retainedRunZones(revision, run, zones)
		val portableObservations = retainedRunObservations(revision, run, observations, runZoneIds)
		authenticateAggregateOwners(portableObservations)
		return importedWifiPortableRun(run, runZoneIds, portableObservations)
	}

	private fun retainedRunZones(
		revision: Long,
		run: ImportedWifiRunEntity,
		zones: List<ImportedWifiRunZoneEntity>,
	): List<String> {
		val runZones = zones.filter {
			it.entryImportRevision == revision && it.runIdentity == run.identity
		}.sortedBy { it.ordinal }
		if (runZones.isEmpty() || runZones.size > ImportedWifiDao.MAX_ZONES_PER_RUN ||
			runZones.map { it.ordinal } != runZones.indices.toList()
		) {
			failImportedWifiLineage(Reason.ZONE_OVERFLOW)
		}
		return runZones.map { it.zoneId }
	}

	private fun retainedRunObservations(
		revision: Long,
		run: ImportedWifiRunEntity,
		observations: List<ImportedWifiObservationEntity>,
		runZoneIds: List<String>,
	): List<PortableCapturedWifiObservationV1> {
		val runObservations = observations.filter {
			it.entryImportRevision == revision && it.runIdentity == run.identity
		}
		if (runObservations.size > ImportedWifiDao.MAX_OBSERVATIONS_PER_ENTRY ||
			runObservations.distinctBy { it.identity }.size != runObservations.size
		) {
			failImportedWifiLineage(Reason.OBSERVATION_OVERFLOW)
		}
		val portableObservations = runObservations.map(::importedWifiPortableObservation)
			.sortedWith(PORTABLE_WIFI_OBSERVATION_ORDER)
		if (portableObservations.any { it.storedZoneId !in runZoneIds }) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return portableObservations
	}

	private fun authenticateRevisionReceipts(
		header: ImportedWifiEntryRevisionEntity,
		receipts: List<ImportedWifiReceiptEntity>,
	) {
		val revisionReceipts = receipts.filter { it.entryImportRevision == header.importRevision }
		if (revisionReceipts.isEmpty() || revisionReceipts.any {
			it.entryContentChecksum != header.contentChecksum
		}) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (revisionReceipts.none {
			it.importJobId == header.importJobId && it.importEntryKey == header.importEntryKey &&
				it.importSourceName == header.importSourceName && it.receivedAtMs == header.receivedAtMs
		}) {
			failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun authenticateAggregateOwners(observations: List<PortableCapturedWifiObservationV1>) {
		val byIdentity = observations.associateBy { it.identity }
		observations.forEach { observation ->
			val ownerIdentity = observation.aggregateOwnerIdentity ?: return@forEach
			val owner = byIdentity[ownerIdentity] ?: failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
			if (owner.aggregateOwnerIdentity != null ||
				owner.semanticRevision != observation.aggregateOwnerSemanticRevision ||
				owner.storedZoneId != observation.storedZoneId ||
				!observation.hasSameAggregateAs(owner)
			) {
				failImportedWifiLineage(Reason.STORED_EVIDENCE_UNVERIFIABLE)
			}
		}
	}

	internal enum class Reason {
		STORED_EVIDENCE_UNVERIFIABLE,
		DEPENDENCY_OVERFLOW,
		RUN_OVERFLOW,
		ZONE_OVERFLOW,
		OBSERVATION_OVERFLOW,
		REVISION_OVERFLOW,
	}
}

private fun importedWifiPortableEntry(
	header: ImportedWifiEntryRevisionEntity,
	portableRuns: List<PortableCapturedWifiRunV1>,
): PortableCapturedWifiEntryV1 {
	if (portableRuns.sumOf { it.observations.size } > ImportedWifiDao.MAX_OBSERVATIONS_PER_ENTRY) {
		failImportedWifiLineage(ImportedWifiLineageAuthenticator.Reason.OBSERVATION_OVERFLOW)
	}
	val authorityValues = buildList {
		add(header.identity)
		portableRuns.forEach { run ->
			add(run.identity.value)
			add(run.deletionScopeDigest.value)
			addAll(run.observations.map { it.identity.value })
		}
	}
	if (authorityValues.distinct().size != authorityValues.size) {
		failImportedWifiLineage(ImportedWifiLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE)
	}
	return try {
		PortableCapturedWifiEntryV1(
			format = header.sourceFormat,
			schemaVersion = header.sourceSchemaVersion,
			identity = PortableWifiOpaqueIdentity(header.identity),
			contentChecksum = PortableWifiDigest(header.contentChecksum),
			sessionMode = PortableWifiSessionMode.valueOf(header.sessionMode),
			startTimeMs = header.startTimeMs,
			endTimeMs = header.endTimeMs,
			runs = portableRuns,
		)
	} catch (_: RuntimeException) {
		failImportedWifiLineage(ImportedWifiLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE)
	}
}

private fun SourceEvidenceState?.retainsImportedWifiEpoch(epoch: Long, expectedEpoch: Long): Boolean =
	this?.acceptsRetainedEpoch(epoch) ?: (epoch == expectedEpoch)

private fun failImportedWifiLineage(reason: ImportedWifiLineageAuthenticator.Reason): Nothing =
	throw ImportedWifiLineageFailure(reason)

private fun importedWifiPortableObservation(row: ImportedWifiObservationEntity): PortableCapturedWifiObservationV1 =
	try {
		require(row.wallTimeUncertaintyMs in 0L..row.observedTimeMs)
		require(row.coverageStartTimeMs in 0L..(row.observedTimeMs - row.wallTimeUncertaintyMs))
		require(row.latestPossibleTimeMs == Math.addExact(row.observedTimeMs, row.wallTimeUncertaintyMs))
		PortableCapturedWifiObservationV1(
			identity = PortableWifiOpaqueIdentity(row.identity),
			semanticRevision = row.semanticRevision,
			supersedesSemanticRevision = row.supersedesSemanticRevision,
			aggregateOwnerIdentity = row.aggregateOwnerIdentity?.let(::PortableWifiOpaqueIdentity),
			aggregateOwnerSemanticRevision = row.aggregateOwnerSemanticRevision,
			contentChecksum = PortableWifiDigest(row.contentChecksum),
			coverageStartTimeMs = row.coverageStartTimeMs,
			observedTimeMs = row.observedTimeMs,
			latestPossibleTimeMs = row.latestPossibleTimeMs,
			wallTimeUncertaintyMs = row.wallTimeUncertaintyMs,
			storedZoneId = row.storedZoneId,
			availability = PortableWifiAvailability.valueOf(row.availability),
			resultCompleteness = PortableWifiResultCompleteness.valueOf(row.resultCompleteness),
			submittedResultCount = row.submittedResultCount,
			acceptedResultCount = row.acceptedResultCount,
			staleResultCount = row.staleResultCount,
			clockUnverifiableResultCount = row.clockUnverifiableResultCount,
			malformedResultCount = row.malformedResultCount,
			observationCount = row.observationCount,
			twoPointFourGhzCount = row.twoPointFourGhzCount,
			fiveGhzCount = row.fiveGhzCount,
			sixGhzCount = row.sixGhzCount,
			otherBandCount = row.otherBandCount,
			strongestSignalDbm = row.strongestSignalDbm,
			weakestSignalDbm = row.weakestSignalDbm,
			meanSignalDbm = row.meanSignalDbm,
			sourceQualityFlags = row.sourceQualityFlags,
			sourceQualityConfidence = row.sourceQualityConfidence,
		)
	} catch (_: RuntimeException) {
		failImportedWifiLineage(ImportedWifiLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE)
	}

private fun importedWifiPortableRun(
	row: ImportedWifiRunEntity,
	zones: List<String>,
	observations: List<PortableCapturedWifiObservationV1>,
): PortableCapturedWifiRunV1 = try {
	PortableCapturedWifiRunV1(
		identity = PortableWifiOpaqueIdentity(row.identity),
		deletionScopeDigest = PortableWifiDeletionScopeDigest(row.deletionScopeDigest),
		contentChecksum = PortableWifiDigest(row.contentChecksum),
		startTimeMs = row.startTimeMs,
		endTimeMs = row.endTimeMs,
		storedZoneIds = zones,
		captureCoverage = PortableWifiCaptureCoverage.valueOf(row.captureCoverage),
		availability = PortableWifiRunAvailability.valueOf(row.availability),
		acquisitionCompleteness = PortableWifiAcquisitionCompleteness.valueOf(row.acquisitionCompleteness),
		hasUnresolvedProviderRange = row.hasUnresolvedProviderRange,
		retentionLoss = row.retentionLoss,
		observations = observations,
	)
} catch (_: RuntimeException) {
	failImportedWifiLineage(ImportedWifiLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE)
}


internal data class AuthenticatedImportedWifiLineage(
	val revisions: List<AuthenticatedImportedWifiRevision>,
	val receipts: List<ImportedWifiReceiptEntity>,
)

internal data class AuthenticatedImportedWifiRevision(
	val header: ImportedWifiEntryRevisionEntity,
	val entry: PortableCapturedWifiEntryV1,
)

internal class ImportedWifiLineageFailure(
	val reason: ImportedWifiLineageAuthenticator.Reason,
) : RuntimeException(null, null, false, false)

private fun PortableCapturedWifiObservationV1.hasSameAggregateAs(
	other: PortableCapturedWifiObservationV1,
): Boolean = observationCount == other.observationCount &&
	twoPointFourGhzCount == other.twoPointFourGhzCount && fiveGhzCount == other.fiveGhzCount &&
	sixGhzCount == other.sixGhzCount && otherBandCount == other.otherBandCount &&
	strongestSignalDbm == other.strongestSignalDbm && weakestSignalDbm == other.weakestSignalDbm &&
	meanSignalDbm.toBits() == other.meanSignalDbm.toBits()
