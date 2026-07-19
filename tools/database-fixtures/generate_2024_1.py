#!/usr/bin/env python3
"""Generate deterministic Room database fixtures from the exact 2024.1 release commit."""

from __future__ import annotations

import json
import shutil
import sqlite3
import subprocess
from pathlib import Path
from typing import Callable


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "core" / "base" / "src" / "test" / "resources" / "baseline" / "2024.1"
RELEASE_COMMIT = "e8586ade79569fb756ed6736184d6f55cc4ffd3f"

SCHEMAS = {
    "main_database.db": (
        "sbase/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/10.json",
        "seed_main",
    ),
    "debug_database_sbase.db": (
        "sbase/schemas/com.adsamcik.tracker.shared.base.database.DebugDatabase/1.json",
        "seed_debug",
    ),
    "preference_database.db": (
        "sbase/schemas/com.adsamcik.tracker.shared.base.database.PreferenceDatabase/1.json",
        "seed_preferences",
    ),
    "debug_database_logger.db": (
        "logger/schemas/com.adsamcik.tracker.logger.LogDatabase/1.json",
        "seed_logs",
    ),
    "points_database.db": (
        "points/schemas/com.adsamcik.tracker.points.database.PointsDatabase/1.json",
        "seed_points",
    ),
    "stats_database.db": (
        "statistics/schemas/com.adsamcik.tracker.statistics.database.StatsDatabase/1.json",
        "seed_stats",
    ),
    "challenge_database.db": (
        "game/schemas/com.adsamcik.tracker.game.challenge.database.ChallengeDatabase/1.json",
        "seed_challenges",
    ),
}


def git_show(path: str) -> str:
    return subprocess.check_output(
        ["git", "show", f"{RELEASE_COMMIT}:{path}"],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
    )


def create_database(path: Path, schema_path: str, seed: Callable[[sqlite3.Connection], None]) -> None:
    schema = json.loads(git_show(schema_path))["database"]
    path.unlink(missing_ok=True)
    connection = sqlite3.connect(path)
    try:
        connection.execute("PRAGMA journal_mode=DELETE")
        connection.execute("PRAGMA foreign_keys=ON")
        for entity in schema["entities"]:
            table_name = entity["tableName"]
            connection.execute(entity["createSql"].replace("${TABLE_NAME}", table_name))
            for index in entity.get("indices", []):
                connection.execute(index["createSql"].replace("${TABLE_NAME}", table_name))
        for view in schema.get("views", []):
            connection.execute(view["createSql"].replace("${VIEW_NAME}", view["viewName"]))
        for query in schema["setupQueries"]:
            connection.execute(query)
        connection.execute(f"PRAGMA user_version={schema['version']}")
        seed(connection)
        connection.commit()
        connection.execute("VACUUM")
    finally:
        connection.close()


