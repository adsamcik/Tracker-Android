package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
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
	override fun current(): String {
		val bootCount = Settings.Global.getInt(
			context.contentResolver,
			Settings.Global.BOOT_COUNT,
			UNKNOWN_BOOT_COUNT,
		)
		if (bootCount >= 0) return "android-boot-count:$bootCount"

		val estimatedBootWallTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
		return "android-boot-epoch-hour:${estimatedBootWallTimeMs / MILLIS_PER_HOUR}"
	}

	private companion object {
		const val UNKNOWN_BOOT_COUNT = -1
		const val MILLIS_PER_HOUR = 60L * 60L * 1_000L
	}
}

data class SourceRegistration(
	val ownerScope: String,
	val state: SourceRegistrationStateEntity,
)

/**
 * Allocates durable source identities and monotonically increasing source sequences.
 *
 * The active logical session is the owner scope. Re-registering after process death therefore
 * continues the same source instance and sequence, while a new session cannot inherit an
 * off-session hardware baseline.
 */
@Singleton
class SourceRegistrationRepository @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val clockDomainProvider: BootClockDomainProvider,
) {
	suspend fun begin(source: SourceKind, appliedRevision: Long, updatedAtMs: Long): SourceRegistration {
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = clockDomainProvider.current()
		return database.withTransaction {
			val ownerScope = requireNotNull(database.sourceSessionDao().activeSession()) {
				"A source registration requires a durable active tracking session"
			}.logicalTrackingId
			val dao = database.sourceRegistrationStateDao()
			val current = dao.get(source.stableCode, ownerScope)
			val next = when {
				current == null -> SourceRegistrationStateEntity(
					sourceKind = source.stableCode,
					ownerScope = ownerScope,
					sourceInstanceId = UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = 0L,
					nextSequence = 0L,
					appliedRevision = appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = updatedAtMs,
				)
				current.clockDomainId == clockDomainId && current.collectedDataEpoch == lifecycle.epoch ->
					current.copy(
						registrationGeneration = current.registrationGeneration + 1L,
						appliedRevision = appliedRevision,
						updatedAtMs = updatedAtMs,
					)
				else -> current.copy(
					sourceInstanceId = UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = current.registrationGeneration + 1L,
					nextSequence = 0L,
					appliedRevision = appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = updatedAtMs,
				)
			}
			dao.replace(next)
			SourceRegistration(ownerScope, next)
		}
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
