package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import javax.inject.Inject
import javax.inject.Singleton

/** One bounded Room snapshot composing local and portable-origin Pressure-only rows. */
@Singleton
internal class PressureHistoryPageReader @Inject constructor(
	private val database: AppDatabase,
	private val liveSelector: PressureHistorySelector,
	private val importedEvaluator: ImportedPressureHistoryEvaluator,
	private val portableReader: PortablePressureRoomReader,
) {
	internal suspend fun selectRecent(limit: Int): List<PressureOnlyHistoryEntry> =
		database.withTransaction {
			require(limit in 1..ImportedPressureDao.MAX_HISTORY_ENTRY_CANDIDATES)
			val live = liveSelector.discoverRecentPressureOnlyInTransaction(limit)
			val imported = importedEvaluator.selectRecentInTransaction(limit)
			PressureHistoryPageComposer.compose(
				live = live.mapNotNull { entry ->
					entry.toPublicPressureOnlyEntryOrNull()?.let { public ->
						LocalPressurePageCandidate(
							entry = public,
							portable = portableReader.portableEntryForComparison(entry),
						)
					}
				},
				imported = imported,
				limit = limit,
			)
		}

	/**
	 * Caller-owned transaction bridge for shared history.
	 *
	 * Rows carry source-authenticated membership and newest-member recency. Public envelope bounds
	 * are presentation only and never participate in shared ordering or producer selection.
	 */
	internal suspend fun selectRecentInTransaction(limit: Int): PressureSourceComposedPage {
		require(limit in 1..ImportedPressureDao.MAX_HISTORY_ENTRY_CANDIDATES)
		return PressureSourcePageComposer.compose(
			live = liveSelector.discoverRecentPressureOnlyInTransaction(limit),
			imported = importedEvaluator.selectRecentInTransaction(limit),
			portableReader = portableReader,
			limit = limit,
		)
	}
}

internal sealed interface PressureSourceComposedPage {
	data class Available(val rows: List<PressureSourceComposedRow>) : PressureSourceComposedPage
	data class Failed(val reason: PressureSourceComposedFailure) : PressureSourceComposedPage
}

internal enum class PressureSourceComposedFailure {
	LOCAL_MEMBERSHIP_UNVERIFIABLE,
	IMPORTED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_CONFLICT,
	DEPENDENCY_OVERFLOW,
}

internal data class PressureSourceRecency(
	val newestMemberStartTimeMs: Long,
	val newestMemberEndTimeMs: Long,
	val stableTieIdentity: PortablePressureOpaqueIdentity,
) {
	init {
		require(newestMemberStartTimeMs >= 0L)
		require(newestMemberEndTimeMs >= newestMemberStartTimeMs)
	}
}

internal sealed interface PressureSourceComposedRow {
	val public: PressureOnlyHistoryEntry
	val recency: PressureSourceRecency

	data class Local(
		val logical: PressureLogicalHistoryEntry,
		val portable: PortablePressureEntryV1,
		override val public: PressureOnlyHistoryEntry,
		override val recency: PressureSourceRecency,
	) : PressureSourceComposedRow

	data class Imported(
		val evaluation: ImportedPressureHistoryEvaluation,
		val selection: PressureImportedSelection,
		override val public: PressureOnlyHistoryEntry,
		override val recency: PressureSourceRecency,
	) : PressureSourceComposedRow
}

internal sealed interface PressureImportedSelection {
	data class Actionable(
		val identity: PortablePressureOpaqueIdentity,
		val expectedImportRevision: Long,
		val expectedCollectedDataEpoch: Long,
	) : PressureImportedSelection

	data class RetentionBoundary(
		val identity: PortablePressureOpaqueIdentity,
		val latestImportRevision: Long,
		val collectedDataEpoch: Long,
	) : PressureImportedSelection
}

