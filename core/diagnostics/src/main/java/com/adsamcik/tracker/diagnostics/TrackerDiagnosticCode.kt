package com.adsamcik.tracker.diagnostics

/**
 * Stable, payload-free diagnostic identifiers emitted by Tracker.
 *
 * Values are part of the diagnostic wire contract. Never renumber or reuse them.
 */
enum class TrackerDiagnosticCode(
    val wireCode: UInt,
    val kind: TrackerDiagnosticKind,
) {
    NAVIGATION_DESTINATION_REJECTED(0x5452_0100u, TrackerDiagnosticKind.HANDLED_ERROR),
    OSM_IMPORT_FAILED(0x5452_0101u, TrackerDiagnosticKind.HANDLED_ERROR),
    OSM_IMPORT_WARNING(0x5452_0102u, TrackerDiagnosticKind.BREADCRUMB),
    TRACKING_START_FAILED(0x5452_0103u, TrackerDiagnosticKind.HANDLED_ERROR),
    TRACKING_SESSION_RECOVERY_FAILED(0x5452_0104u, TrackerDiagnosticKind.HANDLED_ERROR),
    TRACKING_CHECKPOINT_FAILED(0x5452_0105u, TrackerDiagnosticKind.HANDLED_ERROR),
    PERSISTENCE_COMMIT_INCONSISTENT(0x5452_0106u, TrackerDiagnosticKind.HANDLED_ERROR),
    TRACKING_PROCESSOR_DISABLED(0x5452_0107u, TrackerDiagnosticKind.HANDLED_ERROR),
    TRACKING_SHUTDOWN_DEGRADED(0x5452_0108u, TrackerDiagnosticKind.HANDLED_ERROR),
    ACTIVITY_RECOGNITION_FAILED(0x5452_0109u, TrackerDiagnosticKind.HANDLED_ERROR),
    STEP_COUNTER_REGRESSION(0x5452_010Au, TrackerDiagnosticKind.HANDLED_ERROR),
    LIVE_STATS_CORRUPTED(0x5452_010Bu, TrackerDiagnosticKind.HANDLED_ERROR),
    RETENTION_FAILED(0x5452_010Cu, TrackerDiagnosticKind.HANDLED_ERROR),
    APP_INITIALIZATION_FAILED(0x5452_010Du, TrackerDiagnosticKind.HANDLED_ERROR),
    PERSISTENCE_WRITE_FAILED(0x5452_010Eu, TrackerDiagnosticKind.HANDLED_ERROR),
    TRACKING_PIPELINE_STAGE_FAILED(0x5452_010Fu, TrackerDiagnosticKind.HANDLED_ERROR),
    TRACKING_CYCLE_FAILED(0x5452_0110u, TrackerDiagnosticKind.HANDLED_ERROR),
    TRACKING_SESSION_STORE_FAILED(0x5452_0111u, TrackerDiagnosticKind.HANDLED_ERROR),
    DOMAIN_EVENT_PERSIST_FAILED(0x5452_0112u, TrackerDiagnosticKind.HANDLED_ERROR),
}

enum class TrackerDiagnosticKind {
    BREADCRUMB,
    HANDLED_ERROR,
}
