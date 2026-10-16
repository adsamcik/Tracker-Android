package com.adsamcik.tracker.stats.api.repository

/**
 * Opaque producer-owned identity for one count-domain owner.
 *
 * The encoded value is a namespaced SHA-256 identity. It never contains a provider account,
 * platform source id, stable user id, or device id.
 */
class StepsCountDomainOwnerIdentity private constructor(
	val encoded: String,
) {
	override fun equals(other: Any?): Boolean =
		other is StepsCountDomainOwnerIdentity && encoded == other.encoded

	override fun hashCode(): Int = encoded.hashCode()
	override fun toString(): String = "StepsCountDomainOwnerIdentity"

	/**
	 * Validates namespaced opaque owner identities without granting recording or mutation authority.
	 */
	companion object {
		/**
		 * Parses a namespaced SHA-256 owner identity; malformed encodings throw and parsing grants no
		 * source authority.
		 */
		fun opaque(encoded: String): StepsCountDomainOwnerIdentity {
			require(STEPS_COUNT_DOMAIN_OPAQUE_IDENTITY.matches(encoded))
			return StepsCountDomainOwnerIdentity(encoded)
		}
	}
}

/**
 * Opaque identity of one immutable count-domain receipt.
 *
 * Parsing an opaque identity grants no import, provider, session, writer, or deletion authority.
 * A query implementation must reauthenticate the stored receipt and its exact owner revision.
 */
class StepsCountDomainReceipt private constructor(
	val identity: String,
) {
	override fun equals(other: Any?): Boolean =
		other is StepsCountDomainReceipt && identity == other.identity

	override fun hashCode(): Int = identity.hashCode()
	override fun toString(): String = "StepsCountDomainReceipt"

	/**
	 * Validates namespaced opaque receipt identities; stored lineage must still be checked before
	 * use.
	 */
	companion object {
		/**
		 * Parses a namespaced SHA-256 receipt identity; the stored receipt and owner revision must still
		 * be verified.
		 */
		fun opaque(identity: String): StepsCountDomainReceipt {
			require(STEPS_COUNT_DOMAIN_OPAQUE_IDENTITY.matches(identity))
			return StepsCountDomainReceipt(identity)
		}
	}
}

/** Opaque owner-effect digest used to reauthenticate the selected source revision. */
class StepsCountDomainOwnerEffect private constructor(
	val encoded: String,
) {
	override fun equals(other: Any?): Boolean =
		other is StepsCountDomainOwnerEffect && encoded == other.encoded

	override fun hashCode(): Int = encoded.hashCode()
	override fun toString(): String = "StepsCountDomainOwnerEffect"

	/**
	 * Validates the canonical content-digest representation of an owner revision.
	 */
	companion object {
		/**
		 * Parses a lowercase SHA-256 owner-effect digest; malformed encodings throw.
		 */
		fun opaque(encoded: String): StepsCountDomainOwnerEffect {
			require(STEPS_COUNT_DOMAIN_DIGEST.matches(encoded))
			return StepsCountDomainOwnerEffect(encoded)
		}
	}
}

/** Source owner types accepted by the P5 compatibility proof. */
enum class StepsCountDomainOwnerKind {
	SESSION_FACT,
	SESSION_COMPLETENESS,
	AMBIENT_FACT,
}

/** Native and imported portable evidence remain separate authority namespaces. */
enum class StepsCountDomainOwnerOrigin {
	NATIVE,
	IMPORTED_PORTABLE,
}

/** Exact owner revision; interval, count, zone, and source display names are intentionally absent. */
data class StepsCountDomainOwnerReference(
	val kind: StepsCountDomainOwnerKind,
	val identity: StepsCountDomainOwnerIdentity,
	val revision: Long,
	val effect: StepsCountDomainOwnerEffect,
	val origin: StepsCountDomainOwnerOrigin = StepsCountDomainOwnerOrigin.NATIVE,
) {
	init {
		require(revision > 0L)
	}
}

/**
 * One native session-to-Ambient comparison.
 *
 * Session facts and terminal completeness must both be present. [StepsCountDomainOwnerOrigin]
 * keeps imported evidence separate from native authority while allowing their authenticated
 * source-domain keys to be compared.
 */
class StepsCountDomainCompatibilityRequest(
	sessionOwners: List<StepsCountDomainOwnerReference>,
	ambientOwners: List<StepsCountDomainOwnerReference>,
) {
	val exceedsOwnerBounds: Boolean =
		sessionOwners.size > MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_SIDE ||
			ambientOwners.size > MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_SIDE ||
			sessionOwners.size.toLong() + ambientOwners.size.toLong() >
			MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_REQUEST.toLong()
	private val sessionOwnersSnapshot =
		if (exceedsOwnerBounds) {
			emptyList()
		} else {
			sessionOwners.toList()
		}
	private val ambientOwnersSnapshot =
		if (exceedsOwnerBounds) {
			emptyList()
		} else {
			ambientOwners.toList()
		}

	val sessionOwners: List<StepsCountDomainOwnerReference>
		get() = sessionOwnersSnapshot.toList()
	val ambientOwners: List<StepsCountDomainOwnerReference>
		get() = ambientOwnersSnapshot.toList()

	init {
		require(sessionOwnersSnapshot.distinct().size == sessionOwnersSnapshot.size)
		require(ambientOwnersSnapshot.distinct().size == ambientOwnersSnapshot.size)
		require(sessionOwnersSnapshot.all {
			it.kind == StepsCountDomainOwnerKind.SESSION_FACT ||
				it.kind == StepsCountDomainOwnerKind.SESSION_COMPLETENESS
		})
		require(ambientOwnersSnapshot.all {
			it.kind == StepsCountDomainOwnerKind.AMBIENT_FACT
		})
	}
}

/**
 * Producer-authenticated count-domain result.
 *
 * Exact compatibility is never inferred from wall overlap, numeric equality, zone, or a source
 * display name. Missing either side remains [Unproven].
 */
sealed interface StepsCountDomainCompatibilityResult {
	/**
	 * Both selected sides have compatible exact producer-issued count-domain ownership.
	 */
	data object ExactCompatible : StepsCountDomainCompatibilityResult
	/**
	 * The selected source count domains conflict and cannot be added or treated as one domain.
	 */
	data object Conflict : StepsCountDomainCompatibilityResult
	/**
	 * The stored count-domain evidence cannot prove exact compatibility between both sides.
	 */
	data object Unproven : StepsCountDomainCompatibilityResult
	/**
	 * A selected count-domain owner or receipt has been deleted.
	 */
	data object Deleted : StepsCountDomainCompatibilityResult
	/**
	 * Stored count-domain identity or lineage cannot be verified.
	 */
	data object Unverifiable : StepsCountDomainCompatibilityResult
}

/** Bounded read-only compatibility API for day and numeric composers. */
interface StepsCountDomainCompatibilityQuery {
	/**
	 * Compares bounded selected owner lists in request order using source-issued count-domain
	 * lineage, not numeric equality or wall overlap.
	 */
	suspend fun compare(
		requests: List<StepsCountDomainCompatibilityRequest>,
	): List<StepsCountDomainCompatibilityResult>
}

const val MAX_STEPS_COUNT_DOMAIN_REQUESTS = 64
const val MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_SIDE = 256
const val MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_REQUEST = 512
const val MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_BATCH = 512

private val STEPS_COUNT_DOMAIN_OPAQUE_IDENTITY = Regex("sha256:[0-9a-f]{64}")
private val STEPS_COUNT_DOMAIN_DIGEST = Regex("[0-9a-f]{64}")
