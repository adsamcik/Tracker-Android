-- SOURCE ONLY, NOT_RUN. This is an input sketch for the existing populated schema12 fixture.
-- Create tables from the pinned main12 JSON, as createReleasedV12Database does.
INSERT INTO activity(id, name, iconName) VALUES (7, 'My cycling label', 'bicycle');
INSERT INTO tracker_session(id, start, `end`, user_initiated, collections,
    distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id)
VALUES (201, 1700099900000, 1700100100000, 1, 2, 125.0, 0.0, 125.0, 20, 7);

-- Actual MIGRATION_12_13 source assigns NULL to detected primary_activity and saves label7
-- as legacy_activity_id. The original chosen name does not mean detected Android Activity7.
-- The former TableCopySpec expression is:
SELECT COALESCE(CAST(NULL AS INTEGER), 7) AS former_primary_activity;
-- Source-level predicted result: 7. DefaultTripRepository resolves 7 as WALK.
-- Selected routine repair copies the canonical field directly:
SELECT CAST(NULL AS INTEGER) AS canonical_primary_activity;
-- Source-level predicted result: NULL. Same repository resolves legacy NULL as UNKNOWN.
-- This SQL was not executed. The real Room fixture methods are in
-- ../LEGACY_ACTIVITY_DOMAIN_SELECTED/SELECTED/.../LegacyV26ImportTest.kt and remain NOT_RUN.
