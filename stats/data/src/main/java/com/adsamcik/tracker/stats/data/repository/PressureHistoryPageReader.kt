package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureDigest
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One bounded Room snapshot composing local and portable-origin Pressure-only rows. */
@Singleton
internal class PressureHistoryPageReader @Inject constructor(
	private val database: AppDatabase,
	private val liveSelector: PressureHistorySelector,
	private val importedEvaluator: ImportedPressureHistoryEvaluator,
	private val portableReader: PortablePressureRoomReader,
) : PressureImportedHistoryEligibleReader {
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

	override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<PressureImportedHistoryEligibleEntry> {
		require(limit > 0)
		if (limit > PressurePortableFormatV1.MAX_ENTRIES) {
			return unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
			)
		}
		val accepted = mutableListOf<PressureImportedHistoryEligibleEntry>()
		var localAuthorities: Map<
			PortablePressureOpaqueIdentity,
			PressureLocalDuplicateAuthority,
		>? = null
		var beforeRecencyStartTimeMs: Long? = null
		var beforeRecencyTieIdentity: String? = null
		var scannedCandidates = 0
		while (scannedCandidates < PRESSURE_IMPORTED_ELIGIBLE_SCAN_BUDGET) {
			currentCoroutineContext().ensureActive()
			val remainingBudget = PRESSURE_IMPORTED_ELIGIBLE_SCAN_BUDGET - scannedCandidates
			val pageLimit = minOf(PRESSURE_IMPORTED_ELIGIBLE_PAGE_SIZE, remainingBudget)
			val finalBudgetPage = remainingBudget <= PRESSURE_IMPORTED_ELIGIBLE_PAGE_SIZE
			val probeLimit = pageLimit + if (finalBudgetPage) 1 else 0
			val candidateProbe = try {
				database.importedPressureDao().recentHistoryCandidatePage(
					limit = probeLimit,
					beforeRecencyStartTimeMs = beforeRecencyStartTimeMs,
					beforeRecencyTieIdentity = beforeRecencyTieIdentity,
				)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				return unavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				)
			}
			if (candidateProbe.isEmpty()) break
			if (!isValidImportedPressureHistoryCandidatePage(
					candidateProbe,
					beforeRecencyStartTimeMs,
					beforeRecencyTieIdentity,
				)
			) {
				return unavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				)
			}
			val candidateBudgetExceeded = finalBudgetPage && candidateProbe.size > pageLimit
			if (candidateBudgetExceeded) {
				return unavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
				)
			}
			val localDiscovery = try {
				localAuthorities?.let { PressureLocalDuplicateAuthorityRead.Ready(it) }
					?: when (
						val local = liveSelector.discoverPressureOnlyDuplicateAuthorityInTransaction()
					) {
						is PressureOnlyDiscoveryResult.Unavailable ->
							PressureLocalDuplicateAuthorityRead.Unavailable(local.reason)
						is PressureOnlyDiscoveryResult.Content ->
							authenticateLocalDuplicateAuthorities(local.entries)?.let {
								PressureLocalDuplicateAuthorityRead.Ready(it)
							} ?: PressureLocalDuplicateAuthorityRead.IntegrityFailure
					}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				PressureLocalDuplicateAuthorityRead.IntegrityFailure
			}
			val duplicateAuthorities = when (localDiscovery) {
				is PressureLocalDuplicateAuthorityRead.Unavailable -> return unavailable(
					when (localDiscovery.reason) {
						SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT,
						SourceAwareHistoryPageUnavailableReason.LOGICAL_MEMBERSHIP_LIMIT,
						SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
						-> SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
						else -> SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
					},
				)
				PressureLocalDuplicateAuthorityRead.IntegrityFailure -> return unavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				)
				is PressureLocalDuplicateAuthorityRead.Ready -> localDiscovery.authorities
			}
			localAuthorities = duplicateAuthorities
			val candidates = candidateProbe.take(pageLimit)
			for (candidate in candidates) {
				currentCoroutineContext().ensureActive()
				val evaluation = try {
					importedEvaluator.evaluateCandidateInTransaction(candidate)
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: RuntimeException) {
					return unavailable(
						SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
					)
				}
				scannedCandidates++
				when (val decision = evaluation.toSharedHistoryEligibility(duplicateAuthorities)) {
					is PressureImportedEligibilityDecision.Accepted -> accepted += decision.entry
					PressureImportedEligibilityDecision.Suppressed -> Unit
					is PressureImportedEligibilityDecision.Unavailable ->
						return unavailable(decision.reason)
				}
			}
			if (candidateProbe.size < probeLimit) break
			val last = candidates.last()
			beforeRecencyStartTimeMs = last.recencyStartTimeMs
			beforeRecencyTieIdentity = last.recencyTieIdentity
		}
		return ImportedHistoryEligiblePage.Available(
			accepted.sortedWith(pressureImportedHistoryEligibleOrder).take(limit),
		)
	}

	private fun authenticateLocalDuplicateAuthorities(
		entries: List<PressureLogicalHistoryEntry>,
	): Map<PortablePressureOpaqueIdentity, PressureLocalDuplicateAuthority>? {
		val authorities =
			linkedMapOf<PortablePressureOpaqueIdentity, PressureLocalDuplicateAuthority>()
		for (entry in entries) {
			val logical = entry.identity as? PressureHistoryEntryIdentity.Logical ?: return null
			val identity = try {
				PortablePressureOpaqueIdentity.derive(
					PortablePressureIdentityKind.LOGICAL_ENTRY,
					logical.logicalTrackingId,
				)
			} catch (_: IllegalArgumentException) {
				return null
			}
			val authority = PressureLocalDuplicateAuthority(
				portableReader.portableEntryForComparison(entry),
			)
			val previous = authorities[identity]
			if (previous != null && previous != authority) return null
			authorities[identity] = authority
		}
		return authorities
	}

	private fun ImportedPressureHistoryEvaluation.toSharedHistoryEligibility(
		localAuthorities: Map<PortablePressureOpaqueIdentity, PressureLocalDuplicateAuthority>,
	): PressureImportedEligibilityDecision = when (this) {
		is ImportedPressureHistoryEvaluation.Unverifiable ->
			PressureImportedEligibilityDecision.Unavailable(
				when (reason) {
					ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW ->
						SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
					ImportedPressureHistoryFailure.SOURCE_EVIDENCE_STATE_MISSING ->
						SourceAwareHistoryPageUnavailableReason
							.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE
					else -> SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
				},
			)
		is ImportedPressureHistoryEvaluation.Readable ->
			readableEligibility(localAuthorities)
		is ImportedPressureHistoryEvaluation.Retained ->
			retainedEligibility(localAuthorities)
	}

	private fun ImportedPressureHistoryEvaluation.Readable.readableEligibility(
		localAuthorities: Map<PortablePressureOpaqueIdentity, PressureLocalDuplicateAuthority>,
	): PressureImportedEligibilityDecision {
		val imported = latest.entry
		localAuthorities[imported.identity]?.let { local ->
			return if (isReExportable && local.portable == imported) {
				PressureImportedEligibilityDecision.Suppressed
			} else {
				PressureImportedEligibilityDecision.Unavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				)
			}
		}
		val newest = candidate.authenticatedRecencyOrNull()
			?: return PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
			)
		return authenticatedEligibleEntry(
			entry = toPublicPressureOnlyEntry(),
			identity = imported.identity,
			importRevision = latest.header.importRevision,
			contentChecksum = latest.header.contentChecksum,
			newestMemberStartTimeMs = newest.first,
			newestMemberIdentity = newest.second,
			expectedContentChecksum = imported.contentChecksum,
		)
	}

	private fun ImportedPressureHistoryEvaluation.Retained.retainedEligibility(
		localAuthorities: Map<PortablePressureOpaqueIdentity, PressureLocalDuplicateAuthority>,
	): PressureImportedEligibilityDecision {
		val identity = portableValueOrNull {
			PortablePressureOpaqueIdentity(candidate.identity)
		} ?: return PressureImportedEligibilityDecision.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
		)
		if (identity in localAuthorities) {
			return PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		}
		val hasRecencyAuthority = protectedIdentities.any { retained ->
			retained is RetainedImportedPressureIdentity.RunScope &&
				retained.identity == recencyTieIdentity
		}
		if (!hasRecencyAuthority) {
			return PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
			)
		}
		val newest = candidate.authenticatedRecencyOrNull()
			?: return PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
			)
		if (newest.first != recencyStartTimeMs || newest.second != recencyTieIdentity) {
			return PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		}
		return authenticatedEligibleEntry(
			entry = toPublicPressureOnlyEntry(),
			identity = identity,
			importRevision = candidate.importRevision,
			contentChecksum = candidate.contentChecksum,
			newestMemberStartTimeMs = newest.first,
			newestMemberIdentity = newest.second,
			expectedContentChecksum = null,
		)
	}

	private fun authenticatedEligibleEntry(
		entry: PressureOnlyHistoryEntry,
		identity: PortablePressureOpaqueIdentity,
		importRevision: Long,
		contentChecksum: String,
		newestMemberStartTimeMs: Long,
		newestMemberIdentity: PortablePressureOpaqueIdentity,
		expectedContentChecksum: PortablePressureDigest?,
	): PressureImportedEligibilityDecision = try {
		val digest = PortablePressureDigest(contentChecksum)
		if (expectedContentChecksum != null && digest != expectedContentChecksum) {
			PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		} else {
			PressureImportedEligibilityDecision.Accepted(
				PressureImportedHistoryEligibleEntry(
					entry = entry,
					identity = ImportedPressureHistoryIdentity(identity.value),
					importRevision = importRevision,
					contentChecksum = digest,
					recency = ImportedHistoryRecency(
						source = HistorySource.PRESSURE,
						newestMemberStartTimeMs = newestMemberStartTimeMs,
						newestMemberTieIdentity = ImportedHistoryRecencyTieIdentity(
							newestMemberIdentity.value,
						),
					),
				),
			)
		}
	} catch (_: IllegalArgumentException) {
		PressureImportedEligibilityDecision.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
		)
	}

	private fun unavailable(
		reason: SourceAwareHistoryPageUnavailableReason,
	): ImportedHistoryEligiblePage.Unavailable = ImportedHistoryEligiblePage.Unavailable(reason)
}

