package com.adsamcik.tracker.shared.model.steps.portable

import java.security.MessageDigest

/** Shared bounds for authenticated portable Steps count-domain evidence. */
object PortableCountDomainFormatV2 {
	const val MAX_RECEIPTS: Int = 131_072
	const val MAX_OWNER_REVISIONS: Int = 131_072
	const val MAX_OWNER_LINEAGE_REVISIONS: Int = 64
	const val MAX_COMPLETENESS_MARKERS: Int = 4_096
	const val MAX_ROOTS: Int = 131_072
}

@JvmInline
value class PortableCountDomainOpaqueIdentity(val value: String) {
	init {
		require(PORTABLE_COUNT_DOMAIN_OPAQUE.matches(value))
	}
}

@JvmInline
value class PortableCountDomainDigest(val value: String) {
	init {
		require(PORTABLE_COUNT_DOMAIN_DIGEST.matches(value))
	}
}

enum class PortableCountDomainOwnerKind {
	SESSION_FACT,
	SESSION_COMPLETENESS,
	AMBIENT_FACT,
}

enum class PortableCountDomainOperation {
	BIND,
	UNPROVEN,
	RETRACT,
}

enum class PortableCountDomainCoverage {
	BASELINE,
	COVERED,
	RESET_GAP,
	PARTIAL,
	COMPLETE_RUN,
	AMBIENT_AGGREGATE,
}

enum class PortableCountDomainCompletenessState {
	COMPLETE,
	UNPROVEN,
}

/**
 * Source-authenticated immutable receipt. All opaque values are retained verbatim across imports.
 */
@Suppress("LongParameterList")
data class PortableCountDomainReceiptV2(
	val identity: PortableCountDomainOpaqueIdentity,
	val domainIdentity: PortableCountDomainOpaqueIdentity,
	val ownerKind: PortableCountDomainOwnerKind,
	val scopeIdentity: PortableCountDomainOpaqueIdentity,
	val ownerIdentity: PortableCountDomainOpaqueIdentity,
	val ownerRevision: Long,
	val registrationGeneration: Long,
	val collectedDataEpoch: Long,
	val authorityRevision: Long,
	val authorityFingerprint: PortableCountDomainDigest,
	val coverage: PortableCountDomainCoverage,
	val coverageVersion: Int,
	val countDomainVersion: Int,
	val effectChecksum: PortableCountDomainDigest,
	val completenessEvidenceChecksum: PortableCountDomainDigest?,
) {
	init {
		require(ownerRevision > 0L)
		require(registrationGeneration > 0L)
		require(collectedDataEpoch >= 0L)
		require(authorityRevision > 0L)
		require(coverageVersion > 0)
		require(countDomainVersion > 0)
		require(coverage in ownerKind.allowedCoverage)
		require(
			(ownerKind == PortableCountDomainOwnerKind.SESSION_COMPLETENESS) ==
				(completenessEvidenceChecksum != null),
		)
		require(identity == PortableCountDomainIntegrity.receiptIdentity(this))
	}

	companion object {
		@Suppress("LongParameterList")
		fun create(
			domainIdentity: PortableCountDomainOpaqueIdentity,
			ownerKind: PortableCountDomainOwnerKind,
			scopeIdentity: PortableCountDomainOpaqueIdentity,
			ownerIdentity: PortableCountDomainOpaqueIdentity,
			ownerRevision: Long,
			registrationGeneration: Long,
			collectedDataEpoch: Long,
			authorityRevision: Long,
			authorityFingerprint: PortableCountDomainDigest,
			coverage: PortableCountDomainCoverage,
			coverageVersion: Int,
			countDomainVersion: Int,
			effectChecksum: PortableCountDomainDigest,
			completenessEvidenceChecksum: PortableCountDomainDigest?,
		): PortableCountDomainReceiptV2 {
			val provisional = PortableCountDomainReceiptV2(
				identity = PortableCountDomainIntegrity.receiptIdentity(
					domainIdentity = domainIdentity,
					ownerKind = ownerKind,
					scopeIdentity = scopeIdentity,
					ownerIdentity = ownerIdentity,
					ownerRevision = ownerRevision,
					registrationGeneration = registrationGeneration,
					collectedDataEpoch = collectedDataEpoch,
					authorityRevision = authorityRevision,
					authorityFingerprint = authorityFingerprint,
					coverage = coverage,
					coverageVersion = coverageVersion,
					countDomainVersion = countDomainVersion,
					effectChecksum = effectChecksum,
					completenessEvidenceChecksum = completenessEvidenceChecksum,
				),
				domainIdentity = domainIdentity,
				ownerKind = ownerKind,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				registrationGeneration = registrationGeneration,
				collectedDataEpoch = collectedDataEpoch,
				authorityRevision = authorityRevision,
				authorityFingerprint = authorityFingerprint,
				coverage = coverage,
				coverageVersion = coverageVersion,
				countDomainVersion = countDomainVersion,
				effectChecksum = effectChecksum,
				completenessEvidenceChecksum = completenessEvidenceChecksum,
			)
			return provisional
		}
	}
}

