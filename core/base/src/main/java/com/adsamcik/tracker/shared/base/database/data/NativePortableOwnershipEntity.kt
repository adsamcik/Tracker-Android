package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.adsamcik.tracker.shared.base.database.PortableActivityIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityDeletionScopeDigest
import com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureIdentityKind
import com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureOpaqueIdentity

/** Rebuildable identity lookup only. Readers still qualify the original native source records. */
@Entity(
	tableName = "native_portable_ownership",
	primaryKeys = ["source_kind", "identity_kind", "raw_identity", "logical_tracking_id", "service_run_id"],
	indices = [
		Index(value = ["source_kind", "opaque_identity", "logical_tracking_id"], name = "idx_native_portable_owner_identity"),
		Index(value = ["logical_tracking_id", "service_run_id"], name = "idx_native_portable_owner_run"),
	],
)
data class NativePortableOwnershipEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "identity_kind") val identityKind: String,
	@ColumnInfo(name = "opaque_identity") val opaqueIdentity: String,
	@ColumnInfo(name = "raw_identity") val rawIdentity: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
)

/** Uses the existing portable hash domains; no source payload or new provenance is stored. */
object NativePortableOwnership {
	const val ACTIVITY: Int = 2
	const val PRESSURE: Int = 4

	fun run(logicalTrackingId: String, serviceRunId: String): List<NativePortableOwnershipEntity> =
		listOf(ACTIVITY, PRESSURE).flatMap { source ->
			listOfNotNull(
				row(source, "LOGICAL_ENTRY", logicalTrackingId, logicalTrackingId, serviceRunId),
				row(source, "PHYSICAL_RUN", serviceRunId, logicalTrackingId, serviceRunId),
				if (source == ACTIVITY) row(source, "DELETION_SCOPE", serviceRunId, logicalTrackingId, serviceRunId) else null,
			)
		}

	fun activity(revision: ActivityCapturedWindowRevisionEntity): List<NativePortableOwnershipEntity> =
		fact(ACTIVITY, revision.logicalTrackingId, revision.serviceRunId, revision.logicalWindowId)

	fun pressure(revision: PressureFactRevisionEntity): List<NativePortableOwnershipEntity> =
		fact(PRESSURE, revision.logicalTrackingId, revision.serviceRunId, revision.logicalFactId)

	fun fact(source: Int, logicalId: String, runId: String, windowId: String): List<NativePortableOwnershipEntity> =
		listOfNotNull(
			row(source, "LOGICAL_ENTRY", logicalId, logicalId, runId),
			row(source, "PHYSICAL_RUN", runId, logicalId, runId),
			if (source == ACTIVITY) row(source, "DELETION_SCOPE", runId, logicalId, runId) else null,
			row(source, if (source == ACTIVITY) "CAPTURE_WINDOW" else "WINDOW", windowId, logicalId, runId),
		)

	private fun row(source: Int, kind: String, rawId: String, logicalId: String, runId: String): NativePortableOwnershipEntity? {
		// An invalid legacy local ID cannot be a valid portable owner. Preserve its source row;
		// the ordinary source reader remains responsible for explaining its unusable contents.
		if (logicalId.isBlank() || runId.isBlank()) return null
		val opaque = try {
			when (source) {
				ACTIVITY -> if (kind == "DELETION_SCOPE") PortableActivityDeletionScopeDigest.derive(logicalId, runId).value
					else PortableActivityOpaqueIdentity.derive(PortableActivityIdentityKind.valueOf(kind), rawId).value
				PRESSURE -> PortablePressureOpaqueIdentity.derive(PortablePressureIdentityKind.valueOf(kind), rawId).value
				else -> error("Unsupported native portable source")
			}
		} catch (_: IllegalArgumentException) {
			return null
		}
		return NativePortableOwnershipEntity(source, kind, opaque, rawId, logicalId, runId)
	}
}
