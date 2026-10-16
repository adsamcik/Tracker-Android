package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

/** Metadata selection only. It grants no provider, source erase, export or reward authority. */
sealed interface OrganizationSessionId {
	/**
	 * Identifies a saved user/imported grouping annotation, independent of sensor acquisition.
	 */
	data class Annotation(val localId: String) : OrganizationSessionId
	/**
	 * Identifies the adapter for an original recording group.
	 */
	data class Recording(val adapterKey: String) : OrganizationSessionId
}

/**
 * User-selected UTC millisecond session grouping bounds; repository mutations validate the
 * interval.
 */
data class OrganizationInterval(val fromMs: Long, val toMs: Long)
/**
 * Existing local or built-in activity label identity and display name.
 */
data class OrganizationActivityLabel(val id: Long, val name: String)

/**
 * Editable grouping over saved data with optimistic revision/epoch, optional labels and honest
 * missing or active time bounds.
 */
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

	/**
	 * Selects the exact saved membership of a Location file by its archive identity.
	 */
	data class LocationFile(val archiveIdentity: String) : OrganizationSourceSelector {
		override val source = HistorySource.LOCATION
		init { requireOrganizationIdentity(archiveIdentity) }
	}

	/**
	 * Selects one typed captured entry, with optional paired import-job/entry membership for a
	 * selected-reading file.
	 */
	data class CapturedEntry(override val source: HistorySource, val entryIdentity: String,
		val importJobId: String? = null, val importEntryKey: String? = null) : OrganizationSourceSelector {
		init {
			require((importJobId == null) == (importEntryKey == null))
			require(importJobId?.let { it.isNotBlank() && it.length <= MAX_IMPORT_MEMBERSHIP_ID_LENGTH } != false)
			require(importEntryKey?.let { it.isNotBlank() && it.length <= MAX_IMPORT_MEMBERSHIP_ID_LENGTH } != false)
			require(source != HistorySource.LOCATION)
			if (source == HistorySource.STEPS || source == HistorySource.PRESSURE) {
				requireOrganizationPrefixedIdentity(entryIdentity)
			}
			else {
				requireOrganizationIdentity(entryIdentity)
			}
		}
	}

	/**
	 * Selects one original imported all-day Steps day identity.
	 */
	data class BackgroundStepsDay(val dayIdentity: String) : OrganizationSourceSelector {
		override val source = HistorySource.STEPS
		init { requireOrganizationPrefixedIdentity(dayIdentity) }
	}

	/**
	 * Selects exact imported all-day Wi-Fi or Cell archive membership.
	 */
	data class BackgroundRadioArchive(override val source: HistorySource,
        val archiveIdentity: String) : OrganizationSourceSelector {
		init {
			require(source == HistorySource.WIFI || source == HistorySource.CELL)
			requireOrganizationIdentity(archiveIdentity)
		}
	}

	/**
	 * Finite source-membership bound for one imported session annotation.
	 */
	companion object { const val MAX_COUNT = 4_096 }
}

private val organizationIdentity = Regex("[0-9a-f]{64}")
private val organizationPrefixedIdentity = Regex("sha256:[0-9a-f]{64}")
private fun requireOrganizationIdentity(value: String) { require(organizationIdentity.matches(value)) }
private fun requireOrganizationPrefixedIdentity(value: String) { require(organizationPrefixedIdentity.matches(value)) }

/**
 * Visible session groups, continuation availability and their data epoch, or unavailable
 * organization storage.
 */
sealed interface SessionOrganizationRead {
	/**
	 * Visible grouped sessions and their data epoch; hasMore means the bounded list has further
	 * entries.
	 */
	data class Content(val sessions: List<OrganizedSession>, val hasMore: Boolean,
		val collectedDataEpoch: Long) : SessionOrganizationRead
	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data object Unavailable : SessionOrganizationRead
}

/**
 * Invalid input, active recording, missing label, stale selection or storage issue preventing the
 * session edit.
 */
enum class OrganizationEditProblem {
	INVALID_INTERVAL, FUTURE_END, INVALID_ZONE, NAME_TOO_LONG, MISSING_ACTIVITY_LABEL,
	END_SESSION_FIRST, STALE, NOT_FOUND, STORAGE_UNAVAILABLE,
}
/**
 * Saved session organization, a specific input/state rejection, or unavailable storage.
 */
sealed interface OrganizationMutationResult {
	/**
	 * The requested session annotation change was saved.
	 */
	data object Saved : OrganizationMutationResult
	/**
	 * The session change was rejected for the specific input or current-state problem.
	 */
	data class Rejected(val problem: OrganizationEditProblem) : OrganizationMutationResult
}

/**
 * Reads and edits optional session organization over existing observations without creating sensor
 * or capture authority.
 */
interface SessionOrganizationRepository {
	/** Bounded metadata view. Null bounds request recent groups, not an unbounded payload read. */
	fun observe(fromMs: Long? = null, toMs: Long? = null, limit: Int = 100): Flow<SessionOrganizationRead>
	/**
	 * Reads at most the requested visible session count; range bounds are both null or a nonempty UTC
	 * millisecond interval.
	 */
	suspend fun read(fromMs: Long? = null, toMs: Long? = null, limit: Int = 100): SessionOrganizationRead
	/** Exact metadata lookup for editors, independent of paging/range position. */
	suspend fun find(id: OrganizationSessionId): SessionOrganizationRead
	/** Exact selected session updates, including edits/removal outside the currently paged list. */
	fun observeSession(id: OrganizationSessionId): Flow<SessionOrganizationRead> =
		kotlinx.coroutines.flow.flow { emit(find(id)) }
	/**
	 * Returns existing built-in and user activity labels available for session organization.
	 */
	suspend fun activityLabels(): List<OrganizationActivityLabel>
	/**
	 * Saves a retrospective grouping under the expected data epoch; validates bounds, zone, optional
	 * name and activity label.
	 */
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
	/**
	 * Removes the selected grouping after rechecking revision/epoch; recorded observations remain
	 * independently saved.
	 */
	suspend fun remove(selection: OrganizedSession): OrganizationMutationResult
}

private const val MAX_IMPORT_MEMBERSHIP_ID_LENGTH = 4_096
