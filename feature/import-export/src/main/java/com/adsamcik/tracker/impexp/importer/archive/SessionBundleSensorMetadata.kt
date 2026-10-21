package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.file.PortableActivityFileImport
import com.adsamcik.tracker.impexp.importer.file.PortablePressureFileReceipt
import com.adsamcik.tracker.impexp.portable.AllDaySensorSourceFile
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.impexp.portable.SessionBundleChildV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import kotlinx.serialization.json.jsonPrimitive

internal fun SessionBundleChildV1.requireFormatVersion(actual: Int) {
	if (formatVersion != actual) {
		throw InvalidSessionBundle("Session source version does not match its data")
	}
}

internal suspend fun prepareActivityClaims(
    context: Context, child: StagedSessionChild, input: FileImportStream, declared: SessionBundleChildV1,
    capture: (HistorySource, String, String, String) -> Unit, add: (SessionBundleSourceClaim) -> Unit,
) {
    AllDaySensorSourceFile.open(input, "ACTIVITY", context).use { data ->
        declared.requireFormatVersion(data.schemaVersion)
        data.sessionInput?.let { PortableActivityJsonV1Codec().decode(it) }?.entries.orEmpty().forEach { entry ->
            capture(HistorySource.ACTIVITY, entry.identity.value, entry.contentChecksum.value,
                PortableActivityFileImport.subordinateEntryKey(child.receiptKey, entry.identity.value))
        }
        val identities = linkedSetOf<String>()
        data.forEachObservation { identities += it.getValue("id").jsonPrimitive.content }
        if (identities.isNotEmpty()) {
            add(SessionBundleSourceClaim.SensorObservations(HistorySource.ACTIVITY, identities.toList()))
        }
    }
}

internal suspend fun preparePressureClaims(
    context: Context, child: StagedSessionChild, input: FileImportStream, declared: SessionBundleChildV1,
    capture: (HistorySource, String, String, String) -> Unit, add: (SessionBundleSourceClaim) -> Unit,
) {
    AllDaySensorSourceFile.open(input, "PRESSURE", context).use { data ->
        declared.requireFormatVersion(data.schemaVersion)
        data.sessionInput?.let { sessionInput ->
            PortablePressureJsonV1Codec().decode(sessionInput) { entry ->
                capture(HistorySource.PRESSURE, entry.identity.value, entry.contentChecksum.value,
                    PortablePressureFileReceipt.entryKey(child.receiptKey, entry.identity.value))
            }
        }
        val identities = linkedSetOf<String>()
        data.forEachObservation { identities += it.getValue("id").jsonPrimitive.content }
        if (identities.isNotEmpty()) {
            add(SessionBundleSourceClaim.SensorObservations(HistorySource.PRESSURE, identities.toList()))
        }
    }
}

internal suspend fun resolveSensorObservations(database: AppDatabase,
    claim: SessionBundleSourceClaim.SensorObservations): List<OrganizationSourceSelector> {
    val identities = claim.identities.toSet()
    val found = if (claim.source == HistorySource.ACTIVITY) {
        claim.identities.chunked(SENSOR_CLAIM_PAGE_SIZE)
            .flatMap { database.activitySnapshotDao().bySourceSignalIds(it) }.mapNotNull { it.sourceSignalId }.toSet()
    } else {
        claim.identities.chunked(SENSOR_CLAIM_PAGE_SIZE)
            .flatMap { database.pressureSampleDao().bySourceSignalIds(it) }.mapNotNull { it.sourceSignalId }.toSet()
    }
    if (found != identities) {
        throw InvalidSessionBundle("Session observations are incomplete")
    }
    return listOf(OrganizationSourceSelector.SensorObservations(claim.source, claim.identities))
}

private const val SENSOR_CLAIM_PAGE_SIZE = 500
