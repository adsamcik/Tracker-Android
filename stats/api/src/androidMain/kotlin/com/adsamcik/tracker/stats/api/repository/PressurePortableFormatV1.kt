package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.pressure.portable.PressurePortableFormatV1 as ModelFormat
import com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureIntegrity as ModelIntegrity

/** Source-compatible API surface over the shared, storage-independent Pressure wire contract. */
object PressurePortableFormatV1 {
	const val FORMAT = ModelFormat.FORMAT
	const val SCHEMA_VERSION = ModelFormat.SCHEMA_VERSION
	const val FILE_EXTENSION = ModelFormat.FILE_EXTENSION
	const val MIME_TYPE = ModelFormat.MIME_TYPE
	const val MAX_ENTRIES = ModelFormat.MAX_ENTRIES
	const val MAX_RUNS_PER_ENTRY = ModelFormat.MAX_RUNS_PER_ENTRY
	const val MAX_WINDOWS_PER_RUN = ModelFormat.MAX_WINDOWS_PER_RUN
	const val MAX_TOTAL_RUNS = ModelFormat.MAX_TOTAL_RUNS
	const val MAX_TOTAL_WINDOWS = ModelFormat.MAX_TOTAL_WINDOWS
	const val MAX_LOCAL_IDENTITY_LENGTH = ModelFormat.MAX_LOCAL_IDENTITY_LENGTH
	const val MAX_ZONE_ID_LENGTH = ModelFormat.MAX_ZONE_ID_LENGTH
	const val MAX_IMPORT_RECEIPT_FIELD_LENGTH = ModelFormat.MAX_IMPORT_RECEIPT_FIELD_LENGTH
}

typealias PortablePressureIdentityKind =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureIdentityKind
typealias PortablePressureOpaqueIdentity =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureOpaqueIdentity
typealias PortablePressureDigest =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureDigest
typealias PortablePressureAvailability =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureAvailability
typealias PortablePressureCoverage =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureCoverage
typealias PortablePressureSensorAccuracy =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureSensorAccuracy
typealias PortablePressureWindowClosure =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureWindowClosure
typealias PortablePressureWindowQualification =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureWindowQualification
typealias PortablePressureWindowV1 =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureWindowV1
typealias PortablePressureRunV1 =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureRunV1
typealias PortablePressureEntryV1 =
	com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureEntryV1

val PORTABLE_PRESSURE_WINDOW_ORDER =
	com.adsamcik.tracker.shared.model.pressure.portable.PORTABLE_PRESSURE_WINDOW_ORDER
val PORTABLE_PRESSURE_RUN_ORDER =
	com.adsamcik.tracker.shared.model.pressure.portable.PORTABLE_PRESSURE_RUN_ORDER
val PORTABLE_PRESSURE_ENTRY_ORDER =
	com.adsamcik.tracker.shared.model.pressure.portable.PORTABLE_PRESSURE_ENTRY_ORDER

object PortablePressureIntegrity {
	fun expectedWindowChecksum(window: PortablePressureWindowV1): PortablePressureDigest =
		ModelIntegrity.expectedWindowChecksum(window)

	fun entryChecksum(
		identity: PortablePressureOpaqueIdentity,
		startTimeMs: Long,
		endTimeMs: Long,
		runs: List<PortablePressureRunV1>,
	): PortablePressureDigest = ModelIntegrity.entryChecksum(identity, startTimeMs, endTimeMs, runs)

	fun expectedEntryChecksum(entry: PortablePressureEntryV1): PortablePressureDigest =
		ModelIntegrity.expectedEntryChecksum(entry)
}
