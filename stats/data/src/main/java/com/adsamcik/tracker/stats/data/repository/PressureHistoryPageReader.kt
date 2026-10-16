package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
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
// One bounded Pressure page owner keeps selected/recent reads and exact duplicate authority in the same Room snapshot.
@Suppress("TooManyFunctions")
@Singleton
internal class PressureHistoryPageReader @Inject constructor(
	private val database: AppDatabase,
	private val liveSelector: PressureHistorySelector,
	private val importedEvaluator: ImportedPressureHistoryEvaluator,
	private val portableReader: PortablePressureRoomReader,
	private val integrationObserver: TrackingHistoryIntegrationObserver =
		TrackingHistoryIntegrationObserver(),
) : PressureImportedHistoryEligibleReader {
	/** Source-qualified keys from arbitrary ranges reuse exact native/import duplicate authority. */
	// Preserve ordered candidate, native-owner and storage rejection before exposing selected data.
	@Suppress("ReturnCount")
	internal suspend fun selectedImportedInTransaction(
		identities: List<String>,
		range: com.adsamcik.tracker.stats.api.repository.HistoryRange,
	): ImportedHistoryEligiblePage<PressureImportedHistoryEligibleEntry> {
		require(identities.size in 1..100 && identities.distinct().size == identities.size)
		val candidates = database.importedPressureDao().selectedHistoryCandidates(identities)
		if (candidates.size != identities.size || candidates.map { it.identity }.toSet() != identities.toSet()) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		val localEntries = try {
			loadExactLocalDuplicateEntries(identities.asSequence())
		} catch (_: NativePortableOwnerBudgetExceeded) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED)
		} catch (_: NativePortableOwnerMembershipInvalid) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		} catch (overflow: PressureHistoryPageDependencyOverflow) {
			return unavailable(overflow.reason)
		}
		authenticateLocalDuplicateAuthorities(localEntries)
			?: return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		return selectedImportedWithLocalOwners(candidates, identities, range, localEntries)
	}

	private suspend fun selectedImportedWithLocalOwners(
		candidates: List<ImportedPressureHistoryCandidate>,
		identities: List<String>,
		range: com.adsamcik.tracker.stats.api.repository.HistoryRange,
		localEntries: List<PressureLogicalHistoryEntry>,
	): ImportedHistoryEligiblePage<PressureImportedHistoryEligibleEntry> {
		val collisionIds = localEntries.mapNotNullTo(linkedSetOf()) { entry ->
			val id = (entry.identity as? PressureHistoryEntryIdentity.Logical)?.logicalTrackingId
			id?.takeIf { logicalId -> PortablePressureOpaqueIdentity.derive(
				PortablePressureIdentityKind.LOGICAL_ENTRY, logicalId).value in identities }
		}
		val visible = database.nativeLogicalIdsVisibleInRange(collisionIds, range).mapTo(linkedSetOf()) {
			PortablePressureOpaqueIdentity.derive(PortablePressureIdentityKind.LOGICAL_ENTRY, it) }
		val entries = mutableListOf<PressureImportedHistoryEligibleEntry>()
		for (candidate in candidates) {
			currentCoroutineContext().ensureActive()
			val evaluation = importedEvaluator.evaluateCandidateInTransaction(candidate)
			when (val decision = evaluation.selectedEligibility(candidate.identity, visible)) {
				is PressureImportedEligibilityDecision.Accepted -> entries += decision.entry
				PressureImportedEligibilityDecision.Suppressed -> Unit
				is PressureImportedEligibilityDecision.Unavailable -> return unavailable(decision.reason)
			}
		}
		return ImportedHistoryEligiblePage.Available(entries)
	}

	private suspend fun ImportedPressureHistoryEvaluation.selectedEligibility(
		identity: String,
		visible: Set<PortablePressureOpaqueIdentity>,
	): PressureImportedEligibilityDecision {
		val decision = qualifyWithExactNativeOwners()
		if (decision != PressureImportedEligibilityDecision.Suppressed ||
			PortablePressureOpaqueIdentity(identity) in visible
		) {
			return decision
		}
		return when (val unsuppressed = toSharedHistoryEligibility(emptyMap())) {
			is PressureImportedEligibilityDecision.Accepted -> unsuppressed
			is PressureImportedEligibilityDecision.Unavailable -> unsuppressed
			PressureImportedEligibilityDecision.Suppressed -> error("Empty native authority cannot suppress an import")
		}
	}

	internal suspend fun selectRecent(limit: Int): List<PressureOnlyHistoryEntry> =
		database.withTransaction {
			require(limit in 1..ImportedPressureDao.MAX_HISTORY_ENTRY_CANDIDATES)
			val imported = when (
				val selection = importedEvaluator.selectRecentInTransaction(limit)
			) {
				is ImportedPressureHistorySelection.Available -> selection.evaluations
				is ImportedPressureHistorySelection.Unverifiable ->
					throw PressureHistoryPageDependencyOverflow(
						selection.reason.toPageUnavailableReason(),
					)
			}
			integrationObserver.record(
				TrackingHistoryIntegrationCheckpoint.PRESSURE_LIVE_SELECTOR,
			)
			val live = liveSelector.discoverRecentPressureOnlyInTransaction(limit)
			for (evaluation in imported) {
				currentCoroutineContext().ensureActive()
				// A corrupt import remains a value-free row in its source list. It cannot
				// suppress a native recording or enter shared qualified history.
				if (evaluation is ImportedPressureHistoryEvaluation.Unverifiable) {
					continue
				}
				when (val decision = evaluation.qualifyWithExactNativeOwners()) {
					is PressureImportedEligibilityDecision.Unavailable ->
						throw PressureHistoryPageDependencyOverflow(decision.reason)
					is PressureImportedEligibilityDecision.Accepted,
					PressureImportedEligibilityDecision.Suppressed -> Unit
				}
			}
			// Comparison-only owners stay outside this display page.
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
		importedRecencyAuthorityFailure()?.let { return it }
		return collectRecentImportedEligible(limit)
	}

	private suspend fun importedRecencyAuthorityFailure(): ImportedHistoryEligiblePage.Unavailable? {
		val recencyAuthorityFailure = try {
			importedEvaluator.recentRecencyAuthorityFailureInTransaction()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		}
		return recencyAuthorityFailure?.let { failure -> unavailable(failure.toPageUnavailableReason()) }
	}

	// Preserve ordered query, recency, budget and per-candidate rejection inside the one bounded traversal.
	@Suppress("ReturnCount")
	private suspend fun collectRecentImportedEligible(
		limit: Int,
	): ImportedHistoryEligiblePage<PressureImportedHistoryEligibleEntry> {
		val accepted = mutableListOf<PressureImportedHistoryEligibleEntry>()
		var beforeRecencyStartTimeMs: Long? = null
		var beforeRecencyTieIdentity: String? = null
		var scannedCandidates = 0
		while (scannedCandidates < PRESSURE_IMPORTED_ELIGIBLE_SCAN_BUDGET) {
			currentCoroutineContext().ensureActive()
			val remainingBudget = PRESSURE_IMPORTED_ELIGIBLE_SCAN_BUDGET - scannedCandidates
			val pageLimit = minOf(PRESSURE_IMPORTED_ELIGIBLE_PAGE_SIZE, remainingBudget)
			val finalBudgetPage = remainingBudget <= PRESSURE_IMPORTED_ELIGIBLE_PAGE_SIZE
			val probeLimit = pageLimit + if (finalBudgetPage) {
				1
			} else {
				0
			}
			val candidateProbe = readImportedCandidateProbe(
				probeLimit, beforeRecencyStartTimeMs, beforeRecencyTieIdentity,
			) ?: return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
			if (candidateProbe.isEmpty()) {
				break
			}
			importedCandidatePageFailure(candidateProbe, beforeRecencyStartTimeMs, beforeRecencyTieIdentity,
				finalBudgetPage, pageLimit)?.let { return it }
			val candidates = candidateProbe.take(pageLimit)
			appendEligibleImportedCandidates(candidates, accepted)?.let { return it }
			scannedCandidates += candidates.size
			if (candidateProbe.size < probeLimit) {
				break
			}
			val last = candidates.last()
			beforeRecencyStartTimeMs = last.recencyStartTimeMs
			beforeRecencyTieIdentity = last.recencyTieIdentity
		}
		return ImportedHistoryEligiblePage.Available(
			accepted.sortedWith(pressureImportedHistoryEligibleOrder).take(limit),
		)
	}

	private fun importedCandidatePageFailure(
		candidateProbe: List<ImportedPressureHistoryCandidate>,
		beforeRecencyStartTimeMs: Long?,
		beforeRecencyTieIdentity: String?,
		finalBudgetPage: Boolean,
		pageLimit: Int,
	): ImportedHistoryEligiblePage.Unavailable? {
		if (!isValidImportedPressureHistoryCandidatePage(
				candidateProbe, beforeRecencyStartTimeMs, beforeRecencyTieIdentity,
			)
		) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		val candidateBudgetExceeded = finalBudgetPage && candidateProbe.size > pageLimit
		if (candidateBudgetExceeded) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED)
		}
		return null
	}

	private suspend fun readImportedCandidateProbe(
		probeLimit: Int,
		beforeRecencyStartTimeMs: Long?,
		beforeRecencyTieIdentity: String?,
	): List<ImportedPressureHistoryCandidate>? = try {
		integrationObserver.record(TrackingHistoryIntegrationCheckpoint.PRESSURE_IMPORTED_CANDIDATE_PAGE)
		database.importedPressureDao().recentHistoryCandidatePage(
			limit = probeLimit,
			beforeRecencyStartTimeMs = beforeRecencyStartTimeMs,
			beforeRecencyTieIdentity = beforeRecencyTieIdentity,
		)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: RuntimeException) {
		null
	}

	private suspend fun appendEligibleImportedCandidates(
		candidates: List<ImportedPressureHistoryCandidate>,
		accepted: MutableList<PressureImportedHistoryEligibleEntry>,
	): ImportedHistoryEligiblePage.Unavailable? {
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
			when (val decision = evaluation.qualifyWithExactNativeOwners()) {
				is PressureImportedEligibilityDecision.Accepted -> accepted += decision.entry
				PressureImportedEligibilityDecision.Suppressed -> Unit
				is PressureImportedEligibilityDecision.Unavailable ->
					return unavailable(decision.reason)
			}
		}
		return null
	}

	private suspend fun loadExactLocalDuplicateEntries(
		identities: Sequence<String>,
	): List<PressureLogicalHistoryEntry> =
		portableReader.nativeEntriesForComparisonInTransaction(identities)

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
			if (previous != null && previous != authority) {
				return null
			}
			authorities[identity] = authority
		}
		return authorities
	}

	/** Each import's own keys resolve exact owners; unrelated imports cannot lend ownership. */
	private suspend fun ImportedPressureHistoryEvaluation.qualifyWithExactNativeOwners():
		PressureImportedEligibilityDecision {
		if (this is ImportedPressureHistoryEvaluation.Unverifiable) {
			return toSharedHistoryEligibility(emptyMap())
		}
		return qualifyExactNativeOwnerKeys(nativeOwnerKeys())
	}

	private fun ImportedPressureHistoryEvaluation.nativeOwnerKeys(): Sequence<String> = when (this) {
		is ImportedPressureHistoryEvaluation.Readable -> sequence {
			yield(latest.entry.identity.value)
			for (run in latest.entry.runs) {
				yield(run.identity.value)
				for (window in run.windows) yield(window.identity.value)
			}
		}
		is ImportedPressureHistoryEvaluation.Retained -> protectedIdentities.asSequence().map { it.value }
		is ImportedPressureHistoryEvaluation.Unverifiable -> error("Handled above")
	}

	// Keep exact owner loading and its ordered budget, storage and collision failures in the same snapshot.
	@Suppress("ReturnCount")
	private suspend fun ImportedPressureHistoryEvaluation.qualifyExactNativeOwnerKeys(
		keys: Sequence<String>,
	): PressureImportedEligibilityDecision {
		val local = try {
			integrationObserver.record(TrackingHistoryIntegrationCheckpoint.PRESSURE_LOCAL_DUPLICATE_SELECTOR)
			authenticateLocalDuplicateAuthorities(loadExactLocalDuplicateEntries(keys))
				?: return PressureImportedEligibilityDecision.Unavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: NativePortableOwnerBudgetExceeded) {
			return PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED)
		} catch (overflow: PressureHistoryPageDependencyOverflow) {
			return PressureImportedEligibilityDecision.Unavailable(overflow.reason.toNativeOwnerUnavailableReason())
		} catch (_: RuntimeException) {
			return PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		if (conflictsWithNativeOwners(local)) {
			return PressureImportedEligibilityDecision.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		return toSharedHistoryEligibility(local)
	}

	private fun ImportedPressureHistoryEvaluation.conflictsWithNativeOwners(
		local: Map<PortablePressureOpaqueIdentity, PressureLocalDuplicateAuthority>,
	): Boolean = when (this) {
		is ImportedPressureHistoryEvaluation.Readable -> local.keys.any { it != latest.entry.identity }
		is ImportedPressureHistoryEvaluation.Retained -> local.isNotEmpty()
		is ImportedPressureHistoryEvaluation.Unverifiable -> false
	}

	private fun SourceAwareHistoryPageUnavailableReason.toNativeOwnerUnavailableReason() = when (this) {
		SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT,
		SourceAwareHistoryPageUnavailableReason.LOGICAL_MEMBERSHIP_LIMIT,
		SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED ->
			SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
		else -> SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
	}

	private fun ImportedPressureHistoryEvaluation.toSharedHistoryEligibility(
		localAuthorities: Map<PortablePressureOpaqueIdentity, PressureLocalDuplicateAuthority>,
	): PressureImportedEligibilityDecision = when (this) {
		is ImportedPressureHistoryEvaluation.Unverifiable ->
			PressureImportedEligibilityDecision.Unavailable(
				reason.toPageUnavailableReason(),
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

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
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
		if (entry.pressure.productState == HistoryProductState.FAILED) {
			PressureImportedEligibilityDecision.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		} else if (expectedContentChecksum != null && digest != expectedContentChecksum) {
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

private fun ImportedPressureHistoryFailure.toPageUnavailableReason():
	SourceAwareHistoryPageUnavailableReason = when (this) {
	ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW ->
		SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
	ImportedPressureHistoryFailure.SOURCE_EVIDENCE_STATE_MISSING ->
		SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE
	else -> SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
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

	private const val LOCAL_ORIGIN_ORDER = 0
	private const val IMPORTED_ORIGIN_ORDER = 1
}
