package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

/** Metadata selection only. It grants no provider, source erase, export or reward authority. */
sealed interface OrganizationSessionId {
	data class Annotation(val localId: String) : OrganizationSessionId
	data class Recording(val adapterKey: String) : OrganizationSessionId
}

data class OrganizationInterval(val fromMs: Long, val toMs: Long)
data class OrganizationActivityLabel(val id: Long, val name: String)

data class OrganizedSession(
	val id: OrganizationSessionId,
	val revision: Long,
	val collectedDataEpoch: Long,
	val name: String?,
	val activityLabelId: Long?,
	val activityLabelName: String?,
	val displayZoneId: String,
	val fromMs: Long?,
	val toMs: Long?,
	/** Derived from acquisition, never stored as another organization lifecycle. */
	val isActive: Boolean,
	val timeUncertain: Boolean,
	/** Opens the original recording; edited bounds do not reattribute its source evidence. */
	val originalSegmentId: Long? = null,
	/** Null groups locally by interval; an imported bundle selects only these exact source records. */
	val importedSourceSelection: List<OrganizationSourceSelector>? = null,
)

/** Exact organization membership only. Source readers still own retention, corrections and payload access. */
sealed interface OrganizationSourceSelector {
	val source: HistorySource

	data class LocationFile(val archiveIdentity: String) : OrganizationSourceSelector {
		override val source = HistorySource.LOCATION
		init { requireOrganizationIdentity(archiveIdentity) }
	}

	data class CapturedEntry(override val source: HistorySource, val entryIdentity: String,
		val importJobId: String? = null, val importEntryKey: String? = null) : OrganizationSourceSelector {
		init {
			require((importJobId == null) == (importEntryKey == null))
			require(importJobId?.let { it.isNotBlank() && it.length <= 4_096 } != false)
			require(importEntryKey?.let { it.isNotBlank() && it.length <= 4_096 } != false)
			require(source != HistorySource.LOCATION)
			if (source == HistorySource.STEPS || source == HistorySource.PRESSURE) requireOrganizationPrefixedIdentity(entryIdentity)
			else requireOrganizationIdentity(entryIdentity)
		}
	}

	data class BackgroundStepsDay(val dayIdentity: String) : OrganizationSourceSelector {
		override val source = HistorySource.STEPS
		init { requireOrganizationPrefixedIdentity(dayIdentity) }
	}

	data class BackgroundRadioArchive(override val source: HistorySource, val archiveIdentity: String) : OrganizationSourceSelector {
		init {
			require(source == HistorySource.WIFI || source == HistorySource.CELL)
			requireOrganizationIdentity(archiveIdentity)
		}
	}

	companion object { const val MAX_COUNT = 4_096 }
}

private val organizationIdentity = Regex("[0-9a-f]{64}")
private val organizationPrefixedIdentity = Regex("sha256:[0-9a-f]{64}")
private fun requireOrganizationIdentity(value: String) { require(organizationIdentity.matches(value)) }
private fun requireOrganizationPrefixedIdentity(value: String) { require(organizationPrefixedIdentity.matches(value)) }

sealed interface SessionOrganizationRead {
	data class Content(val sessions: List<OrganizedSession>, val hasMore: Boolean,
		val collectedDataEpoch: Long) : SessionOrganizationRead
	data object Unavailable : SessionOrganizationRead
}

enum class OrganizationEditProblem {
	INVALID_INTERVAL, FUTURE_END, INVALID_ZONE, NAME_TOO_LONG, MISSING_ACTIVITY_LABEL,
	END_SESSION_FIRST, STALE, NOT_FOUND, STORAGE_UNAVAILABLE,
}
sealed interface OrganizationMutationResult {
	data object Saved : OrganizationMutationResult
	data class Rejected(val problem: OrganizationEditProblem) : OrganizationMutationResult
}

interface SessionOrganizationRepository {
	/** Bounded metadata view. Null bounds request recent groups, not an unbounded payload read. */
	fun observe(fromMs: Long? = null, toMs: Long? = null, limit: Int = 100): Flow<SessionOrganizationRead>
	suspend fun read(fromMs: Long? = null, toMs: Long? = null, limit: Int = 100): SessionOrganizationRead
	/** Exact metadata lookup for editors, independent of paging/range position. */
	suspend fun find(id: OrganizationSessionId): SessionOrganizationRead
	/** Exact selected session updates, including edits/removal outside the currently paged list. */
	fun observeSession(id: OrganizationSessionId): Flow<SessionOrganizationRead> =
		kotlinx.coroutines.flow.flow { emit(find(id)) }
	suspend fun activityLabels(): List<OrganizationActivityLabel>
	suspend fun create(expectedEpoch: Long, interval: OrganizationInterval,
		displayZoneId: String, name: String?, activityLabelId: Long?): OrganizationMutationResult
	/**
	 * Null intervalChange preserves exact existing instants, including zone-only edits.
	 * Descriptive imported labels survive ordinary edits; the existing No label action clears them
	 * explicitly because a null local label ID alone also means the imported label is unchanged.
	 */
	suspend fun edit(selection: OrganizedSession, intervalChange: OrganizationInterval?,
		displayZoneId: String, name: String?, activityLabelId: Long?,
		clearImportedActivityLabel: Boolean = false): OrganizationMutationResult
	suspend fun remove(selection: OrganizedSession): OrganizationMutationResult
}
