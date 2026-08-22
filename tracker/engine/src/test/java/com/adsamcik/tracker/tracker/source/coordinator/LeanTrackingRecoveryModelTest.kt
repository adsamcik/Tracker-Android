package com.adsamcik.tracker.tracker.source.coordinator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Executable comparison model for TI-D052.
 *
 * This is deliberately test-only. It asks whether policy plus one immutable intent stream, one
 * external-action outbox, one boot-aware lease, and one registration row containing its bounded
 * eligibility vector can reproduce the required recovery/fencing queries. It is not a second
 * production coordinator.
 */
class LeanTrackingRecoveryModelTest {
	@Test
	fun `start reconfigure and stop need one immutable intent stream`() {
		val subject = model()
		subject.grant(LeanSource.STEPS, LeanPurpose.SESSION_CAPTURE, revision = 1, epoch = 1)
		subject.grant(LeanSource.PRESSURE, LeanPurpose.SESSION_CAPTURE, revision = 1, epoch = 1)

		subject.startManual("session-1", setOf(LeanSource.STEPS), elapsedNanos = 10)
		subject.state.intents.getValue("session-1").map(LeanIntent::revision) shouldBe listOf(1L)
		subject.sessionState("session-1") shouldBe LeanSessionState.STARTING

		subject.reconcile()
		subject.sessionState("session-1") shouldBe LeanSessionState.STARTING
		subject.applyPending()
		subject.sessionState("session-1") shouldBe LeanSessionState.ACTIVE

		subject.reconfigure("session-1", setOf(LeanSource.PRESSURE), elapsedNanos = 20)
		subject.state.intents.getValue("session-1").map(LeanIntent::revision) shouldBe listOf(1L, 2L)
		subject.state.intents.getValue("session-1").first().bindings
			.map(LeanEligibility::source) shouldBe listOf(LeanSource.STEPS)
		subject.reconcile()
		subject.applyPending()
		subject.sessionState("session-1") shouldBe LeanSessionState.ACTIVE
		subject.activeRegistration(LeanSource.STEPS) shouldBe null
		subject.activeRegistration(LeanSource.PRESSURE)?.generation shouldBe 1L

		subject.stop("session-1", elapsedNanos = 30)
		subject.sessionState("session-1") shouldBe LeanSessionState.STOPPING
		subject.reconcile()
		subject.applyPending()
		subject.sessionState("session-1") shouldBe LeanSessionState.FINALIZED
		subject.state.intents.getValue("session-1").map(LeanIntent::revision) shouldBe listOf(1L, 2L, 3L)

		shouldThrow<IllegalArgumentException> {
			subject.startManual("empty", emptySet(), elapsedNanos = 40)
		}
	}

	@Test
	fun `crash before and after provider effect replays one durable action generation`() {
		val durable = LeanDurableState(bootId = "boot-1")
		val external = LeanExternalState()
		val firstProcess = LeanTrackingRecoveryModel(durable, external)
		firstProcess.grant(LeanSource.STEPS, LeanPurpose.SESSION_CAPTURE, revision = 1, epoch = 1)
		firstProcess.startManual("session-1", setOf(LeanSource.STEPS), elapsedNanos = 10)
		firstProcess.reconcile()

		// Crash before the provider call: intent, generation reservation, and action already exist.
		durable.actions.size shouldBe 1
		durable.actions.values.single().status shouldBe LeanActionStatus.PENDING
		external.generations[LeanSource.STEPS] shouldBe null

		val secondProcess = LeanTrackingRecoveryModel(durable, external)
		secondProcess.reconcile()
		durable.actions.size shouldBe 1
		secondProcess.apply(durable.actions.keys.single(), acknowledge = false)

		// Crash after provider acceptance but before Room acknowledgement.
		external.generations[LeanSource.STEPS] shouldBe 1L
		durable.actions.values.single().status shouldBe LeanActionStatus.PENDING

		val thirdProcess = LeanTrackingRecoveryModel(durable, external)
		thirdProcess.reconcile()
		durable.actions.size shouldBe 1
		thirdProcess.applyPending()

		durable.actions.values.single().status shouldBe LeanActionStatus.ACCEPTED
		thirdProcess.activeRegistration(LeanSource.STEPS)?.generation shouldBe 1L
		thirdProcess.sessionState("session-1") shouldBe LeanSessionState.ACTIVE
	}

