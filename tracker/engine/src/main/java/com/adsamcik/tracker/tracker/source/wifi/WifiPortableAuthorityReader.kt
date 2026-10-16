package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.dao.WifiPortableWalIdentityRow
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.stats.api.repository.WifiCapturedPortableFormatV1
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.MAX_PLAN_PAYLOAD_BYTES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.PLAN_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.PORTABLE_WAL_BATCH_SIZE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Loads the complete selected portable authority graph with fixed-count, cap+1 batch reads.
 * Nothing below is keyed by one WAL row, so adding observations cannot create Room fan-out.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun WifiCapturedFactMaintenance.loadPortableAuthoritySnapshot(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	walRows: List<SourceEventWalEntity>,
	completeness: List<SourceSessionCompletenessEntity>,
): WifiPortableAuthoritySnapshot {
	val counter = WifiPortableQueryCounter()
	val readDao = database.trackingHistoryReadDao()
	val factDao = database.wifiCapturedFactDao()
	val session = counter.read { database.sourceSessionDao().session(logicalTrackingId) }
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val runs = counter.read { readDao.serviceRuns(serviceRunIds) }
	if (runs.size != serviceRunIds.size ||
		runs.distinctBy(SourceServiceRunEntity::serviceRunId).size != runs.size ||
		runs.mapTo(mutableSetOf(), SourceServiceRunEntity::serviceRunId) != serviceRunIds.toSet()
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val manifests = counter.read {
		readDao.manifests(serviceRunIds, WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1)
	}
	val sources = counter.read {
		readDao.manifestSources(serviceRunIds, WifiCapturedPortableFormatV1.MAX_MANIFEST_SOURCES + 1)
	}
	if (manifests.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS ||
		sources.size > WifiCapturedPortableFormatV1.MAX_MANIFEST_SOURCES
	) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
	val segments = counter.read { readDao.segments(segmentIds) }
	if (segments.size != segmentIds.size || segments.distinctBy(SessionSegment::id).size != segments.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
	val wifiManifestKeys = sources.filter { source ->
		source.sourceKind == WIFI_SOURCE && source.purpose == CAPTURE_PURPOSE &&
			source.persistenceEligible
	}.mapTo(mutableSetOf()) { it.logicalTrackingId to it.manifestRevision }
	val wifiPlanRevisions = manifests.filter { manifest ->
		(manifest.logicalTrackingId to manifest.manifestRevision) in wifiManifestKeys
	}.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
	val planHeaders = counter.read {
		factDao.historyAcquisitionPlanRevisions(
			planRevisions,
			WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
		)
	}
	val desiredPlans = counter.read {
		factDao.historyDesiredPlans(
			WIFI_SOURCE,
			wifiPlanRevisions,
			WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
		)
	}
	if (planHeaders.size != planRevisions.size || desiredPlans.size != wifiPlanRevisions.size ||
		planHeaders.distinctBy(AcquisitionPlanRevisionEntity::revision).size != planHeaders.size ||
		desiredPlans.distinctBy(SourceDesiredPlanEntity::revision).size != desiredPlans.size
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val decodedPlans = desiredPlans.associate { desired ->
		desired.revision to decodeWifiPortablePlan(desired)
	}

	val (registrationGenerations, registrations) = loadWifiPortableRegistrations(walRows, completeness, counter)
	val (authorizationRows, authorizationSnapshots) = loadWifiPortableAuthorizations(registrationGenerations, counter)

	val demandIds = authorizationRows.mapNotNull { it.demandId }.distinct()
	if (demandIds.size > WifiCapturedPortableFormatV1.MAX_DEMANDS) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	val demands = mutableListOf<SourceDemandEntity>()
	for (batch in demandIds.chunked(PORTABLE_WAL_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val loaded = counter.read { factDao.historyDemands(batch, batch.size + 1) }
		if (loaded.size != batch.size || loaded.any { it.demandId !in batch }) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		demands += loaded
	}
	if (demands.distinctBy(SourceDemandEntity::demandId).size != demands.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val policyRevisions = demands.map(SourceDemandEntity::sourcePolicyRevision).distinct()
	val policies = mutableListOf<SourcePolicyEntity>()
	for (batch in policyRevisions.chunked(PORTABLE_WAL_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val loaded = counter.read { factDao.historyPoliciesAtRevisions(WIFI_SOURCE, batch, batch.size + 1) }
		if (loaded.size != batch.size || loaded.any { it.policyRevision !in batch }) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		policies += loaded
	}
	val consentIdentities = demands.map { demand ->
		portableConsentIdentity(
			demand.policyPurposeIncludingCapture()
				?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE),
			demand.consentEpoch,
		)
	}.distinct()
	val consents = mutableListOf<SourceConsentEpochEntity>()
	for (batch in consentIdentities.chunked(PORTABLE_WAL_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val loaded = counter.read { factDao.historyConsentAuthorities(WIFI_SOURCE, batch, batch.size + 1) }
		if (loaded.size != batch.size || loaded.any { portableConsentIdentity(it.purpose, it.epoch) !in batch }) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		consents += loaded
	}

	val actions = loadWifiPortableStartActions(serviceRunIds, counter)

	val sequenceProofs = loadWifiPortableSequenceProofs(walRows, counter)
	val deliveryProofs = loadWifiPortableDeliveryProofs(walRows, counter)
	counter.requireWithinLimit()
	return WifiPortableAuthoritySnapshot(
		session = session,
		savedSessionCutoff = counter.read { database.savedSessionCutoff(session) },
		runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId),
		manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId),
		sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision },
		segmentsById = segments.associateBy(SessionSegment::id),
		planHeadersByRevision = planHeaders.associateBy(AcquisitionPlanRevisionEntity::revision),
		desiredPlansByRevision = desiredPlans.associateBy(SourceDesiredPlanEntity::revision),
		plansByRevision = decodedPlans,
		registrationsByGeneration = registrations.associateBy {
			it.registrationGeneration
		},
		authorizationsByKey = authorizationSnapshots,
		authorizationsByGeneration = authorizationSnapshots.values.groupBy {
			it.members.first().registrationGeneration
		}.mapValues { (_, snapshots) -> snapshots.sortedWith(PORTABLE_AUTHORIZATION_ORDER) },
		demandsById = demands.associateBy(SourceDemandEntity::demandId),
		policiesByRevision = policies.associateBy(SourcePolicyEntity::policyRevision),
		consentsByIdentity = consents.associateBy { portableConsentIdentity(it.purpose, it.epoch) },
		actionsByRun = actions.groupBy(LifecycleDesiredActionEntity::serviceRunId),
		sequenceProofsByIdentity = sequenceProofs.groupBy(WifiPortableWalIdentityRow::portableSequenceIdentity),
		deliveryProofsByIdentity = deliveryProofs.groupBy(WifiPortableWalIdentityRow::portableDeliveryIdentity),
		queryCount = counter.count,
	)
}

private class WifiPortableQueryCounter {
	var count: Int = 0
		private set

	suspend fun <T> read(block: suspend () -> T): T {
		count = Math.addExact(count, 1)
		requireWithinLimit()
		return block()
	}

	fun requireWithinLimit() {
		if (count > WIFI_PORTABLE_AUTHORITY_QUERY_LIMIT) {
			throw WifiCapturedMaintenanceLimitExceeded()
		}
	}
}

private suspend fun WifiCapturedFactMaintenance.loadWifiPortableRegistrations(
	walRows: List<SourceEventWalEntity>,
	completeness: List<SourceSessionCompletenessEntity>,
	counter: WifiPortableQueryCounter,
): Pair<List<Long>, List<ProviderRegistrationGenerationEntity>> {
	val factDao = database.wifiCapturedFactDao()
	val registrationGenerations = (walRows.map(SourceEventWalEntity::registrationGeneration) +
		completeness.filter { it.registrationGeneration > 0L }
			.map(SourceSessionCompletenessEntity::registrationGeneration)).distinct()
	if (registrationGenerations.size > WifiCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	val registrations = counter.read {
		factDao.historyProviderRegistrations(
			WIFI_SOURCE,
			registrationGenerations,
			WifiCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS + 1,
		)
	}
	if (registrations.size != registrationGenerations.size ||
		registrations.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration).size !=
			registrations.size
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return registrationGenerations to registrations
}

private suspend fun WifiCapturedFactMaintenance.loadWifiPortableAuthorizations(
	registrationGenerations: List<Long>,
	counter: WifiPortableQueryCounter,
): Pair<List<SourceAuthorizationEntity>, Map<Pair<Long, Long>, SourceAuthorizationSnapshot>> {
	val factDao = database.wifiCapturedFactDao()
	val authorizationRows = counter.read {
		factDao.historyAuthorizations(
			WIFI_SOURCE,
			registrationGenerations,
			WifiCapturedPortableFormatV1.MAX_AUTHORIZATION_ROWS + 1,
		)
	}
	if (authorizationRows.size > WifiCapturedPortableFormatV1.MAX_AUTHORIZATION_ROWS ||
		authorizationRows.any { it.registrationGeneration !in registrationGenerations }
	) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	val authorizationGroups = authorizationRows.groupBy {
		it.registrationGeneration to it.authorizationRevision
	}
	val authorizationSnapshots = authorizationGroups.mapValues { (_, rows) ->
		runCatching { rows.toAuthorizationSnapshotOrNull() }.getOrNull()
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (authorizationSnapshots.size != authorizationGroups.size ||
		authorizationSnapshots.values.any { snapshot ->
			snapshot.members.distinctBy { it.memberId }.size != snapshot.members.size
		}
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return authorizationRows to authorizationSnapshots
}

private suspend fun WifiCapturedFactMaintenance.loadWifiPortableStartActions(
	serviceRunIds: List<String>,
	counter: WifiPortableQueryCounter,
): List<LifecycleDesiredActionEntity> {
	val factDao = database.wifiCapturedFactDao()
	val actions = counter.read {
		factDao.historyStartActions(
			WIFI_SOURCE,
			serviceRunIds,
			WifiCapturedPortableFormatV1.MAX_MANIFESTS + 1,
		)
	}
	if (actions.size > WifiCapturedPortableFormatV1.MAX_MANIFESTS ||
		actions.distinctBy(LifecycleDesiredActionEntity::actionId).size != actions.size
	) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	return actions
}

private suspend fun WifiCapturedFactMaintenance.loadWifiPortableSequenceProofs(
	walRows: List<SourceEventWalEntity>,
	counter: WifiPortableQueryCounter,
): List<WifiPortableWalIdentityRow> {
	val factDao = database.wifiCapturedFactDao()
	val sequenceProofs = mutableListOf<WifiPortableWalIdentityRow>()
	val sequenceIdentities = walRows.map(SourceEventWalEntity::portableSequenceIdentity).distinct()
	if (sequenceIdentities.size != walRows.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for (batch in sequenceIdentities.chunked(PORTABLE_WAL_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val loaded = counter.read {
			factDao.portableWalSequenceIdentities(WIFI_SOURCE, batch, batch.size + 1)
		}
		if (loaded.size > batch.size) {
			throw WifiCapturedMaintenanceLimitExceeded()
		}
		sequenceProofs += loaded
	}
	return sequenceProofs
}

private suspend fun WifiCapturedFactMaintenance.loadWifiPortableDeliveryProofs(
	walRows: List<SourceEventWalEntity>,
	counter: WifiPortableQueryCounter,
): List<WifiPortableWalIdentityRow> {
	val factDao = database.wifiCapturedFactDao()
	val deliveryProofs = mutableListOf<WifiPortableWalIdentityRow>()
	val deliveryIdentities = walRows.map { row ->
		row.portableDeliveryIdentity()
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}.distinct()
	if (deliveryIdentities.size != walRows.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for (batch in deliveryIdentities.chunked(PORTABLE_WAL_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val loaded = counter.read {
			factDao.portableWalDeliveryIdentities(WIFI_SOURCE, batch, batch.size + 1)
		}
		if (loaded.size > batch.size) {
			throw WifiCapturedMaintenanceLimitExceeded()
		}
		deliveryProofs += loaded
	}
	return deliveryProofs
}

private fun WifiCapturedFactMaintenance.decodeWifiPortablePlan(desired: SourceDesiredPlanEntity): WifiPlan {
	val plan = runCatching { planCodec.decode(desired.payload) as? WifiPlan }.getOrNull()
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val encoded = runCatching { planCodec.encode(plan) }.getOrNull()
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (desired.payloadVersion != PLAN_PAYLOAD_VERSION || desired.sourceKind != WIFI_SOURCE ||
		desired.revision != plan.revision || desired.payload.size > MAX_PLAN_PAYLOAD_BYTES
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!desired.payload.contentEquals(encoded.bytes) || desired.payloadChecksum != encoded.checksum ||
		!plan.hasSupportedHistoricalShape()
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return plan
}
