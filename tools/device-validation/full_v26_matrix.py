#!/usr/bin/env python3
"""Build and verify the one-time packaged v26 -> v27 full-matrix check."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import xml.etree.ElementTree as ET
from pathlib import Path


IMPORTED_TABLES = (
    "activity",
    "location_sample",
    "step_interval",
    "activity_snapshot",
    "cell_sample",
    "wifi_observation",
    "session_segment",
    "daily_summary",
    "exploration_cell",
    "exploration_streak",
    "pressure_sample",
    "ski_run_segment",
)

SKIPPED_TABLES = (
    "network_operator",
    "tracker_run",
    "live_stats",
    "frequent_place",
    "inferred_trip",
    "trip_leg",
    "achievement_progress",
    "personal_record",
    "route_cache",
    "export_log",
    "storage_size_snapshot",
    "domain_event",
    "domain_event_cursor",
    "pending_signal",
)

RELEASE_TABLES = IMPORTED_TABLES + SKIPPED_TABLES

SEED_SQL = (
    "INSERT INTO activity(id,name,iconName) VALUES(9101,'Full matrix activity','walk')",
    """INSERT INTO location_sample(
        id,time_ms,elapsed_realtime_nanos,lat_e7,lon_e7,alt_m,raw_gps_alt_m,
        h_acc_m,v_acc_m,speed_mps,speed_accuracy_mps,provider,quality,motion_state,
        policy,bucket_id,created_at
    ) VALUES(9102,1700200000000,2000000000,500755000,144378000,222.5,221.0,
        4.0,6.0,1.5,0.5,'gps','HIGH','MOVING','FULL_MATRIX',7,1700200000100)""",
    """INSERT INTO step_interval(
        id,start_time_ms,end_time_ms,step_count,sensor_value_start,sensor_value_end,
        sensor_reset,created_at
    ) VALUES(9103,1700200000000,1700200060000,77,1000,1077,0,1700200060100)""",
    """INSERT INTO activity_snapshot(
        id,time_ms,activity_type,confidence,is_transition,created_at
    ) VALUES(9104,1700200001000,7,93,1,1700200001100)""",
    """INSERT INTO cell_sample(
        id,time_ms,cell_id,lac,mcc,mnc,network_type,signal_strength,lat_e7,lon_e7,
        provenance,created_at
    ) VALUES(9105,1700200002000,123456,321,230,1,13,-85,500755100,144378100,
        'DIRECT',1700200002100)""",
    """INSERT INTO wifi_observation(
        id,time_ms,bssid,ssid,capabilities,frequency,level,lat_e7,lon_e7,provenance,
        created_at
    ) VALUES(9106,1700200003000,'02:11:22:33:44:55','full-matrix','[WPA3]',5955,-42,
        500755200,144378200,'DIRECT',1700200003100)""",
    """INSERT INTO session_segment(
        id,start_time_ms,end_time_ms,distance_m,steps,primary_activity,
        activity_confidence,sample_count,source,inference_version,created_at,
        has_distance_anomaly
    ) VALUES(9107,1700200000000,1700200060000,123.5,77,7,93,4,
        'LEGACY_MIGRATION','full-matrix-v26',1700200060100,0)""",
    """INSERT INTO daily_summary(
        date_epoch_day,total_distance_m,total_steps,total_duration_ms,trip_count,
        active_tracking_ms,last_updated_ms,created_at
    ) VALUES(21000,123.5,77,60000,1,59000,1700200060200,1700200060200)""",
    """INSERT INTO exploration_cell(
        id,cell_token,level,quality,first_discovered_at,last_visited_at,visit_count,
        season_bitmask,center_lat_e7,center_lon_e7,created_at
    ) VALUES(9108,'full-matrix-cell',12,4,1700200000000,1700200060000,3,5,
        500755000,144378000,1700200060100)""",
    """INSERT INTO exploration_streak(
        type,current_count,best_count,last_increment_day,updated_at
    ) VALUES('FULL_MATRIX',3,5,21000,1700200060100)""",
    """INSERT INTO pressure_sample(
        id,time_ms,elapsed_realtime_nanos,pressure_hpa,altitude_m,bucket_id,created_at
    ) VALUES(9109,1700200004000,2004000000,1003.25,88.5,7,1700200004100)""",
    """INSERT INTO ski_run_segment(
        id,session_id,run_index,segment_type,start_time_ms,end_time_ms,vertical_m,
        distance_m,max_speed_mps,avg_speed_mps,lift_type,created_at
    ) VALUES(9110,9107,1,'DOWNHILL',1700200000000,1700200060000,45.0,350.0,
        18.0,8.0,NULL,1700200060100)""",
    "INSERT INTO network_operator(mcc,mnc,name) VALUES('230','01','Full Matrix Operator')",
    """INSERT INTO tracker_run(
        id,start_time_ms,end_time_ms,policy,policy_params,user_initiated,created_at
    ) VALUES(9201,1700200000000,1700200060000,'FULL_MATRIX','{}',1,1700200060100)""",
    """INSERT INTO live_stats(
        id,date_epoch_day,session_distance_m,session_steps,session_duration_ms,
        day_total_distance_m,day_total_steps,day_total_duration_ms,last_updated_ms
    ) VALUES(1,21000,123.5,77,60000,123.5,77,60000,1700200060200)""",
    """INSERT INTO frequent_place(
        id,center_lat_e7,center_lon_e7,radius_m,visit_count,first_visit_ms,last_visit_ms,
        auto_category,created_at
    ) VALUES(9202,500755000,144378000,25.0,2,1700100000000,1700200000000,
        'HOME',1700200000100)""",
    """INSERT INTO inferred_trip(
        id,segment_id,start_time_ms,end_time_ms,distance_m,steps,primary_activity,
        transport_mode,departure_place_id,arrival_place_id,source,inference_version,
        leg_count,created_at
    ) VALUES(9203,9107,1700200000000,1700200060000,123.5,77,7,'WALK',9202,9202,
        'BATCH_INFERENCE','full-matrix-v26',1,1700200060100)""",
    """INSERT INTO trip_leg(
        id,trip_id,sequence_index,start_time_ms,end_time_ms,distance_m,transport_mode,
        created_at
    ) VALUES(9204,9203,0,1700200000000,1700200060000,123.5,'WALK',1700200060100)""",
    """INSERT INTO achievement_progress(
        id,achievement_id,current_value,target_value,tier,unlocked_at,updated_at,notified_at
    ) VALUES(9205,'full-matrix-achievement',4,10,1,NULL,1700200060100,NULL)""",
    """INSERT INTO personal_record(
        id,metric,value,achieved_at,updated_at
    ) VALUES(9206,'full_matrix_distance',123.5,1700200060000,1700200060100)""",
    """INSERT INTO route_cache(
        id,session_id,segment_id,encoded_polyline,point_count,simplified_count,
        start_time,end_time,distance_meters,created_at
    ) VALUES(9207,9107,9107,'_p~iF~ps|U_ulLnnqC_mqNvxq`@',3,3,
        1700200000000,1700200060000,123.5,1700200060100)""",
    """INSERT INTO export_log(
        id,format,scope,file_name,file_size_bytes,record_count,started_at,completed_at,
        status,error_message,created_at
    ) VALUES(9208,'CSV','FULL','full-matrix.csv',1234,12,1700200000000,
        1700200060000,'SUCCESS',NULL,1700200060100)""",
    """INSERT INTO storage_size_snapshot(
        id,epoch_day,database_size_bytes,location_count,session_count,wifi_count,
        cell_count,exploration_cell_count,route_cache_count,created_at
    ) VALUES(9209,21000,123456,1,1,1,1,1,1,1700200060100)""",
    """INSERT INTO domain_event(
        id,event_type,processor_id,timestamp_ms,payload
    ) VALUES(9210,'FULL_MATRIX_EVENT','full-matrix',1700200060000,'{}')""",
    """INSERT INTO domain_event_cursor(
        consumer_id,last_processed_ms,last_processed_id
    ) VALUES('full-matrix-consumer',1700200060000,9210)""",
    """INSERT INTO pending_signal(
        id,session_id,signal_json,created_at
    ) VALUES(9211,9107,'{\"version\":1,\"fullMatrix\":true}',1700200060100)""",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def table_names(connection: sqlite3.Connection) -> set[str]:
    return {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }


def count(connection: sqlite3.Connection, table: str) -> int:
    return int(connection.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0])


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def validate_database(connection: sqlite3.Connection, expected_version: int) -> None:
    version = int(connection.execute("PRAGMA user_version").fetchone()[0])
    require(version == expected_version, f"Expected user_version {expected_version}, got {version}")
    quick_check = connection.execute("PRAGMA quick_check").fetchone()[0]
    require(quick_check == "ok", f"quick_check failed: {quick_check}")
    foreign_keys = list(connection.execute("PRAGMA foreign_key_check"))
    require(not foreign_keys, f"foreign_key_check failed: {foreign_keys[:5]}")


def build_fixture(schema_path: Path, output: Path, manifest_path: Path) -> None:
    require(schema_path.is_file(), f"Frozen v26 schema is missing: {schema_path}")
    require(not output.exists(), f"Refusing to overwrite fixture: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    schema = json.loads(schema_path.read_text(encoding="utf-8"))["database"]
    require(schema["version"] == 26, f"Expected frozen schema v26, got {schema['version']}")
    require(len(schema["entities"]) == len(RELEASE_TABLES), "Unexpected v26 entity count")

    connection = sqlite3.connect(output)
    try:
        connection.execute("BEGIN IMMEDIATE")
        for entity in schema["entities"]:
            table = entity["tableName"]
            connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity.get("indices", []):
                connection.execute(index["createSql"].replace("${TABLE_NAME}", table))
        for query in schema["setupQueries"]:
            connection.execute(query)
        connection.execute("CREATE TABLE android_metadata (locale TEXT)")
        connection.execute("INSERT INTO android_metadata(locale) VALUES('en_US')")
        connection.execute("PRAGMA user_version=26")
        connection.commit()
        validate_database(connection, 26)
        missing = set(RELEASE_TABLES) - table_names(connection)
        require(not missing, f"Frozen v26 schema is missing released tables: {sorted(missing)}")
        connection.execute("PRAGMA foreign_keys=OFF")
        connection.execute("BEGIN IMMEDIATE")
        for statement in SEED_SQL:
            connection.execute(statement)
        connection.commit()
        connection.execute("PRAGMA foreign_keys=ON")
        validate_database(connection, 26)
        for table in RELEASE_TABLES:
            require(count(connection, table) == 1, f"Expected one seeded row in {table}")
        connection.execute("VACUUM")
        journal_mode = connection.execute("PRAGMA journal_mode=DELETE").fetchone()[0]
        require(journal_mode.lower() == "delete", f"Expected DELETE journal, got {journal_mode}")
    finally:
        connection.close()

    connection = sqlite3.connect(f"file:{output}?mode=ro", uri=True)
    try:
        validate_database(connection, 26)
        counts = {table: count(connection, table) for table in RELEASE_TABLES}
    finally:
        connection.close()

    manifest = {
        "fixture": str(output),
        "schema": str(schema_path),
        "schema_identity_hash": schema["identityHash"],
        "source_version": 26,
        "source_size_bytes": output.stat().st_size,
        "source_sha256": sha256(output),
        "source_counts": counts,
        "expected_imported_counts": {
            **{table: counts[table] for table in IMPORTED_TABLES},
            "location_observation": counts["location_sample"],
        },
        "expected_skipped_counts": {table: counts[table] for table in SKIPPED_TABLES},
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


def parse_preferences(path: Path) -> dict[str, object]:
    root = ET.parse(path).getroot()
    values: dict[str, object] = {}
    for child in root:
        name = child.attrib.get("name")
        if not name:
            continue
        if child.tag == "string":
            values[name] = child.text or ""
        elif child.tag in ("int", "long"):
            values[name] = int(child.attrib["value"])
        elif child.tag == "boolean":
            values[name] = child.attrib["value"].lower() == "true"
    return values


def parse_counts(encoded: object) -> dict[str, int]:
    result: dict[str, int] = {}
    for entry in str(encoded or "").split(";"):
        if not entry:
            continue
        table, separator, value = entry.rpartition("=")
        require(bool(separator and table), f"Malformed count entry: {entry}")
        result[table] = int(value)
    return result


def assert_row(connection: sqlite3.Connection, sql: str, expected: tuple[object, ...]) -> None:
    row = connection.execute(sql).fetchone()
    require(row == expected, f"Unexpected row for {sql!r}: expected {expected!r}, got {row!r}")


def verify(
    manifest_path: Path,
    source_after: Path,
    target_before: Path,
    target_after: Path,
    preferences_path: Path,
    report_path: Path,
) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(sha256(source_after) == manifest["source_sha256"], "Legacy source bytes changed")

    source = sqlite3.connect(f"file:{source_after}?mode=ro", uri=True)
    before = sqlite3.connect(f"file:{target_before}?mode=ro", uri=True)
    after = sqlite3.connect(f"file:{target_after}?mode=ro", uri=True)
    try:
        validate_database(source, 26)
        validate_database(before, 27)
        validate_database(after, 27)
        require(
            len(table_names(before)) == 53,
            "Expected 51 application tables plus room_master_table and android_metadata",
        )

        source_counts = {table: count(source, table) for table in RELEASE_TABLES}
        require(source_counts == manifest["source_counts"], "Source logical counts changed")

        assert_row(before, "SELECT name,iconName FROM activity WHERE id=9101", ("Full matrix activity", "walk"))
        assert_row(
            before,
            "SELECT lat_e7,lon_e7,alt_m,source_signal_id FROM location_sample WHERE id=9102",
            (500755000, 144378000, 222.5, "legacy:location_sample:9102"),
        )
        assert_row(before, "SELECT source_signal_id FROM step_interval WHERE id=9103", ("legacy:step_interval:9103",))
        assert_row(before, "SELECT source_signal_id FROM activity_snapshot WHERE id=9104", ("legacy:activity_snapshot:9104",))
        assert_row(
            before,
            "SELECT source_signal_id,source_item_index,lat_e7,lon_e7 FROM cell_sample WHERE id=9105",
            ("legacy:cell_sample:9105", 0, 500755100, 144378100),
        )
        assert_row(
            before,
            "SELECT source_signal_id,source_item_index,lat_e7,lon_e7 FROM wifi_observation WHERE id=9106",
            ("legacy:wifi_observation:9106", 0, 500755200, 144378200),
        )
        assert_row(before, "SELECT primary_activity,distance_m FROM session_segment WHERE id=9107", (7, 123.5))
        assert_row(before, "SELECT total_steps,total_distance_m FROM daily_summary WHERE date_epoch_day=21000", (77, 123.5))
        assert_row(before, "SELECT cell_token,visit_count FROM exploration_cell WHERE id=9108", ("full-matrix-cell", 3))
        assert_row(before, "SELECT current_count,best_count FROM exploration_streak WHERE type='FULL_MATRIX'", (3, 5))
        assert_row(before, "SELECT pressure_hpa,source_signal_id FROM pressure_sample WHERE id=9109", (1003.25, "legacy:pressure_sample:9109"))
        assert_row(before, "SELECT segment_type,distance_m FROM ski_run_segment WHERE id=9110", ("DOWNHILL", 350.0))
        assert_row(
            before,
            """SELECT ingress_disposition,source_signal_id,source_event_id,callback_id,clock_domain_id
               FROM location_observation WHERE fix_time_ms=1700200000000""",
            (
                "MIGRATED_ACCEPTED",
                "legacy:location_observation:1",
                "legacy:location_observation_event:1",
                "legacy:location_observation_callback:1",
                "legacy:unknown",
            ),
        )

        removed_skipped = {
            "network_operator",
            "frequent_place",
            "inferred_trip",
            "trip_leg",
            "personal_record",
            "route_cache",
            "storage_size_snapshot",
        }
        require(not (removed_skipped & table_names(before)), "A removed skipped table exists in v27")
        retained_skipped = {
            "tracker_run",
            "live_stats",
            "achievement_progress",
            "export_log",
            "domain_event",
            "domain_event_cursor",
            "pending_signal",
        }
        for table in retained_skipped:
            require(count(before, table) == 0, f"Skipped source rows leaked into v27 table {table}")

        receipt = before.execute(
            """SELECT source_name,source_size_bytes,status
               FROM import_job_receipt WHERE job_id='legacy-database-v26'"""
        ).fetchone()
        require(receipt is not None, "Legacy completion receipt is missing")
        require(receipt[0] == "main_database:v26", f"Unexpected receipt source: {receipt}")
        require(receipt[1] == manifest["source_size_bytes"], f"Unexpected receipt size: {receipt}")
        require(receipt[2] == "COMPLETE", f"Unexpected receipt status: {receipt}")

        preferences = parse_preferences(preferences_path)
        require(preferences.get("status") == "COMPLETE", f"Unexpected external status: {preferences}")
        require(preferences.get("source_version") == 26, f"Unexpected source version: {preferences}")
        imported_counts = parse_counts(preferences.get("imported_counts"))
        skipped_counts = parse_counts(preferences.get("skipped_counts"))
        require(imported_counts == manifest["expected_imported_counts"], f"Imported report mismatch: {imported_counts}")
        require(skipped_counts == manifest["expected_skipped_counts"], f"Skipped report mismatch: {skipped_counts}")

        baseline = {
            "location_sample": count(before, "location_sample"),
            "session_segment": count(before, "session_segment"),
            "step_interval": count(before, "step_interval"),
            "tracker_run": count(before, "tracker_run"),
        }
        final_counts = {
            "location_sample": count(after, "location_sample"),
            "session_segment": count(after, "session_segment"),
            "step_interval": count(after, "step_interval"),
            "tracker_run": count(after, "tracker_run"),
        }
        for table in baseline:
            require(final_counts[table] > baseline[table], f"Post-import write did not increase {table}")
        assert_row(after, "SELECT name,iconName FROM activity WHERE id=9101", ("Full matrix activity", "walk"))
        require(
            after.execute("SELECT COUNT(*) FROM import_job_receipt WHERE job_id='legacy-database-v26' AND status='COMPLETE'").fetchone()[0] == 1,
            "Completion receipt was not retained after the current-version write",
        )
    finally:
        source.close()
        before.close()
        after.close()

    report = {
        "result": "PASS",
        "source_sha256_unchanged": manifest["source_sha256"],
        "source_counts": manifest["source_counts"],
        "reported_imported_counts": manifest["expected_imported_counts"],
        "reported_skipped_counts": manifest["expected_skipped_counts"],
        "target_counts_before_current_write": baseline,
        "target_counts_after_current_write": final_counts,
        "target_before_sha256": sha256(target_before),
        "target_after_sha256": sha256(target_after),
    }
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    build = subparsers.add_parser("build")
    build.add_argument("--schema", required=True, type=Path)
    build.add_argument("--output", required=True, type=Path)
    build.add_argument("--manifest", required=True, type=Path)

    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--manifest", required=True, type=Path)
    verify_parser.add_argument("--source-after", required=True, type=Path)
    verify_parser.add_argument("--target-before", required=True, type=Path)
    verify_parser.add_argument("--target-after", required=True, type=Path)
    verify_parser.add_argument("--preferences", required=True, type=Path)
    verify_parser.add_argument("--report", required=True, type=Path)

    args = parser.parse_args()
    if args.command == "build":
        build_fixture(args.schema, args.output, args.manifest)
    else:
        verify(
            args.manifest,
            args.source_after,
            args.target_before,
            args.target_after,
            args.preferences,
            args.report,
        )


if __name__ == "__main__":
    main()
