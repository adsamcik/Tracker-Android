package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainReceiptV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsManifestV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsProviderCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsSessionMode
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executor
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedPortableStepsCountDomainFenceRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `full clear removes graph payload and retains terminal owner fences`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val dao = database.importedPortableStepsCountDomainDao()
		dao.insertAuthenticatedGraph(
			graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		dao.insertBinding(
			ImportedPortableStepsCountDomainBindingEntity(
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				entry.identity.value,
				1L,
				graph.identity.value,
				1,
			),
		)

		database.withTransaction {
			database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
		}

		dao.graph(graph.identity.value) shouldBe null
		dao.ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		).map { it.fenceKind }.toSet() shouldBe
			setOf(ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR)
		stagingTableCount() shouldBe 0L
	}

	@Test
	fun `full clear pages deterministic staging owners without heap wide revision queries`() =
		runTest {
			val queries = mutableListOf<String>()
			database.close()
			database = AppDatabase.inMemoryBuilder(
				ApplicationProvider.getApplicationContext<Application>(),
			).allowMainThreadQueries()
				.setQueryCallback(
					{ sql, _ -> queries += sql.replace(Regex("\\s+"), " ").trim().lowercase() },
					Executor(Runnable::run),
				)
				.build()
			val entry = entry()
			seedSessionPayload(entry)
			val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
			val dao = database.importedPortableStepsCountDomainDao()
			dao.insertAuthenticatedGraph(
				graph,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
			)
			dao.insertBinding(
				ImportedPortableStepsCountDomainBindingEntity(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
					entry.identity.value,
					1L,
					graph.identity.value,
					1,
				),
			)
			queries.clear()

			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}

			queries.any {
				it.contains("from imported_steps_full_clear_owner_stage") &&
					it.contains("order by owner_kind, owner_identity limit ?")
			} shouldBe true
			queries.none {
				it.startsWith("select * from imported_steps_count_domain_owner_revision") &&
					!it.contains("where graph_identity = ?")
			} shouldBe true
			stagingTableCount() shouldBe 0L
	}

	@Test
	fun `selected root fencing derives owners from the complete authenticated graph`() {
		val entry = entry()
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val binding = ImportedPortableStepsCountDomainBindingEntity(
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			entry.identity.value,
			1L,
			graph.identity.value,
			1,
		)
		val selected = graph.roots.single {
			it.productIdentity.value == entry.runs.single().facts.first().identity.value
		}

		val fences = authenticatedImportedPortableSelectedOwnerFences(
			selections = listOf(
				AuthenticatedImportedPortableRootSelection(
					AuthenticatedImportedPortableGraphBinding(binding, graph),
					listOf(selected),
				),
			),
			fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
			collectedDataEpoch = 7L,
			fencedAtMs = 9L,
			maximumFenceCount = 1,
		)

		fences.map { it.ownerIdentity } shouldBe listOf(selected.ownerIdentity.value)
		graph.roots.size shouldBe 3
		graph.identity.value shouldBe entry.withExplicitUnprovenCountDomain()
			.countDomainGraph.identity.value
	}

	@Test
	fun `full clear independently fences and deletes an authenticated orphan graph`() = runTest {
		val graph = entry().withExplicitUnprovenCountDomain().countDomainGraph
		val dao = database.importedPortableStepsCountDomainDao()
		dao.insertAuthenticatedGraph(
			graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)

		database.withTransaction {
			database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
		}

		dao.graph(graph.identity.value) shouldBe null
		dao.ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		).map { it.ownerIdentity }.toSet() shouldBe
			graph.roots.map { it.ownerIdentity.value }.toSet()
	}

	@Test
	fun `full clear aggregates overlapping bound and orphan graph revisions into latest fence`() =
		runTest {
			val entry = entry()
			seedSessionPayload(entry)
			val (older, latest) = sessionGraphProgression(entry)
			val advancedRoot = latest.roots.first {
				it.ownerRevision == 2L
			}
			val dao = database.importedPortableStepsCountDomainDao()
			dao.insertAuthenticatedGraph(
				older,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
			)
			installV2SessionBinding(entry, latest)

			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}

			val fence = dao.ownerFences(listOf(advancedRoot.ownerIdentity.value), 2).single()
			fence.latestSourceRevision shouldBe 2L
			fence.latestOwnerEffectChecksum shouldBe latest.ownerRevisions.single {
				it.ownerKind == advancedRoot.ownerKind &&
					it.ownerIdentity == advancedRoot.ownerIdentity &&
					it.ownerRevision == 2L
			}.ownerEffectChecksum.value
			fence.graphIdentity shouldBe latest.identity.value
			dao.graph(older.identity.value) shouldBe null
			dao.graph(latest.identity.value) shouldBe null
		}

	@Test
	fun `full clear folds maximum v1 Ambient corrections and repeated graph appearances`() =
		runTest {
			val day = ambientDay("bounded")
			val appearances = (1L..ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY.toLong()).flatMap {
				revision ->
				List(4) { ambientAppearance(day, revision) }
			}

			val fences = stageFences(
				appearances,
				maximumOwnerCount = 1,
				maximumOwnerRevisionCount = ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY,
			)

			fences.single().latestSourceRevision shouldBe
				ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY.toLong()
		}

	@Test
	fun `full clear folds sparse v1 Ambient owner appearances across graph revisions`() = runTest {
		val first = ambientDay("sparse", factTag = "shared")
		val middle = ambientDay("sparse", factTag = "middle")
		val latest = ambientDay("sparse", factTag = "shared")

		val fences = stageFences(
			listOf(
				ambientAppearance(first, 1L),
				ambientAppearance(middle, 2L),
				ambientAppearance(latest, 3L),
			),
			maximumOwnerCount = 2,
			maximumOwnerRevisionCount = 3,
		)

		fences.single {
			it.ownerIdentity ==
				first.withExplicitUnprovenCountDomain(1L)
					.countDomainGraph.ownerRevisions.single().ownerIdentity
					.value
		}.latestSourceRevision shouldBe 3L
	}

	@Test
	fun `full clear rejects conflicting duplicate v1 Ambient owner semantics`() = runTest {
		val first = ambientDay("conflict", factTag = "shared", stepCount = 1L)
		val conflicting = ambientDay("conflict", factTag = "shared", stepCount = 2L)
		assertFailsWith<IllegalArgumentException> {
			stageFences(
				listOf(
					ambientAppearance(first, 1L),
					ambientAppearance(conflicting, 1L),
				),
				maximumOwnerCount = 1,
				maximumOwnerRevisionCount = 1,
			)
		}
		stagingTableCount() shouldBe 0L
	}

	@Test
	fun `full clear bounds distinct owners and owner revisions rather than appearances`() = runTest {
		val first = ambientAppearance(ambientDay("first"), 1L)
		val second = ambientAppearance(ambientDay("second"), 1L)
		assertFailsWith<IllegalArgumentException> {
			stageFences(
				listOf(first, second),
				maximumOwnerCount = 1,
				maximumOwnerRevisionCount = 2,
			)
		}
		assertFailsWith<IllegalArgumentException> {
			stageFences(
				listOf(first, ambientAppearance(ambientDay("first"), 2L)),
				maximumOwnerCount = 1,
				maximumOwnerRevisionCount = 1,
			)
		}
		stagingTableCount() shouldBe 0L
	}

	@Test
	fun `full clear keeps explicit v2 owner lineages prefix compatible`() = runTest {
		val entry = entry()
		val (older, latest) = sessionGraphProgression(entry)
		val conflicting = conflictingPrefixGraph(latest)
		stageFences(
			listOf(
				sessionAppearance(entry, older, 1L),
				sessionAppearance(entry, latest, 2L),
			),
			maximumOwnerCount = latest.roots.size,
			maximumOwnerRevisionCount = latest.ownerRevisions.size,
		).single {
			it.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT.name &&
				it.latestSourceRevision == 2L
		}.latestSourceRevision shouldBe 2L

		assertFailsWith<IllegalArgumentException> {
			stageFences(
				listOf(
					sessionAppearance(entry, older, 1L),
					sessionAppearance(entry, conflicting, 2L),
				),
				maximumOwnerCount = latest.roots.size,
				maximumOwnerRevisionCount = latest.ownerRevisions.size,
			)
		}
		stagingTableCount() shouldBe 0L
	}

	@Test
	fun `full clear rejects conflicting owner semantics across bound and orphan graphs`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val (_, latest) = sessionGraphProgression(entry)
		val conflicting = conflictingFirstOwnerGraph(latest)
		val dao = database.importedPortableStepsCountDomainDao()
		dao.insertAuthenticatedGraph(
			conflicting,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		installV2SessionBinding(entry, latest)

		assertFailsWith<IllegalArgumentException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}

		dao.graph(conflicting.identity.value)?.graphIdentity shouldBe conflicting.identity.value
		dao.graph(latest.identity.value)?.graphIdentity shouldBe latest.identity.value
		dao.ownerFences(
			latest.roots.map { it.ownerIdentity.value },
			latest.roots.size + 1,
		) shouldBe emptyList()
		stagingTableCount() shouldBe 0L
	}

	@Test
	fun `full clear rejects a graph whose bound file receipt names another graph`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val dao = database.importedPortableStepsCountDomainDao()
		val receiptIdentity = ImportedPortableCountDomainIdentity.fileReceipt("job", "entry")
		val archiveChecksum = "sha256:" + "a".repeat(64)
		dao.insertAuthenticatedGraph(
			graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		dao.insertBinding(
			ImportedPortableStepsCountDomainBindingEntity(
				productKind =
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				productIdentity = entry.identity.value,
				productRevision = 1L,
				graphIdentity = graph.identity.value,
				sourceSchemaVersion = 2,
				sourceReceiptIdentity = receiptIdentity,
				sourceArchiveContentChecksum = archiveChecksum,
			),
		)
		dao.insertFileReceipt(
			ImportedPortableStepsFileReceiptEntity(
				importJobId = "job",
				entryKey = "entry",
				receiptIdentity = receiptIdentity,
				sourceName = "steps.trackersteps",
				receivedAtMs = 1L,
				archiveContentChecksum = archiveChecksum,
				entryOrdinal = 0,
				entryIdentity = entry.identity.value,
				graphIdentity = "sha256:" + "f".repeat(64),
			),
		)

		assertFailsWith<IllegalStateException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}
		dao.graph(graph.identity.value)?.graphIdentity shouldBe graph.identity.value
		dao.ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		) shouldBe emptyList()
	}

	@Test
	fun `compatible terminal fence is preserved across later destructive requests`() = runTest {
		val entry = entry()
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val root = graph.roots.first()
		val owner = graph.ownerRevisions.single {
			it.ownerKind == root.ownerKind &&
				it.ownerIdentity == root.ownerIdentity &&
				it.ownerRevision == root.ownerRevision
		}
		val retained = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
			ownerKind = owner.ownerKind.name,
			ownerIdentity = owner.ownerIdentity.value,
			scopeIdentity = owner.scopeIdentity.value,
			latestSourceRevision = owner.ownerRevision,
			latestOwnerEffectChecksum = owner.ownerEffectChecksum.value,
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			productIdentity = entry.identity.value,
			graphIdentity = graph.identity.value,
			fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
			collectedDataEpoch = 7L,
			fencedAtMs = 8L,
		)
		val laterDeletion = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
			ownerKind = owner.ownerKind.name,
			ownerIdentity = owner.ownerIdentity.value,
			scopeIdentity = owner.scopeIdentity.value,
			latestSourceRevision = owner.ownerRevision,
			latestOwnerEffectChecksum = owner.ownerEffectChecksum.value,
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			productIdentity = entry.identity.value,
			graphIdentity = graph.identity.value,
			fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
			collectedDataEpoch = 8L,
			fencedAtMs = 9L,
		)
		val dao = database.importedPortableStepsCountDomainDao()
		dao.insertOwnerFences(listOf(retained))

		database.insertOrAuthenticateImportedPortableOwnerFences(listOf(laterDeletion))
		database.insertOrAuthenticateImportedPortableOwnerFences(listOf(laterDeletion))

		dao.ownerFences(listOf(owner.ownerIdentity.value), 2) shouldBe listOf(retained)
		val conflicting = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
			ownerKind = owner.ownerKind.name,
			ownerIdentity = owner.ownerIdentity.value,
			scopeIdentity = owner.scopeIdentity.value,
			latestSourceRevision = owner.ownerRevision,
			latestOwnerEffectChecksum = owner.ownerEffectChecksum.value,
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			productIdentity = identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "conflict").value,
			graphIdentity = graph.identity.value,
			fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
			collectedDataEpoch = 8L,
			fencedAtMs = 9L,
		)
		assertFailsWith<IllegalArgumentException> {
			database.insertOrAuthenticateImportedPortableOwnerFences(listOf(conflicting))
		}
		dao.ownerFences(listOf(owner.ownerIdentity.value), 2) shouldBe listOf(retained)
	}

	@Test
	fun `graphless legacy v1 session full clear reconstructs exact unproven owner fences`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val dao = database.importedPortableStepsCountDomainDao()

		AppDatabase.deleteAllCollectedData(
			database = database,
			operationId = "graphless-session",
			collectedDataEpoch = 8L,
			retainedFromMs = null,
			updatedAtMs = 3_000L,
		)

		database.importedStepsDao().entry(entry.identity.value) shouldBe null
		dao.ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		).map { it.ownerIdentity }.toSet() shouldBe
			graph.roots.map { it.ownerIdentity.value }.toSet()
		dao.ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		).all {
			it.fenceKind == ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR &&
				it.collectedDataEpoch == 8L
		} shouldBe true
	}

	@Test
	fun `graphless v1 session rejects orphan durable file receipt provenance`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		database.importedPortableStepsCountDomainDao().insertFileReceipt(
			ImportedPortableStepsFileReceiptEntity(
				importJobId = "orphan-job",
				entryKey = "orphan-entry",
				receiptIdentity = ImportedPortableCountDomainIdentity.fileReceipt(
					"orphan-job",
					"orphan-entry",
				),
				sourceName = "orphan.trackersteps",
				receivedAtMs = 1L,
				archiveContentChecksum = entry.contentChecksum.value,
				entryOrdinal = 0,
				entryIdentity = entry.identity.value,
				graphIdentity = graph.identity.value,
			),
		)

		assertFailsWith<IllegalArgumentException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}

		database.importedStepsDao().entry(entry.identity.value)?.identity shouldBe
			entry.identity.value
		database.importedPortableStepsCountDomainDao().ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		) shouldBe emptyList()
	}

	@Test
	fun `graphless v1 session rejects orphan v2 graph and binding evidence`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val dao = database.importedPortableStepsCountDomainDao()
		dao.insertAuthenticatedGraph(
			graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		dao.insertBinding(
			ImportedPortableStepsCountDomainBindingEntity(
				productKind =
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				productIdentity = identity(
					PortableStepsIdentityKind.LOGICAL_ENTRY,
					"orphan-binding",
				).value,
				productRevision = 1L,
				graphIdentity = graph.identity.value,
				sourceSchemaVersion = 2,
				sourceReceiptIdentity = "sha256:" + "e".repeat(64),
				sourceArchiveContentChecksum = entry.contentChecksum.value,
			),
		)

		assertFailsWith<IllegalArgumentException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}

		database.importedStepsDao().entry(entry.identity.value)?.identity shouldBe
			entry.identity.value
		dao.graph(graph.identity.value)?.graphIdentity shouldBe graph.identity.value
	}

	@Test
	fun `missing v2 session binding never downgrades to synthetic v1`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = installV2SessionBinding(entry)
		val dao = database.importedPortableStepsCountDomainDao()
		val binding = requireNotNull(
			dao.binding(
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				entry.identity.value,
				1L,
			),
		)
		dao.deleteBindingExact(
			binding.productKind,
			binding.productIdentity,
			binding.productRevision,
			binding.graphIdentity,
		) shouldBe 1

		assertFailsWith<IllegalArgumentException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}

		database.importedStepsDao().entry(entry.identity.value)?.identity shouldBe
			entry.identity.value
		dao.graph(graph.identity.value)?.graphIdentity shouldBe graph.identity.value
		dao.ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		) shouldBe emptyList()
	}

	@Test
	fun `orphan file receipt rolls back orphan graph fences and deletion`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val dao = database.importedPortableStepsCountDomainDao()
		dao.insertAuthenticatedGraph(
			graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		dao.insertFileReceipt(fileReceipt(entry, graph, 0))

		assertFailsWith<IllegalArgumentException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}

		dao.graph(graph.identity.value)?.graphIdentity shouldBe graph.identity.value
		dao.fileReceipt("job-0", "entry-0") shouldBe fileReceipt(entry, graph, 0)
		dao.ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		) shouldBe emptyList()
	}

	@Test
	fun `bounded file receipt pages authenticate through the final partial page`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val dao = database.importedPortableStepsCountDomainDao()
		val sourceReceipt = fileReceipt(entry, graph, 0)
		dao.insertAuthenticatedGraph(
			graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		dao.insertBinding(
			ImportedPortableStepsCountDomainBindingEntity(
				productKind =
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				productIdentity = entry.identity.value,
				productRevision = 1L,
				graphIdentity = graph.identity.value,
				sourceSchemaVersion = 1,
				sourceReceiptIdentity = sourceReceipt.receiptIdentity,
				sourceArchiveContentChecksum = entry.contentChecksum.value,
			),
		)
		repeat(ImportedPortableStepsCountDomainDao.FILE_RECEIPT_PAGE_SIZE + 1) { index ->
			dao.insertFileReceipt(fileReceipt(entry, graph, index))
		}

		database.loadAuthenticatedImportedSessionCountDomainBinding(entry)
			?.graph shouldBe graph
	}

	@Test
	fun `full clear fences authenticated products before deleting overbound file receipts`() =
		runTest {
			val entry = entry()
			seedSessionPayload(entry)
			val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
			val dao = database.importedPortableStepsCountDomainDao()
			val sourceReceipt = fileReceipt(entry, graph, 0)
			dao.insertAuthenticatedGraph(
				graph,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
			)
			dao.insertBinding(
				ImportedPortableStepsCountDomainBindingEntity(
					productKind =
						ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
					productIdentity = entry.identity.value,
					productRevision = 1L,
					graphIdentity = graph.identity.value,
					sourceSchemaVersion = 1,
					sourceReceiptIdentity = sourceReceipt.receiptIdentity,
					sourceArchiveContentChecksum = entry.contentChecksum.value,
				),
			)
			(0..ImportedPortableStepsCountDomainDao.MAX_FILE_RECEIPTS_PER_ENTRY)
				.chunked(256)
				.forEach { indices ->
					dao.insertFileReceipts(
						indices.map { index -> fileReceipt(entry, graph, index) },
					)
				}
			assertFailsWith<IllegalStateException> {
				database.loadAuthenticatedImportedSessionCountDomainBinding(entry)
			}

			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}

			dao.graph(graph.identity.value) shouldBe null
			dao.fileReceiptCountForEntry(entry.identity.value) shouldBe 0
			dao.ownerFences(
				graph.roots.map { it.ownerIdentity.value },
				graph.roots.size + 1,
			).map { it.ownerIdentity }.toSet() shouldBe
				graph.roots.map { it.ownerIdentity.value }.toSet()
		}

	@Test
	fun `missing graphless session evidence rolls back full clear fences`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val factIdentity = entry.runs.single().facts.first().identity.value
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM step_fact_revision WHERE logical_fact_id = ?",
			arrayOf(factIdentity),
		)

		assertFailsWith<IllegalStateException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}

		database.importedStepsDao().entry(entry.identity.value)?.identity shouldBe entry.identity.value
		database.importedPortableStepsCountDomainDao().ownerFences(
			entry.withExplicitUnprovenCountDomain().countDomainGraph.roots
				.map { it.ownerIdentity.value },
			8,
		) shouldBe emptyList()
	}

	@Test
	fun `conflicting preexisting owner fence rolls back live graphless session clear`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val owner = graph.ownerRevisions.first()
		val conflicting = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
			ownerKind = owner.ownerKind.name,
			ownerIdentity = owner.ownerIdentity.value,
			scopeIdentity = owner.scopeIdentity.value,
			latestSourceRevision = owner.ownerRevision,
			latestOwnerEffectChecksum = owner.ownerEffectChecksum.value,
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			productIdentity = identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "other").value,
			graphIdentity = graph.identity.value,
			fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_SELECTED_DELETE,
			collectedDataEpoch = 7L,
			fencedAtMs = 8L,
		)
		database.importedPortableStepsCountDomainDao().insertOwnerFences(listOf(conflicting))

		assertFailsWith<IllegalArgumentException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}

		database.importedStepsDao().entry(entry.identity.value)?.identity shouldBe entry.identity.value
		database.importedPortableStepsCountDomainDao().ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		) shouldBe listOf(conflicting)
		stagingTableCount() shouldBe 0L
	}

	@Test
	fun `full clear accepts retention truncated v2 graph only with exact pruned owner fence`() =
		runTest {
			val entry = entry()
			seedSessionPayload(entry)
			val graph = installV2SessionBinding(entry)
			database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 100L)
			database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(
				1_500L,
				7L,
				100L,
			) shouldBe 1

			AppDatabase.deleteAllCollectedData(
				database,
				operationId = "truncated-session",
				collectedDataEpoch = 8L,
				retainedFromMs = null,
				updatedAtMs = 200L,
			)

			database.importedStepsDao().entry(entry.identity.value) shouldBe null
			database.importedPortableStepsCountDomainDao().ownerFences(
				graph.roots.map { it.ownerIdentity.value },
				graph.roots.size + 1,
			).map { it.ownerIdentity }.toSet() shouldBe
				graph.roots.map { it.ownerIdentity.value }.toSet()
		}

	@Test
	fun `full clear rejects retention truncated v2 graph when pruned owner fence is missing`() =
		runTest {
			val entry = entry()
			seedSessionPayload(entry)
			installV2SessionBinding(entry)
			database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 100L)
			database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(
				1_500L,
				7L,
				100L,
			) shouldBe 1
			val removedOwner = entry.withExplicitUnprovenCountDomain().countDomainGraph.roots
				.single {
					it.productIdentity.value == entry.runs.single().facts.first().identity.value
				}
				.ownerIdentity.value
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM imported_steps_count_domain_owner_fence WHERE owner_identity = ?",
				arrayOf(removedOwner),
			)

			assertFailsWith<IllegalArgumentException> {
				AppDatabase.deleteAllCollectedData(
					database,
					operationId = "truncated-session-missing-fence",
					collectedDataEpoch = 8L,
					retainedFromMs = null,
					updatedAtMs = 200L,
				)
			}

			database.importedStepsDao().entry(entry.identity.value)?.identity shouldBe
				entry.identity.value
		}

	@Test
	fun `schema v1 binding cannot disguise fabricated bind authority`() = runTest {
		val entry = entry()
		seedSessionPayload(entry)
		val expected = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val owner = expected.ownerRevisions.first {
			it.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT
		}
		val receipt = PortableCountDomainReceiptV2.create(
			domainIdentity = owner.scopeIdentity,
			ownerKind = owner.ownerKind,
			scopeIdentity = owner.scopeIdentity,
			ownerIdentity = owner.ownerIdentity,
			ownerRevision = owner.ownerRevision,
			registrationGeneration = 1L,
			collectedDataEpoch = 7L,
			authorityRevision = 1L,
			authorityFingerprint = PortableCountDomainDigest("sha256:" + "a".repeat(64)),
			coverage = PortableCountDomainCoverage.COVERED,
			coverageVersion = 1,
			countDomainVersion = 1,
			effectChecksum = owner.ownerEffectChecksum,
			completenessEvidenceChecksum = null,
		)
		val fabricated = PortableCountDomainGraphV2.create(
			receipts = listOf(receipt),
			ownerRevisions = expected.ownerRevisions.map {
				if (it == owner) {
					it.copy(
						operation = PortableCountDomainOperation.BIND,
						receiptIdentity = receipt.identity,
					)
				} else {
					it
				}
			},
			completenessMarkers = expected.completenessMarkers,
			roots = expected.roots,
		)
		val dao = database.importedPortableStepsCountDomainDao()
		dao.insertAuthenticatedGraph(
			fabricated,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		dao.insertBinding(
			ImportedPortableStepsCountDomainBindingEntity(
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				entry.identity.value,
				1L,
				fabricated.identity.value,
				1,
			),
		)

		assertFailsWith<IllegalStateException> {
			database.withTransaction {
				database.preserveImportedPortableCountDomainFullClearFences(7L, 8L, 9L)
			}
		}
		dao.ownerFences(
			fabricated.roots.map { it.ownerIdentity.value },
			fabricated.roots.size + 1,
		) shouldBe emptyList()
	}

	private suspend fun stageFences(
		graphs: List<AuthenticatedFullClearGraphAppearance>,
		maximumOwnerCount: Int,
		maximumOwnerRevisionCount: Int,
	): List<ImportedPortableStepsCountDomainOwnerFenceEntity> {
		database.withTransaction {
			database.stageAuthenticatedPortableOwnerFencesForFullClear(
				graphs = graphs.asSequence(),
				fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
				collectedDataEpoch = 8L,
				fencedAtMs = 9L,
				maximumOwnerCount = maximumOwnerCount,
				maximumOwnerRevisionCount = maximumOwnerRevisionCount,
			)
		}
		val ownerIdentities = graphs.asSequence()
			.flatMap { it.graph.roots.asSequence() }
			.map { it.ownerIdentity.value }
			.distinct()
			.toList()
		return database.importedPortableStepsCountDomainDao().ownerFences(
			ownerIdentities,
			ownerIdentities.size + 1,
		)
	}

	private fun stagingTableCount(): Long =
		database.openHelper.writableDatabase.query(
			"SELECT COUNT(*) FROM sqlite_temp_master WHERE type = 'table' " +
				"AND name LIKE 'imported_steps_full_clear_%_stage'",
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private suspend fun seedSessionPayload(entry: PortableStepsEntryV1) {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 7L),
		)
		val metadata = ImportedStepsAdmissionRows.entry(entry, 7L, 7L)
		database.withTransaction {
			database.importedStepsDao().insertEntry(metadata)
			entry.runs.forEach { run ->
				val segmentId = database.sessionSegmentDao().insert(
					SessionSegment(
						startTimeMs = run.startTimeMs,
						endTimeMs = run.endTimeMs,
						distanceM = 0f,
						steps = null,
						primaryActivity = null,
						activityConfidence = null,
						sampleCount = 0,
						logicalTrackingId = metadata.identity,
						serviceRunId = run.identity.value,
						source = SegmentSource.PORTABLE_STEPS_IMPORT,
						inferenceVersion = null,
						createdAt = 100L,
					),
				)
				database.importedStepsDao().insertRun(
					ImportedStepsAdmissionRows.run(metadata, run, segmentId),
				)
				database.importedStepsDao().insertManifests(
					ImportedStepsAdmissionRows.manifests(run),
				)
				run.facts.forEach { fact ->
					database.stepFactRevisionDao().insert(
						ImportedStepsAdmissionRows.fact(metadata, run, fact, 100L),
					)
				}
			}
		}
	}

	private suspend fun installV2SessionBinding(
		entry: PortableStepsEntryV1,
		graph: PortableCountDomainGraphV2 =
			entry.withExplicitUnprovenCountDomain().countDomainGraph,
	): PortableCountDomainGraphV2 {
		val dao = database.importedPortableStepsCountDomainDao()
		val receiptIdentity = ImportedPortableCountDomainIdentity.fileReceipt("v2-job", "v2-entry")
		dao.insertAuthenticatedGraph(
			graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
		)
		dao.insertBinding(
			ImportedPortableStepsCountDomainBindingEntity(
				productKind =
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				productIdentity = entry.identity.value,
				productRevision = 1L,
				graphIdentity = graph.identity.value,
				sourceSchemaVersion = 2,
				sourceReceiptIdentity = receiptIdentity,
				sourceArchiveContentChecksum = entry.contentChecksum.value,
			),
		)
		dao.insertFileReceipt(
			ImportedPortableStepsFileReceiptEntity(
				importJobId = "v2-job",
				entryKey = "v2-entry",
				receiptIdentity = receiptIdentity,
				sourceName = "steps.trackersteps",
				receivedAtMs = 1L,
				archiveContentChecksum = entry.contentChecksum.value,
				entryOrdinal = 0,
				entryIdentity = entry.identity.value,
				graphIdentity = graph.identity.value,
			),
		)
		return graph
	}

	private fun sessionGraphProgression(
		entry: PortableStepsEntryV1,
	): Pair<PortableCountDomainGraphV2, PortableCountDomainGraphV2> {
		val template = entry.withExplicitUnprovenCountDomain().countDomainGraph
		val originalOwner = template.ownerRevisions.first {
			it.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT
		}
		val firstReceipt = sessionFactReceipt(
			originalOwner,
			1L,
			originalOwner.ownerEffectChecksum,
		)
		val firstOwner = originalOwner.copy(
			operation = PortableCountDomainOperation.BIND,
			receiptIdentity = firstReceipt.identity,
		)
		val older = PortableCountDomainGraphV2.create(
			receipts = listOf(firstReceipt),
			ownerRevisions = template.ownerRevisions.map {
				if (it == originalOwner) firstOwner else it
			},
			completenessMarkers = template.completenessMarkers,
			roots = template.roots,
		)
		val latestEffect = PortableCountDomainDigest("sha256:" + "d".repeat(64))
		val latestReceipt = sessionFactReceipt(originalOwner, 2L, latestEffect)
		val latestOwner = PortableCountDomainOwnerRevisionV2(
			ownerKind = originalOwner.ownerKind,
			scopeIdentity = originalOwner.scopeIdentity,
			ownerIdentity = originalOwner.ownerIdentity,
			ownerRevision = 2L,
			operation = PortableCountDomainOperation.BIND,
			receiptIdentity = latestReceipt.identity,
			ownerEffectChecksum = latestEffect,
			linkedAtMs = 2L,
		)
		val latest = PortableCountDomainGraphV2.create(
			receipts = listOf(firstReceipt, latestReceipt),
			ownerRevisions = older.ownerRevisions + latestOwner,
			completenessMarkers = older.completenessMarkers,
			roots = older.roots.map { root ->
				if (root.ownerKind == originalOwner.ownerKind &&
					root.ownerIdentity == originalOwner.ownerIdentity
				) {
					root.copy(ownerRevision = 2L)
				} else {
					root
				}
			},
		)
		return older to latest
	}

	private fun conflictingFirstOwnerGraph(
		latest: PortableCountDomainGraphV2,
	): PortableCountDomainGraphV2 {
		val firstOwner = latest.ownerRevisions.first {
			it.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT &&
				it.ownerRevision == 1L
		}
		val conflictingEffect = PortableCountDomainDigest("sha256:" + "c".repeat(64))
		val receipt = sessionFactReceipt(firstOwner, 1L, conflictingEffect)
		return PortableCountDomainGraphV2.create(
			receipts = listOf(receipt),
			ownerRevisions = latest.ownerRevisions
				.filterNot {
					it.ownerKind == firstOwner.ownerKind &&
						it.ownerIdentity == firstOwner.ownerIdentity
				}
				.plus(
					firstOwner.copy(
						operation = PortableCountDomainOperation.BIND,
						receiptIdentity = receipt.identity,
						ownerEffectChecksum = conflictingEffect,
					),
				),
			completenessMarkers = latest.completenessMarkers,
			roots = latest.roots.map { root ->
				if (root.ownerKind == firstOwner.ownerKind &&
					root.ownerIdentity == firstOwner.ownerIdentity
				) {
					root.copy(ownerRevision = 1L)
				} else {
					root
				}
			},
		)
	}

	private fun conflictingPrefixGraph(
		latest: PortableCountDomainGraphV2,
	): PortableCountDomainGraphV2 {
		val firstOwner = latest.ownerRevisions.first {
			it.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT &&
				it.ownerRevision == 1L
		}
		val conflictingEffect = PortableCountDomainDigest("sha256:" + "e".repeat(64))
		val conflictingReceipt = sessionFactReceipt(firstOwner, 1L, conflictingEffect)
		return PortableCountDomainGraphV2.create(
			receipts = latest.receipts.filterNot {
				it.ownerKind == firstOwner.ownerKind &&
					it.ownerIdentity == firstOwner.ownerIdentity &&
					it.ownerRevision == firstOwner.ownerRevision
			} + conflictingReceipt,
			ownerRevisions = latest.ownerRevisions.map {
				if (it == firstOwner) {
					it.copy(
						receiptIdentity = conflictingReceipt.identity,
						ownerEffectChecksum = conflictingEffect,
					)
				} else {
					it
				}
			},
			completenessMarkers = latest.completenessMarkers,
			roots = latest.roots,
		)
	}

	private fun ambientAppearance(
		day: PortableAmbientStepsDayV1,
		revision: Long,
	): AuthenticatedFullClearGraphAppearance {
		val graph = day.withExplicitUnprovenCountDomain(revision).countDomainGraph
		return AuthenticatedFullClearGraphAppearance(
			graph = graph,
			graphIdentity = graph.identity.value,
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			productIdentity = day.identity.value,
			graphRevision = revision,
			sourceSchemaVersion = AmbientStepsPortableFormatV1.SCHEMA_VERSION,
			isBound = true,
		)
	}

	private fun sessionAppearance(
		entry: PortableStepsEntryV1,
		graph: PortableCountDomainGraphV2,
		revision: Long,
	): AuthenticatedFullClearGraphAppearance =
		AuthenticatedFullClearGraphAppearance(
			graph = graph,
			graphIdentity = graph.identity.value,
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			productIdentity = entry.identity.value,
			graphRevision = revision,
			sourceSchemaVersion = 2,
			isBound = true,
		)

	private fun ambientDay(
		tag: String,
		factTag: String = tag,
		stepCount: Long = 1L,
	): PortableAmbientStepsDayV1 {
		val fact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				"fact-$factTag",
			),
			0L,
			86_400_000L,
			stepCount,
		)
		return PortableAmbientStepsDayV1.create(
			identity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.DAY,
				"day-$tag",
			),
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = 86_400_000L,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			partialCauses = emptyList(),
			retainedStepCount = stepCount,
			facts = listOf(fact),
			gaps = emptyList(),
		)
	}

	private fun sessionFactReceipt(
		owner: PortableCountDomainOwnerRevisionV2,
		revision: Long,
		effect: PortableCountDomainDigest,
	): PortableCountDomainReceiptV2 = PortableCountDomainReceiptV2.create(
		domainIdentity = owner.scopeIdentity,
		ownerKind = owner.ownerKind,
		scopeIdentity = owner.scopeIdentity,
		ownerIdentity = owner.ownerIdentity,
		ownerRevision = revision,
		registrationGeneration = 1L,
		collectedDataEpoch = 7L,
		authorityRevision = revision,
		authorityFingerprint = PortableCountDomainDigest("sha256:" + "a".repeat(64)),
		coverage = PortableCountDomainCoverage.COVERED,
		coverageVersion = 1,
		countDomainVersion = 1,
		effectChecksum = effect,
		completenessEvidenceChecksum = null,
	)

	private fun fileReceipt(
		entry: PortableStepsEntryV1,
		graph: PortableCountDomainGraphV2,
		index: Int,
	): ImportedPortableStepsFileReceiptEntity = ImportedPortableStepsFileReceiptEntity(
		importJobId = "job-$index",
		entryKey = "entry-$index",
		receiptIdentity = ImportedPortableCountDomainIdentity.fileReceipt(
			"job-$index",
			"entry-$index",
		),
		sourceName = "steps-$index.trackersteps",
		receivedAtMs = index.toLong(),
		archiveContentChecksum = entry.contentChecksum.value,
		entryOrdinal = 0,
		entryIdentity = entry.identity.value,
		graphIdentity = graph.identity.value,
	)

	private fun entry(): PortableStepsEntryV1 {
		val run = PortableStepsRunV1(
			identity = identity(PortableStepsIdentityKind.PHYSICAL_RUN, "run"),
			deletionScopeDigest = PortableStepsDeletionScopeDigest.derive("entry", "run"),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			storedZoneId = "UTC",
			manifests = listOf(PortableStepsManifestV1(1L, 1_000L, 1L, 1L)),
			completeness = PortableStepsCompletenessV1(
				PortableStepsCaptureCoverage.WHOLE_RUN,
				PortableStepsProviderCoverage.COMPLETE,
				true,
				true,
				false,
			),
			facts = listOf(
				PortableStepsFactV1.create(
					identity(PortableStepsIdentityKind.FACT, "fact-1"),
					1L,
					1_000L,
					1_400L,
					0L,
					PortableStepsFactCoverage.COVERED,
					2L,
				),
				PortableStepsFactV1.create(
					identity(PortableStepsIdentityKind.FACT, "fact-2"),
					1L,
					1_400L,
					2_000L,
					0L,
					PortableStepsFactCoverage.COVERED,
					2L,
				),
			),
		)
		return PortableStepsEntryV1.create(
			identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "entry"),
			PortableStepsSessionMode.MANUAL,
			1_000L,
			2_000L,
			listOf(run),
		)
	}

	private fun identity(kind: PortableStepsIdentityKind, value: String) =
		PortableStepsOpaqueIdentity.derive(kind, value)
}
