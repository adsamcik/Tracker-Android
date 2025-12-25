"""
Tracker Photo Geotagger - GPX Parser

Parse GPX files exported from Tracker Android.
"""

import logging
from datetime import datetime
from pathlib import Path
from typing import List

import gpxpy
import gpxpy.gpx

from models import LocationPoint

logger = logging.getLogger(__name__)


class GPXParser:
    """Parser for GPX track files."""
    
    def __init__(self, gpx_file: Path):
        """
        Initialize GPX parser.
        
        Args:
            gpx_file: Path to GPX file
            
        Raises:
            FileNotFoundError: If GPX file doesn't exist
            ValueError: If GPX file is invalid
        """
        self.gpx_file = gpx_file
        if not gpx_file.exists():
            raise FileNotFoundError(f"GPX file not found: {gpx_file}")
        
        self.gpx: gpxpy.gpx.GPX = self._parse()
        self.locations: List[LocationPoint] = self._extract_locations()
        
        logger.info(f"Parsed GPX file: {len(self.locations)} location points")
        if self.locations:
            logger.info(f"  Time range: {self.locations[0].timestamp} to {self.locations[-1].timestamp}")
    
    def _parse(self) -> gpxpy.gpx.GPX:
        """
        Parse GPX file.
        
        Returns:
            Parsed GPX object
            
        Raises:
            ValueError: If GPX parsing fails
        """
        try:
            with open(self.gpx_file, 'r', encoding='utf-8') as f:
                gpx = gpxpy.parse(f)
            return gpx
        except Exception as e:
            raise ValueError(f"Failed to parse GPX file: {e}") from e
    
    def _extract_locations(self) -> List[LocationPoint]:
        """
        Extract location points from GPX tracks.
        
        Returns:
            List of location points sorted by timestamp
        """
        locations = []
        
        for track in self.gpx.tracks:
            for segment in track.segments:
                for point in segment.points:
                    # Skip points without timestamp
                    if point.time is None:
                        logger.warning(f"Skipping point without timestamp: lat={point.latitude}, lon={point.longitude}")
                        continue
                    
                    try:
                        location = LocationPoint(
                            timestamp=point.time,
                            latitude=point.latitude,
                            longitude=point.longitude,
                            altitude=point.elevation,
                            speed=point.speed,
                        )
                        locations.append(location)
                    except ValueError as e:
                        logger.warning(f"Skipping invalid point: {e}")
                        continue
        
        # Sort by timestamp for efficient matching
        locations.sort(key=lambda loc: loc.timestamp)
        
        return locations
    
    def get_time_range(self) -> tuple[datetime, datetime]:
        """
        Get time range covered by track.
        
        Returns:
            Tuple of (start_time, end_time) in UTC
            
        Raises:
            ValueError: If no locations available
        """
        if not self.locations:
            raise ValueError("No locations in GPX file")
        
        return self.locations[0].timestamp, self.locations[-1].timestamp
    
    def get_locations(self) -> List[LocationPoint]:
        """
        Get all location points.
        
        Returns:
            List of location points sorted by timestamp
        """
        return self.locations
