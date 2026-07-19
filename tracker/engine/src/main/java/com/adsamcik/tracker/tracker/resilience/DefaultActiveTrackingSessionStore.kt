package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private object ActiveTrackingSessionSerializer : Serializer<ActiveTrackingSessionProto> {
	override val defaultValue: ActiveTrackingSessionProto =
		ActiveTrackingSessionProto.getDefaultInstance()

	override suspend fun readFrom(input: InputStream): ActiveTrackingSessionProto = try {
		ActiveTrackingSessionProto.parseFrom(input)
	} catch (exception: Exception) {
		Log.w(TAG, "Unable to read active tracking session; using empty state", exception)
		defaultValue
	}

	override suspend fun writeTo(
		t: ActiveTrackingSessionProto,
		output: OutputStream,
	) {
		t.writeTo(output)
	}

	private const val TAG = "ActiveTrackingSession"
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
			ActiveTrackingSessionStoreResult.Success(
				context.activeTrackingSessionDataStore.data.first().toDescriptor(),
			)
		}
	}

	override suspend fun save(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			context.activeTrackingSessionDataStore.updateData {
				ActiveTrackingSessionProto.newBuilder()
					.setActive(true)
					.setUserInitiated(descriptor.isUserInitiated)
					.setAmbient(descriptor.isAmbient)
					.setPolicyTier(descriptor.policyTier.name)
					.build()
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
	return ActiveTrackingSessionDescriptor(
		isUserInitiated = userInitiated,
		isAmbient = ambient,
		policyTier = tier,
	)
}
