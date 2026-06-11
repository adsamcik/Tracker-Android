package com.adsamcik.tracker.tracker.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.tracker.proto.PermissionHintsProto
import java.io.InputStream
import java.io.OutputStream

/**
 * Serializer for [PermissionHintsProto] used by DataStore.
 */
internal object PermissionHintsSerializer : Serializer<PermissionHintsProto> {
    override val defaultValue: PermissionHintsProto = PermissionHintsProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): PermissionHintsProto = try {
        PermissionHintsProto.parseFrom(input)
    } catch (e: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: PermissionHintsProto, output: OutputStream) {
        t.writeTo(output)
    }
}

/**
 * DataStore extension for permission hint tracking (wifi permission prompts, etc.).
 */
internal val Context.permissionHintsProtoDataStore: DataStore<PermissionHintsProto> by dataStore(
    fileName = "permission_hints.pb",
    serializer = PermissionHintsSerializer
)
