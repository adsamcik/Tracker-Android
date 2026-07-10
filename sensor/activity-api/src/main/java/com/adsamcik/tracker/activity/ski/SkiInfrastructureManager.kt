package com.adsamcik.tracker.activity.ski

import android.net.Uri
import com.adsamcik.tracker.stats.api.ski.SkiLift

/**
 * Contract for the optional, user-imported ski infrastructure database.
 */
interface SkiInfrastructureManager {
    fun isAvailable(): Boolean

    suspend fun importDatabase(uri: Uri): SkiInfrastructureImportResult

    fun clearDatabase()

    fun getMetadata(key: String): String?

    fun findLiftsNearby(lat: Double, lon: Double, radiusDeg: Double): List<SkiLift>
}
