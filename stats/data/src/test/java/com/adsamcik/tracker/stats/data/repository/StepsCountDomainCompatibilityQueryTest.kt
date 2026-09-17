package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLineageKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStoredOwner
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityRequest
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityResult
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerEffect
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerIdentity
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerKind
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerReference
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepsCountDomainCompatibilityQueryTest {
	@Test
	fun `same provider token survives authority and registration differences`() {
		val sessionFact = evidence(
			StepsCountDomainOwnerKind.SESSION_FACT,
			'1',
			'a',
			authorityRevision = 1L,
			registrationGeneration = 1L,
		)
		val completeness = evidence(
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			'2',
			'a',
			authorityRevision = 2L,
			registrationGeneration = 2L,
		)
		val ambient = evidence(
			StepsCountDomainOwnerKind.AMBIENT_FACT,
			'3',
			'a',
			authorityRevision = 3L,
			registrationGeneration = 3L,
		)

		resolveStepsCountDomainCompatibility(
			request(listOf(sessionFact.reference, completeness.reference), listOf(ambient.reference)),
			read(sessionFact, completeness, ambient),
		) shouldBe StepsCountDomainCompatibilityResult.ExactCompatible
	}

	@Test
	fun `different provider token and collected epoch conflict`() {
		val sessionFact = evidence(StepsCountDomainOwnerKind.SESSION_FACT, '1', 'a')
		val completeness = evidence(
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			'2',
			'a',
		)
		val changedInstance = evidence(
			StepsCountDomainOwnerKind.AMBIENT_FACT,
			'3',
			'b',
		)
		val changedEpoch = evidence(
			StepsCountDomainOwnerKind.AMBIENT_FACT,
			'4',
			'a',
			epoch = 8L,
		)

		resolveStepsCountDomainCompatibility(
			request(
				listOf(sessionFact.reference, completeness.reference),
				listOf(changedInstance.reference),
			),
			read(sessionFact, completeness, changedInstance),
		) shouldBe StepsCountDomainCompatibilityResult.Conflict
		resolveStepsCountDomainCompatibility(
			request(
				listOf(sessionFact.reference, completeness.reference),
				listOf(changedEpoch.reference),
			),
			read(sessionFact, completeness, changedEpoch),
		) shouldBe StepsCountDomainCompatibilityResult.Conflict
	}

	@Test
	fun `missing deleted corrupt and stale ownership fail closed`() {
		val sessionFact = evidence(StepsCountDomainOwnerKind.SESSION_FACT, '5', 'a')
		val completeness = evidence(
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			'6',
			'a',
		)
		val ambient = evidence(StepsCountDomainOwnerKind.AMBIENT_FACT, '7', 'a')
		val baseRequest = request(
			listOf(sessionFact.reference, completeness.reference),
			listOf(ambient.reference),
		)

		resolveStepsCountDomainCompatibility(
			baseRequest,
			read(sessionFact, completeness),
		) shouldBe StepsCountDomainCompatibilityResult.Unproven
		val terminalUnproven = completeness.asUnproven()
		resolveStepsCountDomainCompatibility(
			request(
				listOf(sessionFact.reference, terminalUnproven.reference),
				listOf(ambient.reference),
			),
			read(sessionFact, terminalUnproven, ambient),
		) shouldBe StepsCountDomainCompatibilityResult.Unproven

		val deleted = ambient.copy(
			stored = ambient.stored.copy(
				owner = ambient.stored.owner.copy(
					operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
					receiptIdentity = null,
				),
				receipt = null,
			),
		)
		resolveStepsCountDomainCompatibility(
			request(
				listOf(sessionFact.reference, completeness.reference),
				listOf(deleted.reference),
			),
			read(sessionFact, completeness, deleted),
		) shouldBe StepsCountDomainCompatibilityResult.Deleted
		val mismatchedRetraction = deleted.copy(
			reference = deleted.reference.copy(
				effect = StepsCountDomainOwnerEffect.opaque("0".repeat(64)),
			),
		)
		resolveStepsCountDomainCompatibility(
			request(
				listOf(sessionFact.reference, completeness.reference),
				listOf(mismatchedRetraction.reference),
			),
			read(sessionFact, completeness, mismatchedRetraction),
		) shouldBe StepsCountDomainCompatibilityResult.Unverifiable

		val corrupt = ambient.copy(
			stored = ambient.stored.copy(
				owner = ambient.stored.owner.copy(
					ownerEffectChecksum = "f".repeat(64),
				),
			),
		)
		resolveStepsCountDomainCompatibility(
			request(
				listOf(sessionFact.reference, completeness.reference),
				listOf(corrupt.reference),
			),
			read(sessionFact, completeness, corrupt),
		) shouldBe StepsCountDomainCompatibilityResult.Unverifiable

		val staleRead = read(sessionFact, completeness, ambient).let { ready ->
			ready.copy(
				latestRevisions = ready.latestRevisions +
					(StepsCountDomainOwnerLineageKey(
						ambient.key.ownerKind,
						ambient.key.ownerIdentity,
					) to 2L),
			)
		}
		resolveStepsCountDomainCompatibility(baseRequest, staleRead) shouldBe
			StepsCountDomainCompatibilityResult.Unverifiable
	}

	private fun request(
		session: List<StepsCountDomainOwnerReference>,
		ambient: List<StepsCountDomainOwnerReference>,
	) = StepsCountDomainCompatibilityRequest(session, ambient)

	private fun read(vararg evidence: Evidence): StepsCountDomainOwnerRead.Ready =
		StepsCountDomainOwnerRead.Ready(
			owners = evidence.associate { it.key to it.stored },
			latestRevisions = evidence.associate {
				StepsCountDomainOwnerLineageKey(it.key.ownerKind, it.key.ownerIdentity) to
					it.key.ownerRevision
			},
		)

	private fun evidence(
		kind: StepsCountDomainOwnerKind,
		identityDigit: Char,
		tokenDigit: Char,
		authorityRevision: Long = 1L,
		epoch: Long = 7L,
		registrationGeneration: Long = 1L,
	): Evidence {
		val ownerIdentity = opaque(identityDigit)
		val ownerKind = when (kind) {
			StepsCountDomainOwnerKind.SESSION_FACT ->
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS ->
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			StepsCountDomainOwnerKind.AMBIENT_FACT ->
				StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT
		}
		val coverage = when (kind) {
			StepsCountDomainOwnerKind.SESSION_FACT ->
				StepsCountDomainReceiptEntity.COVERAGE_COVERED
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS ->
				StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN
			StepsCountDomainOwnerKind.AMBIENT_FACT ->
				StepsCountDomainReceiptEntity.COVERAGE_AMBIENT_AGGREGATE
		}
		val domainIdentity = StepsCountDomainReceiptIntegrity.counterDomainIdentity(
			StepsCounterDomainToken.opaque(opaque(tokenDigit)),
		)
		val effect = identityDigit.toString().repeat(64)
		val scopeIdentity =
			if (kind == StepsCountDomainOwnerKind.AMBIENT_FACT) opaque('a') else opaque('e')
		val marker = if (kind == StepsCountDomainOwnerKind.SESSION_COMPLETENESS) {
			completenessMarker(ownerIdentity)
		} else {
			null
		}
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity,
			ownerKind,
			scopeIdentity,
			ownerIdentity,
			1L,
			registrationGeneration,
			epoch,
			authorityRevision,
			"a".repeat(64),
			coverage,
			1,
			StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effect,
			marker?.evidenceChecksum,
		)
		val receipt = StepsCountDomainReceiptEntity(
			receiptIdentity,
			domainIdentity,
			ownerKind,
			scopeIdentity,
			ownerIdentity,
			1L,
			registrationGeneration,
			epoch,
			authorityRevision,
			"a".repeat(64),
			coverage,
			1,
			StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effect,
			marker?.evidenceChecksum,
		)
		val owner = StepsCountDomainOwnerRevisionEntity(
			ownerKind,
			scopeIdentity,
			ownerIdentity,
			1L,
			StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
			receiptIdentity,
			effect,
			1L,
		)
		val reference = StepsCountDomainOwnerReference(
			kind,
			StepsCountDomainOwnerIdentity.opaque(ownerIdentity),
			1L,
			StepsCountDomainOwnerEffect.opaque(effect),
		)
		return Evidence(
			reference,
			StepsCountDomainOwnerLookupKey(ownerKind, ownerIdentity, 1L),
			StepsCountDomainStoredOwner(owner, receipt, marker),
		)
	}

	private fun completenessMarker(
		ownerIdentity: String,
	): StepsCountDomainCompletenessMarkerEntity {
		val timeline = "d".repeat(64)
		val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE,
			lastAdmissionOrdinal = 1L,
			lastSourceSequence = 1L,
			providerFlushOutcome = "COMPLETE",
			registrationRemovalOutcome = "REMOVED",
			registrationTimelineChecksum = timeline,
		)
		return StepsCountDomainCompletenessMarkerEntity(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE,
			lastAdmissionOrdinal = 1L,
			lastSourceSequence = 1L,
			providerFlushOutcome = "COMPLETE",
			registrationRemovalOutcome = "REMOVED",
			registrationTimelineChecksum = timeline,
			evidenceChecksum = checksum,
		)
	}

	private fun Evidence.asUnproven(): Evidence {
		val marker = requireNotNull(stored.completenessMarker)
		val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
			ownerKind = marker.ownerKind,
			ownerIdentity = marker.ownerIdentity,
			ownerRevision = marker.ownerRevision,
			terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
			lastAdmissionOrdinal = marker.lastAdmissionOrdinal,
			lastSourceSequence = marker.lastSourceSequence,
			providerFlushOutcome = "FAILED",
			registrationRemovalOutcome = marker.registrationRemovalOutcome,
			registrationTimelineChecksum = marker.registrationTimelineChecksum,
		)
		val unprovenMarker = marker.copy(
			terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
			providerFlushOutcome = "FAILED",
			evidenceChecksum = checksum,
		)
		return copy(
			stored = stored.copy(
				owner = stored.owner.copy(
					operation = StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN,
					receiptIdentity = null,
				),
				receipt = null,
				completenessMarker = unprovenMarker,
			),
		)
	}

	private fun opaque(character: Char) =
		"sha256:${character.toString().repeat(64)}"

	private data class Evidence(
		val reference: StepsCountDomainOwnerReference,
		val key: StepsCountDomainOwnerLookupKey,
		val stored: StepsCountDomainStoredOwner,
	)
}
