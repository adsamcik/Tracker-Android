package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientStepsEffectiveGapInterval
import com.adsamcik.tracker.shared.base.database.dao.AmbientStepsStructuralDayRow
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityResult
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage as ApiStepsHistoryCoverage
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Provider capability is captured before entering Room; product reads never start a provider. */
internal enum class AmbientStepsRuntimeAvailability {
	AVAILABLE,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_RESTRICTED,
}

/** Current collection availability is orthogonal to any retained historical day product. */
internal enum class AmbientStepsProductAvailability {
	AVAILABLE,
	DISABLED,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_RESTRICTED,
	HISTORICAL_EVIDENCE_MISSING,
	STORAGE_FAILED,
}

internal enum class AmbientStepsProductMaterialization {
	NOT_APPLICABLE,
	MATERIALIZING,
	READY,
	FAILED,
}

internal data class AmbientStepsDayPageCursor(
	val latestWindowEndTimeMs: Long,
	val epochDay: Long,
	val storedZoneId: String,
) {
	init {
		require(latestWindowEndTimeMs >= 0L)
		require(storedZoneId.isNotBlank())
		ZoneId.of(storedZoneId)
	}
}

internal data class AmbientStepsDayPageRequest(
	val limit: Int,
	val before: AmbientStepsDayPageCursor? = null,
) {
	init {
		require(limit in 1..MAX_AMBIENT_DAY_PAGE_SIZE)
	}
}

internal data class AmbientStepsDayPage(
	val days: List<AmbientStepsDayProduct>,
	val next: AmbientStepsDayPageCursor?,
	val availability: AmbientStepsProductAvailability,
	val materialization: AmbientStepsProductMaterialization,
)

internal enum class AmbientStepsDayDependency {
	FACTS,
	GAPS,
	SESSION_CANDIDATES,
	SESSION_HISTORY,
	IMPORTED_SESSION_ENTRIES,
	CURSORS,
	AUTHORITY_TRANSITIONS,
	AUTHORIZATIONS,
}

internal sealed interface AmbientStepsDayPageResult {
	data class Snapshot(val page: AmbientStepsDayPage) : AmbientStepsDayPageResult

	/** A truncated dependency can never be presented as an exact or partial numeric day. */
	data class DependencyOverflow(
		val dependency: AmbientStepsDayDependency,
		val requestedAfter: AmbientStepsDayPageCursor?,
	) : AmbientStepsDayPageResult

	data class Unavailable(
		val availability: AmbientStepsProductAvailability,
		val materialization: AmbientStepsProductMaterialization,
	) : AmbientStepsDayPageResult
}

/**
 * Source-specific, bounded Ambient Steps read facade.
 *
 * Facts, effective gaps, exact session Steps evidence, cursor/writer authority, policy/consent,
 * deletion epoch and retention floor are observed under one Room transaction. Capability is an
 * immutable pre-read input because Android provider state is intentionally outside this module and
 * is never queried while Room holds a transaction.
 */
