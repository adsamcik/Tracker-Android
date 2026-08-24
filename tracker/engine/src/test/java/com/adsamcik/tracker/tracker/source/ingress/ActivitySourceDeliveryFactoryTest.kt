package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import io.kotest.assertions.throwables.shouldThrow
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

		val a = subject.create(first, identity(), automationAuthority())
		val b = subject.create(reordered, identity(), automationAuthority())

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

		subject.create(evidence, identity(), automationAuthority()).candidate.identity shouldBe
			subject.create(
				changedReceipt,
				changedIdentity,
				automationAuthority(AUTOMATION_EPOCH + 1),
			).candidate.identity
	}

	@Test
	fun `exact duplicate native events preserve multiplicity in identity and units`() {
		val one = batch(recognitions = listOf(recognition(10L)))
		val two = one.copy(recognitions = listOf(recognition(10L), recognition(10L)))

		(subject.create(one, identity(), automationAuthority()).candidate.identity ==
			subject.create(two, identity(), automationAuthority()).candidate.identity) shouldBe false
		subject.create(two, identity(), automationAuthority()).candidate.units.size shouldBe 2
	}

	@Test
	fun `every source-native field changes identity`() {
		val original = batch(recognitions = listOf(recognition(10L)))
		val originalIdentity = subject.create(
			original,
			identity(),
			automationAuthority(),
		).candidate.identity
		val variants = listOf(
			original.copy(recognitions = listOf(recognition(11L))),
			original.copy(recognitions = listOf(recognition(10L, DetectedActivityType.RUNNING))),
			original.copy(recognitions = listOf(recognition(10L, confidence = 89))),
			batch(transitions = listOf(transition(10L))),
			batch(transitions = listOf(transition(10L, DetectedActivityType.RUNNING))),
			batch(transitions = listOf(transition(10L, transitionType = ActivityTransitionType.EXIT))),
		)

		variants.forEach { variant ->
			(subject.create(
				variant,
				identity(),
				automationAuthority(),
			).candidate.identity == originalIdentity) shouldBe false
		}
	}

	@Test
	fun `future provider time rejects the whole source delivery`() {
		val batches = listOf(
			batch(recognitions = listOf(recognition(5_001L))),
			batch(transitions = listOf(transition(5_001L))),
			batch(
				recognitions = listOf(recognition(5_000L)),
				transitions = listOf(transition(5_001L)),
			),
		)

		batches.forEach { futureBatch ->
			shouldThrow<IllegalArgumentException> {
				subject.create(futureBatch, identity(), automationAuthority())
			}
		}
	}

	@Test
	fun `provider time equal to receipt preserves the provider observation`() {
		val delivery = subject.create(
			batch(recognitions = listOf(recognition(5_000L))),
			identity(),
			automationAuthority(),
		)
		val evidence = delivery.candidate.units.single().evidence

		evidence.observedElapsedRealtimeNanos shouldBe 5_000L
		evidence.receivedElapsedRealtimeNanos shouldBe 5_000L
		evidence.wallTimeMs shouldBe 10_000L
		evidence.wallTimeUncertaintyMs shouldBe 1L
		evidence.quality.flags.contains(
			com.adsamcik.tracker.tracker.source.model.SourceQualityFlag.CLOCK_UNCERTAIN,
		) shouldBe false
	}

	@Test
	fun `delayed observations before the current epoch remain facts without automation authority`() {
		val delivery = subject.create(
			batch(
				recognitions = listOf(
					recognition(99L),
					recognition(100L),
				),
			),
			identity(),
			automationAuthority(effectiveElapsedRealtimeNanos = 100L),
		)

		delivery.candidate.units.map { it.evidence.activityAutomationEpoch } shouldContainExactly
			listOf(null, AUTOMATION_EPOCH)
	}

	@Test
	fun `suppressed current epoch cannot authorize automatic start`() {
		val delivery = subject.create(
			batch(recognitions = listOf(recognition(100L))),
			identity(),
			automationAuthority(lockSuppressed = true),
		)

		delivery.candidate.units.single().evidence.activityAutomationEpoch shouldBe null
	}

	@Test
	fun `newest configured transition alone receives automation authority`() {
		val walkingThenStill = subject.create(
			batch(
				transitions = listOf(
					transition(100L, DetectedActivityType.WALKING),
					transition(200L, DetectedActivityType.STILL),
				),
			),
			identity(),
			automationAuthority(),
		)
		val stillThenWalking = subject.create(
			batch(
				transitions = listOf(
					transition(100L, DetectedActivityType.STILL),
					transition(200L, DetectedActivityType.WALKING),
				),
			),
			identity(),
			automationAuthority(),
		)

		walkingThenStill.candidate.units.map { unit ->
			(unit.evidence.payload as ActivityTransitionPayload).activityType to
				unit.evidence.activityAutomationEpoch
		} shouldContainExactly listOf(
			stableActivityCode(DetectedActivityType.WALKING) to null,
			stableActivityCode(DetectedActivityType.STILL) to AUTOMATION_EPOCH,
		)
		stillThenWalking.candidate.units.map { unit ->
			(unit.evidence.payload as ActivityTransitionPayload).activityType to
				unit.evidence.activityAutomationEpoch
		} shouldContainExactly listOf(
			stableActivityCode(DetectedActivityType.STILL) to null,
			stableActivityCode(DetectedActivityType.WALKING) to AUTOMATION_EPOCH,
		)
	}

	@Test
	fun `provider order breaks equal transition timestamps and participates in delivery identity`() {
		val walkingThenStill = batch(
			transitions = listOf(
				transition(100L, DetectedActivityType.WALKING),
				transition(100L, DetectedActivityType.STILL),
			),
		)
		val stillThenWalking = batch(
			transitions = listOf(
				transition(100L, DetectedActivityType.STILL),
				transition(100L, DetectedActivityType.WALKING),
			),
		)

		val a = subject.create(walkingThenStill, identity(), automationAuthority())
		val b = subject.create(stillThenWalking, identity(), automationAuthority())

		a.candidate.units.single { it.evidence.activityAutomationEpoch != null }
			.evidence.payload shouldBe ActivityTransitionPayload(
			activityType = stableActivityCode(DetectedActivityType.STILL),
			transitionType = ActivityTransitionType.ENTER.value,
			providerElapsedRealtimeNanos = 100L,
		)
		b.candidate.units.single { it.evidence.activityAutomationEpoch != null }
			.evidence.payload shouldBe ActivityTransitionPayload(
			activityType = stableActivityCode(DetectedActivityType.WALKING),
			transitionType = ActivityTransitionType.ENTER.value,
			providerElapsedRealtimeNanos = 100L,
		)
		(a.candidate.identity == b.candidate.identity) shouldBe false
	}

	@Test
	fun `newer unconfigured transition cannot hide latest configured transition`() {
		val delivery = subject.create(
			batch(
				transitions = listOf(
					transition(100L, DetectedActivityType.WALKING),
					transition(200L, DetectedActivityType.STILL),
				),
				automaticTransitions = setOf(
					ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER),
				),
			),
			identity(),
			automationAuthority(),
		)

		delivery.candidate.units.single { it.evidence.activityAutomationEpoch != null }
			.evidence.payload shouldBe ActivityTransitionPayload(
			activityType = stableActivityCode(DetectedActivityType.WALKING),
			transitionType = ActivityTransitionType.ENTER.value,
			providerElapsedRealtimeNanos = 100L,
		)
	}

	@Test
	fun `captured automatic mechanism separates recognition from transition control`() {
		val recognitionControlled = subject.create(
			batch(
				recognitions = listOf(recognition(100L)),
				transitions = listOf(transition(100L)),
				automaticRecognitionEligible = true,
				automaticTransitions = emptySet(),
			),
			identity(),
			automationAuthority(),
		)
		val transitionControlled = subject.create(
			batch(
				recognitions = listOf(recognition(100L)),
				transitions = listOf(transition(100L)),
				automaticRecognitionEligible = false,
				automaticTransitions = setOf(
					ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER),
				),
			),
			identity(),
			automationAuthority(),
		)

		recognitionControlled.candidate.units.single {
			it.evidence.payload is ActivityRecognitionPayload
		}.evidence.activityAutomationEpoch shouldBe AUTOMATION_EPOCH
		recognitionControlled.candidate.units.single {
			it.evidence.payload is ActivityTransitionPayload
		}.evidence.activityAutomationEpoch shouldBe null
		transitionControlled.candidate.units.single {
			it.evidence.payload is ActivityRecognitionPayload
		}.evidence.activityAutomationEpoch shouldBe null
		transitionControlled.candidate.units.single {
			it.evidence.payload is ActivityTransitionPayload
		}.evidence.activityAutomationEpoch shouldBe AUTOMATION_EPOCH
	}

	private fun batch(
		recognitions: List<ActivityRecognitionEvidence> = emptyList(),
		transitions: List<ActivityTransitionEvidence> = emptyList(),
		automaticRecognitionEligible: Boolean = true,
		automaticTransitions: Set<ActivityTransitionData>? = null,
	) = ActivityRecognitionEvidenceBatch(
		receivedElapsedRealtimeNanos = 5_000L,
		receivedWallTimeMs = 10_000L,
		registrationIdentity = identity(),
		automaticRecognitionEligible = automaticRecognitionEligible,
		automaticTransitions = automaticTransitions ?: transitions.map { evidence ->
			ActivityTransitionData(evidence.activityType, evidence.transitionType)
		}.toSet(),
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

	private fun automationAuthority(
		epoch: Long = AUTOMATION_EPOCH,
		effectiveElapsedRealtimeNanos: Long = 0L,
		automaticControlEnabled: Boolean = true,
		lockSuppressed: Boolean = false,
		powerSaverSuppressed: Boolean = false,
	) = ActivityAutomationEpochEntity(
		epoch = epoch,
		automaticControlEnabled = automaticControlEnabled,
		lockSuppressed = lockSuppressed,
		powerSaverSuppressed = powerSaverSuppressed,
		bootClockDomainId = "boot-1",
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
	)

	private companion object {
		const val AUTOMATION_EPOCH = 17L
	}
}
