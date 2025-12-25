"""
Database to GPS Format Exporter

Export location data from Tracker Android SQLite database to GPX or KML formats.
"""

import logging
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import List, Optional

from models import LocationPoint

logger = logging.getLogger(__name__)


class DatabaseExporter:
    """
    Export Tracker Android database to GPS track formats.
    
    Supports:
    - GPX export (GPS Exchange Format)
    - KML export (Keyhole Markup Language)
    """
    
    def __init__(self, db_file: Path):
        """
        Initialize database exporter.
        
        Args:
            db_file: Path to SQLite database file
            
        Raises:
            FileNotFoundError: If database file doesn't exist
            ValueError: If database schema is invalid
        """
        if not db_file.exists():
            raise FileNotFoundError(f"Database not found: {db_file}")
        
        self.db_file = db_file
        self._validate_schema()
    
    def _validate_schema(self):
        """Validate database has location_data table."""
        conn = sqlite3.connect(str(self.db_file))
        cursor = conn.cursor()
        
        cursor.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name='location_data'"
        )
        
        if not cursor.fetchone():
            conn.close()
            raise ValueError("Database missing 'location_data' table")
        
        conn.close()
    
    def _get_locations(
        self,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None
    ) -> List[LocationPoint]:
        """
        Retrieve locations from database.
        
        Args:
            start_time: Optional start time filter
            end_time: Optional end time filter
            
        Returns:
            List of location points sorted by timestamp
        """
        conn = sqlite3.connect(str(self.db_file))
        cursor = conn.cursor()
        
        # Build query with optional time filters
        query = "SELECT time, lat, lon, altitude FROM location_data"
        params = []
        
        if start_time or end_time:
            query += " WHERE"
            if start_time:
                query += " time >= ?"
                params.append(int(start_time.timestamp() * 1000))
            if end_time:
                if start_time:
                    query += " AND"
                query += " time <= ?"
                params.append(int(end_time.timestamp() * 1000))
        
        query += " ORDER BY time ASC"
        
        cursor.execute(query, params)
        rows = cursor.fetchall()
        conn.close()
        
        locations = []
        for row in rows:
            time_ms, lat, lon, alt = row
            
            # Validate coordinates
            if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
                logger.warning(
                    f"Skipping invalid coordinates: lat={lat}, lon={lon}"
                )
                continue
            
            # Convert epoch milliseconds to datetime
            timestamp = datetime.fromtimestamp(time_ms / 1000.0)
            
            locations.append(
                LocationPoint(
                    timestamp=timestamp,
                    latitude=lat,
                    longitude=lon,
                    altitude=alt if alt is not None else None,
                )
            )
        
        return locations
    
    def export_to_gpx(
        self,
        output_file: Path,
        track_name: str = "Tracker Export",
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
    ) -> int:
        """
        Export database locations to GPX format.
        
        Args:
            output_file: Output GPX file path
            track_name: Name for the GPS track
            start_time: Optional start time filter
            end_time: Optional end time filter
            
        Returns:
            Number of points exported
        """
        locations = self._get_locations(start_time, end_time)
        
        if not locations:
            raise ValueError("No location points to export")
        
        # Write GPX file
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
            f.write('<gpx version="1.1" creator="Tracker Database Exporter" ')
            f.write('xmlns="http://www.topografix.com/GPX/1/1">\n')
            f.write(f'  <trk>\n')
            f.write(f'    <name>{track_name}</name>\n')
            f.write(f'    <trkseg>\n')
            
            for loc in locations:
                f.write(
                    f'      <trkpt lat="{loc.latitude:.8f}" '
                    f'lon="{loc.longitude:.8f}">\n'
                )
                
                if loc.altitude is not None:
                    f.write(f'        <ele>{loc.altitude:.2f}</ele>\n')
                
                # Format timestamp in ISO 8601
                time_str = loc.timestamp.strftime('%Y-%m-%dT%H:%M:%SZ')
                f.write(f'        <time>{time_str}</time>\n')
                f.write(f'      </trkpt>\n')
            
            f.write('    </trkseg>\n')
            f.write('  </trk>\n')
            f.write('</gpx>\n')
        
        logger.info(
            f"Exported {len(locations)} points to GPX: {output_file}"
        )
        return len(locations)
    
    def export_to_kml(
        self,
        output_file: Path,
        track_name: str = "Tracker Export",
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
    ) -> int:
        """
        Export database locations to KML format.
        
        Args:
            output_file: Output KML file path
            track_name: Name for the track
            start_time: Optional start time filter
            end_time: Optional end time filter
            
        Returns:
            Number of points exported
        """
        locations = self._get_locations(start_time, end_time)
        
        if not locations:
            raise ValueError("No location points to export")
        
        # Write KML file
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
            f.write('<kml xmlns="http://www.opengis.net/kml/2.2">\n')
            f.write('  <Document>\n')
            f.write(f'    <name>{track_name}</name>\n')
            
            for loc in locations:
                f.write('    <Placemark>\n')
                
                # TimeStamp
                time_str = loc.timestamp.strftime('%Y-%m-%dT%H:%M:%S')
                f.write('      <TimeStamp>\n')
                f.write(f'        <when>{time_str}</when>\n')
                f.write('      </TimeStamp>\n')
                
                # Point coordinates (KML uses lon,lat,alt order)
                f.write('      <Point>\n')
                if loc.altitude is not None:
                    coords = (
                        f'{loc.longitude:.8f},{loc.latitude:.8f},'
                        f'{loc.altitude:.2f}'
                    )
                else:
                    coords = f'{loc.longitude:.8f},{loc.latitude:.8f}'
                
                f.write(f'        <coordinates>{coords}</coordinates>\n')
                f.write('      </Point>\n')
                f.write('    </Placemark>\n')
            
            f.write('  </Document>\n')
            f.write('</kml>\n')
        
        logger.info(
            f"Exported {len(locations)} points to KML: {output_file}"
        )
        return len(locations)
    
    def get_stats(self) -> dict:
        """
        Get statistics about the database.
        
        Returns:
            Dictionary with database statistics
        """
        locations = self._get_locations()
        
        if not locations:
            return {
                'total_points': 0,
                'time_range': None,
                'duration': None,
            }
        
        duration = locations[-1].timestamp - locations[0].timestamp
        
        return {
            'total_points': len(locations),
            'time_range': (locations[0].timestamp, locations[-1].timestamp),
            'duration': duration,
            'has_altitude': any(
                loc.altitude is not None for loc in locations
            ),
        }
