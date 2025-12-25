"""
Tracker Photo Geotagger - SQLite Database Parser

Parses SQLite database files exported from Tracker Android to extract
location data.
"""

import logging
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import List

from models import LocationPoint

logger = logging.getLogger(__name__)


class DatabaseParser:
    """
    Parser for Tracker Android SQLite database exports.
    
    Queries the location_data table to extract GPS coordinates,
    timestamps, and altitude information.
    
    Database schema (location_data table):
    - id: INTEGER PRIMARY KEY
    - time: INTEGER (epoch milliseconds)
    - lat: REAL (latitude in degrees)
    - lon: REAL (longitude in degrees)
    - altitude: REAL (meters, nullable)
    - accuracy: REAL (meters, nullable)
    - speed: REAL (m/s, nullable)
    - ... additional fields
    """
    
    def __init__(self, db_path: Path):
        """
        Initialize database parser.
        
        Args:
            db_path: Path to SQLite database file
            
        Raises:
            FileNotFoundError: If database file doesn't exist
            ValueError: If database is invalid or corrupted
        """
        if not db_path.exists():
            raise FileNotFoundError(f"Database file not found: {db_path}")
        
        self.db_path = db_path
        self._locations: List[LocationPoint] = []
        self._parse()
    
    def _parse(self):
        """
        Parse database and extract location points.
        
        Raises:
            ValueError: If database parsing fails or no valid points found
        """
        try:
            conn = sqlite3.connect(str(self.db_path))
            cursor = conn.cursor()
            
            # Verify table exists
            cursor.execute(
                "SELECT name FROM sqlite_master "
                "WHERE type='table' AND name='location_data'"
            )
            if not cursor.fetchone():
                raise ValueError(
                    "Database missing 'location_data' table. "
                    "Is this a Tracker Android export?"
                )
            
            # Query all location data, ordered by time
            # Using column names that match DatabaseLocation entity
            query = """
                SELECT time, lat, lon, altitude
                FROM location_data
                ORDER BY time ASC
            """
            
            cursor.execute(query)
            rows = cursor.fetchall()
            
            if not rows:
                raise ValueError("No location data found in database")
            
            # Convert rows to LocationPoint objects
            for row in rows:
                time_ms, lat, lon, altitude = row
                
                # Validate coordinates
                if not self._validate_coordinates(lat, lon):
                    logger.debug(
                        f"Skipping invalid coordinates: "
                        f"lat={lat}, lon={lon}"
                    )
                    continue
                
                # Convert epoch milliseconds to datetime
                timestamp = datetime.fromtimestamp(time_ms / 1000.0)
                
                location = LocationPoint(
                    timestamp=timestamp,
                    latitude=lat,
                    longitude=lon,
                    altitude=altitude if altitude is not None else None
                )
                
                self._locations.append(location)
            
            conn.close()
            
            if not self._locations:
                raise ValueError("No valid location points in database")
            
            logger.info(
                f"Parsed {len(self._locations)} locations from database"
            )
            
        except sqlite3.Error as e:
            raise ValueError(f"Database error: {e}")
        except Exception as e:
            raise ValueError(f"Failed to parse database: {e}")
    
    def _validate_coordinates(
        self,
        latitude: float,
        longitude: float
    ) -> bool:
        """
        Validate GPS coordinates are within valid ranges.
        
        Args:
            latitude: Latitude in degrees
            longitude: Longitude in degrees
            
        Returns:
            True if valid, False otherwise
        """
        if not (-90 <= latitude <= 90):
            return False
        if not (-180 <= longitude <= 180):
            return False
        return True
    
    def get_locations(self) -> List[LocationPoint]:
        """
        Get parsed locations sorted by timestamp.
        
        Returns:
            List of LocationPoint objects
        """
        return self._locations.copy()
    
    def get_locations_in_range(
        self,
        start_time: datetime,
        end_time: datetime
    ) -> List[LocationPoint]:
        """
        Get locations within a specific time range.
        
        Useful for filtering large database exports to match
        photo capture time ranges.
        
        Args:
            start_time: Start of time range (inclusive)
            end_time: End of time range (inclusive)
            
        Returns:
            Filtered list of LocationPoint objects
        """
        return [
            loc for loc in self._locations
            if start_time <= loc.timestamp <= end_time
        ]
