package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiEffectiveLocalRow
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.measuredStartTimeMs
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason
import com.adsamcik.tracker.stats.api.repository.AmbientWifiAvailability
import com.adsamcik.tracker.stats.api.repository.AmbientWifiCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientWifiDayReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiFact
import com.adsamcik.tracker.stats.api.repository.AmbientWifiGap
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import com.adsamcik.tracker.stats.api.repository.AmbientWifiRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Fixed-query Ambient Wi-Fi reader. Local and imported origins are always explicit. */
@Singleton
// One Room snapshot owns bounded child reads, retained qualification and source publication.
@Suppress("TooManyFunctions")
internal class RoomAmbientWifiRepository(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val retained: RetainedAmbientRadioReader = RetainedAmbientRadioReader(database),
	private val importedRetained: RetainedImportedAmbientRadioReader = RetainedImportedAmbientRadioReader(database),
) : AmbientWifiRepository {
	@Inject constructor(database: AppDatabase, @IoDispatcher ioDispatcher: CoroutineDispatcher,
		boot: BootClockDomainProvider) : this(database, ioDispatcher, RetainedAmbientRadioReader(database, boot))

	override suspend fun read(request: AmbientWifiReadRequest): AmbientWifiReadResult =
		withContext(ioDispatcher) {
			try {
				database.withTransaction { readInTransaction(request) }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientWifiReadResult.StorageUnavailable
			}
		}

	/** Caller owns the snapshot when it also needs the source lineage for export. */
	internal suspend fun readInTransaction(request: AmbientWifiReadRequest): AmbientWifiReadResult =
		readQualifiedInTransaction(request).strictResult()

	/** Display-day history can select an earlier child without widening ordinary export scope. */
	internal suspend fun readObservationWindowInTransaction(request: AmbientWifiReadRequest): AmbientWifiReadResult =
		readQualifiedWindowInTransaction(request, includeObservationOverlap = true).strictResult()

	/** Saved-data status preserves valid siblings and typed gaps on the measured reading window. */
	internal suspend fun readQualifiedObservationWindowInTransaction(request: AmbientWifiReadRequest):
		RetainedAmbientRead<AmbientWifiReadResult, AmbientWifiEffectiveLocalRow> =
		readQualifiedWindowInTransaction(request, includeObservationOverlap = true)

	/** Optional paired consumers keep independently qualified rows and the explicit read gaps. */
	internal suspend fun readQualifiedInTransaction(
		request: AmbientWifiReadRequest,
	): RetainedAmbientRead<AmbientWifiReadResult, AmbientWifiEffectiveLocalRow> =
		readQualifiedWindowInTransaction(request, includeObservationOverlap = false)

	/** Caller owns one Room snapshot and selects real parent identities, never a whole day limit. */
	internal suspend fun readSelectedInTransaction(localFactIds: List<String>, importedFactIds: List<String>,
		localGapIds: List<String>, importedGapIds: List<String>): RetainedAmbientRead<AmbientWifiReadResult,
            AmbientWifiEffectiveLocalRow> {
		require(
			localFactIds.size + importedFactIds.size + localGapIds.size + importedGapIds.size in
				1..MAX_SELECTED_RADIO_ROWS,
		)
		val dao = database.ambientWifiFactDao()
		val local = if (localFactIds.isEmpty()) {
			emptyList()
		} else {
			dao.effectiveLocalByFactIds(localFactIds, localFactIds.size + 1)
		}
		val imported = if (importedFactIds.isEmpty()) {
			emptyList()
		} else {
			dao.effectiveImportedByFactIds(importedFactIds, importedFactIds.size + 1)
		}
		val gaps = if (localGapIds.isEmpty()) {
			emptyList()
		} else {
			dao.gapsByIds(localGapIds, localGapIds.size + 1)
		}
		val importedGaps = if (importedGapIds.isEmpty()) {
			emptyList()
		} else {
			dao.importedGapsByIds(importedGapIds, importedGapIds.size + 1)
		}
		if (local.size > localFactIds.size || imported.size > importedFactIds.size ||
			gaps.size > localGapIds.size || importedGaps.size > importedGapIds.size) {
			return RetainedAmbientRead(AmbientWifiReadResult.DependencyOverflow)
		}
		return qualifyAndCompose(local, imported, gaps, importedGaps, MAX_SELECTED_RADIO_ROWS)
	}

	private suspend fun readQualifiedWindowInTransaction(request: AmbientWifiReadRequest,
		includeObservationOverlap: Boolean): RetainedAmbientRead<AmbientWifiReadResult, AmbientWifiEffectiveLocalRow> {
		val dao = database.ambientWifiFactDao()
		val local = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
			if (includeObservationOverlap) {
				dao.effectiveLocalOverObservationWindow(request.fromInclusiveMs, request.toExclusiveMs,
                    request.limit + 1)
			} else {
				dao.effectiveLocalOverWindow(request.fromInclusiveMs, request.toExclusiveMs, request.limit + 1)
			}
		} else {
			emptyList()
		}
		val imported = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
			if (includeObservationOverlap) {
				dao.importedOverObservationWindow(request.fromInclusiveMs, request.toExclusiveMs, request.limit + 1)
			} else {
				dao.importedOverWindow(request.fromInclusiveMs, request.toExclusiveMs, request.limit + 1)
			}
		} else {
			emptyList()
		}
		val gaps = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
			dao.gapsOverWindow(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		val importedGaps = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
			dao.importedGapsOverWindow(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		return qualifyAndCompose(local, imported, gaps, importedGaps, request.limit)
	}

	override suspend fun readDay(request: AmbientWifiDayReadRequest): AmbientWifiReadResult =
		withContext(ioDispatcher) {
			try {
				database.withTransaction {
					readDayInTransaction(request)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientWifiReadResult.StorageUnavailable
			}
		}

	/** Caller owns the shared history snapshot; this read does not start collection. */
	internal suspend fun readDayInTransaction(request: AmbientWifiDayReadRequest): AmbientWifiReadResult {
		val dao = database.ambientWifiFactDao()
		val local = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
			dao.effectiveLocalForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		val imported = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
			dao.importedForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		val gaps = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
			dao.gapsForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		val importedGaps = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
			dao.importedGapsForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		return qualifyAndCompose(local, imported, gaps, importedGaps, request.limit).strictResult()
	}

	private suspend fun qualifyAndCompose(local: List<AmbientWifiEffectiveLocalRow>,
		imported: List<ImportedAmbientWifiFactEntity>, gaps: List<AmbientWifiGapEntity>,
		importedGaps: List<ImportedAmbientWifiGapEntity>, limit: Int): RetainedAmbientRead<AmbientWifiReadResult,
            AmbientWifiEffectiveLocalRow> {
		if (exceedsReadLimit(local.size, imported.size, gaps.size, importedGaps.size, limit)) {
			return RetainedAmbientRead(AmbientWifiReadResult.DependencyOverflow)
		}
		val native = retained.qualifyWifiInTransaction(local, limit)
		val nativeGaps = retained.qualifyWifiGapsInTransaction(gaps, limit)
		val history = mutableListOf<ImportedAmbientWifiFactEntity>()
		for (ids in imported.map { it.factId }.distinct().chunked(SQLITE_ID_CHUNK_SIZE)) {
			history += database.ambientWifiFactDao().importedFactRevisions(ids,
				MAX_IMPORTED_WIFI_REVISIONS_PER_READ - history.size + 1)
			if (history.size > MAX_IMPORTED_WIFI_REVISIONS_PER_READ) {
				return RetainedAmbientRead(AmbientWifiReadResult.DependencyOverflow)
			}
		}
		val portable = importedRetained.wifiInTransaction(imported, history, importedGaps)
		return RetainedAmbientRead(compose(native.rows, portable.facts, nativeGaps.rows, portable.gaps, limit),
			native.gaps + nativeGaps.gaps + portable.readGaps, native.rows)
	}

	private fun RetainedAmbientRead<AmbientWifiReadResult,
        AmbientWifiEffectiveLocalRow>.strictResult(): AmbientWifiReadResult = when {
		RadioContextReadGapReason.DEPENDENCY_LIMIT in readGaps -> AmbientWifiReadResult.DependencyOverflow
		readGaps.any { it != RadioContextReadGapReason.RETENTION_BOUNDARY } -> AmbientWifiReadResult.StorageUnavailable
		else -> result
	}

	// Ordered collection-budget and row-integrity exits retain their exact typed outcomes.
	@Suppress("ReturnCount")
	private suspend fun compose(
		local: List<AmbientWifiEffectiveLocalRow>,
		imported: List<ImportedAmbientWifiFactEntity>,
		gaps: List<AmbientWifiGapEntity>,
		importedGaps: List<ImportedAmbientWifiGapEntity>,
		limit: Int,
	): AmbientWifiReadResult {
		if (exceedsReadLimit(local.size, imported.size, gaps.size, importedGaps.size, limit)) {
			return AmbientWifiReadResult.DependencyOverflow
		}
		val localChildren = localChildren(local) ?: return AmbientWifiReadResult.DependencyOverflow
		val localFacts = local.map { row ->
			composeLocalFact(row, localChildren[row.fact.logicalFactId to row.fact.semanticRevision].orEmpty())
				?: return AmbientWifiReadResult.StorageUnavailable
		}
		val importedChildren = importedChildren(imported) ?: return AmbientWifiReadResult.DependencyOverflow
		val importedKeys = imported.map { Triple(it.archiveId, it.factId, it.semanticRevision) }.toSet()
		if (!importedKeys.containsAll(importedChildren.keys)) {
			return AmbientWifiReadResult.StorageUnavailable
		}
		val importedFacts = imported.map { parent ->
			composeImportedFact(parent, importedChildren[Triple(parent.archiveId, parent.factId,
				parent.semanticRevision)].orEmpty()) ?: return AmbientWifiReadResult.StorageUnavailable
		}
		return composeSnapshot(localFacts, importedFacts, gaps, importedGaps)
	}

	// Keep every individual and combined collection count in the same pre-read bound.
	@Suppress("ComplexCondition")
	private fun exceedsReadLimit(local: Int, imported: Int, gaps: Int, importedGaps: Int, limit: Int): Boolean =
		local > limit || imported > limit || gaps > limit || importedGaps > limit ||
			local + imported > limit || gaps + importedGaps > limit

	private suspend fun localChildren(local: List<AmbientWifiEffectiveLocalRow>):
		Map<Pair<String, Long>, List<AmbientWifiChildEntity>>? {
		val localIds = local.map { it.fact.logicalFactId }.distinct()
		return localIds.chunked(SQLITE_ID_CHUNK_SIZE).flatMap { ids ->
			val rows = database.radioObservationChildDao()
				.wifiCurrentBackgroundChildrenForFactIds(ids, ids.size * MAX_WIFI_CHILDREN + 1)
			if (rows.size > ids.size * MAX_WIFI_CHILDREN) {
				return null
			}
			rows
		}.groupBy { it.logicalFactId to it.semanticRevision }
	}

	private fun composeLocalFact(
		row: AmbientWifiEffectiveLocalRow,
		children: List<AmbientWifiChildEntity>,
	): AmbientWifiFact? {
		val base = row.toApiFact() ?: return null
		if ((row.fact.purposeDemandId != null || children.isNotEmpty()) &&
			children.size != base.observationCount
		) {
			return null
		}
		if (children.any { !it.matchesParent(row, base) }) {
			return null
		}
		return base.copy(networks = children.map { child -> WifiObservedNetwork(
			child.observedWallTimeMs, child.wallTimeUncertaintyMs,
			child.bssid?.takeIf(String::isNotBlank),
			child.ssid?.takeIf(String::isNotEmpty),
			child.frequencyMhz, child.signalDbm,
		) })
	}

	// One saved child must match its parent, measured interval and exact content digest.
	@Suppress("ComplexCondition")
	private fun AmbientWifiChildEntity.matchesParent(
		row: AmbientWifiEffectiveLocalRow,
		base: AmbientWifiFact,
	): Boolean = !(
		this.writerId != row.fact.writerId || this.writerVersion != row.fact.writerVersion ||
			!radioChildFitsMeasuredInterval(this.observedWallTimeMs, this.wallTimeUncertaintyMs,
				base.coverageStartTimeMs, base.latestPossibleTimeMs) ||
			this.logicalFactId != row.fact.logicalFactId ||
			this.semanticRevision != row.fact.semanticRevision ||
			this.contentChecksum != RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(
				RadioChildOwner(RadioChildPurpose.BACKGROUND, this.writerId,
					this.writerVersion, this.logicalFactId, this.semanticRevision, this.itemIndex),
				this.providerTimestampNanos, this.observedWallTimeMs,
				this.wallTimeUncertaintyMs, this.bssid, this.ssid,
				this.frequencyMhz, this.signalDbm,
			))
	)

	private suspend fun importedChildren(imported: List<ImportedAmbientWifiFactEntity>):
		Map<Triple<String, String, Long>, List<ImportedAmbientWifiChildEntity>>? {
		val importedIds = imported.map(ImportedAmbientWifiFactEntity::factId).distinct()
		return importedIds.chunked(SQLITE_ID_CHUNK_SIZE).flatMap { ids ->
			val rows = database.importedRadioChildDao()
				.wifiCurrentBackgroundChildrenForFactIds(ids, ids.size * MAX_WIFI_CHILDREN + 1)
			if (rows.size > ids.size * MAX_WIFI_CHILDREN) {
				return null
			}
			rows
		}.groupBy { Triple(it.archiveId, it.factId, it.semanticRevision) }
	}

	// Ordered parent, child-count, measured-interval and digest rejections keep distinct exits.
	@Suppress("ReturnCount")
	private fun composeImportedFact(
		parent: ImportedAmbientWifiFactEntity,
		children: List<ImportedAmbientWifiChildEntity>,
	): AmbientWifiFact? {
		val base = parent.toApiFact() ?: return null
		if (children.size > MAX_WIFI_CHILDREN ||
			children.isNotEmpty() && children.size != base.observationCount ||
			children.map(ImportedAmbientWifiChildEntity::itemIndex).distinct().size != children.size
		) {
			return null
		}
		val networks = children.map { child ->
			if (!radioChildFitsMeasuredInterval(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
				base.coverageStartTimeMs, base.latestPossibleTimeMs)) {
				return null
			}
			child.toObservedNetworkIfValid(parent)
				?: return null
		}
		return base.copy(networks = networks)
	}

	private suspend fun composeSnapshot(localFacts: List<AmbientWifiFact>, importedFacts: List<AmbientWifiFact>,
		gaps: List<AmbientWifiGapEntity>, importedGaps: List<ImportedAmbientWifiGapEntity>): AmbientWifiReadResult {
		val facts = (localFacts + importedFacts).sortedWith(
			compareBy<AmbientWifiFact>(AmbientWifiFact::observedTimeMs, AmbientWifiFact::identity),
		)
		val availability = if (
			database.ambientWifiFactDao().latestAuthority()?.let {
				AmbientWifiAuthorityIntegrity.isAuthentic(it) &&
					it.state == AmbientWifiAuthorityEntity.STATE_ACTIVE
			} == true
		) {
			AmbientWifiAvailability.AVAILABLE
		} else {
			AmbientWifiAvailability.DISABLED
		}
		return AmbientWifiReadResult.Snapshot(
			facts,
			(gaps.map {
				if (it.effectChecksum != AmbientWifiFactIntegrity.gapChecksum(it)) {
					return AmbientWifiReadResult.StorageUnavailable
				}
				AmbientWifiGap(
					it.gapId,
					AmbientWifiOrigin.LOCAL_DEVICE,
					it.structuralEpochDay,
					it.gapStartTimeMs,
					it.gapEndTimeMs,
					it.storedZoneId,
					it.reason,
				)
			} + importedGaps.map {
				it.toApiGap() ?: return AmbientWifiReadResult.StorageUnavailable
			}).sortedWith(compareBy(AmbientWifiGap::startTimeMs, AmbientWifiGap::identity)),
			availability,
		)
	}
}

