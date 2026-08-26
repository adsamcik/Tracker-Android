package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptOutcome
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptPayload
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class SourcePayloadCodecTest {
	private val codec = DefaultSourcePayloadCodec()

	@Test
	fun `all durable payload kinds have a stable version-one round trip`() {
		val payloads = listOf<SourcePayload>(
			LocationFixPayload(50.1, 14.4, 5f, 200.0, 3f, 2f, 90f, "gps"),
			ActivityTransitionPayload(3, 1, 100L),
			StepCounterWindowPayload("boot", 100, 120, 20, 10, 20, 1, 2, false),
			PressureWindowPayload(4, 1013.2, 0.5, 1012.9f, 1013.5f, 10, 20, 1, 4),
			WifiResultSnapshotPayload(
				listOf(WifiAccessPointEvidence("token", 5_200, -60)),
				platformTimestampMs = 100L,
				resultAgeMs = 5L,
			),
			CellSnapshotPayload(
				subscriptionId = 1,
				observations = listOf(CellObservationEvidence("token", "LTE", true, -90, 100L)),
				refreshOutcome = CellRefreshOutcome.CALLBACK,
			),
		)

		payloads.forEach { payload ->
			val encoded = codec.encode(payload, 1)
			codec.decode(payload.source, 1, encoded.bytes) shouldBe payload
			encoded.checksum.length shouldBe 64
		}
	}

	@Test
	fun `version one bytes and decoder remain frozen to the released v27 contract`() {
		val released = listOf(
			LocationFixPayload(50.1, 14.4, 5f, 200.0, 3f, 2f, 90f, "gps") to
				"0000000140490ccccccccccd402ccccccccccccd40a00000014069000000000000014040000001400000000142b400000003677073",
			ActivityTransitionPayload(3, 1, 100L) to
				"0000000200000003000000010000000000000064",
			ActivityRecognitionPayload(7, 87, 101L) to
				"000000030000000700000057010000000000000065",
			StepCounterWindowPayload("boot", 100, 120, 20, 10, 20, 1, 2, false) to
				"000000040004626f6f74000000000000006400000000000000780000000000000014000000000000000a" +
				"00000000000000140000000000000001000000000000000200",
			PressureWindowPayload(4, 1013.2, 0.5, 1012.9f, 1013.5f, 10, 20, 1, 4) to
				"0000000500000004408fa9999999999a3fe0000000000000447d399a447d6000" +
				"000000000000000a000000000000001400000000000000010000000000000004",
			WifiScanAttemptPayload("scan-1", WifiScanAttemptOutcome.RESULTS_AVAILABLE, 2, 5) to
				"0000000600067363616e2d31000000030100000002010000000000000005",
			WifiResultSnapshotPayload(
				listOf(WifiAccessPointEvidence("token", 5_200, -60)),
				platformTimestampMs = 100,
				resultAgeMs = 5,
			) to "00000007000000010005746f6b656e00001450ffffffc4010000000000000064010000000000000005",
			CellSnapshotPayload(
				subscriptionId = 1,
				observations = listOf(CellObservationEvidence("token", "LTE", true, -90, 100)),
				refreshOutcome = CellRefreshOutcome.CALLBACK,
			) to "000000080100000001000000010005746f6b656e00034c54450101ffffffa601000000000000006400000000",
		)

		released.forEach { (payload, goldenHex) ->
			val golden = goldenHex.hexToByteArray()
			codec.encode(payload, 1).bytes.toHex() shouldBe goldenHex
			LegacyV27SourcePayloadDecoder.decode(payload.source, golden) shouldBe payload
			codec.decode(payload.source, 1, golden) shouldBe payload
		}
	}

	@Test
	fun `version two preserves each Wi-Fi observation time`() {
		val payload = WifiResultSnapshotPayload(
			accessPoints = listOf(
				WifiAccessPointEvidence("", 2_412, -45, providerTimestampNanos = 100L),
				WifiAccessPointEvidence("", 5_200, -60, providerTimestampNanos = 200L),
			),
			platformTimestampMs = 0L,
			resultAgeMs = 3L,
		)

		val encoded = codec.encode(payload, 2)

		codec.decode(payload.source, 2, encoded.bytes) shouldBe payload
	}

	@Test
	fun `version three preserves explicit Steps boundaries and Long counts`() {
		listOf(
			StepBoundaryKind.BASELINE,
			StepBoundaryKind.COVERED,
			StepBoundaryKind.COUNTER_RESET,
		).forEach { boundaryKind ->
			val covered = boundaryKind == StepBoundaryKind.COVERED
			val payload = StepCounterWindowPayload(
				bootClockDomainId = "boot-v3",
				firstCumulativeCount = if (covered) 0L else Long.MAX_VALUE,
				lastCumulativeCount = Long.MAX_VALUE,
				deltaCount = if (covered) Long.MAX_VALUE else 0L,
				windowStartElapsedRealtimeNanos = 10L,
				windowEndElapsedRealtimeNanos = 20L,
				firstProviderSequence = 1L,
				lastProviderSequence = 2L,
				boundaryKind = boundaryKind,
			)

			codec.decode(payload.source, STEP_BOUNDARY_KIND_PAYLOAD_VERSION,
				codec.encode(payload, STEP_BOUNDARY_KIND_PAYLOAD_VERSION).bytes) shouldBe payload
		}
	}

	@Test
	fun `version three cannot manufacture a legacy-ambiguous Steps boundary`() {
		val ambiguous = StepCounterWindowPayload(
			bootClockDomainId = "boot-v3",
			firstCumulativeCount = 100L,
			lastCumulativeCount = 100L,
			deltaCount = 0L,
			windowStartElapsedRealtimeNanos = 10L,
			windowEndElapsedRealtimeNanos = 10L,
			firstProviderSequence = 1L,
			lastProviderSequence = 1L,
			boundaryKind = StepBoundaryKind.LEGACY_AMBIGUOUS,
		)

		shouldThrow<IllegalArgumentException> {
			codec.encode(ambiguous, STEP_BOUNDARY_KIND_PAYLOAD_VERSION)
		}
	}

	@Test
	fun `legacy reset-shaped Steps bytes remain explicitly ambiguous`() {
		val explicitBaseline = StepCounterWindowPayload(
			bootClockDomainId = "boot-legacy",
			firstCumulativeCount = 100L,
			lastCumulativeCount = 100L,
			deltaCount = 0L,
			windowStartElapsedRealtimeNanos = 10L,
			windowEndElapsedRealtimeNanos = 10L,
			firstProviderSequence = 1L,
			lastProviderSequence = 1L,
			boundaryKind = StepBoundaryKind.BASELINE,
		)

		listOf(1, 2).forEach { legacyVersion ->
			val decoded = codec.decode(
				explicitBaseline.source,
				legacyVersion,
				codec.encode(explicitBaseline, legacyVersion).bytes,
			) as StepCounterWindowPayload

			decoded.boundaryKind shouldBe StepBoundaryKind.LEGACY_AMBIGUOUS
			decoded.deltaCount shouldBe 0L
		}
	}
}

private fun String.hexToByteArray(): ByteArray = chunked(2)
	.map { it.toInt(16).toByte() }
	.toByteArray()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
