package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientStepsEffectiveGapInterval
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryDependency

internal sealed interface NativeAmbientStepsRangeRead {
	data class Ready(
		val factsByDay: Map<AmbientStepsHistoryKey, List<QualifiedAmbientStepsFact>>,
		val gapsByDay: Map<AmbientStepsHistoryKey, List<EffectiveAmbientStepsGap>>,
		val materializingDays: Set<AmbientStepsHistoryKey>,
		val unverifiableDays: Set<AmbientStepsHistoryKey>,
		val qualifiedMeasuredGaps: List<AmbientStepsEffectiveGapInterval> = emptyList(),
	) : NativeAmbientStepsRangeRead

	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : NativeAmbientStepsRangeRead

	data object Unverifiable : NativeAmbientStepsRangeRead
}

internal class NativeAmbientStepsRangeReader(
	private val database: AppDatabase,
) {
	suspend fun read(range: AmbientStepsRequestedRange, evidence: SourceEvidenceState): NativeAmbientStepsRangeRead =
		readSnapshot(range, evidence, selectedFactRows = null, selectedMeasuredGaps = null)

	/** Original selected records use the same qualifier without scanning unrelated days/cursors. */
	suspend fun readSelected(range: AmbientStepsRequestedRange, evidence: SourceEvidenceState,
		facts: List<AmbientStepsFactRevisionEntity>,
            gaps: List<AmbientStepsEffectiveGapInterval>): NativeAmbientStepsRangeRead {
		require(
			facts.size <= MAX_SELECTED_AMBIENT_STEPS_ITEMS &&
				facts.map { it.logicalFactId }.distinct().size == facts.size &&
				gaps.size <= MAX_SELECTED_AMBIENT_STEPS_ITEMS,
		)
		return readSnapshot(range, evidence, facts, gaps)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun readSnapshot(
		range: AmbientStepsRequestedRange, evidence: SourceEvidenceState,
		selectedFactRows: List<AmbientStepsFactRevisionEntity>?,
            selectedMeasuredGaps: List<AmbientStepsEffectiveGapInterval>?,
	): NativeAmbientStepsRangeRead {
		val factDao = database.ambientStepsFactRevisionDao()
		val stateDao = database.ambientStepsImportStateDao()
		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
		)
		val hasOwner = owner?.owner == SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS
		val hasNativeState = if (selectedFactRows != null) {
			selectedFactRows.isNotEmpty() || !selectedMeasuredGaps.isNullOrEmpty()
		}
		else {
			factDao.countAll() != 0L || stateDao.countCursors() != 0L ||
			stateDao.countGaps() != 0L || stateDao.countAuthorityTransitions() != 0L
		}
		if (!hasOwner) {
			return if (hasNativeState) {
				NativeAmbientStepsRangeRead.Unverifiable
			} else {
				NativeAmbientStepsRangeRead.Ready(emptyMap(), emptyMap(), emptySet(), emptySet())
			}
		}
		val ownerGeneration = requireNotNull(owner).ownerGeneration
		val activeCursors = if (selectedFactRows != null) {
			emptyList()
		} else {
			stateDao.activeCursors(MAX_ACTIVE_CURSORS + 1)
		}
		if (activeCursors.size > MAX_ACTIVE_CURSORS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_CURSORS)
		}
		val facts = selectedFactRows ?: factDao.latestEffectiveOverlapping(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			range.fromMs,
			range.toMs,
			MAX_NATIVE_FACTS + 1,
		)
		if (facts.size > MAX_NATIVE_FACTS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_FACTS)
		}
		val selectedFacts = facts.filter {
			it.historyKeyOrNull() in range.keys
		}
		if (selectedFactRows == null && factDao.countUnrecognizedPayloadRows(
				AmbientStepsFactRevisionEntity.WRITER_ID,
				AmbientStepsFactRevisionEntity.WRITER_VERSION,
			) != 0L
		) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}
		val revisions = mutableListOf<AmbientStepsFactRevisionEntity>()
		selectedFacts.map(AmbientStepsFactRevisionEntity::logicalFactId).distinct()
			.chunked(FACT_ID_QUERY_BATCH_SIZE).forEach { ids ->
				val page = factDao.revisionsForLogicalFacts(
					AmbientStepsFactRevisionEntity.WRITER_ID,
					AmbientStepsFactRevisionEntity.WRITER_VERSION,
					ids,
					MAX_NATIVE_REVISIONS - revisions.size + 1,
				)
				revisions += page
				if (revisions.size > MAX_NATIVE_REVISIONS) {
					return overflow(AmbientStepsHistoryDependency.NATIVE_FACTS)
				}
		}
		if (!selectedFacts.haveAuthenticatedNativeLineages(revisions)) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}
		val gaps = selectedMeasuredGaps ?: stateDao.effectiveGapIntervalsOverlapping(
			range.fromMs,
			range.toMs,
			MAX_NATIVE_GAPS + 1,
		)
		if (gaps.size > MAX_NATIVE_GAPS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_GAPS)
		}
		val registrations = buildSet {
			selectedFacts.mapNotNullTo(this) { it.registrationGeneration }
			gaps.mapTo(this) { it.registrationGeneration }
			gaps.mapNotNullTo(this) { it.predecessorRegistrationGeneration }
			activeCursors.mapTo(this) { it.registrationGeneration }
		}.sorted()
		if (registrations.size > MAX_NATIVE_CURSORS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_CURSORS)
		}
		val allGaps = if (selectedFactRows == null) {
			stateDao.maintenanceGaps(MAX_NATIVE_GAPS + 1)
		}
		else if (registrations.isEmpty()) {
			emptyList()
		} else {
			stateDao.gapsForRegistrations(registrations, MAX_NATIVE_GAPS + 1)
		}
		if (allGaps.size > MAX_NATIVE_GAPS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_GAPS)
		}
		val cursors = if (registrations.isEmpty()) {
			emptyList()
		} else {
			stateDao.cursors(registrations)
		}
		if (cursors.size != registrations.size) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}
		val allGapsByRegistration = allGaps.groupBy(AmbientStepsImportGapEntity::registrationGeneration)
		if (cursors.any { cursor ->
				!cursor.hasCompleteNativeGapSequence(
					allGapsByRegistration[cursor.registrationGeneration].orEmpty(), evidence,
				)
			}
		) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}
		val authorityRegistrations = buildSet {
			selectedFacts.mapNotNullTo(this) { it.registrationGeneration }
			activeCursors.mapTo(this) { it.registrationGeneration }
		}.sorted()
		val transitions = if (authorityRegistrations.isEmpty()) {
			emptyList()
		} else {
			stateDao.authorityTransitionsBounded(
				authorityRegistrations,
				MAX_NATIVE_AUTHORITY_ROWS + 1,
			)
		}
		if (transitions.size > MAX_NATIVE_AUTHORITY_ROWS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_AUTHORITY)
		}
		val authorizationKeys = buildSet {
			cursors.filter { it.registrationGeneration in authorityRegistrations }.forEach {
				add(NativeAmbientAuthorizationKey(it.registrationGeneration, it.authorizationRevision))
			}
			transitions.forEach {
				add(NativeAmbientAuthorizationKey(
					it.registrationGeneration,
					it.fromAuthorizationRevision,
				))
				add(NativeAmbientAuthorizationKey(
					it.registrationGeneration,
					it.toAuthorizationRevision,
				))
			}
		}
		if (authorizationKeys.size > MAX_NATIVE_AUTHORITY_ROWS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_AUTHORITY)
		}
		val authorizationRows = if (authorizationKeys.isEmpty()) {
			emptyList()
		} else {
			database.sourceBrokerDao().authorizationRevisionsBounded(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				authorizationKeys.map { it.registrationGeneration }.distinct(),
				authorizationKeys.map { it.authorizationRevision }.distinct(),
				MAX_NATIVE_AUTHORIZATION_MEMBERS + 1,
			)
		}
		if (authorizationRows.size > MAX_NATIVE_AUTHORIZATION_MEMBERS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_AUTHORITY)
		}
		val authorizations = authorizationRows.groupBy {
			NativeAmbientAuthorizationKey(it.registrationGeneration, it.authorizationRevision)
		}.mapValues { (key, rows) -> NativeAmbientHistoricalAuthorization.fromRows(rows, key) }
		if (authorizationKeys.any { authorizations[it] == null }) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}
		val demandIds = authorizationRows.mapNotNull(SourceAuthorizationEntity::demandId).distinct()
		if (demandIds.size > MAX_NATIVE_AUTHORIZATION_MEMBERS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_AUTHORITY)
		}
		val demandsById = if (demandIds.isEmpty()) {
			emptyMap()
		} else {
			database.sourceBrokerDao().demandsByIds(demandIds)
				.associateBy(SourceDemandEntity::demandId)
		}
		if (demandsById.size != demandIds.size ||
			authorizationRows.groupBy {
				NativeAmbientAuthorizationKey(it.registrationGeneration, it.authorizationRevision)
			}.any { (_, rows) -> !NativeAmbientHistoricalAuthorization.haveAuthenticDemands(rows, demandsById) }
		) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}
		val policyRevisions = buildSet {
			selectedFacts.mapNotNullTo(this) { it.sourcePolicyRevision }
			cursors.mapTo(this) { it.sourcePolicyRevision }
			transitions.forEach {
				add(it.fromSourcePolicyRevision)
				add(it.toSourcePolicyRevision)
			}
		}.sorted()
		if (policyRevisions.size > MAX_NATIVE_AUTHORITY_ROWS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_AUTHORITY)
		}
		val policies = if (policyRevisions.isEmpty()) {
			emptyList()
		} else {
			database.sourcePolicyDao().policiesAtRevisions(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				policyRevisions,
			)
		}
		if (policies.size != policyRevisions.size) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}
		val consentEpochs = buildSet {
			selectedFacts.mapNotNullTo(this) { it.ambientConsentEpoch }
			cursors.mapTo(this) { it.ambientConsentEpoch }
			transitions.forEach {
				add(it.fromAmbientConsentEpoch)
				add(it.toAmbientConsentEpoch)
			}
		}.sorted()
		if (consentEpochs.size > MAX_NATIVE_AUTHORITY_ROWS) {
			return overflow(AmbientStepsHistoryDependency.NATIVE_AUTHORITY)
		}
		val consents = if (consentEpochs.isEmpty()) {
			emptyList()
		} else {
			database.sourcePolicyDao().consentEpochs(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
				consentEpochs,
			)
		}
		if (consents.size != consentEpochs.size) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}

		val cursorsByGeneration = cursors.associateBy(AmbientStepsImportCursorEntity::registrationGeneration)
		val policiesByRevision = policies.associateBy(SourcePolicyEntity::policyRevision)
		val consentsByEpoch = consents.associateBy(SourceConsentEpochEntity::epoch)
		val transitionsByRegistration = transitions.groupBy(
			AmbientStepsImportAuthorityTransitionEntity::registrationGeneration,
		)
		val timelines = cursorsByGeneration.mapValues { (generation, cursor) ->
			NativeAmbientAuthorityTimeline.create(
				cursor,
				transitionsByRegistration[generation].orEmpty(),
				authorizations,
				policiesByRevision,
				consentsByEpoch,
			)
		}
		if (activeCursors.any { timelines[it.registrationGeneration] == null }) {
			return NativeAmbientStepsRangeRead.Unverifiable
		}
		val factsByDay = linkedMapOf<AmbientStepsHistoryKey, MutableList<QualifiedAmbientStepsFact>>()
		val unverifiable = linkedSetOf<AmbientStepsHistoryKey>()
		val revisionsByFact = revisions.groupBy(AmbientStepsFactRevisionEntity::logicalFactId)
		selectedFacts.forEach { fact ->
			val key = fact.historyKeyOrNull() ?: return@forEach
			val day = range.days.singleOrNull { it.historyKey == key }
			val lineage = revisionsByFact[fact.logicalFactId].orEmpty()
			if (day == null || lineage.filter {
					it.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT
				}.any { revision ->
					!revision.hasValidNativeHistoryAuthority(
						day,
						ownerGeneration,
						evidence,
						revision.registrationGeneration?.let(cursorsByGeneration::get),
						revision.registrationGeneration?.let(timelines::get),
						revision.sourcePolicyRevision?.let(policiesByRevision::get),
						revision.ambientConsentEpoch?.let(consentsByEpoch::get),
					)
				}
			) {
				unverifiable += key
			} else {
				factsByDay.getOrPut(key, ::mutableListOf) += fact.toNativeQualifiedFact(day)
			}
		}
		val gapsByDay = linkedMapOf<AmbientStepsHistoryKey, MutableList<EffectiveAmbientStepsGap>>()
		val qualifiedMeasuredGaps = mutableListOf<AmbientStepsEffectiveGapInterval>()
		gaps.forEach { gap ->
			val qualification = gap.qualifyNativeGap(evidence, cursorsByGeneration)
			if (selectedMeasuredGaps != null && qualification.valid && qualification.effect != null) {
				qualifiedMeasuredGaps += gap.copy(gapStartTimeMs = qualification.effect.startTimeMs,
					gapEndTimeMs = qualification.effect.endTimeMs)
			}
			range.days.filter {
				gap.gapEndTimeMs > it.startTimeMs && gap.gapStartTimeMs < it.endTimeMs
			}.forEach { day ->
				if (!qualification.valid) {
					unverifiable += day.historyKey
				} else {
					qualification.effect?.let {
						gapsByDay.getOrPut(day.historyKey, ::mutableListOf) += it
					}
				}
			}
		}
		val materializing = if (selectedFactRows != null) {
			emptySet()
		} else {
			materializingDays(
			range,
			evidence,
			activeCursors,
			factsByDay,
			gapsByDay,
		)
		}
		return NativeAmbientStepsRangeRead.Ready(
			factsByDay.mapValues { it.value.toList() },
			gapsByDay.mapValues { it.value.toList() },
			materializing,
			unverifiable,
			qualifiedMeasuredGaps,
		)
	}

	private suspend fun materializingDays(
		range: AmbientStepsRequestedRange,
		evidence: SourceEvidenceState,
		activeCursors: List<AmbientStepsImportCursorEntity>,
		factsByDay: Map<AmbientStepsHistoryKey, List<QualifiedAmbientStepsFact>>,
		gapsByDay: Map<AmbientStepsHistoryKey, List<EffectiveAmbientStepsGap>>,
	): Set<AmbientStepsHistoryKey> {
		val authority = database.sourcePolicyDao().authority()
		val policy = authority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		}?.let {
			database.sourcePolicyDao().policyAtRevision(
				it.currentPolicyRevision,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			)
		}
		val consent = database.sourcePolicyDao().latestConsentEpoch(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		if (policy == null || consent == null || !policy.hasMaterializingNativeConsent(consent)) {
			return emptySet()
		}
		return activeCursors.singleOrNull().materializingNativeDayKeys(
			range, evidence, policy, consent, factsByDay, gapsByDay,
		)
	}

	private fun overflow(dependency: AmbientStepsHistoryDependency) =
		NativeAmbientStepsRangeRead.DependencyOverflow(dependency)

	private companion object {
		const val MAX_ACTIVE_CURSORS = 1
		const val MAX_NATIVE_FACTS = 65_536
		const val MAX_NATIVE_REVISIONS = 65_536
		const val MAX_NATIVE_GAPS = 16_384
		const val MAX_NATIVE_CURSORS = 400
		const val MAX_NATIVE_AUTHORITY_ROWS = 400
		const val MAX_NATIVE_AUTHORIZATION_MEMBERS = 400
		const val FACT_ID_QUERY_BATCH_SIZE = 400
	}
}

