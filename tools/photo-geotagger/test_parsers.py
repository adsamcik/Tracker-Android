"""
Unit tests for GPX, KML, and Database parsers.
"""

import unittest
from datetime import datetime
from pathlib import Path
import tempfile
import sqlite3
import shutil

from gpx_parser import GPXParser
from kml_parser import KmlParser
from db_parser import DatabaseParser
from models import LocationPoint


class TestGPXParser(unittest.TestCase):
    """Test GPX parser with various valid and invalid inputs."""
    
    def setUp(self):
        """Create temporary directory for test files."""
        self.temp_dir = Path(tempfile.mkdtemp())
    
    def tearDown(self):
        """Clean up temporary files."""
        shutil.rmtree(self.temp_dir)
    
    def test_parse_valid_gpx(self):
        """Test parsing a valid GPX file."""
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Tracker">
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
        
        gpx_file = self.temp_dir / "test.gpx"
        gpx_file.write_text(gpx_content)
        
        parser = GPXParser(gpx_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 2)
        self.assertAlmostEqual(locations[0].latitude, 40.7128)
        self.assertAlmostEqual(locations[0].longitude, -74.0060)
        self.assertAlmostEqual(locations[0].altitude, 10.5)
        self.assertEqual(locations[0].timestamp.year, 2025)
    
    def test_gpx_without_altitude(self):
        """Test GPX waypoints without altitude data."""
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1">
  <trk>
    <trkseg>
      <trkpt lat="40.7128" lon="-74.0060">
        <time>2025-11-02T14:30:00Z</time>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""
        
        gpx_file = self.temp_dir / "test.gpx"
        gpx_file.write_text(gpx_content)
        
        parser = GPXParser(gpx_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 1)
        self.assertIsNone(locations[0].altitude)
    
    def test_gpx_missing_file(self):
        """Test error handling for missing GPX file."""
        with self.assertRaises(FileNotFoundError):
            GPXParser(self.temp_dir / "nonexistent.gpx")
    
    def test_gpx_invalid_xml(self):
        """Test error handling for invalid XML."""
        gpx_file = self.temp_dir / "invalid.gpx"
        gpx_file.write_text("This is not XML")
        
        with self.assertRaises(ValueError):
            GPXParser(gpx_file)
    
    def test_gpx_empty_track(self):
        """Test parsing GPX with no waypoints returns empty list."""
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1">
  <trk>
    <trkseg>
    </trkseg>
  </trk>
</gpx>"""
        
        gpx_file = self.temp_dir / "empty.gpx"
        gpx_file.write_text(gpx_content)
        
        parser = GPXParser(gpx_file)
        locations = parser.get_locations()
        self.assertEqual(len(locations), 0)


class TestKMLParser(unittest.TestCase):
    """Test KML parser with various valid and invalid inputs."""
    
    def setUp(self):
        """Create temporary directory for test files."""
        self.temp_dir = Path(tempfile.mkdtemp())
    
    def tearDown(self):
        """Clean up temporary files."""
        shutil.rmtree(self.temp_dir)
    
    def test_parse_valid_kml(self):
        """Test parsing a valid KML file (Tracker format)."""
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
        
        kml_file = self.temp_dir / "test.kml"
        kml_file.write_text(kml_content)
        
        parser = KmlParser(kml_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 2)
        # KML uses lon,lat,alt order
        self.assertAlmostEqual(locations[0].latitude, 40.7128)
        self.assertAlmostEqual(locations[0].longitude, -74.0060)
        self.assertAlmostEqual(locations[0].altitude, 10.5)
    
    def test_kml_without_namespace(self):
        """Test KML without namespace declaration."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml>
  <Document>
    <Placemark>
      <TimeStamp><when>2025-11-02T14:30:00</when></TimeStamp>
      <Point><coordinates>-74.0060,40.7128</coordinates></Point>
    </Placemark>
  </Document>
</kml>"""
        
        kml_file = self.temp_dir / "test.kml"
        kml_file.write_text(kml_content)
        
        parser = KmlParser(kml_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 1)
        self.assertIsNone(locations[0].altitude)
    
    def test_kml_missing_timestamp(self):
        """Test KML placemarks without timestamps are skipped."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <Point><coordinates>-74.0060,40.7128,10.5</coordinates></Point>
    </Placemark>
  </Document>
</kml>"""
        
        kml_file = self.temp_dir / "test.kml"
        kml_file.write_text(kml_content)
        
        with self.assertRaises(ValueError):
            KmlParser(kml_file)
    
    def test_kml_invalid_coordinates(self):
        """Test KML with invalid coordinate format."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <TimeStamp><when>2025-11-02T14:30:00</when></TimeStamp>
      <Point><coordinates>invalid</coordinates></Point>
    </Placemark>
  </Document>
