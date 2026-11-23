package com.adsamcik.tracker.maintenance

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only integrity checks intended to run against both clean and retained emulator installs. */
@RunWith(AndroidJUnit4::class)
class SourceRecoveryIntegrityInstrumentationTest {
	@Test
	fun location_source_wal_has_canonical_observations_and_no_pending_backlog() = runBlocking {
		val context: Context = ApplicationProvider.getApplicationContext()
		val database = AppDatabase.database(context)
		val pending = database.pendingSignalDao().countAll()
		val quarantined = database.quarantinedSignalDao().countAll()
		val sourceEvents = database.sourceEventWalDao().countAll()
		val observations = database.locationObservationDao().countAll()
		val openRuns = database.trackerRunDao().countOpenRuns()
		val hasLiveStats = database.liveStatsDao().get() != null
		val hasMissing = database.openHelper.readableDatabase.query(
			"SELECT 1 FROM source_event_wal AS wal LEFT JOIN location_observation AS observation " +
				"ON observation.source_event_id = wal.event_id " +
				"WHERE wal.source_kind = ? AND observation.id IS NULL LIMIT 1",
			arrayOf<Any>(SourceKind.LOCATION.stableCode),
		).use { it.moveToFirst() }

		Log.i(
			TAG,
			"sourceEvents=$sourceEvents observations=$observations " +
				"pending=$pending quarantined=$quarantined missingLocationCanonical=$hasMissing " +
				"openRuns=$openRuns hasLiveStats=$hasLiveStats",
		)
		assertEquals("pending persistence WAL must drain after recovery", 0, pending)
		assertEquals("every retained location WAL event needs a canonical raw observation", false, hasMissing)
		assertTrue("at most one tracker run may remain open", openRuns <= 1)
	}

	private companion object {
		const val TAG = "SourceRecoveryCheck"
	}
}