internal class AmbientStepsDayRepository @Inject constructor(
	private val database: AppDatabase,
	private val stepsSelector: StepsSegmentHistorySelector,
	private val countDomainQuery: StepsCountDomainCompatibilityQuery,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
	private val importedStepsReader = ImportedStepsRetainedReader(database)

	suspend fun readPage(
		request: AmbientStepsDayPageRequest,
		runtimeAvailability: AmbientStepsRuntimeAvailability,
	): AmbientStepsDayPageResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { readPageInTransaction(request, runtimeAvailability) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			AmbientStepsDayPageResult.Unavailable(
				AmbientStepsProductAvailability.STORAGE_FAILED,
				AmbientStepsProductMaterialization.FAILED,
			)
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun readPageInTransaction(
		request: AmbientStepsDayPageRequest,
		runtimeAvailability: AmbientStepsRuntimeAvailability,
	): AmbientStepsDayPageResult {
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return historicalEvidenceMissing()
		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
		)
		if (owner?.owner != SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS) {
			return historicalEvidenceMissing()
		}
		val policyAuthority = database.sourcePolicyDao().authority()
		val currentPolicy = policyAuthority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		}?.let { authority ->
			database.sourcePolicyDao().policyAtRevision(
				authority.currentPolicyRevision,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			)
		}
		val currentConsent = database.sourcePolicyDao().latestConsentEpoch(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		val activeCursors = database.ambientStepsImportStateDao().activeCursors(
			MAX_ACTIVE_AMBIENT_CURSORS + 1,
		)
		if (activeCursors.size > MAX_ACTIVE_AMBIENT_CURSORS) {
			return overflow(AmbientStepsDayDependency.CURSORS, request)
		}

		val dayRows = database.ambientStepsFactRevisionDao().discoverStructuralDayPage(
			writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
			beforeLatestWindowEndTimeMs = request.before?.latestWindowEndTimeMs,
			beforeEpochDay = request.before?.epochDay,
			beforeStoredZoneId = request.before?.storedZoneId,
			limit = request.limit + 1,
		)
		val acceptedRows = dayRows.take(request.limit)
		val dayIdentities = acceptedRows.mapNotNull(AmbientStepsStructuralDayRow::toDayOrNull)
		if (dayIdentities.size != acceptedRows.size) return historicalEvidenceMissing()
		val next = acceptedRows.lastOrNull()
			?.takeIf { dayRows.size > request.limit }
			?.toCursor()
		val currentState = currentProductState(
			policyAuthority = policyAuthority,
			policy = currentPolicy,
			consent = currentConsent,
			runtimeAvailability = runtimeAvailability,
			activeCursors = activeCursors,
			evidenceState = evidenceState,
			days = dayIdentities,
		)
		if (dayIdentities.isEmpty()) {
			return AmbientStepsDayPageResult.Snapshot(
				AmbientStepsDayPage(emptyList(), next, currentState.first, currentState.second),
			)
		}

		val fromTimeMs = dayIdentities.minOf(AmbientStepsDayIdentity::startTimeMs)
		val toTimeMs = dayIdentities.maxOf(AmbientStepsDayIdentity::endTimeMs)
		val facts = database.ambientStepsFactRevisionDao().latestEffectiveOverlapping(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			fromTimeMs,
			toTimeMs,
			MAX_AMBIENT_FACTS_PER_PAGE + 1,
		)
		if (facts.size > MAX_AMBIENT_FACTS_PER_PAGE) {
			return overflow(AmbientStepsDayDependency.FACTS, request)
		}
		val gaps = database.ambientStepsImportStateDao().effectiveGapIntervalsOverlapping(
			fromTimeMs,
			toTimeMs,
			MAX_AMBIENT_GAPS_PER_PAGE + 1,
		)
		if (gaps.size > MAX_AMBIENT_GAPS_PER_PAGE) {
			return overflow(AmbientStepsDayDependency.GAPS, request)
		}
		val selectedDayKeys = dayIdentities.associateBy(AmbientStepsDayIdentity::key)
		val selectedFacts = facts.filter { fact ->
			fact.dayKeyOrNull()?.let(selectedDayKeys::containsKey) == true
		}
		val registrationGenerations = buildSet {
			selectedFacts.mapNotNullTo(this) { it.registrationGeneration }
			gaps.mapTo(this) { it.registrationGeneration }
			gaps.mapNotNullTo(this) { it.predecessorRegistrationGeneration }
		}.sorted()
		if (registrationGenerations.size > MAX_HISTORICAL_AMBIENT_CURSORS_PER_PAGE) {
			return overflow(AmbientStepsDayDependency.CURSORS, request)
		}
		val cursors = if (registrationGenerations.isEmpty()) emptyList() else {
			database.ambientStepsImportStateDao().cursors(registrationGenerations)
		}
		if (cursors.size != registrationGenerations.size) return historicalEvidenceMissing()
		val factRegistrationGenerations = selectedFacts.mapNotNull {
			it.registrationGeneration
		}.distinct().sorted()
		val transitions = if (factRegistrationGenerations.isEmpty()) emptyList() else {
			database.ambientStepsImportStateDao().authorityTransitionsBounded(
				factRegistrationGenerations,
				MAX_AMBIENT_AUTHORITY_TRANSITIONS_PER_PAGE + 1,
			)
		}
		if (transitions.size > MAX_AMBIENT_AUTHORITY_TRANSITIONS_PER_PAGE) {
			return overflow(AmbientStepsDayDependency.AUTHORITY_TRANSITIONS, request)
		}
		val authorizationKeys = buildSet {
			cursors.filter { cursor ->
				cursor.registrationGeneration in factRegistrationGenerations
			}.forEach { cursor ->
				add(
					AmbientStepsAuthorizationKey(
						cursor.registrationGeneration,
						cursor.authorizationRevision,
					),
				)
			}
			transitions.forEach { transition ->
				add(
					AmbientStepsAuthorizationKey(
						transition.registrationGeneration,
						transition.fromAuthorizationRevision,
					),
				)
				add(
					AmbientStepsAuthorizationKey(
						transition.registrationGeneration,
						transition.toAuthorizationRevision,
					),
				)
			}
		}
		if (authorizationKeys.size > MAX_AMBIENT_AUTHORIZATION_KEYS_PER_PAGE) {
			return overflow(AmbientStepsDayDependency.AUTHORIZATIONS, request)
		}
		val authorizationRows = if (authorizationKeys.isEmpty()) emptyList() else {
			database.sourceBrokerDao().authorizationRevisionsBounded(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				authorizationKeys.map(AmbientStepsAuthorizationKey::registrationGeneration).distinct(),
				authorizationKeys.map(AmbientStepsAuthorizationKey::authorizationRevision).distinct(),
				MAX_AMBIENT_AUTHORIZATION_MEMBERS_PER_PAGE + 1,
			)
		}
		if (authorizationRows.size > MAX_AMBIENT_AUTHORIZATION_MEMBERS_PER_PAGE) {
			return overflow(AmbientStepsDayDependency.AUTHORIZATIONS, request)
		}
		val authorizations = authorizationRows.groupBy {
			AmbientStepsAuthorizationKey(it.registrationGeneration, it.authorizationRevision)
		}.mapValues { (key, rows) -> rows.toAmbientAuthorizationOrNull(key) }
		if (authorizationKeys.any { key -> authorizations[key] == null }) {
			return historicalEvidenceMissing()
		}
		val segments = database.trackingHistoryReadDao().portableStepsSegmentCandidatePage(
			fromMs = fromTimeMs,
			toMs = toTimeMs,
			stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			limit = HISTORY_SEGMENT_BATCH_CAP + 1,
			afterStartTimeMs = null,
			afterSegmentId = null,
		)
		if (segments.size > HISTORY_SEGMENT_BATCH_CAP) {
			return overflow(AmbientStepsDayDependency.SESSION_CANDIDATES, request)
		}
		val importedEntries = database.importedStepsDao().entriesOverlapping(
			fromInclusiveMs = fromTimeMs,
			toExclusiveMs = toTimeMs,
			limit = ImportedStepsRetainedReader.MAX_ENTRY_BATCH + 1,
		)
		if (importedEntries.size > ImportedStepsRetainedReader.MAX_ENTRY_BATCH) {
			return overflow(AmbientStepsDayDependency.IMPORTED_SESSION_ENTRIES, request)
		}

		val policyRevisions = buildSet {
			selectedFacts.mapNotNullTo(this) { it.sourcePolicyRevision }
			transitions.forEach { transition ->
				add(transition.fromSourcePolicyRevision)
				add(transition.toSourcePolicyRevision)
			}
		}.sorted()
		val policies = if (policyRevisions.isEmpty()) emptyList() else {
			database.sourcePolicyDao().policiesAtRevisions(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				policyRevisions,
			)
		}
		if (policies.size != policyRevisions.size) return historicalEvidenceMissing()
		val consentEpochs = buildSet {
			selectedFacts.mapNotNullTo(this) { it.ambientConsentEpoch }
			transitions.forEach { transition ->
				add(transition.fromAmbientConsentEpoch)
				add(transition.toAmbientConsentEpoch)
			}
		}.sorted()
		val consents = if (consentEpochs.isEmpty()) emptyList() else {
			database.sourcePolicyDao().consentEpochs(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
				consentEpochs,
			)
		}
		if (consents.size != consentEpochs.size) return historicalEvidenceMissing()

		val stepsSnapshot = if (segments.isEmpty()) null else {
			loadStepsHistoryBatchSnapshot(database, segments)
		}
		if (stepsSnapshot?.dependencyOverflow != null) {
			return overflow(AmbientStepsDayDependency.SESSION_HISTORY, request)
		}
		val sessionEvidence = stepsSnapshot?.let {
			stepsSelector.selectManyWithSnapshot(segments, it)
		}.orEmpty()
		if (sessionEvidence.any { evidence ->
				evidence.segment.logicalTrackingId.isNullOrBlank() ||
					evidence.segment.serviceRunId.isNullOrBlank()
			}
		) return historicalEvidenceMissing()
		val localSessions = sessionEvidence.map { evidence ->
			val result = evidence.steps
			val logicalTrackingId = requireNotNull(evidence.segment.logicalTrackingId)
			val serviceRunId = requireNotNull(evidence.segment.serviceRunId)
			QualifiedSessionStepsWindow(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				startTimeMs = evidence.segment.startTimeMs,
				endTimeMs = evidence.segment.endTimeMs,
				stepCount = result.count.takeIf {
					result.availability == StepsHistoryAvailability.AVAILABLE &&
						result.materialization == StepsHistoryMaterialization.READY &&
						result.coverage == StepsHistoryCoverage.COMPLETE
				},
				storedZoneId = stepsSnapshot?.manifestsByRun
					?.get(serviceRunId)
					.orEmpty()
					.map { it.zoneId }
					.distinct()
					.singleOrNull(),
				countDomainOwners = buildList {
					stepsSnapshot?.factStatesByRun?.get(serviceRunId).orEmpty()
						.mapNotNullTo(this) { it.state?.countDomainOwnerReferenceOrNull() }
					stepsSnapshot?.completenessByRun?.get(serviceRunId).orEmpty()
						.filter {
							it.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS
						}
						.mapNotNullTo(this) { it.countDomainOwnerReferenceOrNull() }
				}.distinct(),
			)
		}
		val importedSessions = when (val imported = importedStepsReader.readEntriesInTransaction(
			importedEntries.map { it.identity },
		)) {
			is ImportedStepsRetainedRead.Ready -> {
				val verified = mutableListOf<QualifiedSessionStepsWindow>()
				imported.entries.forEach { entry ->
					entry.runs.forEach { run ->
						val wire = entry.portableRunsById.getValue(run.identity)
						val history = wire.toImportedStepsHistory(
							run.identity in entry.retentionTruncatedRunIds,
						)
						val owners = database.importedSessionCountDomainOwners(
							entry,
							run.identity,
						) ?: return historicalEvidenceMissing()
						verified += QualifiedSessionStepsWindow(
							logicalTrackingId = entry.metadata.identity,
							serviceRunId = run.identity,
							startTimeMs = run.startTimeMs,
							endTimeMs = run.endTimeMs,
							stepCount = history.count.takeIf {
								history.productState == HistoryProductState.READY &&
									history.coverage == ApiStepsHistoryCoverage.COMPLETE
							},
							storedZoneId = run.storedZoneId,
							origin = QualifiedSessionStepsOrigin.PORTABLE_IMPORT,
							countDomainOwners = owners,
						)
					}
				}
				val unverified = importedEntries.filter { entry ->
					entry.identity in imported.unverifiableEntries
				}.map { entry -> entry.unverifiableImportedSession() }
				verified + unverified
			}
			is ImportedStepsRetainedRead.Unverifiable -> {
				if (imported.reason == ImportedStepsReadFailure.DEPENDENCY_OVERFLOW) {
					return overflow(AmbientStepsDayDependency.IMPORTED_SESSION_ENTRIES, request)
				}
				importedEntries.map { entry -> entry.unverifiableImportedSession() }
			}
		}
		val sessions = localSessions + importedSessions

		val cursorsByGeneration = cursors.associateBy(AmbientStepsImportCursorEntity::registrationGeneration)
		val policiesByRevision = policies.associateBy(SourcePolicyEntity::policyRevision)
		val consentsByEpoch = consents.associateBy(SourceConsentEpochEntity::epoch)
		val transitionsByRegistration = transitions.groupBy(
			AmbientStepsImportAuthorityTransitionEntity::registrationGeneration,
		)
		val authorityTimelines = cursorsByGeneration.mapValues { (generation, cursor) ->
			AmbientStepsAuthorityTimeline.create(
				cursor,
				transitionsByRegistration[generation].orEmpty(),
				authorizations,
				policiesByRevision,
				consentsByEpoch,
			)
		}
		val qualifiedGaps = gaps.map { gap ->
			gap.qualifyForRead(evidenceState, cursorsByGeneration)
		}
		val factsByDay = selectedFacts.groupBy { fact -> fact.dayKeyOrNull() }
		val preparedDays = dayIdentities.map { day ->
			val dayFacts = factsByDay[day.key].orEmpty()
			val daySessions = sessions.filter { session ->
				session.endTimeMs > day.startTimeMs && session.startTimeMs < day.endTimeMs
			}
			if (dayFacts.any { fact ->
					!fact.hasValidReadAuthority(
						day = day,
						ownerGeneration = owner.ownerGeneration,
						evidenceState = evidenceState,
						cursor = fact.registrationGeneration?.let(cursorsByGeneration::get),
						authorityTimeline = fact.registrationGeneration?.let(authorityTimelines::get),
						policy = fact.sourcePolicyRevision?.let(policiesByRevision::get),
						consent = fact.ambientConsentEpoch?.let(consentsByEpoch::get),
					)
				}
			) {
				return@map PreparedAmbientStepsDay(
					day,
					emptyList(),
					emptyList(),
					daySessions,
					AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE,
				)
			}
			val qualifiedFacts = dayFacts.map { it.toQualifiedFact(day) }
			val dayGaps = qualifiedGaps.filter { gap ->
				gap.row.gapEndTimeMs > day.startTimeMs && gap.row.gapStartTimeMs < day.endTimeMs
			}
			if (dayGaps.any { !it.valid }) {
				return@map PreparedAmbientStepsDay(
					day,
					qualifiedFacts,
					emptyList(),
					daySessions,
					AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE,
				)
			}
			val effectiveGaps = dayGaps.asSequence()
				.mapNotNull(AmbientStepsGapReadQualification::effect)
				.toList()
			PreparedAmbientStepsDay(
				day,
				qualifiedFacts,
				effectiveGaps,
				daySessions,
				null,
			)
		}
		val requestOwners = mutableListOf<Pair<Int, Int>>()
		val compatibilityRequests = buildList {
			preparedDays.forEachIndexed { dayIndex, prepared ->
				if (prepared.unavailableCause == null) {
					prepared.sessions.forEachIndexed { sessionIndex, session ->
						requestOwners += dayIndex to sessionIndex
						add(session.countDomainCompatibilityRequest(prepared.facts))
					}
				}
			}
		}
		val compatibilityResults =
			countDomainQuery.compareInProductionChunks(compatibilityRequests)
		val resultsByOwner = requestOwners.zip(compatibilityResults).toMap()
		val productDays = preparedDays.mapIndexed { dayIndex, prepared ->
			prepared.unavailableCause?.let { cause ->
				return@mapIndexed unavailableDay(prepared.day, prepared.sessions, cause)
			}
			val compatibleSessions = prepared.sessions.mapIndexed { sessionIndex, session ->
				session.copy(
					compatibility = resultsByOwner[dayIndex to sessionIndex]
						?: StepsCountDomainCompatibilityResult.Unverifiable,
				)
			}
			composeAmbientStepsDay(
				prepared.day,
				prepared.facts,
				prepared.gaps,
				compatibleSessions,
			)
		}
		return AmbientStepsDayPageResult.Snapshot(
			AmbientStepsDayPage(productDays, next, currentState.first, currentState.second),
		)
	}

	private data class PreparedAmbientStepsDay(
		val day: AmbientStepsDayIdentity,
		val facts: List<QualifiedAmbientStepsFact>,
		val gaps: List<EffectiveAmbientStepsGap>,
		val sessions: List<QualifiedSessionStepsWindow>,
		val unavailableCause: AmbientStepsDayCause?,
	)

	private fun currentProductState(
		policyAuthority: SourcePolicyAuthorityEntity?,
		policy: SourcePolicyEntity?,
		consent: SourceConsentEpochEntity?,
		runtimeAvailability: AmbientStepsRuntimeAvailability,
		activeCursors: List<AmbientStepsImportCursorEntity>,
		evidenceState: SourceEvidenceState,
		days: List<AmbientStepsDayIdentity>,
	): Pair<AmbientStepsProductAvailability, AmbientStepsProductMaterialization> {
		if (policyAuthority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE || policy == null) {
			return AmbientStepsProductAvailability.HISTORICAL_EVIDENCE_MISSING to
				AmbientStepsProductMaterialization.FAILED
		}
		val eligible = policy.enabled && policy.ambientPersistenceEligible &&
			policy.ambientConsentEpoch != null && consent != null && consent.eligible &&
			consent.persistenceEligible && consent.epoch == policy.ambientConsentEpoch &&
			consent.policyRevision == policy.policyRevision
		if (!eligible) {
			return AmbientStepsProductAvailability.DISABLED to
				AmbientStepsProductMaterialization.NOT_APPLICABLE
		}
		val availability = when (runtimeAvailability) {
			AmbientStepsRuntimeAvailability.AVAILABLE -> AmbientStepsProductAvailability.AVAILABLE
			AmbientStepsRuntimeAvailability.UNSUPPORTED -> AmbientStepsProductAvailability.UNSUPPORTED
			AmbientStepsRuntimeAvailability.PERMISSION_REQUIRED ->
				AmbientStepsProductAvailability.PERMISSION_REQUIRED
			AmbientStepsRuntimeAvailability.OS_RESTRICTED -> AmbientStepsProductAvailability.OS_RESTRICTED
		}
		if (availability != AmbientStepsProductAvailability.AVAILABLE) {
			return availability to AmbientStepsProductMaterialization.NOT_APPLICABLE
		}
		val cursor = activeCursors.singleOrNull()
		if (cursor == null || cursor.collectedDataEpoch != evidenceState.collectedDataEpoch ||
			cursor.sourcePolicyRevision != policy.policyRevision ||
			cursor.ambientConsentEpoch != consent.epoch
		) {
			return availability to AmbientStepsProductMaterialization.MATERIALIZING
		}
		val materializing = days.isEmpty() || days.any { day ->
			cursor.importedThroughTimeMs < day.endTimeMs
		}
		return availability to if (materializing) {
			AmbientStepsProductMaterialization.MATERIALIZING
		} else {
			AmbientStepsProductMaterialization.READY
		}
	}

	private fun overflow(
		dependency: AmbientStepsDayDependency,
		request: AmbientStepsDayPageRequest,
	) = AmbientStepsDayPageResult.DependencyOverflow(dependency, request.before)

	private fun historicalEvidenceMissing() = AmbientStepsDayPageResult.Unavailable(
		AmbientStepsProductAvailability.HISTORICAL_EVIDENCE_MISSING,
		AmbientStepsProductMaterialization.FAILED,
	)
}

