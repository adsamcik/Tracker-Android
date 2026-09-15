package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiEffectiveLocalRow
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Fixed-query Ambient Wi-Fi reader. Local and imported origins are always explicit. */
@Singleton
internal class RoomAmbientWifiRepository @Inject constructor(
	private val database: AppDatabase,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AmbientWifiRepository {
	override suspend fun read(request: AmbientWifiReadRequest): AmbientWifiReadResult =
		withContext(ioDispatcher) {
			try {
				database.withTransaction {
					val dao = database.ambientWifiFactDao()
					val local = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
						dao.effectiveLocalOverWindow(
							request.fromInclusiveMs,
							request.toExclusiveMs,
							request.limit + 1,
						)
					} else emptyList()
					val imported = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
						dao.importedOverWindow(
							request.fromInclusiveMs,
							request.toExclusiveMs,
							request.limit + 1,
						)
					} else emptyList()
					val gaps = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
						dao.gapsOverWindow(
							request.fromInclusiveMs,
							request.toExclusiveMs,
							request.limit + 1,
						)
					} else emptyList()
					val importedGaps = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
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
				AmbientWifiReadResult.StorageUnavailable
			}
		}

	override suspend fun readDay(request: AmbientWifiDayReadRequest): AmbientWifiReadResult =
		withContext(ioDispatcher) {
			try {
				database.withTransaction {
					val dao = database.ambientWifiFactDao()
					val local = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
						dao.effectiveLocalForDay(
							request.structuralEpochDay,
							request.storedZoneId,
							request.limit + 1,
						)
					} else emptyList()
					val imported = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
						dao.importedForDay(
							request.structuralEpochDay,
							request.storedZoneId,
							request.limit + 1,
						)
					} else emptyList()
					val fromMs = local.minOfOrNull { it.fact.structuralDayStartTimeMs }
						?: imported.minOfOrNull(ImportedAmbientWifiFactEntity::coverageStartTimeMs)
						?: 0L
					val toMs = local.maxOfOrNull { it.fact.structuralDayEndTimeMs }
						?: imported.maxOfOrNull(ImportedAmbientWifiFactEntity::latestPossibleTimeMs)
						?.plus(1L)
						?: 1L
					val gaps = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
						dao.gapsOverWindow(fromMs, toMs, request.limit + 1)
					} else emptyList()
					val importedGaps = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
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
				AmbientWifiReadResult.StorageUnavailable
			}
		}

	private suspend fun compose(
		local: List<AmbientWifiEffectiveLocalRow>,
		imported: List<ImportedAmbientWifiFactEntity>,
		gaps: List<AmbientWifiGapEntity>,
		importedGaps: List<ImportedAmbientWifiGapEntity>,
		limit: Int,
	): AmbientWifiReadResult {
		if (local.size > limit || imported.size > limit || gaps.size > limit ||
			importedGaps.size > limit || local.size + imported.size > limit ||
			gaps.size + importedGaps.size > limit
		) return AmbientWifiReadResult.DependencyOverflow
		val localFacts = local.map { it.toApiFact() ?: return AmbientWifiReadResult.StorageUnavailable }
		val importedHistory = if (imported.isEmpty()) emptyList() else {
			buildList {
				imported.map(ImportedAmbientWifiFactEntity::factId).distinct()
					.chunked(SQLITE_ID_CHUNK_SIZE).forEach { factIds ->
						addAll(
							database.ambientWifiFactDao().importedFactRevisions(
								factIds,
								MAX_IMPORTED_WIFI_REVISIONS_PER_READ + 1 - size,
							),
						)
						if (size > MAX_IMPORTED_WIFI_REVISIONS_PER_READ) return@buildList
					}
			}
		}
		if (importedHistory.size > MAX_IMPORTED_WIFI_REVISIONS_PER_READ) {
			return AmbientWifiReadResult.DependencyOverflow
		}
		val importedHistoryByFact = importedHistory.groupBy(ImportedAmbientWifiFactEntity::factId)
		if (imported.any { latest ->
				val history = importedHistoryByFact[latest.factId].orEmpty()
				history.size.toLong() != latest.semanticRevision ||
					history.withIndex().any { (index, revision) ->
						revision.semanticRevision != index.toLong() + 1L
					} ||
					history.lastOrNull() != latest ||
					history.any { it.toApiFact() == null }
			}
		) return AmbientWifiReadResult.StorageUnavailable
		val importedFacts = imported.map {
			it.toApiFact() ?: return AmbientWifiReadResult.StorageUnavailable
		}
		val facts = (localFacts + importedFacts).sortedWith(
			compareBy<AmbientWifiFact>(AmbientWifiFact::observedTimeMs, AmbientWifiFact::identity),
		)
		val availability = if (
			database.ambientWifiFactDao().latestAuthority()?.state ==
			AmbientWifiAuthorityEntity.STATE_ACTIVE
		) {
			AmbientWifiAvailability.AVAILABLE
		} else AmbientWifiAvailability.DISABLED
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

private fun AmbientWifiEffectiveLocalRow.toApiFact(): AmbientWifiFact? {
	val source = fact
	if (source.effectChecksum != AmbientWifiFactIntegrity.effectChecksum(source)) return null
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
		source.coverageStartTimeMs,
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

private fun ImportedAmbientWifiFactEntity.toApiFact(): AmbientWifiFact? {
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
	if (AmbientWifiPortableIntegrity.createFact(sourceFact).contentChecksum != contentChecksum) {
		return null
	}
	return sourceFact.copy(origin = AmbientWifiOrigin.PORTABLE_IMPORT)
}

private fun ImportedAmbientWifiGapEntity.toApiGap(): AmbientWifiGap? {
	val sourceGap = AmbientWifiGap(
		gapId,
		AmbientWifiOrigin.valueOf(portableOrigin),
		structuralEpochDay,
		startTimeMs,
		endTimeMs,
		storedZoneId,
		reason,
	)
	if (AmbientWifiPortableIntegrity.createGap(sourceGap).contentChecksum != contentChecksum) {
		return null
	}
	return sourceGap.copy(origin = AmbientWifiOrigin.PORTABLE_IMPORT)
}

private fun String.toCoverage(): AmbientWifiCoverage =
	if (this == AmbientWifiFactRevisionEntity.COMPLETENESS_COMPLETE) {
		AmbientWifiCoverage.COMPLETE
	} else AmbientWifiCoverage.UNVERIFIABLE

private const val MAX_IMPORTED_WIFI_REVISIONS_PER_READ = 65_536
private const val SQLITE_ID_CHUNK_SIZE = 400
