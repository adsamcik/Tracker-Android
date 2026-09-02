package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
	fun `version four qualified Pressure bytes are frozen and decode independently`() {
		val payload = qualifiedPressure()

		val first = codec.encode(payload, PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION)
		val retry = codec.encode(payload, PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION)

		first.bytes.contentEquals(retry.bytes) shouldBe true
		first.checksum shouldBe retry.checksum
		first.bytes.toHex() shouldBe QUALIFIED_PRESSURE_V4_GOLDEN_HEX
		codec.decode(
			SourceKind.PRESSURE,
			PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
			QUALIFIED_PRESSURE_V4_GOLDEN_HEX.hexToByteArray(),
		) shouldBe payload
	}

	@Test
	fun `Pressure payload versions cannot silently add or discard qualified evidence`() {
		val qualified = qualifiedPressure()
		val legacy = PressureWindowPayload(4, 1013.2, 0.5, 1012.9f, 1013.5f, 10, 20, 1, 4)

		listOf(1, 2, STEP_BOUNDARY_KIND_PAYLOAD_VERSION).forEach { legacyVersion ->
			shouldThrow<IllegalArgumentException> { codec.encode(qualified, legacyVersion) }
			codec.decode(
				legacy.source,
				legacyVersion,
				codec.encode(legacy, legacyVersion).bytes,
			) shouldBe legacy
		}
		shouldThrow<IllegalArgumentException> {
			codec.encode(legacy, PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION)
		}
	}

	@Test
	fun `version four rejects malformed Pressure quality and wire tails`() {
		val valid = qualifiedPressure()
		val oneSample = qualifiedOneSamplePressure()
		listOf(
			valid.copy(slopeHectopascalsPerSecond = Double.NaN),
			valid.copy(rSquared = 1.1),
			valid.copy(expectedSampleCount = 3),
			valid.copy(lastProviderSequence = 5L),
			valid.copy(maximumInterSampleGapNanos = -1L),
			valid.copy(sensorAccuracy = PressureSensorAccuracy.LEGACY_UNAVAILABLE),
			valid.copy(closureKind = PressureWindowClosureKind.LEGACY_UNAVAILABLE),
			oneSample.copy(
				minimumHectopascals = 999f,
				maximumHectopascals = 1_001f,
			),
			oneSample.copy(
				lastHectopascals = 1_001f,
				maximumHectopascals = 1_001f,
			),
			oneSample.copy(meanHectopascals = 999.0),
			oneSample.copy(sumSquaredDeviations = 1.0),
		).forEach { malformed ->
			shouldThrow<IllegalArgumentException> {
				codec.encode(malformed, PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION)
			}
		}

		val encoded = codec.encode(valid, PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION).bytes
		shouldThrow<java.io.EOFException> {
			codec.decode(
				valid.source,
				PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
				encoded.copyOf(encoded.size - 1),
			)
		}
		shouldThrow<IllegalArgumentException> {
			codec.decode(
				valid.source,
				PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
				encoded + byteArrayOf(0),
			)
		}
		val unknownClosure = encoded.copyOf().also { it[it.lastIndex] = 99 }
		shouldThrow<IllegalStateException> {
			codec.decode(
				valid.source,
				PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
				unknownClosure,
			)
		}
	}

	@Test
	fun `version four rejects independently encoded Pressure sequence cardinality mismatch`() {
		shouldThrow<IllegalArgumentException> {
			codec.decode(
				SourceKind.PRESSURE,
				PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
				MISMATCHED_PRESSURE_SEQUENCE_V4_HEX.hexToByteArray(),
			)
		}
	}

	@Test
	fun `version four rejects independently encoded one sample statistics mismatch`() {
		shouldThrow<IllegalArgumentException> {
			codec.decode(
				SourceKind.PRESSURE,
				PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
				MISMATCHED_ONE_SAMPLE_PRESSURE_V4_HEX.hexToByteArray(),
			)
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

private fun qualifiedPressure() = PressureWindowPayload(
	sampleCount = 4,
	meanHectopascals = 1_001.5,
	sumSquaredDeviations = 5.0,
	minimumHectopascals = 1_000f,
	maximumHectopascals = 1_003f,
	windowStartElapsedRealtimeNanos = 1_000_000_000L,
	windowEndElapsedRealtimeNanos = 4_000_000_000L,
	firstProviderSequence = 1L,
	lastProviderSequence = 4L,
	firstHectopascals = 1_000f,
	lastHectopascals = 1_003f,
	slopeHectopascalsPerSecond = 1.0,
	rSquared = 1.0,
	sensorAccuracy = PressureSensorAccuracy.LOW,
	effectiveSamplePeriodMicros = 1_000_000,
	effectiveMaximumReportLatencyMicros = 5_000_000,
	targetWindowDurationNanos = 4_000_000_000L,
	expectedSampleCount = 4,
	maximumInterSampleGapNanos = 1_000_000_000L,
	closureKind = PressureWindowClosureKind.TARGET_ELAPSED,
)

private fun qualifiedOneSamplePressure() = PressureWindowPayload(
	sampleCount = 1,
	meanHectopascals = 1_000.0,
	sumSquaredDeviations = 0.0,
	minimumHectopascals = 1_000f,
	maximumHectopascals = 1_000f,
	windowStartElapsedRealtimeNanos = 1_000_000_000L,
	windowEndElapsedRealtimeNanos = 1_000_000_000L,
	firstProviderSequence = 1L,
	lastProviderSequence = 1L,
	firstHectopascals = 1_000f,
	lastHectopascals = 1_000f,
	sensorAccuracy = PressureSensorAccuracy.HIGH,
	effectiveSamplePeriodMicros = 1_000_000,
	effectiveMaximumReportLatencyMicros = 0,
	targetWindowDurationNanos = 4_000_000_000L,
	expectedSampleCount = 4,
	maximumInterSampleGapNanos = 0L,
	closureKind = PressureWindowClosureKind.SOURCE_BOUNDARY,
)

private const val QUALIFIED_PRESSURE_V4_GOLDEN_HEX =
	"0000000500000004408f4c00000000004014000000000000447a0000447ac000" +
		"000000003b9aca0000000000ee6b280000000000000000010000000000000004" +
		"447a0000447ac000013ff0000000000000013ff000000000000000000003000f4240" +
		"004c4b4000000000ee6b280000000004000000003b9aca0000000001"

private const val MISMATCHED_PRESSURE_SEQUENCE_V4_HEX =
	"0000000500000004408f4c00000000004014000000000000447a0000447ac000" +
		"000000003b9aca0000000000ee6b280000000000000000010000000000000005" +
		"447a0000447ac000013ff0000000000000013ff000000000000000000003000f4240" +
		"004c4b4000000000ee6b280000000004000000003b9aca0000000001"

private const val MISMATCHED_ONE_SAMPLE_PRESSURE_V4_HEX =
	"0000000500000001408f4000000000003ff0000000000000447a0000447a0000" +
		"000000003b9aca00000000003b9aca0000000000000000010000000000000001" +
		"447a0000447a0000000000000005000f42400000000000000000ee6b2800" +
		"00000004000000000000000000000002"

private fun String.hexToByteArray(): ByteArray = chunked(2)
	.map { it.toInt(16).toByte() }
	.toByteArray()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
