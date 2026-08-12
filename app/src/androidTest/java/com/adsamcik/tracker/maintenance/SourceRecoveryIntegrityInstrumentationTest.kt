package com.adsamcik.tracker.maintenance

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
		val missing = database.sourceEventWalDao().locationEventsMissingCanonicalObservation(
			sourceKind = SourceKind.LOCATION.stableCode,
			afterOrdinal = 0L,
			limit = 1,
		)

		Log.i(
			TAG,
			"sourceEvents=$sourceEvents observations=$observations " +
				"pending=$pending quarantined=$quarantined missingLocationCanonical=${missing.size}",
		)
		assertEquals("pending persistence WAL must drain after recovery", 0, pending)
		assertEquals("every retained location WAL event needs a canonical raw observation", 0, missing.size)
	}

	private companion object {
		const val TAG = "SourceRecoveryCheck"
	}
}
