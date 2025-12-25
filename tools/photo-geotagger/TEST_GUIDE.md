# Photo Geotagger - Testing Guide

## Test Suite Overview

The photo geotagging tool includes comprehensive unit and integration tests covering all major components.

## Test Files

### 1. `test_parsers.py` - Parser Unit Tests

Tests for GPX, KML, and SQLite database parsers.

**Test Classes:**
- `TestGPXParser` - GPX format parsing
- `TestKMLParser` - KML format parsing  
- `TestDatabaseParser` - SQLite database parsing
- `TestLocationPoint` - Data model tests

**Coverage:**
- ✅ Valid file parsing
- ✅ Missing/malformed files
- ✅ Invalid XML/data
- ✅ Empty files
- ✅ Missing altitude data
- ✅ Invalid coordinates
- ✅ Coordinate range validation
- ✅ Time range filtering (database)

**Example tests:**
```python
# GPX parsing
test_parse_valid_gpx()
test_gpx_without_altitude()
test_gpx_invalid_xml()

# KML parsing  
test_parse_valid_kml()
test_kml_without_namespace()
test_kml_invalid_coordinates()

# Database parsing
test_parse_valid_database()
test_database_missing_table()
test_get_locations_in_range()
```

### 2. `test_matcher.py` - Matching Logic Tests

Tests for time-based photo-to-location matching algorithm.

**Test Class:**
- `TestTimeMatcher` - Time-based matching with/without interpolation
- `TestMatchTypeEnum` - Match type enumeration

**Coverage:**
- ✅ Exact timestamp matches
- ✅ Nearest neighbor matching
- ✅ Match within tolerance
- ✅ No match outside tolerance
- ✅ Linear interpolation
- ✅ Interpolation gap limits
- ✅ Confidence scoring
- ✅ Boundary conditions (before/after track)
- ✅ Altitude interpolation
- ✅ Empty location list

**Example tests:**
```python
test_exact_match()
test_nearest_match_within_tolerance()
test_no_match_outside_tolerance()
test_interpolated_match()
test_confidence_decreases_with_distance()
```

### 3. `test_integration.py` - End-to-End Tests

Integration tests for complete workflows.

**Test Classes:**
- `TestEndToEndWorkflow` - Full GPX→match→geotag workflows
- `TestErrorHandling` - Error handling across the stack

**Coverage:**
- ✅ GPX to photo workflow
- ✅ KML to photo workflow
- ✅ Database to photo workflow
- ✅ All formats produce identical results
- ✅ Corrupted file handling
- ✅ Wrong database schema
- ✅ Invalid coordinate ranges

**Example tests:**
```python
test_gpx_to_photo_workflow()
test_kml_to_photo_workflow()
test_all_formats_produce_same_results()
test_corrupted_gpx_file()
```

## Prerequisites

### Virtual Environment Setup

Tests require dependencies to be installed. Set up the virtual environment first:

**Windows (PowerShell):**
```powershell
.\setup_venv.ps1
```

**Linux/macOS (Bash):**
```bash
./setup_venv.sh
```

**Activate Environment:**

Before running tests, always activate the virtual environment:

**Windows:**
```powershell
.\venv\Scripts\Activate.ps1
```

**Linux/macOS:**
```bash
source venv/bin/activate
```

## Running Tests

### Run All Tests

```bash
# Ensure virtual environment is activated (venv) should appear in prompt
python run_tests.py
```

### Run Specific Test Module

```bash
# Parser tests only
python run_tests.py parsers

# Matcher tests only
python run_tests.py matcher

# Integration tests only
python run_tests.py integration
```

### Verbose Output

```bash
python run_tests.py -v
python run_tests.py parsers -v
```

### Run Single Test Class

```bash
python -m unittest test_parsers.TestGPXParser
python -m unittest test_matcher.TestTimeMatcher
```

### Run Single Test Method

```bash
python -m unittest test_parsers.TestGPXParser.test_parse_valid_gpx
python -m unittest test_matcher.TestTimeMatcher.test_interpolated_match
```

## Test Coverage Summary

| Component | Tests | Coverage |
|-----------|-------|----------|
| **GPX Parser** | 6 tests | Valid parsing, missing files, invalid XML, empty tracks, no altitude |
| **KML Parser** | 5 tests | Valid parsing, no namespace, missing timestamps, invalid coords |
| **Database Parser** | 7 tests | Valid parsing, NULL altitude, missing table, empty table, invalid coords, time range |
| **Location Model** | 3 tests | Creation, immutability, optional altitude |
| **Time Matcher** | 11 tests | Exact match, nearest, interpolation, confidence, boundaries, empty |
| **Integration** | 6 tests | End-to-end workflows, format consistency, error handling |

**Total: 38 tests**

## Expected Output

### Successful Test Run

