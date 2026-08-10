package com.adsamcik.tracker.activity.api.registration

import android.content.Context
import android.provider.Settings
import androidx.room.withTransaction
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes and fences every physical GMS activity-recognition registration. */
@Singleton
class DefaultActivityRegistrationArbiter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val backend: GmsActivityRecognitionBackend,
) : ActivityRegistrationArbiter {
	private val mutex = Mutex()
	private val demands = mutableMapOf<ActivityRegistrationOwner, ActivityRegistrationDemand>()
	@Volatile private var current = EMPTY_SNAPSHOT
	private var deletionPaused = false

	override suspend fun setDemand(
		owner: ActivityRegistrationOwner,
		demand: ActivityRegistrationDemand,
	): ActivityRegistrationResult = mutex.withLock {
		if (demand.enabled) demands[owner] = demand else demands.remove(owner)
		reconcileLocked()
	}

	override suspend fun clearDemand(owner: ActivityRegistrationOwner): ActivityRegistrationResult = mutex.withLock {
		demands.remove(owner)
		reconcileLocked()
	}

	override suspend fun closeForCollectedDataDeletion(): ActivityRegistrationResult = mutex.withLock {
		deletionPaused = true
		// A session cannot survive full collected-data deletion. The application monitor remains a
		// desired owner and will be installed with a fresh identity after the deletion commits.
		demands.remove(ActivityRegistrationOwner.ACTIVE_SESSION)
		demands.remove(ActivityRegistrationOwner.LEGACY_REQUEST_MANAGER)
		fenceAndRemoveLocked(clearOwners = false)
	}

	override suspend fun resumeAfterCollectedDataDeletion(): ActivityRegistrationResult = mutex.withLock {
		deletionPaused = false
		reconcileLocked()
	}

	override fun snapshot(): ActivityRegistrationSnapshot = current

	private suspend fun reconcileLocked(): ActivityRegistrationResult {
		hydratePersistedIdentityIfNeeded()
		val combined = combineDemands()
		if (deletionPaused) return applied(current)
		if (!combined.enabled) return fenceAndRemoveLocked(clearOwners = true)
		if (!context.hasActivityPermission) {
			return failure(ActivityRegistrationStatus.BLOCKED, ActivityRegistrationFailureCode.PERMISSION_MISSING, false)
		}
		if (!backend.isAvailable) {
			return failure(ActivityRegistrationStatus.BLOCKED, ActivityRegistrationFailureCode.PROVIDER_UNAVAILABLE, true)
		}
		if (current.active && current.matches(combined)) {
			current = current.copy(owners = combined.owners)
			return applied(current)
		}

		val oldIdentity = current.identity
		val identity = try {
			reserveIdentity(combined.appliedRevision)
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			return failure(ActivityRegistrationStatus.FAILED, ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE, true)
		}
		val registered = backend.applyRegistration(
			RecognitionConfig(
				intervalSeconds = combined.intervalSeconds ?: 0,
				requestedTransitions = combined.transitions,
			),
			identity,
		)
		if (!registered) {
			oldIdentity?.let { runCatching { backend.removeRegistration(it) } }
			current = ActivityRegistrationSnapshot(false, identity, combined.owners, null, emptySet())
			return failure(ActivityRegistrationStatus.FAILED, ActivityRegistrationFailureCode.PROVIDER_REGISTRATION_FAILED, true)
		}

		current = ActivityRegistrationSnapshot(
			active = true,
			identity = identity,
			owners = combined.owners,
			continuousRecognitionIntervalSeconds = combined.intervalSeconds,
			transitions = combined.transitions,
		)
		if (oldIdentity != null && oldIdentity != identity) {
			val removed = runCatching { backend.removeRegistration(oldIdentity) }.isSuccess
			if (!removed) {
				return ActivityRegistrationResult(
					ActivityRegistrationStatus.DEGRADED,
					current,
					ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
					retryable = true,
				)
			}
		}
		return applied(current)
	}

	private suspend fun fenceAndRemoveLocked(clearOwners: Boolean): ActivityRegistrationResult {
		hydratePersistedIdentityIfNeeded()
		val oldIdentity = current.identity
		val fence = try {
			reserveIdentity(appliedRevision = null)
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			return failure(ActivityRegistrationStatus.FAILED, ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE, true)
		}
		current = ActivityRegistrationSnapshot(
			active = false,
			identity = fence,
			owners = if (clearOwners) emptySet() else demands.keys.toSet(),
			continuousRecognitionIntervalSeconds = null,
			transitions = emptySet(),
		)
		if (oldIdentity == null) return applied(current)
		return if (runCatching { backend.removeRegistration(oldIdentity) }.isSuccess) {
			applied(current)
		} else {
			ActivityRegistrationResult(
				ActivityRegistrationStatus.DEGRADED,
				current,
				ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
				retryable = true,
			)
		}
	}

	private suspend fun reserveIdentity(appliedRevision: Long?): ActivityRegistrationIdentity {
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = currentClockDomain()
		return database.withTransaction {
			val dao = database.sourceRegistrationStateDao()
			val existing = dao.get(ACTIVITY_SOURCE_KIND, OWNER_SCOPE)
			val next = if (existing == null) {
				SourceRegistrationStateEntity(
					sourceKind = ACTIVITY_SOURCE_KIND,
					ownerScope = OWNER_SCOPE,
					sourceInstanceId = UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = 0L,
					nextSequence = 0L,
					appliedRevision = appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = System.currentTimeMillis(),
				)
			} else {
				existing.copy(
					sourceInstanceId = if (
						existing.clockDomainId == clockDomainId && existing.collectedDataEpoch == lifecycle.epoch
					) existing.sourceInstanceId else UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = existing.registrationGeneration + 1L,
					nextSequence = if (
						existing.clockDomainId == clockDomainId && existing.collectedDataEpoch == lifecycle.epoch
					) existing.nextSequence else 0L,
					appliedRevision = appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = System.currentTimeMillis(),
				)
			}
			dao.replace(next)
			ActivityRegistrationIdentity(
				next.sourceInstanceId,
				next.registrationGeneration,
				next.collectedDataEpoch,
				next.appliedRevision,
			)
		}
	}

	private suspend fun hydratePersistedIdentityIfNeeded() {
		if (current.identity != null) return
		val persisted = database.sourceRegistrationStateDao().get(ACTIVITY_SOURCE_KIND, OWNER_SCOPE) ?: return
		current = current.copy(
			identity = ActivityRegistrationIdentity(
				persisted.sourceInstanceId,
				persisted.registrationGeneration,
				persisted.collectedDataEpoch,
				persisted.appliedRevision,
			),
		)
	}

	private fun combineDemands(): CombinedDemand {
		val active = demands.filterValues(ActivityRegistrationDemand::enabled)
		return CombinedDemand(
			owners = active.keys,
			intervalSeconds = active.values.mapNotNull { it.continuousRecognitionIntervalSeconds }.minOrNull(),
			transitions = active.values.flatMap { it.transitions }.toSet(),
			appliedRevision = active.values.mapNotNull { it.planRevision }.maxOrNull(),
		)
	}

	private fun currentClockDomain(): String {
		val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
		if (bootCount >= 0) return "android-boot-count:$bootCount"
		val estimatedBootMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
		return "android-boot-epoch-hour:${estimatedBootMs / 3_600_000L}"
	}

	private fun ActivityRegistrationSnapshot.matches(demand: CombinedDemand): Boolean =
		continuousRecognitionIntervalSeconds == demand.intervalSeconds && transitions == demand.transitions

	private fun applied(snapshot: ActivityRegistrationSnapshot) =
		ActivityRegistrationResult(ActivityRegistrationStatus.APPLIED, snapshot)

	private fun failure(
		status: ActivityRegistrationStatus,
		code: ActivityRegistrationFailureCode,
		retryable: Boolean,
	) = ActivityRegistrationResult(status, current, code, retryable)

	private data class CombinedDemand(
		val owners: Set<ActivityRegistrationOwner>,
		val intervalSeconds: Int?,
		val transitions: Set<com.adsamcik.tracker.activity.ActivityTransitionData>,
		val appliedRevision: Long?,
	) {
		val enabled: Boolean get() = intervalSeconds != null || transitions.isNotEmpty()
	}

	private companion object {
		const val ACTIVITY_SOURCE_KIND = 2
		const val OWNER_SCOPE = "activity-registration-arbiter"
		val EMPTY_SNAPSHOT = ActivityRegistrationSnapshot(false, null, emptySet(), null, emptySet())
	}
}
