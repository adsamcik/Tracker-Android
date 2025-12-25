# Photo Geotagging Tool - Implementation Plan

## Executive Summary

Python command-line tool to update photo EXIF GPS coordinates using location data exported from Tracker Android. Fully local processing, privacy-preserving, with intelligent time-based matching and optional interpolation.

**Revised Architecture** (based on expert guidance):
- **Core Engine**: ExifTool (industry-standard metadata tool) via PyExifTool wrapper
- **Why ExifTool**: Universal format support (JPEG, HEIC, RAW, PNG+XMP), safe writes preserving color profiles/thumbnails, built-in coordinate conversion
- **Python Role**: Track parsing (GPX/KML/DB), custom time matching/interpolation, CLI, reporting
- **Fallback**: Pure Python (piexif) for JPEG-only workflows if ExifTool unavailable

**Key Advantages**:
- ✅ **Robust**: Handles HEIC (iOS), RAW formats, maker notes without corruption risk
- ✅ **Privacy-First**: Zero network calls, all local processing (aligned with Tracker philosophy)
- ✅ **Flexible**: Custom interpolation algorithms, activity-aware matching (future)
- ✅ **Production-Ready**: ExifTool is the de-facto standard used by professionals

---

## 1. Tracker Android Export Formats

### 1.1 GPX Export (Primary Target)
**File**: `impexp/src/main/java/com/adsamcik/tracker/impexp/exporter/GpxExporter.kt`

- **Format**: XML (GPX 1.1 standard via Jenetics JPX library)
- **MIME Type**: `application/gpx+xml`
- **Extension**: `.gpx`
- **Structure**:
  ```xml
  <gpx>
    <metadata>
      <author>Tracker</author>
      <desc>Export from [start_time] to [end_time]</desc>
    </metadata>
    <trk>
      <trkseg>
        <trkpt lat="40.7128" lon="-74.0060">
          <ele>10.5</ele>  <!-- altitude in meters, optional -->
          <time>2025-11-02T14:30:00Z</time>
        </trkpt>
        <!-- more trackpoints -->
      </trkseg>
    </trk>
  </gpx>
  ```
- **Data Available**:
  - Latitude (required)
  - Longitude (required)
  - Altitude (optional)
  - Timestamp (ISO 8601 format)
- **Current Limitation**: Single segment per track (comment indicates future enhancement for time gaps/motion splits)

### 1.2 KML Export (Secondary Target)
**File**: `impexp/src/main/java/com/adsamcik/tracker/impexp/exporter/KmlExporter.kt`

- **Format**: XML (KML 2.2)
- **MIME Type**: `application/vnd.google-earth.kml+xml`
- **Extension**: `.kml`
- **Structure**:
  ```xml
  <kml xmlns="http://www.opengis.net/kml/2.2">
    <Document>
      <Placemark>
        <TimeStamp><when>2025-11-02T14:30:00</when></TimeStamp>
        <Point>
          <coordinates>-74.0060,40.7128,10.5</coordinates> <!-- lon,lat,alt -->
        </Point>
      </Placemark>
      <!-- more placemarks -->
    </Document>
  </kml>
  ```
- **Data Available**: Same as GPX (lat, lon, alt, time)
- **Note**: Coordinates order is lon,lat,alt (KML standard)

### 1.3 SQLite Database Export (Advanced)
**File**: `impexp/src/main/java/com/adsamcik/tracker/impexp/exporter/DatabaseExporter.kt`

- **Format**: SQLite database file
- **Extension**: `.db`
- **Table**: `location_data` (DatabaseLocation entity)
- **Columns**:
  - `id` (primary key)
  - `time` (epoch milliseconds)
  - `lat`, `lon` (doubles)
  - `alt` (nullable double)
  - `hor_acc`, `ver_acc` (horizontal/vertical accuracy, nullable floats)
  - `speed`, `s_acc` (speed and speed accuracy, nullable floats)
  - `activity`, `confidence` (ActivityInfo - detected activity type and confidence)
- **Advantages**: Full metadata (accuracy, speed, activity), no parsing overhead
- **Complexity**: Requires SQLite driver

---

## 2. Photo EXIF Data Requirements

