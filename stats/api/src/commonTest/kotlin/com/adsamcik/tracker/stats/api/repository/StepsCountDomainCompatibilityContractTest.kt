package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StepsCountDomainCompatibilityContractTest {
	@Test
	fun `public owner contract contains no raw provider or stable device identifier`() {
		val rawProvider = "raw-provider-account"
		val rawDevice = "stable-device-id"
		val owner = StepsCountDomainOwnerIdentity.opaque(opaque('a'))
		val receipt = StepsCountDomainReceipt.opaque(opaque('b'))
		val reference = StepsCountDomainOwnerReference(
			StepsCountDomainOwnerKind.SESSION_FACT,
			owner,
			1L,
			StepsCountDomainOwnerEffect.opaque("c".repeat(64)),
		)

		assertEquals("StepsCountDomainOwnerIdentity", owner.toString())
		assertEquals("StepsCountDomainReceipt", receipt.toString())
		assertFalse(reference.toString().contains(rawProvider))
		assertFalse(reference.toString().contains(rawDevice))
		assertFalse(receipt.toString().contains(receipt.identity))
	}

	@Test
	fun `request is bounded typed and defensively immutable`() {
		val sessionInput = mutableListOf(reference(StepsCountDomainOwnerKind.SESSION_FACT, '1'))
		val ambientInput = mutableListOf(reference(StepsCountDomainOwnerKind.AMBIENT_FACT, '2'))
		val request = StepsCountDomainCompatibilityRequest(sessionInput, ambientInput)
		sessionInput.clear()
		ambientInput.clear()

		assertEquals(1, request.sessionOwners.size)
		assertEquals(1, request.ambientOwners.size)
		(request.sessionOwners as MutableList<*>).clear()
		(request.ambientOwners as MutableList<*>).clear()
		assertEquals(1, request.sessionOwners.size)
		assertEquals(1, request.ambientOwners.size)

		assertFailsWith<IllegalArgumentException> {
			StepsCountDomainCompatibilityRequest(
				listOf(reference(StepsCountDomainOwnerKind.AMBIENT_FACT, '3')),
				emptyList(),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsCountDomainCompatibilityRequest(
				emptyList(),
				listOf(reference(StepsCountDomainOwnerKind.SESSION_FACT, '4')),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsCountDomainCompatibilityRequest(
				List(MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_SIDE + 1) { index ->
					reference(
						StepsCountDomainOwnerKind.SESSION_FACT,
						"0123456789abcdef"[index % 16],
						index.toLong() + 1L,
					)
				},
				emptyList(),
			)
		}
	}

	@Test
	fun `result surface keeps absence conflict deletion and corruption distinct`() {
		assertEquals(
			5,
			listOf(
				StepsCountDomainCompatibilityResult.ExactCompatible,
				StepsCountDomainCompatibilityResult.Conflict,
				StepsCountDomainCompatibilityResult.Unproven,
				StepsCountDomainCompatibilityResult.Deleted,
				StepsCountDomainCompatibilityResult.Unverifiable,
			).distinct().size,
		)
	}

	private fun reference(
		kind: StepsCountDomainOwnerKind,
		digit: Char,
		revision: Long = 1L,
	) = StepsCountDomainOwnerReference(
		kind,
		StepsCountDomainOwnerIdentity.opaque(opaque(digit)),
		revision,
		StepsCountDomainOwnerEffect.opaque(digit.toString().repeat(64)),
	)

	private fun opaque(digit: Char): String = "sha256:${digit.toString().repeat(64)}"
}
