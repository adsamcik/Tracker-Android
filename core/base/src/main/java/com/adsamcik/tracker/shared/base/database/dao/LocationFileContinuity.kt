package com.adsamcik.tracker.shared.base.database.dao

import java.security.MessageDigest

/** Descriptive file paths are separate from native registration and clock identity. */
object LocationFileContinuity {
    private const val IMPORTED_PREFIX = "imported-location:"

    /** Only read projections use this namespace; imported samples keep their elapsed clock unknown. */
    fun isImported(continuityId: String?): Boolean = continuityId?.startsWith(IMPORTED_PREFIX) == true

    /** Recover the unchanged opaque file ID on re-export, without generating a new path for each file. */
    fun filePathId(continuityId: String, clockDomainId: String?): String =
        if (isImported(continuityId)) {
            continuityId.removePrefix(IMPORTED_PREFIX)
        } else {
            val bytes = (continuityId + "\u0000" + clockDomainId).toByteArray(Charsets.UTF_8)
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }

    /** A current owner correction may sit between untouched native neighbours from the same path. */
    fun samePath(beforeId: String?, beforeClock: String?, afterId: String?, afterClock: String?): Boolean =
        beforeId == afterId || (beforeId != null && afterId != null &&
            (isImported(beforeId) || isImported(afterId)) &&
            filePathId(beforeId, beforeClock) == filePathId(afterId, afterClock))

    const val IMPORTED_ID = """(SELECT '${IMPORTED_PREFIX}' || i.path_id FROM location_file_identity i
        WHERE i.sample_id = location_sample.id AND i.imported = 1
        AND i.path_id IS NOT NULL AND i.path_sequence >= 0)"""
    const val IMPORTED_SEQUENCE = """(SELECT i.path_sequence FROM location_file_identity i
        WHERE i.sample_id = location_sample.id AND i.imported = 1
        AND i.path_id IS NOT NULL AND i.path_sequence >= 0)"""
    private const val CURRENT_CORRECTION = """EXISTS (SELECT 1 FROM location_point_correction correction
        JOIN source_evidence_state state ON state.id = 1
        AND (state.collected_data_epoch = correction.collected_data_epoch
        OR instr(',' || state.restored_data_epochs || ',', ',' || correction.collected_data_epoch || ',') > 0)
        JOIN location_file_identity corrected ON corrected.sample_id = correction.sample_id AND corrected.imported = 1
        WHERE correction.sample_id = location_sample.id)"""
    const val SAVED_ID = """CASE WHEN lr.sample_id IS NULL OR ${CURRENT_CORRECTION} THEN ${IMPORTED_ID}
        WHEN location_sample.clock_domain_id IS NULL THEN NULL
        ELSE lr.source_instance_id || ':' || lr.registration_generation END"""
    const val SAVED_SEQUENCE = """CASE WHEN lr.sample_id IS NULL OR ${CURRENT_CORRECTION} THEN ${IMPORTED_SEQUENCE}
        ELSE lr.source_sequence END"""
}
