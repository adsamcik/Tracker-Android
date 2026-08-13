package com.adsamcik.tracker.tracker.failure

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import io.kotest.matchers.shouldBe
import java.io.IOException
import org.junit.jupiter.api.Test

class TrackingOperationalFailureTest {
	@Test
	fun `I O and SQLite availability failures are operational`() {
		IOException("write failed").isTrackingOperationalFailure() shouldBe true
		SQLiteDatabaseLockedException("locked").isTrackingOperationalFailure() shouldBe true
		RuntimeException("Room wrapper", SQLiteException("disk unavailable"))
			.isTrackingOperationalFailure() shouldBe true
	}

	@Test
	fun `constraint and programmer failures are not operational`() {
		SQLiteConstraintException("invalid row").isTrackingOperationalFailure() shouldBe false
		IllegalArgumentException("invalid request").isTrackingOperationalFailure() shouldBe false
		IllegalStateException("broken invariant").isTrackingOperationalFailure() shouldBe false
		LinkageError("missing symbol").isTrackingOperationalFailure() shouldBe false
	}
}
