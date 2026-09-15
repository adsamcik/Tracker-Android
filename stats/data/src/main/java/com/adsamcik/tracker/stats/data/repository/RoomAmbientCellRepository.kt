package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellEffectiveLocalRow
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
internal class RoomAmbientCellRepository @Inject constructor(
	private val database: AppDatabase,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AmbientCellRepository {
	override suspend fun read(request: AmbientCellReadRequest): AmbientCellReadResult =
		withContext(ioDispatcher) {
			try {
				database.withTransaction {
					val dao = database.ambientCellFactDao()
					val local = if (AmbientCellOrigin.LOCAL_DEVICE in request.origins) {
						dao.effectiveLocalOverWindow(
							request.fromInclusiveMs,
							request.toExclusiveMs,
							request.limit + 1,
						)
					} else emptyList()
					val imported = if (AmbientCellOrigin.PORTABLE_IMPORT in request.origins) {
						dao.importedOverWindow(
							request.fromInclusiveMs,
							request.toExclusiveMs,
							request.limit + 1,
						)
					} else emptyList()
					val gaps = if (AmbientCellOrigin.LOCAL_DEVICE in request.origins) {
						dao.gapsOverWindow(
							request.fromInclusiveMs,
							request.toExclusiveMs,
							request.limit + 1,
						)
					} else emptyList()
					val importedGaps = if (AmbientCellOrigin.PORTABLE_IMPORT in request.origins) {
						dao.importedGapsOverWindow(
							request.fromInclusiveMs,
							request.toExclusiveMs,
							request.limit + 1,
						)
					} else emptyList()
					compose(local, imported, gaps, importedGaps, request.limit)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientCellReadResult.StorageUnavailable
			}
		}

	override suspend fun readDay(request: AmbientCellDayReadRequest): AmbientCellReadResult =
		withContext(ioDispatcher) {
			try {
				database.withTransaction {
					val dao = database.ambientCellFactDao()
					val local = if (AmbientCellOrigin.LOCAL_DEVICE in request.origins) {
						dao.effectiveLocalForDay(
							request.structuralEpochDay,
							request.storedZoneId,
							request.limit + 1,
						)
					} else emptyList()
					val imported = if (AmbientCellOrigin.PORTABLE_IMPORT in request.origins) {
						dao.importedForDay(
							request.structuralEpochDay,
							request.storedZoneId,
							request.limit + 1,
						)
					} else emptyList()
					val fromMs = local.minOfOrNull { it.fact.structuralDayStartTimeMs }
						?: imported.minOfOrNull(ImportedAmbientCellFactEntity::coverageStartTimeMs)
						?: 0L
					val toMs = local.maxOfOrNull { it.fact.structuralDayEndTimeMs }
						?: imported.maxOfOrNull(ImportedAmbientCellFactEntity::latestPossibleTimeMs)
						?.plus(1L)
						?: 1L
					val gaps = if (AmbientCellOrigin.LOCAL_DEVICE in request.origins) {
						dao.gapsOverWindow(fromMs, toMs, request.limit + 1)
					} else emptyList()
					val importedGaps = if (AmbientCellOrigin.PORTABLE_IMPORT in request.origins) {
						dao.importedGapsForDay(
							request.structuralEpochDay,
							request.storedZoneId,
							request.limit + 1,
						)
					} else emptyList()
					compose(local, imported, gaps, importedGaps, request.limit)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientCellReadResult.StorageUnavailable
			}
		}

	private suspend fun compose(
		local: List<AmbientCellEffectiveLocalRow>,
		imported: List<ImportedAmbientCellFactEntity>,
		gaps: List<AmbientCellGapEntity>,
		importedGaps: List<ImportedAmbientCellGapEntity>,
		limit: Int,
	): AmbientCellReadResult {
		if (local.size > limit || imported.size > limit || gaps.size > limit ||
			importedGaps.size > limit || local.size + imported.size > limit ||
			gaps.size + importedGaps.size > limit
		) return AmbientCellReadResult.DependencyOverflow
		val localFacts = local.map { it.toApiFact() ?: return AmbientCellReadResult.StorageUnavailable }
		val importedHistory = if (imported.isEmpty()) emptyList() else {
			buildList {
				imported.map(ImportedAmbientCellFactEntity::factId).distinct()
					.chunked(CELL_SQLITE_ID_CHUNK_SIZE).forEach { factIds ->
						addAll(
							database.ambientCellFactDao().importedFactRevisions(
								factIds,
								MAX_IMPORTED_CELL_REVISIONS_PER_READ + 1 - size,
							),
						)
						if (size > MAX_IMPORTED_CELL_REVISIONS_PER_READ) return@buildList
					}
			}
		}
		if (importedHistory.size > MAX_IMPORTED_CELL_REVISIONS_PER_READ) {
			return AmbientCellReadResult.DependencyOverflow
		}
		val importedHistoryByFact = importedHistory.groupBy(ImportedAmbientCellFactEntity::factId)
		if (imported.any { latest ->
				val history = importedHistoryByFact[latest.factId].orEmpty()
				history.size.toLong() != latest.semanticRevision ||
					history.withIndex().any { (index, revision) ->
						revision.semanticRevision != index.toLong() + 1L
					} ||
					history.lastOrNull() != latest ||
					history.any { it.toApiFact() == null }
			}
		) return AmbientCellReadResult.StorageUnavailable
		val importedFacts = imported.map {
			it.toApiFact() ?: return AmbientCellReadResult.StorageUnavailable
		}
		val facts = (localFacts + importedFacts)
			.sortedWith(compareBy<AmbientCellFact>(
				AmbientCellFact::observedTimeMs,
				AmbientCellFact::identity,
			))
		val availability = if (
			database.ambientCellFactDao().latestAuthority()?.state ==
			AmbientCellAuthorityEntity.STATE_ACTIVE
		) AmbientCellAvailability.AVAILABLE else AmbientCellAvailability.DISABLED
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

private fun AmbientCellEffectiveLocalRow.toApiFact(): AmbientCellFact? {
	val source = fact
	if (source.effectChecksum != AmbientCellFactIntegrity.effectChecksum(source)) return null
	val count = source.observationCount ?: ownerObservationCount ?: return null
	return AmbientCellFact(
		source.logicalFactId,
		AmbientCellOrigin.LOCAL_DEVICE,
		source.coverageStartTimeMs,
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

private fun ImportedAmbientCellFactEntity.toApiFact(): AmbientCellFact? {
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
	if (AmbientCellPortableIntegrity.createFact(sourceFact).contentChecksum != contentChecksum) {
		return null
	}
	return sourceFact.copy(origin = AmbientCellOrigin.PORTABLE_IMPORT)
}

private fun ImportedAmbientCellGapEntity.toApiGap(): AmbientCellGap? {
	val sourceGap = AmbientCellGap(
		gapId,
		AmbientCellOrigin.valueOf(portableOrigin),
		structuralEpochDay,
		startTimeMs,
		endTimeMs,
		storedZoneId,
		reason,
	)
	if (AmbientCellPortableIntegrity.createGap(sourceGap).contentChecksum != contentChecksum) {
		return null
	}
	return sourceGap.copy(origin = AmbientCellOrigin.PORTABLE_IMPORT)
}

private fun String.toCellCoverage(): AmbientCellCoverage =
	if (this == AmbientCellFactRevisionEntity.COMPLETENESS_COMPLETE) {
		AmbientCellCoverage.COMPLETE
	} else AmbientCellCoverage.UNVERIFIABLE

private fun String.toSubscriptionCompleteness(): AmbientCellSubscriptionCompleteness = when (this) {
	AmbientCellFactRevisionEntity.SUBSCRIPTION_COMPLETE -> AmbientCellSubscriptionCompleteness.COMPLETE
	AmbientCellFactRevisionEntity.SUBSCRIPTION_PARTIAL -> AmbientCellSubscriptionCompleteness.PARTIAL
	else -> AmbientCellSubscriptionCompleteness.UNKNOWN
}

private const val MAX_IMPORTED_CELL_REVISIONS_PER_READ = 65_536
private const val CELL_SQLITE_ID_CHUNK_SIZE = 400
