package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.junit.Test

class CellHistoryComposerTest {
	@Test
	fun `legacy segment is unavailable and cannot fabricate a Cell value`() {
		val entry = requireNotNull(
			CellHistoryComposer.composeSelected(legacySegment(), CellHistorySnapshot.empty(
				CellMembershipExpansion(listOf(legacySegment()), emptyMap()),
			), DENY_EXECUTION),
		)

		entry.state shouldBe CellHistoryProductState.UNAVAILABLE
		entry.causes shouldBe setOf(CellHistoryCause.SOURCE_NOT_CAPTURED)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `bounded membership overflow fails closed without observations`() {
		val segment = capturedSegment()
		val expansion = CellMembershipExpansion(
			segments = listOf(segment),
			failures = mapOf("logical" to CellHistoryCause.READ_BUDGET_EXCEEDED),
			overflow = true,
		)
		val entry = requireNotNull(CellHistoryComposer.composeSelected(
			segment,
			CellHistorySnapshot.empty(expansion).copy(overflow = true),
			DENY_EXECUTION,
		))

		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.READ_BUDGET_EXCEEDED)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `incomplete replacement membership fails closed without exposing a physical fragment`() {
		val segment = capturedSegment()
		val expansion = CellMembershipExpansion(
			segments = listOf(segment),
			failures = mapOf("logical" to CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID),
		)

		val entry = requireNotNull(CellHistoryComposer.composeSelected(
			segment,
			CellHistorySnapshot.empty(expansion),
			DENY_EXECUTION,
		))

		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `Cell plan evidence requires canonical bounded bytes with no trailing payload`() {
		val payload = cellPlanPayload()
		val valid = SourceDesiredPlanEntity(7L, CELL_SOURCE, 1, payload, sha256(payload))

		CellHistoryPlanIntegrity.decode(valid)?.maximumAgeMs shouldBe 1_000L
		val trailingPayload = payload + byteArrayOf(0)
		CellHistoryPlanIntegrity.decode(
			valid.copy(payload = trailingPayload, payloadChecksum = sha256(trailingPayload)),
		) shouldBe null
	}

	private fun legacySegment() = segment(logicalId = null, runId = null)

	private fun capturedSegment() = segment(logicalId = "logical", runId = "run")

	private fun segment(logicalId: String?, runId: String?) = SessionSegment(
		id = 1L,
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = 2_000L,
		logicalTrackingId = logicalId,
		serviceRunId = runId,
	)

	private fun cellPlanPayload(): ByteArray = ByteArrayOutputStream().use { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(1)
			output.writeUTF("CELL")
			output.writeLong(7L)
			output.writeUTF("OBSERVE_CHANGES")
			output.writeLong(60_000L)
			output.writeLong(1_000L)
			output.writeInt(0)
			output.writeLong(1_000L)
			output.writeLong(60_000L)
			output.writeDouble(2.0)
		}
		bytes.toByteArray()
	}

	private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(payload).joinToString("") { "%02x".format(it) }

	private companion object {
		val DENY_EXECUTION = SourceProductLaneExecutionAuthority { false }
	}
}