private fun SourcePolicyEntity.hasMaterializingNativeConsent(consent: SourceConsentEpochEntity): Boolean =
	enabled && ambientPersistenceEligible && ambientConsentEpoch == consent.epoch &&
		consent.eligible && consent.persistenceEligible && consent.policyRevision <= policyRevision

private fun AmbientStepsImportCursorEntity?.materializingNativeDayKeys(
	range: AmbientStepsRequestedRange,
	evidence: SourceEvidenceState,
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	factsByDay: Map<AmbientStepsHistoryKey, List<QualifiedAmbientStepsFact>>,
	gapsByDay: Map<AmbientStepsHistoryKey, List<EffectiveAmbientStepsGap>>,
): Set<AmbientStepsHistoryKey> {
	if (this == null || collectedDataEpoch != evidence.collectedDataEpoch ||
		sourcePolicyRevision != policy.policyRevision || ambientConsentEpoch != consent.epoch
	) {
		return range.days.filter { factsByDay[it.historyKey].isNullOrEmpty() }
			.mapTo(linkedSetOf()) { it.historyKey }
	}
	return range.days.filter { day ->
		factsByDay[day.historyKey].isNullOrEmpty() && gapsByDay[day.historyKey].isNullOrEmpty() ||
			segmentStartTimeMs < day.endTimeMs && importedThroughTimeMs < day.endTimeMs
	}.mapTo(linkedSetOf()) { it.historyKey }
}

