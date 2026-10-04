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
}
