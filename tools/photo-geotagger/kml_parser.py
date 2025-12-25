"""
Tracker Photo Geotagger - KML Parser

Parses KML files exported from Tracker Android to extract location points.
"""

import logging
from datetime import datetime
from pathlib import Path
from typing import List
from lxml import etree

from models import LocationPoint

logger = logging.getLogger(__name__)


class KmlParser:
    """
    Parser for KML (Keyhole Markup Language) files.
    
    Supports KML files exported from Tracker Android with Placemarks
    containing coordinates and timestamps.
    
    KML coordinate format: longitude,latitude,altitude
    (Note: Order differs from GPX which uses latitude,longitude)
    """
    
    def __init__(self, kml_path: Path):
        """
        Initialize KML parser.
        
        Args:
            kml_path: Path to KML file
            
        Raises:
            FileNotFoundError: If KML file doesn't exist
            ValueError: If KML file is invalid or empty
        """
        if not kml_path.exists():
            raise FileNotFoundError(f"KML file not found: {kml_path}")
        
        self.kml_path = kml_path
        self._locations: List[LocationPoint] = []
        self._parse()
    
    def _parse(self):
        """
        Parse KML file and extract location points.
        
        Raises:
            ValueError: If KML parsing fails or no valid points found
        """
        try:
            tree = etree.parse(str(self.kml_path))
            root = tree.getroot()
            
            # Handle namespaces (KML 2.2 standard)
            namespaces = {
                'kml': 'http://www.opengis.net/kml/2.2',
                'gx': 'http://www.google.com/kml/ext/2.2'
            }
            
            # Find all Placemarks with coordinates and timestamps
            placemarks = root.xpath(
                '//kml:Placemark',
                namespaces=namespaces
            )
            
            if not placemarks:
                # Try without namespace (some exporters omit xmlns)
                placemarks = root.xpath('//Placemark')
            
            for placemark in placemarks:
                location = self._parse_placemark(
                    placemark,
                    namespaces
                )
                if location:
                    self._locations.append(location)
            
            if not self._locations:
                raise ValueError(
                    "No valid location points found in KML file"
                )
            
            # Sort by timestamp
            self._locations.sort(key=lambda loc: loc.timestamp)
            
            logger.info(
                f"Parsed {len(self._locations)} locations from KML"
            )
            
        except etree.XMLSyntaxError as e:
            raise ValueError(f"Invalid KML file: {e}")
        except Exception as e:
            raise ValueError(f"Failed to parse KML: {e}")
    
    def _parse_placemark(
        self,
        placemark: etree._Element,
        namespaces: dict
    ) -> LocationPoint | None:
        """
        Extract location from a single Placemark element.
        
        Args:
            placemark: Placemark XML element
            namespaces: XML namespaces dict
            
        Returns:
            LocationPoint if valid, None otherwise
        """
        # Extract timestamp
        timestamp = None
        
        # Try TimeStamp/when (Tracker Android format)
        when_elem = placemark.find('.//kml:TimeStamp/kml:when', namespaces)
        if when_elem is None:
            when_elem = placemark.find('.//TimeStamp/when')
        
        if when_elem is not None and when_elem.text:
            timestamp = self._parse_timestamp(when_elem.text.strip())
        
        # Try TimeSpan/begin (alternative format)
        if timestamp is None:
            begin_elem = placemark.find(
                './/kml:TimeSpan/kml:begin',
                namespaces
            )
            if begin_elem is None:
                begin_elem = placemark.find('.//TimeSpan/begin')
            
            if begin_elem is not None and begin_elem.text:
                timestamp = self._parse_timestamp(begin_elem.text.strip())
        
        if timestamp is None:
            logger.debug("Skipping placemark without timestamp")
            return None
        
        # Extract coordinates
        coords_elem = placemark.find(
            './/kml:Point/kml:coordinates',
            namespaces
        )
        if coords_elem is None:
            coords_elem = placemark.find('.//Point/coordinates')
        
        if coords_elem is None or not coords_elem.text:
            logger.debug("Skipping placemark without coordinates")
            return None
        
        # Parse coordinates: "longitude,latitude,altitude"
        coords_text = coords_elem.text.strip()
        coords_parts = coords_text.split(',')
        
        if len(coords_parts) < 2:
            logger.warning(f"Invalid coordinates format: {coords_text}")
            return None
        
        try:
            # KML order: longitude, latitude, altitude
            longitude = float(coords_parts[0])
            latitude = float(coords_parts[1])
            altitude = float(coords_parts[2]) if len(coords_parts) > 2 else None
            
            # Validate ranges
            if not (-90 <= latitude <= 90):
                logger.warning(f"Invalid latitude: {latitude}")
                return None
            if not (-180 <= longitude <= 180):
                logger.warning(f"Invalid longitude: {longitude}")
                return None
            
            return LocationPoint(
                timestamp=timestamp,
                latitude=latitude,
                longitude=longitude,
                altitude=altitude
            )
            
        except (ValueError, TypeError) as e:
            logger.warning(f"Failed to parse coordinates: {e}")
            return None
    
    def _parse_timestamp(self, timestamp_str: str) -> datetime:
        """
        Parse KML timestamp string to datetime.
        
        KML timestamps can be:
        - ISO 8601: 2025-11-02T14:30:00Z
        - Tracker format: 2025-11-02T14:30:00
        - With timezone: 2025-11-02T14:30:00+02:00
        
        Args:
            timestamp_str: Timestamp string from KML
            
        Returns:
            Datetime object (naive, UTC assumed)
            
        Raises:
            ValueError: If timestamp format is invalid
        """
        # Remove 'Z' suffix (UTC indicator)
        timestamp_str = timestamp_str.rstrip('Z')
        
        # Try ISO 8601 formats
        formats = [
            '%Y-%m-%dT%H:%M:%S',           # Basic ISO
            '%Y-%m-%dT%H:%M:%S.%f',        # With microseconds
            '%Y-%m-%d %H:%M:%S',           # Space separator
        ]
        
        for fmt in formats:
            try:
                # Parse and remove timezone if present
                base_str = timestamp_str.split('+')[0].split('-')
                # Reconstruct without timezone offset
                if len(base_str) > 3:
                    base_str = '-'.join(base_str[:3])
                else:
                    base_str = timestamp_str.split('+')[0]
                
                return datetime.strptime(base_str, fmt)
            except ValueError:
                continue
        
        # Fallback: Try dateutil parser
        try:
            from dateutil import parser
            dt = parser.isoparse(timestamp_str)
            # Strip timezone info to make naive
            return dt.replace(tzinfo=None)
        except Exception:
            pass
        
        raise ValueError(f"Unable to parse timestamp: {timestamp_str}")
    
    def get_locations(self) -> List[LocationPoint]:
        """
        Get parsed locations sorted by timestamp.
        
        Returns:
            List of LocationPoint objects
        """
        return self._locations.copy()
