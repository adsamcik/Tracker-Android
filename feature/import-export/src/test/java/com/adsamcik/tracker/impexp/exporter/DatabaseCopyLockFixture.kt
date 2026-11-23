package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File

/** Host fixtures use the same real framework engine as their concurrent writers, never a no-op lock. */
internal fun openFrameworkBackupDatabase(file: File): SupportSQLiteDatabase {
    val (version, wal) = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        database.version to database.rawQuery("PRAGMA journal_mode", null).use { row ->
            check(row.moveToFirst())
            row.getString(0) == "wal"
        }
    }
    require(version > 0)
    val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration
        .builder(ApplicationProvider.getApplicationContext<Context>()).name(file.path)
        .callback(object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = error("The fixture database must already exist")
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                error("The copy lock must not upgrade fixture data")
        }).build())
    helper.setWriteAheadLoggingEnabled(wal)
    return object : SupportSQLiteDatabase by helper.writableDatabase {
        override fun close() = helper.close()
    }
}
