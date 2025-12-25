# Database Export Feature - Implementation Summary

## Overview
Added bidirectional database conversion capability to the photo geotagger tool. The tool now supports both:
- **Import**: Reading location data FROM databases for photo geotagging (existing)
- **Export**: Converting databases TO standard GPS formats GPX/KML (new)

## Implementation Status: ✅ COMPLETE

### New Files Created

1. **db_exporter.py** (270+ lines)
   - `DatabaseExporter` class for SQLite → GPS format conversion
   - Methods:
     - `export_to_gpx()`: Generates GPX trackpoints with elevation/time
     - `export_to_kml()`: Generates KML placemarks with timestamps
     - `get_stats()`: Database statistics (point count, time range, duration)
     - `_get_locations()`: Time-range filtering support
     - `_validate_schema()`: Database schema validation

2. **export_db.py** (180+ lines)
   - Command-line interface for database export
   - Features:
     - Positional args: input database, output file
     - `--start` / `--end`: Time range filtering
     - `--name`: Custom track naming
     - `--stats`: Database statistics mode (no export)
   - Auto-detects format from `.gpx` / `.kml` extension
   - Supports ISO 8601 and simple datetime formats

3. **test_db_exporter.py** (160+ lines)
   - Comprehensive test suite with 8 tests:
     - `test_export_to_gpx`: Validates GPX XML structure
     - `test_export_to_kml`: Validates KML placemarks
     - `test_export_with_time_filter`: Verifies filtering works
     - `test_export_without_altitude`: Optional altitude handling
     - `test_get_stats`: Statistics accuracy
     - `test_empty_database_export`: Empty DB handling
     - `test_missing_database_file`: File not found errors
     - `test_invalid_database_schema`: Schema validation

### Files Modified

1. **run_tests.py**
   - Added `test_db_exporter` module import
   - Updated `run_all_tests()` to include exporter tests
   - Added `'exporter'` option for isolated test runs

2. **README.md**
   - Added "Database Export" feature bullet
   - Added "Database Export" section with usage examples
   - Documented CLI options and datetime formats
   - Listed use cases (sharing, backup, format conversion)

## Test Results

```
Running all tests...
Ran 44 tests in 1.221s
OK
```

**Test Breakdown:**
- 18 parser tests (GPX, KML, DB parsing)
- 11 matcher tests (time matching, interpolation)
- 7 integration tests (end-to-end workflows)
- 8 export tests (database export functionality)

**Total: 44 passing tests (100% success rate)**

## Usage Examples

### Basic Export
```bash
# Export entire database to GPX
python export_db.py tracker.db export.gpx

# Export to KML
python export_db.py tracker.db export.kml
```

### Time-Range Filtering
```bash
# Export specific time period
python export_db.py tracker.db trip.gpx \
  --start "2025-11-01 08:00" \
  --end "2025-11-01 18:00"
```

### Custom Track Naming
```bash
# Name the track in the output file
python export_db.py tracker.db morning_run.gpx --name "Morning Run"
```

### Database Statistics
```bash
# View database info without exporting
python export_db.py tracker.db --stats
```

**Output:**
```
Database Statistics:
  Total points: 1,234
  Time range: 2025-11-01 08:00:00 to 2025-11-01 18:00:00
  Duration: 10h 0m 0s
```

## Technical Details

### Database Schema Support
Reads from Tracker Android `location_data` table:
- `time` (INTEGER, epoch milliseconds)
- `latitude` (REAL)
- `longitude` (REAL)
- `altitude` (REAL, optional)

### Output Formats

**GPX (GPS Exchange Format):**
```xml
<?xml version="1.0"?>
<gpx version="1.1" creator="Tracker Android Exporter">
  <trk>
    <name>Track Name</name>
    <trkseg>
      <trkpt lat="50.0814" lon="14.4211">
        <ele>200.5</ele>
        <time>2025-11-01T08:00:00Z</time>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
```

**KML (Keyhole Markup Language):**
```xml
<?xml version="1.0"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>Track Name</name>
    <Placemark>
      <Point>
        <coordinates>14.4211,50.0814,200.5</coordinates>
      </Point>
      <TimeStamp><when>2025-11-01T08:00:00Z</when></TimeStamp>
    </Placemark>
  </Document>
</kml>
```

### Coordinate Validation
- Latitude: -90.0 to +90.0
- Longitude: -180.0 to +180.0
- Invalid coordinates logged as warnings, skipped in output

### Error Handling
- Missing database file → clear error message
- Invalid schema → schema validation error
- Empty database → empty track/document (valid XML)
- Coordinate validation → skip invalid points with warning

## Use Cases

1. **Sharing Routes**: Convert database to GPX for sharing with friends or GPS communities
2. **Tool Compatibility**: Import into Strava, Google Earth, GPS analysis software
3. **Backup**: Store location data in standard, widely-supported formats
4. **Trip Extraction**: Export specific time ranges for individual trips or activities
5. **Data Migration**: Move data between different GPS platforms

## Architecture Alignment

Follows Tracker Android evergreen guidelines:
- ✅ Privacy-first: 100% local processing, no network operations
- ✅ Modular design: `DatabaseExporter` class with clear interface
- ✅ Reuses existing patterns: `LocationPoint` interface, similar to parsers
- ✅ Comprehensive testing: 8 tests covering happy paths and error conditions
- ✅ Proper error handling: Sealed result types via exceptions for invalid data
- ✅ Documentation: README updated with examples and use cases

## Future Enhancements (Optional)

- [ ] Support for additional GPS formats (TCX, FIT)
- [ ] Batch export (multiple databases → single track)
- [ ] Track simplification (reduce point count while preserving shape)
- [ ] Export filtering by activity type or accuracy threshold
- [ ] Progress bars for large database exports

---

**Implementation completed**: 2025-01-XX
**Tests validated**: All 44 tests passing
**Documentation**: README.md updated with usage examples
