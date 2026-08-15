package com.adsamcik.tracker.diagnostics

/**
 * Tracker-owned Tracebox templates.
 *
 * Every value is compile-time static and payload-free. Runtime data must be supplied separately
 * with an explicit Tracebox privacy classification.
 */
@Suppress("MaxLineLength")
object TrackerTraceboxTemplates {
	const val ACTIVITY_CALLBACK_HANDOFF_INCOMPLETE =
		"Activity callback durable handoff was not completed"
	const val ACTIVITY_CALLBACK_HANDOFF_FAILED = "Activity callback durable handoff failed"
	const val ACTIVITY_RECOGNITION_UNAVAILABLE = "Activity recognition is unavailable"
	const val ACTIVITY_RECOGNITION_FAILED = "Activity recognition failed"
	const val ACTIVITY_SOURCE_RECOVERY_DEFERRED = "Activity source projection recovery was deferred"
	const val ACTIVITY_SOURCE_RECOVERY_FAILED = "Activity source projection recovery failed"
	const val APPLICATION_INITIALIZATION_FAILED = "Application initialization failed"
	const val APPLICATION_PROCESS_STARTED = "Application process started"
	const val APPLICATION_STARTUP_RECONCILED = "Application startup reconciliation completed"
	const val DATA_RETENTION_FAILED = "Data retention failed"
	const val DOMAIN_EVENT_PERSISTENCE_FAILED = "Domain event persistence failed"
	const val DURABLE_SOURCE_RECOVERY_DEFERRED = "Durable source projection recovery was deferred"
	const val DURABLE_SOURCE_RECOVERY_FAILED = "Durable source projection recovery failed"
	const val LEGACY_DATABASE_IMPORT_FAILED = "Legacy database import failed"
	const val MAPLIBRE_HTTP_FACTORY_INSTALLATION_FAILED =
		"MapLibre gateway HTTP factory installation failed; online networking remains disabled ({})"
	const val MAPLIBRE_SDK_INITIALIZATION_FAILED = "MapLibre SDK initialization failed"
	const val NAVIGATION_DESTINATION_REJECTED = "Navigation destination was rejected"
	const val PERSISTENCE_COMMIT_INCONSISTENT = "Persistence commit became inconsistent"
	const val PROCESS_TRACKING_CYCLE = "Process tracking cycle"
	const val RAW_LOCATION_REPAIR_COMPLETED =
		"Canonical raw location observations repaired: count {}"
	const val RAW_LOCATION_REPAIR_DECODE_FAILED =
		"Unable to decode a location source event during raw repair"
	const val SOURCE_OUTBOX_DRAIN_BOUNDED = "Source outbox drain reached its safety bound"
	const val STARTUP_SOURCE_RECOVERY_DEFERRED = "Startup source projection recovery was deferred"
	const val STEP_COUNTER_REGRESSED = "Step counter regressed"
	const val TRACKER_MODULE_INITIALIZATION_FAILED = "Tracker module initialization failed"
	const val TRACKING_COORDINATOR_SESSION_COUNTS =
		"Tracking coordinator session counts: projection drains {}, projected events {}, plan revisions {}, frames {}, motion policy changes {}, stationary optimizations {}, fidelity restores {}"
	const val TRACKING_COORDINATOR_SESSION_TIMINGS =
		"Tracking coordinator session timings: projection duration ns {}, wake lock ns {}"
	const val TRACKING_CYCLE_FAILED = "Tracking cycle failed"
	const val TRACKING_PERSISTENCE_WRITE_FAILED = "Tracking persistence write failed"
	const val TRACKING_PIPELINE_STAGE_FAILED = "Tracking pipeline stage failed"
	const val TRACKING_PROCESSOR_DISABLED = "Tracking processor was disabled after repeated failures"
	const val TRACKING_SESSION_RECOVERY_FAILED = "Tracking session recovery failed"
	const val TRACKING_SESSION_STARTED = "Tracking session started"
	const val TRACKING_SESSION_START_REQUESTED = "Tracking session start requested"
	const val TRACKING_SESSION_STOPPED = "Tracking session stopped"
	const val TRACKING_SESSION_STORE_FAILED = "Tracking session store failed"
	const val TRACKING_SHUTDOWN_DEGRADED = "Tracking shutdown was degraded"
	const val TRACKING_SIGNAL_CHECKPOINT_FAILED = "Tracking signal checkpoint failed"
	const val TRACKING_SOURCE_SESSION_START_REJECTED = "Tracking source session start was rejected"
	const val TRACKING_START_FAILED = "Tracking start failed"
	const val TRACKING_START_STORAGE_UNAVAILABLE = "Tracking start failed: storage unavailable"
	const val TRACKING_STOP_REQUESTED = "Tracking stop requested: reason {}"
}
