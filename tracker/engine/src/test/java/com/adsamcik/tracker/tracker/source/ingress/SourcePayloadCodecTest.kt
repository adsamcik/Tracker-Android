package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
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
}
