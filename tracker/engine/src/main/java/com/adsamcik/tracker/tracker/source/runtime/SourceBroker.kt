package com.adsamcik.tracker.tracker.source.runtime

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.coordinator.SessionManifestPurpose
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable authority for source consumers and physical registration eligibility.
 *
 * Provider adapters still own Android-specific calls, but they may reserve an identity only from
 * this broker's active demand vector. The vector is persisted before registration and later used to
 * reject callbacks that attempt to cross a purpose, policy, consent, manifest, or generation fence.
 */
@Singleton
class SourceBroker @Inject constructor(
	private val database: AppDatabase,
) {
	fun sessionConsumerId(logicalTrackingId: String): String = "session:$logicalTrackingId"

	fun buildSessionDemands(
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevision: Long,
		lifecycleLeaseGeneration: Long,
		policyRevision: Long,
		bindings: Collection<SessionManifestSourceEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): List<SourceDemandEntity> {
		val consumerId = sessionConsumerId(logicalTrackingId)
		return bindings.sortedWith(
			compareBy<SessionManifestSourceEntity>(SessionManifestSourceEntity::purpose)
				.thenBy(SessionManifestSourceEntity::sourceKind),
		).map { binding ->
			val purpose = when (binding.purpose) {
				SourceBrokerPurpose.SESSION_CAPTURE -> SourceBrokerPurpose.SESSION_CAPTURE
				SessionManifestPurpose.CONTROL.name -> SourceBrokerPurpose.CONTROL_CONTINUATION
				else -> error("Unsupported session-manifest purpose ${binding.purpose}")
			}
			SourceDemandEntity(
				demandId = demandId(
					consumerId,
					binding.sourceKind,
					purpose,
					policyRevision,
					binding.consentEpoch,
					manifestRevision,
					bootId,
					elapsedRealtimeNanos,
				),
				consumerId = consumerId,
				sourceKind = binding.sourceKind,
				purpose = purpose,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				manifestRevision = manifestRevision,
				lifecycleLeaseGeneration = lifecycleLeaseGeneration,
				sourcePolicyRevision = policyRevision,
				consentEpoch = binding.consentEpoch,
				persistenceEligible = binding.persistenceEligible,
				qosCode = binding.qosCode,
				maximumAgeMs = qosMaximumAgeMs(binding.qosCode),
				desiredLatencyMs = qosDesiredLatencyMs(binding.qosCode),
				requestedBootId = bootId,
				requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
				requestedAtMs = wallTimeMs,
				status = SourceDemandEntity.STATUS_ACTIVE,
				retireBootId = null,
				retireElapsedRealtimeNanos = null,
				retiredAtMs = null,
			)
		}
	}

	/** Must be called from the manifest/intent transaction. */
	suspend fun replaceSessionDemandsInTransaction(
		logicalTrackingId: String,
		demands: List<SourceDemandEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		val consumerId = sessionConsumerId(logicalTrackingId)
		val dao = database.sourceBrokerDao()
		val affectedSources = (dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind) +
			demands.map(SourceDemandEntity::sourceKind)).toSet()
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		if (demands.isNotEmpty()) dao.insertDemands(demands)
		affectedSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(
				sourceKind,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}
	}

	suspend fun markSessionDemandsRetiring(
		logicalTrackingId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val consumerId = sessionConsumerId(logicalTrackingId)
		val affectedSources = dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind).toSet()
		val updated = dao.markConsumerRetiring(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		affectedSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(sourceKind, bootId, elapsedRealtimeNanos, wallTimeMs)
		}
		updated
	}

	suspend fun retireSessionDemands(
		logicalTrackingId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val consumerId = sessionConsumerId(logicalTrackingId)
		val affectedSources = dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind).toSet()
		val updated = dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		affectedSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(sourceKind, bootId, elapsedRealtimeNanos, wallTimeMs)
		}
		updated
	}

	/**
	 * Replaces one application-scoped control demand before its provider is reconciled.
	 * Fails closed if the authoritative policy has no current CONTROL epoch.
	 */
	suspend fun replaceAutomaticControlDemand(
		consumerId: String,
		source: SourceKind,
		enabled: Boolean,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		maximumAgeMs: Long,
		desiredLatencyMs: Long,
	): SourceDemandEntity? = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val affectedSourceKinds = (
			dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind) + source.stableCode
		).toSet()
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		if (!enabled) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, source.stableCode)
		if (policy == null) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		val consentEpoch = policy.controlConsentEpoch
		if (consentEpoch == null) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		val demand = SourceDemandEntity(
			demandId = demandId(
				consumerId,
				source.stableCode,
				SourceBrokerPurpose.CONTROL_AUTOSTART,
				authority.currentPolicyRevision,
				consentEpoch,
				null,
				bootId,
				elapsedRealtimeNanos,
			),
			consumerId = consumerId,
			sourceKind = source.stableCode,
			purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = authority.currentPolicyRevision,
			consentEpoch = consentEpoch,
			persistenceEligible = policy.controlPersistenceEligible,
			qosCode = policy.qosCode,
			maximumAgeMs = maximumAgeMs,
			desiredLatencyMs = desiredLatencyMs,
			requestedBootId = bootId,
			requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
			requestedAtMs = wallTimeMs,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		dao.insertDemands(listOf(demand))
		rotateCurrentAuthorizationsInTransaction(
			affectedSourceKinds,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		demand
	}

	suspend fun registrationAuthorization(
		source: SourceKind,
		sourceInstanceId: String,
		registrationGeneration: Long,
		bootId: String,
		physicalConfigurationFingerprint: String,
		observedElapsedRealtimeNanos: Long,
	): ProviderRegistrationAuthorization? = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val generation = dao.registrationAtObservedTime(
			source.stableCode,
			registrationGeneration,
			sourceInstanceId,
			bootId,
			physicalConfigurationFingerprint,
			observedElapsedRealtimeNanos,
		)
			?: return@withTransaction null
		val authorization = dao.authorizationAt(
			source.stableCode,
			registrationGeneration,
			bootId,
			observedElapsedRealtimeNanos,
		).toAuthorizationSnapshotOrNull() ?: return@withTransaction null
		if (authorization.isDenied) return@withTransaction null
		ProviderRegistrationAuthorization(
			generation,
			authorization,
		)
	}

	private suspend fun rotateCurrentAuthorizationsInTransaction(
		sourceKinds: Collection<Int>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		sourceKinds.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(
				sourceKind,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}
	}

	private suspend fun rotateCurrentAuthorizationInTransaction(
		sourceKind: Int,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		val dao = database.sourceBrokerDao()
		val demands = dao.authorizationDemands(sourceKind)
		val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
		dao.currentPhysicalRegistrations(sourceKind)
			.filter { registration -> registration.clockDomainId == bootId }
			.forEach { registration ->
				val current = dao.latestAuthorization(sourceKind, registration.registrationGeneration)
					.toAuthorizationSnapshotOrNull()
				if (current?.authorizationFingerprint == fingerprint) return@forEach
				val revision = dao.maximumAuthorizationRevision(sourceKind) + 1L
				dao.insertAuthorizations(
					SourceBrokerAuthorization.rows(
						sourceKind,
						registration.registrationGeneration,
						revision,
						demands,
						bootId,
						elapsedRealtimeNanos,
						wallTimeMs,
					),
				)
			}
	}

	private fun demandId(
		consumerId: String,
		sourceKind: Int,
		purpose: String,
		policyRevision: Long,
		consentEpoch: Long,
		manifestRevision: Long?,
		activationBootId: String,
		activationElapsedRealtimeNanos: Long,
	): String {
		val value = listOf(
			consumerId,
			sourceKind,
			purpose,
			policyRevision,
			consentEpoch,
			manifestRevision ?: 0L,
			activationBootId,
			activationElapsedRealtimeNanos,
		).joinToString("\u001f")
		return MessageDigest.getInstance("SHA-256")
			.digest(value.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	private fun qosMaximumAgeMs(qosCode: Int): Long = when (qosCode) {
		0 -> Long.MAX_VALUE
		1 -> 120_000L
		2 -> 30_000L
		else -> 5_000L
	}

	private fun qosDesiredLatencyMs(qosCode: Int): Long = when (qosCode) {
		0 -> Long.MAX_VALUE
		1 -> 60_000L
		2 -> 15_000L
		else -> 1_000L
	}
}

data class ProviderRegistrationAuthorization(
	val generation: ProviderRegistrationGenerationEntity,
	val authorization: SourceAuthorizationSnapshot,
) {
	fun captureEligibility(
		logicalTrackingId: String,
		manifestRevision: Long,
	): SourceAuthorizationEntity? = authorization.authorizedMembers.singleOrNull { item ->
		item.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
			item.logicalTrackingId == logicalTrackingId &&
			item.manifestRevision == manifestRevision &&
			item.persistenceEligible
	}
}
