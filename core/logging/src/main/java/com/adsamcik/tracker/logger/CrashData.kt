package com.adsamcik.tracker.logger

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Crash data entity for storing application crashes
 */
@Entity(tableName = "crash_data")
data class CrashData(
    val timeStamp: Long = System.currentTimeMillis(),
    val exceptionName: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val cause: String? = null,
    val threadName: String,
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val deviceManufacturer: String,
    val availableMemory: Long,
    val totalMemory: Long,
    val batteryLevel: Float,
    val isCharging: Boolean,
    val networkType: String,
    val isInBackground: Boolean = false
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L
}