private fun AmbientStepsImportCursorEntity.hasCompleteNativeGapSequence(
	gaps: List<AmbientStepsImportGapEntity>,
	evidence: SourceEvidenceState,
): Boolean = gaps.size.toLong() == lastGapSequence &&
	gaps.withIndex().none { (index, gap) -> !gap.matchesNativeGapSequence(this, index, evidence) }

private fun AmbientStepsImportGapEntity.matchesNativeGapSequence(
	cursor: AmbientStepsImportCursorEntity,
	index: Int,
	evidence: SourceEvidenceState,
): Boolean = gapSequence == index + 1L && provider == cursor.provider &&
	sourceInstanceId == cursor.sourceInstanceId && evidence.acceptsRetainedEpoch(collectedDataEpoch) &&
	recordedAtMs <= cursor.updatedAtMs && gapEndTimeMs <= cursor.importedThroughTimeMs

private fun AmbientStepsFactRevisionEntity.historyKeyOrNull(): AmbientStepsHistoryKey? {
	val epochDay = structuralEpochDay ?: return null
	val zone = storedZoneId ?: return null
	return AmbientStepsHistoryKey(epochDay, zone)
}

private fun List<AmbientStepsFactRevisionEntity>.haveAuthenticatedNativeLineages(
	revisions: List<AmbientStepsFactRevisionEntity>,
): Boolean {
	val byId = revisions.groupBy(AmbientStepsFactRevisionEntity::logicalFactId)
	if (byId.keys != map(AmbientStepsFactRevisionEntity::logicalFactId).toSet()) {
		return false
	}
	return all { selected ->
		val lineage = byId[selected.logicalFactId].orEmpty()
		val first = lineage.firstOrNull() ?: return@all false
		if (lineage.withIndex().any { (index, revision) ->
				!revision.hasExactNativeLineageHeader(first, index)
			}
		) {
			return@all false
		}
		val retractions = lineage.filter {
			it.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT
		}
		if (retractions.size > 1 ||
			retractions.singleOrNull()?.let { it != lineage.last() } == true
		) {
			return@all false
		}
		val upserts = lineage.filter {
			it.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT
		}
		val origin = upserts.firstOrNull() ?: return@all false
		if (upserts.any { !it.hasSameNativeStableOrigin(origin) }) {
			return@all false
		}
		lineage.last() == selected &&
			selected.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT
	}
}

