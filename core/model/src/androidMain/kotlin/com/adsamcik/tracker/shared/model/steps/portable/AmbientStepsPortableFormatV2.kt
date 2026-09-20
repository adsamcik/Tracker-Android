package com.adsamcik.tracker.shared.model.steps.portable

/** Additive authenticated Ambient Steps archive under the existing MIME type and extension. */
object AmbientStepsPortableFormatV2 {
	const val FORMAT: String = AmbientStepsPortableFormatV1.FORMAT
	const val SCHEMA_VERSION: Int = 2
	const val FILE_EXTENSION: String = AmbientStepsPortableFormatV1.FILE_EXTENSION
	const val MIME_TYPE: String = AmbientStepsPortableFormatV1.MIME_TYPE
	const val MAX_FILE_BYTES: Long = AmbientStepsPortableFormatV1.MAX_FILE_BYTES
	const val MAX_DAYS: Int = AmbientStepsPortableFormatV1.MAX_DAYS
}

data class PortableAmbientStepsDayV2(
	val product: PortableAmbientStepsDayV1,
	val countDomainGraph: PortableCountDomainGraphV2,
) {
	init {
		val roots = countDomainGraph.roots
		require(roots.all { it.ownerKind == PortableCountDomainOwnerKind.AMBIENT_FACT })
		require(roots.size == product.facts.size)
		require(roots.map { it.productIdentity.value }.toSet() ==
			product.facts.mapTo(linkedSetOf()) { it.identity.value })
		val dayIdentity = product.identity.value
		require(roots.all { it.containerIdentity.value == dayIdentity })
		require(roots.none { root ->
			countDomainGraph.ownerRevisions.single {
				it.ownerKind == root.ownerKind &&
					it.ownerIdentity == root.ownerIdentity &&
					it.ownerRevision == root.ownerRevision
			}.operation == PortableCountDomainOperation.RETRACT
		})
	}
}

data class PortableAmbientStepsArchiveV2(
	val format: String = AmbientStepsPortableFormatV2.FORMAT,
	val schemaVersion: Int = AmbientStepsPortableFormatV2.SCHEMA_VERSION,
	val contentChecksum: AmbientStepsPortableDigest,
	val days: List<PortableAmbientStepsDayV2>,
) {
	init {
		require(format == AmbientStepsPortableFormatV2.FORMAT)
		require(schemaVersion == AmbientStepsPortableFormatV2.SCHEMA_VERSION)
		require(days.isNotEmpty())
		require(days.size <= AmbientStepsPortableFormatV2.MAX_DAYS)
		require(days == days.sortedWith(PORTABLE_AMBIENT_STEPS_DAY_V2_ORDER))
		require(days.map { it.product.identity }.distinct().size == days.size)
		val facts = days.flatMap { it.product.facts }
		val gaps = days.flatMap { it.product.gaps }
		require(facts.map(PortableAmbientStepsFactV1::identity).distinct().size == facts.size)
		require(gaps.map(PortableAmbientStepsGapV1::identity).distinct().size == gaps.size)
		val ownerLineages = days.flatMap { it.countDomainGraph.roots }.map {
			it.ownerKind to it.ownerIdentity
		}
		require(ownerLineages.distinct().size == ownerLineages.size)
		require(contentChecksum == AmbientStepsPortableV2Integrity.archiveChecksum(days))
	}

	companion object {
		fun create(days: List<PortableAmbientStepsDayV2>): PortableAmbientStepsArchiveV2 {
			val ordered = days.sortedWith(PORTABLE_AMBIENT_STEPS_DAY_V2_ORDER)
			return PortableAmbientStepsArchiveV2(
				contentChecksum = AmbientStepsPortableV2Integrity.archiveChecksum(ordered),
				days = ordered,
			)
		}
	}
}

val PortableAmbientStepsArchiveV2.identity: AmbientStepsPortableOpaqueIdentity
	get() = AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.ARCHIVE,
		contentChecksum.value,
	)

val PORTABLE_AMBIENT_STEPS_DAY_V2_ORDER: Comparator<PortableAmbientStepsDayV2> =
	compareBy<PortableAmbientStepsDayV2> { it.product.structuralDayStartTimeMs }
		.thenBy { it.product.storedZoneId }
		.thenBy { it.product.identity.value }

object AmbientStepsPortableV2Integrity {
	fun archiveChecksum(days: List<PortableAmbientStepsDayV2>): AmbientStepsPortableDigest =
		AmbientStepsPortableIntegrity.digest(
			"tracker-portable-ambient-steps-archive-v2",
			listOf(
				AmbientStepsPortableFormatV2.FORMAT,
				AmbientStepsPortableFormatV2.SCHEMA_VERSION,
				days.map {
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
