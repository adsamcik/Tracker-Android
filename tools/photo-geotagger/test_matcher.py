"""
Unit tests for photo-to-location matching logic.
"""

import unittest
from datetime import datetime, timedelta
from pathlib import Path

from matcher import TimeMatcher
from models import LocationPoint, MatchType, PhotoMetadata


class TestTimeMatcher(unittest.TestCase):
    """Test time-based matching with and without interpolation."""
    
    def setUp(self):
        """Create test location points."""
        # Create a series of locations 5 minutes apart
        base_time = datetime(2025, 11, 2, 14, 0)
        self.locations = [
            LocationPoint(
                timestamp=base_time + timedelta(minutes=i*5),
                latitude=40.7128 + i*0.001,
                longitude=-74.0060 + i*0.001,
                altitude=10.0 + i
            )
            for i in range(6)
        ]
        # Times: 14:00, 14:05, 14:10, 14:15, 14:20, 14:25
    
    def _create_photo(self, capture_time: datetime, name: str = "test.jpg") -> PhotoMetadata:
        """Helper to create test PhotoMetadata."""
        return PhotoMetadata(
            filepath=Path(f"/tmp/{name}"),
            capture_time=capture_time,
            timezone_offset=None,  # UTC
        )
    
    def test_exact_match(self):
        """Test matching photo with exact GPS timestamp."""
        matcher = TimeMatcher(
            locations=self.locations,
            max_time_delta=timedelta(minutes=5),
            interpolate=False
        )
        
        # Photo taken at exact GPS point time
        photo = self._create_photo(datetime(2025, 11, 2, 14, 10))
        result = matcher.match(photo)
        
        self.assertIsNotNone(result)
        self.assertEqual(result.match_type, MatchType.EXACT)
        self.assertAlmostEqual(result.location.latitude, 40.7148)
        self.assertGreater(result.confidence, 0.95)
    
    def test_nearest_match_within_tolerance(self):
        """Test matching photo close to GPS point."""
        matcher = TimeMatcher(
            locations=self.locations,
            max_time_delta=timedelta(minutes=5),
            interpolate=False
        )
        
        # Photo 2 minutes after GPS point
        photo = self._create_photo(datetime(2025, 11, 2, 14, 12))
        result = matcher.match(photo)
        
        self.assertIsNotNone(result)
        self.assertEqual(result.match_type, MatchType.NEAREST)
        # Should match to 14:10 point (2 min away)
        self.assertAlmostEqual(result.location.latitude, 40.7148)
    
    def test_no_match_outside_tolerance(self):
        """Test no match when photo is too far from any GPS point."""
        matcher = TimeMatcher(
            locations=self.locations,
            max_time_delta=timedelta(minutes=2),  # Strict tolerance
            interpolate=False
        )
        
        # Photo 3 minutes after nearest GPS point (14:10 + 3min = 14:13)
        # Nearest point is 14:10 (3 min before) or 14:15 (2 min after)
        # Both are outside 2 min tolerance
        photo = self._create_photo(datetime(2025, 11, 2, 14, 12, 30))
        result = matcher.match(photo)
        
        self.assertEqual(result.match_type, MatchType.NO_MATCH)
    
    def test_interpolated_match(self):
        """Test linear interpolation between two GPS points."""
        matcher = TimeMatcher(
            locations=self.locations,
            max_time_delta=timedelta(minutes=5),
            interpolate=True,
            max_interpolation_gap=timedelta(minutes=10)
        )
        
        # Photo exactly halfway between 14:10 and 14:15
        photo = self._create_photo(datetime(2025, 11, 2, 14, 12, 30))
        result = matcher.match(photo)
        
        self.assertIsNotNone(result)
        self.assertEqual(result.match_type, MatchType.INTERPOLATED)
        
        # Should be halfway between points
        expected_lat = (40.7148 + 40.7158) / 2
        self.assertAlmostEqual(
            result.location.latitude, expected_lat, places=4
        )
    
    def test_interpolation_gap_too_large(self):
        """Test interpolation skipped when gap is too large."""
        # Only use first and last location (25 min gap)
        sparse_locations = [self.locations[0], self.locations[-1]]
        
        matcher = TimeMatcher(
            locations=sparse_locations,
            max_time_delta=timedelta(minutes=30),
            interpolate=True,
            max_interpolation_gap=timedelta(minutes=10)  # Gap < 10 min
        )
        
        # Photo in the middle (but gap is 25 min)
        photo = self._create_photo(datetime(2025, 11, 2, 14, 12))
        result = matcher.match(photo)
        
        # Should fall back to nearest match, not interpolate
        self.assertIsNotNone(result)
        self.assertEqual(result.match_type, MatchType.NEAREST)
    
    def test_confidence_decreases_with_distance(self):
        """Test that confidence decreases as time delta increases."""
        matcher = TimeMatcher(
            locations=self.locations,
            max_time_delta=timedelta(minutes=5),
            interpolate=False
        )
        
        # Exact match
        photo_exact = self._create_photo(datetime(2025, 11, 2, 14, 10))
        result_exact = matcher.match(photo_exact)
        
        # 4 minutes away
        photo_far = self._create_photo(datetime(2025, 11, 2, 14, 14))
        result_far = matcher.match(photo_far)
        
        self.assertGreater(result_exact.confidence, result_far.confidence)
    
    def test_match_before_first_location(self):
        """Test photo taken before first GPS point."""
        matcher = TimeMatcher(
            locations=self.locations,
            max_time_delta=timedelta(minutes=5),
            interpolate=False
        )
        
        # Photo before first location (14:00)
        photo = self._create_photo(datetime(2025, 11, 2, 13, 57))
        result = matcher.match(photo)
        
        # Should not match
        self.assertEqual(result.match_type, MatchType.NO_MATCH)
    
    def test_match_after_last_location(self):
        """Test photo taken after last GPS point."""
        matcher = TimeMatcher(
            locations=self.locations,
            max_time_delta=timedelta(minutes=5),
            interpolate=False
        )
        
        # Photo after last location (14:25)
        photo = self._create_photo(datetime(2025, 11, 2, 14, 28))
        result = matcher.match(photo)
        
        # Should not match
        self.assertEqual(result.match_type, MatchType.NO_MATCH)
    
    def test_altitude_interpolation(self):
        """Test that altitude is also interpolated."""
        matcher = TimeMatcher(
            locations=self.locations,
            max_time_delta=timedelta(minutes=5),
            interpolate=True,
            max_interpolation_gap=timedelta(minutes=10)
        )
        
        # Photo halfway between 14:00 (alt=10) and 14:05 (alt=11)
        photo = self._create_photo(datetime(2025, 11, 2, 14, 2, 30))
        result = matcher.match(photo)
        
        self.assertIsNotNone(result)
        # Altitude should be interpolated
        expected_alt = 10.5
        self.assertAlmostEqual(result.location.altitude, expected_alt, places=1)
    
    def test_empty_locations(self):
        """Test matcher with no locations raises error."""
        with self.assertRaises(ValueError):
            TimeMatcher(
                locations=[],
                max_time_delta=timedelta(minutes=5),
                interpolate=False
            )


class TestMatchTypeEnum(unittest.TestCase):
    """Test MatchType enumeration."""
    
    def test_match_types_exist(self):
        """Test all expected match types exist."""
        self.assertEqual(MatchType.NO_MATCH.value, "no_match")
        self.assertEqual(MatchType.EXACT.value, "exact")
        self.assertEqual(MatchType.NEAREST.value, "nearest")
        self.assertEqual(MatchType.INTERPOLATED.value, "interpolated")


if __name__ == '__main__':
    unittest.main()
