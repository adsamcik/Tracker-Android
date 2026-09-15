package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryPage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistoryProducts
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.SourceOnlyHistoryIntent
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import javax.inject.Inject
import javax.inject.Singleton

/** Shared read-only adapter over source-owned history facades. */
internal interface TrackingHistorySourceUnionReader {
	suspend fun sessionInTransaction(segment: SessionSegment): HistoricalSessionSourceRead

	suspend fun candidateNativeOnlyInTransaction(
		segmentIds: List<Long>,
	): HistoricalSourceUnionRead<List<HistoricalSourceOnlyMembership>>

	suspend fun recentInTransaction(
		limit: Int,
	): HistoricalSourceUnionRead<List<HistoricalSourceUnionEntry>>
}

internal sealed interface HistoricalSessionSourceRead {
	data class Available(
		val segmentId: Long,
		val wifi: WifiHistoryQuery?,
		val cell: CellHistoryQuery?,
		val activity: ActivityHistoryQuery,
		val pressure: PressureSessionHistoryQuery,
	) : HistoricalSessionSourceRead {
		init {
			require(segmentId > 0L)
		}

		val publicProducts: SessionHistoryProducts?
			get() {
				val wifiProduct = wifi ?: return null
				val cellProduct = cell ?: return null
				return SessionHistoryProducts(
					segmentId = segmentId,
					wifi = wifiProduct,
					cell = cellProduct,
					activity = activity,
					pressure = pressure,
				)
			}
	}

	data class Unavailable(
		val reason: TrackingHistoryUnavailableReason,
		val source: HistorySource,
	) : HistoricalSessionSourceRead
}

internal sealed interface HistoricalSourceUnionRead<out T> {
	data class Available<T>(val value: T) : HistoricalSourceUnionRead<T>

	data class Unavailable(
		val reason: SourceAwareHistoryPageUnavailableReason,
		val source: HistorySource,
	) : HistoricalSourceUnionRead<Nothing>
}

/** Complete native replacement membership used only for suppression and collision checks. */
internal data class HistoricalSourceOnlyMembership(
	val source: HistorySource,
	val logicalTrackingId: String,
	val physicalSegmentIds: List<Long>,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(physicalSegmentIds.isNotEmpty())
		require(physicalSegmentIds.all { it > 0L })
		require(physicalSegmentIds.distinct().size == physicalSegmentIds.size)
	}
}

/** One source-owned row plus authenticated recency and optional native replacement membership. */
internal data class HistoricalSourceUnionEntry(
	val entry: SourceAwareHistoryPageEntry,
	val recencyStartTimeMs: Long,
	val recencyTieBreaker: Long,
	val nativeMembership: HistoricalSourceOnlyMembership?,
) {
	init {
		require(recencyStartTimeMs >= 0L && recencyTieBreaker >= 0L)
		require(entry.source != null)
		require(
			(entry.intent == SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY) ==
				(nativeMembership != null),
		) {
			"Only exact native source rows may carry physical replacement membership"
		}
		require(nativeMembership == null || nativeMembership.source == entry.source)
	}
}

/**
 * Production adapter. Source-specific repositories retain ownership of authentication, duplicate
 * suppression, origin conflict handling, and bounded dependency reads.
 */
