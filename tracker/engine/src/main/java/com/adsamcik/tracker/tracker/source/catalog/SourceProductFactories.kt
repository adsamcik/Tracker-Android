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

/**
 * Whether a product descriptor is dormant or explicitly contained; this value does not activate
 * collection.
 */
sealed interface SourceProductCapability {
	/**
	 * The contract is described but must still obtain runtime and writer authority before activation.
	 */
	data object Dormant : SourceProductCapability
	/** This product is withheld under the recorded decision reason. */
	data class Contained(
		val reason: TrackingDecisionContainmentReason,
	) : SourceProductCapability
}

/** Exact durable destination owner and positive generation required before product writes. */
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

/** Source-owned persistence path for one purpose, including paths with no recorded product writer. */
sealed interface SourceWriterContract {
	/** This purpose has no product writer or drain; the reason distinguishes deliberate exclusions. */
	data class NotApplicable(
		val reason: SourceWriterUnsupportedReason,
	) : SourceWriterContract

	/** Uses the existing protected Location owner rather than creating a competing writer. */
	data class ProtectedLocation(
		val ownership: SourceWriterOwnership,
	) : SourceWriterContract

	/** Canonical source-local writer ownership and the supported executable lane bindings. */
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

	/**
	 * Sessionless writer identity or retention scopes for independently authorized all-day products.
	 */
	sealed interface Ambient : SourceWriterContract {
		val writerId: String
		val writerVersion: Int
		val writerOwnerGeneration: Long
	}

	/** All-day Steps writer with its exact durable destination generation. */
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

	/** All-day Wi-Fi writer ID, version and owner generation; no session owner is implied. */
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

	/** All-day Cell writer ID, version and owner generation; no session owner is implied. */
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

/** Reasons a source-purpose pair intentionally has no recorded-product writer. */
enum class SourceWriterUnsupportedReason {
	CONTROL_HAS_NO_PRODUCT_WRITER,
}

/** Which source-owned projection interprets durable observations for this purpose. */
sealed interface SourceProjectionContract {
	/** Versioned handoff to the existing canonical Location pipeline. */
	data class ProtectedLocationCanonicalHandoff(
		val projectionId: String,
		val projectionVersion: Int,
	) : SourceProjectionContract {
		init {
			require(projectionId.isNotBlank())
			require(projectionVersion > 0)
		}
	}

	/** Supported non-Location projection bindings for one source-owned product. */
	data class ExecutableLanes(
		val lanes: Set<ExecutableSourceLaneBinding>,
	) : SourceProjectionContract {
		init {
			require(lanes.isNotEmpty())
			require(lanes.none { it.source == SourceKind.LOCATION })
		}
	}

	/** Activity-only automatic-start projection; it does not create captured Activity history. */
	data class ActivityControl(
		val projectionId: String,
		val projectionVersion: Int,
	) : SourceProjectionContract {
		init {
			require(projectionId.isNotBlank())
			require(projectionVersion > 0)
		}
	}

	/** Versioned all-day projection identity independent of session capture. */
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

/** Drain routing needed to settle a purpose, or an explicit reason no session drain applies. */
sealed interface SourceDrainContract {
	/** Uses the existing protected Location owner rather than creating a competing writer. */
	data object ProtectedLocation : SourceDrainContract
	/** Routes drain requests to the chosen source-local product lane. */
	data object SourceLocalProductRouter : SourceDrainContract
	/** This purpose has no product writer or drain; the reason distinguishes deliberate exclusions. */
	data class NotApplicable(val reason: SourceDrainUnsupportedReason) : SourceDrainContract
}

/** Reasons CONTROL or all-day products do not use session product draining. */
enum class SourceDrainUnsupportedReason {
	CONTROL_HAS_NO_PRODUCT_DRAIN,
	AMBIENT_PRODUCT_IS_NOT_SESSION_DRAINED,
}

/** The source-owned saved-data reader family required by a product descriptor. */
sealed interface SourceQueryContract {
	/** Reads retained canonical Location points; querying never starts Location. */
	data object LocationSamples : SourceQueryContract
	/** Reads captured or imported Activity measurements through their saved-data authority. */
	data object ActivityHistory : SourceQueryContract
	/** Reads saved session Steps intervals and their exact count-domain semantics. */
	data object StepsSessionHistory : SourceQueryContract
	/** Reads saved Pressure windows with their original measurement bounds. */
	data object PressureSessionHistory : SourceQueryContract
	/** Reads saved session Wi-Fi observations and available network details. */
	data object WifiHistory : SourceQueryContract
	/** Reads saved session Cell observations and available tower details. */
	data object CellHistory : SourceQueryContract
	/** Reads independent all-day Steps provider windows for daily history. */
	data object AmbientStepsHistory : SourceQueryContract
	/** Reads sessionless Wi-Fi observations under their original all-day purpose. */
	data object AmbientWifiHistory : SourceQueryContract
	/** Reads sessionless Cell observations under their original all-day purpose. */
	data object AmbientCellHistory : SourceQueryContract
	/** This purpose has no applicable query or portable format under the attached reason. */
	data class Unsupported(val reason: SourceQueryUnsupportedReason) : SourceQueryContract
}

/** Reasons temporary CONTROL evidence has no user-facing product query. */
enum class SourceQueryUnsupportedReason {
	CONTROL_HAS_NO_PRODUCT_QUERY,
}

/** Typed portable format supported for one saved source-purpose product. */
sealed interface SourcePortableContract {
	/** Portable captured Activity measurements with their saved provenance. */
	data object CapturedActivity : SourcePortableContract
	/** Version-two session Steps format carrying count-domain information. */
	data object SessionStepsV2 : SourcePortableContract
	/** Version-one Pressure format retaining original measured window bounds. */
	data object PressureV1 : SourcePortableContract
	/** Captured Wi-Fi source format; optional Location enrichment is not invented into radio data. */
	data object CapturedWifiV1 : SourcePortableContract
	/** Captured Cell source format retaining observation and tower details. */
	data object CapturedCell : SourcePortableContract
	/** Version-two all-day Steps format retaining real provider windows and count domains. */
	data object AmbientStepsV2 : SourcePortableContract
	/** Version-one sessionless Wi-Fi format under independent retained purpose authority. */
	data object AmbientWifiV1 : SourcePortableContract
	/** Version-one sessionless Cell format under independent retained purpose authority. */
	data object AmbientCellV1 : SourcePortableContract
	/** This purpose has no applicable query or portable format under the attached reason. */
	data class Unsupported(val reason: SourcePortableUnsupportedReason) : SourcePortableContract
}

/** Reasons this source-purpose descriptor cannot produce a typed portable file. */
enum class SourcePortableUnsupportedReason {
	CONTROL_EVIDENCE_IS_NOT_PORTABLE,
	LOCATION_SOURCE_PORTABLE_CONTRACT_UNAVAILABLE,
	AMBIENT_LOCATION_PORTABLE_CONTRACT_UNAVAILABLE,
}

/**
 * Retention authority required by a purpose; CONTROL remains separate from durable user observations.
 */
sealed interface SourceRetentionContract {
	/** Temporary automatic-start evidence; never a recorded Activity product. */
	data object ActivityControl : SourceRetentionContract {
		val maximumAgeNanos = com.adsamcik.tracker.shared.base.database.ACTIVITY_CONTROL_RETENTION_NANOS
	}
	/** Session-product retention with an optional separately approved portable import scope. */
	data class SessionCapture(
		val portableImportScope: RetentionAuthorityScope?,
	) : SourceRetentionContract

