package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.stats.api.PolicyTier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private object ActiveTrackingSessionSerializer : Serializer<ActiveTrackingSessionProto> {
	override val defaultValue: ActiveTrackingSessionProto =
		ActiveTrackingSessionProto.getDefaultInstance()

	override suspend fun readFrom(input: InputStream): ActiveTrackingSessionProto = try {
		ActiveTrackingSessionProto.parseFrom(input)
	} catch (exception: Exception) {
		defaultValue
	}

	override suspend fun writeTo(
		t: ActiveTrackingSessionProto,
		output: OutputStream,
	) {
		t.writeTo(output)
	}

}

private val Context.activeTrackingSessionDataStore: DataStore<ActiveTrackingSessionProto> by dataStore(
	fileName = "active_tracking_session.pb",
	serializer = ActiveTrackingSessionSerializer,
)

@Singleton
class DefaultActiveTrackingSessionStore @Inject constructor(
	@ApplicationContext private val context: Context,
	private val dispatchers: DispatchersProvider,
) : ActiveTrackingSessionStore {

	override suspend fun read(): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			// Normalize legacy descriptors as part of the read.  Older installs have no logical or
			// service-run IDs; assigning them once here prevents every subsequent read from inventing
			// a different correlation identity.
			val stored = context.activeTrackingSessionDataStore.updateData { current ->
				current.normalizedForCurrentContract()
			}
			ActiveTrackingSessionStoreResult.Success(
				stored.toDescriptor(),
			)
		}
	}

	override suspend fun save(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			context.activeTrackingSessionDataStore.updateData {
				descriptor.toProto()
			}
			ActiveTrackingSessionStoreResult.Success(descriptor)
		}
	}

	override suspend fun clear(): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			context.activeTrackingSessionDataStore.updateData {
				ActiveTrackingSessionProto.getDefaultInstance()
			}
			ActiveTrackingSessionStoreResult.Success(null)
		}
	}

	override suspend fun clearIfCurrent(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			var remaining: ActiveTrackingSessionProto? = null
			context.activeTrackingSessionDataStore.updateData { current ->
				val currentDescriptor = current.toDescriptor()
				if (
					currentDescriptor?.logicalTrackingId == descriptor.logicalTrackingId &&
					currentDescriptor.serviceRunId == descriptor.serviceRunId &&
					currentDescriptor.lifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE &&
					currentDescriptor.lifecycleRevision == descriptor.lifecycleRevision
				) {
					ActiveTrackingSessionProto.getDefaultInstance().also { remaining = it }
				} else {
					remaining = current
					current
				}
			}
			ActiveTrackingSessionStoreResult.Success(remaining?.toDescriptor())
		}
	}

	private suspend inline fun runStoreOperation(
		operation: suspend () -> ActiveTrackingSessionStoreResult,
	): ActiveTrackingSessionStoreResult = try {
		operation()
	} catch (exception: CancellationException) {
		throw exception
	} catch (exception: Exception) {
		ActiveTrackingSessionStoreResult.Failure(exception)
	}
}

private fun ActiveTrackingSessionProto.toDescriptor(): ActiveTrackingSessionDescriptor? {
	if (!active) return null
	val tier = PolicyTier.entries.firstOrNull { it.name == policyTier } ?: return null
	if (tier == PolicyTier.OFF) return null
	val persistedLifecycleState = lifecycleState.toLifecycleState()
	val stopCandidate = if (persistedLifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE) {
		TrackingStopCandidate(
			reason = stopCandidateReason.toStopCandidateReason(),
			requestedAtEpochMs = stopCandidateRequestedAtEpochMs.takeIf { it > 0L },
		)
	} else {
		null
	}
	return ActiveTrackingSessionDescriptor(
		isUserInitiated = userInitiated,
		isAmbient = ambient,
		policyTier = tier,
		logicalTrackingId = logicalTrackingId.ifBlank { newDescriptorCorrelationId() },
		serviceRunId = serviceRunId.ifBlank { newDescriptorCorrelationId() },
		lifecycleState = persistedLifecycleState,
		lifecycleRevision = lifecycleRevision,
		lifecycleChangedAtEpochMs = lifecycleChangedAtEpochMs.takeIf { it > 0L },
		stopCandidate = stopCandidate,
		sessionSegmentId = sessionSegmentId.takeIf { it > 0L },
	)
}

private fun ActiveTrackingSessionProto.normalizedForCurrentContract(): ActiveTrackingSessionProto {
	val descriptor = toDescriptor() ?: return this
	return descriptor.toProto()
}

private fun ActiveTrackingSessionDescriptor.toProto(): ActiveTrackingSessionProto =
	ActiveTrackingSessionProto.newBuilder()
		.setActive(true)
		.setUserInitiated(isUserInitiated)
		.setAmbient(isAmbient)
		.setPolicyTier(policyTier.name)
		.setLogicalTrackingId(logicalTrackingId)
		.setServiceRunId(serviceRunId)
		.setLifecycleState(lifecycleState.name)
		.setLifecycleRevision(lifecycleRevision)
		.setLifecycleChangedAtEpochMs(lifecycleChangedAtEpochMs ?: 0L)
		.setStopCandidateReason(stopCandidate?.reason?.name.orEmpty())
		.setStopCandidateRequestedAtEpochMs(stopCandidate?.requestedAtEpochMs ?: 0L)
		.setSessionSegmentId(sessionSegmentId ?: 0L)
		.build()

/**
 * Old proto records did not carry correlation IDs.  Keep generation here (rather than deriving
 * IDs from user flags/tier) so two independent legacy sessions can never be accidentally joined.
 */
private fun newDescriptorCorrelationId(): String = java.util.UUID.randomUUID().toString()

private fun String.toLifecycleState(): LogicalTrackingLifecycleState = when {
	isBlank() -> LogicalTrackingLifecycleState.ACTIVE // legacy v1 descriptor
	else -> LogicalTrackingLifecycleState.entries.firstOrNull { it.name == this }
		// An unrecognized future state must fail closed: never restart a session whose lifecycle
		// this version cannot interpret.
		?: LogicalTrackingLifecycleState.STOP_CANDIDATE
}

private fun String.toStopCandidateReason(): TrackingStopCandidateReason =
	TrackingStopCandidateReason.entries.firstOrNull { it.name == this }
		?: TrackingStopCandidateReason.UNKNOWN