// One retained revision keeps its complete immutable lineage header and checksum.
private fun AmbientStepsFactRevisionEntity.hasExactNativeLineageHeader(
	first: AmbientStepsFactRevisionEntity,
	index: Int,
): Boolean =
	logicalFactId == first.logicalFactId &&
		semanticRevision == index + 1L &&
		writerId == first.writerId &&
		writerVersion == first.writerVersion &&
		writerOwnerGeneration == first.writerOwnerGeneration &&
		collectedDataEpoch == first.collectedDataEpoch &&
		purpose == first.purpose &&
		AmbientStepsFactIntegrity.hasValidEffectChecksum(this)

private fun AmbientStepsFactRevisionEntity.hasSameNativeStableOrigin(
	other: AmbientStepsFactRevisionEntity,
): Boolean = provider == other.provider &&
	registrationGeneration == other.registrationGeneration &&
	continuitySegmentGeneration == other.continuitySegmentGeneration &&
	sourceInstanceId == other.sourceInstanceId &&
	windowStartTimeMs == other.windowStartTimeMs &&
	structuralEpochDay == other.structuralEpochDay &&
	storedZoneId == other.storedZoneId &&
	structuralDayStartTimeMs == other.structuralDayStartTimeMs &&
	structuralDayEndTimeMs == other.structuralDayEndTimeMs

private fun AmbientStepsFactRevisionEntity.hasValidNativeHistoryAuthority(
	day: AmbientStepsDayIdentity,
	ownerGeneration: Long,
	evidence: SourceEvidenceState,
	cursor: AmbientStepsImportCursorEntity?,
	timeline: NativeAmbientAuthorityTimeline?,
	policy: SourcePolicyEntity?,
	consent: SourceConsentEpochEntity?,
): Boolean {
	val start = windowStartTimeMs ?: return false
	val end = windowEndTimeMs ?: return false
	return hasValidNativeOwnerAndRetention(ownerGeneration, evidence, start) &&
		hasMatchingNativeCalendar(day, start, end) &&
		hasNativeProviderAuthority(cursor, timeline, start, end) &&
		hasNativePolicyAuthority(policy, consent, start)
}

private fun AmbientStepsFactRevisionEntity.hasValidNativeOwnerAndRetention(
	ownerGeneration: Long,
	evidence: SourceEvidenceState,
	start: Long,
): Boolean = AmbientStepsFactIntegrity.hasValidEffectChecksum(this) &&
	writerOwnerGeneration == ownerGeneration && evidence.acceptsRetainedEpoch(collectedDataEpoch) &&
	(evidence.retainedFromMs?.let { start >= it } ?: true)

private fun AmbientStepsFactRevisionEntity.hasMatchingNativeCalendar(
	day: AmbientStepsDayIdentity,
	start: Long,
	end: Long,
): Boolean = historyKeyOrNull() == day.historyKey && structuralDayStartTimeMs == day.startTimeMs &&
	structuralDayEndTimeMs == day.endTimeMs && start >= day.startTimeMs && end <= day.endTimeMs

private fun AmbientStepsFactRevisionEntity.hasNativeProviderAuthority(
	cursor: AmbientStepsImportCursorEntity?,
	timeline: NativeAmbientAuthorityTimeline?,
	start: Long,
	end: Long,
): Boolean = cursor != null && cursor.provider == provider && cursor.sourceInstanceId == sourceInstanceId &&
	cursor.registrationGeneration == registrationGeneration && cursor.collectedDataEpoch == collectedDataEpoch &&
	cursor.registrationAcceptedAtMs <= start && cursor.importedThroughTimeMs >= end && timeline?.accepts(this) == true

private fun AmbientStepsFactRevisionEntity.hasNativePolicyAuthority(
	policy: SourcePolicyEntity?,
	consent: SourceConsentEpochEntity?,
	start: Long,
): Boolean = policy != null && hasNativePolicyAt(policy, start) &&
	consent != null && hasNativeConsentAt(consent, policy, start)

private fun AmbientStepsFactRevisionEntity.hasNativePolicyAt(policy: SourcePolicyEntity, start: Long): Boolean =
	policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && policy.policyRevision == sourcePolicyRevision &&
	policy.enabled && policy.ambientPersistenceEligible && policy.ambientConsentEpoch == ambientConsentEpoch &&
	policy.effectiveWallTimeMs <= start

private fun AmbientStepsFactRevisionEntity.hasNativeConsentAt(
	consent: SourceConsentEpochEntity,
	policy: SourcePolicyEntity,
	start: Long,
): Boolean = consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
	consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && consent.epoch == ambientConsentEpoch &&
	consent.policyRevision <= policy.policyRevision && consent.eligible && consent.persistenceEligible &&
	consent.effectiveWallTimeMs <= start

