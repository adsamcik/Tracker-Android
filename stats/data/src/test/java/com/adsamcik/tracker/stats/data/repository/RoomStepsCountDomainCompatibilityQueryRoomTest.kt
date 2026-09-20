package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainSchema
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.stats.api.repository.MAX_STEPS_COUNT_DOMAIN_REQUESTS
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityRequest
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityResult
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerEffect
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerIdentity
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerKind
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerOrigin
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerReference
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainCoverage
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainDigest
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainGraphV2
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainOperation
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainOwnerKind as PortableOwnerKind
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainReceiptV2
import com.adsamcik.tracker.stats.api.repository.PortableCountDomainRootV2
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2Request
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportMetadataV2
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportReceipt
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomStepsCountDomainCompatibilityQueryRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var query: RoomStepsCountDomainCompatibilityQuery

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		check(
			StepsCountDomainSchema.installIfAbsent(database.openHelper.writableDatabase) ==
				com.adsamcik.tracker.shared.base.database.StepsCountDomainSchemaState.ValidV2,
		)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		database.sourceEvidenceStateDao().updateLifecycle(7L, null, 0L)
		query = RoomStepsCountDomainCompatibilityQuery(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `production query accepts one token across independent owner registrations`() = runTest {
		val sessionFact = insertEvidence(
			StepsCountDomainOwnerKind.SESSION_FACT,
			'1',
			tokenDigit = 'a',
			registrationGeneration = 1L,
		)
		val completeness = insertEvidence(
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			'2',
			tokenDigit = 'a',
			registrationGeneration = 1L,
		)
		val ambient = insertEvidence(
			StepsCountDomainOwnerKind.AMBIENT_FACT,
			'3',
			tokenDigit = 'a',
			registrationGeneration = 99L,
		)

		val request = StepsCountDomainCompatibilityRequest(
			listOf(sessionFact, completeness),
			listOf(ambient),
		)
		query.compare(
			listOf(
				request,
			),
		) shouldContainExactly listOf(StepsCountDomainCompatibilityResult.ExactCompatible)

		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM steps_count_domain_completeness_marker WHERE owner_identity = ?",
			arrayOf(completeness.identity.encoded),
		)
		query.compare(listOf(request)) shouldContainExactly
			listOf(StepsCountDomainCompatibilityResult.Unverifiable)
	}

	@Test
	fun `native session and imported Ambient evidence compare in separate origin namespaces`() =
		runTest {
			val sessionFact = insertEvidence(
				StepsCountDomainOwnerKind.SESSION_FACT,
				'1',
				tokenDigit = 'a',
			)
			val completeness = insertEvidence(
				StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
				'2',
				tokenDigit = 'a',
			)
			val ambient = insertImportedAmbientEvidence('3', tokenDigit = 'a')

			query.compare(
				listOf(
					StepsCountDomainCompatibilityRequest(
						listOf(sessionFact, completeness),
						listOf(ambient),
					),
				),
			) shouldContainExactly listOf(StepsCountDomainCompatibilityResult.ExactCompatible)
		}

	@Test
	fun `malformed persisted owner effect is unverifiable`() = runTest {
		val sessionFact = insertEvidence(StepsCountDomainOwnerKind.SESSION_FACT, '4', 'a')
		val completeness =
			insertEvidence(StepsCountDomainOwnerKind.SESSION_COMPLETENESS, '5', 'a')
		val ambient = insertEvidence(StepsCountDomainOwnerKind.AMBIENT_FACT, '6', 'a')
		database.openHelper.writableDatabase.execSQL(
			"UPDATE steps_count_domain_owner_revision SET owner_effect_checksum = ? " +
				"WHERE owner_identity = ?",
			arrayOf("f".repeat(64), ambient.identity.encoded),
		)

		query.compare(
			listOf(
				StepsCountDomainCompatibilityRequest(
					listOf(sessionFact, completeness),
					listOf(ambient),
				),
			),
		) shouldContainExactly listOf(StepsCountDomainCompatibilityResult.Unverifiable)
	}

	@Test
	fun `incompatible schema is unverifiable rather than absent`() = runTest {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE steps_count_domain_schema_marker SET contract_version = 1 WHERE id = 1",
		)

		query.compare(
			listOf(StepsCountDomainCompatibilityRequest(emptyList(), emptyList())),
		) shouldContainExactly listOf(StepsCountDomainCompatibilityResult.Unverifiable)
	}

	@Test
	fun `current tokenless Ambient correction resolves unproven and authenticates its effect`() =
		runTest {
			val sessionFact = insertEvidence(StepsCountDomainOwnerKind.SESSION_FACT, '4', 'a')
			val completeness =
				insertEvidence(StepsCountDomainOwnerKind.SESSION_COMPLETENESS, '5', 'a')
			val ownerIdentity = opaque('7')
			val scopeIdentity = opaque('8')
			val sqlite = database.openHelper.writableDatabase
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES (?, ?, ?, 1, " +
					"'UNPROVEN', NULL, ?, 1)",
				arrayOf(
					StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
					scopeIdentity,
					ownerIdentity,
					"a".repeat(64),
				),
			)
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES (?, ?, ?, 2, " +
					"'UNPROVEN', NULL, ?, 2)",
				arrayOf(
					StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
					scopeIdentity,
					ownerIdentity,
					"b".repeat(64),
				),
			)
			val current = StepsCountDomainOwnerReference(
				StepsCountDomainOwnerKind.AMBIENT_FACT,
				StepsCountDomainOwnerIdentity.opaque(ownerIdentity),
				2L,
				StepsCountDomainOwnerEffect.opaque("b".repeat(64)),
			)
			val request = StepsCountDomainCompatibilityRequest(
				listOf(sessionFact, completeness),
				listOf(current),
			)

			query.compare(listOf(request)) shouldContainExactly
				listOf(StepsCountDomainCompatibilityResult.Unproven)
			query.compare(
				listOf(
					StepsCountDomainCompatibilityRequest(
						request.sessionOwners,
						listOf(
							StepsCountDomainOwnerReference(
								current.kind,
								current.identity,
								current.revision,
								StepsCountDomainOwnerEffect.opaque("c".repeat(64)),
							),
						),
					),
				),
			) shouldContainExactly listOf(StepsCountDomainCompatibilityResult.Unverifiable)
		}

	@Test
	fun `aggregate owner chunks preserve every request result and reject request-count overflow`() =
		runTest {
			val requests = List(MAX_STEPS_COUNT_DOMAIN_REQUESTS) { requestIndex ->
				val session = buildList {
					add(reference(StepsCountDomainOwnerKind.SESSION_COMPLETENESS, requestIndex, 0))
					repeat(8) { ownerIndex ->
						add(
							reference(
								StepsCountDomainOwnerKind.SESSION_FACT,
								requestIndex,
								ownerIndex + 1,
							),
						)
					}
				}
				StepsCountDomainCompatibilityRequest(
					session,
					listOf(reference(StepsCountDomainOwnerKind.AMBIENT_FACT, requestIndex, 9)),
				)
			}
			query.compare(requests).all {
				it == StepsCountDomainCompatibilityResult.Unproven
			} shouldBe true

			val overCount = List(MAX_STEPS_COUNT_DOMAIN_REQUESTS + 1) {
				StepsCountDomainCompatibilityRequest(emptyList(), emptyList())
			}
			query.compare(overCount).all {
				it == StepsCountDomainCompatibilityResult.Unverifiable
			} shouldBe true

			val ownerOverflow = StepsCountDomainCompatibilityRequest(
				List(257) { index ->
					reference(StepsCountDomainOwnerKind.SESSION_FACT, index, 0)
				},
				emptyList(),
			)
			query.compare(
				listOf(
					ownerOverflow,
					StepsCountDomainCompatibilityRequest(emptyList(), emptyList()),
				),
			) shouldContainExactly listOf(
				StepsCountDomainCompatibilityResult.Unverifiable,
				StepsCountDomainCompatibilityResult.Unproven,
			)
		}

	private fun insertEvidence(
		kind: StepsCountDomainOwnerKind,
		identityDigit: Char,
		tokenDigit: Char,
		registrationGeneration: Long = 1L,
	): StepsCountDomainOwnerReference {
		val ownerKind = kind.storedCode()
		val ownerIdentity = opaque(identityDigit)
		val scopeIdentity = if (kind == StepsCountDomainOwnerKind.AMBIENT_FACT) {
			ownerIdentity
		} else {
			opaque('e')
		}

		val effect = identityDigit.toString().repeat(64)
		val marker = if (kind == StepsCountDomainOwnerKind.SESSION_COMPLETENESS) {
			marker(ownerIdentity)
		} else {
			null
		}
		val coverage = when (kind) {
			StepsCountDomainOwnerKind.SESSION_FACT ->
				StepsCountDomainReceiptEntity.COVERAGE_COVERED
			StepsCountDomainOwnerKind.SESSION_COMPLETENESS ->
				StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN
			StepsCountDomainOwnerKind.AMBIENT_FACT ->
				StepsCountDomainReceiptEntity.COVERAGE_AMBIENT_AGGREGATE
		}
		val domainIdentity = StepsCountDomainReceiptIntegrity.counterDomainIdentity(
			StepsCounterDomainToken.opaque(opaque(tokenDigit)),
		)
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = 7L,
			authorityRevision = registrationGeneration,
			authorityFingerprint = "a".repeat(64),
			coverageKind = coverage,
			coverageVersion = 1,
			countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effectChecksum = effect,
			completionEvidenceChecksum = marker?.evidenceChecksum,
		)
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"INSERT INTO steps_count_domain_receipt VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			arrayOf(
				receiptIdentity,
				domainIdentity,
				ownerKind,
				scopeIdentity,
				ownerIdentity,
				1L,
				registrationGeneration,
				7L,
				registrationGeneration,
				"a".repeat(64),
				coverage,
				1,
				StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
				effect,
				marker?.evidenceChecksum,
			),
		)
		sqlite.execSQL(
			"INSERT INTO steps_count_domain_owner_revision VALUES (?, ?, ?, ?, 'BIND', ?, ?, 1)",
			arrayOf(ownerKind, scopeIdentity, ownerIdentity, 1L, receiptIdentity, effect),
		)
		marker?.let {
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_completeness_marker VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				arrayOf(
					it.ownerKind,
					it.ownerIdentity,
					it.ownerRevision,
					it.terminalState,
					it.lastAdmissionOrdinal,
					it.lastSourceSequence,
					it.providerFlushOutcome,
					it.registrationRemovalOutcome,
					it.registrationTimelineChecksum,
					it.evidenceChecksum,
				),
			)
		}
		return StepsCountDomainOwnerReference(
			kind,
			StepsCountDomainOwnerIdentity.opaque(ownerIdentity),
			1L,
			StepsCountDomainOwnerEffect.opaque(effect),
		)
	}

	private suspend fun insertImportedAmbientEvidence(
		identityDigit: Char,
		tokenDigit: Char,
	): StepsCountDomainOwnerReference {
		val ownerIdentity = PortableCountDomainOpaqueIdentity(opaque(identityDigit))
		val scopeIdentity = PortableCountDomainOpaqueIdentity(opaque('8'))
		val effect = PortableCountDomainDigest(identityDigit.toString().repeat(64))
		val fact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				"compat-$identityDigit",
			),
			0L,
			86_400_000L,
			1L,
		)
		val day = PortableAmbientStepsDayV1.create(
			identity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.DAY,
				"compat-day-$identityDigit",
			),
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = 86_400_000L,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			partialCauses = emptyList(),
			retainedStepCount = 1L,
			facts = listOf(fact),
			gaps = emptyList(),
		)
		val receipt = PortableCountDomainReceiptV2.create(
			domainIdentity = PortableCountDomainOpaqueIdentity(
				StepsCountDomainReceiptIntegrity.counterDomainIdentity(
					StepsCounterDomainToken.opaque(opaque(tokenDigit)),
				),
			),
			ownerKind = PortableOwnerKind.AMBIENT_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			registrationGeneration = 9L,
			collectedDataEpoch = 7L,
			authorityRevision = 4L,
			authorityFingerprint = PortableCountDomainDigest("a".repeat(64)),
			coverage = PortableCountDomainCoverage.AMBIENT_AGGREGATE,
			coverageVersion = 1,
			countDomainVersion = 1,
			effectChecksum = effect,
			completenessEvidenceChecksum = null,
		)
		val graph = PortableCountDomainGraphV2.create(
			receipts = listOf(receipt),
			ownerRevisions = listOf(
				PortableCountDomainOwnerRevisionV2(
					ownerKind = PortableOwnerKind.AMBIENT_FACT,
					scopeIdentity = scopeIdentity,
					ownerIdentity = ownerIdentity,
					ownerRevision = 1L,
					operation = PortableCountDomainOperation.BIND,
					receiptIdentity = receipt.identity,
					ownerEffectChecksum = effect,
					linkedAtMs = 1L,
				),
			),
			completenessMarkers = emptyList(),
			roots = listOf(
				PortableCountDomainRootV2(
					containerIdentity = PortableCountDomainOpaqueIdentity(day.identity.value),
					productIdentity = PortableCountDomainOpaqueIdentity(fact.identity.value),
					ownerKind = PortableOwnerKind.AMBIENT_FACT,
					ownerIdentity = ownerIdentity,
					ownerRevision = 1L,
				),
			),
		)
		val archive = PortableAmbientStepsArchiveV2.create(
			listOf(PortableAmbientStepsDayV2(day, graph)),
		)
		check(
			RoomImportPortableAmbientSteps(
				database,
				database.importedAmbientStepsDao(),
				Dispatchers.Unconfined,
			).importArchive(
				ImportPortableAmbientStepsV2Request(
					archive = archive,
					receipt = PortableAmbientStepsImportReceipt(
						"compat-job-$identityDigit",
						"compat-entry-$identityDigit",
						"compat.trackerambientsteps",
						86_400_000L,
					),
					metadata = PortableAmbientStepsImportMetadataV2(
						encodedByteCount = 1L,
						archiveContentChecksum = archive.contentChecksum,
						dayCount = 1,
						factCount = 1,
						gapCount = 0,
						receiptCount = 1,
						ownerRevisionCount = 1,
						rootCount = 1,
					),
					expectedCollectedDataEpoch = 7L,
				),
			) is ImportPortableAmbientStepsResult.Applied,
		)
		return StepsCountDomainOwnerReference(
			kind = StepsCountDomainOwnerKind.AMBIENT_FACT,
			identity = StepsCountDomainOwnerIdentity.opaque(ownerIdentity.value),
			revision = 1L,
			effect = StepsCountDomainOwnerEffect.opaque(effect.value),
			origin = StepsCountDomainOwnerOrigin.IMPORTED_PORTABLE,
		)
	}

	private fun marker(ownerIdentity: String): StepsCountDomainCompletenessMarkerEntity {
		val timeline = "d".repeat(64)
		val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE,
			lastAdmissionOrdinal = 1L,
			lastSourceSequence = 1L,
			providerFlushOutcome = "COMPLETE",
			registrationRemovalOutcome = "REMOVED",
			registrationTimelineChecksum = timeline,
		)
		return StepsCountDomainCompletenessMarkerEntity(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE,
			lastAdmissionOrdinal = 1L,
			lastSourceSequence = 1L,
			providerFlushOutcome = "COMPLETE",
			registrationRemovalOutcome = "REMOVED",
			registrationTimelineChecksum = timeline,
			evidenceChecksum = checksum,
		)
	}

	private fun reference(
		kind: StepsCountDomainOwnerKind,
		requestIndex: Int,
		ownerIndex: Int,
	): StepsCountDomainOwnerReference {
		val digest = (requestIndex * 16 + ownerIndex).toString(16).padStart(64, '0').takeLast(64)
		return StepsCountDomainOwnerReference(
			kind,
			StepsCountDomainOwnerIdentity.opaque("sha256:$digest"),
			1L,
			StepsCountDomainOwnerEffect.opaque(digest),
		)
	}

	private fun StepsCountDomainOwnerKind.storedCode(): String = when (this) {
		StepsCountDomainOwnerKind.SESSION_FACT ->
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT
		StepsCountDomainOwnerKind.SESSION_COMPLETENESS ->
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
		StepsCountDomainOwnerKind.AMBIENT_FACT ->
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT
	}

	private fun opaque(digit: Char) = "sha256:${digit.toString().repeat(64)}"
}
