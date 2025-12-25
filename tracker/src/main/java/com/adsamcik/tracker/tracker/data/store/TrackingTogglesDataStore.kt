package com.adsamcik.tracker.tracker.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.tracker.proto.TrackingTogglesProto
import java.io.InputStream
import java.io.OutputStream

/**
 * Serializer for [TrackingTogglesProto] used by DataStore.
 */
internal object TrackingTogglesSerializer : Serializer<TrackingTogglesProto> {
    override val defaultValue: TrackingTogglesProto = TrackingTogglesProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): TrackingTogglesProto = try {
        TrackingTogglesProto.parseFrom(input)
    } catch (e: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: TrackingTogglesProto, output: OutputStream) {
        t.writeTo(output)
    }
}

/**
 * DataStore extension for tracking toggles.
 * Each toggle is a boolean preference keyed by its string resource name.
 */
internal val Context.trackingTogglesProtoDataStore: DataStore<TrackingTogglesProto> by dataStore(
    fileName = "tracking_toggles.pb",
    serializer = TrackingTogglesSerializer
)
