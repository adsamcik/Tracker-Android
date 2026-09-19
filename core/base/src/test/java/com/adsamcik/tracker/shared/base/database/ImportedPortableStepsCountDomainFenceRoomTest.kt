package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
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
			database.preserveImportedPortableCountDomainFullClearFences(8L, 9L)
		}

		dao.graph(graph.identity.value) shouldBe null
		dao.ownerFences(
			graph.roots.map { it.ownerIdentity.value },
			graph.roots.size + 1,
		).map { it.fenceKind }.toSet() shouldBe
			setOf(ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR)
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
					identity(PortableStepsIdentityKind.FACT, "fact"),
					1L,
					1_000L,
					2_000L,
					0L,
					PortableStepsFactCoverage.COVERED,
					4L,
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