@Singleton
internal class DefaultTrackingHistorySourceUnionReader @Inject constructor(
	private val wifi: DefaultWifiHistoryRepository,
	private val cell: DefaultCellHistoryRepository,
	private val activity: DefaultActivityHistoryRepository,
	private val pressureSelector: PressureHistorySelector,
	private val pressurePageReader: PressureHistoryPageReader,
) : TrackingHistorySourceUnionReader {
	override suspend fun sessionInTransaction(
		segment: SessionSegment,
	): HistoricalSessionSourceRead {
		val wifiQuery = wifi.sessionInTransaction(segment.id)
		wifiQuery.failureOrNull()?.let { return it }
		val cellQuery = cell.sessionInTransaction(segment.id)
		cellQuery.failureOrNull()?.let { return it }
		val activityQuery = activity.sessionInTransaction(segment.id)
		activityQuery.failureOrNull()?.let { return it }
		val pressureQuery = pressureSelector.selectManyInTransaction(listOf(segment))
			.singleOrNull { selected -> selected.segment.id == segment.id }
			?.let { selected ->
				PressureSessionHistoryQuery.Found(selected.toPublicPressureSessionHistory())
			}
			?: return HistoricalSessionSourceRead.Unavailable(
				TrackingHistoryUnavailableReason.PHYSICAL_MEMBERSHIP_INVALID,
				HistorySource.PRESSURE,
			)
		pressureQuery.failureOrNull()?.let { return it }
		return HistoricalSessionSourceRead.Available(
			segmentId = segment.id,
			wifi = wifiQuery,
			cell = cellQuery,
			activity = activityQuery,
			pressure = pressureQuery,
		)
	}

	override suspend fun candidateNativeOnlyInTransaction(
		segmentIds: List<Long>,
	): HistoricalSourceUnionRead<List<HistoricalSourceOnlyMembership>> {
		val wifiGroups = when (val page = wifi.selectBySegmentIdsInTransaction(segmentIds)) {
			is WifiComposedPage.Available -> page.entries.filter { it.entry.capturesOnlyWifi }
			is WifiComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.WIFI)
		}
		val cellGroups = when (val page = cell.selectBySegmentIdsInTransaction(segmentIds)) {
			is CellComposedPage.Available -> page.entries.filter(ComposedCellEntry::capturesOnlyCell)
			is CellComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.CELL)
		}
		val activityGroups = when (val page = activity.selectBySegmentIdsInTransaction(segmentIds)) {
			is ActivityComposedPage.Available ->
				page.entries.filter { it.entry.capturesOnlyActivity }
			is ActivityComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.ACTIVITY)
		}
		val pressureGroups = pressureSelector.selectLogicalBySegmentIdsInTransaction(segmentIds)
		if (pressureGroups.hasDependencyOverflow) {
			return HistoricalSourceUnionRead.Unavailable(
				SourceAwareHistoryPageUnavailableReason.LOGICAL_MEMBERSHIP_LIMIT,
				HistorySource.PRESSURE,
			)
		}
		return HistoricalSourceUnionRead.Available(buildList {
			wifiGroups.mapTo(this, ComposedWifiEntry::toNativeMembership)
			cellGroups.mapTo(this, ComposedCellEntry::toNativeMembership)
			activityGroups.mapTo(this, ComposedActivityEntry::toNativeMembership)
			pressureGroups.filter(PressureLogicalHistoryEntry::hasExactPressureOnlyIntent)
				.mapTo(this, PressureLogicalHistoryEntry::toNativeMembership)
		})
	}

	override suspend fun recentInTransaction(
		limit: Int,
	): HistoricalSourceUnionRead<List<HistoricalSourceUnionEntry>> {
		val wifiOnly = when (val page = wifi.recentWifiOnlyInTransaction(limit)) {
			is WifiComposedPage.Available -> page.entries
			is WifiComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.WIFI)
		}
		val wifiImported = when (val page = wifi.recentInTransaction(limit)) {
			is WifiSourceRecentPage.Available ->
				page.entries.filterIsInstance<WifiSourceRecentEntry.Imported>()
			is WifiSourceRecentPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.WIFI)
		}
		if (wifiImported.any { it.entry.selection == null }) {
			return HistoricalSourceUnionRead.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				HistorySource.WIFI,
			)
		}
		val cellOnly = when (val page = cell.recentCellOnlyInTransaction(limit)) {
			is CellComposedPage.Available -> page.entries
			is CellComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.CELL)
		}
		val cellImported = when (val page = cell.recentCellHistoryInTransaction(limit)) {
			is CellSourceComposedPage.Available ->
				page.entries.filterIsInstance<CellSourceComposedEntry.Imported>()
			is CellSourceComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.CELL)
		}
		val activityOnly = when (val page = activity.recentActivityOnlyInTransaction(limit)) {
			is ActivityComposedPage.Available -> page.entries
			is ActivityComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.ACTIVITY)
		}
		val activityImported = when (val page = activity.recent(limit)) {
			is ActivityHistoryPage.Available ->
				page.entries.filter { it.origin == ActivityHistoryOrigin.IMPORTED }
			is ActivityHistoryPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.ACTIVITY)
		}
		val pressureOnly = when (
			val result = pressureSelector.discoverRecentPressureOnlyIntentInTransaction(limit)
		) {
			is PressureOnlyDiscoveryResult.Content -> result.entries
			is PressureOnlyDiscoveryResult.Unavailable -> return HistoricalSourceUnionRead.Unavailable(
				result.reason,
				HistorySource.PRESSURE,
			)
		}
		val pressureImported = try {
			pressurePageReader.selectRecent(limit)
				.filter { it.origin is PressureHistoryOrigin.Imported }
		} catch (overflow: PressureHistoryPageDependencyOverflow) {
			return HistoricalSourceUnionRead.Unavailable(overflow.reason, HistorySource.PRESSURE)
		}
		return HistoricalSourceUnionRead.Available(buildList {
			wifiOnly.mapTo(this, ComposedWifiEntry::toUnionEntry)
			wifiImported.mapTo(this) { it.entry.toImportedWifiUnionEntry() }
			cellOnly.mapTo(this, ComposedCellEntry::toUnionEntry)
			cellImported.mapTo(this) { it.entry.toImportedCellUnionEntry() }
			activityOnly.mapTo(this, ComposedActivityEntry::toUnionEntry)
			activityImported.mapTo(this) { it.toImportedActivityUnionEntry() }
			pressureOnly.mapNotNullTo(this, PressureLogicalHistoryEntry::toUnionEntryOrNull)
			pressureImported.mapTo(this) { it.toImportedPressureUnionEntry() }
		})
	}
}