private fun AmbientStepsStructuralDayRow.toDayOrNull(): AmbientStepsDayIdentity? = runCatching {
	AmbientStepsDayIdentity(
		structuralEpochDay,
		storedZoneId,
		structuralDayStartTimeMs,
		structuralDayEndTimeMs,
	)
}.getOrNull()

private fun AmbientStepsStructuralDayRow.toCursor() = AmbientStepsDayPageCursor(
	latestWindowEndTimeMs,
	structuralEpochDay,
	storedZoneId,
)

private data class AmbientStepsDayKey(val epochDay: Long, val storedZoneId: String)

private val AmbientStepsDayIdentity.key: AmbientStepsDayKey
	get() = AmbientStepsDayKey(epochDay, storedZoneId)

private fun AmbientStepsFactRevisionEntity.dayKeyOrNull(): AmbientStepsDayKey? {
	val epochDay = structuralEpochDay ?: return null
	val zoneId = storedZoneId ?: return null
	return AmbientStepsDayKey(epochDay, zoneId)
}

private fun AmbientStepsFactRevisionEntity.hasValidReadAuthority(
	day: AmbientStepsDayIdentity,
	ownerGeneration: Long,
	evidenceState: SourceEvidenceState,
	cursor: AmbientStepsImportCursorEntity?,
	authorityTimeline: AmbientStepsAuthorityTimeline?,
	policy: SourcePolicyEntity?,
	consent: SourceConsentEpochEntity?,
): Boolean {
	val start = windowStartTimeMs ?: return false
	val end = windowEndTimeMs ?: return false
	val retainedFromMs = evidenceState.retainedFromMs
	return AmbientStepsFactIntegrity.hasValidEffectChecksum(this) &&
		writerOwnerGeneration == ownerGeneration &&
		collectedDataEpoch == evidenceState.collectedDataEpoch &&
		(retainedFromMs == null || start >= retainedFromMs) &&
		dayKeyOrNull() == day.key && structuralDayStartTimeMs == day.startTimeMs &&
		structuralDayEndTimeMs == day.endTimeMs && start >= day.startTimeMs && end <= day.endTimeMs &&
		cursor != null && cursor.provider == provider && cursor.sourceInstanceId == sourceInstanceId &&
		cursor.registrationGeneration == registrationGeneration &&
		cursor.collectedDataEpoch == collectedDataEpoch &&
		cursor.registrationAcceptedAtMs <= start &&
		cursor.importedThroughTimeMs >= end &&
		authorityTimeline?.accepts(this) == true &&
		policy != null && policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		policy.policyRevision == sourcePolicyRevision && policy.enabled &&
		policy.ambientPersistenceEligible && policy.ambientConsentEpoch == ambientConsentEpoch &&
		policy.effectiveWallTimeMs <= start &&
		consent != null && consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && consent.epoch == ambientConsentEpoch &&
		consent.policyRevision == sourcePolicyRevision && consent.eligible && consent.persistenceEligible &&
		consent.effectiveWallTimeMs <= start
}

