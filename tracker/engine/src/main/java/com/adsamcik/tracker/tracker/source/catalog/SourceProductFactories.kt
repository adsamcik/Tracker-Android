package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.tracker.api.TrackingDecisionContainmentReason
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import javax.inject.Inject

sealed interface SourceProductCapability {
	data object Dormant : SourceProductCapability
	data class Contained(
		val reason: TrackingDecisionContainmentReason,
	) : SourceProductCapability
}

data class SourceWriterOwnership(
	val destination: String,
	val owner: String,
	val ownerGeneration: Long,
) {
	init {
		require(destination.isNotBlank())
		require(owner.isNotBlank())
		require(ownerGeneration > 0L)
	}
}

sealed interface SourceWriterContract {
	data class ProtectedLocation(
		val ownership: SourceWriterOwnership,
	) : SourceWriterContract

	data class SourceLocalLane(
		val ownership: SourceWriterOwnership,
		val lanes: Set<ExecutableSourceLaneBinding>,
	) : SourceWriterContract {
		init {
			require(lanes.isNotEmpty())
			require(lanes.map(ExecutableSourceLaneBinding::source).distinct().size == 1)
			require(lanes.none { it.source == SourceKind.LOCATION }) {
				"Location remains owned by the protected canonical writer"
			}
		}
	}

	data object ActivityControlOutboxOnly : SourceWriterContract

	sealed interface Ambient : SourceWriterContract {
		val writerId: String
		val writerVersion: Int
		val writerOwnerGeneration: Long
	}

	data class AmbientSteps(
		val ownership: SourceWriterOwnership,
		override val writerId: String,
		override val writerVersion: Int,
		override val writerOwnerGeneration: Long,
	) : Ambient {
		init {
			require(writerId.isNotBlank())
			require(writerVersion > 0)
			require(writerOwnerGeneration == ownership.ownerGeneration)
		}
	}

	data class AmbientWifi(
		override val writerId: String,
		override val writerVersion: Int,
		override val writerOwnerGeneration: Long,
	) : Ambient {
		init {
			require(writerId.isNotBlank())
			require(writerVersion > 0)
			require(writerOwnerGeneration > 0L)
		}
	}

	data class AmbientCell(
		override val writerId: String,
		override val writerVersion: Int,
		override val writerOwnerGeneration: Long,
	) : Ambient {
		init {
			require(writerId.isNotBlank())
			require(writerVersion > 0)
			require(writerOwnerGeneration > 0L)
		}
	}
}

sealed interface SourceProjectionContract {
	data class ProtectedLocationCanonicalHandoff(
		val projectionId: String,
		val projectionVersion: Int,
	) : SourceProjectionContract {
		init {
			require(projectionId.isNotBlank())
			require(projectionVersion > 0)
		}
	}

	data class ExecutableLanes(
		val lanes: Set<ExecutableSourceLaneBinding>,
	) : SourceProjectionContract {
		init {
			require(lanes.isNotEmpty())
			require(lanes.none { it.source == SourceKind.LOCATION })
		}
	}

	data class ActivityControl(
		val projectionId: String,
		val projectionVersion: Int,
	) : SourceProjectionContract {
		init {
			require(projectionId.isNotBlank())
			require(projectionVersion > 0)
		}
	}

	data class AmbientWriter(
		val writerId: String,
		val writerVersion: Int,
	) : SourceProjectionContract {
		init {
			require(writerId.isNotBlank())
			require(writerVersion > 0)
		}
	}
}

sealed interface SourceDrainContract {
	data object ProtectedLocation : SourceDrainContract
	data object SourceLocalProductRouter : SourceDrainContract
	data class NotApplicable(val reason: SourceDrainUnsupportedReason) : SourceDrainContract
}

enum class SourceDrainUnsupportedReason {
	CONTROL_HAS_NO_PRODUCT_DRAIN,
	AMBIENT_PRODUCT_IS_NOT_SESSION_DRAINED,
}