// Maps one immutable joined row; each missing-field rejection preserves the saved record contract.
@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun AmbientWifiEffectiveLocalRow.toApiFact(): AmbientWifiFact? {
	val source = fact
	if (source.effectChecksum != AmbientWifiFactIntegrity.effectChecksum(source)) {
		return null
	}
	val measuredStart = source.measuredStartTimeMs() ?: return null
	val count = source.observationCount ?: ownerObservationCount ?: return null
	val two = source.twoPointFourGhzCount ?: ownerTwoPointFourGhzCount ?: return null
	val five = source.fiveGhzCount ?: ownerFiveGhzCount ?: return null
	val six = source.sixGhzCount ?: ownerSixGhzCount ?: return null
	val other = source.otherBandCount ?: ownerOtherBandCount ?: return null
	val strongest = source.strongestSignalDbm ?: ownerStrongestSignalDbm ?: return null
	val weakest = source.weakestSignalDbm ?: ownerWeakestSignalDbm ?: return null
	val sum = source.signalSumDbm ?: ownerSignalSumDbm ?: return null
	return AmbientWifiFact(
		source.logicalFactId,
		AmbientWifiOrigin.LOCAL_DEVICE,
		measuredStart,
		source.observedWallTimeMs,
		Math.addExact(source.observedWallTimeMs, source.wallTimeUncertaintyMs),
		source.structuralEpochDay,
		source.storedZoneId,
		source.coverageCompleteness.toCoverage(),
		count,
		two,
		five,
		six,
		other,
		strongest,
		weakest,
		sum.toDouble() / count,
		source.semanticRevision,
		source.supersedesSemanticRevision,
	)
}

