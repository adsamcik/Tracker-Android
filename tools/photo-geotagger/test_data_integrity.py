"""
Comprehensive data integrity and resiliency tests.

These tests focus on:
- Data corruption detection and recovery
- File integrity after operations
- Backup/restore correctness
- Edge cases and boundary conditions
- Race conditions and concurrent access
- Large dataset handling
"""

import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path
import tempfile
import shutil
import sqlite3
import subprocess
import hashlib
import json
import xml.etree.ElementTree as ET
from PIL import Image
import io

from gpx_parser import GPXParser
from kml_parser import KmlParser
from db_parser import DatabaseParser
from db_exporter import DatabaseExporter
from matcher import TimeMatcher
from exiftool_wrapper import ExifToolWrapper
from models import PhotoMetadata, LocationPoint


class TestDataIntegrity(unittest.TestCase):
    """Test data integrity throughout the geotagging pipeline."""
    
    def setUp(self):
        """Create temporary directory for test files."""
        self.temp_dir = Path(tempfile.mkdtemp())
        self.connections = []
    
    def tearDown(self):
        """Clean up temporary files and connections."""
        for conn in self.connections:
            try:
                conn.close()
            except:
                pass
        import gc
        gc.collect()
        try:
            shutil.rmtree(self.temp_dir)
        except PermissionError:
            import time
            time.sleep(0.1)
            try:
                shutil.rmtree(self.temp_dir)
            except:
                pass
    
    def _compute_file_hash(self, filepath):
        """Compute SHA256 hash of file for integrity verification."""
        sha256 = hashlib.sha256()
        with open(filepath, 'rb') as f:
            while chunk := f.read(8192):
                sha256.update(chunk)
        return sha256.hexdigest()
    
    def _create_test_photo(self, filename, width=100, height=100, color='red'):
        """Create a test photo and return its hash."""
        photo_path = self.temp_dir / filename
        img = Image.new('RGB', (width, height), color=color)
        img.save(photo_path, 'JPEG', quality=95)
        return photo_path, self._compute_file_hash(photo_path)
    
    def _create_test_database(self, filepath, locations):
        """Create test database with location data."""
        conn = sqlite3.connect(str(filepath))
        self.connections.append(conn)
        cursor = conn.cursor()
        
        cursor.execute("""
            CREATE TABLE location_data (
                id INTEGER PRIMARY KEY,
                time INTEGER,
                lat REAL,
                lon REAL,
                altitude REAL
            )
        """)
        
        for loc in locations:
            cursor.execute(
                "INSERT INTO location_data (time, lat, lon, altitude) VALUES (?, ?, ?, ?)",
                loc
            )
        
        conn.commit()
        conn.close()
        return filepath
    
    def test_gpx_parser_malformed_xml(self):
        """Test GPX parser handles malformed XML gracefully."""
        malformed_cases = [
            # Missing closing tag
            """<?xml version="1.0"?><gpx><trk><trkseg><trkpt lat="40.7" lon="-74.0"></trkseg></trk></gpx>""",
            # Invalid XML characters
            """<?xml version="1.0"?><gpx><trk><name>Test\x00Track</name></trk></gpx>""",
            # Empty file
            """""",
            # Not XML at all
            """This is just plain text, not XML""",
        ]
        
        for i, content in enumerate(malformed_cases):
            gpx_file = self.temp_dir / f"malformed_{i}.gpx"
            gpx_file.write_text(content, encoding='utf-8', errors='ignore')
            
            # Parser should handle gracefully, not crash
            try:
                parser = GPXParser(gpx_file)
                locations = parser.get_locations()
                # Should return empty list or minimal data, not crash
                self.assertIsInstance(locations, list)
            except Exception as e:
                # If it raises an exception, it should be a clear, expected error
                self.assertIn('XML', str(e).upper() or 'PARSE' in str(e).upper())
    
    def test_database_export_preserves_precision(self):
        """Test that database export preserves coordinate precision."""
        # Use high-precision coordinates
        test_data = [
            (1762090200000, 40.712775, -74.005973, 10.5),
            (1762090500000, 40.713812, -74.004956, 12.3),
            (1762090800000, 40.714891, -74.003842, 15.7),
        ]
        
        db_file = self._create_test_database(self.temp_dir / "precise.db", test_data)
        exporter = DatabaseExporter(db_file)
        
        # Export to GPX
        gpx_output = self.temp_dir / "precise.gpx"
        exporter.export_to_gpx(gpx_output)
        
        # Re-parse and verify precision
        parser = GPXParser(gpx_output)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 3)
        
        # Verify coordinates match to at least 5 decimal places (±1.1m accuracy)
        for original, parsed in zip(test_data, locations):
            _, lat_orig, lon_orig, alt_orig = original
            self.assertAlmostEqual(parsed.latitude, lat_orig, places=5,
                                   msg=f"Latitude precision lost: {lat_orig} -> {parsed.latitude}")
            self.assertAlmostEqual(parsed.longitude, lon_orig, places=5,
                                   msg=f"Longitude precision lost: {lon_orig} -> {parsed.longitude}")
            if alt_orig is not None and parsed.altitude is not None:
                self.assertAlmostEqual(parsed.altitude, alt_orig, places=1,
                                       msg=f"Altitude precision lost: {alt_orig} -> {parsed.altitude}")
    
    def test_database_export_preserves_timestamp_precision(self):
        """Test that timestamp precision is maintained through export/import cycle."""
        # Test with millisecond precision timestamps
        base_time = 1762086600000  # Nov 2, 2025 13:30:00 UTC (corrected)
        test_data = [
            (base_time, 40.7128, -74.0060, 10.5),
            (base_time + 500, 40.7129, -74.0061, 10.6),  # 500ms later
            (base_time + 1000, 40.7130, -74.0062, 10.7),  # 1s later
            (base_time + 1500, 40.7131, -74.0063, 10.8),  # 1.5s later
        ]
        
        db_file = self._create_test_database(
            self.temp_dir / "timestamps.db", test_data
        )
        exporter = DatabaseExporter(db_file)
        
        gpx_output = self.temp_dir / "timestamps.gpx"
        exporter.export_to_gpx(gpx_output)
        
        # Parse back
        parser = GPXParser(gpx_output)
        locations = parser.get_locations()
        
        # Verify timestamp precision (GPX uses ISO 8601, seconds)
        for i, loc in enumerate(locations):
            timestamp_seconds = test_data[i][0] / 1000.0
            expected_dt = datetime.fromtimestamp(
                timestamp_seconds, tz=timezone.utc
            )
            # GPX typically stores second precision, not millisecond
            # Compare without microseconds since GPX doesn't support them
            actual_utc = loc.timestamp.astimezone(
                timezone.utc
            ).replace(microsecond=0)
            expected_utc = expected_dt.replace(microsecond=0)
            self.assertEqual(
                actual_utc, expected_utc,
                msg=f"Timestamp mismatch at index {i}"
            )
    
    def test_database_corruption_detection(self):
        """Test detection of corrupted database files."""
        # Create valid database
        test_data = [(1762090200000, 40.7128, -74.0060, 10.5)]
        db_file = self._create_test_database(self.temp_dir / "valid.db", test_data)
        
        # Corrupt the database by writing garbage
        corrupted_db = self.temp_dir / "corrupted.db"
        with open(corrupted_db, 'wb') as f:
            f.write(b'This is not a valid SQLite database file' * 100)
        
        # Parser should detect corruption
        with self.assertRaises(Exception) as ctx:
            parser = DatabaseParser(corrupted_db)
            locations = parser.get_locations()
        
        # Error message should indicate database issue
        error_msg = str(ctx.exception).lower()
        self.assertTrue('database' in error_msg or 'sqlite' in error_msg or 'file' in error_msg)
    
    def test_database_schema_validation(self):
        """Test that incorrect schema is properly detected."""
        # Create database with wrong schema
        wrong_schema_db = self.temp_dir / "wrong_schema.db"
        conn = sqlite3.connect(str(wrong_schema_db))
        self.connections.append(conn)
        cursor = conn.cursor()
        
        # Create table with different column names
        cursor.execute("""
            CREATE TABLE location_data (
                id INTEGER PRIMARY KEY,
                timestamp INTEGER,
                latitude REAL,
                longitude REAL
            )
        """)
        
        cursor.execute(
            "INSERT INTO location_data (timestamp, latitude, longitude) VALUES (?, ?, ?)",
            (1762090200000, 40.7128, -74.0060)
        )
        
        conn.commit()
        conn.close()
        
        # DatabaseParser expects 'time', 'lat', 'lon' columns
        with self.assertRaises(Exception) as ctx:
            parser = DatabaseParser(wrong_schema_db)
            locations = parser.get_locations()
        
        # Should indicate schema problem
        self.assertTrue('column' in str(ctx.exception).lower() or 
                        'schema' in str(ctx.exception).lower())
    
    def test_large_dataset_memory_efficiency(self):
        """Test that large datasets don't cause memory issues."""
        # Create database with 10,000 location points
        large_data = []
        base_time = 1762090200000
        base_lat = 40.7128
        base_lon = -74.0060
        
        for i in range(10000):
            # Simulate a track with points every 5 seconds over ~14 hours
            time_ms = base_time + (i * 5000)
            # Small incremental changes to simulate movement
            lat = base_lat + (i * 0.0001)
            lon = base_lon + (i * 0.0001)
            alt = 10.0 + (i * 0.01)
            large_data.append((time_ms, lat, lon, alt))
        
        db_file = self._create_test_database(self.temp_dir / "large.db", large_data)
        
        # Parse large database
        parser = DatabaseParser(db_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 10000)
        
        # Export to GPX (test streaming/memory efficiency)
        exporter = DatabaseExporter(db_file)
        gpx_output = self.temp_dir / "large.gpx"
        exporter.export_to_gpx(gpx_output)
        
        # Verify file was created and has reasonable size
        self.assertTrue(gpx_output.exists())
        file_size_mb = gpx_output.stat().st_size / (1024 * 1024)
        # 10k points should be roughly 1-5 MB
        self.assertLess(file_size_mb, 20, "GPX file unexpectedly large")
        
        # Verify can parse back
        parser2 = GPXParser(gpx_output)
        locations2 = parser2.get_locations()
        self.assertEqual(len(locations2), 10000)
    
    def test_coordinate_boundary_conditions(self):
        """Test extreme coordinate values are handled correctly."""
        boundary_coords = [
            # Valid extreme values
            (1762090200000, 90.0, 180.0, 8848.0),      # North Pole, East max, Everest
            (1762090300000, -90.0, -180.0, -430.0),    # South Pole, West max, Dead Sea
            (1762090400000, 0.0, 0.0, 0.0),            # Null Island
            (1762090500000, 51.5074, -0.1278, 11.0),   # London
        ]
        
        db_file = self._create_test_database(self.temp_dir / "boundaries.db", boundary_coords)
        exporter = DatabaseExporter(db_file)
        
        # Export and re-import
        gpx_output = self.temp_dir / "boundaries.gpx"
        exporter.export_to_gpx(gpx_output)
        
        parser = GPXParser(gpx_output)
        locations = parser.get_locations()
        
        # Verify all coordinates preserved
        self.assertEqual(len(locations), 4)
        for original, parsed in zip(boundary_coords, locations):
            _, lat_orig, lon_orig, alt_orig = original
            self.assertAlmostEqual(parsed.latitude, lat_orig, places=4)
            self.assertAlmostEqual(parsed.longitude, lon_orig, places=4)
    
    def test_invalid_coordinates_rejected(self):
        """Test that invalid coordinates are properly rejected or sanitized."""
        invalid_coords = [
            (1762090200000, 91.0, 0.0, 10.0),      # Latitude > 90
            (1762090300000, -91.0, 0.0, 10.0),     # Latitude < -90
            (1762090400000, 0.0, 181.0, 10.0),     # Longitude > 180
            (1762090500000, 0.0, -181.0, 10.0),    # Longitude < -180
        ]
        
        db_file = self._create_test_database(self.temp_dir / "invalid.db", invalid_coords)
        exporter = DatabaseExporter(db_file)
        
        gpx_output = self.temp_dir / "invalid.gpx"
        # Export should reject invalid coordinates (raise error or filter them)
        # Since all coordinates are invalid, should raise ValueError
        with self.assertRaises(ValueError) as ctx:
            exporter.export_to_gpx(gpx_output)
        
        self.assertIn("No location points", str(ctx.exception))
    
    def test_concurrent_database_access(self):
        """Test handling of concurrent access to database files."""
        test_data = [(1762090200000, 40.7128, -74.0060, 10.5)]
        db_file = self._create_test_database(self.temp_dir / "concurrent.db", test_data)
        
        # Open multiple parsers simultaneously
        parser1 = DatabaseParser(db_file)
        parser2 = DatabaseParser(db_file)
        
        # Both should be able to read
        locations1 = parser1.get_locations()
        locations2 = parser2.get_locations()
        
        self.assertEqual(len(locations1), 1)
        self.assertEqual(len(locations2), 1)
        self.assertEqual(locations1[0].latitude, locations2[0].latitude)
    
    def test_export_file_overwrite_handling(self):
        """Test that export handles existing files correctly."""
        test_data = [(1762090200000, 40.7128, -74.0060, 10.5)]
        db_file = self._create_test_database(self.temp_dir / "test.db", test_data)
        
        gpx_output = self.temp_dir / "export.gpx"
        
        # Create existing file with different content
        gpx_output.write_text("Old content", encoding='utf-8')
        old_hash = self._compute_file_hash(gpx_output)
        
        # Export should overwrite
        exporter = DatabaseExporter(db_file)
        exporter.export_to_gpx(gpx_output)
        
        new_hash = self._compute_file_hash(gpx_output)
        
        # File should be different
        self.assertNotEqual(old_hash, new_hash)
        
        # New file should be valid GPX
        parser = GPXParser(gpx_output)
        locations = parser.get_locations()
        self.assertEqual(len(locations), 1)
    
    def test_empty_time_range_export(self):
        """Test export with time range that matches no data."""
        test_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),  # Nov 2, 2025 14:30
            (1762093800000, 40.7138, -74.0050, 12.0),  # Nov 2, 2025 15:30
        ]
        db_file = self._create_test_database(self.temp_dir / "test.db", test_data)
        
        exporter = DatabaseExporter(db_file)
        gpx_output = self.temp_dir / "empty_range.gpx"
        
        # Export with time range that doesn't match any data
        start_time = datetime(2025, 11, 1, 0, 0, tzinfo=timezone.utc)
        end_time = datetime(2025, 11, 1, 23, 59, tzinfo=timezone.utc)
        
        # Should raise error when no data matches
        with self.assertRaises(ValueError) as ctx:
            exporter.export_to_gpx(gpx_output, start_time=start_time, end_time=end_time)
        
        self.assertIn("No location points", str(ctx.exception))
    
    def test_null_altitude_handling(self):
        """Test that NULL/missing altitude values are handled correctly."""
        mixed_altitude_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),
            (1762090300000, 40.7138, -74.0050, None),  # NULL altitude
            (1762090400000, 40.7148, -74.0040, 15.0),
        ]
        
        db_file = self._create_test_database(self.temp_dir / "mixed_alt.db", mixed_altitude_data)
        exporter = DatabaseExporter(db_file)
        
        gpx_output = self.temp_dir / "mixed_alt.gpx"
        exporter.export_to_gpx(gpx_output)
        
        # Parse and verify
        parser = GPXParser(gpx_output)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 3)
        self.assertIsNotNone(locations[0].altitude)
        # Second point may have None or 0 for altitude
        self.assertIsNotNone(locations[2].altitude)
    
    def test_timezone_consistency(self):
        """Test that timezone handling is consistent across formats."""
        # Create GPX with explicit timezone
        gpx_content = """<?xml version="1.0"?>
<gpx version="1.1">
  <trk>
    <trkseg>
      <trkpt lat="40.7128" lon="-74.0060">
        <time>2025-11-02T14:30:00Z</time>
      </trkpt>
      <trkpt lat="40.7138" lon="-74.0050">
        <time>2025-11-02T15:30:00+01:00</time>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""
        
        gpx_file = self.temp_dir / "timezone.gpx"
        gpx_file.write_text(gpx_content)
        
        parser = GPXParser(gpx_file)
        locations = parser.get_locations()
        
        # Both timestamps should be parsed (may not be UTC but should have tz)
        self.assertEqual(len(locations), 2)
        for loc in locations:
            # Should have timezone info
            self.assertIsNotNone(loc.timestamp.tzinfo,
                                 msg="Timestamp should have timezone info")
    
    def test_special_characters_in_track_names(self):
        """Test handling of special characters in track/path names."""
        special_names = [
            "Track with spaces",
            "Track-with-dashes",
            "Track_with_underscores",
            "Track.with.dots",
        ]
        
        test_data = [(1762090200000, 40.7128, -74.0060, 10.5)]
        db_file = self._create_test_database(self.temp_dir / "special.db", test_data)
        
        for name in special_names:
            exporter = DatabaseExporter(db_file)
            gpx_output = self.temp_dir / "special.gpx"
            
            # Should handle these characters without crashing
            exporter.export_to_gpx(gpx_output, track_name=name)
            
            # Verify file is valid XML
            tree = ET.parse(str(gpx_output))
            root = tree.getroot()
            # Should have proper namespace
            self.assertTrue('gpx' in root.tag.lower())
    
    def test_file_permission_errors(self):
        """Test handling of file permission errors during export."""
        test_data = [(1762090200000, 40.7128, -74.0060, 10.5)]
        db_file = self._create_test_database(self.temp_dir / "test.db", test_data)
        
        exporter = DatabaseExporter(db_file)
        
        # Try to export to a directory that doesn't exist
        nonexistent_dir = self.temp_dir / "nonexistent" / "subdir" / "export.gpx"
        
        with self.assertRaises(Exception) as ctx:
            exporter.export_to_gpx(nonexistent_dir)
        
        # Should indicate file/path error
        error_msg = str(ctx.exception).lower()
        self.assertTrue('file' in error_msg or 'path' in error_msg or 'directory' in error_msg)
    
    def test_stats_accuracy(self):
        """Test that statistics calculation is accurate."""
        # Create dataset with known properties
        test_data = []
        base_time = 1762090200000  # Nov 2, 2025 14:30:00 UTC
        
        for i in range(100):
            time_ms = base_time + (i * 60000)  # 1 minute intervals
            lat = 40.0 + (i * 0.001)
            lon = -74.0 + (i * 0.001)
            alt = 10.0 + (i * 0.1)
            test_data.append((time_ms, lat, lon, alt))
        
        db_file = self._create_test_database(self.temp_dir / "stats.db", test_data)
        exporter = DatabaseExporter(db_file)
        
        stats = exporter.get_stats()
        
        # Verify statistics
        self.assertEqual(stats['total_points'], 100)
        
        # Time range should be 99 minutes (100 points at 1-minute intervals)
        expected_start = datetime.fromtimestamp(base_time / 1000.0)
        expected_end = datetime.fromtimestamp((base_time + 99 * 60000) / 1000.0)
        
        # get_stats returns time_range tuple, not individual start_time/end_time
        actual_start, actual_end = stats['time_range']
        
        self.assertEqual(actual_start, expected_start)
        self.assertEqual(actual_end, expected_end)
        
        # Duration is a timedelta object
        expected_duration = expected_end - expected_start
        self.assertEqual(stats['duration'], expected_duration)


class TestMatcherIntegrity(unittest.TestCase):
    """Test data integrity in the matching process."""
    
    def setUp(self):
        """Set up test environment."""
        self.temp_dir = Path(tempfile.mkdtemp())
    
    def tearDown(self):
        """Clean up."""
        import gc
        gc.collect()
        try:
            shutil.rmtree(self.temp_dir)
        except:
            pass
    
    def test_interpolation_accuracy(self):
        """Test that interpolation produces mathematically correct results."""
        # Create two location points
        loc1 = LocationPoint(
            latitude=40.0,
            longitude=-74.0,
            altitude=100.0,
            timestamp=datetime(2025, 11, 2, 14, 0, 0, tzinfo=timezone.utc)
        )
        loc2 = LocationPoint(
            latitude=41.0,
            longitude=-73.0,
            altitude=200.0,
            timestamp=datetime(2025, 11, 2, 15, 0, 0, tzinfo=timezone.utc)
        )
        
        locations = [loc1, loc2]
        matcher = TimeMatcher(
            locations=locations,
            interpolate=True,
            max_time_delta=timedelta(hours=2)  # Wide tolerance
        )
        
        # Photo taken exactly halfway
        photo = PhotoMetadata(
            filepath=Path("/tmp/test.jpg"),
            capture_time=datetime(
                2025, 11, 2, 14, 30, 0, tzinfo=timezone.utc
            ),
            timezone_offset=None
        )
        
        result = matcher.match(photo)
        
        # MatchResult has .location which is a LocationPoint
        self.assertIsNotNone(result.location)
        self.assertAlmostEqual(result.location.latitude, 40.5, places=6)
        self.assertAlmostEqual(result.location.longitude, -73.5, places=6)
        if result.location.altitude is not None:
            self.assertAlmostEqual(
                result.location.altitude, 150.0, places=6
            )
    
    def test_no_time_drift_in_matching(self):
        """Test that repeated matching doesn't cause time drift."""
        locations = [
            LocationPoint(
                latitude=40.0,
                longitude=-74.0,
                altitude=100.0,
                timestamp=datetime(
                    2025, 11, 2, 14, 0, 0, tzinfo=timezone.utc
                )
            )
        ]
        
        matcher = TimeMatcher(
            locations=locations,
            max_time_delta=timedelta(minutes=5)
        )
        
        photo = PhotoMetadata(
            filepath=Path("/tmp/test.jpg"),
            capture_time=datetime(
                2025, 11, 2, 14, 1, 0, tzinfo=timezone.utc
            ),
            timezone_offset=None
        )
        
        # Match multiple times
        result1 = matcher.match(photo)
        result2 = matcher.match(photo)
        result3 = matcher.match(photo)
        
        # All results should have valid locations
        self.assertIsNotNone(result1.location)
        self.assertIsNotNone(result2.location)
        self.assertIsNotNone(result3.location)
        
        # Results should be identical - access via .location
        self.assertEqual(
            result1.location.latitude, result2.location.latitude
        )
        self.assertEqual(
            result1.location.latitude, result3.location.latitude
        )
        self.assertEqual(
            result1.location.timestamp, result2.location.timestamp
        )
        self.assertEqual(
            result1.location.timestamp, result3.location.timestamp
        )


if __name__ == '__main__':
    unittest.main()