	@Test
	fun `reboot invalidates lease trigger registration and late callback`() {
		val subject = model()
		subject.grant(LeanSource.ACTIVITY, LeanPurpose.CONTROL_AUTOSTART, revision = 1, epoch = 1)
		subject.setAutomationEpoch(7)
		val trigger = LeanTrigger(
			id = "trigger-1",
			bootId = "boot-1",
			observedElapsedNanos = 10,
			receivedElapsedNanos = 11,
			expiresElapsedNanos = 100,
			automationEpoch = 7,
		)
		val lease = subject.acquireLease("owner-1", nowNanos = 1, ttlNanos = 50)!!
		subject.reconcile()
		subject.applyPending()
		val oldGeneration = subject.activeRegistration(LeanSource.ACTIVITY)!!.generation
		subject.accepts(
			LeanCallback(LeanSource.ACTIVITY, oldGeneration, "boot-1", 0, LeanPurpose.CONTROL_AUTOSTART),
		) shouldBe true

		subject.reboot("boot-2")

		subject.leaseValid(lease, nowNanos = 2) shouldBe false
		subject.accepts(
			LeanCallback(LeanSource.ACTIVITY, oldGeneration, "boot-1", 0, LeanPurpose.CONTROL_AUTOSTART),
		) shouldBe false
		shouldThrow<IllegalArgumentException> {
			subject.startAutomatic("stale", setOf(LeanSource.STEPS), trigger, elapsedNanos = 12)
		}

		subject.reconcile()
		subject.applyPending()
		val newGeneration = subject.activeRegistration(LeanSource.ACTIVITY)!!.generation
		newGeneration shouldBe oldGeneration + 1
		subject.accepts(
			LeanCallback(LeanSource.ACTIVITY, newGeneration, "boot-2", 0, LeanPurpose.CONTROL_AUTOSTART),
		) shouldBe true
	}

	@Test
	fun `consent revoke appends effective intent and fences prior capture immediately`() {
		val subject = model()
		subject.grant(LeanSource.STEPS, LeanPurpose.SESSION_CAPTURE, revision = 1, epoch = 1)
		subject.startManual("session-1", setOf(LeanSource.STEPS), elapsedNanos = 10)
		subject.reconcile()
		subject.applyPending()
		val oldGeneration = subject.activeRegistration(LeanSource.STEPS)!!.generation

		subject.revoke(LeanSource.STEPS, LeanPurpose.SESSION_CAPTURE, revision = 2, epoch = 2)
		subject.refreshSessionsForPolicy(elapsedNanos = 20)

		subject.state.intents.getValue("session-1").map(LeanIntent::revision) shouldBe listOf(1L, 2L)
		subject.sessionState("session-1") shouldBe LeanSessionState.STOPPING
		subject.accepts(
			LeanCallback(
				LeanSource.STEPS,
				oldGeneration,
				"boot-1",
				0,
				LeanPurpose.SESSION_CAPTURE,
				"session-1",
			),
		) shouldBe false
		subject.reconcile()
		subject.applyPending()
		subject.sessionState("session-1") shouldBe LeanSessionState.FINALIZED
	}

	@Test
	fun `ambient only demand survives process recovery without a fabricated session`() {
		val durable = LeanDurableState(bootId = "boot-1")
		val external = LeanExternalState()
		val firstProcess = LeanTrackingRecoveryModel(durable, external)
		firstProcess.grant(
			LeanSource.STEPS,
			LeanPurpose.AMBIENT_PRODUCT,
			revision = 1,
			epoch = 1,
			persistenceEligible = true,
		)
		firstProcess.reconcile()
		firstProcess.applyPending()
		val oldGeneration = firstProcess.activeRegistration(LeanSource.STEPS)!!.generation
		durable.intents shouldBe emptyMap()

		val recovered = LeanTrackingRecoveryModel(durable, LeanExternalState())
		recovered.recoverAfterProcessDeath()
		recovered.applyPending()

		val registration = recovered.activeRegistration(LeanSource.STEPS)!!
		registration.generation shouldBe oldGeneration + 1
		registration.eligibility.map(LeanEligibility::purpose) shouldBe listOf(LeanPurpose.AMBIENT_PRODUCT)
		durable.intents shouldBe emptyMap()
	}

