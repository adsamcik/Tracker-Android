package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.sourceQualityFromStableFlags
import com.adsamcik.tracker.tracker.source.model.toStableFlags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.inject.Inject

/**
 * Canonical event-time projection for the real multi-source tracking consumers.
 *
 * Phase 10 retires the trigger-polled writer. Joined-frame effects are therefore the durable,
 * replayable handoff for downstream consumers and corrections.
 */
class ExplicitTrackingJoinProjection @Inject constructor(
	private val joiner: EventTimeJoiner,
) : Projection {
	override val id: String = ID
	override val version: Int = VERSION

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		val state = context.loadJoinState(STATE_KEY)
			?.let(ExplicitJoinStateCodec::decode)
			?: ExplicitJoinState()
		val candidate = event.toJoinCandidateState()
		state.candidates.removeAll { it.candidate.eventId == candidate.candidate.eventId }
		state.candidates += candidate
		state.advanceWatermark(event)

		TrackingJoinSpecs.contracts.forEach { contract ->
			if (event.evidence.source == contract.spec.primarySource &&
				contract.acceptsPrimary(event.evidence.payload)
			) {
				state.anchors.putIfAbsent(
					anchorKey(contract.consumerId, event.eventId.value),
					ExplicitJoinAnchor(contract.consumerId, event.eventId.value),
				)
			}
		}

		state.anchors.values.toList().forEach { anchor ->
			val contract = TrackingJoinSpecs.byId.getValue(anchor.consumerId)
			val primary = state.candidates.firstOrNull {
				it.candidate.eventId == anchor.primaryEventId
			}?.candidate ?: return@forEach
			val available = state.candidates.map(ExplicitJoinCandidateState::candidate)
			val inferred = joiner.join(primary, available, contract.spec)
			val finalizable = state.canFinalize(primary, contract.spec)
			val desiredFinalization = if (finalizable) {
				inferred.finalization
			} else {
				JoinFinalization.PROVISIONAL
			}
			val signature = frameSignature(inferred.inputs, desiredFinalization)
			if (signature == anchor.lastSignature) return@forEach

			val isLateCorrection = anchor.finalized
			if (isLateCorrection && contract.spec.lateCorrectionPolicy == LateCorrectionPolicy.QUARANTINE) {
				return@forEach
			}
			val revision = anchor.revision + 1
			val emittedFinalization = if (isLateCorrection) {
				JoinFinalization.CORRECTION
			} else {
				desiredFinalization
			}
			val frame = joiner.join(
				primary = primary,
				available = available,
				spec = contract.spec,
				finalizationOverride = emittedFinalization,
				revision = revision,
				supersedesFrameId = anchor.lastFrameId,
				emittedAtAdmissionOrdinal = event.admissionOrdinal,
			)
			context.recordOutbox(
				ProjectionOutboxEffect(
					stableId = "$ID:${frame.frameId}:$revision",
					kind = OUTBOX_KIND,
					payloadVersion = JoinedFrameEffectCodec.VERSION,
					payload = JoinedFrameEffectCodec.encode(anchor.consumerId, frame),
				),
			)
			anchor.lastFrameId = frame.frameId
			anchor.lastSignature = signature
			anchor.revision = revision
			anchor.finalized = finalizable
		}

		state.prune()
		context.saveJoinState(
			key = STATE_KEY,
			payload = ExplicitJoinStateCodec.encode(state),
			minimumRequiredOrdinal = state.candidates.minOfOrNull {
				it.admissionOrdinal
			} ?: event.admissionOrdinal,
			payloadVersion = STATE_VERSION,
		)
	}

	private fun AdmittedSourceEvent<out SourcePayload>.toJoinCandidateState(): ExplicitJoinCandidateState {
		val (windowStart, windowEnd) = when (val sourcePayload = evidence.payload) {
			is PressureWindowPayload -> sourcePayload.windowStartElapsedRealtimeNanos to
				sourcePayload.windowEndElapsedRealtimeNanos
			is StepCounterWindowPayload -> sourcePayload.windowStartElapsedRealtimeNanos to
				sourcePayload.windowEndElapsedRealtimeNanos
			else -> evidence.observedElapsedRealtimeNanos to evidence.observedElapsedRealtimeNanos
		}
		return ExplicitJoinCandidateState(
			candidate = JoinCandidate(
				eventId = eventId.value,
				source = evidence.source,
				observedElapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
				clockDomainId = evidence.clockDomainId,
				logicalTrackingId = evidence.logicalTrackingId?.value,
				quality = evidence.quality,
				windowStartElapsedRealtimeNanos = windowStart,
				windowEndElapsedRealtimeNanos = windowEnd,
			),
			admissionOrdinal = admissionOrdinal,
		)
	}

	companion object {
		const val ID = "explicit-tracking-joins"
		const val VERSION = 1
		const val OUTBOX_KIND = "tracking-joined-frame-v1"
		internal const val STATE_KEY = "tracking-consumer-joins"
		private const val STATE_VERSION = 1
	}
}