/**
 * Compatibility adapter for pre-union tests and constructors. It preserves their actual
 * Activity/Pressure reads without manufacturing absent radio products.
 */
internal class LegacyTrackingHistorySourceUnionReader(
	private val activity: DefaultActivityHistoryRepository,
	private val pressureSelector: PressureHistorySelector,
	private val pressurePageReader: PressureHistoryPageReader,
) : TrackingHistorySourceUnionReader {
	override suspend fun sessionInTransaction(
		segment: SessionSegment,
	): HistoricalSessionSourceRead = HistoricalSessionSourceRead.Available(
		segmentId = segment.id,
		wifi = null,
		cell = null,
		activity = activity.sessionInTransaction(segment.id),
		pressure = pressureSelector.selectManyInTransaction(listOf(segment))
			.singleOrNull { it.segment.id == segment.id }
			?.let { PressureSessionHistoryQuery.Found(it.toPublicPressureSessionHistory()) }
			?: PressureSessionHistoryQuery.NotFound,
	)

	override suspend fun candidateNativeOnlyInTransaction(
		segmentIds: List<Long>,
	): HistoricalSourceUnionRead<List<HistoricalSourceOnlyMembership>> {
		val activityGroups = when (val page = activity.selectBySegmentIdsInTransaction(segmentIds)) {
			is ActivityComposedPage.Available ->
				page.entries.filter { it.entry.capturesOnlyActivity }
			is ActivityComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.ACTIVITY)
		}
		val pressureGroups = pressureSelector.selectLogicalBySegmentIdsInTransaction(segmentIds)
		if (pressureGroups.hasDependencyOverflow) {
			return HistoricalSourceUnionRead.Unavailable(
				SourceAwareHistoryPageUnavailableReason.LOGICAL_MEMBERSHIP_LIMIT,
				HistorySource.PRESSURE,
			)
		}
		return HistoricalSourceUnionRead.Available(buildList {
			activityGroups.mapTo(this, ComposedActivityEntry::toNativeMembership)
			pressureGroups.filter(PressureLogicalHistoryEntry::hasExactPressureOnlyIntent)
				.mapTo(this, PressureLogicalHistoryEntry::toNativeMembership)
		})
	}

	override suspend fun recentInTransaction(
		limit: Int,
	): HistoricalSourceUnionRead<List<HistoricalSourceUnionEntry>> {
		val activityOnly = when (val page = activity.recentActivityOnlyInTransaction(limit)) {
			is ActivityComposedPage.Available -> page.entries
			is ActivityComposedPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.ACTIVITY)
		}
		val activityImported = when (val page = activity.recent(limit)) {
			is ActivityHistoryPage.Available ->
				page.entries.filter { it.origin == ActivityHistoryOrigin.IMPORTED }
			is ActivityHistoryPage.Failed ->
				return page.cause.toUnionFailure(HistorySource.ACTIVITY)
		}
		val pressureOnly = when (
			val result = pressureSelector.discoverRecentPressureOnlyIntentInTransaction(limit)
		) {
			is PressureOnlyDiscoveryResult.Content -> result.entries
			is PressureOnlyDiscoveryResult.Unavailable -> return HistoricalSourceUnionRead.Unavailable(
				result.reason,
				HistorySource.PRESSURE,
			)
		}
		val pressureImported = try {
			pressurePageReader.selectRecent(limit)
				.filter { it.origin is PressureHistoryOrigin.Imported }
		} catch (overflow: PressureHistoryPageDependencyOverflow) {
			return HistoricalSourceUnionRead.Unavailable(overflow.reason, HistorySource.PRESSURE)
		}
		return HistoricalSourceUnionRead.Available(buildList {
			activityOnly.mapTo(this, ComposedActivityEntry::toUnionEntry)
			activityImported.mapTo(this) { it.toImportedActivityUnionEntry() }
			pressureOnly.mapNotNullTo(this, PressureLogicalHistoryEntry::toUnionEntryOrNull)
			pressureImported.mapTo(this) { it.toImportedPressureUnionEntry() }
		})
	}
}