/** One immutable source owner revision. BIND has exactly one receipt; other operations have none. */
data class PortableCountDomainOwnerRevisionV2(
	val ownerKind: PortableCountDomainOwnerKind,
	val scopeIdentity: PortableCountDomainOpaqueIdentity,
	val ownerIdentity: PortableCountDomainOpaqueIdentity,
	val ownerRevision: Long,
	val operation: PortableCountDomainOperation,
	val receiptIdentity: PortableCountDomainOpaqueIdentity?,
	val ownerEffectChecksum: PortableCountDomainDigest,
	val linkedAtMs: Long,
) {
	init {
		require(ownerRevision > 0L)
		require(linkedAtMs >= 0L)
		require((operation == PortableCountDomainOperation.BIND) == (receiptIdentity != null))
		require(
			operation != PortableCountDomainOperation.RETRACT ||
				ownerKind != PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
		)
	}
}

/** Exact source retirement evidence for one session-completeness revision. */
@Suppress("LongParameterList")
data class PortableCountDomainCompletenessMarkerV2(
	val ownerIdentity: PortableCountDomainOpaqueIdentity,
	val ownerRevision: Long,
	val terminalState: PortableCountDomainCompletenessState,
	val lastAdmissionOrdinal: Long?,
	val lastSourceSequence: Long?,
	val providerFlushOutcome: String,
	val registrationRemovalOutcome: String,
	val registrationTimelineChecksum: PortableCountDomainDigest,
	val evidenceChecksum: PortableCountDomainDigest,
) {
	init {
		require(ownerRevision > 0L)
		require((lastAdmissionOrdinal == null) == (lastSourceSequence == null))
		require(lastAdmissionOrdinal == null || lastAdmissionOrdinal > 0L)
		require(lastSourceSequence == null || lastSourceSequence >= 0L)
		require(providerFlushOutcome.isNotBlank())
		require(registrationRemovalOutcome.isNotBlank())
		if (terminalState == PortableCountDomainCompletenessState.COMPLETE) {
			require(lastAdmissionOrdinal != null && lastSourceSequence != null)
			require(providerFlushOutcome in COMPLETE_FLUSH_OUTCOMES)
			require(registrationRemovalOutcome in COMPLETE_REMOVAL_OUTCOMES)
		}
		require(
			evidenceChecksum == PortableCountDomainIntegrity.completenessMarkerChecksum(this),
		)
	}

	companion object {
		private val COMPLETE_FLUSH_OUTCOMES =
			setOf("COMPLETE", "NOT_SUPPORTED", "NOT_REQUESTED")
		private val COMPLETE_REMOVAL_OUTCOMES = setOf("REMOVED", "NOT_REGISTERED")

		@Suppress("LongParameterList")
		fun create(
			ownerIdentity: PortableCountDomainOpaqueIdentity,
			ownerRevision: Long,
			terminalState: PortableCountDomainCompletenessState,
			lastAdmissionOrdinal: Long?,
			lastSourceSequence: Long?,
			providerFlushOutcome: String,
			registrationRemovalOutcome: String,
			registrationTimelineChecksum: PortableCountDomainDigest,
		): PortableCountDomainCompletenessMarkerV2 {
			val checksum = PortableCountDomainIntegrity.completenessMarkerChecksum(
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				terminalState = terminalState,
				lastAdmissionOrdinal = lastAdmissionOrdinal,
				lastSourceSequence = lastSourceSequence,
				providerFlushOutcome = providerFlushOutcome,
				registrationRemovalOutcome = registrationRemovalOutcome,
				registrationTimelineChecksum = registrationTimelineChecksum,
			)
			return PortableCountDomainCompletenessMarkerV2(
				ownerIdentity,
				ownerRevision,
				terminalState,
				lastAdmissionOrdinal,
				lastSourceSequence,
				providerFlushOutcome,
				registrationRemovalOutcome,
				registrationTimelineChecksum,
				checksum,
			)
		}
	}
}

