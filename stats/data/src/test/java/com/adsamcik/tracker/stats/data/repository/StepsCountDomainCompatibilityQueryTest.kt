package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLineageKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStoredOwner
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
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
	fun `same domain survives authority revision differences`() {
		val sessionFact = evidence(
			StepsCountDomainOwnerKind.SESSION_FACT,
			'1',
			"provider",
			"instance",
			authorityRevision = 1L,
		)
		val completeness = evidence(
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			'2',
			"provider",
			"instance",
			authorityRevision = 2L,
		)
		val ambient = evidence(
			StepsCountDomainOwnerKind.AMBIENT_FACT,
			'3',
			"provider",
			"instance",
			authorityRevision = 3L,
		)

		resolveStepsCountDomainCompatibility(
			request(listOf(sessionFact.reference, completeness.reference), listOf(ambient.reference)),
			read(sessionFact, completeness, ambient),
		) shouldBe StepsCountDomainCompatibilityResult.ExactCompatible
	}

	@Test
	fun `source instance and collected epoch changes conflict`() {
		val sessionFact = evidence(StepsCountDomainOwnerKind.SESSION_FACT, '1', "provider", "first")
		val completeness = evidence(
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			'2',
			"provider",
			"first",
		)
		val changedInstance = evidence(
			StepsCountDomainOwnerKind.AMBIENT_FACT,
			'3',
			"provider",
			"second",
		)
		val changedEpoch = evidence(
			StepsCountDomainOwnerKind.AMBIENT_FACT,
			'4',
			"provider",
			"first",
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
		val sessionFact = evidence(StepsCountDomainOwnerKind.SESSION_FACT, '5', "provider", "instance")
		val completeness = evidence(
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			'6',
			"provider",
			"instance",
		)
		val ambient = evidence(StepsCountDomainOwnerKind.AMBIENT_FACT, '7', "provider", "instance")
		val baseRequest = request(
			listOf(sessionFact.reference, completeness.reference),
			listOf(ambient.reference),
		)

		resolveStepsCountDomainCompatibility(
			baseRequest,
			read(sessionFact, completeness),
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
		provider: String,
		sourceInstance: String,
		authorityRevision: Long = 1L,
		epoch: Long = 7L,
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
		val providerIdentity =
			StepsCountDomainReceiptIntegrity.nativeProviderDomainIdentity(provider)
		val sourceIdentity =
			StepsCountDomainReceiptIntegrity.nativeSourceInstanceIdentity(sourceInstance)
		val domainIdentity =
			StepsCountDomainReceiptIntegrity.nativeDomainIdentity(provider, sourceInstance)
		val effect = identityDigit.toString().repeat(64)
		val scopeIdentity =
			if (kind == StepsCountDomainOwnerKind.AMBIENT_FACT) opaque('a') else opaque('e')
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity,
			providerIdentity,
			sourceIdentity,
			ownerKind,
			scopeIdentity,
			ownerIdentity,
			1L,
			1L,
			epoch,
			authorityRevision,
			"a".repeat(64),
			coverage,
			1,
			StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effect,
		)
		val receipt = StepsCountDomainReceiptEntity(
			receiptIdentity,
			domainIdentity,
			providerIdentity,
			sourceIdentity,
			ownerKind,
			scopeIdentity,
			ownerIdentity,
			1L,
			1L,
			epoch,
			authorityRevision,
			"a".repeat(64),
			coverage,
			1,
			StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effect,
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
			StepsCountDomainStoredOwner(owner, receipt),
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
