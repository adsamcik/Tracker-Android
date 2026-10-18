package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class EncodedSourcePlan(val bytes: ByteArray, val checksum: String, val formatVersion: Int = 1)

@Singleton
class SourcePlanCodec @Inject constructor() {
	fun encode(plan: SourcePlan): EncodedSourcePlan {
		val formatVersion = when {
			plan is LocationPlan && plan.passiveSystemDelivery -> PASSIVE_LOCATION_FORMAT_VERSION
			plan is LocationPlan && plan.adaptiveProfileVersion != null -> ADAPTIVE_LOCATION_FORMAT_VERSION
			plan is ActivityPlan && plan.adaptiveProfileVersion != null -> ADAPTIVE_ACTIVITY_FORMAT_VERSION
			else -> FORMAT_VERSION
		}
		val bytes = ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(formatVersion)
				output.writeUTF(plan.source.name)
				output.writeLong(plan.revision)
				output.writePlanPayload(plan)
			}
			buffer.toByteArray()
		}
		require(bytes.size <= MAX_PAYLOAD_BYTES) { "Source-plan payload exceeds the durable bound" }
		return EncodedSourcePlan(bytes, sha256(bytes), formatVersion)
	}

	fun decode(bytes: ByteArray): SourcePlan {
		require(bytes.size in 1..MAX_PAYLOAD_BYTES) { "Source-plan payload size is invalid" }
		return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
			val formatVersion = input.readInt()
			require(formatVersion in FORMAT_VERSION..PASSIVE_LOCATION_FORMAT_VERSION) { "Unsupported source-plan format" }
			val source = SourceKind.valueOf(input.readUTF())
			require(formatVersion == FORMAT_VERSION ||
				(formatVersion in setOf(ADAPTIVE_LOCATION_FORMAT_VERSION, PASSIVE_LOCATION_FORMAT_VERSION) &&
					source == SourceKind.LOCATION) ||
				(formatVersion == ADAPTIVE_ACTIVITY_FORMAT_VERSION && source == SourceKind.ACTIVITY)) {
				"Source-plan format does not match source"
			}
			val revision = input.readLong()
			input.readPlanPayload(source, revision, formatVersion)
				.also { require(input.available() == 0) { "Trailing source-plan bytes" } }
		}
	}

	private fun DataOutputStream.writePlanPayload(plan: SourcePlan) {
		when (plan) {
			is LocationPlan -> {
				writeUTF(plan.backend.name)
				writeUTF(plan.mode.name)
				writeLong(plan.requestedIntervalMs)
				writeLong(plan.minimumUpdateIntervalMs)
				writeFloat(plan.minimumDisplacementMeters)
				writeLong(plan.maximumBatchDelayMs)
				writeNullableLong(plan.probeDurationMs)
				writeBoolean(plan.preciseLocationAvailable)
				plan.adaptiveProfileVersion?.let { writeInt(it) }
				if (plan.passiveSystemDelivery) writeBoolean(true)
			}
			is ActivityPlan -> {
				writeUTF(plan.mode.name)
				writeLong(plan.desiredDetectionLatencyMs)
				writeInt(plan.confidenceThresholdPercent)
				writeIntSet(plan.transitionTypes)
				plan.adaptiveProfileVersion?.let { writeInt(it) }
			}
			is StepsPlan -> {
				writeBoolean(plan.enabled)
				writeLong(plan.maximumReportLatencyMs)
				writeLong(plan.projectionCheckpointIntervalMs)
				writeBoolean(plan.movementPolicyNeedsLowLatency)
			}
			is PressurePlan -> {
				writeBoolean(plan.enabled)
				writeInt(plan.hardwareSamplePeriodMicros)
				writeInt(plan.maximumReportLatencyMicros)
				writeLong(plan.aggregationWindowMs)
				// Retain the v1 field position while making the unsupported mode unrepresentable.
				writeBoolean(false)
			}
			is WifiPlan -> {
				writeUTF(plan.mode.name)
				writeLong(plan.minimumAttemptIntervalMs)
				writeLong(plan.maximumAcceptableResultAgeMs)
				writeLong(plan.unchangedResultDedupeWindowMs)
				writeBackoff(plan.backoff)
			}
			is CellPlan -> {
				writeUTF(plan.mode.name)
				writeLong(plan.minimumRefreshAttemptIntervalMs)
				writeLong(plan.maximumAcceptableCachedAgeMs)
				writeIntSet(plan.subscriptionIds)
				writeBackoff(plan.backoff)
			}
		}
	}

	private fun DataInputStream.readPlanPayload(
		source: SourceKind,
		revision: Long,
		formatVersion: Int,
	): SourcePlan {
		return when (source) {
			SourceKind.LOCATION -> readLocationPlan(revision, formatVersion)
			SourceKind.ACTIVITY -> ActivityPlan(
				revision,
				ActivityMode.valueOf(readUTF()),
				readLong(),
				readInt(),
				readIntSet(),
				if (formatVersion == ADAPTIVE_ACTIVITY_FORMAT_VERSION) readInt() else null,
			)
			SourceKind.STEPS -> StepsPlan(
				revision,
				readBoolean(),
				readLong(),
				readLong(),
				readBoolean(),
			)
			SourceKind.PRESSURE -> readPressurePlan(revision)
			SourceKind.WIFI -> WifiPlan(
				revision,
				WifiMode.valueOf(readUTF()),
				readLong(),
				readLong(),
				readLong(),
				readBackoff(),
			)
			SourceKind.CELL -> CellPlan(
				revision,
				CellMode.valueOf(readUTF()),
				readLong(),
				readLong(),
				readIntSet(),
				readBackoff(),
			)
		}
	}

	private fun DataInputStream.readLocationPlan(
		revision: Long,
		formatVersion: Int,
	): LocationPlan {
		return LocationPlan(
			revision,
			LocationBackend.valueOf(readUTF()),
			LocationMode.valueOf(readUTF()),
			readLong(),
			readLong(),
			readFloat(),
			readLong(),
			readNullableLong(),
			readBoolean(),
			if (formatVersion == ADAPTIVE_LOCATION_FORMAT_VERSION) readInt() else null,
			if (formatVersion == PASSIVE_LOCATION_FORMAT_VERSION) {
				readBoolean().also { require(it) }
			} else {
				false
			},
		)
	}

	private fun DataInputStream.readPressurePlan(revision: Long): PressurePlan {
		val enabled = readBoolean()
		val hardwareSamplePeriodMicros = readInt()
		val maximumReportLatencyMicros = readInt()
		val aggregationWindowMs = readLong()
		if (readBoolean()) {
			throw UnsupportedPressurePlanModeException(PRESSURE_MOVEMENT_GATED_BURST)
		}
		return PressurePlan(
			revision,
			enabled,
			hardwareSamplePeriodMicros,
			maximumReportLatencyMicros,
			aggregationWindowMs,
		)
	}

	private fun DataOutputStream.writeNullableLong(value: Long?) {
		writeBoolean(value != null)
		if (value != null) writeLong(value)
	}

	private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

	private fun DataOutputStream.writeIntSet(values: Set<Int>) {
		writeInt(values.size)
		values.sorted().forEach(::writeInt)
	}

	private fun DataInputStream.readIntSet(): Set<Int> {
		val size = readInt()
		require(size in 0..MAX_SET_SIZE)
		return buildSet(size) { repeat(size) { add(readInt()) } }
	}

	private fun DataOutputStream.writeBackoff(backoff: RetryBackoff) {
		writeLong(backoff.initialDelayMs)
		writeLong(backoff.maximumDelayMs)
		writeDouble(backoff.multiplier)
	}

	private fun DataInputStream.readBackoff() = RetryBackoff(readLong(), readLong(), readDouble())

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes)
		.toHexString()

	/**
	 * Current encoding version and bounds used to reject unsupported or oversized acquisition plans.
	 */
	companion object {
		const val FORMAT_VERSION = 1
		internal const val ADAPTIVE_LOCATION_FORMAT_VERSION = 2
		internal const val ADAPTIVE_ACTIVITY_FORMAT_VERSION = 3
		internal const val PASSIVE_LOCATION_FORMAT_VERSION = 4
		const val MAX_SET_SIZE = 10_000
		const val MAX_PAYLOAD_BYTES = 32 * 1024
		const val PRESSURE_MOVEMENT_GATED_BURST = "MOVEMENT_GATED_BURST"
	}
}

/** Rejects a persisted Pressure acquisition mode that this codec cannot interpret safely. */
class UnsupportedPressurePlanModeException(
	val mode: String,
) : IllegalArgumentException("Unsupported Pressure acquisition mode: $mode")