private fun AmbientStepsFactRevisionEntity.toNativeQualifiedFact(
	day: AmbientStepsDayIdentity,
) = QualifiedAmbientStepsFact(
	logicalFactId = logicalFactId,
	day = day,
	startTimeMs = requireNotNull(windowStartTimeMs),
	endTimeMs = requireNotNull(windowEndTimeMs),
	stepCount = requireNotNull(stepCount),
	provenance = AmbientStepsProviderProvenance(
		requireNotNull(provider),
		requireNotNull(sourceInstanceId),
		requireNotNull(registrationGeneration),
		requireNotNull(continuitySegmentGeneration),
	),
	portableIdentity = AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.FACT,
		logicalFactId,
	).value,
	correctionRevision = semanticRevision,
	contentChecksum = PortableAmbientStepsFactV1.create(
		AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.FACT,
			logicalFactId,
		),
		requireNotNull(windowStartTimeMs),
		requireNotNull(windowEndTimeMs),
		requireNotNull(stepCount),
	).contentChecksum.value,
	countDomainOwner = countDomainOwnerReferenceOrNull(),
)

private data class NativeAmbientAuthorizationKey(
	val registrationGeneration: Long,
	val authorizationRevision: Long,
)

private data class NativeAmbientHistoricalAuthorization(
	val key: NativeAmbientAuthorizationKey,
	val fingerprint: String,
	val effectiveBootId: String,
	val effectiveElapsedRealtimeNanos: Long,
	val effectiveWallTimeMs: Long,
	val sourcePolicyRevision: Long,
	val ambientConsentEpoch: Long,
) {
	companion object {
		fun fromRows(
			rows: List<SourceAuthorizationEntity>, key: NativeAmbientAuthorizationKey,
		): NativeAmbientHistoricalAuthorization? {
			val first = rows.firstOrNull() ?: return null
			if (first.hasInvalidNativeAmbientAuthorizationShape(key)) {
				return null
			}
			if (rows.any { it.differsFromNativeAmbientAuthorization(first) }) {
				return null
			}
			return NativeAmbientHistoricalAuthorization(
				key,
				first.authorizationFingerprint,
				first.effectiveBootId,
				first.effectiveElapsedRealtimeNanos,
				first.effectiveWallTimeMs,
				requireNotNull(first.sourcePolicyRevision),
				requireNotNull(first.consentEpoch),
			)
		}

		// Complete immutable historical row shape; keep every scope/null field visible in one contract.
		@Suppress("CyclomaticComplexMethod", "ComplexCondition")
		private fun SourceAuthorizationEntity.hasInvalidNativeAmbientAuthorizationShape(
			key: NativeAmbientAuthorizationKey,
		): Boolean = this.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			this.registrationGeneration != key.registrationGeneration ||
			this.authorizationRevision != key.authorizationRevision || this.isDenyAll ||
			this.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT || !this.persistenceEligible ||
			this.sourcePolicyRevision == null || this.consentEpoch == null ||
			this.purposeEligibilityMask != SourceBrokerPurpose.MASK_AMBIENT_PRODUCT ||
			this.logicalTrackingId != null || this.serviceRunId != null ||
			this.manifestRevision != null || this.lifecycleLeaseGeneration != null

		// One sibling row must match the whole saved authorization tuple, without query or policy evaluation.
		@Suppress("CyclomaticComplexMethod", "ComplexCondition")
		private fun SourceAuthorizationEntity.differsFromNativeAmbientAuthorization(
			first: SourceAuthorizationEntity,
		): Boolean =
			this.sourceKind != first.sourceKind ||
						this.registrationGeneration != first.registrationGeneration ||
						this.authorizationRevision != first.authorizationRevision ||
						this.authorizationFingerprint != first.authorizationFingerprint ||
						this.purposeEligibilityMask != first.purposeEligibilityMask ||
						this.effectiveBootId != first.effectiveBootId ||
						this.effectiveElapsedRealtimeNanos != first.effectiveElapsedRealtimeNanos ||
						this.effectiveWallTimeMs != first.effectiveWallTimeMs || this.isDenyAll ||
						this.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT || !this.persistenceEligible ||
						this.sourcePolicyRevision != first.sourcePolicyRevision ||
						this.consentEpoch != first.consentEpoch || this.logicalTrackingId != null ||
						this.serviceRunId != null || this.manifestRevision != null ||
						this.lifecycleLeaseGeneration != null


		@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
		fun haveAuthenticDemands(
			rows: List<SourceAuthorizationEntity>, demandsById: Map<String, SourceDemandEntity>,
		): Boolean {
			val first = rows.firstOrNull() ?: return false
			val ids = rows.mapNotNull(SourceAuthorizationEntity::demandId)
			if (ids.size != rows.size || ids.distinct().size != ids.size) {
				return false
			}
			val demands = ids.map { demandsById[it] ?: return false }
			if (SourceBrokerAuthorization.fingerprint(demands) != first.authorizationFingerprint) {
				return false
			}
			if (rows.zip(demands).any { (row, demand) -> !row.matchesNativeAmbientDemand(demand) }) {
				return false
			}
			return demands.all { it.hasNativeAmbientAuthority(first) }
		}

		private fun SourceAuthorizationEntity.matchesNativeAmbientDemand(demand: SourceDemandEntity): Boolean =
			demandId == demand.demandId && consumerId == demand.consumerId &&
			memberId == SourceBrokerAuthorization.memberId(demand.demandId) && purpose == demand.purpose &&
			sourcePolicyRevision == demand.sourcePolicyRevision && consentEpoch == demand.consentEpoch &&
			persistenceEligible == demand.persistenceEligible

		private fun SourceDemandEntity.hasNativeAmbientAuthority(first: SourceAuthorizationEntity): Boolean =
			sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
			persistenceEligible && sourcePolicyRevision == first.sourcePolicyRevision && consentEpoch == first.consentEpoch &&
			hasSessionlessNativeAmbientClock(first)

		private fun SourceDemandEntity.hasSessionlessNativeAmbientClock(first: SourceAuthorizationEntity): Boolean =
			logicalTrackingId == null && serviceRunId == null && manifestRevision == null && lifecycleLeaseGeneration == null &&
			requestedBootId == first.effectiveBootId && requestedElapsedRealtimeNanos <= first.effectiveElapsedRealtimeNanos &&
			requestedAtMs <= first.effectiveWallTimeMs
	}
}