private fun ImportedPressureHistoryCandidate.authenticatedRecencyOrNull():
	Pair<Long, PortablePressureOpaqueIdentity>? {
	val startTimeMs = recencyStartTimeMs ?: return null
	val tieIdentity = recencyTieIdentity ?: return null
	return portableValueOrNull { startTimeMs to PortablePressureOpaqueIdentity(tieIdentity) }
}

internal val pressureSourceRecencyRunOrder =
	compareBy<com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1>(
		{ it.startTimeMs },
		{ it.identity.value },
	)

private data class PressureLocalDuplicateAuthority(
	val portable: PortablePressureEntryV1?,
)

private sealed interface PressureLocalDuplicateAuthorityRead {
	data class Ready(
		val authorities: Map<PortablePressureOpaqueIdentity, PressureLocalDuplicateAuthority>,
	) : PressureLocalDuplicateAuthorityRead

	data class Unavailable(
		val reason: SourceAwareHistoryPageUnavailableReason,
	) : PressureLocalDuplicateAuthorityRead

	data object IntegrityFailure : PressureLocalDuplicateAuthorityRead
}

private sealed interface PressureImportedEligibilityDecision {
	data class Accepted(
		val entry: PressureImportedHistoryEligibleEntry,
	) : PressureImportedEligibilityDecision

	data object Suppressed : PressureImportedEligibilityDecision

	data class Unavailable(
		val reason: SourceAwareHistoryPageUnavailableReason,
	) : PressureImportedEligibilityDecision
}

private val pressureImportedHistoryEligibleOrder =
	Comparator<PressureImportedHistoryEligibleEntry> { left, right ->
		importedHistoryRecencyOrder.compare(left.recency, right.recency)
	}

private inline fun <T> portableValueOrNull(block: () -> T): T? = try {
	block()
} catch (_: IllegalArgumentException) {
	null
}

private const val PRESSURE_IMPORTED_ELIGIBLE_PAGE_SIZE = 32
private const val PRESSURE_IMPORTED_ELIGIBLE_SCAN_BUDGET = PressurePortableFormatV1.MAX_ENTRIES

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
