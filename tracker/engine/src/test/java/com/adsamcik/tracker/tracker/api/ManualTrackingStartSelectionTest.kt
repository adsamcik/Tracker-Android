package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicy
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import io.kotest.matchers.shouldBe
import org.junit.Test

class ManualTrackingStartSelectionTest {
	@Test
	fun `quality and unrelated ambient changes preserve original selected capture choices`() {
		val selected = snapshot(1L, setOf(TrackingSourceComponent.WIFI, TrackingSourceComponent.CELL))
		val qualityChanged = selected.copy(revision = 2L, policies = selected.policies.mapValues { (_, row) ->
			row.copy(policyRevision = 2L, qos = if (row.enabled) {
				SourceQos.RESPONSIVE
			} else {
				SourceQos.OFF
			},
				ambientConsentEpoch = if (row.source == TrackingSourceComponent.STEPS) {
					9L
				} else {
					null
				},
				ambientPersistenceEligible = row.source == TrackingSourceComponent.STEPS)
		})
		qualityChanged.manualStartSelection() shouldBe selected.manualStartSelection()
		selected.manualStartSelection().captureConsentEpochs.keys shouldBe setOf(TrackingCaptureSource.WIFI, TrackingCaptureSource.CELL)
	}

	@Test
	fun `changing any selected source or its capture consent changes original choices`() {
		for (source in TrackingSourceComponent.entries) {
			val original = snapshot(1L, setOf(source))
			val turnedOff = snapshot(2L, emptySet())
			(original.manualStartSelection() == turnedOff.manualStartSelection()) shouldBe false
			val reenabled = snapshot(3L, setOf(source), captureEpoch = 2L)
			(original.manualStartSelection() == reenabled.manualStartSelection()) shouldBe false
		}
	}

	private fun snapshot(revision: Long, enabled: Set<TrackingSourceComponent>, captureEpoch: Long = 1L) =
		SourcePolicySnapshot(revision, TrackingSourceComponent.entries.associateWith { source ->
			val chosen = source in enabled
			SourcePolicy(source, chosen, if (chosen) {
				SourceQos.BALANCED
			} else {
				SourceQos.OFF
			},
				locationMinTimeSeconds = 2.takeIf { source == TrackingSourceComponent.LOCATION },
				locationMinDistanceMeters = 10.takeIf { source == TrackingSourceComponent.LOCATION },
				locationRequiredAccuracyMeters = 50.takeIf { source == TrackingSourceComponent.LOCATION },
				captureConsentEpoch = captureEpoch.takeIf { chosen }, controlConsentEpoch = null,
				ambientConsentEpoch = null, capturePersistenceEligible = chosen,
				controlPersistenceEligible = false, ambientPersistenceEligible = false,
				effectiveTime = SourcePolicyEffectiveTime("boot", 1L, 1L), policyRevision = revision)
		})
}