/**
 * Product-to-owner root. [containerIdentity] is the exact run or structural-day identity.
 */
data class PortableCountDomainRootV2(
	val containerIdentity: PortableCountDomainOpaqueIdentity,
	val productIdentity: PortableCountDomainOpaqueIdentity,
	val ownerKind: PortableCountDomainOwnerKind,
	val ownerIdentity: PortableCountDomainOpaqueIdentity,
	val ownerRevision: Long,
) {
	init {
		require(ownerRevision > 0L)
	}
}

/** Complete canonical graph carried by one portable entry or structural day. */
data class PortableCountDomainGraphV2(
	val identity: PortableCountDomainOpaqueIdentity,
	val contentChecksum: PortableCountDomainOpaqueIdentity,
	val receipts: List<PortableCountDomainReceiptV2>,
	val ownerRevisions: List<PortableCountDomainOwnerRevisionV2>,
	val completenessMarkers: List<PortableCountDomainCompletenessMarkerV2>,
	val roots: List<PortableCountDomainRootV2>,
) {
	init {
		require(receipts.size <= PortableCountDomainFormatV2.MAX_RECEIPTS)
		require(ownerRevisions.isNotEmpty())
		require(ownerRevisions.size <= PortableCountDomainFormatV2.MAX_OWNER_REVISIONS)
		require(completenessMarkers.size <= PortableCountDomainFormatV2.MAX_COMPLETENESS_MARKERS)
		require(roots.isNotEmpty())
		require(roots.size <= PortableCountDomainFormatV2.MAX_ROOTS)
		require(receipts == receipts.sortedWith(PORTABLE_COUNT_DOMAIN_RECEIPT_ORDER))
		require(ownerRevisions == ownerRevisions.sortedWith(PORTABLE_COUNT_DOMAIN_OWNER_ORDER))
		require(
			completenessMarkers ==
				completenessMarkers.sortedWith(PORTABLE_COUNT_DOMAIN_MARKER_ORDER),
		)
		require(roots == roots.sortedWith(PORTABLE_COUNT_DOMAIN_ROOT_ORDER))
		val rootKinds = roots.mapTo(linkedSetOf(), PortableCountDomainRootV2::ownerKind)
		require(
			rootKinds == setOf(PortableCountDomainOwnerKind.AMBIENT_FACT) ||
				PortableCountDomainOwnerKind.AMBIENT_FACT !in rootKinds,
		)
		require(receipts.map { it.identity }.distinct().size == receipts.size)
		require(
			ownerRevisions.map { Triple(it.ownerKind, it.ownerIdentity, it.ownerRevision) }
				.distinct().size == ownerRevisions.size,
		)
		require(
			completenessMarkers.map { it.ownerIdentity to it.ownerRevision }.distinct().size ==
				completenessMarkers.size,
		)
		require(
			roots.map {
				listOf(
					it.containerIdentity.value,
					it.productIdentity.value,
					it.ownerKind.name,
					it.ownerIdentity.value,
				)
			}.distinct().size == roots.size,
		)
		validateGraph()
		require(contentChecksum == PortableCountDomainIntegrity.graphChecksum(
			receipts,
			ownerRevisions,
			completenessMarkers,
			roots,
		))
		require(identity == PortableCountDomainIntegrity.graphIdentity(contentChecksum))
	}

	companion object {
		fun create(
			receipts: List<PortableCountDomainReceiptV2>,
			ownerRevisions: List<PortableCountDomainOwnerRevisionV2>,
			completenessMarkers: List<PortableCountDomainCompletenessMarkerV2>,
			roots: List<PortableCountDomainRootV2>,
		): PortableCountDomainGraphV2 {
			val orderedReceipts = receipts.sortedWith(PORTABLE_COUNT_DOMAIN_RECEIPT_ORDER)
			val orderedOwners = ownerRevisions.sortedWith(PORTABLE_COUNT_DOMAIN_OWNER_ORDER)
			val orderedMarkers = completenessMarkers.sortedWith(PORTABLE_COUNT_DOMAIN_MARKER_ORDER)
			val orderedRoots = roots.sortedWith(PORTABLE_COUNT_DOMAIN_ROOT_ORDER)
			val checksum = PortableCountDomainIntegrity.graphChecksum(
				orderedReceipts,
				orderedOwners,
				orderedMarkers,
				orderedRoots,
			)
			return PortableCountDomainGraphV2(
				PortableCountDomainIntegrity.graphIdentity(checksum),
				checksum,
				orderedReceipts,
				orderedOwners,
				orderedMarkers,
				orderedRoots,
			)
		}
	}

	private fun validateGraph() {
		val receiptsByIdentity = receipts.associateBy { it.identity }
		val ownersByLineage = ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }
		require(ownersByLineage.size == roots.map { it.ownerKind to it.ownerIdentity }.distinct().size)
		ownersByLineage.values.forEach { lineage ->
			require(lineage.size <= PortableCountDomainFormatV2.MAX_OWNER_LINEAGE_REVISIONS)
			require(lineage.map { it.scopeIdentity }.distinct().size == 1)
			require(lineage.zipWithNext().all { (left, right) ->
				right.ownerRevision == Math.addExact(left.ownerRevision, 1L)
			})
			require(lineage.dropLast(1).none {
				it.operation == PortableCountDomainOperation.RETRACT
			})
			require(lineage.dropLast(1).none {
				it.ownerKind == PortableCountDomainOwnerKind.SESSION_COMPLETENESS &&
					it.operation == PortableCountDomainOperation.BIND
			})
			require(lineage.dropLast(1).none {
				it.ownerKind != PortableCountDomainOwnerKind.AMBIENT_FACT &&
					it.operation == PortableCountDomainOperation.UNPROVEN
			})
		}
		val referencedReceipts = ownerRevisions.mapNotNull { owner ->
			val receiptIdentity = owner.receiptIdentity ?: return@mapNotNull null
			val receipt = receiptsByIdentity[receiptIdentity] ?: error("Missing portable receipt")
			require(owner.operation == PortableCountDomainOperation.BIND)
			require(receipt.ownerKind == owner.ownerKind)
			require(receipt.scopeIdentity == owner.scopeIdentity)
			require(receipt.ownerIdentity == owner.ownerIdentity)
			require(receipt.ownerRevision == owner.ownerRevision)
			require(receipt.effectChecksum == owner.ownerEffectChecksum)
			receiptIdentity
		}
		require(referencedReceipts.distinct().size == referencedReceipts.size)
		require(referencedReceipts.toSet() == receiptsByIdentity.keys)

		val markersByOwner = completenessMarkers.associateBy {
			it.ownerIdentity to it.ownerRevision
		}
		ownerRevisions.forEach { owner ->
			val marker = markersByOwner[owner.ownerIdentity to owner.ownerRevision]
			if (owner.ownerKind == PortableCountDomainOwnerKind.SESSION_COMPLETENESS) {
				require(marker != null)
				val receipt = owner.receiptIdentity?.let(receiptsByIdentity::get)
				when (owner.operation) {
					PortableCountDomainOperation.BIND -> {
						require(marker.terminalState == PortableCountDomainCompletenessState.COMPLETE)
						require(receipt?.completenessEvidenceChecksum == marker.evidenceChecksum)
					}
					PortableCountDomainOperation.UNPROVEN -> {
						require(marker.terminalState == PortableCountDomainCompletenessState.UNPROVEN)
						require(receipt == null)
					}
					PortableCountDomainOperation.RETRACT -> error(
						"Session completeness cannot be retracted",
					)
				}
			} else {
				require(marker == null)
			}
		}
		require(
			markersByOwner.keys == ownerRevisions
				.filter { it.ownerKind == PortableCountDomainOwnerKind.SESSION_COMPLETENESS }
				.mapTo(linkedSetOf()) { it.ownerIdentity to it.ownerRevision },
		)

		val latestByLineage = ownersByLineage.mapValues { (_, lineage) -> lineage.last() }
		roots.forEach { root ->
			val latest = latestByLineage[root.ownerKind to root.ownerIdentity]
				?: error("Portable root has no owner lineage")
			require(root.ownerRevision == latest.ownerRevision)
		}
	}
}