private fun WifiHistoryQuery.failureOrNull(): HistoricalSessionSourceRead.Unavailable? = when (this) {
	WifiHistoryQuery.NotFound -> HistoricalSessionSourceRead.Unavailable(
		TrackingHistoryUnavailableReason.PHYSICAL_MEMBERSHIP_INVALID,
		HistorySource.WIFI,
	)
	is WifiHistoryQuery.Failed -> HistoricalSessionSourceRead.Unavailable(
		if (cause == WifiHistoryCause.READ_BUDGET_EXCEEDED) {
			TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
		} else {
			TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
		},
		HistorySource.WIFI,
	)
	is WifiHistoryQuery.Found -> if (entry.state == WifiHistoryProductState.FAILED) {
		HistoricalSessionSourceRead.Unavailable(
			if (WifiHistoryCause.READ_BUDGET_EXCEEDED in entry.causes) {
				TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
			} else {
				TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
			},
			HistorySource.WIFI,
		)
	} else {
		null
	}
}

private fun CellHistoryQuery.failureOrNull(): HistoricalSessionSourceRead.Unavailable? = when (this) {
	CellHistoryQuery.NotFound -> HistoricalSessionSourceRead.Unavailable(
		TrackingHistoryUnavailableReason.PHYSICAL_MEMBERSHIP_INVALID,
		HistorySource.CELL,
	)
	is CellHistoryQuery.Found -> when (entry.state) {
		CellHistoryProductState.FAILED,
		CellHistoryProductState.UNVERIFIABLE,
		-> HistoricalSessionSourceRead.Unavailable(
			if (CellHistoryCause.READ_BUDGET_EXCEEDED in entry.causes) {
				TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
			} else {
				TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
			},
			HistorySource.CELL,
		)
		else -> null
	}
}

private fun ActivityHistoryQuery.failureOrNull(): HistoricalSessionSourceRead.Unavailable? =
	when (this) {
		ActivityHistoryQuery.NotFound -> HistoricalSessionSourceRead.Unavailable(
			TrackingHistoryUnavailableReason.PHYSICAL_MEMBERSHIP_INVALID,
			HistorySource.ACTIVITY,
		)
		is ActivityHistoryQuery.Found -> if (entry.state == ActivityHistoryProductState.FAILED) {
			HistoricalSessionSourceRead.Unavailable(
				if (ActivityHistoryCause.READ_BUDGET_EXCEEDED in entry.causes) {
					TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
				} else {
					TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
				},
				HistorySource.ACTIVITY,
			)
		} else {
			null
		}
	}

private fun PressureSessionHistoryQuery.failureOrNull(): HistoricalSessionSourceRead.Unavailable? =
	when (this) {
		PressureSessionHistoryQuery.NotFound -> HistoricalSessionSourceRead.Unavailable(
			TrackingHistoryUnavailableReason.PHYSICAL_MEMBERSHIP_INVALID,
			HistorySource.PRESSURE,
		)
		is PressureSessionHistoryQuery.Found ->
			if (history.pressure.productState == HistoryProductState.FAILED) {
				HistoricalSessionSourceRead.Unavailable(
					if (
						PressureHistoryCause.BATCH_DEPENDENCY_OVERFLOW in
						history.pressure.causes
					) {
						TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
					} else {
						TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
					},
					HistorySource.PRESSURE,
				)
			} else {
				null
			}
	}

private fun WifiHistoryCause.toUnionFailure(
	source: HistorySource,
): HistoricalSourceUnionRead.Unavailable = HistoricalSourceUnionRead.Unavailable(
	if (this == WifiHistoryCause.READ_BUDGET_EXCEEDED) {
		SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
	} else {
		SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
	},
	source,
)