internal data class ExplicitJoinCandidateState(
	val candidate: JoinCandidate,
	val admissionOrdinal: Long,
)

internal data class ExplicitJoinAnchor(
	val consumerId: String,
	val primaryEventId: String,
	var lastFrameId: String? = null,
	var lastSignature: String? = null,
	var revision: Int = 0,
	var finalized: Boolean = false,
)

internal data class ExplicitJoinWatermark(
	val source: SourceKind,
	val clockDomainId: String,
	val logicalTrackingId: String?,
	var observedThroughElapsedRealtimeNanos: Long,
	var admittedThroughOrdinal: Long,
)

internal data class ExplicitJoinState(
	val candidates: MutableList<ExplicitJoinCandidateState> = mutableListOf(),
	val anchors: MutableMap<String, ExplicitJoinAnchor> = linkedMapOf(),
	val watermarks: MutableMap<String, ExplicitJoinWatermark> = linkedMapOf(),
) {
	fun advanceWatermark(event: AdmittedSourceEvent<out SourcePayload>) {
		val key = watermarkKey(
			event.evidence.source,
			event.evidence.clockDomainId,
			event.evidence.logicalTrackingId?.value,
		)
		val existing = watermarks[key]
		if (existing == null) {
			watermarks[key] = ExplicitJoinWatermark(
				source = event.evidence.source,
				clockDomainId = event.evidence.clockDomainId,
				logicalTrackingId = event.evidence.logicalTrackingId?.value,
				observedThroughElapsedRealtimeNanos = event.evidence.observedElapsedRealtimeNanos,
				admittedThroughOrdinal = event.admissionOrdinal,
			)
		} else {
			existing.observedThroughElapsedRealtimeNanos = maxOf(
				existing.observedThroughElapsedRealtimeNanos,
				event.evidence.observedElapsedRealtimeNanos,
			)
			existing.admittedThroughOrdinal = maxOf(existing.admittedThroughOrdinal, event.admissionOrdinal)
		}
	}

	fun canFinalize(primary: JoinCandidate, spec: JoinSpec): Boolean {
		val timeoutHorizon = primary.observedElapsedRealtimeNanos + spec.missingInputTimeoutMs.toNanos()
		val clockHigh = watermarks.values
			.filter {
				it.clockDomainId == primary.clockDomainId &&
					it.logicalTrackingId == primary.logicalTrackingId
			}
			.maxOfOrNull(ExplicitJoinWatermark::observedThroughElapsedRealtimeNanos)
		val timedOut = clockHigh != null &&
			clockHigh - spec.allowedLatenessMs.toNanos() >= timeoutHorizon
		return spec.input.all { (source, input) ->
			if (source == spec.primarySource) return@all true
			val watermark = watermarks[watermarkKey(source, primary.clockDomainId, primary.logicalTrackingId)]
			val requiredThrough = when (input.direction) {
				JoinDirection.BEFORE_OR_EQUAL -> primary.observedElapsedRealtimeNanos
				JoinDirection.AFTER_OR_EQUAL,
				JoinDirection.NEAREST,
				JoinDirection.BRACKET,
				JoinDirection.WINDOW_OVERLAP,
				-> primary.observedElapsedRealtimeNanos + input.maximumAgeMs.toNanos()
			}
			val sourceComplete = watermark != null &&
				watermark.observedThroughElapsedRealtimeNanos - spec.allowedLatenessMs.toNanos() >= requiredThrough
			sourceComplete || timedOut
		}
	}

	fun prune() {
		val anchorCandidates = candidates.associateBy { it.candidate.eventId }
		anchors.entries.removeAll { (_, anchor) ->
			if (!anchor.finalized) return@removeAll false
			val primary = anchorCandidates[anchor.primaryEventId]?.candidate ?: return@removeAll true
			val contract = TrackingJoinSpecs.byId.getValue(anchor.consumerId)
			val clockHigh = watermarks.values
				.filter {
					it.clockDomainId == primary.clockDomainId &&
						it.logicalTrackingId == primary.logicalTrackingId
				}
				.maxOfOrNull(ExplicitJoinWatermark::observedThroughElapsedRealtimeNanos)
				?: return@removeAll false
			val correctionHorizon = primary.observedElapsedRealtimeNanos +
				(contract.spec.missingInputTimeoutMs + contract.spec.allowedLatenessMs).toNanos()
			clockHigh > correctionHorizon
		}

		val retainedAnchorIds = anchors.values.mapTo(mutableSetOf(), ExplicitJoinAnchor::primaryEventId)
		val highByClock = watermarks.values.groupBy {
			it.clockDomainId to it.logicalTrackingId
		}.mapValues { (_, values) ->
			values.maxOf(ExplicitJoinWatermark::observedThroughElapsedRealtimeNanos)
		}
		val retentionNanos = (
			TrackingJoinSpecs.MISSING_INPUT_TIMEOUT_MS +
				TrackingJoinSpecs.ALLOWED_LATENESS_MS +
				TrackingJoinSpecs.WINDOW_CONTEXT_MAX_AGE_MS
			).toNanos()
		candidates.removeAll { state ->
			state.candidate.eventId !in retainedAnchorIds &&
				state.candidate.observedElapsedRealtimeNanos <
				(highByClock[state.candidate.clockDomainId to state.candidate.logicalTrackingId]
					?: Long.MIN_VALUE) - retentionNanos
		}
	}
}