private data class NativeAmbientStoredAuthority(
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val sourcePolicyRevision: Long,
	val ambientConsentEpoch: Long,
) {
	fun matches(fact: AmbientStepsFactRevisionEntity): Boolean =
		fact.authorizationRevision == authorizationRevision &&
			fact.authorizationFingerprint == authorizationFingerprint &&
			fact.sourcePolicyRevision == sourcePolicyRevision &&
			fact.ambientConsentEpoch == ambientConsentEpoch

	fun matches(authorization: NativeAmbientHistoricalAuthorization): Boolean =
		authorizationRevision == authorization.key.authorizationRevision &&
			authorizationFingerprint == authorization.fingerprint &&
			sourcePolicyRevision == authorization.sourcePolicyRevision &&
			ambientConsentEpoch == authorization.ambientConsentEpoch

	// One supplied historical boundary must agree with the complete saved policy and consent tuple.
	@Suppress("CyclomaticComplexMethod")
	fun hasDurablePolicyAt(
		boundaryMs: Long,
		policies: Map<Long, SourcePolicyEntity>,
		consents: Map<Long, SourceConsentEpochEntity>,
	): Boolean {
		val policy = policies[sourcePolicyRevision] ?: return false
		val consent = consents[ambientConsentEpoch] ?: return false
		return policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			policy.policyRevision == sourcePolicyRevision && policy.enabled &&
			policy.ambientPersistenceEligible && policy.ambientConsentEpoch == ambientConsentEpoch &&
			policy.effectiveWallTimeMs <= boundaryMs &&
			consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
			consent.epoch == ambientConsentEpoch && consent.policyRevision <= policy.policyRevision &&
			consent.eligible && consent.persistenceEligible && consent.effectiveWallTimeMs <= boundaryMs
	}
}

private data class NativeAmbientAuthorityPhase(
	val firstSegment: Long,
	val lastSegment: Long,
	val startMs: Long,
	val endMs: Long,
	val authority: NativeAmbientStoredAuthority,
) {
	fun accepts(fact: AmbientStepsFactRevisionEntity): Boolean {
		val segment = fact.continuitySegmentGeneration ?: return false
		val start = fact.windowStartTimeMs ?: return false
		val end = fact.windowEndTimeMs ?: return false
		return segment in firstSegment..lastSegment && start >= startMs && end <= endMs &&
			end > start && authority.matches(fact)
	}
}

