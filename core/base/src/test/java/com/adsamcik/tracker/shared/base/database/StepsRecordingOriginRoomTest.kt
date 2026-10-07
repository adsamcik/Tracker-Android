package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsRecordingOriginRoomTest {

	@Test fun `Room raw readers authenticate nullable recording origin and reject wrong storage type`() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		val db = AppDatabase.testDatabase(context)
		try {
			val legacy = binding(1, null)
			val origin = binding(2, 2)
			val continuation = binding(3, 2)
			db.sourceSessionDao().insertManifestSources(listOf(legacy, origin, continuation))
			listOf(legacy, origin, continuation).forEach { expected ->
				db.sourceSessionDao().rawManifestSources("session", expected.manifestRevision, 2)
					.single().validatedOrNull() shouldBe expected
			}
			db.sourceSessionDao().rawManifestSourcesForRevisions("session", listOf(1, 2, 3), 4)
				.map { it.validatedOrNull() } shouldBe listOf(legacy, origin, continuation)
			db.openHelper.writableDatabase.execSQL("UPDATE session_manifest_source SET " +
				"steps_recording_manifest_revision='invalid' WHERE manifest_revision=3")
			db.sourceSessionDao().rawManifestSources("session", 3, 2).single().validatedOrNull() shouldBe null
		} finally { db.close() }
	}

	@Test fun `checksum retains legacy encoding and covers marked origin on every membership`() {
		val manifest = manifest()
		val legacy = binding(3, null)
		val marked = binding(3, 2)
		val legacyChecksum = SessionManifestIntegrity.compute(manifest, listOf(legacy))
		val markedChecksum = SessionManifestIntegrity.compute(manifest, listOf(marked))
		// Pinned fixture for the pre-origin v2 encoding, independent of the new compute branch.
		legacyChecksum shouldBe "be2673a6913992ede3e2e6f731a23d17575c32dfa66969605904ec06170a2440"
		assertFalse(legacyChecksum == markedChecksum)
		assertTrue(SessionManifestIntegrity.verify(manifest.copy(manifestChecksum = legacyChecksum), listOf(legacy)))
		assertTrue(SessionManifestIntegrity.verify(manifest.copy(manifestChecksum = markedChecksum), listOf(marked)))
		assertFalse(SessionManifestIntegrity.verify(manifest.copy(manifestChecksum = markedChecksum), listOf(marked.copy(stepsRecordingManifestRevision = 1))))
		assertFalse(SessionManifestIntegrity.verify(manifest.copy(manifestChecksum = markedChecksum), listOf(legacy)))
	}

	private fun binding(revision: Long, origin: Long?) = SessionManifestSourceEntity(
		logicalTrackingId = "session", manifestRevision = revision, sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE, consentEpoch = 1, persistenceEligible = true, qosCode = 2,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
		writerOwnerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
		stepsRecordingManifestRevision = origin,
	)

	private fun manifest() = SessionManifestVersionEntity(
		logicalTrackingId = "session", manifestRevision = 3, serviceRunId = "run", sessionMode = "MANUAL",
		sourcePolicyRevision = 3, acquisitionPlanRevision = 3, rolloutRevision = 1, startOrigin = "POLICY_RECONCILIATION",
		effectiveBootId = "boot", effectiveElapsedRealtimeNanos = 100, effectiveWallTimeMs = 100,
		zoneId = "UTC", automationEpoch = null, changeReason = "POLICY_RECONCILIATION", manifestChecksum = "",
	)
}
