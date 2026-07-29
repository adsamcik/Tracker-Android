package com.adsamcik.tracker.impexp.exporter.research

import android.content.Context
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.stats.api.research.RESEARCH_EVIDENCE_SCHEMA_V2
import com.adsamcik.tracker.stats.api.research.ResearchClockDomain
import com.adsamcik.tracker.stats.api.research.ResearchControlEventKind
import com.adsamcik.tracker.stats.api.research.ResearchControlEventRecord
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceCapabilities
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceEnvelope
import com.adsamcik.tracker.stats.api.research.ResearchHorizontalEstimatorDecision
import com.adsamcik.tracker.stats.api.research.ResearchHorizontalEstimatorRecord
import com.adsamcik.tracker.stats.api.research.ResearchLifecycleBoundary
import com.adsamcik.tracker.stats.api.research.ResearchLossRange
import com.adsamcik.tracker.stats.api.research.ResearchPrivacyClass
import com.adsamcik.tracker.stats.api.research.ResearchTerminalIntegrityRecord
import com.adsamcik.tracker.stats.api.research.ResearchTraceIdentity
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleRecord
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleTransition
import com.adsamcik.tracker.stats.api.research.ResearchTrackingMode
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchControlEvidencePayloadExporterTest {

	@Test
	fun `writes a lossless V2 control envelope projection`() = runTest {
		val identity = ResearchTraceIdentity(traceId = "trace-1", runId = "run-1")
		val estimator = ResearchEvidenceEnvelope(
			identity = identity,
			sequence = 10L,
			clockDomain = ResearchClockDomain(
				id = "boot-1",
				kind = ResearchClockDomain.Kind.ANDROID_ELAPSED_REALTIME,
			),
			privacyClass = ResearchPrivacyClass.ENCRYPTED_RESEARCH,
			algorithmVersions = mapOf("horizontalEstimator" to "v1"),
			capabilities = ResearchEvidenceCapabilities(
				controlTraceEvents = true,
				logicalTrackingLifecycle = true,
				acquisitionDecisions = true,
				horizontalEstimatorDecisions = true,
				replayDigests = true,
			),
			lifecycleBoundary = ResearchLifecycleBoundary.RESUME,
			lossRanges = listOf(ResearchLossRange(0L, 2L, ResearchLossRange.Reason.BUFFER_OVERFLOW)),
			terminalIntegrity = ResearchTerminalIntegrityRecord(
				envelopeCount = 2L,
				firstSequence = 10L,
				lastSequence = 11L,
				lossRangeCount = 1L,
				lostEventCount = 3L,
				complete = false,
			),
			record = ResearchHorizontalEstimatorRecord(
				logicalTrackingId = "logical-1",
				estimatorVersion = "enu-v1",
				decision = ResearchHorizontalEstimatorDecision.UPDATED,
				eventEpochMs = 100L,
				sourceElapsedNanos = 200L,
				deltaNanos = 10L,
				sourceSequence = 8L,
				stateDimension = 2,
				stateBefore = listOf(1.0, 2.0),
				stateAfter = listOf(3.0, 4.0),
				covarianceBefore = listOf(1.0, 0.0, 0.0, 1.0),
				covarianceAfter = listOf(2.0, 0.0, 0.0, 2.0),
				processNoise = listOf(0.1, 0.0, 0.0, 0.1),
				measurementEastM = 5.0,
				measurementNorthM = -6.0,
				measurementCovariance = listOf(3.0, 0.0, 0.0, 3.0),
				innovation = listOf(0.2, -0.3),
				normalizedInnovationSquared = 1.5,
				accepted = true,
			),
			schemaVersion = RESEARCH_EVIDENCE_SCHEMA_V2,
		)
		val lifecycle = estimator.copy(
			sequence = 11L,
			lifecycleBoundary = null,
			lossRanges = emptyList(),
			terminalIntegrity = null,
			record = ResearchTrackingLifecycleRecord(
				logicalTrackingId = "logical-1",
				transition = ResearchTrackingLifecycleTransition.RESUMED,
				trackingMode = ResearchTrackingMode.AUTOMATIC,
				resumedFromLogicalTrackingId = "logical-0",
			),
		)
		val output = ByteArrayOutputStream()

		val result = ResearchControlEvidencePayloadExporter { listOf(estimator, lifecycle) }
			.export(mockk<Context>(), emptySequence(), output, null)

		result shouldBe ExportResult.Success
		val lines = String(output.toByteArray(), Charsets.UTF_8)
			.lineSequence()
			.filter { it.isNotBlank() }
			.toList()
		JSONObject(lines[0]).apply {
			getBoolean("encryptedRequired") shouldBe true
			getBoolean("complete") shouldBe false
		}
		val estimatorEnvelope = JSONObject(lines[1])
		estimatorEnvelope.getString("lifecycleBoundary") shouldBe "RESUME"
		estimatorEnvelope.getJSONObject("terminalIntegrity").getLong("lostEventCount") shouldBe 3L
		estimatorEnvelope.getJSONObject("capabilities").getBoolean("rawPressureEvents") shouldBe false
		val estimatorRecord = estimatorEnvelope.getJSONObject("record")
		estimatorRecord.getLong("deltaNanos") shouldBe 10L
		estimatorRecord.getInt("stateDimension") shouldBe 2
		estimatorRecord.getJSONArray("stateBefore").getDouble(0) shouldBe 1.0
		estimatorRecord.getJSONArray("covarianceAfter").getDouble(3) shouldBe 2.0
		estimatorRecord.getDouble("measurementEastM") shouldBe 5.0
		estimatorRecord.has("latitudeE7") shouldBe false
		JSONObject(lines[2]).getJSONObject("record").getString("resumedFromLogicalTrackingId") shouldBe "logical-0"
	}

	@Test
	fun `rejects a non-encrypted V2 control envelope`() = runTest {
		val unencrypted = ResearchEvidenceEnvelope(
			identity = ResearchTraceIdentity(traceId = "trace-1"),
			sequence = 0L,
			clockDomain = ResearchClockDomain("boot-1", ResearchClockDomain.Kind.ANDROID_ELAPSED_REALTIME),
			privacyClass = ResearchPrivacyClass.NONE,
			record = ResearchControlEventRecord(
				logicalTrackingId = "logical-1",
				kind = ResearchControlEventKind.TRACKING_ENABLED,
			),
			schemaVersion = RESEARCH_EVIDENCE_SCHEMA_V2,
		)

		val result = ResearchControlEvidencePayloadExporter { listOf(unencrypted) }
			.export(mockk<Context>(), emptySequence(), ByteArrayOutputStream(), null)

		(result is ExportResult.Error) shouldBe true
	}
}
