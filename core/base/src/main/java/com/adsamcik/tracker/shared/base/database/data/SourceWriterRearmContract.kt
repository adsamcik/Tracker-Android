package com.adsamcik.tracker.shared.base.database.data

/**
 * Immutable source-owned identity shared by every generation-aware producer and consumer.
 *
 * A current destination-owner row is only an admission fence. Historical facts continue to resolve
 * through the exact provenance captured here, even after a later generation becomes canonical.
 */
data class SourceWriterGenerationBinding(
	val sourceKind: Int,
	val destination: String,
	val candidateOwner: String,
	val containedOwner: String,
	val bindingGeneration: Long,
	val projectionId: String,
	val projectionVersion: Int,
) {
	init {
		require(sourceKind > 0)
		require(destination.isNotBlank())
		require(candidateOwner.isNotBlank())
		require(containedOwner.isNotBlank())
		require(candidateOwner != containedOwner)
		require(bindingGeneration > 0L)
		require(projectionId.isNotBlank())
		require(projectionVersion > 0)
	}

	val canonicalOwnerGeneration: Long =
		SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration)
	val containedOwnerGeneration: Long =
		SourceWriterGenerationContract.containedOwnerGeneration(bindingGeneration)

	fun next(): SourceWriterGenerationBinding = copy(
		bindingGeneration = SourceWriterGenerationContract.nextBindingGeneration(bindingGeneration),
	)

	fun canonicalActivation(activationRevision: Long): SourceWriterCanonicalActivation =
		SourceWriterCanonicalActivation(
			sourceKind = sourceKind,
			destination = destination,
			writerOwner = candidateOwner,
			writerOwnerGeneration = canonicalOwnerGeneration,
			writerBindingGeneration = bindingGeneration,
			writerProjectionId = projectionId,
			writerProjectionVersion = projectionVersion,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			activationRevision = activationRevision,
		)

	fun containedDestinationOwner(): SourceWriterDestinationOwner =
		SourceWriterDestinationOwner(
			sourceKind = sourceKind,
			destination = destination,
			owner = containedOwner,
			ownerGeneration = containedOwnerGeneration,
		)

	fun historicalProvenance(): SourceWriterHistoricalProvenance =
		SourceWriterHistoricalProvenance(
			sourceKind = sourceKind,
			destination = destination,
			writerOwner = candidateOwner,
			writerOwnerGeneration = canonicalOwnerGeneration,
			writerBindingGeneration = bindingGeneration,
			writerProjectionId = projectionId,
			writerProjectionVersion = projectionVersion,
		)

	fun matchesCanonicalActivation(
		actual: SourceWriterCanonicalActivation,
		expectedActivationRevision: Long,
	): Boolean = actual == canonicalActivation(expectedActivationRevision)

	fun matchesContainedOwner(actual: SourceWriterDestinationOwner): Boolean =
		actual == containedDestinationOwner()

	fun matchesHistoricalProvenance(actual: SourceWriterHistoricalProvenance): Boolean =
		actual == historicalProvenance()
}

/**
 * Exact canonical lane identity required by rollback and `AlreadyApplied` decisions.
 *
 * This type intentionally permits a noncanonical observed [productStage]. Callers compare it with
 * [SourceWriterGenerationBinding.matchesCanonicalActivation] and fail closed on any mismatch.
 */
data class SourceWriterCanonicalActivation(
	val sourceKind: Int,
	val destination: String,
	val writerOwner: String,
	val writerOwnerGeneration: Long,
	val writerBindingGeneration: Long,
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
	val productStage: String,
	val activationRevision: Long,
) {
	init {
		require(sourceKind > 0)
		require(destination.isNotBlank())
		require(writerOwner.isNotBlank())
		require(writerOwnerGeneration > 0L)
		require(writerBindingGeneration > 0L)
		require(writerProjectionId.isNotBlank())
		require(writerProjectionVersion > 0)
		require(productStage.isNotBlank())
		require(activationRevision > 0L)
	}

	fun historicalProvenance(): SourceWriterHistoricalProvenance =
		SourceWriterHistoricalProvenance(
			sourceKind = sourceKind,
			destination = destination,
			writerOwner = writerOwner,
			writerOwnerGeneration = writerOwnerGeneration,
			writerBindingGeneration = writerBindingGeneration,
			writerProjectionId = writerProjectionId,
			writerProjectionVersion = writerProjectionVersion,
		)
}

