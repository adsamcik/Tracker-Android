package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface BootClockDomainProvider {
	fun current(): String
}

@Singleton
class AndroidBootClockDomainProvider @Inject constructor(
	@ApplicationContext private val context: Context,
) : BootClockDomainProvider {
	private val conservativeProcessBootId = "process-${UUID.randomUUID()}"

	override fun current(): String {
		val bootCount = Settings.Global.getInt(
			context.contentResolver,
			Settings.Global.BOOT_COUNT,
			UNKNOWN_BOOT_COUNT,
		)
		if (bootCount >= 0) return "android-boot-count:$bootCount"

		// An unreadable BOOT_COUNT cannot safely be replaced by a wall-clock estimate: clock
		// changes can split one boot or join two boots. A process boundary is conservative, but it
		// never authorizes an elapsed-time lease or callback across an unverified reboot.
		return conservativeProcessBootId
	}

	private companion object {
		const val UNKNOWN_BOOT_COUNT = -1
	}
}

data class SourceRegistration(
	val ownerScope: String,
	val state: SourceRegistrationStateEntity,
	val physicalConfigurationFingerprint: String,
	val authorization: SourceAuthorizationSnapshot,
	val requiresProviderAcceptance: Boolean,
	val predecessorState: SourceRegistrationStateEntity? = null,
) {
	val purposeEligibilityMask: Long get() = authorization.purposeEligibilityMask
	val eligibilityFingerprint: String get() = authorization.authorizationFingerprint
}

/**
 * Allocates durable source identities and monotonically increasing source sequences.
 *
 * The broker/source pair is the owner scope, so compatible control, capture, and ambient demands
 * share one sequence space. A fresh immutable physical generation is allocated only when the
 * normalized provider configuration, boot domain, or collected-data epoch changes. Authority-only
 * changes append an observed-time authorization revision without restarting compatible provider
 * work.
 */