internal object JoinedFrameEffectCodec {
	const val VERSION = 1

	fun encode(consumerId: String, frame: JoinedFrame): ByteArray = ByteArrayOutputStream().use { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(VERSION)
			output.writeUTF(consumerId)
			output.writeUTF(frame.frameId)
			output.writeUTF(frame.joinSpecId)
			output.writeUTF(frame.primaryEventId)
			output.writeLong(frame.observationElapsedRealtimeNanos)
			output.writeUTF(frame.clockDomainId)
			output.writeInt(frame.finalization.ordinal)
			output.writeInt(frame.revision)
			output.writeNullableUtf(frame.supersedesFrameId)
			output.writeLong(frame.emittedAtAdmissionOrdinal)
			output.writeInt(frame.inputs.size)
			frame.inputs.toSortedMap(compareBy(SourceKind::stableCode)).forEach { (source, input) ->
				output.writeInt(source.stableCode)
				output.writeInt(input.result.ordinal)
				output.writeNullableLong(input.ageMs)
				output.writeInt(input.eventIds.size)
				input.eventIds.forEach(output::writeUTF)
			}
		}
		bytes.toByteArray()
	}

	fun decode(payload: ByteArray, payloadVersion: Int): JoinedFrameDelivery {
		require(payloadVersion == VERSION)
		return DataInputStream(ByteArrayInputStream(payload)).use { input ->
			require(input.readInt() == VERSION)
			val consumerId = input.readUTF()
			val frameId = input.readUTF()
			val specId = input.readUTF()
			val primaryEventId = input.readUTF()
			val observed = input.readLong()
			val clock = input.readUTF()
			val finalization = JoinFinalization.entries[input.readInt()]
			val revision = input.readInt()
			val supersedes = input.readNullableUtf()
			val ordinal = input.readLong()
			val inputs = buildMap {
				repeat(input.readBoundedCount()) {
					val sourceCode = input.readInt()
					val source = SourceKind.entries.single { it.stableCode == sourceCode }
					val result = JoinInputResult.entries[input.readInt()]
					val age = input.readNullableLong()
					val eventIds = List(input.readBoundedCount()) { input.readUTF() }
					put(source, JoinedInput(eventIds, age, result))
				}
			}
			require(input.available() == 0)
			JoinedFrameDelivery(
				consumerId,
				JoinedFrame(
					frameId,
					specId,
					primaryEventId,
					observed,
					clock,
					inputs,
					finalization,
					revision,
					supersedes,
					ordinal,
				),
			)
		}
	}
}

internal data class JoinedFrameDelivery(val consumerId: String, val frame: JoinedFrame)

private object ExplicitJoinStateCodec {
	private const val VERSION = 1
	private const val MAX_COUNT = 100_000