	/**
	 * Independent live all-day retention plus an optional separately approved portable import scope.
	 */
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

	/** Retention behavior withheld until the attached containment decision is resolved. */
	data class Contained(
		val reason: TrackingDecisionContainmentReason,
	) : SourceRetentionContract
}

/**
 * Immutable writer, projection, query, portable and retention contracts for one source-purpose pair.
 */
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

	/**
	 * Session Location descriptor reusing its existing protected canonical writer and sample reader.
	 */
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

	/** Session Activity descriptor with canonical source-local lanes and captured Activity history. */
	data object ActivitySession : SourceProductBinding {
		override val source = TrackingSource.ACTIVITY
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
			setOf(
				ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS_V1,
				ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS_V2,
			),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.ActivityHistory
		override val portable = SourcePortableContract.CapturedActivity
		override val retention = portableSessionRetention()
	}

	/** Session Steps descriptor with canonical lanes, exact interval queries and version-two files. */
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

	/** Session Pressure descriptor with canonical lanes, original windows and typed Pressure files. */
	data object PressureSession : SourceProductBinding {
		override val source = TrackingSource.PRESSURE
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			setOf(
				ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS_V1,
				ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS_V2,
			),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.PressureSessionHistory
		override val portable = SourcePortableContract.PressureV1
		override val retention = portableSessionRetention()
	}

	/** Session Wi-Fi descriptor with canonical lanes, source history and typed radio export. */
	data object WifiSession : SourceProductBinding {
		override val source = TrackingSource.WIFI
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS,
			setOf(
				ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS_V1,
				ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS_V2,
			),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.WifiHistory
		override val portable = SourcePortableContract.CapturedWifiV1
		override val retention = portableSessionRetention()
	}

	/** Session Cell descriptor with canonical lanes, source history and typed radio export. */
	data object CellSession : SourceProductBinding {
		override val source = TrackingSource.CELL
		override val purpose = TrackingPurpose.SESSION_CAPTURE
		override val capability = SourceProductCapability.Dormant
		override val writer = sourceLocalWriter(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
			setOf(
				ExecutableSourceLaneCatalog.CELL_SESSION_FACTS_V1,
				ExecutableSourceLaneCatalog.CELL_SESSION_FACTS_V2,
			),
		)
		override val drain = SourceDrainContract.SourceLocalProductRouter
		override val projection = executableProjection(writer)
		override val query = SourceQueryContract.CellHistory
		override val portable = SourcePortableContract.CapturedCell
		override val retention = portableSessionRetention()
	}

	/**
	 * Temporary Activity trigger descriptor with no recorded-product writer, query or portable export.
	 */
	data object ActivityControl : SourceProductBinding {
		override val source = TrackingSource.ACTIVITY
		override val purpose = TrackingPurpose.CONTROL
		override val capability = SourceProductCapability.Dormant
		override val writer = SourceWriterContract.NotApplicable(
			SourceWriterUnsupportedReason.CONTROL_HAS_NO_PRODUCT_WRITER,
		)
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
		override val retention = SourceRetentionContract.ActivityControl
	}

	/**
	 * Contained all-day Location descriptor reusing the protected writer; it does not assert acquisition
	 * is enabled.
	 */
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

	/**
	 * Independent all-day Steps descriptor with its own writer, retention and provider-window history.
	 */
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

	/** Independent all-day Wi-Fi descriptor with its own writer, retention and source history. */
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

	/** Independent all-day Cell descriptor with its own writer, retention and source history. */
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
	/**
	 * Selects immutable contracts for the supported canonical source-purpose pair; it never invokes a
	 * provider.
	 */
	// Exhaustive source-purpose mapping returns immutable descriptors without invoking services.
	@Suppress("CyclomaticComplexMethod")
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