### 2.1 Reading EXIF
**Required Tags**:
- `DateTimeOriginal` (0x9003) - Primary capture time
- `DateTimeDigitized` (0x9004) - Fallback capture time
- `DateTime` (0x0132) - Last fallback
- `OffsetTimeOriginal` (0x9011) - Timezone offset (if available)

**Optional Context**:
- Existing GPS tags (to detect already geotagged photos)
- Camera make/model (for reporting)

### 2.2 Writing EXIF GPS Tags
**Required GPS Tags**:
- `GPSLatitude` (0x0002) - Degrees, minutes, seconds (rational triplet)
- `GPSLatitudeRef` (0x0001) - "N" or "S"
- `GPSLongitude` (0x0004) - Degrees, minutes, seconds (rational triplet)
- `GPSLongitudeRef` (0x0003) - "E" or "W"
- `GPSAltitude` (0x0006) - Meters (rational)
- `GPSAltitudeRef` (0x0005) - 0 (above sea level) or 1 (below)
- `GPSTimeStamp` (0x0007) - UTC time (hour, minute, second)
- `GPSDateStamp` (0x001D) - UTC date (YYYY:MM:DD)

**Optional GPS Tags**:
- `GPSSpeed` (0x000D) - Ground speed (if available from track)
- `GPSSpeedRef` (0x000C) - "K" (km/h)
- `GPSTrack` (0x000F) - Direction of movement (if interpolating between points)
- `GPSMapDatum` (0x0012) - "WGS-84"

---

## 3. Tool Architecture

### 3.1 Core Components

```
photo-geotagger/
├── geotagger/
│   ├── __init__.py
│   ├── parsers/
│   │   ├── __init__.py
│   │   ├── base.py           # Abstract parser interface
│   │   ├── gpx_parser.py     # GPX format parser
│   │   ├── kml_parser.py     # KML format parser
│   │   └── db_parser.py      # SQLite database parser
│   ├── matchers/
│   │   ├── __init__.py
│   │   ├── time_matcher.py   # Time-based matching strategies
│   │   └── interpolator.py   # Coordinate interpolation
│   ├── photo/
│   │   ├── __init__.py
│   │   ├── exif_reader.py    # Read EXIF metadata
│   │   └── exif_writer.py    # Write GPS EXIF tags
│   ├── models.py              # Data models (LocationPoint, PhotoMetadata)
│   ├── processor.py           # Main processing orchestration
│   └── utils.py               # Timezone, coordinate conversion utilities
├── tests/
│   ├── fixtures/              # Sample GPX/KML/photos for testing
│   ├── test_parsers.py
│   ├── test_matchers.py
│   └── test_integration.py
├── cli.py                     # Command-line interface
├── requirements.txt
├── README.md
└── setup.py
```

### 3.2 Data Models

```python
@dataclass
class LocationPoint:
    """Single location point from track."""
    timestamp: datetime  # UTC
    latitude: float
    longitude: float
    altitude: Optional[float] = None
    horizontal_accuracy: Optional[float] = None
    speed: Optional[float] = None

@dataclass
class PhotoMetadata:
    """Photo file metadata."""
    filepath: Path
    capture_time: datetime  # Localized or UTC
    timezone_offset: Optional[timedelta] = None
    existing_gps: Optional[Tuple[float, float]] = None  # (lat, lon) if already tagged

@dataclass
class MatchResult:
    """Result of matching photo to location."""
    photo: PhotoMetadata
    matched_location: Optional[LocationPoint]
    match_type: MatchType  # EXACT, NEAREST, INTERPOLATED, NO_MATCH
    time_delta: Optional[timedelta]  # Time difference from photo to location
    confidence: float  # 0.0 to 1.0
```

---

## 4. ExifTool Integration Strategy

### 4.1 Why ExifTool?

**Expert Consensus** (based on industry best practices):
> "ExifTool is the most complete metadata implementation available. For deep metadata (XMP structures, sidecars, RAW quirks), use ExifTool from either language." 
>
> — Recommended for production geotagging tools

**Advantages**:
1. **Universal Format Support**: 500+ file formats (JPEG, HEIC, PNG, RAW, video)
2. **Built-in Geotagging**: Native `-geotag` command with linear interpolation
3. **Safe Writes**: Preserves color profiles, thumbnails, non-GPS EXIF automatically
4. **Coordinate Conversion**: Handles decimal degrees ↔ DMS internally
5. **Battle-Tested**: Used by professional photographers, forensics, digital asset management