@Singleton
class SourceRegistrationRepository @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val clockDomainProvider: BootClockDomainProvider,
) {
	suspend fun begin(
		source: SourceKind,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): SourceRegistration {
		require(physicalConfigurationFingerprint.isNotBlank())
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = clockDomainProvider.current()
		return database.withTransaction {
			val brokerDao = database.sourceBrokerDao()
			val demands = brokerDao.authorizationDemands(source.stableCode)
			require(demands.isNotEmpty()) {
				"A source registration requires at least one durable active broker demand"
			}
			val ownerScope = "source-broker:${source.stableCode}"
			val purposeEligibilityMask = SourceBrokerAuthorization.purposeMask(demands)
			require(purposeEligibilityMask > 0L) { "Broker demand has no recognized purpose eligibility" }
			val dao = database.sourceRegistrationStateDao()
			val current = dao.get(source.stableCode, ownerScope)
			val currentPhysical = current?.let { state ->
				brokerDao.registration(state.sourceKind, state.registrationGeneration)
			}
			if (current != null && currentPhysical != null &&
				current.clockDomainId == clockDomainId &&
				current.collectedDataEpoch == lifecycle.epoch &&
				currentPhysical.status in setOf(
					ProviderRegistrationGenerationEntity.STATUS_RESERVED,
					ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				) &&
				currentPhysical.physicalConfigurationFingerprint == physicalConfigurationFingerprint
			) {
				val reused = current.copy(appliedRevision = appliedRevision, updatedAtMs = updatedAtMs)
				dao.replace(reused)
				val authorization = appendAuthorizationIfChanged(
					source = source,
					registrationGeneration = current.registrationGeneration,
					demands = demands,
					bootId = clockDomainId,
					elapsedRealtimeNanos = updatedElapsedRealtimeNanos,
					wallTimeMs = updatedAtMs,
				)
				return@withTransaction SourceRegistration(
					ownerScope = ownerScope,
					state = reused,
					physicalConfigurationFingerprint = physicalConfigurationFingerprint,
					authorization = authorization,
					requiresProviderAcceptance = currentPhysical.status ==
						ProviderRegistrationGenerationEntity.STATUS_RESERVED,
					predecessorState = current,
				)
			}
			val registrationGeneration = brokerDao.maximumRegistrationGeneration(source.stableCode) + 1L
			val next = when {
				current == null -> SourceRegistrationStateEntity(
					sourceKind = source.stableCode,
					ownerScope = ownerScope,
					sourceInstanceId = UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = registrationGeneration,
					nextSequence = 0L,
					appliedRevision = appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = updatedAtMs,
				)
				current.clockDomainId == clockDomainId && current.collectedDataEpoch == lifecycle.epoch ->
					current.copy(
						registrationGeneration = registrationGeneration,
						appliedRevision = appliedRevision,
						updatedAtMs = updatedAtMs,
					)
				else -> current.copy(
					sourceInstanceId = UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = registrationGeneration,
					nextSequence = 0L,
					appliedRevision = appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = updatedAtMs,
				)
			}
			brokerDao.insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = source.stableCode,
					registrationGeneration = next.registrationGeneration,
					sourceInstanceId = next.sourceInstanceId,
					ownerScope = ownerScope,
					clockDomainId = next.clockDomainId,
					physicalConfigurationFingerprint = physicalConfigurationFingerprint,
					collectedDataEpoch = lifecycle.epoch,
					status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
					reservedAtMs = updatedAtMs,
					reservedElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
					acceptedAtMs = null,
					acceptedElapsedRealtimeNanos = null,
					retiredAtMs = null,
					retiredElapsedRealtimeNanos = null,
					failureCode = null,
				),
			)
			val authorization = appendAuthorizationIfChanged(
				source = source,
				registrationGeneration = next.registrationGeneration,
				demands = demands,
				bootId = clockDomainId,
				elapsedRealtimeNanos = updatedElapsedRealtimeNanos,
				wallTimeMs = updatedAtMs,
			)
			SourceRegistration(
				ownerScope,
				next,
				physicalConfigurationFingerprint,
				authorization,
				requiresProviderAcceptance = true,
				predecessorState = current,
			)
		}
	}

	suspend fun markAccepted(
		registration: SourceRegistration,
		acceptedAtMs: Long,
		acceptedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): ProviderRegistrationGenerationEntity? {
		if (!registration.requiresProviderAcceptance) return null
		return database.sourceBrokerDao().acceptReservedReplacement(
			reservedState = registration.state,
			expectedPointerGeneration = registration.predecessorState?.registrationGeneration,
			expectedPointerInstanceId = registration.predecessorState?.sourceInstanceId,
			requiredAuthorizationFingerprint = registration.authorization.authorizationFingerprint,
			acceptedAtMs = acceptedAtMs,
			acceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
		)
	}

	suspend fun markFailed(
		registration: SourceRegistration,
		failureCode: String,
		failedAtMs: Long,
		failedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	) {
		database.sourceBrokerDao().finishRegistration(
			registration.state.sourceKind,
			registration.state.registrationGeneration,
			registration.state.sourceInstanceId,
			ProviderRegistrationGenerationEntity.STATUS_FAILED,
			failedAtMs,
			failedElapsedRealtimeNanos,
			failureCode,
		)
	}

	suspend fun markRetired(
		registration: SourceRegistration,
		retiredAtMs: Long,
		reason: String? = null,
		retiredElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	) {
		database.sourceBrokerDao().finishRegistration(
			registration.state.sourceKind,
			registration.state.registrationGeneration,
			registration.state.sourceInstanceId,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs,
			retiredElapsedRealtimeNanos,
			reason,
		)
	}

	private suspend fun appendAuthorizationIfChanged(
		source: SourceKind,
		registrationGeneration: Long,
		demands: List<com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): SourceAuthorizationSnapshot {
		val dao = database.sourceBrokerDao()
		val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
		val latest = dao.latestAuthorization(source.stableCode, registrationGeneration)
			.toAuthorizationSnapshotOrNull()
		if (latest?.authorizationFingerprint == fingerprint) return latest
		val revision = dao.maximumAuthorizationRevision(source.stableCode) + 1L
		val rows = SourceBrokerAuthorization.rows(
			source.stableCode,
			registrationGeneration,
			revision,
			demands,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		dao.insertAuthorizations(rows)
		return requireNotNull(rows.toAuthorizationSnapshotOrNull())
	}

	suspend fun allocateSequence(registration: SourceRegistration, updatedAtMs: Long): Long =
		database.sourceRegistrationStateDao().allocateSequence(
			registration.state.sourceKind,
			registration.ownerScope,
			updatedAtMs,
		).also { allocated ->
			check(allocated.sourceInstanceId == registration.state.sourceInstanceId) {
				"Source registration changed while a callback was being admitted"
			}
			check(allocated.registrationGeneration == registration.state.registrationGeneration) {
				"Source generation changed while a callback was being admitted"
			}
		}.nextSequence

	suspend fun loadRuntimeState(registration: SourceRegistration): SourceRuntimeStateEntity? =
		database.sourceRuntimeStateDao()
			.get(registration.state.sourceKind, registration.ownerScope)
			?.takeIf { state ->
				state.sourceInstanceId == registration.state.sourceInstanceId &&
					state.clockDomainId == registration.state.clockDomainId
			}

	suspend fun saveRuntimeState(
		registration: SourceRegistration,
		lastProviderSequence: Long,
		lastAdmittedSourceSequence: Long?,
		lastAdmissionOrdinal: Long?,
		stateVersion: Int,
		payload: ByteArray,
		updatedAtMs: Long,
	) {
		database.sourceRuntimeStateDao().save(
			SourceRuntimeStateEntity(
				sourceKind = registration.state.sourceKind,
				ownerScope = registration.ownerScope,
				sourceInstanceId = registration.state.sourceInstanceId,
				clockDomainId = registration.state.clockDomainId,
				registrationGeneration = registration.state.registrationGeneration,
				lastProviderSequence = lastProviderSequence,
				lastAdmittedSourceSequence = lastAdmittedSourceSequence,
				lastAdmissionOrdinal = lastAdmissionOrdinal,
				stateVersion = stateVersion,
				payload = payload,
				updatedAtMs = updatedAtMs,
			),
		)
	}
}