private class NativeAmbientAuthorityTimeline(
	private val phases: List<NativeAmbientAuthorityPhase>,
) {
	fun accepts(fact: AmbientStepsFactRevisionEntity): Boolean = phases.any { it.accepts(fact) }

	companion object {
		@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
		fun create(
			cursor: AmbientStepsImportCursorEntity,
			transitions: List<AmbientStepsImportAuthorityTransitionEntity>,
			authorizations: Map<NativeAmbientAuthorizationKey, NativeAmbientHistoricalAuthorization?>,
			policies: Map<Long, SourcePolicyEntity>,
			consents: Map<Long, SourceConsentEpochEntity>,
		): NativeAmbientAuthorityTimeline? {
			if (transitions.size.toLong() != cursor.authorityTransitionSequence ||
				transitions.withIndex().any { (index, transition) ->
					transition.transitionSequence != index + 1L ||
						!transition.hasNativeOrigin(cursor) ||
						transition.recordedAtMs > cursor.updatedAtMs
				}
			) {
				return null
			}
			val current = NativeAmbientStoredAuthority(
				cursor.authorizationRevision,
				cursor.authorizationFingerprint,
				cursor.sourcePolicyRevision,
				cursor.ambientConsentEpoch,
			)
			val currentAuthorization = authorizations[NativeAmbientAuthorizationKey(
				cursor.registrationGeneration,
				cursor.authorizationRevision,
			)] ?: return null
			if (!cursor.hasMatchingNativeCurrentAuthorization(current, currentAuthorization)) {
				return null
			}
			if (transitions.isEmpty()) {
				if (!current.hasDurablePolicyAt(cursor.eligibleFromTimeMs, policies, consents)) {
					return null
				}
				return NativeAmbientAuthorityTimeline(listOf(
					NativeAmbientAuthorityPhase(
						1L,
						cursor.continuitySegmentGeneration,
						cursor.eligibleFromTimeMs,
						cursor.importedThroughTimeMs,
						current,
					),
				))
			}
			val phases = mutableListOf<NativeAmbientAuthorityPhase>()
			var firstSegment = 1L
			val initialAuthorization = authorizations[NativeAmbientAuthorizationKey(
				cursor.registrationGeneration,
				transitions.first().fromAuthorizationRevision,
			)] ?: return null
			if (initialAuthorization.effectiveBootId != cursor.registrationClockDomainId ||
				initialAuthorization.effectiveElapsedRealtimeNanos <
				cursor.registrationAcceptedElapsedRealtimeNanos
			) {
				return null
			}
			var phaseStart = AmbientStepsImportCursorEntity.privacyFloorTimeMs(
				cursor.registrationAcceptedAtMs,
				initialAuthorization.effectiveWallTimeMs,
			)
			var expected: NativeAmbientStoredAuthority? = null
			var previousBoundary = cursor.registrationAcceptedAtMs
			var previousElapsed = initialAuthorization.effectiveElapsedRealtimeNanos
			transitions.forEach { transition ->
				val from = transition.nativeFromAuthority()
				val to = transition.nativeToAuthority()
				val fromAuthorization = authorizations[NativeAmbientAuthorizationKey(
					transition.registrationGeneration,
					transition.fromAuthorizationRevision,
				)] ?: return null
				val toAuthorization = authorizations[NativeAmbientAuthorizationKey(
					transition.registrationGeneration,
					transition.toAuthorizationRevision,
				)] ?: return null
				if (!transition.hasContinuousNativePhase(firstSegment, previousBoundary, phaseStart, expected, from) ||
					!transition.hasNativeHistoricalAuthority(cursor, from, to, fromAuthorization, toAuthorization,
						previousElapsed, policies, consents)
				) {
					return null
				}
				phases += NativeAmbientAuthorityPhase(
					firstSegment,
					transition.fromContinuitySegmentGeneration,
					phaseStart,
					transition.effectiveBoundaryTimeMs,
					from,
				)
				firstSegment = transition.toContinuitySegmentGeneration
				phaseStart = transition.effectiveBoundaryTimeMs
				previousBoundary = transition.effectiveBoundaryTimeMs
				previousElapsed = toAuthorization.effectiveElapsedRealtimeNanos
				expected = to
			}
			val latest = transitions.last()
			if (!cursor.hasMatchingNativeFinalTransition(latest, expected, current, firstSegment, phaseStart)) {
				return null
			}
			phases += NativeAmbientAuthorityPhase(
				firstSegment,
				cursor.continuitySegmentGeneration,
				phaseStart,
				cursor.importedThroughTimeMs,
				current,
			)
			return NativeAmbientAuthorityTimeline(phases)
		}

		private fun AmbientStepsImportCursorEntity.hasMatchingNativeCurrentAuthorization(
			current: NativeAmbientStoredAuthority,
			authorization: NativeAmbientHistoricalAuthorization,
		): Boolean {
			if (!current.matches(authorization) || authorization.effectiveBootId != authorizationEffectiveBootId ||
				authorization.effectiveElapsedRealtimeNanos != authorizationEffectiveElapsedRealtimeNanos ||
				authorization.effectiveWallTimeMs != authorizationEffectiveWallTimeMs
			) {
				return false
			}
			return authorization.effectiveBootId == registrationClockDomainId &&
				authorization.effectiveElapsedRealtimeNanos >= registrationAcceptedElapsedRealtimeNanos
		}

		private fun AmbientStepsImportAuthorityTransitionEntity.hasContinuousNativePhase(
			firstSegment: Long,
			previousBoundary: Long,
			phaseStart: Long,
			expected: NativeAmbientStoredAuthority?,
			from: NativeAmbientStoredAuthority,
		): Boolean {
			if (fromContinuitySegmentGeneration < firstSegment ||
				toContinuitySegmentGeneration != Math.addExact(fromContinuitySegmentGeneration, 1L)
			) {
				return false
			}
			return effectiveBoundaryTimeMs >= previousBoundary && effectiveBoundaryTimeMs >= phaseStart &&
				(expected == null || expected == from)
		}

		// Original captured phase inputs remain explicit, without a new holder or mutable authority reread.
		@Suppress("LongParameterList")
		private fun AmbientStepsImportAuthorityTransitionEntity.hasNativeHistoricalAuthority(
			cursor: AmbientStepsImportCursorEntity,
			from: NativeAmbientStoredAuthority,
			to: NativeAmbientStoredAuthority,
			fromAuthorization: NativeAmbientHistoricalAuthorization,
			toAuthorization: NativeAmbientHistoricalAuthorization,
			previousElapsed: Long,
			policies: Map<Long, SourcePolicyEntity>,
			consents: Map<Long, SourceConsentEpochEntity>,
		): Boolean {
			if (!from.matches(fromAuthorization) || !to.matches(toAuthorization) ||
				!hasContinuousNativeAuthorizationClocks(cursor, fromAuthorization, toAuthorization, previousElapsed) ||
				!hasMatchingNativeNextAuthorization(toAuthorization)
			) {
				return false
			}
			return effectiveBoundaryTimeMs == AmbientStepsImportCursorEntity.privacyFloorTimeMs(
				registrationAcceptedAtMs, toAuthorization.effectiveWallTimeMs,
			) && from.hasDurablePolicyAt(effectiveBoundaryTimeMs, policies, consents) &&
				to.hasDurablePolicyAt(effectiveBoundaryTimeMs, policies, consents)
		}

		private fun hasContinuousNativeAuthorizationClocks(
			cursor: AmbientStepsImportCursorEntity,
			from: NativeAmbientHistoricalAuthorization,
			to: NativeAmbientHistoricalAuthorization,
			previousElapsed: Long,
		): Boolean = from.effectiveBootId == cursor.registrationClockDomainId &&
			to.effectiveBootId == cursor.registrationClockDomainId && from.effectiveElapsedRealtimeNanos == previousElapsed &&
			to.effectiveElapsedRealtimeNanos >= from.effectiveElapsedRealtimeNanos &&
			to.effectiveWallTimeMs >= from.effectiveWallTimeMs

		private fun AmbientStepsImportAuthorityTransitionEntity.hasMatchingNativeNextAuthorization(
			authorization: NativeAmbientHistoricalAuthorization,
		): Boolean = authorization.effectiveBootId == toAuthorizationEffectiveBootId &&
			authorization.effectiveElapsedRealtimeNanos == toAuthorizationEffectiveElapsedRealtimeNanos &&
			authorization.effectiveWallTimeMs == toAuthorizationEffectiveWallTimeMs

		private fun AmbientStepsImportCursorEntity.hasMatchingNativeFinalTransition(
			latest: AmbientStepsImportAuthorityTransitionEntity,
			expected: NativeAmbientStoredAuthority?,
			current: NativeAmbientStoredAuthority,
			firstSegment: Long,
			phaseStart: Long,
		): Boolean {
			if (expected != current || latest.toAuthorizationEffectiveBootId != authorizationEffectiveBootId ||
				latest.toAuthorizationEffectiveElapsedRealtimeNanos != authorizationEffectiveElapsedRealtimeNanos ||
				latest.toAuthorizationEffectiveWallTimeMs != authorizationEffectiveWallTimeMs
			) {
				return false
			}
			return latest.effectiveBoundaryTimeMs == eligibleFromTimeMs &&
				firstSegment <= continuitySegmentGeneration && phaseStart <= importedThroughTimeMs
		}

		private fun AmbientStepsImportAuthorityTransitionEntity.hasNativeOrigin(
			cursor: AmbientStepsImportCursorEntity,
		): Boolean = transitionId == AmbientStepsImportAuthorityTransitionIntegrity.transitionId(
			registrationGeneration,
			transitionSequence,
			provider,
			sourceInstanceId,
			collectedDataEpoch,
			fromContinuitySegmentGeneration,
			toContinuitySegmentGeneration,
			fromAuthorizationRevision,
			fromAuthorizationFingerprint,
			fromSourcePolicyRevision,
			fromAmbientConsentEpoch,
			toAuthorizationRevision,
			toAuthorizationFingerprint,
			toAuthorizationEffectiveBootId,
			toAuthorizationEffectiveElapsedRealtimeNanos,
			toAuthorizationEffectiveWallTimeMs,
			toSourcePolicyRevision,
			toAmbientConsentEpoch,
			registrationAcceptedAtMs,
			effectiveBoundaryTimeMs,
		) && registrationGeneration == cursor.registrationGeneration && provider == cursor.provider &&
			sourceInstanceId == cursor.sourceInstanceId && collectedDataEpoch == cursor.collectedDataEpoch &&
			registrationAcceptedAtMs == cursor.registrationAcceptedAtMs &&
			toAuthorizationEffectiveBootId == cursor.registrationClockDomainId

		private fun AmbientStepsImportAuthorityTransitionEntity.nativeFromAuthority() =
			NativeAmbientStoredAuthority(
				fromAuthorizationRevision,
				fromAuthorizationFingerprint,
				fromSourcePolicyRevision,
				fromAmbientConsentEpoch,
			)

		private fun AmbientStepsImportAuthorityTransitionEntity.nativeToAuthority() =
			NativeAmbientStoredAuthority(
				toAuthorizationRevision,
				toAuthorizationFingerprint,
				toSourcePolicyRevision,
				toAmbientConsentEpoch,
			)
	}
}