private data class AmbientStepsStoredAuthority(
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

	fun hasDurablePolicyAt(
		boundaryTimeMs: Long,
		policiesByRevision: Map<Long, SourcePolicyEntity>,
		consentsByEpoch: Map<Long, SourceConsentEpochEntity>,
	): Boolean {
		val policy = policiesByRevision[sourcePolicyRevision] ?: return false
		val consent = consentsByEpoch[ambientConsentEpoch] ?: return false
		return policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			policy.policyRevision == sourcePolicyRevision && policy.enabled &&
			policy.ambientPersistenceEligible && policy.ambientConsentEpoch == ambientConsentEpoch &&
			policy.effectiveWallTimeMs <= boundaryTimeMs &&
			consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
			consent.epoch == ambientConsentEpoch && consent.policyRevision == sourcePolicyRevision &&
			consent.eligible && consent.persistenceEligible && consent.effectiveWallTimeMs <= boundaryTimeMs
	}
}

private data class AmbientStepsAuthorizationKey(
	val registrationGeneration: Long,
	val authorizationRevision: Long,
)

private data class AmbientStepsHistoricalAuthorization(
	val key: AmbientStepsAuthorizationKey,
	val fingerprint: String,
	val effectiveBootId: String,
	val effectiveElapsedRealtimeNanos: Long,
	val effectiveWallTimeMs: Long,
	val sourcePolicyRevision: Long,
	val ambientConsentEpoch: Long,
)