val PORTABLE_COUNT_DOMAIN_RECEIPT_ORDER: Comparator<PortableCountDomainReceiptV2> =
	compareBy<PortableCountDomainReceiptV2> { it.ownerKind.ordinal }
		.thenBy { it.ownerIdentity.value }
		.thenBy(PortableCountDomainReceiptV2::ownerRevision)

val PORTABLE_COUNT_DOMAIN_OWNER_ORDER: Comparator<PortableCountDomainOwnerRevisionV2> =
	compareBy<PortableCountDomainOwnerRevisionV2> { it.ownerKind.ordinal }
		.thenBy { it.ownerIdentity.value }
		.thenBy(PortableCountDomainOwnerRevisionV2::ownerRevision)

val PORTABLE_COUNT_DOMAIN_MARKER_ORDER: Comparator<PortableCountDomainCompletenessMarkerV2> =
	compareBy<PortableCountDomainCompletenessMarkerV2> { it.ownerIdentity.value }
		.thenBy(PortableCountDomainCompletenessMarkerV2::ownerRevision)

val PORTABLE_COUNT_DOMAIN_ROOT_ORDER: Comparator<PortableCountDomainRootV2> =
	compareBy<PortableCountDomainRootV2> { it.containerIdentity.value }
		.thenBy { it.productIdentity.value }
		.thenBy { it.ownerKind.ordinal }
		.thenBy { it.ownerIdentity.value }