private data class NativeAmbientGapQualification(
	val valid: Boolean,
	val effect: EffectiveAmbientStepsGap?,
)

private fun AmbientStepsEffectiveGapInterval.qualifyNativeGap(
	evidence: SourceEvidenceState,
	cursors: Map<Long, AmbientStepsImportCursorEntity>,
): NativeAmbientGapQualification {
	val declared = runCatching {
		AmbientStepsImportGapEntity(
			gapId,
			registrationGeneration,
			gapSequence,
			provider,
			sourceInstanceId,
			reason,
			declaredGapStartTimeMs,
			declaredGapEndTimeMs,
			predecessorRegistrationGeneration,
			predecessorProvider,
			previousClockDomainId,
			nextClockDomainId,
			previousZoneId,
			nextZoneId,
			collectedDataEpoch,
			recordedAtMs,
		)
	}.getOrNull()
	val cursor = cursors[registrationGeneration]
	val predecessor = predecessorRegistrationGeneration?.let(cursors::get)
	val originMatches = hasNativeGapOrigin(cursor, predecessor)
	val valid = declared != null && gapId == AmbientStepsImportGapIntegrity.gapId(
		registrationGeneration,
		gapSequence,
		provider,
		sourceInstanceId,
		reason,
		declaredGapStartTimeMs,
		declaredGapEndTimeMs,
		predecessorRegistrationGeneration,
		predecessorProvider,
		previousClockDomainId,
		nextClockDomainId,
		previousZoneId,
		nextZoneId,
		collectedDataEpoch,
	) && evidence.acceptsRetainedEpoch(collectedDataEpoch) && hasMatchingNativeGapCursor(cursor) &&
		hasContainedNativeGap() && originMatches
	if (!valid) {
		return NativeAmbientGapQualification(false, null)
	}
	val retainedStart = maxOf(gapStartTimeMs, evidence.retainedFromMs ?: gapStartTimeMs)
	return NativeAmbientGapQualification(
		true,
		if (retainedStart < gapEndTimeMs) {
			EffectiveAmbientStepsGap(retainedStart, gapEndTimeMs)
		} else {
			null
		},
	)
}

private fun AmbientStepsEffectiveGapInterval.hasNativeGapOrigin(
	cursor: AmbientStepsImportCursorEntity?,
	predecessor: AmbientStepsImportCursorEntity?,
): Boolean {
	if (predecessorRegistrationGeneration == null) {
		return predecessorProvider == null && cursor?.registrationClockDomainId == previousClockDomainId
	}
	if (predecessor == null) {
		return false
	}
	return predecessor.provider == predecessorProvider && predecessor.collectedDataEpoch == collectedDataEpoch &&
		predecessor.registrationClockDomainId == previousClockDomainId &&
		predecessor.importedThroughTimeMs == declaredGapStartTimeMs
}

private fun AmbientStepsEffectiveGapInterval.hasMatchingNativeGapCursor(
	cursor: AmbientStepsImportCursorEntity?,
): Boolean {
	if (cursor == null) {
		return false
	}
	if (cursor.provider != provider || cursor.sourceInstanceId != sourceInstanceId ||
		cursor.collectedDataEpoch != collectedDataEpoch || cursor.lastGapSequence < gapSequence
	) {
		return false
	}
	return cursor.registrationAcceptedAtMs <= declaredGapStartTimeMs &&
		cursor.importedThroughTimeMs >= declaredGapEndTimeMs &&
		cursor.registrationClockDomainId == nextClockDomainId && recordedAtMs <= cursor.updatedAtMs
}

private fun AmbientStepsEffectiveGapInterval.hasContainedNativeGap(): Boolean =
	gapStartTimeMs >= declaredGapStartTimeMs && gapEndTimeMs <= declaredGapEndTimeMs && gapEndTimeMs > gapStartTimeMs

private const val MAX_SELECTED_AMBIENT_STEPS_ITEMS = 100
