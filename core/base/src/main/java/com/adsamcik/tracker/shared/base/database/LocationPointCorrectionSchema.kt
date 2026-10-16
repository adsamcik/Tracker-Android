package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.EFFECTIVE_LOCATION_SAMPLE_QUERY

internal fun createLocationPointCorrectionSchema(db: SupportSQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS location_point_correction (
        sample_id INTEGER NOT NULL PRIMARY KEY, collected_data_epoch INTEGER NOT NULL, revision INTEGER NOT NULL,
        time_ms INTEGER NOT NULL, lat_e7 INTEGER, lon_e7 INTEGER, alt_m REAL, raw_gps_alt_m REAL,
        h_acc_m REAL, v_acc_m REAL, speed_mps REAL, speed_accuracy_mps REAL,
        raw_platform_speed_mps REAL, raw_platform_speed_accuracy_mps REAL, bearing_deg REAL, bearing_accuracy_deg REAL,
        provider TEXT NOT NULL, quality TEXT NOT NULL, motion_state TEXT, permission_precision TEXT NOT NULL,
        is_mock INTEGER NOT NULL, alt_datum TEXT NOT NULL, alt_source TEXT NOT NULL,
        alt_conversion_status TEXT NOT NULL, raw_gps_alt_datum TEXT NOT NULL,
        FOREIGN KEY(sample_id) REFERENCES location_sample(id) ON UPDATE NO ACTION ON DELETE CASCADE
    )""")
    db.execSQL(("CREATE INDEX IF NOT EXISTS idx_location_correction_time ON " +
        "location_point_correction(time_ms, sample_id)"))
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_location_correction_coords ON location_point_correction(lat_e7, lon_e7)")
    // The migration drops the previous view first. Keep the SQL identical to Room's view contract.
    db.execSQL("CREATE VIEW `effective_location_sample` AS $EFFECTIVE_LOCATION_SAMPLE_QUERY")
}