private fun CellHistoryCause.toUnionFailure(
	source: HistorySource,
): HistoricalSourceUnionRead.Unavailable = HistoricalSourceUnionRead.Unavailable(
	if (this == CellHistoryCause.READ_BUDGET_EXCEEDED) {
		SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
	} else {
		SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
	},
	source,
)

private fun ActivityHistoryCause.toUnionFailure(
	source: HistorySource,
): HistoricalSourceUnionRead.Unavailable = HistoricalSourceUnionRead.Unavailable(
	if (this == ActivityHistoryCause.READ_BUDGET_EXCEEDED) {
		SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
	} else {
		SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
	},
	source,
)

private fun ComposedWifiEntry.toNativeMembership() = HistoricalSourceOnlyMembership(
	HistorySource.WIFI,
	logicalTrackingId,
	physicalSegmentIds,
)

private fun ComposedCellEntry.toNativeMembership() = HistoricalSourceOnlyMembership(
	HistorySource.CELL,
	logicalTrackingId,
	physicalSegmentIds,
)

private fun ComposedActivityEntry.toNativeMembership() = HistoricalSourceOnlyMembership(
	HistorySource.ACTIVITY,
	logicalTrackingId,
	physicalSegmentIds,
)

private fun PressureLogicalHistoryEntry.toNativeMembership() = HistoricalSourceOnlyMembership(
	source = HistorySource.PRESSURE,
	logicalTrackingId = (identity as PressureHistoryEntryIdentity.Logical).logicalTrackingId,
	physicalSegmentIds = physicalMembers.map { it.segment.id },
)

private fun ComposedWifiEntry.toUnionEntry() = HistoricalSourceUnionEntry(
	entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
	recencyStartTimeMs = recencyStartTimeMs,
	recencyTieBreaker = recencySegmentId,
	nativeMembership = toNativeMembership(),
)

private fun com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
	.toImportedWifiUnionEntry() = HistoricalSourceUnionEntry(
	entry = SourceAwareHistoryPageEntry.WifiOnly(this),
	recencyStartTimeMs = startTime.raw,
	recencyTieBreaker = endTime.raw,
	nativeMembership = null,
)

private fun ComposedCellEntry.toUnionEntry() = HistoricalSourceUnionEntry(
	entry = SourceAwareHistoryPageEntry.CellOnly(entry),
	recencyStartTimeMs = recencyStartTimeMs,
	recencyTieBreaker = recencySegmentId,
	nativeMembership = toNativeMembership(),
)

private fun com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
	.toImportedCellUnionEntry() = HistoricalSourceUnionEntry(
	entry = SourceAwareHistoryPageEntry.CellOnly(this),
	recencyStartTimeMs = startTime.raw,
	recencyTieBreaker = endTime.raw,
	nativeMembership = null,
)

private fun ComposedActivityEntry.toUnionEntry() = HistoricalSourceUnionEntry(
	entry = SourceAwareHistoryPageEntry.ActivityOnly(entry),
	recencyStartTimeMs = recencyStartTimeMs,
	recencyTieBreaker = recencySegmentId,
	nativeMembership = toNativeMembership(),
)

private fun com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
	.toImportedActivityUnionEntry() = HistoricalSourceUnionEntry(
	entry = SourceAwareHistoryPageEntry.ActivityOnly(this),
	recencyStartTimeMs = startTime.raw,
	recencyTieBreaker = endTime.raw,
	nativeMembership = null,
)

private fun PressureLogicalHistoryEntry.toUnionEntryOrNull(): HistoricalSourceUnionEntry? =
	toPublicPressureOnlyEntryOrNull()?.let { public ->
		HistoricalSourceUnionEntry(
			entry = SourceAwareHistoryPageEntry.PressureOnly(public),
			recencyStartTimeMs = recencyMember.segment.startTimeMs,
			recencyTieBreaker = recencyMember.segment.id,
			nativeMembership = toNativeMembership(),
		)
	}

private fun com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
	.toImportedPressureUnionEntry() = HistoricalSourceUnionEntry(
	entry = SourceAwareHistoryPageEntry.PressureOnly(this),
	recencyStartTimeMs = startTime.raw,
	recencyTieBreaker = endTime.raw,
	nativeMembership = null,
)

private val List<PressureLogicalHistoryEntry>.hasDependencyOverflow: Boolean
	get() = any { entry ->
		entry.physicalMembers.any { member ->
			PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW in member.reasons
		}
	}
