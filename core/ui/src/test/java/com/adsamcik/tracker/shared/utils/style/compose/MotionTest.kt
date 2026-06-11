package com.adsamcik.tracker.shared.utils.style.compose

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Motion tokens")
class MotionTest {

	@Nested
	@DisplayName("RidgelineMotion named springs")
	inner class NamedSprings {

		@Test
		fun `Snap has high stiffness for immediate response`() {
			RidgelineMotion.Snap.stiffness shouldBe 1500f
		}

		@Test
		fun `Snap has underdamped ratio for slight overshoot`() {
			RidgelineMotion.Snap.dampingRatio shouldBe 0.75f
		}

		@Test
		fun `Settle is critically damped (no overshoot)`() {
			RidgelineMotion.Settle.dampingRatio shouldBe 1.0f
		}

		@Test
		fun `Settle has moderate stiffness`() {
			RidgelineMotion.Settle.stiffness shouldBe 400f
		}

		@Test
		fun `Respond has quick settle with tiny overshoot`() {
			RidgelineMotion.Respond.dampingRatio shouldBe 0.82f
			RidgelineMotion.Respond.stiffness shouldBe 800f
		}

		@Test
		fun `Crest has celebratory bounce (low damping)`() {
			RidgelineMotion.Crest.dampingRatio shouldBe 0.55f
			RidgelineMotion.Crest.stiffness shouldBe 300f
		}

		@Test
		fun `Drift is critically damped with low stiffness`() {
			RidgelineMotion.Drift.dampingRatio shouldBe 1.0f
			RidgelineMotion.Drift.stiffness shouldBe 50f
		}

		@Test
		fun `Surge is theatrical with dramatic bounce`() {
			RidgelineMotion.Surge.dampingRatio shouldBe 0.58f
			RidgelineMotion.Surge.stiffness shouldBe 180f
		}

		@Test
		fun `Snap is stiffer than Settle`() {
			RidgelineMotion.Snap.stiffness shouldBeGreaterThan RidgelineMotion.Settle.stiffness
		}

		@Test
		fun `Drift is the least stiff spring`() {
			RidgelineMotion.Drift.stiffness shouldBeLessThan RidgelineMotion.Settle.stiffness
			RidgelineMotion.Drift.stiffness shouldBeLessThan RidgelineMotion.Surge.stiffness
			RidgelineMotion.Drift.stiffness shouldBeLessThan RidgelineMotion.Crest.stiffness
		}

		@Test
		fun `Crest has more bounce than Snap`() {
			RidgelineMotion.Crest.dampingRatio shouldBeLessThan RidgelineMotion.Snap.dampingRatio
		}
	}

	@Nested
	@DisplayName("generic typed spring constructors")
	inner class GenericSprings {

		@Test
		fun `ridgelineSnap matches Snap parameters`() {
			val spec = ridgelineSnap<Float>()
			spec.dampingRatio shouldBe 0.75f
			spec.stiffness shouldBe 1500f
		}

		@Test
		fun `ridgelineSettle matches Settle parameters`() {
			val spec = ridgelineSettle<Float>()
			spec.dampingRatio shouldBe 1.0f
			spec.stiffness shouldBe 400f
		}

		@Test
		fun `ridgelineRespond matches Respond parameters`() {
			val spec = ridgelineRespond<Float>()
			spec.dampingRatio shouldBe 0.82f
			spec.stiffness shouldBe 800f
		}

		@Test
		fun `ridgelineCrest matches Crest parameters`() {
			val spec = ridgelineCrest<Float>()
			spec.dampingRatio shouldBe 0.55f
			spec.stiffness shouldBe 300f
		}

		@Test
		fun `ridgelineDrift matches Drift parameters`() {
			val spec = ridgelineDrift<Float>()
			spec.dampingRatio shouldBe 1.0f
			spec.stiffness shouldBe 50f
		}

		@Test
		fun `ridgelineSurge matches Surge parameters`() {
			val spec = ridgelineSurge<Float>()
			spec.dampingRatio shouldBe 0.58f
			spec.stiffness shouldBe 180f
		}
	}