internal object PressureSourcePageComposer {
	fun compose(
		live: List<PressureLogicalHistoryEntry>,
		imported: List<ImportedPressureHistoryEvaluation>,
		portableReader: PortablePressureRoomReader,
		limit: Int,
	): PressureSourceComposedPage {
		require(limit > 0)
		val rows = mutableListOf<PressureSourceComposedRow>()
		val localPortableByIdentity =
			linkedMapOf<PortablePressureOpaqueIdentity, PortablePressureEntryV1>()
		for (logical in live) {
			val public = logical.toPublicPressureOnlyEntryOrNull()
				?: return PressureSourceComposedPage.Failed(
					PressureSourceComposedFailure.LOCAL_MEMBERSHIP_UNVERIFIABLE,
				)
			val portable = portableReader.portableEntryForComparison(logical)
				?: return PressureSourceComposedPage.Failed(
					PressureSourceComposedFailure.LOCAL_MEMBERSHIP_UNVERIFIABLE,
				)
			val previous = localPortableByIdentity.putIfAbsent(portable.identity, portable)
			if (previous != null && previous != portable) {
				return PressureSourceComposedPage.Failed(
					PressureSourceComposedFailure.ORIGIN_CONFLICT,
				)
			}
			val member = logical.recencyMember
			val serviceRunId = member.segment.serviceRunId?.takeIf(String::isNotBlank)
				?: return PressureSourceComposedPage.Failed(
					PressureSourceComposedFailure.LOCAL_MEMBERSHIP_UNVERIFIABLE,
				)
			val recency = portableValueOrNull {
				PressureSourceRecency(
					member.segment.startTimeMs,
					member.segment.endTimeMs,
					PortablePressureOpaqueIdentity.derive(
						PortablePressureIdentityKind.PHYSICAL_RUN,
						serviceRunId,
					),
				)
			} ?: return PressureSourceComposedPage.Failed(
				PressureSourceComposedFailure.LOCAL_MEMBERSHIP_UNVERIFIABLE,
			)
			rows += PressureSourceComposedRow.Local(logical, portable, public, recency)
		}

		for (evaluation in imported) {
			when (evaluation) {
				is ImportedPressureHistoryEvaluation.Unverifiable ->
					return PressureSourceComposedPage.Failed(
						if (evaluation.reason == ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW) {
							PressureSourceComposedFailure.DEPENDENCY_OVERFLOW
						} else {
							PressureSourceComposedFailure.IMPORTED_EVIDENCE_UNVERIFIABLE
						},
					)
				is ImportedPressureHistoryEvaluation.Readable -> {
					val importedEntry = evaluation.latest.entry
					val local = localPortableByIdentity[importedEntry.identity]
					if (local != null && evaluation.isReExportable && local == importedEntry) continue
					if (local != null) {
						return PressureSourceComposedPage.Failed(
							PressureSourceComposedFailure.ORIGIN_CONFLICT,
						)
					}
					val newest = importedEntry.runs.maxWith(pressureSourceRecencyRunOrder)
					rows += PressureSourceComposedRow.Imported(
						evaluation,
						PressureImportedSelection.Actionable(
							importedEntry.identity,
							evaluation.latest.header.importRevision,
							evaluation.latest.header.collectedDataEpoch,
						),
						evaluation.toPublicPressureOnlyEntry(),
						PressureSourceRecency(newest.startTimeMs, newest.endTimeMs, newest.identity),
					)
				}
				is ImportedPressureHistoryEvaluation.Retained -> {
					val identity = portableValueOrNull {
						PortablePressureOpaqueIdentity(evaluation.candidate.identity)
					} ?: return PressureSourceComposedPage.Failed(
						PressureSourceComposedFailure.IMPORTED_EVIDENCE_UNVERIFIABLE,
					)
					if (identity in localPortableByIdentity) {
						return PressureSourceComposedPage.Failed(
							PressureSourceComposedFailure.ORIGIN_CONFLICT,
						)
					}
					rows += PressureSourceComposedRow.Imported(
						evaluation,
						PressureImportedSelection.RetentionBoundary(
							identity,
							evaluation.candidate.importRevision,
							evaluation.collectedDataEpoch,
						),
						evaluation.toPublicPressureOnlyEntry(),
						PressureSourceRecency(
							evaluation.recencyStartTimeMs,
							evaluation.recencyEndTimeMs,
							evaluation.recencyTieIdentity,
						),
					)
				}
			}
		}
		return PressureSourceComposedPage.Available(
			rows.sortedWith(pressureSourceRowOrder).take(limit),
		)
	}

	private inline fun <T> portableValueOrNull(block: () -> T): T? = try {
		block()
	} catch (_: IllegalArgumentException) {
		null
	}
}

internal val pressureSourceRecencyRunOrder =
	compareBy<com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1>(
		{ it.startTimeMs },
		{ it.endTimeMs },
		{ it.identity.value },
	)

private val pressureSourceRowOrder =
	compareByDescending<PressureSourceComposedRow> { it.recency.newestMemberStartTimeMs }
		.thenByDescending { it.recency.newestMemberEndTimeMs }
		.thenBy { it.recency.stableTieIdentity.value }

internal data class LocalPressurePageCandidate(
	val entry: PressureOnlyHistoryEntry,
	val portable: PortablePressureEntryV1?,
)

internal object PressureHistoryPageComposer {
	fun compose(
		live: List<LocalPressurePageCandidate>,
		imported: List<ImportedPressureHistoryEvaluation>,
		limit: Int,
	): List<PressureOnlyHistoryEntry> {
		require(limit > 0)
		val livePortable = live.mapNotNull(LocalPressurePageCandidate::portable).toSet()
		val rows = buildList<PressurePageRow> {
			live.mapTo(this) { candidate ->
				candidate.entry.toPageRow(LOCAL_ORIGIN_ORDER)
			}
			imported.forEach { evaluation ->
				val exactDuplicate = evaluation is ImportedPressureHistoryEvaluation.Readable &&
					evaluation.isReExportable && evaluation.latest.entry in livePortable
				if (!exactDuplicate) {
					add(evaluation.toPublicPressureOnlyEntry().toPageRow(IMPORTED_ORIGIN_ORDER))
				}
			}
		}
		return rows.sortedWith(
			compareByDescending<PressurePageRow>(PressurePageRow::recencyStartMs)
				.thenByDescending(PressurePageRow::recencyEndMs)
				.thenBy(PressurePageRow::originOrder),
		).take(limit).map(PressurePageRow::entry)
	}

	private fun PressureOnlyHistoryEntry.toPageRow(originOrder: Int) = PressurePageRow(
		entry = this,
		recencyStartMs = startTime.raw,
		recencyEndMs = endTime.raw,
		originOrder = originOrder,
	)

	private data class PressurePageRow(
		val entry: PressureOnlyHistoryEntry,
		val recencyStartMs: Long,
		val recencyEndMs: Long,
		val originOrder: Int,
	)

	private companion object {
		const val LOCAL_ORIGIN_ORDER = 0
		const val IMPORTED_ORIGIN_ORDER = 1
	}
}