	@Test
	fun `shared Activity registration separates control from captured history`() {
		val subject = model()
		subject.grant(
			LeanSource.ACTIVITY,
			LeanPurpose.CONTROL_AUTOSTART,
			revision = 1,
			epoch = 1,
			persistenceEligible = false,
		)
		subject.grant(LeanSource.ACTIVITY, LeanPurpose.SESSION_CAPTURE, revision = 1, epoch = 2)
		subject.startManual("session-1", setOf(LeanSource.ACTIVITY), elapsedNanos = 10)
		subject.reconcile()
		subject.applyPending()

		val shared = subject.activeRegistration(LeanSource.ACTIVITY)!!
		shared.eligibility.map(LeanEligibility::purpose).toSet() shouldBe setOf(
			LeanPurpose.CONTROL_AUTOSTART,
			LeanPurpose.SESSION_CAPTURE,
		)
		subject.accepts(
			LeanCallback(
				LeanSource.ACTIVITY,
				shared.generation,
				"boot-1",
				0,
				LeanPurpose.SESSION_CAPTURE,
				"session-1",
			),
		) shouldBe true

		subject.stop("session-1", elapsedNanos = 20)
		subject.reconcile()
		subject.applyPending()
		val controlOnly = subject.activeRegistration(LeanSource.ACTIVITY)!!

		controlOnly.generation shouldBe shared.generation + 1
		controlOnly.eligibility.map(LeanEligibility::purpose) shouldBe listOf(LeanPurpose.CONTROL_AUTOSTART)
		subject.accepts(
			LeanCallback(
				LeanSource.ACTIVITY,
				controlOnly.generation,
				"boot-1",
				0,
				LeanPurpose.SESSION_CAPTURE,
				"session-1",
			),
		) shouldBe false
		subject.accepts(
			LeanCallback(
				LeanSource.ACTIVITY,
				controlOnly.generation,
				"boot-1",
				0,
				LeanPurpose.CONTROL_AUTOSTART,
			),
		) shouldBe true
	}

	@Test
	fun `deletion epoch rotates registration and rejects delayed callbacks`() {
		val subject = model()
		subject.grant(
			LeanSource.STEPS,
			LeanPurpose.AMBIENT_PRODUCT,
			revision = 1,
			epoch = 1,
			persistenceEligible = true,
		)
		subject.reconcile()
		subject.applyPending()
		val beforeDeletion = subject.activeRegistration(LeanSource.STEPS)!!

		subject.advanceDeletionEpoch()
		subject.accepts(
			LeanCallback(
				LeanSource.STEPS,
				beforeDeletion.generation,
				"boot-1",
				0,
				LeanPurpose.AMBIENT_PRODUCT,
			),
		) shouldBe false
		subject.reconcile()
		subject.applyPending()

		val afterDeletion = subject.activeRegistration(LeanSource.STEPS)!!
		afterDeletion.generation shouldBe beforeDeletion.generation + 1
		afterDeletion.collectedDataEpoch shouldBe 1L
		subject.accepts(
			LeanCallback(
				LeanSource.STEPS,
				afterDeletion.generation,
				"boot-1",
				1,
				LeanPurpose.AMBIENT_PRODUCT,
			),
		) shouldBe true
	}

	@Test
	fun `bounded registration vector is deterministic and auditable`() {
		val subject = model()
		subject.grant(
			LeanSource.ACTIVITY,
			LeanPurpose.CONTROL_AUTOSTART,
			revision = 4,
			epoch = 7,
			persistenceEligible = false,
		)
		subject.grant(LeanSource.ACTIVITY, LeanPurpose.SESSION_CAPTURE, revision = 4, epoch = 8)
		subject.startManual("session-1", setOf(LeanSource.ACTIVITY), elapsedNanos = 10)
		subject.reconcile()
		subject.applyPending()

		subject.activeRegistration(LeanSource.ACTIVITY)!!.eligibility.shouldContainExactly(
			LeanEligibility(
				source = LeanSource.ACTIVITY,
				purpose = LeanPurpose.CONTROL_AUTOSTART,
				consumerId = "app:CONTROL_AUTOSTART",
				sessionId = null,
				intentRevision = null,
				policyRevision = 4,
				consentEpoch = 7,
				persistenceEligible = false,
				effectiveBootId = "boot-1",
				effectiveElapsedNanos = 0,
			),
			LeanEligibility(
				source = LeanSource.ACTIVITY,
				purpose = LeanPurpose.SESSION_CAPTURE,
				consumerId = "session:session-1",
				sessionId = "session-1",
				intentRevision = 1,
				policyRevision = 4,
				consentEpoch = 8,
				persistenceEligible = true,
				effectiveBootId = "boot-1",
				effectiveElapsedNanos = 0,
			),
		)
	}