sealed interface SourceQueryContract {
	data object LocationSamples : SourceQueryContract
	data object ActivityHistory : SourceQueryContract
	data object StepsSessionHistory : SourceQueryContract
	data object PressureSessionHistory : SourceQueryContract
	data object WifiHistory : SourceQueryContract
	data object CellHistory : SourceQueryContract
	data object AmbientStepsHistory : SourceQueryContract
	data object AmbientWifiHistory : SourceQueryContract
	data object AmbientCellHistory : SourceQueryContract
	data class Unsupported(val reason: SourceQueryUnsupportedReason) : SourceQueryContract
}

enum class SourceQueryUnsupportedReason {
	CONTROL_HAS_NO_PRODUCT_QUERY,
}

sealed interface SourcePortableContract {
	data object CapturedActivity : SourcePortableContract
	data object SessionStepsV2 : SourcePortableContract
	data object PressureV1 : SourcePortableContract
	data object CapturedWifiV1 : SourcePortableContract
	data object CapturedCell : SourcePortableContract
	data object AmbientStepsV2 : SourcePortableContract
	data object AmbientWifiV1 : SourcePortableContract
	data object AmbientCellV1 : SourcePortableContract
	data class Unsupported(val reason: SourcePortableUnsupportedReason) : SourcePortableContract
}

enum class SourcePortableUnsupportedReason {
	CONTROL_EVIDENCE_IS_NOT_PORTABLE,
	LOCATION_SOURCE_PORTABLE_CONTRACT_UNAVAILABLE,
	AMBIENT_LOCATION_PORTABLE_CONTRACT_UNAVAILABLE,
}

sealed interface SourceRetentionContract {
	data class SessionCapture(
		val portableImportScope: RetentionAuthorityScope?,
	) : SourceRetentionContract

	data class Ambient(
		val liveScope: RetentionAuthorityScope,
		val portableImportScope: RetentionAuthorityScope?,
	) : SourceRetentionContract {
		init {
			require(liveScope == RetentionAuthorityScope.LIVE_AMBIENT)
			require(
				portableImportScope == null ||
					portableImportScope == RetentionAuthorityScope.PORTABLE_IMPORT,
			)
		}
	}

	data class Contained(
		val reason: TrackingDecisionContainmentReason,
	) : SourceRetentionContract
}

sealed interface SourceProductBinding {
	val source: TrackingSource
	val purpose: TrackingPurpose
	val capability: SourceProductCapability
	val writer: SourceWriterContract
	val drain: SourceDrainContract
	val projection: SourceProjectionContract
	val query: SourceQueryContract
	val portable: SourcePortableContract
	val retention: SourceRetentionContract

	data object LocationSession : SourceProductBinding {
		override val source = TrackingSource.LOCATION
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = PROTECTED_LOCATION_WRITER
		override val drain = SourceDrainContract.ProtectedLocation
		override val projection = PROTECTED_LOCATION_PROJECTION
		override val query = SourceQueryContract.LocationSamples
		override val portable = SourcePortableContract.Unsupported(
			SourcePortableUnsupportedReason.LOCATION_SOURCE_PORTABLE_CONTRACT_UNAVAILABLE,
		)
		override val retention = SourceRetentionContract.SessionCapture(portableImportScope = null)
	}

	data object ActivitySession : SourceProductBinding {
		override val source = TrackingSource.ACTIVITY
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
			setOf(ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.ActivityHistory
		override val portable = SourcePortableContract.CapturedActivity
		override val retention = portableSessionRetention()
	}

	data object StepsSession : SourceProductBinding {
		override val source = TrackingSource.STEPS
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			setOf(
				ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1,
				ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V2,
			),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.StepsSessionHistory
		override val portable = SourcePortableContract.SessionStepsV2
		override val retention = portableSessionRetention()
	}

	data object PressureSession : SourceProductBinding {
		override val source = TrackingSource.PRESSURE
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			setOf(ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.PressureSessionHistory
		override val portable = SourcePortableContract.PressureV1
		override val retention = portableSessionRetention()
	}

	data object WifiSession : SourceProductBinding {
		override val source = TrackingSource.WIFI
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS,
			setOf(ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.WifiHistory
		override val portable = SourcePortableContract.CapturedWifiV1
		override val retention = portableSessionRetention()
	}

