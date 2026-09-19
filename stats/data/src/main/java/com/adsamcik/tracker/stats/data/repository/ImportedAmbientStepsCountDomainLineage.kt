package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AuthenticatedImportedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.authenticatedGraph
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2

internal data class AuthenticatedImportedAmbientStepsGraphRevision(
	val graphRevision: Long,
	val productImportRevisions: Set<Long>,
	val sourceSchemaVersion: Int,
	val graph: PortableCountDomainGraphV2,
)

internal suspend fun AppDatabase.loadAuthenticatedImportedAmbientStepsGraphLineage(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	val dayIdentity = lineage.revisions.lastOrNull()?.header?.dayIdentity
	val bindings = dayIdentity?.let {
		importedPortableStepsCountDomainDao().bindings(
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			listOf(it),
		)
	}.orEmpty()
	if (lineage.revisions.isEmpty()) {
		check(lineage.archiveDays.isEmpty())
		check(bindings.isEmpty())
		return emptyList()
	}
	val resolvedDayIdentity = checkNotNull(dayIdentity)
	val members = lineage.archiveDays.filter { it.dayIdentity == resolvedDayIdentity }
	check(members.isNotEmpty())
	check(bindings.isNotEmpty())
	check(bindings.size <= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY)
	check(bindings.map { it.productRevision } == (1L..bindings.size.toLong()).toList())
	check(
		bindings.mapTo(linkedSetOf()) { it.productRevision } ==
			members.mapTo(linkedSetOf()) { it.boundCountDomainGraphRevision },
	)
	val revisionsByNumber = lineage.revisions.associateBy { it.header.importRevision }
	val archivesByIdentity = lineage.archives.associateBy { it.archiveIdentity }
	val graphLineage = bindings.map { binding ->
		check(binding.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY)
		check(binding.productIdentity == resolvedDayIdentity)
		val revisionMembers = members.filter {
			it.boundCountDomainGraphRevision == binding.productRevision
		}
		check(revisionMembers.isNotEmpty())
		val productRevisions = revisionMembers.mapTo(linkedSetOf()) { it.boundDayImportRevision }
		val sourceSchemaVersions = revisionMembers.mapTo(linkedSetOf()) { member ->
			checkNotNull(archivesByIdentity[member.archiveIdentity]).sourceSchemaVersion
		}
		check(binding.sourceSchemaVersion in sourceSchemaVersions)
		val graph = checkNotNull(
			importedPortableStepsCountDomainDao().authenticatedGraph(
				binding.graphIdentity,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
			),
		)
		productRevisions.forEach { productRevision ->
			PortableAmbientStepsDayV2(
				checkNotNull(revisionsByNumber[productRevision]).day,
				graph,
			)
		}
		AuthenticatedImportedAmbientStepsGraphRevision(
			graphRevision = binding.productRevision,
			productImportRevisions = productRevisions,
			sourceSchemaVersion = binding.sourceSchemaVersion,
			graph = graph,
		)
	}
	check(
		lineage.latest.header.importRevision in
			graphLineage.last().productImportRevisions,
	)
	check(
		graphLineage.map { it.graph.identity }.distinct().size == graphLineage.size,
	)
	return graphLineage
}
