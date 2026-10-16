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
	val canonicalStage: String,
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
		require(canonicalStage.isNotBlank())
	}

	val canonicalOwnerGeneration: Long =
		SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration)
	val containedOwnerGeneration: Long =
		SourceWriterGenerationContract.containedOwnerGeneration(bindingGeneration)

	/**
	 * Copies this writer binding with its next generation while preserving its source, destination
	 * and projection contract.
	 */
	fun next(): SourceWriterGenerationBinding = copy(
		bindingGeneration = SourceWriterGenerationContract.nextBindingGeneration(bindingGeneration),
	)

	/**
	 * Builds the exact canonical activation identity for this binding and supplied activation
	 * revision.
	 */
	fun canonicalActivation(activationRevision: Long): SourceWriterCanonicalActivation =
		SourceWriterCanonicalActivation(
			sourceKind = sourceKind,
			destination = destination,
			writerOwner = candidateOwner,
			writerOwnerGeneration = canonicalOwnerGeneration,
			writerBindingGeneration = bindingGeneration,
			writerProjectionId = projectionId,
			writerProjectionVersion = projectionVersion,
			productStage = canonicalStage,
			activationRevision = activationRevision,
		)

	/**
	 * Builds the permanent destination owner expected when this binding is contained.
	 */
	fun containedDestinationOwner(): SourceWriterDestinationOwner =
		SourceWriterDestinationOwner(
			sourceKind = sourceKind,
			destination = destination,
			owner = containedOwner,
			ownerGeneration = containedOwnerGeneration,
		)

	/**
	 * Copies immutable writer provenance for historical reads without including current activation
	 * state.
	 */
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

	/**
	 * Compares all canonical activation values, including the expected activation revision.
	 */
	fun matchesCanonicalActivation(
		actual: SourceWriterCanonicalActivation,
		expectedActivationRevision: Long,
	): Boolean = actual == canonicalActivation(expectedActivationRevision)

	/**
	 * Compares the complete expected containment destination owner.
	 */
	fun matchesContainedOwner(actual: SourceWriterDestinationOwner): Boolean =
		actual == containedDestinationOwner()

	/**
	 * Compares immutable source/destination/owner/projection provenance for this binding.
	 */
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

	/**
	 * Copies immutable writer provenance for historical reads without including current activation
	 * state.
	 */
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
	val canonicalStage: String,
) {
	init {
		require(sourceKind > 0)
		require(destination.isNotBlank())
		require(candidateOwner.isNotBlank())
		require(containedOwner.isNotBlank())
		require(candidateOwner != containedOwner)
		require(projectionId.isNotBlank())
		require(projectionVersion > 0)
		require(canonicalStage.isNotBlank())
	}

	/**
	 * Builds a generation-specific writer binding from this capability's unchanged source and
	 * projection contract.
	 */
	fun binding(bindingGeneration: Long): SourceWriterGenerationBinding =
		SourceWriterGenerationBinding(
			sourceKind = sourceKind,
			destination = destination,
			candidateOwner = candidateOwner,
			containedOwner = containedOwner,
			bindingGeneration = bindingGeneration,
			projectionId = projectionId,
			projectionVersion = projectionVersion,
			canonicalStage = canonicalStage,
		)

	/**
	 * Compares the full source, destination, owner, projection and canonical-stage contract.
	 */
	fun matchesIdentity(
		sourceKind: Int,
		destination: String,
		candidateOwner: String,
		containedOwner: String,
		projectionId: String,
		projectionVersion: Int,
		canonicalStage: String,
	): Boolean = this.sourceKind == sourceKind &&
		this.destination == destination &&
		this.candidateOwner == candidateOwner &&
		this.containedOwner == containedOwner &&
		this.projectionId == projectionId &&
		this.projectionVersion == projectionVersion &&
		this.canonicalStage == canonicalStage

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

/**
 * Source-owned declaration of support for new writes under an exact generation binding.
 */
fun interface SourceWriterRearmWriterSupport {
	/**
	 * Reports whether this exact binding can accept new source writes.
	 */
	fun supportsNewWrites(binding: SourceWriterGenerationBinding): Boolean
}

/**
 * Source-owned declaration that immutable facts from an exact writer provenance remain supported.
 */
fun interface SourceWriterRearmFactsSupport {
	/**
	 * Reports whether saved facts with this immutable writer provenance remain supported.
	 */
	fun supportsImmutableFacts(provenance: SourceWriterHistoricalProvenance): Boolean
}

/**
 * Source-owned declaration that historical readers support an exact immutable provenance.
 */
fun interface SourceWriterRearmReaderSupport {
	/**
	 * Reports whether historical readers support this immutable writer provenance.
	 */
	fun supportsHistoricalReads(provenance: SourceWriterHistoricalProvenance): Boolean
}

/**
 * Source-owned declaration that retention/recovery maintenance supports an exact immutable
 * provenance.
 */
fun interface SourceWriterRearmMaintenanceSupport {
	/**
	 * Reports whether retention/recovery maintenance supports this immutable writer provenance.
	 */
	fun supportsHistoricalMaintenance(provenance: SourceWriterHistoricalProvenance): Boolean
}

/**
 * Source-owned declaration that export/import supports an exact immutable provenance.
 */
fun interface SourceWriterRearmTransferSupport {
	/**
	 * Reports whether export/import supports this immutable writer provenance.
	 */
	fun supportsHistoricalTransfer(provenance: SourceWriterHistoricalProvenance): Boolean
}

/**
 * Source-owned declaration that deletion supports an exact immutable provenance.
 */
fun interface SourceWriterRearmDeletionSupport {
	/**
	 * Reports whether deletion can retract data with this immutable writer provenance.
	 */
	fun supportsHistoricalDeletion(provenance: SourceWriterHistoricalProvenance): Boolean
}

/**
 * Source-owned authority that must keep its exact full-deletion proof valid through [operation].
 */
interface SourceWriterFullDeletionRearmAuthority {
	/**
	 * Runs the operation only while the supplied full-deletion rearm authority remains valid; absent
	 * authority returns null.
	 */
	suspend fun <T : Any> runIfAuthorized(
		input: SourceWriterRearmAuthorityInput,
		operation: suspend () -> T,
	): T?

	/**
	 * Unavailable authority sentinel; it never executes the supplied rearm operation.
	 */
	companion object {
		val UNAVAILABLE: SourceWriterFullDeletionRearmAuthority =
			object : SourceWriterFullDeletionRearmAuthority {
			/**
			 * Runs the operation only while the supplied full-deletion rearm authority remains valid;
			 * absent authority returns null.
			 */
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
	/**
	 * Requires writer, facts, reads, maintenance, transfer and deletion support for the same exact
	 * binding.
	 */
	fun fullySupports(binding: SourceWriterGenerationBinding): Boolean {
		if (capability.bindingForHistoricalProvenance(binding.historicalProvenance()) != binding) {
			return false
		}
		val provenance = binding.historicalProvenance()
		return writer.supportsNewWrites(binding) &&
			facts.supportsImmutableFacts(provenance) &&
			readers.supportsHistoricalReads(provenance) &&
			maintenance.supportsHistoricalMaintenance(provenance) &&
			transfer.supportsHistoricalTransfer(provenance) &&
			deletion.supportsHistoricalDeletion(provenance)
	}

	/**
	 * Returns the next binding only when the retired and next generations have full support and the
	 * rearm input matches.
	 */
	fun nextBindingIfFullySupported(
		input: SourceWriterRearmAuthorityInput,
	): SourceWriterGenerationBinding? {
		val retiredBinding = capability.bindingForHistoricalProvenance(
			input.retiredCanonicalActivation.historicalProvenance(),
		) ?: return null
		val nextBinding = capability.nextBindingAfter(input) ?: return null
		if (!fullySupports(retiredBinding) || !fullySupports(nextBinding)) {
			return null
		}
		return nextBinding
	}

	/**
	 * Runs with the next binding only after full support and source-owned deletion authority both
	 * accept the input.
	 */
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
	/**
	 * Returns the source-owned complete rearm support declaration, or null when none is installed.
	 */
	fun declarationFor(sourceKind: Int): SourceWriterRearmSupportDeclaration?

	/**
	 * Empty source-support resolver used when no complete rearm implementation is installed.
	 */
	companion object {
		val NONE: SourceWriterRearmSupportResolver = SourceWriterRearmSupportResolver { null }
	}
}