	fun encode(state: ExplicitJoinState): ByteArray = ByteArrayOutputStream().use { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(VERSION)
			output.writeInt(state.candidates.size)
			state.candidates.forEach { item ->
				val candidate = item.candidate
				output.writeUTF(candidate.eventId)
				output.writeInt(candidate.source.stableCode)
				output.writeLong(candidate.observedElapsedRealtimeNanos)
				output.writeUTF(candidate.clockDomainId)
				output.writeNullableUtf(candidate.logicalTrackingId)
				output.writeNullableFloat(candidate.quality.confidence)
				output.writeLong(candidate.quality.toStableFlags())
				output.writeLong(candidate.windowStartElapsedRealtimeNanos)
				output.writeLong(candidate.windowEndElapsedRealtimeNanos)
				output.writeLong(item.admissionOrdinal)
			}
			output.writeInt(state.anchors.size)
			state.anchors.values.forEach { anchor ->
				output.writeUTF(anchor.consumerId)
				output.writeUTF(anchor.primaryEventId)
				output.writeNullableUtf(anchor.lastFrameId)
				output.writeNullableUtf(anchor.lastSignature)
				output.writeInt(anchor.revision)
				output.writeBoolean(anchor.finalized)
			}
			output.writeInt(state.watermarks.size)
			state.watermarks.values.forEach { watermark ->
				output.writeInt(watermark.source.stableCode)
				output.writeUTF(watermark.clockDomainId)
				output.writeNullableUtf(watermark.logicalTrackingId)
				output.writeLong(watermark.observedThroughElapsedRealtimeNanos)
				output.writeLong(watermark.admittedThroughOrdinal)
			}
		}
		bytes.toByteArray()
	}

	fun decode(payload: ByteArray): ExplicitJoinState = DataInputStream(ByteArrayInputStream(payload)).use { input ->
		require(input.readInt() == VERSION)
		val candidates = MutableList(input.readBoundedCount()) {
			val eventId = input.readUTF()
			val source = input.readSourceKind()
			val observed = input.readLong()
			val clock = input.readUTF()
			val logicalTrackingId = input.readNullableUtf()
			val confidence = input.readNullableFloat()
			val quality = sourceQualityFromStableFlags(input.readLong(), confidence)
			val windowStart = input.readLong()
			val windowEnd = input.readLong()
			ExplicitJoinCandidateState(
				JoinCandidate(
					eventId,
					source,
					observed,
					clock,
					logicalTrackingId,
					quality,
					windowStart,
					windowEnd,
				),
				input.readLong(),
			)
		}
		val anchors = linkedMapOf<String, ExplicitJoinAnchor>()
		repeat(input.readBoundedCount()) {
			val anchor = ExplicitJoinAnchor(
				consumerId = input.readUTF(),
				primaryEventId = input.readUTF(),
				lastFrameId = input.readNullableUtf(),
				lastSignature = input.readNullableUtf(),
				revision = input.readInt(),
				finalized = input.readBoolean(),
			)
			anchors[anchorKey(anchor.consumerId, anchor.primaryEventId)] = anchor
		}
		val watermarks = linkedMapOf<String, ExplicitJoinWatermark>()
		repeat(input.readBoundedCount()) {
			val watermark = ExplicitJoinWatermark(
				source = input.readSourceKind(),
				clockDomainId = input.readUTF(),
				logicalTrackingId = input.readNullableUtf(),
				observedThroughElapsedRealtimeNanos = input.readLong(),
				admittedThroughOrdinal = input.readLong(),
			)
			watermarks[watermarkKey(
				watermark.source,
				watermark.clockDomainId,
				watermark.logicalTrackingId,
			)] = watermark
		}
		require(input.available() == 0)
		ExplicitJoinState(candidates, anchors, watermarks)
	}

	private fun DataInputStream.readBoundedCount(): Int = readInt().also {
		require(it in 0..MAX_COUNT) { "Invalid explicit-join state collection size $it" }
	}
}

private fun anchorKey(consumerId: String, primaryEventId: String) = "$consumerId|$primaryEventId"

private fun watermarkKey(source: SourceKind, clockDomainId: String, logicalTrackingId: String?) =
	"${source.stableCode}|$clockDomainId|${logicalTrackingId.orEmpty()}"

private fun Long.toNanos(): Long = Math.multiplyExact(this, 1_000_000L)

private fun frameSignature(inputs: Map<SourceKind, JoinedInput>, finalization: JoinFinalization): String =
	buildString {
		append(finalization.name)
		inputs.toSortedMap(compareBy(SourceKind::stableCode)).forEach { (source, input) ->
			append('|').append(source.stableCode).append(':').append(input.result.name).append(':')
			append(input.ageMs ?: -1L).append(':').append(input.eventIds.joinToString(","))
		}
	}

private fun DataOutputStream.writeNullableUtf(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

private fun DataOutputStream.writeNullableFloat(value: Float?) {
	writeBoolean(value != null)
	if (value != null) writeFloat(value)
}

private fun DataInputStream.readNullableFloat(): Float? = if (readBoolean()) readFloat() else null

private fun DataInputStream.readSourceKind(): SourceKind {
	val stableCode = readInt()
	return SourceKind.entries.single { it.stableCode == stableCode }
}

private fun DataInputStream.readBoundedCount(): Int = readInt().also {
	require(it in 0..100_000) { "Invalid joined-frame collection size $it" }
}
