package com.adsamcik.tracker.shared.base.database.data

import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class StepsCountDomainReceiptEntityTest {
	@Test
	fun `receipt identity binds domain epoch authority coverage version and effect`() {
		val base = receipt()

		receipt(tokenDigit = 'b').receiptIdentity shouldNotBe base.receiptIdentity
		receipt(registrationGeneration = 3L).receiptIdentity shouldNotBe base.receiptIdentity
		receipt(collectedDataEpoch = 8L).receiptIdentity shouldNotBe base.receiptIdentity
		receipt(authorityRevision = 4L).receiptIdentity shouldNotBe base.receiptIdentity
		receipt(
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_BASELINE,
		).receiptIdentity shouldNotBe base.receiptIdentity
		receipt(coverageVersion = 2).receiptIdentity shouldNotBe base.receiptIdentity
		receipt(effectChecksum = "e".repeat(64)).receiptIdentity shouldNotBe base.receiptIdentity
		StepsCountDomainReceiptIntegrity.hasValidReceipt(base) shouldBe true
	}

	@Test
	fun `receipt uses only the provider-issued opaque counter token`() {
		val receipt = receipt(tokenDigit = 'c')

		receipt.domainIdentity shouldBe opaque('c')
		receipt.toString().contains("provider-account") shouldBe false
		receipt.toString().contains("stable-device-instance") shouldBe false
	}

	@Test
	fun `portable extension derives identity but grants no stored native owner`() {
		val portable = StepsCountDomainReceiptIntegrity.portableDomainIdentity(
			"tracker.steps.portable.v2",
			opaque('f'),
		)

		StepsCountDomainReceiptIntegrity.isOpaque(portable) shouldBe true
		StepsCountDomainOwnerRevisionEntity.BINDABLE_OWNER_KINDS.any {
			it.contains("PORTABLE")
		} shouldBe false
	}

	private fun receipt(
		tokenDigit: Char = 'a',
		collectedDataEpoch: Long = 7L,
		authorityRevision: Long = 3L,
		registrationGeneration: Long = 2L,
		coverageKind: String = StepsCountDomainReceiptEntity.COVERAGE_COVERED,
		coverageVersion: Int = 1,
		effectChecksum: String = "d".repeat(64),
	): StepsCountDomainReceiptEntity {
		val domainIdentity = StepsCountDomainReceiptIntegrity.counterDomainIdentity(
			StepsCounterDomainToken.opaque(opaque(tokenDigit)),
		)
		val ownerIdentity = opaque('a')
		val scopeIdentity = opaque('9')
		val identity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = domainIdentity,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = "c".repeat(64),
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = null,
		)
		return StepsCountDomainReceiptEntity(
			receiptIdentity = identity,
			domainIdentity = domainIdentity,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = "c".repeat(64),
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = null,
		)
	}

	private fun opaque(digit: Char) = "sha256:${digit.toString().repeat(64)}"
}
