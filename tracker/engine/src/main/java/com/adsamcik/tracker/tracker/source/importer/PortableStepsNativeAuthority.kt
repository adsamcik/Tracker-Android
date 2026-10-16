package com.adsamcik.tracker.tracker.source.importer

import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PORTABLE_STEPS_ENTRY_ORDER
import com.adsamcik.tracker.stats.api.repository.PortableStepsConflictScope
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import java.util.ConcurrentModificationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class NativeAuthorityCollector(
	private val candidate: PortableStepsEntryV1,
) {
	private val seenIdentities = hashSetOf<String>()
	private val seenDeletionScopes = hashSetOf<String>()
	private var previous: PortableStepsEntryV1? = null
	private var count = 0
	private var resolution: NativeAuthorityResolution = NativeAuthorityResolution.NoCollision

	suspend fun emit(value: PortableStepsEntryV1) {
		currentCoroutineContext().ensureActive()
		val entry = value.snapshotAndValidate()
		if (previous?.let { prior -> PORTABLE_STEPS_ENTRY_ORDER.compare(prior, entry) >= 0 } == true) {
			throw IllegalArgumentException("Portable Steps authority is not in canonical order")
		}
		entry.identityConflictWithinEntry()?.let {
			throw IllegalArgumentException("Portable Steps authority repeats an identity within an entry")
		}
		val existingIdentities = registerEntryIdentities(entry)
		val next = when {
			entry.identity == candidate.identity -> if (entry == candidate) {
				NativeAuthorityResolution.Duplicate
			} else {
				NativeAuthorityResolution.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
			}
			else -> candidate.collisionWith(existingIdentities.keys, entry.runs.map { it.deletionScopeDigest.value })
		}
		resolution = resolution.combine(next)
		previous = entry
		count++
	}

	fun finish(
		result: ExportPortableStepsResult,
		evidence: SourceEvidenceState?,
	): NativeAuthorityPreflight = when (result) {
		is ExportPortableStepsResult.Exported -> if (result.entryCount == count && count > 0) {
			NativeAuthorityPreflight.Stable(evidence, resolution)
		} else {
			NativeAuthorityPreflight.Outcome(attributionUnverifiable())
		}
		ExportPortableStepsResult.NoEntries -> if (count == 0) {
			NativeAuthorityPreflight.Stable(evidence, NativeAuthorityResolution.NoCollision)
		} else {
			NativeAuthorityPreflight.Outcome(attributionUnverifiable())
		}
		is ExportPortableStepsResult.Unverifiable -> NativeAuthorityPreflight.Outcome(
			result.toImportResult(),
		)
		is ExportPortableStepsResult.RetryableFailure -> NativeAuthorityPreflight.Outcome(
			ImportPortableStepsResult.RetryableFailure(result.reason),
		)
	}

	private fun registerEntryIdentities(entry: PortableStepsEntryV1): Map<String, PortableStepsConflictScope> {
		val existingIdentities = entry.identityScopes()
		if (existingIdentities.keys.any { identity -> !seenIdentities.add(identity) } ||
			entry.runs.any { run -> !seenDeletionScopes.add(run.deletionScopeDigest.value) }
		) {
			throw IllegalArgumentException("Portable Steps authority repeats an identity")
		}
		return existingIdentities
	}

	private fun PortableStepsEntryV1.collisionWith(
		existingIdentities: Set<String>,
		existingDeletionScopes: List<String>,
	): NativeAuthorityResolution = when {
		identity.value in existingIdentities ->
			NativeAuthorityResolution.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)
		runs.any { run -> run.identity.value in existingIdentities ||
			run.deletionScopeDigest.value in existingDeletionScopes
		} -> NativeAuthorityResolution.Conflict(PortableStepsConflictScope.PHYSICAL_RUN)
		runs.any { run -> run.facts.any { fact -> fact.identity.value in existingIdentities } } ->
			NativeAuthorityResolution.Conflict(PortableStepsConflictScope.FACT)
		else -> NativeAuthorityResolution.NoCollision
	}
}