private fun List<SourceAuthorizationEntity>.toAmbientAuthorizationOrNull(
	key: AmbientStepsAuthorizationKey,
): AmbientStepsHistoricalAuthorization? {
	val first = firstOrNull() ?: return null
	if (first.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
		first.registrationGeneration != key.registrationGeneration ||
		first.authorizationRevision != key.authorizationRevision ||
		first.isDenyAll || first.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT ||
		!first.persistenceEligible || first.sourcePolicyRevision == null || first.consentEpoch == null ||
		first.purposeEligibilityMask != SourceBrokerPurpose.MASK_AMBIENT_PRODUCT ||
		first.logicalTrackingId != null || first.serviceRunId != null || first.manifestRevision != null ||
		first.lifecycleLeaseGeneration != null
	) return null
	if (any { row ->
			row.sourceKind != first.sourceKind ||
				row.registrationGeneration != first.registrationGeneration ||
				row.authorizationRevision != first.authorizationRevision ||
				row.authorizationFingerprint != first.authorizationFingerprint ||
				row.purposeEligibilityMask != first.purposeEligibilityMask ||
				row.effectiveBootId != first.effectiveBootId ||
				row.effectiveElapsedRealtimeNanos != first.effectiveElapsedRealtimeNanos ||
				row.effectiveWallTimeMs != first.effectiveWallTimeMs || row.isDenyAll ||
				row.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT || !row.persistenceEligible ||
				row.sourcePolicyRevision != first.sourcePolicyRevision ||
				row.consentEpoch != first.consentEpoch || row.logicalTrackingId != null ||
				row.serviceRunId != null || row.manifestRevision != null ||
				row.lifecycleLeaseGeneration != null
		}
	) return null
	return AmbientStepsHistoricalAuthorization(
		key,
		first.authorizationFingerprint,
		first.effectiveBootId,
		first.effectiveElapsedRealtimeNanos,
		first.effectiveWallTimeMs,
		requireNotNull(first.sourcePolicyRevision),
		requireNotNull(first.consentEpoch),
	)
}