def seed_main(db: sqlite3.Connection) -> None:
    native_ids = range(-34, -1)
    db.executemany(
        "INSERT INTO activity(id, name, iconName) VALUES (?, ?, ?)",
        [(activity_id, f"Native {activity_id}", f"native_{abs(activity_id)}") for activity_id in native_ids],
    )
    db.execute(
        "INSERT INTO activity(id, name, iconName) VALUES (7, 'Custom run', 'custom_run')"
    )
    db.executemany(
        "INSERT INTO network_operator(mcc, mnc, name) VALUES (?, ?, ?)",
        [
            ("230", "01", "Test Operator"),
            ("001", "01", "Leading Zero"),
            ("310", "260", "Wide Cell Operator"),
        ],
    )

    locations = [
        (1, 1_000, 48.1234567, 17.9876543, 200.5, 5.0, 1.0, 2.5, 0.5, 0, 80),
        (2, 2_000, -89.9999999, 179.9999999, None, None, None, None, None, 4, 0),
        (3, 3_000, 89.9999999, -179.9999999, -430.25, 49.999, 999.0, 0.0, 0.0, 7, 100),
        (4, 4_000, 0.0000001, -0.0000001, 8_848.86, 50.0, 2.0, 123.45, 9.9, 8, 55),
        (5, 5_000, 0.0, 0.0, 0.0, 9.999, 0.0, -1.0, 0.0, 3, 1),
        (6, 6_000, 0.00000016, -0.00000016, 12.3456789, 10.0, 3.0, 1.0, 0.1, 1, 50),
    ]
    db.executemany(
        """
        INSERT INTO location_data(
            id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        locations,
    )

    activity_ids = list(range(-34, -1)) + [7, None]
    sessions = []
    for index, activity_id in enumerate(activity_ids, start=1):
        sessions.append(
            (
                index,
                10_000 * index,
                10_000 * index + 5_000,
                index % 2,
                index + 1,
                index * 100.25,
                index * 60.5,
                index * 39.75,
                index * 10,
                activity_id,
            )
        )
    sessions.extend(
        [
            (100, 900_000, 900_000, 1, 10, 1.0, 1.0, 0.0, 1, -2),
            (101, 910_000, 920_000, 0, 1, 2.0, 0.0, 2.0, 0, -5),
        ]
    )
    db.executemany(
        """
        INSERT INTO tracker_session(
            id, start, `end`, user_initiated, collections, distance,
            distance_on_foot, distance_in_vehicle, steps, session_activity_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        sessions,
    )

    db.executemany(
        """
        INSERT INTO wifi_data(
            bssid, longitude, latitude, altitude, first_seen, last_seen,
            ssid, capabilities, frequency, level
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        [
            (
                "AA:BB:CC:DD:EE:01",
                17.875,
                48.125,
                250.75,
                1_700_000_000_000,
                1_700_000_050_000,
                "Home 'quoted'",
                "[WPA2-PSK-CCMP][ESS]",
                2412,
                -55,
            ),
            (
                "AA:BB:CC:DD:EE:02",
                None,
                None,
                None,
                1_700_000_100_000,
                1_700_000_200_000,
                "Síť-测试",
                "",
                5955,
                -127,
            ),
        ],
    )
    db.executemany(
        """
        INSERT INTO cell_location(
            id, time, mcc, mnc, cell_id, type, asu, lat, lon, alt
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        [
            (1, 1_700_000_000_000, "230", "01", 9_999, 13, 40, 48.125, 17.875, 250.25),
            (
                2,
                1_700_000_100_000,
                "310",
                "260",
                68_719_476_735,
                20,
                97,
                -89.9999999,
                179.9999999,
                None,
            ),
            (3, 1_700_000_000_000, "001", "01", 9_999, 3, 10, 48.5, 18.5, 123.0),
        ],
    )
    db.executemany(
        """
        INSERT INTO location_wifi_count(id, time, count, lat, lon, alt)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        [
            (1, 1_700_000_000_000, 3, 48.125, 17.875, 200.0),
            (2, 1_700_000_100_000, 0, -89.9999999, 179.9999999, None),
        ],
    )


def seed_debug(db: sqlite3.Connection) -> None:
    db.executemany(
        "INSERT INTO debug_activity(id, time, action, activity, confidence) VALUES (?, ?, ?, ?, ?)",
        [
            (1, 1_700_000_000_000, "tracker_started", 0, 80),
            (2, 1_700_000_001_000, "tracker_stopped", 3, 100),
            (3, 1_700_000_002_000, None, 4, 0),
            (4, 9_223_372_036_854_000_000, "boundary", 8, 101),
        ],
    )


def seed_preferences(db: sqlite3.Connection) -> None:
    db.executemany(
        "INSERT INTO generic(id, value) VALUES (?, ?)",
        [
            ("tracking_enabled", "true"),
            ("json", '{"retentionDays":365,"name":"Síť-测试"}'),
            ("empty", ""),
        ],
    )
    db.executemany(
        "INSERT INTO notification(id, `order`, isInTitle, isInContent) VALUES (?, ?, ?, ?)",
        [
            ("distance", 0, 1, 1),
            ("steps", 1, 0, 1),
            ("duration", 2, 1, 0),
        ],
    )


def seed_logs(db: sqlite3.Connection) -> None:
    db.executemany(
        "INSERT INTO log_data(id, timeStamp, message, data, source) VALUES (?, ?, ?, ?, ?)",
        [
            (1, 1_700_000_000_000, "Started", "{}", "Tracker"),
            (2, 1_700_000_001_000, "Unicode Síť-测试", '{"value":null}', "Activity"),
            (3, 9_223_372_036_854_000_000, "", "x" * 4096, "Boundary"),
        ],
    )


def seed_points(db: sqlite3.Connection) -> None:
    db.executemany(
        "INSERT INTO points_awarded(id, time, value, source) VALUES (?, ?, ?, ?)",
        [
            (1, 1_700_000_000_000, 1.25, "walk"),
            (2, 1_700_000_001_000, -5.5, "correction"),
            (3, 9_223_372_036_854_000_000, 1.7976931348623157e308, "boundary"),
        ],
    )


def seed_stats(db: sqlite3.Connection) -> None:
    db.executemany(
        "INSERT INTO statCache(session_id, provider_id, value) VALUES (?, ?, ?)",
        [
            (1, "distance", '{"meters":100.25}'),
            (34, "activity", '{"id":-23}'),
            (101, "unicode", '{"label":"Síť-测试"}'),
        ],
    )


def seed_challenges(db: sqlite3.Connection) -> None:
    db.executemany(
        "INSERT INTO entry(id, type, start_time, end_time, difficulty) VALUES (?, ?, ?, ?, ?)",
        [
            (1, 0, 1_700_000_000_000, 1_700_086_400_000, 1),
            (2, 1, 1_700_100_000_000, 1_700_186_400_000, 5),
            (3, 2, 1_700_200_000_000, 1_700_286_400_000, 10),
        ],
    )
    db.executemany(
        "INSERT INTO challenge_session_data(id, challenge_processed) VALUES (?, ?)",
        [(1, 1), (34, 0), (101, 1)],
    )
    db.execute(
        """
        INSERT INTO challenge_explorer(
            required_location_count, location_count, id, entry_id, completed
        ) VALUES (100, 99, 1, 1, 0)
        """
    )
    db.execute(
        """
        INSERT INTO challenge_walk_distance(
            required_distance, distance, id, entry_id, completed
        ) VALUES (10000.5, 9999.75, 1, 2, 0)
        """
    )
    db.execute(
        """
        INSERT INTO challenge_step(
            requiredStepCount, stepCount, id, entry_id, completed
        ) VALUES (100000, 100000, 1, 3, 1)
        """
    )


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    seeders = globals()
    for file_name, (schema_path, seeder_name) in SCHEMAS.items():
        create_database(OUTPUT / file_name, schema_path, seeders[seeder_name])
        print(f"generated {OUTPUT / file_name}")

    module_copies = {
        OUTPUT / "main_database.db": (
            ROOT
            / "core"
            / "base"
            / "src"
            / "androidTest"
            / "assets"
            / "baseline"
            / "2024.1"
            / "main_database.db"
        ),
        OUTPUT / "debug_database_logger.db": (
            ROOT
            / "core"
            / "logging"
            / "src"
            / "test"
            / "resources"
            / "baseline"
            / "2024.1"
            / "debug_database_logger.db"
        ),
        OUTPUT / "points_database.db": (
            ROOT
            / "domain"
            / "points"
            / "src"
            / "test"
            / "resources"
            / "baseline"
            / "2024.1"
            / "points_database.db"
        ),
    }
    for source, destination in module_copies.items():
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        print(f"copied {destination}")


if __name__ == "__main__":
    main()
