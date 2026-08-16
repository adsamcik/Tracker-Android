package com.adsamcik.tracker.diagnostics

import dev.tracebox.api.LogTemplate

/**
 * Tracker-owned Tracebox templates.
 *
 * Every value is immutable developer-authored text and payload-free. Runtime data must be supplied
 * separately with an explicit Tracebox privacy classification.
 */
@Suppress("MaxLineLength")
object TrackerTraceboxTemplates {
	val ACTIVITY_CALLBACK_HANDOFF_INCOMPLETE =
		LogTemplate.of("Activity callback durable handoff was not completed")
	val ACTIVITY_CALLBACK_HANDOFF_FAILED =
		LogTemplate.of("Activity callback durable handoff failed")
	val ACTIVITY_RECOGNITION_UNAVAILABLE =
		LogTemplate.of("Activity recognition is unavailable")
	val ACTIVITY_RECOGNITION_FAILED = LogTemplate.of("Activity recognition failed")
	val ACTIVITY_SOURCE_RECOVERY_DEFERRED =
		LogTemplate.of("Activity source projection recovery was deferred")
	val ACTIVITY_SOURCE_RECOVERY_FAILED =
		LogTemplate.of("Activity source projection recovery failed")
	val APPLICATION_INITIALIZATION_FAILED = LogTemplate.of("Application initialization failed")
	val APPLICATION_PROCESS_STARTED = LogTemplate.of("Application process started")
	val APPLICATION_STARTUP_MEASUREMENT = LogTemplate.of(
		"Application startup measurement: process elapsed ms {}, process CPU ms {}",
	)
	val APPLICATION_BATTERY_POWER_MEASUREMENT = LogTemplate.of(
		"Application battery and power measurement: foreground {}, foreground entries {}, battery service available {}, battery percent available {}, battery percent {}, charging {}, charge available {}, charge uAh {}, energy available {}, energy nWh {}, interactive {}, device idle {}, power save {}",
	)
	val APPLICATION_MEMORY_MEASUREMENT = LogTemplate.of(
		"Application memory measurement: foreground {}, foreground entries {}, process PSS KiB {}, Java heap used KiB {}, native heap allocated KiB {}, trim level {}",
	)
	val APPLICATION_RESOURCE_MEASUREMENT_FAILED =
		LogTemplate.of("Application resource measurement failed")
	val APPLICATION_STARTUP_RECONCILED =
		LogTemplate.of("Application startup reconciliation completed")
	val DATA_RETENTION_FAILED = LogTemplate.of("Data retention failed")
	val DOMAIN_EVENT_PERSISTENCE_FAILED = LogTemplate.of("Domain event persistence failed")
	val DURABLE_SOURCE_RECOVERY_DEFERRED =
		LogTemplate.of("Durable source projection recovery was deferred")
	val DURABLE_SOURCE_RECOVERY_FAILED =
		LogTemplate.of("Durable source projection recovery failed")
	val LEGACY_DATABASE_IMPORT_FAILED = LogTemplate.of("Legacy database import failed")
	val MAPLIBRE_HTTP_FACTORY_INSTALLATION_FAILED = LogTemplate.of(
		"MapLibre gateway HTTP factory installation failed; online networking remains disabled ({})",
	)
	val MAPLIBRE_SDK_INITIALIZATION_FAILED = LogTemplate.of("MapLibre SDK initialization failed")
	val NAVIGATION_DESTINATION_REJECTED = LogTemplate.of("Navigation destination was rejected")
	val PERSISTENCE_COMMIT_INCONSISTENT = LogTemplate.of("Persistence commit became inconsistent")
	val PROCESS_TRACKING_CYCLE = LogTemplate.of("Process tracking cycle")
	val RAW_LOCATION_REPAIR_COMPLETED =
		LogTemplate.of("Canonical raw location observations repaired: count {}")
	val RAW_LOCATION_REPAIR_DECODE_FAILED =
		LogTemplate.of("Unable to decode a location source event during raw repair")
	val SOURCE_OUTBOX_DRAIN_BOUNDED = LogTemplate.of("Source outbox drain reached its safety bound")
	val STARTUP_SOURCE_RECOVERY_DEFERRED =
		LogTemplate.of("Startup source projection recovery was deferred")
	val STEP_COUNTER_REGRESSED = LogTemplate.of("Step counter regressed")
	val TRACKER_MODULE_INITIALIZATION_FAILED = LogTemplate.of("Tracker module initialization failed")
	val TRACKING_COORDINATOR_SESSION_COUNTS = LogTemplate.of(
		"Tracking coordinator session counts: projection drains {}, projected events {}, plan revisions {}, frames {}, source timer wakeups {}, source requests served {}, motion policy changes {}, stationary optimizations {}, fidelity restores {}",
	)
	val TRACKING_COORDINATOR_SESSION_TIMINGS = LogTemplate.of(
		"Tracking coordinator session timings: projection duration ns {}, wake lock ns {}",
	)
	val TRACKING_CYCLE_FAILED = LogTemplate.of("Tracking cycle failed")
	val TRACKING_PERSISTENCE_WRITE_FAILED = LogTemplate.of("Tracking persistence write failed")
	val TRACKING_PIPELINE_STAGE_FAILED = LogTemplate.of("Tracking pipeline stage failed")
	val TRACKING_PROCESSOR_DISABLED =
		LogTemplate.of("Tracking processor was disabled after repeated failures")
	val TRACKING_SESSION_RECOVERY_FAILED = LogTemplate.of("Tracking session recovery failed")
	val TRACKING_SESSION_STARTED = LogTemplate.of("Tracking session started")
	val TRACKING_SESSION_START_REQUESTED = LogTemplate.of("Tracking session start requested")
	val TRACKING_SESSION_STOPPED = LogTemplate.of("Tracking session stopped")
	val TRACKING_SESSION_STORE_FAILED = LogTemplate.of("Tracking session store failed")
	val TRACKING_SHUTDOWN_DEGRADED = LogTemplate.of("Tracking shutdown was degraded")
	val TRACKING_SIGNAL_CHECKPOINT_FAILED = LogTemplate.of("Tracking signal checkpoint failed")
	val TRACKING_SOURCE_SESSION_START_REJECTED =
		LogTemplate.of("Tracking source session start was rejected")
	val TRACKING_START_FAILED = LogTemplate.of("Tracking start failed")
	val TRACKING_START_STORAGE_UNAVAILABLE =
		LogTemplate.of("Tracking start failed: storage unavailable")
	val TRACKING_STOP_REQUESTED = LogTemplate.of("Tracking stop requested: reason {}")
}