@Suppress("TooManyFunctions")
object PortableCountDomainIntegrity {
	fun receiptIdentity(receipt: PortableCountDomainReceiptV2): PortableCountDomainOpaqueIdentity =
		receiptIdentity(
			receipt.domainIdentity,
			receipt.ownerKind,
			receipt.scopeIdentity,
			receipt.ownerIdentity,
			receipt.ownerRevision,
			receipt.registrationGeneration,
			receipt.collectedDataEpoch,
			receipt.authorityRevision,
			receipt.authorityFingerprint,
			receipt.coverage,
			receipt.coverageVersion,
			receipt.countDomainVersion,
			receipt.effectChecksum,
			receipt.completenessEvidenceChecksum,
		)

	@Suppress("LongParameterList")
	fun receiptIdentity(
		domainIdentity: PortableCountDomainOpaqueIdentity,
		ownerKind: PortableCountDomainOwnerKind,
		scopeIdentity: PortableCountDomainOpaqueIdentity,
		ownerIdentity: PortableCountDomainOpaqueIdentity,
		ownerRevision: Long,
		registrationGeneration: Long,
		collectedDataEpoch: Long,
		authorityRevision: Long,
		authorityFingerprint: PortableCountDomainDigest,
		coverage: PortableCountDomainCoverage,
		coverageVersion: Int,
		countDomainVersion: Int,
		effectChecksum: PortableCountDomainDigest,
		completenessEvidenceChecksum: PortableCountDomainDigest?,
	): PortableCountDomainOpaqueIdentity = nativeOpaqueDigest(
		"steps-count-domain-receipt-v2",
		domainIdentity.value,
		ownerKind.name,
		scopeIdentity.value,
		ownerIdentity.value,
		ownerRevision,
		registrationGeneration,
		collectedDataEpoch,
		authorityRevision,
		authorityFingerprint.value,
		coverage.name,
		coverageVersion,
		countDomainVersion,
		effectChecksum.value,
		completenessEvidenceChecksum?.value,
	)