</kml>"""
        
        kml_file = self.temp_dir / "test.kml"
        kml_file.write_text(kml_content)
        
        with self.assertRaises(ValueError):
            KmlParser(kml_file)


class TestDatabaseParser(unittest.TestCase):
    """Test SQLite database parser."""
    
    def setUp(self):
        """Create temporary directory and test database."""
        self.temp_dir = Path(tempfile.mkdtemp())
        self.db_file = self.temp_dir / "test.db"
        self.connections = []  # Track connections for cleanup
    
    def tearDown(self):
        """Clean up temporary files."""
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
    
    def _create_test_database(self, locations):
        """Helper to create test database with location data."""
        conn = sqlite3.connect(str(self.db_file))
        self.connections.append(conn)  # Track for cleanup
        cursor = conn.cursor()
        
        # Create location_data table (simplified Tracker schema)
        cursor.execute("""
            CREATE TABLE location_data (
                id INTEGER PRIMARY KEY,
                time INTEGER,
                lat REAL,
                lon REAL,
                altitude REAL
            )
        """)
        
        # Insert test data
        for loc in locations:
            cursor.execute(
                "INSERT INTO location_data (time, lat, lon, altitude) "
                "VALUES (?, ?, ?, ?)",
                loc
            )
        
        conn.commit()
        self.connections.append(conn)  # Track for cleanup
        conn.close()
    
    def test_parse_valid_database(self):
        """Test parsing a valid database."""
        # Create test data (time in epoch milliseconds)
        test_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),  # 2025-11-02 14:30:00
            (1762090500000, 40.7138, -74.0050, 12.0),  # 2025-11-02 14:35:00
        ]
        self._create_test_database(test_data)
        
        parser = DatabaseParser(self.db_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 2)
        self.assertAlmostEqual(locations[0].latitude, 40.7128)
        self.assertAlmostEqual(locations[0].longitude, -74.0060)
        self.assertAlmostEqual(locations[0].altitude, 10.5)
    
    def test_database_without_altitude(self):
        """Test database with NULL altitude values."""
        test_data = [
            (1762090200000, 40.7128, -74.0060, None),
        ]
        self._create_test_database(test_data)
        
        parser = DatabaseParser(self.db_file)
        locations = parser.get_locations()
        
        self.assertEqual(len(locations), 1)
        self.assertIsNone(locations[0].altitude)
    
    def test_database_missing_table(self):
        """Test error handling for database without location_data table."""
        conn = sqlite3.connect(str(self.db_file))
        cursor = conn.cursor()
        cursor.execute("CREATE TABLE wrong_table (id INTEGER)")
        conn.commit()
        conn.close()
        
        with self.assertRaises(ValueError):
            DatabaseParser(self.db_file)
    
    def test_database_empty_table(self):
        """Test error handling for empty location_data table."""
        self._create_test_database([])
        
        with self.assertRaises(ValueError):
            DatabaseParser(self.db_file)
    
    def test_database_invalid_coordinates(self):
        """Test database with invalid coordinate ranges raises error."""
        test_data = [
            (1762090200000, 91.0, -74.0060, 10.5),  # Invalid lat > 90
            (1762090500000, 40.7138, -181.0, 12.0),  # Invalid lon < -180
        ]
        self._create_test_database(test_data)
        
        # Should raise ValueError because no valid points
        with self.assertRaises(ValueError):
            DatabaseParser(self.db_file)
    
    def test_get_locations_in_range(self):
        """Test filtering locations by time range."""
        test_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),  # 2025-11-02 14:30:00
            (1762090500000, 40.7138, -74.0050, 12.0),  # 2025-11-02 14:35:00
            (1762090800000, 40.7148, -74.0040, 13.5),  # 2025-11-02 14:40:00
        ]
        self._create_test_database(test_data)
        
        parser = DatabaseParser(self.db_file)
        
        # Filter to middle location only
        start = datetime(2025, 11, 2, 14, 33)
        end = datetime(2025, 11, 2, 14, 37)
        filtered = parser.get_locations_in_range(start, end)
        
        self.assertEqual(len(filtered), 1)
        self.assertAlmostEqual(filtered[0].latitude, 40.7138)


class TestLocationPoint(unittest.TestCase):
    """Test LocationPoint model."""
    
    def test_location_point_creation(self):
        """Test creating a LocationPoint."""
        timestamp = datetime(2025, 11, 2, 14, 30)
        location = LocationPoint(
            timestamp=timestamp,
            latitude=40.7128,
            longitude=-74.0060,
            altitude=10.5
        )
        
        self.assertEqual(location.timestamp, timestamp)
        self.assertAlmostEqual(location.latitude, 40.7128)
        self.assertAlmostEqual(location.longitude, -74.0060)
        self.assertAlmostEqual(location.altitude, 10.5)
    
    def test_location_point_without_altitude(self):
        """Test LocationPoint with no altitude."""
        location = LocationPoint(
            timestamp=datetime(2025, 11, 2, 14, 30),
            latitude=40.7128,
            longitude=-74.0060
        )
        
        self.assertIsNone(location.altitude)
    
    def test_location_point_immutable(self):
        """Test that LocationPoint is immutable (frozen dataclass)."""
        location = LocationPoint(
            timestamp=datetime(2025, 11, 2, 14, 30),
            latitude=40.7128,
            longitude=-74.0060
        )
        
        with self.assertRaises(Exception):  # FrozenInstanceError
            location.latitude = 50.0


if __name__ == '__main__':
    unittest.main()
