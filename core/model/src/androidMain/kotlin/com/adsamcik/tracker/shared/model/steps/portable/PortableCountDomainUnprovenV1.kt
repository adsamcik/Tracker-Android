package com.adsamcik.tracker.shared.model.steps.portable

/**
 * Makes legacy v1 absence explicit. It creates no receipt and therefore cannot prove compatibility.
 */
fun PortableStepsEntryV1.withExplicitUnprovenCountDomain(): PortableStepsEntryV2 {
	val owners = mutableListOf<PortableCountDomainOwnerRevisionV2>()
	val markers = mutableListOf<PortableCountDomainCompletenessMarkerV2>()
	val roots = mutableListOf<PortableCountDomainRootV2>()
	runs.forEach { run ->
		val container = PortableCountDomainOpaqueIdentity(run.identity.value)
		run.facts.forEach { fact ->
			val ownerIdentity = PortableCountDomainIntegrity.unprovenOwnerIdentity(
				PortableCountDomainOwnerKind.SESSION_FACT,
				fact.identity.value,
			)
			val effect = PortableCountDomainIntegrity.unprovenEffectChecksum(
				PortableCountDomainOwnerKind.SESSION_FACT,
				fact.identity.value,
				fact.contentChecksum.value,
			)
			owners += PortableCountDomainOwnerRevisionV2(
				ownerKind = PortableCountDomainOwnerKind.SESSION_FACT,
				scopeIdentity = PortableCountDomainIntegrity.unprovenScopeIdentity(run.identity.value),
				ownerIdentity = ownerIdentity,
				ownerRevision = LEGACY_UNPROVEN_REVISION,
				operation = PortableCountDomainOperation.UNPROVEN,
				receiptIdentity = null,
				ownerEffectChecksum = effect,
				linkedAtMs = LEGACY_UNPROVEN_LINK_TIME_MS,
			)
			roots += PortableCountDomainRootV2(
				containerIdentity = container,
				productIdentity = PortableCountDomainOpaqueIdentity(fact.identity.value),
				ownerKind = PortableCountDomainOwnerKind.SESSION_FACT,
				ownerIdentity = ownerIdentity,
				ownerRevision = LEGACY_UNPROVEN_REVISION,
			)
		}
		val completenessOwner = PortableCountDomainIntegrity.unprovenOwnerIdentity(
			PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
			run.identity.value,
		)
		val completenessEffect = PortableCountDomainIntegrity.unprovenEffectChecksum(
			PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
			run.identity.value,
			contentChecksum.value,
		)
		owners += PortableCountDomainOwnerRevisionV2(
			ownerKind = PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
			scopeIdentity = PortableCountDomainIntegrity.unprovenScopeIdentity(run.identity.value),
			ownerIdentity = completenessOwner,
			ownerRevision = LEGACY_UNPROVEN_REVISION,
			operation = PortableCountDomainOperation.UNPROVEN,
			receiptIdentity = null,
			ownerEffectChecksum = completenessEffect,
			linkedAtMs = LEGACY_UNPROVEN_LINK_TIME_MS,
		)
		val timeline = PortableCountDomainIntegrity.unprovenEffectChecksum(
			PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
			run.identity.value,
			"legacy-v1-no-registration-timeline",
		)
		markers += PortableCountDomainCompletenessMarkerV2.create(
			ownerIdentity = completenessOwner,
			ownerRevision = LEGACY_UNPROVEN_REVISION,
			terminalState = PortableCountDomainCompletenessState.UNPROVEN,
			lastAdmissionOrdinal = null,
			lastSourceSequence = null,
			providerFlushOutcome = LEGACY_UNPROVEN_OUTCOME,
			registrationRemovalOutcome = LEGACY_UNPROVEN_OUTCOME,
			registrationTimelineChecksum = timeline,
		)
		roots += PortableCountDomainRootV2(
			containerIdentity = container,
			productIdentity = container,
			ownerKind = PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
			ownerIdentity = completenessOwner,
			ownerRevision = LEGACY_UNPROVEN_REVISION,
		)
	}
	return PortableStepsEntryV2(
		product = this,
		countDomainGraph = PortableCountDomainGraphV2.create(
			receipts = emptyList(),
			ownerRevisions = owners,
			completenessMarkers = markers,
			roots = roots,
		),
	)
}

/** Legacy Ambient Steps facts remain explicitly unproven after v1 admission and v2 re-export. */
fun PortableAmbientStepsDayV1.withExplicitUnprovenCountDomain(
	ownerRevision: Long = LEGACY_UNPROVEN_REVISION,
): PortableAmbientStepsDayV2 {
	require(ownerRevision > 0L)
	val container = PortableCountDomainOpaqueIdentity(identity.value)
	val owners = facts.map { fact ->
		PortableCountDomainOwnerRevisionV2(
			ownerKind = PortableCountDomainOwnerKind.AMBIENT_FACT,
			scopeIdentity = PortableCountDomainIntegrity.unprovenScopeIdentity(identity.value),
			ownerIdentity = PortableCountDomainIntegrity.unprovenOwnerIdentity(
				PortableCountDomainOwnerKind.AMBIENT_FACT,
				fact.identity.value,
			),
			ownerRevision = ownerRevision,
			operation = PortableCountDomainOperation.UNPROVEN,
			receiptIdentity = null,
			ownerEffectChecksum = PortableCountDomainIntegrity.unprovenEffectChecksum(
				PortableCountDomainOwnerKind.AMBIENT_FACT,
				fact.identity.value,
				fact.contentChecksum.value,
			),
			linkedAtMs = LEGACY_UNPROVEN_LINK_TIME_MS,
		)
	}
	val ownersByProduct = facts.zip(owners).associate { (fact, owner) ->
		fact.identity.value to owner
	}
	return PortableAmbientStepsDayV2(
		product = this,
		countDomainGraph = PortableCountDomainGraphV2.create(
			receipts = emptyList(),
			ownerRevisions = owners,
			completenessMarkers = emptyList(),
			roots = facts.map { fact ->
				val owner = ownersByProduct.getValue(fact.identity.value)
				PortableCountDomainRootV2(
					containerIdentity = container,
					productIdentity = PortableCountDomainOpaqueIdentity(fact.identity.value),
					ownerKind = PortableCountDomainOwnerKind.AMBIENT_FACT,
					ownerIdentity = owner.ownerIdentity,
					ownerRevision = owner.ownerRevision,
				)
			},
		),
	)
}

private const val LEGACY_UNPROVEN_REVISION = 1L
private const val LEGACY_UNPROVEN_LINK_TIME_MS = 0L
private const val LEGACY_UNPROVEN_OUTCOME = "LEGACY_V1_UNPROVEN"
