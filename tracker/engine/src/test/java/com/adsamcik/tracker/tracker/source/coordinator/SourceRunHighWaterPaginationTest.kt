package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.RawSourceRunWalGenerationEvidence
import com.adsamcik.tracker.shared.base.database.dao.RawSourceRunWalManifestRevisionEvidence
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalDao
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceRunHighWaterPaginationTest {
	@Test
	fun `2048 revisions with two provider generations page without association rejection`() =
		runTest {
			val providers = listOf(
				ProviderIdentity("steps-provider", 1L),
				ProviderIdentity("steps-provider", 2L),
			)
			val rows = buildList {
				var pageRowId = 1L
				for (revision in 1L..MAX_RUN_DRAIN_MANIFEST_REVISIONS.toLong()) {
					for (provider in providers) {
						add(
							manifestAssociation(
								pageRowId = pageRowId++,
								manifestRevision = revision,
								provider = provider,
							),
						)
					}
				}
			}
			val generationRows = providers.mapIndexed { index, provider ->
				generationAssociation(provider, highWaterAdmissionOrdinal = index.toLong() + 1L)
			}
			val fixture = fixture(rows, generationRows)

			fixture.read(
				manifestRevisions = (1L..MAX_RUN_DRAIN_MANIFEST_REVISIONS.toLong()).toList(),
				throughOrdinal = 2L,
				providers = providers,
			) shouldBe SourceRunHighWaterRead.Ready(2L)
		}

	@Test
	fun `one revision accepts the complete supported provider generation envelope`() = runTest {
		val providers = (1..MAX_RUN_RETIREMENT_ACTIONS).map(::provider)
		val rows = providers.mapIndexed { index, provider ->
			manifestAssociation(
				pageRowId = index.toLong() + 1L,
				manifestRevision = 1L,
				provider = provider,
			)
		}
		val generationRows = providers.mapIndexed { index, provider ->
			generationAssociation(
				provider = provider,
				highWaterAdmissionOrdinal = index.toLong() + 1L,
			)
		}
		val fixture = fixture(rows, generationRows)

		fixture.read(
			manifestRevisions = listOf(1L),
			throughOrdinal = MAX_RUN_RETIREMENT_ACTIONS.toLong(),
			providers = providers,
		) shouldBe SourceRunHighWaterRead.Ready(MAX_RUN_RETIREMENT_ACTIONS.toLong())
	}

	@Test
	fun `duplicate association cursor across pages fails closed`() = runTest {
		val firstPage = (1L..ASSOCIATION_PAGE_SIZE.toLong()).map { pageRowId ->
			controlAssociation(pageRowId, pageRowId)
		}
		val duplicateBoundary = listOf(
			controlAssociation(
				pageRowId = ASSOCIATION_PAGE_SIZE.toLong(),
				manifestRevision = ASSOCIATION_PAGE_SIZE.toLong() + 1L,
			),
		)
		val fixture = scriptedFixture(listOf(firstPage, duplicateBoundary))

		fixture.read(
			manifestRevisions = (1L..(ASSOCIATION_PAGE_SIZE + 1L)).toList(),
			throughOrdinal = ASSOCIATION_PAGE_SIZE + 1L,
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `out of order association page fails closed`() = runTest {
		val fixture = scriptedFixture(
			listOf(
				listOf(
					controlAssociation(pageRowId = 2L, manifestRevision = 1L),
					controlAssociation(pageRowId = 1L, manifestRevision = 1L),
				),
			),
		)

		fixture.read(
			manifestRevisions = listOf(1L),
			throughOrdinal = 2L,
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `later association page cancellation propagates`() = runTest {
		val database = mockk<AppDatabase>()
		val dao = mockk<SourceEventWalDao>()
		val firstPage = (1L..ASSOCIATION_PAGE_SIZE.toLong()).map { pageRowId ->
			controlAssociation(pageRowId, manifestRevision = 1L)
		}
		var pageCalls = 0
		every { database.sourceEventWalDao() } returns dao
		coEvery {
			dao.rawExactRunSourceManifestRevisionAssociations(
				any(), any(), any(), any(), any(), any(), any(), any(), any(),
			)
		} coAnswers {
			if (pageCalls++ == 0) {
				firstPage
			} else {
				throw CancellationException("cancel later association page")
			}
		}

		shouldThrow<CancellationException> {
			sourceRunHighWater(
				database = database,
				source = SourceKind.STEPS,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				runManifestRevisions = listOf(1L),
				throughOrdinal = 1L,
				productMemberships = emptyList(),
				retirementClaims = emptyList(),
			)
		}
	}

	@Test
	fun `oversized association page fails closed`() = runTest {
		val oversizedPage = (1L..(ASSOCIATION_PAGE_SIZE + 1L)).map { pageRowId ->
			controlAssociation(pageRowId, manifestRevision = 1L)
		}
		val fixture = scriptedFixture(listOf(oversizedPage))

		fixture.read(
			manifestRevisions = listOf(1L),
			throughOrdinal = oversizedPage.size.toLong(),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	@Test
	fun `provider generation envelope overflow fails closed across pages`() = runTest {
		val rows = (1..MAX_RUN_RETIREMENT_ACTIONS + 1).map { generation ->
			controlAssociation(
				pageRowId = generation.toLong(),
				manifestRevision = 1L,
				provider = provider(generation),
			)
		}
		val fixture = fixture(rows, emptyList())

		fixture.read(
			manifestRevisions = listOf(1L),
			throughOrdinal = rows.size.toLong(),
		) shouldBe SourceRunHighWaterRead.Unverifiable
	}

	private fun fixture(
		rows: List<RawSourceRunWalManifestRevisionEvidence>,
		generationRows: List<RawSourceRunWalGenerationEvidence>,
	): Fixture {
		val database = mockk<AppDatabase>()
		val dao = mockk<SourceEventWalDao>()
		every { database.sourceEventWalDao() } returns dao
		coEvery {
			dao.rawExactRunSourceManifestRevisionAssociations(
				any(), any(), any(), any(), any(), any(), any(), any(), any(),
			)
		} coAnswers {
			val afterAssociationRowId = invocation.args[7] as Long?
			val limit = invocation.args[8] as Int
			rows.asSequence()
				.filter { row ->
					afterAssociationRowId == null ||
						requireNotNull(row.associationPageRowId) > afterAssociationRowId
				}
				.take(limit)
				.toList()
		}
		coEvery {
			dao.rawExactRunSourceManifestAssociations(
				any(), any(), any(), any(), any(), any(), any(), any(), any(),
			)
		} returns generationRows
		coEvery {
			dao.rawWrongRunSourceManifestAssociations(
				any(), any(), any(), any(), any(), any(), any(), any(),
			)
		} returns emptyList()
		return Fixture(database)
	}

	private fun scriptedFixture(
		pages: List<List<RawSourceRunWalManifestRevisionEvidence>>,
	): Fixture {
		val database = mockk<AppDatabase>()
		val dao = mockk<SourceEventWalDao>()
		every { database.sourceEventWalDao() } returns dao
		coEvery {
			dao.rawExactRunSourceManifestRevisionAssociations(
				any(), any(), any(), any(), any(), any(), any(), any(), any(),
			)
		} returnsMany pages
		return Fixture(database)
	}

	private data class Fixture(val database: AppDatabase) {
		suspend fun read(
			manifestRevisions: List<Long>,
			throughOrdinal: Long,
			providers: List<ProviderIdentity> = emptyList(),
		): SourceRunHighWaterRead = sourceRunHighWater(
			database = database,
			source = SourceKind.STEPS,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			runManifestRevisions = manifestRevisions,
			throughOrdinal = throughOrdinal,
			productMemberships = providers.map { provider ->
				SourceDrainMembership(
					sourceInstanceId = provider.sourceInstanceId,
					registrationGeneration = provider.registrationGeneration,
					lastAdmissionOrdinal = throughOrdinal,
					lastSourceSequence = 1L,
					appDrainComplete = true,
					providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
					stopStatus = "COMPLETE",
					unresolvedSequenceStart = null,
					unresolvedSequenceEndInclusive = null,
				)
			},
			retirementClaims = providers.map { provider ->
				SourceDrainRetirementClaim(
					source = SourceKind.STEPS,
					sourceInstanceId = provider.sourceInstanceId,
					registrationGeneration = provider.registrationGeneration,
					actionId = "action-${provider.registrationGeneration}",
					attemptCount = 1,
					leaseGeneration = provider.registrationGeneration,
					cleanupOnly = false,
				)
			},
		)
	}

	private data class ProviderIdentity(
		val sourceInstanceId: String,
		val registrationGeneration: Long,
	)

	private fun provider(generation: Int) = ProviderIdentity(
		sourceInstanceId = "steps-provider-$generation",
		registrationGeneration = generation.toLong(),
	)

	private fun manifestAssociation(
		pageRowId: Long,
		manifestRevision: Long,
		provider: ProviderIdentity,
		productEligibleRowCount: Long = 1L,
		controlOnlyRowCount: Long = 0L,
	) = RawSourceRunWalManifestRevisionEvidence(
		associationPageRowId = pageRowId,
		sessionManifestRevision = manifestRevision,
		sourceInstanceId = provider.sourceInstanceId,
		registrationGeneration = provider.registrationGeneration,
		associatedRowCount = 1L,
		malformedRowCount = 0L,
		productEligibleRowCount = productEligibleRowCount,
		controlOnlyRowCount = controlOnlyRowCount,
	)

	private fun controlAssociation(
		pageRowId: Long,
		manifestRevision: Long,
		provider: ProviderIdentity = provider(pageRowId.toInt()),
	) = manifestAssociation(
		pageRowId = pageRowId,
		manifestRevision = manifestRevision,
		provider = provider,
		productEligibleRowCount = 0L,
		controlOnlyRowCount = 1L,
	)

	private fun generationAssociation(
		provider: ProviderIdentity,
		highWaterAdmissionOrdinal: Long,
	) = RawSourceRunWalGenerationEvidence(
		sourceInstanceId = provider.sourceInstanceId,
		registrationGeneration = provider.registrationGeneration,
		lifecycleLeaseGeneration = provider.registrationGeneration,
		associatedRowCount = 1L,
		malformedRowCount = 0L,
		productEligibleRowCount = 1L,
		controlOnlyRowCount = 0L,
		highWaterAdmissionOrdinal = highWaterAdmissionOrdinal,
	)

	private companion object {
		const val ASSOCIATION_PAGE_SIZE = 128
		const val LOGICAL_TRACKING_ID = "paged-association-logical"
		const val SERVICE_RUN_ID = "paged-association-run"
	}
}
