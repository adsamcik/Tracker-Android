package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity

internal fun authenticateCapturedCellAggregateDependencies(
	lineages: List<CellCapturedLineage>,
	lineagesById: Map<String, CellCapturedLineage>,
	reuseAuthorityById: Map<String, CellAggregateReuseAuthority>,
	aggregateById: Map<String, CellHistoricalIdentityFreeAggregate>,
) {
	for (lineage in lineages) {
		val ownerId = lineage.aggregateOwnerLogicalFactId ?: continue
		val ownerRevision = lineage.aggregateOwnerSemanticRevision
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val owner = lineagesById[ownerId]
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		authenticateCapturedCellAggregateReuse(lineage, owner, ownerRevision, reuseAuthorityById, aggregateById)
	}
}

private fun CellCapturedFactRevisionEntity.hasFiniteAggregateReuseAuthority(): Boolean =
	providerAcceptanceEndNanos != Long.MAX_VALUE &&
		authorizationEffectEndNanos != Long.MAX_VALUE &&
		consentEffectEndNanos != Long.MAX_VALUE &&
		sessionRunEffectEndNanos != Long.MAX_VALUE &&
		deletionEffectEndNanos != Long.MAX_VALUE

// Keep this fixed aggregate tuple together so every technology and quality count stays visible.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun CellCapturedFactRevisionEntity.matchesIdentityFreeAggregate(
	aggregate: CellHistoricalIdentityFreeAggregate,
): Boolean = observationCount == aggregate.observationCount &&
	registeredObservationCount == aggregate.registeredObservationCount &&
	gsmCount == aggregate.gsmCount &&
	cdmaCount == aggregate.cdmaCount &&
	wcdmaCount == aggregate.wcdmaCount &&
	tdscdmaCount == aggregate.tdscdmaCount &&
	lteCount == aggregate.lteCount &&
	nrCount == aggregate.nrCount &&
	qualityUnknownCount == aggregate.qualityUnknownCount &&
	qualityNoneOrUnknownCount == aggregate.qualityNoneOrUnknownCount &&
	qualityPoorCount == aggregate.qualityPoorCount &&
	qualityModerateCount == aggregate.qualityModerateCount &&
	qualityGoodCount == aggregate.qualityGoodCount &&
	qualityGreatCount == aggregate.qualityGreatCount &&
	weakObservationCount == aggregate.weakObservationCount &&
	knownQualityObservationCount == aggregate.knownQualityObservationCount &&
	allKnownQualityIsWeak == aggregate.allKnownQualityIsWeak

private fun authenticateCapturedCellAggregateReuse(
	lineage: CellCapturedLineage, owner: CellCapturedLineage, ownerRevision: Long,
	reuseAuthorityById: Map<String, CellAggregateReuseAuthority>,
	aggregateById: Map<String, CellHistoricalIdentityFreeAggregate>,
) {
	val referenced = owner.revisions.singleOrNull { revision ->
		revision.semanticRevision == ownerRevision
	}
	val dependent = lineage.revisions.last()
	val ownerAuthority = reuseAuthorityById[owner.logicalFactId]
	val dependentAuthority = reuseAuthorityById[lineage.logicalFactId]
	val dependentAggregate = aggregateById[lineage.logicalFactId]
	val exactOwner = requireCurrentCapturedCellAggregateOwner(owner, referenced, dependent)
	requireCompatibleCapturedCellAggregateReuse(ownerAuthority, dependentAuthority, exactOwner, dependentAggregate)
}

private fun requireCurrentCapturedCellAggregateOwner(
	owner: CellCapturedLineage, referenced: CellCapturedFactRevisionEntity?, dependent: CellCapturedFactRevisionEntity,
): CellCapturedFactRevisionEntity {
	if (referenced == null || owner.revisions.last() != referenced) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (owner.revisions.any { revision ->
			revision.factKind != CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
		} || dependent.factKind != CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return referenced
}

private fun requireCompatibleCapturedCellAggregateReuse(
	ownerAuthority: CellAggregateReuseAuthority?, dependentAuthority: CellAggregateReuseAuthority?,
	referenced: CellCapturedFactRevisionEntity, dependentAggregate: CellHistoricalIdentityFreeAggregate?,
) {
	if (ownerAuthority == null || dependentAuthority == null ||
		!dependentAuthority.canReuseAggregateFrom(ownerAuthority)
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!referenced.hasFiniteAggregateReuseAuthority() || dependentAggregate == null ||
		!referenced.matchesIdentityFreeAggregate(dependentAggregate)
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}