### 4.2 Implementation Approaches

#### Option A: Use ExifTool's Native `-geotag` (Recommended for MVP)
**How it works**:
- ExifTool reads GPX file directly
- Matches photos by timestamp (nearest neighbor by default)
- Supports linear interpolation with `-geosync` offset adjustment
- Writes GPS tags in single pass

**Command Example**:
```bash
exiftool -geotag track.gpx \
         -geosync=-30 \          # Adjust if camera clock offset
         -geotime<${DateTimeOriginal} \
         -overwrite_original \
         *.jpg
```

**Pros**:
- Minimal code (wrapper around ExifTool CLI)
- Proven reliability
- Fast (C/Perl implementation)

**Cons**:
- Limited control over interpolation algorithm
- Harder to generate detailed match reports (need to parse stdout)
- Requires GPX format (no direct support for KML/DB)

#### Option B: Custom Python Matching + ExifTool for Writing
**How it works**:
- Parse GPX/KML/DB with Python (full control)
- Implement custom time matching and interpolation
- Use PyExifTool to write GPS coordinates per photo

**Implementation Sketch**:
```python
import exiftool

with exiftool.ExifToolHelper() as et:
    # For each matched photo+location
    et.set_tags(
        ["photo.jpg"],
        tags={
            "GPSLatitude": latitude,
            "GPSLatitudeRef": "N" if latitude >= 0 else "S",
            "GPSLongitude": longitude,
            "GPSLongitudeRef": "E" if longitude >= 0 else "W",
            "GPSAltitude": altitude,
        },
        params=["-P", "-overwrite_original"]
    )
```

**Pros**:
- Full control over matching logic (custom interpolation, time zones, activity-aware)
- Support all formats (GPX/KML/DB) uniformly
- Detailed match reports (confidence, time delta, interpolation flag)

**Cons**:
- More code to maintain
- Slightly slower (Python overhead + subprocess per write)

**Decision**: **Start with Option B** for flexibility, add Option A optimization later.

### 4.3 Fallback Strategy

**Graceful Degradation**:
1. **Primary**: ExifTool via PyExifTool wrapper
2. **Fallback (JPEG only)**: Pure Python `piexif` if ExifTool not installed
3. **Error Handling**: Clear message directing user to install ExifTool

**Detection Logic**:
```python
def check_exiftool_available():
    try:
        result = subprocess.run(
            ["exiftool", "-ver"],
            capture_output=True, text=True, timeout=5
        )
        version = float(result.stdout.strip())
        if version < 12.15:
            log.warning(f"ExifTool {version} detected, but 12.15+ recommended")
        return True
    except FileNotFoundError:
        log.error("ExifTool not found. Install: https://exiftool.org/")
        return False
```

---

## 5. Matching Strategies

### 4.1 Nearest Neighbor Match
**Algorithm**:
1. Convert photo timestamp to UTC
2. Binary search or linear scan to find closest location point
3. Accept match if time delta < threshold (default: 5 minutes)

**Pros**: Simple, fast
**Cons**: May assign wrong location if stationary periods exist

### 4.2 Linear Interpolation
**Algorithm**:
1. Find two location points bracketing photo timestamp (before & after)
2. If time gap between points < threshold (default: 15 minutes):
   - Interpolate latitude, longitude, altitude linearly
   - Calculate based on time ratio: `t = (photo_time - t1) / (t2 - t1)`
   - `lat = lat1 + t * (lat2 - lat1)`
3. Else use nearest neighbor

**Pros**: More accurate for photos taken between location samples
**Cons**: Assumes linear motion (less accurate for curves)

**Accuracy Adjustment**:
- If original location points have horizontal accuracy metadata, propagate with conservative estimate
- Mark interpolated points distinctly in output report

### 4.3 Motion-Aware Matching (Future Enhancement)
- Detect stationary periods (speed < 0.5 m/s for > 2 minutes)
- Use activity type from DatabaseLocation (IN_VEHICLE, ON_FOOT, STILL)
- Adjust interpolation model based on motion type

---

## 5. Command-Line Interface