	data object CellSession : SourceProductBinding {
		override val source = TrackingSource.CELL
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
			setOf(ExecutableSourceLaneCatalog.CELL_SESSION_FACTS),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.CellHistory
		override val portable = SourcePortableContract.CapturedCell
		override val retention = portableSessionRetention()
	}

	data object ActivityControl : SourceProductBinding {
		override val source = TrackingSource.ACTIVITY
		override val purpose = TrackingPurpose.CONTROL
		override val capability = SourceProductCapability.Contained(
			TrackingDecisionContainmentReason.AUTO_005_CONTROL_EVIDENCE_UNRESOLVED,
		)
		override val writer = SourceWriterContract.ActivityControlOutboxOnly
		override val drain = SourceDrainContract.NotApplicable(
			SourceDrainUnsupportedReason.CONTROL_HAS_NO_PRODUCT_DRAIN,
		)
		override val projection = SourceProjectionContract.ActivityControl(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)
		override val query = SourceQueryContract.Unsupported(
			SourceQueryUnsupportedReason.CONTROL_HAS_NO_PRODUCT_QUERY,
		)
		override val portable = SourcePortableContract.Unsupported(
			SourcePortableUnsupportedReason.CONTROL_EVIDENCE_IS_NOT_PORTABLE,
		)
		override val retention = SourceRetentionContract.Contained(
			TrackingDecisionContainmentReason.AUTO_005_CONTROL_EVIDENCE_UNRESOLVED,
		)
	}

	data object LocationAmbient : SourceProductBinding {
		override val source = TrackingSource.LOCATION
		override val purpose = TrackingPurpose.AMBIENT_PRODUCT
		override val capability = SourceProductCapability.Contained(
			TrackingDecisionContainmentReason.EXPANDED_AMBIENT_LOCATION_UNAVAILABLE,
		)
		override val writer = PROTECTED_LOCATION_WRITER
		override val drain = SourceDrainContract.ProtectedLocation
		override val projection = PROTECTED_LOCATION_PROJECTION
		override val query = SourceQueryContract.LocationSamples
		override val portable = SourcePortableContract.Unsupported(
			SourcePortableUnsupportedReason.AMBIENT_LOCATION_PORTABLE_CONTRACT_UNAVAILABLE,
		)
		override val retention = SourceRetentionContract.Ambient(
			RetentionAuthorityScope.LIVE_AMBIENT,
			portableImportScope = null,
		)
	}

	data object StepsAmbient : SourceProductBinding {
		override val source = TrackingSource.STEPS
		override val purpose = TrackingPurpose.AMBIENT_PRODUCT
		override val capability = SourceProductCapability.Dormant
		override val writer = SourceWriterContract.AmbientSteps(
			ownership = SourceWriterOwnership(
				SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS,
				SourceDestinationOwnerEntity.INITIAL_AMBIENT_STEPS_GENERATION,
			),
			writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.INITIAL_AMBIENT_STEPS_GENERATION,
		)
		override val drain = SourceDrainContract.NotApplicable(
			SourceDrainUnsupportedReason.AMBIENT_PRODUCT_IS_NOT_SESSION_DRAINED,
		)
		override val projection = SourceProjectionContract.AmbientWriter(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
		)
		override val query = SourceQueryContract.AmbientStepsHistory
		override val portable = SourcePortableContract.AmbientStepsV2
		override val retention = portableAmbientRetention()
	}

	data object WifiAmbient : SourceProductBinding {
		override val source = TrackingSource.WIFI
		override val purpose = TrackingPurpose.AMBIENT_PRODUCT
		override val capability = SourceProductCapability.Dormant
		override val writer = SourceWriterContract.AmbientWifi(
			AmbientWifiFactRevisionEntity.WRITER_ID,
			AmbientWifiFactRevisionEntity.WRITER_VERSION,
			AmbientWifiAuthorityEntity.FIRST_WRITER_OWNER_GENERATION,
		)
		override val drain = SourceDrainContract.NotApplicable(
			SourceDrainUnsupportedReason.AMBIENT_PRODUCT_IS_NOT_SESSION_DRAINED,
		)
		override val projection = SourceProjectionContract.AmbientWriter(
			AmbientWifiFactRevisionEntity.WRITER_ID,
			AmbientWifiFactRevisionEntity.WRITER_VERSION,
		)
		override val query = SourceQueryContract.AmbientWifiHistory
		override val portable = SourcePortableContract.AmbientWifiV1
		override val retention = portableAmbientRetention()
	}

