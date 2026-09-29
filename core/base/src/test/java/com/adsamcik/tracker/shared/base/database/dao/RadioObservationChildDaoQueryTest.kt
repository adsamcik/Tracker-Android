package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioObservationChildDaoQueryTest {
	@Test
	fun `bulk Wi-Fi readers return latest purpose-owned children within their bounds`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
		try {
			// This query test seeds only the cursor keys. Production writes retain all parent FKs.
			val sqlite = database.openHelper.writableDatabase
			sqlite.execSQL("PRAGMA foreign_keys=OFF")
			insertSessionCursor(sqlite, "fact-a", "session-a", 2L)
			insertSessionCursor(sqlite, "fact-b", "session-b", 1L)
			insertBackgroundCursor(sqlite, "background-a", 2L)
			val dao = database.radioObservationChildDao()
			dao.insertWifiSession(listOf(
				sessionChild("fact-a", 1L, "old"),
				sessionChild("fact-a", 2L, "current"),
				sessionChild("fact-b", 1L, "other-session"),
			))
			dao.insertWifiBackground(listOf(
				backgroundChild(1L, "old-background"),
				backgroundChild(2L, "current-background"),
			))

			dao.wifiCurrentSessionChildrenByFactIds(SESSION_WRITER, SESSION_VERSION,
				listOf("fact-a", "fact-b"), 3).map(WifiCapturedChildEntity::ssid) shouldBe
				listOf("current", "other-session")
			dao.wifiCurrentSessionChildrenForLogicalTracking("session-a", 2)
				.map(WifiCapturedChildEntity::ssid) shouldBe listOf("current")
			dao.wifiCurrentBackgroundChildrenForFactIds(listOf("background-a"), 2)
				.map(AmbientWifiChildEntity::ssid) shouldBe listOf("current-background")
			dao.wifiCurrentSessionChildrenByFactIds(SESSION_WRITER, SESSION_VERSION,
				listOf("fact-a", "fact-b"), 1).map(WifiCapturedChildEntity::ssid) shouldBe
				listOf("current")
		} finally {
			database.close()
		}
	}

	@Test
	fun `exact background revision readers do not let earlier corrections consume the limit`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
		try {
			// The query contract is tested independently of parent admission and its FK fixtures.
			database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=OFF")
			val local = database.radioObservationChildDao()
			local.insertWifiBackground(listOf(
				backgroundChild(1L, "old"), backgroundChild(2L, "selected"),
			))
			local.insertCellBackground(listOf(
				cellBackgroundChild(1L, "old"), cellBackgroundChild(2L, "selected"),
			))
			local.wifiBackgroundChildrenForRevision("background-a", 2L, 1)
				.map(AmbientWifiChildEntity::ssid) shouldBe listOf("selected")
			local.cellBackgroundChildrenForRevision("background-cell", 2L, 1)
				.map(AmbientCellChildEntity::towerIdentity) shouldBe listOf("selected")

			val imported = database.importedRadioChildDao()
			imported.insertWifiBackground(listOf(
				importedWifiChild(1L, "old"), importedWifiChild(2L, "selected"),
			))
			imported.insertCellBackground(listOf(
				importedCellChild(1L, "old"), importedCellChild(2L, "selected"),
			))
			imported.wifiBackgroundChildrenForRevision("imported-wifi", 2L, 1)
				.map(ImportedAmbientWifiChildEntity::ssid) shouldBe listOf("selected")
			imported.cellBackgroundChildrenForRevision("imported-cell", 2L, 1)
				.map(ImportedAmbientCellChildEntity::towerIdentity) shouldBe listOf("selected")
		} finally {
			database.close()
		}
	}

	private fun insertSessionCursor(
		sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
		factId: String,
		trackingId: String,
		latestRevision: Long,
	) {
		sqlite.execSQL(
			"INSERT INTO wifi_captured_fact_cursor " +
				"(writer_projection_id,writer_projection_version,logical_fact_id,logical_tracking_id," +
				"service_run_id,session_segment_id,writer_owner_generation,collected_data_epoch," +
				"scope_deletion_generation,latest_semantic_revision,latest_mutation_id," +
				"latest_effect_checksum,latest_source_admission_ordinal,cursor_revision,updated_at_ms) " +
				"VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
			arrayOf(SESSION_WRITER, SESSION_VERSION, factId, trackingId, "run-a", 1L, 1L,
				0L, 0L, latestRevision, "mutation", CHECKSUM, 1L, 1L, 1_000L),
		)
	}

	private fun insertBackgroundCursor(
		sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
		factId: String,
		latestRevision: Long,
	) {
		sqlite.execSQL(
			"INSERT INTO ambient_wifi_fact_cursor " +
				"(writer_id,writer_version,logical_fact_id,latest_semantic_revision," +
				"latest_mutation_id,latest_effect_checksum,latest_source_admission_ordinal," +
				"collected_data_epoch,scope_deletion_generation,cursor_revision,updated_at_ms) " +
				"VALUES (?,?,?,?,?,?,?,?,?,?,?)",
			arrayOf(BACKGROUND_WRITER, BACKGROUND_VERSION, factId, latestRevision, CHECKSUM,
				CHECKSUM, 1L, 0L, 0L, 1L, 1_000L),
		)
	}

	private fun sessionChild(factId: String, revision: Long, name: String) =
		WifiCapturedChildEntity(SESSION_WRITER, SESSION_VERSION, factId, revision, 0,
			1_000L, 1_000L, 0L, "aa:bb:cc:dd:ee:ff", name, 2_412, -45, CHECKSUM)

	private fun backgroundChild(revision: Long, name: String) =
		AmbientWifiChildEntity(BACKGROUND_WRITER, BACKGROUND_VERSION, "background-a", revision,
			0, 1_000L, 1_000L, 0L, "aa:bb:cc:dd:ee:ff", name, 2_412, -45, CHECKSUM)

	private fun cellBackgroundChild(revision: Long, identity: String) =
		AmbientCellChildEntity(BACKGROUND_WRITER, BACKGROUND_VERSION, "background-cell", revision,
			0, 1_000L, 1_000L, 0L, identity, "LTE", true, -75, CHECKSUM)

	private fun importedWifiChild(revision: Long, name: String) =
		ImportedAmbientWifiChildEntity("archive-wifi", "imported-wifi", revision, 0,
			1_000L, 1_000L, 0L, "aa:bb:cc:dd:ee:ff", name, 2_412, -45, CHECKSUM)

	private fun importedCellChild(revision: Long, identity: String) =
		ImportedAmbientCellChildEntity("archive-cell", "imported-cell", revision, 0,
			1_000L, 1_000L, 0L, identity, "LTE", true, -75, CHECKSUM)

	private companion object {
		const val SESSION_WRITER = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
		const val SESSION_VERSION = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
		const val BACKGROUND_WRITER = AmbientWifiFactRevisionEntity.WRITER_ID
		const val BACKGROUND_VERSION = AmbientWifiFactRevisionEntity.WRITER_VERSION
		const val CHECKSUM = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	}
}
