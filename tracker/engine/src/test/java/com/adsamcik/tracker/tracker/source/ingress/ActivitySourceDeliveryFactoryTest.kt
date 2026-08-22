package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class ActivitySourceDeliveryFactoryTest {
	private val subject = ActivitySourceDeliveryFactory()

	@Test
	fun `identity is independent of callback ordering while original indexes remain mapping metadata`() {
		val first = batch(
			recognitions = listOf(recognition(30L), recognition(10L, confidence = 70)),
			transitions = listOf(transition(20L), transition(10L)),
		)
		val reordered = first.copy(
			recognitions = first.recognitions.reversed(),
			transitions = first.transitions.reversed(),
		)

		val a = subject.create(first, identity())
		val b = subject.create(reordered, identity())

		a.candidate.identity shouldBe b.candidate.identity
		a.originalEvents.shouldContainExactly(
			ActivityOriginalEvent.Recognition(1),
			ActivityOriginalEvent.Transition(1),
			ActivityOriginalEvent.Transition(0),
			ActivityOriginalEvent.Recognition(0),
		)
		b.originalEvents.shouldContainExactly(
			ActivityOriginalEvent.Recognition(0),
			ActivityOriginalEvent.Transition(0),
			ActivityOriginalEvent.Transition(1),
			ActivityOriginalEvent.Recognition(1),
		)
	}

	@Test
	fun `identity ignores receipt and registration authorization transport metadata`() {
		val evidence = batch(recognitions = listOf(recognition(10L)))
		val changedReceipt = evidence.copy(
			receivedElapsedRealtimeNanos = 9_000L,
			receivedWallTimeMs = 20_000L,
		)
		val changedIdentity = identity().copy(
			sourceInstanceId = "another-instance",
			registrationGeneration = 99L,
			collectedDataEpoch = 88L,
			clockDomainId = "another-boot",
			physicalConfigurationFingerprint = "another-config",
		)

		subject.create(evidence, identity()).candidate.identity shouldBe
			subject.create(changedReceipt, changedIdentity).candidate.identity
	}

	@Test
	fun `exact duplicate native events preserve multiplicity in identity and units`() {
		val one = batch(recognitions = listOf(recognition(10L)))
		val two = one.copy(recognitions = listOf(recognition(10L), recognition(10L)))

		(subject.create(one, identity()).candidate.identity ==
			subject.create(two, identity()).candidate.identity) shouldBe false
		subject.create(two, identity()).candidate.units.size shouldBe 2
	}

	@Test
	fun `every source-native field changes identity`() {
		val original = batch(recognitions = listOf(recognition(10L)))
		val originalIdentity = subject.create(original, identity()).candidate.identity
		val variants = listOf(
			original.copy(recognitions = listOf(recognition(11L))),
			original.copy(recognitions = listOf(recognition(10L, DetectedActivityType.RUNNING))),
			original.copy(recognitions = listOf(recognition(10L, confidence = 89))),
			batch(transitions = listOf(transition(10L))),
			batch(transitions = listOf(transition(10L, DetectedActivityType.RUNNING))),
			batch(transitions = listOf(transition(10L, transitionType = ActivityTransitionType.EXIT))),
		)

		variants.forEach { variant ->
			(subject.create(variant, identity()).candidate.identity == originalIdentity) shouldBe false
		}
	}

	private fun batch(
		recognitions: List<ActivityRecognitionEvidence> = emptyList(),
		transitions: List<ActivityTransitionEvidence> = emptyList(),
	) = ActivityRecognitionEvidenceBatch(
		receivedElapsedRealtimeNanos = 5_000L,
		receivedWallTimeMs = 10_000L,
		registrationIdentity = identity(),
		recognitions = recognitions,
		transitions = transitions,
	)

	private fun recognition(
		at: Long,
		type: DetectedActivityType = DetectedActivityType.WALKING,
		confidence: Int = 90,
	) = ActivityRecognitionEvidence(type, confidence, at)

	private fun transition(
		at: Long,
		type: DetectedActivityType = DetectedActivityType.WALKING,
		transitionType: ActivityTransitionType = ActivityTransitionType.ENTER,
	) = ActivityTransitionEvidence(type, transitionType, at)

	private fun identity() = ActivityRegistrationIdentity(
		sourceInstanceId = "activity-instance",
		registrationGeneration = 2L,
		collectedDataEpoch = 3L,
		clockDomainId = "boot-1",
		physicalConfigurationFingerprint = "physical-config",
	)
}