	fun completenessMarkerChecksum(
		marker: PortableCountDomainCompletenessMarkerV2,
	): PortableCountDomainDigest = completenessMarkerChecksum(
		marker.ownerIdentity,
		marker.ownerRevision,
		marker.terminalState,
		marker.lastAdmissionOrdinal,
		marker.lastSourceSequence,
		marker.providerFlushOutcome,
		marker.registrationRemovalOutcome,
		marker.registrationTimelineChecksum,
	)

	@Suppress("LongParameterList")
	fun completenessMarkerChecksum(
		ownerIdentity: PortableCountDomainOpaqueIdentity,
		ownerRevision: Long,
		terminalState: PortableCountDomainCompletenessState,
		lastAdmissionOrdinal: Long?,
		lastSourceSequence: Long?,
		providerFlushOutcome: String,
		registrationRemovalOutcome: String,
		registrationTimelineChecksum: PortableCountDomainDigest,
	): PortableCountDomainDigest = nativeRawDigest(
		"steps-count-completeness-marker-v1",
		PortableCountDomainOwnerKind.SESSION_COMPLETENESS.name,
		ownerIdentity.value,
		ownerRevision,
		terminalState.name,
		lastAdmissionOrdinal,
		lastSourceSequence,
		providerFlushOutcome,
		registrationRemovalOutcome,
		registrationTimelineChecksum.value,
	)

	fun graphChecksum(
		receipts: List<PortableCountDomainReceiptV2>,
		owners: List<PortableCountDomainOwnerRevisionV2>,
		markers: List<PortableCountDomainCompletenessMarkerV2>,
		roots: List<PortableCountDomainRootV2>,
	): PortableCountDomainOpaqueIdentity = opaqueDigest(
		"tracker-portable-steps-count-domain-graph-v2",
		listOf(
			receipts.map { receipt ->
				listOf(
					receipt.identity.value,
					receipt.domainIdentity.value,
					receipt.ownerKind.name,
					receipt.scopeIdentity.value,
					receipt.ownerIdentity.value,
					receipt.ownerRevision,
					receipt.registrationGeneration,
					receipt.collectedDataEpoch,
					receipt.authorityRevision,
					receipt.authorityFingerprint.value,
					receipt.coverage.name,
					receipt.coverageVersion,
					receipt.countDomainVersion,
					receipt.effectChecksum.value,
					receipt.completenessEvidenceChecksum?.value,
				)
			},
			owners.map { owner ->
				listOf(
					owner.ownerKind.name,
					owner.scopeIdentity.value,
					owner.ownerIdentity.value,
					owner.ownerRevision,
					owner.operation.name,
					owner.receiptIdentity?.value,
					owner.ownerEffectChecksum.value,
					owner.linkedAtMs,
				)
			},
			markers.map { marker ->
				listOf(
					marker.ownerIdentity.value,
					marker.ownerRevision,
					marker.terminalState.name,
					marker.lastAdmissionOrdinal,
					marker.lastSourceSequence,
					marker.providerFlushOutcome,
					marker.registrationRemovalOutcome,
					marker.registrationTimelineChecksum.value,
					marker.evidenceChecksum.value,
				)
			},
			roots.map { root ->
				listOf(
					root.containerIdentity.value,
					root.productIdentity.value,
					root.ownerKind.name,
					root.ownerIdentity.value,
					root.ownerRevision,
				)
			},
		),
	)

	fun graphIdentity(
		contentChecksum: PortableCountDomainOpaqueIdentity,
	): PortableCountDomainOpaqueIdentity = opaqueDigest(
		"tracker-portable-steps-count-domain-graph-identity-v2",
		contentChecksum.value,
	)