	data object CellAmbient : SourceProductBinding {
		override val source = TrackingSource.CELL
		override val purpose = TrackingPurpose.AMBIENT_PRODUCT
		override val capability = SourceProductCapability.Dormant
		override val writer = SourceWriterContract.AmbientCell(
			AmbientCellFactRevisionEntity.WRITER_ID,
			AmbientCellFactRevisionEntity.WRITER_VERSION,
			AmbientCellAuthorityEntity.FIRST_WRITER_OWNER_GENERATION,
		)
		override val drain = SourceDrainContract.NotApplicable(
			SourceDrainUnsupportedReason.AMBIENT_PRODUCT_IS_NOT_SESSION_DRAINED,
		)
		override val projection = SourceProjectionContract.AmbientWriter(
			AmbientCellFactRevisionEntity.WRITER_ID,
			AmbientCellFactRevisionEntity.WRITER_VERSION,
		)
		override val query = SourceQueryContract.AmbientCellHistory
		override val portable = SourcePortableContract.AmbientCellV1
		override val retention = portableAmbientRetention()
	}
}

/** Produces immutable source-specific descriptors; it never resolves or invokes product services. */
class SourceProductFactories @Inject constructor() {
	fun create(identity: TrackingSourcePurposeIdentity): SourceProductBinding =
		when (identity.purpose) {
			TrackingPurpose.SESSION_CAPTURE -> when (identity.source) {
				TrackingSource.LOCATION -> SourceProductBinding.LocationSession
				TrackingSource.ACTIVITY -> SourceProductBinding.ActivitySession
				TrackingSource.STEPS -> SourceProductBinding.StepsSession
				TrackingSource.PRESSURE -> SourceProductBinding.PressureSession
				TrackingSource.WIFI -> SourceProductBinding.WifiSession
				TrackingSource.CELL -> SourceProductBinding.CellSession
			}
			TrackingPurpose.CONTROL -> when (identity.source) {
				TrackingSource.ACTIVITY -> SourceProductBinding.ActivityControl
				else -> error("${identity.source} does not support CONTROL")
			}
			TrackingPurpose.AMBIENT_PRODUCT -> when (identity.source) {
				TrackingSource.LOCATION -> SourceProductBinding.LocationAmbient
				TrackingSource.STEPS -> SourceProductBinding.StepsAmbient
				TrackingSource.WIFI -> SourceProductBinding.WifiAmbient
				TrackingSource.CELL -> SourceProductBinding.CellAmbient
				TrackingSource.ACTIVITY,
				TrackingSource.PRESSURE,
				-> error("${identity.source} does not support AMBIENT_PRODUCT")
			}
		}
}

private val PROTECTED_LOCATION_OWNERSHIP = SourceWriterOwnership(
	SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
	SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
	SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
)

private val PROTECTED_LOCATION_WRITER =
	SourceWriterContract.ProtectedLocation(PROTECTED_LOCATION_OWNERSHIP)

private val PROTECTED_LOCATION_PROJECTION =
	SourceProjectionContract.ProtectedLocationCanonicalHandoff(
		SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
		SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
	)

private fun sourceLocalWriter(
	destination: String,
	owner: String,
	lanes: Set<ExecutableSourceLaneBinding>,
) = SourceWriterContract.SourceLocalLane(
	ownership = SourceWriterOwnership(
		destination,
		owner,
		SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
	),
	lanes = lanes,
)

private fun executableProjection(
	writer: SourceWriterContract.SourceLocalLane,
) = SourceProjectionContract.ExecutableLanes(writer.lanes)

private fun portableSessionRetention() = SourceRetentionContract.SessionCapture(
	portableImportScope = RetentionAuthorityScope.PORTABLE_IMPORT,
)

private fun portableAmbientRetention() = SourceRetentionContract.Ambient(
	liveScope = RetentionAuthorityScope.LIVE_AMBIENT,
	portableImportScope = RetentionAuthorityScope.PORTABLE_IMPORT,
)
