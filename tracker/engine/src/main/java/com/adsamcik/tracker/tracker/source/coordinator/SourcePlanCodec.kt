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

data class EncodedSourcePlan(val bytes: ByteArray, val checksum: String)

@Singleton
class SourcePlanCodec @Inject constructor() {
	fun encode(plan: SourcePlan): EncodedSourcePlan {
		val bytes = ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(FORMAT_VERSION)
				output.writeUTF(plan.source.name)
				output.writeLong(plan.revision)
				when (plan) {
					is LocationPlan -> with(output) {
						writeUTF(plan.backend.name)
						writeUTF(plan.mode.name)
						writeLong(plan.requestedIntervalMs)
						writeLong(plan.minimumUpdateIntervalMs)
						writeFloat(plan.minimumDisplacementMeters)
						writeLong(plan.maximumBatchDelayMs)
						writeNullableLong(plan.probeDurationMs)
						writeBoolean(plan.preciseLocationAvailable)
					}
					is ActivityPlan -> with(output) {
						writeUTF(plan.mode.name)
						writeLong(plan.desiredDetectionLatencyMs)
						writeInt(plan.confidenceThresholdPercent)
						writeIntSet(plan.transitionTypes)
					}
					is StepsPlan -> with(output) {
						writeBoolean(plan.enabled)
						writeLong(plan.maximumReportLatencyMs)
						writeLong(plan.projectionCheckpointIntervalMs)
						writeBoolean(plan.movementPolicyNeedsLowLatency)
					}
					is PressurePlan -> with(output) {
						writeBoolean(plan.enabled)
						writeInt(plan.hardwareSamplePeriodMicros)
						writeInt(plan.maximumReportLatencyMicros)
						writeLong(plan.aggregationWindowMs)
						writeBoolean(plan.movementGatedBurst)
					}
					is WifiPlan -> with(output) {
						writeUTF(plan.mode.name)
						writeLong(plan.minimumAttemptIntervalMs)
						writeLong(plan.maximumAcceptableResultAgeMs)
						writeLong(plan.unchangedResultDedupeWindowMs)
						writeBackoff(plan.backoff)
					}
					is CellPlan -> with(output) {
						writeUTF(plan.mode.name)
						writeLong(plan.minimumRefreshAttemptIntervalMs)
						writeLong(plan.maximumAcceptableCachedAgeMs)
						writeIntSet(plan.subscriptionIds)
						writeBackoff(plan.backoff)
					}
				}
			}
			buffer.toByteArray()
		}
		return EncodedSourcePlan(bytes, sha256(bytes))
	}

	fun decode(bytes: ByteArray): SourcePlan = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
		require(input.readInt() == FORMAT_VERSION) { "Unsupported source-plan format" }
		val source = SourceKind.valueOf(input.readUTF())
		val revision = input.readLong()
		when (source) {
			SourceKind.LOCATION -> LocationPlan(
				revision,
				LocationBackend.valueOf(input.readUTF()),
				LocationMode.valueOf(input.readUTF()),
				input.readLong(),
				input.readLong(),
				input.readFloat(),
				input.readLong(),
				input.readNullableLong(),
				input.readBoolean(),
			)
			SourceKind.ACTIVITY -> ActivityPlan(
				revision,
				ActivityMode.valueOf(input.readUTF()),
				input.readLong(),
				input.readInt(),
				input.readIntSet(),
			)
			SourceKind.STEPS -> StepsPlan(
				revision,
				input.readBoolean(),
				input.readLong(),
				input.readLong(),
				input.readBoolean(),
			)
			SourceKind.PRESSURE -> PressurePlan(
				revision,
				input.readBoolean(),
				input.readInt(),
				input.readInt(),
				input.readLong(),
				input.readBoolean(),
			)
			SourceKind.WIFI -> WifiPlan(
				revision,
				WifiMode.valueOf(input.readUTF()),
				input.readLong(),
				input.readLong(),
				input.readLong(),
				input.readBackoff(),
			)
			SourceKind.CELL -> CellPlan(
				revision,
				CellMode.valueOf(input.readUTF()),
				input.readLong(),
				input.readLong(),
				input.readIntSet(),
				input.readBackoff(),
			)
		}.also { require(input.available() == 0) { "Trailing source-plan bytes" } }
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
		.joinToString("") { byte -> "%02x".format(byte) }

	private companion object {
		const val FORMAT_VERSION = 1
		const val MAX_SET_SIZE = 10_000
	}
}
