"""
Tracker Photo Geotagger - Time-Based Matcher

Match photos to GPS locations by timestamp with optional linear interpolation.
"""

import logging
from bisect import bisect_left
from datetime import datetime, timedelta
from typing import List, Optional

from models import LocationPoint, PhotoMetadata, MatchResult, MatchType

logger = logging.getLogger(__name__)


class TimeMatcher:
    """
    Match photos to GPS locations by timestamp.
    
    Supports:
    - Nearest neighbor matching (default)
    - Linear interpolation between points
    - Configurable time tolerance
    """
    
    def __init__(
        self,
        locations: List[LocationPoint],
        max_time_delta: timedelta = timedelta(minutes=5),
        interpolate: bool = False,
        max_interpolation_gap: timedelta = timedelta(minutes=15),
    ):
        """
        Initialize time matcher.
        
        Args:
            locations: List of GPS location points (must be sorted by timestamp)
            max_time_delta: Maximum time difference for nearest match
            interpolate: Enable linear interpolation between points
            max_interpolation_gap: Maximum gap for interpolation
        """
        self.locations = sorted(locations, key=lambda loc: loc.timestamp)
        self.max_time_delta = max_time_delta
        self.interpolate = interpolate
        self.max_interpolation_gap = max_interpolation_gap
        
        if not self.locations:
            raise ValueError("No location points provided")
        
        logger.info(f"TimeMatcher initialized: {len(self.locations)} points")
        logger.info(f"  Max time delta: {self.max_time_delta}")
        logger.info(f"  Interpolation: {self.interpolate}")
    
    def match(self, photo: PhotoMetadata) -> MatchResult:
        """
        Match photo to location by timestamp.
        
        Args:
            photo: Photo metadata with capture time
            
        Returns:
            Match result with location (if found) and match type
        """
        photo_time = photo.capture_time_utc
        
        # Check if photo time is within track range
        if photo_time < self.locations[0].timestamp:
            time_delta = self.locations[0].timestamp - photo_time
            return MatchResult(
                photo=photo,
                match_type=MatchType.NO_MATCH,
                error=f"Photo taken {time_delta} before track start",
            )
        
        if photo_time > self.locations[-1].timestamp:
            time_delta = photo_time - self.locations[-1].timestamp
            return MatchResult(
                photo=photo,
                match_type=MatchType.NO_MATCH,
                error=f"Photo taken {time_delta} after track end",
            )
        
        # Find bracketing points using binary search
        idx_after = bisect_left(
            self.locations,
            photo_time,
            key=lambda loc: loc.timestamp
        )
        
        # Handle edge cases
        if idx_after == 0:
            # Photo exactly at or before first point
            return self._nearest_match(photo, self.locations[0])
        
        if idx_after >= len(self.locations):
            # Photo exactly at or after last point
            return self._nearest_match(photo, self.locations[-1])
        
        # Get bracketing locations
        loc_before = self.locations[idx_after - 1]
        loc_after = self.locations[idx_after]
        
        # Try interpolation if enabled
        if self.interpolate:
            gap = loc_after.timestamp - loc_before.timestamp
            if gap <= self.max_interpolation_gap:
                return self._interpolate_match(photo, loc_before, loc_after)
        
        # Fall back to nearest neighbor
        delta_before = photo_time - loc_before.timestamp
        delta_after = loc_after.timestamp - photo_time
        
        if delta_before < delta_after:
            return self._nearest_match(photo, loc_before)
        else:
            return self._nearest_match(photo, loc_after)
    
    def _nearest_match(
        self,
        photo: PhotoMetadata,
        location: LocationPoint
    ) -> MatchResult:
        """
        Create nearest neighbor match result.
        
        Args:
            photo: Photo metadata
            location: Nearest location point
            
        Returns:
            Match result with confidence based on time delta
        """
        photo_time = photo.capture_time_utc
        time_delta = abs(photo_time - location.timestamp)
        
        # Check if within tolerance
        if time_delta > self.max_time_delta:
            return MatchResult(
                photo=photo,
                match_type=MatchType.NO_MATCH,
                time_delta=time_delta,
                error=f"Nearest location {time_delta} away (> {self.max_time_delta})",
            )
        
        # Calculate confidence (1.0 for exact, decreases with time delta)
        if time_delta.total_seconds() < 1.0:
            match_type = MatchType.EXACT
            confidence = 1.0
        else:
            match_type = MatchType.NEAREST
            # Linear decrease from 1.0 to 0.5 as delta approaches max
            ratio = time_delta / self.max_time_delta
            confidence = 1.0 - (0.5 * ratio)
        
        return MatchResult(
            photo=photo,
            location=location,
            match_type=match_type,
            time_delta=time_delta,
            confidence=confidence,
        )
    
    def _interpolate_match(
        self,
        photo: PhotoMetadata,
        loc_before: LocationPoint,
        loc_after: LocationPoint
    ) -> MatchResult:
        """
        Create interpolated match result.
        
        Linearly interpolates latitude, longitude, and altitude
        based on time ratio between bracketing points.
        
        Args:
            photo: Photo metadata
            loc_before: Location point before photo time
            loc_after: Location point after photo time
            
        Returns:
            Match result with interpolated location
        """
        photo_time = photo.capture_time_utc
        
        # Calculate interpolation ratio
        total_gap = (loc_after.timestamp - loc_before.timestamp).total_seconds()
        time_from_before = (photo_time - loc_before.timestamp).total_seconds()
        t = time_from_before / total_gap
        
        # Linear interpolation
        lat = loc_before.latitude + t * (loc_after.latitude - loc_before.latitude)
        lon = loc_before.longitude + t * (loc_after.longitude - loc_before.longitude)
        
        # Interpolate altitude if both points have it
        alt = None
        if loc_before.altitude is not None and loc_after.altitude is not None:
            alt = loc_before.altitude + t * (loc_after.altitude - loc_before.altitude)
        elif loc_before.altitude is not None:
            alt = loc_before.altitude
        elif loc_after.altitude is not None:
            alt = loc_after.altitude
        
        # Conservative accuracy estimate (sum of both points)
        accuracy = None
        if loc_before.horizontal_accuracy and loc_after.horizontal_accuracy:
            accuracy = loc_before.horizontal_accuracy + loc_after.horizontal_accuracy
        
        interpolated_location = LocationPoint(
            timestamp=photo_time,
            latitude=lat,
            longitude=lon,
            altitude=alt,
            horizontal_accuracy=accuracy,
        )
        
        # Confidence based on interpolation gap
        # Shorter gaps = higher confidence
        gap_minutes = total_gap / 60
        if gap_minutes < 1:
            confidence = 0.95
        elif gap_minutes < 5:
            confidence = 0.90
        elif gap_minutes < 10:
            confidence = 0.80
        else:
            confidence = 0.70
        
        time_delta = timedelta(seconds=min(time_from_before, total_gap - time_from_before))
        
        return MatchResult(
            photo=photo,
            location=interpolated_location,
            match_type=MatchType.INTERPOLATED,
            time_delta=time_delta,
            confidence=confidence,
        )