### 5.1 Basic Usage
```bash
# Match photos to GPX track, modify in-place
python -m geotagger match photos/*.jpg --track export.gpx

# Create copies with GPS tags added
python -m geotagger match photos/*.jpg --track export.gpx --output tagged/ --preserve-originals

# Use KML format
python -m geotagger match photos/*.jpg --track export.kml --format kml

# Use SQLite database export
python -m geotagger match photos/*.jpg --track export.db --format db
```

### 5.2 Options
```
Required:
  photos              Path(s) to photo files or directories (recursive scan)
  --track PATH        Path to location export file (GPX/KML/DB)

Optional:
  --format {gpx,kml,db}          Auto-detect from extension if not specified
  --output DIR                   Output directory (creates copies if specified)
  --preserve-originals           Create .original backups when modifying in-place
  --max-time-delta MINUTES       Maximum time difference for match (default: 5)
  --interpolate                  Enable linear interpolation (default: off)
  --max-interpolation-gap MIN    Maximum gap for interpolation (default: 15)
  --timezone OFFSET              Override photo timezone (e.g., +02:00, -05:00)
  --dry-run                      Show matches without writing EXIF
  --verbose                      Detailed logging
  --report PATH                  Save match report as JSON/CSV
  --skip-already-tagged          Skip photos with existing GPS data
  --overwrite-existing           Overwrite existing GPS tags (default: skip)
```

### 5.3 Output Report
**JSON format** (`--report matches.json`):
```json
{
  "summary": {
    "total_photos": 150,
    "matched": 142,
    "interpolated": 38,
    "no_match": 8,
    "already_tagged": 5,
    "processing_time_seconds": 4.2
  },
  "matches": [
    {
      "photo": "photos/IMG_1234.jpg",
      "capture_time": "2025-11-02T14:35:22+00:00",
      "match_type": "interpolated",
      "location": {
        "latitude": 40.7128,
        "longitude": -74.0060,
        "altitude": 10.5
      },
      "time_delta_seconds": 12,
      "confidence": 0.95
    }
  ],
  "unmatched": [
    {
      "photo": "photos/IMG_9999.jpg",
      "capture_time": "2025-11-01T08:00:00+00:00",
      "reason": "Photo timestamp outside track time range"
    }
  ]
}
```

---

## 6. Dependencies

### 6.1 Core Strategy: ExifTool + Python Wrapper

**Decision Rationale** (based on expert guidance):
- **ExifTool** is the de-facto standard for metadata handling
- Supports all formats (JPEG, HEIC/HEIF, RAW, PNG with XMP, maker notes, sidecars)
- Bulletproof GPS writing with proper coordinate conversion
- Handles edge cases (color profiles, thumbnails, vendor-specific tags)
- Python wrappers (`pyexiftool`) provide clean API

**Trade-offs**:
- ✅ Maximum compatibility and reliability
- ✅ Future-proof (new formats, weird cameras)
- ✅ Linear interpolation built-in (`-geotag` feature)
- ⚠️ Requires ExifTool binary installation (one-time setup)
- ⚠️ Slightly slower than pure Python for simple cases (negligible for batch)

### 6.2 Python Libraries (requirements.txt)
```python
# EXIF handling (primary approach)
PyExifTool>=0.5.6      # Python wrapper for ExifTool CLI
                       # Requires exiftool binary (install separately)

# EXIF handling (fallback for simple JPEG)
piexif>=1.1.3          # Pure Python EXIF read/write (JPEG only)
Pillow>=10.0.0         # Image file handling, EXIF preservation

# HEIC/HEIF support (iOS photos)
pillow-heif>=0.14.0    # HEIF image plugin for Pillow
# pyheif>=0.7.1        # Alternative HEIC reader (requires libheif-dev)

# Track parsing
gpxpy>=1.6.0           # GPX parser (pure Python, mature)
lxml>=5.0.0            # KML XML parsing (fast, well-maintained)

# Database (optional - for SQLite DB exports)
# sqlite3 is built-in to Python 3.x

# CLI & utilities
click>=8.1.0           # Command-line interface framework
python-dateutil>=2.8.0 # Timezone handling, ISO 8601 parsing
tqdm>=4.66.0           # Progress bars for batch operations
tabulate>=0.9.0        # Report table formatting (CSV/console)

# Development & testing
pytest>=7.4.0
pytest-cov>=4.1.0
```

