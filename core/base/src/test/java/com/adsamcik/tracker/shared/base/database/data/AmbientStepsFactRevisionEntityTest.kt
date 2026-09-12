package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class AmbientStepsFactRevisionEntityTest {
	@Test
	fun `covered zero requires exact provider registration consent and structural day authority`() {
		val fact = providerFact(stepCount = 0L)

		fact.stepCount shouldBe 0L
		AmbientStepsFactIntegrity.hasValidEffectChecksum(fact) shouldBe true
		listOf<() -> Unit>(
			{ fact.copy(logicalFactId = "provider:0:1000") },
			{ fact.copy(provider = null) },
			{ fact.copy(registrationGeneration = 0L) },
			{ fact.copy(authorizationRevision = null) },
			{ fact.copy(windowEndTimeMs = fact.windowStartTimeMs) },
			{ fact.copy(stepCount = null) },
			{ fact.copy(stepCount = -1L) },
			{ fact.copy(storedZoneId = "Europe/Prague") },
			{ fact.copy(sourcePolicyRevision = 0L) },
			{ fact.copy(ambientConsentEpoch = null) },
		).forEach { invalid -> shouldThrow<IllegalArgumentException> { invalid() } }
	}

	@Test
	fun `local deletion revision is redacted and retains only opaque fence authority`() {
		val upsert = providerFact(stepCount = 12L)
		val retraction = signed(
			upsert.copy(
				semanticRevision = 2L,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					upsert.logicalFactId,
					2L,
					AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
				),
				operation = AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
				originKind = AmbientStepsFactRevisionEntity.ORIGIN_LOCAL_DELETE,
				provider = null,
				registrationGeneration = null,
				sourceInstanceId = null,
				authorizationRevision = null,
				authorizationFingerprint = null,
				windowStartTimeMs = null,
				windowEndTimeMs = null,
				observedAtMs = null,
				structuralEpochDay = null,
				storedZoneId = null,
				structuralDayStartTimeMs = null,
				structuralDayEndTimeMs = null,
				stepCount = null,
				sourcePolicyRevision = null,
				ambientConsentEpoch = null,
				scopeDeletionGeneration = 1L,
				appliedAtMs = 3_000L,
			),
		)

		AmbientStepsFactIntegrity.hasValidEffectChecksum(retraction) shouldBe true
		shouldThrow<IllegalArgumentException> {
			retraction.copy(windowStartTimeMs = 1_000L)
		}
	}

	private fun providerFact(stepCount: Long): AmbientStepsFactRevisionEntity {
		val provider = AmbientStepsFactRevisionEntity.PROVIDER_HEALTH_CONNECT_MOBILE_STEPS
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			provider,
			1_000L,
			2_000L,
			0L,
			"UTC",
			7L,
		)
		return signed(
			AmbientStepsFactRevisionEntity(
				logicalFactId = logicalFactId,
				semanticRevision = 1L,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					logicalFactId,
					1L,
					AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
				writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
				writerOwnerGeneration = 1L,
				operation = AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				originKind = AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
				provider = provider,
				registrationGeneration = 2L,
				sourceInstanceId = "ambient-instance",
				authorizationRevision = 3L,
				authorizationFingerprint = "ambient-authorization",
				windowStartTimeMs = 1_000L,
				windowEndTimeMs = 2_000L,
				observedAtMs = 2_000L,
				structuralEpochDay = 0L,
				storedZoneId = "UTC",
				structuralDayStartTimeMs = 0L,
				structuralDayEndTimeMs = 86_400_000L,
				stepCount = stepCount,
				purpose = AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
				sourcePolicyRevision = 4L,
				ambientConsentEpoch = 5L,
				collectedDataEpoch = 7L,
				scopeDeletionGeneration = 0L,
				effectChecksum = "0".repeat(64),
				appliedAtMs = 2_000L,
			),
		)
	}

	private fun signed(fact: AmbientStepsFactRevisionEntity): AmbientStepsFactRevisionEntity =
		fact.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(fact))
}