	private fun model() = LeanTrackingRecoveryModel(
		state = LeanDurableState(bootId = "boot-1"),
		external = LeanExternalState(),
	)
}

private enum class LeanSource { LOCATION, WIFI, CELL, ACTIVITY, STEPS, PRESSURE }

private enum class LeanPurpose {
	CONTROL_AUTOSTART,
	CONTROL_CONTINUATION,
	SESSION_CAPTURE,
	AMBIENT_PRODUCT,
}

private enum class LeanIntentState { RUNNING, FINALIZED }

private enum class LeanSessionState { STARTING, ACTIVE, STOPPING, FINALIZED }

private enum class LeanRegistrationStatus { RESERVED, ACTIVE, RETIRING, RETIRED }

private enum class LeanActionStatus { PENDING, ACCEPTED }

private enum class LeanStartOrigin { MANUAL, AUTOMATIC, POLICY_RECONCILIATION }

private data class LeanPolicyKey(val source: LeanSource, val purpose: LeanPurpose)

private data class LeanGrant(
	val enabled: Boolean,
	val policyRevision: Long,
	val consentEpoch: Long,
	val persistenceEligible: Boolean,
	val effectiveBootId: String,
	val effectiveElapsedNanos: Long,
)

private data class LeanTrigger(
	val id: String,
	val bootId: String,
	val observedElapsedNanos: Long,
	val receivedElapsedNanos: Long,
	val expiresElapsedNanos: Long,
	val automationEpoch: Long,
)

private data class LeanEligibility(
	val source: LeanSource,
	val purpose: LeanPurpose,
	val consumerId: String,
	val sessionId: String?,
	val intentRevision: Long?,
	val policyRevision: Long,
	val consentEpoch: Long,
	val persistenceEligible: Boolean,
	val effectiveBootId: String,
	val effectiveElapsedNanos: Long,
)

private data class LeanIntent(
	val sessionId: String,
	val revision: Long,
	val state: LeanIntentState,
	val origin: LeanStartOrigin,
	val effectiveBootId: String,
	val effectiveElapsedNanos: Long,
	val automationEpoch: Long?,
	val trigger: LeanTrigger?,
	val bindings: List<LeanEligibility>,
)

private data class LeanRegistration(
	val source: LeanSource,
	val generation: Long,
	val bootId: String,
	val collectedDataEpoch: Long,
	val eligibility: List<LeanEligibility>,
	var status: LeanRegistrationStatus,
)

private data class LeanAction(
	val id: String,
	val source: LeanSource,
	val registrationGeneration: Long,
	val desiredActive: Boolean,
	val origin: LeanStartOrigin,
	var status: LeanActionStatus,
)

private data class LeanLease(
	val owner: String,
	val bootId: String,
	val generation: Long,
	val expiresElapsedNanos: Long,
)

private data class LeanCallback(
	val source: LeanSource,
	val registrationGeneration: Long,
	val bootId: String,
	val collectedDataEpoch: Long,
	val purpose: LeanPurpose,
	val sessionId: String? = null,
)

private data class LeanDurableState(
	var bootId: String,
	var collectedDataEpoch: Long = 0,
	var automationEpoch: Long = 0,
	val policy: MutableMap<LeanPolicyKey, LeanGrant> = mutableMapOf(),
	val intents: MutableMap<String, MutableList<LeanIntent>> = mutableMapOf(),
	val registrations: MutableMap<LeanSource, MutableList<LeanRegistration>> = mutableMapOf(),
	val actions: MutableMap<String, LeanAction> = linkedMapOf(),
	var lease: LeanLease? = null,
)

private data class LeanExternalState(
	val generations: MutableMap<LeanSource, Long> = mutableMapOf(),
)