/** Exact permanent destination-owner row expected after one canonical generation is contained. */
data class SourceWriterDestinationOwner(
	val sourceKind: Int,
	val destination: String,
	val owner: String,
	val ownerGeneration: Long,
) {
	init {
		require(sourceKind > 0)
		require(destination.isNotBlank())
		require(owner.isNotBlank())
		require(ownerGeneration > 0L)
	}
}

/**
 * Immutable fact provenance used by readers, maintenance, transfer and deletion.
 *
 * Resolution is intentionally independent of the current destination owner. Reusing only a
 * projection ID/version or only the newest binding would hide or make undeletable older facts.
 */
data class SourceWriterHistoricalProvenance(
	val sourceKind: Int,
	val destination: String,
	val writerOwner: String,
	val writerOwnerGeneration: Long,
	val writerBindingGeneration: Long,
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
) {
	init {
		require(sourceKind > 0)
		require(destination.isNotBlank())
		require(writerOwner.isNotBlank())
		require(writerOwnerGeneration > 0L)
		require(writerBindingGeneration > 0L)
		require(writerProjectionId.isNotBlank())
		require(writerProjectionVersion > 0)
	}
}

/**
 * Exact durable full-deletion receipt presented to a source-owned rearm authority.
 *
 * Source implementations may require stronger source-local fences while holding their transaction;
 * these shared fields never replace those proofs or create a generic deletion implementation.
 */
data class SourceWriterCompletedFullDeletion(
	val collectedDataEpoch: Long,
	val sourceDeletionGeneration: Long,
	val sourceEvidenceRevision: Long,
	val deletedSourceEventHighWaterOrdinal: Long,
	val completedAtMs: Long,
) {
	init {
		require(collectedDataEpoch > 0L)
		require(sourceDeletionGeneration > 0L)
		require(sourceEvidenceRevision > 0L)
		require(deletedSourceEventHighWaterOrdinal >= 0L)
		require(completedAtMs >= 0L)
	}
}

/** Complete, exact authority input for one contained generation to create its successor binding. */
data class SourceWriterRearmAuthorityInput(
	val retiredCanonicalActivation: SourceWriterCanonicalActivation,
	val containedDestinationOwner: SourceWriterDestinationOwner,
	val completedFullDeletion: SourceWriterCompletedFullDeletion,
)

/**
 * One source's immutable declaration of writer semantics across arbitrary continuing cycles.
 *
 * Constructing a declaration does not register support, activate a writer, or authorize rearm.
 */
data class SourceWriterRearmCapability(
	val sourceKind: Int,
	val destination: String,
	val candidateOwner: String,
	val containedOwner: String,
	val projectionId: String,
	val projectionVersion: Int,
) {
	init {
		require(sourceKind > 0)
		require(destination.isNotBlank())
		require(candidateOwner.isNotBlank())
		require(containedOwner.isNotBlank())
		require(candidateOwner != containedOwner)
		require(projectionId.isNotBlank())
		require(projectionVersion > 0)
	}

	fun binding(bindingGeneration: Long): SourceWriterGenerationBinding =
		SourceWriterGenerationBinding(
			sourceKind = sourceKind,
			destination = destination,
			candidateOwner = candidateOwner,
			containedOwner = containedOwner,
			bindingGeneration = bindingGeneration,
			projectionId = projectionId,
			projectionVersion = projectionVersion,
		)

	/**
	 * Resolves old facts solely from their immutable provenance, never from the current owner row.
	 */
	fun bindingForHistoricalProvenance(
		provenance: SourceWriterHistoricalProvenance,
	): SourceWriterGenerationBinding? = try {
		binding(provenance.writerBindingGeneration)
			.takeIf { it.matchesHistoricalProvenance(provenance) }
	} catch (_: ArithmeticException) {
		null
	}

	/**
	 * Returns the next binding only for an exact retired canonical lane, exact contained owner and
	 * representable checked generation arithmetic.
	 */
	fun nextBindingAfter(
		input: SourceWriterRearmAuthorityInput,
	): SourceWriterGenerationBinding? {
		val retired = bindingForHistoricalProvenance(
			input.retiredCanonicalActivation.historicalProvenance(),
		) ?: return null
		if (!retired.matchesCanonicalActivation(
				input.retiredCanonicalActivation,
				input.retiredCanonicalActivation.activationRevision,
			) ||
			!retired.matchesContainedOwner(input.containedDestinationOwner)
		) {
			return null
		}
		return try {
			val nextGeneration = SourceWriterGenerationContract
				.nextBindingGenerationForContainedOwner(
					input.containedDestinationOwner.ownerGeneration,
				) ?: return null
			binding(nextGeneration)
		} catch (_: ArithmeticException) {
			null
		}
	}
}