private data class AmbientStepsAuthorityPhase(
	val firstContinuitySegmentGeneration: Long,
	val lastContinuitySegmentGeneration: Long,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val authority: AmbientStepsStoredAuthority,
) {
	fun accepts(fact: AmbientStepsFactRevisionEntity): Boolean {
		val segmentGeneration = fact.continuitySegmentGeneration ?: return false
		val start = fact.windowStartTimeMs ?: return false
		val end = fact.windowEndTimeMs ?: return false
		return segmentGeneration in firstContinuitySegmentGeneration..lastContinuitySegmentGeneration &&
			start >= startTimeMs && end <= endTimeMs && end > start && authority.matches(fact)
	}
}

private class AmbientStepsAuthorityTimeline private constructor(
	private val phases: List<AmbientStepsAuthorityPhase>,
) {
	fun accepts(fact: AmbientStepsFactRevisionEntity): Boolean = phases.any { it.accepts(fact) }

	companion object {
		@Suppress("LongMethod", "CyclomaticComplexMethod")
		fun create(
			cursor: AmbientStepsImportCursorEntity,
			transitions: List<AmbientStepsImportAuthorityTransitionEntity>,
			authorizations: Map<AmbientStepsAuthorizationKey, AmbientStepsHistoricalAuthorization?>,
			policiesByRevision: Map<Long, SourcePolicyEntity>,
			consentsByEpoch: Map<Long, SourceConsentEpochEntity>,
		): AmbientStepsAuthorityTimeline? {
			if (transitions.size.toLong() != cursor.authorityTransitionSequence ||
				transitions.withIndex().any { (index, transition) ->
					transition.transitionSequence != index + 1L ||
					!transition.hasExactOrigin(cursor) ||
					transition.recordedAtMs > cursor.updatedAtMs
				}
			) return null
			val current = AmbientStepsStoredAuthority(
				cursor.authorizationRevision,
				cursor.authorizationFingerprint,
				cursor.sourcePolicyRevision,
				cursor.ambientConsentEpoch,
			)
			val currentAuthorization = authorizations[
				AmbientStepsAuthorizationKey(
					cursor.registrationGeneration,
					cursor.authorizationRevision,
				)
			] ?: return null
			if (!current.matches(currentAuthorization) ||
				currentAuthorization.effectiveBootId != cursor.authorizationEffectiveBootId ||
				currentAuthorization.effectiveElapsedRealtimeNanos !=
					cursor.authorizationEffectiveElapsedRealtimeNanos ||
				currentAuthorization.effectiveWallTimeMs != cursor.authorizationEffectiveWallTimeMs ||
				currentAuthorization.effectiveBootId != cursor.registrationClockDomainId ||
				currentAuthorization.effectiveElapsedRealtimeNanos <
					cursor.registrationAcceptedElapsedRealtimeNanos
			) return null
			if (transitions.isEmpty()) {
				if (!current.hasDurablePolicyAt(
						cursor.eligibleFromTimeMs,
						policiesByRevision,
						consentsByEpoch,
					)
				) return null
				return AmbientStepsAuthorityTimeline(
					listOf(
						AmbientStepsAuthorityPhase(
							1L,
							cursor.continuitySegmentGeneration,
							cursor.eligibleFromTimeMs,
							cursor.importedThroughTimeMs,
							current,
						),
					),
				)
			}

			val phases = mutableListOf<AmbientStepsAuthorityPhase>()
			var firstSegment = 1L
			val firstTransition = transitions.first()
			val initialAuthorization = authorizations[
				AmbientStepsAuthorizationKey(
					cursor.registrationGeneration,
					firstTransition.fromAuthorizationRevision,
				)
			] ?: return null
			if (initialAuthorization.effectiveBootId != cursor.registrationClockDomainId ||
				initialAuthorization.effectiveElapsedRealtimeNanos <
					cursor.registrationAcceptedElapsedRealtimeNanos
			) return null
			var phaseStart = AmbientStepsImportCursorEntity.privacyFloorTimeMs(
				cursor.registrationAcceptedAtMs,
				initialAuthorization.effectiveWallTimeMs,
			)
			var expectedAuthority: AmbientStepsStoredAuthority? = null
			var previousBoundary = cursor.registrationAcceptedAtMs
			var previousAuthorizationElapsedRealtimeNanos =
				initialAuthorization.effectiveElapsedRealtimeNanos
			transitions.forEach { transition ->
				val from = transition.fromAuthority()
				val to = transition.toAuthority()
				val fromAuthorization = authorizations[
					AmbientStepsAuthorizationKey(
						transition.registrationGeneration,
						transition.fromAuthorizationRevision,
					)
				] ?: return null
				val toAuthorization = authorizations[
					AmbientStepsAuthorizationKey(
						transition.registrationGeneration,
						transition.toAuthorizationRevision,
					)
				] ?: return null
				if (transition.fromContinuitySegmentGeneration < firstSegment ||
					transition.toContinuitySegmentGeneration !=
					Math.addExact(transition.fromContinuitySegmentGeneration, 1L) ||
					transition.effectiveBoundaryTimeMs < previousBoundary ||
					transition.effectiveBoundaryTimeMs < phaseStart ||
					(expectedAuthority != null && expectedAuthority != from) ||
					!from.matches(fromAuthorization) || !to.matches(toAuthorization) ||
					fromAuthorization.effectiveBootId != cursor.registrationClockDomainId ||
					toAuthorization.effectiveBootId != cursor.registrationClockDomainId ||
					fromAuthorization.effectiveElapsedRealtimeNanos !=
						previousAuthorizationElapsedRealtimeNanos ||
					toAuthorization.effectiveElapsedRealtimeNanos <
						fromAuthorization.effectiveElapsedRealtimeNanos ||
					toAuthorization.effectiveWallTimeMs < fromAuthorization.effectiveWallTimeMs ||
					toAuthorization.effectiveBootId != transition.toAuthorizationEffectiveBootId ||
					toAuthorization.effectiveElapsedRealtimeNanos !=
					transition.toAuthorizationEffectiveElapsedRealtimeNanos ||
					toAuthorization.effectiveWallTimeMs != transition.toAuthorizationEffectiveWallTimeMs ||
					transition.effectiveBoundaryTimeMs !=
					AmbientStepsImportCursorEntity.privacyFloorTimeMs(
						transition.registrationAcceptedAtMs,
						toAuthorization.effectiveWallTimeMs,
					) ||
					!from.hasDurablePolicyAt(
						transition.effectiveBoundaryTimeMs,
						policiesByRevision,
						consentsByEpoch,
					) ||
					!to.hasDurablePolicyAt(
						transition.effectiveBoundaryTimeMs,
						policiesByRevision,
						consentsByEpoch,
					)
				) return null
				phases += AmbientStepsAuthorityPhase(
					firstSegment,
					transition.fromContinuitySegmentGeneration,
					phaseStart,
					transition.effectiveBoundaryTimeMs,
					from,
				)
				firstSegment = transition.toContinuitySegmentGeneration
				phaseStart = transition.effectiveBoundaryTimeMs
				previousBoundary = transition.effectiveBoundaryTimeMs
				previousAuthorizationElapsedRealtimeNanos =
					toAuthorization.effectiveElapsedRealtimeNanos
				expectedAuthority = to
			}
			val latest = transitions.last()
			if (expectedAuthority != current ||
				latest.toAuthorizationEffectiveBootId != cursor.authorizationEffectiveBootId ||
				latest.toAuthorizationEffectiveElapsedRealtimeNanos !=
				cursor.authorizationEffectiveElapsedRealtimeNanos ||
				latest.toAuthorizationEffectiveWallTimeMs != cursor.authorizationEffectiveWallTimeMs ||
				latest.effectiveBoundaryTimeMs != cursor.eligibleFromTimeMs ||
				firstSegment > cursor.continuitySegmentGeneration ||
				phaseStart > cursor.importedThroughTimeMs
			) return null
			phases += AmbientStepsAuthorityPhase(
				firstSegment,
				cursor.continuitySegmentGeneration,
				phaseStart,
				cursor.importedThroughTimeMs,
				current,
			)
			return AmbientStepsAuthorityTimeline(phases)
		}
	}
}