private class LeanTrackingRecoveryModel(
	val state: LeanDurableState,
	private val external: LeanExternalState,
) {
	fun grant(
		source: LeanSource,
		purpose: LeanPurpose,
		revision: Long,
		epoch: Long,
		persistenceEligible: Boolean = true,
	) {
		state.policy[LeanPolicyKey(source, purpose)] = LeanGrant(
			enabled = true,
			policyRevision = revision,
			consentEpoch = epoch,
			persistenceEligible = persistenceEligible,
			effectiveBootId = state.bootId,
			effectiveElapsedNanos = 0,
		)
	}

	fun revoke(source: LeanSource, purpose: LeanPurpose, revision: Long, epoch: Long) {
		state.policy[LeanPolicyKey(source, purpose)] = LeanGrant(
			enabled = false,
			policyRevision = revision,
			consentEpoch = epoch,
			persistenceEligible = false,
			effectiveBootId = state.bootId,
			effectiveElapsedNanos = 0,
		)
	}

	fun setAutomationEpoch(epoch: Long) {
		require(epoch > state.automationEpoch)
		state.automationEpoch = epoch
	}

	fun startManual(sessionId: String, sources: Set<LeanSource>, elapsedNanos: Long) {
		appendRunningIntent(sessionId, sources, LeanStartOrigin.MANUAL, elapsedNanos, null)
	}

	fun startAutomatic(
		sessionId: String,
		sources: Set<LeanSource>,
		trigger: LeanTrigger,
		elapsedNanos: Long,
	) {
		require(trigger.bootId == state.bootId)
		require(trigger.automationEpoch == state.automationEpoch)
		require(trigger.observedElapsedNanos <= trigger.receivedElapsedNanos)
		require(trigger.receivedElapsedNanos <= elapsedNanos)
		require(elapsedNanos <= trigger.expiresElapsedNanos)
		require(enabledGrant(LeanSource.ACTIVITY, LeanPurpose.CONTROL_AUTOSTART) != null)
		appendRunningIntent(sessionId, sources, LeanStartOrigin.AUTOMATIC, elapsedNanos, trigger)
	}

	fun reconfigure(sessionId: String, sources: Set<LeanSource>, elapsedNanos: Long) {
		require(state.intents[sessionId]?.lastOrNull()?.state == LeanIntentState.RUNNING)
		appendRunningIntent(sessionId, sources, LeanStartOrigin.POLICY_RECONCILIATION, elapsedNanos, null)
	}

	fun stop(sessionId: String, elapsedNanos: Long) {
		val history = state.intents.getValue(sessionId)
		val current = history.last()
		require(current.state == LeanIntentState.RUNNING)
		history += current.copy(
			revision = current.revision + 1,
			state = LeanIntentState.FINALIZED,
			effectiveBootId = state.bootId,
			effectiveElapsedNanos = elapsedNanos,
			bindings = emptyList(),
		)
	}

	fun refreshSessionsForPolicy(elapsedNanos: Long) {
		state.intents.keys.toList().forEach { sessionId ->
			val current = state.intents.getValue(sessionId).last()
			if (current.state != LeanIntentState.RUNNING) return@forEach
			val retainedSources = current.bindings
				.filter { binding -> currentGrantMatches(binding) }
				.map(LeanEligibility::source)
				.toSet()
			if (retainedSources.isEmpty()) {
				stop(sessionId, elapsedNanos)
			} else if (retainedSources.size != current.bindings.size) {
				appendRunningIntent(
					sessionId,
					retainedSources,
					LeanStartOrigin.POLICY_RECONCILIATION,
					elapsedNanos,
					null,
				)
			}
		}
	}

	fun reconcile(forceReregister: Boolean = false) {
		val desired = desiredEligibility()
		val sources = (desired.keys + state.registrations.keys).toSet()
		sources.forEach { source ->
			val vector = desired[source].orEmpty()
			val current = currentRegistration(source)
			val currentMatches = current != null &&
				current.bootId == state.bootId &&
				current.collectedDataEpoch == state.collectedDataEpoch &&
				current.eligibility == vector
			if (currentMatches && !forceReregister) return@forEach

			if (vector.isEmpty()) {
				if (current != null && pendingAction(source) == null) {
					current.status = LeanRegistrationStatus.RETIRING
					appendAction(current, desiredActive = false)
				}
				return@forEach
			}

			if (pendingAction(source) != null) return@forEach
			val generation = state.registrations[source]
				.orEmpty()
				.maxOfOrNull(LeanRegistration::generation)
				.orZero() + 1
			val registration = LeanRegistration(
				source = source,
				generation = generation,
				bootId = state.bootId,
				collectedDataEpoch = state.collectedDataEpoch,
				eligibility = vector,
				status = LeanRegistrationStatus.RESERVED,
			)
			state.registrations.getOrPut(source, ::mutableListOf) += registration
			appendAction(registration, desiredActive = true)
		}
	}

	fun recoverAfterProcessDeath() = reconcile(forceReregister = true)

	fun applyPending() {
		state.actions.values
			.filter { it.status == LeanActionStatus.PENDING }
			.map(LeanAction::id)
			.forEach { apply(it, acknowledge = true) }
	}

	fun apply(actionId: String, acknowledge: Boolean) {
		val action = state.actions.getValue(actionId)
		if (action.desiredActive) {
			external.generations[action.source] = action.registrationGeneration
		} else if (external.generations[action.source] == action.registrationGeneration) {
			external.generations.remove(action.source)
		}
		if (!acknowledge) return

		state.registrations.getValue(action.source).forEach { registration ->
			when {
				action.desiredActive && registration.generation == action.registrationGeneration ->
					registration.status = LeanRegistrationStatus.ACTIVE
				action.desiredActive && registration.status != LeanRegistrationStatus.RETIRED ->
					registration.status = LeanRegistrationStatus.RETIRED
				!action.desiredActive && registration.generation == action.registrationGeneration ->
					registration.status = LeanRegistrationStatus.RETIRED
			}
		}
		action.status = LeanActionStatus.ACCEPTED
	}

	fun activeRegistration(source: LeanSource): LeanRegistration? =
		state.registrations[source]?.lastOrNull { it.status == LeanRegistrationStatus.ACTIVE }

	fun sessionState(sessionId: String): LeanSessionState {
		val current = state.intents.getValue(sessionId).last()
		if (current.state == LeanIntentState.RUNNING) {
			val allAccepted = desiredEligibility().values.flatten()
				.filter { it.sessionId == sessionId }
				.all { eligibility ->
					activeRegistration(eligibility.source)?.eligibility?.contains(eligibility) == true
				}
			return if (allAccepted) LeanSessionState.ACTIVE else LeanSessionState.STARTING
		}

		val stillInstalled = state.registrations.values.flatten().any { registration ->
			registration.status != LeanRegistrationStatus.RETIRED &&
				registration.eligibility.any { it.sessionId == sessionId }
		}
		return if (stillInstalled) LeanSessionState.STOPPING else LeanSessionState.FINALIZED
	}

	fun accepts(callback: LeanCallback): Boolean {
		if (callback.bootId != state.bootId || callback.collectedDataEpoch != state.collectedDataEpoch) {
			return false
		}
		val registration = activeRegistration(callback.source) ?: return false
		if (registration.generation != callback.registrationGeneration) return false
		if (registration.bootId != callback.bootId) return false
		val desired = desiredEligibility()[callback.source].orEmpty()
		return registration.eligibility.any { eligibility ->
			eligibility.purpose == callback.purpose &&
				eligibility.sessionId == callback.sessionId &&
				currentGrantMatches(eligibility) &&
				eligibility in desired
		}
	}

	fun acquireLease(owner: String, nowNanos: Long, ttlNanos: Long): LeanLease? {
		require(ttlNanos > 0)
		val previous = state.lease
		if (previous != null && previous.bootId == state.bootId &&
			previous.expiresElapsedNanos > nowNanos && previous.owner != owner
		) {
			return null
		}
		return LeanLease(
			owner = owner,
			bootId = state.bootId,
			generation = (previous?.generation ?: 0) + 1,
			expiresElapsedNanos = nowNanos + ttlNanos,
		).also { state.lease = it }
	}

	fun leaseValid(lease: LeanLease, nowNanos: Long): Boolean =
		state.lease == lease && lease.bootId == state.bootId && lease.expiresElapsedNanos > nowNanos

	fun reboot(newBootId: String) {
		require(newBootId != state.bootId)
		state.bootId = newBootId
	}

	fun advanceDeletionEpoch() {
		state.collectedDataEpoch += 1
	}

	private fun appendRunningIntent(
		sessionId: String,
		sources: Set<LeanSource>,
		origin: LeanStartOrigin,
		elapsedNanos: Long,
		trigger: LeanTrigger?,
	) {
		val history = state.intents.getOrPut(sessionId, ::mutableListOf)
		val revision = (history.lastOrNull()?.revision ?: 0) + 1
		val bindings = sources.mapNotNull { source ->
			enabledGrant(source, LeanPurpose.SESSION_CAPTURE)?.toEligibility(
				source = source,
				purpose = LeanPurpose.SESSION_CAPTURE,
				consumerId = "session:$sessionId",
				sessionId = sessionId,
				intentRevision = revision,
			)
		}.sortedWith(ELIGIBILITY_ORDER)
		require(bindings.isNotEmpty()) { "A session requires at least one policy-authorized capture source" }
		history += LeanIntent(
			sessionId = sessionId,
			revision = revision,
			state = LeanIntentState.RUNNING,
			origin = origin,
			effectiveBootId = state.bootId,
			effectiveElapsedNanos = elapsedNanos,
			automationEpoch = trigger?.automationEpoch,
			trigger = trigger,
			bindings = bindings,
		)
	}

	private fun desiredEligibility(): Map<LeanSource, List<LeanEligibility>> {
		val eligibility = mutableListOf<LeanEligibility>()
		state.policy.forEach { (key, grant) ->
			if (grant.enabled && key.purpose in APP_PURPOSES) {
				eligibility += grant.toEligibility(
					source = key.source,
					purpose = key.purpose,
					consumerId = "app:${key.purpose}",
					sessionId = null,
					intentRevision = null,
				)
			}
		}
		state.intents.values.forEach { history ->
			val current = history.last()
			if (current.state == LeanIntentState.RUNNING) {
				eligibility += current.bindings.filter(::currentGrantMatches)
			}
		}
		return eligibility
			.groupBy(LeanEligibility::source)
			.mapValues { (_, entries) -> entries.sortedWith(ELIGIBILITY_ORDER) }
	}

	private fun enabledGrant(source: LeanSource, purpose: LeanPurpose): LeanGrant? =
		state.policy[LeanPolicyKey(source, purpose)]?.takeIf(LeanGrant::enabled)

	private fun currentGrantMatches(eligibility: LeanEligibility): Boolean =
		state.policy[LeanPolicyKey(eligibility.source, eligibility.purpose)]?.let { grant ->
			grant.enabled &&
				grant.policyRevision == eligibility.policyRevision &&
				grant.consentEpoch == eligibility.consentEpoch &&
				grant.persistenceEligible == eligibility.persistenceEligible
		} == true

	private fun currentRegistration(source: LeanSource): LeanRegistration? =
		state.registrations[source]?.lastOrNull { it.status != LeanRegistrationStatus.RETIRED }

	private fun pendingAction(source: LeanSource): LeanAction? =
		state.actions.values.lastOrNull { it.source == source && it.status == LeanActionStatus.PENDING }

	private fun appendAction(registration: LeanRegistration, desiredActive: Boolean) {
		val origin = desiredEligibility()[registration.source]
			.orEmpty()
			.firstNotNullOfOrNull { eligibility ->
				eligibility.sessionId?.let { state.intents.getValue(it).last().origin }
			} ?: LeanStartOrigin.POLICY_RECONCILIATION
		val id = "${registration.source}:${registration.generation}:${if (desiredActive) "APPLY" else "REMOVE"}"
		state.actions[id] = LeanAction(
			id = id,
			source = registration.source,
			registrationGeneration = registration.generation,
			desiredActive = desiredActive,
			origin = origin,
			status = LeanActionStatus.PENDING,
		)
	}

	private fun LeanGrant.toEligibility(
		source: LeanSource,
		purpose: LeanPurpose,
		consumerId: String,
		sessionId: String?,
		intentRevision: Long?,
	) = LeanEligibility(
		source = source,
		purpose = purpose,
		consumerId = consumerId,
		sessionId = sessionId,
		intentRevision = intentRevision,
		policyRevision = policyRevision,
		consentEpoch = consentEpoch,
		persistenceEligible = persistenceEligible,
		effectiveBootId = effectiveBootId,
		effectiveElapsedNanos = effectiveElapsedNanos,
	)

	private fun Long?.orZero(): Long = this ?: 0

	private companion object {
		val APP_PURPOSES = setOf(LeanPurpose.CONTROL_AUTOSTART, LeanPurpose.AMBIENT_PRODUCT)
		val ELIGIBILITY_ORDER = compareBy<LeanEligibility>(
			{ it.source.ordinal },
			{ it.purpose.ordinal },
			LeanEligibility::consumerId,
			{ it.intentRevision ?: 0 },
		)
	}
}
