package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellEffectiveLocalRow
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.CellChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.measuredStartTimeMs
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason
import com.adsamcik.tracker.stats.api.repository.AmbientCellAvailability
import com.adsamcik.tracker.stats.api.repository.AmbientCellCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientCellDayReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellFact
import com.adsamcik.tracker.stats.api.repository.AmbientCellGap
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientCellQualityDistribution
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadResult
import com.adsamcik.tracker.stats.api.repository.AmbientCellRepository
import com.adsamcik.tracker.stats.api.repository.AmbientCellSubscriptionCompleteness
import com.adsamcik.tracker.stats.api.repository.AmbientCellTechnologyMix
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.CellObservedTower
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
// One Room snapshot owns bounded child reads, retained qualification and source publication.
@Suppress("TooManyFunctions")
internal class RoomAmbientCellRepository(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val retained: RetainedAmbientRadioReader = RetainedAmbientRadioReader(database),
	private val importedRetained: RetainedImportedAmbientRadioReader = RetainedImportedAmbientRadioReader(database),
) : AmbientCellRepository {
	@Inject constructor(database: AppDatabase, @IoDispatcher ioDispatcher: CoroutineDispatcher,
		boot: BootClockDomainProvider) : this(database, ioDispatcher, RetainedAmbientRadioReader(database, boot))

	override suspend fun read(request: AmbientCellReadRequest): AmbientCellReadResult =
		withContext(ioDispatcher) {
			try {
				database.withTransaction { readInTransaction(request) }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientCellReadResult.StorageUnavailable
			}
		}

	/** Caller owns the snapshot when it also needs the source lineage for export. */
	internal suspend fun readInTransaction(request: AmbientCellReadRequest): AmbientCellReadResult =
		readQualifiedInTransaction(request).strictResult()

	/** Display-day history can select an earlier child without widening ordinary export scope. */
	internal suspend fun readObservationWindowInTransaction(request: AmbientCellReadRequest): AmbientCellReadResult =
		readQualifiedWindowInTransaction(request, includeObservationOverlap = true).strictResult()

	/** Saved-data status preserves valid siblings and typed gaps on the measured reading window. */
	internal suspend fun readQualifiedObservationWindowInTransaction(request: AmbientCellReadRequest):
		RetainedAmbientRead<AmbientCellReadResult, AmbientCellEffectiveLocalRow> =
		readQualifiedWindowInTransaction(request, includeObservationOverlap = true)

	/** Optional paired consumers keep independently qualified rows and the explicit read gaps. */
	internal suspend fun readQualifiedInTransaction(
		request: AmbientCellReadRequest,
	): RetainedAmbientRead<AmbientCellReadResult, AmbientCellEffectiveLocalRow> =
		readQualifiedWindowInTransaction(request, includeObservationOverlap = false)

	/** Caller owns one Room snapshot and selects real parent identities, never a whole day limit. */
	internal suspend fun readSelectedInTransaction(localFactIds: List<String>, importedFactIds: List<String>,
		localGapIds: List<String>, importedGapIds: List<String>): RetainedAmbientRead<AmbientCellReadResult,
            AmbientCellEffectiveLocalRow> {
		require(
			localFactIds.size + importedFactIds.size + localGapIds.size + importedGapIds.size in
				1..MAX_SELECTED_RADIO_ROWS,
		)
		val dao = database.ambientCellFactDao()
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
			return RetainedAmbientRead(AmbientCellReadResult.DependencyOverflow)
		}
		return qualifyAndCompose(local, imported, gaps, importedGaps, MAX_SELECTED_RADIO_ROWS)
	}

	private suspend fun readQualifiedWindowInTransaction(request: AmbientCellReadRequest,
		includeObservationOverlap: Boolean): RetainedAmbientRead<AmbientCellReadResult, AmbientCellEffectiveLocalRow> {
		val dao = database.ambientCellFactDao()
		val local = if (AmbientCellOrigin.LOCAL_DEVICE in request.origins) {
			if (includeObservationOverlap) {
				dao.effectiveLocalOverObservationWindow(request.fromInclusiveMs, request.toExclusiveMs,
                    request.limit + 1)
			} else {
				dao.effectiveLocalOverWindow(request.fromInclusiveMs, request.toExclusiveMs, request.limit + 1)
			}
		} else {
			emptyList()
		}
		val imported = if (AmbientCellOrigin.PORTABLE_IMPORT in request.origins) {
			if (includeObservationOverlap) {
				dao.importedOverObservationWindow(request.fromInclusiveMs, request.toExclusiveMs, request.limit + 1)
			} else {
				dao.importedOverWindow(request.fromInclusiveMs, request.toExclusiveMs, request.limit + 1)
			}
		} else {
			emptyList()
		}
		val gaps = if (AmbientCellOrigin.LOCAL_DEVICE in request.origins) {
			dao.gapsOverWindow(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		val importedGaps = if (AmbientCellOrigin.PORTABLE_IMPORT in request.origins) {
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

	override suspend fun readDay(request: AmbientCellDayReadRequest): AmbientCellReadResult =
		withContext(ioDispatcher) {
			try {
				database.withTransaction {
					readDayInTransaction(request)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientCellReadResult.StorageUnavailable
			}
		}

	/** Caller owns the shared history snapshot; this read does not start collection. */
	internal suspend fun readDayInTransaction(request: AmbientCellDayReadRequest): AmbientCellReadResult {
		val dao = database.ambientCellFactDao()
		val local = if (AmbientCellOrigin.LOCAL_DEVICE in request.origins) {
			dao.effectiveLocalForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		val imported = if (AmbientCellOrigin.PORTABLE_IMPORT in request.origins) {
			dao.importedForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		val gaps = if (AmbientCellOrigin.LOCAL_DEVICE in request.origins) {
			dao.gapsForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else {
			emptyList()
		}
		val importedGaps = if (AmbientCellOrigin.PORTABLE_IMPORT in request.origins) {
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

	private suspend fun qualifyAndCompose(local: List<AmbientCellEffectiveLocalRow>,
		imported: List<ImportedAmbientCellFactEntity>, gaps: List<AmbientCellGapEntity>,
		importedGaps: List<ImportedAmbientCellGapEntity>, limit: Int): RetainedAmbientRead<AmbientCellReadResult,
            AmbientCellEffectiveLocalRow> {
		if (exceedsReadLimit(local.size, imported.size, gaps.size, importedGaps.size, limit)) {
			return RetainedAmbientRead(AmbientCellReadResult.DependencyOverflow)
		}
		val native = retained.qualifyCellInTransaction(local, limit)
		val nativeGaps = retained.qualifyCellGapsInTransaction(gaps, limit)
		val history = mutableListOf<ImportedAmbientCellFactEntity>()
		for (ids in imported.map { it.factId }.distinct().chunked(CELL_SQLITE_ID_CHUNK_SIZE)) {
			history += database.ambientCellFactDao().importedFactRevisions(ids,
				MAX_IMPORTED_CELL_REVISIONS_PER_READ - history.size + 1)
			if (history.size > MAX_IMPORTED_CELL_REVISIONS_PER_READ) {
				return RetainedAmbientRead(AmbientCellReadResult.DependencyOverflow)
			}
		}
		val portable = importedRetained.cellInTransaction(imported, history, importedGaps)
		return RetainedAmbientRead(compose(native.rows, portable.facts, nativeGaps.rows, portable.gaps, limit),
			native.gaps + nativeGaps.gaps + portable.readGaps, native.rows)
	}

	private fun RetainedAmbientRead<AmbientCellReadResult,
        AmbientCellEffectiveLocalRow>.strictResult(): AmbientCellReadResult = when {
		RadioContextReadGapReason.DEPENDENCY_LIMIT in readGaps -> AmbientCellReadResult.DependencyOverflow
		readGaps.any { it != RadioContextReadGapReason.RETENTION_BOUNDARY } -> AmbientCellReadResult.StorageUnavailable
		else -> result
	}

	// Ordered collection-budget and row-integrity exits retain their exact typed outcomes.
	@Suppress("ReturnCount")
	private suspend fun compose(
		local: List<AmbientCellEffectiveLocalRow>,
		imported: List<ImportedAmbientCellFactEntity>,
		gaps: List<AmbientCellGapEntity>,
		importedGaps: List<ImportedAmbientCellGapEntity>,
		limit: Int,
	): AmbientCellReadResult {
		if (exceedsReadLimit(local.size, imported.size, gaps.size, importedGaps.size, limit)) {
			return AmbientCellReadResult.DependencyOverflow
		}
		val localChildren = localChildren(local) ?: return AmbientCellReadResult.DependencyOverflow
		val localFacts = local.map { row ->
			composeLocalFact(row, localChildren[row.fact.logicalFactId to row.fact.semanticRevision].orEmpty())
				?: return AmbientCellReadResult.StorageUnavailable
		}
		val importedChildren = importedChildren(imported) ?: return AmbientCellReadResult.DependencyOverflow
		val importedKeys = imported.map { Triple(it.archiveId, it.factId, it.semanticRevision) }.toSet()
		if (!importedKeys.containsAll(importedChildren.keys)) {
			return AmbientCellReadResult.StorageUnavailable
		}
		val importedFacts = imported.map { parent ->
			composeImportedFact(parent, importedChildren[Triple(parent.archiveId, parent.factId,
				parent.semanticRevision)].orEmpty()) ?: return AmbientCellReadResult.StorageUnavailable
		}
		return composeSnapshot(localFacts, importedFacts, gaps, importedGaps)
	}

	// Keep every individual and combined collection count in the same pre-read bound.
	@Suppress("ComplexCondition")
	private fun exceedsReadLimit(local: Int, imported: Int, gaps: Int, importedGaps: Int, limit: Int): Boolean =
		local > limit || imported > limit || gaps > limit || importedGaps > limit ||
			local + imported > limit || gaps + importedGaps > limit

	private suspend fun localChildren(local: List<AmbientCellEffectiveLocalRow>):
		Map<Pair<String, Long>, List<AmbientCellChildEntity>>? {
		return local.map { it.fact.logicalFactId }.distinct()
			.chunked(CELL_SQLITE_ID_CHUNK_SIZE).flatMap { ids ->
				val rows = database.radioObservationChildDao().cellCurrentBackgroundChildrenForFactIds(
					ids, ids.size * MAX_CELL_CHILDREN + 1)
				if (rows.size > ids.size * MAX_CELL_CHILDREN) {
					return null
				}
				rows
			}.groupBy { it.logicalFactId to it.semanticRevision }
	}

	private fun composeLocalFact(
		row: AmbientCellEffectiveLocalRow,
		children: List<AmbientCellChildEntity>,
	): AmbientCellFact? {
		val base = row.toApiFact() ?: return null
		if ((row.fact.purposeDemandId != null || children.isNotEmpty()) &&
			children.size != base.observationCount
		) {
			return null
		}
		if (children.any { !it.matchesParent(row, base) }) {
			return null
		}
		return base.copy(towers = children.map { child -> CellObservedTower(
			child.observedWallTimeMs, child.wallTimeUncertaintyMs,
			child.towerIdentity?.takeIf(String::isNotBlank),
			CellHistoryTechnology.valueOf(child.radioType), child.registered, child.signalDbm,
		) })
	}

	// One saved child must match its parent, measured interval and exact content digest.
	private fun AmbientCellChildEntity.matchesParent(
		row: AmbientCellEffectiveLocalRow,
		base: AmbientCellFact,
	): Boolean = !(
		this.writerId != row.fact.writerId || this.writerVersion != row.fact.writerVersion ||
			!radioChildFitsMeasuredInterval(this.observedWallTimeMs, this.wallTimeUncertaintyMs,
				base.coverageStartTimeMs, base.latestPossibleTimeMs) ||
			this.contentChecksum != RadioObservationChildIntegrity.cell(CellChildChecksumInput(
				RadioChildOwner(RadioChildPurpose.BACKGROUND, this.writerId,
					this.writerVersion, this.logicalFactId, this.semanticRevision, this.itemIndex),
				this.providerTimestampNanos, this.observedWallTimeMs,
				this.wallTimeUncertaintyMs, this.towerIdentity, this.radioType,
				this.registered, this.signalDbm))
	)

	private suspend fun importedChildren(imported: List<ImportedAmbientCellFactEntity>):
		Map<Triple<String, String, Long>, List<ImportedAmbientCellChildEntity>>? {
		return imported.map(ImportedAmbientCellFactEntity::factId).distinct()
			.chunked(CELL_SQLITE_ID_CHUNK_SIZE).flatMap { ids ->
				val rows = database.importedRadioChildDao().cellCurrentBackgroundChildrenForFactIds(
					ids, ids.size * MAX_CELL_CHILDREN + 1)
				if (rows.size > ids.size * MAX_CELL_CHILDREN) {
					return null
				}
				rows
			}.groupBy { Triple(it.archiveId, it.factId, it.semanticRevision) }
	}

	// Ordered parent, child-count, measured-interval and digest rejections keep distinct exits.
	@Suppress("ReturnCount")
	private fun composeImportedFact(
		row: ImportedAmbientCellFactEntity,
		children: List<ImportedAmbientCellChildEntity>,
	): AmbientCellFact? {
		val base = row.toApiFact() ?: return null
		if (children.size > MAX_CELL_CHILDREN ||
			children.isNotEmpty() && children.size != base.observationCount ||
			children.map(ImportedAmbientCellChildEntity::itemIndex).distinct().size != children.size
		) {
			return null
		}
		val towers = children.map { child ->
			if (!radioChildFitsMeasuredInterval(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
				base.coverageStartTimeMs, base.latestPossibleTimeMs)) {
				return null
			}
			child.toObservedTowerIfValid(row)
				?: return null
		}
		return base.copy(towers = towers)
	}

	private suspend fun composeSnapshot(localFacts: List<AmbientCellFact>, importedFacts: List<AmbientCellFact>,
		gaps: List<AmbientCellGapEntity>, importedGaps: List<ImportedAmbientCellGapEntity>): AmbientCellReadResult {
		val facts = (localFacts + importedFacts)
			.sortedWith(compareBy<AmbientCellFact>(
				AmbientCellFact::observedTimeMs,
				AmbientCellFact::identity,
			))
		val availability = if (
			database.ambientCellFactDao().latestAuthority()?.let {
				AmbientCellAuthorityIntegrity.isAuthentic(it) &&
					it.state == AmbientCellAuthorityEntity.STATE_ACTIVE
			} == true
		) {
			AmbientCellAvailability.AVAILABLE
		} else {
			AmbientCellAvailability.DISABLED
		}
		return AmbientCellReadResult.Snapshot(
			facts,
			(gaps.map {
				if (it.effectChecksum != AmbientCellFactIntegrity.gapChecksum(it)) {
					return AmbientCellReadResult.StorageUnavailable
				}
				AmbientCellGap(
					it.gapId,
					AmbientCellOrigin.LOCAL_DEVICE,
					it.structuralEpochDay,
					it.gapStartTimeMs,
					it.gapEndTimeMs,
					it.storedZoneId,
					it.reason,
				)
			} + importedGaps.map {
				it.toApiGap() ?: return AmbientCellReadResult.StorageUnavailable
			}).sortedWith(compareBy(AmbientCellGap::startTimeMs, AmbientCellGap::identity)),
			availability,
		)
	}
}

// One immutable imported child must match its parent identity, index, provider time and exact digest.
@Suppress("ComplexCondition")
internal fun ImportedAmbientCellChildEntity.toObservedTowerIfValid(
	parent: ImportedAmbientCellFactEntity,
): CellObservedTower? {
	if (archiveId != parent.archiveId || factId != parent.factId ||
		semanticRevision != parent.semanticRevision || itemIndex < 0 || providerTimestampNanos <= 0L ||
		contentChecksum != PortableRadioChildIntegrity.digest("cell", listOf(factId),
			listOf(semanticRevision, itemIndex, providerTimestampNanos, observedWallTimeMs,
				wallTimeUncertaintyMs, towerIdentity, radioType, registered, signalDbm))
	) {
		return null
	}
	return runCatching { CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
		towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType),
		registered, signalDbm) }.getOrNull()
}

// Maps one immutable joined row; each missing-field rejection preserves the saved record contract.
@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun AmbientCellEffectiveLocalRow.toApiFact(): AmbientCellFact? {
	val source = fact
	if (source.effectChecksum != AmbientCellFactIntegrity.effectChecksum(source)) {
		return null
	}
	val measuredStart = source.measuredStartTimeMs() ?: return null
	val count = source.observationCount ?: ownerObservationCount ?: return null
	return AmbientCellFact(
		source.logicalFactId,
		AmbientCellOrigin.LOCAL_DEVICE,
		measuredStart,
		source.observedWallTimeMs,
		Math.addExact(source.observedWallTimeMs, source.wallTimeUncertaintyMs),
		source.structuralEpochDay,
		source.storedZoneId,
		source.coverageCompleteness.toCellCoverage(),
		source.subscriptionCompleteness.toSubscriptionCompleteness(),
		count,
		source.registeredObservationCount ?: ownerRegisteredObservationCount ?: return null,
		AmbientCellTechnologyMix(
			source.gsmCount ?: ownerGsmCount ?: return null,
			source.cdmaCount ?: ownerCdmaCount ?: return null,
			source.wcdmaCount ?: ownerWcdmaCount ?: return null,
			source.tdscdmaCount ?: ownerTdscdmaCount ?: return null,
			source.lteCount ?: ownerLteCount ?: return null,
			source.nrCount ?: ownerNrCount ?: return null,
		),
		AmbientCellQualityDistribution(
			source.qualityUnknownCount ?: ownerQualityUnknownCount ?: return null,
			source.qualityNoneOrUnknownCount ?: ownerQualityNoneOrUnknownCount ?: return null,
			source.qualityPoorCount ?: ownerQualityPoorCount ?: return null,
			source.qualityModerateCount ?: ownerQualityModerateCount ?: return null,
			source.qualityGoodCount ?: ownerQualityGoodCount ?: return null,
			source.qualityGreatCount ?: ownerQualityGreatCount ?: return null,
		),
		source.semanticRevision,
		source.supersedesSemanticRevision,
	)
}

internal fun ImportedAmbientCellFactEntity.toApiFact(): AmbientCellFact? {
	val sourceFact = AmbientCellFact(
		factId,
		AmbientCellOrigin.valueOf(portableOrigin),
		coverageStartTimeMs,
		observedTimeMs,
		latestPossibleTimeMs,
		structuralEpochDay,
		storedZoneId,
		coverageCompleteness.toCellCoverage(),
		subscriptionCompleteness.toSubscriptionCompleteness(),
		observationCount,
		registeredObservationCount,
		AmbientCellTechnologyMix(gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount),
		AmbientCellQualityDistribution(
			qualityUnknownCount,
			qualityNoneOrUnknownCount,
			qualityPoorCount,
			qualityModerateCount,
			qualityGoodCount,
			qualityGreatCount,
		),
		semanticRevision,
		supersedesSemanticRevision,
	)
	val portable = AmbientCellPortableIntegrity.createFact(sourceFact)
	if (portable.contentChecksum != contentChecksum ||
		portable.effectChecksum != portableEffectChecksum
	) {
		return null
	}
	return sourceFact.copy(origin = AmbientCellOrigin.PORTABLE_IMPORT)
}

internal fun ImportedAmbientCellGapEntity.toApiGap(): AmbientCellGap? {
	val sourceGap = AmbientCellGap(
		gapId,
		AmbientCellOrigin.valueOf(portableOrigin),
		structuralEpochDay,
		startTimeMs,
		endTimeMs,
		storedZoneId,
		reason,
	)
	val portable = AmbientCellPortableIntegrity.createGap(sourceGap)
	if (portable.contentChecksum != contentChecksum ||
		portable.effectChecksum != portableEffectChecksum
	) {
		return null
	}
	return sourceGap.copy(origin = AmbientCellOrigin.PORTABLE_IMPORT)
}

private fun String.toCellCoverage(): AmbientCellCoverage =
	if (this == AmbientCellFactRevisionEntity.COMPLETENESS_COMPLETE) {
		AmbientCellCoverage.COMPLETE
	} else {
		AmbientCellCoverage.UNVERIFIABLE
	}

private fun String.toSubscriptionCompleteness(): AmbientCellSubscriptionCompleteness = when (this) {
	AmbientCellFactRevisionEntity.SUBSCRIPTION_COMPLETE -> AmbientCellSubscriptionCompleteness.COMPLETE
	AmbientCellFactRevisionEntity.SUBSCRIPTION_PARTIAL -> AmbientCellSubscriptionCompleteness.PARTIAL
	else -> AmbientCellSubscriptionCompleteness.UNKNOWN
}

private const val MAX_IMPORTED_CELL_REVISIONS_PER_READ = 65_536
private const val CELL_SQLITE_ID_CHUNK_SIZE = 400
private const val MAX_CELL_CHILDREN = 256

private const val MAX_SELECTED_RADIO_ROWS = 100
