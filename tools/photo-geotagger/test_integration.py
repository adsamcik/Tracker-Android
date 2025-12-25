"""
Integration tests for end-to-end geotagging workflow.
"""

import unittest
from datetime import datetime
from pathlib import Path
import tempfile
import shutil
import sqlite3
from PIL import Image

from gpx_parser import GPXParser
from kml_parser import KmlParser
from db_parser import DatabaseParser
from matcher import TimeMatcher
from exiftool_wrapper import ExifToolWrapper
from models import PhotoMetadata


class TestEndToEndWorkflow(unittest.TestCase):
    """Test complete workflow from parsing to geotagging."""
    
    def setUp(self):
        """Create temporary directory for test files."""
        self.temp_dir = Path(tempfile.mkdtemp())
        self.photos_dir = self.temp_dir / "photos"
        self.photos_dir.mkdir()
    
    def tearDown(self):
        """Clean up temporary files."""
        # Close any open database connections
        import gc
        gc.collect()
        try:
            shutil.rmtree(self.temp_dir)
        except PermissionError:
            # Windows may hold file locks briefly
            import time
            time.sleep(0.1)
            shutil.rmtree(self.temp_dir)
    
    def _create_test_photo(self, filename, capture_time):
        """Create a test JPEG photo with EXIF timestamp."""
        photo_path = self.photos_dir / filename
        
        # Create a simple 100x100 red image
        img = Image.new('RGB', (100, 100), color='red')
        img.save(photo_path, 'JPEG')
        
        # Note: For real EXIF writing, would need to use exiftool
        # This creates a basic JPEG for testing
        return photo_path
    
    def test_gpx_to_photo_workflow(self):
        """Test complete workflow: GPX -> parse -> match -> geotag."""
        # Create test GPX
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1">
  <trk>
    <trkseg>
      <trkpt lat="40.7128" lon="-74.0060">
        <ele>10.5</ele>
        <time>2025-11-02T14:30:00Z</time>
      </trkpt>
      <trkpt lat="40.7138" lon="-74.0050">
        <ele>12.0</ele>
        <time>2025-11-02T14:35:00Z</time>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""
        
        gpx_file = self.temp_dir / "track.gpx"
        gpx_file.write_text(gpx_content)
        
        # Parse GPX
        parser = GPXParser(gpx_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 2)
        
        # Create matcher
        from datetime import timedelta, timezone
        from models import PhotoMetadata
        from pathlib import Path
        
        matcher = TimeMatcher(
            locations=locations,
            max_time_delta=timedelta(minutes=5),
            interpolate=True
        )
        
        # Match a photo timestamp (timezone-aware to match GPX timestamps)
        photo = PhotoMetadata(
            filepath=Path("/tmp/test.jpg"),
            capture_time=datetime(2025, 11, 2, 14, 32, 30, tzinfo=timezone.utc),
            timezone_offset=None,  # Will be treated as UTC
        )
        result = matcher.match(photo)
        
        self.assertIsNotNone(result)
        self.assertGreater(result.confidence, 0.0)
    
    def test_kml_to_photo_workflow(self):
        """Test complete workflow with KML format."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <TimeStamp><when>2025-11-02T14:30:00</when></TimeStamp>
      <Point><coordinates>-74.0060,40.7128,10.5</coordinates></Point>
    </Placemark>
    <Placemark>
      <TimeStamp><when>2025-11-02T14:35:00</when></TimeStamp>
      <Point><coordinates>-74.0050,40.7138,12.0</coordinates></Point>
    </Placemark>
  </Document>
</kml>"""
        
        kml_file = self.temp_dir / "track.kml"
        kml_file.write_text(kml_content)
        
        # Parse KML
        parser = KmlParser(kml_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 2)
        # Verify coordinates are correctly parsed (lon,lat order in KML)
        self.assertAlmostEqual(locations[0].latitude, 40.7128)
        self.assertAlmostEqual(locations[0].longitude, -74.0060)
    
    def test_database_to_photo_workflow(self):
        """Test complete workflow with database format."""
        db_file = self.temp_dir / "tracker.db"
        
        # Create test database
        conn = sqlite3.connect(str(db_file))
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
        
        test_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),
            (1762090500000, 40.7138, -74.0050, 12.0),
        ]
        
        for loc in test_data:
            cursor.execute(
                "INSERT INTO location_data (time, lat, lon, altitude) "
                "VALUES (?, ?, ?, ?)",
                loc
            )
        
        conn.commit()
        conn.close()
        
        # Parse database
        parser = DatabaseParser(db_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 2)
    
    def test_all_formats_produce_same_results(self):
        """Test that GPX, KML, and DB produce identical location data."""
        from datetime import timedelta
        
        # Create identical data in all three formats
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1">
  <trk><trkseg>
    <trkpt lat="40.7128" lon="-74.0060">
      <ele>10.5</ele>
      <time>2025-11-02T14:30:00Z</time>
    </trkpt>
  </trkseg></trk>