### 6.3 System Requirements

**Python**:
- Python 3.9+ (for modern type hints, `datetime.fromisoformat()`, pattern matching)

**ExifTool Binary** (required):
- **Installation**:
  - **Linux**: `sudo apt install exiftool` (Debian/Ubuntu) or `sudo yum install perl-Image-ExifTool` (RHEL/CentOS)
  - **macOS**: `brew install exiftool`
  - **Windows**: Download from [exiftool.org](https://exiftool.org/), extract `exiftool.exe` to PATH or tool directory
- **Version**: 12.15+ (required for PyExifTool wrapper compatibility)
- **Verification**: `exiftool -ver` should output version number

**Optional Native Libraries** (for HEIC support without ExifTool):
- **libheif-dev** (Linux): `sudo apt install libheif-dev`
- Not required if using ExifTool (it handles HEIC natively)

**Cross-platform**: Windows, macOS, Linux (all tested)

---

## 7. Privacy & Security Considerations

### 7.1 Privacy-First Design (Aligned with Project Philosophy)
- **No network operations**: All processing strictly local
- **No telemetry or analytics**: Zero data collection
- **Preserve source data**: Default to creating copies, not modifying originals
- **Explicit user control**: No automatic geotagging without explicit command
- **Report transparency**: Clear report of what was modified

### 7.2 Data Handling
- **Memory efficiency**: Stream large GPX/KML files, don't load entire tracks into memory
- **Temporary files**: Use OS temp directory, clean up on exit
- **Error handling**: Never silently fail; report each photo processing error
- **Validation**: Verify coordinate validity before writing EXIF (lat: -90 to 90, lon: -180 to 180)

---

## 8. Implementation Phases

### Phase 1: MVP (Core Functionality)
**Goal**: Basic GPX parsing + ExifTool integration + nearest neighbor matching

**Deliverables**:
- GPX parser (gpxpy library)
- ExifTool wrapper integration (PyExifTool)
- Nearest neighbor time matcher (pure Python)
- Basic CLI (match command, --track, --output)
- ExifTool binary detection & setup verification
- Unit tests for parsers and matchers

**Implementation Note**: Use ExifTool's native `-geotag` feature where possible, fall back to manual coordinate setting for custom interpolation.

**Time Estimate**: 2-3 days

### Phase 2: Enhanced Matching
**Goal**: Custom interpolation + multiple format support + HEIC

**Deliverables**:
- Linear interpolation matcher (custom algorithm)
- KML parser (lxml)
- SQLite database parser (built-in sqlite3)
- HEIC/HEIF support via pillow-heif or ExifTool auto-detection
- Extended CLI options (--interpolate, --max-time-delta, --format, etc.)
- Integration tests with real Tracker exports
- Format compatibility matrix validation

**ExifTool Advantage**: HEIC/HEIF/RAW handled automatically without format-specific code.

**Time Estimate**: 2-3 days

### Phase 3: Polish & Usability
**Goal**: Production-ready tool

**Deliverables**:
- JSON/CSV report generation
- Progress bars for batch processing
- Dry-run mode
- Error recovery (partial failures)
- Comprehensive README with examples
- Package distribution (PyPI optional)

**Time Estimate**: 1-2 days

### Phase 4: Advanced Features (Future)
**Potential Enhancements**:
- Motion-aware matching using activity data from DB exports
- GUI wrapper (optional)
- Reverse geocoding integration (optional, requires network - off by default)
- Track simplification (reduce GPX points for faster matching)
- Multi-track support (merge multiple GPX files)
- Video file support (extract frames, geotag, create subtitles with location)

---

## 9. Testing Strategy

### 9.1 Unit Tests
- **Parsers**: Validate parsing of sample GPX/KML files exported from Tracker
- **Matchers**: Test nearest neighbor, interpolation with synthetic data
- **EXIF**: Verify GPS tag writing correctness (decimal degrees ↔ DMS conversion)
- **Timezone**: Edge cases (DST transitions, UTC conversion)

### 9.2 Integration Tests
**Fixtures**:
1. Export real GPX/KML from Tracker Android (use dummy data generator or real walk)
2. Create test photos with known EXIF timestamps
3. Verify correct matching and GPS tag writing

**Edge Cases**:
- Photo before first track point
- Photo after last track point
- Photo during track gap (stationary period)
- Photo with existing GPS tags
- Corrupted EXIF data
- Large batches (1000+ photos)

### 9.3 Manual Validation
- Import geotagged photos into Google Photos / Apple Photos
- Verify map placement accuracy
- Cross-check interpolated locations against actual track path

---

## 10. Example Workflows

### 10.1 Vacation Photos
**Scenario**: User took 200 photos during a day trip, tracked with Tracker Android.

**Steps**:
1. Export GPX from Tracker (Settings → Export → GPX, select date range)
2. Download photos from camera/phone
3. Run tool:
   ```bash
   python -m geotagger match vacation_photos/ \
     --track trip_2025-11-02.gpx \
     --output tagged_photos/ \
     --interpolate \
     --report trip_report.json
   ```
4. Review report: check match rate, unmatched photos
5. Upload tagged photos to cloud storage (automatically displays on map)

### 10.2 Continuous Tracking
**Scenario**: User tracks daily commute for a week, wants to geotag daily phone snapshots.

**Steps**:
1. Export GPX covering entire week
2. Organize photos by day
3. Run tool with `--preserve-originals` to keep backups:
   ```bash
   python -m geotagger match phone_photos/ \
     --track week_2025-10-28_to_11-03.gpx \
     --preserve-originals \
     --verbose
   ```

### 10.3 Event Photography
**Scenario**: Professional photographer at outdoor event, wants precise locations.

**Steps**:
1. Export SQLite database (full accuracy metadata)
2. Use database format for maximum precision:
   ```bash
   python -m geotagger match event_photos/ \
     --track export.db \
     --format db \
     --interpolate \
     --max-interpolation-gap 5 \
     --report event_geotag_report.csv
   ```
3. Review CSV report in spreadsheet for quality control

---

## 11. Documentation Plan

### 11.1 README.md Structure
1. **Introduction**: What the tool does, privacy guarantees
2. **Installation**: pip install, requirements
3. **Quick Start**: Basic usage example
4. **Export Guide**: How to export GPX/KML/DB from Tracker Android (with screenshots)
5. **CLI Reference**: All commands and options
6. **Matching Strategies**: Explanation of nearest vs. interpolated
7. **Troubleshooting**: Common issues (timezone problems, no matches, etc.)
8. **FAQ**: "Is this safe?", "What about existing GPS tags?", etc.

### 11.2 Code Documentation
- Docstrings for all public functions/classes (Google style)
- Type hints throughout (PEP 484)
- Inline comments for complex algorithms (interpolation math, DMS conversion)

---

## 12. Compatibility Matrix

| Tracker Export | Python Tool Support | Priority | Notes |
|----------------|---------------------|----------|-------|
| GPX 1.1        | ✅ Full             | High     | Primary format, well-supported libraries |
| KML 2.2        | ✅ Full             | Medium   | Secondary format, XML parsing |
| SQLite DB      | ✅ Full             | Low      | Advanced users, full metadata access |
| JSON (future)  | 🔄 Planned          | Low      | If Tracker adds JSON export |

| Photo Format   | ExifTool Support    | Pure Python (piexif) | Notes |
|----------------|---------------------|----------------------|-------|
| JPEG/JPG       | ✅ Full             | ✅ Full              | Primary target, universal EXIF support |
| HEIF/HEIC      | ✅ Full             | ⚠️ Limited (pillow-heif) | iOS default, ExifTool recommended |
| PNG            | ✅ Full (XMP)       | ❌ Not supported     | PNG uses XMP metadata, ExifTool handles it |
| RAW (DNG, CR2, NEF, ARW) | ✅ Full   | ❌ Not supported     | ExifTool preserves maker notes safely |
| TIFF           | ✅ Full             | ⚠️ Basic             | ExifTool handles multi-page TIFF |
| WebP           | ✅ Full             | ❌ Not supported     | Modern format, ExifTool support only |

**ExifTool Advantages Over Pure Python**:
- Preserves color profiles, thumbnails, maker notes automatically
- Handles sidecar files (`.xmp`, `.thm`)
- Built-in coordinate conversion (decimal ↔ DMS)
- Safe metadata writes (backup original, verify changes)
- Consistent behavior across 500+ file formats

**When Pure Python (piexif) is Sufficient**:
- Simple JPEG-only workflows
- Embedded systems without ExifTool binary
- Minimal dependency requirements (air-gapped environments)

---

## 13. Success Criteria

### 13.1 Functional
- [ ] Parse GPX exports from Tracker without errors
- [ ] Match ≥95% of photos within track time range (±5 min tolerance)
- [ ] Write valid GPS EXIF tags readable by standard photo viewers
- [ ] Process 1000 photos in <30 seconds (on modern hardware)
- [ ] Preserve all existing EXIF data (no corruption)

### 13.2 Usability
- [ ] Single command for common use case (no complex config)
- [ ] Clear error messages (no stack traces for user errors)
- [ ] Progress indication for batch operations
- [ ] Comprehensive match report (JSON/CSV)

### 13.3 Quality
- [ ] 100% test coverage for core matchers and parsers
- [ ] No dependencies with known security vulnerabilities
- [ ] Cross-platform compatibility (Windows, macOS, Linux tested)
- [ ] Zero network calls (privacy audit via network monitor)

---

## 14. Open Questions & Decisions

### 14.1 Interpolation Default
**Question**: Should interpolation be enabled by default or opt-in?

**Options**:
- **A**: Default ON (better accuracy for most users)
- **B**: Default OFF (conservative, explicit opt-in)

**Recommendation**: **B (Default OFF)** - Aligns with privacy-first philosophy (don't infer data not explicitly collected). Users wanting higher accuracy can enable with `--interpolate`.

### 14.2 Backup Strategy
**Question**: How to handle backups when modifying in-place?

**Options**:
- **A**: Always create .original backups
- **B**: Only with `--preserve-originals` flag
- **C**: Never modify in-place, require `--output` dir

**Recommendation**: **B** - Default to in-place modification (simpler workflow), but offer explicit backup flag. Warn user if flag not provided.

### 14.3 Multi-Track Handling
**Question**: How to handle multiple GPX files for a long time period?

**Options**:
- **A**: Require user to manually merge GPX files first
- **B**: Accept multiple `--track` arguments, merge internally
- **C**: Auto-discover all GPX files in a directory

**Recommendation**: **B (Phase 4)** - Start with single track (MVP), add multi-track support later with `--track file1.gpx --track file2.gpx`.

### 14.4 Timezone Handling
**Question**: How to handle photos without timezone EXIF offset?

**Options**:
- **A**: Assume UTC (safest)
- **B**: Assume local system timezone
- **C**: Require `--timezone` flag
- **D**: Infer from track location (reverse geocode timezone - violates privacy)

**Recommendation**: **A + C** - Default to UTC assumption with clear warning. Allow explicit override with `--timezone`. Never auto-infer requiring network.

---

## 15. Alignment with Project Philosophy

This tool design adheres to Tracker Android's core principles:

✅ **Privacy-first**: Zero network operations, all processing local  
✅ **Opinionated simplicity**: One clear default workflow, minimal flags  
✅ **Explicit user control**: No automatic actions, require explicit command  
✅ **Reliability over novelty**: Start with proven algorithms (nearest neighbor), add interpolation carefully  
✅ **Consistency**: CLI design mirrors standard Unix tools (input, output, flags)  
✅ **Battery & performance**: Efficient streaming parsers, O(n log n) matching (not O(n²))  
✅ **Plain language**: Clear error messages, no jargon (e.g., "No location found within 5 minutes of photo time")

---

## 16. Next Steps

1. **Validate Plan**: Review with stakeholders/users for feedback
2. **Setup Repository**: Create `photo-geotagger` repo (or subdirectory in Tracker-Android)
3. **Implement Phase 1**: GPX parser + nearest neighbor + basic CLI
4. **Generate Test Data**: Use Tracker's `DummyDataSeeder.kt` to create realistic GPX exports
5. **Iterate**: Test with real-world photos, refine matching algorithms
6. **Document**: Write comprehensive README with screenshots from Tracker export flow
7. **Distribute**: Package for PyPI (optional), provide install instructions

---

**Document Version**: 1.0  
**Last Updated**: 2025-11-02  
**Status**: Planning Complete, Ready for Implementation
