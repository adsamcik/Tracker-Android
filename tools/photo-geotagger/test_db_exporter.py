"""
Tests for database export functionality.
"""

import unittest
from datetime import datetime
from pathlib import Path
import tempfile
import shutil
import sqlite3
import xml.etree.ElementTree as ET

from db_exporter import DatabaseExporter


class TestDatabaseExporter(unittest.TestCase):
    """Test database to GPS format export."""
    
    def setUp(self):
        """Create temporary directory and test database."""
        self.temp_dir = Path(tempfile.mkdtemp())
        self.db_file = self.temp_dir / "test.db"
        self.connections = []
    
    def tearDown(self):
        """Clean up temporary files."""
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
    
    def _create_test_database(self, locations):
        """Helper to create test database with location data."""
        conn = sqlite3.connect(str(self.db_file))
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
                "INSERT INTO location_data (time, lat, lon, altitude) "
                "VALUES (?, ?, ?, ?)",
                loc
            )
        
        conn.commit()
        conn.close()
    
    def test_export_to_gpx(self):
        """Test exporting database to GPX format."""
        test_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),
            (1762090500000, 40.7138, -74.0050, 12.0),
        ]
        self._create_test_database(test_data)
        
        exporter = DatabaseExporter(self.db_file)
        output_file = self.temp_dir / "export.gpx"
        
        count = exporter.export_to_gpx(output_file, track_name="Test Track")
        
        self.assertEqual(count, 2)
        self.assertTrue(output_file.exists())
        
        # Validate GPX content
        tree = ET.parse(output_file)
        root = tree.getroot()
        
        # Find all trackpoints
        ns = {'gpx': 'http://www.topografix.com/GPX/1/1'}
        trkpts = root.findall('.//gpx:trkpt', ns)
        
        self.assertEqual(len(trkpts), 2)
        self.assertAlmostEqual(
            float(trkpts[0].get('lat')), 40.7128, places=4
        )
        self.assertAlmostEqual(
            float(trkpts[0].get('lon')), -74.0060, places=4
        )
    
    def test_export_to_kml(self):
        """Test exporting database to KML format."""
        test_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),
            (1762090500000, 40.7138, -74.0050, 12.0),
        ]
        self._create_test_database(test_data)
        
        exporter = DatabaseExporter(self.db_file)
        output_file = self.temp_dir / "export.kml"
        
        count = exporter.export_to_kml(output_file, track_name="Test Track")
        
        self.assertEqual(count, 2)
        self.assertTrue(output_file.exists())
        
        # Validate KML content
        tree = ET.parse(output_file)
        root = tree.getroot()
        
        # Find all placemarks
        ns = {'kml': 'http://www.opengis.net/kml/2.2'}
        placemarks = root.findall('.//kml:Placemark', ns)
        
        self.assertEqual(len(placemarks), 2)
    
    def test_export_with_time_filter(self):
        """Test exporting with time range filter."""
        test_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),  # 14:30
            (1762090500000, 40.7138, -74.0050, 12.0),  # 14:35
            (1762090800000, 40.7148, -74.0040, 13.5),  # 14:40
        ]
        self._create_test_database(test_data)
        
        exporter = DatabaseExporter(self.db_file)
        output_file = self.temp_dir / "filtered.gpx"
        
        # Filter to middle point only
        start = datetime(2025, 11, 2, 14, 33)
        end = datetime(2025, 11, 2, 14, 37)
        
        count = exporter.export_to_gpx(
            output_file,
            start_time=start,
            end_time=end
        )
        
        self.assertEqual(count, 1)
    
    def test_export_without_altitude(self):
        """Test exporting data without altitude."""
        test_data = [
            (1762090200000, 40.7128, -74.0060, None),
            (1762090500000, 40.7138, -74.0050, None),
        ]
        self._create_test_database(test_data)
        
        exporter = DatabaseExporter(self.db_file)
        output_file = self.temp_dir / "no_altitude.gpx"
        
        count = exporter.export_to_gpx(output_file)
        
        self.assertEqual(count, 2)
        # Verify file doesn't have <ele> tags
        content = output_file.read_text()
        self.assertNotIn('<ele>', content)
    
    def test_get_stats(self):
        """Test database statistics."""
        test_data = [
            (1762090200000, 40.7128, -74.0060, 10.5),
            (1762090500000, 40.7138, -74.0050, 12.0),
        ]
        self._create_test_database(test_data)
        
        exporter = DatabaseExporter(self.db_file)
        stats = exporter.get_stats()
        
        self.assertEqual(stats['total_points'], 2)
        self.assertIsNotNone(stats['time_range'])
        self.assertTrue(stats['has_altitude'])
    
    def test_empty_database_export(self):
        """Test error handling for empty database."""
        self._create_test_database([])
        
        exporter = DatabaseExporter(self.db_file)
        output_file = self.temp_dir / "empty.gpx"
        
        with self.assertRaises(ValueError):
            exporter.export_to_gpx(output_file)
    
    def test_missing_database_file(self):
        """Test error handling for missing database."""
        missing_file = self.temp_dir / "nonexistent.db"
        
        with self.assertRaises(FileNotFoundError):
            DatabaseExporter(missing_file)
    
    def test_invalid_database_schema(self):
        """Test error handling for wrong schema."""
        conn = sqlite3.connect(str(self.db_file))
        self.connections.append(conn)
        cursor = conn.cursor()
        cursor.execute("CREATE TABLE wrong_table (id INTEGER)")
        conn.commit()
        conn.close()
        
        with self.assertRaises(ValueError):
            DatabaseExporter(self.db_file)


if __name__ == '__main__':
    unittest.main()
