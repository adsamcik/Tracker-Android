package com.adsamcik.tracker.shared.model.steps.portable

/** Additive authenticated Steps archive under the existing MIME type and extension. */
object StepsPortableFormatV2 {
	const val FORMAT: String = StepsPortableFormatV1.FORMAT
	const val SCHEMA_VERSION: Int = 2
	const val FILE_EXTENSION: String = StepsPortableFormatV1.FILE_EXTENSION
	const val MIME_TYPE: String = StepsPortableFormatV1.MIME_TYPE
	const val MAX_FILE_BYTES: Long = StepsPortableFormatV1.MAX_FILE_BYTES
	const val MAX_ENTRIES: Int = StepsPortableFormatV1.MAX_ENTRIES
}

/**
 * Pairs session Steps products with exact fact and completeness roots; no retracted owner can serve as a
 * current product root.
 */
data class PortableStepsEntryV2(
	val product: PortableStepsEntryV1,
	val countDomainGraph: PortableCountDomainGraphV2,
) {
	init {
		val runsByIdentity = product.runs.associateBy { it.identity.value }
		val factsByIdentity = product.runs.flatMap { run ->
			run.facts.map { fact -> fact.identity.value to run.identity.value }
		}.toMap()
		val sessionFactRoots = countDomainGraph.roots.filter {
			it.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT
		}
		val completenessRoots = countDomainGraph.roots.filter {
			it.ownerKind == PortableCountDomainOwnerKind.SESSION_COMPLETENESS
		}
		require(countDomainGraph.roots.size == sessionFactRoots.size + completenessRoots.size)
		require(sessionFactRoots.size == factsByIdentity.size)
		require(sessionFactRoots.map { it.productIdentity.value }.toSet() == factsByIdentity.keys)
		require(sessionFactRoots.all { root ->
			factsByIdentity[root.productIdentity.value] == root.containerIdentity.value
		})
		require(completenessRoots.map { it.productIdentity.value }.toSet() == runsByIdentity.keys)
		require(completenessRoots.all {
			it.containerIdentity == it.productIdentity
		})
		require(countDomainGraph.roots.none { root ->
			countDomainGraph.ownerRevisions.single {
				it.ownerKind == root.ownerKind &&
					it.ownerIdentity == root.ownerIdentity &&
					it.ownerRevision == root.ownerRevision
			}.operation == PortableCountDomainOperation.RETRACT
		})
	}
}

/** Ordered session Steps archive preserving unique runs, deletion scopes, facts and count-domain owner lineages. */
data class PortableStepsArchiveV2(
	val format: String = StepsPortableFormatV2.FORMAT,
	val schemaVersion: Int = StepsPortableFormatV2.SCHEMA_VERSION,
	val contentChecksum: PortableStepsDigest,
	val entries: List<PortableStepsEntryV2>,
) {
	init {
		require(format == StepsPortableFormatV2.FORMAT)
		require(schemaVersion == StepsPortableFormatV2.SCHEMA_VERSION)
		require(entries.isNotEmpty())
		require(entries.size <= StepsPortableFormatV2.MAX_ENTRIES)
		require(entries == entries.sortedWith(PORTABLE_STEPS_ENTRY_V2_ORDER))
		require(entries.map { it.product.identity }.distinct().size == entries.size)
		val runs = entries.flatMap { it.product.runs }
		val facts = runs.flatMap(PortableStepsRunV1::facts)
		require(runs.map(PortableStepsRunV1::identity).distinct().size == runs.size)
		require(
			runs.map(PortableStepsRunV1::deletionScopeDigest).distinct().size == runs.size,
		)
		require(facts.map(PortableStepsFactV1::identity).distinct().size == facts.size)
		val ownerLineages = entries.flatMap { it.countDomainGraph.roots }.map {
			it.ownerKind to it.ownerIdentity
		}
		require(ownerLineages.distinct().size == ownerLineages.size)
		require(contentChecksum == PortableStepsV2Integrity.archiveChecksum(entries))
	}

	/** Orders session entries before computing the v2 archive membership checksum. */
	companion object {
		/** Sorts session entries canonically and validates unique source ownership before returning the archive. */
		fun create(entries: List<PortableStepsEntryV2>): PortableStepsArchiveV2 {
			val ordered = entries.sortedWith(PORTABLE_STEPS_ENTRY_V2_ORDER)
			return PortableStepsArchiveV2(
				contentChecksum = PortableStepsV2Integrity.archiveChecksum(ordered),
				entries = ordered,
			)
		}
	}
}

val PORTABLE_STEPS_ENTRY_V2_ORDER: Comparator<PortableStepsEntryV2> =
	compareBy<PortableStepsEntryV2> { it.product.startTimeMs }
		.thenBy { it.product.identity.value }

/** Hashes session products with their exact count-domain graph identities and checksums. */
object PortableStepsV2Integrity {
	/**
	 * Hashes ordered session product and count-domain graph checksums, independent of export time or
	 * destination receipts.
	 */
	fun archiveChecksum(entries: List<PortableStepsEntryV2>): PortableStepsDigest =
		PortableStepsIntegrity.digest(
			"tracker-portable-steps-archive-v2",
			listOf(
				StepsPortableFormatV2.FORMAT,
				StepsPortableFormatV2.SCHEMA_VERSION,
				entries.map {
					listOf(
						it.product.identity.value,
						it.product.contentChecksum.value,
						it.countDomainGraph.identity.value,
						it.countDomainGraph.contentChecksum.value,
					)
				},
			),
		)
}
