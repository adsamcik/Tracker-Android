package com.adsamcik.tracker.tracker.source.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class WifiWalAdapterRejection {
	MISSING_EVENT,
	WRONG_SOURCE,
	INCOMPLETE_CAPTURE_BINDING,
	WAL_INTEGRITY_MISMATCH,
	SOURCE_SEQUENCE_MISMATCH,
	DELIVERY_CARDINALITY_OVERFLOW,
	MALFORMED_PRODUCER_DELIVERY,
	PAYLOAD_MISMATCH,
	DELIVERY_IDENTITY_MISMATCH,
	MISSING_HISTORICAL_PLAN,
	HISTORICAL_PLAN_MISMATCH,
	HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
	HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
	MISSING_REGISTRATION,
	REGISTRATION_MISMATCH,
	MISSING_AUTHORIZATION,
	AUTHORIZATION_MEMBER_OVERFLOW,
	AUTHORIZATION_MISMATCH,
	MISSING_POLICY,
	POLICY_MISMATCH,
	MISSING_CONSENT,
	CONSENT_MISMATCH,
	MISSING_SESSION,
	SESSION_MISMATCH,
	MANIFEST_TIMELINE_OVERFLOW,
	MANIFEST_TIMELINE_UNVERIFIABLE,
	MANIFEST_MISMATCH,
	MISSING_SEGMENT,
	SEGMENT_MISMATCH,
	INVALID_STORED_ZONE,
	DELETED_EVIDENCE,
	DELETED_SCOPE,
	SCOPE_DELETION_AUTHORITY_MISMATCH,
	BEFORE_RETENTION_FLOOR,
	TEMPORAL_AUTHORITY_UNVERIFIABLE,
}

internal sealed interface WifiWalAdapterResult {
	data class Evaluated(
		val classification: WifiCapturedFactClassification,
		val wal: SourceEventWalEntity,
		val qualifiedItemIndexes: List<Int>,
	) : WifiWalAdapterResult

	data class Groups(val results: List<WifiWalAdapterResult>) : WifiWalAdapterResult

	data class Rejected(val reason: WifiWalAdapterRejection) : WifiWalAdapterResult
}

/**
 * Dormant read-only bridge from one exact retained Wi-Fi WAL row to the source-local fact model.
 *
 * The caller supplies only an event id. Provider bytes and historical capture authority are loaded
 * from Room in one transaction; this component neither starts a provider nor writes a product fact.
 */
internal class WifiWalQualificationAdapter @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	suspend fun qualify(eventId: SourceEventId): WifiWalAdapterResult = qualify(
		eventId = eventId,
		classify = { input, authority, deletion ->
			WifiCapturedFactClassifier.classify(input, authority, deletion)
		},
	)

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	suspend fun qualify(
		eventId: SourceEventId,
		classify: suspend (
			WifiObservationInput,
			WifiCaptureAuthority,
			WifiDeletionAuthority,
		) -> WifiCapturedFactClassification,
	): WifiWalAdapterResult = database.withTransaction {
		currentCoroutineContext().ensureActive()
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(eventId.value, MAX_WIFI_WAL_PAYLOAD_BYTES)
			?: return@withTransaction rejectWifiWal(WifiWalAdapterRejection.MISSING_EVENT)
		if (wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION) {
			return@withTransaction qualifyWifiWalCaptureGroup(
				database, payloadCodec, planCodec, bootClockDomainProvider, eventId, wal, classify,
			)
		}
		val carrier = when (val result = WifiWalCarrierAuthenticator.authenticate(wal)) {
			is WifiWalCarrierAuthentication.Authenticated -> result.carrier
			is WifiWalCarrierAuthentication.Rejected ->
                return@withTransaction rejectWifiWal(result.reason.toWifiWalAdapterRejection())
		}
		val registration = database.sourceBrokerDao().registration(
			WIFI_QUAL_SOURCE, wal.registrationGeneration,
		)
			?: return@withTransaction rejectWifiWal(WifiWalAdapterRejection.MISSING_REGISTRATION)
		val start = registration.acceptedElapsedRealtimeNanos
			?: return@withTransaction rejectWifiWal(WifiWalAdapterRejection.REGISTRATION_MISMATCH)
		val items = database.qualifyWifiPurposeItems(wal, carrier.payload, start,
			registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE)
			?: return@withTransaction rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
		val members = items.historicalMembers().filter { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
				items.forDemand(requireNotNull(member.demandId)).isNotEmpty()
		}.map { member ->
			items.authorizationTimeline.flatMap { it.authorizedMembers }.last { it.demandId == member.demandId }
		}
		val results = members.map { member ->
			qualifyWifiWalCaptureGroup(
				database, payloadCodec, planCodec, bootClockDomainProvider, eventId, wal, classify, member, items,
			)
		}
		if (results.singleOrNull() is WifiWalAdapterResult.Evaluated) {
			results.single()
		}
		else {
			WifiWalAdapterResult.Groups(results)
		}
	}
}