fun interface SourceWriterRearmWriterSupport {
	fun supportsNewWrites(binding: SourceWriterGenerationBinding): Boolean
}

fun interface SourceWriterRearmFactsSupport {
	fun supportsImmutableFacts(provenance: SourceWriterHistoricalProvenance): Boolean
}

fun interface SourceWriterRearmReaderSupport {
	fun supportsHistoricalReads(provenance: SourceWriterHistoricalProvenance): Boolean
}

fun interface SourceWriterRearmMaintenanceSupport {
	fun supportsHistoricalMaintenance(provenance: SourceWriterHistoricalProvenance): Boolean
}

fun interface SourceWriterRearmTransferSupport {
	fun supportsHistoricalTransfer(provenance: SourceWriterHistoricalProvenance): Boolean
}

fun interface SourceWriterRearmDeletionSupport {
	fun supportsHistoricalDeletion(provenance: SourceWriterHistoricalProvenance): Boolean
}

/**
 * Source-owned authority that must keep its exact full-deletion proof valid through [operation].
 */
interface SourceWriterFullDeletionRearmAuthority {
	suspend fun <T : Any> runIfAuthorized(
		input: SourceWriterRearmAuthorityInput,
		operation: suspend () -> T,
	): T?

	companion object {
		val UNAVAILABLE: SourceWriterFullDeletionRearmAuthority =
			object : SourceWriterFullDeletionRearmAuthority {
			override suspend fun <T : Any> runIfAuthorized(
				input: SourceWriterRearmAuthorityInput,
				operation: suspend () -> T,
			): T? = null
		}
	}
}

/**
 * Explicit all-surfaces declaration. Partial implementations cannot contribute rearm support.
 */
data class SourceWriterRearmSupportDeclaration(
	val capability: SourceWriterRearmCapability,
	val writer: SourceWriterRearmWriterSupport,
	val facts: SourceWriterRearmFactsSupport,
	val readers: SourceWriterRearmReaderSupport,
	val maintenance: SourceWriterRearmMaintenanceSupport,
	val transfer: SourceWriterRearmTransferSupport,
	val deletion: SourceWriterRearmDeletionSupport,
	val fullDeletionAuthority: SourceWriterFullDeletionRearmAuthority,
) {
	fun nextBindingIfFullySupported(
		input: SourceWriterRearmAuthorityInput,
	): SourceWriterGenerationBinding? {
		val retiredProvenance = input.retiredCanonicalActivation.historicalProvenance()
		val nextBinding = capability.nextBindingAfter(input) ?: return null
		val nextProvenance = nextBinding.historicalProvenance()
		if (!writer.supportsNewWrites(nextBinding) ||
			!facts.supportsImmutableFacts(retiredProvenance) ||
			!facts.supportsImmutableFacts(nextProvenance) ||
			!readers.supportsHistoricalReads(retiredProvenance) ||
			!readers.supportsHistoricalReads(nextProvenance) ||
			!maintenance.supportsHistoricalMaintenance(retiredProvenance) ||
			!maintenance.supportsHistoricalMaintenance(nextProvenance) ||
			!transfer.supportsHistoricalTransfer(retiredProvenance) ||
			!transfer.supportsHistoricalTransfer(nextProvenance) ||
			!deletion.supportsHistoricalDeletion(retiredProvenance) ||
			!deletion.supportsHistoricalDeletion(nextProvenance)
		) {
			return null
		}
		return nextBinding
	}

	suspend fun <T : Any> runIfFullySupportedAndAuthorized(
		input: SourceWriterRearmAuthorityInput,
		operation: suspend (SourceWriterGenerationBinding) -> T,
	): T? {
		val nextBinding = nextBindingIfFullySupported(input) ?: return null
		return fullDeletionAuthority.runIfAuthorized(input) {
			operation(nextBinding)
		}
	}
}

/**
 * Explicit source lookup seam. The production default is fail-closed and registers nothing.
 */
fun interface SourceWriterRearmSupportResolver {
	fun declarationFor(sourceKind: Int): SourceWriterRearmSupportDeclaration?

	companion object {
		val NONE: SourceWriterRearmSupportResolver = SourceWriterRearmSupportResolver { null }
	}
}
