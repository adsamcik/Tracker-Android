package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativePortableOwnershipSchemaTest {
	@Test
	fun additiveLookupDDLMatchesFreshRoomAndKeepsExistingRowsWhenRepeated() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val room = AppDatabase.testDatabase(context)
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
				.callback(object : SupportSQLiteOpenHelper.Callback(1) {
					override fun onCreate(db: SupportSQLiteDatabase) = createNativePortableOwnershipTable(db)
					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No fixture upgrade")
				}).build(),
		)
		try {
			val db = helper.writableDatabase
			TableInfo.read(db, "native_portable_ownership") shouldBe
				TableInfo.read(room.openHelper.writableDatabase, "native_portable_ownership")
			db.execSQL("INSERT INTO native_portable_ownership VALUES (2, 'LOGICAL_ENTRY', ?, 'logical', 'logical', 'run')", arrayOf("a".repeat(64)))
			createNativePortableOwnershipTable(db)
			db.query("SELECT raw_identity FROM native_portable_ownership").use {
				it.moveToFirst() shouldBe true
				it.getString(0) shouldBe "logical"
				it.moveToNext() shouldBe false
			}
		} finally {
			helper.close()
			room.close()
		}
	}

	@Test
	fun rebuiltPressureWindowOwnershipUsesTheExistingWriterQualifiedPortableIdentity() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
				.callback(object : SupportSQLiteOpenHelper.Callback(1) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						createNativePortableOwnershipTable(db)
						db.execSQL("CREATE TABLE source_service_run (logical_tracking_id TEXT, service_run_id TEXT)")
						for (table in listOf("activity_captured_window_revision", "pressure_fact_revision")) {
							val window = if (table.startsWith("activity")) {
								"logical_window_id"
							} else {
								"logical_fact_id"
							}
							db.execSQL("CREATE TABLE $table (logical_tracking_id TEXT, service_run_id TEXT, $window TEXT, " +
								"writer_projection_id TEXT, writer_projection_version INTEGER)")
						}
					}
					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No fixture upgrade")
				}).build(),
		)
		try {
			val db = helper.writableDatabase
			db.execSQL("INSERT INTO pressure_fact_revision VALUES ('logical', 'run', 'fact', 'writer', 7)")
			// The former lookup hashed only the fact ID and must be replaced during upgrade.
			db.execSQL("INSERT INTO native_portable_ownership VALUES (4, 'WINDOW', ?, 'fact', 'logical', 'run')",
				arrayOf("a".repeat(64)))
			rebuildNativePortableOwnership(db)
			val expected = com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureOpaqueIdentity.derive(
				com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureIdentityKind.WINDOW,
				"6:writer:7:4:fact").value
			db.query("SELECT opaque_identity, raw_identity FROM native_portable_ownership WHERE identity_kind = 'WINDOW'").use {
				it.moveToFirst() shouldBe true
				it.getString(0) shouldBe expected
				it.getString(1) shouldBe "fact"
				it.moveToNext() shouldBe false
			}
		} finally {
			helper.close()
		}
	}
}
