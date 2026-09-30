package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiEffectiveLocalRow
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
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
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
				database.withTransaction { readInTransaction(request) }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientWifiReadResult.StorageUnavailable
			}
		}

	/** Caller owns the snapshot when it also needs the source lineage for export. */
	internal suspend fun readInTransaction(request: AmbientWifiReadRequest): AmbientWifiReadResult {
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
		return compose(local, imported, gaps, importedGaps, request.limit)
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
		} else emptyList()
		val imported = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
			dao.importedForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else emptyList()
		val gaps = if (AmbientWifiOrigin.LOCAL_DEVICE in request.origins) {
			dao.gapsForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else emptyList()
		val importedGaps = if (AmbientWifiOrigin.PORTABLE_IMPORT in request.origins) {
			dao.importedGapsForDay(
				request.structuralEpochDay,
				request.storedZoneId,
				request.limit + 1,
			)
		} else emptyList()
		return compose(local, imported, gaps, importedGaps, request.limit)
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
		val localIds = local.map { it.fact.logicalFactId }.distinct()
		val localChildren = localIds.chunked(SQLITE_ID_CHUNK_SIZE).flatMap { ids ->
			val rows = database.radioObservationChildDao()
				.wifiCurrentBackgroundChildrenForFactIds(ids, ids.size * MAX_WIFI_CHILDREN + 1)
			if (rows.size > ids.size * MAX_WIFI_CHILDREN) {
				return AmbientWifiReadResult.DependencyOverflow
			}
			rows
		}.groupBy { it.logicalFactId to it.semanticRevision }
		val localFacts = local.map { row ->
			val base = row.toApiFact() ?: return AmbientWifiReadResult.StorageUnavailable
			val children = localChildren[row.fact.logicalFactId to row.fact.semanticRevision].orEmpty()
			if ((row.fact.purposeDemandId != null || children.isNotEmpty()) &&
				children.size != base.observationCount
			) {
				return AmbientWifiReadResult.StorageUnavailable
			}
			if (children.any { child ->
				child.writerId != row.fact.writerId || child.writerVersion != row.fact.writerVersion ||
					child.logicalFactId != row.fact.logicalFactId ||
					child.semanticRevision != row.fact.semanticRevision ||
					child.contentChecksum != RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(
						RadioChildOwner(RadioChildPurpose.BACKGROUND, child.writerId,
							child.writerVersion, child.logicalFactId, child.semanticRevision, child.itemIndex),
						child.providerTimestampNanos, child.observedWallTimeMs,
						child.wallTimeUncertaintyMs, child.bssid, child.ssid,
						child.frequencyMhz, child.signalDbm,
					))
			}) return AmbientWifiReadResult.StorageUnavailable
			base.copy(networks = children.map { child -> WifiObservedNetwork(
				child.observedWallTimeMs, child.wallTimeUncertaintyMs,
				child.bssid?.takeIf(String::isNotBlank),
				child.ssid?.takeIf(String::isNotEmpty),
				child.frequencyMhz, child.signalDbm,
			) })
		}
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
		val importedIds = imported.map(ImportedAmbientWifiFactEntity::factId).distinct()
		val importedChildRows = importedIds.chunked(SQLITE_ID_CHUNK_SIZE).flatMap { ids ->
			val rows = database.importedRadioChildDao()
				.wifiCurrentBackgroundChildrenForFactIds(ids, ids.size * MAX_WIFI_CHILDREN + 1)
			if (rows.size > ids.size * MAX_WIFI_CHILDREN) {
				return AmbientWifiReadResult.DependencyOverflow
			}
			rows
		}.groupBy { Triple(it.archiveId, it.factId, it.semanticRevision) }
		val importedKeys = imported.map { Triple(it.archiveId, it.factId, it.semanticRevision) }.toSet()
		if (!importedKeys.containsAll(importedChildRows.keys)) {
			return AmbientWifiReadResult.StorageUnavailable
		}
		val importedFacts = imported.map { parent ->
			val base = parent.toApiFact() ?: return AmbientWifiReadResult.StorageUnavailable
			val children = importedChildRows[Triple(parent.archiveId, parent.factId,
				parent.semanticRevision)].orEmpty()
			if (children.size > MAX_WIFI_CHILDREN ||
				children.isNotEmpty() && children.size != base.observationCount ||
				children.map(ImportedAmbientWifiChildEntity::itemIndex).distinct().size != children.size
			) return AmbientWifiReadResult.StorageUnavailable
			val networks = children.map { child ->
				child.toObservedNetworkIfValid(parent)
					?: return AmbientWifiReadResult.StorageUnavailable
			}
			base.copy(networks = networks)
		}
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

internal fun AmbientWifiEffectiveLocalRow.toApiFact(): AmbientWifiFact? {
	val source = fact
	if (source.effectChecksum != AmbientWifiFactIntegrity.effectChecksum(source)) return null
	val measuredStart = Math.subtractExact(source.coverageStartTimeMs, source.wallTimeUncertaintyMs)
	if (measuredStart < 0L) return null
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
	) return null
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
	} else AmbientWifiCoverage.UNVERIFIABLE

private const val MAX_IMPORTED_WIFI_REVISIONS_PER_READ = 65_536
private const val MAX_WIFI_CHILDREN = 64
private const val SQLITE_ID_CHUNK_SIZE = 400
