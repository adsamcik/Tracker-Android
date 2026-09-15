package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Source-preserving full-clear contract for parent-owned AppDatabase integration. */
@RunWith(RobolectricTestRunner::class)
class AmbientRadioFullClearTest {
	private lateinit var database: AppDatabase

	@BeforeTest
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `source clear preserves exact and rehashed replay footprints`() = runTest {
		val archiveId = digest("archive")
		val factId = digest("fact")
		val portableEffect = digest("portable-effect")
		database.ambientWifiFactDao().insertAuthority(wifiAuthority())
		database.ambientCellFactDao().insertAuthority(cellAuthority())
		database.ambientWifiFactDao().insertImportedFact(
			ImportedAmbientWifiFactEntity(
				archiveId = archiveId,
				factId = factId,
				semanticRevision = 1L,
				supersedesSemanticRevision = null,
				contentChecksum = digest("content"),
				portableEffectChecksum = portableEffect,
				portableOrigin = "PORTABLE_IMPORT",
				coverageStartTimeMs = 1L,
				observedTimeMs = 2L,
				latestPossibleTimeMs = 3L,
				storedZoneId = "UTC",
				structuralEpochDay = 0L,
				coverageCompleteness = "UNVERIFIABLE",
				observationCount = 1,
				twoPointFourGhzCount = 1,
				fiveGhzCount = 0,
				sixGhzCount = 0,
				otherBandCount = 0,
				strongestSignalDbm = -50,
				weakestSignalDbm = -50,
				meanSignalDbm = -50.0,
				retentionPolicyId = "privacy:wifi:import:v1",
				retentionApprovalRevision = 1L,
				collectedDataEpoch = 4L,
				importDeletionGeneration = 0L,
				receivedAtMs = 4L,
			),
		)

		database.clearAmbientWifiProductPreservingReplayFootprints(4L, 10L)
		database.clearAmbientCellProductPreservingReplayFootprints(4L, 10L)

		assertNull(database.ambientWifiFactDao().latestAuthority())
		assertNull(database.ambientCellFactDao().latestAuthority())
		assertEquals(0L, database.ambientWifiFactDao().importedFactCount())
		assertNotNull(database.ambientWifiFactDao().replayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_FACT_IDENTITY,
			factId,
			1L,
		))
		assertNotNull(database.ambientWifiFactDao().replayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_FACT_EFFECT,
			portableEffect,
			0L,
		))
		assertNull(database.ambientWifiFactDao().replayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_FACT_EFFECT,
			digest("unrelated-effect"),
			0L,
		))
	}

	private fun wifiAuthority() = AmbientWifiAuthorityIntegrity.create(
		1L,
		AmbientWifiAuthorityEntity.STATE_ACTIVE,
		1L,
		1L,
		"privacy:wifi:ambient:v1",
		1L,
		4L,
		0L,
		"boot-1",
		1L,
		1L,
		1L,
		"wifi-owner",
		1L,
		"wifi-demand",
	)

	private fun cellAuthority() = AmbientCellAuthorityIntegrity.create(
		1L,
		AmbientCellAuthorityEntity.STATE_ACTIVE,
		1L,
		1L,
		"privacy:cell:ambient:v1",
		1L,
		4L,
		0L,
		"boot-1",
		1L,
		1L,
		1L,
		"cell-owner",
		1L,
		"cell-demand",
	)

	private fun digest(value: String) = AmbientWifiAuthorityIntegrity.digest("test", value)
}
