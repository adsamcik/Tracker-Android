package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunZoneEntity
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
	@Suppress("LongMethod", "ComplexCondition")
	fun authenticate(
		identity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedWifiEntryRevisionEntity>,
		receipts: List<ImportedWifiReceiptEntity>,
		runs: List<ImportedWifiRunEntity>,
		zones: List<ImportedWifiRunZoneEntity>,
		observations: List<ImportedWifiObservationEntity>,
	): AuthenticatedImportedWifiLineage {
		if (headers.size > ImportedWifiDao.MAX_REVISIONS_PER_ENTRY) fail(Reason.REVISION_OVERFLOW)
		if (receipts.size > ImportedWifiDao.MAX_RECEIPTS_PER_ENTRY) fail(Reason.DEPENDENCY_OVERFLOW)
		if (runs.size > ImportedWifiDao.MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE) fail(Reason.RUN_OVERFLOW)
		if (zones.size > ImportedWifiDao.MAX_TOTAL_ZONES_PER_ENTRY_LINEAGE) fail(Reason.ZONE_OVERFLOW)
		if (observations.size > ImportedWifiDao.MAX_TOTAL_OBSERVATIONS_PER_ENTRY_LINEAGE) {
			fail(Reason.OBSERVATION_OVERFLOW)
		}
		if (headers.any { it.identity != identity || it.collectedDataEpoch != expectedCollectedDataEpoch } ||
			headers.map { it.importRevision } != (1L..headers.size.toLong()).toList() ||
			headers.any { header ->
				header.supersedesImportRevision != header.importRevision.takeIf { it > 1L }?.minus(1L)
			} || receipts.any {
				it.entryIdentity != identity || it.collectedDataEpoch != expectedCollectedDataEpoch
			} || receipts.distinctBy { it.importJobId to it.importEntryKey }.size != receipts.size ||
			runs.any { it.entryIdentity != identity || it.collectedDataEpoch != expectedCollectedDataEpoch ||
				it.scopeDeletionGeneration != 0L } ||
			zones.any { it.entryIdentity != identity } || observations.any { it.entryIdentity != identity }
		) fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)

		val headerKeys = headers.mapTo(linkedSetOf()) { identity to it.importRevision }
		if (runs.any { (it.entryIdentity to it.entryImportRevision) !in headerKeys } ||
			zones.any { (it.entryIdentity to it.entryImportRevision) !in headerKeys } ||
			observations.any { (it.entryIdentity to it.entryImportRevision) !in headerKeys }
		) fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		val runKeys = runs.mapTo(linkedSetOf()) { it.entryImportRevision to it.identity }
		if (zones.any { (it.entryImportRevision to it.runIdentity) !in runKeys } ||
			observations.any { (it.entryImportRevision to it.runIdentity) !in runKeys }
		) fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)

		val authenticated = headers.map { header ->
			val revisionRuns = runs.filter { it.entryImportRevision == header.importRevision }
			if (revisionRuns.size !in 1..ImportedWifiDao.MAX_RUNS_PER_ENTRY ||
				revisionRuns.distinctBy { it.identity }.size != revisionRuns.size ||
				revisionRuns.distinctBy { it.deletionScopeDigest }.size != revisionRuns.size
			) fail(Reason.RUN_OVERFLOW)
			val portableRuns = revisionRuns.map { run ->
				val runZones = zones.filter {
					it.entryImportRevision == header.importRevision && it.runIdentity == run.identity
				}.sortedBy { it.ordinal }
				if (runZones.isEmpty() || runZones.size > ImportedWifiDao.MAX_ZONES_PER_RUN ||
					runZones.map { it.ordinal } != runZones.indices.toList()
				) fail(Reason.ZONE_OVERFLOW)
				val runZoneIds = runZones.map { it.zoneId }
				val runObservations = observations.filter {
					it.entryImportRevision == header.importRevision && it.runIdentity == run.identity
				}
				if (runObservations.size > ImportedWifiDao.MAX_OBSERVATIONS_PER_ENTRY ||
					runObservations.distinctBy { it.identity }.size != runObservations.size
				) fail(Reason.OBSERVATION_OVERFLOW)
				val portableObservations = runObservations.map(::toPortableObservation)
					.sortedWith(PORTABLE_WIFI_OBSERVATION_ORDER)
				if (portableObservations.any { it.storedZoneId !in runZoneIds }) {
					fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
				}
				authenticateAggregateOwners(portableObservations)
				toPortableRun(run, runZoneIds, portableObservations)
			}.sortedWith(PORTABLE_WIFI_RUN_ORDER)
			if (portableRuns.sumOf { it.observations.size } >
				ImportedWifiDao.MAX_OBSERVATIONS_PER_ENTRY
			) fail(Reason.OBSERVATION_OVERFLOW)
			val authorityValues = buildList {
				add(header.identity)
				portableRuns.forEach { run ->
					add(run.identity.value)
					add(run.deletionScopeDigest.value)
					addAll(run.observations.map { it.identity.value })
				}
			}
			if (authorityValues.distinct().size != authorityValues.size) {
				fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			val entry = try {
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
				fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			val revisionReceipts = receipts.filter { it.entryImportRevision == header.importRevision }
			if (revisionReceipts.isEmpty() || revisionReceipts.any {
				it.entryContentChecksum != header.contentChecksum
			} || revisionReceipts.none {
				it.importJobId == header.importJobId && it.importEntryKey == header.importEntryKey &&
					it.importSourceName == header.importSourceName && it.receivedAtMs == header.receivedAtMs
			}) fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
			AuthenticatedImportedWifiRevision(header, entry)
		}
		if (receipts.any { receipt -> headers.none { it.importRevision == receipt.entryImportRevision } }) {
			fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		authenticated.zipWithNext().forEach { (previous, current) ->
			if (!current.entry.isReciprocalCorrectionOf(previous.entry)) {
				fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
			}
		}
		return AuthenticatedImportedWifiLineage(authenticated, receipts)
	}

	private fun authenticateAggregateOwners(observations: List<PortableCapturedWifiObservationV1>) {
		val byIdentity = observations.associateBy { it.identity }
		observations.forEach { observation ->
			val ownerIdentity = observation.aggregateOwnerIdentity ?: return@forEach
			val owner = byIdentity[ownerIdentity] ?: fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
			if (owner.aggregateOwnerIdentity != null ||
				owner.semanticRevision != observation.aggregateOwnerSemanticRevision ||
				owner.storedZoneId != observation.storedZoneId ||
				!observation.hasSameAggregateAs(owner)
			) fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun toPortableObservation(row: ImportedWifiObservationEntity): PortableCapturedWifiObservationV1 =
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
			fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
		}

	private fun toPortableRun(
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
		fail(Reason.STORED_EVIDENCE_UNVERIFIABLE)
	}

	private fun fail(reason: Reason): Nothing = throw ImportedWifiLineageFailure(reason)

	internal enum class Reason {
		STORED_EVIDENCE_UNVERIFIABLE,
		DEPENDENCY_OVERFLOW,
		RUN_OVERFLOW,
		ZONE_OVERFLOW,
		OBSERVATION_OVERFLOW,
		REVISION_OVERFLOW,
	}
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
