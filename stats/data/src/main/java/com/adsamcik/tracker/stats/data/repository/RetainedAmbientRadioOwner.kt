package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity

/** Legacy unchanged-result indirection may borrow an earlier independently qualified aggregate. */
internal fun retainedWifiOwnerCompatible(fact: AmbientWifiFactRevisionEntity, owner: AmbientWifiFactRevisionEntity): Boolean =
    owner.factKind == AmbientWifiFactRevisionEntity.FACT_KIND_AGGREGATE &&
        owner.writerId == fact.writerId && owner.writerVersion == fact.writerVersion &&
        owner.logicalFactId == fact.aggregateOwnerLogicalFactId && owner.semanticRevision == fact.aggregateOwnerSemanticRevision &&
        owner.ambientConsentEpoch == fact.ambientConsentEpoch && owner.collectedDataEpoch == fact.collectedDataEpoch &&
        owner.scopeDeletionGeneration == fact.scopeDeletionGeneration && owner.sourceAdmissionOrdinal < fact.sourceAdmissionOrdinal

internal fun retainedCellOwnerCompatible(fact: AmbientCellFactRevisionEntity, owner: AmbientCellFactRevisionEntity): Boolean =
    owner.factKind == AmbientCellFactRevisionEntity.FACT_KIND_AGGREGATE &&
        owner.writerId == fact.writerId && owner.writerVersion == fact.writerVersion &&
        owner.logicalFactId == fact.aggregateOwnerLogicalFactId && owner.semanticRevision == fact.aggregateOwnerSemanticRevision &&
        owner.ambientConsentEpoch == fact.ambientConsentEpoch && owner.collectedDataEpoch == fact.collectedDataEpoch &&
        owner.scopeDeletionGeneration == fact.scopeDeletionGeneration && owner.sourceAdmissionOrdinal < fact.sourceAdmissionOrdinal