	fun unprovenOwnerIdentity(
		ownerKind: PortableCountDomainOwnerKind,
		productIdentity: String,
	): PortableCountDomainOpaqueIdentity = opaqueDigest(
		"tracker-portable-steps-count-domain-unproven-owner-v2",
		listOf(ownerKind.name, productIdentity),
	)

	fun unprovenScopeIdentity(
		containerIdentity: String,
	): PortableCountDomainOpaqueIdentity = opaqueDigest(
		"tracker-portable-steps-count-domain-unproven-scope-v2",
		containerIdentity,
	)

	fun unprovenEffectChecksum(
		ownerKind: PortableCountDomainOwnerKind,
		productIdentity: String,
		productChecksum: String,
	): PortableCountDomainDigest = rawDigest(
		"tracker-portable-steps-count-domain-unproven-effect-v2",
		listOf(ownerKind.name, productIdentity, productChecksum),
	)

	private fun opaqueDigest(namespace: String, value: Any?): PortableCountDomainOpaqueIdentity =
		PortableCountDomainOpaqueIdentity("sha256:${digest(namespace, value)}")

	private fun rawDigest(namespace: String, value: Any?): PortableCountDomainDigest =
		PortableCountDomainDigest(digest(namespace, value))

	private fun nativeOpaqueDigest(vararg values: Any?): PortableCountDomainOpaqueIdentity =
		PortableCountDomainOpaqueIdentity("sha256:${nativeDigest(*values)}")

	private fun nativeRawDigest(vararg values: Any?): PortableCountDomainDigest =
		PortableCountDomainDigest(nativeDigest(*values))

	private fun nativeDigest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte ->
				(byte.toInt() and 0xff).toString(16).padStart(2, '0')
			}
	}

	private fun digest(namespace: String, value: Any?): String {
		val digest = MessageDigest.getInstance("SHA-256")
		digest.appendCanonical(listOf(namespace, value))
		return digest.digest().joinToString("") { byte ->
			(byte.toInt() and 0xff).toString(16).padStart(2, '0')
		}
	}

	private fun MessageDigest.appendCanonical(value: Any?) {
		when (value) {
			null -> update("N;".toByteArray(Charsets.UTF_8))
			is Boolean -> update(if (value) byteArrayOf('B'.code.toByte(), '1'.code.toByte(), ';'.code.toByte())
			else byteArrayOf('B'.code.toByte(), '0'.code.toByte(), ';'.code.toByte()))
			is Int -> appendCanonical(value.toLong())
			is Long -> update("I$value;".toByteArray(Charsets.UTF_8))
			is String -> {
				val bytes = value.toByteArray(Charsets.UTF_8)
				update("S${bytes.size}:".toByteArray(Charsets.UTF_8))
				update(bytes)
				update(";".toByteArray(Charsets.UTF_8))
			}
			is Collection<*> -> {
				update("L${value.size}[".toByteArray(Charsets.UTF_8))
				value.forEach(::appendCanonical)
				update("];".toByteArray(Charsets.UTF_8))
			}
			else -> error("Unsupported portable count-domain canonical value ${value::class.java.name}")
		}
	}
}

private val PortableCountDomainOwnerKind.allowedCoverage: Set<PortableCountDomainCoverage>
	get() = when (this) {
		PortableCountDomainOwnerKind.SESSION_FACT -> setOf(
			PortableCountDomainCoverage.BASELINE,
			PortableCountDomainCoverage.COVERED,
			PortableCountDomainCoverage.RESET_GAP,
			PortableCountDomainCoverage.PARTIAL,
		)
		PortableCountDomainOwnerKind.SESSION_COMPLETENESS ->
			setOf(PortableCountDomainCoverage.COMPLETE_RUN)
		PortableCountDomainOwnerKind.AMBIENT_FACT ->
			setOf(PortableCountDomainCoverage.AMBIENT_AGGREGATE)
	}

private val PORTABLE_COUNT_DOMAIN_OPAQUE = Regex("sha256:[0-9a-f]{64}")
private val PORTABLE_COUNT_DOMAIN_DIGEST = Regex("[0-9a-f]{64}")
