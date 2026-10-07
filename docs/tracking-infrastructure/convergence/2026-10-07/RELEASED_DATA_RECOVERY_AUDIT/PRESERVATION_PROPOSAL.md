# Smallest concrete old-data preservation proposal

PROPOSED, NOT_APPROVED, NOT_IMPLEMENTED. Source-only investigation; every test is NOT_RUN.
The original user asked that all older data migrate into the new infrastructure while preserving
what exists, with no invented ownership. The user confirmed only main was released. This proposal
covers main12 records and optional known normalized metadata, using the current one-shot import
and Room database. It adds no backup format, archive engine, recovery scheduler or provider demand.

## User-facing approval wording

“Older recordings are safe in the original database, but the updated app currently leaves out some
saved details: session labels and distance breakdowns, first-seen network dates, Cell details and
Wi-Fi counts. I recommend copying those details into the current app data too. Short or unfinished
older sessions would keep their original values rather than getting made-up dates or measurements.
May I preserve this older data when upgrading?”

Trade-off: small extra local metadata and migration code; the benefit is that old recorded values
are available to the same app/history/data readers. The activity-type collision was separately
corrected at 6c7dd22d737063ccf107f2c320312e59d8c361a8 without new storage. This question covers the concrete reuse/additions
below; it does not grant collection or native proof to old data.

## Existing storage to reuse

- Keep the existing canonical Location/Activity/Wi-Fi/Cell/session tables and their migrated IDs.
- Keep the existing atomic LegacyImportRoomCallback/LegacyV26Importer and generic import-job receipt.
- Reuse organization_annotation for the original chosen session label, adapter key segment:<id>.
  activity_label_id is the original saved label reference. Existing activity definitions are already
  copied. Keep original bounds in session_segment; leave optional annotation bound/zone/name overrides
  unset. There was no saved timezone or session name to invent. A missing/unsupported label remains
  unavailable by the existing reader; the raw original ID is retained below.
- Leave canonical primary_activity as stored detected Activity, including NULL. Never map a chosen
  label ID into it. Existing actual Activity snapshots remain independent observations.
- Keep legacy radio details in their original observation rows. Existing legacy child mirrors keep
  their common presentation fields and row references; obtain extra metadata from the same original
  row instead of duplicating it again in child tables.
- Existing imported typed products and Location file-membership tables enforce file/native lineage
  contracts. They are not suitable as a generic bag for unknown old fields; do not fabricate a typed
  file or accepted native owner to reuse them.

## Minimal target additions

These are the exact columns already populated by the frozen normalization chain. Add them as
nullable fields with NULL defaults on the existing entities; new provider rows leave them unset.
Known normalized sources without a column yield NULL, preserving compatibility without guessing.

| Existing target | Optional original fields to retain | Count |
| --- | --- | --- |
| location_sample | legacy_lat REAL/Double, legacy_lon REAL/Double, legacy_alt_m REAL/Double | 3 |
| wifi_observation | legacy_first_seen_ms INTEGER/Long; legacy_alt_m, legacy_lat, legacy_lon REAL/Double | 4 |
| cell_sample | legacy_source_id INTEGER/Long; legacy_mcc/legacy_mnc TEXT; legacy_alt_m/legacy_lat/legacy_lon REAL/Double | 6 |
| session_segment | legacy_user_initiated INTEGER/Boolean; legacy_distance_on_foot_m/legacy_distance_in_vehicle_m REAL; legacy_activity_id INTEGER/Long | 4 |

17 optional columns total. The original coordinate values preserve precision alongside current
E7 representation. Source code/type and ASU retain the original meaning; missing LAC/provider
clock/purpose stays unavailable, rather than treating conversion defaults as measured values.
No additional signal-unit field is needed for the known main cell_location.asu mapping.

Three known old tables must become declared active Room data:

1. legacy_rejected_tracker_session: reuse the normalized compatibility table's exact original
   id/start/end/user_initiated/collections/distance/distance_on_foot/distance_in_vehicle/steps/
   session_activity_id. These records failed the old conversion's interval/count filter. Store
   the raw record; do not promote it into a valid trip, invent end time, sensor readings or counts.
2. legacy_location_wifi_count: reuse id/time/count/lat/lon/alt from normalized source. This is an
   actual count observation, not an invented network list or continuously scanning interval.
3. network_operator: the current NetworkOperator Parcelable class has mcc/mnc/name but no Room
   annotation. Restore its released androidx.room.Entity import and
   @Entity(tableName = "network_operator", primaryKeys = ["mcc", "mnc"]), then register that same
   class in AppDatabase. Main12 has TEXT/TEXT keys and a nullable TEXT name, matching this class.
   Copy its original string keys/name; do not add a duplicate DTO. A minimal DAO supplies only
   bounded needed read/clear operations, rather than a new repository engine.

The two legacy compatibility tables are optional for known normalized26 inputs. If either is
absent, copy zero rows from that table and continue the canonical import; do not newly reject an
otherwise supported input. The released main12 normalization supplies both tables.

No generic metadata table, JSON blob, second database, duplicate full legacy archive, new foreign
native identity or additional value proof is proposed. Keep this under the current unshipped
schema assembly convention; incomplete unknown development databases stay preserved/blocked.
The one-shot importer runs only when creating the target, not on every open.

## Cleanup and ownership

- Optional original fields live on their parent row. Existing delete/retention of that row deletes
  them too. Existing original radio -> child mirror deletion remains the one cleanup path.
- An annotation references the same legacy segment adapter. Existing metadata edits/group removal,
  suppression and retention apply; it does not own or delete unrelated observations by interval.
- The raw rejected-session table is organization/legacy data, with no inferred captured source or
  ambient/session consent. Explicit full clear removes it. Time retention may remove it only when
  both original nonnegative times are before the cutoff; an invalid/unknown bound is not fabricated.
  Source-purpose erase cannot claim it from a guessed measurement set.
- The count-only Wi-Fi table belongs to legacy Wi-Fi data. Its original time controls retention;
  full clear or an explicit all-Wi-Fi-data erase deletes it. Capture-only/background-only erase
  cannot retrospectively assign an unknown old purpose.
- Operator names are finite untimed Cell metadata. Keep them until explicit full clear or an
  all-Cell-data erase. Add no orphan-pruning/reference engine or guessed recency rule.
- Add the three table deletes to the existing clear transaction. Keep its existing legacy import
  completion marker (AppDatabase.deleteCollectedRows already excludes that job from receipt clear),
  preventing the retained vault from resurrecting cleared data. Keep independently valid sibling
  purposes and source payload-free replay fences under their existing rules.
- Retention work uses the existing approved operation/transaction gates. No independent cleanup
  worker or guessed deletion authority is introduced.

## Focused source scenarios needed after approval

Extend populated main12 conversion with: custom label collision/negative built-in reference;
nonzero foot/vehicle breakdown and initiated flag; zero/one-collection and invalid-bound records;
Wi-Fi first/last dates and original coordinates/altitude; Cell padded MNC/source ID/ASU/altitude;
count-only rows and operator name; exact repeat/reopen; parent delete/retention/clear cleanup with
legacy completion marker preserved; normalized optional-column absence stays NULL; no native
session/authorization/proof rows invented; known normalized26 input with either compatibility
table absent still imports canonical rows. Check existing query mappings and schema sentinels in
source before freezing. Execute only in the final convergence gate.

## Scope remaining after preservation

This proposal makes data available in current typed/local storage. Universal old-source History
browse/detail routes and source-format presentation remain their planned UI/reader work; raw storage
alone does not claim that every older item already appears there. Backup redesign is outside this
proposal. No new UI element or export format is selected here.
