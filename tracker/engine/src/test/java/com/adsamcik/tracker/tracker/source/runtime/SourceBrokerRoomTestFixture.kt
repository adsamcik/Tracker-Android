package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before

/** Shared Room setup; each concrete contract still receives a fresh database for every test. */
abstract class SourceBrokerRoomTestFixture {
	protected lateinit var database: AppDatabase
	protected lateinit var subject: SourceBroker
	protected lateinit var rolloutStore: RoomTrackingRolloutStateStore
	internal lateinit var retentionReader: TestLiveAmbientRetentionAuthorityReader
	protected var elapsed = 10L

	@Before
	fun setUp() {
		resetBroker(setOf(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE))
	}

	@After
	fun tearDown() = database.close()

	protected fun binding(
		source: SourceKind,
		purpose: String,
		consentEpoch: Long,
		persistenceEligible: Boolean,
	): SessionManifestSourceEntity {
		val writer = when {
			source == SourceKind.STEPS && isCaptured(purpose, persistenceEligible) -> STEPS_TEST_WRITER
			source == SourceKind.PRESSURE && isCaptured(purpose, persistenceEligible) -> PRESSURE_TEST_WRITER
			else -> null
		}
		return SessionManifestSourceEntity(
			logicalTrackingId = "session-1",
			manifestRevision = 4L,
			sourceKind = source.stableCode,
			purpose = purpose,
			consentEpoch = consentEpoch,
			persistenceEligible = persistenceEligible,
			qosCode = 2,
			outputDestination = writer?.destination,
			writerOwner = writer?.owner,
			writerOwnerGeneration = writer?.ownerGeneration,
			writerProjectionId = writer?.projectionId,
			writerProjectionVersion = writer?.projectionVersion,
			writerBindingGeneration = writer?.bindingGeneration,
		)
	}

	protected fun resetBroker(captureModes: Set<CaptureReachabilityMode>) {
		if (::database.isInitialized) {
			database.close()
		}
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		rolloutStore = runBlocking {
			database.sourceEvidenceStateDao().ensure(
				SourceEvidenceState(collectedDataEpoch = 3L, updatedAtMs = 1L),
			)
			activateAllBrokerTestProductLanes(database, captureModes)
		}
		retentionReader = TestLiveAmbientRetentionAuthorityReader()
		subject = SourceBroker(
			database,
			rolloutStore,
			retentionReader,
			FixedClock(100L, 100L),
		)
	}
}

private fun isCaptured(purpose: String, persistenceEligible: Boolean): Boolean =
	purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible

private data class BrokerTestWriter(
	val destination: String,
	val owner: String,
	val ownerGeneration: Long,
	val projectionId: String? = null,
	val projectionVersion: Int? = null,
	val bindingGeneration: Long? = null,
)

private val STEPS_TEST_WRITER = ExecutableSourceLaneCatalog.PREFERRED_STEPS_SESSION_FACTS.let { binding ->
	BrokerTestWriter(
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		owner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		ownerGeneration = SourceWriterGenerationContract.canonicalOwnerGeneration(binding.bindingGeneration),
		projectionId = binding.projectionId,
		projectionVersion = binding.projectionVersion,
		bindingGeneration = binding.bindingGeneration,
	)
}

private val PRESSURE_TEST_WRITER = ExecutableSourceLaneCatalog.PREFERRED_PRESSURE_SESSION_FACTS.let { binding ->
	BrokerTestWriter(
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		owner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		ownerGeneration = SourceWriterGenerationContract.canonicalOwnerGeneration(binding.bindingGeneration),
		projectionId = binding.projectionId,
		projectionVersion = binding.projectionVersion,
		bindingGeneration = binding.bindingGeneration,
	)
}

// Keep the closed six-source manual/automatic lane and destination-owner fixture table together.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private suspend fun activateAllBrokerTestProductLanes(
	database: AppDatabase,
	captureModes: Set<CaptureReachabilityMode>,
): RoomTrackingRolloutStateStore {
	val automatic = CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE in captureModes
	val bindings = SourceKind.entries.map { source ->
		when (source) {
			SourceKind.LOCATION -> if (automatic) {
				ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V2
			}
				else {
				ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V1
			}
			SourceKind.STEPS -> if (automatic) {
				ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V2
			}
				else {
				ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1
			}
			SourceKind.ACTIVITY -> if (automatic) {
				ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS_V2
			}
				else {
				ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS_V1
			}
			SourceKind.PRESSURE -> if (automatic) {
				ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS_V2
			}
				else {
				ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS_V1
			}
			SourceKind.WIFI -> if (automatic) {
				ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS_V2
			}
				else {
				ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS_V1
			}
			SourceKind.CELL -> if (automatic) {
				ExecutableSourceLaneCatalog.CELL_SESSION_FACTS_V2
			}
				else {
				ExecutableSourceLaneCatalog.CELL_SESSION_FACTS_V1
			}
		}
	}
	bindings.forEach { binding ->
		val (destination, owner) = when (binding.source) {
			SourceKind.LOCATION -> SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION to
				SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE
			SourceKind.STEPS -> SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS to
				SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
			SourceKind.ACTIVITY -> SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY to
				SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS
			SourceKind.PRESSURE -> SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE to
				SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS
			SourceKind.WIFI -> SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI to
				SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS
			SourceKind.CELL -> SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL to
				SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS
		}
		database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(
			sourceKind = binding.source.stableCode,
			destination = destination,
			owner = owner,
			ownerGeneration = if (binding.source == SourceKind.LOCATION) {
				SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION
			} else {
				SourceWriterGenerationContract.canonicalOwnerGeneration(binding.bindingGeneration)
			},
			updatedAtMs = 1L,
		))
	}
	return installCanonicalProductLanesForTest(
		database = database,
		bindings = bindings,
		rolloutRevision = bindings.size.toLong(),
	)
}