	@Nested
	@DisplayName("RidgelineDurations")
	inner class Durations {

		@Test
		fun `INSTANT_MS is 50`() {
			RidgelineDurations.INSTANT_MS shouldBe 50
		}

		@Test
		fun `QUICK_MS is 150`() {
			RidgelineDurations.QUICK_MS shouldBe 150
		}

		@Test
		fun `STANDARD_MS is 300`() {
			RidgelineDurations.STANDARD_MS shouldBe 300
		}

		@Test
		fun `EMPHASIZED_MS is 500`() {
			RidgelineDurations.EMPHASIZED_MS shouldBe 500
		}

		@Test
		fun `EXPRESSIVE_MS is 800`() {
			RidgelineDurations.EXPRESSIVE_MS shouldBe 800
		}

		@Test
		fun `AMBIENT_MS is 2000`() {
			RidgelineDurations.AMBIENT_MS shouldBe 2000
		}

		@Test
		fun `BACKGROUND_LOOP_MS is 4000`() {
			RidgelineDurations.BACKGROUND_LOOP_MS shouldBe 4000
		}

		@Test
		fun `durations increase monotonically`() {
			RidgelineDurations.INSTANT_MS shouldBeLessThan RidgelineDurations.QUICK_MS
			RidgelineDurations.QUICK_MS shouldBeLessThan RidgelineDurations.STANDARD_MS
			RidgelineDurations.STANDARD_MS shouldBeLessThan RidgelineDurations.EMPHASIZED_MS
			RidgelineDurations.EMPHASIZED_MS shouldBeLessThan RidgelineDurations.EXPRESSIVE_MS
			RidgelineDurations.EXPRESSIVE_MS shouldBeLessThan RidgelineDurations.AMBIENT_MS
			RidgelineDurations.AMBIENT_MS shouldBeLessThan RidgelineDurations.BACKGROUND_LOOP_MS
		}
	}

	@Nested
	@DisplayName("sheet springs")
	inner class SheetSprings {

		@Test
		fun `SheetExpandSpring has slight overshoot`() {
			SheetExpandSpring.dampingRatio shouldBe 0.85f
			SheetExpandSpring.stiffness shouldBe 600f
		}

		@Test
		fun `SheetDismissSpring is critically damped`() {
			SheetDismissSpring.dampingRatio shouldBe 1.0f
			SheetDismissSpring.stiffness shouldBe 600f
		}

		@Test
		fun `expand and dismiss share same stiffness`() {
			SheetExpandSpring.stiffness shouldBe SheetDismissSpring.stiffness
		}
	}

	@Nested
	@DisplayName("LoadingMotion")
	inner class LoadingMotionTest {

		@Test
		fun `EnterDuration matches STANDARD_MS`() {
			LoadingMotion.EnterDuration shouldBe RidgelineDurations.STANDARD_MS
		}

		@Test
		fun `ExitDuration matches QUICK_MS`() {
			LoadingMotion.ExitDuration shouldBe RidgelineDurations.QUICK_MS
		}

		@Test
		fun `PulseDuration is 1200`() {
			LoadingMotion.PulseDuration shouldBe 1200
		}

		@Test
		fun `PulseAlphaMin is less than PulseAlphaMax`() {
			LoadingMotion.PulseAlphaMin shouldBeLessThan LoadingMotion.PulseAlphaMax
		}

		@Test
		fun `PulseAlphaMin is 0_08`() {
			LoadingMotion.PulseAlphaMin shouldBe 0.08f
		}

		@Test
		fun `PulseAlphaMax is 0_16`() {
			LoadingMotion.PulseAlphaMax shouldBe 0.16f
		}
	}
}
