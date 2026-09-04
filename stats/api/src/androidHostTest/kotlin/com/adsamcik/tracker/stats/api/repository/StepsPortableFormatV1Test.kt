package com.adsamcik.tracker.stats.api.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class StepsPortableFormatV1Test {
	@Test
	fun `opaque identities are stable namespaced hashes without the local identity`() {
		val raw = "logical/run/device-visible-identity"
		val logical = PortableStepsOpaqueIdentity.derive(
			PortableStepsIdentityKind.LOGICAL_ENTRY,
			raw,
		)
		val logicalReplay = PortableStepsOpaqueIdentity.derive(
			PortableStepsIdentityKind.LOGICAL_ENTRY,
			raw,
		)
		val run = PortableStepsOpaqueIdentity.derive(
			PortableStepsIdentityKind.PHYSICAL_RUN,
			raw,
		)

		logical shouldBe logicalReplay
		logical shouldNotBe run
		logical.value shouldNotContain raw
		logical.value shouldBe
			"sha256:381c12b425db4f1204c25e8534a7b1fc6b188a1c17ce7fa9d6b27968fccf41bc"
	}

	@Test
	fun `deletion scope is a stable unprefixed opaque durable fence key`() {
		val digest = PortableStepsDeletionScopeDigest.derive(
			logicalTrackingId = "logical-secret",
			serviceRunId = "run-secret",
		)

		digest.value shouldBe
			"0f11a409114f463bf27855efff10a25ccb1ed6bff35a96d2ffda00f9bb8ed899"
		digest.value shouldNotContain "logical-secret"
		digest.value shouldNotContain "run-secret"
		shouldThrow<IllegalArgumentException> {
			PortableStepsDeletionScopeDigest("sha256:${digest.value}")
		}
	}

	@Test
	fun `identity and semantic checksum are independent conflict dimensions`() {
		val identity = factIdentity("fact-1")
		val first = fact(identity = identity, count = 10L)
		val corrected = fact(identity = identity, count = 11L)

		first.identity shouldBe corrected.identity
		first.contentChecksum shouldNotBe corrected.contentChecksum
		first.contentChecksum.value shouldBe
			"sha256:14a512571cc6513c9962e93399441e1723a2565275d167d64a86aba86e7306b1"
	}

	@Test
	fun `non-covered evidence cannot fabricate zero while covered zero is explicit`() {
		val coveredZero = fact(
			identity = factIdentity("covered-zero"),
			count = 0L,
		)
		coveredZero.coverage shouldBe PortableStepsFactCoverage.COVERED
		coveredZero.stepCount shouldBe 0L

		shouldThrow<IllegalArgumentException> {
			PortableStepsFactV1.create(
				identity = factIdentity("bad-baseline"),
				manifestRevision = 1L,
				intervalStartTimeMs = 1_000L,
				intervalEndTimeMs = 1_000L,
				wallTimeUncertaintyMs = 0L,
				coverage = PortableStepsFactCoverage.BASELINE,
				stepCount = 0L,
			)
		}
		shouldThrow<IllegalArgumentException> {
			PortableStepsFactV1.create(
				identity = factIdentity("bad-partial"),
				manifestRevision = 1L,
				intervalStartTimeMs = 1_000L,
				intervalEndTimeMs = 2_000L,
				wallTimeUncertaintyMs = 0L,
				coverage = PortableStepsFactCoverage.PARTIAL,
				stepCount = 0L,
			)
		}

		val resetGap = PortableStepsFactV1.create(
			identity = factIdentity("reset-gap"),
			manifestRevision = 1L,
			intervalStartTimeMs = 1_000L,
			intervalEndTimeMs = 1_000L,
			wallTimeUncertaintyMs = 0L,
			coverage = PortableStepsFactCoverage.RESET_GAP,
			stepCount = null,
		)
		resetGap.stepCount shouldBe null
		val partial = PortableStepsFactV1.create(
			identity = factIdentity("partial"),
			manifestRevision = 1L,
			intervalStartTimeMs = 1_000L,
			intervalEndTimeMs = 2_000L,
			wallTimeUncertaintyMs = 0L,
			coverage = PortableStepsFactCoverage.PARTIAL,
			stepCount = null,
		)
		partial.stepCount shouldBe null
	}

	@Test
	fun `fact constructor rejects a checksum copied from different semantic content`() {
		val first = fact(factIdentity("fact-a"), count = 3L)
		val other = fact(factIdentity("fact-b"), count = 4L)

		shouldThrow<IllegalArgumentException> {
			first.copy(contentChecksum = other.contentChecksum)
		}
	}

	@Test
	fun `entry binds replacement runs in canonical physical order`() {
		val first = run(
			logicalTrackingId = "entry",
			identitySeed = "run-1",
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			fact = fact(factIdentity("fact-1"), 10L, 1_000L, 2_000L),
		)
		val replacement = run(
			logicalTrackingId = "entry",
			identitySeed = "run-2",
			startTimeMs = 2_000L,
			endTimeMs = 3_000L,
			fact = fact(factIdentity("fact-2"), 20L, 2_000L, 3_000L),
		)
		val entry = PortableStepsEntryV1.create(
			identity = entryIdentity("entry"),
			sessionMode = PortableStepsSessionMode.MANUAL,
			startTimeMs = 1_000L,
			endTimeMs = 3_000L,
			runs = listOf(first, replacement),
		)

		entry.runs.map { it.identity } shouldContainExactly listOf(first.identity, replacement.identity)
		PortableStepsIntegrity.expectedEntryChecksum(entry) shouldBe entry.contentChecksum
		entry.contentChecksum.value shouldBe
			"sha256:b791b81501aefde2fa5c434a4803895d61bf28e6eae0de5cda398b976ce6d926"

		shouldThrow<IllegalArgumentException> {
			PortableStepsEntryV1.create(
				identity = entryIdentity("entry-repeated-deletion-scope"),
				sessionMode = PortableStepsSessionMode.MANUAL,
				startTimeMs = 1_000L,
				endTimeMs = 3_000L,
				runs = listOf(
					first,
					replacement.copy(deletionScopeDigest = first.deletionScopeDigest),
				),
			)
		}

		shouldThrow<IllegalArgumentException> {
			PortableStepsEntryV1.create(
				identity = entryIdentity("entry-reordered"),
				sessionMode = PortableStepsSessionMode.MANUAL,
				startTimeMs = 1_000L,
				endTimeMs = 3_000L,
				runs = listOf(replacement, first),
			)
		}
	}

	@Test
	fun `run wall envelope does not claim manifest or fact ownership`() {
		val clockJumpFact = fact(
			identity = factIdentity("clock-jump"),
			count = 3L,
			startTimeMs = 100L,
			endTimeMs = 200L,
		)
		val run = run(
			logicalTrackingId = "entry",
			identitySeed = "clock-jump-run",
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			fact = clockJumpFact,
		).copy(
			manifests = listOf(
				PortableStepsManifestV1(
					revision = 1L,
					effectiveWallTimeMs = 50L,
					originSourcePolicyRevision = 7L,
					captureConsentEpoch = 3L,
				),
			),
		)

		run.facts.single() shouldBe clockJumpFact
		run.manifests.single().effectiveWallTimeMs shouldBe 50L
	}

	@Test
	fun `schema v1 has only Steps session capture membership`() {
		PortableStepsSource.entries shouldContainExactly listOf(PortableStepsSource.STEPS)
		PortableStepsPurpose.entries shouldContainExactly listOf(
			PortableStepsPurpose.SESSION_CAPTURE,
		)
	}

	@Test
	fun `export selection and success counts are finite and non-empty`() {
		val request = ExportPortableStepsRequest(fromInclusiveMs = 1_000L, toExclusiveMs = 2_000L)
		request.toExclusiveMs - request.fromInclusiveMs shouldBe 1_000L
		shouldThrow<IllegalArgumentException> {
			ExportPortableStepsRequest(fromInclusiveMs = 1_000L, toExclusiveMs = 1_000L)
		}
		shouldThrow<IllegalArgumentException> {
			ExportPortableStepsResult.Exported(entryCount = 0)
		}
	}

	@Test
	fun `import outcomes cannot collapse replay privacy fences retention and conflict`() {
		val outcomes = setOf(
			ImportPortableStepsResult.Duplicate,
			ImportPortableStepsResult.DeletedScope,
			ImportPortableStepsResult.OutsideRetention,
			ImportPortableStepsResult.Conflict(PortableStepsConflictScope.FACT),
		)

		outcomes.size shouldBe 4
	}

	private fun factIdentity(seed: String) = PortableStepsOpaqueIdentity.derive(
		PortableStepsIdentityKind.FACT,
		seed,
	)

	private fun entryIdentity(seed: String) = PortableStepsOpaqueIdentity.derive(
		PortableStepsIdentityKind.LOGICAL_ENTRY,
		seed,
	)

	private fun fact(
		identity: PortableStepsOpaqueIdentity,
		count: Long,
		startTimeMs: Long = 1_000L,
		endTimeMs: Long = 2_000L,
	): PortableStepsFactV1 = PortableStepsFactV1.create(
		identity = identity,
		manifestRevision = 1L,
		intervalStartTimeMs = startTimeMs,
		intervalEndTimeMs = endTimeMs,
		wallTimeUncertaintyMs = 25L,
		coverage = PortableStepsFactCoverage.COVERED,
		stepCount = count,
	)

	private fun run(
		logicalTrackingId: String,
		identitySeed: String,
		startTimeMs: Long,
		endTimeMs: Long,
		fact: PortableStepsFactV1,
	): PortableStepsRunV1 = PortableStepsRunV1(
		identity = PortableStepsOpaqueIdentity.derive(
			PortableStepsIdentityKind.PHYSICAL_RUN,
			identitySeed,
		),
		deletionScopeDigest = PortableStepsDeletionScopeDigest.derive(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = identitySeed,
		),
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		storedZoneId = "Europe/Prague",
		manifests = listOf(
			PortableStepsManifestV1(
				revision = 1L,
				effectiveWallTimeMs = startTimeMs,
				originSourcePolicyRevision = 7L,
				captureConsentEpoch = 3L,
			),
		),
		completeness = PortableStepsCompletenessV1(
			captureCoverage = PortableStepsCaptureCoverage.WHOLE_RUN,
			providerCoverage = PortableStepsProviderCoverage.COMPLETE,
			appDrainComplete = true,
			stopComplete = true,
			hasUnresolvedProviderRange = false,
		),
		facts = listOf(fact),
	)
}