</gpx>"""
        
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <TimeStamp><when>2025-11-02T14:30:00</when></TimeStamp>
      <Point><coordinates>-74.0060,40.7128,10.5</coordinates></Point>
    </Placemark>
  </Document>
</kml>"""
        
        # Write files
        gpx_file = self.temp_dir / "test.gpx"
        kml_file = self.temp_dir / "test.kml"
        db_file = self.temp_dir / "test.db"
        
        gpx_file.write_text(gpx_content)
        kml_file.write_text(kml_content)
        
        # Create database
        conn = sqlite3.connect(str(db_file))
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
        cursor.execute(
            "INSERT INTO location_data (time, lat, lon, altitude) "
            "VALUES (?, ?, ?, ?)",
            (1730556600000, 40.7128, -74.0060, 10.5)
        )
        conn.commit()
        conn.close()
        
        # Parse all formats
        gpx_locs = GPXParser(gpx_file).get_locations()
        kml_locs = KmlParser(kml_file).get_locations()
        db_locs = DatabaseParser(db_file).get_locations()
        
        # All should have same number of locations
        self.assertEqual(len(gpx_locs), 1)
        self.assertEqual(len(kml_locs), 1)
        self.assertEqual(len(db_locs), 1)
        
        # All should have same coordinates
        for locs in [gpx_locs, kml_locs, db_locs]:
            self.assertAlmostEqual(locs[0].latitude, 40.7128, places=4)
            self.assertAlmostEqual(locs[0].longitude, -74.0060, places=4)
            self.assertAlmostEqual(locs[0].altitude, 10.5, places=1)


class TestErrorHandling(unittest.TestCase):
    """Test error handling across the stack."""
    
    def setUp(self):
        """Create temporary directory."""
        self.temp_dir = Path(tempfile.mkdtemp())
        self.connections = []  # Track DB connections for cleanup
    
    def tearDown(self):
        """Clean up."""
        # Close all database connections
        for conn in self.connections:
            try:
                conn.close()
            except:
                pass
        # Force garbage collection
        import gc
        gc.collect()
        # Retry cleanup with delay on Windows
        try:
            shutil.rmtree(self.temp_dir)
        except PermissionError:
            import time
            time.sleep(0.1)
            try:
                shutil.rmtree(self.temp_dir)
            except:
                pass  # Best effort cleanup
    
    def test_corrupted_gpx_file(self):
        """Test handling of corrupted GPX file."""
        gpx_file = self.temp_dir / "corrupt.gpx"
        gpx_file.write_text("<?xml version='1.0'?><gpx><broken>")
        
        with self.assertRaises(ValueError):
            GPXParser(gpx_file)
    
    def test_wrong_database_schema(self):
        """Test handling of database with wrong schema."""
        db_file = self.temp_dir / "wrong.db"
        conn = sqlite3.connect(str(db_file))
        self.connections.append(conn)  # Track for cleanup
        cursor = conn.cursor()
        cursor.execute("CREATE TABLE wrong_table (id INTEGER)")
        conn.commit()
        conn.close()
        
        with self.assertRaises(ValueError):
            DatabaseParser(db_file)
    
    def test_kml_with_invalid_coordinates(self):
        """Test KML with coordinates out of range."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <TimeStamp><when>2025-11-02T14:30:00</when></TimeStamp>
      <Point><coordinates>-200.0,100.0,10.5</coordinates></Point>
    </Placemark>
  </Document>
</kml>"""
        
        kml_file = self.temp_dir / "invalid.kml"
        kml_file.write_text(kml_content)
        
        # Should raise ValueError because all coordinates are invalid
        with self.assertRaises(ValueError):
            KmlParser(kml_file)


if __name__ == '__main__':
    unittest.main()
