package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceAcquisitionFloorCodec
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Semantic identity of one desired source plan. Room plan revisions remain the execution
 * generations; this digest deliberately excludes revision, plan ID, and creation time so a retry
 * can receive a fresh Room revision while still proving it represents the same desired behavior.
 */
internal fun SourceSessionPlanInputs.desiredPlanFingerprint(
	plan: AcquisitionPlanRevision,
	rolloutRevision: Long,
	captureMode: CaptureReachabilityMode,
	startOrigin: SessionStartOrigin,
	foregroundCapabilityFlags: Long,
	controlDependencies: Set<SourceKind>,
	codec: SourcePlanCodec,
): String {
	val buffer = ByteArrayOutputStream()
	DataOutputStream(buffer).use { output ->
		output.writeInt(DESIRED_PLAN_FINGERPRINT_VERSION)
		output.writeLong(rolloutRevision)
		output.writeString(captureMode.name)
		output.writeString(startOrigin.name)
		output.writeLong(foregroundCapabilityFlags)
		output.writeSourceSet(controlDependencies)
		output.writeSettings(settings)
		output.writeString(clockDomainId)
		output.writeString(zoneId)
		output.writeEnvironment(environment)
		output.writeResolutionContext(resolutionContext)
		output.writeDemands(demands)
		output.writeNullableLong(plan.sourcePolicyRevision)
		val orderedPlans = plan.plans.values.sortedBy { sourcePlan -> sourcePlan.source.stableCode }
		output.writeInt(orderedPlans.size)
		orderedPlans.forEach { sourcePlan ->
			output.writeInt(sourcePlan.source.stableCode)
			val encoded = codec.encode(sourcePlan.withFingerprintRevision())
			output.writeByteArray(encoded.bytes)
		}
	}
	return MessageDigest.getInstance("SHA-256")
		.digest(buffer.toByteArray())
		.joinToString("") { byte -> "%02x".format(byte) }
}

private fun DataOutputStream.writeSettings(settings: TrackingParamsState) {
	writeBoolean(settings.locationEnabled)
	writeBoolean(settings.activityEnabled)
	writeBoolean(settings.stepsEnabled)
	writeBoolean(settings.wifiEnabled)
	writeBoolean(settings.cellEnabled)
	writeBoolean(settings.barometerEnabled)
	writeBoolean(settings.ambientLocationEnabled)
	writeBoolean(settings.ambientStepsEnabled)
	writeBoolean(settings.ambientWifiEnabled)
	writeBoolean(settings.ambientCellEnabled)
	writeInt(settings.autoTrackingMode)
	writeBoolean(settings.transitionDetectionEnabled)
	writeBoolean(settings.notificationStyled)
	writeInt(settings.minDistanceMeters)
	writeInt(settings.minTimeSeconds)
	writeInt(settings.requiredAccuracyMeters)
	writeString(settings.presetName)
	writeInt(settings.sourceCollectionSettings.location.stableCode)
	writeInt(settings.sourceCollectionSettings.activity.stableCode)
	writeInt(settings.sourceCollectionSettings.steps.stableCode)
	writeInt(settings.sourceCollectionSettings.pressure.stableCode)
	writeInt(settings.sourceCollectionSettings.wifi.stableCode)
	writeInt(settings.sourceCollectionSettings.cell.stableCode)
	writeBoolean(settings.advancedSourceControlsEnabled)
	writeInt(settings.sourceSettingsVersion)
	writeBoolean(settings.legacySettingsMigrationCompleted)
	writeNullableLong(settings.sourcePolicyRevision)
}

private fun DataOutputStream.writeEnvironment(environment: SourcePlanEnvironment) {
	writeString(environment.locationBackend.name)
	writeBoolean(environment.preciseLocationAvailable)
	writeInt(environment.subscriptionIds.size)
	environment.subscriptionIds.sorted().forEach(::writeInt)
}

private fun DataOutputStream.writeResolutionContext(context: PlanResolutionContext) {
	SourceKind.entries.sortedBy(SourceKind::stableCode).forEach { source ->
		val constraint = context.constraints[source] ?: SourceConstraint()
		writeInt(source.stableCode)
		writeBoolean(constraint.hardwareAvailable)
		writeBoolean(constraint.providerAvailable)
		writeBoolean(constraint.permissionGranted)
		writeBoolean(constraint.foregroundCapabilityLegal)
		writeBoolean(constraint.backgroundStartLegal)
	}
	writeBoolean(context.powerSaver)
	writeBoolean(context.doze)
	writeBoolean(context.severeThermalPressure)
	writeBoolean(context.motionProfile.stationary)
	writeString(context.motionProfile.locationStrategy.name)
	writeBoolean(context.motionProfile.expensiveNetworkScansAllowed)
	writeBoolean(context.motionProfile.continuousPressureAllowed)
	writeBoolean(context.motionProfile.lowLatencyStepReporting)
}

private fun DataOutputStream.writeDemands(demands: List<SourceDemand>) {
	val ordered = demands.sortedWith(
		compareBy<SourceDemand>(
			{ demand -> demand.source.stableCode },
			SourceDemand::maximumAgeMs,
			SourceDemand::desiredLatencyMs,
			{ demand -> demand.quality.name },
			{ demand -> demand.reason.name },
			{ demand -> demand.acquisitionFloor?.let(SourceAcquisitionFloorCodec::encode).orEmpty() },
			{ demand -> demand.requestedDeliveryLatencyMs ?: Long.MIN_VALUE },
			SourceDemand::adaptiveReductionAllowed,
		),
	)
	writeInt(ordered.size)
	ordered.forEach { demand ->
		writeInt(demand.source.stableCode)
		writeLong(demand.maximumAgeMs)
		writeLong(demand.desiredLatencyMs)
		writeString(demand.quality.name)
		writeString(demand.reason.name)
		writeNullableString(demand.acquisitionFloor?.let(SourceAcquisitionFloorCodec::encode))
		writeNullableLong(demand.requestedDeliveryLatencyMs)
		writeBoolean(demand.adaptiveReductionAllowed)
	}
}

private fun DataOutputStream.writeSourceSet(sources: Set<SourceKind>) {
	val ordered = sources.sortedBy(SourceKind::stableCode)
	writeInt(ordered.size)
	ordered.forEach { source -> writeInt(source.stableCode) }
}

private fun DataOutputStream.writeString(value: String) {
	writeByteArray(value.toByteArray(Charsets.UTF_8))
}

private fun DataOutputStream.writeNullableString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeString(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataOutputStream.writeByteArray(value: ByteArray) {
	writeInt(value.size)
	write(value)
}

private fun SourcePlan.withFingerprintRevision(): SourcePlan = when (this) {
	is LocationPlan -> copy(revision = 0L)
	is ActivityPlan -> copy(revision = 0L)
	is StepsPlan -> copy(revision = 0L)
	is PressurePlan -> copy(revision = 0L)
	is WifiPlan -> copy(revision = 0L)
	is CellPlan -> copy(revision = 0L)
}

private const val DESIRED_PLAN_FINGERPRINT_VERSION = 1
