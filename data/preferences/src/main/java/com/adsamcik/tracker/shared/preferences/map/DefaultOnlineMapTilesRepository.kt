package com.adsamcik.tracker.shared.preferences.map

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

private object OnlineMapTilesSerializer : Serializer<OnlineMapTilesProto> {
	override val defaultValue: OnlineMapTilesProto = OnlineMapTilesProto.getDefaultInstance()

	override suspend fun readFrom(input: InputStream): OnlineMapTilesProto = try {
		OnlineMapTilesProto.parseFrom(input)
	} catch (_: Exception) {
		defaultValue
	}

	override suspend fun writeTo(t: OnlineMapTilesProto, output: OutputStream) {
		t.writeTo(output)
	}
}

private val Context.onlineMapTilesDataStore: DataStore<OnlineMapTilesProto> by dataStore(
	fileName = "online_map_tiles.pb",
	serializer = OnlineMapTilesSerializer,
)

/**
 * DataStore-backed implementation of [OnlineMapTilesRepository].
 *
 * No legacy migration is performed: online tiles are a new feature in v10 and
 * have never had a SharedPreferences key.
 */
class DefaultOnlineMapTilesRepository(
	private val context: Context,
	private val io: CoroutineDispatcher,
) : OnlineMapTilesRepository {

	override val data: Flow<OnlineMapTilesState> =
		context.onlineMapTilesDataStore.data.map { it.toDomain() }

	override suspend fun setEnabled(enabled: Boolean) {
		withContext(io) {
			context.onlineMapTilesDataStore.updateData { current ->
				current.toBuilder().setEnabled(enabled).build()
			}
		}
	}

	override suspend fun setProviderId(providerId: String) {
		withContext(io) {
			context.onlineMapTilesDataStore.updateData { current ->
				current.toBuilder().setProviderId(providerId).build()
			}
		}
	}

	override suspend fun setCustomUrl(customUrl: String) {
		withContext(io) {
			context.onlineMapTilesDataStore.updateData { current ->
				current.toBuilder().setCustomUrl(customUrl).build()
			}
		}
	}
}

internal fun resetOnlineMapTilesForTests() {
	try {
		val fileClassName =
			"com.adsamcik.tracker.shared.preferences.map.DefaultOnlineMapTilesRepositoryKt"
		val delegateFieldName = "onlineMapTilesDataStore\$delegate"
		val fileClass = Class.forName(fileClassName)
		val delegateField = fileClass.getDeclaredField(delegateFieldName).apply { isAccessible = true }
		val delegate = delegateField.get(null)
		val instanceField = delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
		instanceField.set(delegate, null)
	} catch (_: Exception) {
		// no-op when not initialized yet
	}
}

private fun OnlineMapTilesProto.toDomain(): OnlineMapTilesState {
	val resolvedId = providerId.ifEmpty { DEFAULT_ONLINE_TILE_PROVIDER_ID }
	return OnlineMapTilesState(
		enabled = enabled,
		providerId = resolvedId,
		customUrl = customUrl,
	)
}
