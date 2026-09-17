package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class StepsCountDomainReceiptEntityTest {
	@Test
	fun `receipt identity binds domain epoch authority coverage version and effect`() {
		val base = receipt()

		receipt(sourceInstance = "instance-b").receiptIdentity shouldNotBe base.receiptIdentity
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
	fun `native receipt retains only one-way provider and source identities`() {
		val provider = "raw-provider-account"
		val sourceInstance = "stable-device-instance"
		val receipt = receipt(providerDomain = provider, sourceInstance = sourceInstance)

		receipt.toString().contains(provider) shouldBe false
		receipt.toString().contains(sourceInstance) shouldBe false
		receipt.providerDomainIdentity.contains(provider) shouldBe false
		receipt.sourceInstanceIdentity.contains(sourceInstance) shouldBe false
		receipt.domainIdentity.contains(provider) shouldBe false
		receipt.domainIdentity.contains(sourceInstance) shouldBe false
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
		providerDomain: String = "provider-domain",
		sourceInstance: String = "instance-a",
		collectedDataEpoch: Long = 7L,
		authorityRevision: Long = 3L,
		coverageKind: String = StepsCountDomainReceiptEntity.COVERAGE_COVERED,
		coverageVersion: Int = 1,
		effectChecksum: String = "d".repeat(64),
	): StepsCountDomainReceiptEntity {
		val providerIdentity =
			StepsCountDomainReceiptIntegrity.nativeProviderDomainIdentity(providerDomain)
		val sourceIdentity =
			StepsCountDomainReceiptIntegrity.nativeSourceInstanceIdentity(sourceInstance)
		val domainIdentity =
			StepsCountDomainReceiptIntegrity.nativeDomainIdentity(providerDomain, sourceInstance)
		val ownerIdentity = opaque('a')
		val scopeIdentity = opaque('9')
		val identity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = domainIdentity,
			providerDomainIdentity = providerIdentity,
			sourceInstanceIdentity = sourceIdentity,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			registrationGeneration = 2L,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = "c".repeat(64),
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effectChecksum = effectChecksum,
		)
		return StepsCountDomainReceiptEntity(
			receiptIdentity = identity,
			domainIdentity = domainIdentity,
			providerDomainIdentity = providerIdentity,
			sourceInstanceIdentity = sourceIdentity,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			registrationGeneration = 2L,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = "c".repeat(64),
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effectChecksum = effectChecksum,
		)
	}

	private fun opaque(digit: Char) = "sha256:${digit.toString().repeat(64)}"
}