```
Running all tests...

test_database_empty_table (test_parsers.TestDatabaseParser) ... ok
test_database_invalid_coordinates (test_parsers.TestDatabaseParser) ... ok
test_database_missing_table (test_parsers.TestDatabaseParser) ... ok
test_database_without_altitude (test_parsers.TestDatabaseParser) ... ok
test_get_locations_in_range (test_parsers.TestDatabaseParser) ... ok
test_parse_valid_database (test_parsers.TestDatabaseParser) ... ok
test_gpx_empty_track (test_parsers.TestGPXParser) ... ok
test_gpx_invalid_xml (test_parsers.TestGPXParser) ... ok
test_gpx_missing_file (test_parsers.TestGPXParser) ... ok
test_gpx_without_altitude (test_parsers.TestGPXParser) ... ok
test_parse_valid_gpx (test_parsers.TestGPXParser) ... ok
test_kml_invalid_coordinates (test_parsers.TestKMLParser) ... ok
test_kml_missing_timestamp (test_parsers.TestKMLParser) ... ok
test_kml_without_namespace (test_parsers.TestKMLParser) ... ok
test_parse_valid_kml (test_parsers.TestKMLParser) ... ok
test_location_point_creation (test_parsers.TestLocationPoint) ... ok
test_location_point_immutable (test_parsers.TestLocationPoint) ... ok
test_location_point_without_altitude (test_parsers.TestLocationPoint) ... ok
test_altitude_interpolation (test_matcher.TestTimeMatcher) ... ok
test_confidence_decreases_with_distance (test_matcher.TestTimeMatcher) ... ok
test_empty_locations (test_matcher.TestTimeMatcher) ... ok
test_exact_match (test_matcher.TestTimeMatcher) ... ok
test_interpolated_match (test_matcher.TestTimeMatcher) ... ok
test_interpolation_gap_too_large (test_matcher.TestTimeMatcher) ... ok
test_match_after_last_location (test_matcher.TestTimeMatcher) ... ok
test_match_before_first_location (test_matcher.TestTimeMatcher) ... ok
test_nearest_match_within_tolerance (test_matcher.TestTimeMatcher) ... ok
test_no_match_outside_tolerance (test_matcher.TestTimeMatcher) ... ok
test_match_types_exist (test_matcher.TestMatchTypeEnum) ... ok
test_all_formats_produce_same_results (test_integration.TestEndToEndWorkflow) ... ok
test_database_to_photo_workflow (test_integration.TestEndToEndWorkflow) ... ok
test_gpx_to_photo_workflow (test_integration.TestEndToEndWorkflow) ... ok
test_kml_to_photo_workflow (test_integration.TestEndToEndWorkflow) ... ok
test_corrupted_gpx_file (test_integration.TestErrorHandling) ... ok
test_kml_with_invalid_coordinates (test_integration.TestErrorHandling) ... ok
test_wrong_database_schema (test_integration.TestErrorHandling) ... ok

----------------------------------------------------------------------
Ran 38 tests in 0.342s

OK
```

## Adding New Tests

### Test Template

```python
import unittest
from pathlib import Path
import tempfile
import shutil

class TestNewFeature(unittest.TestCase):
    """Test description."""
    
    def setUp(self):
        """Create test fixtures."""
        self.temp_dir = Path(tempfile.mkdtemp())
    
    def tearDown(self):
        """Clean up."""
        shutil.rmtree(self.temp_dir)
    
    def test_feature_works(self):
        """Test that feature works correctly."""
        # Arrange
        # Act
        # Assert
        self.assertEqual(actual, expected)
```

## Continuous Testing

### Watch Mode (requires pytest-watch)

```bash
pip install pytest-watch
ptw -- -v
```

### Pre-Commit Hook

Create `.git/hooks/pre-commit`:

```bash
#!/bin/sh
cd tools/photo-geotagger
python run_tests.py
exit $?
```

```bash
chmod +x .git/hooks/pre-commit
```

## Test Dependencies

Required for tests:
- `unittest` (Python standard library)
- `tempfile` (Python standard library)
- `PIL` (Pillow) - for test photo creation
- All production dependencies

Install test requirements:
```bash
pip install -r requirements.txt
pip install pillow  # For image creation in tests
```

## Troubleshooting

### Tests Fail with "Module not found"

```bash
# Ensure you're in the photo-geotagger directory
cd tools/photo-geotagger

# Run tests
python run_tests.py
```

### Database Tests Fail

Ensure SQLite3 is available:
```python
import sqlite3
print(sqlite3.version)
```

### ExifTool Tests Fail

ExifTool tests require ExifTool binary installed:
```bash
exiftool -ver  # Should show version 12.00+
```

## Test Coverage Analysis

Generate coverage report (requires `coverage`):

```bash
pip install coverage

# Run tests with coverage
coverage run -m unittest discover

# Generate report
coverage report
coverage html  # Opens in htmlcov/index.html
```

Expected coverage: **>90%** for core components.

## Best Practices

1. **Test naming**: `test_<what>_<condition>` (e.g., `test_parse_gpx_with_altitude`)
2. **One assertion per test**: Focus on single behavior
3. **Arrange-Act-Assert**: Clear test structure
4. **Cleanup**: Always clean up temp files in `tearDown()`
5. **Independence**: Tests should not depend on each other
6. **Fast**: Unit tests should run in <1 second each
7. **Deterministic**: Same input → same output, always

## Future Test Additions

Potential tests to add:

- [ ] Performance benchmarks (large GPX files)
- [ ] Stress tests (millions of points)
- [ ] EXIF writing verification (requires real photos)
- [ ] Timezone handling edge cases
- [ ] Multi-format merging
- [ ] Backup/restore functionality
- [ ] Report generation tests
- [ ] CLI argument parsing tests

---

**Status**: ✅ Comprehensive test suite implemented with 38 tests covering parsers, matching, and integration workflows.
