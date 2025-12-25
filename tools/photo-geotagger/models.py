"""
Tracker Photo Geotagger - Data Models

Core data structures for location points, photo metadata, and match results.
All timestamps in UTC for consistency.
"""

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from pathlib import Path
from typing import Optional, Dict, Any


class MatchType(Enum):
    """Type of location match for a photo."""
    NO_MATCH = "no_match"           # No location found within tolerance
    EXACT = "exact"                 # Exact timestamp match (< 1 second)
    NEAREST = "nearest"             # Nearest neighbor within tolerance
    INTERPOLATED = "interpolated"   # Linearly interpolated between two points


@dataclass(frozen=True)
class LocationPoint:
    """
    Single location point from GPS track.
    
    Attributes:
        timestamp: UTC datetime when location was recorded
        latitude: Latitude in decimal degrees (-90 to 90)
        longitude: Longitude in decimal degrees (-180 to 180)
        altitude: Altitude in meters above WGS84 ellipsoid (optional)
        horizontal_accuracy: Horizontal position accuracy in meters (optional)
        speed: Ground speed in meters per second (optional)
    """
    timestamp: datetime
    latitude: float
    longitude: float
    altitude: Optional[float] = None
    horizontal_accuracy: Optional[float] = None
    speed: Optional[float] = None
    
    def __post_init__(self):
        """Validate coordinate ranges."""
        if not -90.0 <= self.latitude <= 90.0:
            raise ValueError(f"Latitude {self.latitude} out of range [-90, 90]")
        if not -180.0 <= self.longitude <= 180.0:
            raise ValueError(f"Longitude {self.longitude} out of range [-180, 180]")


@dataclass
class PhotoMetadata:
    """
    Photo file metadata extracted from EXIF.
    
    Attributes:
        filepath: Absolute path to photo file
        capture_time: When photo was taken (in local time or UTC)
        timezone_offset: Timezone offset from UTC if known
        existing_gps: Existing GPS coordinates (lat, lon) if already tagged
        file_size: Original file size in bytes (for verification)
        format: Image format (JPEG, HEIC, PNG, etc.)
    """
    filepath: Path
    capture_time: datetime
    timezone_offset: Optional[timedelta] = None
    existing_gps: Optional[tuple[float, float]] = None
    file_size: int = 0
    format: str = "UNKNOWN"
    
    @property
    def capture_time_utc(self) -> datetime:
        """Get capture time in UTC."""
        if self.timezone_offset:
            return self.capture_time - self.timezone_offset
        # Assume UTC if no timezone info
        return self.capture_time
    
    @property
    def has_gps(self) -> bool:
        """Check if photo already has GPS tags."""
        return self.existing_gps is not None


@dataclass
class MatchResult:
    """
    Result of matching a photo to a location.
    
    Attributes:
        photo: Photo metadata
        location: Matched location point (None if no match)
        match_type: Type of match performed
        time_delta: Time difference between photo and location
        confidence: Match confidence 0.0-1.0 (based on time delta and match type)
        error: Error message if matching failed
    """
    photo: PhotoMetadata
    location: Optional[LocationPoint] = None
    match_type: MatchType = MatchType.NO_MATCH
    time_delta: Optional[timedelta] = None
    confidence: float = 0.0
    error: Optional[str] = None
    
    @property
    def is_matched(self) -> bool:
        """Check if match was successful."""
        return self.location is not None and self.match_type != MatchType.NO_MATCH
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "photo": str(self.photo.filepath),
            "capture_time": self.photo.capture_time.isoformat(),
            "match_type": self.match_type.value,
            "location": {
                "latitude": self.location.latitude,
                "longitude": self.location.longitude,
                "altitude": self.location.altitude,
                "timestamp": self.location.timestamp.isoformat(),
            } if self.location else None,
            "time_delta_seconds": self.time_delta.total_seconds() if self.time_delta else None,
            "confidence": self.confidence,
            "error": self.error,
        }


@dataclass
class ProcessingStats:
    """
    Statistics for batch processing operation.
    
    Tracks counts of photos processed, matched, skipped, and errors.
    """
    total_photos: int = 0
    matched: int = 0
    interpolated: int = 0
    skipped_already_tagged: int = 0
    skipped_no_match: int = 0
    errors: int = 0
    backups_created: int = 0
    processing_time_seconds: float = 0.0
    
    match_results: list[MatchResult] = field(default_factory=list)
    
    def add_result(self, result: MatchResult):
        """Add a match result and update counters."""
        self.match_results.append(result)
        self.total_photos += 1
        
        if result.error:
            self.errors += 1
        elif result.photo.has_gps and result.match_type == MatchType.NO_MATCH:
            self.skipped_already_tagged += 1
        elif result.is_matched:
            self.matched += 1
            if result.match_type == MatchType.INTERPOLATED:
                self.interpolated += 1
        else:
            self.skipped_no_match += 1
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "summary": {
                "total_photos": self.total_photos,
                "matched": self.matched,
                "interpolated": self.interpolated,
                "skipped_already_tagged": self.skipped_already_tagged,
                "skipped_no_match": self.skipped_no_match,
                "errors": self.errors,
                "backups_created": self.backups_created,
                "processing_time_seconds": round(self.processing_time_seconds, 2),
                "success_rate": round(self.matched / self.total_photos * 100, 1) if self.total_photos > 0 else 0.0,
            },
            "matches": [r.to_dict() for r in self.match_results if r.is_matched],
            "unmatched": [
                {
                    "photo": str(r.photo.filepath),
                    "capture_time": r.photo.capture_time.isoformat(),
                    "reason": r.error or "No location within time tolerance"
                }
                for r in self.match_results if not r.is_matched and not r.photo.has_gps
            ],
        }
