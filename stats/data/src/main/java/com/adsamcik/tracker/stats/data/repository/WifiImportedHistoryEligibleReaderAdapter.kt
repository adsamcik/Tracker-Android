package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductCandidate
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPageEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentRequest
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Imported-only Wi-Fi bridge for one caller-owned shared-history Room transaction. */
@Singleton
internal class WifiImportedHistoryEligibleReaderAdapter internal constructor(
	private val pageEvaluator: ImportedWifiProductRecentPageEvaluator,
	private val localPortableReader: ReadLocalPortableCapturedWifi,
	private val limits: WifiImportedHistoryEligibleLimits,
) : WifiImportedHistoryEligibleReader {
	@Inject
	constructor(
		pageEvaluator: ImportedWifiProductRecentPageEvaluator,
		localPortableReader: ReadLocalPortableCapturedWifi,
	) : this(pageEvaluator, localPortableReader, WifiImportedHistoryEligibleLimits())

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<WifiImportedHistoryEligibleEntry> {
		require(limit > 0)
		if (limit > limits.maximumCandidates) return unavailable(readBudgetExceeded)
		val accepted = ArrayList<WifiImportedHistoryEligibleEntry>(limit)
		val localPortableByLogicalId =
			mutableMapOf<String, ReadLocalPortableCapturedWifiResult>()
		var scannedCandidates = 0
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: PortableWifiOpaqueIdentity? = null
		var sourceExhausted = false
		while (accepted.size < limit && scannedCandidates < limits.maximumCandidates) {
			currentCoroutineContext().ensureActive()
			val remaining = limits.maximumCandidates - scannedCandidates
			val pageLimit = minOf(limits.pageSize, remaining)
			val request = ImportedWifiProductRecentRequest(
				limit = pageLimit,
				beforeNewestMemberStartTimeMs = beforeStartTimeMs,
				beforeNewestMemberIdentity = beforeIdentity,
			)
			val page = try {
				pageEvaluator.selectRecentPageInTransaction(request)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				return unavailable(sourceIntegrityFailure)
			}
			if (!page.isValidAfter(request)) return unavailable(sourceIntegrityFailure)
			for (evaluation in page.evaluations) {
				currentCoroutineContext().ensureActive()
				scannedCandidates++
				when (
					val decision = evaluation.toEligibility(localPortableByLogicalId)
				) {
					is WifiImportedEligibilityDecision.Accepted -> {
						accepted += decision.entry
						if (accepted.size == limit) {
							return ImportedHistoryEligiblePage.Available(accepted)
						}
					}
					WifiImportedEligibilityDecision.Suppressed -> Unit
					is WifiImportedEligibilityDecision.Unavailable ->
						return unavailable(decision.reason)
				}
			}
			if (!page.hasMore) {
				sourceExhausted = true
				break
			}
			val last = page.evaluations.lastOrNull()
				?: return unavailable(sourceIntegrityFailure)
			beforeStartTimeMs = last.candidate.newestMemberStartTimeMs
			beforeIdentity = last.candidate.newestMemberIdentity
		}
		return if (sourceExhausted) {
			ImportedHistoryEligiblePage.Available(accepted)
		} else {
			unavailable(readBudgetExceeded)
		}
	}

	private suspend fun ImportedWifiProductEvaluation.toEligibility(
		localPortableByLogicalId: MutableMap<String, ReadLocalPortableCapturedWifiResult>,
	): WifiImportedEligibilityDecision = when (this) {
		is ImportedWifiProductEvaluation.Unverifiable ->
			WifiImportedEligibilityDecision.Unavailable(
				when (reason) {
					ImportedWifiProductFailure.DEPENDENCY_OVERFLOW -> readBudgetExceeded
					ImportedWifiProductFailure.SOURCE_EVIDENCE_STATE_MISSING ->
						recencyAuthorityUnavailable
					else -> sourceIntegrityFailure
				},
			)
		is ImportedWifiProductEvaluation.Readable ->
			readableEligibility(localPortableByLogicalId)
	}

	@Suppress("ReturnCount")
	private suspend fun ImportedWifiProductEvaluation.Readable.readableEligibility(
		localPortableByLogicalId: MutableMap<String, ReadLocalPortableCapturedWifiResult>,
	): WifiImportedEligibilityDecision {
		val newest = entry.runs.maxWithOrNull(
			compareBy(
				{ it.startTimeMs },
				{ it.identity.value },
			),
		) ?: return WifiImportedEligibilityDecision.Unavailable(recencyAuthorityUnavailable)
		if (candidate.newestMemberStartTimeMs != newest.startTimeMs ||
			candidate.newestMemberIdentity != newest.identity
		) {
			return WifiImportedEligibilityDecision.Unavailable(recencyAuthorityUnavailable)
		}
		collidingLocalLogicalTrackingId?.let { logicalId ->
			val local = try {
				localPortableByLogicalId[logicalId] ?: localPortableReader.readInTransaction(
					ExportPortableCapturedWifiRequest(logicalId),
				).also { localPortableByLogicalId[logicalId] = it }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				return WifiImportedEligibilityDecision.Unavailable(sourceIntegrityFailure)
			}
			return when (local) {
				is ReadLocalPortableCapturedWifiResult.Ready ->
					if (isReExportable && local.entry == entry) {
						WifiImportedEligibilityDecision.Suppressed
					} else {
						WifiImportedEligibilityDecision.Unavailable(sourceIntegrityFailure)
					}
				is ReadLocalPortableCapturedWifiResult.Outcome ->
					WifiImportedEligibilityDecision.Unavailable(
						if (local.result is ExportPortableCapturedWifiResult.Unverifiable &&
							local.result.reason == PortableWifiUnverifiableReason.DEPENDENCY_OVERFLOW
						) {
							readBudgetExceeded
						} else {
							sourceIntegrityFailure
						},
					)
			}
		}
		val publicEntry = try {
			toPublicWifiEntry()
		} catch (_: RuntimeException) {
			return WifiImportedEligibilityDecision.Unavailable(sourceIntegrityFailure)
		}
		val selection = publicEntry.importedSelection
			?: return WifiImportedEligibilityDecision.Unavailable(sourceIntegrityFailure)
		if (selection != candidate.selection) {
			return WifiImportedEligibilityDecision.Unavailable(sourceIntegrityFailure)
		}
		val recency = try {
			ImportedHistoryRecency(
				source = HistorySource.WIFI,
				newestMemberStartTimeMs = candidate.newestMemberStartTimeMs,
				newestMemberTieIdentity = ImportedHistoryRecencyTieIdentity(
					candidate.newestMemberIdentity.value,
				),
			)
		} catch (_: IllegalArgumentException) {
			return WifiImportedEligibilityDecision.Unavailable(recencyAuthorityUnavailable)
		}
		return try {
			WifiImportedEligibilityDecision.Accepted(
				WifiImportedHistoryEligibleEntry(publicEntry, selection, recency),
			)
		} catch (_: IllegalArgumentException) {
			WifiImportedEligibilityDecision.Unavailable(recencyAuthorityUnavailable)
		}
	}

	private fun unavailable(
		reason: SourceAwareHistoryPageUnavailableReason,
	) = ImportedHistoryEligiblePage.Unavailable(reason)

	private companion object {
		val readBudgetExceeded =
			SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
		val sourceIntegrityFailure =
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
		val recencyAuthorityUnavailable =
			SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE
	}
}

