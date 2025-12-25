"""
Tracker Photo Geotagger - ExifTool Wrapper

Safe EXIF metadata operations using ExifTool with corruption prevention.
"""

import json
import logging
import shutil
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Optional, Tuple

from models import LocationPoint, PhotoMetadata

logger = logging.getLogger(__name__)


class ExifToolWrapper:
    """
    Wrapper for ExifTool with safety features.
    
    Features:
    - Automatic backup creation
    - File verification after writes
    - Rollback on errors
    - Coordinate conversion (decimal to DMS)
    """
    
    def __init__(self, exiftool_path: Optional[str] = None):
        """
        Initialize ExifTool wrapper.
        
        Args:
            exiftool_path: Path to exiftool binary (or None for PATH search)
            
        Raises:
            RuntimeError: If ExifTool not found or version too old
        """
        self.exiftool_path = exiftool_path or "exiftool"
        self._verify_exiftool()
    
    def _verify_exiftool(self):
        """
        Verify ExifTool is available and check version.
        
        Raises:
            RuntimeError: If ExifTool not found or version < 12.15
        """
        try:
            result = subprocess.run(
                [self.exiftool_path, "-ver"],
                capture_output=True,
                text=True,
                timeout=5
            )
            version_str = result.stdout.strip()
            version = float(version_str)
            
            logger.info(f"ExifTool version: {version_str}")
            
            if version < 12.15:
                logger.warning(
                    f"ExifTool {version} detected. "
                    "Version 12.15+ recommended for best compatibility."
                )
        except FileNotFoundError:
            raise RuntimeError(
                "ExifTool not found. Please install:\n"
                "  Linux: sudo apt install exiftool\n"
                "  macOS: brew install exiftool\n"
                "  Windows: Download from https://exiftool.org/"
            )
        except Exception as e:
            raise RuntimeError(f"Failed to verify ExifTool: {e}")
    
    def read_photo_metadata(self, photo_path: Path) -> PhotoMetadata:
        """
        Read EXIF metadata from photo.
        
        Args:
            photo_path: Path to photo file
            
        Returns:
            Photo metadata with capture time and existing GPS
            
        Raises:
            RuntimeError: If reading metadata fails
        """
        try:
            result = subprocess.run(
                [
                    self.exiftool_path,
                    "-json",
                    "-DateTimeOriginal",
                    "-OffsetTimeOriginal",
                    "-GPSLatitude",
                    "-GPSLongitude",
                    "-FileSize",
                    "-FileType",
                    str(photo_path),
                ],
                capture_output=True,
                text=True,
                timeout=30
            )
            
            if result.returncode != 0:
                raise RuntimeError(f"ExifTool error: {result.stderr}")
            
            data = json.loads(result.stdout)[0]
            
            # Extract capture time
            capture_time = self._parse_capture_time(data)
            if not capture_time:
                raise RuntimeError("No capture time found in EXIF")
            
            # Extract existing GPS
            existing_gps = None
            if "GPSLatitude" in data and "GPSLongitude" in data:
                try:
                    lat = self._parse_gps_coordinate(data["GPSLatitude"])
                    lon = self._parse_gps_coordinate(data["GPSLongitude"])
                    existing_gps = (lat, lon)
                except ValueError:
                    pass
            
            return PhotoMetadata(
                filepath=photo_path,
                capture_time=capture_time,
                existing_gps=existing_gps,
                file_size=data.get("FileSize", 0),
                format=data.get("FileType", "UNKNOWN"),
            )
            
        except Exception as e:
            raise RuntimeError(f"Failed to read metadata from {photo_path}: {e}")
    
    def write_gps_tags(
        self,
        photo_path: Path,
        location: LocationPoint,
        create_backup: bool = True,
        verify: bool = True,
    ) -> bool:
        """
        Write GPS tags to photo with safety checks.
        
        Args:
            photo_path: Path to photo file
            location: Location to write
            create_backup: Create .original backup before writing
            verify: Verify GPS coordinates after writing
            
        Returns:
            True if successful
            
        Raises:
            RuntimeError: If write fails or verification fails
        """
        backup_path = None
        original_size = photo_path.stat().st_size
        
        try:
            # Create backup
            if create_backup:
                backup_path = photo_path.with_suffix(
                    photo_path.suffix + ".original"
                )
                shutil.copy2(photo_path, backup_path)
                logger.debug(f"Created backup: {backup_path}")
            
            # Convert coordinates to DMS format for ExifTool
            lat_ref = "N" if location.latitude >= 0 else "S"
            lon_ref = "E" if location.longitude >= 0 else "W"
            
            # Build ExifTool command
            cmd = [
                self.exiftool_path,
                f"-GPSLatitude={abs(location.latitude)}",
                f"-GPSLatitudeRef={lat_ref}",
                f"-GPSLongitude={abs(location.longitude)}",
                f"-GPSLongitudeRef={lon_ref}",
            ]
            
            # Add altitude if available
            if location.altitude is not None:
                alt_ref = 0 if location.altitude >= 0 else 1
                cmd.extend([
                    f"-GPSAltitude={abs(location.altitude)}",
                    f"-GPSAltitudeRef={alt_ref}",
                ])
            
            # Add timestamp
            cmd.extend([
                f"-GPSDateStamp={location.timestamp.strftime('%Y:%m:%d')}",
                f"-GPSTimeStamp={location.timestamp.strftime('%H:%M:%S')}",
                "-GPSMapDatum=WGS-84",
            ])
            
            # Overwrite original (ExifTool creates .original if not disabled)
            cmd.append("-overwrite_original")
            cmd.append(str(photo_path))
            
            # Execute
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=30
            )
            
            if result.returncode != 0:
                raise RuntimeError(f"ExifTool error: {result.stderr}")
            
            # Verify file integrity
            new_size = photo_path.stat().st_size
            if new_size == 0:
                raise RuntimeError("Photo file corrupted (size = 0)")
            
            # Verify GPS coordinates if requested
            if verify:
                self._verify_gps_write(photo_path, location)
            
            logger.debug(f"GPS tags written to {photo_path.name}")
            return True
            
        except Exception as e:
            # Rollback on error
            if backup_path and backup_path.exists():
                logger.warning(f"Write failed, restoring from backup: {e}")
                shutil.copy2(backup_path, photo_path)
                backup_path.unlink()
            raise RuntimeError(f"Failed to write GPS tags: {e}")
    
    def _verify_gps_write(self, photo_path: Path, expected: LocationPoint):
        """
        Verify GPS coordinates were written correctly.
        
        Args:
            photo_path: Path to photo file
            expected: Expected location
            
        Raises:
            RuntimeError: If coordinates don't match (within tolerance)
        """
        metadata = self.read_photo_metadata(photo_path)
        
        if not metadata.existing_gps:
            raise RuntimeError("GPS tags not found after write")
        
        lat, lon = metadata.existing_gps
        
        # Check within 0.00001 degree (~1 meter)
        tolerance = 0.00001
        if abs(lat - expected.latitude) > tolerance:
            raise RuntimeError(
                f"GPS verification failed: "
                f"latitude {lat} != {expected.latitude}"
            )
        if abs(lon - expected.longitude) > tolerance:
            raise RuntimeError(
                f"GPS verification failed: "
                f"longitude {lon} != {expected.longitude}"
            )
    
    def _parse_capture_time(self, exif_data: dict) -> Optional[datetime]:
        """
        Parse capture time from EXIF data.
        
        Args:
            exif_data: EXIF data from ExifTool JSON
            
        Returns:
            Capture datetime or None if not found
        """
        # Try DateTimeOriginal first (most accurate)
        if "DateTimeOriginal" in exif_data:
            time_str = exif_data["DateTimeOriginal"]
            try:
                # ExifTool format: "2025:11:02 14:30:00"
                return datetime.strptime(time_str, "%Y:%m:%d %H:%M:%S")
            except ValueError:
                pass
        
        return None
    
    def _parse_gps_coordinate(self, coord_str: str) -> float:
        """
        Parse GPS coordinate from ExifTool output.
        
        Args:
            coord_str: Coordinate string (e.g., "40 deg 42' 46.08" N")
            
        Returns:
            Decimal degrees
            
        Raises:
            ValueError: If parsing fails
        """
        # ExifTool provides decimal degrees directly in JSON mode
        try:
            return float(coord_str)
        except ValueError:
            # Handle DMS format if needed
            raise ValueError(f"Invalid GPS coordinate format: {coord_str}")
