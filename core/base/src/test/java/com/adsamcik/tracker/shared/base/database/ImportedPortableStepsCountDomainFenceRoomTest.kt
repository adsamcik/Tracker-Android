package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
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
	): PortableCountDomainGraphV2 {
		val graph = entry.withExplicitUnprovenCountDomain().countDomainGraph
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
