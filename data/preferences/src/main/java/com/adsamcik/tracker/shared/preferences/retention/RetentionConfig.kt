package com.adsamcik.tracker.shared.preferences.retention

data class RetentionConfigState(
    val rawDataRetentionDays: Int = DEFAULT_RAW_DAYS,
    val wifiCellRetentionDays: Int = DEFAULT_RAW_DAYS,
    val tripRetentionDays: Int = DEFAULT_RAW_DAYS,
    val dailySummaryRetentionDays: Int = DEFAULT_DAILY_SUMMARY_DAYS,
    val explorationRetentionDays: Int = 0,
    val autoPurgeEnabled: Boolean = false,
    val exportBeforePurge: Boolean = false,
    val legacySessionRetentionDays: Int = DEFAULT_RAW_DAYS,
    val autoCleanupEnabled: Boolean = false,
    val dataRetentionYears: Int = DEFAULT_RETENTION_YEARS,
) {
    companion object {
        const val DEFAULT_RAW_DAYS = 365
        const val DEFAULT_DAILY_SUMMARY_DAYS = 730
        const val DEFAULT_RETENTION_YEARS = 1
    }
}