internal sealed interface NativeAuthorityPreflight {
	data class Stable(
		val evidenceState: SourceEvidenceState?,
		val resolution: NativeAuthorityResolution,
	) : NativeAuthorityPreflight

	data class Outcome(val result: ImportPortableStepsResult) : NativeAuthorityPreflight
}

internal sealed interface NativeAuthorityResolution {
	data object NoCollision : NativeAuthorityResolution
	data object Duplicate : NativeAuthorityResolution
	data class Conflict(val scope: PortableStepsConflictScope) : NativeAuthorityResolution
}

private fun NativeAuthorityResolution.combine(other: NativeAuthorityResolution): NativeAuthorityResolution {
	if (this is NativeAuthorityResolution.Conflict || other is NativeAuthorityResolution.Conflict) {
		val scopes = listOfNotNull(
			(this as? NativeAuthorityResolution.Conflict)?.scope,
			(other as? NativeAuthorityResolution.Conflict)?.scope,
		)
		val scope = scopes.minBy { it.conflictPriority() }
		return NativeAuthorityResolution.Conflict(scope)
	}
	return if (this == NativeAuthorityResolution.Duplicate || other == NativeAuthorityResolution.Duplicate) {
		NativeAuthorityResolution.Duplicate
	} else {
		NativeAuthorityResolution.NoCollision
	}
}

private fun PortableStepsConflictScope.conflictPriority(): Int = when (this) {
	PortableStepsConflictScope.LOGICAL_ENTRY -> 0
	PortableStepsConflictScope.PHYSICAL_RUN -> 1
	PortableStepsConflictScope.FACT -> 2
}

private fun PortableStepsEntryV1.identityScopes(): Map<String, PortableStepsConflictScope> = buildMap {
	put(identity.value, PortableStepsConflictScope.LOGICAL_ENTRY)
	runs.forEach { run ->
		put(run.identity.value, PortableStepsConflictScope.PHYSICAL_RUN)
		run.facts.forEach { fact -> put(fact.identity.value, PortableStepsConflictScope.FACT) }
	}
}

internal fun PortableStepsEntryV1.identityConflictWithinEntry(): PortableStepsConflictScope? {
	val seen = hashSetOf<String>()
	if (!seen.add(identity.value)) {
		return PortableStepsConflictScope.LOGICAL_ENTRY
	}
	for (run in runs) {
		if (!seen.add(run.identity.value)) {
			return PortableStepsConflictScope.PHYSICAL_RUN
		}
		for (fact in run.facts) {
			if (!seen.add(fact.identity.value)) {
				return PortableStepsConflictScope.FACT
			}
		}
	}
	return null
}

internal fun PortableStepsEntryV1.snapshotAndValidate(): PortableStepsEntryV1 = try {
	copy(
		runs = runs.map { run ->
			run.copy(manifests = run.manifests.toList(), facts = run.facts.toList())
		},
	).also { snapshot ->
		require(
			com.adsamcik.tracker.stats.api.repository.PortableStepsIntegrity
				.expectedEntryChecksum(snapshot) == snapshot.contentChecksum,
		)
	}
} catch (failure: IllegalArgumentException) {
	throw PortableStepsSnapshotException(failure)
} catch (failure: ConcurrentModificationException) {
	throw PortableStepsSnapshotException(failure)
}

private fun ExportPortableStepsResult.Unverifiable.toImportResult(): ImportPortableStepsResult = when (reason) {
	PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING -> ImportPortableStepsResult.RetryableFailure(
		PortableStepsTransferRetryableReason.DAY_REPAIR_MATERIALIZING,
	)
	PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY ->
		ImportPortableStepsResult.OutsideRetention
	PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW,
	PortableStepsExportUnverifiableReason.RANGE_UNSUPPORTED,
	-> ImportPortableStepsResult.Unverifiable(PortableStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW)
	PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
	PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
	PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	-> attributionUnverifiable()
}

internal fun attributionUnverifiable() = ImportPortableStepsResult.Unverifiable(
	PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE,
)