private fun ImportedWifiProductRecentPage.isValidAfter(
	request: ImportedWifiProductRecentRequest,
): Boolean {
	if (evaluations.size > request.limit || hasMore && evaluations.isEmpty()) return false
	val candidates = evaluations.map(ImportedWifiProductEvaluation::candidate)
	if (candidates.map(ImportedWifiProductCandidate::identity).distinct().size != candidates.size) {
		return false
	}
	val cursors = buildList {
		if (request.beforeNewestMemberStartTimeMs != null &&
			request.beforeNewestMemberIdentity != null
		) {
			add(
				request.beforeNewestMemberStartTimeMs to
					request.beforeNewestMemberIdentity.value,
			)
		}
		addAll(candidates.map {
			it.newestMemberStartTimeMs to it.newestMemberIdentity.value
		})
	}
	return cursors.zipWithNext().all { (left, right) ->
		left.first > right.first || left.first == right.first && left.second > right.second
	}
}

internal data class WifiImportedHistoryEligibleLimits(
	val maximumCandidates: Int = ImportedWifiDao.MAX_IMPORTED_ENTRIES,
	val pageSize: Int = ImportedWifiDao.MAX_HISTORY_ENTRY_CANDIDATES,
) {
	init {
		require(maximumCandidates in 1..ImportedWifiDao.MAX_IMPORTED_ENTRIES)
		require(pageSize in 1..ImportedWifiDao.MAX_HISTORY_ENTRY_CANDIDATES)
		require(pageSize <= maximumCandidates)
	}
}

private sealed interface WifiImportedEligibilityDecision {
	data class Accepted(
		val entry: WifiImportedHistoryEligibleEntry,
	) : WifiImportedEligibilityDecision

	data object Suppressed : WifiImportedEligibilityDecision

	data class Unavailable(
		val reason: SourceAwareHistoryPageUnavailableReason,
	) : WifiImportedEligibilityDecision
}