private fun AmbientStepsStoredAuthority.matches(
	authorization: AmbientStepsHistoricalAuthorization,
): Boolean = authorizationRevision == authorization.key.authorizationRevision &&
	authorizationFingerprint == authorization.fingerprint &&
	sourcePolicyRevision == authorization.sourcePolicyRevision &&
	ambientConsentEpoch == authorization.ambientConsentEpoch

private fun AmbientStepsImportAuthorityTransitionEntity.hasExactOrigin(
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

private fun AmbientStepsImportAuthorityTransitionEntity.fromAuthority() =
	AmbientStepsStoredAuthority(
		fromAuthorizationRevision,
		fromAuthorizationFingerprint,
		fromSourcePolicyRevision,
		fromAmbientConsentEpoch,
	)

private fun AmbientStepsImportAuthorityTransitionEntity.toAuthority() =
	AmbientStepsStoredAuthority(
		toAuthorizationRevision,
		toAuthorizationFingerprint,
		toSourcePolicyRevision,
		toAmbientConsentEpoch,
	)

private data class AmbientStepsGapReadQualification(
	val row: AmbientStepsEffectiveGapInterval,
	val valid: Boolean,
	val effect: EffectiveAmbientStepsGap?,
)

private fun AmbientStepsEffectiveGapInterval.qualifyForRead(
	evidenceState: SourceEvidenceState,
	cursorsByGeneration: Map<Long, AmbientStepsImportCursorEntity>,
): AmbientStepsGapReadQualification {
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
	val cursor = cursorsByGeneration[registrationGeneration]
	val predecessor = predecessorRegistrationGeneration?.let(cursorsByGeneration::get)
	val originMatches = if (predecessorRegistrationGeneration == null) {
		predecessorProvider == null && cursor?.registrationClockDomainId == previousClockDomainId
	} else {
		predecessor != null && predecessor.provider == predecessorProvider &&
			predecessor.collectedDataEpoch == collectedDataEpoch &&
			predecessor.registrationClockDomainId == previousClockDomainId &&
			predecessor.importedThroughTimeMs == declaredGapStartTimeMs
	}
	val valid = declared != null &&
		gapId == AmbientStepsImportGapIntegrity.gapId(
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
		) && collectedDataEpoch == evidenceState.collectedDataEpoch && cursor != null &&
		cursor.provider == provider && cursor.sourceInstanceId == sourceInstanceId &&
		cursor.collectedDataEpoch == collectedDataEpoch && cursor.lastGapSequence >= gapSequence &&
		cursor.registrationAcceptedAtMs <= declaredGapStartTimeMs &&
		cursor.importedThroughTimeMs >= declaredGapEndTimeMs &&
		cursor.registrationClockDomainId == nextClockDomainId && recordedAtMs <= cursor.updatedAtMs &&
		gapStartTimeMs >= declaredGapStartTimeMs && gapEndTimeMs <= declaredGapEndTimeMs &&
		gapEndTimeMs > gapStartTimeMs && originMatches
	if (!valid) return AmbientStepsGapReadQualification(this, false, null)
	val retainedStart = maxOf(gapStartTimeMs, evidenceState.retainedFromMs ?: gapStartTimeMs)
	val effect = if (retainedStart < gapEndTimeMs) {
		EffectiveAmbientStepsGap(retainedStart, gapEndTimeMs)
	} else {
		null
	}
	return AmbientStepsGapReadQualification(this, true, effect)
}

private fun AmbientStepsFactRevisionEntity.toQualifiedFact(
	day: AmbientStepsDayIdentity,
) = QualifiedAmbientStepsFact(
	logicalFactId = logicalFactId,
	day = day,
	startTimeMs = requireNotNull(windowStartTimeMs),
	endTimeMs = requireNotNull(windowEndTimeMs),
	stepCount = requireNotNull(stepCount),
	provenance = AmbientStepsProviderProvenance(
		provider = requireNotNull(provider),
		sourceInstanceId = requireNotNull(sourceInstanceId),
		registrationGeneration = requireNotNull(registrationGeneration),
		continuitySegmentGeneration = requireNotNull(continuitySegmentGeneration),
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

private fun unavailableDay(
	day: AmbientStepsDayIdentity,
	sessions: List<QualifiedSessionStepsWindow>,
	cause: AmbientStepsDayCause,
): AmbientStepsDayProduct {
	val unavailable = AmbientStepsNumericValue.Unavailable(setOf(cause))
	return AmbientStepsDayProduct(day, unavailable, sessions, unavailable)
}

private fun com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
	.unverifiableImportedSession() = QualifiedSessionStepsWindow(
	logicalTrackingId = identity,
	serviceRunId = identity,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	stepCount = null,
	storedZoneId = null,
	origin = QualifiedSessionStepsOrigin.PORTABLE_IMPORT,
)

internal const val MAX_AMBIENT_DAY_PAGE_SIZE = 31
private const val MAX_ACTIVE_AMBIENT_CURSORS = 1
private const val MAX_AMBIENT_FACTS_PER_PAGE = 400
private const val MAX_AMBIENT_GAPS_PER_PAGE = 400
private const val MAX_HISTORICAL_AMBIENT_CURSORS_PER_PAGE = 400
private const val MAX_AMBIENT_AUTHORITY_TRANSITIONS_PER_PAGE = 400
private const val MAX_AMBIENT_AUTHORIZATION_KEYS_PER_PAGE = 400
private const val MAX_AMBIENT_AUTHORIZATION_MEMBERS_PER_PAGE = 400