internal fun ImportedAmbientWifiFactEntity.toApiFact(): AmbientWifiFact? {
	val sourceFact = AmbientWifiFact(
		factId,
		AmbientWifiOrigin.valueOf(portableOrigin),
		coverageStartTimeMs,
		observedTimeMs,
		latestPossibleTimeMs,
		structuralEpochDay,
		storedZoneId,
		coverageCompleteness.toCoverage(),
		observationCount,
		twoPointFourGhzCount,
		fiveGhzCount,
		sixGhzCount,
		otherBandCount,
		strongestSignalDbm,
		weakestSignalDbm,
		meanSignalDbm,
		semanticRevision,
		supersedesSemanticRevision,
	)
	val portable = AmbientWifiPortableIntegrity.createFact(sourceFact)
	if (portable.contentChecksum != contentChecksum ||
		portable.effectChecksum != portableEffectChecksum
	) {
		return null
	}
	return sourceFact.copy(origin = AmbientWifiOrigin.PORTABLE_IMPORT)
}

// One immutable imported child must match its parent identity, index, provider time and exact digest.
@Suppress("ComplexCondition")
internal fun ImportedAmbientWifiChildEntity.toObservedNetworkIfValid(
	parent: ImportedAmbientWifiFactEntity,
): WifiObservedNetwork? {
	if (archiveId != parent.archiveId || factId != parent.factId ||
		semanticRevision != parent.semanticRevision || itemIndex < 0 || providerTimestampNanos <= 0L ||
		contentChecksum != PortableRadioChildIntegrity.digest(
			"wifi", listOf(factId), listOf(semanticRevision, itemIndex,
				providerTimestampNanos, observedWallTimeMs, wallTimeUncertaintyMs,
				bssid, ssid, frequencyMhz, signalDbm),
		)
	) {
		return null
	}
	return runCatching {
		WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
			bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty),
			frequencyMhz, signalDbm)
	}.getOrNull()
}

internal fun ImportedAmbientWifiGapEntity.toApiGap(): AmbientWifiGap? {
	val sourceGap = AmbientWifiGap(
		gapId,
		AmbientWifiOrigin.valueOf(portableOrigin),
		structuralEpochDay,
		startTimeMs,
		endTimeMs,
		storedZoneId,
		reason,
	)
	val portable = AmbientWifiPortableIntegrity.createGap(sourceGap)
	if (portable.contentChecksum != contentChecksum ||
		portable.effectChecksum != portableEffectChecksum
	) {
		return null
	}
	return sourceGap.copy(origin = AmbientWifiOrigin.PORTABLE_IMPORT)
}

private fun String.toCoverage(): AmbientWifiCoverage =
	if (this == AmbientWifiFactRevisionEntity.COMPLETENESS_COMPLETE) {
		AmbientWifiCoverage.COMPLETE
	} else {
		AmbientWifiCoverage.UNVERIFIABLE
	}

private const val MAX_IMPORTED_WIFI_REVISIONS_PER_READ = 65_536
private const val MAX_WIFI_CHILDREN = 64
private const val SQLITE_ID_CHUNK_SIZE = 400

private const val MAX_SELECTED_RADIO_ROWS = 100
